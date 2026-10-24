# Implementation Plan: Panel de salud de proveedores (037)

**Spec**: [spec.md](spec.md) | **Rama**: `feature/HU2-121-panel-salud-proveedores`

## Resumen

Una consulta de lectura: un caso de uso en `core` pide a un puerto de salida las series de Prometheus, calcula estado/tasa/latencia/último error y devuelve una lista. Un adaptador WebClient en `infrastructure` implementa el puerto contra la HTTP API de Prometheus. Un controller expone `GET /provider-health`. Sin métricas nuevas, sin persistencia.

## Decisiones

- D1 (Q1/Q2): consultas instantáneas sobre una ventana configurable (por defecto 5 min), agregadas por `provider, channel`:
  - tasa de éxito: `sum by (provider,channel)(increase(notification_dispatched_total{result="delivered"}[W])) / sum by (provider,channel)(increase(notification_dispatched_total[W]))`
  - latencia p95: `histogram_quantile(0.95, sum by (le,provider)(rate(notification_provider_duration_seconds_bucket[W])))`
  - volumen: denominador de la tasa.
- D2 (Q4, A4): último error con `query_range` de `sum by (provider,errorCode,failureCategory)(increase(notification_errors_total[1m]))` sobre `lookbackSeconds` (por defecto 3600 s); se elige el código con el último paso `> 0` por proveedor. `at` es ese paso. Código y categoría se validan contra el catálogo `ErrorCode` de `utils`; un valor desconocido se descarta, nunca se reenvía crudo.
- D3 (FR-003): `UNKNOWN` si volumen < `minSamples` (defecto 5); `HEALTHY` si tasa ≥ 0,95; `DEGRADED` si 0,5 ≤ tasa < 0,95; `DOWN` si tasa < 0,5. Umbrales en `provider-health.*` de `application.yml`, no sensibles y versionados.
- D4 (FR-009): el PromQL se arma con plantillas fijas; la ventana es entero positivo acotado y no se interpola ningún valor de la petición. El endpoint no recibe parámetros en esta versión.
- D5 (FR-006/007): rol `OPERADOR` registrado en `RouteAuthorizationPolicy` y en el PEP. El principal viene de `AuthenticatedPrincipalArgumentResolver`. La respuesta no contiene datos personales, así que el enmascarado no tiene efecto; la prueba lo afirma con un error cuyo texto incluye correo y token.
- D6 (FR-008): `grafanaUrl` = `GRAFANA_URL` + `/d/<uid>?var-provider=` (uid del tablero de 021), nulo si no hay variable. Tiempo límite de Prometheus 2 s (`timeout`), 503 con `ErrorCode` de infraestructura al fallar. Si el catálogo no tiene un código adecuado, se agrega uno nuevo sin reutilizar valores.
- D7 (Principio VII): el enmascarado por rol y la salud por tenant no existen aún en las métricas; se registran como excepción con dueño (Observabilidad) y revisión 2026-12-01 si se pide salud por tenant.

## Contrato (lo que lista Contrato; no se edita `api-notificaciones.yaml` aquí)

Endpoint nuevo, ruta literal:

- `GET /provider-health`, `x-minimum-role: OPERADOR`, `x-status: implemented`, sin parámetros, respuesta 200 `ProviderHealthResponse`; 401, 403, 503 con `ErrorResponse`.
- `ProviderHealthResponse`: `{ generatedAt, windowSeconds, grafanaUrl|null, providers: [ProviderHealth] }`.
- `ProviderHealth`: `{ provider, channel, status: HEALTHY|DEGRADED|DOWN|UNKNOWN, successRate|null (0..1), sampleCount, latencyP95Millis|null, lastError: { code: "NTF-n", category, at } | null }`.

Consumo por la UI (repo aparte): una tabla que sondea cada 15 s (igual al scrape), colorea `status`, abre `grafanaUrl` en pestaña nueva y muestra `lastError.code` con su etiqueta del catálogo; no interpreta texto libre.

## Estructura

```text
core/.../port/in/GetProviderHealthUseCase.java
core/.../port/out/ProviderMetricsQueryPort.java      (snapshot de series por proveedor)
core/.../usecase/GetProviderHealthService.java       (umbrales, estado, selección de último error)
core/.../domain/valueobject/ProviderHealth*.java     (records)
infrastructure/.../adapter/out/metrics/PrometheusProviderMetricsAdapter.java
infrastructure/.../adapter/in/web/ProviderHealthController.java  (+ DTO)
infrastructure/.../config/ProviderHealthProperties.java
infrastructure/src/main/resources/application.yml    (provider-health.*, solo claves nuevas)
```

El wiring en `UseCaseConfig` y la ruta en `RouteAuthorizationPolicy` los toca quien integra; se listan como dependencia de la tarea final. No se toca `pom.xml` (WebClient y Jackson ya están).

## Pruebas (Principio IV)

- Unitaria `core` (StepVerifier): estados por umbral, `UNKNOWN` sin volumen, selección de último error, descarte de código fuera del catálogo.
- Integración del adaptador: Testcontainers con Prometheus real (como 021) alimentado por métricas expuestas; sin mocks de Prometheus.
- E2E `ProviderHealthE2ETest` (`RANDOM_PORT` + Mongo + RabbitMQ + Prometheus): enviar notificaciones con proveedor simulado en `ACCEPTED` y luego `PERMANENT_FAILURE`; afirmar tasa, `DEGRADED/DOWN`, `lastError.code`, 403 para `CLIENTE`, 503 con Prometheus detenido (control positivo previo: la misma petición da 200), y `Duration` ≤ 2 s.
- Seguridad: error con correo y token en el mensaje; la respuesta serializada no los contiene. Dos tenants distintos obtienen la misma respuesta global sin ningún identificador de tenant.
- `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Riesgos

- El scrape es de 15 s: el estado tiene retraso hasta ese valor más la ventana; se documenta en el contrato.
- `lastError.at` es aproximado (A4).
- Sin tenant en las métricas (A1): puede chocar con un requisito futuro de aislamiento.

## Preguntas abiertas

1. ¿Salud global está bien o se necesita por tenant? Recomendación: global ahora (A1).
2. ¿Umbrales 0,95 / 0,5 y ventana 5 min? Recomendación: aceptarlos como configurables.
3. ¿Lista de proveedores desde Prometheus o desde el catálogo? Recomendación: Prometheus, y completar `UNKNOWN` con el catálogo en una historia posterior.
