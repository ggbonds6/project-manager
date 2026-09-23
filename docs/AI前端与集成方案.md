# AI 能力服务的前端方案与主系统集成评估

> 评估对象：AI 能力服务（`ai-backend`，Java，独立部署，默认 8100）的**前端形态**与**与主系统的集成方式**。
> 结论先行：**不单独做一套 React 前端**——把它作为主系统前端的一个菜单 + 一个全局悬浮入口，
> AI 服务保持**纯后端**（现有的 `static/index.html` 调试页随之上移/退役）。
> 理由与落地步骤见下。

---

## 1. 需求（要达成的四件事）

| # | 需求 | 说明 |
| --- | --- | --- |
| ① | **知识库后台面板** | 看文档库、解析/索引任务与失败、检索调试（召回 + 精排分数）、后续的评测集与指标 |
| ② | **附件 → 向量化** | 主系统上传附件后**后台自动解析入库**；附件列表要能看到"是否已可检索" |
| ③ | **全局悬浮 AI 图标** | 任意页面右下角一个小图标，点开对话框，随时提问 |
| ④ | **基于知识库 + 业务数据的问答** | 既要能引用附件原文（带出处页码），也要能回答"这个项目预算多少 / 已付多少"这类结构化问题 |

---

## 2. 三条路线对比

| 路线 | 做法 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- | --- |
| **A. 独立 React 前端** | 给 `ai-backend` 单建一个 Vite 工程，单独部署 | AI 侧完全自治、可独立发版 | ① 第二套构建链与依赖（又一个 node_modules、又一份 nginx）；② **鉴权要重做一套**（主系统已有 JWT/角色）；③ 附件预览、上传队列、antd 主题、markdown 渲染这些**主系统里已经写好的组件全部用不上**；④ 用户要跨两个系统登录 | ❌ 不推荐（成本最高、收益只有"解耦"这个名义） |
| **B. 并入主系统前端（菜单 + 全局悬浮）** ✅ | AI 只提供 API；主系统前端加一个菜单页（知识库面板）+ 一个全局悬浮对话入口 | 复用 React18 + antd5 + 现有鉴权/路由/请求封装/附件预览/上传队列；**一次部署**；用户一个系统里完成全部操作；问答入口贴着业务上下文（在项目详情页提问默认就限定该项目） | 主系统前端体积略增；AI 相关 UI 与主系统同版本发布（前端耦合，但后端仍独立） | ✅ **推荐** |
| **C. 混合** | 用户侧入口并入主系统（菜单 + 悬浮），仅保留 AI 服务自带的单页调试页供运维排障 | 兼顾"用户零切换"与"运维可独立排查" | 多维护一个调试页（但本来就存在，且零成本） | ✅ 作为 B 的补充（把 `static/index.html` 明确降级为**运维调试页**，不再加功能） |

**为什么不是 A**：本项目里 AI 的**用户可见面**其实只有三块（知识库面板、附件状态、对话窗），
而这三块需要的基建（antd 表格/抽屉、上传队列与进度、附件预览按页打开、JWT 与角色、统一错误提示）
**主系统前端全都有**。独立前端等于把这些重写一遍，还会引入"两套登录态"。

---

## 3. 推荐方案（B + C）的界面设计

### 3.1 主导航新增一项

| 菜单 | 谁可见 | 页面内容（页签） |
| --- | --- | --- |
| **AI 与知识库** `/ai` | 管理员 + 项目经理（`ADMIN`/`MANAGER`） | ① **文档库**：已入库文档列表（文件名/来源项目/页数/切片数/解析时间/状态），可搜索、删除、重新解析、查看解析结果（复用附件预览组件）<br>② **任务队列**：解析任务（排队/进行中/完成/失败 + 失败原因），支持取消/重试——**复用主系统既有的上传任务中心交互**<br>③ **检索调试**：输入问题 → 显示"召回候选 → 融合 → 精排前后分数"，用来判断"答不出来"是解析问题还是检索问题<br>④ **服务自检**：`/health`（网关连通、OCR/向量化/重排是否可用、当前 `VEC_BACKEND`）<br>⑤ **评测与指标**（P2）：recall@5 / MRR / 引用准确率 / 数字幻觉率 |

