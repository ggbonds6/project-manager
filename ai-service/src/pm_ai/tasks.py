"""上传任务队列：让"一次传多个文件"看得见、能排队、可取消。

## 为什么需要这一层

原来的 `POST /documents` 是**同步**接口——解析完才返回。后果：

- 前端只能"转圈"，**看不到任何进度**（一份 25 页扫描件要几十秒，用户不知道它在干什么）；
- 多文件只能串行等，且前一个的提示会被后一个顶掉（前端 `addNotice` 是单例）；
- 关了页面/刷新，正在进行的工作就"消失"了，也不知道到底成没成。

## 设计要点

| 决策 | 理由 |
| --- | --- |
| **提交即返回** | 收下文件、落暂存区、登记任务，立刻给 task_id；解析在后台线程池里跑 |
| **进度来自解析器内部** | `document.read_document` 的 on_progress 回调实时写入任务（渲染 x/y 页、识别 x/y 页） |
| **总进度按阶段加权** | 渲染约占 10%、识别约占 80%——只报"页数"在渲染阶段会让人以为卡住了 |
| **落盘 `work/tasks.json`** | 刷新页面/重启服务后历史还在；重启时把残留的"进行中"标为失败（执行线程已没了，留着会让前端无限轮询） |
| **可取消** | 请求发出去了没法中断，但下一批/下一页开始前会检查取消标记，通常几秒内生效 |
"""

from __future__ import annotations

import json
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from . import document
from .config import settings
from .store import store

MAX_TASKS_KEPT = 100
"""落盘保留的任务条数（只留最近这些，避免文件无限增长）。"""

STAGE_LABEL = {
    "receive": "接收文件",
    "detect": "判定文件类型",
    "render": "渲染页面",
    "ocr": "识别中",
    "ocr-fallback": "本地引擎补跑",
    "check": "数据校验",
    "store": "写入文档库",
    "done": "完成",
}

_STAGE_RANGE = {
    # 阶段 → (该阶段起点占比, 终点占比)。识别是最耗时的一段，权重给到 0.72。
    "render": (0.05, 0.20),
    "ocr": (0.20, 0.92),
    "ocr-fallback": (0.92, 0.97),
    "check": (0.97, 0.99),
    "store": (0.99, 1.0),
}


class TaskCancelled(RuntimeError):
    """任务被用户取消（在进度回调里抛出，用来中断解析）。"""


@dataclass
class Task:
    task_id: str
    filename: str
    size_bytes: int = 0
    status: str = "QUEUED"
    """`QUEUED` / `PARSING` / `DONE` / `FAILED` / `CANCELLED`"""
    stage_key: str = "receive"
    stage: str = STAGE_LABEL["receive"]
    stage_done: int = 0
    stage_total: int = 0
    provider: str = ""
    doc_id: str = ""
    error: str = ""
    notes: list[str] = field(default_factory=list)
    pages: list[dict] = field(default_factory=list)
    stages: dict = field(default_factory=dict)
    checks: list[dict] = field(default_factory=list)
    summary: dict = field(default_factory=dict)
    created_at: str = ""
    started_at: str = ""
    finished_at: str = ""
    tmp_path: str = ""
    cancel_requested: bool = False

    # ── 派生 ──
    @property
    def percent(self) -> float:
        """总进度（0–100）。按阶段加权，避免"渲染 12 页"看起来像卡住。"""
        if self.status == "DONE":
            return 100.0
        lo, hi = _STAGE_RANGE.get(self.stage_key, (0.0, 0.05))
        frac = (self.stage_done / self.stage_total) if self.stage_total else 0.0
        return round((lo + (hi - lo) * min(1.0, frac)) * 100, 1)

    @property
    def elapsed(self) -> float:
        if not self.started_at:
            return 0.0
        end = self.finished_at or datetime.now().isoformat(timespec="seconds")
        try:
            t0 = datetime.fromisoformat(self.started_at)
            t1 = datetime.fromisoformat(end)
        except ValueError:
            return 0.0
        return round((t1 - t0).total_seconds(), 1)

    def to_dict(self, with_pages: bool = True) -> dict:
        d = {
            "task_id": self.task_id,
            "filename": self.filename,
            "size_bytes": self.size_bytes,
            "status": self.status,
            "stage": self.stage,
            "stage_key": self.stage_key,
            "stage_done": self.stage_done,
            "stage_total": self.stage_total,
            "percent": self.percent,
            "provider": self.provider,
            "doc_id": self.doc_id,
            "error": self.error,
            "notes": self.notes,
            "stages": self.stages,
            "checks": self.checks,
            "summary": self.summary,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "elapsed": self.elapsed,
        }
        if with_pages:
            d["pages"] = self.pages
        return d


