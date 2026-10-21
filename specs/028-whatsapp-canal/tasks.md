---

description: "Task list for HU2-112"
---

# Tasks: Enviar notificaciones por el canal WhatsApp

**Input**: Design documents from `/specs/028-whatsapp-canal/`

**Prerequisites**: plan.md (Aprobado 2026-10-21), spec.md, research.md, data-model.md, contracts/twilio-whatsapp-messages-api.md, contracts/api-notificaciones-cambios.md, quickstart.md

**Tests**: Solicitadas por el plan (Principio IV): unitarias de `core` (contenido, validador, servicio, parámetros), del adaptador contra `FakeProviderServer`, de propiedades y de etiquetas de error, y **dos E2E** (`WhatsAppDeliveryE2ETest`, `WhatsAppDisabledProviderE2ETest`) con Testcontainers + `WebTestClient`. Orden por tarea: prueba, verla fallar por la razón correcta, implementar, ejecutar la clase, `spotless:apply`.

**Organization**: Setup (verificación y contrato), Foundational (configuración del proveedor), y una fase por historia de usuario del spec (US1 a US6). Las historias comparten clases de prueba, así que se ejecutan en orden salvo donde se marca `[P]`.

## Path Conventions

- `core/src/main/java/co/edu/uco/notification/core/...` y su espejo en `core/src/test/java/...`
- `utils/` no cambia.
- Abreviaturas: `provider/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/`; `provider-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/`; `config/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/`; `rest/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/`; `mongo/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/`; `core-domain/` = `core/src/main/java/co/edu/uco/notification/core/domain/`.
- Entorno: Docker corriendo y `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` antes de `./mvnw`.

---

## Phase 1: Setup

**Purpose**: confirmar los datos del proveedor que el diseño asumió y dejar el contrato público primero (Principio II).

- [ ] T001 Verificar contra la documentación vigente de Twilio los cuatro puntos marcados **[verificar]** en `specs/028-whatsapp-canal/research.md` y anotar el resultado de cada uno (con fecha y enlace consultado, sin pegar secretos) en una sección "Verificación 2026-10-XX" de ese archivo: (1) nombre, disponibilidad y semántica de la cabecera de idempotencia de la API de mensajes, también para WhatsApp; (2) nombres `ContentSid` y `ContentVariables` y el formato aceptado de las variables; (3) códigos de error de la tabla de la Decisión 5 (63016, 63024, 63015, 63007, 63112, 21211, 20003); (4) tope de `Body` (1600) y de WhatsApp. **Si la cabecera de idempotencia no existe o no aplica a WhatsApp, DETENERSE: no seguir con T002 en adelante, documentar el hallazgo y pedir nueva aprobación al usuario** (FR-011 y SC-008 degradan a riesgo documentado y cambian T034 y T035). Si difiere cualquiera de los otros tres, ajustar research.md, `contracts/twilio-whatsapp-messages-api.md` y las tareas afectadas, y avisar al usuario antes de seguir
- [ ] T002 Actualizar `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` según `contracts/api-notificaciones-cambios.md`, antes de cualquier código: quitar `body` de `required` de `SendNotificationRequest`, añadir el objeto `providerTemplate` (`templateId`, `variables`), y ajustar las descripciones de `channelType`, `recipientAddress`, `subject`, `body` y `attachments`; sin endpoints nuevos. Comprobar que el archivo sigue siendo un OpenAPI válido (la prueba existente que lo carga debe seguir en verde)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: registrar el proveedor nuevo en la configuración; todas las historias lo necesitan.

