# OpenSearch 部署手册（10.254.212.110 · OpenSearch 3.1.0 + Dashboards 3.1.0 · tar.gz + systemd）

> **文档性质：傻瓜式部署手册。** 拿到一台符合 第 1 节前置条件的新机器 + 两个安装包，
> **从第 1 步按顺序做到最后一步即可部署成功**；不需要知道"别人做到哪了"。
>
> **怎么用这份文档（3 条）**
> 1. **按步骤号顺序执行**（第 1 步 → 第 N 步），不要跳步；每步只依赖它前面的步骤。
> 2. **每步都看 ② 期望输出 与 ③ 完成判据**：输出对得上、判据成立，才算这步过了，再往下走。
> 3. **出错看 ④ 失败了怎么办**（指向 参考 B · 常见故障 的具体条目），别自己猜。
>
> **总步骤清单（可勾选）**
>
> | ☐ | 步骤 | 标题 | 约耗时 |
> | --- | --- | --- | --- |
> | ☐ | 1 | 主机与前置条件核对 | 约 3 分钟 |
> | ☐ | 2 | 建立部署根与专用账号 | 约 2 分钟 |
> | ☐ | 3 | 放置安装包并解压程序目录 | 约 3~5 分钟 |
> | ☐ | 4 | 权限模型与目录属主 | 约 2 分钟 |
> | ☐ | 5 | 内核参数与资源限制 | 约 3 分钟（含重启约 2 分钟） |
> | ☐ | 6 | 关闭 swap 与内存锁定 | 约 2 分钟 |
> | ☐ | 7 | `/home` 挂载检查 | 约 1 分钟 |
> | ☐ | 8 | 防火墙（先收暴露面） | 约 3 分钟 |
> | ☐ | 9 | 时间同步 | 约 1 分钟 |
> | ☐ | 10 | 运行期目录与资产落位 | 约 3 分钟 |
> | ☐ | 11 | 运行官方安装脚本（证书 + 初始口令） | 约 2~3 分钟 |
> | ☐ | 12 | 用我们的配置接上它 | 约 3 分钟 |
> | ☐ | 13 | 装 unit 并首次启动 | 约 2 分钟（首次启动 30~90 秒） |
> | ☐ | 14 | 验收（6 项，全过才算成功） | 约 3 分钟 |
> | ☐ | 15 | 重启自愈与单节点故障演练 | 约 5 分钟 |
> | ☐ | 16 | 创建索引 `pm-ai-chunks` | 约 1 分钟 |
> | ☐ | 17 | 安装 OpenSearch Dashboards | 约 5 分钟 |
> | ☐ | 18 | 快照与每日备份（cron） | 约 5 分钟 |
> | ☐ | 19 | 接入 AI 服务（切 `VEC_BACKEND=opensearch`） | 约 5 分钟 + 重建索引视语料量 |
> | ☐ | 20 | 第二台 `10.254.212.111`（冷备 + 演练机） | 约 5 分钟/次 |
>
> **背景与取舍**（想先知道"为什么这么定"再看）：[`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md)、
> 本文 **附录 D**（结论与前提）、**附录 C**（官方文档逐条核对）。
> **关联资产**：[`deploy/opensearch/`](../deploy/opensearch/)（6 个文件，落位见 步骤 10）。
> **官方依据**：[Tar 安装页](https://docs.opensearch.org/latest/install-and-configure/install-opensearch/tar/)、
> [安全插件启停](https://docs.opensearch.org/latest/security/configuration/disable-enable-security/)、
> [demo 配置与口令要求](https://docs.opensearch.org/latest/security/configuration/demo-configuration/)、
> [安全设置](https://docs.opensearch.org/latest/install-and-configure/configuring-opensearch/security-settings/)。
>
> **本手册本身只写文档与部署资产**：`backend/**`、`frontend/**`、`scripts/**` 应用代码**一行未改**。
> 但 **步骤 19（AI 侧接入）依赖的应用代码已随 `ai-1.2.0` 落地**（`ai-backend/**`：OpenSearch 原生 REST 接入、
> 索引自建、`_bulk` 幂等、kNN 硬 filter、`/admin/reindex`、指标与比对工具；见 [`../ITERATION.md`](../ITERATION.md)）。
> 本轮手册的改动只有一处实质内容：**把 步骤 19 从占位句写成可复制的真实步骤**。

---


## 1. 前置条件（开始前必须具备）

> 本节是**第 0 步**：下面 6 条全部达成，才能开始第 1 步。每条都给了"怎么达成"的命令。
> **如果这是一台全新机器，照着本节的命令做完，前置条件就齐了。**

### 1.1 机器与配置要求

| 项 | 要求 | 怎么核对 |
| --- | --- | --- |
| 主机 | **`pdmsosgh`（`10.254.212.110`）** | `hostname` / `hostname -I` |
| 架构 | **x86_64**（安装包必须是 `linux-x64`） | `uname -m`（期望 `x86_64`） |
| CPU/内存 | **8 核 / 16GB**（JVM 堆按 50% 取 8g） | `nproc` / `free -h` |
| 磁盘 | **≥200GB** 可用，且 `/home` 是**本地盘** | `df -h /home` / `findmnt -no SOURCE,FSTYPE,OPTIONS /home` |
| 容器 | **不装 Docker、不使用 Docker** | `command -v docker`（**期望无输出**） |
| 登录账号 | **`lhim`**（有 sudo 权限，执行本手册全部命令） | `id lhim` |

```bash
# 一次性核对（6 条命令，逐条对照上表）
hostname ; hostname -I ; uname -m ; nproc ; free -h | head -2 ; df -h /home ; command -v docker || echo "no-docker"
```

**② 期望输出**：`pdmsosgh`；包含 `10.254.212.110`；`x86_64`；`8`；内存约 `15Gi`；`/home` 有 ≥200G 可用；
最后一行打印 `no-docker`。
**③ ✅ 完成判据**：`uname -m` = `x86_64` **且** 打印了 `no-docker`。
**④ ✗ 失败**：架构不是 `x86_64` ⇒ 换 `linux-arm64` 包（本手册其余步骤不变）；`/home` 不是本地盘 ⇒ 见 步骤 7。

### 1.2 专用服务账号 `opensearch`（非 root 运行）

Service 以专用账号运行；**它的家目录就是部署根**。新机器上用下面 4 条命令建出来：

```bash
# ① 建账号：有家目录（= 部署根）、可登录 shell（后面要用 sudo -u opensearch 干活）
sudo useradd -m -d /home/opensearch -s /bin/bash opensearch

# ② 锁掉口令（只能从 sudo 进入，不能直接 ssh 登录）
sudo passwd -l opensearch

# ③ 属主归位 + 部署根权限（组可写 + setgid，供运维账号传文件，见 步骤 4）
sudo chown -R opensearch:opensearch /home/opensearch
sudo chmod 2775 /home/opensearch

# ④ 让运维账号 lhim 属于 opensearch 组（改完【断开终端重连】生效）
sudo usermod -aG opensearch lhim
```

```bash
# 核对
getent passwd opensearch | cut -d: -f1,6      # 期望 opensearch:/home/opensearch
sudo passwd -S opensearch | awk '{print $2}'  # 期望 L（locked）
groups | grep opensearch                      # 期望看到 opensearch（重连后才准）
```

**② 期望输出**：`opensearch:/home/opensearch`；`L`；一行含 `opensearch` 的组列表。
**③ ✅ 完成判据**：三条都符合，且 `ls -ld /home/opensearch` 显示属主 `opensearch:opensearch`、权限含 `2775`。
**④ ✗ 失败**：`groups` 里没有 `opensearch` ⇒ 没重连（断开终端重登）；账号已存在 ⇒ 跳过 ①，直接做 ②③④。

### 1.3 部署根目录结构

```bash
sudo -u opensearch mkdir -p /home/opensearch/app /home/opensearch/config /home/opensearch/config/jvm.options.d \
                        /home/opensearch/data /home/opensearch/logs /home/opensearch/backup/fs-repo
sudo -u opensearch ls -ld /home/opensearch/app /home/opensearch/config /home/opensearch/data \
                        /home/opensearch/logs /home/opensearch/backup /home/opensearch/backup/fs-repo
```

**② 期望输出**：6 行 `drwxr-x---`（或 `drwxrwsr-x`）且属主 `opensearch opensearch`。
**③ ✅ 完成判据**：6 个目录都存在（`app` `config` `data` `logs` `backup` `backup/fs-repo`）。
**④ ✗ 失败**：`Permission denied` ⇒ 回 第 1 节 的 ③④（属主/组或没重连）。

### 1.4 两个安装包已放到 `/home/opensearch/`

需要两个文件（版本必须一致，都是 **3.1.0**）：

| 文件 | 大小参考 |
| --- | --- |
| `opensearch-3.1.0-linux-x64.tar.gz` | 1,000,330,285 字节 |
| `opensearch-dashboards-3.1.0-linux-x64.tar.gz` | 385,456,866 字节 |

**把这两个文件放到 `/home/opensearch/` 下即可**（来源不限：离线拷贝、终端工具上传、内网文件服务器都行）。

```bash
ls -l /home/opensearch/*.tar.gz
```

**② 期望输出**：两行，分别是上面两个文件名，大小与参考值一致（±0）。
**③ ✅ 完成判据**：两个文件都在、字节数一致。
**④ ✗ 失败**：缺文件 ⇒ 先取回安装包；字节数不对 ⇒ 重新取一份（**不要**拿旧版本的包）。

### 1.5 仓库资产已放到服务器

把本仓库 `deploy/opensearch/` 目录整体放到服务器任意可读位置，本手册用变量 `ASSETS` 指代它：

```bash
# 按你的实际位置设（只需在当前会话设一次；后面每条命令都用它）
ASSETS=/home/lhim/software/deploy/opensearch
ls "$ASSETS"
```

**② 期望输出**：6 个条目 —— `opensearch.yml.example`、`jvm.options.d`、`opensearch.service`、
`snapshot-backup.sh`、`opensearch_dashboards.yml.example`、`opensearch-dashboards.service`。
**③ ✅ 完成判据**：6 个条目齐全。
**④ ✗ 失败**：少文件 ⇒ 从仓库重新取 `deploy/opensearch/`（落位表见 步骤 10）。

### 1.6 应用/AI 服务器可达（本机与对端都要能通）

```bash
# 本机侧：确认端口还没被占用（此刻应为空 —— 服务还没起）
sudo ss -lntp | grep -E ':(9200|9300|9600|5601)' || echo "ports-free"
```
```bash
# 在【应用/AI 服务器】上执行：确认到本机 9200 的网络路径通（此刻会连不上，正常；要的是"能建立连接/明确拒绝"而不是超时）
curl -m 5 -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:9200/ ; echo "exit=$?"
```

**② 期望输出**：本机侧打印 `ports-free`；对端返回 `000` 且 `exit=7`（拒绝）或 `exit=28`（超时但已配路由）。
**③ ✅ 完成判据**：本机 4 个端口**没有**任何监听进程。
**④ ✗ 失败**：本机有监听 ⇒ 先停掉占用进程（`sudo ss -lntp | grep 9200` 找到 PID）；对端完全不通 ⇒ 先解决网络/路由再往下。

---


## 步骤 1：主机与前置条件核对（约 3 分钟）

① 执行（第 1 节 的 6 条前置条件逐条复核；本步只是"再确认一遍"，不改任何东西）

```bash
hostname ; hostname -I ; uname -m ; nproc ; free -h | head -2 ; df -h /home ; command -v docker || echo "no-docker"
getent passwd opensearch | cut -d: -f1,6
ls -ld /home/opensearch/app /home/opensearch/config /home/opensearch/data /home/opensearch/logs /home/opensearch/backup
ls -l /home/opensearch/*.tar.gz
ls "$ASSETS"
```

② 期望输出

- `pdmsosgh`；`10.254.212.110`；`x86_64`；`8`；内存约 15Gi；`/home` ≥200G 可用；最后打印 `no-docker`
- `opensearch:/home/opensearch`
- 5 行目录，属主均为 `opensearch opensearch`
- 两行 `.tar.gz`：`opensearch-3.1.0-linux-x64.tar.gz`（1,000,330,285 字节）、`opensearch-dashboards-3.1.0-linux-x64.tar.gz`（385,456,866 字节）
- 6 个资产条目（`opensearch.yml.example` / `jvm.options.d` / `opensearch.service` / `snapshot-backup.sh` / `opensearch_dashboards.yml.example` / `opensearch-dashboards.service`）

③ ✅ 完成判据

**上面每一条都对得上**。任何一条不符 ⇒ 回 第 1 节 对应小节补齐后再进第 2 步。

④ ✗ 失败了怎么办

架构/磁盘不符 ⇒ 第 1 节；账号缺失 ⇒ 第 1 节；目录缺失 ⇒ 第 1 节；包缺失/字节数不符 ⇒ 第 1 节；资产不全 ⇒ 第 1 节。

---

## 步骤 2：建立部署根与专用账号（约 2 分钟）

> 若 第 1 节 已做过（账号已存在、目录已建），本步的 ① 会幂等通过——照跑一遍不影响。

① 执行

```bash
sudo useradd -m -d /home/opensearch -s /bin/bash opensearch 2>/dev/null || true
sudo passwd -l opensearch
sudo -u opensearch mkdir -p /home/opensearch/app /home/opensearch/config /home/opensearch/config/jvm.options.d \
                        /home/opensearch/data /home/opensearch/logs /home/opensearch/backup/fs-repo
sudo chown -R opensearch:opensearch /home/opensearch
sudo chmod 2775 /home/opensearch
sudo usermod -aG opensearch lhim        # 改完断开终端重连，组身份才生效
```

② 期望输出

- `passwd: password expiry information changed.`（或中文等价提示，表示已锁）
- 无其它输出（`mkdir` / `chown` / `chmod` 成功时都静默）

③ ✅ 完成判据

断开终端重连后执行 `groups | grep opensearch` **能看到 `opensearch`**，且 `ls -ld /home/opensearch` 显示 `drwxrwsr-x`。

④ ✗ 失败了怎么办

`useradd: user already exists` ⇒ 正常，忽略该行；`groups` 里没有 `opensearch` ⇒ 没重连（断开重登）；`chown` 报 `Operation not permitted` ⇒ 确认用了 `sudo`。

---

## 步骤 3：放置安装包并解压程序目录（约 3~5 分钟）

> **前提**：两个 `.tar.gz` 已在 `/home/opensearch/`（第 1 节）。本步把 OpenSearch 解开成 `/home/opensearch/app`。

① 执行

```bash
# 解压到部署根（用服务账号身份解，解出来的属主天然是 opensearch）
cd /home/opensearch
sudo -u opensearch tar -zxf /home/opensearch/opensearch-3.1.0-linux-x64.tar.gz

# 把解出来的目录改名成 app（★若 app/ 已存在，先确认它是空的再改名）
sudo -u opensearch mv /home/opensearch/opensearch-3.1.0 /home/opensearch/app

# 属主/权限归位（chown 只改属主；★不要加 chmod -R，见 步骤 4 红线）
sudo chown -R opensearch:opensearch /home/opensearch/app
```

② 期望输出

- `tar` 会打印大量文件名（最后几行形如 `opensearch-3.1.0/plugins/…`）
- `ls -ld /home/opensearch/app` 形如 `drwxr-sr-x … opensearch opensearch`
- `ls /home/opensearch/app` 至少有：`bin` `config` `jdk` `lib` `modules` `plugins`
- `ls -l /home/opensearch/app/bin/opensearch` 形如 **`-rwxr-x---`**（★必须带 `x`）

③ ✅ 完成判据

`/home/opensearch/app/bin/opensearch` **存在且带 `x`（`-rwxr-x---`）**，属主是 `opensearch`。

④ ✗ 失败了怎么办

`tar: … No such file` ⇒ 回 第 1 节（包不在位）；`app/bin/opensearch` 不带 `x` ⇒ 跑 **§4.5 的三条 `chmod 750`**；`mv` 报 `Directory not empty` ⇒ `app/` 已有内容，先 `ls /home/opensearch/app` 确认，必要时改名保留再解压。

---



## 步骤 4：权限模型与目录属主（约 2 分钟）

> **本机采用权限共享**：让运维账号 `lhim` 能用终端工具把文件传进 `/home/opensearch` 的子目录，也让服务账号 `opensearch` 正常读写。
> **代价（如实写）**：服务目录对 `lhim` 可写 ⇒ 一次手滑（尤其批量删除）可以直接毁掉数据。若要收紧，见 **附录 B**。
>
> 🔴 **危险动作（命令块正上方，务必看）**：`chmod` **只允许作用在运行期目录 `config` / `data` / `logs` / `backup`**。
> **绝对不要**对 `/home/opensearch` 整个递归，更**不要对 `app/`（程序目录）或 `dashboards/app/` 套用 `chmod 664`** ——
> 那会**抹掉程序与自带 JDK 的执行位**（`app/bin/opensearch`、`app/jdk/bin/*`、`app/jdk/lib/jspawnhelper`、`app/plugins/**/tools/*.sh`），
> 结果是 **`systemd` 报 `status=203/EXEC`（OpenSearch）或 `status=126/n/a`（Dashboards，`use_node: cannot execute`），且应用日志为空**（应用根本没被启动）。
> 恢复命令：OpenSearch ⇒ **§4.5**；Dashboards ⇒ **步骤 17 恢复块 A**。
>
> **一条命令核对两棵程序树（四个文件都必须带 `x`）**：
> ```bash
> ls -l /home/opensearch/app/bin/opensearch /home/opensearch/app/jdk/bin/java \
>       /home/opensearch/dashboards/app/bin/use_node /home/opensearch/dashboards/app/node/bin/node
> # 期望：四条都是 -rwxr-x---（只要有一条不带 x ⇒ 按上面的恢复命令修）
> ```
① 执行


```bash
# ① 目录：组可写 + setgid（新文件自动继承 opensearch 组）
sudo find /home/opensearch/config /home/opensearch/data /home/opensearch/logs /home/opensearch/backup \
     -type d -exec chmod 2775 {} +

# ② 文件：组可读写（覆盖已有文件需要"文件本身可写"）
sudo find /home/opensearch/config /home/opensearch/data /home/opensearch/logs /home/opensearch/backup \
     -type f -exec chmod 664 {} +

# ③ 私钥例外：不给组写（安全插件对私钥最敏感）
sudo find /home/opensearch/config -name '*.pem' -exec chmod 640 {} +

# ④ 新建文件默认 664（组可写）
echo 'umask 002' | sudo tee /etc/profile.d/lhim-umask.sh >/dev/null
```

```bash
# 核对（★必须在【断开终端重连】之后执行，组身份才生效）
groups | grep opensearch                                     # 期望：一行含 opensearch
ls -ld /home/opensearch                                      # 期望：drwxrwsr-x
umask                                                        # 期望：0002
ls -l /home/opensearch/app/bin/opensearch /home/opensearch/app/jdk/bin/java   # 期望两条都是 -rwxr-x---
```

**② 期望输出**
- `groups`：一行含 `opensearch`
- `ls -ld /home/opensearch`：`drwxrwsr-x`（2775）
- `umask`：`0002`
- `app/bin/opensearch` 与 `app/jdk/bin/java`：**`-rwxr-x---`（带 `x`）**

**③ ✅ 完成判据**
**四条都对**，尤其是最后一条 —— 它证明你的 `find` 范围**没有波及 `app/`**。

**④ ✗ 失败了怎么办**
`203/EXEC` 或 `app/bin/opensearch` 不带 `x` ⇒ 跑 **§4.5 的三条 `chmod 750`** 再继续；
`groups` 没有 `opensearch` ⇒ 断开终端重连；目录权限不是 `2775` ⇒ 重跑 ①。

---

## 步骤 5：内核参数与资源限制（约 3 分钟，含一次重启约 2 分钟）

> 🔴 **这一步漏做必然启动失败**（报 `max virtual memory areas vm.max_map_count [65530] is too low`）。
① 执行


```bash
# ① 立即生效 + 持久化
sudo sysctl -w vm.max_map_count=262144
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-opensearch.conf

# ② 文件描述符与内存锁定上限
sudo tee /etc/security/limits.d/99-opensearch.conf >/dev/null <<'EOF'
lhim        soft  nofile  65536
lhim        hard  nofile  65536
opensearch  soft  nofile  65536
opensearch  hard  nofile  65536
opensearch  soft  memlock unlimited
opensearch  hard  memlock unlimited
EOF

# ③ 重启使 limits 与 sysctl 持久化生效（重启后重新登录继续下一步）
sudo reboot
```

```bash
# —— 重新登录后核对 ——
cat /proc/sys/vm/max_map_count     # 期望 262144
ulimit -n                          # 期望 65536
```

**② 期望输出**：`262144`；`65536`。
**③ ✅ 完成判据**：**重启之后**这两个值仍然正确（证明持久化生效，而不是只在当前会话生效）。
**④ ✗ 失败了怎么办**：重启后 `max_map_count` 变回 `65530` ⇒ `/etc/sysctl.d/99-opensearch.conf` 没写成；
`ulimit -n` 还是默认值 ⇒ 检查 `/etc/security/limits.d/99-opensearch.conf` 与是否重登。

---

## 步骤 6：关闭 swap 与内存锁定（约 2 分钟）
① 执行


```bash
free -h ; swapon --show                     # 先看现状
sudo swapoff -a                             # 立即关
sudo cp /etc/fstab /etc/fstab.bak.$(date +%F)
sudo sed -i.bak '/[[:space:]]swap[[:space:]]/s/^/#/' /etc/fstab    # 重启后也不再启用
```

**② 期望输出**：`swapon --show` **没有任何输出**（空）。
**③ ✅ 完成判据**：`swapon --show` 为空，且 `free -h` 的 Swap 行显示 `0B`。
**④ ✗ 失败了怎么办**：`swapoff: cannot allocate memory` ⇒ 内存被占用过多，先释放/加大内存再试；
`fstab` 备份失败 ⇒ 手动 `sudo cp /etc/fstab /etc/fstab.bak`。启动后的内存锁定校验放在 **步骤 14 的 A4**。

---

## 步骤 7：`/home` 挂载检查（约 1 分钟）

> 部署根在 `/home` 下，必须先确认它是**本地盘**且没有 `noexec`（有 `noexec` 会导致 §4.5 那类"程序起不来"）。
① 执行


```bash
df -h /home
findmnt -no SOURCE,FSTYPE,OPTIONS /home
```

**② 期望输出**
- `SOURCE` 是本地块设备（`/dev/sdX`、`/dev/vdX`、`/dev/mapper/…`）
- `FSTYPE` 是 `xfs` 或 `ext4`
- `OPTIONS` 里**没有 `noexec`**（`nosuid` / `nodev` 无害，不用管）

**③ ✅ 完成判据**：三条都符合。
**④ ✗ 失败了怎么办**
`/home` 是 NFS/autofs ⇒ 把 `path.data` 指到本地盘（下面这段）；
`OPTIONS` 带 `noexec` ⇒ 让平台方去掉，或把部署根挪到非 `noexec` 的盘。

```bash
# 退路：只把数据目录挪到本地盘，其余仍在 /home/opensearch
sudo mkdir -p /data/opensearch
sudo chown opensearch:opensearch /data/opensearch
sudo chmod 2775 /data/opensearch
# 然后把 /home/opensearch/config/opensearch.yml 里的 path.data 改成 /data/opensearch
# （该文件在步骤 10 落位；改完按步骤 12 的 ④ 拷进 app/config/）
```

---

## 步骤 8：防火墙（先收暴露面）（约 3 分钟）

> **顺序原则：先收暴露面，再起服务** —— 这一步在启动服务之前做完，"服务一上线就只在白名单里可见"。

| 端口 | 放行来源 | 用途 |
| --- | --- | --- |
| `9200/tcp` | **应用/AI 服务器**（逐个写死 IP） | REST（kNN 检索、`_bulk`、快照管理） |
| `5601/tcp` | **运维网段 / 跳板机** | Dashboards |
| `9300/tcp` | **不放行**（单节点不需要对外） | 节点间 transport |
| `9600/tcp` | **不放行** | Performance Analyzer |

> 🔴 **危险动作**：只加白名单，**不要把 9200/5601 对全网开放**；下面的 ③ 会把"全局开放"删掉，属于收紧而不是放开。
① 执行


```bash
# ① 确认 firewalld 在跑
sudo systemctl enable --now firewalld
sudo firewall-cmd --state                    # 期望 running

# ② 逐个来源写富规则（★把 <…> 换成真实 IP/网段，不要写 0.0.0.0/0）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<应用/AI 服务器 IP>" port port="9200" protocol="tcp" accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<运维网段或跳板机 IP>" port port="5601" protocol="tcp" accept'
sudo firewall-cmd --reload
sudo firewall-cmd --list-rich-rules          # 复核

# ③ 关掉可能已存在的全局开放（有则删，没有会提示 not present）
sudo firewall-cmd --permanent --remove-port=9200/tcp 2>/dev/null || true
sudo firewall-cmd --permanent --remove-port=5601/tcp 2>/dev/null || true
sudo firewall-cmd --reload

# ④ 自检：从【不在白名单】的机器验证它确实不通（期望超时/拒绝，绝不能是 200）
curl -m 3 -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:9200/ ; echo "exit=$?"

# 若本机确实没有 firewalld（只有 iptables），等价命令（仅作等价说明，不需要执行）：
#   sudo iptables -I INPUT -p tcp -s <应用/AI 服务器 IP> -m tcp --dport 9200 -j ACCEPT
#   sudo iptables -I INPUT -p tcp -s <运维网段> -m tcp --dport 5601 -j ACCEPT
#   sudo iptables -A INPUT -p tcp --dport 9200 -j DROP
#   sudo iptables -A INPUT -p tcp --dport 5601 -j DROP
#   （持久化按平台方口径做；本机既定用 firewalld。）
```

**② 期望输出**
- `sudo firewall-cmd --state` → `running`
- `--list-rich-rules` → 恰好两行，分别含 `port="9200"` 与 `port="5601"`，且 `source address` 是你写的那两个来源
- ④ 从白名单外的机器 → `000`（不是 `200`）

**③ ✅ 完成判据**：富规则**只有那两条**、且从白名单外访问 9200 **拿不到 `200`**。
**④ ✗ 失败了怎么办**
`FirewallD is not running` ⇒ 重跑 ①；规则里出现 `0.0.0.0/0` ⇒ 立刻删掉重写；
④ 拿到 `200` ⇒ 白名单写宽了（或 ③ 没执行），重查 `--list-all`。

---

## 步骤 9：时间同步（约 1 分钟）
① 执行


```bash
timedatectl                                    # 先看
sudo systemctl enable --now chronyd
sudo timedatectl set-timezone Asia/Shanghai
date
```

**② 期望输出**：`timedatectl` 首屏含 `System clock synchronized: yes`；`date` 显示东八区时间。
**③ ✅ 完成判据**：`System clock synchronized: yes`。
**④ ✗ 失败了怎么办**：`no` ⇒ 内网 NTP 源地址由平台方给（见 参考 C · 未确认项），把源写进 `/etc/chrony.conf` 后 `sudo systemctl restart chronyd`。

---

## 步骤 10：运行期目录与资产落位（约 3 分钟）

> **落位规则**：属主一律 `opensearch:opensearch`；配置文件 `640`，脚本 `750`，两个 unit 必须 `root:root 644`。
> 落位源统一用 第 1 节 约定的 `$ASSETS`。
① 执行


```bash
# ① 我们维护的配置（源头副本）
sudo install -D -o opensearch -g opensearch -m 640 \
     "$ASSETS/opensearch.yml.example" /home/opensearch/config/opensearch.yml
sudo install -D -o opensearch -g opensearch -m 640 \
     "$ASSETS/jvm.options.d/heap.options" /home/opensearch/config/jvm.options.d/heap.options

# ② 每晚快照脚本（可执行）
sudo install -D -o opensearch -g opensearch -m 750 \
     "$ASSETS/snapshot-backup.sh" /home/opensearch/config/snapshot-backup.sh

# ③ 两个 systemd unit：必须 root:root 644
sudo install -D -o root -g root -m 644 "$ASSETS/opensearch.service" /etc/systemd/system/opensearch.service
sudo install -D -o root -g root -m 644 "$ASSETS/opensearch-dashboards.service" /etc/systemd/system/opensearch-dashboards.service

# ④ 把配置拷进程序目录（运行时生效的那份；用服务账号身份拷，属主天然正确）
sudo -u opensearch cp /home/opensearch/config/opensearch.yml /home/opensearch/app/config/opensearch.yml
sudo -u opensearch mkdir -p /home/opensearch/app/config/jvm.options.d
sudo -u opensearch cp /home/opensearch/config/jvm.options.d/heap.options \
                      /home/opensearch/app/config/jvm.options.d/heap.options
sudo chmod 640 /home/opensearch/app/config/opensearch.yml \
               /home/opensearch/app/config/jvm.options.d/heap.options
```

```bash
# ⑤ 核对（8 条，逐条看输出）
ls -l /home/opensearch/config/                 # opensearch.yml(640) / jvm.options.d / snapshot-backup.sh(750)
ls -l /home/opensearch/app/config/opensearch.yml
ls -l /home/opensearch/app/bin/opensearch /home/opensearch/app/jdk/bin/java   # 期望带 x
ls -l /etc/systemd/system/opensearch.service /etc/systemd/system/opensearch-dashboards.service   # root root 644
sudo systemd-analyze verify /etc/systemd/system/opensearch.service
sudo systemd-analyze verify /etc/systemd/system/opensearch-dashboards.service    # 期望无输出
ls -ld /home/opensearch/backup /home/opensearch/backup/fs-repo
sudo -u opensearch cat /home/opensearch/app/config/jvm.options.d/heap.options | grep -E '^-Xm'
```

**② 期望输出**
- `config/opensearch.yml` → `-rw-r-----`；`config/snapshot-backup.sh` → `-rwxr-x---`
- `app/bin/opensearch`、`app/jdk/bin/java` → 均 `-rwxr-x---`
- 两个 unit → `-rw-r--r-- root root`
- 两条 `systemd-analyze verify` → **没有任何输出**
- `backup` 与 `backup/fs-repo` → `drwxrwsr-x`（或 `drwxr-x---`）
- 最后一条 → `-Xms8g` 与 `-Xmx8g`

**③ ✅ 完成判据**：上面 7 组输出**都对**，且 `systemd-analyze verify` 无任何输出。
**④ ✗ 失败了怎么办**
属主/权限不对 ⇒ 重跑 ①~④；`systemd-analyze verify` 有报错 ⇒ 它会指出行号，对照资产文件修正；
`systemd-analyze` 报"unit 找不到" ⇒ ③ 没落位到 `/etc/systemd/system/`；
任何 `app/` 下文件不带 `x` ⇒ **§4.5**。

---


## 步骤 11：运行官方安装脚本（证书 + 初始口令）（约 2~3 分钟）

> 官方脚本 `opensearch-tar-install.sh` 会**应用自签 TLS 证书并把安全设置写进 `config/opensearch.yml`**；
> 本步由此得到 transport 层可用的证书与 `admin` 用户。**必须在程序目录里、用服务账号身份运行。**
① 执行


```bash
# ① 设初始管理员口令（★必须满足官方四条要求）
export OPENSEARCH_INITIAL_ADMIN_PASSWORD='<强口令>'
#   官方 demo-configuration 页的四条（照抄）：
#     ① 长度 8~100 字符；
#     ② 至少各一个【大写字母、小写字母、数字、特殊字符】；
#     ③ 必须通过 zxcvbn 强度估计并达到 strong 及以上（常见词、日期、1234/qwerty 这类序列、
#        以及 3 替 E 这种可预测替换都会拉低评级，即使四类字符齐全也会被拒）；
#     ④ 不得与用户名 admin 相似。
#   生成示例： openssl rand -base64 24

# ② 在程序目录里跑（用服务账号身份）
sudo -u opensearch -H bash -lc "cd /home/opensearch/app && OPENSEARCH_INITIAL_ADMIN_PASSWORD='$OPENSEARCH_INITIAL_ADMIN_PASSWORD' ./opensearch-tar-install.sh"
```

```bash
# ③ 核对证书与 keystore 已生成
sudo -u opensearch ls -l /home/opensearch/app/config/ | grep -E '\.pem$|keystore'
# ④ 两个权限例外（keystore 只属主可读；插件脚本必须带 x）
sudo chmod 600 /home/opensearch/app/config/opensearch.keystore 2>/dev/null || true
sudo chmod 750 /home/opensearch/app/plugins/opensearch-security/tools/*.sh 2>/dev/null || true
```

**② 期望输出**
- 脚本打印若干安装/证书生成日志，末尾无 `ERROR`
- ③ 至少看到：`esnode.pem`、`esnode-key.pem`、`root-ca.pem`（文件名以实际为准）与 `opensearch.keystore`
- `ls -l …/opensearch.keystore` → `-rw-------`
- `ls -l …/tools/*.sh` → `-rwxr-x---`

**③ ✅ 完成判据**：证书 + keystore **存在**，keystore 是 `600`，`tools/*.sh` 带 `x`。
**④ ✗ 失败了怎么办**
报口令不满足要求 ⇒ 按上面四条换一个（`openssl rand -base64 24`）；
`Permission denied` ⇒ 确认用了 `sudo -u opensearch`；
脚本报缺证书 ⇒ 重跑本步 ②（它会重新生成）。

---

## 步骤 12：用我们的配置接上它（约 3 分钟）
① 执行


```bash
# ① 先备份 demo 脚本刚写的那份（出问题能对照）
sudo -u opensearch cp /home/opensearch/app/config/opensearch.yml \
                      /home/opensearch/app/config/opensearch.yml.demo.bak

# ② 改【源头】那份的两处必改项
sudo -u opensearch vi /home/opensearch/config/opensearch.yml
#   ★必改：network.host: 10.254.212.110
#   该文件里已经写好（无需改动）：
#     cluster.name: pm-os / node.name: os01 / discovery.type: single-node
#     http.port: 9200 / path.data|logs|repo 三个路径
#     plugins.security.disabled: false
#     plugins.security.ssl.http.enabled: false        ← REST 层走 HTTP
#     transport 证书三行（esnode.pem / esnode-key.pem / root-ca.pem）
#     allow_unsafe_democertificates / allow_default_init_securityindex
#   ★不要删掉 transport 证书三行 —— 删了节点会因缺证书起不来。

# ③ 拷进程序目录（运行时生效的那份）
sudo -u opensearch cp /home/opensearch/config/opensearch.yml /home/opensearch/app/config/opensearch.yml
sudo chown opensearch:opensearch /home/opensearch/app/config/opensearch.yml
sudo chmod 640 /home/opensearch/app/config/opensearch.yml

# ④ 核对生效值
sudo -u opensearch grep -nE '^(cluster\.name|node\.name|discovery\.type|network\.host|http\.port|path\.(data|logs|repo)|plugins\.security)' \
     /home/opensearch/app/config/opensearch.yml
```

**② 期望输出**
- `network.host: 10.254.212.110`
- `cluster.name: pm-os`、`node.name: os01`、`discovery.type: single-node`
- `http.port: 9200`
- `path.data: /home/opensearch/data`、`path.logs: /home/opensearch/logs`、`path.repo: /home/opensearch/backup`
- `plugins.security.ssl.http.enabled: false`
- 三行 `plugins.security.ssl.transport.pem*`

**③ ✅ 完成判据**：上面这些行**都在**（尤其 `network.host` 是本机 IP、`ssl.http.enabled: false`、transport 三行齐）。
**④ ✗ 失败了怎么办**
`network.host` 还是模板值 ⇒ ② 没保存；transport 三行缺失 ⇒ 回 **步骤 11**（证书由它生成）并把三行按资产文件补回；
`ssl.http.enabled` 是 `true` ⇒ REST 会走 HTTPS，后续步骤全部按 HTTP 写，会连不上 —— 改回 `false`。

---

## 步骤 13：装 unit 并首次启动（约 2 分钟；首次启动 30~90 秒）

> unit 已在**步骤 10 ③** 落位到 `/etc/systemd/system/`。本步加载并启动。
① 执行


```bash
sudo systemctl daemon-reload
sudo systemctl enable --now opensearch
sleep 20
sudo systemctl status opensearch --no-pager | head -12
```

```bash
# 跟日志（Ctrl-C 退出）；首启约 30~90 秒属正常，别急着改配置
sudo journalctl -u opensearch -f
# 文件日志另有一份： /home/opensearch/logs/pm-os.log
# 复核 unit 施加的上限
systemctl show opensearch -p LimitNOFILE -p LimitMEMLOCK
```

**② 期望输出**
- `systemctl status` → `Active: active (running)`
- `LimitNOFILE=65536`、`LimitMEMLOCK=infinity`
- `journalctl` 末尾出现 `[node] started` 之类字样

**③ ✅ 完成判据**：`Active: active (running)` **且** `systemctl is-enabled opensearch` = `enabled`。
**④ ✗ 失败了怎么办**
**`status=203/EXEC`**（且 `logs/` 为空）⇒ **§4.5**（执行位被抹掉）→ 仍失败按 参考 B · 常见故障 中 `status=203/EXEC` 条目查另外三类原因；
`max virtual memory areas … too low` ⇒ 回**步骤 5**；
`AccessDeniedException` / 读不到配置 ⇒ 回**步骤 10** 复核属主与 `640`。

---

## 步骤 14：验收（6 项，全过才算成功）（约 3 分钟）
① 执行


```bash
export OS=http://127.0.0.1:9200
export AUTH='admin:<步骤 11 设的强口令>'
```

**A1 · 根接口通（HTTP，不是 HTTPS）**
```bash
curl -sS -u "$AUTH" "$OS/" | head -20
```
② 期望输出（逐字对齐关键字段）：
```json
{
  "name" : "os01",
  "cluster_name" : "pm-os",
  "version" : { "distribution" : "opensearch", "number" : "3.1.0", "build_type" : "tar" },
  "tagline" : "The OpenSearch Project: https://opensearch.org/"
}
```

**A2 · 集群健康 = green（★不是 yellow）**
```bash
curl -sS -u "$AUTH" "$OS/_cluster/health?pretty"
```
② 期望输出：
```json
{ "cluster_name" : "pm-os", "status" : "green", "number_of_nodes" : 1,
  "number_of_data_nodes" : 1, "unassigned_shards" : 0,
  "active_shards_percent_as_number" : 100.0 }
```

**A3 · 节点就位（单节点 = 1 行）**
```bash
curl -sS -u "$AUTH" "$OS/_cat/nodes?v"
```
② 期望输出（1 行 1 节点）：
```text
ip             heap.percent ram.percent cpu node.role node.roles master name
10.254.212.110           31          62   3 dimr      dimr       *      os01
```

**A4 · 内存锁定生效**
```bash
curl -sS -u "$AUTH" "$OS/_nodes?filter_path=nodes.*.process.mlockall&pretty"
```
② 期望输出：`{"nodes":{"<node-id>":{"process":{"mlockall":true}}}}`

**A5 · 索引列表（未建索引时只有表头）**
```bash
curl -sS -u "$AUTH" "$OS/_cat/indices?v&expand_wildcards=all"
```

**A6 · 从应用/AI 服务器侧验证连通**
```bash
# 在【应用/AI 服务器】执行（不是本机）
curl -sS -m 5 -u admin:'<强口令>' http://10.254.212.110:9200/_cluster/health
```
② 期望输出：`{"cluster_name":"pm-os","status":"green",...}`

**③ ✅ 完成判据**
`A1 build_type=tar` ｜ `A2 status=green` ｜ `A3` 恰好 1 行 ｜ `A4 mlockall=true` ｜ `A5` 只有表头（或已建索引为 green）｜ `A6` 跨机可达 —— **六项全过**。

**④ ✗ 失败了怎么办**
A1 报 SSL 错误（`wrong version number` / `certificate verify failed`）⇒ 运行时配置里不是 `ssl.http.enabled: false`（回**步骤 12**）；
A1 报 `Empty reply from server` ⇒ 仍是 HTTPS 在应答（同上）；
A2 是 `yellow` ⇒ 某索引设了 `number_of_replicas ≥ 1`（详见 参考 B · 常见故障）；
A2 是 `red` ⇒ 见 参考 B · 常见故障；
A4 是 `false` ⇒ 回**步骤 5/6**（memlock limits 或 `bootstrap.memory_lock`）；
A6 不通 ⇒ 回**步骤 8** 的白名单与 `network.host`。

---

## 步骤 15：重启自愈与单节点故障演练（约 5 分钟）
① 执行


```bash
# ① 重启自愈（"部署完就不管"的底线）
sudo reboot
# —— 重新登录后等 1~2 分钟 ——
systemctl is-enabled opensearch        # 期望 enabled
systemctl is-active opensearch         # 期望 active
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cluster/health | head -3   # 期望 green
```
```bash
# ② 单节点故障演练（新装无数据，停机成本为零；这是验证"重启能自愈"的手段）
sudo systemctl stop opensearch
curl -sS -m 3 http://127.0.0.1:9200/ ; echo "exit=$?"      # 期望连不上（exit=7/28）
curl -sS "http://127.0.0.1:8100/health?with_vec=true"      # 期望 vec 块 not ok（AI 侧明确报错，不静默降级）
sudo systemctl start opensearch
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cluster/health | head -3   # 期望 green
```

**② 期望输出**：重启后 `enabled` / `active` / `green`；演练期间 `exit=7` 或 `28`；恢复后再次 `green`。
**③ ✅ 完成判据**：**重启后自动恢复为 `green`**，且服务停止时 AI 侧**明确失败**（不是"没找到相关内容"）。
**④ ✗ 失败了怎么办**：重启后没起来 ⇒ `systemctl status -l` + 参考 B · 常见故障；AI 侧没报错而是"空结果" ⇒ 那说明降级被静默吞了，按 步骤 19 的降级口径修正。

---

## 步骤 16：创建索引 `pm-ai-chunks`（约 1 分钟）

> **参数已定死**：`knn_vector` **4096 维** / `space_type: cosinesimil` / HNSW（`engine: lucene`，`m=16`，`ef_construction=128`）/
> **主分片 1 + 副本 0**（单节点上副本无法分配，会一直 `yellow`）。
① 执行


```bash
OS=http://127.0.0.1:9200
AUTH='admin:<强口令>'
curl -sS -u "$AUTH" -XPUT "$OS/pm-ai-chunks" -H 'Content-Type: application/json' -d '{
  "settings": { "index": { "knn": true, "number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "-1" } },
  "mappings": { "properties": {
    "vector":  { "type": "knn_vector", "dimension": 4096, "data_type": "float", "space_type": "cosinesimil",
                 "method": { "name": "hnsw", "engine": "lucene", "parameters": { "m": 16, "ef_construction": 128 } } },
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
  } }
}'
```
```bash
# 核对
curl -sS -u "$AUTH" "$OS/pm-ai-chunks/_mapping?pretty" | grep -E '"dimension"|"space_type"|"engine"|"number_of_replicas"'
curl -sS -u "$AUTH" "$OS/_cat/indices?v&expand_wildcards=all"
```

**② 期望输出**
- PUT 返回 `{"acknowledged":true,"shards_acknowledged":true,"index":"pm-ai-chunks"}`
- mapping 里能看到 `"dimension" : 4096`、`"space_type" : "cosinesimil"`、`"engine" : "lucene"`
- `_cat/indices` 里 `pm-ai-chunks` 的 `health` 是 **`green`**（不是 `yellow`）

**③ ✅ 完成判据**：索引存在、`dimension=4096`、`health=green`。
**④ ✗ 失败了怎么办**
`resource_already_exists_exception` ⇒ 索引已建，跳过（要重建先 `DELETE /pm-ai-chunks`）；
`Unknown engine [nmslib]` ⇒ 用 `lucene`（见 附录 E · HNSW 与引擎的版本注记）；
`health=yellow` ⇒ 副本数写成 ≥1 了，`PUT /pm-ai-chunks/_settings {"index":{"number_of_replicas":0}}`。

---


## 步骤 17：安装 OpenSearch Dashboards（约 5 分钟）

> 版本必须与 OpenSearch **完全一致**（本手册 = `3.1.0`）。同机部署，端口 `5601`，**只用 `admin` 账号**。
> 落位规则与**步骤 10** 一致：属主 `opensearch:opensearch`，配置文件 `640`。
> 🔴 **危险动作**：**不要对 `dashboards/app/` 套用 `chmod 664`** —— 会抹掉 `bin/opensearch-dashboards`、`node/bin/node` 的执行位 ⇒ `systemd` 报 `status=203/EXEC`。
① 执行


```bash
# ① 解压到 dashboards/app，并把属主归位
sudo -u opensearch mkdir -p /home/opensearch/dashboards
cd /home/opensearch/dashboards
sudo -u opensearch tar -zxf /home/opensearch/opensearch-dashboards-3.1.0-linux-x64.tar.gz
sudo -u opensearch mv /home/opensearch/dashboards/opensearch-dashboards-3.1.0 /home/opensearch/dashboards/app
sudo chown -R opensearch:opensearch /home/opensearch/dashboards

# ② 放配置（-D 自动建出 dashboards/app/config）
sudo install -D -o opensearch -g opensearch -m 640 \
     "$ASSETS/opensearch_dashboards.yml.example" \
     /home/opensearch/dashboards/app/config/opensearch_dashboards.yml

# ③ 改两处必改项
sudo -u opensearch vi /home/opensearch/dashboards/app/config/opensearch_dashboards.yml
#   ★必改：server.host: "10.254.212.110"
#   ★必改：opensearch.username / opensearch.password 用 admin 与步骤 11 的强口令

# ④ 启动
sudo systemctl daemon-reload
sudo systemctl enable --now opensearch-dashboards
sleep 15
sudo systemctl status opensearch-dashboards --no-pager | head -10
```

```bash
# ⑤ 核对
ls -l  /home/opensearch/dashboards/app/config/opensearch_dashboards.yml   # 期望 -rw-r----- opensearch opensearch
ls -l  /home/opensearch/dashboards/app/bin/opensearch-dashboards          # 期望 -rwxr-x---
curl -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:5601/     # 期望 200 或 302
systemctl is-enabled opensearch-dashboards                                # 期望 enabled
```

**② 期望输出**
- `Active: active (running)`
- 配置文件 `-rw-r----- opensearch opensearch`
- `bin/opensearch-dashboards` 带 `x`
- `http://10.254.212.110:5601/` → `200` 或 `302`
- `is-enabled` → `enabled`

**③ ✅ 完成判据**：服务 `running` + `5601` 返回 `200/302` + 用浏览器打开能看到登录页。
**④ ✗ 失败了怎么办**
`status=126/n/a` 且日志 `bin/use_node: cannot execute: Permission denied` ⇒ **`use_node` / `node` 缺 `x`**（全量 `chmod 664` 波及了 `dashboards/` 树）⇒ 用下面的**恢复块 A**；
`status=203/EXEC` ⇒ 同样是权限位问题（**`126` 与 `203` 修法一致**）⇒ 恢复块 A；
`5601` 连不上 ⇒ 回**步骤 8**（`5601` 白名单）；日志报连不上 9200 ⇒ 检查 yml 里 `opensearch.hosts` 是 `http://127.0.0.1:9200` 且口令正确。

#### 恢复块 A · Dashboards 程序树执行位恢复（`status=126` 或 `203/EXEC` 时用）

> 触发条件：`opensearch-dashboards.service` 起不来，journald 报 `bin/use_node: cannot execute: Permission denied`
> （`status=126/n/a`）或 `status=203/EXEC`。
> 根因：**`dashboards/app/` 下的执行位被抹掉**（例如对 `/home/opensearch` 整个跑过 `chmod 664`）。

```bash
sudo find /home/opensearch/dashboards/app/bin -type f -exec chmod 750 {} +
sudo find /home/opensearch/dashboards/app/node/bin -type f -exec chmod 750 {} +
sudo find /home/opensearch/dashboards/app -name '*.sh' -exec chmod 750 {} +

# 核对（两个都必须带 x ⇒ -rwxr-x---）
ls -l /home/opensearch/dashboards/app/bin/use_node /home/opensearch/dashboards/app/node/bin/node

# 重启（先 reset-failed 清掉重启计数，否则 systemd 可能仍卡在 start-limit 里）
sudo systemctl reset-failed opensearch-dashboards && sudo systemctl restart opensearch-dashboards
sudo systemctl status opensearch-dashboards --no-pager | head -10
```

**② 期望输出**：两条 `ls -l` 都是 `-rwxr-x---`；`status` 显示 `Active: active (running)`。
**③ ✅ 完成判据**：`curl -sS -o /dev/null -w '%{http_code}\n' http://10.254.212.110:5601/` 返回 `200` 或 `302`。
**④ ✗ 失败**：仍报 `126/203` ⇒ 用 `ls -l` 逐个确认上面三条 `find` 覆盖到的路径都带 `x`；仍不行 ⇒ 参考 B · 常见故障。

> **内存说明**：Dashboards 是 Node.js 进程，本方案在 unit 里把老生代封顶到 `1024MB`（官方 `config/node.options` 默认是 4096）。

---

## 步骤 18：快照与每日备份（约 5 分钟）

> **单节点的强制项**：没有副本，快照是唯一的恢复手段。
① 执行


```bash
# ① 准备口令文件（cron 用；权限 600）
printf '%s\n' '<强口令>' | sudo tee /etc/opensearch-backup.pass >/dev/null
sudo chmod 600 /etc/opensearch-backup.pass

# ② 手动跑一次（验证全链路：注册仓库 → 打快照 → 清理 → rsync 到 .111）
bash /home/opensearch/config/snapshot-backup.sh snapshot
bash /home/opensearch/config/snapshot-backup.sh status
```

```bash
# ③ 装 cron（★装到 root 的 crontab —— 只有 root 读得到上面那份 600 的口令文件）
sudo crontab -e
# 加入这一行：
30 2 * * * /bin/bash /home/opensearch/config/snapshot-backup.sh snapshot >> /home/opensearch/logs/snapshot-cron.log 2>&1

# ④ 复核三件事（别只看 crontab 有没有装上）
sudo crontab -l | grep snapshot-backup.sh                 # ① cron 行在 root 的 crontab 里
ls -l /etc/opensearch-backup.pass                         # ② 期望 -rw------- root root
sudo -u root bash /home/opensearch/config/snapshot-backup.sh status   # ③ 以 cron 用户身份真能读到口令

# ⑤ 装日志轮转（日志不轮转会写满 /home，而磁盘满会让索引变只读）
sudo tee /etc/logrotate.d/opensearch >/dev/null <<'EOF'
/home/opensearch/logs/*.log {
    daily
    rotate 14
    size 100M
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
    su opensearch opensearch
}
EOF
sudo logrotate -d /etc/logrotate.d/opensearch | head -20      # -d 只演练，不真轮转
```

**② 期望输出**
- ② 的手动跑 → 末尾 `[OK] 快照任务完成：nightly-<日期>`
- `status` → 列出最近一份快照与集群健康
- ④ 三条核对都通过（cron 行存在、口令文件 `-rw------- root root`、`status` 能读到口令）
- ⑤ `logrotate -d` 打印它打算做什么，**没有 error**

**③ ✅ 完成判据**：**快照真的打出来了**（`status` 里能看到今天的 `nightly-*`），且**以 root 身份能读到口令**。
**④ ✗ 失败了怎么办**
`没有拿到 OpenSearch 口令` ⇒ 口令文件属主/权限与 cron 用户不匹配（本方案是 `root:root 600` + root 的 crontab）；
`rsync` 报无法免密 ssh ⇒ `ssh-copy-id lhim@10.254.212.111`，或临时 `SKIP_REMOTE=1`（**别长期跳过**，否则没有异地副本）；
快照报 `repository_exception` ⇒ 仓库目录属主不对或不在 `path.repo` 之下（步骤 18）。

---

## 步骤 19：接入 AI 服务（切 `VEC_BACKEND=opensearch`）（约 5 分钟 + 重建索引视语料量）

> 前置：**步骤 16 的索引已建**、**步骤 14 验收已过**。配置改在 **AI 服务器**上，一致性比对在**开发机**上跑。
> 🔴 **顺序不能颠倒**：建索引 → 验连通 → 改 `.env` 并重启 AI → **先做 top-k 一致性验证** → 确认一致后再全量重建/切换 → 出问题回滚 `local`。
> **绝不静默降级**：`VEC_BACKEND=opensearch` 而集群不可达时，检索与重建**立刻**返回 **503 + "向量后端不可用"**
> （不是含糊的 500，更不是"没找到相关内容"）——**不会**悄悄退回 `local`，也**不会**退回关键词检索。
① 执行


**①-1 建索引 + 验连通（在 OpenSearch 服务器上执行）**

```bash
# a) 索引：没建过就先按 步骤 16 建 pm-ai-chunks，再核对三件事（这一条不过，后面全都白做）
curl -sS -u admin:'<步骤 11 的强口令>' 'http://127.0.0.1:9200/pm-ai-chunks/_mapping?pretty' \
  | grep -E '"dimension"|"space_type"|"engine"'
curl -sS -u admin:'<步骤 11 的强口令>' 'http://127.0.0.1:9200/_cat/indices?v&expand_wildcards=all'

# b) 连通：在【AI 服务器】上执行（要的是"连得上"——不是超时，也不是 401）
curl -sS -u admin:'<步骤 11 的强口令>' http://10.254.212.110:9200/_cluster/health?pretty
```

> **建索引两种方式的取舍（★先看这一条，别踩）**：`步骤 16` 给的是**手工 `curl -XPUT`** 建索引；
> AI 服务自己在**首次写入/首次检索**时也会建（`OpenSearchIndex.ensureIndexOnce`：先 `HEAD /<index>`，404 才 `PUT`，
> 已存在则**原样不动**）。所以两种方式**只能选一种**，参数必须与代码一致：
> `dimension` **4096**、`space_type` **cosinesimil（只写字段层）**、`engine` **lucene**、`m` **16**、
> **`ef_construction` = 100**（代码用 HNSW 官方默认值；步骤 16 的示例写的是 128）、
> **`refresh_interval` = 1s**（代码是 1s，为了"灌完即可查"；步骤 16 的离线灌库示例给的是 `-1`）。
> **索引已存在时不会被改写**——若你已按 128/`-1` 手工建过，`DELETE /pm-ai-chunks` 后让服务自建（或按本节参数重建），再跑 ①-4。

**①-2 AI 服务侧改配置（AI 服务器上的 `/home/lhim/pm/ai/.env`）**

```bash
cp /home/lhim/pm/ai/.env /home/lhim/pm/ai/.env.bak.$(date +%Y%m%d%H%M)
vi /home/lhim/pm/ai/.env
```

要写进 `.env` 的就是这 5 个键（**口令只放 `.env`：不要写进仓库、工单、日志或聊天记录**）：

```ini
VEC_BACKEND=opensearch
OPENSEARCH_URL=http://10.254.212.110:9200
OPENSEARCH_INDEX=pm-ai-chunks
OPENSEARCH_USER=admin
OPENSEARCH_PASSWORD=<步骤 11 的强口令>
```

```bash
# 让新配置生效（pm 根目录；AI 与主系统共用同一套 pm.sh）
cd /home/lhim/pm && bash pm.sh restart ai

# 核对：backend 已是 opensearch，vec.ok 反映【集群真实状态】，open_search 只报"口令配没配"（不回显明文）
curl -sS 'http://127.0.0.1:8100/health?with_vec=true'
```

**①-3 先做 top-k 一致性验证（在开发机上对真集群跑；这一步不过，就不许全量切换）**

> **判据口径**：两条后端的**相似度数值不必逐位相等**（OpenSearch 的 `_score` 经过引擎内部换算），
> 真正要求一致的是**排序与集合**——同一份语料、同一个 query，top-k 命中的切片（`docId:pageNo`）与顺序应当基本一致。
> **重合率明显偏低时，按这个顺序查**：① `space_type`（是不是 `cosinesimil`，是不是只写在字段层）
> → ② 字段名（`vector` / `text` / `doc_id`… 与索引 mapping 对得上）
> → ③ 是否漏灌（`_count` 与切片总数对不上就是漏灌）。
> 工具本身：`com.pmgt.ai.tools.TopKCompareTool`（在 `src/test/java`，**不进 `mvn test`**）。

```powershell
cd ai-backend
mvn -o test-compile
mvn -o dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
$cp = (Get-Content target/cp.txt -Raw).Trim()
$env:OPENSEARCH_PASSWORD = '<步骤 11 的强口令>'   # 口令只从环境变量读，不进命令行、不进日志
$env:LLM_API_KEY = '<平台 sk>'
java -cp "target/classes;target/test-classes;$cp" com.pmgt.ai.tools.TopKCompareTool `
     --work ./work --url http://10.254.212.110:9200 --user admin `
     --eval ../eval/smoke-30.json --top-k 5 --reindex
```

> `--reindex` 才会写索引（幂等、按 `chunk_key` 跳过已存在）；**不带它只读不写**。
> 另外，**第一次拿真集群比对时，相似度那一列本来就不该期待逐位相等**——看的是"重合率"与"top-1 一致数"。

**①-4 全量重建索引（在 AI 服务器上执行；真实端点）**

```bash
curl -XPOST 'http://127.0.0.1:8100/admin/reindex'                  # 全量，默认跳过已存在（断点续跑）
curl -XPOST 'http://127.0.0.1:8100/admin/reindex?docIds=abc,def'   # 局部（只重建这几份，如补录元数据后）
curl -XPOST 'http://127.0.0.1:8100/admin/reindex?force=true'       # 忽略已存在全重写（换模型/维度时）
curl -s    'http://127.0.0.1:8100/admin/reindex/<job_id>'          # 进度 / 失败数 / ETA
curl -XPOST 'http://127.0.0.1:8100/admin/reindex/<job_id>/cancel'  # 取消（协作式，在文档边界生效）
```

```bash
# 核对（本机侧看文档数）
curl -sS -u admin:'<步骤 11 的强口令>' 'http://127.0.0.1:9200/pm-ai-chunks/_count?pretty'
```

> 重建是**可中断、可续跑**的：写入按 `chunk_key` 幂等，取消/断线后**再跑一次会跳过已写入的切片**；
> 已写入的切片不回滚（它们本身是正确的）。向量化在重建里是**批 32 + 并发 8**（共享 embed 信号量），
> 写入按 `OPENSEARCH_BULK_SIZE=500` 分批。

**①-5 回滚（切换后出问题：改一个开关 + 重启，秒级）**

```bash
# AI 服务器上的 /home/lhim/pm/ai/.env
VEC_BACKEND=local
cd /home/lhim/pm && bash pm.sh restart ai
```

> `local` 的行为**没有变**（仍是进程内余弦 + `work/vectors/` 缓存），所以回滚就是改一个开关再重启；
> `work/docs/` 与 `work/vectors/` 保持原样即可直接用。**但 `local` 仍要求 AI 服务单实例**。

> ⚠️ **只接一台**：`.110` 与 `.111` 是**彼此独立的单节点集群**（`discovery.type: single-node`，数据集不共享）。
> 生产 = `.110`，`.111` 是冷备/演练机（收快照与归档）。**不要把两台同时接进业务**——
> 同一个问题会在两台上召回不同、索引各自演化。

**② 期望输出**
- `_cluster/health` 是 `green`（单节点 0 副本）；`_mapping` 里能看到 `"dimension" : 4096`、`"space_type" : "cosinesimil"`、`"engine" : "lucene"`
- `/health?with_vec=true`：`vec.backend=opensearch`、`vec.ok=true`、`vec.indexed_chunks` 与 `_count` 一致；`vec.open_search` 里的口令只显示"已配置"
- `TopKCompareTool`：逐条打印两边 top-k 与重合率，末尾"平均 top-k 重合率"接近 1、`top-1 一致` 为 `<N>/<N>`
- 重建：`GET /admin/reindex/<job_id>` 里 `failed_docs=0`，`processed_docs` 最终等于 `total_docs`
- `_count` 的 `count` 与语料切片总数一致（且随重建增长）

**③ ✅ 完成判据**：`_count` 与预期切片数一致 **且** top-k 顺序/集合与 `local` 一致（这两条都过才算切换成功），
且 `/health?with_vec=true` 的 `vec.ok=true`。**任一不过就按 ①-5 回滚 `local`**。

**④ ✗ 失败了怎么办**
`count: 0` 或进度里 `written_chunks=0` ⇒ 重建没跑或没灌进去：先看 `GET /admin/reindex/<job_id>` 的 `failed_docs` 与失败明细；
连不上 9200 ⇒ 回**步骤 8**（防火墙白名单），并核对 `OPENSEARCH_URL`/`OPENSEARCH_USER`/`OPENSEARCH_PASSWORD`（口令错是 **401**，与"连不上"不是一件事）；
启动或检索直接返回 **503 + "向量后端不可用"** ⇒ 这是**预期行为**（不静默降级）：按上一条查网络与口令，**不要指望它自己退回 `local`**；
回答"没找到相关内容"而不是明确报错 ⇒ 说明实际走的还是 `local`（`.env` 没生效或没重启 AI）；
相似度/顺序对不上 ⇒ 按 ①-3 的顺序查 `space_type` → 字段名 → 是否漏灌（附录 E · HNSW 与引擎的版本注记 / 索引设计 · 查询）；
索引是我按 步骤 16 手工建的、怕和代码参数不一致 ⇒ 见 ①-1 的"建索引两种方式的取舍"（`ef_construction` 100/128 与 `refresh_interval` 1s/-1 这两处最容易踩）。

---

## 步骤 20：第二台 `10.254.212.111`（冷备 + 演练机）（约 5 分钟/次）

> 这台机器**跑同一套安装**，但**服务默认停着**；作用有两个：① 接收每晚快照副本；② 做恢复演练。
① 执行


```bash
# ① 在 .111 上重复步骤 1~13（同样的包、同样的 unit），然后【不要 enable】
sudo systemctl disable --now opensearch

# ② 让 .110 能免密 rsync 到 .111（在 .110 上执行）
ssh-copy-id lhim@10.254.212.111
ssh lhim@10.254.212.111 'mkdir -p /home/opensearch/backup/dist'     # 归档安装包，离线可重建

# ③ 恢复演练（七步，在 .111 上做；做完把服务停回去）
sudo systemctl start opensearch
bash /home/opensearch/config/snapshot-backup.sh status
bash /home/opensearch/config/snapshot-backup.sh restore <最近一份快照名>    # 会要求输入 yes
curl -sS -u admin:'<强口令>' http://127.0.0.1:9200/_cat/indices?v | grep -E 'pm-ai-chunks|opensearch_dashboards|opendistro_security'
sudo systemctl stop opensearch
```

**② 期望输出**：`.111` 上 `_cat/indices` 能看到 `pm-ai-chunks` **以及** `.opensearch_dashboards*`、`.opendistro_security`。
**③ ✅ 完成判据**：**恢复后索引齐全**（尤其系统索引在），演练完服务已停回。
**④ ✗ 失败了怎么办**
少系统索引 ⇒ 快照索引列表被改过（步骤 20 与 步骤 18）；`restore` 报错 ⇒ 看脚本打印的"下一步该敲什么命令"；
免密不通 ⇒ 回 ②。

---

## 运维速查（日常命令）

```bash
sudo systemctl status opensearch            # 状态（先看这个）
sudo journalctl -u opensearch -n 200        # 最近 200 行（journald）
sudo journalctl -u opensearch -f            # 跟日志
sudo tail -f /home/opensearch/logs/pm-os.log   # 文件日志（path.logs）
sudo systemctl restart opensearch           # 改 opensearch.yml / heap.options 后必须重启
sudo systemctl stop opensearch
sudo systemctl start opensearch
sudo systemctl is-enabled opensearch        # 是否开机自启
curl -sS -u admin:'<口令>' http://127.0.0.1:9200/_cluster/health?pretty
bash /home/opensearch/config/snapshot-backup.sh status       # 最近快照
```

> **改配置的规矩**：改 `/home/opensearch/config/` 下那份（源头），按**步骤 12 的 ③** 拷进 `app/config/`，再 `systemctl restart opensearch`。
> **升级与回滚**：见 **参考 B · 升级路径与回滚**；**常见故障**：见 **参考 B · 常见故障**（含 `status=203/EXEC` 条目）；**程序可执行位恢复**：见 **§4.5**。

---


## 参考 B · 运维与排障

### 运维 · 日常命令速查

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

### 运维 · 快照与恢复（**单节点的强制项**）

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

#### 运维 · ★cron 装到谁、口令谁能读（**三者必须自洽，否则备份每天静默失败**）

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
| 演练 | 每季度一次（步骤 20 七步），**演练过才算有备份** |

```bash
# 手工等价命令（脚本坏了时的兜底）
curl -sS -u admin:'<口令>' -XPUT 'http://127.0.0.1:9200/_snapshot/os-backup' -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/home/opensearch/backup/fs-repo","compress":true,"index.latest":false}}'
curl -sS -u admin:'<口令>' -XPUT "http://127.0.0.1:9200/_snapshot/os-backup/nightly-$(date +%F)?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{"indices":"pm-ai-chunks","ignore_unavailable":true,"include_global_state":false}'
curl -sS -u admin:'<口令>' 'http://127.0.0.1:9200/_snapshot/os-backup/_all?pretty' | grep -E '"snapshot"|"state"'
```

### 运维 · 升级路径（官方 Tar 升级做法 + 回滚）

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
#    规则（与 步骤 10 的落位表一致）：属主一律 opensearch:opensearch；
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
sudo chown -R opensearch:opensearch /home/opensearch   # 只改属主；★不要顺手加 chmod -R（见 步骤 4 红线）
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
# ⚠️ 若新版本【已经写过数据】，直接回退旧版本可能读不了新格式 → 那时用快照恢复（步骤 18）
```

> **确认新版稳定后**再删旧目录：`sudo rm -rf /home/opensearch/app-<旧版本>`（先确认快照里有一份可恢复的数据）。
> **大版本（2.x → 3.x）不要直跳**：按官方"先升到最后一个 2.x，再升 3.x"的路径走，每步都打快照。
> 本方案是单节点，停机升级即可（分钟级）。

### 运维 · 监控要看哪些指标

| 类别 | 指标 | 看它判什么 | 怎么看 |
| --- | --- | --- | --- |
| **JVM 堆** | 堆使用率（`heap.percent`）、GC 统计 | 堆长期 >85% 或 GC 停顿变长 ⇒ 调 heap.options 或扩内存 | `_cat/nodes?v`、`_nodes/stats/jvm` |
| **内存锁定** | `mlockall` | 必须 `true`（第 1 节 A4） | `_nodes?filter_path=nodes.*.process.mlockall` |
| **线程池** | `search`/`write` 队列与 `rejected` | 有拒绝 ⇒ 检索/写入压力超容量 | `_cat/thread_pool/search,write?v&h=node_name,active,queue,rejected` |
| **磁盘** | 使用率与水位 | 磁盘满会让索引变只读（见 参考 B · 常见故障）；**单节点最容易死在这里**（快照也吃盘） | `_cat/allocation?v`、`df -h /home/opensearch` |
| **段与合并** | 段数、merge 次数/耗时 | 段数暴涨（批量写入期）⇒ 用 `_forcemerge` 收口 | `_cat/segments/pm-ai-chunks?v` |
| **kNN** | `_plugins/_knn/stats` | 图未被缓存 ⇒ 查询走磁盘 | `GET _plugins/_knn/stats?pretty` |
| **集群状态** | `status`、`unassigned_shards`、`number_of_pending_tasks` | `yellow` = 有副本没分配（单节点应为 `green`，见 步骤 16）；`red` 见 参考 B · 常见故障 | `_cluster/health`、`_cat/pending_tasks?v` |
| **快照** | 最近快照 `state` 与耗时 | 失败要在 24h 内发现（否则等于没有备份） | `bash /home/opensearch/config/snapshot-backup.sh status` |
| **服务本身** | systemd 状态、重启次数 | `Restart=on-failure` 生效说明崩过 | `systemctl status opensearch`（看 `Main PID` 与 `Active`） |

> ⚠️ **本项目 AI 侧目前没有 actuator/micrometer**（《AI模型调用与任务队列评估》已登记"无任何指标"），
> OpenSearch 侧也**没有接 Prometheus/告警**——本节指标目前**只能靠命令巡检**。要不要上监控栈见 参考 C · 未确认项。

### 运维 · 常见故障

| 现象 | 判读 | 处置 |
| --- | --- | --- |
| 启动即退出，日志 `max virtual memory areas vm.max_map_count [65530] is too low` | 内核参数没设或没持久化（重启后丢了） | 步骤 4 全做一遍；**进程内改不了**，必须在宿主设 |
| 直接拒绝启动并提示不能以 root 运行 | 用 root（或 root 的 unit）拉起来了 | unit 里 `User=opensearch` / `Group=opensearch`（资产已设）；手工调试也要 `sudo -u opensearch …` |
| **`status=126/n/a`** 且日志 **`bin/use_node: cannot execute: Permission denied`**（Dashboards） | **Dashboards 程序树的执行位被抹掉**：`dashboards/app/bin/use_node`、`dashboards/app/node/bin/node` 缺 `x`（全量 `chmod 664` 波及 `dashboards/` 树）。这同样属于 **exec 阶段失败 ⇒ 应用日志通常也是空的** | 用 **步骤 17 的恢复块 A**：三条 `chmod 750` + `systemctl reset-failed opensearch-dashboards` + `restart`；核对 `ls -l …/bin/use_node …/node/bin/node` 均为 `-rwxr-x---` |
| **`status=203/EXEC`**（`Control process exited, code=exited, status=203/EXEC`，且 `logs/` 里什么都没有） | **systemd 在 exec 阶段就没能启动程序**（OpenSearch 或 Dashboards 都可能）⇒ 应用根本没跑起来，所以应用日志为空，**别在 `logs/` 里找原因**。**`126` 与 `203` 都是权限位问题，修法一致**。四类原因：① 程序树文件缺 `x`（OpenSearch ⇒ §4.5；Dashboards ⇒ 步骤 17 恢复块 A）；② `/home` 挂载带 `noexec`；③ 路径/属主问题导致服务账号穿不过某一级目录；④ SELinux / 麒麟 kysec 拒绝 exec | ① `systemctl status <服务> -l` 与 `systemctl cat <服务>`（核对 `ExecStart` 路径）；② **手工以服务账号执行**看真实原因：`sudo -u opensearch /home/opensearch/app/bin/opensearch` 或 `sudo -u opensearch /home/opensearch/dashboards/app/bin/opensearch-dashboards`；③ 按原因处置：执行位 ⇒ §4.5 / 步骤 17 恢复块 A；`noexec` ⇒ `findmnt -no OPTIONS /home`；属主/路径 ⇒ 步骤 4 与步骤 10；SELinux/kysec ⇒ `getenforce`、`ls -Z`、`sudo ausearch -m avc -ts recent`、`journalctl -k` |
| **systemd 启动失败，日志报权限不足 / `status=200/CHDIR` / 找不到 `/home/opensearch/app/bin/opensearch`** | 属主或权限不对（D 方案下**不再**是家目录穿透问题：部署根就是 `opensearch` 自己的家目录） | **步骤 4 / 步骤 10**：`sudo -u opensearch ls -d /home/opensearch` 与 `sudo -u opensearch ls /home/opensearch/app/bin/opensearch`；不对就 `sudo chown -R opensearch:opensearch /home/opensearch`。若仍报错且系统开了 SELinux，看 `ls -Z /home/opensearch` 与 `sudo ausearch -m avc -ts recent` |
| 启动失败：`/home/opensearch` 所在盘不可写 / 数据目录报错 | `/home` 是 NFS/autofs，或空间不足 | 步骤 7：`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home`；按"退路"把 `path.data` 指到本地盘 |
| 启动失败：`Permission denied` 且 `/home` 挂载选项里有 **`noexec`** | `noexec` 会导致 JVM/`bin/opensearch` 无法执行 | 把部署根挪到非 `noexec` 的盘（或让平台方去掉 `noexec`；**`nosuid`/`nodev` 无需处理**，见 步骤 7） |
| 启动失败：`OPENSEARCH_INITIAL_ADMIN_PASSWORD` 相关报错 / demo 配置装不上 | 口令没设或**不满足官方要求**（8~100 字符、四类字符、zxcvbn 达 strong、不与 admin 相似） | 换强口令（`openssl rand -base64 24`），重新跑 步骤 11 |
| 启动报权限错误：`AccessDeniedException` / 读不到 `config/opensearch.yml` | 属主或权限不对 | 步骤 10：`chown -R opensearch:opensearch`；`config/opensearch.yml` 640；`opensearch.keystore` 600；`plugins/opensearch-security/tools/*.sh` 750 |
| 日志报 keystore 相关错误 | `config/opensearch.keystore` 缺失或权限不对 | `sudo -u opensearch /home/opensearch/app/bin/opensearch-keystore list` 看是否可读；必要时重建并用 systemd 环境变量注入敏感值 |
| 日志报"没有证书 / 集群形成失败（`Transport client authentication no longer supported` 之类）" | 我们替换 `opensearch.yml` 时**把 transport 证书那几行丢了** | 对照 步骤 12/步骤 12 与资产文件，把 `plugins.security.ssl.transport.*` 三行补回；证书文件在 `config/*.pem`，确认存在 |
| 建索引报 `Unknown engine [nmslib]` / 引擎相关错误 | 3.x 上**不该再用 `nmslib`**（legacy/deprecated） | 步骤 16：`method.engine` 必须是 `lucene`（本方案取值）；已有 `nmslib` 旧索引要重建 |
| kNN 查询报解析错误 / 命中为空 | 查询体结构与版本不匹配：3.x 是 **`"knn": { "<字段名>": {...} }`**（字段名是第一层键） | 对照 附录 E · 索引设计 · 查询 的 3.x 结构；用 `curl` 手工打一次最简查询（只要 `vector` + `k`）先确认能通，再加 `filter` |
| 报 `k` 超出范围 / 要返回很多条 | 3.x 官方：`k` 合法区间 **[1, 10000]** | 附录 E · 索引设计 · 查询：本项目用 `k=50`（= 生效召回数 `RETRIEVAL_RECALL=50`），远在范围内；若有人手工调大，别超过 10000 |
| 快照报"未知字段 / 400" | 请求体里混进了该 API 不支持的字段（例如早前版本误加的 `expand_wildcards`） | 步骤 20 + 脚本：请求体只允许 `indices`/`ignore_unavailable`/`include_global_state`/`partial` |
| curl 报 SSL 错误（`wrong version number` / `certificate verify failed`） | REST 层 TLS 与请求协议不匹配：官方默认是 HTTPS，本方案走 HTTP | 确认运行时 `config/opensearch.yml` 里是 `plugins.security.ssl.http.enabled: false`（步骤 12），改完 `systemctl restart opensearch` |
| curl 用 HTTP 时报 `Empty reply from server` | 仍是 HTTPS 端口在应答 | 同上；先用 `curl -k https://127.0.0.1:9200/` 确认服务本身活着 |
| 认证失败 `401 Unauthorized` | 用户名/口令不对 | `curl -u admin:'<口令>'` 单独验证；口令含特殊字符时**用单引号包住**；确认用的是本轮设的强口令（不是 demo 默认值） |
| **`yellow`** | 主分片就绪、**副本未分配** | **单节点上几乎总是"某个索引设了 replica ≥1"**：`PUT /<索引>/_settings {"index":{"number_of_replicas":0}}`；用 `_cat/indices?v&health=yellow` 找出来逐个改 |
| **`red`** | 有**主分片**未分配 ⇒ 检索/写入报错 | `_cat/indices?v` 与 `_cluster/allocation/explain`；单节点上常见原因是**数据目录权限**（步骤 4）或**磁盘满** |
| 磁盘满 / 索引变只读（flood stage） | 达 95% 水位 | ① 清旧快照（脚本保留期调小）；② **查日志是否在涨**：`du -sh /home/opensearch/logs`，没配轮转就照 步骤 18 的"日志轮转"配一份；③ 解锁：`PUT /<索引>/_settings {"index.blocks.read_only_allow_delete":null}`；④ 复核磁盘水位（参考 B · 监控要看哪些指标） |
| 首次检索特别慢（几十秒~分钟级） | 索引里没有向量：**旧版本是在问答路径上惰性补算**（`ai-1.2.0` 已把向量化**前移到解析入库**，正常不应再出现；仍在补算会记 `ai.vector.lazy_backfill`，**持续非 0 就是漏了前移**） | 按 步骤 19 ①-4 先灌满索引再切 `VEC_BACKEND`；核对 `ai.vector.lazy_backfill`（应为 0）与 `ai.vector.indexed` |
| 快照失败 `repository_exception` / 报目录不可写 | 仓库目录属主不对或不在 `path.repo` 之下 | `sudo chown -R opensearch:opensearch /home/opensearch/backup`；核对 `path.repo` 与脚本 `REPO_LOCATION` 的父子关系 |
| `snapshot-backup.sh` 报无法免密 ssh | `rsync` 到 `.111` 需要免密 | `ssh-copy-id lhim@10.254.212.111`；或临时 `SKIP_REMOTE=1`（但**别长期跳过**，否则没有异地副本） |
| AI 日志"集群不可达（http://…）" | 网络/防火墙/`network.host` 三者之一 | 在 AI 那台跑 步骤 1.6 与 步骤 14 A6；核对 `network.host` 与 步骤 8 白名单 |

---

---

## 参考 C · 未确认项

| 序号 | 未确认项 | 影响 | 谁来确认 |
| --- | --- | --- | --- |
| 1 | **磁盘类型与容量**（当前给定 200GB；需确认 SSD/HDD、是否单盘） | HDD 上 kNN 检索延迟会明显变差；单盘则数据与快照抢 I/O | 你 / 平台方 |
| 1 | **内网 NTP 时间源地址** | 时间不同步会让日志/快照时间戳不可比 | 平台方 |
| 2 | **快照保留期与执行时间**（本文按每晚 02:30、留 7 天写） | 影响备份盘容量与 RPO | 你（改脚本 `KEEP_DAYS` 与 crontab） |
| 3 | **现网真实文档数与切片总数**（容量表在 附录 E · 索引设计 · 容量与分片，按 30 切片/份估算） | 决定"要不要现在就上"以及 步骤 19 的重建耗时 | 你（`/documents` 或自检页） |
| 4 | **主系统何时回填"切片带项目/密级元数据"**（字段与接口**已就绪**：`PUT /documents/{id}/meta` 补录 `attachment_id`/`project_id`/`phase_code`/`security_level`/`doc_type`/`source`，回填后 `POST /admin/reindex?docIds=<id>` 即写入索引、**不必重解析**；但**谁来填、什么时候填**未定） | 不填就只能按 `doc_id` 做硬作用域，"先过滤再检索"的**项目/密级**维度只是**能力就绪**、不是**功能可用** | 你 / 开发方 |
| 5 | **平台是否真的不支持降维**（本仓库实测传 `dimensions` → HTTP 400） | 决定 `VEC_EMBED_DIMENSIONS` 永远为 0（本文按"不传"写） | 平台方 |
| 6 | **AI 能力服务实际跑在哪台机器上**、其容器是否设 `-Xmx` | `OPENSEARCH_URL` 已定（指向生产 `.110`）；本项只影响 步骤 8 的防火墙白名单与堆预算 | 你 |
| 7 | **是否要上监控/告警栈**（Prometheus / Grafana / OpenSearch 告警插件） | 参考 B · 监控要看哪些指标 目前**只能手工巡检**（`ai-1.2.0` 已给出 micrometer/actuator 指标与 `/health?with_metrics=true`，但**没有告警**） | 你 |
| 8 | **`.111` 演练机的 ssh 免密是否允许配置**（`snapshot-backup.sh` 的 rsync 需要） | 不配就只能 `SKIP_REMOTE=1`，等价于没有异地副本 | 你 |
| 9 | **Dashboards 系统索引的实际名字**（脚本按 `.opensearch_dashboards*` 与 `.opendistro_security` 写，点号前缀未经真机确认） | 若实际名字不同，快照会"成功但没备到"，恢复后表现为界面空白/权限回默认 | 你（`_cat/indices?v` 看一眼，见 步骤 17） |
| 10 | **`local` 与 `opensearch` 的 top-k 是否真的一致**（查询体与索引 mapping 已按 3.x 结构落地并有离线断言，但**真集群从未实测**） | 决定 `VEC_BACKEND=opensearch` 能不能算"切换成功" | 你 / 开发方（**必须**先按 步骤 19 ①-3 跑一次顺序对照；这一步不在离线测试覆盖范围内） |

> **已在本次部署中定死、因此不再列为未确认**：部署形态（**tar + systemd，不用 Docker**）、机器（**`pdmsosgh` / x86_64**）、
> 版本（**OpenSearch 3.1.0 + Dashboards 3.1.0**）、节点数（**单节点**）、部署根（**`/home/opensearch/`**）、
> 建账号方式（**有家目录 + `passwd -l`**）、权限模型（**权限共享**，步骤 4）、安全模式（**HTTP + basic auth**，步骤 17）、
> Dashboards 账号（**用 `admin`**）、cron 归属（**root**）、logrotate（**本机自建**）、防火墙（**firewalld**）、
> 副本数（**0**）、堆大小（**8g**）、是否需要 Dashboards（**需要**，参考 B）、
> **向量后端选型（生产用 `VEC_BACKEND=opensearch`，`local` 只作回滚路径）**、
> **两台机器的角色（`.110` = 生产；`.111` = 冷备 + 演练机，不接业务、只收快照）**。

---

## 附录 A · 将来加第 3 台 → 3 节点集群（升级路径）

> **什么时候做**：① 需要**两个 AI 实例**做无中断检索；② 切片 **>50 万**（单节点磁盘/恢复窗口吃紧）。其余情况**不要动**（附录 D · 0.3）。

| 参数 | 单节点现值 | 3 节点改为 | 说明 |
| --- | --- | --- | --- |
| `discovery.seed_hosts` | **不写** | `os01,os02,os-mgr` | 3 个节点的**可解析名或 IP**；顺序不重要，但要**一致** |
| `cluster.initial_master_nodes` | **不写** | `os01,os02,os-mgr` | ★**只能在数据目录为空时写一次**：写错/改错必须清 `data/` 重来（最容易踩的坑） |
| `discovery.type` | `single-node` | **删掉这一行** | 集群模式不能有它 |
| 第 3 台角色 | —— | `node.roles: [cluster_manager]` | **dedicated cluster-manager，不存数据**（可用轻量机） |
| 数据节点角色 | （默认全角色） | `node.roles: [data, ingest]` | 两台老机器 |
| 副本数 | **0** | **1** | 此时才有第二台机器放副本，green 才成立 |
| heap | `-Xms8g -Xmx8g` | 数据节点不变；管理节点用 `-Xms2g -Xmx2g`（**估算（假设：管理节点不存数据）**） | 各写各的 `jvm.options.d/heap.options` |
| 防火墙 | 9200 白名单 | **另加 9300**（节点间 transport）在两两之间放行 | 步骤 10 ⑤ |
| 快照仓库 | 每台本地 `fs` 仓库 | 建议共享一个（NFS）或对象存储 | 步骤 18 |

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

---

## 附录 B · 变更项（**不属于本次部署步骤**）

> 本附录只登记"将来要加固/改变基线时才会做的事"。**主线（附录 D~步骤 7）里不出现这些分支**，照主线做即可。
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
**要改什么**：`sudo chmod -R 2750 /home/opensearch/data`（**只这一个目录**，其余仍按 步骤 4 共享）。
**注意**：做完后 `lhim` 不能再往 `data/` 传文件；备份/恢复走 参考 B · 快照与恢复，不需要人工往 `data/` 放东西。

### B.4 给 Dashboards 改用专用只读账号（不用 `admin`）

**什么时候考虑**：安全基线要求"服务账号最小权限"。
**要改什么**：在 `app/config/opensearch-security/` 下加内部用户与角色
（最小集合：可读写 `.opensearch_dashboards*`、只读业务索引），
用 `app/plugins/opensearch-security/tools/hash.sh` 生成口令哈希，再跑
`securityadmin.sh -cd … -icl -nhnv` 重载配置；最后改
`dashboards/app/config/opensearch_dashboards.yml` 的 `opensearch.username/password`。
**注意**：`securityadmin.sh` 的证书路径以 `app/config/*.pem` 实际文件名为准（步骤 17 生成的那套）。

### B.5 REST 层改走自签 TLS（等保要求传输加密时）

**什么时候考虑**：合规明确要求"管理面传输加密"。
**要改什么**：把 `config/opensearch.yml` 的 `plugins.security.ssl.http.enabled` 改成 `true`，
并补 `plugins.security.ssl.http.pemcert_filepath` / `pemkey_filepath` / `pemtrustedcas_filepath`（证书就是 步骤 17 生成的那套）；
AI 侧 `OPENSEARCH_URL` 由 `http://` 改 `https://` 并让它信任该 CA；
Dashboards 的 `opensearch.hosts` 同步改 `https://`，并配 `opensearch.ssl.certificateAuthorities` 与
`opensearch.ssl.verificationMode`。
**代价**：自签证书有有效期，到期不换会全线连不上；证书轮换要重启服务。

### B.6 日志轮转交给平台方统一采集

**什么时候考虑**：机房已有统一日志平台（集中采集 + 轮转）。
**要改什么**：删掉本机自建的 `/etc/logrotate.d/opensearch`（步骤 18），改由平台方采集。
**验收口径不变**：`du -sh /home/opensearch/logs` 不单调增长，`df -h /home` 有足够余量。

### B.7 加第 3 台 → 3 节点集群

见 **附录 A**（只改两个参数）。

---

---

## 附录 C · 官方文档逐条核对（官方怎么写 → 我们原来怎么写 → 现在怎么写）

> 核对对象：官方 Tar 安装页、安全插件启停页、demo 配置页、安全设置页（链接见文首）。
> **"原来"指本文件的 Docker 版初稿与本仓库当时的资产。**

| # | 项 | 官方怎么说 | 我们原来怎么写 | 现在改成什么 |
| --- | --- | --- | --- | --- |
| 1 | **默认 REST 层是 HTTPS** | Tar 页 Step 3 的验证命令是 `curl -X GET https://localhost:9200 -u 'admin:<password>' --insecure`；demo 脚本"applies self-signed TLS certificates" | 写的是"内网 HTTP + basic auth"，**没有显式处理 TLS** | **第四章 步骤 12** 定为写法：**HTTP + basic auth**，在配置资产里**显式写下** `plugins.security.ssl.http.enabled: false`（自签 TLS 的改法移到**附录 B**，不在部署步骤里） |
| 2 | **口令复杂度** | demo 配置页四条：8~100 字符；四类字符各至少一个；**zxcvbn 达 strong 或以上**；**不得与用户名 admin 相似** | 只写了">=8 位 + 四类字符齐全" | **步骤 11** 按官方**四条**逐字写全，并说明"四类字符齐全也可能被拒" |
| 3 | **`DISABLE_INSTALL_DEMO_CONFIG=true` 的后果** | 镜像/安装脚本 README 的 Scenario 3：跳过 demo 脚本 ⇒ **没有证书与安全配置**，安全插件开着就会**启动失败** | Docker 编排里设了 `DISABLE_INSTALL_DEMO_CONFIG: "true"` 且同时启用安全插件 —— **这是错的，会起不来** | **删掉 Docker 路线**；tar 路线改为"**让 demo 脚本正常跑**（拿到证书与 admin 用户），用强口令保证口令安全"，并在 步骤 5/步骤 6 讲清顺序 |
| 4 | **`OPENSEARCH_INITIAL_ADMIN_PASSWORD` 的用法** | tar 路线：先 `export OPENSEARCH_INITIAL_ADMIN_PASSWORD=<强口令>`，再 `./opensearch-tar-install.sh`；2.12 起不设会失败 | 把它当成 Docker 的环境变量写在 compose 里 | 改为 **步骤 11** 的 `export` + 脚本（tar 语汇），并说明它只在**首次**生效 |
| 5 | **安装脚本的实际作用** | `opensearch-tar-install.sh` 会"apply a generic configuration… defines some environment variables and also applies self-signed TLS certificates"，并把安全设置写进 `config/opensearch.yml` | 没提这个脚本 | **步骤 11/步骤 12** 说明它的作用与"改了 `opensearch.yml`"，并强制**紧接着**用我们的配置接上（先备份 `config/opensearch.yml.demo.bak`） |
| 6 | **transport 层证书不能漏** | demo 脚本写入的 `opensearch.yml` 含 transport 证书路径；安全插件默认要求 transport 加密（缺证书 ⇒ 集群起不来 / 报 `Transport client authentication no longer supported`） | 我们的配置模板**只有** `plugins.security.ssl.http.enabled`，把 transport 段丢了 | 配置资产补回 `plugins.security.ssl.transport.pemcert_filepath` / `pemkey_filepath` / `pemtrustedcas_filepath` + `allow_unsafe_democertificates` / `allow_default_init_securityindex`，并解释**为什么不能整份覆盖成"干净配置"** |
| 7 | **`vm.max_map_count` 与 swap** | Tar 页 Step 2：`sudo swapoff -a`；`vm.max_map_count=262144`（官方示例改 `/etc/sysctl.conf` 后 `sysctl -p`，再 `cat /proc/sys/vm/max_map_count` 校验） | 有（写了 `sysctl.d` 与重启校验） | 与官方一致；保留 `sysctl.d` 写法（更不容易和别人的行冲突）并补上 `cat /proc/sys/vm/max_map_count` 这条官方校验 |
| 8 | **systemd unit 示例** | 官方给了完整示例：`Type=forking`、`ExecStart=/opt/opensearch/bin/opensearch -d`、`User/Group=opensearch`、`StandardOutput=journal`、`LimitNOFILE=65535`、`LimitNPROC/LimitAS/LimitFSIZE`、`TimeoutStopSec=0`、`KillSignal=SIGTERM`、`KillMode=process`、`SendSIGKILL=no`、`SuccessExitStatus=143`、`TimeoutStartSec=75`；并提醒 **tar + systemd 非生产推荐**（要 systemd 就用 RPM） | 原来用 Docker，**没有 unit** | 资产 [`opensearch.service`](../deploy/opensearch/opensearch.service) **照官方示例改写**，只改三处：路径→`/home/opensearch/app`、保留 User/Group 语义、**补 `LimitMEMLOCK=infinity` + `Environment=MAX_MAP_COUNT=262144`**，并保留官方的"非生产推荐"提醒；unit 由 **步骤 10** 落位、**步骤 13** 启动 |
| 9 | **memlock 与 `MAX_MAP_COUNT`** | 官方 configuring-opensearch：systemd 托管且开 `bootstrap.memory_lock` 时，需要放开 memlock 并设 `MAX_MAP_COUNT=262144` | 原 compose 用 `ulimits.memlock: -1`（容器语汇） | 改为 unit 的 `LimitMEMLOCK=infinity` + `Environment=MAX_MAP_COUNT=262144`，并在 步骤 10/第 1 节 A4 给出验证命令 |
| 10 | **专用非 root 用户** | Tar 页：`sudo adduser --system --shell /bin/bash -U --no-create-home opensearch`；`sudo usermod -aG opensearch $USER`；`sudo chown -R opensearch /opt/opensearch/`；OpenSearch 不能以 root 运行 | 原方案靠容器内 uid 1000（宿主 `chown 1000:1000`） | 步骤 4/步骤 7 全部改为**系统用户 `opensearch` + `chown -R opensearch:opensearch`**，并给"权限不对"的排障行 |
| 11 | **端口 9600** | 官方（Docker 与 tar）都提 `9200`（REST）与 `9600`（Performance Analyzer） | 原来只讨论 9200，9600 未表态 | **步骤 8** 明确：**9600 不放行**（本方案不用 Performance Analyzer） |
| 12 | **数据/日志/备份路径与属主** | 官方 tar 路线不规定安装路径（"The tarball installation does not define a specific installation path, users, roles, or permissions"），但要自己保证权限正确；官方示例把 `data` 放在安装目录下 | 原来绑定宿主目录 `1000:1000`（容器语汇） | 附录 D · 0.2/步骤 4 定稿 `/home/opensearch/{app,config,data,logs,backup}`，**data/logs 放在安装目录之外**（升级不碰数据），属主 `opensearch:opensearch`、权限 750（文件 644 / keystore 600） |
| 13 | **升级与"复制 config"** | 官方 tar 升级思路是"换程序目录、带上你自己的 `config/`"（安全配置与证书都要自己接管） | 原来是 `docker load` 换 tag；升级段只写了"把证书/`opensearch-security/`/keystore 也带过去"，**没有属主与权限** | 参考 B · 升级路径 改为 stop → 解包新目录 → **复制 `config/opensearch.yml` + `jvm.options.d/` + demo 生成的证书与 `opensearch-security/`** → **⑥ 明确属主与权限（`*.pem`/`opensearch-security/*.yml` = `640`、`keystore` = `600`、`tools/*.sh` = `750`，一律 `opensearch:opensearch`）** → chown/chmod → 换目录 → start；旧目录留作回滚。**升级后最常见的 644/640 事故就发生在这里** |
| 14 | **`plugins.security.ssl.http.enabled` 的默认值** | 安全设置页：该设置 "Default is `false`"，但 demo 脚本会把它设成 **true**（所以 quickstart 用 HTTPS） | 原来只有"TLS 可选"一句，说不清默认到底是什么 | **步骤 12** 明确：字段默认 false，但 demo 配置会改成 true，因此我们**必须显式写 false** |
| 15 | **禁用安全插件的代价** | 启停页：禁用后"exposes the configuration index… If the index contains sensitive information, make sure to protect it"；安全设置页：`plugins.security.disabled` 的警告是"can expose your configuration (including passwords) to the public"；且**禁用/启用需要整集群重启** | 原方案把"禁插件"只当作"简单"选项 | 步骤 8 方案 C 明确列为**不采用**，并原样引用官方的暴露警告与"需要重启"这一操作代价 |
| 16 | **下载 URL（官方口径留档）** | 官方 Tar 页给的是 `wget https://artifacts.opensearch.org/releases/bundle/opensearch/<版本>/opensearch-<版本>-linux-x64.tar.gz`（**页面没有给 `.sha512` 的说明**） | 原文没有校验步骤 | 本机由运维在浏览器自行下载后放到 `/home/opensearch/`，**本次部署不做校验步骤**（包已在位，见 第 1 节）；此处仅留官方 URL 形式备查。**不改动任何主线步骤****同目录下有 `.sha512`（165 字节文本，`sha512sum -c` 可直接吃），给出 Windows/Linux 两种校验命令 + "校验不通过绝不安装" |
| 17 | **架构** | 官方按 x64 / arm64 分别给下载命令 | 原方案假设这两台是 aarch64（银河麒麟） | 附录 D · 0.2 明确这两台是 **x86_64**，用 `linux-x64.tar.gz`；并提醒别和既有 aarch64 服务器的产物搞混 |
| 18 | **`jvm.options.d` 覆盖目录** | 官方 Tar 页 Step 4 是让你直接改 `config/jvm.options` 里的 `-Xms/-Xmx` | 原来把堆写在容器环境变量 `OPENSEARCH_JAVA_OPTS` 里 | **步骤 12** 用 `config/jvm.options.d/heap.options`（**升级时 `jvm.options` 会被新版本覆盖，我们的文件不会**），并在资产里解释为什么是 8g |
| 19 | **快照仓库位置** | 官方快照文档要求仓库 location 在 `path.repo` 之下 | 原方案把 `path.repo` 与宿主目录直接对应（容器语汇） | 步骤 18 明确 `path.repo: /home/opensearch/backup` 与脚本 `REPO_LOCATION=/home/opensearch/backup/fs-repo` 的**父子关系**，并给出 `repository_exception` 的排障行 |
| 20 | **专用账号的建法（D 方案偏离官方）** | 官方 Tar 页给的是 `sudo adduser --system --shell /bin/bash -U --no-create-home opensearch`（**无家目录的系统账号**） | 上一版照官方写 | **D 方案要求部署根就是该账号的家目录**，所以改为 `sudo useradd -m -d /home/opensearch -s /bin/bash opensearch` + `sudo passwd -l opensearch`（**有家目录、锁登录**）。这是**有意偏离官方示例**，原因写在 步骤 4 |
| 21 | **`/home` 挂载前提** | 官方 tar 页只要求 `vm.max_map_count`/swap，**不规定安装路径**（"does not define a specific installation path"） | 上一版把部署根放在 `lhim` 家目录下，并要求 `chmod 711 /home/lhim` | 部署根改为 `opensearch` 自己的家目录 ⇒ **删掉 `chmod 711` 那条**；新增 **步骤 4.2 `/home` 挂载前置检查**（`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home`：确认本地盘/非 NFS/空间够，并给 `path.data` 退路；说明 **`nosuid`/`nodev` 无害**，只有 `noexec` 会挡路） |
| 22 | **Dashboards 版本兼容** | 官方 Dashboards tar 页是独立页，**没有明写"必须与 OpenSearch 同版本"**；但官方版本矩阵与下载页都要求两者版本一致 | 上一版根本没提 Dashboards | **步骤 7 与 第 1 节** 把"**三个数字都要一样**（`3.1.0`）"写成硬约束；两个包已在位，Dashboard 的安装从 参考 A · 容量与分片 开始（下载与校验不在本次步骤内） |
| 23 | **Dashboards 配置文件字段名** | 官方示例 `config/opensearch_dashboards.yml`：`server.host`（默认 `"localhost"`，"usually means remote machines will not be able to connect"）、`server.port`、`opensearch.hosts`、`opensearch.username/password`、`opensearch.requestHeadersWhitelist`（**已标 DEPRECATED**，新名 `opensearch.requestHeadersAllowlist`）、`opensearch.ssl.verificationMode`、`opensearch.memoryCircuitBreaker`；多租户键 `opensearch_security.multitenancy.enabled` 出现在安全插件文档与官方示例注释里 | 上一版没有 Dashboards 配置 | 参考 A · 容量与分片 逐项照官方字段名写进资产 `opensearch_dashboards.yml.example`：用**新名** `requestHeadersAllowlist`（并在注释里留旧名对照）、`server.host` 显式改内网 IP、`opensearch.hosts` 用 `http://`（与 步骤 8 方案 A 绑定）、多租户项保留并注明"与 workspace 互斥" |
| 24 | **Dashboards 的 systemd** | 官方 Dashboards tar 页**只给三步**（解包 → 改 yml → `./bin/opensearch-dashboards`），**没有 systemd 示例** | 上一版没有 | 步骤 17 + 资产 `opensearch-dashboards.service`：**骨架照官方 OpenSearch tar 页那份 unit**，按 Node.js 运行方式改 3 处并写明原因（`Type=simple` 而非 `forking`、`KillMode=mixed` 以收掉 Node 子进程、`Environment=NODE_OPTIONS=--max-old-space-size=1024`） |
| 25 | **Dashboards 的 Node 堆默认值** | 官方 `config/node.options` 里给的示例是 **`--max-old-space-size=4096`**（默认 4GB 老生代）；官方示例 yml 提到 `opensearch.memoryCircuitBreaker` 的阈值基于该值 | 上一版没有 | **步骤 17** 明确"默认 4GB、16GB 机器上必须封顶"，本方案在 unit 里压到 **1024MB**，并给出"官方支持两种设置方式（`config/node.options` 或 `NODE_OPTIONS`），我们选后者"的取舍 |
| 26 | **快照默认不含系统索引（脚本级修正）** | 官方快照/恢复文档说明：恢复快照时不能包含全局状态、且**不恢复 `.opendistro_security`**（除非 admin 证书签名）⇒ 系统索引默认不在快照里 | 上一版脚本只备 `pm-ai-chunks` ⇒ 恢复后 Dashboards 空白、安全配置回默认 | 步骤 20 + 脚本：默认索引列表改为 `pm-ai-chunks,.opensearch_dashboards*,.opendistro_security`；给出三条验证命令（快照后看 `indices`、恢复后看 `_cat/indices`、浏览器里看仪表盘） |
| 27 | **k-NN 引擎可用性（3.x）** | `docs/latest` 的 Methods and engines 页：引擎有 **Lucene / Faiss / NMSLIB（legacy，标注 "now deprecated"）/ JVector**；`engine` 省略时**默认 `faiss`**；Lucene 支持 `hnsw`（space：`l2`/`cosinesimil`/`innerproduct`），Faiss 支持 `hnsw`/`ivf`，其中 **`cosinesimil` 对 Faiss 是 2.19+**，且"Faiss 用 `cosinesimil` 时向量会被自动归一化 ⇒ 存储值与你输入的不同"；另有 **JVector（`disk_ann`，需单独插件 `opensearch-jvector`，不能与 opensearch-knn 同集群）** | 上一版把 `engine` 写成"可选 lucene/faiss"，并说"nmslib 需要额外参数"，没说清楚 3.x 的取舍 | 步骤 8 重写：**默认用 `lucene`**（与代码 `OpenSearchIndex` 一致、支持带过滤检索、官方定位"smaller deployments / <1000 万向量"）、`faiss` 作为可选并**明确写出归一化的副作用**、**3.x 新索引不要用 `nmslib`**、并登记 JVector 这条路（未采用，需额外插件） |
| 28 | **k-NN mapping 的键名与默认值** | k-NN vector 页：`dimension` 合法区间 **[1, 16000]**；`data_type` 默认 `float`（可选 `binary`/`byte`/`float`/`half_float`）；`space_type` **既可写在字段层也可写在 `method` 内**（"This value can also be specified within the `method`"），默认 `l2`；`mode` 默认 **`in_memory`**（`on_disk` 为低成本路线，配合 `compression_level`，默认 `32x`、默认开启 rescore）；方法参数键名是 **`parameters`**（不是 `params`）；`m` 默认 **16**、`ef_construction` 默认 **100** | 上一版把 `space_type` 同时写在字段层与 `method` 内（两种写法混用），`ef_construction` 写 128 没说这是"偏离默认"，也没提 `dimension` 上限与 `mode` 默认值 | 步骤 16 改为：`space_type` **只写字段层**、`data_type: float` 显式写出、`method` 内只留 `name`/`engine`/`parameters`；附录 E 补 `dimension` 上限 16000（我们的 4096 合规）、`mode` 默认 `in_memory`（故不写）、`m`/`ef_construction` 与官方默认值的对照。**`ai-1.2.0` 的代码取 `ef_construction=100`（官方默认），与 步骤 16 示例的 128 不同——这是刻意偏差，已在 步骤 19 ①-1 写明两者不能混用** |
| 29 | **k-NN 查询语法（3.x）** | k-NN query 页的请求体结构：**`"knn": { "<vector_field>": { "vector": [...], "k": n, "filter": {...}, "method_parameters": {...}, "rescore": {...} } }`** —— **字段名直接是 `knn` 下第一层**；`filter` **只能配合 `faiss`/`lucene`**；`k` 合法区间 [1, 10000]；`method_parameters.ef_search` 对 **lucene** 的行为是"与 `k` 取较大者传给引擎" | 上一版按 2.x 的固定键结构写（`knn.vector.vector`），没有区分"结构里的 `vector` 是键名还是字段名"；适配层代码也按 2.x 固定结构拼查询体 | **文档侧**：附录 E · 索引设计 · 查询 写明 3.x 语法（`knn` 下第一层是**字段名**）与 `filter` 的引擎前提（文档 步骤 16 是手工建索引的示例）。**代码侧（`ai-1.2.0`）**：`OpenSearchIndex.search()` 已改为 3.x 结构并带 `filter` 硬作用域，`OpenSearchIndexTest` 有离线断言；但**真集群未实测**，切换前必须按 步骤 19 ①-3 做"top-k 顺序与 local 一致"的对照（登记在 参考 C 第 10 条） |
| 30 | **快照请求体字段（3.x）** | Create Snapshot API 页：请求体字段**只有** `indices`（支持 `,` 列表与 `*` 通配与 `-` 排除，**"Don't put spaces between items"**）、`ignore_unavailable`（默认 false）、`include_global_state`（默认 true）、`partial`（默认 false）；**没有 `expand_wildcards`**。官方示例本身就用了通配 + 系统索引：`{"indices": "opensearch_dashboards*,my-index*,-my-index-2016", ...}` | 上一版脚本在请求体里加了 **`"expand_wildcards": "all"`**，并声称"通配符必须显式展开"——**这是错的**（该参数不属于这个 API） | 脚本**移除 `expand_wildcards`**（注释留档说明为什么不能加回来）；`indices` 直接用逗号 + `*`（官方原生支持）；文档 步骤 20 同步改正 |
| 31 | **安全插件键与 tar 安装脚本（3.x 是否仍存在）** | 3.x 的 Tar 页与 security-settings 页：`opensearch-tar-install.sh` **仍存在**（Step 3 Option 1 仍叫这个名字，且仍会"apply self-signed TLS certificates"并改 `config/opensearch.yml`）；`OPENSEARCH_INITIAL_ADMIN_PASSWORD` **仍是 2.12+ 的必需项**；口令四条要求（8~100 字符、四类字符各一、zxcvbn 达 strong 以上、不得与 admin 相似）在 3.x 文档里**未变**；`plugins.security.ssl.http.enabled`、transport 三个 `pem*_filepath`、`allow_unsafe_democertificates`、`allow_default_init_securityindex` 这些键**在 3.x 文档里都还在**（且 Tar 页的追加脚本仍然写 `plugins.security.allow_default_init_securityindex: true`） | 上一版已按 2.x 写对；但没说明"3.x 是否仍成立" | 步骤 5/步骤 8 内容**不变**，在附录 B 这里登记"已按 3.x 复核、四项键与口令要求均未变" |
| 32 | **heap 覆盖目录 `jvm.options.d/` 与自带 JDK（3.x）** | 3.x Tar 页：**"The tarball is a self-contained directory with everything needed to run OpenSearch, including an integrated Java Development Kit (JDK)"**；Step 4 给的是 `export OPENSEARCH_JAVA_HOME=/path/to/opensearch-<版本>/jdk`；调整堆的官方写法是**改 `config/jvm.options`**（页面未提 `jvm.options.d`） | 上一版声称 `jvm.options.d/` 是"官方支持的覆盖目录" | 步骤 6 保留 `jvm.options.d/heap.options` 做法，但**改口径**：把它写成"**我们的做法**（借 JVM 官方启动脚本会读取 `jvm.options.d/*.options` 的机制）"，并注明**官方 Tar 页只写了改 `config/jvm.options`**；同时写入 **3.x 自带 JDK**（`app/jdk`）与 `OPENSEARCH_JAVA_HOME` 的设置方式，明确"宿主不需要预装 Java" |
| 33 | **systemd 示例（3.x 是否与 2.x 相同）** | 3.x Tar 页的 systemd 一节与 2.x **逐行相同**（`Type=forking`、`ExecStart=/opt/opensearch/bin/opensearch -d`、`User/Group`、`StandardOutput=journal`、`LimitNOFILE=65535`、`LimitNPROC/LimitAS/LimitFSIZE`、`TimeoutStopSec=0`、`KillSignal=SIGTERM`、`KillMode=process`、`SendSIGKILL=no`、`SuccessExitStatus=143`、`TimeoutStartSec=75`），并且**同样标注"只适合非生产、要 systemd 就用 RPM"**；Dashboards tar 页在 3.x **仍然只给三步、仍没有 systemd 示例** | 上一版已照该示例写 | **保持**（`opensearch.service` 不动逻辑），只在附录 B 登记"3.x 逐行相同"；Dashboards 的 unit 继续按"照官方 OpenSearch unit + 按 Node 运行方式改 3 处"的口径，并在 步骤 17 与 unit 注释里标明"官方无示例" |
| 34 | **Dashboards 的 yml 键与 Node 堆默认值（3.x）** | 3.x 官方示例 `config/opensearch_dashboards.yml`：`opensearch.requestHeadersWhitelist` **仍标注 DEPRECATED**（新名 `requestHeadersAllowlist`）、`opensearch_security.multitenancy.enabled` 仍存在且官方注释仍写"**workspace 与多租户互斥**"；`config/node.options` 的示例默认**仍是 `--max-old-space-size=4096`**（`--max-semi-space-size=64` 注释保留） | 上一版已按这套写 | **保持**，附录 B 登记"3.x 复核：键名/互斥关系/4096 默认值均未变" |
| 35 | **资产落位与权限（官方未覆盖，属本项目运维约定）** | 官方 Tar 页只说"不定义安装路径/用户/权限，要自己保证正确"；安全插件文档要求配置不得"组/其他人可写"；systemd 要求 unit 属主为 root | 前几版把落位/属主/权限散落多处，**出现两套说法**（`644` 与 `640` 混用；旧版还写了服务器上不存在的相对路径 `deploy/…`） | **步骤 4 收敛为一套**：`deploy/opensearch/` 下 6 个资产的落位表（目标路径 + 属主:组 + 权限）、运行期落位表（含 keystore `600`、`tools/*.sh` `750`、口令文件 `600`）、**7 条核对命令**；权限按本机采用的**共享模型**：目录 `2775` / 文件 `664` / 私钥 `640`；两个 unit 仍 `root:root 644` |

---

## 附录 D · 结论与前提（背景与取舍）


### 0.1 为什么上 OpenSearch（三条触发点，任一成立即必须换）

| 序号 | 触发点 | 现状依据 | 阈值 |
| --- | --- | --- | --- |
| ① | **延迟**：`local` 给**每一次提问**加的固定开销 | 每次检索把范围内**全部切片**的向量 JSON 读出来解析成 `float[]` 再逐条算余弦 | 切片 **≥ 4,000**（≈130 份）时 **+1.0s**；≥15,000 时 **+3.8s**（相对实测 10s 基线 **+38%**） |
| ② | **内存/并发**：常驻 = `16KB × 切片数 × 并发问数` | AI 容器**当前没有 `-Xmx`**（未确认项） | 4 GB 堆、留 50% 余量 ⇒ **4 并发时 ≈3 万切片触顶**；8 并发 ⇒ **≈1.5 万切片** |
| ③ | **架构（最硬的一条）**：`local` 是**进程内状态** | AI 服务**必须单实例**；且切片**没有 `project_id`/密级等元数据**，"先过滤再检索"今天**做不到**，而"先过滤"正是权限合规的前提 | **需要第 2 个 AI 实例时** |

> 数字出处：[`AI模型调用与任务队列评估.md`](AI模型调用与任务队列评估.md) 步骤 7 表与"量化触发点"表（本轮真机读数：
> 1 个 4096 维切片向量 JSON = **38,333 字节**，即 **9.36 字节/维**）。该文档结论：把 OpenSearch 部署从 P2 **上提为 P1**。
>
> 一句话：**规模不是"余弦算不动"的理由（15,000 切片余弦仅 ≈61ms），但"延迟 / 内存 / HA + 元数据过滤"三条已经把切换点推到 ~4,000 切片。**

### 0.2 部署形态结论：**单节点 + systemd**

| 项 | 本方案定稿 |
| --- | --- |
| 节点数 | **1 个**：`os01` = **10.254.212.110**（8 核 / 16GB 内存 / 200GB 磁盘） |
| 第二台 `10.254.212.111` | **冷备 + 演练机**：同一套安装与同样的 unit，**服务默认停着**；只接收每晚快照副本（步骤 20 / 步骤 18） |
| 安装方式 | 官方 **`opensearch-<版本>-linux-x64.tar.gz`** 解包到部署根的 `app/`（**这两台是 x86_64**） |
| 部署根 | **`/home/opensearch/`**（程序 / 配置 / 数据 / 日志 / 备份**分离**，见下方结构） |
| 托管方式 | **systemd**（unit 见 [`opensearch.service`](../deploy/opensearch/opensearch.service)），专用非 root 用户 `opensearch` |
| 索引 | `pm-ai-chunks`，**主分片 1 + 副本 0**（见 参考 A · 容量与分片） |
| 端口 | `9200`（REST）**只放行两台应用服务器**；`9300`（transport）本方案单节点不需对外；**9200 绝不对外** |
| 监听 | `network.host: <本机内网IP>`（不用 `0.0.0.0`） |
| 安全 | **内网 HTTP + basic auth（强口令）**：显式关掉 REST 层 TLS（步骤 12）；transport 层 TLS 保持开启 |
| 数据/日志/快照 | `/home/opensearch/{data,logs,backup}`（**在程序目录 `app/` 之外**，升级换 `app/` 不碰数据） |
| 部署根 | **`/home/opensearch/`** = **专用账号 `opensearch` 自己的家目录**（`useradd -m -d /home/opensearch …` + `passwd -l` 锁登录），见 步骤 4 |
| Dashboards | **OpenSearch Dashboards 3.1.0 与 OpenSearch 同机**（`10.254.212.110:5601`，单独 systemd 服务），见 **步骤 7** |

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
└─ dashboards/          ← OpenSearch Dashboards（见 步骤 7）
   └─ app/              ←   解包出来的内容（bin/ config/ node/ plugins/ …）
      └─ config/opensearch_dashboards.yml
```

> **`config/` 与程序自带 `config/` 的关系（全方案只有一种做法）**：我们只维护部署根 `config/` 下的**一份**
> `opensearch.yml` 与 `jvm.options.d/heap.options`；**安装与每次升级时把它拷贝进 `app/config/`**（**不用软链**）。
> 运行时真正生效的永远是 `app/config/` 那份，`config/` 是它的源头。详见 步骤 6。
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
| **单点故障** | OpenSearch 挂 → kNN 检索不可用（**AI 问答的检索环节受影响**） | ① **快照/备份是强制项**（[`snapshot-backup.sh`](../deploy/opensearch/snapshot-backup.sh) + cron，步骤 18）；② **AI 侧降级口径定死**（步骤 19：**明确报错，不许静默返回无关结果**）；③ systemd `Restart=on-failure` + `enable`（开机自启） |
| 无副本 | 数据盘损坏即丢索引 | 每晚快照 + `rsync` 到 `.111`（步骤 18 / 步骤 20），**RTO 分钟级** |
| 无滚动升级 | 升级要停机（分钟级） | 升级步骤见 参考 B · 升级路径（停服 → 解包新版本 → 复制 `config/` → 启动；旧目录保留即回滚） |

> **重要边界**：OpenSearch 里存的是**可重建物**（切片文本 + 向量 + 索引），与既有运维口径一致——
> **业务数据仍在主系统崖山库**（V1~V14，16 张表）。索引即使全丢，也能从主系统附件重新解析重建（代价是时间，见 步骤 19）。

### 0.5 两台新机器与既有服务器的关系（别搞混）

| 机器 | 角色 | 说明 |
| --- | --- | --- |
| `10.254.212.110` | **OpenSearch 单节点（推荐）** | 本文 第 1 节~参考 B 的主体（x86_64） |
| `10.254.212.111` | **冷备 + 演练机** | 同样的安装与 unit，服务默认停着；存快照副本（步骤 20） |
| 既有 `pdmsappgh` / `ai-kingbase-gh` | 主系统 / AI 能力服务 | **不在这两台新机器上**；本文只改它们的 `.env`（步骤 19） |
| `10.254.208.35:8090` | 平台网关（对话/OCR/向量化/重排） | 与本文无关，仅在重建索引时要它可用 |

---


---

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
> ⚠️ 这条会把 `app/config/` 恢复成官方模板 —— **做完必须按 步骤 12 把我们那份配置再拷回去**。

---

## 附录 E · 索引与检索设计

### 索引设计 · 容量与分片（**先看这张表，再决定参数**）

**单条切片的真实口径**（读代码确认）：向量 = 4096 维 `float32` = `4096 × 4 = 16,384 字节 ≈ 16 KB`；
再加原文（≤500 字）、`doc_id`/`filename` 等字段与倒排，**≈18~20 KB/切片**（正文与倒排为**估算（假设：平均每切片 500 字、中文 3 字节/字 ≈1.5KB 原文）**）。

| 规模（切片数） | 对应文档数（估算，假设：30 切片/份） | 向量本体 | **索引总占用（估算，含原文+倒排+段开销，约 ×1.15~1.25）** | `local`（进程内 JSON）对比 |
| --- | --- | --- | --- | --- |
| 2 万 | ≈670 份 | 16KB × 2 万 ≈ **313 MB** | **≈350~400 MB** | 2 万 × 38,333 B ≈ **766 MB**（且每次提问全量解析） |
| 10 万 | ≈3,300 份 | ≈ **1.56 GB** | **≈1.8 GB** | ≈ **3.8 GB** |
| 100 万 | ≈33,000 份 | ≈ **15.6 GB** | **≈18 GB** | ≈ **38 GB**（且单问 +3.8s 起点） |

> **对照口径**：现有 `local` 的实测值是 **38,333 字节/切片（9.36 字节/维）**——那是**十进制文本 JSON**，
> 同一份数据换成 OpenSearch 的 `float32` 二进制后**只占几百 MB**，检索是毫秒级 kNN。
> 结论：**本项目这个量级不需要量化、也不需要 `on_disk`**（那是百万级/磁盘受限时的手段，见 步骤 8）。
> 磁盘规划：索引数据放 `/home/opensearch/data`，**给快照留出与索引等量的空间**（步骤 18 每天一份、按保留期轮转）。

| 参数 | 值 | 理由 |
| --- | --- | --- |
| `number_of_shards` | **1** | 单节点 + 这个量级（≤100 万切片）：**分片越少越好**。分片是并行度单位，但每个分片都有固定开销（段、堆、文件句柄），1 个分片在单节点上就是最优的检索并行度来源 |
| `number_of_replicas` | **0** | **单节点上副本永远无法分配** ⇒ 只会让状态变 **yellow** 且白占一份盘。**副本不能替代备份**（备份看 步骤 18） |
| `refresh_interval` | 写入期 `-1`（关闭），写完改回 `1s` | 批量灌数据时避免频繁 refresh 产生大量小段 |
| `index.knn` | `true` | kNN 索引开关（mapping 同级 settings） |

### 索引设计 · 索引创建（`knn_vector` 4096 维 / cosinesimil / HNSW）

> **3.x 的写法要点（与 2.x 的差别，先看这三条再抄下面的请求）**：
> ① **引擎只能是 `faiss` / `lucene`**（`nmslib` 是 legacy/deprecated，不要在 3.x 新索引上用）；
> ② `space_type` **写在字段这一层**（官方 k-NN vector 页明确："This value can also be specified within the `method`"，
>    两种都合法；我们选**字段层**，与 `knn_vector` 文档的示例一致，避免两种写法混用）；
> ③ 方法参数统一放 **`method.parameters`**，键名是 `m` / `ef_construction`（**注意不是 `params`**）。
> 我们**不写** `mode` 与 `compression_level`：`mode` 默认就是 `in_memory`（本方案要的低延迟），
> 量化/`on_disk` 是百万级或磁盘受限时才用（见 步骤 8）。

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

> ⚠️ **必须说清楚的一件事**：这几个元数据字段**代码会写，但值从哪来还没定**——
> `OpenSearchIndex.toDocument()`（`ai-1.2.0`）已经把 `DocMeta` 里的
> `attachment_id`/`project_id`/`phase_code`/`security_level`/`doc_type`/`source` **原样写进索引文档**
> （**元数据为空就不写该字段**，查询侧按"不在作用域内"处理，缺失 ≠ 通配）；
> 元数据的**来源**是 `work/meta/`（`DocMetaStore`），由主系统调 `PUT /documents/{id}/meta` 补录。
> 所以本节的 filters 示例要真正生效，需要**主系统把元数据填进来**（`attachment_id → project_id/密级` 的映射在主系统崖山库），
> **什么时候填、谁来填仍未定**（登记在 参考 C 第 4 条）。
> 在那之前，过滤器可以先按 `doc_id` 用起来（见 步骤 19 ①-3 与 [`ai-backend/README.md`](../ai-backend/README.md) 的接口表）。

### 索引设计 · HNSW 与引擎的版本注记

| 项 | 取值 | 注记 |
| --- | --- | --- |
| `engine` | **`lucene`（本方案默认）** | OpenSearch 自带、无需额外插件配置；**本仓库代码写的就是 `lucene`**（`OpenSearchIndex.ensureIndex`）。与 `local` 后端的暴力余弦结果最接近，便于"顺序对得上"的验证。官方对它的定位是 "well suited for smaller deployments" 且**支持带过滤的检索**（与我们的 `filter` 预过滤用法契合），上限约"少于 1000 万向量"——本项目 2 万量级绰绰有余 |
| `engine` | `lucene`（本方案取此值） | 注意：`engine` 省略时的默认值是 `faiss`，所以配置里必须显式写 `lucene`。Faiss 支持 `hnsw` 与 `ivf`（IVF 需训练）；`cosinesimil` 在 **2.19 起**才支持，且官方说明"Faiss 引擎下用 `cosinesimil` 时向量会在索引时被自动归一化（因为 Faiss 内部用归一化向量的内积）⇒ **存储的向量值与你输入的不同**"。切引擎必须**重建索引** |
| `engine` | ~~`nmslib`~~ | **3.x 不要用**：官方 `docs/latest` 把它标为 legacy、"now deprecated"。老集群的 2.x 索引还在用它，但**新索引一律选 `lucene` 或 `faiss`** |
| `name` | `hnsw` | 近似最近邻图索引（`ivf` 需要训练步骤，本方案不用） |
| `m` | `16`（= 官方默认值） | 每个节点的双向连边数（官方要求保持 2~100）；越大召回越好、内存与建索引越慢 |
| `ef_construction` | `128`（官方默认 **100**，我们略调高） | 建图时的候选队列长度；越大图质量越好、建索引越慢 |
| `space_type` | `cosinesimil` | **与 `local` 后端的余弦同一口径**（这一条**未实测**：集群起来后必须做"top-k 顺序与 local 一致"的对照，见 步骤 19 ①-3）。官方对 `lucene` 支持的 space 列表是 `l2` / `cosinesimil` / `innerproduct`，`cosinesimil` 在列 |
| `data_type` | `float` | 官方默认值；`on_disk` 模式只支持 `float` / `half_float` |
| `mode` | **不写**（默认 `in_memory`） | 官方默认 `in_memory`（低延迟优先）。`on_disk` 是 2.17 引入的低成本路线，官方明确"配合 `compression_level`、默认 `32x`、并默认开启 rescore（用磁盘上的全精度向量重排）" |
| `on_disk` / 量化（`sq`/`pq`、`compression_level`） | **本方案不用** | 百万级切片或磁盘受限时才考虑；本项目当前约 2 万切片。要用请先核对你的版本与官方 k-NN 文档，切了必须**重建索引** |
| 维数上限 | **16,000** | 官方 `knn_vector.dimension` 的合法区间是 `[1, 16000]` ⇒ 我们的 **4096 在范围内**（这是选型时容易忽略的一条硬上限） |

> **验证口径（做完 步骤 19 ①-3 的对照才算数）**：对同一份语料、同一批问题，把 OpenSearch kNN 的 top-k 顺序与 `local` 的 top-k **逐条对比**；
> 顺序对不上通常意味着 `space_type` 或字段名写错（对照 [`OpenSearchIndex`](../ai-backend/src/main/java/com/pmgt/ai/module/retrieval/OpenSearchIndex.java) 的注释协议：
> 建索引 → 幂等写入 → 查询页码一致 → 顺序与 local 一致）。

### 索引设计 · 写入（`_bulk`，`_id` = 切片键，幂等 upsert）

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
> 这是"不需要额外版本号或重建流程"的前提，也是**可中断续跑**的基础（见 步骤 19）。
> **`vector` 的生成**：必须来自平台向量化接口 `POST {LLM_BASE_URL}/embeddings`（`{"model":"Qwen3-VL-Embedding-8B","input":["<切片文本>"]}`，
> 返回 `data[i].embedding`），**不要自己在文档里编造数字**——这里写 `<4096 个数字>` 就是因为本文件不造假数据。

### 索引设计 · 查询（预过滤 + kNN + hybrid）

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

> **⚠️ 这处"巧合"已经消除（`ai-1.2.0`）**：
> [`OpenSearchIndex.search()`](../ai-backend/src/main/java/com/pmgt/ai/module/retrieval/OpenSearchIndex.java)
> 本轮已按 **3.x 结构**拼查询体（`knn` 下第一层是**字段名**），并带 `filter` 硬作用域；
> 结构有**离线断言**（`OpenSearchIndexTest` 直接断言请求体）。
> 但**离线断言不等于真机验证**：真集群上"top-k 顺序与 `local` 一致"**仍未实测**，
> 切换前必须按 步骤 19 ①-3 跑一次对照（登记在 参考 C 第 10 条）。

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

---

## 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| docs-1.6 | 2026-09-23 | 首版（**已被下方"修订二"取代**）：为两台新服务器出 OpenSearch 部署与接入文档 + 部署资产，当时定稿**单节点 + Docker（`docker load`）**路线 |
| docs-1.6（修订二） | 2026-09-23 | **部署形态改为官方 `tar.gz` 解包 + systemd（不用 Docker）**（新机器没装 Docker）：删除全部 Docker 路线资产（compose / `.env.example` / `os.sh`），新增 `opensearch.yml.example` / `jvm.options.d/heap.options` / `opensearch.service` / `snapshot-backup.sh`；两处决策级修正：**架构 x86_64**、**按官方文档逐条对齐**（默认 REST 层是 HTTPS ⇒ 必须显式关 TLS；口令复杂度补 zxcvbn 与"不得与 admin 相似"；`DISABLE_INSTALL_DEMO_CONFIG=true` 会让节点起不来 ⇒ 改为让 demo 脚本正常跑；transport 证书不能漏；systemd unit 照官方示例并补 `LimitMEMLOCK`/`MAX_MAP_COUNT`；升级路径改为换程序目录 + 复制 `config/`）。逐条核对表见 **附录 B** |
| docs-1.6（修订三） | 2026-09-23 | **部署根定为 `/home/opensearch/`（D 方案，= 专用账号 `opensearch` 自己的家目录），内部结构规范成"程序/配置/数据/日志/备份/Dashboards"分离；新增 OpenSearch Dashboards（步骤 7）**。① **目录**：`app/`（当前程序）、`app-<旧版本>/`（回滚点）、`config/`（我们维护的 `opensearch.yml` + `jvm.options.d/heap.options` + `snapshot-backup.sh`）、`data/`、`logs/`、`backup/fs-repo/`、`dashboards/app/`。② **建账号方式改掉**：不用官方那种"系统账号 + `--no-create-home`"，改为 `useradd -m -d /home/opensearch -s /bin/bash opensearch` + `passwd -l`（有家目录、锁死登录）。③ **删掉上一版的 `/home/lhim` 穿透权限（`chmod 711`）那一节**（D 方案下不再需要，留着会误导），换成 **步骤 4.1 家目录可用性检查**（`sudo -u opensearch ls/touch` 自证）。④ **新增 步骤 4.2 `/home` 挂载前置检查**（`df -h /home`、`findmnt -no SOURCE,FSTYPE,OPTIONS /home` 确认本地盘、非 NFS/autofs、空间够；给出"只把 `path.data` 挪到 `/data/opensearch`"的既定退路；并说明 **`nosuid`/`nodev` 对本服务无害、不要为此改挂载参数**，真正会挡路的只有 `noexec`）。⑤ **升级流程**按新结构重写（解包到 `app-<版本>/` → 拷 `config/` 与证书/`opensearch-security/` → 停服 → 旧 `app/` 改名保留、新目录改名 `app/` → `chown -R` → 启动 → 验收；回滚即反操作）。⑥ **Dashboards（步骤 7）**：版本必须与 OpenSearch 完全一致（`3.1.0`）、下载 + sha512 校验 + **归档 tar 到 `.111`**、解包到 `dashboards/app/`、`opensearch_dashboards.yml` 关键项逐条给依据（`server.host` 绑内网、`opensearch.hosts` 与 REST TLS 取舍绑定、只读账号优先、`requestHeadersAllowlist` 必须含 `authorization`、多租户与 workspace 互斥）、systemd unit（照官方 OpenSearch 那份示例改 3 处：`Type=simple`/`KillMode=mixed`/`NODE_OPTIONS` 堆上限）、内存预算（Node 约 0.6~1.2GB，标注估算）、5601 只放行运维网段、**冷备结论（不需要单独冷备）**，以及 **⚠️ 快照必须显式包含系统索引**（`snapshot-backup.sh` 默认列表已改为 `pm-ai-chunks,.opensearch_dashboards*,.opendistro_security`）。⑦ 集群名 `pm-os` 与节点名 `os01` **保持不变** |
| docs-1.6（修订四） | 2026-09-23 | **版本升 3.1.0（按 3.x 官方文档重核，含 k-NN 引擎/查询语法变更）+ 安装包目录统一 `/home/opensearch/`**。① **版本**：全文版本号统一到 **`3.1.0`**（原为上一代版本）（OpenSearch 与 Dashboards 同版本），包名/体积按真机实际写（OpenSearch **1,000,330,285 字节**、Dashboards **385,456,866 字节**）；**上一版固化的上一代版本 SHA512 作废**，改为"**以现场 `.sha512` 为准**"（本轮无法联网核实 3.1.0 的校验和）。② **安装包目录**：`scp` 目标从 `/tmp` 统一到 **`/home/opensearch/`**，并把**跨用户/跨家目录拷贝**写成显式前置（新增 第 1 节：为什么 `lhim` 直接 `scp` 会 `Permission denied`；做法 A `usermod -aG opensearch lhim` + `chmod 2775`（setgid 继承组）并写明代价；做法 B `install -o opensearch -g opensearch -m 644`；并提醒**不要** `sudo -u opensearch cp /home/lhim/…`）；**解压后必须 `chown -R opensearch:opensearch`**；新增 **步骤 4 占用与清理**（两包合计 ≈1.32 GiB；验收通过后 `.110` 可删、`.111` 留归档）。③ **3.x 重核**（附录 B 新增第 27~33 条）：**k-NN 引擎**（3.x 不要用 `nmslib`；`engine` 省略时默认 `faiss`；`cosinesimil` 对 lucene 一直支持、对 faiss 是 2.19+；维度上限 16000；`method.parameters` 而非 `params`；`mode` 默认 `in_memory`）、**查询语法**（3.x 是 `"knn": { "<字段名>": {...} }`，并如实登记"仓库适配层按 2.x 固定结构拼查询体、因字段名恰好叫 `vector` 语义仍正确但需实测"）、**快照请求体**（官方 Create Snapshot API **没有 `expand_wildcards`**，已从脚本移除；`indices` 原生支持 `*`）、安全插件键与 tar 安装脚本/口令四条要求**在 3.x 未变**、`jvm.options.d` 机制仍受支持、自带 JDK 由包内提供、systemd 示例 3.x 与 2.x 相同、Dashboards 的 yml 键与 `node.options` 默认 4096 **在 3.x 仍成立** |
| docs-1.6（修订五） | 2026-09-23 | **补"仓库资产落位对照表"（属主/权限/`install` 命令 + 两条省事路径）**：真机上卡在"父目录能写、`config` 不能写、`cp` 进去属主变 `lhim`"——新增落位表（源→目标→属主:组→权限，6 个资产）、三条纪律、核对命令、两条省事路径（不动权限的"以服务账号干活" / 权限共享），并写明权限共享的代价；权限统一到 **配置 `640` / 脚本 `750` / unit `root:root 644`** |
| docs-1.6（修订五·补） | 2026-09-23 | 补齐 7 处"文件落位"缺口：**Dashboards 配置节的仓库相对路径错误修正**、`dashboards/app/` 属主、`backup/fs-repo` 进表、`snapshot-backup.env`（`600`）、`tools/*.sh` 的 `x` 位、**升级段补证书/keystore 的属主与权限**、`/etc` 两处进表；并加"目录 `w` vs 文件 `w`"的最小权限解释 |
| docs-1.6（修订五·收口） | 2026-09-23 | 收口 4 条：**cron 落位与口令自洽**（新增 cron 子节：三者必须对齐，否则备份每天静默失败）、`opensearch.keystore`（`600`）与 `opensearch-security/` 目录（`750`）进表、**日志轮转（logrotate）**建议与"磁盘满"排障交叉引用、`backup/dist/` 一行 |
| docs-1.6（修订六） | 2026-09-23 | **主线去分支，按真机现状写成一条路**（本次）。① **删除"校验"整块**：`sha512sum -c`、`.sha512`、Windows `Get-FileHash` 那套全部移出主线（官方 URL 口径只在附录 C 留档，且不指向主线步骤）。② **删除"过程性/搬运"叙述**：浏览器下载 → `/home/lhim/software` → 复制到 `/home/opensearch` 的链路、`scp`、跨用户拷贝、`/tmp` 中转、`install -D`/`chown` 的"怎么搬进去"全部删除；**主线直接从现状开始**（"两个安装包已在 `/home/opensearch/`，`app/` 已解压完成"）。③ **每步状态标注**：//。④ **权限改为写法**：本机采用**权限共享**（`find -type d 2775` / `-type f 664` / `*.pem 640` / `umask 002` / 断连重连），删掉"路线一/二"与"data/ 建议收紧"等并列说法（收紧移到附录 B）。⑤ **安全模式唯一**：HTTP + basic auth（自签 TLS 移到附录 B）。⑥ **Dashboards 账号用 `admin`**（只读账号移到附录 B）。⑦ **备份 cron 口径**：`root` 的 crontab + `/etc/opensearch-backup.pass`（`root:root 600`）。⑧ **logrotate 本机自建**（平台方统一采集移到附录 B）。⑨ **防火墙以 firewalld 为主线**，iptables 仅作为同一代码块内的注释。⑩ **章节重排**：现状 → 权限 → 宿主准备 → 落位 → 安装 → systemd 验收 → 索引与检索 → Dashboards → 运维（含快照/cron/防火墙）→ AI 接入 → 未确认项；**附录 A（3 节点）/ 附录 B（变更项，原"将来加固"内容）/ 附录 C（官方逐条核对 35 条）**。全文 2261 → 约 1720 行 |
| docs-1.6（修订七） | 2026-09-23 | **按"先收暴露面、再起服务"调顺序 + 去强调词**：① **防火墙从运维章末提前到宿主准备章**（现为 步骤 8），步骤 1.6 跨机可达与 步骤 1 自检都引用它，并加"顺序原则"说明；② 步骤 12 标题去掉"唯一"字样，正文里"唯一写法/唯一口径/唯一路径"等**强调词全部去除**（靠内容表达）；③ 步骤 4 的衔接说明写明确：**要覆盖已存在的配置文件时，在终端工具里先删除再上传**（目录组可写 ⇒ 能删），然后重跑 步骤 10 收回 `640`，并指路 **附录 B.2「以服务账号干活」**；④ **附录 B 新增 B.2「以服务账号干活」**（原 B.2~B.6 顺延为 B.3~B.7）；⑤ 步骤 15 单节点故障演练**保留在主线、按必做**（新装无数据，停机成本为零，是验证"重启能自愈"的唯一手段）；⑥ `snapshot-backup.env` 保留（"用不到就别建"是事实陈述） |
| docs-1.6（修订八） | 2026-09-23 | **修一条范围写错的命令（真故障）**：上机时 `systemctl start opensearch` 连续失败，`journalctl` 报 `Control process exited, code=exited, **status=203/EXEC**`。**根因是文档的错**：步骤 4 原文 `sudo find /home/opensearch -type f -exec chmod 664 {} +` **递归抹掉了程序目录的执行位**（`app/bin/opensearch`、`app/jdk/bin/*`、`app/jdk/lib/jspawnhelper`、`app/plugins/**/tools/*.sh`），JVM 与启动脚本无法执行。① **步骤 4 改为"只作用于运行期目录"**（`config`/`data`/`logs`/`backup`），并**明确禁止**对 `app/` 或 `dashboards/app/` 套用 `chmod 664`；② **新增 §4.5「程序目录可执行位恢复」**（三条 `chmod 750` + `daemon-reload` + `restart` + 四条复核，含"从 tar 包重新解压程序目录"的更稳替代）；③ **步骤 19 新增 `status=203/EXEC` 排障条目**（含义：systemd 在 exec 阶段就失败 ⇒ 应用日志为空，别在 `logs/` 里找；四处日志 + 四类原因与判据）；④ **步骤 10 ⑤ 核对新增"`app/bin/opensearch` 与 `jdk/bin/java` 必须带 `x`"**；⑤ **参考 A · 容量与分片 加同款红线**（不要对 `dashboards/app/` 套 `chmod 664`）；⑥ **附录 B.1 的递归 `find` 限定到运行期目录**；⑦ 资产 `opensearch.yml.example` 顶部加"权限红线"注释、`opensearch.service` 去掉已废弃的 `chmod 711 /home/lhim` 历史注释、`opensearch_dashboards.yml.example` 的示例权限由 `644` 改 `640` |
| docs-1.6（修订九） | 2026-09-23 | **由"部署记录"改写为傻瓜式部署手册**（用户意见：这是部署文档，不是部署记录，要能照着从零部署成功）。① **删除全部状态标注与既成事实**（`（已完成）`/`（待执行）`/`（当前状态）`/"已解压完成"等）；② **新增 §1 前置条件（开始前必须具备）**：机器/架构/内存/磁盘/Docker 检查、**专用账号 `opensearch` 的从零建立命令**（`useradd -m -d /home/opensearch -s /bin/bash` + `passwd -l` + `chown` + `usermod -aG`）、目录结构建立、两个安装包位置、资产位置、端口与网络前置，**每条都有"怎么达成"的命令**；③ **"解压 tarball 到 `app/`"恢复为正式步骤**（步骤 3：`sudo -u opensearch tar -zxf … ` + `mv … app` + `chown -R` + 期望输出）；④ **主线 20 步全部改为四件套**：① 执行（可直接复制的命令块）② 期望输出（逐字给出 `"status":"green"`、`-rwxr-x---` 等）③ ✅ 完成判据 ④ ✗ 失败了怎么办（指向 §4.5 或参考 B · 常见故障的具体条目），并给出**耗时估计**；⑤ 顶部新增 **"怎么用这份文档"（3 条）+ 总步骤清单（可勾选，20 步 + 耗时）**；⑥ 危险动作（递归 `chmod`、防火墙、停机演练）在命令块紧邻处加 🔴 警告；⑦ 删除主线回溯性文字（"本项目已实际踩过这个坑"、"用户真机现状"、"本次部署不做校验"等），仅保留在附录/变更记录里；⑧ 参考章节去编号改为 **参考 B · 运维与排障 / 参考 C · 未确认项 / 附录 D · 结论与前提 / 附录 E · 索引与检索设计**，`附录 A`（3 节点）`附录 B`（变更项）`附录 C`（官方 35 条）与**变更记录**保留不动。**每步只依赖它前面的步骤**，新机器可从零照做 |
| docs-1.6（修订十） | 2026-09-23 | **补 Dashboards 侧的同源漏洞**：上机时 `opensearch-dashboards.service` 启动失败，journald 报 `bin/use_node: cannot execute: Permission denied` → `Main process exited, code=exited, status=126/n/a`（重启计数 22）。根因与修订八同源：**之前那次全量 `chmod 664` 也波及了 `dashboards/` 树**，而手册只给了 OpenSearch `app/` 的执行位恢复。① **步骤 17 新增「恢复块 A · Dashboards 程序树执行位恢复」**（`dashboards/app/bin` + `node/bin` 与 `*.sh` 三条 `chmod 750` → 核对 `use_node`/`node` 为 `-rwxr-x---` → **`systemctl reset-failed` + `restart`**），并在失败指引里加 `126`；② **排障表新增 `status=126/n/a` 与 `status=203/EXEC` 两行**，写明 **exec 阶段失败 ⇒ 应用日志为空**、四处取证与**四类原因**，并注明"**`126` 与 `203` 都是权限位问题、修法一致**"；③ **步骤 4 权限红线扩写**：`chmod 664` 只作用于 `config`/`data`/`logs`/`backup`；**`app/` 与 `dashboards/app/` 两棵程序树都不得递归**，并给出**一次核对两棵树的四文件判据**（`app/bin/opensearch`、`app/jdk/bin/java`、`dashboards/app/bin/use_node`、`dashboards/app/node/bin/node` 必须都带 `x`） |
| docs-1.7 | 2026-09-23 | **把 步骤 19 从占位句写成可复制的真实步骤（本轮唯一的实质内容改动）**，因为 AI 侧接入代码已随 `ai-1.2.0` 落地（`ai-backend/**`）。① **步骤 19 重写为 5 步固定顺序**：①-1 建索引 + 验连通（`_mapping`/`_cat/indices`/`_cluster/health`）→ ①-2 改 `.env`（`VEC_BACKEND`/`OPENSEARCH_URL`/`OPENSEARCH_INDEX`/`OPENSEARCH_USER`/`OPENSEARCH_PASSWORD`，口令只放 `.env`）+ `bash pm.sh restart ai` → **①-3 先做 top-k 一致性验证再做全量切换**（`TopKCompareTool` 的完整可复制命令 + 判据：**相似度数值不必逐位相等，要求一致的是排序/集合**；重合率偏低先查 `space_type` → 字段名 → 是否漏灌）→ ①-4 全量重建（真实端点 `/admin/reindex` 全量/局部/强制/进度/取消 5 条 `curl`）→ ①-5 回滚 `local`；② **写明不静默降级**：集群不可达时检索与重建**立刻 503 + "向量后端不可用"**，`/health?with_vec=true` 的 `vec.ok` 取**集群真实状态**，**不会**悄悄退回 `local`；③ **写明建索引两种方式的取舍**：手工 `步骤 16` 与代码自建只能选一种，`ef_construction` **100（代码）vs 128（步骤 16 示例）**、`refresh_interval` **1s（代码）vs -1（步骤 16 灌库示例）**，**索引已存在不会被改写**；④ **写明只接一台**：`.110` 与 `.111` 是彼此独立的单节点集群，生产 = `.110`；⑤ **参考 C 同步落定**：向量后端选型（生产 `opensearch`、`local` 仅回滚）与两台机器角色（`.110` 生产 / `.111` 冷备演练）移入"已定死"清单；第 3/4/6/7/10 条按 `ai-1.2.0` 的实际状态改写（元数据接口与硬作用域已就绪、待定的是"谁在什么时候填"；top-k 真机验证**仍未做**）；⑥ **顺带修掉 8 处失效的步骤引用**（`步骤 17`/`步骤 19`/`步骤 20` 误指：kNN 语法与 mapping → 步骤 16 与 附录 E、升级 → 参考 B · 升级路径、logrotate → 步骤 18、磁盘水位与 yellow/red → 参考 B · 常见故障）与 2 处已过期的表述（**向量化已前移**，不再是"惰性在问答路径上"；**元数据字段代码已写入**，不再是"不是代码自动写入的"）；⑦ 文档头部原"`ai-backend/**` 应用代码一行未改"改为如实说明"手册改的是 步骤 19，AI 侧代码随 `ai-1.2.0` 落地" |



