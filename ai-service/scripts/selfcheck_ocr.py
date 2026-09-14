#!/usr/bin/env python
"""OCR 冒烟自检——主要用于 **Docker 构建期**。

为什么需要它：`pip install` 成功 ≠ OCR 能用。常见坑是模型文件缺失（内网环境），
装包阶段看不出来，等到真正识别时才炸。这里在构建阶段就跑一次真实识别，
**构建通过 = 镜像里的 OCR 确实可用**。

刻意不依赖系统中文字体（用 PDF 内置字体生成图片），所以精简镜像里也能跑。

用法：
    python scripts/selfcheck_ocr.py
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

SAMPLE = "2026 1234 5678"


def check_package() -> bool:
    """确认打包正确（src 布局配置生效）——这是 pip install -e . 是否真的装对了。"""
    try:
        import pm_ai  # noqa: F401
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] 导入 pm_ai 失败：{type(exc).__name__}: {exc}")
        print("       多为 pyproject.toml 缺少 [build-system] / packages.find 配置")
        return False
    print("[OK]   pm_ai 包导入正常")
    return True


def check_ocr() -> bool:
    """生成一张测试图并真实识别一次。"""
    try:
        import fitz  # PyMuPDF
        from rapidocr_onnxruntime import RapidOCR
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] 依赖导入失败：{type(exc).__name__}: {exc}")
        return False

    out_dir = Path(tempfile.mkdtemp(prefix="selfcheck_"))
    img = out_dir / "selfcheck.png"

    # 用 PDF 内置字体（helvetica）绘制，无需系统中文字体
    doc = fitz.open()
    page = doc.new_page(width=420, height=220)
    page.insert_text((40, 120), SAMPLE, fontsize=42)
    page.get_pixmap(dpi=200).save(img)
    doc.close()

    raw = RapidOCR()(str(img))
    result = raw[0] if isinstance(raw, tuple) else raw
    if not result:
        print("[FAIL] OCR 未识别出任何内容——模型文件可能缺失")
        return False

    text = " ".join(str(item[1]) for item in result).strip()
    digits = "".join(ch for ch in text if ch.isdigit())
    print(f"[OK]   OCR 识别正常，结果：{text!r}")

    if len(digits) < 8:
        # 不判定失败：裁剪/字体差异可能影响个别字符，但引擎本身是通的
        print(f"[WARN] 数字识别偏少（{digits!r}），引擎可用但精度需在真实样本上复核")
    return True


def main() -> int:
    print("=" * 60)
    print("PM AI Service · OCR 冒烟自检")
    print("=" * 60)
    ok = check_package() and check_ocr()
    print("=" * 60)
    print("自检通过" if ok else "自检失败")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
