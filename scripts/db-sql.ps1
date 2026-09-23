# ============================================================
# 在开发机上直接对崖山 YashanDB 执行 SQL（排障 / 批量修数据 / 清演示数据）
#
# 用法（在仓库根或任意位置执行均可）：
#   .\scripts\db-sql.ps1 scripts/demo-reset.sql      # 清空演示数据
#   .\scripts\db-sql.ps1 my-query.sql                # 任意 SQL 文件（相对/绝对路径均可）
#
# 前置条件：JDK 17+（java 在 PATH 上）；无参调用会打印本段说明（截取脚本第 2~20 行）
#
# 连接信息取值优先级（口令只在进程环境里传递，**不会被打印、不落盘**）：
#   1) 已导出的 DB_URL / DB_USER / DB_PASSWORD
#   2) 运行中的 pm-backend 容器环境变量（docker inspect，最可靠）
#   3) deploy/docker/.env 的 YASHAN_*（自行拼默认 URL；不依赖任何仓库外目录）
#
# 为什么需要这个脚本：开发机通常没有 yasql 客户端；后端的迁移执行器只跑
# db/migration-yashan 下的脚本。手工查/改数据时用本脚本 + scripts/jdbc/RunSql.java 兜底
# （走 backend/lib 里入库的 JDBC 驱动，无需额外安装）。
#
# 踩过的坑（为什么从 .sh 改成 .ps1、编码、执行策略）见本注释块末尾那几行。
# ============================================================
# 踩过的坑：
#   - 为什么从 .sh 改为 .ps1：本机没有 Git Bash（PATH 上的 bash 只是 WSL 桩），.sh 跑不了；
#     开发机脚本统一用 Windows 原生 PowerShell，避免 .sh/.ps1 两套并存产生漂移。
#     （`scripts/pm.sh` 随发布包下发、只在 Linux 服务器上运行，保持 .sh 不动。）
#   - 本脚本文件是 UTF-8 **带 BOM**：Windows PowerShell 5.1 对无 BOM 的 UTF-8 脚本按
#     ANSI(GBK) 解析，中文字面量会直接变乱码；带 BOM 才能 5.1 与 7 都正常显示中文。
#   - 在 5.1 下若执行策略为 Restricted，用 `powershell -ExecutionPolicy Bypass -File ...` 调用。
#   - 原 .sh 里 `java` 用 cygpath 转 Windows 路径（因为 Git Bash 给的是 /c/... 形式）；
#     原生 PowerShell 拿到的本来就是 Windows 路径，这一层转换不再需要。
#   - PowerShell 会把裸写的 `-Dfile.encoding=UTF-8` 在"."处拆成 `-Dfile` + `.encoding=UTF-8`
#     （5.1 与 7 都如此，实测踩到），所以调用 java 时这两个 -D 参数必须加引号（见文件末尾）。
# ============================================================
param(
    [Parameter(Position = 0)][string]$SqlFile
)

$ErrorActionPreference = 'Stop'
# 本机控制台默认 936(GBK)：按 UTF-8 输出，避免中文乱码（Java 子进程也会跟着走 UTF-8）
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
Set-Location (Split-Path -Parent $PSScriptRoot)
$root = (Get-Location).Path

if ([string]::IsNullOrEmpty($SqlFile)) {
    # 与原 .sh 相同：打印脚本自身的第 2~20 行并去掉行首 "# "（本段注释，不含任何代码）
    Get-Content -LiteralPath $PSCommandPath -Encoding UTF8 |
        Select-Object -Skip 1 -First 19 |
        ForEach-Object { $_ -replace '^# ?', '' }
    exit 1
}
if (-not (Test-Path -LiteralPath $SqlFile -PathType Leaf)) {
    [Console]::Error.WriteLine("❌ 找不到 SQL 文件：$SqlFile")
    exit 1
}

$driver = Join-Path $root 'backend\lib\yashandb-jdbc-1.9.3.jar'
$runner = Join-Path $root 'scripts\jdbc\RunSql.java'
foreach ($f in @($driver, $runner)) {
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) {
        [Console]::Error.WriteLine("❌ 缺少文件：$f")
        exit 1
    }
}

# 读 KEY=VALUE 形式的属性文件（近似 bash `set -a; . .env`；不支持变量插值与命令替换）
function Read-DotEnvFile([string]$Path) {
    $map = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $text = $line.Trim()
        if ($text -eq '' -or $text.StartsWith('#')) { continue }
        $eq = $text.IndexOf('=')
        if ($eq -lt 1) { continue }
        $key = $text.Substring(0, $eq).Trim()
        if ($key.StartsWith('export ')) { $key = $key.Substring(7).Trim() }
        $value = $text.Substring($eq + 1)
        # 成对的首尾引号按 bash 语义去掉
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            if (($first -eq '"' -or $first -eq "'") -and $value.EndsWith($first)) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        $map[$key] = $value
    }
    return $map
}

