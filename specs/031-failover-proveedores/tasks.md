# Tasks: Failover entre proveedores del canal

**Entrada**: [spec.md](spec.md), [plan.md](plan.md). Rutas relativas a `core/src/main/java/co/edu/uco/notification/core` (core) e `infrastructure/src/main/java/co/edu/uco/notification/infrastructure` (infra). Las pruebas se ejecutan al final de la implementación, por clase; `verify` solo en T040.

## Fase 1: Dependencia

- [x] T001 Fijar `resilience4j-bom` en `dependencyManagement` del `pom.xml` raíz y declarar `resilience4j-circuitbreaker` en `infrastructure/pom.xml` (sin `resilience4j-spring-boot3`).

## Fase 2: Tipos compartidos en core (bloquea todo lo demás)

- [x] T002 Crear `domain/valueobject/FailureCause`, `port/out/ProviderResult`, `domain/AttemptRecord` (plan, Tipos compartidos).
- [x] T003 [P] Crear `exception/ProviderCircuitOpenException` y `exception/AllProvidersCircuitOpenException`.
- [x] T004 Ampliar `domain/DeliveryAttempt` con `failedOver`, `latencyMillis`, `failureCause`, `correlationId`, `messageId`; conservar las fábricas `of` de 4 y 5 argumentos y añadir `of(Instant, AttemptRecord, int)`.
- [x] T005 `domain/Notification`: `mark*` y `recordFailedOver` reciben `AttemptRecord`; `recoverableAttemptsInCurrentCycle` excluye `failedOver` (FR-006).
- [x] T006 `port/out/NotificationSenderPort.send` devuelve `Mono<ProviderResult>`.
- [x] T007 `usecase/DispatchNotificationService`: recorrido secuencial de la ruta, omisiones, error de transporte, `AllProvidersCircuitOpenException`, `Outcome(last, failedOver)` y `applyOutcome` (FR-001 a FR-004, FR-009 a FR-013).

## Fase 3: Infraestructura

- [x] T008 Adaptadores `Brevo`, `Twilio`, `Fcm`: `send` devuelve `ProviderResult.of(result)`; clasificadores intactos.
- [x] T009 `SimulatedProviderProperties.enabled`, `SimulatedNotificationProvider` (`disabledReason`, `ProviderDisabledException`) y `ConfigurationConfig.fixedConfiguration` (FR-008).
- [x] T010 `ProviderCircuitProperties`, `CircuitBreakerNotificationSender` y apilado en `ObservedProviderConfig`; `ObservedNotificationSender` adaptado a `ProviderResult` (FR-007).
- [x] T011 `DeliveryAttemptDocument`, `NotificationDocumentMapper` (compatibilidad con documentos sin los campos nuevos) y `DeliveryAttemptResponse.failedOver`.
- [x] T012 `application.yml` (`SIMULATED_PROVIDER_ENABLED`, catálogo con el real primero) y `application-local.yml` (`simulated.enabled: true`) (FR-014).
- [x] T013 `ChannelCatalogSeeder`: actualiza solo el orden antiguo exacto `simulated,<real>`; cualquier otra divergencia solo se registra.
- [x] T014 Revisar `MicrometerNotificationMetrics.resultLabel`, `NotificationMetricsPort`, `ProviderLogs`, `applyOutcome` y `DeliveryAttemptDocument` ante todos los valores de `AttemptResult`.

## Fase 4: Ajuste de pruebas existentes

- [x] T015 `core`: ajustar las ~28 llamadas a `DeliveryAttempt.of` en 8 archivos de prueba (las fábricas de 4 y 5 argumentos se conservan; solo cambian las que usan el constructor canónico).
- [x] T016 `core`: migrar las ~22 llamadas a `Notification.mark*` en 5 archivos de prueba a `AttemptRecord.of(result, origin, providerId)`.
- [x] T017 Pruebas de adaptadores (`Brevo*`, `Twilio*`, `Fcm*`, `Simulated*`, `ObservedNotificationSenderTest`, `RecordingAttachmentSender`) y mocks del puerto: `.map(ProviderResult::result)` y `ProviderResult.of(X)`; `SimulatedProviderProperties(result, enabled)`.
- [x] T018 Pruebas de mapeo Mongo y respuestas REST que construyen `DeliveryAttemptDocument` o `DeliveryAttemptResponse`.

