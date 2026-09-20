"""模型可调用的工具集。

## 设计原则

**只给模型"它做不到的事"，不给"它已经会的事"。**

| 工具 | 为什么必须做成工具 |
| --- | --- |
| `search_documents` | 长文档塞不进上下文，必须按需检索 |
| `read_page` | 检索到的片段可能不够，需要读整页原文核对 |
| `calculate` | **模型算数不可靠**，金额求和/比例校验必须交给代码 |

> **为什么没有 `list_documents`**（2026-09-18 删除）：文档清单已经由
> `qa.build_messages` → `prompts.build_doc_scope_note` 注入 system 提示词
> （`doc_id | 文件名 | 页数`），工具返回的信息与之重复，只会引诱模型"先列一遍文档"
> 白烧一轮往返。清单若要展示更多字段，改 scope note 即可。
>
> 判据（提工具前先问）：只有模型**物理上做不到**的才值得做成工具——看不见的数据
> （检索）、算不准的（精确算术）；模型本来就会的（摘要/抽取/判断/格式化）交给提示词，
> 不要工具化；只是"想让流程更自主"的更不要（见下条）。

## 为什么不做成"全流程 agent 自主调度"

文档处理的步骤是**确定的**（解析 → 检索 → 回答），用代码编排比让模型自己决定更快更稳。
工具的价值在于**补模型的能力短板**（记忆、精确计算、全文检索），
而不是把编排权也交出去——审计场景要求可复现、可解释。

## ⚠️ 将来接向量库只改这里

`search_documents` 的实际实现在 `retrieval.py`：
**向量召回（Qwen3-VL-Embedding）+ 关键词召回（2-gram + IDF）→ Reranker 精排**。
工具这一层只做"参数容错 + 结果整形"，**工具签名不变**——这正是当初把检索单独分层的意义：
换检索算法（关键词 → 混合 → 将来换 OpenSearch kNN）不用动提示词与问答编排。
"""

from __future__ import annotations

import ast
import operator
from decimal import Decimal
from typing import Any

from . import retrieval
from .store import store

# ══════════════════════════════════════════════════════════════════
# 工具 1：检索文档
# ══════════════════════════════════════════════════════════════════


def search_documents(query: str, top_k: int = 5, doc_id: str | None = None) -> dict:
    """在文档中检索相关段落。`doc_id` 为空则检索全部已上传文档。"""
    # 模型有时把数字传成字符串（"10"），这里做容错
    try:
        top_k = int(top_k)
    except (TypeError, ValueError):
        top_k = 5
    result = retrieval.search(query or "", top_k=top_k, doc_id=doc_id)
    if not result["hits"]:
        return {
            "found": 0,
            "hits": [],
            "hint": "没有检索到相关内容。可换关键词（如换成金额、合同编号、条款名），或确认问题涉及的文档是否已上传。",
            # note 会写明"向量服务不可用，本次仅关键词召回"这类降级信息，别吞掉
            "note": result.get("note", ""),
        }
    return {
        "found": result["found"],
        "hits": result["hits"],
        "retrieval": result["retrieval"],
        "reranked": result["reranked"],
        "note": result["note"],
    }


# ══════════════════════════════════════════════════════════════════
# 工具 2：读整页
# ══════════════════════════════════════════════════════════════════


def read_page(doc_id: str, page_no: int) -> dict:
    """读取指定文档的某一页原文（核对上下文用）。"""
    doc = store.get(doc_id) if doc_id else None
    if doc is None:
        return {"error": f"找不到文档 {doc_id}；文档清单见 system 提示词。"}
    try:
        page_no = int(page_no)
    except (TypeError, ValueError):
        return {"error": f"页码无效：{page_no}"}
    text = doc.page_text(page_no)
    if not text.strip():
        return {
            "error": f"{doc.filename} 第 {page_no} 页没有文本（可能是空白页或未识别）。",
            "total_pages": doc.page_count,
        }
    return {
        "doc_id": doc.doc_id,
        "filename": doc.filename,
        "page_no": int(page_no),
        "total_pages": doc.page_count,
        "text": text[:4000] + ("…（本页过长，已截断）" if len(text) > 4000 else ""),
    }


# ══════════════════════════════════════════════════════════════════
# 工具 3：计算（金额求和、比例校验等）
# ══════════════════════════════════════════════════════════════════

# ⚠️ 只留四则运算。**刻意不给 `**` / `//` / `%`**（2026-09-18 收紧）：
# 表达式长度上限 200 字符挡不住 `9**9**9` —— 大整数幂会算出一个天文数字，CPU/内存被打满；
# 而"金额求和、比例校验、差额"这些审计场景根本用不到乘方/取整/取模。
_OPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}