### 3.2 全局悬浮 AI 图标（任何页面可用）

- 挂在 `layout/MainLayout.tsx`（与左侧菜单同级），右下角固定小图标；点击打开右侧 `Drawer`（不打断当前操作）。
- **上下文感知**：在项目详情页打开时，作用域默认 = 该项目；其他页面默认 = 全部可访问范围，并在窗口顶部显式显示"当前范围：XX 项目（可切换）"。
- 对话窗要素：消息流（markdown 渲染，复用现有依赖）、**答案里的出处可点击**（`[P1]` → 直接打开该附件在第 1 页的预览）、"依据/未找到"分区展示、`👍/👎 + 纠错`（P2，用于攒评测集）。
- 明确提示：涉及**结构化数据**的问题（预算/付款/统计）由"受控查询工具"回答，并标注"来自系统数据"；涉及**文档内容**的问题必须给出处页码；查不到就写"未找到"，不允许猜。

### 3.3 附件页的融合（需求 ②）

- 附件列表/附件中心每个附件增加一列/标签：**未解析 ｜ 解析中（进度）｜ 已可检索 ｜ 解析失败（原因 + 重试）**。
- 上传成功后**自动**触发解析（可配置开关）；失败不阻塞上传本身。
- 解析产物（文本、切片、向量）**只存在 AI 服务侧**，主系统只存"状态 + doc_id"（符合"知识服务只存可重建物、主系统是唯一事实源"的既有边界）。

---

## 4. 集成接口与边界（后端要做的事）

### 4.1 数据与控制流

```
主系统前端 ──(1)上传附件──▶ 主系统后端 ──(2)存存储(local/OBS)+附件记录
                                   │
                                   └─(3)POST /documents(multipart 或 OBS 预签名 URL)──▶ AI 服务
                                                                                    │
主系统前端 ──(4)轮询任务状态──▶ 主系统后端 ──(5)GET /upload-tasks/{id}──────────────▶ AI 服务
                                   │
                                   └─(6)回写 attachment.ai_index_status / 任务表(V13)
主系统前端 ──(7)提问──▶ 主系统后端 ──(8)权限校验 + 作用域解析──▶ POST /chat(doc_ids/项目范围)
                                                                │
主系统前端 ◀──(9)答案 + 出处 ◀── 主系统后端 ◀────────────────────┘
```

**四条必须定死的边界**（与《知识库实施方案与路线》一致）：

1. **AI 服务不直连主系统数据库**：结构化问题一律通过主系统的受控查询接口（P2 的工具），否则权限、口径、审计全部失控；
2. **主系统是唯一事实源**：AI 服务只存切片/向量/OCR 中间产物（可重建）；
3. **权限在主系统收口**：每次问答由主系统解析出**允许的 `doc_ids`/项目范围**再传给 AI 服务，AI 服务不做权限判断；
4. **文件归属主系统**：AI 服务要么收 multipart，要么用**有时效的预签名 URL** 拉取，不长期保存原始附件。

### 4.2 主系统后端要新增的东西

| 项 | 内容 |
| --- | --- |
| 迁移 **V13** | `attachment_ai_task`（task_id/doc_id/状态/进度/失败原因/时间）、`attachment` 加 `ai_index_status`；`ai_ask_log`（问答留痕：谁、何时、问什么、引用哪些附件、耗时）——**这是审计场景的硬要求** |
| `AiServiceClient` | 轻量 HTTP 客户端（`RestClient`），基址 `AI_SERVICE_BASE_URL`（内网，如 `http://pm-ai-backend:8100`），带**服务间令牌**（共享 `JWT_SECRET` 或单独的 `AI_SERVICE_TOKEN`） |
| 接口 | `POST /api/attachments/{id}/ai-parse`（触发解析）、`GET /api/ai/tasks`、`DELETE /api/ai/tasks/{id}`、`POST /api/ai/chat`（**代理 + 权限 + 留痕**）、`GET /api/ai/documents`、`GET /api/ai/health`、`GET /api/ai/retrieval-debug` |
| 审计 | 问答与解析动作写入 `operate_log`（沿用现有留痕机制） |

### 4.3 部署与鉴权

