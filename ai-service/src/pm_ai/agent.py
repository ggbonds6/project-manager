"""工具调用循环——让模型"自己去找答案"，而不是把文档硬塞进上下文。

## 一次问答是怎么跑的

```
用户提问
  → 模型判断需要什么信息 → 发起工具调用（如 search_documents("付款条款")）
  → 本地执行工具，把结果回传
  → 模型看结果，可能再查一次（换关键词 / 读某页原文 / 算一下金额）
  → 信息够了 → 给出最终答案（带来源页码）
```

## 为什么要限制轮数

不限制的话模型可能反复检索、绕圈（尤其在提示词约束很严时）。
到上限后**不会直接失败**，而是再做一次"禁用工具"的调用，
让模型基于**已经拿到的信息**作答，并要求它把没找到的部分明说——
这样用户至少能拿到部分答案 + 明确的缺口，而不是一个报错。

## 可靠性地基

- 工具执行**永不抛异常**（`tools.execute` 内部兜底成结构化错误返回给模型），
  一条工具失败不会让整轮问答崩掉；
- 每轮的 token 与耗时都记账，便于排查"为什么这次特别慢"。
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field

from . import llm_client, tools

DEFAULT_MAX_ROUNDS = 8
"""工具调用轮数上限。

设小了信息不够，设大了慢且 token 消耗陡增（每轮都要重发全部上下文）。
提示词里已明确引导模型"控制在 5 次工具调用以内"，这里留出余量兜底。"""

BRIEF_CHARS = 120
"""工具结果在"调用轨迹"里的摘要长度（给人和前端看，不影响模型）。"""


@dataclass
class ToolTrace:
    """一次工具调用记录——前端据此展示"模型查了什么、查到几条"。"""
    round: int
    name: str
    arguments: dict
    brief: str
    elapsed: float
    is_error: bool = False


@dataclass
class AgentResult:
    text: str = ""
    trace: list[ToolTrace] = field(default_factory=list)
    rounds: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    elapsed: float = 0.0
    stopped_reason: str = "done"   # done | max_rounds | error
    error: str = ""

    def to_dict(self) -> dict:
        return {
            "text": self.text,
            "trace": [
                {"round": t.round, "name": t.name, "arguments": t.arguments,
                 "brief": t.brief, "elapsed": round(t.elapsed, 2), "is_error": t.is_error}
                for t in self.trace
            ],
            "rounds": self.rounds,
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "elapsed": round(self.elapsed, 2),
            "stopped_reason": self.stopped_reason,
            "error": self.error,
        }


def _brief(name: str, result: dict) -> str:
    """把工具结果压成一行摘要，供展示与排错。"""
    if not isinstance(result, dict):
        return str(result)[:BRIEF_CHARS]
    if "error" in result:
        return f"错误：{result['error']}"
    if name == "search_documents":
        hits = result.get("hits") or []
        if not hits:
            return "无命中"
        pages = "、".join(f"{h.get('filename', '')}P{h.get('page_no')}" for h in hits[:4])
        return f"命中 {len(hits)} 段：{pages}"
    if name == "read_page":
        return f"读取 {result.get('filename', '')} 第 {result.get('page_no')} 页（{len(result.get('text', ''))} 字）"
    if name == "list_documents":
        return f"共 {result.get('count', 0)} 份文档"
    if name == "calculate":
        return f"{result.get('expression', '')} = {result.get('result')}"
    return json.dumps(result, ensure_ascii=False)[:BRIEF_CHARS]


def _to_assistant_message(resp: llm_client.ChatResult) -> dict:
    """把带 tool_calls 的响应转成可回传给模型的消息。

    注意 content 可能是空串，OpenAI 规范要求此时传 None（不能传空字符串）。
    """
    return {
        "role": "assistant",
        "content": resp.text or None,
        "tool_calls": [
            {
                "id": c.id,
                "type": "function",
                "function": {"name": c.name, "arguments": c.raw_arguments or "{}"},
            }
            for c in resp.tool_calls
        ],
    }


def run(
    messages: list[dict],
    max_rounds: int = DEFAULT_MAX_ROUNDS,
    timeout: float | None = None,
) -> AgentResult:
    """跑一轮完整的"提问 → 工具 → 回答"。

    注意：会**原地修改**传入的 messages（追加 assistant / tool 消息），
    这样多轮对话时上下文能自然延续。
    """
    started = time.perf_counter()
    trace: list[ToolTrace] = []
    prompt_tokens = completion_tokens = 0
    rounds = 0

    try:
        for round_no in range(1, max_rounds + 1):
            rounds = round_no
            resp = llm_client.chat_messages(
                messages,
                tools=tools.TOOL_SCHEMAS,
                timeout=timeout,
            )
            prompt_tokens += resp.prompt_tokens
            completion_tokens += resp.completion_tokens

            # 没有工具调用 → 这就是最终答案
            if not resp.has_tool_calls:
                return AgentResult(
                    text=resp.text,
                    trace=trace,
                    rounds=rounds,
                    prompt_tokens=prompt_tokens,
                    completion_tokens=completion_tokens,
                    elapsed=time.perf_counter() - started,
                    stopped_reason="done",
                )

            messages.append(_to_assistant_message(resp))

            for call in resp.tool_calls:
                t0 = time.perf_counter()
                result = tools.execute(call.name, call.arguments)
                cost = time.perf_counter() - t0

                # 工具结果必须回传；用 JSON 保证结构清晰、模型好解析
                messages.append({
                    "role": "tool",
                    "tool_call_id": call.id,
                    "content": json.dumps(result, ensure_ascii=False),
                })
                trace.append(ToolTrace(
                    round=round_no,
                    name=call.name,
                    arguments=call.arguments,
                    brief=_brief(call.name, result),
                    elapsed=cost,
                    is_error=isinstance(result, dict) and "error" in result,
                ))

        # ── 到上限：禁用工具再问一次，让它用已有信息作答 ──
        messages.append({
            "role": "user",
            "content": (
                "（已达到本轮工具调用上限。请**仅根据以上已经获得的信息**作答；"
                "仍然必须标注来源页码；确实没有查到的部分，请明确写「文档中未找到」，不要猜测。）"
            ),
        })
        resp = llm_client.chat_messages(messages, tools=None, timeout=timeout)
        prompt_tokens += resp.prompt_tokens
        completion_tokens += resp.completion_tokens
        return AgentResult(
            text=resp.text,
            trace=trace,
            rounds=rounds,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            elapsed=time.perf_counter() - started,
            stopped_reason="max_rounds",
        )

    except Exception as exc:  # noqa: BLE001 - 把失败原因如实带回，不吞掉
        return AgentResult(
            text="",
            trace=trace,
            rounds=rounds,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            elapsed=time.perf_counter() - started,
            stopped_reason="error",
            error=f"{type(exc).__name__}: {exc}",
        )
