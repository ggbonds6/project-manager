#!/usr/bin/env bash
# ============================================================
# snapshot-backup.sh —— OpenSearch 单节点的【每晚快照 + 异地副本】脚本
#
# 为什么必须有它：本方案是【单节点】（见文档 §0.2/§0.3），OpenSearch 自身没有副本可以用来容错，
# 所以"部署完就不怎么管"能不能成立，完全取决于这台机器上有没有一份【可靠的、可恢复的】数据副本。
# 索引是可重建物（重新解析附件即可），但重建代价是几十分钟到几小时 —— 有快照就能几分钟恢复。
#
# 它做四件事（幂等，重复执行安全）：
#   ① 注册/确认文件系统快照仓库（已存在则视为成功，不报错）
#   ② 打一份当天日期的快照（异步发起 + 轮询到终态，避免大快照把 HTTP 连接挂死）
#   ③ 按保留期删除过期快照（默认 7 天）
#   ④ 把快照目录 rsync 到第二台机器（10.254.212.111）
#
# 用法（脚本建议放在部署根的 config/ 下，例如 /home/opensearch/config/）：
#   bash snapshot-backup.sh                  # 等价于 snapshot：打快照 + 清理 + 同步
#   bash snapshot-backup.sh snapshot         # 同上
#   bash snapshot-backup.sh status           # 只看：集群健康 + 仓库 + 已有快照列表
#   bash snapshot-backup.sh restore <快照名> # 恢复演练/真恢复（★会覆盖当前索引，需输入 yes 确认）
#   bash snapshot-backup.sh help             # 用法
#
# 建议的 cron（每晚 02:30；见文档 §7.2）：
#   30 2 * * * /bin/bash /home/opensearch/config/snapshot-backup.sh snapshot >> /home/opensearch/logs/snapshot-cron.log 2>&1
#
# 退出码：0 成功 ｜ 1 环境/依赖/接口失败 ｜ 2 参数用法错误
#
# 依赖：bash 4+、curl、rsync（同步到 .111 时需要）。**不依赖 jq、不需要 docker**。
# 覆盖配置：同目录（或环境变量 SNAPSHOT_ENV）下的 snapshot-backup.env，见 usage。
# ============================================================
set -euo pipefail

# ============================================================
# 1. 路径与默认配置
#    BASE 按脚本自身位置推导，不硬编码；所有默认值都带环境变量/配置文件覆盖口子。
# ============================================================
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 配置文件默认与脚本同目录；放在 config/ 下时就是 /home/opensearch/config/snapshot-backup.env
ENV_FILE="${SNAPSHOT_ENV:-$BASE/snapshot-backup.env}"
readonly BASE ENV_FILE

DEF_OS_URL="http://127.0.0.1:9200"
DEF_USER="admin"
DEF_REPO="os-backup"
# 仓库 location 必须在 opensearch.yml 的 path.repo 之下（默认 /home/opensearch/backup）
DEF_REPO_LOCATION="/home/opensearch/backup/fs-repo"
# ⚠⚠ 要快照哪些索引：**必须显式列出系统索引**。
#   原因：OpenSearch 的 `_snapshot` 默认【不含系统索引】——只写业务索引的话，恢复后：
#     · `.opensearch_dashboards*`（saved objects：仪表盘/可视化/索引模式）会丢 ⇒ **Dashboards 打开是空的**；
#     · `.opendistro_security`（安全配置：内部用户/角色/角色映射）会丢 ⇒ **安全配置回默认值**（口令/权限全变）。
#   所以这里用逗号分隔的索引列表，把三类都带上：
#     ① 业务：pm-ai-chunks
#     ② Dashboards：.opensearch_dashboards*（含 .opensearch_dashboards_config）
#     ③ 安全插件：.opendistro_security（若你们改过用户/角色/租户，就必须带上它）
#   ⚠️ 用通配符（`*`）要求请求里带 "expand_wildcards"，见 take_snapshot 里的请求体。
DEF_INDEX="pm-ai-chunks,.opensearch_dashboards*,.opendistro_security"
DEF_KEEP_DAYS="7"
DEF_REMOTE="lhim@10.254.212.111:/home/opensearch/backup/"
DEF_REMOTE_HOST="10.254.212.111"
readonly DEF_OS_URL DEF_USER DEF_REPO DEF_REPO_LOCATION DEF_INDEX DEF_KEEP_DAYS DEF_REMOTE DEF_REMOTE_HOST

