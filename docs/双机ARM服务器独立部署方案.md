# 双机 ARM 服务器独立部署方案评估

> 场景（2026-09-09）：两台 **ARM 架构** 服务器（已装 Docker），**每台独立部署一套完整前后端**并各自可对外服务；
> 两台指向**同一套崖山 YashanDB 主备**（10.254.212.106/.107:1688，Oracle 模式，schema 已由 v3.0 初始化并迁移数据）；
> 上层由用户自建的**网关负载均衡**统一入口。
>
> 结论先行：**推荐每台一份 Docker Compose**（现仓库 `deploy/docker/` 资产基本可直接复用），不建议裸机 jar+npm 分开部署。

---

## 1. 方案对比

| 维度 | **A. Docker Compose ×2（推荐）** | B. 裸机分开部署（后端 jar + 前端产物 + nginx/systemd） | C. K8s / K3s / Swarm |
| --- | --- | --- | --- |
| 现有资产复用 | ✅ `deploy/docker/` 已备好（compose + 双 Dockerfile + nginx conf），修复 1 处即可用 | ❌ `deploy/linux/*.sh` 是**开发模式**（mvn spring-boot:run + vite dev），不可直接上生产，需新写 systemd/产物目录/反代 | 仅当未来 >4 台或需滚动发布 |
| 双机同构部署 | ✅ 同一份 compose + .env，拷贝即同构 | 需两台逐一装 JDK17/nginx、维护产物与脚本 | 需搭建控制面，2 台收益低 |
| 升级 / 回滚 | ✅ 换 tag 或重 build 一次 `up -d`，compose 管理 restart/自愈 | 手工停服务、替换 jar/dist、再启动，易出错 | ✅ 但成本高 |
| ARM 支持 | ✅ maven/eclipse-temurin/node/nginx 官方镜像均有 arm64，**在服务器上直接 build** 即可 | ✅ 无镜像层，纯 Java + nginx arm 无碍 | ✅ |
| 故障定位 | 中（logs/exec） | 高（journalctl 直读进程） | 低（多层） |
| 运维心智 | Docker 单机编排，简单 | 系统服务 + 文件管理，传统但琐碎 | 重 |
| **主要成本** | 镜像构建、多一层抽象 | 两台手工初始化与每次发布的人工步骤 | 控制面维护 |

> 裸机方案唯一的实质优势是"少一层抽象、排查直接"，但本项目已有完整 Docker 资产且两台服务器均装好 Docker——
> 用 Docker 的边际成本远低于为"裸机"重建一套生产启动体系（systemd unit、nginx 反代、产物版本管理都要新写）。

---

## 2. 必须先解决的前置问题（与选型无关，双机 + LB 的硬前提）

### 2.1 附件存储必须跨机一致（P0，最容易踩）
- 附件写入 `backend/uploads`（容器内 `/uploads`），单机默认是**本地卷 `pm_uploads`**。
- 若两机各自本地卷，网关轮询下会出现"上传到 A、下载命中 B → 404"，合同/验收文档等核心附件会时好时坏。
- **处置（推荐）**：两机挂载同一 **NFS / 共享盘**，compose 里用 bind 目录替换本地卷：
  ```yaml
  # docker-compose.yml（已支持，见文件内注释）
  volumes:
    - ${UPLOAD_VOLUME:-pm_uploads}:/uploads
  ```
  两台服务器 `.env` 均设：`UPLOAD_VOLUME=/mnt/pm-uploads`（各自挂好同一 NFS 后，路径相同即指向同一存储）。
  同时把 NFS 目录纳入备份范围（附件是唯一"数据库外"的持久化数据）。
- 备选：改造成对象存储/S3（需改后端读写代码，本期不推荐）；网关对 `/uploads/*` 做源站亲和只能临时缓解，不解决"独立运行"目标。

### 2.2 JWT_SECRET 两机必须一致（P0）
- 后端 JWT 签名密钥来自 `JWT_SECRET`。两机若不同，A 签发的 token 在 B 验签失败 → 登录后请求随机 401。
- 两机 `.env` 填**相同**的 `JWT_SECRET`（>=32 字节随机串），并各自改掉内置演示账号默认密码。

### 2.3 ARM 构建与崖山驱动
- 后端是纯 Java（Spring Boot + 崖山 JDBC 驱动均为字节码），**跨架构无需重编译**；官方基础镜像（maven:3.9-eclipse-temurin-17 / eclipse-temurin:17-jre / node:20-alpine / nginx:1.27-alpine）均有 arm64。
- **在 ARM 服务器上直接 `docker compose build`**（源码放置到服务器），不要从 x86 机器拷贝镜像（除非用 buildx 多平台构建 + 私有 registry）。
- 首次启动做一次连通冒烟（见 §5），确认驱动在 arm 环境连接正常；若崖山驱动将来含 native 段则以厂商 arm 版为准（当前 jar 无）。

### 2.4 全新库的首次初始化竞态
- 现库（pm schema）**已完成 V1~V8 且已迁移数据**，两台启动时 runner 扫描 `schema_version` 全部"已登记跳过"，**无竞态**。
- 若未来在全新库环境双机同时首启：并发执行同版本 DDL 可能互相冲突。规则：**先只启动一台**完成建表登记，再启动第二台（幂等跳过）。

---

## 3. 推荐方案 A 实施步骤（每台服务器重复）

### 3.1 服务器准备
- 已装 Docker Engine + Compose 插件；能访问崖山 1688（主备两个 IP）。
- 代码放置：任一台拉取代码（或用发布 tar），例如 `/opt/pm/project-manager`。

