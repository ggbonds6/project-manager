"""HTTP 接口（FastAPI）。

本期只做最小集，目的是"能验证 OCR 效果"：
  GET  /health          服务与配置自检（含模型连通性）
  POST /ocr/file        上传 PDF/图片，返回识别文本
  POST /ocr/pdf-info    只看 PDF 是文本型还是扫描件（快速摸底用）

复杂能力（字段抽取、向量检索、问答）后续按需追加，接口保持向后兼容。
"""

from __future__ import annotations

import shutil
import uuid
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from . import __version__, llm_client, ocr_engine, pdf_utils
from .config import settings

app = FastAPI(title="PM AI Service", version=__version__)

MAX_UPLOAD_MB = 300


def _save_tmp(upload: UploadFile) -> Path:
    """把上传文件落到临时区（大文件不驻留内存）。"""
    tmp_dir = settings.work_dir / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    name = Path(upload.filename or "upload.bin").name
    dest = tmp_dir / f"{uuid.uuid4().hex}_{name}"
    with dest.open("wb") as fh:
        shutil.copyfileobj(upload.file, fh, length=1024 * 1024)
    return dest


@app.get("/health")
def health(with_llm: bool = False) -> JSONResponse:
    """服务自检。`with_llm=true` 时顺带探测大模型连通性（较慢）。"""
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


@app.post("/ocr/pdf-info")
async def pdf_info(file: UploadFile = File(...)):
    """快速判断 PDF 类型与规模——用于 A1 附件构成摸底。"""
    path = _save_tmp(file)
    try:
        if path.suffix.lower() != ".pdf":
            raise HTTPException(status_code=400, detail="该接口仅接受 PDF")
        info = pdf_utils.read_pdf(path)
        return {
            "code": 0,
            "data": {
                "file": path.name,
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
async def ocr_file(
    file: UploadFile = File(...),
    dpi: int = Form(default=0),
    force_ocr: bool = Form(default=False),
):
    """识别 PDF 或图片。

    - **文本型 PDF**：默认直接返回文本层（快、准），不跑 OCR；
    - **扫描件 / 图片**：渲染后用 OCR 识别；
    - `force_ocr=true` 可对文本型 PDF 也强制走 OCR（用于对比两者差异）。
    """
    path = _save_tmp(file)
    try:
        if file.size and file.size > MAX_UPLOAD_MB * 1024 * 1024:
            raise HTTPException(status_code=413, detail=f"文件超过 {MAX_UPLOAD_MB}MB")

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

            images = pdf_utils.render_pages(path, dpi=use_dpi)
            merged, per_page = ocr_engine.ocr_pages(images)
            try:
                shutil.rmtree(images[0].parent if images else "", ignore_errors=True)
            except Exception:  # noqa: BLE001 - 清理失败不影响结果
                pass
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


def run() -> None:
    """`pm-ai` 命令入口。"""
    import uvicorn

    uvicorn.run("pm_ai.api:app", host=settings.host, port=settings.port)