- **同源**：前端只调**主系统后端**的 `/api/ai/*`（nginx 里已有的 `location /api/ → pm-backend:8080` 无需改动）。
  **不加** `location /ai-api/ → pm-ai-backend:8100` 这条反代——那会让浏览器**绕过主系统的权限解析**直接够到 AI 服务，
  与下面第 3 条边界自相矛盾（2026-09-20 修正：原文写作"前端只调 `/ai-api/*`"是错的）。AI 服务的 8100
  **只在内网可达**，仅被主系统后端调用。
- **鉴权**：主系统后端持有服务间令牌调用 AI 服务（`AI_SERVICE_TOKEN`，与用户 JWT 分离）；**浏览器永远不直连 AI 服务**。
- **AI 服务侧的 `/v1` 前缀**：按路线图加版本前缀（`/v1/documents`、`/v1/chat`…），给未来留出契约演进空间。

---

## 5. 分期与工作量（建议顺序）

| 阶段 | 范围 | 交付 | 估计 |
| --- | --- | --- | --- |
| **P0 打通入口** | 主系统代理 + V13（任务表/状态列）+ 附件页状态标签与自动触发 + **全局悬浮对话窗**（引用附件原文、带出处页码、未找到如实说） | 需求 ②③④（文档问答部分）可用 | **3~5 人日** |
| **P1 知识库面板** | `/ai` 菜单页：文档库 / 任务队列 / 检索调试 / 服务自检 + 问答留痕落库 | 需求 ① 可用；运维可自查"答不出来是谁的问题" | **2~3 人日** |
| **P2 结构化问答与质量** | 受控查询工具（预算/付款/统计）+ 指标口径层 + 评测集与 👍/👎 反馈闭环 | 需求 ④ 的"业务数据"部分；质量可量化 | **5~8 人日**（并依赖 OpenSearch 落地） |
| **P3 可选** | Agentic RAG（渐进式检索）、GraphRAG 评估、父块上卷、权限级过滤 | 按需 | 按需 |

> **依赖**：P2 的"业务数据问答"必须等主系统的受控查询接口就绪；检索质量的上限取决于 OpenSearch + 评测集。
> **前置**：AI 服务的 `/v1` 版本前缀与 JWT 共享密钥要先定（属 P0）。

---

## 6. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| AI 服务不可用（平台网关/服务本身） | 解析与问答直接失败 | 主系统**不降级**：附件页显示"AI 服务不可用"、问答给明确错误；**不让用户以为"没找到"**（这正是删掉本地兜底引擎的同一条理由） |
| 作用域/权限泄漏 | 看到不该看的项目文档 | 权限**只在主系统**判定；AI 服务只接受 `doc_ids`；问答留痕可追溯 |
| 解析成本与耗时 | 大附件拖慢上传体验 | 上传与解析**解耦**（上传立即返回、解析异步 + 进度），失败可重试 |
| OpenSearch 未部署 | 检索仍是进程内余弦，规模受限 | P0/P1 用 `local` 即可（几万切片够用）；P2 前完成集群与适配层实测 |
| 前端与 AI 服务版本错配 | 接口变更导致页面报错 | `/v1` 前缀 + 主系统后端做适配层（前端只认主系统契约，不认 AI 原始契约） |
| 双套问答入口（悬浮 + 菜单）语义重叠 | 用户困惑 | 悬浮窗=随手问（带当前页面上下文）；`/ai` 页=管理与调试；两者共用同一套对话组件 |

---

## 7. 与既有文档的关系

- 三层边界与四层架构、P0~P4 路线：见 [`../ai-backend/docs/知识库实施方案与路线.md`](../ai-backend/docs/知识库实施方案与路线.md)（本文只做**前端形态与集成方式**的取舍与落地清单）；
- AI 服务现有接口与契约：见 [`../ai-backend/docs/迁移方案与对照表.md`](../ai-backend/docs/迁移方案与对照表.md) §3；
- 验收标准（语言无关）与当前实测数字：见 [`附件智能处理-验收标准与现状.md`](附件智能处理-验收标准与现状.md)；
- 本文**不改**任何既定边界，只是把"问答入口并入主系统前端"这条既有结论**展开成可施工的方案**。

---

## 8. 追加决策与答复（2026-09-20）

