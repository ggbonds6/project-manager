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

> 服务器不需要源码：镜像内已含后端 jar（含建库迁移 SQL）、前端产物与 nginx 配置；服务器**只 `docker load`，绝不 `--build`**。
> 部署根 `/home/lhim/pm/` 下是唯一入口 `pm.sh` + 两个互相隔离的项目目录 `main/`（主系统）、`ai/`（AI 能力服务）。
> 下面与《部署与发布全流程手册.md》**§0 是同一套命令**（两处不会互相矛盾）；`v3.6.3` / `v1.0.1` 换成你本次的版本号。

**主系统**（每台服务器各做一遍）
```powershell
# 第 1 步 · 出包（开发机，仓库根）
.\scripts\make-release.ps1 v3.6.3
```
```bash
# 第 2 步 · 上传（开发机，仓库根；整棵树铺到部署根）
scp -r dist/pm-release-v3.6.3/*  lhim@pdmsappgh:/home/lhim/pm/
scp -r dist/pm-release-v3.6.3/*  lhim@ai-kingbase-gh:/home/lhim/pm/
```
```bash
# 第 3 步 · 配 .env（必填 YASHAN_PASSWORD、JWT_SECRET（两台必须一致）、OBS 五项）
cd /home/lhim/pm/main
cp .env.example .env
vi .env
```
```bash
# 第 4 步 · 启动（load + 切 IMAGE_TAG + 起容器 + 健康检查，一条命令）
cd /home/lhim/pm
bash pm.sh upgrade main main/releases/pm-images-aarch64-v3.6.3.tar.gz
```
```bash
# 第 5 步 · 验收（期望 db:"up"）
cd /home/lhim/pm
bash pm.sh status
curl -s http://127.0.0.1:8080/api/health
```

**AI 能力服务**（要 AI 功能才做；不做则跳过，主系统照常跑）
```powershell
# 第 1 步 · 出包（开发机，仓库根；AI 版本号与主系统不绑定）
.\scripts\make-release.ps1 v1.0.1 -Project ai
```
```bash
# 第 2 步 · 上传（开发机；只传到跑 AI 的那台）
scp -r dist/pm-ai-release-v1.0.1/*  lhim@<AI服务器>:/home/lhim/pm/
```
```bash
# 第 3 步 · 配 .env（必填 LLM_API_KEY；AI_IMAGE_TAG 已预填；AI_BIND_IP=本机内网IP）
cd /home/lhim/pm/ai
cp .env.example .env
vi .env
```
```bash
# 第 4 步 · 启动
cd /home/lhim/pm
bash pm.sh upgrade ai ai/releases/pm-ai-images-aarch64-v1.0.1.tar.gz
```
```bash
# 第 5 步 · 自检 + 让主系统连上它（自检期望 200）
cd /home/lhim/pm
bash pm.sh status
curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:8100/health?with_ocr=false"
# —— 下面两条在【主系统服务器】执行：把 AI 地址告诉主系统 ——
vi /home/lhim/pm/main/.env      # AI_SERVICE_BASE_URL=http://<AI服务器内网IP>:8100
cd /home/lhim/pm && bash pm.sh restart main
```

启动后：浏览器打开 `http://<服务器IP>:8080`（默认端口，可改 `WEB_PORT`）；`/api`、`/uploads` 由 nginx 反代到后端容器（后端不对外暴露）。
**首次建库**由后端启动时的迁移 Runner 自动执行 `db/migration-yashan/V1~V14`（幂等，已执行版本记入 `schema_version`），无需手工导库；迁移**逐条执行、隐式提交、没有回滚** → 升级到含新迁移的版本前先备份库（失败处置见手册 §6）。
内置账号：admin / jingban01 / lingdao01（密码均 123456）；生产务必先改密并覆盖 `JWT_SECRET`。

> ⚠️ **两个包各 `upgrade` 一次**，且 `start` 不做 load；日常操作（start / stop / restart / status / logs / upgrade）与最常见的 3 个报错处置见手册 **§0.3 / §0.5**，验收见 §5、AI 接入细节见 §8、迁移与排查见 §6。

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

- 崖山 YashanDB（Oracle 模式）；迁移脚本位于 `backend/src/main/resources/db/migration-yashan`（V1–V14），
  由后端启动时自研 `YashanMigrationRunner` 顺序执行（替代 Flyway，崖山官方不支持 Flyway）。
- 已执行版本记录在库表 `schema_version`；新增表结构 = 在该目录新增 `V{n}__xxx.sql` 即可。
- 当前业务表（**16 张**）：sys_user / dict_item / phase_template / phase_tpl / project / project_contract /
  project_phase / contract / payment / attachment / attachment_upload_task / project_division / project_overview /
  operate_log / attachment_ai_task / ai_ask_log（主键为 identity 自增；后两张为 v3.6.0 的 AI 集成表；v3.7.0 的 V14 给 `ai_ask_log` 加 `biz_query_count`/`biz_entities` 两列——**只加列，张数不变**）。

## 6. Git 协作（SSH）

见 `docs/GITHUB-SSH-setup.md`：配置 ssh key → 切换 remote 为 SSH → 验证连通 → 推送。