- [ ] T003 [P] Ajustar primero las pruebas de `core` que fijan tres proveedores gestionados para que esperen cuatro (`core/src/test/java/co/edu/uco/notification/core/domain/configuration/ParameterRegistryTest.java`, `ConfigurationFixtures.java`, `ConfigurationValidatorTest.java` y `core/src/test/java/co/edu/uco/notification/core/port/in/ProviderViewTest.java`), incluidos los descriptores `provider.twilio-whatsapp.timeout-ms` (rango 1000-60000, defecto 10000) y `provider.twilio-whatsapp.connect-timeout-ms` (500-30000, defecto 5000); verlas fallar
- [ ] T004 Añadir `"twilio-whatsapp"` a `MANAGED_PROVIDERS` en `core/src/main/java/co/edu/uco/notification/core/domain/configuration/ParameterRegistry.java`; `./mvnw -B -ntp -pl core test` en verde — depende de T003
- [ ] T005 [P] Prueba `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/TwilioWhatsAppProviderPropertiesTest.java`: `disabledReason()` por cada caso (falta `account-sid`, `auth-token`, `from-number`; `account-sid` fuera de `AC` + 32 hex; `from-number` sin formato internacional), motivos que nombran la propiedad y la variable de entorno (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_WHATSAPP_FROM_NUMBER`) sin valores; todo presente → vacío; defaults de `baseUrl`, `timeoutMs`, `connectTimeoutMs`; `toString()` sin `accountSid` ni `authToken`
- [ ] T006 Crear `config/TwilioWhatsAppProviderProperties.java` (`@ConfigurationProperties("notification.provider.twilio-whatsapp")`, record con defaults `Long`, `disabledReason()`, `toString()` que no muestra credenciales) — depende de T005
- [ ] T007 [P] Añadir la entrada `twilio-whatsapp` / `WHATSAPP` / `body.maxLength` / 1600 (valor y fecha confirmados en T001) a `config/ProviderContentLimits.java` y ajustar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/ProviderContentLimitsTest.java`; añadir un caso a la prueba de `ContentLimitWithinProviderLimitRule` que rechace un límite de canal mayor al del proveedor
- [ ] T008 Registrar el proveedor en `config/ConfigurationConfig.java`: `@EnableConfigurationProperties(TwilioWhatsAppProviderProperties.class)`, habilitación en `enabledProviders` si `disabledReason()` está vacío, inclusión en `configuredProviders`, y valores de `timeoutKey`/`connectTimeoutKey` desde las propiedades; correr `ConfigurationStartupTest` y las pruebas de configuración existentes en verde — depende de T004, T006, T007
- [ ] T009 [P] Añadir a `provider/ProviderLogs.java` una variante de `rejectedByProvider` con campo `reason` (etiqueta no sensible) sin alterar la existente; cubrirla con una caso en `provider-test/ProviderLogSanitizationTest.java`

**Checkpoint**: el contexto arranca con el proveedor registrado y las pruebas de `core` y de configuración en verde.

---

## Phase 3: User Story 1 - Las notificaciones por WhatsApp se entregan de verdad (P1) 🎯 MVP

**Goal**: con el proveedor habilitado y preferente, un texto libre produce exactamente una petición y queda `DELIVERED` con `providerId = twilio-whatsapp`; el catálogo sembrado declara WHATSAPP.

**Independent Test**: spec.md, User Story 1, escenarios 1-4.

- [ ] T010 [US1] Pruebas del camino feliz en `provider-test/TwilioWhatsAppNotificationProviderTest.java` contra `FakeProviderServer`: ruta `/2010-04-01/Accounts/{sid}/Messages.json`, `Authorization: Basic base64(sid:token)`, formulario `To` = `whatsapp:` + destinatario, `From` = `whatsapp:` + origen, `Body`, sin asunto aunque la notificación lo traiga, respuesta `201` → `ACCEPTED`, `providerId()` = `twilio-whatsapp`, `supportsAttachments()` falso, `send(null)` → NPE
- [ ] T011 [US1] Implementar `provider/TwilioWhatsAppNotificationProvider.java` (`@Component`, clientes por `ProviderHttpClients.providerClientSource`, reutiliza `TwilioResponseClassifier` y `TwilioApiResponse`, sin comentarios) solo con el camino de texto libre, la validación del destinatario con `PhoneNumbers.isE164` y el registro de aceptación — depende de T010, T006, T008
- [ ] T012 [US1] Modificar `infrastructure/src/main/resources/application.yml`: canal `WHATSAPP` con `providers: ${NOTIFICATION_WHATSAPP_PROVIDERS:simulated,twilio-whatsapp}` y `content-schema` solo con `body` (`maxLength` 1600) por ahora; bloque `notification.provider.twilio-whatsapp` con `account-sid: ${TWILIO_ACCOUNT_SID:}`, `auth-token: ${TWILIO_AUTH_TOKEN:}`, `from-number: ${TWILIO_WHATSAPP_FROM_NUMBER:}`, `base-url`, `timeout-ms`, `connect-timeout-ms` con las variables de data-model.md; sin valores de credencial ni comentarios
- [ ] T013 [US1] Crear la E2E explícita `provider-test/WhatsAppDeliveryE2ETest.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers` Mongo y RabbitMQ + `WebTestClient` + `FakeProviderServer`, credenciales inventadas; copiar la estructura de `TwilioSmsDeliveryE2ETest`): `twilio-whatsapp` preferente → `DELIVERED`, intento con `providerId = twilio-whatsapp`, 1 petición con `To`/`From`/`Body`; `simulated` preferente → `requests().isEmpty()` y entregado por el simulado; el catálogo sembrado lista WHATSAPP con ambos proveedores

