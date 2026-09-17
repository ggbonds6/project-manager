# 迭代记录（开发进度日志）

> 本文件记录本系统从设计到前后端实现各迭代的进度、交付内容与验证情况，随每次迭代更新。

## 项目速览

| 项 | 内容 |
| --- | --- |
| 定位 | 单位内部政府信息化项目全生命周期管理（硬件 / 软件两类项目） |
| 前端 | React 18 + TypeScript + Vite + Ant Design 5（`frontend/`） |
| 后端 | Java 17 + Spring Boot 3.3.5 + MyBatis-Plus + 自研迁移 Runner（`backend/`） |
| 数据库 | 崖山 YashanDB（Oracle 模式）主备集群（主 10.254.212.106 / 备 10.254.212.107:1688，库/schema `PM`，业务账号 `pm`；v3.0 起，替代 MySQL 8 + Flyway） |
| 认证 | JWT + BCrypt，角色 ADMIN / MANAGER / VIEWER |
| 需求基线 | `docs/政府信息化项目管理系统-设计方案.md`（设计与实现同步稿，Q1–Q15 处置状态见 §11） |
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
| v3.5.1 | 2026-09-17 | 修复前端 React #310 + 演示数据补全 + 死代码清理 | **🔴 React #310（hook 顺序）**：v3.5.0 新加的 6 个 hook（`payNodeOrder`/`contractById` 两个 `useMemo`、三个筛选 `useState`、`filteredPayments`）被放在了组件里 `if (!detail) return …` **之后**——首屏（`detail` 为 null）走提前 return 少调这些 hook，加载完成后再调，数量不一致即抛 `Rendered more hooks than during the previous render`（#310）。修复：全部**前移到提前 return 之前**，并加注释说明"hook 必须在任何提前 return 之前"。已加结构性自检：组件内提前 return 之后 hook 数为 **0**（共 34 个 hook 全在其前）。**演示数据补全**：合同补 `contractStatus`（按推进度自动给 DONE/CHANGED/ACTIVE）、`settleAmount`（结算后才有）、`remark`、服务类合同的 `scopeRemark/验收标准`；新增 **4 份"方案评估"合同（状态=待签订、无付款记录）**用于演示状态标签与"零付款节点"的合同；核算单元合同数由 2~4 份扩到 **2~5 份**。**新增开发机工具**：`scripts/demo-reset.sql`（**物理**清空演示数据，解决"逻辑删除导致库里堆积 `deleted=1` 历史行、翻库时误以为当前数据缺字段"）、`scripts/db-sql.sh` + `scripts/jdbc/RunSql.java`（开发机无 yasql 客户端时手工查/改库，连接信息自动从容器环境变量或 `.env` 取，**口令不打印不落盘**）、`scripts/README.md`（脚本清单与用法）。**死代码清理**：前端 `tsc --noUnusedLocals --noUnusedParameters` **清零**（ProjectDetailPage 的 `Timeline`/`Typography`/未用 `useWatch`，以及 FlowTemplateDesigner/PhaseEditModal/ProjectOverviewPanel/StatsPage/SystemPage 的既有未使用导入）；后端未使用导入 **8 处清零** + 删掉死方法 `ContractController.normalize`（另 3 个疑似死方法是 `this::method` 方法引用，经核对**保留**）；`seed-attachments.mjs` 删掉只写不读的 `nameCache`。**实测**：清库重灌后 **29 份合同 / 77 笔付款 / 128 条项目分工 / 412 个附件**（阶段 217 · 合同 114 · 付款凭证 81）；`contract` 表 `deleted=1` 行数 **0**、在用合同字段缺失 **0**（仅 `settle_amount` 有 21 份为空——未结算，符合业务）；合同状态分布 ACTIVE 16 / DONE 6 / DRAFT 4 / CHANGED 3；类型分布 MAIN 9 / SUPERVISE 9 / EVAL 4 / TEST 4 / BUDGET 3。**约定**：前端改动不再由 AI 起浏览器做端到端验证（结构性检查 + 数据/接口核对即可，交互效果由用户刷新页面确认），已写入 `scripts/README.md` 与部署手册 §2.1 | `e76a183` |

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

---

