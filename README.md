# 政府信息化项目管理系统 (project-manager)

面向单位内部的政府信息化项目全生命周期管理系统：**硬件项目 / 软件项目** 的立项 → 招投标/合同 → 实施 → 验收 → 质保运维全过程记录、阶段附件档案与资金付款跟踪。

> 需求/流程/字段/统计口径的权威说明见 [`docs/政府信息化项目管理系统-设计方案.md`](docs/政府信息化项目管理系统-设计方案.md)（设计与实现同步稿，含 §0 修订记录与 §13 实现差异清单，随迭代更新）。`demo/` 为纯静态交互原型（Mock 数据）。**开发迭代进度见 [`ITERATION.md`](ITERATION.md)。**

## 技术栈

| 端 | 选型 |
| --- | --- |
| 前端 `frontend/` | React 18 + TypeScript + Vite + Ant Design 5 + axios + react-router |
| 后端 `backend/` | Java 17 + Spring Boot 3.3 + MyBatis-Plus + Flyway + MySQL 8 |
| 认证 | JWT（jjwt）+ BCrypt，角色 ADMIN / MANAGER / VIEWER |

## 目录结构

```
.
├─ docs/         设计方案
├─ demo/         静态原型（评审用，Mock 数据）
├─ frontend/     React 前端
└─ backend/      Spring Boot 后端
```

## 本地运行

前置：JDK 17+、Node 18+、MySQL 8（启动脚本按平台分类，见 [`deploy/README.md`](deploy/README.md)；服务器可直接用 Docker 一体化）。

**一键启动（Windows）**：双击根目录 `start-dev.cmd`（自动检测/等待 MySQL，启动后端与前端并打开浏览器）；停止用 `stop-dev.cmd`。Linux 与 Docker 见 [`deploy/README.md`](deploy/README.md)。

或分步启动：

1. 建库（Flyway 会自动建表与种子数据）：

   ```sql
   CREATE DATABASE project_manager DEFAULT CHARACTER SET utf8mb4;
   ```

2. 后端（默认连 `jdbc:mysql://127.0.0.1:3306/project_manager`，账号 `pm/Pm@123456`，可用环境变量覆盖）：

   ```bash
   cd backend
   mvn spring-boot:run          # http://127.0.0.1:8080/api/health
   ```

3. 前端：

   ```bash
   cd frontend
   npm install
   npm run dev                  # http://localhost:5173（/api 代理到 8080）
   ```

4. （可选）灌入演示数据（走真实 API，会先清空现有演示项目）：

   ```bash
   node scripts/seed-demo.mjs         # 总项目/子项目 + 独立/部分共享/全部共享合同
   node scripts/seed-attachments.mjs  # 为各核算单元阶段补齐典型附件
   ```

预置登录账号（密码均 `123456`）：`admin`（管理员）/ `jingban01`（经办人）/ `lingdao01`（领导，只读）。

> 启动脚本按平台分类（Windows / Linux），并支持 Docker 一体化部署（环境打包）：
> 见 [`deploy/README.md`](deploy/README.md)（含环境变量、数据迁移、附件迁移）。
> Git 协作的 SSH 配置见 [`docs/GITHUB-SSH-setup.md`](docs/GITHUB-SSH-setup.md)。

## 接口约定

统一响应 `{ code, message, data }`，`code=0` 成功；写操作以 `POST/PUT/DELETE` + `/api/...` 前缀；附件经 `/uploads/**` 静态访问。
