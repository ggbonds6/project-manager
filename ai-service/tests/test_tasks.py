"""`tasks.py` 任务状态机的回归测试（**离线**：不解析 PDF、不调 OCR、不起真任务线程）。

`TaskManager` 的线程池不是测试重点，"状态怎么迁移、什么状态可取消/可移除、
重启后残留任务怎么处理"才是——这些逻辑出问题的症状是前端一直转圈或任务卡住不动。

做法：
- `_run` 换成空实现后再 `submit`：任务留在 QUEUED，不真跑任何东西；
- 需要走完整状态机时，抓出**真正的** `_run` 在测试线程里同步调一次，
  它依赖的 `document.read_document` 与 `store.save` 都换成假实现。

⚠️ import `pm_ai.tasks` 会在模块级构造全局 `TaskManager`（读/回写 `work/tasks.json`）。
`tests/conftest.py` 已把 `WORK_DIR` 指到临时目录，所以不会动仓库里那份真实记录。
"""

from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from pm_ai import document
from pm_ai import tasks as tasks_mod


class _FakeStore:
    """假文档库：`_run` 成功路径只用到 `save()` 的 doc_id。"""

    def save(self, filename, doc, size_bytes=0):
        return SimpleNamespace(doc_id="doc-1")


def _noop_run(self, task_id, dpi):
    """替身 `_run`：让 `submit` 登记完就结束，绝不真跑解析。"""
    return None


@pytest.fixture()
def real_run():
    """真正的 `_run`（在 monkeypatch 之前抓到），供测试同步调用。"""
    return tasks_mod.TaskManager._run


@pytest.fixture()
def manager(tmp_path, monkeypatch, real_run):
    """一个落盘在 tmp_path、且不会真跑任务的 TaskManager。"""
    monkeypatch.setattr(tasks_mod.TaskManager, "_run", _noop_run)
    mgr = tasks_mod.TaskManager(path=tmp_path / "tasks.json", pool_size=1)
    try:
        yield mgr
    finally:
        mgr._pool.shutdown(wait=False)


# ══════════════════════════════════════════════════════════════════
# 登记 / 查询
# ══════════════════════════════════════════════════════════════════


def test_submit_is_visible_in_list_and_get(manager, tmp_path) -> None:
    """挡的是：提交即返回之后，任务却查不到 / 列表里没有。

    "提交即返回"是这一层的核心承诺（前端拿到 task_id 就靠它轮询进度）。
    """
    task = manager.submit(tmp_path / "a.pdf", "a.pdf", 123)
    assert task.status == "QUEUED"
    assert task.stage_key == "receive"
    assert manager.get(task.task_id) is task
    assert manager.get("no-such-task") is None

    listed = manager.list()
    assert [t["task_id"] for t in listed] == [task.task_id]
    # 列表接口不带页级明细（否则刷新一次列表就拖着全文 JSON）
    assert "pages" not in listed[0]
    # 还没开始：进度 0、耗时 0（不能算出个负数或 NULL 让前端显示成 NaN）
    assert listed[0]["percent"] == 0.0
    assert task.elapsed == 0.0


# ══════════════════════════════════════════════════════════════════
# 取消 / 移除
# ══════════════════════════════════════════════════════════════════


def test_cancel_queued_then_remove(manager, tmp_path) -> None:
    """挡的是：取消一个排队中的任务没生效、或已结束的任务还能被"取消"。

    排队中的任务可以立刻置 CANCELLED（还没轮到它跑）；已经结束的任务再取消
    必须返回 False，否则前端会以为"取消成功"而把状态改回去。
    """
    task = manager.submit(tmp_path / "b.pdf", "b.pdf", 1)
    assert manager.cancel(task.task_id) is True
    assert task.cancel_requested is True
    assert task.status == "CANCELLED"
    assert task.stage == "已取消"
    assert task.finished_at, "取消后必须落结束时间，否则前端 elapsed 会一直往上涨"

    assert manager.cancel(task.task_id) is False
    assert manager.cancel("no-such-task") is False

    # 已结束的任务可以从列表移除（不删已入库的文档）
    assert manager.remove(task.task_id) is True
    assert manager.get(task.task_id) is None
    assert manager.list() == []


def test_remove_refuses_unfinished_task(manager, tmp_path) -> None:
    """挡的是：把还在队列/解析中的任务从列表删掉——它就永远消失了，没人知道结果。

    实现口径：QUEUED / PARSING 一律 `remove()` 返回 False（要停就先 `cancel()`）。
    """
    task = manager.submit(tmp_path / "c.pdf", "c.pdf", 1)
    assert manager.remove(task.task_id) is False
    assert manager.remove("no-such-task") is False
    assert manager.get(task.task_id) is not None