## 功能完成度

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 登录 / 角色（三角色只读控制） | ✅ | admin/jingban01/lingdao01 |
| 项目管理（列表/卡片/筛选/分页/新建/编辑/删除） | ✅ | 删除仅管理员；列表为**可折叠树**（顶层展开子项目明细） |
| **总项目 / 子项目体系** | ✅ | v1.3：`project.parent_id`，子项目独立进度/付款，总项目为汇总容器 |
| **合同（每（子）项目独立）** | ✅ | v1.4 起口径：合同一律独立（同一承包商也分别登记）；管理员按合同录入金额，付款/凭证归属合同、与资金情况折叠面板联动 |
| 阶段模板与自动生成阶段实例 | ✅ | HW9/SW11，按核算单元生成 |
| 阶段推进 + 整体进度自动计算 | ✅ | 权重口径见设计稿 §9 |
| 项目详情五页签（流程/信息/资金/附件/日志） | ✅ | 附件按阶段展示与直达上传；资金页含合同面板 |
| 付款记录 CRUD + 资金汇总 | ✅ | 管理员录入；凭证附件预览/全屏 |
| 附件存储（本地盘 ↔ 华为 OBS） | ✅ | v3.1：`AttachmentStorage` 抽象，`app.storage.type=local\|obs`；切换对前端透明、元数据零迁移 |
| 工作台（汇总/待办/验收/逾期/最近更新） | ✅ | v0.7，v1.3 口径=核算单元（叶子） |
| 项目统计（ECharts，筛选联动） | ✅ | 状态·类型构成、流程阶段分布、年度资金（预算/合同/实付，合同去重） |
| 系统管理（用户/字典/阶段模板/日志） | ✅ | v0.8：仅管理员，写操作全留痕 |
| 一键启动/停止 | ✅ | 按平台分类（`deploy/windows`、`deploy/linux`）+ Docker 一体化（`deploy/docker`）；根目录 `start-dev.cmd` 为 Windows 入口 |
| 列表/附件筛选多选 | ✅ | v1.5：列表（类型/状态/年度）与附件中心（类别/归属）改为多选 |
| 金额口径实时同步 | ✅ | v1.5：后端查询时实时汇总（合同+付款），列表/详情/资金页/统计口径一致 |
| 部署方案 | ✅ | v3.0：Docker（backend+frontend nginx，数据库为**外部崖山主备**）、Linux/Win 分类脚本（检查 1688）、环境变量 YASHAN_* |
| 文档维护约定 | ✅ | v1.1 起：每次迭代同步更新本文档、设计稿与 README（v1.5 已清理“待提交记录”半成品章节） |
| 演示数据脚本 | ✅ | `node scripts/seed-demo.mjs`（父子+三类合同形态）、`seed-attachments.mjs` |
| 附件上传/下载/在线预览/全屏/逻辑删除 | ✅ | v1.6：图片/pdf/文本内嵌；md 用 react-markdown、docx 用 docx-preview、xls/xlsx 用 SheetJS；doc/ppt/pptx/ofd 等提示下载；全屏支持 |
| 文件类型字典 / 上传白名单 | ✅ | v1.6：V5 FILE_TYPE 字典 24 项；后端白名单强校验并提示允许清单 |
| 附件存储与预览加载 | ✅ | v1.6：本地 backend/uploads / Docker 卷 pm_uploads；预览均带加载等待态，解析失败或不支持类型明确提示下载 |
| 数据库国产化（崖山 Oracle 模式） | ✅ | v3.0：MySQL → 崖山 YashanDB 主备；自研 Runner 替代 Flyway；`db/migration-yashan/` V1~V8；主键 identity；驱动级 primary+TAF 高可用 |
| 设计稿 Q1–Q15 评审回写 | ⏳ 待办 | 待业务反馈 |

## 运行方式速查

```bash
# 一键启动：见 deploy/README（Windows: start-dev.cmd / 双击；Docker: deploy/docker；生产见 docs/部署与发布全流程手册.md）
# 数据库：崖山 YashanDB（Oracle 模式）主备；后端连接用环境变量（见 README/deploy/README）：
#   export YASHAN_MASTER_IP=10.254.212.106 YASHAN_STANDBY_IP=10.254.212.107
#   export YASHAN_DB=PM YASHAN_USER=pm YASHAN_PASSWORD=xxx
cd backend  && mvn spring-boot:run      # :8080（自研 Runner 自动执行 db/migration-yashan 建表/种子）
cd frontend && npm install && npm run dev  # :5173
node scripts/seed-demo.mjs              # 可选：重置演示项目（总项目/子项目+三种合同形态）
node scripts/seed-attachments.mjs       # 可选：为各核算单元阶段补齐典型附件
```

## 后续待办（Backlog）

1. **设计稿 Q1–Q15 评审**：根据贵方反馈修订流程模板/字段/口径并回写文档。
2. 附件体验增强：pdf/图片缩略图预览、批量上传/下载。
3. 工程化：前端按路由代码分包（当前单包较大）、后端 profile（dev/prod）与部署脚本、数据库每日备份、操作日志导出。
4. 阶段逾期自动标红提醒已具备基础版，可补充列表页逾期角标与全局提醒。

> 更新约定：每次完成一个可验证的迭代后，须在本文件追加一行记录，**同步更新 [`docs/政府信息化项目管理系统-设计方案.md`](docs/政府信息化项目管理系统-设计方案.md)（版本头/§0 修订记录/受影响章节/§13 差异清单）与 README**，并在提交说明中引用对应验证结果。具体规则见设计文档 §14 维护约定。

---

## 变更记录说明

> 本机评审/迭代改动已整理并合入正式迭代（见上方「迭代总表」**v1.5**），对应提交 `9a8e387`、`6f02c99`（均已推送）。按维护约定，所有已实现并验证的迭代均须同步更新本文档、设计稿（§0/§13）与 README 后一并提交，**不再保留"待提交/半成品"章节**。

