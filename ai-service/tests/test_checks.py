"""`checks.py` 确定性校验的回归测试。

这些断言原本只存在于模块注释和一次性手工脚本里——注释**只有人读得到**，
所以同类错误（大小写金额不一致、比例合计不对）会一次次重新出现。
这里把它们固化成可自动发现的断言。

所有断言都对着**当前实现**写（先读代码、再定期望值）；发现注释与实现不一致的地方，
在对应测试里显式注明，并在测试名/注释里写清"这条挡的是什么坑"。
"""

from __future__ import annotations

from decimal import Decimal

from pm_ai.checks import (
    _to_decimal,
    check_amount_case,
    check_document,
    check_number_style,
    check_percent_sum,
    cn_amount_to_number,
    verify_numbers_in_source,
)

# ══════════════════════════════════════════════════════════════════
# 中文大写金额解析
# ══════════════════════════════════════════════════════════════════


def test_cn_amount_documented_sample() -> None:
    """挡的是：注释里唯一被实测过的样例（合同上的「柒佰柒拾捌万元整」）被解析错。

    真实场景：平台 OCR 能完整读出这串大写，一旦解析器算错，大小写互校就会
    把"对的合同"判成不一致（或者反过来），复核人员从此不再信任这条检查。
    """
    assert cn_amount_to_number("柒佰柒拾捌万元整") == Decimal("7780000")


def test_cn_amount_section_join() -> None:
    """挡的是：万/亿分段拼接算错（大额合同必现）。

    实现里 `万` / `亿` 会把"当前段"结算后累加，段与段之间是**加法**：
    壹亿贰仟万 = 1 亿 + 2 千万 = 120,000,000，不是 1.2 亿之外的别的数。
    """
    assert cn_amount_to_number("壹亿贰仟万元整") == Decimal("120000000")
    # 逐级单位：叁仟贰佰壹拾 = 3000 + 200 + 10
    assert cn_amount_to_number("叁仟贰佰壹拾元整") == Decimal("3210")
    # 「拾万」省略前导「壹」（合同里很常见）→ 实现按 `number or 1` 处理 = 100,000
    assert cn_amount_to_number("拾万元整") == Decimal("100000")


def test_cn_amount_unparsable_returns_none() -> None:
    """挡的是：解析不出来时**猜一个数**。

    注释写得很清楚："宁可说'没看懂'，也不要猜一个数"。
    装饰字（人民币/元/整）、空串、以及金额为零（total == 0）都必须返回 None，
    否则大小写互校会把 None 当成 0 去和阿拉伯数字比，制造假失败。
    """
    assert cn_amount_to_number("") is None
    assert cn_amount_to_number("人民币元整") is None
    assert cn_amount_to_number("零元整") is None


def test_to_decimal_normalizes() -> None:
    """挡的是：阿拉伯金额串（带千分位/全角）被当成非法而丢掉。

    实现只显式替换了千分位 `,，` 与全角小数点 `．`，**没有**做全角→半角数字替换；
    全角数字能通过是因为 `Decimal` 自身接受 Unicode 数字（`Decimal("１２３") == 123`）。
    这是 docstring「统一全角」与实现之间的一处**轻微不一致**：这里按实测行为断言。
    """
    assert _to_decimal("1,234,567.89") == Decimal("1234567.89")
    assert _to_decimal("１２３") == Decimal("123")
    assert _to_decimal("１，２３４") == Decimal("1234")  # 全角逗号被替换掉
    assert _to_decimal("12．5") == Decimal("12.5")  # 全角小数点被替换掉
    assert _to_decimal(" 7,780,000.00 ") == Decimal("7780000.00")
    assert _to_decimal("abc") is None
    assert _to_decimal("") is None


# ══════════════════════════════════════════════════════════════════
# 金额大小写互校
# ══════════════════════════════════════════════════════════════════


