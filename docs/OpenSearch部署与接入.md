# OpenSearch 部署与接入（本机 pdmsosgh / 10.254.212.110 · OpenSearch 3.1.0 + Dashboards 3.1.0 · tar.gz + systemd）

> **文档性质：操作手册（SOP），风格与 [`部署与发布全流程手册.md`](部署与发布全流程手册.md) §0 一致** ——
> **§0~§10 是一条路走到底**：每步都标了状态（**（当前状态：已完成）** / **（待执行）** / **（将来执行）**），
> 命令可直接复制；正文里**没有分支选项**。要"另一个做法/将来加固"，看 **附录 B（变更项）**。
>
> **本机事实（照此写，不需要你再判断）**：`pdmsosgh`（`10.254.212.110`），**x86_64**，**无 Docker**；
> 两个安装包已在 `/home/opensearch/`，`app/` 已解压完成；`config/`、`data/`、`logs/`、`backup/` 已建，
> `dashboards/` 尚未创建；登录账号 `lhim`（已在 `opensearch` 组），服务账号 `opensearch`（已建、`passwd -l`）。
> **权限采用共享模型**（`lhim` 可直接往 `/home/opensearch` 任意子目录写，见 §2.1）。
>
> **与之配套的资产**：[`deploy/opensearch/`](../deploy/opensearch/)（6 个文件：`opensearch.yml.example`、
> `jvm.options.d/heap.options`、`opensearch.service`、`snapshot-backup.sh`、`opensearch_dashboards.yml.example`、
> `opensearch-dashboards.service`；落位见 §2.2）。
>
> **关联**：[`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md)（"换 OpenSearch 的三条量化触发点"）、
> [`部署与发布全流程手册.md`](部署与发布全流程手册.md) §9（AI 能力服务 ai-backend）、[`deploy/README.md`](../deploy/README.md)。
>
> **官方依据**（本文件反复引用，均已逐条核对；核对结果见 **附录 C**）：
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
| 第二台 `10.254.212.111` | **冷备 + 演练机**：同一套安装与同样的 unit，**服务默认停着**；只接收每晚快照副本（§9.4 / §8.2） |
| 安装方式 | 官方 **`opensearch-<版本>-linux-x64.tar.gz`** 解包到部署根的 `app/`（**这两台是 x86_64**） |
| 部署根 | **`/home/opensearch/`**（程序 / 配置 / 数据 / 日志 / 备份**分离**，见下方结构） |
| 托管方式 | **systemd**（unit 见 [`opensearch.service`](../deploy/opensearch/opensearch.service)），专用非 root 用户 `opensearch` |
| 索引 | `pm-ai-chunks`，**主分片 1 + 副本 0**（见 §6.1） |
| 端口 | `9200`（REST）**只放行两台应用服务器**；`9300`（transport）本方案单节点不需对外；**9200 绝不对外** |
| 监听 | `network.host: <本机内网IP>`（不用 `0.0.0.0`） |
| 安全 | **内网 HTTP + basic auth（强口令）**：显式关掉 REST 层 TLS（§4.4）；transport 层 TLS 保持开启 |
| 数据/日志/快照 | `/home/opensearch/{data,logs,backup}`（**在程序目录 `app/` 之外**，升级换 `app/` 不碰数据） |
| 部署根 | **`/home/opensearch/`** = **专用账号 `opensearch` 自己的家目录**（`useradd -m -d /home/opensearch …` + `passwd -l` 锁登录），见 §2.4 |
| Dashboards | **OpenSearch Dashboards 3.1.0 与 OpenSearch 同机**（`10.254.212.110:5601`，单独 systemd 服务），见 **§7** |

**部署根目录结构（两台机器一致；安装当天建好，之后升级只动 `app/`）**

```text
/home/opensearch/                     ← 专用账号 opensearch 的家目录（= 部署根）
├─ app/                 ← 当前程序（tarball 解包出来的内容：bin/ config/ jdk/ lib/ modules/ plugins/ …）
├─ app-<旧版本>/         ← 升级时保留的旧目录（回滚点；确认新版稳定后可删）
├─ config/              ← 我们维护的配置（升级时不用重配）
│  ├─ opensearch.yml            （= deploy/opensearch/opensearch.yml.example 的落地副本）
│  ├─ jvm.options.d/heap.options（= deploy/opensearch/jvm.options.d/heap.options）
│  ├─ snapshot-backup.sh        （= deploy/opensearch/snapshot-backup.sh）
│  └─ snapshot-backup.env       （脚本的覆盖配置；权限 600）
├─ data/                ← path.data（索引数据：**唯一的有状态目录**）
├─ logs/                ← path.logs（文件日志，另有 journald）
├─ backup/fs-repo/      ← path.repo（快照仓库；snapshot-backup.sh 往这里写，再 rsync 到 .111）
└─ dashboards/          ← OpenSearch Dashboards（见 §7）
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
| **单点故障** | OpenSearch 挂 → kNN 检索不可用（**AI 问答的检索环节受影响**） | ① **快照/备份是强制项**（[`snapshot-backup.sh`](../deploy/opensearch/snapshot-backup.sh) + cron，§8.2）；② **AI 侧降级口径定死**（§9.3：**明确报错，不许静默返回无关结果**）；③ systemd `Restart=on-failure` + `enable`（开机自启） |
| 无副本 | 数据盘损坏即丢索引 | 每晚快照 + `rsync` 到 `.111`（§8.2 / §9.4），**RTO 分钟级** |
| 无滚动升级 | 升级要停机（分钟级） | 升级步骤见 §8.3（停服 → 解包新版本 → 复制 `config/` → 启动；旧目录保留即回滚） |

> **重要边界**：OpenSearch 里存的是**可重建物**（切片文本 + 向量 + 索引），与既有运维口径一致——
> **业务数据仍在主系统崖山库**（V1~V14，16 张表）。索引即使全丢，也能从主系统附件重新解析重建（代价是时间，见 §9.2）。

### 0.5 两台新机器与既有服务器的关系（别搞混）

| 机器 | 角色 | 说明 |
| --- | --- | --- |
| `10.254.212.110` | **OpenSearch 单节点（推荐）** | 本文 §1~§8 的主体（x86_64） |
| `10.254.212.111` | **冷备 + 演练机** | 同样的安装与 unit，服务默认停着；存快照副本（§9.4） |
| 既有 `pdmsappgh` / `ai-kingbase-gh` | 主系统 / AI 能力服务 | **不在这两台新机器上**；本文只改它们的 `.env`（§9.1） |
| `10.254.208.35:8090` | 平台网关（对话/OCR/向量化/重排） | 与本文无关，仅在重建索引时要它可用 |

---


## 1. 安装包与当前状态（**已完成，核对即可**）　（当前状态：已完成）

> **本节的每一行都是"当前状态"，不是"建议"。** 装包这一步已经做完了，你只需要核对。
> **本机**：`pdmsosgh`（`10.254.212.110`），**x86_64**，**无 Docker**。全部操作以 `lhim` 登录（它已在 `opensearch` 组）。

### 1.1 版本（**已定：3.1.0**）　（当前状态：已完成）

| 项 | 值 | 状态 |
| --- | --- | --- |
| OpenSearch 包 | `/home/opensearch/opensearch-3.1.0-linux-x64.tar.gz`（**1,000,330,285 字节**） | （当前状态：已在位） |
| Dashboards 包 | `/home/opensearch/opensearch-dashboards-3.1.0-linux-x64.tar.gz`（**385,456,866 字节**） | （当前状态：已在位） |
| OpenSearch 版本 | **3.1.0**（x86_64） | （当前状态：已解压到 `app/`） |
| Dashboards 版本 | **3.1.0**（与 OpenSearch 同版本） | （待执行） |

### 1.2 目录现状（**哪些已有、哪些还没有**）　（当前状态）

```bash
ls -ld /home/opensearch /home/opensearch/app /home/opensearch/config \
       /home/opensearch/data /home/opensearch/logs /home/opensearch/backup
ls -d  /home/opensearch/dashboards 2>/dev/null || echo "dashboards/ 尚未创建 —— 正常，见 §7"
```

| 路径 | 当前状态 |
| --- | --- |
| `/home/opensearch/`（部署根 = `opensearch` 账号的家目录） | （当前状态：已存在） |
| `/home/opensearch/app/` | （当前状态：**已解压完成**，里面是 3.1.0 的程序） |
| `/home/opensearch/config/`、`data/`、`logs/`、`backup/` | （当前状态：已建） |
| `/home/opensearch/dashboards/` | （待执行：§7 才创建） |
| 两个 `.tar.gz` | （当前状态：在部署根下，共 ≈1.32 GiB；留着或删除都不影响运行） |

> **解压是谁做的都无所谓，但 `app/` 的属主必须是 `opensearch`** —— §2 的统一命令会把属主收口，
> 所以**现在不用管**，照 §2 跑一遍即可。

### 1.3 账号现状（**已建**）　（当前状态：已完成）

| 账号 | 用途 | 当前状态 |
| --- | --- | --- |
| `lhim` | 运维登录（执行本文所有命令） | （当前状态：已存在，已在 `opensearch` 组） |
| `opensearch` | 服务账号（OpenSearch / Dashboards 都以它运行，已锁口令） | （当前状态：已建、`passwd -l`） |

```bash
id lhim | grep -o 'opensearch' && echo "lhim 在 opensearch 组 ✅"
getent passwd opensearch | cut -d: -f1,6      # 期望：opensearch:/home/opensearch
sudo passwd -S opensearch | awk '{print "口令状态:", $2}'   # 期望：L（locked）
```


## 2. 权限模型与资产落位（**照抄即可**）　（待执行）

> 本机采用**权限共享**：让 `lhim` 能用终端工具直接把文件传进 `/home/opensearch` 的**任意子目录**，
> 也让服务账号 `opensearch` 正常读写。**下面 5 条命令就是全部权限设置。**
> 代价（如实写在这里，不再重复）：服务目录对 `lhim` 可写 ⇒ 一次手滑（尤其批量删除）可以直接毁掉数据；
> 若将来要收紧，见 **附录 B**（不属于本次部署步骤）。

### 2.1 一次性设置权限（**待执行；5 条命令**）

> ⚠️ **范围只限"运行期目录"：`config` / `data` / `logs` / `backup`。**
> **绝对不要**对 `/home/opensearch` 整个递归，更**不要对 `app/`（程序目录）或 `dashboards/app/` 套用 `chmod 664`** ——
> 那会**抹掉程序与自带 JDK 的执行位**（`app/bin/opensearch`、`app/jdk/bin/*`、`app/jdk/lib/jspawnhelper`、`app/plugins/**/tools/*.sh`），
> 结果是 **systemd 报 `status=203/EXEC` 且应用日志为空**（应用根本没被启动）。
> 本项目**已实际踩过这个坑**（详见 §8.5 与 §4.5 的恢复命令）。`app/` 的属主只需 `chown`（§2.3 ④），**权限保持解压原样**。

```bash
# ① lhim 属于 opensearch 组（当前状态：已满足；本条幂等）
sudo usermod -aG opensearch lhim

# ② 目录：组可写 + setgid（新文件自动继承 opensearch 组）
#    ★范围：只列运行期目录；app/ 与 dashboards/ 【不在其中】
sudo find /home/opensearch/config /home/opensearch/data /home/opensearch/logs /home/opensearch/backup \
     -type d -exec chmod 2775 {} +

# ③ 文件：组可读写（终端工具覆盖已有文件需要"文件本身可写"）
#    ★同样只列运行期目录
sudo find /home/opensearch/config /home/opensearch/data /home/opensearch/logs /home/opensearch/backup \
     -type f -exec chmod 664 {} +

# ④ 私钥例外：不给组写（安全插件对私钥最敏感）
sudo find /home/opensearch/config -name '*.pem' -exec chmod 640 {} +

# ⑤ 之后 lhim 新建的文件默认就是 664（组可写）
echo 'umask 002' | sudo tee /etc/profile.d/lhim-umask.sh >/dev/null
```

> **程序目录 `app/` 的正确权限（不要动，只核对）**：解压出来的可执行位就是对的。若发现异常按下表核对：
>
> ```bash
> ls -l /home/opensearch/app/bin/opensearch          # 期望 -rwxr-x---
> ls -l /home/opensearch/app/jdk/bin/java            # 期望 -rwxr-x---
> ls -l /home/opensearch/app/jdk/lib/jspawnhelper    # 期望 -rwxr-x---
> ls -l /home/opensearch/app/plugins/opensearch-security/tools/*.sh   # 期望 -rwxr-x---
> ```
> 任何一条**不带 `x`** ⇒ 按 **§4.5** 的三条命令恢复后重启。
> **自查小技巧**：跑完 §2.1 后执行 `find /home/opensearch/app -maxdepth 2 -name opensearch -o -maxdepth 2 -name java | xargs ls -l`，
> 只要 `bin/opensearch` 与 `jdk/bin/java` 仍是 `-rwxr-x---`，就说明你的 `find` 范围没有波及 `app/`。

```bash
# ★执行完必须【断开终端工具并重连】（组身份要新会话才生效），重连后验证：
groups | grep opensearch          # 期望：看到 opensearch
ls -ld /home/opensearch           # 期望：drwxrwsr-x（2775）
umask                             # 期望：0002
```

> **为什么"目录开写"不够**：往目录里**新建/拖入**文件只看目录有没有 `w`；
> 而**覆盖已有文件**还要看那个文件有没有 `w`（终端工具做的是 open + truncate）。
> 所以 ②③ 两条必须一起做。这两句话就是你需要的全部权限概念。
>
> **与本架构的衔接**：§2.2 落位的那 6 个文件都是**新建**（不是覆盖），所以只依赖目录的 `w`；
> 落位后再由 §2.3 把它们收成 **配置 `640`**。
> **将来要"覆盖"一个已存在的配置文件时**：在终端工具里**先删除、再上传**（目录组可写 ⇒ 你有权删；
> 删除只需要目录的 `w`，不需要文件本身的 `w`），然后重跑 §2.3 第 ① 步把它收回 `640`。
> 更省事的做法见 **附录 B.2「以服务账号干活」**。

### 2.2 资产落位（表一：仓库 `deploy/opensearch/` 下的 6 个文件）　（待执行）

**做法**：用终端工具把仓库里的 `deploy/opensearch/` 下这 6 个文件**上传到下表的目标路径**（父目录不存在就先建），
然后**一条命令统一定属主与权限**（§2.3）。

| 源（仓库 `deploy/opensearch/`） | 上传到（目标路径） | 属主:组 | 权限 | 状态 |
| --- | --- | --- | --- | --- |
| `opensearch.yml.example` | `/home/opensearch/config/opensearch.yml` | `opensearch:opensearch` | `640` | （待执行） |
| `jvm.options.d/heap.options` | `/home/opensearch/config/jvm.options.d/heap.options` | `opensearch:opensearch` | `640` | （待执行） |
| `snapshot-backup.sh` | `/home/opensearch/config/snapshot-backup.sh` | `opensearch:opensearch` | `750` | （待执行） |
| `opensearch_dashboards.yml.example` | `/home/opensearch/dashboards/app/config/opensearch_dashboards.yml` | `opensearch:opensearch` | `640` | （待执行：先做完 §7.1 解压，该目录才会存在） |
| `opensearch.service` | `/etc/systemd/system/opensearch.service` | **`root:root`** | **`644`** | （待执行） |
| `opensearch-dashboards.service` | `/etc/systemd/system/opensearch-dashboards.service` | **`root:root`** | **`644`** | （待执行） |

**之后再拷一份进程序目录**（`app/config/` 才是运行时真正生效的那份）：

| 目标路径 | 属主:组 | 权限 | 状态 |
| --- | --- | --- | --- |
| `/home/opensearch/app/config/opensearch.yml` | `opensearch:opensearch` | `640` | （待执行） |
| `/home/opensearch/app/config/jvm.options.d/heap.options` | `opensearch:opensearch` | `640` | （待执行） |

> **改配置永远改 `config/` 那份**（源头），再按 §2.3 第 ③ 步拷进 `app/config/`，然后重启服务。
> **不要直接改 `app/config/`** —— 升级换 `app/` 时会被换掉。

### 2.3 统一定属主与权限（**待执行；一条命令组**）

```bash
# ① 仓库资产落位后，统一收口（若目录还没建，先建）
sudo mkdir -p /home/opensearch/config/jvm.options.d
sudo chown -R opensearch:opensearch /home/opensearch/config
sudo chmod 750 /home/opensearch/config
sudo chmod 640 /home/opensearch/config/opensearch.yml \
               /home/opensearch/config/jvm.options.d/heap.options
sudo chmod 750 /home/opensearch/config/snapshot-backup.sh

# ② 两个 systemd unit：必须 root:root 644（systemd 的硬要求）
sudo chown root:root /etc/systemd/system/opensearch.service /etc/systemd/system/opensearch-dashboards.service
sudo chmod 644       /etc/systemd/system/opensearch.service /etc/systemd/system/opensearch-dashboards.service

# ③ 把配置拷进程序目录（运行时生效的那份）
sudo -u opensearch cp /home/opensearch/config/opensearch.yml /home/opensearch/app/config/opensearch.yml
sudo -u opensearch mkdir -p /home/opensearch/app/config/jvm.options.d
sudo -u opensearch cp /home/opensearch/config/jvm.options.d/heap.options \
                      /home/opensearch/app/config/jvm.options.d/heap.options
sudo chown opensearch:opensearch /home/opensearch/app/config/opensearch.yml \
                                  /home/opensearch/app/config/jvm.options.d/heap.options
sudo chmod 640 /home/opensearch/app/config/opensearch.yml \
               /home/opensearch/app/config/jvm.options.d/heap.options
```

```bash
# ④ 统一把程序目录归服务账号（含 app/、dashboards/；§2.1 已开共享，这一步只修属主）
sudo chown -R opensearch:opensearch /home/opensearch/app
sudo chown -R opensearch:opensearch /home/opensearch/dashboards 2>/dev/null || true
sudo chown -R opensearch:opensearch /home/opensearch/data /home/opensearch/logs /home/opensearch/backup
```

### 2.4 落位表二：运行期要管的其它落位点（**属主/权限速查**）　（对照表）

| 目标 | 用途 | 属主:组 | 权限 |
| --- | --- | --- | --- |
| `/home/opensearch/data` | `path.data`（索引数据：唯一的有状态目录） | `opensearch:opensearch` | `2775`（§2.1 ② 已含；**收紧见附录 B.1）** |
| `/home/opensearch/logs` 与 `logs/*.log` | `path.logs`（文件日志） | `opensearch:opensearch` | 目录 `2775`／文件 `664`（`umask 002` 保证新文件如此） |
| `/home/opensearch/backup` 与 `backup/fs-repo` | `path.repo`（快照仓库） | `opensearch:opensearch` | 目录 `2775` |
| `/home/opensearch/app/config/opensearch.keystore` | 安全插件密钥库（**首次安装由 demo 脚本生成**） | `opensearch:opensearch` | **`600`** |
| `/home/opensearch/app/config/opensearch-security/` 及其中 `*.yml` | 安全配置（内部用户/角色/角色映射） | `opensearch:opensearch` | 目录 `2775`／`*.yml` `640` |
| `/home/opensearch/app/plugins/opensearch-security/tools/*.sh` | `hash.sh` / `securityadmin.sh` | `opensearch:opensearch` | **`750`（必须有 `x`）** |
| `/etc/opensearch-backup.pass` | 快照脚本的口令（一行） | **`root:root`** | **`600`** |
| `/home/opensearch/backup/dist/`（**仅 `.111` 用**） | 归档安装包（离线重建用） | `lhim:opensearch` | `2775` |
| `/etc/profile.d/lhim-umask.sh` | 给 `lhim` 设 `umask 002` | **`root:root`** | **`644`** |

> `keystore` 与 `opensearch-security/` 在 §6 跑完官方安装脚本后才出现；出现后按上表执行一次：
> ```bash
> sudo chmod 600 /home/opensearch/app/config/opensearch.keystore 2>/dev/null || true
> sudo chmod 750 /home/opensearch/app/plugins/opensearch-security/tools/*.sh 2>/dev/null || true
> ```

### 2.5 落位核对（**待执行；8 条命令，逐条看输出**）

```bash
# ① 我们维护的配置
ls -l /home/opensearch/config/                 # opensearch.yml(640) / jvm.options.d / snapshot-backup.sh(750)
# ② 运行时生效的配置
ls -l /home/opensearch/app/config/opensearch.yml
# ★程序可执行位（缺 x 会导致 status=203/EXEC，见 §4.5）
ls -l /home/opensearch/app/bin/opensearch /home/opensearch/app/jdk/bin/java
# ③ 两个 unit
ls -l /etc/systemd/system/opensearch.service /etc/systemd/system/opensearch-dashboards.service   # root root 644
sudo systemd-analyze verify /etc/systemd/system/opensearch.service
sudo systemd-analyze verify /etc/systemd/system/opensearch-dashboards.service                    # 期望无输出
# ④ 安全插件工具必须带 x
ls -l /home/opensearch/app/plugins/opensearch-security/tools/*.sh                               # 期望 -rwxr-x---
# ⑤ 快照仓库目录
ls -ld /home/opensearch/backup /home/opensearch/backup/fs-repo
# ⑥ 口令文件（§8.2 会建）与 keystore
ls -l /etc/opensearch-backup.pass 2>/dev/null || echo "(还没建：见 §8.2 第 ① 条)"
ls -l /home/opensearch/app/config/opensearch.keystore 2>/dev/null || echo "(还没生成：见 §4.2)"
# ⑦ 以服务账号身份确认它读得到程序与配置
sudo -u opensearch ls /home/opensearch/app/bin/opensearch
sudo -u opensearch cat /home/opensearch/app/config/jvm.options.d/heap.options
```


## 3. 宿主准备（**待执行；按顺序做完再进 §6**）

> 全部以 `lhim` 执行，需要 root 的地方已标 `sudo`。**做完 §3.7 的自检再往下**。

### 3.1 内核参数 `vm.max_map_count`（**漏了必失败**）　（待执行）

OpenSearch 底层 Lucene 用 `mmap` 打开索引文件，默认 `65530` 不够
（会报 `max virtual memory areas vm.max_map_count [65530] is too low`）。

```bash
# ① 立即生效
sudo sysctl -w vm.max_map_count=262144

# ② 持久化（官方示例是改 /etc/sysctl.conf；这里写成独立文件，避免与别人的行冲突）
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-opensearch.conf

# ③ 读回校验
sysctl vm.max_map_count
cat /proc/sys/vm/max_map_count          # 两者都期望 262144
```
```bash
# ④ 重启后必须再核一次（不重启不算验证过持久化）
sudo reboot
# —— 重新登录后 ——
cat /proc/sys/vm/max_map_count          # 期望 262144
```

### 3.2 文件描述符与内存锁定上限　（待执行）

```bash
ulimit -n                # 当前值，期望 ≥ 65536
```
```bash
sudo tee /etc/security/limits.d/99-opensearch.conf >/dev/null <<'EOF'
lhim        soft  nofile  65536
lhim        hard  nofile  65536
opensearch  soft  nofile  65536
opensearch  hard  nofile  65536
opensearch  soft  memlock unlimited
opensearch  hard  memlock unlimited
EOF
# 重登后复核： ulimit -n  → 65536
```

### 3.3 swap 与内存锁定　（待执行）

```bash
free -h ; swapon --show          # 看现状
sudo swapoff -a                  # 关掉 swap（官方 Tar 页 Step 2 的第一条）
sudo cp /etc/fstab /etc/fstab.bak.$(date +%F)
sudo sed -i.bak '/[[:space:]]swap[[:space:]]/s/^/#/' /etc/fstab    # 重启后也不再启用
```
```bash
# 起来之后验证"内存锁定真的生效"（§9.2 再做）
curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/_nodes?filter_path=nodes.*.process.mlockall'
# 期望：{"nodes":{"<node-id>":{"process":{"mlockall":true}}}}
```

### 3.4 `/home` 挂载检查　（待执行）

部署根在 `/home` 下，所以先确认它是**本地盘**且**空间够**。

```bash
df -h /home
findmnt -no SOURCE,FSTYPE,OPTIONS /home
# 期望：SOURCE 是本地块设备（/dev/sdX、/dev/vdX、/dev/mapper/…），FSTYPE 是 xfs 或 ext4
#   且 OPTIONS 里【没有 noexec】（nosuid / nodev 无害，不用管）
```
```bash
# 空间不足时的既定退路：只把 path.data 挪到数据盘，其余仍在 /home/opensearch
sudo mkdir -p /data/opensearch
sudo chown opensearch:opensearch /data/opensearch
sudo chmod 2775 /data/opensearch
# 然后改 /home/opensearch/config/opensearch.yml 里的 path.data 为 /data/opensearch，
# 按 §2.3 ③ 拷进 app/config/，§9 重启后复核：
#   curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/_nodes?filter_path=nodes.*.settings.path.data&pretty'
```

### 3.5 防火墙（**firewalld；9200 绝不对外**）　（待执行）

> **顺序原则：先收暴露面，再起服务** —— 这一步在 §5 启动服务之前完成，所以"服务一上线就只在白名单里可见"。

| 端口 | 放行来源 | 用途 |
| --- | --- | --- |
| `9200/tcp` | **应用/AI 服务器**（逐个写死 IP） | REST（kNN 检索、`_bulk`、快照管理） |
| `5601/tcp` | **运维网段 / 跳板机** | Dashboards（给人看的 UI） |
| `9300/tcp` | **不放行**（单节点不需要对外） | 节点间 transport |
| `9600/tcp` | **不放行** | Performance Analyzer |

```bash
# ① 确认 firewalld 在跑
sudo systemctl enable --now firewalld
sudo firewall-cmd --state                    # 期望 running

# ② 把需要放行的来源逐个写成富规则（★把 <…> 换成真实 IP/网段，不要写 0.0.0.0/0）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<应用/AI 服务器 IP>" port port="9200" protocol="tcp" accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<运维网段或跳板机 IP>" port port="5601" protocol="tcp" accept'
sudo firewall-cmd --reload
sudo firewall-cmd --list-rich-rules          # 复核

# ③ 关掉默认 zone 里可能已有的 9200/5601 全局开放（有则删，没有会提示 not present）
sudo firewall-cmd --permanent --remove-port=9200/tcp 2>/dev/null || true
sudo firewall-cmd --permanent --remove-port=5601/tcp 2>/dev/null || true
sudo firewall-cmd --reload

# 若本机确实没有 firewalld（只有 iptables），等价命令（仅作等价说明，不需要执行）：
#   sudo iptables -I INPUT -p tcp -s <应用/AI 服务器 IP> -m tcp --dport 9200 -j ACCEPT
#   sudo iptables -I INPUT -p tcp -s <运维网段> -m tcp --dport 5601 -j ACCEPT
#   sudo iptables -A INPUT -p tcp --dport 9200 -j DROP
#   sudo iptables -A INPUT -p tcp --dport 5601 -j DROP
#   （持久化按平台方口径做；本机既定用 firewalld。）
```

```bash
# ④ 自检：从【不在白名单】的机器验证它确实不通（期望超时/拒绝，绝不能是 200）
curl -m 3 -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:9200/ ; echo "exit=$?"
```

### 3.6 时间同步　（待执行）

```bash
timedatectl                      # 期望 System clock synchronized: yes
sudo systemctl enable --now chronyd
sudo timedatectl set-timezone Asia/Shanghai
date
# 内网 NTP 源地址由平台方给（见 §10）
```

### 3.7 从应用/AI 服务器侧确认可达　（待执行）

> 前提：**§3.5 的防火墙已配好**（先收暴露面、再起服务）。

```bash
# 在【应用/AI 服务器】上执行（不是在本机）
curl -sS -m 5 -o /dev/null -w '%{http_code}\n' http://10.254.212.110:9200/
# 期望：401（说明网络通了、且要求认证 —— 这正是我们要的）
```

### 3.8 宿主准备自检　（待执行）

```bash
cat /proc/sys/vm/max_map_count        # 262144
ulimit -n                             # ≥65536（重登后 65536）
swapon --show                         # 无输出
findmnt -no OPTIONS /home             # 无 noexec
sudo firewall-cmd --state             # running
sudo firewall-cmd --list-rich-rules   # 只有 9200/5601 两条白名单
sudo ss -lntp | grep -E ':(9200|9300|9600|5601)'   # 此时应【没有任何监听】（服务还没起）
timedatectl | head -3                 # synchronized: yes
uname -m                              # x86_64
```


## 4. 安装　（待执行）

### 4.1 程序目录现状　（当前状态：已完成）

`app/` 已经解压好（§1.3）——**本节不用再解压**。确认一下即可：

```bash
ls /home/opensearch/app | head            # 期望：bin config jdk lib modules plugins 等
cat /home/opensearch/app/VERSION 2>/dev/null || true
```

### 4.2 跑官方安装脚本（**生成证书与初始管理员口令**）　（待执行）

官方脚本 `opensearch-tar-install.sh` 的作用：**定义环境变量、应用自签 TLS 证书，并把安全设置写进
`config/opensearch.yml`**；本节点由此获得可用的安全配置（证书 + admin 用户）。官方对它的定位是
"**不适合生产环境的通用配置**"——我们只用它的证书与用户，随后用 §6.3 的配置覆盖它。

```bash
# ① 程序目录归服务账号（§2.3 ④ 已做，这里再确认）
sudo chown -R opensearch:opensearch /home/opensearch/app

# ② 设初始管理员口令（★必须满足官方四条要求）
export OPENSEARCH_INITIAL_ADMIN_PASSWORD='<强口令>'
#   官方 demo-configuration 页的口令要求（照抄）：
#     ① 长度 8~100 字符；
#     ② 至少各一个【大写字母、小写字母、数字、特殊字符】；
#     ③ 必须通过 zxcvbn 强度估计并达到 strong 及以上（常见词、日期、1234/qwerty
#        这类序列、以及 3 替 E 这种可预测替换都会拉低评级，即使四类字符齐全也会被拒）；
#     ④ 不得与用户名 admin 相似。
#   生成示例： openssl rand -base64 24

# ③ 以服务账号身份在程序目录里跑脚本
sudo -u opensearch -H bash -lc "cd /home/opensearch/app && OPENSEARCH_INITIAL_ADMIN_PASSWORD='$OPENSEARCH_INITIAL_ADMIN_PASSWORD' ./opensearch-tar-install.sh"
```

```bash
# ④ 确认证书与 keystore 已生成（文件名以实际为准）
sudo -u opensearch ls -l /home/opensearch/app/config/ | grep -E '\.pem$|keystore'
# ⑤ 按 §2.4 的两个例外收口权限
sudo chmod 600 /home/opensearch/app/config/opensearch.keystore 2>/dev/null || true
sudo chmod 750 /home/opensearch/app/plugins/opensearch-security/tools/*.sh 2>/dev/null || true
```

### 4.3 用我们的配置接上它　（待执行）

```bash
# ① 备份 demo 脚本刚写的那份（出问题能对照）
sudo -u opensearch cp /home/opensearch/app/config/opensearch.yml \
                      /home/opensearch/app/config/opensearch.yml.demo.bak
# ② 看它写了什么（我们的配置要保住 transport 那几行）
sudo -u opensearch grep -nE 'plugins\.security|discovery|network\.host|path\.' \
                      /home/opensearch/app/config/opensearch.yml || true

# ③ 改【源头】那份的这两处（§2.2 已把 opensearch.yml 放到 config/）
sudo -u opensearch vi /home/opensearch/config/opensearch.yml
#   ★必改：network.host: 10.254.212.110
#   该文件里已经写好：
#     cluster.name: pm-os / node.name: os01 / discovery.type: single-node
#     http.port: 9200 / data / logs / repo 三个路径
#     plugins.security.disabled: false
#     plugins.security.ssl.http.enabled: false        ← REST 层走 HTTP（本机既定）
#     transport 证书三行（esnode.pem / esnode-key.pem / root-ca.pem）
#     allow_unsafe_democertificates / allow_default_init_securityindex

# ④ 拷进程序目录（运行时生效的那份），权限按 §2.3 ③
sudo -u opensearch cp /home/opensearch/config/opensearch.yml /home/opensearch/app/config/opensearch.yml
sudo chown opensearch:opensearch /home/opensearch/app/config/opensearch.yml
sudo chmod 640 /home/opensearch/app/config/opensearch.yml

# ⑤ 核对生效值
sudo -u opensearch grep -nE '^(cluster\.name|node\.name|discovery\.type|network\.host|http\.port|path\.(data|logs|repo)|plugins\.security)' \
     /home/opensearch/app/config/opensearch.yml
sudo -u opensearch cat /home/opensearch/app/config/jvm.options.d/heap.options   # 期望 -Xms8g / -Xmx8g
```

### 4.4 安全模式（HTTP + basic auth）

- `plugins.security.ssl.http.enabled: false` —— 9200 走明文 HTTP（§2.2 的 `config/opensearch.yml` 里已写好）；
- **basic auth 仍然生效**（安全插件照旧要求 `admin` + §4.2 设的那个强口令）；
- **transport 层 TLS 保持开启**（节点间通信加密，证书就是 §4.2 生成的那套）；
- 补偿措施：§3.5 的防火墙只放行应用/AI 服务器。
- **不要删掉 `config/opensearch.yml` 里的 transport 证书三行** —— 删了节点会因缺证书起不来。
- 若要改走自签 TLS（等保要求），见 **附录 B.5**（不属于本次部署步骤）。

### 4.5 程序目录可执行位恢复（**若你误跑过全量 `chmod 664`，先做这一步**）

> **什么时候需要**：`systemctl start opensearch` 失败、`journalctl -u opensearch` 报
> `Control process exited, code=exited, status=203/EXEC`，且 `logs/` 里**什么都没有**。
> 原因：**`app/` 下文件被抹掉了执行位**（例如对 `/home/opensearch` 整个跑过 `chmod 664`）。
> `203/EXEC` 属于 systemd 的 **exec 阶段**失败 —— **应用根本没被启动**，所以应用日志必然是空的。

```bash
# ① 先把执行位还给"程序 + 自带 JDK"
sudo find /home/opensearch/app/bin /home/opensearch/app/jdk/bin -type f -exec chmod 750 {} +
sudo chmod 750 /home/opensearch/app/jdk/lib/jspawnhelper

# ② 插件里的脚本（hash.sh / securityadmin.sh 等）也要有 x
sudo find /home/opensearch/app/plugins -name '*.sh' -exec chmod 750 {} +

# ③ 重启（unit 本身没变，但 daemon-reload 后重试更稳）
sudo systemctl daemon-reload && sudo systemctl restart opensearch

# ④ 复核（四条都必须带 x）
ls -l /home/opensearch/app/bin/opensearch          # 期望 -rwxr-x---
ls -l /home/opensearch/app/jdk/bin/java            # 期望 -rwxr-x---
ls -l /home/opensearch/app/jdk/lib/jspawnhelper    # 期望 -rwxr-x---
ls -l /home/opensearch/app/plugins/opensearch-security/tools/*.sh   # 期望 -rwxr-x---
sudo systemctl status opensearch --no-pager | head -12
```

> **更稳的替代做法（拿不准该给哪些文件加 `x` 时）**：直接**从 tar 包重新解压一份程序目录**，
> 让可执行位回到官方原始状态（属主用 `chown -R opensearch:opensearch` 归位；只覆盖 `app/`，不碰 `config/data/logs/backup`）：
> ```bash
> cd /tmp && tar -xvf /home/opensearch/opensearch-3.1.0-linux-x64.tar.gz     # 若包已删，先按附录 C 第 16 条取回
> sudo rsync -a --delete /tmp/opensearch-3.1.0/ /home/opensearch/app/        # 仅同步程序目录
> sudo chown -R opensearch:opensearch /home/opensearch/app
> sudo systemctl daemon-reload && sudo systemctl restart opensearch
> ```
> ⚠️ 这条会把 `app/config/` 恢复成官方模板 —— **做完必须按 §4.3 把我们那份配置再拷回去**。

## 5. systemd 启动与验收　（待执行）

### 5.1 装 unit 并启动

```bash
# ① 两个 unit 已在 §2.2/§2.3 落位（root:root 644），刷新 systemd 并启动
sudo systemctl daemon-reload
sudo systemctl enable --now opensearch
sudo systemctl status opensearch --no-pager          # 期望 active (running)
```

> 首次启动**约 30~90 秒**（JVM + 插件加载）。unit 里 `TimeoutStartSec=75`；**这一步就失败先看日志**，不要乱改配置。

```bash
# ② 跟日志（Ctrl-C 退出）
sudo journalctl -u opensearch -f
#   文件日志另有一份： /home/opensearch/logs/pm-os.log
# ③ 复核 unit 施加的上限
systemctl show opensearch -p LimitNOFILE -p LimitMEMLOCK
# 期望 LimitNOFILE=65536 ／ LimitMEMLOCK=infinity
```

### 5.2 验收（**逐条对照，全过才算成功**）

```bash
export OS=http://127.0.0.1:9200
export AUTH='admin:<§6.2 设的强口令>'
```

**A1 · 根接口通（HTTP，不是 HTTPS）**
```bash
curl -sS -u "$AUTH" "$OS/" | head -20
```
```json
{
  "name" : "os01",
  "cluster_name" : "pm-os",
  "version" : { "distribution" : "opensearch", "number" : "3.1.0", "build_type" : "tar" },
  "tagline" : "The OpenSearch Project: https://opensearch.org/"
}
```
> `build_type` 应为 **`tar`**。报 SSL 相关错误（如 `wrong version number`）⇒
> 运行时那份 `app/config/opensearch.yml` 里不是 `plugins.security.ssl.http.enabled: false`（回 §6.3/§6.4）。

**A2 · 集群健康 = green（★不是 yellow）**
```bash
curl -sS -u "$AUTH" "$OS/_cluster/health?pretty"
```
```json
{ "cluster_name" : "pm-os", "status" : "green", "number_of_nodes" : 1,
  "number_of_data_nodes" : 1, "unassigned_shards" : 0,
  "active_shards_percent_as_number" : 100.0 }
```
> 期望**恰好 `green`**。`yellow` 只有一个原因：**某个索引设了 `number_of_replicas ≥ 1`**（单节点放不下副本）——
> 本方案索引一律 `number_of_replicas: 0`（§9.1）。

**A3 · 节点就位（单节点 = 1 行）**
```bash
curl -sS -u "$AUTH" "$OS/_cat/nodes?v"
```
```text
ip             heap.percent ram.percent cpu node.role node.roles master name
10.254.212.110           31          62   3 dimr      dimr       *      os01
```

**A4 · 内存锁定生效**
```bash
curl -sS -u "$AUTH" "$OS/_nodes?filter_path=nodes.*.process.mlockall&pretty"
# 期望 {"nodes":{"<node-id>":{"process":{"mlockall":true}}}}
```

**A5 · 索引列表（还没建索引时只有表头）**
```bash
curl -sS -u "$AUTH" "$OS/_cat/indices?v&expand_wildcards=all"
```

**A6 · 从应用/AI 服务器侧验证连通**
```bash
# 在【应用/AI 服务器】执行（不是本机）
curl -sS -m 5 -u admin:'<强口令>' http://10.254.212.110:9200/_cluster/health
# 期望 {"cluster_name":"pm-os","status":"green",...}
```

```
A1 build_type=tar ｜ A2 green ｜ A3 1 行 ｜ A4 mlockall=true ｜ A5 表头或 green 索引 ｜ A6 跨机可达
```

### 5.3 重启后自动拉起（**"部署完就不管"的底线**）

```bash
sudo reboot
# —— 重新登录后（等 1~2 分钟）——
systemctl is-enabled opensearch        # 期望 enabled
systemctl is-active opensearch         # 期望 active
curl -sS -u "$AUTH" http://127.0.0.1:9200/_cluster/health | head -3    # 期望 green
```

### 5.4 单节点故障演练（**做一次，避免真出事时误判**）

```bash
# ① 模拟服务挂掉（不是删数据）
sudo systemctl stop opensearch
curl -sS -m 3 http://127.0.0.1:9200/ ; echo "exit=$?"
# 期望：连不上（exit=7/28）——这是预期现象，不是部署失败

# ② 此时 AI 侧的表现（§9.3 要求"明确报错"）
curl -s "http://127.0.0.1:8100/health?with_vec=true"    # 期望 vec 块 not ok
#   提问一次：期望明确失败，而不是"没找到相关内容"

# ③ 起来
sudo systemctl start opensearch
curl -sS -u "$AUTH" http://127.0.0.1:9200/_cluster/health | head -3    # green
```

---


## 6. 索引与检索设计　（待执行）

### 6.1 容量与分片（**先看这张表，再决定参数**）　（待执行）

**单条切片的真实口径**（读代码确认）：向量 = 4096 维 `float32` = `4096 × 4 = 16,384 字节 ≈ 16 KB`；
再加原文（≤500 字）、`doc_id`/`filename` 等字段与倒排，**≈18~20 KB/切片**（正文与倒排为**估算（假设：平均每切片 500 字、中文 3 字节/字 ≈1.5KB 原文）**）。

| 规模（切片数） | 对应文档数（估算，假设：30 切片/份） | 向量本体 | **索引总占用（估算，含原文+倒排+段开销，约 ×1.15~1.25）** | `local`（进程内 JSON）对比 |
| --- | --- | --- | --- | --- |
| 2 万 | ≈670 份 | 16KB × 2 万 ≈ **313 MB** | **≈350~400 MB** | 2 万 × 38,333 B ≈ **766 MB**（且每次提问全量解析） |
| 10 万 | ≈3,300 份 | ≈ **1.56 GB** | **≈1.8 GB** | ≈ **3.8 GB** |
| 100 万 | ≈33,000 份 | ≈ **15.6 GB** | **≈18 GB** | ≈ **38 GB**（且单问 +3.8s 起点） |

> **对照口径**：现有 `local` 的实测值是 **38,333 字节/切片（9.36 字节/维）**——那是**十进制文本 JSON**，
> 同一份数据换成 OpenSearch 的 `float32` 二进制后**只占几百 MB**，检索是毫秒级 kNN。
> 结论：**本项目这个量级不需要量化、也不需要 `on_disk`**（那是百万级/磁盘受限时的手段，见 §6.3）。
> 磁盘规划：索引数据放 `/home/opensearch/data`，**给快照留出与索引等量的空间**（§8.2 每天一份、按保留期轮转）。

| 参数 | 值 | 理由 |
| --- | --- | --- |
| `number_of_shards` | **1** | 单节点 + 这个量级（≤100 万切片）：**分片越少越好**。分片是并行度单位，但每个分片都有固定开销（段、堆、文件句柄），1 个分片在单节点上就是最优的检索并行度来源 |
| `number_of_replicas` | **0** | **单节点上副本永远无法分配** ⇒ 只会让状态变 **yellow** 且白占一份盘。**副本不能替代备份**（备份看 §8.2） |
| `refresh_interval` | 写入期 `-1`（关闭），写完改回 `1s` | 批量灌数据时避免频繁 refresh 产生大量小段 |
| `index.knn` | `true` | kNN 索引开关（mapping 同级 settings） |

### 6.2 索引创建（`knn_vector` 4096 维 / cosinesimil / HNSW）　（待执行）

> **3.x 的写法要点（与 2.x 的差别，先看这三条再抄下面的请求）**：
> ① **引擎只能是 `faiss` / `lucene`**（`nmslib` 是 legacy/deprecated，不要在 3.x 新索引上用）；
> ② `space_type` **写在字段这一层**（官方 k-NN vector 页明确："This value can also be specified within the `method`"，
>    两种都合法；我们选**字段层**，与 `knn_vector` 文档的示例一致，避免两种写法混用）；
> ③ 方法参数统一放 **`method.parameters`**，键名是 `m` / `ef_construction`（**注意不是 `params`**）。
> 我们**不写** `mode` 与 `compression_level`：`mode` 默认就是 `in_memory`（本方案要的低延迟），
> 量化/`on_disk` 是百万级或磁盘受限时才用（见 §6.3）。

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
        "data_type": "float",
        "space_type": "cosinesimil",
        "method": {
          "name": "hnsw",
          "engine": "lucene",
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
> 这件事**属于应用代码改动，不在本轮范围内**（本轮只写文档与部署资产）。过滤器仍然可以先按 `doc_id` 用起来（见 §6.4）。

### 6.3 HNSW 与引擎的版本注记　（说明）

| 项 | 取值 | 注记 |
| --- | --- | --- |
| `engine` | **`lucene`（本方案默认）** | OpenSearch 自带、无需额外插件配置；**本仓库代码写的就是 `lucene`**（`OpenSearchIndex.ensureIndex`）。与 `local` 后端的暴力余弦结果最接近，便于"顺序对得上"的验证。官方对它的定位是 "well suited for smaller deployments" 且**支持带过滤的检索**（与我们的 `filter` 预过滤用法契合），上限约"少于 1000 万向量"——本项目 2 万量级绰绰有余 |
| `engine` | `lucene`（本方案取此值） | 注意：`engine` 省略时的默认值是 `faiss`，所以配置里必须显式写 `lucene`。Faiss 支持 `hnsw` 与 `ivf`（IVF 需训练）；`cosinesimil` 在 **2.19 起**才支持，且官方说明"Faiss 引擎下用 `cosinesimil` 时向量会在索引时被自动归一化（因为 Faiss 内部用归一化向量的内积）⇒ **存储的向量值与你输入的不同**"。切引擎必须**重建索引** |
| `engine` | ~~`nmslib`~~ | **3.x 不要用**：官方 `docs/latest` 把它标为 legacy、"now deprecated"。老集群的 2.x 索引还在用它，但**新索引一律选 `lucene` 或 `faiss`** |
| `name` | `hnsw` | 近似最近邻图索引（`ivf` 需要训练步骤，本方案不用） |
| `m` | `16`（= 官方默认值） | 每个节点的双向连边数（官方要求保持 2~100）；越大召回越好、内存与建索引越慢 |
| `ef_construction` | `128`（官方默认 **100**，我们略调高） | 建图时的候选队列长度；越大图质量越好、建索引越慢 |
| `space_type` | `cosinesimil` | **与 `local` 后端的余弦同一口径**（这一条**未实测**：集群起来后必须做"top-k 顺序与 local 一致"的对照，见 §6.5 验证口径）。官方对 `lucene` 支持的 space 列表是 `l2` / `cosinesimil` / `innerproduct`，`cosinesimil` 在列 |
| `data_type` | `float` | 官方默认值；`on_disk` 模式只支持 `float` / `half_float` |
| `mode` | **不写**（默认 `in_memory`） | 官方默认 `in_memory`（低延迟优先）。`on_disk` 是 2.17 引入的低成本路线，官方明确"配合 `compression_level`、默认 `32x`、并默认开启 rescore（用磁盘上的全精度向量重排）" |
| `on_disk` / 量化（`sq`/`pq`、`compression_level`） | **本方案不用** | 百万级切片或磁盘受限时才考虑；本项目当前约 2 万切片。要用请先核对你的版本与官方 k-NN 文档，切了必须**重建索引** |
| 维数上限 | **16,000** | 官方 `knn_vector.dimension` 的合法区间是 `[1, 16000]` ⇒ 我们的 **4096 在范围内**（这是选型时容易忽略的一条硬上限） |

> **验证口径（做完 §6.5 的对照才算数）**：对同一份语料、同一批问题，把 OpenSearch kNN 的 top-k 顺序与 `local` 的 top-k **逐条对比**；
> 顺序对不上通常意味着 `space_type` 或字段名写错（对照 [`OpenSearchIndex`](../ai-backend/src/main/java/com/pmgt/ai/module/retrieval/OpenSearchIndex.java) 的注释协议：
> 建索引 → 幂等写入 → 查询页码一致 → 顺序与 local 一致）。

### 6.4 写入（`_bulk`，`_id` = 切片键，幂等 upsert）　（待执行）

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
> 这是"不需要额外版本号或重建流程"的前提，也是**可中断续跑**的基础（见 §9.2）。
> **`vector` 的生成**：必须来自平台向量化接口 `POST {LLM_BASE_URL}/embeddings`（`{"model":"Qwen3-VL-Embedding-8B","input":["<切片文本>"]}`，
> 返回 `data[i].embedding`），**不要自己在文档里编造数字**——这里写 `<4096 个数字>` 就是因为本文件不造假数据。

### 6.5 查询（预过滤 + kNN + hybrid）　（待执行）

**① 纯 kNN + filter 预过滤（"先过滤再检索"的落地形态）**

> **★3.x 的查询语法与 2.x 不同**（官方 k-NN query 页 `docs/latest` 的原文结构）：
> 查询体是 **`"knn": { "<向量字段名>": { "vector": [...], "k": n, "filter": {...} } }`** ——
> **字段名直接放在 `knn` 下面**，而不是像旧写法那样嵌一层固定的 `vector` 键。
> 我们的字段名就叫 `vector`，所以下面看起来像旧写法，但结构是 **3.x 的**（`knn.vector.vector` 里的第一个 `vector` 是**字段名**）。
> 另外 3.x 官方明确：**`filter` 只能配合 `faiss` 或 `lucene` 引擎**（本项目用 `lucene`，没问题）。

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
# 结构说明同 ①：knn 下面第一层是【字段名】vector（3.x 写法）
curl -sS -u "$AUTH" -XPOST "$OS/pm-ai-chunks/_search" -H 'Content-Type: application/json' -d '{
  "size": 50,
  "_source": ["filename","page_no","doc_id","chunk_key"],
  "query": { "knn": { "vector": { "vector": <查询向量>, "k": 50,
    "filter": { "bool": { "filter": [
      { "terms": { "project_id": ["P2026-001"] } },
      { "terms": { "security_level": ["内部","公开"] } } ] } } } } }
}'
```

> **⚠️ 与仓库现有适配层的一处不一致（要认账，属应用改动、不在本轮范围）**：
> 仓库里的 [`OpenSearchIndex.search()`](../ai-backend/src/main/java/com/pmgt/ai/module/retrieval/OpenSearchIndex.java)
> 按 **2.x 的固定结构**拼查询体（`{"knn":{"vector":{"vector":[...],"k":k}}}`，那个 `vector` 是**固定键名**）。
> 3.x 的官方结构是"`knn` 下第一层写**字段名**"。由于我们的字段名恰好也叫 `vector`，**语义正确**；
> 但**这个巧合不能当保障**——真要上线前必须按 §9.2 ⑧ 做一次"top-k 顺序与 local 一致"的实测，
> 并在需要时把适配层改成显式字段名（那是一次应用代码改动，需另行排期，见 §9 未确认项）。

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

## 7. OpenSearch Dashboards（可视化，与 OpenSearch 同机 · `10.254.212.110:5601`）　（待执行）

> **官方依据**：[Installing OpenSearch Dashboards from a tarball](https://docs.opensearch.org/latest/install-and-configure/install-dashboards/tar/)（**该页只给三步：解包 → 改 `config/opensearch_dashboards.yml` → `./bin/opensearch-dashboards`，没有 systemd 示例**）、
> [Configuring OpenSearch Dashboards](https://docs.opensearch.org/latest/install-and-configure/configuring-dashboards/)（配置文件位置口径）、
> 官方示例配置 [`config/opensearch_dashboards.yml`](https://github.com/opensearch-project/OpenSearch-Dashboards/blob/main/config/opensearch_dashboards.yml)（**字段名与默认值的权威来源**）。
> 资产：[`opensearch_dashboards.yml.example`](../deploy/opensearch/opensearch_dashboards.yml.example)、[`opensearch-dashboards.service`](../deploy/opensearch/opensearch-dashboards.service)。

### 7.1 安装（解包 + 配置）　（待执行）

> **落位规则与 §2 完全一致**：属主 `opensearch:opensearch`，配置文件 `640`，目录权限按 §2.1（共享模型）；
> 命令用 `sudo install -D`（`-D` 会自动建出 `dashboards/app/config`，不需要先 `mkdir`）。
> ⚠️ **不要对 `dashboards/app/` 套用 `chmod 664`**（同 §2.1 的红线：会抹掉 `bin/opensearch-dashboards`、`node/bin/node`
> 以及 `jdk`/`node` 下工具的执行位 ⇒ `systemd` 报 `status=203/EXEC`）。这里只需要：
> `sudo chown -R opensearch:opensearch /home/opensearch/dashboards`（改属主）+ `install -m 640`（改配置）。

```bash
# ① 解包到 dashboards/app —— ★"以谁解压就归谁"，所以解压后必须统一改属主
sudo -u opensearch mkdir -p /home/opensearch/dashboards
cd /home/opensearch/dashboards
tar -zxf /home/opensearch/opensearch-dashboards-3.1.0-linux-x64.tar.gz
mv opensearch-dashboards-3.1.0 app
ls /home/opensearch/dashboards/app        # 期望看到 bin/ config/ node/ plugins/ src/ …

# ★属主归位（用 lhim 解压会让 dashboards/ 里混入 lhim 属主 ⇒ Dashboards 以 opensearch 运行会读不了）
sudo chown -R opensearch:opensearch /home/opensearch/dashboards
ls -ld /home/opensearch/dashboards /home/opensearch/dashboards/app
# 期望：均为 opensearch opensearch；目录 750（app/ 常见 drwxr-sr-x，属正常）
sudo -u opensearch ls /home/opensearch/dashboards/app/bin/opensearch-dashboards   # 最终确认它读得到
```

**配置文件（官方规定的位置就是解包目录下的 `config/opensearch_dashboards.yml`）**

```bash
# ② 放行我们的配置：一条 install 同时完成「建目录（-D）+ 复制 + 定属主 + 640」
#    资产源用 §2 约定的 $ASSETS（= 仓库 deploy/opensearch 的本地路径），不要写相对路径
sudo install -D -o opensearch -g opensearch -m 640 \
     "$ASSETS/opensearch_dashboards.yml.example" \
     /home/opensearch/dashboards/app/config/opensearch_dashboards.yml

# ③ 改两处必改项（改的是落位后的那份；用服务账号身份编辑，避免又变成 lhim 属主）
sudo -u opensearch vi /home/opensearch/dashboards/app/config/opensearch_dashboards.yml
#   ★必改：server.host 改成 10.254.212.110；opensearch.username/password 用 admin 与 §4.2 的强口令
#   （用 root 的 vi 直接改即可——文件属主不会变）

# ④ 复核落位（与 §2 表里的 Dashboards 行逐项对齐）
ls -l  /home/opensearch/dashboards/app/config/opensearch_dashboards.yml   # 期望 -rw-r----- opensearch opensearch
ls -ld /home/opensearch/dashboards/app/config                            # 期望 drwxr-x--- opensearch opensearch
```

| 配置项 | 本方案取值 | 为什么 |
| --- | --- | --- |
| `server.host` | **`"10.254.212.110"`** | 官方默认是 `"localhost"`（官方注释原话："usually means remote machines will not be able to connect"）⇒ 必须显式改成内网 IP。**不要写 `0.0.0.0`**，暴露面交给防火墙（§7.3） |
| `server.port` | `5601` | 官方默认端口 |
| `server.name` | `"pm-os-dashboards"` | 显示用 |
| `opensearch.hosts` | **`["http://127.0.0.1:9200"]`** | ★**与 §4.4 的 REST 配置绑定**：§4.4 已定 `plugins.security.ssl.http.enabled: false`，9200 是 HTTP，所以这里用 `http://`。REST 层将来若改走自签 TLS，这里要同步改成 `https://` 并配信任链——见附录 B |
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
> **transport 层证书仍然存在**（§4.4），所以上面这套照旧可用。
> ⚠️ 改完 `internal_users.yml` 等文件后**必须跑一次 `securityadmin.sh`**，否则改动只在磁盘上、不生效。

### 7.2 内存预算（Dashboards 是 **Node.js**，不是 JVM）　（说明）

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

### 7.3 端口与防火墙（**5601 只给运维网段**）　（待执行）

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

### 7.4 systemd（装 unit 并验收）　（待执行）

```bash
# ① 装 unit（资产：deploy/opensearch/opensearch-dashboards.service）
sudo cp /home/opensearch/config/opensearch-dashboards.service /etc/systemd/system/opensearch-dashboards.service
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

# ⑤ 重启后自动拉起（与 §1.3 同一条底线）
sudo reboot
# —— 重新登录后 ——
systemctl is-enabled opensearch-dashboards ; systemctl is-active opensearch-dashboards
```

> **unit 里三处与 Dashboards 特性相关的调整（都在资产文件里写了原因）**：
> ① `Type=simple`（官方 OpenSearch 那份 systemd 示例是 `Type=forking`，对应 `-d`；`bin/opensearch-dashboards` 是前台进程）；
> ② `KillMode=mixed`（包装脚本 + Node 子进程要一起收掉，避免孤儿进程占着 5601）；
> ③ `Environment=NODE_OPTIONS=--max-old-space-size=1024`（Node 堆封顶，§7.2）。
> **官方没有给 Dashboards 的 systemd 示例**（tar 页只有三步），这三处是我们按它的运行方式补的，其余指令照官方 OpenSearch 那份 unit。

### 7.5 冷备结论：**Dashboards 不需要冷备**（回答疑问）　（结论）

| 问题 | 结论 |
| --- | --- |
| Dashboards 需要单独做冷备吗？ | **不需要**。它的"状态"（saved objects：仪表盘/可视化/索引模式、租户、动态配置）**存在 OpenSearch 的 `.opensearch_dashboards*` 索引里**，不在 Dashboards 本地 |
| 那它的"备份"是什么？ | ① **OpenSearch 的快照**（且**必须含 `.opensearch_dashboards*`**，见 §7.6）；② 一份 `opensearch_dashboards.yml`（就在本仓库资产里）；③ **`.111` 上归档的 tar 包**（程序本体，重装用） |
| 灾难恢复怎么做？ | 在 `.111`（或重装后的机器）上：装同版本 Dashboards → 放回 `opensearch_dashboards.yml` → 恢复 OpenSearch 快照（含系统索引）→ 启动。**四步，全是已有材料** |
| 要备份 `dashboards/app/data/` 之类目录吗？ | **不用**：那是运行期临时文件；真正要留的只有 `config/opensearch_dashboards.yml` |

### 7.6 ⚠️ **快照必须显式包含系统索引**（否则恢复出来是空的）　（结论）

> **这是本节最容易漏、后果最隐蔽的一条。**
> OpenSearch 的 `_snapshot` **默认不包含系统索引**。只写业务索引时（`pm-ai-chunks`），那么恢复到 `.111` 之后：
> - **Dashboards 打开是空的**（`.opensearch_dashboards*` 没恢复 ⇒ 仪表盘、可视化、索引模式全没了）；
> - **安全配置回默认**（`.opendistro_security` 没恢复 ⇒ 内部用户/角色/角色映射全变回 demo 默认值）。

因此 [`snapshot-backup.sh`](../deploy/opensearch/snapshot-backup.sh) 的默认索引列表**已经显式包含系统索引**：

```text
pm-ai-chunks,.opensearch_dashboards*,.opendistro_security
```

它由脚本里的 `INDEX` 配置项控制（也可用环境变量 `INDEX=` 覆盖）。
**通配符不需要任何额外参数**——官方 Create Snapshot API 的 `indices` 字段原文就写着
"You can use `,` to create a list of indexes, `*` to specify an index pattern, and `-` to exclude certain indexes"，
官方示例本身就是 `{"indices": "opensearch_dashboards*,my-index*,-my-index-2016", "ignore_unavailable": true, ...}`。

> ⚠️ **一条已被纠正的错误做法（留档，别改回去）**：本脚本早期版本在请求体里加过 `"expand_wildcards": "all"`，
> 理由是"通配符需要显式展开"。**这是错的**：该 API 的请求体字段只有
> `indices` / `ignore_unavailable` / `include_global_state` / `partial` 四个，**没有 `expand_wildcards`**。
> 现已移除。索引名的点号前缀（`.opensearch_dashboards*`）**以现场 `_cat/indices?v` 的实际名字为准**。

```bash
# ① 看脚本当前要快照哪些索引（默认就是上面那串）
grep -n '^DEF_INDEX=' /home/opensearch/config/snapshot-backup.sh

# ② 手动打一份，确认系统索引真的进了快照（★关键验证）
bash /home/opensearch/config/snapshot-backup.sh snapshot
curl -sS -u admin:'<强口令>' \
  'http://127.0.0.1:9200/_snapshot/os-backup/nightly-'$(date +%F)'?pretty' | grep -E '"indices"|\.opensearch_dashboards|\.opendistro_security' | head -20
# 期望：indices 列表里能看到 .opensearch_dashboards* 与 .opendistro_security
#   只有 pm-ai-chunks ⇒ 索引列表被改过（或系统索引名与本文件不一致，用 _cat/indices?v 核对实际名字）
```
```bash
# ③ 恢复演练时的验证（在 .111 上；步骤见 §9.4 七步）
curl -sS -u admin:'<强口令>' 'http://127.0.0.1:9200/_cat/indices?v' | grep -E 'opensearch_dashboards|opendistro_security'
# 期望：能看到 .opensearch_dashboards_* 与（若自定义过） .opendistro_security
# ④ 最终判据：浏览器打开恢复后的 Dashboards，**仪表盘/索引模式还在** ⇒ 系统索引确实恢复成功
```

> **为什么不用 `include_global_state: true` 顺带带上一切**：全局状态包含集群设置、模板、以及
> **`.opendistro_security` 的权限校验逻辑对象**，跨集群恢复时容易互相干扰（官方也说明"恢复快照时
> 不能包含全局状态，且不恢复 `.opendistro_security`"，除非用 admin 证书签名）。
> 本方案的选择是**显式列出要的索引**——可控、可验证、恢复后不会把别处的集群设置带乱。


## 8. 运维（systemd + tar 语汇）

### 8.1 日常命令速查　（随时可用）

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

### 8.2 快照与恢复（**单节点的强制项**）　（待执行）

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

#### 8.2.1 ★cron 装到谁、口令谁能读（**三者必须自洽，否则备份每天静默失败**）

这条是**真缺陷**：脚本读口令的顺序是「环境变量 → `snapshot-backup.env` → `/etc/opensearch-backup.pass`」，
而 `/etc/opensearch-backup.pass` 是 **`root:root 600`** —— 也就是说**非 root 的 cron 用户读不到它**，
于是脚本在 `require_pass` 阶段就退出，**每天 2:30 静默失败**（没有快照，而你毫不知情）。

**cron 装到 `root` 的 crontab**，口令文件 `/etc/opensearch-backup.pass` 保持
**`root:root 600`** —— 三者天然自洽（root 读写 `backup/`、`logs/` 不受限，口令也最安全）。

```bash
# 装到 root 的 crontab（每晚 02:30）
sudo crontab -e
# 加入这一行：
30 2 * * * /bin/bash /home/opensearch/config/snapshot-backup.sh snapshot >> /home/opensearch/logs/snapshot-cron.log 2>&1

# 复核三件事（★别只看 crontab 有没有装上）
sudo crontab -l | grep snapshot-backup.sh                 # ① cron 行在 root 的 crontab 里
ls -l /etc/opensearch-backup.pass                         # ② 期望 -rw------- root root
sudo -u root bash /home/opensearch/config/snapshot-backup.sh status   # ③ 以 cron 用户身份真能读到口令
```

> **配错的现象就是"备份每天静默失败"**：`crontab -l` 看着没问题、白天谁也不会去看日志。
> 两个验证办法：① 看 cron 日志（`sudo journalctl -u crond -n 50` 或 `/var/log/cron`，按发行版）；
> ② **手工以 cron 用户身份跑一次** `bash … snapshot` —— 它会把"没拿到口令"这条**明确打出来**。
> 再配合每周看一眼 `bash … status` 里最近一份快照的日期，就能保证"备份真的在跑"。

| 项 | 值/建议 |
| --- | --- |
| 仓库 | 文件系统仓库 `os-backup` → `/home/opensearch/backup/fs-repo`（必须在 `path.repo` 之下） |
| 频次 | **每晚一次**（RPO ≈1 天）；要更小就提高 cron 频次 |
| 保留 | 脚本默认 **7 天**（`KEEP_DAYS=7`；更早的 `nightly-*` 自动删） |
| 异地 | 每晚 `rsync` 到 `.111`（**单节点方案里防硬件损坏的唯一手段**） |
| 演练 | 每季度一次（§9.4 七步），**演练过才算有备份** |

```bash
# 手工等价命令（脚本坏了时的兜底）
curl -sS -u admin:'<口令>' -XPUT 'http://127.0.0.1:9200/_snapshot/os-backup' -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/home/opensearch/backup/fs-repo","compress":true,"index.latest":false}}'
curl -sS -u admin:'<口令>' -XPUT "http://127.0.0.1:9200/_snapshot/os-backup/nightly-$(date +%F)?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{"indices":"pm-ai-chunks","ignore_unavailable":true,"include_global_state":false}'
curl -sS -u admin:'<口令>' 'http://127.0.0.1:9200/_snapshot/os-backup/_all?pretty' | grep -E '"snapshot"|"state"'
```

### 8.3 升级路径（官方 Tar 升级做法 + 回滚）　（将来执行）

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
tar -xvf /home/opensearch/opensearch-<新版本>-linux-x64.tar.gz
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

# ⑥ ★证书/安全配置/keystore 的【属主与权限】——升级后最常见的 644/640 事故就在这里
#    规则（与 §2 落位表一致）：属主一律 opensearch:opensearch；
#      · *.pem（含私钥 esnode-key.pem / admin-key.pem）→ 640（组可读、组不可写）
#      · opensearch-security/*.yml（内部用户/角色）  → 640
#      · opensearch.keystore                          → 600（只属主可读）
sudo chown -R opensearch:opensearch /home/opensearch/app-<新版本>/config
sudo find /home/opensearch/app-<新版本>/config -maxdepth 1 -name '*.pem' -exec chmod 640 {} +
sudo find /home/opensearch/app-<新版本>/config/opensearch-security -name '*.yml' -exec chmod 640 {} + 2>/dev/null || true
sudo chmod 600 /home/opensearch/app-<新版本>/config/opensearch.keystore 2>/dev/null || true
sudo chmod 750 /home/opensearch/app-<新版本>/plugins/opensearch-security/tools/*.sh 2>/dev/null || true
ls -l /home/opensearch/app-<新版本>/config/*.pem /home/opensearch/app-<新版本>/config/opensearch-security/*.yml 2>/dev/null | head
ls -l /home/opensearch/app-<新版本>/config/opensearch.keystore 2>/dev/null
#   期望：pem 与 opensearch-security/*.yml 为 -rw-r-----（640）；keystore 为 -rw-------（600）
```

```bash
# ⑦ 停服 + 换目录（旧 app/ 改名保留 = 回滚点）
sudo systemctl stop opensearch
sudo mv /home/opensearch/app           /home/opensearch/app-<旧版本>
sudo mv /home/opensearch/app-<新版本>   /home/opensearch/app

# ⑧ 权限归位（换目录后必须重做，属主会跟着改名的目录走，但确认一次更稳）
sudo chown -R opensearch:opensearch /home/opensearch   # 只改属主；★不要顺手加 chmod -R（见 §2.1 红线）
sudo chmod 640 /home/opensearch/app/config/opensearch.yml \
               /home/opensearch/app/config/jvm.options.d/heap.options
sudo -u opensearch ls /home/opensearch/app/bin/opensearch     # 最终确认能读到

# ⑨ 启动 + 验收
sudo systemctl start opensearch
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/ | head -12                 # 看 version.number 是否新版本
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cluster/health | head -3   # 期望 green
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cat/nodes?v                # 1 行
```
```bash
# 回滚（升级后起不来或行为异常）：把目录换回去再起（就是 ⑦⑧⑨ 的反操作）
sudo systemctl stop opensearch
sudo mv /home/opensearch/app            /home/opensearch/app-failed-<新版本>
sudo mv /home/opensearch/app-<旧版本>    /home/opensearch/app
sudo chown -R opensearch:opensearch /home/opensearch
sudo systemctl start opensearch
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cluster/health | head -3   # 期望 green
# ⚠️ 若新版本【已经写过数据】，直接回退旧版本可能读不了新格式 → 那时用快照恢复（§8.2）
```

> **确认新版稳定后**再删旧目录：`sudo rm -rf /home/opensearch/app-<旧版本>`（先确认快照里有一份可恢复的数据）。
> **大版本（2.x → 3.x）不要直跳**：按官方"先升到最后一个 2.x，再升 3.x"的路径走，每步都打快照。
> 本方案是单节点，停机升级即可（分钟级）。

### 8.4 监控要看哪些指标　（说明）

| 类别 | 指标 | 看它判什么 | 怎么看 |
| --- | --- | --- | --- |
| **JVM 堆** | 堆使用率（`heap.percent`）、GC 统计 | 堆长期 >85% 或 GC 停顿变长 ⇒ 调 heap.options 或扩内存 | `_cat/nodes?v`、`_nodes/stats/jvm` |
| **内存锁定** | `mlockall` | 必须 `true`（§1.2 A4） | `_nodes?filter_path=nodes.*.process.mlockall` |
| **线程池** | `search`/`write` 队列与 `rejected` | 有拒绝 ⇒ 检索/写入压力超容量 | `_cat/thread_pool/search,write?v&h=node_name,active,queue,rejected` |
| **磁盘** | 使用率与水位 | §8.5 的水位判读；**单节点最容易死在这里**（快照也吃盘） | `_cat/allocation?v`、`df -h /home/opensearch` |
| **段与合并** | 段数、merge 次数/耗时 | 段数暴涨（批量写入期）⇒ 用 `_forcemerge` 收口 | `_cat/segments/pm-ai-chunks?v` |
| **kNN** | `_plugins/_knn/stats` | 图未被缓存 ⇒ 查询走磁盘 | `GET _plugins/_knn/stats?pretty` |
| **集群状态** | `status`、`unassigned_shards`、`number_of_pending_tasks` | yellow/red 判读见 §8.5 | `_cluster/health`、`_cat/pending_tasks?v` |
| **快照** | 最近快照 `state` 与耗时 | 失败要在 24h 内发现（否则等于没有备份） | `bash /home/opensearch/config/snapshot-backup.sh status` |
| **服务本身** | systemd 状态、重启次数 | `Restart=on-failure` 生效说明崩过 | `systemctl status opensearch`（看 `Main PID` 与 `Active`） |

> ⚠️ **本项目 AI 侧目前没有 actuator/micrometer**（《AI模型调用与任务队列评估》已登记"无任何指标"），
> OpenSearch 侧也**没有接 Prometheus/告警**——本节指标目前**只能靠命令巡检**。要不要上监控栈见 §10。

### 8.5 常见故障　（排障用）

| 现象 | 判读 | 处置 |
| --- | --- | --- |
| 启动即退出，日志 `max virtual memory areas vm.max_map_count [65530] is too low` | 内核参数没设或没持久化（重启后丢了） | §2.1 全做一遍；**进程内改不了**，必须在宿主设 |
| 直接拒绝启动并提示不能以 root 运行 | 用 root（或 root 的 unit）拉起来了 | unit 里 `User=opensearch` / `Group=opensearch`（资产已设）；手工调试也要 `sudo -u opensearch …` |
| **`status=203/EXEC`**（`Control process exited, code=exited, status=203/EXEC`，且 `logs/` 里**什么都没有**） | **systemd 在 exec 阶段就没能启动程序** ⇒ 应用根本没跑起来，所以应用日志为空，**别在 `logs/` 里找原因**。四类原因：① **`app/` 下文件被执行位被抹掉**（例如对整个 `/home/opensearch` 跑过 `chmod 664`，本项目**实际发生过**）；② `/home` 挂载带 **`noexec`**；③ 路径/属主问题导致服务账号 `opensearch` **穿不过某一级目录**；④ **SELinux / 麒麟 kysec 拒绝 exec** | ① 先看 `systemctl status opensearch -l` 与 `systemctl cat opensearch`（核对 `ExecStart` 路径）；② **手工以服务账号执行**，这一步能看到真实原因：`sudo -u opensearch /home/opensearch/app/bin/opensearch`；③ 按原因处置：**执行位** ⇒ §4.5 三条命令恢复后重启；**noexec** ⇒ `findmnt -no OPTIONS /home` 确认后把部署根挪到非 noexec 的盘；**属主/路径** ⇒ §2.1/§2.3 复核（`sudo -u opensearch ls /home/opensearch/app/bin/opensearch`）；**SELinux/kysec** ⇒ `getenforce`、`ls -Z /home/opensearch/app/bin/opensearch`、`sudo ausearch -m avc -ts recent` / `journalctl -k` |
| **systemd 启动失败，日志报权限不足 / `status=200/CHDIR` / 找不到 `/home/opensearch/app/bin/opensearch`** | 属主或权限不对（D 方案下**不再**是家目录穿透问题：部署根就是 `opensearch` 自己的家目录） | **§2.1 / §2.3**：`sudo -u opensearch ls -d /home/opensearch` 与 `sudo -u opensearch ls /home/opensearch/app/bin/opensearch`；不对就 `sudo chown -R opensearch:opensearch /home/opensearch`。若仍报错且系统开了 SELinux，看 `ls -Z /home/opensearch` 与 `sudo ausearch -m avc -ts recent` |
| 启动失败：`/home/opensearch` 所在盘不可写 / 数据目录报错 | `/home` 是 NFS/autofs，或空间不足 | §3.4：`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home`；按"退路"把 `path.data` 指到本地盘 |
| 启动失败：`Permission denied` 且 `/home` 挂载选项里有 **`noexec`** | `noexec` 会导致 JVM/`bin/opensearch` 无法执行 | 把部署根挪到非 `noexec` 的盘（或让平台方去掉 `noexec`；**`nosuid`/`nodev` 无需处理**，见 §3.4） |
| 启动失败：`OPENSEARCH_INITIAL_ADMIN_PASSWORD` 相关报错 / demo 配置装不上 | 口令没设或**不满足官方要求**（8~100 字符、四类字符、zxcvbn 达 strong、不与 admin 相似） | 换强口令（`openssl rand -base64 24`），重新跑 §4.2 |
| 启动报权限错误：`AccessDeniedException` / 读不到 `config/opensearch.yml` | 属主或权限不对 | §2.3：`chown -R opensearch:opensearch`；`config/opensearch.yml` 640；`opensearch.keystore` 600；`plugins/opensearch-security/tools/*.sh` 750 |
| 日志报 keystore 相关错误 | `config/opensearch.keystore` 缺失或权限不对 | `sudo -u opensearch /home/opensearch/app/bin/opensearch-keystore list` 看是否可读；必要时重建并用 systemd 环境变量注入敏感值 |
| 日志报"没有证书 / 集群形成失败（`Transport client authentication no longer supported` 之类）" | 我们替换 `opensearch.yml` 时**把 transport 证书那几行丢了** | 对照 §4.3/§4.4 与资产文件，把 `plugins.security.ssl.transport.*` 三行补回；证书文件在 `config/*.pem`，确认存在 |
| 建索引报 `Unknown engine [nmslib]` / 引擎相关错误 | 3.x 上**不该再用 `nmslib`**（legacy/deprecated） | §6.2/§6.3：`method.engine` 必须是 `lucene`（本方案取值）；已有 `nmslib` 旧索引要重建 |
| kNN 查询报解析错误 / 命中为空 | 查询体结构与版本不匹配：3.x 是 **`"knn": { "<字段名>": {...} }`**（字段名是第一层键） | 对照 §6.5 ①的 3.x 结构；用 `curl` 手工打一次最简查询（只要 `vector` + `k`）先确认能通，再加 `filter` |
| 报 `k` 超出范围 / 要返回很多条 | 3.x 官方：`k` 合法区间 **[1, 10000]** | §6.5：本项目用 `k=50`（`RETRIEVAL_RECALL=50`），远在范围内；若有人手工调大，别超过 10000 |
| 快照报"未知字段 / 400" | 请求体里混进了该 API 不支持的字段（例如早前版本误加的 `expand_wildcards`） | §7.6 + 脚本：请求体只允许 `indices`/`ignore_unavailable`/`include_global_state`/`partial` |
| curl 报 SSL 错误（`wrong version number` / `certificate verify failed`） | REST 层 TLS 与请求协议不匹配：官方默认是 HTTPS，本方案走 HTTP | 确认运行时 `config/opensearch.yml` 里是 `plugins.security.ssl.http.enabled: false`（§4.4），改完 `systemctl restart opensearch` |
| curl 用 HTTP 时报 `Empty reply from server` | 仍是 HTTPS 端口在应答 | 同上；先用 `curl -k https://127.0.0.1:9200/` 确认服务本身活着 |
| 认证失败 `401 Unauthorized` | 用户名/口令不对 | `curl -u admin:'<口令>'` 单独验证；口令含特殊字符时**用单引号包住**；确认用的是本轮设的强口令（不是 demo 默认值） |
| **`yellow`** | 主分片就绪、**副本未分配** | **单节点上几乎总是"某个索引设了 replica ≥1"**：`PUT /<索引>/_settings {"index":{"number_of_replicas":0}}`；用 `_cat/indices?v&health=yellow` 找出来逐个改 |
| **`red`** | 有**主分片**未分配 ⇒ 检索/写入报错 | `_cat/indices?v` 与 `_cluster/allocation/explain`；单节点上常见原因是**数据目录权限**（§2.4）或**磁盘满** |
| 磁盘满 / 索引变只读（flood stage） | 达 95% 水位 | ① 清旧快照（脚本保留期调小）；② **查日志是否在涨**：`du -sh /home/opensearch/logs`，没配轮转就照 §2 的"日志轮转"配一份；③ 解锁：`PUT /<索引>/_settings {"index.blocks.read_only_allow_delete":null}`；④ 复核 §8.1 的水位 |
| 首次检索特别慢（几十秒~分钟级） | **惰性向量化在问答路径上**（§9.2 现状） | 按 §9.2 先灌满索引再切 `VEC_BACKEND`；临时手段是重复发几次同样的提问 |
| 快照失败 `repository_exception` / 报目录不可写 | 仓库目录属主不对或不在 `path.repo` 之下 | `sudo chown -R opensearch:opensearch /home/opensearch/backup`；核对 `path.repo` 与脚本 `REPO_LOCATION` 的父子关系 |
| `snapshot-backup.sh` 报无法免密 ssh | `rsync` 到 `.111` 需要免密 | `ssh-copy-id lhim@10.254.212.111`；或临时 `SKIP_REMOTE=1`（但**别长期跳过**，否则没有异地副本） |
| AI 日志"集群不可达（http://…）" | 网络/防火墙/`network.host` 三者之一 | 在 AI 那台跑 §3.6 与 §5.2 A6；核对 `network.host` 与 §3.5 白名单 |

---

## 9. AI 服务接入与切换/回滚　（待执行）

> 接入侧事实（读代码确认，不要臆造）：`ai-backend/src/main/resources/application.yml:54-62` —
> `RETRIEVAL_RECALL=50`、`RETRIEVAL_TOP_K=5`、`VEC_BACKEND=local`、`OPENSEARCH_URL`（空）、`OPENSEARCH_INDEX=pm-ai-chunks`、
> `OPENSEARCH_USER`/`OPENSEARCH_PASSWORD`；`RetrievalService.checkBackend()` 在 `VEC_BACKEND=opensearch` 时
> **url 为空 / 适配层不可用 / 集群不可达 一律显式抛错，绝不静默退回 `local`**。

### 9.1 改哪些配置（AI 服务器上的 `/home/lhim/pm/ai/.env`）　（待执行）

```bash
cd /home/lhim/pm/ai
cp .env .env.bak.$(date +%Y%m%d%H%M)     # 先备份（回滚就靠它）

vi .env
```
```bash
# ── 检索后端切到 OpenSearch（必改）──
VEC_BACKEND=opensearch
# ★地址写成 http://<os主机>:9200 —— 这个默认值与"关掉 REST 层 TLS"的取舍绑定：
#   本节按 §4.4 的 HTTP 口径写；REST 层若将来改自签 TLS，见附录 B。
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
> `dimension`/`space_type` 写错**没有编译期提示**；所以 §6.5 的"与 local 的 top-k 顺序对照"是必做项。

### 9.2 全量重建索引（现有切片必须重新向量化）　（待执行）

**现状（读代码确认，这是最容易踩的坑）**：向量化/写入**不在入库路径上**——
`DocStore.save` 只落文本，**第一次检索**才在问答请求里惰性补算（`RetrievalService.embedChunks`，串行批 `EMBED_BATCH=32`），
写索引也在那条路径里（`ensureIndexOnce → upsertAll`）。**因此"切完 `VEC_BACKEND` 就完事"是错的**：
第一个提问会扛下整个语料的重建工作，**必然撞穿前端 180s 超时**。

**正确顺序：先把索引灌满并核数，再切开关。**

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| **① 备份现状** | 备份 `work/vectors/` 与 `work/docs/`（`tar -czf vectors-$(date +%F).tar.gz work/vectors work/docs`） | 回滚 `local` 不需要重算 |
| **② 摸清规模** | `curl -s http://127.0.0.1:8100/documents` 数文档；或直接数 `work/docs/*.json` 与切片总数 | 得到 **N_chunks**（后续所有时间估算都用它） |
| **③ 生成切片 NDJSON** | 从 `work/docs/<doc_id>.json` 逐页按 500 字**不跨页**切片，照 §6.4 的两行格式落成 `ndjson/part-0001.ndjson`… | 文件数 × 每片条数 ≈ N_chunks |
| **④ 生成向量并写回 NDJSON** | 按 `POST {LLM_BASE_URL}/embeddings`（`input` 一次最多 **32 条** = `EMBED_BATCH`）批量取向量，**并发 8** | 吞吐参考 **147.9 条/s**（平台手册实测口径，见下） |
| **⑤ `_bulk` 灌入** | `curl --data-binary @part-000N.ndjson "$OS/pm-ai-chunks/_bulk"`，并发 2~4 | 返回 `"errors":false` |
| **⑥ 核数** | `curl "$OS/pm-ai-chunks/_count"` 与 ② 的 N_chunks 对比 | 一致才算灌完 |
| **⑦ 切开关** | §9.1，然后 `bash pm.sh restart ai` | `/health?with_vec=true` 里 `opensearch` ok |
| **⑧ 对照验证** | 抽 20~30 个问题，对比 kNN top-k 与切换前 `local` 的 top-k **顺序**与**页码** | 顺序对得上才继续（对不上查 `space_type`/字段名） |

**时间估算（`估算（假设：吞吐取平台手册实测 147.9 条/s、并发 8、忽略网关排队）`）**

| 规模 | 纯向量化 | 含 `_bulk` 写入与网络（估算 ×1.5） | 说明 |
| --- | --- | --- | --- |
| 5,000 切片 | ≈ 34 s | **≈ 1 分钟** | 小规模，随手可重跑 |
| 2 万切片 | ≈ 135 s | **≈ 3~4 分钟** | 对应 §6.1 的"≈670 份文档" |
| 10 万切片 | ≈ 11 分钟 | **≈ 17~20 分钟** | 建议分片并行、分批灌 |
| 100 万切片 | ≈ 113 分钟 | **≈ 3 小时** | 必须分批 + 断点续跑，别想一次跑完 |

> **"必须带进度与可中断续跑"怎么落地**：以 **`chunk_key`** 为唯一键——
> 每次跑完把已成功写入的 `chunk_key` 追加到 `done.txt`，重跑时用 `grep -vFxf done.txt` 过滤；
> **不要按文件或按文档记进度**（部分成功的批次会整段重跑）。查询侧同理：`_bulk` 的 `_id` 相同 ⇒ 重写幂等，不会翻倍。
> **不要用"删索引重来"当重试手段**（那会把已成功的几十分钟一起丢掉）。

### 9.3 双写 / 灰度与**降级行为（必须定清楚）**　（口径）

| 阶段 | 做法 | 判据 |
| --- | --- | --- |
| 灰度①（影子验证） | `VEC_BACKEND` 仍是 `local`；把 §9.2 的索引照常灌满，只做"离线对照"（不经 AI 服务） | kNN top-k 与 local 顺序一致 |
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

### 9.4 第二台机器 `10.254.212.111`：**冷备 + 演练机**　（待执行）

| 项 | 做法 |
| --- | --- |
| 角色 | **冷备**（同构安装、同样的 unit，**服务默认 `systemctl disable --now`**）+ **恢复演练机** |
| 为什么默认停着 | 单节点方案下它起来也**不参与**任何服务（没有 quorum、没有副本）；不如把资源留给"随时能顶上去" |
| 每天做什么 | ① 在 `.110` 上打快照（§8.2）；② `snapshot-backup.sh` 自动把快照目录 **`rsync` 到 `.111`** |
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

### 9.5 切换前检查清单（**逐条打勾，缺一不可**）　（待执行）

```
□ A1~A6 全绿（§1.2），且 _cluster/health 是 green（不是 yellow）
□ 索引映射已建：dimension=4096、space_type=cosinesimil、number_of_replicas=0（§6.2）
□ _count 与 N_chunks 一致；抽样 20 条，page_no 与原文页码对得上（§9.2 ⑥⑧）
□ AI 那台能跨机访问 9200（§1.2 A6），且【不该通的机器确实不通】（§2.5 ④）
□ ai/.env 已备份（.env.bak.<时间戳>）；OPENSEARCH_PASSWORD 与 OpenSearch 侧 admin 口令完全一致
□ work/vectors/ 与 work/docs/ 已备份（回滚 local 的保险）
□ 降级口径已确认（§9.3）：连不上时【明确报错】，不静默换检索方式
□ 快照脚本已跑通至少一次（bash snapshot-backup.sh snapshot 返回 0）+ cron 已装（§8.2）
□ 从 .111 做过一次恢复演练（§9.4 七步）
□ 有人知道回滚怎么做（VEC_BACKEND=local + pm.sh restart ai，秒级）
```

**本节说明：顺带拿到的一条收益**

- **AI 服务从"必须单实例"变成"可跑 2 个实例"**：`VEC_BACKEND=local` 是**进程内状态**（向量缓存 + 文档库都在本地卷上），
  这正是它不能 HA 的根因；向量与索引搬进 OpenSearch 后，检索侧不再依赖本地进程状态，AI 才谈得上水平扩。
  ⚠️ **但要如实说边界**：`work/docs/`（文档库）与 `work/vectors/`（缓存）**仍是本地卷**（代码现状），
  所以"完全无状态 + 2 实例"还需要**把文档库也搬出进程**——这一步**不在本轮方案内**（§9 未确认项）。
  本次部署的确定收益是：**检索不再受单实例约束、延迟与内存不再随语料线性劣化、可以做"先过滤再检索"**。

---

## 10. 未确认项（**只需要这些，其余都已定死**）

| 序号 | 未确认项 | 影响 | 谁来确认 |
| --- | --- | --- | --- |
| 1 | **磁盘类型与容量**（当前给定 200GB；需确认 SSD/HDD、是否单盘） | HDD 上 kNN 检索延迟会明显变差；单盘则数据与快照抢 I/O | 你 / 平台方 |
| 1 | **内网 NTP 时间源地址** | 时间不同步会让日志/快照时间戳不可比 | 平台方 |
| 2 | **快照保留期与执行时间**（本文按每晚 02:30、留 7 天写） | 影响备份盘容量与 RPO | 你（改脚本 `KEEP_DAYS` 与 crontab） |
| 3 | **现网真实文档数与切片总数**（§9.1 规模表按 30 切片/份估算） | 决定"要不要现在就上"以及 §9.2 的重建耗时 | 你（`/documents` 或自检页） |
| 4 | **"切片带项目/密级元数据"的落地排期**（`project_id`/`security_level` 目前无人写入） | 不做这一步，"先过滤再检索"只是**能力就绪**、不是**功能可用** | 你 / 开发方 |
| 5 | **平台是否真的不支持降维**（本仓库实测传 `dimensions` → HTTP 400） | 决定 `VEC_EMBED_DIMENSIONS` 永远为 0（本文按"不传"写） | 平台方 |
| 6 | **AI 能力服务实际跑在哪台机器上**、其容器是否设 `-Xmx` | 决定 §9.1 的 `OPENSEARCH_URL` 与 §3.5 的防火墙白名单 | 你 |
| 7 | **是否要上监控/告警栈**（Prometheus / Grafana / OpenSearch 告警插件） | §9.4 的指标目前只能手工巡检 | 你 |
| 8 | **`.111` 演练机的 ssh 免密是否允许配置**（`snapshot-backup.sh` 的 rsync 需要） | 不配就只能 `SKIP_REMOTE=1`，等价于没有异地副本 | 你 |
| 9 | **Dashboards 系统索引的实际名字**（脚本按 `.opensearch_dashboards*` 与 `.opendistro_security` 写，点号前缀未经真机确认） | 若实际名字不同，快照会"成功但没备到"，恢复后表现为界面空白/权限回默认 | 你（`_cat/indices?v` 看一眼，见 §7.5） |
| 10 | **仓库适配层的 kNN 查询体是否要按 3.x 语法改写**（现状按 2.x 固定结构拼、字段名恰好为 `vector` 故语义正确，但未实测） | 决定切换 `VEC_BACKEND=opensearch` 前是否要先做一次应用代码改动（不在本轮范围） | 你 / 开发方（先按 §9.2 ⑧ 做顺序对照） |

> **已在本次部署中定死、因此不再列为未确认**：部署形态（**tar + systemd，不用 Docker**）、机器（**`pdmsosgh` / x86_64**）、
> 版本（**OpenSearch 3.1.0 + Dashboards 3.1.0**）、节点数（**单节点**）、部署根（**`/home/opensearch/`**）、
> 建账号方式（**有家目录 + `passwd -l`**）、权限模型（**权限共享**，§2.1）、安全模式（**HTTP + basic auth**，§6.4）、
> Dashboards 账号（**用 `admin`**）、cron 归属（**root**）、logrotate（**本机自建**）、防火墙（**firewalld**）、
> 副本数（**0**）、堆大小（**8g**）、是否需要 Dashboards（**需要**，§8）。

## 附录 A · 将来加第 3 台 → 3 节点集群（升级路径）

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
| 快照仓库 | 每台本地 `fs` 仓库 | 建议共享一个（NFS）或对象存储 | §8.2 |

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

---

## 附录 B · 变更项（**不属于本次部署步骤**）

> 本附录只登记"将来要加固/改变基线时才会做的事"。**主线（§0~§7）里不出现这些分支**，照主线做即可。
> 每项都写清"什么时候才考虑 + 改了会动到哪些文件"。

### B.1 收紧 `/home/opensearch` 权限（放弃权限共享）

**什么时候考虑**：不再允许 `lhim` 直接往服务目录写（换人运维、或安全基线要求隔离）。
**要改什么**：

```bash
# 目录回到只有服务账号可写（部署根本身仍留可穿过）
sudo chmod 2750 /home/opensearch/{config,data,logs,backup} /home/opensearch/backup/fs-repo
# ★文件权限同样【只限运行期目录】——绝不要对 app/ 递归 chmod（会抹掉执行位 ⇒ status=203/EXEC，见 §4.5）
sudo find /home/opensearch/config /home/opensearch/data /home/opensearch/logs /home/opensearch/backup \
     -type f -exec chmod 640 {} +
sudo rm -f /etc/profile.d/lhim-umask.sh                      # 不再需要 umask 002
# 之后传文件改走"以服务账号身份"：
sudo -u opensearch -s      # 进去后用普通 cp 落位，属主天然是 opensearch
```
**代价**：`lhim` 不能再用终端工具直传；每次落位都要 `sudo` 或切账号。

### B.2 以服务账号干活（不动任何权限，落位最省事）

**什么时候考虑**：不想为了传文件而开组写；或落位动作频繁（改配置、补证书、换版本）。
**做法**：把资产交给服务账号，然后**以它的身份**操作——所有 `cp`/`mkdir`/`chmod` 都在它自己的家目录里完成，**不需要 `sudo`**。

```bash
sudo cp -r <你的资产目录>/deploy /home/opensearch/
sudo chown -R opensearch:opensearch /home/opensearch/deploy
sudo -u opensearch -s                      # 之后所有文件操作都以 opensearch 身份进行
# （进去后）
cd /home/opensearch
cp deploy/opensearch/opensearch.yml.example config/opensearch.yml
mkdir -p config/jvm.options.d
cp deploy/opensearch/jvm.options.d/heap.options config/jvm.options.d/
cp deploy/opensearch/snapshot-backup.sh config/ && chmod 750 config/snapshot-backup.sh
cp config/opensearch.yml app/config/opensearch.yml
mkdir -p app/config/jvm.options.d && cp config/jvm.options.d/heap.options app/config/jvm.options.d/
exit
```

**两个必须记住的点**：
1. 两个 systemd unit **仍必须 root 落位**：`sudo install -D -o root -g root -m 644 <源> /etc/systemd/system/<unit>`；
2. `cp` 会保留源权限位 —— 若源是 `644`，进去后补一条 `chmod 640`（或直接用 `install -m 640`）。
### B.3 单独收紧 `data/`（保留共享，但防误删索引数据）

**什么时候考虑**：仍用权限共享，但不想让 `lhim` 误删/误覆盖索引数据。
**要改什么**：`sudo chmod -R 2750 /home/opensearch/data`（**只这一个目录**，其余仍按 §2.1 共享）。
**注意**：做完后 `lhim` 不能再往 `data/` 传文件；备份/恢复走 §9 的快照流程，不需要人工往 `data/` 放东西。

### B.4 给 Dashboards 改用专用只读账号（不用 `admin`）

**什么时候考虑**：安全基线要求"服务账号最小权限"。
**要改什么**：在 `app/config/opensearch-security/` 下加内部用户与角色
（最小集合：可读写 `.opensearch_dashboards*`、只读业务索引），
用 `app/plugins/opensearch-security/tools/hash.sh` 生成口令哈希，再跑
`securityadmin.sh -cd … -icl -nhnv` 重载配置；最后改
`dashboards/app/config/opensearch_dashboards.yml` 的 `opensearch.username/password`。
**注意**：`securityadmin.sh` 的证书路径以 `app/config/*.pem` 实际文件名为准（§6.2 生成的那套）。

### B.5 REST 层改走自签 TLS（等保要求传输加密时）

**什么时候考虑**：合规明确要求"管理面传输加密"。
**要改什么**：把 `config/opensearch.yml` 的 `plugins.security.ssl.http.enabled` 改成 `true`，
并补 `plugins.security.ssl.http.pemcert_filepath` / `pemkey_filepath` / `pemtrustedcas_filepath`（证书就是 §6.2 生成的那套）；
AI 侧 `OPENSEARCH_URL` 由 `http://` 改 `https://` 并让它信任该 CA；
Dashboards 的 `opensearch.hosts` 同步改 `https://`，并配 `opensearch.ssl.certificateAuthorities` 与
`opensearch.ssl.verificationMode`。
**代价**：自签证书有有效期，到期不换会全线连不上；证书轮换要重启服务。

### B.6 日志轮转交给平台方统一采集

**什么时候考虑**：机房已有统一日志平台（集中采集 + 轮转）。
**要改什么**：删掉本机自建的 `/etc/logrotate.d/opensearch`（§9.3），改由平台方采集。
**验收口径不变**：`du -sh /home/opensearch/logs` 不单调增长，`df -h /home` 有足够余量。

### B.7 加第 3 台 → 3 节点集群

见 **附录 A**（只改两个参数）。

---

## 附录 C · 官方文档逐条核对（官方怎么写 → 我们原来怎么写 → 现在怎么写）

> 核对对象：官方 Tar 安装页、安全插件启停页、demo 配置页、安全设置页（链接见文首）。
> **"原来"指本文件的 Docker 版初稿与本仓库当时的资产。**

| # | 项 | 官方怎么说 | 我们原来怎么写 | 现在改成什么 |
| --- | --- | --- | --- | --- |
| 1 | **默认 REST 层是 HTTPS** | Tar 页 Step 3 的验证命令是 `curl -X GET https://localhost:9200 -u 'admin:<password>' --insecure`；demo 脚本"applies self-signed TLS certificates" | 写的是"内网 HTTP + basic auth"，**没有显式处理 TLS** | **第四章 §4.4** 定为写法：**HTTP + basic auth**，在配置资产里**显式写下** `plugins.security.ssl.http.enabled: false`（自签 TLS 的改法移到**附录 B**，不在部署步骤里） |
| 2 | **口令复杂度** | demo 配置页四条：8~100 字符；四类字符各至少一个；**zxcvbn 达 strong 或以上**；**不得与用户名 admin 相似** | 只写了">=8 位 + 四类字符齐全" | **§4.2** 按官方**四条**逐字写全，并说明"四类字符齐全也可能被拒" |
| 3 | **`DISABLE_INSTALL_DEMO_CONFIG=true` 的后果** | 镜像/安装脚本 README 的 Scenario 3：跳过 demo 脚本 ⇒ **没有证书与安全配置**，安全插件开着就会**启动失败** | Docker 编排里设了 `DISABLE_INSTALL_DEMO_CONFIG: "true"` 且同时启用安全插件 —— **这是错的，会起不来** | **删掉 Docker 路线**；tar 路线改为"**让 demo 脚本正常跑**（拿到证书与 admin 用户），用强口令保证口令安全"，并在 §3.2/§3.3 讲清顺序 |
| 4 | **`OPENSEARCH_INITIAL_ADMIN_PASSWORD` 的用法** | tar 路线：先 `export OPENSEARCH_INITIAL_ADMIN_PASSWORD=<强口令>`，再 `./opensearch-tar-install.sh`；2.12 起不设会失败 | 把它当成 Docker 的环境变量写在 compose 里 | 改为 **§4.2** 的 `export` + 脚本（tar 语汇），并说明它只在**首次**生效 |
| 5 | **安装脚本的实际作用** | `opensearch-tar-install.sh` 会"apply a generic configuration… defines some environment variables and also applies self-signed TLS certificates"，并把安全设置写进 `config/opensearch.yml` | 没提这个脚本 | **§4.2/§4.3** 说明它的作用与"改了 `opensearch.yml`"，并强制**紧接着**用我们的配置接上（先备份 `config/opensearch.yml.demo.bak`） |
| 6 | **transport 层证书不能漏** | demo 脚本写入的 `opensearch.yml` 含 transport 证书路径；安全插件默认要求 transport 加密（缺证书 ⇒ 集群起不来 / 报 `Transport client authentication no longer supported`） | 我们的配置模板**只有** `plugins.security.ssl.http.enabled`，把 transport 段丢了 | 配置资产补回 `plugins.security.ssl.transport.pemcert_filepath` / `pemkey_filepath` / `pemtrustedcas_filepath` + `allow_unsafe_democertificates` / `allow_default_init_securityindex`，并解释**为什么不能整份覆盖成"干净配置"** |
| 7 | **`vm.max_map_count` 与 swap** | Tar 页 Step 2：`sudo swapoff -a`；`vm.max_map_count=262144`（官方示例改 `/etc/sysctl.conf` 后 `sysctl -p`，再 `cat /proc/sys/vm/max_map_count` 校验） | 有（写了 `sysctl.d` 与重启校验） | 与官方一致；保留 `sysctl.d` 写法（更不容易和别人的行冲突）并补上 `cat /proc/sys/vm/max_map_count` 这条官方校验 |
| 8 | **systemd unit 示例** | 官方给了完整示例：`Type=forking`、`ExecStart=/opt/opensearch/bin/opensearch -d`、`User/Group=opensearch`、`StandardOutput=journal`、`LimitNOFILE=65535`、`LimitNPROC/LimitAS/LimitFSIZE`、`TimeoutStopSec=0`、`KillSignal=SIGTERM`、`KillMode=process`、`SendSIGKILL=no`、`SuccessExitStatus=143`、`TimeoutStartSec=75`；并提醒 **tar + systemd 非生产推荐**（要 systemd 就用 RPM） | 原来用 Docker，**没有 unit** | 资产 [`opensearch.service`](../deploy/opensearch/opensearch.service) **照官方示例改写**，只改三处：路径→`/home/opensearch/app`、保留 User/Group 语义、**补 `LimitMEMLOCK=infinity` + `Environment=MAX_MAP_COUNT=262144`**，并保留官方的"非生产推荐"提醒；unit 由 **§2.2** 落位、**§5.1** 启动 |
| 9 | **memlock 与 `MAX_MAP_COUNT`** | 官方 configuring-opensearch：systemd 托管且开 `bootstrap.memory_lock` 时，需要放开 memlock 并设 `MAX_MAP_COUNT=262144` | 原 compose 用 `ulimits.memlock: -1`（容器语汇） | 改为 unit 的 `LimitMEMLOCK=infinity` + `Environment=MAX_MAP_COUNT=262144`，并在 §2.2/§1.2 A4 给出验证命令 |
| 10 | **专用非 root 用户** | Tar 页：`sudo adduser --system --shell /bin/bash -U --no-create-home opensearch`；`sudo usermod -aG opensearch $USER`；`sudo chown -R opensearch /opt/opensearch/`；OpenSearch 不能以 root 运行 | 原方案靠容器内 uid 1000（宿主 `chown 1000:1000`） | §2.4/§3.4 全部改为**系统用户 `opensearch` + `chown -R opensearch:opensearch`**，并给"权限不对"的排障行 |
| 11 | **端口 9600** | 官方（Docker 与 tar）都提 `9200`（REST）与 `9600`（Performance Analyzer） | 原来只讨论 9200，9600 未表态 | **§3.5** 明确：**9600 不放行**（本方案不用 Performance Analyzer） |
| 12 | **数据/日志/备份路径与属主** | 官方 tar 路线不规定安装路径（"The tarball installation does not define a specific installation path, users, roles, or permissions"），但要自己保证权限正确；官方示例把 `data` 放在安装目录下 | 原来绑定宿主目录 `1000:1000`（容器语汇） | §0.2/§2.4 定稿 `/home/opensearch/{app,config,data,logs,backup}`，**data/logs 放在安装目录之外**（升级不碰数据），属主 `opensearch:opensearch`、权限 750（文件 644 / keystore 600） |
| 13 | **升级与"复制 config"** | 官方 tar 升级思路是"换程序目录、带上你自己的 `config/`"（安全配置与证书都要自己接管） | 原来是 `docker load` 换 tag；升级段只写了"把证书/`opensearch-security/`/keystore 也带过去"，**没有属主与权限** | §8.3 改为 stop → 解包新目录 → **复制 `config/opensearch.yml` + `jvm.options.d/` + demo 生成的证书与 `opensearch-security/`** → **⑥ 明确属主与权限（`*.pem`/`opensearch-security/*.yml` = `640`、`keystore` = `600`、`tools/*.sh` = `750`，一律 `opensearch:opensearch`）** → chown/chmod → 换目录 → start；旧目录留作回滚。**升级后最常见的 644/640 事故就发生在这里** |
| 14 | **`plugins.security.ssl.http.enabled` 的默认值** | 安全设置页：该设置 "Default is `false`"，但 demo 脚本会把它设成 **true**（所以 quickstart 用 HTTPS） | 原来只有"TLS 可选"一句，说不清默认到底是什么 | **§4.4** 明确：字段默认 false，但 demo 配置会改成 true，因此我们**必须显式写 false** |
| 15 | **禁用安全插件的代价** | 启停页：禁用后"exposes the configuration index… If the index contains sensitive information, make sure to protect it"；安全设置页：`plugins.security.disabled` 的警告是"can expose your configuration (including passwords) to the public"；且**禁用/启用需要整集群重启** | 原方案把"禁插件"只当作"简单"选项 | §3.5 方案 C 明确列为**不采用**，并原样引用官方的暴露警告与"需要重启"这一操作代价 |
| 16 | **下载 URL（官方口径留档）** | 官方 Tar 页给的是 `wget https://artifacts.opensearch.org/releases/bundle/opensearch/<版本>/opensearch-<版本>-linux-x64.tar.gz`（**页面没有给 `.sha512` 的说明**） | 原文没有校验步骤 | 本机由运维在浏览器自行下载后放到 `/home/opensearch/`，**本次部署不做校验步骤**（包已在位，见 §1.1）；此处仅留官方 URL 形式备查。**不改动任何主线步骤****同目录下有 `.sha512`（165 字节文本，`sha512sum -c` 可直接吃），给出 Windows/Linux 两种校验命令 + "校验不通过绝不安装" |
| 17 | **架构** | 官方按 x64 / arm64 分别给下载命令 | 原方案假设这两台是 aarch64（银河麒麟） | §0.2 明确这两台是 **x86_64**，用 `linux-x64.tar.gz`；并提醒别和既有 aarch64 服务器的产物搞混 |
| 18 | **`jvm.options.d` 覆盖目录** | 官方 Tar 页 Step 4 是让你直接改 `config/jvm.options` 里的 `-Xms/-Xmx` | 原来把堆写在容器环境变量 `OPENSEARCH_JAVA_OPTS` 里 | **§4.3** 用 `config/jvm.options.d/heap.options`（**升级时 `jvm.options` 会被新版本覆盖，我们的文件不会**），并在资产里解释为什么是 8g |
| 19 | **快照仓库位置** | 官方快照文档要求仓库 location 在 `path.repo` 之下 | 原方案把 `path.repo` 与宿主目录直接对应（容器语汇） | §8.2 明确 `path.repo: /home/opensearch/backup` 与脚本 `REPO_LOCATION=/home/opensearch/backup/fs-repo` 的**父子关系**，并给出 `repository_exception` 的排障行 |
| 20 | **专用账号的建法（D 方案偏离官方）** | 官方 Tar 页给的是 `sudo adduser --system --shell /bin/bash -U --no-create-home opensearch`（**无家目录的系统账号**） | 上一版照官方写 | **D 方案要求部署根就是该账号的家目录**，所以改为 `sudo useradd -m -d /home/opensearch -s /bin/bash opensearch` + `sudo passwd -l opensearch`（**有家目录、锁登录**）。这是**有意偏离官方示例**，原因写在 §2.4 |
| 21 | **`/home` 挂载前提** | 官方 tar 页只要求 `vm.max_map_count`/swap，**不规定安装路径**（"does not define a specific installation path"） | 上一版把部署根放在 `lhim` 家目录下，并要求 `chmod 711 /home/lhim` | 部署根改为 `opensearch` 自己的家目录 ⇒ **删掉 `chmod 711` 那条**；新增 **§2.4.2 `/home` 挂载前置检查**（`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home`：确认本地盘/非 NFS/空间够，并给 `path.data` 退路；说明 **`nosuid`/`nodev` 无害**，只有 `noexec` 会挡路） |
| 22 | **Dashboards 版本兼容** | 官方 Dashboards tar 页是独立页，**没有明写"必须与 OpenSearch 同版本"**；但官方版本矩阵与下载页都要求两者版本一致 | 上一版根本没提 Dashboards | **§7 与 §1.1** 把"**三个数字都要一样**（`3.1.0`）"写成硬约束；两个包已在位，Dashboard 的安装从 §7.1 开始（下载与校验不在本次步骤内） |
| 23 | **Dashboards 配置文件字段名** | 官方示例 `config/opensearch_dashboards.yml`：`server.host`（默认 `"localhost"`，"usually means remote machines will not be able to connect"）、`server.port`、`opensearch.hosts`、`opensearch.username/password`、`opensearch.requestHeadersWhitelist`（**已标 DEPRECATED**，新名 `opensearch.requestHeadersAllowlist`）、`opensearch.ssl.verificationMode`、`opensearch.memoryCircuitBreaker`；多租户键 `opensearch_security.multitenancy.enabled` 出现在安全插件文档与官方示例注释里 | 上一版没有 Dashboards 配置 | §7.1 逐项照官方字段名写进资产 `opensearch_dashboards.yml.example`：用**新名** `requestHeadersAllowlist`（并在注释里留旧名对照）、`server.host` 显式改内网 IP、`opensearch.hosts` 用 `http://`（与 §3.5 方案 A 绑定）、多租户项保留并注明"与 workspace 互斥" |
| 24 | **Dashboards 的 systemd** | 官方 Dashboards tar 页**只给三步**（解包 → 改 yml → `./bin/opensearch-dashboards`），**没有 systemd 示例** | 上一版没有 | §7.4 + 资产 `opensearch-dashboards.service`：**骨架照官方 OpenSearch tar 页那份 unit**，按 Node.js 运行方式改 3 处并写明原因（`Type=simple` 而非 `forking`、`KillMode=mixed` 以收掉 Node 子进程、`Environment=NODE_OPTIONS=--max-old-space-size=1024`） |
| 25 | **Dashboards 的 Node 堆默认值** | 官方 `config/node.options` 里给的示例是 **`--max-old-space-size=4096`**（默认 4GB 老生代）；官方示例 yml 提到 `opensearch.memoryCircuitBreaker` 的阈值基于该值 | 上一版没有 | **§7.2** 明确"默认 4GB、16GB 机器上必须封顶"，本方案在 unit 里压到 **1024MB**，并给出"官方支持两种设置方式（`config/node.options` 或 `NODE_OPTIONS`），我们选后者"的取舍 |
| 26 | **快照默认不含系统索引（脚本级修正）** | 官方快照/恢复文档说明：恢复快照时不能包含全局状态、且**不恢复 `.opendistro_security`**（除非 admin 证书签名）⇒ 系统索引默认不在快照里 | 上一版脚本只备 `pm-ai-chunks` ⇒ 恢复后 Dashboards 空白、安全配置回默认 | §7.6 + 脚本：默认索引列表改为 `pm-ai-chunks,.opensearch_dashboards*,.opendistro_security`；给出三条验证命令（快照后看 `indices`、恢复后看 `_cat/indices`、浏览器里看仪表盘） |
| 27 | **k-NN 引擎可用性（3.x）** | `docs/latest` 的 Methods and engines 页：引擎有 **Lucene / Faiss / NMSLIB（legacy，标注 "now deprecated"）/ JVector**；`engine` 省略时**默认 `faiss`**；Lucene 支持 `hnsw`（space：`l2`/`cosinesimil`/`innerproduct`），Faiss 支持 `hnsw`/`ivf`，其中 **`cosinesimil` 对 Faiss 是 2.19+**，且"Faiss 用 `cosinesimil` 时向量会被自动归一化 ⇒ 存储值与你输入的不同"；另有 **JVector（`disk_ann`，需单独插件 `opensearch-jvector`，不能与 opensearch-knn 同集群）** | 上一版把 `engine` 写成"可选 lucene/faiss"，并说"nmslib 需要额外参数"，没说清楚 3.x 的取舍 | §6.3 重写：**默认用 `lucene`**（与代码 `OpenSearchIndex` 一致、支持带过滤检索、官方定位"smaller deployments / <1000 万向量"）、`faiss` 作为可选并**明确写出归一化的副作用**、**3.x 新索引不要用 `nmslib`**、并登记 JVector 这条路（未采用，需额外插件） |
| 28 | **k-NN mapping 的键名与默认值** | k-NN vector 页：`dimension` 合法区间 **[1, 16000]**；`data_type` 默认 `float`（可选 `binary`/`byte`/`float`/`half_float`）；`space_type` **既可写在字段层也可写在 `method` 内**（"This value can also be specified within the `method`"），默认 `l2`；`mode` 默认 **`in_memory`**（`on_disk` 为低成本路线，配合 `compression_level`，默认 `32x`、默认开启 rescore）；方法参数键名是 **`parameters`**（不是 `params`）；`m` 默认 **16**、`ef_construction` 默认 **100** | 上一版把 `space_type` 同时写在字段层与 `method` 内（两种写法混用），`ef_construction` 写 128 没说这是"偏离默认"，也没提 `dimension` 上限与 `mode` 默认值 | §6.2 改为：`space_type` **只写字段层**、`data_type: float` 显式写出、`method` 内只留 `name`/`engine`/`parameters`；§6.3 补 `dimension` 上限 16000（我们的 4096 合规）、`mode` 默认 `in_memory`（故不写）、`m`/`ef_construction` 与官方默认值的对照 |
| 29 | **k-NN 查询语法（3.x）** | k-NN query 页的请求体结构：**`"knn": { "<vector_field>": { "vector": [...], "k": n, "filter": {...}, "method_parameters": {...}, "rescore": {...} } }`** —— **字段名直接是 `knn` 下第一层**；`filter` **只能配合 `faiss`/`lucene`**；`k` 合法区间 [1, 10000]；`method_parameters.ef_search` 对 **lucene** 的行为是"与 `k` 取较大者传给引擎" | 上一版按 2.x 的固定键结构写（`knn.vector.vector`），没有区分"结构里的 `vector` 是键名还是字段名" | §6.5 ① 加了 3.x 语法说明（`knn` 下第一层是**字段名**；我们字段名恰好也叫 `vector`），② 明确 `filter` 的引擎前提；并在 §6.5 末尾**如实登记一处不一致**：仓库适配层 `OpenSearchIndex.search()` 仍按 2.x 固定结构拼查询体，**因字段名巧合为 `vector` 而语义仍正确**，但上线前必须按"top-k 顺序与 local 一致"实测（改适配层属应用改动，登记在 §9） |
| 30 | **快照请求体字段（3.x）** | Create Snapshot API 页：请求体字段**只有** `indices`（支持 `,` 列表与 `*` 通配与 `-` 排除，**"Don't put spaces between items"**）、`ignore_unavailable`（默认 false）、`include_global_state`（默认 true）、`partial`（默认 false）；**没有 `expand_wildcards`**。官方示例本身就用了通配 + 系统索引：`{"indices": "opensearch_dashboards*,my-index*,-my-index-2016", ...}` | 上一版脚本在请求体里加了 **`"expand_wildcards": "all"`**，并声称"通配符必须显式展开"——**这是错的**（该参数不属于这个 API） | 脚本**移除 `expand_wildcards`**（注释留档说明为什么不能加回来）；`indices` 直接用逗号 + `*`（官方原生支持）；文档 §7.6 同步改正 |
| 31 | **安全插件键与 tar 安装脚本（3.x 是否仍存在）** | 3.x 的 Tar 页与 security-settings 页：`opensearch-tar-install.sh` **仍存在**（Step 3 Option 1 仍叫这个名字，且仍会"apply self-signed TLS certificates"并改 `config/opensearch.yml`）；`OPENSEARCH_INITIAL_ADMIN_PASSWORD` **仍是 2.12+ 的必需项**；口令四条要求（8~100 字符、四类字符各一、zxcvbn 达 strong 以上、不得与 admin 相似）在 3.x 文档里**未变**；`plugins.security.ssl.http.enabled`、transport 三个 `pem*_filepath`、`allow_unsafe_democertificates`、`allow_default_init_securityindex` 这些键**在 3.x 文档里都还在**（且 Tar 页的追加脚本仍然写 `plugins.security.allow_default_init_securityindex: true`） | 上一版已按 2.x 写对；但没说明"3.x 是否仍成立" | §3.2/§3.5 内容**不变**，在附录 B 这里登记"已按 3.x 复核、四项键与口令要求均未变" |
| 32 | **heap 覆盖目录 `jvm.options.d/` 与自带 JDK（3.x）** | 3.x Tar 页：**"The tarball is a self-contained directory with everything needed to run OpenSearch, including an integrated Java Development Kit (JDK)"**；Step 4 给的是 `export OPENSEARCH_JAVA_HOME=/path/to/opensearch-<版本>/jdk`；调整堆的官方写法是**改 `config/jvm.options`**（页面未提 `jvm.options.d`） | 上一版声称 `jvm.options.d/` 是"官方支持的覆盖目录" | §3.3 保留 `jvm.options.d/heap.options` 做法，但**改口径**：把它写成"**我们的做法**（借 JVM 官方启动脚本会读取 `jvm.options.d/*.options` 的机制）"，并注明**官方 Tar 页只写了改 `config/jvm.options`**；同时写入 **3.x 自带 JDK**（`app/jdk`）与 `OPENSEARCH_JAVA_HOME` 的设置方式，明确"宿主不需要预装 Java" |
| 33 | **systemd 示例（3.x 是否与 2.x 相同）** | 3.x Tar 页的 systemd 一节与 2.x **逐行相同**（`Type=forking`、`ExecStart=/opt/opensearch/bin/opensearch -d`、`User/Group`、`StandardOutput=journal`、`LimitNOFILE=65535`、`LimitNPROC/LimitAS/LimitFSIZE`、`TimeoutStopSec=0`、`KillSignal=SIGTERM`、`KillMode=process`、`SendSIGKILL=no`、`SuccessExitStatus=143`、`TimeoutStartSec=75`），并且**同样标注"只适合非生产、要 systemd 就用 RPM"**；Dashboards tar 页在 3.x **仍然只给三步、仍没有 systemd 示例** | 上一版已照该示例写 | **保持**（`opensearch.service` 不动逻辑），只在附录 B 登记"3.x 逐行相同"；Dashboards 的 unit 继续按"照官方 OpenSearch unit + 按 Node 运行方式改 3 处"的口径，并在 §7.4 与 unit 注释里标明"官方无示例" |
| 34 | **Dashboards 的 yml 键与 Node 堆默认值（3.x）** | 3.x 官方示例 `config/opensearch_dashboards.yml`：`opensearch.requestHeadersWhitelist` **仍标注 DEPRECATED**（新名 `requestHeadersAllowlist`）、`opensearch_security.multitenancy.enabled` 仍存在且官方注释仍写"**workspace 与多租户互斥**"；`config/node.options` 的示例默认**仍是 `--max-old-space-size=4096`**（`--max-semi-space-size=64` 注释保留） | 上一版已按这套写 | **保持**，附录 B 登记"3.x 复核：键名/互斥关系/4096 默认值均未变" |
| 35 | **资产落位与权限（官方未覆盖，属本项目运维约定）** | 官方 Tar 页只说"不定义安装路径/用户/权限，要自己保证正确"；安全插件文档要求配置不得"组/其他人可写"；systemd 要求 unit 属主为 root | 前几版把落位/属主/权限散落多处，**出现两套说法**（`644` 与 `640` 混用；旧版还写了服务器上不存在的相对路径 `deploy/…`） | **§2 收敛为一套**：`deploy/opensearch/` 下 6 个资产的落位表（目标路径 + 属主:组 + 权限）、运行期落位表（含 keystore `600`、`tools/*.sh` `750`、口令文件 `600`）、**7 条核对命令**；权限按本机采用的**共享模型**：目录 `2775` / 文件 `664` / 私钥 `640`；两个 unit 仍 `root:root 644` |

## 11. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| docs-1.6 | 2026-09-23 | 首版（**已被下方"修订二"取代**）：为两台新服务器出 OpenSearch 部署与接入文档 + 部署资产，当时定稿**单节点 + Docker（`docker load`）**路线 |
| docs-1.6（修订二） | 2026-09-23 | **部署形态改为官方 `tar.gz` 解包 + systemd（不用 Docker）**（新机器没装 Docker）：删除全部 Docker 路线资产（compose / `.env.example` / `os.sh`），新增 `opensearch.yml.example` / `jvm.options.d/heap.options` / `opensearch.service` / `snapshot-backup.sh`；两处决策级修正：**架构 x86_64**、**按官方文档逐条对齐**（默认 REST 层是 HTTPS ⇒ 必须显式关 TLS；口令复杂度补 zxcvbn 与"不得与 admin 相似"；`DISABLE_INSTALL_DEMO_CONFIG=true` 会让节点起不来 ⇒ 改为让 demo 脚本正常跑；transport 证书不能漏；systemd unit 照官方示例并补 `LimitMEMLOCK`/`MAX_MAP_COUNT`；升级路径改为换程序目录 + 复制 `config/`）。逐条核对表见 **附录 B** |
| docs-1.6（修订三） | 2026-09-23 | **部署根定为 `/home/opensearch/`（D 方案，= 专用账号 `opensearch` 自己的家目录），内部结构规范成"程序/配置/数据/日志/备份/Dashboards"分离；新增 OpenSearch Dashboards（§7）**。① **目录**：`app/`（当前程序）、`app-<旧版本>/`（回滚点）、`config/`（我们维护的 `opensearch.yml` + `jvm.options.d/heap.options` + `snapshot-backup.sh`）、`data/`、`logs/`、`backup/fs-repo/`、`dashboards/app/`。② **建账号方式改掉**：不用官方那种"系统账号 + `--no-create-home`"，改为 `useradd -m -d /home/opensearch -s /bin/bash opensearch` + `passwd -l`（有家目录、锁死登录）。③ **删掉上一版的 `/home/lhim` 穿透权限（`chmod 711`）那一节**（D 方案下不再需要，留着会误导），换成 **§2.4.1 家目录可用性检查**（`sudo -u opensearch ls/touch` 自证）。④ **新增 §2.4.2 `/home` 挂载前置检查**（`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home` 确认本地盘、非 NFS/autofs、空间够；给出"只把 `path.data` 挪到 `/data/opensearch`"的既定退路；并说明 **`nosuid`/`nodev` 对本服务无害、不要为此改挂载参数**，真正会挡路的只有 `noexec`）。⑤ **升级流程**按新结构重写（解包到 `app-<版本>/` → 拷 `config/` 与证书/`opensearch-security/` → 停服 → 旧 `app/` 改名保留、新目录改名 `app/` → `chown -R` → 启动 → 验收；回滚即反操作）。⑥ **Dashboards（§7）**：版本必须与 OpenSearch 完全一致（`3.1.0`）、下载 + sha512 校验 + **归档 tar 到 `.111`**、解包到 `dashboards/app/`、`opensearch_dashboards.yml` 关键项逐条给依据（`server.host` 绑内网、`opensearch.hosts` 与 REST TLS 取舍绑定、只读账号优先、`requestHeadersAllowlist` 必须含 `authorization`、多租户与 workspace 互斥）、systemd unit（照官方 OpenSearch 那份示例改 3 处：`Type=simple`/`KillMode=mixed`/`NODE_OPTIONS` 堆上限）、内存预算（Node 约 0.6~1.2GB，标注估算）、5601 只放行运维网段、**冷备结论（不需要单独冷备）**，以及 **⚠️ 快照必须显式包含系统索引**（`snapshot-backup.sh` 默认列表已改为 `pm-ai-chunks,.opensearch_dashboards*,.opendistro_security`）。⑦ 集群名 `pm-os` 与节点名 `os01` **保持不变** |
| docs-1.6（修订四） | 2026-09-23 | **版本升 3.1.0（按 3.x 官方文档重核，含 k-NN 引擎/查询语法变更）+ 安装包目录统一 `/home/opensearch/`**。① **版本**：全文版本号统一到 **`3.1.0`**（原为上一代版本）（OpenSearch 与 Dashboards 同版本），包名/体积按真机实际写（OpenSearch **1,000,330,285 字节**、Dashboards **385,456,866 字节**）；**上一版固化的上一代版本 SHA512 作废**，改为"**以现场 `.sha512` 为准**"（本轮无法联网核实 3.1.0 的校验和）。② **安装包目录**：`scp` 目标从 `/tmp` 统一到 **`/home/opensearch/`**，并把**跨用户/跨家目录拷贝**写成显式前置（新增 §1.3：为什么 `lhim` 直接 `scp` 会 `Permission denied`；做法 A `usermod -aG opensearch lhim` + `chmod 2775`（setgid 继承组）并写明代价；做法 B `install -o opensearch -g opensearch -m 644`；并提醒**不要** `sudo -u opensearch cp /home/lhim/…`）；**解压后必须 `chown -R opensearch:opensearch`**；新增 **§2 占用与清理**（两包合计 ≈1.32 GiB；验收通过后 `.110` 可删、`.111` 留归档）。③ **3.x 重核**（附录 B 新增第 27~33 条）：**k-NN 引擎**（3.x 不要用 `nmslib`；`engine` 省略时默认 `faiss`；`cosinesimil` 对 lucene 一直支持、对 faiss 是 2.19+；维度上限 16000；`method.parameters` 而非 `params`；`mode` 默认 `in_memory`）、**查询语法**（3.x 是 `"knn": { "<字段名>": {...} }`，并如实登记"仓库适配层按 2.x 固定结构拼查询体、因字段名恰好叫 `vector` 语义仍正确但需实测"）、**快照请求体**（官方 Create Snapshot API **没有 `expand_wildcards`**，已从脚本移除；`indices` 原生支持 `*`）、安全插件键与 tar 安装脚本/口令四条要求**在 3.x 未变**、`jvm.options.d` 机制仍受支持、自带 JDK 由包内提供、systemd 示例 3.x 与 2.x 相同、Dashboards 的 yml 键与 `node.options` 默认 4096 **在 3.x 仍成立** |
| docs-1.6（修订五） | 2026-09-23 | **补"仓库资产落位对照表"（属主/权限/`install` 命令 + 两条省事路径）**：真机上卡在"父目录能写、`config` 不能写、`cp` 进去属主变 `lhim`"——新增落位表（源→目标→属主:组→权限，6 个资产）、三条纪律、核对命令、两条省事路径（不动权限的"以服务账号干活" / 权限共享），并写明权限共享的代价；权限统一到 **配置 `640` / 脚本 `750` / unit `root:root 644`** |
| docs-1.6（修订五·补） | 2026-09-23 | 补齐 7 处"文件落位"缺口：**Dashboards 配置节的仓库相对路径错误修正**、`dashboards/app/` 属主、`backup/fs-repo` 进表、`snapshot-backup.env`（`600`）、`tools/*.sh` 的 `x` 位、**升级段补证书/keystore 的属主与权限**、`/etc` 两处进表；并加"目录 `w` vs 文件 `w`"的最小权限解释 |
| docs-1.6（修订五·收口） | 2026-09-23 | 收口 4 条：**cron 落位与口令自洽**（新增 cron 子节：三者必须对齐，否则备份每天静默失败）、`opensearch.keystore`（`600`）与 `opensearch-security/` 目录（`750`）进表、**日志轮转（logrotate）**建议与"磁盘满"排障交叉引用、`backup/dist/` 一行 |
| docs-1.6（修订六） | 2026-09-23 | **主线去分支，按真机现状写成一条路**（本次）。① **删除"校验"整块**：`sha512sum -c`、`.sha512`、Windows `Get-FileHash` 那套全部移出主线（官方 URL 口径只在附录 C 留档，且不指向主线步骤）。② **删除"过程性/搬运"叙述**：浏览器下载 → `/home/lhim/software` → 复制到 `/home/opensearch` 的链路、`scp`、跨用户拷贝、`/tmp` 中转、`install -D`/`chown` 的"怎么搬进去"全部删除；**主线直接从现状开始**（"两个安装包已在 `/home/opensearch/`，`app/` 已解压完成"）。③ **每步状态标注**：（当前状态：已完成）/（待执行）/（将来执行）。④ **权限改为写法**：本机采用**权限共享**（`find -type d 2775` / `-type f 664` / `*.pem 640` / `umask 002` / 断连重连），删掉"路线一/二"与"data/ 建议收紧"等并列说法（收紧移到附录 B）。⑤ **安全模式唯一**：HTTP + basic auth（自签 TLS 移到附录 B）。⑥ **Dashboards 账号用 `admin`**（只读账号移到附录 B）。⑦ **备份 cron 口径**：`root` 的 crontab + `/etc/opensearch-backup.pass`（`root:root 600`）。⑧ **logrotate 本机自建**（平台方统一采集移到附录 B）。⑨ **防火墙以 firewalld 为主线**，iptables 仅作为同一代码块内的注释。⑩ **章节重排**：现状 → 权限 → 宿主准备 → 落位 → 安装 → systemd 验收 → 索引与检索 → Dashboards → 运维（含快照/cron/防火墙）→ AI 接入 → 未确认项；**附录 A（3 节点）/ 附录 B（变更项，原"将来加固"内容）/ 附录 C（官方逐条核对 35 条）**。全文 2261 → 约 1720 行 |
| docs-1.6（修订七） | 2026-09-23 | **按"先收暴露面、再起服务"调顺序 + 去强调词**：① **防火墙从运维章末提前到宿主准备章**（现为 §3.5），§3.7 跨机可达与 §3.8 自检都引用它，并加"顺序原则"说明；② §4.4 标题去掉"唯一"字样，正文里"唯一写法/唯一口径/唯一路径"等**强调词全部去除**（靠内容表达）；③ §2.1 的衔接说明写明确：**要覆盖已存在的配置文件时，在终端工具里先删除再上传**（目录组可写 ⇒ 能删），然后重跑 §2.3 收回 `640`，并指路 **附录 B.2「以服务账号干活」**；④ **附录 B 新增 B.2「以服务账号干活」**（原 B.2~B.6 顺延为 B.3~B.7）；⑤ §5.4 单节点故障演练**保留在主线、按必做**（新装无数据，停机成本为零，是验证"重启能自愈"的唯一手段）；⑥ `snapshot-backup.env` 保留（"用不到就别建"是事实陈述） |
| docs-1.6（修订八） | 2026-09-23 | **修一条范围写错的命令（真故障）**：上机时 `systemctl start opensearch` 连续失败，`journalctl` 报 `Control process exited, code=exited, **status=203/EXEC**`。**根因是文档的错**：§2.1 原文 `sudo find /home/opensearch -type f -exec chmod 664 {} +` **递归抹掉了程序目录的执行位**（`app/bin/opensearch`、`app/jdk/bin/*`、`app/jdk/lib/jspawnhelper`、`app/plugins/**/tools/*.sh`），JVM 与启动脚本无法执行。① **§2.1 改为"只作用于运行期目录"**（`config`/`data`/`logs`/`backup`），并**明确禁止**对 `app/` 或 `dashboards/app/` 套用 `chmod 664`；② **新增 §4.5「程序目录可执行位恢复」**（三条 `chmod 750` + `daemon-reload` + `restart` + 四条复核，含"从 tar 包重新解压程序目录"的更稳替代）；③ **§8.5 新增 `status=203/EXEC` 排障条目**（含义：systemd 在 exec 阶段就失败 ⇒ 应用日志为空，别在 `logs/` 里找；四处日志 + 四类原因与判据）；④ **§2.5 核对新增"`app/bin/opensearch` 与 `jdk/bin/java` 必须带 `x`"**；⑤ **§7.1 加同款红线**（不要对 `dashboards/app/` 套 `chmod 664`）；⑥ **附录 B.1 的递归 `find` 限定到运行期目录**；⑦ 资产 `opensearch.yml.example` 顶部加"权限红线"注释、`opensearch.service` 去掉已废弃的 `chmod 711 /home/lhim` 历史注释、`opensearch_dashboards.yml.example` 的示例权限由 `644` 改 `640` |


