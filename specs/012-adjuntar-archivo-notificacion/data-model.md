# Data Model: Adjuntar un archivo a una notificación

Modelo con la respuesta recomendada de Q1 (referencia https). Si Q1 cambia, ver
`plan.md § Si Q1 cambia a contenido embebido`.

## `core` — dominio

### `Attachment` (nuevo, `core/domain/valueobject`)

Record `Attachment(String fileName, String contentType, Long sizeBytes, String url)`.

| Campo | Regla (la aplica `AttachmentPolicy` en la aceptación, no el constructor) |
|---|---|
| `fileName` | 1–255 caracteres; sin `/`, `\` ni caracteres de control; distinto de `.` y `..` |
| `contentType` | normalizado por el constructor: minúsculas, sin parámetros, sin espacios; en la lista global |
| `sizeBytes` | 1 – 10 485 760, inclusivo |
| `url` | ≤ 2048 caracteres; URI absoluta `https`, con servidor, sin usuario ni contraseña |

- `static Attachment of(fileName, contentType, sizeBytes, url)`.
- `toString()` redefinido: `Attachment[fileName=…, contentType=…, sizeBytes=…]`, sin `url`.
- Sin invariantes en el constructor más allá de la normalización: debe poder reconstituirse desde MongoDB
  aunque un tope global cambie después (research.md, Decisión 3).

### `NotificationContent` (modificado)

Record `NotificationContent(String subject, String body, List<Attachment> attachments)`.

- Constructor canónico: invariantes actuales de `subject` y `body` sin cambios; `attachments` nulo →
  `List.of()`, si no `List.copyOf` (orden conservado, elementos no nulos).
- Constructor adicional `(subject, body)` → lista vacía: las llamadas existentes no cambian.
- `of(subject, body)` y `of(body)` sin cambios; nuevo `of(subject, body, attachments)`.
- `boolean hasAttachments()`.
- `MAX_LENGTH` sigue midiendo solo `subject` + `body`.

### `AttachmentPolicy` (nuevo, `core/domain/policy`)

Clase final con constructor privado, como `ContentSchemaValidator`.

- Constantes: `MAX_ATTACHMENTS = 5`, `MAX_SIZE_BYTES = 10_485_760L`, `MAX_FILE_NAME_LENGTH = 255`,
  `MAX_URL_LENGTH = 2048`, `ALLOWED_CONTENT_TYPES` (conjunto inmutable, research.md, Decisión 3).
- `static void validate(List<Attachment> attachments)`: lista vacía → nada; más de 5 → excepción con
  posición `-1` y regla "at most 5 attachments are allowed"; luego cada adjunto en orden, primera regla
  incumplida → `InvalidAttachmentException`.

### `ContentSchemaValidator` (modificado)

Firma sin cambios: `validate(ChannelType, String schema, NotificationContent content)`.

1. Si `content.hasAttachments()` y el esquema está vacío o su raíz no tiene `properties.attachments` →
   `InvalidContentException(channel, "channel does not accept attachments")`.
2. Si el esquema está vacío (y no hay adjuntos) → sin cambios: no valida.
3. El nodo validado gana `attachments: [ { fileName, contentType, sizeBytes } ]` solo cuando hay
   adjuntos; **nunca** `url`.

### `InvalidAttachmentException` (nuevo, `core/exception`)

`RuntimeException` con `int position()` y mensaje `attachments[<i>]: <regla>` más ` (<fileName>)` cuando
la regla no es la del nombre. Posición `-1` → `attachments: <regla>`. Nunca contiene la `url`.

## `core` — puertos

### `NotificationSenderPort` (modificado)

`boolean supportsAttachments();` abstracto.

| Implementación | Valor |
|---|---|
| `SimulatedNotificationProvider` | `true` |
| `BrevoNotificationProvider`, `TwilioNotificationProvider`, `FcmNotificationProvider` | `false` |
| Dobles de prueba (`NotificationSenderRegistryTest.FakeSender`, `QueryChannelCatalogServiceTest`, `ProviderRoutingE2ETest.RecordingNotificationSender`) | `false` salvo que la prueba necesite otra cosa |

`SendNotificationCommand`, `BatchNotificationItem`, `ChannelRoute`, `ChannelCatalogPort` y los eventos no
cambian de forma.

## `core` — casos de uso

- `SendNotificationService.validateAndProceed`: `AttachmentPolicy.validate(content.attachments())` y
  después `ContentSchemaValidator.validate(...)`; luego el flujo actual (duplicada → aceptación).
- `SendNotificationBatchService.processItem`: `InvalidAttachmentException` se suma a los errores que
  producen `BatchItemResult.rejected`.
- `DispatchNotificationService.sendThrough`: si `notification.content().hasAttachments()` y
  `!sender.supportsAttachments()`, el resultado es `Mono.just(AttemptResult.PERMANENT_FAILURE)` sin
  llamar a `sender.send`; el resto del flujo (marcar en proceso, aplicar resultado, guardar, publicar) no
  cambia. Transiciones: `PENDING → IN_PROCESS → FAILED`, ya permitidas.

## `infrastructure`

### REST

- `SendNotificationRequest` gana `List<AttachmentRequest> attachments` (nulo = sin adjuntos).
- `AttachmentRequest` (nuevo record): `fileName`, `contentType`, `Long sizeBytes`, `url`.
- `NotificationController.toCommand` mapea a `Attachment.of(...)` y a `NotificationContent.of(subject,
  body, attachments)`; registra aceptación y rechazo cuando hay adjuntos (research.md, Decisión 8).
- `AttachmentLogFormatter` (nuevo, paquete REST, final, package-private): `[fileName|contentType|sizeBytes,
  …]` con caracteres de control del nombre reemplazados.
- `NotificationExceptionHandler`: `InvalidAttachmentException` → `400 ErrorResponse`.

### MongoDB (`notifications`)

- `AttachmentDocument` (nuevo record): `fileName`, `contentType`, `Long sizeBytes`, `url`.
- `NotificationDocument` gana `List<AttachmentDocument> attachments` (copia defensiva como
  `deliveryAttempts`).
- `NotificationDocumentMapper`: ida y vuelta en orden; `null` en documentos antiguos → lista vacía.
- Sin índices nuevos ni migración.

### Catálogo

Sin cambios de código ni de `application.yml`. Un canal habilita adjuntos escribiendo en su
`contentSchema`, por ejemplo:

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

Sin estados nuevos. Una notificación con adjuntos por un proveedor sin soporte recorre
`PENDING → IN_PROCESS → FAILED` con un intento `PERMANENT_FAILURE` a nombre del proveedor.