def test_check_amount_case_pass_and_fail() -> None:
    """挡的是：金额被识别错却报"通过"（或反过来制造假警）。

    这是 checks.py 里最有价值的一条：大小写两个来源独立，错一个就会露出来。
    """
    ok = check_amount_case(["合同总价 ¥7,780,000.00（大写：柒佰柒拾捌万元整）。"])
    assert ok["name"] == "金额大小写互校"
    assert ok["status"] == "pass"
    assert ok["items"][0]["status"] == "pass"
    assert ok["items"][0]["page"] == 1

    bad = check_amount_case(["合同总价 ¥7,780,001.00（大写：柒佰柒拾捌万元整）。"])
    assert bad["status"] == "fail"
    assert bad["items"][0]["status"] == "fail"
    # detail 要把两个数都摊开给复核人员看，否则"哪里不一致"还得自己找
    assert "7,780,000" in bad["items"][0]["detail"]
    assert "7,780,001.00" in bad["items"][0]["detail"]


def test_check_amount_case_skips_without_pair() -> None:
    """挡的是：没有可配对的大小写金额时硬给一个结论。

    实现口径是 skip（"文中未找到可配对的大小写金额"），而不是 pass——
    "没检查"和"检查通过"在复核界面上是两回事。
    """
    assert check_amount_case(["本合同自双方签字之日起生效。"])["status"] == "skip"
    # 只有大写、没有阿拉伯数字 → 无从互校，同样 skip
    assert check_amount_case(["金额为柒佰柒拾捌万元整。"])["status"] == "skip"
    assert check_amount_case([])["status"] == "skip"


def test_check_amount_case_page_number_is_one_based() -> None:
    """挡的是：页码对不上（前端按 page 跳转，错一页等于把人带到错的地方）。"""
    result = check_amount_case(["第一页没有金额", "第二页 ¥10.00（壹拾元整）"])
    assert result["status"] == "pass"
    assert result["items"][0]["page"] == 2


def test_check_amount_case_requires_same_sentence() -> None:
    """挡的是：以为"大小写互校"是全页配对。

    实现按「。」「；」「;」和换行切分、**只在同一句/同一行内**配对
    （注释：大小写金额通常在同一句里）。
    所以把这句拆成两句后，这条检查会 skip 而不是误报 fail——这是刻意的口径，
    写清楚免得有人"顺手"改成全页配对引入大量假失败。
    """
    result = check_amount_case(["总价 ¥7,780,000.00。大写：柒佰柒拾捌万元整。"])
    assert result["status"] == "skip"


# ══════════════════════════════════════════════════════════════════
# 同段比例合计
# ══════════════════════════════════════════════════════════════════


def test_check_percent_sum_pass_and_warn() -> None:
    """挡的是：付款比例合计不是 100% 却没人提示（审计最常抓的问题之一）。"""
    ok = check_percent_sum(["付款：已付 70%，竣工后 30%。"])
    assert ok["status"] == "pass"
    assert ok["items"][0]["status"] == "pass"

    bad = check_percent_sum(["付款：已付 70%，竣工后 20%。"])
    assert bad["status"] == "warn"
    assert bad["items"][0]["status"] == "warn"


def test_check_percent_sum_tolerance_is_one_point() -> None:
    """挡的是：容差被改大/改小（实现是 |合计 - 100| <= 1，即 ±1 个百分点）。

    容差是有意的：OCR 会把 99.5% 读成 99%，卡死在 100 会产生大量假警。
    """
    assert check_percent_sum(["已付 70%，尾款 29%。"])["status"] == "pass"
    assert check_percent_sum(["已付 70%，尾款 28%。"])["status"] == "warn"


