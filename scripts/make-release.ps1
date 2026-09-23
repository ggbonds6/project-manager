# ============================================================
# PM 发布打包脚本（在开发机执行，产出可直接上传服务器的发布目录）
#
# 用法（在仓库根或任意位置执行均可）：
#   .\scripts\make-release.ps1 <版本> [平台] [-Project main|ai] [-Only backend|frontend|both] [-ReuseTag <旧版本>]
#
#   ① 主系统（默认 -Project main；产物 dist/pm-release-<版本>/）：
#      .\scripts\make-release.ps1 v3.6.2                            # 两个镜像都重建（与历史默认行为一致）
#      .\scripts\make-release.ps1 v3.6.2 linux/amd64                # 如需 x86 产物
#      .\scripts\make-release.ps1 v3.6.2 -Only backend              # 只重建后端；前端复用本机最新 pm-frontend 镜像
#      .\scripts\make-release.ps1 v3.6.2 -Only frontend -ReuseTag v3.6.1   # 只重建前端；后端显式复用 v3.6.1
#      $env:SKIP_BUILD='1'; .\scripts\make-release.ps1 v3.6.2       # 跳过构建（两个镜像都已存在于本机）
#      ★-Only 只省掉"其中一端的构建时间"：发布包内容与默认行为**完全一致**（两个镜像都在包里）。
#      ★-Only 与 SKIP_BUILD=1 语义冲突，不允许同时给（exit 2）。
#
#   ② AI 能力服务（-Project ai；产物 dist/pm-ai-release-<版本>/；独立发版，见部署手册 §8）：
#      .\scripts\make-release.ps1 v1.0.0 -Project ai                # 构建 pm-ai-backend:v1.0.0 并出发布包
#      .\scripts\make-release.ps1 v1.0.0 -Project ai linux/amd64    # 平台参数与主系统同款（第二位置参数）
#      ★-Project ai 不认 -Only / -ReuseTag（那是主系统语义）→ 明确报错 exit 2，不会静默忽略。
#
# 环境变量：
#   SKIP_BUILD=1   跳过构建，直接打包本机已存在的同版本镜像（快速重打包）
#   DOCKER=...     指定 docker 可执行文件（默认先查 PATH，再查 Docker Desktop 安装目录）
#
# 产出（主系统）：dist/pm-release-<版本>/
#   ★按服务器布局产出「一个部署根 + 两个隔离项目」，上传后落地即符合布局（服务器上不用再 mkdir/cp/mv）：
#   ├─ pm.sh                              ← 复制 scripts/pm.sh：服务器唯一入口
#   │                                       （start/stop/restart/status/logs/upgrade；取代了旧的 pm-upgrade.sh）
#   └─ main/                              ← 主系统运行目录（= 服务器 <部署根>/main）
#       ├─ docker-compose.yml             服务器专用编排（无 build 段）
#       ├─ .env.example                   .env 模板（IMAGE_TAG 已预填本次版本）
#       ├─ 服务器部署步骤.txt              照做即可
#       └─ releases/pm-images-<arch>-<版本>.tar.gz     镜像包（含 pm-backend + pm-frontend）
#
# 产出（AI 能力服务）：dist/pm-ai-release-<版本>/
#   ├─ pm.sh                              ← 同一份 scripts/pm.sh（两套包都带，重复上传无害）
#   └─ ai/                                ← AI 运行目录（= 服务器 <部署根>/ai）
#       ├─ docker-compose.yml             ← 复制 ai-backend/docker-compose.deploy.yml（包内已改名，服务器不用再 mv）
#       ├─ .env.example                   ← 复制 ai-backend/.env.example（含 AI_IMAGE_TAG 预填）
#       ├─ 服务器部署步骤-ai.txt           照做即可
#       └─ releases/pm-ai-images-<arch>-<版本>.tar.gz  镜像包（只含 pm-ai-backend）
#   ★两套包各自只含自己那一半（主系统包不含 ai/，AI 包不含 main/），但都带 pm.sh 且都落在部署根下，
#     所以两套包可以先后上传到同一台服务器的同一个部署根，互不覆盖（见部署手册 §8）。
#
# 前置条件：Docker Desktop 已启动；构建 arm64 需先装 QEMU 模拟（只需一次）：
#   docker run --privileged --rm tonistiigi/binfmt --install arm64
#
# 踩过的坑：
#   - 本机没有 gzip.exe（也没有 Git Bash）：原 .sh 的 `docker save ... | gzip > x.tar.gz` 不能照抄——
#     bash 管道传的是字节流，而 PowerShell 管道会把二进制当文本解码，镜像包必然损坏。
#     这里改成 `docker save -o 临时 tar` + .NET GZipStream 压缩（等价于 gzip；只是 gzip 头里不写
#     原始文件名与 mtime，docker load 不看这两项），压缩完删掉临时文件。主系统与 AI 两个分支共用这套流程。
#   - 为什么从 .sh 改为 .ps1：本机没有 Git Bash（PATH 上的 bash 只是 WSL 桩），.sh 跑不了；
#     开发机脚本统一用 Windows 原生 PowerShell，避免 .sh/.ps1 两套并存产生漂移。
#     （`pm.sh` 随发布包下发、只在 Linux 服务器上运行，保持 .sh 不动；它取代了旧的 `pm-upgrade.sh`，
#      后者已从仓库删除，发布包里也不再复制它。）
#   - 本脚本文件是 UTF-8 **带 BOM**：Windows PowerShell 5.1 对无 BOM 的 UTF-8 脚本按
#     ANSI(GBK) 解析，中文字面量会直接变乱码；带 BOM 才能 5.1 与 7 都正常显示中文。
#   - 在 5.1 下若执行策略为 Restricted，用 `powershell -ExecutionPolicy Bypass -File ...` 调用。
#   - -Only 复用镜像时**不解析时间**判新旧：docker 的 .Created 是 RFC3339Nano，日期时间部分定宽零填充，
#     归一化成"只留数字"的字符串后字典序即时间先后（小数秒只在前面完全相同才参与比较）；
#     这样避开 5.1 下 DateTime 解析 9 位小数秒的兼容问题。详见 Resolve-ReuseSourceTag 上方注释。
# ============================================================
param(
    [Parameter(Position = 0)][string]$Version,
    [Parameter(Position = 1)][string]$Platform = 'linux/arm64',
    [string]$Project = 'main',
    [string]$Only = 'both',
    [string]$ReuseTag
)

