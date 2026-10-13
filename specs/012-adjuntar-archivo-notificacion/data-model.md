# Data Model: Adjuntar un archivo a una notificación

Modelo de la versión 3 del plan (Q1 híbrida por tamaño). Las reglas y sus porqués están en research.md;
aquí, la forma de cada tipo y documento.

## `core` — dominio

### `AttachmentSubmission` (nuevo, `core/domain/valueobject`)

Adjunto tal como llega en el comando, antes de verificarse.

Record `AttachmentSubmission(String fileName, String contentType, Long sizeBytes, String content, String url)`.

- El constructor solo normaliza `contentType` (minúsculas, sin parámetros, sin espacios; nulo se conserva).
- `content` es el texto Base64 sin decodificar; `url`, la dirección que envió el cliente.
- `boolean isEmbedded()` (`content != null`), `boolean isReference()` (`url != null`).
- `toString()` sin `content` ni `url`.
- Sin invariantes más allá de la normalización: las reglas las aplica `AttachmentPolicy` con posición.

### `Attachment` (rediseñado)

Adjunto verificado, parte del agregado `Notification`.

Record `Attachment(TenantId tenantId, String fileName, String contentType, long sizeBytes, Sha256Digest sha256, AttachmentSource source)`.

- `tenantId`, `sha256` y `source` no nulos. Sin validación de topes (reconstitución tolerante).
- `toString()` con nombre, tipo, tamaño y huella; sin contenido ni clave de objeto.

### `AttachmentSource` (nuevo, interfaz sellada)

| Variante | Campos | Notas |
|---|---|---|
| `EmbeddedContent` | `byte[] bytes` | Copia defensiva al construir y al leer; `toString` sin bytes. |
| `StoredObject` | `UploadId uploadId`, `String objectKey` | `objectKey` = `cleanKey` de la subida. |

### `Sha256Digest` (nuevo)

Record `Sha256Digest(String hex)`: 64 caracteres hexadecimales en minúsculas. `static of(byte[])` con
`MessageDigest.getInstance("SHA-256")`.

### `UploadId` (nuevo)

Record `UploadId(String value)`, no vacío; generado como UUID aleatorio.

### `ScanState` (nuevo)

`PENDING_SCAN`, `CLEAN`, `INFECTED`.

### `ScanVerdict` (nuevo)

Record `ScanVerdict(Outcome outcome, String signature, String signatureVersion)`, con `Outcome` = `CLEAN`
| `INFECTED`; `signature` solo en `INFECTED`.

### `AttachmentRejectionReason` (nuevo)

`MALWARE`, `CONTENT_TYPE_MISMATCH`.

### `AttachmentUpload` (nuevo, `core/domain`)

Agregado de la subida.

| Campo | Tipo | Notas |
|---|---|---|
| `uploadId` | `UploadId` | |
| `tenantId` | `TenantId` | propio; toda búsqueda lo incluye |
| `fileName`, `contentType`, `sizeBytes` | | declarados en la emisión, ya validados |
| `uploadKey` | `String` | `uploads/{tenantId}/{uploadId}` (enmienda 3.1: el prefijo `uploads/` permite la regla de ciclo de vida del bucket) |
| `cleanKey` | `String` | `tenants/{tenantId}/clean/{uploadId}`; solo en `CLEAN` |
| `state` | `ScanState` | nace en `PENDING_SCAN` |
| `rejectionReason`, `signature` | | solo en `INFECTED` |
| `sha256` | `Sha256Digest` | desde el escaneo |
| `issuedAt`, `expiresAt`, `completedAt`, `scannedAt` | `Instant` | |
| `version` | `long` | bloqueo optimista |

Transiciones (métodos que devuelven una copia; cualquier otra lanza `IllegalStateException`):

```text
issue(...)                                   → PENDING_SCAN
markCompleted(now)       PENDING_SCAN        → PENDING_SCAN (completedAt)
markClean(sha256, now)   PENDING_SCAN        → CLEAN
markInfected(sha256, reason, signature, now) PENDING_SCAN → INFECTED
```

### `NotificationContent` (sin cambio de forma)

Record `NotificationContent(String subject, String body, List<Attachment> attachments)`; lista nula →
vacía, copia inmutable en orden, `hasAttachments()`, constructor de dos argumentos. `MAX_LENGTH` sigue
midiendo solo `subject` + `body`.

### `AttachmentPolicy` (reescrita en parte, `core/domain/policy`)

