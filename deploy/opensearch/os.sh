#!/usr/bin/env bash
# ============================================================
# os.sh —— OpenSearch 单节点的【服务器唯一入口】
#
# 为什么会有这个脚本：
#   主系统 / AI 能力服务已经有一个统一入口 pm.sh；OpenSearch 是第三个独立组件，
#   它有自己的目录、自己的 .env、自己的生命周期。运维不该为它记第二套 docker 命令，
#   所以这里照 pm.sh 的同一套形状做一个入口：同样的子命令名、同样的中文提示、
#   同样的"幂等 + 失败给下一步命令"，并且【绝不硬编码 /home/lhim】——
#   BASE 由本脚本自身位置推导，脚本放哪、从哪调用都操作同一个工程。
#
# 服务器布局（BASE = 本脚本所在目录，例如 /home/lhim/os）：
#   <BASE>/
#   ├─ os.sh                 ← 本脚本
#   ├─ docker-compose.yml    ← 服务器专用编排（无 build 段 + pull_policy: never）
#   ├─ .env                  ← OPENSEARCH_* / OS_IMAGE_TAG / OS_DATA_DIR …（权限 600）
#   ├─ .env.example
#   ├─ data/                 ← 索引数据（属主 1000:1000，权限 750）【唯一有状态目录】
#   ├─ backup/               ← 快照仓库（path.repo，属主 1000:1000，权限 750）
#   └─ opensearch-<版本>-linux-<arch>.tar   ← 开发机 save 出来的镜像包
#
# 用法（在 BASE 目录下执行）：
#   bash os.sh                      # 无参数 = 打印用法并退出 1
#   bash os.sh start                # docker compose up -d + 等集群健康（幂等）
#   bash os.sh stop                 # 停容器（只停不删；没有容器时跳过）
#   bash os.sh restart              # = stop + start
#   bash os.sh status               # 容器状态 + 集群健康 + 节点列表 + 一句话结论
#   bash os.sh logs [行数]          # 跟日志（默认 docker compose logs -f；给行数则打印最近 N 行）
#   bash os.sh load <镜像包.tar>    # docker load → 解析版本 → 切 .env 的 OS_IMAGE_TAG（备份）→ 启动 + 健康检查
#   bash os.sh help                 # 用法说明
#
# 退出码：0 成功 ｜ 1 环境缺文件、镜像包不存在、健康检查失败 ｜ 2 子命令/参数用法错误
#
# 依赖：bash 4+（银河麒麟自带即可）、docker + docker compose v2、curl。**不依赖 jq**。
#
# 与 pm.sh 的关系：两者【互不调用】——pm.sh 管 /home/lhim/pm/{main,ai}，本脚本管 /home/lhim/os。
# 日常运维记住"主系统/AI 用 pm.sh，OpenSearch 用 os.sh"即可。
# 文档：《docs/OpenSearch部署与接入.md》（本脚本的每条命令都在那里有对应步骤）。
# ============================================================
set -euo pipefail

# ============================================================
# 1. 路径与常量
#    全程用 `docker compose -f <绝对路径>` 而不 cd 进目录：cd 会让"相对路径"的
#    含义取决于调用姿势（.env 里就有 OS_DATA_DIR=/home/lhim/os/data 这类绝对路径，
#    但手工换成相对路径时行为会不一致）。显式 -f 后，从哪里调用都操作同一个工程。
#    不传 -p：工程名与目录名一致（目录 /home/lhim/os ⇒ 工程名 os）；
#    若传了 -p，手工执行 `docker compose up -d` 与脚本会各建一套容器并互相报"容器名已被占用"。
# ============================================================
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$BASE/docker-compose.yml"
ENV_FILE="$BASE/.env"
ENV_EXAMPLE="$BASE/.env.example"
readonly BASE COMPOSE_FILE ENV_FILE ENV_EXAMPLE

readonly HEALTH_RETRIES=36   # 健康检查最多轮询次数
readonly HEALTH_INTERVAL=5   # 轮询间隔（秒）→ 约 5s × 36 = 180s，够 JVM + 安全插件冷启动
readonly DEF_INDEX="pm-ai-chunks"
readonly DEF_TAG="2.19.1"    # 与 .env.example / 编排的默认值保持一致

