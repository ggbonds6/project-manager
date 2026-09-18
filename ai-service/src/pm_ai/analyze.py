"""编排：文档 → 结构化文本 → 提示词 → 千问 → markdown 结果。

这一层只负责"把链路串起来"，具体规则都在：
- 解析/分页/置信度 → `document.py`
- 提示词与铁律     → `prompts.py`
- 模型调用         → `llm_client.py`

## 超长文档怎么处理

先按**页**截断（不是按字粗暴切），并在提示词里**明确告知模型"你没看到全部"**——
否则模型会把"只给了前 10 页"当成"原文就这 10 页"，输出看似完整实则缺失的结论，
这在审计场景是危险的。

截断信息会同时回传给前端，让使用者知道结论的覆盖范围。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from pathlib import Path

from . import checks, document, llm_client, prompts
from .config import settings


@dataclass
class AnalyzeResult:
    markdown: str
    doc: dict = field(default_factory=dict)
    llm: dict = field(default_factory=dict)
    verification: dict = field(default_factory=dict)
    """**输出侧的机器校验**：答案里的数字能否在原文逐字找到。

    比"让模型自评置信度"可靠——模型说 0.95 你无从验证，但"这个数字原文里有没有"
    是代码算出来的、可复现的。见 `checks.verify_numbers_in_source`。
    """
    truncated: bool = False
    truncate_note: str = ""
    warning: str = ""

    def to_payload(self) -> dict:
        return {
            "markdown": self.markdown,
            "doc": self.doc,
            "llm": self.llm,
            "verification": self.verification,
            "truncated": self.truncated,
            "truncate_note": self.truncate_note,
            "warning": self.warning,
        }


def _truncate(doc: document.DocumentText, limit: int) -> tuple[str, bool, str]:
    """按**页**累加直到接近上限，返回 (文本, 是否截断, 说明)。"""
    if doc.char_count <= limit:
        return doc.labeled_text, False, ""

    kept: list[str] = []
    total = 0
    for p in doc.pages:
        body = p.text.strip() or "（本页未识别到文本）"
        block = f"{document.page_header(p)}\n{body}"
        # 至少保留一页，避免首屏超大时一页都不给
        if kept and total + len(block) > limit:
            break
        kept.append(block)
        total += len(block)

    note = (
        f"因篇幅限制，本次**仅提供前 {len(kept)} 页**（原文共 {doc.page_count} 页），"
        f"未提供的页面未参与分析，因此结论**不覆盖**那部分内容。"
    )
    return "\n\n".join(kept), True, note


def analyze(path: str | Path, instruction: str = "",
            dpi: int | None = None) -> AnalyzeResult:
    """对一个文件做完整的"抽取 + 生成"。

    :param path:        文件路径
    :param instruction: 用户特别关注点（可为空）
    :param dpi:         扫描件渲染 DPI，None 取配置
    """
    started = time.perf_counter()

    # ── 1) 文档 → 带页码的结构化文本 ───────────────────────────
    doc = document.read_document(path, dpi=dpi)
    if doc.error:
        return AnalyzeResult(
            markdown=f"> ⚠️ **无法分析该文件**：{doc.error}",
            doc=doc.summary(),
            warning=doc.error,
        )

    if not doc.text.strip():
        return AnalyzeResult(
            markdown=(
                "> ⚠️ **未从文件中提取到任何文本**。\n>\n"
                "> 可能原因：扫描件清晰度过低、OCR 未能识别，或文件为空白页。\n"
                "> 建议：确认文件内容正常，或提高渲染 DPI（`.env` 的 `OCR_DPI`）后重试。"
            ),
            doc=doc.summary(),
            warning="未提取到文本",
        )

    # ── 2) 截断（按页）────────────────────────────────────────
    labeled, truncated, note = _truncate(doc, settings.llm_max_input_chars)
    user_prompt = prompts.build_user_prompt(
        labeled_text=labeled,
        page_count=doc.page_count,
        instruction=instruction,
        truncated_note=note,
    )

    # ── 3) 调用模型（temperature=0：审计场景要求可复现）────────
    doc_summary = doc.summary()
    try:
        chat = llm_client.chat(
            user_prompt,
            system=prompts.SYSTEM_PROMPT,
            temperature=0.0,
            timeout=settings.llm_timeout,
        )
    except Exception as exc:  # noqa: BLE001 - 要把失败原因如实告知使用者
        return AnalyzeResult(
            markdown=(
                f"> ⚠️ **模型调用失败**：`{type(exc).__name__}` {exc}\n>\n"
                f"> 排查：① 推理服务是否可达（`/health?with_llm=true`）　"
                f"② `LLM_TIMEOUT` 是否偏小　③ 模型名是否正确"
            ),
            doc=doc_summary,
            llm={"ok": False},
            truncated=truncated,
            truncate_note=note,
            warning=f"模型调用失败：{type(exc).__name__}",
        )

    markdown = chat.text
    warning = ""

    if not markdown:
        # 空输出必须给出**可操作的原因**，不能含糊说"系统繁忙"——审计场景要能解释清楚
        if chat.is_truncated:
            markdown = (
                "> ⚠️ **模型输出被截断，未给出最终结论**\n>\n"
                f"> 思维链占满了全部输出额度（`max_tokens={settings.llm_max_tokens}`，"
                f"思考内容约 {len(chat.reasoning)} 字），最终答案被挤掉了。\n>\n"
                "> **处理**：调大 `.env` 的 `LLM_MAX_TOKENS`；或把文档拆分后分次分析；"
                "也可设 `LLM_ENABLE_THINKING=false` 换速度（但抽取质量会下降）。"
            )
            warning = "模型输出被 max_tokens 截断，未给出结论"
        elif chat.reasoning:
            markdown = (
                "> ⚠️ **模型只输出了思考过程，没有给出最终结论**\n>\n"
                f"> 思考内容约 {len(chat.reasoning)} 字，"
                f"`finish_reason={chat.finish_reason or '-'}`。\n"
                "> **处理**：重试一次，或调大 `LLM_MAX_TOKENS`。"
            )
            warning = "模型未给出结论（仅返回思考内容）"
        else:
            markdown = f"> ⚠️ {prompts.EMPTY_OUTPUT_HINT}"
            warning = "模型返回空内容"

    if truncated and not warning:
        warning = "原文超长，仅前若干页参与分析"

    # 输出侧机器校验：答案里的每个数字能不能在原文找到。
    # 这是"禁止虚构"的**机器兜底**——不依赖模型的自觉，也不依赖它的自评分。
    verification = checks.verify_numbers_in_source(
        markdown, [p.text for p in doc.pages])
    if verification.get("status") == "warn" and not warning:
        warning = "有数字在原文中未找到，请重点核对"

    return AnalyzeResult(
        markdown=markdown,
        doc=doc_summary,
        llm={
            "ok": True,
            "model": chat.model,
            "prompt_tokens": chat.prompt_tokens,
            "completion_tokens": chat.completion_tokens,
            "reasoning_chars": len(chat.reasoning),
            "finish_reason": chat.finish_reason,
            "elapsed": round(time.perf_counter() - started, 2),
        },
        verification=verification,
        truncated=truncated,
        truncate_note=note,
        warning=warning,
    )
