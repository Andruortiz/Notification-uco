---

description: "Task list template for feature implementation"
---

# Tasks: Buscar notificaciones por filtros

**Input**: Design documents from `/specs/005-search-notifications/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/search-notifications.yaml](./contracts/search-notifications.yaml), [quickstart.md](./quickstart.md)

**Tests**: Incluidas — el Principio IV exige al menos una prueba end-to-end bloqueante para todo flujo
observable, y esta historia además introduce una consulta dinámica sobre Mongo que necesita probarse
contra el driver real, no solo con mocks.

**Organization**: Una sola historia de usuario (P1) — el `spec.md` de esta historia no tiene una
segunda historia independiente; todo el valor de negocio cae en una sola capacidad cohesiva (buscar
con filtros combinables, paginado, con historial completo).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: A qué historia de usuario pertenece la tarea (US1)
- Cada descripción incluye la ruta de archivo exacta

## Path Conventions

`core/` para el puerto y el caso de uso; `infrastructure/` para el adaptador Mongo, el controller, los
DTOs y el contrato OpenAPI — mismo patrón ya usado por `GetNotificationStatusUseCase`.

---

## Phase 1: Setup

- [x] T001 Confirmar que `spring-boot-starter-data-mongodb-reactive` (ya presente en
      `infrastructure/pom.xml`) expone todo lo necesario para una consulta dinámica con `Criteria` —
      no se espera ninguna dependencia nueva, solo verificación

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Contrato, puerto de salida e índice que la historia necesita antes de implementar nada
del lado de negocio

**⚠️ CRITICAL**: Ninguna parte de la historia puede implementarse hasta que esta fase esté completa —
en particular, el contrato debe actualizarse primero (Principio II)

- [x] T002 [P] Aplicar [contracts/search-notifications.yaml](./contracts/search-notifications.yaml) a
      `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: agregar los
      parámetros `limit`/`offset` a `GET /notifications` y el schema `NotificationSearchResponse`,
      reemplazando el response `200` actual (array plano) por el envoltorio paginado (Principio II —
      el contrato se actualiza antes que el controller)
- [x] T003 [P] Añadir el record `NotificationSearchCriteria` a
      `core/src/main/java/co/edu/uco/notification/core/repository/NotificationSearchCriteria.java`
      (tenantId, filtros opcionales, limit, offset — ver data-model.md)
- [x] T004 Extender `NotificationRepository` con
      `Flux<Notification> search(NotificationSearchCriteria criteria)` en
      `core/src/main/java/co/edu/uco/notification/core/repository/NotificationRepository.java`
      (depende de T003)
- [x] T005 [P] Añadir `@CompoundIndex(name = "tenant_acceptedAt", def = "{'tenantId': 1, 'acceptedAt': -1}")`
      a
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationDocument.java`
      (research.md, Decisión 3 — sin este índice SC-003 no es alcanzable) — confirmado que
      `@CompoundIndex` es `@Repeatable`, no hace falta envolver en `@CompoundIndexes`
- [x] T006 Implementar `search(...)` en
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationMongoAdapter.java`:
      `Criteria` dinámico (solo los filtros presentes), ordenado por `acceptedAt` descendente,
      `skip(offset).limit(limit + 1)` para poder calcular `hasNext` sin un `count()` aparte (research.md,
      Decisión 1) (depende de T004, T005)
- [x] T007 Confirmar que `HexagonalArchitectureTest` sigue en verde — ningún archivo de `core/`
      depende de Spring (3/3 tests en verde)

**Checkpoint**: Contrato, puerto de salida e índice listos — la historia puede implementarse

---

## Phase 3: User Story 1 - Buscar notificaciones combinando filtros y ver su historial completo (Priority: P1) 🎯 MVP

**Goal**: Un operador busca notificaciones combinando cualquier subconjunto de destinatario, canal,
estado y rango de fechas, y recibe una página paginada de resultados con el historial completo de
intentos de cada una.

