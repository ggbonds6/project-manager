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

    # ── OCR ───────────────────────────────────────────────────
    ocr_dpi: int = field(default_factory=lambda: _env_int("OCR_DPI", 300))
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
            "ocr_dpi": self.ocr_dpi,
            "scanned_char_threshold": self.scanned_char_threshold,
            "work_dir": str(self.work_dir),
        }


settings = Settings()
