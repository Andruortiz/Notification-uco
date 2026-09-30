---

description: "Task list for HU2-021"
---

# Tasks: Enviar un lote de notificaciones por HTTP

**Input**: Design documents from `/specs/014-exponer-envio-lote/`

**Prerequisites**: plan.md (Estado: Pendiente, ver "Estado del plan"), spec.md (clarificado), research.md,
data-model.md, contracts/send-notification-batch.yaml, quickstart.md.

**Tests**: Exigidas por el Principio IV: una prueba `@WebFluxTest` del adaptador y una E2E con
Testcontainers + `WebTestClient`. Orden por tarea: prueba -> verla fallar por la razón correcta ->
implementar -> ejecutar la clase -> `spotless:apply`.

**Organization**: una sola historia (US1). Fase 1 = contrato; Fase 3 = US1; Fase 4 = cierre.

## Path Conventions

- `rest/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/`
- `rest-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/`

---

## Phase 1: Setup

**Purpose**: contrato público primero (Principio II).

- [x] T001 Aplicar `contracts/send-notification-batch.yaml` a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: descripción de `sendNotificationBatch` marcada como implementada y respuesta `400` con `ErrorResponse`; sin tocar schemas ni otras operaciones. Commit propio antes del controller

---

## Phase 2: Foundational

Sin tareas: el caso de uso, su bean en `UseCaseConfig`, `NotificationExceptionHandler` y el DTO de
elemento `SendNotificationRequest` ya existen.

---

## Phase 3: User Story 1 - Enviar varias notificaciones en una sola solicitud (P1) MVP

**Goal**: `POST /notifications:sendBatch` responde `202` con `batchId` y un resultado por elemento en
orden; rechazo estructural `400` sin registros nuevos.

**Independent Test**: spec.md, User Story 1, escenarios 1-6, y edge cases.

