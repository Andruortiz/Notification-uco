    ---

description: "Task list for CORS para el dashboard de frontend"
---

# Tasks: CORS para el dashboard de frontend

**Input**: Design documents from `/specs/003-cors-configuration/`

**Prerequisites**: plan.md, spec.md, research.md, quickstart.md

**Tests**: Requeridas — el Principio IV de la constitución exige una prueba E2E bloqueante para toda historia que toque un flujo observable de punta a punta; CORS es exactamente eso (comportamiento HTTP observable por el navegador).

**Organization**: Las tareas se agrupan por historia de usuario para poder implementar y probar cada una de forma independiente, aunque en este caso comparten casi todo el código (ver nota en Fase 4).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias)
- **[Story]**: A qué historia de usuario pertenece la tarea (US1, US2)

---

## Phase 1: Setup

- [x] T001 Agregar `notification.cors.allowed-origins` (default `http://localhost:5173`) en `infrastructure/src/main/resources/application.yml`, configurable vía `NOTIFICATION_CORS_ALLOWED_ORIGINS`
- [x] T002 [P] Confirmar que Spotless/SpotBugs/FindSecBugs cubren el paquete `infrastructure/config/` — verificado, `spotless:check` y `spotbugs:check` en verde (0 bugs)

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No se puede considerar ninguna historia de usuario completa sin esto en verde

- [x] T003 Confirmar que `HexagonalArchitectureTest`/`ModularityTests` siguen en verde con el paquete `infrastructure/config/` ampliado — verificado, ambas en verde

**Checkpoint**: Foundation lista — la implementación de las historias de usuario puede empezar

---

## Phase 3: User Story 1 - Consumir la API desde el navegador en desarrollo (Priority: P1) 🎯 MVP

**Goal**: El dashboard, corriendo en un origen permitido, puede llamar la API REST directamente desde el navegador sin que la política de mismo origen bloquee la respuesta.

**Independent Test**: Servir el dashboard desde `http://localhost:5173` y hacer una petición `fetch` directa a la API — confirmar que no hay error de CORS en la consola del navegador.

### Tests for User Story 1 ⚠️

> **NOTE: Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [x] T004 [P] [US1] Test E2E `CorsConfigTest` (con `WebTestClient` contra el contexto real, `@SpringBootTest(webEnvironment = RANDOM_PORT)`) en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/CorsConfigTest.java` — una petición con `Origin: http://localhost:5173` (el default) recibe `Access-Control-Allow-Origin` en la respuesta (SC-001). **Nota de implementación**: se cambió de `@WebFluxTest` (mock server) a `@SpringBootTest(RANDOM_PORT)` — el servidor mock de `@WebFluxTest` no tiene esquema/host real y `CorsUtils.isSameOrigin` lanza `IllegalArgumentException` contra él; CORS solo se puede probar de verdad contra un servidor real
- [x] T005 [P] [US1] Extender `CorsConfigTest` — una petición con un `Origin` no permitido (`http://localhost:9999`) recibe `403 Forbidden` sin las cabeceras `Access-Control-Allow-*` (SC-002)

### Implementation for User Story 1

- [x] T006 [US1] Implementar `CorsConfig` (`@Configuration`, bean `CorsWebFilter` sobre `UrlBasedCorsConfigurationSource` registrado para `/**`) en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/CorsConfig.java` — orígenes permitidos, métodos (`GET`, `POST`, `PUT`, `DELETE`) y cabeceras (`*`, incluye `X-Tenant-Id` y `Content-Type`) leídos desde `notification.cors.allowed-origins` (depende de T001, hace pasar T004 y T005)
- [x] T007 [US1] Confirmar cobertura ≥80 %/≥70 % (Principio IV) para `CorsConfig` — 0 líneas/instrucciones sin cubrir en el reporte de Jacoco

**Checkpoint**: User Story 1 funcional y probable de forma independiente — el dashboard ya puede llamar la API desde el origen por defecto

---

## Phase 4: User Story 2 - Configurar el origen permitido por ambiente (Priority: P2)

**Goal**: El origen permitido se puede cambiar por variable de entorno, sin recompilar ni tocar código.

**Independent Test**: Arrancar el backend con `NOTIFICATION_CORS_ALLOWED_ORIGINS` apuntando a otro origen, y confirmar que solo ese origen recibe las cabeceras CORS.

**Nota**: la configurabilidad no es una pieza de código separada — ya queda resuelta por el `@Value` de T006 (mismo patrón que `pendingOrphanThresholdMs` en HU2-037, ver `research.md` punto 3). Lo único que agrega esta historia es la prueba que lo confirma explícitamente.

### Tests for User Story 2 ⚠️

- [x] T008 [US2] `CorsConfigCustomOriginTest.java` (archivo separado, no una extensión de `CorsConfigTest`, para no pelear con el cacheo de contexto de Spring entre propiedades distintas) — con `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "notification.cors.allowed-origins=http://localhost:4000")`, confirma que `http://localhost:4000` recibe las cabeceras y que el default (`http://localhost:5173`) ya no las recibe (SC-003)

