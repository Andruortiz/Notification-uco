# Tasks: Linea de tiempo por notificacion (HU2-118)

Entradas: [spec.md](spec.md), [plan.md](plan.md). Sin tareas de contrato (la operacion la publica el carril Contrato).
Los intentos `MANUAL` dependen de G2 y quedan fuera.

## Fase 1: Core

- [ ] T001 Ampliar `FailureCause` con `PROVIDER_REJECTED`, `PROVIDER_TEMPORARY`, `ATTACHMENTS_UNSUPPORTED`, `RETRIES_EXHAUSTED`, `UNKNOWN` (FR-004).
- [ ] T002 `DispatchNotificationService`: latencia medida en `attempt`, causa por defecto segun la tabla del plan (conserva la que ya trae el proveedor; `RETRIES_EXHAUSTED` al agotar), `correlationId` en `recordOf`, log `DISPATCH_ATTEMPT_RECORDED` con `correlationId` y `failureCause` (FR-002, FR-003, FR-010).
- [ ] T003 `NotificationTimelineView` y `GetNotificationStatusUseCase.getTimeline` implementado en `GetNotificationStatusService` con intentos ordenados por `occurredOn` y busqueda por tenant e id (FR-001, FR-005, FR-008).
- [ ] T004 Pruebas unitarias `DispatchNotificationServiceTest` (causa, latencia y `correlationId` por rama) y `GetNotificationStatusServiceTest` (orden, tenant ajeno, sin intentos).

## Fase 2: Infrastructure

- [ ] T005 `NotificationTimelineResponse` y `GET /notifications/timeline` en `NotificationController`; destinatario enmascarado salvo ADMINISTRADOR (FR-006, FR-007).
- [ ] T006 Regla `GET /notifications/timeline` rol OPERADOR en `RouteAuthorizationPolicy` y su prueba (FR-009).
- [ ] T007 Pruebas de ida y vuelta de latencia, causa y `correlationId` en `NotificationDocumentMapperTest` y documento legado sin campos.

## Fase 3: E2E y cierre

- [ ] T008 E2E `NotificationTimelineE2ETest`: despacho real con fallo recuperable y luego aceptado; roles OPERADOR/ADMINISTRADOR/CLIENTE; dos tenants (404); texto crudo sembrado ausente de respuesta y logs con control positivo de `DISPATCH_*`; `Duration` < 1 s con 20 intentos (SC-001 a SC-004).
- [ ] T009 `HexagonalArchitectureTest`, `ModularityTests` y pruebas de despacho/mapper en verde; `spotless:apply`.
- [ ] T010 Puerta completa `verify` una sola vez en segundo plano (a cargo del carril de cierre del nivel).
