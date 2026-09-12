---

description: "Task list for HU2-030 — Reencolar notificaciones recuperables vencidas"
---

# Tasks: Reencolar notificaciones recuperables vencidas (HU2-030)

**Input**: Design documents from `/specs/001-requeue-recoverable-notifications/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Requeridas — el Principio IV de la constitución exige una prueba E2E bloqueante para toda historia que toque un flujo observable de punta a punta, además de cobertura unitaria.

**Nota de estado**: la implementación se adelantó a este `tasks.md` (ver nota de proceso en spec.md). Las casillas ya marcadas `[x]` reflejan código que ya existe y fue verificado línea por línea contra el archivo real; las sin marcar son trabajo pendiente confirmado, incluyendo bugs reales encontrados en el código ya escrito.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1 — única historia de este spec)

---

## Phase 1: Setup

- [x] T001 [P] Agregar `notification.scheduler.requeue-interval-ms` en `infrastructure/src/main/resources/application.yml`
- [x] T002 Habilitar scheduling: agregar `@EnableScheduling` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/NotificationServiceApplication.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No se puede considerar la User Story 1 completa sin esto en verde

- [x] T003 Definir el puerto `RequeuePendingNotificationsUseCase` en `core/src/main/java/co/edu/uco/notification/core/port/in/RequeuePendingNotificationsUseCase.java`
- [x] T004 Extender `NotificationRepository` con `Flux<Notification> findByStatus(NotificationStatus status)` en `core/src/main/java/co/edu/uco/notification/core/repository/NotificationRepository.java`
- [x] T005 Corregir `findByStatus` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationMongoAdapter.java` — usaba `mongoTemplate.findById(query, ...)` (siempre vacío); corregido a `mongoTemplate.find(query, NotificationDocument.class)`
- [x] T005b Nuevo, no previsto en el plan original: crear `NotificationVersionConflictException` en `core/src/main/java/co/edu/uco/notification/core/exception/` y traducir `OptimisticLockingFailureException` (Spring) a esa excepción de dominio dentro de `NotificationMongoAdapter.save()` — `research.md` (punto 4) proponía capturar la excepción de Spring directamente en `core`, lo cual viola el Principio I (core sin dependencias de framework); esta es la corrección hexagonal correcta, descubierta durante la implementación
- [x] T006 [P] Confirmar que `HexagonalArchitectureTest`/`ModularityTests` siguen en verde con los paquetes nuevos (`adapter/in/scheduler/`) — verificado, ambas en verde

**Checkpoint**: Foundation lista — T005 y T005b resueltos.

---

## Phase 3: User Story 1 - Reintento automático sin intervención manual (Priority: P1) 🎯 MVP (única historia)

**Goal**: Reencolar automáticamente las notificaciones `RECOVERABLE` cuyo tiempo de espera ya venció, sin intervención de un operador.

**Independent Test**: Guardar una notificación `RECOVERABLE` con un intento previo cuyo tiempo de espera ya venció, ejecutar `requeuePending()`, verificar que vuelve a `PENDING` y queda encolada.

### Tests for User Story 1 ⚠️

- [x] T007 [P] [US1] Prueba unitaria de `RequeuePendingNotificationsService` en `core/src/test/java/co/edu/uco/notification/core/usecase/RequeuePendingNotificationsServiceTest.java` — 7 casos: notificación vencida se reencola; notificación no vencida no se toca; sin notificaciones `RECOVERABLE` no hace nada; conflicto de versión en una no bloquea el resto del lote; `PENDING` huérfana se reencola; `PENDING` reciente no se toca; `PENDING` con intento previo no se toca (T017-T019)
- [x] T008 [P] [US1] Prueba end-to-end contra Mongo real (Testcontainers), agregada a `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationMongoAdapterTest.java` — 2 pruebas: `RECOVERABLE` (una vencida, una no) y `PENDING` huérfana vs. reciente (T020), ambas contra el adaptador Mongo real, no un mock. **No verificada localmente** — requiere Docker (Testcontainers), no disponible en este entorno; queda pendiente de confirmación en CI

### Implementation for User Story 1