$ErrorActionPreference = 'Stop'
# 本机控制台默认 936(GBK)：按 UTF-8 输出，避免中文乱码
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

# ── 锚定到仓库根（脚本位于 scripts/ 下）─────────────────────────────
# ★ 必须同时改"两套当前目录"，否则第 3 步会报
#   「使用"1"个参数调用"Create"时发生异常:未能找到路径 …\dist\… 的一部分」：
#     · PowerShell 的 $PWD（Set-Location 改的是它）—— cmdlet 与外部程序按它解析相对路径；
#     · .NET 的进程当前目录 [Environment]::CurrentDirectory —— [System.IO.File]::Create /
#       ReadAllText 这类 **.NET API 按它解析相对路径**，且 Set-Location 不会同步它。
#   本机实测复现路径：PowerShell 在 C:\Users\<你> 启动 → cd 进仓库 → $PWD 是仓库，
#   但 .NET 当前目录仍是主目录 → 于是 New-Item 建了仓库下的 dist（cmdlet 按 $PWD），
#   而在写 tar.gz 时 .NET 去 C:\Users\<你>\dist 找 → 目录不存在。同步后从任何目录调用都对。
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
[System.IO.Directory]::SetCurrentDirectory($repoRoot)

# 跑原生命令并静默它的 stderr，返回 @{ Output = <stdout 行>; ExitCode = <退出码> } 形式对象
# 等价于 bash 的 `cmd 2>/dev/null`。必须包一层函数：PS 5.1 在 $ErrorActionPreference='Stop' 下
# 对原生命令做 stderr 重定向会被当成**终止性错误**（5.1 独有行为），函数内临时降为 Continue 规避。
function Invoke-NativeQuiet([string]$Exe, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = @(& $Exe @Arguments 2>$null)
    return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
}

# 本机是否已有该镜像（不存在时 docker image inspect 返回非零；stderr 已被上面函数静默）
function Test-DockerImage([string]$DockerBin, [string]$ImageRef) {
    return ((Invoke-NativeQuiet $DockerBin @('image', 'inspect', $ImageRef)).ExitCode -eq 0)
}

# 列出本机某仓库名（如 pm-frontend）下的全部 tag（排除 <none> 悬空镜像）
function Get-LocalRepoTags([string]$DockerBin, [string]$Repository) {
    $tags = @()
    $ls = Invoke-NativeQuiet $DockerBin @('image', 'ls', '--format', '{{.Repository}}|{{.Tag}}')
    if ($ls.ExitCode -ne 0) { return $tags }
    foreach ($line in $ls.Output) {
        if ([string]::IsNullOrEmpty($line)) { continue }
        $parts = $line -split '\|'
        if ($parts.Count -ne 2) { continue }
        if ($parts[0] -cne $Repository) { continue }
        if ([string]::IsNullOrEmpty($parts[1]) -or $parts[1] -ceq '<none>') { continue }
        $tags += $parts[1]
    }
    return $tags
}

# 取镜像创建时间并归一化成"只留数字"的排序键（见文件头"踩过的坑"）：
#   2025-01-02T03:04:05.123456789Z → 20250102030405123456789
# 读不到时间戳（inspect 失败 / 输出为空）时返回空串，由调用方决定怎么排。
function Get-ImageCreatedOrdinalKey([string]$DockerBin, [string]$ImageRef) {
    $res = Invoke-NativeQuiet $DockerBin @('image', 'inspect', '--format', '{{.Created}}', $ImageRef)
    if ($res.ExitCode -ne 0) { return '' }
    $raw = ''
    if ($res.Output.Count -gt 0) { $raw = [string]$res.Output[0] }
    if ([string]::IsNullOrEmpty($raw)) { return '' }
    return ($raw -replace '[^0-9]', '')
}

# -Only backend|frontend：解析出"没改动的那一端"要复用的来源 tag
# ------------------------------------------------------------------
# 判"最新"的可靠办法（未给 -ReuseTag 时）：
#   ① 取 docker image ls 里仓库名等于目标仓库（pm-frontend / pm-backend）的全部 tag，
#      排除本次目标版本本身与 <none>；
#   ② 对每个候选取 docker image inspect --format '{{.Created}}'，归一化成"只留数字"的键后**按序数比较**取最大
#      （日期时间部分定宽零填充 → 字典序即时间先后；小数秒只在前面完全相同时才参与比较）——
#      这样完全不解析时间，绕开 5.1 下 9 位小数秒的 DateTime 兼容坑；
#   ③ 时间戳读不到的候选排在有时间戳的之后；若全部读不到，则按 docker image ls 的返回顺序取第一个
#      （该命令默认按创建时间倒序），并且"相等时保留先遇到的"，因此结果稳定可预期；
#   ④ 选出来后**打印实际复用的来源 tag**（可追溯，别让人猜）。
# 一个候选都没有 → 报错退出 1，并给出建议（先完整构建一次，或用 -ReuseTag 指定）。
function Resolve-ReuseSourceTag([string]$DockerBin, [string]$Repository, [string]$TargetVersion, [string]$ReuseVersion) {
    if (-not [string]::IsNullOrEmpty($ReuseVersion)) {
        $ref = "${Repository}:${ReuseVersion}"
        if (Test-DockerImage $DockerBin $ref) { return $ReuseVersion }
        Write-Host "[FAIL] -ReuseTag $ReuseVersion 指定的镜像 $ref 在本机不存在"
        Write-Host "       先看一眼本机有哪些：$DockerBin image ls $Repository"
        Write-Host "       解决建议：先完整构建一次（不加 -Only），或换一个本机确实存在的版本号。"
        exit 1
    }

    $candidates = @()
    foreach ($tag in (Get-LocalRepoTags $DockerBin $Repository)) {
        # 排除本次要产出的目标版本：它要么刚构建出来、要么是上一次跑残留的，都不是"上一版"
        if ($tag -ceq $TargetVersion) { continue }
        $key = Get-ImageCreatedOrdinalKey $DockerBin "${Repository}:${tag}"
        $candidates += [pscustomobject]@{ Tag = $tag; Key = $key }
    }

    if ($candidates.Count -eq 0) {
        Write-Host "[FAIL] 本机找不到可复用的 ${Repository} 镜像（只找到本次目标版本 ${Repository}:${TargetVersion} 或一个都没有）"
        Write-Host "       解决建议：① 先不带 -Only 完整构建一次（例如 .\scripts\make-release.ps1 $Version）；"
        Write-Host "                 ② 或显式指定来源：-Only <另一端> -ReuseTag <本机已有的版本号>"
        Write-Host "       当前本机镜像：$DockerBin image ls $Repository"
        exit 1
    }

    Write-Host "      · 候选来源（按创建时间新→旧）："
    $bestTag = ''
    $bestKey = ''
    foreach ($c in $candidates) {
        $shown = $c.Key
        if ([string]::IsNullOrEmpty($shown)) { $shown = '<读不到 Created>' }
        Write-Host ("        {0,-28} {1}" -f "${Repository}:$($c.Tag)", $shown)
        if ($bestTag -ceq '' -or [string]::CompareOrdinal($c.Key, $bestKey) -gt 0) {
            $bestTag = $c.Tag
            $bestKey = $c.Key
        }
    }
    return $bestTag
}