# ============================================================
# 2. 通用输出与报错（与 pm.sh 同一套前缀，日志里一眼能分清是谁打印的）
# ============================================================
log()  { printf '%s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*" >&2; }
fail() { printf '[FAIL] %s\n' "$*" >&2; }
ok()   { printf '[OK] %s\n' "$*"; }
step() { printf '\n[%s] %s\n' "$1" "$2"; }   # [1/4] 风格的分步输出

# 参数/用法错误一律退出 2（缺文件、探测失败才是 1），便于脚本里区分"我写错了"和"环境有问题"
die_usage() {
  fail "$1"
  log '运行 "bash os.sh help" 查看用法。' >&2
  exit 2
}

usage() {
  printf 'os.sh —— OpenSearch 单节点的服务器唯一入口（bash）\n'
  printf '部署根目录 BASE = %s\n\n' "$BASE"
  cat <<'EOF'
用法: bash os.sh <子命令> [参数...]

子命令:
  start                启动（docker compose up -d + 等集群健康；幂等：已在跑则只报状态）
  stop                 停止（只停不删，容器保留；没有容器时跳过，幂等）
  restart              重启 = stop + start
  status               容器状态 + 集群健康 + 节点列表 + 一句话结论（可用/不可用）
  logs [行数]          无行数 = docker compose logs -f（Ctrl-C 退出）；给行数 = 打印最近 N 行后退出
  load <镜像包.tar>    装载镜像 + 切 .env 的 OS_IMAGE_TAG（先备份 .env）+ 启动 + 健康检查
  help                 显示本说明

示例（在部署根目录执行；BASE 就是 os.sh 所在目录）:
  bash os.sh                                       # 无参数：打印用法并退出 1
  cp .env.example .env && vi .env && chmod 600 .env
  bash os.sh load /home/lhim/os/opensearch-2.19.1-linux-arm64.tar
  bash os.sh status
  bash os.sh logs 200
  bash os.sh restart

必填配置（.env，与本脚本同目录）:
  OPENSEARCH_BIND_IP   本机内网 IP（如 10.254.212.110）；默认 127.0.0.1 只绑回环
  OPENSEARCH_PASSWORD  强口令：≥8 位且大写/小写/数字/特殊字符四类齐全（不满足容器起不来）
  OS_IMAGE_TAG         必须与 docker load 进来的 tag 一致（load 子命令会自动对齐）
  其余（OPENSEARCH_USER / OPENSEARCH_PORT / OS_DATA_DIR / OS_BACKUP_DIR / TZ）按模板默认

健康检查与端口:
  curl -u $OPENSEARCH_USER:$OPENSEARCH_PASSWORD http://127.0.0.1:$OPENSEARCH_PORT/_cluster/health
  轮询约 5s × 36 次（≈180s），期望 status 为 green；失败会打印排查建议，不会静默通过。
  ★单节点上副本必须为 0：任何索引设了 number_of_replicas ≥ 1 都会让状态永久 yellow。

退出码: 0 成功 ｜ 1 环境缺文件/镜像包不存在/健康检查失败 ｜ 2 子命令或参数用法错误
EOF
}

# ============================================================
# 3. .env 读取与改写
#    读法与 pm.sh 保持一致：grep 第一个 ^KEY= 行 → cut -d= -f2-
#    （用 f2- 而不是 f2：值里带 = 时不会被截断；注释行 #KEY= 因锚定 ^ 不会命中）
# ============================================================
env_raw() { # <env文件> <键> → 原样值
  local f="$1" k="$2" v=""
  if [ -f "$f" ]; then
    v="$(grep -E "^${k}=" "$f" | head -1 | cut -d= -f2- || true)"
  fi
  printf '%s' "${v%$'\r'}"   # 去掉 CRLF 残留的 \r（.env 从 Windows 传上来时常见）
}

env_val() { # <env文件> <键> [默认值] → 去掉首尾空白与成对引号后的值
  local f="$1" k="$2" d="${3:-}" v
  v="$(env_raw "$f" "$k")"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  case "$v" in
    \"*\") v="${v#\"}"; v="${v%\"}" ;;
    \'*\') v="${v#\'}"; v="${v%\'}" ;;
  esac
  if [ -z "$v" ]; then v="$d"; fi
  printf '%s' "$v"
}

# 改 .env 里的键（沿用 pm.sh 的手法：cp 备份 + sed -i.bak，没有该行则追加）
set_env_var() { # <env文件> <键> <值>
  local f="$1" k="$2" val="$3" old esc
  old="$(env_raw "$f" "$k")"
  [ "$old" = "$val" ] && { log "      ${k} 已是 ${val}，无需修改"; return 0; }
  cp "$f" "${f}.bak"                            # 改动前的完整备份（回滚就靠它）
  if grep -q "^${k}=" "$f"; then
    # 值里若混进 & 或 | ，sed 会当成特殊字符（& = 整个匹配串），先转义再替换
    esc="$(printf '%s' "$val" | sed -e 's/[\\&|]/\\&/g')"
    sed -i.bak "s|^${k}=.*|${k}=${esc}|" "$f"   # -i.bak 会再落一份同名 .bak（内容同上，无害）
  else
    printf '%s=%s\n' "$k" "$val" >> "$f"
    log "      .env 里原本没有 ${k} 行，已在末尾追加"
  fi
  rm -f "${f}.bak.bak"
  log "      ${k}: ${old:-<无>} → ${val}"
  log "      原 .env 已备份为 ${f}.bak"
  return 0
}

# ============================================================
# 4. 配置取值（全部来自 .env；没有 .env 时给出默认值，便于 status 也能给结论）
# ============================================================
cfg_tag()   { env_val "$ENV_FILE" OS_IMAGE_TAG "$DEF_TAG"; }
cfg_bind()  { env_val "$ENV_FILE" OPENSEARCH_BIND_IP "127.0.0.1"; }
cfg_port()  { env_val "$ENV_FILE" OPENSEARCH_PORT "9200"; }
cfg_user()  { env_val "$ENV_FILE" OPENSEARCH_USER "admin"; }
cfg_pass()  { env_raw "$ENV_FILE" OPENSEARCH_PASSWORD; }   # 口令取原样：引号会变成口令的一部分
cfg_index() { env_val "$ENV_FILE" OPENSEARCH_INDEX "$DEF_INDEX"; }   # 仅用于 status 显示；真正生效的是 AI 侧同名变量
# 探测地址固定走回环：脚本跑在本机，端口对外绑的是内网 IP，回环一样能连
cfg_url()   { printf 'http://127.0.0.1:%s' "$(cfg_port)"; }

required_keys() {
  printf '%s' 'OPENSEARCH_BIND_IP / OPENSEARCH_PASSWORD（口令须 ≥8 位且四类字符齐全）/ OS_IMAGE_TAG'
}

# 目录/编排文件缺失时给出"能照着做"的报错：期望布局 + 两个文件该放哪
layout_hint() {
  cat >&2 <<EOF
       期望的服务器布局（BASE = 本脚本所在目录 = ${BASE}）：
         ${BASE}/os.sh
         ${BASE}/docker-compose.yml
         ${BASE}/.env  与  ${BASE}/.env.example
         ${BASE}/data/     （属主 1000:1000，权限 750）
         ${BASE}/backup/   （属主 1000:1000，权限 750）
         ${BASE}/opensearch-<版本>-linux-<arch>.tar
       发布方式（开发机，仓库根）：
         scp -r deploy/opensearch/*  lhim@10.254.212.110:/home/lhim/os/
       详细步骤见 docs/OpenSearch部署与接入.md §2~§4。
EOF
}

# ============================================================
# 5. 前置检查（一律 return 1 不 exit：让 status 这类查看命令能把结论都打出来）
# ============================================================
require_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    fail "未找到 docker 命令：本脚本必须在装有 Docker 的服务器上运行"
    return 1
  fi
  # 只探客户端（不连 daemon），所以 daemon 没起也不会误报到这里
  if ! docker compose version >/dev/null 2>&1; then
    fail "docker compose（v2 插件）不可用：请确认 \`docker compose version\` 能正常输出"
    return 1
  fi
  return 0
}

require_compose() {
  if [ ! -f "$COMPOSE_FILE" ]; then
    fail "缺少编排文件：$COMPOSE_FILE"
    layout_hint
    return 1
  fi
  return 0
}

require_env() {
  [ -f "$ENV_FILE" ] && return 0
  fail "缺少配置文件：$ENV_FILE"
  if [ -f "$ENV_EXAMPLE" ]; then
    log "       先生成并填写 .env（必填项见下）："
    log "         cp ${ENV_EXAMPLE} ${ENV_FILE} && chmod 600 ${ENV_FILE} && vi ${ENV_FILE}"
    log "       必填项：$(required_keys)"
  else
    log "       同目录也没有 .env.example（${ENV_EXAMPLE}），请从 deploy/opensearch/ 一并放进来。"
  fi
  return 1
}

# compose 调用：显式 -f 与 --env-file，变量插值只认运行目录里的 .env，不受调用目录影响
compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

# ============================================================
# 6. 健康探测
#    单次探测：结果放 PROBE_CODE / PROBE_BODY / PROBE_STATUS；HTTP 200 才算通。
#    不依赖 jq：用 grep -o 从 JSON 里取 "status":"green" —— 只看它比用 sed 取
#    第一个 status 字段更稳（_cluster/health 里只有 status 这一个 status 键，
#    active_shards_percent_as_number 之类不会被误命中）。
# ============================================================
probe() {
  local url user pass raw
  url="$(cfg_url)/_cluster/health"
  user="$(cfg_user)"
  pass="$(cfg_pass)"
  PROBE_CODE="000"; PROBE_BODY=""; PROBE_STATUS="unknown"
  if [ -z "$pass" ]; then
    PROBE_BODY="（.env 里 OPENSEARCH_PASSWORD 为空，无法认证）"
    return 1
  fi
  # 用 -w 把 HTTP 码附在响应体之后（@@HTTP@@ 分隔），既不需要临时文件，也不需要 jq
  raw="$(curl -sS --max-time 8 -u "${user}:${pass}" -w '\n@@HTTP@@%{http_code}' "$url" 2>/dev/null || true)"
  case "$raw" in
    *@@HTTP@@*)
      PROBE_CODE="${raw##*@@HTTP@@}"
      PROBE_BODY="${raw%$'\n'@@HTTP@@*}"
      ;;
    *) return 1 ;;   # 连不上/超时：curl 没有输出
  esac
  [ "$PROBE_CODE" = "200" ] || return 1
  PROBE_STATUS="$(printf '%s' "$PROBE_BODY" | grep -o '"status"[[:space:]]*:[[:space:]]*"[a-z]*"' | head -1 | sed 's/.*"\([a-z]*\)"$/\1/')"
  [ -n "$PROBE_STATUS" ] || PROBE_STATUS="unknown"
  return 0
}

health_hints() {
  local port bind
  port="$(cfg_port)"; bind="$(cfg_bind)"
  fail "OpenSearch 健康检查失败（最后 HTTP ${PROBE_CODE:-000}）"
  if [ -n "${PROBE_BODY:-}" ]; then
    log "      最后一次响应（截断 200 字符）: $(printf '%s' "$PROBE_BODY" | cut -c1-200)"
  fi
  log "      排查建议："
  log "        1) 看容器状态与日志： bash os.sh status ; bash os.sh logs 200"
  log "        2) HTTP 401/403 = 认证问题：核对 ${ENV_FILE} 的 OPENSEARCH_USER/OPENSEARCH_PASSWORD"
  log "           （口令须 ≥8 位且大写/小写/数字/特殊字符四类齐全，否则容器【启动即失败】）"
  log "        3) HTTP 000（连不上）= 进程没起或端口不对：${bind}:${port}；容器里是否在监听用"
  log "           docker exec pm-opensearch curl -s http://127.0.0.1:9200/ 验证"
  log "        4) 日志报 vm.max_map_count 太小：宿主执行 sysctl -w vm.max_map_count=262144"
  log "           并持久化到 /etc/sysctl.d/99-opensearch.conf（容器内改不了）"
  log "        5) 日志报内存锁定失败：确认 ulimits.memlock 生效；临时可把 bootstrap.memory_lock 改成 false"
  log "        6) 首次启动本来就慢（JVM + 插件约 30~90s，脚本最多等 180s），别急着判失败"
}

wait_health() {
  local i=1
  log "      健康检查: curl -u <user>:<pass> $(cfg_url)/_cluster/health    （每 ${HEALTH_INTERVAL}s 一次，最多 ${HEALTH_RETRIES} 次）"
  while [ "$i" -le "$HEALTH_RETRIES" ]; do
    if probe; then
      if [ "$PROBE_STATUS" = "green" ]; then
        ok "OpenSearch 就绪（HTTP 200，status=green，第 ${i} 次探测通过）"
      elif [ "$PROBE_STATUS" = "yellow" ]; then
        warn "OpenSearch 已响应，但 status=yellow（第 ${i} 次探测通过）"
        log "      ★单节点上 yellow 几乎总是某个索引设了 number_of_replicas ≥ 1 —— 单节点放不下副本，"
        log "        状态会永久 yellow 且白占一份磁盘。查出来改掉："
        log "        curl -u <user>:<pass> '$(cfg_url)/_cat/indices?v&health=yellow'"
        log "        curl -u <user>:<pass> -XPUT '$(cfg_url)/<索引>/_settings' -H 'Content-Type: application/json' -d '{\"index\":{\"number_of_replicas\":0}}'"
      else
        warn "OpenSearch 已响应，但 status=${PROBE_STATUS}（第 ${i} 次探测通过）"
        log "      status=red 表示有【主分片】未分配：看 _cat/indices?v 与 _cluster/allocation/explain"
      fi
      return 0
    fi
    log "      第 ${i}/${HEALTH_RETRIES} 次未通过（HTTP ${PROBE_CODE:-000}），${HEALTH_INTERVAL}s 后重试…"
    i=$((i + 1))
    if [ "$i" -le "$HEALTH_RETRIES" ]; then sleep "$HEALTH_INTERVAL"; fi
  done
  health_hints
  return 1
}

# ============================================================
# 7. start / stop / restart / status / logs
# ============================================================
cmd_start() {
  local tag
  require_docker || return 1
  require_compose || return 1
  require_env || return 1
  tag="$(cfg_tag)"

  step "1/3" "compose 预检（编排语法 + .env 必填项）"
  if ! compose config --quiet; then
    fail "compose 配置校验未通过：$COMPOSE_FILE"
    log "      必填项：$(required_keys)"
    log "      配置文件：$ENV_FILE"
    log "      （OPENSEARCH_PASSWORD 为空时会在这一步报错，属于预期行为）"
    return 1
  fi

  step "2/3" "docker compose up -d"
  if ! compose up -d; then
    fail "启动失败"
    log "      · 镜像没 load 进来？确认本机有 opensearchproject/opensearch:${tag}"
    log "        （没有就用： bash os.sh load ${BASE}/opensearch-<版本>-linux-<arch>.tar ）"
    log "      · 端口被占用？改 $ENV_FILE 的 OPENSEARCH_PORT，或核对 OPENSEARCH_BIND_IP"
    log "      · 报 container name is already in use：容器名 pm-opensearch 被旧容器占着，"
    log "        先释放再起： docker rm -f pm-opensearch"
    log "      · 口令复杂度不满足：容器会反复重启，看 bash os.sh logs 200"
    return 1
  fi

  step "3/3" "等待集群健康"
  if ! wait_health; then compose ps || true; return 1; fi
  compose ps || true
  return 0
}

cmd_stop() {
  local ids
  require_docker || return 1
  require_compose || return 1
  step "stop" "docker compose stop"
  # 幂等：先查有没有容器（-a 连已停止的也算）。一个都没有就不执行 stop、也不算失败——
  # "本来就没起"和"停成功"对运维是同一个结果，不该报错。
  if ! ids="$(compose ps -aq 2>/dev/null)"; then
    fail "查询容器失败：docker 是否在运行？（用 docker info 确认）"
    return 1
  fi
  if [ -z "$ids" ]; then
    log "      没有该工程的容器，无需停止（幂等跳过）"
    return 0
  fi
  if ! compose stop; then
    fail "停止失败"
    return 1
  fi
  ok "OpenSearch 已停止（只停不删；容器保留，下次 start 更快）"
  return 0
}

cmd_restart() {
  log "[INFO] restart = stop + start"
  cmd_stop
  cmd_start
}

cmd_status() {
  local bind port tag idx passinfo
  log "部署根目录 BASE = $BASE"
  log ""
  log "---- 配置（$ENV_FILE）----"
  if [ ! -f "$ENV_FILE" ]; then
    warn "缺 .env：$ENV_FILE"
    log "       先执行： cp ${ENV_EXAMPLE} ${ENV_FILE} && chmod 600 ${ENV_FILE} && vi ${ENV_FILE}"
    log "       必填项：$(required_keys)"
  else
    tag="$(cfg_tag)"; bind="$(cfg_bind)"; port="$(cfg_port)"; idx="$(cfg_index)"
    # 只报"已设置 + 长度"，绝不打印口令明文（日志会被转发、被贴进工单）
    if [ -n "$(cfg_pass)" ]; then
      passinfo="已设置（$(env_raw "$ENV_FILE" OPENSEARCH_PASSWORD | wc -c | tr -d '[:space:]') 位，不打印明文）"
    else
      passinfo="【未设置】"
    fi
    log "      镜像 tag        opensearchproject/opensearch:${tag}"
    log "      绑定与端口      ${bind}:${port}（9200 只应对两台应用服务器放行）"
    log "      索引名          ${idx}"
    log "      数据目录        $(env_val "$ENV_FILE" OS_DATA_DIR "${BASE}/data")"
    log "      快照目录        $(env_val "$ENV_FILE" OS_BACKUP_DIR "${BASE}/backup")"
    log "      口令            ${passinfo}"
  fi

  if require_docker && require_compose; then
    log ""
    log "---- 容器（docker compose ps）----"
    compose ps || log "      （docker compose ps 失败：docker 是否在运行？）"
    log ""
    log "---- 集群（本机回环探测）----"
    if probe; then
      ok "OpenSearch 可用（status=${PROBE_STATUS}，$(cfg_url)/_cluster/health）"
      if [ "$PROBE_STATUS" = "yellow" ]; then
        log "      ★yellow 的常见原因：索引设了 number_of_replicas ≥ 1（单节点放不下副本）。"
        log "        查出来改掉： curl -u <user>:<pass> '$(cfg_url)/_cat/indices?v&health=yellow'"
      fi
      if [ "$PROBE_STATUS" = "red" ]; then
        log "      ★red = 有主分片未分配：看 _cat/indices?v 与 _cluster/allocation/explain"
      fi
      compose exec -T opensearch curl -s -u "$(cfg_user):$(cfg_pass)" 'http://127.0.0.1:9200/_cat/nodes?v&h=ip,heap.percent,ram.percent,cpu,node.role,master,name' \
        || log "      （取节点列表失败，可手工执行： docker exec pm-opensearch curl -s -u <user>:<pass> 'http://127.0.0.1:9200/_cat/nodes?v'）"
      compose exec -T opensearch curl -s -u "$(cfg_user):$(cfg_pass)" 'http://127.0.0.1:9200/_cat/indices?v' \
        || log "      （取索引列表失败）"
      log "      提示：单节点时 _cat/nodes 只显示 1 行，且 ip 列常见 127.0.0.1（容器内视角）——正常现象。"
    else
      fail "OpenSearch 不可用（HTTP ${PROBE_CODE:-000}，$(cfg_url)/_cluster/health）"
      if [ -n "${PROBE_BODY:-}" ]; then
        log "      最后一次响应（截断 200 字符）: $(printf '%s' "$PROBE_BODY" | cut -c1-200)"
      fi
      log "      先看容器是否在跑（上面 ps 的输出），再 bash os.sh logs 200"
    fi
  fi
  # 查看类命令不把"没部署/没启动"当成脚本失败，结论都在上面的输出里
  return 0
}

cmd_logs() {
  local n="${1:-}"
  require_docker || return 1
  require_compose || return 1
  if [ -z "$n" ]; then
    log "[INFO] docker compose logs -f（Ctrl-C 退出；想看最近 N 行用： bash os.sh logs 200）"
    compose logs -f
  else
    case "$n" in
      ''|*[!0-9]*) die_usage "logs 的行数必须是正整数（收到：$n）" ;;
    esac
    log "[INFO] docker compose logs --tail=${n}"
    compose logs --tail="$n"
  fi
}

# ============================================================
# 8. load：docker load → 解析版本 → 切 .env 的 OS_IMAGE_TAG（备份）→ 启动 + 健康检查
#    与 pm.sh upgrade 同一套手法：镜像 tag 是配置的一部分，必须跟着包走，
#    否则"我 load 了新版本但容器还在跑旧镜像"这种事故迟早发生。
# ============================================================
cmd_load() {
  local tarball="${1:-}" ver="${2:-}" cur newtag
  [ -n "$tarball" ] || die_usage "load 需要镜像包参数（例： bash os.sh load ${BASE}/opensearch-2.19.1-linux-arm64.tar）"
  if [ ! -f "$tarball" ]; then
    fail "找不到镜像包：$tarball"
    log "      请确认路径（开发机 save 出来的包一般就放在 ${BASE}/），或用绝对路径："
    log "        bash os.sh load ${BASE}/opensearch-<版本>-linux-<arch>.tar"
    return 1
  fi
  require_docker || return 1
  require_compose || return 1
  require_env || return 1

  step "1/4" "装载镜像：docker load -i $tarball"
  if ! docker load -i "$tarball"; then
    fail "docker load 失败：$tarball"
    log "      常见原因：包损坏（传输中断，先比对 sha256sum）、包与服务器架构不符"
    log "      （arm64 包 load 到 x86 机器也能装进去，但起容器会 exec format error）、或磁盘空间不足。"
    return 1
  fi

  step "2/4" "解析版本并校验镜像是否真的 load 进来了"
  if [ -z "$ver" ]; then
    # 版本 = 第 2 个 '-' 与 '-linux-' 之间那段：opensearch-<版本>-linux-<arch>.tar [.gz]
    ver="$(printf '%s' "$(basename "$tarball")" \
      | sed -n 's/^opensearch-\(.*\)-linux-\(arm64\|amd64\|aarch64\|x86_64\)\.tar\(\.gz\)\?$/\1/p')"
  fi
  if [ -z "$ver" ]; then
    warn "无法从文件名解析版本（期望形如 opensearch-<版本>-linux-<arch>.tar）"
    log "      · 包名被改过？显式指定版本： bash os.sh load <包> <版本>"
    log "      · 或者手工改 $ENV_FILE 的 OS_IMAGE_TAG"
  elif docker image inspect "opensearchproject/opensearch:${ver}" >/dev/null 2>&1; then
    ok "镜像已就位：opensearchproject/opensearch:${ver}"
  else
    warn "镜像包里没有 opensearchproject/opensearch:${ver}，本次【不改动】OS_IMAGE_TAG"
    log "      · 用显式版本再试： bash os.sh load $tarball <真实版本>"
    ver=""
  fi

  step "3/4" "切换 OS_IMAGE_TAG（先备份 .env）"
  if [ -z "$ver" ]; then
    cur="$(cfg_tag)"
    warn "本次不修改 OS_IMAGE_TAG，将按当前 .env 的值启动（当前 ${cur:-<未设置>}）"
  else
    set_env_var "$ENV_FILE" OS_IMAGE_TAG "$ver" || { fail "写入 .env 失败：$ENV_FILE"; return 1; }
    newtag="$ver"
  fi

  step "4/4" "启动/升级（镜像或环境变量变化时 compose 会自动重建容器）"
  cmd_start
  log ""
  if [ -n "${newtag:-}" ]; then
    ok "OpenSearch 已切换到 ${newtag}（OS_IMAGE_TAG=${newtag}，原 .env 备份为 ${ENV_FILE}.bak）"
  else
    ok "OpenSearch 装载流程完成（未切版本，仍运行 OS_IMAGE_TAG=$(cfg_tag)）"
  fi
  log "      回滚办法：把 ${ENV_FILE} 的 OS_IMAGE_TAG 改回旧值（旧值就在 ${ENV_FILE}.bak 里），"
  log "                再执行： bash os.sh restart"
  log "                ⚠️ 若跨大版本降级，先确认旧版本能读回新版本写过的索引，否则用快照恢复。"
  return 0
}

# ============================================================
# 9. 子命令分发
# ============================================================
if [ "$#" -eq 0 ]; then
  usage >&2
  fail "缺少子命令"
  exit 1
fi

cmd="$1"
shift

case "$cmd" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  status)  cmd_status ;;
  logs)    cmd_logs "${1:-}" ;;
  load)    cmd_load "${1:-}" "${2:-}" ;;
  help|-h|--help) usage ;;
  *) die_usage "未知子命令：$cmd" ;;
esac
