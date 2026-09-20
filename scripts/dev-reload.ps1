# ============================================================
# 开发机：改完代码后一键「重建镜像 + 重启」，本机自测用
#
# 用法（在仓库根或任意位置执行均可）：
#   .\scripts\dev-reload.ps1              # 重建前后端并重启（默认）
#   .\scripts\dev-reload.ps1 backend      # 只重建后端（改了 Java / SQL 迁移）
#   .\scripts\dev-reload.ps1 frontend     # 只重建前端（改了 tsx / ts / css）
#
# 前置条件：Docker Desktop 已启动；deploy/docker/.env 已备好
#   （本机测试配置，见 docs/部署与发布全流程手册.md §1.3）
#
# 与正式发版的区别：
#   - 本脚本面向**开发机本机**：用 deploy/docker/docker-compose.yml（含 build 段），
#     走 amd64 本机构建，快；不产 arm64、不生成发布包、不动 releases/。
#   - 服务器发版仍用：scripts/make-release.ps1（开发机）+ scripts/pm-upgrade.sh（服务器）。
#
# 说明：
#   - 镜像 tag 取 deploy/docker/.env 的 IMAGE_TAG（本机默认 latest）；
#   - compose 检测到镜像变化会自动重建并重启对应容器，无需手动 stop；
#   - 脚本最后会轮询 /api/health，就绪后打印访问地址。
#   - 健康检查必须写 `curl.exe`：Windows PowerShell 5.1 里 `curl` 是 Invoke-WebRequest 的别名，
#     直接写 curl 语义完全不同（这也是它从 .sh 翻译过来时最容易踩的坑）。
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
    [Parameter(Position = 0)][string]$Target = 'all'
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
