#!/usr/bin/env bash
# ============================================================
# [Linux] One-click start (generic, no machine-specific paths)
# Requirements: Java 17 + Maven 3.6+ (JAVA_HOME or PATH),
#               Node 18+ / npm, MySQL 8 running or MYSQL_START_CMD env.
# Usage: bash deploy/linux/start-dev.sh
# PIDs/logs stored under deploy/linux/.pids
# ============================================================
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.pids"
mkdir -p "$PID_DIR"

B_PORT=8080
F_PORT=5173

port_listen() { (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ":$1 "; }

echo "=== 1/3 MySQL (3306) ==="
if port_listen 3306; then
  echo "  [OK] MySQL already running"
elif [ -n "${MYSQL_START_CMD:-}" ]; then
  echo "  starting MySQL via MYSQL_START_CMD ..."
  eval "$MYSQL_START_CMD"
  for i in $(seq 1 30); do port_listen 3306 && break; sleep 1; done
  port_listen 3306 || { echo "  [FAIL] MySQL not ready"; exit 1; }
  echo "  [OK] MySQL ready"
else
  echo "  [HINT] MySQL not running. Start it first or export MYSQL_START_CMD."
  exit 1
fi

echo ""
echo "=== 2/3 Backend (8080) ==="
if port_listen $B_PORT; then
  echo "  [OK] Backend already running"
else
  command -v mvn >/dev/null 2>&1 || { echo "  [FAIL] mvn not found (install Maven or set JAVA_HOME/PATH)"; exit 1; }
  echo "  starting Spring Boot ..."
  (cd "$ROOT/backend" && nohup mvn spring-boot:run > "$PID_DIR/backend.log" 2>&1 & echo $! > "$PID_DIR/backend.pid")
  for i in $(seq 1 120); do
    curl -fsS "http://127.0.0.1:$B_PORT/api/health" >/dev/null 2>&1 && break
    sleep 2
  done
  port_listen $B_PORT || { echo "  [FAIL] Backend start timeout, see $PID_DIR/backend.log"; exit 1; }
  echo "  [OK] Backend ready"
fi

echo ""
echo "=== 3/3 Frontend (5173) ==="
if port_listen $F_PORT; then
  echo "  [OK] Frontend already running"
else
  command -v npm >/dev/null 2>&1 || { echo "  [FAIL] npm not found"; exit 1; }
  [ -d "$ROOT/frontend/node_modules" ] || { echo "  installing frontend deps ..."; (cd "$ROOT/frontend" && npm install); }
  echo "  starting Vite (--host for LAN) ..."
  (cd "$ROOT/frontend" && nohup npm run dev -- --host > "$PID_DIR/frontend.log" 2>&1 & echo $! > "$PID_DIR/frontend.pid")
  for i in $(seq 1 60); do port_listen $F_PORT && break; sleep 1; done
  port_listen $F_PORT || { echo "  [FAIL] Frontend start timeout, see $PID_DIR/frontend.log"; exit 1; }
  echo "  [OK] Frontend ready"
fi

echo ""
echo "Done. URL: http://localhost:$F_PORT  API: http://127.0.0.1:$B_PORT/api/health"
echo "Stop: bash deploy/linux/stop-dev.sh  (logs in $PID_DIR)"
