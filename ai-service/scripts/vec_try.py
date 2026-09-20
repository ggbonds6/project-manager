#!/usr/bin/env python
"""检索链路验证：Embedding 召回 + Reranker 精排（可端到端跑通"解析 → 切片 → 向量 → 检索"）。

手册：`Qwen3-VL-Embedding-Reranker调用手册.md`（网关与对话/OCR 同一个，共用一把 sk）

用法（在 ai-service 目录下）：
  python scripts/vec_try.py                       # 默认自检：探活 + 向量化 + 重排（对应手册 §7.1）
  python scripts/vec_try.py --health              # 只探活（GET /models，不消耗配额）
  python scripts/vec_try.py --ingest work/samples/xxx.pdf   # 解析入库（扫描件会自动走平台 OCR）
  python scripts/vec_try.py --search "中标金额是多少"
  python scripts/vec_try.py --search "付款条款" --doc-id <id> --top-k 5
  python scripts/vec_try.py --ingest a.pdf --search "验收标准"   # 一条命令跑完整链路

为什么单独一个脚本：检索质量是问答质量的**天花板**（路线图 §0.3），
所以"向量化是否正常、重排是否真的把相关项排上来"必须能单独验证，
而不是等问答答错了再回头猜是解析、检索还是模型的问题。
"""

from __future__ import annotations

import argparse
import contextlib
import math
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

# Windows 控制台默认是 GBK：不切 UTF-8 的话，中文会乱码、非 GBK 符号直接抛 UnicodeEncodeError
with contextlib.suppress(Exception):  # 老解释器 / 输出被重定向时忽略即可
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pm_ai import document, retrieval, vec_client  # noqa: E402
from pm_ai.config import settings  # noqa: E402
from pm_ai.store import iter_chunks, store  # noqa: E402

SELFTEST_QUERY = "海光 K100AI 推理优化"
SELFTEST_DOCS = [
    "海光 K100AI 是国产 GPGPU，本文介绍 INT8 量化与算子融合带来的推理优化。",
    "今天天气不错，适合出门散步。",
    "政府采购合同应当约定付款方式、验收标准与违约责任。",
]


def _print_config() -> None:
    print(f"网关：{settings.vec_base_url or '（未配置）'}")
    # 0 = 不传维度参数（平台默认 4096）。本平台部署不支持 MRL 降维，见 config.py 注释。
    dims = f"{settings.embed_dimensions} 维" if settings.embed_dimensions else "平台默认维（4096）"
    print(f"模型：{settings.embed_model}（{dims}） / {settings.rerank_model}")
    print(f"召回：{settings.retrieval_recall} 条 → 精排取 top_k={settings.retrieval_top_k}")
    print(f"索引后端：{settings.vec_backend}（local = 进程内余弦；opensearch = 正式选型）")


def cmd_health() -> int:
    health = vec_client.health()
    mark = "[OK]" if health.ok else "[FAIL]"
    print(f"{mark} {health.detail}")
    for name in health.models:
        print(f"   - {name}")
    return 0 if health.ok else 1


def cmd_selftest() -> int:
    """手册 §7.1 的三项功能验收：向量化维度 / 语义判别 / 重排排序。"""
    print("── 1) 向量化（两条文本）──")
    t0 = time.perf_counter()
    vectors = vec_client.embed_texts(["海光 K100AI 推理优化", "今天天气不错"])
    elapsed = time.perf_counter() - t0
    if len(vectors) != 2:
        print(f"[FAIL] 期望 2 条向量，实际 {len(vectors)} 条")
        return 1
    sim = vec_client.cosine(vectors[0], vectors[1])
    print(f"   维度 {len(vectors[0])} ｜ 相似度 {sim:.4f} ｜ 耗时 {elapsed:.2f}s")

    print("── 2) 语义判别（相关 vs 无关）──")
    vectors = vec_client.embed_texts([SELFTEST_QUERY, SELFTEST_DOCS[0], SELFTEST_DOCS[1]])
    related = vec_client.cosine(vectors[0], vectors[1])
    unrelated = vec_client.cosine(vectors[0], vectors[2])
    ok = related > unrelated
    print(
        f"   相关 {related:.4f} {'>' if ok else '<='} 无关 {unrelated:.4f} {'[OK]' if ok else '[FAIL]'}"
    )

    print("── 3) 重排（1 query + 3 docs）──")
    t0 = time.perf_counter()
    ranked = vec_client.rerank(SELFTEST_QUERY, SELFTEST_DOCS)
    elapsed = time.perf_counter() - t0
    for index, score in ranked:
        print(f"   {score:.4f}  [{index}] {SELFTEST_DOCS[index][:40]}")
    top_ok = bool(ranked) and ranked[0][0] == 0  # 第 0 条应当被排在第一位
    print(f"   耗时 {elapsed:.2f}s ｜ 首位命中 {'[OK]' if top_ok else '[FAIL]'}")
    return 0 if (ok and top_ok) else 1


