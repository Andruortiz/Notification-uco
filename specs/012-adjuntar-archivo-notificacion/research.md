# Research: Adjuntar un archivo a una notificación

Cada decisión indica qué se eligió, por qué y qué se descartó. Q2–Q5 de `spec.md § Clarifications` están
confirmadas desde el 2026-09-28; Q1 se reabrió el 2026-09-29 y su respuesta vigente es el modelo híbrido
por tamaño (plan v3, aceptado). Las Decisiones 1, 3, 5, 9 y 11 se reescribieron para la v3; las
Decisiones 12 a 15 son nuevas.

## Decisión 1 — Cómo viaja el archivo (Q1, reabierta el 2026-09-29)

- **Decisión**: modelo híbrido por tamaño, con umbral de 1 MB (1 048 576 bytes).
  - **Hasta 1 MB**: `content` en Base64 dentro de la solicitud. Se decodifica, se verifica (Decisión 12)
    de forma síncrona y se guarda como `BinData` con la notificación, en la misma escritura.
  - **Más de 1 MB, hasta 10 MB**: `url` de una subida emitida por el servicio a su propio MinIO
    (Decisión 13). La notificación solo se acepta si esa subida es del mismo tenant y está `CLEAN`.
  - Exactamente uno de `content` o `url` por adjunto. El umbral separa los caminos en los dos sentidos.
- **Por qué**:
  - La v1 (referencia https alojada por el cliente) validaba tamaño y tipo **declarados** y no podía
    analizar el archivo. El modelo híbrido verifica el archivo real en ambos caminos.
  - Embeber todo (la v2 hipotética) obligaría a aceptar cuerpos de ~70 MB en memoria (5 × 10 MB × 4/3) y
    a guardar hasta 50 MB por notificación, por encima del límite de 16 MB de un documento de MongoDB. Con
    el umbral, el cuerpo queda en ≤ 8 MB y el `BinData` embebido en ≤ 5 MB por notificación.
  - La subida prefirmada lleva los archivos grandes directo al almacén, sin pasar por el servicio ni por
    su memoria durante la solicitud de notificación.
- **Alternativas descartadas**:
  - **Referencia https arbitraria (v1)**: tipo y tamaño no verificables; el servicio no puede analizarlo
    sin volverse cliente HTTP de direcciones arbitrarias (SSRF).
  - **Todo embebido (v2)**: memoria y límite de documento, ver arriba.
  - **GridFS en lugar de MinIO para los grandes**: evita un servicio nuevo, pero obliga a que el archivo
    pase por el servicio (sin subida directa) y no ofrece direcciones prefirmadas; lo descartó el usuario
    al elegir MinIO.

## Decisión 2 — Dónde declara cada canal sus reglas de adjuntos (Q3, confirmada)

- **Decisión**: dentro de la forma de contenido del canal (`contentSchema`, JSON Schema
  2020-12), la misma que hoy fija `maxLength` del cuerpo. El canal declara una propiedad `attachments`
  de tipo arreglo; `maxItems` fija la cantidad, `items.properties.contentType.enum` los tipos y
  `items.properties.sizeBytes.maximum` el tamaño.
- **Regla por defecto cerrada**: un canal acepta adjuntos **solo** si su esquema tiene
  `properties.attachments` en la raíz. Si el esquema está vacío (hoy EMAIL) o no declara esa propiedad
  (hoy SMS y PUSH), una notificación con adjuntos se rechaza con `InvalidContentException` "channel X does
  not accept attachments". Se comprueba leyendo el árbol del esquema, no la validación: JSON Schema, por
  diseño, acepta propiedades no declaradas.
- **Por qué**: es el patrón que la historia pide seguir; `GET /channels` ya expone `contentSchema`, así
  que FR-011 se cumple sin cambiar el contrato de la consulta; la validación reutiliza
  `ContentSchemaValidator` y el refresco del catálogo existente (SC-005) sin código nuevo de caché.
- **Alternativas descartadas**: una sección propia `attachmentPolicy` en el documento del catálogo, en
  `ChannelRoute` y en `ChannelItem`. Más tipada, pero duplica el mecanismo de reglas por canal, cambia el
  contrato de la consulta del catálogo, la siembra y el refresco, y deja dos lugares donde mirar las
  reglas de un canal.