**Checkpoint**: MVP funcional de texto libre.

---

## Phase 4: User Story 2 - Fuera de la ventana de 24 h solo viajan plantillas aprobadas (P1)

**Goal**: la notificación puede llevar un `providerTemplate` en lugar de un cuerpo; el adaptador lo envía como plantilla; un texto libre fuera de ventana termina en fallo permanente con motivo explícito.

**Independent Test**: spec.md, User Story 2, escenarios 1-5.

- [ ] T014 [P] [US2] Pruebas en `core/src/test/java/co/edu/uco/notification/core/domain/valueobject/NotificationContentTest.java` (y `ProviderTemplateReferenceTest.java`): exactamente uno de `body` o `providerTemplate` (ambos o ninguno → precondición); las fábricas actuales conservan su comportamiento; `ProviderTemplateReference` rechaza `templateId` vacío, nombres de variable vacíos y valores nulos, y copia inmutable del mapa
- [ ] T015 [US2] Crear `core-domain/valueobject/ProviderTemplateReference.java` y modificar `core-domain/valueobject/NotificationContent.java` (campo `providerTemplate`, invariante, fábrica para plantilla; sin cambios de firma en las fábricas actuales) — depende de T014
- [ ] T016 [P] [US2] Pruebas en `core/src/test/java/co/edu/uco/notification/core/domain/policy/ContentSchemaValidatorTest.java`: `providerTemplate` con esquema que no declara la propiedad → `InvalidContentException` con `channel does not accept provider templates`; con esquema que la declara → válido; `templateId` vacío, más de 20 variables, valor de más de 1024 caracteres → rechazo; un contenido solo con plantilla no falla por `body` ausente; los casos existentes de adjuntos no cambian
- [ ] T017 [US2] Modificar `core-domain/policy/ContentSchemaValidator.java` (rechazo por esquema que no declara `providerTemplate`; el nodo validado incluye `providerTemplate` y omite `body` si es nulo) — depende de T015, T016
- [ ] T018 [US2] Prueba y ajuste en `core/src/main/java/co/edu/uco/notification/core/usecase/SendNotificationService.java` (con `SendNotificationServiceTest` en `core/src/test/...`): la notificación aceptada conserva el `providerTemplate` del comando; una plantilla en un canal que no la declara se rechaza sin persistir — depende de T017
- [ ] T019 [P] [US2] Pruebas REST en las clases de prueba existentes del controlador de envío y de lote (`infrastructure/src/test/java/.../adapter/in/rest/`): `providerTemplate` llega al comando; cuerpo y plantilla juntos → `400`; ninguno de los dos → `400`; el lote lo trata igual por ítem
- [ ] T020 [US2] Implementar en `rest/`: `ProviderTemplateRequest` (record), campo `providerTemplate` en `SendNotificationRequest`, construcción del contenido en `NotificationController` y `NotificationBatchController`, y la traducción de la precondición a `400` en `NotificationExceptionHandler` si no existe ya — depende de T015, T019
- [ ] T021 [P] [US2] Prueba de ida y vuelta en `infrastructure/src/test/java/.../adapter/out/mongo/` (clase del mapper o del adaptador con Testcontainers): una notificación con `providerTemplate` y `body` nulo se guarda y se reconstruye igual; un documento antiguo sin el campo se lee sin cambios
- [ ] T022 [US2] Añadir `providerTemplate` a `mongo/NotificationDocument.java` y mapearlo en `mongo/NotificationDocumentMapper.java` — depende de T015, T021
- [ ] T023 [P] [US2] Prueba `provider-test/WhatsAppFailureReasonsTest.java`: 63016 → `outside-session-window`, 63024 y 21211 → `invalid-recipient`, 63015 → `recipient-not-in-sandbox`, 63007 y 63112 → `sender-not-configured`, 20003 → `invalid-credentials`, otro o nulo → `unclassified` (tabla ajustada a lo confirmado en T001)
- [ ] T024 [US2] Crear `provider/WhatsAppFailureReasons.java` (final, estática, pura) — depende de T023
- [ ] T025 [US2] Pruebas en `provider-test/TwilioWhatsAppNotificationProviderTest.java`: plantilla → formulario con `ContentSid` y `ContentVariables` (JSON) y **sin** `Body`; `templateId` sin forma `HX` + 32 hex → `PERMANENT_FAILURE`, `FakeProviderServer.requests().isEmpty()` y registro con `reason=invalid-provider-template-format` sin el valor; respuesta `400` con código 63016 → `PERMANENT_FAILURE`, un solo `WARN` con `reason=outside-session-window` y `providerErrorCode`; `400` por plantilla inexistente → `PERMANENT_FAILURE` con el código del proveedor
- [ ] T026 [US2] Completar `provider/TwilioWhatsAppNotificationProvider.java` con el camino de plantilla, la validación del `templateId` y el registro de la etiqueta vía `WhatsAppFailureReasons` y `ProviderLogs` — depende de T009, T024, T025
- [ ] T027 [US2] Completar el `content-schema` del canal WHATSAPP en `application.yml` con la propiedad `providerTemplate` exactamente como en research.md Decisión 9 (comillas simples YAML) y asegurar que la lista de `contracts/` y el documento sembrado de data-model.md coincide
- [ ] T028 [US2] Casos E2E en `provider-test/WhatsAppDeliveryE2ETest.java`: plantilla con variables → `DELIVERED` y una petición con `ContentSid`/`ContentVariables` sin `Body`; cuerpo y plantilla juntos y ninguno de los dos → `400`, nada persistido (`findByTenantAndExternalId` vacío) y `requests().isEmpty()`; plantilla en el canal EMAIL → `400` con `channel does not accept provider templates`; texto libre rechazado por ventana cerrada (el servidor falso responde `400` con 63016) → `FAILED`, exactamente **una** petición (sin reintentos), etiqueta `outside-session-window` en el registro
- [ ] T029 [US2] Prueba E2E de aislamiento por tenant (SC-011) en `provider-test/WhatsAppDeliveryE2ETest.java`: dos tenants distintos, el tenant A envía una plantilla con variables; el tenant B no puede consultar esa notificación (no encontrada), ni la ve en su búsqueda, y los registros capturados del despacho de A llevan el `tenantId` de A y nunca los valores de sus variables; control positivo: A sí la consulta

