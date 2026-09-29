---

description: "Task list for HU2-092 (plan v3)"
---

# Tasks: Adjuntar un archivo a una notificación

**Input**: Design documents from `/specs/012-adjuntar-archivo-notificacion/`

**Prerequisites**: plan.md (versión 3, Aceptado), spec.md (Q1 reabierta: híbrido por tamaño; Q2–Q5
confirmadas), research.md, data-model.md, contracts/api-notificaciones-cambios.md, quickstart.md

**Versión**: regenerado el 2026-09-29 para el plan v3. Sustituye a la lista de la v1 (T001–T029). Lo que la
v1 dejó hecho y la v3 conserva está marcado `[X]` con su origen; lo que la v3 cambia está abierto con la
nota "reabre vN T0xx". La correspondencia completa está al final, en [Correspondencia con la lista
v1](#correspondencia-con-la-lista-v1).

**Trabajo sin commitear heredado**: hay cambios sin commitear de la v1 (T019–T022) en
`SendNotificationBatchService.java`, `SendNotificationBatchServiceTest.java`,
`NotificationExceptionHandler.java` y `NotificationControllerTest.java`. No se tocaron al regenerar esta
lista. Las tareas T046–T049 los revisan y rehacen: su regla se conserva, pero sus pruebas usan una `url`
https arbitraria como adjunto válido, que en la v3 es inválido. `Dockerfile` también tiene cambios sin
commitear y no pertenece a esta historia.

**Tests**: solicitadas explícitamente (Principio IV y plan § Pruebas): unitarias en `core` con
`StepVerifier`; integración de cada adaptador contra su servicio real con Testcontainers (MongoDB,
RabbitMQ, MinIO, ClamAV); `@WebFluxTest` de los controladores; y dos E2E, `NotificationAttachmentE2ETest`
(camino chico) y `AttachmentUploadE2ETest` (camino grande y aislamiento por tenant). Orden por tarea:
prueba → verla fallar por la razón correcta → implementar → ejecutar la clase → `spotless:apply`. Los
mensajes de consumidores y adaptadores se producen con el productor real, nunca con JSON armado a mano;
toda prueba de "no aparece" o "no llega" lleva su control positivo; todo umbral de tiempo se afirma con
`Duration`.

**Organization**: contrato e infraestructura primero; una fase fundacional grande (modelo, política,
puertos, inspector, resolvedor y los adaptadores sin los cuales el contexto no arranca); luego una fase por
historia de usuario en orden de prioridad (P1: US1, US2, US4, US6, US7; P2: US3, US5), y el cierre.

## Path Conventions

- `core-main/` = `core/src/main/java/co/edu/uco/notification/core/`
- `core-test/` = `core/src/test/java/co/edu/uco/notification/core/`
- `infra-main/` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/`
- `infra-test/` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/`

---

## Phase 1: Setup

**Purpose**: contrato público primero (Principio II), dependencias e infraestructura local.

- [ ] T001 Aplicar `contracts/api-notificaciones-cambios.md` (v3) a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: etiqueta `Adjuntos`; `Attachment` con `content` XOR `url` (`oneOf`), lista blanca v3 y extensiones prohibidas; descripción de `SendNotificationRequest.attachments`; `400` ampliado y `409`, `413`, `503` nuevos en `POST /notifications`; frase del lote; operaciones `POST /attachment-uploads`, `POST /attachment-uploads/{uploadId}:complete` y `GET /attachment-uploads/{uploadId}`; parámetro `UploadId` y esquemas `IssueAttachmentUploadRequest`, `AttachmentUploadState` y `AttachmentUploadResponse` (reabre v1 T001)
- [ ] T002 [P] Añadir los servicios `minio` y `clamav` a `docker-compose.yml`, con imágenes fijadas por versión, puertos configurables (`MINIO_PORT`, `MINIO_CONSOLE_PORT`, `CLAMAV_PORT`), credenciales de MinIO desde `.env` y volúmenes propios; añadir a `.env.example` `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT`, `CLAMAV_HOST` y `CLAMAV_PORT`, con valores de ejemplo y nunca secretos reales
- [ ] T003 [P] Añadir las dependencias con versión fijada en `pom.xml` (gestión de dependencias) e `infrastructure/pom.xml`: `tika-core`, el módulo de Tika que detecta contenedores OOXML (sin los analizadores completos), el cliente de MinIO (`MinioAsyncClient`) y el módulo MinIO de Testcontainers en `test` (si la versión 1.19.8 no lo trae, usar `GenericContainer` y anotarlo aquí); `./mvnw -B -ntp clean compile` en verde
- [ ] T004 Crear `infra-test/support/AttachmentTestContainers.java` con contenedores compartidos de ClamAV (`GenericContainer`, puerto 3310, espera hasta que `clamd` acepte conexiones) y MinIO, y una prueba mínima que arranque ambos; medir el arranque de ClamAV bajo Testcontainers y dejar la cifra escrita en esta tarea (plan, Riesgos) — depende de T003
- [ ] T005 Crear `infra-main/config/AttachmentProperties.java` (`@ConfigurationProperties("notification.attachments")`: `scan.timeout` 10 s, `scan.clean-verdict-ttl` 24 h, `scan.consumer-concurrency` 2, `upload.expiration` 15 min, `storage.bucket`, `storage.endpoint`, `storage.public-endpoint`, `clamav.host`, `clamav.port`) y el bloque correspondiente, más `spring.codec.max-in-memory-size: 8MB`, en `infrastructure/src/main/resources/application.yml`, sin secretos (data-model.md § Configuración)

**Checkpoint**: contrato actualizado; `clean compile` en verde; ClamAV y MinIO arrancan bajo Testcontainers.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: modelo, reglas, puertos, verificación y resolución de adjuntos, y los adaptadores que el
contexto necesita para arrancar. Al final, el build completo compila y la suite existente sigue en verde.

### 2A — Conservado de la v1

- [X] T006 `boolean supportsAttachments()` abstracto en `core-main/port/out/NotificationSenderPort.java`, implementado en los cuatro adaptadores de `infra-main/adapter/out/provider/` (`simulated` → `true`, Brevo/Twilio/FCM → `false`) y en los tres dobles de prueba, con sus pruebas — **conservado de la v1** (T002–T003 v1, commit `0bd17b0`); se revalida en la regresión (T074)
- [X] T007 `core-main/exception/InvalidAttachmentException.java` (posición, regla y nombre; mensaje `attachments[i]: <regla> (<fileName>)`, posición `-1` sin índice) — **conservado de la v1** (T009 v1, commit `0bd17b0`)

### 2B — Modelo y reglas en `core`

- [ ] T008 [P] Pruebas en `core-test/domain/AttachmentSubmissionTest.java`: normaliza `contentType` como la v1, conserva `content` y `url` tal cual, `isEmbedded()` / `isReference()`, `toString()` sin `content` ni `url` y con nombre, tipo y tamaño
- [ ] T009 Crear `core-main/domain/valueobject/AttachmentSubmission.java` (data-model.md) — depende de T008
- [ ] T010 [P] Pruebas en `core-test/domain/Sha256DigestTest.java`: huella conocida de un contenido fijo, 64 caracteres hexadecimales en minúsculas, rechazo de un valor mal formado; y de `UploadId` (no vacío)
- [ ] T011 Crear `core-main/domain/valueobject/Sha256Digest.java`, `UploadId.java`, `ScanState.java`, `ScanVerdict.java` y `AttachmentRejectionReason.java` (data-model.md) — depende de T010
- [ ] T012 [P] Reescribir `core-test/domain/AttachmentTest.java` para el `Attachment` verificado: `tenantId`, `sha256` y `source` no nulos; `EmbeddedContent` copia los bytes al construir y al leer; `StoredObject` conserva `uploadId` y `objectKey`; `toString()` sin bytes ni clave de objeto y con nombre, tipo, tamaño y huella (reabre v1 T004)
- [ ] T013 Rediseñar `core-main/domain/valueobject/Attachment.java` y crear `core-main/domain/valueobject/AttachmentSource.java` (sellada: `EmbeddedContent`, `StoredObject`) (reabre v1 T005) — depende de T011, T012
- [ ] T014 Ajustar `core-test/domain/NotificationContentTest.java` al nuevo `Attachment` (los datos de ejemplo con `url` pasan a `EmbeddedContent`); `core-main/domain/valueobject/NotificationContent.java` no debería cambiar: si cambia, anotarlo aquí (reabre las pruebas de v1 T006) — depende de T013
- [ ] T015 [P] Reescribir `core-test/domain/AttachmentPolicyTest.java` para `validate(List<AttachmentSubmission>)`: 5 adjuntos válidos y 6 rechazados; nombre (se conservan los casos de la v1); cada una de las 28 extensiones prohibidas, en mayúsculas y con punto o espacio final, `factura.pdf.exe` rechazado y `factura.exe.pdf` aceptado por esta capa; lista blanca v3 (`image/gif` e `image/webp` rechazados); `sizeBytes` 1 y 10 485 760 válidos, 0, negativo, nulo y 10 485 761 inválidos; `content` y `url` a la vez, ninguno, Base64 inválido, con prefijo `data:` o con saltos de línea; embebido de 1 048 576 válido y de 1 048 577 inválido; embebido con tamaño decodificado distinto de `sizeBytes`; `url` con `sizeBytes` ≤ 1 048 576; `url` de 2049 caracteres; suma de 26 214 400 válida y de 26 214 401 inválida sin posición; `validateUploadRequest` (reglas de nombre, extensión, tipo y tamaño más `sizeBytes` > 1 048 576); `decode`; ningún mensaje contiene el contenido ni la `url`; se reporta la primera regla del primer adjunto inválido (reabre v1 T008)
- [ ] T016 Reescribir `core-main/domain/policy/AttachmentPolicy.java` (constantes, `validate`, `validateUploadRequest`, `decode`; research.md, Decisión 3) (reabre v1 T009) — depende de T009, T015
- [ ] T017 [P] Pruebas en `core-test/domain/AttachmentUploadTest.java`: `issue` nace en `PENDING_SCAN` con `uploadKey` por tenant; `markCompleted`, `markClean` (con `cleanKey`) y `markInfected` (con motivo y firma) solo desde `PENDING_SCAN`; cualquier otra transición lanza `IllegalStateException`; nunca vuelve atrás
- [ ] T018 Crear `core-main/domain/AttachmentUpload.java` (data-model.md) — depende de T011, T017
- [ ] T019 [P] Ajustar `core-test/domain/ContentSchemaValidatorTest.java` a la entrada con `AttachmentSubmission`: se conservan los casos de la v1 (cerrado por defecto, `maxItems`, `enum`, `maximum`, ruta `attachments[i]`) y se añade que un `content` y una `url` reconocibles nunca aparecen en el mensaje (reabre v1 T010)
- [ ] T020 Ajustar `core-main/domain/policy/ContentSchemaValidator.java` para validar los metadatos de las `AttachmentSubmission` (el nodo sigue siendo `{fileName, contentType, sizeBytes}`) (reabre v1 T011) — depende de T009, T019

### 2C — Puertos, verificación y aceptación en `core`

- [ ] T021 Crear los puertos y excepciones de data-model.md: `core-main/port/out/AttachmentStoragePort.java` (con `PresignedUpload`, `StoredObjectInfo`, `UploadLocation`), `MalwareScannerPort.java`, `ContentTypeDetectorPort.java`, `ScanVerdictCachePort.java`, `AttachmentScanRequestPort.java`, `core-main/repository/AttachmentUploadRepository.java`, y `core-main/exception/AttachmentNotReadyException.java`, `AttachmentUploadNotFoundException.java` y `AttachmentInspectionUnavailableException.java` — depende de T011, T018
- [ ] T022 [P] Pruebas en `core-test/usecase/AttachmentInspectorTest.java`: la huella se calcula siempre; tipo detectado fuera de la lista o distinto del declarado → `CONTENT_TYPE_MISMATCH` sin llamar al antivirus; `text/csv` declarado con `text/plain` detectado se acepta; caché `INFECTED` → rechazo sin escanear; caché `CLEAN` de la misma versión de firmas → sin escanear; caché `CLEAN` de otra versión → escanea; sin caché → escanea y guarda el veredicto por `(tenantId, sha256)`; error del escáner → `AttachmentInspectionUnavailableException`
- [ ] T023 Crear `core-main/usecase/AttachmentInspector.java` (research.md, Decisión 12) — depende de T021, T022
- [ ] T024 [P] Pruebas en `core-test/usecase/AttachmentResolverTest.java`: embebido limpio → `Attachment` con `EmbeddedContent`, tenant y huella; embebido infectado → `InvalidAttachmentException` de software malicioso; referencia `CLEAN` → `StoredObject(uploadId, cleanKey)`; `PENDING_SCAN` → `AttachmentNotReadyException`; `INFECTED` → `InvalidAttachmentException`; `url` no emitida, de otro tenant (ruta con otro tenant, o subida inexistente para el tenant de la solicitud) → el **mismo** mensaje en los tres casos; nombre, tipo o tamaño distintos de los de la subida → rechazo; el orden de salida es el de entrada; ningún mensaje contiene el contenido ni la `url`
- [ ] T025 Crear `core-main/usecase/AttachmentResolver.java` (`concatMap`; data-model.md) — depende de T016, T023, T024
- [ ] T026 Añadir `List<AttachmentSubmission> attachments` (nulo → vacío) a `core-main/port/in/SendNotificationCommand.java` y `core-main/port/in/BatchNotificationItem.java`; `content` sigue llevando `subject` y `body` sin adjuntos (data-model.md) — depende de T009
- [ ] T027 [P] Reescribir las pruebas de adjuntos de `core-test/usecase/SendNotificationServiceTest.java`: orden ruta → `AttachmentPolicy` → `ContentSchemaValidator` → `AttachmentResolver` → duplicada → aceptación; cada rechazo sin `save`, `publish` ni `enqueueForDispatch`; la inspección ocurre antes de buscar la duplicada; duplicada válida devuelve la original sin guardar; `AttachmentInspectionUnavailableException` se propaga sin guardar; la notificación guardada lleva los `Attachment` verificados en orden (reabre v1 T012 y T018)
- [ ] T028 Ajustar `core-main/usecase/SendNotificationService.java` al nuevo orden y construir el `NotificationContent` final con los `Attachment` resueltos (reabre v1 T013) — depende de T020, T025, T026, T027

### 2D — Adaptadores base e integración en `infrastructure`

- [ ] T029 [P] Pruebas en `infra-test/adapter/out/detection/TikaContentTypeDetectorAdapterTest.java` con archivos generados en la propia prueba, sin versionar binarios (PDF mínimo, PNG y JPEG con `ImageIO`, DOCX y XLSX mínimos con `java.util.zip`, CSV, TXT): cada tipo de la lista blanca se detecta como tal; un ejecutable (cabecera `MZ`) declarado `.pdf` no es PDF; un HTML declarado como texto se detecta como `text/html`
- [ ] T030 Crear `infra-main/adapter/out/detection/TikaContentTypeDetectorAdapter.java` (detección por contenido en `Schedulers.boundedElastic()`) — depende de T021, T029
- [ ] T031 [P] Pruebas en `infra-test/adapter/out/antivirus/ClamAvMalwareScannerAdapterTest.java` con el contenedor de T004: la cadena EICAR → `INFECTED` con el nombre de la firma; un texto limpio → `CLEAN`; `signatureVersion()` no vacía; puerto cerrado y tiempo vencido → `AttachmentInspectionUnavailableException` (parte de SC-014)
- [ ] T032 Crear `infra-main/adapter/out/antivirus/ClamAvMalwareScannerAdapter.java` (protocolo `INSTREAM` y `VERSION` por Reactor Netty `TcpClient`, tiempo máximo y concurrencia acotada; versión de firmas cacheada unos minutos) y `infra-main/config/ClamAvConfig.java` — depende de T005, T021, T031
- [ ] T033 [P] Pruebas en `infra-test/adapter/out/mongo/ScanVerdictMongoAdapterTest.java` (Testcontainers): guardar y leer por `(tenantId, sha256)`; la misma huella en dos tenants son dos entradas y ninguna se lee desde el otro tenant (con control positivo); el índice TTL existe y solo los `CLEAN` tienen `expiresAt`
- [ ] T034 Crear `infra-main/adapter/out/mongo/ScanVerdictDocument.java` y `ScanVerdictMongoAdapter.java` con sus índices (research.md, Decisión 9) — depende de T021, T033
- [ ] T035 [P] Pruebas en `infra-test/adapter/out/storage/MinioAttachmentStorageAdapterTest.java` con el contenedor de T004: `presignUpload` y PUT real con `WebClient` a la dirección firmada contra el endpoint público; `stat` con tamaño y ETag; `read` con el ETag vigente; `copyIfMatch` con ETag obsoleto falla (SC-013); `delete`; `parseUploadUrl` acepta el endpoint público, el bucket y la ruta `tenants/{tenantId}/uploads/{uploadId}` ignorando la consulta, y rechaza otro servidor, otro puerto, otro bucket, otra ruta y una dirección mal formada; el bucket se crea en el primer uso, no al arrancar
- [ ] T036 Crear `infra-main/adapter/out/storage/MinioAttachmentStorageAdapter.java` (`MinioAsyncClient` con `Mono.fromFuture`) y `infra-main/config/MinioConfig.java` (credenciales solo por entorno) — depende de T005, T021, T035
- [ ] T037 [P] Pruebas en `infra-test/adapter/out/mongo/AttachmentUploadMongoAdapterTest.java` (Testcontainers): guardar y leer por `(tenantId, uploadId)`; con otro tenant → vacío y ningún campo de la subida ajena en el resultado (con control positivo); `transition` condicionada: dos transiciones concurrentes desde `PENDING_SCAN`, solo una gana
- [ ] T038 Crear `infra-main/adapter/out/mongo/AttachmentUploadDocument.java` y `AttachmentUploadMongoAdapter.java` (`findAndModify` condicionado al estado y la versión) — depende de T018, T021, T037
- [ ] T039 [P] Reescribir las pruebas de adjuntos de `infra-test/adapter/out/mongo/NotificationMongoAdapterTest.java`: ida y vuelta con un adjunto `EMBEDDED` (`BinData`, mismos bytes) y uno `OBJECT` (`uploadId`, `objectKey`), en orden, con `tenantId` y `sha256`; documento sin `attachments` → lista vacía; búsqueda y `findByStatus` no traen `attachments.content`; un adjunto con `tenantId` distinto al de la notificación al reconstituir → error de integridad (reabre v1 T014)
- [ ] T040 Rediseñar `infra-main/adapter/out/mongo/AttachmentDocument.java` (sin `url`), ajustar `NotificationDocumentMapper.java` y añadir la proyección sin `attachments.content` a la búsqueda y al reencolado en `NotificationMongoAdapter.java` (reabre v1 T015) — depende de T013, T039
- [ ] T041 [P] Ajustar las pruebas de mapeo de `infra-test/adapter/in/rest/NotificationControllerTest.java`: `attachments` del request llega al comando como `AttachmentSubmission` en orden, con `content` o `url` y el tipo normalizado; ausente o `null` → lista vacía; `AttachmentRequest.toString()` sin `content` ni `url` (reabre v1 T016)
- [ ] T042 Añadir `content` a `infra-main/adapter/in/rest/AttachmentRequest.java` (`toString` sin `content` ni `url`) y mapear a `AttachmentSubmission` en `toCommand` de `infra-main/adapter/in/rest/NotificationController.java` (reabre v1 T017) — depende de T026, T041
- [ ] T043 Cablear en `infra-main/config/UseCaseConfig.java` `AttachmentInspector`, `AttachmentResolver` y el nuevo `SendNotificationService`; comprobar que el contexto arranca sin MinIO ni ClamAV disponibles (ningún adaptador se conecta al arrancar; RNF-12) ejecutando `ProviderRoutingE2ETest` y `NotificationServiceApplicationTests` — depende de T028, T030, T032, T034, T036, T038, T040, T042

**Checkpoint**: `./mvnw -B -ntp clean compile` en verde; `./mvnw -B -ntp -pl core test` en verde; la
suite E2E existente sin cambios de expectativas (SC-004).

---

## Phase 3: User Story 1 - Enviar una notificación con un archivo chico embebido (P1) 🎯 MVP

**Goal**: un adjunto de hasta 1 MB embebido y limpio se acepta, se guarda como `BinData` con la
notificación y el proveedor lo recibe al despachar, también en reintentos.

**Independent Test**: spec.md, User Story 1, escenarios 1–5; SC-001 (embebido) y SC-012.

- [ ] T044 [US1] Crear `infra-test/adapter/in/rest/NotificationAttachmentE2ETest.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers` con MongoDB, RabbitMQ y el ClamAV de T004 + `WebTestClient`, estructura de `NotificationLiveUpdatesE2ETest`; `notification.catalog.refresh-interval-ms=1000`; emisores de prueba `recording-attachments` (`true`) y `recording-plain` (`false`) como beans que guardan lo que reciben; en `@BeforeEach`, canales de prueba en `channel_catalog` y espera activa a que `GET /channels` los refleje). Casos de US1: PDF limpio de 500 KB embebido → `202` → `DELIVERED`; el emisor recibe los mismos bytes, nombre, tipo, tamaño y huella en el orden enviado; el documento en MongoDB tiene `BinData` y `sha256`; duplicada válida → `duplicate: true` sin cambiar los adjuntos; SC-012: 5 × 1 MB embebidos limpios aceptados en ≤ `Duration.ofSeconds(5)` con ClamAV ya usado una vez en la clase
- [ ] T045 [US1] Escenario US1.5 (reintento con los mismos adjuntos) en `NotificationAttachmentE2ETest`: el emisor de prueba devuelve un fallo temporal la primera vez y en el reintento recibe los mismos adjuntos; si el intervalo de reintento no puede bajarse a segundos en la prueba, cubrirlo con la ida y vuelta de T039 más `DispatchNotificationServiceTest` y anotar aquí la desviación — depende de T044

