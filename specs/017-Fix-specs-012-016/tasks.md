---

description: "Task list for HU2-017 corrección de los hallazgos de la revisión de las specs 011 a 016"
---

# Tasks: Corregir los hallazgos de la revisión de las specs 011 a 016

**Input**: Design documents from `/specs/017-Fix-specs-012-016/`

**Prerequisites**: plan.md (Aceptado, v1, 2026-10-05), spec.md, research.md, data-model.md,
contracts/api-notificaciones-cambios.md, quickstart.md.

**Tests**: exigidas (FR-018 y Principio IV). Orden por tarea: prueba que falla por la razón correcta ->
implementar -> ejecutar la clase -> `./mvnw -B -ntp spotless:apply`. Toda prueba que verifique ausencia de
algo lleva un control positivo (el mismo escenario con el dato presente). Cada entrega cierra con una tarea
E2E explícita (Principio IV) y con las pruebas de arquitectura en verde.

**Organization**: una fase por entrega del plan (E1 a E6). Las historias de usuario de la spec se
mapean así: US1 (arranque y autenticación seguros) = E1 y E2; US2 (nada se pierde sin rastro) = E3;
US3 (el despacho no duplica ni pierde) = E4; US5 (reglas del proyecto) = E5; US4 (artefactos
coherentes) = E6. Se recomienda una rama y un PR por entrega, cada una desde `develop` actualizado.

## Path Conventions

Las rutas son relativas a la raíz del repositorio y están escritas completas. Los paquetes Java viven
bajo `core/src/main/java/co/edu/uco/notification/core/`,
`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/` y sus equivalentes `src/test/java`.
No se usan abreviaturas de rutas (hallazgo B-14).

**Reglas de código**: cero comentarios explicativos en código nuevo o tocado (Principio III);
`./mvnw -B -ntp spotless:apply` tras crear o modificar archivos `.java`.

---

## Phase 1: Setup

- [ ] T001 Confirmar la línea base en una rama nueva desde `develop` actualizado: `./mvnw -B -ntp spotless:check` y `./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false` en verde, y anotar el resultado en `specs/017-Fix-specs-012-016/tasks.md`.
- [ ] T002 Registrar en `specs/017-Fix-specs-012-016/tasks.md` la rama de cada entrega antes de empezar (`fix/017-e1-auth-segura`, `fix/017-e2-ticket-panel`, `fix/017-e3-lote-adjuntos`, `fix/017-e4-despacho`, `fix/017-e5-errores-comentarios`, `fix/017-e6-artefactos`) y comprobar que `.specify/feature.json` apunta a `specs/017-Fix-specs-012-016`.

---

## Phase 2: E1 - Arranque y autenticación seguros (US1, P1)

**Goal**: El servicio no arranca con un secreto ausente, corto o público; rechaza tokens sin caducidad; el filtro no confunde errores posteriores con rechazos de credencial; las exenciones son por segmento.

**Independent Test**: Arrancar sin secreto y con el secreto de desarrollo fuera del perfil `local` y comprobar que no inicia; presentar un token sin `exp` y recibir 401; `/actuatorX` exige credencial.

### Tests for E1 (escribir primero y verlos fallar)

