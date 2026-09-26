---

description: "Task list for HU2-027 + HU2-028"
---

# Tasks: Reintentar envío manualmente y distinguir el reintento manual del automático

**Input**: Design documents from `/specs/011-reintentar-envio-manual/`

**Prerequisites**: plan.md, spec.md (Q1–Q4 con respuesta recomendada adoptada), research.md, data-model.md,
contracts/api-notificaciones-cambios.md, quickstart.md.

**Tests**: exigidas por el plan (Principio IV): unitarias en `core` con `StepVerifier`, pruebas del
consumidor y del publicador, y un E2E explícito (`NotificationRetryE2ETest`) con Mongo y RabbitMQ reales.
Orden por tarea: prueba → verla fallar por la razón correcta → implementar → ejecutar la clase →
`spotless:apply`.

**Organization**: fase fundacional (el origen viaja del encolado al intento), luego una fase por historia
de usuario del spec (US1–US3).

## Path Conventions

- `core-main/` = `core/src/main/java/co/edu/uco/notification/core/`
- `core-test/` = `core/src/test/java/co/edu/uco/notification/core/`
- `infra-main/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/`
- `infra-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/`

---

## Phase 1: Setup

**Purpose**: contrato público primero (Principio II).

- [ ] T001 Actualizar solo las descripciones de `retryNotification` (operación, 202, 400, 404) en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` según `contracts/api-notificaciones-cambios.md`; sin cambios estructurales; commit propio antes del controller

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: que el origen del intento viaje desde quien encola hasta el intento registrado
(research.md, Decisiones 1 y 6). Bloquea a todas las historias.

- [ ] T002 Prueba `core-test/usecase/DispatchNotificationServiceTest.java`: las llamadas existentes pasan a `dispatch(id, AttemptOrigin.AUTOMATIC)` y afirman `origin == AUTOMATIC` en el intento; nuevos casos con `MANUAL` para las cuatro ramas (aceptado → DELIVERED, recuperable → RECOVERABLE, recuperable con presupuesto agotado → FAILED, permanente → FAILED) afirman `origin == MANUAL`; `dispatch(id, null)` → `NullPointerException`
- [ ] T003 Cambiar `core-main/port/in/DispatchNotificationUseCase.java` a `dispatch(NotificationId, AttemptOrigin)` y `core-main/usecase/DispatchNotificationService.java` para usar el origen recibido en las cuatro ramas de `applyOutcome` — depende de T002
- [ ] T004 [P] Prueba `infra-test/adapter/out/rabbit/NotificationRabbitPublisherTest.java`: `enqueueForDispatch(n, MANUAL)` y `(n, AUTOMATIC)` escriben el encabezado `x-attempt-origin` con el nombre del enum y conservan `messageId`; `enqueueForDispatch(n, null)` → `NullPointerException`
- [ ] T005 Cambiar `core-main/port/out/NotificationEventPublisherPort.java` a `enqueueForDispatch(Notification, AttemptOrigin)`; agregar `ATTEMPT_ORIGIN_HEADER = "x-attempt-origin"` en `infra-main/config/RabbitTopologyProperties.java`; implementarlo en `infra-main/adapter/out/rabbit/NotificationRabbitPublisher.java`; pasar `AttemptOrigin.AUTOMATIC` en `core-main/usecase/SendNotificationService.java` y en los dos encolados de `core-main/usecase/RequeuePendingNotificationsService.java`; ajustar stubs y `verify` en `core-test/usecase/SendNotificationServiceTest.java`, `core-test/usecase/RequeuePendingNotificationsServiceTest.java` (afirmando `AUTOMATIC`) e `infra-test/adapter/out/mongo/NotificationMongoAdapterTest.java` — depende de T004
- [ ] T006 [P] Prueba `infra-test/adapter/in/rabbit/NotificationDispatchListenerTest.java`: encabezado `MANUAL` → `dispatch(id, MANUAL)`; encabezado ausente (`null`) → `dispatch(id, AUTOMATIC)`; valor desconocido → `IllegalArgumentException` sin llamar al caso de uso; el error del caso de uso se propaga
- [ ] T007 Cambiar `infra-main/adapter/in/rabbit/NotificationDispatchListener.java` para recibir `@Header(name = ATTEMPT_ORIGIN_HEADER, required = false)` y resolver el origen; ajustar el stub `dispatch(any())` → `dispatch(any(), any())` en `infra-test/config/DeadLetterQueueE2ETest.java` e `infra-test/config/RabbitRetryConfigCustomAttemptsTest.java` — depende de T003, T006

**Checkpoint**: `./mvnw -B -ntp -pl core test` verde; el despacho existente sigue registrando `AUTOMATIC`.

---

## Phase 3: User Story 1 - Forzar un nuevo intento sobre una notificación fallida (Priority: P1) 🎯 MVP

**Goal**: `POST /notifications/{id}:retry` devuelve 202 con PENDING para FAILED/RECOVERABLE, 400 para los
demás estados, 404 si no existe; la notificación vuelve a intentarse.

**Independent Test**: `NotificationRetryE2ETest` — FAILED → reintento → 202 PENDING → DELIVERED en ≤ 5 s.

- [ ] T008 [US1] Mover la regla de la vista de estado a la fábrica `NotificationStatusView.from(Notification)` en `core-main/port/in/NotificationStatusView.java` y usarla en `core-main/usecase/GetNotificationStatusService.java` (research.md Decisión 4); `GetNotificationStatusServiceTest` existente debe seguir verde
- [ ] T009 [US1] Prueba `core-test/usecase/RetryNotificationServiceTest.java`: FAILED y RECOVERABLE → vista con PENDING, `save` llamado, eventos (`NotificationRequeued`) publicados, `enqueueForDispatch(saved, MANUAL)`; PENDING, IN_PROCESS, DELIVERED, DISCARDED (parametrizada) → `NotificationNotRetryableException` con el estado en el mensaje y `never()` sobre `save`, `publish` y `enqueueForDispatch`; inexistente → `NotificationNotFoundException`; conflicto de versión en `save` → `NotificationNotRetryableException` sin publicar ni encolar; comando nulo y dependencias nulas → `NullPointerException`
- [ ] T010 [US1] Crear `core-main/exception/NotificationNotRetryableException.java`, `core-main/port/in/RetryNotificationCommand.java`, `core-main/port/in/RetryNotificationUseCase.java` y `core-main/usecase/RetryNotificationService.java` hasta pasar T009 — depende de T005, T008, T009
- [ ] T011 [US1] Registrar el bean `RetryNotificationUseCase` en `infra-main/config/UseCaseConfig.java` — depende de T010
- [ ] T012 [US1] Prueba `infra-test/adapter/in/rest/NotificationControllerTest.java`: `POST /notifications/{id}:retry` con `X-Tenant-Id` construye el comando correcto y responde 202 con `NotificationStatusResponse`; `NotificationNotRetryableException` → 400 con `message`; `NotificationNotFoundException` → 404
- [ ] T013 [US1] Agregar `@PostMapping("/{id}:retry")` en `infra-main/adapter/in/rest/NotificationController.java` y el manejo de `NotificationNotRetryableException` → 400 en `infra-main/adapter/in/rest/NotificationExceptionHandler.java` — depende de T011, T012
- [ ] T014 [US1] **E2E** `infra-test/adapter/in/rest/NotificationRetryE2ETest.java` (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` Mongo + RabbitMQ + `WebTestClient` + `FakeProviderServer` como proveedor `brevo`): (a) primer intento con 400 del proveedor → FAILED; proveedor pasa a 201; reintento → 202 con `status = PENDING`; DELIVERED observado en ≤ 5 s con aserción explícita de `Duration`; dos llamadas al proveedor; (b) reintento sobre DELIVERED → 400, sin llamada nueva al proveedor ni intento nuevo; (c) id inexistente → 404 — depende de T007, T013

