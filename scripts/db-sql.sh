#!/usr/bin/env bash
# ============================================================
# 在开发机上直接对崖山 YashanDB 执行 SQL（排障 / 批量修数据 / 清演示数据）
#
# 用法：
#   bash scripts/db-sql.sh scripts/demo-reset.sql      # 清空演示数据
#   bash scripts/db-sql.sh my-query.sql                # 任意 SQL 文件（相对/绝对路径均可）
#
# 连接信息取值优先级（口令只在进程环境里传递，**不会被打印、不落盘**）：
#   1) 已导出的 DB_URL / DB_USER / DB_PASSWORD
#   2) 运行中的 pm-backend 容器环境变量（docker inspect，最可靠）
#   3) deploy/docker/.env 的 YASHAN_*（自行拼默认 URL）
#
# 为什么需要这个脚本：开发机通常没有 yasql 客户端；后端的迁移执行器只跑
# db/migration-yashan 下的脚本。手工查/改数据时用本脚本 + scripts/jdbc/RunSql.java 兜底
# （走 backend/lib 里入库的 JDBC 驱动，无需额外安装）。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."   # → 仓库根
ROOT="$(pwd)"
SQL_FILE="${1:-}"

if [[ -z "$SQL_FILE" ]]; then
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi
if [[ ! -f "$SQL_FILE" ]]; then
  echo "❌ 找不到 SQL 文件：$SQL_FILE" >&2
  exit 1
fi

DRIVER="$ROOT/backend/lib/yashandb-jdbc-1.9.3.jar"
RUNNER="$ROOT/scripts/jdbc/RunSql.java"
for f in "$DRIVER" "$RUNNER"; do
  [[ -f "$f" ]] || { echo "❌ 缺少文件：$f" >&2; exit 1; }
done

# ── 1/2) 从容器环境变量取（容器在跑时优先）──
if [[ -z "${DB_URL:-}" || -z "${DB_PASSWORD:-}" ]] && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'pm-backend'; then
  ENV_DUMP="$(docker inspect pm-backend --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null || true)"
  DB_URL="${DB_URL:-$(sed -n 's/^SPRING_DATASOURCE_URL=//p' <<<"$ENV_DUMP" | head -1)}"
  DB_USER="${DB_USER:-$(sed -n 's/^SPRING_DATASOURCE_USERNAME=//p' <<<"$ENV_DUMP" | head -1)}"
  DB_PASSWORD="${DB_PASSWORD:-$(sed -n 's/^SPRING_DATASOURCE_PASSWORD=//p' <<<"$ENV_DUMP" | head -1)}"
fi

# ── 3) 兜底：读 deploy/docker/.env 自行拼 URL ──
ENV_FILE="$ROOT/deploy/docker/.env"
if [[ -z "${DB_URL:-}" && -f "$ENV_FILE" ]]; then
  set -a; . "$ENV_FILE"; set +a
  DB_URL="jdbc:yasdb:primary://${YASHAN_MASTER_IP:-10.254.212.106}:1688,${YASHAN_STANDBY_IP:-10.254.212.107}:1688/${YASHAN_DB:-PM}?poolTimeout=60&failover=on&failoverType=session&failoverMethod=basic&failoverRetries=5&failoverDelay=2"
  DB_USER="${YASHAN_USER:-pm}"
  DB_PASSWORD="${YASHAN_PASSWORD:-}"
fi

if [[ -z "${DB_URL:-}" || -z "${DB_PASSWORD:-}" ]]; then
  echo "❌ 拿不到数据库连接信息。请先启动 pm-backend 容器，或导出 DB_URL / DB_USER / DB_PASSWORD。" >&2
  exit 1
fi

export DB_URL DB_USER DB_PASSWORD
echo "→ 目标库：${DB_URL%%\?*}"
echo "→ 账号：${DB_USER}（口令长度 ${#DB_PASSWORD}，不打印）"
echo "→ 执行：$SQL_FILE"
echo

is_win=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) is_win=1 ;; esac
# -Dfile.encoding：Windows 控制台默认 GBK，不指定会让中文输出变乱码（JDK 17 用此参数）
if [[ $is_win -eq 1 ]] && command -v cygpath >/dev/null 2>&1; then
  # Windows 下 java 不认 /c/... 形式，转成原生路径
  CP_WIN="$(cygpath -w "$DRIVER")"
  RUNNER_WIN="$(cygpath -w "$RUNNER")"
  SQL_WIN="$(cygpath -w "$(cd "$(dirname "$SQL_FILE")" && pwd)/$(basename "$SQL_FILE")")"
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP_WIN" "$RUNNER_WIN" "$SQL_WIN"
else
  java -Dfile.encoding=UTF-8 -cp "$DRIVER" "$RUNNER" "$SQL_FILE"
fi