- [ ] T003 [P] [US1] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/AuthJwtPropertiesTest.java`: falla el arranque con secreto vacío, de menos de 32 bytes o igual al valor de desarrollo conocido; falla con `ttl-minutes` 0 y 1441; el valor de desarrollo se acepta solo con el perfil `local`; control positivo con un secreto válido y `ttl-minutes` 720 (usar `ApplicationContextRunner`).
- [ ] T004 [P] [US1] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/security/local/LocalJwtTokenValidationAdapterTest.java`: un token firmado sin `exp` lanza `InvalidTokenException`; un token con `alg=none` y uno con `nbf` futuro se rechazan; control positivo con un token que trae `exp` vigente.
- [ ] T005 [P] [US1] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/security/platform/PlatformJwtTokenValidationAdapterTest.java`: un token sin `exp` se rechaza; control positivo con `exp` vigente.
- [ ] T006 [P] [US1] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationWebFilterTest.java`: (a) un error lanzado por `chain.filter` después de validar el token se propaga y no se responde 401 ni se registra como rechazo de credencial (control positivo: un token inválido sí da 401); (b) `/actuatorX`, `/openapiX` y `/swagger-uiX` exigen credencial, mientras `/actuator/health`, `/openapi/api-notificaciones.yaml` y `/v3/api-docs` siguen exentos; (c) un token con tenant inválido se registra con un motivo propio y no como `UNKNOWN_ROLE`, y el log lleva el `tenantId` cuando el token lo permite.
- [ ] T007 [P] [US1] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/NoPublishedSecretTest.java`: recorre `infrastructure/src/main/resources` y falla si el secreto de desarrollo aparece en cualquier archivo distinto de `application-local.yml`; control positivo con un archivo temporal que sí lo contiene.
- [ ] T008 [US1] Crear el E2E `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationSecurityE2ETest.java` (`@SpringBootTest` con Testcontainers de Mongo y RabbitMQ y secreto de prueba explícito): token sin `exp` -> 401; `/actuatorX` -> 401 y `/actuator/health` -> 200; una ruta de prueba declarada en una `@TestConfiguration` que lanza una excepción, llamada con un token `ADMINISTRADOR`, -> 500 y no 401, sin que el log la marque como rechazo de credencial; control positivo con un token válido que recibe 202 en `POST /notifications`.
### Implementation for E1

- [ ] T009 [US1] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/AuthJwtSecretGuard.java` y registrarlo en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/SecurityConfig.java`: valida al arrancar que el secreto exista, tenga al menos 32 bytes y no sea el de desarrollo salvo con el perfil `local`, con mensajes que nombren la variable `AUTH_JWT_HS256_SECRET`; validar `ttl-minutes` entre 1 y 1440 en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/AuthJwtProperties.java`.
- [ ] T010 [US1] Quitar el valor por defecto en `infrastructure/src/main/resources/application.yml` (`hs256-secret: ${AUTH_JWT_HS256_SECRET:}`) y crear `infrastructure/src/main/resources/application-local.yml` con el secreto de desarrollo bajo el perfil `local`.
- [ ] T011 [US1] Exigir la claim `exp` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/security/local/LocalJwtTokenValidationAdapter.java` (`parseClaims`) y en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/security/platform/PlatformJwtTokenValidationAdapter.java` (`buildParser`), rechazando con `InvalidTokenException` un token sin ella.
- [ ] T012 [US1] Reestructurar `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationWebFilter.java` para que el manejo de errores envuelva solo la validación del token y no la continuación de la cadena; reemplazar `classify` por un motivo explícito que lanzan los adaptadores y extraer el `tenantId` también de los tokens con tenant inválido.
- [ ] T013 [US1] Cambiar `isExempt` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationWebFilter.java` para que una ruta esté exenta solo si es igual a la exención o empieza por la exención más `/`.
- [ ] T014 [US1] Añadir `token.mjs` a `.gitignore` y reescribirlo para que exija `AUTH_JWT_HS256_SECRET` por entorno, sin valor por defecto y sin comentarios; reemplazar el secreto por un marcador en `.env.example` y actualizar la sección de autenticación de `README.md`.
- [ ] T015 [US1] Buscar con `grep -rn "notification-uco-dev-only-secret" infrastructure/src/test core/src/test` las pruebas que dependían del secreto por defecto y declararlo de forma explícita en su configuración de prueba; ejecutar la suite de autenticación completa.
- [ ] T016 [US1] Ejecutar `./mvnw -B -ntp spotless:apply`, las clases nuevas y ampliadas de E1, `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationInterinaE2ETest.java`, `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/AuthModeSelectionTest.java` y las pruebas de arquitectura; marcar E1 como cerrada y anotar los resultados.

---

## Phase 3: E2 - Ticket de un solo uso para el panel en vivo (US1, P1)

**Goal**: El panel en vivo se autentica con un ticket de 30 s de un solo uso; la credencial principal no viaja en la dirección de la conexión.

**Independent Test**: Pedir un ticket, abrir el flujo con él (200) y reutilizarlo (401); con un ticket vencido o `?access_token=` recibir 401.

### Contract first (Principio II)

- [ ] T017 [US1] Editar `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: añadir `POST /notifications:subscribeTicket` (`issueSubscriptionTicket`, rol `CLIENTE`, 200 `SubscriptionTicketResponse` con `ticket` y `expiresInSeconds`, 401), retirar el parámetro `access_token` de `GET /notifications:subscribe`, añadir `ticket` y ajustar la descripción y el 401 como indica `specs/017-Fix-specs-012-016/contracts/api-notificaciones-cambios.md` § 2.
### Tests for E2 (escribir primero y verlos fallar)

