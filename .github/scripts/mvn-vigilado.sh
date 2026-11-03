#!/usr/bin/env bash
set -uo pipefail

IDLE_SECONDS="${IDLE_SECONDS:-300}"
DIAG_DIR="${DIAG_DIR:-vigilado-diag}"
LOG="$(mktemp)"
mkdir -p "$DIAG_DIR"

if command -v setsid >/dev/null; then
  setsid "$@" >"$LOG" 2>&1 &
else
  "$@" >"$LOG" 2>&1 &
fi
COMMAND_PID=$!
tail -n +1 -f "$LOG" &
TAIL_PID=$!

diagnose() {
  local out
  out="$DIAG_DIR/diagnostico-$(date +%H%M%S).txt"
  {
    echo "::error::Sin salida durante ${IDLE_SECONDS}s: se vuelca el diagnostico y se corta la ejecucion"
    echo "=== memoria ==="
    timeout 10 free -m || true
    echo "=== procesos por CPU ==="
    timeout 10 ps -eo pid,pcpu,pmem,etime,args --sort=-pcpu | head -15 || true
    echo "=== contenedores ==="
    timeout 20 docker ps -a --format '{{.Names}} {{.Image}} {{.Status}}' || true
    echo "=== hilos de las JVM del comando ==="
    for pid in $(pgrep -g "$COMMAND_PID" -x java || true); do
      echo "--- jcmd $pid Thread.print ---"
      timeout 30 jcmd "$pid" Thread.print 2>&1 | head -2000 || true
    done
  } 2>&1 | tee "$out"
  sync
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
if ((STATUS != 0)); then
  gzip -c "$LOG" >"$DIAG_DIR/salida-$(date +%H%M%S).log.gz"
  echo "=== resumen de fallos (salida $STATUS) ==="
  grep -E '^\[(ERROR|WARNING)\]|<<< (FAILURE|ERROR)|Tests run:.*(Failures: [1-9]|Errors: [1-9])|BUILD' "$LOG" | tail -80
fi
exit "$STATUS"
