@echo off
chcp 65001 >nul
title 项目管理系统 - 一键启动
setlocal enabledelayedexpansion

set ROOT=%~dp0
set BACKEND_PORT=8080
set FRONT_PORT=5173
set MYSQL_PORT=3306

echo ============================================================
echo   政府信息化项目管理系统 一键启动
echo   步骤：MySQL(3306) -^> 后端(8080) -^> 前端(5173) -^> 打开浏览器
echo ============================================================
echo.

rem ---------- 1. MySQL ----------
set MYSQL_UP=
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%MYSQL_PORT% .*LISTENING"') do set MYSQL_UP=%%a
if defined MYSQL_UP (
  echo [1/4] MySQL 已在运行 (端口 %MYSQL_PORT%)
) else (
  echo [1/4] 正在启动 MySQL ...
  if exist "E:\work\env\mysql\start-mysql.cmd" (
    call "E:\work\env\mysql\start-mysql.cmd"
  ) else (
    echo   未找到 MySQL 启动脚本 E:\work\env\mysql\start-mysql.cmd，请手动启动后重试。
    pause
    exit /b 1
  )
  set /a TRIES=0
  :wait_mysql
  set /a TRIES+=1
  timeout /t 1 /nobreak >nul
  set MYSQL_UP=
  for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%MYSQL_PORT% .*LISTENING"') do set MYSQL_UP=%%a
  if not defined MYSQL_UP if %TRIES% LSS 20 goto wait_mysql
  if not defined MYSQL_UP (
    echo   MySQL 启动超时，请查看 E:\work\env\mysql\start-mysql.cmd 输出。
    pause
    exit /b 1
  )
  echo   MySQL 已就绪
)
echo.

rem ---------- 2. 后端 ----------
set BACK_UP=
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%BACKEND_PORT% .*LISTENING"') do set BACK_UP=%%a
if defined BACK_UP (
  echo [2/4] 后端已在运行 (端口 %BACKEND_PORT%)
) else (
  echo [2/4] 启动后端 Spring Boot ...
  start "pm-backend" cmd /k "cd /d %ROOT%backend && E:\work\env\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run"
  set /a TRIES=0
  :wait_back
  set /a TRIES+=1
  timeout /t 2 /nobreak >nul
  set BACK_UP=
  for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%BACKEND_PORT% .*LISTENING"') do set BACK_UP=%%a
  if not defined BACK_UP if %TRIES% LSS 45 goto wait_back
  if defined BACK_UP (
    echo   后端已就绪 (http://127.0.0.1:%BACKEND_PORT%/api/health)
  ) else (
    echo   后端启动超时，请查看“pm-backend”窗口日志（首次会执行 Flyway 迁移并下载依赖）。
    pause
    exit /b 1
  )
)
echo.

rem ---------- 3. 前端 ----------
set FRONT_UP=
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%FRONT_PORT% .*LISTENING"') do set FRONT_UP=%%a
if defined FRONT_UP (
  echo [3/4] 前端已在运行 (端口 %FRONT_PORT%)
) else (
  echo [3/4] 启动前端 Vite ...
  if not exist "%ROOT%frontend\node_modules" (
    echo   首次运行，先安装前端依赖，请稍候 ...
    pushd "%ROOT%frontend"
    call npm.cmd install
    popd
  )
  start "pm-frontend" cmd /k "cd /d %ROOT%frontend && npm.cmd run dev"
  set /a TRIES=0
  :wait_front
  set /a TRIES+=1
  timeout /t 1 /nobreak >nul
  set FRONT_UP=
  for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:":%FRONT_PORT% .*LISTENING"') do set FRONT_UP=%%a
  if not defined FRONT_UP if %TRIES% LSS 30 goto wait_front
  if defined FRONT_UP (
    echo   前端已就绪 (http://localhost:%FRONT_PORT%)
  ) else (
    echo   前端启动超时，请查看“pm-frontend”窗口日志。
    pause
    exit /b 1
  )
)
echo.

rem ---------- 4. 打开浏览器 ----------
echo [4/4] 打开浏览器 http://localhost:%FRONT_PORT%
start "" "http://localhost:%FRONT_PORT%"
echo.
echo ============================================================
echo   启动完成！
echo   访问地址:  http://localhost:%FRONT_PORT%
echo   登录账号:  admin / 123456   (经办 jingban01 / 领导 lingdao01)
echo   关闭方式:  运行 stop-dev.cmd，或直接关闭对应窗口
echo ============================================================
pause
endlocal