- [ ] T018 [P] [US1] Crear `core/src/test/java/co/edu/uco/notification/core/usecase/IssueSubscriptionTicketServiceTest.java`: emite un ticket opaco de 43 caracteres base64url con vigencia de 30 s según el `Clock`; guarda solo la huella SHA-256 y no el ticket en claro; dos emisiones dan tickets distintos; control positivo: el ticket en claro consumido coincide con el emitido.
- [ ] T019 [P] [US1] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/SubscriptionTicketMongoAdapterTest.java` (Testcontainers): el ticket se consume una vez y el segundo consumo devuelve vacío; un ticket vencido devuelve vacío aunque el TTL de Mongo no haya corrido; dos consumos concurrentes del mismo ticket producen un solo ganador; el documento guardado no contiene el ticket en claro; existe el índice TTL sobre `expiresAt`.
- [ ] T020 [P] [US1] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/RouteAuthorizationPolicyTest.java`: `POST /notifications:subscribeTicket` exige `CLIENTE`.
- [ ] T021 [P] [US1] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationWebFilterTest.java`: `GET /notifications:subscribe?ticket=` resuelve el principal sin pasar por `TokenValidationPort`; `?access_token=` ya no autentica y da 401; la cabecera `Authorization` tiene prioridad sobre `ticket`; un ticket ausente, desconocido o usado da 401; control positivo con un ticket válido.
- [ ] T022 [US1] Crear el E2E `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/SubscriptionTicketE2ETest.java`: un ticket válido abre el flujo y recibe la foto inicial; el mismo ticket por segunda vez da 401; un ticket vencido (con TTL de prueba corto) da 401; `?access_token=<jwt>` da 401; los logs de la prueba no contienen el ticket en claro (control positivo: el ticket sí se obtiene en la respuesta del POST).
- [ ] T023 [US1] Actualizar el escenario SSE de `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationInterinaE2ETest.java` (hoy usa `?access_token=`) para que use el ticket.
### Implementation for E2

- [ ] T024 [US1] Crear `core/src/main/java/co/edu/uco/notification/core/port/out/SubscriptionTicketPort.java`, `core/src/main/java/co/edu/uco/notification/core/port/in/IssueSubscriptionTicketUseCase.java` y `core/src/main/java/co/edu/uco/notification/core/usecase/IssueSubscriptionTicketService.java` con `Clock` inyectado y `SecureRandom` de 32 bytes.
- [ ] T025 [US1] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/SubscriptionTicketDocument.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/SubscriptionTicketMongoAdapter.java` (colección `subscription_tickets`, `_id` = huella SHA-256, índice TTL de 60 s sobre `expiresAt`, consumo con `findAndRemove` y `expiresAt > ahora`).
- [ ] T026 [US1] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/SubscriptionTicketController.java` y su DTO `SubscriptionTicketResponse`, registrar los beans en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/UseCaseConfig.java`, la regla en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/RouteAuthorizationPolicy.java` y la propiedad `notification.auth.subscription-ticket.ttl-seconds` (30) en `infrastructure/src/main/resources/application.yml`.
- [ ] T027 [US1] Cambiar `extractToken` de `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationWebFilter.java` para que, en `GET /notifications:subscribe` sin cabecera `Authorization`, lea `ticket`, lo consuma y construya el principal; dejar de leer `access_token`.
- [ ] T028 [US1] Actualizar `specs/015-autenticacion-interina/quickstart.md` § 6 (SSE por ticket) y la sección de autenticación de `README.md`; ejecutar `./mvnw -B -ntp spotless:apply`, las clases de E2 y las pruebas de arquitectura; marcar E2 como cerrada.

---

## Phase 4: E3 - Lote y adjuntos (US2, P1)

**Goal**: Ningún lote pasa de 500 ítems, un reenvío no se reprocesa, un guardado fallido no es silencioso y las cargas no quedan colgadas.

**Independent Test**: Enviar 501 ítems (400 sin efectos); reenviar el mismo `batchId`; forzar un guardado fallido del registro del lote; cambiar un objeto tras completarlo.

### Contract first (Principio II)

- [ ] T029 [US2] Editar `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: `maxItems: 500` en `SendNotificationBatchRequest.items`, 400 por exceso, campo obligatorio `trackingSaved` en `BatchAcceptedResponse`, descripción del reenvío con el mismo `batchId` y `rejectionReason` fijo `Internal error` para `FAILED`, como indica `specs/017-Fix-specs-012-016/contracts/api-notificaciones-cambios.md` § 1.
### Tests for E3 (escribir primero y verlos fallar)

