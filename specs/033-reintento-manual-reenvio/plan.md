# Implementation Plan: Reintento manual y reenvío por otro canal

**Branch**: `feature/HU2-117-reintento-manual-reenvio` | **Date**: 2026-10-24 | **Spec**: [spec.md](./spec.md)

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

El usuario edita este campo para aprobar (Pendiente -> Aceptado) o pedir revisión. Ningún agente declara aprobación en otro lugar (Principio VI).

## Summary

Dos operaciones de OPERADOR sobre notificaciones FAILED (y RECOVERABLE en el reintento):

1. **Reintento manual** (`retryNotification`): caso de uso `RetryNotificationService` que no despacha; valida, vuelve a PENDING con `requeue()`, guarda con bloqueo optimista y encola en la cola de despacho con el origen `MANUAL` en el encabezado `x-attempt-origin`. El consumidor lo lee (ausente = AUTOMATIC) y lo pasa a `dispatch(id, origin)`. Diseño heredado de la spec 018 ([research.md](./research.md), D1-D3), con el contador **sin reiniciar** ([spec.md#FR-007](./spec.md)).
2. **Reenvío por otro canal**: `ResendNotificationService` crea una notificación nueva delegando en `SendNotificationUseCase` (validación de canal, persistencia, encolado y 409 por `externalId` repetido ya existen); no toca la original ([research.md](./research.md), D4).

Tenant y rol salen del `AuthenticatedPrincipal` de la sesión (specs 025-028). Hecho el cambio de firma, el origen del intento sale del mensaje y no del `AUTOMATIC` fijo.

## Technical Context

Java 25, Spring Boot 4.1 (WebFlux), Reactor, Spring AMQP, Mongo reactivo. **Sin dependencias nuevas ni cambios de esquema** (se reutilizan `Notification`, `DeliveryAttempt.origin` y `externalId` único por tenant). Pruebas: JUnit 5, Mockito, StepVerifier, Testcontainers (Mongo + RabbitMQ), WebTestClient, `FakeProviderServer`. Rendimiento: SC-001 y SC-007 (<= 5 s). Restricciones: sin hilos bloqueados en WebFlux; concurrencia solo con `version` y el índice único `(tenantId, externalId)`.

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I. Hexagonal | Casos de uso, comandos, excepciones y firmas en `core` sin Spring; el encabezado AMQP solo existe en `infrastructure`. `HexagonalArchitectureTest` y `ModularityTests` tras la historia. |
| II. Contract-first | `/notification-retries` ya existe (`planned`); `/notification-resends` es nueva. Ambas rutas literales (ver [contracts/endpoints.md](./contracts/endpoints.md)). El carril Contrato edita el YAML **antes** de los controllers; esta historia no lo toca. Tarea bloqueante en `tasks.md`: no escribir controller hasta verificar que el YAML las declara `available`. |
| III. Sin comentarios | Ninguno en código nuevo. |
| IV. Calidad | Unitarias en `core`; pruebas de consumidor y publicador; `NotificationRetryE2ETest` y `NotificationResendE2ETest` con Mongo + RabbitMQ reales; `verify` una vez al cierre. Plan de pruebas en research.md, D7. |
| V. Git | Commits de una línea; razonamiento en `specs/033-...`. |
| VI. spec-kit | Se detiene aquí; `tasks` e `implement` esperan aprobación. |
| VII. Sin atajos | Excepciones con dueño y fecha abajo. |
| Consumidor RabbitMQ | Cumple la regla: `RabbitRetryConfig` usa `AcknowledgeMode.MANUAL` con DLQ; no hay excepción. Esta historia solo añade la lectura del encabezado `x-attempt-origin`. |
| VIII. Confiabilidad | PENDING se persiste antes de encolar y antes del 202; el reenvío usa el flujo de aceptación normal. |
| IX. Observabilidad | Log estructurado por operación con correlación, tenant, `notificationId` original y nuevo; sin direcciones ni secretos. |

## Dependencias entre historias

El orden acordado es E3 -> G3 -> G2 -> E10 -> E5. E3 deja la forma extensible de los tipos compartidos (propuesta en `specs/031-failover-proveedores/plan.md`; nombres `AttemptRecord`, `ProviderResult` y `FailedProvider` por confirmar): `Outcome(last, failedOver)`, `Notification.mark*` y `recordFailedOver` con un único `AttemptRecord` (origen, proveedor, resultado), y `DeliveryAttempt`, su documento y el mapper construidos desde él. Esta historia **se monta encima** y no redefine nada: MANUAL es solo el valor de `origin` que `dispatch` pone en el `AttemptRecord`, sin firmas paralelas. Lo propio de G2 en `core` es que `dispatch` reciba el origen (`dispatch(id, origin)`) y que `enqueueForDispatch(n, origin)` lo lleve al encabezado `x-attempt-origin`. Las firmas de este plan son provisionales hasta que E3 se fusione. Roles: tres en el código (CLIENTE, OPERADOR, ADMINISTRADOR) hasta B2.

## Decisiones de la revisión

- **Carrera manual contra automático** ([spec.md#FR-006](./spec.md)): ambos hacen `requeue()` + `save` con `version`; el perdedor recibe `NotificationVersionConflictException`. El automático ya la tolera (`onErrorResume` en `recoverExpiredRecoverable`, sin tocar `requeueAndPublish`); el manual la deja propagar a 409. El encolado ocurre solo tras un `save` exitoso, así que gana quien guarda primero y se encola un único mensaje; si gana el automático, el intento es AUTOMATIC. Se añade un E2E de coincidencia (manual y automático sobre la misma RECOVERABLE vencida, un solo intento ante el proveedor).
- **Límite del `externalId` derivado**: el contrato no declara `maxLength` para `externalId` y `ExternalId` solo exige no vacío. Se fija **255 caracteres** para el derivado; si `<externalId>-resend-<canal>` lo excede, 400 con motivo y sin crear nada. Se pide a Contrato declarar `maxLength: 255` en `externalId`.
- **Aislamiento**: `NotificationRepository.findById` no filtra por tenant (verificado). El caso de uso usa `findById` + `filter(tenantId)` + `NotificationNotFoundException`, igual que `GetNotificationStatusService`; ajeno e inexistente dan el mismo 404. Prueba de dos tenants en unitaria y E2E.
- **Dirección fuera de logs y errores**: los controllers de reintento y reenvío no registran el cuerpo de la solicitud; `ResendChannelInvalidException` y demás mensajes no incluyen la dirección. El listener procesa el cuerpo del mensaje con `LogContext.open(..., body)`, pero la dirección no viaja por la cola. E2E que busca la dirección completa en respuesta, errores y log.
- **Consumidor**: hoy NO lee `x-attempt-origin` (`dispatch(id)` fija AUTOMATIC); leerlo es trabajo nuevo de esta historia, no heredado de la 018.
- **SC-006**: solo se cumple si se retira `Notification.retryManually()`; la prueba construye un FAILED con 5 intentos recuperables y verifica que el intento manual fallido vuelve a FAILED.
- **Contrato**: la descripción de `/notification-retries` dice "Planificada"; Contrato la cambia a `available`.

### Pendientes explícitos (Principio VII)

- **PENDING con intentos y sin mensaje**: si el encolado falla tras persistir el reintento, la notificación queda sin mensaje y `recoverOrphanedPending` solo recupera las que no tienen intentos. Preexistente (afecta al reintento automático); el operador ve el 5xx pero no puede repetir porque PENDING no admite reintento. Opción sugerida: que `recoverOrphanedPending` también recupere PENDING con intentos (con marca "encolada en"; conservar MANUAL exige D1 alt. b). **Dueño**: Equipo de backend. **Revisión**: 2026-12-15.
- **Ciclo vestigial**: al retirar `retryManually` (D2) quedan `currentCycle` y `DeliveryAttempt.cycle` persistidos con valor siempre 1. Retirarlos es un cambio de esquema fuera de esta historia. **Dueño**: Equipo de backend. **Revisión**: 2026-12-15.
- **Validación de dirección por canal**: hoy `Recipient` solo exige no vacío; "dirección inválida para el canal" ([spec.md#FR-011](./spec.md)) se limita a lo que valide el envío existente. **Dueño**: Equipo de backend. **Revisión**: 2026-12-15.

## Contrato que consumirá la consola (repositorio aparte)

La consola solo usa estas llamadas con la cookie de sesión del PEP; no envía tenant.

- `POST /notification-retries` con `{"notificationId": "<uuid>"}`: 202 con `NotificationStatusResponse` (estado PENDING). Errores: 400 estado no admitido, 401, 403 (CLIENTE), 404, 409 carrera perdida, 503.
- `POST /notification-resends` con `{"notificationId": "<uuid>", "channelType": "<canal>", "recipient": "<dirección en el canal nuevo>"}`: 202 con `NotificationStatusResponse` de la **notificación nueva**. Errores: 400 estado no admitido / canal igual o no disponible / dirección inválida, 401, 403, 404, 409 reenvío ya hecho por ese canal o carrera, 503.
- Para decidir qué botón mostrar: `GET /notifications` (OPERADOR) trae `status` y `deliveryAttempts[].origin`. La consola enmascara según rol; estas respuestas no devuelven direcciones.

## Project Structure

```text
specs/033-reintento-manual-reenvio/
├── spec.md  plan.md  research.md
├── contracts/endpoints.md
└── checklists/requirements.md      (tasks.md: /speckit-tasks, tras aprobación)
```

Código (sin paquetes nuevos):

```text
core/.../exception/        NotificationStateNotAllowedException (nuevo), ResendChannelInvalidException (nuevo)
core/.../port/in/          RetryNotificationCommand/UseCase, ResendNotificationCommand/UseCase (nuevos);
                           NotificationStatusView.from(Notification); DispatchNotificationUseCase.dispatch(id, origin)
core/.../port/out/         NotificationEventPublisherPort.enqueueForDispatch(n, origin)
core/.../usecase/          RetryNotificationService, ResendNotificationService (nuevos);
                           DispatchNotificationService, GetNotificationStatusService, SendNotificationService,
                           RequeuePendingNotificationsService (ajustes)
core/.../domain/           Notification: retirar retryManually()
infrastructure/.../adapter/in/rest/    NotificationRetryController, NotificationResendController, ExceptionHandler (400/409)
infrastructure/.../adapter/in/rabbit/  NotificationDispatchListener (lee x-attempt-origin)
infrastructure/.../adapter/out/rabbit/ NotificationRabbitPublisher (escribe x-attempt-origin)
infrastructure/.../adapter/in/web/     RouteAuthorizationPolicy: regla POST /notification-resends OPERADOR
infrastructure/.../config/             RabbitTopologyProperties.ATTEMPT_ORIGIN_HEADER
```

El wiring (`UseCaseConfig`) y el YAML los hacen otros carriles; el plan de tareas los marca como dependencia externa.

## Complexity Tracking

| Violación | Por qué | Alternativa rechazada |
|---|---|---|
| Cambio de firma de `dispatch` y `enqueueForDispatch` (ajusta pruebas existentes) | El origen viaja del caso de uso al intento | Métodos paralelos duplican el camino (D1-c) |
| Retirar `retryManually()` y sus pruebas | Reiniciaba el contador, contrario a [spec.md#FR-007](./spec.md) | Dejarlo invita a usarlo y contradice la decisión |