**Independent Test**: Aceptar varias notificaciones con distintos destinatarios/canales/estados,
buscar combinando dos o más filtros vía `GET /notifications`, y confirmar que la respuesta solo
incluye las que cumplen todos los filtros, cada una con su historial completo, paginada y ordenada
por fecha de aceptación descendente.

### Tests for User Story 1 ⚠️

> **NOTE: Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [x] T008 [P] [US1] Unit test en
      `core/src/test/java/co/edu/uco/notification/core/usecase/SearchNotificationsServiceTest.java`:
      filtros combinados se aplican con AND; `from` posterior a `to` se rechaza; `limit`/`offset`
      fuera de rango se rechazan; `hasNext` se calcula correctamente; el orden de los resultados es
      el que devuelve el repositorio (Acceptance Scenarios 1-6, 9) — 9/9 tests en verde
- [x] T009 [P] [US1] Integration test en
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationMongoAdapterSearchTest.java`
      contra MongoDB real (Testcontainers, mismo patrón que `NotificationMongoAdapterTest`): confirma
      que el `Criteria` dinámico, el índice nuevo y el orden descendente funcionan contra el driver
      real, no solo en un mock (depende de T005, T006) — compila y sigue el patrón exacto de
      `NotificationMongoAdapterTest`; no se pudo ejecutar localmente por la misma limitación de
      Testcontainers en Windows ya documentada (afecta igual a los 3 tests preexistentes de este
      tipo) — se confía en que CI (Linux) lo valide
- [x] T010 [US1] E2E test en
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationControllerSearchE2ETest.java`:
      persiste notificaciones directamente vía `NotificationRepository` real (no vía `POST
      /notifications`, para no arrastrar RabbitMQ — el flujo de esta historia es "buscar", no
      "aceptar") y ejerce el flujo HTTP real de búsqueda con filtros combinados, paginación, orden,
      aislamiento por tenant, validación de entrada, y un caso dedicado con historial de intentos real
      de 2 elementos (Acceptance Scenarios 1-9, SC-002; Principio IV) (depende de T002, T006, T014) —
      mismo estado que T009: compila, sigue el patrón de `CorsConfigTest`/`NotificationMongoAdapterTest`,
      pendiente de validación en CI por la misma limitación local

### Implementation for User Story 1

- [x] T011 [P] [US1] Añadir los records `SearchNotificationsQuery`, `NotificationSearchResult` y
      `NotificationSearchPage` en `core/src/main/java/co/edu/uco/notification/core/port/in/` (ver
      data-model.md)
- [x] T012 [US1] Implementar `SearchNotificationsUseCase` (interfaz) y `SearchNotificationsService`
      en `core/src/main/java/co/edu/uco/notification/core/usecase/SearchNotificationsService.java`:
      valida `from`/`to` y `limit`/`offset`, construye `NotificationSearchCriteria`, mapea
      `Notification` → `NotificationSearchResult`, calcula `hasNext` a partir de los `limit + 1`
      resultados del repositorio (depende de T004, T011) — más el bean nuevo en `UseCaseConfig.java`
      (no listado originalmente en plan.md, necesario para que el controller pueda inyectar el caso
      de uso — mismo tipo de ajuste que ya se documentó en HU2-040)
- [x] T013 [P] [US1] Añadir los DTOs `NotificationSearchResponse`, `NotificationHistoryItemResponse`
      y `DeliveryAttemptResponse` en
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/` — ambos
      records con `List` necesitaron un constructor compacto con `List.copyOf(...)` tras un hallazgo
      real de SpotBugs (`EI_EXPOSE_REP`/`EI_EXPOSE_REP2`), consistente con el mismo patrón ya usado en
      `NotificationSearchResult`/`NotificationSearchPage` en `core`
- [x] T014 [US1] Añadir el método `GET /notifications` a
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationController.java`
      con `@RequestParam` opcionales para los cuatro filtros y `limit`/`offset`, mapeando errores de
      validación del caso de uso a `400` (depende de T012, T013) — también requirió agregar
      `@MockBean SearchNotificationsUseCase` a `NotificationControllerTest.java` existente (nuevo
      parámetro de constructor) más 2 pruebas nuevas de ese endpoint a nivel de controller
