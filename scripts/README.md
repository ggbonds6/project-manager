# scripts 脚本清单

本目录存放**开发机**用的脚本：发版打包、演示数据、数据库排障工具。
所有脚本默认在**仓库根目录**下执行。

> 生产服务器上**不需要**这些脚本（服务器不存源码，只放 `docker-compose.yml` + `.env`；
> 升级用随发布包下发的 `pm-upgrade.sh`）。详见 [`docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md)。

---

## 1. 发版与部署

| 脚本 | 用途 | 用法 |
| --- | --- | --- |
| `make-release.sh` | **一键发版**：构建 arm64 镜像 → `docker save` → 产出 `dist/pm-release-<版本>/`（镜像包 + `docker-compose.yml` + `.env.example` + `pm-upgrade.sh` + 部署步骤） | `bash scripts/make-release.sh v3.5.0`<br>只想重新打包（镜像已存在）：`SKIP_BUILD=1 bash scripts/make-release.sh v3.5.0` |
| `pm-upgrade.sh` | **服务器升级**（随发布包下发）：`docker load` + **自动切 `.env` 的 `IMAGE_TAG`**（备份 `.env.bak`）+ `up -d` | 在服务器运行目录：`bash pm-upgrade.sh pm-images-arm64-v3.5.0.tar.gz` |
| `dev-reload.sh` | **开发机一键重建**：重建镜像 + 重启 + 轮询健康检查（本机自测用，不产发布包） | `bash scripts/dev-reload.sh all`<br>只改一端时用 `backend` / `frontend` 可省一半时间 |

> 回滚：把服务器 `/home/lhim/pm/app/.env` 的 `IMAGE_TAG` 改回旧版本 → `docker compose up -d`。

## 2. 演示数据

| 脚本 | 用途 | 用法 |
| --- | --- | --- |
| `seed-demo.mjs` | **演示数据主脚本**：清空现有项目后重建——父子项目、**每个核算单元 2~5 份合同**（施工主合同 / 监理服务 / 第三方测评 / 预算编制 / 方案评估）、主合同 **5 个付款里程碑**（30/40/20/7/3，含部分付款差额场景）、**项目分工**（HW/SW 模板，含风险阻塞项） | `node scripts/seed-demo.mjs [baseUrl]`<br>默认 `http://127.0.0.1:8088` |
| `seed-attachments.mjs` | **三类附件**：阶段附件 / 合同附件（合同正本·法务意见书·会签表·授权委托书·履约保函…）/ 付款凭证（付款审批单·发票·付款凭证）；生成可预览的最小 txt/pdf/csv 走真实上传接口。**故意让约 2/5 的已付款少传一类凭证**，用于验证「报销缺件提醒」 | `node scripts/seed-attachments.mjs [baseUrl]` |
| `demo-phase-guides.sql` | 阶段说明/关键材料的演示文案（直接改库） | 见下方「数据库工具」 |
| `demo-project-overviews.sql` | 项目概览（介绍 Markdown + 功能模块清单）的演示内容 | 见下方「数据库工具」 |

**推荐顺序**（想从干净基线开始时先清库）：

```bash
bash scripts/db-sql.sh scripts/demo-reset.sql   # ① 物理清空演示数据（可选）
node scripts/seed-demo.mjs                      # ② 项目 / 合同 / 付款 / 分工
node scripts/seed-attachments.mjs               # ③ 附件
```

⚠️ **为什么需要 ①**：`seed-demo.mjs` 是通过后端接口删项目的，而本系统的删除一律是**逻辑删除**
（`deleted = 1`，为审计留痕）。反复重跑 seed 会让 `project` / `contract` 等表**堆积历史行**，
直接翻数据库时容易误以为"当前数据缺字段"——那其实是早期批次留下的旧结构行。
排查时请先按 `deleted = 0` 过滤。

## 3. 数据库工具（开发机手工查/改库）

开发机通常**没有 yasql 客户端**，而迁移执行器只会跑 `db/migration-yashan/` 下的脚本，
所以手工查/改数据用下面这一对：

| 文件 | 作用 |
| --- | --- |
| `db-sql.sh` | 包装脚本：自动取连接信息（优先已导出的 `DB_URL/DB_USER/DB_PASSWORD` → 运行中的 `pm-backend` 容器环境变量 → `deploy/docker/.env`），再调用下面的执行器。**口令不打印、不落盘** |
| `jdbc/RunSql.java` | 极简 JDBC 执行器（JDK 11+ 源码文件模式运行，无需 `javac`）。切句规则与后端 `YashanMigrationRunner` 一致；`SELECT` 打印结果表格，其余语句报成功/失败 |

```bash
# 查数据
bash scripts/db-sql.sh path/to/query.sql

# 清空演示数据（物理删除，仅开发/演示环境！）
bash scripts/db-sql.sh scripts/demo-reset.sql
```

`demo-reset.sql` 的清理范围：`project_contract` / `project_division` / `project_overview` /
`project_phase` / `attachment_upload_task` / `attachment` / `payment` / `contract` / `project`。
**保留**：`sys_user`、`dict_item`、`phase_tpl`、`phase_template`、`schema_version`；
`operate_log`（审计日志）默认不清理，需要时放开文件里注释掉的那行。

> 注：附件**元数据**会被删除，但已落盘 / 已上传到 OBS 的**物理文件不会删**，会变成无引用垃圾
> （开发环境可接受；生产环境请走保留策略，不要用这个脚本）。

---

## 4. 新增脚本时的约定

1. 在本文档补一行（用途 + 用法），保持"脚本与说明同步"；
2. 脚本头部写清 **用途 / 用法 / 前置条件**，并在注释里说明踩过的坑；
3. 涉及数据库的：**不要把口令写进脚本**，走环境变量或 `.env`；
4. 涉及演示数据的：新功能要能被数据体现出来（见 [`README.md`](../README.md) §文档维护约定）。

## 5. 协作约定（脚本相关）

- 🚫 **不要主动重建镜像**：`dev-reload.sh` / `make-release.sh` 都耗时且会重启容器，
  **需要时先询问**再执行。
- 🚫 **不要为了验证前端改动去起浏览器**（更不要为此安装浏览器自动化环境）；
  前端改动做到「类型检查 + 构建通过 + 数据/接口核对」即可收尾，交互效果由用户刷新页面确认。
- 🚫 **不要在个人目录/演示库之外执行 `demo-reset.sql`**：它会**物理删除**演示数据（含附件元数据），
  仅限开发机与演示环境；生产环境治理附件请走保留策略。
