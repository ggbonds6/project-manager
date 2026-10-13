# PM AI Backend（AI 能力服务 · Java 版）

附件解析 / 平台 OCR / 大模型抽取问答 / 向量检索（Embedding 召回 + Reranker 精排）的独立服务。
与主系统**同栈**（Spring Boot 3.3.5 + Java 17），但**独立构建与部署**：平台 OCR、大模型、向量化都在 GPU 机上，
AI 能力无论如何都要单独发版。

> 本服务是 `ai-service/`（Python/FastAPI 版）的 Java 重写。迁移对照、必须保留的踩坑结论、
> 接口契约与验证协议见 **[`docs/迁移方案与对照表.md`](docs/迁移方案与对照表.md)**。

## 快速开始

```bash
# 构建（本机 JDK 17：E:\env\jdk\jdk-17，Maven 3.9.9）
mvn -B -DskipTests package

# 运行（默认 8100；本地调试避开 Python 版占用的端口）
E:\env\jdk\jdk-17\bin\java.exe -jar target/pm-ai-backend-1.0.0-SNAPSHOT.jar --server.port=8101

# 自检：网关连通 + 模型列表 + OCR 探活 + 向量探活
curl "http://127.0.0.1:8101/health?with_ocr=true&with_vec=true"
```

配置全部来自环境变量（键名与 Python 版一致，见 `src/main/resources/application.yml`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_PORT` | 8100 | 服务端口 |
| `AI_IMAGE_TAG` | `latest` | 服务器运行目录 `ai/.env` 用的镜像 tag（须是 `docker load` 进来的 `pm-ai-backend:<tag>`）；**出发布包时自动预填本次版本**，日常升级由 `bash pm.sh upgrade ai` 自动切换 |
| `AI_BIND_IP` | `0.0.0.0` | 容器端口绑定到哪张宿主网卡；**本服务当前无入站鉴权**，服务器上建议填本机内网 IP + 防火墙只放行主系统服务器 |
| `LLM_BASE_URL` | `http://10.254.208.35:8090/v1` | 平台网关（对话 / OCR / 向量化 / 重排**共用**） |
| `LLM_API_KEY` | — | 用户 sk（同一把） |
| `LLM_TIMEOUT` | 300 | **只是兜底值**：四类模型各有自己的超时（见下面 `*_TIMEOUT`），只有未分级的调用才用它 |
| `LLM_MODEL` | `Qwen3.8-27B-W8A8` | 对话模型 |
| `OCR_MODEL` | `PaddleOCR-VL-1.6-0.9B` | 平台 OCR 模型 |
| `OCR_CONCURRENCY` | 12 | **全进程**的 OCR 在飞请求上限（共享线程池）；语义已修正，不再是"每份文档 min(12, ceil(页数/8))" |
| `OCR_BATCH_PAGES` | 8 | 单请求页数**上限**（平台 ≤16）；实际批页 = `min(它, ceil(页数/在飞上限))`，页数少的文档自动切小批 |
| `VEC_EMBED_MODEL` / `VEC_RERANK_MODEL` | `Qwen3-VL-Embedding-8B` / `Qwen3-VL-Reranker-8B` | 向量化与重排 |
| `VEC_EMBED_DIMENSIONS` | 0 | **0 = 不传**（平台默认 4096；本部署不支持 MRL 降维，传了会 400） |
| `RETRIEVAL_RECALL` | 50 | 召回条数 = 送进 Reranker 的候选数。每次检索 ≈ `1.5 × recall` 对，一次问答 ≈ 2 次检索；平台重排实测 24 对/s ⇒ 50 时 ≈6.3s/问、问答上限 ≈0.16 QPS。**降配会动答案质量，默认保持 50** |
| `RETRIEVAL_RECALL_MODE` / `RETRIEVAL_RECALL_LOW` | `baseline` / 25 | 降配开关：`baseline`（用 `RETRIEVAL_RECALL`）\| `low`（用 `RETRIEVAL_RECALL_LOW`）\| `custom`。**把默认改成 low/25 之前必须用 `eval/smoke-30.json` 跑前后对比，引用命中率与数字一致率不降才可改** |
| `RETRIEVAL_TOP_K` | 5 | 精排后返回条数（工具侧硬上限 10） |
| `VEC_BACKEND` | local | `local`（进程内余弦 + JSON 缓存，零部署、可随时回滚）\| `opensearch`（正式选型，连真集群） |
| `OPENSEARCH_URL` | `http://10.254.212.110:9200` | **必须指向实际作为生产的那一台**。两台机器是**彼此独立的单节点集群**（各自 `discovery.type: single-node`，数据集不共享），**不要同时接业务**：`.110` = 生产，`.111` = 冷备/演练机（收快照与归档） |
| `OPENSEARCH_INDEX` | `pm-ai-chunks` | 索引名（启动/首次写入时按 3.x 写法自建：4096 维 `knn_vector` / `cosinesimil` / `lucene` / HNSW `m16, ef_construction100` / `number_of_shards:1` / `number_of_replicas:0`） |
| `OPENSEARCH_USER` / `OPENSEARCH_PASSWORD` | `admin` / — | basic auth。**口令只从环境变量/.env 读**，不进仓库、不进日志/异常/自检（只报"配没配"） |
| `OPENSEARCH_TIMEOUT` | 20 | 单次 REST 请求超时（秒） |
| `OPENSEARCH_BULK_SIZE` | 500 | `_bulk` 每批文档数（`_id` = `chunk_key`，幂等） |
| `OPENSEARCH_HYBRID` | false | hybrid（BM25 + kNN 两路融合）开关。默认关：两路分数量纲不同（相加前须各自归一）、本地已有一路关键词召回、且"开与不开"没有评测基线 |
| `LLM_CHAT_TIMEOUT` / `VEC_EMBED_TIMEOUT` / `VEC_RERANK_TIMEOUT` | 150 / 30 / 20 | **每模型超时**（秒）；`OCR_TIMEOUT` 为 600。硬约束：**都 < `AI_CALLER_TIMEOUT`（调用方 180s）**，启动日志逐条核对 |
| `AI_CALLER_TIMEOUT` | 180 | 调用方（主系统→AI）预算，仅用于启动时的超时链校验 |
| `LLM_CHAT_CONCURRENCY` / `VEC_EMBED_CONCURRENCY` / `VEC_RERANK_CONCURRENCY` | 4 / 8 / 2 | 每模型在飞上限（信号量）；OCR 用 `OCR_CONCURRENCY=12`。取值依据：平台 4 个对话副本、手册"批 32 + 并发 8 = 147.9 条/s"、重排单实例 |
| `MODEL_ACQUIRE_TIMEOUT_MS` / `MODEL_BATCH_ACQUIRE_TIMEOUT_MS` | 1500 / 60000 | 等 permit 的时长：交互式短（宁可 503"模型繁忙"也不排队到用户超时）、批量长（没人等） |
| `MODEL_RETRY_MAX` / `MODEL_RETRY_BACKOFF_MS` / `MODEL_RETRY_BACKOFF_MULTIPLIER` / `MODEL_RETRY_JITTER_MS` | 2 / 1000 / 4 / 250 | **只对幂等调用**重试（OCR / embedding / 检索）：连接错误、超时、5xx、429；退避 1s→4s + 抖动。**`chat` 永不重试** |
| `WORK_DIR` | ./work | 临时文件、文档库、向量缓存、`doc-meta.json` |
| `BIZ_QUERY_TIMEOUT` | 20 | P2 受控查询回调主系统 `/api/ai/query/{entity}` 的超时（秒）。**回调地址与 `scope_token` 不在这里配**：由主系统在每次 `/chat` 的 `biz_query` 里现给（§11.1 反向回调），不传就不注册 `query_business_data` 工具 |

