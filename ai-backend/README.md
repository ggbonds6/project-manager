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
| `LLM_BASE_URL` | `http://10.254.208.35:8090/v1` | 平台网关（对话 / OCR / 向量化 / 重排**共用**） |
| `LLM_API_KEY` | — | 用户 sk（同一把） |
| `LLM_MODEL` | `Qwen3.8-27B-W8A8` | 对话模型 |
| `OCR_MODEL` | `PaddleOCR-VL-1.6-0.9B` | 平台 OCR 模型 |
| `OCR_CONCURRENCY` / `OCR_BATCH_PAGES` | 12 / 8 | 并发与单请求页数（平台建议 ≤16） |
| `VEC_EMBED_MODEL` / `VEC_RERANK_MODEL` | `Qwen3-VL-Embedding-8B` / `Qwen3-VL-Reranker-8B` | 向量化与重排 |
| `VEC_EMBED_DIMENSIONS` | 0 | **0 = 不传**（平台默认 4096；本部署不支持 MRL 降维，传了会 400） |
| `RETRIEVAL_RECALL` / `RETRIEVAL_TOP_K` | 50 / 5 | 召回条数 → 精排返回条数 |
| `VEC_BACKEND` | local | `local`（进程内余弦 + JSON 缓存）\| `opensearch`（正式选型） |
| `OPENSEARCH_URL` / `OPENSEARCH_INDEX` | — / `pm-ai-chunks` | OpenSearch 集群与索引 |
| `WORK_DIR` | ./work | 临时文件、文档库、向量缓存 |

## 四条链路（与主系统**同一套形状**）

> 主系统的对应链路见 [`../deploy/README.md`](../deploy/README.md) 与 [`../docs/部署与发布全流程手册.md`](../docs/部署与发布全流程手册.md)；
> 本服务**独立构建、独立发版**：主系统发布包不含它，它的发布包也不含主系统（这是刻意的，见文首）。

| 阶段 | 主系统 | AI 能力服务（本服务） |
| --- | --- | --- |
| ① 本地开发（源码直跑） | `deploy\windows\start-dev.cmd`（`mvn spring-boot:run`）+ Vite | `cd ai-backend && mvn spring-boot:run`（默认 8100；配置见上表） |
| ② 本地开发（容器，一键重建） | `.\scripts\dev-reload.ps1 [all\|backend\|frontend]` | `.\scripts\dev-reload.ps1 -Project ai` |
| ③ 构建 + 打包（出发布包） | `.\scripts\make-release.ps1 <版本>` | `.\scripts\make-release.ps1 <版本> -Project ai` |
| ④ 服务器部署（离线只 load） | 发布包 `docker-compose.yml` + `.env` → `docker compose up -d` | 同上（编排就是 `ai-backend/docker-compose.deploy.yml`），自检 `/health?with_ocr=true&with_vec=true` |

### ③ 出 AI 发布包（开发机，仓库根；需 Docker Desktop 已启动）

```powershell
.\scripts\make-release.ps1 v1.0.0 -Project ai                # 默认 linux/arm64（主系统那两台服务器）
.\scripts\make-release.ps1 v1.0.0 -Project ai linux/amd64    # x86 机器（例如与平台网关同机）
```

产出 `dist/pm-ai-release-v1.0.0/`：`pm-ai-images-<arch>-v1.0.0.tar.gz`（镜像包）、`docker-compose.yml`、
`.env.example`（`AI_IMAGE_TAG` 已预填本次版本）、`服务器部署步骤-ai.txt`（逐步照做即可）。

### ④ 服务器部署（运行目录只需你上传的两个文件 + 已 load 的镜像）

```bash
cd /home/lhim/pm-ai/app && cp .env.example .env && vi .env
#   必填：LLM_API_KEY（平台网关那把 sk）、AI_IMAGE_TAG=v1.0.0、AI_BIND_IP=<本机内网IP>
docker load -i /home/lhim/pm-ai/releases/pm-ai-images-aarch64-v1.0.0.tar.gz
docker compose up -d
curl "http://127.0.0.1:8100/health?with_ocr=true&with_vec=true"   # 期望 code:0 且各模型 ok
```