### 3.2 共享附件目录（仅当双机 LB 场景）
- 部署 NFS：一台做 NFS server（或复用现有共享存储），两机挂载同一目录到 `/mnt/pm-uploads`；
- 验证：`touch /mnt/pm-uploads/.probe` 在两机均可见。

### 3.3 每台配置 `.env`
```bash
cd /opt/pm/project-manager
cp deploy/docker/.env.example deploy/docker/.env
vi deploy/docker/.env
```
```bash
# .env（两机除本机标识外一致；JWT_SECRET 与附件目录必须两机相同）
YASHAN_MASTER_IP=10.254.212.106
YASHAN_STANDBY_IP=10.254.212.107
YASHAN_DB=project_manager
YASHAN_USER=pm
YASHAN_PASSWORD=业务账号密码
JWT_SECRET=两台相同的随机长密钥
UPLOAD_VOLUME=/mnt/pm-uploads        # NFS 共享目录（单机场景留空用本地卷）
WEB_PORT=8080                         # 对外端口，两机可一致（网关按 IP 区分）
```

### 3.4 构建并启动
```bash
docker compose -f deploy/docker/docker-compose.yml up -d --build   # 首次构建需几分钟（拉依赖）
docker compose -f deploy/docker/docker-compose.yml ps
docker compose -f deploy/docker/docker-compose.yml logs -f backend
```
> 构建提示：若服务器拉 Maven/npm 依赖慢，可在 Dockerfile 前加国内镜像源（maven settings / npm registry），或改在能联网的构建机产出后导入。

### 3.5 验证（每台本机先验）
- `curl http://127.0.0.1:8080/api/health` → `{"code":0,...,"db":"up"}`（说明已连上崖山）
- 登录 admin → 项目列表/详情（含 CLOB 概览）
- **跨机附件验证（关键）**：在 A 上传一个附件 → 直连 B 的地址下载同一附件，确认可见（共享存储生效）。

---

## 4. 网关负载均衡侧要点（用户自建）

- 上游：`http://<服务器A IP>:8080`、`http://<服务器B IP>:8080`（WEB_PORT 映射的是前端 nginx）。
- **健康检查用 `/api/health`**（返回 db:up 才摘除/恢复节点）——它同时反映"后端进程 + 数据库连通"。
- 后端 8080 容器不对外映射端口（compose 已 `expose` 不 publish），公网只开前端端口，安全边界正确。
- TLS 终止放在网关；前端 nginx 已透传 `X-Forwarded-Proto`。
- 任一单机故障时另一台独立可用（无状态 API + JWT；附件依赖共享存储，NFS 若为单点需按可用性要求做冗余——见 §2.1）。

---

## 5. 冒烟 / 验收清单

| # | 用例 | 期望 |
| --- | --- | --- |
| 1 | 每台 `/api/health` | code=0, db=up |
| 2 | A 登录并调用列表 | 200（token 由 A 签发） |
| 3 | 用 A 签发的 token 直连 B 调列表 | 200（验证 JWT_SECRET 一致） |
| 4 | A 上传附件 → B 下载 | 文件一致可见（验证共享存储） |
| 5 | 停掉 A 的后端容器 | B 独立可用；网关自动摘除 A（health 探测失败） |
| 6 | 付款/阶段流转等写操作在 A、B 轮流执行 | 数据即时一致（同一崖山库） |
| 7 | （可选）主备 TAF 演练：停崖山主库 | 两机均自动重连备库（进行中事务会回滚，见实施方案 §13） |

---

## 6. 备选方案 B（裸机分开部署）要点——仅当不使用 Docker 时

仓库现有 `deploy/linux/start-dev.sh` 是开发脚本（`mvn spring-boot:run` + `vite dev`），**不建议直接用于生产**。若确要走裸机：

1. 后端：每台装 JDK17；`cd backend && mvn -DskipTests package` 产出可执行 jar（已含 system-scope 驱动，`includeSystemScope` 已配）；配 systemd `pm-backend.service`（Environment=YASHAN_* / JWT_SECRET / UPLOAD_DIR=/mnt/pm-uploads；Restart=always）。
2. 前端：`cd frontend && npm ci && npm run build`，产物 `dist/` 放到 nginx 站点目录；系统 nginx 反代 `/api`、`/uploads` → `127.0.0.1:8080`（前端 nginx conf 参考 `deploy/docker/frontend-nginx.conf`，将 `backend:8080` 改为 `127.0.0.1:8080`）。
3. 附件/JWT/首次初始化等约束与 §2 完全一致。
4. 版本升级 = 替换 jar + 重新 build dist + `systemctl restart pm-backend` + nginx reload。

> 裸机版需要额外维护 systemd unit、nginx 站点配置、产物目录与版本，且两台机器操作重复——除非有"禁用 Docker"的硬性要求，否则不推荐。

---

## 7. 结论

- **推荐：每台一份 `docker compose`（方案 A）**。双机同构、升级/自愈/回滚成本最低，现有 `deploy/docker/` 资产 + 本方案 §3 步骤即可落地。
- **本方案已配套的仓库改动**：修复 `Dockerfile.backend`（补 `COPY backend/lib`，否则 arm 服务器 build 必失败）；compose 附件卷支持 `${UPLOAD_VOLUME}`（默认本地卷、可切 NFS bind）；`.env.example` 增加 `UPLOAD_VOLUME` 说明。
- 两机上线前请逐项过 §5 冒烟清单，重点为 **JWT_SECRET 一致性** 与 **跨机附件读写**。