- [ ] T030 [P] [US2] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationBatchControllerTest.java`: 501 ítems -> 400 sin invocar el caso de uso; exactamente 500 -> 202; el rechazo ocurre antes de procesar ningún ítem.
- [ ] T031 [P] [US2] Ampliar `core/src/test/java/co/edu/uco/notification/core/usecase/SendNotificationBatchServiceTest.java`: (a) un `batchId` ya registrado devuelve el resultado original y no acepta ningún ítem; (b) si el guardado falla, el resultado trae `trackingSaved=false` y se registra un ERROR con categoría `BATCH_RECORD_NOT_PERSISTED` (reemplaza la prueba `sendBatchStillReturnsTheResultWhenPersistingTheBatchRecordFails`, que fijaba el silencio); (c) una clave duplicada por carrera devuelve el registro ganador; (d) un ítem que falla por causa interna devuelve `Internal error` y la causa solo está en el log (control positivo: un rechazo de negocio conserva su propio mensaje).
- [ ] T032 [P] [US2] Ampliar `core/src/test/java/co/edu/uco/notification/core/port/in/BatchAcceptedResultTest.java` y `core/src/test/java/co/edu/uco/notification/core/port/in/BatchItemResultTest.java` con `trackingSaved` y con el motivo fijo de `failed`.
- [ ] T033 [P] [US2] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationBatchMongoAdapterTest.java`: `findByTenantAndBatchId` devuelve el resultado original, aísla tenants con el mismo `batchId` y el guardado usa el `Clock` inyectado.
- [ ] T034 [P] [US2] Ampliar `core/src/test/java/co/edu/uco/notification/core/usecase/ScanAttachmentUploadServiceTest.java`: perder el CAS y descartar la copia huérfana registran un INFO (control positivo: el camino feliz no lo registra).
- [ ] T035 [P] [US2] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/AttachmentScanListenerResilienceTest.java`: un `AttachmentObjectChangedException` que agota los intentos lleva la carga a `FAILED` con `SCAN_EXHAUSTED`; control positivo: un fallo transitorio que se recupera no la falla.
- [ ] T036 [US2] Ampliar el E2E `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationBatchE2ETest.java`: 501 ítems -> 400 y no se crea ninguna notificación; el reenvío del mismo `batchId` devuelve una respuesta idéntica y no crea notificaciones nuevas; el mismo `batchId` en dos tenants son registros independientes; con el guardado del registro fallando la respuesta es 202 con `trackingSaved=false`. Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/AttachmentUploadE2ETest.java` con el objeto cambiado tras completar, que termina en `FAILED/SCAN_EXHAUSTED` sin esperar al sweeper.
### Implementation for E3

