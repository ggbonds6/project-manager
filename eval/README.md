# `eval/` —— AI 问答冒烟评测集（"结构化问答"验收的量尺）

> 契约：[`docs/AI前端与集成方案.md`](../docs/AI前端与集成方案.md) §11.7（验收口径）。
> 本目录只有数据与说明，**不含可执行逻辑**；跑分脚本是 [`scripts/eval-ai.mjs`](../scripts/eval-ai.mjs)。

| 文件 | 作用 |
| --- | --- |
| `smoke-30.json` | 冒烟评测集（35 题，真实库数据），runner 的输入 |
| `probe.sql` | 真值只读探针：本集每个数字都能被它复现（复核用） |
| `truth-snapshot.json` | 由 `--write-truth` 生成的真值快照：**漂移基线**（上次页面接口算出的真值） |
| `report-<时间戳>.json` | runner 非 dry 运行后自动写出的逐题报告（回归对比用；**不进版本库**） |

---

## 1. 两条硬边界（先看，否则造出来的题没有价值）

1. **结构化题的标准答案必须来自接口/库，不能来自文档、也不能靠人推算。**
   本项目最初的缺陷就是："某项目某阶段有几个附件"被模型数成了"已入库文档数"（答 1 个）。
   所以结构化题的 `must_contain` 数字与 `expected.truth` **同源**：`truth` 指向主系统**页面接口**
   （用户在页面上看到的那套），runner 每次运行都现场把真值算出来再比对；
   `must_contain` 只是同一真值的静态副本（供 `--dry` 与人工阅读）。
   `truth` 解析不到时该题记 `SKIP(no-truth)`，**不把标准答案放宽到能跑通**。
2. **文档题的页码必须人工标注，不许瞎标。**
   `citation_pages=null` 的语义是"**待人工补页码**"：runner 对这类题记 `SKIP(unlabeled)`，
   既不算命中也不算失败。给人看的 `note` 里必须写清待补。
   本机**无法**打开物理 PDF（附件在容器卷里，仓库 `work/` 下没有副本），因此
   **当前 35 题的 `citation_pages` 全是 null**；补齐需要人工在附件预览里逐份核对页码后回填。

---

## 1.5 真值来源与局限（**这一节决定了"数字一致率"算不算数**）

评测要回答的问题是"**模型说的数字与用户在页面上看到的一致吗**"，所以真值取自**页面用的既有接口**
（带登录 JWT 就能调），不取自 AI 服务专用的短时效 `scope_token` 通道。

| 真值 `kind` | 调用的页面接口（照 `frontend/src/api/*.ts` 抄） | 取值字段 |
| --- | --- | --- |
| `phase_attachment_count` / `_all` / `_sum` | `projectApi.detail(id)` → `GET /api/projects/{id}`，再逐阶段 `attachmentApi.listByBiz` → `GET /api/attachments?bizType=PROJECT_PHASE&bizId={phaseId}` | `phases[].{id,phaseName,sortNo}`；响应数组长度 |
| `ai_ready_count` | 同上取附件 id，再 `GET /api/ai/attachments/status?attachmentIds=...` | `[].indexStatus === 'READY'` |
| `contract_total` | `contractApi.listByProject` → `GET /api/projects/{id}/contracts` | 条数 + `[].contractAmount` 求和 |
| `contract_amount_total` | 同上，但**只取总额**（用于"只问总额"的题，避免把份数变成扣分项） | `[].contractAmount` 求和 |
| `budget_amount` / `contract_amount_main` / `child_count` / `bid_amount` | `projectApi.detail(id)` → `GET /api/projects/{id}` | `budgetAmount` / `contractTotal` / `childCount` / `bidAmount` |
| `payment_records` | `paymentApi.listByProject` → `GET /api/projects/{id}/payments` | 条数 + `[].paidAmount` 求和 |
| `project_type_status` / `project_name_code` | `projectApi.detail(id)` → `GET /api/projects/{id}` | `type`/`status`、`code`/`name`（name 去括号后缀再比） |

**三条口径**（都由后端保证，写进每题 `note`）：

- 逻辑删除：`Attachment.deleted` 与 `BaseEntity.deleted` 上有 `@TableLogic`，MyBatis-Plus 在 SQL 层追加
  `deleted = 0`，所以页面接口返回的条数天然是"未删"口径，runner 不额外加条件（**也不该加**——
  否则就成了"评测脚本自己算的数字"，而不是"页面上的数字"）。
- 阶段附件只算 `biz_type='PROJECT_PHASE'`，**不含**合同/付款/项目级附件（与 §11.4 的
  `phase_attachment_count` 口径对齐，后端口径实现在 `ProjectMetricsService.attachmentCountByPhase`）。
