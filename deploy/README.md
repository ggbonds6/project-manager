# 部署与运维说明

> 数据库为**崖山 YashanDB（Oracle 模式）主备集群**（外部主机，见 §4 环境变量），
> 仓库启动脚本仅负责拉起后端与前端；`deploy/windows`（Windows）、`deploy/linux`（Linux）、
> `deploy/docker`（后端 + 前端 nginx 一体化镜像）。
> 仓库根目录的 `start-dev.cmd` / `stop-dev.cmd` 为 Windows 通用启动入口（内部调用 `deploy\windows\*`）。

---

## 1. 方式一：Docker（服务器推荐，前后端打包）

前置：服务器安装 Docker Engine + Compose 插件；**服务器需能访问崖山主备库**（1688）。

```bash
# 1) 到仓库根目录
cd project-manager

# 2) 准备环境变量（YASHAN_PASSWORD 必须填写）
cp deploy/docker/.env.example deploy/docker/.env
#    编辑 .env：填入 YASHAN_PASSWORD

# 3) 构建并启动（首次会拉取镜像并构建，需几分钟）
docker compose -f deploy/docker/docker-compose.yml up -d --build

# 4) 查看状态 / 日志
docker compose -f deploy/docker/docker-compose.yml ps
docker compose -f deploy/docker/docker-compose.yml logs -f backend
```

启动后：

- 前端（nginx）：**http://服务器IP:8080**（默认端口，可改 `WEB_PORT`）
- `/api`、`/uploads` 由 nginx 反代到后端容器（后端不对外暴露）
- 附件卷 `pm_uploads`（宿主机 `docker volume inspect` 可查路径）

**首次建库**：后端启动时自研迁移 Runner 自动执行 `db/migration-yashan/V1~V8` 完成建表与种子（幂等，已执行版本记入 `schema_version`），无需手工导库。

> 内置账号：admin / jingban01 / lingdao01（密码均 123456）；生产务必先改密并覆盖 `JWT_SECRET`。

> 📌 **双机（两台 ARM 服务器，独立运行、指向同一崖山库、上层网关负载均衡）部署**：
> 每台一份 Docker Compose 即可，含附件共享存储 / JWT 一致性 / 冒烟清单等完整步骤，
> 见 [`docs/双机ARM服务器独立部署方案.md`](../docs/双机ARM服务器独立部署方案.md)。

---

## 2. 方式二：Linux 裸机（JDK17 + Maven + Node）

```bash
# 启动（需先 export YASHAN_MASTER_IP/YASHAN_STANDBY_IP/YASHAN_DB/YASHAN_USER/YASHAN_PASSWORD）
bash deploy/linux/start-dev.sh

# 停止（只停后端与前端）
bash deploy/linux/stop-dev.sh
```

日志与 pid：`deploy/linux/.pids/`（backend.log / frontend.log / *.pid）。

---

## 3. 方式三：Windows

```cmd
:: 启动（需先 set YASHAN_MASTER_IP/YASHAN_STANDBY_IP/YASHAN_DB/YASHAN_USER/YASHAN_PASSWORD）
deploy\windows\start-dev.cmd

:: 停止
deploy\windows\stop-dev.cmd
```

或直接双击仓库根目录的 `start-dev.cmd`（等同方式三入口）。

---

## 4. 环境变量速查

| 变量 | 用途 | 默认 |
| --- | --- | --- |
| `YASHAN_MASTER_IP` | 崖山主库 IP | 10.254.212.106 |
| `YASHAN_STANDBY_IP` | 崖山备库 IP | 10.254.212.107 |
| `YASHAN_DB` | 业务库名（schema） | project_manager |
| `YASHAN_USER` | 应用账号（业务账号 `pm`，具 CONNECT/RESOURCE；勿用 sys） | pm |
| `YASHAN_PASSWORD` | 应用账号密码（**必填，勿提交到 Git**） | - |
| `JWT_SECRET` | JWT 密钥（生产必改） | 示例值 |
| `UPLOAD_DIR` | 附件目录（Docker 内固定 /uploads） | ./uploads |
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
