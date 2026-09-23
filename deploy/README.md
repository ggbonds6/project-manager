# 部署与运维说明

> 数据库为**崖山 YashanDB（Oracle 模式）主备集群**（外部主机，见 §4 环境变量）。

| 目录 | 用途 | 谁在用 |
| --- | --- | --- |
| `deploy/docker` | **生产部署（唯一方式）**：后端 jar + 前端 nginx 一体化镜像 | 两台服务器（离线 `docker load`） |
| `deploy/windows` | 源码模式启动（`mvn spring-boot:run` + Vite） | 开发机本地开发 |
| `ai-backend/`（**独立**） | **AI 能力服务**（Spring Boot 3 / Java 17）：附件解析 / 平台 OCR / 抽取问答 / 向量检索 | 独立镜像 `pm-ai-backend`、单独发版；**不在本发布包内**（见部署手册 §8） |

仓库根目录的 `start-dev.cmd` / `stop-dev.cmd` 为 Windows 通用启动入口（内部调用 `deploy\windows\*`）。
原 `deploy/linux`（Linux 源码直跑脚本）已移除——开发机为 Windows，服务器不存源码，该脚本无执行场景。

> **AI 能力服务不在这套发布物里，不是漏了**：它必须**独立部署、独立发版**（平台 OCR / 大模型 / 向量化都在 GPU 机上，
> 主系统那两台 ARM 服务器没有 GPU）。构建、配置、启动、运维风险见
> [`docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md) §8。

---

## 1. 生产部署：Docker（服务器）

> **服务器不需要源码**：镜像内已含后端 jar（含建库迁移 SQL）、前端产物与 nginx 配置。
> 服务器运行目录只有 2 个文件：`docker-compose.yml` + `.env`（均取自发布包）。
> 完整步骤（含环境准备、验收、升级、回滚）见 [`docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md)。

服务器用的编排是 **`deploy/docker/docker-compose.deploy.yml`**（发布包里改名为 `docker-compose.yml`）：
**没有 `build:` 段** + `pull_policy: never`，镜像缺失时立刻报错，不会误触发联网构建。

```text
# ── 开发机（Windows PowerShell，无需 Git Bash）：出发布包（镜像 + 编排 + .env 模板）──
.\scripts\make-release.ps1 v3.3.0                # 产 dist/pm-release-v3.3.0/
scp dist/pm-release-v3.3.0/pm-images-aarch64-v3.3.0.tar.gz  lhim@<服务器>:/home/lhim/pm/releases/
scp dist/pm-release-v3.3.0/docker-compose.yml dist/pm-release-v3.3.0/.env.example lhim@<服务器>:/home/lhim/pm/app/

# ── 服务器：配 .env → load → up（不加 --build）──
cd /home/lhim/pm/app && cp .env.example .env && vi .env    # 填 YASHAN_PASSWORD / JWT_SECRET / OBS 五项
docker load -i /home/lhim/pm/releases/pm-images-aarch64-v3.3.0.tar.gz
docker compose up -d
docker compose ps && curl http://127.0.0.1:8080/api/health     # 期望 db:"up"
```

> 💡 服务器上还可用 **`bash pm-upgrade.sh <镜像包>`** 一步完成「load → 自动切换 `.env` 的 `IMAGE_TAG` → 重启」，
> 不需要手工改版本号（脚本随发布包下发，见 §1 与《部署与发布全流程手册.md》§4）。

启动后：

- 前端（nginx）：**http://服务器IP:8080**（默认端口，可改 `WEB_PORT`）
- `/api`、`/uploads` 由 nginx 反代到后端容器（后端不对外暴露）
- **附件存储**：生产统一 **华为 OBS**（`APP_STORAGE_TYPE=obs` + `APP_STORAGE_OBS_*`，对象置于桶内 `uploads/` 前缀下），
  详见[《部署与发布全流程手册》附录 A](../docs/部署与发布全流程手册.md)；`APP_STORAGE_TYPE=local` 时用命名卷 `pm_uploads`

