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

- [x] T001 Aplicar `contracts/api-notificaciones-cambios.md` a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: descripción del tag `Catálogo`, paths `/channels` (get `listChannels`) y `/providers` (get `listProviders`), y los esquemas `ProviderStatus`, `ChannelCatalogResponse`, `ChannelItem`, `ChannelProviderItem`, `ProviderCatalogResponse`, `ProviderItem`; corregir la frase "hoy el catálogo es estático en application.yml" de `/channels:register`, que dejó de ser cierta con HU2-042. **Añadido**: también se ajustó la descripción de `/channels:register` ("Bloqueado: el catálogo dinámico ya existe, pero su escritura por API aún no está habilitada")

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: cambios de puertos que todas las historias necesitan.

- [x] T002 Añadir el método abstracto `Optional<String> disabledReason()` a `core-main/port/out/NotificationSenderPort.java` e implementarlo en `infra-main/adapter/out/provider/BrevoNotificationProvider.java`, `TwilioNotificationProvider.java`, `FcmNotificationProvider.java` (devuelven el campo `disabledReason` existente) y `SimulatedNotificationProvider.java` (`Optional.empty()`), y en los dobles `core-test/port/out/NotificationSenderRegistryTest.java` (`FakeSender`) e `infra-test/adapter/out/provider/ProviderRoutingE2ETest.java` (`RecordingNotificationSender`); `./mvnw -B -ntp clean compile` y compilación de pruebas en verde antes de seguir (research.md Decisión 2)
- [x] T003 [P] Pruebas de `disabledReason()` en `infra-test/adapter/out/provider/BrevoNotificationProviderTest.java`, `TwilioNotificationProviderTest.java`, `FcmNotificationProviderTest.java` (presente con el motivo esperado sin credenciales, vacío con credenciales completas) y `SimulatedNotificationProviderTest.java` (vacío) — depende de T002. **Desviación de proceso**: como planeaba el orden T002 → T003, la implementación ya existía al escribir estas pruebas; pasaron a la primera, sin rojo observado. FCM usa el mismo `@MethodSource("disabledConfigurations")` de las pruebas existentes y además afirma que el motivo no contiene fragmentos de la credencial
- [x] T004 [P] Pruebas en `core-test/port/out/NotificationSenderRegistryTest.java`: `find` devuelve el adaptador registrado y vacío para un id desconocido, rechaza nulo; `providerIds` devuelve exactamente los ids registrados y es inmutable
- [x] T005 Implementar `find(ProviderId)` y `providerIds()` en `core-main/port/out/NotificationSenderRegistry.java` — depende de T004
- [x] T006 [P] Pruebas en `infra-test/adapter/out/catalog/MongoChannelCatalogAdapterTest.java`: `findAllRoutes` devuelve todas las rutas del snapshot con `channelType` igual a la clave (mayúsculas) aunque la ruta traiga el canal en minúsculas; snapshot vacío → vacío
- [x] T007 Añadir `Flux<ChannelRoute> findAllRoutes()` a `core-main/port/out/ChannelCatalogPort.java` e implementarlo en `infra-main/adapter/out/catalog/MongoChannelCatalogAdapter.java` sobre `ChannelCatalogCache.snapshot()` (research.md Decisión 4) — depende de T006

**Checkpoint**: `core` e `infrastructure` compilan; registro, adaptador de catálogo y adaptadores de proveedor verdes.

---

## Phase 3: User Story 1 - Ver cómo se enruta cada canal (P1) 🎯 MVP

**Goal**: `GET /channels` devuelve los canales ordenados con sus proveedores en orden de preferencia y su forma de contenido.

**Independent Test**: spec.md, User Story 1, escenarios 1–4.

