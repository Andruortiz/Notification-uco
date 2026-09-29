---

description: "Task list for HU2-092"
---

# Tasks: Adjuntar un archivo a una notificación

**Input**: Design documents from `/specs/012-adjuntar-archivo-notificacion/`

**Prerequisites**: plan.md (Aceptado), spec.md (Q1–Q5 confirmadas), research.md, data-model.md,
contracts/api-notificaciones-cambios.md, quickstart.md

**Tests**: Solicitadas explícitamente por el plan (Principio IV): unitarias en `core` con `StepVerifier`,
integración del adaptador MongoDB con Testcontainers, `@WebFluxTest` del controlador y una E2E nueva,
`NotificationAttachmentE2ETest`. Orden por tarea: prueba → verla fallar por la razón correcta →
implementar → ejecutar la clase → `spotless:apply`.

**Organization**: contrato primero, luego una fase fundacional (puerto de proveedor, modelo y validación
del adjunto, que todas las historias usan) y una fase por historia de usuario del spec en orden de
prioridad (US1, US2, US4 son P1; US3, US5 son P2). La E2E cierra en la fase final porque recorre todas.

## Path Conventions

- `core-main/` = `core/src/main/java/co/edu/uco/notification/core/`
- `core-test/` = `core/src/test/java/co/edu/uco/notification/core/`
- `infra-main/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/`
- `infra-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/`

---

## Phase 1: Setup

**Purpose**: contrato público primero (Principio II).

- [X] T001 Aplicar `contracts/api-notificaciones-cambios.md` a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: campo `attachments` en `SendNotificationRequest`, esquema nuevo `Attachment`, descripción del `400` de `POST /notifications`, frase añadida a la descripción de `POST /notifications:sendBatch`, y descripciones de `ChannelItem.contentSchema` y `RegisterChannelRequest.contentSchema`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: capacidad del proveedor, modelo del adjunto y reglas de validación que usan todas las historias.

