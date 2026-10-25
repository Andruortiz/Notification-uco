#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"
export K6_PROMETHEUS_RW_TREND_STATS="p(95),avg"
export LOADTEST_COMMIT="${LOADTEST_COMMIT:-$(git rev-parse --short HEAD 2>/dev/null || echo desconocido)}"

exec k6 run -o experimental-prometheus-rw notificaciones.js
