---

description: "Task list for el catálogo de canales persistido en Mongo"
---

# Tasks: Catálogo de canales persistido en Mongo

**Input**: Design documents from `/specs/002-catalog-mongo-persistence/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Requeridas — el Principio IV de la constitución exige una prueba E2E bloqueante para toda historia que toque un flujo observable de punta a punta, además de cobertura unitaria.

**Organization**: Las tareas se agrupan por historia de usuario para poder implementar y probar cada una de forma independiente.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias)
- **[Story]**: A qué historia de usuario pertenece la tarea (US1, US2)

---

## Phase 1: Setup

- [x] T001 Agregar `notification.catalog.refresh-interval-ms` (default `30000`) en `infrastructure/src/main/resources/application.yml`, configurable vía `NOTIFICATION_CATALOG_REFRESH_INTERVAL_MS`
- [x] T002 [P] Confirmar que Spotless/SpotBugs/FindSecBugs cubren el paquete nuevo `infrastructure/.../adapter/out/catalog/` — verificado, `spotless:check` y `spotbugs:check` en verde (0 bugs)

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No se puede considerar ninguna historia de usuario completa sin esto en verde

- [x] T003 Crear `ChannelCatalogDocument` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogDocument.java` — `@Document(collection = "channel_catalog")`, `@Id String channelType`, `List<String> providers`, `String contentSchema`
- [x] T004 [P] Crear `ChannelCatalogCache` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogCache.java` — `AtomicReference<Map<String, ChannelRoute>>` inicializado en mapa vacío, con métodos `snapshot()` y `replace(Map<String, ChannelRoute>)`
- [x] T005 Confirmar que `HexagonalArchitectureTest`/`ModularityTests` siguen en verde con el paquete `adapter/out/catalog/` ampliado — verificado, ambas en verde

**Checkpoint**: Foundation lista — la implementación de las historias de usuario puede empezar

---

## Phase 3: User Story 1 - Cambiar el catálogo sin redeploy (Priority: P1) 🎯 MVP

**Goal**: El catálogo de canales se resuelve desde Mongo (vía un snapshot en memoria refrescado periódicamente) en lugar de `application.yml`; una migración de arranque siembra las entradas existentes; un cambio en Mongo se refleja en todas las réplicas sin redeploy.

**Independent Test**: Insertar/editar una entrada de catálogo directamente en Mongo, esperar el intervalo de refresco, y verificar que `ChannelCatalogPort.findActiveRoute` devuelve la configuración actualizada sin reiniciar el servicio; y verificar que, en un ambiente nuevo, la migración deja el catálogo con las mismas entradas que tenía `application.yml`.

### Tests for User Story 1 ⚠️

> **NOTE: Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [x] T006 [P] [US1] Test unitario `ChannelCatalogSeederTest` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogSeederTest.java` — migra desde `ChannelCatalogProperties` solo cuando la colección está vacía; no hace nada si ya hay datos
- [x] T007 [P] [US1] Test unitario `ChannelCatalogRefresherTest` (camino feliz) en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogRefresherTest.java` — consulta Mongo, construye el mapa canal→`ChannelRoute` y reemplaza el snapshot de `ChannelCatalogCache`
- [x] T008 [P] [US1] Test unitario `MongoChannelCatalogAdapterTest` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/MongoChannelCatalogAdapterTest.java` — `findActiveRoute` lee del snapshot de `ChannelCatalogCache`; devuelve `Mono.empty()` si el canal no está o si la entrada está mal formada (sin proveedores); llamar `findActiveRoute` con dos `TenantId` distintos para el mismo canal y confirmar que devuelve exactamente la misma `ChannelRoute` (FR-003 — catálogo global, `tenantId` no filtra)
- [x] T009 [US1] Test E2E `ChannelCatalogE2ETest` (Testcontainers + `@DataMongoTest`, mismo patrón que `NotificationMongoAdapterTest`) en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogE2ETest.java` — contra un Mongo vacío: ejecutar el seeder, ejecutar el refresco, y verificar que `MongoChannelCatalogAdapter.findActiveRoute(EMAIL, tenantId)` resuelve la misma ruta que hoy define `application.yml` (Principio IV, SC-002); confirmar que la migración no toca nada si la colección ya tiene datos; insertar un segundo `ChannelCatalogDocument` con el mismo `channelType` (mismo `_id`, vía `insert()` para forzar el conflicto — `save()` haría upsert y no lo detectaría) y confirmar que Mongo rechaza el duplicado con `DuplicateKeyException` (FR-007, SC-004 — unicidad por canal). **No verificada localmente** — requiere Docker (Testcontainers), no disponible en este entorno; pendiente de confirmación en CI
- [x] T009a [US1] Verificación (sin código nuevo): confirmado que `SendNotificationServiceTest` y `DispatchNotificationServiceTest` siguen en verde sin ninguna modificación tras el cambio de adaptador (22/22 pruebas, `./mvnw -pl core test -Dtest=SendNotificationServiceTest,DispatchNotificationServiceTest`) — ambos mockean `ChannelCatalogPort` por su interfaz, así que el swap de `ConfigurationChannelCatalogAdapter` a `MongoChannelCatalogAdapter` es transparente por construcción (Principio I: el core depende del puerto, nunca del adaptador concreto); la garantía de que el adaptador real cumple el contrato del puerto la da T009 contra Mongo real, no una prueba adicional por caso de uso (FR-002, SC-003)

### Implementation for User Story 1

- [x] T010 [US1] Implementar `ChannelCatalogSeeder` (`ApplicationRunner`) en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogSeeder.java` — si `channel_catalog` está vacía, inserta un `ChannelCatalogDocument` por cada entrada de `ChannelCatalogProperties.channels()` (depende de T003, hace pasar T006). `seed()` retorna `Mono<Void>` (testeable); `run()` solo la suscribe (fire-and-forget, no bloquea hilos reactivos en el arranque)
- [x] T011 [US1] Implementar `ChannelCatalogRefresher` (`@Scheduled(fixedDelayString = "${notification.catalog.refresh-interval-ms:30000}")`) en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogRefresher.java` — consulta todos los `ChannelCatalogDocument`, descarta las entradas sin proveedores, y reemplaza el snapshot de `ChannelCatalogCache` (depende de T004, hace pasar T007)
- [x] T012 [US1] Implementar `MongoChannelCatalogAdapter implements ChannelCatalogPort` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/MongoChannelCatalogAdapter.java` — `findActiveRoute` lee `ChannelCatalogCache.snapshot()` por el nombre del canal en mayúsculas (mantiene la búsqueda case-insensitive que ya tenía `ConfigurationChannelCatalogAdapter`, para no romper FR-002) (depende de T004, hace pasar T008)
- [x] T013 [US1] Eliminar `ConfigurationChannelCatalogAdapter.java` y su test `ConfigurationChannelCatalogAdapterTest.java` — reemplazados por `MongoChannelCatalogAdapter`; `ChannelCatalogProperties` se conserva, ahora solo como fuente de `ChannelCatalogSeeder`; confirmado sin referencias residuales (`grep -r ConfigurationChannelCatalogAdapter` solo encuentra menciones históricas en `specs/`/`docs/`, ninguna en código)
- [x] T014 [US1] Confirmar cobertura ≥80 %/≥70 % (Principio IV) para los archivos nuevos de esta historia — `ChannelCatalogDocument`, `ChannelCatalogCache`, `MongoChannelCatalogAdapter`, `ChannelCatalogSeeder` y `ChannelCatalogRefresher` tienen 0 líneas/instrucciones sin cubrir en el reporte de Jacoco (solo el método `@Scheduled` de `ChannelCatalogRefresher` queda sin ejercitar directamente, igual que `PendingNotificationSchedulerAdapter` en HU2-030). El `jacoco:check` del módulo completo falla localmente, pero por una causa preexistente y ajena a esta historia — ver nota en T021

