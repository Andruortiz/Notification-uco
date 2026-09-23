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

- [ ] T001 Crear `ProviderDisabledException` en `core/src/main/java/co/edu/uco/notification/core/exception/ProviderDisabledException.java` (hermana de `ProviderNotAvailableException`; recibe `ProviderId` + motivo)
- [ ] T002 [P] Prueba de `ProviderDisabledException` en `core/src/test/java/co/edu/uco/notification/core/exception/ProviderDisabledExceptionTest.java`
- [ ] T003 [P] Crear `BrevoResponseClassifier` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoResponseClassifier.java` (función pura y total, research.md Decisión 4, quince filas)
- [ ] T004 [P] Prueba de `BrevoResponseClassifier` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoResponseClassifierTest.java` (las quince filas)
- [ ] T005 [P] Crear `BrevoProviderProperties` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoProviderProperties.java` (`@ConfigurationProperties("notification.provider.brevo")`: api-key, sender-email, sender-name, base-url, timeout-ms, connect-timeout-ms)
- [ ] T006 [P] Crear `BrevoEmailRequest` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoEmailRequest.java` (record del cuerpo JSON, contracts/brevo-transactional-email.md)
- [ ] T007 Crear `BrevoWebClientConfig` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/BrevoWebClientConfig.java` (`@Bean WebClient` sobre `ReactorClientHttpConnector`, `responseTimeout`/`CONNECT_TIMEOUT_MILLIS` desde `BrevoProviderProperties`, sin wiretap)
- [ ] T008 [P] Crear ayuda de pruebas `FakeBrevoServer` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/FakeBrevoServer.java` (`com.sun.net.httpserver.HttpServer`, sin dependencias nuevas)

**Checkpoint**: con esto listo, todas las historias de usuario pueden implementarse.

---

## Phase 2: User Story 1 - Las notificaciones de correo se entregan de verdad (P1) 🎯 MVP

**Goal**: con Brevo habilitado y preferente, una notificación de correo produce exactamente una
petición real y queda `DELIVERED` con el `providerId` del proveedor real.

**Independent Test**: ver spec.md, User Story 1, Acceptance Scenarios 1–4.

- [ ] T009 [US1] Implementar `BrevoNotificationProvider.send(...)` (camino feliz) en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoNotificationProvider.java`: construir `BrevoEmailRequest` desde `Notification` + `BrevoProviderProperties` (research.md Decisión 5), llamar con `WebClient`, clasificar con `BrevoResponseClassifier`
- [ ] T010 [US1] `providerId()` = `ProviderId.of("brevo")` en `BrevoNotificationProvider`
- [ ] T011 [US1] Modificar `infrastructure/src/main/resources/application.yml`: canal `EMAIL` lista `${NOTIFICATION_EMAIL_PROVIDERS:simulated,brevo}` + bloque `notification.provider.brevo` sin valores de credencial (research.md Decisión 3 y 12 — seguir el procedimiento de stash antes de tocar este archivo)
- [ ] T012 [US1] Modificar `ChannelCatalogSeederTest` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogSeederTest.java` si hace falta reflejar el nuevo valor sembrado
- [ ] T013 [P] [US1] Pruebas de `BrevoNotificationProvider` (camino feliz) en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoNotificationProviderTest.java`: petición construida contra `FakeBrevoServer`, remitente desde configuración, único destinatario
- [ ] T014 [US1] `BrevoEmailDeliveryE2ETest` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoEmailDeliveryE2ETest.java`: escenario de entrega completa contra `FakeBrevoServer` + Testcontainers (Mongo, RabbitMQ) + `WebTestClient` (Acceptance Scenarios 1, 2, 4); escenario con `simulated` preferente sin llamar a Brevo (Scenario 3)

**Checkpoint**: MVP funcional e independientemente probable.

---

## Phase 3: User Story 2 - Sin credenciales el proveedor se deshabilita con un motivo visible (P1)

**Goal**: sin `BREVO_API_KEY`/`BREVO_SENDER_EMAIL`, el adaptador se registra deshabilitado, avisa
una vez al arrancar, y `send(...)` falla sin registrar intento ni cambiar el estado.

**Independent Test**: ver spec.md, User Story 2, Acceptance Scenarios 1–5.