**首次建库**：后端启动时自研迁移 Runner 自动执行 `db/migration-yashan/V1~V13` 完成建表与种子（幂等，已执行版本记入 `schema_version`），无需手工导库。
> ⚠️ 迁移是**逐条 DDL 顺序执行、崖山 Oracle 模式隐式提交、没有回滚**：升级到含新迁移的版本前**先备份库**；
> 若中途失败，按《部署与发布全流程手册》§6 处置（先 `docker compose stop` 止血，再人工核 `schema_version` 与已执行语句）。

> 内置账号：admin / jingban01 / lingdao01（密码均 123456）；生产务必先改密并覆盖 `JWT_SECRET`。

### 1.1 AI 能力服务（独立部署，v3.6.0 起）

AI 功能（知识库、悬浮问答、附件自动解析）**依赖一个独立部署的 `pm-ai-backend`**。主系统侧只需要 `.env` 里三个变量：

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `AI_ENABLED` | 入口总开关；`false` 时 `/api/ai/*` 直接返回"AI 服务不可用"，界面如实提示 | `true` |
| `AI_SERVICE_BASE_URL` | **从 pm-backend 容器里**能访问到的地址：同宿主机用 `http://host.docker.internal:8100`；另一台机器填其内网 IP。**绝不能填 `127.0.0.1`**（容器里那是后端自己） | `http://host.docker.internal:8100` |
| `AI_AUTO_PARSE` | 上传后是否自动解析。**首次部署建议 `false`**：先在「AI 与知识库 → 服务自检」页确认可达，再改 `true` | `false`（应用内默认 true） |

**部署顺序（推荐）**：
1. 先发主系统（本文件 §1）→ 打开「AI 与知识库 → 服务自检」，此时应显示 **AI 服务不可用**（这是正常的，说明开关生效、不是"未找到"）；
2. 再发 AI 服务——**和主系统同一套脚本**（在仓库根执行）：
   ```powershell
   .\scripts\make-release.ps1 v1.0.0 -Project ai     # 出 dist/pm-ai-release-v1.0.0/（镜像包 + 编排 + .env 模板 + 部署步骤）
   ```
   服务器上：`cp .env.example .env`（填 `LLM_API_KEY`/`AI_IMAGE_TAG`/`AI_BIND_IP`）→ `docker load` → `docker compose up -d` → 自检 `/health?with_ocr=true&with_vec=true`；
   完整步骤见《部署与发布全流程手册》§8；
3. 最后按需把 `AI_AUTO_PARSE` 改成 `true`（改完 `docker compose up -d` 生效，无需重建镜像）。

> ⚠️ 两个已知边界：① AI 服务**当前没有任何入站鉴权**，端口不要对全网开放（`ai-backend/docker-compose.deploy.yml` 里用 `AI_BIND_IP` 绑定内网 IP + 防火墙只放行主系统服务器）；
> ② 双机负载均衡时**建议 AI 服务集中部署一台**——它的文档库/向量缓存是本地文件，两台各跑一个会让索引各自演化，同一个问题问到不同机器答案不一致。

