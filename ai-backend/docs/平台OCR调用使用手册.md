# 平台 OCR 调用使用手册

> 适用对象:需要通过 API / 脚本 / Skills 调用平台文档识别能力的开发者
> 版本:v1.0(2026-09-18)
> 模型:`PaddleOCR-VL-1.6-0.9B` · 服务节点:node135(8 × 海光 K100AI DCU)

---

## 目录

- [一、能力概述](#一能力概述)
- [二、获取密钥与配置环境变量](#二获取密钥与配置环境变量)
- [三、HTTP 接口规范](#三http-接口规范)
- [四、调用示例](#四调用示例)
- [五、印章与手写体识别](#五印章与手写体识别)
- [六、与 Skills 集成(通用 Agent Skills 开发)](#六与-skills-集成通用-agent-skills-开发)
- [七、性能基线与并发建议](#七性能基线与并发建议)
- [八、运维与排障](#八运维与排障)
- [附录 A:最小可用 Skill 全文](#附录-a最小可用-skill-全文)
- [附录 B:字段与错误码总表](#附录-b字段与错误码总表)

---

## 一、能力概述

PaddleOCR-VL 把**图片**解析成**结构化 Markdown**:版面分析(标题/正文/表格/图/公式)+ 文字识别,
表格输出为 `<table>` HTML,同时返回每个版面块的类型、文本与像素坐标。

| 项目 | 说明 |
|---|---|
| 模型 | PaddleOCR-VL-1.6-0.9B(0.9B,文档专用 VLM) |
| 能力 | 版面检测 + 文本/表格/公式识别 → Markdown + 版面块 |
| 输入 | PNG / JPG / JPEG / WebP / BMP(**不支持 PDF 直接上传**,需先转图) |
| 输出 | `markdown`(正文)、`blocks`(版面块与 bbox)、`pages`、耗时 |
| 单页延迟 | 约 **1.5 s**(P50 1.52 s / P95 1.61 s) |
| 批量吞吐 | **约 419 页/分**(512 页批量,并发 96) |
| 精度 | 字符错误率 **CER 2.63%**,表格单元格 12/12 正确 |
| 部署 | node135 全部 8 张 DCU:8 个 vLLM VL 实例 + 80 个布局进程、常驻 80 工作进程 |

### 两个入口,客户端只用平台网关

| 入口 | 地址 | 鉴权 | 谁能用 |
|---|---|---|---|
| **平台网关(唯一对外入口)** | `http://10.254.208.35:8090/v1/ocr` | 需要 sk | 所有能访问 `10.254.208.35` 的客户端 |
| 服务直连 | `http://10.254.213.135:8090/v1/ocr` | 无 | **仅控制面主机 10.254.208.35**(node135 防火墙只放通该 IP) |

> ⚠️ **普通客户端连不上 135 直连地址**(会被防火墙丢弃)。请一律走平台网关。
> 走网关除了鉴权,还会计入平台的用量统计(可在控制台「OCR 服务」页查看)。

> 💡 OCR 用量**不消耗** sk 的 token 配额。配额(`quota_tokens`)只对 Qwen3.8 对话接口生效,
> OCR 用量单独记录在 `ocr_usage` 表,按「页数 / 输出字符数 / 延迟」统计。

---

## 二、获取密钥与配置环境变量

### 2.1 申请 sk(用户密钥)

1. 浏览器打开控制台 **`http://10.254.208.35:8080`**
2. 用平台账号登录(管理员 `admin`,如无账号请联系平台管理员)
3. 进入 **「API 密钥」** 页 → **新建密钥**,填写名称与备注
4. **密钥只在创建时完整显示一次**,请立即复制保存(形如 `sk-q38-` + 48 位十六进制)

> 该密钥与 Qwen3.8 对话服务**共用同一套体系**:同一个 sk 既能调 `/v1/chat/completions`,
> 也能调 `/v1/ocr`。密钥可在控制台随时停用/启用。

### 2.2 环境变量约定(必读)

**所有客户端代码与 Skill 都必须通过下面两个环境变量取地址和密钥,禁止硬编码。**

| 变量 | 必填 | 含义 | 示例值 |
|---|---|---|---|
| `Q38_OCR_BASE_URL` | **是** | 平台 OCR 接口**基地址**,到 `/v1` 为止(**不要带 `/ocr`**,不要带结尾 `/`) | `http://10.254.208.35:8090/v1` |
| `Q38_API_KEY` | **是** | 用户 sk | `sk-q38-xxxxxxxxxxxxxxxx` |
| `Q38_OCR_TIMEOUT` | 否 | 单次请求超时(秒),默认 `300`;大批量建议 `600` | `600` |

> 脚本拼接规则:`{Q38_OCR_BASE_URL}/ocr` = `http://10.254.208.35:8090/v1/ocr`。

### 2.3 三种填写方式

#### 方式 1:当前 shell 临时生效(调试用)

```bash
export Q38_OCR_BASE_URL="http://10.254.208.35:8090/v1"
export Q38_API_KEY="sk-q38-xxxxxxxxxxxxxxxx"
```

#### 方式 2:写进 shell 配置(每次登录自动加载)

```bash
cat >> ~/.bashrc <<'EOF'
export Q38_OCR_BASE_URL="http://10.254.208.35:8090/v1"
export Q38_API_KEY="sk-q38-xxxxxxxxxxxxxxxx"
EOF
source ~/.bashrc
```

#### 方式 3:凭据文件(**推荐**,不进 shell 历史、权限可控、Skill 直接复用)

```bash
mkdir -p ~/.dsh/credentials && chmod 700 ~/.dsh/credentials

cat > ~/.dsh/credentials/ocr.env <<'EOF'
Q38_OCR_BASE_URL=http://10.254.208.35:8090/v1
Q38_API_KEY=sk-q38-xxxxxxxxxxxxxxxx
EOF

chmod 600 ~/.dsh/credentials/ocr.env

# 加载到当前 shell
set -a; . ~/.dsh/credentials/ocr.env; set +a
```

这种方式和本机已有的 `~/.dsh/credentials/monitoring.env` 约定一致,建议统一使用。

### 2.4 自检

```bash
curl -s "$Q38_OCR_BASE_URL/ocr/health" -H "Authorization: Bearer $Q38_API_KEY"
```

正常返回:

```json
{"status":"ok","workers":80,"idle":80,"stats":{"requests":264,"pages":266,"errors":0,"busy":0}}
```

---

## 三、HTTP 接口规范

### 3.1 端点

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `POST` | `/v1/ocr` | 需要 | 文档识别 |
| `GET` | `/v1/ocr/health` | 需要 | OCR 服务状态(工作进程数、累计页数、错误数) |
| `GET` | `/v1/models` | 需要 | 模型列表,含 `PaddleOCR-VL-1.6-0.9B` |

鉴权头:`Authorization: Bearer <sk>`。

### 3.2 请求体(三种形式,任选)

**形式 A — JSON 单页**

```json
{"image": "<base64>"}
```

`image` 支持三种写法:裸 base64、`data:image/png;base64,...`、`http(s)://...`(由服务端去取图)。

**形式 B — JSON 批量**

```json
{"images": ["<base64>", "<base64>", "<base64>"]}
```

返回的 `results[]` 顺序与请求一致。

**形式 C — multipart 文件上传**

字段名任意;带多个文件字段即为多页。

```bash
curl -X POST "$Q38_OCR_BASE_URL/ocr" \
  -H "Authorization: Bearer $Q38_API_KEY" \
  -F "file=@doc.png"
```

### 3.3 响应

```json
{
  "model": "PaddleOCR-VL-1.6-0.9B",
  "pages": 1,
  "elapsed_ms": 1583,
  "total_ms": 1600,
  "markdown": "2026年度技术白皮书\n\n一、总体概述\n\n…<table>…",
  "blocks": [
    {"label": "title", "content": "2026年度技术白皮书", "bbox": [88, 60, 1112, 118]}
  ],
  "results": [
    {"markdown": "…", "blocks": [ … ], "width": 1200, "height": 1600}
  ],
  "width": 1200,
  "height": 1600
}
```

单页请求时顶层直接展开该页字段(附带 `results`);多页请求时 `markdown` 为各页拼接。

### 3.4 错误码

| HTTP | message | 原因与处理 |
|---:|---|---|
| 401 | `missing api key` | 没带 `Authorization` 头 → 检查凭据文件是否已 `source` |
| 401 | `invalid api key` | sk 不存在 → 从控制台重新复制 |
| 401 | `api key disabled` | 密钥被停用 → 联系管理员 |
| 400 | `invalid JSON body` | 请求体不是合法 JSON |
| 400 | `invalid base64 image data` | `image` 不是合法 base64(常见于传了 PDF/二进制直传) |
| 400 | `failed to fetch image url: ConnectionError` | URL 形式取图失败(地址不可达) |
| 400 | `no image provided` | 没带 `image`/`images`,或 multipart 里没有文件字段 |
| 400 | `... 'cv' worker: Image read Error` | 内容不是有效图片 → `file 路径` 确认格式 |
| 405 | — | 用了 GET;该接口只接受 POST |
| 502 | `ocr upstream error: ...` | 网关连不上 OCR 服务 → 查服务是否在线 |
| 500 | — | 服务端异常 → 重试一次;持续失败查服务端日志 |

错误响应统一为:

```json
{"error": {"message": "...", "type": "invalid_request_error", "code": 401}}
```

---

## 四、调用示例

### 4.1 curl

```bash
# 单页(base64)
B64=$(base64 -w0 doc.png)
curl -s -X POST "$Q38_OCR_BASE_URL/ocr" \
  -H "Authorization: Bearer $Q38_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"image\":\"$B64\"}" | python3 -m json.tool | head -20

# 批量(多张,一次请求)
python3 - <<'PY'
import base64, json, os, urllib.request
files = ["a.png", "b.png"]
req = urllib.request.Request(
    os.environ["Q38_OCR_BASE_URL"] + "/ocr",
    data=json.dumps({"images": [base64.b64encode(open(f, "rb").read()).decode()
                               for f in files]}).encode(),
    method="POST")
req.add_header("Content-Type", "application/json")
req.add_header("Authorization", "Bearer " + os.environ["Q38_API_KEY"])
with urllib.request.urlopen(req, timeout=600) as r:
    print(json.loads(r.read())["markdown"])
PY
```

### 4.2 Python(requests)

```python
import base64, os, requests

BASE = os.environ["Q38_OCR_BASE_URL"]
KEY  = os.environ["Q38_API_KEY"]
H    = {"Authorization": f"Bearer {KEY}"}

def ocr_image(path: str) -> str:
    """识别单张图片,返回 Markdown"""
    b64 = base64.b64encode(open(path, "rb").read()).decode()
    r = requests.post(f"{BASE}/ocr", headers=H, json={"image": b64}, timeout=300)
    if r.status_code != 200:
        raise RuntimeError(f"OCR 失败 {r.status_code}: {r.text[:200]}")
    return r.json()["markdown"]

def ocr_batch(paths: list[str]) -> list[dict]:
    """批量识别,返回每页明细(顺序一致)"""
    imgs = [base64.b64encode(open(p, "rb").read()).decode() for p in paths]
    r = requests.post(f"{BASE}/ocr", headers=H, json={"images": imgs}, timeout=600)
    r.raise_for_status()
    return r.json()["results"]

print(ocr_image("doc.png"))
```

### 4.3 PDF 处理

PDF **不能直接上传**,先转图片再识别:

```bash
# poppler-utils
pdftoppm -r 200 -png input.pdf /tmp/page      # 生成 /tmp/page-1.png, /tmp/page-2.png …
python3 ocr.py /tmp/page-*.png --out ./out
```

### 4.4 现成 CLI(零依赖,推荐直接用)

仓库与 Skill 里带了一个纯标准库实现的 CLI:

```bash
python3 ocr.py check                          # 连通性 + 鉴权自检
python3 ocr.py doc.png                        # Markdown → stdout
python3 ocr.py --json doc.png                 # 完整 JSON
python3 ocr.py --out ./out page*.png          # 批量,每页落 .md + .json
```

---

## 五、印章与手写体识别

### 5.1 结论速查

| 内容类型 | 默认能识别吗 | 需要调什么 |
|---|---|---|
| 印刷正文 / 表格 / 公式 | ✅ 可以 | 无需调整 |
| **印章** | ❌ **默认完全不识别**(返回空) | 必须显式加 `"seal": true` |
| **手写体** | ✅ 可以,无需任何参数 | 字迹很小时可提高 `ocr_min_pixels` |

### 5.2 印章

**为什么默认不识别**:版面模型 `PP-DocLayoutV2` 能把印章检测成独立的 `seal` 版面块,
但 PaddleOCR-VL-1.6 的默认配置是 `use_seal_recognition: False` —— 此时印章块被归入
"图片类"版面块,**原样保留在结果里、不送识别**,所以 `seal` 块的 `content` 是空的。

开启方式:请求体里加 `"seal": true`。

实测(合成合同,两枚公章 = 公司名环形文字 + "合同专用章" + 编号):

| 配置 | 印章文字输出 |
|---|---|
| 默认(不开) | `(空)` |
| `"seal": true` | `海光信息技术股份有限公司 / 合同专用章 / 1101080008888` ✅ **完全正确** |

#### 关键参数是 `seal_min_pixels`,不是 `seal_max_pixels`

这一条容易搞反,实测对照如下:

| `seal_min_pixels` | 印章 1(期望"海光信息技术股份有限公司") | 印章 2(期望"北京智算科技有限公司") |
|---|---|---|
| `112896`(默认,≈336×336) | ❌ `海合同专用章` | ❌ `北京北浜科技有限公司` |
| **`400000`(≈632×632,平台默认)** | ✅ 完全正确 | `北京北固真智算科技有限公司`(多 2 字) |
| `800000` | ✅ 完全正确 | `北京北昇智算科技有限公司` |
| `1200000` | ❌ `通鸿光信息技术股份有限公司` | ❌ `北京算智科技有限公司` |

**原因**:印章裁剪图本身很小(约 330×330 像素),`seal_min_pixels` 决定它被**放大**到多少像素
再送模型。默认值太小 → 环形弧线文字糊在一起、只剩零星几个字;放大到 632×632 后弧线才能分开;
继续放大(1.2M)反而过放大、整体退化。

#### 可选参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `seal` | `false` | 开启印章识别 |
| `seal_min_pixels` | 开启时自动取 `400000` | 印章裁剪图放大下限(**最有效的旋钮**) |
| `seal_max_pixels` | `1003520` | 上限,一般不用改 |
| `chart` | `false` | 顺带说明:图表解析同理,默认也关 |

#### 代价(重要)

印章块要按 632×632 放大后再单独发一次 VL 请求,开销明显:

| 场景 | 单页延迟 | 批量吞吐(64 页 / 并发 32) |
|---|---|---|
| 含章文档,不开 `seal` | 1.06 s | **461.6 页/分**(130 ms/页) |
| 含章文档,开 `seal` | 6.31 s | **231.0 页/分**(260 ms/页) |

> 含章文档吞吐**约减半**。**只在确实需要读印章文字时开启**;不要为了"保险"对所有文档默认开启。

#### 压字场景(印章盖在文字上)——难点

实测把印章直接盖在公司名和日期上:

| 配置 | 印章输出 |
|---|---|
| 不开 | `(空)` |
| 开 `seal` | `有限公司 / 合同专用章 / 1101080008888` —— 环形公司名大量丢失 |

建议:
- 印章文字只作**辅助校验**(如核对编号 `1101080008888` 这类数字,实测稳定正确),
  不要作为关键字段的唯一来源;
- 公司名、日期等关键要素**以正文识别结果为准**(印章压字不影响正文块识别,实测正文一字不差);
- 若印章和正文都需要,可**发两次请求**:一次常规(拿正文),一次 `seal: true`(拿印章)。

### 5.3 手写体

**默认配置即可识别,不需要任何参数。**

实测样张(毛笔楷书手写体,中文 + 数字 + 标点混排):

| | 内容 |
|---|---|
| 期望 | `会议记录 2026-09-18 / 今天讨论了三件事:一是把OCR服务的吞吐 / 提到了四百页每分钟;二是印章和手写体 / 还需要再验证一下识别效果;三是下周 / 要把这套能力接到平台上给大家用。` |
| 实际 | **逐字一致**(日期 `2026-09-18`、冒号、分号全部正确) |

难样本(整体旋转 5° + 光照不均 + 噪声 + 降采样,模拟手机拍照):

| 配置 | 结果 |
|---|---|
| 默认 | 正文 4 行**全部正确**,但**首行标题丢失** |
| `ocr_max_pixels: 2000000` | 同上,无改善 |
| 开启文档预处理(方向矫正 + 去畸变) | 找回了标题行,但**整页被误判成表格**(结果被 `<table>` 包裹) |

#### 可选参数

| 参数 | 说明 |
|---|---|
| `ocr_min_pixels` | 正文档放大下限;字迹小、笔画粘连时提高(平台默认 `112896`) |
| `ocr_max_pixels` | 上限,默认 `1003520`;实测提高对清晰手写无增益 |
| 文档预处理 | `use_doc_orientation_classify` / `use_doc_unwarping`:DCU 上**官方不在支持白名单**,需 `PADDLE_PDX_DISABLE_DEV_MODEL_WL=true` 绕过,且会改变版面判型,慎用 |

#### 手写体注意事项

- 本次验证用的是**手写体字体样张**;真实**连笔草书 / 行草**未做专项测试,建议用实际样张先验证;
- 手写数字与英文混排表现良好(实测日期完全正确);
- 纸张有格线、字距紧凑或墨色不均时,优先提高 `ocr_min_pixels`;
- 手写内容与印刷内容混排时,版面块可能被合并成一个 `content` 块,需要按行自行切分。

### 5.4 调用示例

```bash
# 开启印章识别(用平台默认分辨率 400000)
curl -X POST "$Q38_OCR_BASE_URL/ocr" \
  -H "Authorization: Bearer $Q38_API_KEY" -H "Content-Type: application/json" \
  -d "{\"image\":\"$B64\",\"seal\":true}" \
| python3 -c "import json,sys; d=json.load(sys.stdin); print([b['content'] for b in d['blocks'] if b['label']=='seal'])"

# 自定义印章分辨率
-d "{\"image\":\"$B64\",\"seal\":true,\"seal_min_pixels\":800000}"

# 手写体:字迹很小时提高正文档分辨率
-d "{\"image\":\"$B64\",\"ocr_min_pixels\":300000}"
```

```python
import base64, os, requests

BASE = os.environ["Q38_OCR_BASE_URL"]
H = {"Authorization": "Bearer " + os.environ["Q38_API_KEY"]}
b64 = base64.b64encode(open("contract.png", "rb").read()).decode()

r = requests.post(f"{BASE}/ocr", headers=H,
                  json={"image": b64, "seal": True, "seal_min_pixels": 400000},
                  timeout=300)
d = r.json()
print("正文:", d["markdown"][:200])
print("印章:", [b["content"] for b in d["blocks"] if b["label"] == "seal"])
```

也可把选项放进 `options` 对象:`{"image": "...", "options": {"seal": true}}`。

用 Skill 自带的 CLI 更简单:

```bash
python3 <skill_dir>/scripts/ocr.py 合同.png                 # 默认:不识别印章,但会提示"检测到 N 处印章"
python3 <skill_dir>/scripts/ocr.py --seal 合同.png          # 开启印章识别
python3 <skill_dir>/scripts/ocr.py --seal --seals-only 合同.png   # 只打印印章文字
python3 <skill_dir>/scripts/ocr.py --seal --seal-min-pixels 800000 合同.png
```
multipart 上传时用表单字段 `@seal=true` 传递。

> **封装成 Skill 时怎么让 agent 自动判断该不该开?** 见 [6.7 让 agent 智能决定是否开启印章识别](#67-让-agent-智能决定是否开启印章识别)。

### 5.5 服务端已支持的选项一览

`GET $Q38_OCR_BASE_URL/ocr/health` 会返回 `options` 字段,列出当前支持的全部可调项:

```
seal, chart, min_pixels, max_pixels, ocr_min_pixels, ocr_max_pixels,
table_min_pixels, table_max_pixels, seal_min_pixels, seal_max_pixels,
chart_min_pixels, chart_max_pixels
```

---

## 六、与 Skills 集成(通用 Agent Skills 开发)

> 本章按 **通用 Agent Skills 规范**写,不绑定任何特定 Agent 平台。
> 文末附「DSH 平台变体」小节,给出在 DeepSeek Harness 上的落地差异。

### 6.1 Agent Skills 是什么

Agent Skills 是 Anthropic 提出并已被多家 Agent 采用的**开放约定**:把"某类任务该怎么做"的
领域知识沉淀成一个可被按需加载的模块。本质就是 **一个文件夹 + 一个 `SKILL.md`**,
不依赖任何 SDK、不需要写代码接进框架。

**它的核心机制是"渐进式加载",这决定了你该怎么写:**

```
Agent 启动
  └─ 只把所有 skill 的 name + description 加载进系统提示词(很轻)
       └─ 当某个 description 被判定与当前任务相关
            └─ 才读取该 skill 的完整 SKILL.md 正文
                 └─ 需要时才去读 references/ 里的详细资料
                      └─ scripts/ 里的脚本只"被执行",不加载进上下文
```

由此得到两条最重要的写作原则:

1. **`description` 决定这个 skill 会不会被想起来** —— 写清"做什么 + 什么时候用 + 用户可能怎么说";
2. **正文决定被想起来之后做得好不好**,而详细资料应下沉到 `references/`,可执行逻辑下沉到 `scripts/`,
   否则每次触发都把大量无关内容塞进上下文。

**与几种相邻技术的区别:**

| | Agent Skill | MCP Server | Function Calling |
|---|---|---|---|
| 形态 | Markdown 文件夹 | 常驻服务进程 | API schema |
| 内容 | 流程 / 规范 / 示例 + 可选脚本 | 工具与资源 | 函数签名 |
| 解决的问题 | 把"怎么做某件事"的领域知识交给 Agent | 让 Agent 连上外部系统 | 让模型精确调用某个动作 |
| 本项目的用法 | **本章:把 OCR 调用流程与判定规则交给 Agent** | 可选:把平台包成 MCP 工具 | 可选:用 SDK 时包成 function |

### 6.2 通用规范

#### frontmatter 字段

| 字段 | 必需 | 约束 | 说明 |
|---|---|---|---|
| `name` | ✅ | ≤64 字符,只能小写字母、数字、`-`,首尾不能是 `-` | 唯一标识,建议与目录名一致,如 `ocr-doc-parsing` |
| `description` | ✅ | ≤1024 字符,非空 | **做什么 + 何时使用**;唯一被预加载的内容,写得越具体触发越准 |
| `license` | — | — | 许可证名称或指向随 skill 附带的许可证文件 |
| `compatibility` | — | ≤500 字符 | 环境与依赖说明(运行时版本、系统包、网络权限等) |
| `metadata` | — | 键值对 | 自定义元数据,如作者、版本号 |
| `allowed-tools` | — | 空格分隔 | 允许使用的工具列表(实验性) |

> **实测结论(本项目已验证)**:在本环境的 DeepSeek Harness 上,只要 `name` / `description` 合规,
> 额外写上 `license` / `compatibility` / `metadata` 这些通用字段,**skill 仍能正常被发现与加载**。
> 也就是说**一份 SKILL.md 可以同时满足通用规范和各平台实现,不必分叉维护**。

#### 目录结构

```
my-skill/
├── SKILL.md          # 必需:元数据 + 指令
├── scripts/          # 可选:可执行代码(只执行,不加载进上下文)
│   └── ocr.py
├── references/       # 可选:详细参考资料(按需读取)
│   └── api.md
└── assets/           # 可选:模板、图片等静态资源
```

分工原则(避免上下文膨胀):

| 内容 | 放哪 |
|---|---|
| 核心规则、判定逻辑、必守约束 | `SKILL.md` 正文 |
| 详细接口文档、字段总表、长清单 | `references/` |
| 可执行逻辑(调用、解析、批处理) | `scripts/` |

#### SKILL.md 正文的三段式

无论做什么 skill,正文至少覆盖这三块:

1. **使用场景** —— 什么情况下用、什么情况下**不要**用
2. **执行步骤** —— 让 Agent 照着做就能得到正确结果(含可复制的命令)
3. **边界与排障** —— 约束、限制、常见错误与处理

### 6.3 本 OCR Skill 的目录骨架

```
ocr-doc-parsing/
├── SKILL.md              # frontmatter(标准字段)+ 调用流程 + 印章判定规则
├── scripts/
│   └── ocr.py            # 零依赖 CLI:check / 识别 / --seal / --out
└── references/
    └── api.md            # HTTP 接口速查、可调选项表、错误码表
```

对应关系:`SKILL.md` 只放"什么时候调、怎么调、要不要开印章";接口细节全部下沉到 `references/api.md`;
实际调用逻辑封进 `scripts/ocr.py`,Agent 只执行不加载。

### 6.4 凭据与环境变量(通用做法)

**铁律:地址和密钥一律从环境变量读,绝不写进 SKILL.md 或脚本。**

| 变量 | 必填 | 含义 | 示例 |
|---|---|---|---|
| `Q38_OCR_BASE_URL` | 是 | 平台 OCR 接口基地址(到 `/v1` 为止,**不带 `/ocr`**) | `http://10.254.208.35:8090/v1` |
| `Q38_API_KEY` | 是 | 用户 sk | `sk-q38-xxxxxxxx` |
| `Q38_OCR_TIMEOUT` | 否 | 单次请求超时秒数,默认 300 | `600` |

**填写方式**(三选一,推荐第 3 种):

```bash
# 1) 当前 shell 临时生效
export Q38_OCR_BASE_URL="http://10.254.208.35:8090/v1"
export Q38_API_KEY="sk-q38-xxxxxxxx"

# 2) 写进 shell 配置(每次登录自动加载)
cat >> ~/.bashrc <<'EOF'
export Q38_OCR_BASE_URL="http://10.254.208.35:8090/v1"
export Q38_API_KEY="sk-q38-xxxxxxxx"
EOF

# 3) 凭据文件(推荐:不进 shell 历史、权限可控、脚本按需 source)
mkdir -p ~/.dsh/credentials && chmod 700 ~/.dsh/credentials
cat > ~/.dsh/credentials/ocr.env <<'EOF'
Q38_OCR_BASE_URL=http://10.254.208.35:8090/v1
Q38_API_KEY=sk-q38-xxxxxxxx
EOF
chmod 600 ~/.dsh/credentials/ocr.env
set -a; . ~/.dsh/credentials/ocr.env; set +a     # 加载
```

把依赖声明写进 `compatibility`,让 Agent 一眼知道前置条件:

```yaml
compatibility: 需要 Python 3.9+;网络可达平台网关;环境变量 Q38_OCR_BASE_URL 与 Q38_API_KEY
```

凭据文件路径不必是 `~/.dsh/credentials/`(那是本环境的约定),跨平台时可换成
`~/.config/<your-app>/ocr.env`,**关键是在 SKILL.md 里写清楚"变量缺失时去哪里配"**。

### 6.5 SKILL.md 写法模板(通用字段)

```markdown
---
name: ocr-doc-parsing
description: |
  调用平台上的 PaddleOCR-VL 文档识别能力,把图片/扫描件/PDF 页面转成 Markdown(含表格与公式)。
  当用户提供图片、截图、扫描件或文档页面,需要提取文字/表格/版面结构,或需要批量把图片转成
  Markdown 时使用。触发词:OCR、文字识别、图片转文字、扫描件识别、文档解析、表格提取、
  印章识别、公章、手写体识别、PaddleOCR。
license: internal
compatibility: 需要 Python 3.9+;网络可达平台网关;环境变量 Q38_OCR_BASE_URL 与 Q38_API_KEY
metadata:
  author: platform-team
  version: "1.1"
---

# PaddleOCR-VL 文档识别

## 凭据与环境变量
(三个变量 + 三种填写方式 + 缺失时的提示)

## 执行步骤
1. 先自检 `python3 <skill_dir>/scripts/ocr.py check`
2. 识别   `python3 <skill_dir>/scripts/ocr.py 图片路径`
3. 结果呈现:只回 Markdown,不要把整段 JSON 原样贴出

## 印章决策(见 6.7)
## 关键约束
## 排障
```

**`description` 的写法要点**(决定触发率):

1. **写清"做什么 + 什么时候用"**,两段都要有;
2. **把用户真实说法和关键词都列进去**(口语:"帮我把这张图转成文字";术语:"OCR""版面还原");
3. **说明不适用的场景**,避免误触发(例如"通用图片理解不适用");
4. **控制在 1024 字符内**,2–4 句为宜,太长会稀释注意力。

### 6.6 调用脚本的写法(scripts/ 里的可执行逻辑)

`scripts/ocr.py` 骨架(完整版见示例 bundle):

```python
import base64, json, os, sys, urllib.error, urllib.request

BASE = (os.environ.get("Q38_OCR_BASE_URL") or "").rstrip("/")
KEY  = os.environ.get("Q38_API_KEY") or ""
TIMEOUT = float(os.environ.get("Q38_OCR_TIMEOUT") or "300")

def _post(path, payload, ctype):
    req = urllib.request.Request(BASE + path, data=payload, method="POST")
    req.add_header("Content-Type", ctype)
    if KEY:
        req.add_header("Authorization", "Bearer " + KEY)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:    msg = json.loads(body)["error"]["message"]
        except Exception: msg = body[:300]
        return e.code, {"error": {"message": msg}}
```

要点:

- **优先用标准库**(`urllib`),避免目标机器没装 `requests`,降低 skill 的部署门槛;
- 环境变量缺失时给**明确的配置指引**(而不是抛 `KeyError`),Agent 才能自助修复;
- 上游错误解析成人类可读的一句话,并**在 stderr 给出决策提示**(如"检测到印章但未识别");
- 约定退出码:`0` 成功 / `1` 参数或环境变量错误 / `2` 上游错误;
- **路径不要写死**:用 `<skill_dir>/scripts/...` 引用,或在脚本内基于 `__file__` 推导。

### 6.7 让 agent 智能决定是否开启印章识别

**问题**:印章默认不识别,开启后含章文档吞吐约减半(461 → 231 页/分)。
如果 SKILL.md 里只写一句"要读印章就加 `--seal`",Agent 的行为只会走向两个极端 ——
要么永远不开(印章读不到),要么为"保险"全部开启(白白慢一倍)。

**解决思路:把判定依据写进 SKILL.md,让 Agent 有可依据的信号,而不是靠猜语义。** 四个关键设计:

#### ① 给明确的触发词,不要让 Agent 猜

直接列出 **必须开启** 的措辞:

> 印章、公章、图章、戳、盖章、验章、用章、章上的字、骑缝章、合同章、法人章、印章编号;
> 英文 seal / stamp / chop

同时在 `description` 里也带上"印章""公章"等词,提高这条能力被想起来的概率。

#### ② 给"免费探测"信号(最关键的一点)

默认识别就会返回 `label == "seal"` 的版面块(**内容为空,但足以证明这页有章**)。
也就是说"这页有没有印章"是**免费**得到的,根本不需要开 `--seal` 去试。

把这个信号写进 SKILL.md,并让 CLI 主动暴露它 —— 示例 bundle 的 `scripts/ocr.py` 在默认路径下会打印:

```
⚠ 检测到 2 处印章,但未识别(seal 块内容为空)。需要印章文字请加 --seal 重新识别。
```

Agent 看到这行就知道:有章、但没读;要不要读交给用户决定。没有这行说明本来没章,开了也白开。

#### ③ 给"两遍法",而不是二选一

批量场景下"全开"和"全不开"都不对。把流程写进 SKILL.md:

```
第 1 遍:默认识别(便宜)  → 正文/表格 + 印章块数量
   ├─ 用户没提印章、也不需要印章字段 → 结束
   ├─ 第 1 遍没检测到印章            → 结束(开了也没用)
   └─ 用户需要印章文字且有章         → 第 2 遍:只对含章页开 --seal 重跑
```

配套给出可直接复制的命令(先全量落盘,再从 JSON 里筛含章页,只重跑那几页):

```bash
python3 <skill_dir>/scripts/ocr.py --out ./out pages/*.png
python3 - <<'PY'
import glob, json
for f in glob.glob("./out/*.json"):
    d = json.load(open(f))
    if any(b.get("label") == "seal" for b in d.get("blocks", [])):
        print(f)          # 这些就是含章页
PY
python3 <skill_dir>/scripts/ocr.py --seal --seals-only --out ./out_seal 含章页.png
```

效果:一批 200 页里只有 5 页有章时,只有那 5 页付 2 倍代价,整体吞吐几乎不受影响。

#### ④ 给成本数字,让 Agent 有取舍依据

写"开启印章识别"不如写"开启会让含章文档慢约一倍(461 → 231 页/分)"——
模型拿到量化代价后,才会倾向于"先问用户"而不是"默默全开"。

#### 决策速查表(直接抄进 SKILL.md)

| 场景 | 是否开 `--seal` | 做法 |
|---|---|---|
| 用户明确要读印章 / 核对印章 | ✅ 开 | 直接 `--seal`;只看印章可加 `--seals-only` |
| 用户要合同正文,没提印章 | ❌ 不开 | 默认识别;若提示"检测到印章",**告知用户有章**并询问是否需要印章文字 |
| 批量提取正文(几十上百页) | ❌ 不开 | 默认识别;确需印章时按两遍法只重跑含章页 |
| 页面里根本没有印章 | ❌ 不开 | 开了也读不出东西,纯浪费约 2 倍算力 |
| 印章压在正文上 | ⚠️ 谨慎 | 环形公司名会大量丢失,只适合辅助校验;关键字段以正文为准 |
| 用户问"这页有没有盖章" | ❌ 不开 | **默认识别结果里就有 `seal` 块**,数量即答案 |

#### 正例 vs 反例

| | SKILL.md 里的写法 | Agent 的实际行为 |
|---|---|---|
| ❌ 反例 | `需要印章识别时加 --seal` | 没有判据,行为随机:或永不开启,或全量开启 |
| ❌ 反例 | `建议始终开启 --seal 以保证完整性` | 所有请求慢一倍,用户不知道为什么 |
| ✅ 正例 | 触发词 + 免费探测信号 + 两遍法 + 成本数字 + 决策速查表 | 按用户意图分流;检测到章但用户没提时**主动询问** |

#### 验证 Agent 是否真的按规则决策

准备三份样张:**有章合同**、**无章文档**、**手写便签**,分别用不同说法触发,检查结果:

| 说法 | 期望行为 |
|---|---|
| "把这份文件转成 Markdown"(有章合同) | **不带** `--seal`,并提示"检测到 2 处印章,需要读印章文字吗" |
| "这份合同盖的什么章?" | **带** `--seal`,并说明印章文字的可靠性局限 |
| "这几页有没有盖章?" | **不带** `--seal`,直接用默认识别结果里的 `seal` 块数量回答 |
| "把这几页转成 Markdown"(无章文档) | **不带** `--seal`(开了也没用) |

只要四种说法能走到不同分支,说明决策规则写到位了。

### 6.8 安装与分发

| 方式 | 做法 | 适用场景 |
|---|---|---|
| **skills CLI** | `npx skills add <owner>/<repo> -g --skill <名称> -y`(需 Node.js) | skill 已托管在 Git 仓库,想全局安装给各 Agent 共用 |
| **技能市场 / clawhub** | 通过市场客户端安装(如 OpenClaw 生态) | 公开分发的通用 skill |
| **手动安装** | 把整个 bundle 目录拷进目标 Agent 的 skills 目录 | **内网私有 skill、本项目推荐** |

手动安装(以本环境为例,其他平台替换成对应目录即可):

```bash
mkdir -p ~/.dsh/skills/ocr-doc-parsing
cp -r ocr-skill/. ~/.dsh/skills/ocr-doc-parsing/
chmod +x ~/.dsh/skills/ocr-doc-parsing/scripts/ocr.py

# 配凭据
mkdir -p ~/.dsh/credentials && chmod 700 ~/.dsh/credentials
printf 'Q38_OCR_BASE_URL=http://10.254.208.35:8090/v1\nQ38_API_KEY=%s\n' "<你的sk>" \
  > ~/.dsh/credentials/ocr.env
chmod 600 ~/.dsh/credentials/ocr.env

# 自检
set -a; . ~/.dsh/credentials/ocr.env; set +a
python3 ~/.dsh/skills/ocr-doc-parsing/scripts/ocr.py check
```

**各 Agent 的 skills 目录(常见约定,以各自文档为准):**

| 平台 / 工具 | 用户级 | 项目级 |
|---|---|---|
| DeepSeek Harness(DSH,本环境已验证) | `~/.dsh/skills/` | `<项目根>/.dsh/skills/` |
| Claude Code 系 | `~/.claude/skills/` | `<项目>/.claude/skills/` |
| 通用 agents 约定 | `~/.agents/skills/` | `<项目>/.agents/skills/` |
| 其他 Agent | 见其文档,通常为 `<配置目录>/skills/` | 同左 |

> 多数实现都**只扫描 skills 根目录的第一层**:必须是 `<skills根>/<name>/SKILL.md`,
> 不能多套一层(如 `<skills根>/x/y/SKILL.md`)。多平台共用时,把 bundle 多拷几份即可。

**验证是否被发现**:让 Agent 列出可用 skill,或直接用 skill 名称触发一次;
若没有出现,按 6.10 逐条排查。

### 6.9 跨平台适配注意事项

| 事项 | 建议 |
|---|---|
| frontmatter 字段 | 只用 6.2 的通用字段。平台私有字段(如 DSH 的 `whenToUse`、`disable-model-invocation`、`user-invocable`)**不要**写进通用版,需要时单独维护"平台变体" |
| 实测兼容性 | 通用字段(`license`/`compatibility`/`metadata`)在 DSH 下可正常加载 → **一份文件可跨平台复用**,无需求分叉 |
| 脚本路径 | 不要写死绝对路径;用 `<skill_dir>/scripts/...` 引用,或在脚本内基于 `__file__` 推导 |
| 工具依赖 | 不要依赖某个 Agent 专有的工具名;脚本只用标准 shell / Python,保证任何 Agent 都能执行 |
| 凭据 | 一律环境变量;`compatibility` 里声明需要哪些变量,SKILL.md 里写清缺失时的配置方法 |
| 网络前提 | 明确写出"必须走平台网关,不要直连 135"(见 1 节的两个入口),避免 Agent 猜地址 |
| 输出约定 | 在正文里规定"默认只回 Markdown,不要把整段 JSON 贴给用户",否则各平台 Agent 表现不一致 |

### 6.10 Skill 开发常见坑

| 坑 | 现象 | 规避 |
|---|---|---|
| `description` 太笼统 | skill 永远不被触发 | 写清"做什么 + 何时用 + 用户真实说法" |
| `description` 太长 | 触发率反而下降 | 控制在 1024 字符内,2–4 句 |
| `name` 不合规 | skill 被丢弃(大写、下划线、空格、超 64 字符) | 只用小写字母/数字/`-` |
| frontmatter 里出现裸冒号 | YAML 解析失败,skill 不被发现 | 值含 `:` 时用引号包起来,或写成 `|` 块 |
| 平台私有字段混进通用版 | 换平台后行为不一致 | 通用版只用标准字段,私有字段另存变体 |
| 地址 / 密钥硬编码 | 换环境全部失效、密钥进版本库 | 一律读环境变量 + 凭据文件 |
| 把所有细节写进 SKILL.md | 每次触发都塞满上下文 | 详细资料下沉 `references/`,逻辑下沉 `scripts/` |
| 拿大段 JSON 回给用户 | 输出噪声大 | 正文里明确规定输出形态 |
| 只写"按需开启印章" | Agent 行为随机:或永不开启,或全量开启 | 按 6.7 给出触发词 + 免费探测信号 + 两遍法 + 成本数字 |
| 短超时调大批量 | 大量超时、误判服务故障 | 用 `Q38_OCR_TIMEOUT=600` |
| 用 135 直连地址 | 连接超时 / 被防火墙丢包 | 固定用平台网关地址 |

### 附:DSH(DeepSeek Harness)平台变体

若该 skill 只在本环境使用,可在通用 frontmatter 基础上追加平台扩展字段,获得更细的控制:

```yaml
---
name: ocr-doc-parsing
description: (同通用版)
whenToUse: 当用户提供了图片、截图、扫描件或文档页面,需要提取其中的文字/表格/版面结构时使用。
disable-model-invocation: false   # true 则模型看不到,只能人工 / 调用
user-invocable: true              # false 则不出现在用户命令里
---
```

| 平台字段 | 作用 | 注意 |
|---|---|---|
| `whenToUse` | 更细的使用时机说明,补充 description | 通用平台会忽略,不影响加载 |
| `disable-model-invocation` | `true` 时仅允许人工调用 | 必须是合法布尔值;拼错会导致**整个 skill 被丢弃** |
| `user-invocable` | `false` 时不暴露给用户命令 | 同上 |

DSH 的目录优先级(rank 越小越优先):`<项目>/.dsh/skills`(100)> `<项目>/.agents/skills`(200)>
自定义目录(300)> `~/.dsh/skills`(400)> `~/.agents/skills`(500)。
同名 skill 以优先级高者为准。

---

## 七、性能基线与并发建议

### 7.1 实测基线(node135,8 卡全量)

| 路径 | 规模 | 吞吐 | 每页 |
|---|---:|---:|---:|
| 页面内嵌管线批量 | 1024 页 | 473.1 页/分 | 127 ms |
| 直连 135 API | 512 页 / 并发 96 | **419.7 页/分** | 143 ms |
| **经平台网关** | 512 页 / 并发 96 | **418.9 页/分** | 143 ms |
| 经平台网关 | 256 页 / 并发 64 | 396.9 页/分 | 151 ms |

- **单页串行延迟**:直连 1524 ms / 经网关 1540 ms(P50 1517 / 1538,P95 1606 / 1605)。
- **平台网关的额外开销约 0.2%–1%**,单页多约 16 ms,可忽略;
  换来的是统一鉴权、配额与用量记账。
- **HTTP 服务层本身约 11% 开销**(473 → 419 页/分),因为每页要过一次 HTTP + base64 编解码 + 落临时文件。

### 7.2 并发建议

| 场景 | 请求大小 | 建议并发 | 超时 |
|---|---|---|---|
| 单页交互 | 1 页 | 1 | ≥60 s |
| 小组批量 | ≤16 页/请求 | 2–4 | ≥300 s |
| 大批处理(几百页) | 1–8 页/请求 | 32–96 | ≥600 s |
| 超大作业(上千页) | 8–16 页/请求 | 96 | ≥600 s |

> 服务端 80 个工作进程,并发 96 已接近吞吐上限(419 页/分);再加并发不再提升,只会拉长排队。
> 客户端侧注意:一张 1 MB 图片 base64 后约 1.33 MB,并发 96 时上下行带宽要跟得上。

---

## 八、运维与排障

### 8.1 服务端自检(node135)

```bash
# OCR 服务
curl -s http://127.0.0.1:8090/health

# 8 个 VL 实例与负载均衡
for p in 8080 8081 8082 8083 8084 8085 8086 8087 8088; do
  printf "%s:%s " $p "$(curl -s -o /dev/null -w %{http_code} -m 5 http://127.0.0.1:$p/health)"
done; echo

# GPU 占用(VL 18% + 80 个布局进程,合计约 92%)
/opt/hyhal/bin/hy-smi

# 服务日志
sudo docker exec ppocr-client grep -a "\[ocr\]" /data/models/ocr_service.log | tail
```

### 8.2 服务端重启

```bash
# 只重启 OCR 服务(不动 VL 实例)
sudo docker exec ppocr-client pkill -9 -f python3
sudo docker exec -d ppocr-client bash -c \
  'export LD_LIBRARY_PATH=/opt/dtk/.hyhal/rocm_smi/lib:$LD_LIBRARY_PATH; \
   unset ROCR_VISIBLE_DEVICES; cd /data/models && \
   nohup python3 -u /data/models/ocr_service.py > /data/models/ocr_service.log 2>&1 &'

# 全量重建(8 VL + nginx + OCR 服务)
bash /data/models/deploy_ppocr_service.sh
```

### 8.3 常见故障

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 客户端 `✗ 无法连接` | 网络不通 / 网关没起 | `curl http://10.254.208.35:8090/health`;控制台「集群状态」看网关 |
| 全部请求 401 | sk 失效 | 控制台检查密钥是否被停用 |
| `/v1/ocr/health` 显示 `no_workers` | 工作进程没起来 | 看 `ocr_service.log`;按 8.2 重启 |
| 请求大量 502 | 网关连不上 135 | 在控制面 `curl http://10.254.213.135:8090/health` |
| 吞吐突然腰斩 | 显存吃紧 / 残留进程 | `hy-smi` 看显存;`pkill -9 -f python3` 清干净再重启 |
| 单页延迟 >10 s | 并发过高在排队 | 降并发;看 `/v1/ocr/health` 的 `idle`(空闲进程数为 0 即在排队) |

### 8.4 用量查看

- 控制台 **「OCR 服务」** 页:KPI(在线状态/常驻进程/24h 请求/24h 页数/平均延迟)、
  按密钥汇总、最近调用明细
- 接口:`GET http://10.254.208.35:8080/api/ocr?hours=24`(需控制台登录态)

---

## 附录 A:最小可用 Skill 全文

可直接复制使用。frontmatter 只用**通用字段**,因此同一份文件在 DSH / Claude Code 等平台都能加载。

`<skills根>/ocr-doc-parsing/SKILL.md`:

```markdown
---
name: ocr-doc-parsing
description: 调用私有化平台上的 PaddleOCR-VL 文档识别能力,把图片/扫描件/PDF 页面转成
  Markdown(含表格与公式)。当用户提供图片、截图、扫描件或文档页面,需要提取文字/表格/版面
  结构,或需要批量把图片转成 Markdown 时使用。触发词:OCR、文字识别、图片转文字、扫描件识别、
  文档解析、表格提取、印章识别、公章、手写体识别、PaddleOCR。
license: internal
compatibility: 需要 Python 3.9+;网络可达平台网关;环境变量 Q38_OCR_BASE_URL 与 Q38_API_KEY
metadata:
  author: platform-team
  version: "1.1"
---

# PaddleOCR-VL 文档识别

## 凭据与环境变量
| 变量 | 必填 | 含义 | 示例 |
|---|---|---|---|
| `Q38_OCR_BASE_URL` | 是 | 平台 OCR 接口基地址(到 /v1 为止,不带 /ocr) | `http://10.254.208.35:8090/v1` |
| `Q38_API_KEY` | 是 | 用户 sk(与 Qwen3.8 同一套密钥) | `sk-q38-xxxxxxxx` |
| `Q38_OCR_TIMEOUT` | 否 | 超时秒数,默认 300 | `600` |

填写方式(推荐凭据文件):
    mkdir -p ~/.dsh/credentials && chmod 700 ~/.dsh/credentials
    printf 'Q38_OCR_BASE_URL=http://10.254.208.35:8090/v1\nQ38_API_KEY=<你的sk>\n' \
      > ~/.dsh/credentials/ocr.env
    chmod 600 ~/.dsh/credentials/ocr.env
加载:set -a; . ~/.dsh/credentials/ocr.env; set +a

sk 在 http://10.254.208.35:8080 → API 密钥 新建,只显示一次,立即保存。

## 执行步骤
1. 自检(每次必做)
   [ -f ~/.dsh/credentials/ocr.env ] && { set -a; . ~/.dsh/credentials/ocr.env; set +a; }
   python3 <skill_dir>/scripts/ocr.py check
2. 识别
   python3 <skill_dir>/scripts/ocr.py 图片路径            # Markdown → stdout
   python3 <skill_dir>/scripts/ocr.py --json 图片路径     # 完整 JSON
   python3 <skill_dir>/scripts/ocr.py --out 目录 *.png    # 批量落盘
3. 呈现:默认只回 Markdown;要表格就保留 <table> 原文;要坐标才贴 blocks。

## 印章:怎么决定要不要开(见手册 6.7)
- 默认不识别,seal 块内容为空;开启后含章文档吞吐约减半。
- 用户提到 印章/公章/盖章/验章/章上的字/seal 等 → 加 --seal。
- 否则先默认跑:CLI 会提示"检测到 N 处印章",把选择权交给用户。
- 批量用两遍法:默认跑全量 → 筛出含章页 → 只对含章页 --seal 重跑。
- 用户问"有没有盖章" → 不用开,默认结果里的 seal 块数量即答案。

## 手写体
默认即可识别,无需参数;字迹小可加 --ocr-min-pixels 300000。

## 关键约束
- 只支持图片(PNG/JPG/JPEG/WebP/BMP);PDF 先 `pdftoppm -r 200 -png in.pdf page`。
- 不要用 135 直连(http://10.254.213.135:8090),被防火墙挡住;一律走平台网关。
- 单页约 1.5 s(开印章约 6 s);批量 ≤16 页/请求,并发 32–96,超时设 600 s。
- 鉴权头 `Authorization: Bearer <sk>`,漏了会 401。
- 稳定误识:`K100AI` 会读成 `KI00AI`(数字 1 → 字母 I),关键编号建议人工复核。

## 排障
| 现象 | 处理 |
|---|---|
| `✗ 未设置 Q38_*` | 按上表配置并 source |
| `401 missing api key` | 没 source 凭据文件 |
| `401 invalid api key` | sk 复制错了 |
| `400 invalid base64 image data` | 传了 PDF/非图片 |
| `400 no image provided` | 参数名应为 image / images |
| 印章块内容为空 | 加 --seal |
| `✗ 无法连接` | 网络不通,curl 平台 /health 验证 |
```

同目录下再放:

- `scripts/ocr.py` —— 零依赖 CLI(标准库实现,支持 `check` / `--json` / `--out` / `--seal` / `--seals-only` / `--seal-min-pixels` / `--ocr-min-pixels`)
- `references/api.md` —— HTTP 接口速查、可调选项表、错误码表

安装后验证:

```bash
python3 <skills根>/ocr-doc-parsing/scripts/ocr.py check
# ✓ 服务在线:workers=80 idle=80 累计页数=… 错误=0
#   可调选项:seal, chart, min_pixels, ...
```

---

## 附录 B:字段与错误码总表

### 环境变量

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `Q38_OCR_BASE_URL` | 是 | — | 平台 OCR 基地址(到 `/v1`) |
| `Q38_API_KEY` | 是 | — | 用户 sk |
| `Q38_OCR_TIMEOUT` | 否 | `300` | 单次请求超时(秒) |

### 可调选项(请求体字段)

| 选项 | 默认 | 说明 |
|---|---|---|
| `seal` | `false` | 开启印章识别(否则印章块内容为空) |
| `seal_min_pixels` | 开启 `seal` 时 `400000` | 印章裁剪图放大下限,**最有效** |
| `seal_max_pixels` | `1003520` | 印章裁剪图上限 |
| `ocr_min_pixels` | `112896` | 正文档放大下限(手写字小时提高) |
| `ocr_max_pixels` | `1003520` | 正文档上限 |
| `chart` | `false` | 开启图表解析 |
| `table_min_pixels` / `table_max_pixels` | 同上默认 | 表格块分辨率 |
| `chart_min_pixels` / `chart_max_pixels` | 同上默认 | 图表块分辨率 |

### 响应字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `model` | str | 模型 ID |
| `pages` | int | 本次识别页数 |
| `elapsed_ms` | int | 工作进程内的识别耗时 |
| `total_ms` | int | 服务端端到端耗时(含排队) |
| `markdown` | str | Markdown 正文(多页为拼接) |
| `blocks[]` | list | 版面块:`label` / `content` / `bbox` |
| `blocks[].label` | str | `text`/`title`/`table`/`figure`/`formula`/`header`/`footer` 等 |
| `blocks[].bbox` | list | 原图像素坐标 `[x1,y1,x2,y2]` |
| `results[]` | list | 每页明细,顺序与请求一致 |
| `width`/`height` | int | 页面尺寸(单页请求时展开到顶层) |

### 错误码

见 [3.4](#34-错误码)。

---

## 相关文档

| 文档 | 内容 |
|---|---|
| `PaddleOCR-VL平台集成说明.md` | 平台侧集成方案、架构、运维 |
| `PaddleOCR-VL高吞吐批量优化报告.md` | 吞吐优化全过程与性能分析 |
| 示例 Skill bundle | `ocr-skill/`(含 `SKILL.md`、`scripts/ocr.py`、`references/api.md`) |