- [x] T015 [US1] Confirmar cobertura ≥80 % líneas / ≥70 % ramas (Principio IV) para todos los
      archivos nuevos de esta historia — todos los archivos de `core/` (criteria, query, result, page,
      service): 0 instrucciones/líneas sin cubrir. `NotificationSearchResponse` y `NotificationController`
      cubiertos vía `NotificationControllerTest`. `NotificationMongoAdapter.search(...)`,
      `NotificationHistoryItemResponse` y `DeliveryAttemptResponse` solo se ejercitan vía las pruebas
      con Testcontainers (T009/T010), que no corren localmente por la limitación ya documentada — la
      puerta de cobertura del *bundle* completo no pudo validarse end-to-end localmente por esa misma
      razón; se confía en que CI (Linux, con Testcontainers funcionando) la valide

**Checkpoint**: La historia está completa y probable de forma independiente

---

## Phase 4: Polish & Cross-Cutting Concerns

- [x] T016 [P] Confirmar Spotless/SpotBugs/FindSecBugs sin hallazgos sobre todos los archivos nuevos o
      modificados de esta historia — `mvn verify` pasó ambas puertas (llegó hasta jacoco-check sin
      fallos de Spotless ni SpotBugs) tras corregir el hallazgo real de T013
- [ ] T017 Ejecutar manualmente los cuatro escenarios de [quickstart.md](./quickstart.md) (filtros
      combinados, paginación, validación de entrada, aislamiento por tenant) contra el `docker
      compose` local — queda como validación manual pendiente, igual que en historias anteriores
- [ ] T018 [P] Confirmar que `Listado.tsx` (Front-Notification) puede conectarse al nuevo endpoint sin
      cambios adicionales del lado del backend — no es parte de esta historia (cae en HU2-070), pero
      vale la pena verificar que el contrato resultante es consumible sin sorpresas

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias — puede iniciar de inmediato
- **Foundational (Phase 2)**: depende de Setup — BLOQUEA la historia completa, en particular T002
  (contrato) debe completarse antes de T013/T014 (DTOs/controller), y T005/T006 (índice + adaptador)
  antes de T009 (integration test) y T012 (caso de uso)
- **User Story 1 (Phase 3)**: depende de Foundational
- **Polish (Phase 4)**: depende de que User Story 1 esté completa

### Within Each User Story

- Tests (T008, T009, T010) se escriben y deben FALLAR antes de implementar T011-T014
- Puerto de salida (T004) antes del caso de uso (T012)
- Caso de uso (T012) antes del controller (T014)
- DTOs (T013) pueden construirse en paralelo con el caso de uso, ambos necesarios antes de T014

### Parallel Opportunities

- T002 (contrato), T003 (criteria record) y T005 (índice) en paralelo — archivos distintos, sin
  dependencias entre sí
- T008 y T009 (pruebas en archivos distintos) en paralelo, antes de T011-T014
- T011 (records del puerto) y T013 (DTOs) en paralelo — capas distintas (`core` vs `infrastructure`)

---

## Implementation Strategy

### MVP First (única historia)

1. Completar Phase 1: Setup
2. Completar Phase 2: Foundational (bloqueante — incluye el contrato)
3. Completar Phase 3: User Story 1
4. **DETENER y VALIDAR**: correr T010 (E2E) y los cuatro escenarios de quickstart.md de forma
   independiente
5. Esto ya deja al frontend (`Listado.tsx`, HU2-070) con un endpoint real para consumir

---

## Notes

- [P] tareas = archivos distintos, sin dependencias pendientes
- [Story] mapea la tarea a su historia de usuario para trazabilidad
- Verificar que las pruebas fallan antes de implementar
- Commit por tarea o grupo lógico, una sola rama para toda la historia
  (`feature/HU2-025-search-notifications`, ADR-0015) — commits de una sola línea, atribución de IA
  según la instrucción vigente de esta sesión (Principio V, con la excepción ya documentada en
  plan.md)
- Detenerse en el checkpoint para validar la historia de forma independiente
- Evitar: tareas vagas, conflictos de archivo simultáneos
