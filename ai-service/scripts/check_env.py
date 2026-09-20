#!/usr/bin/env python
"""环境自检——装完依赖后**先跑这个**，避免在真正测试时才发现环境问题。

检查项：
  1. Python 版本
  2. 关键依赖是否装齐
  3. **平台 OCR** 能否连通（内网网关，需先配好 .env；本地引擎已于 2026-09-18 移除）
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

    # 本地引擎已于 2026-09-18 移除，故可选依赖里不再有 paddleocr
    optional = [("faiss", "FAISS（后续检索用）")]
    for module, name in optional:
        try:
            __import__(module)
            print(f"{OK}{name}")
        except Exception:  # noqa: BLE001
            print(f"{WARN}{name} 未安装（可选，不影响本期）")
    return all_ok


def check_platform_ocr() -> bool:
    """检查**平台 OCR 连通性**。

    为什么这里改为探连通性而不是"加载引擎"：本地引擎（RapidOCR/PaddleOCR）已随兜底逻辑
    一起移除，平台是唯一识别通道——平台不通就等于"服务没有 OCR 能力"，
    这是必须在自检里硬性暴露的问题（以前有本地兜底，探不通还能降级，现在不能）。
    """
    print("\n[3/5] 平台 OCR 连通性（内网网关）")
    try:
        from pm_ai import platform_ocr
        from pm_ai.config import settings

        started = time.perf_counter()
        h = platform_ocr.health(force=True, timeout=5)
        cost = time.perf_counter() - started
        if h.ok:
            print(f"{OK}PaddleOCR-VL 可用：{h.detail}（{cost:.1f}s）")
            return True
        print(f"{FAIL}平台 OCR 不可用：{h.detail}")
        addr = settings.ocr_base_url or "未配置（OCR_BASE_URL 或 LLM_BASE_URL）"
        print(f"       平台地址：{addr}")
        print("       排查：① 网关地址是否正确　② sk 是否有效（401）　③ 网络能否访问该地址")
        print("       注意：本地 OCR 兜底已于 2026-09-18 移除，平台不通就没有可用的识别能力")
        return False
    except Exception as exc:  # noqa: BLE001
        print(f"{FAIL}{type(exc).__name__}: {exc}")
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

    results = [check_python(), check_deps(), check_platform_ocr()]
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