### 8.1 没有 OpenSearch，现在这条路能通吗？——**能，已经通了**

`VEC_BACKEND=local`（进程内余弦 + `work/vectors/<docId>.json` 缓存）**不是占位实现**，它是可用的检索后端：

| 环节 | 现状 |
| --- | --- |
| 上传附件 → 解析（平台 OCR / 文本层）→ 切片 → 向量化（4096 维） | ✅ 已实测（Java 版，真实内网网关） |
| 混合检索（向量召回 + 关键词召回 → 融合 → Reranker 精排）→ 带出处页码的答案 | ✅ 已实测：`/chat` 10.27s，工具轨迹 `search_documents×2 → read_page`，金额给出并主动存疑、查不到如实说"未找到" |
| 单元测试 | ✅ 81 用例全绿（含"向量不可用降级关键词并写 note"） |

**规模边界**：`local` 后端适合**几万切片**（当前项目是百级附件、千级切片，余量充足）；
OpenSearch 解决的是**十万级以上 + 并发 + 元数据过滤**，那是**规模问题、不是功能前提**。
→ **接入不必等 OpenSearch**：P0（主系统代理 + 附件状态 + 悬浮问答）现在就能做；集群上线后只切一个环境变量
（适配层已接线、未实测，切换前按 `ai-backend/docs/迁移方案与对照表.md` §5 四步验证）。

### 8.2 问答模块访问主系统表数据：**走内部 HTTP RPC（受控查询接口），不用 MCP**

| 方案 | 评价 |
| --- | --- |
| **主系统内部 RPC（采用）** | 主系统已有 JWT/角色/字典/统计口径/审计，**查询必须在它那里收口**；AI 服务与主系统同内网、已是 HTTP 依赖，新增一条契约成本最低；权限、口径、留痕三件事都能天然落位 |
| MCP（暂不采用） | 价值在"**多个异构 AI 客户端**共用同一批工具"；当前只有一个消费方，引入 MCP server/注册/传输只会多一个组件。等真出现多入口（如另一个 Agent/工作流）再把这批 RPC 包一层 MCP —— **不返工**，因为工具定义本来就在主系统侧 |

**渠道定义（定死，避免以后各写各的）**：

1. 主系统暴露 `/api/ai/query/*`（如 `projects` / `contracts` / `payments` / `stats` 四类），
   **只接受结构化参数**（项目 id、年度、类型、聚合方式、分页），**不接受 SQL、表达式或字段名自由拼接**；
2. 返回统一结构：`{data, unit, 口径说明, data_time, scope}` —— 让模型能解释"这个数字是什么口径、什么时候的数据"；
3. AI 服务侧把它包装成**工具**（与 `search_documents` / `read_page` / `calculate` 同一层），
   提示词明确："**结构化问题必须走这些工具，不得自行推算或估算**"；
   ⚠️ **模型侧只暴露 1 个工具** `query_business_data(entity, filters)`，`entity ∈ {projects, contracts, payments, stats}`
   —— 4 个 RPC 不变，但"一个实体一个工具"会让工具数膨胀到 7 个、拉低选择准确率与提示词缓存命中率。
   依据与取舍见 [`AI工具集与检索编排评估.md`](AI工具集与检索编排评估.md)；
4. 每次调用写 `ai_ask_log` + `operate_log`（谁、何时、问什么、查了哪些数据、答了什么）；
5. **三条禁止**：AI 服务直连数据库 ❌ ／ 把主系统库连接串交给 AI 服务 ❌ ／ 让模型生成 SQL（NL2SQL）❌
   —— 审计场景不可控、难复核、有注入与性能风险。

### 8.3 AI 前端设计：参考成熟做法，但只做**最小可用集**

