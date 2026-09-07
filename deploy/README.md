# 部署与运维说明

> 仓库启动脚本按平台分类：`deploy/windows`（Windows）、`deploy/linux`（Linux），
> 服务器建议直接使用 `deploy/docker`（MySQL + 后端 + 前端 nginx 一体化，把环境也打包进去）。
> 仓库根目录的 `start-dev.cmd` / `stop-dev.cmd` 已改为 Windows 通用启动的入口（内部调用 `deploy\windows\*`）。

---

## 1. 方式一：Docker（服务器推荐，环境全部整合）

前置：服务器安装 Docker Engine + Compose 插件。

```bash
# 1) 到仓库根目录
cd project-manager

# 2) 准备环境变量（可选，默认值见 .env.example）
cp deploy/docker/.env.example deploy/docker/.env

# 3) 构建并启动（首次会拉取镜像并构建，需几分钟）
docker compose -f deploy/docker/docker-compose.yml up -d --build

# 4) 查看状态
docker compose -f deploy/docker/docker-compose.yml ps
```

启动后：

- 前端（nginx）：**http://服务器IP:8080**（默认端口，可改 `WEB_PORT`）
- `/api`、`/uploads` 由 nginx 反代到后端容器（后端不对外暴露；如需在宿主机执行 `scripts/seed-demo.mjs`，可临时给 backend 映射 `- "8080:8080"`，或在容器内执行）
- MySQL 数据卷 `pm_db`、附件卷 `pm_uploads`（宿主机 `docker volume inspect` 可查路径）

### 首次数据迁移（可选）

把旧环境导出的 SQL 导入到容器数据库：

```bash
# SQL 里含 CREATE DATABASE/表/数据（含 flyway 历史）
docker compose -f deploy/docker/docker-compose.yml exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < project_manager_db_xxxx.sql
```

若目标没有 `pm` 用户（本 compose 已在 mysql 初始化时自动建库建用户，通常无需处理），
如需补建：

```bash
docker compose -f deploy/docker/docker-compose.yml exec mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "CREATE USER IF NOT EXISTS 'pm'@'%' IDENTIFIED BY 'Pm@123456'; GRANT ALL ON project_manager.* TO 'pm'@'%'; FLUSH PRIVILEGES;"
```

附件实体文件（旧环境 `backend/uploads`）复制进卷：

```bash
docker run --rm -v pm_uploads:/uploads -v "$PWD/old-uploads":/old:ro alpine \
  sh -c 'cp -r /old/* /uploads/ 2>/dev/null || true'
```

> 内置账号：admin / jingban01 / lingdao01（密码均 123456）；生产务必先改密并覆盖 `JWT_SECRET`。

---

## 2. 方式二：Linux 裸机（JDK17 + Maven + Node + MySQL 已装好）

```bash
# 启动（需 JAVA_HOME/ PATH 含 mvn、npm；MySQL 需在跑，或 export MYSQL_START_CMD=...）
bash deploy/linux/start-dev.sh

# 停止（只停后端与前端）
bash deploy/linux/stop-dev.sh
```

日志与 pid：`deploy/linux/.pids/`（backend.log / frontend.log / *.pid）。

---

## 3. 方式三：Windows

```cmd
:: 启动（需要 PATH 里有 mvn/java/npm；MySQL 在跑或 set MYSQL_START_CMD=...）
deploy\windows\start-dev.cmd

:: 停止
deploy\windows\stop-dev.cmd
```

或直接双击仓库根目录的 `start-dev.cmd`（等同方式三入口）。

---

## 4. 环境变量速查

| 变量 | 用途 | 默认 |
| --- | --- | --- |
| `MYSQL_START_CMD` | MySQL 未运行时的启动命令（脚本自动调用） | - |
| `MYSQL_ROOT_PASSWORD` | Docker MySQL root 密码 | root123456 |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` | 业务库与应用账号 | project_manager / pm / Pm@123456 |
| `JWT_SECRET` | JWT 密钥（生产必改） | 示例值 |
| `UPLOAD_DIR` | 附件目录（Docker 内固定 /uploads） | ./uploads |
| `WEB_PORT` | 前端对外端口（Docker） | 8080 |

---

## 5. 数据库版本与迁移

- MySQL 8；Flyway 迁移位于 `backend/src/main/resources/db/migration`（V1–V4），应用启动自动执行。
- 当前库结构（业务表 8 张）：sys_user / dict_item / phase_template / project / project_phase / payment / contract / attachment / operate_log（逻辑删除，含 flyway_schema_history）。

## 6. Git 协作（SSH）

见 `docs/GITHUB-SSH-setup.md`：配置 ssh key → 切换 remote 为 SSH → 验证连通 → 推送。
