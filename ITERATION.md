# 迭代记录（开发进度日志）

> 本文件记录本系统从设计到前后端实现各迭代的进度、交付内容与验证情况，随每次迭代更新。

## 项目速览

| 项 | 内容 |
| --- | --- |
| 定位 | 单位内部政府信息化项目全生命周期管理（硬件 / 软件两类项目） |
| 前端 | React 18 + TypeScript + Vite + Ant Design 5（`frontend/`） |
| 后端 | Java 17 + Spring Boot 3.3.5 + MyBatis-Plus + 自研迁移 Runner（`backend/`） |
| 数据库 | 崖山 YashanDB（Oracle 模式）主备集群（主 10.254.212.106 / 备 10.254.212.107:1688，库/schema `PM`，业务账号 `pm`；v3.0 起，替代 MySQL 8 + Flyway）；迁移脚本 `db/migration-yashan/` **V1~V12** |
| 认证 | JWT + BCrypt，角色 ADMIN / MANAGER / VIEWER |
| 附件存储 | 抽象 `AttachmentStorage`：默认本地盘，可切**华为 OBS**（`app.storage.type`）；归属分四类 `PROJECT` / `PROJECT_PHASE` / `CONTRACT` / `PAYMENT` |
| 需求基线 | `docs/政府信息化项目管理系统-设计方案.md`（设计与实现同步稿，Q1–Q15 处置状态见 §11） |
| 脚本清单 | `scripts/README.md`（发版打包 / 演示数据 / 开发机数据库工具） |
| 当前版本 | **v3.5.1**（2026-09-17） |
| ai-backend | **`ai-1.0.1`**（2026-09-20）：Java 版 AI 能力服务（Spring Boot 3.3.5 / Java 17，**与主系统同栈**），**独立构建与部署**（默认 8100、独立镜像与发版，不动主系统）；附件解析 + 平台 OCR + 文档库/问答 + **检索链路（Embedding 召回 + Reranker 精排）**；接口 `/analyze`、`/documents`、`/upload-tasks`、`/chat`；见 `ai-backend/README.md`。原 Python 版 `ai-service/` **已删除**（知识资产迁入 `ai-backend/docs/`） |
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
| ai-0.5.0 | 2026-09-18 | **ai-service：检索链路接通（Embedding 召回 + Reranker 精排）** | 平台已部署 **Qwen3-VL-Embedding-8B**（**固定 4096 维**；⚠️ 平台实际部署**不支持 MRL 降维**，传 `dimensions` 实测 **HTTP 400**，故 `VEC_EMBED_DIMENSIONS=0` 表示**不传**）与 **Qwen3-VL-Reranker-8B**（网关 `http://10.254.208.35:8090/v1`，与千问对话/平台 OCR **共用同一把 sk**，手册 `ai-service/Qwen3-VL-Embedding-Reranker调用手册.md`）；新增 `vec_client.py`（纯 urllib：`/embeddings`、Jina 风格 `/rerank`、`GET /models` 探活、`cosine`）、`retrieval.py`（**三步链路**：向量召回 + 关键词召回（字符 2-gram + IDF，原在 `tools.py`，本轮**搬迁**）→ 按 (doc_id, page_no, 文本 sha1) 去重融合 → Reranker 精排取 top_k）、`scripts/vec_try.py`（`--health` / `--selftest` / `--ingest` / `--search` / `--top-k` / `--doc-id` / `--offline`）、`tests/test_retrieval.py`（5 条离线用例：精排顺序生效 / 向量不可用降级关键词且 note 写明 / 切片向量走缓存（第二次只为 query 编码）/ 切片键随内容变化 / **`VEC_BACKEND=opensearch` 显式报错**）；`tools.search_documents` 改为**委托** `retrieval.search()`（工具签名不变，返回体新增 `retrieval`/`reranked`/`note`）；新增配置 `VEC_BASE_URL`/`VEC_API_KEY`（默认复用 `LLM_*`，`VEC_TIMEOUT=300`）/`VEC_EMBED_MODEL`/`VEC_RERANK_MODEL`/`VEC_EMBED_DIMENSIONS=0`（**不传 dimensions**；平台不支持 MRL 降维，实际 4096 维）/`RETRIEVAL_RECALL=50`/`RETRIEVAL_TOP_K=5`/`VEC_BACKEND=local`/`OPENSEARCH_*`（索引 `pm-ai-chunks`）；`GET /health?with_vec=true` 返回 `vec` 块（ok/detail/models/backend/embed_model/rerank_model/dimensions）；向量缓存在 `work/vectors/<doc_id>.json`（含 model/dimensions，切片内容变了自动失效；**向量与索引都是可重建物，不进主系统**）；向量服务不可用时**降级为关键词检索**并在 `note` 写明原因（与 OCR 降级性质不同：答案仍带页码来源）；**实测**：开发机跑通**离线接线演练**（`--offline`：解析一个文本型 PDF → 1 个切片 → 混合检索命中并带页码与分数），质量门 **55 用例**全绿 + `ruff check` / `ruff format --check` 通过；**未验证（如实）**：开发机连不上政务内网网关（`URLError: timed out`），真实向量化/重排/语义判别**尚未验证**，需在有内网访问的机器上跑 `python scripts/vec_try.py`（默认自检）；**检索层存储选型统一为 OpenSearch**（崖山内核自带向量能力仅作备选、不作选型基线），**适配层尚未实现**（`VEC_BACKEND` 只支持 `local`，配置成 `opensearch` 会显式报错而非静默降级），**下一步：部署 OpenSearch 并补适配层** | 本轮待提交 |
| ai-1.0.0 | 2026-09-20 | **AI 能力服务全量 Java 化：`ai-service`（Python/FastAPI）→ `ai-backend`（Spring Boot 3.3.5 / Java 17）** | 与主系统**同栈**、仍**独立构建与部署**（默认 8100）。四类能力全部移植：**平台 OCR**（`PlatformOcrClient`：批量 ≤16 页/请求、并发 12、"批量响应 blocks 在 `results[i]` 不在顶层"的坑、失败页占位**不兜底**、健康探针带 TTL 缓存）、**PDF 解析**（PDFBox 3 替代 PyMuPDF：页数 / 逐页文本 / 按 DPI 渲染 JPEG q85）、**确定性校验**（`Checks`：`BigDecimal` 大写金额解析与互校、比例合计容差 ±1、数值写法规整、答案数字可溯源；Java 正则显式 `UNICODE_CHARACTER_CLASS`，`BigDecimal` 一律 `compareTo` + `stripTrailingZeros` 归一）、**大模型**（`LlmClient` / `Prompts`（提示词**逐字照搬**）/ `ToolAgent`（8 轮工具循环 + 轮数用尽禁用工具再问）/ `QaService`（单条 system 必须在最前）/ `AnalyzeService`）、**检索链路**（`VecClient` + `RetrievalService`：字符 2-gram + IDF 关键词 + 向量召回 → 去重融合 → Reranker 精排；向量缓存 `work/vectors/`；向量/重排不可用**降级关键词并写 note**）；新增 `OpenSearchIndex` kNN 适配层（**未实测**：集群未部署；配 `opensearch` 时**显式报错、不静默降级**）。接口层：`/health`（`with_ocr`/`with_llm`/`with_vec` 三探测）、`/analyze`、`/ocr/pdf-info`、`/ocr/file`、`/documents`（增删查）、`/upload-tasks`（**先返回、后台解析**、状态机、取消/移除、重启把残留标失败）、`/chat`；响应形状与 Python 版**逐字段一致**（成功 `{code,data}`，失败 HTTP 状态码 + `{"detail"}`）。**踩坑（都已写进代码注释）**：Lombok 注解处理器必须显式配 `annotationProcessorPaths`（否则满屏"找不到符号"、看着像缺类）；`@Async` 自调用走不到代理（改显式线程池提交）；`StoredDoc.PageInfo.page_no` 必须 `@JsonProperty`（否则页码静默丢失、整页被误判成空白页）；`.env` 需 `spring.config.import` 才等价于 Python 的 dotenv。**实测（真实内网网关 `10.254.208.35:8090`）**：扫描件平台 OCR 正确识别《中标通知书》正文；`/analyze` 8.73s（tokens 1375+779）输出带 `[P1]` 来源与置信度的结构化 markdown；上传任务 `QUEUED→DONE` 1.47s；`/chat` 10.27s、工具轨迹 `search_documents×2 → read_page`，对上"中标金额"给出金额并主动标注"千分位是中文逗号、**存疑**请核对原件"，对"付款方式"如实回答**文档中未找到**并给出依据。**74 个 JUnit 用例全绿**（`ai-1.0.1` 收口时为 81）；`Checks` 与 Python 版做过 **35 个用例的逐字段差分对照，完全一致**。Python 侧代码/脚本/文档的清理见下一轮 | 本轮待提交 |
| ai-1.0.1 | 2026-09-20 | **清理与回归单一主线：删除 Python 侧 `ai-service`，知识资产迁入 `ai-backend`** | Python 侧代码/脚本/测试/文档整体删除（`ai-service/` 50 个受控文件：2 份手册迁入 `ai-backend/docs`、其余 48 个删除），只保留**仍然有价值的知识**并迁入主线：两份只读接口手册（`平台OCR调用使用手册.md`、`Qwen3-VL-Embedding-Reranker调用手册.md`）与四份规划文档（架构评估与规范化、知识库总体架构与演进路线、知识库落地实施方案、embedding 端点部署方案）落 `ai-backend/docs/`，另新增《平台能力实测结论》把散落在 Python README 里的实测数字与踩坑结论固化下来（含平台 OCR 渲染参数/印章编造/并发、置信度四层口径、思维链吃光额度、单条 system、Embedding 不支持 MRL 降维、Rerank 返回入参下标、质量门用例数）；合成样本迁到 `ai-backend/work/samples` 并同步 `verify-e2e.ps1`；**保真修正**：PDFBox 逐页文本换行归一（`\r\n`→`\n`）且整页空白回空串，使 Java 与 PyMuPDF **逐字节一致**（电子版 182==182、扫描件 0==0，此前是 191 vs 182 / 1 vs 0）；旧 Python 服务容器退役（端口让给 Java 版）；全部相对路径引用（手册位置、样本路径、curl 示例）同步更新；**OpenSearch 仍待集群部署**（适配层已就绪、配 `opensearch` 显式报错） | 本轮待提交 |
| docs-1.0 | 2026-09-20 | **文档与脚本集约化重整（跨主系统 + AI 能力服务）** | ① **修表格格式**：ITERATION 总表内夹了空行，导致 `ai-1.0.0`/`ai-1.0.1` 两行被"踢出"表格（Markdown 里**空行即结束表格**）——已去除；全仓脚本体检另修 1 处单元格裸竖线（厂商手册表格里 `` `\|` `` → `` `\|` ``）。② **建两张地图**：新增 [`docs/README.md`](docs/README.md)（**唯一文档地图**：按角色导读 + 权威文档清单 + 已核减登记 + 维护约定）、重写 [`scripts/README.md`](scripts/README.md) 为**脚本与操作唯一清单**（场景速查表 + Shell 要求 + 分组明细；如实记录本机**无 Git Bash**、`scripts/*.sh` 暂时跑不了，并给出两条替代路径）。③ **核减过程性文档**（理由：一次性/已完成/已被取代的计划留在仓库只会制造歧义）：崖山两份（可行性分析 + 迁移实施方案）压缩为《崖山数据库与迁移约定》、知识库两份合并为《知识库实施方案与路线》、附件智能处理自测方案压缩为《附件智能处理-验收标准与现状》、双机 ARM 与 Gitea 两份并入《部署与发布全流程手册》附录 A/B、删除《embedding 端点部署方案》（平台已直接提供）《架构评估与规范化方案》（结论已在迁移对照表与 ITERATION）《流程模板图形化评估》（功能已于 v2.3 移除）。④ 根 `README.md` 增加"两张地图"入口，并把"文档地图同步""Markdown 表格两禁忌"写入硬性维护约定。 | 本轮待提交 |

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
- **未做（待确认）**：① 把 `scripts/*.sh` 改写/新增为 Windows 原生 `.cmd`/`.ps1` 等价实现
  （本机无 Git Bash，`make-release.sh` / `dev-reload.sh` / `db-sql.sh` 目前只能装 Git Bash 或走 WSL）；
  ② `.workbuddy/memory/` 下 7 份助手工作日志（约 1900 行，**未被 git 跟踪**）是否清理。

