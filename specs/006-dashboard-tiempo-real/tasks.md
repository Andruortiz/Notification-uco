---

description: "Task list template for feature implementation"
---

# Tasks: Ver notificaciones en tiempo real en el dashboard

**Input**: Design documents from `/specs/006-dashboard-tiempo-real/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/subscribe-notifications.yaml](./contracts/subscribe-notifications.yaml), [quickstart.md](./quickstart.md)

**Tests**: Incluidas — el Principio IV exige al menos una prueba end-to-end bloqueante para todo flujo
observable, y esta historia además introduce un consumidor de RabbitMQ nuevo que necesita probarse
contra el broker real, no solo con mocks.

**Organization**: Tres historias de usuario, en el orden de prioridad de `spec.md` — US1 (P1, MVP): ver
el ciclo de vida completo de una notificación en vivo. US2 (P2): la vista filtrada entra/sale sola.
US3 (P3): resincronización automática tras una reconexión.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: A qué historia de usuario pertenece la tarea (US1/US2/US3)
- Cada descripción incluye la ruta de archivo exacta

## Path Conventions

`core/` para los eventos de dominio, el puerto de suscripción y el caso de uso; `infrastructure/` para
el adaptador RabbitMQ consumidor, el método SSE del controller, los DTOs y el contrato OpenAPI — mismo
patrón ya usado por `SearchNotificationsUseCase` (HU2-025).

---

## Phase 1: Setup

- [x] T001 [P] Confirmar que `spring-boot-starter-amqp` (para `AnonymousQueue`) y
      `spring-boot-starter-webflux` (para `Flux<ServerSentEvent<T>>`) ya presentes en
      `infrastructure/pom.xml` cubren todo lo necesario — no se espera ninguna dependencia nueva, solo
      verificación

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Contrato, brecha de eventos de dominio cerrada, y el consumidor del fanout existente —
nada de esto es específico de una historia, las tres lo necesitan

**⚠️ CRITICAL**: Ninguna historia puede implementarse hasta que esta fase esté completa — en
particular, el contrato debe actualizarse primero (Principio II), y sin el cierre de la brecha de
eventos (T003-T011) las transiciones `RECOVERABLE`/reencolado/`DISCARDED` seguirían siendo invisibles
sin importar qué tan bien funcione el resto del mecanismo

- [x] T002 [P] Aplicar [contracts/subscribe-notifications.yaml](./contracts/subscribe-notifications.yaml)
      a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: agregar el path
      `GET /notifications:subscribe` (mismos filtros que `GET /notifications`, sin `limit`/`offset`,
      respuesta `text/event-stream`) y el schema `NotificationLiveUpdate` (Principio II — el contrato
      se actualiza antes que el controller)
- [x] T003 [P] Añadir el record `NotificationRecoverable` en
      `core/src/main/java/co/edu/uco/notification/core/domain/event/NotificationRecoverable.java`
      (mismo shape que `NotificationFailed`: `notificationId` + `occurredOn`, validación con
      `Preconditions.requireNonNull`)
- [x] T004 [P] Añadir el record `NotificationRequeued` en
      `core/src/main/java/co/edu/uco/notification/core/domain/event/NotificationRequeued.java` (mismo
      shape que T003)
- [x] T005 [P] Añadir el record `NotificationDiscarded` en
      `core/src/main/java/co/edu/uco/notification/core/domain/event/NotificationDiscarded.java` (mismo
      shape que T003)
- [x] T006 Extender la lista `permits` del `sealed interface DomainEvent` en
      `core/src/main/java/co/edu/uco/notification/core/domain/event/DomainEvent.java` para incluir
      `NotificationRecoverable`, `NotificationRequeued` y `NotificationDiscarded` (depende de
      T003-T005)
- [x] T007 En `core/src/main/java/co/edu/uco/notification/core/domain/Notification.java`: registrar
      `new NotificationRecoverable(...)` dentro de `markRecoverable(...)`, `new
      NotificationRequeued(...)` dentro de `requeue()`, y `new NotificationDiscarded(...)` dentro de
      `discard()` — mismo patrón exacto que ya usan `markDelivered(...)`/`markFailed(...)` (depende de
      T006)
- [x] T008 [P] Añadir `NotificationRecoverableTest.java` en
      `core/src/test/java/co/edu/uco/notification/core/domain/event/` (mismo patrón que
      `NotificationFailedTest.java`) (depende de T003)
- [x] T009 [P] Añadir `NotificationRequeuedTest.java` en la misma carpeta (depende de T004)
- [x] T010 [P] Añadir `NotificationDiscardedTest.java` en la misma carpeta (depende de T005)
- [x] T011 Actualizar `core/src/test/java/co/edu/uco/notification/core/domain/NotificationTest.java`:
      agregar asserts de que `markRecoverable(...)`, `requeue()` y `discard()` registran su evento
      correspondiente vía `pullEvents()` (depende de T007)
- [x] T012 Añadir el puerto de salida `NotificationUpdatesPort` en
      `core/src/main/java/co/edu/uco/notification/core/port/out/NotificationUpdatesPort.java`:
      `Flux<NotificationId> updates()`
- [x] T013 [P] Añadir el método `matches(Notification notification)` a
      `core/src/main/java/co/edu/uco/notification/core/repository/NotificationSearchCriteria.java`:
      evalúa en memoria la misma regla AND (`recipientId`/`channelType`/`status`/`from`-`to` sobre
      `acceptedAt`) que el adaptador Mongo aplica como consulta dinámica (research.md, Decisión 5)
- [x] T014 [P] Añadir pruebas unitarias de `matches(...)` en
      `core/src/test/java/co/edu/uco/notification/core/repository/NotificationSearchCriteriaTest.java`
      (o crear el archivo si no existe): cada filtro por separado, combinación AND, y el caso sin
      ningún filtro (siempre coincide) (depende de T013)
- [x] T015 [P] Añadir a
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/RabbitConfig.java`
      un bean `AnonymousQueue` y su `Binding` hacia el `FanoutExchange`
      `notificationEventsExchange` ya declarado (sin exchange nueva) — una cola nueva, exclusiva y
      auto-eliminable por cada réplica (research.md, Decisión 2)
