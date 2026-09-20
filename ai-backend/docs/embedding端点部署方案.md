# embedding 端点：选型与部署方案

> **📍 本文档已随实现迁移到 `ai-backend`（Java / Spring Boot）。**
>
> - 实现位置：`ai-backend/src/main/java/com/pmgt/ai/`，模块级对照见
>   [《迁移方案与对照表》](迁移方案与对照表.md)。
> - **Python 侧代码与文档已删除**，所以正文里出现的 `ai-service/...`、`src/pm_ai/*.py`、
>   `scripts/*.py` 等**路径均属历史**——本文已就地改为对应的 Java 位置，或标注为「历史」。
> - ⚠️ 文中若出现"见 README §13 / §14 / §15"这类**对旧 Python README 的引用**，
>   那些结论已全部搬进 [《平台能力实测结论》](平台能力实测结论.md)，
>   编号换算表见该文「附录 B：章节编号对照」。
> - **历史实测数据与决策结论一字未删**：路径会过期，结论不会。

> ⛔ **本文已被取代（2026-09-18 起，请勿照此施工）**
>
> 平台网关已**直接提供** Embedding / Reranker：**`Qwen3-VL-Embedding-8B`**（固定 4096 维）与
> **`Qwen3-VL-Reranker-8B`**（网关 `http://10.254.208.35:8090/v1`，与千问对话、平台 OCR 共用同一把 sk）。
> 因此**本文描述的"自建 TEI embedding 端点"路线不再采用**——不需要额外容器、不需要预置模型权重、
> 也不需要 `EMBED_*` 那套环境变量。服务侧的实际实现是 `ai-backend` 的 `module/retrieval/VecClient.java`，
> 链路验证脚本是 `ai-backend/scripts/verify-e2e.ps1`，现状见
> [`知识库落地实施方案.md`](知识库落地实施方案.md)（文首"二次更新"）与
> [《平台能力实测结论》](平台能力实测结论.md) §4（检索链路实测与选型口径）。
>
> **正文一字未删，保留作历史推演**：仅在"平台撤掉这两个模型、需要自建退路"时才参考。
> ⚠️ 若真要走这条路，注意"平台实际部署不支持 MRL 降维"这一实测结论对自建选型同样适用
> （降维要自己确认模型/服务是否支持，别照抄维度数字）。

> 2026-09-18 · 配套《知识库落地实施方案》§2。本文**正文即定稿**，可直接照着做；
> 对比推演与备选路线见文末附录。
> 适用场景：把"文本 → 向量"这一步做成一个独立的、OpenAI 兼容的内部服务。

---

## 0. 定稿

| 项 | 结论 |
| --- | --- |
| **部署形态** | **TEI 独立容器**（HuggingFace Text Embeddings Inference），暴露 `POST /v1/embeddings` |
| **镜像（麒麟 ARM64 CPU）** | `ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9` ← **官方 aarch64 镜像，已确认存在** |
| **镜像（NVIDIA GPU）** | `…-inference:1.9`（Ampere 8.0）/ `89-1.9`（Ada）/ `86-1.9`（Ampere 8.6）/ `hopper-1.9` |
| **模型（首选）** | `Qwen/Qwen3-Embedding-0.6B` · 1024 维 · 32K 上下文 · Apache-2.0 |
| **模型（纯 CPU 起步）** | `BAAI/bge-small-zh-v1.5` · 512 维 · **512 token** · MIT |
| **许可** | TEI 引擎 Apache-2.0；两个模型分别 Apache-2.0 / MIT —— **全部 OSI 开源，过评审干净** |
| **端口** | 容器内 80 → 宿主 `8091`（仅内网，不对外） |
| **放置** | 与知识服务**同一 compose**（不进主系统 compose） |
| **为什么是它** | 它是**唯一同时满足**下面三条的：① 有**官方 aarch64 CPU 镜像**（能直接跑麒麟）② 原生 OpenAI 兼容 `/v1/embeddings`（现有客户端零改动）③ Apache-2.0 |

> ⚠️ **一条必须知道的边界**：TEI 的 GPU 镜像**只支持 NVIDIA**。
> 平台那台是 **8× 海光 K100AI DCU**，**跑不了 TEI** —— 这正是"选项 A（让平台方提供端点）"
> 必须由**平台方用他们自己的适配栈**（sglang / vLLM 海光版）来做，而不是我们丢一个容器过去。

---

## 1. 模型选型

### 1.1 三个候选的硬指标

