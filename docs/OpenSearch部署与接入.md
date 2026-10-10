# OpenSearch 部署与接入（两台新服务器 10.254.212.110 / 10.254.212.111 · tar.gz + systemd）

> **文档性质：操作手册（SOP），风格与 [`部署与发布全流程手册.md`](部署与发布全流程手册.md) §0 一致** ——
> §0~§7 是**可逐条照做**的定稿步骤（编号步骤 + 可直接复制的命令 + 预期输出）；要"为什么"看每节末尾的说明与附录。
>
> **部署形态：官方 `tar.gz` 解包 + systemd 托管。这两台机器上【不装 Docker、也不使用 Docker】**——
> 理由：新机器本来就没装 Docker，且这两台"部署完就基本不用怎么运维"；官方 Tar + systemd 足够。
> 与之配套的资产：[`deploy/opensearch/`](../deploy/opensearch/)（`opensearch.yml.example`、`jvm.options.d/heap.options`、
> `opensearch.service`、`snapshot-backup.sh`）。
>
> **关联**：[`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md)（"换 OpenSearch 的三条量化触发点"）、
> [`部署与发布全流程手册.md`](部署与发布全流程手册.md) §8（AI 能力服务 ai-backend）、[`deploy/README.md`](../deploy/README.md)。
>
> **官方依据**（本文件反复引用，均已逐条核对；核对结果见 **附录 B**）：
> [Tar 安装页](https://docs.opensearch.org/latest/install-and-configure/install-opensearch/tar/)、
> [安全插件启停](https://docs.opensearch.org/latest/security/configuration/disable-enable-security/)、
> [demo 配置与口令要求](https://docs.opensearch.org/latest/security/configuration/demo-configuration/)、
> [安全设置（含 `plugins.security.ssl.http.enabled`）](https://docs.opensearch.org/latest/install-and-configure/configuring-opensearch/security-settings/)。
>
> **本轮只写文档与部署资产**：`backend/**`、`ai-backend/**`、`frontend/**`、`scripts/**` 应用代码**一行未改**。

---

## 0. 结论与前提（先看这一节）

### 0.1 为什么上 OpenSearch（三条触发点，任一成立即必须换）

| 序号 | 触发点 | 现状依据 | 阈值 |
| --- | --- | --- | --- |
| ① | **延迟**：`local` 给**每一次提问**加的固定开销 | 每次检索把范围内**全部切片**的向量 JSON 读出来解析成 `float[]` 再逐条算余弦 | 切片 **≥ 4,000**（≈130 份）时 **+1.0s**；≥15,000 时 **+3.8s**（相对实测 10s 基线 **+38%**） |
| ② | **内存/并发**：常驻 = `16KB × 切片数 × 并发问数` | AI 容器**当前没有 `-Xmx`**（未确认项） | 4 GB 堆、留 50% 余量 ⇒ **4 并发时 ≈3 万切片触顶**；8 并发 ⇒ **≈1.5 万切片** |
| ③ | **架构（最硬的一条）**：`local` 是**进程内状态** | AI 服务**必须单实例**；且切片**没有 `project_id`/密级等元数据**，"先过滤再检索"今天**做不到**，而"先过滤"正是权限合规的前提 | **需要第 2 个 AI 实例时** |

> 数字出处：[`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md) §3.4 表与"量化触发点"表（本轮真机读数：
> 1 个 4096 维切片向量 JSON = **38,333 字节**，即 **9.36 字节/维**）。该文档结论：把 OpenSearch 部署从 P2 **上提为 P1**。
>
> 一句话：**规模不是"余弦算不动"的理由（15,000 切片余弦仅 ≈61ms），但"延迟 / 内存 / HA + 元数据过滤"三条已经把切换点推到 ~4,000 切片。**

### 0.2 部署形态结论：**单节点 + systemd**

| 项 | 本方案定稿 |
| --- | --- |
| 节点数 | **1 个**：`os01` = **10.254.212.110**（8 核 / 16GB 内存 / 200GB 磁盘） |
| 第二台 `10.254.212.111` | **冷备 + 演练机**：同一套安装与同样的 unit，**服务默认停着**；只接收每晚快照副本（§6.4 / §7.2） |
| 安装方式 | 官方 **`opensearch-<版本>-linux-x64.tar.gz`** 解包到部署根的 `app/`（**这两台是 x86_64**） |
| 部署根 | **`/home/opensearch/`**（程序 / 配置 / 数据 / 日志 / 备份**分离**，见下方结构） |
| 托管方式 | **systemd**（unit 见 [`opensearch.service`](../deploy/opensearch/opensearch.service)），专用非 root 用户 `opensearch` |
| 索引 | `pm-ai-chunks`，**主分片 1 + 副本 0**（见 §5.1） |
| 端口 | `9200`（REST）**只放行两台应用服务器**；`9300`（transport）本方案单节点不需对外；**9200 绝不对外** |
| 监听 | `network.host: <本机内网IP>`（不用 `0.0.0.0`） |
| 安全 | **内网 HTTP + basic auth（强口令）**：显式关掉 REST 层 TLS（§3.5）；transport 层 TLS 保持开启 |
| 数据/日志/快照 | `/home/opensearch/{data,logs,backup}`（**在程序目录 `app/` 之外**，升级换 `app/` 不碰数据） |
| 部署根 | **`/home/opensearch/`** = **专用账号 `opensearch` 自己的家目录**（`useradd -m -d /home/opensearch …` + `passwd -l` 锁登录），见 §2.4 |
| Dashboards | **OpenSearch Dashboards 2.19.1 与 OpenSearch 同机**（`10.254.212.110:5601`，单独 systemd 服务），见 **§12** |

**部署根目录结构（两台机器一致；安装当天建好，之后升级只动 `app/`）**

```text
/home/opensearch/                     ← 专用账号 opensearch 的家目录（= 部署根）
├─ app/                 ← 当前程序（tarball 解包出来的内容：bin/ config/ jdk/ lib/ modules/ plugins/ …）
├─ app-<旧版本>/         ← 升级时保留的旧目录（回滚点；确认新版稳定后可删）
├─ config/              ← 我们维护的配置（升级时不用重配）
│  ├─ opensearch.yml            （= deploy/opensearch/opensearch.yml.example 的落地副本）
│  ├─ jvm.options.d/heap.options（= deploy/opensearch/jvm.options.d/heap.options）
│  ├─ snapshot-backup.sh        （= deploy/opensearch/snapshot-backup.sh）
│  └─ snapshot-backup.env       （可选：脚本的覆盖配置；含口令，权限 600）
├─ data/                ← path.data（索引数据：**唯一的有状态目录**）
├─ logs/                ← path.logs（文件日志，另有 journald）
├─ backup/fs-repo/      ← path.repo（快照仓库；snapshot-backup.sh 往这里写，再 rsync 到 .111）
└─ dashboards/          ← OpenSearch Dashboards（见 §12）
   └─ app/              ←   解包出来的内容（bin/ config/ node/ plugins/ …）
      └─ config/opensearch_dashboards.yml
```

> **`config/` 与程序自带 `config/` 的关系（全方案只有一种做法）**：我们只维护部署根 `config/` 下的**一份**
> `opensearch.yml` 与 `jvm.options.d/heap.options`；**安装与每次升级时把它拷贝进 `app/config/`**（**不用软链**）。
> 运行时真正生效的永远是 `app/config/` 那份，`config/` 是它的源头。详见 §3.3。
> Dashboards 同理：我们只维护 `dashboards/app/config/opensearch_dashboards.yml`（它就是 Dashboards 官方规定的配置文件位置）。

> ⚠️ **架构提醒（很实际的坑）**：本项目**其它服务器（`pdmsappgh` / `ai-kingbase-gh`）是 aarch64**，
> 而**这两台是 x86_64**。所以安装包必须是 **`...-linux-x64.tar.gz`**（不是 `arm64`）。
> 先确认：`uname -m` 期望输出 **`x86_64`**。把 arm64 包解开在 x86 机器上，启动会直接失败。

### 0.3 为什么是单节点：**2 节点相比单节点几乎换不到可用性**（quorum 明账）

> **这一节是给运维/评审看的"明账"，不是免责声明。**

- OpenSearch 用 **majority quorum** 选举：可参与选举的节点数为 `N` 时，需要 **`⌊N/2⌋+1`** 票。
  - **N=1**：1 票即可（单节点 `discovery.type: single-node` 直接选自己）；
  - **N=2**：**quorum = 2 ⇒ 需要 2 票** ⇒ **挂掉任意一台就失去 master**；
  - **N=3**：quorum = 2 ⇒ 挂掉一台仍能选举，这才是真 HA。
- **N=2 挂一台的实际后果**：副本 = 1 时**数据不丢**，但**集群不可写、也读不了集群状态**（分片分配、索引元数据、
  写入全部停摆）——**服务不可用，直到它回来**。这等于"用两倍的机器拿到单节点的可用性"。
- **N=2 还多出来的复杂度**：`discovery.seed_hosts` + `cluster.initial_master_nodes` 必须两机一致且**只能写一次**
  （写错要清 `data/` 重来）、两台配置不能撞、重启**必须一台一台来**（同时重启 = 双双失去 quorum）、升级窗口翻倍。
- **真正 HA 的升级路径**（附录 A）：加**第 3 台**（可用轻量机做 **dedicated cluster-manager**，不存数据）→
  届时只需改**两个参数**：`discovery.seed_hosts` 与 `cluster.initial_master_nodes`（3 个节点名/IP），副本从 0 改成 1。

### 0.4 单节点的代价（必须接受的明账）

| 风险 | 后果 | 本方案的补偿措施 |
| --- | --- | --- |
| **单点故障** | OpenSearch 挂 → kNN 检索不可用（**AI 问答的检索环节受影响**） | ① **快照/备份是强制项**（[`snapshot-backup.sh`](../deploy/opensearch/snapshot-backup.sh) + cron，§7.2）；② **AI 侧降级口径定死**（§6.3：**明确报错，不许静默返回无关结果**）；③ systemd `Restart=on-failure` + `enable`（开机自启） |
| 无副本 | 数据盘损坏即丢索引 | 每晚快照 + `rsync` 到 `.111`（§7.2 / §6.4），**RTO 分钟级** |
| 无滚动升级 | 升级要停机（分钟级） | 升级步骤见 §7.3（停服 → 解包新版本 → 复制 `config/` → 启动；旧目录保留即回滚） |

> **重要边界**：OpenSearch 里存的是**可重建物**（切片文本 + 向量 + 索引），与既有运维口径一致——
> **业务数据仍在主系统崖山库**（V1~V14，16 张表）。索引即使全丢，也能从主系统附件重新解析重建（代价是时间，见 §6.2）。

### 0.5 两台新机器与既有服务器的关系（别搞混）

| 机器 | 角色 | 说明 |
| --- | --- | --- |
| `10.254.212.110` | **OpenSearch 单节点（推荐）** | 本文 §1~§7 的主体（x86_64） |
| `10.254.212.111` | **冷备 + 演练机** | 同样的安装与 unit，服务默认停着；存快照副本（§6.4） |
| 既有 `pdmsappgh` / `ai-kingbase-gh` | 主系统 / AI 能力服务 | **不在这两台新机器上**；本文只改它们的 `.env`（§6.1） |
| `10.254.208.35:8090` | 平台网关（对话/OCR/向量化/重排） | 与本文无关，仅在重建索引时要它可用 |

---

## 1. 获取安装包（在**一台有外网**的机器上）

> **服务器无外网** ⇒ 安装包只能先在别处下载、校验、再 scp 过去。
> 官方包 URL 形式（官方 Tar 页原文）：`https://artifacts.opensearch.org/releases/bundle/opensearch/<版本>/opensearch-<版本>-linux-x64.tar.gz`

### 1.1 固定版本（本文示例 pin **2.19.1**，x86_64）

| 项 | 值 |
| --- | --- |
| 文件名 | `opensearch-2.19.1-linux-x64.tar.gz` |
| 体积 | **≈ 920 MiB**（964,414,604 字节，本轮实测 HTTP HEAD） |
| 校验和文件 | `opensearch-2.19.1-linux-x64.tar.gz.sha512`（**165 字节文本**，内容格式：`<128位sha512>  <文件名>`） |
| 本轮实测的 SHA512 | `72af27eaab94f0dad092d5a97bfda0baeb995a390cdfb94ce076424216032f0d7c5081001997b95a0954bee15a44b824e8b7ecd2066b920ac6cbb313b7be24de` |

> ⚠️ **版本以官方下载页当前可用为准**：`2.19.1` 是本轮实际验证过 URL 与校验和可用的版本，不是"永远最新"。
> 换版本时**只需改两个地方**（见 §1.5 的清单，别到处找）。**校验不通过绝不安装**。

### 1.2 下载（二选一，取决于你手上是什么机器）

**有外网的 Windows 机器（开发机）**
```powershell
$VER = '2.19.1'
$URL = 'https://artifacts.opensearch.org/releases/bundle/opensearch'
New-Item -ItemType Directory -Path dist\opensearch -Force | Out-Null

# ① 下载 tar.gz 与官方 .sha512（两行都要执行）
curl.exe -fL --retry 3 -o "dist\opensearch\opensearch-$VER-linux-x64.tar.gz"        "$URL/$VER/opensearch-$VER-linux-x64.tar.gz"
curl.exe -fL --retry 3 -o "dist\opensearch\opensearch-$VER-linux-x64.tar.gz.sha512" "$URL/$VER/opensearch-$VER-linux-x64.tar.gz.sha512"

# ② 看官方校验文件长什么样（应输出：<128 位 sha512>  opensearch-<VER>-linux-x64.tar.gz）
Get-Content "dist\opensearch\opensearch-$VER-linux-x64.tar.gz.sha512"

# ③ 自己算一遍并比对（Windows 原生，不需要 Git Bash / sha512sum）
$expected = ((Get-Content "dist\opensearch\opensearch-$VER-linux-x64.tar.gz.sha512" -Raw) -split '\s+')[0].ToLower()
$actual   = (Get-FileHash "dist\opensearch\opensearch-$VER-linux-x64.tar.gz" -Algorithm SHA512).Hash.ToLower()
if ($expected -eq $actual) { "校验通过 ✅" } else { "校验不通过 ❌ 不要上传" }
```

**有外网的 Linux 机器**
```bash
VER=2.19.1
URL=https://artifacts.opensearch.org/releases/bundle/opensearch
mkdir -p dist/opensearch && cd dist/opensearch

curl -fL --retry 3 -O "$URL/$VER/opensearch-$VER-linux-x64.tar.gz"
curl -fL --retry 3 -O "$URL/$VER/opensearch-$VER-linux-x64.tar.gz.sha512"

# 官方给的 .sha512 就是 sha512sum -c 能直接吃的格式
sha512sum -c "opensearch-$VER-linux-x64.tar.gz.sha512"
# 期望：opensearch-2.19.1-linux-x64.tar.gz: OK
```

### 1.3 传到两台机器

```bash
# 在【有外网那台机器】的 dist/opensearch 目录下执行（用户与既有服务器一致：lhim）
scp opensearch-2.19.1-linux-x64.tar.gz opensearch-2.19.1-linux-x64.tar.gz.sha512 lhim@10.254.212.110:/tmp/
scp opensearch-2.19.1-linux-x64.tar.gz opensearch-2.19.1-linux-x64.tar.gz.sha512 lhim@10.254.212.111:/tmp/
```

### 1.4 服务器侧再核一次校验（**两台都做**）

```bash
# 服务器执行
cd /tmp
sha512sum -c opensearch-2.19.1-linux-x64.tar.gz.sha512
# 期望：opensearch-2.19.1-linux-x64.tar.gz: OK
# 不一致 = 传输损坏 → 重传（不要"先解开看看"）
```
```bash
# 顺手确认架构（应输出 x86_64；若输出 aarch64 说明你拿的是 ARM 机器，安装包要换 arm64 版）
uname -m
```

### 1.5 换版本时要同步改的地方（**就这两处，别到处找**）

| 序号 | 位置 | 改什么 |
| --- | --- | --- |
| 1 | `docs/OpenSearch部署与接入.md`（本文件） | §1.1 表格里的版本号、文件名、体积、SHA512（**从官方 `.sha512` 文件里抄**） |
| 2 | `deploy/opensearch/` 的使用命令 | 所有 `opensearch-<版本>-linux-x64.tar.gz` 路径（§3.1 的解包命令） |

> 版本号**不写进任何配置文件**（`opensearch.yml` / systemd unit 里都没有版本号），所以不存在第三处。
> 服务器上"当前是哪个版本"用 `cat /home/opensearch/app/VERSION` 或看目录名即可查。

**本节说明**：为什么坚持 pin 一个具体版本 + 校验和？离线环境没有"自动升级"，也**没有第二份副本可对比**；
tar 包一旦传坏，症状是解包/启动期的各种奇怪失败，排查成本远高于先花 10 秒做一次校验。

---

## 2. 宿主准备（**两台都要做一遍**）

> 登录：`ssh lhim@10.254.212.110` / `ssh lhim@10.254.212.111`。下面每段都可独立复制执行。

### 2.1 内核参数 `vm.max_map_count`（**最关键，漏了必失败**）

官方 Tar 页 Step 2 要求这一项：OpenSearch 底层的 Lucene 用 `mmap` 打开索引文件，
默认 `65530` 不够（会报 `max virtual memory areas vm.max_map_count [65530] is too low`）。

```bash
# ① 立即生效
sudo sysctl -w vm.max_map_count=262144

# ② 持久化（官方示例是改 /etc/sysctl.conf；这里写成独立文件，避免和别人的行冲突）
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-opensearch.conf

# ③ 读回校验（含官方给的那条）
sysctl vm.max_map_count
cat /proc/sys/vm/max_map_count
# 两者都期望：262144
```
```bash
# ④ 重启后校验（★必须做一次：不重启不算验证过持久化）
sudo reboot
# —— 重新登录后 ——
cat /proc/sys/vm/max_map_count      # 期望 262144
```

### 2.2 文件描述符与内存锁定上限

```bash
# ① 当前 shell 的值
ulimit -n
# 期望：≥ 65536（OpenSearch 要求"至少 65536"；systemd unit 里也设了 LimitNOFILE=65536）
```
```bash
# ② 给 lhim 与 opensearch 两个用户持久化（opensearch 用户在第 3 步创建，这里先写好）
sudo tee /etc/security/limits.d/99-opensearch.conf >/dev/null <<'EOF'
lhim        soft  nofile  65536
lhim        hard  nofile  65536
opensearch  soft  nofile  65536
opensearch  hard  nofile  65536
# 内存锁定（bootstrap.memory_lock 需要）：systemd unit 里已设 LimitMEMLOCK=infinity，
# 这里再给登录 shell 留一份，便于手工调试
opensearch  soft  memlock unlimited
opensearch  hard  memlock unlimited
EOF

# ③ 重登后校验
ulimit -n            # 65536（对 lhim）
```

### 2.3 swap 处理与内存锁定

```bash
# ① 看现状
free -h                      # 关注 Swap 行
swapon --show                # 有输出 = 正在用 swap
```
```bash
# ② 关掉 swap（官方 Tar 页 Step 2 的第一条命令就是它）
sudo swapoff -a
# 重启后仍生效：注释掉 /etc/fstab 里的 swap 行
sudo cp /etc/fstab /etc/fstab.bak.$(date +%F)
sudo sed -i.bak '/[[:space:]]swap[[:space:]]/s/^/#/' /etc/fstab
```
```bash
# ③ 启动后验证"内存锁定真的生效"（§4 起来再做）
curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/_nodes?filter_path=nodes.*.process.mlockall'
# 期望：{"nodes":{"<node-id>":{"process":{"mlockall":true}}}}
```

> **为什么同时要 `LimitMEMLOCK=infinity`**：官方 configuring-opensearch 文档明确——用 systemd 托管时，
> 除了 `bootstrap.memory_lock: true`，还要在 unit 里放开 memlock，否则 JVM 无法锁定内存、启动即报错。
> 本方案的 [`opensearch.service`](../deploy/opensearch/opensearch.service) 已经设好这一项。

### 2.4 磁盘、目录规划与专用用户（属主很关键）

```bash
# ① 看清磁盘：容量、挂载点、文件系统类型
df -hT | grep -E 'Filesystem|/home|/data'
lsblk -f
# 期望：/home 容量 ≥ 200GB（本次给定），文件系统 xfs 或 ext4

# ② 写测试（权限问题不要靠"应该能写"猜；先用当前用户测盘）
sudo -u lhim sh -c 'dd if=/dev/zero of=/home/.wtest bs=1M count=64 oflag=direct && rm -f /home/.wtest'
# 期望：64+0 records out … copied 67108864 bytes（无报错）
```
```bash
# ③ 目录规划（一次建全；与 opensearch.yml、unit、快照脚本逐字对应）
sudo mkdir -p /home/opensearch/config /home/opensearch/data /home/opensearch/logs \
              /home/opensearch/backup/fs-repo
ls -ld /home/opensearch/*          # 期望：app/dashboards 还没建（§3.1、§6 才解包），其余四个目录都在
```
```bash
# ④ 专用非 root 用户（★D 方案：有家目录的普通账号 + 锁死登录；SSH 进不来，只有 root 能 su -）
sudo useradd -m -d /home/opensearch -s /bin/bash opensearch
sudo passwd -l opensearch            # 锁定口令：无口令、不能直接登录
id opensearch                        # 期望能看到 uid/gid
getent passwd opensearch             # 期望家目录是 /home/opensearch
sudo ls -ld /home/opensearch         # 期望属主 opensearch

# ⑤ 让 lhim 也属于 opensearch 组，便于日常查看文件
sudo usermod -aG opensearch lhim
#   执行后【退出 SSH 重新登录】再复核： groups | grep opensearch
```

> **为什么不用官方示例的 `adduser --system … --no-create-home`**：D 方案把**部署根就设在 `opensearch` 账号的家目录**
> （`/home/opensearch/`），所以该账号**必须有家目录**；同时它**不应是能登录的交互账号**（服务只由 systemd 以 `User=opensearch` 拉起），
> 因此用 `passwd -l` 锁口令。官方那种"系统账号 + 无家目录"的写法与 D 方案目录结构不兼容。

```bash
# ⑥ 属主与权限
sudo chown -R opensearch:opensearch /home/opensearch
sudo chmod 755 /home/opensearch                    # 部署根：可穿过（子目录各自 750）
sudo chmod 750 /home/opensearch/data /home/opensearch/logs /home/opensearch/backup /home/opensearch/config

# ⑦ 复核（子目录都应显示 opensearch opensearch 与 drwxr-x---）
ls -ld /home/opensearch /home/opensearch/config /home/opensearch/data /home/opensearch/logs \
       /home/opensearch/backup /home/opensearch/backup/fs-repo
```

### 2.4.1 家目录可用性检查（**D 方案成立的前提，必须现场核对**）

> **上一版曾要求 `chmod 711 /home/lhim`（因为当时程序放在 `lhim` 家里）——D 方案下这一条【不再需要】，
> 本文件已删除该要求，避免误导**：部署根现在是 `opensearch` 账号**自己的**家目录，权限自查自管。

```bash
# ① 以 opensearch 身份验证它能进自己的家目录（这条过了，systemd 就不会在这上面栽跟头）
sudo -u opensearch ls -d /home/opensearch
sudo -u opensearch touch /home/opensearch/.wtest && sudo -u opensearch rm -f /home/opensearch/.wtest
# 期望：第一条打印该目录；第二条无报错
#   报 Permission denied ⇒ 回 ⑥ 复核属主/权限（或查 SELinux 上下文，见 §7.5）

# ② 等到 §3.1 解包、§3.4 改完属主后，做"最终确认"（见 §3.4）
sudo -u opensearch ls /home/opensearch/app/bin/opensearch
# 期望：打印出该路径
```

| 目录（宿主） | 用途 | 属主/权限 |
| --- | --- | --- |
| `/home/opensearch` | **部署根 = opensearch 账号的家目录** | `opensearch:opensearch`，`755` |
| `/home/opensearch/app` | **程序目录**（tar 解包出来的那份；升级时整目录替换） | `opensearch:opensearch`（`chown -R`） |
| `/home/opensearch/app-<旧版本>` | 升级时保留的旧程序（**回滚点**） | 同上 |
| `/home/opensearch/config` | 我们维护的 `opensearch.yml` + `jvm.options.d/heap.options`（安装/升级时拷进 `app/config/`） | `opensearch:opensearch`，`750`（文件 `644`；`snapshot-backup.env` 含口令用 `600`） |
| `/home/opensearch/data` | 索引数据（**唯一有状态目录**） | `opensearch:opensearch`，`750` |
| `/home/opensearch/logs` | 日志（`path.logs`；**在程序目录之外**，升级不丢日志） | `opensearch:opensearch`，`750` |
| `/home/opensearch/backup/fs-repo` | 快照仓库（`path.repo` 之下） | `opensearch:opensearch`，`750` |
| `/home/opensearch/dashboards/` | **OpenSearch Dashboards** 的程序与配置（见 §6） | `opensearch:opensearch` |
| `/home/opensearch/config/snapshot-backup.sh` | 每晚快照脚本 | `opensearch`（`755`） |

### 2.4.2 `/home` 挂载前置检查（**D 方案把部署根放在 `/home` 下，所以必须先确认它靠谱**）

D 方案的前提是"`/home` 是一块正常的本地盘"。**上生产前必须现场核对**；若 `/home` 是网络盘或空间不够，
数据的可靠性与容量都会出问题。

```bash
# ① 空间够不够（程序 ~1GB + 索引 + 日志 + 快照；本机 200GB 盘）
df -h /home

# ② /home 到底挂在什么上（本地盘 / NFS / autofs / 单独分区）
findmnt /home
findmnt -no SOURCE,FSTYPE,OPTIONS /home
lsblk -f
# 期望：SOURCE 是本地块设备（/dev/sdX、/dev/vdX、/dev/mapper/…），FSTYPE 是 xfs 或 ext4
#   若是 nfs/nfs4/cifs/autofs ⇒ ★不要直接上（见下方退路）
```

| `findmnt /home` 看到什么 | 结论 | 怎么做 |
| --- | --- | --- |
| 本地盘（`/dev/…`，xfs/ext4，容量够） | **D 方案成立** | 按本文继续 |
| 本地盘但**容量不足** | 需要退路 | 只把 `path.data` 指到别的盘（见下） |
| **NFS / CIFS** | 不适合放 `path.data`（mmap、fsync、文件锁在网络盘上不可靠） | 用退路：`path.data` 放本地盘 |
| **autofs / 自动挂载** | 卸载时服务会莫名失效 | 不要用；换常用挂载点或用退路 |

**空间不足 / `/home` 不是本地盘时的既定退路（只挪数据，其余不动）**

```bash
# ① 在真正的数据盘上建目录（示例用 /data，按现场实际盘改）
sudo mkdir -p /data/opensearch
sudo chown opensearch:opensearch /data/opensearch
sudo chmod 750 /data/opensearch

# ② 改【配置源头】的 path.data，再按 §3.3 ⑥ 拷进 app/config/
sudo -u opensearch vi /home/opensearch/config/opensearch.yml
#   path.data: /data/opensearch

# ③ 重启生效
sudo systemctl restart opensearch

# ④ 复核：数据确实指到新盘
curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/_nodes?filter_path=nodes.*.settings.path.data&pretty'
df -h /data/opensearch
```
> ⚠️ 除 `path.data` 外**其余仍在 `/home/opensearch`**（程序、配置、日志、快照）。
> 若日志/快照也要挪，同理改 `path.logs` 与 `path.repo`（改完都要 `chown opensearch:opensearch` + 重启）。

> **`/home` 被 `nosuid`/`nodev` 挂载要不要处理？——不需要，别为此改挂载参数。**
> 这两项只影响"setuid 程序能否提权"和"设备文件能否使用"；OpenSearch 是普通 JVM 进程，
> **既不依赖 setuid，也不访问块/字符设备**，`nosuid,nodev` 对它完全无害。
> 真正会挡路的是 **`noexec`**：若 `/home` 以 `noexec` 挂载，`bin/opensearch` 与 JVM 都跑不起来——
> 用 `findmnt -no OPTIONS /home` 确认没有 `noexec` 即可。

### 2.5 防火墙（**9200 绝不对外**）

| 端口 | 放行来源 | 用途 |
| --- | --- | --- |
| `9200/tcp` | **两台应用服务器 / AI 所在机器**（仅此） | REST（kNN 检索、`_bulk`、快照管理） |
| `9300/tcp` | 本方案**单节点：不需要对外**（将来 3 节点才在两两之间放行） | 节点间 transport |
| `9600/tcp` | **不放行** | Performance Analyzer |

```bash
# ① 先看用的哪个防火墙（麒麟常见 firewalld 或 iptables；本机实际用哪个未确认，见 §8）
systemctl is-active firewalld 2>/dev/null || true
systemctl is-active iptables  2>/dev/null || true
```
```bash
# ② firewalld 版（把 9200 只放进受信来源的富规则，不做全局开放）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<应用服务器IP>" port port="9200" protocol="tcp" accept'
#   ↑ 把 <…> 逐个替换成明确地址（不要留网段、绝不要写 0.0.0.0/0）
sudo firewall-cmd --reload
sudo firewall-cmd --list-rich-rules
```
```bash
# ③ iptables 版（仅当机器上确实没有 firewalld）
sudo iptables -I INPUT -p tcp -s <应用服务器IP> -m tcp --dport 9200 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 9200 -j DROP
#   持久化按平台方口径（iptables-services / firewalld）
```
```bash
# ④ 自检：从【不该通】的机器验证它真的不通
curl -m 3 -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:9200/ ; echo "exit=$?"
# 期望：超时（exit=28）或 connection refused ——【绝不能】是 200 或 401
```

> ⚠️ **认证 ≠ 防火墙**：内网 HTTP + basic auth 意味着"口令一旦泄漏就能直连"（明文过网）。
> 所以**必须**两者同时做——防火墙收紧来源，认证挡住可达者。这是 §3.5 选型的补偿措施，不是可选项。

### 2.6 时间同步

```bash
# ① 看同步状态
timedatectl                 # 期望：System clock synchronized: yes
chronyc sources -v 2>/dev/null || ntpq -p 2>/dev/null || true

# ② 确保服务在跑（内网时间源地址需平台方给，见 §8）
sudo systemctl enable --now chronyd 2>/dev/null || sudo systemctl enable --now ntpd 2>/dev/null || true

# ③ 时区（政务系统日期敏感，与主系统 TZ=Asia/Shanghai 对齐）
sudo timedatectl set-timezone Asia/Shanghai
date
```

### 2.7 本节完成自检

```bash
cat /proc/sys/vm/max_map_count     # 262144
ulimit -n                          # ≥65536（重登后 65536）
id opensearch                      # 用户存在，家目录 = /home/opensearch
getent passwd opensearch           # 家目录确认
findmnt -no SOURCE,FSTYPE,OPTIONS /home   # ★本地盘 + 无 noexec（§2.4.2）
ls -ld /home/opensearch /home/opensearch/data   # opensearch opensearch；data 为 drwxr-x---
sudo -u opensearch ls -d /home/opensearch       # ★能进自己的家目录（§2.4.1）
uname -m                           # x86_64
timedatectl | head -3              # synchronized: yes
```

---

## 3. 安装（**两台都做一遍**）

> 全程**用非 root 的 `opensearch` 用户执行解包与启动**（官方明确 OpenSearch 不能以 root 运行）。
> 程序目录统一 `/home/opensearch/app`（tar 解包内容）；**配置从部署根的 `config/` 拷进去**（§3.3）。

### 3.1 解包（官方 Tar 页 Step 1）

```bash
# 用 lhim 解包到部署根（部署根已是 opensearch 账号的家目录，权限见 §2.4）
cd /home/opensearch
tar -xvf /tmp/opensearch-2.19.1-linux-x64.tar.gz
ls -d opensearch-2.19.1        # 确认解出来的目录名
mv opensearch-2.19.1 app
ls /home/opensearch/app    # 期望看到 bin/ config/ jdk/ lib/ modules/ plugins/ …
```

> 升级时**不要**复用它：新版本解包成 `/home/opensearch/app-<新版本>/`（保留旧 `app/` 作回滚点），步骤见 §7.3。

### 3.2 跑官方安装脚本（**这一步决定初始管理员口令怎么来的**）

官方 Tar 页 Step 3"Option 1: Test your OpenSearch settings with security enabled"里的脚本就是它。
**它的实际作用（官方原话归纳）**：应用一份"generic configuration"——**定义一些环境变量、应用自签 TLS 证书**，
并把安全设置写进 `config/opensearch.yml`；**本节点由此获得 demo 安全配置**。

官方同时给了两条硬警告，我们照做：
> "An OpenSearch node configured by the demo security script is **not suitable for a production environment**."
> 至少要把 demo TLS 证书换成自己的，并**更新内部用户与口令**。
> 以及：**2.12 起必须先用 `OPENSEARCH_INITIAL_ADMIN_PASSWORD` 指定初始管理员口令**（否则装不上/起不来）。

```bash
# ① 程序目录归 opensearch 所有
sudo chown -R opensearch:opensearch /home/opensearch/app

# ② 设初始管理员口令（★必须满足官方要求）
export OPENSEARCH_INITIAL_ADMIN_PASSWORD='<强口令>'
#   官方 demo-configuration 页的口令要求（照抄，比"四类字符"更严）：
#     ① 长度 8~100 字符；
#     ② 至少各一个【大写字母、小写字母、数字、特殊字符】；
#     ③ 必须通过 zxcvbn 强度估计并达到 strong 及以上（它衡量熵 ⇒ 常见词、日期、
#        1234/qwerty 这类序列、以及 3 替 E 这种可预测替换都会拉低评级，即使四类字符齐全也会被拒）；
#     ④ 不得与用户名 admin 相似。
#   不满足时官方行为：日志报出未满足的项，OpenSearch 退出。
#   生成示例： openssl rand -base64 24

# ③ 切到程序目录、以 opensearch 用户跑脚本（官方写法：./opensearch-tar-install.sh）
sudo -u opensearch -H bash -lc "cd /home/opensearch/app && OPENSEARCH_INITIAL_ADMIN_PASSWORD='$OPENSEARCH_INITIAL_ADMIN_PASSWORD' ./opensearch-tar-install.sh"
```

> **这一步会改 `config/opensearch.yml`**（写入 demo 的 TLS 与安全设置），并且**会在 `config/` 下生成自签证书**
> （官方 Docker 文档里出现的 `esnode.pem` / `esnode-key.pem` / `root-ca.pem` 就是这套）。
> 所以**下一步必须紧接着做**——用我们的配置接上它（§3.3）。

### 3.3 接入我们的配置（`opensearch.yml` + 堆大小）

```bash
# ① 先备份 demo 脚本刚写的那份（出问题能对照）
sudo -u opensearch cp /home/opensearch/app/config/opensearch.yml \
                      /home/opensearch/app/config/opensearch.yml.demo.bak
# ② 看一眼 demo 都写了什么（下面替换时要保住 transport 那几行，见资产文件里的说明）
sudo -u opensearch grep -nE 'plugins\.security|discovery|network\.host|path\.' \
                      /home/opensearch/app/config/opensearch.yml || true
# ③ 确认证书确实生成了（文件名以实际为准）
sudo -u opensearch ls -l /home/opensearch/app/config/ | grep -E '\.pem$'
```

**先把两份配置放进部署根的 `config/`（**这是我们维护的唯一副本**），再拷进程序目录**

```bash
# ④ 落地"我们维护的副本"到 config/（资产来自仓库；也可以直接 scp 这两个文件过来）
cp /tmp/opensearch.yml.example /home/opensearch/config/opensearch.yml
mkdir -p /home/opensearch/config/jvm.options.d
cp /tmp/heap.options            /home/opensearch/config/jvm.options.d/heap.options

# ⑤ ★把 network.host 改成【本机】内网 IP：.110 写 10.254.212.110，.111 写 10.254.212.111
sudo -u opensearch vi /home/opensearch/config/opensearch.yml
#   该文件里已含：cluster.name: pm-os / node.name: os01 / discovery.type: single-node /
#   network.host / http.port: 9200 / path.data|logs|repo / plugins.security.disabled: false /
#   plugins.security.ssl.http.enabled: false（= 内网 HTTP）/ transport 证书三行
sudo chown -R opensearch:opensearch /home/opensearch/config
sudo chmod 750 /home/opensearch/config
sudo chmod 644 /home/opensearch/config/opensearch.yml /home/opensearch/config/jvm.options.d/heap.options
```

```bash
# ⑥ 【拷贝】进程序目录（不用软链；每次换 app/ 之后都要重复这一步）
sudo -u opensearch cp /home/opensearch/config/opensearch.yml \
                      /home/opensearch/app/config/opensearch.yml
sudo -u opensearch mkdir -p /home/opensearch/app/config/jvm.options.d
sudo -u opensearch cp /home/opensearch/config/jvm.options.d/heap.options \
                      /home/opensearch/app/config/jvm.options.d/heap.options
sudo chown opensearch:opensearch /home/opensearch/app/config/opensearch.yml \
                                  /home/opensearch/app/config/jvm.options.d/heap.options
sudo chmod 644 /home/opensearch/app/config/opensearch.yml \
               /home/opensearch/app/config/jvm.options.d/heap.options

# ⑦ 核对生效值（应能看到 -Xms8g / -Xmx8g；以及我们那份 opensearch.yml 已经在 app/config/ 里）
cat /home/opensearch/app/config/jvm.options.d/heap.options
grep -nE '^(cluster\.name|node\.name|discovery\.type|network\.host|path\.(data|logs|repo))' \
     /home/opensearch/app/config/opensearch.yml
```

> **`config/` 与 `app/config/` 的分工（别搞成两套在打架）**：
> `config/` = **我们维护的源头**（升级时不用重配）；`app/config/` = **运行时真正生效的那份**（每次换 `app/` 后照 §3.3 ⑥ 再拷一次）。
> **用拷贝、不用软链**：软链在 `chown -R`、备份、权限排查时容易变成隐形坑。
> 另外把 unit 与快照脚本也放进 `config/`，这样"部署完就不管"时所有自定义物都在一个目录里：
> ```bash
> cp /tmp/opensearch.service /tmp/snapshot-backup.sh /home/opensearch/config/
> chmod 755 /home/opensearch/config/snapshot-backup.sh
> ls -l /home/opensearch/config/
> ```

### 3.4 权限收口（**一份都不能漏**）

```bash
# 程序目录整体归 opensearch（官方步骤：sudo chown -R opensearch /opt/opensearch/）
sudo chown -R opensearch:opensearch /home/opensearch/app
# 关键文件权限：官方文档反复强调 config 下的文件与 keystore
sudo chmod 644 /home/opensearch/app/config/opensearch.yml
sudo chmod 644 /home/opensearch/app/config/jvm.options.d/heap.options
sudo chmod 755 /home/opensearch/app/plugins/opensearch-security/tools/*.sh 2>/dev/null || true
sudo chmod 600 /home/opensearch/app/config/opensearch.keystore 2>/dev/null || true
ls -l /home/opensearch/app/config/opensearch.yml /home/opensearch/app/config/opensearch.keystore 2>/dev/null
```

```bash
# ★最终确认：以 opensearch 用户身份真的能读到程序与配置（§2.4.1 的第 ④ 步）
sudo -u opensearch ls /home/opensearch/app/bin/opensearch
sudo -u opensearch cat /home/opensearch/app/config/jvm.options.d/heap.options
# 期望：前者打印出该路径、后者打印出 -Xms8g/-Xmx8g
#   报 Permission denied ⇒ 路径上某一级目录缺 x（回到 §2.4.1 逐级 ls -ld），或 app/ 的属主不对
```

> **为什么 0644/0755 反复出现**：官方排障里"权限不对"是启动失败的高频原因——
> `config/opensearch.yml` 必须对 `opensearch` 用户可读；`opensearch.keystore` 必须存在且**只有属主可读**（`600`）；
> `plugins/opensearch-security/tools/*.sh` 要可执行（否则 `hash.sh` / `securityadmin.sh` 用不了）。详见 §7.5。

### 3.5 安全方案：三选一（**必须显式选**，不能"默认飘着"）

> **为什么必须显式处理**：官方 Tar 页 Step 3 写明——demo 脚本会**应用自签 TLS 证书**；
> 它给出的验证命令是 `curl -X GET https://localhost:9200 -u 'admin:<password>' --insecure`，
> 也就是说**默认是 HTTPS**。而我们要的是"内网 HTTP + basic auth"，这**必须显式把 REST 层 TLS 关掉**。

| 方案 | 怎么做（改部署根的 `config/opensearch.yml`，再按 §3.3 ⑥ 拷进 `app/config/`） | 代价/风险 | 建议 |
| --- | --- | --- | --- |
| **A. 内网 HTTP + basic auth（本方案默认）** | `plugins.security.ssl.http.enabled: false`（资产文件里已经写好）；防火墙侧把 9200 只放行应用服务器 | **口令与数据明文过内网**（可被嗅探）；口令泄漏即可直连 | ✅ **推荐**（配合强口令 + §2.5 防火墙来源限制 + 定期轮换） |
| **B. 保留 TLS（自签证书）** | 把 `plugins.security.ssl.http.enabled` 改成 `true`，并配 `plugins.security.ssl.http.pemcert_filepath` / `...pemkey_filepath` / `...pemtrustedcas_filepath`（证书就是 demo 生成的那套，或按官方 Tar 页 Step 4 用 openssl 自己生成） | AI 侧要改成 `https://` 且**必须信任该 CA**（否则只能关校验 = 形同虚设）；自签证书有 730 天有效期，**到期不换就全线连不上**；证书轮换要重启 | 仅当**等保/合规明确要求传输加密**时选 |
| **C. 禁用安全插件** | `plugins.security.disabled: true`（然后**必须整集群重启**） | **不推荐**：等于 9200 无鉴权；官方明确警告——"Disabling this plugin can expose your configuration (including passwords) to the public."，且禁用后安全配置索引（`.opendistro_security`）会暴露 | ❌ **不采用** |

> **三条实现细节（方案 A）**：
> ① `plugins.security.ssl.http.enabled` 是**静态**设置，改完必须 `systemctl restart opensearch`；
> ② 关掉的**只是 REST 这一层**：transport 层 TLS 仍然开启（资产文件里保留了 `esnode.pem` 三行），
>    安全插件仍然生效、basic auth 照常要求 `admin` + 强口令；
> ③ 若把整份 `opensearch.yml` 换成一份**不含 transport 证书行**的干净配置，节点会因缺证书起不来——
>    这也是资产文件里刻意保留那三行的原因。换配置时请改 `config/opensearch.yml`（源头），再拷进 `app/config/`。

> **补充：官方对 tar + systemd 的定位**。官方在同一节写明：tar 的 systemd 示例"only suitable for testing in a
> non-production environment… You should install OpenSearch with the RPM distribution if you want to run OpenSearch
> as a systemd-managed service"，并强调 tar 不定义安装路径/用户/权限，**配错了会有意外行为**。
> 本方案之所以仍选 tar：**新机器不装 Docker、部署完基本不动**；代价是上面这些加固项（专用用户、
> memlock、nofile、开机自启、Restart 策略、权限收口）**必须逐条落实**，不能省。

---

## 4. systemd 与验收

### 4.1 装 unit 并启动

```bash
# ① 放 unit 文件（路径按官方示例：/etc/systemd/system/opensearch.service）
#    资产来自仓库： deploy/opensearch/opensearch.service
sudo cp /tmp/opensearch.service /etc/systemd/system/opensearch.service
sudo chmod 644 /etc/systemd/system/opensearch.service

# ② 刷新 systemd 并开机自启 + 立即启动
sudo systemctl daemon-reload
sudo systemctl enable --now opensearch

# ③ 看状态（官方给的验证方式）
sudo systemctl status opensearch --no-pager
# 期望：Active: active (running)
```

> 首次启动**约 30~90 秒**（JVM + 插件加载）。`systemctl start` 会在 unit 里的 `TimeoutStartSec=75` 之内返回；
> **如果这里就失败，先看日志**（下一条），不要去改配置瞎试。

```bash
# ④ 跟日志（Ctrl-C 退出）
sudo journalctl -u opensearch -f
#   另有文件日志（path.logs）： /home/opensearch/logs/pm-os.log
#   日志里常见的两条"预期内"信息：demo 安全脚本的安装输出、插件加载列表
# ⑤ 复核 unit 施加的上限（§2.2 提到的那两条）
systemctl show opensearch -p LimitNOFILE -p LimitMEMLOCK
# 期望：LimitNOFILE=65536 ／ LimitMEMLOCK=infinity
```

### 4.2 验收（**逐条对照，全过才算部署成功**）

```bash
export OS=http://127.0.0.1:9200
export AUTH='admin:<你设的强口令>'
```

**A1 · 根接口通（HTTP，不是 HTTPS）**
```bash
curl -sS -u "$AUTH" "$OS/" | head -20
```
```json
{
  "name" : "os01",
  "cluster_name" : "pm-os",
  "version" : { "distribution" : "opensearch", "number" : "2.19.1", "build_type" : "tar" },
  "tagline" : "The OpenSearch Project: https://opensearch.org/"
}
```
> `build_type` 应为 **`tar`**（确认确实走的是 tar 安装）。
> 若报 **SSL 相关错误**（如 `wrong version number`），说明 REST 层 TLS 没关掉 —— 回 §3.5 方案 A 检查
> `plugins.security.ssl.http.enabled: false` 是否真的写进了**运行时**那份 `config/opensearch.yml`。

**A2 · 集群健康 = green（★不是 yellow）**
```bash
curl -sS -u "$AUTH" "$OS/_cluster/health?pretty"
```
```json
{
  "cluster_name" : "pm-os",
  "status" : "green",
  "number_of_nodes" : 1,
  "number_of_data_nodes" : 1,
  "active_primary_shards" : 0,
  "unassigned_shards" : 0,
  "active_shards_percent_as_number" : 100.0
}
```
> 期望**恰好 `green`**。**看到 `yellow`** 多半是某个索引设了 `number_of_replicas ≥ 1`——
> **单节点上副本永远无法分配**，状态必然 yellow 且白占一份盘（修法见 §5.1 / §7.5）。

**A3 · 节点就位（单节点 = 1 行）**
```bash
curl -sS -u "$AUTH" "$OS/_cat/nodes?v"
```
```text
ip             heap.percent ram.percent cpu node.role node.roles master name
10.254.212.110           31          62   3 dimr      dimr       *      os01
```
> 单节点只显示 **1 行**、且 `master` 列是 `*`（自己就是 master）——**这是正常的**。

**A4 · 内存锁定生效**
```bash
curl -sS -u "$AUTH" "$OS/_nodes?filter_path=nodes.*.process.mlockall&pretty"
# 期望：{"nodes":{"<node-id>":{"process":{"mlockall":true}}}}
```
> `false` ⇒ §2.3 的锁定没生效（swap 还在或 `LimitMEMLOCK` 没放开），按 §7.5 处置。

**A5 · 索引列表（还没建索引时为空，属正常）**
```bash
curl -sS -u "$AUTH" "$OS/_cat/indices?v&expand_wildcards=all"
# 只有表头 = 还没建索引；做完 §5.2 后应出现 green open pm-ai-chunks … 1 0 … 的行
```

**A6 · 从应用服务器侧验证连通性（**关键**：证明防火墙与 `network.host` 都对）**
```bash
# 在【AI 能力服务所在机器】执行（不是在 OpenSearch 机器上！）
curl -sS -m 5 -u admin:'<强口令>' http://10.254.212.110:9200/_cluster/health
# 期望：{"cluster_name":"pm-os","status":"green",...}
```

```
A1 build_type=tar ｜ A2 green ｜ A3 1 行 ｜ A4 mlockall=true ｜ A5 表头或 green 索引 ｜ A6 跨机可达
```

### 4.3 重启后自动拉起（**"部署完就不管"的底线，必须验**）

```bash
sudo reboot
# —— 重新登录后（等 1~2 分钟让它起完）——
systemctl is-enabled opensearch        # 期望：enabled
systemctl is-active opensearch         # 期望：active
curl -sS -u "$AUTH" http://127.0.0.1:9200/_cluster/health | head -3
# 期望：status green
```

### 4.4 单节点故障演练（**先做一次，避免真出事时误判**）

```bash
# ① 模拟"服务挂掉"（不是删数据）
sudo systemctl stop opensearch
curl -sS -m 3 http://127.0.0.1:9200/ ; echo "exit=$?"
# 期望：连不上（exit=7/28）——【预期现象】，不是部署失败

# ② 此时 AI 侧的表现（★这是"降级行为"的实测，§6.3 要求明确它）
curl -s "http://127.0.0.1:8100/health?with_vec=true"     # 期望：vec 块 not ok（不可用）
#   提问一次：期望【明确报错】，而不是"没找到相关内容"这类静默错答案

# ③ 起来
sudo systemctl start opensearch
curl -sS -u "$AUTH" http://127.0.0.1:9200/_cluster/health | head -3   # green
```

---

## 5. 索引与检索设计

### 5.1 容量与分片（**先看这张表，再决定参数**）

**单条切片的真实口径**（读代码确认）：向量 = 4096 维 `float32` = `4096 × 4 = 16,384 字节 ≈ 16 KB`；
再加原文（≤500 字）、`doc_id`/`filename` 等字段与倒排，**≈18~20 KB/切片**（正文与倒排为**估算（假设：平均每切片 500 字、中文 3 字节/字 ≈1.5KB 原文）**）。

| 规模（切片数） | 对应文档数（估算，假设：30 切片/份） | 向量本体 | **索引总占用（估算，含原文+倒排+段开销，约 ×1.15~1.25）** | `local`（进程内 JSON）对比 |
| --- | --- | --- | --- | --- |
| 2 万 | ≈670 份 | 16KB × 2 万 ≈ **313 MB** | **≈350~400 MB** | 2 万 × 38,333 B ≈ **766 MB**（且每次提问全量解析） |
| 10 万 | ≈3,300 份 | ≈ **1.56 GB** | **≈1.8 GB** | ≈ **3.8 GB** |
| 100 万 | ≈33,000 份 | ≈ **15.6 GB** | **≈18 GB** | ≈ **38 GB**（且单问 +3.8s 起点） |

> **对照口径**：现有 `local` 的实测值是 **38,333 字节/切片（9.36 字节/维）**——那是**十进制文本 JSON**，
> 同一份数据换成 OpenSearch 的 `float32` 二进制后**只占几百 MB**，检索是毫秒级 kNN。
> 结论：**本项目这个量级不需要量化、也不需要 `on_disk`**（那是百万级/磁盘受限时的手段，见 §5.3）。
> 磁盘规划：索引数据放 `/home/opensearch/data`，**给快照留出与索引等量的空间**（§7.2 每天一份、按保留期轮转）。

| 参数 | 值 | 理由 |
| --- | --- | --- |
| `number_of_shards` | **1** | 单节点 + 这个量级（≤100 万切片）：**分片越少越好**。分片是并行度单位，但每个分片都有固定开销（段、堆、文件句柄），1 个分片在单节点上就是最优的检索并行度来源 |
| `number_of_replicas` | **0** | **单节点上副本永远无法分配** ⇒ 只会让状态变 **yellow** 且白占一份盘。**副本不能替代备份**（备份看 §7.2） |
| `refresh_interval` | 写入期 `-1`（关闭），写完改回 `1s` | 批量灌数据时避免频繁 refresh 产生大量小段 |
| `index.knn` | `true` | kNN 索引开关（mapping 同级 settings） |

### 5.2 索引创建（`knn_vector` 4096 维 / cosinesimil / HNSW）

```bash
OS=http://127.0.0.1:9200
AUTH='admin:<强口令>'
curl -sS -u "$AUTH" -XPUT "$OS/pm-ai-chunks" -H 'Content-Type: application/json' -d '{
  "settings": {
    "index": {
      "knn": true,
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "refresh_interval": "-1"
    }
  },
  "mappings": {
    "properties": {
      "vector": {
        "type": "knn_vector",
        "dimension": 4096,
        "space_type": "cosinesimil",
        "method": {
          "name": "hnsw",
          "engine": "lucene",
          "space_type": "cosinesimil",
          "parameters": { "m": 16, "ef_construction": 128 }
        }
      },
      "text":          { "type": "text" },
      "filename":      { "type": "text", "fields": { "kw": { "type": "keyword" } } },
      "doc_id":        { "type": "keyword" },
      "chunk_key":     { "type": "keyword" },
      "page_no":       { "type": "integer" },
      "project_id":    { "type": "keyword" },
      "phase_code":    { "type": "keyword" },
      "attachment_id": { "type": "keyword" },
      "security_level":{ "type": "keyword" },
      "doc_type":      { "type": "keyword" },
      "source":        { "type": "keyword" },
      "created_at":    { "type": "date" }
    }
  }
}'
```
```json
{"acknowledged":true,"shards_acknowledged":true,"index":"pm-ai-chunks"}
```

**字段说明（哪些是代码已有、哪些是本次要新增的）**

| 字段 | 类型 | 来源 | 说明 |
| --- | --- | --- | --- |
| `vector` | `knn_vector`(4096) | **代码已有**（`OpenSearchIndex.ensureIndex`） | 维度必须与平台实际输出一致：**4096**（平台不支持 MRL 降维，传 `dimensions` 会 HTTP 400） |
| `text` | `text` | 代码已有 | 切片原文（≤500 字，**不跨页**，`DocStore.DEFAULT_CHUNK_CHARS=500`）；BM25 用 |
| `filename` | `text` + `.kw` | 代码已有 | 文件名 |
| `doc_id` | `keyword` | 代码已有 | 文档 id |
| `chunk_key` | `keyword` | 代码已有 | 切片键 = `docId:pageNo:sha1(文本)前12位`（`RetrievalService.chunkKey`），**同时用作 `_id`** |
| `page_no` | `integer` | 代码已有 | 页码（来源标注 `[1][2]` 的前提） |
| `project_id` | `keyword` | **本次新增** | 项目 id —— **"先过滤再检索"的合规前提**（今天的切片**没有**这个字段） |
| `phase_code` | `keyword` | **本次新增** | 阶段编码 |
| `attachment_id` | `keyword` | **本次新增** | 主系统附件 id（与 `attachment.ai_doc_id` 对得上） |
| `security_level` | `keyword` | **本次新增** | 密级 —— 权限过滤字段 |
| `doc_type` | `keyword` | **本次新增** | 资料类型（合同/验收/图纸…） |
| `source` | `keyword` | **本次新增** | 来源标记（如 `pm-attachment`） |
| `created_at` | `date` | **本次新增** | 入库时间（按时间收窄检索用） |

> ⚠️ **必须说清楚的一件事**：`project_id`/`security_level` 这几个字段**不是代码自动写入的**——
> 现有 `OpenSearchIndex.toDocument()` 只写 `doc_id`/`filename`/`page_no`/`text`/`chunk_key`/`vector` 六个字段，
> 而**切片本身也没有项目/密级元数据**（这是《AI模型调用与任务队列评估》登记的"最硬的一条"）。
> 所以本节的 filters 示例要真正生效，需要**先把元数据从主系统带进切片**（`attachment_id → project_id/密级` 的映射在主系统崖山库），
> 这件事**属于应用代码改动，不在本轮范围内**（本轮只写文档与部署资产）。过滤器仍然可以先按 `doc_id` 用起来（见 §5.4）。

### 5.3 HNSW 与引擎的版本注记

| 项 | 取值 | 注记 |
| --- | --- | --- |
| `engine` | **`lucene`（推荐默认）** | OpenSearch 自带、无需额外插件配置；**本仓库代码写的就是 `lucene`**（`OpenSearchIndex.ensureIndex`）。与 `local` 后端的暴力余弦结果最接近，便于"顺序对得上"的验证 |
| `engine` | `faiss`（可选） | 需要 `method.parameters.encoder`（如 `"encoder": {"name":"sq","parameters":{"type":"fp16"}}`）；**`m` 等参数名与 lucene 路径不同**，切引擎必须重建索引 |
| `name` | `hnsw` | 近似最近邻图索引 |
| `m` | `16`（建议值） | 每个节点的连边数；越大召回越好、内存与建索引越慢。**未实测**，属工程常规推荐值 |
| `ef_construction` | `128`（建议值） | 建图时的候选队列长度；越大图质量越好、建索引越慢 |
| `space_type` | `cosinesimil` | **与 `local` 后端的余弦同一口径**（这一条**未实测**：集群起来后必须做"top-k 顺序与 local 一致"的对照，见 §5.5 验证口径） |
| `on_disk` / 量化（`sq`/`pq`、`mode: on_disk`） | **本方案不用** | 百万级切片或磁盘受限时才考虑；**版本前提**：磁盘化向量检索是 2.x 后期引入的能力（2.17 起在 k-NN 插件里逐步提供），**本项目当前约 2 万切片规模用不上**。要用请先核对你的版本与官方 k-NN 文档，切了必须**重建索引** |

> **验证口径（做完 §5.5 的对照才算数）**：对同一份语料、同一批问题，把 OpenSearch kNN 的 top-k 顺序与 `local` 的 top-k **逐条对比**；
> 顺序对不上通常意味着 `space_type` 或字段名写错（对照 [`OpenSearchIndex`](../ai-backend/src/main/java/com/pmgt/ai/module/retrieval/OpenSearchIndex.java) 的注释协议：
> 建索引 → 幂等写入 → 查询页码一致 → 顺序与 local 一致）。

### 5.4 写入（`_bulk`，`_id` = 切片键，幂等 upsert）

```bash
# ① 一份 NDJSON：每个文档两行（action + 文档），【每行末尾都要有换行，最后一行也不例外】
cat > /home/opensearch/bulk-demo.ndjson <<'EOF'
{"index":{"_index":"pm-ai-chunks","_id":"d-1001:1:9a1c0f3b7d2e"}}
{"doc_id":"d-1001","filename":"施工合同.pdf","page_no":1,"text":"甲方应于合同生效后 30 日内支付预付款……","chunk_key":"d-1001:1:9a1c0f3b7d2e","project_id":"P2026-001","phase_code":"CONTRACT","attachment_id":"8842","security_level":"内部","doc_type":"合同","source":"pm-attachment","created_at":"2026-09-23T10:00:00+08:00","vector":<4096 个数字>}
EOF

# ② 灌数据
curl -sS -u "$AUTH" -XPOST "$OS/pm-ai-chunks/_bulk?refresh=false" \
  -H 'Content-Type: application/x-ndjson' --data-binary @/home/opensearch/bulk-demo.ndjson
```
```json
{"took":31,"errors":false,"items":[{"index":{"_index":"pm-ai-chunks","_id":"d-1001:1:9a1c0f3b7d2e","_version":1,"result":"created","status":201}}]}
```

> **幂等**：同一份文档写两遍，`_id` 相同 ⇒ `_version` 递增、`result` 变 `updated`，**文档数不翻倍**。
> 这是"不需要额外版本号或重建流程"的前提，也是**可中断续跑**的基础（见 §6.2）。
> **`vector` 的生成**：必须来自平台向量化接口 `POST {LLM_BASE_URL}/embeddings`（`{"model":"Qwen3-VL-Embedding-8B","input":["<切片文本>"]}`，
> 返回 `data[i].embedding`），**不要自己在文档里编造数字**——这里写 `<4096 个数字>` 就是因为本文件不造假数据。

### 5.5 查询（预过滤 + kNN + hybrid）

**① 纯 kNN + filter 预过滤（"先过滤再检索"的落地形态）**
```bash
curl -sS -u "$AUTH" -XPOST "$OS/pm-ai-chunks/_search" -H 'Content-Type: application/json' -d '{
  "size": 5,
  "_source": ["filename","page_no","doc_id","chunk_key","project_id","security_level"],
  "query": {
    "knn": {
      "vector": {
        "vector": <查询向量 4096 个数字>,
        "k": 5,
        "filter": {
          "bool": {
            "filter": [
              { "terms": { "project_id": ["P2026-001"] } },
              { "terms": { "security_level": ["内部","公开"] } },
              { "terms": { "phase_code": ["CONTRACT","ACCEPT"] } }
            ]
          }
        }
      }
    }
  }
}'
```
```json
{
  "took": 12,
  "timed_out": false,
  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
  "hits": {
    "total": { "value": 5, "relation": "eq" },
    "max_score": 0.8612,
    "hits": [
      { "_index": "pm-ai-chunks", "_id": "d-1001:1:9a1c0f3b7d2e", "_score": 0.8612,
        "_source": { "filename": "施工合同.pdf", "page_no": 1, "doc_id": "d-1001",
                     "chunk_key": "d-1001:1:9a1c0f3b7d2e", "project_id": "P2026-001", "security_level": "内部" } }
    ]
  }
}
```

**② hybrid：BM25 与 kNN 融合（**两次查询 + 客户端融合**，最稳的做法）**
```bash
# 关键词路（BM25）：同一套 filter
curl -sS -u "$AUTH" -XPOST "$OS/pm-ai-chunks/_search" -H 'Content-Type: application/json' -d '{
  "size": 50,
  "_source": ["filename","page_no","doc_id","chunk_key"],
  "query": { "bool": {
    "must":   { "match": { "text": "预付款 支付 比例" } },
    "filter": [ { "terms": { "project_id": ["P2026-001"] } },
                { "terms": { "security_level": ["内部","公开"] } } ]
  } }
}'

# 向量路（kNN）：同一套 filter，k=50（对应 RETRIEVAL_RECALL=50）
curl -sS -u "$AUTH" -XPOST "$OS/pm-ai-chunks/_search" -H 'Content-Type: application/json' -d '{
  "size": 50,
  "_source": ["filename","page_no","doc_id","chunk_key"],
  "query": { "knn": { "vector": { "vector": <查询向量>, "k": 50,
    "filter": { "bool": { "filter": [
      { "terms": { "project_id": ["P2026-001"] } },
      { "terms": { "security_level": ["内部","公开"] } } ] } } } } }
}'
```

| 融合方式 | 做法 | 评价 |
| --- | --- | --- |
| **本项目现状（推荐沿用）** | 按 `chunk_key`（= `docId:pageNo:sha1`）去重，**向量命中优先**，再交 Reranker 精排 | 与代码里 `RetrievalService` 的融合口径一致（**不做分数归一**，因此不受两路量纲差异影响） |
| 加权求和 | `score = 0.3 × BM25_norm + 0.7 × kNN_norm` | ⚠️ 必须**各自归一化**后再加权：BM25 与 `cosinesimil` 分数**不同量纲**，直接相加会系统性偏向分数量纲大的那一路 |
| RRF（倒数排名融合） | `score = Σ 1/(k + rank)`，按排名而非分数融合 | 更省心（无需归一化）；**属较新的能力，且各版本支持度不同，用前先核对你的版本文档**（本方案不依赖它） |

> **`size` 与 `k` 的关系**：`size` 是返回条数，`k` 是 kNN 候选数。
> 本项目的口径是**召回 50 → 精排 5**（`RETRIEVAL_RECALL=50` / `RETRIEVAL_TOP_K=5`，
> 工具侧硬上限 `HARD_MAX_TOP_K=10`），所以这里用 `k=50, size=50`，精排由 AI 侧完成。

**③ 建完索引别忘了把 refresh 打开（写入期是 `-1`）**
```bash
curl -sS -u "$AUTH" -XPUT "$OS/pm-ai-chunks/_settings" -H 'Content-Type: application/json' \
  -d '{"index":{"refresh_interval":"1s"}}'
curl -sS -u "$AUTH" "$OS/pm-ai-chunks/_count"      # 期望 {"count":<N>,"_shards":{...}}
```

---

## 6. AI 服务接入与切换/回滚

> 接入侧事实（读代码确认，不要臆造）：`ai-backend/src/main/resources/application.yml:54-62` —
> `RETRIEVAL_RECALL=50`、`RETRIEVAL_TOP_K=5`、`VEC_BACKEND=local`、`OPENSEARCH_URL`（空）、`OPENSEARCH_INDEX=pm-ai-chunks`、
> `OPENSEARCH_USER`/`OPENSEARCH_PASSWORD`；`RetrievalService.checkBackend()` 在 `VEC_BACKEND=opensearch` 时
> **url 为空 / 适配层不可用 / 集群不可达 一律显式抛错，绝不静默退回 `local`**。

### 6.1 改哪些配置（AI 服务器上的 `/home/lhim/pm/ai/.env`）

```bash
cd /home/lhim/pm/ai
cp .env .env.bak.$(date +%Y%m%d%H%M)     # 先备份（回滚就靠它）

vi .env
```
```bash
# ── 检索后端切到 OpenSearch（必改）──
VEC_BACKEND=opensearch
# ★地址写成 http://<os主机>:9200 —— 这个默认值与"关掉 REST 层 TLS"的取舍绑定：
#   若你选了 §3.5 方案 B（保留自签 TLS），这里必须改成 https://…，并让 AI 侧信任那套 CA。
OPENSEARCH_URL=http://10.254.212.110:9200
OPENSEARCH_INDEX=pm-ai-chunks
OPENSEARCH_USER=admin
OPENSEARCH_PASSWORD=<与 OpenSearch 侧 admin 的强口令完全一致>

# ── 检索参数：先别动（保持与 local 一致，才能做"顺序对得上"的对照）──
RETRIEVAL_RECALL=50
RETRIEVAL_TOP_K=5
# 维度：保持不传（平台不支持 MRL 降维，传了 HTTP 400）
VEC_EMBED_DIMENSIONS=0
```
```bash
cd /home/lhim/pm
bash pm.sh restart ai
# 期望：[OK] AI 能力服务(ai) 可用

# 自检要看到【向量那一段】是 opensearch 且 ok
curl -s "http://127.0.0.1:8100/health?with_vec=true"
```

| 配置项 | 值 | 不改的后果 |
| --- | --- | --- |
| `VEC_BACKEND` | `opensearch` | 还是进程内检索，本次部署白做 |
| `OPENSEARCH_URL` | `http://10.254.212.110:9200` | **必须是从 AI 容器内可达的地址**：写 `127.0.0.1` = 容器自己，**必然连不上** |
| `OPENSEARCH_USER` / `OPENSEARCH_PASSWORD` | `admin` / 强口令 | 401；口令含特殊字符时 `.env` 里**不要加引号**（compose 会把引号当值的一部分） |

> **技术选型（接入侧刻意保持的纪律）：走原生 REST，不引入 OpenSearch Java 客户端。**
> 依据：现有 [`OpenSearchIndex`](../ai-backend/src/main/java/com/pmgt/ai/module/retrieval/OpenSearchIndex.java) 就是
> `java.net.http.HttpClient` + Jackson 手写 REST（`PUT /<index>`、`POST /<index>/_bulk`、`POST /<index>/_search`、
> `HEAD /<index>`、`GET /_cluster/health` + Basic 认证头），**零第三方依赖**，与"不引新依赖"的既有纪律一致；
> 而官方 Java 客户端会引入**版本联动**（客户端版本要与集群版本匹配）与额外的离线依赖分发成本。
> **代价（要认账）**：kNN 查询体、错误码语义、连接池与认证头全部自己维护——例如 `_bulk` 的 NDJSON **末尾必须换行**、
> `dimension`/`space_type` 写错**没有编译期提示**；所以 §5.5 的"与 local 的 top-k 顺序对照"不是可选项。

### 6.2 全量重建索引（现有切片必须重新向量化）

**现状（读代码确认，这是最容易踩的坑）**：向量化/写入**不在入库路径上**——
`DocStore.save` 只落文本，**第一次检索**才在问答请求里惰性补算（`RetrievalService.embedChunks`，串行批 `EMBED_BATCH=32`），
写索引也在那条路径里（`ensureIndexOnce → upsertAll`）。**因此"切完 `VEC_BACKEND` 就完事"是错的**：
第一个提问会扛下整个语料的重建工作，**必然撞穿前端 180s 超时**。

**正确顺序：先把索引灌满并核数，再切开关。**

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| **① 备份现状** | 备份 `work/vectors/` 与 `work/docs/`（`tar -czf vectors-$(date +%F).tar.gz work/vectors work/docs`） | 回滚 `local` 不需要重算 |
| **② 摸清规模** | `curl -s http://127.0.0.1:8100/documents` 数文档；或直接数 `work/docs/*.json` 与切片总数 | 得到 **N_chunks**（后续所有时间估算都用它） |
| **③ 生成切片 NDJSON** | 从 `work/docs/<doc_id>.json` 逐页按 500 字**不跨页**切片，照 §5.4 的两行格式落成 `ndjson/part-0001.ndjson`… | 文件数 × 每片条数 ≈ N_chunks |
| **④ 生成向量并写回 NDJSON** | 按 `POST {LLM_BASE_URL}/embeddings`（`input` 一次最多 **32 条** = `EMBED_BATCH`）批量取向量，**并发 8** | 吞吐参考 **147.9 条/s**（平台手册实测口径，见下） |
| **⑤ `_bulk` 灌入** | `curl --data-binary @part-000N.ndjson "$OS/pm-ai-chunks/_bulk"`，并发 2~4 | 返回 `"errors":false` |
| **⑥ 核数** | `curl "$OS/pm-ai-chunks/_count"` 与 ② 的 N_chunks 对比 | 一致才算灌完 |
| **⑦ 切开关** | §6.1，然后 `bash pm.sh restart ai` | `/health?with_vec=true` 里 `opensearch` ok |
| **⑧ 对照验证** | 抽 20~30 个问题，对比 kNN top-k 与切换前 `local` 的 top-k **顺序**与**页码** | 顺序对得上才继续（对不上查 `space_type`/字段名） |

**时间估算（`估算（假设：吞吐取平台手册实测 147.9 条/s、并发 8、忽略网关排队）`）**

| 规模 | 纯向量化 | 含 `_bulk` 写入与网络（估算 ×1.5） | 说明 |
| --- | --- | --- | --- |
| 5,000 切片 | ≈ 34 s | **≈ 1 分钟** | 小规模，随手可重跑 |
| 2 万切片 | ≈ 135 s | **≈ 3~4 分钟** | 对应 §5.1 的"≈670 份文档" |
| 10 万切片 | ≈ 11 分钟 | **≈ 17~20 分钟** | 建议分片并行、分批灌 |
| 100 万切片 | ≈ 113 分钟 | **≈ 3 小时** | 必须分批 + 断点续跑，别想一次跑完 |

> **"必须带进度与可中断续跑"怎么落地**：以 **`chunk_key`** 为唯一键——
> 每次跑完把已成功写入的 `chunk_key` 追加到 `done.txt`，重跑时用 `grep -vFxf done.txt` 过滤；
> **不要按文件或按文档记进度**（部分成功的批次会整段重跑）。查询侧同理：`_bulk` 的 `_id` 相同 ⇒ 重写幂等，不会翻倍。
> **不要用"删索引重来"当重试手段**（那会把已成功的几十分钟一起丢掉）。

### 6.3 双写 / 灰度与**降级行为（必须定清楚）**

| 阶段 | 做法 | 判据 |
| --- | --- | --- |
| 灰度①（影子验证） | `VEC_BACKEND` 仍是 `local`；把 §6.2 的索引照常灌满，只做"离线对照"（不经 AI 服务） | kNN top-k 与 local 顺序一致 |
| 灰度②（单机切换） | 只在一台 AI 上切 `opensearch`；观察 1~2 天 | 自检 ok；问答引用页码正确；`/ai` 页检索调试无异常 |
| 灰度③（全量） | 另一台（若将来有 2 个实例）再切 | 两实例结果一致 |
| 回滚 | `VEC_BACKEND=local` + `bash pm.sh restart ai`；`work/vectors/` 未删 ⇒ **无需重新向量化**，秒级回滚 | 自检里 backend 回到 `local` 且检索可用 |

> **双写**：现有代码**没有**"同时写 local 与 OpenSearch"的双写开关（`VEC_BACKEND` 是**单选**）。
> 所以"双写"不通过配置实现，而是靠**保留本地 `work/vectors/` 不删**——它天然就是 `local` 的完整副本，
> 这已经等价于"双份数据 + 秒级回滚"。**不要**为了双写去改代码（属于应用改动，不在本轮范围）。

**★ OpenSearch 不可用时 AI 侧怎么降级（本轮必须定死的口径）**

| 口径 | 定稿 |
| --- | --- |
| **绝不静默降级** | 不允许"连不上就悄悄改用进程内余弦或退回关键词，却不说"——那会让用户拿到**看起来正常但来源不同**的答案。现有代码已钉住这条（**显式抛错**） |
| 用户可见 | 问答返回**明确失败**（"检索服务不可用"类话术），**不是**"没找到相关内容" |
| 运维可见 | AI 日志有明确原因（"集群不可达（http://…）"）；`/health?with_vec=true` 的 `vec` 块为 not ok |
| 恢复 | OpenSearch `systemctl start opensearch` 后 AI **不需要重启**（每次请求现建连接），下一次提问即恢复 |

### 6.4 第二台机器 `10.254.212.111`：**冷备 + 演练机**

| 项 | 做法 |
| --- | --- |
| 角色 | **冷备**（同构安装、同样的 unit，**服务默认 `systemctl disable --now`**）+ **恢复演练机** |
| 为什么默认停着 | 单节点方案下它起来也**不参与**任何服务（没有 quorum、没有副本）；不如把资源留给"随时能顶上去" |
| 每天做什么 | ① 在 `.110` 上打快照（§7.2）；② `snapshot-backup.sh` 自动把快照目录 **`rsync` 到 `.111`** |
| 何时真的切过去 | `.110` 硬件故障且短期修不好 → 在 `.111` 上 `systemctl start opensearch` + 注册仓库 + `_restore`（RTO 分钟级） |
| 何时不用它了 | ① 需要**两个 AI 实例**做无中断检索；② 切片 **>50 万** → 加**第 3 台**转 3 节点集群（附录 A） |

```bash
# 在 .111 上（一次性准备；注意：默认不 enable，避免和 .110 抢角色）
sudo systemctl disable --now opensearch 2>/dev/null || true
# 目录先建好、属主给对（rsync 过来的快照要能被 opensearch 读）
sudo mkdir -p /home/opensearch/backup/fs-repo && sudo chown -R opensearch:opensearch /home/opensearch/backup
```

**恢复演练步骤（建议每季度一次；演练时 `.110` 正常跑着，不动它）**

```bash
# 在 .111 上执行
# ① 确认快照副本在（.110 每晚 rsync 过来的）
ls -l /home/opensearch/backup/fs-repo/ | head
sudo chown -R opensearch:opensearch /home/opensearch/backup/fs-repo

# ② 起这个"独立空集群"
sudo systemctl start opensearch
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cluster/health | head -3    # 期望 green

# ③ 注册仓库（与 .110 同名同路径）
curl -sS -u admin:'<强口令>' -XPUT 'http://127.0.0.1:9200/_snapshot/os-backup' \
  -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/home/opensearch/backup/fs-repo","compress":true,"index.latest":false}}'

# ④ 看有哪些快照
curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/_snapshot/os-backup/_all?pretty' | grep -E '"snapshot"|"state"' | head

# ⑤ 恢复（演练机上索引通常是空的，可直接恢复）
curl -sS -u admin:'<强口令>' -XPOST 'http://127.0.0.1:9200/_snapshot/os-backup/nightly-<日期>/_restore?wait_for_completion=true' \
  -H 'Content-Type: application/json' -d '{"indices":"pm-ai-chunks","include_global_state":false}'

# ⑥ 核数：与 .110 上的 _count 一致
curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/pm-ai-chunks/_count'

# ⑦ 演练完停掉，别让它长期跑着（避免以后误判"哪个是主"）
sudo systemctl stop opensearch
```

| 指标 | 口径 |
| --- | --- |
| RTO（恢复时间目标） | **分钟级**（估算（假设：索引 ≈2 万切片 ≈400MB，本地 `_restore` 速率按 20~50MB/s）⇒ **约 10~60 秒**，加起服务≈90s、注册仓库与核数操作 ⇒ **约 5 分钟**） |
| RPO（可接受的数据丢失） | **≈1 天**（每晚一份快照）。要更小就把 cron 频次提高（对索引无影响，只多占盘） |
| 演练判据 | ⑥ 的 `count` 与主节点一致，且随机抽 3 个问题能检索到相同页码 |

### 6.5 切换前检查清单（**逐条打勾，缺一不可**）

```
□ A1~A6 全绿（§4.2），且 _cluster/health 是 green（不是 yellow）
□ 索引映射已建：dimension=4096、space_type=cosinesimil、number_of_replicas=0（§5.2）
□ _count 与 N_chunks 一致；抽样 20 条，page_no 与原文页码对得上（§6.2 ⑥⑧）
□ AI 那台能跨机访问 9200（§4.2 A6），且【不该通的机器确实不通】（§2.5 ④）
□ ai/.env 已备份（.env.bak.<时间戳>）；OPENSEARCH_PASSWORD 与 OpenSearch 侧 admin 口令完全一致
□ work/vectors/ 与 work/docs/ 已备份（回滚 local 的保险）
□ 降级口径已确认（§6.3）：连不上时【明确报错】，不静默换检索方式
□ 快照脚本已跑通至少一次（bash snapshot-backup.sh snapshot 返回 0）+ cron 已装（§7.2）
□ 从 .111 做过一次恢复演练（§6.4 七步）
□ 有人知道回滚怎么做（VEC_BACKEND=local + pm.sh restart ai，秒级）
```

**本节说明：顺带拿到的一条收益**

- **AI 服务从"必须单实例"变成"可跑 2 个实例"**：`VEC_BACKEND=local` 是**进程内状态**（向量缓存 + 文档库都在本地卷上），
  这正是它不能 HA 的根因；向量与索引搬进 OpenSearch 后，检索侧不再依赖本地进程状态，AI 才谈得上水平扩。
  ⚠️ **但要如实说边界**：`work/docs/`（文档库）与 `work/vectors/`（缓存）**仍是本地卷**（代码现状），
  所以"完全无状态 + 2 实例"还需要**把文档库也搬出进程**——这一步**不在本轮方案内**（§8 未确认项）。
  本次部署的确定收益是：**检索不再受单实例约束、延迟与内存不再随语料线性劣化、可以做"先过滤再检索"**。

---

## 7. 运维（systemd + tar 语汇）

### 7.1 日常命令速查

```bash
sudo systemctl status opensearch            # 状态（先看这个）
sudo journalctl -u opensearch -n 200        # 最近 200 行（journald）
sudo journalctl -u opensearch -f            # 跟日志
sudo tail -f /home/opensearch/logs/pm-os.log   # 文件日志（path.logs）
sudo systemctl restart opensearch           # 重启（改 opensearch.yml / heap.options 后必须重启）
sudo systemctl stop opensearch              # 停
sudo systemctl start opensearch             # 起
sudo systemctl is-enabled opensearch        # 是否开机自启
curl -sS -u admin:'<口令>' http://127.0.0.1:9200/_cluster/health?pretty
```

> **日志在哪**：① journald（`journalctl -u opensearch`，unit 里 `StandardOutput=journal`）；
> ② 文件日志 `/home/opensearch/logs/`（`path.logs`，主日志名为 `<cluster.name>.log`，即 `pm-os.log`）。
> 排查时**两个都看**：启动期失败往往只在 journald 里，运行期异常两条都有。

### 7.2 快照与恢复（**单节点的强制项**）

用资产脚本 [`snapshot-backup.sh`](../deploy/opensearch/snapshot-backup.sh)（幂等：重复执行安全）：

```bash
# ① 给脚本准备口令（不给就只能把口令写进命令历史，cron 更不安全）
printf '%s\n' '<强口令>' | sudo tee /etc/opensearch-backup.pass >/dev/null
sudo chmod 600 /etc/opensearch-backup.pass

# ② 手动跑一次（验证全链路：注册仓库 → 打快照 → 清理 → rsync 到 .111）
bash /home/opensearch/config/snapshot-backup.sh snapshot
# 期望末尾：[OK] 快照任务完成：nightly-<日期>
# 任一步失败都会打印"下一步该敲什么命令"

# ③ 只看状态（不做任何写操作）
bash /home/opensearch/config/snapshot-backup.sh status
```
```bash
# ④ 装 cron（每晚 02:30；脚本自己会打印这行，直接复制）
crontab -e
# 加入：
30 2 * * * /bin/bash /home/opensearch/config/snapshot-backup.sh snapshot >> /home/opensearch/logs/snapshot-cron.log 2>&1
crontab -l      # 复核
```

| 项 | 值/建议 |
| --- | --- |
| 仓库 | 文件系统仓库 `os-backup` → `/home/opensearch/backup/fs-repo`（必须在 `path.repo` 之下） |
| 频次 | **每晚一次**（RPO ≈1 天）；要更小就提高 cron 频次 |
| 保留 | 脚本默认 **7 天**（`KEEP_DAYS=7`；更早的 `nightly-*` 自动删） |
| 异地 | 每晚 `rsync` 到 `.111`（**单节点方案里防硬件损坏的唯一手段**） |
| 演练 | 每季度一次（§6.4 七步），**演练过才算有备份** |

```bash
# 手工等价命令（脚本坏了时的兜底）
curl -sS -u admin:'<口令>' -XPUT 'http://127.0.0.1:9200/_snapshot/os-backup' -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/home/opensearch/backup/fs-repo","compress":true,"index.latest":false}}'
curl -sS -u admin:'<口令>' -XPUT "http://127.0.0.1:9200/_snapshot/os-backup/nightly-$(date +%F)?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{"indices":"pm-ai-chunks","ignore_unavailable":true,"include_global_state":false}'
curl -sS -u admin:'<口令>' 'http://127.0.0.1:9200/_snapshot/os-backup/_all?pretty' | grep -E '"snapshot"|"state"'
```

### 7.3 升级路径（官方 Tar 升级做法 + 回滚）

> **不变式**：**数据（`/home/opensearch/data`）与配置（`/home/opensearch/config/`）都在程序目录之外**，
> 所以升级 = 换掉 `app/`，数据与配置原地保留。**新版先解包到 `app-<版本>/`，最后一步才改名成 `app/`**——
> 这样"新程序就位"与"切换生效"之间有一道明确的边界。

| 目录 | 升级过程中的角色 |
| --- | --- |
| `/home/opensearch/app/` | 当前正在跑的程序（升级后被改名成 `app-<旧版本>/`） |
| `/home/opensearch/app-<新版本>/` | 新版本解包并配好后的暂存目录（最后改名成 `app/`） |
| `/home/opensearch/app-<旧版本>/` | 回滚点（确认新版稳定后可删） |

```bash
# ① 先打一份快照（升级前必做；出问题能回到原点）
bash /home/opensearch/config/snapshot-backup.sh snapshot

# ② 记录当前版本（验收时要对比）
cat /home/opensearch/app/VERSION 2>/dev/null || ls -d /home/opensearch/app

# ③ 解包新版本到 app-<新版本>/（★不要覆盖 app/，旧的要留着回滚）
cd /home/opensearch
tar -xvf /tmp/opensearch-<新版本>-linux-x64.tar.gz
mv opensearch-<新版本> app-<新版本>
ls -d /home/opensearch/app*        # 期望：app/ 与 app-<新版本>/ 并存
```

```bash
# ④ 把我们维护的配置拷进新程序目录（源头永远是部署根的 config/）
cp /home/opensearch/config/opensearch.yml \
   /home/opensearch/app-<新版本>/config/opensearch.yml
mkdir -p /home/opensearch/app-<新版本>/config/jvm.options.d
cp /home/opensearch/config/jvm.options.d/heap.options \
   /home/opensearch/app-<新版本>/config/jvm.options.d/heap.options

# ⑤ ★证书与安全配置也要带过去（它们由 demo 脚本生成，只存在于旧 app/config/ 下）
cp -a /home/opensearch/app/config/*.pem                  /home/opensearch/app-<新版本>/config/ 2>/dev/null || true
cp -a /home/opensearch/app/config/root-ca.pem            /home/opensearch/app-<新版本>/config/ 2>/dev/null || true
cp -a /home/opensearch/app/config/opensearch-security    /home/opensearch/app-<新版本>/config/ 2>/dev/null || true
cp -a /home/opensearch/app/config/opensearch.keystore    /home/opensearch/app-<新版本>/config/ 2>/dev/null || true
ls -l /home/opensearch/app-<新版本>/config/ | grep -E '\.pem$|security|keystore'
#   不带这些，安全插件会因为"没有证书/配置"起不来（对照官方 Tar 页的 warning：demo 配置要自己接管）
```

```bash
# ⑥ 停服 + 换目录（旧 app/ 改名保留 = 回滚点）
sudo systemctl stop opensearch
sudo mv /home/opensearch/app           /home/opensearch/app-<旧版本>
sudo mv /home/opensearch/app-<新版本>   /home/opensearch/app

# ⑦ 权限归位（换目录后必须重做，属主会跟着改名的目录走，但确认一次更稳）
sudo chown -R opensearch:opensearch /home/opensearch
sudo chmod 644 /home/opensearch/app/config/opensearch.yml \
               /home/opensearch/app/config/jvm.options.d/heap.options
sudo -u opensearch ls /home/opensearch/app/bin/opensearch     # 最终确认能读到

# ⑧ 启动 + 验收
sudo systemctl start opensearch
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/ | head -12                 # 看 version.number 是否新版本
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cluster/health | head -3   # 期望 green
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cat/nodes?v                # 1 行
```
```bash
# 回滚（升级后起不来或行为异常）：把目录换回去再起（就是 ⑥⑦⑧ 的反操作）
sudo systemctl stop opensearch
sudo mv /home/opensearch/app            /home/opensearch/app-failed-<新版本>
sudo mv /home/opensearch/app-<旧版本>    /home/opensearch/app
sudo chown -R opensearch:opensearch /home/opensearch
sudo systemctl start opensearch
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cluster/health | head -3   # 期望 green
# ⚠️ 若新版本【已经写过数据】，直接回退旧版本可能读不了新格式 → 那时用快照恢复（§7.2）
```

> **确认新版稳定后**再删旧目录：`sudo rm -rf /home/opensearch/app-<旧版本>`（先确认快照里有一份可恢复的数据）。
> **大版本（2.x → 3.x）不要直跳**：按官方"先升到最后一个 2.x，再升 3.x"的路径走，每步都打快照。
> 本方案是单节点，停机升级即可（分钟级）。

### 7.4 监控要看哪些指标

| 类别 | 指标 | 看它判什么 | 怎么看 |
| --- | --- | --- | --- |
| **JVM 堆** | 堆使用率（`heap.percent`）、GC 统计 | 堆长期 >85% 或 GC 停顿变长 ⇒ 调 heap.options 或扩内存 | `_cat/nodes?v`、`_nodes/stats/jvm` |
| **内存锁定** | `mlockall` | 必须 `true`（§4.2 A4） | `_nodes?filter_path=nodes.*.process.mlockall` |
| **线程池** | `search`/`write` 队列与 `rejected` | 有拒绝 ⇒ 检索/写入压力超容量 | `_cat/thread_pool/search,write?v&h=node_name,active,queue,rejected` |
| **磁盘** | 使用率与水位 | §7.5 的水位判读；**单节点最容易死在这里**（快照也吃盘） | `_cat/allocation?v`、`df -h /home/opensearch` |
| **段与合并** | 段数、merge 次数/耗时 | 段数暴涨（批量写入期）⇒ 用 `_forcemerge` 收口 | `_cat/segments/pm-ai-chunks?v` |
| **kNN** | `_plugins/_knn/stats` | 图未被缓存 ⇒ 查询走磁盘 | `GET _plugins/_knn/stats?pretty` |
| **集群状态** | `status`、`unassigned_shards`、`number_of_pending_tasks` | yellow/red 判读见 §7.5 | `_cluster/health`、`_cat/pending_tasks?v` |
| **快照** | 最近快照 `state` 与耗时 | 失败要在 24h 内发现（否则等于没有备份） | `bash /home/opensearch/config/snapshot-backup.sh status` |
| **服务本身** | systemd 状态、重启次数 | `Restart=on-failure` 生效说明崩过 | `systemctl status opensearch`（看 `Main PID` 与 `Active`） |

> ⚠️ **本项目 AI 侧目前没有 actuator/micrometer**（《AI模型调用与任务队列评估》已登记"无任何指标"），
> OpenSearch 侧也**没有接 Prometheus/告警**——本节指标目前**只能靠命令巡检**。要不要上监控栈见 §8。

### 7.5 常见故障

| 现象 | 判读 | 处置 |
| --- | --- | --- |
| 启动即退出，日志 `max virtual memory areas vm.max_map_count [65530] is too low` | 内核参数没设或没持久化（重启后丢了） | §2.1 全做一遍；**进程内改不了**，必须在宿主设 |
| 直接拒绝启动并提示不能以 root 运行 | 用 root（或 root 的 unit）拉起来了 | unit 里 `User=opensearch` / `Group=opensearch`（资产已设）；手工调试也要 `sudo -u opensearch …` |
| **systemd 启动失败，日志报权限不足 / `status=200/CHDIR` / 找不到 `/home/opensearch/app/bin/opensearch`** | 属主或权限不对（D 方案下**不再**是家目录穿透问题：部署根就是 `opensearch` 自己的家目录） | **§2.4.1 / §2.4 ⑥**：`sudo -u opensearch ls -d /home/opensearch` 与 `sudo -u opensearch ls /home/opensearch/app/bin/opensearch`；不对就 `sudo chown -R opensearch:opensearch /home/opensearch`。若仍报错且系统开了 SELinux，看 `ls -Z /home/opensearch` 与 `sudo ausearch -m avc -ts recent` |
| 启动失败：`/home/opensearch` 所在盘不可写 / 数据目录报错 | `/home` 是 NFS/autofs，或空间不足 | §2.4.2：`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home`；按"退路"把 `path.data` 指到本地盘 |
| 启动失败：`Permission denied` 且 `/home` 挂载选项里有 **`noexec`** | `noexec` 会导致 JVM/`bin/opensearch` 无法执行 | 把部署根挪到非 `noexec` 的盘（或让平台方去掉 `noexec`；**`nosuid`/`nodev` 无需处理**，见 §2.4.2） |
| 启动失败：`OPENSEARCH_INITIAL_ADMIN_PASSWORD` 相关报错 / demo 配置装不上 | 口令没设或**不满足官方要求**（8~100 字符、四类字符、zxcvbn 达 strong、不与 admin 相似） | 换强口令（`openssl rand -base64 24`），重新跑 §3.2 |
| 启动报权限错误：`AccessDeniedException` / 读不到 `config/opensearch.yml` | 属主或权限不对 | §3.4：`chown -R opensearch:opensearch`；`config/opensearch.yml` 644；`opensearch.keystore` 600；`plugins/opensearch-security/tools/*.sh` 755 |
| 日志报 keystore 相关错误 | `config/opensearch.keystore` 缺失或权限不对 | `sudo -u opensearch /home/opensearch/app/bin/opensearch-keystore list` 看是否可读；必要时重建并用 systemd 环境变量注入敏感值 |
| 日志报"没有证书 / 集群形成失败（`Transport client authentication no longer supported` 之类）" | 我们替换 `opensearch.yml` 时**把 transport 证书那几行丢了** | 对照 §3.5 与资产文件，把 `plugins.security.ssl.transport.*` 三行补回；证书文件在 `config/*.pem`，确认存在 |
| curl 报 SSL 错误（`wrong version number` / `certificate verify failed`） | REST 层 TLS 与请求协议不匹配：官方默认是 HTTPS，我们要 HTTP | 确认运行时 `config/opensearch.yml` 里是 `plugins.security.ssl.http.enabled: false`（§3.5 方案 A），改完 `systemctl restart opensearch` |
| curl 用 HTTP 时报 `Empty reply from server` | 仍是 HTTPS 端口在应答 | 同上；先用 `curl -k https://127.0.0.1:9200/` 确认服务本身活着 |
| 认证失败 `401 Unauthorized` | 用户名/口令不对 | `curl -u admin:'<口令>'` 单独验证；口令含特殊字符时**用单引号包住**；确认用的是本轮设的强口令（不是 demo 默认值） |
| **`yellow`** | 主分片就绪、**副本未分配** | **单节点上几乎总是"某个索引设了 replica ≥1"**：`PUT /<索引>/_settings {"index":{"number_of_replicas":0}}`；用 `_cat/indices?v&health=yellow` 找出来逐个改 |
| **`red`** | 有**主分片**未分配 ⇒ 检索/写入报错 | `_cat/indices?v` 与 `_cluster/allocation/explain`；单节点上常见原因是**数据目录权限**（§2.4）或**磁盘满** |
| 磁盘满 / 索引变只读（flood stage） | 达 95% 水位 | ① 清旧快照（脚本保留期调小）；② 解锁：`PUT /<索引>/_settings {"index.blocks.read_only_allow_delete":null}`；③ 复核 §7.1 的水位 |
| 首次检索特别慢（几十秒~分钟级） | **惰性向量化在问答路径上**（§6.2 现状） | 按 §6.2 先灌满索引再切 `VEC_BACKEND`；临时手段是重复发几次同样的提问 |
| 快照失败 `repository_exception` / 报目录不可写 | 仓库目录属主不对或不在 `path.repo` 之下 | `sudo chown -R opensearch:opensearch /home/opensearch/backup`；核对 `path.repo` 与脚本 `REPO_LOCATION` 的父子关系 |
| `snapshot-backup.sh` 报无法免密 ssh | `rsync` 到 `.111` 需要免密 | `ssh-copy-id lhim@10.254.212.111`；或临时 `SKIP_REMOTE=1`（但**别长期跳过**，否则没有异地副本） |
| AI 日志"集群不可达（http://…）" | 网络/防火墙/`network.host` 三者之一 | 在 AI 那台跑 §4.2 A6；核对 `network.host` 与 §2.5 白名单 |

---

## 8. 未确认项（**照实登记，需要你或平台方确认**）

| 序号 | 未确认项 | 影响 | 谁来确认 |
| --- | --- | --- | --- |
| 1 | **官方当前 tar 版本号与其 SHA512**（本文按 `2.19.1` 写，并已实测该版本的下载 URL 与 `.sha512` 可用；**换版本必须重新核对**） | 决定 §1 的下载/校验命令与文件名 | 你（在能联网的机器上核对官方下载页 + `.sha512`） |
| 2 | **AI 能力服务（ai-backend）实际跑在哪台机器上** | 决定 §6.1 的 `OPENSEARCH_URL` 与 §2.5 的防火墙白名单 | 你 |
| 3 | 磁盘类型与容量（本次给定 200GB；要确认 SSD/HDD、是否单盘） | HDD 上 kNN 检索延迟会明显变差；单盘则数据与快照抢 I/O | 你 / 平台方 |
| 4 | 是否允许在业务网段使用内网 HTTP（§3.5 方案 A） | 若等保/合规要求传输加密，必须改走方案 B（自签 TLS + AI 侧信任策略） | 平台方（安全/合规） |
| 5 | 是否需要 OpenSearch Dashboards | 要的话需额外下载 Dashboards 包（**版本必须与 OpenSearch 一致**）并给它一套 `opensearch_dashboards.yml` 与端口 | 你 |
| 6 | 时间源（内网 NTP 服务器地址） | 时间不同步会让日志/快照时间戳不可比 | 平台方 |
| 7 | 防火墙实现（firewalld / iptables；麒麟发行版差异） | 决定 §2.5 用哪段命令 | 你（`systemctl is-active firewalld`） |
| 8 | 快照保留期与执行时间（本文默认每晚 02:30、留 7 天） | 影响备份盘容量与 RPO | 你 |
| 9 | 现网真实文档数与切片总数（§5.1 规模表按 30 切片/份估算） | 决定"要不要现在就上"以及 §6.2 的重建耗时 | 你（`/documents` 或自检页） |
| 10 | AI 容器是否设置 `-Xmx`（《AI模型调用与任务队列评估》登记为未确认） | 与"内存触发点"（第 2 条）直接相关 | 你 |
| 11 | **"切片带项目/密级元数据"的落地排期**（§5.2 的 `project_id`/`security_level` 目前无人写入） | 不做这一步，OpenSearch 的"先过滤再检索"只是**能力就绪**、不是**功能可用** | 你 / 开发方 |
| 12 | 平台是否真的不支持降维（本仓库实测传 `dimensions` → HTTP 400） | 决定 `VEC_EMBED_DIMENSIONS` 永远为 0（本文按"不传"写） | 平台方 |
| 13 | 是否要上监控/告警栈（Prometheus、Grafana、OpenSearch 告警插件） | §7.4 的指标目前只能手工巡检 | 你 |
| 14 | `.111` 演练机的 ssh 免密是否允许配置（`snapshot-backup.sh` 的 rsync 需要） | 不配就只能 `SKIP_REMOTE=1`，等价于没有异地副本 | 你 |
| 15 | **`/home` 是否确实是本地盘且空间够**（D 方案把部署根放在 `/home/opensearch`） | 若 `/home` 是 NFS/autofs 或空间不足，需按 §2.4.2 的退路把 `path.data` 指到别的盘；若 `/home` 带 `noexec` 则要换部署根 | 你（`findmnt -no SOURCE,FSTYPE,OPTIONS /home` + `df -h /home`） |
| 16 | **是否允许 5601 对内网运维网段/跳板机开放**（Dashboards） | 决定 §12 的防火墙规则；**不能对全网开放** | 你 / 平台方 |
| 17 | **Dashboards 是否改用专用只读账号（而不是 `admin`）** | 用 `admin` 最省事但权限过大；只读账号更合规，需额外建角色与用户 | 你 / 平台方（安全） |
| 18 | **是否需要多租户（`opensearch_security.multitenancy.enabled`）** | 开启后 saved objects 按租户隔离；官方示例 yml 明确写了它与 workspace 特性互斥 | 你 |

> **已由本轮决定解决、故不再列为未确认**：部署形态（**tar + systemd，不用 Docker**）、节点数（**单节点**，§0.3）、
> 第二台机器的作用（**冷备 + 演练机**，§6.4）、副本数（**0**，§5.1）、堆大小（**8g / 16GB 内存的 50%**，§3.3）、
> 机器架构（**x86_64**，§0.2）、**部署根目录（`/home/opensearch/`，程序/配置/数据/日志/备份分离）**、
> **建账号方式（有家目录的普通账号 + `passwd -l` 锁登录）**、**是否需要 Dashboards（需要，已落地为 §12）**。

---

## 9. 附录 A · 将来加第 3 台 → 3 节点集群（升级路径）

> **什么时候做**：① 需要**两个 AI 实例**做无中断检索；② 切片 **>50 万**（单节点磁盘/恢复窗口吃紧）。其余情况**不要动**（§0.3）。

| 参数 | 单节点现值 | 3 节点改为 | 说明 |
| --- | --- | --- | --- |
| `discovery.seed_hosts` | **不写** | `os01,os02,os-mgr` | 3 个节点的**可解析名或 IP**；顺序不重要，但要**一致** |
| `cluster.initial_master_nodes` | **不写** | `os01,os02,os-mgr` | ★**只能在数据目录为空时写一次**：写错/改错必须清 `data/` 重来（最容易踩的坑） |
| `discovery.type` | `single-node` | **删掉这一行** | 集群模式不能有它 |
| 第 3 台角色 | —— | `node.roles: [cluster_manager]` | **dedicated cluster-manager，不存数据**（可用轻量机） |
| 数据节点角色 | （默认全角色） | `node.roles: [data, ingest]` | 两台老机器 |
| 副本数 | **0** | **1** | 此时才有第二台机器放副本，green 才成立 |
| heap | `-Xms8g -Xmx8g` | 数据节点不变；管理节点用 `-Xms2g -Xmx2g`（**估算（假设：管理节点不存数据）**） | 各写各的 `jvm.options.d/heap.options` |
| 防火墙 | 9200 白名单 | **另加 9300**（节点间 transport）在两两之间放行 | §2.5 |
| 快照仓库 | 每台本地 `fs` 仓库 | 建议共享一个（NFS）或对象存储 | §7.2 |

```yaml
# 3 节点时 opensearch.yml 的差异片段（示意，两台老机器）
cluster.name: pm-os
node.name: os01
node.roles: [data, ingest]
discovery.seed_hosts: os01,os02,os-mgr
cluster.initial_master_nodes: os01,os02,os-mgr
# 删掉 discovery.type: single-node
```
```bash
# 改完逐台重启（★一次只重启一台，等它回到 green 再动下一台）
sudo systemctl restart opensearch
curl -sS -u admin:'<口令>' http://127.0.0.1:9200/_cluster/health?pretty    # 期望 number_of_nodes=3, green
```

> **单节点 → 3 节点不需要重建索引**：只在其中一台保留 `data/`，其余两台以**空数据目录**加入；
> 再把副本数从 0 改成 1（`PUT /pm-ai-chunks/_settings {"index":{"number_of_replicas":1}}`）。
> ⚠️ 已有数据时**不要**再写 `cluster.initial_master_nodes` 去"引导新集群"；这一步最容易出错，
> 届时务必按官方"从单节点迁移到多节点集群"的步骤做，并**先在 `.111` 上演练**。

## 10. 附录 B · 官方文档逐条核对（官方怎么写 → 我们原来怎么写 → 现在怎么写）

> 核对对象：官方 Tar 安装页、安全插件启停页、demo 配置页、安全设置页（链接见文首）。
> **"原来"指本文件的 Docker 版初稿与本仓库当时的资产。**

| # | 项 | 官方怎么说 | 我们原来怎么写 | 现在改成什么 |
| --- | --- | --- | --- | --- |
| 1 | **默认 REST 层是 HTTPS** | Tar 页 Step 3 的验证命令是 `curl -X GET https://localhost:9200 -u 'admin:<password>' --insecure`；demo 脚本"applies self-signed TLS certificates" | 写的是"内网 HTTP + basic auth"，**没有显式处理 TLS** | §3.5 给出**三选一**（HTTP+basic auth / 自签 TLS / 禁插件并说明不推荐），并在配置资产里**显式写下** `plugins.security.ssl.http.enabled: false` |
| 2 | **口令复杂度** | demo 配置页四条：8~100 字符；四类字符各至少一个；**zxcvbn 达 strong 或以上**；**不得与用户名 admin 相似** | 只写了">=8 位 + 四类字符齐全" | §3.2 按官方**四条**逐字写全，并说明"四类字符齐全也可能被拒" |
| 3 | **`DISABLE_INSTALL_DEMO_CONFIG=true` 的后果** | 镜像/安装脚本 README 的 Scenario 3：跳过 demo 脚本 ⇒ **没有证书与安全配置**，安全插件开着就会**启动失败** | Docker 编排里设了 `DISABLE_INSTALL_DEMO_CONFIG: "true"` 且同时启用安全插件 —— **这是错的，会起不来** | **删掉 Docker 路线**；tar 路线改为"**让 demo 脚本正常跑**（拿到证书与 admin 用户），用强口令保证口令安全"，并在 §3.2/§3.3 讲清顺序 |
| 4 | **`OPENSEARCH_INITIAL_ADMIN_PASSWORD` 的用法** | tar 路线：先 `export OPENSEARCH_INITIAL_ADMIN_PASSWORD=<强口令>`，再 `./opensearch-tar-install.sh`；2.12 起不设会失败 | 把它当成 Docker 的环境变量写在 compose 里 | 改为 §3.2 的 **`export` + 脚本**（tar 语汇），并说明它只在**首次**生效 |
| 5 | **安装脚本的实际作用** | `opensearch-tar-install.sh` 会"apply a generic configuration… defines some environment variables and also applies self-signed TLS certificates"，并把安全设置写进 `config/opensearch.yml` | 没提这个脚本 | §3.2 说明它的作用与"改了 `opensearch.yml`"，并强制**紧接着** §3.3 接入我们的配置（含先备份 `config/opensearch.yml.demo.bak`） |
| 6 | **transport 层证书不能漏** | demo 脚本写入的 `opensearch.yml` 含 transport 证书路径；安全插件默认要求 transport 加密（缺证书 ⇒ 集群起不来 / 报 `Transport client authentication no longer supported`） | 我们的配置模板**只有** `plugins.security.ssl.http.enabled`，把 transport 段丢了 | 配置资产补回 `plugins.security.ssl.transport.pemcert_filepath` / `pemkey_filepath` / `pemtrustedcas_filepath` + `allow_unsafe_democertificates` / `allow_default_init_securityindex`，并解释**为什么不能整份覆盖成"干净配置"** |
| 7 | **`vm.max_map_count` 与 swap** | Tar 页 Step 2：`sudo swapoff -a`；`vm.max_map_count=262144`（官方示例改 `/etc/sysctl.conf` 后 `sysctl -p`，再 `cat /proc/sys/vm/max_map_count` 校验） | 有（写了 `sysctl.d` 与重启校验） | 与官方一致；保留 `sysctl.d` 写法（更不容易和别人的行冲突）并补上 `cat /proc/sys/vm/max_map_count` 这条官方校验 |
| 8 | **systemd unit 示例** | 官方给了完整示例：`Type=forking`、`ExecStart=/opt/opensearch/bin/opensearch -d`、`User/Group=opensearch`、`StandardOutput=journal`、`LimitNOFILE=65535`、`LimitNPROC/LimitAS/LimitFSIZE`、`TimeoutStopSec=0`、`KillSignal=SIGTERM`、`KillMode=process`、`SendSIGKILL=no`、`SuccessExitStatus=143`、`TimeoutStartSec=75`；并提醒 **tar + systemd 非生产推荐**（要 systemd 就用 RPM） | 原来用 Docker，**没有 unit** | 资产 [`opensearch.service`](../deploy/opensearch/opensearch.service) **照官方示例改写**，只改三处：路径→`/home/opensearch/app`、保留 User/Group 语义、**补 `LimitMEMLOCK=infinity` + `Environment=MAX_MAP_COUNT=262144`**（官方 configuring-opensearch 要求），并保留官方的"非生产推荐"提醒 |
| 9 | **memlock 与 `MAX_MAP_COUNT`** | 官方 configuring-opensearch：systemd 托管且开 `bootstrap.memory_lock` 时，需要放开 memlock 并设 `MAX_MAP_COUNT=262144` | 原 compose 用 `ulimits.memlock: -1`（容器语汇） | 改为 unit 的 `LimitMEMLOCK=infinity` + `Environment=MAX_MAP_COUNT=262144`，并在 §2.2/§4.2 A4 给出验证命令 |
| 10 | **专用非 root 用户** | Tar 页：`sudo adduser --system --shell /bin/bash -U --no-create-home opensearch`；`sudo usermod -aG opensearch $USER`；`sudo chown -R opensearch /opt/opensearch/`；OpenSearch 不能以 root 运行 | 原方案靠容器内 uid 1000（宿主 `chown 1000:1000`） | §2.4/§3.4 全部改为**系统用户 `opensearch` + `chown -R opensearch:opensearch`**，并给"权限不对"的排障行 |
| 11 | **端口 9600** | 官方（Docker 与 tar）都提 `9200`（REST）与 `9600`（Performance Analyzer） | 原来只讨论 9200，9600 未表态 | §0.2/§2.5 明确：**9600 不放行**（本方案不用 Performance Analyzer） |
| 12 | **数据/日志/备份路径与属主** | 官方 tar 路线不规定安装路径（"The tarball installation does not define a specific installation path, users, roles, or permissions"），但要自己保证权限正确；官方示例把 `data` 放在安装目录下 | 原来绑定宿主目录 `1000:1000`（容器语汇） | §0.2/§2.4 定稿 `/home/opensearch/{app,config,data,logs,backup}`，**data/logs 放在安装目录之外**（升级不碰数据），属主 `opensearch:opensearch`、权限 750（文件 644 / keystore 600） |
| 13 | **升级与"复制 config"** | 官方 tar 升级思路是"换程序目录、带上你自己的 `config/`"（安全配置与证书都要自己接管） | 原来是 `docker load` 换 tag | §7.3 改为 stop → 解包新目录 → **复制 `config/opensearch.yml` + `jvm.options.d/` + demo 生成的证书与 `opensearch-security/`** → chown → start，旧目录留作回滚 |
| 14 | **`plugins.security.ssl.http.enabled` 的默认值** | 安全设置页：该设置 "Default is `false`"，但 demo 脚本会把它设成 **true**（所以 quickstart 用 HTTPS） | 原来只有"TLS 可选"一句，说不清默认到底是什么 | §3.5 明确：**字段默认 false，但 demo 配置会改成 true**，因此我们**必须显式写 false**（而不是"默认就是 HTTP"） |
| 15 | **禁用安全插件的代价** | 启停页：禁用后"exposes the configuration index… If the index contains sensitive information, make sure to protect it"；安全设置页：`plugins.security.disabled` 的警告是"can expose your configuration (including passwords) to the public"；且**禁用/启用需要整集群重启** | 原方案把"禁插件"只当作"简单"选项 | §3.5 方案 C 明确列为**不采用**，并原样引用官方的暴露警告与"需要重启"这一操作代价 |
| 16 | **下载 URL 与校验** | 官方 Tar 页给的是 `wget https://artifacts.opensearch.org/releases/bundle/opensearch/<版本>/opensearch-<版本>-linux-x64.tar.gz`（**页面没有给 `.sha512` 的说明**） | 原文没有校验步骤 | §1 用官方 URL 形式，并**实测确认**同目录下有 `.sha512`（165 字节文本，`sha512sum -c` 可直接吃），给出 Windows/Linux 两种校验命令 + "校验不通过绝不安装" |
| 17 | **架构** | 官方按 x64 / arm64 分别给下载命令 | 原方案假设这两台是 aarch64（银河麒麟） | §0.2 明确这两台是 **x86_64**，用 `linux-x64.tar.gz`；并提醒别和既有 aarch64 服务器的产物搞混 |
| 18 | **`jvm.options.d` 覆盖目录** | 官方 Tar 页 Step 4 是让你直接改 `config/jvm.options` 里的 `-Xms/-Xmx` | 原来把堆写在容器环境变量 `OPENSEARCH_JAVA_OPTS` 里 | §3.3 改为官方支持的 `config/jvm.options.d/heap.options`（**升级时 `jvm.options` 会被新版本覆盖，我们的文件不会**），并在资产里解释为什么是 8g |
| 19 | **快照仓库位置** | 官方快照文档要求仓库 location 在 `path.repo` 之下 | 原方案把 `path.repo` 与宿主目录直接对应（容器语汇） | §7.2 明确 `path.repo: /home/opensearch/backup` 与脚本 `REPO_LOCATION=/home/opensearch/backup/fs-repo` 的**父子关系**，并给出 `repository_exception` 的排障行 |
| 20 | **专用账号的建法（D 方案偏离官方）** | 官方 Tar 页给的是 `sudo adduser --system --shell /bin/bash -U --no-create-home opensearch`（**无家目录的系统账号**） | 上一版照官方写 | **D 方案要求部署根就是该账号的家目录**，所以改为 `sudo useradd -m -d /home/opensearch -s /bin/bash opensearch` + `sudo passwd -l opensearch`（**有家目录、锁登录**）。这是**有意偏离官方示例**，原因写在 §2.4 |
| 21 | **`/home` 挂载前提** | 官方 tar 页只要求 `vm.max_map_count`/swap，**不规定安装路径**（"does not define a specific installation path"） | 上一版把部署根放在 `lhim` 家目录下，并要求 `chmod 711 /home/lhim` | 部署根改为 `opensearch` 自己的家目录 ⇒ **删掉 `chmod 711` 那条**；新增 **§2.4.2 `/home` 挂载前置检查**（`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home`：确认本地盘/非 NFS/空间够，并给 `path.data` 退路；说明 **`nosuid`/`nodev` 无害**，只有 `noexec` 会挡路） |
| 22 | **Dashboards 版本兼容** | 官方 Dashboards tar 页是独立页，**没有明写"必须与 OpenSearch 同版本"**；但官方版本矩阵与下载页都要求两者版本一致 | 上一版根本没提 Dashboards | §12.1 把"**三个数字都要一样**（`2.19.1`）"写成硬约束并给出下载 + `.sha512` 校验 + 归档 tar 到 `.111` |
| 23 | **Dashboards 配置文件字段名** | 官方示例 `config/opensearch_dashboards.yml`：`server.host`（默认 `"localhost"`，"usually means remote machines will not be able to connect"）、`server.port`、`opensearch.hosts`、`opensearch.username/password`、`opensearch.requestHeadersWhitelist`（**已标 DEPRECATED**，新名 `opensearch.requestHeadersAllowlist`）、`opensearch.ssl.verificationMode`、`opensearch.memoryCircuitBreaker`；多租户键 `opensearch_security.multitenancy.enabled` 出现在安全插件文档与官方示例注释里 | 上一版没有 Dashboards 配置 | §12.2 逐项照官方字段名写进资产 `opensearch_dashboards.yml.example`：用**新名** `requestHeadersAllowlist`（并在注释里留旧名对照）、`server.host` 显式改内网 IP、`opensearch.hosts` 用 `http://`（与 §3.5 方案 A 绑定）、多租户项保留并注明"与 workspace 互斥" |
| 24 | **Dashboards 的 systemd** | 官方 Dashboards tar 页**只给三步**（解包 → 改 yml → `./bin/opensearch-dashboards`），**没有 systemd 示例** | 上一版没有 | §12.5 + 资产 `opensearch-dashboards.service`：**骨架照官方 OpenSearch tar 页那份 unit**，按 Node.js 运行方式改 3 处并写明原因（`Type=simple` 而非 `forking`、`KillMode=mixed` 以收掉 Node 子进程、`Environment=NODE_OPTIONS=--max-old-space-size=1024`） |
| 25 | **Dashboards 的 Node 堆默认值** | 官方 `config/node.options` 里给的示例是 **`--max-old-space-size=4096`**（默认 4GB 老生代）；官方示例 yml 提到 `opensearch.memoryCircuitBreaker` 的阈值基于该值 | 上一版没有 | §12.3 明确"默认 4GB、16GB 机器上必须封顶"，本方案在 unit 里压到 **1024MB**，并给出"官方支持两种设置方式（`config/node.options` 或 `NODE_OPTIONS`），我们选后者"的取舍 |
| 26 | **快照默认不含系统索引（脚本级修正）** | 官方快照/恢复文档说明：恢复快照时不能包含全局状态、且**不恢复 `.opendistro_security`**（除非 admin 证书签名）⇒ 系统索引默认不在快照里 | 上一版脚本只备 `pm-ai-chunks` ⇒ 恢复后 Dashboards 空白、安全配置回默认 | §12.7 + 脚本：默认索引列表改为 `pm-ai-chunks,.opensearch_dashboards*,.opendistro_security`，并加 **`expand_wildcards: all`**；给出三条验证命令（快照后看 `indices`、恢复后看 `_cat/indices`、浏览器里看仪表盘） |

## 11. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| docs-1.6 | 2026-09-23 | 首版（**已被下方"修订二"取代**）：为两台新服务器出 OpenSearch 部署与接入文档 + 部署资产，当时定稿**单节点 + Docker（`docker load`）**路线 |
| docs-1.6（修订二） | 2026-09-23 | **部署形态改为官方 `tar.gz` 解包 + systemd（不用 Docker）**（新机器没装 Docker）：删除全部 Docker 路线资产（compose / `.env.example` / `os.sh`），新增 `opensearch.yml.example` / `jvm.options.d/heap.options` / `opensearch.service` / `snapshot-backup.sh`；两处决策级修正：**架构 x86_64**、**按官方文档逐条对齐**（默认 REST 层是 HTTPS ⇒ 必须显式关 TLS；口令复杂度补 zxcvbn 与"不得与 admin 相似"；`DISABLE_INSTALL_DEMO_CONFIG=true` 会让节点起不来 ⇒ 改为让 demo 脚本正常跑；transport 证书不能漏；systemd unit 照官方示例并补 `LimitMEMLOCK`/`MAX_MAP_COUNT`；升级路径改为换程序目录 + 复制 `config/`）。逐条核对表见 **附录 B** |
| docs-1.6（修订三） | 2026-09-23 | **部署根定为 `/home/opensearch/`（D 方案，= 专用账号 `opensearch` 自己的家目录），内部结构规范成"程序/配置/数据/日志/备份/Dashboards"分离；新增 OpenSearch Dashboards（§12）**。① **目录**：`app/`（当前程序）、`app-<旧版本>/`（回滚点）、`config/`（我们维护的 `opensearch.yml` + `jvm.options.d/heap.options` + `snapshot-backup.sh`）、`data/`、`logs/`、`backup/fs-repo/`、`dashboards/app/`。② **建账号方式改掉**：不用官方那种"系统账号 + `--no-create-home`"，改为 `useradd -m -d /home/opensearch -s /bin/bash opensearch` + `passwd -l`（有家目录、锁死登录）。③ **删掉上一版的 `/home/lhim` 穿透权限（`chmod 711`）那一节**（D 方案下不再需要，留着会误导），换成 **§2.4.1 家目录可用性检查**（`sudo -u opensearch ls/touch` 自证）。④ **新增 §2.4.2 `/home` 挂载前置检查**（`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home` 确认本地盘、非 NFS/autofs、空间够；给出"只把 `path.data` 挪到 `/data/opensearch`"的既定退路；并说明 **`nosuid`/`nodev` 对本服务无害、不要为此改挂载参数**，真正会挡路的只有 `noexec`）。⑤ **升级流程**按新结构重写（解包到 `app-<版本>/` → 拷 `config/` 与证书/`opensearch-security/` → 停服 → 旧 `app/` 改名保留、新目录改名 `app/` → `chown -R` → 启动 → 验收；回滚即反操作）。⑥ **Dashboards（§12）**：版本必须与 OpenSearch 完全一致（`2.19.1`）、下载 + sha512 校验 + **归档 tar 到 `.111`**、解包到 `dashboards/app/`、`opensearch_dashboards.yml` 关键项逐条给依据（`server.host` 绑内网、`opensearch.hosts` 与 REST TLS 取舍绑定、只读账号优先、`requestHeadersAllowlist` 必须含 `authorization`、多租户与 workspace 互斥）、systemd unit（照官方 OpenSearch 那份示例改 3 处：`Type=simple`/`KillMode=mixed`/`NODE_OPTIONS` 堆上限）、内存预算（Node 约 0.6~1.2GB，标注估算）、5601 只放行运维网段、**冷备结论（不需要单独冷备）**，以及 **⚠️ 快照必须显式包含系统索引**（`snapshot-backup.sh` 默认列表已改为 `pm-ai-chunks,.opensearch_dashboards*,.opendistro_security`，并加 `expand_wildcards: all`）。⑦ 集群名 `pm-os` 与节点名 `os01` **保持不变** |

## 12. OpenSearch Dashboards（可视化，与 OpenSearch 同机 · `10.254.212.110:5601`）

> **官方依据**：[Installing OpenSearch Dashboards from a tarball](https://docs.opensearch.org/latest/install-and-configure/install-dashboards/tar/)（**该页只给三步：解包 → 改 `config/opensearch_dashboards.yml` → `./bin/opensearch-dashboards`，没有 systemd 示例**）、
> [Configuring OpenSearch Dashboards](https://docs.opensearch.org/latest/install-and-configure/configuring-dashboards/)（配置文件位置口径）、
> 官方示例配置 [`config/opensearch_dashboards.yml`](https://github.com/opensearch-project/OpenSearch-Dashboards/blob/main/config/opensearch_dashboards.yml)（**字段名与默认值的权威来源**）。
> 资产：[`opensearch_dashboards.yml.example`](../deploy/opensearch/opensearch_dashboards.yml.example)、[`opensearch-dashboards.service`](../deploy/opensearch/opensearch-dashboards.service)。

### 12.1 版本硬约束（**必须先看这条**）

| 项 | 值/规则 |
| --- | --- |
| **版本必须与 OpenSearch 完全一致** | 本方案 pin **`2.19.1`** ⇒ Dashboards 用 **`opensearch-dashboards-2.19.1-linux-x64.tar.gz`**（**三个数字都要一样**，不能 2.19.0 配 2.19.1） |
| 下载地址形式 | `https://artifacts.opensearch.org/releases/bundle/opensearch-dashboards/<版本>/opensearch-dashboards-<版本>-linux-x64.tar.gz` |
| 校验和 | 同目录的 `.sha512`（与 OpenSearch 同一套校验命令；**校验不通过绝不安装**） |
| 架构 | **x86_64**（`linux-x64`）；与 OpenSearch 两台机器一致 |
| 归档 | 安装包**同时归档一份到 `.111`**（`/home/opensearch/backup/dist/`）——离线环境里"能重建"的前提是"包还在" |

```bash
# ① 在【有外网】的机器上下载（与 §1 同一套路）
VER=2.19.1
URL=https://artifacts.opensearch.org/releases/bundle/opensearch-dashboards
curl -fL --retry 3 -O "$URL/$VER/opensearch-dashboards-$VER-linux-x64.tar.gz"
curl -fL --retry 3 -O "$URL/$VER/opensearch-dashboards-$VER-linux-x64.tar.gz.sha512"
sha512sum -c "opensearch-dashboards-$VER-linux-x64.tar.gz.sha512"
# 期望：opensearch-dashboards-2.19.1-linux-x64.tar.gz: OK

# ② 传到 OpenSearch 那台
scp opensearch-dashboards-2.19.1-linux-x64.tar.gz* lhim@10.254.212.110:/tmp/
# ③ 归档一份到冷备/演练机（★离线时能重建，见 §12.6 的冷备结论）
ssh lhim@10.254.212.111 'mkdir -p /home/opensearch/backup/dist'
scp opensearch-dashboards-2.19.1-linux-x64.tar.gz* lhim@10.254.212.111:/home/opensearch/backup/dist/
```

### 12.2 安装（解包 + 配置 + 只读账号）

```bash
# ① 解包到 dashboards/app（与 OpenSearch 同属主 opensearch，非 root 运行）
sudo -u opensearch mkdir -p /home/opensearch/dashboards
cd /home/opensearch/dashboards
tar -zxf /tmp/opensearch-dashboards-2.19.1-linux-x64.tar.gz
mv opensearch-dashboards-2.19.1 app
ls /home/opensearch/dashboards/app        # 期望看到 bin/ config/ node/ plugins/ src/ …
```

**配置文件（官方规定的位置就是解包目录下的 `config/opensearch_dashboards.yml`）**

```bash
# ② 放行我们的配置（资产：deploy/opensearch/opensearch_dashboards.yml.example）
sudo -u opensearch cp /tmp/opensearch_dashboards.yml.example \
                       /home/opensearch/dashboards/app/config/opensearch_dashboards.yml
sudo -u opensearch vi /home/opensearch/dashboards/app/config/opensearch_dashboards.yml
#   ★必改两处：server.host 改成 10.254.212.110；opensearch.password 填只读账号口令
sudo chown opensearch:opensearch /home/opensearch/dashboards/app/config/opensearch_dashboards.yml
sudo chmod 644 /home/opensearch/dashboards/app/config/opensearch_dashboards.yml
```

| 配置项 | 本方案取值 | 为什么 |
| --- | --- | --- |
| `server.host` | **`"10.254.212.110"`** | 官方默认是 `"localhost"`（官方注释原话："usually means remote machines will not be able to connect"）⇒ 必须显式改成内网 IP。**不要写 `0.0.0.0`**，暴露面交给防火墙（§12.4） |
| `server.port` | `5601` | 官方默认端口 |
| `server.name` | `"pm-os-dashboards"` | 显示用 |
| `opensearch.hosts` | **`["http://127.0.0.1:9200"]`** | ★**与"关掉 REST 层 TLS"的取舍绑定**：正因为主文档 §3.5 方案 A 里写了 `plugins.security.ssl.http.enabled: false`，9200 才是 HTTP，这里才能用 `http://`。若你改走自签 TLS 方案 B，这里必须写 `https://` 并配 `opensearch.ssl.certificateAuthorities` 与 `opensearch.ssl.verificationMode`（否则 Dashboards 连不上） |
| `opensearch.username` / `opensearch.password` | **专用只读账号**（建议） | 官方注释：这组凭据是给 Dashboards **自己**做维护动作（建/写它自己的系统索引）用的；**用户仍各自登录**。用 `admin` 最省事但权限过大 |
| `opensearch_security.multitenancy.enabled` | `true` | 多租户隔离 saved objects。⚠️ 官方示例 yml 明确写了：开启 **workspace** 特性时必须把它设为 `false`（二者互斥）——本方案用传统多租户 |
| `opensearch.requestHeadersAllowlist` | `["securitytenant", "Authorization", "authorization"]` | ★必须允许 `authorization`，否则登录态传不到 OpenSearch（现象：登录后立刻又跳回登录页）。官方示例里 `opensearch.requestHeadersWhitelist` 已标 **DEPRECATED**，新名是 `requestHeadersAllowlist` |
| `opensearch.requestTimeout` / `opensearch.shardTimeout` | `30000` | 官方示例默认值；查询慢时可调大 |
| `logging.dest` | `stdout` | 配合 systemd 进 journald |
| `i18n.locale` | `"zh-CN"` | 官方示例支持 `en` / `zh-CN` |

**只读账号怎么建（不用 `admin`）**：用安全插件的内部用户 + 角色（官方《Defining users and roles》与
《YAML files》两页的口径）——最小可用集合是"能读业务索引 + 能读写 `.opensearch_dashboards*`"：

```bash
# ① 生成口令哈希（官方 Tar 页给的 hash.sh 用法；需要指向自带 JDK）
cd /home/opensearch/app/plugins/opensearch-security/tools
OPENSEARCH_JAVA_HOME=/home/opensearch/app/jdk ./hash.sh -p '<该账号的强口令>'
#   记下输出的 bcrypt 哈希

# ② 编辑内部用户与角色（先备份！）
cd /home/opensearch/app/config/opensearch-security
sudo -u opensearch cp internal_users.yml internal_users.yml.bak
sudo -u opensearch cp roles.yml          roles.yml.bak
sudo -u opensearch cp roles_mapping.yml  roles_mapping.yml.bak
#   在 internal_users.yml 加：
#     dashboards_ro:
#       hash: "<上一步的哈希>"
#       reserved: false
#       backend_roles: []
#       description: "OpenSearch Dashboards 服务账号（只读）"
#   在 roles.yml 加一个角色（cluster 权限只要能读写自己的系统索引；index 权限给读业务索引）：
#     dashboards_ro_role:
#       cluster_permissions: ["cluster_monitor"]
#       index_permissions:
#         - index_patterns: [".opensearch_dashboards*"]
#           allowed_actions: ["indices_all"]
#         - index_patterns: ["pm-ai-chunks"]
#           allowed_actions: ["read", "indices:data/read/search"]
#   在 roles_mapping.yml 里把用户映射到角色：
#     dashboards_ro_role:
#       users: ["dashboards_ro"]

# ③ 让安全插件重新加载配置（官方要求 OpenSearch 正在运行）
cd /home/opensearch/app/plugins/opensearch-security/tools
OPENSEARCH_JAVA_HOME=/home/opensearch/app/jdk ./securityadmin.sh \
  -cd /home/opensearch/app/config/opensearch-security/ \
  -cacert /home/opensearch/app/config/root-ca.pem \
  -cert  /home/opensearch/app/config/admin.pem \
  -key   /home/opensearch/app/config/admin-key.pem -icl -nhnv
#   期望末尾：Done with success
```
> ⚠️ **`securityadmin.sh` 的参数与证书路径依你的实际部署而定**：证书名以 `app/config/*.pem` 实际存在的为准；
> 若你走的是"REST 关 TLS"方案 A，**transport 层证书仍然存在**（§3.5），所以上面这套照旧可用。
> ⚠️ 改完 `internal_users.yml` 等文件后**必须跑一次 `securityadmin.sh`**，否则改动只在磁盘上、不生效。

### 12.3 内存预算（Dashboards 是 **Node.js**，不是 JVM）

| 进程 | 内存占用 | 依据 |
| --- | --- | --- |
| OpenSearch | **JVM 堆 8g**（`-Xms8g -Xmx8g`）+ 页缓存 ~8g | §3.3；16GB 物理内存的 50/50 分法 |
| OpenSearch 非堆 | 直接内存/线程栈/元空间，**约 0.5~1g**（估算（假设：常规索引与并发，未实测）） | 工程经验值，标注为估算 |
| **Dashboards（Node）** | **约 0.6~1.2 GB**（估算（假设：`--max-old-space-size=1024` 上限内、单实例、日常多人看板）） | Node 服务典型占用；本方案用 unit 里 `NODE_OPTIONS=--max-old-space-size=1024` 显式封顶 |
| 合计 | **约 17~18 GB 的名义需求 vs 16 GB 物理内存** | 见下方"这意味着什么" |

> **这意味着什么（必须说清）**：本机 16GB、OpenSearch 堆 8g、页缓存需要 8g —— **页缓存与 Dashboards 的 Node 进程会互相挤**。
> 而 Dashboards **默认并不小**：官方 `config/node.options` 里给的默认示例就是 **`--max-old-space-size=4096`（4GB）**，
> 也就是说"什么都不改"时它会按 4GB 老生代去用。所以在 16GB 机器上**必须给它封顶**——
> 本方案在 unit 里用 `NODE_OPTIONS=--max-old-space-size=1024` 压到 **1GB**（官方支持这两种设置方式：
> `config/node.options` 文件或 `NODE_OPTIONS` 环境变量；我们在 unit 里设，便于一处集中管理）。
> 若两台机器后续还要跑更多东西（Dashboards + 将来可能的第二个实例），**建议把物理内存加到 32GB**，
> 或把 Dashboards 挪到 `.111`（它只是前端，连远端 9200 即可 —— 但要**放行 5601 与跨机 9200**）。
> 这是**估算**，上生产后请用 `systemctl status opensearch-dashboards`（看 RSS）与 `_cat/nodes?v`（看堆%）各观察一周再定。

**如何调 Node 堆上限**：改 unit 里那一行然后重启（唯一一处，别在别处再设一遍）：

```bash
sudo systemctl edit --full opensearch-dashboards     # 改 Environment=NODE_OPTIONS=--max-old-space-size=<MB>
sudo systemctl daemon-reload && sudo systemctl restart opensearch-dashboards
systemctl show opensearch-dashboards -p Environment  # 复核生效值
```

### 12.4 端口与防火墙（**5601 只给运维网段**）

| 端口 | 服务 | 放行来源 | 与其它端口的差异 |
| --- | --- | --- | --- |
| `5601/tcp` | **Dashboards（浏览器访问）** | **只放行运维网段 / 跳板机**（**绝不对全网开放**） | 它是**给人用的 UI**，暴露面比 9200 大；宁可只让少数人访问 |
| `9200/tcp` | OpenSearch REST | 只放行两台应用服务器（AI 所在机器） | 机器对机器，**不给人直接用** |
| `9600/tcp` | Performance Analyzer | **不放行** | 本方案不用 |

```bash
# firewalld 版（把 5601 只放进运维来源；9200 的规则见 §2.5）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<运维网段/跳板机IP>" port port="5601" protocol="tcp" accept'
sudo firewall-cmd --reload
sudo firewall-cmd --list-rich-rules

# 自检：从【不在白名单】的机器验证它确实不通（期望超时/拒绝，绝不能是 200）
curl -m 3 -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:5601/ ; echo "exit=$?"
```

> **绑定 + 防火墙两道一起做**：`server.host` 绑内网 IP 是"少听一张网卡"，防火墙是"少放一批来源"。
> 只做其中一个都不够（同网段里任何机器仍然能直连）。

### 12.5 systemd（装 unit 并验收）

```bash
# ① 装 unit（资产：deploy/opensearch/opensearch-dashboards.service）
sudo cp /tmp/opensearch-dashboards.service /etc/systemd/system/opensearch-dashboards.service
sudo chmod 644 /etc/systemd/system/opensearch-dashboards.service
sudo systemctl daemon-reload
sudo systemctl enable --now opensearch-dashboards

# ② 状态与日志
systemctl status opensearch-dashboards --no-pager      # 期望 Active: active (running)
journalctl -u opensearch-dashboards -f                 # Ctrl-C 退出

# ③ 验收：浏览器打开（用【有权限的普通用户】登录，不要用 admin）
#    http://10.254.212.110:5601
```
```bash
# ④ 命令行侧的最小验证（不需要浏览器）
curl -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:5601/api/status
# 期望：200（若返回 503/连接被拒，先看 journalctl）
systemctl show opensearch-dashboards -p User -p Environment -p LimitNOFILE -p Restart
# 期望：User=opensearch；Environment 里有 --max-old-space-size=1024；LimitNOFILE=65536；Restart=on-failure

# ⑤ 重启后自动拉起（与 §4.3 同一条底线）
sudo reboot
# —— 重新登录后 ——
systemctl is-enabled opensearch-dashboards ; systemctl is-active opensearch-dashboards
```

> **unit 里三处与 Dashboards 特性相关的调整（都在资产文件里写了原因）**：
> ① `Type=simple`（官方 OpenSearch 那份 systemd 示例是 `Type=forking`，对应 `-d`；`bin/opensearch-dashboards` 是前台进程）；
> ② `KillMode=mixed`（包装脚本 + Node 子进程要一起收掉，避免孤儿进程占着 5601）；
> ③ `Environment=NODE_OPTIONS=--max-old-space-size=1024`（Node 堆封顶，§12.3）。
> **官方没有给 Dashboards 的 systemd 示例**（tar 页只有三步），这三处是我们按它的运行方式补的，其余指令照官方 OpenSearch 那份 unit。

### 12.6 冷备结论：**Dashboards 不需要冷备**（回答疑问）

| 问题 | 结论 |
| --- | --- |
| Dashboards 需要单独做冷备吗？ | **不需要**。它的"状态"（saved objects：仪表盘/可视化/索引模式、租户、动态配置）**存在 OpenSearch 的 `.opensearch_dashboards*` 索引里**，不在 Dashboards 本地 |
| 那它的"备份"是什么？ | ① **OpenSearch 的快照**（且**必须含 `.opensearch_dashboards*`**，见 §12.7）；② 一份 `opensearch_dashboards.yml`（就在本仓库资产里）；③ **`.111` 上归档的 tar 包**（程序本体，重装用） |
| 灾难恢复怎么做？ | 在 `.111`（或重装后的机器）上：装同版本 Dashboards → 放回 `opensearch_dashboards.yml` → 恢复 OpenSearch 快照（含系统索引）→ 启动。**四步，全是已有材料** |
| 要备份 `dashboards/app/data/` 之类目录吗？ | **不用**：那是运行期临时文件；真正要留的只有 `config/opensearch_dashboards.yml` |

### 12.7 ⚠️ **快照必须显式包含系统索引**（否则恢复出来是空的）

> **这是本节最容易漏、后果最隐蔽的一条。**
> OpenSearch 的 `_snapshot` **默认不包含系统索引**。如果快照只写业务索引（`pm-ai-chunks`），那么恢复到 `.111` 之后：
> - **Dashboards 打开是空的**（`.opensearch_dashboards*` 没恢复 ⇒ 仪表盘、可视化、索引模式全没了）；
> - **安全配置回默认**（`.opendistro_security` 没恢复 ⇒ 内部用户/角色/角色映射全变回 demo 默认值）。

因此 [`snapshot-backup.sh`](../deploy/opensearch/snapshot-backup.sh) 的默认索引列表**已经显式包含系统索引**：

```text
pm-ai-chunks,.opensearch_dashboards*,.opendistro_security
```

它由脚本里的 `INDEX` 配置项控制（也可用环境变量 `INDEX=` 覆盖），
并且请求体里带了 **`"expand_wildcards": "all"`** —— 否则 `*` 通配符展开不到系统索引，
会出现"快照成功但内容不全"这种最难查的假成功。

```bash
# ① 看脚本当前要快照哪些索引（默认就是上面那串）
grep -n '^DEF_INDEX=' /home/opensearch/config/snapshot-backup.sh

# ② 手动打一份，确认系统索引真的进了快照（★关键验证）
bash /home/opensearch/config/snapshot-backup.sh snapshot
curl -sS -u admin:'<强口令>' \
  'http://127.0.0.1:9200/_snapshot/os-backup/nightly-'$(date +%F)'?pretty' | grep -E '"indices"|\.opensearch_dashboards|\.opendistro_security' | head -20
# 期望：indices 列表里能看到 .opensearch_dashboards* 与 .opendistro_security
#   只有 pm-ai-chunks ⇒ 说明索引列表被改过或没带 expand_wildcards，回上面检查
```
```bash
# ③ 恢复演练时的验证（在 .111 上；步骤见 §6.4 七步）
curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/_cat/indices?v' | grep -E 'opensearch_dashboards|opendistro_security'
# 期望：能看到 .opensearch_dashboards_* 与（若自定义过） .opendistro_security
# ④ 最终判据：浏览器打开恢复后的 Dashboards，**仪表盘/索引模式还在** ⇒ 系统索引确实恢复成功
```

> **为什么不用 `include_global_state: true` 顺带带上一切**：全局状态包含集群设置、模板、以及
> **`.opendistro_security` 的权限校验逻辑对象**，跨集群恢复时容易互相干扰（官方也说明"恢复快照时
> 不能包含全局状态，且不恢复 `.opendistro_security`"，除非用 admin 证书签名）。
> 本方案的选择是**显式列出要的索引**——可控、可验证、恢复后不会把别处的集群设置带乱。

