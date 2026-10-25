$ErrorActionPreference = 'Stop'

Set-Location $PSScriptRoot

if (-not $env:K6_PROMETHEUS_RW_SERVER_URL) { $env:K6_PROMETHEUS_RW_SERVER_URL = 'http://localhost:9090/api/v1/write' }
$env:K6_PROMETHEUS_RW_TREND_STATS = 'p(95),avg'
if (-not $env:LOADTEST_COMMIT) {
    $env:LOADTEST_COMMIT = (git rev-parse --short HEAD 2>$null)
    if (-not $env:LOADTEST_COMMIT) { $env:LOADTEST_COMMIT = 'desconocido' }
}

k6 run -o experimental-prometheus-rw notificaciones.js
exit $LASTEXITCODE
