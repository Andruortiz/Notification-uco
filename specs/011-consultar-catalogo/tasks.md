---

description: "Task list for HU2-085"
---

# Tasks: Consultar el catálogo de canales y proveedores

**Input**: Design documents from `/specs/011-consultar-catalogo/`

**Prerequisites**: plan.md (Aceptado), spec.md, research.md, data-model.md,
contracts/api-notificaciones-cambios.md, quickstart.md

**Tests**: Solicitadas explícitamente por el plan (Principio IV): unitarias en `core` con `StepVerifier`,
pruebas de adaptador y de controlador, y una E2E nueva con Testcontainers + `WebTestClient`. Orden por
tarea: prueba → verla fallar por la razón correcta → implementar → ejecutar la clase → `spotless:apply`.

**Organization**: fase fundacional compartida (contrato y cambios de puertos que todas las historias
necesitan) y luego una fase por historia de usuario del spec (US1–US4).

## Path Conventions

- `core-main/` = `core/src/main/java/co/edu/uco/notification/core/`
- `core-test/` = `core/src/test/java/co/edu/uco/notification/core/`
- `infra-main/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/`
- `infra-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/`

---

## Phase 1: Setup

**Purpose**: contrato público primero (Principio II).

- [ ] T001 Aplicar `contracts/api-notificaciones-cambios.md` a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: descripción del tag `Catálogo`, paths `/channels` (get `listChannels`) y `/providers` (get `listProviders`), y los esquemas `ProviderStatus`, `ChannelCatalogResponse`, `ChannelItem`, `ChannelProviderItem`, `ProviderCatalogResponse`, `ProviderItem`; corregir la frase "hoy el catálogo es estático en application.yml" de `/channels:register`, que dejó de ser cierta con HU2-042

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: cambios de puertos que todas las historias necesitan.

- [ ] T002 Añadir el método abstracto `Optional<String> disabledReason()` a `core-main/port/out/NotificationSenderPort.java` e implementarlo en `infra-main/adapter/out/provider/BrevoNotificationProvider.java`, `TwilioNotificationProvider.java`, `FcmNotificationProvider.java` (devuelven el campo `disabledReason` existente) y `SimulatedNotificationProvider.java` (`Optional.empty()`), y en los dobles `core-test/port/out/NotificationSenderRegistryTest.java` (`FakeSender`) e `infra-test/adapter/out/provider/ProviderRoutingE2ETest.java` (`RecordingNotificationSender`); `./mvnw -B -ntp clean compile` y compilación de pruebas en verde antes de seguir (research.md Decisión 2)
- [ ] T003 [P] Pruebas de `disabledReason()` en `infra-test/adapter/out/provider/BrevoNotificationProviderTest.java`, `TwilioNotificationProviderTest.java`, `FcmNotificationProviderTest.java` (presente con el motivo esperado sin credenciales, vacío con credenciales completas) y `SimulatedNotificationProviderTest.java` (vacío) — depende de T002
- [ ] T004 [P] Pruebas en `core-test/port/out/NotificationSenderRegistryTest.java`: `find` devuelve el adaptador registrado y vacío para un id desconocido, rechaza nulo; `providerIds` devuelve exactamente los ids registrados y es inmutable
- [ ] T005 Implementar `find(ProviderId)` y `providerIds()` en `core-main/port/out/NotificationSenderRegistry.java` — depende de T004
- [ ] T006 [P] Pruebas en `infra-test/adapter/out/catalog/MongoChannelCatalogAdapterTest.java`: `findAllRoutes` devuelve todas las rutas del snapshot con `channelType` igual a la clave (mayúsculas) aunque la ruta traiga el canal en minúsculas; snapshot vacío → vacío
- [ ] T007 Añadir `Flux<ChannelRoute> findAllRoutes()` a `core-main/port/out/ChannelCatalogPort.java` e implementarlo en `infra-main/adapter/out/catalog/MongoChannelCatalogAdapter.java` sobre `ChannelCatalogCache.snapshot()` (research.md Decisión 4) — depende de T006

**Checkpoint**: `core` e `infrastructure` compilan; registro, adaptador de catálogo y adaptadores de proveedor verdes.

---

## Phase 3: User Story 1 - Ver cómo se enruta cada canal (P1) 🎯 MVP

**Goal**: `GET /channels` devuelve los canales ordenados con sus proveedores en orden de preferencia y su forma de contenido.

**Independent Test**: spec.md, User Story 1, escenarios 1–4.