**Checkpoint**: MVP entregable.

---

## Phase 4: User Story 2 - Aislamiento por tenant en el reintento (Priority: P1)

**Goal**: un tenant no puede reintentar ni descubrir notificaciones de otro.

**Independent Test**: dos tenants, reintento cruzado → 404 idéntico a inexistente, sin efectos; control positivo del dueño.

- [ ] T015 [US2] Caso en `core-test/usecase/RetryNotificationServiceTest.java`: notificación FAILED de `tenant-a` pedida por `tenant-b` → `NotificationNotFoundException` y `never()` sobre `save`, `publish` y `enqueueForDispatch`
- [ ] T016 [US2] Caso **E2E** en `infra-test/adapter/in/rest/NotificationRetryE2ETest.java`: FAILED de `tenant-1`; reintento con `tenant-2` → 404 con el mismo `message` que un id inexistente (salvo el id); tras esperar, la notificación sigue FAILED con un solo intento y el proveedor recibió una sola llamada; control positivo: el mismo reintento con `tenant-1` → 202 y la notificación termina DELIVERED

---

## Phase 5: User Story 3 - Distinguir en la auditoría el reintento manual del automático (Priority: P2)

**Goal**: el histórico muestra `origin = MANUAL` en el intento del reintento y `AUTOMATIC` en los demás.

