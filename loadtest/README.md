# Pruebas de carga (RNF-02 y RNF-03)

Demuestra con numeros que una replica con proveedor `simulated` acepta con p95 <= 200 ms (RNF-02) y despacha >= 500 notificaciones por minuto (RNF-03). La prueba es una herramienta aparte del producto: vive fuera de los modulos Maven.

## Alcance

- Una sola replica con proveedor simulado. No demuestra rendimiento con proveedores reales ni con varias replicas.
- La app corre como jar en el host, con MongoDB y RabbitMQ del `docker-compose.yml`. Prometheus solo raspa `host.docker.internal:8061`, asi que con la app en contenedor no hay medicion de despacho (haria falta una red compartida con Prometheus, fuera de alcance).
- La guarda de entorno es declarativa: ningun endpoint revela el proveedor activo. Que el simulado no se use fuera de desarrollo lo garantiza `simulated.enabled` junto al catalogo real.

## Prerrequisitos

- Node 20 o superior. Tambien lo exigen `verify` (pruebas `LoadTestVerdictE2ETest` y de `veredicto.js`) y el job nocturno; no hay respaldo en Java.
- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) solo para ejecutar la carga real (no lo necesita `verify`).
- Prometheus con remote-write: `docker compose -f docker-compose.observability.yml up -d prometheus` (arranca con `--web.enable-remote-write-receiver`).

## Ejecucion local

1. `docker compose up -d mongodb rabbitmq` y el stack de observabilidad.
2. Genera un par RSA efimero y un Bearer: `node loadtest/token.js` imprime `{"publicKey","bearer"}`. Arranca el servicio con `AUTH_PLATFORM_PUBLIC_KEY=<publicKey>`, `SPRING_PROFILES_ACTIVE=local` y `SIMULATED_PROVIDER_RESULT=ACCEPTED`. (Alternativa en PowerShell 7: `scripts/dev-token.ps1`.)
3. Exporta `LOADTEST_BEARER=<bearer>` (el JWT sin el prefijo `Bearer `) y `LOADTEST_CONFIRM_SIMULATED=true`.
4. `./loadtest/run.sh` (Bash) o `./loadtest/run.ps1` (PowerShell). Escribe `loadtest/resultado.json` y termina con codigo distinto de 0 si algun umbral falla.

## Variables

| Variable | Defecto | Uso |
|---|---|---|
| `LOADTEST_CONFIRM_SIMULATED` | obligatoria | debe ser `true` |
| `LOADTEST_BEARER` o `LOADTEST_COOKIE` | obligatoria una | credencial; nunca se imprime ni se guarda |
| `LOADTEST_BASE_URL` | `http://localhost:8060` | otro host exige `LOADTEST_ALLOW_REMOTE=true` |
| `PROMETHEUS_URL` | `http://localhost:9090` | consulta del despacho |
| `K6_PROMETHEUS_RW_SERVER_URL` | `http://localhost:9090/api/v1/write` | destino de las series `k6_*` |
| `LOADTEST_RATE_PER_MINUTE` | 700 | tasa de llegada constante |
| `LOADTEST_WINDOW_MINUTES` / `LOADTEST_WARMUP_MINUTES` | 5 / 1 | ventana medida y calentamiento |
| `LOADTEST_DRAIN_SECONDS` | 45 | espera de drenaje (2 scrapes de 15 s mas margen) |
| `LOADTEST_RESULT_FILE` | `resultado.json` | archivo de salida |

## Que se mide

- p95 de `POST /notifications` y ratio de 202 en el cliente, solo en la ventana medida.
- Despacho: incremento de `notification_dispatched_total{result="delivered",provider="simulated"}` desde el inicio de la ventana medida hasta el fin del drenaje, dividido por los minutos de la ventana. Un reinicio del contador invalida la ejecucion.
- Los umbrales viven en `veredicto.js` y se comparan con el tablero en `LoadTestDashboardCoherenceTest`. Las series `k6_notificaciones_aceptacion_seconds_p95` y `k6_notificaciones_despachadas_por_minuto` alimentan los paneles "Ultima prueba de carga".

## Pruebas

`cd loadtest && node --test` prueba la logica de veredicto, la guarda y la ausencia de secretos.

## CI

`.github/workflows/carga-nocturna.yml` corre cada dia a las 06:00 UTC y con `workflow_dispatch`; publica `resultado.json` como artefacto 90 dias. Ante rojo repite una vez y se considera regresion solo si las dos ejecuciones concuerdan. No bloquea PR ni `verify`.

## Repetirla al cerrar niveles

Lanza el workflow con `workflow_dispatch` sobre el commit de cierre y compara `resultado.json` con el anterior (mismo veredicto y tasas dentro de +-10 %).
