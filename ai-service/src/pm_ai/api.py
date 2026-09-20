"""HTTP 接口（FastAPI）+ 简易前端页面。

接口清单：
  GET  /                  前端页面（文档库 + 上传队列 + 问答）
  GET  /health            服务与配置自检（`?with_llm=true` 附带模型连通性；平台 OCR 状态默认带上）
  POST /analyze           **核心**：上传文件 → 抽取 → 返回带「来源 + 置信度」的 markdown
  POST /upload-tasks      **多文件异步上传**：提交即返回，后台解析并实时上报进度（网页用）
  GET  /upload-tasks      任务队列（含进度、阶段、耗时）
  GET  /upload-tasks/{id} 单个任务详情（页级明细 = 过程性内容）
  DELETE /upload-tasks/{id}  取消进行中的任务 / 移除已结束的任务（`?remove=true`）
  POST /documents         同步上传入库（CLI/脚本、单文件快速验证）
  GET  /documents         已入库文档列表
  GET  /documents/{id}    文档详情（页级明细 + 确定性校验结果）
  DELETE /documents/{id}  删除文档
  POST /chat              基于文档库问答（模型自行调用检索/计算工具）
  POST /ocr/file          只做识别，返回文本（平台 OCR，不调模型）
  POST /ocr/pdf-info      只判断 PDF 是文本型还是扫描件（摸底用）

## 一个容易踩的坑：阻塞接口不要写成 `async def`

OCR 与大模型调用都是**同步阻塞**操作。若把它们放进 `async def` 且没有 `await`，
会**阻塞整个事件循环**——一个人上传大文件，其他人全部卡住。
因此这些接口一律用同步 `def`，由 FastAPI 自动丢进线程池执行。

（上传任务接口虽然返回很快，但内部也只是"登记任务"，真正的解析在 `tasks.py` 的
线程池里跑，同样不占用事件循环。）
"""

from __future__ import annotations

import shutil
import uuid
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import __version__, analyze, document, llm_client, pdf_utils, platform_ocr, qa
from .config import settings
from .store import store
from .tasks import tasks

app = FastAPI(title="PM AI Service", version=__version__)

# 允许跨源访问。
# 为什么需要：本服务常被**不同源**的页面调用——IDE 内置预览、主系统前端（8088）、
# 或本地打开的 HTML 文件。没有 CORS 时浏览器会直接拦掉请求，页面表现为"连不上服务"。
# 内网工具、无用户凭据、数据不出内网，因此放开来源；
# 将来经 nginx 与主系统同源部署后，这段可以收紧甚至移除。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

MAX_UPLOAD_MB = 300

STATIC_DIR = Path(__file__).resolve().parents[2] / "static"


def _save_tmp(upload: UploadFile) -> Path:
    """把上传文件落到临时区（大文件不驻留内存）。"""
    tmp_dir = settings.work_dir / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    name = Path(upload.filename or "upload.bin").name
    dest = tmp_dir / f"{uuid.uuid4().hex}_{name}"
    with dest.open("wb") as fh:
        shutil.copyfileobj(upload.file, fh, length=1024 * 1024)
    return dest


def _guard_size(upload: UploadFile) -> None:
    if upload.size and upload.size > MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"文件超过 {MAX_UPLOAD_MB}MB")


# ── 前端页面 ─────────────────────────────────────────────────────

if STATIC_DIR.is_dir():
    app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


@app.get("/", include_in_schema=False)
def index():
    """简易前端页面。"""
    page = STATIC_DIR / "index.html"
    if page.is_file():
        return FileResponse(page)
    return JSONResponse(
        {
            "code": 0,
            "service": "pm-ai-service",
            "version": __version__,
            "hint": "前端页面未随镜像提供（static/index.html 不存在），可直接访问 /docs 使用接口。",
        }
    )


# ── 自检 ────────────────────────────────────────────────────────


@app.get("/health")
def health(with_llm: bool = False, with_ocr: bool = True) -> JSONResponse:
    """服务自检。

    - `with_llm=true` 顺带探测大模型连通性（约 2s，失败约 15s）
    - `with_ocr=false` 跳过平台 OCR 探测（探测结果有缓存，正常很快）
    """
    payload = {
        "code": 0,
        "service": "pm-ai-service",
        "version": __version__,
        "config": settings.summary(),
    }
    # 先探测（带缓存）平台 OCR 是否可用
    if with_ocr:
        h = platform_ocr.health(timeout=3)
        payload["ocr"] = {
            "ok": h.ok,
            "workers": h.workers,
            "idle": h.idle,
            "options": h.options,
            "detail": h.detail,
        }
    # 引擎只剩平台一条路（本地 OCR 兜底已于 2026-09-18 移除），故这里恒为 "platform"。
    # 字段保留是为了不让读取它的主系统/前端拿到 KeyError；真实的连通性看上面的 ocr.ok。
    payload["provider"] = "platform"
    if with_llm:
        ok, detail = llm_client.ping()
        payload["llm"] = {"ok": ok, "detail": detail}
    return JSONResponse(payload)


