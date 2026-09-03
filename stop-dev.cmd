@echo off
chcp 65001 >nul
title 项目管理系统 - 停止服务
echo 正在停止项目管理系统相关进程 (按端口查找) ...
echo.

setlocal enabledelayedexpansion
for %%P in (8080 5173) do (
  for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%%P .*LISTENING"') do (
    set PID=%%a
    if not "!PID!"=="" (
      echo 端口 %%P (PID !PID!): 结束进程
      taskkill /PID !PID! /F >nul 2>&1
    )
  )
)

rem 提示：MySQL(3306) 默认保留不停止；如需停止请运行 E:\work\env\mysql\stop-mysql.cmd
echo.
echo 后端与前端进程已结束。
echo 提示：MySQL(3306) 未停止；如需一并停止请运行 E:\work\env\mysql\stop-mysql.cmd
pause
endlocal
