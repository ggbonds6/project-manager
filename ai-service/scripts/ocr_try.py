#!/usr/bin/env python
"""单文件 OCR 试验：验证「这份附件能不能被正确识别」。

这是自测方案里 A3 的入口脚本。用法：

    # 文本型 PDF（会直接取文本层，不跑 OCR）
    python scripts/ocr_try.py samples/批复.pdf

    # 扫描件（渲染 + 平台 OCR），并把识别结果存成 txt 便于人工比对
    python scripts/ocr_try.py samples/身份证扫描件.pdf --save

    # 提高渲染 DPI 再试（**只用于排障与印章"两遍法"**，见下方说明）
    python scripts/ocr_try.py samples/社保证明.pdf --dpi 300

    # 强行走 OCR（用于对比"文本层"与"OCR 结果"的差异）
    python scripts/ocr_try.py samples/合同.pdf --force-ocr

    # 指定产物目录（默认 <work_dir>/ocr_try）
    python scripts/ocr_try.py samples/合同.pdf --save --out-dir /app/work/out

## 只走平台 OCR

本地 RapidOCR/PaddleOCR 引擎与 `--engine` 参数已于 2026-09-18 一并移除：
实测同一份合同平台把"柒佰柒拾捌万元整"完整识别，本地引擎金额全丢，
"能跑但结果不可用"的引擎留着只会误导判断。本脚本因此也不再打印
"平均置信度 / 低置信行数"——平台 OCR **不返回置信度**，编一个数出来毫无意义。

## `--dpi` 到底管什么

它覆盖平台默认渲染 DPI（`OCR_PLATFORM_DPI`，默认 150），**默认不要调**：
VL 模型内部会下采样到约 100 万像素，实测 120~300 DPI 的正文识别结果完全一致，
而 300 DPI 的图每页 6.7MB（150 DPI JPEG 仅 396KB），白等的是上传时间。
它真正的用途是低 DPI 下印章文字会被**编造**（实测读出过完全不相干的银行名），
需要读章时用高 DPI 原图重跑该页（手册的"两遍法"）。

⚠️ **产物不写回输入目录**：识别文本统一落到输出目录，
   因为输入目录在 Docker 里是只读挂载，而且不该污染附件原件。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from pm_ai import document, pdf_utils  # noqa: E402
from pm_ai.config import settings  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description="单文件 OCR 效果试验（平台 OCR）")
    ap.add_argument("path", help="待识别的 PDF 或图片路径")
    ap.add_argument(
        "--dpi",
        type=int,
        default=0,
        help="覆盖平台渲染 DPI（默认取配置 OCR_PLATFORM_DPI=150；"
        "只用于排障/印章两遍法，调大不会改善正文识别）",
    )
    ap.add_argument("--save", action="store_true", help="把识别文本保存为 .txt")
    ap.add_argument("--force-ocr", action="store_true", help="文本型 PDF 也强制走 OCR")
    ap.add_argument("--preview", type=int, default=30, help="预览前 N 行，0 表示全部")
    ap.add_argument("--out-dir", default="", help="产物输出目录，默认 <work_dir>/ocr_try")
    args = ap.parse_args()

    path = Path(args.path)
    if not path.exists():
        print(f"[FAIL] 文件不存在：{path}")
        return 1

    out_dir = Path(args.out_dir) if args.out_dir else settings.work_dir / "ocr_try"
    out_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 64)
    print(f"文件：{path.name}")
    print(f"大小：{path.stat().st_size / 1024 / 1024:.2f} MB")

    # ── 1) PDF 先判定类型（文本型 vs 扫描件）──────────────────
    # 先摸清"有没有文本层"再决定期望值：文本型拿文本层是正常的，不是 OCR 没跑
    if path.suffix.lower() == ".pdf":
        info = pdf_utils.read_pdf(path)
        if info.error:
            print(f"[FAIL] PDF 解析失败：{info.error}")
            return 1
        print(
            f"页数：{info.page_count}　文本层字符：{info.text_chars}"
            f"　每页字符：{info.chars_per_page:.1f}"
        )
        print(f"判定：{info.kind}")

    # ── 2) 识别（复用主链路的 read_document）──────────────────
    # 与 /documents、/upload-tasks 走同一个函数：脚本看到的识别结果与入库结果必然一致，
    # 否则"摸底"很容易得出与实际入库不同的结论。
    t0 = time.perf_counter()
    doc = document.read_document(path, dpi=args.dpi or None, force_ocr=args.force_ocr)
    end_to_end = time.perf_counter() - t0

    if doc.error:
        print(f"[FAIL] {doc.error}")
        return 1

    pages = max(doc.page_count, 1)
    print("-" * 64)
    print(f"类型：{doc.kind}　引擎：{doc.engine}　页数：{doc.page_count}　字符数：{doc.char_count}")
    print(f"渲染：{doc.dpi or '-'} DPI / {doc.image_format or '-'}　阶段耗时：{doc.stages}")
    print(
        f"OCR 耗时：{doc.elapsed:.1f}s　单页平均：{doc.elapsed / pages:.2f}s"
        f"　端到端：{end_to_end:.1f}s"
    )

    # 平台不返回置信度，故这里只报"哪些页根本没识别出来"这种确定的信息
    if doc.failed_pages:
        print(f"\n⚠️ 识别失败页：{doc.failed_pages}（本地 OCR 兜底已移除，这些页没有内容）")
        print("   处理：人工核对原文，或确认平台 OCR 可用后重跑；印章类内容可用 --dpi 高值两遍法")
    for note in doc.notes:
        print(f"⚠️ {note}")

    print("-" * 64)
    _show(doc.text, args.preview)

    if args.save:
        saved = _save(doc.text, out_dir / f"{path.stem}.ocr.txt")
        print(f"\n已保存：{saved}")

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


def _save(text: str, dest: Path) -> Path:
    """把识别文本写到 dest（自动建目录），返回写入路径。"""
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8")
    return dest


if __name__ == "__main__":
    raise SystemExit(main())
