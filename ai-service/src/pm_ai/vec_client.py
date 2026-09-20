"""Qwen3-VL Embedding / Reranker 客户端（平台网关，纯 HTTP）。

手册：`Qwen3-VL-Embedding-Reranker调用手册.md`
- 向量化 `POST {VEC_BASE_URL}/embeddings` → `{"data":[{"index":i,"embedding":[...]}]}`
- 重排   `POST {VEC_BASE_URL}/rerank`     → `{"results":[{"index":i,"relevance_score":s}]}`
- 探活   `GET  {VEC_BASE_URL}/models`
- 鉴权   `Authorization: Bearer <sk>`（与千问对话、平台 OCR **共用同一把 sk**）

为什么用 urllib 而不是 requests / openai SDK：
这里只是"发一个 JSON、收一个 JSON"，重活全在网关侧；少一个依赖就少一份内网装机成本
（与 `platform_ocr.py` 的做法保持一致）。

⚠️ 两个实测坑（手册里写明，别踩回去）：
1. 直连地址（`10.254.213.135:8091/8092`）有防火墙白名单，**普通客户端连不上**，一律走网关；
2. `/v1/score` 传图片时必须用 `content` 数组结构，写成 `{"image": ...}` 会 400。
   本模块只用 `/embeddings` 与 `/rerank`（Jina 风格），不碰 `/score`。
"""

from __future__ import annotations

import json
import math
import urllib.error
import urllib.request
from dataclasses import dataclass, field

from .config import settings


class VecError(RuntimeError):
    """向量化 / 重排调用失败（网络、鉴权或上游错误）。"""


def _request(path: str, payload: dict | None, timeout: int | float | None = None) -> dict:
    base = settings.vec_base_url.rstrip("/")
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"{base}{path}", data=data, method="POST" if data else "GET")
    req.add_header("Content-Type", "application/json")
    if settings.vec_api_key:
        req.add_header("Authorization", f"Bearer {settings.vec_api_key}")
    try:
        with urllib.request.urlopen(req, timeout=timeout or settings.vec_timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "ignore")[:300]
        # 手册的错误码表：401 鉴权、400 结构写错、502 网关连不上上游、500 上游异常
        raise VecError(f"HTTP {exc.code}: {detail}") from exc
    except Exception as exc:  # noqa: BLE001 - 网络层异常统一转成 VecError，便于上层降级
        raise VecError(f"{type(exc).__name__}: {exc}") from exc


# ══════════════════════════════════════════════════════════════════
# 向量化
# ══════════════════════════════════════════════════════════════════


def embed_texts(
    texts: list[str],
    instruction: str | None = None,
    dimensions: int | None = None,
) -> list[list[float]]:
    """批量向量化文本，返回顺序与入参一致。

    :param instruction: 自定义指令（`messages[0].role=system`）。**不传时模型默认**
        `Represent the user's input.`；官方实测按任务定制指令有 1%~5% 收益。
        注意：带指令时必须走 `messages` 形式，而 `dimensions` 只在 `input` 形式下传
        （手册只给了这两种组合，混用未经验证）。
    :param dimensions: 覆盖配置的向量维度。**0/None 表示不传该参数**——
        本平台部署的 Embedding 不支持 MRL 降维（实测传了直接 400），默认取平台 4096 维。
    """
    if not texts:
        return []
    dims = dimensions if dimensions is not None else settings.embed_dimensions

    if instruction:
        out: list[list[float]] = []
        for text in texts:
            data = _request(
                "/embeddings",
                {
                    "model": settings.embed_model,
                    "messages": [
                        {"role": "system", "content": [{"type": "text", "text": instruction}]},
                        {"role": "user", "content": [{"type": "text", "text": text}]},
                    ],
                },
            )
            out.append(data["data"][0]["embedding"])
        return out

    payload: dict = {"model": settings.embed_model, "input": list(texts)}
    if dims:
        payload["dimensions"] = dims
    data = _request("/embeddings", payload)
    # 网关按 index 标注顺序；显式排序，避免上游乱序时把向量和文本错配
    rows = sorted(data.get("data", []), key=lambda d: d.get("index", 0))
    return [row["embedding"] for row in rows]


# ══════════════════════════════════════════════════════════════════
# 重排
# ══════════════════════════════════════════════════════════════════


def rerank(query: str, documents: list[str], top_k: int | None = None) -> list[tuple[int, float]]:
    """按相关性重排候选，返回 `[(原始下标, 分数)]`，分数降序。

    注意：上游**按原顺序**返回 `results`，下标是**入参里的下标**，
    所以调用方必须用这个下标回查自己的候选列表（别按返回顺序当排名用）。
    """
    if not documents:
        return []
    data = _request(
        "/rerank",
        {"model": settings.rerank_model, "query": query, "documents": list(documents)},
    )
    rows = sorted(data.get("results", []), key=lambda r: -float(r.get("relevance_score", 0.0)))
    pairs = [(int(r.get("index", 0)), float(r.get("relevance_score", 0.0))) for r in rows]
    return pairs[:top_k] if top_k else pairs


# ══════════════════════════════════════════════════════════════════
# 探活与工具函数
# ══════════════════════════════════════════════════════════════════


@dataclass
class VecHealth:
    ok: bool
    detail: str
    models: list[str] = field(default_factory=list)


def health(timeout: float = 10.0) -> VecHealth:
    """探活：`GET /models`（轻量，不消耗向量化/重排配额）。"""
    try:
        data = _request("/models", None, timeout=timeout)
    except VecError as exc:
        return VecHealth(ok=False, detail=str(exc))
    names = [m.get("id", "") for m in data.get("data", []) if isinstance(m, dict)]
    missing = [m for m in (settings.embed_model, settings.rerank_model) if names and m not in names]
    if missing:
        return VecHealth(
            ok=True,
            detail=f"网关可达，但模型列表里没有 {missing}（仍是 {len(names)} 个模型）",
            models=names,
        )
    return VecHealth(ok=True, detail="网关可达，两个模型都在", models=names)


def cosine(a: list[float], b: list[float]) -> float:
    """余弦相似度。向量已归一化时它等于点积，但这里不假设上游归一化。"""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b, strict=False))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0