- Constantes: `MAX_ATTACHMENTS = 5`, `MAX_SIZE_BYTES = 10_485_760L`, `EMBEDDED_MAX_SIZE_BYTES =
  1_048_576L`, `MAX_TOTAL_SIZE_BYTES = 26_214_400L`, `MAX_FILE_NAME_LENGTH = 255`, `MAX_URL_LENGTH = 2048`,
  `ALLOWED_CONTENT_TYPES` (7 tipos), `FORBIDDEN_EXTENSIONS` (28 sufijos).
- `static void validate(List<AttachmentSubmission>)`: reglas 1–7 de research.md, Decisión 3.
- `static void validateUploadRequest(String fileName, String contentType, Long sizeBytes)`: reglas 2–5
  más `sizeBytes` > 1 048 576, para la emisión.
- `static byte[] decode(int position, AttachmentSubmission)`: Base64 → bytes, o
  `InvalidAttachmentException`.

### `ContentSchemaValidator` (sin cambio)

Cerrado por defecto; el nodo validado lleva `attachments[i].{fileName, contentType, sizeBytes}`, nunca
`content` ni `url`. Recibe los metadatos de las `AttachmentSubmission`.

### Excepciones (`core/exception`)

| Excepción | Estado | HTTP |
|---|---|---|
| `InvalidAttachmentException(position, rule[, fileName])` | sin cambio | `400` |
| `AttachmentNotReadyException(position)` | nueva | `409` |
| `AttachmentUploadNotFoundException` | nueva | `404` |
| `AttachmentInspectionUnavailableException` | nueva | `503` |

## `core` — puertos

### Entrada (`port/in`)

| Puerto | Firma | Notas |
|---|---|---|
| `SendNotificationCommand` | `content` sigue llevando `subject` y `body` (sin adjuntos); gana `List<AttachmentSubmission> attachments` (nulo → vacío) | el servicio construye el `NotificationContent` final con los `Attachment` verificados |
| `BatchNotificationItem` | igual que el comando | |
| `IssueAttachmentUploadUseCase` | `Mono<IssuedUpload> issue(TenantId, fileName, contentType, sizeBytes)` | `IssuedUpload(uploadId, uploadUrl, expiresAt)` |
| `CompleteAttachmentUploadUseCase` | `Mono<AttachmentUpload> complete(TenantId, UploadId)` | |
| `GetAttachmentUploadUseCase` | `Mono<AttachmentUpload> get(TenantId, UploadId)` | |
| `ScanAttachmentUploadUseCase` | `Mono<Void> scan(TenantId, UploadId)` | lo invoca el consumidor |

### Salida (`port/out`) y repositorio

| Puerto | Métodos |
|---|---|
| `NotificationSenderPort` | `boolean supportsAttachments()` (sin cambio desde la v1) |
| `AttachmentStoragePort` | `presignUpload(key, expiresIn) → Mono<PresignedUpload>`, `stat(key) → Mono<StoredObjectInfo(sizeBytes, etag)>`, `read(key, etag) → Mono<byte[]>`, `copyIfMatch(from, to, etag) → Mono<Void>`, `delete(key) → Mono<Void>`, `parseUploadUrl(url) → Optional<UploadLocation(tenantId, uploadId)>` |
| `MalwareScannerPort` | `scan(byte[]) → Mono<ScanVerdict>`, `signatureVersion() → Mono<String>` |
| `ContentTypeDetectorPort` | `detect(byte[], fileName) → Mono<String>` |
| `ScanVerdictCachePort` | `find(TenantId, Sha256Digest) → Mono<ScanVerdict>`, `save(TenantId, Sha256Digest, ScanVerdict) → Mono<Void>` |
| `AttachmentScanRequestPort` | `requestScan(TenantId, UploadId) → Mono<Void>` |
| `AttachmentUploadRepository` | `save`, `findByTenantAndId(TenantId, UploadId)`, `transition(AttachmentUpload expected, AttachmentUpload next) → Mono<Boolean>` (condicionada al estado y la versión) |

## `core` — casos de uso

- `AttachmentInspector.inspect(tenantId, fileName, declaredType, bytes) → Mono<Inspection(sha256,
  verdict, reason)>`: huella → Tika → caché → ClamAV (research.md, Decisión 12).
- `AttachmentResolver.resolve(tenantId, List<AttachmentSubmission>) → Mono<List<Attachment>>` con
  `concatMap` (orden): embebido → decodificar + inspeccionar; referencia → parsear la `url`, buscar la
  subida por tenant, comparar metadatos y estado.
- `SendNotificationService`: ruta → `AttachmentPolicy.validate` → `ContentSchemaValidator` →
  `AttachmentResolver` → duplicada → aceptación.