- [ ] T015 [US2] Decisión de habilitación en el constructor de `BrevoNotificationProvider` (una vez, a partir de `BrevoProviderProperties`) + log `WARN` único con `providerId` y motivo, sin valores de credencial (research.md Decisión 2)
- [ ] T016 [US2] `send(...)` de un adaptador deshabilitado devuelve `Mono.error(new ProviderDisabledException(...))` **antes** de construir cualquier petición
- [ ] T017 [P] [US2] Pruebas del camino deshabilitado en `BrevoNotificationProviderTest.java`: sin api-key, sin sender-email, con ambas presentes (no emite aviso)
- [ ] T018 [US2] Escenario de proveedor deshabilitado en `BrevoEmailDeliveryE2ETest.java`: arranque sin credenciales, notificación no entregada, no marcada fallida, sin intento, estado intacto, mensaje trazable (mismo patrón que `ProviderRoutingE2ETest.leavesATraceableFailureWhenThePreferredProviderHasNoAdapter`)

**Checkpoint**: US1 + US2 funcionan de forma independiente.

---

## Phase 4: User Story 3 - Los fallos del proveedor se clasifican y no se confunden entre sí (P2)

**Goal**: cada familia de respuesta produce exactamente la categoría esperada; una notificación
sin asunto falla sin llamar al proveedor.

**Independent Test**: ver spec.md, User Story 3, Acceptance Scenarios 1–6.

- [ ] T019 [US3] Verificar que `BrevoResponseClassifierTest` (T004) cubre las quince filas de research.md Decisión 4, incluidos timeout y error de conexión
- [ ] T020 [US3] Camino de asunto vacío en `BrevoNotificationProvider.send(...)`: si `content().subject()` es nulo o en blanco, `Mono.just(PERMANENT_FAILURE)` sin llamar al proveedor (research.md Decisión 6, Q1 confirmada)
- [ ] T021 [P] [US3] Prueba de asunto vacío en `BrevoNotificationProviderTest.java`: cero peticiones a `FakeBrevoServer`, resultado `PERMANENT_FAILURE`
- [ ] T022 [P] [US3] Prueba de tiempo de espera en `BrevoNotificationProviderTest.java` con aserción explícita de `Duration` (SC-006), usando el retardo programable de `FakeBrevoServer`

**Checkpoint**: US1 + US2 + US3 funcionan de forma independiente.

---

## Phase 5: User Story 4 - Nada sensible sale del componente (P2)

**Goal**: ni la credencial ni el contenido de la notificación aparecen en registros ni en
mensajes internos.

**Independent Test**: ver spec.md, User Story 4, Acceptance Scenarios 1–4.

- [ ] T023 [US4] Confirmar que `BrevoNotificationProvider` solo registra `notificationId`, `tenantId`, `providerId`, categoría y código de estado (nunca credencial/asunto/cuerpo/dirección) — research.md Decisión 9
- [ ] T024 [US4] Prueba de no filtración en `BrevoEmailDeliveryE2ETest.java`: `ListAppender` de Logback enganchado al logger raíz durante un despacho completo, más una cola temporal en el exchange de eventos; afirmar ausencia del valor de la api-key, el asunto, el cuerpo y la dirección (SC-004)
- [ ] T025 [US4] Prueba equivalente para el despacho que falla (deshabilitado): el registro del fallo contiene notificationId/tenant/provider/categoría y nada prohibido

**Checkpoint**: US1–US4 funcionan de forma independiente.

---

## Phase 6: User Story 5 - Un mensaje repetido no produce dos correos (P3)

**Goal**: cada petición lleva una clave de idempotencia estable por notificación y distinta entre
notificaciones (deduplicación real: riesgo residual documentado, no resuelto por código).

**Independent Test**: ver spec.md, User Story 5, Acceptance Scenarios 1–3.

- [ ] T026 [US5] `headers["Idempotency-Key"] = notificationId` en la construcción de `BrevoEmailRequest` (research.md Decisión 8)
- [ ] T027 [P] [US5] Prueba en `BrevoNotificationProviderTest.java`: dos despachos de la misma notificación llevan la misma clave; dos notificaciones distintas llevan claves distintas (SC-009)

**Checkpoint**: las cinco historias de usuario son independientemente funcionales.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T028 Ejecutar `spotless:apply` tras cada clase nueva o modificada
- [ ] T029 Confirmar `HexagonalArchitectureTest` y `ModularityTests` en verde
- [ ] T030 Confirmar cobertura ≥80 % líneas / ≥70 % ramas de los archivos nuevos (`./mvnw -B -ntp verify`)
- [ ] T031 `./mvnw -B -ntp verify` completo; si únicamente fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y reportarlo explícitamente (plan.md, paso 7)
- [ ] T032 `git stash pop` del cambio ajeno de `application.yml` (research.md Decisión 12); si hay conflicto, no resolverlo aquí — reportar el comando exacto
- [ ] T033 Ejecutar la validación automatizada de `quickstart.md` (sección previa a la prueba manual de humo)

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