| | **Qwen3-Embedding-0.6B** | **bge-m3** | **bge-small-zh-v1.5** |
| --- | --- | --- | --- |
| 参数 / 体积 | 0.6B / ~1.2GB | 0.57B（稠密头） / ~2.9GB | **24M / ~100MB** |
| 维度 | 1024（**MRL 可 32–1024**） | 1024 | 512 |
| 上下文 | **32K** | 8K（原生 32K，实际默认 8192） | **512** ⚠️ |
| 中文检索（原生中文基准 retrieval@10） | 优（4B 版 91%，0.6B 略低） | 88% | **90%** |
| 许可 | Apache-2.0 | MIT | MIT |
| 特色 | **指令感知**（可注入"为合同条款检索生成嵌入"）、免微调提 1–5% | 稠密+稀疏+多向量三模态 | 极小，CPU 友好 |
| 主要负担 | CPU 上慢（decoder 架构，比 BERT 类大 25×） | 稀疏头**要另起服务 + 应用层做融合**；有 GPU 下 JSON/markdown 输入报 NaN 的实测报告（需自验） | 上下文只有 512 token |

### 1.2 结论与理由

**首选 `Qwen3-Embedding-0.6B`**，理由按重要性排序：

1. **32K 上下文**——彻底摆脱切片长度焦虑。对比之下 `bge-small-zh` 只有 **512 token**，
   你们切片目标是 500 字，中文 BERT 分词约 1 字 1 token → **正好卡在上限**，
   被迫把切片压到 ~400 字或依赖截断，这是实打实的约束。
2. **中文与跨语言检索优于 bge-m3**（多个 2026 榜单一致：中文项 Qwen3 64.2 vs BGE-M3 62.8）。
3. **指令感知**：可以传 `Instruct: 为政府采购合同条款检索生成嵌入\nQuery: …`，
   **不微调就拿到 1–5% 的领域增益**——对"合同/付款/验收"这类强领域词汇很实用。
4. **Apache-2.0**，且是国产模型（阿里），合规叙事干净。
5. **MRL 可降维**是同系列能力，但**你们这个规模用不上**（见 §5），别为它增加复杂度。

**不选 `bge-m3`**：它的最大卖点是"稠密+稀疏+多向量三合一"，
但**稀疏头要单起一个服务、再在应用层做融合权重 AB 测试**——等于把 P2 的复杂度提前塞进 P0。
而且我们**本来就要做 BM25 + 向量双路 RRF 融合**（《知识库落地实施方案》§5.2），
这条路已经覆盖了"关键词穿透力"这个需求，**不需要模型内部的稀疏头**。

**`bge-small-zh-v1.5` 的定位**：**纯 CPU 环境下先跑通链路**的起步选择。
它能在 CPU 上跑到可用吞吐，代价是上下文只有 512 token（切片要压到 ~400 字）。

> **换模型必须"重建索引"，但不需要重新 OCR** —— 解析结果已持久化在 `work/docs/`，
> 重跑向量化即可（1.5 万切片，分钟级）。所以**模型选择不是不可逆决策**，别在这上面卡住。

---

## 2. 部署形态：为什么用 TEI 而不是别的

| 形态 | 优点 | 为什么不用 / 何时用 |
| --- | --- | --- |
| **TEI（选用）** | 官方 aarch64 CPU 镜像；OpenAI 兼容；Apache-2.0；动态批处理；**同镜像还能起 reranker**（P2 直接复用） | — |
| 进程内 in-process（`sentence-transformers`/`onnxruntime`） | 零新增服务、零端口 | 会拉进 torch（~1GB）；不能独立扩缩；换模型要重建 ai-service 镜像。**若坚决不加容器，用 onnxruntime 版**（已在镜像里），但仍建议留 HTTP 边界 |
| vLLM / sglang | 吞吐最高，**且是海光 DCU 唯一可行路线** | GPU 镜像生态偏 NVIDIA；DCU 要用海光官方适配版 → **留给平台方（选项 A）** |
| Ollama | 装机最简 | **无动态批处理、不适合服务端批量入库**；且它不是为"服务化 embedding"设计的 |
| Xinference（备选） | 一个容器跑多模型；OpenAI 兼容；国产团队（Apache-2.0） | 资源占用更大、抽象更多。**若合规上希望服务框架也偏国产，选它** |

**关键取舍**：把 `/v1/embeddings` 当作**稳定契约**，
背后是 TEI、vLLM 还是平台方的端点都无所谓——**客户端只有一套代码**（现有 OpenAI 兼容客户端），
换实现只改 `EMBED_BASE_URL`。这也是为什么**不建议**做成进程内调用。

---

## 3. 部署步骤

