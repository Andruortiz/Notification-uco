# Implementation Plan: Ver notificaciones en tiempo real en el dashboard

**Branch**: `feature/HU2-072-live-dashboard-updates` | **Date**: 2026-09-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-dashboard-tiempo-real/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

El dashboard necesita ver los cambios de estado de una notificación (`PENDING → IN_PROCESS →
DELIVERED/RECOVERABLE/FAILED/DISCARDED`) sin refrescar. La pieza que lo hace posible con
infraestructura ya existente y sin agregar ningún componente nuevo (Redis, WebSocket, Mongo Change
Streams) es un `FanoutExchange` de RabbitMQ (`notification.events.exchange`) que **ya existe y ya
recibe cada `DomainEvent` publicado hoy**, pero que ningún consumidor escucha todavía — es
infraestructura declarada y sin usar. Esta historia: (1) cierra una brecha del modelo de dominio
donde 3 de las 6 transiciones de estado (`RECOVERABLE`, el reencolado a `PENDING`, y `DISCARDED`) no
registran ningún `DomainEvent` hoy; (2) agrega el primer consumidor de ese fanout — cada réplica
declara su propia cola anónima vinculada a él, de modo que las N réplicas reciben cada evento sin
necesitar un almacén compartido nuevo; y (3) expone un endpoint `GET /notifications:subscribe` sobre
Server-Sent Events (WebFlux) que primero reproduce la foto vigente (reutilizando la búsqueda de
HU2-025) y luego continúa con actualizaciones en vivo filtradas igual que la búsqueda manual — lo que
también resuelve la resincronización tras reconexión (FR-006) sin ningún mecanismo adicional, porque
una reconexión de `EventSource` es, para el servidor, simplemente una solicitud GET nueva.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux (`Flux<ServerSentEvent<T>>` para el endpoint de
suscripción), Reactor (`Sinks.Many` para difundir dentro de una réplica cada evento recibido del
fanout a todas sus conexiones SSE activas), Spring AMQP (cola anónima/exclusiva nueva vinculada al
`FanoutExchange` `notification.events.exchange` ya declarado), Spring Data Reactive MongoDB
(reutiliza `NotificationRepository.findById`/`.search(...)` ya existentes, sin colección ni campo
nuevo)

**Storage**: MongoDB — ninguna colección ni campo nuevo; se reutiliza `notifications` tal como está.
RabbitMQ — ninguna exchange nueva; se agrega una cola anónima por réplica (`AnonymousQueue`, no
declarada en el contrato de infraestructura como un recurso persistente, se recrea al reiniciar cada
réplica) vinculada al fanout `notification.events.exchange` ya existente

**Testing**: JUnit 5 + Mockito + `StepVerifier` (nuevo caso de uso y nuevos eventos de dominio);
prueba de integración contra RabbitMQ real (Testcontainers, mismo patrón que el resto del proyecto)
para el nuevo adaptador consumidor del fanout; prueba E2E (Principio IV) que abre una conexión SSE
real contra el controller, fuerza una transición de estado de punta a punta, y valida que el evento
llega con el filtro correcto aplicado

**Target Platform**: mismo objetivo Kubernetes del resto del componente. El diseño debe funcionar
correctamente con N réplicas del mismo deployable (ADR-0006 ya documenta que ingesta y despacho
comparten proceso) — el fanout existente es precisamente lo que permite esto sin agregar un almacén
compartido nuevo

**Project Type**: hexagonal — nuevo puerto de entrada (`SubscribeToNotificationUpdatesUseCase`) y
puerto de salida (`NotificationUpdatesPort`) en `core`; nuevo adaptador RabbitMQ de entrada
(consumidor del fanout) y un controller nuevo, `NotificationLiveUpdatesController`, con el
endpoint SSE, en `infrastructure`

**Performance Goals**: SC-001 (cambio de estado reflejado en ≤5 s), SC-003 (resincronización tras
reconexión en ≤5 s), SC-005 (≥50 sesiones de dashboard concurrentes por tenant sin degradar la
latencia de SC-001)

**Constraints**: reactivo, no bloqueante — ninguna réplica mantiene una tabla de "qué réplica tiene
qué conexión abierta" (el fanout resuelve esto entregando cada evento a todas); el endpoint SSE no
debe bloquear el event loop de Netty con trabajo síncrono; el estado `IN_PROCESS` es hoy transitorio
y solo vive en memoria durante `DispatchNotificationService.attemptSend` — nunca se persiste de forma
independiente antes de conocerse el resultado del proveedor (una sola escritura a Mongo cubre ambas
transiciones) — esta historia no cambia ese flujo de despacho para persistirlo por separado (ver
Assumptions)

