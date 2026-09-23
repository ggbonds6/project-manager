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
| `local/`（**已 gitignore**） | 两个**本机启动助手**（`start-project-local.cmd` / `stop-project-local.cmd`，双击可用）。⚠️ **密钥类一律放仓库外**：数据库导出与口令清单在 `E:\env\pm-local\export\` | 本机 |
| 根目录 `start-dev.cmd` / `stop-dev.cmd` | 一键启动/停止的**通用入口**（转发到 `deploy/windows/`） | 开发机（双击即用） |

> 生产服务器上**只有发布包里的 `docker-compose.yml` + `.env` + `pm-upgrade.sh`**（服务器不存源码，也不需要本目录任何脚本）。

### 0.2 Shell 要求（**先看这条，否则会白折腾**）

| 扩展名 | 需要什么 | 本机现状（实测） |
| --- | --- | --- |
| `.cmd` / `.bat` | Windows 原生，双击或 cmd 直接跑 | ✅ 可用 |
| `.ps1` | Windows 原生（PowerShell）。**5.1 与 7 都能跑**：本机默认壳是 Windows PowerShell 5.1，`pwsh` 7 也已装 | ✅ 可用，但**本机默认策略会拦**——见下方"执行策略" |
| `.sh` | **Git Bash 或 WSL**（本项目的 `.sh` 是 bash 脚本） | ⚠️ **本机没有 Git Bash**（PATH 上的 `bash` 只是 WSL 桩）。开发机脚本**已全部改为 `.ps1`**；**仓库里只剩 `scripts/pm-upgrade.sh` 一个 `.sh`，它随发布包下发、只在 Linux 服务器上运行**，本机不需要跑它 |
| `.mjs` | Node.js 18+ | ✅ 可用（用 `node xxx.mjs`；PowerShell 里若 `npm` 被策略拦，用 `npm.cmd`） |

**结论（本机 Windows 开发）**：
- 能用：全部 `.cmd`、`.ps1`（5.1 或 7）、`.mjs`；开发机脚本**已经没有 `.sh`**（唯一例外见上表）；
- 写 `.ps1` 时**避开 pwsh 7 独有语法**（`??`、三元 `? :`、`-Parallel`、`Invoke-RestMethod -Form`），否则 5.1 下会失败；
  唯一必须用 `pwsh -File`（7）跑的是 `ai-backend/scripts/verify-e2e.ps1`（它用了 `-Form`）；
- `scripts/*.ps1` 文件是 **UTF-8 带 BOM**：5.1 对无 BOM 的 UTF-8 脚本会按 ANSI(GBK) 解析，中文直接变乱码。
  ⚠️ **BOM 是刻意的，不要"顺手"改成无 BOM**：实测（PS 5.1 与 7 各跑一遍）无 BOM 时中文字面量在**解析期**就坏掉
  （`'中文：已就绪'` → `'涓枃锛氬凡灏辩华'`），加 BOM 后 5.1 与 7 都正确 —— 也就是
  **「无 BOM + 中文 + 5.1 可跑」三者不可兼得**；真要取掉 BOM，只能同时放弃 5.1 或删掉脚本里的中文。
  新建 `.ps1` 请照抄现有文件的编码（编辑器里选「UTF-8 with BOM / 带 BOM 的 UTF-8」）。
- **执行策略（第一次跑 `.ps1` 必踩）**：常见报错 `无法加载文件 …\.ps1，因为在此系统上禁止运行脚本`（`PSSecurityException`）。
  根因：本机 **5 个作用域（MachinePolicy/UserPolicy/Process/CurrentUser/LocalMachine）全是 `Undefined`**，
  Windows 客户端在此情况下**默认即 `Restricted`**（`Get-ExecutionPolicy` 会告诉你 `Restricted`）——不是脚本有问题。
  两种修法，任选一种：
  1. **一次性持久修（推荐，只影响当前用户）**：`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`
     —— 本仓库脚本**没有 Zone.Identifier 网络锁定标记**（直接 git clone / 本地文件），`RemoteSigned` 下可正常执行；
     想还原就 `Set-ExecutionPolicy -Scope CurrentUser Undefined`。
  2. **免副作用（不改机器策略，每次带上）**：`powershell -ExecutionPolicy Bypass -File scripts\make-release.ps1 <版本>`
     —— 已实测 PS 5.1 与 7 都能加载；`.cmd` 里也可以这么调。
- ⚠️ **写 `.ps1` 的硬约定：用 .NET API 处理相对路径前，必须同步"进程当前目录"**。
  PowerShell 有**两套**当前目录：`$PWD`（`Set-Location` 改的是它，cmdlet 与外部程序按它解析）与
  .NET 的 `[System.IO.Directory]::GetCurrentDirectory()`（`[System.IO.File]::Create/ReadAllText` 等**按它解析**），
  **`Set-Location` 不会同步后者**。踩过的坑：`make-release.ps1` 从"主目录启动的 PowerShell 里 `cd` 进仓库"调用时，
  `New-Item` 按 `$PWD` 在仓库下建好了 `dist\…`，而 `[System.IO.File]::Create($tarGz)` 去 `C:\Users\<你>\dist\…` 找 →
  报 `未能找到路径 …\dist\… 的一部分`。**修法（脚本开头三行，三个脚本已统一）**：
  ```powershell
  $repoRoot = Split-Path -Parent $PSScriptRoot
  Set-Location -LiteralPath $repoRoot
  [System.IO.Directory]::SetCurrentDirectory($repoRoot)   # ★ 关键这一行
  ```
  好处不只是修 bug：脚本从此**可以从任何目录调用**，不再要求"必须先 cd 到仓库根"。

### 0.3 三条硬约定（已写入团队协作记忆）

1. 🚫 **不主动重建镜像**：`dev-reload.ps1` / `make-release.ps1` 耗时且会重启容器 —— **需要时先问**。
2. 🚫 **不为前端改动起浏览器**（也不要为此装浏览器自动化）：前端做到「类型检查 + 构建 + 数据/接口核对」即收尾，
   交互效果由用户刷新页面确认。
3. 🚫 **`demo-reset.sql` 只允许用于开发机/演示环境**：它**物理删除**演示数据（含附件元数据），生产环境走保留策略。

---

## 1. 场景速查（最常用的一张表）

> 下表的 `.\scripts\*.ps1` 都是**本机 Windows 原生用法**（Windows PowerShell 5.1 或 `pwsh` 7 都能跑），**不需要 Git Bash**；
> 若执行策略为 Restricted，改用 `powershell -ExecutionPolicy Bypass -File scripts\xxx.ps1`。
> 只有服务器上的 `pm-upgrade.sh` 仍用 bash（它只在 Linux 服务器运行）。

| 我要做什么 | 命令 | Shell | 说明 |
| --- | --- | --- | --- |
| 起本地开发环境（前后端源码模式） | `start-dev.cmd`（或 `deploy\windows\start-dev.cmd`） | cmd | 后端 :8080、前端 :5173（`--host` 支持局域网） |
| 停本地开发环境 | `stop-dev.cmd` | cmd | 按端口停 8080/5173；**不碰外部崖山库** |
| 起 AI 能力服务（本地） | `cd ai-backend && mvn -B -DskipTests package`<br>`java -jar target/pm-ai-backend-1.0.0-SNAPSHOT.jar` | cmd | 默认 :8100；本地调试常用 `--server.port=8101` 避开占用；凭据放 `ai-backend/.env` |
| AI 服务质量门（编译 + 单测） | `cd ai-backend && scripts\check.cmd`（或 `mvn -B test`） | cmd | 当前基线 **81 用例** |
| AI 服务端到端验收（真实平台网关） | `pwsh -File ai-backend\scripts\verify-e2e.ps1` | pwsh 7 | 自动起服务→自检→平台 OCR→抽取→上传任务→问答，并打印 Python 基线对比 |
| 灌演示数据 | `node scripts/seed-demo.mjs` → `node scripts/seed-attachments.mjs` | Node | 默认打 `http://127.0.0.1:8088`（Docker 前端端口） |
| **改完文档后做体检**（建议每次提交前） | `node scripts/check-docs.mjs` | Node | 查四类问题：表格内空行断表、单元格裸竖线、本地链接失效、ITERATION 总表与明细不一致 |
| 从干净基线灌演示数据 | `.\scripts\db-sql.ps1 scripts/demo-reset.sql` → 上面两步 | PowerShell | 物理清空后重灌（见 §2.2） |
| 手工查/改数据库 | `.\scripts\db-sql.ps1 <sql文件>` | PowerShell | 无需 yasql 客户端，口令不落盘 |
| 本地测试部署（镜像模式，前后端） | `deploy\docker\` 下按 `deploy/README.md` §2 | cmd | 本机 Docker Desktop；前端对外端口见 `.env` 的 `WEB_PORT`（本机约定 8088） |
| 本地测试部署（AI 服务） | `docker compose -f ai-backend/docker-compose.yml up -d --build`（等价脚本：`.\scripts\dev-reload.ps1 -Project ai`） | cmd / PowerShell | 镜像 `pm-ai-backend:local`，卷 `./work:/app/work` |
| 迭代自测（改完代码重建镜像） | 主系统：`.\scripts\dev-reload.ps1 [all\|backend\|frontend]`<br>AI 服务：`.\scripts\dev-reload.ps1 -Project ai` | PowerShell | **按需执行，不主动跑**（约定 ①） |
| 发版打包（主系统，两个镜像都重建） | `.\scripts\make-release.ps1 v3.x.y` | PowerShell | 产出 `dist/pm-release-v3.x.y/`（镜像 + compose + `.env.example` + `pm-upgrade.sh`） |
| 发版打包（主系统，**只重建改动的那一端**） | `.\scripts\make-release.ps1 v3.x.y -Only backend`<br>`.\scripts\make-release.ps1 v3.x.y -Only frontend -ReuseTag v3.x.x` | PowerShell | 没改动的那一端用 `docker tag` 复用本机已有镜像；**发布包内容与完整构建完全一致**；与 `SKIP_BUILD=1` 互斥（详见 §2.1） |
| 发版打包（AI 能力服务，**独立发版**） | `.\scripts\make-release.ps1 v1.x.y -Project ai` | PowerShell | 产出 `dist/pm-ai-release-v1.x.y/`（AI 镜像 + compose + `.env.example` + AI 部署步骤；不含主系统的任何文件） |
| 服务器首次部署 | 见 `docs/部署与发布全流程手册.md` §3~§5 | 服务器 bash | 发布目录只需 `docker-compose.yml` + `.env` |
| 服务器升级 | 在服务器运行目录 `bash pm-upgrade.sh pm-images-arm64-v3.x.y.tar.gz` | 服务器 bash | 自动 `docker load` + 切 `.env` 的 `IMAGE_TAG` + `up -d` |
| 服务器重启/查看 | `docker compose up -d` / `docker compose ps` / `docker compose logs -f --tail 100` | 服务器 | 详见手册 §6 |
| 回滚 | 改 `.env` 的 `IMAGE_TAG` 回旧版本 → `docker compose up -d` | 服务器 | 镜像仍在本地则秒回滚 |

---

## 2. 开发机脚本（`scripts/`）

### 2.1 发版与部署

| 脚本 | 用途 | 用法 |
| --- | --- | --- |
| `make-release.ps1` | **一键发版**（主系统 / AI 能力服务两套发布物）：构建镜像 → `docker save` → 产出发布目录（镜像包 + compose + `.env.example` + 部署步骤） | 主系统：`.\scripts\make-release.ps1 v3.5.0`；只重建改动的一端：`.\scripts\make-release.ps1 v3.6.2 -Only backend`；镜像已存在只重打包：`$env:SKIP_BUILD='1'; .\scripts\make-release.ps1 v3.5.0`；AI 服务：`.\scripts\make-release.ps1 v1.0.0 -Project ai` |
| `dev-reload.ps1` | **开发机一键重建**：重建镜像 + 重启 + 轮询健康检查（不产发布包） | 主系统：`.\scripts\dev-reload.ps1 all\|backend\|frontend`；AI 服务：`.\scripts\dev-reload.ps1 -Project ai` |

**`make-release.ps1` 参数表**（两套发布物统一的开关，`-Project` 决定产出哪一套）：

| 参数 | 取值 | 默认 | 说明 |
| --- | --- | --- | --- |
| `<版本>`（位置 0） | 如 `v3.6.2` / `v1.0.0` | 必填 | 决定镜像 tag 与发布目录名；缺省时打印用法并退出 1 |
| `[平台]`（位置 1） | `linux/arm64` \| `linux/amd64` | `linux/arm64` | 决定镜像包名里的 `aarch64` / `x86_64`；**必须与目标服务器架构一致**（混架构会 `exec format error`） |
| `-Project` | `main` \| `ai` | `main` | `main`=主系统发布包，`ai`=AI 能力服务发布包；**两者发布物互不包含** |
| `-Only` | `backend` \| `frontend` \| `both` | `both` | **仅主系统**：只重建一端，另一端 `docker tag` 复用本机镜像（省一半构建时间） |
| `-ReuseTag` | 如 `v3.6.1` | 空 | **仅配合 `-Only`**：显式指定复用来源；不给则自动挑本机最新 tag 并**打印实际来源** |
| 环境变量 `SKIP_BUILD=1` | `0` / `1` | 空 | 完全跳过构建，只重打包本机已存在的同版本镜像 |

**产出与内容**：

| 命令 | 产出目录 | 内容 |
| --- | --- | --- |
| `make-release.ps1 <版本>` | `dist/pm-release-<版本>/` | `pm-images-<arch>-<版本>.tar.gz`（**backend + frontend 两个镜像**）、`docker-compose.yml`、`.env.example`（预填 `IMAGE_TAG`）、`pm-upgrade.sh`、`服务器部署步骤.txt` |
| `make-release.ps1 <版本> -Project ai` | `dist/pm-ai-release-<版本>/` | `pm-ai-images-<arch>-<版本>.tar.gz`（只有 AI 镜像）、`docker-compose.yml`（← `ai-backend/docker-compose.deploy.yml`，**包内已改名，服务器不用再 mv**）、`.env.example`（预填 `AI_IMAGE_TAG`）、`服务器部署步骤-ai.txt` |

> **退出码约定**：参数非法或语义冲突 = **2**（例：`-Only` 配 `-Project ai`、`-Only` 配 `SKIP_BUILD=1`、`-ReuseTag` 没配 `-Only`、平台写法不支持）；缺文件 / 本机缺镜像 = **1**；构建失败 = 透传 docker 的退出码。
> **`-Only` 的语义**：只省掉「没改动那一端」的构建时间，**发布包内容与默认行为完全一致**（包里仍是两个镜像、同一个 `<版本>` tag，服务器端零额外操作）。
> 复用来的镜像会先做**架构校验**（与 `[平台]` 不一致立即报错），并打印实际来源（例：`复用镜像：pm-frontend:v3.6.1  →（docker tag）→  pm-frontend:v3.6.2`）；一个可复用的 tag 都没有时报错并提示「先完整构建一次，或用 `-ReuseTag` 指定」；且**来源校验在构建之前**完成——`-ReuseTag` 写错会立刻退出，不会白等一轮构建。
> **AI 发布包不含** `pm-upgrade.sh`（主系统服务器专用）、也不含主系统的 `.env.example`；同理主系统发布包里没有 AI 镜像（见部署手册 §8）。

### 2.2 演示数据

| 脚本 | 用途 | 用法 |
| --- | --- | --- |
| `seed-demo.mjs` | 演示数据主脚本：父子项目、每核算单元 2~5 份合同（施工主合同 / 监理 / 第三方测评 / 预算编制 / 方案评估）、主合同 5 个付款里程碑（30/40/20/7/3，含部分付款差额）、项目分工（HW/SW 模板） | `node scripts/seed-demo.mjs [baseUrl]` |
| `seed-attachments.mjs` | 三类附件（阶段 / 合同 / 付款凭证），走真实上传接口；**故意让约 2/5 的已付款少传一类凭证**，用于验证「报销缺件提醒」 | `node scripts/seed-attachments.mjs [baseUrl]` |
| `demo-phase-guides.sql` | 阶段说明 / 关键材料的演示文案（直接改库） | 用 `db-sql.ps1` 执行 |
| `demo-project-overviews.sql` | 项目概览（介绍 Markdown + 功能模块清单）演示内容 | 用 `db-sql.ps1` 执行 |
| `demo-reset.sql` | **物理**清空演示数据（解决逻辑删除导致的历史行堆积） | `.\scripts\db-sql.ps1 scripts/demo-reset.sql` |

**推荐顺序**（要从干净基线开始时）：

```powershell
.\scripts\db-sql.ps1 scripts/demo-reset.sql   # ① 物理清空（可选，仅开发/演示环境）
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
| `db-sql.ps1` | 包装脚本：自动取连接信息（优先 `DB_URL/DB_USER/DB_PASSWORD` → 运行中的 `pm-backend` 容器环境变量 → `deploy/docker/.env`），再调用执行器；**口令不打印、不落盘** |
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
**开发机侧的统一入口已补齐**（与主系统对等）：本地「重建 + 重启 + 健康检查」用 `.\scripts\dev-reload.ps1 -Project ai`，
出发布包用 `.\scripts\make-release.ps1 <版本> -Project ai`（产出 `dist/pm-ai-release-<版本>/`，见 §2.1）。
AI 分支的前置条件与主系统不同：`dev-reload.ps1 -Project ai` 要求 `ai-backend/.env` 已存在（缺了会指向 `.env.example` 与手册 §8.2），
端口从该 `.env` 的 `AI_PORT` 读（缺省 8100），健康检查探 `/health?with_ocr=false`。

---

## 4. 部署相关脚本与资产

| 位置 | 用途 |
| --- | --- |
| `deploy/windows/start-dev.cmd`、`stop-dev.cmd` | 源码模式启动/停止（本地开发）；根目录同名 `.cmd` 是通用入口 |
| `deploy/docker/Dockerfile.backend`、`Dockerfile.frontend` | 生产镜像（构建上下文＝仓库根） |
| `deploy/docker/docker-compose.deploy.yml` | **服务器用编排**：无 `build:` + `pull_policy: never`（发布包里改名为 `docker-compose.yml`） |
| 发布包内 `pm-upgrade.sh` | 服务器安装/升级（`docker load` + 切 `IMAGE_TAG` + `up -d`） |
| `ai-backend/Dockerfile`、`ai-backend/docker-compose.yml`、`ai-backend/docker-compose.deploy.yml` | AI 服务镜像；本地编排（**含 `build:`**，上下文＝仓库根）；**服务器专用编排**（无 `build:` + `pull_policy: never`，发布包里改名为 `docker-compose.yml`）。AI 服务**独立发版**，不在主系统发布包内（出包见 §2.1 的 `-Project ai`） |

---

## 5. 新增脚本时的约定

1. **同步本文档**：新增/改名/删除脚本必须在 §1 速查表与对应分组里补/改一行，保持"脚本与说明一致"；
2. 脚本头部写清 **用途 / 用法 / 前置条件**，并把踩过的坑写进注释；
3. 涉及数据库的：**不要把口令写进脚本**，走环境变量或 `.env`；
4. 涉及演示数据的：新功能要能被数据体现出来（见 [`README.md`](../README.md) 文档维护约定）；
5. **只提供 Windows 原生入口**：开发机脚本一律 `.cmd` / `.ps1`，**不要再新增 `.sh`**（本机没有 Git Bash，见 §0.2）；
   只有「随发布包下发、在 Linux 服务器上跑」的脚本才用 `.sh`（目前只有 `pm-upgrade.sh`）。
