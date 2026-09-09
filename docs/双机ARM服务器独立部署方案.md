# 生产部署手册：双机 ARM 独立部署（崖山 YashanDB + OBS + 内网 Gitea 发布）

> **文档性质：定稿手册（v1.0，2026-09-09）。** 主体（§1~§6）是部署/升级/运维的唯一操作依据；
> 方案选型对比、备选路线与问答记录统一放在**附录 A（过程记录）**，修订历史见**附录 B**。

---

## 1. 定稿架构

| 项 | 定稿 |
| --- | --- |
| 部署形态 | **每台服务器一份 Docker Compose**（前端 nginx 容器 + 后端 Spring Boot 容器），两台完全同构、各自独立对外服务 |
| 服务器 | 两台 **ARM64**（Linux，已装 Docker Engine + Compose v2） |
| 数据库 | 外部**崖山 YashanDB（Oracle 模式）主备**（主 10.254.212.106 / 备 10.254.212.107:1688，库 `project_manager`，账号 `pm`）——两台指向同一套，驱动级 primary + TAF 高可用 |
| 附件存储 | **华为 OBS**（通用 S3 协议、忽略证书校验），桶私有、不匿名读；附件下载/预览一律经后端带 token 接口 |
| 代码托管 / 发布 | 内网 **Gitea**：git 托管 + Release 资产分发；服务器升级 = 下载 Release → `scripts/pm-upgrade.sh` |
| 入口 | 上层网关负载均衡（用户自建），健康检查 **`/api/health`**（db:up 才在池内） |
| 安全基线 | 两机 `.env` 的 `JWT_SECRET` **必须一致**（否则跨机验签 401）；业务账号勿用 sys；密码/密钥不进仓库与镜像 |

> **代码状态**：附件存储抽象（local/OBS）已于 **v3.1 落地并提交**，§3 步骤可直接执行；OBS 模式实库验证在服务器首启时按 §5 冒烟执行。

---

## 2. 部署前必须满足的前提（逐项打勾）

1. **[代码] 附件存储 OBS 实现已合入并提交**（v3.1：`AttachmentStorage` 抽象，`app.storage.type=local\|obs`，OBS 走 esdk-obs-java：path-style + 忽略证书校验 + **对象前缀 `uploads/`**；存量附件已由人工上传至桶 `pdmsbucket/uploads/2026/...`，与 `attachment.file_path` 通过前缀精确对应）。**obs 模式实库验证**在服务器部署完成后执行（§5 冒烟 #4：obs 模式起后端 → 下载存量附件与源文件字节比对）。
2. **[OBS] 桶与凭证**：桶名（私有读写）、AK/SK、endpoint（内网域名/IP，含协议）；已确认 SDK 侧 `pathStyle=true`、**忽略证书校验**（esdk-obs-java `validateCertificate` 默认 false）。
3. **[数据库] 崖山连通**：两机到 10.254.212.106/.107 的 1688 可达；`pm` 账号可连（库已完成 V1~V8 初始化与数据迁移）。
4. **[密钥] `JWT_SECRET`**：生成一个 ≥32 字节随机串，两机 `.env` 填写**相同**值。
5. **[发布源] 内网 Gitea 就绪**（或过渡期使用 GitHub Release）：仓库与 Release 上传权限可用。
6. **[Docker] 两机** `docker compose version` 可用；磁盘预留镜像与 Release 资产空间。

---

## 3. 服务器部署步骤（两台各执行一遍）

### 3.1 准备运行目录
```bash
sudo mkdir -p /opt/pm/app /opt/pm/images /opt/pm/releases
# 从发布资产包（pm-<ver>-deploy.tar.gz）解压 compose 与配置模板到 /opt/pm/app
tar -xzf pm-<ver>-deploy.tar.gz -C /opt/pm/app
ls /opt/pm/app        # docker-compose.yml / frontend-nginx.conf / .env.example / pm-upgrade.sh
```

### 3.2 配置 `.env`（两机除注释本机外内容一致）
```bash
cp /opt/pm/app/.env.example /opt/pm/app/.env && vi /opt/pm/app/.env
```
```bash
# ---------- 崖山数据库 ----------
YASHAN_MASTER_IP=10.254.212.106
YASHAN_STANDBY_IP=10.254.212.107
YASHAN_DB=project_manager
YASHAN_USER=pm
YASHAN_PASSWORD=<业务账号密码>

# ---------- 安全（两机必须相同，≥32 字节随机串） ----------
JWT_SECRET=<相同随机串>

# ---------- 附件：OBS（生产定稿） ----------
APP_STORAGE_TYPE=obs                 # local=本地盘（默认）| obs=华为 OBS
APP_STORAGE_OBS_ENDPOINT=https://obs.lhim.com
APP_STORAGE_OBS_BUCKET=pdmsbucket
APP_STORAGE_OBS_AK=<ak>
APP_STORAGE_OBS_SK=<sk>
APP_STORAGE_OBS_PREFIX=uploads       # 对象在桶内 uploads/ 前缀下（与已上传存量结构一致）；桶根直存可置空
# 本地盘/NFS 过渡期才需要：UPLOAD_VOLUME=/mnt/pm-uploads

# ---------- 对外端口 ----------
WEB_PORT=8080
```

