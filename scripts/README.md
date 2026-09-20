# 脚本与操作清单（唯一入口）

> **本文件是仓库里所有脚本的唯一索引。** 先看 §1 场景速查，再按需查后面分组明细。
> 维护约定见 §7：**新增/删除脚本必须同步本文件**，否则视为未完成。

---

## 0. 环境与通用约定

### 0.1 脚本分布（为什么不在一个目录）

| 目录 | 职责 | 谁执行 |
| --- | --- | --- |
| `scripts/` | 开发机工具：发版打包、演示数据、数据库手工查改 | 开发机（仓库根） |
| `deploy/windows/` | **源码模式**启动/停止前后端（本地开发） | 开发机 |
| `deploy/docker/` | **镜像模式**部署资产（Dockerfile + compose） | 开发机构建、服务器运行 |
| `ai-backend/scripts/` | AI 能力服务的质量门与端到端验收 | 开发机（AI 模块根） |
| `local/`（**已 gitignore**） | **本机专用资产**：数据库导出与口令清单、两个本机启动助手（`start-project-local.cmd` / `stop-project-local.cmd`） | 本机（双击可用；绝不入库） |
| 根目录 `start-dev.cmd` / `stop-dev.cmd` | 一键启动/停止的**通用入口**（转发到 `deploy/windows/`） | 开发机（双击即用） |

> 生产服务器上**只有发布包里的 `docker-compose.yml` + `.env` + `pm-upgrade.sh`**（服务器不存源码，也不需要本目录任何脚本）。

### 0.2 Shell 要求（**先看这条，否则会白折腾**）

| 扩展名 | 需要什么 | 本机现状（实测） |
| --- | --- | --- |
| `.cmd` / `.bat` | Windows 原生，双击或 cmd 直接跑 | ✅ 可用 |
| `.ps1` | PowerShell 7（`pwsh`）。**Windows PowerShell 5.1 不支持 `Invoke-RestMethod -Form`**，端到端验收脚本会失败 | ✅ `pwsh` 已装；5.1 下请用 `pwsh -File xxx.ps1` 显式调用 |
| `.sh` | **Git Bash 或 WSL**（本项目的 `.sh` 是 bash 脚本） | ⚠️ **本机没有 Git Bash**：PATH 上的 `bash` 只是 WSL 桩，`.sh` **目前跑不了** |
| `.mjs` | Node.js 18+ | ✅ 可用（用 `node xxx.mjs`；PowerShell 里若 `npm` 被策略拦，用 `npm.cmd`） |

