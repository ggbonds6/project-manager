#!/usr/bin/env python
"""单文件 OCR 试验：验证「这份附件能不能被正确识别」。

这是自测方案里 A3 的入口脚本。用法：

    # 文本型 PDF（会直接取文本层，不跑 OCR）
    python scripts/ocr_try.py samples/批复.pdf

    # 扫描件（自动渲染 + OCR），并把识别结果存成 txt 便于人工比对
    python scripts/ocr_try.py samples/身份证扫描件.pdf --save

    # 提高 DPI 再试（小字、证件常用 300~400）
    python scripts/ocr_try.py samples/社保证明.pdf --dpi 400

    # 强行走 OCR（用于对比"文本层"与"OCR 结果"的差异）
    python scripts/ocr_try.py samples/合同.pdf --force-ocr

⚠️ Windows 终端若中文乱码，先执行：chcp 65001
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from pm_ai import ocr_engine, pdf_utils  # noqa: E402
from pm_ai.config import settings  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description="单文件 OCR 效果试验")
    ap.add_argument("path", help="待识别的 PDF 或图片路径")
    ap.add_argument("--dpi", type=int, default=0, help="渲染 DPI，默认取配置（300）")
    ap.add_argument("--save", action="store_true", help="把识别文本保存为同名 .txt")
    ap.add_argument("--force-ocr", action="store_true", help="文本型 PDF 也强制走 OCR")
    ap.add_argument("--preview", type=int, default=30, help="预览前 N 行，0 表示全部")
    ap.add_argument("--engine", default="rapid", choices=["rapid", "paddle"])
    args = ap.parse_args()

    path = Path(args.path)
    if not path.exists():
        print(f"[FAIL] 文件不存在：{path}")
        return 1

    dpi = args.dpi or settings.ocr_dpi
    print("=" * 64)
    print(f"文件：{path.name}")
    print(f"大小：{path.stat().st_size / 1024 / 1024:.2f} MB")

    # ── 1) PDF 先判定类型（文本型 vs 扫描件）──────────────────
    is_pdf = path.suffix.lower() == ".pdf"
    if is_pdf:
        info = pdf_utils.read_pdf(path)
        if info.error:
            print(f"[FAIL] PDF 解析失败：{info.error}")
            return 1
        print(f"页数：{info.page_count}　文本层字符：{info.text_chars}　每页字符：{info.chars_per_page:.1f}")
        print(f"判定：{info.kind}")

        if not info.is_scanned(settings.scanned_char_threshold) and not args.force_ocr:
            print("-" * 64)
            print("文本型 PDF → 直接取文本层（不消耗 OCR 算力）")
            text = pdf_utils.extract_text(path)
            _show(text, args.preview)
            if args.save:
                _save(path, text)
            return 0

    # ── 2) 渲染成图片 ─────────────────────────────────────────
    t0 = time.perf_counter()
    images = pdf_utils.render_pages(path, dpi=dpi) if is_pdf else [path]
    render_cost = time.perf_counter() - t0
    print(f"渲染 {len(images)} 页 @ {dpi} DPI，耗时 {render_cost:.1f}s")

    # ── 3) OCR ───────────────────────────────────────────────
    merged, per_page = ocr_engine.ocr_pages(images, args.engine)
    pages = max(len(images), 1)

    print("-" * 64)
    print(f"引擎：{args.engine}　识别行数：{len(merged.lines)}　平均置信：{merged.avg_score:.3f}")
    print(f"OCR 耗时：{merged.elapsed:.1f}s　单页平均：{merged.elapsed / pages:.2f}s")
    print(f"端到端：{render_cost + merged.elapsed:.1f}s")

    low = merged.low_confidence
    if low:
        print(f"\n低置信行 {len(low)} 条（人工复核优先看这些）：")
        for line in low[:10]:
            print(f"   [{line.score:.2f}] {line.text}")

    print("-" * 64)
    _show(merged.text, args.preview)

    if args.save:
        _save(path, merged.text)
        print(f"\n已保存：{path.with_suffix('.ocr.txt')}")
        if len(images) > 1:
            print(f"分页图片保留在：{images[0].parent}（人工比对时可逐页看）")

    return 0


def _show(text: str, preview: int) -> None:
    if not text.strip():
        print("（无文本）")
        return
    lines = text.splitlines()
    if preview and len(lines) > preview:
        print("\n".join(lines[:preview]))
        print(f"...（共 {len(lines)} 行，已截断）")
    else:
        print(text)


def _save(src: Path, text: str) -> None:
    src.with_suffix(".ocr.txt").write_text(text, encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