### 3.1 外网机准备（两组产物）

```bash
# ① 镜像（选与本机架构/显卡匹配的 tag）
#    ARM64 / 麒麟 CPU：
docker pull ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9
#    NVIDIA GPU（按显卡架构选）：
# docker pull ghcr.io/huggingface/text-embeddings-inference:1.9

docker save ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9 \
  | gzip > tei-cpu-arm64-1.9.tar.gz

# ② 模型权重（任选其一，国内建议 ModelScope）
pip install -U "huggingface_hub[cli]" modelscope
huggingface-cli download Qwen/Qwen3-Embedding-0.6B \
  --local-dir ./models/Qwen3-Embedding-0.6B
# 或
modelscope download --model Qwen/Qwen3-Embedding-0.6B \
  --local_dir ./models/Qwen3-Embedding-0.6B

tar czf tei-models.tar.gz -C ./models Qwen3-Embedding-0.6B
```

> ⚠️ 权重目录里必须有 `config.json` / `tokenizer*` / `*.safetensors`（HF 格式）。
> TEI 用 `--model-id` 指向**本地目录**，**不要**写模型名 —— 写名字它会联网去 Hub 拉，内网必失败。

### 3.2 内网服务器导入

```bash
mkdir -p /home/lhim/pm/ai/{models,work,os-data}
gunzip -c tei-cpu-arm64-1.9.tar.gz | docker load
tar xzf tei-models.tar.gz -C /home/lhim/pm/ai/models
docker image ls | grep text-embeddings      # 确认镜像已导入
```

### 3.3 compose 片段（加在**知识服务侧**编排里）

```yaml
services:
  embedding:
    image: ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9
    container_name: pm-embedding
    restart: unless-stopped
    pull_policy: never                 # 与主系统同一约定：无外网，禁止误拉取
    command:
      - --model-id=/data/models/Qwen3-Embedding-0.6B   # 本地目录，不是模型名
      - --port=8091
      - --dtype=float32                # CPU 上用 float32（不要 bf16）
      - --max-concurrent-requests=8
      - --max-batch-tokens=32768
      - --auto-truncate                # 超长输入自动截断而不是报错
      - --api-key=${EMBED_API_KEY}     # 内网也建议带上，留一致的口子
    environment:
      HF_HUB_OFFLINE: "1"              # 禁止任何联网尝试
      TRANSFORMERS_OFFLINE: "1"
    volumes:
      - /home/lhim/pm/ai/models:/data/models:ro
    ports:
      - "127.0.0.1:8091:8091"          # 只监听本机/内网，不对外暴露
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8091/health"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 120s               # 首次加载模型要时间
```

### 3.4 `.env`（与《知识库落地实施方案》§6.3 对齐）

```ini
EMBED_BASE_URL=http://embedding:8091/v1
EMBED_API_KEY=                        # 与 compose 的 EMBED_API_KEY 保持一致
EMBED_MODEL=Qwen3-Embedding-0.6B      # TEI 会忽略该字段，但客户端需要
EMBED_DIM=1024
EMBED_BATCH=32
```

> `EMBED_DIM` **必须与实测维度一致**，它是建索引 mapping 的依据。

### 3.5 启动与冒烟验证（四步，缺一不可）

```bash
docker compose up -d embedding
docker compose logs -f embedding | head -50      # 看是否有 "Ready" / 模型加载完成

# ① 健康
curl -s http://127.0.0.1:8091/health

# ② 维度（最关键：确认 EMBED_DIM）
curl -s http://127.0.0.1:8091/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"input":"数据库一体机补充协议","model":"x"}' \
  | python -c "import sys,json;print('dim =',len(json.load(sys.stdin)['data'][0]['embedding']))"

# ③ 自相似度应 ≈ 1.0
#    把同一句编码两次，余弦相似度必须 >0.999

# ④ 跨文本分离度应明显 <1.0
#    "合同金额七百七十八万" vs "项目验收合格" 的余弦相似度应明显低于上一步
```

**验收标准**：`dim` 与 `EMBED_DIM` 一致；同文本余弦 >0.999；语义无关文本余弦明显更低（经验值 <0.85）。
第 ③④ 步是"确认服务真的在算、而不是返回常量"的必要检查——**别只测 200 OK**。

---

## 4. 性能与容量

### 4.1 量级（本项目）

```
500 份附件 × 平均 30 切片  ≈  1.5 万切片
1.5 万 × 1024 维 × 4B      ≈  60 MB 向量
```

**结论**：容量与维度都不是问题，**1024 维直接用，不必为省存储去做 MRL 降维**
（省 30MB 换来一层额外的归一化与对账成本，不值）。