# ══════════════════════════════════════════════════════════════════
# 状态机：QUEUED → PARSING → DONE / FAILED / CANCELLED
# ══════════════════════════════════════════════════════════════════


def test_run_state_machine_reaches_done(manager, real_run, tmp_path, monkeypatch) -> None:
    """挡的是：进度加权、阶段名、以及成功路径的收尾（状态/耗时/暂存清理）。

    进度按阶段加权（渲染 5%~20%、识别 20%~92%），只报"页数"会让渲染阶段看起来像卡住。
    这里在假解析器里上报两次进度，顺便验证 `percent` 落在预期档位上。
    """
    monkeypatch.setattr(tasks_mod, "store", _FakeStore())
    task = manager.submit(tmp_path / "d.pdf", "d.pdf", 10)
    seen: list[tuple[str, float]] = []

    def fake_read_document(path, dpi=None, on_progress=None):
        assert manager.get(task.task_id).status == "PARSING", "进解析器前状态必须已是 PARSING"
        if on_progress:
            on_progress("render", 1, 2)
            seen.append((manager.get(task.task_id).stage, manager.get(task.task_id).percent))
            on_progress("ocr", 5, 10)
            seen.append((manager.get(task.task_id).stage, manager.get(task.task_id).percent))
        doc = document.DocumentText(kind="text_pdf", engine="text-layer", provider="text-layer")
        doc.pages = [
            document.PageText(page_no=1, text="合同总价 100 元", source="text-layer"),
            document.PageText(page_no=2, text="第二页", source="text-layer"),
        ]
        return doc

    monkeypatch.setattr(tasks_mod.document, "read_document", fake_read_document)
    real_run(manager, task.task_id, None)

    # 渲染走完一半：(0.05 + (0.20-0.05)*0.5)*100 = 12.5
    # 识别走完一半：(0.20 + (0.97-0.20)*0.5)*100 = 58.5
    # （识别段终点是 0.97 而不是 0.92：本地引擎兜底阶段已于 2026-09-18 移除，
    #   它原先占的 0.92~0.97 归还给识别阶段，见 tasks._STAGE_RANGE）
    assert seen == [("渲染页面", 12.5), ("识别中", 58.5)]

    assert task.status == "DONE"
    assert task.stage_key == "done"
    assert task.stage == "完成"
    assert task.percent == 100.0, "DONE 恒为 100，不再看 stage_done/stage_total"
    assert task.provider == "text-layer"
    assert task.doc_id == "doc-1"
    assert task.summary["pages"] == 2
    assert len(task.pages) == 2
    assert task.finished_at
    assert task.tmp_path == "", "结束后必须清掉暂存路径"


def test_run_failure_lands_on_task_status(manager, real_run, tmp_path, monkeypatch) -> None:
    """挡的是：解析抛异常后任务卡在 PARSING（前端无限轮询）。

    任何异常都必须落到任务状态上（FAILED + error 原文），这也是 `_run` 里
    那个 `except Exception` 存在的唯一理由。
    """
    monkeypatch.setattr(tasks_mod, "store", _FakeStore())
    task = manager.submit(tmp_path / "e.pdf", "e.pdf", 1)

    def boom(path, dpi=None, on_progress=None):
        raise RuntimeError("PDF 解析失败：文件损坏")

    monkeypatch.setattr(tasks_mod.document, "read_document", boom)
    real_run(manager, task.task_id, None)

    assert task.status == "FAILED"
    assert task.error == "RuntimeError: PDF 解析失败：文件损坏"
    assert task.finished_at
    assert task.tmp_path == ""


def test_run_rejects_document_without_text(manager, real_run, tmp_path, monkeypatch) -> None:
    """挡的是：一个字都没提取到却算成功入库。

    空白文档（清晰度过低 / 平台识别失败）必须报错并给出可操作提示，
    否则文档库里会多出一堆"看着成功、实际没内容"的记录。
    """
    monkeypatch.setattr(tasks_mod, "store", _FakeStore())
    task = manager.submit(tmp_path / "f.pdf", "f.pdf", 1)

    def empty_doc(path, dpi=None, on_progress=None):
        return document.DocumentText(
            kind="scanned", pages=[document.PageText(page_no=1, text="   ")]
        )

    monkeypatch.setattr(tasks_mod.document, "read_document", empty_doc)
    real_run(manager, task.task_id, None)

    assert task.status == "FAILED"
    assert "未从文件中提取到任何文本" in task.error


