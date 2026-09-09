#!/usr/bin/env bash
# ============================================================
# PM 服务器「安装/升级」脚本（面向 Linux + Docker Compose）
# 适用：双机 ARM 独立部署（见 docs/双机ARM服务器独立部署方案.md §3）
#
# 设计原则：不硬编码任何仓库/下载链接——镜像包由外部准备好
# （GitHub Release 下载 / 内网代理 / 人工拷贝均可），本脚本只做：
#   1) 校验运行工程目录与 .env
#   2) docker load 预构建镜像
#   3) docker compose up -d（版本化镜像 tag 由 .env 或 compose 决定）
#
# 用法：
#   ./pm-upgrade.sh <镜像tar包> [app目录]
#   例：./pm-upgrade.sh pm-v1.0.0-arm64-images.tar.gz ~/pm/app
#     （缺省 app 目录 = $HOME/pm/app，其次脚本同级的 ../deploy/docker）
# ============================================================
set -euo pipefail

IMG_TAR="${1:?用法: pm-upgrade.sh <镜像tar包> [app目录]}"
APP_DIR="${2:-}"

# 定位运行工程目录（含 docker-compose.yml）
find_app_dir() {
  local cand
  if [ -n "$APP_DIR" ]; then
    cand="$APP_DIR"
  else
    cand="${HOME}/pm/app"
  fi
  if [ ! -f "$cand/docker-compose.yml" ]; then
    # 回退：脚本仓库内 deploy/docker
    local here
    here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    if [ -f "$here/deploy/docker/docker-compose.yml" ]; then
      cand="$here/deploy/docker"
    fi
  fi
  echo "$cand"
}

if [ ! -f "$IMG_TAR" ]; then
  echo "[FAIL] 找不到镜像包: $IMG_TAR" >&2
  exit 1
fi

APP_DIR="$(find_app_dir)"
if [ ! -f "$APP_DIR/docker-compose.yml" ]; then
  echo "[FAIL] 未找到运行工程 docker-compose.yml（app 目录: $APP_DIR）" >&2
  echo "       请先解压 pm-*-deploy.tar.gz 并确认 compose 文件位置。" >&2
  exit 1
fi

if [ ! -f "$APP_DIR/.env" ]; then
  echo "[WARN] 未发现 $APP_DIR/.env"
  if [ -f "$APP_DIR/.env.example" ]; then
    cp "$APP_DIR/.env.example" "$APP_DIR/.env"
    echo "      已从 .env.example 生成 .env —— 请编辑填写后重新执行本脚本："
    echo "        vi $APP_DIR/.env   (YASHAN_PASSWORD / JWT_SECRET 必须填写；两机 JWT_SECRET 必须一致)"
  else
    echo "[FAIL] 无 .env.example，请手工准备 .env" >&2
  fi
  exit 1
fi

echo "[1/3] docker load 镜像包: $IMG_TAR"
docker load -i "$IMG_TAR"

echo "[2/3] compose 校验"
docker compose -f "$APP_DIR/docker-compose.yml" config --quiet

echo "[3/3] 启动/升级服务"
docker compose -f "$APP_DIR/docker-compose.yml" up -d

echo
echo "[OK] 完成。健康检查: curl http://127.0.0.1:$(grep -E '^WEB_PORT=' "$APP_DIR/.env" | cut -d= -f2 || echo 8080)/api/health"
docker compose -f "$APP_DIR/docker-compose.yml" ps
