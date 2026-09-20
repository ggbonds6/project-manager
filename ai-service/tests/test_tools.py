"""`tools.py` 的回归测试（**离线**：检索用空文档库替身，不算真文档、不联网）。

重点两条：
- `calculate` 的**白名单**：只允许数字与 `+ - * /`，且全程 Decimal；
- 工具分发 `execute` 的参数过滤（历史上被 lambda 包一层坑过一次）。
"""

from __future__ import annotations

import pytest

from pm_ai import tools as tools_mod
from pm_ai.tools import TOOL_SCHEMAS, calculate, execute


class _EmptyStore:
    """空文档库替身：让检索类工具不依赖 `work/docs` 里的真实数据。"""

    def get(self, doc_id):
        return None

    def all_docs(self):
        return iter(())


# ══════════════════════════════════════════════════════════════════
# calculate：精确算术
# ══════════════════════════════════════════════════════════════════


def test_calculate_returns_string_result() -> None:
    """挡的是：`result` 变回数字类型（JSON 浮点会丢分位精度）。

    审计口径要求"看到什么就是什么"，所以结果一律是字符串。
    """
    result = calculate("2394690 + 3192920")
    assert result["result"] == "5587610"
    assert isinstance(result["result"], str)
    assert result["expression"] == "2394690 + 3192920"


def test_calculate_is_exact_for_tenths() -> None:
    """挡的是：算钱悄悄换回 float（0.1 + 0.2 != 0.3）。

    这条就是"金额计算用 Decimal 而不是 float"的钉子：一旦有人把 `_eval_node`
    改回 float，`result` 会变成 `0.30000000000000004`，这里立刻红。
    """
    result = calculate("0.1 + 0.2")
    assert result["result"] == "0.3"
    assert result["rounded_2"] == "0.30"


def test_calculate_handles_commas_and_rounding() -> None:
    """挡的是：合同里抄来的金额（带千分位）算不了；以及"精确值 + 到分"两个口径混用。"""
    assert calculate("1,234,567.89 - 234,567.89")["result"] == "1000000"
    # 除不尽时 result 保留高精度、rounded_2 才是到分（前端要哪个取哪个）
    assert calculate("1 / 3")["rounded_2"] == "0.33"
    assert calculate("2 * 3 - 1")["result"] == "5"


@pytest.mark.parametrize(
    "expression",
    [
        '__import__("os")',  # 任意代码执行：最直接的攻击面
        "(1).__class__",  # 属性访问：从这里能摸到 type/mro，进而逃出沙箱
        "9**9**9",  # 乘方：大整数幂会把 CPU/内存打满（长度上限挡不住它）
        "7 // 2",  # 取整：审计场景用不到，白名单刻意不给
        "7 % 2",  # 取模：同上
        "True",  # bool 是 int 的子类，必须显式排除
        "abc",  # 名字而非数字
        "1 +",  # 语法错
    ],
)
def test_calculate_rejects_non_whitelisted(expression) -> None:
    """挡的是：沙箱收紧之后又被放开。

    实现刻意只留四则运算（见 `_OPS` 的注释）：`9**9**9` 这类表达式 200 字符的
    长度上限根本拦不住，只能靠"运算符白名单"。**任何拒绝都必须是结构化 error，
    不能抛异常**（抛出去会中断整轮问答）。
    """
    result = calculate(expression)
    assert "error" in result
    assert "result" not in result


def test_calculate_rejects_too_long_expression() -> None:
    """挡的是：超长表达式（>200 字符）被硬算——它是拒绝乘方之外的第二道闸。"""
    result = calculate("1+" * 101)
    assert result["error"] == "表达式过长"


def test_calculate_empty_and_division_by_zero() -> None:
    """挡的是：空表达式 / 除零把异常抛给上层。"""
    assert calculate("")["error"] == "表达式为空"
    assert calculate("   ")["error"] == "表达式为空"
    assert "error" in calculate("1 / 0")


# ══════════════════════════════════════════════════════════════════
# 工具注册表与分发
# ══════════════════════════════════════════════════════════════════


def test_execute_filters_parameters_by_signature() -> None:
    """挡的是：`_DISPATCH` 又用 lambda 包一层。

    踩过的坑（代码注释里写着）：`inspect.signature(lambda **kw: ...)` 只看得到 `**kw`，
    参数过滤会把模型传来的参数**全丢掉**，症状是
    `search_documents() missing 1 required positional argument: 'query'`。
    所以 `execute` 必须把多余/未知参数丢掉，同时保留真实参数。
    """
    result = execute("calculate", {"expression": "2394690 + 3192920", "多余参数": 1})
    assert result["result"] == "5587610"

    assert execute("不存在的工具", {})["error"].startswith("未知工具")


def test_tool_registry_matches_dispatch() -> None:
    """挡的是：schema 里暴露了工具、`_DISPATCH` 里却没有（模型调用必然报错）。

    另外钉住 `list_documents` **已被删除**（清单改由 system 提示词注入）：
    它要是被"顺手加回来"，模型会先白烧一轮往返列文档。
    """
    names = {schema["function"]["name"] for schema in TOOL_SCHEMAS}
    assert names == {"search_documents", "read_page", "calculate"}
    assert set(tools_mod._DISPATCH) == names


# ══════════════════════════════════════════════════════════════════
# 检索 / 读页（用空库替身，纯离线）
# ══════════════════════════════════════════════════════════════════


def test_search_documents_empty_input_is_safe(monkeypatch) -> None:
    """挡的是：空查询 / 空文档库让工具炸掉。

    检索是问答的第一步，它一抛异常整轮问答就断了。空输入应当返回
    `found=0` + 一句"换个关键词"的 hint（而不是异常，也不是编造内容）。
    """
    monkeypatch.setattr(tools_mod, "store", _EmptyStore())
    for query in ("", "   "):
        result = tools_mod.search_documents(query)
        assert result["found"] == 0
        assert result["hits"] == []
        assert "hint" in result

    # 模型常把数字传成字符串（"10"）——实现里做容错，这里确认不炸
    assert tools_mod.search_documents("付款条款", top_k="不是数字")["found"] == 0


def test_read_page_unknown_doc_returns_error(monkeypatch) -> None:
    """挡的是：找不到文档时抛异常而不是给出可读的错误。"""
    monkeypatch.setattr(tools_mod, "store", _EmptyStore())
    result = tools_mod.read_page("no-such-doc", 1)
    assert "找不到文档" in result["error"]
