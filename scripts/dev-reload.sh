#!/usr/bin/env bash
# ============================================================
# 开发机：改完代码后一键「重建镜像 + 重启」，本机自测用
#
# 用法（在仓库根或任意位置执行均可）：
#   bash scripts/dev-reload.sh              # 重建前后端并重启（默认）
#   bash scripts/dev-reload.sh backend      # 只重建后端（改了 Java / SQL 迁移）
#   bash scripts/dev-reload.sh frontend     # 只重建前端（改了 tsx / ts / css）
#
# 与正式发版的区别：
#   - 本脚本面向**开发机本机**：用 deploy/docker/docker-compose.yml（含 build 段），
#     走 amd64 本机构建，快；不产 arm64、不生成发布包、不动 releases/。
#   - 服务器发版仍用：scripts/make-release.sh（开发机）+ scripts/pm-upgrade.sh（服务器）。
#
# 说明：
#   - 镜像 tag 取 deploy/docker/.env 的 IMAGE_TAG（本机默认 latest）；
#   - compose 检测到镜像变化会自动重建并重启对应容器，无需手动 stop；
#   - 脚本最后会轮询 /api/health，就绪后打印访问地址。
# ============================================================
set -euo pipefail

TARGET="${1:-all}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$REPO_ROOT/deploy/docker"

if [ ! -f "$COMPOSE_DIR/docker-compose.yml" ]; then
  echo "[FAIL] 未找到 $COMPOSE_DIR/docker-compose.yml" >&2
  exit 1
fi
if [ ! -f "$COMPOSE_DIR/.env" ]; then
  echo "[FAIL] 未找到 $COMPOSE_DIR/.env（本机测试配置），请先按 docs/部署与发布全流程手册.md §1.3 准备" >&2
  exit 1
fi

case "$TARGET" in
  all)      SERVICES="backend frontend" ;;
  backend)  SERVICES="backend" ;;
  frontend) SERVICES="frontend" ;;
  *)
    echo "[FAIL] 参数只能是 all | backend | frontend（当前：$TARGET）" >&2
    exit 2
    ;;
esac

cd "$COMPOSE_DIR"

PORT="$(grep -E '^WEB_PORT=' .env | head -1 | cut -d= -f2- || true)"
PORT="${PORT:-8080}"

echo "──────────────────────────────────────────────"
echo " 目标：$SERVICES    端口：$PORT"
echo "──────────────────────────────────────────────"

echo "[1/3] 重建镜像（改了依赖/首次构建会较慢，请耐心）"
docker compose build $SERVICES

echo
echo "[2/3] 重建并启动容器（镜像变化时 compose 会自动重建）"
docker compose up -d $SERVICES

echo
echo "[3/3] 等待后端就绪…"
READY=0
for _ in $(seq 1 60); do
  if curl -sf -m 3 "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 3
done

echo
if [ "$READY" = "1" ]; then
  echo "✅ 已就绪：http://127.0.0.1:${PORT}/    （默认账号 admin / 123456）"
else
  echo "⚠️  等待超时（后端可能仍在启动或启动失败）。排查："
  echo "     cd $COMPOSE_DIR && docker compose logs --tail 80 backend"
fi

echo
docker compose ps
