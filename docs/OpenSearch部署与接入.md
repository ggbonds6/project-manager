# OpenSearch 部署与接入（两台新服务器 10.254.212.110 / 10.254.212.111）

> **文档性质：操作手册（SOP），风格与 [`部署与发布全流程手册.md`](部署与发布全流程手册.md) §0 一致** ——
> §0~§7 是**可逐条照做**的定稿步骤（编号步骤 + 可直接复制的命令 + 预期输出）；要"为什么"看每节末尾的说明与 §9 附录。
> 配套资产（与本文件逐字一致）：[`deploy/opensearch/docker-compose.yml`](../deploy/opensearch/docker-compose.yml)、
> [`deploy/opensearch/.env.example`](../deploy/opensearch/.env.example)、[`deploy/opensearch/os.sh`](../deploy/opensearch/os.sh)。
>
> **关联**：[`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md)（"换 OpenSearch 的三条量化触发点"与本轮真机读数）、
> [`部署与发布全流程手册.md`](部署与发布全流程手册.md) §8（AI 能力服务 ai-backend 的构建与发布）、
> [`政府信息化项目管理系统-设计方案.md`](政府信息化项目管理系统-设计方案.md)、[`deploy/README.md`](../deploy/README.md)。
>
> **本轮只写文档与部署资产**：`scripts/**`、`deploy/**`、`ai-backend/**`、`frontend/**`、`backend/**` 的应用代码**一行未改**。

---

## 0. 结论与前提（先看这一节）

### 0.1 为什么上 OpenSearch（三条触发点，任一成立即必须换）

| 序号 | 触发点 | 现状依据 | 阈值 |
| --- | --- | --- | --- |
| ① | **延迟**：`local` 给**每一次提问**加的固定开销 | 每次检索把范围内**全部切片**的向量 JSON 读出来解析成 `float[]` 再逐条算余弦 | 切片 **≥ 4,000**（≈130 份）时 **+1.0s**；≥15,000 时 **+3.8s**（相对实测 10s 基线 **+38%**） |
| ② | **内存/并发**：常驻 = `16KB × 切片数 × 并发问数` | AI 容器**当前没有 `-Xmx`**（未确认项） | 4 GB 堆、留 50% 余量 ⇒ **4 并发时 ≈3 万切片触顶**；8 并发 ⇒ **≈1.5 万切片** |
| ③ | **架构（最硬的一条）**：`local` 是**进程内状态** | AI 服务**必须单实例**；且切片**没有 `project_id`/密级等元数据**，"先过滤再检索"今天**做不到**，而"先过滤"正是权限合规的前提 | **需要第 2 个 AI 实例时**（要做无中断检索 / 水平扩） |

> 数字出处：[`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md) §3.4 表与"量化触发点"表（本轮真机读数：
> 1 个 4096 维切片向量 JSON = **38,333 字节**，即 **9.36 字节/维**）。该文档的结论是"建议把 OpenSearch 集群部署从 P2 **上提为 P1**"。
>
> 一句话：**规模不是"余弦算不动"的理由（15,000 切片余弦仅 ≈61ms），但"延迟 / 内存 / HA + 元数据过滤"三条已经把切换点推到 ~4,000 切片。**

### 0.2 部署形态结论：**单节点（推荐默认）**

| 项 | 本方案定稿 |
| --- | --- |
| 节点数 | **1 个**：`os01` = **10.254.212.110** |
| 第二台 `10.254.212.111` | **冷备 + 演练机**（跑同一套编排，**默认停着**；存每晚快照副本，见 §6.4） |
| 索引 | `pm-ai-chunks`，**主分片 1 + 副本 0**（见 §5.1） |
| 端口 | `9200` **只放行两台 AI/应用服务器**；`9300` 只放行集群内节点；**绝不对全网开放** |
| 安全 | 内网 **HTTP + basic auth（强口令）**；TLS 为可选（§3.5） |
| 数据路径 | `/home/lhim/os/data`（宿主）；容器内 `/usr/share/opensearch/data` |
| 快照仓库 | `/home/lhim/os/backup`（宿主）；容器内 `/usr/share/opensearch/backup` |

### 0.3 为什么不推荐 2 节点（quorum 明账）

> **这一节是给运维/评审看的"明账"，不是免责声明。** 结论：**2 节点相比单节点几乎换不到可用性，却多一倍运维复杂度。**

- OpenSearch 用 **majority quorum** 选 master：集群内可参与选举的节点数为 `N` 时，需要 **`⌊N/2⌋+1`** 票。
  - **N=1**：1 票即可（单节点 `discovery.type: single-node` 直接选自己）；
  - **N=2**：**quorum = 2 ⇒ 需要 2 票** ⇒ **挂掉任意一台就失去 master**；
  - **N=3**：quorum = 2 ⇒ 挂掉一台仍能选举，这才是真 HA。
- **N=2 挂一台的实际后果**：副本 = 1 时**数据不丢**（另一台上有一份完整副本），但**集群不可写、也不可读**（没有 master 就没有集群状态：
  分片分配、索引元数据、写入全部停摆）——**服务不可用，直到它回来**。这等于"用两倍的机器拿到单节点的可用性"。
- **N=2 还多出来的复杂度**：`discovery.seed_hosts` + `cluster.initial_master_nodes` 必须两机一致且**只能写一次**（写错要清 `data/` 重来）、
  两机的 `node.name` / `container_name` 不能撞、滚动重启**必须一台一台来**（同时重启 = 双双失去 quorum）、升级窗口翻倍。
- **真正 HA 的升级路径**（附录 A）：加**第 3 台**（可用轻量机做 **dedicated cluster-manager**，不存数据）→
  届时只需改**两个参数**：`discovery.seed_hosts` 变 3 个地址、`cluster.initial_master_nodes` 变 3 个节点名；
  数据节点 `node.roles` 收敛为 `[data, ingest]`，第 3 台为 `[cluster_manager]`。

### 0.4 单节点的代价（必须接受的明账）

| 风险 | 后果 | 本方案的补偿措施 |
| --- | --- | --- |
| **单点故障** | OpenSearch 挂 → kNN 检索不可用（**AI 问答的检索环节受影响**） | ① **快照/备份从此是强制项**（§7.2，不是可选）；② **必须定清楚 AI 侧怎么降级**（§6.3：**明确报错或降质提示，不许静默返回无关结果**）；③ `restart: unless-stopped` + 开机自启 |
| 无副本 | 数据盘损坏即丢索引 | 每晚快照 + `rsync` 到 `.111`（§6.4），**RTO 分钟级**（演练步骤见 §6.4） |
| 无滚动升级 | 升级要停机 | 停机窗口内升级（分钟级，详见 §7.4）；索引可重建，不阻塞业务写入 |

> **重要边界**：OpenSearch 里存的是**可重建物**（切片文本 + 向量 + 索引），与既有运维口径一致——
> **业务数据仍在主系统崖山库**（`backend/src/main/resources/db/migration-yashan`，V1~V14，16 张表）。
> 索引即使全丢，也能从主系统附件重新解析重建（代价是时间，见 §6.2）。

### 0.5 两台新机器与既有服务器的关系（别搞混）

| 机器 | 角色 | 说明 |
| --- | --- | --- |
| `10.254.212.110` | **OpenSearch 单节点（推荐）** | 本文 §1~§7 的主体 |
| `10.254.212.111` | **冷备 + 演练机** | 同一套编排，默认停着；存快照副本（§6.4） |
| 既有 `pdmsappgh` / `ai-kingbase-gh` | 主系统 / AI 能力服务 | **不在这两台新机器上**；本文只改它们的 `.env`（§6.1） |
| `10.254.208.35:8090` | 平台网关（对话/OCR/向量化/重排） | 与本文无关，仅在重建索引时要它可用 |

> ⚠️ 上面 `.110`/`.111` 的角色、以及"AI 服务跑在哪台上"是**本轮按任务给定信息写定的**，实际拓扑需你确认（§8 第 1、2 条）。

---

## 1. 出镜像（开发机，Windows + Docker Desktop，**有外网**）

> **服务器无外网 ⇒ 只 `docker load`，绝不 `--build` / `docker pull`。**
> OpenSearch 官方镜像是**多架构镜像（含 `linux/arm64`）**，所以本机**不需要** `tonistiigi/binfmt` 模拟，也不需要 buildx——一条 `docker pull --platform` 即可。
> 与主系统镜像包的差别：主系统是 `buildx build` 出来的，本包是**上游镜像直接 save**。

### 第 1 步 · 确认镜像仓库里有这个 tag、且**含 arm64**（必须做，别跳）

```powershell
# 开发机（Windows PowerShell / pwsh 均可）
docker manifest inspect opensearchproject/opensearch:2.19.1
```

| 预期 | 处理 |
| --- | --- |
| 输出 JSON 里 `manifests[].platform.architecture` 同时含 **`amd64`** 与 **`arm64`** | 通过，进第 2 步 |
| 报 `no such manifest` / `manifest unknown` | 该 tag 不存在 → 换一个 2.x 稳定 tag 重试；**以镜像仓库实际可用为准** |
| 只看到 `amd64` | **不要用这个 tag**：换 tag，或改用 `--platform` 拉取后确认（见第 2 步注） |
| 报网络/超时错误 | 开发机没连上 registry（配镜像加速或换网络），**不要**把半截结果当成"没 arm64" |

> **镜像名的两种取法（先确认架构再决定拉哪个）**：
> - 服务器是 **aarch64（银河麒麟，最可能）** → 用 `--platform linux/arm64`；
> - 服务器是 **x86_64** → 用 `--platform linux/amd64`。
> 第 2 步第 0 条就是让你**先在服务器上 `uname -m` 确认**，不要凭猜测拉。
>
> 本文统一用 **`2.19.1`** 作为示例 tag（固定版本，不用 `latest`：离线环境里"这次部署的是哪个版本"必须可追溯）。
> **`2.19.1` 只是示例，不是"已确认可用"**：本条未能联网核实该 tag 的 arm64 清单（见 §8 第 3 条），**以第 1 步的实际输出为准**。

### 第 2 步 · 在服务器上确认架构（两台都要，先做再拉）

```bash
uname -m
# 期望：aarch64（银河麒麟）或 x86_64
```

### 第 3 步 · 拉取并导出（开发机，仓库根）

```powershell
# 开发机（仓库根执行）；OS_VER 换成第 1 步确认过的 tag
$OS_VER   = '2.19.1'
$Platform = 'linux/arm64'          # 服务器 uname -m = aarch64 用这个；x86_64 改 'linux/amd64'
$Arch     = 'arm64'                # 与上面一致：arm64 / amd64

docker pull --platform $Platform "opensearchproject/opensearch:$OS_VER"

# 校验"拉到的确实是目标架构"（不能只看 pull 成功）
docker image inspect "opensearchproject/opensearch:$OS_VER" --format '{{.Architecture}} {{.Os}}'
# 期望：arm64 linux（或 amd64 linux）

# 导出成 tar（产物落在仓库根的 dist\opensearch\，与 dist\ 同级的 gitignore 约定一致）
New-Item -ItemType Directory -Path dist\opensearch -Force | Out-Null
$tar = "dist\opensearch\opensearch-$OS_VER-linux-$Arch.tar"
docker save -o $tar "opensearchproject/opensearch:$OS_VER"

# 体积与校验和（Windows 原生；服务器侧对应 sha256sum）
Get-Item $tar | ForEach-Object { "{0:N1} MB  {1}" -f ($_.Length / 1MB), $_.Name }
Get-FileHash $tar -Algorithm SHA256 | Format-List Algorithm, Hash, Path
```

| 项 | 期望（**估算，假设：上游镜像约 1.1GB**，未实测） |
| --- | --- |
| tar 体积 | **1.0~1.3 GB**（未压缩；比主系统的 ~150MB 大一个量级，**scp 前先想好带宽**） |
| 校验和 | `Get-FileHash` 打印的 64 位 SHA256；**记下来**，第 5 步服务器侧要比对 |

> **压缩（可选）**：Windows 无 `gzip.exe`，用 .NET GZipStream（与手册 §2.3 同一手法）：
> ```powershell
> $gz = "$tar.gz"
> $in = [System.IO.File]::OpenRead((Resolve-Path $tar))
> try { $out = [System.IO.File]::Create($gz)
>   try { $z = [System.IO.Compression.GZipStream]::new($out, [System.IO.Compression.CompressionMode]::Compress)
>     try { $in.CopyTo($z) } finally { $z.Dispose() } } finally { $out.Dispose() } } finally { $in.Dispose() }
> Get-Item $gz | ForEach-Object { "{0:N1} MB  {1}" -f ($_.Length / 1MB), $_.Name }
> ```
> 服务器侧解压：`gunzip -c opensearch-2.19.1-linux-arm64.tar.gz > opensearch-2.19.1-linux-arm64.tar`（或直接 `docker load -i xxx.tar.gz`，`docker load` 认 gzip）。

### 第 4 步 · 传到两台机器（开发机）

```bash
# 开发机在【仓库根】执行（dist 的上一层）；用户与既有服务器一致：lhim
scp dist/opensearch/opensearch-2.19.1-linux-arm64.tar  lhim@10.254.212.110:/home/lhim/os/
scp dist/opensearch/opensearch-2.19.1-linux-arm64.tar  lhim@10.254.212.111:/home/lhim/os/
```

### 第 5 步 · 服务器侧校验 + 装载（两台都做；装载由 §4 的 `os.sh load` 完成）

```bash
# 服务器执行（两台）
sha256sum /home/lhim/os/opensearch-2.19.1-linux-arm64.tar
# 期望：与开发机第 3 步的 SHA256 逐位一致；不一致 = 传输损坏，重传（不要"先 load 看看"）
```

> **为什么校验和是硬要求**：`docker load` 对**截断的 tar** 有时会报错、有时会装进一个损坏的镜像层，
> 后者起容器才炸，排查成本高得多。先比校验和，比出问题就重传。

**本节说明（要"为什么"再看）**

- **不需要 `tonistiigi/binfmt`**：那是为"在 x86 上 build arm64 镜像"准备的（手册 §1.1 ②）；直接 `pull --platform` 上游多架构镜像不需要模拟。
- **为什么固定版本**：离线环境没有"自动升级"，`latest` 会让两台机器在某次重拉后悄悄变成不同版本。`OS_IMAGE_TAG`（§3.2）就是版本开关。
- **`docker save` 出来的 tar 与 `docker load` 的关系**：`load` 只恢复镜像与 tag，**不含**数据卷、不含 `.env`，所以服务器侧的数据与配置永远是"本机资产"。

---

## 2. 服务器准备（**两台都要做一遍**）

> 登录：`ssh lhim@10.254.212.110` / `ssh lhim@10.254.212.111`。
> 下面**每一段都可独立复制执行**；执行完请对照"预期"确认，不要一口气全贴。

### 2.1 内核参数 `vm.max_map_count`（**最关键的一步，漏了必失败**）

OpenSearch 底层的 Lucene 用 `mmap` 打开索引文件，默认 `65530` 不够（会报 `max virtual memory areas vm.max_map_count [65530] is too low`）。

```bash
# ① 立即生效
sudo sysctl -w vm.max_map_count=262144

# ② 持久化（重启后仍生效）——写成独立文件，别去改 /etc/sysctl.conf 里别人的行
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-opensearch.conf

# ③ 立即读回校验
sysctl vm.max_map_count
# 期望：vm.max_map_count = 262144
```

```bash
# ④ 重启后校验（★必须做一次：不重启不算验证过持久化）
sudo reboot
# —— 重新登录后 ——
sysctl vm.max_map_count      # 期望：vm.max_map_count = 262144
```

### 2.2 文件描述符 `ulimit -n`

```bash
# ① 当前 shell 的值
ulimit -n
# 期望：≥ 65536（官方镜像内已设好 65536；这里管的是【宿主 shell】与手工排查场景）

# ② 持久化到 lhim 用户（重登生效）
sudo tee /etc/security/limits.d/99-opensearch.conf >/dev/null <<'EOF'
lhim  soft  nofile  65536
lhim  hard  nofile  65536
EOF

# ③ 重新登录后校验
ulimit -n                    # 期望：65536
```

> 容器内的 `ulimits` 由 §3 的 compose 显式设置（`nofile: 65536`），**与宿主的 limits 是两件事**，但两边都要对。

### 2.3 关闭 swap / 锁定内存（内存锁定由 `bootstrap.memory_lock` 完成）

```bash
# ① 看现状
free -h                      # 关注 Swap 行
swapon --show                # 有输出 = 正在用 swap
```

| 你的选择 | 命令 | 说明 |
| --- | --- | --- |
| **二选一：彻底关 swap（推荐）** | `sudo swapoff -a` 并注释掉 `/etc/fstab` 里的 swap 行 | 最干净；重启后 `swapon --show` 应无输出 |
| 二选一：保留 swap + 内存锁定 | 什么都不做，靠 §3 的 `bootstrap.memory_lock: true` | 需要容器有 `memlock` 权限（compose 里已给：`ulimits.memlock: -1`） |

```bash
# ② 起来之后验证"内存锁定真的生效"（§4 启动完再做）
curl -s -u "$OPENSEARCH_USER:$OPENSEARCH_PASSWORD" 'http://127.0.0.1:9200/_nodes?filter_path=nodes.*.process.mlockall'
# 期望：每个节点 mlockall: true
```

### 2.4 数据盘挂载点与权限

```bash
# ① 看清磁盘：容量、挂载点、文件系统类型
df -hT | grep -E 'Filesystem|/home|/data'
lsblk -f
# 期望：/home（或你规划的数据盘）容量 ≥ 200GB（本次给定），文件系统 xfs 或 ext4

# ② 写测试（★权限问题不要靠"应该能写"猜，实测一次）
sudo -u lhim sh -c 'dd if=/dev/zero of=/home/lhim/os/.wtest bs=1M count=64 oflag=direct && rm -f /home/lhim/os/.wtest'
# 期望：64+0 records out … copied 67108864 bytes（无报错）
```

```bash
# ③ 目录规划（一次建全；与 §3 编排、§4 输出、§6.4 快照路径逐字对应）
mkdir -p /home/lhim/os/data /home/lhim/os/backup
```

```bash
# ④ 权限：OpenSearch 容器内以 uid=1000 gid=1000 运行，宿主目录必须能写
sudo chown -R 1000:1000 /home/lhim/os/data /home/lhim/os/backup
sudo chmod 750 /home/lhim/os/data /home/lhim/os/backup
sudo chmod 750 /home/lhim/os

# ⑤ 复核（两个目录都应显示 1000:1000 与 drwxr-x---）
ls -ld /home/lhim/os /home/lhim/os/data /home/lhim/os/backup
```

| 目录（宿主） | 容器内路径 | 用途 | 属主 |
| --- | --- | --- | --- |
| `/home/lhim/os/data` | `/usr/share/opensearch/data` | 索引数据（**唯一有状态目录**） | `1000:1000`，`750` |
| `/home/lhim/os/backup` | `/usr/share/opensearch/backup` | 快照仓库（`path.repo`） | `1000:1000`，`750` |
| `/home/lhim/os/.env` | 注入容器 env | 配置（**含口令，`chmod 600`**） | `lhim` |
| `/home/lhim/os/docker-compose.yml` | —— | 编排 | `lhim` |
| `/home/lhim/os/os.sh` | —— | 唯一入口 | `lhim` |

### 2.5 防火墙（**9200 绝不对外**）

| 端口 | 放行来源 | 用途 |
| --- | --- | --- |
| `9200/tcp` | 两台 AI/应用服务器 + 另一台 OS 节点（`10.254.212.111`）——**仅此** | REST（kNN 检索、`_bulk`、快照管理） |
| `9300/tcp` | 仅集群内节点（本方案单节点=不需要；**附录 A 三节点时需要**） | 节点间传输 |
| `9600/tcp` | **不放行**（仅本机回环排查用） | 性能分析器 |

```bash
# ① 先看用的是哪个防火墙（麒麟常见 firewalld 或 iptables；本机实际用哪个未确认，见 §8）
systemctl is-active firewalld 2>/dev/null || true
systemctl is-active iptables  2>/dev/null || true
sudo firewall-cmd --state     2>/dev/null || true
```

```bash
# ② firewalld 版（稳妥做法：把 9200 只放进受信来源的富规则，不做全局开放）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="10.254.212.111" port port="9200" protocol="tcp" accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="10.254.208.0/24" port port="9200" protocol="tcp" accept'
#   ↑ 上面这条 /24 只是示例占位：把 <两台 AI/应用服务器内网IP> 逐个替换成明确地址再执行，不要留网段
sudo firewall-cmd --reload
sudo firewall-cmd --list-rich-rules
```

```bash
# ③ iptables 版（仅当机器上确实没有 firewalld）
sudo iptables -I INPUT -p tcp -s 10.254.212.111 -m tcp --dport 9200 -j ACCEPT
sudo iptables -I INPUT -p tcp -s <应用服务器IP> -m tcp --dport 9200 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 9200 -j DROP
#   然后持久化（麒麟上一般是 iptables-services 或 firewalld；按平台方口径做）
```

```bash
# ④ 自检：从【不该通】的地方验证它真的不通
#   在第三台机器（或任意不在白名单的机器）上执行：
curl -m 3 -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:9200/ ; echo "exit=$?"
# 期望：超时（exit=28）或 connection refused ——【绝不能】是 200 或 401
```

> ⚠️ **认证 ≠ 防火墙**：内网 HTTP + basic auth 意味着"口令一旦泄漏就能直连"。
> 所以**必须**两者同时做——防火墙收紧来源，认证挡住可达者。这条是 §3.5 选型的补偿措施，不是可选项。

### 2.6 时间同步

```bash
# ① 看时间同步状态
timedatectl                 # 期望：System clock synchronized: yes
timedatectl show-timesync --all 2>/dev/null | head -20 || true
chronyc sources -v 2>/dev/null || ntpq -p 2>/dev/null || true

# ② 确保服务在跑（内网时间源地址需平台方给，见 §8）
sudo systemctl enable --now chronyd 2>/dev/null || sudo systemctl enable --now ntpd 2>/dev/null || true

# ③ 时区（政务系统日期敏感，与主系统容器 TZ=Asia/Shanghai 对齐）
sudo timedatectl set-timezone Asia/Shanghai
date
```

> **为什么时间同步是部署前提**：OpenSearch 的时间戳、快照元数据、证书有效期（若走 TLS）、
> 以及跨机日志比对都依赖准确时间；两台机器时钟漂移会让排障时的证据互相矛盾。

### 2.7 本节完成自检

```bash
sysctl vm.max_map_count            # 262144
ulimit -n                          # ≥65536（重登后 65536）
ls -ld /home/lhim/os/data          # 1000:1000, drwxr-x---
uname -m                           # aarch64 或 x86_64（决定 §1 拉哪个平台）
timedatectl | head -3              # synchronized: yes
```

---

## 3. 编排与 `.env`

> **两个文件必须放在同一个目录**（`/home/lhim/os/`），因为 compose v2 只读**与 compose 同目录**的 `.env`。
> 发布方式：把 [`deploy/opensearch/`](../deploy/opensearch/) 下的三个文件 `docker-compose.yml` / `.env.example` / `os.sh`
> 用 `scp -r deploy/opensearch/* lhim@10.254.212.110:/home/lhim/os/` 铺过去（`.env.example` 不填，现场 `cp` 成 `.env`）。

### 3.1 `docker-compose.yml`（**单节点版，逐字复制**）

```yaml
# ============================================================
# OpenSearch 单节点【服务器专用】编排（离线部署：只 load，不 build）
#
# 为什么没有 build: 段 —— 服务器无外网，镜像只能由开发机 docker save 后 scp 过来 docker load。
# pull_policy: never   —— 本机没有镜像时立刻报错，绝不联网拉取、也绝不误触发构建。
# 工程名 = 目录名      —— 本文件放在 /home/lhim/os/ 下 ⇒ compose 工程名 os（脚本不传 -p）。
# .env 必须与本文件同目录（compose v2 只读同目录的 .env）。
#
# 启动： cd /home/lhim/os && bash os.sh start     （或手工 docker compose up -d）
# 说明： 单节点（discovery.type: single-node）不写 cluster.initial_master_nodes / discovery.seed_hosts；
#        需要 2/3 节点集群时看文档《OpenSearch部署与接入.md》附录 A。
# 安全： 默认内网 HTTP + basic auth（强口令）；TLS 为可选，见文档 §3.5。
# ============================================================
services:
  opensearch:
    image: opensearchproject/opensearch:${OS_IMAGE_TAG:-2.19.1}
    pull_policy: never
    container_name: pm-opensearch
    restart: unless-stopped
    environment:
      # ── 集群与节点（单节点模式：不配 seed_hosts / initial_master_nodes）──
      cluster.name: pm-os
      node.name: os01
      discovery.type: single-node
      # ── 内存：16GB 物理内存的 50%，两个值必须相等；上限 31g ──
      #    剩下 8GB 留给文件系统 cache（Lucene 靠它读索引文件，全给堆反而更慢）
      OPENSEARCH_JAVA_OPTS: "-Xms8g -Xmx8g"
      # 锁定内存：禁止堆被换出（配合 ulimits.memlock: -1）
      bootstrap.memory_lock: "true"
      # ── 路径 ──
      path.data: /usr/share/opensearch/data
      path.repo: /usr/share/opensearch/backup
      # ── 网络：network.host 绑到内网地址（不绑 0.0.0.0）；对外暴露由 ports 的 OPENSEARCH_BIND_IP 决定 ──
      network.host: 0.0.0.0
      http.port: 9200
      # ── 安全插件：不用 demo 配置（证书/口令由 .env 给）──
      DISABLE_INSTALL_DEMO_CONFIG: "true"
      DISABLE_SECURITY_PLUGIN: "false"
      OPENSEARCH_INITIAL_ADMIN_PASSWORD: ${OPENSEARCH_PASSWORD:?必须在 .env 中设置 OPENSEARCH_PASSWORD}
      TZ: ${TZ:-Asia/Shanghai}
    ulimits:
      memlock:
        soft: -1
        hard: -1
      nofile:
        soft: 65536
        hard: 65536
    volumes:
      - ${OS_DATA_DIR:-/home/lhim/os/data}:/usr/share/opensearch/data
      - ${OS_BACKUP_DIR:-/home/lhim/os/backup}:/usr/share/opensearch/backup
    ports:
      # ★9200 只绑内网（默认只绑回环，最保守）；绝不要写 0.0.0.0
      - "${OPENSEARCH_BIND_IP:-127.0.0.1}:9200:9200"
    healthcheck:
      # 容器内自带 curl；口令从容器 env 读，不写死在文件里
      test: ["CMD-SHELL", "curl -sS -u \"$$OPENSEARCH_USER:$$OPENSEARCH_PASSWORD\" http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 180s
    logging:
      # 限制容器日志体积，防长期运行打满磁盘（无外网服务器尤为重要）
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"
```

### 3.2 `.env.example`（**逐字复制；`cp .env.example .env` 后只改标了「必填」的**）

```bash
# ============================================================
# OpenSearch 单节点 .env（与 docker-compose.yml 同目录：/home/lhim/os/.env）
#
# 为什么单独一个文件：口令不进 compose（compose 会被人复制、被贴进工单），
# .env 只在本机、权限 600，与主系统 main/.env、ai/.env 同一套做法。
#
# 用法： cp .env.example .env && chmod 600 .env && vi .env
# ============================================================

# ── 镜像（★由 os.sh load 自动改写；手工 load 时自己改）──
# 必须是本机 docker load 进来的那个 tag，例如 2.19.1
OS_IMAGE_TAG=2.19.1

# ── 对外绑定与端口 ──
# ★必填：写【本机内网 IP】（10.254.212.110 或 10.254.212.111），让 9200 只在这张网卡上监听；
#   默认 127.0.0.1 最保守（只有本机能连）。两者都不写 0.0.0.0。
OPENSEARCH_BIND_IP=127.0.0.1
OPENSEARCH_PORT=9200

# ── 安全插件（basic auth）──
OPENSEARCH_USER=admin
# ★必填：强口令。复杂度要求（OpenSearch 安全插件校验，不满足则容器启动失败）：
#   ≥ 8 位，且【大写字母、小写字母、数字、特殊字符】四类都要有；不要用 admin/Password123 这类。
#   生成示例（服务器上执行）：  openssl rand -base64 24
OPENSEARCH_PASSWORD=change-me-Strong-Passw0rd!

# ── 索引名（可选）──
# 只被 os.sh status 用来显示；真正决定检索索引的是【AI 侧】/home/lhim/pm/ai/.env 的
# OPENSEARCH_INDEX，两边必须写得完全一样（默认 pm-ai-chunks，与代码默认值一致）。
OPENSEARCH_INDEX=pm-ai-chunks

# ── 宿主目录（compose 的两个卷映射；属主必须 1000:1000，权限 750，见文档 §2.4）──
OS_DATA_DIR=/home/lhim/os/data
OS_BACKUP_DIR=/home/lhim/os/backup

# ── 时区（政务系统日期敏感；与主系统容器 TZ=Asia/Shanghai 对齐）──
TZ=Asia/Shanghai
```

> **`OPENSEARCH_PASSWORD` 的三条硬约束**：① 复杂度四类齐全且 ≥8 位（**不满足会直接启动失败**）；
> ② 只存在于 `.env`（`chmod 600`）与密码库，**不入仓库、不进工单**；
> ③ **两台机器若将来组成集群，口令必须完全一致**（否则集群内部认证失败）——本方案单节点，这条只在附录 A 生效。

### 3.3 AI 侧要填的四个变量（**与 §6.1 是同一组，这里先登记**）

| 变量 | 值 | 说明 |
| --- | --- | --- |
| `VEC_BACKEND` | `opensearch` | 切换开关；**集群不可达时显式报错，绝不静默退回 `local`** |
| `OPENSEARCH_URL` | `http://10.254.212.110:9200` | 从 **AI 容器内**可达的地址；**不要写 127.0.0.1**（在容器里那是它自己） |
| `OPENSEARCH_INDEX` | `pm-ai-chunks` | 与代码默认值一致（`application.yml:60`） |
| `OPENSEARCH_USER` / `OPENSEARCH_PASSWORD` | `admin` / 同 §3.2 的强口令 | basic auth |

### 3.4 堆内存取值规则（照这个表取，别拍脑袋）

| 物理内存 | `-Xms`/`-Xmx`（两值相等） | 留给文件系统 cache |
| --- | --- | --- |
| 8 GB | `4g` | 4 GB |
| **16 GB（本次）** | **`8g`** | **8 GB** |
| 32 GB | `16g` | 16 GB |
| ≥64 GB | **`31g`（上限，不要超过）** | 其余 |

> 规则：**取物理内存的 50%，上限 31g**。超过 31g 会失去 JVM 的压缩指针（`UseCompressedOops`），
> 同样的数据反而需要更多内存。**`-Xms` 与 `-Xmx` 必须相等**：不等会让堆在使用中反复扩张/收缩，产生停顿。
> 另外：容器**不设 `mem_limit`**，靠 JVM 参数约束；若平台方要求 cgroup 限额，请把 `mem_limit` 设为 `16g` 以上并同步复核（未确认项，见 §8）。

### 3.5 安全方案怎么选（TLS 两种选择）

| 方案 | 做法 | 优点 | 代价与风险 | 建议 |
| --- | --- | --- | --- | --- |
| **A. 内网 HTTP + basic auth（本方案默认）** | 不改安全插件配置，只配强口令 + 防火墙收紧来源 | 落地快、无证书运维；AI 侧只填 `http://` | **口令明文过网**（内网嗅探可截获）；一旦口令泄漏即可直连 | ✅ **推荐**，但**必须**同时满足：9200 只放行两台应用服务器 + 口令强度达标 + 定期轮换 |
| **B. 自签 TLS（`security` 插件自带证书）** | 生成 CA + 节点证书与 `admin` 客户端证书，配 `plugins.security.ssl.http.enabled=true`、`pemcert_filepath` 等；AI 侧改用 `https://` | 传输加密、可做客户端证书认证 | 证书生成/分发/轮换/过期全要运维；JVM 需信任自签 CA（AI 侧要额外导信任库）；密钥与口令分离管理更麻烦 | 仅当**等保/合规明确要求传输加密**时选 |

> - **明确不要用 demo 配置**：官方镜像默认会装一份 demo 证书与 `admin/admin` 口令，那是给本地试用用的。
>   本方案用 `DISABLE_INSTALL_DEMO_CONFIG: "true"` 关掉它，并用 `.env` 注入强口令。
> - **`DISABLE_INSTALL_DEMO_CONFIG` 的取舍**：设为 `true` 后，**首次启动必须提供 `OPENSEARCH_INITIAL_ADMIN_PASSWORD`**（compose 里已用
>   `${OPENSEARCH_PASSWORD:?...}` 强制必填）——换句话说，**它把"忘了设口令"从"跑起来但弱口令"变成"直接启动失败"**，这是我们要的行为。
>   反之若设为 `false`（用 demo 配置），集群会带 demo 证书与弱口令起来，**不合规**。
> - **审计日志**：安全插件自带 audit log（写容器内 `/usr/share/opensearch/logs/`），需要留存时把该目录也挂卷（本方案未挂，见 §8）。

---

## 4. 启动与验收

### 4.1 启动（每台机器一条命令）

```bash
cd /home/lhim/os
cp .env.example .env          # 第一次才做
vi .env                       # ★必填：OPENSEARCH_BIND_IP=<本机内网IP>、OPENSEARCH_PASSWORD=<强口令>
chmod 600 .env

# 装载镜像（把 OS_IMAGE_TAG 对齐到包里的版本；已一致则提示"无需修改"）+ 起容器 + 健康检查
bash os.sh load /home/lhim/os/opensearch-2.19.1-linux-arm64.tar
```

**预期输出（关键行）**：

```text
[1/4] 装载镜像：docker load -i /home/lhim/os/opensearch-2.19.1-linux-arm64.tar
[2/4] 解析版本并把 .env 的 OS_IMAGE_TAG 对齐到 2.19.1
      OS_IMAGE_TAG: <无> → 2.19.1
      原 .env 已备份为 /home/lhim/os/.env.bak
[3/4] docker compose up -d
[4/4] 健康检查
[OK] OpenSearch 就绪
```

> 首次启动**约 30~90 秒**（JVM + 插件加载）；`os.sh load` 内部已做轮询（约 5s × 36 次 ≈ 180s），
> 失败会打印排查建议，**不会静默通过**。

### 4.2 日常启停（**记住这四条就够**）

```bash
cd /home/lhim/os
bash os.sh start              # 起（幂等：已在跑则只报状态）
bash os.sh stop               # 停（只停不删；容器保留，下次 start 更快）
bash os.sh restart            # 重启 = stop + start
bash os.sh status             # 容器状态 + 集群健康 + 节点列表（可用/不可用一句话结论）
bash os.sh logs               # 跟踪容器日志（Ctrl-C 退出）
bash os.sh load <镜像包.tar>  # load + 切 OS_IMAGE_TAG + 重启 + 健康检查
bash os.sh help               # 用法
```

> **`os.sh` 与 `pm.sh` 的关系**：两个入口**互不调用**——`pm.sh` 管 `/home/lhim/pm/{main,ai}`，
> `os.sh` 管 `/home/lhim/os`。日常运维记住"主系统/AI 用 `pm.sh`，OpenSearch 用 `os.sh`"即可。

### 4.3 验收（**逐条对照，全过才算部署成功**）

```bash
cd /home/lhim/os
export OPENSEARCH_USER=admin
export OPENSEARCH_PASSWORD='<你写进 .env 的那个强口令>'
OS=http://127.0.0.1:9200
```

**A1 · 集群健康 = green（★不是 yellow）**

```bash
curl -s -u "$OPENSEARCH_USER:$OPENSEARCH_PASSWORD" "$OS/_cluster/health?pretty"
```
```json
{
  "cluster_name" : "pm-os",
  "status" : "green",
  "number_of_nodes" : 1,
  "number_of_data_nodes" : 1,
  "active_primary_shards" : 0,
  "active_shards" : 0,
  "relocating_shards" : 0,
  "initializing_shards" : 0,
  "unassigned_shards" : 0,
  "delayed_unassigned_shards" : 0,
  "number_of_pending_tasks" : 0,
  "number_of_in_flight_fetch" : 0,
  "task_max_waiting_in_queue_millis" : 0,
  "active_shards_percent_as_number" : 100.0
}
```
> 期望**恰好是 `green`**。**若看到 `yellow`**：多半是某个索引设了 `number_of_replicas ≥ 1`——
> **单节点上副本永远无法分配**，状态必然 yellow 且白占一份盘。修法见 §5.1 / §7.5。

**A2 · 节点就位（单节点 = 1 行）**

```bash
curl -s -u "$OPENSEARCH_USER:$OPENSEARCH_PASSWORD" "$OS/_cat/nodes?v"
```
```text
ip        heap.percent ram.percent cpu load_1m load_5m load_15m node.role node.roles  master name
127.0.0.1           31          62   3    0.42    0.51     0.60 dimr      dimr        *      os01
```
> ⚠️ 单节点时 `_cat/nodes` 只显示 **1 行**，且 `ip` 列常见是 `127.0.0.1`（容器内视角）——**这是正常的，不是配置错了**。

**A3 · 内存锁定生效**

```bash
curl -s -u "$OPENSEARCH_USER:$OPENSEARCH_PASSWORD" "$OS/_nodes?filter_path=nodes.*.process.mlockall&pretty"
```
```json
{ "nodes": { "<node-id>": { "process": { "mlockall": true } } } }
```
> `mlockall: false` ⇒ §2.3 的内存锁定没生效（swap 还在或 `memlock` 权限不足），按 §7.6 处置。

**A4 · 索引列表（还没建索引时为空，属正常）**

```bash
curl -s -u "$OPENSEARCH_USER:$OPENSEARCH_PASSWORD" "$OS/_cat/indices?v&expand_wildcards=all"
```
```text
health status index uuid pri rep docs.count docs.deleted store.size pri.store.size
```
> 只有表头 = 还没建索引。做完 §5.1 后这里应出现 `green open pm-ai-chunks ... 1 0 ...`。

**A5 · 从 AI 那台机器验证连通性（**关键**：证明防火墙与绑定都对）**

```bash
# 在【AI 能力服务所在机器】执行（不是在 OS 机器上！）
curl -sS -m 5 -u admin:'<强口令>' http://10.254.212.110:9200/_cluster/health
# 期望：{"cluster_name":"pm-os","status":"green",...}
```

```
A1 green ｜ A2 1 行 ｜ A3 mlockall=true ｜ A4 表头或 green 索引 ｜ A5 跨机可达
```

### 4.4 单节点故障演练（**先做一次，避免真出事时误判**）

```bash
# ① 模拟"服务挂掉"（不是删数据）
cd /home/lhim/os && bash os.sh stop
curl -sS -m 3 http://127.0.0.1:9200/ ; echo "exit=$?"
# 期望：连不上（exit=7/28）——这是【预期现象】，不是部署失败

# ② 此时 AI 侧的表现（★这是"降级行为"的实测，§6.3 要求明确它）
curl -s "http://127.0.0.1:8100/health?with_vec=true"     # 期望：vec 块 not ok（不可用）
#   提问一次：期望【明确报错】，而不是"没找到相关内容"这类静默错答案

# ③ 起来
bash os.sh start && bash os.sh status                    # 期望：[OK] OpenSearch 可用
```

**本节说明（要"为什么"再看）**

- **为什么期望 green 而不是 yellow**：`yellow` = 有主分片但**副本分片未分配**。单节点没有第二台机器放副本，
  所以**只要设了副本，状态就永远 yellow**。green 的前提是"主分片全部就绪且副本数=0"。
- **为什么演练要现在做**：单节点是**单点故障**（§0.4）。演练的目的是让运维**认得出**这个现象
  （连不上 ≠ 部署坏了），以及确认 AI 侧的降级行为确实是"明确报错"。
- **开关机自启**：`restart: unless-stopped` 保证 docker daemon 重启后容器自动回来；
  宿主机重启后请确认 `systemctl is-enabled docker`（手册 §1.2 ①）。

---

## 5. 索引与检索设计

### 5.1 容量与分片（**先看这张表，再决定参数**）

**单条切片的真实口径**（读代码确认）：向量 = 4096 维 `float32` = `4096 × 4 = 16,384 字节 ≈ 16 KB`；
再加上原文（≤500 字）、`doc_id`/`filename` 等字段与倒排，**≈18~20 KB/切片**（正文与倒排为**估算（假设：平均每切片 500 字、中文 3 字节/字 ≈1.5KB 原文）**）。

| 规模（切片数） | 对应文档数（估算，假设：30 切片/份） | 向量本体 | **索引总占用（估算，含原文+倒排+段开销，约 ×1.15~1.25）** | `local`（进程内 JSON）对比 |
| --- | --- | --- | --- | --- |
| 2 万 | ≈670 份 | 16KB × 2 万 ≈ **313 MB** | **≈350~400 MB** | 2 万 × 38,333 B ≈ **766 MB**（且每次提问全量解析） |
| 10 万 | ≈3,300 份 | ≈ **1.56 GB** | **≈1.8 GB** | ≈ **3.8 GB** |
| 100 万 | ≈33,000 份 | ≈ **15.6 GB** | **≈18 GB** | ≈ **38 GB**（且单问 +3.8s 起点） |

> **对照口径**：现有 `local` 的实测值是 **38,333 字节/切片（9.36 字节/维）**——那是**十进制文本 JSON**，
> 同一份数据换成 OpenSearch 的 `float32` 二进制后**只占几百 MB**，检索是毫秒级 kNN。
> 结论：**本项目这个量级不需要量化、也不需要 `on_disk`**（那两样是百万级/磁盘受限时的手段，见 §5.3）。
> 磁盘规划：索引数据放 §2.4 的 200GB 盘，**给快照留出与索引等量的空间**（§7.2 每天一份、按保留期轮转）。

**分片与副本建议**

| 参数 | 值 | 理由 |
| --- | --- | --- |
| `number_of_shards` | **1** | 单节点 + 这个量级（≤100 万切片）：**分片越少越好**。分片是并行度单位，但每个分片都有固定开销（段、堆、文件句柄），1 个分片在单节点上就是最优的检索并行度来源 |
| `number_of_replicas` | **0** | **单节点上副本永远无法分配** ⇒ 只会让状态变 **yellow** 且白占一份盘。**副本不能替代备份**（备份看 §7.2） |
| `refresh_interval` | 写入期 `-1`（关闭），写完改回 `1s` | 批量灌数据时避免频繁 refresh 产生大量小段 |
| `index.knn` | `true` | kNN 索引开关（mapping 同级 settings） |

> ⚠️ **`number_of_replicas: 0` 是单节点的必然结论，不是"省事"**：设 1 的后果是
> ① 集群状态永久 yellow（运维每次看到都要重新判断一次"是不是坏了"）；② 白白占用一份磁盘；
> ③ 换不到任何可用性（单节点挂了，副本也访问不到）。
> 将来加第 3 台转 3 节点集群时，**才**把副本改成 1（见附录 A）。

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
> 所以本节的 filters 示例要真正生效，需要**先把元数据从主系统带进切片**（`attachment_id → project_id/密级`的映射在主系统崖山库），
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
| `on_disk` / 量化（`sq`/`pq`、`mode: on_disk`） | **本方案不用** | 百万级切片或磁盘受限时才考虑；**版本前提**：磁盘化向量检索（`mode: on_disk`）是 2.x 后期引入的能力（2.17 起在 k-NN 插件里逐步提供），**本项目当前约 2 万切片规模用不上**。要用请先核对你的集群版本与官方 k-NN 文档，切了必须**重建索引** |

> **验证口径（做完 §5.5 的对照才算数）**：对同一份语料、同一批问题，把 OpenSearch kNN 的 top-k 顺序与 `local` 的 top-k **逐条对比**；
> 顺序对不上通常意味着 `space_type` 或字段名写错（对照 [`OpenSearchIndex`](../ai-backend/src/main/java/com/pmgt/ai/module/retrieval/OpenSearchIndex.java) 的注释协议：
> 建索引 → 幂等写入 → 查询页码一致 → 顺序与 local 一致）。

### 5.4 写入（`_bulk`，`_id` = 切片键，幂等 upsert）

```bash
# ① 一份 NDJSON：每个文档两行（action + 文档），【每行末尾都要有换行，最后一行也不例外】
cat > /home/lhim/os/bulk-demo.ndjson <<'EOF'
{"index":{"_index":"pm-ai-chunks","_id":"d-1001:1:9a1c0f3b7d2e"}}
{"doc_id":"d-1001","filename":"施工合同.pdf","page_no":1,"text":"甲方应于合同生效后 30 日内支付预付款……","chunk_key":"d-1001:1:9a1c0f3b7d2e","project_id":"P2026-001","phase_code":"CONTRACT","attachment_id":"8842","security_level":"内部","doc_type":"合同","source":"pm-attachment","created_at":"2026-09-23T10:00:00+08:00","vector":<4096 个数字>}
EOF

# ② 灌数据
curl -sS -u "$AUTH" -XPOST "$OS/pm-ai-chunks/_bulk?refresh=false" \
  -H 'Content-Type: application/x-ndjson' --data-binary @/home/lhim/os/bulk-demo.ndjson
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
| RRF（倒数排名融合） | `score = Σ 1/(k + rank)`，按排名而非分数融合 | 更省心（无需归一化）；**属较新的能力，且各版本支持度不同，用前先核对你的集群版本文档**（本方案不依赖它） |

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
OPENSEARCH_URL=http://10.254.212.110:9200
OPENSEARCH_INDEX=pm-ai-chunks
OPENSEARCH_USER=admin
OPENSEARCH_PASSWORD=<与 OpenSearch /home/lhim/os/.env 里的强口令完全一致>

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
> 所以"双写"这件事**不通过配置实现**，而是靠**保留本地 `work/vectors/` 不删**——它天然就是 `local` 的完整副本，
> 这已经等价于"双份数据 + 秒级回滚"。**不要**为了双写去改代码，那属于应用改动（不在本轮范围）。

**★ OpenSearch 不可用时 AI 侧怎么降级（本轮必须定死的口径）**

| 口径 | 定稿 |
| --- | --- |
| **绝不静默降级** | 不允许"集群连不上就悄悄改用进程内余弦或退回关键词，却不说"——那会让用户拿到**看起来正常但来源不同**的答案。现有代码已经钉住了这条（**显式抛错**） |
| 用户可见 | 问答返回**明确失败**（"检索服务不可用"类话术），**不是**"没找到相关内容" |
| 运维可见 | AI 日志有明确原因（"集群不可达（http://…）"）；`/health?with_vec=true` 的 `vec` 块为 not ok |
| 恢复 | OpenSearch `os.sh start` 后 AI **不需要重启**（每次请求现建连接），下一次提问即恢复 |

### 6.4 第二台机器 `10.254.212.111`：**冷备 + 演练机**（本方案定稿用法）

| 项 | 做法 |
| --- | --- |
| 角色 | **冷备**（同构、装了同一套编排与镜像，**默认停着**；需要时才起）+ **恢复演练机** |
| 为什么默认停着 | 单节点方案下，它起来也**不参与**任何服务（没有 quorum、没有副本）；不如把资源留给"随时能顶上去" |
| 每天做什么 | ① 在 `.110` 上打快照（§7.2）；② 把 `/home/lhim/os/backup` **`rsync` 到 `.111`** |
| 何时真的切过去 | `.110` 硬件故障且短期修不好 → 在 `.111` 上 `os.sh start` + 注册快照仓库 + `_snapshot/.../_restore`（RTO 分钟级） |
| 何时不用它了 | ① 需要**两个 AI 实例**做无中断检索；② 切片 **>50 万** → 加**第 3 台**转 3 节点集群（附录 A） |

```bash
# 每晚在 .110 上执行（建议做成 cron；具体时间与保留期见 §8）
# ① 打快照（需先在 .110 注册仓库，见 §7.2）
curl -sS -u "$AUTH" -XPUT "$OS/_snapshot/os-backup/nightly-$(date +%F)?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d '{"indices":"pm-ai-chunks","ignore_unavailable":true,"include_global_state":false}'

# ② 把快照目录同步到 .111（快照文件是可复制物）
rsync -a --delete /home/lhim/os/backup/ lhim@10.254.212.111:/home/lhim/os/backup/
#   ⚠️ .111 上目录属主同样必须 1000:1000（否则恢复时 OpenSearch 读不了），见 §2.4
```

**恢复演练步骤（建议每季度一次；RTO 口径见下）**

```bash
# 在 .111 上（演练机；演练时 .110 正常跑着，不动它）
cd /home/lhim/os
# ① 确认目录权限与 .env（OPENSEARCH_BIND_IP / OPENSEARCH_PASSWORD 与 .110 一致）
ls -ld data backup
# ② 起容器（此时 .111 是一个【独立的空集群】）
bash os.sh start
# ③ 注册快照仓库（指向 rsync 过来的那个目录）
curl -sS -u "$AUTH" -XPUT "$OS/_snapshot/os-backup" -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/usr/share/opensearch/backup","compress":true}}'
# ④ 看有哪些快照
curl -sS -u "$AUTH" "$OS/_snapshot/os-backup/_all?pretty" | head -40
# ⑤ 恢复
curl -sS -u "$AUTH" -XPOST "$OS/_snapshot/os-backup/nightly-2026-09-24/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{"indices":"pm-ai-chunks"}'
# ⑥ 核数：与 .110 的 _count 一致
curl -sS -u "$AUTH" "$OS/pm-ai-chunks/_count"
# ⑦ 演练完把 .111 停掉，别让它长期跑着（避免以后误判"哪个是主"）
bash os.sh stop
```

| 指标 | 口径 |
| --- | --- |
| RTO（恢复时间目标） | **分钟级**（估算（假设：索引 ≈2 万切片 ≈400MB，本地 `_restore` 速率按 20~50MB/s）⇒ **约 10~60 秒**，加上起容器≈90s、注册仓库与核数操作 ⇒ **约 5 分钟**） |
| RPO（可接受的数据丢失） | **≈1 天**（每晚一份快照）。要更小就把快照频次提到每小时（对索引无影响，只多占盘） |
| 演练判据 | ⑥ 的 `count` 与主节点一致，且随机抽 3 个问题能检索到相同页码 |

### 6.5 切换前检查清单（**逐条打勾，缺一不可**）

```
□ A1~A5 全绿（§4.3），且 _cluster/health 是 green（不是 yellow）
□ 索引映射已建：dimension=4096、space_type=cosinesimil、number_of_replicas=0（§5.2）
□ _count 与 N_chunks 一致；抽样 20 条，page_no 与原文页码对得上（§6.2 ⑥⑧）
□ AI 那台能跨机访问 9200（§4.3 A5），且【不该通的机器确实不通】（§2.5 ④）
□ ai/.env 已备份（.env.bak.<时间戳>）；OPENSEARCH_PASSWORD 与 os/.env 完全一致
□ work/vectors/ 与 work/docs/ 已备份（回滚 local 的保险）
□ 降级口径已确认（§6.3）：连不上时【明确报错】，不静默换检索方式
□ 快照仓库已注册 + 至少成功打过一份快照 + 已 rsync 到 .111（§7.2 / §6.4）
□ 有人知道回滚怎么做（VEC_BACKEND=local + pm.sh restart ai，秒级）
```

**本节说明：顺带拿到的一条收益**

- **AI 服务从"必须单实例"变成"可跑 2 个实例"**：`VEC_BACKEND=local` 是**进程内状态**（向量缓存 + 文档库都在本地卷上），
  这正是它不能 HA 的根因；向量与索引搬进 OpenSearch 后，检索侧不再依赖本地进程状态，AI 才谈得上水平扩。
  ⚠️ **但要如实说边界**：`work/docs/`（文档库）与 `work/vectors/`（缓存）**仍是本地卷**（代码现状），
  所以"完全无状态 + 2 实例"还需要**把文档库也搬出进程**——这一步**不在本轮方案内**（§8 未确认项）。
  本次部署的确定收益是：**检索不再受单实例约束、延迟与内存不再随语料线性劣化、可以做"先过滤再检索"**。

---

## 7. 运维

### 7.1 容量与水位（磁盘）

| 水位 | 默认阈值（2.x） | 触发后的行为 | 处置 |
| --- | --- | --- | --- |
| `low` | 85% | **不再向该节点分配新分片** | 清理旧快照 / 扩容 / 迁移分片 |
| `high` | 90% | 尝试把分片迁走；迁不走就继续涨 | 立即清理，别等 critical |
| `flood` | 95% | **索引变只读**（`index.blocks.read_only_allow_delete`） | 见 §7.5 |

```bash
# 看水位与磁盘
curl -sS -u "$AUTH" "$OS/_cat/allocation?v"
curl -sS -u "$AUTH" "$OS/_cluster/settings?include_defaults=true&flat_settings=true" | grep -o '"cluster.routing.allocation.disk[^,]*'
df -h /home/lhim/os/data /home/lhim/os/backup
```

> **单节点方案的水位要更敏感**：没有第二台机器可以"迁走分片"，到 `low` 就只能靠**清理**（快照轮转、删无用索引）。
> 建议把 `low` 提前到 **75%**（`PUT _cluster/settings {"persistent":{"cluster.routing.allocation.disk.watermark.low":"75%"}}`），
> 给 `.110` 这台 200GB 盘留出反应时间（**这个 75% 是运维建议值，不是官方默认**）。

### 7.2 快照与恢复（**单节点的强制项**）

```bash
# ① 注册文件系统仓库（location 必须与 compose 的 path.repo 一致）
curl -sS -u "$AUTH" -XPUT "$OS/_snapshot/os-backup" -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/usr/share/opensearch/backup","compress":true}}'
curl -sS -u "$AUTH" "$OS/_snapshot/os-backup/_verify"      # 期望 {"nodes":{...}} 全 ok

# ② 打一份快照（wait_for_completion=true 便于脚本判成功）
curl -sS -u "$AUTH" -XPUT "$OS/_snapshot/os-backup/nightly-$(date +%F)?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{"indices":"pm-ai-chunks","include_global_state":false}'

# ③ 看结果：state 应为 SUCCESS
curl -sS -u "$AUTH" "$OS/_snapshot/os-backup/_all?pretty" | grep -E '"state"|"snapshot"|"duration_in_millis"'

# ④ 恢复（演练或真故障）
curl -sS -u "$AUTH" -XPOST "$OS/_snapshot/os-backup/nightly-2026-09-24/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{"indices":"pm-ai-chunks"}'
```

| 项 | 建议 |
| --- | --- |
| 频次 | **每晚一次**（RPO ≈1 天）；要更小就每小时 |
| 保留 | 最近 **7 天**（`_snapshot/os-backup/_all` + 删除超过 7 天的 `nightly-*`）；具体保留期见 §8 |
| 异地 | 每晚 `rsync` 到 `.111`（§6.4），**这是单节点方案里"防硬件损坏"的唯一手段** |
| 演练 | 每季度一次（§6.4 的七步），**演练过才算有备份** |

### 7.3 滚动重启与版本升级

| 场景 | 做法 |
| --- | --- |
| 重启容器（改 `.env` / 改堆） | `cd /home/lhim/os && bash os.sh restart`；**单节点无需"滚动"**，重启期间检索不可用（分钟级），选低峰窗口 |
| 改索引 mapping | mapping 字段**只能加**、不能改类型；`knn_vector` 的 `dimension`/`space_type`/`engine` **改了就必须重建索引**（新建索引 → 重新灌 → 用别名切） |
| 升级 OpenSearch（2.x → 2.x 小版本） | ① 读官方升级说明（版本间的 breaking change 与 k-NN 变更）；② **先打快照**；③ 开发机出**新 tag** 的镜像（§1，`OS_IMAGE_TAG` 改新版本）；④ `bash os.sh load <新包.tar>`；⑤ 验收（§4.3 A1~A5）；⑥ 出问题回滚：`os.sh load <旧包.tar>`（**注意**：索引数据是"新版本写过"的，**降级前先确认该版本支持读回**，不行就用快照恢复） |
| 2.x → 3.x 大版本 | **不要直跳**：按官方"先升到最后一个 2.x，再升 3.x"的路径走；每步都打快照 |

```bash
# 升级的标准动作（低峰窗口；先看清版本再动）
cd /home/lhim/os
grep '^OS_IMAGE_TAG=' .env                       # 当前版本
curl -sS -u "$AUTH" "$OS/" | grep -o '"number" : "[^"]*"'   # 集群报告的版本
bash os.sh load /home/lhim/os/opensearch-<新版本>-linux-arm64.tar
bash os.sh status
```

### 7.4 监控要看哪些指标

| 类别 | 指标 | 看它判什么 | 怎么看 |
| --- | --- | --- | --- |
| **JVM 堆** | 堆使用率（`heap.percent`）、GC 次数/耗时 | 堆长期 >85% 或 GC 停顿变长 ⇒ 调 `-Xms/-Xmx` 或扩内存 | `_cat/nodes?v`、`_nodes/stats/jvm` |
| **内存锁定** | `mlockall` | 必须 `true`，否则可能被换出导致停顿 | `_nodes?filter_path=nodes.*.process.mlockall`（§4.3 A3） |
| **线程池** | `search`/`write` 队列与拒绝数（`rejected`） | 有拒绝 ⇒ 检索/写入压力超容量 | `_cat/thread_pool/search,write?v&h=node_name,active,queue,rejected` |
| **磁盘** | 各盘使用率与水位 | 见 §7.1；**单节点最容易死在这里**（快照会吃盘） | `_cat/allocation?v`、`df -h` |
| **磁盘 I/O** | 读写延迟、IO util | kNN 检索是内存+磁盘混合负载，I/O 饱和会拖慢查询 | 宿主 `iostat -x 1`（若有 sysstat；**麒麟上是否预装未确认**） |
| **段与合并** | 段数、merge 次数与耗时 | 段数暴涨（批量写入期）⇒ 用 `forcemerge` 收口 | `_cat/segments/pm-ai-chunks?v`、`_nodes/stats/indices/merges` |
| **kNN** | `_plugins/_knn/stats`（缓存、加载的图） | 图未被缓存 ⇒ 每次查询走磁盘 | `GET _plugins/_knn/stats?pretty` |
| **集群状态** | `status`、`unassigned_shards`、`number_of_pending_tasks` | yellow/red 判读见 §7.5；pending tasks 长期 >0 说明 master 忙 | `_cluster/health`、`_cat/pending_tasks?v` |
| **快照** | 最近快照的 `state` 与耗时 | 失败要在 24h 内发现（否则等于没有备份） | `_snapshot/os-backup/_all` |
| **容器** | `os.sh status` 的结论行 | 一句话"可用/不可用" | `bash os.sh status` |

> ⚠️ **本项目 AI 侧目前没有 actuator/micrometer**（《AI模型调用与任务队列评估》已登记"无任何指标"），
> OpenSearch 侧也**没有接 Prometheus/告警**——本节的指标目前**只能靠命令巡检**。要不要上监控栈见 §8。

### 7.5 常见故障

| 现象 | 判读 | 处置 |
| --- | --- | --- |
| 容器起不来，日志 `max virtual memory areas vm.max_map_count [65530] is too low` | 内核参数没设或没持久化（重启后丢了） | §2.1 全做一遍；**注意容器内改不了**，必须在宿主设 |
| 启动失败并提示口令不合规（密码复杂度） | `OPENSEARCH_PASSWORD` 没满足"四类字符 + ≥8 位" | 换口令（`openssl rand -base64 24`），`os.sh restart` |
| 启动失败：`OPENSEARCH_INITIAL_ADMIN_PASSWORD is not set` | `.env` 里没写口令，或 compose 没读到 `.env` | 确认 `.env` **与 compose 同目录**、`OS_IMAGE_TAG` 与 `OPENSEARCH_PASSWORD` 都有值 |
| **`red`** | 有**主分片**未分配 ⇒ 检索/写入报错 | 看 `_cat/indices?v` 与 `_cluster/allocation/explain`；单节点上常见原因是**数据目录权限**（§2.4 属主/权限）或**磁盘满**（§7.1） |
| **`yellow`** | 主分片就绪、**副本未分配** | **单节点上几乎总是"某个索引设了 replica ≥1"**：`PUT /<索引>/_settings {"index":{"number_of_replicas":0}}`。**幂等修法**：`_cat/indices?v&health=yellow` 找出来逐个改 |
| 健康检查一直 not ok，日志报内存锁定失败 | `memlock` 权限不足（或 cgroup 限制） | 确认 compose 的 `ulimits.memlock: -1` 生效；仍失败就临时 `bootstrap.memory_lock: false`（**性能会降，属临时手段**），同时查宿主 limits |
| 查询报 `401 Unauthorized` | 口令不一致 / `.env` 引号问题 | 比对 `os/.env` 与 `ai/.env` 的 `OPENSEARCH_PASSWORD` **逐字符**；`.env` 里**不要加引号**；用 `curl -u admin:'口令'` 单独验证 |
| 查询报 `403 Forbidden` | `admin` 之外的账号权限不足（如只读账号做写入） | 用 `admin` 做写入；只读账号另外建并给 `read` 权限 |
| 磁盘满 / 索引变只读（`flood stage`） | 达到 95% 水位，索引被置只读 | ① 清旧快照（`_snapshot/os-backup/nightly-*`）；② 解锁：`PUT /<索引>/_settings {"index.blocks.read_only_allow_delete":null}`；③ 复核 §7.1 水位 |
| 首次检索特别慢（几十秒~分钟级） | **惰性向量化在问答路径上**（§6.2 现状） | 说明索引没预热：按 §6.2 先灌满索引，再切 `VEC_BACKEND`；临时手段是发几次同样的提问 |
| AI 日志"集群不可达（http://…）" | 网络/防火墙/绑定三者之一 | 在 AI 那台跑 §4.3 A5；核对 `OPENSEARCH_BIND_IP` 与 §2.5 白名单 |
| 日志出现 `unassigned_shards` 长期不降 | 分片分配卡住（磁盘水位 / 权限） | `_cluster/allocation/explain` 看原因；按 §7.1 / §2.4 处理 |

### 7.6 一条命令速查

```bash
cd /home/lhim/os
bash os.sh status                 # 容器 + 集群健康 + 节点（先看这个）
bash os.sh logs | tail -100       # 最近日志
bash os.sh restart                # 重启（改 .env 后）
bash os.sh load <新包.tar>        # 升级/换版本
```

---

## 8. 未确认项（**照实登记，需要你或平台方确认**）

| 序号 | 未确认项 | 影响 | 谁来确认 |
| --- | --- | --- | --- |
| 1 | **两台机器的实际 CPU 架构**（本文按银河麒麟 aarch64 写，但 §1 第 2 步要求 `uname -m` 确认） | 决定 `docker pull --platform` 与镜像包后缀（`arm64`/`amd64`） | 你（在服务器上执行 `uname -m`） |
| 2 | **AI 能力服务（ai-backend）实际跑在哪台机器上**（决定 §6.1 的 `OPENSEARCH_URL` 与 §2.5 的防火墙白名单） | 白名单写错 = 要么连不上、要么开太宽 | 你 |
| 3 | **`opensearchproject/opensearch:2.19.1` 这个 tag 是否真的存在且含 `arm64`** | 出镜像第一步就会验证；本文**未能联网核实**（开发机到 registry 的连接超时） | 你（`docker manifest inspect`） |
| 4 | **磁盘类型与容量**（本次给定 200GB；要确认是 SSD 还是 HDD、是否单盘） | HDD 上 kNN 检索延迟会明显变差；单盘则数据与快照抢 I/O | 你 / 平台方 |
| 5 | **是否允许在业务网段使用内网 HTTP**（§3.5 方案 A） | 若等保/合规要求传输加密，必须改走自签 TLS（工作量与证书运维都增加） | 平台方（安全/合规） |
| 6 | **是否需要 OpenSearch Dashboards** | 要的话需额外出镜像（Dashboards 与 OpenSearch 版本必须**同版本**）、加一个容器与端口；本文未包含 | 你 |
| 7 | **时间源**（内网 NTP 服务器地址） | 时间不同步会让日志/快照时间戳不可比 | 平台方 |
| 8 | **防火墙用哪个**（firewalld / iptables；麒麟发行版差异） | 决定 §2.5 用哪段命令 | 你（`systemctl is-active firewalld`） |
| 9 | **快照保留期与执行时间**（本文建议每晚 1 次、留 7 天） | 影响备份盘容量与 RPO | 你 |
| 10 | **现网真实文档数与切片总数**（§5.1 的规模表按 30 切片/份估算） | 决定"要不要现在就上"以及 §6.2 的重建耗时 | 你（`/documents` 或自检页） |
| 11 | **AI 容器是否设置 `-Xmx`**（《AI模型调用与任务队列评估》登记为未确认） | 与"内存触发点"（第 2 条）直接相关 | 你（查 `ai/docker-compose.yml` 与运行中容器） |
| 12 | **"切片带项目/密级元数据"这件事的落地排期**（§5.4 的 `project_id`/`security_level` 目前无人写入） | 不做这一步，OpenSearch 的"先过滤再检索"只是**能力就绪**、不是**功能可用** | 你 / 开发方 |
| 13 | **上一轮未确认项的继承**：平台是否真的不支持降维（本仓库实测传 `dimensions` → HTTP 400） | 决定 `VEC_EMBED_DIMENSIONS` 永远为 0（本文按"不传"写） | 平台方 |
| 14 | **是否要上监控/告警栈**（Prometheus、Grafana、OpenSearch 告警插件） | §7.4 的指标目前只能手工巡检 | 你 |

> **已由本轮决定解决、故不再列为未确认**：节点数（定为**单节点**，理由见 §0.3）、第二台机器的作用（**冷备 + 演练机**，§6.4）、
> 副本数（**0**，§5.1）、堆大小（**8g / 16GB 内存的 50%**，§3.4）。

---

## 9. 附录 A · 将来加第 3 台 → 3 节点集群（升级路径）

> **什么时候做**：① 需要**两个 AI 实例**做无中断检索；② 切片 **>50 万**（单节点磁盘/恢复窗口吃紧）。其余情况**不要动**（§0.3）。

**届时只需改两个参数（两台老机器各改一遍，第 3 台按新配置写）**

| 参数 | 单节点现值 | 3 节点改为 | 说明 |
| --- | --- | --- | --- |
| `discovery.seed_hosts` | **不写** | `os01,os02,os-mgr` | 3 个节点的**可解析名或 IP**；顺序不重要，但要**一致** |
| `cluster.initial_master_nodes` | **不写** | `os01,os02,os-mgr` | ★**只能在数据目录为空时写一次**：写错/改错必须清 `data/` 重来（这是最容易踩的坑） |

**另外要一起改的（不改这两个参数不足以成为真 HA）**

| 项 | 单节点 | 3 节点 |
| --- | --- | --- |
| `discovery.type` | `single-node` | **删掉这一行**（集群模式不能有它） |
| 第 3 台角色 | —— | `node.roles: [cluster_manager]`（**dedicated cluster-manager，不存数据**，可用轻量机） |
| 数据节点角色 | （默认全角色） | `node.roles: [data, ingest]`（两台老机器） |
| 副本数 | **0** | **1**（此时才有第二台机器放副本，green 才成立） |
| `OPENSEARCH_JAVA_OPTS` | `-Xms8g -Xmx8g` | 数据节点不变；dedicated cluster-manager 用小堆（如 `-Xms2g -Xmx2g`，**估算（假设：管理节点不存数据）**） |
| `container_name` | `pm-opensearch` | **必须改成 `os-node1`/`os-node2`/`os-node3`**：三台机器各自的容器名可以相同，但同一个部署根里若并存多个容器会撞名；为免歧义，节点名与容器名一一对应 |
| 防火墙 | 9200 白名单 | **另加 9300**（节点间传输）在 3 个节点两两之间放行 |
| 快照仓库 | 每台本地 `fs` 仓库 | 建议共享一个（NFS）或用 `s3` 类插件指向内网对象存储 |

```yaml
# 3 节点时的环境变量片段（示意，供届时改 os-node1/os-node2）
      cluster.name: pm-os
      node.name: os01
      node.roles: [data, ingest]
      discovery.seed_hosts: os01,os02,os-mgr
      cluster.initial_master_nodes: os01,os02,os-mgr
      # 删掉 discovery.type: single-node
```

```yaml
# 第 3 台（dedicated cluster-manager，不存数据）
      cluster.name: pm-os
      node.name: os-mgr
      node.roles: [cluster_manager]
      discovery.seed_hosts: os01,os02,os-mgr
      cluster.initial_master_nodes: os01,os02,os-mgr
      OPENSEARCH_JAVA_OPTS: "-Xms2g -Xmx2g"
```

```bash
# 改完逐台重启（★一次只重启一台，等它回到 green 再动下一台）
cd /home/lhim/os && bash os.sh restart && bash os.sh status
```
```bash
# 验收：3 行节点、1 个 master、副本已分配、green
curl -sS -u "$AUTH" "$OS/_cat/nodes?v"
curl -sS -u "$AUTH" "$OS/_cluster/health?pretty"    # 期望 status=green, number_of_nodes=3, unassigned_shards=0
curl -sS -u "$AUTH" "$OS/_cat/indices?v"            # 期望 pm-ai-chunks 的 rep=1 且 health=green
```

> **单节点 → 3 节点不需要重建索引**：把原来的 `data/` 目录拷到第 1 台（或从快照恢复），
> 再把副本数从 0 改成 1 即可（`PUT /pm-ai-chunks/_settings {"index":{"number_of_replicas":1}}`）。
> ⚠️ **`cluster.initial_master_nodes` 与已有数据目录冲突**：从单节点（数据目录非空）转集群时，
> 该参数**只在"引导一个全新集群"时用**；已有数据时正确做法是**只在其中一台保留数据**并以集群模式启动它，
> 其余两台以空数据目录加入（**这一步最容易出错，届时务必按官方"从单节点迁移到多节点集群"的步骤做并先在 `.111` 上演练**）。

## 10. 附录 B · 与既有部署规范的对齐（自查表）

| 规范（来源） | 本方案做法 | 一致 |
| --- | --- | --- |
| 服务器无外网：只 `docker load`，**绝不** `--build` | 编排**无 `build:` 段** + `pull_policy: never`；镜像由开发机 `docker save` 后 scp | ✅ |
| 工程名 = 目录名 | 文件放 `/home/lhim/os/`，脚本**不传 `-p`** ⇒ 工程名 `os` | ✅ |
| `.env` 与 compose **同目录** | `/home/lhim/os/{docker-compose.yml,.env}` | ✅ |
| 唯一入口、中文提示、幂等、失败给下一步命令 | [`os.sh`](../deploy/opensearch/os.sh)：`start\|stop\|restart\|status\|logs\|load\|help` | ✅ |
| 容器名沿用仓库习惯（单数） | `pm-opensearch`（与 `pm-backend`/`pm-frontend`/`pm-ai-backend` 同一命名风格） | ✅ |
| 日志限体积 | `logging: json-file, max-size 50m, max-file 5` | ✅ |
| 密钥不入仓库/不进镜像 | `OPENSEARCH_PASSWORD` 只在服务器 `.env`（`chmod 600`）；仓库里只有 `.env.example` 占位 | ✅ |
| 实例资产与文档逐字一致 | `docs/OpenSearch部署与接入.md` §3 ↔ [`deploy/opensearch/docker-compose.yml`](../deploy/opensearch/docker-compose.yml)、[`.env.example`](../deploy/opensearch/.env.example) | ✅ |

---

## 11. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| docs-1.6 | 2026-09-23 | 首版：为两台新服务器（`10.254.212.110` / `.111`）出 OpenSearch 部署与接入文档 + 部署资产（`deploy/opensearch/{docker-compose.yml,.env.example,os.sh}`）。**定稿单节点**（`.110`），第二台为**冷备 + 演练机**；含 2 节点 quorum 明账（为何不推荐）、3 节点升级路径（附录 A）、容量与 mapping 设计（4096 维 / cosinesimil / 1 分片 0 副本）、AI 侧接入与切换/回滚、快照与恢复演练、排障表与未确认项（14 条）。**无源码变更** |
