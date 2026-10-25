# Tasks: Panel de salud de proveedores (037)

**Entradas**: [spec.md](spec.md), [plan.md](plan.md). Rama `feature/HU2-121-panel-salud-proveedores`, sobre E3.

## Fase 1: core

- [x] T001 Puerto `ProviderMetricsQueryPort`, `GetProviderHealthUseCase`, value objects `ProviderHealth*` y `ProviderMetricsUnavailableException` en `core` (FR-005).
- [x] T002 Prueba `GetProviderHealthServiceTest` (StepVerifier para el error): umbrales, `UNKNOWN` con precedencia, exclusión de `recoverable`, `failed` prevalece, descarte de etiquetas inválidas, latencia repetida por canal (FR-003, FR-004, FR-009).
- [x] T003 `GetProviderHealthService` (D1, D4, D5).

## Fase 2: infrastructure

- [x] T004 `ProviderHealthProperties` y bloque `provider-health.*` en `application.yml` (A5, FR-008).
- [x] T005 `PrometheusProviderMetricsAdapter` con WebClient, plantillas PromQL fijas y tiempo límite; cualquier fallo se traduce en `ProviderMetricsUnavailableException` sin URL (D2, D5, FR-009).
- [x] T006 `ProviderHealthController`, regla `GET /provider-health` -> `OPERADOR` en `RouteAuthorizationPolicy`, manejo 503 `INFRASTRUCTURE_ERROR` en `NotificationExceptionHandler` (D6, D8).
- [x] T007 `ProviderHealthConfig` propia (sin tocar `UseCaseConfig`) y `grafanaUrl` (D7).
- [x] T008 `.env.example` y README con `PROMETHEUS_URL`, `GRAFANA_URL`, `PROVIDER_HEALTH_*` y límites documentados (D3).

## Fase 3: verificación

- [x] T009 E2E `ProviderHealthE2ETest` (`RANDOM_PORT` + Mongo + RabbitMQ + Prometheus simulado con servidor HTTP): estados, tasa, latencia, último error, `Duration` ≤ 2 s, 403 para `CLIENTE`, mismo resultado para dos tenants, sin texto sensible ni etiqueta inválida, 503 con `NTF-5000` tras control positivo 200 y sin la URL.
- [x] T010 `HexagonalArchitectureTest`, `ModularityTests` y `RouteAuthorizationPolicyTest` en verde.
- [ ] T011 `./mvnw verify` único en segundo plano al cerrar la historia (lo ejecuta el agente de integración tras cablear contrato y contadores).

## Desviaciones

- T005/T009: el E2E simula Prometheus con un servidor HTTP de prueba y no con Prometheus real en Testcontainers (acordado con el caller). No hay prueba de integración del adaptador contra Prometheus real: queda cubierto solo por el E2E con respuestas canned; las plantillas PromQL no se validan contra un Prometheus real.
- FR-009: el patrón efectivo es `^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`, no solo minúsculas, porque los canales reales son `EMAIL`, `SMS`, `PUSH`.
- D9: `CIRCUIT_OPEN` y `failedOver` no se modelan en el enum ni en la respuesta (reservados en el contrato).
- La respuesta serializa directamente `ProviderHealthReport` de `core`; no hay DTO REST aparte.
- T010: `RouteAuthorizationPolicyContractTest` falla por `GET /notifications/timeline` (historia ajena, contrato adelantado); `GET /provider-health` coincide.
