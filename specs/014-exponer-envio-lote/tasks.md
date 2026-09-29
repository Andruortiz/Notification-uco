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

- [ ] T001 Aplicar `contracts/send-notification-batch.yaml` a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: descripción de `sendNotificationBatch` marcada como implementada y respuesta `400` con `ErrorResponse`; sin tocar schemas ni otras operaciones. Commit propio antes del controller

---

## Phase 2: Foundational

Sin tareas: el caso de uso, su bean en `UseCaseConfig`, `NotificationExceptionHandler` y el DTO de
elemento `SendNotificationRequest` ya existen.

---

## Phase 3: User Story 1 - Enviar varias notificaciones en una sola solicitud (P1) MVP

**Goal**: `POST /notifications:sendBatch` responde `202` con `batchId` y un resultado por elemento en
orden; rechazo estructural `400` sin registros nuevos.

**Independent Test**: spec.md, User Story 1, escenarios 1-6, y edge cases.

- [ ] T002 [P] [US1] Crear los records `rest/SendNotificationBatchRequest.java` (`batchId`, `List<SendNotificationRequest> items`), `rest/BatchItemResultResponse.java` (`externalId`, `outcome`, `notificationId`, `rejectionReason`; fábrica `from(BatchItemResult)`) y `rest/BatchAcceptedResponse.java` (`batchId`, `results`; fábrica `from(BatchAcceptedResult)`) según data-model.md
- [ ] T003 [US1] Prueba `rest-test/NotificationBatchControllerTest.java` (`@WebFluxTest(controllers = NotificationBatchController.class)`, `@MockBean SendNotificationBatchUseCase`, mismo patrón que `NotificationControllerTest`): `202` con los tres resultados (`ACCEPTED`, `DUPLICATE`, `REJECTED` con `notificationId` nulo y motivo) en orden; comando capturado con tenant, `batchId` enviado y elementos traducidos (incluido `subject` nulo); sin `batchId` -> comando con `batchId` nulo; `400` y caso de uso nunca invocado para: `items` vacío, `items` ausente, elemento nulo, `priority` ausente, `priority` desconocida, campo obligatorio en blanco, `batchId` en blanco, cabecera `X-Tenant-Id` ausente — depende de T002
- [ ] T004 [US1] Implementar `rest/NotificationBatchController.java` (`@RestController`, `@PostMapping("/notifications:sendBatch")`, `@RequestHeader("X-Tenant-Id")`, `ResponseEntity.status(ACCEPTED)`; validación estructural antes de invocar el caso de uso, `Preconditions.requireNonBlank` antes de `Priority.valueOf`) hasta pasar T003 — depende de T003
- [ ] T005 [US1] Prueba E2E `rest-test/NotificationBatchE2ETest.java` (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` con MongoDB y RabbitMQ + `WebTestClient`, catálogo por defecto, `externalId` y tenants únicos por prueba): (a) lote de 3 EMAIL con `batchId` propio -> `202`, mismo `batchId`, 3 `ACCEPTED` en el orden de la solicitud y cada `notificationId` llega a `DELIVERED` por `GET /notifications/{id}` (SC-001, SC-002); (b) lote mixto: EMAIL válido + canal `FAX` + SMS de 161 caracteres + `externalId` ya aceptado antes -> `ACCEPTED`, `REJECTED` con motivo, `REJECTED` con motivo, `DUPLICATE` con el `notificationId` original, sin `batchId` -> uno generado (SC-003); (c) dos tenants con los mismos `externalId` -> ambos `ACCEPTED` con ids distintos y `GET` cruzado -> `404` (SC-004); (d) lote con un elemento sin `priority` -> `400` y 0 documentos para ese tenant, con (a) como control positivo (SC-005) — depende de T004
- [ ] T006 [US1] Verificar el riesgo de plan.md "Riesgos conocidos" (mismo `externalId` dos veces en un lote): observar el resultado real en E2E; si falla, no parchear el adaptador: registrar el pendiente aquí con dueño y fecha y reportarlo

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