- 合同金额不含 `changeAmount`（变更金额单列）。

**§11 受控查询（`/api/ai/query/*`）是第二条真值，只作交叉校验**：它按 §11.2 只认短时效 `scope_token`
（AI 服务专用），runner 的登录 JWT 调不动。所以：

```powershell
# 有 scope_token 时才跑交叉校验（额外报一条"两条路径口径一致率"）；拿不到就不做，绝不伪造令牌
node scripts/eval-ai.mjs --set eval/smoke-30.json --scope-token <scope_token>
```

**局限**（如实列出）：

1. **页码未标注** → 引用命中率当前恒为 `n/a`（这是设计，不是 bug）。
2. **文档正文不可核验** → 9 道文档题里 9 道是"弱判定"（只校验检索到并给出引用，不判文本事实），
   标了 `expect_weak: true`。
3. **演示库会被重新灌数据** → 造题当天实测漂移过一轮（阶段附件 31→34、READY 文档 3→6、
   付款 0→9 条、`bid_amount` NULL→1884960）。因此：
   - 每次改动或数据变动后，先跑 `--write-truth` 刷新 `eval/truth-snapshot.json`；
   - 非 dry 运行时 runner 会把"快照值 vs 现场值"的漂移打出来，提示哪些 `must_contain` 该更新。

---

## 2. 怎么造题

### 2.1 先挑真实项目（不许编 id）

```powershell
# 在仓库根执行；连接信息由 db-sql.ps1 自己从 deploy/docker/.env 或运行中的容器取，口令不落盘
powershell -ExecutionPolicy Bypass -File scripts\db-sql.ps1 eval\probe.sql
```

`probe.sql` 第 0 段列出所有顶级项目。本集固定选中的是：

| 项目 | 值 |
| --- | --- |
| id | **55** |
| code / name | `RJ-2026-005` / 中小学教育信息化改造（单项目） |
| type / status | `SW` / `RUN` |
| budget_amount | 2,100,000 元 |
| bid_amount | 1,884,960 元（造题当天为 NULL，复测时已登记） |
| 子项目数 | 0（`parent_id IS NULL`，单项目） |
| 关联合同数 | 4（`project_contract` → contract 100/101/102/103，合计 1,990,800 元） |
| 付款记录数 / 已付合计 | 9 条 / 1,855,560 元（造题当天为 0 条，复测时已灌入） |
| 阶段数 | 11，阶段附件数依次 `9/2/3/1/3/2/3/3/3/3/2`，合计 34 |
| 已入库（可问答）附件 | 6 份（id 1321~1326，全挂在阶段 526「立项申报」；其中 1324/1325/1326 是复测时新解析的） |

> ⚠️ 上表值来自 **2026-09-23 复测**（页面接口与 SQL 双向核对一致）。库里的演示数据会被重新灌，
> 所以这些数字**不是常量**：改完数据先 `--write-truth` 刷新快照，再按漂移提示更新 `must_contain`。

### 2.2 一道题要写全四件事

```jsonc
{
  "id": "S-001-001",                       // <类别首字母>-NNN-NNN，S=structured D=document M=mixed N=negative P=permission
  "category": "structured",
  "question": "项目 55 的「立项申报」阶段一共有几个附件？",
  "scope": { "projectId": 55, "attachmentIds": [] },   // attachmentIds 非空 → 该题必须有 >=1 条 citations
  "expected": {
    "must_contain": ["9"],                 // 数字用字符串；比较时忽略千分位/空格/单位
    "must_contain_any": [["2100000"], ["210", "万"]],   // 可选：多组等价表述，任一组全命中即可
    "truth": { "kind": "phase_attachment_count",        // 结构化题**必填**；kind 见 §1.5 的表
               "projectId": 55,
               "phaseName": "立项申报",                   // 阶段类真值可指定阶段，不写则取第一个
               "via": "page-apis" },                   // 真值通道；给了 scope_token 会再加交叉校验
    "caliber_required": true,              // 计入"口径转述率"
    "must_call_tool": "query_business_data",
    "citation_pages": null                 // 没人工标页码就写 null，绝不瞎标
  },
  "note": "真值来源：GET /api/attachments?bizType=PROJECT_PHASE&bizId=526 条数=9（口径：仅阶段附件，逻辑删除由 @TableLogic 排除）"
}
```

**`truth` 的三个可选字段**（本次造题实际用到的）：