- [x] T008 [P] [US1] Crear las vistas en `core-main/port/in/`: `ProviderStatus.java` (enum `ENABLED`, `DISABLED`, `MISSING_ADAPTER`), `ChannelView.java`, `ChannelProviderView.java`, `ProviderView.java`, `ProviderChannelView.java` (records con validación de no nulos, `List.copyOf`, `preferenceOrder >= 1`, `statusReason == null` si y solo si `ENABLED`; data-model.md) y `QueryChannelCatalogUseCase.java` (`Mono<List<ChannelView>> listChannels()`, `Mono<List<ProviderView>> listProviders()`). **Añadido no planificado**: `core-test/port/in/ChannelProviderViewTest.java` y `ProviderViewTest.java` cubren los invariantes de data-model.md (`statusReason` presente solo si no es `ENABLED`, `preferenceOrder >= 1`, no nulos), que son reglas y no solo transporte
- [x] T009 [US1] Pruebas de `listChannels` en `core-test/usecase/QueryChannelCatalogServiceTest.java` con `StepVerifier`: orden alfabético de canales, posiciones 1-based en el orden guardado, proveedor repetido en un canal aparece en sus dos posiciones, esquema en blanco o nulo → `null`, esquema presente tal cual, catálogo vacío → lista vacía; constructor rechaza nulos — depende de T005, T007, T008
- [x] T010 [US1] Implementar `core-main/usecase/QueryChannelCatalogService.java` (`listChannels` y la derivación de estado privada de research.md Decisión 2) — depende de T009. **Desviación**: `listProviders` quedó transitoriamente como `Mono.error(new UnsupportedOperationException())` para que la interfaz compilara; T017 lo vio fallar por esa razón (4 fallos) y T018 lo reemplazó. No queda ningún rastro del esbozo
- [x] T011 [US1] Registrar el bean `QueryChannelCatalogUseCase` en `infra-main/config/UseCaseConfig.java` — depende de T010
- [x] T012 [P] [US1] Crear los DTOs en `infra-main/adapter/in/rest/`: `ChannelCatalogResponse.java`, `ChannelItemResponse.java`, `ChannelProviderItemResponse.java` (records con `from(...)` estáticos; `status` como `name()`). **Desviación**: los tres DTOs de T019 se crearon en el mismo paso
- [x] T013 [US1] Pruebas de `GET /channels` en `infra-test/adapter/in/rest/ChannelCatalogControllerTest.java` (`@WebFluxTest`, `@MockBean QueryChannelCatalogUseCase`): mapeo de todos los campos, `contentSchema` y `statusReason` nulos explícitos, `items: []` — depende de T008, T012. **Desviación**: una sola clase de prueba cubrió en el mismo ciclo T013, T020 y T022 (ambas rutas y los `400`); el rojo observado fue el de compilación (controlador ausente)
- [x] T014 [US1] Implementar `GET /channels` en `infra-main/adapter/in/rest/ChannelCatalogController.java` (`@RequestHeader(name = "X-Tenant-Id", required = false)` + `TenantId.of` para validar; research.md Decisión 1) — depende de T013. **Desviación**: el controlador se implementó con las dos rutas a la vez (T014 + T021). La validación del tenant es `Mono.fromCallable(() -> TenantId.of(tenantId))` encadenado con `flatMap` al caso de uso, en lugar de una llamada síncrona con el resultado descartado: mismo `400 ErrorResponse`, sin valor de retorno ignorado y sin tocar el caso de uso cuando el tenant es inválido

**Checkpoint**: `GET /channels` responde con el catálogo; controlador y caso de uso verdes.

---

## Phase 4: User Story 2 - Saber si cada proveedor puede enviar de verdad (P1)

**Goal**: cada proveedor mostrado lleva `ENABLED`, `DISABLED` con motivo o `MISSING_ADAPTER` con motivo fijo, y ese estado es el que encuentra el despacho.

**Independent Test**: spec.md, User Story 2, escenarios 1–4.

- [x] T015 [US2] Pruebas de estado en `core-test/usecase/QueryChannelCatalogServiceTest.java`: adaptador sin motivo → `ENABLED` y `statusReason` nulo; adaptador con motivo → `DISABLED` con ese motivo; sin adaptador → `MISSING_ADAPTER` con `no notification sender registered for this provider` — depende de T010
- [x] T016 [US2] Ajustar `core-main/usecase/QueryChannelCatalogService.java` si T015 lo exige (la derivación ya se creó en T010) — depende de T015. Sin cambios: la derivación de T010 ya cumplía las tres pruebas de estado

---

## Phase 5: User Story 3 - Ver cada proveedor y dónde se usa (P2)

**Goal**: `GET /providers` devuelve la unión de adaptadores y proveedores del catálogo, con su estado y sus canales.

**Independent Test**: spec.md, User Story 3, escenarios 1–3.

- [x] T017 [US3] Pruebas de `listProviders` en `core-test/usecase/QueryChannelCatalogServiceTest.java`: proveedor en varios canales aparece una vez con todos; adaptador sin canal aparece con `channels` vacío; proveedor del catálogo sin adaptador aparece `MISSING_ADAPTER` con sus canales; repetición en un canal da dos entradas; orden por `providerId` y canales por `channelType` y posición; catálogo vacío lista solo los adaptadores — depende de T010
- [x] T018 [US3] Implementar `listProviders` en `core-main/usecase/QueryChannelCatalogService.java` — depende de T017
- [x] T019 [P] [US3] Crear los DTOs `ProviderCatalogResponse.java`, `ProviderItemResponse.java`, `ProviderChannelItemResponse.java` en `infra-main/adapter/in/rest/`. Hecho dentro de T012
- [x] T020 [US3] Pruebas de `GET /providers` en `infra-test/adapter/in/rest/ChannelCatalogControllerTest.java`: mapeo de campos, `channels` vacío, `statusReason` nulo explícito — depende de T019. Hecho dentro de T013
- [x] T021 [US3] Implementar `GET /providers` en `infra-main/adapter/in/rest/ChannelCatalogController.java` — depende de T020. Hecho dentro de T014

