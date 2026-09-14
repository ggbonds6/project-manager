#!/usr/bin/env python
"""生成**合成测试样本**——用于在拿到真实附件前，先把整条链路跑通。

⚠️ 人名、身份证号、金额、文号**全部为虚构**，仅用于技术验证，与任何真实项目无关。

为什么需要它：真实附件要从系统里导、还要脱敏，而"链路通不通、OCR 认不认得数字"
这件事可以用合成样本先确认。**它不替代真实附件验证**，只是让你先有个可跑的东西。

生成的样本刻意覆盖三类难度：

| 样本 | 类型 | 验证目的 |
| --- | --- | --- |
| 资金批复 | 电子版（有文本层） | 确认文本型 PDF 直接取文本、不浪费 OCR |
| 中标通知书 | 扫描件（图片型） | 通用 OCR 对中文正文的质量 |
| 支付报销单 | 扫描件 + 表格 | **数字密集**，审计命门所在 |
| 人员信息表 | 扫描件 + 证件字段 | 身份证号这类长数字串的识别 |

用法：
    python scripts/make_samples.py [输出目录]     # 默认 ./samples
"""

from __future__ import annotations

import sys
from pathlib import Path

CJK_FONT = "china-s"  # PyMuPDF 内置简体中文字体（无需系统字体）
PAGE_W, PAGE_H = 595, 842  # A4 @72dpi
SCAN_DPI = 200  # 渲染成"扫描件"时的 DPI

# ── 合成数据（虚构）───────────────────────────────────────────

APPROVAL = [
    ("关于公共卫生督导系统国产化改造项目资金批复", 18, 0),
    ("", 10, 0),
    ("项目编号：WSJK-2025-0037", 13, 0),
    ("批复单位：XX市财政局", 13, 0),
    ("批复文号：XX财建〔2025〕128号", 13, 0),
    ("批复金额：人民币 8,645,500.00 元", 13, 0),
    ("批复日期：2025年3月18日", 13, 0),
    ("", 10, 0),
    ("建设内容：公共卫生督导系统国产化改造，含公卫督导、", 13, 0),
    ("CDR、检查预约平台、医学影像、体检系统五个子系统。", 13, 0),
    ("资金来源：市级财政专项资金。", 13, 0),
]

AWARD = [
    ("中标通知书", 20, 0),
    ("", 10, 0),
    ("项目名称：公共卫生督导系统国产化改造项目", 13, 0),
    ("招标编号：ZB-2025-0821", 13, 0),
    ("中标单位：XX信息技术有限公司", 13, 0),
    ("中标金额：人民币 7,982,300.00 元", 13, 0),
    ("项目负责人：张伟", 13, 0),
    ("中标日期：2025年6月9日", 13, 0),
    ("", 10, 0),
    ("请中标单位在收到本通知书后 30 日内与招标人签订合同。", 13, 0),
]

# 表格用固定列坐标绘制，模拟真实表格的列对齐
REIMBURSE_TITLE = [
    ("支付报销单", 18, 0),
    ("", 8, 0),
    ("项目名称：公共卫生督导系统国产化改造项目", 12, 0),
    ("合同金额：人民币 7,982,300.00 元", 12, 0),
    ("", 8, 0),
]

REIMBURSE_HEADER = ["序号", "支付节点", "比例", "支付金额(元)", "支付日期"]
REIMBURSE_ROWS = [
    ["1", "合同签订", "30%", "2,394,690.00", "2025-06-20"],
    ["2", "初验通过", "40%", "3,192,920.00", "2025-11-15"],
    ["3", "终验通过", "20%", "1,596,460.00", "2026-03-10"],
    ["4", "质保期满", "10%", "798,230.00", "2027-03-10"],
]
REIMBURSE_TOTAL = ["合计", "", "100%", "7,982,300.00", ""]
REIMBURSE_COLS = [60, 130, 240, 300, 430]  # 各列起始 x 坐标

PERSONNEL = [
    ("投标单位人员信息表", 18, 0),
    ("", 10, 0),
    ("投标单位：XX信息技术有限公司", 12, 0),
    ("", 8, 0),
    ("法定代表人：李建国", 13, 0),
    ("身份证号：320102198005121234", 13, 0),
    ("", 6, 0),
    ("委托代理人：王小明", 13, 0),
    ("身份证号：320104198711085678", 13, 0),
    ("", 6, 0),
    ("项目负责人：张伟", 13, 0),
    ("身份证号：320106198203194321", 13, 0),
    ("", 6, 0),
    ("社保缴纳单位：XX信息技术有限公司", 13, 0),
    ("缴纳起止：2025年1月至2026年3月", 13, 0),
]