| 场景 | 成熟产品常见做法（可参考） | **本项目采用** | 明确不做（避免冗余） |
| --- | --- | --- | --- |
| 知识库后台 | Dify / RAGFlow / FastGPT 一类：文档列表（状态·切片数·解析时间）+ **检索测试**（输入 query → 命中片段与分数）+ 重新解析/删除 | ✅ 全部采用；检索测试尤其重要（用来定位"答不出来"是解析问题还是检索问题） | 切片在线编辑、可视化流程编排、模型/提示词市场、标注工作台 |
| 问答窗 | Copilot / ChatGPT 一类：右侧栏或抽屉、消息流、**引用可点击跳原文**、工具过程可折叠、复制/重新生成/停止 | ✅ 采用（引用可点击到"附件第 N 页"是本项目最能建立信任的一点） | 多模型切换、插件市场、语音、图片生成 |
| 悬浮入口 | Intercom / Crisp 客服气泡、Copilot 侧栏 | ✅ 右下角小图标 → 右侧抽屉（不打断当前操作） | 主动弹窗式推送、营销文案 |
| 会话管理 | 侧栏多会话历史、重命名/删除 | 🕐 **P2 可选**（先做单会话 + 清空，避免一上来就写一堆状态管理） | 会话分享/协作编辑 |
| 反馈闭环 | 👍/👎 + 纠错进评测集 | 🕐 **P2 采用**（审计场景价值最高的一环：错误答案是最宝贵的评测数据） | 精细标注体系 |
| 范围/权限提示 | 顶部显式显示"当前范围"，可切换 | ✅ **必须**（安全相关，不可省；默认跟着当前页面：项目详情页=该项目） | —— |

> 一句话原则：**这一版只做"能问、能查出处、能知道可不可用、能反馈"四件事**；
> 编排、插件、多模型那些属于"看起来专业、实际增加维护面"的功能，一律不做。

### 8.4 `ai-backend/static/index.html` 的处置：**前端落地后删除**

它是 Python 时代留下的单页调试页（811 行 + vendored `marked.min.js`）。等主系统的 `/ai` 面板与悬浮问答落地后，
它提供的查看/上传/问答能力被完全覆盖，**保留只会形成两套 UI 各自演进**。

- 过渡期：**冻结**（不再加任何功能），仅在排障时使用；
- 删除时机：P0 + P1 验收通过后，随一次清理提交删除 `ai-backend/static/` 整个目录，
  并同步移除 `ai-backend/Dockerfile` 里的 `COPY static/` 与 `README` 中的相关段落（已登记为待办）。

### 8.5 本轮已执行的决策（对应负责人指示）

| 决策 | 执行 |
| --- | --- |
| 开发机脚本转 Windows 原生 | `scripts/make-release.sh`、`dev-reload.sh`、`db-sql.sh` → `.ps1`（删除 `.sh`，避免两套漂移）；当时服务器升级脚本 `pm-upgrade.sh` 保持 `.sh`（它只在 Linux 服务器上跑）——**该脚本已于 v3.6.3 被统一的 `scripts/pm.sh` 取代**（见部署手册 §0.4/§4） |
| 本机配置移出仓库树 | `local/export`（数据库导出、`口令与配置清单.md`、SSH 公钥）→ **`E:\env\pm-local\export`**；仓库内 `local/` 只留两个本机启动助手（已 gitignore） |
| 接入不必等 OpenSearch | 见 §8.1：`VEC_BACKEND=local` 即可打通全链路，P0 现在可开工 |
| 数据访问渠道 | 见 §8.2：主系统受控查询 RPC + 工具化，不用 MCP、不直连库、不生成 SQL |

---

## 9. P0 接口契约（**冻结**：前端与主系统后端各按此实现，不再各自发明）

统一的响应信封沿用主系统既有约定（`{code, message, data}`，成功 `code=0`，前端 `api.get<T>()` 已自动解包到 `data`）；
路径前缀一律 `/api/ai`，**前端不认 AI 服务的原始契约**（AI 服务字段变了只改主系统适配层）。
字段命名：**camelCase**（与主系统既有接口一致）。时间：`yyyy-MM-dd HH:mm:ss`。