- [x] T002 [P] [US1] Crear los records `rest/SendNotificationBatchRequest.java` (`batchId`, `List<SendNotificationRequest> items`), `rest/BatchItemResultResponse.java` (`externalId`, `outcome`, `notificationId`, `rejectionReason`; fábrica `from(BatchItemResult)`) y `rest/BatchAcceptedResponse.java` (`batchId`, `results`; fábrica `from(BatchAcceptedResult)`) según data-model.md
- [x] T003 [US1] Prueba `rest-test/NotificationBatchControllerTest.java` (`@WebFluxTest(controllers = NotificationBatchController.class)`, `@MockBean SendNotificationBatchUseCase`, mismo patrón que `NotificationControllerTest`): `202` con los tres resultados (`ACCEPTED`, `DUPLICATE`, `REJECTED` con `notificationId` nulo y motivo) en orden; comando capturado con tenant, `batchId` enviado y elementos traducidos (incluido `subject` nulo); sin `batchId` -> comando con `batchId` nulo; `400` y caso de uso nunca invocado para: `items` vacío, `items` ausente, elemento nulo, `priority` ausente, `priority` desconocida, campo obligatorio en blanco, `batchId` en blanco, cabecera `X-Tenant-Id` ausente — depende de T002
- [x] T004 [US1] Implementar `rest/NotificationBatchController.java` (`@RestController`, `@PostMapping("/notifications:sendBatch")`, `@RequestHeader("X-Tenant-Id")`, `ResponseEntity.status(ACCEPTED)`; validación estructural antes de invocar el caso de uso, `Preconditions.requireNonBlank` antes de `Priority.valueOf`) hasta pasar T003 — depende de T003
- [x] T005 [US1] Prueba E2E `rest-test/NotificationBatchE2ETest.java` (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` con MongoDB y RabbitMQ + `WebTestClient`, catálogo por defecto, `externalId` y tenants únicos por prueba): (a) lote de 3 EMAIL con `batchId` propio -> `202`, mismo `batchId`, 3 `ACCEPTED` en el orden de la solicitud y cada `notificationId` llega a `DELIVERED` por `GET /notifications/{id}` (SC-001, SC-002); (b) lote mixto: EMAIL válido + canal `FAX` + SMS de 161 caracteres + `externalId` ya aceptado antes -> `ACCEPTED`, `REJECTED` con motivo, `REJECTED` con motivo, `DUPLICATE` con el `notificationId` original, sin `batchId` -> uno generado (SC-003); (c) dos tenants con los mismos `externalId` -> ambos `ACCEPTED` con ids distintos y `GET` cruzado -> `404` (SC-004); (d) lote con un elemento sin `priority` -> `400` y 0 documentos para ese tenant, con (a) como control positivo (SC-005) — depende de T004. Desviación: esta prueba no existía cuando una revisión de código encontró H1-H4 (ver Fase 3.1); se agregó junto con las correcciones, no antes
- [x] T006 [US1] Verificar el riesgo de plan.md "Riesgos conocidos" (mismo `externalId` dos veces en un lote): confirmado por la revisión de código (no por esta E2E, que no llegó a ejercitar la carrera real de concurrencia). Desviación: en vez de reportarlo como pendiente fuera de alcance, se corrigió en el núcleo — ver Fase 3.1, T010-T013 — porque la corrección era acotada y dejarla como defecto conocido violaba el Principio VII

---

## Phase 3.1: Corrección de defectos encontrados en revisión de código (H1-H4)

**Contexto**: una revisión de código posterior a T001-T006 encontró que el controller de T004 nunca
llegó a invocar el caso de uso real (quedó en `throw new UnsupportedOperationException()`, H1), y que
una vez conectado de verdad aparecían tres defectos más en el núcleo: un fallo de infraestructura
tumbaba el lote completo (H3), una carrera de idempotencia intra-lote no se resolvía como `DUPLICATE`
(H4), y no había persistencia del `batchId` (H2). Ver plan.md "Desviaciones del plan".

- [x] T010 [H1] Conectar `NotificationBatchController.sendBatch` con `SendNotificationBatchUseCase` de verdad (traducir `SendNotificationBatchRequest` a `SendNotificationBatchCommand`, invocar el caso de uso, mapear con `BatchAcceptedResponse.from`, responder `202`); `NotificationBatchControllerTest` (T003) ya definía el comportamiento esperado y no cambió
- [x] T011 [H3] Agregar `BatchItemOutcome.FAILED` y `BatchItemResult.failed(ExternalId, String)`; en `SendNotificationBatchService.processItem`, un segundo `onErrorResume(Throwable.class, ...)` después del de rechazo de negocio para que una excepción no reconocida (por ejemplo `AttachmentInspectionUnavailableException`) quede como `FAILED` en vez de tumbar el `Mono` del lote completo; actualizar el enum `outcome` y las descripciones de `notificationId`/`rejectionReason` en `api-notificaciones.yaml`; pruebas en `SendNotificationBatchServiceTest` con un solo ítem fallido y con varios ítems del mismo lote donde uno falla por infraestructura y los demás se aceptan, verificando ambos resultados en orden sin que el `Mono` falle
- [x] T012 [H4] Agregar `core/exception/NotificationAlreadyAcceptedException`; `NotificationMongoAdapter.save()` traduce `DuplicateKeyException` a esa excepción (antes solo traducía `OptimisticLockingFailureException`); `SendNotificationService.acceptAndDispatch` envuelve `notificationRepository.save(notification)` con `.onErrorResume(NotificationAlreadyAcceptedException.class, ...)` que resuelve como el mismo camino de duplicado que una búsqueda previa exitosa; prueba unitaria en `SendNotificationServiceTest` que simula la carrera (primer `findByTenantAndExternalId` vacío, `save()` falla con la nueva excepción, segundo `findByTenantAndExternalId` devuelve la notificación existente)
- [x] T013 [H2] Puerto `core/repository/NotificationBatchRepository`; adaptador Mongo `NotificationBatchMongoAdapter` con `NotificationBatchDocument` (colección `notification_batches`, índice único `{tenantId, batchId}`) y `BatchItemResultDocument`; wire en `UseCaseConfig` (el bean de `SendNotificationBatchUseCase` recibe también el repositorio); `SendNotificationBatchService.sendBatch` persiste el registro después de `collectList()` y antes de devolver el resultado, con `.onErrorResume(ex -> Mono.empty())` para que un fallo de esa escritura no tumbe la respuesta; prueba de integración `NotificationBatchMongoAdapterTest` (Testcontainers) con aislamiento entre dos tenants con el mismo `batchId`
- [x] T014 Actualizar `spec.md § Assumptions` y `plan.md` para reflejar que el `batchId` ya se persiste y que la carrera de idempotencia intra-lote ya no queda pendiente

**Checkpoint**: `NotificationBatchControllerTest` y `NotificationBatchE2ETest` en verde.

---

## Phase 4: Polish & Cross-Cutting Concerns

- [ ] T007 `./mvnw -B -ntp spotless:apply` y revisar que el código nuevo no tenga comentarios (Principio III)
- [ ] T008 `HexagonalArchitectureTest` y `ModularityTests` en verde
- [ ] T009 `./mvnw -B -ntp clean verify` completo en verde (cobertura >= 80 % líneas y >= 70 % ramas, Spotless, SpotBugs/FindSecBugs)

---

## Dependencies & Execution Order

- T001 antes de T004 (contract-first).
- T002 -> T003 -> T004 -> T005 -> T006.
- T007-T009 al final.

## Parallel Opportunities

- T001 y T002 son independientes (archivos distintos).

## Implementation Strategy

Historia única y pequeña: contrato, DTOs, prueba del adaptador, controller, E2E, puerta completa.
