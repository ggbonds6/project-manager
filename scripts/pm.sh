#!/usr/bin/env bash
# ============================================================
# pm.sh —— PM 主系统 + AI 能力服务【两个项目共用的唯一服务器入口】
#
# 为什么会有这个脚本：
#   服务器上不再把两个项目混在一棵目录里（旧布局是 pm/app 放主系统、pm-ai/app 放 AI，
#   各配一个升级脚本）。现在主系统与 AI 能力服务各有独立运行目录，但运维
#   **只需要记住一个入口**：本脚本；两个项目的启停、状态、日志、升级都在这里。
#
# 服务器布局（BASE = 本脚本所在目录；脚本按【自身位置】推导，绝不硬编码 /home/lhim）：
#   <BASE>/                     ← 部署根，例如 /home/lhim/pm
#   ├─ pm.sh                    ← 本脚本（发布包会把它放在 pm/ 根目录下）
#   ├─ main/                    ← 主系统运行目录（dist 产出，服务器上无源码）
#   │  ├─ docker-compose.yml     （服务器专用编排，无 build 段）
#   │  ├─ .env                   （IMAGE_TAG / WEB_PORT / YASHAN_* / AI_* …）
#   │  ├─ .env.example
#   │  ├─ 服务器部署步骤.txt
#   │  └─ releases/pm-images-aarch64-<版本>.tar.gz
#   └─ ai/                      ← AI 能力服务运行目录（dist 产出，服务器上无源码）
#      ├─ docker-compose.yml     （= ai-backend/docker-compose.deploy.yml 改名）
#      ├─ .env                   （AI_IMAGE_TAG / AI_PORT / AI_BIND_IP / LLM_API_KEY …）
#      ├─ .env.example
#      ├─ 服务器部署步骤-ai.txt
#      └─ releases/pm-ai-images-aarch64-<版本>.tar.gz
#
# 用法（在 BASE 目录下执行）：
#   bash pm.sh                              # 无参数 = 打印用法并退出 1
#   bash pm.sh start   [main|ai|all]        # 默认 all；★先起 ai 再起 main；每步 up -d 后健康检查
#   bash pm.sh stop    [main|ai|all]        # 默认 all；★反序（先 main 再 ai）；容器不存在时不报错
#   bash pm.sh restart [main|ai|all]        # = stop + start（同一个目标）
#   bash pm.sh status                       # 两个工程 ps + 各自健康探测结论（可用/不可用）
#   bash pm.sh logs <main|ai> [服务名]      # docker compose logs -f [服务名]
#   bash pm.sh upgrade <main|ai> <镜像包>   # load → 解析版本 → 切 .env 的 tag（备份）→ up -d → 健康检查
#   bash pm.sh <镜像包>                     # 兼容旧习惯，等价于 upgrade main <镜像包>
#                                           # （包名是 pm-ai-images-* 时拒绝并提示改用 upgrade ai）
#   bash pm.sh help                         # 用法说明
#
# 退出码：0 成功 ｜ 1 环境缺文件、镜像包不存在、健康检查失败 ｜ 2 子命令/参数用法错误
#
# 依赖：bash 4+（Linux 自带即可）、docker + docker compose v2、curl。**不依赖 jq**。
# ============================================================
set -euo pipefail

# ============================================================
# 1. 路径与常量
#    为什么全程用 `docker compose -f <绝对路径>` 而不 cd 进运行目录：
#      cd 会让"相对路径"的含义取决于调用姿势（例如 .env 里可能出现的
#      WORK_DIR=./work、UPLOAD_VOLUME=/mnt/... 之类），从 BASE 调用一次、
#      从 main/ 里再调用一次，行为就不一样了。显式 -f 后，脚本放在哪、
#      从哪里调用都不影响它操作哪个工程。
# ============================================================
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN_DIR="$BASE/main"
AI_DIR="$BASE/ai"
readonly BASE MAIN_DIR AI_DIR

readonly HEALTH_RETRIES=20   # 健康检查最多轮询次数
readonly HEALTH_INTERVAL=3   # 轮询间隔（秒）→ 约 3s × 20 = 60s，够 JVM 冷启动