def cmd_ingest(path: Path) -> str | None:
    """解析 → 切片 → 入库（向量按需在检索时补算并缓存）。"""
    if not path.is_file():
        print(f"[FAIL] 文件不存在：{path}")
        return None
    print(f"── 解析入库：{path.name} ──")

    def on_progress(stage: str, done: int, total: int) -> None:
        if total and (done == total or done % 5 == 0):
            print(f"   [{stage}] {done}/{total}")

    t0 = time.perf_counter()
    doc = document.read_document(path, on_progress=on_progress)
    if doc.error:
        print(f"[FAIL] 解析失败：{doc.error}")
        return None
    saved = store.save(filename=path.name, doc=doc, size_bytes=path.stat().st_size)
    chunks = list(iter_chunks(saved))
    print(
        f"   [OK] doc_id={saved.doc_id} ｜ {doc.kind} ｜ {doc.page_count} 页 ｜ "
        f"{doc.char_count} 字 ｜ 切片 {len(chunks)} ｜ 耗时 {time.perf_counter() - t0:.2f}s"
    )
    if doc.notes:
        for note in doc.notes:
            print(f"   [!] {note}")
    if doc.failed_pages:
        print(f"   [!] 识别失败页：{doc.failed_pages}（本地兜底已移除，这些页没有内容）")
    return saved.doc_id


def cmd_search(query: str, doc_id: str | None, top_k: int) -> int:
    print(f"── 检索：{query!r}{f'（限 doc_id={doc_id}）' if doc_id else ''} ──")
    t0 = time.perf_counter()
    result = retrieval.search(query, top_k=top_k, doc_id=doc_id)
    elapsed = time.perf_counter() - t0
    print(
        f"   方式 {result['retrieval']} ｜ 重排 {'是' if result['reranked'] else '否'} ｜ "
        f"命中 {result['found']} ｜ 耗时 {elapsed:.2f}s"
    )
    if result.get("note"):
        print(f"   [!] {result['note']}")
    for i, hit in enumerate(result["hits"], 1):
        print(
            f"   {i}. [{hit['score']:.4f}] {hit['filename']} P{hit['page_no']}"
            f"（向量 {hit['vector_score']:.3f} / 关键词 {hit['keyword_score']}）"
        )
        print(f"      {hit['text'][:90].replace(chr(10), ' ')}")
    return 0 if result["hits"] else 1


def _install_offline_stub() -> None:
    """把向量化/重排换成**确定性伪实现**，用于离线演练整条链路。

    什么时候用：开发机不在政务内网（连不上网关）时，仍要确认
    "切片 → 向量缓存 → 召回 → 融合 → 精排 → 命中带页码"这条接线是对的。
    ⚠️ 伪向量由文本哈希铺满，**没有任何语义**，因此检索结果本身无意义，
    只看"有没有结果、顺序是否由重排决定、缓存是否命中"。
    """
    import hashlib

    def fake_embed(texts: list[str], instruction: str | None = None, dimensions: int | None = None):
        dims = dimensions or settings.embed_dimensions
        out: list[list[float]] = []
        for text in texts:
            digest = hashlib.sha256(text.encode("utf-8")).digest()
            vec = [(digest[i % len(digest)] / 255.0) - 0.5 for i in range(dims)]
            norm = math.sqrt(sum(v * v for v in vec)) or 1.0
            out.append([v / norm for v in vec])
        return out

    def fake_rerank(query: str, documents: list[str], top_k: int | None = None):
        # 用字符 2-gram 重合度做分数：确定性、可解释，便于人工确认"排序确实来自重排"
        def bigrams(s: str) -> set[str]:
            s = "".join(ch for ch in s if not ch.isspace())
            return {s[i : i + 2] for i in range(max(0, len(s) - 1))}

        q = bigrams(query)
        scored = []
        for index, doc in enumerate(documents):
            d = bigrams(doc)
            score = len(q & d) / len(q) if q else 0.0
            scored.append((index, round(score, 4)))
        scored.sort(key=lambda pair: -pair[1])
        return scored[:top_k] if top_k else scored

    vec_client.embed_texts = fake_embed  # type: ignore[assignment]
    vec_client.rerank = fake_rerank  # type: ignore[assignment]


def main() -> int:
    parser = argparse.ArgumentParser(description="检索链路验证（Embedding + Reranker）")
    parser.add_argument("--health", action="store_true", help="只探活")
    parser.add_argument("--selftest", action="store_true", help="自检：向量化 + 语义判别 + 重排")
    parser.add_argument("--ingest", default="", help="解析并入库一个 PDF/图片")
    parser.add_argument("--search", default="", help="检索（全库或 --doc-id 指定文档）")
    parser.add_argument("--doc-id", default="", help="限定文档 ID")
    parser.add_argument("--top-k", type=int, default=0, help="精排后返回条数，默认取配置")
    parser.add_argument(
        "--offline",
        action="store_true",
        help="离线演练：用确定性伪向量/伪重排跑通链路（网关不可达时用，结果无语义）",
    )
    args = parser.parse_args()

    _print_config()
    if args.offline:
        _install_offline_stub()
        print("[!] 离线演练模式：向量与重排都是**伪实现**，只看接线是否通，不看结果质量")
    print()

    if args.health:
        return cmd_health()

    doc_id = args.doc_id or None
    failed = 0

    if args.ingest:
        ingested = cmd_ingest(Path(args.ingest))
        if ingested is None:
            return 1
        doc_id = ingested
        print()

    if args.search:
        failed |= cmd_search(args.search, doc_id, args.top_k)
    elif not args.ingest:
        if args.offline:
            # 伪向量没有语义，跑手册 §7.1 的"语义判别/排序正确"必然失败——那是假阴性，
            # 离线模式只做接线演练，请搭配 --ingest / --search 使用。
            print("[!] 离线模式请配合 --ingest <文件> 或 --search <查询> 使用（自检需要真实模型）")
            return 0
        # 默认自检：先探活，再跑手册 §7.1 的三项
        failed |= cmd_health()
        print()
        failed |= cmd_selftest()

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
