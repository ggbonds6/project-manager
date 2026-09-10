#!/usr/bin/env bash
# ============================================================
# PM 发布打包脚本（在开发机执行，产出可直接上传服务器的发布目录）
#
# 用法：
#   scripts/make-release.sh <版本> [平台]
#   例：scripts/make-release.sh v3.1.1              # 默认 linux/arm64（生产两台服务器）
#       scripts/make-release.sh v3.1.1 linux/amd64  # 如需 x86 产物
#
# 环境变量：
#   SKIP_BUILD=1   跳过构建，直接打包本机已存在的同版本镜像（快速重打包）
#   DOCKER=/path/to/docker  指定 docker 可执行文件（Windows Git Bash 下自动探测）
#
# 产出：dist/pm-release-<版本>/
#   ├─ pm-images-<arch>-<版本>.tar.gz    镜像包（docker load 用）
#   ├─ docker-compose.yml                运行编排（含 image 双模）
#   ├─ .env.example                      .env 模板（含 IMAGE_TAG 预填）
#   ├─ frontend-nginx.conf               前端 nginx 配置
#   ├─ pm-upgrade.sh                     服务器升级脚本（可选）
#   └─ 服务器部署步骤.txt                 照做即可
# ============================================================
set -euo pipefail

VER="${1:?用法: scripts/make-release.sh <版本> [平台]}"
PLATFORM="${2:-linux/arm64}"

case "$PLATFORM" in
  *arm64*|*aarch64*) ARCH=aarch64 ;;
  *amd64*|*x86_64*)  ARCH=x86_64 ;;
  *) echo "[FAIL] 不支持的平台: $PLATFORM（用 linux/arm64 或 linux/amd64）"; exit 2 ;;
esac

# 定位仓库根（脚本位于 scripts/ 下）
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# 定位 docker
DOCKER_BIN="${DOCKER:-}"
if [ -z "$DOCKER_BIN" ]; then
  if command -v docker >/dev/null 2>&1; then
    DOCKER_BIN=docker
  elif [ -x "${LOCALAPPDATA:-}/Programs/DockerDesktop/resources/bin/docker.exe" ]; then
    DOCKER_BIN="${LOCALAPPDATA}/Programs/DockerDesktop/resources/bin/docker.exe"
  else
    echo "[FAIL] 未找到 docker 可执行文件，请安装 Docker Desktop 或设置 DOCKER 环境变量"; exit 2
  fi
fi

OUT="dist/pm-release-${VER}"
mkdir -p "$OUT"

if [ "${SKIP_BUILD:-0}" = "1" ]; then
  echo "[1/5] 跳过构建（SKIP_BUILD=1），使用本机已有镜像 pm-backend:${VER} / pm-frontend:${VER}"
  "$DOCKER_BIN" image inspect "pm-backend:${VER}" >/dev/null 2>&1 || { echo "[FAIL] 本机不存在 pm-backend:${VER}"; exit 1; }
  "$DOCKER_BIN" image inspect "pm-frontend:${VER}" >/dev/null 2>&1 || { echo "[FAIL] 本机不存在 pm-frontend:${VER}"; exit 1; }
else
  echo "[1/5] 构建镜像（平台 $PLATFORM，QEMU 模拟构建较慢，请耐心）"
  "$DOCKER_BIN" buildx build --platform "$PLATFORM" --load -t "pm-backend:${VER}"  -f deploy/docker/Dockerfile.backend  .
  "$DOCKER_BIN" buildx build --platform "$PLATFORM" --load -t "pm-frontend:${VER}" -f deploy/docker/Dockerfile.frontend .
fi

echo "[2/5] 校验架构"
"$DOCKER_BIN" image inspect "pm-backend:${VER}" --format '  backend={{.Architecture}}/{{.Os}}'

echo "[3/5] 导出镜像包"
"$DOCKER_BIN" save "pm-backend:${VER}" "pm-frontend:${VER}" | gzip > "${OUT}/pm-images-${ARCH}-${VER}.tar.gz"
ls -lh "${OUT}/pm-images-${ARCH}-${VER}.tar.gz"

echo "[4/5] 复制部署文件"
cp deploy/docker/docker-compose.yml   "${OUT}/"
cp deploy/docker/.env.example         "${OUT}/"
cp deploy/docker/frontend-nginx.conf  "${OUT}/"
[ -f scripts/pm-upgrade.sh ] && cp scripts/pm-upgrade.sh "${OUT}/"
# .env.example 中 IMAGE_TAG 预填为本次版本，避免服务器漏配导致回退到 latest 而尝试 build
sed -i.bak "s/^IMAGE_TAG=.*/IMAGE_TAG=${VER}/" "${OUT}/.env.example" 2>/dev/null || \
  sed -i '' "s/^IMAGE_TAG=.*/IMAGE_TAG=${VER}/" "${OUT}/.env.example"
rm -f "${OUT}/.env.example.bak"

echo "[5/5] 生成服务器部署步骤说明"
cat > "${OUT}/服务器部署步骤.txt" <<TXT
PM ${VER} 服务器部署步骤（${ARCH}，离线：只 load 不 build）
=====================================================
1) 上传（开发机执行；两台服务器各一份）
   scp ${OUT}/pm-images-${ARCH}-${VER}.tar.gz  lhim@<服务器>:/home/lhim/pm/releases/
   scp ${OUT}/docker-compose.yml ${OUT}/.env.example  lhim@<服务器>:/home/lhim/pm/project-manager/deploy/docker/

2) 服务器配置 .env（放在 compose 同目录！）
   cd /home/lhim/pm/project-manager
   cp deploy/docker/.env.example deploy/docker/.env && vi deploy/docker/.env
   必填：YASHAN_PASSWORD / JWT_SECRET（两台相同）/ OBS 四项；确认 IMAGE_TAG=${VER}
   服务器附件统一 OBS：APP_STORAGE_TYPE=obs、APP_STORAGE_OBS_PREFIX=uploads

3) 加载镜像并启动（不加 --build）
   docker load -i /home/lhim/pm/releases/pm-images-${ARCH}-${VER}.tar.gz
   docker compose -f deploy/docker/docker-compose.yml up -d

4) 验收
   curl http://127.0.0.1:\${WEB_PORT:-8080}/api/health      # 期望 db:"up"
   浏览器登录 admin/123456 → 下载一个存量附件（应成功，读 OBS 桶 uploads/ 前缀）

5) 回滚（如需）
   改 deploy/docker/.env 的 IMAGE_TAG 为上一版本号 → docker compose -f deploy/docker/docker-compose.yml up -d
TXT

echo
echo "✅ 发布目录已生成: ${OUT}/"
ls -lh "${OUT}/"
echo
echo "上传命令模板（把 <服务器> 换成 pdmsappgh / ai-kingbase-gh）："
echo "  scp ${OUT}/pm-images-${ARCH}-${VER}.tar.gz lhim@<服务器>:/home/lhim/pm/releases/"
echo "  scp ${OUT}/docker-compose.yml ${OUT}/.env.example lhim@<服务器>:/home/lhim/pm/project-manager/deploy/docker/"
