#!/usr/bin/env bash
set -uo pipefail

IDLE_SECONDS="${IDLE_SECONDS:-300}"
LOG="$(mktemp)"

if command -v setsid >/dev/null; then
  setsid "$@" >"$LOG" 2>&1 &
else
  "$@" >"$LOG" 2>&1 &
fi
COMMAND_PID=$!
tail -n +1 -f "$LOG" &
TAIL_PID=$!

diagnose() {
  echo "::error::Sin salida durante ${IDLE_SECONDS}s: se vuelca el diagnostico y se corta la ejecucion"
  echo "=== memoria ==="
  free -m || true
  echo "=== contenedores ==="
  docker ps -a --format '{{.Names}} {{.Image}} {{.Status}}' || true
  echo "=== hilos de las JVM del comando ==="
  for pid in $(pgrep -g "$COMMAND_PID" -x java || true); do
    echo "--- jcmd $pid Thread.print ---"
    jcmd "$pid" Thread.print 2>&1 | head -600 || true
  done
}

while kill -0 "$COMMAND_PID" 2>/dev/null; do
  sleep 15
  idle=$(($(date +%s) - $(stat -c %Y "$LOG")))
  if ((idle > IDLE_SECONDS)); then
    diagnose
    kill -KILL -- "-$COMMAND_PID" 2>/dev/null || kill -KILL "$COMMAND_PID" 2>/dev/null || true
    kill "$TAIL_PID" 2>/dev/null || true
    exit 124
  fi
done

wait "$COMMAND_PID"
STATUS=$?
sleep 1
kill "$TAIL_PID" 2>/dev/null || true
exit "$STATUS"
