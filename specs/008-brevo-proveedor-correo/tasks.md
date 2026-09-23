---

description: "Task list template for feature implementation"
---

# Tasks: Integrar Brevo como primer proveedor real de correo

**Input**: Design documents from `/specs/008-brevo-proveedor-correo/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/brevo-transactional-email.md, quickstart.md

**Tests**: Solicitadas explícitamente por el plan (Principio IV): unitarias del clasificador, del
adaptador contra `FakeBrevoServer`, y una E2E nueva con Testcontainers + `WebTestClient`.

**Organization**: Fases por historia de usuario (spec.md, US1–US5), precedidas por una fase
fundacional compartida (la pieza que toca `core` y las clases de transporte que casi todas las
historias necesitan).

## Path Conventions

- `core/src/main/java/co/edu/uco/notification/core/...`
- `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/...`
- Tests en los árboles `.../src/test/java/...` espejo.

---

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: la pieza de `core` y las clases de transporte/configuración que todas las historias
de usuario necesitan. Ninguna historia puede probarse sin esto.

- [x] T001 Crear `ProviderDisabledException` en `core/src/main/java/co/edu/uco/notification/core/exception/ProviderDisabledException.java` (hermana de `ProviderNotAvailableException`; recibe `ProviderId` + motivo)
- [x] T002 [P] Prueba de `ProviderDisabledException` en `core/src/test/java/co/edu/uco/notification/core/exception/ProviderDisabledExceptionTest.java`
- [x] T003 [P] Crear `BrevoResponseClassifier` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoResponseClassifier.java` (función pura y total, research.md Decisión 4, quince filas)
- [x] T004 [P] Prueba de `BrevoResponseClassifier` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoResponseClassifierTest.java` (las quince filas)
- [x] T005 [P] Crear `BrevoProviderProperties` — **desviación**: vive en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/BrevoProviderProperties.java`, no en `adapter/out/provider`, porque `BrevoWebClientConfig` (módulo `config`) la consume y dejarla en `adapter` producía un ciclo `adapter -> config -> adapter` detectado por `ModularityTests` (mismo patrón ya resuelto antes para `RabbitTopologyProperties`)
- [x] T006 [P] Crear `BrevoEmailRequest` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoEmailRequest.java` (record del cuerpo JSON, contracts/brevo-transactional-email.md)
- [x] T007 Crear `BrevoWebClientConfig` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/BrevoWebClientConfig.java` (`@Bean WebClient` sobre `ReactorClientHttpConnector`, `responseTimeout`/`CONNECT_TIMEOUT_MILLIS` desde `BrevoProviderProperties`, sin wiretap)
- [x] T008 [P] Crear ayuda de pruebas `FakeBrevoServer` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/FakeBrevoServer.java` (`com.sun.net.httpserver.HttpServer`, sin dependencias nuevas)

**Checkpoint**: con esto listo, todas las historias de usuario pueden implementarse.

---

## Phase 2: User Story 1 - Las notificaciones de correo se entregan de verdad (P1) 🎯 MVP

**Goal**: con Brevo habilitado y preferente, una notificación de correo produce exactamente una
petición real y queda `DELIVERED` con el `providerId` del proveedor real.

**Independent Test**: ver spec.md, User Story 1, Acceptance Scenarios 1–4.

- [x] T009 [US1] Implementar `BrevoNotificationProvider.send(...)` (camino feliz) en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoNotificationProvider.java`: construir `BrevoEmailRequest` desde `Notification` + `BrevoProviderProperties` (research.md Decisión 5), llamar con `WebClient`, clasificar con `BrevoResponseClassifier`
- [x] T010 [US1] `providerId()` = `ProviderId.of("brevo")` en `BrevoNotificationProvider`
- [x] T011 [US1] Modificar `infrastructure/src/main/resources/application.yml`: canal `EMAIL` lista `${NOTIFICATION_EMAIL_PROVIDERS:simulated,brevo}` + bloque `notification.provider.brevo` sin valores de credencial (research.md Decisión 3 y 12 — stash aplicado antes de editar)
- [x] T012 [US1] Verificado: `ChannelCatalogSeederTest` no depende del `application.yml` real (construye `ChannelCatalogProperties` en memoria) — sin cambios necesarios
- [x] T013 [P] [US1] Pruebas de `BrevoNotificationProvider` (camino feliz) en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoNotificationProviderTest.java`: petición construida contra `FakeBrevoServer`, remitente desde configuración, único destinatario — verde
- [x] T014 [US1] `BrevoEmailDeliveryE2ETest` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoEmailDeliveryE2ETest.java`: 6/6 verde con Testcontainers reales (Mongo+RabbitMQ) — entrega, salto del simulado, clasificación 400/500, no-filtración

