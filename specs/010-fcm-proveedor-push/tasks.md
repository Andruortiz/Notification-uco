---

description: "Task list for HU2-091"
---

# Tasks: Integrar Firebase Cloud Messaging como primer proveedor real de PUSH

**Input**: Design documents from `/specs/010-fcm-proveedor-push/`

**Prerequisites**: plan.md (Aceptado), spec.md (Q1–Q4 confirmadas), research.md, data-model.md, contracts/fcm-http-v1-api.md, contracts/api-notificaciones-cambios.md, quickstart.md. Rama rebasada sobre HU2-090 (research.md Decisión 0).

**Tests**: Solicitadas explícitamente por el plan (Principio IV): unitarias del clasificador y de la carga de
credenciales, del obtenedor de autorización y del adaptador contra dos `FakeProviderServer` (autorización y
envío), y dos E2E nuevas con Testcontainers + `WebTestClient`. Orden por tarea: prueba → verla fallar por la
razón correcta → implementar → ejecutar la clase → `spotless:apply`.

**Organization**: Fase fundacional compartida (contrato, clasificador, credenciales, configuración,
obtenedor de autorización) y luego una fase por historia de usuario del spec (US1–US5).

## Path Conventions

- Abreviaturas: `provider/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/`; `provider-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/`; `config/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/`; `config-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/`.
- **Regla transversal de literales de prueba** (research.md Decisión 11): ningún literal contiguo con forma de clave PEM, JSON de cuenta de servicio, correo de cuenta de servicio, token de acceso o identificador de dispositivo real. El par RSA se genera en tiempo de ejecución; los fragmentos reconocibles se arman por concatenación.

---

## Phase 1: Setup

**Purpose**: contrato público primero (Principio II).