**Checkpoint**: camino chico de punta a punta.

---

## Phase 4: User Story 2 - Rechazar completo lo que no se admite (P1)

**Goal**: cualquier adjunto inválido rechaza la notificación completa con el código correcto y un motivo
sin contenido ni dirección; en lote, solo el elemento; con el antivirus caído, `503` y nada aceptado.

**Independent Test**: spec.md, User Story 2, escenarios 1–10; SC-002, SC-007, SC-008 (embebido) y SC-014.

- [ ] T046 [P] [US2] Rehacer las pruebas de lote en `core-test/usecase/SendNotificationBatchServiceTest.java` (reabre v1 T019). **Hay trabajo sin commitear** de la v1 en este archivo: su caso válido usa una `url` https arbitraria, que en la v3 es inválida. Rehacerlo con un adjunto embebido, con el `AttachmentResolver` real y los puertos simulados. Casos: un elemento con adjunto inválido, otro con una subida en `PENDING_SCAN` y otro válido → los dos primeros `rejected` con su motivo, el válido `accepted` (SC-007); `AttachmentInspectionUnavailableException` no se convierte en `rejected`
- [ ] T047 [US2] Ajustar `core-main/usecase/SendNotificationBatchService.java`: `InvalidAttachmentException` y `AttachmentNotReadyException` → `rejected` (reabre v1 T020; **hay trabajo sin commitear** que ya añade `InvalidAttachmentException`: revisarlo, conservarlo y ampliarlo) — depende de T021, T046
- [ ] T048 [P] [US2] Pruebas de errores en `infra-test/adapter/in/rest/NotificationControllerTest.java`: `InvalidAttachmentException` → `400` con `attachments[i]` y sin `content` ni `url`; `AttachmentNotReadyException` → `409`; `AttachmentInspectionUnavailableException` → `503`; cuerpo mayor a 8 MB → `413` (reabre v1 T021; **hay trabajo sin commitear** con el caso `400`: conservarlo y ajustar su dato de ejemplo)
- [ ] T049 [US2] Manejar en `infra-main/adapter/in/rest/NotificationExceptionHandler.java` `InvalidAttachmentException` → `400`, `AttachmentNotReadyException` → `409`, `AttachmentUploadNotFoundException` → `404` y `AttachmentInspectionUnavailableException` → `503`, y confirmar el `413` del límite del codec (reabre v1 T022; **hay trabajo sin commitear** con el `400`: conservarlo) — depende de T021, T048
- [ ] T050 [US2] Casos de rechazo en `NotificationAttachmentE2ETest`: SC-002, una solicitud por regla de FR-006 que aplique al camino chico (canal sin adjuntos, tipo del canal, tipo global, extensión prohibida `factura.pdf.exe`, tamaño del canal, tope agregado, cantidad, nombre, tipo ausente, tamaño inválido, `content` y `url` a la vez, ninguno, Base64 inválido, embebido > 1 MB, tamaño decodificado distinto, `url` para ≤ 1 MB, tipo real distinto del declarado) → `400` con `attachments[i]` y ningún documento guardado para esos `externalId`; SC-008 embebido: la cadena EICAR en un `.txt` → `400` de software malicioso y nada guardado; cuerpo > 8 MB → `413` — depende de T044, T049
- [ ] T051 [US2] Crear `infra-test/adapter/in/rest/NotificationAttachmentScannerDownE2ETest.java` (SC-014): contexto con MongoDB y RabbitMQ y `notification.attachments.clamav.port` apuntando a un puerto cerrado; notificación con adjunto embebido → `503` y ningún documento guardado; notificación sin adjuntos por el mismo canal → `202` (control positivo) — depende de T049