# ============================================================
# 2. 通用输出与报错
# ============================================================
log()  { printf '%s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*" >&2; }
fail() { printf '[FAIL] %s\n' "$*" >&2; }
ok()   { printf '[OK] %s\n' "$*"; }
step() { printf '\n[%s] %s\n' "$1" "$2"; }   # [1/4] 风格的分步输出

# 参数/用法错误一律退出 2（缺文件、探测失败才是 1），便于脚本里区分"我写错了"和"环境有问题"
die_usage() {
  fail "$1"
  log '运行 "bash pm.sh help" 查看用法。' >&2
  exit 2
}

# ============================================================
# 3. 项目画像：把 main / ai 的差异收在一处
# ============================================================
proj_dir()    { case "$1" in main) printf '%s' "$MAIN_DIR" ;; ai) printf '%s' "$AI_DIR" ;; esac; }
compose_file(){ printf '%s/docker-compose.yml' "$(proj_dir "$1")"; }
env_file()    { printf '%s/.env' "$(proj_dir "$1")"; }
proj_image()  { case "$1" in main) printf 'pm-backend' ;; ai) printf 'pm-ai-backend' ;; esac; }
proj_tagkey() { case "$1" in main) printf 'IMAGE_TAG' ;; ai) printf 'AI_IMAGE_TAG' ;; esac; }
proj_portkey(){ case "$1" in main) printf 'WEB_PORT' ;; ai) printf 'AI_PORT' ;; esac; }
proj_portdef(){ case "$1" in main) printf '8080' ;; ai) printf '8100' ;; esac; }
proj_label()  { case "$1" in main) printf '主系统(main)' ;; ai) printf 'AI 能力服务(ai)' ;; esac; }
expected_tar(){ case "$1" in main) printf 'pm-images-<arch>-<版本>.tar.gz' ;; ai) printf 'pm-ai-images-<arch>-<版本>.tar.gz' ;; esac; }
upgrade_eg()  { case "$1" in
                  main) printf 'bash pm.sh upgrade main main/releases/pm-images-aarch64-v3.6.3.tar.gz' ;;
                  ai)   printf 'bash pm.sh upgrade ai ai/releases/pm-ai-images-aarch64-v1.0.1.tar.gz' ;;
                esac; }

# 各工程的必填配置项（.env 缺失时逐个列出，省得对着模板猜）
required_keys() {
  case "$1" in
    main) printf '%s' 'YASHAN_PASSWORD / JWT_SECRET / OBS 五项（APP_STORAGE_OBS_ENDPOINT、APP_STORAGE_OBS_BUCKET、APP_STORAGE_OBS_AK、APP_STORAGE_OBS_SK、APP_STORAGE_OBS_PREFIX）' ;;
    ai)   printf '%s' 'LLM_API_KEY / AI_IMAGE_TAG / AI_BIND_IP' ;;
  esac
}

# ============================================================
# 4. .env 读取
#    读法与旧 pm-upgrade.sh 保持一致：grep 第一个 ^KEY= 行 → cut -d= -f2-
#    （用 f2- 而不是 f2：值里带 = 时不会被截断；注释行 #KEY= 因为锚定了 ^ 不会命中）
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

# 改 .env 里的版本 tag（沿用旧 pm-upgrade.sh 的手法：cp 备份 + sed -i.bak，没有该行则追加）
set_env_tag() { # <env文件> <键> <版本>
  local f="$1" k="$2" ver="$3" old esc
  old="$(env_raw "$f" "$k")"
  if [ "$old" = "$ver" ]; then
    log "      ${k} 已是 ${ver}，无需修改"
    return 0
  fi
  cp "$f" "${f}.bak"                            # 改动前的完整备份（回滚就靠它）
  if grep -q "^${k}=" "$f"; then
    # 版本串里若混进 & 或 | ，sed 会当成特殊字符（& = 整个匹配串），先转义再替换
    esc="$(printf '%s' "$ver" | sed -e 's/[\\&|]/\\&/g')"
    sed -i.bak "s|^${k}=.*|${k}=${esc}|" "$f"   # -i.bak 会再落一份同名 .bak（内容同上，无害）
  else
    printf '%s=%s\n' "$k" "$ver" >> "$f"
    log "      .env 里原本没有 ${k} 行，已在末尾追加"
  fi
  rm -f "${f}.bak.bak"
  log "      ${k}: ${old:-<无>} → ${ver}"
  log "      原 .env 已备份为 ${f}.bak"
  return 0
}

# 只凭包名猜这个包属于哪个工程（判不出来输出空串）。
# 用途：拦住"把 AI 包升级进主系统"这类手滑——包名是唯一可靠的判据。
tarball_project() {
  local base
  base="$(basename "$1")"
  case "$base" in
    pm-ai-images-*.tar.gz) printf 'ai' ;;
    pm-images-*.tar.gz)    printf 'main' ;;
  esac
  return 0
}

# 版本解析：main = pm-images-<arch>-<版本>.tar.gz，ai = pm-ai-images-<arch>-<版本>.tar.gz
# 解析不出时【不报错、返回空串】——调用处据此决定"不改 tag"（与旧脚本行为一致）
parse_version() { # <main|ai> <镜像包路径>
  local proj="$1" base
  base="$(basename "$2")"
  case "$proj" in
    main)
      if [[ "$base" =~ ^pm-images-[^-]+-(.+)\.tar\.gz$ ]]; then printf '%s' "${BASH_REMATCH[1]}"; fi
      ;;
    ai)
      if [[ "$base" =~ ^pm-ai-images-[^-]+-(.+)\.tar\.gz$ ]]; then printf '%s' "${BASH_REMATCH[1]}"; fi
      ;;
  esac
  return 0
}

