# Tasks: Prueba de conexion del proveedor (HU2-114)

Spec: [spec.md](spec.md) | Plan: [plan.md](plan.md). Contrato ya publicado en `api-notificaciones.yaml` (no se edita aqui).
Wiring en `ProviderProbeConfig` propia, sin tocar `UseCaseConfig`.

## Fase 1 - core (puertos y caso de uso)

- [x] T001 `ProbeErrorCode`, `ProbeOutcome`, `ProviderProbePort`, `ProviderProbeRegistry` y `ProviderTestInProgressException` en `core`
- [x] T002 `ProviderProbeResult`, `TestProviderConnectionUseCase` y `TestProviderConnectionService` (Clock y Duration por constructor, una prueba en vuelo por canal y proveedor) en `core`
- [x] T003 Prueba unitaria `TestProviderConnectionServiceTest`: validacion canal/proveedor, `CREDENTIALS_MISSING`, `ADAPTER_MISSING`, `TIMEOUT` con tiempo virtual, latencia con `Clock` fijo, concurrencia por par ([FR-004](spec.md#functional-requirements), [FR-006](spec.md#functional-requirements)) (desviacion: `ProviderProbeResult` usa `errorCode` nulo para OK)

## Fase 2 - adaptadores (depende de T001)

- [x] T004 `ErrorCode.PROVIDER_TEST_IN_PROGRESS` (NTF-3060) y clasificador compartido `ProviderProbes`
- [x] T005 Sonda Brevo `GET /v3/account` y prueba contra doble HTTP con credencial sentinela (FR-001, FR-005)
- [x] T006 Sonda Twilio `GET /2010-04-01/Accounts/{sid}.json` y prueba con doble HTTP y sentinela
- [x] T007 `FcmAccessTokenProvider.probe()` sin cache, sonda FCM y prueba (credencial revocada con token cacheado vigente)
- [x] T008 Sonda `simulated` local segun `SIMULATED_PROVIDER_RESULT` y prueba

## Fase 3 - API y wiring (depende de T002, T004)

- [x] T009 `ProviderProbeConfig` (registro, caso de uso, `notification.provider.probe.timeout`, defecto 5 s)
- [x] T010 `RouteAuthorizationPolicy`: `POST /provider-connection-tests` minimo ADMINISTRADOR, con su prueba unitaria
- [x] T011 `ProviderConnectionTestController`, DTOs, mapeo 429 en `NotificationExceptionHandler`, log estructurado (FR-007) y metrica `notification.provider.probe` (FR-005)
- [x] T012 Prueba de controller: metrica con tags `providerId` y `status` sin sentinela, log con identidad y tenant, dos tenants (FR-009)

## Fase 4 - E2E y cierre

- [x] T013 **E2E** `ProviderConnectionTestE2ETest`: ADMINISTRADOR prueba `simulated` en los tres canales (OK y fallo, `Duration` < 1 s), sonda lenta -> `TIMEOUT` con `Duration` <= limite + 1 s, un real contra doble HTTP (OK y 401 con sentinela ausente), CLIENTE y OPERADOR 403, 429 concurrente, sin notificaciones en Mongo, >= 2 proveedores por canal (SC-001 a SC-004)
- [x] T014 `ProviderConnectionTestPepE2ETest`: con PEP activo y doble del PDP, CLIENTE y OPERADOR 403 y la sonda no se invoca; ADMINISTRADOR 200 (SC-003, SC-005)
- [x] T015 `HexagonalArchitectureTest` y `ModularityTests` en verde; `spotless:check compile`
- [ ] T016 Cierre: `verify` completo lo ejecuta quien integra el nivel (no se corre en esta entrega)

## Desviaciones

- T013: el E2E se reparte en `ProviderConnectionTestE2ETest` (OK, TIMEOUT, Brevo contra doble, 403, 429, tenants) y `ProviderConnectionTestSimulatedFailureE2ETest` (resultado simulado fallido, otro contexto por la propiedad). La sonda lenta es `GatedProbeProvider` (doble en `support`), el adaptador `simulated` no se toca.
- T014: el rol de la sesion con PEP es siempre CLIENTE; el 403 lo decide el doble del PDP (`StubSecurityPlatform.denyDecisionsFor`).
- Errores 400: se reutilizan `NTF-1003` (canal desconocido) y `NTF-1004` (proveedor fuera del canal o vacio).
- Las pruebas de contrato que cuentan operaciones o comparan con el YAML siguen fallando hasta que se reconcilie el contrato del nivel.
