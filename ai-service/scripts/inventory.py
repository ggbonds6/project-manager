#!/usr/bin/env python
"""附件构成摸底（自测方案 A1）——**这一步不需要模型，可以立刻做**。

回答三个决定后续优先级的问题：
  1. 扫描件占比多少？　→ 决定 OCR 是不是先决条件
  2. OFD / 其他格式占比多少？→ 决定要不要先攻格式提取
  3. 哪些文件特别大？　→ 决定异步处理与耗时预算

用法：

    python scripts/inventory.py <附件目录> [--out 附件构成统计.xlsx]

输出：控制台汇总 + Excel（每份文件一行，便于筛选排查）。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from pm_ai import pdf_utils  # noqa: E402
from pm_ai.config import settings  # noqa: E402

LARGE_MB = 50  # 大文件阈值


def build_rows(root: Path) -> list[dict]:
    rows: list[dict] = []
    for path in pdf_utils.iter_documents(root):
        size_mb = path.stat().st_size / 1024 / 1024
        ext = path.suffix.lower().lstrip(".")
        row = {
            "文件": path.name,
            "相对路径": str(path.relative_to(root)),
            "扩展名": ext,
            "大小MB": round(size_mb, 2),
            "页数": None,
            "文本字符数": None,
            "每页字符": None,
            "类型": ext,
            "备注": "",
        }

        if ext == "pdf":
            info = pdf_utils.read_pdf(path)
            row["页数"] = info.page_count
            row["文本字符数"] = info.text_chars
            row["每页字符"] = round(info.chars_per_page, 1)
            row["类型"] = info.kind
            if info.error:
                row["备注"] = f"解析失败：{info.error[:60]}"

        if size_mb >= LARGE_MB:
            row["备注"] = (row["备注"] + " 大文件").strip()

        rows.append(row)
    return rows


def summarize(rows: list[dict]) -> None:
    print("=" * 64)
    total = len(rows)
    print(f"附件总数：{total}")
    if not total:
        print("（目录下没有发现 PDF 或图片）")
        return

    by_kind: dict[str, list[dict]] = {}
    for r in rows:
        by_kind.setdefault(r["类型"], []).append(r)

    print("-" * 64)
    print(f"{'类型':<12}{'数量':>6}{'占比':>9}{'总大小MB':>12}")
    for kind, items in sorted(by_kind.items(), key=lambda kv: -len(kv[1])):
        size = sum(i["大小MB"] for i in items)
        print(f"{kind:<12}{len(items):>6}{len(items)/total*100:>8.1f}%{size:>12.1f}")

    scanned = len(by_kind.get("scanned", []))
    print("-" * 64)
    print(f"扫描件占比：{scanned / total * 100:.1f}%（{scanned}/{total}）")
    if scanned / total > 0.5:
        print("→ 扫描件超过一半，**OCR 是先决条件**，应先解决")
    elif scanned:
        print("→ 扫描件存在但非主体，可先做电子版路径，OCR 并行推进")
    else:
        print("→ 无扫描件，可**跳过 OCR** 直接做文本提取与抽取验证")

    others = {k: v for k, v in by_kind.items() if k not in {"pdf", "text_pdf", "scanned"}}
    if others:
        print(f"\n⚠️ 存在非 PDF 格式：{', '.join(f'{k}({len(v)})' for k, v in others.items())}")
        print("   → 这些需要各自评估提取方案（OFD 尤需注意，服务端生态弱）")

    large = [r for r in rows if r["大小MB"] >= LARGE_MB]
    if large:
        print(f"\n大文件（≥{LARGE_MB}MB）{len(large)} 个，处理耗时需单独评估：")
        for r in sorted(large, key=lambda x: -x["大小MB"])[:10]:
            pages = f"{r['页数']}页" if r["页数"] else "-"
            print(f"   {r['大小MB']:>7.1f}MB  {pages:>7}  {r['文件']}")


def main() -> int:
    ap = argparse.ArgumentParser(description="附件构成摸底（不需要模型）")
    ap.add_argument("root", help="附件目录")
    ap.add_argument("--out", default="", help="Excel 输出路径，默认 <目录>/附件构成统计.xlsx")
    args = ap.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        print(f"[FAIL] 不是目录：{root}")
        return 1

    rows = build_rows(root)
    summarize(rows)

    if rows:
        out = Path(args.out) if args.out else root / "附件构成统计.xlsx"
        try:
            import pandas as pd

            pd.DataFrame(rows).to_excel(out, index=False)
            print(f"\n明细已导出：{out}")
        except Exception as exc:  # noqa: BLE001
            print(f"\n[WARN] Excel 导出失败（{exc}），改用 CSV")
            import csv

            csv_path = out.with_suffix(".csv")
            with csv_path.open("w", newline="", encoding="utf-8-sig") as fh:
                writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
                writer.writeheader()
                writer.writerows(rows)
            print(f"已导出：{csv_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