**Checkpoint**: MVP funcional e independientemente probable.

---

## Phase 3: User Story 2 - Sin credenciales el proveedor se deshabilita con un motivo visible (P1)

**Goal**: sin `BREVO_API_KEY`/`BREVO_SENDER_EMAIL`, el adaptador se registra deshabilitado, avisa
una vez al arrancar, y `send(...)` falla sin registrar intento ni cambiar el estado.

**Independent Test**: ver spec.md, User Story 2, Acceptance Scenarios 1–5.

- [x] T015 [US2] Decisión de habilitación en el constructor de `BrevoNotificationProvider` (una vez, a partir de `BrevoProviderProperties`) + log `WARN` único con `providerId` y motivo, sin valores de credencial (research.md Decisión 2)
- [x] T016 [US2] `send(...)` de un adaptador deshabilitado devuelve `Mono.error(new ProviderDisabledException(...))` **antes** de construir cualquier petición
- [x] T017 [P] [US2] Pruebas del camino deshabilitado en `BrevoNotificationProviderTest.java`: sin api-key, sin sender-email, y prueba dedicada del aviso único de arranque con `ListAppender` (nombra `brevo`/`sender-email`, nunca la clave) — verde
- [x] T018 [US2] Escenario de proveedor deshabilitado — **desviación**: en un archivo separado, `BrevoDisabledProviderE2ETest.java`, no dentro de `BrevoEmailDeliveryE2ETest.java`, porque requiere un `@SpringBootTest` sin las credenciales que la clase de US1 sí necesita (contextos Spring distintos). 2/2 verde con Testcontainers reales

**Checkpoint**: US1 + US2 funcionan de forma independiente.

---

## Phase 4: User Story 3 - Los fallos del proveedor se clasifican y no se confunden entre sí (P2)

**Goal**: cada familia de respuesta produce exactamente la categoría esperada; una notificación
sin asunto falla sin llamar al proveedor.

**Independent Test**: ver spec.md, User Story 3, Acceptance Scenarios 1–6.

- [x] T019 [US3] Verificado: `BrevoResponseClassifierTest` (T004) cubre las quince filas de research.md Decisión 4, incluidos timeout y error de conexión — 18 pruebas, verde
- [x] T020 [US3] Camino de asunto vacío en `BrevoNotificationProvider.send(...)`: si `content().subject()` es nulo o en blanco, `Mono.just(PERMANENT_FAILURE)` sin llamar al proveedor (research.md Decisión 6, Q1 confirmada)
- [x] T021 [P] [US3] Prueba de asunto vacío en `BrevoNotificationProviderTest.java`: cero peticiones a `FakeBrevoServer`, resultado `PERMANENT_FAILURE` (nulo y en blanco) — verde
- [x] T022 [P] [US3] Prueba de tiempo de espera en `BrevoNotificationProviderTest.java` con aserción explícita de `Duration` (SC-006), usando el retardo programable de `FakeBrevoServer` — verde

**Checkpoint**: US1 + US2 + US3 funcionan de forma independiente.

---

## Phase 5: User Story 4 - Nada sensible sale del componente (P2)

**Goal**: ni la credencial ni el contenido de la notificación aparecen en registros ni en
mensajes internos.

**Independent Test**: ver spec.md, User Story 4, Acceptance Scenarios 1–4.

