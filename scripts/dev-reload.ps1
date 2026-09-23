# ============================================================
# 开发机：改完代码后一键「重建镜像 + 重启」，本机自测用
#
# 用法（在仓库根或任意位置执行均可）：
#   .\scripts\dev-reload.ps1                    # 主系统：重建前后端并重启（默认）
#   .\scripts\dev-reload.ps1 backend            # 主系统：只重建后端（改了 Java / SQL 迁移）
#   .\scripts\dev-reload.ps1 frontend           # 主系统：只重建前端（改了 tsx / ts / css）
#   .\scripts\dev-reload.ps1 -Project ai        # AI 能力服务：重建并重启 + 健康检查
#   ★-Project ai 不接受位置参数 all|backend|frontend（那是主系统的语义）→ 传了明确报错 exit 2。
#
# 前置条件：Docker Desktop 已启动
#   · 主系统：deploy/docker/.env 已备好（本机测试配置，见 docs/部署与发布全流程手册.md §1.3）
#   · AI 服务：ai-backend/.env 已备好（cp ai-backend/.env.example ai-backend/.env 后填 LLM_API_KEY，
#     见 docs/部署与发布全流程手册.md §8.2）
#
# 与正式发版的区别：
#   - 本脚本面向**开发机本机**：主系统用 deploy/docker/docker-compose.yml、AI 用 ai-backend/docker-compose.yml
#     （两者都含 build 段），走 amd64 本机构建，快；不产 arm64、不生成发布包、不动 releases/。
#   - 服务器发版仍用：scripts/make-release.ps1（开发机）+ scripts/pm-upgrade.sh（服务器）；
#     AI 服务出发布包用 `.\scripts\make-release.ps1 <版本> -Project ai`（见 scripts/README.md §2.1）。
#
# 说明：
#   - 主系统镜像 tag 取 deploy/docker/.env 的 IMAGE_TAG（本机默认 latest）；AI 用编排里写死的 pm-ai-backend:local；
#   - compose 检测到镜像变化会自动重建并重启对应容器，无需手动 stop；
#   - 脚本最后会轮询健康检查，就绪后打印访问地址。
#   - 健康检查必须写 `curl.exe`：Windows PowerShell 5.1 里 `curl` 是 Invoke-WebRequest 的别名，
#     直接写 curl 语义完全不同（这也是它从 .sh 翻译过来时最容易踩的坑）。
#   - 两套编排的 build 上下文都是**仓库根**（ai-backend/docker-compose.yml 里是 context: ..，
#     Dockerfile 里是 COPY ai-backend/...），所以 AI 分支用 `-f ai-backend/docker-compose.yml` 从仓库根调用，
#     而不是 cd 进 ai-backend/——文件里的相对路径（./work、.env）由 compose 按编排文件所在目录解析，不受影响。
#
# 踩过的坑：
#   - 为什么从 .sh 改为 .ps1：本机没有 Git Bash（PATH 上的 bash 只是 WSL 桩），.sh 跑不了；
#     开发机脚本统一用 Windows 原生 PowerShell，避免 .sh/.ps1 两套并存产生漂移。
#     （`pm-upgrade.sh` 随发布包下发、只在 Linux 服务器上运行，保持 .sh 不动。）
#   - 本脚本文件是 UTF-8 **带 BOM**：Windows PowerShell 5.1 对无 BOM 的 UTF-8 脚本按
#     ANSI(GBK) 解析，中文字面量会直接变乱码；带 BOM 才能 5.1 与 7 都正常显示中文。
#   - 在 5.1 下若执行策略为 Restricted，用 `powershell -ExecutionPolicy Bypass -File ...` 调用。
# ============================================================
param(
    [Parameter(Position = 0)][string]$Target = 'all',
    [string]$Project = 'main'
)

$ErrorActionPreference = 'Stop'
# 本机控制台默认 936(GBK)：按 UTF-8 输出，避免中文乱码
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