---

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
| 一键启动/停止 / 开发机重建 | ✅ | `deploy/windows`、`deploy/docker`、根目录 `start-dev.cmd`；**开发机一键重建** `scripts/dev-reload.sh`（v3.3.1） |
| 列表/附件筛选多选 | ✅ | v1.5：列表（类型/状态/年度）与附件中心（类别/归属）多选 |
| 金额口径实时同步 | ✅ | 后端查询时实时汇总（合同 + 付款），列表/详情/资金页/统计口径一致 |
| 部署方案 | ✅ | v3.0~v3.1：Docker（backend + frontend nginx，数据库为**外部崖山主备**）；服务器**不存源码**，只放 compose + `.env`；发版 `make-release.sh` / 升级 `pm-upgrade.sh`（自动切 `IMAGE_TAG`） |
| 数据库国产化（崖山 Oracle 模式） | ✅ | v3.0 起替代 MySQL + Flyway；自研 `YashanMigrationRunner`；`db/migration-yashan/` **V1~V12**；主键 identity；驱动级 primary + TAF 高可用 |
| 演示数据脚本 | ✅ | `seed-demo.mjs`（父子项目 + 每核算单元 2~5 份合同 + 付款里程碑 + 项目分工）、`seed-attachments.mjs`（阶段/合同/付款三类附件，含缺件场景）、`demo-reset.sql`（物理清空，v3.5.1） |
| 开发机数据库工具 | ✅ | v3.5.1：`scripts/db-sql.sh` + `scripts/jdbc/RunSql.java`（无 yasql 客户端时手工查/改库，口令不打印不落盘）；脚本清单见 `scripts/README.md` |
| 文档维护约定 | ✅ | v1.1 起：每次迭代同步更新本文档（总表 **+ 各迭代明细**）、设计稿（§0/受影响章节/§13）、README；v3.5.1 起补：**每次迭代必须用演示数据体现新功能**、涉及脚本须同步 `scripts/README.md` |
| 附件智能处理（OCR / 大模型抽取 / 向量检索） | 🚧 进行中 | **AI 能力服务已全量 Java 化**：`ai-backend`（Spring Boot 3.3.5 / Java 17，独立构建与部署，默认 8100），接口 `/analyze`、`/ocr/pdf-info`、`/ocr/file`、`/documents`、`/upload-tasks`、`/chat`。平台 OCR + 千问对话 + Qwen3-VL Embedding/Reranker **全部在真实内网网关上验证通过**（扫描件 OCR 1.3~1.7s、抽取 ≈8s、问答 ≈10s、向量化 4096 维且余弦 0.3245 与 Python 基线**逐位一致**、PDF 文本层与 PyMuPDF **逐字节一致**）；**81 个 JUnit 用例全绿**；Docker 镜像已构建并起容器实测通过。原 Python 版已删除（知识资产迁入 `ai-backend/docs/`）。**下一步**：OpenSearch 集群部署后实测 kNN 在线路由（适配层已实现、**路由已接但未实测**）+ 建评测集 + 与主系统集成（JWT + 解析结果回写） |
| 设计稿 Q1–Q15 评审回写 | ⏳ 待办 | 待业务反馈 |

