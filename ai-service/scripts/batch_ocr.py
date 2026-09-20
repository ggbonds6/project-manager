#!/usr/bin/env python
"""批量 OCR / 文本提取：一次跑完整个目录，产出可人工比对的结果。

用途（自测方案 A3 的批量版）：
  1. 拿到真实数据——现网附件的**识别质量**与**处理耗时**；
  2. 每份附件导出 `.ocr.txt`，方便人工抽查"关键字段（尤其数字）有没有识别错"。

用法：

    python scripts/batch_ocr.py <附件目录> --out-dir out/ocr
    python scripts/batch_ocr.py samples --dpi 300 --limit 20
    python scripts/batch_ocr.py samples --force-ocr     # 文本型也走 OCR，做对比

产出：
    out/ocr/批次汇总.xlsx   每份一行（类型/页数/字符数/失败页/耗时/是否成功）
    out/ocr/txt/<文件名>.ocr.txt

## 只走平台 OCR

本地 RapidOCR/PaddleOCR 引擎与 `--engine` 参数已于 2026-09-18 一并移除（原因见
`src/pm_ai/config.py` 的 OCR 段注释：本地引擎金额/编号识别不可用，留着只会误导判断）。
因此汇总表里**不再有"平均置信度 / 低置信行数"这类列**——平台 OCR 不返回置信度，
填个数字进去等于伪造质量信号。要判断识别结果可不可信，看"识别失败页"与人工比对。
`--dpi` 保留，但它只覆盖平台渲染 DPI（默认 150），用于排障与印章"两遍法"。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from pm_ai import document, pdf_utils  # noqa: E402
from pm_ai.config import settings  # noqa: E402


def process_one(path: Path, dpi: int | None, force_ocr: bool) -> tuple[dict, str]:
    """处理单个文件，返回 (汇总行, 文本)。

    直接调用 `document.read_document`：与线上链路（`/documents`、`/upload-tasks`）同一个实现，
    这样"批量摸底"的数字才能代表真实入库时的表现。
    """
    row = {
        "文件": path.name,
        "扩展名": path.suffix.lower().lstrip("."),
        "大小MB": round(path.stat().st_size / 1024 / 1024, 2),
        "类型": "",
        "页数": None,
        "文本字符数": None,
        "文本行数": None,
        "识别失败页": "",
        "OCR耗时s": None,
        "端到端耗时s": None,
        "成功": True,
        "说明": "",
    }
    started = time.perf_counter()

    try:
        doc = document.read_document(path, dpi=dpi, force_ocr=force_ocr)

        row.update(
            {
                "类型": doc.kind,
                "页数": doc.page_count,
                "文本字符数": doc.char_count,
                "文本行数": len(doc.text.splitlines()),
                "端到端耗时s": round(time.perf_counter() - started, 2),
            }
        )
        # 只有真正跑了 OCR 的才有这一项；文本层解析不该被算进 OCR 耗时
        if doc.stages.get("ocr") is not None:
            row["OCR耗时s"] = doc.stages["ocr"]

        if doc.error:
            row["成功"] = False
            row["说明"] = doc.error[:120]
            return row, doc.text

        if doc.failed_pages:
            row["识别失败页"] = "、".join(str(p) for p in doc.failed_pages)
            row["说明"] = "存在识别失败页，需人工复核（本地兜底已移除）"
        elif not doc.text.strip():
            row["说明"] = "未提取到文本"
        return row, doc.text

    except Exception as exc:  # noqa: BLE001 - 单个失败不应中断整批
        row["成功"] = False
        row["说明"] = f"{type(exc).__name__}: {exc}"[:120]
        return row, ""


def main() -> int:
    ap = argparse.ArgumentParser(description="批量 OCR / 文本提取（平台 OCR）")
    ap.add_argument("root", help="附件目录")
    ap.add_argument("--out-dir", default="", help="输出目录，默认 <work_dir>/out")
    ap.add_argument(
        "--dpi",
        type=int,
        default=0,
        help="覆盖平台渲染 DPI（默认取 OCR_PLATFORM_DPI=150；"
        "只用于排障/印章两遍法，调大不会改善正文识别）",
    )
    ap.add_argument("--force-ocr", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 个（先小样本验证）")
    args = ap.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        print(f"[FAIL] 不是目录：{root}")
        return 1

    out_dir = Path(args.out_dir) if args.out_dir else settings.work_dir / "out"
    txt_dir = out_dir / "txt"
    txt_dir.mkdir(parents=True, exist_ok=True)

    files = list(pdf_utils.iter_documents(root))
    if args.limit:
        files = files[: args.limit]
    if not files:
        print("[FAIL] 目录下没有可处理的 PDF / 图片")
        return 1

    dpi = args.dpi or None
    print(
        f"待处理 {len(files)} 个文件　"
        f"DPI={'默认(' + str(settings.ocr_platform_dpi) + ')' if dpi is None else dpi}"
        f"　引擎=platform"
    )
    print("=" * 64)

    rows: list[dict] = []
    for i, path in enumerate(files, 1):
        row, text = process_one(path, dpi, args.force_ocr)
        rows.append(row)
        (txt_dir / f"{path.stem}.ocr.txt").write_text(text, encoding="utf-8")

        flag = "OK " if row["成功"] else "ERR"
        cost = row["端到端耗时s"]
        cost_txt = f"{cost:>6.1f}s" if cost is not None else "     -"
        print(
            f"[{i:>3}/{len(files)}] {flag} {cost_txt}  {row['类型']:<9} {path.name}"
            + (f"  ← {row['说明']}" if row["说明"] else "")
        )

    # ── 汇总 ─────────────────────────────────────────────────
    print("=" * 64)
    ok = [r for r in rows if r["成功"]]
    scanned = [r for r in ok if r["类型"] == "scanned"]
    print(f"成功 {len(ok)}/{len(rows)}")
    if scanned:
        total_ocr = sum(r["OCR耗时s"] or 0 for r in scanned)
        pages = sum(r["页数"] or 0 for r in scanned)
        print(
            f"扫描件 {len(scanned)} 份，共 {pages} 页，OCR 合计 {total_ocr:.0f}s"
            f"（单页均 {total_ocr / max(pages, 1):.2f}s）"
        )
    # 不再统计"平均置信度 / 低置信行数"：平台 OCR 不返回置信度，本地引擎已于 2026-09-18 移除
    failed = [r for r in ok if r["识别失败页"]]
    if failed:
        pages_failed = sum(len(r["识别失败页"].split("、")) for r in failed)
        print(
            f"⚠️ {len(failed)} 份存在识别失败页（共 {pages_failed} 页）"
            f"—— 本地兜底已移除，这些页没有内容，务必人工复核"
        )

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