- [ ] T037 [US2] Crear `core/src/main/java/co/edu/uco/notification/core/domain/BatchLimits.java` (`MAX_ITEMS = 500`) y aplicarlo en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationBatchController.java` junto al chequeo de lista vacía, rechazando con `IllegalArgumentException` mapeada a 400 antes de procesar nada.
- [ ] T038 [US2] Añadir `findByTenantAndBatchId` a `core/src/main/java/co/edu/uco/notification/core/repository/NotificationBatchRepository.java`, implementarlo en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationBatchMongoAdapter.java` (con el `Clock` inyectado en lugar de `Instant.now()`) y resolver la `DuplicateKeyException` de la carrera devolviendo el registro ganador.
- [ ] T039 [US2] Modificar `core/src/main/java/co/edu/uco/notification/core/usecase/SendNotificationBatchService.java`: consultar el registro antes de procesar, no reprocesar un lote existente, registrar con categoría el fallo de guardado en lugar del `onErrorResume(ex -> Mono.empty())` y devolver `trackingSaved`; sustituir `ex.getMessage()` por el motivo fijo `Internal error` en `failed`.
- [ ] T040 [US2] Añadir `trackingSaved` a `core/src/main/java/co/edu/uco/notification/core/port/in/BatchAcceptedResult.java` y a su DTO de respuesta en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/`; ajustar `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/BatchItemResultResponse.java` si hace falta.
- [ ] T041 [US2] Modificar `core/src/main/java/co/edu/uco/notification/core/usecase/ScanAttachmentUploadService.java` para registrar a nivel INFO la pérdida del CAS y el descarte de la copia huérfana, y exponer en su puerto de entrada la operación de fallo por agotamiento que ya usa el sweeper (`core/src/main/java/co/edu/uco/notification/core/usecase/ExpireAbandonedUploadsService.java`); invocarla desde `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/AttachmentScanListener.java` al agotar los intentos con `AttachmentObjectChangedException`.
- [ ] T042 [US2] Ejecutar `./mvnw -B -ntp spotless:apply`, las clases nuevas y ampliadas de E3, `HexagonalArchitectureTest` y `ModularityTests`; marcar E3 como cerrada.

---

## Phase 5: E4 - Despacho confiable (US3, P2)

**Goal**: Una reentrega no duplica el envío, el consumidor confirma solo tras persistir, el reencolado no deja huérfanos ni duplica entre réplicas y el reintento manual tiene una regla de contador definida.

**Independent Test**: Reentregar 100 veces el mismo mensaje produce 1 envío; un guardado fallido tras un envío aceptado no produce un segundo envío; un encolado fallido se reintenta.

### Tests for E4 - dominio y núcleo (escribir primero y verlos fallar)

- [ ] T043 [P] [US3] Ampliar `core/src/test/java/co/edu/uco/notification/core/domain/DeliveryAttemptTest.java` y el test de `Notification` en `core/src/test/java/co/edu/uco/notification/core/domain/` (crearlo si no existe): `cycle` por intento (1 por defecto al leer uno antiguo), `currentCycle`, `pendingSince` fijado en cada transición a `PENDING`, `dispatchReservedAt`, y el conteo de intentos recuperables, `shouldGiveUp` y `nextBackoff` calculados solo sobre el ciclo vigente; un reintento manual incrementa el ciclo y conserva el historial.
- [ ] T044 [P] [US3] Ampliar `core/src/test/java/co/edu/uco/notification/core/usecase/DispatchNotificationServiceTest.java`: una reentrega sobre `IN_PROCESS`, `RECOVERABLE`, `DELIVERED`, `FAILED` o `DISCARDED` se ignora sin error y sin llamar al proveedor; con la reserva ganada el proveedor se llama una sola vez; si `save` falla tras un envío aceptado se reintenta de forma acotada y no hay segundo envío; si el `sender` lanza antes de un resultado se libera la reserva y se propaga el error; un id inexistente lanza `NotificationNotFoundException`; el servicio usa siempre la instancia devuelta por la operación anterior (sin conflicto de versión falso).
- [ ] T045 [P] [US3] Ampliar `core/src/test/java/co/edu/uco/notification/core/usecase/RequeuePendingNotificationsServiceTest.java`: un `PENDING` es huérfano por `pendingSince` aunque tenga intentos; si el encolado de una notificación falla, las demás de la pasada se procesan, el fallo se registra con categoría y la notificación se reintenta en la pasada siguiente; el reclamo no entrega la misma notificación a dos llamadas; las `IN_PROCESS` con `dispatchReservedAt` anterior al umbral pasan a `RECOVERABLE`; el lote respeta `batch-size`.
### Tests for E4 - infraestructura (escribir primero y verlos fallar)

- [ ] T046 [P] [US3] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationMongoAdapterTest.java` (Testcontainers): `reserveForDispatch` atómico (dos llamadas concurrentes, una gana), `releaseReservation`, `claimForRequeue` (dos reclamos concurrentes no toman la misma notificación) y `claimStuckInProcess`; el índice `{status, pendingSince}` existe; un documento anterior sin los campos nuevos se lee con `pendingSince` igual a `acceptedAt`, `currentCycle` 1 y `cycle` 1 en sus intentos. Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationDocumentMapperTest.java` con los campos nuevos.
- [ ] T047 [P] [US3] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/ManualAckSettlerTest.java`: confirma tras persistir; ante un fallo republica con la cabecera `x-dispatch-attempt` incrementada y confirma el original; agotados los intentos deriva a la DLQ con `x-exception-message`; si la publicación no se confirma hace nack sin reencolar; un mensaje sin `message_id` no detiene el consumidor.
- [ ] T048 [P] [US3] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/NotificationDispatchListenerTest.java`, `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfigTest.java` y `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfigCustomAttemptsTest.java` para el modo `MANUAL`: ack solo tras persistir el resultado, reentrega ignorada con ack, e intentos máximos configurables.
- [ ] T049 [US3] Crear el E2E `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/DispatchIdempotencyE2ETest.java` (Testcontainers con un proveedor simulado que cuenta envíos): publicar 100 veces el mismo `notificationId` (con `message_id` distinto) produce exactamente 1 envío y la DLQ queda vacía; un `save` que falla tras un envío aceptado (decorador de prueba del repositorio) no produce segundo envío y el mensaje queda en la DLQ con la causa; un encolado fallido del reencolado se reintenta en la pasada siguiente; una notificación `IN_PROCESS` atascada pasa a `RECOVERABLE` tras el umbral; control positivo: una notificación nueva se entrega una vez.
- [ ] T050 [US3] Verificar en verde los E2E de proveedor deshabilitado `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoDisabledProviderE2ETest.java`, `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/TwilioDisabledProviderE2ETest.java` y `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/FcmDisabledProviderE2ETest.java` (la notificación queda `PENDING` sin intentos, el mensaje va a la DLQ con la causa y la reserva se libera) y actualizar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/DeadLetterQueueE2ETest.java` al ack manual.
### Implementation for E4

