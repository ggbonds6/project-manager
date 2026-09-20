"""确定性校验：把"可信度"从主观打分换成**可复现的检查**。

## 为什么需要它

之前的做法是让**模型自评置信度**（0.00–1.00）。它有用（能排序复核优先级），但有两个硬伤：

1. **不可验证** —— 0.87 是怎么来的？没人知道，也无法复现；
2. **实测会失灵** —— 曾出现"OCR 平均置信度 0.97 的文件里金额被认错"，分数高不等于内容对。

所以再加一层**代码算出来的、可复现的判断**：

| 检查 | 判据 | 为什么可信 |
| --- | --- | --- |
| 金额大小写互校 | 阿拉伯数字金额 vs 中文大写金额是否同一个数 | 两个独立来源互相印证，**错一个就会露出来** |
| 同一数值写法一致 | 同一数字在文中出现多次时写法是否一致 | 见「7,780,000.00」与「7780000」混用会误读 |
| 比例合计 | 同一段里各期比例之和是否接近 100% | 算术，与语义无关 |
| 答案数字可溯源 | **模型答案里的每个数字是否真能在原文找到** | 这条直接卡"编造"——比让模型自评可靠得多 |

输出统一为 `{"name", "status": pass|warn|fail|skip, "detail", "items"}`，
前端按 status 着色；**任何一条都不修改原文**，只做"指出"。
"""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation

# ── 中文大写金额解析 ─────────────────────────────────────────────

_CN_DIGIT = {
    "零": 0,
    "壹": 1,
    "贰": 2,
    "叁": 3,
    "肆": 4,
    "伍": 5,
    "陆": 6,
    "柒": 7,
    "捌": 8,
    "玖": 9,
}
_CN_UNIT = {"拾": 10, "佰": 100, "仟": 1000}
_CN_SECTION = {"万": 10**4, "亿": 10**8}

_CN_AMOUNT_RE = re.compile(r"[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]{2,}")
"""连续的中文大写数字（含单位）。"""

_NUM_AMOUNT_RE = re.compile(r"(\d{1,3}(?:[,，]\d{3})+(?:[.．]\d{1,2})?|\d+[.．]\d{1,2})")
"""阿拉伯数字金额：带千分位或有小数位。"""

_PERCENT_RE = re.compile(r"(\d+(?:[.．]\d+)?)\s*[%％]")


def cn_amount_to_number(text: str) -> Decimal | None:
    """中文大写金额 → 数字。`柒佰柒拾捌万元整` → `7780000`。

    不认识的字符直接跳过（"人民币"/"元"/"整"等），不影响结果。
    解析失败返回 None —— **宁可说"没看懂"，也不要猜一个数**。
    """
    if not text:
        return None
    raw = text.replace("整", "").replace("正", "")
    total = Decimal(0)  # 已结算的「万/亿」段
    section = Decimal(0)  # 当前段
    number = Decimal(0)  # 当前数字
    seen = False
    for ch in raw:
        if ch in _CN_DIGIT:
            number = Decimal(_CN_DIGIT[ch])
            seen = True
        elif ch in _CN_UNIT:
            unit = Decimal(_CN_UNIT[ch])
            # "拾万"这类省略了前导"壹"
            section += (number or Decimal(1)) * unit
            number = Decimal(0)
            seen = True
        elif ch in _CN_SECTION:
            section = (section + number) * Decimal(_CN_SECTION[ch])
            total += section
            section = Decimal(0)
            number = Decimal(0)
            seen = True
        # 其余字符（人民币/元/角/分…）忽略
    total += section + number
    if not seen or total == 0:
        return None
    return total


def _to_decimal(raw: str) -> Decimal | None:
    """阿拉伯数字串 → Decimal（去千分位、统一全角）。"""
    s = raw.replace(",", "").replace("，", "").replace("．", ".").strip()
    try:
        return Decimal(s)
    except (InvalidOperation, ValueError):
        return None


# ── 单条检查 ────────────────────────────────────────────────────


def check_amount_case(pages: list[str]) -> dict:
    """金额大小写互校——**最有价值的一条**。

    同一处金额通常会同时写「¥7,780,000.00」和「柒佰柒拾捌万元整」。
    两个来源独立，一旦其中一个被识别/摘录错，这里立刻能看出来。
    """
    items: list[dict] = []
    checked = 0
    for page_no, text in enumerate(pages, 1):
        # 按行/句分组：大小写金额通常在同一句里
        for seg in re.split(r"[。；;\n]", text):
            cn = _CN_AMOUNT_RE.findall(seg)
            ar = _NUM_AMOUNT_RE.findall(seg)
            if not cn or not ar:
                continue
            cn_num = next((n for n in (cn_amount_to_number(c) for c in cn) if n is not None), None)
            ars = [(_to_decimal(a), a) for a in ar]
            ars = [(n, a) for n, a in ars if n is not None]
            if cn_num is None or not ars:
                continue
            checked += 1
            match = next((a for n, a in ars if n == cn_num), None)
            if match:
                items.append(
                    {"page": page_no, "status": "pass", "detail": f"{match} ↔ {cn[0]}（一致）"}
                )
            else:
                items.append(
                    {
                        "page": page_no,
                        "status": "fail",
                        "detail": f"大写「{cn[0]}」= {cn_num:,}，"
                        f"但阿拉伯数字写作 {[a for _, a in ars]} —— **不一致，必须人工核对**",
                    }
                )
    if not checked:
        return {
            "name": "金额大小写互校",
            "status": "skip",
            "detail": "文中未找到可配对的大小写金额",
            "items": [],
        }
    fails = [i for i in items if i["status"] == "fail"]
    return {
        "name": "金额大小写互校",
        "status": "fail" if fails else "pass",
        "detail": f"共核对 {checked} 处"
        + (f"，**{len(fails)} 处不一致**" if fails else "，全部一致"),
        "items": items,
    }