---

## Phase 5: User Story 4 - El archivo adjunto nunca queda expuesto (P1)

**Goal**: aceptación, rechazo, emisión, aviso y análisis quedan registrados con nombre, tipo, tamaño y
huella; ni el contenido ni la dirección de subida aparecen nunca.

**Independent Test**: spec.md, User Story 4, escenarios 1–4; SC-003 (el camino grande en T062).

- [ ] T052 [P] [US4] Pruebas con `ListAppender` en `infra-test/adapter/in/rest/NotificationControllerTest.java`: aceptación con adjuntos registra `tenantId`, `externalId`, `notificationId`, `duplicate` y `[fileName|contentType|sizeBytes|sha256]`; rechazo registra `tenantId`, `externalId`, metadatos y motivo; ninguna línea contiene el `content` ni la `url`; un nombre con `\r\n` se registra en una sola línea; sin adjuntos no se registra nada nuevo (reabre v1 T023)
- [ ] T053 [US4] Crear `infra-main/adapter/in/rest/AttachmentLogFormatter.java` y registrar aceptación (`doOnSuccess`) y rechazo (`doOnError`) solo con adjuntos en `infra-main/adapter/in/rest/NotificationController.java` (research.md, Decisión 8) (reabre v1 T024) — depende de T042, T052
- [ ] T054 [US4] SC-003, camino chico, en `NotificationAttachmentE2ETest`: un contenido embebido reconocible, una vez aceptado y otra rechazado, no aparece en registros capturados con `ListAppender`, cuerpos de error, eventos leídos de una cola temporal enlazada al exchange de eventos, `GET /notifications/{id}` ni `GET /notifications`; nombre, tipo, tamaño y huella sí aparecen en los registros (control positivo) — depende de T044, T053