> **接回主系统**：在主系统那台机器的 `.env` 里把 `AI_SERVICE_BASE_URL` 指到本机内网 IP
> （**与本服务同宿主机**才用 `http://host.docker.internal:8100`），`docker compose up -d` 即生效——**不需要重建镜像**；
> 打开「AI 与知识库 → 服务自检」确认全绿后，再按需把 `AI_AUTO_PARSE=true` 打开。完整说明见部署手册 §8.3。

> **运维边界（发版前必须知道）**：本服务**当前没有任何入站鉴权**，端口不要对全网开放——
> 上线时用 `AI_BIND_IP` 绑内网 IP，并用防火墙只放行主系统服务器；`WORK_DIR` 必须挂卷（丢了要重新解析，不影响正确性）；
> 双机负载均衡场景**建议本服务集中部署一台**（否则两个索引各自演化，同一问题答案可能不一致）。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 自检（`with_llm` / `with_ocr` / `with_vec`） |
| POST | `/analyze` | 附件 → 带来源页码的结构化 markdown（走大模型） |
| POST | `/ocr/pdf-info` | 判断文本型/扫描件与规模 |
| POST | `/ocr/file` | 只识别不调模型（摸底用） |
| POST/GET/DELETE | `/documents[/{id}]` | 文档库（解析入库、列表、详情、删除） |
| POST/GET/DELETE | `/upload-tasks[/{id}]` | 上传解析任务（先返回、后台解析、轮询进度） |
| POST | `/chat` | 文档问答（工具调用：检索 → 读页 → 计算）。请求 `{question, doc_ids, history}`（`docIds` 亦接受）；响应 `data.answer` 正文用 `[1][2]` 标注出处，`data.citations` 给出结构化出处 `[{index,doc_id,filename,page_no,snippet,score}]`——**前端"点引用跳原文第 N 页"就靠它**，无引用时为 `[]` |

成功响应 `{"code":0,"data":{...}}`；失败为 HTTP 状态码 + `{"detail":"..."}`（与 Python 版一致）。

## 运维与风险

- **平台不可用时不降级**：OCR 没有本地兜底引擎（实测本地引擎金额全丢，比明确失败更危险）；
  失败页在响应里以 `failed_pages` + `notes` 显式暴露，需人工复核。
- **检索降级是可见的**：向量服务不可用时退化为关键词检索，并在结果 `note` 里写明。
- **`VEC_BACKEND=opensearch` 在集群未部署/未配置时显式报错**，不静默退回 `local`。

## 文档索引

| 文档 | 用途 |
| --- | --- |
| [`docs/迁移方案与对照表.md`](docs/迁移方案与对照表.md) | **入口文档**：Python → Java 的模块对照、必须原样保留的踩坑结论、HTTP 契约与验证协议 |
| [`docs/平台能力实测结论.md`](docs/平台能力实测结论.md) | **与语言无关的实测结论**：平台 OCR 渲染/并发/印章、确定性校验、大模型与检索链路的全部实测数字与依据（换实现语言也不该丢的部分） |
| [`docs/平台OCR调用使用手册.md`](docs/平台OCR调用使用手册.md) | 平台 OCR（PaddleOCR-VL）的 HTTP 接口、印章与手写体、性能基线与排障（**由 ai-service 迁入**） |
| [`docs/Qwen3-VL-Embedding-Reranker调用手册.md`](docs/Qwen3-VL-Embedding-Reranker调用手册.md) | Embedding / Reranker 的接口、维度与性能基线、召回—精排配比（**由 ai-service 迁入**） |
| [`docs/知识库实施方案与路线.md`](docs/知识库实施方案与路线.md) | **知识库/检索层的一份文档**（原"落地实施方案"与"总体架构与演进路线"合并）：目标与前提、四层架构、索引 mapping 与字段、`VEC_BACKEND=opensearch` 对接、P0/P1/P2 分期与评测集指标、风险登记 |
| [`../docs/AI工具集与检索编排评估.md`](../docs/AI工具集与检索编排评估.md) | **动工具集或检索编排之前必读**（主系统侧文档）：平台的"无状态能力"与本服务"有状态编排"的职责边界、`search_documents`/`read_page`/`calculate` 逐个保留理由、为什么默认走流水线而不是全自主 agent、改造清单与验收口径 |