# 取第一个匹配前缀的行（等价于 sed -n 's/^PREFIX=//p' | head -1）
function Get-FirstPrefixedLineValue([string[]]$Lines, [string]$Prefix) {
    foreach ($line in $Lines) {
        if ($line.StartsWith($Prefix)) { return $line.Substring($Prefix.Length) }
    }
    return ''
}

# 取 .env 变量；未定义**或为空**时用默认值（等价于 bash 的 ${VAR:-默认}，注意"空值也取默认"）
function Get-EnvValueOrDefault([hashtable]$Map, [string]$Key, [string]$Default) {
    if ($Map.ContainsKey($Key) -and -not [string]::IsNullOrEmpty($Map[$Key])) { return $Map[$Key] }
    return $Default
}

[string]$dbUrl = $env:DB_URL
[string]$dbUser = $env:DB_USER
[string]$dbPassword = $env:DB_PASSWORD

# ── 1/2) 从容器环境变量取（容器在跑时优先）──
if ([string]::IsNullOrEmpty($dbUrl) -or [string]::IsNullOrEmpty($dbPassword)) {
    $names = @()
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        $names = @((Invoke-NativeQuiet 'docker' @('ps', '--format', '{{.Names}}')).Output)
    }
    if ($names -contains 'pm-backend') {
        $envDump = @((Invoke-NativeQuiet 'docker' @('inspect', 'pm-backend', '--format', '{{range .Config.Env}}{{println .}}{{end}}')).Output)
        if ([string]::IsNullOrEmpty($dbUrl))      { $dbUrl      = Get-FirstPrefixedLineValue $envDump 'SPRING_DATASOURCE_URL=' }
        if ([string]::IsNullOrEmpty($dbUser))     { $dbUser     = Get-FirstPrefixedLineValue $envDump 'SPRING_DATASOURCE_USERNAME=' }
        if ([string]::IsNullOrEmpty($dbPassword)) { $dbPassword = Get-FirstPrefixedLineValue $envDump 'SPRING_DATASOURCE_PASSWORD=' }
    }
}

# ── 3) 兜底：读 deploy/docker/.env 自行拼 URL ──
$envFile = Join-Path $root 'deploy\docker\.env'
if ([string]::IsNullOrEmpty($dbUrl) -and (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    $vars = Read-DotEnvFile $envFile
    $masterIp  = Get-EnvValueOrDefault $vars 'YASHAN_MASTER_IP'  '10.254.212.106'
    $standbyIp = Get-EnvValueOrDefault $vars 'YASHAN_STANDBY_IP' '10.254.212.107'
    $dbName    = Get-EnvValueOrDefault $vars 'YASHAN_DB'         'PM'
    $dbUrl = "jdbc:yasdb:primary://${masterIp}:1688,${standbyIp}:1688/${dbName}?poolTimeout=60&failover=on&failoverType=session&failoverMethod=basic&failoverRetries=5&failoverDelay=2"
    $dbUser = Get-EnvValueOrDefault $vars 'YASHAN_USER' 'pm'
    $dbPassword = Get-EnvValueOrDefault $vars 'YASHAN_PASSWORD' ''
}

if ([string]::IsNullOrEmpty($dbUrl) -or [string]::IsNullOrEmpty($dbPassword)) {
    [Console]::Error.WriteLine('❌ 拿不到数据库连接信息。请先启动 pm-backend 容器，或导出 DB_URL / DB_USER / DB_PASSWORD。')
    exit 1
}

# 口令只经环境变量传给 java 子进程（RunSql.java 从 getenv 读），不打印、不落盘
$env:DB_URL = $dbUrl
$env:DB_USER = $dbUser
$env:DB_PASSWORD = $dbPassword

$urlWithoutQuery = $dbUrl.Split('?')[0]
Write-Host "→ 目标库：$urlWithoutQuery"
Write-Host "→ 账号：$dbUser（口令长度 $($dbPassword.Length)，不打印）"
Write-Host "→ 执行：$SqlFile"
Write-Host ''

# 原 .sh 在这里按 uname 判断 Windows 并用 cygpath 转路径；原生 PowerShell 已是 Windows 路径
$sqlPath = (Resolve-Path -LiteralPath $SqlFile).ProviderPath
# -Dfile.encoding：Windows 控制台默认 GBK，不指定会让中文输出变乱码（JDK 17 用此参数）
# ⚠️ 两个 -D 参数**必须加引号**：裸写 `-Dfile.encoding=UTF-8` 会被 PowerShell 在"."处拆成
#    `-Dfile` + `.encoding=UTF-8`（5.1 与 7 都如此），java 会报"找不到或无法加载主类 .encoding=UTF-8"
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $driver $runner $sqlPath
exit $LASTEXITCODE