**Checkpoint**: texto libre y plantilla funcionan de punta a punta; la ventana cerrada es un fallo permanente nombrado.

---

## Phase 5: User Story 3 - Sin credenciales el proveedor se deshabilita con motivo visible (P1)

**Goal**: sin credenciales o con formato inválido, arranque completo, un aviso con el motivo, notificación intacta y trazable, cero llamadas.

**Independent Test**: spec.md, User Story 3, escenarios 1-3.

- [ ] T030 [US3] Pruebas en `provider-test/TwilioWhatsAppNotificationProviderTest.java`: cada motivo (falta `account-sid`, `auth-token`, `from-number`; `account-sid` y `from-number` mal formados) → `ProviderDisabledException` que nombra el dato y **`FakeProviderServer.requests().isEmpty()`**; un único `WARN` al construir, sin valores; con todo presente, ningún `WARN`; `disabledReason()` expuesto por el puerto
- [ ] T031 [US3] Completar la rama deshabilitada de `provider/TwilioWhatsAppNotificationProvider.java` y `config/TwilioWhatsAppProviderProperties.java` hasta pasar T030
- [ ] T032 [US3] Crear la E2E explícita `provider-test/WhatsAppDisabledProviderE2ETest.java` con la configuración por defecto (sin `TWILIO_WHATSAPP_FROM_NUMBER`; `base-url` apuntando a `FakeProviderServer`; copiar la estructura de `TwilioDisabledProviderE2ETest`): el catálogo sembrado declara WHATSAPP con `[simulated, twilio-whatsapp]`; un texto libre y una plantilla se aceptan y se entregan por el simulado (SC-009); con `twilio-whatsapp` preferente → sin intentos, notificación `PENDING`, DLQ con causa que nombra `TWILIO_WHATSAPP_FROM_NUMBER`, y **`FakeProviderServer.requests().isEmpty()`**; con cuenta y token presentes pero sin número de WhatsApp, el proveedor `twilio` de SMS sigue habilitado (independencia de habilitación)

