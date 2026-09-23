# ============================================================
# PM 发布打包脚本（在开发机执行，产出可直接上传服务器的发布目录）
#
# 用法（在仓库根或任意位置执行均可）：
#   .\scripts\make-release.ps1 <版本> [平台]
#   例：.\scripts\make-release.ps1 v3.1.1              # 默认 linux/arm64（生产两台服务器）
#       .\scripts\make-release.ps1 v3.1.1 linux/amd64  # 如需 x86 产物
#
# 环境变量：
#   SKIP_BUILD=1   跳过构建，直接打包本机已存在的同版本镜像（快速重打包）
#   DOCKER=...     指定 docker 可执行文件（默认先查 PATH，再查 Docker Desktop 安装目录）
#
# 产出：dist/pm-release-<版本>/
#   ├─ pm-images-<arch>-<版本>.tar.gz    镜像包（docker load 用）
#   ├─ docker-compose.yml                服务器专用编排（无 build 段；运行目录只需「本文件 + .env」）
#   ├─ .env.example                      .env 模板（含 IMAGE_TAG 预填）
#   ├─ pm-upgrade.sh                     服务器升级脚本（可选）
#   └─ 服务器部署步骤.txt                 照做即可
#
# 前置条件：Docker Desktop 已启动；构建 arm64 需先装 QEMU 模拟（只需一次）：
#   docker run --privileged --rm tonistiigi/binfmt --install arm64
#
# 踩过的坑：
#   - 本机没有 gzip.exe（也没有 Git Bash）：原 .sh 的 `docker save ... | gzip > x.tar.gz` 不能照抄——
#     bash 管道传的是字节流，而 PowerShell 管道会把二进制当文本解码，镜像包必然损坏。
#     这里改成 `docker save -o 临时 tar` + .NET GZipStream 压缩（等价于 gzip；只是 gzip 头里不写
#     原始文件名与 mtime，docker load 不看这两项），压缩完删掉临时文件。
#   - 为什么从 .sh 改为 .ps1：本机没有 Git Bash（PATH 上的 bash 只是 WSL 桩），.sh 跑不了；
#     开发机脚本统一用 Windows 原生 PowerShell，避免 .sh/.ps1 两套并存产生漂移。
#     （`pm-upgrade.sh` 随发布包下发、只在 Linux 服务器上运行，保持 .sh 不动。）
#   - 本脚本文件是 UTF-8 **带 BOM**：Windows PowerShell 5.1 对无 BOM 的 UTF-8 脚本按
#     ANSI(GBK) 解析，中文字面量会直接变乱码；带 BOM 才能 5.1 与 7 都正常显示中文。
#   - 在 5.1 下若执行策略为 Restricted，用 `powershell -ExecutionPolicy Bypass -File ...` 调用。
# ============================================================
param(
    [Parameter(Position = 0)][string]$Version,
    [Parameter(Position = 1)][string]$Platform = 'linux/arm64'
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

if ([string]::IsNullOrEmpty($Version)) {
    [Console]::Error.WriteLine('用法: scripts/make-release.ps1 <版本> [平台]')
    exit 1
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

# 定位仓库根（脚本位于 scripts/ 下）
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

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

$out = "dist/pm-release-$Version"
# 清空旧产出，避免上一版遗留文件（如已停用的 frontend-nginx.conf）混进发布包
if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Path $out -Force | Out-Null

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
    & $dockerBin buildx build --platform $Platform --load -t "pm-backend:${Version}" -f deploy/docker/Dockerfile.backend .
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $dockerBin buildx build --platform $Platform --load -t "pm-frontend:${Version}" -f deploy/docker/Dockerfile.frontend .
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host '[2/5] 校验架构'
& $dockerBin image inspect "pm-backend:${Version}" --format '  backend={{.Architecture}}/{{.Os}}'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host '[3/5] 导出镜像包'
$tarGz = Join-Path $out "pm-images-${arch}-${Version}.tar.gz"
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
# 服务器专用编排（无 build 段）：服务器运行目录里不需要任何源码
Copy-Item -LiteralPath 'deploy/docker/docker-compose.deploy.yml' -Destination (Join-Path $out 'docker-compose.yml') -Force
Copy-Item -LiteralPath 'deploy/docker/.env.example' -Destination $out -Force
if (Test-Path -LiteralPath 'scripts/pm-upgrade.sh') { Copy-Item -LiteralPath 'scripts/pm-upgrade.sh' -Destination $out -Force }
# .env.example 中 IMAGE_TAG 预填为本次版本，避免服务器漏配导致 No such image
$envExample = Join-Path $out '.env.example'
$exampleText = [System.IO.File]::ReadAllText($envExample, [System.Text.UTF8Encoding]::new($false))
$exampleText = [regex]::Replace($exampleText, '(?m)^IMAGE_TAG=.*$', { param($match) "IMAGE_TAG=$Version" })
[System.IO.File]::WriteAllText($envExample, $exampleText, [System.Text.UTF8Encoding]::new($false))

Write-Host '[5/5] 生成服务器部署步骤说明'
$steps = @"
PM $Version 服务器部署步骤（$arch，离线：只 load 不 build）
=====================================================
服务器上【不需要源码】，运行目录只有 docker 相关文件：
    /home/lhim/pm/
     ├─ releases/                        镜像包归档
     └─ app/                             运行目录：只需 docker-compose.yml + .env
         ├─ docker-compose.yml
         └─ .env

1) 上传（开发机执行；两台服务器各一份）
   scp $out/pm-images-${arch}-${Version}.tar.gz  lhim@<服务器>:/home/lhim/pm/releases/
   ssh lhim@<服务器> 'mkdir -p /home/lhim/pm/app'
   scp $out/docker-compose.yml $out/.env.example  lhim@<服务器>:/home/lhim/pm/app/

2) 服务器配置 .env（首次；放在 docker-compose.yml 同目录）
   cd /home/lhim/pm/app
   cp .env.example .env && vi .env
   必填：YASHAN_PASSWORD / JWT_SECRET（两台相同）/ OBS 五项；确认 IMAGE_TAG=$Version
   服务器附件统一 OBS：APP_STORAGE_TYPE=obs、APP_STORAGE_OBS_PREFIX=uploads
   ★YASHAN_DB=PM、YASHAN_USER=pm 两项【保持默认不要改】：
     - 用 sys 会按 SYS schema 查表 → 报表不存在；
     - 口令必须填【pm 用户】的，不是 sys 的（两者口令不同，是踩坑高发点）
   ★口令禁止明文进仓库：只填在服务器本机 .env（已 gitignore）
   改口令【无需重新构建镜像】（账号口令是运行时环境变量注入，不在镜像里）：
     docker compose stop → vi .env → docker compose up -d
   已出现 YAS-02193 the account is locked：先 stop 止血 → 改对口令 → 仍需 DBA 解锁
     （YAS-02143=口令错；YAS-02193=账号被锁，后者多是前者反复重试所致）
   之后升级【不要】用 .env.example 覆盖 .env
   ★AI 能力服务（可选，独立部署）：本次不部署 AI 时，.env 里的 AI_* 保持模板默认即可——
     打开「AI 与知识库 → 服务自检」会明确显示"AI 服务不可用"（这是对的，不是"未找到"）；
     部署 AI 服务后把 AI_SERVICE_BASE_URL 指向它（同宿主机用 host.docker.internal，另一台机器填其内网 IP，
     ★绝不能填 127.0.0.1，容器里那是后端自己），自检通过后再把 AI_AUTO_PARSE 改成 true。
     详见 docker-compose.yml 同目录的 .env.example 注释与《部署与发布全流程手册》§8。