## Fase 5: Pruebas nuevas

- [x] T019 [P] `DispatchNotificationServiceTest`: A recuperable y B acepta; A y B recuperables; A permanente sin conmutar; A acepta y B sin invocar; deshabilitado y circuito abierto omitidos sin intento; todos abiertos lanza `AllProvidersCircuitOpenException` sin métrica ni cambio de estado; orden del historial; excepción de transporte; ruta parcial con proveedor sin adaptador; ruta sin invocables (FR-001 a FR-004, FR-009 a FR-013).
- [x] T020 [P] `NotificationTest`: `recordFailedOver` y `recoverableAttemptsInCurrentCycle` (FR-006).
- [x] T021 [P] `RequeuePendingNotificationsServiceTest`: intentos `failedOver` no cuentan para el agotamiento.
- [x] T022 [P] `CircuitBreakerNotificationSenderTest`: abre al umbral, no invoca abierto, semiabierto cierra con éxito, permanentes no abren, circuitos independientes, aserción de `Duration` de la omisión (SC-002).
- [x] T023 [P] `NotificationDocumentMapperTest` / `NotificationMongoAdapterTest`: ida y vuelta de `failedOver` y documento viejo sin el campo.
- [x] T024 [P] `SimulatedNotificationProviderTest`: deshabilitado por defecto y habilitado (control positivo); prueba de `fixedConfiguration` sin `simulated` por defecto (SC-003).
- [x] T025 [P] `ChannelCatalogSeederTest`: orden antiguo se actualiza; personalizado no se toca; cobertura en `ChannelCatalogE2ETest`.

## Fase 6: E2E

- [x] T026 `ProviderFailoverE2ETest`: proveedor de prueba `failing-test` en `src/test`; ruta `[failing-test, simulated]`; crea por API, espera `DELIVERED`, historial con fallido `failedOver` y entregador (SC-001, SC-004); sin `simulated` habilitado queda `RECOVERABLE` (SC-003) con control positivo; dos tenants (FR-010); aserción de `Duration` del despacho.
- [x] T027 `ProviderFailoverCircuitE2ETest`: tras fallos hasta el umbral A recibe 0 invocaciones y entrega B sin intento de A (SC-002, `Duration`); todos abiertos conserva estado, sin intentos ni métrica de fallo; control positivo tras la espera.

## Fase 7: Cierre

- [x] T028 `./mvnw -B -ntp spotless:apply` y `spotless:check compile`; `HexagonalArchitectureTest` y `ModularityTests` en verde.
- [x] T029 Ejecutar pruebas de `core`, las clases tocadas y los adaptadores (Brevo, Twilio, Fcm, simulated) y despacho.
- [ ] T040 `./mvnw -B -ntp verify` en segundo plano, una sola vez, en verde (lo ejecuta quien integra la ola; ver informe).

## Desviaciones

- T001/T010: se usa solo `resilience4j-circuitbreaker`; el decorador emplea `tryAcquirePermission`/`onError`/`onSuccess` directamente, porque contar `RECOVERABLE_FAILURE` como fallo exige leer el resultado y el operador de `resilience4j-reactor` solo cuenta errores. No se añade `resilience4j-reactor`.
- `AttemptRecord` vive en `core.domain` (lo consume `Notification`).
- T019: las pruebas de failover de despacho viven en `DispatchFailoverTest` (clase nueva) en lugar de ampliar `DispatchNotificationServiceTest`.
- T026: el caso con `simulated` deshabilitado vive en `ProviderFailoverSimulatedDisabledE2ETest` porque exige otro contexto de Spring; el control positivo es `ProviderFailoverE2ETest`.
- T017: las E2E de Brevo, Twilio, Fcm y de catalogo que asumian `simulated` primero se ajustaron al nuevo orden por defecto (rutas de un solo proveedor donde la prueba espera no entrega).
- T029 y T040 quedan sin marcar: `verify` completo no se ejecuta en esta ola.