### 3.3 加载镜像并启动
```bash
cd /opt/pm/releases
curl -fLO https://git.pm.internal/<org>/project-manager/releases/download/<ver>/pm-<ver>-arm64-images.tar.gz   # 或 GitHub Release / 人工拷贝
bash /opt/pm/app/pm-upgrade.sh pm-<ver>-arm64-images.tar.gz /opt/pm/app
# 脚本自动：docker load → compose config 校验 → compose up -d
```

### 3.4 本机验证
```bash
curl http://127.0.0.1:8080/api/health        # {"code":0,...,"db":"up"}
# 登录 admin → 项目列表/详情；上传附件并在另一台下载验证（OBS 模式天然跨机一致）
```

### 3.5 第二台
重复 3.1~3.4。**唯一注意**：`.env` 中 `JWT_SECRET`、崖山连接、OBS 凭证与第一台**完全一致**。

---

## 4. 网关负载均衡

- 上游：`http://<服务器A>:8080`、`http://<服务器B>:8080`。
- 健康检查：**`GET /api/health`**，`data.db == "up"` 才保留在池（同时反映后端进程与数据库连通）。
- TLS 终止在网关；前端容器已透传 `X-Forwarded-Proto`；JWT 在请求头/本地存储，与网关会话无关。
- 单机故障时另一台独立可用；写操作在崖山主备故障切换瞬间可能回滚（TAF 对进行中事务不透明），关键操作失败重试即可。

---

## 5. 升级流程（定稿：内网 Gitea Release）

```text
开发机：git tag v1.1.0 → push 内网 Gitea
     → Gitea Releases 上传 pm-v1.1.0-arm64-images.tar.gz + pm-v1.1.0-deploy.tar.gz
两台服务器：curl 内网下载 → bash pm-upgrade.sh pm-v1.1.0-arm64-images.tar.gz /opt/pm/app
```
- `.env` 不随升级覆盖（保留在两机）；如需新增配置项在发布说明中列明并手工合并。
- 服务器日常运行零外网依赖，仅升级时访问内网 Gitea。

---

## 6. 运维要点

| 对象 | 责任与做法 |
| --- | --- |
| 数据库 | 崖山侧备份（每日 + 按 RPO）；两机无本地数据库 |
| 附件 | 在 **OBS**：启用版本化与生命周期；桶私有、不开放匿名读；下载走后端带 token 接口 |
| 应用日志 | `docker compose -f /opt/pm/app/docker-compose.yml logs -f backend` |
| 升级 | §5 流程；先升一台验证再升第二台 |
| 巡检 | 每台 `/api/health`；网关健康探测状态；`docker compose ps` |
| 凭据 | 账号密码/JWT/OBS SK 只在两机 `.env` 与 Gitea/密码库，不入仓库 |

---

## 附录 A：过程记录（非操作必读，仅供追溯）

### A.1 部署形态选型（Docker vs 裸机 vs 编排）
- 结论：**Docker Compose ×2**。原因：两台同构、升级/回滚成本低、现有 `deploy/docker/` 资产直接复用。
- 备选：裸机 jar + 前端产物 + systemd/nginx（需另写一整套生产启动体系，`deploy/linux` 脚本为开发模式不可直上）；K8s/K3s（两台过重）。
- 细节对比见本仓库 git 历史（提交 `c78f43e` 前身文档）。

### A.2 附件存储路线（本地盘/NFS → 定稿 OBS）
- 双机若用本地盘必须共享存储（A 传 B 读 404），曾计划 NFS。
- 核实代码后确认切对象存储为"后端内部替换"：前端附件 IO 全部收口于 `/api/attachments/{id}/download`（不使用 `/uploads` 静态直链），故 **前端 0 改动、数据库 0 迁移**（`file_path` 即对象 key）。删除本就只做 DB 逻辑删除、不物理删文件，天然兼容。
- 改造量：`AttachmentStorage` 接口 + Local/OBS 双实现 ≈120~180 行 + controller 两段 20~30 行 diff + 配置。→ **定稿 OBS，免除 NFS**。
- OBS 客户端定为华为官方 `esdk-obs-java`：`pathStyle(true)` + `validateCertificate(false)`（默认即忽略证书）。

### A.3 发布源（GitHub → 内网 Gitea）与制品库澄清
- 早期发布模型基于 GitHub Release 下载；定稿为内网 **Gitea**（官方 arm64、自带 Release 资产分发）。
- 澄清：Nexus/Artifactory 属制品/依赖仓库（非代码托管），且 Nexus 官方仅 x86_64（ARM 无官方镜像），本项目不引入。
- 搭建 Gitea 见独立手册《内网代码托管平台搭建-Gitea.md》。

### A.4 待办（落地顺序）
1. 后端附件存储抽象 + OBS 实现（含 `app.storage.type` 配置与 `APP_STORAGE_OBS_*` 环境变量）。
2. Gitea 服务器部署 + 仓库迁移 + 首个 Release。
3. 双机生产部署执行 + §5 冒烟验收。

---

## 附录 B：修订记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v1.0 | 2026-09-09 | 定稿为手册体：主体=部署/升级/运维；OBS 与内网 Gitea 敲定进 §1~§2；选型与问答移附录 A（此前评估过程版本保留于 git 历史） |
