"""文档 → 结构化文本（**带页码、页内区域、识别来源、质量信号**）。

## 为什么要按页切分并带页码

提示词要求"每条信息标注来源页码"。如果喂给模型的是一坨扁平文本，
模型根本不知道某句话在第几页——只能编造页码，或者干脆不标。
**按页切分并显式写 `【第 N 页】`，来源标注才可能真实可信。**

这也是"可核验"的基础：审计场景里，一条无法回溯到原文的结论等于没有价值。

## 出处：从"页码"升级到"页内区域"

平台 OCR（PaddleOCR-VL）返回的 `blocks` 带 **bbox 像素坐标**，所以出处可以比页码更细：
`[P2]` 之外还能定位到"第 2 页上半部的那个表格"。这是换引擎白捡的能力。

## 置信度：三层，**不要混成一个数字**

| 层 | 来源 | 性质 |
| --- | --- | --- |
| 识别层 | 本地引擎给逐页分（平台**不返回**置信度） | 机器分，实测**不可靠**（0.97 也会错） |
| 校验层 | `checks.py` 的确定性检查（大小写金额互校等） | **可复现**，这是最硬的一层 |
| 理解层 | 模型自评 0–1 | 主观，只用来排序复核优先级 |

⚠️ 实测教训：OCR 平均置信度 0.97 的文件里，金额仍被识别错（千分位逗号→小数点）。
**识别置信度高 ≠ 内容正确**，三层都不能替代人工复核。
"""

from __future__ import annotations

import shutil
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import checks, ocr_engine, pdf_utils, platform_ocr
from .config import settings

ProgressFn = Callable[[str, int, int], None]
"""进度回调：`(阶段, 已完成, 总数)`。阶段取值：`render` / `ocr` / `ocr-fallback` / `check`。"""


@dataclass
class PageText:
    page_no: int
    text: str
    confidence: float | None = None
    """**识别层**置信度。仅本地引擎给；平台 OCR 与文本层为 `None`（不代表不可靠，是"没有这个信息"）。"""
    source: str = ""
    """这一页实际由谁识别：`text-layer` / `platform` / `rapid` / `paddle`。"""
    blocks: list[dict] = field(default_factory=list)
    """版面块（平台引擎）：`{label, content, bbox}`——出处定位与质量信号的来源。"""
    elapsed: float = 0.0
    error: str = ""
    note: str = ""
    """补充说明（例：平台失败后由本地引擎补跑）。"""

    # ── 质量信号：比"一个分数"更容易解释，也更容易动作化 ──
    @property
    def chars(self) -> int:
        return len(self.text)

    @property
    def is_empty(self) -> bool:
        return not self.text.strip()

    def _count_label(self, label: str) -> int:
        return sum(1 for b in self.blocks if b.get("label") == label)

    @property
    def table_count(self) -> int:
        n = self._count_label("table")
        return n if n else self.text.lower().count("<table")

    @property
    def seal_count(self) -> int:
        """检测到的印章数。**不开 `OCR_SEAL` 时内容为空**，但数量本身即"这页有章"的证据。"""
        return self._count_label("seal")

    def blocks_brief(self) -> list[dict]:
        """精简块信息（保留 label + bbox 用于区域定位，丢掉 content 以免存储膨胀）。"""
        out: list[dict] = []
        for b in self.blocks:
            item = {"label": b.get("label")}
            if b.get("bbox"):
                item["bbox"] = b["bbox"]
            out.append(item)
        return out

    def quality(self) -> dict:
        return {
            "page_no": self.page_no,
            "chars": self.chars,
            "elapsed": round(self.elapsed, 2),
            "source": self.source,
            "confidence": self.confidence,
            "empty": self.is_empty,
            "tables": self.table_count,
            "seals": self.seal_count,
            "error": self.error,
            "note": self.note,
        }


