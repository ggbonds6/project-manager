"""配置：一律从环境变量读取（.env 仅用于本机开发）。

刻意不引入 pydantic-settings，避免环境变量名映射带来的隐式行为，
所有键名都在这里显式列出，便于排查"为什么配置没生效"。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

try:
    from dotenv import load_dotenv

    # 就近加载：ai-service/.env（与 pyproject.toml 同级）
    load_dotenv(Path(__file__).resolve().parents[2] / ".env")
except Exception:  # dotenv 未安装也不影响（生产用真实环境变量）
    pass


def _env(key: str, default: str = "") -> str:
    return os.getenv(key, default).strip()


def _env_int(key: str, default: int) -> int:
    raw = _env(key)
    try:
        return int(raw) if raw else default
    except ValueError:
        return default


def _env_bool(key: str, default: bool) -> bool:
    """布尔环境变量：接受 1/true/yes/on（大小写不敏感）。"""
    raw = _env(key).lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on", "y"}


@dataclass
class Settings:
    # ── 服务自身 ────────────────────────────────────────────────
    host: str = field(default_factory=lambda: _env("AI_HOST", "0.0.0.0"))
    port: int = field(default_factory=lambda: _env_int("AI_PORT", 8100))

    # ── 大模型（千问，OpenAI 兼容协议）────────────────────────────
    # 只要推理服务暴露 /v1/chat/completions，这里就能接（vLLM / Ollama / 一体机均可）
    llm_base_url: str = field(default_factory=lambda: _env("LLM_BASE_URL", "http://127.0.0.1:8000/v1"))
    llm_api_key: str = field(default_factory=lambda: _env("LLM_API_KEY", ""))
    llm_model: str = field(default_factory=lambda: _env("LLM_MODEL", "qwen"))
    llm_timeout: int = field(default_factory=lambda: _env_int("LLM_TIMEOUT", 300))
    # 单次送给模型的最大字符数：中文约 1 字 ≈ 1 token，留足上下文余量。
    # 超过则按页截断，并在提示词里**明确告知模型"你没看到全部"**，避免它当成全文。
    llm_max_input_chars: int = field(
        default_factory=lambda: _env_int("LLM_MAX_INPUT_CHARS", 20000)
    )
    # 输出上限。⚠️ 默认给得较大：Qwen3 的思维链会消耗大量额度，
    # 设小了会出现"completion_tokens 撞上限、content 为空"（实测坑，详见 llm_client.py）。
    llm_max_tokens: int = field(
        default_factory=lambda: _env_int("LLM_MAX_TOKENS", 16384)
    )
    # 是否保留思维链。**默认 False** —— 实测（2026-09-16，6 页扫描件合同）：
    # 开启思考时模型思考 30584 字、耗尽 16384 token 仍未给出答案（finish_reason=length，
    # content 为空，耗时 4 分 55 秒）；关闭后完整产出全部章节且来源/置信度标注正常。
    # 在"高约束抽取"这类任务上，思考模式不但慢，还会把最终答案挤掉。
    llm_enable_thinking: bool = field(
        default_factory=lambda: _env_bool("LLM_ENABLE_THINKING", False)
    )

    # ── OCR ───────────────────────────────────────────────────
    ocr_dpi: int = field(default_factory=lambda: _env_int("OCR_DPI", 300))
    # OCR 并发页数。⚠️ 实测（开发机 12 核 / Docker Desktop，25 页扫描件）**并发反而更慢**：
    #   1 路 93~104s ｜ 2 路 108s ｜ 3 路 139s。
    # 因为 onnxruntime 自身已多线程（实测占约 6 核），多实例只会互相抢核、缓存颠簸。
    # 故默认 1；换到核数明显更多的机器时可再实测调大。
    ocr_workers: int = field(default_factory=lambda: _env_int("OCR_WORKERS", 1))
    # 判定扫描件的阈值：平均每页字符数低于此值即视为扫描件
    scanned_char_threshold: int = field(default_factory=lambda: _env_int("SCANNED_CHAR_THRESHOLD", 50))

    # ── 工作目录（OCR 中间产物、导出结果）────────────────────────
    work_dir: Path = field(
        default_factory=lambda: Path(_env("WORK_DIR", "./work")).resolve()
    )

    def __post_init__(self) -> None:
        self.work_dir.mkdir(parents=True, exist_ok=True)

    def summary(self) -> dict:
        """用于 /health 与自检脚本，不输出密钥明文。"""
        return {
            "llm_base_url": self.llm_base_url,
            "llm_model": self.llm_model,
            "llm_api_key": "已配置" if self.llm_api_key else "未配置",
            "llm_max_input_chars": self.llm_max_input_chars,
            "llm_max_tokens": self.llm_max_tokens,
            "llm_enable_thinking": self.llm_enable_thinking,
            "ocr_dpi": self.ocr_dpi,
            "ocr_workers": self.ocr_workers,
            "scanned_char_threshold": self.scanned_char_threshold,
            "work_dir": str(self.work_dir),
        }


settings = Settings()
