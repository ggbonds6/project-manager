"""大模型客户端（千问，走 OpenAI 兼容协议）。

**为什么不直接写死某个厂商 SDK**：只要推理服务暴露 `/v1/chat/completions`，
本客户端就能接——vLLM、Ollama、各厂商一体机都符合。这样后续换模型/换部署方式时，
上层抽取与问答代码零改动。

## ⚠️ Qwen3 系列思维链的两个坑（实测踩过，2026-09-16）

**坑 1：思维链会吃光输出额度，导致 `content` 为空。**
Qwen3 是混合推理模型，默认开启思考。思考内容放在 `reasoning_content`，
`message.content` 才是最终答案。思考过长时**全部输出额度被思考耗尽**，
`content` 返回空字符串——上层看起来就是"模型什么都没答"。
实测：抽取一份 6 页合同 → `completion_tokens=8192`（正好撞上限）、`content` 为空、
`finish_reason=length`、推理耗时 148s。

对策：
① `LLM_MAX_TOKENS` 默认放大到 16384（见 config.py）；
② 保留 `reasoning` 字段用于诊断"为什么没答案"；
③ `finish_reason == "length"` 时明确报"输出被截断"，而不是含糊地说"系统繁忙"。

**坑 2：长时间思考会把最终答案挤掉——所以本项目默认关闭思考。**
实测（2026-09-16，同一份 6 页扫描件合同）：

| 配置 | 结果 |
| --- | --- |
| 开启思考 | 思考 30584 字 → 耗尽 16384 token → `finish_reason=length` →**最终答案为空**，耗时 4 分 55 秒 |
| 关闭思考 | 完整产出「文件概要 / 关键信息 / 原文依据 / 存疑项」四节；来源标注（`[P1, P5]`）与置信度分档（0.95 / 0.60）正常；还主动标出了 OCR 可疑数字并给 0.60 |

结论：在"高约束抽取"这类任务上，思考模式**不但慢，还会把答案挤没**。
故默认 `LLM_ENABLE_THINKING=false`。若换到确实需要深度推理的任务（如合规性判断），
可设 `true` 一试，但**务必同时把 `LLM_MAX_TOKENS` 调得更大**，并预期耗时显著上升。

`temperature` 默认 0：字段抽取与审计相关任务需要**可复现**，
不要让同一个输入每次给出不同答案。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

from openai import OpenAI

from .config import settings


@dataclass
class ToolCall:
    """模型发起的一次工具调用请求。"""

    id: str
    name: str
    arguments: dict
    raw_arguments: str = ""


@dataclass
class ChatResult:
    text: str = ""
    model: str = ""
    prompt_tokens: int = 0
    completion_tokens: int = 0
    reasoning: str = ""
    """思维链内容（Qwen3 等推理模型）。不展示给用户，用于诊断"为什么没答案"。"""
    finish_reason: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)

    @property
    def is_truncated(self) -> bool:
        """是否因输出额度用尽被截断。"""
        return self.finish_reason == "length"

    @property
    def has_text(self) -> bool:
        return bool(self.text.strip())

    @property
    def has_tool_calls(self) -> bool:
        return bool(self.tool_calls)


_CLIENT: OpenAI | None = None


def get_client() -> OpenAI:
    global _CLIENT
    if _CLIENT is None:
        if not settings.llm_api_key:
            raise RuntimeError(
                "未配置 LLM_API_KEY。请在 ai-service/.env 中填写推理服务的密钥"
                "（若本地服务不校验密钥，可随便填一个非空值）。"
            )
        _CLIENT = OpenAI(
            base_url=settings.llm_base_url,
            api_key=settings.llm_api_key,
            timeout=settings.llm_timeout,
        )
    return _CLIENT


def chat_messages(
    messages: list[dict],
    tools: list[dict] | None = None,
    temperature: float = 0.0,
    timeout: float | None = None,
    max_retries: int | None = None,
    max_tokens: int | None = None,
    enable_thinking: bool | None = None,
) -> ChatResult:
    """带**完整消息列表**的调用（支持工具调用）。

    与 `chat()` 的区别：这里可以传多条消息——包含 assistant 的 `tool_calls`
    以及 `role="tool"` 的工具结果，供工具调用循环反复调用。

    :param timeout:         传 None 沿用客户端默认（config 的 `LLM_TIMEOUT`）
    :param max_tokens:      输出上限；None 取 `settings.llm_max_tokens`
    :param enable_thinking: 是否开启思维链；None 取 `settings.llm_enable_thinking`。
                            **只在为 False 时才附带参数**——服务端不支持该字段时
                            附带它会直接 400，所以非必要不传。
    """
    client = get_client()
    options: dict[str, object] = {}
    if timeout is not None:
        options["timeout"] = timeout
    if max_retries is not None:
        options["max_retries"] = max_retries
    if options:
        client = client.with_options(**options)  # type: ignore[arg-type]

    kwargs: dict = {
        "model": settings.llm_model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens if max_tokens is not None else settings.llm_max_tokens,
    }
    if tools:
        # tool_choice=auto：由模型自行决定是否调用工具。
        # 强制调用会破坏"信息不足就直说"的场景（模型不得不编一个查询出来）。
        kwargs["tools"] = tools
        kwargs["tool_choice"] = "auto"

    thinking = settings.llm_enable_thinking if enable_thinking is None else enable_thinking
    if not thinking:
        # vLLM 部署 Qwen3 时通过 chat_template_kwargs 关闭思考
        kwargs["extra_body"] = {"chat_template_kwargs": {"enable_thinking": False}}

    resp = client.chat.completions.create(**kwargs)
    choice = resp.choices[0]
    msg = choice.message
    usage = getattr(resp, "usage", None)

    calls: list[ToolCall] = []
    for tc in getattr(msg, "tool_calls", None) or []:
        raw = getattr(tc.function, "arguments", "") or "{}"
        try:
            args = json.loads(raw)
        except Exception:  # noqa: BLE001 - 参数不是合法 JSON 时按空参数处理
            args = {}
        calls.append(
            ToolCall(
                id=getattr(tc, "id", ""), name=tc.function.name, arguments=args, raw_arguments=raw
            )
        )

    return ChatResult(
        text=(msg.content or "").strip(),
        model=resp.model,
        prompt_tokens=getattr(usage, "prompt_tokens", 0) or 0,
        completion_tokens=getattr(usage, "completion_tokens", 0) or 0,
        # reasoning_content 不属于 OpenAI 标准字段，用 getattr 兼容其他模型
        reasoning=(getattr(msg, "reasoning_content", "") or "").strip(),
        finish_reason=choice.finish_reason or "",
        tool_calls=calls,
    )


def chat(
    prompt: str,
    system: str | None = None,
    temperature: float = 0.0,
    timeout: float | None = None,
    max_retries: int | None = None,
    max_tokens: int | None = None,
    enable_thinking: bool | None = None,
) -> ChatResult:
    """单轮对话（system + 一条用户消息的便捷封装）。

    默认 `temperature=0`：字段抽取与审计相关任务需要**可复现**，
    不要让同一个输入每次给出不同答案。
    """
    messages: list[dict] = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})
    return chat_messages(
        messages,
        temperature=temperature,
        timeout=timeout,
        max_retries=max_retries,
        max_tokens=max_tokens,
        enable_thinking=enable_thinking,
    )


def ping(timeout: float = 15.0) -> tuple[bool, str]:
    """连通性自检：返回 (是否可用, 说明)。

    刻意用**短超时 + 不重试**：自检的目的是"快速告诉你通不通"，
    而不是让用户对着终端等几分钟。推理服务在别的网段时尤其明显。
    """
    try:
        result = chat(
            "回复两个字：正常",
            system="你是一个测试助手。",
            timeout=timeout,
            max_retries=0,
            enable_thinking=False,  # 自检不需要思考，快就好
        )
        return True, f"模型 {result.model} 连通，回复：{result.text[:40]}"
    except Exception as exc:  # noqa: BLE001 - 自检需要吞掉所有异常并报告
        return False, f"{type(exc).__name__}: {exc}"