# 复用来的镜像必须与本次打包平台同架构：否则服务器 docker load 成功、docker compose up 会 exec format error
function Assert-ImageArchMatch([string]$DockerBin, [string]$ImageRef, [string]$ExpectedArch, [string]$PlatformName) {
    $res = Invoke-NativeQuiet $DockerBin @('image', 'inspect', '--format', '{{.Architecture}}', $ImageRef)
    if ($res.ExitCode -ne 0) { return }   # 探不动就不拦（存在性已在别处校验）
    $actual = ''
    if ($res.Output.Count -gt 0) { $actual = ([string]$res.Output[0]).Trim() }
    if ($actual -cne $ExpectedArch) {
        Write-Host "[FAIL] 复用的镜像架构不匹配：$ImageRef 实际是 $actual，而本次打包平台是 $PlatformName（期望 $ExpectedArch）"
        Write-Host "       混架构的发布包传到服务器上 docker load 会成功、但 docker compose up 报 exec format error。"
        Write-Host "       解决建议：用 -ReuseTag 指定同架构的 tag，或这次不加 -Only 完整构建。"
        exit 1
    }
    Write-Host "      · 架构校验通过：$ImageRef = $actual"
}

# 打印发布包里的文件树（相对包根）。
# 为什么不再用 Get-ChildItem 顶层列表：新布局是「包根 pm.sh + 项目子目录 + releases/」，
# 只列顶层会看不到镜像包与步骤文件到底落在哪，收尾时容易误判"包是空的"。
# Sort-Object 只为让输出顺序稳定（目录枚举顺序本身不保证，两次打包的打印顺序可能不同，对比时容易看花眼）。
function Show-ReleaseTree([string]$Root) {
    $rootFull = (Get-Item -LiteralPath $Root).FullName
    Get-ChildItem -Force -Recurse -LiteralPath $Root | Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($rootFull.Length).TrimStart([char]'\')
        if ($_.PSIsContainer) {
            Write-Host ("  <DIR>       {0}" -f $rel)
        } else {
            Write-Host ("  {0,10:N0}  {1}" -f $_.Length, $rel)
        }
    }
}

# ── 参数校验（一律在"动 docker"之前完成；退出码：参数非法 = 2，缺文件/缺镜像 = 1）──
if ([string]::IsNullOrEmpty($Version)) {
    [Console]::Error.WriteLine('用法: scripts/make-release.ps1 <版本> [平台] [-Project main|ai] [-Only backend|frontend|both] [-ReuseTag <旧版本>]')
    [Console]::Error.WriteLine('  主系统（默认）：scripts/make-release.ps1 v3.6.2 [linux/arm64|linux/amd64] [-Only backend|frontend] [-ReuseTag v3.6.1]')
    [Console]::Error.WriteLine('  AI 能力服务：  scripts/make-release.ps1 v1.0.0 -Project ai [linux/arm64|linux/amd64]')
    [Console]::Error.WriteLine('  跳过构建：     $env:SKIP_BUILD=''1''; scripts/make-release.ps1 v3.6.2')
    exit 1
}

if ($Project -cne 'main' -and $Project -cne 'ai') {
    Write-Host "[FAIL] -Project 只能是 main | ai（当前：$Project）"
    exit 2
}
if ($Only -cne 'both' -and $Only -cne 'backend' -and $Only -cne 'frontend') {
    Write-Host "[FAIL] -Only 只能是 backend | frontend | both（当前：$Only）"
    exit 2
}

# 是否"显式"给了 -Only（-Only both 等于默认行为，不算单边复用）
$onlyGiven = $PSBoundParameters.ContainsKey('Only') -and ($Only -cne 'both')
$onlySpecified = $PSBoundParameters.ContainsKey('Only')
$reuseGiven = -not [string]::IsNullOrEmpty($ReuseTag)

if ($Project -ceq 'ai') {
    # -Only / -ReuseTag 是主系统"两个镜像里只重建一端"的语义；AI 服务只有一个镜像，这两项无意义
    if ($onlySpecified) {
        Write-Host "[FAIL] -Only 对 -Project ai 无意义：AI 能力服务只有一个镜像（pm-ai-backend），没有「只重建一端」的说法"
        Write-Host "       请去掉 -Only；若要跳过构建请用环境变量 SKIP_BUILD=1。"
        exit 2
    }
    if ($reuseGiven) {
        Write-Host "[FAIL] -ReuseTag 对 -Project ai 无意义（它只配合主系统的 -Only backend|frontend 使用）"
        exit 2
    }
} else {
    # ★只要**显式**给了 -Only（即便写成 -Only both），就不能再叠 SKIP_BUILD=1：
    #   前者要求"构建没改动的那一端"，后者要求"完全跳过构建"，语义互斥；-Only both 本就等于默认行为，
    #   去掉它即可，不必为这种写法留歧义。
    if ($onlySpecified -and $env:SKIP_BUILD -eq '1') {
        Write-Host "[FAIL] -Only 与 SKIP_BUILD=1 语义冲突：前者要「构建另一端」，后者要「完全跳过构建」，不能同时给"
        Write-Host "       二者选一：要么 SKIP_BUILD=1（不加 -Only，两个镜像都必须已存在），要么 -Only backend|frontend（另一端复用本机镜像）。"
        exit 2
    }
    if ($reuseGiven -and -not $onlyGiven) {
        Write-Host "[FAIL] -ReuseTag 只在 -Only backend|frontend 时有意义（当前 -Only $Only）"
        Write-Host "       例如：-Only backend -ReuseTag v3.6.1 表示「只构建后端、前端复用 v3.6.1」。"
        exit 2
    }
}