# 跑原生命令并静默它的 stderr，返回 @{ Output = <stdout 行>; ExitCode = <退出码> } 形式对象
# 等价于 bash 的 `cmd 2>/dev/null`。必须包一层函数：PS 5.1 在 $ErrorActionPreference='Stop' 下
# 对原生命令做 stderr 重定向会被当成**终止性错误**（5.1 独有行为），函数内临时降为 Continue 规避。
function Invoke-NativeQuiet([string]$Exe, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = @(& $Exe @Arguments 2>$null)
    return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
}

# 定位仓库根（脚本位于 scripts/ 下）
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($Project -cne 'main' -and $Project -cne 'ai') {
    [Console]::Error.WriteLine("[FAIL] -Project 只能是 main | ai（当前：$Project）")
    exit 2
}

# ============================================================
# 分支 A：AI 能力服务（-Project ai）
#   编排文件 ai-backend/docker-compose.yml 含 build 段（context: ..，dockerfile: ai-backend/Dockerfile），
#   与主系统的 deploy/docker/docker-compose.yml 是**两套**，互不影响。
# ============================================================
if ($Project -ceq 'ai') {
    # 位置参数 Target 是主系统语义（all|backend|frontend），AI 只有一个服务，不接受
    if ($PSBoundParameters.ContainsKey('Target')) {
        [Console]::Error.WriteLine("[FAIL] -Project ai 不接受位置参数 all | backend | frontend（那是主系统语义；当前：$Target）")
        [Console]::Error.WriteLine('       AI 服务只有一个容器：.\scripts\dev-reload.ps1 -Project ai')
        exit 2
    }

    $composeFile = Join-Path $repoRoot 'ai-backend\docker-compose.yml'
    $envFile = Join-Path $repoRoot 'ai-backend\.env'
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        [Console]::Error.WriteLine("[FAIL] 未找到 $composeFile")
        exit 1
    }
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        [Console]::Error.WriteLine("[FAIL] 未找到 $envFile（AI 服务的本机配置，密钥只放这里）")
        [Console]::Error.WriteLine('       请先准备：cp ai-backend\.env.example ai-backend\.env，然后填写 LLM_API_KEY 等')
        [Console]::Error.WriteLine('       各字段含义见 docs\部署与发布全流程手册.md §8.2')
        exit 1
    }

    # 端口取 ai-backend/.env 的 AI_PORT（取不到则回落 8100，与 .env.example 默认一致）
    $port = ''
    foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
        if ($line -cmatch '^AI_PORT=') {
            $port = $line.Substring('AI_PORT='.Length).Trim()
            break
        }
    }
    if ([string]::IsNullOrEmpty($port)) { $port = '8100' }

    # ★锚定仓库根并同步 .NET 当前目录（理由见 scripts/README.md §0.2）：
    #   compose 的相对路径由编排文件所在目录解析，但 -f 传的就是仓库根下的相对路径，
    #   所以必须保证 PowerShell 当前目录（$PWD）是仓库根，才能从任何位置调用。
    Set-Location -LiteralPath $repoRoot
    [System.IO.Directory]::SetCurrentDirectory($repoRoot)

    Write-Host '──────────────────────────────────────────────'
    Write-Host " 项目：ai（AI 能力服务）    编排：ai-backend/docker-compose.yml    端口：$port"
    Write-Host '──────────────────────────────────────────────'

    Write-Host '[1/3] 重建镜像并启动容器（--build；build 上下文=仓库根，改了依赖/首次构建会较慢）'
    & docker compose -f 'ai-backend/docker-compose.yml' up -d --build
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host ''
    Write-Host '[2/3] 等待 AI 服务就绪…'
    $ready = $false
    # 3 秒一轮、最多 20 轮（约 60 秒）：镜像自带 healthcheck start_period=30s，
    # 首次连平台网关做初始化时会更久，超时就按下面的提示排查。
    for ($i = 1; $i -le 20; $i++) {
        # curl -sf -m 3：静默 + HTTP 失败即非零 + 3 秒超时（必须写 curl.exe，见文件头说明）
        # 只探 with_ocr=false：这个探针不依赖平台网关，避免网关抖动误判成"服务没起来"
        if ((Invoke-NativeQuiet 'curl.exe' @('-sf', '-m', '3', "http://127.0.0.1:$port/health?with_ocr=false")).ExitCode -eq 0) {
            $ready = $true; break
        }
        Start-Sleep -Seconds 3
    }

    Write-Host ''
    if ($ready) {
        Write-Host "✅ 已就绪：http://127.0.0.1:$port/health?with_ocr=false"
        Write-Host '   完整自检（会连平台网关/OCR/向量，慢一些）：'
        Write-Host "     curl.exe ""http://127.0.0.1:$port/health?with_ocr=true&with_vec=true"""
    } else {
        Write-Host '⚠️  等待超时（约 60 秒；AI 服务可能仍在启动或启动失败）。排查：'
        Write-Host "     docker compose -f $repoRoot\ai-backend\docker-compose.yml logs --tail 80 ai-backend"
        Write-Host '     常见原因：ai-backend\.env 没填 LLM_API_KEY、平台网关不可达（内网/VPN 未连）、端口被占用'
    }

    Write-Host ''
    Write-Host '[3/3] 容器状态'
    & docker compose -f 'ai-backend/docker-compose.yml' ps
    exit $LASTEXITCODE
}