- [ ] T051 [US3] Modificar `core/src/main/java/co/edu/uco/notification/core/domain/Notification.java` y `core/src/main/java/co/edu/uco/notification/core/domain/DeliveryAttempt.java`: campos `dispatchReservedAt`, `pendingSince` y `currentCycle` (y `cycle` por intento) como en `specs/017-Fix-specs-012-016/data-model.md` § 1, con valores por defecto al reconstituir un documento antiguo; el conteo de intentos recuperables y la política de reintento pasan a calcularse sobre el ciclo vigente (`core/src/main/java/co/edu/uco/notification/core/domain/policy/RetryPolicy.java` y `core/src/main/java/co/edu/uco/notification/core/usecase/DispatchNotificationService.java`).
- [ ] T052 [US3] Añadir a `core/src/main/java/co/edu/uco/notification/core/repository/NotificationRepository.java` los métodos `reserveForDispatch`, `releaseReservation`, `claimForRequeue` y `claimStuckInProcess` con los contratos de `specs/017-Fix-specs-012-016/data-model.md` § 2.
- [ ] T053 [US3] Implementar los cuatro métodos en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationMongoAdapter.java` con `ReactiveMongoTemplate.findAndModify`, mapear los campos nuevos en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationDocument.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/NotificationDocumentMapper.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/DeliveryAttemptDocument.java`, y declarar el índice `{status, pendingSince}`.
- [ ] T054 [US3] Reescribir el flujo de `core/src/main/java/co/edu/uco/notification/core/usecase/DispatchNotificationService.java`: reservar antes de enviar, ignorar con INFO la reentrega sobre cualquier estado distinto de `PENDING`, enviar solo con la reserva ganada, guardar el resultado con la instancia devuelta, reintentar el guardado de forma acotada, liberar la reserva si el `sender` lanza antes de un resultado y publicar los eventos después de persistir.
- [ ] T055 [US3] Modificar `core/src/main/java/co/edu/uco/notification/core/usecase/RequeuePendingNotificationsService.java`: usar `claimForRequeue` y `pendingSince` en lugar del filtro `deliveryAttempts().isEmpty()`, aislar y registrar con categoría el error de cada notificación en lugar de `onErrorResume(error -> Mono.empty())` y de cortar el `Flux.merge`, liberar las `IN_PROCESS` atascadas con `claimStuckInProcess` y respetar `batch-size`; añadir en `infrastructure/src/main/resources/application.yml` las propiedades `notification.scheduler.in-process-timeout-ms` (600000) y `notification.scheduler.batch-size` (100).
- [ ] T056 [US3] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/ManualAckSettler.java` extrayendo la lógica de confirmación, republicación con confirmación, reintento por cabecera y DLQ que hoy vive en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/AttachmentScanListener.java`, y usarla desde ese listener (sin cambiar su comportamiento: sus pruebas existentes deben seguir en verde) y desde `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/NotificationDispatchListener.java`.
- [ ] T057 [US3] Pasar el listener de despacho a `AcknowledgeMode.MANUAL` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfig.java` retirando el interceptor con estado, cambiar la firma de `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/NotificationDispatchListener.java` para recibir el canal y la etiqueta de entrega, y confirmar solo tras persistir el resultado; conservar `notification.rabbit.dispatch.max-attempts` y el enrutamiento a `notification.dispatch.dlq.queue` con `x-exception-message`.
- [ ] T058 [US3] Ejecutar `./mvnw -B -ntp spotless:apply`, las clases nuevas y ampliadas de E4, los E2E de proveedor deshabilitado, `HexagonalArchitectureTest` y `ModularityTests`, y `./mvnw -B -ntp clean verify` completo (E4 toca el flujo central); marcar E4 como cerrada.

---

## Phase 6: E5 - Errores coherentes y cero comentarios (US5, P3)

**Goal**: Los conflictos responden 409, un error inesperado responde 500 genérico con correlación y el código de producción no tiene comentarios explicativos.

**Independent Test**: Provocar un conflicto de versión, una clave duplicada y un error inesperado; buscar comentarios en `src/main`.

### Contract first (Principio II)

- [ ] T059 [US5] Editar `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: declarar en `components/responses` `Conflict` (409) e `InternalError` (500) con `ErrorResponse` y referenciarlas desde `POST /notifications`, `POST /notifications:sendBatch`, `POST /notifications/{id}:retry` y las operaciones de subida de adjuntos, como indica `specs/017-Fix-specs-012-016/contracts/api-notificaciones-cambios.md` § 3.
### Tests for E5 (escribir primero y verlos fallar)

