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
    llm_base_url: str = field(
        default_factory=lambda: _env("LLM_BASE_URL", "http://127.0.0.1:8000/v1")
    )
    llm_api_key: str = field(default_factory=lambda: _env("LLM_API_KEY", ""))
    llm_model: str = field(default_factory=lambda: _env("LLM_MODEL", "qwen"))
    llm_timeout: int = field(default_factory=lambda: _env_int("LLM_TIMEOUT", 300))
    # 单次送给模型的最大字符数：中文约 1 字 ≈ 1 token，留足上下文余量。
    # 超过则按页截断，并在提示词里**明确告知模型"你没看到全部"**，避免它当成全文。
    llm_max_input_chars: int = field(default_factory=lambda: _env_int("LLM_MAX_INPUT_CHARS", 20000))
    # 输出上限。⚠️ 默认给得较大：Qwen3 的思维链会消耗大量额度，
    # 设小了会出现"completion_tokens 撞上限、content 为空"（实测坑，详见 llm_client.py）。
    llm_max_tokens: int = field(default_factory=lambda: _env_int("LLM_MAX_TOKENS", 16384))
    # 是否保留思维链。**默认 False** —— 实测（2026-09-16，6 页扫描件合同）：
    # 开启思考时模型思考 30584 字、耗尽 16384 token 仍未给出答案（finish_reason=length，
    # content 为空，耗时 4 分 55 秒）；关闭后完整产出全部章节且来源/置信度标注正常。
    # 在"高约束抽取"这类任务上，思考模式不但慢，还会把最终答案挤掉。
    llm_enable_thinking: bool = field(
        default_factory=lambda: _env_bool("LLM_ENABLE_THINKING", False)
    )

    # ── OCR ───────────────────────────────────────────────────
    #
    # 只有**内网平台 OCR**（PaddleOCR-VL，GPU）一条路。
    # 本地 RapidOCR/PaddleOCR 兜底与 OCR_PROVIDER / OCR_DPI / OCR_WORKERS 三个配置项
    # 已于 2026-09-18 一并移除：实测同一份合同平台把"柒佰柒拾捌万元整"完整识别，
    # 本地引擎金额全丢——质量差距过大，留着"能跑但结果不可用"的兜底反而误导使用者。
    # 平台不可用时没有备份引擎，必须让错误显式暴露出来（失败页保留 error，由上层提示人工复核）。
    #
    # 平台 OCR 的地址与密钥。**默认复用大模型的**——同一个网关、同一个 sk，
    # 单独设这两个变量只在"想把 OCR 指向别的网关"时才需要。
    ocr_base_url: str = field(default_factory=lambda: _env("OCR_BASE_URL", ""))
    ocr_api_key: str = field(default_factory=lambda: _env("OCR_API_KEY", ""))
    # 单次请求超时（秒）。手册建议大批量设 600。
    ocr_timeout: int = field(default_factory=lambda: _env_int("OCR_TIMEOUT", 600))
    # 同时在飞的请求数。平台是本项目专用、可直接打满：
    # 实测（6 页合同）串行 1.84s/页 → 并发 6 得 1.33 页/秒 → 并发 12 得 2.45 页/秒（4.5×）。
    ocr_concurrency: int = field(default_factory=lambda: _env_int("OCR_CONCURRENCY", 12))
    # 每个请求带几页（手册：≤16 页/请求）。
    ocr_batch_pages: int = field(default_factory=lambda: _env_int("OCR_BATCH_PAGES", 8))
    # 是否识别印章。**默认 false**：① 印章块默认就返回（内容为空）＝免费的"有没有章"探测；
    # ② 开它会让含章文档吞吐减半；③ 低 DPI 下会**编造**印章文字（实测读出过完全不相干的银行名）。
    # 确需读印章时，应改用高 DPI 原图重跑该页（手册"两遍法"）。
    ocr_seal: bool = field(default_factory=lambda: _env_bool("OCR_SEAL", False))
    ocr_seal_min_pixels: int = field(
        default_factory=lambda: _env_int("OCR_SEAL_MIN_PIXELS", 400000)
    )
    # 发给平台 OCR 的图：**150 DPI JPEG**。实测 120~300 DPI 识别结果完全一致
    # （VL 模型内部下采样到 ≈100 万像素），但 300DPI PNG 每页 6.7MB、150DPI JPEG 396KB，差 17 倍。
    ocr_platform_dpi: int = field(default_factory=lambda: _env_int("OCR_PLATFORM_DPI", 150))
    ocr_platform_format: str = field(
        default_factory=lambda: _env("OCR_PLATFORM_FORMAT", "jpeg").lower()
    )
    ocr_platform_quality: int = field(default_factory=lambda: _env_int("OCR_PLATFORM_QUALITY", 85))

    # ── 检索（Embedding 召回 + Reranker 精排）────────────────────
    #
    # 平台已部署 **Qwen3-VL-Embedding-8B**（4096 维；本部署**不支持 MRL 降维**，见下）与
    # **Qwen3-VL-Reranker-8B**，入口与对话/OCR 是**同一个网关、同一把 sk**
    # （见《Qwen3-VL-Embedding-Reranker调用手册.md》）。
    # 链路：向量召回 recall 条 → Reranker 精排 → 取 top_k（手册建议 50~100 → 5~10）。
    vec_base_url: str = field(default_factory=lambda: _env("VEC_BASE_URL", ""))
    vec_api_key: str = field(default_factory=lambda: _env("VEC_API_KEY", ""))
    vec_timeout: int = field(default_factory=lambda: _env_int("VEC_TIMEOUT", 300))
    embed_model: str = field(
        default_factory=lambda: _env("VEC_EMBED_MODEL", "Qwen3-VL-Embedding-8B")
    )
    rerank_model: str = field(
        default_factory=lambda: _env("VEC_RERANK_MODEL", "Qwen3-VL-Reranker-8B")
    )
    # 输出维度：**0 = 不传该参数**（用平台默认 4096 维）。
    # ⚠️ 实测（2026-09-20）：本平台部署的 Qwen3-VL-Embedding-8B **不支持 MRL 降维**——
    # 传 `dimensions` 会被直接拒掉：HTTP 400 `does not support matryoshka representation,
    # changing output dimensions will lead to poor results`。手册虽写 MRL 支持 64~4096，
    # 但**以实际部署为准**，故默认不传（4096 维）；真要用低维得平台侧换部署参数。
    embed_dimensions: int = field(default_factory=lambda: _env_int("VEC_EMBED_DIMENSIONS", 0))
    # 召回条数（送进 Reranker 的候选数）与精排后返回条数
    retrieval_recall: int = field(default_factory=lambda: _env_int("RETRIEVAL_RECALL", 50))
    retrieval_top_k: int = field(default_factory=lambda: _env_int("RETRIEVAL_TOP_K", 5))
    # 向量索引后端：
    #   local      = 进程内余弦 + 本地向量缓存（**当前默认**，零部署，够用到几万条切片）
    #   opensearch = OpenSearch kNN（**正式选型**，集群尚未部署 → **适配层也尚未实现**：
    #                配成它会在检索时显式报错，不静默退回 local。见 retrieval._check_backend）
    vec_backend: str = field(default_factory=lambda: _env("VEC_BACKEND", "local").lower())
    opensearch_url: str = field(default_factory=lambda: _env("OPENSEARCH_URL", ""))
    opensearch_index: str = field(default_factory=lambda: _env("OPENSEARCH_INDEX", "pm-ai-chunks"))
    opensearch_user: str = field(default_factory=lambda: _env("OPENSEARCH_USER", ""))
    opensearch_password: str = field(default_factory=lambda: _env("OPENSEARCH_PASSWORD", ""))

    # 判定扫描件的阈值：平均每页字符数低于此值即视为扫描件。
    # ⚠️ 它不是"本地引擎参数"——文本型/扫描件的分流规则与引擎无关，故保留。
    scanned_char_threshold: int = field(
        default_factory=lambda: _env_int("SCANNED_CHAR_THRESHOLD", 50)
    )

    # ── 工作目录（OCR 中间产物、导出结果）────────────────────────
    work_dir: Path = field(default_factory=lambda: Path(_env("WORK_DIR", "./work")).resolve())

    def __post_init__(self) -> None:
        self.work_dir.mkdir(parents=True, exist_ok=True)
        # 平台 OCR 与对话模型同一个网关、共用一个 sk：没单独配就自动回落，避免同一份配置写两遍
        if not self.ocr_base_url:
            self.ocr_base_url = self.llm_base_url
        if not self.ocr_api_key:
            self.ocr_api_key = self.llm_api_key
        # 向量化/重排同样走这个网关（手册：与对话、OCR 共用同一套 sk）
        if not self.vec_base_url:
            self.vec_base_url = self.llm_base_url
        if not self.vec_api_key:
            self.vec_api_key = self.llm_api_key

    def summary(self) -> dict:
        """用于 /health 与自检脚本，不输出密钥明文。"""
        return {
            "llm_base_url": self.llm_base_url,
            "llm_model": self.llm_model,
            "llm_api_key": "已配置" if self.llm_api_key else "未配置",
            "llm_max_input_chars": self.llm_max_input_chars,
            "llm_max_tokens": self.llm_max_tokens,
            "llm_enable_thinking": self.llm_enable_thinking,
            "vec_base_url": self.vec_base_url,
            "vec_api_key": "已配置" if self.vec_api_key else "未配置",
            "vec_embed_model": self.embed_model,
            "vec_rerank_model": self.rerank_model,
            "vec_embed_dimensions": self.embed_dimensions,
            "vec_backend": self.vec_backend,
            "retrieval_recall": self.retrieval_recall,
            "retrieval_top_k": self.retrieval_top_k,
            "ocr_base_url": self.ocr_base_url,
            "ocr_api_key": "已配置" if self.ocr_api_key else "未配置",
            "ocr_timeout": self.ocr_timeout,
            "ocr_concurrency": self.ocr_concurrency,
            "ocr_batch_pages": self.ocr_batch_pages,
            "ocr_seal": self.ocr_seal,
            "ocr_platform_image": f"{self.ocr_platform_dpi}dpi/{self.ocr_platform_format}"
            f"/q{self.ocr_platform_quality}",
            "scanned_char_threshold": self.scanned_char_threshold,
            "work_dir": str(self.work_dir),
        }


settings = Settings()