- [ ] T008 [P] [US1] Crear las vistas en `core-main/port/in/`: `ProviderStatus.java` (enum `ENABLED`, `DISABLED`, `MISSING_ADAPTER`), `ChannelView.java`, `ChannelProviderView.java`, `ProviderView.java`, `ProviderChannelView.java` (records con validación de no nulos, `List.copyOf`, `preferenceOrder >= 1`, `statusReason == null` si y solo si `ENABLED`; data-model.md) y `QueryChannelCatalogUseCase.java` (`Mono<List<ChannelView>> listChannels()`, `Mono<List<ProviderView>> listProviders()`)
- [ ] T009 [US1] Pruebas de `listChannels` en `core-test/usecase/QueryChannelCatalogServiceTest.java` con `StepVerifier`: orden alfabético de canales, posiciones 1-based en el orden guardado, proveedor repetido en un canal aparece en sus dos posiciones, esquema en blanco o nulo → `null`, esquema presente tal cual, catálogo vacío → lista vacía; constructor rechaza nulos — depende de T005, T007, T008
- [ ] T010 [US1] Implementar `core-main/usecase/QueryChannelCatalogService.java` (`listChannels` y la derivación de estado privada de research.md Decisión 2) — depende de T009
- [ ] T011 [US1] Registrar el bean `QueryChannelCatalogUseCase` en `infra-main/config/UseCaseConfig.java` — depende de T010
- [ ] T012 [P] [US1] Crear los DTOs en `infra-main/adapter/in/rest/`: `ChannelCatalogResponse.java`, `ChannelItemResponse.java`, `ChannelProviderItemResponse.java` (records con `from(...)` estáticos; `status` como `name()`)
- [ ] T013 [US1] Pruebas de `GET /channels` en `infra-test/adapter/in/rest/ChannelCatalogControllerTest.java` (`@WebFluxTest`, `@MockBean QueryChannelCatalogUseCase`): mapeo de todos los campos, `contentSchema` y `statusReason` nulos explícitos, `items: []` — depende de T008, T012
- [ ] T014 [US1] Implementar `GET /channels` en `infra-main/adapter/in/rest/ChannelCatalogController.java` (`@RequestHeader(name = "X-Tenant-Id", required = false)` + `TenantId.of` para validar; research.md Decisión 1) — depende de T013

**Checkpoint**: `GET /channels` responde con el catálogo; controlador y caso de uso verdes.

---

## Phase 4: User Story 2 - Saber si cada proveedor puede enviar de verdad (P1)

**Goal**: cada proveedor mostrado lleva `ENABLED`, `DISABLED` con motivo o `MISSING_ADAPTER` con motivo fijo, y ese estado es el que encuentra el despacho.

**Independent Test**: spec.md, User Story 2, escenarios 1–4.

- [ ] T015 [US2] Pruebas de estado en `core-test/usecase/QueryChannelCatalogServiceTest.java`: adaptador sin motivo → `ENABLED` y `statusReason` nulo; adaptador con motivo → `DISABLED` con ese motivo; sin adaptador → `MISSING_ADAPTER` con `no notification sender registered for this provider` — depende de T010
- [ ] T016 [US2] Ajustar `core-main/usecase/QueryChannelCatalogService.java` si T015 lo exige (la derivación ya se creó en T010) — depende de T015

---

## Phase 5: User Story 3 - Ver cada proveedor y dónde se usa (P2)

**Goal**: `GET /providers` devuelve la unión de adaptadores y proveedores del catálogo, con su estado y sus canales.

**Independent Test**: spec.md, User Story 3, escenarios 1–3.

- [ ] T017 [US3] Pruebas de `listProviders` en `core-test/usecase/QueryChannelCatalogServiceTest.java`: proveedor en varios canales aparece una vez con todos; adaptador sin canal aparece con `channels` vacío; proveedor del catálogo sin adaptador aparece `MISSING_ADAPTER` con sus canales; repetición en un canal da dos entradas; orden por `providerId` y canales por `channelType` y posición; catálogo vacío lista solo los adaptadores — depende de T010
- [ ] T018 [US3] Implementar `listProviders` en `core-main/usecase/QueryChannelCatalogService.java` — depende de T017
- [ ] T019 [P] [US3] Crear los DTOs `ProviderCatalogResponse.java`, `ProviderItemResponse.java`, `ProviderChannelItemResponse.java` en `infra-main/adapter/in/rest/`
- [ ] T020 [US3] Pruebas de `GET /providers` en `infra-test/adapter/in/rest/ChannelCatalogControllerTest.java`: mapeo de campos, `channels` vacío, `statusReason` nulo explícito — depende de T019
- [ ] T021 [US3] Implementar `GET /providers` en `infra-main/adapter/in/rest/ChannelCatalogController.java` — depende de T020

