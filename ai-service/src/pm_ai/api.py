"""HTTP 接口（FastAPI）+ 简易前端页面。

接口清单：
  GET  /                  简易前端（上传 → 分析 → markdown 结果）
  GET  /health            服务与配置自检（?with_llm=true 附带模型连通性）
  POST /analyze           **核心**：上传文件 → 抽取 → 返回带「来源 + 置信度」的 markdown
  POST /ocr/file          只做识别，返回文本（含识别置信度、低置信行数）
  POST /ocr/pdf-info      只判断 PDF 是文本型还是扫描件（摸底用）

## 一个容易踩的坑：阻塞接口不要写成 `async def`

OCR 与大模型调用都是**同步阻塞**操作。若把它们放进 `async def` 且没有 `await`，
会**阻塞整个事件循环**——一个人上传大文件，其他人全部卡住。
因此这些接口一律用同步 `def`，由 FastAPI 自动丢进线程池执行。
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

from . import __version__, analyze, document, llm_client, ocr_engine, pdf_utils, qa
from .config import settings
from .store import store

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
    return JSONResponse({
        "code": 0,
        "service": "pm-ai-service",
        "version": __version__,
        "hint": "前端页面未随镜像提供（static/index.html 不存在），可直接访问 /docs 使用接口。",
    })


# ── 自检 ────────────────────────────────────────────────────────

@app.get("/health")
def health(with_llm: bool = False) -> JSONResponse:
    """服务自检。`with_llm=true` 时顺带探测大模型连通性（约 2s，失败约 15s）。"""
    payload = {
        "code": 0,
        "service": "pm-ai-service",
        "version": __version__,
        "config": settings.summary(),
    }
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
    """只识别，不调模型。

    - **文本型 PDF**：默认直接返回文本层（快、准），不跑 OCR；
    - **扫描件 / 图片**：渲染后用 OCR 识别；
    - `force_ocr=true` 可对文本型 PDF 也强制走 OCR（用于对比两者差异）。
    """
    path = _save_tmp(file)
    try:
        _guard_size(file)
        use_dpi = dpi or settings.ocr_dpi
        suffix = path.suffix.lower()

        if suffix == ".pdf":
            info = pdf_utils.read_pdf(path)
            if info.error:
                raise HTTPException(status_code=400, detail=f"PDF 解析失败：{info.error}")

            if not info.is_scanned(settings.scanned_char_threshold) and not force_ocr:
                text = pdf_utils.extract_text(path)
                return {
                    "code": 0,
                    "data": {
                        "kind": "text_pdf",
                        "pages": info.page_count,
                        "text": text,
                        "engine": "text-layer",
                        "elapsed": 0,
                    },
                }

            # 渲染图落到 work/（输入目录在容器里是只读挂载）
            render_dir = settings.work_dir / "pages" / uuid.uuid4().hex
            try:
                images = pdf_utils.render_pages(path, dpi=use_dpi, out_dir=render_dir)
                merged, per_page = ocr_engine.ocr_pages(images)
            finally:
                shutil.rmtree(render_dir, ignore_errors=True)

            return {
                "code": 0,
                "data": {
                    "kind": "scanned",
                    "pages": info.page_count,
                    "text": merged.text,
                    "engine": "ocr",
                    "dpi": use_dpi,
                    "elapsed": round(merged.elapsed, 2),
                    "avg_score": round(merged.avg_score, 3),
                    "low_confidence_count": len(merged.low_confidence),
                    "per_page_scores": [round(p.avg_score, 3) for p in per_page],
                },
            }

        if suffix in pdf_utils.IMAGE_EXTS:
            result = ocr_engine.ocr_image(path)
            return {
                "code": 0,
                "data": {
                    "kind": "image",
                    "text": result.text,
                    "engine": "ocr",
                    "elapsed": round(result.elapsed, 2),
                    "avg_score": round(result.avg_score, 3),
                    "low_confidence_count": len(result.low_confidence),
                },
            }

        raise HTTPException(status_code=400, detail=f"不支持的格式：{suffix}")
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
                       "可提高 OCR_DPI 后重试。",
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