---

## Phase 6: User Story 6 - Adjuntar un archivo grande mediante una subida al servicio (P1)

**Goal**: emitir la subida, recibir el archivo en MinIO, analizarlo de forma asíncrona con la máquina de
estados y aceptar notificaciones que referencian subidas `CLEAN`.

**Independent Test**: spec.md, User Story 6, escenarios 1–8; SC-001 (subida), SC-008 (subida), SC-009,
SC-011 y SC-013.

- [ ] T055 [P] [US6] Pruebas en `core-test/usecase/`: `IssueAttachmentUploadServiceTest` (valida con `validateUploadRequest`, crea la subida en `PENDING_SCAN` con `uploadKey` del tenant y devuelve dirección y vencimiento), `CompleteAttachmentUploadServiceTest` (inexistente u otro tenant → `AttachmentUploadNotFoundException`; objeto ausente → rechazo `409`; tamaño distinto → rechazo `400`, objeto borrado y sigue `PENDING_SCAN`; correcto → `requestScan` y `completedAt`; ya resuelta → estado sin encolar), `GetAttachmentUploadServiceTest` (por tenant; otro tenant → no encontrada) y `ScanAttachmentUploadServiceTest` (estado distinto de `PENDING_SCAN` → sin efecto; `CLEAN` → `copyIfMatch` al `cleanKey`, transición y borrado del `uploadKey`, en ese orden; `INFECTED` → borrado y transición con motivo y firma; precondición del ETag fallida → error para reintentar, sin transición: SC-013; antivirus caído → error para reintentar)
- [ ] T056 [US6] Crear `core-main/port/in/IssueAttachmentUploadUseCase.java`, `CompleteAttachmentUploadUseCase.java`, `GetAttachmentUploadUseCase.java`, `ScanAttachmentUploadUseCase.java` y los servicios `core-main/usecase/IssueAttachmentUploadService.java`, `CompleteAttachmentUploadService.java`, `GetAttachmentUploadService.java` y `ScanAttachmentUploadService.java` (research.md, Decisión 13) — depende de T021, T023, T038, T055
- [ ] T057 [US6] Declarar la cola `notification.attachment.scan` y su DLQ en `infra-main/config/RabbitConfig.java` y `RabbitTopologyProperties.java`, y crear `infra-main/adapter/out/rabbit/AttachmentScanRequestRabbitPublisher.java` (implementa `AttachmentScanRequestPort`; mensaje `{tenantId, uploadId}`) — depende de T021
- [ ] T058 [P] [US6] Pruebas en `infra-test/adapter/in/rabbit/AttachmentScanListenerTest.java` (RabbitMQ con Testcontainers, sin simular el broker): el mensaje se produce con `AttachmentScanRequestPort.requestScan` real; el listener invoca `ScanAttachmentUploadUseCase` con el tenant y la subida; ack solo después de que el caso de uso termina; un segundo mensaje para una subida ya resuelta se confirma sin efecto; un error persistente termina en la DLQ con su causa, tras los reintentos de `RabbitRetryConfig` — depende de T057
- [ ] T059 [US6] Crear `infra-main/adapter/in/rabbit/AttachmentScanListener.java` (ack manual, concurrencia `scan.consumer-concurrency`, registro del veredicto con `tenantId`, `uploadId`, `sha256`, estado, motivo y firma) — depende de T056, T057, T058
- [ ] T060 [P] [US6] Pruebas en `infra-test/adapter/in/rest/AttachmentUploadControllerTest.java` (`@WebFluxTest`): `POST /attachment-uploads` → `201` con `Location`, `uploadId`, `uploadUrl` y `expiresAt`; `:complete` → `202` con el estado; `GET` → `200` sin `uploadUrl`; `404`, `409`, `400` y `503` según la excepción; `AttachmentUploadResponse.toString()` sin `uploadUrl`; registros de emisión y aviso sin la dirección
- [ ] T061 [US6] Crear `infra-main/adapter/in/rest/AttachmentUploadController.java`, `IssueAttachmentUploadRequest.java` y `AttachmentUploadResponse.java`, y cablear los cuatro servicios en `infra-main/config/UseCaseConfig.java` — depende de T049, T053, T056, T060
- [ ] T062 [US6] Crear `infra-test/adapter/in/rest/AttachmentUploadE2ETest.java` (como T044 más el MinIO de T004 con su endpoint público configurado): emitir → PUT real a `uploadUrl` → `:complete` → sondear `GET` hasta `CLEAN` y afirmar ≤ `Duration.ofSeconds(30)` para 10 MB (SC-011) → notificación con esa `url` → `202` → `DELIVERED`, con el emisor recibiendo la misma huella y el `StoredObject` (SC-001); notificación con una subida emitida y no completada → `409` (SC-009); archivo de prueba antivirus → `INFECTED` → notificación → `400` (SC-008), usando un DOCX mínimo generado con `java.util.zip` que contiene una entrada EICAR y una entrada de relleno para superar 1 MB (si ClamAV no lo detecta, anotar la desviación aquí y cambiar de estrategia); `url` https ajena → `400` sin intento de descarga (SC-009); `:complete` sin objeto → `409` y con tamaño distinto → `400` y la subida sigue `PENDING_SCAN` (US6.7); `uploadUrl` ausente de registros, respuestas de error, `GET /attachment-uploads/{id}`, eventos y consultas de notificación, con nombre, tipo, tamaño y huella en los registros (SC-003, control positivo) — depende de T059, T061