**Independent Test**: tras fallo automático + reintento manual, la búsqueda devuelve dos intentos AUTOMATIC y MANUAL en ese orden.

- [ ] T017 [US3] Caso **E2E** en `infra-test/adapter/in/rest/NotificationRetryE2ETest.java`: tras el flujo de T014(a), `GET /notifications?recipientId=…` del tenant devuelve la notificación con `deliveryAttempts` = [`AUTOMATIC`/`PERMANENT_FAILURE`, `MANUAL`/`ACCEPTED`]
- [ ] T018 [US3] Caso en `core-test/usecase/RequeuePendingNotificationsServiceTest.java`: el reintento automático de una RECOVERABLE cuyo último intento fue MANUAL encola con `AUTOMATIC` (US3, escenario 2)

---

## Phase 6: Concurrencia (FR-010, SC-003)

- [ ] T019 **E2E** en `infra-test/adapter/in/rest/NotificationRetryE2ETest.java`: sobre una FAILED, dos reintentos lanzados en paralelo → exactamente un 202 y un 400; tras llegar a DELIVERED y un margen de espera, el proveedor recibió exactamente una llamada adicional y el histórico tiene exactamente un intento MANUAL

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T020 `./mvnw -B -ntp spotless:apply` y pruebas de arquitectura (`HexagonalArchitectureTest`, `ModularityTests`) en verde
- [ ] T021 `./mvnw -B -ntp clean verify` completo en verde (cobertura ≥ 80 % líneas / ≥ 70 % ramas); si solo fallan `DeadLetterQueueE2ETest` o `RabbitRetryConfigCustomAttemptsTest` por el broker local, repetir con el broker desechable o excluirlas y reportarlo
- [ ] T022 Revisar que no quedan comentarios explicativos en código nuevo, marcar tareas y anotar desviaciones en este archivo; commit de artefactos

---

## Dependencies & Execution Order

- T001 → todo lo demás (contrato primero).
- Fase 2: T002 → T003; T004 → T005; T006 → T007 (T007 también depende de T003). T002, T004 y T006 son pruebas en archivos distintos, paralelizables.
- US1: T008 → T009 → T010 → T011 → T012 → T013 → T014.
- US2 (T015, T016) y US3 (T017, T018) dependen de US1; son independientes entre sí.
- T019 depende de T014.
- Fase 7 al final.

## Parallel Example: Phase 2

```text
T002 DispatchNotificationServiceTest   |  T004 NotificationRabbitPublisherTest  |  T006 NotificationDispatchListenerTest
```

## Implementation Strategy

MVP = Fases 1–3 (reintento funcional de punta a punta). US2 y US3 añaden garantías verificadas sobre el
mismo código (aislamiento y auditoría); la concurrencia se prueba al final sobre el E2E ya montado.