def _write_lines(page, items, start_y: float = 80.0, leading: float = 1.9) -> float:
    """按行写文本，返回下一个可用的 y 坐标。"""
    y = start_y
    for text, size, _ in items:
        if text:
            page.insert_text((60, y), text, fontname=CJK_FONT, fontsize=size)
        y += size * leading
    return y


def _write_table(page, y: float) -> None:
    """绘制支付明细表（固定列坐标，模拟真实表格）。"""
    row_h = 30
    for text, x in zip(REIMBURSE_HEADER, REIMBURSE_COLS):
        page.insert_text((x, y), text, fontname=CJK_FONT, fontsize=11)
    y += row_h * 0.8
    for row in [*REIMBURSE_ROWS, REIMBURSE_TOTAL]:
        for text, x in zip(row, REIMBURSE_COLS):
            if text:
                page.insert_text((x, y), text, fontname=CJK_FONT, fontsize=11)
        y += row_h


def _to_scanned(pdf_path: Path, dpi: int = SCAN_DPI) -> None:
    """把文本型 PDF 转成**图片型 PDF**（即"扫描件"）：文本层清空，必须 OCR 才能读。"""
    import fitz

    src = fitz.open(pdf_path)
    out = fitz.open()
    for page in src:
        rect = page.rect
        pix = page.get_pixmap(dpi=dpi)
        new_page = out.new_page(width=rect.width, height=rect.height)
        new_page.insert_image(rect, stream=pix.tobytes("png"))
    src.close()
    tmp = pdf_path.with_name(pdf_path.stem + ".__tmp.pdf")
    out.save(str(tmp), deflate=True)
    out.close()
    tmp.replace(pdf_path)


def _new_pdf():
    """新建一个单页 A4 文档，返回 (doc, page)。"""
    import fitz

    doc = fitz.open()
    page = doc.new_page(width=PAGE_W, height=PAGE_H)
    return doc, page


def main() -> int:
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("samples")
    out_dir.mkdir(parents=True, exist_ok=True)

    try:
        import fitz  # noqa: F401
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] 需要 PyMuPDF：{exc}")
        return 1

    print(f"输出目录：{out_dir.resolve()}\n")

    # ① 电子版批复（保留文本层）
    doc, page = _new_pdf()
    _write_lines(page, APPROVAL)
    p1 = out_dir / "合成样本1-资金批复(电子版).pdf"
    doc.save(str(p1))
    doc.close()
    print(f"[OK] {p1.name}   电子版（文本层保留）")

    # ② 扫描件中标通知书
    doc, page = _new_pdf()
    _write_lines(page, AWARD)
    p2 = out_dir / "合成样本2-中标通知书(扫描件).pdf"
    doc.save(str(p2))
    doc.close()
    _to_scanned(p2)
    print(f"[OK] {p2.name}   扫描件（图片型，需 OCR）")

    # ③ 扫描件支付报销单（表格）
    doc, page = _new_pdf()
    y = _write_lines(page, REIMBURSE_TITLE)
    _write_table(page, y + 10)
    p3 = out_dir / "合成样本3-支付报销单(扫描件表格).pdf"
    doc.save(str(p3))
    doc.close()
    _to_scanned(p3)
    print(f"[OK] {p3.name}   扫描件 + 表格")

    # ④ 扫描件人员信息表（身份证号）
    doc, page = _new_pdf()
    _write_lines(page, PERSONNEL)
    p4 = out_dir / "合成样本4-人员信息表(扫描件).pdf"
    doc.save(str(p4))
    doc.close()
    _to_scanned(p4)
    print(f"[OK] {p4.name}   扫描件 + 证件字段")

    print()
    print("下一步：")
    print("  python scripts/inventory.py samples        # 摸底（应看到 3 份 scanned、1 份 text_pdf）")
    print("  python scripts/ocr_try.py samples/合成样本3-支付报销单(扫描件表格).pdf --save")
    print()
    print("⚠️ 合成样本全部为虚构数据，仅用于验证链路；真实效果仍需用实际附件复核。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