| 字段 | 含义 |
| --- | --- |
| `phaseName` | 阶段类真值指定阶段；不写取第一个阶段 |
| `derived: true` + `sources: ["S-00x-00y"]` | 该题真值是**算出来的**（差额/比例），来源题写在 `sources` 里。标了它，数据集体检就不要求来源值出现在答案里（差额题本来就不该答出合同总额的另一种写法） |
| `alsoFrom` | 非派生但需要并列多个真值时的补充来源（当前数据集未用到，留给后续扩展） |

各类别的额外要求：

| 类别 | 额外字段 | 判定要点 |
| --- | --- | --- |
| `structured` | **必须有 `truth`** | 答案数字 == 页面接口真值 + 调用了 `query_business_data`；**有真值的题不做 `SKIP(no-truth)`，所以"数字一致率"分母永远非空** |
| `document` | 建议 `must_call_tool: "search_documents"` | 页码命中（标了才判）+ 指定了附件就必须有引用 |
| `mixed` | 同 structured | 系统数据与文档依据**分开陈述**（§11.5 第 3 条） |
| `negative` | `expect_refusal: true`，**不许有 `must_contain`** | 出现"没有/未找到/不存在"等如实拒答即通过 |
| `permission` | `expect_error_or_empty: true`，可选 `forbid_values: [7200000]` | HTTP 4xx/5xx 或无数据；且**不得泄漏** forbid_values |

### 2.3 造题的自我约束（本次实际遵守的）

- **能查到就造，查不到就少造**：没有权威值的量（文档正文事实）一律不写进 `must_contain`，
  只写成弱判定题；造题当天项目 55 没有付款行，就写成"应如实说没有付款记录"，
  **不编数字**——复测时库里有了 9 条付款，才改成两值强判定。
- **数不到的文档事实不硬判**：`expect_weak: true` 标出 9 道弱判定题——只校验"确实检索了文档、给了引用"，
  不校验具体文本，因为本机读不到 PDF 正文。
- **负例要问"确定不存在"的东西**：服务器型号、机房门牌号、项目经理手机号/身份证号（库内均无此字段）。
- **越权题用真实不存在的 id**：`project` 表 id 实际范围仅 45~55，故用 `999999`；
  另保留一题 `projectId=45`（库中存在但阶段全 `deleted=1`），用来在真实无权 token 下验"403 且不泄漏 7200000"。
- **数字不手写，由真值驱动**：新增/修改题后跑 `--write-truth`，照 `truth-snapshot.json` 回填 `must_contain`，
  避免"人工抄错一位数"变成永久假失败。

---

## 3. 怎么跑

```powershell
# ① 数据集体检（**不发任何请求**，没有服务也能跑 —— 当提交前的质量门用）
node scripts/eval-ai.mjs --set eval/smoke-30.json --dry
# 退出码：0 = 体检通过；1 = 数据集不合格（缺必填/类别非法/id 重复/结构化题缺 truth）

# ② 真值核对（**不需要 AI 服务**，只连主系统页面接口）——同时是"真值 == 页面所见"的自证
$env:PM_TOKEN = "<粘贴 JWT>"
node scripts/eval-ai.mjs --set eval/smoke-30.json --base http://127.0.0.1:8080 --list-truth
node scripts/eval-ai.mjs --set eval/smoke-30.json --base http://127.0.0.1:8080 --write-truth  # 另写快照

# ③ 真实问答（需要主系统在跑 + 一个登录 JWT + AI 服务可用）
node scripts/eval-ai.mjs --set eval/smoke-30.json --base http://127.0.0.1:8080
```

**token 从哪来**（二选一）：

1. 浏览器登录主系统 → DevTools → Application → Local Storage 取 `token`；
2. `POST {base}/api/auth/login`，body `{"account":"...","password":"..."}`，取响应 `data.token`。

> 本机复现 `--list-truth` 时的实际做法（**开发环境**）：后端用 `mvn -B -DskipTests package` 构建后
> 以 `--server.port=8095` 起在本地，JWT 用 `.env` 里的本地 `JWT_SECRET` 按 `JwtUtil.createToken`
> 的 claims（`sub`/`account`/`name`/`role`，HS256）现签一个 ADMIN 令牌——**令牌只在进程内传递，不落盘**。
> 生产/共享环境请走正常登录拿 token。

⚠️ **token / scope_token 不要写进任何文件**（也不要进数据集）：它们是凭据，
缺了 runner 会明确报错退出（码 2）；`scope_token` 拿不到就只做页面 API 真值，**绝不伪造**。

其他开关：`--timeout <ms>`（默认 120000）、`--max <n>`（只跑前 n 题，联调省时）、`--out <path>`（报告路径）、
`--scope-token <jwt>`（启用 §11 交叉校验）。

