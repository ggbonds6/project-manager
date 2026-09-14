"""大模型客户端（千问，走 OpenAI 兼容协议）。

**为什么不直接写死某个厂商 SDK**：只要推理服务暴露 `/v1/chat/completions`，
本客户端就能接——vLLM、Ollama、各厂商一体机都符合。这样后续换模型/换部署方式时，
上层抽取与问答代码零改动。

本期（OCR 验证阶段）该模块仅用于**连通性自检**与后续抽取，不参与 OCR 本身。
"""

from __future__ import annotations

from dataclasses import dataclass

from openai import OpenAI

from .config import settings


@dataclass
class ChatResult:
    text: str
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


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


def chat(
    prompt: str,
    system: str | None = None,
    temperature: float = 0.0,
    timeout: float | None = None,
    max_retries: int | None = None,
) -> ChatResult:
    """单轮对话。

    默认 `temperature=0`：字段抽取与审计相关任务需要**可复现**，
    不要让同一个输入每次给出不同答案。

    `timeout` / `max_retries` 传 None 表示沿用客户端默认值（见 config 的 LLM_TIMEOUT）。
    """
    messages: list[dict[str, str]] = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})

    client = get_client()
    options: dict[str, object] = {}
    if timeout is not None:
        options["timeout"] = timeout
    if max_retries is not None:
        options["max_retries"] = max_retries
    if options:
        client = client.with_options(**options)  # type: ignore[arg-type]

    resp = client.chat.completions.create(
        model=settings.llm_model,
        messages=messages,  # type: ignore[arg-type]
        temperature=temperature,
    )
    choice = resp.choices[0]
    usage = getattr(resp, "usage", None)
    return ChatResult(
        text=(choice.message.content or "").strip(),
        model=resp.model,
        prompt_tokens=getattr(usage, "prompt_tokens", 0) or 0,
        completion_tokens=getattr(usage, "completion_tokens", 0) or 0,
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
        )
        return True, f"模型 {result.model} 连通，回复：{result.text[:40]}"
    except Exception as exc:  # noqa: BLE001 - 自检需要吞掉所有异常并报告
        return False, f"{type(exc).__name__}: {exc}"