**结论（本机 Windows 开发）**：
- 能用：全部 `.cmd`、`.ps1`（走 `pwsh`）、`.mjs`；
- 不能用：`scripts/*.sh`（`make-release.sh` / `dev-reload.sh` / `db-sql.sh` / `pm-upgrade.sh`）；
- 要跑 `.sh` 的两种办法：① 安装 [Git for Windows](https://git-scm.com/download/win)（推荐，装完 `bash scripts/xxx.sh` 即可）；
  ② 用 WSL 发行版（`wsl bash scripts/xxx.sh`）。
  **注意**：`.sh` 里的路径与换行按 POSIX 写，不要在 PowerShell 里直接 `.\xxx.sh` 执行。

### 0.3 三条硬约定（已写入团队协作记忆）

1. 🚫 **不主动重建镜像**：`dev-reload.sh` / `make-release.sh` 耗时且会重启容器 —— **需要时先问**。
2. 🚫 **不为前端改动起浏览器**（也不要为此装浏览器自动化）：前端做到「类型检查 + 构建 + 数据/接口核对」即收尾，
   交互效果由用户刷新页面确认。
3. 🚫 **`demo-reset.sql` 只允许用于开发机/演示环境**：它**物理删除**演示数据（含附件元数据），生产环境走保留策略。

---

## 1. 场景速查（最常用的一张表）

| 我要做什么 | 命令 | Shell | 说明 |
| --- | --- | --- | --- |
| 起本地开发环境（前后端源码模式） | `start-dev.cmd`（或 `deploy\windows\start-dev.cmd`） | cmd | 后端 :8080、前端 :5173（`--host` 支持局域网） |
| 停本地开发环境 | `stop-dev.cmd` | cmd | 按端口停 8080/5173；**不碰外部崖山库** |
| 起 AI 能力服务（本地） | `cd ai-backend && mvn -B -DskipTests package`<br>`java -jar target/pm-ai-backend-1.0.0-SNAPSHOT.jar` | cmd | 默认 :8100；本地调试常用 `--server.port=8101` 避开占用；凭据放 `ai-backend/.env` |
| AI 服务质量门（编译 + 单测） | `cd ai-backend && scripts\check.cmd`（或 `mvn -B test`） | cmd | 当前基线 **81 用例** |
| AI 服务端到端验收（真实平台网关） | `pwsh -File ai-backend\scripts\verify-e2e.ps1` | pwsh 7 | 自动起服务→自检→平台 OCR→抽取→上传任务→问答，并打印 Python 基线对比 |
| 灌演示数据 | `node scripts/seed-demo.mjs` → `node scripts/seed-attachments.mjs` | Node | 默认打 `http://127.0.0.1:8088`（Docker 前端端口） |
| **改完文档后做体检**（建议每次提交前） | `node scripts/check-docs.mjs` | Node | 查四类问题：表格内空行断表、单元格裸竖线、本地链接失效、ITERATION 总表与明细不一致 |
| 从干净基线灌演示数据 | `bash scripts/db-sql.sh scripts/demo-reset.sql` → 上面两步 | Git Bash | 物理清空后重灌（见 §5） |
| 手工查/改数据库 | `bash scripts/db-sql.sh <sql文件>` | Git Bash | 无需 yasql 客户端，口令不落盘 |
| 本地测试部署（镜像模式，前后端） | `deploy\docker\` 下按 `deploy/README.md` §2 | cmd | 本机 Docker Desktop；前端对外端口见 `.env` 的 `WEB_PORT`（本机约定 8088） |
| 本地测试部署（AI 服务） | `docker compose -f ai-backend/docker-compose.yml up -d --build` | cmd | 镜像 `pm-ai-backend:local`，卷 `./work:/app/work` |
| 迭代自测（改完代码重建镜像） | `bash scripts/dev-reload.sh [all\|backend\|frontend]` | Git Bash | **按需执行，不主动跑**（约定 ①） |
| 发版打包 | `bash scripts/make-release.sh v3.x.y` | Git Bash | 产出 `dist/pm-release-v3.x.y/`（镜像 + compose + `.env.example` + `pm-upgrade.sh`） |
| 服务器首次部署 | 见 `docs/部署与发布全流程手册.md` §3~§5 | 服务器 bash | 发布目录只需 `docker-compose.yml` + `.env` |
| 服务器升级 | 在服务器运行目录 `bash pm-upgrade.sh pm-images-arm64-v3.x.y.tar.gz` | 服务器 bash | 自动 `docker load` + 切 `.env` 的 `IMAGE_TAG` + `up -d` |
| 服务器重启/查看 | `docker compose up -d` / `docker compose ps` / `docker compose logs -f --tail 100` | 服务器 | 详见手册 §6 |
| 回滚 | 改 `.env` 的 `IMAGE_TAG` 回旧版本 → `docker compose up -d` | 服务器 | 镜像仍在本地则秒回滚 |

---

## 2. 开发机脚本（`scripts/`）

### 2.1 发版与部署

| 脚本 | 用途 | 用法 |
| --- | --- | --- |
| `make-release.sh` | **一键发版**：构建 arm64 镜像 → `docker save` → 产出 `dist/pm-release-<版本>/`（镜像包 + `docker-compose.yml` + `.env.example` + `pm-upgrade.sh` + 部署步骤） | `bash scripts/make-release.sh v3.5.0`；镜像已存在只重打包：`SKIP_BUILD=1 bash scripts/make-release.sh v3.5.0` |
| `dev-reload.sh` | **开发机一键重建**：重建镜像 + 重启 + 轮询健康检查（不产发布包） | `bash scripts/dev-reload.sh all\|backend\|frontend` |

### 2.2 演示数据

| 脚本 | 用途 | 用法 |
| --- | --- | --- |
| `seed-demo.mjs` | 演示数据主脚本：父子项目、每核算单元 2~5 份合同（施工主合同 / 监理 / 第三方测评 / 预算编制 / 方案评估）、主合同 5 个付款里程碑（30/40/20/7/3，含部分付款差额）、项目分工（HW/SW 模板） | `node scripts/seed-demo.mjs [baseUrl]` |
| `seed-attachments.mjs` | 三类附件（阶段 / 合同 / 付款凭证），走真实上传接口；**故意让约 2/5 的已付款少传一类凭证**，用于验证「报销缺件提醒」 | `node scripts/seed-attachments.mjs [baseUrl]` |
| `demo-phase-guides.sql` | 阶段说明 / 关键材料的演示文案（直接改库） | 用 `db-sql.sh` 执行 |
| `demo-project-overviews.sql` | 项目概览（介绍 Markdown + 功能模块清单）演示内容 | 用 `db-sql.sh` 执行 |
| `demo-reset.sql` | **物理**清空演示数据（解决逻辑删除导致的历史行堆积） | `bash scripts/db-sql.sh scripts/demo-reset.sql` |

**推荐顺序**（要从干净基线开始时）：

```bash
bash scripts/db-sql.sh scripts/demo-reset.sql   # ① 物理清空（可选，仅开发/演示环境）
node scripts/seed-demo.mjs                      # ② 项目 / 合同 / 付款 / 分工
node scripts/seed-attachments.mjs               # ③ 附件
```

⚠️ 为什么需要 ①：`seed-demo.mjs` 走接口删项目，而本系统删除一律是**逻辑删除**（`deleted = 1`，审计留痕）。
反复重跑 seed 会让 `project` / `contract` 等表堆积历史行，直接翻库时容易误判"当前数据缺字段"——
排查时先按 `deleted = 0` 过滤。

### 2.3 数据库工具（开发机手工查/改库）

开发机通常**没有 yasql 客户端**，而迁移执行器只跑 `db/migration-yashan/`，所以手工操作走这一对：

| 文件 | 作用 |
| --- | --- |
| `db-sql.sh` | 包装脚本：自动取连接信息（优先 `DB_URL/DB_USER/DB_PASSWORD` → 运行中的 `pm-backend` 容器环境变量 → `deploy/docker/.env`），再调用执行器；**口令不打印、不落盘** |
| `jdbc/RunSql.java` | 极简 JDBC 执行器（JDK 源码文件模式运行，无需 `javac`）；切句规则与 `YashanMigrationRunner` 一致；`SELECT` 打印表格 |

`demo-reset.sql` 清理范围：`project_contract` / `project_division` / `project_overview` / `project_phase` /
`attachment_upload_task` / `attachment` / `payment` / `contract` / `project`。
**保留**：`sys_user`、`dict_item`、`phase_tpl`、`phase_template`、`schema_version`；`operate_log` 默认保留。

> 附件**元数据**会被删，已落盘 / 已上 OBS 的**物理文件不会删**（变成无引用垃圾）。开发环境可接受，生产不要用。

---

### 2.4 文档体检（`check-docs.mjs`）

整理文档时踩过的两类坑**都会静默把表格渲染打断**，所以固化成工具，提交前跑一次（在仓库根执行）：

```bash
node scripts/check-docs.mjs          # 有问题时退出码 1，可直接放进 CI/提交前钩子
```

| 检查项 | 为什么 |
| --- | --- |
| 表格内空行 | Markdown 里**空行即结束表格**，后面的行会渲染成普通段落（曾把 ITERATION 最新两行踢出表格） |
| 单元格裸竖线 | 未转义的 `\|` 会被当成多一列，必须写成 `\|`（例如 `` `local\|obs` ``） |
| 本地链接失效 | 只查相对路径链接（http/mailto/锚点跳过），防止文档核减后留下断链 |
| ITERATION 一致性 | 「迭代总表」数据行数必须等于「各迭代明细」段数（仓库硬约定） |

---

## 3. AI 能力服务脚本（`ai-backend/scripts/`）

| 脚本 | 用途 | Shell | 用法 |
| --- | --- | --- | --- |
| `check.cmd` | **质量门（Windows 原生）**：`mvn test`（+ 可选打包） | cmd | `cd ai-backend && scripts\check.cmd [package]` |
| `check.sh` | 同上（Linux / Git Bash 版） | bash | `bash ai-backend/scripts/check.sh` |
| `verify-e2e.ps1` | **端到端验收**：起服务 → `/health` 三探测 → 平台 OCR → `/analyze` → `/upload-tasks` → `/chat`，并打印基线数字 | pwsh 7 | `pwsh -File ai-backend\scripts\verify-e2e.ps1 [-Port 8101] [-SkipBuild]` |

AI 服务的构建与发布（独立镜像、独立端口）见 [`docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md) §8。

