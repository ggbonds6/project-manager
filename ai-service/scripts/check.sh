#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# PM AI Service · 质量门（ruff + pytest）
#
# 用法（在仓库任意位置执行，脚本会自己定位 ai-service/）：
#   bash scripts/check.sh                        # 全量：ruff check + format --check + pytest
#   bash scripts/check.sh tests/test_tools.py    # 只跑某个文件（多余参数原样转给 pytest）
#   bash scripts/check.sh -k calculate -x        # 任意 pytest 参数
#   PY=/path/to/python bash scripts/check.sh     # 指定解释器
#
# 解释器查找顺序（**本地优先**，快；找不到才退回 Docker）：
#   1) 环境变量 PY
#   2) E:/env/venvs/pm-ai/Scripts/python.exe   ← 本机约定：Python 统一装在 E:\env
#   3) .venv/Scripts/python.exe / .venv/bin/python（仓库内虚拟环境）
#   4) PATH 上的 python3 / python
#   5) 都没有 → 用 Docker（镜像 pm-ai-service:local）
#
# 为什么默认不再走 Docker：容器要拉镜像、装依赖、拷源码，一轮十几秒起步；
# 本机装了 Python 后同样的检查是**秒级**。Docker 只在"本机确实没有解释器"时才用。
#
# 首次在本机准备环境（一次性，装在 E:\env 统一管理）：
#   E:\env\python-3.11.9\python.exe -m venv E:\env\venvs\pm-ai
#   E:\env\venvs\pm-ai\Scripts\python.exe -m pip install -e ".[dev]"
#
# Windows 上若没有 Git Bash，用原生入口：scripts\check.cmd（逻辑与本文一致）
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

# Git Bash（MSYS2）会把 /src、/tmp/qa 这类**容器内路径**误转成 Windows 路径。
# 关掉转换；Linux/macOS 上这两行无副作用。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.."   # → ai-service/
ROOT="$(pwd)"
IMAGE="${IMAGE:-pm-ai-service:local}"

c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_warn() { printf '\033[33m%s\033[0m\n' "$*"; }
c_err()  { printf '\033[31m%s\033[0m\n' "$*"; }

# ── 找本机解释器（Windows 上是 python.exe，POSIX 上是 python）──
find_python() {
  if [[ -n "${PY:-}" ]]; then printf '%s' "$PY"; return 0; fi
  for cand in \
    "E:/env/venvs/pm-ai/Scripts/python.exe" \
    ".venv/Scripts/python.exe" \
    ".venv/bin/python" \
    "$(command -v python3 2>/dev/null || true)" \
    "$(command -v python 2>/dev/null || true)"
  do
    if [[ -n "$cand" && -x "$cand" ]]; then printf '%s' "$cand"; return 0; fi
  done
  return 1
}

if PY_BIN="$(find_python)"; then
  echo "── 质量门：本机解释器 ${PY_BIN} ──"
  echo
  "$PY_BIN" -m ruff check .
  "$PY_BIN" -m ruff format . --check
  "$PY_BIN" -m pytest "$@"
  echo
  c_ok "质量门通过：ruff check + ruff format --check + pytest"
  exit 0
fi

# ── 兜底：没有本机解释器时用 Docker ──────────────────────────────
c_warn "本机没找到 Python 解释器，退回 Docker（见脚本头部说明如何准备本机环境）"
echo

if ! command -v docker >/dev/null 2>&1; then
  c_err "既没有 Python，也没有 docker。"
  echo "  任选其一：① 装 Python 后 pip install -e \".[dev]\"；② 装 Docker 并 bash scripts/docker-verify.sh up"
  exit 1
fi

if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
  c_err "本机没有镜像 ${IMAGE}（或 Docker 守护进程没起来）。"
  echo "  先构建：bash scripts/docker-verify.sh up    # 或 docker compose build"
  exit 1
fi

# Docker Desktop 只认 Windows 路径（C:/...），Git Bash 的 pwd 给的是 /c/...，用 cygpath 转
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
HOST_ROOT="$(host_path "${ROOT}")"

# 容器里执行的动作统一放在 scripts/qa-in-container.sh（POSIX sh）。
# 放成独立文件而不是一长串引号，是为了**不依赖 bash**：
# Windows 上 `bash` 很可能指向 WSL 的桩，而 `sh /src/scripts/qa-in-container.sh` 到处都能跑。
QA_CMD='sh /src/scripts/qa-in-container.sh "$@"'

echo "── 质量门：镜像 ${IMAGE} ｜ 源码 ${HOST_ROOT}（只读挂载）──"
echo

if docker run --rm \
  -e PY_COLORS=1 \
  -e FORCE_COLOR=1 \
  -v "${HOST_ROOT}":/src:ro \
  "${IMAGE}" \
  sh -c "${QA_CMD}" pm-ai-qa "$@"; then
  echo
  c_ok "质量门通过（容器内 ruff check + pytest）"
else
  echo
  c_err "质量门未通过（上面是容器里的原始输出）"
  exit 1
fi
