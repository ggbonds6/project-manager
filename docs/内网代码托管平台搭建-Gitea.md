# 内网代码托管与发布平台搭建（Gitea，替代 GitHub）

> **文档性质：定稿手册（v1.0，2026-09-09）。** 主体（§1~§8）为搭建与迁移的操作依据；
> §1 选型保留简短决策说明（便于维护者理解取舍），无过程性讨论。修订见文末。

> 目标：新增一台内网服务器，自建代码托管 + Release 发布分发，使开发/部署链路不再依赖 github.com——
> 日常 push、版本发布（Release 资产）、服务器升级下载全部走内网。
> 适用：本项目（`project-manager`，双机 ARM 部署，见 [`双机ARM服务器独立部署方案.md`](双机ARM服务器独立部署方案.md) §5 升级流程）。

---

## 1. 选型

| 候选 | 结论 | 理由 |
| --- | --- | --- |
| **Gitea** | ✅ **推荐** | Go 单二进制，内存占用小（数百 MB 级）；**官方提供 arm64** 二进制与容器镜像；自带 **Release（发布资产下载）**、用户/组织/私有仓库、Webhook、Git LFS；操作习惯与 GitHub 高度一致，迁移平滑 |
| GitLab CE | 次选（重） | 功能全但内存要求高（建议 ≥4GB）；小型内部团队偏重 |
| **Nexus / Artifactory** | ❌ **赛道不同，不替代代码托管** | 属**制品/依赖仓库**（Maven/npm 代理与归档、Docker 托管等），不管 git 代码托管与协作；且 Nexus 官方仅 x86_64 镜像，**本项目服务器为 ARM，无官方 arm64 支持**，排除 |
| Gogs / Gitea 前身 | 不选 | 迭代慢，功能少于 Gitea |
| 纯 `git --bare` + 共享盘 | 不选 | 无权限/Web/Release，无法替代 GitHub 分发 |

> **决策备注**：本项目选 Gitea 仅承担"代码托管 + Release 资产分发"（对照 GitHub 本体）。
> Nexus/Artifactory 属制品/依赖仓库（对照 GitHub Packages），非代码托管，且 Nexus 官方无 arm64 镜像，
> 本项目**不引入**；若后续需要内网 Maven/npm 依赖代理再单独评估（详见附录 A）。

> 是否同时搭**私有 Docker Registry**：若升级走"镜像 tar 下载"（当前推荐）则**暂不需要**；
> 以后机器变多、改为"服务器直接 pull 镜像"时，可在同机追加一个 `registry:2` 容器（见 §6，可选）。

---

## 2. 服务器准备与拓扑

- 新增一台服务器（Linux；x86_64 或 arm64 均可，Docker 环境即可），建议配置：2C4G 起步、≥50GB 磁盘（代码+Release 资产+备份）。
- 拓扑：

```text
┌─────────────┐  git/ssh(22) + https(443)   ┌──────────────────────┐
│ 开发机/服务器 │ ──────────────────────────▶ │ 内网 Gitea 服务器       │
└─────────────┘     git.pm.internal           │  nginx(https) → gitea  │
                                             │  容器：gitea + (可选 db) │
                                             └──────────────────────┘
```

- DNS：内网 DNS 加一条 `git.pm.internal → <服务器IP>`；没有 DNS 就在各客户端/服务器 `/etc/hosts` 加一行。

---

## 3. Docker Compose 部署 Gitea

> 以下用官方镜像 `gitea/gitea`（latest 拉取自动匹配当前架构，含 arm64）。
> 规模小可用内置 SQLite（零额外容器）；多人并发/要更稳推荐 Postgres（注释给出）。

```bash
mkdir -p /opt/gitea/{data,config}
cat > /opt/gitea/docker-compose.yml <<'YAML'
services:
  gitea:
    image: gitea/gitea:1.22
    container_name: gitea
    restart: unless-stopped
    environment:
      - USER_UID=1000
      - USER_GID=1000
      - GITEA__server__DOMAIN=git.pm.internal        # 对外域名/IP
      - GITEA__server__ROOT_URL=https://git.pm.internal/
      - GITEA__server__SSH_DOMAIN=git.pm.internal
      - GITEA__server__SSH_PORT=2222                 # 对外 SSH 端口（宿主）
      - GITEA__server__LFS_START_SERVER=true         # 大文件（可选）
      - GITEA__database__DB_TYPE=sqlite3             # 小规模；多人可换 postgres
      # DB_TYPE=postgres 时另配：
      # - GITEA__database__HOST=db:5432
      # - GITEA__database__NAME=gitea
      # - GITEA__database__USER=gitea
      # - GITEA__database__PASSWD=xxxx
    volumes:
      - ./data:/data                               # 代码库 + 配置 + 附件（备份对象）
    ports:
      - "2222:22"      # SSH（对外 2222，避免与服务器系统 SSH 22 冲突；也可直接 22）
      - "127.0.0.1:3000:3000"  # Web 只在本机，由 nginx 反代对外
    # 可选 postgres 服务：
    # db:
    #   image: postgres:16-alpine
    #   restart: unless-stopped
    #   environment: { POSTGRES_DB: gitea, POSTGRES_USER: gitea, POSTGRES_PASSWORD: xxxx }
    #   volumes: [ ./pgdata:/var/lib/postgresql/data ]
YAML

cd /opt/gitea && docker compose up -d
```