POLL_MAX=180     # 轮询快照状态的次数上限
POLL_INTERVAL=10 # 轮询间隔（秒）→ 最多等 30 分钟；索引量级只有几百 MB 时通常几十秒就完

# ============================================================
# 2. 通用输出（与 scripts/pm.sh 同一套前缀，日志里一眼分得清是谁打的）
# ============================================================
log()  { printf '%s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*" >&2; }
fail() { printf '[FAIL] %s\n' "$*" >&2; }
ok()   { printf '[OK] %s\n' "$*"; }
step() { printf '\n[%s] %s\n' "$1" "$2"; }

die_usage() {
  fail "$1"
  log '运行 "bash snapshot-backup.sh help" 查看用法。' >&2
  exit 2
}

usage() {
  cat <<'EOF'
snapshot-backup.sh —— OpenSearch 单节点每晚快照 + 异地副本（bash，不需要 docker）

用法: bash snapshot-backup.sh [子命令] [参数]

子命令:
  snapshot              打快照 + 按保留期清理 + rsync 到第二台机器（默认；不加子命令也是它）
  status                只看状态：集群健康 / 快照仓库 / 已有快照列表（不做任何写操作）
  restore <快照名>      从快照恢复索引（★会覆盖当前索引；需输入 yes 确认）
  help                  显示本说明

配置（优先级：环境变量 > 同目录 snapshot-backup.env > 内置默认值）:
  OS_URL              OpenSearch 地址（默认 http://127.0.0.1:9200；REST 层是 HTTP，见文档 §3.5）
  OS_USER             用户名（默认 admin）
  OS_PASSWORD         ★口令：不写在这里就用 /etc/opensearch-backup.pass 里的那一行
  SNAPSHOT_REPO       仓库名（默认 os-backup）
  REPO_LOCATION       仓库目录，必须在 opensearch.yml 的 path.repo 之下
                      （默认 /home/opensearch/backup/fs-repo）
  INDEX               要快照的索引（逗号分隔，支持通配符；默认已含系统索引）：
                      pm-ai-chunks,.opensearch_dashboards*,.opendistro_security
                      ★不要图省事只写业务索引：OpenSearch 快照默认不含系统索引，
                        Dashboards 的 saved objects 与安全配置就都恢复不回来（详见脚本头部注释）。
  KEEP_DAYS           保留天数（默认 7；更早的 nightly-* 会被删掉）
  REMOTE              异地副本目标（默认 lhim@10.254.212.111:/home/opensearch/backup/）
  REMOTE_HOST         远端主机 IP（默认 10.254.212.111）——仅用于预检与提示
  SKIP_REMOTE         设为 1 则跳过 rsync（第二台还没就绪时用）

示例:
  bash snapshot-backup.sh                                  # 手动打一次
  bash snapshot-backup.sh status
  KEEP_DAYS=3 bash snapshot-backup.sh snapshot               # 临时改保留期
  SKIP_REMOTE=1 bash snapshot-backup.sh snapshot             # 只留本地快照
  bash snapshot-backup.sh restore nightly-2026-09-24

退出码: 0 成功 ｜ 1 环境/依赖/接口失败 ｜ 2 参数用法错误
EOF
}

# ============================================================
# 3. 配置读取
#    读法与 pm.sh 保持一致：grep 第一个 ^KEY= 行 → cut -d= -f2-
#    优先级：已存在的环境变量 > 配置文件 > 默认值
# ============================================================
file_val() { # <键> [默认值]
  local k="$1" d="${2:-}" v=""
  if [ -f "$ENV_FILE" ]; then
    v="$(grep -E "^${k}=" "$ENV_FILE" | head -1 | cut -d= -f2- || true)"
    v="${v%$'\r'}"
    v="${v#"${v%%[![:space:]]*}"}"
    v="${v%"${v##*[![:space:]]}"}"
    case "$v" in
      \"*\") v="${v#\"}"; v="${v%\"}" ;;
      \'*\') v="${v#\'}"; v="${v%\'}" ;;
    esac
  fi
  if [ -z "$v" ]; then v="$d"; fi
  printf '%s' "$v"
}

env_or_file() { # <环境变量名> <配置键> [默认值]
  local ev="$1" key="$2" d="${3:-}" cur
  cur="$(eval "printf '%s' \"\${${ev}:-}\"")"
  if [ -n "$cur" ]; then printf '%s' "$cur"; else file_val "$key" "$d"; fi
}

cfg_url()      { env_or_file OS_URL OS_URL "$DEF_OS_URL"; }
cfg_user()     { env_or_file OS_USER OS_USER "$DEF_USER"; }
cfg_repo()     { env_or_file SNAPSHOT_REPO SNAPSHOT_REPO "$DEF_REPO"; }
cfg_location() { env_or_file REPO_LOCATION REPO_LOCATION "$DEF_REPO_LOCATION"; }
cfg_index()    { env_or_file INDEX INDEX "$DEF_INDEX"; }
cfg_keep()     { env_or_file KEEP_DAYS KEEP_DAYS "$DEF_KEEP_DAYS"; }
cfg_remote()   { env_or_file REMOTE REMOTE "$DEF_REMOTE"; }
cfg_rhost()    { env_or_file REMOTE_HOST REMOTE_HOST "$DEF_REMOTE_HOST"; }

# 口令：环境变量 > 配置文件 > /etc/opensearch-backup.pass（权限 600，避免写进任何可读文件）
cfg_pass() {
  local cur v=""
  cur="${OS_PASSWORD:-}"
  if [ -n "$cur" ]; then printf '%s' "$cur"; return 0; fi
  v="$(file_val OS_PASSWORD "")"
  if [ -n "$v" ]; then printf '%s' "$v"; return 0; fi
  if [ -f /etc/opensearch-backup.pass ]; then
    v="$(head -1 /etc/opensearch-backup.pass | tr -d '\r\n')"
  fi
  printf '%s' "$v"
}

# ============================================================
# 4. 前置检查（一律 return 1 不 exit；失败信息必须给出下一步命令）
# ============================================================
require_curl() {
  if ! command -v curl >/dev/null 2>&1; then
    fail "未找到 curl：请先安装（银河麒麟一般自带）"
    return 1
  fi
  return 0
}

require_pass() {
  if [ -n "$(cfg_pass)" ]; then return 0; fi
  fail "没有拿到 OpenSearch 口令，无法调用快照接口"
  log "      三种给法（任选其一）："
  log "        ① 临时：OS_PASSWORD='<口令>' bash snapshot-backup.sh snapshot"
  log "        ② 配置文件： echo 'OS_PASSWORD=<口令>' >> ${ENV_FILE} && chmod 600 ${ENV_FILE}"
  log "        ③ 独立口令文件（推荐 cron 用）： "
  log "             printf '%s\n' '<口令>' | sudo tee /etc/opensearch-backup.pass >/dev/null"
  log "             sudo chmod 600 /etc/opensearch-backup.pass"
  return 1
}

# 统一的 API 调用：结果放 API_CODE / API_BODY。
# 返回值刻意区分两类失败：
#   return 1 → 连不上（API_CODE=000），调用方通常直接中止
#   return 0 → 拿到了 HTTP 状态码（**包括 4xx/5xx**），由调用方按 API_CODE 判语义
# 这样做的原因：快照接口里"已存在仓库""快照同名"都是 400，属于可接受的幂等结果；
# 把它们和"连不上"混为一谈会让脚本分不清"该重试"还是"该继续"。
api() { # <METHOD> <路径（含 query）> [JSON body]
  local method="$1" path="$2" body="${3:-}" raw
  local -a args=(-sS --max-time 60 -u "$(cfg_user):$(cfg_pass)" -X "$method")
  args+=(-w '\n@@HTTP@@%{http_code}' "$(cfg_url)${path}")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "$body")
  fi
  raw="$(curl "${args[@]}" 2>/dev/null || true)"
  case "$raw" in
    *@@HTTP@@*)
      API_CODE="${raw##*@@HTTP@@}"
      API_BODY="${raw%$'\n'@@HTTP@@*}"
      return 0
      ;;
    *) API_CODE="000"; API_BODY=""; return 1 ;;
  esac
}