**Checkpoint**: camino grande de punta a punta.

---

## Phase 7: User Story 7 - Ningún tenant ve ni usa los archivos de otro (P1)

**Goal**: aislamiento total entre tenants en subidas, adjuntos, caché de veredictos, errores y registros.

**Independent Test**: spec.md, User Story 7, escenarios 1–4; SC-010.

- [ ] T063 [US7] Casos de dos tenants en `AttachmentUploadE2ETest` (SC-010): A deja una subida `CLEAN`; B hace `GET` y `:complete` → `404` idénticos a los de una subida inexistente (se comparan los cuerpos completos); B referencia la `url` de A → `400` idéntico al de una dirección no emitida; ninguna respuesta, registro capturado ni documento de B contiene el `uploadId`, la clave, la huella ni el nombre de A; B sube el mismo archivo y su análisis no reutiliza el veredicto de A (dos entradas en `attachment_scan_verdicts`); A sí puede usar su subida (control positivo) — depende de T062
- [ ] T064 [US7] Revisión del aislamiento contra la tabla de plan.md § Aislamiento por tenant: para cada punto, comprobar en el código qué se hace con el resultado del predicado (no solo el predicado), como pide el antecedente de HU2-072, y registrar aquí cualquier corrección hecha — depende de T063

---

## Phase 8: User Story 3 - Cada canal declara qué adjuntos admite (P2)