> 📌 **双机（两台 ARM 服务器，独立运行、指向同一崖山库、上层网关负载均衡）部署**：
> 每台一份 Docker Compose 即可，含附件共享存储 / JWT 一致性 / 冒烟清单等完整步骤，
> 见 [`docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md) **附录 A · 生产拓扑与选型（双机 ARM）**。

---

## 2. 开发机本地试跑：Docker

用仓库内**含 `build:` 段的** `deploy/docker/docker-compose.yml`（**服务器不要用这一份**）：

```bash
cd project-manager
cp deploy/docker/.env.example deploy/docker/.env   # ⚠️ .env 必须与 docker-compose.yml 同目录
#   编辑 .env：填 YASHAN_PASSWORD；本机测试建议 APP_STORAGE_TYPE=local、WEB_PORT=8088
docker compose -f deploy/docker/docker-compose.yml up -d --build
```

### 2.1 改完代码一键重建（日常开发用这个）

```powershell
.\scripts\dev-reload.ps1              # 重建前后端并重启（默认）
.\scripts\dev-reload.ps1 backend      # 只重建后端（改了 Java / 迁移 SQL）
.\scripts\dev-reload.ps1 frontend     # 只重建前端（改了 tsx / ts / css）
```

> 开发机脚本都是 Windows 原生 `.ps1`（Windows PowerShell 5.1 或 `pwsh` 7 均可，**不需要 Git Bash**）；
> 若执行策略为 Restricted，用 `powershell -ExecutionPolicy Bypass -File scripts\dev-reload.ps1`。

脚本依次做：`docker compose build` → `up -d`（compose 检测到镜像变化会自动重建容器）→
轮询 `/api/health`，就绪后打印访问地址与容器状态。

- **不需要手动 `stop`**，也不需要重新生成发布包（发布包是发版才用的，见 §1）；
- 只改一个端时用 `backend` / `frontend` 能省一半时间；
- 首次构建或改了依赖（`pom.xml` / `package.json`）会明显变慢，属正常。

### 2.2 查看状态与日志

```bash
docker compose -f deploy/docker/docker-compose.yml ps
docker compose -f deploy/docker/docker-compose.yml logs -f backend
```

---

## 3. 开发机本地：源码模式（Windows）

```cmd
:: 启动（需先 set YASHAN_MASTER_IP/YASHAN_STANDBY_IP/YASHAN_DB/YASHAN_USER/YASHAN_PASSWORD）
deploy\windows\start-dev.cmd

:: 停止
deploy\windows\stop-dev.cmd
```

或直接双击仓库根目录的 `start-dev.cmd`。后端 8080、前端 Vite 5173。

---

## 4. 环境变量速查

| 变量 | 用途 | 默认 |
| --- | --- | --- |
| `YASHAN_MASTER_IP` | 崖山主库 IP | 10.254.212.106 |
| `YASHAN_STANDBY_IP` | 崖山备库 IP | 10.254.212.107 |
| `YASHAN_DB` | 业务库名（Oracle 模式下的 schema 名） | **PM**（大写） |
| `YASHAN_USER` | 应用账号（业务账号 `pm`，具 CONNECT/RESOURCE；勿用 sys） | pm |
| `YASHAN_PASSWORD` | 应用账号密码（**必填，勿提交到 Git**） | - |
| `JWT_SECRET` | JWT 密钥（生产必改；两机必须相同） | 示例值 |
| `UPLOAD_DIR` | 附件目录（Docker 内固定 /uploads） | ./uploads |
| `APP_STORAGE_TYPE` | 附件存储：`local`（本地卷/共享盘）\| `obs`（华为 OBS） | local |
| `WEB_PORT` | 前端对外端口（Docker） | 8080 |

> 连接使用驱动级高可用：`jdbc:yasdb:primary://主,备/库?poolTimeout=60&failover=on&failoverType=session&failoverMethod=basic&failoverRetries=5&failoverDelay=2`
> ——驱动自动识别主节点并支持故障自动重连（TAF）；主备切换对"进行中的事务"不透明，事务会失败回滚，关键写操作请重试。

---

## 5. 数据库版本与迁移

- 崖山 YashanDB（Oracle 模式）；迁移脚本位于 `backend/src/main/resources/db/migration-yashan`（V1–V13），
  由后端启动时自研 `YashanMigrationRunner` 顺序执行（替代 Flyway，崖山官方不支持 Flyway）。
- 已执行版本记录在库表 `schema_version`；新增表结构 = 在该目录新增 `V{n}__xxx.sql` 即可。
- 当前业务表（**16 张**）：sys_user / dict_item / phase_template / phase_tpl / project / project_contract /
  project_phase / contract / payment / attachment / attachment_upload_task / project_division / project_overview /
  operate_log / attachment_ai_task / ai_ask_log（主键为 identity 自增；后两张为 v3.6.0 的 AI 集成表）。

## 6. Git 协作（SSH）

见 `docs/GITHUB-SSH-setup.md`：配置 ssh key → 切换 remote 为 SSH → 验证连通 → 推送。