- [x] T016 Implementar `NotificationUpdatesRabbitAdapter` en
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/rabbit/NotificationUpdatesRabbitAdapter.java`:
      implementa `NotificationUpdatesPort`; `@RabbitListener` sobre la cola anónima de T015;
      deserializa únicamente el campo `notificationId` del mensaje JSON (ignorando el resto, sin
      necesitar distinguir el tipo concreto del evento); emite a un `Sinks.Many<NotificationId>`
      (multicast, `onBackpressureBuffer`); expone `updates()` como `sink.asFlux()` (depende de T012,
      T015)
- [x] T017 [P] Añadir prueba de integración
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/rabbit/NotificationUpdatesRabbitAdapterTest.java`
      contra RabbitMQ real (Testcontainers, mismo patrón que el resto del proyecto): publica un evento
      de dominio real a `notification.events.exchange` y confirma que `updates()` emite el
      `notificationId` correspondiente (depende de T016) — requirió agregar la dependencia de test
      `org.testcontainers:rabbitmq` a `infrastructure/pom.xml` (no listada originalmente en plan.md;
      el proyecto solo tenía el módulo Testcontainers de MongoDB hasta ahora)
- [x] T018 Confirmar que `HexagonalArchitectureTest`/`ModularityTests` siguen en verde con los tipos
      nuevos de `core` y el adaptador nuevo de `infrastructure` (depende de T003-T017)

**Checkpoint**: Contrato, brecha de eventos cerrada, y consumidor del fanout listos — las tres
historias pueden implementarse

---

## Phase 3: User Story 1 - Ver el ciclo de vida de una notificación sin refrescar (Priority: P1) 🎯 MVP

**Goal**: Un operador con el dashboard abierto ve reflejado cada cambio de estado de una notificación
de su tenant (`PENDING`/`IN_PROCESS`/`DELIVERED`/`RECOVERABLE`/`FAILED`/`DISCARDED`) sin recargar la
página ni repetir una búsqueda.

**Independent Test**: Con una conexión abierta a `GET /notifications:subscribe`, aceptar una
notificación y dejarla progresar por su ciclo de vida; confirmar que cada transición persistida llega
como un evento SSE sin ninguna acción manual del lado del cliente.

### Tests for User Story 1 ⚠️