3) 加载镜像并启动（不加 --build）
   docker load -i /home/lhim/pm/releases/pm-images-${arch}-${Version}.tar.gz
   cd /home/lhim/pm/app && docker compose up -d

4) 验收
   curl http://127.0.0.1:8080/api/health      # 期望 db:"up"
   浏览器登录 admin/123456 → 下载一个存量附件（应成功，读 OBS 桶 uploads/ 前缀）

5) 升级（新版本）
   docker load -i /home/lhim/pm/releases/pm-images-${arch}-<新版本>.tar.gz
   cd /home/lhim/pm/app
   改 .env 的 IMAGE_TAG=<新版本>   →   docker compose up -d

6) 回滚（如需）
   改 .env 的 IMAGE_TAG 为上一版本号 → docker compose up -d（旧镜像仍在本机）
"@
[System.IO.File]::WriteAllText((Join-Path $out '服务器部署步骤.txt'), $steps, [System.Text.UTF8Encoding]::new($false))

Write-Host ''
Write-Host "✅ 发布目录已生成: $out/"
Get-ChildItem -Force -LiteralPath $out | Format-Table Mode, LastWriteTime, Length, Name -AutoSize
Write-Host ''
Write-Host '上传命令模板（把 <服务器> 换成 pdmsappgh / ai-kingbase-gh）：'
Write-Host "  scp $out/pm-images-${arch}-${Version}.tar.gz lhim@<服务器>:/home/lhim/pm/releases/"
Write-Host "  scp $out/docker-compose.yml $out/.env.example lhim@<服务器>:/home/lhim/pm/app/"
