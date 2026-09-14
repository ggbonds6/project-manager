#!/usr/bin/env python
"""环境自检——装完依赖后**先跑这个**，避免在真正测试时才发现环境问题。

检查项：
  1. Python 版本
  2. 关键依赖是否装齐
  3. OCR 引擎能否加载（首次会触发模型加载，较慢）
  4. 大模型是否连通（千问，需先配好 .env）
  5. 打印当前配置摘要（不显示密钥明文）

用法：
    python scripts/check_env.py
    python scripts/check_env.py --skip-llm     # 模型还没配好时，先跳过第 4 项
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

OK = "  [OK]  "
WARN = "  [WARN]"
FAIL = "  [FAIL]"

REQUIRED = [
    ("fitz", "PyMuPDF", "PDF 解析与页面渲染"),
    ("rapidocr_onnxruntime", "RapidOCR", "OCR 引擎"),
    ("openai", "openai", "大模型调用"),
    ("fastapi", "FastAPI", "HTTP 接口"),
    ("pandas", "pandas", "汇总导出"),
]


def check_python() -> bool:
    print("[1/5] Python 版本")
    major, minor = sys.version_info[:2]
    print(f"       {sys.version.split()[0]}　（{sys.executable}）")
    if (major, minor) >= (3, 10):
        print(f"{OK}版本满足要求（>=3.10）")
        return True
    print(f"{FAIL}需要 Python 3.10 及以上")
    return False


def check_deps() -> bool:
    print("\n[2/5] 依赖检查")
    all_ok = True
    for module, name, purpose in REQUIRED:
        try:
            __import__(module)
            print(f"{OK}{name:<12} {purpose}")
        except Exception as exc:  # noqa: BLE001
            all_ok = False
            print(f"{FAIL}{name:<12} 未安装（{exc}）")

    optional = [("paddleocr", "PaddleOCR（备选引擎）"), ("faiss", "FAISS（后续检索用）")]
    for module, name in optional:
        try:
            __import__(module)
            print(f"{OK}{name}")
        except Exception:  # noqa: BLE001
            print(f"{WARN}{name} 未安装（可选，不影响本期）")
    return all_ok


def check_ocr() -> bool:
    print("\n[3/5] OCR 引擎加载（首次较慢，请稍候）")
    try:
        from pm_ai import ocr_engine

        started = time.perf_counter()
        ocr_engine.get_engine("rapid")
        cost = time.perf_counter() - started
        print(f"{OK}RapidOCR 加载成功，耗时 {cost:.1f}s")
        if cost > 60:
            print(f"{WARN}加载偏慢——内网环境请确认模型文件是否已就位")
        return True
    except Exception as exc:  # noqa: BLE001
        print(f"{FAIL}OCR 加载失败：{type(exc).__name__}: {exc}")
        print("       内网环境常见原因：RapidOCR 模型文件未提前放置（见 README 离线安装一节）")
        print("       用 Docker 部署时不会遇到该问题——镜像内已自带模型，构建期已自检")
        return False


def check_llm() -> bool:
    print("\n[4/5] 大模型连通性（千问）")
    try:
        from pm_ai import llm_client
        from pm_ai.config import settings

        if not settings.llm_api_key:
            print(f"{WARN}未配置 LLM_API_KEY —— 请先 cp .env.example .env 并填写")
            return False

        started = time.perf_counter()
        ok, detail = llm_client.ping()
        cost = time.perf_counter() - started
        if ok:
            print(f"{OK}{detail}（{cost:.1f}s）")
            return True
        print(f"{FAIL}{detail}")
        print("       排查：① base_url 是否以 /v1 结尾　② 密钥是否正确　③ 网络是否可达")
        return False
    except Exception as exc:  # noqa: BLE001
        print(f"{FAIL}{type(exc).__name__}: {exc}")
        return False


def check_config() -> None:
    print("\n[5/5] 当前配置")
    from pm_ai.config import settings

    for key, value in settings.summary().items():
        print(f"       {key:<24} {value}")


def main() -> int:
    ap = argparse.ArgumentParser(description="AI 服务环境自检")
    ap.add_argument("--skip-llm", action="store_true", help="跳过大模型连通性检查")
    args = ap.parse_args()

    print("=" * 64)
    print("PM AI Service · 环境自检")
    print("=" * 64)

    results = [check_python(), check_deps(), check_ocr()]
    if not args.skip_llm:
        results.append(check_llm())
    check_config()

    print("\n" + "=" * 64)
    if all(results):
        print("全部通过，可以开始测试：")
        print("   python scripts/inventory.py <附件目录>          # 先摸底")
        print("   python scripts/ocr_try.py <某个文件> --save     # 再试识别")
        return 0

    print("存在未通过项，请先按上方提示解决（部分项不影响本期测试）。")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