| # | 方法与路径 | 入参 | 返回 `data` | 权限 |
| --- | --- | --- | --- | --- |
| 1 | `GET /api/ai/health` | — | `{available, aiServiceBaseUrl, vectorBackend, platformReachable, models:{chat,ocr,embedding,reranker}, documentCount, pendingTaskCount, checkedAt, message}` | 登录 |
| 2 | `GET /api/ai/documents` | `keyword, projectId, page=1, size=20` | `{total, records:[{docId, filename, projectId, projectName, attachmentId, pageCount, chunkCount, sizeBytes, indexedAt, indexStatus, error}]}` | 登录（按可访问项目过滤） |
| 3 | `DELETE /api/ai/documents/{docId}` | — | `{docId, deleted}` | ADMIN/MANAGER |
| 4 | `GET /api/ai/tasks` | `status, projectId, page=1, size=20` | `{total, records:[{taskId, attachmentId, filename, projectId, projectName, status, progress, docId, error, createdAt, updatedAt}]}` | 登录 |
| 5 | `GET /api/ai/tasks/{taskId}` | — | 同上单条 | 登录 |
| 6 | `POST /api/ai/tasks/{taskId}/retry` | — | `{taskId}` | ADMIN/MANAGER |
| 7 | `POST /api/ai/attachments/{attachmentId}/parse` | — | `{taskId, docId}` | ADMIN/MANAGER |
| 8 | `GET /api/ai/attachments/status` | `attachmentIds=1,2,3`（逗号分隔，上限 200） | `[{attachmentId, indexStatus, progress, docId, error}]` | 登录 |
| 9 | `POST /api/ai/chat` | `{question, projectId?, attachmentIds?, conversationId?, topK?}` | `{conversationId, answer, citations:[{index, docId, attachmentId, filename, pageNo, snippet, score}], systemData:[{label,value,unit,caliber,dataTime,scope}], toolTrace:[{name,summary,elapsedMs,hitCount}], degraded, notice, elapsedMs, logId}` | 登录（**作用域在主系统解析**） |
| 10 | `GET /api/ai/ask-logs` | `page, size, userId, from, to` | `{total, records:[{logId, userId, userName, question, projectId, attachmentIds, answerDigest, citedCount, elapsedMs, degraded, createdAt}]}` | ADMIN（P1 使用） |

**枚举取值（定死，前端直接映射中文标签）**：

| 字段 | 取值 | 中文标签 |
| --- | --- | --- |
| `indexStatus`（文档/附件） | `NOT_PARSED` / `PARSING` / `READY` / `FAILED` | 未解析 ｜ 解析中 ｜ 已可检索 ｜ 解析失败 |
| 任务 `status` | `QUEUED` / `RUNNING` / `DONE` / `FAILED` | 排队中 ｜ 解析中 ｜ 已完成 ｜ 失败 |
| `vectorBackend` | `local` / `opensearch` | 进程内向量 ｜ OpenSearch |

**P0 只实现 #1~#9**；`systemData` 在 P0 恒为 `[]`（受控查询工具属 P2）；#10 随 P1 的留痕页一起做。
**AI 服务不可用时的行为**（不许含糊）：`#1` 返回 `available=false` + 中文原因；
`#9` 直接返回业务错误（`code≠0`，message 明确写"AI 服务不可用"），**不允许降级成"未找到"**。

**引用里的 `attachmentId` 由主系统补齐**（AI 服务只认 `doc_id`，它不持有附件语义）：主系统按 `doc_id → attachmentId`
（`attachment.ai_doc_id` / `attachment_ai_task`）回填；映射不到（如文档已删）时为 `null`，前端据此**把该条引用显示为
"文件名 第 N 页（附件已删除）"而不是做成可点击链接**。补充原因：`citations` 是前端"点一下跳到第 N 页"的唯一依据，
若只给 `doc_id`，前端还得再查一次文档列表做映射——那是把主系统的知识漏给前端，不如在主系统一次补齐。

---

## 10. 前端工程规范（AI 模块落地时遵守）