**Checkpoint**: User Story 1 funcional y probable de forma independiente — el catálogo ya vive en Mongo

---

## Phase 4: User Story 2 - Resistencia a caídas de Mongo (Priority: P2)

**Goal**: Una caída temporal de Mongo no interrumpe la resolución de rutas activas — el servicio sigue usando la última configuración conocida hasta que Mongo vuelve a estar disponible.

**Independent Test**: Con el catálogo ya cargado, simular un fallo de Mongo en el refresco y verificar que `ChannelCatalogCache` conserva el snapshot anterior sin propagar el error; verificar que, al restablecerse Mongo, el refresco se reanuda con normalidad.

### Tests for User Story 2 ⚠️

- [x] T015 [P] [US2] Extender `ChannelCatalogRefresherTest` con el escenario de falla — `mongoTemplate.findAll(...)` emite un error, verificar que `ChannelCatalogCache` no cambia y que el error no se propaga fuera del `@Scheduled` (`doesNotReplaceTheSnapshotWhenMongoFails`)
- [x] T016 [US2] Prueba E2E de resiliencia — separada en su propio archivo `ChannelCatalogResilienceE2ETest.java` (no en `ChannelCatalogE2ETest`, como decía el plan original) para no compartir el contenedor Mongo estático detenido con las demás pruebas E2E de la Fase 3, que correrían en un orden no garantizado dentro de la misma clase (JUnit 5 no ordena los métodos por defecto). Detiene su propio contenedor (`MONGO.stop()`) tras un primer refresco exitoso, dispara un nuevo refresco, y verifica que `findActiveRoute` sigue devolviendo la última ruta conocida. **No verificada localmente** (Docker), pendiente de confirmación en CI

