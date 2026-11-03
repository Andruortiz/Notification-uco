# Implementation Plan: Panel de salud de proveedores (037)

**Spec**: [spec.md](spec.md) | **Rama**: `feature/HU2-121-panel-salud-proveedores` | Se implementa después de E3.

## Resumen

Un caso de uso en `core` pide a un puerto de salida las series de Prometheus, calcula estado/tasa/latencia/último error y devuelve una lista. Un adaptador WebClient en `infrastructure` implementa el puerto contra la HTTP API de Prometheus. Un controller expone `GET /provider-health`. Sin métricas nuevas ni persistencia.

## Decisiones

- D1 (Q2, FR-003): ventana W configurable (defecto 5 min), por `provider,channel`:
  - `d = sum by (provider,channel)(increase(notification_dispatched_total{result="delivered"}[W]))`, `f = ... {result="failed"}`, `r = ... {result="recoverable"}`.
  - tasa = `d / (d + f)`; volumen = `d + f`. Los `recoverable` son reintentos y no entran al denominador (evita mezclar reintentos con envíos); `r / (d + f + r)` ≥ `recoverableThreshold` (defecto 0,5) fuerza `DEGRADED`.
  - latencia: `histogram_quantile(0.95, sum by (le,provider)(rate(notification_provider_duration_seconds_bucket[W])))`, solo por proveedor; se repite en cada canal del proveedor.
- D2 (Q4, hallazgos 1 y 5): último error con `query_range` de `sum by (provider,channel,result)(increase(notification_dispatched_total{result=~"recoverable|failed"}[1m]))`, paso 60 s, `lookbackSeconds` (defecto 900 s, no 3600) para acotar costo: 15 puntos por serie, cardinalidad = proveedores x canales x 2 (acotada por catálogo, decenas de series). Se toma el último paso > 0 por proveedor/canal; si hay `failed` y `recoverable` en el mismo paso, prevalece `failed`. `code`/`category` permanecen nulos hasta que se instrumenten errores por proveedor. `notification_errors_total` no se consulta: sus errores con `provider=none` no se atribuyen a ningún proveedor y los que llevan proveedor (p. ej. `DISPATCH_EVENTS_NOT_PUBLISHED`) no son fallos del proveedor.
- D3 (hallazgo 3): `ProviderDisabledException` cuenta como `failed` y la métrica no lo distingue. Sin cambiar 020, un proveedor desactivado con tráfico saldría `DOWN`; se acepta y se documenta en el contrato como límite (A6, Equipo de backend, 2026-12-15). Un proveedor sin tráfico en la ventana es `UNKNOWN` o no aparece (A2).
- D4 (FR-003): `UNKNOWN` si volumen < `minSamples` (defecto 5), con precedencia sobre la regla de `recoverable`; `HEALTHY` tasa ≥ 0,95; `DEGRADED` 0,5 ≤ tasa < 0,95 o regla de recoverable; `DOWN` tasa < 0,5. Umbrales en `provider-health.*` de `application.yml` (solo claves nuevas).
- D5 (FR-009, hallazgo 6): PromQL de plantillas fijas; la ventana es entero acotado; el endpoint no recibe parámetros. Los `provider` y `channel` devueltos se validan con el patrón de FR-009 en el servicio antes de armar la respuesta o la URL; los inválidos se descartan. `PROMETHEUS_URL` solo por `ProviderHealthProperties`; el adaptador captura sus errores y no los propaga con la URL ni los registra con ella.
- D6 (FR-006/007, hallazgo 8): el rol `OPERADOR` se aplica con la regla `GET /provider-health` en `RouteAuthorizationPolicy`; la sesión del PDP autentica, el controller no usa `tenantId`. El enmascarado queda sin objeto; la prueba de seguridad lo afirma.
- D7 (FR-008, hallazgo 4): el dashboard 021 (`uid: notification-service`) solo tiene la variable `instance`. Se elige no tocar el JSON de 021: `grafanaUrl` = `GRAFANA_URL` + `/d/notification-service` y no lleva parámetro de proveedor. El proveedor se codifica en la URL solo como filtro de tiempo si se necesita; en esta versión la URL es única para todo el panel y la UI no añade `var-provider`. Alternativa fuera de alcance: agregar la variable `provider` al JSON de 021 (`observability/grafana/dashboards/*.json`) en otra historia.
- D8 (Q4, hallazgo 7): el 503 usa `INFRASTRUCTURE_ERROR` (5000). Se descarta `AUTHENTICATION_UNAVAILABLE` (2003).
- D9 (Q6): esquema extensible: `status` incluye `CIRCUIT_OPEN` reservado (no lo produce esta historia) y `failedOver` queda como campo opcional futuro; ambos son aditivos y no rompen consumidores.