# ============================================================
# 5. 前置检查：docker / 目录 / .env
#    这里一律 return 1（不 exit），让 cmd_start 能在 all 模式下继续起另一个工程，
#    最后统一汇总失败——单个工程的问题不该连累另一个。
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

# 目录/编排文件缺失时给出"能照着做"的报错：期望布局 + 发布包的哪部分该放哪
layout_hint() {
  cat >&2 <<EOF
       期望的服务器布局（BASE = 本脚本所在目录 = ${BASE}）：
         ${BASE}/pm.sh
         ${BASE}/main/  {docker-compose.yml, .env, .env.example, 服务器部署步骤.txt, releases/}
         ${BASE}/ai/    {docker-compose.yml, .env, .env.example, 服务器部署步骤-ai.txt, releases/}
       发布包里的东西应该这样放（开发机解开 dist/*.tar.gz 之后）：
         · 主系统包 dist/pm-release-<版本>/：
             docker-compose.yml、.env.example、服务器部署步骤.txt  → ${BASE}/main/
             pm-images-<arch>-<版本>.tar.gz                        → ${BASE}/main/releases/
         · AI 能力服务包 dist/pm-ai-release-<版本>/：
             docker-compose.yml、.env.example、服务器部署步骤-ai.txt → ${BASE}/ai/
             pm-ai-images-<arch>-<版本>.tar.gz                      → ${BASE}/ai/releases/
       注意：包里的 docker-compose.yml 已经是"改名后"的服务器编排，直接放进去即可。
EOF
}

require_project() { # <main|ai>
  local proj="$1" dir cf
  dir="$(proj_dir "$proj")"
  cf="$(compose_file "$proj")"
  if [ ! -d "$dir" ]; then
    fail "$(proj_label "$proj") 的运行目录不存在：$dir"
    layout_hint
    return 1
  fi
  if [ ! -f "$cf" ]; then
    fail "$(proj_label "$proj") 缺少编排文件：$cf"
    layout_hint
    return 1
  fi
  return 0
}

require_env() { # <main|ai>
  local proj="$1" dir ef
  dir="$(proj_dir "$proj")"
  ef="$(env_file "$proj")"
  [ -f "$ef" ] && return 0
  fail "$(proj_label "$proj") 缺少配置文件：$ef"
  if [ -f "${dir}/.env.example" ]; then
    log "       先生成并填写 .env（必填项见下）："
    log "         cp ${dir}/.env.example ${ef} && vi ${ef}"
    log "       必填项：$(required_keys "$proj")"
  else
    log "       同目录也没有 .env.example（${dir}/.env.example），请从发布包里一并放进来。"
  fi
  return 1
}

# ============================================================
# 6. compose 调用与健康检查
#    不传 -p：让工程名与目录名一致（main / ai），这样手工执行 `docker compose up -d`
#    与 pm.sh 操作的是同一个工程，不会各自建一套容器、互相报"容器名已被占用"。
#    显式 --env-file：变量插值只认运行目录里的 .env，不受调用目录影响。
# ============================================================
compose() { # <main|ai> <compose 子命令与参数...>
  local proj="$1"; shift
  docker compose --env-file "$(env_file "$proj")" -f "$(compose_file "$proj")" "$@"
}

proj_port() { # <main|ai> → 对外端口（main=WEB_PORT，ai=AI_PORT）
  local proj="$1"
  env_val "$(env_file "$proj")" "$(proj_portkey "$proj")" "$(proj_portdef "$proj")"
}

health_url() { # <main|ai> → 本机健康探测地址
  local proj="$1"
  if [ "$proj" = "main" ]; then
    printf 'http://127.0.0.1:%s/api/health' "$(proj_port "$proj")"
  else
    printf 'http://127.0.0.1:%s/health?with_ocr=false' "$(proj_port "$proj")"
  fi
}

# 单次探测：结果放 PROBE_CODE / PROBE_BODY；HTTP 200 且业务健康才算通过
probe() { # <main|ai> <url>
  local proj="$1" url="$2" raw
  # 用 -w 把 HTTP 码附在响应体之后（@@HTTP@@ 分隔），既不需要临时文件，也不需要 jq
  raw="$(curl -sS --max-time 5 -w '\n@@HTTP@@%{http_code}' "$url" 2>/dev/null || true)"
  case "$raw" in
    *@@HTTP@@*)
      PROBE_CODE="${raw##*@@HTTP@@}"
      PROBE_BODY="${raw%$'\n'@@HTTP@@*}"
      ;;
    *) PROBE_CODE="000"; PROBE_BODY="" ;;   # 连不上/超时：curl 没有输出
  esac
  [ "$PROBE_CODE" = "200" ] || return 1
  # main 的 /api/health 在【数据库连不上时仍然返回 200】，body 里是 db:"down: ..."：
  # 只看 HTTP 码会把"库不通"误报成"可用"，所以这里必须再看 body。
  # ai 的 /health 里 code 是字面量 0（恒为 0），看它没有信息量，故只认 HTTP 200
  # ——与容器自身 healthcheck（探 /health?with_ocr=false 能通）判定一致。
  if [ "$proj" = "main" ]; then
    grep -Eq '"db"[[:space:]]*:[[:space:]]*"up"' <<< "$PROBE_BODY" || return 1
  fi
  return 0
}

health_hints() { # <main|ai> <端口>
  local proj="$1" port="$2" dir
  dir="$(proj_dir "$proj")"
  fail "$(proj_label "$proj") 健康检查失败（最后 HTTP ${PROBE_CODE:-000}）"
  if [ -n "${PROBE_BODY:-}" ]; then
    log "      最后一次响应（截断 200 字符）: $(printf '%s' "$PROBE_BODY" | cut -c1-200)"
  fi
  log "      排查建议："
  log "        1) 看容器状态与日志： bash pm.sh status ; bash pm.sh logs $proj"
  log "        2) 端口是否配错： ${dir}/.env 的 $(proj_portkey "$proj")=${port}（探测用的就是它）"
  if [ "$proj" = "main" ]; then
    log "        3) HTTP 200 但 db 不是 up：数据库/口令问题，检查 YASHAN_PASSWORD、YASHAN_MASTER_IP 连通性"
    log "           （YAS-02143=口令错；YAS-02193=账号被锁，多是口令错反复重试造成）"
  else
    log "        3) 进程/端口没起：确认 AI_BIND_IP 与 AI_PORT；LLM_API_KEY 未填时服务自检也会不通过"
  fi
  log "        4) 本检查只探【本机回环】；若只是外部访问不通，多半是防火墙或 AI_BIND_IP 绑定问题"
}

# 轮询健康检查：约 3s × 20 次；失败不静默通过，一定打印排查建议
wait_health() { # <main|ai>
  local proj="$1" url port i=1
  url="$(health_url "$proj")"
  port="$(proj_port "$proj")"
  log "      健康检查: curl $url    （每 ${HEALTH_INTERVAL}s 一次，最多 ${HEALTH_RETRIES} 次）"
  while [ "$i" -le "$HEALTH_RETRIES" ]; do
    if probe "$proj" "$url"; then
      ok "$(proj_label "$proj") 可用（HTTP 200，第 ${i} 次探测通过）"
      return 0
    fi
    log "      第 ${i}/${HEALTH_RETRIES} 次未通过（HTTP ${PROBE_CODE}），${HEALTH_INTERVAL}s 后重试…"
    i=$((i + 1))
    if [ "$i" -le "$HEALTH_RETRIES" ]; then sleep "$HEALTH_INTERVAL"; fi
  done
  health_hints "$proj" "$port"
  return 1
}

# ============================================================
# 7. 汇总输出：访问地址 + 一句话状态
# ============================================================
summary() { # <要汇报的工程...>（顺序即打印顺序）
  local p port bind
  log ""
  log "================= 访问地址与状态 ================="
  for p in "$@"; do
    [ -d "$(proj_dir "$p")" ] || continue
    if [ ! -f "$(env_file "$p")" ]; then
      log "  [$(proj_label "$p")] 未配置（缺 $(env_file "$p")）"
      continue
    fi
    port="$(proj_port "$p")"
    if probe "$p" "$(health_url "$p")"; then
      log "  [$(proj_label "$p")] 可用"
    else
      log "  [$(proj_label "$p")] 不可用（HTTP ${PROBE_CODE:-000}）"
    fi
    if [ "$p" = "main" ]; then
      log "      前端（本机）  http://127.0.0.1:${port}/"
      log "      健康接口      http://127.0.0.1:${port}/api/health"
      log "      （外部访问把 127.0.0.1 换成服务器 IP；端口见 ${MAIN_DIR}/.env 的 WEB_PORT）"
    else
      bind="$(env_val "$(env_file "$p")" AI_BIND_IP '0.0.0.0')"
      log "      服务自检      http://127.0.0.1:${port}/health?with_ocr=false"
      log "      监听地址      ${bind}:${port}（AI_BIND_IP 为空或 0.0.0.0 = 对所有网卡开放）"
      if [ "$bind" = "0.0.0.0" ]; then
        log "      ⚠AI 服务当前没有入站鉴权：建议 AI_BIND_IP 填内网 IP，并用防火墙只放行主系统服务器"
      fi
    fi
  done
  log "================================================="
}

# ============================================================
# 8. start / stop / restart / status / logs
# ============================================================
start_one() { # <main|ai> [soft]
  local proj="$1" soft="${2:-}" ef
  if [ "$soft" = "soft" ] && [ ! -d "$(proj_dir "$proj")" ]; then
    warn "未部署 $(proj_label "$proj")（没有目录 $(proj_dir "$proj")），已跳过"
    log "       只部署主系统时用： bash pm.sh start main" >&2
    return 0
  fi
  require_docker || return 1
  require_project "$proj" || return 1
  require_env "$proj" || return 1
  ef="$(env_file "$proj")"

  step "1/3" "$(proj_label "$proj")：compose 预检（编排语法 + .env 必填项）"
  if ! compose "$proj" config --quiet; then
    fail "compose 配置校验未通过：$(compose_file "$proj")"
    log "      必填项：$(required_keys "$proj")"
    log "      配置文件：$ef"
    log "      （.env 里 YASHAN_PASSWORD 这类必填项为空时，compose 会在这一步就报错，属于预期行为）"
    return 1
  fi

  step "2/3" "$(proj_label "$proj")：docker compose up -d"
  if ! compose "$proj" up -d; then
    fail "启动失败：$(proj_label "$proj")"
    log "      · 镜像没 load 进来？确认本机有 $(proj_image "$proj"):$(env_val "$ef" "$(proj_tagkey "$proj")" latest)"
    log "      · 端口被占用？改 $ef 的 $(proj_portkey "$proj")"
    log "      · 报 container name is already in use：从旧布局（pm/app、pm-ai/app）迁过来的老容器还在，"
    log "        先清理再起： docker rm -f pm-backend pm-frontend pm-ai-backend"
    return 1
  fi

  step "3/3" "$(proj_label "$proj")：健康检查"
  wait_health "$proj" || return 1
  compose "$proj" ps || true
  return 0
}

stop_one() { # <main|ai> [soft]
  local proj="$1" soft="${2:-}" ids
  if [ "$soft" = "soft" ] && [ ! -d "$(proj_dir "$proj")" ]; then
    warn "未部署 $(proj_label "$proj")（没有目录 $(proj_dir "$proj")），已跳过"
    return 0
  fi
  require_docker || return 1
  require_project "$proj" || return 1

  step "stop" "$(proj_label "$proj")：docker compose stop"
  # 幂等：先查有没有容器（-a 连已停止的也算）。一个都没有就不执行 stop、也不算失败——
  # "本来就没起"和"停成功"对运维是同一个结果，不该报错。
  if ! ids="$(compose "$proj" ps -aq 2>/dev/null)"; then
    fail "查询 $(proj_label "$proj") 的容器失败：docker 是否在运行？（用 docker info 确认）"
    return 1
  fi
  if [ -z "$ids" ]; then
    log "      没有该工程的容器，无需停止（幂等跳过）"
    return 0
  fi
  if ! compose "$proj" stop; then
    fail "停止失败：$(proj_label "$proj")"
    return 1
  fi
  ok "$(proj_label "$proj") 已停止（只停不删；容器保留，下次 start 更快）"
  return 0
}

cmd_start() { # [main|ai|all]
  local target="${1:-all}" rc=0 soft=""
  case "$target" in
    main|ai|all) ;;
    *) die_usage "start 的参数只能是 main | ai | all（收到：$target）" ;;
  esac
  # all 模式下"某个工程连目录都没有"= 该工程没部署，跳过并提示（只部署主系统的服务器很常见）；
  # 但"目录在、编排或 .env 缺"仍按错误处理——那是部署不完整，不能静默放过。
  if [ "$target" = "all" ]; then soft="soft"; fi

  # ★为什么先起 ai 再起 main：
  #   ① 主系统的 AI 功能（附件自动解析、知识库问答）依赖 ai 服务。先起 ai，主系统一启动就是
  #      可用状态，能少一次界面上"AI 服务不可用"的误报（首次部署时最容易吓到人）；
  #   ② ai 加载文档库/向量缓存比主系统慢，先发车能把两段启动时间重叠，总等待更短。
  if [ "$target" = "ai" ] || [ "$target" = "all" ]; then start_one ai "$soft" || rc=1; fi
  if [ "$target" = "main" ] || [ "$target" = "all" ]; then start_one main "$soft" || rc=1; fi

  case "$target" in
    main) summary main ;;
    ai)   summary ai ;;
    all)  summary ai main ;;
  esac
  # 某个工程没起来就返回 1：不静默通过（但另一个工程已经尽力起过了）
  return "$rc"
}

cmd_stop() { # [main|ai|all]
  local target="${1:-all}" rc=0 soft=""
  case "$target" in
    main|ai|all) ;;
    *) die_usage "stop 的参数只能是 main | ai | all（收到：$target）" ;;
  esac
  if [ "$target" = "all" ]; then soft="soft"; fi

  # ★为什么反序停（先 main 再 ai）：
  #   主系统会调用 ai（附件解析/问答）。先停主系统，就不会再有新的请求打到 ai；
  #   反过来先停 ai，还在跑的主系统会短时间内报"AI 服务不可用"，日志里留下假故障，
  #   事后排查时容易被这条噪音带偏。
  if [ "$target" = "main" ] || [ "$target" = "all" ]; then stop_one main "$soft" || rc=1; fi
  if [ "$target" = "ai" ] || [ "$target" = "all" ]; then stop_one ai "$soft" || rc=1; fi
  return "$rc"
}

cmd_restart() { # [main|ai|all] —— 等价 stop + start（顺序与依赖关系都在上面两个函数里）
  local target="${1:-all}"
  case "$target" in
    main|ai|all) ;;
    *) die_usage "restart 的参数只能是 main | ai | all（收到：$target）" ;;
  esac
  log "[INFO] restart = stop + start（$target）"
  cmd_stop "$target"
  cmd_start "$target"
}

cmd_status() {
  local p port
  log "部署根目录 BASE = $BASE"
  for p in main ai; do
    log ""
    log "---- $(proj_label "$p")（$(proj_dir "$p")）----"
    if [ ! -d "$(proj_dir "$p")" ]; then
      warn "未部署：没有目录 $(proj_dir "$p")"
      continue
    fi
    if [ ! -f "$(compose_file "$p")" ]; then
      warn "目录存在但缺编排文件：$(compose_file "$p")"
      continue
    fi
    if [ ! -f "$(env_file "$p")" ]; then
      warn "缺 .env：$(env_file "$p")"
      log "       先执行： cp $(proj_dir "$p")/.env.example $(env_file "$p") && vi $(env_file "$p")"
      log "       必填项：$(required_keys "$p")"
      continue
    fi
    compose "$p" ps || log "      （docker compose ps 失败：docker 是否在运行？）"
    port="$(proj_port "$p")"
    if probe "$p" "$(health_url "$p")"; then
      ok "$(proj_label "$p")：可用（$(health_url "$p")）"
    else
      fail "$(proj_label "$p")：不可用（HTTP ${PROBE_CODE:-000}，$(health_url "$p")）"
    fi
  done
  log ""
  log "提示：只部署主系统时用 bash pm.sh start main ；stop 是反序（先 main 再 ai）。"
  # 查看类命令不把"没部署/没启动"当成脚本失败，结论都在上面的输出里
  return 0
}

cmd_logs() { # <main|ai> [服务名...]
  local proj="${1:-}"
  [ -n "$proj" ] || die_usage "logs 需要指定工程：main | ai（例：bash pm.sh logs ai）"
  case "$proj" in
    main|ai) ;;
    *) die_usage "logs 的第一个参数只能是 main | ai（收到：$proj）" ;;
  esac
  if [ "$#" -gt 0 ]; then shift; fi
  require_docker || return 1
  require_project "$proj" || return 1
  require_env "$proj" || return 1
  log "[INFO] docker compose logs -f $*（Ctrl-C 退出）"
  compose "$proj" logs -f "$@"
}

# ============================================================
# 9. upgrade：load → 解析版本 → 切 .env 的 tag（备份）→ up -d → 健康检查
# ============================================================
cmd_upgrade() { # <main|ai> <镜像包>
  local proj="${1:-}" tarball="${2:-}" ef key img ver old guess
  [ -n "$proj" ] || die_usage "upgrade 需要两个参数：<main|ai> <镜像包>（例：$(upgrade_eg main)）"
  case "$proj" in
    main|ai) ;;
    *) die_usage "upgrade 的第一个参数只能是 main | ai（收到：$proj）" ;;
  esac
  [ -n "$tarball" ] || die_usage "upgrade 缺少镜像包参数（例：$(upgrade_eg "$proj")）"

  if [ ! -f "$tarball" ]; then
    fail "找不到镜像包：$tarball"
    log "      请确认路径（发布包一般解到 $(proj_dir "$proj")/releases/），或用绝对路径："
    log "        $(upgrade_eg "$proj")"
    return 1
  fi
  # 包名与工程对不上时直接拒绝：AI 包 load 进主系统只会白 load 一次并把主系统重启一遍，
  # 反过来把主系统包交给 ai 更糟（ai 拿不到镜像却重启了）。包名是唯一可靠判据，宁可让运维改一次命令。
  guess="$(tarball_project "$tarball")"
  if [ -n "$guess" ] && [ "$guess" != "$proj" ]; then
    fail "镜像包与工程不匹配：$(basename "$tarball") 看起来是 $(proj_label "$guess") 的包"
    log "      请改用： bash pm.sh upgrade $guess <镜像包>"
    return 2
  fi
  require_docker || return 1
  require_project "$proj" || return 1
  require_env "$proj" || return 1

  ef="$(env_file "$proj")"
  key="$(proj_tagkey "$proj")"
  img="$(proj_image "$proj")"

  step "1/4" "$(proj_label "$proj")：docker load 镜像包"
  log "      $tarball"
  if ! docker load -i "$tarball"; then
    fail "docker load 失败：$tarball"
    log "      常见原因：包损坏（传输中断）、包与服务器架构不符（aarch64 包 load 到 x86 机器也能装进去，"
    log "      但起容器会 exec format error）、或磁盘空间不足。"
    return 1
  fi

  step "2/4" "解析版本并校验镜像是否真的 load 进来了"
  # 版本来源：① 环境变量 VER 显式指定（包名不规范/重命名过时用）② 按包名规范解析
  ver="${VER:-$(parse_version "$proj" "$tarball" || true)}"
  if [ -z "$ver" ]; then
    warn "无法从文件名解析版本（$(proj_label "$proj") 期望形如 $(expected_tar "$proj")）"
  elif docker image inspect "${img}:${ver}" >/dev/null 2>&1; then
    ok "镜像已就位：${img}:${ver}"
  else
    warn "镜像包里没有 ${img}:${ver}，本次【不改动】$key"
    log "      · 确认包与工程是否对得上：主系统包只含 pm-backend/pm-frontend，AI 包只含 pm-ai-backend；"
    log "      · 或包名里的版本与镜像 tag 不一致——用 VER=vX.Y.Z 显式指定真实版本。"
    ver=""   # 清空 → 后面按"不切版本"处理
  fi

  step "3/4" "切换 $key（先备份 .env）"
  if [ -z "$ver" ]; then
    warn "本次不修改 $key，将按当前 .env 的值启动（当前 $(env_raw "$ef" "$key" | sed 's/^$/未设置/')）"
    log "      如需切版本，两种办法："
    log "        · 显式指定： VER=vX.Y.Z bash pm.sh upgrade $proj $tarball"
    log "        · 手工编辑： vi $ef"
  else
    set_env_tag "$ef" "$key" "$ver" || { fail "写入 .env 失败：$ef"; return 1; }
  fi

  step "4/4" "启动/升级服务（镜像或环境变量变化时 compose 会自动重建容器）"
  if ! compose "$proj" config --quiet; then
    fail "compose 配置校验未通过：$(compose_file "$proj")（必填项：$(required_keys "$proj")）"
    return 1
  fi
  if ! compose "$proj" up -d; then
    # ⚠️ 高频踩坑（真机上出现过）：从旧布局（pm/app、pm-ai/app）迁到 main/、ai/ 后，
    # 工程名随目录名改变，而编排里的 container_name 是写死的 —— 老容器还在（哪怕只是 docker stop 过）
    # 就会占着名字，新工程创建容器时报 Conflict / already in use。
    # 故失败时把补救办法直接打出来（**只删容器，不动镜像与数据卷**）。
    fail "启动失败：$(proj_label "$proj")（排查： bash pm.sh logs $proj）"
    log "      · 报 container name is already in use（从 pm/app、pm-ai/app 迁过来最常见）："
    log "        老容器还占着写死的容器名，先释放再起 —— docker rm -f pm-backend pm-frontend pm-ai-backend"
    log "        （只删容器：镜像、数据卷、.env 都不受影响；docker stop 不解开占用，必须 rm）"
    log "        ⚠️ 若 $ef 里 APP_STORAGE_TYPE=local，注意附件卷名也带旧工程名前缀："
    log "           旧卷 app_pm_uploads → 新工程会挂 main_pm_uploads（空卷，界面看似附件丢失）"
    log "           要么在本行显式沿用旧卷： UPLOAD_VOLUME=app_pm_uploads ，要么先备份数据；用 obs 时该卷只是过场、无需处理"
    log "      · 镜像没 load 进来？确认本机有 $(proj_image "$proj"):$(env_val "$ef" "$(proj_tagkey "$proj")" latest)"
    log "      · 端口被占用？改 $ef 的 $(proj_portkey "$proj")"
    return 1
  fi
  compose "$proj" ps || true

  log ""
  if wait_health "$proj"; then
    old="$(env_raw "$ef" "$key")"
    if [ -z "$ver" ]; then
      ok "$(proj_label "$proj") 升级流程完成（未切版本，仍运行 $key=${old:-<未设置>}）"
    else
      ok "$(proj_label "$proj") 已升级到 $ver（$key=${old}，原 .env 备份为 ${ef}.bak）"
    fi
    return 0
  fi
  fail "升级后健康检查未通过"
  log "      回滚办法：把 ${ef} 的 $key 改回旧值（旧值就在 ${ef}.bak 里，可直接 cp 回去），"
  log "                然后执行： bash pm.sh restart $proj"
  return 1
}

# ============================================================
# 10. 用法说明
# ============================================================
usage() {
  printf 'pm.sh —— PM 主系统 + AI 能力服务 共用的服务器唯一入口（bash）\n'
  printf '部署根目录 BASE = %s\n\n' "$BASE"
  cat <<'EOF'
用法: bash pm.sh <子命令> [参数...]

子命令:
  start   [main|ai|all]       启动（默认 all）。★先起 ai 再起 main；每步 up -d 后做健康检查
  stop    [main|ai|all]       停止（默认 all）。★反序：先 main 再 ai；容器不存在时跳过（幂等）
  restart [main|ai|all]       重启 = stop + start
  status                      两个工程 docker compose ps + 各自健康探测结论（可用/不可用）
  logs    <main|ai> [服务名]  跟踪日志（docker compose logs -f [服务名]；额外参数原样转发）
  upgrade <main|ai> <镜像包>  加载镜像并切版本（main 改 IMAGE_TAG，ai 改 AI_IMAGE_TAG）
  help                        显示本说明
  <镜像包>                    等价于 upgrade main <镜像包>（兼容旧习惯；
                              若包名是 pm-ai-images-* 则拒绝，并提示改用 upgrade ai）

示例（在部署根目录执行；BASE 就是 pm.sh 所在目录）:
  bash pm.sh                                  # 无参数：打印用法并退出 1
  bash pm.sh start                            # 起 AI + 主系统（推荐顺序已在脚本内固化）
  bash pm.sh start main                       # 只起主系统（服务器上没部署 AI 时）
  bash pm.sh status
  bash pm.sh logs ai                          # 跟 AI 服务日志
  bash pm.sh logs main backend                # 只看主系统的 backend 服务
  bash pm.sh upgrade main main/releases/pm-images-aarch64-v3.6.3.tar.gz
  bash pm.sh upgrade ai   ai/releases/pm-ai-images-aarch64-v1.0.1.tar.gz
  VER=v3.6.3 bash pm.sh upgrade main main/releases/pm-images-改过名.tar.gz
  bash pm.sh main/releases/pm-images-aarch64-v3.6.3.tar.gz    # 旧习惯写法，= upgrade main

服务器布局（BASE = pm.sh 所在目录；脚本按自身位置推导，不硬编码任何路径）:
  <BASE>/pm.sh
  <BASE>/main/  docker-compose.yml  .env  .env.example  服务器部署步骤.txt  releases/
  <BASE>/ai/    docker-compose.yml  .env  .env.example  服务器部署步骤-ai.txt  releases/

环境变量:
  VER=vX.Y.Z   升级时显式指定版本，覆盖"从镜像包名解析"的结果

健康检查与端口:
  main → http://127.0.0.1:$(main/.env 的 WEB_PORT，默认 8080)/api/health
  ai   → http://127.0.0.1:$(ai/.env 的 AI_PORT，默认 8100)/health?with_ocr=false
  轮询约 3s × 20 次（≈60s），失败会打印排查建议，不会静默通过。

退出码: 0 成功 ｜ 1 环境缺文件/镜像包不存在/健康检查失败 ｜ 2 子命令或参数用法错误
EOF
}

# ============================================================
# 11. 子命令分发
# ============================================================
if [ "$#" -eq 0 ]; then
  usage >&2
  fail "缺少子命令"
  exit 1
fi

cmd="$1"
shift

case "$cmd" in
  start)   cmd_start "${1:-all}" ;;
  stop)    cmd_stop  "${1:-all}" ;;
  restart) cmd_restart "${1:-all}" ;;
  status)  cmd_status ;;
  logs)    cmd_logs "$@" ;;
  upgrade) cmd_upgrade "$@" ;;
  help|-h|--help) usage ;;
  *)
    # 兼容旧习惯： bash pm.sh <镜像包>  ==  bash pm.sh upgrade main <镜像包>
    # （旧脚本只有主系统，所以"直接给包"默认就是主系统；这条兼容保留）
    if [[ "$cmd" == *.tar.gz || "$cmd" == *.tgz || "$cmd" == *.tar || -f "$cmd" ]]; then
      if [ "$(tarball_project "$cmd")" = "ai" ]; then
        # 包名一看就是 AI 的，就不"默认升级主系统"了：那样会白 load 一次并重启主系统，纯属帮倒忙
        die_usage "这个包是 AI 能力服务的（$(basename "$cmd")）：请用 bash pm.sh upgrade ai $cmd"
      fi
      log "[INFO] 按旧习惯处理：等价于 \"upgrade main $cmd\""
      cmd_upgrade main "$cmd"
    else
      die_usage "未知子命令：$cmd"
    fi
    ;;
esac