### Implementation for User Story 2

- [x] T017 [US2] Agregar manejo de error en `ChannelCatalogRefresher` — `.onErrorResume(error -> Mono.empty())` completa sin reemplazar el snapshot, sin propagar la excepción al scheduler (depende de T011, hace pasar T015 y T016). Sin log de advertencia: el proyecto todavía no tiene ninguna infraestructura de logging (Fase 8, HU2-121 sigue pendiente — "cero logger.info/debug/warn/error en todo el repo" a la fecha) y el patrón existente (`RequeuePendingNotificationsService`, HU2-030) tampoco loguea sus `onErrorResume`; agregar logging ad-hoc aquí se adelantaría a esa historia sin el formato/sanitización que va a definir
- [x] T018 [US2] Confirmar cobertura ≥80 %/≥70 % (Principio IV) para el camino de resiliencia agregado — cubierto por `ChannelCatalogRefresherTest.doesNotReplaceTheSnapshotWhenMongoFails` (0 líneas sin cubrir en `ChannelCatalogRefresher` fuera del método `@Scheduled`, ver T014)

**Checkpoint**: User Stories 1 y 2 funcionan de forma independiente — el catálogo vive en Mongo y resiste caídas temporales

---

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T019 [P] Confirmar (`grep`) que no quedan referencias a `ConfigurationChannelCatalogAdapter` en el código ni en tests — verificado, solo quedan menciones históricas en `specs/002-catalog-mongo-persistence/{plan,tasks}.md` y en `docs/diagramas/`
- [ ] T020 [P] Ejecutar manualmente los 3 escenarios de `quickstart.md` contra el entorno local (`docker compose up -d mongodb`) — pendiente, requiere Docker corriendo localmente (mismo bloqueo que T009/T016)
- [x] T021 Build completo (`./mvnw -pl core,infrastructure -am verify`) — Spotless y SpotBugs/FindSecBugs en verde (0 bugs); ArchUnit y Modulith en verde; Jacoco **falla localmente**, pero por una causa preexistente y ajena a esta historia: `NotificationDocumentMapper`, `NotificationDocument` y la mayoría de `NotificationMongoAdapter` (de HU2-030 y anteriores) solo se cubren vía `NotificationMongoAdapterTest`, que no corre sin Docker — sin ese único test el módulo completo ya cae por debajo de 80 %/70 % independientemente de esta historia. Todo el código nuevo de HU2-107 (`ChannelCatalogDocument`, `ChannelCatalogCache`, `MongoChannelCatalogAdapter`, `ChannelCatalogSeeder`, `ChannelCatalogRefresher`) tiene 0 líneas sin cubrir (ver T014/T018); confirmado con `./mvnw -pl core,infrastructure -am verify -Dtest='!NotificationMongoAdapterTest,!ChannelCatalogE2ETest,!ChannelCatalogResilienceE2ETest'`. Pendiente de confirmación definitiva en CI, donde sí corren los 3 tests Testcontainers

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias — puede empezar de inmediato
- **Foundational (Phase 2)**: depende de Setup — bloquea ambas historias de usuario
- **User Story 1 (Phase 3)**: depende de Foundational
- **User Story 2 (Phase 4)**: depende de Foundational y de que `ChannelCatalogRefresher` ya exista (T011, de US1) — no es independiente en implementación (extiende el mismo refresher), pero sí en prueba y en valor entregado
- **Polish (Phase 5)**: depende de que ambas historias estén completas

### Parallel Opportunities

- T001 y T002 (Setup) en paralelo
- T004 (Foundational) en paralelo con T003/T005
- T006, T007, T008 (tests US1) en paralelo entre sí — archivos distintos
- T015 (test US2) puede escribirse en paralelo con el resto de Phase 3 una vez exista T007, aunque su implementación (T017) depende de T011

---

## Implementation Strategy

### MVP First (User Story 1)

1. Completar Phase 1: Setup
2. Completar Phase 2: Foundational (bloqueante)
3. Completar Phase 3: User Story 1
4. **Detenerse y validar**: correr `quickstart.md` escenarios 1 y 2, confirmar que el catálogo vive en Mongo y se actualiza sin redeploy
5. Desplegar/demostrar si está listo

### Incremental Delivery

1. Setup + Foundational → base lista
2. User Story 1 → probar de forma independiente → MVP
3. User Story 2 → probar de forma independiente (caída de Mongo simulada) → entrega incremental de resiliencia

---

## Notes

- [P] = archivos distintos, sin dependencias
- La etiqueta [Story] mapea cada tarea a su historia de usuario para trazabilidad
- Confirmar que los tests fallan antes de implementar
- Commit por tarea o grupo lógico (una rama por historia, ADR-0015 — commits de una sola línea, sin atribución de IA, Principio V)
- Detenerse en cada checkpoint para validar la historia de forma independiente