**Scale/Scope**: un mecanismo de push nuevo, tres eventos de dominio nuevos que cierran una brecha ya
existente (`NotificationRecoverable`, `NotificationRequeued`, `NotificationDiscarded`), máxima
reutilización de tipos ya existentes (`NotificationSearchResult`, `NotificationRepository`,
`NotificationSearchCriteria`) — ninguna migración de datos, ningún campo nuevo en `NotificationDocument`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. Todo lo nuevo es: un puerto de entrada +
  caso de uso en `core` (sin dependencias de Spring — `Flux`/`Mono` de Reactor ya son parte del
  vocabulario de `core` en los puertos existentes), un puerto de salida nuevo (`NotificationUpdatesPort`)
  implementado por un adaptador RabbitMQ en `infrastructure`, y un controller REST nuevo
  (`NotificationLiveUpdatesController`). Ningún framework se filtra a `core`: los 3 eventos de dominio nuevos son records
  simples, igual que los 4 ya existentes.
- **II. Contract-first, API orientada a acciones** — PASS condicionado a Phase 1: `GET
  /notifications:subscribe` es una operación nueva, no CRUD natural (abre un stream, no consulta ni
  muta un recurso) — usa el patrón `recurso:accion` ya establecido por `/notifications:sendBatch` en
  el contrato existente. Se agrega al contrato antes que el controller.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación).
- **IV. Calidad verificada, no declarada** — PASS condicionado: requiere la prueba E2E real descrita
  en Testing arriba, no solo pruebas unitarias del caso de uso y de los eventos de dominio nuevos.
  Cobertura ≥80 %/≥70 % para los archivos nuevos.