- [x] T023 [US4] Confirmado por lectura del código: `BrevoNotificationProvider` solo registra `notificationId`, `tenantId`, `providerId`, categoría y código/tipo de error (nunca credencial/asunto/cuerpo/dirección) — research.md Decisión 9
- [x] T024 [US4] Prueba de no filtración en `BrevoEmailDeliveryE2ETest.java`: `ListAppender` + cola temporal en el exchange de eventos + cola de despacho — verde
- [x] T025 [US4] Prueba equivalente para el despacho que falla (deshabilitado) en `BrevoDisabledProviderE2ETest.java`: logs sin asunto/cuerpo/dirección — verde

**Checkpoint**: US1–US4 funcionan de forma independiente.

---

## Phase 6: User Story 5 - Un mensaje repetido no produce dos correos (P3)

**Goal**: cada petición lleva una clave de idempotencia estable por notificación y distinta entre
notificaciones (deduplicación real: riesgo residual documentado, no resuelto por código).

**Independent Test**: ver spec.md, User Story 5, Acceptance Scenarios 1–3.

- [x] T026 [US5] `headers["Idempotency-Key"] = notificationId` en la construcción de `BrevoEmailRequest` (research.md Decisión 8)
- [x] T027 [P] [US5] Prueba en `BrevoNotificationProviderTest.java`: dos despachos de la misma notificación llevan la misma clave; dos notificaciones distintas llevan claves distintas (SC-009) — verde

**Checkpoint**: las cinco historias de usuario son independientemente funcionales.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T028 Ejecutar `spotless:apply` tras cada clase nueva o modificada
- [x] T029 Confirmar `HexagonalArchitectureTest` y `ModularityTests` en verde — verde (incluye la reubicación de `BrevoProviderProperties` a `config` para no crear un ciclo de módulos)
- [x] T030 Confirmar cobertura ≥80 % líneas / ≥70 % ramas de los archivos nuevos — "All coverage checks have been met" en `core` e `infrastructure`
- [x] T031 `./mvnw -B -ntp verify` completo; las únicas dos pruebas que fallan en la corrida sin exclusiones son `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest` (broker local en `localhost:5673`, no Testcontainers, fallo preexistente y conocido de este entorno). Excluyéndolas: **BUILD SUCCESS**, 4/4 módulos, 0 bugs SpotBugs, cobertura cumplida. Nota: `BrevoDisabledProviderE2ETest` falló una vez por temporización bajo carga (mismo patrón de espera de DLQ) corriendo junto a toda la suite; confirmado 3 veces en aislamiento que pasa de forma consistente, y se amplió su margen de espera de 25s a 45s
- [x] T032 `git stash pop` del cambio ajeno de `application.yml` — sin conflicto
- [ ] T033 Validación automatizada de `quickstart.md`: pendiente, no ejecutada en esta sesión

---

## Dependencies & Execution Order

- **Foundational (Phase 1)**: sin dependencias externas — bloquea todas las historias.
- **US1 (Phase 2)**: depende de Phase 1. Es el MVP.
- **US2 (Phase 3)**: depende de Phase 1 y de que `BrevoNotificationProvider` exista (Phase 2, T009-T010), porque agrega el camino deshabilitado a la misma clase.
- **US3 (Phase 4)**: depende de Phase 1 (clasificador ya cubierto) y de Phase 2 (agrega el camino de asunto vacío a `send(...)`).
- **US4 (Phase 5)**: depende de Phase 2 y 3 (necesita un despacho real y uno deshabilitado que inspeccionar).
- **US5 (Phase 6)**: depende de Phase 2 (agrega la cabecera a la construcción del cuerpo).
- **Polish (Phase 7)**: depende de todas las anteriores.

### Parallel Opportunities

- T002, T003, T004, T005, T006, T008 son paralelos entre sí (archivos distintos, sin dependencias).
- Dentro de cada historia, las tareas marcadas [P] tocan archivos de prueba distintos entre sí.

## Implementation Strategy

MVP = Phase 1 + Phase 2 (US1). El resto de historias son incrementales sobre la misma clase
`BrevoNotificationProvider`, en el orden de prioridad P1 → P1 → P2 → P2 → P3 que fija spec.md.