**Goal**: las reglas por canal viven en `contentSchema`, se aplican tras el refresco del catálogo y se ven
en `GET /channels`; por defecto ningún canal acepta adjuntos.

**Independent Test**: spec.md, User Story 3, escenarios 1–4; SC-005.

- [ ] T065 [US3] Comprobar que ningún cambio de código es necesario más allá de T020: `GET /channels` ya expone `contentSchema` tal cual y `application.yml` no declara `attachments` en ningún canal; si la comprobación encuentra algo distinto, registrarlo aquí como desviación (reabre v1 T025)
- [ ] T066 [US3] Casos en `NotificationAttachmentE2ETest`: US3.1 (`GET /channels` muestra la declaración), US3.3 (un canal con `maximum` sobre el tope global rechaza un adjunto entre ambos), US3.4 (EMAIL por defecto rechaza) y SC-005 (bajar `maximum` en el catálogo y afirmar que el rechazo empieza antes de `Duration.ofSeconds(5)`) — depende de T044, T065

---

## Phase 9: User Story 5 - Nunca entregar una notificación sin su adjunto (P2)

**Goal**: con adjuntos y un proveedor sin soporte, `FAILED` sin llamar al proveedor ni reintentar.

**Independent Test**: spec.md, User Story 5, escenarios 1–2; SC-006.