- **Configuración por defecto**: ningún canal declara `attachments` (Q3). `application.yml` cambia solo
  por el límite del cuerpo y la configuración no sensible de adjuntos (Decisión 13), no por el catálogo.

## Decisión 3 — Reglas globales y en qué momento se aplican (reescrita para la v3)

- **Decisión**: `AttachmentPolicy` (`core/domain/policy`), política pura sin E/S, con los topes como
  constantes. Orden de evaluación; se reporta la primera regla incumplida del primer adjunto inválido:
  1. a lo sumo **5** adjuntos (sin posición);
  2. `fileName` presente, de 1 a 255 caracteres, sin `/`, `\`, caracteres de control, distinto de `.` y
     `..`;
  3. **extensión prohibida**: `fileName` en minúsculas, sin puntos ni espacios finales, no termina en
     ninguna de `.exe .msi .bat .cmd .com .scr .pif .vbs .vbe .js .jse .wsf .wsh .ps1 .hta .cpl .msc .reg
     .lnk .jar .dll .sh .apk .app .gadget .com.pif .msix .war`;
  4. `contentType` presente y, ya normalizado, en la **lista blanca v3**: `application/pdf`, `image/png`,
     `image/jpeg`, `text/plain`, `text/csv`,
     `application/vnd.openxmlformats-officedocument.wordprocessingml.document`,
     `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (salen `image/gif` e
     `image/webp`; PPTX nunca estuvo en la constante y queda excluido de forma explícita);
  5. `sizeBytes` presente, entre 1 y **10 485 760**, inclusivo;
  6. exactamente uno de `content` o `url`; `content` en Base64 estándar válido (sin prefijo `data:` ni
     saltos de línea), con tamaño decodificado ≤ **1 048 576** e igual a `sizeBytes`; `url` solo si
     `sizeBytes` > 1 048 576, de hasta 2048 caracteres;
  7. suma de `sizeBytes` ≤ **26 214 400** (sin posición).
- **Lo que no está en la política**: que la `url` sea una subida emitida por el servicio para ese tenant
  y esté `CLEAN` necesita E/S; lo comprueba `AttachmentResolver` (Decisión 13). La regla de la v1 "https
  sin credenciales" desaparece: una `url` que no sea del servicio se rechaza igual.
- **Momento**: en la aceptación (`SendNotificationService`) y, para las reglas 2–5, en la emisión de una
  subida. **No** en el constructor de `Attachment` ni al reconstituir desde MongoDB, por el mismo motivo
  que en la v1: si un tope baja mañana, lo ya aceptado debe seguir cargándose.
- **Error**: `InvalidAttachmentException(position, rule[, fileName])`, sin cambio: mensaje
  `attachments[<i>]: <regla>` y `(<fileName>)` cuando el nombre es válido. Nunca incluye el contenido ni
  la dirección.
- **Decodificar el Base64 en `core`** (JDK, `java.util.Base64`) y no en el controlador: así el lote aplica
  la misma regla con la misma posición.
- **Alternativa descartada**: comparar la extensión por su último segmento solamente. No cubre `.com.pif`,
  que la lista incluye como sufijo compuesto.

## Decisión 4 — Normalización del tipo de contenido

- **Decisión**: el tipo se guarda en minúsculas, sin parámetros (`; charset=...`) y sin espacios. Tanto la
  lista global como el `enum` del canal se comparan contra ese valor; los esquemas del catálogo deben
  escribir los tipos en minúsculas (documentado en el contrato).
- **Alternativa descartada**: comodines (`image/*`). No hacen falta para los canales previstos y
  complican la regla global.

## Decisión 5 — Datos sensibles: contenido y dirección de subida (reescrita para la v3)

