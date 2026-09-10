#!/usr/bin/env bash
# ============================================================
# PM 服务器「安装/升级」脚本（面向 Linux + Docker Compose）
# 适用：双机 ARM 独立部署（见 docs/双机ARM服务器独立部署方案.md）
#
# 设计原则：不硬编码任何仓库/下载链接——镜像包由外部准备好
# （内网 Gitea Release 下载 / 内网代理 / 人工拷贝均可），本脚本做：
#   1) 校验运行工程目录与 .env
#   2) docker load 预构建镜像
#   3) 自动把 .env 的 IMAGE_TAG 切到本次镜像版本（备份原 .env）
#   4) docker compose up -d（检测到 image 变化会自动重建容器）
#
# 用法：
#   ./pm-upgrade.sh <镜像tar包> [app目录]
#   例：./pm-upgrade.sh /home/lhim/pm/releases/pm-images-aarch64-v3.2.0.tar.gz
#     （缺省 app 目录 = $HOME/pm/app，即只含 docker-compose.yml + .env 的运行目录；
#       其次 $HOME/pm/project-manager/deploy/docker；最后脚本同级 ../deploy/docker）
#
# 版本号来源：优先按发布包命名规范 pm-images-<arch>-<版本>.tar.gz 解析；
#            解析不出时可用环境变量 VER=vX.Y.Z 显式指定，否则跳过切版本并提示。
# ============================================================
set -euo pipefail

IMG_TAR="${1:?用法: pm-upgrade.sh <镜像tar包> [app目录]}"
APP_DIR="${2:-}"

# 定位运行工程目录（含 docker-compose.yml 与 .env）
find_app_dir() {
  local cand=""
  if [ -n "$APP_DIR" ]; then
    cand="$APP_DIR"
  else
    local c
    for c in "${HOME}/pm/app" "${HOME}/pm/project-manager/deploy/docker"; do
      if [ -f "$c/docker-compose.yml" ]; then cand="$c"; break; fi
    done
  fi
  if [ -z "$cand" ] || [ ! -f "$cand/docker-compose.yml" ]; then
    # 回退：脚本仓库内 deploy/docker
    local here
    here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    if [ -f "$here/deploy/docker/docker-compose.yml" ]; then
      cand="$here/deploy/docker"
    fi
  fi
  echo "$cand"
}

# 从镜像包名解析版本：pm-images-<arch>-<版本>.tar.gz → <版本>
parse_version() {
  local base
  base="$(basename "$1")"
  if [[ "$base" =~ ^pm-images-[^-]+-(.+)\.tar\.gz$ ]]; then
    echo "${BASH_REMATCH[1]}"
  fi
}

if [ ! -f "$IMG_TAR" ]; then
  echo "[FAIL] 找不到镜像包: $IMG_TAR" >&2
  exit 1
fi

APP_DIR="$(find_app_dir)"
if [ ! -f "$APP_DIR/docker-compose.yml" ]; then
  echo "[FAIL] 未找到运行工程 docker-compose.yml（app 目录: $APP_DIR）" >&2
  echo "       请先确认发布包的 docker-compose.yml 已放到该目录。" >&2
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

echo "[1/4] docker load 镜像包: $IMG_TAR"
docker load -i "$IMG_TAR"

echo "[2/4] 切换镜像版本（.env 的 IMAGE_TAG）"
VER="${VER:-$(parse_version "$IMG_TAR")}"
if [ -z "$VER" ]; then
  echo "[WARN] 无法从文件名解析版本（期望形如 pm-images-aarch64-v3.2.0.tar.gz）。"
  echo "       本次不修改 IMAGE_TAG，将按当前 .env 的值启动。"
  echo "       如需切换，请手工编辑: vi $APP_DIR/.env"
elif docker image inspect "pm-backend:${VER}" >/dev/null 2>&1; then
  OLD_VER="$(grep -E '^IMAGE_TAG=' "$APP_DIR/.env" | head -1 | cut -d= -f2- || true)"
  if [ "$OLD_VER" = "$VER" ]; then
    echo "       IMAGE_TAG 已是 $VER，无需修改"
  else
    cp "$APP_DIR/.env" "${APP_DIR}/.env.bak"
    if grep -q '^IMAGE_TAG=' "$APP_DIR/.env"; then
      sed -i.bak "s|^IMAGE_TAG=.*|IMAGE_TAG=${VER}|" "$APP_DIR/.env"
    else
      echo "IMAGE_TAG=${VER}" >> "$APP_DIR/.env"
    fi
    rm -f "${APP_DIR}/.env.bak.bak"
    echo "       IMAGE_TAG: ${OLD_VER:-<无>} → ${VER}（原 .env 已备份为 .env.bak）"
  fi
else
  echo "[WARN] 镜像包内未发现 pm-backend:${VER}，不改动 IMAGE_TAG。"
fi

echo "[3/4] compose 校验"
docker compose -f "$APP_DIR/docker-compose.yml" config --quiet

echo "[4/4] 启动/升级服务（image 变化时 compose 会自动重建容器）"
docker compose -f "$APP_DIR/docker-compose.yml" up -d

echo
PORT="$(grep -E '^WEB_PORT=' "$APP_DIR/.env" | head -1 | cut -d= -f2 || true)"
echo "[OK] 完成。健康检查: curl http://127.0.0.1:${PORT:-8080}/api/health"
docker compose -f "$APP_DIR/docker-compose.yml" ps