# ── 核心：文件分析（抽取 + 来源标注 + 置信度）──────────────────────


@app.post("/analyze")
def analyze_file(
    file: UploadFile = File(...),
    instruction: str = Form(default=""),
    dpi: int = Form(default=0),
):
    """上传文件 → 调用千问抽取 → 返回 markdown 结果。

    输出中的每条信息都带**来源页码**与**置信度**（详见 `prompts.py` 的铁律）。
    文件类型自动判断：文本型 PDF 走文本层，扫描件/图片走 OCR。
    """
    path = _save_tmp(file)
    try:
        _guard_size(file)
        result = analyze.analyze(path, instruction=instruction, dpi=dpi or None)
        payload = result.to_payload()
        payload["file"] = Path(file.filename or path.name).name
        return {"code": 0, "data": payload}
    finally:
        path.unlink(missing_ok=True)


# ── 只看识别结果（不调模型）──────────────────────────────────────


@app.post("/ocr/pdf-info")
def pdf_info(file: UploadFile = File(...)):
    """快速判断 PDF 类型与规模——用于附件构成摸底。"""
    path = _save_tmp(file)
    try:
        if path.suffix.lower() != ".pdf":
            raise HTTPException(status_code=400, detail="该接口仅接受 PDF")
        info = pdf_utils.read_pdf(path)
        return {
            "code": 0,
            "data": {
                # 返回**原始文件名**，不要把内部的临时名（带 uuid 前缀）暴露给调用方
                "file": Path(file.filename or path.name).name,
                "pages": info.page_count,
                "text_chars": info.text_chars,
                "chars_per_page": round(info.chars_per_page, 1),
                "kind": info.kind,
                "error": info.error,
            },
        }
    finally:
        path.unlink(missing_ok=True)


@app.post("/ocr/file")
def ocr_file(
    file: UploadFile = File(...),
    dpi: int = Form(default=0),
    force_ocr: bool = Form(default=False),
):
    """只识别，不调模型（摸底 / 排障 / 对比用）。

    - **文本型 PDF**：默认直接返回文本层（快、准），不跑 OCR；
    - **扫描件 / 图片**：渲染后走**平台 OCR**（PaddleOCR-VL）；
    - `force_ocr=true` 可对文本型 PDF 也强制走 OCR（用于对比两者差异）；
    - `dpi` 可覆盖平台默认渲染 DPI（默认 150，见 `OCR_PLATFORM_DPI`）——
      它只用于排障与印章"两遍法"，提高 DPI 并不会改善正文识别（原因见 `document._render`）。

    实现直接复用 `document.read_document`，与 `/documents`、`/upload-tasks` 走**同一条链路**：
    这样"摸底看到的识别结果"与"入库后的识别结果"必然一致，否则摸底会给出假象
    （此前本接口固定走本地引擎，已经因为两套实现而产生过误导）。

    ⚠️ 平台 OCR **不返回置信度**，所以响应里**没有** `avg_score` /
    `per_page_scores` / `low_confidence_count` 这类字段。本地引擎已于 2026-09-18 移除，
    没有本地分数可以给，也**不能编一个出来**——那只会让"识别质量"这件事失真。
    判断识别结果可不可信，请看 `checks`（确定性校验）与 `failed_pages`。
    """
    path = _save_tmp(file)
    try:
        _guard_size(file)
        doc = document.read_document(path, dpi=dpi or None, force_ocr=force_ocr)
        if doc.error:
            raise HTTPException(status_code=400, detail=doc.error)

        return {
            "code": 0,
            "data": {
                "kind": doc.kind,
                "pages": doc.page_count,
                "text": doc.text,
                "engine": doc.engine,
                "dpi": doc.dpi,
                "image_format": doc.image_format,
                "elapsed": round(doc.elapsed, 2),
                # 没有本地兜底后，失败页会如实留空——必须让调用方看得见，别当成"这页本来就没字"
                "failed_pages": doc.failed_pages,
                "notes": doc.notes,
            },
        }
    finally:
        path.unlink(missing_ok=True)


# ── 文档库：上传 / 列表 / 删除 ────────────────────────────────────
#
# 与 /ocr/file 的区别：/ocr/file 只识别并返回文本（不保存）；
# /documents 会把解析结果**持久化**，作为后续问答的检索基础。
# 它同时承担"解析"与"入库"两件事，扫描件可能耗时数十秒到数分钟。