- [x] T001 Actualizar solo descripciones de `channelType`, `recipientAddress`, `subject` y `body` en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` según `contracts/api-notificaciones-cambios.md` (sobre el texto vigente de HU2-090); sin cambios estructurales

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: piezas que todas las historias necesitan.

- [x] T002 [P] Prueba `provider-test/FcmResponseClassifierTest.java`: todas las filas de research.md Decisión 5 (2xx, 3xx, 400, 401, 403, 404, 408, 429, resto 4xx, 500, 503, resto 5xx, timeout, conexión, otra excepción) — rojo por aserción observado con un esqueleto que devolvía `ACCEPTED` siempre (27/31 fallos); 31/31 verde tras implementar
- [x] T003 [P] Implementar `provider/FcmResponseClassifier.java` (final, métodos estáticos `classifyStatus(int)`, `classifyError(Throwable)`) — depende de T002
- [x] T004 [P] Crear la ayuda de pruebas `provider-test/FcmTestCredentials.java`: genera un par RSA 2048 en tiempo de ejecución, expone la clave pública y arma con `ObjectMapper` el JSON de cuenta de servicio (`type`, `project_id`, `client_email`, `private_key` en PEM PKCS#8) con claves y valores reconocibles construidos por concatenación; revisar el diff antes de commitear (plan § Orden, paso 3) — diff revisado: sin PEM contiguo (`"-----BEGIN " + PEM_LABEL + "-----"` con `PEM_LABEL = "PRIVATE" + " KEY"`), correo y tipo de cuenta concatenados; el par se genera una vez por JVM (`shared()`)
- [x] T005 Prueba `config-test/FcmCredentialsTest.java`: cada fila de motivos de research.md Decisión 3 (ambas presentes, ninguna, archivo inexistente, no JSON, `type` distinto, falta `project_id`/`client_email`/`private_key`, clave ilegible) → motivo que nombra la propiedad o el campo; carga válida por contenido y por archivo temporal; ningún motivo ni `toString()` (de propiedades, credenciales y cuenta) contiene fragmentos de la credencial; `FcmServiceAccount.sign` verifica con la clave pública — depende de T004 — rojo solo de compilación (clases ausentes); 23/23 verde. **Añadido**: directorio como archivo, clave base64 válida pero no PKCS#8, y motivo que nombra `FCM_CREDENTIALS_FILE` cuando el contenido malformado viene del archivo
- [x] T006 Implementar `config/FcmProviderProperties.java` (`@ConfigurationProperties("notification.provider.fcm")`; defaults con `Long`; `toString()` sin credenciales), `config/FcmServiceAccount.java` (clase final; `projectId()`, `clientEmail()`, `sign(byte[])`; la `PrivateKey` no sale; `toString()` sin datos sensibles) y `config/FcmCredentials.java` (`load(properties)` → cuenta o motivo; texto fijo por motivo, nunca `getMessage()`; archivo con `FileSystemResource`) hasta pasar T005. Si SpotBugs/FindSecBugs señala `PATH_TRAVERSAL_IN` en el `verify` (T026), detenerse y reportar antes de cualquier exclusión (research.md Decisión 3). **Desviaciones**: (a) el motivo "malformed" nombra además la fuente (`... in notification.provider.fcm.credentials-json (FCM_CREDENTIALS_JSON): ...`) para distinguir variable de archivo; (b) `FcmServiceAccount.fromPem` quita las fronteras PEM con una expresión (`-----[A-Z0-9 ]+-----`) en lugar de literales, para que tampoco el código de producción contenga una cabecera de clave privada; (c) SpotBugs se comprueba en el `verify` de T026, no en este paso (solo se usan los comandos autorizados)
- [x] T007 Crear `config/FcmProviderConfig.java` (`@EnableConfigurationProperties(FcmProviderProperties.class)`, `@Bean fcmWebClient` con `responseTimeout` y `CONNECT_TIMEOUT_MILLIS`, sin wiretap; `@Bean fcmCredentials`) — depende de T006; ejecutar `BrevoEmailDeliveryE2ETest` y `TwilioSmsDeliveryE2ETest` en verde con tres beans `WebClient` (research.md Decisión 8) — 6/6 y 8/8 verdes con tres `WebClient`
- [x] T008 [P] Crear los records `provider/FcmSendRequest.java` (`message{token, notification{title, body}}`, `@JsonInclude(NON_NULL)`), `provider/FcmSendResponse.java` (`name`) y `provider/FcmErrorResponse.java` (`error{status, details[errorCode]}`, sin `message`, `List.copyOf` en constructor compacto y accesor), todos con `@JsonIgnoreProperties(ignoreUnknown = true)` donde leen — **Desviación menor**: el constructor compacto de `FcmErrorResponse.ErrorBody` copia `details` con `stream().filter(Objects::nonNull).toList()` (inmutable y sin nulos) y el accesor con `List.copyOf`; el record anidado se llama `ErrorBody` para no ocultar `java.lang.Error`; `FcmSendResponse.messageId()` devuelve el último segmento de `name`
- [x] T009 Prueba `provider-test/FcmAccessTokenProviderTest.java` contra un `FakeProviderServer` de autorización: formulario `grant_type`/`assertion`; aserción con cabecera RS256, `iss`, `scope`, `aud = token-url`, `exp = iat + 3600` y firma que verifica con la clave pública; diez obtenciones con `expires_in` 3600 → una petición; `expires_in` dentro del margen → nueva petición en cada obtención; obtenciones concurrentes → una petición; canje fallido no memorizado; `invalidate()` fuerza un canje nuevo; canje con retardo 3 s contra espera 500 ms → error en < 2 s (`Duration` explícita) — rojo solo de compilación; 10/10 verde. **Añadido**: invalidar un token antiguo no descarta el vigente; `200` sin token o ilegible → error que no es `AuthorizationRejectedException` (termina recuperable)
- [x] T010 Implementar `provider/FcmAccessTokenProvider.java` (research.md Decisión 4) hasta pasar T009 — depende de T006, T008, T009 — caché con `Mono.defer(...).cacheInvalidateIf(...)`: comparte el canje en vuelo y no memoriza errores. **No listado en el plan**: `TokenResponse` (record con `toString()` sin el token) y `AuthorizationRejectedException` (solo lleva el código HTTP) viven anidados en `FcmAccessTokenProvider`

**Checkpoint**: contexto arranca con tres `WebClient`; clasificador, credenciales y autorización verdes.

---

## Phase 3: User Story 1 - Las notificaciones push se entregan de verdad (P1) 🎯 MVP

**Goal**: con `fcm` habilitado y preferente, un push produce exactamente una petición y queda `DELIVERED` con `providerId = fcm`; el catálogo sembrado declara PUSH; la autorización se reutiliza.

**Independent Test**: spec.md, User Story 1, escenarios 1–4.

- [x] T011 [US1] (Nota común a T011, T015, T017, T019 y T021: las pruebas de las cinco historias viven en la misma clase y se escribieron juntas antes del adaptador; el rojo observado fue de compilación, adaptador ausente; 43/43 verde tras implementarlo) Pruebas del camino feliz en `provider-test/FcmNotificationProviderTest.java` contra dos `FakeProviderServer` (`authServer`, `fcmServer`): ruta `/v1/projects/{project_id}/messages:send`, `Authorization: Bearer <token del canje>`, cuerpo con `message.token` idéntico a la dirección y `notification.title`/`body`, sin `data`; `200` → `ACCEPTED`; dos envíos → una petición de canje; `providerId()` = `fcm`; `send(null)` → NPE
- [x] T012 [US1] Implementar `provider/FcmNotificationProvider.java` (`@Component`, `@Qualifier("fcmWebClient")`, construye `FcmAccessTokenProvider`; pasos del plan § Diseño del adaptador) — depende de T003, T007, T010, T011 — **Desviación**: el constructor recibe también `FcmProviderProperties` (para `token-url`) además de `FcmCredentials`; el enmascarado es un método privado (`***` + últimos 4 caracteres; `***` con 4 o menos)
- [x] T013 [US1] Modificar `infrastructure/src/main/resources/application.yml` siguiendo research.md Decisión 15 (`git stash push -- infrastructure/src/main/resources/application.yml` antes; `git stash pop` en T027): canal `PUSH` con `${NOTIFICATION_PUSH_PROVIDERS:simulated,fcm}` y `content-schema` de data-model.md; bloque `notification.provider.fcm` con las dos credenciales vacías y defaults — `git stash push` hecho antes de editar; el commit solo lleva lo propio
- [x] T014 [US1] E2E explícito `provider-test/FcmPushDeliveryE2ETest.java` (Testcontainers Mongo + RabbitMQ + `WebTestClient` + `authServer` y `fcmServer`, credencial de `FcmTestCredentials`): `fcm` preferente → `DELIVERED`, `providerId = fcm`, 1 petición de envío con el identificador y el contenido; `simulated` preferente → `fcmServer.requests().isEmpty()`. El preferente se cambia reescribiendo el documento PUSH sembrado con **la misma** forma de contenido — 10/10 verde con Testcontainers reales (incluye T018, T020 y T022). **Desviación**: el servidor de autorización **no** se reinicia entre pruebas (el token queda en caché en el contexto de Spring), para que la prueba de no filtrado conozca la aserción firmada realmente enviada

**Checkpoint**: MVP funcional.

---

## Phase 4: User Story 2 - Sin credenciales el proveedor se deshabilita con motivo (P1)

**Goal**: sin credencial, ambigua o ilegible: arranque completo, un `WARN` con el motivo, notificación intacta y en la DLQ, cero llamadas.

**Independent Test**: spec.md, User Story 2, escenarios 1–4.

- [x] T015 [US2] Pruebas en `provider-test/FcmNotificationProviderTest.java`: con credenciales deshabilitadas (sin credencial, ambas, ilegible) → `ProviderDisabledException` que nombra el motivo y **`authServer.requests().isEmpty()` y `fcmServer.requests().isEmpty()`**; un único `WARN` al construir, sin fragmentos de la credencial; con credencial válida, ningún `WARN`; implementar la rama en `provider/FcmNotificationProvider.java` — además: `client_email` ausente, clave ilegible y archivo inexistente
- [x] T016 [US2] E2E explícito `provider-test/FcmDisabledProviderE2ETest.java` con la configuración por defecto (sin credenciales; `base-url` y `token-url` apuntando a `FakeProviderServer`): catálogo sembrado declara `PUSH` con `[simulated, fcm]`; un push se entrega por el simulado; con `fcm` preferente → sin intentos, `PENDING`, DLQ con causa que nombra `FCM_CREDENTIALS_JSON`, y **`requests().isEmpty()` en los dos servidores** — 2/2 verde

---

## Phase 5: User Story 3 - Los fallos del proveedor se clasifican (P2)

**Goal**: cada familia de respuesta, del envío y del canje, produce la categoría y el estado esperados.

**Independent Test**: spec.md, User Story 3, escenarios 1–6.

- [x] T017 [US3] Pruebas en `provider-test/FcmNotificationProviderTest.java`: envío `404 UNREGISTERED`, `400 INVALID_ARGUMENT`, `403 SENDER_ID_MISMATCH`, `401` → `PERMANENT_FAILURE`; `429`, `500`, `503`, conexión rechazada → `RECOVERABLE_FAILURE`; `401` invalida la autorización (el siguiente envío hace un canje nuevo); canje `400`/`401` → `PERMANENT_FAILURE` y canje `503` → `RECOVERABLE_FAILURE`, ambos con `fcmServer.requests().isEmpty()`; envío con retardo 3 s contra espera 500 ms → `RECOVERABLE_FAILURE` en < 2 s (`Duration` explícita); cuerpo de respuesta vacío o ilegible no cambia la categoría — además: canje `429` → recuperable; servicio de autorización inalcanzable → recuperable con `stage=authorization` y `errorType=`; rechazo `404` con cuerpo sin `details` → sigue permanente y registra el `status`
- [x] T018 [US3] Casos en `provider-test/FcmPushDeliveryE2ETest.java`: `404 UNREGISTERED` → `FAILED` con una sola petición de envío y sin reintentos; `400` → `FAILED`; `503` → `RECOVERABLE` — el caso `UNREGISTERED` espera 2 s tras `FAILED` y comprueba que sigue habiendo una sola petición y que el estado no cambió

---

## Phase 6: User Story 4 - Destinatario y contenido respetan la forma del canal (P2)

**Goal**: identificador opaco enviado tal cual; límites 100/900 en la aceptación; título omitido sin asunto.

**Independent Test**: spec.md, User Story 4, escenarios 1–6.

- [x] T019 [US4] Pruebas en `provider-test/FcmNotificationProviderTest.java`: sin asunto → cuerpo sin `title`; dirección con forma de correo o de teléfono → se envía idéntica en `message.token` (sin comprobación de forma, Q1); identificador de 180 caracteres → idéntico — además: dirección con espacios y `ñ`, idéntica
- [x] T020 [US4] Casos en `provider-test/FcmPushDeliveryE2ETest.java`: asunto 100 + cuerpo 900 → `202`; asunto 101 o cuerpo 901 → `400` con el límite en el mensaje, sin notificación persistida y `fcmServer.requests().isEmpty()`; sin asunto → petición sin `title`; dirección con forma de correo y el servidor respondiendo `400` → `FAILED` tras exactamente una petición cuyo `token` es la dirección aceptada

---

## Phase 7: User Story 5 - Nada sensible sale del componente (P2)

**Goal**: cero credencial, aserción, token de acceso, contenido o identificador completo en registros y mensajes; identificador enmascarado presente.

**Independent Test**: spec.md, User Story 5, escenarios 1–5.

- [x] T021 [US5] Prueba en `provider-test/FcmNotificationProviderTest.java`: registro de aceptación con `providerMessageId` y `***` + últimos 4; registro de rechazo con `providerErrorCode` y sin el `message` del proveedor; registro de canje fallido con `stage=authorization` y sin el cuerpo de la respuesta; en ningún registro la clave (fragmento base64 del PEM), `client_email`, la aserción, el token de acceso, el título, el cuerpo ni el identificador completo
- [x] T022 [US5] Caso de no filtrado en `provider-test/FcmPushDeliveryE2ETest.java`: `ListAppender` en el logger raíz + cola temporal no durable y sin autoborrado en el exchange de eventos (lección de HU2-090); ausencia de los mismos datos que T021 y del `message` de un rechazo; presencia del identificador enmascarado (control positivo)

---

## Phase 8: Polish & Cross-Cutting Concerns

- [x] T023 Regresión: `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, `ChannelCatalogSeederTest`, pruebas de Brevo y Twilio (unitarias y E2E) — verde dentro del `verify` de T026: `ProviderRoutingE2ETest` 3/3, `ChannelCatalogE2ETest` 3/3, `ChannelCatalogSeederTest` 4/4, Brevo (18 + 10 + 6 + 2), Twilio (31 + 32 + 8 + 2)
- [x] T024 `HexagonalArchitectureTest`, `ModularityTests` y `NoHardcodedChannelTest` en verde — 3/3, 2/2 y 1/1
- [x] T025 `./mvnw -B -ntp spotless:apply` tras cada archivo nuevo; cero comentarios `//` o `/*` en el código nuevo (Principio III); revisión final del diff de pruebas contra la regla transversal de literales — búsqueda de `//` y `/*` en todos los archivos `Fcm*`: cero coincidencias
- [x] T026 `./mvnw -B -ntp clean verify` completo y en verde (cobertura ≥80 % líneas / ≥70 % ramas, SpotBugs/FindSecBugs, Spotless). Si solo fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo explícitamente — **BUILD SUCCESS sin exclusiones**, 594 pruebas (utils 11, core 266, infrastructure 317), 0 fallos; SpotBugs/FindSecBugs 0 hallazgos (no apareció `PATH_TRAVERSAL_IN`, no hizo falta ninguna exclusión); cobertura: infrastructure 98.0 % líneas / 86.3 % ramas, core 100 % / 93.1 %, utils 100 % / 100 %. **Nota de entorno** (igual que HU2-090): `localhost:5673` lo ocupa el RabbitMQ de docker compose con credenciales propias, así que el `verify` corrió contra un broker desechable `guest/guest` en `localhost:5674` (`RABBITMQ_PORT=5674 RABBITMQ_USERNAME=guest RABBITMQ_PASSWORD=guest`), eliminado al terminar; `DeadLetterQueueE2ETest` 1/1 y `RabbitRetryConfigCustomAttemptsTest` 1/1 en verde
- [x] T027 `git stash pop` del cambio ajeno de `application.yml` (research.md Decisión 15); conflicto → se reporta sin resolver — aplicado sin conflicto; el cambio ajeno (`spring.application.name`) queda sin commitear, igual que antes de la historia
- [ ] T028 Prueba manual de humo con un proyecto real según `quickstart.md § 3`, fuera de CI, y registro del resultado en su plantilla — la ejecuta el usuario con sus credenciales

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1 → (US2, US3, US4, US5 en ese orden; comparten `FcmNotificationProviderTest` y `FcmPushDeliveryE2ETest`, así que no se paralelizan entre sí) → Polish.
- T007 bloquea todo `@SpringBootTest` nuevo: sin `fcmWebClient` calificado el contexto no arranca.
- T013 (`application.yml`) antes de T014 y T016.
- T028 no bloquea el cierre de la implementación automatizada; queda a cargo del usuario.

### Parallel Opportunities

- T002/T003, T004 y T008 son independientes entre sí (archivos distintos).

## Implementation Strategy

MVP = Phases 1–3 (entrega por FCM con catálogo sembrado). Luego US2 (seguridad operativa, P1) y las P2 en
orden. Commits locales de una línea por grupo lógico, sin push.

## Notes

- Solo el autor marca checkboxes. Toda desviación se anota en la tarea correspondiente.