class TaskManager:
    """线程池 + 落盘的任务管理器。"""

    def __init__(self, path: Path | None = None, pool_size: int | None = None) -> None:
        self.path = Path(path) if path else settings.work_dir / "tasks.json"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._tasks: dict[str, Task] = {}
        self._order: list[str] = []
        self._lock = threading.Lock()
        self._last_save = 0.0
        self._pool = ThreadPoolExecutor(
            max_workers=max(1, pool_size or settings.ocr_concurrency),
            thread_name_prefix="pm-task",
        )
        self._load()

    # ── 持久化 ──
    def _load(self) -> None:
        if not self.path.is_file():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001 - 文件损坏不该拖垮服务
            return
        for item in data.get("tasks") or []:
            item.pop("percent", None)
            item.pop("elapsed", None)
            try:
                t = Task(**{k: v for k, v in item.items() if k in Task.__dataclass_fields__})
            except Exception:  # noqa: BLE001
                continue
            # 上次进程已经没了：残留的进行中任务不可能再推进，标成失败，否则前端会一直轮询
            if t.status in {"QUEUED", "PARSING"}:
                t.status = "FAILED"
                t.error = "服务重启，任务已中断（可重新上传）"
                t.finished_at = t.finished_at or datetime.now().isoformat(timespec="seconds")
            self._tasks[t.task_id] = t
            self._order.append(t.task_id)
        self._save(force=True)

    def _save(self, force: bool = False) -> None:
        """落盘（节流 0.5s，避免每页都写一次文件）。"""
        now = time.time()
        if not force and now - self._last_save < 0.5:
            return
        self._last_save = now
        keep = self._order[-MAX_TASKS_KEPT:]
        payload = {"tasks": [self._tasks[i].to_dict(with_pages=True) for i in keep
                             if i in self._tasks]}
        tmp = self.path.with_suffix(".tmp")
        try:
            tmp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            tmp.replace(self.path)
        except Exception:  # noqa: BLE001 - 落盘失败不影响主流程
            pass

    # ── 对外接口 ──
    def submit(self, tmp_path: Path, filename: str, size_bytes: int,
               dpi: int | None = None) -> Task:
        task = Task(
            task_id=uuid.uuid4().hex[:12],
            filename=filename,
            size_bytes=size_bytes,
            created_at=datetime.now().isoformat(timespec="seconds"),
            tmp_path=str(tmp_path),
            stage_key="receive",
            stage=STAGE_LABEL["receive"],
        )
        with self._lock:
            self._tasks[task.task_id] = task
            self._order.append(task.task_id)
        self._save(force=True)
        self._pool.submit(self._run, task.task_id, dpi)
        return task

    def list(self) -> list[dict]:
        """任务列表（最近在前，不含页级明细）。"""
        items = [self._tasks[i] for i in self._order if i in self._tasks]
        items.sort(key=lambda t: t.created_at, reverse=True)
        return [t.to_dict(with_pages=False) for t in items]

    def get(self, task_id: str) -> Task | None:
        return self._tasks.get(task_id)

    def cancel(self, task_id: str) -> bool:
        task = self._tasks.get(task_id)
        if task is None:
            return False
        if task.status in {"DONE", "FAILED", "CANCELLED"}:
            return False
        task.cancel_requested = True
        if task.status == "QUEUED":
            task.status = "CANCELLED"
            task.stage = "已取消"
            task.finished_at = datetime.now().isoformat(timespec="seconds")
            self._cleanup_file(task)
        self._save(force=True)
        return True

    def remove(self, task_id: str) -> bool:
        """从列表移除一条**已结束**的任务（不删已入库的文档）。"""
        task = self._tasks.get(task_id)
        if task is None or task.status in {"QUEUED", "PARSING"}:
            return False
        with self._lock:
            self._tasks.pop(task_id, None)
            if task_id in self._order:
                self._order.remove(task_id)
        self._save(force=True)
        return True

    # ── 执行 ──
    def _run(self, task_id: str, dpi: int | None) -> None:
        task = self._tasks.get(task_id)
        if task is None or task.status == "CANCELLED":
            return
        task.status = "PARSING"
        task.started_at = datetime.now().isoformat(timespec="seconds")
        tmp = Path(task.tmp_path)
        started = time.perf_counter()

        def on_progress(stage: str, done: int, total: int) -> None:
            if task.cancel_requested:
                raise TaskCancelled("用户取消")
            task.stage_key = stage
            task.stage = STAGE_LABEL.get(stage, stage)
            task.stage_done, task.stage_total = done, total
            self._save()

        try:
            doc = document.read_document(tmp, dpi=dpi, on_progress=on_progress)
            if doc.error:
                raise RuntimeError(doc.error)
            if not doc.text.strip():
                raise RuntimeError(
                    "未从文件中提取到任何文本。若为扫描件，可能是清晰度过低；"
                    "可提高 OCR 平台 DPI 或改用本地引擎后重试。")

            task.stage_key, task.stage = "store", STAGE_LABEL["store"]
            task.provider = doc.provider or doc.engine
            saved = store.save(
                filename=task.filename,
                doc=doc,
                size_bytes=task.size_bytes or (tmp.stat().st_size if tmp.is_file() else 0),
            )
            task.doc_id = saved.doc_id
            task.summary = doc.summary()
            task.stages = doc.stages
            task.checks = doc.checks
            task.notes = list(doc.notes)
            task.pages = [p.quality() for p in doc.pages]
            task.status = "DONE"
            task.stage_key, task.stage = "done", STAGE_LABEL["done"]
            task.stage_done = task.stage_total = doc.page_count
        except TaskCancelled:
            task.status = "CANCELLED"
            task.stage = "已取消"
            task.error = "已被用户取消"
        except Exception as exc:  # noqa: BLE001 - 任何异常都要落到任务状态上
            task.status = "FAILED"
            task.error = f"{type(exc).__name__}: {exc}"
        finally:
            task.finished_at = datetime.now().isoformat(timespec="seconds")
            task.tmp_path = ""
            self._cleanup_file(tmp)
            self._save(force=True)

    @staticmethod
    def _cleanup_file(path: Path) -> None:
        try:
            path.unlink(missing_ok=True)
        except Exception:  # noqa: BLE001
            pass


tasks = TaskManager()