---

## 4. 部署相关脚本与资产

| 位置 | 用途 |
| --- | --- |
| `deploy/windows/start-dev.cmd`、`stop-dev.cmd` | 源码模式启动/停止（本地开发）；根目录同名 `.cmd` 是通用入口 |
| `deploy/docker/Dockerfile.backend`、`Dockerfile.frontend` | 生产镜像（构建上下文＝仓库根） |
| `deploy/docker/docker-compose.deploy.yml` | **服务器用编排**：无 `build:` + `pull_policy: never`（发布包里改名为 `docker-compose.yml`） |
| 发布包内 `pm-upgrade.sh` | 服务器安装/升级（`docker load` + 切 `IMAGE_TAG` + `up -d`） |
| `ai-backend/Dockerfile`、`ai-backend/docker-compose.yml` | AI 服务镜像与本地编排（**独立发版**，不在主系统发布包内） |

---

## 5. 新增脚本时的约定

1. **同步本文档**：新增/改名/删除脚本必须在 §1 速查表与对应分组里补/改一行，保持"脚本与说明一致"；
2. 脚本头部写清 **用途 / 用法 / 前置条件**，并把踩过的坑写进注释；
3. 涉及数据库的：**不要把口令写进脚本**，走环境变量或 `.env`；
4. 涉及演示数据的：新功能要能被数据体现出来（见 [`README.md`](../README.md) 文档维护约定）；
5. **优先提供 Windows 原生入口**：能用 `.cmd`/`.ps1` 就别只给 `.sh`（本机没有 Git Bash，`.sh` 目前跑不了，见 §0.2）。
