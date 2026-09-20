"""检索链路（`retrieval.py`）：精排排序、关键词降级、向量缓存、切片键。

全程**离线**：向量化与重排都被 monkeypatch 掉——真实模型（Qwen3-VL-Embedding/Reranker）
在政务内网，开发机连不上，所以这里只钉"接线与顺序"，不钉模型质量。
"""

from __future__ import annotations

import pytest

from pm_ai import retrieval, vec_client
from pm_ai.document import DocumentText, PageText
from pm_ai.store import DocStore


@pytest.fixture
def doc_store(tmp_path, monkeypatch):
    """把检索用的文档库与向量缓存目录都换成临时目录，别碰仓库里的 work/。"""
    ds = DocStore(root=tmp_path / "docs")
    monkeypatch.setattr(retrieval, "store", ds)
    monkeypatch.setattr(retrieval.settings, "work_dir", tmp_path)
    return ds


def _save(ds: DocStore, name: str, *texts: str) -> str:
    doc = DocumentText(
        kind="text_pdf",
        pages=[PageText(page_no=i + 1, text=text) for i, text in enumerate(texts)],
    )
    return ds.save(filename=name, doc=doc).doc_id


def test_hybrid_keeps_rerank_order(doc_store, monkeypatch):
    """最终顺序必须由 Reranker 决定（而不是向量分数）——否则精排等于没接。"""
    _save(doc_store, "a.pdf", "合同金额为一百万元，付款方式为分期付款。")
    _save(doc_store, "b.pdf", "验收标准参照国家标准执行。")

    monkeypatch.setattr(vec_client, "embed_texts", lambda texts, **kw: [[1.0, 0.0] for _ in texts])

    def fake_rerank(query, documents, top_k=None):
        # 故意把"候选里的第 2 条"排到第一
        return [(1, 0.9), (0, 0.1)]

    monkeypatch.setattr(vec_client, "rerank", fake_rerank)

    result = retrieval.search("付款方式", top_k=2)
    assert result["retrieval"] == "hybrid"
    assert result["reranked"] is True
    assert [hit["score"] for hit in result["hits"]] == [0.9, 0.1]


def test_degrades_to_keyword_when_vec_unavailable(doc_store, monkeypatch):
    """向量/重排不可用时必须降级为关键词检索，并把降级原因写进 note（不许静默）。"""

    def boom(*args, **kwargs):
        raise vec_client.VecError("HTTP 502: rerank upstream error")

    _save(doc_store, "a.pdf", "合同金额为一百万元。")
    monkeypatch.setattr(vec_client, "embed_texts", boom)
    monkeypatch.setattr(vec_client, "rerank", boom)

    result = retrieval.search("合同金额", top_k=3)
    assert result["retrieval"] == "keyword"
    assert result["reranked"] is False
    assert result["found"] == 1
    assert "向量服务不可用" in result["note"]


def test_vector_cache_avoids_reembedding_chunks(doc_store, monkeypatch):
    """切片向量落缓存：第二次检索只该为 query 编码（这是"预建索引"的最小形态）。"""
    _save(doc_store, "a.pdf", "第一页内容" * 5)
    calls: list[int] = []

    def fake_embed(texts, **kwargs):
        calls.append(len(texts))
        return [[1.0, 0.0] for _ in texts]

    monkeypatch.setattr(vec_client, "embed_texts", fake_embed)
    monkeypatch.setattr(
        vec_client,
        "rerank",
        lambda query, documents, top_k=None: [(i, 1.0 - i * 0.1) for i in range(len(documents))],
    )

    retrieval.search("内容")
    assert len(calls) == 2, "首次：切片 + query 各一次"

    calls.clear()
    retrieval.search("内容")
    assert calls == [1], "第二次：切片命中缓存，只编码 query"


def test_chunk_key_follows_text_content():
    """切片键必须带内容指纹——文本变了就当新切片，向量缓存自然失效。"""
    chunk = {"doc_id": "d", "page_no": 1, "text": "甲"}
    assert retrieval.chunk_key(chunk) == retrieval.chunk_key(dict(chunk))
    assert retrieval.chunk_key(chunk) != retrieval.chunk_key({**chunk, "text": "乙"})


def test_unimplemented_backend_fails_fast(doc_store, monkeypatch):
    """`VEC_BACKEND=opensearch` 尚未实现适配层，必须**显式报错**而不是静默退回进程内检索。"""
    monkeypatch.setattr(retrieval.settings, "vec_backend", "opensearch")
    _save(doc_store, "a.pdf", "合同金额为一百万元。")
    with pytest.raises(vec_client.VecError, match="尚未实现"):
        retrieval.search("合同金额")