# 从 JSON 里取一个字符串字段的值（不依赖 jq）
json_str() { # <JSON> <字段名>
  printf '%s' "$1" | grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 \
    | sed 's/.*:[[:space:]]*"\(.*\)"$/\1/'
}

# ============================================================
# 5. 快照仓库：注册（幂等）
# ============================================================
ensure_repo() {
  local loc body
  loc="$(cfg_location)"
  body="{\"type\":\"fs\",\"settings\":{\"location\":\"${loc}\",\"compress\":true,\"index.latest\":false}}"
  step "1/4" "注册/确认快照仓库：$(cfg_repo) → ${loc}"
  if ! api PUT "/_snapshot/$(cfg_repo)" "$body"; then
    fail "OpenSearch 不可达（$(cfg_url)）：先确认服务在跑（systemctl status opensearch）"
    return 1
  fi
  case "$API_CODE" in
    200) ok "仓库已注册" ;;
    400)
      # 已存在是【预期内的幂等结果】，不是失败
      if printf '%s' "$API_BODY" | grep -q 'already_exists'; then
        log "      仓库早已存在，继续（幂等跳过注册）"
      else
        fail "注册仓库被拒（HTTP 400）：$API_BODY"
        log "      常见原因：location 不在 opensearch.yml 的 path.repo 之下。"
        log "      核对： grep -n '^path.repo' \${OPENSEARCH_HOME:-/home/opensearch/app}/config/opensearch.yml"
        log "            上面应为 ${loc} 的父目录（默认 /home/opensearch/backup）"
        return 1
      fi
      ;;
    401|403)
      fail "认证失败（HTTP ${API_CODE}）：口令或用户名不对"
      log "      核对用户名（默认 admin）与口令来源（环境变量 OS_PASSWORD / /etc/opensearch-backup.pass）"
      return 1
      ;;
    *)
      fail "注册仓库失败（HTTP ${API_CODE}）：$API_BODY"
      return 1
      ;;
  esac
  # 目录属主必须是 opensearch 用户，否则 OpenSearch 写不进快照文件
  if [ -d "$loc" ]; then
    return 0
  fi
  warn "仓库目录尚不存在：${loc}（OpenSearch 首次写入时会尝试创建；若失败请看文档 §7.5）"
  return 0
}