def test_run_honours_cancel_request(manager, real_run, tmp_path, monkeypatch) -> None:
    """挡的是：取消标记没人看——请求发出去了没法中断，但下一批开始前必须检查。

    进度回调是唯一的检查点：一旦 `cancel_requested`，回调抛 `TaskCancelled`，
    任务落到 CANCELLED 而不是带着半截结果继续跑完。
    """
    monkeypatch.setattr(tasks_mod, "store", _FakeStore())
    task = manager.submit(tmp_path / "g.pdf", "g.pdf", 1)
    task.cancel_requested = True

    def with_progress(path, dpi=None, on_progress=None):
        on_progress("ocr", 1, 2)  # 这里应当抛 TaskCancelled
        raise AssertionError("取消标记必须先于解析结果生效")

    monkeypatch.setattr(tasks_mod.document, "read_document", with_progress)
    real_run(manager, task.task_id, None)

    assert task.status == "CANCELLED"
    assert task.stage == "已取消"
    assert task.error == "已被用户取消"
    assert task.tmp_path == ""


# ══════════════════════════════════════════════════════════════════
# 落盘 / 重启
# ══════════════════════════════════════════════════════════════════


def test_restart_marks_interrupted_tasks_failed(tmp_path, monkeypatch) -> None:
    """挡的是：服务重启后残留的 QUEUED/PARSING 任务还在"进行中"。

    执行线程已经没了，留着只会让前端无限轮询。启动时（`_load`）要标成 FAILED 并写明原因。
    顺带钉住 `list()` 的排序：`created_at` 倒序，最近的在最前。
    """
    monkeypatch.setattr(tasks_mod.TaskManager, "_run", _noop_run)
    path = tmp_path / "tasks.json"
    interrupted = tasks_mod.Task(
        task_id="t-parsing", filename="a.pdf", status="PARSING", created_at="2026-09-18T10:00:00"
    )
    queued = tasks_mod.Task(
        task_id="t-queued", filename="b.pdf", status="QUEUED", created_at="2026-09-18T09:00:00"
    )
    finished = tasks_mod.Task(
        task_id="t-done",
        filename="c.pdf",
        status="DONE",
        created_at="2026-09-18T11:00:00",
        finished_at="2026-09-18T11:00:05",
    )
    path.write_text(
        json.dumps(
            {"tasks": [interrupted.to_dict(), queued.to_dict(), finished.to_dict()]},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    mgr = tasks_mod.TaskManager(path=path, pool_size=1)
    try:
        assert mgr.get("t-parsing").status == "FAILED"
        assert "服务重启" in mgr.get("t-parsing").error
        assert mgr.get("t-parsing").finished_at
        assert mgr.get("t-queued").status == "FAILED"
        assert mgr.get("t-done").status == "DONE", "已结束的任务不许被重启逻辑改写"
        assert [t["task_id"] for t in mgr.list()] == ["t-done", "t-parsing", "t-queued"]
    finally:
        mgr._pool.shutdown(wait=False)


def test_persistence_keeps_only_recent_100(tmp_path, monkeypatch) -> None:
    """挡的是：`work/tasks.json` 无限增长（每次解析都写全文，几年后会拖慢启动）。

    落盘只保留最后入队的 `MAX_TASKS_KEPT` 条；内存里仍然全都有。
    """
    monkeypatch.setattr(tasks_mod.TaskManager, "_run", _noop_run)
    path = tmp_path / "tasks.json"
    items = []
    for i in range(105):
        hour, minute = divmod(i, 60)
        stamp = f"2026-09-18T{hour:02d}:{minute:02d}:00"
        items.append(
            tasks_mod.Task(
                task_id=f"t{i:03d}",
                filename=f"f{i}.pdf",
                status="DONE",
                created_at=stamp,
                finished_at=stamp,
            ).to_dict()
        )
    path.write_text(json.dumps({"tasks": items}, ensure_ascii=False), encoding="utf-8")

    mgr = tasks_mod.TaskManager(path=path, pool_size=1)
    try:
        saved = json.loads(path.read_text(encoding="utf-8"))["tasks"]
        assert len(saved) == tasks_mod.MAX_TASKS_KEPT == 100
        kept = {t["task_id"] for t in saved}
        assert "t104" in kept, "最近的任务必须留下"
        assert "t000" not in kept, "最旧的 5 条应被裁掉"
        assert mgr.get("t000") is not None, "内存里仍保留全部任务，只是不再落盘"
    finally:
        mgr._pool.shutdown(wait=False)