- [ ] T060 [P] [US5] Crear o ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationExceptionHandlerTest.java`: `NotificationVersionConflictException`, `NotificationAlreadyAcceptedException` e `InvalidStatusTransitionException` dan 409 con mensaje fijo; una excepción inesperada da 500 con mensaje genérico e identificador de correlación y sin el mensaje interno, y se registra la causa en ERROR con categoría; control positivo: los mapeos existentes (404, 400, 413) no cambian.
- [ ] T061 [P] [US5] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/architecture/NoExplanatoryCommentsTest.java`: recorre `src/main/java` de `core`, `infrastructure` y `utils` y falla si encuentra `//` o `/* */` explicativos, o Javadoc en miembros no públicos; control positivo con un archivo temporal que contiene un comentario y debe ser detectado.
- [ ] T062 [US5] Crear el E2E `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/ErrorResponsesE2ETest.java`: con un caso de uso sustituido por un doble que lanza un conflicto de versión la respuesta es 409; con otro que lanza una excepción inesperada es 500 genérico con `X-Correlation-Id` y el cuerpo no contiene el mensaje de la excepción; control positivo: una solicitud correcta responde 202.
### Implementation for E5

- [ ] T063 [US5] Añadir a `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationExceptionHandler.java` los manejadores de los tres 409 y una rama final para `Exception` con 500 genérico y correlación, registrando la causa en ERROR con categoría y sin devolver su mensaje.
- [ ] T064 [US5] Eliminar el comentario `//` de `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/security/platform/PlatformJwtTokenValidationAdapter.java` y los 12 bloques Javadoc de miembros no públicos de `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/KeyVaultSecretsLoader.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/KeyVaultEnvironmentPostProcessor.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/security/platform/PlatformJwtTokenValidationAdapter.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/AuthPlatformProperties.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/ChannelCatalogSeeder.java`, `core/src/main/java/co/edu/uco/notification/core/domain/policy/ContentSchemaValidator.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/RequestEnums.java`, dejando el código autoexplicativo.
- [ ] T065 [US5] Ejecutar `./mvnw -B -ntp spotless:apply`, `NoExplanatoryCommentsTest`, `NotificationExceptionHandlerTest`, el E2E de E5, `HexagonalArchitectureTest` y `ModularityTests`; marcar E5 como cerrada.

---

## Phase 7: E6 - Reconciliación de artefactos (US4, P2)

**Goal**: Las specs 011 a 016 y 018 describen el servicio real: autenticación por token, rutas actuales, estados reales y pruebas por rol.

**Independent Test**: Buscar `X-Tenant-Id` e `infra-main/` en las specs sin coincidencias, y ejecutar los quickstarts de las historias mergeadas con credencial.

### Solo documentación (sin cambios de código)

