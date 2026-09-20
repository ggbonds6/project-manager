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
| 识别层 | **已消失**：本地引擎逐页分，平台**不返回**置信度 | 机器分，实测**不可靠** |
| 校验层 | `checks.py` 的确定性检查（大小写金额互校等） | **可复现**，这是最硬的一层 |
| 理解层 | 模型自评 0–1 | 主观，只用来排序复核优先级 |

⚠️ 实测教训（保留，因为它解释了为什么"没有识别置信度"不算损失）：
本地引擎曾报出 OCR 平均置信度 0.97，同一份文件里的金额仍被识别错（千分位逗号→小数点）。
**识别置信度高 ≠ 内容正确**。真正能兜住"禁止虚构"的是校验层与人工复核，
所以平台不返回置信度并不可惜；`confidence` 这类字段只是为保持 API 形状而留着（恒为 None）。
"""

from __future__ import annotations

import shutil
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from . import checks, pdf_utils, platform_ocr
from .config import settings

ProgressFn = Callable[[str, int, int], None]
"""进度回调：`(阶段, 已完成, 总数)`。阶段取值：`render` / `ocr` / `check`。

（原 `ocr-fallback` 阶段随本地引擎兜底一起移除，见 `_recognize`。）
"""


@dataclass
class PageText:
    page_no: int
    text: str
    confidence: float | None = None
    """**识别层**置信度。平台 OCR **不返回**该值，故此处恒为 `None`。

    保留字段是为了 API 形状与历史文档稳定（主系统、`store.py`、前端仍会读它），
    收到 `None` 即表示"平台没提供这个信息"，而不是"这一页不可靠"。
    """
    source: str = ""
    """这一页实际由谁识别：`text-layer` / `platform`。

    取值域已收窄（2026-09-18）：本地 `rapid` / `paddle` 已随本地引擎一起移除。
    """
    blocks: list[dict] = field(default_factory=list)
    """版面块（平台引擎）：`{label, content, bbox}`——出处定位与质量信号的来源。"""
    elapsed: float = 0.0
    error: str = ""
    note: str = ""
    """补充说明（例：平台该页识别失败，需人工复核）。"""

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
    """实际执行的解析方式。取值域已收窄为 `platform` / `text-layer`（2026-09-18 起）。"""
    provider: str = ""
    """配置层面解析出来的引擎。取值域同上：只可能是 `platform` / `text-layer`。

    保留该字段（主系统与前端会读），但不再存在 `rapid` / `paddle` / `auto` 这几种取值。
    """
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
        """识别质量偏低的页——**平台 OCR 不返回置信度，此属性恒为空**。

        保留（而不是删掉）是为了 API 形状与历史文档稳定：
        `store.py`、`scripts/analyze_cli.py`、`static/index.html` 仍在读它，
        删字段会让这些调用方拿到 `KeyError`/`undefined`；空列表语义也正确——
        "没有可用的低置信信息"，而不是"全部页面都很可靠"。
        """
        return [p.page_no for p in self.pages if p.confidence is not None and p.confidence < 0.85]

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
        """平均识别置信度——**平台 OCR 不返回置信度，此属性恒为 `None`**。

        保留理由同 `low_confidence_pages`：API 形状与历史文档稳定。
        注意 `None` ≠ 0，前端据此显示"该引擎不提供逐页置信度"才是正确解读。
        """
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
    on_progress: ProgressFn | None = None,
) -> DocumentText:
    """读文档 → 结构化文本，自动区分文本型 / 扫描件。

    - **文本型 PDF**：逐页取文本层（最快最准，不消耗 OCR 算力）→ 仍会跑确定性校验
    - **扫描件 / 图片**：渲染 → **平台 OCR**（PaddleOCR-VL）

    :param dpi: 仅用于排障 / 印章"两遍法"——覆盖平台默认渲染 DPI（默认 150）。
        注意提高 DPI **不会改善正文识别**（VL 模型内部下采样到约 100 万像素，
        实测 120~300 DPI 结果完全一致），只在上传体积和耗时上付出代价；
        它真正的用途是低 DPI 下印章文字会被"编造"（实测读出过完全不相干的银行名），
        需要读章时用高 DPI 原图重跑该页。
    :param force_ocr: 文本型 PDF 也强制走 OCR（用于对比文本层与 OCR 的差异）。

    2026-09-18：**本地 OCR 兜底已彻底移除，只走平台**。
    因此这里不再有 `provider` 参数，也不再"平台失败时用本地引擎补跑"——
    失败页保留 `error`，由上层（`document.summary` → 任务/接口）提示人工复核。
    """
    p = Path(path)
    suffix = p.suffix.lower()

    if suffix == ".pdf":
        info = pdf_utils.read_pdf(p)
        if info.error:
            return DocumentText(kind="error", error=f"PDF 解析失败：{info.error}")

        if not info.is_scanned(settings.scanned_char_threshold) and not force_ocr:
            pages = [
                PageText(page_no=i + 1, text=t, source="text-layer")
                for i, t in enumerate(pdf_utils.extract_page_texts(p))
            ]
            doc = DocumentText(
                kind="text_pdf", pages=pages, engine="text-layer", provider="text-layer"
            )
            _attach_checks(doc, on_progress)
            return doc

        return _ocr_pdf(p, dpi, on_progress)

    if suffix in pdf_utils.IMAGE_EXTS:
        return _ocr_image(p, on_progress)

    return DocumentText(kind="error", error=f"不支持的格式：{suffix}")


def _attach_checks(doc: DocumentText, on_progress: ProgressFn | None) -> None:
    if on_progress:
        on_progress("check", 0, 1)
    t0 = time.perf_counter()
    doc.checks = checks.check_document([p.text for p in doc.pages])
    doc.stages["check"] = round(time.perf_counter() - t0, 2)
    if on_progress:
        on_progress("check", 1, 1)


def _render(
    path: Path, dpi: int | None, out_dir: Path, on_progress: ProgressFn | None
) -> tuple[list[Path], str, int]:
    """渲染页面为平台 OCR 要的图，返回 (图, 格式, 实际用的 dpi)。

    **参数只跟平台走**（150 DPI / JPEG / q85）：
    实测 120~300 DPI 识别结果完全一致（VL 模型内部下采样到 ≈100 万像素），
    而 300 DPI PNG 每页 6.7MB、150 DPI JPEG 每页 396KB——差 17 倍，白等的是上传时间。
    传 `dpi` 只为排障与印章"两遍法"（低 DPI 下开 `OCR_SEAL` 会编造印章文字）。
    """
    cb = (lambda d, t: on_progress("render", d, t)) if on_progress else None
    use_dpi = dpi or settings.ocr_platform_dpi
    fmt = settings.ocr_platform_format
    images = pdf_utils.render_pages(
        path,
        dpi=use_dpi,
        out_dir=out_dir,
        fmt=fmt,
        quality=settings.ocr_platform_quality,
        on_progress=cb,
    )
    return images, fmt, use_dpi


def _ocr_pdf(path: Path, dpi: int | None, on_progress: ProgressFn | None) -> DocumentText:
    """扫描件：渲染每页 → 平台识别（批量并发），保留页号与质量信号。"""
    # 渲染图必须落到 work/（输入目录在容器里是只读挂载）
    out_dir = settings.work_dir / "pages" / uuid.uuid4().hex
    started = time.perf_counter()
    stages: dict = {}
    notes: list[str] = []
    try:
        t0 = time.perf_counter()
        images, fmt, use_dpi = _render(path, dpi, out_dir, on_progress)
        stages["render"] = round(time.perf_counter() - t0, 2)

        t0 = time.perf_counter()
        pages = _recognize(images, on_progress, notes)
        stages["ocr"] = round(time.perf_counter() - t0, 2)

        doc = DocumentText(
            kind="scanned",
            pages=pages,
            engine="platform",
            provider="platform",
            dpi=use_dpi,
            image_format=fmt,
            stages=stages,
            notes=notes,
            elapsed=round(time.perf_counter() - started, 2),
        )
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)

    _attach_checks(doc, on_progress)
    doc.elapsed = round(time.perf_counter() - started, 2)
    return doc


def _ocr_image(path: Path, on_progress: ProgressFn | None) -> DocumentText:
    """单张图片（线上图片附件常见）。"""
    started = time.perf_counter()
    notes: list[str] = []
    pages = _recognize([path], on_progress, notes)
    doc = DocumentText(
        kind="image",
        pages=pages,
        engine="platform",
        provider="platform",
        image_format=path.suffix.lstrip("."),
        notes=notes,
        elapsed=round(time.perf_counter() - started, 2),
    )
    _attach_checks(doc, on_progress)
    doc.elapsed = round(time.perf_counter() - started, 2)
    return doc


def _recognize(
    images: list[Path], on_progress: ProgressFn | None, notes: list[str]
) -> list[PageText]:
    """识别全部页面——**只走平台 OCR，没有兜底**。

    本地 RapidOCR/PaddleOCR 兜底已于 2026-09-18 移除，原因见 `config.py` 的 OCR 段注释：
    本地引擎识别出来的金额/编号不可用，"悄悄换一个差引擎"比"明确失败"更危险——
    使用者会以为结果来自平台，从而不再复核。故失败页**保留 `error`**，
    由上层（任务列表 / 接口返回值 / `notes`）显式提示人工复核。
    """
    pages = _platform_pages(images, on_progress)
    failed = [p.page_no for p in pages if p.error]
    if failed:
        notes.append(
            f"{len(failed)} 页平台识别失败（第 {'、'.join(str(n) for n in failed)} 页）："
            f"本地 OCR 兜底已于 2026-09-18 移除，这些页**没有内容**，需人工复核或稍后重跑"
        )
    return pages


def _platform_pages(images: list[Path], on_progress: ProgressFn | None) -> list[PageText]:
    raw = platform_ocr.ocr_images(
        images,
        on_progress=(lambda d, t: on_progress("ocr", d, t)) if on_progress else None,
    )
    return [
        PageText(
            page_no=i + 1,
            text=r.markdown,
            source="platform",
            blocks=r.blocks,
            elapsed=r.elapsed,
            error=r.error,
        )
        for i, r in enumerate(raw)
    ]


# 本地引擎逐页识别函数 `_local_pages`（RapidOCR/PaddleOCR、线程池 + OCR_WORKERS 限流）
# 已于 2026-09-18 随本地 OCR 兜底一并删除，只走平台。
