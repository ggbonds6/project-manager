# Qwen3-VL-Embedding / Reranker 调用手册

> 平台:`AI 推理平台`(控制台 `http://10.254.208.35:8080`,网关 `http://10.254.208.35:8090/v1`)
> 模型:**Qwen3-VL-Embedding-8B**、**Qwen3-VL-Reranker-8B**
> 服务节点:node135(`10.254.213.135`)GPU6 / GPU7 · 版本:v1.0(2026-09-18)

---

## 目录

- [一、能力概述](#一能力概述)
- [二、密钥与环境变量](#二密钥与环境变量)
- [三、接口规范](#三接口规范)
- [四、调用示例](#四调用示例)
- [五、检索链路实战(召回 + 重排)](#五检索链路实战召回--重排)
- [六、性能与容量(测试数据)](#六性能与容量测试数据)
- [七、验收实测记录](#七验收实测记录)
- [八、平台内的功能](#八平台内的功能)
- [九、运维与排障](#九运维与排障)
- [附录](#附录)

---

## 一、能力概述

两个模型组成一条**多模态检索流水线**:Embedding 负责快速召回,Reranker 负责精排。

| 模型 | 作用 | 输入 | 输出 |
|---|---|---|---|
| **Qwen3-VL-Embedding-8B** | 把内容映射到统一向量空间 | 文本 / 图片 / 图文混合 | **4096 维**向量(支持 64–4096 自定义维度) |
| **Qwen3-VL-Reranker-8B** | 判断 query 与 document 的相关性 | (query, document) 对 | 相关性分数(单一标量) |

共同特点:跨模态(文本、图片、截图、文档图像、视频)、30+ 语言、**32K 上下文**、**指令感知**
(可按任务传自定义 instruction,官方实测带来 1%–5% 收益)、模型底座为 Qwen3-VL-8B。

> 精度参考(官方):Qwen3-VL-Embedding-8B 在 **MMEB-V2 取得 77.9**,为该榜第一梯队。

### 部署规格

| 项目 | Embedding | Reranker |
|---|---|---|
| 卡 / 端口 | node135 **GPU6** / `:8091` | node135 **GPU7** / `:8092` |
| 显存占用 | 81% | 81% |
| 上下文 | 32768 | 32768 |
| 服务栈 | 海光 vLLM 镜像 `harbor.sourcefind.cn:5443/dcu/admin/base/vllm:0.18.1-ubuntu22.04-dtk26.04-py3.10` | 同 |

### 入口

| 入口 | 地址 | 鉴权 | 谁能用 |
|---|---|---|---|
| **平台网关(唯一对外入口)** | `http://10.254.208.35:8090/v1/embeddings`<br>`http://10.254.208.35:8090/v1/rerank`、`/v1/score` | 需要 sk | 所有能访问 `10.254.208.35` 的客户端 |
| 服务直连 | `http://10.254.213.135:8091` / `:8092` | 无 | **仅控制面主机 10.254.208.35**(防火墙白名单) |

> ⚠️ 普通客户端连不上直连地址。一律走平台网关,这样才有鉴权与用量统计。

---

## 二、密钥与环境变量

**与 Qwen3.8 对话、PaddleOCR-VL 共用同一套 sk**,在控制台 `http://10.254.208.35:8080` →
「API 密钥」新建。用量按「条数/对数」单独统计,不消耗对话的 token 配额。

| 变量 | 必填 | 含义 | 示例 |
|---|---|---|---|
| `Q38_BASE_URL` | 是 | 平台网关基地址(**到 `/v1` 为止**) | `http://10.254.208.35:8090/v1` |
| `Q38_API_KEY` | 是 | 用户 sk | `sk-q38-xxxxxxxx` |
| `Q38_TIMEOUT` | 否 | 请求超时秒数,默认 300 | `600` |

```bash
# 凭据文件(推荐)
mkdir -p ~/.dsh/credentials && chmod 700 ~/.dsh/credentials
cat > ~/.dsh/credentials/vec.env <<'EOF'
Q38_BASE_URL=http://10.254.208.35:8090/v1
Q38_API_KEY=sk-q38-xxxxxxxx
EOF
chmod 600 ~/.dsh/credentials/vec.env
set -a; . ~/.dsh/credentials/vec.env; set +a
```

---

## 三、接口规范

### 3.1 向量化 `POST /v1/embeddings`

**文本(单条或多条)**

```json
{"model": "Qwen3-VL-Embedding-8B", "input": ["文本一", "文本二"]}
```

**多模态(图片 / 图文混合)** —— 用 `messages` 形式:

```json
{
  "model": "Qwen3-VL-Embedding-8B",
  "messages": [{"role": "user", "content": [
      {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}},
      {"type": "text", "text": "Represent this document image."}
  ]}]
}
```

**可选参数**

| 参数 | 说明 |
|---|---|
| `dimensions` | 输出维度(MRL 支持 **64–4096**),不传为 4096 |
| `messages[0].role=system` | 自定义指令。**不传时默认 `Represent the user's input.`**;官方建议用英文写、按任务定制 |

**响应**

```json
{"object":"list","model":"Qwen3-VL-Embedding-8B","data":[
   {"object":"embedding","index":0,"embedding":[0.0123, -0.0456, ...]}],
 "usage":{"prompt_tokens":16,"total_tokens":16}}
```

### 3.2 重排 `POST /v1/rerank`(Jina 风格,**推荐**)

```json
{"model":"Qwen3-VL-Reranker-8B",
 "query":"海光 K100AI 推理优化",
 "documents":["文档一","文档二","文档三"]}
```

响应按原顺序返回,`relevance_score` 越大越相关:

```json
{"results":[{"index":0,"relevance_score":0.8816,"document":{...}}, ...],
 "usage":{"prompt_tokens":265}}
```

### 3.3 打分 `POST /v1/score`(单对 / 多模态)

纯文本可直接用字符串:

```json
{"model":"Qwen3-VL-Reranker-8B","text_1":"查询","text_2":"文档"}
```

**含图片时必须用 `content` 数组结构**(vLLM 的 `ScoreMultiModalParam`):

```json
{"model":"Qwen3-VL-Reranker-8B",
 "text_1":{"content":[{"type":"text","text":"一张合同或发票扫描件"}]},
 "text_2":{"content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]}}
```

响应:`{"data":[{"index":0,"object":"score","score":0.0916}], "usage":{...}}`

> ⚠️ 写成 `"text_2": {"image": "..."}` 会返回 **400 `queries field required`**,必须用 `content` 数组。

### 3.4 错误码

| HTTP | message | 原因 |
|---:|---|---|
| 401 | `missing api key` / `invalid api key` / `api key disabled` | 鉴权问题 |
| 400 | `invalid JSON body` | 请求体不是合法 JSON |
| 400 | `queries field required` | `/v1/score` 多模态结构写错(应为 `content` 数组) |
| 502 | `embedding upstream error: ...` / `rerank upstream error: ...` | 网关连不上 node135 |
| 500 | — | 上游异常,重试一次 |

---

## 四、调用示例

### 4.1 Embedding:计算相似度

```bash
curl -s -X POST "$Q38_BASE_URL/embeddings" \
  -H "Authorization: Bearer $Q38_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"Qwen3-VL-Embedding-8B","input":["海光 K100AI 推理优化","今天天气不错"]}'
```

```python
import os, math, requests
B = os.environ["Q38_BASE_URL"]; H = {"Authorization": "Bearer " + os.environ["Q38_API_KEY"]}

def embed(texts):
    r = requests.post(f"{B}/embeddings", headers=H, timeout=300,
                      json={"model": "Qwen3-VL-Embedding-8B", "input": texts})
    r.raise_for_status()
    return [x["embedding"] for x in r.json()["data"]]

vecs = embed(["海光 K100AI 推理优化", "今天天气不错"])
cos = lambda a, b: sum(x*y for x, y in zip(a, b)) / (math.sqrt(sum(x*x for x in a)) * math.sqrt(sum(y*y for y in b)))
print("维度", len(vecs[0]), "相似度", round(cos(*vecs), 4))
```

### 4.2 Embedding:图片向量化

```python
import base64
b64 = "data:image/png;base64," + base64.b64encode(open("doc.png", "rb").read()).decode()
r = requests.post(f"{B}/embeddings", headers=H, timeout=600,
                  json={"model": "Qwen3-VL-Embedding-8B", "messages": [{"role": "user", "content": [
                        {"type": "image_url", "image_url": {"url": b64}}]}]})
print(len(r.json()["data"][0]["embedding"]))     # 4096
```

### 4.3 Reranker:重排候选

```bash
curl -s -X POST "$Q38_BASE_URL/rerank" \
  -H "Authorization: Bearer $Q38_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"Qwen3-VL-Reranker-8B","query":"海光 K100AI 推理优化",
       "documents":["海光 K100AI 是国产 GPGPU,介绍 INT8 量化优化。","今天天气不错。"]}'
```

```python
def rerank(query, docs, top_k=None):
    r = requests.post(f"{B}/rerank", headers=H, timeout=300,
                      json={"model": "Qwen3-VL-Reranker-8B", "query": query, "documents": docs})
    r.raise_for_status()
    out = sorted(r.json()["results"], key=lambda x: -x["relevance_score"])
    return [(x["index"], round(x["relevance_score"], 4), docs[x["index"]]) for x in out[:top_k or len(out)]]
```

---

## 五、检索链路实战(召回 + 重排)

```python
import os, math, requests
B = os.environ["Q38_BASE_URL"]; H = {"Authorization": "Bearer " + os.environ["Q38_API_KEY"]}
EMB, RRK = "Qwen3-VL-Embedding-8B", "Qwen3-VL-Reranker-8B"

def embed(texts):
    r = requests.post(f"{B}/embeddings", headers=H, timeout=300, json={"model": EMB, "input": texts})
    r.raise_for_status(); return [x["embedding"] for x in r.json()["data"]]

def rerank(q, docs):
    r = requests.post(f"{B}/rerank", headers=H, timeout=300, json={"model": RRK, "query": q, "documents": docs})
    r.raise_for_status(); return sorted(r.json()["results"], key=lambda x: -x["relevance_score"])

def search(query, corpus, recall=50, top_k=5):
    """先向量召回 recall 条,再用 reranker 精排取 top_k"""
    qv = embed([query])[0]
    dv = embed(corpus)
    cos = lambda a, b: sum(x*y for x, y in zip(a, b)) / (math.sqrt(sum(x*x for x in a)) * math.sqrt(sum(y*y for y in b)))
    cand = sorted(range(len(corpus)), key=lambda i: -cos(qv, dv[i]))[:recall]
    ranked = rerank(query, [corpus[i] for i in cand])
    return [(cand[x["index"]], round(x["relevance_score"], 4), corpus[cand[x["index"]]]) for x in ranked[:top_k]]

corpus = [...]                      # 你的文档库
for idx, score, text in search("海光 K100AI 的量化优化", corpus):
    print(f"{score:.4f}  [{idx}] {text[:60]}")
```

> **实践建议**:召回阶段用 Embedding(快、可预建索引),重排阶段用 Reranker(准、但需要逐对前向)。
> 典型配比是 **召回 50–100 条 → 重排取 Top 5–10**。

---

## 六、性能与容量(测试数据)

> 测试环境:node135 单卡(GPU6 / GPU7),海光 vLLM 0.18.1,输入为**每请求新生成的随机文本**
> (避免前缀缓存虚高),高并发用连接池客户端。硬件基准:该卡实测 **730 GB/s 带宽、115.1 TFLOPS bf16**。

### 6.1 Embedding(Qwen3-VL-Embedding-8B)

| 场景 | 并发 | 吞吐 | P50 | P95 |
|---|---:|---:|---:|---:|
| 短文本 ~30 tok | 32 | 93.9 条/s | — | — |
| 短文本 ~30 tok | 64 | 116.8 条/s | 42 ms | 42 ms |
| 短文本 ~30 tok | 128 | 162.1 条/s | — | — |
| 短文本 ~30 tok | **256** | **170.5 条/s** | — | — |
| 短文本 ~30 tok | 384 | 175.0 条/s(**饱和**) | — | — |
| 中文本 ~173 tok | 32 | 28.3 条/s | 87 ms | 88 ms |
| 长文本 ~720 tok | 16 | 6.6 条/s | 187 ms | 198 ms |
| 单请求批量 32 条 | 8 | **147.9 条/s** | 359 ms | 363 ms |
| **单图(文档)** | 16 | **22.8 条/s** | 89 ms | 560 ms |
| **图文混合** | 8 | 22.3 条/s | 94 ms | 95 ms |

**饱和点 ≈ 并发 256–384,峰值 175 条/s(短文本)。**

### 6.2 Reranker(Qwen3-VL-Reranker-8B)

| 场景 | 并发 | 吞吐 | P50 | P95 |
|---|---:|---:|---:|---:|
| 短-短文本对 ~265 tok | 32 | 24.0 对/s | 34 ms | 35 ms |
| 短-短文本对 | 128 | 24.6 对/s | — | — |
| 短-短文本对 | **256** | **24.8 对/s(饱和)** | — | — |
| 短-长文本对 ~1102 tok | 32 | 4.4 对/s | 184 ms | 194 ms |
| **文本-图片对** | 8 | **4.3 对/s** | 341 ms | 557 ms |

**饱和点 ≈ 并发 128,峰值 24.8 对/s;单次打分 P50 仅 34 ms,交互体验好。**

### 6.3 算力利用率(相对本卡实测峰值 115.1 TFLOPS)

| 负载 | tok/s | 估算 TFLOPS | 占峰值 |
|---|---:|---:|---:|
| Embedding 短文本 | 4,448 | 73.8 | 64% |
| Embedding 中文本 | 4,895 | 81.3 | 71% |
| Embedding 长文本 | 4,779 | 79.3 | 69% |
| **Reranker 短-短对** | **6,501** | **108.0** | **94%** |
| Reranker 短-长对 | 4,823 | 80.1 | 70% |

### 6.4 容量规划建议

| 场景 | 建议配置 | 预期能力 |
|---|---|---|
| 在线单条向量化 | 并发 ≤32 | P50 42 ms,约 90 条/s |
| 离线批量灌库 | 单请求 32 条 + 并发 8 | **147.9 条/s**(单卡) |
| 在线重排 | 并发 ≤32 | P50 34 ms,约 24 对/s |
| **完整检索链路** | 1 卡 Embedding + 1 卡 Reranker | 约 **20 QPS** 的「召回 100 → 重排 100」 |
| 图片文档为主 | — | 向量化 22.8 条/s、重排 4.3 对/s,需要时加卡 |

> 需要更高吞吐:复制本部署到更多卡(实测近线性扩展),或改用 2B 版本。

---

## 七、验收实测记录

测试脚本位于 node135 `/data/models/`(`util_bench.py`、`bench_full.py`)与仓库(`highconc_bench.py`)。

### 7.1 功能验收(经平台网关,全部通过)

| # | 测试项 | 请求 | 实测结果 |
|---|---|---|---|
| 1 | 文本向量化 | `input:["海光 K100AI 推理优化","今天天气不错"]` | 2 条 **4096 维**,相似度 **0.3245**,prompt_tokens 16 ✅ |
| 2 | 多模态向量化 | 图片 + 文本 `Represent this document.` | **4096 维**,prompt_tokens **1282**(含视觉 token)✅ |
| 3 | 语义判别 | 相关 vs 无关文本 | 相关 **0.6988** / 无关 **0.3102** ✅ |
| 4 | Rerank 排序 | 1 query + 3 docs | **0.8820 > 0.0798 > 0.0058**,排序正确 ✅ |
| 5 | 多模态打分 | 文本 query vs 图片 document | 合同查询 **0.0916** vs 无关查询 **0.0202**(5× 差距)✅ |
| 6 | `/v1/score` 单对 | `text_1` / `text_2` | **0.749**,HTTP 200 ✅ |
| 7 | 模型列表 | `GET /v1/models` | 返回 4 个模型(含两个新模型)✅ |
| 8 | 控制台检索页 | `/api/vec/embed`、`/api/vec/rerank` | 维度 4096、相似度矩阵、排序结果均正确 ✅ |

### 7.2 关键实测输出

```
=== 经网关向量化(文本)===
   向量数 2 | 维度 4096 | 相似度 0.3245 | tokens 16
=== 经网关向量化(图片+文本)===
   维度 4096 | tokens 1282
=== 经网关 /v1/score ===
   score: 0.749
=== 控制台 /api/vec/embed ===
   维度 4096 条数 3 耗时 1222 ms
   相似度矩阵 [[1.0, 0.3229, 0.4569], [0.3229, 1.0, 0.3612], [0.4569, 0.3612, 1.0]]
=== 控制台 /api/vec/rerank ===
   耗时 1220 ms
   #1 0.8820 海光 K100AI 是国产 GPGPU,介绍 INT8
   #2 0.0798 PaddleOCR-VL 用于版面分析。
   #3 0.0058 今天天气不错。
```

### 7.3 与公开数据对比

公开资料中**没有**这两个模型的推理性能数据(官方技术报告 arXiv 2601.04720 只测精度;
model card 只给离线 `LLM.embed()` 示例)。同型号 K100AI 的公开性能数据仅有光合社区论坛的
Qwen3.8-27B W8A8 一例(4 卡 TP4,decode 118–131 tok/s);按其速率反推每卡有效带宽 ≈810 GB/s,
与本机实测拷贝带宽 730 GB/s 同量级,**说明本节点硬件与公开数据同级**。

---

## 八、平台内的功能

| 位置 | 功能 |
|---|---|
| 控制台 → 「检索」→ **向量化** | 输入多行文本(可选配一张图片)→ 得到 4096 维向量、前 8 维预览、**两两余弦相似度矩阵**(热力着色) |
| 控制台 → 「检索」→ **重排** | 输入 query 与多行 documents → 返回排序后的分数条列表 |
| 控制台 → 「集群状态」 | 新增两张服务卡(向量化 / 重排),显示在线状态、卡位、端口、24h 用量 |
| 控制台 → 「模型与路由」 | 平台模型总览表已列出这两个模型 |
| 控制台 → 「OCR 服务」 | OCR 页保持独立,用量按页数统计 |
| 用量统计 | 存 `vec_usage` 表,按「条数/对数」统计,**不消耗对话 token 配额** |

网关健康检查会带出全部上游:

```json
{"services":{"chat":"http://127.0.0.1:30000","ocr":"http://10.254.213.135:8090",
             "embedding":"http://10.254.213.135:8091","rerank":"http://10.254.213.135:8092"}}
```

---

## 九、运维与排障

### 9.1 服务自检

```bash
# node135 上
curl -s http://127.0.0.1:8091/health      # Embedding
curl -s http://127.0.0.1:8092/health      # Reranker

# 控制面
curl -s http://127.0.0.1:8090/health | python3 -m json.tool | grep -A5 services
```

### 9.2 重启

```bash
# 单服务(不需要重建容器)
sudo docker restart q3vle     # 约 4-6 分钟(含 torch.compile)
sudo docker restart q3vlr

# 一键重建两个服务
bash /data/models/deploy_q3vl_embed_rerank.sh
```

### 9.3 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `502 embedding upstream error` | node135 服务没起 / 防火墙 | 在 135 上 `curl :8091/health`;检查 firewalld 白名单 |
| `400 queries field required` | `/v1/score` 多模态结构写错 | 用 `{"content":[...]}` 而不是 `{"image":...}` |
| 返回 `未知模型 'Qwen3-VL-Embedding-8B'` | 网关路由被旧的 `/v1/embeddings` 抢先匹配 | 已修复(旧路由转发到 SGLang Router);若复现检查 `gateway.py` 中 `@app.post("/v1/embeddings")` 是否只有一处 |
| 吞吐远低于文档值 | 客户端瓶颈(并发 >64 用 urllib) | 改用连接池客户端(`requests.Session`) |
| 吞吐虚高 | 前缀缓存命中 | 基准测试必须用独立输入 |
| `ImportError: librocm_smi64.so.2` | 容器内 `LD_LIBRARY_PATH` 未追加 | `export LD_LIBRARY_PATH=/opt/dtk/.hyhal/rocm_smi/lib:$LD_LIBRARY_PATH`(追加,不要覆盖) |

---

## 附录

### A. 服务与文件清单

| 位置 | 内容 |
|---|---|
| node135 `/data/models/q3vle`、`q3vlr` | 模型权重(16 GB / 17 GB) |
| node135 `/data/models/start_q3vle.sh`、`start_q3vlr.sh` | 启动脚本(端口/卡可改) |
| node135 `/data/models/deploy_q3vl_embed_rerank.sh` | 一键部署 |
| 控制面 `~/q38ui/gateway.py` | 路由:`/v1/embeddings`、`/v1/rerank`、`/v1/score` |
| 控制面 `~/q38ui/q38store.py` | `vec_usage` 表 + `record_vec()` / `vec_summary()` |
| 控制面 `~/q38ui/dashboard.py` | 「检索」页 + 集群状态卡片 + `/api/vec/*` |
| 仓库 | `Qwen3-VL双模型部署记录.md`、`Qwen3-VL双模型性能测试与公开数据对比.md`、本手册 |

### B. 相关文档

| 文档 | 内容 |
|---|---|
| `Qwen3-VL双模型部署记录.md` | 镜像选型、依赖测试、部署命令、踩坑记录 |
| `Qwen3-VL双模型性能测试与公开数据对比.md` | 完整压测数据、算力利用率分析、公开数据对比 |
| `平台OCR调用使用手册.md` | 文档识别(OCR)的调用手册 |