- [ ] T066 [P] [US4] Reconciliar `specs/011-consultar-catalogo/` (`plan.md`, `quickstart.md`, `research.md`, `tasks.md`): sustituir `X-Tenant-Id` y el 400 por credencial Bearer y 401, y reemplazar las 15 rutas `infra-main/` e `infra-test/` por las rutas completas.
- [ ] T067 [P] [US4] Reconciliar `specs/012-adjuntar-archivo-notificacion/`: revisar `plan.md` líneas 236 y 740 y `tasks.md` T091 y dejar solo menciones históricas marcadas como tales; resolver la sección posterior 'propuesta, pendiente de aprobación' de `plan.md` según el estado real.
- [ ] T068 [P] [US4] Reconciliar `specs/013-preferencias-destinatario/`: confirmar con el usuario las clarificaciones Q1 a Q4 de `spec.md` y registrar la respuesta, pasar el estado del plan según corresponda, añadir tareas que cubran FR-012 (lote) y FR-015, sustituir `X-Tenant-Id` en `quickstart.md` y `tasks.md` por credencial Bearer y declarar los roles necesarios con pruebas por rol.
- [ ] T069 [P] [US4] Reconciliar `specs/014-exponer-envio-lote/`: pasar `plan.md` a `Aceptado` y `spec.md` a su estado real, dejar T007 a T009 coherentes con lo cerrado en el PR #57, y sustituir las menciones de `X-Tenant-Id` en `spec.md`, `plan.md`, `quickstart.md`, `data-model.md` y `tasks.md`.
- [ ] T070 [P] [US4] Reconciliar `specs/016-logs-correlation-id/`: reordenar los FR de `spec.md` (FR-013 a FR-017) y mover la política WARN de Assumptions a requisitos, justificar o corregir T033 (la prueba `RabbitRetryConfigCustomAttemptsTest` no menciona la correlación) y dejar anotada la fecha de la excepción del Principio VII (2026-10-15).
- [ ] T071 [P] [US4] Reconciliar `specs/018-reintentar-envio-manual/`: declarar el rol `OPERADOR` para `:retry` en `spec.md`, `plan.md` y `contracts/api-notificaciones-cambios.md`; añadir tareas de prueba 403 (`CLIENTE`) y 202 (`OPERADOR`) y de 401/403 en la descripción del contrato; sustituir `X-Tenant-Id`; reemplazar las 13 rutas `infra-*`; reemplazar la excepción del ack `AUTO` por la dependencia del despacho con ack manual y reserva atómica de esta spec (E4); cubrir con una tarea el `PENDING` bloqueado si falla el encolado tras persistir; y reemplazar la regla 'cuenta dentro del tope y no lo reinicia' por la del ciclo de intentos (D9).
- [ ] T072 [US4] Ejecutar con credencial Bearer, contra un servicio local aislado (ver `specs/017-Fix-specs-012-016/quickstart.md`), los quickstarts actualizados de `specs/011-consultar-catalogo/quickstart.md`, `specs/014-exponer-envio-lote/quickstart.md`, `specs/016-logs-correlation-id/quickstart.md` y `specs/015-autenticacion-interina/quickstart.md`, anotando en cada uno el resultado; los de 013 y 018 quedan pendientes hasta que esas historias se implementen.
- [ ] T073 [US4] Comprobar con `grep -rn "X-Tenant-Id" specs/011-* specs/013-* specs/014-* specs/018-* specs/012-adjuntar-*` que no quedan coincidencias salvo notas históricas marcadas, y con `grep -rln "infra-main/\|infra-test/" specs` que no quedan rutas abreviadas obsoletas; anotar el resultado en `specs/017-Fix-specs-012-016/tasks.md`.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T074 Ejecutar `./mvnw -B -ntp clean verify` completo en la rama final de cada entrega (con `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` si Testcontainers lo requiere) y confirmar cobertura >= 80 % líneas y >= 70 % ramas, Spotless, SpotBugs y FindSecBugs en verde; si no es ejecutable en local, anotar en `specs/017-Fix-specs-012-016/tasks.md` que lo decide CI.
- [ ] T075 Ejecutar los escenarios de `specs/017-Fix-specs-012-016/quickstart.md` de las historias 1 a 5 contra un servicio local aislado y anotar el resultado de cada uno en ese mismo archivo.
- [ ] T076 Actualizar `specs/017-Fix-specs-012-016/informe.md` con una tabla de cierre que indique el estado final de cada hallazgo comprometido y, para los no comprometidos, la excepción del Principio VII con su dueño y fecha (2026-11-30) tomada de `specs/017-Fix-specs-012-016/plan.md`.
- [ ] T077 Revisar que `HexagonalArchitectureTest` y `ModularityTests` siguen en verde con el árbol final y que no queda ninguna tarea abierta de `specs/017-Fix-specs-012-016/tasks.md` sin motivo anotado.

---

## Dependencies & Execution Order

- Phase 1 (Setup) antes de todo.
- No hay fase fundacional: las entregas son independientes salvo lo indicado.
- **E2 depende de E1** (comparten `AuthenticationWebFilter` y la configuración de seguridad).
- **E3 antes de E4**: ambas tocan `AttachmentScanListener` (E3 añade la llamada de fallo por agotamiento y E4 extrae `ManualAckSettler`); hacerlas en ese orden evita conflictos.
- E4 es la más grande y desbloquea la implementación de 013 y 018; conviene cerrarla antes de implementar el reintento manual.
- E5 es independiente (toca `NotificationExceptionHandler` y comentarios); si se hace después de E1 y E3, el escaneo de comentarios también cubre los archivos nuevos.
- **E6 es solo documentación** y puede hacerse en paralelo con las demás, salvo la parte de 018, que cita la regla del ciclo de intentos y el ack manual (depende de lo decidido en E4).
- Dentro de cada entrega: contrato (si aplica) -> pruebas que fallan -> implementación -> `spotless:apply` -> E2E de la entrega.

## Parallel Opportunities

- En cada fase, las tareas de prueba marcadas `[P]` tocan archivos distintos y pueden escribirse a la vez.
- E6 se reparte por spec (011, 012, 013, 014, 016 y 018 son archivos independientes).
- E5 y E6 pueden avanzar mientras E4 está en revisión.

## Implementation Strategy

- **MVP**: E1. Cierra el riesgo más grave del informe (credenciales forjables) y es independiente.
- Orden recomendado de entrega: E1, E3, E4, E2, E5, E6. E2 se adelanta o se retrasa según la prioridad del panel en vivo.
- Cada entrega es un PR contra `develop`, con commits de una línea y el razonamiento en el cuerpo del PR (Principio V); el usuario revisa y mergea.
- Antes de implementar E4, releer `data-model.md` y `research.md` D8 y D9: son los puntos con más riesgo de regresión sobre 008, 009 y 010.