- **Decisión**:
  - El nodo que `ContentSchemaValidator` valida contiene `fileName`, `contentType` y `sizeBytes` de cada
    adjunto, **nunca** `content` ni `url`.
  - `toString()` redefinido sin `content` ni `url` en `AttachmentSubmission`, `Attachment`,
    `AttachmentSource.EmbeddedContent`, `AttachmentRequest` y `AttachmentUploadResponse`.
  - La dirección prefirmada solo sale del servicio en la respuesta de `POST /attachment-uploads`. No se
    guarda (se guarda la clave del objeto) y no se registra. La `url` que el cliente envía en la
    notificación se reduce a su `uploadId`; la parte de consulta (la firma) se descarta.
  - Los registros (Decisión 8) escriben nombre, tipo, tamaño y huella. La huella SHA-256 no es sensible:
    identifica un archivo sin revelarlo, y es lo que permite auditarlo.
  - Los eventos de dominio y el mensaje de despacho no cambian (solo identificadores). El mensaje de
    escaneo lleva `{ tenantId, uploadId }`.
  - Las respuestas de estado, búsqueda y actualizaciones en vivo no incluyen adjuntos; además, la búsqueda
    y el reencolado leen con una proyección que excluye `attachments.content`.
- **Dónde sí está el contenido**: en el documento de MongoDB de la notificación (camino chico), en el
  objeto limpio de MinIO (camino grande) y en el objeto que recibe el adaptador de proveedor.

## Decisión 6 — Proveedor que no sabe enviar adjuntos (Q4, confirmada)

- **Decisión**: `NotificationSenderPort` gana `boolean supportsAttachments()` **abstracto**.
  `SimulatedNotificationProvider` devuelve `true`; Brevo, Twilio y FCM devuelven `false` hasta sus
  historias. En `DispatchNotificationService.sendThrough`, si la notificación tiene adjuntos y el
  proveedor no los soporta, el resultado es `AttemptResult.PERMANENT_FAILURE` **sin llamar al
  proveedor**, y el flujo existente aplica `markFailed` (fallo definitivo, sin reintentos) y guarda el
  intento a nombre del proveedor.
- **Por qué abstracto y no `default false`**: mismo criterio que `disabledReason()` en la historia del
  catálogo: cada adaptador declara explícitamente su capacidad y un adaptador nuevo no compila hasta
  hacerlo. `default false` sería seguro (falla cerrado), pero ocultaría que el adaptador nunca pensó en
  adjuntos.
- **Por qué en el núcleo y no en cada adaptador**: una sola regla, falla cerrada, y probada una vez; si
  cada adaptador la reimplementara, uno que la olvide entregaría sin adjunto en silencio.
- **Motivo textual**: `DeliveryAttempt` no tiene campo de causa y `core` no registra. El historial guarda
  proveedor y fallo definitivo; el motivo se deduce de datos visibles. Queda como riesgo con dueño y fecha
  en el spec.
- **Rechazar ya en la aceptación si el proveedor preferente no sabe enviar adjuntos**: descartado como
  única barrera, porque el catálogo puede cambiar entre aceptación y despacho; y como barrera adicional,
  porque acopla la aceptación al registro de proveedores sin ganar garantía.

## Decisión 7 — Orden de validación e idempotencia

- **Decisión**: ruta del canal → `AttachmentPolicy` (barata, sin E/S) → `ContentSchemaValidator` →
  `AttachmentResolver` (decodifica e inspecciona los embebidos; resuelve las subidas) → búsqueda de
  duplicada → aceptación. Una duplicada con adjuntos inválidos se rechaza, igual que hoy una duplicada
  con contenido inválido (FR-012). Un reintento de una duplicada vuelve a inspeccionar, pero la caché por
  huella (Decisión 12) evita volver a llamar al antivirus.
- **Alternativa descartada**: buscar la duplicada antes de validar. Cambiaría el comportamiento existente
  sin adjuntos, fuera del alcance.

## Decisión 8 — Registros (FR-010)

- **Decisión**: estilo `clave=valor` que ya usan los adaptadores de proveedor, escrito en infraestructura:
  - `NotificationController`, solo cuando la solicitud trae adjuntos: aceptada (`tenantId`, `externalId`,
    `notificationId`, `duplicate`, `attachments=[fileName|contentType|sizeBytes|sha256, …]`) y rechazada
    (`tenantId`, `externalId`, metadatos, `reason`);
  - `AttachmentUploadController`: emisión y `:complete` (`tenantId`, `uploadId`, metadatos, estado);
  - `AttachmentScanListener`: veredicto (`tenantId`, `uploadId`, `sha256`, `state`, `reason` y firma
    si es `INFECTED`).
  El formateo vive en `AttachmentLogFormatter`, que reemplaza caracteres de control del nombre.