## 运行方式速查

```bash
# 一键启动：见 deploy/README（Windows: start-dev.cmd / 双击；Docker: deploy/docker；生产见 docs/部署与发布全流程手册.md）
# 数据库：崖山 YashanDB（Oracle 模式）主备；后端连接用环境变量（见 README/deploy/README）：
#   export YASHAN_MASTER_IP=10.254.212.106 YASHAN_STANDBY_IP=10.254.212.107
#   export YASHAN_DB=PM YASHAN_USER=pm YASHAN_PASSWORD=xxx
cd backend  && mvn spring-boot:run         # :8080（自研 Runner 自动执行 db/migration-yashan 建库，现 V1~V12）
cd frontend && npm install && npm run dev  # :5173
# Docker 本机（前端 nginx 对外端口见 deploy/docker/.env 的 WEB_PORT，本机约定 8088）

# —— 演示数据（走真实 API；详见 scripts/README.md）——
bash scripts/db-sql.sh scripts/demo-reset.sql   # 可选：物理清空演示数据（从干净基线开始）
node scripts/seed-demo.mjs                      # 项目/合同/付款/分工（默认 http://127.0.0.1:8088）
node scripts/seed-attachments.mjs               # 阶段/合同/付款三类附件

# —— 开发机辅助 ——
bash scripts/dev-reload.sh [all|backend|frontend]   # 重建镜像 + 重启（自测用；不主动执行，按需）
bash scripts/db-sql.sh <sql文件>                    # 手工查/改库（无需 yasql 客户端）
bash scripts/make-release.sh <版本>                 # 产发布包（发版）
```