# ============================================================
# 分支 B：主系统（默认 -Project main）——行为与历史版本完全一致
# ============================================================
$composeDir = Join-Path $repoRoot 'deploy\docker'

if (-not (Test-Path -LiteralPath (Join-Path $composeDir 'docker-compose.yml') -PathType Leaf)) {
    [Console]::Error.WriteLine("[FAIL] 未找到 $composeDir\docker-compose.yml")
    exit 1
}
if (-not (Test-Path -LiteralPath (Join-Path $composeDir '.env') -PathType Leaf)) {
    [Console]::Error.WriteLine("[FAIL] 未找到 $composeDir\.env（本机测试配置），请先按 docs/部署与发布全流程手册.md §1.3 准备")
    exit 1
}

switch -CaseSensitive ($Target) {
    'all'      { $services = 'backend frontend' }
    'backend'  { $services = 'backend' }
    'frontend' { $services = 'frontend' }
    default {
        [Console]::Error.WriteLine("[FAIL] 参数只能是 all | backend | frontend（当前：$Target）")
        exit 2
    }
}
$serviceList = $services -split ' '

Set-Location $composeDir

# 端口取 .env 的第一条 WEB_PORT=（取不到则回落 8080，与原 .sh 一致）
$port = ''
foreach ($line in [System.IO.File]::ReadAllLines((Join-Path $composeDir '.env'))) {
    if ($line -cmatch '^WEB_PORT=') {
        $port = $line.Substring('WEB_PORT='.Length)
        break
    }
}
if ([string]::IsNullOrEmpty($port)) { $port = '8080' }

Write-Host '──────────────────────────────────────────────'
Write-Host " 目标：$services    端口：$port"
Write-Host '──────────────────────────────────────────────'

Write-Host '[1/3] 重建镜像（改了依赖/首次构建会较慢，请耐心）'
& docker compose build $serviceList
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ''
Write-Host '[2/3] 重建并启动容器（镜像变化时 compose 会自动重建）'
& docker compose up -d $serviceList
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ''
Write-Host '[3/3] 等待后端就绪…'
$ready = $false
for ($i = 1; $i -le 60; $i++) {
    # curl -sf -m 3：静默 + HTTP 失败即非零 + 3 秒超时（必须写 curl.exe，见文件头说明）
    if ((Invoke-NativeQuiet 'curl.exe' @('-sf', '-m', '3', "http://127.0.0.1:$port/api/health")).ExitCode -eq 0) {
        $ready = $true; break
    }
    Start-Sleep -Seconds 3
}

Write-Host ''
if ($ready) {
    Write-Host "✅ 已就绪：http://127.0.0.1:$port/    （默认账号 admin / 123456）"
} else {
    Write-Host '⚠️  等待超时（后端可能仍在启动或启动失败）。排查：'
    Write-Host "     cd $composeDir && docker compose logs --tail 80 backend"
}

Write-Host ''
& docker compose ps
exit $LASTEXITCODE