- [X] T002 Añadir el método abstracto `boolean supportsAttachments()` a `core-main/port/out/NotificationSenderPort.java` e implementarlo en `infra-main/adapter/out/provider/SimulatedNotificationProvider.java` (`true`), `BrevoNotificationProvider.java`, `TwilioNotificationProvider.java` y `FcmNotificationProvider.java` (`false`), y en los dobles `core-test/port/out/NotificationSenderRegistryTest.java` (`FakeSender`), `core-test/usecase/QueryChannelCatalogServiceTest.java` y `infra-test/adapter/out/provider/ProviderRoutingE2ETest.java` (`RecordingNotificationSender`, `false`); `./mvnw -B -ntp clean compile` y compilación de pruebas en verde antes de seguir (research.md Decisión 6)
- [X] T003 [P] Pruebas de `supportsAttachments()` en `infra-test/adapter/out/provider/SimulatedNotificationProviderTest.java` (`true`), `BrevoNotificationProviderTest.java`, `TwilioNotificationProviderTest.java` y `FcmNotificationProviderTest.java` (`false`) — depende de T002. **Desviación de proceso**: como el orden planeado es T002 → T003, la implementación ya existía al escribir estas pruebas; pasaron a la primera, sin rojo observado
- [X] T004 [P] Pruebas en `core-test/domain/AttachmentTest.java`: normaliza `contentType` (minúsculas, sin parámetros, sin espacios; nulo se conserva nulo), conserva los demás campos tal cual, `of(...)` equivale al constructor, `toString()` no contiene la `url` y sí nombre, tipo y tamaño
- [X] T005 Crear `core-main/domain/valueobject/Attachment.java` (record `fileName`, `contentType`, `Long sizeBytes`, `url`; sin invariantes más allá de la normalización; `toString` redefinido) — depende de T004
- [X] T006 [P] Pruebas en `core-test/domain/NotificationContentTest.java`: `attachments` nulo → lista vacía, copia defensiva e inmutable en orden, `hasAttachments()`, constructor de dos argumentos y `of(subject, body)`/`of(body)` sin adjuntos, `of(subject, body, attachments)`, elemento nulo rechazado, `MAX_LENGTH` sigue midiendo solo `subject` + `body`
- [X] T007 Extender `core-main/domain/valueobject/NotificationContent.java` con `List<Attachment> attachments`, constructor adicional `(subject, body)`, `of(subject, body, attachments)` y `hasAttachments()` — depende de T005, T006
- [X] T008 [P] Pruebas en `core-test/domain/AttachmentPolicyTest.java`: lista vacía válida; 5 adjuntos válidos, 6 rechazados; cada regla de research.md Decisión 3 con su límite inclusivo (`sizeBytes` 1 y 10 485 760 válidos, 0, negativo, nulo y 10 485 761 inválidos; nombre de 255 válido y de 256 inválido; `/`, `\`, control, `.`, `..`, vacío y nulo inválidos; tipo fuera de la lista, nulo y en mayúsculas con parámetros admitido; `url` `http`, relativa, sin servidor, con usuario, de 2049 caracteres, nula y malformada inválidas; `HTTPS://` válida); el mensaje lleva `attachments[i]` y el nombre cuando es válido, y nunca la `url`; se reporta la primera regla del primer adjunto inválido
- [X] T009 Crear `core-main/exception/InvalidAttachmentException.java` (posición + regla) y `core-main/domain/policy/AttachmentPolicy.java` (constantes y `validate(List<Attachment>)`) — depende de T005, T008
- [X] T010 [P] Pruebas en `core-test/domain/ContentSchemaValidatorTest.java`: con adjuntos y esquema nulo, vacío o sin `properties.attachments` → `InvalidContentException` "does not accept attachments"; sin adjuntos y esquema nulo → sin validar (comportamiento actual); esquema que declara adjuntos con `maxItems`, `enum` de tipos y `maximum` de tamaño → acepta dentro de los límites (inclusivo) y rechaza fuera con la ruta `attachments[i]` en el mensaje; una `url` reconocible nunca aparece en el mensaje
- [X] T011 Extender `core-main/domain/policy/ContentSchemaValidator.java` (cerrado por defecto y nodo con `fileName`, `contentType`, `sizeBytes` sin `url`; data-model.md) — depende de T007, T010

**Checkpoint**: `./mvnw -B -ntp -pl core test` en verde; `infrastructure` compila.

---

## Phase 3: User Story 1 - Enviar una notificación con un archivo adjunto (P1) 🎯 MVP

**Goal**: una notificación con adjuntos válidos para su canal se acepta, se guarda con ellos y el proveedor los recibe al despachar, también en reintentos.

**Independent Test**: spec.md, User Story 1, escenarios 1–5.

- [ ] T012 [P] [US1] Pruebas en `core-test/usecase/SendNotificationServiceTest.java`: con una ruta cuyo esquema declara adjuntos, una notificación con adjuntos válidos se guarda con ellos en orden y se publica y encola; duplicada con adjuntos válidos devuelve la original sin guardar
- [ ] T013 [US1] Llamar a `AttachmentPolicy.validate(content.attachments())` antes de `ContentSchemaValidator.validate` en `core-main/usecase/SendNotificationService.java` — depende de T009, T011, T012
- [ ] T014 [P] [US1] Pruebas en `infra-test/adapter/out/mongo/NotificationMongoAdapterTest.java` (Testcontainers): ida y vuelta de una notificación con dos adjuntos conserva orden y los cuatro campos; un documento guardado sin campo `attachments` se lee con lista vacía
- [ ] T015 [US1] Crear `infra-main/adapter/out/mongo/AttachmentDocument.java`, añadir `List<AttachmentDocument> attachments` con copia defensiva a `infra-main/adapter/out/mongo/NotificationDocument.java` y mapear en ambos sentidos en `infra-main/adapter/out/mongo/NotificationDocumentMapper.java` (nulo → vacío) — depende de T007, T014
- [ ] T016 [P] [US1] Pruebas en `infra-test/adapter/in/rest/NotificationControllerTest.java`: `attachments` del request llega al comando como `Attachment` en orden con tipo normalizado; `attachments` ausente o `null` → contenido sin adjuntos
- [ ] T017 [US1] Crear `infra-main/adapter/in/rest/AttachmentRequest.java`, añadir `List<AttachmentRequest> attachments` a `infra-main/adapter/in/rest/SendNotificationRequest.java` y mapear en `toCommand` de `infra-main/adapter/in/rest/NotificationController.java` — depende de T007, T016

**Checkpoint**: aceptación con adjuntos de punta a punta en `core` y adaptadores.

---

## Phase 4: User Story 2 - Rechazar completo lo que el canal no admite (P1)

**Goal**: cualquier adjunto inválido rechaza la notificación completa con `400` y un motivo sin la `url`; en lote, solo el elemento.

**Independent Test**: spec.md, User Story 2, escenarios 1–6; SC-002 y SC-007.

- [ ] T018 [P] [US2] Pruebas en `core-test/usecase/SendNotificationServiceTest.java`: adjunto que incumple `AttachmentPolicy` y adjunto que incumple el esquema del canal → error y ni `save`, ni `publish`, ni `enqueueForDispatch`; duplicada con adjunto inválido → error sin consultar la duplicada (FR-012) — depende de T013
- [ ] T019 [P] [US2] Pruebas en `core-test/usecase/SendNotificationBatchServiceTest.java`: lote con un elemento con adjunto inválido por `AttachmentPolicy` y otro válido → el primero `rejected` con su motivo, el segundo `accepted` (SC-007)
- [ ] T020 [US2] Añadir `InvalidAttachmentException` a los errores que producen `BatchItemResult.rejected` en `core-main/usecase/SendNotificationBatchService.java` — depende de T009, T019
- [ ] T021 [P] [US2] Pruebas en `infra-test/adapter/in/rest/NotificationControllerTest.java`: `InvalidAttachmentException` → `400` con `ErrorResponse` cuyo mensaje contiene `attachments[i]` y no la `url`
- [ ] T022 [US2] Manejar `InvalidAttachmentException` → `400` en `infra-main/adapter/in/rest/NotificationExceptionHandler.java` — depende de T009, T021

---

## Phase 5: User Story 4 - El archivo adjunto nunca queda expuesto (P1)

**Goal**: aceptación y rechazo con adjuntos quedan registrados con nombre, tipo y tamaño; la `url` nunca aparece.

**Independent Test**: spec.md, User Story 4, escenarios 1–3; SC-003 (E2E en T027).

- [ ] T023 [P] [US4] Pruebas en `infra-test/adapter/in/rest/NotificationControllerTest.java` con `ListAppender`: aceptación con adjuntos registra `tenantId`, `externalId`, `notificationId`, `duplicate` y `[fileName|contentType|sizeBytes]`; rechazo registra `tenantId`, `externalId`, metadatos y motivo; ninguna línea contiene la `url`; un nombre con `\r\n` se registra en una sola línea; sin adjuntos no se registra nada nuevo
- [ ] T024 [US4] Crear `infra-main/adapter/in/rest/AttachmentLogFormatter.java` y registrar aceptación (`doOnSuccess`) y rechazo (`doOnError`) solo con adjuntos en `infra-main/adapter/in/rest/NotificationController.java` (research.md Decisión 8) — depende de T017, T023

---

## Phase 6: User Story 3 - Cada canal declara qué adjuntos admite (P2)

**Goal**: las reglas por canal viven en `contentSchema`, se aplican tras el refresco del catálogo y se ven en `GET /channels`; por defecto ningún canal acepta adjuntos.

**Independent Test**: spec.md, User Story 3, escenarios 1–4; SC-005 (E2E en T027).

- [ ] T025 [US3] Comprobar que ningún cambio de código es necesario más allá de T011: `GET /channels` ya expone `contentSchema` tal cual y `application.yml` no declara `attachments` en ningún canal; si la comprobación encuentra algo distinto, registrarlo aquí como desviación. Los escenarios se prueban en T027

---

## Phase 7: User Story 5 - Nunca entregar una notificación sin su adjunto (P2)

**Goal**: con adjuntos y un proveedor sin soporte, `FAILED` sin llamar al proveedor ni reintentar.

**Independent Test**: spec.md, User Story 5, escenarios 1–2; SC-006.

- [ ] T026 [US5] Pruebas en `core-test/usecase/DispatchNotificationServiceTest.java`: notificación con adjuntos y emisor con `supportsAttachments() == false` → `send` nunca invocado, estado `FAILED`, intento `PERMANENT_FAILURE` a nombre del proveedor, guardado y evento `NotificationFailed` publicado; mismo emisor sin adjuntos → `send` invocado (control positivo); emisor con soporte y adjuntos → `send` invocado. Luego implementar la regla en `sendThrough` de `core-main/usecase/DispatchNotificationService.java` (research.md Decisión 6)

---

## Phase 8: E2E, regresión y cierre

- [ ] T027 **E2E explícita (Principio IV)**: crear `infra-test/adapter/in/rest/NotificationAttachmentE2ETest.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers` con MongoDB y RabbitMQ + `WebTestClient`, estructura de `NotificationLiveUpdatesE2ETest`/`ChannelCatalogQueryE2ETest`), con `notification.catalog.refresh-interval-ms=1000` y dos emisores de prueba como beans, `recording-attachments` (`true`) y `recording-plain` (`false`), que guardan lo que reciben. En `@BeforeEach` escribir canales de prueba en `channel_catalog` (uno con adjuntos → `recording-attachments`, uno sin declarar adjuntos → `recording-attachments`, uno con adjuntos → `recording-plain`) y esperar a que `GET /channels` los refleje. Casos: SC-001 (válido → `202` → `DELIVERED`; el emisor recibió los mismos adjuntos en orden; documento en MongoDB con ellos); SC-002 (una solicitud por regla de FR-006 → `400` con `attachments[i]`, ningún documento guardado para esos `externalId`); US3.1 (`GET /channels` muestra la declaración) y US3.4 (EMAIL por defecto rechaza); SC-003 (`url` reconocible ausente de registros capturados con `ListAppender`, cuerpos de error, eventos leídos de una cola temporal enlazada al exchange de eventos, `GET /notifications/{id}` y `GET /notifications`; nombre, tipo y tamaño presentes en los registros como control positivo); SC-005 (bajar `maximum` en el catálogo y afirmar que el rechazo empieza antes de `Duration.ofSeconds(5)`); SC-006 (canal con `recording-plain`: con adjuntos → `FAILED` y el emisor no la recibe; sin adjuntos → `DELIVERED` y sí la recibe); duplicada válida → `duplicate: true`
- [ ] T028 Regresión: `ProviderRoutingE2ETest`, `ChannelCatalogQueryE2ETest`, `ChannelCatalogE2ETest`, E2E de proveedores, `NotificationControllerSearchE2ETest`, `NotificationLiveUpdates*E2ETest`, `HexagonalArchitectureTest` y `ModularityTests` en verde
- [ ] T029 `./mvnw -B -ntp spotless:apply` y `./mvnw -B -ntp verify` completo y en verde (cobertura ≥ 80 % líneas / ≥ 70 % ramas); si solo fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y dejarlo escrito aquí

---

## Dependencies & Execution Order

- T001 antes que cualquier cambio de request o controlador (Principio II).
- Phase 2 bloquea todo: T002 → T003; T004 → T005 → (T006 → T007); T008 → T009; T010 → T011.
- US1 (T012–T017) depende de Phase 2. US2 depende de T013 (servicio) y de T009. US4 depende de T017.
  US3 depende de T011. US5 depende de T002 y T007.
- T027 depende de todas las historias; T028 y T029 al final.

## Parallel Opportunities

- T003, T004, T006, T008 y T010 son pruebas en archivos distintos (tras T002).
- Dentro de US1: T012, T014 y T016 en paralelo; luego sus implementaciones.
- T019 y T021 en paralelo con T018.

## Implementation Strategy

MVP = Phase 1 + Phase 2 + US1: aceptar y despachar adjuntos válidos. Después US2 y US4 (P1), US3 y US5
(P2), y la E2E final que recorre todas. Un commit local por fase o por grupo de tareas coherente.
