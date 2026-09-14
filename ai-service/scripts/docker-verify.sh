#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# PM AI Service · Docker 验证一键脚本
#
# 用法（在仓库任意位置，脚本会自己定位 ai-service/）：
#   bash scripts/docker-verify.sh up          # 构建 + 启动 + 等就绪 + 自检
#   bash scripts/docker-verify.sh check       # 容器内跑环境自检（依赖/OCR/模型连通）
#   bash scripts/docker-verify.sh inventory   # 附件构成摸底（samples/ 目录）
#   bash scripts/docker-verify.sh ocr <文件>  # 单文件识别试（相对 /samples 的路径）
#   bash scripts/docker-verify.sh logs        # 跟踪日志
#   bash scripts/docker-verify.sh status      # 容器状态
#   bash scripts/docker-verify.sh shell       # 进容器（排查用）
#   bash scripts/docker-verify.sh down        # 停止并移除容器
#
# 依赖：docker（含 compose v2 插件）、curl
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

# Git Bash（MSYS2）会把 /samples、/app/work 这类**容器内路径**误转换成 Windows 路径，
# 导致容器里找不到文件。关掉转换；Linux/macOS 上这两行无副作用。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.."   # → ai-service/
ROOT="$(pwd)"

MODE="${1:-up}"
shift || true

if [[ -f .env ]]; then
  AI_PORT="$(grep -E '^AI_PORT=' .env | tail -1 | cut -d= -f2 | tr -d ' \r' || true)"
fi
AI_PORT="${AI_PORT:-8100}"
BASE_URL="http://127.0.0.1:${AI_PORT}"

c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_warn() { printf '\033[33m%s\033[0m\n' "$*"; }
c_err()  { printf '\033[31m%s\033[0m\n' "$*"; }

require_env() {
  if [[ ! -f .env ]]; then
    c_err "缺少 ai-service/.env"
    echo "  先执行：cp .env.example .env   然后填写 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL"
    exit 1
  fi
}

wait_ready() {
  echo "等待服务就绪（${BASE_URL}/health）…"
  for _ in $(seq 1 40); do
    if curl -fsS -m 5 "${BASE_URL}/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

case "$MODE" in
  up)
    require_env
    mkdir -p samples work
    echo "── 构建镜像（构建期会跑 OCR 冒烟自检，首次约 3~8 分钟）──"
    docker compose build
    echo
    echo "── 启动服务 ──"
    docker compose up -d
    echo
    if wait_ready; then
      c_ok "服务就绪：${BASE_URL}    接口文档：${BASE_URL}/docs"
      echo
      echo "── 容器内的环境自检 ──"
      docker compose exec -T ai-service python scripts/check_env.py || true
      echo
      echo "下一步："
      echo "  1) 把附件放进  ${ROOT}/samples/"
      echo "  2) bash scripts/docker-verify.sh inventory      # 摸底（不需模型）"
      echo "  3) bash scripts/docker-verify.sh ocr <文件名>    # 单份识别试"
    else
      c_err "服务未在 80 秒内就绪，看日志："
      docker compose logs --tail 60 ai-service
      exit 1
    fi
    ;;

  check)
    docker compose exec -T ai-service python scripts/check_env.py
    ;;

  inventory)
    docker compose exec -T ai-service python scripts/inventory.py /samples
    ;;

  ocr)
    if [[ $# -lt 1 ]]; then
      c_err "用法：bash scripts/docker-verify.sh ocr <文件名> [--save] [--dpi 400]"
      echo "  例：bash scripts/docker-verify.sh ocr 中标通知书.pdf --save"
      exit 1
    fi
    docker compose exec -T ai-service python scripts/ocr_try.py "/samples/$1" "${@:2}"
    ;;

  ocr-many)
    docker compose exec -T ai-service python scripts/batch_ocr.py /samples --out-dir /app/work/out "${@}"
    ;;

  make-samples)
    # 先落到容器可写区（/samples 是只读挂载），再拷回宿主机 samples/
    mkdir -p samples
    docker compose exec -T ai-service python scripts/make_samples.py /app/work/samples
    echo
    echo "── 复制到宿主机 samples/ ──"
    docker compose cp ai-service:/app/work/samples/. ./samples/
    ls -1 samples/*.pdf 2>/dev/null || true
    echo
    c_ok "合成样本就绪（虚构数据）。下一步：bash scripts/docker-verify.sh inventory"
    ;;

  logs)
    docker compose logs -f --tail 100 ai-service
    ;;

  status)
    docker compose ps
    echo
    curl -fsS -m 5 "${BASE_URL}/health" || c_warn "服务未响应"
    echo
    ;;

  shell)
    docker compose exec ai-service sh
    ;;

  down)
    docker compose down
    c_ok "已停止并移除容器（镜像与 work/ 保留）"
    ;;

  *)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
