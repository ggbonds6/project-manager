"""文档库：把解析过的文档持久化，作为问答与（将来的）向量化的基础。

## 为什么需要这一层

1. **不重复解析**：OCR 一份 25 页扫描件要上百秒，解析一次就该存下来；
2. **问答的检索基础**：模型不可能把整篇文档塞进上下文，必须能"按需检索段落"；
3. **为向量化预留**：切片挂在 `chunks` 字段上，将来接向量库时**只替换检索实现**
   （`tools.search_documents`），问答编排与提示词都不用动。

## 存储格式

`work/docs/<doc_id>.json`：

```json
{
  "doc_id": "a1b2c3...",
  "filename": "数据库一体机补充协议.pdf",
  "kind": "scanned",
  "pages": [{"page_no": 1, "text": "...", "confidence": 0.97}],
  "avg_confidence": 0.97,
  "uploaded_at": "2026-09-16T15:50:00",
  "size_bytes": 5458280,
  "chunks": []
}
```

`chunks` 现在为空，是留给向量化的位置：
接入后每项形如 `{"page_no": 3, "text": "...", "embedding": [...]}`。

## 切片策略

按**段落**聚合到约 `chunk_chars` 字，**不跨页**。
为什么不按固定长度切：固定长度会把句子、条款从中间截断，检索回来的片段语义不完整——
切片质量是检索效果的分水岭，而"按段落"是不引入额外依赖时的合理起点。
后续做结构化切片（按条款/章节）时，只需改这里的实现。
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterator

from . import document
from .config import settings

DEFAULT_CHUNK_CHARS = 500
"""单个检索单元的目标字数。太小则语义不完整，太大则检索不精准。"""


# ── 数据结构 ─────────────────────────────────────────────────────

@dataclass
class StoredDoc:
    doc_id: str
    filename: str
    kind: str
    pages: list[dict] = field(default_factory=list)
    uploaded_at: str = ""
    size_bytes: int = 0
    engine: str = ""
    dpi: int | None = None
    avg_confidence: float | None = None
    provider: str = ""
    """解析时实际使用的引擎（`platform` / `rapid` / `paddle` / `text-layer`）。"""
    image_format: str = ""
    stages: dict = field(default_factory=dict)
    """阶段耗时 `{"render": 2.1, "ocr": 24.0}`——"时间花在哪"要看得见。"""
    checks: list[dict] = field(default_factory=list)
    """确定性校验结果（大小写金额互校等）——**可复现的"硬置信度"**，见 checks.py。"""
    notes: list[str] = field(default_factory=list)
    chunks: list[dict] = field(default_factory=list)
    """预留给向量化——见模块文档。"""

    # ── 只读派生属性 ──
    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def char_count(self) -> int:
        return sum(len(p.get("text") or "") for p in self.pages)

    @property
    def text(self) -> str:
        return "\n".join(p.get("text") or "" for p in self.pages)

    @property
    def review_pages(self) -> list[int]:
        """建议人工优先复核的页：识别失败的 + 空白的 + 校验不通过的。"""
        pages = {
            p.get("page_no")
            for p in self.pages
            if p.get("error") or not (p.get("text") or "").strip()
        }
        for chk in self.checks:
            if chk.get("status") in {"fail", "warn"}:
                pages |= {i.get("page") for i in chk.get("items", []) if i.get("page")}
        return sorted(p for p in pages if p)

    @property
    def check_summary(self) -> dict:
        counts: dict[str, int] = {}
        for c in self.checks:
            key = c.get("status", "skip")
            counts[key] = counts.get(key, 0) + 1
        return counts

    def page_quality(self) -> list[dict]:
        """页级明细（前端"过程性内容"展示用）。"""
        return [
            {
                "page_no": p.get("page_no"),
                "chars": p.get("chars", len(p.get("text") or "")),
                "elapsed": p.get("elapsed"),
                "source": p.get("source") or self.provider,
                "confidence": p.get("confidence"),
                "tables": p.get("tables", 0),
                "seals": p.get("seals", 0),
                "empty": not (p.get("text") or "").strip(),
                "error": p.get("error") or "",
                "note": p.get("note") or "",
            }
            for p in self.pages
        ]

    def page_text(self, page_no: int) -> str:
        for p in self.pages:
            if p.get("page_no") == page_no:
                return p.get("text") or ""
        return ""

    def labeled_text(self, max_chars: int | None = None) -> str:
        """带页码标记的全文（超出 max_chars 时按页截断）。"""
        blocks: list[str] = []
        total = 0
        for p in self.pages:
            body = (p.get("text") or "").strip() or "（本页未识别到文本）"
            marks: list[str] = []
            if p.get("tables"):
                marks.append(f"含表格 {p['tables']} 个")
            if p.get("seals"):
                marks.append(f"含印章 {p['seals']} 处（未识别文字）")
            suffix = " · " + "、".join(marks) if marks else ""
            block = f"【第 {p['page_no']} 页{suffix}】\n{body}"
            if max_chars is not None and total + len(block) > max_chars and blocks:
                blocks.append("（…后续页面因篇幅限制未提供…）")
                break
            blocks.append(block)
            total += len(block)
        return "\n\n".join(blocks)

    def meta(self) -> dict:
        """列表用的元信息（不含全文，避免列表接口返回巨大 JSON）。"""
        return {
            "doc_id": self.doc_id,
            "filename": self.filename,
            "kind": self.kind,
            "pages": self.page_count,
            "chars": self.char_count,
            "avg_confidence": self.avg_confidence,
            "uploaded_at": self.uploaded_at,
            "size_bytes": self.size_bytes,
            "engine": self.engine,
            "provider": self.provider or self.engine,
            "image_format": self.image_format,
            "review_pages": self.review_pages,
            "check_summary": self.check_summary,
        }

    def to_dict(self) -> dict:
        return {
            "doc_id": self.doc_id,
            "filename": self.filename,
            "kind": self.kind,
            "pages": self.pages,
            "uploaded_at": self.uploaded_at,
            "size_bytes": self.size_bytes,
            "engine": self.engine,
            "dpi": self.dpi,
            "avg_confidence": self.avg_confidence,
            "provider": self.provider,
            "image_format": self.image_format,
            "stages": self.stages,
            "checks": self.checks,
            "notes": self.notes,
            "chunks": self.chunks,
        }

    @classmethod
    def from_dict(cls, d: dict) -> "StoredDoc":
        return cls(
            doc_id=d["doc_id"],
            filename=d.get("filename", ""),
            kind=d.get("kind", ""),
            pages=d.get("pages", []),
            uploaded_at=d.get("uploaded_at", ""),
            size_bytes=d.get("size_bytes", 0),
            engine=d.get("engine", ""),
            dpi=d.get("dpi"),
            avg_confidence=d.get("avg_confidence"),
            provider=d.get("provider", ""),
            image_format=d.get("image_format", ""),
            stages=d.get("stages", {}),
            checks=d.get("checks", []),
            notes=d.get("notes", []),
            chunks=d.get("chunks", []),
        )


# ── 存储 ─────────────────────────────────────────────────────────

class DocStore:
    """基于文件系统的文档库。

    为什么用文件而不是数据库：当前阶段是"验证能力"，引入数据库会多一层运维；
    每个文档一个 JSON 也便于人工查看与排错。将来量大或需要并发写时，
    换实现即可（对外接口保持 `save/get/list/delete`）。
    """

    def __init__(self, root: Path | None = None) -> None:
        self.root = Path(root) if root else settings.work_dir / "docs"
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, doc_id: str) -> Path:
        # 防止 path 穿越：doc_id 只允许十六进制
        safe = re.sub(r"[^0-9a-fA-F]", "", doc_id)
        return self.root / f"{safe}.json"

    def save(self, filename: str, doc: document.DocumentText,
             size_bytes: int = 0) -> StoredDoc:
        """把一个已解析的文档入库，返回入库结果。

        页级信息**尽量留全**（来源 / 区域块 / 质量信号 / 耗时）——这些是"内容出处"
        与"复核优先级"的依据，丢了就只能回去重跑 OCR。
        `blocks` 只留 `label + bbox`（见 `PageText.blocks_brief`），正文已在 `text` 里，
        存全量块内容会让 JSON 体积翻几倍而没什么收益。
        """
        stored = StoredDoc(
            doc_id=uuid.uuid4().hex[:16],
            filename=filename,
            kind=doc.kind,
            pages=[
                {
                    "page_no": p.page_no,
                    "text": p.text,
                    "confidence": p.confidence,
                    "source": p.source,
                    "chars": p.chars,
                    "tables": p.table_count,
                    "seals": p.seal_count,
                    "elapsed": round(p.elapsed, 2),
                    "blocks": p.blocks_brief(),
                    "error": p.error,
                    "note": p.note,
                }
                for p in doc.pages
            ],
            uploaded_at=datetime.now().isoformat(timespec="seconds"),
            size_bytes=size_bytes,
            engine=doc.engine,
            dpi=doc.dpi,
            avg_confidence=doc.avg_confidence,
            provider=doc.provider,
            image_format=doc.image_format,
            stages=doc.stages,
            checks=doc.checks,
            notes=doc.notes,
            chunks=[],
        )
        self._write(stored)
        return stored

    def _write(self, doc: StoredDoc) -> None:
        path = self._path(doc.doc_id)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(doc.to_dict(), ensure_ascii=False), encoding="utf-8")
        tmp.replace(path)   # 原子替换，避免写一半被读到

    def get(self, doc_id: str) -> StoredDoc | None:
        path = self._path(doc_id)
        if not path.is_file():
            return None
        try:
            return StoredDoc.from_dict(json.loads(path.read_text(encoding="utf-8")))
        except Exception:  # noqa: BLE001 - 单个文件损坏不应影响整体
            return None

    def list(self) -> list[dict]:
        """列出全部文档的元信息，最近上传在前。"""
        items: list[dict] = []
        for path in self.root.glob("*.json"):
            try:
                doc = StoredDoc.from_dict(json.loads(path.read_text(encoding="utf-8")))
                items.append(doc.meta())
            except Exception:  # noqa: BLE001
                continue
        items.sort(key=lambda m: m.get("uploaded_at", ""), reverse=True)
        return items

    def delete(self, doc_id: str) -> bool:
        path = self._path(doc_id)
        if path.is_file():
            path.unlink()
            return True
        return False

    def all_docs(self) -> Iterator[StoredDoc]:
        for path in self.root.glob("*.json"):
            try:
                yield StoredDoc.from_dict(json.loads(path.read_text(encoding="utf-8")))
            except Exception:  # noqa: BLE001
                continue


# ── 切片（检索的最小单元）─────────────────────────────────────────

def iter_chunks(doc: StoredDoc, chunk_chars: int = DEFAULT_CHUNK_CHARS) -> Iterator[dict]:
    """把文档切成检索单元，**不跨页**，返回 {doc_id, page_no, text}。

    页号必须带着走——检索结果要能标注来源，否则提示词里"必须标来源"就无从落地。
    """
    for page in doc.pages:
        page_no = page.get("page_no", 0)
        text = (page.get("text") or "").strip()
        if not text:
            continue

        # 先按空行分段；若该页没有空行，则按换行切
        paras = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
        if len(paras) <= 1:
            paras = [p.strip() for p in text.splitlines() if p.strip()]

        buf = ""
        for para in paras:
            if buf and len(buf) + len(para) > chunk_chars:
                yield {"doc_id": doc.doc_id, "filename": doc.filename,
                       "page_no": page_no, "text": buf}
                buf = para
            else:
                buf = f"{buf}\n{para}" if buf else para
        if buf:
            yield {"doc_id": doc.doc_id, "filename": doc.filename,
                   "page_no": page_no, "text": buf}


store = DocStore()
