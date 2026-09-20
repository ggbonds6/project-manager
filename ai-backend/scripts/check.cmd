@echo off
rem ---------------------------------------------------------------------------
rem PM AI Backend (Java) - quality gate (native Windows entry)
rem
rem Usage (run from anywhere; the script cd's to ai-backend itself):
rem   scripts\check.cmd                 compile + unit tests
rem   scripts\check.cmd package         also build the fat jar
rem
rem Requires JDK 17 and Maven on PATH. This machine keeps them in E:\env:
rem   JAVA_HOME=E:\env\jdk\jdk-17   Maven=E:\env\apache-maven-3.9.9
rem
rem NOTE: ASCII only on purpose - cmd.exe reads .cmd in the system ANSI codepage.
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0.."

if "%JAVA_HOME%"=="" if exist "E:\env\jdk\jdk-17" set "JAVA_HOME=E:\env\jdk\jdk-17"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"

echo -- AI backend quality gate: compile + unit tests --
call mvn -B test || exit /b 1

if /i "%~1"=="package" (
  echo.
  echo -- building fat jar --
  call mvn -B -DskipTests package || exit /b 1
)

echo.
echo [OK] ai-backend: mvn test passed
endlocal
