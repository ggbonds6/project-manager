@echo off
rem ============================================================
rem  [Windows] One-click start (generic, no machine-specific paths)
rem  Requirements: Java 17 + Maven 3.6+ on PATH (or JAVA_HOME set),
rem                Node 18+ / npm on PATH,
rem                崖山 YashanDB 主库端口 1688 可达；
rem                后端连接环境变量 YASHAN_MASTER_IP / YASHAN_STANDBY_IP /
rem                YASHAN_DB / YASHAN_USER / YASHAN_PASSWORD 需提前设置。
rem  Usage: deploy\windows\start-dev.cmd
rem ============================================================
chcp 65001 >nul
title PM Start (Windows generic)
setlocal enabledelayedexpansion

set "ROOT=%~dp0..\.."
if not exist "%ROOT%\backend\pom.xml" (
  echo [FAIL] repo root not found: %ROOT%
  exit /b 1
)

set "B_PORT=8080"
set "F_PORT=5173"
set "Y_PORT=1688"

rem ---------- 1. YashanDB 主库可达性检查 ----------
call :portBusy %Y_PORT%
if errorlevel 1 (
  echo [HINT] YashanDB primary not reachable on port %Y_PORT%.
  echo        Start YashanDB or check network ^(default 10.254.212.106:1688^).
  exit /b 1
)
echo [OK] YashanDB reachable ^(port %Y_PORT%^)
if not defined YASHAN_PASSWORD (
  echo [WARN] YASHAN_PASSWORD not set. Backend will fail to connect.
  echo        Set it first, e.g.:  set YASHAN_PASSWORD=xxxx
  echo        Also YASHAN_MASTER_IP/YASHAN_STANDBY_IP/YASHAN_DB/YASHAN_USER if not default.
)

rem ---------- 2. Backend ----------
call :portBusy %B_PORT%
if not errorlevel 1 (
  echo [OK] Backend already running
) else (
  mvn -v >nul 2>&1 || (
    echo [FAIL] mvn not found. Install Maven or set JAVA_HOME + PATH.
    exit /b 1
  )
  echo starting Spring Boot ...
  start "pm-backend" cmd /k "cd /d %ROOT%\backend && mvn spring-boot:run"
  call :waitPort %B_PORT% 180
  if errorlevel 1 (
    echo [FAIL] Backend start timeout. Check window "pm-backend".
    exit /b 1
  )
  echo [OK] Backend ready ^(http://127.0.0.1:%B_PORT%/api/health^)
)

rem ---------- 3. Frontend ----------
call :portBusy %F_PORT%
if not errorlevel 1 (
  echo [OK] Frontend already running
) else (
  if not exist "%ROOT%\frontend\node_modules" (
    echo installing frontend deps ...
    pushd "%ROOT%\frontend"
    call npm install
    popd
  )
  echo starting Vite ^(--host for LAN^) ...
  start "pm-frontend" cmd /k "cd /d %ROOT%\frontend && npm run dev -- --host"
  call :waitPort %F_PORT% 60
  if errorlevel 1 (
    echo [FAIL] Frontend start timeout. Check window "pm-frontend".
    exit /b 1
  )
  echo [OK] Frontend ready
)

echo.
echo ============================================================
echo   Done. URL: http://localhost:%F_PORT%
echo   API  : http://127.0.0.1:%B_PORT%/api/health
echo   Stop : deploy\windows\stop-dev.cmd
echo ============================================================
exit /b 0

rem ---------- subroutines ----------

:portBusy
set "PB_PORT=%~1"
set "PB_FOUND="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%PB_PORT% .*LISTENING"') do set "PB_FOUND=%%a"
if defined PB_FOUND exit /b 0
exit /b 1

:waitPort
set "WP_PORT=%~1"
set "WP_MAX=%~2"
set /a WP_N=0
:waitPortLoop
set /a WP_N+=1
set "WP_FOUND="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%WP_PORT% .*LISTENING"') do set "WP_FOUND=%%a"
if defined WP_FOUND exit /b 0
if %WP_N% GEQ %WP_MAX% exit /b 1
timeout /t 1 /nobreak >nul
goto waitPortLoop