@app.post("/documents")
def upload_document(file: UploadFile = File(...), dpi: int = Form(default=0)):
    """上传文档 → 解析（文本层或 OCR）→ 入库，供问答与检索使用。"""
    path = _save_tmp(file)
    try:
        _guard_size(file)
        doc = document.read_document(path, dpi=dpi or None)
        if doc.error:
            raise HTTPException(status_code=400, detail=doc.error)
        if not doc.text.strip():
            raise HTTPException(
                status_code=400,
                detail="未从文件中提取到任何文本。若为扫描件，可能是清晰度过低；"
                "可提高渲染 DPI（`.env` 的 `OCR_PLATFORM_DPI`，用于排障/印章两遍法）"
                "或确认平台 OCR 是否可用（`/health`）后重试。",
            )
        stored = store.save(
            filename=Path(file.filename or path.name).name,
            doc=doc,
            size_bytes=file.size or path.stat().st_size,
        )
        return {"code": 0, "data": stored.meta()}
    finally:
        path.unlink(missing_ok=True)


@app.get("/documents")
def list_documents():
    """列出已入库的文档（不含全文，避免响应过大）。"""
    return {"code": 0, "data": store.list()}


@app.delete("/documents/{doc_id}")
def delete_document(doc_id: str):
    """删除一份文档。"""
    if not store.delete(doc_id):
        raise HTTPException(status_code=404, detail="文档不存在")
    return {"code": 0, "data": {"deleted": doc_id}}


@app.get("/documents/{doc_id}")
def get_document(doc_id: str):
    """单份文档详情：**含页级明细**（页内区域、质量信号、耗时）与确定性校验结果。"""
    doc = store.get(doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="文档不存在")
    data = doc.to_dict()
    data["page_quality"] = doc.page_quality()
    data["review_pages"] = doc.review_pages
    data["check_summary"] = doc.check_summary
    return {"code": 0, "data": data}


# ── 上传任务队列：多文件、有进度、可取消 ───────────────────────────
#
# 与 `POST /documents` 的分工：
#   /documents      —— **同步**：解析完才返回（CLI/脚本、单文件快速验证用）
#   /upload-tasks   —— **异步**：提交即返回，后台解析并实时上报进度（网页用）
# 两者最终写进同一个文档库，所以用哪种都不会"两套数据"。


@app.post("/upload-tasks")
def create_upload_tasks(
    files: list[UploadFile] = File(...),
    dpi: int = Form(default=0),
):
    """一次提交**多个**文件，立刻返回任务列表；解析在后台进行。

    前端据此做队列：一次选 5 个文件＝5 个并行任务，各自有进度、可单独取消。
    """
    for f in files:
        _guard_size(f)
    created = []
    for f in files:
        path = _save_tmp(f)
        task = tasks.submit(
            path,
            Path(f.filename or path.name).name,
            f.size or path.stat().st_size,
            dpi=dpi or None,
        )
        created.append(task.to_dict(with_pages=False))
    return {"code": 0, "data": created}


@app.get("/upload-tasks")
def list_upload_tasks():
    """任务列表（最近在前，不含页级明细——明细走单个任务接口）。"""
    return {"code": 0, "data": tasks.list()}


@app.get("/upload-tasks/{task_id}")
def get_upload_task(task_id: str):
    """单个任务详情：**含页级进度明细**、阶段耗时、校验结果（前端"过程性内容"的数据源）。"""
    task = tasks.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return {"code": 0, "data": task.to_dict(with_pages=True)}


@app.delete("/upload-tasks/{task_id}")
def cancel_upload_task(task_id: str, remove: bool = False):
    """取消一个进行中的任务；`remove=true` 时改为从列表移除已结束的任务。"""
    if remove:
        if not tasks.remove(task_id):
            raise HTTPException(status_code=400, detail="任务不存在或仍在进行中")
        return {"code": 0, "data": {"removed": task_id}}
    if not tasks.cancel(task_id):
        raise HTTPException(status_code=400, detail="任务不存在或已结束")
    return {"code": 0, "data": {"cancelled": task_id}}


# ── 问答：模型自行调用检索工具 ─────────────────────────────────────


class ChatIn(BaseModel):
    question: str
    doc_ids: list[str] | None = None
    """限定检索范围；为空则检索全部已上传文档。"""
    history: list[dict] | None = None
    """历史问答（只含 user/assistant 纯文本，见 qa.py）。"""


@app.post("/chat")
def chat_ask(payload: ChatIn):
    """基于已上传文档回答问题。

    模型会**自行调用工具**（检索段落、读整页、精确计算）收集依据，
    再给出带来源标注的答案；返回的 `trace` 记录了它查了什么，便于核对。
    """
    result = qa.ask(
        payload.question,
        doc_ids=payload.doc_ids,
        history=payload.history,
    )
    return {"code": 0, "data": result.to_dict()}


def run() -> None:
    """`pm-ai` 命令入口。"""
    import uvicorn

    uvicorn.run("pm_ai.api:app", host=settings.host, port=settings.port)
