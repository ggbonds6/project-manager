# 部署与运维说明

> 数据库为**崖山 YashanDB（Oracle 模式）主备集群**（外部主机，见 §4 环境变量）。

| 目录 | 用途 | 谁在用 |
| --- | --- | --- |
| `deploy/docker` | **生产部署（唯一方式）**：后端 jar + 前端 nginx 一体化镜像 | 两台服务器（离线 `docker load`） |
| `deploy/windows` | 源码模式启动（`mvn spring-boot:run` + Vite） | 开发机本地开发 |

仓库根目录的 `start-dev.cmd` / `stop-dev.cmd` 为 Windows 通用启动入口（内部调用 `deploy\windows\*`）。
原 `deploy/linux`（Linux 源码直跑脚本）已移除——开发机为 Windows，服务器不存源码，该脚本无执行场景。

---

## 1. 生产部署：Docker（服务器）

> **服务器不需要源码**：镜像内已含后端 jar（含建库迁移 SQL）、前端产物与 nginx 配置。
> 服务器运行目录只有 2 个文件：`docker-compose.yml` + `.env`（均取自发布包）。
> 完整步骤（含环境准备、验收、升级、回滚）见 [`docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md)。

服务器用的编排是 **`deploy/docker/docker-compose.deploy.yml`**（发布包里改名为 `docker-compose.yml`）：
**没有 `build:` 段** + `pull_policy: never`，镜像缺失时立刻报错，不会误触发联网构建。

```bash
# ── 开发机：出发布包（镜像 + 编排 + .env 模板）──
bash scripts/make-release.sh v3.1.1              # 产 dist/pm-release-v3.1.1/
scp dist/pm-release-v3.1.1/pm-images-aarch64-v3.1.1.tar.gz  lhim@<服务器>:/home/lhim/pm/releases/
scp dist/pm-release-v3.1.1/docker-compose.yml dist/pm-release-v3.1.1/.env.example lhim@<服务器>:/home/lhim/pm/app/

# ── 服务器：配 .env → load → up（不加 --build）──
cd /home/lhim/pm/app && cp .env.example .env && vi .env    # 填 YASHAN_PASSWORD / JWT_SECRET / OBS 五项
docker load -i /home/lhim/pm/releases/pm-images-aarch64-v3.1.1.tar.gz
docker compose up -d
docker compose ps && curl http://127.0.0.1:8080/api/health     # 期望 db:"up"
```

启动后：

- 前端（nginx）：**http://服务器IP:8080**（默认端口，可改 `WEB_PORT`）
- `/api`、`/uploads` 由 nginx 反代到后端容器（后端不对外暴露）
- **附件存储**：生产统一 **华为 OBS**（`APP_STORAGE_TYPE=obs` + `APP_STORAGE_OBS_*`，对象置于桶内 `uploads/` 前缀下），
  详见《双机ARM服务器独立部署方案.md》；`APP_STORAGE_TYPE=local` 时用命名卷 `pm_uploads`

**首次建库**：后端启动时自研迁移 Runner 自动执行 `db/migration-yashan/V1~V8` 完成建表与种子（幂等，已执行版本记入 `schema_version`），无需手工导库。

> 内置账号：admin / jingban01 / lingdao01（密码均 123456）；生产务必先改密并覆盖 `JWT_SECRET`。

> 📌 **双机（两台 ARM 服务器，独立运行、指向同一崖山库、上层网关负载均衡）部署**：
> 每台一份 Docker Compose 即可，含附件共享存储 / JWT 一致性 / 冒烟清单等完整步骤，
> 见 [`docs/双机ARM服务器独立部署方案.md`](../docs/双机ARM服务器独立部署方案.md)。

---

## 2. 开发机本地试跑：Docker

用仓库内**含 `build:` 段的** `deploy/docker/docker-compose.yml`（**服务器不要用这一份**）：

```bash
cd project-manager
cp deploy/docker/.env.example deploy/docker/.env   # ⚠️ .env 必须与 docker-compose.yml 同目录
#   编辑 .env：填 YASHAN_PASSWORD；本机测试建议 APP_STORAGE_TYPE=local、WEB_PORT=8088
docker compose -f deploy/docker/docker-compose.yml up -d --build

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

- 崖山 YashanDB（Oracle 模式）；迁移脚本位于 `backend/src/main/resources/db/migration-yashan`（V1–V8），
  由后端启动时自研 `YashanMigrationRunner` 顺序执行（替代 Flyway，崖山官方不支持 Flyway）。
- 已执行版本记录在库表 `schema_version`；新增表结构 = 在该目录新增 `V{n}__xxx.sql` 即可。
- 当前业务表：sys_user / dict_item / phase_template / project / project_phase / payment / contract /
  attachment / operate_log / project_overview / phase_tpl（11 张，主键为 identity 自增）。

## 6. Git 协作（SSH）

见 `docs/GITHUB-SSH-setup.md`：配置 ssh key → 切换 remote 为 SSH → 验证连通 → 推送。
