#!/bin/sh
# ─────────────────────────────────────────────────────────────────
# 容器内执行的质量门步骤：ruff check + pytest
#
# 被 scripts/check.sh 调用；也可以直接手动跑（不依赖 Git Bash）：
#   docker run --rm -v "<ai-service 绝对路径>":/src:ro pm-ai-service:local \
#     sh /src/scripts/qa-in-container.sh
#   docker run --rm -v ...:/src:ro pm-ai-service:local \
#     sh /src/scripts/qa-in-container.sh -k calculate -x     # 额外参数转给 pytest
#
# 为什么先拷到 /tmp/qa 再跑：
#   源码是**只读挂载**（容器动不了你的文件），而 `pip install -e .` 会写 egg-info、
#   pytest 会写 .pytest_cache、服务 import 时会建 work/ 目录 —— 拷到容器内可写区再跑，
#   这些产物就都留在一次性的容器里，不会落到工作区。
#
# 刻意用 POSIX sh 而不是 bash：Windows 上的 Git Bash 不一定在 PATH 里
# （`bash` 很可能指向 WSL 的桩），用 `sh` 才能保证到处都能跑。
# ─────────────────────────────────────────────────────────────────
set -e

rm -rf /tmp/qa
mkdir -p /tmp/qa
cp -a /src/. /tmp/qa/
cd /tmp/qa

# 宿主机带过来的 __pycache__（解释器版本不同）无用，清掉免得排查时被误导
find . -name __pycache__ -type d -prune -exec rm -rf {} +

echo "── 安装依赖（.[dev]：pytest + ruff）──"
pip install --no-input -q -e ".[dev]"

# 打印 pm_ai 的来源：确认测的是**挂进来的这份源码**，而不是镜像里那份旧快照
python -c "import pm_ai; print('pm_ai 来源：', pm_ai.__file__)"

echo "── ruff check . ──"
ruff check .

echo "── python -m pytest ──"
python -m pytest "$@"
