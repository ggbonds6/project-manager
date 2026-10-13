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
| POST | `/chat` | 文档问答（工具调用：检索 → 读页 → 计算；带 `biz_query` 时**多一个** `query_business_data`）。请求 `{question, doc_ids, scope?, history, biz_query?}`（`docIds`/`bizQuery` 亦接受）；**`scope` 是检索的硬上界**：`{doc_ids?, attachment_ids?, project_ids?, security_levels?}`，与模型在工具里给的 `doc_id` **取交集**，越界直接返回 `scope_violation`（**不退回更大范围**）——不传时行为与本版本前完全一致。`biz_query = {url, scope_token, entities}` 是主系统给的**受控查询通道**（P2，契约见 [`../docs/AI前端与集成方案.md`](../docs/AI前端与集成方案.md) §11）——**不传时行为与本版本前完全一致**。响应 `data.answer` 正文用 `[1][2]` 标注出处，`data.citations` 给出结构化出处 `[{index,doc_id,filename,page_no,snippet,score}]`——**前端"点引用跳原文第 N 页"就靠它**，无引用时为 `[]`；系统数据查询照常进 `data.trace`（名称/参数摘要/耗时/命中数） |

成功响应 `{"code":0,"data":{...}}`；失败为 HTTP 状态码 + `{"detail":"..."}`（与 Python 版一致）。
其中 **503 = 向量后端不可用 / 模型繁忙**（可重试），**429 = 解析队列已满**（可重试），两者都带明确原因。

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