---

## Phase 6: User Story 4 - Reintentos idempotentes y recorrido de intentos (P2)

**Goal**: cada respuesta se traduce a una categoría, los reintentos se disparan solo cuando sirven y una reentrega no duplica el mensaje cuando el proveedor lo permite.

**Independent Test**: spec.md, User Story 4, escenarios 1-6.

- [ ] T033 [US4] Pruebas en `provider-test/TwilioWhatsAppNotificationProviderTest.java`: `400`/`401` → `PERMANENT_FAILURE`; `429`/`500` → `RECOVERABLE_FAILURE`; conexión rechazada → `RECOVERABLE_FAILURE`; tiempo de espera de 500 ms contra retardo de 3 s → `RECOVERABLE_FAILURE` con aserción explícita de `Duration` (menor de 2 s, SC-006); cuerpo de respuesta vacío o ilegible no cambia la categoría en aceptación ni en rechazo
- [ ] T034 [US4] Pruebas de idempotencia en `provider-test/TwilioWhatsAppNotificationProviderTest.java` (según el resultado de T001): la petición lleva la cabecera de idempotencia con `{notificationId}:{ciclo}:{n}`; dos `send` de la misma notificación sin intento nuevo registrado → misma clave; tras registrar un intento → clave distinta; la clave no aparece en ningún registro
- [ ] T035 [US4] Implementar en `provider/TwilioWhatsAppNotificationProvider.java` el cálculo de la clave y el envío de la cabecera, y completar la clasificación y el registro de recuperables hasta pasar T033 y T034 — depende de T033, T034
- [ ] T036 [US4] Casos E2E en `provider-test/WhatsAppDeliveryE2ETest.java`: `500` → `RECOVERABLE` y reintento posterior hasta `DELIVERED` cuando el servidor falso se recupera; el historial muestra los intentos en orden con `providerId = twilio-whatsapp` y categoría, igual que en los demás canales; las cabeceras de idempotencia recibidas por el servidor falso en los dos intentos son distintas; `400` → `FAILED` sin reintentos

---

## Phase 7: User Story 5 - Nada sensible sale del componente (P2)

**Goal**: cero credenciales, cuerpo, variables de plantilla o número completo en registros y mensajes; número enmascarado presente.

**Independent Test**: spec.md, User Story 5, escenarios 1-3.

- [ ] T037 [US5] Pruebas en `provider-test/TwilioWhatsAppNotificationProviderTest.java`: registro de aceptación con `providerMessageId` y `***` + últimos 4; registro de rechazo con `providerErrorCode`, etiqueta y sin el `message` del proveedor (que contiene el número completo); sin token, SID, `base64(sid:token)`, URL, cuerpo, valores de variables ni clave de idempotencia
- [ ] T038 [US5] Caso de no filtrado en `provider-test/WhatsAppDeliveryE2ETest.java`: `ListAppender` en el logger raíz y cola temporal no durable en el exchange de eventos (copiar el mecanismo de `TwilioSmsDeliveryE2ETest`), con un despacho de texto libre y uno de plantilla con variables; ausencia de token, SID, `base64(sid:token)`, cuerpo, valores de variables, número completo y `message` del rechazo en registros y eventos; presencia del número enmascarado y del `providerMessageId` (control positivo); también un despacho que falla
- [ ] T039 [P] [US5] Ampliar `provider-test/ProviderLogSanitizationTest.java` con el registro del adaptador de WhatsApp (texto libre y plantilla) si la prueba recorre los adaptadores por reflexión o lista

---

## Phase 8: User Story 6 - El contenido respeta la forma y el tamaño del canal (P3)

**Goal**: límite de cuerpo en la aceptación, adjuntos rechazados, asunto ignorado, destinatario mal formado sin llamada.

**Independent Test**: spec.md, User Story 6, escenarios 1-4.

