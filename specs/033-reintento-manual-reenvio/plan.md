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
| VIII. Confiabilidad | PENDING se persiste antes de encolar y antes del 202; el reenvío usa el flujo de aceptación normal. |
| IX. Observabilidad | Log estructurado por operación con correlación, tenant, `notificationId` original y nuevo; sin direcciones ni secretos. |
| Consumidor RabbitMQ con ack manual y DLQ | Excepción preexistente documentada abajo. |

### Excepción justificada: confirmación del consumidor

`NotificationDispatchListener` usa confirmación gestionada por el contenedor (`AUTO`) con reintento con estado y DLQ (`RabbitRetryConfig`). Esta historia solo le añade la lectura de un encabezado; el mensaje se confirma tras retornar el método, es decir, tras persistir el resultado, y los agotados van a la DLQ. Migrar a ack manual es transversal y fuera de alcance. **Dueño**: andrualv. **Revisión**: 2026-11-15.

### Pendientes explícitos (Principio VII)

- **PENDING con intentos y sin mensaje**: si el encolado falla tras persistir el reintento, la notificación queda sin mensaje y `recoverOrphanedPending` solo recupera las que no tienen intentos. Preexistente (afecta al reintento automático); el operador ve el 5xx pero no puede repetir porque PENDING no admite reintento. Resolver exige marca "encolada en" y conservar el origen (research.md, D1 alt. b). **Dueño**: andrualv. **Revisión**: 2026-11-15.
- **Ciclo vestigial**: al retirar `retryManually` (D2) quedan `currentCycle` y `DeliveryAttempt.cycle` persistidos con valor siempre 1. Retirarlos es un cambio de esquema fuera de esta historia. **Dueño**: andrualv. **Revisión**: 2026-11-15.
- **Validación de dirección por canal**: hoy `Recipient` solo exige no vacío; "dirección inválida para el canal" ([spec.md#FR-011](./spec.md)) se limita a lo que valide el envío existente. **Dueño**: andrualv. **Revisión**: 2026-11-15.

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
