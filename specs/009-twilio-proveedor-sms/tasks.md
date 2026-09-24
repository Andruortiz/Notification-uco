---

description: "Task list for HU2-090"
---

# Tasks: Integrar Twilio como primer proveedor real de SMS

**Input**: Design documents from `/specs/009-twilio-proveedor-sms/`

**Prerequisites**: plan.md (Aceptado), spec.md, research.md, data-model.md, contracts/twilio-messages-api.md, contracts/api-notificaciones-cambios.md, quickstart.md

**Tests**: Solicitadas explícitamente por el plan (Principio IV): unitarias de `PhoneNumbers` y del
clasificador, del adaptador contra `FakeProviderServer`, y dos E2E nuevas con Testcontainers +
`WebTestClient`. Orden por tarea: prueba → verla fallar por la razón correcta → implementar → ejecutar la
clase → `spotless:apply`.

**Organization**: Fase fundacional compartida (contrato, utilidad, clasificador, configuración, calificador
del segundo `WebClient`) y luego una fase por historia de usuario del spec (US1–US5).

## Path Conventions

- `utils/src/main/java/co/edu/uco/notification/utils/...`
- `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/...`
- Pruebas en los árboles `.../src/test/java/...` espejo.
- Abreviatura: `provider/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/`; `provider-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/`; `config/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/`.

---

## Phase 1: Setup

**Purpose**: contrato público primero (Principio II) y renombrado de la ayuda de pruebas.