# ============================================================
# 6. 打快照（异步发起 + 轮询终态）
# ============================================================
take_snapshot() {
  local name="$1" body st i
  step "2/4" "打快照：${name}（异步发起，之后轮询状态）"
  # expand_wildcards=all：索引列表里有 .opensearch_dashboards* 这样的通配符，必须显式展开，
  # 否则通配符匹配不到（尤其系统索引默认不在 open 之外的通配范围里）⇒ 快照会"成功"但内容不全。
  # include_global_state=false：不备份集群全局状态（模板/设置等），与本方案的"只要数据"口径一致。
  body="{\"indices\":\"$(cfg_index)\",\"ignore_unavailable\":true,\"expand_wildcards\":\"all\",\"include_global_state\":false}"
  if ! api PUT "/_snapshot/$(cfg_repo)/${name}?wait_for_completion=false" "$body"; then
    fail "发起快照失败：OpenSearch 不可达"
    return 1
  fi
  case "$API_CODE" in
    200) ok "快照已受理" ;;
    400)
      if printf '%s' "$API_BODY" | grep -q 'concurrent_snapshot_execution'; then
        fail "已有快照在执行中（HTTP 400）：等它跑完再重试（bash snapshot-backup.sh status 可以看到）"
        log "      这也是每晚一份的常见竞态：上一份还没跑完时，本次直接退出而不是排队。"
      else
        fail "发起快照被拒（HTTP 400）：$API_BODY"
      fi
      return 1
      ;;
    401|403)
      fail "认证失败（HTTP ${API_CODE}）：口令或用户名不对"
      return 1
      ;;
    *)
      fail "发起快照失败（HTTP ${API_CODE}）：$API_BODY"
      return 1
      ;;
  esac

  i=1
  while [ "$i" -le "$POLL_MAX" ]; do
    if ! api GET "/_snapshot/$(cfg_repo)/${name}?ignore_unavailable=true"; then
      fail "轮询快照状态失败：OpenSearch 不可达"
      return 1
    fi
    if [ "$API_CODE" != "200" ]; then
      fail "轮询快照状态返回 HTTP ${API_CODE}：$API_BODY"
      return 1
    fi
    st="$(json_str "$API_BODY" state)"
    case "$st" in
      SUCCESS)
        ok "快照完成：${name}（第 ${i} 次轮询，state=SUCCESS）"
        return 0
        ;;
      IN_PROGRESS|STARTED|"")
        log "      [${i}/${POLL_MAX}] state=${st:-等待中}，${POLL_INTERVAL}s 后重试…"
        ;;
      FAILED|PARTIAL)
        fail "快照未成功：state=${st}（name=${name}）"
        log "      看原因： curl -sS -u $(cfg_user):'<口令>' '$(cfg_url)/_snapshot/$(cfg_repo)/${name}'"
        log "      常见原因：仓库目录不可写（属主不是 opensearch 用户）、磁盘满。"
        return 1
        ;;
      *)
        warn "未知快照状态：${st}（继续等待）"
        ;;
    esac
    i=$((i + 1))
    sleep "$POLL_INTERVAL"
  done
  fail "等待快照完成超时（已等 $((POLL_MAX * POLL_INTERVAL)) 秒）：${name}"
  log "      快照可能仍在后台执行；用 bash snapshot-backup.sh status 复查。"
  return 1
}

