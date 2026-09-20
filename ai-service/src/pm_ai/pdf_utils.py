"""PDF / 图片处理工具。

只用 PyMuPDF（不依赖 poppler、pdf2image），原因是：
- 它既能提取文本，又能把页面直接渲染成图片，一条依赖覆盖两条路；
- 内网离线环境装机成本最低。

关键概念：
- **文本型 PDF**：本身带文本层，`get_text()` 能取到字 → 直接抽取，不必 OCR；
- **扫描件 PDF**：本质是图片，文本层为空 → 必须 OCR。
本模块的 `classify()` 就是用来区分这两类的（对应自测方案 A1/A2）。
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import fitz  # PyMuPDF

PDF_EXTS = {".pdf"}
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}


@dataclass
class PdfInfo:
    path: Path
    page_count: int
    text_chars: int
    error: str | None = None

    @property
    def chars_per_page(self) -> float:
        return self.text_chars / self.page_count if self.page_count else 0.0

    def is_scanned(self, threshold: int = 50) -> bool:
        """平均每页字符数低于阈值 → 视为扫描件（需 OCR）。"""
        return self.error is None and self.page_count > 0 and self.chars_per_page < threshold

    @property
    def kind(self) -> str:
        if self.error:
            return "error"
        return "scanned" if self.is_scanned() else "text_pdf"


def read_pdf(path: str | Path) -> PdfInfo:
    """读取 PDF 基本信息（页数 + 文本字符数），不加载全部内容。"""
    p = Path(path)
    try:
        with fitz.open(p) as doc:
            pages = doc.page_count
            chars = sum(len(page.get_text()) for page in doc)
        return PdfInfo(path=p, page_count=pages, text_chars=chars)
    except Exception as exc:  # 文件损坏 / 加密 / 非 PDF
        return PdfInfo(path=p, page_count=0, text_chars=0, error=str(exc))


def extract_text(path: str | Path) -> str:
    """提取 PDF 全文本（扫描件会返回空或极少内容）。"""
    with fitz.open(Path(path)) as doc:
        return "\n".join(page.get_text() for page in doc)


def extract_page_texts(path: str | Path) -> list[str]:
    """**逐页**提取文本，返回每页文本的列表（index 0 = 第 1 页）。

    为什么需要逐页：模型要标注"信息来自第几页"，就必须让它看到分页边界；
    给一整坨扁平文本，模型无从判断页码，只能编造或放弃标注。
    """
    with fitz.open(Path(path)) as doc:
        return [page.get_text() for page in doc]


def render_pages(
    path: str | Path,
    dpi: int = 300,
    out_dir: str | Path | None = None,
    fmt: str = "png",
    quality: int = 85,
    on_progress: Callable[[int, int], None] | None = None,
) -> list[Path]:
    """把 PDF 每页渲染成图片，返回图片路径列表（顺序＝页序）。

    **渲染参数要跟着 OCR 引擎走**（别用一套参数喂两个引擎）：

    - 平台 OCR（PaddleOCR-VL）：`150 DPI + jpeg/q85`。实测 120~300 DPI 的识别结果
      **完全一致**（模型内部会下采样到 ≈100 万像素），但 300DPI PNG 每页 6.7MB、
      150DPI JPEG 396KB —— **上传量差 17 倍**，白等的是时间。
    - 本地 RapidOCR：`300 DPI + png`。它没有版面模型，字小了直接丢（金额、身份证号都靠像素）。

    :param on_progress: `(已渲染页数, 总页数)`，用于上报"渲染中"的进度。
    返回图片路径列表；`out_dir` 为 None 时写到 `<PDF同级>/<PDF名>_pages/`。
    """
    src = Path(path)
    target = Path(out_dir) if out_dir else src.with_name(f"{src.stem}_pages")
    target.mkdir(parents=True, exist_ok=True)

    ext = "jpg" if fmt.lower() in {"jpg", "jpeg"} else "png"
    out: list[Path] = []
    with fitz.open(src) as doc:
        total = doc.page_count
        for i, page in enumerate(doc):
            pix = page.get_pixmap(dpi=dpi)
            img = target / f"page_{i + 1:04d}.{ext}"
            if ext == "jpg":
                img.write_bytes(pix.tobytes("jpeg", jpg_quality=quality))
            else:
                pix.save(img)
            out.append(img)
            if on_progress:
                on_progress(i + 1, total)
    return out


def render_page_bytes(page) -> bytes:
    """单页 → PNG 字节（供接口直接返回，不落盘）。"""
    return page.get_pixmap(dpi=300).tobytes("png")


def iter_documents(root: str | Path):
    """遍历目录下的 PDF 与图片（跳过渲染产生的中间目录）。"""
    base = Path(root)
    for p in sorted(base.rglob("*")):
        if not p.is_file():
            continue
        if "_pages" in p.parts:  # 跳过 render_pages 的产物
            continue
        if p.suffix.lower() in PDF_EXTS | IMAGE_EXTS:
            yield p