---

## Phase 6: User Story 4 - La consulta es global y no filtra nada sensible (P2)

**Goal**: `X-Tenant-Id` exigido, respuesta idéntica para todos los tenants, ningún valor de credencial, sin efectos sobre el catálogo.

**Independent Test**: spec.md, User Story 4, escenarios 1–4.

- [x] T022 [US4] Pruebas en `infra-test/adapter/in/rest/ChannelCatalogControllerTest.java`: sin cabecera y con cabecera en blanco → `400` con cuerpo `{"message": ...}` en ambas rutas, sin invocar el caso de uso — depende de T014, T021. Hecho dentro de T013 (parametrizada por ruta)

---

## Phase 7: Prueba end-to-end (Principio IV)

**Purpose**: la E2E explícita de la historia, que recorre controlador, caso de uso, snapshot y adaptadores reales.

- [x] T023 Crear `infra-test/adapter/in/rest/ChannelCatalogQueryE2ETest.java` (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` con MongoDB 7 y RabbitMQ 3 + `WebTestClient`; propiedades `notification.catalog.refresh-interval-ms=1000`, `notification.scheduler.requeue-interval-ms=600000` y `notification.provider.twilio.auth-token` con un valor reconocible sin `account-sid`; `@BeforeEach` restaura los tres documentos por defecto de `channel_catalog` y espera a que `GET /channels` los refleje). Casos: SC-001 (posición 1 de EMAIL = `simulated` `ENABLED`, y una notificación EMAIL aceptada por HTTP termina `DELIVERED` con ese `providerId`); SC-002 (para cada proveedor de `GET /providers`, contra el bean `NotificationSenderRegistry`: `ENABLED` → `send` emite un resultado; `DISABLED` → `send` falla con `ProviderDisabledException`; `MISSING_ADAPTER` → `resolve` lanza `ProviderNotAvailableException`, usando un canal extra con un proveedor inexistente); SC-003 (dos tenants, cuerpos idénticos en ambas rutas y sin el identificador de tenant); SC-004 (el valor del auth-token no aparece en ninguna respuesta y `TWILIO_ACCOUNT_SID` sí — control positivo); SC-005 (se cambia el orden de un canal en MongoDB y se afirma con `Duration` explícita ≤ 5 s hasta verlo en `GET /channels`, y en ese instante `ChannelCatalogPort.findActiveRoute` ya devuelve el preferente nuevo); FR-010 (documentos de `channel_catalog` idénticos antes y después de consultar); `400` sin cabecera — depende de T014, T021. **Ajuste**: la prueba fija además `notification.provider.brevo.api-key`, `notification.provider.fcm.credentials-json` y `credentials-file` vacíos para no depender de variables de entorno del equipo. 9/9 en verde (81 s)

---

## Phase 8: Polish & Cross-Cutting Concerns

- [x] T024 Regresión: `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, `ChannelCatalogResilienceE2ETest`, `NotificationControllerTest`, pruebas de proveedores, `HexagonalArchitectureTest` y `ModularityTests` en verde. Cubierto por el `verify` completo de T026 (las siete clases en verde)
- [x] T025 `./mvnw -B -ntp spotless:apply` y revisión del diff: cero comentarios explicativos en código nuevo (Principio III), ningún cambio ajeno (`Recipient.java`, `application.yml`, `.claude/`, `docs/`, `qodana.yaml`) en los commits. Sin comentarios en el código nuevo; los cambios ajenos quedaron fuera de los commits
- [x] T026 `./mvnw -B -ntp verify` completo y en verde (cobertura ≥ 80 % líneas / ≥ 70 % ramas); si las únicas que fallan son `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo explícitamente en el informe. `clean verify` en verde (12 min 24 s): 11 pruebas en `utils`, 286 en `core`, 348 en `infrastructure`, 0 fallos, SpotBugs 0, Spotless OK. Cobertura: `core` 100 % líneas / 94,5 % ramas; `infrastructure` 98,1 % / 86,3 %; `utils` 100 % / 100 %. `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest` también en verde, con un broker temporal en el puerto 5673; no hizo falta excluirlas

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