# 平台 → 产物架构名（决定镜像包文件名里的 aarch64 / x86_64）
$arch = ''
switch -CaseSensitive -Wildcard ($Platform) {
    '*arm64*'   { $arch = 'aarch64' }
    '*aarch64*' { $arch = 'aarch64' }
    '*amd64*'   { $arch = 'x86_64' }
    '*x86_64*'  { $arch = 'x86_64' }
}
if ($arch -eq '') {
    Write-Host "[FAIL] 不支持的平台: $Platform（用 linux/arm64 或 linux/amd64）"
    exit 2
}

# 产物架构名 → docker 的 .Architecture 取值（用于复用镜像时的架构校验）
$expectDockerArch = 'arm64'
if ($arch -eq 'x86_64') { $expectDockerArch = 'amd64' }

# 定位 docker
$dockerBin = $env:DOCKER
if ([string]::IsNullOrEmpty($dockerBin)) {
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        $dockerBin = 'docker'
    } else {
        $fallback = $null
        if ($env:LOCALAPPDATA) {
            $fallback = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
        }
        if ($fallback -and (Test-Path -LiteralPath $fallback)) {
            $dockerBin = $fallback
        } else {
            Write-Host '[FAIL] 未找到 docker 可执行文件，请安装 Docker Desktop 或设置 DOCKER 环境变量'
            exit 2
        }
    }
}

