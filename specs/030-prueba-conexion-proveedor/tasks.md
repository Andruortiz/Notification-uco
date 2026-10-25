# Tasks: Prueba de conexion del proveedor (HU2-114)

Spec: [spec.md](spec.md) | Plan: [plan.md](plan.md). Contrato ya publicado en `api-notificaciones.yaml` (no se edita aqui).
Wiring en `ProviderProbeConfig` propia, sin tocar `UseCaseConfig`.

## Fase 1 - core (puertos y caso de uso)

- [ ] T001 `ProbeErrorCode`, `ProbeOutcome`, `ProviderProbePort`, `ProviderProbeRegistry` y `ProviderTestInProgressException` en `core`
- [ ] T002 `ProviderProbeResult`, `TestProviderConnectionUseCase` y `TestProviderConnectionService` (Clock y Duration por constructor, una prueba en vuelo por canal y proveedor) en `core`
- [ ] T003 Prueba unitaria `TestProviderConnectionServiceTest`: validacion canal/proveedor, `CREDENTIALS_MISSING`, `ADAPTER_MISSING`, `TIMEOUT` con tiempo virtual, latencia con `Clock` fijo, concurrencia por par ([FR-004](spec.md#functional-requirements), [FR-006](spec.md#functional-requirements))

## Fase 2 - adaptadores (depende de T001)

- [ ] T004 `ErrorCode.PROVIDER_TEST_IN_PROGRESS` (NTF-3060) y clasificador compartido `ProviderProbes`
- [ ] T005 Sonda Brevo `GET /v3/account` y prueba contra doble HTTP con credencial sentinela (FR-001, FR-005)
- [ ] T006 Sonda Twilio `GET /2010-04-01/Accounts/{sid}.json` y prueba con doble HTTP y sentinela
- [ ] T007 `FcmAccessTokenProvider.probe()` sin cache, sonda FCM y prueba (credencial revocada con token cacheado vigente)
- [ ] T008 Sonda `simulated` local segun `SIMULATED_PROVIDER_RESULT` y prueba

## Fase 3 - API y wiring (depende de T002, T004)

- [ ] T009 `ProviderProbeConfig` (registro, caso de uso, `notification.provider.probe.timeout`, defecto 5 s)
- [ ] T010 `RouteAuthorizationPolicy`: `POST /provider-connection-tests` minimo ADMINISTRADOR, con su prueba unitaria
- [ ] T011 `ProviderConnectionTestController`, DTOs, mapeo 429 en `NotificationExceptionHandler`, log estructurado (FR-007) y metrica `notification.provider.probe` (FR-005)
- [ ] T012 Prueba de controller: metrica con tags `providerId` y `status` sin sentinela, log con identidad y tenant, dos tenants (FR-009)

## Fase 4 - E2E y cierre

- [ ] T013 **E2E** `ProviderConnectionTestE2ETest`: ADMINISTRADOR prueba `simulated` en los tres canales (OK y fallo, `Duration` < 1 s), sonda lenta -> `TIMEOUT` con `Duration` <= limite + 1 s, un real contra doble HTTP (OK y 401 con sentinela ausente), CLIENTE y OPERADOR 403, 429 concurrente, sin notificaciones en Mongo, >= 2 proveedores por canal (SC-001 a SC-004)
- [ ] T014 `ProviderConnectionTestPepE2ETest`: con PEP activo y doble del PDP, CLIENTE y OPERADOR 403 y la sonda no se invoca; ADMINISTRADOR 200 (SC-003, SC-005)
- [ ] T015 `HexagonalArchitectureTest` y `ModularityTests` en verde; `spotless:check compile`
- [ ] T016 Cierre: `verify` completo lo ejecuta quien integra el nivel (no se corre en esta entrega)