> ⚠️ 本系统删除一律是**逻辑删除**（`deleted = 1`），而 seed 走 API 删项目 → 反复重跑会堆积历史行；
> 直接翻数据库时请先按 `deleted = 0` 过滤。

## 后续待办（Backlog）

1. **设计稿 Q1–Q15 评审**：按业务反馈修订流程模板/字段/口径并回写文档。
2. **合同与付款的勾稽校验**：各期付款合计 vs 合同金额、比例合计 100% 的自动核对提示；合同变更/结算流程化。
3. **合同附件必传校验**：按合同类型规定必备件（如主合同必须有法务意见书、会签表、履约保函），缺失时提示或拦截。
4. 附件体验增强：pdf/图片缩略图、批量上传与打包下载；OFD 在线预览单独评估（转图片 + OCR 保底）。
5. **附件保留策略**：删除项目后附件元数据与物理文件的治理（当前刻意逻辑保留以便审计追溯）。
6. 工程化：前端按路由代码分包（当前单包较大）、后端 profile（dev/prod）、数据库每日备份、操作日志导出。
7. 阶段逾期提醒增强：已具备基础版，可补列表页逾期角标与全局提醒。
8. **AI 能力服务（`ai-backend`）检索层与主系统集成**：**已全量 Java 化并验证**——Spring Boot 3 / Java 17、与主系统同栈、独立部署；平台 OCR + 千问对话 + Qwen3-VL Embedding/Reranker **全部在真实内网网关上跑通**；**81 个 JUnit 用例全绿**；Docker 镜像已构建并起容器实测通过；原 Python 版 `ai-service/` 已删除、知识资产迁入 `ai-backend/docs/`。**下一步**：① **OpenSearch 集群部署后实测 kNN 在线路由**（适配层已实现、路由已接但未实测；集群不可达/配错时**显式报错**而非静默降级）→ ② **建评测集**（30~50 附件 + 100~200 QA，recall@5 / MRR / 引用准确率 / 数字幻觉率基线）→ ③ 按 [`ai-backend/docs/知识库实施方案与路线.md`](ai-backend/docs/知识库实施方案与路线.md) 推进 **P1 表驱动异步任务**（迁移 V13 `attachment_ai_task`：上传→解析→切片→向量→索引→回写）与**主系统集成**（JWT + 解析结果回写 + 问答入口并入主系统前端）。

## 变更记录说明

> 本机评审/迭代改动已整理并合入正式迭代（见上方「迭代总表」**v1.5**），对应提交 `9a8e387`、`6f02c99`（均已推送）。按维护约定，所有已实现并验证的迭代均须同步更新本文档、设计稿（§0/§13）与 README 后一并提交，**不再保留"待提交/半成品"章节**。