@dataclass
class DocumentText:
    kind: str  # text_pdf | scanned | image | error
    pages: list[PageText] = field(default_factory=list)
    engine: str = "text-layer"
    provider: str = ""
    """配置层面解析出来的引擎（`platform` / `rapid` / `text-layer`）。"""
    elapsed: float = 0.0
    dpi: int | None = None
    image_format: str = ""
    stages: dict = field(default_factory=dict)
    """阶段耗时：`{"render": 2.1, "ocr": 24.0, "check": 0.01}`——"时间花在哪"要看得见。"""
    checks: list[dict] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    error: str | None = None

    # ── 派生 ──
    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def text(self) -> str:
        """纯文本（无页码标记）。"""
        return "\n".join(p.text for p in self.pages)

    @property
    def labeled_text(self) -> str:
        """**带页码标记的原文**——喂给模型的就是它，来源标注的前提。

        页头会带上该页的块构成（含表格 / 含印章），让模型知道"这段是表格里的"，
        标注来源时能给到更细的粒度。
        """
        blocks: list[str] = []
        for p in self.pages:
            body = p.text.strip() or "（本页未识别到文本）"
            blocks.append(f"{page_header(p)}\n{body}")
        return "\n\n".join(blocks)

    @property
    def char_count(self) -> int:
        return len(self.text)

    @property
    def empty_pages(self) -> list[int]:
        return [p.page_no for p in self.pages if p.is_empty]

    @property
    def failed_pages(self) -> list[int]:
        return [p.page_no for p in self.pages if p.error]

    @property
    def low_confidence_pages(self) -> list[int]:
        """识别质量偏低（<0.85）的页——**仅本地引擎有这项**。"""
        return [p.page_no for p in self.pages
                if p.confidence is not None and p.confidence < 0.85]

    @property
    def review_pages(self) -> list[int]:
        """建议人工优先复核的页：识别失败的 + 空白 + 校验不通过的。"""
        pages = set(self.failed_pages) | set(self.empty_pages)
        for chk in self.checks:
            if chk.get("status") in {"fail", "warn"}:
                pages |= {i.get("page") for i in chk.get("items", []) if i.get("page")}
        return sorted(p for p in pages if p)

    @property
    def avg_confidence(self) -> float | None:
        vals = [p.confidence for p in self.pages if p.confidence is not None]
        return round(sum(vals) / len(vals), 3) if vals else None

    @property
    def check_summary(self) -> dict:
        counts: dict[str, int] = {}
        for c in self.checks:
            counts[c.get("status", "skip")] = counts.get(c.get("status", "skip"), 0) + 1
        return counts

    def summary(self) -> dict:
        return {
            "kind": self.kind,
            "pages": self.page_count,
            "engine": self.engine,
            "provider": self.provider,
            "dpi": self.dpi,
            "image_format": self.image_format,
            "chars": self.char_count,
            "avg_confidence": self.avg_confidence,
            "low_confidence_pages": self.low_confidence_pages,
            "empty_pages": self.empty_pages,
            "failed_pages": self.failed_pages,
            "review_pages": self.review_pages,
            "stages": self.stages,
            "checks": self.checks,
            "check_summary": self.check_summary,
            "notes": self.notes,
            "elapsed": round(self.elapsed, 2),
        }


def page_header(p: PageText) -> str:
    marks: list[str] = []
    if p.table_count:
        marks.append(f"含表格 {p.table_count} 个")
    if p.seal_count:
        marks.append(f"含印章 {p.seal_count} 处（未识别文字）")
    if p.error:
        marks.append("识别失败")
    suffix = " · " + "、".join(marks) if marks else ""
    return f"【第 {p.page_no} 页{suffix}】"


# ── 主入口 ──────────────────────────────────────────────────────

def read_document(
    path: str | Path,
    dpi: int | None = None,
    force_ocr: bool = False,
    provider: str | None = None,
    on_progress: ProgressFn | None = None,
) -> DocumentText:
    """读文档 → 结构化文本，自动区分文本型 / 扫描件。

    - **文本型 PDF**：逐页取文本层（最快最准，不消耗 OCR 算力）→ 仍会跑确定性校验
    - **扫描件 / 图片**：按 `provider` 渲染 + 识别（平台优先，失败自动用本地引擎补跑）
    """
    p = Path(path)
    suffix = p.suffix.lower()
    prov = ocr_engine.resolve_provider(provider)

    if suffix == ".pdf":
        info = pdf_utils.read_pdf(p)
        if info.error:
            return DocumentText(kind="error", error=f"PDF 解析失败：{info.error}")

        if not info.is_scanned(settings.scanned_char_threshold) and not force_ocr:
            pages = [
                PageText(page_no=i + 1, text=t, source="text-layer")
                for i, t in enumerate(pdf_utils.extract_page_texts(p))
            ]
            doc = DocumentText(kind="text_pdf", pages=pages,
                               engine="text-layer", provider="text-layer")
            _attach_checks(doc, on_progress)
            return doc

        return _ocr_pdf(p, prov, dpi, on_progress)

    if suffix in pdf_utils.IMAGE_EXTS:
        return _ocr_image(p, prov, on_progress)

    return DocumentText(kind="error", error=f"不支持的格式：{suffix}")


def _attach_checks(doc: DocumentText, on_progress: ProgressFn | None) -> None:
    if on_progress:
        on_progress("check", 0, 1)
    t0 = time.perf_counter()
    doc.checks = checks.check_document([p.text for p in doc.pages])
    doc.stages["check"] = round(time.perf_counter() - t0, 2)
    if on_progress:
        on_progress("check", 1, 1)


def _render(path: Path, provider: str, dpi: int | None, out_dir: Path,
            on_progress: ProgressFn | None) -> tuple[list[Path], str, int]:
    """渲染页面。**参数跟着引擎走**（平台 150DPI/JPEG，本地 300DPI/PNG），返回 (图, 格式, dpi)。"""
    cb = (lambda d, t: on_progress("render", d, t)) if on_progress else None
    if provider == "platform":
        use_dpi = settings.ocr_platform_dpi
        fmt = settings.ocr_platform_format
        images = pdf_utils.render_pages(
            path, dpi=use_dpi, out_dir=out_dir, fmt=fmt,
            quality=settings.ocr_platform_quality, on_progress=cb)
    else:
        use_dpi = dpi or settings.ocr_dpi
        fmt = "png"
        images = pdf_utils.render_pages(path, dpi=use_dpi, out_dir=out_dir, on_progress=cb)
    return images, fmt, use_dpi