- [ ] T001 Actualizar solo descripciones de `channelType`, `recipientAddress`, `subject` y `body` en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` (y en el ítem de lote si repite el texto), según `contracts/api-notificaciones-cambios.md`; sin cambios estructurales
- [ ] T002 Renombrar con `git mv` `provider-test/FakeBrevoServer.java` → `provider-test/FakeProviderServer.java` (sin cambios de comportamiento) y actualizar referencias en `provider-test/BrevoNotificationProviderTest.java` y `provider-test/BrevoEmailDeliveryE2ETest.java`; ejecutar `BrevoNotificationProviderTest` en verde (research.md Decisión 10)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: piezas que todas las historias necesitan.

- [ ] T003 [P] Prueba `utils/src/test/java/co/edu/uco/notification/utils/PhoneNumbersTest.java`: `isE164` válido (`+573001234567`, `+12`) e inválido (nulo, vacío, correo, sin `+`, `+0...`, 16 dígitos, letras); `mask` → `***` + últimos 4, y `***` para nulo, vacío o ≤ 4 dígitos
- [ ] T004 [P] Implementar `utils/src/main/java/co/edu/uco/notification/utils/PhoneNumbers.java` (final, estática, Java puro; `^\+[1-9]\d{1,14}$`) — depende de T003
- [ ] T005 [P] Prueba `provider-test/TwilioResponseClassifierTest.java`: todas las filas de research.md Decisión 5 (2xx, 3xx, 400, 401, 403, 404, 408, 429, resto 4xx incl. 402, 5xx, timeout, conexión, otra excepción)
- [ ] T006 [P] Implementar `provider/TwilioResponseClassifier.java` (final, métodos estáticos `classifyStatus(int)`, `classifyError(Throwable)`) — depende de T005
- [ ] T007 Crear `config/TwilioProviderProperties.java` (`@ConfigurationProperties("notification.provider.twilio")`; defaults con `Long`; `disabledReason()` en el orden de research.md Decisión 3, usando `PhoneNumbers.isE164` para `from-number`) — depende de T004
- [ ] T008 Crear `config/TwilioWebClientConfig.java` (`@EnableConfigurationProperties(TwilioProviderProperties.class)`, `@Bean twilioWebClient` con `responseTimeout` y `CONNECT_TIMEOUT_MILLIS`, sin wiretap) — depende de T007
- [ ] T009 Agregar `@Qualifier("brevoWebClient")` al parámetro `WebClient` del constructor de `provider/BrevoNotificationProvider.java` (research.md Decisión 2); ejecutar `BrevoEmailDeliveryE2ETest` en verde con los dos beans `WebClient` presentes — depende de T008
- [ ] T010 [P] Crear `provider/TwilioApiResponse.java` (record `sid`, `code` con `@JsonIgnoreProperties(ignoreUnknown = true)`; sin `message`)

**Checkpoint**: contexto arranca con dos `WebClient`; utilidad y clasificador verdes.

---

## Phase 3: User Story 1 - Las notificaciones por SMS se entregan de verdad (P1) 🎯 MVP

**Goal**: con Twilio habilitado y preferente, un SMS produce exactamente una petición y queda `DELIVERED` con `providerId = twilio`; el catálogo sembrado declara SMS.

**Independent Test**: spec.md, User Story 1, escenarios 1–4.

- [ ] T011 [US1] Pruebas del camino feliz en `provider-test/TwilioNotificationProviderTest.java` contra `FakeProviderServer`: ruta `/2010-04-01/Accounts/{sid}/Messages.json`, `Authorization: Basic base64(sid:token)`, formulario `To`/`From`/`Body` sin asunto aunque la notificación lo traiga, respuesta `201` → `ACCEPTED`, `providerId()` = `twilio`, `send(null)` → NPE
- [ ] T012 [US1] Implementar `provider/TwilioNotificationProvider.java` (`@Component`, `@Qualifier("twilioWebClient")`, pasos del plan § Diseño del adaptador) — depende de T011
- [ ] T013 [US1] Modificar `infrastructure/src/main/resources/application.yml` siguiendo research.md Decisión 13 (`git stash push -- infrastructure/src/main/resources/application.yml` antes; `git stash pop` al final de la historia): canal `SMS` con `${NOTIFICATION_SMS_PROVIDERS:simulated,twilio}` y `content-schema` de data-model.md; bloque `notification.provider.twilio` con credenciales vacías y defaults
- [ ] T014 [US1] E2E explícito `provider-test/TwilioSmsDeliveryE2ETest.java` (Testcontainers Mongo + RabbitMQ + `WebTestClient` + `FakeProviderServer`, credenciales inventadas): `twilio` preferente → `DELIVERED`, `providerId = twilio`, 1 petición con `To`/`From`/`Body`; `simulated` preferente → `requests().isEmpty()`

**Checkpoint**: MVP funcional.

---

## Phase 4: User Story 2 - Sin credenciales el proveedor se deshabilita con motivo (P1)

**Goal**: sin credenciales o con credenciales mal formadas, arranque completo, un `WARN` con el motivo, notificación intacta y en la DLQ, cero llamadas.

**Independent Test**: spec.md, User Story 2, escenarios 1–4.

- [ ] T015 [US2] Pruebas en `provider-test/TwilioNotificationProviderTest.java`: cada motivo (falta `account-sid`, `auth-token`, `from-number`; `account-sid` y `from-number` mal formados) → `ProviderDisabledException` que nombra el dato y **`FakeProviderServer.requests().isEmpty()`**; un único `WARN` al construir, sin valores; con todo presente, ningún `WARN`
- [ ] T016 [US2] Completar `disabledReason()`/rama deshabilitada en `config/TwilioProviderProperties.java` y `provider/TwilioNotificationProvider.java` hasta pasar T015
- [ ] T017 [US2] E2E explícito `provider-test/TwilioDisabledProviderE2ETest.java` con la configuración por defecto (sin credenciales; `base-url` apuntando a `FakeProviderServer`): catálogo sembrado declara `SMS` con `[simulated, twilio]`; un SMS se entrega por el simulado; con `twilio` preferente → sin intentos, `PENDING`, DLQ con causa que nombra `TWILIO_ACCOUNT_SID`, y **`FakeProviderServer.requests().isEmpty()`**

---

## Phase 5: User Story 3 - Los fallos del proveedor se clasifican (P2)

**Goal**: cada familia de respuesta produce la categoría y el estado esperados.

**Independent Test**: spec.md, User Story 3, escenarios 1–6.

- [ ] T018 [US3] Pruebas en `provider-test/TwilioNotificationProviderTest.java`: `400` → `PERMANENT_FAILURE`, `401` → `PERMANENT_FAILURE`, `429`/`500` → `RECOVERABLE_FAILURE`, conexión rechazada → `RECOVERABLE_FAILURE`; tiempo de espera de 500 ms contra retardo de 3 s → `RECOVERABLE_FAILURE` con aserción explícita de `Duration` (< 2 s); cuerpo de respuesta vacío o ilegible no cambia la categoría
- [ ] T019 [US3] Casos `400` → `FAILED` y `500` → `RECOVERABLE` en `provider-test/TwilioSmsDeliveryE2ETest.java`

---

## Phase 6: User Story 4 - El contenido de un SMS respeta forma y tamaño (P2)

**Goal**: límite de 160 en la aceptación, asunto ignorado, destinatario mal formado sin llamada.

**Independent Test**: spec.md, User Story 4, escenarios 1–4.

- [ ] T020 [US4] Prueba en `provider-test/TwilioNotificationProviderTest.java`: destinatario sin formato internacional (correo, número sin `+`) → `PERMANENT_FAILURE`, **`FakeProviderServer.requests().isEmpty()`**, registro con `reason=invalid-recipient-format` y sin el valor; implementar la rama en `provider/TwilioNotificationProvider.java`
- [ ] T021 [US4] Casos en `provider-test/TwilioSmsDeliveryE2ETest.java`: cuerpo de 160 → `202`; cuerpo de 161 → `400` con el límite en el mensaje, sin notificación persistida (`findByTenantAndExternalId` vacío) y `requests().isEmpty()`; destinatario mal formado → `FAILED` y `requests().isEmpty()`; asunto enviado → la petición no lo contiene

---

## Phase 7: User Story 5 - Nada sensible sale del componente (P2)

**Goal**: cero credenciales, cuerpo o número completo en registros y mensajes; número enmascarado presente.

**Independent Test**: spec.md, User Story 5, escenarios 1–4.

- [ ] T022 [US5] Prueba en `provider-test/TwilioNotificationProviderTest.java`: registro de aceptación con `providerMessageId` y `***` + últimos 4; registro de rechazo con `providerErrorCode` y sin el `message` del proveedor (que contiene el número completo); sin token, SID ni `base64(sid:token)`
- [ ] T023 [US5] Caso de no filtrado en `provider-test/TwilioSmsDeliveryE2ETest.java`: `ListAppender` en el logger raíz + cola temporal en el exchange de eventos; ausencia de token, SID, `base64(sid:token)`, cuerpo, número completo y `message` del rechazo; presencia del número enmascarado (control positivo)

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T024 Regresión: `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, `ChannelCatalogSeederTest`, `BrevoNotificationProviderTest`, `BrevoEmailDeliveryE2ETest`, `BrevoDisabledProviderE2ETest`
- [ ] T025 `HexagonalArchitectureTest` y `ModularityTests` en verde
- [ ] T026 `./mvnw -B -ntp spotless:apply` y revisión de cero comentarios explicativos en el código nuevo (Principio III)
- [ ] T027 `./mvnw -B -ntp verify` completo en verde (cobertura ≥80 % líneas / ≥70 % ramas en `utils` e `infrastructure`); si solo fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y reportarlo
- [ ] T028 `git stash pop` del cambio ajeno de `application.yml` (research.md Decisión 13); si hay conflicto, no resolverlo y reportar el comando

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1 → (US2, US3, US4, US5 en ese orden; comparten `TwilioNotificationProviderTest` y `TwilioSmsDeliveryE2ETest`, así que no se paralelizan entre sí) → Polish.
- T009 bloquea todo `@SpringBootTest`: sin el calificador el contexto no arranca.
- T013 (`application.yml`) antes de T014 y T017.

### Parallel Opportunities

- T003/T004, T005/T006 y T010 son independientes entre sí (archivos distintos).

## Implementation Strategy

MVP = Phases 1–3 (entrega por Twilio con catálogo sembrado). Luego US2 (seguridad operativa, P1), y las
P2 en orden. Commits locales de una línea por grupo lógico, sin push.

## Notes

- Solo el autor marca checkboxes. Toda desviación se anota en la tarea correspondiente.
