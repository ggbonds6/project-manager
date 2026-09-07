#!/usr/bin/env bash
# ============================================================
# [Linux] Stop backend/frontend started by start-dev.sh.
# MySQL is NOT stopped.
# ============================================================
set -u
PID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.pids"
stop_pid() {
  local f="$PID_DIR/$1.pid"
  if [ -f "$f" ]; then
    local pid
    pid="$(cat "$f")"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      echo "stopping $1 (pid $pid) ..."
      kill "$pid" 2>/dev/null
    fi
    rm -f "$f"
  fi
}
stop_pid backend
stop_pid frontend
# fallback: kill anything still listening on 8080/5173
for port in 8080 5173; do
  pids="$( (ss -ltnp 2>/dev/null | grep ":$port " || true) | grep -oP '(?<=pid=)\d+' || true)"
  for pid in $pids; do
    echo "force killing pid $pid on port $port"
    kill -9 "$pid" 2>/dev/null || true
  done
done
echo "Done. MySQL is kept running."
