"""pytest 公共前置。只做两件事，都是为了让测试**纯离线、不污染工作区**。

1. 把 `src/` 挂进 `sys.path`：容器里虽然 `pip install -e .` 过，但"直接跑 pytest"
   也该成立（同事在别的机器上 clone 下来就想跑）。
2. 把 `WORK_DIR` 指到临时目录：`pm_ai.tasks` 在 **import 那一刻**就构造了全局
   `TaskManager`（会读 `work/tasks.json` 并把残留的"进行中"任务标成失败后**回写**），
   `pm_ai.store` 也会建 `work/docs`。测试进程不该动仓库里那份真实记录，
   所以必须在 import pm_ai 之前就把目录改掉。
"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

# ⚠️ 必须在任何 `import pm_ai` 之前执行：settings 是模块级单例，import 时就定死了 work_dir。
# 用 setdefault：已经在环境里显式配了 WORK_DIR 的人（例如想固定看某个目录）不被覆盖。
os.environ.setdefault("WORK_DIR", tempfile.mkdtemp(prefix="pm-ai-pytest-"))