> **NOTE: Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [x] T019 [P] [US1] Unit test (`StepVerifier`) en
      `core/src/test/java/co/edu/uco/notification/core/usecase/SubscribeToNotificationUpdatesServiceTest.java`:
      la foto inicial se emite como una serie de `UPSERT` a partir de
      `NotificationRepository.search(...)` (mock); cada elemento de `NotificationUpdatesPort.updates()`
      (mock) dispara `findById(...)` (mock) y se traduce en un `NotificationLiveUpdate` con `UPSERT`
      cuando `matches(...)` es verdadero
- [x] T020 [US1] E2E test en
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationLiveUpdatesE2ETest.java`:
      acepta una notificación vía el flujo real (`POST /notifications`), abre
      `GET /notifications:subscribe` (`WebTestClient`, consumiendo el `Flux<ServerSentEvent<...>>`),
      fuerza su progreso hasta un resultado final vía el proveedor simulado, y confirma que el evento
      SSE con el estado final llega sin ninguna solicitud manual adicional (Acceptance Scenarios 1-2;
      Principio IV)

### Implementation for User Story 1

- [x] T021 [P] [US1] Añadir el enum `LiveUpdateAction` en
      `core/src/main/java/co/edu/uco/notification/core/port/in/LiveUpdateAction.java`: `UPSERT`,
      `REMOVE`
- [x] T022 [P] [US1] Añadir el record `NotificationLiveUpdate` en
      `core/src/main/java/co/edu/uco/notification/core/port/in/NotificationLiveUpdate.java`:
      `NotificationSearchResult notification`, `LiveUpdateAction action`
- [x] T023 [P] [US1] Añadir el record `SubscribeToNotificationUpdatesQuery` en
      `core/src/main/java/co/edu/uco/notification/core/port/in/SubscribeToNotificationUpdatesQuery.java`:
      `tenantId` (obligatorio) + `recipientId`/`channelType`/`status`/`from`/`to` (opcionales) — ver
      data-model.md
- [x] T024 [US1] Añadir la interfaz `SubscribeToNotificationUpdatesUseCase` en
      `core/src/main/java/co/edu/uco/notification/core/port/in/SubscribeToNotificationUpdatesUseCase.java`:
      `Flux<NotificationLiveUpdate> subscribe(SubscribeToNotificationUpdatesQuery query)` (depende de
      T021-T023)
- [x] T025 [US1] Implementar `SubscribeToNotificationUpdatesService` en
      `core/src/main/java/co/edu/uco/notification/core/usecase/SubscribeToNotificationUpdatesService.java`:
      construye un `NotificationSearchCriteria` desde la query (tope de 200 para la foto inicial,
      research.md Decisión 6), emite la foto vigente como `UPSERT` vía `repository.search(...)`, y
      concatena el `Flux` en vivo mapeando cada `NotificationUpdatesPort.updates()` a través de
      `repository.findById(...)` + `criteria.matches(...)` hacia `UPSERT`/`REMOVE` (depende de T012,
      T013, T016, T024)
- [x] T026 [US1] Añadir el bean `subscribeToNotificationUpdatesUseCase(...)` en
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/UseCaseConfig.java`
      (depende de T025)