# ============================================================
# 分支 A：AI 能力服务（-Project ai）
#   · 只构建/导出 pm-ai-backend:<版本>；构建上下文仍是【仓库根】（Dockerfile 里是 COPY ai-backend/...）
#   · 产出 dist/pm-ai-release-<版本>/：包根放共用的 pm.sh，AI 项目放 ai/（含自己的 compose/.env.example/
#     步骤/releases 镜像包）；不含主系统的 main/、也不含主系统 .env.example（两套发布物互不混装）
# ============================================================
if ($Project -ceq 'ai') {
    $aiDockerfile = Join-Path $repoRoot 'ai-backend\Dockerfile'
    $aiComposeDeploy = Join-Path $repoRoot 'ai-backend\docker-compose.deploy.yml'
    $aiEnvExample = Join-Path $repoRoot 'ai-backend\.env.example'
    foreach ($need in @($aiDockerfile, $aiComposeDeploy, $aiEnvExample)) {
        if (-not (Test-Path -LiteralPath $need -PathType Leaf)) {
            Write-Host "[FAIL] 缺少 AI 服务发布所需文件：$need"
            exit 1
        }
    }

    $out = "dist/pm-ai-release-$Version"
    # 清空旧产出，避免上一版遗留文件混进发布包
    if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    # 新布局：包根放 pm.sh（部署根下的唯一入口），AI 项目放 ai/，镜像包放 ai/releases/
    # ★releases/ 必须先建出来，否则写 tar.gz 时会报「未能找到路径 … 的一部分」
    $outAi = Join-Path $out 'ai'
    $outAiReleases = Join-Path $outAi 'releases'
    New-Item -ItemType Directory -Path $outAiReleases -Force | Out-Null

    $aiImage = "pm-ai-backend:${Version}"
    if ($env:SKIP_BUILD -eq '1') {
        Write-Host "[1/5] 跳过构建（SKIP_BUILD=1），使用本机已有镜像 $aiImage"
        if (-not (Test-DockerImage $dockerBin $aiImage)) {
            Write-Host "[FAIL] 本机不存在 $aiImage"
            exit 1
        }
    } else {
        Write-Host "[1/5] 构建 AI 镜像（$aiImage，平台 $Platform，构建上下文=仓库根，QEMU 模拟构建较慢，请耐心）"
        & $dockerBin buildx build --platform $Platform --load -f 'ai-backend/Dockerfile' -t $aiImage .
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host '[2/5] 校验架构'
    & $dockerBin image inspect $aiImage --format '  ai-backend={{.Architecture}}/{{.Os}}'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host '[3/5] 导出镜像包'
    $tarGz = Join-Path $outAiReleases "pm-ai-images-${arch}-${Version}.tar.gz"
    $tmpTar = Join-Path ([System.IO.Path]::GetTempPath()) "pm-ai-images-${arch}-${Version}.tar"
    & $dockerBin save -o $tmpTar $aiImage
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    # 等价于 `| gzip`（见文件头"踩过的坑"）：不能走 PowerShell 管道，二进制会被当文本解码
    try {
        $inStream = [System.IO.File]::OpenRead($tmpTar)
        try {
            $outStream = [System.IO.File]::Create($tarGz)
            try {
                $gzip = [System.IO.Compression.GZipStream]::new($outStream, [System.IO.Compression.CompressionMode]::Compress)
                try { $inStream.CopyTo($gzip) } finally { $gzip.Dispose() }
            } finally { $outStream.Dispose() }
        } finally { $inStream.Dispose() }
    } finally {
        if (Test-Path -LiteralPath $tmpTar) { Remove-Item -LiteralPath $tmpTar -Force }
    }
    Get-Item -LiteralPath $tarGz | ForEach-Object { Write-Host ("{0,8:N1} MB  {1}" -f ($_.Length / 1MB), $_.FullName) }

    Write-Host '[4/5] 复制部署文件'
    # 服务器唯一入口脚本（部署根下执行：bash pm.sh start|stop|status|logs|upgrade）：
    # 两套发布包都带同一份 pm.sh，先传主系统包再传 AI 包时会被同名覆盖，内容相同、无害。
    # ★守卫保留（文件不在时不中断打包），但会告警——缺了它服务器上就没有统一入口。
    if (Test-Path -LiteralPath 'scripts/pm.sh') {
        Copy-Item -LiteralPath 'scripts/pm.sh' -Destination $out -Force
    } else {
        Write-Host '[WARN] 未找到 scripts/pm.sh：发布包会缺少服务器唯一入口脚本（请确认仓库完整）'
    }
    # 服务器专用编排（无 build 段、pull_policy: never）：服务器运行目录里不需要任何源码
    # ★包内直接改名为 docker-compose.yml → 服务器上不需要再 mv docker-compose.deploy.yml docker-compose.yml
    Copy-Item -LiteralPath $aiComposeDeploy -Destination (Join-Path $outAi 'docker-compose.yml') -Force
    Copy-Item -LiteralPath $aiEnvExample -Destination $outAi -Force
    # .env.example 里 AI_IMAGE_TAG 预填为本次版本（与主系统预填 IMAGE_TAG 同一做法），避免服务器漏配 → No such image
    # ★该模板当前是【UTF-8 无 BOM + LF】：读写都用无 BOM 的 UTF-8；替换只动匹配行的内容，
    #   用 (?=\r?\n|\z) 前瞻把行尾留在原地，因此不会改变模板原有的行尾风格（CRLF 模板也不会被改成 LF）。
    $envExample = Join-Path $outAi '.env.example'
    $exampleText = [System.IO.File]::ReadAllText($envExample, [System.Text.UTF8Encoding]::new($false))
    if ($exampleText -match '(?m)^AI_IMAGE_TAG=') {
        $exampleText = [regex]::Replace($exampleText, '(?m)^AI_IMAGE_TAG=[^\r\n]*(?=\r?\n|\z)', "AI_IMAGE_TAG=$Version")
    } else {
        # 模板里没有这一行 → 追加到末尾（先补一个与文件一致的换行，保证它是独立一行）
        $eol = "`n"
        if ($exampleText -match "`r`n") { $eol = "`r`n" }
        if (-not $exampleText.EndsWith($eol)) { $exampleText += $eol }
        $exampleText += "AI_IMAGE_TAG=$Version" + $eol
    }
    [System.IO.File]::WriteAllText($envExample, $exampleText, [System.Text.UTF8Encoding]::new($false))

    Write-Host '[5/5] 生成服务器部署步骤说明'
    $steps = @"
AI 能力服务 $Version 服务器部署步骤（$arch，离线：只 load 不 build）
=====================================================
【本包已按「一个部署根 + 两个隔离项目」的布局产好：上传后落地即符合布局，服务器上不需要再 mkdir/cp/mv】

    /home/lhim/pm/                       ← 部署根 = pm.sh 所在目录（pm.sh 按自身位置推导，不硬编码 /home/lhim）
     ├─ pm.sh                            ← 唯一入口：start / stop / restart / status / logs / upgrade
     ├─ main/                            ← 主系统运行目录（由 dist/pm-release-<版本>/ 上传；只发 AI 时可能不存在）
     └─ ai/                              ← AI 能力服务运行目录（本包内容）
         ├─ docker-compose.yml            服务器专用编排（无 build 段、pull_policy: never）
         │                                （= 仓库里的 ai-backend/docker-compose.deploy.yml，包内已改名）
         ├─ .env.example                  .env 模板（AI_IMAGE_TAG 已预填 $Version）
         ├─ 服务器部署步骤-ai.txt          本文件
         └─ releases/pm-ai-images-${arch}-${Version}.tar.gz
服务器上【不需要源码】，也不在服务器上构建。

1) 上传（开发机执行）
   ssh lhim@<AI服务器> 'mkdir -p /home/lhim/pm'
   scp -r $out/* lhim@<AI服务器>:/home/lhim/pm/
   ★包里的 ai/ 整体落到 /home/lhim/pm/ai/，与主系统包的 main/ 各占一个目录、【互不覆盖】；
     pm.sh 落在 /home/lhim/pm/pm.sh（两套包带的是同一份 pm.sh，重复上传无害）。
   ★Windows 上若 * 没被展开，分两次传即可（内容一样）：
       scp -r $out/pm.sh lhim@<AI服务器>:/home/lhim/pm/
       scp -r $out/ai    lhim@<AI服务器>:/home/lhim/pm/

2) 服务器配置 .env（首次；必须放在 ai/docker-compose.yml 同目录）
   cd /home/lhim/pm/ai
   cp .env.example .env && vi .env
   ★必须确认/填写这三项（模板里都已有对应行，逐条核对即可）：
     · LLM_API_KEY=<平台网关那把 sk>
        对话 / OCR / 向量化 / 重排【共用同一把】；只写在本机 .env，永不入库、不进镜像
     · AI_IMAGE_TAG=$Version
        已由发布包预填本次版本；docker compose 按它找本机已 load 的镜像，写错会报 No such image
     · AI_BIND_IP=<本机内网 IP>        例：AI_BIND_IP=10.254.208.40
        ★模板默认值是 0.0.0.0（= 对所有网卡开放），必须改成内网 IP：AI 服务当前【没有入站鉴权】，
          填内网 IP 并用防火墙只放行主系统服务器；若 AI 与主系统同宿主机，也可填 127.0.0.1
          （那样主系统得改用 host.docker.internal）。
   ★AI_IMAGE_TAG / AI_BIND_IP / AI_PORT 是 docker compose 的【变量插值】，必须写在这个 .env 里（与 compose 文件同目录）；
     AI_PORT 默认 8100，AI_WORK_DIR 默认 ./work（文档库/切片/向量缓存落盘处，都是可重建物，别放临时目录）。
   ★本包的 docker-compose.yml【已经】是 docker-compose.deploy.yml 的内容，
     服务器上【不需要】再执行 mv docker-compose.deploy.yml docker-compose.yml（那一步是手工部署流程里的，本包已完成改名）。
   ★之后升级【不要】用 .env.example 覆盖 .env（会丢 LLM_API_KEY）。

3) 启动（在部署根目录执行；★一条命令起全部，pm.sh 内部先起 ai 再起 main）
   cd /home/lhim/pm
   bash pm.sh start              # 起 AI + 主系统（默认 all）；只起 AI： bash pm.sh start ai
   bash pm.sh status             # 两个工程 docker compose ps + 健康结论（可用/不可用）
   bash pm.sh logs ai            # 跟 AI 服务日志（Ctrl-C 退出）；主系统： bash pm.sh logs main
   bash pm.sh stop               # 停止（反序：先 main 再 ai）；只停 AI： bash pm.sh stop ai
   ★不要再用 cd ai && docker compose up -d 的老写法：pm.sh 统一带 --env-file 与 -f，
     保证变量插值只认 ai/.env、工程名固定为目录名 ai；手工 cd 进去执行会多出一套同名不同工程的容器。

4) 自检（服务器本机）
   curl "http://127.0.0.1:`${AI_PORT:-8100}/health?with_ocr=false"                 # pm.sh 用的就是这个探针，期望 HTTP 200
   curl "http://127.0.0.1:`${AI_PORT:-8100}/health?with_ocr=true&with_vec=true"    # 连平台网关/OCR/向量的完整自检
   端口非默认时把 8100 换成 ai/.env 里的 AI_PORT。
   ⚠平台网关在内网：这台机器必须能访问 LLM_BASE_URL，否则 OCR/抽取/问答会直接报错（AI 不降级，见手册 §8.4）。

5) 回到【主系统那台服务器】把主系统接过来
   在 /home/lhim/pm/main/.env 里把 AI_SERVICE_BASE_URL 指到本机：
     AI_SERVICE_BASE_URL=http://<AI服务器内网IP>:8100
   ⚠绝不能填 127.0.0.1（在 pm-backend 容器里那是它自己）；同宿主机才用 http://host.docker.internal:8100
   cd /home/lhim/pm && bash pm.sh restart main      # 只改环境变量，不需要重建镜像
   打开「AI 与知识库 → 服务自检」，确认全部可用后再把 main/.env 的 AI_AUTO_PARSE 改成 true
   ★先自检、后开自动解析：自动解析开着时上传会立刻送 AI，网关不通会让失败率直接暴露给用户。

6) 升级（新版本）★必须用 pm.sh；旧发布包里的 pm-upgrade.sh 已作废（已被 pm.sh 取代）
   开发机出包： .\scripts\make-release.ps1 <新版本> -Project ai
   上传：       scp -r dist/pm-ai-release-<新版本>/* lhim@<AI服务器>:/home/lhim/pm/
   服务器：     cd /home/lhim/pm
                bash pm.sh upgrade ai ai/releases/pm-ai-images-${arch}-<新版本>.tar.gz
   pm.sh 依次做：docker load → 按包名解析版本 → 备份并切换 ai/.env 的 AI_IMAGE_TAG → up -d → 健康检查
   （原 .env 备份为 ai/.env.bak；包名被改过时用 VER=<版本> bash pm.sh upgrade ai <包路径> 显式指定）
   ⚠WORK_DIR 卷（默认 ai/work，可用 AI_WORK_DIR 改）存的是文档库 / 切片 / 向量缓存：都是可重建物，
     但别误删（删了要把附件重新 OCR 一遍）；双机主系统时 AI 只集中部署一台，两边各跑会让索引各自演化（手册 §8.3）。

7) 从旧布局迁移（pm-ai/app → pm/ai）★只做一次
   旧布局把 AI 放在 /home/lhim/pm-ai/app，compose 工程名 = 目录名 app；新布局目录名是 ai，工程名随之改变。
   编排里的容器名是固定的（pm-ai-backend），老容器还在时新工程起步会报 container name is already in use：
     docker rm -f pm-ai-backend                                # 释放固定容器名（只删容器，不动镜像与卷）
     （同一台机器上若主系统也还在旧布局，一并清掉： docker rm -f pm-backend pm-frontend pm-ai-backend）
     cp /home/lhim/pm-ai/app/.env /home/lhim/pm/ai/.env        # ★把旧 .env 搬过来，别重新填密钥
   然后按 1) 上传新包 → 3) 启动（bash pm.sh start ai）。
   ⚠AI 的 WORK_DIR 是绑定挂载（<运行目录>/work）：换目录后老的 pm-ai/app/work 不会被挂载——
     想保留文档库/向量缓存就把 work/ 一起 mv 过来，或者让它重新解析（都是可重建物）。
"@
    [System.IO.File]::WriteAllText((Join-Path $outAi '服务器部署步骤-ai.txt'), $steps, [System.Text.UTF8Encoding]::new($false))

    Write-Host ''
    Write-Host "✅ AI 发布目录已生成: $out/（服务器布局：包根 pm.sh + ai/ 项目目录）"
    Show-ReleaseTree $out
    Write-Host ''
    Write-Host '上传命令模板（把 <AI服务器> 换成 AI 服务那台机器；一次把整棵树送到部署根）：'
    Write-Host "  ssh lhim@<AI服务器> 'mkdir -p /home/lhim/pm'"
    Write-Host "  scp -r $out/* lhim@<AI服务器>:/home/lhim/pm/"
    Write-Host '    → 落地为 /home/lhim/pm/pm.sh 与 /home/lhim/pm/ai/…（与主系统包的 main/ 互不覆盖）'
    exit 0
}

# ============================================================
# 分支 B：主系统（-Project main，默认）
# ============================================================
$out = "dist/pm-release-$Version"
# 清空旧产出，避免上一版遗留文件（如已停用的 frontend-nginx.conf）混进发布包
if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Path $out -Force | Out-Null
# 新布局：包根放 pm.sh（部署根下的唯一入口），主系统放 main/，镜像包放 main/releases/
# ★releases/ 必须先建出来，否则写 tar.gz 时会报「未能找到路径 … 的一部分」
$outMain = Join-Path $out 'main'
$outMainReleases = Join-Path $outMain 'releases'
New-Item -ItemType Directory -Path $outMainReleases -Force | Out-Null

if ($env:SKIP_BUILD -eq '1') {
    Write-Host "[1/5] 跳过构建（SKIP_BUILD=1），使用本机已有镜像 pm-backend:${Version} / pm-frontend:${Version}"
    if ((Invoke-NativeQuiet $dockerBin @('image', 'inspect', "pm-backend:${Version}")).ExitCode -ne 0) {
        Write-Host "[FAIL] 本机不存在 pm-backend:${Version}"; exit 1
    }
    if ((Invoke-NativeQuiet $dockerBin @('image', 'inspect', "pm-frontend:${Version}")).ExitCode -ne 0) {
        Write-Host "[FAIL] 本机不存在 pm-frontend:${Version}"; exit 1
    }
} else {
    Write-Host "[1/5] 构建镜像（平台 $Platform，QEMU 模拟构建较慢，请耐心）"

    # -Only backend|frontend：先把"没改动那一端"要复用的来源 tag 定下来，**再**构建另一端。
    # ★顺序很关键：来源镜像不存在 / -ReuseTag 写错时要立刻退出，而不是白等十几~三十分钟构建完才发现没得复用。
    # 复用做法：docker tag 把本机已有的镜像打上本次版本号，于是后续导出/打包/上线流程与默认完全一致
    # （包里仍是 pm-backend + pm-frontend 两个 <版本> 镜像），服务器端不需要任何额外操作。
    # 复用来源的判定见 Resolve-ReuseSourceTag 上方注释。
    $reuseRepository = ''
    $reuseSrcTag = ''
    if ($Only -ceq 'backend')  { $reuseRepository = 'pm-frontend' }
    if ($Only -ceq 'frontend') { $reuseRepository = 'pm-backend' }
    if ($reuseRepository -cne '') {
        $reuseSrcTag = Resolve-ReuseSourceTag -DockerBin $dockerBin -Repository $reuseRepository -TargetVersion $Version -ReuseVersion $ReuseTag
    }

    if ($Only -ceq 'frontend') {
        Write-Host '      · 跳过后端构建（-Only frontend）'
    } else {
        & $dockerBin buildx build --platform $Platform --load -t "pm-backend:${Version}" -f deploy/docker/Dockerfile.backend .
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    if ($Only -ceq 'backend') {
        Write-Host '      · 跳过前端构建（-Only backend）'
    } else {
        & $dockerBin buildx build --platform $Platform --load -t "pm-frontend:${Version}" -f deploy/docker/Dockerfile.frontend .
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    if ($reuseRepository -cne '') {
        Write-Host "      · 复用镜像：${reuseRepository}:${reuseSrcTag}  →（docker tag）→  ${reuseRepository}:${Version}"
        & $dockerBin tag "${reuseRepository}:${reuseSrcTag}" "${reuseRepository}:${Version}"
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Assert-ImageArchMatch -DockerBin $dockerBin -ImageRef "${reuseRepository}:${Version}" -ExpectedArch $expectDockerArch -PlatformName $Platform
    }
}

Write-Host '[2/5] 校验架构'
& $dockerBin image inspect "pm-backend:${Version}" --format '  backend={{.Architecture}}/{{.Os}}'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host '[3/5] 导出镜像包'
$tarGz = Join-Path $outMainReleases "pm-images-${arch}-${Version}.tar.gz"
$tmpTar = Join-Path ([System.IO.Path]::GetTempPath()) "pm-images-${arch}-${Version}.tar"
& $dockerBin save -o $tmpTar "pm-backend:${Version}" "pm-frontend:${Version}"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# 等价于 `| gzip`（见文件头"踩过的坑"）：不能走 PowerShell 管道，二进制会被当文本解码
try {
    $inStream = [System.IO.File]::OpenRead($tmpTar)
    try {
        $outStream = [System.IO.File]::Create($tarGz)
        try {
            $gzip = [System.IO.Compression.GZipStream]::new($outStream, [System.IO.Compression.CompressionMode]::Compress)
            try { $inStream.CopyTo($gzip) } finally { $gzip.Dispose() }
        } finally { $outStream.Dispose() }
    } finally { $inStream.Dispose() }
} finally {
    if (Test-Path -LiteralPath $tmpTar) { Remove-Item -LiteralPath $tmpTar -Force }
}
Get-Item -LiteralPath $tarGz | ForEach-Object { Write-Host ("{0,8:N1} MB  {1}" -f ($_.Length / 1MB), $_.FullName) }

Write-Host '[4/5] 复制部署文件'
# 服务器唯一入口脚本（部署根下执行：bash pm.sh start|stop|status|logs|upgrade）：
# 取代了旧的 pm-upgrade.sh（已从仓库删除，不再复制）。两套发布包都带同一份 pm.sh，重复上传无害。
# ★守卫保留（文件不在时不中断打包），但会告警——缺了它服务器上就没有统一入口。
if (Test-Path -LiteralPath 'scripts/pm.sh') {
    Copy-Item -LiteralPath 'scripts/pm.sh' -Destination $out -Force
} else {
    Write-Host '[WARN] 未找到 scripts/pm.sh：发布包会缺少服务器唯一入口脚本（请确认仓库完整）'
}
# 服务器专用编排（无 build 段）：服务器运行目录里不需要任何源码
Copy-Item -LiteralPath 'deploy/docker/docker-compose.deploy.yml' -Destination (Join-Path $outMain 'docker-compose.yml') -Force
Copy-Item -LiteralPath 'deploy/docker/.env.example' -Destination $outMain -Force
# .env.example 中 IMAGE_TAG 预填为本次版本，避免服务器漏配导致 No such image（行尾处理保持原样）
$envExample = Join-Path $outMain '.env.example'
$exampleText = [System.IO.File]::ReadAllText($envExample, [System.Text.UTF8Encoding]::new($false))
$exampleText = [regex]::Replace($exampleText, '(?m)^IMAGE_TAG=.*$', { param($match) "IMAGE_TAG=$Version" })
[System.IO.File]::WriteAllText($envExample, $exampleText, [System.Text.UTF8Encoding]::new($false))

Write-Host '[5/5] 生成服务器部署步骤说明'
$steps = @"
PM $Version 服务器部署步骤（$arch，离线：只 load 不 build）
=====================================================
服务器上【不需要源码】，也不在服务器上构建。
【本包已按「一个部署根 + 两个隔离项目」的布局产好：上传后落地即符合布局，服务器上不需要再 mkdir/cp/mv】

    /home/lhim/pm/                       ← 部署根 = pm.sh 所在目录（pm.sh 按自身位置推导，不硬编码 /home/lhim）
     ├─ pm.sh                            ← 唯一入口：start / stop / restart / status / logs / upgrade
     ├─ main/                            ← 主系统运行目录（本包内容）
     │   ├─ docker-compose.yml            服务器专用编排（无 build 段）
     │   ├─ .env.example                  .env 模板（IMAGE_TAG 已预填 $Version）
     │   ├─ 服务器部署步骤.txt             本文件
     │   └─ releases/pm-images-${arch}-${Version}.tar.gz
     └─ ai/                              ← AI 能力服务运行目录（由 dist/pm-ai-release-<版本>/ 上传；没部署 AI 就没有这个目录）

1) 上传（开发机执行；两台服务器各一份）
   ssh lhim@<服务器> 'mkdir -p /home/lhim/pm'
   scp -r $out/* lhim@<服务器>:/home/lhim/pm/
   ★包里的 main/ 整体落到 /home/lhim/pm/main/，与 AI 包的 ai/ 各占一个目录、【互不覆盖】；
     pm.sh 落在 /home/lhim/pm/pm.sh（两套包带的是同一份 pm.sh，重复上传无害）。
   ★Windows 上若 * 没被展开，分两次传即可（内容一样）：
       scp -r $out/pm.sh  lhim@<服务器>:/home/lhim/pm/
       scp -r $out/main   lhim@<服务器>:/home/lhim/pm/

2) 服务器配置 .env（首次；放在 main/docker-compose.yml 同目录）
   cd /home/lhim/pm/main
   cp .env.example .env && vi .env
   必填：YASHAN_PASSWORD / JWT_SECRET（两台相同）/ OBS 五项；确认 IMAGE_TAG=$Version
   ★本包的 docker-compose.yml 就是仓库里 deploy/docker/docker-compose.deploy.yml 改名后放进 main/ 的，
     服务器上【不需要】再执行 mv docker-compose.deploy.yml docker-compose.yml（那一步是手工部署流程里的，本包已完成改名）。
   服务器附件统一 OBS：APP_STORAGE_TYPE=obs、APP_STORAGE_OBS_PREFIX=uploads
   ★YASHAN_DB=PM、YASHAN_USER=pm 两项【保持默认不要改】：
     - 用 sys 会按 SYS schema 查表 → 报表不存在；
     - 口令必须填【pm 用户】的，不是 sys 的（两者口令不同，是踩坑高发点）
   ★口令禁止明文进仓库：只填在服务器本机 .env（已 gitignore）
   改口令【无需重新构建镜像】（账号口令是运行时环境变量注入，不在镜像里）：
     cd /home/lhim/pm && bash pm.sh stop main → vi main/.env → bash pm.sh start main
   已出现 YAS-02193 the account is locked：先 stop 止血 → 改对口令 → 仍需 DBA 解锁
     （YAS-02143=口令错；YAS-02193=账号被锁，后者多是前者反复重试所致）
   之后升级【不要】用 .env.example 覆盖 .env
   ★AI 能力服务（可选，独立部署）：本次不部署 AI 时，.env 里的 AI_* 保持模板默认即可——
     打开「AI 与知识库 → 服务自检」会明确显示"AI 服务不可用"（这是对的，不是"未找到"）；
     部署 AI 服务后把 AI_SERVICE_BASE_URL 指向它（同宿主机用 host.docker.internal，另一台机器填其内网 IP，
     ★绝不能填 127.0.0.1，容器里那是后端自己），自检通过后再把 AI_AUTO_PARSE 改成 true。
     AI 服务自己的发布包/步骤见 dist/pm-ai-release-<版本>/（.\scripts\make-release.ps1 <版本> -Project ai）。

3) 启动（在部署根目录执行；★一条命令起全部，pm.sh 内部先起 ai 再起 main）
   cd /home/lhim/pm
   bash pm.sh start              # 起 AI + 主系统（默认 all）；只起主系统： bash pm.sh start main
   bash pm.sh status             # 两个工程 docker compose ps + 健康结论（可用/不可用）
   bash pm.sh logs main          # 跟主系统日志（Ctrl-C 退出）；只看后端： bash pm.sh logs main backend
   bash pm.sh stop               # 停止（反序：先 main 再 ai）；只停主系统： bash pm.sh stop main
   ★不要再用 cd main && docker compose up -d 的老写法：pm.sh 统一带 --env-file 与 -f，
     保证变量插值只认 main/.env、工程名固定为目录名 main；手工 cd 进去执行会多出一套同名不同工程的容器。

4) 验收
   curl http://127.0.0.1:`${WEB_PORT:-8080}/api/health      # 期望 db:"up"
   浏览器登录 admin/123456 → 下载一个存量附件（应成功，读 OBS 桶 uploads/ 前缀）
   （pm.sh start 结束时也会打印一次「访问地址与状态」，以那里的结论为准）

5) 升级（新版本，两台各做一遍）★必须用 pm.sh；旧发布包里的 pm-upgrade.sh 已作废（已被 pm.sh 取代）
   开发机出包： .\scripts\make-release.ps1 <新版本>
   上传：       scp -r dist/pm-release-<新版本>/* lhim@<服务器>:/home/lhim/pm/
   服务器：     cd /home/lhim/pm
                bash pm.sh upgrade main main/releases/pm-images-${arch}-<新版本>.tar.gz
   pm.sh 依次做：docker load → 按包名解析版本 → 备份并切换 main/.env 的 IMAGE_TAG → up -d → 健康检查
   （原 .env 备份为 main/.env.bak；包名被改过时用 VER=<版本> bash pm.sh upgrade main <包路径> 显式指定）

6) 回滚（如需）
   改 main/.env 的 IMAGE_TAG 回上一版本号 → cd /home/lhim/pm && bash pm.sh restart main
   （旧镜像仍在本机；改动前的 .env 就是 main/.env.bak，可直接 cp 回去）

7) 从旧布局迁移（pm/app → pm/main）★只做一次
   旧布局把主系统放在 /home/lhim/pm/app，compose 工程名 = 目录名 app；新布局目录名是 main，工程名随之改变。
   编排里的容器名是固定的（pm-backend / pm-frontend），老容器还在时新工程起步会报 container name is already in use：
     docker rm -f pm-backend pm-frontend                        # 释放固定容器名（只删容器，不动镜像与卷）
     （同一台机器上若 AI 也在旧布局，一并清掉： docker rm -f pm-backend pm-frontend pm-ai-backend）
     cp /home/lhim/pm/app/.env /home/lhim/pm/main/.env          # ★把旧 .env 搬过来，别重新填口令
   然后按 1) 上传新包 → 3) 启动（bash pm.sh start）。
   ⚠附件卷名带工程名前缀：本地卷（APP_STORAGE_TYPE=local）在旧工程下叫 app_pm_uploads，新工程会找 main_pm_uploads——
     生产走 OBS 时该卷只是过场、不受影响；确实用 local 存储就先备份数据，或在 main/.env 里显式
     UPLOAD_VOLUME=app_pm_uploads 继续挂旧卷（确认旧卷仍在： docker volume ls | grep pm_uploads）。
"@
[System.IO.File]::WriteAllText((Join-Path $outMain '服务器部署步骤.txt'), $steps, [System.Text.UTF8Encoding]::new($false))

Write-Host ''
Write-Host "✅ 发布目录已生成: $out/（服务器布局：包根 pm.sh + main/ 项目目录）"
Show-ReleaseTree $out
Write-Host ''
Write-Host '上传命令模板（把 <服务器> 换成 pdmsappgh / ai-kingbase-gh；一次把整棵树送到部署根）：'
Write-Host "  ssh lhim@<服务器> 'mkdir -p /home/lhim/pm'"
Write-Host "  scp -r $out/* lhim@<服务器>:/home/lhim/pm/"
Write-Host '    → 落地为 /home/lhim/pm/pm.sh 与 /home/lhim/pm/main/…（与 AI 包的 ai/ 互不覆盖）'