## Cambios de contrato requeridos (los lista Contrato; no se edita `api-notificaciones.yaml` aquí)

- `GET /provider-health`, `x-minimum-role: OPERADOR`, `x-status: available`, sin parámetros, 200 `ProviderHealthResponse`; 401, 403, 503 con `ErrorResponse`.
- `ProviderHealthResponse`: `{ generatedAt, windowSeconds, grafanaUrl|null, providers: [ProviderHealth] }`.
- `ProviderHealth`: `{ provider, channel, status: HEALTHY|DEGRADED|DOWN|UNKNOWN (CIRCUIT_OPEN reservado), successRate|null (0..1), sampleCount, latencyP95Millis|null (por proveedor, repetida por canal), lastError: { result: recoverable|failed, at, code?, category? } | null, failedOver? }`.
- Límites documentados: con el circuito de E3 abierto el proveedor puede salir `UNKNOWN` o ausente y no `DOWN`, porque no recibe intentos (límite conocido, Equipo de backend, 2026-12-15; Contrato debe citarlo en la descripción de la operación); proveedor desactivado con tráfico cuenta como `failed`; retraso del scrape (15 s) más la ventana; proveedores sin tráfico no aparecen.

Consumo por la UI (repo aparte): tabla que sondea cada 15 s, colorea `status`, abre `grafanaUrl` en pestaña nueva.

## Estructura

```text
core/.../port/in/GetProviderHealthUseCase.java
core/.../port/out/ProviderMetricsQueryPort.java
core/.../usecase/GetProviderHealthService.java
core/.../domain/valueobject/ProviderHealth*.java
infrastructure/.../adapter/out/metrics/PrometheusProviderMetricsAdapter.java
infrastructure/.../adapter/in/web/ProviderHealthController.java
infrastructure/.../config/ProviderHealthProperties.java
infrastructure/src/main/resources/application.yml   (provider-health.*)
```

Wiring en `UseCaseConfig`, la regla en `RouteAuthorizationPolicy` y `pom.xml`: fuera de esta spec; los integra quien corresponda.

## Pruebas (Principio IV)

- Unitaria `core` (StepVerifier): estados por umbral, `UNKNOWN` sin volumen, exclusión de `recoverable` del denominador y regla de degradación, `failed` prevalece como último error, descarte de `provider` inválido.
- Integración del adaptador: Testcontainers con Prometheus real alimentado con métricas expuestas; sin mocks de Prometheus.
- E2E `ProviderHealthE2ETest` (`RANDOM_PORT` + Mongo + RabbitMQ + Prometheus): envíos con proveedor simulado `ACCEPTED` y luego `PERMANENT_FAILURE`; afirma tasa, `DOWN`, `lastError.result = failed`, 403 para `CLIENTE`, 503 con Prometheus detenido (control positivo previo con 200, y el cuerpo no contiene la URL de Prometheus), y `Duration` ≤ 2 s.
- Seguridad: fallo cuyo mensaje incluye correo y token; la respuesta serializada no los contiene. Dos tenants distintos obtienen la misma respuesta global.
- `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Riesgos

- Retraso de scrape (15 s) más ventana.
- Proveedor desactivado con tráfico aparece `DOWN` (D3).
- Sin tenant en las métricas (A1).
- `lastError` sin código hasta instrumentar errores por proveedor (A6).
- Depende de E3: el catálogo real de proveedores y las etiquetas pueden cambiar; revisar al implementar.