- 反向代理（系统 nginx 或再加一个 nginx 容器）：把 `443/80` 转发到 `127.0.0.1:3000`。
- 首次打开 `https://git.pm.internal` 完成安装向导：管理员账号、站点名；**确认 ROOT_URL 与 SSH 端口已按上面环境变量生效**（向导里的值若被环境变量覆盖则不改）。

---

## 4. HTTPS 证书（内网自签即可）

内网服务不需要公网 CA；两种做法：

1. **自建内网 CA（推荐，一劳永逸）**：用 `mkcert` 或 `openssl` 生成 CA → 签发 `git.pm.internal` 证书 → 各开发机/服务器把 **CA 证书加入信任库**（`sslVerify` 全局通过），此后 git/浏览器都不再告警。
2. 简单自签单张证书：nginx 用自签证书，客户端 git 对该域名关闭校验（`git -c http.sslVerify=false clone …` 或 `http.sslCAInfo` 指定 CA）。不推荐全局关校验。

> 服务器（`pm-upgrade.sh` 下载 Release 等）如需 HTTPS 拉取，同样把 CA 导入系统信任库即可，脚本无需关校验。

---

## 5. 仓库迁移与发布流程切换

```bash
# 1) 在 Gitea 建组织/仓库（网页或 API），例如 组织 pm-org / 仓库 project-manager（私有）

# 2) 本机（开发机）把现有仓库推过去（保留全部历史；首次全量，之后增量）
git remote add internal git@git.pm.internal:2222/pm-org/project-manager.git
git push -u internal main
#    或镜像导入（一次性把所有分支/tag 搬过去，适合完整接管）：
#    git push --mirror internal

# 3) 日常：origin 可保留 github 双推，或切换 origin
#    git remote set-url origin git@git.pm.internal:2222/pm-org/project-manager.git

# 4) 发布流程（替代 GitHub Release）：
#    - 本地打 tag + 推送：git tag v1.0.0 && git push internal v1.0.0
#    - 在 Gitea 仓库页 Releases → 新建 Release(v1.0.0) → 上传 §发布资产
#      （pm-<ver>-arm64-images.tar.gz + pm-<ver>-deploy.tar.gz）
#    - 服务器升级下载源改为内网（对应双机部署手册 §3.3 的下载命令，替换其中的仓库 URL）：
#        curl -fLO https://git.pm.internal/pm-org/project-manager/releases/download/v1.0.0/pm-v1.0.0-arm64-images.tar.gz
#      然后照常 ./pm-upgrade.sh（脚本不绑仓库，天然支持内网）
```

---

## 6.（可选）同机追加私有镜像仓库 registry

若以后想"服务器直接 docker pull"，在 `/opt/gitea/docker-compose.yml` 或独立 compose 追加：

```yaml
  registry:
    image: registry:2
    container_name: registry
    restart: unless-stopped
    volumes: [ ./registry-data:/var/lib/registry ]
    ports: [ "127.0.0.1:5000:5000" ]   # 同样由 nginx 反代 https
```

- 开发机/构建机：`docker tag pm-backend:v1.0.0 git.pm.internal:443/pm/backend:v1.0.0 && docker push …`
- 服务器：`docker pull git.pm.internal/pm/backend:v1.0.0 && docker compose up -d`（compose 镜像名写全内网地址）。
- registry 需配 TLS（复用同一张证书）与基本认证；内网无 DNS 泛域或自签时注意 docker daemon 的 `insecure-registries` 配置（**不建议**，优先走 CA 信任的 https）。

---

## 7. 备份（重要）

Gitea 的数据全部在 `./data`（git 仓库、LFS、配置、附件；若用 Postgres 另有 pgdata）：

```bash
# 每日/每周：打包 + 可选异地
tar -czf gitea-backup-$(date +%F).tar.gz -C /opt/gitea data
# 或 gitea dump（官方工具，含仓库+db 一致性快照）：
docker exec gitea gitea dump -c /data/gitea/conf/app.ini --file /backup/gitea-dump.zip
```

> Release 资产与镜像 tar 已属于项目发布物，可一并纳入备份或保留在磁盘归档目录。

---

## 8. 安全检查清单

- [ ] Gitea 仅内网可达；对外只开放 443（https）与 2222（ssh）端口
- [ ] 管理员/组织成员最小权限；仓库私有
- [ ] HTTPS 用内网 CA，客户端 CA 受信；不在服务器全局关闭 git sslVerify
- [ ] 定期备份 `./data`（+ pgdata）并验证可恢复
- [ ] SSH 密钥仅分发到需要 clone/push 的账号；服务器（拉取 Release）可只走 https+只读 token，不开 ssh

---

## 9. 与双机部署方案的关系

- 服务器**无需**在此托管平台放开发仓库源码即可运行：`pm-upgrade.sh` 只消费 Release 资产（镜像 tar + 部署包）。
- 完整升级链路变为：开发机打 tag → Gitea Release 上传资产 → 服务器 curl 内网下载 → `./pm-upgrade.sh`。
- 本平台搭好前的过渡期，仍可继续用 GitHub Release（下载命令见双机部署手册 §3.3，仅换 URL）。

---

## 修订记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v1.0 | 2026-09-09 | 定稿手册：Gitea 选型（含与 Nexus 制品库的职责澄清）、compose 部署、HTTPS 内网 CA、仓库迁移与 Release 发布切换、可选 registry、备份与安全清单 |
