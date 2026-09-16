"""文档 → 结构化文本（**带页码 + 识别置信度**）。

## 为什么要按页切分并带页码

提示词要求"每条信息标注来源页码"。如果喂给模型的是一坨扁平文本，
模型根本不知道某句话在第几页——只能编造页码，或者干脆不标。
**按页切分并显式写 `【第 N 页】`，来源标注才可能真实可信。**

这也是"可核验"的基础：审计场景里，一条无法回溯到原文的结论等于没有价值。

## 两类置信度要分清（重要）

| 置信度 | 来源 | 含义 |
| --- | --- | --- |
| `PageText.confidence` | OCR 引擎 | 该页**识别质量**；文本层为 `None`（不涉及识别，≠ 不可靠） |
| 输出里每条信息的置信度 | 大模型自评 | 该条**理解与摘录**的把握 |

⚠️ 实测教训：OCR 平均置信度 0.97 的文件里，金额仍被识别错（千分位逗号→小数点）。
**识别置信度高 ≠ 内容正确**，两者都不能替代人工复核。
"""

from __future__ import annotations

import shutil
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from . import ocr_engine, pdf_utils
from .config import settings


@dataclass
class PageText:
    page_no: int
    text: str
    confidence: float | None = None
    """该页 OCR 平均置信度。`None` 表示走的是文本层（不涉及识别）。"""


@dataclass
class DocumentText:
    kind: str  # text_pdf | scanned | image | error
    pages: list[PageText] = field(default_factory=list)
    engine: str = "text-layer"
    elapsed: float = 0.0
    dpi: int | None = None
    error: str | None = None

    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def text(self) -> str:
        """纯文本（无页码标记）。"""
        return "\n".join(p.text for p in self.pages)

    @property
    def labeled_text(self) -> str:
        """**带页码标记的原文**——喂给模型的就是它，来源标注的前提。"""
        blocks: list[str] = []
        for p in self.pages:
            body = p.text.strip() or "（本页未识别到文本）"
            blocks.append(f"【第 {p.page_no} 页】\n{body}")
        return "\n\n".join(blocks)

    @property
    def char_count(self) -> int:
        return len(self.text)

    @property
    def empty_pages(self) -> list[int]:
        return [p.page_no for p in self.pages if not p.text.strip()]

    @property
    def low_confidence_pages(self) -> list[int]:
        """识别质量偏低（<0.85）的页号——人工复核优先看这些页。"""
        return [
            p.page_no
            for p in self.pages
            if p.confidence is not None and p.confidence < 0.85
        ]

    @property
    def avg_confidence(self) -> float | None:
        vals = [p.confidence for p in self.pages if p.confidence is not None]
        return round(sum(vals) / len(vals), 3) if vals else None

    def summary(self) -> dict:
        """供接口返回元信息使用。"""
        return {
            "kind": self.kind,
            "pages": self.page_count,
            "engine": self.engine,
            "dpi": self.dpi,
            "chars": self.char_count,
            "avg_confidence": self.avg_confidence,
            "low_confidence_pages": self.low_confidence_pages,
            "empty_pages": self.empty_pages,
            "elapsed": round(self.elapsed, 2),
        }


def read_document(path: str | Path, dpi: int | None = None,
                  force_ocr: bool = False) -> DocumentText:
    """读文档 → 结构化文本，自动区分文本型 / 扫描件。

    - 文本型 PDF：逐页取文本层（快且准，不消耗 OCR 算力）
    - 扫描件 / 图片：逐页 OCR，并记录每页置信度
    """
    p = Path(path)
    use_dpi = dpi or settings.ocr_dpi
    suffix = p.suffix.lower()

    if suffix == ".pdf":
        info = pdf_utils.read_pdf(p)
        if info.error:
            return DocumentText(kind="error", error=f"PDF 解析失败：{info.error}")

        if not info.is_scanned(settings.scanned_char_threshold) and not force_ocr:
            pages = [
                PageText(page_no=i + 1, text=t, confidence=None)
                for i, t in enumerate(pdf_utils.extract_page_texts(p))
            ]
            return DocumentText(kind="text_pdf", pages=pages, engine="text-layer")

        return _ocr_pdf(p, use_dpi)

    if suffix in pdf_utils.IMAGE_EXTS:
        result = ocr_engine.ocr_image(p)
        page = PageText(
            page_no=1,
            text=result.text,
            confidence=round(result.avg_score, 3) if result.lines else None,
        )
        return DocumentText(kind="image", pages=[page], engine="ocr",
                            elapsed=round(result.elapsed, 2))

    return DocumentText(kind="error", error=f"不支持的格式：{suffix}")


def _ocr_pdf(path: Path, dpi: int) -> DocumentText:
    """扫描件：渲染每页 → 逐页 OCR，保留页号与逐页置信度。"""
    # 渲染图必须落到 work/（输入目录在容器里是只读挂载）
    out_dir = settings.work_dir / "pages" / uuid.uuid4().hex
    try:
        images = pdf_utils.render_pages(path, dpi=dpi, out_dir=out_dir)
        merged, per_page = ocr_engine.ocr_pages(images)
        pages = [
            PageText(
                page_no=i + 1,
                text=r.text,
                confidence=round(r.avg_score, 3) if r.lines else None,
            )
            for i, r in enumerate(per_page)
        ]
        return DocumentText(kind="scanned", pages=pages, engine="ocr",
                            elapsed=round(merged.elapsed, 2), dpi=dpi)
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)