- [ ] T067 [P] [US5] Pruebas en `core-test/usecase/DispatchNotificationServiceTest.java`: notificación con adjuntos y emisor con `supportsAttachments() == false` → `send` nunca invocado, estado `FAILED`, intento `PERMANENT_FAILURE` a nombre del proveedor, guardado y evento `NotificationFailed` publicado; mismo emisor sin adjuntos → `send` invocado (control positivo); emisor con soporte y adjuntos → `send` invocado (sin cambio respecto de v1 T026)
- [ ] T068 [US5] Implementar la regla en `sendThrough` de `core-main/usecase/DispatchNotificationService.java` (research.md, Decisión 6) — depende de T067
- [ ] T069 [US5] SC-006 en `NotificationAttachmentE2ETest`: canal con `recording-plain`, con adjuntos → `FAILED` y el emisor no la recibe; sin adjuntos → `DELIVERED` y sí la recibe — depende de T044, T068

---

## Phase 10: Polish & Cross-Cutting Concerns

- [ ] T070 Indicadores de salud de MinIO y ClamAV en `/actuator/health`, fuera del grupo de *readiness* (research.md, Decisión 15), en `infra-main/config/` y `application.yml`, con una prueba en `infra-test/config/AttachmentHealthIndicatorsTest.java`: con MinIO y ClamAV caídos, `/actuator/health/readiness` sigue `UP` y `/actuator/health` los informa `DOWN`
- [ ] T071 Actualizar `README.md` o la documentación de arranque local si enumera los servicios de `docker compose`: añadir `minio` y `clamav` y sus variables de entorno (sin secretos)
- [ ] T072 Revisión de datos sensibles: buscar en el código nuevo cualquier `toString`, mensaje de excepción o registro que pueda incluir `content`, bytes, `url` o `uploadUrl`, y corregirlo; SpotBugs (`EI_EXPOSE_REP` en `byte[]`, inyección en registros) se corrige en el código, nunca con exclusiones sin reportar
- [ ] T073 Revisión de bloqueo: ninguna llamada bloqueante (`.block()`, E/S síncrona de MinIO, ClamAV o Tika) en un hilo de evento
- [ ] T074 Regresión: `ProviderRoutingE2ETest`, `ChannelCatalogQueryE2ETest`, `ChannelCatalogE2ETest`, E2E de proveedores, `NotificationControllerSearchE2ETest`, `NotificationLiveUpdates*E2ETest`, pruebas de los cuatro proveedores (revalida T006), `HexagonalArchitectureTest` y `ModularityTests` en verde
- [ ] T075 `./mvnw -B -ntp spotless:apply` y `./mvnw -B -ntp verify` completo y en verde (cobertura ≥ 80 % líneas / ≥ 70 % ramas); si solo fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y dejarlo escrito aquí