### 4.2 吞吐（估算，部署后实测校准）

| 模型 | CPU（x86 8 核） | CPU（麒麟 ARM64 8 核） | NVIDIA GPU |
| --- | --- | --- | --- |
| `bge-small-zh-v1.5`（24M） | 百级 切片/秒 | 十级~几十/秒 | 千级/秒 |
| `Qwen3-Embedding-0.6B`（595M，decoder） | 个位~十几/秒 | **可能 <5/秒** | 百级~千级/秒 |

> ⚠️ **这是选型的关键权衡**：`Qwen3-Embedding-0.6B` 比 `bge-small-zh` 大 **25 倍**，
> 在 CPU 上会慢一个数量级。1.5 万切片在麒麟 ARM64 纯 CPU 上可能要 **数十分钟到几小时**（一次性）。
> 这个代价是否可接受，取决于：**附件是"一次性全量入库"还是"持续增量"**。
> 增量场景（每天几十份附件）即使 5 切片/秒也毫无压力。

**调优手段**（按性价比排序）：
1. **批量提交**（`EMBED_BATCH=32`，TEI 自动批处理）——收益最大；
2. `--max-batch-tokens` 调大（吃满内存换吞吐）；
3. 大批量入库用 **二进制响应**：请求加 `Accept: application/octet-stream`，
   1024 维向量从 ~28KB JSON 变 4096 字节（**约 7× 带宽**），`numpy.frombuffer` 直接解；
4. 真要快，加一块最便宜的 NVIDIA 卡——比任何 CPU 调优都有效。

---

## 5. P2 的 reranker 复用同一个镜像

TEI ≥1.3 支持 `POST /rerank`（交叉编码器）。**一个实例只加载一个模型**，所以 reranker 是**第二个容器**：

```yaml
  reranker:
    image: ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9
    container_name: pm-reranker
    pull_policy: never
    command:
      - --model-id=/data/models/Qwen3-Reranker-0.6B
      - --port=8092
      - --dtype=float32
      - --api-key=${EMBED_API_KEY}
    environment: { HF_HUB_OFFLINE: "1", TRANSFORMERS_OFFLINE: "1" }
    volumes: [ /home/lhim/pm/ai/models:/data/models:ro ]
    ports: [ "127.0.0.1:8092:8092" ]
```

**reranker 选型**（MTEB-R / CMTEB-R）：

| 模型 | 参数 | MTEB-R | CMTEB-R（中文） | 说明 |
| --- | --- | --- | --- | --- |
| **Qwen3-Reranker-0.6B** | 0.6B | **65.80** | 71.31 | 综合最优 |
| BGE-reranker-v2-m3 | 0.6B | 57.03 | **72.16** | **纯中文略优**，且更成熟 |
| Qwen3-Reranker-4B | 4B | 69.76 | **75.94** | 有 GPU 就上它 |

> 你们是**纯中文语料**，所以 **BGE-reranker-v2-m3 和 Qwen3-Reranker-0.6B 都在候选内**，
> 且两者都是 0.6B、都能被 TEI 加载。**用 P2 的评测集在两者之间选**，不要凭榜单定。
> P2 之前**不需要**部署 reranker。

### 5.1 ⚠️ 重排的成本现实：它基本上是一条"要 GPU"的路

reranker 是**交叉编码器**：它要把「问题 + 每一个候选」**成对**送进模型跑一次前向。
所以一次重排的开销 = **单次前向 × 候选条数**，这跟 embedding（一次前向编码一段文本）完全不是一个量级。

粗算（0.6B 模型、每个候选约 600 token）：

```
单次前向 ≈ 2 × 0.6e9 × 600 ≈ 7.2e11 FLOPs
普通 CPU 有效算力约 5e10 FLOPs/s  →  单对约 3~15 秒
× 50 个候选                       →  150~750 秒   ❌ 交互场景不可用
```

| 场景 | 处置 |
| --- | --- |
| **有 GPU** | top50 重排毫无压力（毫秒~百毫秒级），直接上 `Qwen3-Reranker-0.6B` |
| **纯 CPU** | 三选一，按性价比：① **候选先压到 top10~20** 再重排；② 换 tiny 交叉编码器（如 `ms-marco-MiniLM-L-6-v2`，22M）；③ **先不做 rerank**——只靠 RRF 融合分数已经能吃下大部分收益 |
| **判断依据** | 用 P2 评测集量一下"加 rerank vs 不加 rerank"的**召回/引用准确率增益**，再决定值不值这个延迟 |

