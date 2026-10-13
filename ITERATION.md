# 迭代记录（开发进度日志）

> 本文件记录本系统从设计到前后端实现各迭代的进度、交付内容与验证情况，随每次迭代更新。

## 项目速览

| 项 | 内容 |
| --- | --- |
| 定位 | 单位内部政府信息化项目全生命周期管理（硬件 / 软件两类项目） |
| 前端 | React 18 + TypeScript + Vite + Ant Design 5（`frontend/`） |
| 后端 | Java 17 + Spring Boot 3.3.5 + MyBatis-Plus + 自研迁移 Runner（`backend/`） |
| 数据库 | 崖山 YashanDB（Oracle 模式）主备集群（主 10.254.212.106 / 备 10.254.212.107:1688，库/schema `PM`，业务账号 `pm`；v3.0 起，替代 MySQL 8 + Flyway）；迁移脚本 `db/migration-yashan/` **V1~V15** |
| 认证 | JWT + BCrypt，角色 ADMIN / MANAGER / VIEWER |
| 附件存储 | 抽象 `AttachmentStorage`：默认本地盘，可切**华为 OBS**（`app.storage.type`）；归属分四类 `PROJECT` / `PROJECT_PHASE` / `CONTRACT` / `PAYMENT` |
| 需求基线 | `docs/政府信息化项目管理系统-设计方案.md`（设计与实现同步稿，Q1–Q15 处置状态见 §11） |
| 脚本清单 | `scripts/README.md`（发版打包 / 演示数据 / 开发机数据库工具） |
| 当前版本 | **v3.7.0**（2026-09-23） |
| ai-backend | **`ai-1.2.0`**（2026-09-23）：Java 版 AI 能力服务（Spring Boot 3.3.5 / Java 17，**与主系统同栈**），**独立构建与部署**（默认 8100、独立镜像与发版，不动主系统）；附件解析 + 平台 OCR + 文档库/问答 + **检索链路（Embedding 召回 + Reranker 精排，向量后端可切 `local`/`opensearch`）** + **结构化引用 `citations`**（答案按 `[n]` 标注、可跳附件第 N 页）；接口 `/analyze`、`/documents`、`/upload-tasks`、`/chat`、`/admin/reindex`（全量重建索引，可中断续跑）；**发版链路与主系统对齐**（`make-release.ps1 -Project ai`，见 v3.6.2）；见 `ai-backend/README.md`。原 Python 版 `ai-service/` **已删除**（知识资产迁入 `ai-backend/docs/`） |
| 仓库 | GitHub `ggbonds6/project-manager`（main 分支，全程 git 管理） |

---

## 迭代总表

| 迭代 | 日期 | 主题 | 交付摘要 | 对应提交（Git） |
| --- | --- | --- | --- | --- |
| v0.1 | 2026-09-02 | 需求与设计 | 设计方案评审稿 + 静态 Demo（Mock 20 项目，9 张逻辑表） | `1e3d95d` |
| v0.2 | 2026-09-02 | 前后端骨架 + 认证 | Spring Boot + MyBatis-Plus + Flyway；前端骨架/登录/主布局；健康/字典接口 | `cc30866`、`0926947` |
| v0.3 | 2026-09-02 | 项目核心（M3/M4） | 项目 CRUD/筛选分页/自动编号/阶段推进/附件/付款/日志；前端列表卡片与详情五页签 | `abfd7f6`、`c4e4578`、`f9e8780` |
| v0.4 | 2026-09-02 | 演示数据与环境 | 本机环境配套 + seed-demo（10 项目）；修复自动编号撞唯一索引 | `d8aec3c` |
| v0.5 | 2026-09-02 | UI 优化与缺陷修复 | Hooks 崩溃修复；附件中心按归属分组/过滤/直达上传；迭代记录机制 | `2fd16fd`、`23f511c` |
| v0.6 | 2026-09-02 | 瀑布流 UI + 附件 Mock | 详情页瀑布流还原；seed-attachments 为各阶段补齐 255 附件（pdf 可预览） | `4fcf1fb` |
| v0.7 | 2026-09-02 | 工作台 + 统计（M5） | /api/dashboard 与 /api/stats/*；工作台页与统计页（ECharts，筛选联动） | `9c9e120`、`896e920` |
| v0.8 | 2026-09-02 | 系统管理（M6） | 管理接口（仅 ADMIN）：用户/部门/字典/阶段模板/操作日志；前端系统管理页 | `4f9e2b9`、`221c31d` |
| v0.9 | 2026-09-02 | 界面与交互评审调整 | 移除部门维度；搜索按钮化；付款节点可设置/清除；附件折叠分组+在线预览；日志评论样式 | `a65dc2a` |
| v1.0 | 2026-09-02 | 清理部门 + 标签/付款/预览升级 | 数据库/后端彻底移除部门（V3）；付款收敛管理员；付款节点横幅；标签字典 tagDict；筛选草稿化 | `c0dce44` |
| v1.1 | 2026-09-02 | 文档实时性机制 | 设计稿升级"设计与实现同步稿"（§0/§13/§14）；README/ITERATION 同步约定 | `59d440d` |
| v1.2 | 2026-09-02 | 一键启动 + 附件全屏 | start-dev.cmd/stop-dev.cmd；附件预览全屏（Esc/按钮退出，保留下载） | `9c7d4f8` |
| v1.3 | 2026-09-02 | 父子项目 + 合同模型 | Flyway V4：parent_id、contract 表、contract_id；合同可覆盖多子项目；统计口径=核算单元；前端折叠树/合同面板；种子 v2 | `4727858`、`7f625ef`、`7d0f56f`、`88385a0` |
| v1.4 | 2026-09-02 | 独立合同口径 + 容器化 UI | 合同一律独立（多覆盖拒绝）；顶层=汇总容器（隐藏流程/资金，展示子项目汇总）；二级列表分组卡片；seed v3 | `969f9c2` |
| v1.5 | 2026-09-07 | 评审迭代整理（本机） | 子项目/子项目页签卡片网格；资金情况"合同-付款"折叠父子结构+金额实时同步；合同附件与附件中心互通；列表/附件筛选多选；移除甲方单位筛选；部署按平台分类+Docker 一体化；SSH 指引；文档维护硬化 | `9a8e387`、`6f02c99` |
| v1.6 | 2026-09-07 | 附件：文件类型字典/白名单 + 在线预览扩展 | FILE_TYPE 字典（V5）与上传白名单；全局类型颜色组；集成 docx-preview / SheetJS / react-markdown 实现 docx/xlsx/md 预览；doc/ppt/pptx/ofd 等明确提示"暂不支持预览请下载"；存储位置与预览加载策略评估并写入文档 | `9f8c7bc` |
| v1.7 | 2026-09-07 | 附件预览试扩展（OFD / .doc） | 试集成 `@sharp9/ofdjs`（OFD 首页 Canvas）与 `.doc` 内容嗅探（实为 docx 才渲染）；不稳定/失败自动回落"请下载查看"，不影响其他格式 | `1609b79` |
| v1.8 | 2026-09-08 | 项目概览：README 式介绍 + 功能模块清单 | 新表 `project_overview`（V6：intro_md + modules_json）；详情接口带 overview；项目信息页新增"项目介绍(Markdown 渲染)/功能模块(两级)"展示与编辑；提供演示概览 mock SQL | `cc7a0c4` |
| v1.9 | 2026-09-08 | 流程模板线性化完善（看效果版） | 模板页按类型(HW/SW)线性维护增强：阶段上移/下移调序、复制阶段、权重合计提示、列表按顺序号排序；后端同类型阶段重名校验与项目类型校验 | `0069485` |
| v2.0 | 2026-09-08 | 流程模板设计器：Tab 多模板 + 画布/列表双视图 | V7：`phase_tpl` 多套模板（内置 HW/SW 默认 + 自定义），`phase_template.tpl_id` 归属回填；接口：模板 CRUD/复制/设默认 + 整模板阶段批量保存；前端系统管理「流程模板」改为设计器：Tabs 展示多套模板、新增模板（可选复制起点）、画布视图（节点拖拽排序 + 点击节点配置）、列表视图双切换；项目创建按类型默认模板生成阶段 | `d0289b4` |
| v2.1 | 2026-09-08 | 阶段说明/关键材料/画布布局（数据层） | Flyway V8：`phase_template.guide`(阶段说明)、`key_materials`(关键材料)；`project_phase` 快照同字段（创建项目时随模板带入，详情阶段 VO 透出）；`phase_tpl.flow_json`(draw.io 式节点坐标+连线)；模板保存/复制同步字段；模板 GET/{id} 与 PUT flow 接口 | `ec38485` |
| v2.2 | 2026-09-08 | X6 画布编辑器 + 模板展示/列表折叠 + 详情阶段说明折叠 | 前端：流程模板画布改为 **AntV X6**（节点自由拖放/连线/点击节点配置，配置新增"阶段说明 guide/关键材料 key_materials"；保存按连线拓扑/位置确定阶段顺序）；视图增加「展示」（竖向+折叠查看说明/材料）；「列表」改折叠卡片同显描述；项目详情「流程进展」阶段卡片新增「阶段说明·关键材料」折叠区 | `0f3aa67` |
| v2.3 | 2026-09-08 | 去除画布、纯阶段列表 + 全面阶段说明 | 移除 X6 画布及相关代码与依赖；流程模板只保留 Tab 多模板 + **折叠阶段列表**（面板默认收起，展开显示 说明/做什么/关键材料/常用附件/操作）；清理 SystemPage 遗留旧模板代码；新增 `scripts/demo-phase-guides.sql` 为内置 HW/SW 共 20 阶段补齐"目的/主要工作/要点/完成标准/注意/关键材料"演示数据 | `741de1c` |
| v2.4 | 2026-09-08 | 阶段表单弹窗修复 + 冗余清理 | useFormModal 默认宽度加大、阶段表单标签改短并把提示说明放到输入框下方（不再被输入框遮挡）；阶段列表去掉"有说明"标签；移除画布遗留代码/接口与 `phase_tpl.flow_json` 字段引用（含 GET/{id}、saveFlow） | `0739c77` |
| v3.0 | 2026-09-09 | 数据库国产化：MySQL → 崖山 YashanDB（Oracle 模式） | 依赖/配置/Java 方言/7 处手写 SQL 全量改造；`db/migration-yashan/` V1~V8（yashan 方言：VARCHAR(n CHAR)/CLOB/TIMESTAMP/NUMBER/identity）自研 `YashanMigrationRunner` 替代 Flyway；驱动 `yashandb-jdbc-1.9.3.jar` 本地引入（system scope）；主备驱动级 primary+TAF 高可用连接；deploy/README/docs 全面清理 MySQL 痕迹；PoC 关键项实库验证通过；11 张表数据全量迁移核验一致、本机 MySQL 下线 | `295d1b0` |
| v3.1 | 2026-09-09 | 附件存储对象化（华为 OBS） | 新增 `AttachmentStorage` 抽象（`app.storage.type=local\|obs`，默认 local）；OBS 走 esdk-obs-java（path-style + 忽略证书校验），对象置于桶内 `uploads/` 前缀（与本地相对结构一致）；`AttachmentController` 存储无关化 + 流式下载；`file_path` 即对象相对 key、存量附件已上传桶、元数据零迁移、前端零改动；compose/.env 透传 `APP_STORAGE_*`；存量上传工具 `UploadExistingToObs`（test scope）；对象 key 对齐桶内 `uploads/` 前缀 | `8374e54`、`75877a7` |
| v3.1.1 | 2026-09-10 | 部署资产整理与库名口径修正 | **服务器不存源码**模型定稿：新增 `deploy/docker/docker-compose.deploy.yml`（无 `build:` + `pull_policy: never`），发布包以 `docker-compose.yml` 之名下发，运行目录只需该文件 + `.env`（实测裸目录启动 db:up / 前端 200）；清理 `demo/`（静态原型，零引用）与 `deploy/linux/`（无执行场景）净删 1779 行；崖山库名 `project_manager` → **`PM`** 全仓库统一（含 application.yml / compose / .env.example / README / 两本手册）；deploy/README 重构为生产-本地-源码三段 | `e7f9754` |
| v3.2.0 | 2026-09-10 | 附件后台上传 + 大文件体验修复 | **超时根因（三层叠加）**：axios 全局 `timeout` 30s（最先触发）+ nginx `proxy_read_timeout` 60s + OBS 客户端 `socketTimeout` 60s，大文件同步写对象存储必超时。**改为两段式**：`POST /attachments/upload` 先把文件暂存服务端（`app.upload-tmp-dir`）并登记任务后**立即返回**，后台线程池（2~4）推存储并回写进度；新表 `attachment_upload_task`（迁移 **V9**）即上传记录，新增 `GET /attachments/upload-tasks[/{id}]`；`AttachmentStorage.save` 增 `ProgressCallback`（OBS 用带 `ProgressListener` 的重载，本地按流计数）；服务启动把残留任务标失败。**超时放宽**：上传 axios 10 分钟、nginx `client_body_timeout/proxy_*_timeout` 300s、OBS `socketTimeout` 300s。**前端**：上传弹窗显示"传输进度 → 后台进度"两段并内置上传记录列表，可关闭不中断；预览 >10MB 先提示"较大、可能较慢"并给「下载 / 仍要预览」二选一，各加载态显示文件大小。**本机 local 附件读不到（目录不匹配）**：Docker 用独立命名卷 `docker_pm_uploads`，而历史 498 个附件在宿主机 `backend/uploads`，已 `docker cp` 迁入卷（现 499 个），下载校验字节数与库中 `file_size` 一致 | `4c5153a` |
| v3.3.0 | 2026-09-10 | 上传体验修复：上限提高 + 入口独立化 + 升级脚本自动切版本 | **上限**：实测 110MB 文件报 `FileSizeLimitExceededException`（原 `max-file-size=100MB`）→ 提到 **500MB**（请求 600MB），nginx `client_max_body_size` 同步 200m→**600m**；`GlobalExceptionHandler` 新增超限友好提示（原先落到通用 500「系统繁忙」），并在通用分支识别异常链里的超限特征。**入口独立化**：上传不再要求保持弹窗打开——新增全局 `UploadTaskProvider`（`store/uploadTask.tsx`）持有任务状态与轮询，新增 `UploadTaskCenter`（按钮+弹窗，置于附件中心「上传附件」同级）统一展示进度与历史，含**所属阶段标签**（后端 `upload-tasks` 批量补齐 `phaseName`）与**文件类型标签**、状态/进度/失败原因；`AttachmentUploadModal` 精简为"选文件+类别"即关窗；任务成功后项目详情页自动刷新附件列表。**升级脚本**：`pm-upgrade.sh` 原先不更新 `IMAGE_TAG`（load 完仍按旧版本启动 = 升级无效，须手工改 .env），改为从镜像包名解析版本并自动备份+写入 `.env`。**实测**：110MB 上传 4.24s 返回任务、后台推送 0.8s 完成、下载 110000000 字节 cmp 一致；阶段归属上传返回 `phaseName:"终验"` | `bf1cc96` |
| v3.3.1 | 2026-09-11 | 修复表单弹窗编辑回显错位 + 开发机一键重建脚本 | **回显 bug**：`useFormModal.open()` 在 `setState` 之后**同步**调 `form.resetFields()`，此刻 React 未重渲染、Form 的 `initialValues` 仍是上一次的 → 重置回旧值；而 rc-field-form 在 `initialValues` 变化时只更新内部记录、**不自动填充已有字段**，导致编辑时总显示第一次点开的数据。修复：把「`resetFields()` + `setFieldsValue(initial)`」移入 `useEffect`（依赖 state），待 Form 以新 `initialValues` 渲染完再执行。**该 hook 有 4 处复用**（SystemPage 用户管理/基础字典、FlowTemplateDesigner、ProjectDetailPage 合同弹窗）一并修好。**新增 `scripts/dev-reload.sh`**：开发机一键重建镜像+重启（`all\|backend\|frontend`，实测只重建一端时另一端保持不动），与发版脚本 `make-release.sh`/`pm-upgrade.sh` 职责分离 | `2b36154` |
| v3.4.0 | 2026-09-17 | 合同管理 tab + 项目分工 tab | **合同管理**：一个项目（含子项目）可签多份合同——施工合同（主合同）/ 第三方测评 / 方案评估 / 监理服务 / 项目设计 / 预算编制。迁移 **V10** 为 `contract` 扩充政府合同常见字段（合同类型、甲方、签订·生效·工期起止日期、合同状态、收款户名/开户行/账号、验收标准、质保期/质保金、结算金额；**乙方沿用 `vendor_name`** 不再另设 `party_b` 以免冗余）；新增字典 `CONTRACT_TYPE`(7 项)、`CONTRACT_STATUS`(5 项)。**项目分工**：新增 `project_division` 表——`parent_id` 支持「模块 → 子模块」层级，记录负责方（甲/乙/双方）、甲乙负责人、计划开发·调试·上线日期、进度 0–100、状态、备注；新增字典 `DIVISION_STATUS`(4 项)。**前端**：项目详情页新增两个 tab（置于「资金情况」之后）——「合同管理」用**折叠列表**（收起只露 类型/状态/编号/金额/已付/乙方，展开依次为 ①合同双方 ②**付款账户**（黄底高亮、账号等宽字体，付款前必核）③金额·质保·验收标准 ④该合同的付款节点），「项目分工」用**树形表格**（负责方标签、甲乙负责人两行、三阶段计划、进度条、状态标签；操作列支持「编辑 / 加子模块 / 删除（级联）」）；配色统一在 `config/tagDict.ts`（`CONTRACT_TYPE_TAGS` / `CONTRACT_STATUS_TAGS` / `DIVISION_STATUS_TAGS` / `OWNER_SIDE_TAGS`），与字典 code 一一对应。**后端**：`Contract` 实体/DTO/`apply()` 扩展 14 个字段；新增 `ProjectDivision` 实体 + Mapper + Controller（列表返回**扁平结构**由前端组树；删除**级联子模块**；校验上级同项目且**防循环引用**）。**实测**：V10 迁移执行成功（共 **10** 个脚本）、两个新字典就位；分工新增「数据采集模块」+「数据校验子模块」并正确返回层级；合同新字段映射正常 | `5ce3467` |
| v3.5.0 | 2026-09-17 | 资金/合同职责重划分：资金情况改为以付款为主线 + 合同附件独立归属 + Mock 数据充实 |**问题**：两个 tab 都在讲合同（资金情况以合同为父节点折叠、合同管理也挂付款明细），职责重叠、各自都不完整。**重划分**——「资金情况」= 一笔付款一条记录，「合同管理」= 合同登记 + 合同附件。**迁移 V11**：`payment` 补付款过程字段（`pay_method` 付款方式 / `handler` 经办人 / `invoice_no` 发票号 / `voucher_no` 记账凭证号 / `payee_name`·`payee_bank`·`payee_account` **收款账户快照**，付款当时留痕）；新增字典 `PAY_METHOD`(5 项) 与 `PAY_REQUIRED_ATTACH`（报销所需附件清单，界面据此做缺件提醒）；`ATTACH_TYPE` 扩 8 项（发票 / 付款审批单 / 法务意见书 / 合同会签表 / 授权委托书 / 履约保函 / 合同变更协议 / 供应商资质）；**历史合同附件归位**（`attach_type=CONTRACT` 且挂在项目/阶段上的，统一改挂 `biz_type=CONTRACT` + `biz_id=合同id`）；`attachment` 新增 `(biz_type,biz_id)` 索引。**后端**：`Payment` 实体 / `PaymentSaveRequest` / `PaymentVO` / `apply()` 扩 7 字段；附件 `BIZ_TYPES` 增加 `CONTRACT`；`GET /projects/{id}/attachments` 汇总本项目**合同链**（自身+各级父项目）上的合同附件并补 `bizName`（合同名），上传记录接口同步补 `bizName`。**前端**：「资金情况」弃用「合同为父的折叠面板」，改为**表格 + 可展开行**——列 = 付款节点（含触发条件）/ 状态 / 实付·计划金额 / 付款日期 / 付款方式 / 关联合同（类型标签）/ 收款方 / **报销凭证 n/4** / 操作；展开行只放**关联信息**（付款明细与留痕、本次付款的收款账户快照、报销所需附件清单带缺件红标与逐项上传、已上传凭证列表）；顶部汇总卡 + 筛选（状态 / 合同 / 关键词）+ **风险提示条**（未关联合同的付款、已付款但报销缺件）；「合同管理」把付款明细表换成**付款进度结论**（节点数 / 已付 / 待付 / 已付笔数，并指向资金情况），新增**合同附件区**（按 合同正本 / 法务与审批 / 招标与投标 / 担保与保证 / 其他 分组展示 + 上传，`bizType=CONTRACT`）；附件中心新增「合同：xxx」分组；付款登记表单补付款方式 / 经办人 / 发票号 / 凭证号 / 收款账户（**新增时默认从合同带入**）；配色新增 `PAY_METHOD_TAGS`。**Mock 数据**：`seed-demo.mjs` 重写为 v4——每个核算单元 **2~4 份合同**（施工主合同 88% + 监理 2.5% + 第三方测评 3.5% + 预算编制 1.2%，供应商档案含收款账户与乙方项目经理）、主合同 **5 个付款里程碑**（30/40/20/7/3）含部分付款与差额场景、**项目分工**按 HW/SW 模板生成模块与子模块（含风险阻塞项）；`seed-attachments.mjs` 扩为三类附件（阶段 / 合同 / 付款凭证），并**故意让约 2/5 的已付款少传一类凭证**用于验证缺件提醒。**🔴 顺带修掉一个结构性缺陷（V12）**：原先把「项目挂合同」交给 `project.contract_id` **单个指针**，导致**一个项目实际只能有一份合同**——再登记第二份会覆盖指针、把上一份变成孤儿，而 `cleanupOrphanContracts` 下次会把它**悄悄逻辑删除**（数据会丢）。V10 的「一个项目多合同」其实只做了一半（灌数据时暴露：9 个项目只留下 9 份合同）。**迁移 V12** 新建 `project_contract` 关联表作为**权威关联**（一个项目 N 份合同，一份合同 1 个项目），回填历史并**排除已逻辑删除的项目**；`project.contract_id` 保留但语义收窄为「主合同」指针，由新增的 `ContractLinkService.syncPrimaryContract` 自动指向 MAIN 类型合同（无 MAIN 取最早一份），使项目卡片/统计里既有的「合同金额」口径继续表示主合同。新增 `module/project/service/ContractLinkService` 统一承载「某项目可见哪些合同 / 某合同覆盖哪些项目 / 重建与解除关联 / 清孤儿合同」，并改造 `ContractController`（列表 MAIN 优先排序、增删改走关联表）、`PaymentController`（付款归属校验，多合同时不允许「猜」归属）、`AttachmentController`（合同附件可见范围）、`ProjectService`（级联删除解除关联、孤儿清理按关联表且在用项目）、`StatsService`（合同年度归集、已付汇总）。**实测（修后）**：V11 / V12 迁移均成功（共 **12** 个脚本）；同一项目 **3~4 份合同**（施工主合同 + 监理 + 第三方测评 + 预算编制），全库 **25 份合同 / 77 笔付款 / 128 条项目分工 / 399 个附件**（阶段 217 · 合同 102 · 付款凭证 80），主合同指针与关联表一致（有效关联 25 条 = 合同 25 份） | `9b43b60` |
| v3.5.1 | 2026-09-17 | 修复前端 React #310 + 演示数据补全 + 死代码清理 | **🔴 React #310（hook 顺序）**：v3.5.0 新加的 6 个 hook（`payNodeOrder`/`contractById` 两个 `useMemo`、三个筛选 `useState`、`filteredPayments`）被放在了组件里 `if (!detail) return …` **之后**——首屏（`detail` 为 null）走提前 return 少调这些 hook，加载完成后再调，数量不一致即抛 `Rendered more hooks than during the previous render`（#310）。修复：全部**前移到提前 return 之前**，并加注释说明"hook 必须在任何提前 return 之前"。已加结构性自检：组件内提前 return 之后 hook 数为 **0**（共 34 个 hook 全在其前）。**演示数据补全**：合同补 `contractStatus`（按推进度自动给 DONE/CHANGED/ACTIVE）、`settleAmount`（结算后才有）、`remark`、服务类合同的 `scopeRemark/验收标准`；新增 **4 份"方案评估"合同（状态=待签订、无付款记录）**用于演示状态标签与"零付款节点"的合同；核算单元合同数由 2~4 份扩到 **2~5 份**。**新增开发机工具**：`scripts/demo-reset.sql`（**物理**清空演示数据，解决"逻辑删除导致库里堆积 `deleted=1` 历史行、翻库时误以为当前数据缺字段"）、`scripts/db-sql.sh` + `scripts/jdbc/RunSql.java`（开发机无 yasql 客户端时手工查/改库，连接信息自动从容器环境变量或 `.env` 取，**口令不打印不落盘**）、`scripts/README.md`（脚本清单与用法）。**死代码清理**：前端 `tsc --noUnusedLocals --noUnusedParameters` **清零**（ProjectDetailPage 的 `Timeline`/`Typography`/未用 `useWatch`，以及 FlowTemplateDesigner/PhaseEditModal/ProjectOverviewPanel/StatsPage/SystemPage 的既有未使用导入）；后端未使用导入 **8 处清零** + 删掉死方法 `ContractController.normalize`（另 3 个疑似死方法是 `this::method` 方法引用，经核对**保留**）；`seed-attachments.mjs` 删掉只写不读的 `nameCache`。**实测**：清库重灌后 **29 份合同 / 77 笔付款 / 128 条项目分工 / 412 个附件**（阶段 217 · 合同 114 · 付款凭证 81）；`contract` 表 `deleted=1` 行数 **0**、在用合同字段缺失 **0**（仅 `settle_amount` 有 21 份为空——未结算，符合业务）；合同状态分布 ACTIVE 16 / DONE 6 / DRAFT 4 / CHANGED 3；类型分布 MAIN 9 / SUPERVISE 9 / EVAL 4 / TEST 4 / BUDGET 3。**约定（两条，用户明确要求）**：① 前端改动不再由 AI 起浏览器做端到端验证（结构性检查 + 数据/接口核对即可，交互效果由用户刷新确认）；② **不主动重建镜像**（`dev-reload.sh`/`make-release.sh` 耗时且会重启容器，需要时先问）。两条已写入部署手册 §2.1、`scripts/README.md` §5 与项目记忆。**文档补齐（用户指出「没更新完」）**：`ITERATION.md`「各迭代明细」补 11 个缺失版本（v1.7 / v2.1~v2.4 为历史遗留，v3.2.0~v3.5.1 为本轮相关），现**总表 33 行 == 明细 33 段**；「项目速览」「功能完成度」「运行方式速查」「后续待办」同步重写。《设计方案》逐章同步：§5.3 附件归属改四类、§5.4 补付款留痕与收款账户快照、**新增 §5.5 合同字段**、§6.2 页签五→**八**、§8 表数 **11→14**（补录 `attachment_upload_task`）、§13 差异清单时点 v3.0→**v3.5.1** 并补 21~32 条（第 27 条显式修正第 8 条的旧口径）、§14 维护约定补「总表与明细两处版本须一一对应」；修正目录 4 条失效锚点。 | `e76a183`、`7e65171` |
| ai-0.4.0 | 2026-09-18 | **ai-service：移除本地 OCR + P0 规范化** | **只走内网平台 OCR**：同页实测平台 `PaddleOCR-VL-1.6-0.9B` 金额（`7,780,000.00`/`5,446,000.00`/`2,334,000.00`）与大写「柒佰柒拾捌万元整」**全对**，本地 RapidOCR **金额全丢**（只有合同编号）→ 删除 `ocr_engine.py`、`scripts/selfcheck_ocr.py` 与 `rapidocr-onnxruntime`/`pillow`/`[paddle]` extra，`OCR_PROVIDER`/`OCR_DPI`/`OCR_WORKERS` 废弃，平台不可用**报错不降级**，镜像去掉 `libgl1`/`libglib2.0-0`/`libgomp1` 与构建期 OCR 自检（改为只校验包能导入）；**P0 规范化**：ruff（lint+format）+ pytest（大写金额解析 / 平台 OCR 响应解析 / 任务状态机 / `calculate` 白名单）+ 依赖加上下界 + `print`→`logging`，新增 `[dev]`/`[scripts]` extra 与 `scripts/check.sh`/`check.cmd`（**本机优先、Docker 兜底**；Python 装于 `E:\env\python-3.11.9`、venv 在 `E:\env\venvs\pm-ai`，`ruff check + format --check + pytest` 本机 **0.2 秒**跑完）；工具集 **4→3**（删 `list_documents`）、`calculate` 改 `Decimal`（**返回字符串** `result`/`rounded_2`，避免 JSON 浮点丢分位）并去掉 `**`/`//`/`%`；版本 `0.1.0`→**`0.4.0`**；**Java 迁移最大障碍（本地 OCR 无 Java 等价物）随之消失**，但时机不变（等检索层与接口冻结） | `9e7ea27`（四份文档归档）、`0440563`（v0.4.0）；v0.3 基线 `4240363` |
| ai-0.5.0 | 2026-09-18 | **ai-service：检索链路接通（Embedding 召回 + Reranker 精排）** | 平台已部署 **Qwen3-VL-Embedding-8B**（**固定 4096 维**；⚠️ 平台实际部署**不支持 MRL 降维**，传 `dimensions` 实测 **HTTP 400**，故 `VEC_EMBED_DIMENSIONS=0` 表示**不传**）与 **Qwen3-VL-Reranker-8B**（网关 `http://10.254.208.35:8090/v1`，与千问对话/平台 OCR **共用同一把 sk**，手册 `ai-service/Qwen3-VL-Embedding-Reranker调用手册.md`）；新增 `vec_client.py`（纯 urllib：`/embeddings`、Jina 风格 `/rerank`、`GET /models` 探活、`cosine`）、`retrieval.py`（**三步链路**：向量召回 + 关键词召回（字符 2-gram + IDF，原在 `tools.py`，本轮**搬迁**）→ 按 (doc_id, page_no, 文本 sha1) 去重融合 → Reranker 精排取 top_k）、`scripts/vec_try.py`（`--health` / `--selftest` / `--ingest` / `--search` / `--top-k` / `--doc-id` / `--offline`）、`tests/test_retrieval.py`（5 条离线用例：精排顺序生效 / 向量不可用降级关键词且 note 写明 / 切片向量走缓存（第二次只为 query 编码）/ 切片键随内容变化 / **`VEC_BACKEND=opensearch` 显式报错**）；`tools.search_documents` 改为**委托** `retrieval.search()`（工具签名不变，返回体新增 `retrieval`/`reranked`/`note`）；新增配置 `VEC_BASE_URL`/`VEC_API_KEY`（默认复用 `LLM_*`，`VEC_TIMEOUT=300`）/`VEC_EMBED_MODEL`/`VEC_RERANK_MODEL`/`VEC_EMBED_DIMENSIONS=0`（**不传 dimensions**；平台不支持 MRL 降维，实际 4096 维）/`RETRIEVAL_RECALL=50`/`RETRIEVAL_TOP_K=5`/`VEC_BACKEND=local`/`OPENSEARCH_*`（索引 `pm-ai-chunks`）；`GET /health?with_vec=true` 返回 `vec` 块（ok/detail/models/backend/embed_model/rerank_model/dimensions）；向量缓存在 `work/vectors/<doc_id>.json`（含 model/dimensions，切片内容变了自动失效；**向量与索引都是可重建物，不进主系统**）；向量服务不可用时**降级为关键词检索**并在 `note` 写明原因（与 OCR 降级性质不同：答案仍带页码来源）；**实测**：开发机跑通**离线接线演练**（`--offline`：解析一个文本型 PDF → 1 个切片 → 混合检索命中并带页码与分数），质量门 **55 用例**全绿 + `ruff check` / `ruff format --check` 通过；**未验证（如实）**：开发机连不上政务内网网关（`URLError: timed out`），真实向量化/重排/语义判别**尚未验证**，需在有内网访问的机器上跑 `python scripts/vec_try.py`（默认自检）；**检索层存储选型统一为 OpenSearch**（崖山内核自带向量能力仅作备选、不作选型基线），**适配层尚未实现**（`VEC_BACKEND` 只支持 `local`，配置成 `opensearch` 会显式报错而非静默降级），**下一步：部署 OpenSearch 并补适配层** | `2e729e9` |
| ai-1.0.0 | 2026-09-20 | **AI 能力服务全量 Java 化：`ai-service`（Python/FastAPI）→ `ai-backend`（Spring Boot 3.3.5 / Java 17）** | 与主系统**同栈**、仍**独立构建与部署**（默认 8100）。四类能力全部移植：**平台 OCR**（`PlatformOcrClient`：批量 ≤16 页/请求、并发 12、"批量响应 blocks 在 `results[i]` 不在顶层"的坑、失败页占位**不兜底**、健康探针带 TTL 缓存）、**PDF 解析**（PDFBox 3 替代 PyMuPDF：页数 / 逐页文本 / 按 DPI 渲染 JPEG q85）、**确定性校验**（`Checks`：`BigDecimal` 大写金额解析与互校、比例合计容差 ±1、数值写法规整、答案数字可溯源；Java 正则显式 `UNICODE_CHARACTER_CLASS`，`BigDecimal` 一律 `compareTo` + `stripTrailingZeros` 归一）、**大模型**（`LlmClient` / `Prompts`（提示词**逐字照搬**）/ `ToolAgent`（8 轮工具循环 + 轮数用尽禁用工具再问）/ `QaService`（单条 system 必须在最前）/ `AnalyzeService`）、**检索链路**（`VecClient` + `RetrievalService`：字符 2-gram + IDF 关键词 + 向量召回 → 去重融合 → Reranker 精排；向量缓存 `work/vectors/`；向量/重排不可用**降级关键词并写 note**）；新增 `OpenSearchIndex` kNN 适配层（**未实测**：集群未部署；配 `opensearch` 时**显式报错、不静默降级**）。接口层：`/health`（`with_ocr`/`with_llm`/`with_vec` 三探测）、`/analyze`、`/ocr/pdf-info`、`/ocr/file`、`/documents`（增删查）、`/upload-tasks`（**先返回、后台解析**、状态机、取消/移除、重启把残留标失败）、`/chat`；响应形状与 Python 版**逐字段一致**（成功 `{code,data}`，失败 HTTP 状态码 + `{"detail"}`）。**踩坑（都已写进代码注释）**：Lombok 注解处理器必须显式配 `annotationProcessorPaths`（否则满屏"找不到符号"、看着像缺类）；`@Async` 自调用走不到代理（改显式线程池提交）；`StoredDoc.PageInfo.page_no` 必须 `@JsonProperty`（否则页码静默丢失、整页被误判成空白页）；`.env` 需 `spring.config.import` 才等价于 Python 的 dotenv。**实测（真实内网网关 `10.254.208.35:8090`）**：扫描件平台 OCR 正确识别《中标通知书》正文；`/analyze` 8.73s（tokens 1375+779）输出带 `[P1]` 来源与置信度的结构化 markdown；上传任务 `QUEUED→DONE` 1.47s；`/chat` 10.27s、工具轨迹 `search_documents×2 → read_page`，对上"中标金额"给出金额并主动标注"千分位是中文逗号、**存疑**请核对原件"，对"付款方式"如实回答**文档中未找到**并给出依据。**74 个 JUnit 用例全绿**（`ai-1.0.1` 收口时为 81）；`Checks` 与 Python 版做过 **35 个用例的逐字段差分对照，完全一致**。Python 侧代码/脚本/文档的清理见下一轮 | `7f232c9` |
| ai-1.0.1 | 2026-09-20 | **清理与回归单一主线：删除 Python 侧 `ai-service`，知识资产迁入 `ai-backend`** | Python 侧代码/脚本/测试/文档整体删除（`ai-service/` 50 个受控文件：2 份手册迁入 `ai-backend/docs`、其余 48 个删除），只保留**仍然有价值的知识**并迁入主线：两份只读接口手册（`平台OCR调用使用手册.md`、`Qwen3-VL-Embedding-Reranker调用手册.md`）与四份规划文档（架构评估与规范化、知识库总体架构与演进路线、知识库落地实施方案、embedding 端点部署方案）落 `ai-backend/docs/`，另新增《平台能力实测结论》把散落在 Python README 里的实测数字与踩坑结论固化下来（含平台 OCR 渲染参数/印章编造/并发、置信度四层口径、思维链吃光额度、单条 system、Embedding 不支持 MRL 降维、Rerank 返回入参下标、质量门用例数）；合成样本迁到 `ai-backend/work/samples` 并同步 `verify-e2e.ps1`；**保真修正**：PDFBox 逐页文本换行归一（`\r\n`→`\n`）且整页空白回空串，使 Java 与 PyMuPDF **逐字节一致**（电子版 182==182、扫描件 0==0，此前是 191 vs 182 / 1 vs 0）；旧 Python 服务容器退役（端口让给 Java 版）；全部相对路径引用（手册位置、样本路径、curl 示例）同步更新；**OpenSearch 仍待集群部署**（适配层已就绪、配 `opensearch` 显式报错） | `4301cf6` |
| docs-1.0 | 2026-09-20 | **文档与脚本集约化重整（跨主系统 + AI 能力服务）** | ① **修表格格式**：ITERATION 总表内夹了空行，导致 `ai-1.0.0`/`ai-1.0.1` 两行被"踢出"表格（Markdown 里**空行即结束表格**）——已去除；全仓脚本体检另修 1 处单元格裸竖线（厂商手册表格里**裸竖线 → `\|`**）。② **建两张地图**：新增 [`docs/README.md`](docs/README.md)（**唯一文档地图**：按角色导读 + 权威文档清单 + 已核减登记 + 维护约定）、重写 [`scripts/README.md`](scripts/README.md) 为**脚本与操作唯一清单**（场景速查表 + Shell 要求 + 分组明细；如实记录本机**无 Git Bash**、`scripts/*.sh` 暂时跑不了，并给出两条替代路径）。③ **核减过程性文档**（理由：一次性/已完成/已被取代的计划留在仓库只会制造歧义）：崖山两份（可行性分析 + 迁移实施方案）压缩为《崖山数据库与迁移约定》、知识库两份合并为《知识库实施方案与路线》、附件智能处理自测方案压缩为《附件智能处理-验收标准与现状》、双机 ARM 与 Gitea 两份并入《部署与发布全流程手册》附录 A/B、删除《embedding 端点部署方案》（平台已直接提供）《架构评估与规范化方案》（结论已在迁移对照表与 ITERATION）《流程模板图形化评估》（功能已于 v2.3 移除）。④ 根 `README.md` 增加"两张地图"入口，并把"文档地图同步""Markdown 表格两禁忌"写入硬性维护约定。 | `fc179f5` |
| docs-1.1 | 2026-09-20 | **重要文档同步性审计 + 目录整理（仓库上移）+ AI 前端方案评估** | ① **同步性审计**（README / ITERATION / 设计方案 vs 现状）：发现并修 **2 处**——根 README「当前版本」仍写 v3.5（改为 主系统 v3.5.1 ｜ AI ai-1.0.1 ｜ 文档 docs-1.0）、设计方案 §7 页面结构里的项目详情页签仍是 5 个（与 §6.2 的"v3.5 起最多 8 个"不一致，已统一为 8 个）；核对无误的项：数据模型 **14 张表**（V1 建 9 张含 `sys_dept`、V3 已删 → 实存 14，与设计稿一致）、崖山/MySQL 表述均已是历史口径、脚本与端口与 `scripts/README.md` 一致。② **Python 残留排查**：全仓**无** `.py`/`pyproject.toml`/`requirements*`/`__pycache__`/`.venv`/`.pytest_cache`/`.ruff_cache`；构建资产（Dockerfile/compose/yml）里无 Python 基础镜像与依赖；仅剩 Java 注释与文档中的**历史溯源提及**（如"移植自 `checks.py`"）。③ **目录整理**：删除 `project/` 下无用内容（最初静态原型 `demo/`、已被现设计稿 §11 覆盖的 `设计方案-v0.1`、npm 缓存 `.devtools/` 1660 项、跑服务留下的 `work/`、0 字节文件），把**本机专用资产**（数据库导出/口令清单/SSH 公钥 + 两个本机启动助手）收进仓库内 **`local/`（已 gitignore）**；仓库由 `Desktop\project\project-manager` **上移到 `Desktop\project-manager`**，旧 `project\` 已删除；启动助手的 `ROOT` 改为相对路径 `%~dp0..`。④ **AI 前端方案**：新增 [`docs/AI前端与集成方案.md`](docs/AI前端与集成方案.md)——评估三条路线后**否决"独立 React 前端"**，推荐**并入主系统前端**（新增「AI 与知识库」菜单 + **全局悬浮对话图标**），含知识库面板五页签设计、主系统代理与 V13（`attachment_ai_task`/`ai_index_status`/`ai_ask_log`）、nginx 同源与鉴权边界、P0~P3 分期（P0 3~5 人日）与风险对策。⑤ 新增 `scripts/check-docs.mjs` 回显扫描根（默认扫当前工作目录，在仓库外执行会扫错对象） | `cc73509`（§⑤ 为 `a4cb785`） |
| v3.6.0 | 2026-09-20 | **AI 与知识库 P0：并入主系统前端 + 主系统代理层（V13）**| ① **前端**：新增「AI 与知识库」菜单 `/ai`（文档库／任务队列／服务自检三个**真正可用**的页签，不放占位）；**全局悬浮问答 Drawer**（与 `/ai` 页**共用同一份对话组件**，顶部显式显示并默认跟随页面范围：项目详情页=该项目）；**引用可点击跳原文第 N 页**——为此给 `AttachmentPreviewModal` 加**纯增量**可选参数 `pageNo`（仅 PDF 拼 `#page=N`，不传时行为不变），`attachmentId` 由主系统回填、映射不到则显示"附件已删除"而不做坏链接；附件中心加索引状态标签（未解析／解析中+进度／已可检索／解析失败+原因）＋重新解析＋批量补解析；🔴 自查修掉**React #310 回归**（新增的 `useMemo` 落在 `if (!detail) return` 之后，首屏与加载后 hook 数不一致——`tsc`/`vite build` 发现不了的崩溃型缺陷）＋ `useLocation` 死引用（并纠正 `location.search` 依赖不随路由变化的问题）。验证：`npm run build` EXIT=0、`--noUnusedLocals` 清零。② **主系统后端**：**迁移 V13**（`attachment_ai_task`、`attachment` 加 `ai_index_status`/`ai_doc_id`/`ai_indexed_at`（全可空、存量零影响、NULL 归一到 NOT_PARSED）、`ai_ask_log` 审计留痕）+ `module/ai/**`（`AiServiceClient` 唯一适配 AI 原始契约且双超时、`AiScopeResolver` **权限与作用域在主系统收口**、`AiAnswerAdapter` 含 citations 回填 attachmentId、`AiChatService` 问答+留痕、`AiTaskService` 触发/重试/状态回写、`AiTaskRecoveryRunner` 启动清理残留任务、`AiHealthService` 永远给结论）；AI 不可用时 `/chat` **严格 503 不降级成"未找到"**，读接口用主系统自有记录作答；`citations` 缺失一律空数组、**绝不从 `trace.brief` 抠页码**。验证：`mvn -o test` 66 例全绿（全 mock），过程中修掉 4 个真实缺陷（含 **4xx 被误判成"AI 服务不可用"**的产品缺陷）。④ **上传后自动触发解析**（方案 §3.3 要求）：在 v3.2 两段式上传的**后台推送线程**里、附件落库成功**之后**发布事件，由**独立单线程执行器**（有界队列 200 + CallerRunsPolicy，刻意不复用上传池以免大文件解析挤掉后续上传）异步触发；**AI 失败绝不影响上传**（三层保证：监听者吞异常 + 事件单向 + 发布方再包一层 try，专门有测试钉住）；开关 `pm.ai.auto-parse`（`AI_AUTO_PARSE`，默认 true）**关闭时连事件都不发**；失败时置 `FAILED`+原因（而非 `NOT_PARSED`，否则界面显示"未解析"、没人会去查为什么没解析成）；顺带修掉一个真问题——后台线程没有 `AuthContext`，自动解析的留痕会记成"操作人为空"，故给 `OperationLogService` 加显式传操作人的重载（自动/手动/重试分别记 `AI_AUTO_PARSE`/`AI_PARSE`/`AI_RETRY`）。③ **工具集评估**：新增《AI工具集与检索编排评估》——结论是 3 个工具一个都不能删（平台给的是无状态能力，工具解决有状态编排与本地数据；平台没有"语料库"概念），但要重排分工（检索默认服务端预取、诊断字段不下发模型、未来 4 个业务查询 RPC 在模型侧合并成 1 个工具），并明确**不引入** LangChain4j/Spring AI。 | `7299404` |
| ai-1.1.0 | 2026-09-20 | **`/chat` 结构化引用 + 请求字段名对齐（AI 服务）** | 前端"引用可点击跳原文"的前置条件：`/chat` 原先只返回 `{answer,trace,scope,llm,stopped_reason,error}`，**页码/片段/分数全被丢掉**。新增请求级 `CitationRegistry`——命中在**模型看到时就带 `cite` 编号**（`doc_id+page_no` 去重、按首次命中顺序分配、只增不改、同页重命中只刷新分数），提示词要求句末按 `[cite]` 标注，响应**追加** `citations:[{index,doc_id,filename,page_no,snippet,score}]`（既有键顺序与语义一字未动，键顺序不作契约）。**顺带修掉"静默丢参"真 bug**：契约写 `doc_ids` 而 Java 字段实为 `docIds` → 按文档发 `doc_ids` 会被静默忽略、检索范围退化成"答全库"（不报错的最危险一类），已用 `@JsonProperty("doc_ids")+@JsonAlias("docIds")` 两种都收并写进契约文档；同时清掉提示词里推荐**已删除工具** `list_documents` 的旧文案。验证：`mvn -o test` **94 例全绿**（既有 81 例未改 + 新增 13 例）；**真实内网网关 e2e** 两种字段名都真的被解析（用不存在的 docId 反证），`citations` 页码与 snippet 与原文逐字一致；`verify-e2e.ps1` 修字段搬家缺陷 + **新增 citations 断言**，整脚本真跑 EXIT=0。**遗留**：AI 服务**无任何入站鉴权**（依赖网络隔离，独立待办，不在主系统侧假装修好）。 | `7299404` |
| docs-1.2 | 2026-09-20 | **开发机脚本 PowerShell 化 + 文档与脚本收口**| ① `docs-1.0` 遗留项落地：`scripts/{make-release,dev-reload,db-sql}.sh` → **Windows 原生 `.ps1`** 并删除 `.sh`（`pm-upgrade.sh` 与 `ai-backend/scripts/check.sh` 因只在 Linux/CI 跑而保留）；必须偏离 bash 的地方（无 `gzip.exe` → `.NET GZipStream`；`java -D…` 裸参数被按 `.` 拆参；PS 5.1 对原生命令 `2>$null` 抛终止性错误；`curl` 是别名）已写进脚本注释。② **编码定案**：`scripts/*.ps1` 一律 **UTF-8 带 BOM + CRLF**（实测 PS 5.1 对无 BOM 的 UTF-8 按 GBK 解析，中文字面量**在解析期就坏**；"无 BOM+中文+5.1 可跑"三者不可兼得），写入 `scripts/README.md` §0.2 并明确"别顺手改成无 BOM"。③ **验证**：三脚本 × (PS 5.1 + PS 7) 解析校验 6/6；`db-sql.ps1` **真实执行**（只读查询返回 11 行，走 `.env` 档，口令只打印长度、不打印不落盘）；`make-release.ps1`/`dev-reload.ps1` 因本机 Docker 引擎未启动**未实际执行**（只做逐段比对 + 隔离探针），其 gzip 产物的 `docker load` 闭环**登记为待验证项**。④ **文档收口**：`scripts/README.md`／根 `README.md`／`deploy/README.md`／`docs/部署与发布全流程手册.md`（§2.3 手工打包整段改为"等价说明、不可照抄"的 PowerShell 步骤，`\| gzip` 与 `ls -lh` 清零）／`jdbc/RunSql.java` 与 `demo-reset.sql` 注释／本文件 4 处失效的"当前用法"；新增《AI工具集与检索编排评估》并登记进 `docs/README.md`；`docs/AI前端与集成方案.md` 增 §9 冻结契约与 §10 前端工程规范，并**修正"nginx 加 `/ai-api/` 直连 AI 服务"的自相矛盾结论**（会让浏览器绕过主系统权限解析）。收尾：`check-docs` 19 份 / 0 问题（体检本轮真抓到 2 处自己引入的问题并已修）。 | `7299404` |
| docs-1.3 | 2026-09-20 | **专项论证：要不要引入 LangChain4j（文档处理 / 会话记录）** | 结论**不引入**，并把依据落到《AI工具集与检索编排评估》§7（原 §5 的"不做"改为指向它）：① **逐环节对照**——解析（通用 Tika 路径拿不到"文本层 vs 平台 OCR"路由与页级失败语义，等于用未验证行为替换已验证行为）、切片（换默认切分必须重测召回，而我们还没有评测集）、检索（`OpenSearchEmbeddingStore` 官方只写 exact kNN + metadata filter，**混合检索仍是社区 PR**，而我们已实测"混合召回 → Reranker 精排"分数序）、重排/OCR（框架无关）→ 净收益为负或接近零；② **会话记录三条硬伤**（官方文档自证）：**只有 memory 没有 history**（要完整历史得自己存）、**被淘汰的消息会从 `ChatMemoryStore` 一并删除**（与"审计留痕不得被窗口淘汰"直接冲突）、**会把状态放进 AI 服务**（破坏"主系统是唯一事实源、AI 服务无状态"这条已定边界）——为省几十行样板代码破坏边界不划算，P2 会话管理应在主系统建会话/消息表，与 `ai_ask_log` 同一条数据；③ **量化重估触发条件**（第二家模型/网关、工具数 >8 或需并行工具调用、需接 MCP、文档格式超出 PDF+扫描件、团队愿以可复现性换开发速度）；④ 给出**不返工的三条接缝**（`SearchPort`/`DocumentReader`/`Tools`）与**三条底线**（平台 OCR 路由与页级校验、引用编号与出处、主系统权限/口径/留痕）；⑤ 明确真正的短板是**评测集**而非框架：先做 10~20 条迷你评测集，再做限时 spike（同文档同问题对比代码行数/延迟/引用页码准确率/依赖体积/审计明细可得性）再用数字决定。 | `26072ad` |
| v3.6.1 | 2026-09-20 | **部署配置补全：把 AI 接入参数真正贯通（否则服务器上 AI 必然不可用）** | v3.6.0 交付了 AI 功能，但**部署资产没接**：两个 compose 都没给 backend 传 `AI_*`，容器里的 `AI_SERVICE_BASE_URL` 会是默认 `127.0.0.1:8100`（=后端自己），**AI 功能一定不可用**；而 `AI_AUTO_PARSE` 应用内默认 `true`，会让每次上传都记一条"解析失败"。本轮修：① `deploy/docker/docker-compose.yml` 与 `docker-compose.deploy.yml` 的 backend 增 6 个 `AI_*` 变量 + `extra_hosts: host.docker.internal:host-gateway`（Linux 上容器访问宿主机必须映射）；② `.env.example` 新增 AI 段（逐项说明、"**绝不能填 127.0.0.1**"、首部署建议 `AI_AUTO_PARSE=false`、AI 集中部署的建议）；③ 新增 [`ai-backend/docker-compose.deploy.yml`](ai-backend/docker-compose.deploy.yml)（服务器专用：无 `build:`、`pull_policy: never`、`AI_BIND_IP` 绑内网、`work` 卷持久化、healthcheck、日志限额）；④ 文档：`deploy/README.md` 新增 §1.1（三个变量 + 部署顺序 + 两条边界）并修过期数字（`V1~V9`→`V13`、**11 张→16 张**、补"迁移无回滚、升级前先备份"）、部署手册（§3 `.env` 清单加 AI 段、§8.1 **构建平台必须与目标机一致**（ARM 服务器上 amd64 镜像会 `exec format error`）、§8.3 新增"服务器无源码部署 AI 服务"三步 + 主系统接入 + 双机应集中部署、§5 验收加 A7/A8、质量门 81→**94 用例**）、`崖山数据库与迁移约定.md` 与根 `README.md` 的 `V1~V12`→`V1~V13`。**验证**：四个编排文件 `docker compose config --quiet` 全部 **exit 0**，且 `docker compose config` 确认 `AI_*` 与 `extra_hosts` 确实解进了 backend 服务。 | `5cff88f` |
| v3.6.2 | 2026-09-23 | **构建提速（222s→24s）+ AI 服务获得与主系统对等的发版链路** | ① **构建提速**（实测："改了代码 → 出发布包"从 ~7 分钟降到**几十秒**）：三个 Dockerfile 的编译阶段改 `FROM --platform=$BUILDPLATFORM`（`mvn`/`npm` 跑**构建机原生架构**，只让最终镜像层是目标架构）＋ BuildKit **cache mount** 复用依赖（`/root/.m2/repository`、`/root/.npm`；挂 `repository` 子目录而非 `/root/.m2`，否则会把镜像加速的 `settings.xml` 遮掉）。**实测**：后端 `mvn package` **222s → 47.3s（首次）/ 24.3s（改代码后稳态）**、前端 `npm run build` **218s → 103s**、AI 镜像首次构建 **44s**；三个镜像仍为 `arm64/linux`（换构建阶段**没有**漏进最终镜像）。② **AI 服务发版链路与主系统对齐**：`make-release.ps1` 新增 `-Project main\|ai`，AI 目标产出 `dist/pm-ai-release-<版本>/`（镜像包 + 服务器专用编排 + `.env.example`（预填 `AI_IMAGE_TAG`）+ `服务器部署步骤-ai.txt`）；`dev-reload.ps1` 新增 `-Project ai`；`ai-backend/README.md` 补"四条链路"对照表。③ **主系统"只重建改动那一端"**：`-Only backend\|frontend [-ReuseTag <旧版本>]`（未改的一端 `docker tag` 复用并**打印来源 tag**；复用镜像做架构校验，避免混架构包；来源校验在构建之前，写错立刻失败）。④ **闭环历史遗留**：主系统包与 AI 包的 `docker save`→`docker load` **双双实机验证通过**（此前挂在 Backlog 两次）。验证：三套流程真机跑通（整包 72.9s、`-Only` 打印"复用 pm-frontend:v3.6.2 → v3.6.3"、AI 包 122.3MB）；AI 镜像起容器 `/health` 返回 `code:0`；脚本干跑 16 例 × 两壳 + docker 桩 11 场景 × 两壳（BAD=0）。 | `c13a054` |
| v3.6.3 | 2026-09-23 | **服务器"一个根 + 两个隔离项目 + 一个统一入口"：`main/` + `ai/` + `pm.sh`** | 起因是 6 条现状问题：目录假设过时（主系统 `pm/app`+`pm/releases`，AI 又写在 `pm-ai/…`，两个项目散在两棵树）、`pm-upgrade.sh` 只认主系统（只切 `IMAGE_TAG`、只认 `pm-backend`）、没有统一启停、本地一键启动不含 AI、两套发布包内容不一致、路径散落多处。改动：① **服务器布局**：`/home/lhim/pm/{pm.sh, main/, ai/}`，每个项目目录含 compose + `.env` + `.env.example` + 部署步骤 + `releases/` 镜像包，**发布包按同一树形产出**（`scp -r dist/<包>/* …:/home/lhim/pm/` 一次到位）；② **新 `scripts/pm.sh`（716 行，取代并删除 `pm-upgrade.sh`）**：唯一入口 `start\|stop\|restart\|status\|logs\|upgrade <main\|ai> <镜像包>\|help`，**start 先起 ai 再起 main、stop 反序**，`upgrade` 自动 load + 切对应 `.env` 的 `IMAGE_TAG`/`AI_IMAGE_TAG`（备份 `.env.bak`）+ 重启 + 健康检查；main 的健康检查**要求 body 含 `"db":"up"`**（`/api/health` 恒 200，只看状态码会把"库不通"误报成可用）；两包装错会被拒绝（exit 2）；**工程名=目录名**（不传 `-p`，与手工 `docker compose` 同一工程）；③ **本地一键启动含 AI**：`start-dev.cmd` 第 4 步拉起 AI（失败只警告不拖垮主系统、缺 `.env` 给提示），`stop-dev.cmd` 加 8100；④ **模板/编排**：`.env.example` 补 `AI_IMAGE_TAG`/`AI_BIND_IP`/`AI_WORK_DIR`，`ai-backend/docker-compose.deploy.yml` 去掉写死的 `name: pm-ai`（否则"工程名=目录名"对 AI 不成立）；⑤ **文档**：手册新增 **§0.4 从旧布局迁移**（工程名变化会撞固定容器名、必须搬旧 `.env`、**别用 `cp -r` 覆盖新编排**）并全篇改口径，`deploy/README`、`ai-backend/README`、根 `README` 同步。**验证**：`pm.sh` 在容器 Ubuntu bash 里 `bash -n` exit 0（本机无可用 bash：WSL 默认发行版没有 `/bin/bash`）+ 参数分支逐条实测；**不重新构建**（`SKIP_BUILD=1`）重打两个包，两棵树逐项核对（`pm.sh` 与源逐字节一致、compose 与源逐字节一致、`AI_IMAGE_TAG` 已预填且无 BOM）、gzip 解压 CRC 通过；顺手修掉步骤文本里 `${WEB_PORT:-8080}` 被 PowerShell 当变量展开成空串的真 bug。 | `abbb0bb` |
| v3.6.4 | 2026-09-23 | **部署流程"一页纸"重排（消除"没有明确流程/解释太多/步骤分散"）+ 容器名冲突的现场提示** | 起因是运维在真机上按手册操作时反馈"文档太乱、没有一个明确的流程、太多解释性内容、流程分散"，同时实际踩到 `container name "/pm-backend" is already in use`（`docker stop` 不解开占用；工程名从 `app` 变 `main` 而容器名写死）。改动：① **手册 §0 整段重写为"照抄即可"的线性清单**（0.1 主系统 6 步 / 0.2 AI 服务 5 步 / 0.3 日常操作 / 0.4 旧布局迁移 4 条命令 / 0.5 最常见 3 个报错 → 一条命令 / 0.6 速查），**不放解释性内容**（要"为什么"再往后翻），原 §0.1~§0.3 的背景与目录约定压缩进 §0.6（实测数字与踩坑结论一条没丢）；② `deploy/README.md` §1 与手册 §0 改为**逐行同一套命令**（含"让主系统连上它"）；③ 补两条易错点：**滚动升级显式成步**（先升一台验收再升第二台）、**AI 自检口径**（`with_ocr=false` 只是探活，完整自检要 `with_ocr=true&with_vec=true` + 界面自检页四项全绿）；④ 修掉 §0 重排后 §2.2/§2.4/§8.4 共 4 处过期交叉引用（`§0.3/§0.1` → `§0.6`）；⑤ `pm.sh` 的 `upgrade` 失败分支原来只给一句"排查：bash pm.sh logs main"，现在**直接把补救命令打出来**（`docker rm -f pm-backend pm-frontend pm-ai-backend`，并说明"`stop` 不解开占用、必须 `rm`" + 本地卷名随工程名变化的提醒）。验证：`pm.sh` 容器内 `bash -n` 通过、补丁段逐行核对；`check-docs` 19 份 / 0 问题。 | `fa4c426` |
| v3.6.6 | 2026-09-23 | **修生产 500（运行期缺 `reactive-streams`）+ 深度自检 + OBS 缺对象日志** | 生产反馈：手动点附件解析报 **500「系统繁忙」**、自动解析从不产生任务。真机日志根因：`NoClassDefFoundError: org/reactivestreams/Publisher` —— 后端 AI 代理用 Spring 6.1 `RestClient` 走 multipart，而 `MultipartBodyBuilder` 的常量池本身就引用 `Publisher`，项目只依赖 `spring-boot-starter-web`（无 webflux/reactor）→ 该类在运行期链接失败（**问答 `/chat` 同一条路径也会 500**）。**教训**：66 个单测全绿却线上炸，真因不是"mock 掩盖"，而是 **`AiServiceClientTest` 从未覆盖 `submitParse`（multipart）这条路径**；故修法 = `backend/pom.xml` 补 `org.reactivestreams:reactive-streams`（版本由 BOM 管 → 1.0.4）**＋ 新增真 socket 回归测试**（JDK `HttpServer` + 真 `RestClient`，5 例；实测"修依赖前 3 条红、修后全绿"）。另修：① 服务自检三个模型从「未知」改为**三态（可用/不可用/未探测）+「深度自检」按钮**（`GET /api/ai/health?deep=true` → `with_llm&with_vec`，深探走长超时）；② OBS 缺对象时 WARN 打出 **bucket + 完整 object key + file_path + 核对提示**（用户可见 404 不变）。验证：`mvn -o test` **75 例全绿**、前端 build 0 错、镜像内运行期自检 `Publisher` 加载成功（旧镜像 `ClassNotFoundException`）、两个镜像均 arm64。 | `5c0bcc4` |
| v3.7.0 | 2026-09-23 | **P2 受控查询：结构化事实走主系统受控查询 RPC + 口径同源 + 评测集** | 需求来自用户实测：问"某项目某阶段有几个附件"，**模型只数出已入库的 1 个**——AI 侧只有 `search_documents / read_page / calculate`，**看不到任何业务数据**。定案走 **B 方案（受控查询 + 口径 + 评测集）**：AI 不直连库、不生成 SQL，结构化事实一律反向回调主系统的受控查询接口，并把"口径"与"验收量尺"一并做出来。**为什么不做 A 方案（把"项目事实块"注入提示词）**：注入的是**一次性快照**，用户一改数据答案就错，且模型无法回答"具体某一阶段/某一笔付款"这类需要收窄的追问；快照还会随会话膨胀、把事实源搬到 AI 侧，与"主系统是唯一事实源"的既定边界相反。① **主系统**：4 个 RPC（`projects`/`contracts`/`payments`/`stats`）+ `scope_token`（同 `JWT_SECRET`、`typ=ai_scope`、`jti`、上限 **300s**，越权**在 mapper 之前**拒绝且对库查询 **0 次**）；口径抽 `ProjectMetricsService` 复用（与详情页**逐位一致由构造保证**，不是靠约定）；`AiQueryEntity` 字段白名单**即 400 提示原文**；`operate_log` 逐次留痕 + `ai_ask_log` 记查询次数/entity（**迁移 V14**）；附件预览加 `Cache-Control: private` + `ETag` + 304（并修掉 `file_size` 为空时发字面量 `"null"` 的 Content-Length 缺陷）；`stats` 支持 `projectIds` 收窄。② **AI 侧**：条件注册的**第 4 个工具** `query_business_data`（**不传 `biz_query` 时行为与本版本前逐字节一致**）+ 四条路由铁律（A 系统事实必须查系统／B 文档内容仍走文档并给 `[cite]`／C 引用系统数字必须带口径与数据时间、混用分开陈述／D 不许用 `calculate` 反推系统已有数字）+ 错误映射（403/4xx/空结果/5xx 四类话术，明确"**空结果 ≠ 调用失败**"），工具超时 **20s**。③ **前端**：系统数据区块（值 + 口径 + 数据时间，与文档引用**视觉区分**）、问答超时 **180s**、全屏预览**不再重建 iframe**（不再重复下载）。④ **评测集**：`eval/smoke-30.json` 35 题 + `scripts/eval-ai.mjs`（真值取自**页面接口**、`--scope-token` 交叉校验、`--dry` 数据集体检、漂移快照）。**验证**：backend **147 例**、ai-backend **129 例**、前端 build **0 错**、`check-docs` **0 问题**、三镜像 **arm64**、两个包 **`docker load` 成功**、`--list-truth` **20/20**。**未验证**（照实登记）：真机 nginx 反代下回调连通性、崖山 `FETCH FIRST n ROWS ONLY` 实际执行、`operate_log/ai_ask_log` 真实落库、V14 在真库的 ALTER、OBS `getObjectMetadata` 的 ETag 取值、180s 长问答后段仍能查（目前只有单测推理）、`/api/ai/chat` 全链路未打真实系统（评测用 mock 跑通判定链）、§11 交叉校验因拿不到 `scope_token` 恒 `n/a`。 | `0b49917` |
| docs-1.5 | 2026-09-23 | **AI 模型调用配置评估 + 并发上限推导 + 「统一管理 + 任务环境隔离」队列方案（只评估，不改源码）** | 需求来自用户三问：① 调用配置是否合理 ② 能否提高并发 ③ 要不要加统一管理 + 环境隔离的任务队列。新增 [`docs/AI模型调用与任务队列评估.md`](docs/AI模型调用与任务队列评估.md)，逐调用点给出**真实配置值 + 代码位置**（超时/重试/并发/批量/上限），并做量化推导。**结论**：① 配置"能用但不合理"——四类模型共用一个网关与**一个 300s 超时**、`LlmClient` 的 `timeoutSeconds` 参数**未生效**（`ping(15s)` 实际按 300s 发）、除 OCR 外**无任何并发上限**、rerank 一次打 **75 对**不分批、**全链路重试 0 次**；② 并发**能提，且不用加机器**：问答上限今天 **≈0.16 QPS（≈10/分钟）且由重排单点决定**（75 对 × 2 次检索 ÷ 实测 24 对/s），**recall 50→20 即到 ≈0.4 QPS（约 2.5×）**；向量化今天只跑出平台能力的 **~12%**（串行批 32 ≈18.5 条/s），改并发 8 为 **147.9 条/s（≈8×）**，3,000 切片补算 **162s→20s**；`OCR_CONCURRENCY=12` 对典型文档**根本不生效**（`workers = min(12, 批数)`，8 页文档只有 1 批在飞）；③ **队列值得做，但要做薄**：隔离与背压是真缺口，但**不要在 AI 侧再建持久化队列**（会与 `attachment_ai_task` 形成两个事实源——今天的 `work/tasks.json` 就是教训），改为"**主系统 DB 唯一事实源 + 抢占式领取 + 公平调度**，AI 侧只留**每模型信号量 + 交互/批量两条通道**"。**量化触发点**：本地"进程内向量"在 **≈4,000 切片**时给每次提问加 **+1s**、15,000 切片 **+3.8s**（实测单切片向量 JSON **38,333 字节**，本轮真机读数），4 并发问答常驻 980MB，且 `local` 是**进程内状态 → AI 必须单实例**（需要 2 实例时 local 立刻不成立）⇒ 建议 OpenSearch 从 P2 **上提为 P1**。**P0 五项**：超时对齐（chat 150s/embed 30s/rerank 20s，且 < 调用方 180s）、重排降配、向量化并发化或前移、队列满**诚实失败**（今天队列满 → 500 + 任务**永久 QUEUED** + `work/tmp` 残留）、每模型信号量。**未确认项 11 条照实登记**（含"支持降维"前提与本仓库实测 HTTP 400 的冲突、OCR 并发曲线自相矛盾、rerank 34ms 语义）。**无源码变更** | `81ff293` |
| docs-1.6 | 2026-09-23 | **为两台新服务器出 OpenSearch 部署与接入文档 + 部署资产（含 2 节点 quorum 明账与升级路径）；架构 x86_64 + 按官方 Docker 文档对齐（含 REST TLS 取舍）；最终路线：tar.gz + systemd（无 Docker）；部署根 /home/opensearch/（D 方案＝专用账号家目录，程序/配置/数据/日志/备份分离）；并新增 OpenSearch Dashboards（同机 5601 + systemd unit + "快照必须含系统索引"的修正）；版本 3.1.0（按 3.x 官方重核：k-NN 引擎/查询语法变更）+ 安装包目录统一 /home/opensearch/ + 仓库资产落位对照表（属主/权限/install 命令）+ 两条省事路径 + 落位/权限 7 项补齐 + 收口 4 条 + **修订六：主线去分支（删校验与搬运叙述）、按真机现状写成唯一路径，章节重排（§0~§11 + 附录 A/B/C）** + **修订七：防火墙提前到宿主准备（先收暴露面再起服务）、去强调词、附录 B 增 B.2** + **修订八：修 `chmod 664` 范围错误（导致 `status=203/EXEC`）+ 新增 §4.5 可执行位恢复与 §8.5 排障条目** + **修订九：由部署记录改写为傻瓜式部署手册（前置条件 + 20 步四件套 + 总清单）** + **修订十：补 Dashboards 侧同源漏洞（`status=126` + 恢复块 A + 两棵树判据）** + **修订十一（docs-1.7）：把 步骤 19 从占位句写成可复制的真实步骤（5 步固定顺序 + 不静默降级 503 + 建索引两种方式的取舍 + 参考 C 同步落定）** + **修订十二：把 步骤 16 / 附录 E 的手工建索引参数对齐到代码（`ef_construction` 128→**100**、`refresh_interval` `-1`→**`1s`**，并写明"以本节参数为准；索引一旦存在不会被服务改写，建错了要 `DELETE` 重建"），收掉"照手册手工建出的索引与代码不一致且永远不会被自动纠正"这处分叉；步骤 19 ①-1 的取舍说明随之简化** | 新增 [`docs/OpenSearch部署与接入.md`](docs/OpenSearch部署与接入.md)（照《部署与发布全流程手册》§0 的"照抄即可"线性清单写）+ 三个配套资产（Docker 路线：`docker-compose.yml` / `.env.example` / `os.sh`，**已在【修订二】中删除**）。**关键决策**：① **定稿单节点**（`10.254.212.110`，8 核/16GB/200GB；`discovery.type: single-node`、`-Xms8g -Xmx8g`、`bootstrap.memory_lock: true`、索引 **1 主分片 + 0 副本**、健康检查期望 **green**）；② **2 节点 quorum 明账**——选举需多数派，`N=2` 时 quorum = **2 票** ⇒ 挂一台即**失去 master、集群不可写**（副本=1 只保证不丢数据、不保证可用），故 **2 节点相比单节点几乎换不到可用性却多一倍运维复杂度，明确不推荐**；真 HA 必须第 3 台（dedicated cluster-manager，届时只改 `discovery.seed_hosts` 与 `cluster.initial_master_nodes` 两个参数，见附录 A）；③ 第二台 `10.254.212.111` 定为**冷备 + 演练机**（同一套编排默认停着 + 每晚快照 `rsync` + 七步恢复演练、RTO 分钟级）；④ 安全取**内网 HTTP + basic auth 强口令**（`DISABLE_INSTALL_DEMO_CONFIG: true`，TLS 为可选并列明两种选择的代价）；⑤ 容量口径——4096 维 float32 ≈16KB/条，**2 万切片 ≈350~400MB、10 万 ≈1.8GB、100 万 ≈18GB**（估算），对比现有 `local` 实测 **38,333 字节/切片（9.36 字节/维）**，故**不需要量化/on_disk**；⑥ mapping 把 `project_id`/`phase_code`/`attachment_id`/`security_level` 等 `keyword` 设计进去（"先过滤再检索"），并**如实写明这些字段当前无人写入、切片也没有元数据**（属应用改动，不在本轮）；⑦ 接入侧落地 `VEC_BACKEND=opensearch` + `OPENSEARCH_URL/INDEX/USER/PASSWORD`、**全量重建索引**（含 147.9 条/s 口径的耗时估算与"以 `chunk_key` 为断点续跑"）、灰度与**秒级回滚到 `local`**、以及**降级口径定死为"明确报错、不许静默换检索方式"**。**本轮只写文档与部署资产**：`scripts/**`、`deploy/**`（仅新增 opensearch 目录）、`ai-backend/**`、`frontend/**`、`backend/**` 应用代码一行未改 | `58ef5cb`、`814f2e7`、`f0b410a`、`018c3bf`、`c875660`、`fa8975b`、`69c074c`、`81ec178`、`2c3fbca`、`b4de707`、`403662b`、`85a8ff8` |
| ai-1.2.0 | 2026-09-23 | **AI 能力服务接入 OpenSearch 向量后端 + P0 模型调用参数与效率优化** | 需求来源两条：① **接 OpenSearch 提高检索规模/HA**（《AI模型调用与任务队列评估》量化触发点：进程内 `local` 在 ≈4,000 切片即给每次提问 +1s、15,000 切片 +3.8s，且 `local` 是**进程内状态 ⇒ AI 必须单实例**，需要第 2 个实例时立刻不成立）；② 同文档的 **P0 五项**（超时对齐 / 重排降配 / 向量化并发化或前移 / 队列满诚实失败 / 每模型信号量）。**改动四块**：① **OpenSearch 接入**（**原生 REST，不加 OpenSearch Java Client**；索引自建 3.x 写法 4096/cosinesimil/`engine: lucene`/HNSW m16·ef100/1 分片 0 副本 + 6 个 keyword 元数据字段 + `created_at`；`_bulk` 幂等 `_id=chunk_key`；kNN 带**作用域硬 filter**；`/admin/reindex` 全量重建含进度/断点续跑/取消；集群不可达**不回退**（503 + 明说）；`/health?with_vec=true` 的 `vec.ok` 取集群真实状态）；② **P0 七条**（分级超时 chat150/embed30/rerank20/ocr600 + 调用方预算 180 + 启动校验超时链；修 `LlmClient` 忽略入参；每模型信号量 4/12/8/2；幂等重试退避；**向量化前移**（`DocIndexer`，批 32/并发 8）；OCR 并发语义修正（共享池 + 动态批页）；队列满诚实失败（429 + FAILED + 清临时文件，无永久 QUEUED））；③ **指标**（micrometer/actuator：`ai.model.*` / `ai.upload.*` / `ai.vector.*` / `ai.retrieval.*` + `/health?with_metrics=true` 聚合）；④ **召回档位与比对工具**（`RETRIEVAL_RECALL_MODE=baseline\|low\|custom`，**默认仍 50**、`low`=25 带 WARN；`TopKCompareTool` top-k 一致性比对，**不进 `mvn test`**）。**刻意偏差与已对齐项（如实登记）**：唯一刻意偏差是 `k` 用**生效召回数**（50/25）而**不是** `TOP_K`（否则废掉精排）；`ef_construction=100` 与 `refresh_interval=1s` 原先与手册 步骤 16 示例（128 / `-1`）不同，**已随 `docs-1.7 修订十二` 把手册两处对齐到代码**（两者都是官方默认值），不再构成偏差。**未验证清单（如实登记）**：未对真集群（`10.254.212.110:9200`）跑过建索引/`_bulk`/kNN；top-k 比对工具未真跑；**P1-3（`/chat` 请求级 `doc_ids` 一路接到 Tools/QaService）未做**（当前只把"模型给的 `doc_id`"下推成硬 filter）；召回降配未改默认、未跑评测；hybrid 未启用；OCR 超出 180s 是有意例外；队列满只做了服务层验证。`mvn -o test` **180 例全绿**；发布包 `pm-ai-release-v1.2.0`，镜像 **aarch64**（**若应用服务器 `uname -m` 是 x86_64 需按 amd64 重出一次**） | `d72f608`（应用代码）、`bf2ccb1`（文档） |
| docs-1.8 | 2026-09-23 | **全链路 RAG 完整性评估：五环节现状 / 主流对照 / 缺口清单 / 技术栈评判（只评估，不改源码）** | 需求来自用户原话"整个流程，解析，清洗，切分，召回，生成。流程是否完善，企业主流的流程，当前项目是否有缺失，技术栈是否合理"。新增 [`docs/RAG流程完整性评估.md`](docs/RAG流程完整性评估.md)：① **五环节评级**（解析 B / 清洗 **D＝无独立清洗阶段** / 切分 C / 召回 B- / 生成 B）并逐条给**可证伪判据**；② 现状盘点全部落到 `文件:行`（含 `DocumentReader` 的"整份平均字符二分、混合型 PDF 不逐页回退"、`DocStore` 的 500 字不跨页无重叠、`RetrievalService` 的双路融合与降级、`Prompts` 的六条铁律、`QaService` 的 6 轮历史与 8 轮工具循环）；③ **逐环节主流企业做法对照表**（判定：有/部分/无/不适用 + 差距 + 影响 + 优先级）；④ **22 条缺口清单**（现象→影响→建议→工作量级（标注估算与假设）→P0/P1/P2）；⑤ **技术栈逐项评判**（合理/勉强/该换）与 **9 个量化触发点**（切片数、QPS、索引容量、HA、文档数、质量门禁等）；⑥ 明确不做/推迟 13 项与未确认项 14 条。**本轮首次登记的代码级发现**：`RetrievalService.java:440-442` 在**每次检索**里把当前作用域全部切片 `_bulk` 重写一遍（估算 **≈575 MB/问**，假设 15,000 切片 × 38,333 字节/切片）、请求级 `doc_ids` 未下推检索层、混合型 PDF 无逐页 OCR 回退、`/chat` **无上下文预算**（`LLM_MAX_INPUT_CHARS` 只作用于 `/analyze`）且**答案侧无机器校验**（数字可溯源与 `[cite]` 合法性都只靠提示词）、`OPENSEARCH_HYBRID` 打开会混 BM25 与 cosine 量纲、密级不是 `SearchScope` 维度、`SCANNED_CHAR_THRESHOLD` 疑似死配置。**与既有四份评估的边界已在文首写明（不重复结论）**；`node scripts/check-docs.mjs` 0 问题（23 份 Markdown、总表 53 行 == 明细 53 段）；**未改任何应用代码** | `793846c`（文档本体）、`6d29946`（哈希回填）、`71ee908`（口径收紧） |
| docs-1.9 | 2026-09-23 | **RAG 流水线设计与并发管理（只设计，不改源码；参考 data-classification 的交互）** | 需求来自用户四条原话（HTTP 客户端选型 / 任务流+落库+可溯源 / 统一并发管理模块 / 流程环节要设计好）+ 补充的参考项目 `C:\Users\yjc\Desktop\data`（data-classification）。新增 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md)（§1~§8）。**§2 技术选型**：平台四模型调用 **保持 JDK HttpClient**（瓶颈是模型信号量 4/12/8/2 与重排 24 对/s，不是 HTTP 出口；换客户端要重写 `ModelRegistry.retryable()` 的异常判定）、**OpenSearch 保持原生 REST 但必须改掉"每次调用现建 HttpClient"**（`OpenSearchIndex.java:782-786` 连接池完全不生效）、`opensearch-java` **本阶段不引并留 `OS_CLIENT` 开关**（POM 实测 14 个新增 artifact：httpclient5 5.5 / httpcore5 5.3.4 / httpcore5-h2 / commons-logging / jsr305 / parsson / json.bind-api / annotation-api 1.3.5 / yasson 等，估算 8~9 MB 离线预置；版本钉 3.1.0 与服务端同轨）。**§3 流水线**：七阶段契约（PARSE/CLEAN/CHUNK/EMBED/INDEX/RETRIEVE/GENERATE，逐阶段输入/输出/产物/幂等键/重试/可续跑/超时预算）、清洗阶段落地（8 条规则 C1~C8 + **`clean_rev` 三层防护**并入切片键与向量缓存校验，闭合 T7/G1）、切分改进（表格整块、重叠 10~15%、结构感知、父子块 small-to-big）、解析改进（混合型 PDF 逐页 OCR 回退、页级缓存、docx/xlsx 路径）、召回与生成（作用域硬下推含密级、history-aware 改写、RRF 归一、四类缓存、`/chat` 上下文预算与答案侧 `verification` 机器校验）、**两张 mermaid 时序图**（入库 7 个落库点 + 问答与溯源链 9 个落库点）、超时预算总表。**§4 任务流/落库/可溯源**：**V15 DDL 草案全文**（`attachment_ai_task` 扩 **13 列**：content_hash/pipeline_rev/clean_rev/task_type/priority/stage_key/stage/stage_done/stage_total/elapsed_ms/attempts/claimed_by/claim_expires_at + 新建 **3 表** `ai_pipeline_stage`/`ai_pipeline_artifact`/`ai_pipeline_event`，风格照 V13/V14：`VARCHAR(n CHAR)` + 表外 `COMMENT ON`）、任务级 7 状态 + 11 条合法迁移（新增 PARTIAL/CANCELLED/DEAD）、阶段级 7 状态、"谁写状态"四方案取舍（**AI 回调为主 + 主系统投影兜底，AI 不直连库**）、溯源链 6 跳（`[n]` → citations → **新增 `chunk_key`** → 切片全文 → 原始页 → 附件第 N 页）+ 新增 7 个主系统接口（API-1~API-7）、解析质量六项指标 Q1~Q6、保留与清理（DB 只存摘要 ≤4000 字符、全文留 work/、重建按 rev 跳过）、前端任务流视图（阶段条 + 阶段卡片 + 产物抽屉 + 质量徽标）。**§5 统一并发管理**：**L1 业务并发（新，AI 服务内 `PipelineGate`）→ L2 模型并发（已有 `ModelRegistry`）两级单向**、三条防死锁硬规则 H1~H3、五类通道与优先级/老化/公平、全局限流与诚实失败、10 项指标、热更新取舍、落地位置与双机 LB 抢占式领取、8 条验收指标 V1~V8。**§4.9/§5.10 参考项目对照**：逐项列 data-classification（`scheduler/JobScheduler.java` 等 13 个后端文件 + 4 个前端文件，**只读未改**）做了什么 → 可借鉴什么 → 必须改造什么，并给 **8 条"照搬会出错"否决清单**（第二套线程池/信号量与 `ModelRegistry` 重复、`CallerRunsPolicy` 破坏"先返回后解析"、JPA 运行期建表破坏迁移唯一入口、它的并发数字与"串行"用到交互式等）。**§6 分期**：P0-α 3~5 人日 / P0-β 8~12 / P1-α 10~16 / P1-β 8~12 / P2 15~25（均标估算与假设）。**§7 不做/推迟 19 项**、**§8 未确认 18 条**（含参考项目"未读到"的部分如实登记）。**本轮首次登记**：`OpenSearchIndex` 每次现建 HttpClient、V15 任务流数据模型与状态机、两级并发分层、以及参考项目的可借鉴/否决清单。未改任何应用代码（`ai-backend/**`、`backend/**`、`frontend/**`、`scripts/**`、`deploy/**` 一行未动） | `8155cb6`（文档本体）、`4b2490d`（哈希回填）、`007857c`（口径收紧：§1.2 的 V15 扩列数 12→13 列） |
| code-1.0 | 2026-10-13 | **RAG 流水线 P0-α 落地：删查询侧全量 `_bulk` + 请求级作用域硬下推 + OpenSearch 出口改单例共享** | 依据 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) §6.1/§9 实施三项"不做会出错"的改动（**只动 `ai-backend/**`**，主系统与前端未改）。**① 删掉查询侧全量 `_bulk`（G2）**：改造前 `RetrievalService.recallByOpenSearch` 在**每次 `search()`** 里把当前作用域**全部**切片 upsert 一遍（作用域为空即全库；估算 15,000 切片 × 38,333 字节/切片 ≈ 575 MB/问）——现在先查 `existingChunkKeys`、**只补"算出向量但索引里还没有"的切片**，补写走 `indexChunks`（带 doc 级元数据、`_id` 幂等）且为 **best-effort**（失败只 WARN 不阻断本次检索；正常应为 0，计数仍由 `ai.vector.lazy_backfill` 反映）。**② 请求级作用域硬下推（G3+G17）**：作用域是**上界**，模型给的 `doc_id` 与它**取交集**，越界返回结构化 `scope_violation`（**不退化成"在请求作用域内检索"**）；`SearchScope` 新增第四维 **`securityLevels`（密级）** 与 `intersect`/`provablyEmptyAgainst`，`OpenSearchIndex.clausesOf` 增加 `security_level` terms 子句（缺字段不算命中）；`Tools.searchDocuments`/`Tools.execute`/`ToolAgent.run`/`QaService.ask` 逐层新增带作用域的重载（**老签名保留**，转发到新签名），`/chat` 新增可选 `scope{doc_ids,attachment_ids,project_ids,security_levels}`（snake_case 主名 + camelCase 别名，与 `doc_ids` 同处理，避免静默忽略）。**③ OpenSearch 出口改单例共享 `HttpClient`**：改造前 `execute()` **每次请求 `new HttpClient()`**，连接池与 keep-alive **完全不生效**（每次 kNN/`_bulk` 重付 TCP 握手与 selector 线程创建）——现在构造期建一个进程内唯一实例（显式 `HTTP_1_1`、`followRedirects(NEVER)`、connectTimeout=`min(10s, 单请求超时)`），仍**不发起任何请求**（保留"未启用时不碰网络"语义），并新增 `ai.opensearch.requests` 指标。**测试**：`mvn -o -f ai-backend/pom.xml test` → **189 例全绿**（改造前 181，新增 8 例：`ToolsScopePushdownTest` 6 例 + `ChatControllerRequestTest` 2 例；`RetrievalServiceTest` 补 `existingChunkKeys`/`indexChunks` 替身并增断言"索引已齐时检索路径一次都不写"与"只补缺失的那一片"）。**未做**：真集群压测与 top-k 一致性验证（步骤 19 ①-3）；V15 迁移与任务流/统一并发仍未落地（见设计文档 §9.2/§9.3 的分批计划） | `3de0621`（应用代码）+ `767d328`（文档同步） |
| code-1.1 | 2026-10-13 | **迁移 V15 落成真实 SQL 文件（解析流水线任务流/产物/事件）** | 把设计文档 §4.3 的 DDL 草案落成 `backend/src/main/resources/db/migration-yashan/V15__ai_pipeline_flow.sql`：**① `attachment_ai_task` 扩 13 列**（`content_hash` 幂等指纹 / `pipeline_rev` 流水线版本 / `clean_rev` 清洗版本 / `task_type`+`priority` 任务分类与优先级 / `stage_key`+`stage`+`stage_done`+`stage_total`+`elapsed_ms` 任务流展示 / `attempts` 重试次数 / `claimed_by`+`claim_expires_at` 双机抢占租约），并新增 3 个索引（领取热路径、task_type、content_hash）；**② 新建 3 张流水线表**：`ai_pipeline_stage`（一行=一任务一阶段，`uk(run_id,stage_key)` 唯一 ⇒ 重跑更新同一行、attempts+1；status 含 PENDING/RUNNING/DONE/PARTIAL/SKIPPED/FAILED/CANCELLED）、`ai_pipeline_artifact`（只 INSERT、`artifact_rev` 递增 ⇒ 支持两次解析对比；五类 artifact_type：page_digest/clean_diff/chunk_manifest/embed_batch/index_result；`content_json ≤4000` 只存摘要 + `object_key` 指全文）、`ai_pipeline_event`（状态迁移审计：from/to/reason/operator）。风格与 V13/V14 逐条一致（`VARCHAR(n CHAR)`、identity 主键、表外 `COMMENT ON`、表外 `CREATE INDEX`、不做"列已存在则跳过"的方言判断）。**只加列与加表**⇒ 存量行零影响；**回滚口径 = 停止写入 + 前端隐藏视图**（不需要 DROP）。**表数 16 → 19**，迁移推进到 **V1~V15**。**验证**：`mvn -o -f backend/pom.xml -DskipTests package` 通过，`jar tf` 确认 `BOOT-INF/classes/db/migration-yashan/V15__ai_pipeline_flow.sql` 已随包发布。**未验证**：未在真实崖山库执行（下次启动/部署由 `YashanMigrationRunner` 应用并登记 `schema_version`）；库外只做了语法与风格对照。**文档同步**：设计文档 §9.1 加 I5、`docs/崖山数据库与迁移约定.md`（版本表 + 表数 19 + 下一个版本号 V16）、设计方案 §0/§8（19 张表）、`deploy/README.md`、根 `README.md`、`docs/OpenSearch部署与接入.md` 的迁移口径 | `b251ee7` |
| code-1.3 | 2026-10-13 | **前端「解析流水线」任务流视图 + 可溯源交互 + 轻量质量看板** | 依据 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) **§4.9（含 §4.9.3 组件骨架）** 与 §4.5~§4.8，参照 `C:\Users\yjc\Desktop\data`（data-classification，**只读未改**：`JobCenterModal.tsx`/`JobProgressModal.tsx`/`useJobTracker.ts`/`App.tsx`）的交互落地，**只改 `frontend/**`**。**① 任务流视图**：`/ai` 页**新增「解析流水线」签 + 进行中角标**（5s 只取计数、失败熔断），**进行中/历史两段** + "只看失败" + 失败数角标 + 失败行红底；行内「详情」打开 **`PipelineFlowDrawer`（宽 920）**——五段 `Steps` 阶段条（每段状态色 + 耗时 + 计量；`PARTIAL` 橙色感叹号**与 `DONE` 绿色对勾明确区分**、`SKIPPED` 显示跳过原因、`DEAD` 深红）+ 顶部 6 张统计卡 + 阶段卡片（`Descriptions`：计数/耗时/尝试次数"已重试 N 次"/输入输出摘要/`counters` 键值表/错误红字）+ **产物抽屉**（五类产物：摘要 `content` 结构无关递归渲染、`artifact_rev`/`item_count`/`char_count`/`sha1`、`object_key` 可复制并明说"主系统不代理下载"）。**② 轮询**：列表 3s（仅当有进行中）、详情 **1.5s**，**打开抽屉即重取全量**（列表只给表头）、终态/失败即停、**页面不可见暂停**、**连续 3 次失败自动停**（`useVisiblePolling`）。**③ 可溯源**：问答引用旁新增「溯源」入口（**不动**原有点击语义=打开原文），`CitationTraceDrawer` 按 §4.5.1 展示四跳：引用项 → **`chunk_key`**（`chunkKey`/`chunk_key` 兼容读）→ 该附件的产物摘要（`page_digest`/`clean_diff`/`chunk_manifest`；命中切片在 `chunk_manifest` 里有逐片记录时另行展示）→ 原文第 N 页（复用既有 `AttachmentPreviewModal` 跳页 + `attachmentUrl(id,'inline')#page=N` 新窗口），**每一跳缺数据都写"该环节未上报：<缺什么>"**。**④ 质量看板**：页级空页率 / 清洗剔除字符比 / 切片数与平均长度 / 向量化成功率 / 失败阶段分布，**字段来源逐项写进卡片 tooltip**，后端没给就"暂无数据"（不做阈值判红黄、不做跨项目聚合——API-6 未实现）。**⑤ 失败与取消**：失败原因/尝试次数展示齐全；冻结契约**无流水线级重试/取消端点 ⇒ 只读展示**（`api/ai.ts` 末尾标注待接端点，不摆点不动的按钮）。**契约**：`types/ai.ts` 新增流水线类型 + **snake_case 主名/camelCase 兼容**归一化，`api/ai.ts` 新增 `pipelineRuns`/`pipelineRunDetail`。**不新增 npm 依赖**。**验证**：`cd frontend && npm run build` **0 错**（tsc + vite build）；`tsc --noEmit --noUnusedLocals --noUnusedParameters` **无输出**。**未验证**：`/api/ai/pipeline/*` 后端由主系统侧并行实现，**尚未在浏览器里连真实接口跑过**（当前只能看到"读取失败/暂无数据"的降级外观，运行时正确性待端到端验收）。**文档同步**：设计文档 §9.1 加 I6、§9.2/§9.3 更新（含 §4.9.3 落地差异登记）、`docs/AI前端与集成方案.md` §3.1 页签清单 + §9 接口表 #11/#12 与枚举、根 `README.md` 版本行；`node scripts/check-docs.mjs` **0 问题** | `c7a4fec`（应用代码）+ `093b3c0`（文档同步） |
| code-1.4 | 2026-10-13 | **RAG 流水线主系统侧落地：V15 三表回调写库 + 状态机强校验 + 抢占式领取 + 服务间鉴权 + 只读投影兜底** | 依据 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) **§4 与 §5.3/§5.4**，按任务书"冻结契约"逐字实现，**只改 `backend/**`**（编号取未占用的 **code-1.4**：`code-1.2` 由 AI 侧并行分支占用（阶段上报 + L1 闸门），同号会让总表出现两义）。**① 10 个端点**（`/api/ai/pipeline/**`）：`claim`（**空队列 404**）、`heartbeat`（**租约失效 409**）、`release`、`stages/{stage_key}`（**幂等 upsert：同键 1 行 + attempts+1**）、`artifacts`（**只 INSERT + artifact_rev 自增**）、`events`（只追加审计）、`finish`（终态以主系统为准：非法迁移 400 + 允许目标、并发冲突 409、同态幂等）、`GET /runs/{run_id}`、`GET /runs`；第 10 条"投影兜底"是详情里的**只读**标记（`display_status=STALE` + `projection_note`，**不改库**）。**② 抢占式领取**：`UPDATE … WHERE id=? AND status='QUEUED' AND (claim_expires_at IS NULL OR claim_expires_at < now)`，**受影响行数=1 才算领到**，每次领取前回收 T7 过期行（记 `STALE_CLAIM`）；**刻意不用 `SELECT … FOR UPDATE`**（崖山 Oracle 模式会把行锁绑到分钟级长事务）。**③ 状态机强校验**：T1~T11 写成 11 条规则表（边表派生，T9 两个来源 ⇒ 12 条边），非法迁移报错带"允许的目标状态"；既有对账/恢复路径**不加**这层（要兼容 V15 前的存量形状）。**④ 鉴权双向不通用**：新过滤器只认 `X-AI-Service-Token`（常量时间比较、未配置即 **fail closed**），用户 JWT 调该前缀 401、AI 令牌调用户接口进不了业务链、运行级 `pipeline_token` 当不了用户 JWT。**⑤ 与既有链路分工**：**文件仍走推送**（`/upload-tasks` 是唯一交付字节的通道），**归属权统一走 claim 同一把锁**——提交成功后主系统立刻用同一条条件 UPDATE 登记 `RUNNING`+租约，AI 侧便领不到它（推送/拉取互斥的落点）。**⑥ 提交即写 V15 列**：`content_hash`（**边推流边算 sha1**）、`pipeline_rev`/`clean_rev`/`task_type`/`priority`；`run_id=run-<taskId>` 与 `task_key=sha1(content_hash+pipeline_rev)` **不落库**（无需 V16）。**⑦ 前端只读入口**：`GET /api/ai/runs`、`/api/ai/runs/{run_id}/pipeline`（**与冻结契约同形状**）+ 任务/附件/产物/阶段字典若干；既有 `GET /api/ai/tasks` 的 VO **只增**阶段字段。**验证**：`mvn -o -f backend/pom.xml test` **245 例全绿**（改造前 147，新增 98）；10 个端点的真实请求/响应样例（含 401/400/404/409）由 `AiPipelineEndpointTest` 落 `backend/target/pipeline-endpoint-samples.txt`。**本轮抓到并修掉两个真缺陷**：`content_hash` 算了两遍 sha1（新增 `digestHex` 唯一入口）、MP 3.5.7 的 `getParamNameValuePairs()` 构造期为空导致单测桩失效（改连续返回值 + 拆 `claim`/`claimNext`）。**未验证**：V15 未在真实崖山库执行、10 端点未在真库真 HTTP 上端到端跑过、AI 侧上报链未联调、公平调度/背压/DEAD 判定/投影写入侧/API-3~API-7 **均未做**；**前端仍调 `/ai/pipeline/runs*`（会 401），需改 2 行到用户侧同形状入口**；本轮未加演示数据（`scripts/**` 不在允许改动范围内）。**文档同步**：设计文档 §9.1 加 I7~I12、§9.2/§9.3 勾销与新增、§9.4 缺陷登记、§4.5.2 两条路径说明；`docs/AI前端与集成方案.md` §9 #11/#12 更正 + 新增 **§12 回调契约**；设计方案 §0 + §13 第 44 条；根 `README.md` 版本行；`check-docs` **0 问题** | `1573d7b`（应用代码）+ `c65b487`（文档同步） |
| code-1.2 | 2026-10-13 | **AI 侧落地：五阶段边界上报（§4）+ L1 业务并发闸门 `PipelineGate`（§5）** | 依据 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) §4.4.3 与 §5.2~§5.8，**只改 `ai-backend/**`**（可改文档同步行）。**① 五阶段边界上报**（`PARSE → CLEAN(SKIPPED) → CHUNK → EMBED → INDEX` → `/finish`）：新增 `module/pipeline/**`（`PipelineReporter` 门面、`PipelineReportClient` 七条契约出口、`RunContext`、`StageKey`/`StageStatus`/`StageReport`/`ArtifactType`）；`run_id`/`pipeline_token` 全程贯穿、`run_id` 写日志 MDC；**上报失败不影响主流程**（best-effort + WARN + `ai.pipeline.report_failed`），**只有终态重试**（`finish-retries=2`）；`CLEAN` 如实上报 `SKIPPED` 并写明"本批尚无清洗阶段（G1 属 P1-α）"；五类产物只传**摘要**（`page_digest`/`clean_diff`/`chunk_manifest`/`embed_batch`/`index_result`）；`RETRIEVE`/`GENERATE` 是在线阶段、不落阶段行。字段名 snake_case 与主系统契约**逐字一致**（`code-1.4` 的 10 端点）。**② `pipeline_rev`/`clean_rev` 进幂等键与向量缓存校验**（设计 §3.3.3/T7）：新增 `PipelineRev`（`chunkKey`/`parseChunkKey`/`pipelineRev`/`taskKey`）；切片键可切到 `d=<docId>:p=<pageNo>:h=<sha1>:c=<cleanRev>:k=<chunkRev>`（**解析器兼容旧三段**，**默认仍是 legacy** ⇒ 升级不使既有索引/缓存失效）；向量缓存头部增 `clean_rev`/`chunk_rev`，不匹配即**整体失效重算**（含"接入前写的、没有这两个字段的老缓存"）。**③ L1 `PipelineGate`**：五通道准入上限、`有效优先级 = 任务优先级 + 通道权重 + 老化`、按 `project_id` 轮转 + 每项目在飞上限、队列上限 + 等待超时 + **诚实失败**（深度/上限/原因/建议，绝不"接受了但永远不跑"）、软闸门（交互式有人等时**只暂停派发**新的 `BATCH.PARSE`）、10 项 `ai.gate.*` 指标、`/health?with_metrics=true` 的 `queues.gate`/`queues.pipeline`、启动期 **L1↔L2 交叉校验**；**三条防死锁硬规则可检**：H1 由 `ModelRegistry` 的 H1 守卫**立刻抛错**（`ai.model.h1.violations` 正常恒 0）、H2 由"L1 排队只在任务开始前"保证、H3 L2 恒为公平信号量；`/chat` 仍是 **503 快速失败**（1.5s）、取消后 permit **立即**归还。**④ 两后端一致**：L1 在检索之外，`local` 与 `opensearch` 走同一个闸门。**验证**：`mvn -o -f ai-backend/pom.xml test` → **Tests run: 239, Failures: 0, Errors: 0**（改造前 **189**，新增 **50**：`PipelineGateTest` 16（含"批量持 L2 等 L1"的**死锁反例**：屏障 + 25s 超时断言 + 压测后 L1/L2 在飞全回 0）+ `PipelineReporterTest` 12 + `PipelineRevTest` 6 + `VectorCacheRevisionTest` 4 + `UploadTaskStageReportTest` 4（端到端：真 HTTP 桩 + 真闸门 + 真线程池）+ `ChatGateTest` 5 + `AiSettingsBindingTest` 3（**yml 键必须真绑到 `AiSettings`**））；用既有 `StubHttpServer` 做上报桩，**未连真主系统/真集群**。**未做（如实）**：① AI 侧 `/claim` 轮询领取**未开启**（七条契约客户端已实现并测试，含心跳 409；开启会与既有推送入口形成两条路，按 §5.7 分工不开）；② `BATCH.REINDEX`/`BATCH.EMBED`/`BATCH.EVAL` 三条通道**已实现并测试但没有业务调用点**（重建仍走 `ReindexService` 单线程 + 启动期 409；批量向量化在 PARSE 内部完成）；③ §5.6 的 `/admin/gate/config`、`/admin/gate/pause-batch` 两个运行时端点**未开**（只有内存开关）；④ 未与真主系统联调、V1~V8 真机压测未做；⑤ 真集群 top-k 一致性验证仍未做（沿用前批） | `d01b60e`（应用代码，34 files / +4021 −213）、`8c4a0e6`（文档同步，4 files / +155 −6） |

> 各迭代的完整交付说明见下方「各迭代明细」。

## 各迭代明细

### v0.1 — 需求与设计（评审稿 + 静态 Demo）
- 产出 `docs/政府信息化项目管理系统-设计方案.md`（初始为评审稿 v0.1，后升级为设计与实现同步稿）：
  - 硬件 9 阶段 / 软件 11 阶段默认流程模板（可配置，权重 + 里程碑付款节点）
  - 项目全字段、阶段记录、附件、付款记录设计
  - 9 张逻辑表（sys_user / sys_dept / dict_item / phase_template / project / project_phase / attachment / payment / operate_log）
  - 统计口径定义与 15 条待确认问题（Q1–Q15）
- 产出 `demo/`：双击可开的静态交互原型（登录 → 工作台 → 列表/卡片 → 详情五页签 → 统计），数据内置 Mock，不作定稿。

### v0.2 — 前后端骨架 + 登录认证
- 后端：`/api/health`、Flyway 建表（V1 全量表结构）、统一响应 `R{code,message,data}`、全局异常、MyBatis-Plus 分页、CORS/附件目录。
- 认证：JWT（HS256）+ BCrypt；角色注解 + 拦截器；`/api/auth/login`、`/api/auth/me`；预置账号 admin/jingban01/lingdao01（密码 123456）。
- 基础数据种子（Flyway V2）：部门、甲方单位/资金来源/招标方式/文档类别/付款节点/项目来源字典、HW/SW 阶段模板。
- 前端：登录页、路由守卫、Axios 拦截（自动带 token、统一错误提示）、AntD 主布局与菜单。

### v0.3 — 项目核心闭环
- 项目：新建时按所选类型从模板生成阶段实例；自动编号（YJ/RJ-年-序）；列表筛选（关键字/类型/状态/甲方/年度/负责人）分页；列表计算当前阶段与整体进度、累计实付。
- 阶段：开始/完成/跳过、进度百分比、计划与实际时间、经办记录、阶段关键结果（JSON 字段快照），操作全程写日志。
- 附件：本地磁盘存储（`backend/uploads/`）+ 元数据入库；上传/下载/预览/逻辑删除；阶段卡片直达上传。
- 付款：里程碑记录 CRUD（预付/到货/初验/终验/质保），资金汇总卡（预算/合同/已付/待付）。
- 前端详情页：概览条 + 流程进展（时间线瀑布，每阶段展示记录与附件）/ 项目信息 / 资金情况 / 附件中心 / 操作日志。

### v0.4 — 演示数据与环境
- 本机开发环境：MySQL 8.0.29（`E:\work\env\mysql`，库 `project_manager`，启动脚本 `start-mysql.cmd`）、Maven 3.9.9 + 华为云镜像、git 全局 OpenSSL 后端。
- `scripts/seed-demo.mjs`：走真实 API 灌入 10 个演示项目（5 硬件 + 5 软件，含阶段推进、20 条付款、附件样例）。
- 缺陷修复：项目自动编号改为物理计数（含逻辑删除行）以避免与已删除记录撞唯一索引（原 `RJ-2026-001` 500 错误）。

### v0.5 — UI 优化与缺陷修复（本次）
- 详情页崩溃修复：ProjectDetailPage 的 2 个 Hook 原先位于条件 `return` 之后导致 Hooks 顺序变化，已上移至顶部。
- 附件中心改版：
  - 保留"流程进展"中每个阶段的附件展示与"该阶段直达上传"（不再要求先选阶段）；
  - 附件 Tab 按归属自动分组（按项目阶段顺序 → 项目级 → 付款凭证），各组标题旁提供"上传到此阶段"；
  - 新增按"文档类别 / 归属"过滤，便于按批复、合同、验收报告等类别查看。
- 新增本迭代记录（`ITERATION.md`）。

### v0.6 — 瀑布流 UI 还原 + 全阶段附件 Mock
- 详情页"流程进展"还原 demo 瀑布流样式：时间轴状态圆点/脉冲、阶段卡片、计划/实际/负责人/完成比例、附件 chips、逾期标红提示。
- `scripts/seed-attachments.mjs`：为所有演示项目**每个阶段**补齐典型文档附件（约 255 个 txt/pdf/csv，PDF 可在线预览）；附件扩展名白名单增加 csv。

### v0.7 — 工作台 + 项目统计（M5）
- 后端：`/api/dashboard`（汇总卡/我的待办/60 天内验收/逾期预警/最近更新）、`/api/stats/*`（概览/状态·类型构成/流程阶段分布/年度预算 vs 合同 vs 实付/部门资金排名，支持筛选）。
- 前端：工作台页与统计页（ECharts，筛选联动）；登录默认落地页改为工作台。

### v0.8 — 系统管理（M6）
- 后端管理接口（仅 ADMIN）：用户 CRUD/重置密码/启停、部门、字典、阶段模板维护、操作日志分页查询。
- 前端系统管理页五个页签；非管理员访问拦截提示。

### v0.9 — 界面与交互评审调整
- ① 移除部门维度（表单/详情/统计/系统管理的部门内容与入口）；② 项目管理搜索改为输入框+"搜索"按钮；③ 详情：瀑布流隐藏权重；阶段"记录/推进"弹窗支持手动设置/清除付款节点；付款凭证上传固定类别免再选择、付款行直接预览凭证；④ 附件中心改为折叠分组（Collapse）并新增"在线预览"；⑤ 操作日志改为评论列表样式并支持分页。

### v1.0 — 清理部门 + 标签/付款/预览升级
- 后端与数据库彻底移除部门（Flyway V3 删 sys_dept、删相关列，实体/接口/初始化器全清理）。
- 付款记录新增/编辑/删除收敛为管理员；流程进展显示"付款节点"横幅（节点/计划/已付/状态）并支持直接登记/编辑该笔付款。
- 附件中心"付款凭证"按款项二级分组；新增 `frontend/src/config/tagDict.ts` 项目级标签字典颜色组并复用；项目筛选改为草稿模式（仅"搜 索"生效）。

### v1.1 — 文档实时性机制
- 设计文档升级为"设计与实现同步稿"：新增 §0 版本与迭代同步记录、§13 实现同步说明与差异清单、§14 文档维护约定（每迭代必更新文档）；正文同步部门移除、角色与数据范围、8 表模型、Q1–Q15 处置状态；README/ITERATION 约定同步。

### v1.2 — 一键启动 + 附件全屏
- 根目录 `start-dev.cmd`/`stop-dev.cmd`（一键拉起 MySQL→后端→前端→开浏览器，带状态探测/重试）。
- 附件预览弹窗支持全屏（Esc/按钮退出，图片/pdf/文本均可用，保留下载）。

### v1.3 — 父子项目 + 合同模型（大改）
- Flyway V4：`project.parent_id`（顶层=null）、新增 `contract` 表、`project.contract_id`/`payment.contract_id`，历史数据自动回填；合同可覆盖任意多个子项目。
- 后端：列表默认顶层 + `parentId` 子查询、合同 CRUD/覆盖分配、付款按合同链解析、项目删除级联（子项目递归+付款+孤儿合同清理）；统计/工作台口径=核算单元（叶子）、金额按合同去重。
- 前端：列表可折叠树、新建支持所属总项目、总项目详情新增子项目、资金页合同面板、子项目显示所属总项目导航。
- 种子：seed-demo v2（父子+三种合同形态）、seed-attachments 覆盖各核算单元阶段。

### v1.4 — 独立合同口径 + 容器化 UI 优化
- 口径收敛：合同一律**独立签订**（同一承包商也分别登记），后端仅允许单项目关联；顶层=纯汇总容器（创建子项目时清空其自身阶段，详情隐藏流程/资金页签、展示"子项目"汇总，子项目可共览父级公用附件）。
- 二级列表 UI 重构：顶层行"总项目·N"标签、展开子项为分组卡片样式；ContractPanel 改单项目合同登记；seed v3 每子项目一份合同；演示库重建（11 项目/9 合同/9 付款/226 附件/容器 0 阶段）。

### v1.5 — 评审迭代整理（本机）
- 前端：子项目与容器「子项目」页签统一**卡片网格**；资金情况页重构为**合同（父）-付款（子）折叠结构** + 顶部汇总实时计算；新增**合同附件区**（合同扫描件）与附件中心互通；**流程付款节点横幅**与付款明细同源联动，金额缺失仅显示标签；修复付款弹窗 `name="nodeName"` 冲突；列表/附件中心/统计筛选优化（列表与附件中心改为**多选**，全局移除甲方单位筛选）；精简大量说明性文案。
- 后端：ProjectService 金额口径改为**查询时实时汇总**（叶子=所挂合同金额+变更/实际付款；容器=子项目求和），修复"登记合同/付款后列表与详情不同步"；分页查询 type/status/year 支持**多值**（逗号分隔 → IN）。
- 部署与文档：新增 `deploy/`（windows/linux 分类启停脚本 + Docker 一体化 docker-compose、Dockerfile、nginx 反代、.dockerignore）、`deploy/README.md`、`docs/GITHUB-SSH-setup.md`；根目录启停脚本改为通用入口；README 补充部署/SSH/Docker 指引；本文档与设计稿按维护约定同步。
- 环境：本机开发环境迁移至 `E:\env`（JDK17 / Maven 3.9.9 / MySQL 8.0.29），前后端本地运行验证通过（MySQL:3306 + 后端:8080 + 前端:5173 --host）。

### v1.6 — 附件：文件类型字典/白名单 + 在线预览扩展
- **文件类型字典与上传限制**：Flyway `V5__file_type_dict.sql` 新增 `FILE_TYPE` 字典（24 项，与白名单一致）；后端上传白名单扩展（新增 md/ofd/log/json/xml/html/bmp 等），非白名单 400 并列出允许清单；`tagDict` 新增 `FILE_TYPE_TAGS` 全局颜色组（预览弹窗头部展示彩色类型标签）。
- **在线预览扩展（主流开源集成）**：docx → `docx-preview`、xls/xlsx → SheetJS 表格、md → `react-markdown`；pdf/图片/文本保持内嵌；以上均带加载等待态（Spin/onLoad），动态 import 分包避免主包膨胀。
- **暂不支持类型策略**：doc(97-2003)/ppt/pptx/ofd/压缩包等统一提示"当前文件类型暂不支持在线预览，请下载后查看"。
- **v1.7 试扩展**：OFD 试渲染（`@sharp9/ofdjs` 首页 Canvas，jszip 注入全局）；`.doc` 内容嗅探（zip 头 → docx-preview 渲染，真二进制 doc 提示下载）；均 try/catch 回落，PPT/PPTX 维持提示下载。

### v1.7 — 附件预览试扩展（OFD / .doc）
- 试集成 `@sharp9/ofdjs`：OFD 无浏览器原生支持，只能把首页渲染为 Canvas 充当前台预览；同时对 `.doc` 做**内容嗅探**（实为 docx 时才交给 docx-preview 渲染）。
- 定位为**体验探索**：解析失败或不稳定时一律回落"请下载查看"，不影响其他格式的预览路径，本期不承诺 OFD 在线预览。
- 结论：OFD 服务端提取生态远弱于 PDF；后续若要正式支持，建议单独评估（转图片 + OCR 作为保底）。

### v1.8 — 项目概览（README 式介绍 + 二级功能模块清单）
- 新表 `project_overview`（Flyway V6，一项目一行）：`intro_md`（Markdown）、`modules_json`（两级清单 JSON）。
- 后端：详情接口返回 `overview`；新增 `PUT /api/projects/{id}/overview`（ADMIN/MANAGER，操作留日志）。
- 前端：项目信息页新增「项目介绍」（react-markdown 渲染，空态）与「功能模块清单（一级→子模块）」展示；「编辑项目介绍/功能模块」弹窗（Markdown 文本框 + 一级/子模块动态表单）。
- 演示数据：`scripts/demo-project-overviews.sql`（6 个演示项目已灌入，贴近真实背景/模块）。

### v1.9 — 流程模板线性化完善（看效果版）
- 前端系统管理「流程模板」：按 HW/SW 切换，线性维护阶段；新增**上移/下移**调整顺序、**复制阶段**（副本排到末尾）、权重合计提示（建议 100）、表格按顺序号排序。
- 后端：`PhaseTemplateController` 增加同类型下**阶段重名校验**、创建时项目类型必填校验；更新时未传字段沿用原值。

### v2.0 — 流程模板设计器（Tab 多模板 + 画布/列表双视图）
- 数据模型：Flyway V7 新增 `phase_tpl`（模板：类型/名称/内置/默认/启用/排序/备注），`phase_template` 增加 `tpl_id` 并回填（HW/SW 各生成一套内置默认模板）。
- 后端接口 `/api/phase-tpls`：模板列表、新建（可 `copyTplId` 复制阶段为起点）、更新（含"设为默认"互斥）、删除（内置禁删）、获取模板阶段、**整模板阶段批量保存**（数组顺序=阶段顺序，自动增删改）。
- 项目创建改为取**该类型启用的默认模板**生成阶段（无默认则取首个启用模板）。
- 前端：系统管理「流程模板」改为 **FlowTemplateDesigner** —— Tabs 展示多套模板（内置/默认/停用标记）、"+"新增模板；内容区「画布 / 列表」双视图切换：
  - 画布视图：节点横向排布、HTML5 拖拽调整顺序、节点上悬停删除、**点击节点打开配置**（名称/权重/付款节点/常用附件/说明/可跳过）；
  - 列表视图：表格化维护（上移/下移/复制/编辑/删除）；
  - 顶部：新增阶段、保存（批量保存整模板）、重命名、设为默认。
- 说明：当前画布为**线性流程**（满足"拖节点编排顺序+节点配置"）；**并行/分支**留待 X6/LogicFlow 图形化 B/C 期（见 `docs/流程模板图形化评估.md`）。
- **存储与加载评估**：本地 `backend/uploads`（UPLOAD_DIR 可改）；Docker 独立卷 `pm_uploads`；不入镜像/源码；内网规模"数据目录 vs 文件服务器"差异不大，已用等待态覆盖大文件加载，未来可切对象存储/CDN 仅改地址前缀。详见 `docs/附件与预览方案.md`。

### v2.1 — 阶段说明 / 关键材料 / 画布布局（数据层）
- 迁移 **V8**：`phase_template` 增 `guide`（阶段说明）与 `key_materials`（关键材料）；`project_phase` 同名字段做**创建项目时的快照**（与模板解耦，模板后续改动不影响已在跑的项目），项目详情阶段 VO 一并透出。
- `phase_tpl` 增 `flow_json`（draw.io 式节点坐标 + 连线），为画布编辑预留；模板保存/复制同步这些字段；新增模板 `GET/{id}` 与 `PUT flow` 接口。

### v2.2 — X6 画布编辑器 + 展示/折叠双视图
- 流程模板画布改用 **AntV X6**：节点自由拖放、连线、点击节点配置（配置项新增 guide / key_materials），保存时按**连线拓扑与坐标**推定阶段顺序。
- 新增「展示」视图（竖向排列 + 折叠查看说明/材料）；「列表」改为折叠卡片同显描述；项目详情「流程进展」的阶段卡片新增「阶段说明·关键材料」折叠区。

### v2.3 — 去除画布，回归折叠阶段列表
- **移除 X6 画布**及其代码与依赖：画布的复杂度（坐标/连线维护、小屏不可用、与阶段顺序双份真相）大于收益。
- 流程模板只保留「Tab 多模板 + **折叠阶段列表**」：面板默认收起，展开显示 说明 / 做什么 / 关键材料 / 常用附件 / 操作；同时清理 SystemPage 里的旧模板遗留代码。
- 新增 `scripts/demo-phase-guides.sql`：为内置 HW/SW 共 **20 个阶段**补齐「目的 / 主要工作 / 要点 / 完成标准 / 注意 / 关键材料」演示数据。

### v2.4 — 阶段表单弹窗修复 + 画布遗留清理
- 修复阶段表单弹窗布局：`useFormModal` 默认宽度加大、表单标签改短，并把提示说明移到输入框**下方**（原先被输入框遮挡）。
- 阶段列表去掉"有说明"标签（信息冗余）；清理画布遗留代码/接口与 `phase_tpl.flow_json` 字段引用（含 `GET/{id}`、`saveFlow`）。

### v3.0 — 数据库国产化：MySQL → 崖山 YashanDB（Oracle 模式）
- **背景**：按信创要求迁移至崖山（主备已部署 10.254.212.106/.107:1688）。方案见 `docs/崖山oracle模式迁移实施方案.md`、可行性分析见 `docs/数据库迁移可行性分析-崖山oracle模式.md`。
- **代码改造**：pom 移除 `mysql-connector-j`/`flyway-core`/`flyway-mysql`，system scope 引入 `backend/lib/yashandb-jdbc-1.9.3.jar`（+ Spring Boot 插件 `includeSystemScope`）；`application.yml` 数据源换崖山主备（驱动级 primary + TAF，YASHAN_* 环境变量注入）；`MybatisPlusConfig` `DbType.MYSQL → ORACLE`；7 处手写 SQL 改造（`YEAR()`×4 → `EXTRACT(YEAR FROM ...)`、`limit 1`×2 → `FETCH FIRST 1 ROW ONLY`、`CONCAT` 保留）。
- **迁移脚本**：新建 `backend/src/main/resources/db/migration-yashan/` V1~V8（yashan 方言转换：`VARCHAR(n CHAR)`、`CLOB`、`TIMESTAMP`、`NUMBER`、`GENERATED BY DEFAULT AS IDENTITY`、`COMMENT ON`、普通 KEY 拆表外 CREATE INDEX 并全局唯一命名、多行 VALUES/`UPDATE...JOIN` 改写）；旧 `db/migration/`（MySQL 方言）删除（Git 历史保留）。
- **Flyway 替代**：`YashanMigrationRunner`（约 130 行，`@Order(1)`）——版本表 `schema_version` + 扫描 `db/migration-yashan/*.sql` 按 `V{n}` 排序 + 只执行未登记版本 + 拆句逐条执行 + fail-fast。
- **PoC 实库验证（崖山 23.4.13）**：主备 URL（primary+TAF）连通与 `DATABASE_ROLE` 识别 ✓；`identity` + `getGeneratedKeys` 回填 ✓（IDENTITY 方案成立，实体 `IdType.AUTO` 保持）；`VARCHAR(32 CHAR)` 存 32 汉字无截断 ✓；显式插 id 不同步 identity 计数（坑确认），`ALTER TABLE ... MODIFY (... START WITH n)` **校准生效** ✓；CLOB 2 万字往返 ✓；`EXTRACT`/`CONCAT`/`FETCH FIRST` ✓；空串按 NULL 存储（回归关注）。
- **deploy/README/docs 清理**：docker-compose 移除 mysql 服务与 pm_db 卷、backend 环境变量改 YASHAN_*；Windows/Linux 启停脚本由 MySQL(3306) 检查改崖山(1688)；deploy/README、根 README、ITERATION、设计方案 §8/§14、附件与预览方案同步更新。
- **实施落地（2026-09-09）**：业务账号 `pm`（CONNECT/RESOURCE）主库建立并执行 V1~V8 建表/种子（`schema_version` 登记）；本机 MySQL 11 张表全量迁移并逐表核验一致（identity 每表校准 `START WITH max+1`，金额聚合双端一致）；后端 :8080 / 前端 :5173 接口回归通过；一次性迁移工具已清除（源文件备份本机临时目录）；配置/文档默认应用账号统一为 `pm`；本机 MySQL 已优雅下线。
- **剩余待办**：① 主从切换演练（kill 主观察 TAF 重连，低峰执行）；② 空串/长文本前端回归（崖山空串按 NULL 存储）；③ 业务侧全流程手测一轮。

### v3.1 — 附件存储对象化（华为 OBS）
- **背景**：双机独立部署 + 负载均衡，附件若留本地盘需共享存储；用户环境提供华为 OBS（通用 S3 协议、忽略证书校验）。经代码核实：前端附件 IO 全部收口于 `/api/attachments/{id}/download`（不使用 `/uploads` 静态直链）→ 存储切换为**后端内部替换**，前端 0 改动、元数据 0 迁移（`file_path` 即对象相对 key）。
- **代码**：新增 `com.pmgt.common.storage`：`AttachmentStorage` 接口 + `LocalAttachmentStorage`（默认，行为不变）+ `ObsAttachmentStorage`（esdk-obs-java 3.24.3：path-style、`validateCertificate=false` 忽略证书、连接/读超时）；`AttachmentController` 存储无关化（上传 `storage.save`、下载改 `StreamingResponseBody` 流式、对象缺失 404）；对象 key 带桶内前缀 `uploads/`（可配 `APP_STORAGE_OBS_PREFIX`，默认 uploads，与本地相对结构/已上传存量一致）；`WebConfig` 的 `/uploads` 静态映射仅 local 模式注册；清理 controller 冗余 import。
- **配置**：`app.storage.type=local|obs` + `app.storage.obs.*`（`APP_STORAGE_*` 环境变量注入）；docker-compose/.env.example 透传（默认 local，部署切 obs 只需填 endpoint/bucket/ak/sk/prefix）。
- **存量附件**：本地 `backend/uploads`（495 个）已由人工上传至桶 `pdmsbucket/uploads/2026/...`；另备一次性工具 `UploadExistingToObs`（test scope，不进生产 jar）供需要时重跑。
- **回归**：编译通过；local 模式全回归（上传→下载字节一致→inline 预览→逻辑删除 404）；存量 pdf 在 8080 下载正常。**obs 模式实库验证**在服务器部署首启时执行（下载存量附件字节比对）。

### v3.1.1 — 部署资产整理与库名口径修正
- **服务器不存源码（模型定稿）**：核实两个 Dockerfile 均为多阶段构建——后端 jar（含建库迁移 SQL）、前端 `dist`、`frontend-nginx.conf` 全部 `COPY` 进镜像，compose 除附件卷外无任何指向源码的 bind mount → 服务器运行目录只需 `docker-compose.yml` + `.env`。新增 `deploy/docker/docker-compose.deploy.yml`（**无 `build:` 段** + `pull_policy: never`：镜像缺失时快速失败，不会误联网拉取或构建），由 `make-release.sh` 以 `docker-compose.yml` 之名打进发布包。
  - 实测：裸运行目录（仅 2 个文件）启动成功（`db:up`、前端 HTTP 200）；用发布包实际文件复测同样通过；`IMAGE_TAG` 指向不存在镜像时报 `No such image` 快速失败。
- **清理僵尸资产（净删 1779 行）**：删除 `demo/`（4 文件早期静态交互原型，真实前端 `frontend/` 已完全覆盖，全仓库零引用）、`deploy/linux/`（Linux 源码直跑脚本；本项目唯一开发机为 Windows、服务器不存源码，无任何执行场景）；`.dockerignore` 去掉失效的 `deploy/linux/.pids`。
- **修正崖山库名口径**：`project_manager` → **`PM`**（Oracle 模式下即 schema 名，大写）。覆盖 `application.yml` 默认值、`docker-compose.yml`/`docker-compose.deploy.yml` 默认值、`.env.example`、`README`、`deploy/README`、两本部署手册、迁移实施方案、ITERATION 速查——原默认值与实库不符，漏配 `YASHAN_DB` 即连错库。
- **发版脚本**：`make-release.sh` 产出 5 件（镜像包 + 服务器编排 + `.env` 模板含预填 `IMAGE_TAG` + `pm-upgrade.sh` + 服务器步骤说明），产出前清空旧目录防上一版残留；`pm-upgrade.sh` 默认运行目录 `~/pm/app`。
- **文档**：`deploy/README` 重构为「生产 Docker（服务器）/ 本地 Docker 试跑 / Windows 源码模式」三段；《部署与发布全流程手册》与《双机ARM服务器独立部署方案》同步为"服务器不存源码"口径。
- **产物**：arm64 镜像包（后端 + 前端）由开发机 buildx 产出，两台 aarch64 服务器通用。

### v3.2.0 — 附件后台上传 + 大文件超时根治
- **超时根因（三层默认值叠加）**：axios 全局 `timeout` 30s（**最先触发**）+ nginx `proxy_read_timeout` 60s + OBS 客户端 `socketTimeout` 60s → 大文件同步写对象存储必然超时。
- **改为两段式（迁移 V9）**：`POST /attachments/upload` 先把文件暂存服务端（`app.upload-tmp-dir`）并登记任务后**立即返回**，后台线程池（2~4）再推存储并回写进度；新表 `attachment_upload_task` 即"上传记录"，新增 `GET /attachments/upload-tasks[/{id}]`；`AttachmentStorage.save` 增 `ProgressCallback`（OBS 用带 `ProgressListener` 的重载，本地按流计数）；服务启动把残留 `PENDING/UPLOADING` 任务标为失败（防前端无限轮询）。
- **超时放宽**：上传 axios 10 分钟、nginx `client_body_timeout` / `proxy_*_timeout` 300s、OBS `socketTimeout` 300s。
- **前端**：上传弹窗显示"传输进度 → 后台进度"两段并内置上传记录列表，可关闭不中断；预览 >10MB 先提示"较大、可能较慢"并给「下载 / 仍要预览」二选一，各加载态显示文件大小。
- ⚠️ 上传响应结构由 `AttachmentItem` 变为 `UploadTaskItem`，**前后端必须同版本发布**。
- **本机 local 模式"附件读不到"**：Docker 用的是独立命名卷 `docker_pm_uploads`，而历史 498 个附件在宿主机 `backend/uploads` → 用 `docker cp` 迁入卷。教训：先比对**容器内实际目录**与**数据实际所在**，别只盯配置文件。

### v3.3.0 — 上传体验修复：上限提高 + 入口独立化 + 升级脚本切版本
- **上限是"三处联动"**：`application.yml` `max-file-size` 100MB→**500MB**（请求 600MB）；nginx `client_max_body_size` 200m→**600m**（**必须 ≥ 后端 max-request-size**，否则 nginx 先 413）；前端预校验同步 500MB，避免大文件白传一趟。
- `GlobalExceptionHandler` 补超限友好提示（原先落到通用 500「系统繁忙」），并在兜底分支识别异常链里的超限特征（Tomcat 会把 `FileSizeLimitExceeded` 包成 `IllegalStateException`，只写专用分支会漏）。
- **入口独立化**：上传不再要求保持弹窗打开——新增全局 `UploadTaskProvider`（`store/uploadTask.tsx`）持有任务状态与轮询；`UploadTaskCenter`（按钮 + 弹窗，置于附件中心「上传附件」同级，带进行中角标）统一展示进度与历史，含**所属阶段标签**（后端 `upload-tasks` 批量补齐 `phaseName`）与**文件类型标签**、状态/进度/失败原因；`AttachmentUploadModal` 精简为"选文件 + 类别"即关窗。
- **升级脚本**：`pm-upgrade.sh` 原先只 `load` + `up -d` 而不改 `.env` 的 `IMAGE_TAG` → 启动的还是旧镜像（**等于没升级，且不易察觉**）；改为从镜像包名解析版本、备份 `.env` → `.env.bak` 后写入。

### v3.3.1 — 修复表单弹窗编辑回显错位 + 开发机一键重建脚本
- **回显 bug（真实缺陷）**：`useFormModal.open()` 在 `setState` **之后同步**调 `form.resetFields()`——此刻 React 还没重渲染、Form 的 `initialValues` 仍是上一次的 → 重置回旧值；而 rc-field-form 在 `initialValues` 变化时**只更新内部记录、不自动填充已有字段** → 编辑时永远显示第一次点开的数据。
  修复：把「`resetFields()` + `setFieldsValue(initial)`」移入 **`useEffect`（依赖 state）**，等 Form 以新 `initialValues` 渲染完再执行。**该 hook 有 4 处复用**（SystemPage 用户管理/基础字典、FlowTemplateDesigner、ProjectDetailPage 合同弹窗）一并修好。
- **新增 `scripts/dev-reload.sh`**：开发机一键重建镜像 + 重启 + 轮询健康检查，支持 `all | backend | frontend`（只改一端时省一半时间）；与 `make-release.sh`（产发布包）/ `pm-upgrade.sh`（服务器升级）职责分离。

### v3.4.0 — 合同管理 tab + 项目分工 tab
- **合同管理**（一个（子）项目可签多份合同：施工主合同 / 第三方测评 / 方案评估 / 监理服务 / 项目设计 / 预算编制）
  - 迁移 **V10**：`contract` 扩政府合同常见字段——合同类型、甲方、签订/生效/工期起止日期、合同状态、收款户名/开户行/账号、验收标准、质保期/质保金、结算金额（**乙方沿用 `vendor_name`**，不再另设 `party_b` 以免字段冗余）；新增字典 `CONTRACT_TYPE`(7 项)、`CONTRACT_STATUS`(5 项)。
  - 前端用**折叠列表**：收起只露"一眼能判断"的信息（类型标签 / 状态标签 / 编号 / 合同金额 / 已付占比 / 乙方）；展开依次为 ①合同双方（甲乙方字号加大）②**付款账户**（黄底高亮、账号等宽字体，付款前必须核对）③金额·质保·验收标准 ④该合同的付款节点。
- **项目分工**（模块 → 子模块）
  - 迁移 V10 新建 `project_division`：`parent_id` 支持层级、负责方（甲/乙/双方）、甲乙负责人、计划开发/调试/上线日期、进度 0–100、状态、备注、排序号；新增字典 `DIVISION_STATUS`(4 项)。
  - 前端用**树形表格**（负责方标签、甲乙负责人两行、三阶段计划、进度条、状态标签），操作列支持「编辑 / **加子模块** / 删除（级联）」；顶部统计 总数·已完成·进行中·风险。
  - 后端 `ProjectDivision` 实体 + Mapper + Controller：列表返回**扁平结构**由前端组树（一次性拿全，展开无请求）；删除级联子模块并记日志；校验上级同项目且**防循环引用**。
- 配色统一在 `frontend/src/config/tagDict.ts` 的 `*_TAGS`（与字典 code 一一对应，不落库）：`CONTRACT_TYPE_TAGS`（**施工合同给红色**，主合同视觉最突出）/ `CONTRACT_STATUS_TAGS` / `DIVISION_STATUS_TAGS` / `OWNER_SIDE_TAGS`。
- 实测：V10 迁移执行成功（共 10 个脚本）；两个新字典就位；「数据采集模块 + 数据校验子模块」层级正确返回；合同新字段映射正常。

### v3.5.0 — 资金/合同职责重划分 + 多合同结构修复（V11/V12）
- **职责重划分**（原先两个 tab 都在讲合同：资金情况以合同为父节点折叠、合同管理也挂付款明细 → 重叠且各自不完整）：
  **资金情况 = 以「付款」为主线**（一条记录 = 一笔付款）；**合同管理 = 合同登记 + 合同附件**。
- **迁移 V11**：`payment` 补付款过程字段（`pay_method` 付款方式 / `handler` 经办人 / `invoice_no` 发票号 / `voucher_no` 记账凭证号 / `payee_name`·`payee_bank`·`payee_account` **收款账户快照**——合同账户可能中途变更，事后核对以付款当时为准）；新增字典 `PAY_METHOD`(5 项) 与 `PAY_REQUIRED_ATTACH`（报销所需附件清单，界面据此做**缺件提醒**，可在基础字典自行调整）；`ATTACH_TYPE` 扩 8 项（发票/付款审批单/法务意见书/合同会签表/授权委托书/履约保函/合同变更协议/供应商资质）；**历史合同附件归位**（`attach_type=CONTRACT` 且挂在项目/阶段上的统一改挂 `biz_type=CONTRACT` + `biz_id=合同id`）。
- **🔴 修掉一个结构性缺陷（迁移 V12）**：原先"项目挂合同"靠 `project.contract_id` **单个指针** → **一个项目实际只能有一份合同**（再登记第二份会覆盖指针、把上一份变成孤儿，随后被 `cleanupOrphanContracts` **悄悄逻辑删除**，数据会丢）。V10 的"一个项目多合同"其实只做了一半（灌数据时暴露：9 个项目只留下 9 份合同）。
  修复：新建 `project_contract` **关联表**作为权威关联（一个项目 N 份合同，一份合同 1 个项目），回填历史并**排除已逻辑删除的项目**；`project.contract_id` 保留但语义收窄为「主合同」指针，由新增的 `ContractLinkService.syncPrimaryContract` 自动指向 MAIN 类型合同（无 MAIN 取最早一份），使项目卡片/统计里既有的"合同金额"口径继续表示主合同；新增 `ContractLinkService` 统一承载「某项目可见哪些合同 / 某合同覆盖哪些项目 / 重建与解除关联 / 清孤儿合同」，并改造合同、付款、附件、项目、统计五处调用方（付款归属校验：多合同时**不允许"猜"**归属）。
- **前端**：资金情况改为 **Table + 可展开行**（横向对比多笔付款需要列对齐；折叠项只承载关联信息，不与列里已展示的信息重复）；列含 **报销凭证 `n/4`**（未付灰 / 齐备绿 / 部分黄 / 全缺红 + Tooltip 列出缺哪几项），展开行为 付款明细与留痕（含"与计划差额"）+ **本次付款的收款账户快照** + **报销所需附件清单（缺件红标、逐项可直接上传）** + 已上传凭证；顶部加筛选（状态/合同/关键词）与**风险提示条**（未关联合同的付款、已付款但报销缺件）。合同管理把付款明细表换成**付款进度结论**（指向资金情况，去掉重复），新增**合同附件区**（按 合同正本 / 法务与审批 / 招标与投标 / 担保与保证 / 其他 分组展示 + 上传）；附件中心新增「合同：xxx」分组；付款登记表单补付款方式/经办人/发票号/凭证号/收款账户（**新增时默认从合同带入**）。
- **演示数据**：`seed-demo.mjs` 重写为 v4——每核算单元 2~4 份合同（主合同 88% + 监理 2.5% + 测评 3.5% + 预算编制 1.2%）、主合同 **5 个付款里程碑**（30/40/20/7/3，含部分付款差额场景）、项目分工按 HW/SW 模板生成（含风险阻塞项）；`seed-attachments.mjs` 扩为三类附件并**故意让约 2/5 的已付款少传一类凭证**，用于验证缺件提醒。
- ⚠️ **迁移踩坑（重要）**：V11 首跑失败在最后一行 `CREATE INDEX`——该索引 V1 已建（报 `YAS-02043 columns have been indexed`）；而 **Oracle/崖山 DDL 隐式提交、无回滚**，前面的 ALTER 与字典 INSERT 已落库但版本未登记，容器 `restart: unless-stopped` 又不断重试 → 后续每次都撞 `duplicate column`。
  处置：**先 `docker compose stop` 止血** → 手工把已执行语句**回滚干净** → 重跑（等于顺便验证了每条语句）。
- 实测：V11 / V12 均成功（共 12 个脚本）；同一核算单元返回 3~4 份合同（主合同排最前）；主合同指针与关联表一致（有效关联 25 条 = 合同 25 份）。

### v3.5.1 — 修复 React #310 + 演示数据补全 + 死代码清理
- **🔴 React #310（hook 顺序）**：v3.5.0 新加的 6 个 hook（`payNodeOrder`/`contractById` 两个 `useMemo`、三个筛选 `useState`、`filteredPayments` 的 `useMemo`）被放在组件里 `if (!detail) return …` **之后** → 首屏（loading）走提前 return 少调这些 hook，数据回来后多调 → 数量不一致，抛 `Rendered more hooks than during the previous render`。
  修复：全部**前移到提前 return 之前**，并就地注释说明原因。自查口径：**"提前 return 之后"的 hook 数必须为 0**（该组件 34 个 hook 全在其前）。
- **"数据库里合同缺字段"的排查结论**：`contract` 表 69 行中 `deleted = 0` 仅 25 行，缺字段的是 **V10 之前批次**留下的**逻辑删除旧行**。根因链：本系统删除一律是逻辑删除（审计留痕），而 `seed-demo.mjs` 走 API 删项目 → 旧行只被标记 `deleted=1` 不消失 → 反复重跑 seed 就越堆越多。
  → 排查数据先按 `deleted = 0` 过滤；想从干净基线开始用新增的 `scripts/demo-reset.sql` **物理清空**后再重灌。
- **演示数据补全**：合同补 `contractStatus`（按推进度自动给 DONE / CHANGED / ACTIVE）、`settleAmount`（结算后才有）、`remark` 与服务类合同的验收标准；新增 4 份「方案评估 · **待签订** · 无付款记录」合同（用于演示状态标签与"零付款节点"的合同）；核算单元合同数 2~4 → **2~5 份**。
- **新增开发机工具**：`scripts/demo-reset.sql`（物理清空演示数据）；`scripts/db-sql.sh` + `scripts/jdbc/RunSql.java`（开发机无 yasql 客户端时手工查/改库，连接信息自动从容器环境变量或 `.env` 取，**口令不打印不落盘**）；`scripts/README.md`（11 个脚本的清单、用途与用法）。
- **死代码清理**：前端显式跑 `tsc --noUnusedLocals --noUnusedParameters`（项目默认关着这两个检查）→ **16 处清零**；后端未使用导入 **8 处清零** + 删除死方法 `ContractController.normalize`；`seed-attachments.mjs` 删除只写不读的 `nameCache`。
  ⚠️ 注意：机器扫描报出的另外 3 个"疑似死方法"（`versionOf`/`paymentBrief`/`toPhaseVO`）实为 **`this::method` 方法引用**，核对后保留——**删之前必须 grep 确认**。
- **约定**：前端改动不再由 AI 起浏览器做端到端验证（做到 类型检查 + 构建 + 数据/接口核对 即收尾，交互效果由用户刷新页面确认）；不主动重建镜像，需要时先问。
- 实测：清库重灌后 **29 份合同 / 77 笔付款 / 128 条项目分工 / 412 个附件**（阶段 217 · 合同 114 · 付款凭证 81）；`contract` 表 `deleted=1` 行数 0、在用合同字段缺失 0（仅 `settle_amount` 21 份为空——未结算，符合业务）；合同状态 ACTIVE 16 / DONE 6 / DRAFT 4 / CHANGED 3。
- **文档补齐（同一轮收尾，用户指出「ITERATION.md 根本没更新完，严谨一点」）**：README 的维护约定写着「迭代总表新增一行 **+ 各迭代明细补充要点**」，而此前只加了总表行 → 明细段停在 v3.1.1，缺 **11 个版本**。现已补齐（历史遗留：v1.7 / v2.1 / v2.2 / v2.3 / v2.4；本轮相关：v3.2.0 / v3.3.0 / v3.3.1 / v3.4.0 / v3.5.0 / v3.5.1），并加了**集合级校验**：总表版本集合 == 明细版本集合（33 == 33）。
  《设计方案》逐章同步：§5.3 附件归属**三类→四类**（新增 `CONTRACT`）、§5.4 补 V11 付款留痕与收款账户快照 +「报销所需附件清单」小节、**新增 §5.5 合同字段**（政府合同字段分组 + 一对多/多对多规则演进）、§6.2 页签 **五→八个**、§8 表数 **11→14**（补录 `attachment_upload_task`）、§13 差异清单 **截至 v3.0 → v3.5.1** 并新增 21~32 条、§14 维护约定补「总表与明细两处版本必须一一对应 / 演示数据为固定交付项 / 收尾清死代码」；`README.md` 维护约定同步细化；修正设计方案目录 **4 条失效锚点**（§8/§11/§12/§13 与实际标题不符，现目录 15 条与标题全部匹配）。
  → 教训：本项目文档是「总表 + 明细 + 功能完成度 + 版本表 + 差异清单」多层结构，一处改动往往要同步 5~7 个位置，改完必须做**集合级校验**而不是目测。

### ai-0.4.0 — ai-service v0.4.0：移除本地 OCR 引擎 + P0 规范化

> 独立服务（原 `ai-service/`，**现为 `ai-backend`**）的迭代，**与主系统版本号分开编号**（`ai-` 前缀）。
> 此前已有 4 个提交：骨架 → Docker 化 + 首次端到端自测 → 文档分析前端 + 文档库/问答框架 → **接入平台 OCR + 上传任务队列（v0.3）**；
> 也就是说它在功能完成度表里"暂缓开发"的标注**早已与事实不符**，本轮一并纠正为「🚧 进行中」。

- **🔴 移除本地 OCR 引擎，只走内网平台 OCR**：同一页真实合同（《数据库一体机补充协议》）实测——
  平台 `PaddleOCR-VL-1.6-0.9B` 把 `7,780,000.00` / `5,446,000.00` / `2,334,000.00` 与大写「**柒**佰柒拾捌万元整」**全部识别正确**、印章独立成 `seal` 块；
  本地 RapidOCR **金额一个都没读到**（只认出合同编号）、印章文字串进正文、版面顺序混乱。**质量差距是量级性的**。
  决策依据还有一条审计场景的判断：**"给出低质量结果比明确报错更危险"**——自动回落会把"平台挂了"掩盖成"结果差一点"。
  → 删除 `src/pm_ai/ocr_engine.py`、`scripts/selfcheck_ocr.py`；`pyproject.toml` 删除 `rapidocr-onnxruntime` / `pillow` / `[paddle]` extra；
  `OCR_PROVIDER` / `OCR_DPI` / `OCR_WORKERS` 三个环境变量**不再存在**；`/ocr/file` 不再"固定走本地引擎做对照"，改走平台；
  **平台不可用时报错并提示人工复核，不降级**；Docker 镜像去掉 `libgl1` / `libglib2.0-0` / `libgomp1`（OpenCV/onnxruntime 的依赖），
  构建期不再跑本地 OCR 冒烟自检（改为**只校验包能 import**，识别能力改由运行时自检判定）。
- **🔴 对 Java 迁移的影响（本轮最重要的架构结论）**：主系统那两台国产 ARM 服务器**没有 GPU**，
  所以 AI 能力**无论如何都要独立部署**——"迁 Java"省下的始终只是技术栈统一，**不是少一个服务**；
  而原先"全迁 Java"最大的技术障碍"**本地 OCR 没有 Java 等价物**"**已随本次移除消失**，
  只剩 PyMuPDF 一项，且实测 `pdf_utils.py` **并未使用** `find_tables()`（只用了 `fitz.open` / `page_count` / `page.get_text` / `page.get_pixmap`，
  表格只是按文本里的 `<table` 或平台返回的 blocks 标签计数）→ **可用 PDFBox 1:1 替代，代价由"高"下调为"中低"**。
  → 已写入 `ai-service/docs/架构评估与规范化方案.md`（§2.1 事实修正 + 文末「2026-09-18 更新」），
  但**迁移时机不变**：仍建议等检索层落地与接口冻结后再评估，先迁纯 HTTP 那 70%。
- **P0 规范化落地**（对应评估文档 §3.2 的 P0）：`ruff`（lint + format，规则集 `E/W/F/I/UP/B/SIM`，行宽 100）
  + `pytest`（**4 条**：大写金额解析 / 平台 OCR 响应解析 / 任务状态机 / `calculate` 白名单）；
  依赖**全部加上下界**（不再"每次装出来都不一样"）；日志 `print` → `logging`；
  新增 **`[dev]` extra**（pytest + ruff）与 **`scripts/check.sh` / `check.cmd`**（**本机优先、Docker 兜底**：
  本机 Python 装于 `E:\env\python-3.11.9`、venv 在 `E:\env\venvs\pm-ai`，非必要不再用容器——容器一轮十几秒，
  本机 `ruff check + format --check + pytest` **0.2 秒**，实测 50 passed）；
  `pandas` / `openpyxl` 移入 **`[scripts]` extra**（只有一次性摸底脚本用），`pillow` 删除。
  **CI 仍未做**（属 P1）。
- **工具集 4 → 3**：删掉 `list_documents`——文档清单已由 **system 提示词直接注入**，模型再调一次属重复劳动；
  `calculate` 改为**全程 `Decimal` 精确计算**并收紧运算符：**去掉 `**` / `//` / `%`**，只放开 `+ - * /` 与括号（`9**9**9` 能瞬间打满 CPU）；
  **返回值是字符串**（`result` / `rounded_2`，Java 侧对应 `BigDecimal` + 字符串返回），避免 JSON 浮点丢分位精度。
- **版本**：`0.1.0` → **`0.4.0`**（`pyproject.toml`；README §8 同步改为"实际版本口径"）。
- **配套文档同步**：`ai-service/README.md`（§13 改写为"只走平台 OCR"+ 两条理由 + 不降级行为；环境变量表、目录结构、§8 版本现状重写；新增 **§15 质量门**）、
  `ai-service/.env.example`（删本地引擎变量、补 `OCR_SEAL_MIN_PIXELS` 等平台参数注释）、
  `ai-service/docs/架构评估与规范化方案.md`、`ai-service/docs/知识库总体架构与演进路线.md`（§9.3 本地 OCR 三步现状标注）、
  `docs/附件智能处理能力-自测方案.md`（历史对比数据保留 + 时点说明），以及本文档。
- **下一步**：P0 落实 embedding 来源 → P1 表驱动异步任务（主系统迁移 V13 `attachment_ai_task`）→ P2 混合检索 + 评测集，
  施工图见 [`ai-backend/docs/知识库实施方案与路线.md`](ai-backend/docs/知识库实施方案与路线.md)（原 `ai-service/docs/知识库落地实施方案.md`，随 Python 侧清理由 `docs-1.0` 合并至此）。
- **文档一致性**：本文档「总表行数 == 明细段数」的集合级校验随之由 **33 == 33** 变为 **34 == 34**（新增 `ai-0.4.0` 一行 + 对应明细一段）。

### ai-0.5.0 — ai-service v0.5.0：检索链路接通（Embedding 召回 + Reranker 精排）

> 独立服务（原 `ai-service/`，**现为 `ai-backend`**）的迭代，**与主系统版本号分开编号**（`ai-` 前缀）。
> 上一轮 `ai-0.4.0` 做的是**质量与收敛**（P0 规范化 + 移除本地 OCR 引擎），本轮把**检索层**接上——
> 也就是《知识库总体架构与演进路线》§0.2 里那三类"缺的东西"中的**检索层**。

- **本轮前提：平台能力已就位**。网关 `http://10.254.208.35:8090/v1` 已部署
  **`Qwen3-VL-Embedding-8B`**（**固定 4096 维**；⚠️ 平台实际部署**不支持 MRL 降维**——
  传 `dimensions` 实测 **HTTP 400**（`does not support matryoshka representation`），
  手册写的"64~4096"与**实际部署不符**，故 `VEC_EMBED_DIMENSIONS=0` 表示**不传**）与 **`Qwen3-VL-Reranker-8B`**，
  与千问对话、平台 OCR **共用同一把 sk**（配置默认复用 `LLM_BASE_URL` / `LLM_API_KEY`，不新增密钥）。
  → 这推翻了此前"平台网关不提供 embedding（`POST /v1/embeddings` 返回 404）"的判断，
  也让"本地自建 TEI embedding 端点"那条路线不再必要（文档里作为历史推演保留，口径已改）。
- **新增 `src/pm_ai/vec_client.py`（Embedding / Reranker 客户端，纯 `urllib`，不引新依赖）**：
  `embed_texts(texts, instruction=None, dimensions=None)` → `POST /embeddings`，**返回顺序与入参一致**；
  带 `instruction` 时走 `messages`（system 角色）形式，此时**不传 `dimensions`**（手册只给了这两种组合）；
  `rerank(query, documents, top_k=None)` → `POST /rerank`（Jina 风格），返回 `[(原始下标, 分数)]` 降序——
  ⚠️ **上游按原顺序返回 `results`，下标是入参下标，不能当排名用**；
  `health(timeout=10)` → `GET /models` 探活（**不消耗配额**）；另有 `cosine(a,b)` 工具函数。
- **新增 `src/pm_ai/retrieval.py`（三步链路）**：
  ① **召回**：向量召回（`Qwen3-VL-Embedding-8B`）+ 关键词召回（字符 2-gram + IDF，原实现在 `tools.py`，本轮**搬迁**到这里）；
  ② **融合**：按 `(doc_id, page_no, 文本 sha1)` 去重（向量命中优先，关键词补齐）；
  ③ **精排**：Reranker 逐对打分，取 `top_k`。
  向量缓存在 **`work/vectors/<doc_id>.json`**（含 `model` / `dimensions`，**切片内容变了自动失效**）——
  向量与索引都是**可重建物**，删掉重算即可，**不进主系统**。
- **降级行为（与 OCR 降级性质不同，刻意区分）**：向量服务不可用时**降级为关键词检索**，
  并在结果 `note` 里写明原因（如"向量服务不可用（…），本次仅用关键词召回"）。
  理由：答案**仍然带页码来源**、召回变少是**可见的**（"没找到"看得见），
  不会像 OCR 静默降级那样产出"看起来正常、实际内容全丢"的结果。
- **索引后端可切（但只实现了一个）**：`VEC_BACKEND=local`（默认，进程内余弦、零部署）→ `opensearch`（**正式选型**）。
  ⚠️ **`opensearch` 适配层尚未实现、集群也尚未部署**：配置成 `opensearch` 时 `retrieval._check_backend()`
  **显式抛错**（由 `tests/test_retrieval.py::test_unimplemented_backend_fails_fast` 钉住），
  **不静默退回进程内检索**——静默降级会把"以为在用集群"和"实际在进程内算"混在一起，属最难排查的一类问题。
  切片键已按内容指纹生成，**可直接当索引 `_id` 做幂等 upsert**。
  **下一步：OpenSearch 集群部署后实测 kNN 在线路由**（选型与字段见 `ai-backend/docs/知识库实施方案与路线.md`；适配层已实现、路由已接但**未实测**，集群不可达/配错时**显式报错**、不静默退回进程内检索）。
- **`src/pm_ai/tools.py` 改为委托**：`search_documents` 现在只做"参数容错 + 结果整形"并调用 `retrieval.search()`，
  **工具签名不变**（`search_documents(query, top_k, doc_id)`），返回体新增 `retrieval`（hybrid/keyword）、
  `reranked`、`note` 三个键；关键词实现已从该文件移出。→ 提示词与问答编排**零改动**，
  这正是当初把检索单独分层留下的替换位。
- **新增 `scripts/vec_try.py`（链路验证脚本）**：`--health` / `--selftest`（对应调用手册 §7.1 的三项功能验收：
  向量化维度、语义判别、重排排序）/ `--ingest <PDF>`（解析→切片→入库）/ `--search "<查询>"` /
  `--top-k` / `--doc-id`；另有 **`--offline`**：用**确定性伪向量 / 伪重排**跑通接线（网关不可达时用，
  **结果无语义**，只看"有没有结果、顺序是否由重排决定、缓存是否命中"）。
- **新增配置项**（`config.py`，`.env` / `.env.example` 由用户另行维护）：`VEC_BASE_URL`（默认复用 `LLM_BASE_URL`）、
  `VEC_API_KEY`（默认复用 `LLM_API_KEY`）、`VEC_TIMEOUT`(300)、`VEC_EMBED_MODEL`、`VEC_RERANK_MODEL`、
  `VEC_EMBED_DIMENSIONS`(**0**，＝不传 dimensions；平台不支持 MRL 降维，实际 4096 维)、`RETRIEVAL_RECALL`(50)、`RETRIEVAL_TOP_K`(5)、`VEC_BACKEND`(local)、
  `OPENSEARCH_URL` / `OPENSEARCH_INDEX`(`pm-ai-chunks`) / `OPENSEARCH_USER` / `OPENSEARCH_PASSWORD`。
- **HTTP 自检**：`GET /health?with_vec=true` 返回 `vec` 块（`ok` / `detail` / `models` / `backend` /
  `embed_model` / `rerank_model` / `dimensions`），走 `GET /models` 探活，不消耗配额。
- **测试**：新增 `tests/test_retrieval.py` **5 条纯离线用例**（精排顺序确实生效 / 向量不可用时降级为关键词且 `note` 写明 /
  切片向量走缓存（第二次只为 query 编码）/ 切片键随内容变化 / `VEC_BACKEND=opensearch` 显式报错）。
  质量门现为 **55 个用例全绿**，`ruff check` 与 `ruff format --check` 均通过。
- **实测（如实写，不夸大）**：
  - ✅ **已跑通**：开发机**离线接线演练**（`--offline`）——解析一个文本型 PDF → **1 个切片** →
    混合检索命中、结果**带页码与分数**；质量门 55 用例全绿。
  - ❌ **未验证**：开发机**连不上内网网关**（`URLError: timed out`；`10.254.208.35` 是政务内网地址），
    所以**真实模型的向量化 / 重排 / 语义判别尚未在开发机验证**——需在有内网访问的机器上跑
    `python scripts/vec_try.py`（默认自检）确认，见 `ai-service/README.md` §13。
- **检索层存储选型统一为 OpenSearch**（项目负责人明确决定）：**以 OpenSearch 为准**；
  **崖山 YashanDB 自带的向量能力（v23.5.4.100 AI Edition 的 `VECTOR` + HNSW + `SEARCH INDEX`）
  只作为备选，不作为选型基线**（把选型从"待确认的崖山版本号"上摘下来）。
  **OpenSearch 目前尚未部署**；过渡期用 `VEC_BACKEND=local`。
- **文档同步**：`ai-service/README.md`（新增 **§13 检索链路：Embedding 召回 + Reranker 精排**，
  §14/§15/§16 顺延并修正全部交叉引用；§5 目录结构与 §6 接口表补齐；§7 与 §12.6 改为"已接通"+OpenSearch 口径；
  §16.1 测试要点四条→**五条**、用例数 **55**）、
  `ai-service/docs/知识库落地实施方案.md`（选型改为 OpenSearch 为准、崖山降为备选、补 `retrieval.py` 对接位置）、
  `ai-service/docs/知识库总体架构与演进路线.md`（§7 P2 统一为 OpenSearch；附表工具数 4→3；
  检索层两处更新为"已由 `retrieval.py` 实现"）、
  `ai-service/docs/架构评估与规范化方案.md`（文末追加本轮更新节）。
- **文档一致性**：本文档「总表行数 == 明细段数」的集合级校验随之由 **34 == 34** 变为 **35 == 35**
  （新增 `ai-0.5.0` 一行 + 对应明细一段）。

### ai-1.0.0 — AI 能力服务 Java 化：与主系统同栈、独立部署（2026-09-20）

- **为什么迁**：一套工具链/一套发布脚本/一套人；Python 侧剩下的都是过渡件（本地向量缓存、单页调试前端）。
  **为什么仍独立部署**：平台 OCR、大模型、向量化都在 GPU 机上，AI 能力无论如何都要单独发版——合并进主系统只会把"AI 发版"绑死"业务发版"。
- **模块结构**：`ai-backend/`（Maven 独立模块，`com.pmgt.ai`）：
  `common/{config,web,util}`（配置 / 统一响应与异常 / 进度回调）、
  `module/{ocr,doc,check,store,retrieval,llm,task,system}`（每个模块 service + controller + 单测）。
- **必须保留的踩坑结论（写进代码注释，并有测试钉住）**：批量 OCR 响应 `blocks` 在 `results[i]`；
  平台不返回置信度（不许编）；150 DPI/JPEG 足够（体积差 17 倍）；印章默认关（会编造文字）；
  Embedding 不支持 MRL 降维（传 `dimensions` → 400）；Rerank 返回的是**入参下标**；
  `calculate` 只放行 `+ - * / ( )` 且全程 `BigDecimal`、返回字符串；思维链默认关；system 消息只能一条且在最前。
- **我负责的接线层**：`UploadTask`/`UploadTaskService`（阶段加权进度、取消/移除、重启标失败、落盘只留 100 条）、
  6 个 controller、`ApiResponse`/`ApiException`/`GlobalExceptionHandler`（对齐 FastAPI 的 `{"detail"}`，含上传超限 413）、
  `TempUploads`（300MB 上限 + 路径穿越防护）、`BeansConfig`（`DocStore` 显式装配）、`AsyncConfig`。
- **验收证据（真实网关）**：
  | 项 | 结果 |
  | --- | --- |
  | 自检 `/health?with_ocr=true&with_vec=true` | 网关可达、两个向量模型在列、OCR 探针 ok |
  | 平台 OCR（扫描件 1 页） | `engine=platform`，识别出《中标通知书》正文（项目负责人/中标日期等） |
  | 抽取 `/analyze`（文本型 PDF） | `llm.ok=true`、tokens 1375+779、8.73s、带 `[P1]` 来源与置信度 |
  | 上传任务 `/upload-tasks` | `QUEUED → DONE`、percent 100、1.47s、doc_id 正常 |
  | 问答 `/chat` | 10.27s；轨迹 `search_documents×2 → read_page`；金额正确并**主动存疑**；未找到项如实说明 |
  | 单元测试 | `mvn test` → **81 passed**（迁移期间逐步补齐：换行归一 1 条、检索路由与标点折叠 7 条；口径以 `mvn test` 打印为准） |
  | 与 Python 差分 | `Checks` 35 个用例逐字段一致（含 detail 文案、页码、status） |
- **运行方式**：`mvn -B -DskipTests package` → `java -jar target/pm-ai-backend-1.0.0-SNAPSHOT.jar`；
  质量门 `bash ai-backend/scripts/check.sh`（或 `scripts\check.cmd`）；端到端验收 `pwsh -File ai-backend/scripts/verify-e2e.ps1`；
  凭据放 `ai-backend/.env`（变量名与 Python 版一致，运维无需改脚本）。
- **下一步（本轮未做，属清理与收口）**：删除 Python 侧 `ai-service/`（代码/脚本/文档），
  把两份只读手册（平台 OCR、Embedding/Reranker）迁到 `ai-backend/docs/`，
  统一 ITERATION/README/设计文档与部署资产到 `ai-backend` 这一条主线；
  OpenSearch 集群部署后补适配层实测并切 `VEC_BACKEND=opensearch`。

### ai-1.0.1 — 清理与回归单一主线（2026-09-20）

- **删除**：`ai-service/`（Python 侧 50 个受控文件：`src/pm_ai` 15 个模块、`scripts` 7 个脚本、`tests` 5 个用例文件、
  811 行单页调试前端与其 vendored `marked.min.js`、`Dockerfile`/`docker-compose.yml`/`pyproject.toml`/
  `.env.example`/`.dockerignore` 等），以及它的本地 `.env`、缓存与 `work/` 残留。
  **为什么敢删**：行为规格已由 Java 侧承接并有证据——`Checks` 与 Python **35 用例逐字段差分一致**、
  提示词 **11 项 SHA-256 零差异**、平台 OCR 与向量化都在真实网关上对过基线（§ai-1.0.0）。
- **迁移（保知识、不保代码）**：
  - 两份只读接口手册 → `ai-backend/docs/`（`平台OCR调用使用手册.md`、`Qwen3-VL-Embedding-Reranker调用手册.md`）；
  - 四份规划文档 → `ai-backend/docs/`（架构评估与规范化、知识库总体架构与演进路线、知识库落地实施方案、embedding 端点部署方案），
    每份顶部注明"实现已迁移到 Java 版"，正文里的 Python 实现路径改为对应 Java 类或标注为历史；
  - **新增《平台能力实测结论》**：把原先散在 Python README（64KB）里的实测数字与踩坑结论固化到主线——
    平台 OCR 渲染参数（150 DPI/JPEG q85，实测 120~300 DPI 结果一致但体积差 17 倍）、批量/并发（≤16 页、并发 12）、
    印章默认关（低 DPI 会编造文字）、平台不返回置信度、批量响应 blocks 在 `results[i]`、
    确定性校验的四条规则与两处已知缺口、思维链默认关（会吃光 max_tokens 导致 content 为空）、单条 system 必须在最前、
    Embedding 固定 4096 维（平台不支持 MRL 降维，传 `dimensions` 实测 400）、Rerank 返回入参下标、
    检索降级与 OCR 降级性质不同、质量门用例数（Java 81 / Python 55）。
  - 合成样本 4 份 → `ai-backend/work/samples`（`work/` 不入库），`verify-e2e.ps1` 默认路径同步。
- **保真修正**：`PdfReader.stripPage` 把 PDFBox 的 `\r\n` 归一为 `\n`、整页空白回空串——
  归一前 Java 与 PyMuPDF 差 9 字（191 vs 182）、空白页差 1 字（1 vs 0），归一后**逐字节一致**（182==182、0==0，实测四份样本）。
- **容器退役**：旧 Python 服务容器 `pm-ai-service` 停止并移除（把 8100 让给 Java 版；镜像保留以便回溯），
  Java 版发布走 `ai-backend/docker-compose.yml`（多阶段镜像，不含测试与本地 OCR）。
- **引用同步**：`PlatformOcrClient`/`VecClient` 注释里的手册位置、迁移对照表的 curl 示例（样本改为 `work/samples`）、
  根 README 目录树（`ai-service` 行删除、`ai-backend` 为唯一 AI 服务）；代码注释中残留的 `ai-service/src/pm_ai/*.py`
  统一说明为"历史出处标注"（便于回溯"这段逻辑当初为什么这么写"）。
- **检索侧收口（同轮内完成）**：① **消除 OpenSearch 死代码**——原先 `RetrievalService` 只调 `health()` 做守卫，
  写入与 kNN 查询**无调用点**，于是"配 `opensearch` 且集群可达"时会**静默走进程内检索**（属最危险的一类）；
  现接线为 探活 → 复用同一份向量缓存 → `ensureIndexOnce`（`HEAD`，404 才建）→ `_id`=切片键**幂等 upsert**
  → kNN 召回 → 与关键词榜同一套融合 → Reranker 精排；失败**显式抛错不降级**（集群里可能已有几十万切片，
  关键词那一路召回不全，给部分结果比报错更误导）。② **修全/半角标点折叠**：文档写 `7，982，300.00`（全角）、
  查询 `7,982,300.00`（半角）时原先互相漏召回（本轮验收里模型因此把金额标为"格式存疑"）；
  现匹配前折叠、**原文与返回的 `text` 一律不动**（有意偏离 Python，注释已写明"别改回去对齐 Python"）。
  ③ **自查修掉一个严重 bug**：索引写入原按 `Map.values()` 与切片**下标** zip，而 `Map` 迭代顺序无保证——
  一旦错位，向量会挂到**别的切片文本**上（向量仍 4096 维、检索照样出结果但**全是错的**）；改为按 `chunkKey`
  逐切片取向量，并加回归用例 `indexedVectorStaysPairedWithItsOwnChunkText` 钉住。④ `/chat` 契约纠错：
  `rounds` / `prompt_tokens` / `completion_tokens` / `elapsed` 在 **`data.llm`** 内（对照表 §3 原先写错，
  按顶层取数会静默拿到空值）。
- **配置鲁棒性（实测复现后修）**：`.env` 改为**双路径导入**（`./.env` 与 `./ai-backend/.env`）并新增
  `StartupDiagnostics`——`.env` 按**进程工作目录**解析，从仓库根起 jar 时原先会静默丢凭据
  （服务照常启动、`/health` 也 200，直到调平台才 401 missing api key）。
- **部署资产实测**：`docker build -f ai-backend/Dockerfile`（上下文=仓库根）→ `pm-ai-backend:local`（474MB）
  → 起容器 → `/health` 三探测全 OK、容器内跑平台 OCR 扫描件 1.49s 文本正确。
- **遗留**：OpenSearch 集群仍未部署（9200 探测不通）→ kNN 在线路由标注"**已接线、未实测**"，
  `VEC_BACKEND` 保持 `local`；配错/不可达时**显式报错**、绝不静默退回进程内检索。

### docs-1.0 — 文档与脚本集约化重整（2026-09-20）

- **起因**：多轮迭代后文档积累出三类问题——① ITERATION 表格被空行打断，最新两行渲染成普通段落；
  ② 过程性/已作废文档堆积（Python 时代的操作步骤、已完成的迁移计划、已移除功能的评估），制造歧义与阅读负担；
  ③ 脚本散落在 `scripts/`、`deploy/windows/`、`deploy/docker/`、`ai-backend/scripts/` 四处，没有统一清单。
- **表格修复**：用一次性脚本对全仓 Markdown 做体检（两类病：**表格内空行**、**单元格裸竖线**），
  修掉 ITERATION 的 2 处空行（`ai-1.0.0`/`ai-1.0.1` 回到表格内）与厂商手册 1 处竖线；
  两条禁忌写入 `README.md` 硬性维护约定，避免复发。
- **两张地图**：
  - [`docs/README.md`](docs/README.md)：**唯一文档地图**——按角色的导读路径、权威文档清单（主系统 9 份 + AI 6 份）、
    **已核减文档登记表**（写明去向，避免以后再去仓库里翻）、文档维护约定；
  - [`scripts/README.md`](scripts/README.md)：重写为**脚本与操作唯一清单**——§0 环境与三条硬约定
    （含 **Shell 要求**：`.cmd` 可用、`.ps1` 需 pwsh 7、`.sh` 需要 Git Bash 而**本机没有**）、
    §1 场景速查（开发/停止/AI 本地跑/质量门/端到端验收/演示数据/查库/本地镜像部署/迭代重建/发版/
    服务器部署/升级/重启/回滚共 17 项）、§2~§4 分组明细、§5 新增脚本约定（优先 Windows 原生入口）。
- **核减清单**（原文件 → 去向，详见 `docs/README.md` §2）：崖山 2 合 1（新《崖山数据库与迁移约定》）、
  知识库 2 合 1（新《知识库实施方案与路线》）、附件自测方案压缩（新《附件智能处理-验收标准与现状》）、
  双机 ARM 与 Gitea 并入《部署与发布全流程手册》附录 A/B、删除《embedding 端点部署方案》
  《架构评估与规范化方案》《流程模板图形化评估》三份已作废/已移除的文档。
- **同步机制**：主系统与 AI 能力服务文档改动**双向登记** `docs/README.md`；一个主题只留一份权威文档；
  过程性文档完成后核减，决策与实测数字进 ITERATION。
- **可持续性（别再靠临时脚本发现）**：新增 [`scripts/check-docs.mjs`](scripts/check-docs.mjs)——文档体检：① 表格内空行断表；② 单元格裸竖线；③ 本地链接失效；④ ITERATION 总表与明细不一致；有问题退出码 1（可接 CI）。同时写入维护约定：**改完文档提交前跑一次 `node scripts/check-docs.mjs`**。
- **未做（待确认）**：① 把 `scripts/*.sh` 改写/新增为 Windows 原生 `.cmd`/`.ps1` 等价实现
  （本机无 Git Bash，`make-release.sh` / `dev-reload.sh` / `db-sql.sh` 目前只能装 Git Bash 或走 WSL）
  —— **已于 `docs-1.2` 完成**：三者改写为 Windows 原生 `.ps1`（`pm-upgrade.sh` 例外，它只在 Linux 服务器上跑）；
  ② `.workbuddy/memory/` 下 7 份助手工作日志（约 1900 行，**未被 git 跟踪**）是否清理。

---

### docs-1.1 — 重要文档同步性审计 + 目录整理（仓库上移）+ AI 前端方案评估（2026-09-20）

- **同步性审计**（根 README / ITERATION / 设计方案 逐项对现状）：**发现并修 2 处不一致**——
  ① 根 README「当前版本」仍写 **v3.5** → 改为 `主系统 v3.5.1 ｜ AI 能力服务 ai-1.0.1 ｜ 文档与脚本 docs-1.0`；
  ② 设计方案 §7「页面结构」里项目详情页签仍列 **5 个**（与 §6.2 的"v3.5 起最多 8 个"矛盾）→ 统一为 8 个
  （流程进展/项目信息/子项目（仅容器）/资金情况/合同管理/项目分工/附件中心/操作日志）。
- **核对无误的项**（避免"以为不同步"）：数据模型 **14 张表**——V1 建 9 张（含后被 V3 删除的 `sys_dept`）、
  后续 V4/V6/V7/V9/V10/V12 各增表，实存 14 张，与设计稿 §8 标题一致；MySQL 相关表述均已是历史口径（v3.0 已下线）；
  端口与脚本用法与 `scripts/README.md` 一致；AI 能力服务已在设计稿 §0/§10/§13 登记。
- **Python 残留排查**：全仓**无** `.py` / `pyproject.toml` / `requirements*` / `__pycache__` / `.venv` /
  `.pytest_cache` / `.ruff_cache` / `.mypy_cache`；Dockerfile 与 compose 里**无 Python 基础镜像**。
  仅剩两类**合法的历史提及**：Java 类注释里的溯源（"移植自 `checks.py`"等）与文档中的历史记录/厂商手册里的 Python 示例。
  顺带把 Dockerfile 里一句措辞不清的编码注释改为不依赖 Python 语境。
- **目录整理**：删除 `project/` 下确认无用的内容（最初静态原型 `demo/`、已被现设计稿 §11 覆盖的 `设计方案-v0.1`、
  npm 缓存 `.devtools/` 共 1660 项、跑服务留下的空壳 `work/`、0 字节文件 `打开浏览器`）；
  把**本机专用资产**（数据库导出、口令清单、SSH 公钥 + 两个本机启动助手）收进仓库内 **`local/`（已 gitignore，不入库）**；
  仓库由 `Desktop\project\project-manager` **上移到 `Desktop\project-manager`**，旧 `project\` 目录已删除；
  启动助手的 `ROOT` 改为相对路径 `%~dp0..`（以后搬移无需再改）。
  验证：新位置 HEAD 与旧一致、27 个提交仍在、工作区干净、`local/` 确认被忽略、文档体检 18 份 0 问题。
- **AI 前端方案评估**：新增 [`docs/AI前端与集成方案.md`](docs/AI前端与集成方案.md)——
  路线 A（独立 React 前端）/ B（并入主系统：菜单 + 全局悬浮）/ C（B + 保留调试页）三条对比后**否决 A**、推荐 **B+C**；
  含知识库面板五页签（文档库/任务队列/检索调试/服务自检/评测指标）、附件"是否已可检索"状态与自动触发、
  全局悬浮对话（上下文感知作用域、出处可点击到附件页码）、主系统侧 V13（`attachment_ai_task` / `ai_index_status` / `ai_ask_log`）、
  nginx 同源代理与"浏览器永不直连 AI 服务"的鉴权边界、P0~P3 分期（P0 3~5 人日）与六条风险对策。
- **未做（待你决策）**：① `scripts/*.sh` 是否改写为 Windows 原生 `.cmd`/`.ps1`；② `local/export`（含口令清单）是否移出仓库树。
  —— **两项均已于 `docs-1.2` 落地**（脚本转 `.ps1`；`local/export` → `E:\env\pm-local\export`）。

---

### v3.6.0 — AI 与知识库 P0：并入主系统前端 + 主系统代理层（V13）+ AI 服务结构化引用（2026-09-20）

> 本文与 `docs-1.2` 是**同一轮工作**的两面：本文记"做出了什么、怎么验的"，`docs-1.2` 记"文档与脚本怎么收口的"。
> 方案依据见 [`docs/AI前端与集成方案.md`](docs/AI前端与集成方案.md)（§9 接口契约**冻结**、§10 前端工程规范）与
> [`docs/AI工具集与检索编排评估.md`](docs/AI工具集与检索编排评估.md)。

**① 前端：AI 能力并入主系统（不单独做一套前端）**
- 新增「AI 与知识库」菜单 `/ai`（`ADMIN`/`MANAGER` 可见）：**① 文档库**（分页/搜索/删除/重新解析）、
  **② 任务队列**（状态筛选、失败原因、重试）、**③ 服务自检**（AI 服务/平台网关/向量后端/四个模型/文档数/待处理任务数）
  —— 三个页签都是**真正可用**的四态页面，**不放 P1/P2 占位页签**（检索调试与评测指标属 P1/P2，本轮不建）。
- **全局悬浮问答**：右下角常驻图标 → 右侧 `Drawer`（不打断当前操作），**与 `/ai` 页共用同一份对话组件**
  （不写两份）；顶部显式显示"当前范围"并默认**跟随页面**（项目详情页 = 该项目）；答案用既有 `react-markdown` 渲染，
  `toolTrace` 折叠展示，`degraded`/`notice` 用 `Alert` 明示；**"未找到"如实显示为未找到**，不显示成通用错误。
- **引用可点击跳原文**（本轮最能建立信任的一点）：答案里的 `[n]` 对应结构化 `citations`，点击直接打开该附件并
  **定位到第 N 页**——为此给既有预览组件 `AttachmentPreviewModal` 加了**纯增量**的可选参数 `pageNo`（仅 PDF 分支拼
  `#page=N`，不传时行为完全不变）。`attachmentId` 由主系统在 `citations` 里回填；映射不到（附件已删）则该条
  明确显示"附件已删除"而**不做成坏链接**。
- 附件中心：每个附件显示 AI 索引状态标签（未解析｜解析中+进度｜已可检索｜解析失败+原因）＋「重新解析」，
  并提供"解析未入库附件（N）"批量入口；**AI 服务不可用时明确提示"状态读不到"，不假装已知**。
- 🔴 **自查发现并修掉一个严重回归**：批量解析用的 `useMemo` 被写在页面中段 `if (!detail) return …` **之后**，
  首屏不执行、详情到达后才执行 → `Rendered more hooks than during the previous render`（**React #310**，
  v3.5.1 踩过的同型缺陷，整个项目详情页会崩）。已前移到 hook 区并加注释钉住；**`tsc`/`vite build` 都发现不了这类问题**
  （只有真跑页面才炸），故保留"提前 return 之后 hook 数必须为 0"这条结构性自查。
  同时修掉 `useLocation` 只 import 未使用（违反 §14.7 的 `--noUnusedLocals` 清零要求）——改用路由的 `useLocation()`
  也顺带修好了真问题：引用跳转链路的 `useEffect` 依赖 `location.search`，用全局 `window.location` 不会随路由变化触发。
- 验证：`npm run build`（`tsc && vite build`）**EXIT=0**（4188 modules / 12.34s）、
  `tsc --noEmit --noUnusedLocals --noUnusedParameters` **清零**；**未做浏览器级验证**（需同时起主系统与内网 AI 服务），
  故 `/ai` 与悬浮窗目前只有"编译 + 契约"层面的保证。

**② 主系统后端：代理层 + 迁移 V13**
- **V13**（`db/migration-yashan/V13__ai_integration.sql`）：`attachment_ai_task`（谁触发/进度/doc_id/失败原因，
  冗余 `project_id`+`filename` 便于按项目过滤与附件删除后追溯）、`attachment` 增 `ai_index_status`/`ai_doc_id`/`ai_indexed_at`
  （**全可空、无默认值 → 存量数据零影响**，NULL 由 `Attachment.getAiIndexStatus()` 统一归一为 `NOT_PARSED`）、
  `ai_ask_log`（审计：谁/何时/问什么/作用域 requested 与**实际 docIds**/引用数/耗时/degraded/notice/答案摘要）。
- 新增 `backend/src/main/java/com/pmgt/module/ai/`：`AiServiceClient`（唯一知道 AI 原始契约的适配层，问答 60s / 其它 10s 双超时）、
  `AiScopeResolver`（**权限与作用域在主系统收口**：把前端给的附件/项目校验并换算成 `docIds`，越权明确拒绝）、
  `AiAnswerAdapter`（纯函数适配 §9 契约，含 `citations` 回填 `attachmentId`）、`AiChatService`（问答 + 留痕）、
  `AiDocumentService`（文档库：`keyword` 同时匹配文件名与 docId）、`AiTaskService`（触发/重试/列表/状态回写）、
  `AiTaskRecoveryRunner`（启动把残留的排队/解析中任务标失败，避免前端永远转圈）、`AiHealthService`（永远给结论不抛异常）。
- 边界与取舍：`/chat` 在 AI 服务不可用时**严格报 503 且不降级成"未找到"**；任务/文档**读接口**在 AI 不可用时用主系统
  自己的记录作答（写动作仍报错）；`citations` 缺失一律空数组，**绝不从 `trace.brief` 抠页码伪造引用**。
- **验证**：`mvn -o test` **66 例全绿**（自动触发解析补完后：触发器 8 例 + 上传链路集成 6 例 + 服务层 2 例 + 原 50 例），
  全部 mock/stub，不依赖真实 AI 服务；其中"**AI 服务不可用 → 上传仍然 SUCCESS**（status / attachmentId / progress 三项断言、
  且上传结果不被改写）"与"开关关闭时连事件都不发"各自单独成例。
- **自动触发解析的取舍（值得记住）**：① **失败置 `FAILED` 而非 `NOT_PARSED`**——解析确实尝试过且原因可读；
  置 `NOT_PARSED` 会被界面显示成"未解析"，默认判断是"还没轮到"，于是没人去查为什么没解析成，
  而"AI 服务挂了"恰恰最需要被立刻看到；上传本身仍是 SUCCESS。② **三层失败隔离**：监听者 catch → 事件单向 →
  发布方再包一层 try（防"监听者抛异常被 `store()` 的 catch 误写成上传失败"）。③ **不复用上传线程池**：
  只把工作转交给独立的单线程执行器（有界队列 + CallerRunsPolicy），避免一次大文件解析挤掉后续上传的吞吐。
  ④ 顺带修掉一个真问题——**后台线程没有 `AuthContext`**，自动解析的留痕会记成"操作人为空"，
  故给 `OperationLogService` 加"显式传操作人"的重载（原 4 参方法行为不变，改为读登录态后转调）。
- **演示路径（设计稿 §14.6）**：`node scripts/seed-attachments.mjs` 上传即自动解析 → `/ai` 看文档库与任务队列 →
  悬浮窗提问 → 点引用跳附件第 N 页；**边界场景**用开关演示：停掉 `ai-backend`（或 `AI_AUTO_PARSE=false`）后，
  附件显示"解析失败（AI 服务不可用）+ 重试"而**上传依旧成功**，恢复服务后点重试即入库。

**③ AI 能力服务：结构化引用（`ai-1.1.0`，本轮的前置条件）**
- 前端"引用可点击跳原文"要求 `/chat` 返回**结构化出处**，而原实现只有人读摘要 —— 该改造**独立成一行迭代**记录，
  见下方明细段 `### ai-1.1.0 — /chat 结构化引用 + 请求字段名对齐`。
  **依赖关系**：`v3.6.0` 的引用功能依赖 `ai-1.1.0` —— 若部署的 AI 服务还没有 `citations` 字段，
  前端会拿到**空引用列表（不报错、不伪造）**，页签与自检一切正常。

**④ 工具集评估（回答"平台四件套就位后还要不要自写工具"）**
- 结论：**3 个工具一个都不能删**——平台给的是"无状态能力"（文本→向量/生成），工具解决的是"有状态编排 + 本地数据"
  （语料从哪来、切片怎么存、召回怎么融合、精确算术谁算、审计怎么留痕），**平台没有"语料库"这个概念**。
  但要重排分工：检索默认改**服务端预取**（工具保留用于追问）、诊断字段只落日志不下发模型、未来 4 个业务查询 RPC
  在模型侧**合并成 1 个** `query_business_data(entity, filters)`。明确**不引入** LangChain4j/Spring AI
  （会把"检索了几次、检索到什么"藏进框架日志，与审计要求的可复现冲突）。对照企业成熟方案（Dify/RAGFlow 的
  "检索是流程节点而非模型工具"、Anthropic 工具设计指南的"少而整合/高信号返回/错误要教会模型"）逐条落地。

**未做 / 边界（不美化）**：① **V13 未在真实崖山库执行**——将在下次 `pm-backend` 启动/部署时由 `YashanMigrationRunner`
应用（`ALTER TABLE … ADD (…)` 与 V1~V12 已成功应用的 V8/V10/V11 写法一致，但**方言未实测**）；
② `GET /api/ai/ask-logs`（#10）属 P1 未做（表与写入已就绪）；③ 前端未做浏览器级验证；
④ 范围选择器只拉前 500 个项目；无"全部重解析"入口；⑤ 分页是"取全量再切片"、`chunkCount` 是估算值；
⑥ `make-release.ps1` 的 gzip 产物**未经 `docker load` 实机验证**（本机 Docker 引擎未启动、无 gzip.exe）。

---

### ai-1.1.0 — /chat 结构化引用 + 请求字段名对齐（AI 服务，2026-09-20）

- **为什么必须做（评估时发现的真缺口）**：`/chat` 原先只返回 `{answer,trace,scope,llm,stopped_reason,error}`，
  **页码/片段/分数全被丢掉**——`trace.brief` 只是人读摘要，前端"引用可点击跳原文第 N 页"根本做不了。
  这也正好印证了工具集评估的结论：**"文档在第几页"这层语义平台不会替我们做**（见 [`docs/AI工具集与检索编排评估.md`](docs/AI工具集与检索编排评估.md)）。
- **引用编号怎么保证稳定**：新增请求级 `CitationRegistry`，`search_documents` 的每条命中与 `read_page` 取回的页
  都登记；**编号在模型看到命中时就已经带上**（`cite` 字段），故模型写出的 `[1][3]` 与响应里的 `citations` 一一对应，
  不会因为后续再检索而错位。去重键 `doc_id + page_no`，编号按首次命中顺序分配、**只增不改**；同一页再次命中只刷新分数/片段。
  注册表**按参数传递**（`ToolAgent`/`Tools` 是单例，绝不能用 ThreadLocal/静态态——否则并发问答会串号）。
  `/chat` 追加 `citations:[{index,doc_id,filename,page_no,snippet,score}]`：既有键的顺序与语义**一字未动**，
  键顺序**不作契约**（JSON 对象无序）；提示词同步要求句末按 `[cite]` 标注。
- **顺带修掉一处"静默丢参"的真 bug**：老契约写 `doc_ids`，而 Java 字段实为 `docIds`（服务没有配 SNAKE_CASE），
  于是**按文档发 `doc_ids` 会被静默忽略、检索范围退化成"答全库"**——不报错的那种最危险。
  已改为 `@JsonProperty("doc_ids") + @JsonAlias("docIds")` 两种都收，注释改为事实描述，并把这条坑写进
  `ai-backend/docs/迁移方案与对照表.md` 的契约表。同时清掉提示词里仍在推荐**已删除工具** `list_documents` 的旧文案。
- **验证**：`mvn -o test` **94 例全绿**（既有 81 例未改一字 + 新增 13 例：编号稳定性/去重/无命中为空/两种字段名反序列化）；
  **真实内网网关 e2e**：`doc_ids`（5.24s）与 `docIds`（3.50s）两种写法都真的被解析（用不存在的 docId 反证：
  若被忽略会退化成答全库，实测返回"没有可用文档"、`citations=[]`、不调模型），答案带 `[1]` 角标，
  `citations` 的 `page_no` 与 snippet 与原文**逐字核对一致**。
  `ai-backend/scripts/verify-e2e.ps1` 一并修掉"字段搬家后仍读 `data.rounds`（静默取到空值）"这个既有缺陷，
  并**新增 citations 断言**（`index` 从 1 连续、`page_no ≥ 1`、snippet 非空；失败逐条列问题 + 打印原始返回 + 非 0 退出），
  整脚本真跑 **EXIT=0**：`rounds=2 / tokens=4285+382 / 9.24s / 引用 1 条（P1, score=0.2608）`；
  该脚本原本是 no-BOM + LF，与仓库约定不符（PS 5.1 会按 ANSI 解码导致中文乱码），已转为 UTF-8 带 BOM + CRLF。
- **遗留（重要）**：**AI 服务当前没有任何入站鉴权**（无 Filter/Interceptor、无 Spring Security 依赖）。
  主系统已按 `Authorization: Bearer <pm.ai.token>` 发送，但**对方不校验**，token 为空时连头都不发；
  安全性目前**完全依赖网络隔离**。这是独立待办（登记于 `AiProperties` 配置注释与本文件 Backlog），
  **不在主系统侧"单方面假装修好"**。

---

### docs-1.2 — 开发机脚本 PowerShell 化 + 文档收口（2026-09-20）

- **`scripts/*.sh` → Windows 原生 `.ps1`**（`docs-1.0` 遗留项 ①，本机**无 Git Bash** 一直跑不了）：
  `make-release.ps1` / `dev-reload.ps1` / `db-sql.ps1`，**删除对应 `.sh`**（避免两套漂移）；
  `pm-upgrade.sh` 保持 `.sh`（它只在 Linux 服务器上跑）；`ai-backend/scripts/check.sh` 保留（Linux/CI 质量门）。
- **必须偏离原脚本的地方**（都是本机事实逼出来的，已写进脚本头部注释）：① 用 .NET `GZipStream` 取代
  `docker save | gzip`（本机**没有 gzip.exe**，且 PowerShell 管道会把二进制当文本解码）；② `java -D…` 参数**必须加引号**
  （裸写在 `.` 处被拆成 `-Dfile` + `.encoding=UTF-8`，实测报"找不到主类"）；③ PS 5.1 在 `$ErrorActionPreference='Stop'`
  下对原生命令做 `2>$null` 会抛**终止性**错误（照抄会让 db-sql 在容器未起时直接崩、走不到 `.env` 兜底），
  故加 `Invoke-NativeQuiet` 帮助函数；④ 健康检查用 `curl.exe`（PS 5.1 里 `curl` 是 `Invoke-WebRequest` 别名）。
- **编码决策（重要，别再"顺手"改）**：三个 `.ps1` 一律 **UTF-8 带 BOM + CRLF**。实测 PS 5.1 对**无 BOM** 的 UTF-8
  脚本按 ANSI(GBK) 解析，中文字面量**在解析期就坏掉**（`中文：已就绪` → `涓枃锛氬凡灏辩华`）；
  即"无 BOM + 中文 + 5.1 可跑"三者不可兼得。该结论已写入 `scripts/README.md` §0.2 约定。
- **验证**：三个脚本 × (PS 5.1 + PS 7) 解析校验 **6/6 通过**；`db-sql.ps1` **真实执行**（只读 `SELECT COUNT(*) FROM project`
  → 11 行，第 3 档连接来源 `deploy/docker/.env`，口令只打印长度、不打印不落盘，退出码 0）。
  **`make-release.ps1` / `dev-reload.ps1` 未实际执行**（本机 Docker 引擎未启动）：只做了逐段人工比对 + 隔离探针
  （GZipStream 往返字节一致、正则替换含 CR 行为、switch 作用域、here-string 插值等，两壳全过）。
- **文档收口**：`scripts/README.md`（§0.1 `local/` 行、§0.2 整节重写含 BOM 约定、§1 场景速查 + 用法、§2、§5 约定）、
  `README.md`、`deploy/README.md`、`docs/部署与发布全流程手册.md`（L86 只认 PowerShell、**§2.3「手工打包」整段重写**为
  "等价说明、不可照抄"的 PowerShell 步骤、`| gzip` 与 `ls -lh` 清零）、`scripts/jdbc/RunSql.java` 与 `scripts/demo-reset.sql`
  注释；本文件 4 处失效的"当前用法"（运行方式速查代码块、功能完成度两行脚本名）已改，历史明细只加注不改写。
- **新增/登记**：`docs/AI工具集与检索编排评估.md`（已登记进 `docs/README.md`，并在 AI 侧 README 加"动工具/检索编排前必读"）；
  `docs/AI前端与集成方案.md` 增 §9 P0 接口契约（冻结）与 §10 前端工程规范，并**修正一处自相矛盾的旧结论**
  （原文写"nginx 加 `location /ai-api/` 让前端调它"——那会让浏览器**绕过主系统的权限解析**直接够到 AI 服务，
  与"权限在主系统收口"冲突；已改为"前端只调主系统 `/api/ai/*`，8100 仅内网可达"）。
- **收尾**：`node scripts/check-docs.mjs` → **19 份 Markdown / 0 问题**、ITERATION 总表与明细行数一致。
  （本轮该体检真抓到 2 处我自己引入的问题：新文档表格里一行单元格列数不符、`迁移方案与对照表` 契约行键顺序歧义，均已修。）

---

### docs-1.3 — 专项论证：要不要引入 LangChain4j（文档处理 / 会话记录）（2026-09-20）

- **问题**："接入 LangChain4j 是不是更好？文档处理、消息记录都能更好处理。"
- **结论：不引入**，但不是"框架不好"，而是**它擅长的那部分我们没有痛点，我们有痛点的部分它帮不上，且它的默认语义与审计要求冲突**。
  完整对照表见 [`docs/AI工具集与检索编排评估.md`](docs/AI工具集与检索编排评估.md) §7。
- **文档处理（逐环节）**：解析是**通用 Tika/PDFBox 路径**，拿不到"文本层优先 vs 平台 OCR 兜底"的判定与页级失败清单，
  等于用**未验证行为替换已验证行为**（我们与 PyMuPDF 是逐字节对齐：182/182、空页 0/0）；
  切片换默认切分**必须重测召回**，而我们还没有评测集；检索层 `OpenSearchEmbeddingStore` 官方文档只写
  **exact kNN + metadata filter**，**混合检索（BM25+kNN）目前仍是社区 PR 状态**——用它要么丢掉我们已实测的
  "混合召回 → Reranker 精排"编排（0.9903 > 0.0278 > 0.0014），要么"用它的 store + 自己再写一遍混合与重排"= **更复杂**。
- **会话记录（三条硬伤，官方文档自证）**：① **只有 memory、没有 history**（官方原话：*"If you need to keep an entire history,
  please do so manually"*），而我们要的正是**审计级完整历史**；② **被淘汰的消息会从 `ChatMemoryStore` 一并删除**
  （官方 note），窗口一滑库里也没了，与"留痕不得因上下文窗口被丢弃"直接冲突；
  ③ **它把会话状态放进 AI 服务**，破坏"主系统是唯一事实源、AI 服务无状态（可重建/可重放/可水平扩）"这条已写进设计稿的边界。
  → P2 会话管理应**在主系统**建会话/消息表（与 `ai_ask_log` 同一条数据），而不是把 memory 交给 AI 服务。
- **量化重估触发条件**（任一出现再评估）：第二家模型/网关；工具数 >8 或需并行工具调用/子代理；
  需要接 MCP 生态；文档格式需求超出"PDF + 扫描件"；团队明确愿意用可复现性换开发速度。
- **将来要接也不返工**：三条接缝 `SearchPort` / `DocumentReader` / `Tools`（替换只发生在它们后面）；
  三条底线不交出去：平台 OCR 路由与页级校验、引用编号与答案出处（`citations`）、主系统权限/口径/留痕。
- **真正的短板是评测集，不是框架**：先做 **10~20 条迷你评测集**（跨文档对比 / 金额求和 / 查不到要说未找到，人工标注答案与页码），
  再做**限时 spike**（独立分支，LangChain4j 重写 `/chat` 最小版），同文档同问题对比五项
  （代码行数 / 延迟 / **引用页码准确率** / 依赖体积与传递依赖数 / **审计明细是否还拿得到**），**用数字决定**。
- **记录位置**：`docs/AI工具集与检索编排评估.md` §7（新增）+ §5（原"不做"改为指向 §7）；本文档 Backlog 同步。

---

### v3.6.1 — 部署配置补全：把 AI 接入参数真正贯通（2026-09-20）

- **为什么必须补（这是"部署完 AI 却用不了"的根因）**：v3.6.0 把 AI 功能做完了，但**部署资产没把参数接进去**——
  `deploy/docker/docker-compose.yml` 与 `docker-compose.deploy.yml` 的 backend 环境里一个 `AI_*` 都没有，
  于是容器内 `AI_SERVICE_BASE_URL` 取应用默认值 `http://127.0.0.1:8100`，**在容器里 127.0.0.1 就是后端自己** → 必然连不上。
  更糟的是 `pm.ai.auto-parse` 应用内默认 `true`：每次上传都会去连一次、失败并记"解析失败"，日志与界面持续报错。
- **改了什么**：
  1. **两个 compose 的 backend 增 6 个变量**（`AI_ENABLED` / `AI_SERVICE_BASE_URL` / `AI_SERVICE_TOKEN` / `AI_AUTO_PARSE` /
     `AI_CHAT_TIMEOUT` / `AI_TIMEOUT`）+ `extra_hosts: ["host.docker.internal:host-gateway"]`
     —— Linux 上容器要访问**宿主机**必须做这个映射（Docker Desktop 自带，Linux 没有）。
  2. **`.env.example` 新增 AI 段**：逐项中文说明；点明 **"绝不能填 127.0.0.1"**、"另一台机器填其内网 IP"、
     **首次部署建议 `AI_AUTO_PARSE=false`**（先在自检页确认可达再开）；并写明"双机负载均衡时 AI 应集中一台"。
  3. **新增 `ai-backend/docker-compose.deploy.yml`**：AI 服务的**服务器专用**编排（无 `build:`、`pull_policy: never`、
     `AI_BIND_IP` 可绑内网 IP、`work` 卷持久化、healthcheck、日志限额）——与主系统同一套"离线只 load"路数，
     但**仍是独立发版**（主系统发布包与 compose 依旧不含 AI 镜像）。
  4. **文档同步**：`deploy/README.md` 新增 §1.1（三个变量表 + 推荐部署顺序 + 两条边界：
     无入站鉴权、双机索引会各自演化）并修过期数字（`V1~V9` → `V13`、**11 张表 → 16 张**、补"迁移无回滚、升级前先备份"）；
     部署手册 §3 的 `.env` 清单加 AI 段、§8.1 补**构建平台必须与目标机一致**（ARM 服务器上跑 amd64 镜像会 `exec format error`）、
     §8.3 新增"服务器无源码部署 AI 服务"三步 + 主系统接入 + 双机提醒、§5 验收加 **A7（自检页）/A8（提问并点引用跳页）**、
     质量门 81 → **94 用例**；`崖山数据库与迁移约定.md` 与根 `README.md` 的 `V1~V12` → `V1~V13`。
- **验证（可复现）**：四个编排文件 `docker compose config --quiet` **全部 exit 0**；
  `docker compose config` 解出的 backend 环境里确实出现
  `AI_ENABLED: "true"`、`AI_SERVICE_BASE_URL: http://host.docker.internal:8100`、`AI_AUTO_PARSE: "false"`、
  `extra_hosts: [host.docker.internal=host-gateway]` —— 即"配置真的落到了容器上"，而不只是写在文件里。
- **仍未验证（如实登记）**：① 没有真实服务器，`scp` / `docker load` / **容器到 AI 服务的连通性**都还没实测；
  ② AI 服务**没有任何入站鉴权**，故新增编排里用 `AI_BIND_IP` 绑定内网 IP，并要求防火墙只放行主系统服务器；
  ③ V13 仍未在真实崖山库执行（下次启动/部署时应用）。
- **追加（同一轮，随后一次提交）**：把"**第一次跑 `.ps1` 必踩的执行策略**"写清并实测。现象
  `无法加载文件 …\make-release.ps1，因为在此系统上禁止运行脚本`（`PSSecurityException`）；
  实测根因：本机 **5 个作用域全为 `Undefined`**，Windows 客户端此时**默认即 `Restricted`**（不是脚本问题，也不是 BOM/编码问题）。
  已在 `scripts/README.md` §0.2 给出两种修法并注明取舍：① **一次性持久**（推荐）`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`
  —— 仓库脚本**无 Zone.Identifier 网络锁定标记**，实测 `RemoteSigned` 下可正常加载（PS 5.1 与 7 均验）；
  ② **免副作用** `powershell -ExecutionPolicy Bypass -File scripts\make-release.ps1 <版本>`（不改机器策略，每次带上）。
- **追加 2（同一轮，随后一次提交）**：修掉 `make-release.ps1` 第 3 步的真实 bug —— 从"**主目录启动的 PowerShell 里 `cd` 进仓库**"调用时，
  打包在 GZip 那一步抛 `未能找到路径 C:\Users\<你>\dist\… 的一部分`。根因是 PowerShell 有**两套当前目录**：
  `$PWD`（`Set-Location` 改它；cmdlet 与外部程序按它解析）与 .NET 的 `[System.IO.Directory]::GetCurrentDirectory()`
  （`[System.IO.File]::Create` 等按它解析，**`Set-Location` 不同步**）。于是 `New-Item` 按 `$PWD` 在仓库下建好了目录，
  而写 tar.gz 的 .NET 调用去了用户主目录。修法：脚本开头锚定仓库根并**同步 .NET 当前目录**
  （`Set-Location` + `[System.IO.Directory]::SetCurrentDirectory`），顺带让脚本**可以从任何目录调用**（不再要求先 `cd` 到仓库根）；
  该约定已写入 `scripts/README.md` §0.2 供后续脚本照做。
  **实测验证**：故意从 `C:\`（仓库外）调用 → 全部命中构建缓存，**14.2 秒**产出
  `dist/pm-release-v3.6.1/`（镜像包 **150.9 MB** + compose + `.env.example` + `pm-upgrade.sh` + 服务器部署步骤），退出码 0。
- **顺带记下实测构建耗时（回答"要不要优化速度"）**：首次真构建 backend **451s**（其中 `RUN mvn package` **222s**，
  其余为拉 arm64 基础镜像，**一次性**）、frontend **240s**（其中 `RUN npm run build` **218s**）。
  即"改代码后重建两端"≈ **7.3 分钟/次**；而两端都没改时（本例）**14.2 秒**。慢的两个原因已定位：
  ① 两个 Dockerfile 的构建阶段**没钉 `--platform=$BUILDPLATFORM`**，导致 `mvn`/`npm` 也在 QEMU 模拟里跑；
  ② Maven 依赖下载发生在 `RUN mvn package` 这一层内，**没有 BuildKit cache mount**，源码一变这层就重建 → 依赖每轮重下。
  **优化方案已给出、待批准后再改**（改动会触发一次真构建，用于对比数字）。

---

### v3.6.2 — 构建提速 + AI 服务发版链路对齐（2026-09-23）

- **需求**：① 打包太慢（实测 `mvn package` 222s、`npm run build` 218s，"改一行代码要等 7 分钟"）；
  ② AI 服务没有与主系统对等的"本地开发 → 构建 → 打包 → 服务器部署"链路（此前只有手册里的手工 docker 命令）。
- **提速做了什么**（三个 Dockerfile：主系统后端 / 前端 / AI）：
  1. **编译阶段钉 `--platform=$BUILDPLATFORM`**：`mvn` / `npm` 跑在**构建机原生架构**，
     只让最终镜像层是目标架构。后端产物是纯 Java jar、前端是静态文件，与 CPU 架构无关，所以安全。
     不改的话，`--platform linux/arm64` 时编译全程在 **QEMU 模拟**里跑。
  2. **BuildKit cache mount 复用依赖**：`--mount=type=cache,target=/root/.m2/repository` 与 `/root/.npm`。
     依赖下载原本发生在 `RUN mvn package` 这一层内部，源码一改这层就重建 → 每轮重新下载。
     ⚠️ 挂的是 **`repository` 子目录**：挂 `/root/.m2` 会把镜像加速用的 `settings.xml` 一起遮掉（加速失效）。
  3. 要求 **BuildKit**（Docker 23+ / Desktop 默认开）；已验证 `docker compose build` 路径同样支持 cache mount
     （`dev-reload.ps1` 走的是那条，不是 buildx CLI）。
- **实测数字**（同一台机器、真构建）：
  | 步骤 | 优化前 | 优化后 |
  | --- | --- | --- |
  | 后端 `RUN mvn package` | 222s | **47.3s**（首次，含填充缓存）/ **24.3s**（改代码后稳态） |
  | 前端 `RUN npm run build` | 218s | **103s** |
  | AI 镜像首次构建 | —（同款写法，未测） | **44s** |
  | 全缓存重打包（save+gzip） | — | 14.2s；主系统整包流程 72.9s |
  → **日常"改后端代码 → 出发布包"从 ~7 分钟降到 ~40 秒级**；两端都改约 2 分钟。
- **风险点专门验证（这类优化最容易在这里翻车）**：最终镜像架构**仍是 `arm64/linux`**
  （`pm-backend` / `pm-frontend` / `pm-ai-backend` 三个都查了）——换构建阶段没有漏进最终镜像，服务器不会 `exec format error`。
- **AI 服务四链路对齐**：① 源码直跑 `cd ai-backend && mvn spring-boot:run`；② 容器重建 `.\scripts\dev-reload.ps1 -Project ai`；
  ③ 出发布包 `.\scripts\make-release.ps1 <版本> -Project ai` → `dist/pm-ai-release-<版本>/`；④ 服务器 load + `up -d` +
  `/health?with_ocr=true&with_vec=true` 自检。**AI 与主系统仍各自独立发版**（互不包含，这是刻意的）。
- **主系统"只重建改动那一端"**：`-Only backend|frontend [-ReuseTag <旧版本>]`——未改的一端用 `docker tag` 复用现有镜像，
  **打印实际复用的来源 tag**（可追溯）；额外两道保护：复用镜像做**架构校验**（防混架构包）、来源校验放在**构建之前**（写错立刻退出，不白等）。
- **闭环一个挂了两次的历史遗留**：主系统包与 AI 包的 `docker save` → **`docker load` 实机验证通过**
  （`Loaded image: pm-backend/pm-frontend/pm-ai-backend`）——`GZipStream` 产物终于被证明是 `docker load` 能吃的。
- **脚本工程的验证**（子代理完成，我复核）：AST 解析 PS 5.1 + 7 均 0 错误；参数干跑 16 例 × 两壳；
  **用自编译的 docker 桩**（放 PATH 最前，不碰真实 Docker）跑通 11 个场景 × 两壳（BAD=0），覆盖复用来源挑选、
  日期优先于小数秒、无候选/来源不存在/架构不匹配的拒绝路径、AI 全流程产出与 `.env.example` 追加 `AI_IMAGE_TAG`。
  我另外独立复核了 4 个 `.ps1` 的 BOM、孤立 LF 与 PS 5.1 解析。
- **未验证 / 边界**：真实服务器上的 `scp`→`load`→`compose up` 与"容器到 AI 服务"的连通性仍未实测；
  AI 服务**无入站鉴权**（已用 `AI_BIND_IP` + 防火墙建议兜住）；V13 仍未在真实崖山库执行。
  已知遗留（属原有行为、未改）：主系统 `.env.example` 的 `IMAGE_TAG` 行会被正则吃掉 CRLF 的 `\r`（Linux 下无影响）。

---

### v3.6.3 — 服务器"一个根 + 两个隔离项目 + 一个统一入口"（2026-09-23）

- **起因：现状评估的 6 条问题**：① 服务器目录假设过时（主系统 `pm/app` + `pm/releases`，AI 又写在 `pm-ai/…`，
  两个项目散在两棵树里）；② `pm-upgrade.sh` **只认主系统**（只切 `IMAGE_TAG`、只认 `pm-backend`，对 AI 包完全不认），
  且 app 目录探测写死了几个旧路径；③ 没有统一启停（三个服务分属两个 compose 工程）；④ 本地一键启动不含 AI；
  ⑤ 两套发布包内容不一致（主系统带脚本、AI 包啥都没有）；⑥ 路径散落在手册/deploy README/AI README/ITERATION 多处。
- **新布局（唯一事实）**：
  ```
  /home/lhim/pm/
  ├─ pm.sh        ← 唯一入口
  ├─ main/        docker-compose.yml + .env + .env.example + 服务器部署步骤.txt + releases/pm-images-aarch64-<版本>.tar.gz
  └─ ai/          docker-compose.yml + .env + .env.example + 服务器部署步骤-ai.txt + releases/pm-ai-images-aarch64-<版本>.tar.gz
  ```
  **发布包按同一树形产出** → `scp -r dist/<包>/* lhim@<服务器>:/home/lhim/pm/` 一次到位、落地即符合布局
  （两套包都带同一份 `pm.sh`，重复上传无害；主系统包不含 `ai/`、AI 包不含 `main/`，互不覆盖）。
- **新 `scripts/pm.sh`（716 行，取代并删除 `pm-upgrade.sh`）**：
  - 命令：`start|stop|restart [main|ai|all]`、`status`、`logs <main|ai> [服务]`、`upgrade <main|ai> <镜像包>`、`help`；
    另兼容旧习惯 `pm.sh <镜像包>` = `upgrade main <包>`（但**包名是 AI 包时明确拒绝**，避免把 AI 包 load 进主系统白重启）。
  - `start` **先起 ai 再起 main**（主系统的 AI 功能依赖它，少一次"AI 服务不可用"），`stop` **反序**；`stop` 幂等（没容器不报错）。
  - `upgrade` 自动：`docker load` → 按包名解析版本（`VER=` 可覆盖）→ 校验镜像真在 → 备份并切换对应 `.env` 的
    `IMAGE_TAG`（main）/ `AI_IMAGE_TAG`（ai）→ `up -d` → 健康检查。**这才叫"更新脚本会更新 env 文件"**。
  - **main 的健康检查比 HTTP 200 更严**：`/api/health` 在数据库不通时**仍返回 200**（body 是 `db:"down…"`），
    只看状态码会把"库不通"报成"可用"，故要求 body 含 `"db":"up"`。副作用：DB 故障时 `start main` 退出码 1（正确信号）。
  - **工程名 = 目录名**（不传 `-p`）：pm.sh、手工 `cd main && docker compose …` 操作的是**同一个工程**，不会各建一套容器。
- **`make-release.ps1` 同步改造**：两套包按新树形产出 + 复制 `pm.sh`（缺文件时 `[WARN]` 而非静默出一个没有入口的包）
  + 两份服务器步骤文本重写 + 收尾改为递归打印整棵树。
  **顺手修掉一个真 bug**：步骤文本里 `${WEB_PORT:-8080}` / `${AI_PORT:-8100}` 在双引号 here-string 里会被 PowerShell
  当变量展开成**空串**（文本变成 `http://127.0.0.1:/health`）→ 已用反引号转义并做字节级确认。
- **本地一键启动含 AI**：`start-dev.cmd` 增加第 4 步——AI 已在跑则提示；`ai-backend/.env` 存在则拉起到 8100 并等待，
  **失败只 `[WARN]` 不拖垮主系统**（AI 是独立服务，不该让它挡住主系统启动），缺 `.env` 给"怎么建"的提示；
  `stop-dev.cmd` 的端口清单加 8100。
- **模板与编排**：`ai-backend/.env.example` 补 `AI_IMAGE_TAG`（出包自动预填）/`AI_BIND_IP`（当前无入站鉴权，必须绑内网）/
  `AI_WORK_DIR`；`ai-backend/docker-compose.deploy.yml` **去掉写死的 `name: pm-ai`**——否则"工程名=目录名"对 AI 不成立，
  手册 §0.4 的迁移说明就不准。
- **文档**：手册新增 **§0.4「⚠️ 从旧布局迁移」**（工程名一变就撞固定容器名 → `container name is already in use`；
  **必须把旧 `.env` 搬过来**（现场口令/密钥不在发布包里）；**别用 `cp -r` 把旧目录整体覆盖到新目录**（旧编排会盖掉新编排）），
  并全篇把路径与命令改成 `main/ai + pm.sh`；`deploy/README`、`ai-backend/README`、根 `README` 同步；
  `docs/AI前端与集成方案.md` 里"`pm-upgrade.sh` 保持 `.sh`"的旧结论加注"已被 `pm.sh` 取代"。
- **验证**：
  - `pm.sh`：本机**没有可用的 bash**（`bash` 只是 WSL 空壳，默认发行版 docker-desktop 里没有 `/bin/bash`），
    故用容器里的 Ubuntu bash 校验：`bash -n` **exit 0**；参数分支逐条实测（无参数=1、`help`=0、未知子命令=2、
    `upgrade` 缺参=2、`logs` 缺工程=2、`status`=0 且缺目录时 WARN 而非报错）。
  - **不重新构建**（`SKIP_BUILD=1`，按要求）重打两个包并逐项核对：两包 `pm.sh` 与 `scripts/pm.sh` **逐字节一致**、
    `main/docker-compose.yml` 与 `deploy/…/docker-compose.deploy.yml` 一致、`ai/docker-compose.yml` 与 `ai-backend/…` 一致、
    `AI_IMAGE_TAG` 已就地预填为 `v1.0.0` 且模板无 BOM；两个 `.tar.gz` 用 `GZipStream` 解压到底（CRC 通过，tar 头 `ustar` 正常）。
  - `check-docs`：19 份 / 0 问题；ITERATION 总表与明细行数一致。
- **未验证（如实登记）**：真实服务器上的 `scp -r` → `bash pm.sh start|status|upgrade` 闭环、`docker load` 这两个包、
  `/api/health`（含 `db:"up"` 判定）与 `/health?with_ocr=false` 的真实时序、旧布局迁移实操（含**附件本地卷名会随工程名变**：
  `app_pm_uploads` → `main_pm_uploads`，走 OBS 时该卷只是过场不受影响，用 local 存储则需按步骤文本处理）、
  `--env-file` 在服务器 compose 版本上的兼容性。主系统包本轮走 `SKIP_BUILD=1`，未再验证其 buildx 构建链路。

---

### v3.6.4 — 部署流程"一页纸"重排 + 容器名冲突的现场提示（2026-09-23）

- **背景（真机反馈 + 真机报错）**：运维按手册在服务器上操作时① 反馈"`pm.sh` 与文档不一致、**文档太乱、没有一个明确的流程、太多解释性内容、流程分散**"；
  ② 实跑 `bash pm.sh upgrade main …` 撞上 **`Conflict. The container name "/pm-backend" is already in use`**——
  根因是"**`docker stop` 不解开容器名占用**"（容器还在，只是停了）+ **工程名随目录名从 `app` 变成 `main`**，而编排里的 `container_name` 是写死的。
- **文档重排（核心）**：手册 **§0 整段重写为"照抄即可"的线性清单**，结构固定为：
  `0.1 主系统（出包→上传→配 .env→启动→验收→升第二台）` / `0.2 AI 服务（同 5 步 + 让主系统连上它）` /
  `0.3 日常操作` / `0.4 旧布局迁移（4 条命令 + 3 条注意）` / `0.5 最常见 3 个报错 → 一条命令` / `0.6 速查（目录与背景）`。
  **命令块可直接复制**，解释性内容一律下移到 §0.6 或"要为什么再往后翻"；原 §0.1~§0.3 的背景表与目录树压缩进 §0.6（**目录树只保留一份**），
  **实测数字与踩坑结论一条没丢**（构建 24s/103s、镜像包 ~150MB、YAS-02143/02193、token 401、附件 404 等仍在 §2.2/§3/§6/§8）。
  `deploy/README.md` §1 与手册 §0 改为**逐行同一套命令**（两处不会再互相矛盾）。新增两条易错点：
  - **滚动升级显式成步**：先升一台 → 验收 → 再升第二台（避免两台同时不可用）；
  - **AI 自检口径**：`with_ocr=false` 只是探活（HTTP 200 = 进程活着），完整自检要 `with_ocr=true&with_vec=true` + 界面「AI 与知识库 → 服务自检」四项全绿。
  另修掉重排后 §2.2/§2.4/§8.4 共 **4 处过期交叉引用**（`§0.3`/`§0.1` → `§0.6`）。
- **脚本侧（针对该报错）**：`pm.sh` 的 `upgrade` 失败分支原来只给一句"排查：`bash pm.sh logs main`"，
  现在**直接把补救命令打出来**（`docker rm -f pm-backend pm-frontend pm-ai-backend`，并说明"`stop` 不解开占用、必须 `rm`"，
  以及"`APP_STORAGE_TYPE=local` 时附件卷名随之变化（`app_pm_uploads` → `main_pm_uploads`）"的提醒）。
- **验证**：`pm.sh` 改动后容器内 `bash -n` 通过、补丁段逐行核对（我中途插过一段多余占位代码，已删除）；
  `check-docs` 19 份 Markdown / 0 问题；ITERATION 总表与明细行数一致。
- **未验证**：真机上的完整闭环（运维正在跑：`docker rm -f` → `bash pm.sh start main`）；文档流程的"照抄一次成功率"要等真机走完才算数。

### v3.6.6 — 生产 500 的根因修复（`reactive-streams`）+ 深度自检 + OBS 可诊断性（2026-09-23）

- **故障**：服务器手动点「附件解析」报 500「系统繁忙」；自动解析也从不产生任务（`attachment_ai_task` 0 行）。
- **根因（真机日志确认）**：`Handler dispatch failed: java.lang.NoClassDefFoundError: org/reactivestreams/Publisher`。
  Spring 6.1 `RestClient` 走 multipart 时，`MultipartBodyBuilder` 常量池引用 `Publisher`；项目只有 `spring-boot-starter-web`
  （无 webflux/reactor），无依赖把它带进来 → 运行期链接失败。`/chat` 问答同一条路径，同样会 500。
- **为什么 66 个单测全绿却线上炸（本仓库最值得记住的一条）**：不是"mock 掩盖"那么简单——
  `AiServiceClientTest` **从来没有覆盖 `submitParse`（multipart）这条路径**。修法不只是加依赖，而是补**真 socket 回归测试**
  （JDK `com.sun.net.httpserver.HttpServer` + 真 `RestClient`，5 例：multipart / GET / JSON POST / 类加载守卫 / 状态码），
  并**先验证"修依赖前 3 条红"**再让它变绿。适用于所有"只有真发请求才会被链接到"的缺陷。
- **顺带两件**：① 服务自检页三个模型改**三态**（可用/不可用/**未探测**）+ **「深度自检」按钮**（`?deep=true` 真探对话/向量化/重排；
  深探走 60s 长超时 client、前端放宽 120s；未探测时给引导提示）；② OBS 缺对象时 WARN 打出 **bucket + 完整 key + file_path + 核对提示**。
- **出包**：`dist/pm-release-v3.6.6/`（后端镜像 16:16 已含全部改动、前端按当前源码重建；两者均 arm64）。
  **v3.6.5 包被本包取代**（它只有后端修复，且前端复用旧镜像、没有「深度自检」按钮）。
- **另一个生产问题的结论（与代码无关，已查清）**："桶里有文件却看不到"是 **mock 期在开发机 local 模式上传的那批，字节从未进过 OBS**：
  开发机 Docker 卷 `docker_pm_uploads` 有 1315 个文件 / 266MB，而库里 413 条有效附件中 **412 条**的字节**只**在该卷里。
  已按库内 `file_path` 精确挑出并复制到 `dist/obs-upload/2026/09/`（412 个文件，0.4MB；`dist/` 已 gitignore），
  **数据库无需任何修改**；把这批补传到桶的 `uploads/2026/09/` 即可（**不要清空桶**，会删掉服务器上传的那批）。

### v3.7.0 — P2 受控查询：结构化事实走主系统受控查询 RPC + 口径同源 + 评测集（2026-09-23）

- **需求来源（用户实测）**：问"**某项目某阶段有几个附件**"，模型只数出**已入库的 1 个**。原因不是模型笨——
  AI 侧手里只有 `search_documents / read_page / calculate`，**根本看不到业务数据**，只能把"文档库里搜到的条数"当成"附件数"。
- **定案：B 方案（受控查询 + 口径 + 评测集）**。为什么不做 **A 方案（把"项目事实块"注入提示词）**：
  ① 注入的是**一次性快照**——用户一改数据（传了个附件、改了金额）答案立刻变错，而错得**看不出来**（模型会照抄旧快照并言之凿凿）；
  ② 快照是"整块"的，答不了"**某个具体阶段**有几个附件""**某一笔**付款付了多少"这类需要**收窄**的追问，只能把整棵树都塞进提示词；
  ③ 快照随会话膨胀，且把"业务事实源"搬到了 AI 侧——与既定边界（**主系统是唯一事实源、AI 服务无状态**）相反。
  B 方案把"取数"留在主系统、把"口径"做成**同源实现**、把"验收"做成**可复跑的评测集**，三者缺一不可（只有受控查询没有评测集，等于没人知道答案对不对）。
- **主系统侧**：
  - **4 个 RPC**：`POST /api/ai/query/{entity}`，entity ∈ `projects`／`contracts`／`payments`／`stats`。入参只收**结构化字段过滤**，不收 SQL/表达式/字段名拼接。
  - **scope 令牌**：与用户 JWT **同一把 `JWT_SECRET`**、`typ=ai_scope` 区分类型、带 `jti` 便于排查定位，`projects` 即授权范围，
    **上限 300s**（`ScopeTokenService.MAX_TTL_SECONDS`，配大了会被夹回、只能配小）。
  - **越权在 mapper 之前拒绝**：拒绝路径**对库的查询次数为 0**（不给越权请求留下任何读库痕迹），绝不接受 body 里的 projectId 越权查询。
  - **口径同源**：统计口径抽到 `ProjectMetricsService`，**项目详情页与受控查询 RPC 共用同一份实现**——
    "AI 说的数 == 页面上的数"因此是**由构造保证**的，而不是靠两边各写一遍再人工对。
  - **`AiQueryEntity` 字段白名单即 400 提示原文**：非法 filters 的报错**直接列出该 entity 支持的全部字段**（错误信息本身教会调用方怎么改）。
  - **留痕**：`operate_log` 逐次记录（谁/entity/返回行数/耗时）；`ai_ask_log` 记 `biz_query_count`（用了几次系统查询）与 `biz_entities`（查了哪些 entity）。
  - **迁移 V14**：`ai_ask_log` 加 `biz_query_count INT NOT NULL DEFAULT 0`（存量行读出 0 = P0/P1 确实没用过系统数据，语义自洽）与 `biz_entities VARCHAR(200 CHAR) NULL`。
  - **附件预览缓存**：加 `Cache-Control: private` + `ETag` + `304`；顺带修掉 `file_size` 为空时把字面量 `"null"` 当 Content-Length 发出去的缺陷。
  - **`stats` 支持 `projectIds` 收窄**（一次问多个项目的聚合，不必逐个项目各查一遍）。
- **AI 侧**：
  - **第 4 个工具 `query_business_data`，条件注册**：主系统传了 `biz_query` 才注册；**不传时工具集与行为与本版本前逐字节一致**（可前后分批发版）。
  - **四条路由铁律**（写死在提示词里）：**A** 系统事实（项目/合同/付款/附件数量/统计）**必须查系统**，查不到就说"系统里没有"，不许用文档或常识顶上；
    **B** 文档内容（条款、验收标准原文）仍走文档检索并给 `[cite]` 页码；**C** 引用系统数字**必须带口径与数据时间**、两类信息混用时**分开陈述**；
    **D** **不许用 `calculate` 反推系统已有的数字**（能查的只能查，算出来的必须标明是估算——两者在审计上不是一回事）。
  - **错误映射四类话术**：403 授权范围 / 4xx 参数 / **空结果** / 5xx 调用失败；**明确"空结果 ≠ 调用失败"**——把"没数据"说成"系统坏了"或反之，都会让用户得到错误结论。
  - **工具超时 20s**（比整轮问答短得多，避免一个查不通的 RPC 拖死整轮）。
- **一条刻意的行为变更**：开通受控查询（传了 `biz_query`）时，**即使当前没有已上传文档，也不再走"还没有可问答的文档"这条兜底**——
  因为此时"项目有几个附件""合同金额多少"这类问题**不依赖任何文档**，继续用旧兜底会把本可以答对的问题拦成拒答。**不传 `biz_query` 时旧兜底与全部行为不变。**
- **前端**：新增「系统数据」区块（**值 + 口径 + 数据时间**，与文档引用**视觉区分**，避免用户把系统事实当成文档原文）；
  问答超时 **180s**（与主系统 `AI_CHAT_TIMEOUT` 对齐）；**全屏预览不再重建 iframe**（此前每次全屏都重新下载一遍附件）。
- **评测集**：`eval/smoke-30.json`（**35 题**）+ `scripts/eval-ai.mjs`。设计要点：**真值取自页面接口**（用户看到的那套），
  runner 每次运行**现场算真值再比对**；`--dry` 做数据集体检（不发请求，可作质量门）；`--scope-token` 做"页面接口 vs 受控查询"两条路径交叉校验；
  `--write-truth` 写漂移快照。**真机后端上 20/20 真值解析成功并与 SQL 逐位一致**；页码类 **9 道文档题待人工标注**（`citation_pages` 全为 null，引用命中率恒 `n/a`——如实登记）。
- **造题期间发现的两件事**（都写进 `eval/README.md`）：
  ① `reactive-streams` 缺失**已在前一版（v3.6.6）修掉**，本版不再重复叙述、只作引用（不然会让人以为本版还在修它）；
  ② 造题期间**库被灌了演示数据**，真值随实测漂移：阶段附件 **31→34**、READY 文档 **3→6**、付款 **0→9 条**、中标额 **NULL→1884960**——
  题库已按**新真值**改，并加了**漂移检测**（快照值 vs 现场值不一致时直接打出来，避免"数字悄悄过期、评测还全绿"）。
- **验证数字**：backend `mvn -o test` **147 例全绿**；ai-backend `mvn -o test` **129 例全绿**；前端 `npm run build` **0 错**；
  `node scripts/check-docs.mjs` **0 问题**（20 份）；`eval-ai.mjs --dry` 数据集体检 **exit 0**；三个镜像全部 **arm64/linux**；
  两个发布包 **`docker load` 均成功**（`pm-backend:v3.7.0` / `pm-frontend:v3.7.0` / `pm-ai-backend:v1.1.0`）；
  从 **load 出来**的镜像里 `docker cp` 出 `app.jar`，确认含 `BOOT-INF/lib/reactive-streams-1.0.4.jar` 与新类
  `AiQueryController`/`ScopeTokenService`，AI 镜像内含 `BizQueryClient`；`--list-truth` **20/20**。
- **未验证清单（照实抄，不美化）**：
  1. **真机 nginx 反代下的回调连通性**（AI → `PM_AI_QUERY_CALLBACK_URL` → 主系统），本地只有单测与 mock；
  2. **崖山 `FETCH FIRST n ROWS ONLY` 的实际执行**（分页/限量语法未在真库跑过）；
  3. **`operate_log` / `ai_ask_log` 的真实落库**（含 V14 两列）；
  4. **V14 在真库的 `ALTER`**（沿用 V8/V10/V11 已成功应用的多列写法，但方言未实测）；
  5. **OBS `getObjectMetadata` 的 ETag 取值**（304 协商在 OBS 存储下的实际表现）；
  6. **180s 长问答后段仍能查**（scope_token 300s 覆盖得住 180s 这件事，目前**只有单测推理**，没有真机长跑）；
  7. **`/api/ai/chat` 全链路未打真实系统**（评测用 mock 跑通了判定链，真实链路未端到端跑）；
  8. **§11 交叉校验因拿不到 `scope_token` 恒 `n/a`**。
- **出包**：`dist/pm-release-v3.7.0/`（主系统，前后端都改了故两端都重建）与 `dist/pm-ai-release-v1.1.0/`（AI，新增工具属特性版本故走 minor）。

---

### docs-1.5 — AI 模型调用配置评估 + 并发上限推导 + 「统一管理 + 任务环境隔离」队列方案（2026-09-23）

- **需求来源（用户三问）**：① 目前对平台模型的调用配置是否合理；② 能否提高并发；③ 是否应当增加"统一管理 +
  任务环境隔离的任务队列"模块来提效。**本轮只评估、不改源码**（未动 `scripts/**`、`deploy/**`、`ai-backend/**`、
  `frontend/**`、`backend/**` 任何代码），产出 [`docs/AI模型调用与任务队列评估.md`](docs/AI模型调用与任务队列评估.md)。
- **评估方法（先读清楚，再算）**：逐调用点读源码与配置，得到**真实值表**（§2.1/§2.2：13 个调用点 × 超时/重试/并发池/批量上限 + 判断），
  再用平台既有**实测数字**做量化推导（§3）；所有无实测依据的数字一律标 `估算（假设：…）`，读不到的写"未确认"（§8 共 11 条）。
- **结论一：配置"能用但不合理"。** 四类模型**共用一把 sk、一个网关地址、一个 300s 超时**（`application.yml:30-32`）；
  `LlmClient` 的 `timeoutSeconds` 参数**被忽略**（`LlmClient.java:182,293-296`），于是"自检 15s"实际按 300s 发出；
  **内长外短**（AI 侧 300s > 调用方 180s）导致用户已超时、槽位仍被占用（最坏 8 轮 × 300s）；
  除 OCR 外**无任何并发上限**（全仓 `grep Semaphore` 只命中注释）；rerank **一次打 75 对**（recall 50 + 25）不分批；
  **全链路重试 0 次**（连幂等的 OCR/embedding/rerank 都不重试）；**无 actuator/micrometer → 无任何指标**。
- **结论二：并发能提，而且不用加机器。**
  - **问答上限 ≈0.16 QPS（≈10 问答/分钟），由重排单点决定**：每次问答 2 次检索 × 75 对 = 150 对 ÷ 手册实测 **24 对/s**；
    生成侧 4 副本 ÷ 10s ≈ 0.4 QPS。**`RETRIEVAL_RECALL` 50→20 ⇒ 24/60 = 0.4 QPS（约 2.5×）**，此后瓶颈转为生成侧。
  - **向量化今天只跑出平台能力的 ~12%**：`embedChunks` 是**串行**批 32（`RetrievalService.java:582-588`），
    按手册"32 条 + 并发 8 = 147.9 条/s"反推单批 ≈1.73s ⇒ ≈**18.5 条/s**；改并发 8 即 **147.9 条/s（≈8×）**。
  - **OCR 的 `OCR_CONCURRENCY=12` 对典型文档是死配置**：在飞 = `min(12, ceil(页数/8))` 且每任务**自建线程池**
    （`PlatformOcrClient.java:152,168`）→ 8 页文档只有 **1** 个在飞、25 页文档 4 个；要逼近平台上限 6.98 页/s 需 ≈96 在飞。
- **结论三：任务队列值得做，但要做"薄"——隔离在 AI 侧（资源），队列在主系统侧（事实）。**
  - **必须先纠正三个前提**（§1.2）：①"embedding 单点是容量问题"其实**是接线问题**——向量化**不在入库路径**上，
    而是**第一次检索时在问答请求里**惰性补算（3,000 切片 ≈162s，撞穿前端 180s）；②抢占不在 GPU 之间
    （embedding 在 GPU6、rerank 在 GPU7 物理不冲突），而在**同一个 JVM 的 CPU、同一个网关地址、以及"向量化跑在问答路径上"**；
    ③"6 个 OCR 并行"实际受"每份 `min(12,批数)` + 最多 4 份并发文档"双重约束，常见只有 1~4 在飞。
  - **方案**：`ModelRegistry`（一处 baseUrl/key/超时/重试/并发上限/能力声明 + 启动 fail-fast 探测 + 每模型 5 项指标，
    4 步可回滚迁移）；任务侧**复用 `attachment_ai_task` 作唯一事实源**（V15 增 `task_type`/`priority`/`attempts`/`next_run_at`/
    `content_hash`/`claimed_by`/`claim_expires_at`），**双机用 DB 抢占式领取**（`UPDATE … WHERE status='QUEUED'` 影响行数=1 才算领到 + 心跳续约 + 过期待领）；
    AI 侧只保留 `chat 4 / ocr 12 / embed 8 / rerank 2` 四个信号量与交互/批量两条通道（AI 因 `local` 向量存储**必须单实例**）。
- **量化触发点（必须有的结论）：本地"进程内向量"什么时候必须换 OpenSearch。** 本轮真机读到
  `ai-backend/work/vectors/38c6637b44d949dd.json`：**1 个 4096 维切片 = 38,333 字节**（9.36 字节/维）。
  由此得三条任一成立即必须换：① **延迟**——切片 **≥4,000（≈130 份）**时每次提问 **+1.0s**、15,000 时 **+3.8s**（估算）；
  ② **内存**——常驻 `16KB × 切片数 × 并发`，4 并发时 **≈3 万切片触顶**（AI 容器**没有 `-Xmx`**，属未确认项）；
  ③ **架构（最硬）**——`local` 是进程内状态 ⇒ AI **必须单实例**，一旦需要 HA/2 实例立刻不成立，且切片**没有项目/密级元数据**、
  "先过滤再检索"今天做不到。→ 建议把 OpenSearch 从 **P2 上提为 P1**；这与既有《平台能力实测结论》§4.9"规模不是选型理由"
  **不矛盾**：那句话对**余弦**成立（15,000 切片 ≈61ms，本文复核），本文补的是它没算的 JSON 往返/并发内存/单实例三项成本。
- **发现的两个既有缺陷（有代码证据，不是猜）**：
  1. **请求级 `doc_ids` 只是"提示词级"软约束**：`QaService` 只把它拼进 system（`Prompts` 原话是"**优先**检索这些文档"），
     而 `Tools.searchDocuments` 直接把**模型给的** `docId` 传给检索（`Tools.java:183`），与作用域**无交集运算** →
     模型不传 `doc_id` 就检索**全库**；叠加 AI 服务**无入站鉴权**，网络可达者即可读全库（建议 P1 改硬过滤）。
  2. **解析队列满 → 任务永久 `QUEUED`**：`submit()` 先 `tasks.put`+`persist` 再 `execute`，而 executor 未设拒绝策略（默认 AbortPolicy）
     → 抛 `TaskRejectedException` 落通用 500，**任务停在默认 `QUEUED`（`UploadTask.java:49`）永不变更**，
     且控制器先落的 `work/tmp/` 临时文件**残留**（`UploadTaskController.java:40-42`）。
- **P0 五项（本周可做、低风险）**：① 超时对齐（chat 150s / embed 30s / rerank 20s / ocr 600s，且**必须 < 调用方 180s**）；
  ② 重排降配（recall 50→20~25，先补页码标注建 recall 基线）；③ 向量化并发化（共享信号量并发 8）或**前移进解析任务**；
  ④ 队列满**诚实失败**（标 FAILED + 删临时文件 + 429/503 带原因）；⑤ 每模型信号量（batch 占用设 per-permit 上限）。
  验收指标：网关挂死时自检 ≤20s、单轮问答内部 ≤150s、问答上限 ≥0.3 QPS、3,000 切片补算 ≤30s、
  导入期间问答 P95 劣化 ≤20%、0 条永久 QUEUED / 0 个临时文件残留。
- **明确不做/推迟**（§7）：不引 Redis/MQ/Celery、不引 Spring Batch/Quartz、不在 AI 侧新建持久化队列、不自动重试 `chat`（不幂等）、
  暂不新建 `ai_task` 表、暂不放开 262K、暂不降维（**先向平台确认**，见未确认项）、不引 LangChain4j/Spring AI（沿用既有结论）。
- **验证方式**：`node scripts/check-docs.mjs` **0 问题**（21 份 Markdown）；本文所有"实测"数字均可在
  [`docs/附件智能处理-验收标准与现状.md`](docs/附件智能处理-验收标准与现状.md)、[`ai-backend/docs/平台能力实测结论.md`](ai-backend/docs/平台能力实测结论.md)、
  [`ai-backend/docs/Qwen3-VL-Embedding-Reranker调用手册.md`](ai-backend/docs/Qwen3-VL-Embedding-Reranker调用手册.md)
  或本轮真机读数（`ai-backend/work/vectors/38c6637b44d949dd.json`）中复核；**本轮无源码变更，故未跑 `mvn test`**。

---

### docs-1.6 — 为两台新服务器出 OpenSearch 部署与接入文档 + 部署资产（2026-09-23）

- **需求**：为两台新服务器 **`10.254.212.110` / `10.254.212.111`** 写一份**可照抄**的 OpenSearch 部署 + 接入文档，并把配套部署资产落到仓库。
  环境与既有服务器同构：**内网、无外网**（只 `docker load`，**绝不 `--build`/`docker pull`**）、银河麒麟 aarch64（文档要求先 `uname -m` 确认并给出 x86_64/arm64 两种取法）。
- **交付物 5 个**：① [`docs/OpenSearch部署与接入.md`](docs/OpenSearch部署与接入.md)（主交付，风格照《部署与发布全流程手册》§0：先"照抄即可"的线性步骤，原理/运维/排障在后）；
  ② `deploy/opensearch/docker-compose.yml`；③ `deploy/opensearch/.env.example`；
  ④ `deploy/opensearch/os.sh`；⑤ 文档与资产**逐字自洽**（当时用脚本核对了 compose 代码块与 `.env` 代码块逐字一致）。
  > ⚠️ 上面 ②③④ 三个 Docker 路线资产**已在【修订二】中删除**（改为 tar + systemd 资产），保留此段仅为记录当时的交付与验证方式。
- **决策一：定稿单节点（推荐默认）。** 两台机器 **8 核 / 16GB 内存 / 200GB 存储**，并发不大 ⇒ 单节点足够：
  `discovery.type: single-node`（**不写** `cluster.initial_master_nodes` / `discovery.seed_hosts`）、
  **`OPENSEARCH_JAVA_OPTS: "-Xms8g -Xmx8g"`**（16GB 的 50%，另 8GB 留给文件系统 cache；两值必须相等、上限 31g）、
  `bootstrap.memory_lock: true`（配 `ulimits.memlock: -1`）、`path.data` / `path.repo` 挂卷、
  索引 **`number_of_shards: 1` + `number_of_replicas: 0`**（单节点设副本只会 yellow + 白占盘；这个量级分片越少越好）、健康检查期望 **`"status":"green"`**。
- **决策二：2 节点 quorum 明账（写进 §0 的"为什么不推荐"）。** OpenSearch 选举需**多数派**：`N` 个可选举节点需 **`⌊N/2⌋+1`** 票。
  `N=2` ⇒ **quorum = 2 票** ⇒ **挂掉任意一台就失去 master：集群不可写（也读不了集群状态）**；副本 = 1 只保证"数据不丢"，**不保证服务可用**。
  所以 **2 节点相比单节点几乎换不到可用性，却多一倍运维复杂度**（seed/initial_master_nodes 只能写一次、滚动重启必须一台一台来、升级窗口翻倍）——
  **明确不推荐**。真要 HA 必须**第 3 台**（可用轻量机做 **dedicated cluster-manager**，不存数据），
  届时只改**两个参数**：`discovery.seed_hosts` 与 `cluster.initial_master_nodes`（3 个节点名/IP），并把副本从 0 改成 1 → 路径与示意编排见文档**附录 A**。
- **决策三：第二台 `10.254.212.111` = 冷备 + 演练机。** 跑同一套编排但**默认停着**；每晚在 `.110` 打快照（`_snapshot` 到本地 `fs` 仓库）
  并 `rsync` 到 `.111`；文档给出**七步恢复演练**（起容器 → 注册仓库 → 列出快照 → `_restore` → 核 `_count` → 停掉），
  **RTO 分钟级**（估算：索引 ≈400MB 时 `_restore` 约 10~60s + 起容器约 90s + 操作 ⇒ 约 5 分钟），RPO ≈1 天（每晚一份）。
  并写明"将来①需要两个 AI 实例做无中断检索、或②切片 >50 万时，再加第 3 台转 3 节点集群"。
- **决策四：安全取内网 HTTP + basic auth（强口令）。** `DISABLE_INSTALL_DEMO_CONFIG: "true"`（**明确不用 demo 配置**；它把"忘了设口令"从"跑起来但弱口令"变成"直接启动失败"）、
  `OPENSEARCH_INITIAL_ADMIN_PASSWORD: ${OPENSEARCH_PASSWORD:?…}` 强制必填（复杂度：**≥8 位且大写/小写/数字/特殊字符四类齐全**）；
  TLS 作为**可选方案 B** 列出（自签证书、AI 侧要导信任库、证书轮换运维），并给出选择建议：**仅当等保/合规要求传输加密时选**。
  补偿措施写死：**9200 只放行两台应用服务器 + 另一台 OS 节点**，9300 只放行集群内，9600 不放行，**9200 绝不对外**，并给"从不该通的机器验证它确实不通"的自检命令。
- **决策五：容量口径（回答"换 OpenSearch 值不值"）。** 4096 维 `float32` = **16,384 字节 ≈16KB/条**（+HNSW 图约 1~2KB）⇒
  **2 万切片 ≈350~400MB、10 万 ≈1.8GB、100 万 ≈18GB**（估算，含原文/倒排/段开销约 ×1.15~1.25）；
  对比现有 `local` 的**实测 38,333 字节/切片（9.36 字节/维，十进制 JSON）**——**同样数据只占几百 MB、检索毫秒级**，
  故本项目这个量级**不需要量化、也不需要 `on_disk`**（百万级才考虑；并注明磁盘化向量检索是 2.x 后期能力、各版本支持度不同，用前核对官方 k-NN 文档）。
- **决策六：mapping 把"先过滤再检索"的字段设计进去。** `vector`（`knn_vector` 4096 / `cosinesimil` / HNSW `m:16, ef_construction:128` / `engine: lucene`）
  + **`project_id` / `phase_code` / `attachment_id` / `security_level` / `doc_type` / `source` / `created_at`（keyword/date）**，
  并给出建索引、`_bulk` 写入（`_id` = 切片键 `docId:pageNo:sha1前12位`，幂等）、kNN + filter 预过滤查询、
  以及 **hybrid（BM25 + kNN）** 的两种落地（沿用项目"向量优先去重 + Reranker 精排"口径；加权求和必须**各自归一化**；RRF 属较新能力不依赖）。
  ⚠️ **如实写明**：现有 `OpenSearchIndex.toDocument()` 只写 6 个字段，这几个元数据字段**当前无人写入**、切片本身也没有元数据
  ⇒ 过滤器目前只能按 `doc_id` 用起来，**"先过滤再检索"是能力就绪、不是功能可用**（属应用改动，不在本轮）。
  ⚠️ **注（本轮 `docs-1.7 修订十二`）**：本条里的 `ef_construction:128` 与 `refresh_interval:-1` 是 **docs-1.6 当时的取值**，
  现已把手册 步骤 16 / 附录 E 对齐为代码的 **`100` / `1s`**（见总表 docs-1.6 行）；**以手册当前文本为准，本段不再作为当前口径**
  （另注：上面"`toDocument()` 只写 6 个字段"一条在 `ai-1.2.0` 已变为"元数据非空时一并写入"，见 `ai-1.2.0` 明细）。
- **决策七：AI 侧接入、重建、回滚、降级口径。** 改 `ai/.env`：`VEC_BACKEND=opensearch` + `OPENSEARCH_URL`（**必须是 AI 容器内可达的地址，不能写 127.0.0.1**）/`OPENSEARCH_INDEX`/`OPENSEARCH_USER`/`OPENSEARCH_PASSWORD`；
  **全量重建索引**给出八步顺序与耗时估算（平台手册实测 **147.9 条/s** 口径：2 万切片 ≈3~4 分钟、10 万 ≈17~20 分钟、100 万 ≈3 小时），
  并强调**必须带进度与可中断续跑**（以 **`chunk_key`** 为断点、`_id` 幂等、**不许用"删索引重来"当重试**）；
  指出**最容易踩的坑**：向量化/写索引**不在入库路径上**（第一次检索惰性补算）⇒ "切完开关就完事"会让第一个提问扛下全量重建、**必然撞穿 180s 超时**，所以**先灌满索引再切**；
  ⚠️ **注（`ai-1.2.0`）**：这一条已随 **向量化前移**（`DocIndexer`，解析入库即完成）与 **`/admin/reindex` 全量重建端点**落地而改变——
  当前状态下"惰性补算"只应作为兜底出现（`ai.vector.lazy_backfill` 正常为 0），详见 `ai-1.2.0` 明细与手册 步骤 19；
  回滚 = `VEC_BACKEND=local` + `pm.sh restart ai`（`work/vectors/` 不删即**秒级回滚**，无需重新向量化）；
  **降级口径定死**：集群不可达时 **①明确报错（不是"没找到相关内容"）②`/health?with_vec=true` 的 `vec` 块 not ok ③日志给原因**，**绝不静默换检索方式**（与代码既有 `checkBackend()` 行为一致）。
- **顺带收益（写进 §6）**：向量状态搬出进程后，**AI 服务从"必须单实例"变成可跑 2 个实例**（`VEC_BACKEND=local` 是进程内状态，正是它不能 HA 的根因）；
  同时**如实划边界**：`work/docs/`（文档库）与 `work/vectors/` 仍是本地卷，完全无状态还需把文档库也搬出进程（**不在本轮**）。
- **未确认项 §8 共 14 条**（含"实际架构（先 `uname -m`）""AI 服务跑在哪台""`3.1.0` 这个 tag 是否存在且含 arm64（本轮开发机到 registry 连接超时，**未能联网核实**）""
  磁盘类型与容量""是否允许内网 HTTP""是否需要 Dashboards""NTP 时间源""防火墙用哪个""快照保留期""现网真实切片数""AI 容器是否设 `-Xmx`""元数据落地排期""平台是否真不支持降维""是否上监控栈"）；
  已由本轮决定解决的（节点数/第二台角色/副本数/堆大小）**不再列为未确认**。
- **验证方式**：`bash -n deploy/opensearch/os.sh` **exit 0**（本机 Git Bash 5.3.15；本机 Docker 引擎未运行，未用容器复验——已在报告里说明）；
  另跑了不依赖 docker 的**行为复验**（`.env` 缺失/只填必填项/口令带引号/口令为空/probe 失败/版本解析含四种 arch 与 `.tar.gz`/无 `.env` 时 `start` 必须失败并给下一步命令）与**自洽性核对**
  （文档 §3.1 compose 代码块与资产**逐字 66 行一致**、§3.2 `.env` 代码块**逐字 37 行一致**、`os.sh` 与 compose 引用的 `.env` 键全部存在于 `.env.example`、命名与路径三处一致、compose 结构自检 6 项）；
  `node scripts/check-docs.mjs` **0 问题**。**本轮无源码变更，故未跑 `mvn test` / 前端 build**。

**★ docs-1.6 修订二（同日，决策级修正，不新开版本号）：部署形态改为官方 tar.gz 解包 + systemd（这两台机器不装 Docker、也不使用 Docker）。**

- **为什么改**：新机器本来就没装 Docker，且这两台"部署完就基本不用怎么运维"；官方 Tar + systemd 已经够用，
  少一层容器抽象对"部署完就不管"更有利（但也因此把官方那套"tar 不定义路径/用户/权限，配错会有意外行为"的提醒当成硬要求来落实）。
- **资产全换**：`git rm` 掉 Docker 路线的 `docker-compose.yml` / `.env.example` / `os.sh`（**不留"另一条路"的残影**），
  新增 **`opensearch.yml.example`**（单节点 + `path.data/logs/repo` + REST 层 TLS 取舍 + transport 证书三行）、
  **`jvm.options.d/heap.options`**（`-Xms8g -Xmx8g`，16GB 的 50%，并写明为什么不是更多）、
  **`opensearch.service`**（**照官方 Tar 页给的 systemd 示例改写**：`Type=forking`、`ExecStart=…/bin/opensearch -d`、
  `User/Group=opensearch`、`StandardOutput=journal`、`LimitNOFILE=65536`、`LimitNPROC/LimitAS/LimitFSIZE`、
  `TimeoutStopSec=0`、`KillSignal=SIGTERM`、`KillMode=process`、`SendSIGKILL=no`、`SuccessExitStatus=143`、
  `TimeoutStartSec=75`，**另补官方要求的 `LimitMEMLOCK=infinity` 与 `Environment=MAX_MAP_COUNT=262144`**，并加 `Restart=on-failure`）、
  **`snapshot-backup.sh`**（每晚快照 → 本地 `fs` 仓库 → `rsync` 到 `.111`，含保留期清理、幂等、失败给下一步命令、`bash -n` 通过）。
- **两处事实性修正**：① **架构**：这两台是 **x86_64**（安装包用 `opensearch-<版本>-linux-x64.tar.gz`），
  而本项目其它服务器是 aarch64 —— 文档里明确"别把两边的产物搞混"；
  ② **按官方文档逐条对齐**（附录 B 共 19 条），其中三条是**真错**：
  （a）**官方默认 REST 层是 HTTPS**（demo 脚本装自签证书，quickstart 是 `curl https://localhost:9200 -k`），
  我们原稿只说"内网 HTTP + basic auth"、**没有显式关 TLS** ⇒ 现在给出**三选一**（HTTP+basic auth 推荐 / 自签 TLS / 禁插件不推荐）
  并在配置资产里**显式**写 `plugins.security.ssl.http.enabled: false`；
  （b）**口令复杂度**官方是四条（8~100 字符、四类字符各一、**zxcvbn 达 strong 以上**、**不得与 admin 相似**），我们原来只写了前两条；
  （c）**原 compose 设 `DISABLE_INSTALL_DEMO_CONFIG=true` 且启用安全插件 —— 按官方镜像 README 的 Scenario 3，这会因"没有证书与安全配置"直接起不来**；
  现在改为"**让 demo 脚本正常跑**（拿到证书与 admin 用户）+ 用强口令保证口令安全"，并把顺序（跑脚本 → 再接入我们的 `opensearch.yml`）
  与"**不能把 transport 证书行丢掉**"写成显式步骤。
- **验证**：`bash -n deploy/opensearch/snapshot-backup.sh` **exit 0**（Git Bash 5.3.15）；另跑不影响真实数据的**行为复验**
  （`help` / 未知子命令 exit 2 / `restore` 缺参数 exit 2 / 无口令 exit 1 且给出三种给法 / 配置文件读取生效 / 版本与架构解析）；
  **自洽性核对**（文档引用的资产内容与 `deploy/opensearch/**` 逐字比对、命名与路径一致性、无 Docker 残影）**全部通过**；
  `node scripts/check-docs.mjs` **0 问题**。**本轮仍无任何应用代码变更**。

**★ docs-1.6 修订三（同日，两件事合并在一个提交里，不新开版本号）：① 部署根定为 `/home/opensearch/`（D 方案，= 专用账号 `opensearch` 自己的家目录）；② 新增 OpenSearch Dashboards。**

- 部署根目录树（两台一致）：

  ```text
  /home/opensearch/                     ← 专用账号 opensearch 的家目录（= 部署根）
  ├─ app/                 ← 当前程序（tarball 解包内容：bin/ config/ jdk/ lib/ modules/ plugins/）
  ├─ app-<旧版本>/         ← 升级时保留的旧目录（回滚点）
  ├─ config/              ← 我们维护的 opensearch.yml + jvm.options.d/heap.options + snapshot-backup.sh
  ├─ data/                ← path.data
  ├─ logs/                ← path.logs
  ├─ backup/fs-repo/      ← path.repo（快照仓库）
  └─ dashboards/app/      ← OpenSearch Dashboards（config/opensearch_dashboards.yml）
  ```

- **建账号方式改掉**：不用官方示例那种"系统账号 + `--no-create-home`"（D 方案要求部署根就是该账号的家目录），
  改为 `sudo useradd -m -d /home/opensearch -s /bin/bash opensearch` → `sudo passwd -l opensearch`（锁登录，仅 root 可 `su -`）
  → `sudo chown -R opensearch:opensearch /home/opensearch`；文档 §2.4 给了完整命令与复核项。
- **删掉上一版的 `/home/lhim` 穿透权限（`chmod 711`）要求**（D 方案下不再需要，留着会误导），
  换成 **§2.4.1 家目录可用性检查**：`sudo -u opensearch ls -d /home/opensearch` +
  `sudo -u opensearch touch /home/opensearch/.wtest`（自证能读写自己的家目录）；
  §3.4 再用 `sudo -u opensearch ls /home/opensearch/app/bin/opensearch` 做最终确认。
- **新增 §2.4.2 `/home` 挂载前置检查**（D 方案成立的前提）：`df -h /home`、
  `findmnt -no SOURCE,FSTYPE,OPTIONS /home` / `lsblk -f` 确认**本地盘、非 NFS/autofs/CIFS、空间够**；
  给判定表与**既定退路**（只把 `path.data` 指到 `/data/opensearch`：建目录 + `chown opensearch` + 改 `config/opensearch.yml` + 重启 + 复核）；
  并写明 **`nosuid`/`nodev` 对本服务无害、不要为此改挂载参数**（真正会挡路的只有 `noexec`）。
- **全量改名**：文档 + 四个 OpenSearch 资产（`path.data|logs|repo`、`ExecStart`/`WorkingDirectory`/`OPENSEARCH_HOME`、
  `snapshot-backup.sh` 的仓库目录与 rsync 目标）+ `deploy/README.md` + `docs/README.md` + 本段一次性对齐。
- **升级流程按新结构重写**（§7.3 逐步命令）：打快照 → 记录当前版本 → 解包到 `app-<新版本>/`（**不覆盖 `app/`**）→
  拷 `config/opensearch.yml` 与 `config/jvm.options.d/heap.options` 进新目录 → **把 demo 生成的证书（`*.pem`）、
  `opensearch-security/`、`opensearch.keystore` 也带过去**（不带就起不来）→ 停服 → 旧 `app/` 改名 `app-<旧版本>`、
  新目录改名 `app/` → `chown -R opensearch:opensearch /home/opensearch` → 启动 → 验收；
  **回滚即反操作**（失败的新 `app/` 改名 `app-failed-<新版本>`、旧目录改回 `app/`、`chown -R`、启动）。
- **统一 `config/` 与 `app/config/` 的关系（只留一种做法）**：我们**只维护** `/home/opensearch/config/` 下的
  `opensearch.yml` 与 `jvm.options.d/heap.options`；安装与每次升级时**拷贝**进 `app/config/`（**不用软链**）；
  运行时生效的是 `app/config/` 那份，`heap.options` 必须落在 `app/config/jvm.options.d/` 才不被新版本 `jvm.options` 覆盖。
- **新增 OpenSearch Dashboards（文档 §12）**，与 OpenSearch 同机（`10.254.212.110:5601`）：
  - **官方依据**：Dashboards tar 页（**只有三步：解包 → 改 `config/opensearch_dashboards.yml` → `./bin/opensearch-dashboards`，没有 systemd 示例**）、
    Configuring 页（配置文件位置口径）、以及官方示例 `config/opensearch_dashboards.yml`（**字段名与默认值的权威来源**）。
  - **版本硬约束**：必须与 OpenSearch **完全一致**（`3.1.0`，三数字全同）；下载 + `.sha512` 校验 +
    **归档一份 tar 到 `.111`**（离线重建的前提）；解包到 `/home/opensearch/dashboards/app/`（同属主 `opensearch`、非 root 运行）。
  - **配置**：新增资产 `opensearch_dashboards.yml.example`——`server.host` 绑内网 IP（官方默认 `localhost`，远端连不上）、
    `server.port: 5601`、`opensearch.hosts: ["http://127.0.0.1:9200"]`（**与"关 REST 层 TLS"的取舍绑定**）、
    `opensearch.username/password`（**建议专用只读账号 `dashboards_ro` 而非 admin**，文档给了建角色/用户 + `securityadmin.sh` 的步骤）、
    `opensearch_security.multitenancy.enabled`（并说明官方示例里"workspace 与多租户互斥"）、
    **`opensearch.requestHeadersAllowlist` 必须含 `authorization`**（官方示例里 `requestHeadersWhitelist` 已标 DEPRECATED）。
  - **systemd**：新增资产 `opensearch-dashboards.service`——官方**没给** Dashboards 的 unit，所以骨架照官方【OpenSearch tar 页】那份示例，
    按 Node.js 运行方式改 3 处并写明原因：`Type=simple`（不是 `forking`）、`KillMode=mixed`（包装脚本 + Node 子进程一起收）、
    `Environment=NODE_OPTIONS=--max-old-space-size=1024`（**官方 `config/node.options` 默认是 `4096`，16GB 机器必须封顶**）。
  - **内存预算**：Dashboards 是 **Node.js 不是 JVM**；文档给出估算（Node 约 0.6~1.2GB，标注估算（假设：1GB 上限内、单实例））
    与"OpenSearch 8g 堆 + 页缓存 8g + Node 1GB ≈ 17~18GB 名义需求 vs 16GB 物理内存"的明账与建议（加内存到 32GB 或把 UI 挪到 `.111`）。
  - **端口与防火墙**：`5601` **只放行运维网段/跳板机**（不对全网），并说明它与 9200（只给应用服务器）、9600（不放行）的暴露差异。
  - **冷备结论（回答用户疑问）**：Dashboards **不需要单独冷备**——它的状态（saved objects/租户/索引模式）存在
    OpenSearch 的 `.opensearch_dashboards*` 里；DR = OpenSearch 快照 + 一份 yml + `.111` 上的 tar 包。
  - **⚠️ 快照必须显式包含系统索引（已同时改脚本）**：OpenSearch 快照**默认不含系统索引** ⇒ 只备业务索引的话，
    恢复到 `.111` 后 **Dashboards 是空的、安全配置回默认**。`snapshot-backup.sh` 默认索引列表改为
    `pm-ai-chunks,.opensearch_dashboards*,.opendistro_security`，并在请求体加 **`expand_wildcards: all`**
    （否则通配符展开不到系统索引 ⇒"快照成功但内容不全"的假成功）；文档 §12.7 给出**原因 + 三条验证命令**
    （打快照后看 `indices` 列表、恢复后 `_cat/indices` 看得到系统索引、浏览器里仪表盘还在）。
- **保持不变**：集群名 `pm-os`、节点名 `os01`（用户只批了目录名）。
- **验证**：① 全仓 `git grep`：**两种历史路径写法（`lhim` 家目录下的前后两版）均 0 命中**；
  ② 文档 ↔ **6 个资产**（OpenSearch 4 + Dashboards unit + Dashboards yml）逐项取值一致（自洽性脚本含 Dashboards 项，全部 OK）；
  ③ `bash -n deploy/opensearch/snapshot-backup.sh` **exit 0**；④ `node scripts/check-docs.mjs` **0 问题**；
  ⑤ 正文（附录 B 之前）**零 Docker 残影**。**本轮仍无任何应用代码变更**。

**★ docs-1.6 修订四（同日，版本升级 + 安装包目录统一，不新开版本号）：版本升 3.1.0（按 3.x 官方重核，含 k-NN 引擎变更）+ 安装包目录统一 `/home/opensearch/`。**

- **版本**：全文版本号统一到 **`3.1.0`**（OpenSearch 与 Dashboards 同版本），包名与体积按**真机 `pdmsosgh` 实际值**写：
  `opensearch-3.1.0-linux-x64.tar.gz` **1,000,330,285 字节**、`opensearch-dashboards-3.1.0-linux-x64.tar.gz` **385,456,866 字节**
  （合计 **≈1.32 GiB**）。**上一版固化的上一代版本 SHA512 作废**：本文件**不再固化校验和**，改为"以现场 `.sha512` 为准"
  （本轮无法联网核实 3.1.0 的校验和，已在 §1.1 与 §8 第 1 条如实登记）。
- **安装包目录统一到 `/home/opensearch/`**（真机就是放在部署根下）：`scp` 目标由 `/tmp` 改为 `/home/opensearch/`，
  并把**跨用户/跨家目录拷贝**写成显式前置（新增 **§1.3**）——
  为什么 `lhim` 直接 `scp` 会 `Permission denied`（`/home/opensearch` 属主是专用账号、`useradd -m` 默认 0700）；
  **做法 A（与真机现状一致）**：`sudo usermod -aG opensearch lhim` + `sudo chmod 2775 /home/opensearch`（setgid 让新文件继承 `opensearch` 组）
  → 重登使组生效 → 直传；**并写明代价**（`lhim` 与 `opensearch` 组对该目录有写权限，属同一运维信任级；不接受就收到 `2750` 或用做法 B）；
  **做法 B（不动权限）**：先落 `/tmp`（仅中转）再 `sudo install -o opensearch -g opensearch -m 644`；
  并明确**不要** `sudo -u opensearch cp /home/lhim/…`（穿不过 0700 的家目录）。
  **解压后必须 `sudo chown -R opensearch:opensearch /home/opensearch/app`**（用 `lhim` 解压会让 `app/` 混入 `lhim` 属主 ⇒ 服务启动报权限错误）；
  **§3.1 加"已自行解压则跳过"的兼容说明**（用户现在就是已解压状态）；新增 **§1.6 占用与清理**（≈1.32 GiB；验收通过后 `.110` 可删、`.111` 留一份归档，并给删除命令与"想留原包做离线重装则保留"的一句交代）。
- **按 3.x（`docs/latest`）逐项重核，附录 B 新增第 27~34 条**（"官方 3.x 怎么说 → 我们现在怎么写"），其中**真改的四处**：
  1. **k-NN 引擎**：官方 3.x 文档里 NMSLIB 标为 legacy/"now deprecated"、`engine` 省略时**默认 `faiss`**、Faiss 的 `cosinesimil` 是 **2.19+** 且**会自动归一化向量（存储值 ≠ 输入值）**、另有需单独插件的 JVector ⇒ **§5.3 改为"默认用 `lucene`、`faiss` 可选并写明归一化副作用、3.x 新索引不要用 `nmslib`"**。
  2. **k-NN mapping 键名**：`space_type` **既可写字段层也可写 `method` 内**、`data_type` 默认 `float`、`mode` 默认 **`in_memory`**、`dimension` 合法区间 **[1, 16000]**、参数键名是 **`parameters`**（不是 `params`）、`m` 默认 16、`ef_construction` 默认 100 ⇒ **§5.2/§5.3 改为只写字段层 `space_type` + 显式 `data_type`，并把各项与官方默认值对齐/对照**。
  3. **k-NN 查询语法**：3.x 是 **`"knn": { "<字段名>": {...} }`**（字段名是 `knn` 下第一层），`filter` **只能配 `faiss`/`lucene`**，`k` 范围 [1, 10000] ⇒ **§5.5 补 3.x 语法说明**；并**如实登记一处不一致**：仓库适配层 `OpenSearchIndex.search()` 仍按 2.x 固定结构拼查询体，**因我们的字段名恰好叫 `vector` 而语义仍正确**，但上线前必须做"top-k 顺序与 local 一致"的实测（真要改适配层属应用改动，已登记 §8 第 20 条）。
  4. **快照请求体**：官方 Create Snapshot API 的请求体字段**只有** `indices`/`ignore_unavailable`/`include_global_state`/`partial`，**没有 `expand_wildcards`**（上一版脚本误加了它）⇒ **脚本移除该字段**，并在脚本注释与 §12.7 留档"为什么不能加回来"；`indices` 原生支持 `,` 列表与 `*` 通配（官方示例本身就用了通配 + 系统索引）。
- **3.x 复核后确认"未变"的项**：`opensearch-tar-install.sh` 仍存在且仍装 demo 证书/改 `config/opensearch.yml`；
  `OPENSEARCH_INITIAL_ADMIN_PASSWORD` 仍是 2.12+ 必需项、**口令四条要求未变**；`plugins.security.ssl.http.enabled`、
  transport 三个 `pem*_filepath`、`allow_unsafe_democertificates`、`allow_default_init_securityindex` **四个键都还在**；
  systemd 示例 **3.x 与 2.x 逐行相同**（且同样标注"非生产推荐、要 systemd 就用 RPM"）；
  Dashboards tar 页**仍只给三步、仍无 systemd 示例**；Dashboards 的 `requestHeadersWhitelist` 仍标 DEPRECATED、
  多租户与 workspace 仍互斥、`config/node.options` 默认仍 **4096**。
- **一处口径澄清（`jvm.options.d`）**：官方 Tar 页给的是**直接改 `config/jvm.options`**，页面**没有提** `jvm.options.d`；
  我们保留该做法但**改口径**为"**本项目的做法**（借 OpenSearch 自带 JVM 启动脚本会读 `jvm.options.d/` 的机制）"，
  并写明 **3.x 的 tar 包自带 JDK**（官方原文 "including an integrated Java Development Kit (JDK)" ⇒ **宿主不需要预装 Java**，`OPENSEARCH_JAVA_HOME=/home/opensearch/app/jdk`）。
- **保持不变**：集群名 `pm-os`、节点名 `os01`。
- **验证（六项自查，真实输出）**：① 全仓 `git grep` 两种历史路径写法 **0 命中**；② **上一代版本号（2.19.x）0 命中**；
  ③ 文档 ↔ **6 个资产**（含 3.x 引擎/查询语法/快照字段/Dashboards 项）逐项一致，并校验 6 个资产均 **UTF-8 无 BOM + LF** —— **全部通过**；
  ④ `bash -n deploy/opensearch/snapshot-backup.sh` **exit 0**；⑤ `node scripts/check-docs.mjs` **0 问题**；
  ⑥ 正文（附录 B 之前）**零 Docker 残影**。**本轮仍无任何应用代码变更**。

**★ docs-1.6 修订五（同日，权限落位写清，不新开版本号）：仓库资产落位对照表（属主/权限/`install` 命令 + 为什么 `cp` 会失败）+ 两条省事路径。**

- **真机卡点**：用 `lhim` 能把包 scp 进 `/home/opensearch`（父目录 `2775`、组可写），但**进不去 `/home/opensearch/config`**
  （该子目录 `750`，组只有 `r-x`）；`cp` 过去同样 `Permission denied`；**即使写进去，属主也是 `lhim`**，
  而服务以 `opensearch` 运行 ⇒ 读不了配置。
- **新增 §1.5 仓库资产落位对照表**（源 → 目标 → 属主:组 → 权限，覆盖 6 个资产）：
  `opensearch.yml.example`→`config/opensearch.yml`→再拷 `app/config/opensearch.yml`（`opensearch:opensearch` **640**）、
  `jvm.options.d/heap.options`→`config/jvm.options.d/`→再拷 `app/config/jvm.options.d/`（**640**）、
  `snapshot-backup.sh`→`config/`（**750**）、`opensearch.service`→`/etc/systemd/system/`（**`root:root` 644**）、
  `opensearch_dashboards.yml.example`→`dashboards/app/config/opensearch_dashboards.yml`（**640**）、
  `opensearch-dashboards.service`→`/etc/systemd/system/`（**`root:root` 644**）；
  **逐条给出可直接复制的 `sudo install -D -o <属主> -g <组> -m <权限> <源> <目标>`**（一条命令同时建目录/复制/定属主/定权限），
  而"再拷进 `app/config/`"那一步**用 `sudo -u opensearch cp`**（目标已存在，`cp` 覆盖内容并**保留 `opensearch` 属主**；
  若改用 `install`，源是 `lhim` 属主文件会把属主带成 `lhim`）。
- **新增 §1.5.1「为什么会卡 + 三条纪律」**：① 父目录 `2775` 与子目录 `750` 的差别**是故意的**（父目录开给运维搬包、子目录只给服务自己），
  且**不能**把子目录也改成组可写——安全插件拒绝"组/其他人可写"的配置、`data/` 被别账号写更危险；
  ② `cp` 默认按 `lhim` 身份创建文件 ⇒ 属主变 `lhim` ⇒ 服务读不了/写不了；
  ③ **禁止** `sudo -u opensearch cp /home/lhim/...`（`opensearch` 穿不过 `0700` 的 `/home/lhim`，必失败）——跨用户搬运只能 `sudo` 或先放两边都能读的位置。
- **新增 §1.5.2 落位核对 4 条**：`ls -l /home/opensearch/config/`、`ls -ld /home/opensearch/app/config`、
  `sudo systemd-analyze verify` 两个 unit（期望无输出）、
  **`find /home/opensearch -perm /022 -type f`（期望为空 —— 配置文件不得组/其他人可写）**。
- **新增 §1.5.3 两条省事路径**（用户反馈"权限太麻烦"）：**路线一（★推荐，不动权限）"以服务账号身份干活"**
  ——`sudo cp -r … /home/opensearch/` + `chown -R opensearch:opensearch` + `sudo -u opensearch -s` 进去后
  所有 `cp/mkdir/chmod` 都不需要 `sudo`，**完全不改权限、不削弱隔离**且比每条命令加 `sudo` 顺手；
  **路线二（可选捷径）"权限共享"**（★按用户真机反馈更正过一版）——目标是"能用终端工具把文件传进 `/home/opensearch` 及所有子目录"：
  `usermod -aG opensearch lhim` + **`find /home/opensearch -type d -exec chmod 2775 {} +`**（目录组可写 + setgid）
  + **`find /home/opensearch -type f -exec chmod 664 {} +`**（**文件也要给组写**）+ `find … -name '*.pem' -exec chmod 640 {} +`（私钥例外）
  + `umask 002`，并要求**断开终端工具重连**。
  **更正原因（已在文档里留档，免得改回去）**：① `chmod -R 2775` 一把梭会把 setgid 位与可执行位加到**文件**上（变 775），语义错；
  ② 更关键——**SFTP 覆盖已存在文件需要"文件本身可写"**（工具做的是 **open + truncate**，不是先删再建），
  只让目录可写，覆盖 `config/opensearch.yml` 这类文件照样失败。
  同时写明**三条底线**（① 配置不得组/其他人可写，给出**唯一回退点**：`chmod 640` 那两个 `opensearch.yml` +
  `find … -path '*opensearch-security*' -name '*.yml' -exec chmod 640 {} +`；并说明走了路线二后 `find -perm /022` 这条自检会命中、
  它此时是"你开了哪些文件"的清单而非体检判据；② **仍必须 root 的两处**：`/etc/systemd/system/*.service` 与 `systemctl`；
  ③ **建议保留 `data/` 例外**：`chmod -R 2750 /home/opensearch/data`，坚持全开则风险自负）
  与**不美化的代价**（等于把服务目录写权限交给 `lhim`，**一次手滑尤其批量删除就能毁掉数据**，
  把"服务账号自包含"降级为"同一运维信任级"，因此**列为可选捷径、非默认**）；
  并给出**更省事的替代**：若终端工具支持**以 root 建 SFTP 会话**，则上述 `chmod` 全都不需要（取决于是否允许 root 登录，交由用户决定）。
- **权限统一到 640/750**：文档与资产里原先散落的 `chmod 644`（配置文件）改为 **`chmod 640`**；
  `snapshot-backup.sh` 的 `755` 改为 **`750`**（组/其他人只读、不可执行）；systemd unit 保持 `root:root 644`。
- **交叉引用**：§3.3（接入配置）与 §3.4（权限收口）都加了指向 **§1.5** 的说明，避免两处各说一套。
- **同一轮补齐的 7 个"文件落位"缺口**（用户上机时正是反复撞在这类问题上）：
  1. **§12.2 真错修正**：原来在服务器上写 `deploy/opensearch/...` **仓库相对路径**直拷（服务器上没这个路径），
     且没有先建 `dashboards/app/config` ⇒ 改为"**按 §1.5 表落位 + `sudo install -D`（自动建目录）**"，
     并明确"**本节不再另立一套说法**"，消除与 §1.5 的两套口径。
  2. **`dashboards/app/` 属主**：把"**以谁解压就归谁**"提到显眼位置——解压后统一 `sudo chown -R opensearch:opensearch /home/opensearch/dashboards`，
     并给核对命令（`ls -ld dashboards dashboards/app` + `sudo -u opensearch ls …/bin/opensearch-dashboards`）。
  3. **`backup/fs-repo` 进落位表（表二）**：与 `data/` 同类（**服务要写、运维不该写**），属主 `opensearch:opensearch` / `750`，
     并说明"快照是唯一恢复来源，被手工改坏等于没有备份"。
  4. **`snapshot-backup.env`（可选覆盖配置）**：落位行 + **`600`** + 属主 `opensearch`；
     并写明**口令推荐放 `/etc/opensearch-backup.pass`（`root:root` `600`），两处只留一处**（脚本读法顺序也已注明）。
  5. **`app/plugins/opensearch-security/tools/*.sh` 可执行位**：进表（`750`，必须有 `x`）+ 核对命令
     （`ls -l …/tools/*.sh` 期望 `-rwxr-x---`，缺 `x` 就 `chmod 750`），否则 `hash.sh`/`securityadmin.sh` 跑不了。
  6. **§7.3 升级段补"属主与权限"列**：证书 / `opensearch-security/` / keystore 复制后**显式 chown+chmod**
     （`*.pem` 与 `opensearch-security/*.yml` = `640`、`keystore` = `600`、`tools/*.sh` = `750`），
     并点明"**升级后最常见的 644/640 事故就在这里**"；步骤编号顺延为 ⑥→⑨，回滚文案同步改为"⑦⑧⑨ 的反操作"。
  7. **`/etc` 下两处也进表**：`/etc/profile.d/lhim-umask.sh`（仅路线二需要，`root:root` `644`）与
     `/etc/opensearch-backup.pass`（`600`），避免读者以为"只有 `/home/opensearch` 下要管权限"。
- **给"看不懂权限"的读者的最小解释（§1.5.3 末尾）**：**"往目录里新建/拖入文件 ⇒ 看目录有没有 `w`；
  覆盖已有文件 ⇒ 还要看那个文件有没有 `w`"**，并配对照表（父目录 `2775` 能往里拖 ✅／子目录 `750` 不能拖 ❌／
  覆盖 `640` 的文件需目录与文件都有 `w` ❌ ⇒ 这正是路线二必须把文件也设 `664` 的原因／以 `opensearch` 身份则都行 ✅）。
  另把 §1.5.2 的核对从 4 条扩到 **7 条**（新增 `tools/*.sh` 的 `x` 位、`backup/fs-repo` 属主、口令与覆盖文件落位），
  并在附录 B 增第 35 条（资产落位与权限约定：官方只要求"自己保证正确"，本方案给出唯一口径）。
- **收口补的 4 条（本轮到此为止，不再扩大范围）**：
  1. **（真缺陷）cron 落位与口令可读性 → 新增 §7.2.1**：明确"**cron 用户 / 口令文件属主:权限 / 脚本读法顺序**"三者必须自洽，
     给出二选一**定稿口径**（**A＝推荐**：`root` 的 crontab（`sudo crontab -e`）+ `/etc/opensearch-backup.pass` `root:root 600`；
     B：`opensearch` 的 crontab + 口令文件改 `opensearch:opensearch 600`），并写明**配错的现象是"备份每天静默失败"**
     与验证办法（`sudo crontab -l`、`ls -l` 口令文件、**以 cron 用户身份手工跑一次 `… status`**，以及看 `journalctl -u crond` 或 `/var/log/cron`）。
     脚本内的 cron 提示与 usage 注释也同步改成"写进 **root 的 crontab**"并解释为什么。
  2. **`app/config/opensearch.keystore` 进 §1.5 表二**（**首次安装由 demo 脚本生成**）：`opensearch:opensearch` / **`600`** + 核对命令，
     并注明"升级时也要一起带过去并重设 600"。
  3. **`opensearch-security/` 目录本身**补进表二（**目录 `750`**／里面 `*.yml` `640`）+ `ls -ld` 核对，
     并说明"目录若被开成组可写 = 谁都能塞一份 `internal_users.yml`"。
  4. **日志与磁盘**：表二补 `/home/opensearch/logs/*.log` 一行（文件 `640`／目录 `750`）+ **logrotate 建议**，
     给出最小可用配置（`/etc/logrotate.d/opensearch`，含 `copytruncate` 与 `su opensearch opensearch`）与 `logrotate -d` 演练命令，
     并写明"平台方若有统一采集/轮转则以平台方为准"，与 §7.5「磁盘满 / 索引变只读」排障条目**双向交叉引用**
     （§7.5 那条现在第一步就是 `du -sh /home/opensearch/logs`）。
     另在表二补 **`backup/dist/`（仅 `.111`，归档安装包）** 一行的属主建议（`lhim:opensearch` 或 `root:root` / `755`）。
  于是 **§1.5.2 的核对从 7 条扩到 9 条**（新增 keystore 与安全目录、日志，以及 **cron 自洽性**一条）。
- **验证（六项自查，真实输出）**：① 全仓 `git grep` 两种历史路径写法 **0 命中**；② 上一代版本号（2.19.x）**0 命中**；
  ③ 文档 ↔ **6 个资产**逐项一致，并**新增 22 项落位/权限断言**（含 6 个资产的目标路径、640/750、`root:root 644`、
  `install` 命令、三条纪律、4 条核对命令、两条路线与代价、交叉引用）—— **全部通过**；
  ④ `bash -n deploy/opensearch/snapshot-backup.sh` **exit 0**；⑤ `node scripts/check-docs.mjs` **0 问题**；
  ⑥ 正文（附录 B 之前）**零 Docker 残影**。**本轮仍无任何应用代码变更**。

**★ docs-1.6 修订六（同日，不新开版本号）：主线去分支，按真机现状写成唯一路径。**

- **删除"校验"整块**（主线零命中，grep 自证）：`sha512sum -c`、`.sha512` 文件说明、Windows `Get-FileHash`、
  以及"校验和文件"表行**全部移出主线**；官方 URL 口径只在 **附录 C 第 16 条**留档，且**明确写"本次部署不做校验步骤"**、不再指向任何主线步骤。
- **删除"过程性/搬运"叙述**（主线零命中）：浏览器下载 → `/home/lhim/software` → 复制到 `/home/opensearch` 这条链路、
  `scp`、跨用户/跨家目录拷贝、`/tmp` 中转、`install -D -o/-g/-m` 与 `chown` 那一堆"怎么把包搬进去"的内容**全部删除**；
  连带删除原 §1.3（传到两台机器/做法 A、B）、§1.4（服务器侧再校验）、§1.6（占用与清理）、§3.1（解包）与相关安装包路径说明。
  主线现在从**现状**开始："**两个安装包已在 `/home/opensearch/`，`app/` 已解压完成**"（写在 §1 的第一句事实里）。
- **保留并压缩**：仓库 6 个资产的落位表仍在（§2.2），但**删掉"为什么会卡 / `cp` 会变属主 / 禁止 `sudo -u opensearch cp`"那类解释段**，
  只留「目标路径 + 属主:组 + 权限 + 状态 + 一条定权命令组（§2.3）」；权限共享那节只留**命令与结果**，代价压缩成一句。
- **权限改为唯一写法**（本机采用权限共享，用户已明确接受）：`find -type d -exec chmod 2775` / `-type f ... 664` /
  `*.pem ... 640` / `umask 002` / **断连重连**；**删除**"路线一/路线二""做法 A/B""`data/` 建议收紧"等并列说法
  （「以服务账号身份干活」与「收紧 `data/`」移到**附录 B 变更项**）。
- **安全模式唯一**：**HTTP + basic auth**（`plugins.security.ssl.http.enabled: false`）；自签 TLS 移到附录 B。
- **Dashboards 账号用 `admin`**；专用只读账号移到附录 B。
- **备份 cron 唯一口径**：`root` 的 crontab + `/etc/opensearch-backup.pass`（`root:root 600`）；**删除 B 方案**。
- **logrotate 本机自建**（保留唯一那份配置与 `logrotate -d` 演练）；"平台方统一采集"移到附录 B。
- **防火墙以 firewalld 为主线**（唯一命令组）；iptables 等价命令**只作同一代码块内的注释**，不构成分支段落。
- **每步状态标注**：（当前状态：已完成）/（待执行）/（将来执行）/（说明）/（结论）/（排障用）。
- **章节重排为一条路**：现状(§1) → 权限与落位(§2) → 宿主准备(§3) → 安装(§4) → systemd 验收(§5) → 索引与检索(§6) →
  Dashboards(§7) → 运维·含快照/cron/防火墙(§8) → AI 接入(§9) → 未确认项(§10) → 附录 A（3 节点）/
  **附录 B（变更项，原"将来加固"内容）** / **附录 C（官方逐条核对 35 条）** → 变更记录(§11)。
- **§10 未确认项只留真正待定的 11 条**（磁盘类型与容量、NTP 源、快照保留期、现网切片数、元数据排期、平台降维、
  AI 所在机器与 `-Xmx`、监控栈、`.111` 免密 ssh、Dashboards 系统索引实际名字、适配层 kNN 查询体）；
  **删掉已定死的项**（内网 HTTP、只读账号、cron 装谁、防火墙实现、是否需要 Dashboards 等）。
- **规模变化**：文档 **2261 行 → 1733 行**；附录 C 的 **35 条官方对照完整保留**（并逐条把引用改到新章节号）。
- **验证（六项自查，真实输出）**：① 主线分叉关键词 **26 个词全部 0 命中**；
  ② 文档 ↔ **6 个资产**逐项一致 + 按真机现状的描述（`app/` 已解压、`dashboards/` 尚未创建、两个包字节数）—— **全部通过**；
  ③ `bash -n deploy/opensearch/snapshot-backup.sh` **exit 0**；④ `node scripts/check-docs.mjs` **0 问题**；
  ⑤ 主线**零 Docker 残影、零 `sha512`/校验和、零 `scp`//`tmp` 叙述**，全仓上一代版本号（2.19.x）与两种旧路径 **0 命中**；
  ⑥ 全篇 **§N.M 引用零悬空**。**本轮仍无任何应用代码变更**。

**★ docs-1.6 修订七（同日，不新开版本号）：按"先收暴露面、再起服务"调顺序 + 去强调词。**

用户对上一轮报告里列的 5 点逐条做了决定，本轮全部落实：

1. **§2.1/§2.2 的权限衔接**（目录 `2775` 管"能不能放"，配置 `640` 管"最终权限"）——按用户决定保留该处理，
   并把衔接说明**写明确**：**要"覆盖"一个已存在的配置文件时，在终端工具里【先删除、再上传】**
   （目录组可写 ⇒ 有权删；删除只需要目录的 `w`，不需要文件本身的 `w`），随后重跑 §2.3 第 ① 步收回 `640`；
   并加一句指路：更省事的做法见 **附录 B.2「以服务账号干活」**。
2. **`snapshot-backup.env` 保留**（"用不到就别建"是事实陈述，不是分支）。
3. **去强调词**：§4.4 标题由"安全模式（**本机唯一写法**：HTTP + basic auth）"改为"安全模式（HTTP + basic auth）"；
   正文里"唯一写法 / 唯一口径 / 唯一路径 / 唯一一套"等**形容词全部去除**（靠内容本身表达）；
   §2 标题改为"权限模型与资产落位（**照抄即可**）"。
4. **§5.4 单节点故障演练保留在主线、按"必做"**（新装无数据，停机成本为零；它是验证"重启能自愈"的手段）。
5. **防火墙顺序倒挂修正**：**从运维章末提前到 §3.5（宿主准备内）**，即"**先收暴露面，再起服务**"；
   §3.7 跨机可达加了前提说明并引用 §3.5；§3.8 自检新增两条（`firewall-cmd --state`、`--list-rich-rules`）
   与一条 `ss -lntp | grep 9200/9300/9600/5601`（此时应无任何监听）；原 §3.5~§3.7 顺延为 §3.6~§3.8；
   正文所有 `§8.6` 引用改指 `§3.5`。
6. **附录 B 新增 B.2「以服务账号干活（不动任何权限，落位最省事）」**（含"两个 unit 仍必须 root 落位"与
   "`cp` 保留源权限位 ⇒ 补 `chmod 640`"两条必记项）；原 B.2~B.6 顺延为 **B.3~B.7**，相关引用同步改号。

- **验证（六项自查，真实输出）**：① 主线分叉关键词 26 个词 **0 命中**；
  ② 文档 ↔ 6 个资产 + 按真机现状的描述 —— **全部通过**；③ `bash -n` **exit 0**；④ `check-docs` **0 问题**；
  ⑤ 主线零 Docker / 零 `sha512`+校验和 / 零 `scp`+`/tmp`；全仓上一代版本号与两种旧路径 **0 命中**；
  ⑥ 全篇 **§N.M 与 §N 引用零悬空**。**本轮仍无任何应用代码变更**。

**★ docs-1.6 修订八（同日，不新开版本号）：修一条范围写错的命令（上机真故障）。**

- **现象**：`systemctl start opensearch` 连续失败，`journalctl -u opensearch` 报
  `Control process exited, code=exited, status=203/EXEC`，**重启计数已到 15**，且 `logs/` 里什么都没有。
- **根因（文档的错，不是用户操作错）**：`status=203/EXEC` 是 systemd **exec 阶段**失败 ⇒ 应用根本没被启动，所以应用日志必然为空。
  触发点是 §2.1 原文那条 **无范围限制** 的 `sudo find /home/opensearch -type f -exec chmod 664 {} +`：
  它**递归抹掉了程序目录的执行位** —— `app/bin/opensearch`、`app/jdk/bin/*`（自带 JDK）、
  `app/jdk/lib/jspawnhelper`、`app/plugins/**/tools/*.sh` 全部失去 `x`，JVM 与启动脚本无法执行。
- **改动（三处 + 四处顺带）**：
  1. **§2.1 权限命令改为"只作用于运行期目录"**（`config`/`data`/`logs`/`backup`），
     并在节首用醒目块**明确禁止**："**不要对 `app/`（程序目录）或 `dashboards/app/` 套用 `chmod 664`** ——
     会抹掉 `bin/`、`jdk/bin/`、`jspawnhelper`、`tools/*.sh` 的执行位，导致 systemd 报 `status=203/EXEC`"；
     并给出 `app/` 的正确权限对照与"自查小技巧"（跑完 §2.1 后确认 `bin/opensearch`、`jdk/bin/java` 仍是 `-rwxr-x---`）。
  2. **新增 §4.5「程序目录可执行位恢复」**：三条 `chmod 750`（`app/bin`+`app/jdk/bin`、`jspawnhelper`、`app/plugins` 下的 `*.sh`）
     → `daemon-reload` + `restart` → 四条 `ls -l` 复核；并给"拿不准时从 tar 包重新解压程序目录"的更稳替代（含"必须把我们的配置再拷回去"的提醒）。
  3. **§8.5 新增 `status=203/EXEC` 排障条目**：写明含义（**exec 阶段失败 ⇒ 应用日志为空，别在 `logs/` 里找**）、
     四处日志（`systemctl status -l`、`systemctl cat`、**`sudo -u opensearch /home/opensearch/app/bin/opensearch` 手工执行看真实原因**、
     `journalctl -k`/`ausearch`/`getenforce`）、四类原因与判据（① 执行位被抹掉（**本项目曾发生**）② `/home` 带 `noexec`
     ③ 路径/属主导致服务账号穿不过去 ④ SELinux/kysec 拒绝 exec）。
  4. 顺带：**§2.5 核对新增** `ls -l /home/opensearch/app/bin/opensearch`（期望带 `x`）；
     **§7.1 加同款红线**（不要对 `dashboards/app/` 套 `chmod 664`）；
     **附录 B.1 的递归 `find` 限定到运行期目录**并加"不要对 app/ 递归 chmod"的注释；
     资产侧：`opensearch.yml.example` 顶部加"权限红线"注释、`opensearch.service` 删掉已废弃的 `chmod 711 /home/lhim` 历史注释、
     `opensearch_dashboards.yml.example` 示例权限 `644` → `640`。
- **全篇"递归 chmod"范围核对**：文档里仅剩 6 处带范围限制的 `find … chmod`，全部限定在
  `config/data/logs/backup`、`app/bin`+`app/jdk/bin`（恢复用）、`app/plugins` 下的 `*.sh`（恢复用）、
  `app-<新版本>/config`（升级用）；唯一的 `chmod -R` 是附录 B.3 的 `chmod -R 2750 /home/opensearch/data`（只此一个目录，已注明）。
- **验证（六项自查，真实输出）**：① 主线分叉关键词 26 词 **0 命中**；② 文档 ↔ 6 个资产 + 按真机现状的描述 **全部通过**
  （`/tmp` 现仅出现在 §4.5 的"重新解压"做法里，共 2 处，已按白名单核对）；③ `bash -n` **exit 0**；④ `check-docs` **0 问题**；
  ⑤ 主线零 Docker 残影、零 `sha512`+校验和、零 `scp`；全仓上一代版本号与旧路径 **0 命中**；⑥ 全篇 **§N.M 与 §N 引用零悬空**。
  **本轮仍无任何应用代码变更**。

**★ docs-1.6 修订十（同日，不新开版本号）：补 Dashboards 侧的同源漏洞（`status=126`）。**

- **现象（真机）**：`opensearch-dashboards.service` 启动失败，journald 报
  `bin/use_node: cannot execute: Permission denied` → `Main process exited, code=exited, status=126/n/a`，**重启计数 22**。
- **根因（与修订八同源）**：之前那次**全量 `chmod 664` 也波及了 `dashboards/` 树**，而手册只给了 OpenSearch `app/` 的执行位恢复
  （步骤 4 后的恢复块 / 参考 B 的 203 条目），**没有 Dashboards 的恢复块**，也没把 `status=126` 这个签名写进排障表。
- **三处补充**：
  1. **步骤 17 新增「恢复块 A · Dashboards 程序树执行位恢复」**（与 OpenSearch 的恢复块同格式）：
     `chmod 750` 三条覆盖 `dashboards/app/bin`、`dashboards/app/node/bin`、`dashboards/app` 下 `*.sh`
     → 核对 `ls -l …/bin/use_node …/node/bin/node`（期望 `-rwxr-x---`）
     → **`systemctl reset-failed opensearch-dashboards` + `restart`**（先 reset-failed 清掉重启计数，否则可能卡在 start-limit）；并给期望输出/判据/失败怎么办。
  2. **排障表新增两行**：`status=126/n/a`（含 `bin/use_node: cannot execute: Permission denied`）与 `status=203/EXEC`（原文已有，本轮改写为覆盖两个服务的统一条目），
     写明 **exec 阶段失败 ⇒ 应用日志为空**、四处取证（`status -l`、`cat`、**以服务账号手工执行**、`getenforce`/`ausearch`/`journalctl -k`）与**四类原因**，
     并注明"**`126` 与 `203` 都是权限位问题，修法一致**"。
  3. **步骤 4 权限红线扩写**：`chmod 664` **只允许作用于 `config`/`data`/`logs`/`backup`**；
     **`app/` 与 `dashboards/app/` 两棵程序树都不得递归**；并给出**一次核对两棵树的判据**：
     `ls -l /home/opensearch/app/bin/opensearch /home/opensearch/app/jdk/bin/java /home/opensearch/dashboards/app/bin/use_node /home/opensearch/dashboards/app/node/bin/node`
     （**四个都必须带 `x`**，只要一条不带就按对应恢复块修）。
- **验证（自查真实输出）**：① 主线记录性词 **0 命中**；② 主线分叉关键词 **0 命中**；
  ③ **20 步四件套齐全 20/20**，均有耗时估计；④ 文档 ↔ 6 个资产 **全部通过**（含新增的 Dashboards 恢复块与 126/203 条目、两棵树四文件判据）；
  ⑤ `bash -n` **exit 0**；⑥ `check-docs` **0 问题**；⑦ 主线零 Docker / 零 `sha512`+校验和 / 零 `scp`；⑧ **§ 引用零悬空**。
  **本轮仍无任何应用代码变更**。

**★ docs-1.6 修订九（同日，不新开版本号）：由"部署记录"改写为傻瓜式部署手册。**

用户定位性意见：**"这个文档是部署文档，不是部署记录，我要能够傻瓜式的按照步骤就能部署的。"**
即：任何人拿到一台新机器 + 安装包，照着 1→N 就能一路部署成功，而不是"记录某台机器已经做到哪一步"。

- **一、去掉"部署记录"的一切痕迹（主线内）**：
  ① **删除全部状态标注**（`（当前状态：已完成）`/`（待执行）`/`（当前状态）`/`（说明）`/`（结论）` 等），主线 grep **0 命中**；
  ② **"现状描述"改造为前置条件**：新增 **§1 前置条件（开始前必须具备）**，6 条各给"怎么达成"的命令 ——
  机器/架构/内存/磁盘/Docker 检查、**专用账号 `opensearch` 从零建立**（`useradd -m -d /home/opensearch -s /bin/bash` + `passwd -l` + `chown` + `usermod -aG opensearch lhim`）、
  目录结构建立、两个安装包位置、资产（`$ASSETS`）位置、端口与跨机可达；并逐条给出**期望输出 / 完成判据 / 失败怎么办**；
  ③ **"解压 tarball 到 `app/`"恢复为正式步骤**（步骤 3：`sudo -u opensearch tar -zxf …` → `mv … app` → `chown -R` + 期望输出），**新机器可从零照做**；
  ④ **删除主线回溯性文字**（"本项目已实际踩过这个坑"、"用户真机现状"、"（本次部署不做校验）"等），只保留在附录与变更记录里。
- **二、把主线做成"傻瓜式"（每步四件套）**：**20 步全部统一为**
  `① 执行（可直接复制的命令块）` → `② 期望输出（逐字给出 `"status" : "green"`、`-rwxr-x---` 等）` → `③ ✅ 完成判据` → `④ ✗ 失败了怎么办`（指向 §4.5 或参考 B · 常见故障的具体条目），
  **每步带耗时估计**（如"约 1 分钟""首次约 30~90 秒"）；**每步只依赖它前面的步骤**；
  顶部新增 **"怎么用这份文档"（3 条）** 与 **总步骤清单（可勾选，20 步 + 耗时）**；
  危险动作（递归 `chmod`、防火墙、停机演练）在命令块**紧邻处**加 🔴 警告。
- **三、章节重排（一条路走到底）**：
  `§1 前置条件` → `步骤 1..20`（核对 → 建账号 → 解压 → 权限 → 内核/资源 → swap → `/home` → 防火墙 → 时间 → 落位 → 官方脚本 → 接配置 → 启动 → 验收 → 重启与演练 → 建索引 → Dashboards → 快照与 cron → AI 接入 → 第二台）
  → `运维速查` → `参考 B · 运维与排障`（原运维章，含 9.5 常见故障与 203/EXEC 条目）→ `参考 C · 未确认项`
  → `附录 A`（3 节点）`附录 B`（变更项）`附录 C`（官方 35 条）`附录 D`（结论与前提）`附录 E`（索引与检索设计）→ `变更记录`。
- **四、保留不动**：附录 A / 附录 B / 附录 C（35 条）/ 变更记录 / 常见故障排障表 / 运维速查 —— 均为参考与排障，主线**不依赖**它们也能走完。
- **验证（自查真实输出）**：① 主线记录性词（已完成/待执行/当前状态/已解压完成/已实际踩过/用户真机现状）**全部 0 命中**；
  ② 主线分叉关键词 **0 命中**；③ **20 步四件套齐全 20/20**（脚本逐步骤核对 ①②③④），且每步都有耗时估计、顶部有"怎么用"与清单；
  ④ 文档 ↔ 6 个资产逐项一致（含落位表 6 条目标路径、版本 3.1.0、包字节数、`cluster.name`/`node.name`/`http.port`、REST TLS 关、heap 8g、索引参数、系统索引、cron 装 root、chmod 范围、§4.5、203/EXEC、解压步骤、账号建立命令）；
  ⑤ `bash -n` **exit 0**；⑥ `check-docs` **0 问题**；⑦ 主线零 Docker、零 `sha512`/校验和、零 `scp`（主线 `/tmp` 仅出现在解压做法里）；
  ⑧ **§ 引用零悬空**。**本轮仍无任何应用代码变更**。

---

### ai-1.2.0 — 接入 OpenSearch 向量后端 + P0 模型调用参数与效率优化（AI 服务，2026-09-23）

- **需求来源（两条，都不是"想做就做"）**：
  ① **接入 OpenSearch，提高检索规模与 HA 前提**——《AI模型调用与任务队列评估》给出的量化触发点：进程内 `local`
  在 **≈4,000 切片**时给每次提问 **+1s**、15,000 切片 **+3.8s**，且 `local` 是**进程内状态 ⇒ AI 服务必须单实例**；
  "需要第 2 个 AI 实例做无中断检索"这一刻，`local` 立刻不成立。部署侧的落地文档见
  [`docs/OpenSearch部署与接入.md`](docs/OpenSearch部署与接入.md)（单节点 `.110` 生产 / `.111` 冷备演练，`docs-1.6`）。
  ② **P0 五项**（同一份评估文档）：超时对齐、重排降配、向量化并发化或前移、队列满**诚实失败**、每模型信号量。
- **改动四块**：
  - **① OpenSearch 接入（`VEC_BACKEND=local|opensearch` 可切换）**：传输层用**原生 REST**（HttpClient + Jackson），
    **不引入 OpenSearch Java Client**（本轮 `pom.xml` 唯一新增依赖是 `spring-boot-starter-actuator`，用途见第 ③ 块）；
    索引**自建**（3.x 写法：`knn_vector` **4096** 维 / `space_type: cosinesimil`（只写字段层）/ `engine: lucene` /
    HNSW `m=16`·`ef_construction=100` / **1 主分片 0 副本** / `refresh_interval: 1s`），
    另有 **6 个 keyword 元数据字段**（`attachment_id`/`project_id`/`phase_code`/`security_level`/`doc_type`/`source`）
    与 `created_at`(date)；`_bulk` **幂等**（`_id = chunk_key`，重写不翻倍 ⇒ 可中断续跑）；kNN 带**作用域硬 filter**
    （`doc_ids`/`attachmentIds`/`projectIds` **在召回前**收窄，元数据缺失 = 越界，不做通配）；
    `POST /admin/reindex` **全量重建**（`?docIds=` 局部 / `?force=` 全重写，`GET /admin/reindex/{jobId}` 给
    `processed_docs`/`total_docs`/`failed_docs`/`skipped_docs`/`written_chunks`/`eta_seconds`/`percent`，
    `POST …/{jobId}/cancel` 协作式取消）；集群不可达**不回退**（HTTP **503 + "向量后端不可用"**，不退回 `local`、
    不退回关键词检索）；`/health?with_vec=true` 的 `vec.ok` 取**集群真实探活**并另给 `upstream_ok`。
  - **② P0 七条**：分级超时（chat **150** / embed **30** / rerank **20** / ocr **600**，调用方预算 `AI_CALLER_TIMEOUT=180`，
    `StartupDiagnostics` **启动校验超时链**并逐条打印结论）+ 修 `LlmClient` **忽略入参**（原先无论传什么都用同一个超时）
    + **每模型信号量**（chat 4 / OCR 12 / embed 8 / rerank 2；交互式等 permit **1.5s**，批量 **60s**）+ **幂等重试退避**
    （只重试 OCR/embedding/检索；1s → 4s + 抖动 0~250ms；chat 永不重试）+ **向量化前移**（新增 `DocIndexer`：
    从"问答请求线程里按 32 条**串行**惰性补算"改为**解析入库时就完成**，批 **32** / 并发 **8** / 共享 embed 信号量；
    查询侧仍在补算会记 `ai.vector.lazy_backfill`，正常应为 0）+ **OCR 并发语义修正**（原 `min(12, ceil(页数/8))`
    对 8 页文档恒为 1 ⇒ 等于没并发；改为**共享 12 的池 + 动态批页** `min(页数, ceil(页数/在飞上限))`）+
    **队列满诚实失败**（429 + 任务标 `FAILED` + 清 `work/tmp`，**不再出现"永久 QUEUED"与残留半成品**）。
  - **③ 指标（P0-6）**：`spring-boot-starter-actuator` + micrometer（**只用进程内 `SimpleMeterRegistry`**，不加端口、
    不接外部中间件）——`ai.model.requests`/`latency`(P50/P95/P99)/`inflight`/`queue.depth`/`rejected`/`retries`、
    `ai.upload.queue.depth`/`rejected`、`ai.retrieval.rerank.pairs`、`ai.vector.embedded`/`indexed`/`lazy_backfill`、
    `ai.retrieval.scope.filtered`、`ai.opensearch.retries`；`/health?with_metrics=true` 给"四模型 × 5 项 + 队列 +
    重建状态"的聚合视图。**如实登记一处取舍**：评估文档 §7 曾把 actuator/micrometer 列为"⏸ 推迟（零新增依赖取向优先）"，
    本轮按 P0-6 的明确要求**推翻该推迟**（代价：多 2 个 jar）。
  - **④ 召回档位 + top-k 一致性比对工具**：`RETRIEVAL_RECALL_MODE=baseline`(默认 **50**) / `low`(**25**，启动打 WARN
    说明已降配) / `custom`——**默认未改**，改默认前必须用 `eval/smoke-30.json` 跑降配前后对比；
    新增 `com.pmgt.ai.tools.TopKCompareTool`（在 `src/test/java`、**不带 `@Test` ⇒ 不进 `mvn test`**）：
    同一批切片 + 同一组 query 分别跑 `local` 与 `opensearch`，打印两边 top-k 的 **重合率**、top-1 一致数与耗时
    （口径：**相似度数值不必逐位相等，要求一致的是排序/集合**）。
- **刻意偏差与已对齐项（如实登记，不是笔误）**：
  ① **唯一的刻意偏差**：kNN 的 **`k` 用"生效召回数"**（baseline 50 / `low` 模式 25）而**不是** `RETRIEVAL_TOP_K`——
  若拿 `TOP_K=5` 去做 kNN 候选数，精排就只剩 5 个候选、**等于废掉精排**；
  ② **原与手册不一致、已在 `docs-1.7 修订十二` 对齐（现不再构成偏差）**：`ef_construction = 100`
  （HNSW 官方默认，够用且建索引更快）与 `refresh_interval = 1s`（OpenSearch 默认，为了"灌完即可查"）——
  原先手册 步骤 16 / 附录 E 的手工示例写的是 `128` 与 `-1`，照它建出来的索引与代码**参数不一致且因
  `ensureIndexOnce` 只在 404 时才 `PUT`、已存在不动而永远不会被自动纠正**；现已把手册两处改成与
  `OpenSearchIndex.indexDefinition()` **逐字一致**，并写明"以本节参数为准；建错了要 `DELETE` 再重建"。
  ⇒ 步骤 19 保留了"手工建索引与代码自建**只能选一种**、参数必须一致、索引已存在**不会被改写**"这条运维红线。
- **未验证清单（如实登记）**：① **未对真集群（`10.254.212.110:9200`）跑过建索引 / `_bulk` / kNN 查询**
  （开发机无法访问内网）⇒ 步骤 19 ①-1/①-3/①-4 必须在真机上执行一次；② **top-k 一致性比对工具未真跑**；
  ③ **P1-3（`/chat` 请求级 `doc_ids` 一路接到 `Tools`/`QaService`）未做**：本轮只把"模型给的 `doc_id`"下推成硬 filter，
  请求级 `doc_ids` 的端到端串联需要动 `ToolAgent`/`QaService` 签名，不在 P0 范围；④ 召回降配**只提供档位、未改默认、未跑评测**；
  ⑤ **hybrid（BM25 + kNN 融合）未启用**（默认 false：两路量纲不同，且没有评测基线）；⑥ **OCR 600s 超出调用方 180s
  预算是有意例外**（批量链路、不占问答槽位）；⑦ 队列满的诚实失败**只做了服务层验证**（单测覆盖 429 + FAILED +
  临时文件清理），未在真机压测下验证端到端表现。
- **验证**：`mvn -o test` ⇒ **Tests run: 180, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS**
  （新增 `OpenSearchIndexTest`、`DocIndexerReindexTest`、`EmbedBatcherTest`、`RetrievalBackendParityTest`、
  `ModelRegistryTest`、`LlmClientTimeoutTest`、`OcrQueueConcurrencyTest`、`UploadTaskQueueFullTest` 等）；
  `ai-backend/README.md` 同步更新（接口表、指标读法、重建索引、比对工具、运维边界）；
  `ai-backend/.env.example` 同步新增 `VEC_BACKEND`/`OPENSEARCH_*` 与并发/超时/重试键（口令留空）。
- **发布包与镜像**：`dist/pm-ai-release-v1.2.0/`（`pm.sh` + `ai/{docker-compose.yml,.env.example,服务器部署步骤-ai.txt,
  releases/pm-ai-images-aarch64-v1.2.0.tar.gz}`）；镜像 **aarch64**（沿用既有 AI 发布线）。
  ⚠️ **架构待确认**：若应用服务器 `10.254.208.86/87` 的 `uname -m` 是 `x86_64`，需按 **amd64** 重出一次
  （`scripts/make-release.ps1 v1.2.0 -Project ai` 加对应平台参数即可，一条命令）。
- **文档同步**：`docs/OpenSearch部署与接入.md`（**步骤 19 写实**：5 步固定顺序 + 不静默降级 503 + 建索引两种方式的取舍 +
  只接一台的风险提示；**参考 C** 把向量后端选型与 `.110` 生产/`.111` 冷备落到"已定死"，第 3/4/6/7/10 条按现状改写；
  顺带修掉 8 处失效的步骤引用与 2 处过期表述，见该文档 `变更记录` 的 `docs-1.7` 行）。
  **`docs-1.7 修订十二` 收掉最后一处分叉**：步骤 16 与 附录 E 的手工建索引参数改为与代码一致
  （`ef_construction` 128→**100**、`refresh_interval` `-1`→**`1s`**，并加"以本节参数为准；索引一旦存在不会被服务改写，
  建错了要 `DELETE /pm-ai-chunks` 再重建"），步骤 19 ①-1 的取舍说明随之简化；附录 C 第 28 条由"刻意偏差"改为"已完全对齐"。

---

### docs-1.8 — 全链路 RAG 完整性评估：五环节现状 / 主流对照 / 缺口清单 / 技术栈评判（2026-09-23，**只评估、只写文档**）

- **起因（用户原话）**："我想先评估一下，整个流程，解析，清洗，切分，召回，生成。流程是否完善，企业主流的流程，当前项目是否有缺失，技术栈是否合理？先评估完再研究如何完善。"
  ⇒ 交付物是**一份评估文档**，`ai-backend/**`、`backend/**`、`frontend/**`、`scripts/**`、`deploy/**` **一行未动**。
- **新增** [`docs/RAG流程完整性评估.md`](docs/RAG流程完整性评估.md)（§1~§7 + 附"证据清单"），结构与要点：
  1. **§1 结论摘要**：五环节评级 —— **解析 B**（PDF 双路 + 版面块 + 页级溯源扎实，但只吃 PDF 与 7 种图片、混合型 PDF 不逐页回退、无解析质量抽检）；
     **清洗 D（无独立清洗阶段）**；**切分 C**（500 字、不跨页、无重叠、无父子块、无结构感知）；
     **召回 B-**（双路 + 硬作用域下推 + cross-encoder 精排齐全，但请求级 `doc_ids` 未下推、OpenSearch 路径每次检索全量 `_bulk`）；
     **生成 B**（提示词六条铁律 + 结构化引用 + 拒答口径，但 `/chat` 无上下文预算、答案侧无机器校验）。
     每条评级都写了**可证伪判据**（达到即升级）。另给"最该做的 5 件事"排序。
  2. **§2 现状盘点**：逐环节把行为落到 `文件:行`，例如 `DocumentReader.java:107-111`（整份平均每页字符 <50 才走 OCR）、
     `PlatformOcrClient.java:198-238`（共享池 + 动态批页）、`DocStore.java:42,205-249`（500 字/不跨页/无重叠）、
     `RetrievalService.java:228-245`（作用域先过滤）、`:320-344`（精排）、`Prompts.java:204-277`（问答六条铁律）、
     `QaService.java:52,303-312`（6 轮历史）、`ToolAgent.java:48,269-289`（8 轮 + 到顶禁用工具再问）。
  3. **§3 与主流企业实践对照表**（本文核心）：解析 7 项 / 清洗 7 项 / 切分 7 项 / 索引与召回 10 项 / 生成 9 项 / 横切 8 项，
     逐条判 **有 / 部分 / 无 / 不适用（给理由）** + 差距 + 影响 + 优先级。含"逐页判定 vs 整份判定""父子块 small-to-big""RRF/归一融合"
     "history-aware 查询改写""答案质量评估（人工 + LLM 评委）""多租户/密级隔离"等主流能力的逐条落点。
  4. **§4 缺口清单 22 条**：现象（代码依据）→ 影响（质量/吞吐/成本/可运维）→ 建议做法 → 工作量级 → 优先级；
     工作量一律标 **估算（假设：熟悉本仓库的 1 名后端工程师、含自测与文档同步）**。
  5. **§5 技术栈评判**：逐项给"合理 / 勉强 / 该换"与**该动的时机**；并给 **9 个量化触发点**（T1 切片 ≥4,000 必须换后端、
     T2 问答 >0.16 QPS、T3 向量规模触顶需加**分片并重建**（该文档 §5.2：**估算约 30~50 万向量**）、T4 需要第 2 个实例、T5 文档 >500 份、
     T6 任何切片/召回/提示词改动前必须有页码标注与 recall 基线、T7 清洗一改切片键即全失效需带 `clean_rev`、T8 OCR 排队、T9 入站鉴权）。
  6. **§6 明确不做/推迟 13 项**（不引 LangChain4j/Spring AI、不引 OpenSearch Java Client、不引 Redis/MQ、NL2SQL 永不做、
     GraphRAG 不做、第二 OCR 引擎不做、降维推迟、答案结构化推迟等，各给理由与触发条件）。
  7. **§7 未确认项 14 条**，每条附"怎么确认"的可执行方法（例如：平台 `markdown` 是否含表格/水印 → 打一次 `/ocr/file` 把
     `results[].markdown` 落盘比对；`SCANNED_CHAR_THRESHOLD` 是否死配置 → 起服务设 200 看 `/health` 的 `config.scanned_char_threshold`）。
- **本轮首次登记的代码级发现（本文件之前没人写过）**：
  ① **查询侧全量 `_bulk`**：`RetrievalService.java:440-442` 在**每次 `search()`** 里把当前作用域**全部**切片 `upsertAll` 回索引
  （`_id` 幂等故结果正确，代价巨大；作用域为空时即全库）——**估算（假设：15,000 切片 × 38,333 字节/切片，字节数取自《AI模型调用与任务队列评估》§3.1 真机读数）≈575 MB/问**，列为 **P0**；
  ② **请求级 `doc_ids` 未下推**（`QaService.java:206` 只进提示词、`Tools.java:183` 只用模型给的 `docId`；`RetrievalService.java:200-202` 自认未做），叠加"AI 服务无入站鉴权"⇒ 软作用域 + 网络可达即全库，列为 **P0**；
  ③ **混合型 PDF 无逐页回退**（`DocumentReader.java:107-111,121-139`）：整份按平均字符二分，文本层路径**不**对空页补 OCR ⇒ 静默丢页，列为 **P0**；
  ④ **`/chat` 无上下文预算**：`LLM_MAX_INPUT_CHARS` 全仓只有 `AnalyzeService.java:78` 一个使用点（grep 证明），8 轮工具循环的消息列表无上界；
  ⑤ **`/chat` 答案侧无机器校验**：`Checks.verifyNumbersInSource` 只被 `AnalyzeService.java:146` 调用；答案里的 `[n]` 与 `citations` 表**无人比对**；
  ⑥ **`hybrid=true` 会混量纲**：`OpenSearchIndex.java:558-572` 的融合结果被当成 `vecScore`，而 `RetrievalService.java:299,685-692` 按分数降序选候选 ⇒ BM25（5~30）会挤掉 cosine（0~1）。**默认 false 是正确处置，但开启前必须先归一**；
  ⑦ **密级不是作用域维度**：`DocMeta.securityLevel` 有字段、`security_level` 有 mapping（`OpenSearchIndex.java:222`），但 `SearchScope.java:23` 只有 `docIds/attachmentIds/projectIds`；
  ⑧ **`SCANNED_CHAR_THRESHOLD` 疑似死配置**：`.env.example:59` 有这个键，但 `application.yml` 里没有对应的 `${...}` 映射（yml 只认 48 个变量，该键不在其中）⇒ 按 Spring 宽松绑定规则它绑不到 `ai.ocr.scanned-char-threshold`（**未实机验证**，已登记为未确认项）；
  ⑨ **可观测性只覆盖速度/容量**：micrometer 指标齐全，但**没有**零命中率、空引用率、重排降级次数、拒答率，日志也无 traceId（`application.yml:139-141` 的 pattern 与注释不符）。
- **与既有四份评估的边界（已在文首写成表格，确保不重复结论）**：《AI工具集与检索编排评估》（工具与框架取舍）、《AI模型调用与任务队列评估》（超时/并发/队列与 OpenSearch 触发点）、
  《AI前端与集成方案》§11（受控查询契约）、《OpenSearch部署与接入》附录 E（索引与查询设计）——本文只引用它们的结论与数字，新增的是**五环节完整性视角**与上述 9 条代码级发现。
- **文档同步**：`ITERATION.md`（本行 + 本明细段）、`docs/政府信息化项目管理系统-设计方案.md` §0 增一行、根 `README.md` 版本行加 `docs-1.8`、
  `docs/README.md` 权威文档表登记本文；`node scripts/check-docs.mjs` **0 问题**（23 份 Markdown，总表行数 == 明细段数）。
- **未改任何应用代码**（`ai-backend/**`、`backend/**`、`frontend/**`、`scripts/**`、`deploy/**` 一行未动）；文档提交按显式路径暂存，未使用 `git add -A`。提交：`793846c`（文档本体）、`6d29946`（总表哈希回填）、`71ee908`（口径收紧：编码归一影响描述、T3 估算假设与既有"50 万加第 3 台"口径显式区分）；此后仅哈希登记类小提交不再逐条列出。

---

### docs-1.9 — RAG 流水线设计与并发管理（2026-09-23，**只设计、只写文档**；参考 data-classification 的交互）

- **起因（用户四条原话）**：① "OpenSearch Java Client 可以接入，另外 http 调用，引入 okhttp 等框架会不会效率更高或者更加便于管理。"② "整个流程可以在解析任务里以**任务流**的方式展示……可以做**落库**，做到**可溯源**。"③ "我觉得还可以参考 data-classification 这个项目，也做一个**统一的并发管理模块**。"④ "整个解析到生成的流程环节一定要**设计好**，这是核心关键。"；另经补充：参考项目在 `C:\Users\yjc\Desktop\data`（项目名 data-classification，**不在本仓库内**），用户要求"参考**交互**，逻辑**按本项目实际**"。
- **新增** [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md)（§1 结论摘要 → §2 技术选型 → §3 流水线总体设计 → §4 任务流/落库/可溯源 → §5 统一并发管理 → §6 分期与验收 → §7 不做/推迟 → §8 未确认 + 附证据清单），**目标是把《RAG流程完整性评估》的 G1~G22 与 T1~T9 在设计层面闭合**。
- **§2 技术选型（用户第 1 条）**：现状盘点 6 个 HTTP 出口（`LlmClient:83-86`、`VecClient:84-87`、`PlatformOcrClient:153-157`、`BizQueryClient:74-78`、**`OpenSearchIndex:782-786`（每次现建 HttpClient，连接池完全不生效）**、主系统 `AiHttpClientConfig:31-62`）；四方案逐项对照（连接池/HTTP2/超时/重试/上限与排队/可观测/错误语义/依赖体积/离线影响/与 `RestClient` 结合/迁移代价/镜像影响）。**分侧结论**：平台四模型调用**保持 JDK HttpClient**（R1~R5 五条可证伪理由）；OpenSearch **保持原生 REST 但改单例 HttpClient**（估算每次问答省 15~30ms，真正收益是连接与线程不再重建）；`opensearch-java` **不引、留开关**，触发条件（动作数 >10~15、需要 PIT/scroll/聚合树、真集群验证完成）。**§2.3 传递依赖清单（POM 实测）**：14 个新增 artifact（httpclient5 5.5、httpcore5 5.3.4、httpcore5-h2 5.3.4、commons-logging 1.3.5、jsr305 3.0.2、parsson 1.1.7、jakarta.json.bind-api 2.0.0、jakarta.annotation-api 1.3.5、yasson 2.0.2、jackson-core/databind 2.18.3〔会被 Spring Boot BOM 压回 2.17.x〕等），**估算合计 8~9 MB**，建议钉 **3.1.0**（与服务端同轨，不用 3.10.0）。
- **§3 流水线总体设计（用户第 4 条）**：三条不变式（主系统事实源 / 可重建物不进库 / 越界即空）；**七阶段契约表**（PARSE·CLEAN·CHUNK·EMBED·INDEX·RETRIEVE·GENERATE 逐项：输入/输出/产物/状态机/幂等键/失败与重试/可续跑边界/超时预算）；**清洗阶段落地**（`TextCleaner`/`CleanContext`/`CleanResult`/`CleanRule` 接口 + 8 条规则 C1 页眉页脚（≥50% 页重复且页首页末）/C2 纯页码行/C3 空白与不可见字符/C4 断行与连字符/C5 表格标记/C6 段级去重（只标记）/C7 编码归一/C8 水印（默认关）；**`clean_rev` 三层防护**：L1 切片键增补版本段、L2 向量缓存加 `clean_rev/chunk_rev`、L3 按 rev 差异只重建受影响文档；任务级幂等键 `task_key = sha1(内容 sha1 + pipeline_rev)`）；**切分改进**（标题/条款结构感知、表格整块不截断且超长按行切并重复表头、10~15% 按句回退重叠、父子块 small-to-big 与 `read_page` 分工（先做"同页邻块"）、切片元数据补齐 11 个字段含 `order_in_page`/`parent_key`/`section_path`/`is_table`/`clean_rev`/`char_len`）；**解析改进**（混合型 PDF **逐页 OCR 回退** + `work/pages` 页级缓存；docx/xlsx/txt/md/pptx 扩展路径与页码近似口径；表格结构化"先不做"）；**召回与生成**（请求级作用域硬下推并与模型给的 docId **取交集**、密级进 `SearchScope`、history-aware 改写（预算 8s，失败降级并留 trace）、**RRF（k=60）免归一**作为 `OPENSEARCH_HYBRID` 打开前提、query embedding（LRU 512）/rerank（5min）/检索（60s）/biz（30~60s）四类缓存与检索总预算 5s、`/chat` 上下文预算 `CHAT_MAX_INPUT_CHARS=60000` 的四级裁剪与"必须告知"、答案侧 `verification`（`[n]` 合法性 + 数字可溯源 + 拒答一致性 + 降级可见））；**两张 mermaid 时序图**（入库图标出 7 个落库点与 4 个产物、问答图标出 9 个落库点与溯源链）；超时预算总表（新增 CLEAN 60s / CHUNK 30s / 检索总预算 5s / 单次问答 170s；不变式 `chat 150 < 调用方 180 ≤ nginx 300`）。
- **§4 任务流 + 落库 + 可溯源（用户第 2 条）**：五条设计原则（不建第二事实源 / DB 只存摘要 / 阶段行 upsert / 产物行只 INSERT / AI 不直连库）；数据模型全景（1 张扩列表 + 3 张新表 + 体量 **估算 ≈18 万行/年、200~400 MB/年**）；**V15 DDL 草案全文**——`attachment_ai_task` 扩 **13 列**（content_hash / pipeline_rev / clean_rev / task_type / priority / stage_key / stage / stage_done / stage_total / elapsed_ms / attempts / claimed_by / claim_expires_at）+ 新建 `ai_pipeline_stage`（run_id+stage_key 唯一；status/counters_json/item_failed/attempts/error_msg）、`ai_pipeline_artifact`（只 INSERT、artifact_type 五类：page_digest/clean_diff/chunk_manifest/embed_batch/index_result；content_json ≤4000 摘要 + object_key + sha1）、`ai_pipeline_event`（状态迁移审计），全部 **`VARCHAR(n CHAR)` + 表外 `COMMENT ON`**，与 V13/V14 同风格；**"扩展还是新建"的结论与迁移/兼容/回滚策略**（存量行 `stage_key` 为空显示"历史任务"；AI 服务不升级时视图退化为单阶段；回滚=停止写入+隐藏视图；表数 16→19）。**状态机**：任务级 7 状态（新增 `PARTIAL` 部分成功 / `CANCELLED` / `DEAD` 重试耗尽）+ 11 条合法迁移 T1~T11（含 `RUNNING→QUEUED` 的 stale claim 回退、`DONE→QUEUED` 的 rev 变更重建）；阶段级 7 状态；"可续跑/不可续跑/部分失败"三概念落库表现；**"谁写状态"四方案取舍**（AI 服务写库**否决**、**AI 回调主系统接口为主 + 主系统轮询投影兜底**、终态以主系统为准、AI 侧不建持久队列）。**可溯源**：6 跳链路（答案 `[n]` → `citations[].index` → **新增 `chunk_key`** → 切片清洗后全文 → 原始页 markdown → 附件第 N 页）+ **7 个新增接口 API-1~API-7**（任务流、产物详情、两次解析对比、按 chunk_key 取切片、页级原文/清洗后对照、质量看板、并发队列看板）+ 契约纪律（只增不改、走主系统代理、AI 不可达 503 不降级）。**解析质量六项指标** Q1~Q6（页级空页率 / OCR 回退页数 / 置信度与标签分布 / 清洗剔除量 / 切片数与表格数 / 向量化成功率）与阈值。**保留与清理**（DB 只存摘要、全文留 `work/`、阶段/产物 180 天、事件 90 天、`chunk_manifest` 永久、重建按 `pipeline_rev` 三级跳过；"只改清洗规则"的重建 **估算 ≈20s**）。**§4.9 交互借鉴**：data-classification 的顶栏常驻入口+角标、作业中心（进行中/历史两段、3s 轮询、"只看失败"）、进度弹窗（6 个 Statistic + 进度条 + 项级明细 + 1.5s 轮询 + 失败项一键重试 + 排队 `Alert` 说明"在等什么/等了多久"）、`JobProgressModal.tsx` 的"打开即重取全量"与"结束后停轮询"、协作式取消语义；并给出落到本项目的**组件层级/字段清单/刷新策略**（antd 5 现有组件、不引新库）。
- **§5 统一并发管理（用户第 3 条）**：**两级分层**——L1 业务并发（新，AI 服务内 `PipelineGate`：谁可以开始干活）→ L2 模型并发（已有 `ModelRegistry` 4/12/8/2，一次模型调用的在飞上限），**单向获取**；**三条防死锁/防优先级反转硬规则**（H1 先 L1 后 L2 且 L1 permit 全程持有、H2 排队不嵌套、H3 优先级只在 L1）；五类通道（INTERACTIVE / BATCH.PARSE / BATCH.REINDEX / BATCH.EMBED / BATCH.EVAL）与准入上限、`effective_priority = 任务优先级 + 类型权重 + 老化`、抢占"做与不做"（L2 不抢占改为 1.5s 快速失败；唯一"抢占"= 交互式队列非空时暂停派发新 BATCH.PARSE 的软闸门）；按 `project_id` 加权轮转 + 每项目在飞上限与公平性判据；全局限流（网关总在飞 ≤26 作交叉校验）与**诚实失败硬纪律**；10 项 `ai.gate.*` 指标；热更新取舍（只支持优先级/老化/软闸门的运行时调整，其余重启生效）；**落地位置**（持久化与领取在主系统 DB、L1/L2 在 AI 服务、看板由主系统聚合）+ 双机 LB 的 DB 抢占式领取与 30s 心跳续约；**验收指标 V1~V8**（导入 100 份问答 P95 劣化 ≤20%、0 条永久 QUEUED、按项目公平性、交互式 P99 ≤1.5s、30 分钟混合压测无死锁与 permit 泄漏、优先级与不饥饿、重建不阻塞问答、取消 5s 内释放）。**§5.10 参考项目对照**：逐项（R-1~R-14）列出它的做法与来源文件 → 可借鉴 → 必须改造（14 行对照表），并给 **8 条否决清单**（照搬"每环境线程池+同大小信号量"会与 `ModelRegistry` 重复限流且新池无指标；照搬 `CallerRunsPolicy` 会让 Tomcat 线程去跑几分钟 OCR、破坏"先返回后解析"；照搬 JPA 运行期建表会破坏"V*.sql + YashanMigrationRunner 是 schema 唯一入口"；照搬它的并发数字/串行到交互式；等等）。
- **§6 分期实施与验收**：**P0-α 正确性三修（3~5 人日）**= 删查询侧全量 `_bulk`（G2）+ 作用域硬下推（G3/G17）+ OpenSearch 共享 HttpClient 与重试判定收紧；**P0-β 任务流落库与可溯源（8~12 人日）**= V15 + 阶段产物上报 + 任务流视图 + 溯源 + 质量看板；**P1-α 清洗与切片改进（10~16 人日）**（前置 G8 评测基线）；**P1-β 统一并发管理（8~12 人日）**；**P2 解析扩展与召回/生成改进（15~25 人日）**（含借鉴"只重跑失败页"作为新增选项）；每项都给了**可测验收指标**；并写明每期的文档同步与提交纪律（不用 `git add -A`、`git commit -F`、回填哈希、`check-docs` 0 问题）。
- **§7 明确不做/推迟 19 项**：不引 OkHttp/HC5 替换平台客户端、本阶段不引 `opensearch-java`、不引 Redis/MQ、不在 AI 侧建持久队列、不新建 `ai_task`、**不照搬参考项目的每环境线程池/`CallerRunsPolicy`/JPA 运行期建表/配置热更新机制**、L2 信号量动态调整推迟、表格结构化推迟（先看未确认 1）、答案结构化推迟、HyDE 推迟、章节树推迟、时间维度进作用域推迟、按网关自适应限流推迟、多租户不做、配置页推迟。**§8 未确认 18 条**（含：平台 OCR markdown 是否含表结构、OpenSearch 是否支持 HTTP2、平台网关是否有独立配额、`opensearch-java` jar 真实体积、AI 实例数、`next_run_at` 是否需要、主系统 worker 并发、`chunk_key` 3→5 段兼容策略、V1 的 P95 基线；以及 **参考项目"未读到"的部分如实登记**：只精读了 `scheduler/` 13 个文件 + `SysConfigService`/`SysConfigController` + `application.yml` + 4 个前端文件，`JobRunner.java`/`JobParamsCodec.java`/`ClassificationService.java`/`ClassifyQueueModal.tsx` 等未精读）。
- **与既有文档的边界**：文首用表格写明与《RAG流程完整性评估》（缺口编号）、《AI模型调用与任务队列评估》（超时/并发/队列数字）、《OpenSearch部署与接入》附录 E（mapping 与查询）、《AI工具集与检索编排评估》（框架取舍）、《AI前端与集成方案》§9/§11（契约）的分工，**不重复结论**。
- **参考项目纪律**：`C:\Users\yjc\Desktop\data`（data-classification）**只读、未改它任何文件**；引用处都标了**来源文件路径**（如 `data-classification-backend/src/main/java/com/example/dc/scheduler/JobScheduler.java`、`data-classification/src/components/JobProgressModal.tsx`）。
- **文档同步**：`ITERATION.md`（本行 + 本明细段）、`docs/政府信息化项目管理系统-设计方案.md` §0 增一行、根 `README.md` 版本行加 `docs-1.9`、`docs/README.md` 权威文档表登记本文；`node scripts/check-docs.mjs` **0 问题**（24 份 Markdown，总表行数 == 明细段数）。
- **未改任何应用代码**（`ai-backend/**`、`backend/**`、`frontend/**`、`scripts/**`、`deploy/**` 一行未动）；文档提交按显式路径暂存，未使用 `git add -A`。提交：`8155cb6`（文档本体）、`4b2490d`（哈希回填）、`007857c`（口径收紧：§1.2 的 V15 扩列数 12→13 列）；此后仅哈希登记类小提交不再逐条列出。
### code-1.0 — RAG 流水线 P0-α 落地（2026-10-13，**只动 `ai-backend/**`**）

- **起因**：用户对 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) 的执行指令——"开始执行。重点关注整体性，做好**脚本、代码、文档的统一**，不要有冗余或者模糊性描述"。
- **范围纪律**：本轮只落地该设计文档 §6.1 的 **P0-α 三项正确性修复**；`backend/**`、`frontend/**`、`scripts/**`、`deploy/**` 与参考项目 `C:\Users\yjc\Desktop\data` **一行未动**。设计文档第 1~8 章仍是**设计目标**，**进度以新增的 §9「实施状态」为唯一口径**（已落地 4 条 I1~I4，其余逐项列在 §9.2/§9.3）。
- **① 删查询侧全量 `_bulk`（G2，`RetrievalService`）**：改造前每次 `search()` 把当前作用域全部切片 `upsertAll`（估算 ≈575 MB/问）；现在先 `existingChunkKeys` 只补缺片，补写走 `indexChunks`（带元数据、`_id` 幂等）且 **best-effort**（失败只 WARN，不让"补写失败"把整次问答打挂——少写几片只是召回少几条命中）。
- **② 请求级作用域硬下推（G3+G17，`SearchScope`/`Tools`/`ToolAgent`/`QaService`/`ChatController`）**：`SearchScope` 新增 `securityLevels` 第四维 + `intersect` + `provablyEmptyAgainst`；模型给的 `doc_id` 与请求作用域**取交集**，越界返回 `scope_violation`（不退回更大范围）；kNN filter 增加 `security_level` terms 子句；`/chat` 新增可选 `scope`（四维，snake_case 主名 + camelCase 别名）；**所有老签名保留并转发**，老调用行为不变（有测试钉住）。
- **③ OpenSearch 出口单例共享（§2.4）**：原来每次请求 `new HttpClient()`（连接池/keep-alive 完全不生效），现在进程内唯一实例 + `HTTP_1_1` + `followRedirects(NEVER)` + `connectTimeout=min(10s, 单请求超时)`；新增 `ai.opensearch.requests` 指标（与"线程数不随请求数线性增长"配对验收）。
- **验证**：`mvn -o -f ai-backend/pom.xml test` → **Tests run: 189, Failures: 0, Errors: 0**（新增 `ToolsScopePushdownTest` 6 例、`ChatControllerRequestTest` +2 例；`RetrievalServiceTest` 的 StubIndex 补 `existingChunkKeys`/`indexChunks` 替身并增两条断言；`ChatControllerBizQueryTest`/`ChatControllerRequestTest` 的 stub 改覆写控制器真正调用的六参 `ask`，否则 stub 静默不生效）。
- **未做（如实登记）**：真集群压测与 top-k 一致性验证（步骤 19 ①-3）；V15 迁移、任务流/溯源、统一并发管理仍未落地。
- **文档同步**：`docs/RAG流水线设计与并发管理.md` 增 **§9 实施状态**（§9.1 已落地 4 条含代码位置与验证方式、§9.2 未落地清单、§9.3 下一批依赖顺序）并在文首加"实施状态先看 §9"的提示；`ai-backend/README.md` 更新 `/chat` 请求字段（新增 `scope`）与指标清单（新增 `ai.opensearch.requests`）；`ITERATION.md` 本行 + 本明细段；`docs/政府信息化项目管理系统-设计方案.md` §0 增一行；根 `README.md` 版本行；`docs/README.md` 索引口径补"已落地 P0-α"。`node scripts/check-docs.mjs` **0 问题**。
- 提交按显式路径暂存（**未用 `git add -A`**）、`git commit -F <临时文件>`：`3de0621`（应用代码，11 files / +692 −53）、`767d328`（文档同步：设计文档 §9 + ITERATION + README + 索引 + check-docs 前缀白名单）。

### code-1.1 — 迁移 V15 落成真实 SQL 文件（2026-10-13，**只加列与加表**）

- **起因**：设计文档 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) §9.3「下一批建议」的第 1 项——V15 是任务流/溯源/统一并发的前置，先把 schema 定下来，后续主系统阶段表与 AI 侧阶段上报才有落点。
- **新增** `backend/src/main/resources/db/migration-yashan/V15__ai_pipeline_flow.sql`（与 V13/V14 同风格：`VARCHAR(n CHAR)`、`BIGINT GENERATED BY DEFAULT AS IDENTITY`、表外 `COMMENT ON`、表外 `CREATE INDEX`、不做"列已存在则跳过"的方言判断）：
  - **`attachment_ai_task` 扩 13 列**：`content_hash`（附件字节 sha1，与 `pipeline_rev` 构成幂等键）、`pipeline_rev`、`clean_rev`、`task_type`、`priority`、`stage_key`/`stage`/`stage_done`/`stage_total`/`elapsed_ms`（任务流展示）、`attempts`、`claimed_by`/`claim_expires_at`（双机抢占租约）；新增 `idx_att_ai_task_claim`（status+priority+claim_expires_at）、`idx_att_ai_task_tasktype`、`idx_att_ai_task_content`。
  - **新建 `ai_pipeline_stage`**：一行 = 一任务一阶段，`uk_ai_pl_stage_run_stage(run_id, stage_key)` 唯一 ⇒ 重跑更新同一行、`attempts+1`（前端阶段条不会出现重复节点）；status 覆盖 PENDING/RUNNING/DONE/**PARTIAL**/SKIPPED/FAILED/CANCELLED；`counters_json` 存阶段计数、`input_digest`/`output_digest` 人读摘要、`item_count`/`item_failed`/`error_msg`。
  - **新建 `ai_pipeline_artifact`**：**只 INSERT**（`artifact_rev` 递增 ⇒ 支持"两次解析差异对比"）；五类 `artifact_type`（page_digest/clean_diff/chunk_manifest/embed_batch/index_result）；`content_json` **只存摘要**（≤4000 字符，超长截断并记 `char_count` 真实值），全文留 AI 服务 `work/`，由 `object_key` 指路。
  - **新建 `ai_pipeline_event`**：状态迁移审计（from_status/to_status/reason/operator），回答"谁/何时/为什么把状态改成了什么"。
- **边界与回滚**：**只加列与加表**（13 列全部可空或带默认值 ⇒ 存量行零影响）；回滚口径 = **停止写入这两类行 + 前端隐藏任务流视图**，不需要 DROP（崖山迁移无自动回滚，见 [`docs/崖山数据库与迁移约定.md`](docs/崖山数据库与迁移约定.md)）。**表数 16 → 19**，迁移推进到 **V1~V15**。
- **验证**：`mvn -o -f backend/pom.xml -DskipTests package` 通过；`jar tf` 确认 `BOOT-INF/classes/db/migration-yashan/V15__ai_pipeline_flow.sql` 已随包发布（发布资产无需额外改动）。
- **未验证（如实登记）**：**未在真实崖山库执行**——下次主系统启动/部署时由 `YashanMigrationRunner` 应用并按文件名登记 `schema_version`；库外只做了方言与风格对照（多列 `ALTER TABLE … ADD (…)` 与 V13/V14 同形）。
- **文档同步**：设计文档 §9.1 加 I5（含代码位置与验证方式）；`docs/崖山数据库与迁移约定.md` 版本表加 V15 行 + 表数改 19 + "下一个版本号 V16"；`docs/政府信息化项目管理系统-设计方案.md` §0 加 code-1.1 行、§8 标题与说明改 19 张表、§13 第 30 条改 `V1~V15`；`deploy/README.md`、根 `README.md`、`docs/OpenSearch部署与接入.md` 的迁移口径改 `V1~V15`；`node scripts/check-docs.mjs` **0 问题**。
- 提交按显式路径暂存（**未用 `git add -A`**）。

### code-1.3 — 前端「解析流水线」任务流视图 + 可溯源交互 + 轻量质量看板（2026-10-13，**只改 `frontend/**` 与文档同步行**）

- **起因**：设计文档 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) §9.3「下一批建议」的第 4 项（前端任务流视图），交互口径见 §4.9（来源是对参考项目 `C:\Users\yjc\Desktop\data` 的只读盘点）。
- **位置选择（新增「解析流水线」签，而不是改造「任务队列」签）**：既有的「任务队列」读 `GET /api/ai/tasks`（`QUEUED/RUNNING/DONE/FAILED` 四态 + 重试），本轮的流水线读 `GET /api/ai/pipeline/runs*`（八态含 `PARTIAL/SKIPPED/CANCELLED/DEAD`，主键是 `run_id`）；两者主键与状态枚举都不同，硬拼一张表只能靠 `attachment_id` 猜关联——那会造出"看起来对、其实可能错"的视图。因此新增页签（放在「任务队列」之后），并在签标签上挂**进行中角标**（借鉴参考项目"顶栏常驻入口 + 角标"，`usePipelineActiveCount` 5s 只取计数、**失败即熔断**；顶栏属于共享布局 `MainLayout`，不属 AI 页面，故不下沉到那里）。
- **新增组件（`frontend/src/`）**：`pages/ai/PipelineTab.tsx`（进行中/历史两段 + 只看失败 + 失败行红底 + 加载更多）、`components/ai/PipelineFlowDrawer.tsx`（阶段条 + 统计卡 + 阶段卡片 + 只读说明）、`PipelineArtifactDrawer.tsx`（五类产物摘要，含结构无关的 `ContentKV` 递归渲染）、`PipelineQualityBoard.tsx`（五项指标卡，逐卡标注字段来源）、`CitationTraceDrawer.tsx`（四跳溯源）、`useVisiblePolling.ts`（可见性感知轮询）、`usePipelineActiveCount.ts`、`pipelineFormat.ts`（时长/键值/切片反查等纯函数）。
- **改动**：`types/ai.ts`（流水线类型 + 状态/阶段/产物/任务类型标签表 + **snake_case 主名、camelCase 兼容**的归一化 + `AiCitation.chunkKey` 与 `citationChunkKey`）、`api/ai.ts`（`pipelineRuns`/`pipelineRunDetail` + 待接端点注释）、`pages/ai/AiPage.tsx`（新签 + 受控 Tabs + 角标）、`components/ai/AiChatPanel.tsx`（引用旁「溯源」入口，**原点击语义不变**）。
- **轮询策略**（任务书三条要求逐条落地）：列表 **3s**（仅当已加载行里存在进行中/排队中的 run，终态即停）；详情 **1.5s**（与参考项目详情一致，本设计文档原稿写的 2s 已在 §4.9.3 更正）；**打开抽屉必先重取一次全量**（列表只给表头）；**页面不可见暂停**（参考项目没做这一步）；**连续 3 次读失败自动停**并提示可手动刷新。
- **表达口径**：`PARTIAL` = 橙色感叹号 + `Tag color=warning` + tooltip"可检索但不完整"（**与 `DONE` 的绿色对勾明确区分**）；`SKIPPED` 显示 `error_msg`/`counters.reason` 里的跳过原因，没有就写"该环节未上报跳过原因"；`DEAD` 深红 `#a8071a` 区别于 `FAILED` 的红；详情里带 `projection_note` 时用 `Alert type=warning` 明说"**这份任务流是投影兜底来的：AI 侧可能已中断**"，而不是让用户以为后端一直在正常跑。
- **溯源链的每一跳与缺数据兜底**：① 答案 `[n]` → 引用项（`index`/`score`/`snippet`）；② 引用项 → 切片（`citations[].chunk_key`，兼容 `chunkKey`；缺失即写"主系统未回填 chunk_key"，并说明仍可跳到原文但无法定位具体切片）；③ 切片 → 清洗后文本/产物摘要（`snippet` + 该附件最近一条**已结束**的 run 详情里的 `page_digest`/`clean_diff`/`chunk_manifest`；三类逐个判断，缺哪类写哪类；设计里的 API-4 切片全文**不在冻结契约内故未调用**，界面明写"该环节未上报"而不是拿 snippet 冒充全文）；④ 附件 + 页码 → 原文第 N 页（复用既有 `AttachmentPreviewModal` 的跳页能力与 `attachmentUrl(id,'inline')#page=N`）。
- **质量看板的字段来源**（**后端没给就"暂无数据"，前端只在"分子+分母都给了"时算比率并在 tooltip 写明用哪两个字段**）：页级空页率 ← `page_digest.content.empty_pages / page_count`（或后端给的 `empty_page_ratio`）；清洗剔除字符比 ← `clean_diff.content.removed_chars_ratio`（或 `removed_chars / total_chars`）；切片数/平均长度 ← `chunk_manifest.content.chunk_count / avg_char_len / max_char_len`；向量化成功率 ← `embed_batch.content.embedded / failed`（或 `embed_success_rate`）；失败阶段分布 ← 详情 `stages[].status` 逐行计数。**不做阈值判红黄**（阈值口径归后端，§4.6 的建议阈值只写进 tooltip 作参考），**不做跨项目聚合**（API-6 未实现）。
- **失败与取消**：失败原因、尝试次数（">1 显示已重试 N 次"）齐全；**冻结契约没有流水线级重试/取消端点 ⇒ 只读展示**，在 `api/ai.ts` 末尾以注释登记待接的两个端点，界面上用一句说明代替"点不动的按钮"。任务级重试仍走既有「任务队列」签（未改动）。
- **验证**：`cd frontend && npm run build` → **0 错**（`tsc` + `vite build`，4196 modules，无新增依赖）；`node_modules\.bin\tsc.cmd --noEmit --noUnusedLocals --noUnusedParameters` → **无输出**。
- **未验证（如实登记）**：**没有在浏览器里连真实接口跑过**——`/api/ai/pipeline/*` 由主系统侧并行实现，本轮前端只按冻结契约写完调用与类型；因此"打开抽屉能看到五段阶段条与产物摘要""1.5s 轮询真的会停"这些**运行时行为尚未被实际验证**（构建与类型检查通过 ≠ 运行时正确）。另：溯源第 ③ 跳的"run 定位"在**同一附件多次解析**时只能取最近一条已结束记录（契约没有 `chunk_key → run_id` 反查），界面已把这个局限写在抽屉里。
- **文档同步**：设计文档 §9.1 加 I6、§9.2 把已落地的 `§4.3`/`§4.8~4.9` 勾掉并把剩余的"写入侧/回调接口/API-3~API-7"单列、§9.3 标出第 1、4 项已落地、§4.9.3 登记三处与设计原稿的差异；`docs/AI前端与集成方案.md` §3.1 页签清单加"解析流水线"、§9 接口表加 #11/#12 与字段命名例外说明、§9 枚举表加流水线状态与阶段键、§10 目录行补组件路径；根 `README.md` 版本行加 `code-1.3`；`node scripts/check-docs.mjs` **0 问题**。
- 提交按显式路径暂存（**未用 `git add -A`**）：`c7a4fec`（前端应用代码，12 files / +2897 −21）、`093b3c0`（文档同步：设计文档 §9 + §4.9.3 + AI 前端方案 + ITERATION + README）。

### code-1.4 — RAG 流水线**主系统侧**落地：V15 三表实体/回调写库 + 状态机强校验 + 抢占式领取 + 服务间鉴权 + 只读投影兜底（2026-10-13，**只改 `backend/**` 与文档同步行**）

- **起因**：设计文档 [`docs/RAG流水线设计与并发管理.md`](docs/RAG流水线设计与并发管理.md) **§4（任务流/落库/可溯源）与 §5.3/§5.4（抢占式领取）的主系统侧**，接口按任务书"冻结契约"逐字实现（前缀 `/api/ai/pipeline/**`，鉴权 `X-AI-Service-Token`，JSON 以 snake_case 为主名 + camelCase 别名，失败用**真实 HTTP 状态码**）。**编号说明**：`code-1.2` 未被任何批次占用（AI 侧并行分支在做阶段上报），本轮取 **code-1.4** 避免同号两义。
- **新增 10 个端点**（`backend/.../module/ai/pipeline/controller/AiPipelineController.java`）：`claim`（抢占式领取，**空队列 404**）、`heartbeat`（续约，**租约失效 409**）、`release`（放回队列）、`stages/{stage_key}`（**幂等 upsert**：同键更新同一行 + `attempts+1`）、`artifacts`（**只 INSERT + `artifact_rev` 自增**）、`events`（只追加审计）、`finish`（终态，**主系统为准**：非法迁移 400 + 允许目标、并发冲突 409、同态重复幂等）、`GET /runs/{run_id}`（详情 + 投影兜底）、`GET /runs`（列表）；第 10 条"投影兜底"不是独立端点而是详情里的只读标记。
- **抢占式领取（§5.4/§5.7）**：`UPDATE attachment_ai_task SET status='RUNNING', claimed_by=?, claim_expires_at=?, attempts=attempts+1, stage_key='PARSE' WHERE id=? AND status='QUEUED' AND (claim_expires_at IS NULL OR claim_expires_at < now)`——**受影响行数 = 1 才算领到**，0 就换下一个候选；每次领取前先把"租约过期却还挂在 RUNNING"的行放回队列（**T7**，记 `STALE_CLAIM` 事件）。**刻意不用 `SELECT … FOR UPDATE`**（崖山 Oracle 模式下会把行锁绑到分钟级的长事务上，见 §5.7）。
- **状态机强校验（§4.4.1）**：`AiPipelineStateMachine` 把 T1~T11 写成**11 条规则表**（`transitionRules()`），边表由它派生（T9 一条规则两个来源 ⇒ 12 条边，单测逐条核对）；非法迁移 400 且报错里带"允许的目标状态"。**边界（如实登记）**：既有的 `AiTaskService` 对账/启动恢复路径**不加**这层校验（它要处理 V15 之前的存量形状，强校验会把老数据的清理路径堵死）。
- **鉴权（双向不通用）**：新增 `AiPipelineTokenFilter`（只认 `X-AI-Service-Token`，**常量时间比较**，未配置即 **fail closed 全拒**），`AuthFilter` 旁路该前缀（`ApiPaths.SERVICE_PIPELINE_PREFIX` 唯一常量点）。**用户 JWT 调该前缀 ⇒ 401**；**AI 令牌调用户接口 ⇒ 进不了业务链**；运行级 `pipeline_token`（JWT，`typ=ai_pipeline`）**当不了用户 JWT**（claims 用途隔离），带了就必须属于本 run（否则 403）。
- **与既有链路衔接（§5.7 的"分工"选择）**：**文件交付仍走推送**（`AiTaskService.submit` → `POST /upload-tasks`，这是唯一把字节交给 AI 的通道），**任务归属权统一走 claim 的同一把锁**：提交成功后主系统立刻用同一条条件 UPDATE 把该行登记为 `RUNNING` + 租约（`registerPushedClaim`），因此 AI 侧来 claim 时领不到它——这就是"推送模式与拉取模式互斥"的落点。理由：拉取模式要求 AI 能自己取到附件字节，而冻结契约里没有"按 `attachment_id` 取附件内容"的接口，硬改成纯拉取会留下"领到了但取不到文件"的断链（详见该服务类注释与 §9.2）。
- **提交时写入 V15 列**：`content_hash`（**边把附件流推给 AI 边算 sha1**，不重复读、不整份入内存）、`pipeline_rev`、`clean_rev`、`task_type`（手动 `PARSE`/自动 `PARSE`/`RETRY`）、`priority`（交互式 10 / 批量 0）。**`task_key` 与 `run_id` 都不落库、不新增列**：`run_id = run-<taskId>`、`task_key = sha1(content_hash + pipeline_rev)`，claim 响应与详情接口按需实时派生（新列要走 V16，本批不需要）。
- **投影兜底（§4.4.3 方案 C 的只读形态）**：详情里若某阶段仍是 `RUNNING` 且超过 `pm.ai.pipeline.stale-running-seconds`（默认 300）无上报 ⇒ 展示层 `display_status=STALE` + `stale_stage_keys` + `projection_note`，**绝不改数据库状态**（单测断言 4 个 Mapper 的写方法全是 `never()`）。
- **前端只读入口（用户 JWT）**：`GET /api/ai/runs`、`GET /api/ai/runs/{run_id}/pipeline`（**与冻结契约同形状**：查询参数/字段/分页信封一致，`run_id` 原样可用）+ `GET /api/ai/tasks/{taskId}/pipeline[/artifacts[/{artifact_type}]]`、`GET /api/ai/attachments/{id}/pipeline[/runs]`、`GET /api/ai/stage-dict`；既有 `GET /api/ai/tasks` 的 VO **只增**阶段字段（`stage_key/stage/stage_done/stage_total/elapsed_ms/attempts/task_type`），老前端不读它们 ⇒ 行为不变。溯源入口：`chunk_manifest` 的 content 即切片清单（含 `chunk_key`），另有单条产物内容接口；**主系统不代理取 `work/` 全文**（503 语义未定义，不擅自开）。
- **验证**：`mvn -o -f backend/pom.xml test` → **Tests run: 245, Failures: 0, Errors: 0**（改造前 **147**，新增 **98** 例：状态机 9 / 领取 23 / 回写 17 / 查询与投影 11 / 鉴权 12 / 契约字段名 5 / 端点级 17 / 既有 `AiTaskServiceTest` +4）。**10 个端点的真实请求与响应样例（含 401/400/404/409）**由 `AiPipelineEndpointTest` 写到 `backend/target/pipeline-endpoint-samples.txt`（`target/` 不入库），报告直接引用该文件。
- **本轮抓到的两个真缺陷（已修，写进设计文档 §9.4）**：① `content_hash` **算了两遍 sha1**（`MessageDigest.digest()` 的 20 字节结果又被 `sha1Hex` 哈希一次 ⇒ 指纹形状正常但永远对不上），修法是新增 `AiPipelineKeys.digestHex(DigestInputStream)` 作"取流指纹"的唯一入口；② MyBatis-Plus 3.5.7 下 `getParamNameValuePairs()` 在构造期是空的，单测原本想用它区分两条 `selectList` ⇒ 回收逻辑误把刚插入的 QUEUED 行当过期行（用例表现为"事件写了两条"），改为连续返回值/状态位区分，并把"回收"与"领取"拆成 `claim`/`claimNext` 两个方法。
- **与 AI 侧并行分支的联调核对**：读 `ai-backend/.../module/pipeline/PipelineReportClient.java` 逐字段核对——请求体字段名、`X-AI-Service-Token` 头、`409 ⇒ 停止并放回` 的语义、claim 响应读的 6 个字段全部一致；发现 **AI 侧心跳发的是空 body**（不带 `claimed_by`），故把 `claimed_by` 放宽为**可选**（带了必须是当前持有者，不带只校验"仍是 RUNNING 且租约未过期"，两者都给 409 语义），并补两条单测钉住。
- **未做/未验证（如实登记）**：① **V15 未在真实崖山库执行**——条件 UPDATE、`LambdaUpdateWrapper` 生成的 SQL 与 `@TableLogic` 的 `deleted=0` 只经过单测（Mapper 全为桩），**没有连库跑过一条真实 SQL**；② 10 个端点**没有在真实 HTTP + 真库上端到端跑过**（样例来自 MockMvc 独立装配：真 Controller/真 Service/真过滤器/真异常处理器，只有 Mapper 是桩）；③ AI 侧上报链**未与主系统联调**（两侧代码都在，未同机跑过）；④ **按项目公平调度、批量/交互式分流、队列上限与诚实拒绝、`attempts>=3 ⇒ DEAD` 的自动判定、投影兜底的写入侧、API-3~API-7、保留与清理作业**均未做（见设计文档 §9.2）；⑤ **前端仍调 `/ai/pipeline/runs*`**（浏览器会 401）——用户侧同形状入口已提供，需前端改 2 行（`frontend/src/api/ai.ts` 第 183/202 行），已写进 `docs/AI前端与集成方案.md` §12；⑥ 本轮**未新增演示数据**（§14-6 的固定交付项）：`scripts/**` 不在本轮允许改动的范围内，且没有可连的库，故未做（建议下一步加 `scripts/pipeline-demo.mjs` 模拟一次 claim→5 阶段→5 产物→finish，既能演示也能当端到端验收）。
- **文档同步**：设计文档 §9.1 加 I7~I12、§9.2 逐条勾掉/收敛未落地项、§9.3 标出第 1/2/4/5 项状态并新增第 6 项（投影写入侧）、§9.4 新增"本轮抓到的真缺陷"、§4.5.2 增"两条路径一套读逻辑"说明；`docs/AI前端与集成方案.md` §9 的 #11/#12 权限与字段更正 + 新增 **§12 解析流水线回调契约（10 个端点 + 状态码 + 前端路径迁移 2 行）**；`docs/政府信息化项目管理系统-设计方案.md` §0 加 code-1.4 行、§13 加第 44 条；根 `README.md` 版本行加 `code-1.4`；`node scripts/check-docs.mjs` **0 问题**。
- 提交按显式路径暂存（**未用 `git add -A`**）：`1573d7b`（应用代码，57 files / +7056 −44）、`c65b487`（文档同步：设计文档 §9 + §4.5.2 + AI 回调契约 §12 + 设计方案 §0/§13 + ITERATION + README + docs/README，6 files / +160 −20）。

### code-1.2 — AI 侧：五阶段边界上报（§4）+ L1 业务并发闸门 `PipelineGate`（§5）（2026-10-13，**只动 `ai-backend/**`**）

- **起因**：设计文档 §9.3「下一批建议」的第 3 项（AI 侧在 5 个阶段边界上报 + `run_id` 贯穿）与第 5 项（L1 `PipelineGate`），并需与已落地的 `code-1.4`（主系统 10 端点）**逐字对齐契约**。
- **范围纪律**：只改 `ai-backend/**` 与文档同步行；`backend/**`、`frontend/**`（并行分支正在改）**一行未动**；`git add` 全按显式路径（**未用 `git add -A`**）。
- **① 五阶段边界上报（新增 `module/pipeline/**`）**：`PipelineReporter`（门面：一次判开关、一处生成 `run_id`、一处算 `task_key`）+ `PipelineReportClient`（七条契约：`/claim`·heartbeat·release·stages·artifacts·events·finish；`X-AI-Service-Token` **只在这一处出现**；响应兼容 `{code,message,data}` 信封与裸对象两种形态；心跳 409 ⇒ `conflict()`）+ `RunContext`（`run_id` 全程贯穿、写日志 MDC、`close()` 幂等）+ `StageKey`/`StageStatus`/`StageReport`/`ArtifactType`。
  - **接入点**：`UploadTaskService.run` 报 `PARSE`（`RUNNING → DONE/PARTIAL/FAILED`）、`CLEAN`（**`SKIPPED` + 原因**："本批尚未实现清洗阶段（G1 属 P1-α）"）、`page_digest`/`clean_diff` 产物、状态事件与 `/finish` 终态；`DocIndexer.prepare` 报 `CHUNK`（+ `chunk_manifest`），`DocIndexer.indexPrepared` 报 `EMBED`（+ `embed_batch`）与 `INDEX`（+ `index_result`，含新增的 **`PARTIAL`** 语义）。
  - **纪律**：best-effort（非终态失败只 WARN + `ai.pipeline.report_failed`，**不重试**）；**终态重试**（默认 2 次 ⇒ 共 3 次尝试，用尽后 ERROR 并提示按 `run_id` 人工核对）；`AI_SERVICE_TOKEN` **绝不进日志/异常/请求体/自检**（自检只报"配没配"）。
- **② `pipeline_rev`/`clean_rev` 进幂等键与向量缓存校验（T7 的 L1/L2 两层防护）**：新增 `PipelineRev`；`RetrievalService.chunkKey` 委托它（形态可切：`legacy` 三段 = **默认**，与接入前逐字节一致；`rev` 五段 `d=…:p=…:h=…:c=<cleanRev>:k=<chunkRev>`）+ `parseChunkKey` 两种形态都认；向量缓存 `work/vectors/<docId>.json` 头部增 `clean_rev`/`chunk_rev`，不匹配即**整体失效重算**（含"接入前写的、缺这两个字段的老缓存"）；`task_key = sha1(内容 sha1 + pipeline_rev)`。
- **③ L1 `PipelineGate`（新增 `common/gate/**`）**：五通道（`INTERACTIVE`/`BATCH.PARSE`/`BATCH.REINDEX`/`BATCH.EMBED`/`BATCH.EVAL`）；`有效优先级 = 任务优先级 + 通道权重 + 老化`（100/20/10/5/0，老化每 30s +2、上限 40）；**按 `project_id` 轮转 + 每项目在飞上限**；队列上限 + 等待超时 + **诚实失败**（`reason=full|timeout|paused` + 深度 + 上限 + 建议）；**软闸门**（交互式有等待者时只暂停**派发**新的 `BATCH.PARSE`，绝不踢在跑的批量）；10 项 `ai.gate.*` 指标；`/health?with_metrics=true` 增 `queues.gate`/`queues.pipeline`；启动日志打印通道一览并做 **L1↔L2 交叉校验**（`ΣL1 ≤ ΣL2`，超了只 WARN）。
  - **三条硬规则可检**：**H1**（先 L1 后 L2、L1 全程持有）——`ModelRegistry` 增 **H1 守卫**：未持 L1 permit 就调 L2 时**立刻抛错**（消息里给出正确写法），并以 `ai.model.h1.violations` 计数（正常恒 0）；上传链路因此把 permit 的持有者改成**任务线程**（提交线程只做 `probe` 预检，不占许可），保证"持牌的人就是干活的人"。**H2**（排队不嵌套）——L1 排队只发生在任务开始前，L2 排队只发生在单次调用前。**H3**——L2 恒为 `new Semaphore(limit, true)`，优先级不在这里生效。
  - **语义保持**：`/chat` 仍是 **503 快速失败**（交互式等待 1500ms、队列 0）；`/upload-tasks` 队列满仍是 **429 + FAILED + 清临时文件**（0 条永久 QUEUED、0 个 `work/tmp` 残留）；**取消后 permit 立即归还**（协作式取消在阶段/页边界生效，但指标与 permit 立刻回落）。
- **验证**：`mvn -o -f ai-backend/pom.xml test` → **Tests run: 239, Failures: 0, Errors: 0**（改造前 **189**，新增 **50** 例）：`PipelineGateTest` 16（准入/队列满/超时/预检/按项目轮转/优先级/老化防饥饿/指标/软闸门/人工暂停/取消立即释放/禁用放行 + H1/H2/H3，其中**死锁反例**用"L1 与 L2 各只有 1 个许可 + 交互式与批量混合并发 + 屏障 + 25s 超时断言 + 压测后 L1/L2 在飞全回 0"验证）、`PipelineReporterTest` 12（含终态重试到成功、终态诚实放弃、CLEAN=SKIPPED 带原因、幂等同体、令牌不进请求体）、`PipelineRevTest` 6、`VectorCacheRevisionTest` 4、`UploadTaskStageReportTest` 4（端到端：真 HTTP 桩 + 真闸门 + 真线程池）、`ChatGateTest` 5、`AiSettingsBindingTest` 3（**yml 键必须真绑到 `AiSettings`**，防"配置写了没生效"）。全程用既有 `StubHttpServer` 做上报桩，**未连真主系统/真集群**。
- **测试过程中真实抓到的两个并发缺陷**（都已修 + 有测试钉住）：① `wouldPause` 读的是**通道自己的**交互式计数（恒 0）⇒ 软闸门永不生效；改为进程级计数并把坑写进注释。② 派发扫描原先"从轮转游标起第一个可派发的即选中"，**无视有效优先级** ⇒ 高优先级反而排在后面；改为"**先取有效优先级最高，同优先级再按 `project_id` 轮转**"。
- **未做/未验证（如实登记）**：① **AI 侧 `/claim` 轮询领取未开启**（七条契约客户端已实现并测试，含心跳 409；开启会与既有"主系统推送 → `/upload-tasks`"形成两条入口，按 §5.7 分工本轮不开）；② L1 的 `BATCH.REINDEX`/`BATCH.EMBED`/`BATCH.EVAL` 三条通道**已实现并测试但没有业务调用点**（重建仍走 `ReindexService` 单线程 + 启动期 409；批量向量化在 PARSE 任务内部完成）；③ §5.6 的 `/admin/gate/config`、`/admin/gate/pause-batch` 两个运行时端点**未开**（只有内存开关）；④ **未与真主系统联调**（字段名逐字对齐，但没有一次真机端到端）；⑤ V1~V8 真机压测未做（单测覆盖 V2/V4/V5/V8 的**语义**，不覆盖真实负载的 P95）；⑥ 真集群 top-k 一致性验证沿用前批未做。
- **文档同步**：设计文档 §9.1 加 **I13~I15**（含代码位置与验证方式）、§9.2 勾掉 §3.2/§5 并收敛措辞、§9.3 标出第 3/5 项状态；`ai-backend/README.md` 新增「L1 业务并发闸门 `ai.gate.*`」与「阶段上报 `ai.pipeline.*`」两节 + 指标清单 + `/chat` 的 503 说明；`ai-backend/src/main/resources/application.yml`、`ai-backend/.env.example`、`ai-backend/README.md` **三处一致**；`node scripts/check-docs.mjs` **0 问题**。
- 提交按显式路径暂存（**未用 `git add -A`**）、`git commit -F <临时文件>`。
## 功能完成度

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 登录 / 角色（三角色只读控制） | ✅ | admin / jingban01（经办人）/ lingdao01（领导，只读） |
| 项目管理（列表/卡片/筛选/分页/新建/编辑/删除） | ✅ | 删除仅管理员；列表为**可折叠树**（顶层展开子项目明细） |
| **总项目 / 子项目体系** | ✅ | v1.3：`project.parent_id`，子项目独立进度/合同/付款，总项目为汇总容器 |
| **合同管理（一个项目多份合同）** | ✅ | v3.5.0（**V12**）：`project_contract` 关联表为权威关联——施工主合同 / 监理服务 / 第三方测评 / 预算编制 / 方案评估各自独立签订；**v1.4 的"一份合同只挂一个(子)项目"规则保留**；政府合同常见字段（V10）与类型/状态字典+配色；`project.contract_id` 收窄为「主合同」指针 |
| **合同附件** | ✅ | v3.5.0：`bizType=CONTRACT` **独立归属到具体合同**（合同正本 / 法务意见书 / 合同会签表 / 授权委托书 / 履约保函 / 中标通知书…），按业务分组展示与上传；历史挂在项目/阶段的合同类附件已由 V11 归位 |
| **资金情况（以付款为主线）** | ✅ | v3.5.0（**V11**）：一条记录 = 一笔付款；付款过程留痕（付款方式 / 经办人 / 发票号 / 记账凭证号 / **收款账户快照**）；状态·合同·关键词筛选 + **风险提示条**（未关联合同的付款、已付款但凭证不齐） |
| **报销凭证缺件提醒** | ✅ | v3.5.0：所需附件清单走字典 `PAY_REQUIRED_ATTACH`（可自行调整），界面按项显示已传/缺失，已付款缺件红标并可直接上传 |
| **项目分工（模块 → 子模块）** | ✅ | v3.4.0（**V10**）：`project_division` 记录负责方（甲/乙/双方）、甲乙负责人、计划开发/调试/上线、进度 0–100、状态、备注；树形表格 + 级联删除 + 防循环引用 |
| 阶段模板与自动生成阶段实例 | ✅ | HW 9 / SW 11，按核算单元生成；多套模板（`phase_tpl`） |
| 阶段推进 + 整体进度自动计算 | ✅ | 权重口径见设计稿 §9 |
| 项目详情页签 | ✅ | 最多 **8 个**：流程进展 / 项目信息 / 子项目（仅容器）/ 资金情况 / 合同管理 / 项目分工 / 附件中心 / 操作日志（容器项目隐藏"流程进展""资金情况"） |
| 付款记录 CRUD + 资金汇总 | ✅ | 管理员录入；凭证附件预览/全屏；与合同进度联动 |
| 附件存储（本地盘 ↔ 华为 OBS） | ✅ | v3.1：`AttachmentStorage` 抽象，`app.storage.type=local\|obs`；切换对前端透明、元数据零迁移 |
| 附件**后台上传** + 上传记录中心 | ✅ | v3.2.0（V9）：暂存服务端 + 登记任务即返回，后台线程池推存储；v3.3.0 入口独立为全局 `UploadTaskProvider` + `UploadTaskCenter`（可关窗不中断、带进度与历史） |
| 附件上传/下载/在线预览/全屏/逻辑删除 | ✅ | 图片/pdf/文本内嵌；md 用 react-markdown、docx 用 docx-preview、xls/xlsx 用 SheetJS；doc/ppt/pptx/ofd 提示下载 |
| 文件类型字典 / 上传白名单 / 大小上限 | ✅ | V5 FILE_TYPE 字典；后端白名单强校验；单文件 **500MB**（三处联动，见 v3.3.0） |
| 工作台（汇总/待办/验收/逾期/最近更新） | ✅ | v0.7，v1.3 口径 = 核算单元（叶子） |
| 项目统计（ECharts，筛选联动） | ✅ | 状态·类型构成、流程阶段分布、年度资金（预算/合同/实付，合同去重） |
| 系统管理（用户/字典/阶段模板/日志） | ✅ | v0.8：仅管理员，写操作全留痕 |
| 一键启动/停止 / 开发机重建 | ✅ | `deploy/windows`、`deploy/docker`、根目录 `start-dev.cmd`；**开发机一键重建** `.\scripts\dev-reload.ps1`（v3.3.1 起，`docs-1.2` 由 `.sh` 转 Windows 原生 `.ps1`） |
| 列表/附件筛选多选 | ✅ | v1.5：列表（类型/状态/年度）与附件中心（类别/归属）多选 |
| 金额口径实时同步 | ✅ | 后端查询时实时汇总（合同 + 付款），列表/详情/资金页/统计口径一致 |
| 部署方案 | ✅ | v3.0~v3.1：Docker（backend + frontend nginx，数据库为**外部崖山主备**）；服务器**不存源码**，只放 compose + `.env`；发版 `.\scripts\make-release.ps1` / 升级 `pm-upgrade.sh`（自动切 `IMAGE_TAG`） |
| 数据库国产化（崖山 Oracle 模式） | ✅ | v3.0 起替代 MySQL + Flyway；自研 `YashanMigrationRunner`；`db/migration-yashan/` **V1~V15**；主键 identity；驱动级 primary + TAF 高可用 |
| 演示数据脚本 | ✅ | `seed-demo.mjs`（父子项目 + 每核算单元 2~5 份合同 + 付款里程碑 + 项目分工）、`seed-attachments.mjs`（阶段/合同/付款三类附件，含缺件场景）、`demo-reset.sql`（物理清空，v3.5.1） |
| 开发机数据库工具 | ✅ | v3.5.1：`scripts/db-sql.ps1` + `scripts/jdbc/RunSql.java`（无 yasql 客户端时手工查/改库，口令不打印不落盘）；脚本清单见 `scripts/README.md` |
| 文档维护约定 | ✅ | v1.1 起：每次迭代同步更新本文档（总表 **+ 各迭代明细**）、设计稿（§0/受影响章节/§13）、README；v3.5.1 起补：**每次迭代必须用演示数据体现新功能**、涉及脚本须同步 `scripts/README.md` |
| 附件智能处理（OCR / 大模型抽取 / 向量检索） | 🚧 进行中 | **AI 能力服务已全量 Java 化**：`ai-backend`（Spring Boot 3.3.5 / Java 17，独立构建与部署，默认 8100），接口 `/analyze`、`/ocr/pdf-info`、`/ocr/file`、`/documents`、`/upload-tasks`、`/chat`。平台 OCR + 千问对话 + Qwen3-VL Embedding/Reranker **全部在真实内网网关上验证通过**（扫描件 OCR 1.3~1.7s、抽取 ≈8s、问答 ≈10s、向量化 4096 维且余弦 0.3245 与 Python 基线**逐位一致**、PDF 文本层与 PyMuPDF **逐字节一致**）；`/chat` 自 **ai-1.1.0** 起返回**结构化引用** `citations`（可跳到附件第 N 页），并支持**受控查询**（工具 `query_business_data`：项目/合同/付款/统计口径，条件注册、超时 20s）；**JUnit 用例 129 例全绿**（v3.7.0）；Docker 镜像已构建并起容器实测通过。原 Python 版已删除（知识资产迁入 `ai-backend/docs/`）。**下一步**：OpenSearch 集群部署后实测 kNN 在线路由（适配层已实现、**路由已接但未实测**）+ **给 AI 服务加服务间鉴权**（当前无入站鉴权，仅靠网络隔离） |
| **AI 与知识库（并入主系统，v3.6.0 起）** | 🚧 P0/P2 已完成 | 前端：`/ai` 菜单（文档库／任务队列／服务自检）+ **全局悬浮问答**（引用可点击跳原文第 N 页）+ 附件索引状态标签与重新解析 + **上传后自动触发解析**（`AI_AUTO_PARSE`，默认开；AI 失败不影响上传，只显示"解析失败+原因"）+ **v3.7.0「系统数据」区块**（值 + 口径 + 数据时间，与文档引用视觉区分）；后端：代理层 `module/ai/**`（权限与作用域在主系统收口、`ai_ask_log` 审计留痕）+ **迁移 V13/V14** + **v3.7.0 受控查询 4 个 RPC**（`/api/ai/query/{entity}`，scope_token 上限 300s、越权在 mapper 之前拒绝）。验证：前端 `npm run build` 0 错、后端 `mvn -o test` **147 例全绿**、ai-backend **129 例全绿**、评测集 `eval/smoke-30.json` 35 题 + `--list-truth` 20/20。**待办**：P1 检索调试与问答留痕页（`GET /api/ai/ask-logs` 已建表未开接口）、**V13/V14 待下次启动/部署应用**、AI 服务入站鉴权、9 道文档题页码待人工标注 |
| 设计稿 Q1–Q15 评审回写 | ⏳ 待办 | 待业务反馈 |

## 运行方式速查

```powershell
# 一键启动：见 deploy/README（Windows: start-dev.cmd / 双击；Docker: deploy/docker；生产见 docs/部署与发布全流程手册.md）
# 数据库：崖山 YashanDB（Oracle 模式）主备；后端连接用环境变量（见 README/deploy/README）：
#   export YASHAN_MASTER_IP=10.254.212.106 YASHAN_STANDBY_IP=10.254.212.107
#   export YASHAN_DB=PM YASHAN_USER=pm YASHAN_PASSWORD=xxx
cd backend  && mvn spring-boot:run         # :8080（自研 Runner 自动执行 db/migration-yashan 建库，现 V1~V15）
cd frontend && npm install && npm run dev  # :5173
# Docker 本机（前端 nginx 对外端口见 deploy/docker/.env 的 WEB_PORT，本机约定 8088）

# —— AI 与知识库（v3.6.0；AI 是**独立部署**的服务，主系统只代理它）——
# 主系统侧配置（都有默认值，本机默认指向 http://127.0.0.1:8100）：
#   AI_ENABLED=true（总开关） / AI_SERVICE_BASE_URL / AI_SERVICE_TOKEN（当前对方不校验） / AI_CHAT_TIMEOUT=180 / AI_TIMEOUT=10
#   PM_AI_QUERY_CALLBACK_URL（v3.7.0 受控查询回调地址，留空 = 不带 biz_query、工具不注册、行为与旧版一致）
#   PM_AI_SCOPE_TOKEN_TTL_SECONDS=300（scope 令牌有效期，代码里 MAX_TTL_SECONDS=300 只许配小不许配大）
cd ai-backend && mvn spring-boot:run       # :8100（需 .env 里的平台网关 LLM_BASE_URL/LLM_API_KEY，见 ai-backend/README.md）
# 主系统前端 → /api/ai/* → 主系统后端（权限/作用域/留痕）→ AI 服务 8100（仅内网，浏览器永不直连）
# 自检与端到端：.\ai-backend\scripts\verify-e2e.ps1 -SkipBuild   # 含 citations 断言
# 容器化重建 AI 服务 / 出 AI 发布包（v3.6.2 起，与主系统同一套脚本形状）：
#   .\scripts\dev-reload.ps1 -Project ai              # 本机重建并重启（:8100）
#   .\scripts\make-release.ps1 v1.0.0 -Project ai     # 出 dist/pm-ai-release-v1.0.0/

# —— 服务器侧统一入口（v3.6.3 起；发布包里 pm.sh 落在部署根 /home/lhim/pm/）——
#   cd /home/lhim/pm
#   bash pm.sh start [main|ai|all]     # 先起 ai 再起 main（默认 all）
#   bash pm.sh status | logs <main|ai> | stop [main|ai|all]
#   bash pm.sh upgrade main main/releases/pm-images-aarch64-<版本>.tar.gz   # load + 切 IMAGE_TAG + 重启 + 健康检查
#   bash pm.sh upgrade ai   ai/releases/pm-ai-images-aarch64-<版本>.tar.gz  # 切 AI_IMAGE_TAG
# 布局：/home/lhim/pm/{pm.sh, main/, ai/}；每个项目目录含 compose + .env + releases/ 镜像包。

# —— 演示数据（走真实 API；详见 scripts/README.md）——
.\scripts\db-sql.ps1 scripts\demo-reset.sql   # 可选：物理清空演示数据（从干净基线开始）
node scripts/seed-demo.mjs                      # 项目/合同/付款/分工（默认 http://127.0.0.1:8088）
node scripts/seed-attachments.mjs               # 阶段/合同/付款三类附件

# —— 开发机辅助（Windows 原生 PowerShell，**不需要 Git Bash**；5.1 与 7 都能跑）——
.\scripts\dev-reload.ps1 [all|backend|frontend]   # 主系统：重建镜像 + 重启（自测用；不主动执行，按需）
.\scripts\dev-reload.ps1 -Project ai              # AI 服务：重建 + 重启 + :8100 健康检查
.\scripts\db-sql.ps1 <sql文件>                    # 手工查/改库（无需 yasql 客户端）
.\scripts\make-release.ps1 <版本>                 # 主系统发布包（两端镜像）
.\scripts\make-release.ps1 <版本> -Only backend   # 只重建后端；前端复用现有镜像（打印复用来源 tag）
.\scripts\make-release.ps1 <版本> -Project ai     # AI 服务发布包（独立发版）
```

> ⚠️ 本系统删除一律是**逻辑删除**（`deleted = 1`），而 seed 走 API 删项目 → 反复重跑会堆积历史行；
> 直接翻数据库时请先按 `deleted = 0` 过滤。

> ⚠️ **AI 相关功能的启用条件**（v3.6.0）：`ai-backend` 未启动时主系统**不报"未找到"**，而是明确提示
> "AI 服务不可用"（自检页与附件状态标签会直接说明）；附件解析需要 AI 服务可达 + 平台网关
> （`LLM_BASE_URL`/`LLM_API_KEY`）可用。

## 后续待办（Backlog）

1. **设计稿 Q1–Q15 评审**：按业务反馈修订流程模板/字段/口径并回写文档。
2. **合同与付款的勾稽校验**：各期付款合计 vs 合同金额、比例合计 100% 的自动核对提示；合同变更/结算流程化。
3. **合同附件必传校验**：按合同类型规定必备件（如主合同必须有法务意见书、会签表、履约保函），缺失时提示或拦截。
4. 附件体验增强：pdf/图片缩略图、批量上传与打包下载；OFD 在线预览单独评估（转图片 + OCR 保底）。
5. **附件保留策略**：删除项目后附件元数据与物理文件的治理（当前刻意逻辑保留以便审计追溯）。
6. 工程化：前端按路由代码分包（当前单包较大）、后端 profile（dev/prod）、数据库每日备份、操作日志导出。
7. 阶段逾期提醒增强：已具备基础版，可补列表页逾期角标与全局提醒。
8. **AI 能力服务（`ai-backend`）**：已全量 Java 化并验证（与主系统同栈、独立部署、**129 例 JUnit 全绿**（v3.7.0）、Docker 镜像起容器实测通过）。
   **下一步**：① **给 AI 服务加服务间鉴权**（当前**无任何入站鉴权**，主系统已发 `Bearer` 但对方不校验，安全性完全依赖网络隔离——这是当前最该补的安全项）→ ② **OpenSearch 集群部署后实测 kNN 在线路由**（适配层已实现、路由已接但未实测；集群不可达/配错时**显式报错**而非静默降级）→ ③ 扩充评测集规模（当前 `eval/smoke-30.json` 35 题为**结构化问答**冒烟集；100~200 QA 的检索 recall@5 / MRR 基线仍待做）→ ④ 检索编排两项调整（**待负责人批准**）：检索改**服务端预取**（工具保留用于追问）、诊断字段（`retrieval`/`reranked`）只落日志不下发模型 —— 依据与验收口径见 [`docs/AI工具集与检索编排评估.md`](docs/AI工具集与检索编排评估.md) §4。
9. **AI 与知识库 P1/P2**：**P0 与 P2 已完成**（P2 = 受控查询，见 v3.7.0）。**待做**：P1 = `/ai` 页补**检索调试**（召回→融合→精排分数，判断"答不出来"是解析还是检索问题）与**问答留痕页**（`GET /api/ai/ask-logs`，表与写入已就绪）；P2 遗留 = 👍/👎 反馈收进评测集、**9 道文档题页码待人工标注**、受控查询的真机端到端（见 v3.7.0 未验证清单）。
10. **V13/V14 的落地验证**：迁移脚本已在库外通过评审（多列 `ALTER TABLE … ADD (…)` 与 V1~V12 已成功应用的写法一致），但**未在真实崖山库执行**——下次 `pm-backend` 启动/部署时由 `YashanMigrationRunner` 应用并登记；应用后请核对 `attachment_ai_task` / `ai_ask_log` 两表、`attachment` 三列（V13）与 `ai_ask_log` 的 `biz_query_count`/`biz_entities` 两列（V14）。部署顺序：**先 restart 主系统（跑 V13/V14），再确认 `ai-backend` 为 ai-1.1.0**（否则前端引用为空列表、受控查询工具不注册）。
11. **演示路径**（设计稿 §14.6 要求）：`node scripts/seed-attachments.mjs`（上传即自动触发解析，受 `AI_AUTO_PARSE` 开关控制）→ `/ai` 页看文档库与任务队列 → 悬浮窗提问 → 点引用跳附件第 N 页。
12. **收尾清理（已登记，不在本轮做）**：① P0+P1 验收通过后删除 `ai-backend/static/index.html` 整个 `static/` 与 Dockerfile 里的 `COPY static/`（方案 §8.4）；② 前端小口子：范围选择器只拉前 500 个项目（无分页懒加载）、无"全部重新解析"入口、缺浏览器级人工验收。
    ~~③ `make-release.ps1` 的 gzip 产物需验证 `docker load` 闭环~~ —— **已于 v3.6.2 实机验证通过**（主系统包与 AI 包都验了）。
13. **评测集（v3.7.0 已落地第一版，继续扩）**：**已交付** `eval/smoke-30.json`（35 题，覆盖结构化真值 / 文档 / 混合 / 负例 / 越权）+ `scripts/eval-ai.mjs`（`--dry` 体检 / `--list-truth` 真值 / `--write-truth` 漂移快照 / `--scope-token` 交叉校验），真值取自**页面接口**并在真机后端上 **20/20 解析成功、与 SQL 逐位一致**。**待做**：① **9 道文档题的引用页码人工标注**（标注前引用命中率恒 `n/a`，这是当前评测集最大的缺口）；② 扩到跨文档对比 / 金额求和 / "查不到必须说未找到"更多样本；③ 👍/👎 反馈回流成题。有了它才能比较切片策略、混合检索配比、**以及"要不要引入 LangChain4j/Spring AI"这种框架级问题**（论证见 [`docs/AI工具集与检索编排评估.md`](docs/AI工具集与检索编排评估.md) §7.4）。若确要做框架对比，再开**限时 spike**（独立分支，不碰主线）：同文档同问题对比代码行数 / 延迟 / **引用页码准确率** / 依赖体积 / **审计明细是否还拿得到**。
14. **P2 会话管理（若做）**：按"主系统是唯一事实源、AI 服务无状态"的边界，会话与消息表建在**主系统**（与 `ai_ask_log` 归并为同一条数据），**不要**把会话状态放进 AI 服务（见 §7.2 三条硬伤）。
15. **RAG 全链路补强（依据 `docs-1.8` 的评估，见 [`docs/RAG流程完整性评估.md`](docs/RAG流程完整性评估.md) §4/§5）**：
    **P0（建议按此顺序）**：① 修 `RetrievalService.java:440-442` 查询侧**每次检索全量 `_bulk`**（估算 ≈575 MB/问）；② 把请求级 `doc_ids` 下推到检索层（`SearchPort.searchInScope` + `SearchScope` 已就绪，只差 `QaService`/`Tools` 传参）；③ **补评测的尺子**——9 道文档题页码标注 + 检索级 `recall@5`/MRR 基线（**没有它，后续切片/召回/降配改动一律无判据**）；④ 建**独立清洗阶段**（先做四件低风险项：跨页重复行=页眉页脚、纯页码行、空白/不可见字符归一、行尾断行合并），并落盘"清洗前后对照"供抽检；⑤ 补 `/chat` 的**上下文预算**与**答案侧机器校验**（数字可溯源 + `[cite]` 编号合法性）、⑥ 混合型 PDF 的**逐页 OCR 回退**、⑦ **AI 服务入站鉴权**。
    **P1**：表格整块成切片（不截断）、重叠窗口与父子块（先做"同页邻块"）、融合改 RRF/归一（`OPENSEARCH_HYBRID` 开启的前置）、非 PDF 格式（docx/xlsx）解析、解析质量抽检基线、密级纳入 `SearchScope`、重解析幂等（内容指纹 + 清理旧 doc/向量）、质量类在线指标（零命中率/空引用率/降级次数/拒答率）、查询改写（history-aware，前置是页码与 recall 基线）。
    **触发点**（任一出现即必须动，见该文档 §5.2）：切片 ≥4,000 必须换 `opensearch` 后端、问答需求 >0.16 QPS、向量规模触顶（该文档 §5.2 T3：**估算约 30~50 万向量**，取决于堆与 HNSW 开销）、需要第 2 个 AI 实例、文档数 >500、任何质量改动前必须先有基线。

## 变更记录说明

> 本机评审/迭代改动已整理并合入正式迭代（见上方「迭代总表」**v1.5**），对应提交 `9a8e387`、`6f02c99`（均已推送）。按维护约定，所有已实现并验证的迭代均须同步更新本文档、设计稿（§0/§13）与 README 后一并提交，**不再保留"待提交/半成品"章节**。