- `SendNotificationBatchService`: `InvalidAttachmentException` y `AttachmentNotReadyException` →
  `rejected`.
- `DispatchNotificationService.sendThrough`: con adjuntos y proveedor sin soporte →
  `PERMANENT_FAILURE` sin llamar al proveedor (sin cambio desde la v1).
- `IssueAttachmentUploadService`, `CompleteAttachmentUploadService`, `GetAttachmentUploadService`,
  `ScanAttachmentUploadService` (research.md, Decisión 13).

## `infrastructure`

### REST

- `AttachmentRequest`: `fileName`, `contentType`, `Long sizeBytes`, `content`, `url`; `toString` sin
  `content` ni `url`.
- `SendNotificationRequest.attachments`: sin cambio (nulo → vacío).
- `IssueAttachmentUploadRequest`: `fileName`, `contentType`, `Long sizeBytes`.
- `AttachmentUploadResponse`: `uploadId`, `state`, `fileName`, `contentType`, `sizeBytes`, `sha256`,
  `rejectionReason` (solo en `INFECTED`), `uploadUrl` y `expiresAt` (estos dos solo en la respuesta de
  emisión); `toString` sin `uploadUrl`. La firma detectada no se expone al cliente: solo va al registro.
- `AttachmentUploadController`: `POST /attachment-uploads` (`201`), `POST
  /attachment-uploads/{uploadId}:complete` (`202`), `GET /attachment-uploads/{uploadId}` (`200`).
- `NotificationExceptionHandler`: `400`, `404`, `409`, `503`; `413` por el límite del codec.
- `AttachmentLogFormatter`: `[fileName|contentType|sizeBytes|sha256, …]`, control chars reemplazados.

### RabbitMQ

- Cola `notification.attachment.scan` con su DLQ, declaradas en `RabbitConfig` desde
  `RabbitTopologyProperties`; mensaje `{ "tenantId": "...", "uploadId": "..." }`.
- `AttachmentScanRequestRabbitPublisher` (implementa `AttachmentScanRequestPort`) y
  `AttachmentScanListener` (ack manual).

### MongoDB

`notifications.attachments[]`:

```json
{
  "tenantId": "tenant-1",
  "fileName": "factura-0042.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 183422,
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "storage": "EMBEDDED",
  "content": { "$binary": "..." }
}
```

```json
{
  "tenantId": "tenant-1",
  "fileName": "contrato.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 5242880,
  "sha256": "...",
  "storage": "OBJECT",
  "uploadId": "4f1c...",
  "objectKey": "tenants/tenant-1/clean/4f1c..."
}
```

- `NotificationMongoAdapter`: búsqueda y reencolado con proyección sin `attachments.content`.
- `attachment_uploads` y `attachment_scan_verdicts`: research.md, Decisión 9.

### MinIO

Bucket `notification-attachments` (configurable). Claves `tenants/{tenantId}/uploads/{uploadId}` y
`tenants/{tenantId}/clean/{uploadId}`. Credenciales por variables de entorno.

### Configuración (`application.yml`, sin secretos)

```yaml
spring:
  codec:
    max-in-memory-size: 8MB
notification:
  attachments:
    scan:
      timeout: 10s
      clean-verdict-ttl: 24h
      consumer-concurrency: 2
    upload:
      expiration: 15m
    storage:
      bucket: notification-attachments
      endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
      public-endpoint: ${MINIO_PUBLIC_ENDPOINT:http://localhost:9000}
    clamav:
      host: ${CLAMAV_HOST:localhost}
      port: ${CLAMAV_PORT:3310}
```

`MINIO_ACCESS_KEY` y `MINIO_SECRET_KEY` solo por entorno.

### Catálogo

Sin cambios de código. Un canal habilita adjuntos escribiendo en su `contentSchema`:

```json
{
  "type": "object",
  "required": ["body"],
  "properties": {
    "body": { "type": "string", "maxLength": 5000 },
    "attachments": {
      "type": "array",
      "maxItems": 2,
      "items": {
        "type": "object",
        "properties": {
          "contentType": { "enum": ["application/pdf", "image/png"] },
          "sizeBytes": { "maximum": 5242880 }
        }
      }
    }
  }
}
```

## Flujo de estados

- **Notificación**: sin estados nuevos. Con adjuntos y un proveedor sin soporte:
  `PENDING → IN_PROCESS → FAILED` con un intento `PERMANENT_FAILURE`.
- **Subida**: `PENDING_SCAN → CLEAN | INFECTED`, sin vuelta atrás. `:complete` fallido (objeto ausente o
  tamaño distinto) deja la subida en `PENDING_SCAN`.