- [x] T009 [US1] Implementar `RequeuePendingNotificationsService` en `core/src/main/java/co/edu/uco/notification/core/usecase/RequeuePendingNotificationsService.java`
- [x] T010 [US1] Ajustar `requeueAndPublish` para que un conflicto de versión en una notificación no bloquee el resto del lote — implementado con `.onErrorResume(NotificationVersionConflictException.class, error -> Mono.empty())` (ver T005b: se usa la excepción de dominio, no la de Spring, para no violar el Principio I)
- [x] T011 [US1] Corregir `PendingNotificationSchedulerAdapter` — usaba `@Scheduled(cron = "${notification.scheduler.requeue-interval-ms:30000}")` (`cron` no acepta milisegundos, esto rompía el arranque del contexto de Spring); corregido a `@Scheduled(fixedDelayString = "${notification.scheduler.requeue-interval-ms:30000}")`
- [x] T012 [US1] Registrar el bean `requeuePendingNotificationsUseCase` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/UseCaseConfig.java`
- [x] T013 [US1] Confirmar cobertura ≥80%/≥70% (Principio IV) — `./mvnw -pl core verify` (incluye jacoco:check) en verde

**Checkpoint**: User Story 1 completa. Única verificación pendiente: T008 en CI (Docker no disponible localmente).

---

## Phase 4: Convergencia — hallazgo del backlog (2026-08-29, resuelto 2026-09-11)

**Origen**: la fila de HU2-030 en el backlog de Notion ya documentaba, desde antes de este spec, que la primera versión de esta historia (Fase 3, solo cubre `RECOVERABLE`) dejaba sin resolver un caso más grave: una notificación `PENDING` huérfana si el proceso cae entre `save()` y `enqueueForDispatch()` — violación directa de RNF-05. Se encontró al revisar el backlog antes de abrir el PR, con la implementación de solo-`RECOVERABLE` ya escrita.

- [x] T017 Agregar `recoverOrphanedPending()` en `RequeuePendingNotificationsService` — consulta `PENDING` con cero intentos de entrega y `acceptedAt` más antiguo que un umbral corto; solo llama `enqueueForDispatch`, sin transicionar estado ni guardar (FR-008, FR-009, FR-010)
- [x] T018 Agregar `notification.scheduler.pending-orphan-threshold-ms` (`infrastructure/src/main/resources/application.yml`, default 60000) y el parámetro `Duration` correspondiente en el constructor de `RequeuePendingNotificationsService` y en `UseCaseConfig`
- [x] T019 [P] Pruebas unitarias del caso huérfano en `RequeuePendingNotificationsServiceTest.java`: se reencola sin cambiar estado; no se toca si es reciente; no se toca si ya tiene un intento (llegó a `PENDING` por un reencolado exitoso, no está huérfana)
- [x] T020 [P] Prueba E2E del caso huérfano en `NotificationMongoAdapterTest.java` contra Mongo real — misma limitación de Docker que T008

**Checkpoint**: el hallazgo del 2026-08-29 queda resuelto antes de abrir el PR — la historia ya no se cierra con la brecha conocida.

---

## Phase Final: Polish & Cross-Cutting Concerns

- [ ] T014 [P] Ejecutar la validación manual de `quickstart.md` contra la app real — pendiente, requiere Docker corriendo localmente (mismo bloqueo que T008)
- [x] T015 Confirmar que ningún archivo nuevo de esta historia tiene comentarios explicativos (Principio III) — verificado
- [x] T016 Spotless + SpotBugs + FindSecBugs limpios en los archivos nuevos/modificados — `./mvnw -pl core,infrastructure -am clean install -DskipTests` en verde

---

## Dependencies & Execution Order

- **Setup (T001-T002)**: sin dependencias — T001 ya está, T002 puede hacerse ya
- **Foundational (T003-T006)**: bloquea la User Story 1 — **T005 es el bloqueante real hoy** (bug que deja el caso de uso inoperante)
- **User Story 1 (T007-T013)**: depende de Foundational completo
- **Polish (T014-T016)**: depende de que User Story 1 esté terminada

### Parallel Opportunities

- T001 y T002 (Setup) — archivos distintos, en paralelo
- T006 (ArchUnit) puede correr en paralelo con T005 una vez exista el código
- T007 y T008 (pruebas) — archivos distintos, en paralelo entre sí, pero ambas requieren T005 y T010 ya resueltos para poder pasar
- T014, T015, T016 (Polish) — en paralelo entre sí

## Implementation Strategy

### MVP = User Story 1 completa (es la única historia de este spec)

1. Cerrar Setup (T002) y Foundational (T005, T006) — **T005 es el bug crítico, priorízalo**
2. Aplicar T010 y T011 (los dos ajustes de diseño que salieron en research.md)
3. Escribir T007 y T008 (pruebas) — deben fallar contra el código actual (T005 roto) y pasar después de corregirlo
4. Confirmar cobertura (T013) y limpieza (T014-T016)
5. Commit + push + PR

## Notes

- Commit por tarea o grupo lógico, rama única `feature/HU2-030-reencolar-notificaciones-recoverable` (ya creada), commits de una sola línea, sin atribución de IA (Principio V)
- No cerrar la historia sin T005, T010 y T011 — son bugs reales, no mejoras opcionales
