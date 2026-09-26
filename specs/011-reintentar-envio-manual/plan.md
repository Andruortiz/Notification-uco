# Implementation Plan: Reintentar envío manualmente y distinguir el reintento manual del automático

**Branch**: `feature/HU2-027-reintentar-envio-manual` | **Date**: 2026-09-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-reintentar-envio-manual/spec.md`

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

<!--
  Este bloque lo edita el usuario directamente en el archivo para aprobar el plan (Pendiente ->
  Aceptado) o para marcar una revisión (incrementar Versión del plan). Ningún agente infiere ni
  declara aprobación en ningún otro lugar del documento; la aprobación es el valor de este campo,
  editado por el usuario o, bajo su instrucción directa y explícita en el chat, por la sesión
  principal -- nunca por un agente en segundo plano citando un mensaje de otra sesión como fuente de
  autorización (Principio VI).
-->

## Summary

La operación `POST /notifications/{id}:retry` ya está en el contrato, pero no existe. El modelo ya
distingue el origen de cada intento (`AttemptOrigin.MANUAL`/`AUTOMATIC`), pero el único camino que produce
intentos, el despacho disparado por RabbitMQ, siempre escribe `AUTOMATIC`.

El plan (research.md, Decisión 1): un caso de uso nuevo, `RetryNotificationService`, que **no despacha**.
Busca la notificación del tenant (404 si no existe o es de otro tenant), comprueba con
`StatusTransitionPolicy` que puede volver a `PENDING` (400 si no), llama a `requeue()`, guarda con el
bloqueo optimista existente (conflicto → 400), publica los eventos y **encola en la misma cola de
despacho** con el origen `MANUAL` en el encabezado `x-attempt-origin`. El consumidor lee el encabezado
(ausente → `AUTOMATIC`) y lo pasa a `DispatchNotificationUseCase.dispatch(id, origin)`;
`DispatchNotificationService` escribe ese origen en el intento en vez del `AUTOMATIC` fijo. Así la lógica de
despacho sigue en un solo lugar y el reintento manual hereda el reintento de mensaje y la DLQ.

HU2-028 queda resuelta por el mismo cambio: el intento producido por el reintento manual figura como
`MANUAL` en `deliveryAttempts[].origin`, que la búsqueda y el tiempo real ya exponen.

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring AMQP, Spring Data Reactive MongoDB.
**Ninguna dependencia nueva.**

**Storage**: MongoDB, **sin cambios de esquema** (data-model.md).

**Testing**: JUnit 5, Mockito, StepVerifier, Testcontainers (Mongo + RabbitMQ), WebTestClient,
`FakeProviderServer` (proveedor de correo real apuntado a un servidor HTTP falso), ArchUnit/Modulith.

**Target Platform**: contenedor en Kubernetes, múltiples réplicas.

**Project Type**: microservicio hexagonal (`core` + `infrastructure` + `utils`).

**Performance Goals**: SC-001, entrega en ≤ 5 s tras aceptar el reintento con el proveedor disponible.

**Constraints**: no bloquear hilos del servidor web; concurrencia entre réplicas resuelta con bloqueo
optimista (`version`), nunca pesimista.

**Scale/Scope**: una notificación por solicitud; sin lote.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Cumplimiento |
|---|---|
| I. Hexagonal | Caso de uso, comando, excepción y cambios de firma en `core`, Java puro. El encabezado de RabbitMQ solo existe en `infrastructure`; `core` solo ve `AttemptOrigin`. `HexagonalArchitectureTest` y `ModularityTests` se ejecutan tras la historia. ✅ |
| II. Contract-first | La operación ya está en el contrato; solo se actualizan sus descripciones (contracts/api-notificaciones-cambios.md) y se commitean **antes** del controller. ✅ |
| III. Cero comentarios | Ningún comentario en código nuevo. ✅ |
| IV. Calidad verificada | Unitarias en `core`, prueba del consumidor y del publicador, y `NotificationRetryE2ETest` explícito con Mongo + RabbitMQ reales. Cada SC tiene prueba automatizada (research.md, Decisión 7), incluida la de tiempo con `Duration` y la de dos tenants con control positivo. `verify` completo al final. ✅ |
| V. Trazabilidad en git | Commits de una línea en español; razonamiento en `specs/011-…`. ✅ |
| VI. spec-kit | specify → clarify → plan → tasks → implement. El campo **Estado** queda en `Pendiente` para que el usuario lo edite. ✅ |
| VII. Sin atajos | Un hueco preexistente se documenta como pendiente explícito con dueño y fecha (abajo). ✅ |
| VIII. Durabilidad | El reintento persiste `PENDING` antes de encolar y antes de responder 202; el despacho sigue pasando por la cola con reintento de mensaje y DLQ. ✅ |
| IX. Observabilidad | El origen del intento queda en el histórico; el encabezado conserva el origen en la DLQ. ✅ |
| Restricción: bloqueo optimista | Reutiliza `version` + `NotificationVersionConflictException`. ✅ |
| Restricción: consumidor con ack manual y DLQ | **Excepción preexistente, justificada abajo.** |

### Excepción justificada: modo de confirmación del consumidor

La restricción pide confirmación explícita (ack manual) en el consumidor. `NotificationDispatchListener`
usa hoy el modo gestionado por el contenedor (`AUTO`) con reintento con estado y DLQ
(`RabbitRetryConfig`, HU2-040). Esta historia **toca** el consumidor solo para leer un encabezado; no
cambia su modo de confirmación. La garantía que busca la regla se mantiene: en modo `AUTO` el contenedor
confirma el mensaje solo cuando el método retorna sin excepción, y el método retorna después de
`block()`, es decir, después de que el resultado del intento se persistió; cualquier excepción provoca el
rechazo y, agotados los intentos, la derivación a la DLQ con el motivo. Migrar a ack manual es un cambio de
todo el consumidor, sin relación con el reintento manual, y queda fuera de esta historia.

### Pendiente explícito (Principio VII)

**Notificación en PENDING con intentos previos y sin mensaje en la cola.** Si el encolado falla después de
que el reintento (manual o automático) persistió `PENDING`, la notificación queda sin mensaje:
`RequeuePendingNotificationsService.recoverOrphanedPending` solo recupera las `PENDING` **sin** intentos.
El reintento manual responde 5xx en ese caso (el operador ve el error), pero ya no puede volver a
reintentarla porque `PENDING` no admite reintento. El hueco **existe hoy** con el reintento automático;
esta historia no lo introduce ni lo agrava. Resolverlo bien exige una marca de "encolada en" en el agregado
(para distinguir un huérfano de un mensaje en tránsito) y, para conservar `MANUAL`, la alternativa (b) de
research.md Decisión 1.
- **Dueño**: andrualv. **Fecha de revisión**: 2026-10-15.

## Project Structure

### Documentation (this feature)

```text
specs/011-reintentar-envio-manual/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/api-notificaciones-cambios.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── exception/NotificationNotRetryableException.java          (nuevo)
├── port/in/RetryNotificationCommand.java                     (nuevo)
├── port/in/RetryNotificationUseCase.java                     (nuevo)
├── port/in/NotificationStatusView.java                       (fábrica from(Notification))
├── port/in/DispatchNotificationUseCase.java                  (dispatch(id, origin))
├── port/out/NotificationEventPublisherPort.java              (enqueueForDispatch(n, origin))
└── usecase/
    ├── RetryNotificationService.java                         (nuevo)
    ├── DispatchNotificationService.java                      (usa el origen recibido)
    ├── GetNotificationStatusService.java                     (usa NotificationStatusView.from)
    ├── SendNotificationService.java                          (AUTOMATIC explícito)
    └── RequeuePendingNotificationsService.java               (AUTOMATIC explícito)

