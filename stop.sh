#!/usr/bin/env bash
#
# stop.sh — gracefully stop the Halo process started by start.sh.
#
# Usage:
#   ./stop.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="halo"
PID_FILE="$SCRIPT_DIR/${APP_NAME}.pid"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"

if [[ ! -f "$PID_FILE" ]]; then
  echo "[stop] No PID file ($PID_FILE); $APP_NAME does not appear to be running."
  exit 0
fi

PID="$(cat "$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then
  echo "[stop] Process $PID is not running; removing stale PID file."
  rm -f "$PID_FILE"
  exit 0
fi

echo "[stop] Stopping $APP_NAME (PID $PID), waiting up to ${STOP_TIMEOUT}s..."
kill "$PID"

for _ in $(seq 1 "$STOP_TIMEOUT"); do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "[stop] Stopped gracefully."
    rm -f "$PID_FILE"
    exit 0
  fi
  sleep 1
done

echo "[stop] Graceful shutdown timed out; sending SIGKILL."
kill -9 "$PID" 2>/dev/null || true
rm -f "$PID_FILE"
echo "[stop] Force stopped."