def test_check_percent_sum_splits_by_paragraph_not_sentence() -> None:
    """挡的是：把切分粒度从"段落"改成"句子"（注释里明确写过的实测坑）。

    「已付 70%」与「竣工后 30%」常在同一段的两个句子里：按句切就各只剩 1 个比例、
    整条检查白白跳过。所以单换行（同段）必须能合起来算 100%；
    而空行分隔的两段各自只有 1 个比例，只能 skip。
    """
    assert check_percent_sum(["已付 70%\n竣工后 30%"])["status"] == "pass"
    assert check_percent_sum(["已付 70%\n\n竣工后 30%"])["status"] == "skip"


def test_check_percent_sum_accepts_full_width_sign() -> None:
    """挡的是：全角百分号（扫描件里很常见）被漏掉。"""
    assert check_percent_sum(["已付 70％，竣工后 30％"])["status"] == "pass"


# ══════════════════════════════════════════════════════════════════
# 数值写法一致性
# ══════════════════════════════════════════════════════════════════


def test_number_style_flags_thousand_separator_mixing() -> None:
    """挡的是：同一数值千分位写法混用（1,234 / 1234）不被发现。"""
    result = check_number_style(["金额 1,234 元", "同一金额 1234 元"])
    assert result["status"] == "warn"
    assert "1,234" in result["items"][0]["detail"]


def test_number_style_misses_trailing_zero_variants() -> None:
    """⚠️ 注释与实现**不一致**，这里固化的是实测行为。

    模块 docstring 举例说「7,780,000.00」与「7780000」混用会被这条检查抓到，
    但实现用 `str(Decimal(...))` 当字典键：`Decimal("7780000.00")` → `"7780000.00"`，
    与 `"7780000"` 是两个不同的键，于是**不会**被报告为"同一数值的多种写法"。
    也就是说这条检查只认"值相同且字符串形态也相同（仅千分位/全角差异）"的混用。

    固化当前行为是为了：哪天有人按 docstring 去修（改成按数值归一键），
    这条测试会失败并迫使他回来读这段说明；目前它是"已知缺口"而不是"保证"。
    """
    result = check_number_style(["总价 7,780,000.00 元", "总价 7780000 元"])
    assert result["status"] == "pass"


# ══════════════════════════════════════════════════════════════════
# 答案数字可溯源（反幻觉）
# ══════════════════════════════════════════════════════════════════


def test_verify_numbers_in_source_pass_and_warn() -> None:
    """挡的是：模型答案里凭空出现原文没有的数字（"编造"的机器校验）。

    注意实现是按**数值**比较（Decimal 相等，与写法/末尾零无关），
    所以 `7,780,000` 与 `¥7,780,000.00` 视为同一个数——这也正是我们想要的。
    """
    pages = ["合同总价 ¥7,780,000.00 元。"]
    ok = verify_numbers_in_source("合同总价为 7,780,000 元。", pages)
    assert ok["status"] == "pass"
    assert ok["items"] == []

    bad = verify_numbers_in_source("合同总价为 9,999,999 元。", pages)
    assert bad["status"] == "warn"
    assert "9,999,999" in bad["items"][0]["detail"]


def test_verify_numbers_in_source_skips_answer_without_numbers() -> None:
    """挡的是：答案里没有数字时也给 pass（"没检查"必须显式是 skip）。"""
    assert verify_numbers_in_source("无法判断。", ["任意原文"])["status"] == "skip"


# ══════════════════════════════════════════════════════════════════
# 输出契约
# ══════════════════════════════════════════════════════════════════


def test_check_document_output_contract() -> None:
    """挡的是：检查结果的结构漂了，前端按 `status` 着色的那套跟着崩。

    契约：`{"name", "status", "detail", "items"}`，status 只能是 pass/warn/fail/skip。
    """
    result = check_document(["无金额无比例的一段文字。"])
    assert [c["name"] for c in result] == ["金额大小写互校", "同段比例合计", "数值写法一致性"]
    for item in result:
        assert {"name", "status", "detail", "items"} <= set(item)
        assert item["status"] in {"pass", "warn", "fail", "skip"}