- **Brecha que no se cierra**: el formato estructurado (JSON) del RNF-10 sigue pendiente para todo el
  servicio (spec, Risks).

## Decisión 9 — Persistencia (reescrita para la v3)

- **`notifications`**: `NotificationDocument.attachments` es una lista de `AttachmentDocument`
  { `tenantId`, `fileName`, `contentType`, `sizeBytes`, `sha256`, `storage` (`EMBEDDED` | `OBJECT`),
  `content` (`BinData`, solo `EMBEDDED`), `uploadId` y `objectKey` (solo `OBJECT`) }, en el orden
  recibido. Sin `url`. Un documento sin el campo se lee como lista vacía. Tope: 5 MB de `BinData` por
  documento.
- **`attachment_uploads`** (nueva): `AttachmentUploadDocument` { `_id` = `uploadId`, `tenantId`,
  `fileName`, `contentType`, `sizeBytes`, `uploadKey`, `cleanKey`, `state`, `rejectionReason`,
  `signature`, `sha256`, `issuedAt`, `expiresAt`, `completedAt`, `scannedAt`, `version` }. Índice
  `(tenantId, _id)`. Las transiciones son `findAndModify` con la condición `state = PENDING_SCAN`.
- **`attachment_scan_verdicts`** (nueva): { `tenantId`, `sha256`, `verdict`, `signature`,
  `signatureVersion`, `scannedAt`, `expiresAt` }. Índice único `(tenantId, sha256)`; índice TTL sobre
  `expiresAt`, que solo tienen los `CLEAN` (24 h).
- **Sin migración**: la v1 nunca llegó a `develop`; no hay subdocumentos con `url` que conservar.
- **Por qué embebido en la notificación y no en una colección aparte**: una sola escritura atómica
  (Principio VIII) sin transacciones multi-documento, que el MongoDB local (no réplica) no ofrece.

## Decisión 10 — Lote (Q5, confirmada)

- **Decisión**: `SendNotificationBatchService` convierte en `rejected` con su motivo
  `ChannelNotAvailableException`, `InvalidContentException`, `InvalidAttachmentException` y
  `AttachmentNotReadyException`. `AttachmentInspectionUnavailableException` es de infraestructura y se
  propaga como cualquier otro error de infraestructura del lote.
- **Hallazgo (sin cambio)**: `/notifications:sendBatch` está en el contrato y el caso de uso existe, pero
  **ningún controlador lo atiende**. Esta historia no lo expone; SC-007 se prueba en el caso de uso.

## Decisión 11 — Pruebas (reescrita para la v3)

- **Unitarias `core`** (JUnit 5 + Mockito + `StepVerifier`): `AttachmentPolicyTest` (cada regla y cada
  límite inclusivo, incluidos 1 048 576 / 1 048 577 y 26 214 400 / 26 214 401, cada extensión prohibida,
  mayúsculas y punto final), `AttachmentSubmissionTest`, `AttachmentTest`, `Sha256DigestTest`,
  `AttachmentUploadTest` (solo desde `PENDING_SCAN`), `AttachmentInspectorTest` (orden y cortocircuitos
  de la caché), `AttachmentResolverTest` (dos tenants), los servicios de subida,
  `SendNotificationServiceTest`, `SendNotificationBatchServiceTest` (SC-007),
  `DispatchNotificationServiceTest` (SC-006), `ContentSchemaValidatorTest`, `NotificationContentTest`.
- **Integración de adaptadores** (Testcontainers, sin simular el servicio real): MinIO (firma, PUT real,
  `stat`, copia con ETag obsoleto que falla: SC-013), ClamAV (EICAR, archivo limpio, versión de firmas,
  puerto cerrado → no disponible: SC-014), Tika (un archivo real por tipo de la lista blanca, ejecutable
  renombrado, HTML declarado como texto), MongoDB (`BinData`, proyección, transición concurrente con un
  solo ganador, caché por tenant).