---

## Phase 6: User Story 4 - La consulta es global y no filtra nada sensible (P2)

**Goal**: `X-Tenant-Id` exigido, respuesta idéntica para todos los tenants, ningún valor de credencial, sin efectos sobre el catálogo.

**Independent Test**: spec.md, User Story 4, escenarios 1–4.

- [ ] T022 [US4] Pruebas en `infra-test/adapter/in/rest/ChannelCatalogControllerTest.java`: sin cabecera y con cabecera en blanco → `400` con cuerpo `{"message": ...}` en ambas rutas, sin invocar el caso de uso — depende de T014, T021

---

## Phase 7: Prueba end-to-end (Principio IV)

**Purpose**: la E2E explícita de la historia, que recorre controlador, caso de uso, snapshot y adaptadores reales.

- [ ] T023 Crear `infra-test/adapter/in/rest/ChannelCatalogQueryE2ETest.java` (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` con MongoDB 7 y RabbitMQ 3 + `WebTestClient`; propiedades `notification.catalog.refresh-interval-ms=1000`, `notification.scheduler.requeue-interval-ms=600000` y `notification.provider.twilio.auth-token` con un valor reconocible sin `account-sid`; `@BeforeEach` restaura los tres documentos por defecto de `channel_catalog` y espera a que `GET /channels` los refleje). Casos: SC-001 (posición 1 de EMAIL = `simulated` `ENABLED`, y una notificación EMAIL aceptada por HTTP termina `DELIVERED` con ese `providerId`); SC-002 (para cada proveedor de `GET /providers`, contra el bean `NotificationSenderRegistry`: `ENABLED` → `send` emite un resultado; `DISABLED` → `send` falla con `ProviderDisabledException`; `MISSING_ADAPTER` → `resolve` lanza `ProviderNotAvailableException`, usando un canal extra con un proveedor inexistente); SC-003 (dos tenants, cuerpos idénticos en ambas rutas y sin el identificador de tenant); SC-004 (el valor del auth-token no aparece en ninguna respuesta y `TWILIO_ACCOUNT_SID` sí — control positivo); SC-005 (se cambia el orden de un canal en MongoDB y se afirma con `Duration` explícita ≤ 5 s hasta verlo en `GET /channels`, y en ese instante `ChannelCatalogPort.findActiveRoute` ya devuelve el preferente nuevo); FR-010 (documentos de `channel_catalog` idénticos antes y después de consultar); `400` sin cabecera — depende de T014, T021

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T024 Regresión: `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, `ChannelCatalogResilienceE2ETest`, `NotificationControllerTest`, pruebas de proveedores, `HexagonalArchitectureTest` y `ModularityTests` en verde
- [ ] T025 `./mvnw -B -ntp spotless:apply` y revisión del diff: cero comentarios explicativos en código nuevo (Principio III), ningún cambio ajeno (`Recipient.java`, `application.yml`, `.claude/`, `docs/`, `qodana.yaml`) en los commits
- [ ] T026 `./mvnw -B -ntp verify` completo y en verde (cobertura ≥ 80 % líneas / ≥ 70 % ramas); si las únicas que fallan son `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo explícitamente en el informe

---

## Dependencies & Execution Order

- Phase 1 (T001) antes de cualquier código de controlador (Principio II).
- Phase 2 bloquea todas las historias: T002 → T003; T004 → T005; T006 → T007.
- US1 (T008–T014) necesita Phase 2. US2 (T015–T016) y US3 (T017–T021) necesitan T010. US4 (T022) necesita
  los dos endpoints. La E2E (T023) necesita US1 y US3.
- Polish al final.

## Parallel Opportunities

- T003, T004 y T006 tocan archivos distintos tras T002.
- T008 y T012 (vistas de `core` y DTOs de `infrastructure`) en paralelo; T019 en paralelo con T017.

## Implementation Strategy

MVP = Phase 1 + Phase 2 + US1 (`GET /channels` con estado). US2 endurece la derivación de estado ya creada,
US3 añade `GET /providers`, US4 fija los rechazos y la E2E cierra SC-001 a SC-005.