### L1 业务并发闸门 `ai.gate.*`（设计 §5；**改这些数字前先读依据**）

> 与上面 `ai.limits.*`（L2 每模型信号量）的分工是**两层、单向**：**先 L1 后 L2，且 L1 的 permit 全程持有**。
> 三条防死锁硬规则：**H1** 顺序统一（先 L1 后 L2）、**H2** 排队不嵌套（L1 在任务开始前、L2 在单次调用前）、
> **H3** 优先级只在 L1（L2 永远是公平信号量）。顺序写反时 `ModelRegistry` 会**立刻抛错**而不是静默死锁
> （`ai.model.h1.violations` 可观测，正常恒为 0）。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_GATE_ENABLED` | true | 是否启用 L1。`false` = 直接放行（只用于灰度对照/排障），不建队列不排队 |
| `AI_GATE_INTERACTIVE_MAX` | 4 | 交互式在飞上限。取 `LLM_CHAT_CONCURRENCY`（平台 4 个对话副本）；**给更大只会让多出来的请求在 L2 排队、指标误导** |
| `AI_GATE_INTERACTIVE_QUEUE` | **0** | 交互式队列上限。刻意是 0：`/chat` 的纪律是"要么 1.5s 内开始、要么 503"，一个"能进但等不到"的队列只会制造假象 |
| `AI_GATE_INTERACTIVE_WAIT_MS` | 1500 | 交互式等待上限（沿用 `MODEL_ACQUIRE_TIMEOUT_MS`）⇒ **503 快速失败**，绝不排队到前端 180s |
| `AI_GATE_BATCH_PARSE_MAX` | 4 | 批量解析在飞上限（= 既有 `uploadTaskExecutor` 的 maxPoolSize 4，接入 L1 不改变既有行为） |
| `AI_GATE_BATCH_PARSE_QUEUE` | 100 | 批量解析队列上限（= 既有线程池队列容量 100） |
| `AI_GATE_BATCH_PARSE_PER_PROJECT_MAX` | 4 | 每项目在飞上限（设计 §5.3 建议 `ceil(max/2)`~4） |
| `AI_GATE_BATCH_REINDEX_MAX` | 1 | 重建索引串行（现状 `ReindexService` 单 job，这里把该语义显式化） |
| `AI_GATE_BATCH_EMBED_MAX` | 4 | 批量向量化通道上限。**不是 L2 的 8**：向量化是"批内并行"，每个文档在 L2 内部已把 embed 打满，L1 再放 8 个进来只会在 L2 排队 |
| `AI_GATE_BATCH_EVAL_MAX` | 1 | 评测跑批（最低优先级，无人在等） |
| `AI_GATE_PAUSE_BATCH_WHEN_INTERACTIVE` | true | **软闸门**：交互式队列非空时暂停派发新的 `BATCH.PARSE`（**只影响新任务，绝不踢掉在跑的批量**；这是设计里唯一需要"抢占"语义的地方） |
| `AI_GATE_PER_PROJECT_MAX` | 4 | 未单独配置时的每项目在飞上限兜底 |
| `AI_GATE_AGING_STEP_SECONDS` / `AI_GATE_AGING_BONUS_PER_STEP` / `AI_GATE_AGING_BONUS_MAX` | 30 / 2 / 40 | 老化：每等满 30s 加 2 分、上限 40（**防饥饿**；`effective_priority = 任务优先级 + 通道权重 + 老化`） |

**通道类型权重**（`effective_priority` 的第二项，设计 §5.2）：
`INTERACTIVE=100` / `BATCH.PARSE=20` / `BATCH.REINDEX=10` / `BATCH.EMBED=5` / `BATCH.EVAL=0`。
**诚实失败**：拒绝永远带 `reason`（`full` 队列满 / `timeout` 等超时 / `paused` 被软闸门压住）+ 当前深度 + 上限 + 建议，
**绝不出现"接受了但永远不跑"**（0 条永久 QUEUED、0 个 `work/tmp` 残留）。

启动日志会打印 L1 通道一览并做 **L1↔L2 交叉校验**（`ΣL1 上限 ≤ ΣL2 上限`，设计 §5.10.2 第 6 条），超了只 WARN 不退出。

### 阶段上报（主系统流水线）`ai.pipeline.*`（设计 §4）

> AI 服务不直连库：阶段/产物/事件一律**回调主系统** `POST /api/ai/pipeline/**`，
> 鉴权头 `X-AI-Service-Token`（= `AI_SERVICE_TOKEN`，**只从环境变量/.env 读，绝不进仓库/日志/异常/指标/自检**）。
> JSON 用 **snake_case**（兼容 camelCase），响应体 `{code,message,data}`，真实 HTTP 状态码。
> **上报失败不影响主流程**（best-effort：只 WARN + 计数），**只有终态会重试**。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_SERVICE_TOKEN` | — | 上报令牌（`X-AI-Service-Token`）。**不配 = 不上报**（解析照常，只是任务流视图没有阶段行）。与 `AI_PIPELINE_BASE_URL` 一起才是"启用" |
| `AI_PIPELINE_ENABLED` | false | 上报总开关（默认 false：没配地址/令牌时不做任何外呼，行为与接入前一致） |
| `AI_PIPELINE_BASE_URL` | — | **主系统**基地址（如 `http://10.254.208.36:8080`）。注意这是"AI 回调主系统"，与 `LLM_BASE_URL`（平台网关）不是一回事 |
| `AI_PIPELINE_TIMEOUT` | 3 | 单次上报超时（秒）。取小值：上报是 best-effort，**不能拖慢主流程** |
| `AI_PIPELINE_FINISH_RETRIES` | 2 | **终态**上报的额外重试次数（共 3 次尝试）。终态丢失会让任务永远停在 `RUNNING`（终态以主系统为准） |
| `AI_PIPELINE_HEARTBEAT_SECONDS` / `AI_PIPELINE_LEASE_SECONDS` | 30 / 90 | 领取租约与心跳间隔（契约里的 `lease_seconds`；**409 表示租约被抢 ⇒ 立即停止该任务并放回**） |
| `AI_PIPELINE_MDC` | true | 是否把 `run_id` 写进日志 MDC（排障时按一次任务过滤日志） |
| `AI_PIPELINE_CHUNK_KEY_FORMAT` | `legacy` | 切片键形态。`legacy`=三段（**与接入前逐字节一致，升级不使既有索引/缓存失效**）；`rev`=`d=<docId>:p=<pageNo>:h=<sha1>:c=<cleanRev>:k=<chunkRev>`（清洗/切片规则变更即换键，需跑一次 `POST /admin/reindex` 统一）。解析器两种都认 |
| `AI_PIPELINE_PARSE_REV` / `AI_PIPELINE_CLEAN_REV` / `AI_PIPELINE_CHUNK_REV` | 1 / **0** / 0 | 流水线版本段。`clean_rev=0` = **清洗关闭**（默认口径；`CLEAN` 阶段如实上报 `SKIPPED`，原因写在 `error_msg`，切片输入 = 解析原文）。规则清单/开关/阈值任一变化即 +1 ⇒ 切片键（`rev` 形态）与向量缓存（`work/vectors/*.json` 头部记 `clean_rev/chunk_rev`）整体失效；`GET /admin/reindex/plan` 会算出"哪些文档需要重算" |

`pipeline_rev = parse=<n>,clean=<n>,chunk=<n>,embed=<sha1(embed_model+dimensions)[0:8]>`；
任务级幂等键 `task_key = sha1(内容 sha1 + pipeline_rev)`。
**五阶段边界**：`PARSE` → `CLEAN`（`DONE`/`PARTIAL`，或关闭时 `SKIPPED` + 原因）→ `CHUNK` → `EMBED` → `INDEX`，随后 `/finish` 报终态
（`DONE`/`PARTIAL`（有页级失败时）/`FAILED`/`CANCELLED`）；`RETRIEVE`/`GENERATE` 是在线阶段，不落阶段行。

### 清洗阶段（**新增**，设计 §3.3 / G1+T7）`ai.clean.*`

> 纯本地、**确定性**：同输入 + 同 `clean_rev` ⇒ 同输出（可缓存、可复算、可做前后对照）。
> 规则**只作用于文本层与平台 OCR markdown 两路**解析结果（未知来源的页原样传下去）。
> **默认关闭**：`ai.clean.enabled=false` 时 `CLEAN` 阶段上报 `SKIPPED` + 原因，切片输入 = 解析原文，
> **与接入前逐字一致**（`clean_rev=0`；回归见 `CleanRevRegressionTest`）。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_CLEAN_ENABLED` | **false** | 清洗总开关。打开时请同时把 `AI_PIPELINE_CLEAN_REV` 设为 ≥1，并跑一次 `POST /admin/reindex?force=true` |
| `AI_CLEAN_RULES` | `c1,c2,c3,c4,c5,c6,c7` | 规则清单（逗号分隔）。留空 = 用每条规则自己的默认开关（`c1`~`c7` 开、`c8` 关）。⚠️ **关掉 `c5` 等于同时关掉"表格不被 `c3/c4/c8` 动"的保护** |
| `AI_CLEAN_HEADER_FOOTER_RATIO` | 0.5 | `c1` 跨页阈值：同一归一化行在 ≥ 该比例的页出现、且位于页首/页末 2 行内 ⇒ 判为页眉/页脚 |
| `AI_CLEAN_HEADER_FOOTER_MIN_CHARS` | 4 | `c1` 最小行长：短于此的行不参与页眉/页脚判定 |
| `AI_CLEAN_DEDUP_DROP` | false | `c6` 段级去重：false = **只标记不删**（`duplicate_of` 进产物供复核）；true 才真删重复段 |
| `AI_CLEAN_WATERMARK_PATTERNS` | 空 | `c8` 水印正则白名单（逗号分隔）。为空 = 该规则不生效（**默认关**：水印文字常是密级线索，合规风险高于收益） |

**规则清单（`c1`~`c8`，与设计 §3.3.2 逐条对应）**：

| id | 规则 | 默认 | 判据（可核对的算法） |
| --- | --- | --- | --- |
| `c1` | 页眉/页脚（跨页重复行） | 开 | 归一化（折空白+去数字）后同一行在 ≥50% 的页出现，且位于页首/页末 2 行内 ⇒ 整行剔除；命中行全部进 `removed_lines` |
| `c2` | 纯页码行 | 开 | `^[-—–\s]*\d{1,4}[-—–\s]*$`、`^第\s*\d+\s*页(共\s*\d+\s*页)?$`、`^\d+\s*/\s*\d+$`、`^Page\s+\d+(\s+of\s+\d+)?$`；**先把"共 Y 页"抽成 `total_pages` 再删行** |
| `c3` | 空白与不可见字符归一 | 开 | 换行统一、零宽字符删除、全角空格→半角、行首尾 strip、≥3 空格压缩、连续空行压到 1 行；**表格块内不压缩连续空格** |
| `c4` | 断行合并 / 连字符修复 | 开 | 上一行行尾无句末标点且当前行不是条款/列表/标题起始 ⇒ 合并；`-`/`‐` 结尾 + 下一行小写字母 ⇒ 去连字符合并；**表格块内不合并** |
| `c5` | 表格与正文分离 | 开 | markdown 竖线结构（连续 ≥2 竖线行，含分隔行或 ≥3 行）⇒ **只标记** `table_ranges`，交切分整块处理，不改写文本 |
| `c6` | 段级去重 | 开（只标记） | 归一化后完全相同且 ≥20 字的段落，同文档出现 ≥2 次 ⇒ 保留首次、后续记 `duplicate_of`；`AI_CLEAN_DEDUP_DROP=true` 才删 |
| `c7` | 编码归一 | 开 | 全角 ASCII 区 → 半角，**但保留句读类标点 `，；：？！（）`**（设计 §3.3.2 C7 ②）；统计替代字符 `\uFFFD`（不静默删），归一只记进 `normalized[]` |
| `c8` | 水印剔除 | **关** | 只删命中 `AI_CLEAN_WATERMARK_PATTERNS` 的整行；词表为空即不生效（"非页首/页末的高频重复行"**未做**，见下方"未做"） |

**产物与阶段上报**：`clean_diff` 摘要（≤4000 字符，含 `removed_chars_ratio`/`removed_lines`/`rules_fired`/被删行样本）
进主系统 DB；**全文前后对照**留 `work/clean/<docId>.json`（可重建物，`object_key` 指路）。
`CLEAN` 阶段的 `counters` 含 `clean_rev`/`rules_enabled`/`rules_hit`/`pages*`/`before_chars`/`after_chars`/
`removed_chars(_ratio)`/`removed_lines`/`pages_all_removed`/`duplicate_paragraphs`/`table_blocks`/`table_chars`/`replacement_chars`/`total_pages`。

**未做（如实登记）**：`c5` 的平台 `blocks[label=table]` **bbox 匹配未做**（`BlockRef` 只有 label+bbox、没有行号/行文本，
无法映射到行 ⇒ 只认竖线结构，无竖线的表格按正文处理）；`c8` 的"非页首/页末高频重复行"未做（只做词表）；
`c1` 的白名单正则未提供（命中行一律留痕供人工复核）。

### 切分（**新增**，设计 §3.4 / G11+G12）`ai.chunk.*`

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_CHUNK_IMPROVED` | **true** | 改进切分（结构感知 + 表格整块 + 重叠 + 父子块）。false = **回退到接入前的切法**（段落累积到 `chunk-chars` 就断、无重叠、无结构感知、**无长度上限**），仅用于口味回归对照 |
| `AI_CHUNK_CHARS` | 500 | 单切片的**目标**字数（打包目标，不是硬上限），与接入前一致 |
| `AI_CHUNK_MAX_CHAR_LEN` | 2000 | 单切片的**硬上限**。验收口径：**不存在 > 该值的切片**；超长段落按句末标点二次切 |
| `AI_CHUNK_OVERLAP_RATIO` | 0.12 | 相邻切片重叠比例（上限 0.15）。默认 ≈ 500×12% = 60 字；**按句回退**、**只在本页内**；表格片与结构边界处不加重叠前缀 |
| `AI_CHUNK_STRUCTURE_AWARE` | true | 按标题/条款边界优先切（标题总是边界，不在标题与其后续正文之间切）；关闭即退化为"按段落打包" |
| `AI_CHUNK_TABLES_WHOLE` | true | 表格整块成片；超硬上限时按行切、**每片重复表头行 + 分隔行**并前置 `【表格：…】` |
| `AI_CHUNK_PARENT_SUMMARY_CHARS` | 200 | 父块摘要字数上限（父子块 small-to-big 的"上卷"）；0 = 关闭该能力 |