- [x] T027 [P] [US1] Añadir el DTO `NotificationLiveUpdateResponse` en
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationLiveUpdateResponse.java`:
      mapea `NotificationLiveUpdate` al shape `NotificationLiveUpdate` del contrato (reutiliza
      `NotificationHistoryItemResponse`/`DeliveryAttemptResponse` ya existentes para el campo
      `notification`)
- [x] T028 [US1] Añadir el endpoint `GET /notifications:subscribe`: lee `X-Tenant-Id` y los filtros
      opcionales, construye la query, devuelve `Flux<ServerSentEvent<NotificationLiveUpdateResponse>>`
      intercalado con un comentario de keep-alive cada 15 s (research.md Decisión 7) (depende de T026,
      T027) — desviación de plan.md: **no** se agregó como método de `NotificationController.java`
      sino en una clase nueva, `NotificationLiveUpdatesController.java`, sin `@RequestMapping` de
      clase. Motivo descubierto en implementación: Spring siempre combina el prefijo `@RequestMapping`
      de la clase con el patrón del método insertando un separador `/`, por lo que un método bajo
      `@RequestMapping("/notifications")` nunca puede producir la ruta exacta `/notifications:subscribe`
      (sin `/` antes de los dos puntos) — solo `/notifications/:subscribe`, que no coincide con el
      contrato. Una clase sin prefijo de clase no combina nada y expone la ruta literal.
- [ ] T029 [US1] Confirmar cobertura ≥80 % líneas / ≥70 % ramas (Principio IV) para todos los archivos
      nuevos de esta historia

**Checkpoint**: User Story 1 completa y probable de forma independiente — MVP entregable

---

## Phase 4: User Story 2 - La vista filtrada se mantiene al día automáticamente (Priority: P2)

**Goal**: Con filtros activos en la suscripción, una notificación entra o sale de la vista visible en
vivo cuando su estado (u otro atributo filtrable) empieza o deja de cumplirlos, sin repetir la
búsqueda.

**Independent Test**: Suscribirse con un filtro (ej. `status=RECOVERABLE`); forzar que una
notificación fuera de esa vista pase a cumplirlo (esperar `UPSERT`) y que una visible deje de
cumplirlo (esperar `REMOVE`).

### Tests for User Story 2 ⚠️

> **NOTE: Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [x] T030 [P] [US2] Extender
      `core/src/test/java/co/edu/uco/notification/core/usecase/SubscribeToNotificationUpdatesServiceTest.java`
      (T019) con casos donde una notificación rehidratada dejó de cumplir el filtro activo (espera
      `REMOVE`) y donde empieza a cumplirlo (espera `UPSERT`), incluyendo el caso sin ningún filtro
      (todo cumple siempre)
- [x] T031 [US2] Añadir el E2E test
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationLiveUpdatesFilteredE2ETest.java`:
      suscribirse con `status=FAILED`, forzar una notificación ajena al filtro hacia `FAILED`
      (esperar `UPSERT`), y forzar una notificación visible bajo `status=RECOVERABLE` hacia
      `DELIVERED` (esperar `REMOVE`) (Acceptance Scenarios US2; Principio IV)

### Implementation for User Story 2

- [x] T032 [US2] Confirmar que el método `subscribe(...)` de `NotificationController.java` (T028)
      propaga cada combinación de filtros sin perder ninguno — si algún filtro no llega correctamente
      al `SubscribeToNotificationUpdatesQuery`, corregirlo aquí
- [ ] T033 [US2] Confirmar cobertura ≥80 % líneas / ≥70 % ramas para los archivos nuevos/modificados
      de esta historia

**Checkpoint**: User Story 1 y 2 funcionan de forma independiente

---

## Phase 5: User Story 3 - Recuperar la vista tras una desconexión temporal (Priority: P3)

**Goal**: Al reconectar después de una caída, el dashboard recibe primero el estado vigente completo
(incluyendo cualquier cambio ocurrido durante la desconexión) antes de retomar el flujo en vivo.

**Independent Test**: Abrir la conexión, cerrarla, cambiar el estado de una notificación mientras está
"desconectado", reconectar, y confirmar que la primera ráfaga de eventos refleja el estado actual, no
el previo a la desconexión.

### Tests for User Story 3 ⚠️

