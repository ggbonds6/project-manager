# RAG 全链路完整性评估（解析 → 清洗 → 切分 → 召回 → 生成）

> **触发问题（用户原话）**：
> "我想先评估一下，整个流程，解析，清洗，切分，召回，生成。流程是否完善，企业主流的流程，当前项目是否有缺失，技术栈是否合理？先评估完再研究如何完善。"
>
> **本轮范围**：**只评估、只写文档**，`ai-backend/**`、`backend/**`、`frontend/**`、`scripts/**`、`deploy/**` 一行未动。
> **评估对象**：`ai-backend`（AI 能力服务，Spring Boot 3.3.5 / Java 17）+ 主系统侧接入（`backend/src/main/java/com/pmgt/module/ai/**`）+ 评测资产（`eval/smoke-30.json`、`scripts/eval-ai.mjs`）。
>
> **证据口径（三类，逐条标注，不混用）**：
> ① **代码事实**＝给出 `文件:行`，可逐字复核；
> ② **实测**＝仓库既有文档里记录的实测数字（注明出处文档）；
> ③ **估算**＝无实测依据的推导，一律写成 **估算（假设：…）**，并写清假设。
> 读不到、判不了的一律进 §7「未确认项」，并给"怎么确认"——**本文字不出现"应该是/大概"式的结论**。
>
> **与既有文档的边界（避免重复结论）**：
>
> | 既有文档 | 它回答的问题 | 本文不重复的部分 |
> | --- | --- | --- |
> | [`AI工具集与检索编排评估.md`](AI工具集与检索编排评估.md) | 3 个模型工具留不留、检索该不该改"服务端预取"、要不要引 LangChain4j | 工具取舍与框架取舍（本文只在 §5/§6 引用其触发条件，不重新论证） |
> | [`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md) | 13 个模型调用点的超时/重试/并发/批量、并发上限推导（问答 0.16→0.4 QPS）、何时必须换 OpenSearch、队列方案 | 尺寸/并发数字（本文引用其结论，不重算） |
> | [`AI前端与集成方案.md`](AI前端与集成方案.md) §11 | P2 受控查询的契约与提示词路由规则 | 业务数据工具的设计（本文只评估其在"生成"环节的完备性） |
> | [`OpenSearch部署与接入.md`](OpenSearch部署与接入.md) 附录 E | 索引 mapping、预过滤 + hybrid 的查询写法、部署与回滚 SOP | 部署步骤（本文只评估"在线路由这段代码"的正确性与成本） |
> | [`../eval/README.md`](../eval/README.md) + [`../eval/smoke-30.json`](../eval/smoke-30.json) + [`../scripts/eval-ai.mjs`](../scripts/eval-ai.mjs) | 端到端冒烟（数字一致率/引用命中率/工具选择/拒答） | 评测集的题面与判据实现（本文评估它"覆盖了什么、没覆盖什么"） |
> | [`../ai-backend/docs/平台能力实测结论.md`](../ai-backend/docs/平台能力实测结论.md)、[`../ai-backend/docs/知识库实施方案与路线.md`](../ai-backend/docs/知识库实施方案与路线.md) | 平台四件套的实测数字、知识库四层架构与 P0/P1/P2 分期 | 架构愿景（本文只对照"已落地的代码"与"主流企业做法"） |

---

## 1. 结论摘要（先给答案）

### 1.1 五环节完整度评级（判据可证伪，见右列）

| 环节 | 评级 | 一句话结论 | 判据（达到即升级，达不到即维持） |
| --- | --- | --- | --- |
| **解析** | **B**（基本完善，有明确缺口） | PDF 双路（文本层 / 扫描件）+ 平台 OCR 版面块 + 页级溯源 + 页级失败暴露，做得**比多数同规模项目扎实**；但**只吃 PDF 与 7 种图片**，混合型 PDF 无逐页回退，表格/公式/图表未结构化，无解析质量抽检基线 | 若"非 PDF/图片格式可用率"与"混合型 PDF 无静默丢页"两条都成立，则升 A |
| **清洗** | **D（无独立清洗阶段）** | 代码里**没有清洗环节**：只有换行归一、空白页归一、切片时 `strip()` 三处被动归一。页眉页脚/水印/页码行/断行连字符/段落与文档级去重/编码归一 **逐条为"无"**（依据见 §2.2） | 出现一个独立的"清洗器"接口并在入库链路里被调用，即升 C；再把页眉页脚量化噪声降下来，升 B |
| **切分** | **C**（部分缺失） | 段积累式、**500 字、不跨页、无重叠、无父子块、无结构感知**；元数据只有 `docId/filename/pageNo`；表格可被 500 字边界切断；切片现算不落盘 | 若"表格整块不截断""父子块 small-to-big""重叠窗口"三项缺一，即维持 C |
| **召回** | **B-**（骨架完整，两处硬伤） | 双路召回（向量 + 自研 2-gram/IDF 关键词）→ 去重融合 → cross-encoder 精排，作用域**硬过滤已下推到 kNN filter**；但**请求级 `doc_ids` 根本没下推到检索层**，且 **OpenSearch 路径每次检索会把当前作用域全部切片重新 `_bulk` 写一遍** | 两条硬伤修掉、且在线检索有 recall@k 基线，即升 B+ |
| **生成** | **B**（基本完善，防护是一半） | 提示词铁律（只依据原文 / 句末 `[cite]` / 缺失即声明 / 数字原样照抄 / 不确定就说不确定）+ 结构化引用表 + 拒答口径 + 工具集克制，**约束密度高于主流**；但 `/chat` **没有任何上下文预算**（`LLM_MAX_INPUT_CHARS` 只作用于 `/analyze`），且 **答案侧的机器校验（数字可溯源、`[n]` 合法性）在 `/chat` 完全没有**，只剩提示词在拦 | 加"答案侧机器校验"与"单次问答预算"两项，即升 A- |

> 一句话总评：**"解析—召回"这条主干是工程化的、有实测撑着；"清洗"这一环是空白；"切分"是能用的起点而非方案；"生成"的约束写得比实现严——提示词要求的校验，代码只做了 `/analyze` 那一半。**

### 1.2 缺口清单（30 秒版，全量见 §4）

| # | 缺口 | 现象（代码事实） | 为什么是硬伤 |
| --- | --- | --- | --- |
| G1 | **无独立清洗阶段** | 全仓无 clean 相关实现（§2.2 逐条依据） | 页眉页脚/水印/页码行进切片 → 污染向量与关键词两路，召回精度不可控 |
| G2 | **OpenSearch 路径每次检索全量 `_bulk` upsert** | `RetrievalService.java:440-442` 在**每次 `search()`** 里把当前作用域的所有切片写回索引 | 生产（`VEC_BACKEND=opensearch`）下每次提问重写全库切片；**估算（假设：15,000 切片、每切片 JSON 38,333 字节，字节数取自《AI模型调用与任务队列评估》§3.1 真机读数）≈ 575 MB/问** |
| G3 | **请求级 `doc_ids` 未下推检索层** | `QaService.java:206` 只把它拼进 system 提示词；`Tools.java:183` 直接拿**模型给的** `docId` 去检索；`RetrievalService.java:200-202` 自认"不在本轮 P0 范围" | 请求级作用域是**软约束**：模型不传 `doc_id` 就检索全库；而 AI 服务**无入站鉴权**（`ai-backend/README.md:104-106`） |
| G4 | **混合型 PDF 无逐页回退** | `DocumentReader.java:107-111`：整份按"平均每页字符 < 50"二分；文本层路径（`:121-139`）**从不**对空页补 OCR | 电子版里夹几页扫描页 → 那几页**静默为空**（只在 `empty_pages` 里留痕），答案会"看起来完整" |
| G5 | **非 PDF/图片格式全不支持** | `DocumentReader.java:69-70,113-116`：只认 `.pdf` 与 7 种图片，其余 `kind=error` "不支持的格式" | 主系统附件白名单里有 docx/xlsx/md 等（前端还能预览），这些附件走 AI 解析**必然失败** |
| G6 | **`/chat` 无上下文预算** | `LLM_MAX_INPUT_CHARS` 只在 `AnalyzeService.java:78` 被使用（已 grep 全仓） | 8 轮工具循环里消息列表无上界：每轮重发全部历史 + 工具结果，prefill 成本与超时风险随轮数线性膨胀 |
| G7 | **答案侧机器校验缺席（`/chat`）** | `Checks.verifyNumbersInSource` 只被 `AnalyzeService.java:146` 调用 | "禁止虚构"在问答链路上**只有提示词**；引用编号 `[n]` 是否真在 `citations` 表里也**无人校验** |
| G8 | **检索级评估缺失** | `scripts/eval-ai.mjs` 只报端到端指标（数字一致率/引用命中率/工具选择/拒答/延迟）；9 道文档题 `citation_pages=null` → 引用命中率恒 `n/a`（[`../eval/README.md`](../eval/README.md):65） | 任何切片/召回/降配改动**没有判据**；现有"降配前必须评测"的门禁缺尺子 |
| G9 | **可观测性只覆盖"速度与容量"** | micrometer 指标见 `ai-backend/README.md:127-148`（模型 QPS/延迟分位/在途/排队/重试/队列深度/向量化计数） | 没有"零命中率 / 空引用率 / 重排降级次数 / 拒答率"这类**质量信号**，线上劣化只能靠用户投诉 |
| G10 | **重解析不幂等、旧数据不清理** | `DocStore.java:90-100` 每次新 UUID；`DocStore.delete`（`:147-154`）不碰向量文件与索引 | 同一附件重解析 → 新 `doc_id`、旧文档与 `work/vectors/*.json` 永留 → 重复切片占候选位、`work/` 无界增长 |

### 1.3 技术栈总评（详见 §5）

| 项 | 判定 | 一句话理由 |
| --- | --- | --- |
| Spring Boot 3.3.5 + JDK 17 | **合理** | 与主系统同栈，一套工具链/发布脚本/人；AI 侧没有需要框架才能表达的东西 |
| JDK `HttpClient` + Jackson 原生 REST（无 LangChain4j、无 OpenSearch Java Client） | **合理** | 只要 5 个 REST 动作 + 4 类模型调用；审计场景要"每次调用可见"，框架会把它藏起来（既有结论见《AI工具集与检索编排评估》§2.3/§7） |
| `Qwen3.8-27B-W8A8` 4 副本 | **合理** | 生成侧上限 ≈0.4 QPS（实测口径见《AI模型调用与任务队列评估》§3.3），当前需求远低于它 |
| `PaddleOCR-VL`（平台侧 6 DCU，**该数字不在仓库代码里，见 §7 未确认 13**）+ 全进程 12 在飞 | **合理** | 代码侧已把"每任务自建池"改成**全进程共享池 + 动态批页**（`PlatformOcrClient.java:198-238`），并发语义与平台甜点对齐 |
| `Qwen3-VL-Embedding-8B` 单实例 4096 维 | **合理但被 `local` 后端绑住** | 维度固定、不支持 MRL（传 `dimensions` 会 400）；`local` 后端是**进程内状态** ⇒ AI 服务必须单实例 |
| `Reranker-8B` 单实例 | **勉强（当前第一瓶颈）** | 24 对/s（平台实测），recall=50 时 ≈75 对/次检索、≈2 次检索/问 ⇒ 问答上限被压到 ≈0.16 QPS。**该加：先降候选/改预取，再考虑第二实例** |
| OpenSearch 3.1.0 **单节点**（HNSW lucene、`cosinesimil`、1 分片 0 副本） | **勉强可接受** | 单节点是**明确的无 HA 取舍**（快照兜底，已有明账）；且 1 分片**不可拆分**，扩容必须重建索引 |
| `local` 进程内向量作为回滚后端 | **合理** | 回滚路径必须有；但**只能当回滚**——两条后端同时接业务会产生"同一问题两种召回"（`application.yml:76-78` 已写死这条纪律） |
| `work/` 文件态存储（docs/vectors/doc-meta） | **勉强** | 零运维、可人工查看，但**单实例硬约束** + 每次检索全量读盘（《AI模型调用与任务队列评估》§3.4 已量化） |
| `attachment_ai_task` 作为任务事实源 | **合理方向、当前未收敛** | 主系统表是事实源，但 AI 侧 `work/tasks.json`（最多 100 条）仍是影子记录，重启即标 FAILED |
| `eval/smoke-30.json` + `scripts/eval-ai.mjs` | **合理的第一版** | 35 题（16 结构化 / 9 文档 / 4 混合 / 4 负例 / 2 越权）、真值取自页面接口——**方向对**；缺"检索级指标 + 页码标注" |

**总评：整体技术栈"合理"，不需要换栈；需要"加两件、改一件"**——加**清洗阶段**与**检索级评测**，改**OpenSearch 在线路由（去掉查询侧全量 upsert）**。量化触发点见 §5.2。

### 1.4 最该做的 5 件事（本评估的建议顺序）

| 顺序 | 事项 | 为什么排这个位置 | 工作量级 |
| --- | --- | --- | --- |
| 1 | **修掉查询侧全量 `_bulk`（G2）** | 生产正跑 `opensearch`；这是"每次提问重写全库"的成本与写放大，属于**必须马上修的正确性问题**，且改动极小（删一段 + 把补写改成异常路径） | **0.5~1 人日（估算）** |
| 2 | **把请求级 `doc_ids` 下推到检索层（G3）** | 接口接缝（`SearchPort.searchInScope` + `SearchScope`）**已经就绪**，只差 `QaService`/`Tools` 把参数传下去；不修则作用域是软的 | **1~2 人日（估算）** |
| 3 | **补评测的"尺子"：9 道文档题页码 + 检索级 recall@5/MRR（G8）** | 没有它，后面所有切片/召回/降配改动都无法判定优劣——**它是第 4、5 项的前置** | **3~5 人日（估算）** |
| 4 | **建独立清洗阶段（G1），先做四件低风险项** | 清洗是当前**唯一整段空白**的环节，且它同时影响向量与关键词两路的质量 | **3~5 人日（估算）** |
| 5 | **补 `/chat` 的答案侧机器校验与上下文预算（G6/G7）** | 提示词已经把要求写在纸面上，代码只做了一半；补上即把"禁止虚构"从"期望"变成"可检测" | **3~5 人日（估算）** |

---

## 2. 现状盘点（逐环节：真实参数 + 代码位置）

### 2.0 端到端数据流（代码级）

```
上传（multipart，≤500MB）
  → UploadTaskService.submit（:145-174）落 work/tmp + 登记任务，立即返回
  → 线程池 uploadTaskExecutor（core2/max4/queue100，AsyncConfig.java:21-33）
  → run（:236-323）：
       detect（PdfReader.read → 页数/文本层字符数）
       → DocumentReader.read（:98-117）分流：文本层 / 扫描件(渲染→平台 OCR) / 图片 / error
       → 平台 OCR（PlatformOcrClient.recognize：共享池 12、动态批页 ≤8，平台上限 16）
       → Checks.checkDocument（确定性校验，不改原文）
       → DocStore.save（work/docs/<doc_id>.json，doc_id = uuid4 前 16 位 hex）
       → DocIndexer.indexDocument（:88-166）：DocStore.iterChunks（500 字、不跨页）
             → EmbedBatcher（批 32 × 并发 8，共享 embed 信号量）
             → work/vectors/<doc_id>.json（local）或 OpenSearch _bulk（opensearch）
问答（POST /chat）
  → ChatController（:44-52）→ QaService.ask（:186-257）
       scopedDocs(docIds) → 只用于 system 里的文档清单（**不是检索过滤**）
       → ToolAgent.run（:210-305）工具循环（≤8 轮）
            → Tools.searchDocuments → SearchPort.search（RetrievalService.searchInScope）
                 关键词 2-gram+IDF 打分（全作用域切片）
                 向量召回（local 进程内余弦 / opensearch kNN 带 filter）
                 融合（向量榜在前、关键词榜 recall/2 补齐，LinkedHashSet 去重）
                 → Reranker 精排（Qwen3-VL-Reranker-8B，一次 ≤ recall+recall/2 对）→ top_k
            → read_page（整页 ≤4000 字） / calculate（BigDecimal 白名单）
       → CitationRegistry（doc_id+page_no 去重，编号按首次命中顺序）→ citations
```

### 2.1 解析

**支持的格式（`DocumentReader.java:98-117`）**

| 输入 | 分支 | 代码位置 | 说明 |
| --- | --- | --- | --- |
| `.pdf` | 先 `PdfReader.read` 取页数/文本层字符数，**平均每页字符 < 50 判扫描件** | `DocumentReader.java:102-111`、`PdfReader.java:94-107` | 阈值来自 `AiSettings.Ocr.scannedCharThreshold = 50`（`AiSettings.java:75`） |
| 文本型 PDF | 逐页取文本层，`source="text-layer"`，kind=`text_pdf` | `DocumentReader.java:121-139`、`PdfReader.java:130-141` | 逐页独立 `PDFTextStripper`（`PdfReader.java:218-226`），**页码与页序严格一一对应** |
| 扫描件 PDF | 逐页渲染 → 平台 OCR，kind=`scanned` | `DocumentReader.java:150-193` | 渲染产物写 `work/pages/<uuid>/page_0001.jpg`，`finally` 删除（`:185-188`） |
| 图片 `.png .jpg .jpeg .bmp .tif .tiff .webp` | 直接平台 OCR，kind=`image` | `DocumentReader.java:69-70,113-114,196-211` | 不渲染、不判扫描 |
| **其它一切后缀** | `kind=error` + "不支持的格式：<后缀>" | `DocumentReader.java:116` | **DOCX / XLSX / PPTX / TXT / MD / OFD 均为不支持** |

**文本层 / 扫描件的区分与"混合型"**

- 判据是**整份文档的平均每页字符数**（`PdfInfo.isScanned(threshold)`，`DocumentReader.java:107-111`），**不是逐页判定**。
- 文本层路径（`:121-139`）**没有**"空页 → 补跑 OCR"的回退；`emptyPages()`（`DocumentText.java:140-142`）只把空页记进 `summary()` 与 `review_pages`。
- ⇒ **一份"电子版 + 夹几页扫描页"的 PDF，只要平均字符数 ≥50，那几页就是空的**（可证伪判据：对该文件调 `POST /documents` 后看 `summary.empty_pages` 与 `pages[].text` 是否为空且无 OCR 补跑）。

**OCR 走平台 `PaddleOCR-VL` 的哪些能力**

| 能力 | 判定 | 代码依据 |
| --- | --- | --- |
| 版面重建 | **部分** | 平台返回 `blocks[{label, content, bbox}]`，代码**只留 `label + bbox`** 做质量信号（`PlatformOcrClient.java:477-503`、`StoredDoc.BlockRef`），**正文用 `markdown` 原样**（`PlatformOcrClient.java:468-470`） |
| 表格 | **部分（依赖平台 markdown）** | 代码仅在 `PageText.tableCount()`（`PageText.java:102-104`，`label=="table"` 计数）里把它当"含表格 N 个"的页头标记（`DocumentText.java:134-138`、`StoredDoc.java:277-283`），**不做 blocks → HTML/Markdown 表的重建** |
| 印章 | **部分（默认关）** | `AiSettings.java:68` `seal=false`：关时平台仍返回 `label=="seal"` 的空块（"有章"的免费探测，`sealCount()`）；开时按 `sealMinPixels=400000` 放大重跑（`PlatformOcrClient.java:266-272`），**低 DPI 会编造印章文字**故默认关 |
| 手写 / 公式 / 图表 | **未确认** | 代码**不区分**这些 label（只认 `table`/`seal`）；平台是否返回见 §7 未确认第 1、2 条 |

**DPI 与批页（实测口径已在代码注释里写死）**

- 发给平台的图：`platformDpi=150` / `platformFormat=jpeg` / `platformQuality=85`（`AiSettings.java:70-73`、`application.yml:52-54`）。实测 120~300 DPI 识别结果**一致**（VL 内部下采样到 ≈100 万像素），而 300 DPI PNG 每页 6.7MB vs 150 DPI JPEG 396KB（`PdfReader.java:47-51`）。
- 批页：`batchPages=8`（配置上限），平台硬上限 `MAX_BATCH_PAGES=16`（`PlatformOcrClient.java:101-102`）。
- **在飞语义已修正**：全进程共享一个 OCR 线程池，池大小 = `OCR_CONCURRENCY=12`；批页 = `min(batchPages, ceilDiv(页数, permits))`（`PlatformOcrClient.java:205-224`）⇒ 8 页文档会被切成 8 个单页批次（旧写法下只有 1 个批次在飞）。

**失败与重试**

| 项 | 行为 | 代码依据 |
| --- | --- | --- |
| 单批失败 | 同一句错误**分发到该批每一页**，失败页在返回列表里**占位**（返回顺序 == 输入顺序） | `PlatformOcrClient.java:281-291` |
| 失败页留痕 | `notes` 写明"X 页平台识别失败（第 …）：本地 OCR 兜底已移除，这些页没有内容，需人工复核" | `DocumentReader.java:221-236` |
| 兜底引擎 | **无**（本地 RapidOCR 已于 2026-09-18 移除，行为不回退） | `DocumentReader.java:214-219` |
| 重试 | 幂等重试最多 `MODEL_RETRY_MAX=2`，退避 1s→4s + 抖动 ≤250ms；仅连接错误/超时/5xx/429 | `PlatformOcrClient.java:305-308`、`application.yml:108-113` |
| 健康探测 | TTL 缓存：ok 60s / fail 15s | `PlatformOcrClient.java:104-108,349-367` |
| 中间产物 | `finally` 删除 `work/pages/<uuid>/` | `DocumentReader.java:185-188,374-389` |
| 超大文件上限 | Spring `max-file-size=500MB` / `max-request-size=600MB`；但 `TempUploads` 另有 300MB 硬限（既有结论：口径不统一，见《AI模型调用与任务队列评估》P1-7） | `application.yml:18-21` |

**页级来源如何保留（这是本项目做得好的一环，逐级可查）**

| 层级 | 载体 | 代码依据 |
| --- | --- | --- |
| 解析 | `PageText.pageNo`（1 基，来自循环下标） | `DocumentReader.java:124-129,250-259` |
| 落盘 | `StoredDoc.PageInfo.page_no`（`@JsonProperty("page_no")`，注释里记录了"漏标就静默丢页码"的实测事故） | `StoredDoc.java:323-329` |
| 喂模型 | `DocumentText.labeledText()` → `【第 N 页 · 含表格 X 个】`；`StoredDoc.labeledText(maxChars)` 同形 | `DocumentText.java:134-138`、`StoredDoc.java:271-293` |
| 检索 | `DocStore.Chunk(docId, filename, pageNo, text)` | `DocStore.java:187` |
| 引用 | `CitationRegistry` 以 `doc_id + page_no` 为键去重，返回 `citations[{index,doc_id,filename,page_no,snippet,score}]` | `CitationRegistry.java:88-134` |

**解析质量抽检**：**只有人工复核优先级，没有抽检基线**——`review_pages()`（`StoredDoc.java:228-251`）把"识别失败 + 空白 + 校验不通过"的页排成清单供人工看；但**没有**"用固定样本集跑差分"的机制（无黄金集、无字符级对照报告，`PdfReader.debugSample` 只是手工排障工具，`PdfReader.java:189-206`）。

### 2.2 清洗

**结论（明写）：本项目没有独立清洗阶段。** 全仓 `grep -i "clean|清洗|页眉|页脚|水印"` 只命中 `SearchScope.clean()`（去空白字符，`SearchScope.java:90-101`）与 `VecClient.normalizeLimit()` 等**与文本清洗无关**的实现。

逐条判定（**这就是本环节的全部事实**）：

| 清洗能力 | 判定 | 代码依据（无则为"无实现"） |
| --- | --- | --- |
| 页眉 / 页脚剔除 | **无** | 无任何实现；`DocumentText.labeledText` 反而**新增**页头标记（`DocumentText.java:136`） |
| 水印剔除 | **无** | 无实现；平台 markdown 若含水印文字会原样进切片（见 §7 未确认第 1 条） |
| 页码行剔除 | **无** | 无实现 |
| 断行合并 / 连字符修复 | **无** | 仅在切片时按"空行 → 换行"分段（`DocStore.java:214-229`），不合并断行 |
| 表格与正文分离 | **无** | 表格只是页头标记（`PageText.java:114-128`），正文仍是一整块 markdown |
| 段落级去重 | **无** | 无实现 |
| 文档级去重（同内容重复入库） | **无** | `DocStore.save` 每次新 UUID（`DocStore.java:90-100`），无内容指纹 |
| 编码归一 | **部分（仅换行）** | `\r\n` / `\r` → `\n` 仅发生在 **PDF 文本层**路径（`PdfReader.java:229-234`）；OCR 路径不做 |
| 空白归一 | **部分** | 整页 `isBlank()` → `""`（`PdfReader.java:225`）；切片时 `text.strip()`（`DocStore.java:209`）、每个段落/行 `strip()`（`:216,224`） |
| 乱码 / 异常字符处理 | **无** | 无实现（`Checks` 与 `CitationRegistry` 的归一都明确声明"不改原文"——`Checks.java:40`、`CitationRegistry.java:148-153`） |
| 敏感信息处理（脱敏） | **无** | 无实现；主系统侧也不在 AI 链路做（越权题的判据是"不泄漏"，见 `../eval/README.md`） |
| 空页 / 噪声过滤 | **部分** | 空页在切片时被跳过（`DocStore.java:211-213`）；**但空页本身不被过滤掉**，仍进 `pages` 与 `empty_pages` 清单 |

**三条"看起来像清洗、实际不是"的地方（避免误判为已有能力）**：

1. `RetrievalService.fold()`（`:564-580`）把全角标点折半角——**只作用于打分时的临时副本**，原文、向量缓存、返回给模型的 `text` 全不动（`:640` 注释明确）。
2. `CitationRegistry.normalizeSnippet()`（`:148-153`）折空白 + 截断到 200 字——**只作用于前端展示副本**。
3. `Checks.checkDocument` / `verifyNumbersInSource` 只"指出"不"修改"（`Checks.java:40`）。

> ⇒ 这三处都是**为了别的目的**做的归一，不能算清洗环节。**"无独立清洗阶段"是本评估的一条硬事实。**

### 2.3 切分

| 项 | 现状 | 代码依据 |
| --- | --- | --- |
| 目标切片长度 | **500 字** | `DocStore.java:42` `DEFAULT_CHUNK_CHARS = 500`（可传参覆盖，但生产调用点一律用默认：`DocIndexer.java:91`、`RetrievalService.java:502`） |
| 切分策略 | **按段落累积**：先按空行（`\n\s*\n`）分段；若该页只有 1 段（扫描件常见一整块），退化为**按换行逐行**累积 | `DocStore.java:205-249` |
| 跨页 | **不跨页**（逐页内部切完即封口） | `DocStore.java:208`（外层 `for (PageInfo page : doc.getPages())`） |
| 重叠 | **无重叠**（`buf.setLength(0)` 直接清空，`DocStore.java:236`） | 同上 |
| 父子块（small-to-big） | **无** | 无实现；检索命中只返回切片本体（`RetrievalService.java:355-365`），`read_page` 是"再花一次工具往返读整页"（`Tools.java:253-282`） |
| 结构感知（标题层级 / 章节） | **无** | 无标题识别；markdown 的 `#` 只作为普通字符参与累积 |
| 表格不截断 | **无保证** | 段落累积到 500 字就断开；一个 markdown 表格若跨 500 字边界会被切成两片（**可证伪判据**：造一份含 800 字宽表的 PDF，检查 `iterChunks` 输出里是否存在"有表头无表体"或"只有分隔行"的切片） |
| 长句不截断 | **部分** | 只在"段落/行"粒度断开——单个段落若自身 > 500 字，会成为**一个超长切片**（`:237-242` 的 `else` 分支直接 append，不二次切） |
| 切片元数据 | **只有 `docId / filename / pageNo / text`** | `DocStore.java:187` |
| 项目 / 密级 / 时间元数据 | **不在切片上**，在 side-car `work/doc-meta.json`（`DocMetaStore.java`）里按 `docId` 关联；写 OpenSearch 时作为**文档字段**写入（`OpenSearchIndex.java:362-379`） | `DocMeta.java:20-27`、`DocMetaStore.java:28-47` |
| 切片唯一键 | `docId:pageNo:sha1(文本)[0:12]` | `RetrievalService.java:583-586` |
| 切片是否落盘 | **不落盘**（`StoredDoc.chunks` 恒空，每次现算） | `StoredDoc.java:23-25,44` |
| 返回给模型的片段上限 | 检索命中 **500 字**（截断补 `…`）；`read_page` **4000 字** | `RetrievalService.java:79,363-365`、`Tools.java:81,273-275` |

**切片大小与模型上限的关系（可算的部分）**

- embedding：固定 4096 维、不支持 MRL（`AiSettings.java:84-86`；实测 400 见《平台能力实测结论》）。500 字 → 向量化**远未触到模型输入上限**，即**"500 字"不是被 embedding 上限逼出来的，而是语义完整度与检索精度的权衡**。
- 生成侧：一次问答里"命中 5×500 字 ≈ 2,500 字"进上下文（`RetrievalService.java:79` + `Tools.normalizeTopK` 默认 5），相对 20000 字的预算占比很小——**说明当前瓶颈不在"切片太大"，而在"上下文预算根本没接"（G6）**。

### 2.4 召回

**链路（`RetrievalService.searchInScope`，`:210-374`）**

| 步骤 | 现状 | 代码依据 |
| --- | --- | --- |
| 0. 入口 | `search(query, topK, docId, recall)` → `SearchScope.ofDocIds([docId])` | `RetrievalService.java:185-191` |
| 1. 后端守卫 | `local` 放行；`opensearch` 必须配 URL 且**探活通过**（`GET /_cluster/health`），**绝不静默退回** | `RetrievalService.java:519-544`、`OpenSearchIndex.java:650-678` |
| 2. 装载切片 | 无作用域 → `DocStore.loadChunks(null)` = **读全库所有 doc JSON（含全文）**；有 docIds → 逐份读 | `RetrievalService.java:494-506`、`DocStore.java:157-170,252-267` |
| 3. 作用域硬过滤 | 先按元数据交集过滤（`scope.matches`），越界即空、**不退回全库**；计数 `ai.retrieval.scope.filtered` | `RetrievalService.java:228-245`、`SearchScope.java:57-66` |
| 4. 关键词召回 | 自研：英文/数字整词 + 中文 2-gram（去停用二元组），分数 = 完整查询命中 12 分 + `idf*(1+min(tf,5)*0.25)`，`idf = log(1 + n/(1+df))`；**对全作用域切片逐条扫描** | `RetrievalService.java:596-669`、`STOP_BIGRAMS`（`:87-89`） |
| 5. 向量召回（local） | 进程内余弦；切片向量按文档缓存 `work/vectors/<docId>.json`（模型名或维度不符即失效） | `RetrievalService.java:707-778,795-857`、`VecClient.java:343` |
| 5'. 向量召回（opensearch） | 切片向量走同一份本地缓存 → **`ensureIndexOnce` → `upsertAll(全部作用域切片)`** → query 向量化 → kNN（带 `filter` 下推）→ 丢弃本地已不存在的陈旧命中 | `RetrievalService.java:409-459`（**`upsertAll` 在 `:441`**）、`OpenSearchIndex.java:528-549` |
| 6. 融合 | **不是 RRF、不做分数归一**：向量榜（按分数取 `recall` 条）在前，关键词榜（`recall/2` 条）补齐，靠 `LinkedHashSet` 插入顺序去重 | `RetrievalService.java:293-309` |
| 7. 重排 | `Qwen3-VL-Reranker-8B` 一次给全部候选打分，取 `top_k`；`hit.index()` 是**入参下标**（不是排名） | `RetrievalService.java:320-344`、`VecClient.java:264-299` |
| 8. 重排不可用降级 | **仅 local 路径**：按 `2*向量分 + 关键词分` 排序，并在 `note` 写明原因；**opensearch 路径失败直接抛** | `RetrievalService.java:282-291,331-344` |
| 9. 空结果 | 返回 0 条 + 明确文案（"作用域内没有可检索的切片…越界即空" / "文档库里还没有切片…" / "没有检索到相关内容"） | `RetrievalService.java:221-226,240-244,302-309` |
| 10. 工具层整形 | 每条命中登记 `cite` 编号后返回；空结果给"换关键词"的 `hint` 并保留 `note` | `Tools.java:180-231` |

**关键参数真实值**

| 参数 | 值 | 位置 |
| --- | --- | --- |
| `RETRIEVAL_RECALL`（=送进 reranker 的候选数） | **50** | `application.yml:65`、`AiSettings.java:103` |
| `RETRIEVAL_RECALL_MODE` / `RETRIEVAL_RECALL_LOW` | **baseline** / 25 | `application.yml:69-71` |
| `RETRIEVAL_TOP_K` | **5**（工具侧硬上限 `HARD_MAX_TOP_K=10`） | `application.yml:72`、`RetrievalService.java:81,247` |
| `VEC_BACKEND` | **local**（README 写明"生产用 opensearch"） | `application.yml:74`、[`../ai-backend/README.md`](../ai-backend/README.md):42 |
| `OPENSEARCH_HYBRID` | **false** | `application.yml:91`、`AiSettings.java:155-165` |
| rerank 并发 / 超时 | 2 / 20s | `application.yml:97,103` |
| 每次检索重排对数 | `recall + recall/2` 去重后（recall=50 时 ≤75 对），**一次问答通常 2 次检索** | `RetrievalService.java:314-319` |

**逐项判定（主流召回能力对照，详见 §3.4）**

| 能力 | 判定 | 依据 |
| --- | --- | --- |
| 混合检索（向量 + 关键词） | **有** | **两路都在**：`kw = keywordScores(...)` 在 `:250` **无条件**执行，与向量榜融合（`:299-300`）。`OPENSEARCH_HYBRID=false` 只是**不额外启用平台 BM25 那一路**（`OpenSearchIndex.java:558-572`） |
| RRF / 加权融合 | **无** | 只有"向量优先、关键词补齐"的插入顺序（`:298-300`） |
| 元数据预过滤（先过滤再检索） | **有（接缝完备）** | 本地路径在打分前收窄（`:228-245`）；OpenSearch 路径下推成 `knn.vector.filter`（`OpenSearchIndex.java:536-545,607-638`） |
| 查询改写 / 多轮改写（history-aware） | **无** | 全仓无实现（grep `rewrite` / `改写` / `HyDE` 只命中文档与注释）。现有多查询**完全由模型驱动**：提示词建议"换 2~3 组不同关键词"（`Prompts.java:267-268`），`history` 只回放给模型、**不参与检索改写**（`QaService.java:303-312`） |
| HyDE / 多查询扩展（服务端） | **无** | 无实现 |
| 去重与多样性 | **部分** | 去重有（切片键含内容 sha1，`:293-296`）；**多样性无**（同页多切片会同时入选） |
| 重排（cross-encoder） | **有** | `VecClient.rerank`（`:264-299`），文档内写清"分数序 0.9903 > 0.0278 > 0.0014"已实测 |
| 上下文压缩 / 截断 | **有（截断式，非压缩）** | 命中 500 字、整页 4000 字；无摘要式压缩 |
| 缓存 | **部分** | 切片向量缓存有（`work/vectors`）；**query embedding 无缓存、rerank 结果无缓存**（既有结论 P2-1，见 §6） |
| 空结果与拒答路径 | **有** | 见上表第 9、10 步 + `Prompts.java:220,241-243` |
| 召回评估（recall@k / MRR / 命中率） | **无** | 只有端到端指标（`scripts/eval-ai.mjs:1301-1312`）；`eval/README.md:65` 明写"页码未标注 → 引用命中率恒为 n/a" |

**两处必须在文档里点名的代码级问题**

1. **查询侧全量 upsert（`RetrievalService.java:440-442`）**：`recallByOpenSearch` 里把"当前作用域的**所有**切片"组装成 `ChunkDoc` 并 `openSearchIndex.upsertAll(docs)`。这是**每次 `search()` 都执行**的写路径（`_id` 幂等，所以结果正确，代价巨大）：作用域为空时即**全库切片**。**估算（假设：15,000 切片、每切片 4096 维 JSON 38,333 字节——字节数取自《AI模型调用与任务队列评估》§3.1 真机读数）≈ 575 MB/次检索**，且 4 并发问答会各自做一遍。
2. **`hybrid=true` 会把两种量纲的分数塞进同一个 Map（`OpenSearchIndex.java:558-572` → `RetrievalService.java:299`）**：`search(vector, queryText, k, scope)` 的输出被当成 `vecScore`，随后 `topKeys(vecScore, recall)` **按分数降序**取候选（`:685-692`）——BM25 分数（通常 5~30）与 `cosinesimil` 分数（≈0~1）**直接比较**，BM25 会占满候选位。⇒ 配置注释里"不做归一"的说法与代码实际行为**不一致**：真实风险不是"排序不可解释"，而是"开启即成倍改变候选集"。**默认 false 是正确处置**，但**开启前必须先归一**。

### 2.5 生成

**提示词约束（`Prompts.java:204-277`，`QA_SYSTEM_PROMPT`）**

| 铁律 | 内容要点 | 代码位置 |
| --- | --- | --- |
| 1. 先查再答 | 回答文档问题前**必须**先 `search_documents`，禁止凭常识 | `:214-216` |
| 2. 只依据原文 | 原文没有就写「**文档中未找到**」，不得用外部知识补全 | `:218-221` |
| 3. 必须标注来源（`[cite]` 编号） | 句末写 `[cite]`；编号**只能取自工具返回**；不得自造/推算/跳号；同一页编号固定 | `:222-232` |
| 4. 金额与数字 | 原样引用不换算；求和/比例/差额**必须调 `calculate`**；可疑数字指出而非修正 | `:234-239` |
| 5. 不确定就说不确定 | 明说"现有检索结果中未包含…"，不给模糊答案 | `:241-243` |
| 6. 多文档不要混 | 不同文件分别引用，不拼成新结论 | `:245-246` |
| 节奏约束 | 工具调用总数建议 ≤5 次 | `:274-276` |
| 系统数据的口径与时间 | 转述 `query_business_data` 的数字必须**原话带** `caliber` + `data_time`；两类信息分开陈述；不得用 `calculate` 反推系统数字 | `:325-357`（`QA_BIZ_QUERY_PROMPT`） |
| 文档清单注入 | `doc_id / 文件名 / 页数` 表格注入 system（替代 `list_documents` 工具） | `:285-303` |

**工具集（`Tools.java`）**

| 工具 | 注册条件 | 关键参数/上限 | 依据 |
| --- | --- | --- | --- |
| `search_documents` | 始终 | `query` 必填；`top_k` 默认 5（硬上限 10）；`doc_id` 可选 | `Tools.java:167-231,586-599` |
| `read_page` | 始终 | 单页返回 ≤**4000 字**（超长截断并告知） | `Tools.java:81,238-282` |
| `calculate` | 始终 | 白名单递归下降解析器：**只认数字与 `+ - * / ( )`**（`**`/`//`/`%` 在词法层即拒）；`BigDecimal`；返回字符串 `result` + `rounded_2`；表达式 ≤200 字符 | `Tools.java:78-79,305-483` |
| `query_business_data` | **仅当请求带 `biz_query`** | `entity` 枚举 `projects/contracts/payments/stats`；`limit` 默认 20、上限 100；回调超时 20s；不传 `biz_query` 时**工具清单与旧版逐字节一致** | `Tools.java:84-92,508-537,627-665`、`QaService.java:297-299`、`application.yml:117-118` |

**多轮与历史**：`MAX_HISTORY_TURNS = 6`（`QaService.java:52`）；只回放 `user`/`assistant` **纯文本**（不带 `tool_calls`），截断到最近 6 轮（`:303-312`）；且**只允许一条 system 且在最前**（实测第二条 system 会 400，`:267-270`）。

**思考模式与采样**：`enableThinking=false`（实测开启后 4 分 55 秒且 content 为空，`ai-backend/README.md`+`AiSettings.java:53-54`）；`temperature=0`（`ToolAgent.java:226-227` 传 `null` → `LlmClient` 默认 `0.0`，`:144,169`）；关思考时按 vLLM 口径传 `chat_template_kwargs.enable_thinking=false`（`LlmClient.java:182-188`，且注释写明"服务端不支持该字段时附带它会 400，故只在 false 时传"）。

**上下文预算（这里是缺口）**

| 项 | 现状 | 代码依据 |
| --- | --- | --- |
| `LLM_MAX_INPUT_CHARS=20000` | **只作用于 `/analyze`**（按页截断 + 明确告知模型"你没看到全部"） | `application.yml:37`、`AiSettings.java:50`、`AnalyzeService.java:78,169-192` |
| `/chat` 的上下文预算 | **没有**：`QaService`/`ToolAgent` 只限轮数（8）与历史轮数（6），**对消息总长度无任何闸门** | `ToolAgent.java:48,224-267`（每轮 append assistant+tool 消息）；grep 证明 `getMaxInputChars` 全仓只有 `AnalyzeService` 与 `HealthController` 两个使用点 |
| `max-tokens` | 16384（输出侧） | `application.yml:38` |
| 工具轮数上限 | 8；到上限后**禁用工具再问一次**，要求"仅根据已获信息作答 + 仍标 `[cite]` + 没查到就写未找到" | `ToolAgent.java:48,269-289` |

**引用回填与幻觉防护**

| 项 | 现状 | 判定 |
| --- | --- | --- |
| 引用编号分配 | **在模型看到命中时即分配**（`Tools.hitMap` → `CitationRegistry.register`），按首次命中顺序 1,2,3…，只增不改，同页只占一个号 | ✅ 设计正确（`CitationRegistry.java:18-34,88-108`） |
| 引用回填响应 | `QaResult.toDict()` **追加** `citations`，既有键顺序与语义不动 | ✅ `QaService.java:130-143` |
| 答案里 `[n]` 的合法性校验 | **无**——没有任何代码把答案文本里的 `[n]` 与 `citations` 表比对 | ❌ 缺口（G7） |
| 答案数字可溯源校验 | **`/chat` 无**；`Checks.verifyNumbersInSource` 只在 `/analyze` 被调用 | ❌ 缺口（G7） |
| 确定性校验（金额大小写互校 / 比例合计 / 数值写法） | `/analyze` 与每次解析入库都会跑（`Checks.checkDocument`，结果进 `checks`/`review_pages`） | ✅ 但对 `/chat` 答案无用 |

**失败与降级口径**

| 场景 | 行为 | 依据 |
| --- | --- | --- |
| 问题为空 | 直接回"请先输入问题"，`stopped_reason=done`，`error=问题为空` | `QaService.java:194-204` |
| 无文档且无 `biz_query` | 回固定提示"还没有可问答的文档"，不跑模型 | `QaService.java:54-56,206-209` |
| 无文档但有 `biz_query` | **照常跑问答**（业务事实不来自文档） | `QaService.java:172-178,206-209` |
| 模型报错 | 答案替换为"问答失败 + 排查三选" ；`llm.ok=false`、`stopped_reason=error` | `QaService.java:226-238` |
| 达到轮数上限 | 答案提示"已达到工具调用轮数上限"+ 建议；`stopped_reason=max_rounds` | `QaService.java:232-234` |
| 空输出 | 回 `Prompts.EMPTY_OUTPUT_HINT`（三条可能原因） | `QaService.java:236`、`Prompts.java:184-188` |
| 模型繁忙 | 每模型信号量 `chat=4`，等 permit 1.5s 超时 → 明确"模型繁忙" | `application.yml:101,106`、`ModelRegistry.java:217-247` |
| chat 重试 | **永不重试**（不幂等，避免重复消耗 4 个副本并让用户再等一轮） | `LlmClient.java:208-211` |

### 2.6 横切

| 关注点 | 现状 | 代码依据 |
| --- | --- | --- |
| **可观测性** | micrometer + actuator 已接（`spring-boot-starter-actuator`，`pom.xml:58-61`）：每模型 `requests/latency(P50/P95/P99)/inflight/queue.depth/rejected/retries`；`upload.queue.depth/inflight/rejected`；`ai.vector.embedded/indexed/lazy_backfill`；`ai.retrieval.rerank.pairs`；`ai.retrieval.scope.filtered`；`ai.opensearch.retries`；聚合视图 `/health?with_metrics=true` | `ModelRegistry.java:107-141,307-345`、`VecClient.java:90`、`UploadTaskService.java:83-88`、`HealthController.java:89-95`、[`../ai-backend/README.md`](../ai-backend/README.md):127-148 |
| 可观测性的**空白** | 无检索质量/答案质量信号（零命中率、空引用率、重排降级次数、拒答率）；无 traceId（日志 pattern 注释写"带请求/任务上下文"，实际格式只有时间/级别/线程/logger/msg） | `application.yml:139-141` |
| **权限与作用域** | 作用域硬过滤的**接口与两条后端下推都已实现**（`SearchScope` + kNN filter）；`/documents/{id}/meta` 提供元数据补录入口 | `SearchScope.java`、`OpenSearchIndex.java:607-638`、`ai-backend/README.md`:117 |
| 权限的**已知缺口** | ① `/chat` 请求级 `doc_ids` **未下推**（G3）；② AI 服务**无任何入站鉴权**（仅靠网络隔离，主系统发了 `Bearer` 但对方不校验） | `RetrievalService.java:200-202`、`ai-backend/README.md`:104-106 |
| **多租户 / 密级隔离** | `DocMeta` 有 `security_level` 字段并写入索引（`security_level` keyword）；但 `SearchScope` **只有 `docIds/attachmentIds/projectIds` 三个维度**——密级**不能**作为作用域维度参与过滤 | `DocMeta.java:24`、`OpenSearchIndex.java:222`、`SearchScope.java:23,57-66` |
| **增量与重建** | `/admin/reindex`：文档级（整份已索引即跳过）+ 切片级（按 `chunk_key` 跳过，`_id` 含内容指纹）双粒度断点；可取消（文档边界）、有进度/ETA；`VEC_BACKEND=opensearch` 且集群不可达时**启动即 503** | `ReindexService.java:29-54`、`DocIndexer.java:101-122`、`OpenSearchIndex.java:452-474`、[`../ai-backend/README.md`](../ai-backend/README.md):164-166 |
| **幂等与断点** | 重建可续跑；**上传解析不可续跑**（重启把 QUEUED/PARSING 一律标 FAILED）；**重解析不幂等**（新 doc_id、旧数据不清理，G10） | `UploadTaskService.java:118-132`、`DocStore.java:90-100,147-154` |
| **容量水位与背压** | 有：每模型信号量 + 交互/批量两档 permit 等待时长；解析队列 100 且有界拒绝（429 + 标 FAILED + 清临时文件，无永久 QUEUED） | `application.yml:101-107`、`UploadTaskService.java:145-174` |
| **评测资产** | `eval/smoke-30.json`：**35 题**（16 结构化 / 9 文档 / 4 混合 / 4 负例 / 2 越权）；`scripts/eval-ai.mjs` 报数字一致率、引用命中率、工具选择正确率、拒答正确率、口径转述率、引用覆盖率、两条路径口径一致率 | `eval/smoke-30.json`、`scripts/eval-ai.mjs:1301-1312` |
| 评测的**空白** | 9 道文档题 `citation_pages=null` → 引用命中率恒 `n/a`；**无 recall@k / MRR**；无自动 LLM 评委；无幻觉率指标 | `eval/README.md:65,202,213`、《AI模型调用与任务队列评估》§3.5 末段 |

### 2.7 与既有文档的边界（明确"本文不重复什么"）

- **不重复**：《AI工具集与检索编排评估》已论证"3 个工具一个不能删""暂不引 LangChain4j""检索该改服务端预取"——本文只把它们作为**触发条件**引用（§5.2、§6）。
- **不重复**：《AI模型调用与任务队列评估》已量化"0.16 QPS 瓶颈在重排""何时必须换 OpenSearch""队列方案"——本文引用其数字，**不重算**。
- **新增（本文首次登记）**：①「无独立清洗阶段」的逐条事实；②「查询侧全量 `_bulk`」这一代码级问题；③「混合型 PDF 无逐页回退」；④「`/chat` 无上下文预算 + 答案侧无机器校验」；⑤「`hybrid=true` 的分数混量纲实际后果」；⑥「密级不是作用域维度」；⑦ 五环节评级与逐环节主流对照表。

---

## 3. 与主流企业实践对照表（本文核心）

> 判定口径：**有**＝代码里能指出实现且行为完整；**部分**＝有实现但覆盖不全/依赖外部；**无**＝无实现；**不适用**＝给出理由说明为什么本项目不需要。
> 优先级：**P0**＝不做会出错或挡住后续；**P1**＝明显影响质量/成本/可运维；**P2**＝有收益但可排后。

### 3.1 解析

| 能力项 | 主流企业做法（要点） | 判定 | 本项目现状（代码依据） | 差距 | 影响 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| 多格式（DOCX/XLSX/PPTX/图片） | ① 统一解析层（Tika/POI/专用库）；② 每格式一条 reader 接在同一个接口后；③ 格式白名单与上传口对齐；④ 不支持格式明确报错并可查询 | **部分** | 只支持 PDF + 7 种图片，其余 `kind=error`（`DocumentReader.java:69-70,113-116`）；主系统白名单含 docx/xlsx/md（前端能预览） | 格式覆盖与上传口不一致 | docx/xlsx 附件解析必然失败；用户以为"AI 不支持这个功能" | **P1** |
| 版面重建 | ① 按区域类型（标题/正文/表/图/页眉）保留结构；② 区域顺序还原阅读顺序；③ 表格区域单独抽；④ 版面结果可回放 | **部分** | 平台返回 `blocks[{label,content,bbox}]`，代码**只留 label+bbox** 做质量信号，正文直接用 `markdown`（`PlatformOcrClient.java:468-503`、`StoredDoc.java:354-356`） | 区域内容全部丢弃，阅读顺序与区域类型不可用 | 表格/多栏页的正文顺序依赖平台 markdown；无法做"表格整块"处理 | **P2** |
| 表格转结构化（HTML/Markdown 表） | ① 表格区域 → HTML/Markdown；② 保留表头与行列关系；③ 表格不参与普通切片 | **部分** | 不做转换；仅在页头标注"含表格 N 个"（`PageText.java:102-104,114-128`）；能否拿到 markdown 表**取决于平台**（§7 未确认 1） | 无表格结构化保证 | 表格类问题（验收标准、付款节点）易答错 | **P1** |
| 公式 / 图表 | ① 公式转 LaTeX；② 图表转文字描述或跳过；③ 审计类文档通常只需"存在性"提示 | **无** | 代码不区分这些 label | 公式/图表内容不进检索 | 对政务合同类文档影响有限（**不适用**为主：本项目文档以条款/金额/表格为主） | **P2** |
| 扫描件与文本层混合文档 | ① **逐页**判定；② 空/稀疏页单独走 OCR 回填；③ 合并后标注每页来源 | **无（按整份判定）** | 判据是整份平均字符 <50（`DocumentReader.java:107-111`）；文本层路径**不**对空页补 OCR（`:121-139`），空页只进 `empty_pages` | 混排 PDF 静默丢页 | 答案"看起来完整"但缺内容——**审计场景最危险的一类** | **P0** |
| 页级溯源 | ① 每个 chunk 带页码；② 引用可跳原文页；③ 页内可定位到区域/片段 | **有（片区段级待补）** | 逐页文本 + `【第 N 页】` + `chunk.pageNo` + `citations.page_no` 全链可查（§2.1 表） | 引用只到"页"，超长页无法定位片段 | 4000 字以上的页，人工核对要翻整页 | **P2** |
| 解析质量抽检 | ① 固定样本集 + 字段级基线；② 改动解析后跑差分；③ 页级质量分与人工抽检闭环 | **无** | 只有 `review_pages()` 人工复核优先级（`StoredDoc.java:228-251`）；`PdfReader.debugSample` 是手工工具 | 无基线即无法判断"改解析有没有变差" | 解析相关改动只能靠感官 | **P1** |

### 3.2 清洗

| 能力项 | 主流企业做法（要点） | 判定 | 本项目现状（代码依据） | 差距 | 影响 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| 页眉 / 页脚 / 页码行剔除 | ① 跨页重复行检测（同位置/同文本高频）；② 页码正则；③ 白名单保留（如"第 X 页 共 Y 页"里的业务信息） | **无** | 无实现（§2.2） | 整段缺失 | 每页噪声进切片 → 向量与关键词两路都被污染；同一噪声在多个切片里抬高 df，压低真正稀有词的 IDF 权重（`RetrievalService.java:661`） | **P0** |
| 水印剔除 | ① 检测高频重复短语；② OCR 侧请求参数抑制；③ 白名单 | **无** | 无实现；平台 markdown 原样入库 | 整段缺失 | 水印文字可能被当成正文引用（合规风险） | **P1** |
| 断行合并 / 连字符修复 | ① 行尾无标点且下一行小写 → 合并；② 连字符去断行；③ 段落重排 | **无** | 仅在切片时按"空行/换行"分段（`DocStore.java:214-229`），不合并 | 整段缺失 | OCR 出的硬换行会让"完整句子"被切成两段，语义向量质量下降 | **P1** |
| 表格与正文分离 | ① 表格区域独立成块；② 正文与表格分别切片；③ 表格标记不参与正文语义 | **无** | 只有页头标记（`PageText.java:114-128`） | 整段缺失 | 表格行与正文混在同一 500 字块里，检索精度下降 | **P1** |
| 去重（段落 / 文档级） | ① 段级 simhash/MinHash；② 文档级内容指纹避免重复入库；③ 索引侧 `_id` 去重 | **无** | `DocStore.save` 每次新 UUID（`DocStore.java:90-100`）；无内容指纹 | 整段缺失 | 重解析产生重复切片占候选位、`work/` 无界增长 | **P1** |
| 编码与空白归一 | ① 统一 UTF-8；② 全角/半角归一；③ 空白与不可见字符清理 | **部分** | 仅 `\r\n`→`\n`（PDF 文本层路径，`PdfReader.java:229-234`）+ 空白页归一（`:225`）+ 切片 `strip()`（`DocStore.java:209,216,224`） | 只做了一半且**只在文本层路径** | OCR 路径的 `\r`/全角空白会进切片与提示词 | **P2** |
| 敏感信息处理 | ① 身份证/手机号/账号掩码；② 分级可配置；③ 审计留痕 | **无** | 无实现；`_eval` 里有"不得返回个人敏感信息"的负例题（`N-001-003`），但那是**提示词级**约束 | 整段缺失 | 与"审计可追溯"存在张力：要么脱敏要么全量可见，需明确策略 | **P2** |

### 3.3 切分

| 能力项 | 主流企业做法（要点） | 判定 | 本项目现状（代码依据） | 差距 | 影响 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| 语义 / 结构感知切分 | ① 按标题层级（H1/H2）建树；② 按段落/条款切；③ 递归分隔符优先（段落→句→字） | **部分** | 段落/行粒度累积（`DocStore.java:214-229`），**无标题层级**、无"句子级兜底切分"（超长段落整体成块，`:237-242`） | 无结构感知 | 条款被跨段拼进同一块，检索到的片段可能缺前提条件 | **P1** |
| 父子块（small-to-big） | ① 子块检索、父块（整节/整页）进上下文；② 命中自动上卷；③ 减少 `read_page` 往返 | **无** | 无实现；`read_page` 是"再花一次工具往返读整页"（`Tools.java:253-282`），实测轨迹里确实出现了它 | 整段缺失 | 每次追问多一轮模型往返（延迟与 token 成本）；片段语义不完整导致"存疑"结论 | **P1** |
| 重叠窗口 | ① 10%~20% 重叠；② 或按句子边界回退若干字 | **无** | `buf.setLength(0)` 直接清空（`DocStore.java:236`） | 整段缺失 | 边界处的关键句只落在后一块的"前半句"，前一块检索到时信息不全 | **P1** |
| 表格与长句不截断 | ① 表格整块成切片；② 超长段落按句切；③ 关键行不拆 | **无** | 500 字硬累积，表格可被切断；超长段落反而是单块（`DocStore.java:233-242`） | 两类极端都不处理 | 表格类问题的召回与答案质量直接受损 | **P0** |
| 切片元数据（项目/密级/时间/来源） | ① 切片同时落"可过滤字段"与"可展示字段"；② 元数据与权限口径同源 | **部分** | 切片本体只有 `docId/filename/pageNo/text`（`DocStore.java:187`）；项目/密级/时间在 side-car `DocMeta`（`DocMetaStore.java`），写 OpenSearch 时下推到文档字段 | 切片层不可直接过滤；元数据需人工补录（`PUT /documents/{id}/meta`） | 元数据缺失时"带约束即越界"（安全侧正确，但会导致**该文档永远搜不到**——见 `RetrievalService.java:241-244` 的文案） | **P1** |
| 切片质量抽检与可视化 | ① 切片列表可浏览/编辑；② 切片边界可视化；③ 抽样人工评分 | **无** | `/ai` 页只有文档库/任务队列/服务自检；无切片视图（`AI前端与集成方案.md` §8.3 明确"切片在线编辑"不做） | 无抽检手段 | 切片改动无法肉眼验证 | **P2** |
| 切片大小与模型上限对齐 | ① 按 embedding 上限留余量；② 按生成预算定 top_k×chunk | **部分** | 500 字**不是**被上限逼出来的（远未触顶）；但生成侧预算未接（G6），所以"500×5"这个组合没有在预算约束下校验过 | 缺少"预算→top_k×chunk"的推导 | 见 G6 | **P1** |

### 3.4 索引与召回

| 能力项 | 主流企业做法（要点） | 判定 | 本项目现状（代码依据） | 差距 | 影响 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| 向量 + 关键词混合检索 | ① 两路并行；② 融合排序；③ 可按 query 类型调权重 | **有** | 两路都在（`RetrievalService.java:250,255-300`）；OpenSearch 路径另有可选 BM25（`OPENSEARCH_HYBRID=false`） | 无 | — | — |
| RRF / 加权融合 | ① RRF（`1/(k+rank)`）无需归一最常用；② 或各自归一后加权；③ 融合口径固定可复现 | **无** | 只有"向量优先、关键词补齐"的插入顺序（`:298-300`）；**开启 hybrid 会混量纲**（`OpenSearchIndex.java:558-572` + `RetrievalService.java:299,685-692`） | 无归一/无 RRF | 候选集选择不可解释；hybrid 一旦开启会被 BM25 量纲主导 | **P1** |
| 查询改写 / 多轮改写（history-aware） | ① 用历史把"它/这个项目"补全成独立 query；② 多查询扩展（同义/子问题）；③ 改写结果留痕 | **无** | 无实现；多查询**完全靠模型自觉**（`Prompts.java:267-268`），`history` 不参与检索（`QaService.java:303-312`） | 整段缺失 | 追问质量依赖模型第一轮措辞；检索不可复现（同一问题不同措辞不同召回） | **P1** |
| HyDE / 多查询扩展（服务端） | ① 先让模型写假想答案再检索；② 或多路 query 并行召回后融合 | **无** | 无实现 | 整段缺失 | 短问题/术语问题召回偏低 | **P2** |
| 元数据预过滤（先过滤再检索） | ① 过滤下推到检索引擎；② 缺失元数据**不**通配；③ 越界即空且可解释 | **有（接缝完备，未接线到请求）** | 本地在打分前收窄（`:228-245`）；kNN 下推 `filter`（`OpenSearchIndex.java:536-545`）；缺失即越界（`SearchScope.java:57-66`）；**但请求级 doc_ids 未下推（G3）** | 请求作用域未接线 | 软约束 = 全库检索；叠加无入站鉴权 ⇒ 网络可达即读全库 | **P0** |
| 去重与多样性 | ① 内容指纹去重；② MMR/同页限流提升多样性 | **部分** | 去重有（切片键含 sha1，`:293-296`）；多样性无 | 无多样性控制 | 同一页多个切片可能占满 top_k，跨文档对比类问题缺素材 | **P2** |
| 重排（cross-encoder） | ① cross-encoder 精排；② 候选 20~100；③ 分数与降级可见 | **有** | `Qwen3-VL-Reranker-8B`，候选 `recall + recall/2`（≤75），取 top_k；local 路径降级可见（`note`） | 单实例吞吐是瓶颈 | 问答上限 ≈0.16 QPS（实测口径见《AI模型调用与任务队列评估》§3.3） | **P1** |
| 上下文压缩 | ① 摘要式压缩；② 按相关性裁剪；③ 保留可溯源片段 | **部分（只截断）** | 命中 500 字、整页 4000 字（`RetrievalService.java:363-365`、`Tools.java:273-275`），无摘要压缩 | 无压缩 | 长条款页仍要 `read_page` 多花一轮 | **P2** |
| 缓存 | ① query embedding 缓存；② rerank 结果缓存；③ 答案缓存 | **部分** | 切片向量缓存有（`work/vectors`）；query embedding 与 rerank 结果**无缓存** | 缺两类缓存 | 重复提问重复付 embed+rerank（rerank 是当前最贵的单步） | **P1** |
| 召回评估（recall@k / MRR / 命中率） | ① 标注"该命中哪些 doc:page"的离线集；② 每次改切片/召回/参数都跑；③ 门槛化 | **无** | 只有端到端指标（`scripts/eval-ai.mjs:1301-1312`）；9 道文档题页码未标注 → 引用命中率 `n/a`（`eval/README.md:65`） | 整段缺失 | **所有后续质量改动都无法判定优劣** | **P0** |

### 3.5 生成

| 能力项 | 主流企业做法（要点） | 判定 | 本项目现状（代码依据） | 差距 | 影响 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| 忠实性与引用校验 | ① 答案句与来源句做蕴含/相似度校验；② 引用编号越界即拒/标红；③ 关键字段（金额/日期）逐字回查原文 | **部分** | 提示词要求极严（`Prompts.java:222-239`）；**但代码层只做了"给编号"**，没有把答案里的 `[n]` 与 `citations` 表比对，也没有数字回查 | 校验只到"半程" | 幻觉只能靠提示词概率性避免 | **P0** |
| 拒答策略（无依据必须拒答） | ① 检索为空/低分 → 强制拒答；② 拒答与"系统错误"话术区分；③ 拒答率作为指标 | **部分** | 提示词要求"文档中未找到"；空结果给 `hint`（`Tools.java:192-202`）；**无相似度阈值门**（低分命中照样进上下文） | 无阈值门、无在线拒答率指标 | 低分噪声可能被当成依据 | **P1** |
| 多轮一致 | ① 指代消解；② 会话级引用编号稳定；③ 上下文预算内回放 | **部分** | 6 轮纯文本历史（`QaService.java:52,303-312`）；引用编号**请求级**、不跨轮（`CitationRegistry` 每次新建） | 无指代消解、编号不跨轮 | 追问"这个金额呢"时编号会重新分配，用户看到的前后编号不一致 | **P1** |
| 结构化输出 | ① JSON schema 约束；② 字段级校验与重试；③ 前端按结构渲染 | **部分** | 答案本体是自由 markdown；**结构化的只有 `citations` 与 `trace`**（`QaService.java:130-143`、`ToolAgent.java:70-79`） | 答案本体无结构 | 前端难以做字段级核对（当前靠人读 markdown） | **P2** |
| 答案质量评估（人工 + LLM 评委） | ① 离线 LLM 评委打分（忠实/相关/完整）；② 人工抽检；③ 👍/👎 回流 | **无** | 无 LLM 评委；`AI前端与集成方案.md` §8.3 把 👍/👎 列为 P2 未做 | 整段缺失 | 质量只能靠题集通过率这一个粗信号 | **P1** |
| 幻觉度量 | ① 数字幻觉率；② 无据断言率；③ 引用错配率 | **部分** | `eval-ai.mjs` 用"答案数字 vs 页面接口真值"报**数字一致率**（`scripts/eval-ai.mjs:1302`）——是幻觉度量的一个**侧面**；无据断言/引用错配无度量 | 覆盖不全 | 幻觉只能看一个维度 | **P1** |
| 成本与延迟预算 | ① 每问答 token/耗时上限；② 超预算降级（减轮/减 top_k）；③ 按请求类型分档 | **部分** | 有输出上限 16384、超时链（chat 150s）、每模型并发闸门；**无"单次问答预算"**（G6） | 缺输入侧预算 | 长文档 + 8 轮循环下 prefill 成本不可控 | **P1** |
| 提示词版本管理 | ① 提示词独立文件 + 版本号；② 变更走评测回归；③ 线上可回滚 | **部分** | 提示词集中在 `Prompts.java`（"一处修改处处生效"），有"逐字照搬 Python"的纪律，但**无版本号、无回归门禁** | 无版本化 | 改提示词无法回答"这次改动到底好了还是差了" | **P2** |
| A/B 与回归（评测集驱动） | ① 固定题集 + 基线数字；② 每次改动跑回归；③ 门槛不过不放行 | **部分** | 题集与脚本已就绪（35 题）；应用层**没有** CI/回归门禁，靠人工跑 `node scripts/eval-ai.mjs` | 无自动化回归 | 改动的质量影响靠人记 | **P1** |

### 3.6 横切

| 能力项 | 主流企业做法（要点） | 判定 | 本项目现状（代码依据） | 差距 | 影响 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| 可观测性（速度/容量） | ① 每模型 QPS/延迟分位/错误率/在途；② 队列深度；③ 依赖健康 | **有** | `ModelRegistry` 六项 + 上传队列 3 项 + 向量化 4 项 + OpenSearch 重试；`/health?with_metrics=true` 聚合（§2.6） | 无 | — | — |
| 可观测性（质量） | ① 零命中率；② 引用覆盖率；③ 降级次数；④ 拒答率；⑤ 端到端 trace | **无** | 无这些计数器；日志无 traceId（`application.yml:139-141`） | 整段缺失 | 线上"答得差了"无法从监控发现 | **P1** |
| 权限与作用域 | ① 服务间鉴权；② 请求作用域硬下推；③ 越权即空且可审计 | **部分** | 接口与下推都在（§2.6），但 **AI 服务无入站鉴权**、**请求 `doc_ids` 未下推** | 两处缺口 | 安全/合规缺口（越权可见、网络可达即全库） | **P0** |
| 多租户 / 密级隔离 | ① 密级作为过滤维度；② 先过滤再检索；③ 越级即空 | **部分** | `security_level` 有字段、有 mapping（`OpenSearchIndex.java:222`），但 **`SearchScope` 无密级维度**（`SearchScope.java:23`） | 密级不可作为作用域 | 密级隔离只能靠"项目级"近似，做不到"同项目内按密级" | **P1** |
| 增量与重建 | ① 幂等幂等幂等；② 可中断续跑；③ 重建有进度与取消 | **有** | `/admin/reindex` 双粒度断点 + 进度/ETA + 协作式取消（`ReindexService.java:29-54`、`DocIndexer.java:101-122`） | — | — | — |
| 幂等与断点（解析侧） | ① 内容指纹去重；② 任务可重放；③ 失败可续 | **无** | 重解析每次新 doc_id（`DocStore.java:90-100`）；重启把在跑任务标 FAILED（`UploadTaskService.java:118-132`） | 整段缺失 | 重复切片 + `work/` 无界增长 + 重启后任务丢失 | **P1** |
| 容量水位 | ① 明确的准入与拒绝语义；② 水位告警；③ 容量页 | **部分** | 有信号量 + 429/503 明确拒绝；**无水位告警阈值**（指标有，告警规则不在本仓库） | 无告警规则 | 容量问题要靠人看指标 | **P2** |

---

## 4. 缺口清单（现象 → 影响 → 建议做法 → 工作量级 → 优先级）

> 工作量级一律为 **估算（假设：熟悉本仓库的 1 名后端工程师、含自测与文档同步、不含跨团队协调）**。凡引用既有结论的条目在"建议做法"里注明。

| # | 现象（代码依据） | 影响（质量/吞吐/成本/可运维） | 建议做法 | 工作量级 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| G1 | **无独立清洗阶段**：全仓无页眉页脚/水印/页码行/断行/去重实现（§2.2） | 质量（两路召回都被噪声污染）、成本（无谓 token） | 新增 `module/clean/TextCleaner`，插在 `DocumentReader.read` 与 `DocStore.save` 之间；**第一期只做四件低风险项**：① 跨页重复行检测（同文本在 ≥50% 页出现 → 判为页眉页脚）；② 纯页码行正则剔除；③ 空白与不可见字符归一（含 `\r`）；④ 行尾无标点 + 下一行小写 → 合并。**同时落盘"清洗前后对照"**（`work/clean/<docId>.json`）供抽检，并加 `ai.clean.removed_lines` 计数器 | **3~5 人日** | **P0** |
| G2 | **OpenSearch 查询侧全量 `_bulk`**：`RetrievalService.java:440-442` 每次 `search()` 把作用域内**全部**切片写回索引 | 成本（**估算（假设：15,000 切片、每切片 JSON 38,333 字节）≈575 MB/问**）、吞吐（写放大挤占集群与网络）、可运维（集群写入量随问答量线性增长） | 删除查询侧 `upsertAll`；入库/重建路径已负责写索引（`DocIndexer.java:163`）。若仍需"补齐缺失切片"，改为**显式异常路径**：先查 `existingChunkKeys`（`OpenSearchIndex.java:452-474`），只对缺失者写入，并计 `ai.vector.lazy_backfill` + WARN（与既有"正常应为 0"口径一致） | **0.5~1 人日** | **P0** |
| G3 | **请求级 `doc_ids` 未下推**：`QaService.java:206` 只进提示词；`Tools.java:183` 只用模型给的 `docId`；`RetrievalService.java:200-202` 自认未做 | 安全/合规（越权可见）、质量（候选位被作用域外内容占用） | `QaService` 把 `docIds` 收窄成 `SearchScope`，经 `ToolAgent` 传给 `Tools.searchDocuments`，与模型给的 `docId` **取交集**；`SearchPort.searchInScope` 已就绪，改动集中在签名与调用点。验收：不传 `doc_id` 的检索**只能**命中请求作用域内切片（补一条测试钉住"越界即空"） | **1~2 人日** | **P0** |
| G4 | **混合型 PDF 无逐页回退**：`DocumentReader.java:107-111` 整份二分；文本层路径不回填空页（`:121-139`） | 质量（**静默丢页**，答案看似完整） | 文本层路径结束后，对 `emptyPages()` 且 `pageCount>1` 的页**单独渲染 + 平台 OCR 回填**，并在 `notes` 写"第 N 页由 OCR 补识别"；页级 `source` 字段已存在，逐页标注来源即可 | **2~4 人日** | **P0** |
| G5 | **非 PDF/图片格式不支持**：`DocumentReader.java:69-70,113-116` | 可用性（docx/xlsx 附件解析必失败） | 在 `DocumentReader` 后新增"电子文档读取器"分支（PDFBox 之外的 docx/xlsx 用 POI，纯文本直读），产出同样的 `DocumentText`（逐页/逐段 + 页码占位）；**只新增分支，不动已验证的 PDF 路径**（既有结论：新格式只接在 `DocumentReader` 接口后） | **3~6 人日** | **P1** |
| G6 | **`/chat` 无上下文预算**：`LLM_MAX_INPUT_CHARS` 只在 `AnalyzeService.java:78` 被使用 | 成本（prefill 随轮数膨胀）、可运维（超时风险不可控） | 引入"单次问答消息预算"（按字符估算即可，与既有 20000 口径分离）：① 超预算时先裁历史 tool 结果、再减轮数；② 裁掉的内容写进答案尾部说明（与 `/analyze` 的截断口径一致：**必须告诉模型与用户"你没看到全部"**）；③ 记 `ai.chat.context.truncated` | **2~3 人日** | **P0** |
| G7 | **`/chat` 答案侧无机器校验**：`Checks.verifyNumbersInSource` 只被 `AnalyzeService.java:146` 调用；答案 `[n]` 无合法性校验 | 质量（幻觉只靠提示词）、可审计（无法自动指出"这句话没出处"） | ① 把答案里的 `[n]` 与 `citations` 表比对，越界/未登记的编号计数并回传（不改写答案）；② 复用 `Checks.verifyNumbersInSource` 对 `/chat` 答案跑"数字可溯源"，结果作为响应字段（如 `verification`）与指标 | **2~3 人日** | **P0** |
| G8 | **检索级评估缺失**：`scripts/eval-ai.mjs` 只有端到端指标；9 道文档题 `citation_pages=null`（`eval/README.md:65`） | 可运维/质量（**所有后续切片与召回改动无判据**） | ① 人工补 9 道文档题的 `citation_pages`（需能打开原 PDF 的人参与）；② 扩题面时同步标注"应命中的 `doc_id:page` 集合"；③ 新增离线评测入口（直接对 `RetrievalService` 跑 recall@5 / MRR / top-1 命中率），**不进 `mvn test`**，与 `TopKCompareTool` 同一形态 | **3~5 人日** | **P0** |
| G9 | **无质量类在线指标**：micrometer 只覆盖速度/容量（§2.6） | 可运维（线上劣化不可见） | 加四个计数器：`ai.retrieval.empty`（零命中）、`ai.chat.citations.empty`（空引用答案）、`ai.retrieval.rerank.degraded`（降级次数）、`ai.chat.cite.invalid`（答案里的非法编号，配合 G7） | **1~2 人日** | **P1** |
| G10 | **重解析不幂等、旧数据不清理**：`DocStore.java:90-100,147-154` | 成本（`work/` 无界增长）、质量（重复切片占候选位） | 由主系统在触发解析时带上 `content_hash`（或 AI 侧对上传字节算 sha1）；同 hash 且已 DONE 默认复用（`force=true` 才重跑）；重解析成功后删除旧 doc 与 `work/vectors/<old>.json`，并调 `OpenSearchIndex.deleteDoc`（`:500-505` 已实现） | **2~4 人日** | **P1** |
| G11 | **表格可被 500 字边界切断、超长段落整块成片**：`DocStore.java:233-242` | 质量（表格类问题召回/答案受损） | 切片前先做"表格块识别"（平台 `blocks[label=table]` 的 bbox 范围内文本，或 markdown 表结构正则）；表格块**整块成切片**（超长则按行切且**每片重复表头**）；超长段落按句号/分号二次切 | **2~4 人日** | **P0** |
| G12 | **无重叠、无父子块**：`DocStore.java:236`；`Tools.java:253-282` | 质量（边界句信息不全）、延迟（`read_page` 多一轮） | ① 加 10%~15% 重叠（按句回退）；② 引入"父块"（同页或同段落组）作为 `cite` 的上卷目标，命中子块时把父块摘要一并给模型（**先做"同页邻块"这个最简版本**，与《AI工具集与检索编排评估》P1"命中窗口扩张"同向，不重复投资） | **3~5 人日** | **P1** |
| G13 | **融合无归一/无 RRF，`hybrid=true` 会混量纲**：`RetrievalService.java:299,685-692` + `OpenSearchIndex.java:558-572` | 质量（候选集不可解释）、可运维（开关不能安全打开） | 融合改为 **RRF**（对 rank 融合，天然免归一），或先各自 min-max 归一再加权；`OPENSEARCH_HYBRID` 打开前必须先落地归一 | **2~3 人日** | **P1** |
| G14 | **无查询改写 / 无 history-aware 改写** | 质量（追问与短问题的召回） | 在检索前加一步"轻量改写"：把历史最后一轮 + 当前问题交给 chat 模型产出一个独立 query（成本：一次短调用），**改写结果写入 trace** 以保证可复现与可审计。**前置：G8 的评测基线** | **3~5 人日** | **P1** |
| G15 | **无 query embedding / rerank 结果缓存**（既有结论 P2-1，此处只登记优先级） | 成本（重复提问重复付最贵的一步） | ① query embedding LRU（按 query 文本哈希）；② rerank 结果按 `(query, 切片键集合, top_k)` 短时缓存（TTL 5~10 分钟） | **2~3 人日** | **P1** |
| G16 | **无解析质量抽检基线** | 可运维（解析改动无法验证） | 建 20~30 份样例的解析基线（页数/字符数/关键字段/页级空页数），改动解析后跑差分报告（形态与 `--write-truth` 的漂移快照一致） | **3~5 人日** | **P1** |
| G17 | **密级不是作用域维度**：`SearchScope.java:23` 只有三维 | 合规（做不到"同项目内按密级过滤"） | `SearchScope` 增加 `securityLevels` 维度，本地路径与 kNN filter 各加一条 terms 子句（`OpenSearchIndex.java:619-638` 已是通用写法） | **1~2 人日** | **P1** |
| G18 | **无指代消解、引用编号不跨轮**（`CitationRegistry` 每请求新建） | 质量（多轮一致性） | ① 多轮编号策略改为"会话内复用"（需把注册表提到会话级，与"AI 服务无状态"边界冲突，须先定会话表 → 属 P2）；② 短期只改提示词：要求模型在追问里**同时写文件名+页码** | **1 人日（提示词）+ 3~5 人日（会话级编号，依赖主系统会话表）** | **P2** |
| G19 | **答案本体无结构化输出** | 质量（难做字段级核对） | 见 §6 推迟项：审计场景的答案要"给人读"，结构化收益低于成本 | — | **P2** |
| G20 | **无 LLM 评委 / 无点赞回流** | 质量（评测扩张速度慢） | ① `AI前端与集成方案.md` §8.3 的 👍/👎 回流（前端已规划未做）；② 离线 LLM 评委（用同一 chat 模型按"忠实/相关/完整"三档打分） | **3~5 人日** | **P1** |
| G21 | **AI 服务无入站鉴权**（`ai-backend/README.md:104-106`） | 安全（网络可达即可读全库） | 服务间令牌校验（主系统已发 `Bearer`，AI 侧只需校验）；令牌只从环境变量读，不进日志 | **1~2 人日** | **P0** |
| G22 | **解析格式上限与上传口不一致**（G5 的另一面） | 可用性/可运维（用户看到"AI 不支持"） | 短期：在附件页按后缀给"该格式暂不支持 AI 解析"的明确标签（主系统侧改动，不在 `ai-backend`）；长期：随 G5 扩格式 | **1 人日（标签）+ G5** | **P1** |

---

## 5. 技术栈评判（合理 / 勉强 / 该换）

### 5.1 逐项判定

| 项 | 判定 | 理由（依据） | 什么时候必须动 |
| --- | --- | --- | --- |
| Spring Boot 3.3.5 + JDK 17 | **合理** | 与主系统同栈（`pom.xml:14-19`）；AI 侧要的只是 REST + 线程池 + 信号量 + 指标，全部是标准件；129 例 JUnit 已成回归资产 | 不需要动 |
| JDK `HttpClient` + Jackson 原生 REST（AI 侧） | **合理** | 只有 5 个 OpenSearch REST 动作（`OpenSearchIndex.java:35-46` 写明理由）与 4 类模型调用；"查询体是给人看的 JSON"对排障与文档对照有直接价值 | 动作数超过 ~15 个、或需要连接池/重试策略统一治理时，再抽一层薄封装（**不是**引 SDK） |
| 主系统 `RestClient`（Spring 6.1）+ 双超时 | **合理** | `AiServiceClient` 用 chat/fast 两个 `RestClient` 分离 180s/10s 预算（`AiHttpClientConfig`）；接口边界清晰 | 出现第二个 AI 消费方时再抽公共客户端 |
| 无 LangChain4j / 无 Spring AI | **合理** | 既有结论（《AI工具集与检索编排评估》§2.3/§7）：它的强项（多厂商路由、记忆、Store）本项目都不用，而它会藏起"检索了几次、检到什么" | 触发条件（沿用既有结论，不重复论证）：工具数 >8、需并行工具调用/子代理、需接 MCP、文档格式需求超出 PDF+图片且愿意接在 `DocumentReader` 之后 |
| 无 OpenSearch Java Client | **合理** | 只 5 个动作；且 3.x 的 `knn` 语法/`parameters` 键名这类版本差异**最需要肉眼核对**（`OpenSearchIndex.java:41-45`） | 需要 bulk 结果流式处理、跨版本兼容矩阵、或父子/聚合查询超过 ~10 种时，再评估 |
| `Qwen3.8-27B-W8A8` 4 副本 | **合理** | 生成侧上限 ≈0.4 QPS（《AI模型调用与任务队列评估》§3.3 推导），并发闸门 `chat=4` 与副本数对齐（`application.yml:101`） | **当并发问答需求 >4 且重排瓶颈已解除**时（见下），必须先向平台确认"4 副本是否开连续批处理"，再决定加副本 |
| `PaddleOCR-VL` 6 DCU + 全进程 12 在飞 | **合理** | 并发语义已在 `ai-1.2.0` 修正为"共享池 + 动态批页"（`PlatformOcrClient.java:198-238`），与平台实测甜点（在飞 12 → 2.45 页/s）对齐 | 入库积压信号：`/actuator/metrics/ai.model.queue.depth`（model=ocr）持续 >0 且 `ai.upload.queue.depth` >20 → 把 `OCR_CONCURRENCY` 提到 16~24 并复测吞吐（平台上限 96 在飞 → 6.98 页/s） |
| `Qwen3-VL-Embedding-8B` 单实例 4096 维 | **合理，但被 `local` 后端绑定** | 维度固定、不支持 MRL（`AiSettings.java:84-86` + 实测 400）；`local` 是进程内状态 ⇒ 单实例 | 见 §5.2 触发点 T1（切片数）与 T4（HA） |
| `Reranker-8B` 单实例 | **勉强（当前第一瓶颈）** | 24 对/s；recall=50 → ≈150 对/问 → ≈0.16 QPS（实测口径，非估算） | 见 §5.2 触发点 T2；**先"降候选/改预取"，不要先加实例**——既有结论已给出 2.5× 的无成本收益路径 |
| OpenSearch 3.1.0 **单节点** + HNSW lucene + `cosinesimil` + 1 分片 0 副本 | **勉强可接受** | 单节点的可用性明账已在《OpenSearch部署与接入》§0.3/§0.4 算清（快照兜底）；1 分片 0 副本是单机上的**正确**配置（副本无法分配只会 yellow 且白占盘，`OpenSearchIndex.java:54-55`） | 见 §5.2 触发点 T3（容量）与 T4（HA）：**分片数不可原地增加**，扩容即重建索引 |
| `local` 进程内向量作为回滚后端 | **合理（但只能当回滚）** | 回滚路径必须具备；两条后端同时接业务会产生"同一问题两种召回"（`application.yml:76-78` 已写死纪律） | 一旦出现"需要在生产双跑对比"的需求，改为**离线**对比（`TopKCompareTool` 已是这个形态），而不是在线双接 |
| `work/` 文件态存储（docs/vectors/doc-meta） | **勉强** | 单实例硬约束 + 每次检索全量读盘（量化见《AI模型调用与任务队列评估》§3.4）；但"可重建物不进主系统库"这条边界是对的 | 见 §5.2 触发点 T5（文档数）与 T4（第二个实例） |
| `attachment_ai_task` 作为任务事实源 | **合理方向、当前未收敛** | 主系统表已建（V13），但 AI 侧 `work/tasks.json`（≤100 条）仍是影子记录，重启即在跑任务标 FAILED（`UploadTaskService.java:118-132`） | 双机 LB 或需要"重启自动续跑"时，按既有结论落 V15 六列 + 抢占式领取（复用 `attachment_ai_task`，不新建表） |
| `eval/smoke-30.json` + `scripts/eval-ai.mjs` | **合理的第一版** | 35 题、真值取自页面接口、支持漂移快照与交叉校验——**方向完全正确**，比多数项目的第一版评测强 | 见 §5.2 触发点 T6（做任何切片/召回/提示词改动之前必须先补页码标注与检索级指标） |

### 5.2 量化触发点（什么时候必须换/加什么）

| 触发点 | 量化阈值 | 必须做什么 | 依据 |
| --- | --- | --- | --- |
| **T1 切片数** | 切片 **≥4,000**（≈130 份 30 切片文档）时 `local` 给每次提问 **+1.0s**；**≥15,000** 时 **+3.8s** | `local` 只能作为回滚；生产必须 `VEC_BACKEND=opensearch`。**注意先修 G2**，否则换后端后每次检索多付一次全量 `_bulk` | 《AI模型调用与任务队列评估》§3.4 表（实测系数） |
| **T2 重排吞吐** | 问答需求 >**0.16 QPS**（≈10 问答/分钟） | ① `RETRIEVAL_RECALL_MODE=low`（50→25，≈0.4 QPS，**前提是评测基线存在**，否则不许改默认）；② 检索改**服务端预取**（把 2 次检索/问降到 1 次）；③ 仍不够才考虑第二 rerank 实例 | 《AI模型调用与任务队列评估》§3.3/§6.1 P0-2；《AI工具集与检索编排评估》§4 P0-1 |
| **T3 索引容量** | 向量数 >**约 50 万**（**估算（假设：4096 维 float = 16 KB/向量 + HNSW 图开销约 1.5×，1 分片单节点可用堆按 8 GB 估）**≈8 GB 量级）或 kNN P95 >**300 ms** | 必须**增加分片数并重建索引**（分片数不可原地改），并把 `number_of_replicas` 随节点数恢复 | `OpenSearchIndex.java:188-229`（1 分片 0 副本） |
| **T4 可用性 / 水平扩展** | 出现"AI 服务要 2 个实例"的需求（HA 或吞吐） | **硬前置**：`local` 必须退场（进程内状态 + 本地卷）→ 先完成 OpenSearch 上线；之后 AI 侧才真正无状态可水平扩 | `ai-backend/README.md`:202-203 |
| **T5 文档库规模** | 文档数 >**约 500**（**估算（假设：每份 30 切片、`work/docs` JSON + `work/vectors` 合计约 1.2 MB/份，500 份 ≈600 MB）**） | `work/` 全量读盘的固定开销开始明显（每次检索读全库 JSON）；要么把检索的"候选装载"改为按需（先查索引拿 `doc_id`，再按需读该文档），要么把文档文本移出文件态 | 《AI模型调用与任务队列评估》§3.4 + `RetrievalService.java:494-506` |
| **T6 质量改动门禁** | **任何**切片/召回/提示词/降配改动之前 | 必须先有：① 9 道文档题页码标注；② 检索级 `recall@5`/MRR 基线。**没有这两样，改动一律不许进默认配置** | `eval/README.md`:65；《AI模型调用与任务队列评估》§6.1 P0-2 的"必须先有基线" |
| **T7 清洗与存储的联动** | 一旦实现清洗（G1） | 切片键含内容 sha1（`RetrievalService.java:583-586`）⇒ **清洗规则一改，全部切片键变化、全部向量失效**。必须同时给出"清洗版本号"并把它并入切片键或缓存元数据（`loadVectors` 已按 `model`/`dimensions` 校验，扩展一个 `clean_rev` 是同一写法） | `RetrievalService.java:795-823` |
| **T8 OCR 并发** | `ai.model.queue.depth{model=ocr}` 持续 >0，或入库端到端 >**15 分钟**（100 份 20 页扫描件；现状估算 ≈10~14 分钟） | 提高 `OCR_CONCURRENCY` 至 16~24 并复测；平台上限 96 在飞 → 6.98 页/s，再往上本地渲染与共享网关成为新瓶颈 | 《AI模型调用与任务队列评估》§3.2（含区间估算） |
| **T9 入站鉴权** | 任何"AI 端口非严格内网隔离"的部署场景 | 必须加服务间令牌校验（G21）；当前完全依赖网络隔离 | `ai-backend/README.md`:104-106 |

---

## 6. 明确不做 / 推迟的项与理由

| 项 | 判定 | 理由（可证伪的口径） |
| --- | --- | --- |
| 引入 LangChain4j / Spring AI | **不做** | 既有结论（`AI工具集与检索编排评估.md` §7）已逐环节核算净收益为负；本轮读代码**没有**发现推翻它的新证据（工具 3+1 个、无多厂商、无并行工具调用） |
| 引入 OpenSearch Java Client / Elasticsearch Java API | **不做** | 只 5 个动作；且 3.x 的 `knn`/`parameters` 版本差异需要肉眼核对（`OpenSearchIndex.java:41-45`） |
| 引入 Redis / MQ / 独立向量库 | **不做** | 缓存需求（G15）用进程内 LRU 即可；MQ 的收益场景被"AI 服务单实例"抵消；第二套向量库会与 OpenSearch 形成双事实源 |
| NL2SQL / `sql_query` 工具 | **永不做** | 既有结论（`AI前端与集成方案.md` §8.2 三条禁止）：审计不可控、注入与性能风险 |
| 在 AI 侧新建持久化任务队列 | **不做** | 会与 `attachment_ai_task` 形成两个事实源；既有结论（`AI模型调用与任务队列评估` §5.2.4） |
| GraphRAG / 知识图谱 | **不做** | 语料是"合同/方案/验收材料"，实体关系以"项目—合同—付款"为主，**已在主系统结构化了**；图结构的边际收益低于其维护成本（同一批问题的正解走 `query_business_data` 更准） |
| 第二个 OCR 引擎（本地兜底） | **不做（行为不回退）** | 实测本地引擎金额全丢，"悄悄换差引擎"比明确失败更危险（`DocumentReader.java:214-219`） |
| 高 DPI 全量重跑 OCR | **不做** | 实测 120~300 DPI 正文识别一致、体积差 17 倍（`PdfReader.java:47-51`）；高 DPI 只用于"印章两遍法"排障 |
| `seal=true` 默认开启 | **不做** | 吞吐减半，且低 DPI 会**编造**印章文字（`PlatformOcrClient.java:62-66`） |
| 降维（`VEC_EMBED_DIMENSIONS>0`） | **推迟** | 与本仓库实测（HTTP 400 不支持 MRL）冲突；即便支持，它也**替代不了** OpenSearch（不解决单实例与"先过滤"）；且无 recall 基线（§7 未确认 4） |
| 切片在线编辑 / 可视化编排 | **推迟** | `AI前端与集成方案.md` §8.3 已定为"不做"；当前收益低于维护面（但**切片只读视图**值得随 G16 一起做，成本低） |
| 答案本体改结构化 JSON 输出 | **推迟** | 审计场景的答案要"给人读+可逐条核对"；结构化的收益集中在前端渲染，而当前前端已能渲染 markdown + 引用跳转（G19 因此列为 P2） |
| 全库改数据库/对象存储（替代 `work/`） | **推迟** | 触发点 T4/T5 未到之前，文件态是"零运维 + 可人工查看"的最优解；过早引入 DB 会多一层运维且违反"可重建物不进主系统"的边界 |
| `/analyze` 改逐页 map-reduce | **推迟（既有结论）** | 属 `AI模型调用与任务队列评估` §6.2 P1-5；与本次"问答链路"评估不同轴，本文不重复排期 |

---

## 7. 未确认项（诚实登记 + 怎么确认）

| # | 未确认项 | 为什么要紧 | 怎么确认（可执行） |
| --- | --- | --- | --- |
| 1 | 平台 OCR 的 `markdown` 字段**是否包含 Markdown/HTML 表格**、是否包含页眉页脚/水印文字 | 直接决定 G1（清洗）与 G11（表格整块）要做什么、做多少；如果平台已输出干净 markdown 表，G11 的成本大幅下降 | 取一份"带表格 + 水印"的真实 PDF：`curl -XPOST .../ocr/file -F file=@x.pdf` 后把 `results[].markdown` 原样落盘，人工比对表结构与水印文字是否出现 |
| 2 | 平台是否返回 `handwrite` / `formula` / `chart` 等 label | 决定"公式/图表"是"不适用"还是"平台给了但代码丢了" | 同上，打印 `results[].blocks[].label` 的**全集**（去重计数） |
| 3 | rerank "P50 34ms / 24 对/s"的语义（单对 vs 整批） | 0.16 QPS 的推导按"吞吐 24 对/s"；若语义不同结论要改（既有未确认项，本文沿用） | 自测一批 75 对的墙钟：`VecClient.rerank` 直连一次，打印耗时（`ai-backend/scripts` 下加一个临时 main 或用现有测试类） |
| 4 | 降维能力（平台是否支持 `dimensions`） | 决定 §6"降维推迟"是否要重新评估 | 在真实网关发一次带 `dimensions=1536` 的 `/embeddings` 请求，看是否仍 400 |
| 5 | 生产当前是 `local` 还是 `opensearch`、`indexed_chunks` 多少、`work/docs` 多少份 | 决定 G2 的紧迫度与 §5.2 T1/T5 的落点 | 服务器上 `grep VEC_BACKEND ai/.env`；`curl 'http://127.0.0.1:8100/health?with_vec=true'` 看 `indexed_chunks`；`ls work/docs \| wc -l` |
| 6 | 切片 500 字与 4096 维 embedding 的匹配度 | 决定 G11/G12 之外要不要动切片大小 | 补页码标注（G8）后，用离线检索评测对比 300/500/800 字三档的 recall@5（**必须先有 T6 的基线**） |
| 7 | `SCANNED_CHAR_THRESHOLD` 这个环境变量是否真的失效 | 我按 Spring 的"环境变量 → `@ConfigurationProperties` 宽松绑定"规则判定：`.env` 里的裸键 `SCANNED_CHAR_THRESHOLD` 绑不到 `ai.ocr.scanned-char-threshold`（`application.yml` 里**没有** `${SCANNED_CHAR_THRESHOLD:...}`，全仓 grep 只命中 `.env.example:59` 与文档），故它**看起来是死配置**——但未实机验证 | 起服务时把该变量设成 200，`curl /health` 看 `config.scanned_char_threshold` 是否跟着变；不变即确认失效（修法：在 yml 里补 `${SCANNED_CHAR_THRESHOLD:50}`） |
| 8 | 主系统附件白名单里哪些格式**实际会**进入 AI 解析队列（自动触发） | 决定 G5/G22 的真实影响面（是所有 docx 都失败，还是自动解析只对 PDF 开放） | 看 `backend` 侧 `AttachmentUploadAutoParse` 的触发条件与 FILE_TYPE 字典；或实测上传一个 xlsx 附件看任务队列状态 |
| 9 | `OCR_CONCURRENCY=12` 在真实负载下的**实际在飞数** | 决定 T8 是否已经该提 | 压测 4 份 20 页扫描件时读 `/actuator/metrics/ai.model.inflight` 与 `ai.model.queue.depth`（model=ocr） |
| 10 | 现网 `work/pages/` 是否有渲染残留、`work/tmp/` 是否有临时文件残留 | 反映"清理纪律"是否被破坏（`DocumentReader`/`UploadTaskService` 都有 finally 清理） | 服务器 `ls work/pages \| wc -l`、`ls work/tmp \| wc -l`，应为 0 |
| 11 | 清洗的真实痛点分布（页眉页脚出现率、空白页率、水印率） | 决定 G1 第一期做哪几件、做到什么程度 | 取 20 份真实入库文档，写一次性脚本统计：① 同一行文本在 ≥50% 页出现的比例；② 纯页码行比例；③ 空页比例（`summary.empty_pages`）；④ 页首/页末行重复率 |
| 12 | 9 道文档题的原始 PDF 是否可获取 | G8 的页码标注是 T6 的前置，而它卡在"拿不到物理 PDF"（`eval/smoke-30.json` 各题 note 里明写） | 由持有附件的人从主系统附件页导出这 9 份 PDF，逐个标注"答案依据在第几页" |
| 13 | 平台网关是否有独立配额/限流（四类模型共用同一网关） | 决定 T8 提并发后的真实上限（既有未确认项，本文沿用） | 向平台方确认；或在压测中同时打 OCR 与 chat，观察两侧延迟是否互相劣化 |
| 14 | 平台侧部署形态：OCR 是否 6 DCU、chat 是否 4 个**独立可并行副本**、是否开连续批处理（continuous batching） | 决定 §1.3/§5.1 里"4 副本 ≈0.4 QPS"与"OCR 在飞 12"这两条推导的前提是否成立；**这些数字不出现在仓库代码里**（代码只体现并发参数 12/8/2/4） | 向平台方索取部署清单；或自行压测：并发 4 个 chat 请求看是否真的并行（总耗时 ≈ 单个耗时而不是 4 倍） |

---

## 附：本文的证据清单（可逐条复核）

| 环节 | 关键文件与行 |
| --- | --- |
| 解析 | `ai-backend/.../doc/DocumentReader.java:69-70,98-117,121-139,150-211,221-261,374-389`；`ocr/PdfReader.java:94-107,130-141,157-180,218-234,236-265`；`ocr/PlatformOcrClient.java:101-102,198-238,246-296,305-308,439-459,468-503`；`doc/PageText.java:102-128` |
| 清洗 | 全仓无实现；被动归一在 `PdfReader.java:225,229-234`、`DocStore.java:209,215-229`、`RetrievalService.java:564-580`、`CitationRegistry.java:148-153`、`Checks.java:40` |
| 切分 | `store/DocStore.java:42,187,190-249,252-267`；`retrieval/DocIndexer.java:91`；`retrieval/RetrievalService.java:79,583-586`；`llm/Tools.java:81` |
| 召回 | `retrieval/RetrievalService.java:210-374,409-459,472-506,519-544,596-669,685-692,795-823`；`retrieval/OpenSearchIndex.java:188-229,452-474,528-572,607-638,692-716`；`retrieval/SearchScope.java:23,57-66`；`retrieval/DocMeta.java:20-27`；`llm/Tools.java:167-231` |
| 生成 | `llm/Prompts.java:204-277,285-303,325-357`；`llm/QaService.java:52,130-143,186-257,291-316`；`llm/ToolAgent.java:48,210-305`；`llm/Tools.java:78-92,238-282,305-537,586-665`；`llm/LlmClient.java:139-227,319-328`；`llm/CitationRegistry.java:88-134`；`llm/AnalyzeService.java:78,146,169-192` |
| 横切 | `common/model/ModelRegistry.java:107-141,217-247,307-345`；`module/task/UploadTaskService.java:83-88,118-132,145-174,236-323`；`module/admin/ReindexService.java:29-54`；`module/system/controller/HealthController.java:50-97,142-174`；`common/config/AiSettings.java`（全文）；`src/main/resources/application.yml`（全文）；`src/main/java/.../Prompts.java` 与 `application.yml:136-141`（日志无 traceId） |
| 配置口径 | `ai-backend/.env.example`（全文，含 `SCANNED_CHAR_THRESHOLD=50` 一处疑似死配置，见 §7 未确认第 7 条）；`application.yml:1-141` |
| 评测 | `eval/smoke-30.json`（35 题）；`scripts/eval-ai.mjs:1301-1312`；`eval/README.md:65,202,213` |