### Implementation for User Story 2

- [x] T009 [US2] Ninguna — cubierto por T006. Esta línea documenta explícitamente que no hay trabajo de implementación adicional, para que no quede como un olvido silencioso (Principio VII).

**Checkpoint**: User Stories 1 y 2 funcionan de forma independiente — CORS activo y configurable sin recompilar

---

## Phase Final: Polish & Cross-Cutting Concerns

- [ ] T010 [P] Ejecutar manualmente los 3 escenarios de `quickstart.md` contra el backend y un frontend/página real — pendiente, requiere un navegador real y el frontend (`Front-Notification`) corriendo; los 3 tests E2E automatizados (T004, T005, T008) ya cubren el mismo comportamiento contra un servidor real, esto es la confirmación final en el navegador
- [x] T011 Build completo (`./mvnw -pl core,infrastructure -am verify`) — Spotless, SpotBugs, FindSecBugs en verde (0 bugs); ArchUnit y Modulith en verde; 42/42 pruebas de `infrastructure` en verde. Jacoco **falla localmente**, pero por la misma causa preexistente y ajena a esta historia documentada en HU2-107/HU2-135 (clases solo cubiertas por `NotificationMongoAdapterTest`, que no corre sin Docker) — `CorsConfig` en sí tiene 0 líneas sin cubrir (ver T007)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias — puede empezar de inmediato
- **Foundational (Phase 2)**: depende de Setup — bloquea ambas historias de usuario
- **User Story 1 (Phase 3)**: depende de Foundational
- **User Story 2 (Phase 4)**: depende de Foundational y de que `CorsConfig` ya exista (T006, de US1) — no es independiente en implementación (es el mismo bean), pero sí en prueba y en valor entregado (confirma la configurabilidad, no la reimplementa)
- **Polish (Phase Final)**: depende de que ambas historias estén completas

### Parallel Opportunities

- T001 y T002 (Setup) en paralelo
- T004 y T005 (tests US1) en paralelo entre sí — mismo archivo pero casos independientes, se pueden escribir en paralelo y correr juntos
- T010 puede hacerse en paralelo con el resto de Phase Final

---

## Implementation Strategy

### MVP First (User Story 1)

1. Completar Phase 1: Setup
2. Completar Phase 2: Foundational (bloqueante)
3. Completar Phase 3: User Story 1
4. **Detenerse y validar**: correr `quickstart.md` escenarios 1 y 2, confirmar que el dashboard puede llamar la API y que un origen no permitido queda bloqueado
5. Desplegar/demostrar si está listo

### Incremental Delivery

1. Setup + Foundational → base lista
2. User Story 1 → probar de forma independiente → MVP
3. User Story 2 → probar de forma independiente (cambiar el origen por variable de entorno) → entrega incremental

---

## Notes

- [P] = archivos distintos, sin dependencias
- La etiqueta [Story] mapea cada tarea a su historia de usuario para trazabilidad
- Confirmar que los tests fallan antes de implementar
- Commit por tarea o grupo lógico (rama única `feature/HU2-071-cors-frontend`, ya creada), commits de una sola línea
- Detenerse en cada checkpoint para validar la historia de forma independiente

---

## Phase 5: Convergence

- [x] T012 Agregar `@Configuration` a `CorsConfig` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/CorsConfig.java` per T006 (partial) — sin la anotación, Spring nunca registra el bean `corsWebFilter` y CORS queda inactivo pese a que el código existe (CRITICAL) — agregada manualmente en IntelliJ antes de esta sesión de implementación, confirmada al re-leer el archivo
- [x] T013 Crear `CorsConfigTest.java` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/CorsConfigTest.java` con las pruebas E2E de origen permitido y origen no permitido per Constitution IV, T004, T005 (missing) (CRITICAL)
- [x] T014 Extender `CorsConfigTest` con el caso de origen configurable por variable de entorno per FR-002, SC-003, T008 (missing) — implementado como archivo separado `CorsConfigCustomOriginTest.java`, ver nota en T008
- [x] T015 Alinear el formato de `CorsConfig.java` (indentación, orden de imports) con el estilo del resto de `infrastructure/config` per T002 (partial)