> **NOTE: Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [x] T034 [US3] Añadir el E2E test
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationLiveUpdatesReconnectE2ETest.java`:
      conectar, recibir actualizaciones, cancelar la suscripción SSE (simulando una caída), cambiar el
      estado de una notificación mientras está desconectado, reconectar con una solicitud nueva, y
      confirmar que la primera ráfaga de `UPSERT` refleja el estado actual incluyendo ese cambio
      (Acceptance Scenarios US3; Principio IV)
- [x] T035 [P] [US3] Unit test (`StepVerifier`) confirmando que
      `SubscribeToNotificationUpdatesService.subscribe(...)` (T025) siempre arranca con una llamada
      nueva a `repository.search(...)` en cada invocación, sin importar llamadas previas — sin caché
      ni estado retenido entre conexiones

### Implementation for User Story 3

- [ ] T036 [US3] Confirmar cobertura ≥80 % líneas / ≥70 % ramas para los archivos nuevos/modificados
      de esta historia — se espera que no haga falta código de producción nuevo (research.md, Decisión
      6: la resincronización es una consecuencia del diseño de US1, no un mecanismo aparte)

**Checkpoint**: Las tres historias de usuario funcionan de forma independiente

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T037 [P] Confirmar Spotless/SpotBugs/FindSecBugs sin hallazgos sobre todos los archivos nuevos o
      modificados de esta historia
- [ ] T038 [P] Confirmar que los logs de `NotificationUpdatesRabbitAdapter` y
      `SubscribeToNotificationUpdatesService` incluyen `notificationId` y `tenantId` (Principio IX),
      sin credenciales ni el contenido completo de la notificación
- [ ] T039 Ejecutar manualmente los escenarios de [quickstart.md](./quickstart.md) (ciclo de vida en
      vivo, vista filtrada, resincronización tras reconexión, aislamiento por tenant, multi-réplica)
      contra el `docker compose` local
- [ ] T040 [P] Confirmar que `Front-Notification` (repositorio separado) podría consumir el nuevo
      endpoint SSE sin cambios adicionales del lado del backend — no es parte de esta historia, pero
      vale la pena verificar que el contrato resultante es consumible sin sorpresas

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias — puede iniciar de inmediato
- **Foundational (Phase 2)**: depende de Setup — BLOQUEA las tres historias; en particular T002
  (contrato) antes de T027/T028 (DTO/controller), T006-T007 (eventos wireados) antes de T011, y
  T012/T015/T016 (puerto + cola + adaptador) antes de T025 (caso de uso)
- **User Story 1 (Phase 3)**: depende de Foundational — ninguna dependencia de US2/US3
- **User Story 2 (Phase 4)**: depende de Foundational y de que exista `SubscribeToNotificationUpdatesService`
  (T025) para extenderlo — reutiliza el mismo mecanismo, no lo duplica
- **User Story 3 (Phase 5)**: depende de Foundational y de T025 igual que US2 — mayormente pruebas,
  ya que la resincronización es una propiedad emergente del diseño de US1
- **Polish (Phase 6)**: depende de que las tres historias estén completas

### Within Each User Story

- Las pruebas (T019-T020, T030-T031, T034-T035) se escriben y deben FALLAR antes de implementar
- Puerto de entrada (T021-T024) antes del caso de uso (T025)
- Caso de uso (T025) antes del bean de wiring (T026) y del controller (T028)
- DTO de respuesta (T027) puede construirse en paralelo con el caso de uso, ambos necesarios antes de
  T028

### Parallel Opportunities

- T002 (contrato), T003/T004/T005 (los 3 eventos nuevos), T012 (puerto de salida), T013 (método
  `matches`) y T015 (cola + binding) en paralelo — archivos distintos, sin dependencias entre sí
- T008/T009/T010 (pruebas de los 3 eventos nuevos) en paralelo
- T019 (unit test) puede escribirse en paralelo con T020 (E2E) — archivos distintos
- T021/T022/T023 (tipos del puerto de entrada) en paralelo — capas distintas de un mismo paquete, sin
  dependencia entre sí
- T037/T038/T040 en Polish, en paralelo

---

## Implementation Strategy

### MVP First (User Story 1 solamente)

1. Completar Phase 1: Setup
2. Completar Phase 2: Foundational (bloqueante — incluye el contrato y el cierre de la brecha de
   eventos)
3. Completar Phase 3: User Story 1
4. **DETENER y VALIDAR**: correr T020 (E2E) y el primer escenario de quickstart.md de forma
   independiente
5. Esto ya deja al frontend (`Front-Notification`) con un endpoint real de actualizaciones en vivo,
   aunque todavía sin el comportamiento fino de filtros (US2) ni la garantía explícita de
   resincronización probada (US3, aunque ya funciona por diseño)

### Incremental Delivery

1. Completar Setup + Foundational → base lista
2. Agregar User Story 1 → probar de forma independiente → MVP
3. Agregar User Story 2 → probar de forma independiente (mayormente pruebas nuevas sobre el mismo
   mecanismo)
4. Agregar User Story 3 → probar de forma independiente (mayormente pruebas nuevas)
5. Cada historia agrega valor sin romper las anteriores

---

## Notes

- [P] tareas = archivos distintos, sin dependencias pendientes
- [Story] mapea la tarea a su historia de usuario para trazabilidad
- Verificar que las pruebas fallan antes de implementar
- Commit por tarea o grupo lógico, una sola rama para toda la historia
  (`006-dashboard-tiempo-real`, o el nombre de rama por historia que se le asigne, ADR-0015) —
  commits de una sola línea (Principio V)
- Detenerse en cada checkpoint para validar la historia de forma independiente
- Evitar: tareas vagas, conflictos de archivo simultáneos, dependencias cruzadas entre historias que
  rompan su independencia