> **这也是为什么"机器上有没有 NVIDIA 卡"是本次选型的关键分歧点**：
> 有卡 → 可以做 Qwen3-Embedding + Qwen3-Reranker 的完整体；
> 没卡 → 向量化用 bge-small-zh 走着，**重排先不上**（或用 tiny 模型 + 小候选集）。

---

## 6. 与"选项 A（平台方提供端点）"的关系

| 情形 | 动作 |
| --- | --- |
| 平台方加了端点 | 改 `EMBED_BASE_URL` + `EMBED_API_KEY` + `EMBED_DIM` → **重建索引**（分钟级） |
| 平台方没加 | 本方案就是最终形态，一直用下去，没有欠债 |

**两条路都保留**的理由：本地方案是**能力基线**（不依赖别人），平台方案是**运维便利**（少一个容器）。
**先做本地，别等平台**——这是唯一不阻塞工期的选择。

> ⚠️ 平台方提供时，务必**实测维度并做 §3.5 的 ③④ 两步**。
> 不同实现（vLLM / sglang / 平台封装）对 `input` 是字符串还是数组、
> 是否支持 `encoding_format`、返回是否归一化的行为**可能不一致**——
> 客户端要按实测结果做兼容，不要假设"OpenAI 兼容 = 完全一致"。

---

## 7. 风险与坑

| # | 风险 | 应对 |
| --- | --- | --- |
| 1 | **模型权重必须离线预放** | `--model-id` 指本地目录 + `HF_HUB_OFFLINE=1`；**与 RapidOCR"首跑自动下载"是同一个坑** |
| 2 | TEI 可能不支持某模型（新模型支持晚于模型发布） | **先做加载冒烟**；加载失败立刻退回 `bge-small-zh-v1.5`（BERT 家族肯定支持） |
| 3 | 麒麟 ARM64 上 GPU 不可用 | 走 `cpu-arm64-1.9`；海光 DCU 不属支持列表 |
| 4 | `bge-small-zh` 只有 512 token，切片 500 字会**顶到上限** | 开 `--auto-truncate`，并**把 `chunk_chars` 降到 400**；或改用 Qwen3（32K） |
| 5 | 首次启动慢（加载权重 1–2 分钟） | `start_period: 120s`，别让 healthcheck 过早判死 |
| 6 | 镜像/权重没预放 → 现场卡住 | 内网导入后**做一次断网验证**（拔掉外网也一样跑通） |
| 7 | 维度变了但索引没重建 → 检索结果错乱 | `EMBED_DIM` 与索引 mapping 的 `dimension` 必须同时改；**索引版本化 + 别名切换** |
| 8 | 端口对外暴露 | 绑 `127.0.0.1`，只在 compose 网络内互访 |

---

## 附录 A：对比推演（过程记录）

| 讨论 | 结论 | 依据 |
| --- | --- | --- |
| 为什么不用最"省事"的 in-process | 会失去"换实现只改 URL"的能力 | 我们要保留 A 方案（平台端点）这条路，HTTP 契约是必需的 |
| 为什么不用 Ollama | 它面向"单机交互"，非服务端批量 | 无动态批处理，入库吞吐差一个量级 |
| 为什么不一上来就 vLLM | 海光 DCU 需要用海光适配版 | 那是平台方的栈；我们用 TEI 的 aarch64 CPU 镜像更可控 |
| 为什么不选 bge-m3 | 稀疏头的收益已被"BM25 + 向量 RRF 融合"覆盖 | 《知识库落地实施方案》§5.2 已含双路融合；避免复杂度前置 |
| 为什么 1024 维不做 MRL 降维 | 省 30MB 不值得再加一层对账 | 见 §4.1 |
| 为什么 reranker 现在不部署 | P2 才有用，且要在两个候选间用评测集选 | 见 §5 |

## 附录 B：一句话速查

```
形态：TEI 独立容器 → POST /v1/embeddings
镜像：ARM64 → cpu-arm64-1.9 ｜ NVIDIA → 1.9 / 89-1.9 / 86-1.9
模型：有 GPU → Qwen3-Embedding-0.6B（1024 维）｜纯 CPU → bge-small-zh-v1.5（512 维，切片≤400 字）
离线：docker save 镜像 + 预下权重，--model-id 指本地目录，HF_HUB_OFFLINE=1
验收：dim 与 EMBED_DIM 一致；同文本余弦 >0.999；无关文本余弦明显更低
P2：同镜像再起一个容器跑 Qwen3-Reranker-0.6B（或 BGE-reranker-v2-m3，用评测集定）
```
