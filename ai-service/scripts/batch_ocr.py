#!/usr/bin/env python
"""批量 OCR / 文本提取：一次跑完整个目录，产出可人工比对的结果。

用途（自测方案 A3 的批量版）：
  1. 拿到真实数据——现网附件的**识别质量**与**处理耗时**；
  2. 每份附件导出 `.ocr.txt`，方便人工抽查"关键字段（尤其数字）有没有识别错"。

用法：

    python scripts/batch_ocr.py <附件目录> --out-dir out/ocr
    python scripts/batch_ocr.py samples --dpi 400 --limit 20
    python scripts/batch_ocr.py samples --force-ocr     # 文本型也走 OCR，做对比

产出：
    out/ocr/批次汇总.xlsx   每份一行（类型/页数/置信度/耗时/是否成功）
    out/ocr/txt/<文件名>.ocr.txt
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from pm_ai import ocr_engine, pdf_utils  # noqa: E402
from pm_ai.config import settings  # noqa: E402


def process_one(path: Path, dpi: int, engine: str, force_ocr: bool) -> tuple[dict, str]:
    """处理单个文件，返回 (汇总行, 文本)。"""
    row = {
        "文件": path.name,
        "扩展名": path.suffix.lower().lstrip("."),
        "大小MB": round(path.stat().st_size / 1024 / 1024, 2),
        "类型": "",
        "页数": None,
        "文本字符数": None,
        "识别行数": None,
        "平均置信": None,
        "低置信行数": None,
        "OCR耗时s": None,
        "端到端耗时s": None,
        "成功": True,
        "说明": "",
    }
    started = time.perf_counter()

    try:
        images: list[Path] = []
        if path.suffix.lower() == ".pdf":
            info = pdf_utils.read_pdf(path)
            if info.error:
                row["成功"] = False
                row["说明"] = f"PDF 解析失败：{info.error[:80]}"
                return row, ""
            row["页数"] = info.page_count
            row["文本字符数"] = info.text_chars

            if not info.is_scanned(settings.scanned_char_threshold) and not force_ocr:
                text = pdf_utils.extract_text(path)
                row.update({"类型": "text_pdf", "识别行数": len(text.splitlines()),
                            "端到端耗时s": round(time.perf_counter() - started, 2)})
                return row, text

            row["类型"] = "scanned"
            images = pdf_utils.render_pages(path, dpi=dpi)
        else:
            row["类型"] = "image"
            images = [path]

        merged, _ = ocr_engine.ocr_pages(images, engine)
        row.update({
            "识别行数": len(merged.lines),
            "平均置信": round(merged.avg_score, 3),
            "低置信行数": len(merged.low_confidence),
            "OCR耗时s": round(merged.elapsed, 2),
            "端到端耗时s": round(time.perf_counter() - started, 2),
        })
        if merged.avg_score and merged.avg_score < 0.85:
            row["说明"] = "平均置信偏低，建议人工复核"
        return row, merged.text

    except Exception as exc:  # noqa: BLE001 - 单个失败不应中断整批
        row["成功"] = False
        row["说明"] = f"{type(exc).__name__}: {exc}"[:120]
        return row, ""


def main() -> int:
    ap = argparse.ArgumentParser(description="批量 OCR / 文本提取")
    ap.add_argument("root", help="附件目录")
    ap.add_argument("--out-dir", default="out/ocr", help="输出目录")
    ap.add_argument("--dpi", type=int, default=0)
    ap.add_argument("--engine", default="rapid", choices=["rapid", "paddle"])
    ap.add_argument("--force-ocr", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 个（先小样本验证）")
    args = ap.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        print(f"[FAIL] 不是目录：{root}")
        return 1

    out_dir = Path(args.out_dir)
    txt_dir = out_dir / "txt"
    txt_dir.mkdir(parents=True, exist_ok=True)

    files = list(pdf_utils.iter_documents(root))
    if args.limit:
        files = files[: args.limit]
    if not files:
        print("[FAIL] 目录下没有可处理的 PDF / 图片")
        return 1

    dpi = args.dpi or settings.ocr_dpi
    print(f"待处理 {len(files)} 个文件　DPI={dpi}　引擎={args.engine}")
    print("=" * 64)

    rows: list[dict] = []
    for i, path in enumerate(files, 1):
        row, text = process_one(path, dpi, args.engine, args.force_ocr)
        rows.append(row)
        (txt_dir / f"{path.stem}.ocr.txt").write_text(text, encoding="utf-8")

        flag = "OK " if row["成功"] else "ERR"
        cost = row["端到端耗时s"]
        cost_txt = f"{cost:>6.1f}s" if cost is not None else "     -"
        print(f"[{i:>3}/{len(files)}] {flag} {cost_txt}  {row['类型']:<9} {path.name}"
              + (f"  ← {row['说明']}" if row["说明"] else ""))

    # ── 汇总 ─────────────────────────────────────────────────
    print("=" * 64)
    ok = [r for r in rows if r["成功"]]
    scanned = [r for r in ok if r["类型"] == "scanned"]
    print(f"成功 {len(ok)}/{len(rows)}")
    if scanned:
        total_ocr = sum(r["OCR耗时s"] or 0 for r in scanned)
        pages = sum(r["页数"] or 0 for r in scanned)
        avg_conf = [r["平均置信"] for r in scanned if r["平均置信"]]
        print(f"扫描件 {len(scanned)} 份，共 {pages} 页，OCR 合计 {total_ocr:.0f}s"
              f"（单页均 {total_ocr / max(pages, 1):.2f}s）")
        if avg_conf:
            print(f"平均置信度：{sum(avg_conf) / len(avg_conf):.3f}")
        low = sum(r["低置信行数"] or 0 for r in scanned)
        if low:
            print(f"低置信行合计 {low} 条 —— 人工复核优先看这些")

    try:
        import pandas as pd

        xlsx = out_dir / "批次汇总.xlsx"
        pd.DataFrame(rows).to_excel(xlsx, index=False)
        print(f"\n汇总表：{xlsx}")
    except Exception as exc:  # noqa: BLE001
        print(f"\n[WARN] Excel 导出失败（{exc}）")
    print(f"识别文本：{txt_dir}/")

    # ── 下一次运行的提示 ──────────────────────────────────────
    print("\n下一步：从 txt 里挑 5~10 份，人工比对**金额、日期、编号**是否识别正确，")
    print("        把结果填进《自测方案》§8 的抽取记录表。")

    return 0 if len(ok) == len(rows) else 2


if __name__ == "__main__":
    raise SystemExit(main())