def _ocr_pdf(path: Path, provider: str, dpi: int | None,
             on_progress: ProgressFn | None) -> DocumentText:
    """扫描件：渲染每页 → 识别（平台批量并发 / 本地逐页），保留页号与质量信号。"""
    # 渲染图必须落到 work/（输入目录在容器里是只读挂载）
    out_dir = settings.work_dir / "pages" / uuid.uuid4().hex
    started = time.perf_counter()
    stages: dict = {}
    notes: list[str] = []
    try:
        t0 = time.perf_counter()
        images, fmt, use_dpi = _render(path, provider, dpi, out_dir, on_progress)
        stages["render"] = round(time.perf_counter() - t0, 2)

        t0 = time.perf_counter()
        pages = _recognize(images, provider, on_progress, notes)
        stages["ocr"] = round(time.perf_counter() - t0, 2)

        doc = DocumentText(kind="scanned", pages=pages, engine=provider,
                           provider=provider, dpi=use_dpi, image_format=fmt,
                           stages=stages, notes=notes,
                           elapsed=round(time.perf_counter() - started, 2))
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)

    _attach_checks(doc, on_progress)
    doc.elapsed = round(time.perf_counter() - started, 2)
    return doc


def _ocr_image(path: Path, provider: str, on_progress: ProgressFn | None) -> DocumentText:
    """单张图片（线上图片附件常见）。"""
    started = time.perf_counter()
    notes: list[str] = []
    pages = _recognize([path], provider, on_progress, notes)
    doc = DocumentText(kind="image", pages=pages, engine=provider, provider=provider,
                       image_format=path.suffix.lstrip("."), notes=notes,
                       elapsed=round(time.perf_counter() - started, 2))
    _attach_checks(doc, on_progress)
    doc.elapsed = round(time.perf_counter() - started, 2)
    return doc


def _recognize(images: list[Path], provider: str,
               on_progress: ProgressFn | None, notes: list[str]) -> list[PageText]:
    """按 provider 识别；平台路径下**失败页自动用本地引擎补跑**。"""
    if provider == "platform":
        pages = _platform_pages(images, on_progress)
        failed = [i for i, p in enumerate(pages) if p.error]
        if failed:
            # 兜底：平台不可用/单请求失败时，至少让这些页有内容，并在元信息里说清楚
            sub = [images[i] for i in failed]
            local = _local_pages(sub, "rapid", None)
            recovered = 0
            for k, i in enumerate(failed):
                lp = local[k]
                if not lp.is_empty:
                    lp.page_no = pages[i].page_no
                    lp.note = f"平台识别失败，已用本地引擎补跑（原错误：{pages[i].error[:80]}）"
                    pages[i] = lp
                    recovered += 1
            notes.append(
                f"{len(failed)} 页平台识别失败，其中 {recovered} 页已用本地引擎补跑"
                f"（识别质量会低于平台，请重点核对）")
            if on_progress:
                on_progress("ocr-fallback", len(images), len(images))
        return pages

    return _local_pages(images, provider, on_progress)


def _platform_pages(images: list[Path], on_progress: ProgressFn | None) -> list[PageText]:
    raw = platform_ocr.ocr_images(
        images,
        on_progress=(lambda d, t: on_progress("ocr", d, t)) if on_progress else None,
    )
    return [
        PageText(page_no=i + 1, text=r.markdown, source="platform",
                 blocks=r.blocks, elapsed=r.elapsed, error=r.error)
        for i, r in enumerate(raw)
    ]


def _local_pages(images: list[Path], provider: str,
                 on_progress: ProgressFn | None) -> list[PageText]:
    """本地引擎逐页识别（并发受 `OCR_WORKERS` 限制，进度逐页上报）。"""
    results: list[PageText | None] = [None] * len(images)
    lock = threading.Lock()
    counter = {"done": 0}

    def one(i: int) -> None:
        r = ocr_engine.ocr_image(images[i], provider)
        results[i] = PageText(
            page_no=i + 1,
            text=r.text,
            source=provider,
            confidence=round(r.avg_score, 3) if r.lines else None,
            elapsed=r.elapsed,
        )
        with lock:
            counter["done"] += 1
            if on_progress:
                on_progress("ocr", counter["done"], len(images))

    workers = max(1, min(settings.ocr_workers, len(images)))
    if workers == 1:
        for i in range(len(images)):
            one(i)
    else:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            list(pool.map(one, range(len(images))))
    return [p or PageText(page_no=i + 1, text="", error="未识别") for i, p in enumerate(results)]