def _eval_node(node: ast.AST) -> Decimal:
    """按白名单求值。**全程 Decimal**，与 `checks.py` 的校验口径保持一致。

    原实现走 float：同一个服务里"金额校验用十进制、金额计算用浮点"，
    大额累加会出现分位漂移，`round(value, 4)` 只是把误差藏起来。
    """
    if isinstance(node, ast.Expression):
        return _eval_node(node.body)
    if isinstance(node, ast.Constant):
        # bool 是 int 的子类，显式排除，避免 True/False 被当成数字
        if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
            raise ValueError("只支持数字")
        return Decimal(str(node.value))
    if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
        return _OPS[type(node.op)](_eval_node(node.left), _eval_node(node.right))
    if isinstance(node, ast.UnaryOp) and type(node.op) in _OPS:
        return _OPS[type(node.op)](_eval_node(node.operand))
    raise ValueError("仅支持数字与 + - * / ( )")


def calculate(expression: str) -> dict:
    """做精确算术。**金额求和、比例校验请用本工具，不要自己心算。**

    例：`calculate("2394690 + 3192920 + 1596460 + 798230")`

    返回的 `result` 是**字符串**而不是数字：JSON 里的浮点会丢分位精度，
    而审计口径要求"看到什么就是什么"。需要四舍五入到分时用 `rounded_2`。
    """
    expr = (expression or "").strip().replace(",", "").replace("，", "")
    if not expr:
        return {"error": "表达式为空"}
    if len(expr) > 200:
        return {"error": "表达式过长"}
    try:
        # 不用 eval：只允许数字与四则运算，避免任意代码执行
        value = _eval_node(ast.parse(expr, mode="eval"))
    except Exception as exc:  # noqa: BLE001
        return {"error": f"无法计算：{exc}"}
    return {
        "expression": expression,
        "result": format(value.normalize(), "f"),
        "rounded_2": format(value.quantize(Decimal("0.01")), "f"),
    }


# ══════════════════════════════════════════════════════════════════
# 工具注册表（给模型看的 schema + 本地执行分发）
# ══════════════════════════════════════════════════════════════════

TOOL_SCHEMAS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "search_documents",
            "description": (
                "在已上传文档中检索相关段落，返回页码与原文片段。"
                "**回答任何关于文档内容的问题前，都必须先用本工具检索**，"
                "不要凭记忆或常识作答。可多次调用，换不同关键词。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "检索关键词或问题，如“付款条款”“中标金额”",
                    },
                    "top_k": {"type": "integer", "description": "返回片段数，默认 5，最多 10"},
                    "doc_id": {
                        "type": "string",
                        "description": "限定在某份文档内检索；不填则检索全部",
                    },
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_page",
            "description": "读取某份文档指定页的完整原文。当检索片段不足以判断、需要看上下文时使用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "doc_id": {
                        "type": "string",
                        "description": "文档 ID（见 system 提示词里的文档清单，或 search_documents 的返回）",
                    },
                    "page_no": {"type": "integer", "description": "页码，从 1 开始"},
                },
                "required": ["doc_id", "page_no"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "calculate",
            "description": (
                "做精确算术计算。**涉及金额求和、比例校验、差额计算时必须使用本工具**，"
                "不要自己心算。表达式只支持数字与 + - * / ( )。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "expression": {
                        "type": "string",
                        "description": "算式，如 2394690 + 3192920 + 1596460 + 798230",
                    },
                },
                "required": ["expression"],
            },
        },
    },
]

# ⚠️ 直接指向真实函数，**不要用 lambda 包一层**。
# `inspect.signature(lambda **kw: ...)` 只能看到 `**kw`，
# 会让下面的参数过滤把模型传来的参数**全部丢掉**
# （实测症状：`search_documents() missing 1 required positional argument: 'query'`）。
_DISPATCH = {
    "search_documents": search_documents,
    "read_page": read_page,
    "calculate": calculate,
}


def execute(name: str, arguments: dict[str, Any]) -> dict:
    """执行工具。**任何异常都转成结构化错误返回给模型**，不让它中断整个问答。"""
    fn = _DISPATCH.get(name)
    if fn is None:
        return {"error": f"未知工具：{name}"}
    try:
        import inspect

        sig = inspect.signature(fn)
        allowed = {k: v for k, v in (arguments or {}).items() if k in sig.parameters}
        return fn(**allowed)
    except Exception as exc:  # noqa: BLE001
        return {"error": f"{type(exc).__name__}: {exc}"}
