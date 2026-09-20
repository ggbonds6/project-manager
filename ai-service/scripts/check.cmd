@echo off
rem ---------------------------------------------------------------------------
rem PM AI Service - quality gate (native Windows entry: ruff + pytest)
rem
rem Usage (run from anywhere; the script cd's to ai-service itself):
rem   scripts\check.cmd                       all: ruff check + format --check + pytest
rem   scripts\check.cmd tests\test_tools.py   single file (extra args go to pytest)
rem   scripts\check.cmd -k calculate -x       any pytest args
rem
rem Interpreter lookup order (same as scripts/check.sh):
rem   1) PY env var   2) E:\env\venvs\pm-ai   3) .venv   4) python on PATH
rem
rem NOTE: this file is intentionally **ASCII only**. cmd.exe reads .cmd in the
rem system ANSI codepage (GBK on zh-CN), so UTF-8 Chinese comments here get
rem mangled and break parsing (verified: "is not recognized as an internal or
rem external command"). Chinese docs live in README section 15 and check.sh.
rem
rem First-time setup (once, Python lives in E:\env together with JDK/Maven):
rem   E:\env\python-3.11.9\python.exe -m venv E:\env\venvs\pm-ai
rem   E:\env\venvs\pm-ai\Scripts\python.exe -m pip install -e ".[dev]"
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0.."

set "PY_BIN=%PY%"
if not defined PY_BIN if exist "E:\env\venvs\pm-ai\Scripts\python.exe" set "PY_BIN=E:\env\venvs\pm-ai\Scripts\python.exe"
if not defined PY_BIN if exist ".venv\Scripts\python.exe" set "PY_BIN=.venv\Scripts\python.exe"
if not defined PY_BIN for %%P in (python.exe) do if not defined PY_BIN set "PY_BIN=%%~$PATH:P"

if not defined PY_BIN (
  echo [ERROR] No Python interpreter found.
  echo   One-time setup ^(Python is kept in E:\env^):
  echo     E:\env\python-3.11.9\python.exe -m venv E:\env\venvs\pm-ai
  echo     E:\env\venvs\pm-ai\Scripts\python.exe -m pip install -e ".[dev]"
  echo   Or run the Docker fallback: bash scripts/check.sh
  exit /b 1
)

echo -- quality gate: %PY_BIN% --
echo.
"%PY_BIN%" -m ruff check . || exit /b 1
"%PY_BIN%" -m ruff format . --check || exit /b 1
"%PY_BIN%" -m pytest %* || exit /b 1
echo.
echo [OK] ruff check + ruff format --check + pytest
endlocal