| 项 | 规定 |
| --- | --- |
| 目录 | 页面 `src/pages/ai/*`（`AiPage.tsx` + 各页签子组件）；对话组件 `src/components/ai/*`（**悬浮窗与 `/ai` 页共用**，不许写两份）；接口 `src/api/ai.ts`；类型 `src/types/ai.ts` |
| 请求 | 只用 `@/api/http` 的 `api.get/post/put/del/upload`（自动带 token、自动解包 `data`、错误已统一 toast）；**不引 axios 实例**、不手写 fetch |
| 类型 | 契约字段在 `src/types/ai.ts` 里与 §9 一一对应；枚举用字符串字面量联合类型 + 中文标签映射表（同 `tagDict` 的做法） |
| 依赖 | **不新增 npm 依赖**：markdown 用既有 `react-markdown`，高亮/滚动/图标用 antd 与 `@ant-design/icons`，日期用既有 `dayjs` |
| 权限 | 菜单与按钮用 `useAuth()` 的 `user.role` 判定（`ADMIN`/`MANAGER`/`VIEWER`）；**前端隐藏只是顺手**，真正的拦截在主系统后端 |
| 状态 | 列表分页/加载/空态/错误态四态齐全（沿用既有页面写法）；对话流用 `useState` 局部状态，不引状态管理库 |
| 交互底线 | 任何"未找到"必须显示为**未找到**（不得显示为通用错误）；引用必须可点击跳附件原文；解析失败必须能看到原因并可重试 |
| 验收 | `npm run build`（`tsc` + `vite build`）零错误；`npm run dev` 下 `/ai` 页与悬浮窗可用；不改动非 AI 相关页面行为 |

> 与 §8.3 的关系：§8.3 定"做哪些、不做哪些"，本节定"怎么写"。两者冲突时以"最小可用集"为准。

---

## 11. P2 受控查询（结构化问答）契约 —— **冻结，两侧照此实现**

> 起因：问"某项目某阶段有几个附件"这类**事实问题**时，模型只能数出已入库文档（答成 1 个）——因为 AI 侧只有
> `search_documents / read_page / calculate`，**看不到任何业务数据**。按 §8.2 的既定边界补齐：
> **AI 服务不直连库、不生成 SQL**；结构化事实一律走**主系统受控查询接口**，AI 侧只暴露**一个**带枚举的工具。

### 11.1 流向（是"反向回调"，不是 AI 拉数据）

```
用户提问 → 主系统(解析权限/作用域) → AI 服务 /chat ──需要事实时──▶ 回调主系统 /api/ai/query/{entity}
                                                        ◀── {data, unit, caliber, data_time, scope} ──
```
主系统的 `pm-backend` **只在 compose 内网 expose 8080**（对外只有 nginx 的 `WEB_PORT`），所以 AI 服务必须通过
**主系统自己给出的、它可达的地址**回调；地址由主系统配置 `PM_AI_QUERY_CALLBACK_URL`
（例：`http://10.254.212.106:8080/api/ai/query`，即走 nginx 的 `/api/` 反代）。

### 11.2 主系统 → AI 的 `/chat` 请求新增字段（可选）

```json
"biz_query": {
  "url": "http://<主系统可达地址>/api/ai/query",
  "scope_token": "<短时效 JWT，claims: sub=userId, typ=ai_scope, jti, projects=[1,2,3], exp≤300s>",
  "entities": ["projects", "contracts", "payments", "stats"]
}
```
- **`scope_token` 是唯一授权凭据**：主系统用 `JWT_SECRET` 签发（与用户 JWT 同一把密钥，靠 `typ=ai_scope` 区分类型、
  `jti` 供排查定位），`projects` 即该用户可访问的项目 id；
  查询接口只认 token 里的范围，**绝不接受 body 里传来的 projectId 去越权查询**（越界一律 403）。
- **有效期上限 300s**（`ScopeTokenService.MAX_TTL_SECONDS = 300`，配大了会被夹回、只能配小），与超时链对齐：
  前端 180s / nginx 300s / 主系统 `AI_CHAT_TIMEOUT` 180s。**必须**大于一轮问答最长耗时，否则问答后段的回调会 401，
  而模型会把"授权过期"说成"无权查看/系统里没有数据"——答案直接错（§11.6 红线）。
- 不传 `biz_query` 时 AI 侧**不注册**该工具（行为与本版本前完全一致，便于分批发版）。

### 11.3 工具（AI 侧只这一个）

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `query_business_data` | `entity`（枚举 `projects`／`contracts`／`payments`／`stats`）、`filters`（对象，字段由主系统定义）、`limit`（可选，默认 20、上限 100） | 描述里写死："**项目/合同/付款/附件数量/统计口径这类事实必须用它**，不得靠文档推测、不得自行推算"，并列出每个 entity 支持的 filters 字段与示例 |

调用：`POST {biz_query.url}/{entity}`，Header `Authorization: Bearer <scope_token>`，body `{filters, limit}`。

### 11.4 四个 RPC 的入参与返回（主系统实现）

