#!/usr/bin/env python
"""命令行分析：不开浏览器也能跑（适合批量脚本与自动化）。

与网页界面走**同一套逻辑**（`pm_ai.analyze`），输出也完全一致，
只是把结果打到终端 / 存成 .md 文件。

用法：

    # 容器内（samples 以只读方式挂在 /samples）
    python scripts/analyze_cli.py /samples/合同.pdf
    python scripts/analyze_cli.py /samples/合同.pdf --instruction "抽取付款条款"
    python scripts/analyze_cli.py /samples/合同.pdf --save          # 存到 work/out/
    python scripts/analyze_cli.py /samples/合同.pdf --save --out /app/work/out

    # 宿主机一条命令
    bash scripts/docker-verify.sh analyze /samples/合同.pdf --save

⚠️ 每次分析都要调大模型，一份 6 页扫描件约 1 分钟（OCR 约 27s + 模型约 60s）。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from pm_ai import analyze  # noqa: E402
from pm_ai.config import settings  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description="命令行文档分析（输出带来源与置信度的 markdown）")
    ap.add_argument("path", help="待分析的 PDF / 图片路径")
    ap.add_argument("--instruction", default="", help="特别关注点（同界面上的输入框）")
    ap.add_argument("--dpi", type=int, default=0, help="扫描件渲染 DPI，默认取配置")
    ap.add_argument("--save", action="store_true", help="把 markdown 存成 .md 文件")
    ap.add_argument("--out", default="", help="产物目录，默认 <work_dir>/out")
    args = ap.parse_args()

    path = Path(args.path)
    if not path.exists():
        print(f"[FAIL] 文件不存在：{path}")
        return 1

    print("=" * 72)
    print(f"文件：{path.name}")
    print("=" * 72)

    result = analyze.analyze(path, instruction=args.instruction, dpi=args.dpi or None)

    d, llm = result.doc, result.llm
    print(f"类型：{d.get('kind')}　页数：{d.get('pages')}　字数：{d.get('chars')}"
          f"　识别置信度：{d.get('avg_confidence')}")
    if d.get("low_confidence_pages"):
        print(f"⚠️ 识别偏低页：{d['low_confidence_pages']}（人工复核优先看）")
    if llm.get("ok"):
        print(f"模型：{llm.get('model')}　耗时：{llm.get('elapsed')}s　"
              f"tokens：{llm.get('prompt_tokens')}+{llm.get('completion_tokens')}"
              f"　finish：{llm.get('finish_reason')}")
    if result.warning:
        print(f"⚠️ {result.warning}")
    if result.truncated:
        print(f"⚠️ {result.truncate_note}")
    print("-" * 72)
    print(result.markdown)

    if args.save:
        out_dir = Path(args.out) if args.out else settings.work_dir / "out"
        out_dir.mkdir(parents=True, exist_ok=True)
        dest = out_dir / f"{path.stem}.分析结果.md"
        dest.write_text(result.markdown, encoding="utf-8")
        print("-" * 72)
        print(f"已保存：{dest}")

    return 0 if llm.get("ok") and not result.warning else 2


if __name__ == "__main__":
    raise SystemExit(main())
