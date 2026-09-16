"""问答编排：组装消息 → 跑工具调用循环 → 返回答案、引用与调用轨迹。

## 与「文档抽取」(analyze.py) 的分工

| | 抽取 | 问答 |
| --- | --- | --- |
| 输入 | 一份文件 + 固定模板 | 一个问题 + 可选文档范围 |
| 信息获取 | **全文直接给模型** | **模型自己检索**（工具调用） |
| 适合 | "把这份合同的付款条款整理出来" | "这几份材料里，验收结论是什么？" |

## 上下文控制

- **历史只保留纯文本的问答对**（不带 tool_calls）——
  把工具调用中间过程塞回历史会让上下文迅速膨胀，且容易让模型"学样"重复调用；
- 只保留最近 `MAX_HISTORY_TURNS` 轮，更早的丢弃（长对话对文档问答的价值递减）。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

from . import agent, prompts
from .config import settings
from .store import store

MAX_HISTORY_TURNS = 6
"""保留的历史问答轮数。"""


@dataclass
class QaResult:
    answer: str = ""
    trace: list[dict] = field(default_factory=list)
    scope: list[dict] = field(default_factory=list)
    llm: dict = field(default_factory=dict)
    stopped_reason: str = "done"
    error: str = ""

    def to_dict(self) -> dict:
        return {
            "answer": self.answer,
            "trace": self.trace,
            "scope": self.scope,
            "llm": self.llm,
            "stopped_reason": self.stopped_reason,
            "error": self.error,
        }


def _scoped_docs(doc_ids: list[str] | None) -> list[dict]:
    docs = store.list()
    if doc_ids:
        keep = set(doc_ids)
        docs = [d for d in docs if d["doc_id"] in keep]
    return docs


def build_messages(
    question: str,
    doc_ids: list[str] | None = None,
    history: list[dict] | None = None,
) -> tuple[list[dict], list[dict]]:
    """组装消息列表，返回 (messages, 本次问答覆盖的文档)。"""
    docs = _scoped_docs(doc_ids)

    # ⚠️ 只允许**一条 system 消息**，且必须在最前面。
    # 实测该推理服务（vLLM + Qwen3 模板）遇到第二条 system 会直接 400：
    #   "System message must be at the beginning."
    # 因此把"角色铁律"与"文档清单"合并进同一条 system。
    system_content = prompts.QA_SYSTEM_PROMPT + "\n\n" + prompts.build_doc_scope_note(docs)
    messages: list[dict] = [{"role": "system", "content": system_content}]

    # 只回放纯文本历史（见模块文档）；截断到最近 N 轮
    for item in (history or [])[-MAX_HISTORY_TURNS * 2:]:
        role = item.get("role")
        content = (item.get("content") or "").strip()
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": content})

    messages.append({"role": "user", "content": question})
    return messages, docs


def ask(
    question: str,
    doc_ids: list[str] | None = None,
    history: list[dict] | None = None,
    max_rounds: int | None = None,
) -> QaResult:
    """回答一个关于已上传文档的问题。"""
    started = time.perf_counter()
    question = (question or "").strip()
    if not question:
        return QaResult(answer="> ⚠️ 请先输入问题。", error="问题为空")

    docs = _scoped_docs(doc_ids)
    if not docs:
        return QaResult(
            answer="> ⚠️ **还没有可问答的文档。**\n>\n> 请先在左侧上传文件（PDF / 图片），上传后我就能基于文件内容回答。",
            error="没有可用文档",
        )

    messages, docs = build_messages(question, doc_ids, history)
    result = agent.run(messages, max_rounds=max_rounds or agent.DEFAULT_MAX_ROUNDS,
                       timeout=settings.llm_timeout)

    answer = result.text
    if not answer:
        if result.stopped_reason == "error":
            answer = (f"> ⚠️ **问答失败**：`{result.error}`\n>\n"
                      "> 排查：① 推理服务是否可达（`/health?with_llm=true`）　"
                      "② `LLM_TIMEOUT` 是否偏小　③ 模型名是否正确")
        elif result.stopped_reason == "max_rounds":
            answer = ("> ⚠️ 已达到工具调用轮数上限，仍未给出结论。\n>\n"
                      "> 可尝试：把问题问得更具体、指定某一份文档，或调大 `LLM_MAX_TOKENS`。")
        else:
            answer = f"> ⚠️ {prompts.EMPTY_OUTPUT_HINT}"

    return QaResult(
        answer=answer,
        trace=result.to_dict()["trace"],
        scope=[{"doc_id": d["doc_id"], "filename": d["filename"]} for d in docs],
        llm={
            "ok": result.stopped_reason != "error",
            "model": settings.llm_model,
            "rounds": result.rounds,
            "prompt_tokens": result.prompt_tokens,
            "completion_tokens": result.completion_tokens,
            "tool_calls": len(result.trace),
            "elapsed": round(time.perf_counter() - started, 2),
        },
        stopped_reason=result.stopped_reason,
        error=result.error,
    )