---

## Dependencies & Execution Order

- **Phase 1**: T001 antes que cualquier cambio de controlador (Principio II). T002, T003 en paralelo;
  T004 depende de T003; T005 después de T003.
- **Phase 2** bloquea todo:
  - 2B: T008 → T009; T010 → T011; (T011, T012) → T013 → T014; T015 → T016 (con T009); T017 → T018;
    T019 → T020.
  - 2C: T021 tras T011 y T018; T022 → T023; T024 → T025; T026 tras T009; T027 → T028.
  - 2D: cada prueba antes de su adaptador (T029 → T030, T031 → T032, T033 → T034, T035 → T036,
    T037 → T038, T039 → T040, T041 → T042); T043 al final de la fase.
  - Entre T013 y T043 el módulo `infrastructure` puede no compilar: no se commitea en ese intervalo sin
    que `clean compile` esté en verde.
- **Historias**: US1 (T044–T045) tras Phase 2. US2 tras T044 (para su E2E). US4 tras T042 y T044. US6
  tras Phase 2, T049 y T053. US7 tras T062. US3 tras T020 y T044. US5 tras T044.
- **Phase 10** al final; T075 es lo último.

## Parallel Opportunities

- Phase 1: T002 y T003.
- Phase 2: las pruebas T008, T010, T012, T015, T017, T019 en paralelo; T022 y T024; las pruebas de
  adaptador T029, T031, T033, T035, T037, T039 y T041 en paralelo (archivos distintos).
- US2: T046 y T048 en paralelo. US6: T055, T058 (tras T057) y T060 en paralelo. US5: T067 en paralelo con
  cualquier tarea de US6 o US7.

## Implementation Strategy

MVP = Phase 1 + Phase 2 + US1: aceptar y despachar adjuntos chicos embebidos y verificados. Después US2 y
US4 (rechazos y no exposición del camino chico), US6 y US7 (camino grande y aislamiento), US3 y US5 (P2),
y el cierre. Un commit local por fase o por grupo coherente de tareas, siempre con `clean compile` en
verde.

## Correspondencia con la lista v1

| v1 | Estado en la v1 | v3 |
|---|---|---|
| T001 contrato | hecho (`8c45a4d`) | reabierto → T001 |
| T002–T003 `supportsAttachments()` | hecho (`0bd17b0`) | conservado → T006 `[X]` |
| T004–T005 `Attachment` | hecho (`0bd17b0`) | reabierto → T012, T013 (+ T008, T009 `AttachmentSubmission`) |
| T006–T007 `NotificationContent` | hecho (`0bd17b0`) | forma conservada; pruebas reabiertas → T014 |
| T008–T009 `AttachmentPolicy` + excepción | hecho (`0bd17b0`) | excepción conservada → T007 `[X]`; política reabierta → T015, T016 |
| T010–T011 `ContentSchemaValidator` | hecho (`0bd17b0`) | reabierto (entrada) → T019, T020 |
| T012–T013, T018 `SendNotificationService` | hecho (`dfb462d`) | reabierto → T027, T028 |
| T014–T015 MongoDB | hecho (`dfb462d`) | reabierto → T039, T040 |
| T016–T017 REST | hecho (`dfb462d`) | reabierto → T041, T042 |
| T019–T020 lote | sin commitear | reabierto → T046, T047 |
| T021–T022 manejador `400` | sin commitear | reabierto → T048, T049 |
| T023–T024 registros | pendiente | → T052, T053 |
| T025 catálogo por defecto | pendiente | → T065 |
| T026 despacho | pendiente | → T067, T068 |
| T027 E2E | pendiente | → T044, T045, T050, T051, T054, T062, T063, T066, T069 |
| T028 regresión | pendiente | → T074 |
| T029 `verify` | pendiente | → T075 |