# ============================================================
# 7. 保留期清理：删掉比 KEEP_DAYS 更早的 nightly-* 快照
# ============================================================
prune_snapshots() {
  local keep="$1" cutoff names n name_date kept=0 deleted=0
  step "3/4" "按保留期清理（保留最近 ${keep} 天）"
  case "$keep" in
    ''|*[!0-9]*) fail "KEEP_DAYS 必须是正整数（收到：$keep）"; return 1 ;;
  esac
  cutoff="$(date -d "${keep} days ago" +%F 2>/dev/null || true)"
  if [ -z "$cutoff" ]; then
    warn "本机 date 不支持 -d（非 GNU date？），跳过清理以免误删"
    return 0
  fi
  if ! api GET "/_snapshot/$(cfg_repo)/_all"; then
    warn "列快照失败，跳过清理"
    return 0
  fi
  if [ "$API_CODE" != "200" ]; then
    warn "列快照返回 HTTP ${API_CODE}，跳过清理"
    return 0
  fi
  # 只按仓库端点取，所以这里的 "snapshot" 全是本仓库的（若将来加了第二个仓库，
  # 仓库名过滤在这里失效；本方案只有一个 fs 仓库，故不额外处理）
  names="$(printf '%s' "$API_BODY" | grep -o '"snapshot"[[:space:]]*:[[:space:]]*"[^"]*"' \
            | sed 's/.*:[[:space:]]*"\(.*\)"$/\1/' | sort || true)"
  if [ -z "$names" ]; then
    log "      还没有任何快照，无需清理"
    return 0
  fi
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    case "$n" in
      nightly-*) ;;
      *) log "      跳过非本脚本产生的快照：${n}"; kept=$((kept + 1)); continue ;;
    esac
    # 快照名里的日期段（nightly-YYYY-MM-DD）；解析不出就保守保留
    name_date="$(printf '%s' "$n" | sed -n 's/^nightly-\([0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}\).*$/\1/p')"
    if [ -z "$name_date" ]; then
      warn "快照名里没有可解析的日期，保守保留：${n}"
      kept=$((kept + 1)); continue
    fi
    if [[ "$name_date" < "$cutoff" ]]; then
      if api DELETE "/_snapshot/$(cfg_repo)/${n}" && [ "$API_CODE" = "200" ]; then
        ok "已删除过期快照：${n}（${name_date} < ${cutoff}）"
        deleted=$((deleted + 1))
      else
        warn "删除失败（HTTP ${API_CODE}；可手工执行）： curl -XDELETE -u $(cfg_user):'<口令>' '$(cfg_url)/_snapshot/$(cfg_repo)/${n}'"
      fi
    else
      kept=$((kept + 1))
    fi
  done <<< "$names"
  log "      保留 ${kept} 份，删除 ${deleted} 份（截止日期 ${cutoff}）"
  return 0
}