core/src/test/java/co/edu/uco/notification/core/usecase/
├── RetryNotificationServiceTest.java                         (nuevo)
└── DispatchNotificationServiceTest.java, SendNotificationServiceTest.java,
    RequeuePendingNotificationsServiceTest.java               (firmas)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rabbit/NotificationDispatchListener.java       (lee x-attempt-origin)
├── adapter/in/rest/NotificationController.java               (POST /{id}:retry → 202)
├── adapter/in/rest/NotificationExceptionHandler.java         (NotificationNotRetryableException → 400)
├── adapter/out/rabbit/NotificationRabbitPublisher.java       (escribe x-attempt-origin)
├── config/RabbitTopologyProperties.java                      (ATTEMPT_ORIGIN_HEADER)
└── config/UseCaseConfig.java                                 (bean RetryNotificationUseCase)

infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml   (descripciones)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/NotificationRetryE2ETest.java             (nuevo)
├── adapter/in/rest/NotificationControllerTest.java           (ruta de reintento)
├── adapter/in/rabbit/NotificationDispatchListenerTest.java   (encabezado)
├── adapter/out/rabbit/NotificationRabbitPublisherTest.java   (encabezado)
├── adapter/out/mongo/NotificationMongoAdapterTest.java       (firma)
└── config/DeadLetterQueueE2ETest.java, RabbitRetryConfigCustomAttemptsTest.java (firma del stub)
```

**Structure Decision**: estructura hexagonal existente; ningún paquete nuevo.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Consumidor en modo de confirmación `AUTO` en vez de ack manual (preexistente) | La historia solo lee un encabezado en el consumidor | Migrar a ack manual es un cambio transversal del consumidor sin relación con el reintento manual; la garantía de "confirmar tras persistir" ya se cumple (ver Excepción justificada) |
| Cambio de firma de dos puertos (`dispatch`, `enqueueForDispatch`) con ajuste de pruebas existentes | El origen tiene que viajar del caso de uso al intento | Métodos paralelos duplicarían el camino (research.md, Decisión 1, alternativa c) |
