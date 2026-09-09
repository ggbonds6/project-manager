@echo off
rem ============================================================
rem  [Windows] Stop backend(8080) and frontend(5173) by port.
rem  崖山 YashanDB 为外部库，本脚本不触碰。
rem ============================================================
chcp 65001 >nul
title PM Stop
setlocal enabledelayedexpansion
echo Stopping backend(8080) / frontend(5173) ...
for %%P in (8080 5173) do (
  for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%%P .*LISTENING"') do (
    set PID=%%a
    if not "!PID!"=="" (
      echo   port %%P pid !PID! - taskkill
      taskkill /PID !PID! /F >nul 2>&1
    )
  )
)
echo Done. YashanDB is kept running.
endlocal