# ============================================================
# 8. 异地副本：rsync 到第二台机器
# ============================================================
sync_remote() {
  local remote rhost loc
  remote="$(cfg_remote)"; rhost="$(cfg_rhost)"; loc="$(cfg_location)"
  step "4/4" "同步到第二台机器（异地副本）"
  if [ "${SKIP_REMOTE:-0}" = "1" ]; then
    warn "SKIP_REMOTE=1：本次跳过 rsync（第二台还没就绪时用）"
    log "      但请注意：单节点方案里【异地副本是防硬件损坏的唯一手段】，别长期跳过（文档 §0.4）"
    return 0
  fi
  if ! command -v rsync >/dev/null 2>&1; then
    fail "未找到 rsync：无法同步到 ${rhost}"
    log "      安装： sudo yum install -y rsync   （或 apt-get install -y rsync，按发行版）"
    log "      临时跳过： SKIP_REMOTE=1 bash snapshot-backup.sh snapshot"
    return 1
  fi
  if [ ! -d "$loc" ]; then
    fail "本地快照目录不存在：${loc}（快照没落盘？先看 bash snapshot-backup.sh status）"
    return 1
  fi
  # 预检连通性（rsync 失败信息很难懂，这里先把"到底通不通"问清楚）
  if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "$(printf '%s' "$remote" | cut -d: -f1)" true 2>/dev/null; then
    fail "无法免密 ssh 到 ${rhost}（rsync 需要它）"
    log "      ① 配免密： ssh-copy-id ${remote%%:*}      （在【本机】执行，输一次远端口令）"
    log "      ② 确认远端目录存在且属主正确： ssh ${rhost} 'ls -ld /home/opensearch/backup'"
    log "         期望属主 opensearch:opensearch（否则恢复时读不了）"
    log "      ③ 临时跳过本次同步： SKIP_REMOTE=1 bash snapshot-backup.sh snapshot"
    return 1
  fi
  if ! rsync -a --delete "$loc/" "$remote"; then
    fail "rsync 失败：${loc}/ → ${remote}"
    log "      手工复核： rsync -av --delete ${loc}/ ${remote}"
    return 1
  fi
  ok "已同步到 ${remote}"
  return 0
}

# ============================================================
# 9. 子命令
# ============================================================
cmd_snapshot() {
  local name keep
  require_curl || return 1
  require_pass || return 1
  name="nightly-$(date +%F)"
  keep="$(cfg_keep)"

  log "================================================"
  log "OpenSearch 快照任务：${name}"
  log "  地址   $(cfg_url)（用户 $(cfg_user)）"
  log "  仓库   $(cfg_repo) → $(cfg_location)"
  log "  索引   $(cfg_index)（含系统索引，见脚本头部注释）"
  log "  保留   ${keep} 天"
  log "  异地   $(cfg_remote)（SKIP_REMOTE=${SKIP_REMOTE:-0}）"
  log "================================================"

  ensure_repo || return 1
  take_snapshot "$name" || return 1
  prune_snapshots "$keep" || true   # 清理失败不该让"快照已成功"变成失败
  sync_remote || return 1
  log ""
  ok "快照任务完成：${name}"
  log "      下一步：把下面这行写进 crontab -e（每晚 02:30）"
  log "        30 2 * * * /bin/bash ${BASE}/snapshot-backup.sh snapshot >> ${BASE}/logs/snapshot-cron.log 2>&1"
  return 0
}

