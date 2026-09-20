# AI 能力服务（Java 版）· 端到端真实验收
#
# 为什么要有这个脚本：迁移的验收标准不是"能编译"，而是"对着真实内网网关跑出与 Python 版同级的数字"。
# 手工 curl 容易漏项、也难复现，所以固化成一条命令，产出可直接对比的证据。
#
# 用法（在 ai-backend/ 目录下）：
#   pwsh -File scripts\verify-e2e.ps1                 # 默认端口 8101（避开 Python 版占用的 8100）
#   pwsh -File scripts\verify-e2e.ps1 -Port 8102 -SkipBuild
#
# 依赖：JDK 17（默认取 E:\env\jdk\jdk-17）、Maven、以及能访问平台网关（10.254.208.35:8090）。
param(
    [int]$Port = 8101,
    [switch]$SkipBuild,
    [string]$SamplesDir = "..\ai-service\work\samples"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

if (-not $env:JAVA_HOME -and (Test-Path "E:\env\jdk\jdk-17")) {
    $env:JAVA_HOME = "E:\env\jdk\jdk-17"
}
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
$base = "http://127.0.0.1:$Port"
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8

if (-not $SkipBuild) {
    Write-Host "== 构建 ==" -ForegroundColor Cyan
    & mvn -B -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "构建失败" }
}

$jar = (Get-ChildItem "target\pm-ai-backend-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
if (-not $jar) { throw "没找到 jar，先执行 mvn package" }

Write-Host "== 启动服务（$base）==" -ForegroundColor Cyan
$log = "target\e2e-run.log"
$proc = Start-Process -FilePath $java -ArgumentList "-jar", "`"$jar`"", "--server.port=$Port" `
    -PassThru -RedirectStandardOutput $log -RedirectStandardError "target\e2e-run.err.log"

try {
    $ready = $false
    foreach ($i in 1..40) {
        Start-Sleep -Milliseconds 700
        try {
            Invoke-RestMethod "$base/health?with_ocr=false" -TimeoutSec 3 | Out-Null
            $ready = $true; break
        } catch { }
    }
    if (-not $ready) {
        Write-Host "服务未就绪，日志尾部：" -ForegroundColor Red
        Get-Content $log -Tail 30 -ErrorAction SilentlyContinue
        throw "服务启动失败"
    }

    Write-Host "`n== 1) 自检：网关 + 模型 + OCR + 向量 ==" -ForegroundColor Cyan
    $health = Invoke-RestMethod "$base/health?with_ocr=true&with_vec=true" -TimeoutSec 60
    $health | ConvertTo-Json -Depth 6

    $scanned = Get-ChildItem $SamplesDir -Filter "*扫描件*.pdf" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $textPdf = Get-ChildItem $SamplesDir -Filter "*电子版*.pdf" -ErrorAction SilentlyContinue |
        Select-Object -First 1

    if ($scanned) {
        Write-Host "`n== 2) 平台 OCR（扫描件：$($scanned.Name)）==" -ForegroundColor Cyan
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $ocr = Invoke-RestMethod "$base/ocr/file" -Method Post -Form @{ file = $scanned } -TimeoutSec 600
        $sw.Stop()
        Write-Host ("pages={0} kind={1} engine={2} elapsed={3}s 墙钟={4:N2}s" -f `
            $ocr.data.pages, $ocr.data.kind, $ocr.data.engine, $ocr.data.elapsed, $sw.Elapsed.TotalSeconds)
        Write-Host "failed_pages=$($ocr.data.failed_pages -join ',') notes=$($ocr.data.notes -join ' | ')"
        Write-Host "文本前 200 字：$($ocr.data.text.Substring(0, [Math]::Min(200, $ocr.data.text.Length)))"
    }

    if ($textPdf) {
        Write-Host "`n== 3) 抽取（文本型 PDF：$($textPdf.Name)）==" -ForegroundColor Cyan
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $an = Invoke-RestMethod "$base/analyze" -Method Post -Form @{ file = $textPdf } -TimeoutSec 900
        $sw.Stop()
        Write-Host ("kind={0} pages={1} 墙钟={2:N2}s llm.ok={3} tokens={4}+{5}" -f `
            $an.data.doc.kind, $an.data.doc.pages, $sw.Elapsed.TotalSeconds, `
            $an.data.llm.ok, $an.data.llm.prompt_tokens, $an.data.llm.completion_tokens)
        Write-Host "markdown 前 300 字：`n$($an.data.markdown.Substring(0, [Math]::Min(300, $an.data.markdown.Length)))"
    }

    Write-Host "`n== 4) 上传任务（先返回、后台解析）==" -ForegroundColor Cyan
    $target = if ($scanned) { $scanned } else { $textPdf }
    if ($target) {
        $task = Invoke-RestMethod "$base/upload-tasks" -Method Post -Form @{ file = $target } -TimeoutSec 120
        $id = $task.data.task_id
        Write-Host "task_id=$id 首次返回 status=$($task.data.status) percent=$($task.data.percent)"
        foreach ($i in 1..120) {
            Start-Sleep -Seconds 1
            $detail = Invoke-RestMethod "$base/upload-tasks/$id" -TimeoutSec 30
            if ($detail.data.status -in @("DONE", "FAILED", "CANCELLED")) {
                Write-Host ("status={0} percent={1} elapsed={2}s doc_id={3} error={4}" -f `
                    $detail.data.status, $detail.data.percent, $detail.data.elapsed, `
                    $detail.data.doc_id, $detail.data.error)
                break
            }
            if ($i % 5 -eq 0) { Write-Host "  ...$($detail.data.stage) $($detail.data.percent)%" }
        }
    }

    Write-Host "`n== 5) 问答（走检索 → 读页 → 计算）==" -ForegroundColor Cyan
    $body = @{ question = "中标金额是多少？付款方式是怎么约定的？" } | ConvertTo-Json
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $chat = Invoke-RestMethod "$base/chat" -Method Post -ContentType "application/json; charset=utf-8" `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 900
    $sw.Stop()
    Write-Host ("rounds={0} tokens={1}+{2} 墙钟={3:N2}s" -f `
        $chat.data.rounds, $chat.data.prompt_tokens, $chat.data.completion_tokens, $sw.Elapsed.TotalSeconds)
    if ($chat.data.trace) {
        Write-Host "工具调用轨迹："
        $chat.data.trace | ForEach-Object { Write-Host ("  [{0}] {1} {2} ({3:N2}s){4}" -f `
            $_.round, $_.name, ($_.arguments | ConvertTo-Json -Compress), $_.elapsed, $(if ($_.is_error) { " ERROR" } else { "" })) }
    }
    Write-Host "答案：`n$($chat.data.answer)"

    Write-Host "`n== 验收基线（Python 版实测，供对比）==" -ForegroundColor Cyan
    Write-Host "  向量化 4096 维；余弦 0.3245；语义 0.7605 > 0.2133；重排 0.9903 > 0.0278 > 0.0014"
    Write-Host "  扫描件平台 OCR 1 页约 1.56s；检索 hybrid+已重排、约 0.31s、命中带页码"
}
finally {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Write-Host "`n服务已停止。" -ForegroundColor DarkGray
    }
}