**切片元数据（设计 §3.4.5）**：`order_in_page`/`parent_key`/`is_table`/`table_rows`/`section_path`/`char_len`/`source`
+ `clean_rev`/`chunk_rev` 都写进 OpenSearch 文档与 mapping（`page_no`/`doc_id`/`filename` 原有）。
**父子块本轮形态**：父块 = **一页**（`parent_key = <docId>:p=<pageNo>`），子块检索命中后由检索层现算父块摘要
（同页子块拼接前 N 字）一并给模型；`work/chunks/<docId>.json` 里有 `parents[]`（父键 + 子键 + 摘要）。
**升级点**：`section_path` 已经逐片算好，升级到"章节树父块"只需把 `parent_key` 从"页"换成"章节"，子块/重叠/上限不动。

**`GET /admin/reindex/plan`（新增，只读）**：按向量缓存头部算出 `stale_docs`/`fresh_docs`/`unknown_docs`
与 `needs_rebuild`，回答"`clean_rev` 改了要不要重建、影响多少份"（`index_checked=false`：不连集群）。
`POST /admin/reindex` 的任务行新增 `pipeline_rev`/`clean_rev`/`chunk_rev`/`clean_enabled`/`chunk_improved` 快照。

## 四条链路（与主系统**同一套形状**）