cmd_status() {
  local st
  require_curl || return 1
  log "配置：$(cfg_url)  仓库=$(cfg_repo)  目录=$(cfg_location)  保留=$(cfg_keep) 天  异地=$(cfg_remote)"
  if [ -f "$ENV_FILE" ]; then log "配置文件：$ENV_FILE"; else log "配置文件：$ENV_FILE（不存在，用默认值）"; fi
  log ""
  log "---- 集群健康 ----"
  if api GET "/_cluster/health"; then
    st="$(json_str "$API_BODY" status)"
    ok "OpenSearch 可达（status=${st}）"
    [ "$st" = "yellow" ] && log "      ★yellow：单节点上几乎总是某个索引设了 number_of_replicas ≥ 1（文档 §7.5）"
  else
    fail "OpenSearch 不可达（$(cfg_url)）"
    log "      先确认服务： systemctl status opensearch ; journalctl -u opensearch -n 50"
    return 1
  fi
  require_pass || return 1
  log ""
  log "---- 快照仓库 ----"
  if api GET "/_snapshot/$(cfg_repo)" && [ "$API_CODE" = "200" ]; then
    printf '%s\n' "$API_BODY"
  else
    warn "仓库不可查（HTTP ${API_CODE}）；可能还没注册过，先跑 bash snapshot-backup.sh snapshot"
  fi
  log ""
  log "---- 已有快照 ----"
  if api GET "/_snapshot/$(cfg_repo)/_all" && [ "$API_CODE" = "200" ]; then
    printf '%s\n' "$API_BODY" | grep -o '"snapshot"[[:space:]]*:[[:space:]]*"[^"]*"\|"state"[[:space:]]*:[[:space:]]*"[^"]*"' || log "      （没有快照）"
  else
    log "      （查不到；先跑 bash snapshot-backup.sh snapshot）"
  fi
  log ""
  log "---- 本地/异地目录 ----"
  ls -ld "$(cfg_location)" 2>/dev/null || log "      ${cfg_location}（不存在）"
  return 0
}

cmd_restore() {
  local name="${1:-}" ans=""
  [ -n "$name" ] || die_usage "restore 需要快照名（例： bash snapshot-backup.sh restore nightly-$(date +%F)）"
  require_curl || return 1
  require_pass || return 1
  fail "恢复会把索引【覆盖】成快照里的状态（这是一条会改数据的命令）"
  log "      快照名：${name}"
  log "      索引：$(cfg_index)"
  log "      （默认含 .opensearch_dashboards* 与 .opendistro_security：恢复后 Dashboards 的仪表盘与安全配置都会回来）"
  log "      注意：目标索引若已存在，恢复请求会失败（HTTP 400）——"
  log "            应急恢复的常见做法是先删旧索引再恢复："
  log "              curl -XDELETE -u $(cfg_user):'<口令>' '$(cfg_url)/pm-ai-chunks'"
  log "              # 系统索引（.opensearch_dashboards*）一般无需删：演练机上本来就不存在"
  log "      确认无误请输入 yes 继续："
  read -r ans || true
  [ "$ans" = "yes" ] || { log "已取消（只接受 yes）"; return 1; }

  step "restore" "从 ${name} 恢复 $(cfg_index)"
  if ! api POST "/_snapshot/$(cfg_repo)/${name}/_restore?wait_for_completion=true" \
       "{\"indices\":\"$(cfg_index)\",\"ignore_unavailable\":true,\"include_global_state\":false}"; then
    fail "OpenSearch 不可达（$(cfg_url)）"
    return 1
  fi
  if [ "$API_CODE" = "200" ]; then
    ok "恢复完成"
    log "      核对业务索引条数： curl -sS -u $(cfg_user):'<口令>' '$(cfg_url)/pm-ai-chunks/_count'"
    log "      核对系统索引回来了： curl -sS -u $(cfg_user):'<口令>' '$(cfg_url)/_cat/indices/.opensearch_dashboards*?v'"
    return 0
  fi
  fail "恢复失败（HTTP ${API_CODE}）：$API_BODY"
  log "      常见原因：目标索引已存在（先删）、快照不完整、磁盘空间不足。"
  log "      演练场景请看文档 §6.4 的七步（那里用的是第二台机器，不动生产节点）。"
  return 1
}

# ============================================================
# 10. 分发
# ============================================================
cmd="${1:-snapshot}"
case "$cmd" in
  snapshot|"") cmd_snapshot ;;
  status)      cmd_status ;;
  restore)     shift || true; cmd_restore "${1:-}" ;;
  help|-h|--help) usage ;;
  *) die_usage "未知子命令：$cmd" ;;
esac