def check_percent_sum(pages: list[str]) -> dict:
    """比例合计：同一段里的各期比例之和是否接近 100%。

    ⚠️ 切分粒度是**段落**而不是句子——实测合同里「已付 70%」与「竣工后 30%」
    就在同一段的两个句子里，按句切会漏掉。但同段也可能混入无关比例（进度、完成率），
    所以不通过时只给 `warn`（提示），不下结论。
    """
    items: list[dict] = []
    for page_no, text in enumerate(pages, 1):
        for seg in re.split(r"\n\s*\n", text):
            nums = [_to_decimal(m) for m in _PERCENT_RE.findall(seg)]
            nums = [n for n in nums if n is not None]
            if len(nums) < 2:
                continue
            total = sum(nums)
            ok = abs(total - Decimal(100)) <= Decimal(1)
            items.append(
                {
                    "page": page_no,
                    "status": "pass" if ok else "warn",
                    "detail": f"「{seg.strip()[:60]}…」比例合计 {total.normalize()}%"
                    + ("" if ok else "（不等于 100%，也可能只是同段的无关比例，请自行判断）"),
                }
            )
    if not items:
        return {
            "name": "同段比例合计",
            "status": "skip",
            "detail": "未找到同段多比例的情形",
            "items": [],
        }
    return {
        "name": "同段比例合计",
        "status": "warn" if any(i["status"] == "warn" for i in items) else "pass",
        "detail": f"检查 {len(items)} 段",
        "items": items,
    }


def check_number_style(pages: list[str]) -> dict:
    """同一数值的**写法是否一致**（全角/半角、千分位混用会误读）。"""
    styles: dict[str, set[str]] = {}
    for text in pages:
        for raw in re.findall(r"[0-9０-９][0-9０-９,，.．]*", text):
            if len(raw) < 4:
                continue
            num = _to_decimal(
                raw.replace("０", "0")
                .replace("１", "1")
                .replace("２", "2")
                .replace("３", "3")
                .replace("４", "4")
                .replace("５", "5")
                .replace("６", "6")
                .replace("７", "7")
                .replace("８", "8")
                .replace("９", "9")
            )
            if num is None:
                continue
            styles.setdefault(str(num), set()).add(raw)
    mixed = {k: sorted(v) for k, v in styles.items() if len(v) > 1}
    if not mixed:
        return {
            "name": "数值写法一致性",
            "status": "pass",
            "detail": "未发现同一数值的多种写法",
            "items": [],
        }
    items = [
        {"status": "warn", "detail": f"{k} 出现了 {len(v)} 种写法：{' / '.join(v)}"}
        for k, v in list(mixed.items())[:12]
    ]
    return {
        "name": "数值写法一致性",
        "status": "warn",
        "detail": f"{len(mixed)} 个数值存在多种写法（可能是全角/千分位混用）",
        "items": items,
    }


def check_document(pages: list[str]) -> list[dict]:
    """对原文跑全部检查。`pages` 是**逐页文本**（页码即 list 下标 + 1）。"""
    return [check_amount_case(pages), check_percent_sum(pages), check_number_style(pages)]


# ── 输出侧：答案里的数字能不能在原文找到 ──────────────────────────

_ANY_NUMBER_RE = re.compile(
    r"[¥￥]?\s*(\d{1,3}(?:[,，]\d{3})+(?:[.．]\d{1,2})?|\d+(?:[.．]\d{1,2})?)"
)


def _normalized_numbers(text: str) -> set[Decimal]:
    out: set[Decimal] = set()
    for raw in _ANY_NUMBER_RE.findall(text):
        n = _to_decimal(raw)
        if n is not None and n != 0:
            out.add(n)
    return out


def verify_numbers_in_source(answer: str, pages: list[str]) -> dict:
    """**反幻觉**：模型答案里的每个数字，是否真能在原文里找到。

    这是"禁止虚构"的机器校验——比模型自评置信度可靠。找不到的数字单独列出来，
    提示"可能是推断/计算得来，或可能是编造"（**推断也可能是合理的**，例如按比例反推总额，
    所以状态是 warn 而不是 fail，由人判断）。
    """
    src = _normalized_numbers("\n".join(pages))
    ans = _normalized_numbers(answer)
    if not ans:
        return {"name": "答案数字可溯源", "status": "skip", "detail": "答案中没有数字", "items": []}
    missing = sorted(ans - src)
    items = [
        {"status": "warn", "detail": f"{n:,} 未在原文中找到（可能是推断/计算，也可能是编造）"}
        for n in missing[:20]
    ]
    return {
        "name": "答案数字可溯源",
        "status": "pass" if not missing else "warn",
        "detail": f"答案含 {len(ans)} 个数值，其中 {len(ans) - len(missing)} 个在原文中找到"
        + (f"，**{len(missing)} 个未找到**" if missing else ""),
        "items": items,
    }
