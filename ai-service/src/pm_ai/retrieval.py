"""检索链路：向量召回 + 关键词召回 → Reranker 精排。

手册：`Qwen3-VL-Embedding-Reranker调用手册.md` §5（召回 50~100 条 → 重排取 Top 5~10）。

三步：
1. **召回**：向量（Qwen3-VL-Embedding-8B）与关键词（字符 2-gram + IDF，不引分词库）各取一批；
2. **融合**：按 (doc_id, page_no, 文本内容) 去重 —— 向量命中的优先（语义召回），关键词命中补齐；
3. **精排**：Reranker（Qwen3-VL-Reranker-8B）逐对打分，取 top_k。

三条设计约束（来自《知识库总体架构与演进路线》）：
- 向量与索引都是**可重建物**，所以缓存在 `work/vectors/` 下，删掉重算即可，不进主系统；
- 索引后端可切：`VEC_BACKEND=local`（进程内余弦，当前默认、零部署）
  → `opensearch`（**正式选型**；集群尚未部署、**适配层也尚未实现**——配成它会显式报错，
    不静默退回进程内检索，见 `_check_backend()` 与《知识库落地实施方案》）；
- 向量服务不可用时**退化为关键词检索**并在结果里写明。检索降级与 OCR 降级性质不同：
  答案仍然带页码来源，只是召回变少（"没找到"是可见的），不会产生"看起来对但实际编造"的结果。
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from pathlib import Path

from . import vec_client
from .config import settings
from .store import StoredDoc, iter_chunks, store

MAX_SNIPPET_CHARS = 500
"""单个检索片段返回给模型的最大字数——太长会白占上下文。"""

EMBED_BATCH = 32
"""单次向量化请求的条数。手册实测：单请求 32 条 + 并发 8 可达 147.9 条/s（单卡）。"""

_STOP_BIGRAMS = {
    "的是",
    "了的",
    "和和",
    "在在",
    "有有",
    "我我",
    "你你",
    "他他",
    "什么",
    "怎么",
    "哪些",
    "哪个",
    "如何",
    "请问",
    "告诉",
}
"""高频但无区分度的二元组，降权用（没有分词库时的粗糙替代）。"""


# ══════════════════════════════════════════════════════════════════
# 切片与关键词召回
# ══════════════════════════════════════════════════════════════════


def load_chunks(doc_id: str | None = None) -> list[dict]:
    """取出待检索的切片（`doc_id` 为空则全库）。"""
    docs: list[StoredDoc] = (
        [d for d in [store.get(doc_id)] if d] if doc_id else list(store.all_docs())
    )
    chunks: list[dict] = []
    for doc in docs:
        chunks.extend(iter_chunks(doc))
    return chunks


def chunk_key(chunk: dict) -> str:
    """切片唯一键：文档 + 页 + 内容指纹。

    带内容指纹是为了"文本变了就当作新切片"——向量缓存据此自动失效，
    不需要额外的版本号或重建流程。
    """
    text = chunk.get("text") or ""
    digest = hashlib.sha1(text.encode("utf-8")).hexdigest()[:12]
    return f"{chunk.get('doc_id')}:{chunk.get('page_no')}:{digest}"


def _query_terms(query: str) -> list[str]:
    """把查询拆成检索项：英文/数字取整词，中文取 2-gram。

    为什么用 2-gram 而不是分词：中文不引分词库（内网离线装机省事），
    2-gram 对"付款条款""中标金额"这类术语足够有效。
    """
    q = (query or "").strip().lower()
    terms: set[str] = set()
    terms.update(re.findall(r"[a-z0-9][a-z0-9\-\.]*", q))
    han = re.sub(r"[^\u4e00-\u9fff]", "", q)
    if len(han) == 1:
        terms.add(han)
    for i in range(len(han) - 1):
        bigram = han[i : i + 2]
        if bigram not in _STOP_BIGRAMS:
            terms.add(bigram)
    return list(terms)


def keyword_scores(query: str, chunks: list[dict]) -> dict[str, float]:
    """关键词打分（简化版 BM25），返回 `{切片键: 分数}`。

    - 完整查询命中 → 高权重；
    - 单个检索项命中 → 基础分 + 词频加成；
    - 用 IDF 压制"项目""合同"这类到处都是的词，突出稀有词。
    """
    terms = _query_terms(query)
    raw = (query or "").strip().lower()
    if not terms and not raw:
        return {}

    df: dict[str, int] = dict.fromkeys(terms, 0)
    for ch in chunks:
        low = ch["text"].lower()
        for t in terms:
            if t in low:
                df[t] += 1
    n = len(chunks)

    scores: dict[str, float] = {}
    for ch in chunks:
        low = ch["text"].lower()
        score = 0.0
        if raw and raw in low:
            score += 12.0
        for t in terms:
            count = low.count(t)
            if not count:
                continue
            idf = math.log(1 + n / (1 + df.get(t, 0)))  # 稀有词权重高
            score += idf * (1 + min(count, 5) * 0.25)
        if score > 0:
            scores[chunk_key(ch)] = score
    return scores


# ══════════════════════════════════════════════════════════════════
# 向量召回（含本地缓存）
# ══════════════════════════════════════════════════════════════════


def _vec_path(doc_id: str) -> Path:
    return settings.work_dir / "vectors" / f"{doc_id}.json"


def _load_vectors(doc_id: str, keep: set[str]) -> dict[str, list[float]]:
    """读本地向量缓存。模型名变了、或切片内容变了 → 视为失效。

    `VEC_EMBED_DIMENSIONS=0`（不传维度、用平台默认）时不校验维度——
    缓存里存的是实际维度，换部署导致维度变化会让余弦算不出来（长度不等 → 0 分），
    届时删掉 `work/vectors/` 重算即可（向量本来就是可重建物）。
    """
    path = _vec_path(doc_id)
    if not path.is_file():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 - 缓存坏了直接重算，不影响功能
        return {}
    if payload.get("model") != settings.embed_model:
        return {}
    if settings.embed_dimensions and payload.get("dimensions") != settings.embed_dimensions:
        return {}
    items = payload.get("items") or {}
    return {k: v for k, v in items.items() if k in keep}


def _save_vectors(doc_id: str, items: dict[str, list[float]]) -> None:
    path = _vec_path(doc_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    # 维度写**实际长度**（平台默认 4096，且不支持 MRL 降维）。
    # 分量保留 6 位小数：4096 维 × 每切片一份 JSON，全精度浮点会让缓存体积翻倍，
    # 而 6 位对余弦排序毫无影响（本缓存只是过渡，正式向量归 OpenSearch）。
    dims = len(next(iter(items.values()))) if items else settings.embed_dimensions
    payload = {
        "model": settings.embed_model,
        "dimensions": dims,
        "items": {k: [round(x, 6) for x in v] for k, v in items.items()},
    }
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def embed_chunks(chunks: list[dict], on_progress=None) -> dict[str, list[float]]:
    """确保所有切片都有向量（缺的补算并落缓存），返回 `{切片键: 向量}`。

    按文档分组缓存：一份文档的切片进了同一份 JSON，重算粒度就是"这份文档"。
    """
    by_doc: dict[str, list[dict]] = {}
    for ch in chunks:
        by_doc.setdefault(ch.get("doc_id") or "", []).append(ch)

    out: dict[str, list[float]] = {}
    for doc_id, doc_chunks in by_doc.items():
        keys = [chunk_key(ch) for ch in doc_chunks]
        cached = _load_vectors(doc_id, set(keys))
        out.update(cached)

        missing = [ch for ch, k in zip(doc_chunks, keys, strict=False) if k not in cached]
        if not missing:
            continue
        done = 0
        for start in range(0, len(missing), EMBED_BATCH):
            batch = missing[start : start + EMBED_BATCH]
            vectors = vec_client.embed_texts([ch["text"] for ch in batch])
            for ch, vec in zip(batch, vectors, strict=False):
                out[chunk_key(ch)] = vec
            done += len(batch)
            if on_progress:
                on_progress(done, len(missing))
        _save_vectors(doc_id, {k: out[k] for k in keys if k in out})
    return out


def vector_scores(query_vector: list[float], chunks: list[dict], vectors: dict) -> dict[str, float]:
    """查询向量与各切片的余弦相似度。"""
    scores: dict[str, float] = {}
    for ch in chunks:
        vec = vectors.get(chunk_key(ch))
        if vec:
            scores[chunk_key(ch)] = vec_client.cosine(query_vector, vec)
    return scores


# ══════════════════════════════════════════════════════════════════
# 对外：混合检索
# ══════════════════════════════════════════════════════════════════


def _check_backend() -> None:
    """索引后端守卫：**OpenSearch 适配层尚未实现**，配置成它时必须显式失败。

    为什么不静默退回 local：那会把"以为在用集群"与"实际在进程内算"混在一起——
    数据规模、召回质量、运维方式完全不同，静默降级是最难排查的一类问题。
    OpenSearch 是**正式选型**（集群尚未部署），部署后在 `embed_chunks` / 召回两处
    补适配层即可（切片键已按内容指纹生成，可直接当 `_id` 做幂等 upsert）。
    """
    if settings.vec_backend != "local":
        raise vec_client.VecError(
            f"VEC_BACKEND={settings.vec_backend} 的索引适配层尚未实现（当前只支持 local）；"
            f"OpenSearch 部署后在此接入，选型与字段设计见 docs/知识库落地实施方案.md"
        )


def search(
    query: str,
    top_k: int | None = None,
    doc_id: str | None = None,
    recall: int | None = None,
) -> dict:
    """混合检索：向量 + 关键词召回 → Reranker 精排 → top_k。

    返回 `{found, hits, retrieval, reranked, note}`；`hits[i]` 保留
    `doc_id / filename / page_no / score / text`（模型据此标注来源页码）。
    """
    query = (query or "").strip()
    if not query:
        return {"found": 0, "hits": [], "retrieval": "none", "reranked": False, "note": "查询为空"}

    # 配置了未实现的后端就直接失败（不静默降级），错误由调用方暴露给使用者
    _check_backend()

    chunks = load_chunks(doc_id)
    if not chunks:
        return {
            "found": 0,
            "hits": [],
            "retrieval": "none",
            "reranked": False,
            "note": "文档库里还没有切片（先上传并解析文档）",
        }

    top_k = max(1, min(int(top_k or settings.retrieval_top_k), 10))
    recall = max(top_k, int(recall or settings.retrieval_recall))

    kw = keyword_scores(query, chunks)

    vec: dict[str, float] = {}
    note = ""
    try:
        vectors = embed_chunks(chunks)
        query_vector = vec_client.embed_texts([query])[0]
        vec = vector_scores(query_vector, chunks, vectors)
    except vec_client.VecError as exc:
        note = f"向量服务不可用（{exc}），本次仅用关键词召回"

    by_key = {chunk_key(ch): ch for ch in chunks}
    candidates: list[str] = []
    for key, _ in sorted(vec.items(), key=lambda kv: -kv[1])[:recall]:
        candidates.append(key)
    for key, _ in sorted(kw.items(), key=lambda kv: -kv[1])[: max(1, recall // 2)]:
        if key not in candidates:
            candidates.append(key)

    if not candidates:
        return {
            "found": 0,
            "hits": [],
            "retrieval": "hybrid" if vec else "keyword",
            "reranked": False,
            "note": note or "没有检索到相关内容",
        }

    reranked = False
    order: list[tuple[str, float]] = []
    try:
        texts = [by_key[key]["text"] for key in candidates]
        pairs = vec_client.rerank(query, texts, top_k=top_k)
        order = [(candidates[idx], score) for idx, score in pairs]
        reranked = True
    except vec_client.VecError as exc:
        reason = f"重排服务不可用（{exc}），按召回分数排序"
        note = f"{note}；{reason}" if note else reason
        order = sorted(
            candidates,
            key=lambda key: -(vec.get(key, 0.0) * 2 + kw.get(key, 0.0)),
        )[:top_k]
        order = [(key, vec.get(key, 0.0)) for key in order]

    hits: list[dict] = []
    for key, score in order[:top_k]:
        chunk = by_key[key]
        text = chunk["text"]
        hits.append(
            {
                "doc_id": chunk["doc_id"],
                "filename": chunk["filename"],
                "page_no": chunk["page_no"],
                "score": round(score, 4),
                "keyword_score": round(kw.get(key, 0.0), 2),
                "vector_score": round(vec.get(key, 0.0), 4),
                "text": text[:MAX_SNIPPET_CHARS] + ("…" if len(text) > MAX_SNIPPET_CHARS else ""),
            }
        )

    return {
        "found": len(hits),
        "hits": hits,
        "retrieval": "hybrid" if vec else "keyword",
        "reranked": reranked,
        "note": note,
    }