- **Consumidor de escaneo**: mensaje producido con el publicador real, nunca con JSON armado a mano;
  ack después de persistir; reenvío de un mensaje ya resuelto sin efecto; DLQ con el antivirus caído.
- **E2E** (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` + `WebTestClient`, refresco del catálogo a
  1 s, emisores de prueba `recording-attachments` y `recording-plain` como beans):
  - `NotificationAttachmentE2ETest` (MongoDB, RabbitMQ, ClamAV): camino chico; SC-001 (embebido),
    SC-002, SC-003, SC-005 con `Duration`, SC-006, SC-008 (embebido), SC-012 con `Duration`, US3.1, US3.4
    y duplicada.
  - `AttachmentUploadE2ETest` (lo anterior más MinIO): camino grande; SC-001 (subida), SC-003 (dirección
    de subida), SC-008 (subida), SC-009, SC-010 con dos tenants y control positivo, SC-011 con
    `Duration`.
  - `NotificationAttachmentScannerDownE2ETest` (MongoDB, RabbitMQ, ClamAV inalcanzable): SC-014 con
    control positivo sin adjuntos.
  - Las pruebas de "no aparece" llevan su control positivo; las de tiempo afirman con `Duration`.
- **Arquitectura**: `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Decisión 12 — Verificación de contenido: Tika, ClamAV y huella (nueva)

- **Decisión**: `AttachmentInspector` en `core`, común a ambos caminos, sobre bytes en memoria (≤ 1 MB en
  la aceptación; ≤ 10 MB en el consumidor, con concurrencia acotada):
  1. `Sha256Digest.of(bytes)` con `MessageDigest` (JDK).
  2. `ContentTypeDetectorPort.detect(bytes, fileName)` (Tika, en infraestructura). El tipo detectado debe
     estar en la lista blanca y coincidir con el declarado; única equivalencia admitida: `text/csv`
     declarado y `text/plain` detectado.
  3. `ScanVerdictCachePort.find(tenantId, sha256)`: `INFECTED` → rechazo sin reescanear; `CLEAN` de la
     misma versión de firmas y ≤ 24 h → se omite el antivirus.
  4. `MalwareScannerPort.scan(bytes)` (ClamAV `INSTREAM`); el veredicto se guarda en la caché.
- **Tika detrás de un puerto, no en `core`**: `HexagonalArchitectureTest` solo prohíbe Spring, pero el
  Principio I prohíbe cualquier framework en `core`. Solo `tika-core` más el detector de contenedores
  OOXML, sin los analizadores completos; se ejecuta en `boundedElastic`.
- **ClamAV sin librería cliente**: el protocolo `INSTREAM` es simple (bloques con longitud y un bloque
  vacío final); el adaptador lo habla con Reactor Netty `TcpClient`, con tiempo máximo y concurrencia
  acotada. `VERSION` da la versión de firmas, cacheada unos minutos.
- **Caché por `(tenantId, sha256)` y no global**: una caché global permitiría a un tenant averiguar, por
  la latencia o por la respuesta, si otro tenant subió un archivo concreto (antecedente de HU2-072). Se
  pierde la deduplicación entre tenants, que no es un objetivo.
- **Alternativas descartadas**: comprobar la firma de los primeros bytes a mano (la reemplaza Tika, por
  decisión del usuario); invalidar la caché solo por TTL (una firma nueva publicada hoy no revisaría lo
  cacheado ayer).

## Decisión 13 — Subidas a MinIO y máquina de estados (nueva)

- **Emisión** (`POST /attachment-uploads`): valida las reglas 2–5 de la Decisión 3 más `sizeBytes`
  > 1 048 576; crea `AttachmentUpload` en `PENDING_SCAN` con `uploadKey =
  tenants/{tenantId}/uploads/{uploadId}`; devuelve un PUT prefirmado contra el endpoint **público** de
  MinIO (configurable, distinto del interno), vigente 15 minutos por defecto.
- **Cierre** (`POST /attachment-uploads/{uploadId}:complete`): `stat` del objeto; ausente → `409`;
  tamaño distinto → `400`, se borra el objeto y la subida sigue en `PENDING_SCAN`; si no, publica
  `{ tenantId, uploadId }` en la cola de escaneo y responde `202`. Si la subida ya está resuelta, devuelve
  su estado sin encolar.
- **Escaneo** (`AttachmentScanListener`, ack manual, reintentos de `RabbitRetryConfig`, DLQ propia):
  `stat` → ETag; lectura condicionada al ETag; `AttachmentInspector`; `CLEAN` → copia condicionada al
  ETag a `cleanKey = tenants/{tenantId}/clean/{uploadId}`, transición y borrado de `uploadKey`;
  `INFECTED` → borrado del objeto y transición con motivo (`MALWARE` con la firma, o
  `CONTENT_TYPE_MISMATCH`). Ack solo después de persistir.
- **Por qué la copia condicionada**: el PUT prefirmado sigue vigente después del escaneo; sin la copia a
  una clave nunca prefirmada, alguien con la dirección podría reemplazar un objeto ya limpio (FR-025).
- **Por qué `INFECTED` también para el tipo que no coincide**: el usuario fijó tres estados; un motivo
  explícito evita que la auditoría confunda un tipo incorrecto con un virus.
- **Uso en la notificación** (`AttachmentResolver`): la `url` debe tener esquema, servidor y puerto del
  endpoint público y la ruta `/{bucket}/tenants/{tenantId}/uploads/{uploadId}`; el `tenantId` de la ruta
  debe ser el de la solicitud; la subida se busca por `(tenantId, uploadId)`; nombre, tipo y tamaño deben
  coincidir; `PENDING_SCAN` → `409`, `INFECTED` → `400`, `CLEAN` → `StoredObject(uploadId, cleanKey)`.
- **Cliente**: `MinioAsyncClient` (`CompletableFuture` → `Mono.fromFuture`); la firma de la dirección es
  un cálculo local.
- **Alternativas descartadas**:
  - Notificaciones de bucket de MinIO a RabbitMQ en lugar de `:complete`: acopla la configuración de
    MinIO a la topología del broker, es más difícil de probar y el evento no trae un tenant validado.
  - Escaneo síncrono en `:complete`: haría innecesaria la máquina de estados que el usuario pidió y
    retendría hasta 10 MB y la latencia del antivirus en una solicitud HTTP.
  - POST prefirmado con política de tamaño: el SDK lo permite, pero el tamaño se comprueba igual en
    `:complete` y en el escaneo, y el PUT simplifica el cliente.

## Decisión 14 — Aislamiento por tenant (nueva)

- **Decisión**: `tenantId` propio en `AttachmentUpload`, en cada `AttachmentDocument` y en la caché de
  veredictos. Toda búsqueda de subidas es por `(tenantId, uploadId)`; las claves de MinIO se arman desde el
  registro del tenant, nunca desde la `url` del cliente; "no existe" y "es de otro tenant" dan la misma
  respuesta (`404` en la API de subidas, el mismo `400` en la notificación). Al reconstituir una
  notificación, un adjunto con `tenantId` distinto al de la notificación es un error de integridad.
- **Por qué tanto**: en HU2-072 un filtro de tenant correcto no evitó emitir un `REMOVE` con los datos
  completos de otro tenant. Aquí se prueba qué hace el código con el resultado del predicado, no solo el
  predicado, en E2E con dos tenants y control positivo.

## Decisión 15 — Disponibilidad del antivirus y del almacén (nueva)

- **Decisión**: si ClamAV o MinIO fallan o vencen su tiempo, `AttachmentInspectionUnavailableException`
  → `503` en la aceptación y en las operaciones de subida; nack y reintento en el consumidor. Nunca se
  acepta un archivo sin analizar.
- **Salud**: indicadores de MinIO y ClamAV en `/actuator/health`, **fuera** del grupo de *readiness*: su
  caída solo afecta a notificaciones con adjuntos y no debe sacar de servicio las que no los llevan
  (propuesta del plan v3, aceptada con él).
- **Límite del cuerpo**: `spring.codec.max-in-memory-size` = 8 MB; excederlo responde `413`.