- [ ] T040 [US6] Pruebas en `provider-test/TwilioWhatsAppNotificationProviderTest.java`: destinatario sin formato internacional (correo, número sin `+`, nulo) → `PERMANENT_FAILURE`, `FakeProviderServer.requests().isEmpty()` y registro con `reason=invalid-recipient-format` sin el valor; asunto presente → la petición no lo contiene
- [ ] T041 [US6] Casos E2E en `provider-test/WhatsAppDeliveryE2ETest.java`: cuerpo de 1600 caracteres → `202`; de 1601 → `400` con el límite en el mensaje, sin notificación persistida y `requests().isEmpty()` (SC-012); notificación con adjunto → `400` por canal que no acepta adjuntos, sin persistir; destinatario mal formado → `FAILED` y `requests().isEmpty()`; asunto enviado → aceptado y ausente de la petición

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T042 Regresión: correr `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, `ChannelCatalogQueryE2ETest`, `ChannelCatalogSeederTest`, `ConfigurationStartupTest`, `ConfigurationProviderTimeoutsE2ETest`, las pruebas de correo, SMS y push, y las de adjuntos; corregir cualquier prueba que fije la lista de canales o de proveedores gestionados (EMAIL, SMS, PUSH) sin cambiar su intención
- [ ] T043 `./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests,NoHardcodedChannelTest' -Dsurefire.failIfNoSpecifiedTests=false` en verde: `core` sigue sin Spring y sin conceptos de WhatsApp
- [ ] T044 `./mvnw -B -ntp spotless:apply` tras crear o editar `.java`; revisar que el código nuevo no tiene comentarios `//` ni `/*` (Principio III) y que `application.yml` y el YAML del contrato no llevan comentarios nuevos
- [ ] T045 `specs/028-whatsapp-canal/quickstart.md`: ejecutar la validación automatizada de la sección 2 y confirmar que las filas de la tabla coinciden con lo implementado; corregir la guía si difiere. Dejar la prueba de humo manual (sección 3) pendiente de la cuenta de pruebas del usuario, con su tabla de registro sin rellenar
- [ ] T046 `./mvnw -B -ntp clean verify` completo en segundo plano con `timeout` alto (unos 10 minutos); debe cumplir 80 % de líneas y 70 % de ramas y cero hallazgos de SpotBugs/FindSecBugs. Si fallan solo `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, levantar el broker desechable de las instrucciones del repositorio y repetir; si siguen siendo las únicas, repetir excluyéndolas y decirlo explícitamente en el informe (sobre esas decide CI)
- [ ] T047 Cierre: `git status` sin archivos ajenos añadidos (specs 019, 024, 025, 026, `.claude/`, `docs/`, `.archify/` fuera de los commits); commits locales de una línea por grupo lógico, sin trailer, sin push; informar tareas sin marcar, desviaciones y los riesgos con dueño y fecha de la spec

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1 → US2 → US3 → US4 → US5 → US6 → Polish. T001 bloquea todo: si la idempotencia no existe, se detiene el flujo.
- US2 depende de US1 (adaptador y E2E ya creados) y concentra los cambios de `core`, REST y Mongo.
- US3, US4, US5 y US6 amplían las mismas clases de prueba (`TwilioWhatsAppNotificationProviderTest`, `WhatsAppDeliveryE2ETest`), por eso no se paralelizan entre sí.
- Dentro de US2: T014/T015 antes de T016/T017; T017 antes de T018; T015 antes de T020 y T022; T024 antes de T026; T027 antes de T028.
- T012 (`application.yml`) antes de T013 y T032.

### Parallel Opportunities

- Fase 2: T003, T005, T007 y T009 son independientes entre sí (archivos distintos).
- Fase 4: T014, T016, T019, T021 y T023 son pruebas en archivos distintos y pueden escribirse en paralelo antes de sus implementaciones.
- T039 es independiente de T037 y T038.

## Implementation Strategy

MVP = Phases 1-3 (texto libre por Twilio con catálogo sembrado). Luego US2 (plantillas y ventana, el cambio de modelo más grande), US3 (deshabilitación visible), y las P2 y P3 en orden. Entregar en commits locales de una línea por grupo lógico, sin push. Cada fase termina en verde antes de seguir.

## Notes

- Solo el autor marca checkboxes. Toda desviación del plan se anota en la tarea correspondiente y se corrigen las tareas que queden apuntando al diseño anterior.
- Las pruebas de consumidores y adaptadores producen los eventos con el productor real, nunca con JSON armado a mano.
- Una prueba de "no llega nada" solo vale con un control positivo en la misma clase.