- **V. Trazabilidad en git** — rama `feature/HU2-072-live-dashboard-updates` (HU2-072, "Panel en
  vivo: notificaciones aparecen en tiempo real"), commits de una sola línea.
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PASS. `spec.md` clarificado (2
  preguntas resueltas) y validado (checklist 100 %), este plan es el siguiente artefacto.
- **VII. Sin atajos** — atención explícita, no oculta: el estado `IN_PROCESS` no se persiste de forma
  independiente hoy (ver Constraints); esta historia expone en tiempo real los estados que sí llegan
  a persistirse y documenta la limitación en vez de fingir que la cubre. Cambiar el flujo de despacho
  para persistir `IN_PROCESS` de forma independiente introduciría un riesgo nuevo (notificaciones
  huérfanas en `IN_PROCESS` sin un mecanismo de recuperación, análogo al que ya existe para `PENDING`
  huérfano) que es una historia aparte, no algo que deba colarse sin análisis dentro de esta.
- **VIII. Confiabilidad y durabilidad de notificaciones** — PASS. Esta historia es de solo lectura
  respecto al ciclo de vida de la notificación — no cambia cómo se acepta, despacha, reintenta o
  persiste. Cerrar la brecha de eventos de dominio (`Recoverable`/`Requeued`/`Discarded`) en realidad
  refuerza este principio (más trazabilidad), no lo arriesga.
- **IX. Observabilidad y trazabilidad** — PASS directo — esta historia es, en esencia, la primera vez
  que se consume la infraestructura de eventos de dominio que este principio motivó.
- **Restricciones técnicas** — reactivo/no bloqueante (PASS, todo `Flux`/`Mono`); multi-réplica (PASS,
  ver Decisión 2 de research.md — el fanout existente resuelve esto sin almacén compartido nuevo);
  ningún bloqueo optimista adicional (no se muta ningún agregado nuevo desde este flujo de lectura);
  ninguna credencial nueva; el adaptador RabbitMQ solo conoce `notificationId` (no rehidrata) y el
  log de cada actualización entregada vive en `NotificationLiveUpdatesController`, que conoce el
  `tenantId` de la suscripción, e incluye ambos identificadores siguiendo el Principio IX.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/006-dashboard-tiempo-real/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml
└── + GET /notifications:subscribe (nuevo, patrón recurso:accion) — mismos parámetros de filtro que
    GET /notifications (sin limit/offset), respuesta text/event-stream

core/src/main/java/co/edu/uco/notification/core/domain/event/
├── DomainEvent.java                # + 3 nuevos en la lista sealed permits
├── NotificationRecoverable.java    # nuevo: cierra la brecha de markRecoverable()
├── NotificationRequeued.java       # nuevo: cierra la brecha de requeue()
└── NotificationDiscarded.java      # nuevo: cierra la brecha de discard()

core/src/main/java/co/edu/uco/notification/core/domain/
└── Notification.java                # markRecoverable()/requeue()/discard() registran su evento,
                                      # igual que ya hacen accept()/markQueued()/markDelivered()/
                                      # markFailed() — sin cambiar ninguna firma pública

core/src/main/java/co/edu/uco/notification/core/port/out/
└── NotificationUpdatesPort.java     # nuevo: Flux<NotificationId> updates() — fuente de "algo cambió"

core/src/main/java/co/edu/uco/notification/core/port/in/
├── SubscribeToNotificationUpdatesUseCase.java  # nuevo: puerto de entrada
├── SubscribeToNotificationUpdatesQuery.java    # nuevo: tenantId + mismos filtros que la búsqueda
├── NotificationLiveUpdate.java                 # nuevo: NotificationSearchResult + LiveUpdateAction
└── LiveUpdateAction.java                       # nuevo: enum UPSERT, REMOVE

core/src/main/java/co/edu/uco/notification/core/usecase/
└── SubscribeToNotificationUpdatesService.java  # nuevo: snapshot inicial (reutiliza
                                                 # NotificationRepository.search) + Flux en vivo desde
                                                 # NotificationUpdatesPort, rehidratando cada
                                                 # notificationId vía findById y evaluando el filtro

core/src/main/java/co/edu/uco/notification/core/repository/
└── NotificationSearchCriteria.java  # + método matches(Notification) — evaluación en memoria de la
                                      # misma regla de filtrado que el adaptador Mongo aplica como
                                      # consulta dinámica, para no duplicar la definición del filtro

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/
├── RabbitConfig.java                 # + AnonymousQueue y Binding nuevos hacia el FanoutExchange
│                                      # notificationEventsExchange ya declarado (sin exchange nueva)
└── UseCaseConfig.java                # + bean subscribeToNotificationUpdatesUseCase(...)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/rabbit/
└── NotificationUpdatesRabbitAdapter.java  # nuevo: implementa NotificationUpdatesPort — @RabbitListener
                                            # de la cola anónima, extrae notificationId, lo emite a un
                                            # Sinks.Many local compartido por todas las conexiones SSE
                                            # de esa réplica

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/
├── NotificationLiveUpdatesController.java  # nuevo: GET /notifications:subscribe (SSE) — clase propia,
│                                            # sin @RequestMapping de clase (ver nota abajo)
└── NotificationLiveUpdateResponse.java     # nuevo: DTO del evento SSE (notificación + acción)

core/src/test/java/co/edu/uco/notification/core/domain/event/
├── NotificationRecoverableTest.java  # nuevo
├── NotificationRequeuedTest.java     # nuevo
└── NotificationDiscardedTest.java    # nuevo

core/src/test/java/co/edu/uco/notification/core/domain/
└── NotificationTest.java             # + asserts de que markRecoverable/requeue/discard registran su evento

core/src/test/java/co/edu/uco/notification/core/usecase/
└── SubscribeToNotificationUpdatesServiceTest.java  # nuevo (StepVerifier)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/rabbit/
└── NotificationUpdatesRabbitAdapterTest.java  # nuevo, Testcontainers (RabbitMQ real)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/
└── NotificationLiveUpdatesE2ETest.java  # nuevo — prueba E2E del Principio IV
```

**Structure Decision**: Sigue el mismo patrón ya establecido por `SearchNotificationsUseCase`
(puerto + query + vista + servicio en `core`, DTOs + controller en `infrastructure`), reutilizando
`NotificationSearchResult` como la forma de cada elemento entregado en vivo en vez de crear una vista
paralela. Lo genuinamente nuevo es el puerto de salida `NotificationUpdatesPort` (no existía ninguna
fuente de "cambios en vivo" en el proyecto) y su adaptador RabbitMQ, que consume por primera vez el
`FanoutExchange` `notification.events.exchange` — infraestructura ya declarada en `RabbitConfig.java`
pero sin ningún consumidor hasta ahora. No se crea ningún módulo ni paquete nuevo.

**Nota de implementación**: el endpoint SSE no pudo agregarse como método de `NotificationController`
(la idea original). Spring combina siempre el `@RequestMapping("/notifications")` de la clase con el
patrón del método insertando un separador `/`, así que ningún método bajo esa clase puede producir la
ruta exacta `/notifications:subscribe` del contrato (solo `/notifications/:subscribe`, con un `/` de
más). Se resolvió con una clase de controller nueva sin `@RequestMapping` de clase,
`NotificationLiveUpdatesController`, cuyo único método usa la ruta absoluta literal — evita la
combinación de rutas por completo.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