---

## 4. 指标怎么读

runner 控制台汇总与 `report-*.json` 的 `metrics` 段一一对应：

| 指标 | 定义 | 怎么读 |
| --- | --- | --- |
| **数字一致率** | **有真值的题**里，答案命中真值值的个数 / 真值值总个数 | 契约 §11.7 第 1、2 条的量化，**主指标**。真值由页面接口现场算出（§1.5）。忽略千分位/空格/全角逗号/单位后缀；个位数字做**边界匹配**（防 `34` 里的 `3`、`2026` 里的 `2` 误判）；真值为 `0` 时还要求答案带如实措辞，防日期里的 `0` 蒙混 |
| **两条路径口径一致率** | 页面 API 真值 == §11 受控查询真值 的题数 / 有交叉校验的题数 | 给 `--scope-token` 才有；**交叉校验，不判失败**（不一致属主系统自身口径分歧，应由人裁决） |
| **工具选择正确率** | `toolTrace` 出现 `must_call_tool` 的题 / 带该字段的题 | 事实题必须走 `query_business_data`（§11.5 第 1 条） |
| **拒答正确率** | negative 题出现如实拒答措辞的比例 | 出现"没有/未找到/不存在/未登记/无法确认"等即算；**编造具体值即 FAIL** |
| **引用命中率** | 已标注页码的题：命中页数 / 应命中页数 | 分母为 0 时显示 `n/a` —— **补齐页码前这项没有意义**，这是设计，不是缺陷 |
| **引用覆盖率** | 指定了 `attachmentIds` 的题给出 ≥1 条引用的比例 | 补充指标：页码可以待补，但"一条引用都没有"是不可核验，直接 FAIL |
| **口径转述率** | `caliber_required` 题出现口径/数据时间表述的比例 | **只记账不判失败**（口径写法千变万化，硬判会把"答对但话少"全打成 FAIL）；比率过低说明 §11.4 的 `caliber/data_time` 没被模型转述出来 |
| **平均耗时** | 逐题串行 `POST /api/ai/chat` 的平均毫秒 | 含网络与模型时间，不是纯检索耗时 |

状态语义：

- `PASS`：全部判定通过；
- `FAIL`：**退出码 1**，逐题打印失败原因（数字与真值不一致/没调工具/没引用/未如实拒答/越权泄漏）；
- `SKIP(no-truth)`：该题没有任何可用真值来源（页面接口不可达、字段缺失、或 `truth.kind` 未实现）。
  **其余判定（must_contain/工具/引用/拒答）都已执行并通过**，只是无法与真值逐位比对；
- `SKIP(unlabeled)`：`citation_pages=null`（待人工补页码），引用命中率不适用；
- `SKIP(no-endpoint)`：接口不可达（保留状态位，用于将来引入其他外部真值通道）。

**退出码**：`0` = 无失败（含全跳过）；`1` = 有失败；`2` = 用法/配置错误（缺 token、连不上服务、数据集读不了）。

---

## 5. 回归怎么用

1. 改完 AI/查询相关代码 → `--dry` 过一遍（数据集没被改坏）；
2. 数据有变动（演示库重灌）→ `--write-truth` 刷新 `eval/truth-snapshot.json`，
   按打印出的"漂移"提示更新 `must_contain`（否则会拿旧数字判新库，出现假失败）；
3. 起服务 → 带上一份报告对比：`report-*.json` 里每题都留了 `note`（真值来源）、`truth`（现场真值
   与逐值命中情况）、`expected`、`answer`、`citations`、`toolTrace`、`httpStatus`、`elapsedMs`，
   另有 `truthDrift`（快照 vs 现场漂移）与 `truthCalls`（真值解析实际打了哪些接口）；
4. 关键关注项：
   - **数字一致率**掉 → 明细里看是哪道题的哪个值变了（模型答错 / 接口口径变了 / 数据被重灌）；
   - `SKIP(no-truth)` 数量不为 0 → 真值通道出问题（接口挂了或字段改了），优先排查；
   - `truthDrift` 非空 → 库数据变了，数据集该同步；
   - **拒答正确率**掉 → 模型开始编造不存在的事实，优先级最高；
   - 越权题出现 `forbid_values` 命中 → 立刻按数据泄漏处理。

> 报告文件默认落在 `eval/report-<时间戳>.json`，由本目录的 `eval/.gitignore`（`report-*.json`）排除，
> 不会污染工作区 diff（仓库根 `.gitignore` 不在本次改动范围内，故就近声明）。
> 需要长期留档的基线报告，请另行复制到仓库外或显式 `git add -f`。