| entity | filters（结构化字段，不收 SQL/表达式/字段名拼接） | 返回要点 |
| --- | --- | --- |
| `projects` | `projectId?`／`name?`／`status?`／`type?`／`year?` | 项目事实：名称/编号/类型/状态/**当前阶段**/进度/预算/已付/合同数，以及**每阶段附件数** `phases:[{phaseName,status,percent,attachmentCount}]` |
| `contracts` | `projectId?`／`vendorName?` | 合同清单：名称/编号/供应商/金额/状态/签订日期/覆盖的子项目 |
| `payments` | `projectId?`／`nodeCode?`／`status?` | 付款记录：节点/计划金额/已付金额/日期/状态/归属合同 |
| `stats` | `projectId?`／`kind`（`phase_attachment_count`／`type_distribution`／`year_amount`） | 聚合值；`phase_attachment_count` 就是"某项目各阶段附件数"这类问题的正解 |

统一返回：
```json
{ "code": 0, "data": { "rows": [], "unit": "个", "caliber": "口径说明（含税/是否含子项目/按立项年度…）",
                       "data_time": "2026-09-23 16:40:00", "scope": "项目 12（含 3 个子项目）" } }
```
**`caliber` / `data_time` / `scope` 是硬要求**：模型必须能原话转述，否则数字无法审计。

### 11.5 提示词路由规则（AI 侧，必须写死）

1. 涉及**项目/合同/付款/附件数量/统计**等事实 → **必须**调用 `query_business_data`；查不到就如实说"系统里没有"，**不得**用文档内容或常识顶上；
2. 涉及**文档内容**（条款、金额条文、验收标准原文）→ 用 `search_documents` / `read_page`，给 `[n]` 出处页码；
3. 引用系统数字必须带**口径与数据时间**；两类信息混用时**分开陈述**（"系统数据：…；文档依据：…[1]"）；
4. **不许用 `calculate` 反推系统已有的数字**（例如用"合同总额 × 比例"倒推某笔付款）：能查到的数字只能查、
   算出来的数字必须标明是估算——两者在审计上完全不同。

### 11.6 留痕与错误

- 主系统：每次查询写 `operate_log`（谁/entity/filters 摘要/返回行数/耗时），并在 `ai_ask_log` 记本次问答用了几次系统查询、查了哪些 entity
  （**迁移 V14** 给 `ai_ask_log` 加 `biz_query_count` / `biz_entities` 两列，表数量仍是 16 张）；
- AI 侧：工具调用照常进 `toolTrace`（名称/参数摘要/耗时/命中数），前端可折叠查看；工具超时 **20s**；
- 错误必须**教会模型**：范围外 → `403 + "该项目不在本次授权范围（scope）内"`；filters 非法 → `400 + 列出该 entity 支持的字段`；空结果 → `200 + rows:[] + "该范围内没有匹配数据"`（**不是 404**，便于模型区分"没数据"与"调用失败"）；
- **口径**：受控查询接口使用**真实 HTTP 状态码**（`401`/`400`/`403`/`500`），**不走本仓库惯用的"HTTP 200 + 信封 code"**。
  理由：这套接口的消费方是**服务间调用**（AI 服务按 `statusCode()` 分流——403=授权范围问题、4xx=参数问题、5xx=调用失败），
  包成 200 会把控制流语义丢掉，AI 侧就只能靠解析 message 猜，错误话术必然漂移。
  越权判断在 **mapper 之前**完成（拒绝时对库的查询次数为 **0**）。

### 11.7 验收口径

1. 问"某项目某阶段有几个附件" → 数字与 `stats?kind=phase_attachment_count` **逐位一致**，且带口径与数据时间；
2. 问"预算多少/已付多少" → 与项目详情页、统计页一致；
3. 越权（token 里没有的项目 id）→ 403 且**不泄漏**任何数据；
4. 不传 `biz_query` → 行为与本版本前完全一致（回归）；
5. `ai_ask_log` 能查到该次问答用了几次系统查询、查了哪些 entity；
6. 冒烟评测集（`eval/smoke-30.json` + `node scripts/eval-ai.mjs`）给出：引用命中率、数字一致率、工具选择正确率、拒答正确率、平均延迟。