> 主系统的对应链路见 [`../deploy/README.md`](../deploy/README.md) 与 [`../docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md)；
> 本服务**独立构建、独立发版**：主系统发布包不含它，它的发布包也不含主系统（这是刻意的，见文首）。

| 阶段 | 主系统 | AI 能力服务（本服务） |
| --- | --- | --- |
| ① 本地开发（源码直跑） | `deploy\windows\start-dev.cmd`（`mvn spring-boot:run`）+ Vite | `cd ai-backend && mvn spring-boot:run`（默认 8100；配置见上表） |
| ② 本地开发（容器，一键重建） | `.\scripts\dev-reload.ps1 [all\|backend\|frontend]` | `.\scripts\dev-reload.ps1 -Project ai` |
| ③ 构建 + 打包（出发布包） | `.\scripts\make-release.ps1 <版本>` → `dist/pm-release-<版本>/`（`pm.sh` + `main/`） | `.\scripts\make-release.ps1 <版本> -Project ai` → `dist/pm-ai-release-<版本>/`（同一份 `pm.sh` + `ai/`） |
| ④ 服务器部署（离线只 load） | `scp -r dist/pm-release-<版本>/* lhim@<服务器>:/home/lhim/pm/` → 配 `main/.env` → `bash pm.sh upgrade main main/releases/<镜像包>` | `scp -r dist/pm-ai-release-<版本>/* lhim@<AI服务器>:/home/lhim/pm/` → 配 `ai/.env` → `bash pm.sh upgrade ai ai/releases/<镜像包>`；自检 `/health?with_ocr=true&with_vec=true` |

### ③ 出 AI 发布包（开发机，仓库根；需 Docker Desktop 已启动）

```powershell
.\scripts\make-release.ps1 v1.0.0 -Project ai                # 默认 linux/arm64（主系统那两台服务器）
.\scripts\make-release.ps1 v1.0.0 -Project ai linux/amd64    # x86 机器（例如与平台网关同机）
```

产出 `dist/pm-ai-release-v1.0.0/`：包根 `pm.sh`（服务器唯一入口，两套发布包里是同一个文件）+ `ai/` 目录：
`ai/docker-compose.yml`（= `ai-backend/docker-compose.deploy.yml`）、`ai/.env.example`（`AI_IMAGE_TAG` 已预填本次版本）、
`ai/服务器部署步骤-ai.txt`（逐步照做即可）、`ai/releases/pm-ai-images-<arch>-v1.0.0.tar.gz`（镜像包）。

### ④ 服务器部署（AI 运行目录 `ai/` + 已 load 的镜像；与主系统 `main/` 互不影响）

```bash
# ① 开发机（仓库根、dist 的上一层）：把整棵发布包铺到部署根
#    scp -r dist/pm-ai-release-v1.0.0/*  lhim@<AI服务器>:/home/lhim/pm/

# ② 服务器：配 .env → 一条命令 load + 切版本 + 启动 + 健康检查
cd /home/lhim/pm/ai && cp .env.example .env && vi .env
#   必填：LLM_API_KEY（平台网关那把 sk）、AI_BIND_IP=<本机内网IP>
#   AI_IMAGE_TAG 出包时已预填 v1.0.0，升级时由 pm.sh 自动切换，不用手改
cd /home/lhim/pm
bash pm.sh upgrade ai ai/releases/pm-ai-images-aarch64-v1.0.0.tar.gz
bash pm.sh status                                                  # 期望：AI 能力服务(ai)：可用
curl "http://127.0.0.1:8100/health?with_ocr=true&with_vec=true"     # 期望 code:0 且各模型 ok
```

> 日常启停同样只走统一入口：`bash pm.sh start ai` / `bash pm.sh stop ai` / `bash pm.sh logs ai`。
> 手工等价的 `docker compose` 命令要在 `/home/lhim/pm/ai` 下执行（compose v2 只读 compose 同目录的 `.env`）；
> 手工 load 写法：`docker load -i /home/lhim/pm/ai/releases/pm-ai-images-aarch64-v1.0.0.tar.gz`。

> **接回主系统**：在主系统那台机器的 `main/.env` 里把 `AI_SERVICE_BASE_URL` 指到本机内网 IP
> （**与本服务同宿主机**才用 `http://host.docker.internal:8100`），`cd /home/lhim/pm && bash pm.sh restart main` 即生效——**不需要重建镜像**；
> 打开「AI 与知识库 → 服务自检」确认全绿后，再按需把 `AI_AUTO_PARSE=true` 打开。完整说明见部署手册 §8.3。

> **运维边界（发版前必须知道）**：本服务**当前没有任何入站鉴权**，端口不要对全网开放——
> 上线时用 `AI_BIND_IP` 绑内网 IP，并用防火墙只放行主系统服务器；`WORK_DIR` 必须挂卷（丢了要重新解析，不影响正确性）；
> 双机负载均衡场景**建议本服务集中部署一台**（否则两个索引各自演化，同一问题答案可能不一致）。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 自检（`with_llm` / `with_ocr` / `with_vec` / `with_metrics`）。`with_vec=true` 时 `vec.ok` 取**后端真实状态**：`VEC_BACKEND=opensearch` 时探集群（`/_cluster/health`）并同时给出 `upstream_ok`（平台网关的向量模型），集群不可达则 `ok=false` 且 `detail` 以"向量后端不可用"开头——**不会**用"网关可达"糊过去 |
| POST | `/analyze` | 附件 → 带来源页码的结构化 markdown（走大模型） |
| POST | `/ocr/pdf-info` | 判断文本型/扫描件与规模 |
| POST | `/ocr/file` | 只识别不调模型（摸底用） |
| POST/GET/DELETE | `/documents[/{id}]` | 文档库（解析入库、列表、详情、删除） |
| PUT/GET | `/documents/{id}/meta` | 文档**过滤元数据**补录/读取（`attachment_id`/`project_id`/`phase_code`/`security_level`/`doc_type`/`source`）。它是"先过滤再检索"（`doc_ids`/`attachmentIds`/`projectId` 硬作用域）的前提；回填后调 `POST /admin/reindex?docIds=<id>` 即可写进索引（**不必重解析**，重解析要重跑 OCR） |
| POST | `/admin/reindex` | **全量重建索引（含重新向量化）**：`?docIds=a,b`（只重建这些）/ `?force=false`（默认，断点续跑）。返回 `job_id` |
| GET | `/admin/reindex[/{jobId}]` | 重建进度（`processed_docs`/`total_docs`/`failed_docs`/`skipped_docs`/`written_chunks`/**`eta_seconds`**） |
| POST | `/admin/reindex/{jobId}/cancel` | 中断重建（协作式，在文档边界生效；已写入的切片不回滚，下次续跑会跳过） |
| POST/GET/DELETE | `/upload-tasks[/{id}]` | 上传解析任务（先返回、后台解析、轮询进度；**解析阶段就完成向量化入库**）。队列满时返回 **429** 且任务标 `FAILED`（不再出现"永久 QUEUED"与 `work/tmp` 残留） |
| POST | `/chat` | 文档问答（工具调用：检索 → 读页 → 计算；带 `biz_query` 时**多一个** `query_business_data`）。请求 `{question, doc_ids, scope?, history, biz_query?}`（`docIds`/`bizQuery` 亦接受）；**`scope` 是检索的硬上界**：`{doc_ids?, attachment_ids?, project_ids?, security_levels?}`，与模型在工具里给的 `doc_id` **取交集**，越界直接返回 `scope_violation`（**不退回更大范围**）——不传时行为与本版本前完全一致。`biz_query = {url, scope_token, entities}` 是主系统给的**受控查询通道**（P2，契约见 [`../docs/AI前端与集成方案.md`](../docs/AI前端与集成方案.md) §11）——**不传时行为与本版本前完全一致**。响应 `data.answer` 正文用 `[1][2]` 标注出处，`data.citations` 给出结构化出处 `[{index,doc_id,filename,page_no,snippet,score}]`——**前端"点引用跳原文第 N 页"就靠它**，无引用时为 `[]`；系统数据查询照常进 `data.trace`（名称/参数摘要/耗时/命中数）。**L1 交互式准入**：1.5s 内拿不到许可就 **503 快速失败**（不排队到 180s） |

成功响应 `{"code":0,"data":{...}}`；失败为 HTTP 状态码 + `{"detail":"..."}`（与 Python 版一致）。
其中 **503 = 向量后端不可用 / 模型繁忙 / 交互式繁忙（L1 满）**（可重试），
**429 = 解析队列已满**（可重试），两者都带明确原因（L1 的拒绝还带深度/上限/原因/建议）。

## 指标（P0-6；依赖 `spring-boot-starter-actuator` + micrometer）

```bash
# 每模型（chat/ocr/embed/rerank）：QPS = ai.model.requests 的计数速率；错误率按 outcome 分
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.model.requests'
# 延迟分位（P50/P95/P99 在代码里 publishPercentiles 打开，不依赖配置）
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.model.latency'
# 在途数 / 队列深度（等 permit）/ 在飞上限
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.model.inflight'
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.model.queue.depth'
# 解析队列深度 / 在途；被拒绝的任务数
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.upload.queue.depth'
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.upload.rejected'
# 聚合视图（四模型 × 5 项 + 队列 + 重建状态，一眼看全）
curl -s 'http://127.0.0.1:8100/health?with_metrics=true'
```

另有：`ai.model.rejected`（等不到 permit）、`ai.model.retries`（幂等重试）、
`ai.retrieval.rerank.pairs`（重排对数，降配 `RETRIEVAL_RECALL` 的收益口径）、
`ai.vector.embedded` / `ai.vector.indexed`（入库向量化/写索引切片数）、
`ai.vector.lazy_backfill`（查询侧补算切片数，**正常应为 0**）、
`ai.retrieval.scope.filtered`（被硬作用域过滤掉的切片数）、`ai.opensearch.retries`、
`ai.opensearch.requests`（HTTP 出口请求数：与"线程数不随请求数线性增长"配对，验收连接复用）。

### L1 业务闸门与阶段上报的指标（设计 §5.5 + §4）

```bash
# L1 队列深度 / 在途 / 上限（按 queue=interactive|batch.parse|batch.reindex|batch.embed|batch.eval 分标签）
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.depth'
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.inflight'
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.limit'
# 准入 / 拒绝（拒绝带 reason=full|timeout|paused）/ 等待时长分位 / 老化补偿
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.admitted'
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.rejected'
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.waiting_ms'
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.aging.bonus'
# 每项目公平性（V3 的判据）与 H1 顺序违规（正常恒 0）
curl -s 'http://127.0.0.1:8100/actuator/metrics/ai.gate.fairness.per_project'
# 聚合视图（含 gate + pipeline 两段）
curl -s 'http://127.0.0.1:8100/health?with_metrics=true'
```

`ai.gate.*` 共 10 项（与设计 §5.5 逐条对应）：`depth` / `inflight` / `limit` / `admitted` / `rejected` /
`waiting_ms` / `fairness.per_project` / `aging.bonus`，另加 `per_project.inflight.max` 与 `aging.bonus.awarded`；
上报侧：`ai.pipeline.report_failed`（best-effort 失败计数，**持续 >0 说明主系统接口或令牌有问题**）、
`ai.pipeline.report_sent`、`ai.pipeline.report_latency{call=...}`（claim/heartbeat/release/stage/artifact/event/finish）。

## 重建索引（可中断、可续跑）

```bash
# 全量重建（默认 skip_existing：文档整份已索引就跳过，其余按 chunk_key 只补缺失的切片）
curl -XPOST 'http://127.0.0.1:8100/admin/reindex'
# 只看进度：processed_docs / total_docs / failed_docs / skipped_docs / written_chunks / eta_seconds / percent
curl -s 'http://127.0.0.1:8100/admin/reindex/<job_id>'
# 中断（已写入的切片不回滚——它们是正确的；再跑一次会跳过，这就是"断点续跑"）
curl -XPOST 'http://127.0.0.1:8100/admin/reindex/<job_id>/cancel'
# 强制全部重写（换了 embedding 模型/维度时用）
curl -XPOST 'http://127.0.0.1:8100/admin/reindex?force=true'
```

向量化在重建里同样是**批 32 + 并发 8**（共享 embed 信号量），写入按 `OPENSEARCH_BULK_SIZE=500` 分批。
`VEC_BACKEND=opensearch` 且集群不可达时，重建**启动即报错**（`503` + "向量后端不可用"），
不会悄悄改成"用进程内重建"。

**改了 `clean_rev`/`chunk_rev` 之后先看计划，再决定要不要重建**（设计 §3.3.3 的 L3）：

```bash
curl -s 'http://127.0.0.1:8100/admin/reindex/plan'
# → {pipeline_rev, clean_rev, chunk_rev, clean_enabled, chunk_improved, chunk_key_format,
#    docs, stale_docs, fresh_docs, unknown_docs, stale_sample[], index_checked:false,
#    needs_rebuild, recommendation}
```

`stale_docs` = 向量缓存头部的 `clean_rev/chunk_rev` 与当前口径**不一致**的文档数（⇒ 会重算）；
`fresh_docs` = 一致（缓存仍有效）；`unknown_docs` = 没有向量缓存（从未向量化）。
`needs_rebuild=true` 时跑 `POST /admin/reindex?force=true`（`force` 才会忽略"已存在"）。
本端点是**只读**的：不发请求、不写文件、不改索引；`index_checked=false` 表示没有连集群核对索引里的 `chunk_key`
（`opensearch` 后端上"缓存一致"不等于"索引齐"，那一步要另跑一次重建或人工核对）。

## 检索级评测（recall@k / MRR / 命中率；**不进 `mvn test`**）

`scripts/eval-ai.mjs` 评的是**端到端问答**（数字一致率、拒答、工具选择）。检索这一层单独有一把尺子：

```powershell
cd ai-backend
mvn -o test-compile
mvn -o dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
$cp = (Get-Content target/cp.txt -Raw).Trim()
$env:LLM_API_KEY = '<平台 sk>'        # 需要真检索时（向量化/重排走平台网关）
java -cp "target/classes;target/test-classes;$cp" com.pmgt.ai.tools.RetrievalEvalTool `
     --work ./work --eval ../eval/smoke-30.json --top-k 5 --out ./target/retrieval-eval.json
```

- **指标**：`recall@k`（top-k 覆盖到的真值数 / 该题真值总数，宏平均）、`MRR`（首个命中真值的排名倒数）、`命中率`（top-k 里至少 1 条真值的题占比）；
- **真值两个来源**：`expected.citation_pages`（**人工标注**的页码，当前数据集全是 `null`）或
  `expected.citation_chunk_keys`（正确切片键，更严格；给了就优先按它比对）；
- **`citation_pages=null` ⇒ 该题记 `SKIPPED_UNLABELED`**：**跳过而不是失败**（退出码仍为 0），
  指标分母只统计已标注题；全部未标注时打印 `n/a`（**不是 0**——0 会被读成"全错"）。
  **页码标注是用户的任务**（[`../eval/README.md`](../eval/README.md) §1 的硬边界，不许瞎标）；
- **退出码**：0 = 无失败（含全跳过）；1 = 有已标注题一条都没命中；2 = 用法/参数错误；
- 工具源码在 [`src/test/java/com/pmgt/ai/tools/RetrievalEvalTool.java`](src/test/java/com/pmgt/ai/tools/RetrievalEvalTool.java)，
  指标计算在**主源码** [`src/main/java/com/pmgt/ai/module/eval/RetrievalEval.java`](src/main/java/com/pmgt/ai/module/eval/RetrievalEval.java)
  （纯函数，被 `RetrievalEvalTest` 覆盖，含 `citation_pages=null ⇒ SKIP` 的用例）。
- **为什么不开 `/admin/eval` 端点**（本轮的二选一）：① 它依赖平台网关与人工标注，是开发/验收期活动；
  ② AI 服务当前**没有入站鉴权**（见「运维与风险」），新开一个"能对全库跑任意 query"的端点等于开一个未鉴权探测面；
  ③ 与既有 `TopKCompareTool` 同一先例（手工跑、口令只从环境变量读），不引入第二种跑法。

## top-k 一致性比对工具（手工对真集群；**不进 `mvn test`**）

同一批切片 + 同一组 query，分别在 `local`（进程内余弦）与 `opensearch`（集群 kNN）上检索，
打印两边 top-k 的**重合率**与耗时——这是"切 `VEC_BACKEND=opensearch` 之前必须做"的验证
（口径见 [`../docs/OpenSearch部署与接入.md`](../docs/OpenSearch部署与接入.md) 步骤 19：
`_count` 对得上 **且** top-k 顺序与 `local` 一致，才算切换成功）。

```powershell
cd ai-backend
mvn -o test-compile
mvn -o dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
$cp = (Get-Content target/cp.txt -Raw).Trim()
$env:OPENSEARCH_PASSWORD = '<生产口令>'      # 口令只从环境变量读，绝不进命令行/日志
$env:LLM_API_KEY = '<平台 sk>'
java -cp "target/classes;target/test-classes;$cp" com.pmgt.ai.tools.TopKCompareTool `
     --work ./work --url http://10.254.212.110:9200 --user admin `
     --eval ../eval/smoke-30.json --top-k 5 --reindex
```

输出：每组 query 的两边 top-k（`docId:pageNo`）、`top-k 重合率`、耗时，末尾给平均重合率与 top-1 一致数。
`--reindex` 才会写索引（幂等、按 `chunk_key` 跳过已存在）；不带它只读不写。
工具源码在 [`src/test/java/com/pmgt/ai/tools/TopKCompareTool.java`](src/test/java/com/pmgt/ai/tools/TopKCompareTool.java)，
说明写在类注释里。

## 运维与风险

- **平台不可用时不降级**：OCR 没有本地兜底引擎（实测本地引擎金额全丢，比明确失败更危险）；
  失败页在响应里以 `failed_pages` + `notes` 显式暴露，需人工复核。
- **检索降级是可见的**：`local` 后端下向量服务不可用时退化为关键词检索，并在结果 `note` 里写明。
- **`VEC_BACKEND=opensearch` 在集群未配置/不可达时显式报错（HTTP 503 + "向量后端不可用"），
  不静默退回 `local`，也不退回关键词检索**——集群挂了却给出"部分结果"比报错更容易误导
  （目标集群里可能已有几十万切片，关键词那一路根本召回不全）。
- **超时链**：`AI_CALLER_TIMEOUT(180) > chat 150 / rerank 20 / embed 30 / ocr 600`——
  `ocr` 是有意的例外（单次 OCR 可能跑几分钟，且它是"没有人在等"的批量调用，不占问答槽位）。
  启动日志会逐条核对并打印结论（`StartupDiagnostics`）。
- **`local` 后端仍要求单实例**（进程内向量缓存 + 文档库都在本地卷上）；
  换成 `opensearch` 后索引与向量移出进程，AI 服务才具备水平扩展的前提。

## 文档索引

| 文档 | 用途 |
| --- | --- |
| [`docs/迁移方案与对照表.md`](docs/迁移方案与对照表.md) | **入口文档**：Python → Java 的模块对照、必须原样保留的踩坑结论、HTTP 契约与验证协议 |
| [`docs/平台能力实测结论.md`](docs/平台能力实测结论.md) | **与语言无关的实测结论**：平台 OCR 渲染/并发/印章、确定性校验、大模型与检索链路的全部实测数字与依据（换实现语言也不该丢的部分） |
| [`docs/平台OCR调用使用手册.md`](docs/平台OCR调用使用手册.md) | 平台 OCR（PaddleOCR-VL）的 HTTP 接口、印章与手写体、性能基线与排障（**由 ai-service 迁入**） |
| [`docs/Qwen3-VL-Embedding-Reranker调用手册.md`](docs/Qwen3-VL-Embedding-Reranker调用手册.md) | Embedding / Reranker 的接口、维度与性能基线、召回—精排配比（**由 ai-service 迁入**） |
| [`docs/知识库实施方案与路线.md`](docs/知识库实施方案与路线.md) | **知识库/检索层的一份文档**（原"落地实施方案"与"总体架构与演进路线"合并）：目标与前提、四层架构、索引 mapping 与字段、`VEC_BACKEND=opensearch` 对接、P0/P1/P2 分期与评测集指标、风险登记 |
| [`../docs/AI工具集与检索编排评估.md`](../docs/AI工具集与检索编排评估.md) | **动工具集或检索编排之前必读**（主系统侧文档）：平台的"无状态能力"与本服务"有状态编排"的职责边界、`search_documents`/`read_page`/`calculate` 逐个保留理由、为什么默认走流水线而不是全自主 agent、改造清单与验收口径 |

