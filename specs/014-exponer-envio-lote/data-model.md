# Data Model: Enviar un lote de notificaciones por HTTP

Sin entidades de dominio nuevas para el adaptador HTTP en sí: son DTOs que traducen el contrato a los
tipos existentes de `core/port/in`. Ver "Desviaciones del plan" en `plan.md`: una revisión de código
posterior encontró tres defectos que sí requirieron tocar el núcleo (`BatchItemOutcome.FAILED`,
`NotificationAlreadyAcceptedException` y un registro propio de persistencia del lote,
`notification_batches`, vía el nuevo puerto `NotificationBatchRepository`). Esas piezas no son DTOs
HTTP y no se documentan aquí en detalle; ver el código y `plan.md`.

## Entrada

| DTO | Campo | Traducción | Regla |
|-----|-------|------------|-------|
| `SendNotificationBatchRequest` | `batchId` | `null` o `BatchId.of(batchId)` | Opcional; si viene, no puede estar en blanco |
| | `items` | `List<BatchNotificationItem>` | Obligatorio, al menos un elemento, ningún elemento nulo |
| `SendNotificationRequest` (existente) | `externalId` | `ExternalId.of` | Obligatorio, no en blanco |
| | `channelType` | `ChannelType.of` | Obligatorio, no en blanco |
| | `recipientId` | `RecipientId.of` | Obligatorio, no en blanco |
| | `recipientAddress` | `Recipient.of` | Obligatorio, no en blanco |
| | `subject`, `body` | `NotificationContent.of(subject, body)` | `body` obligatorio |
| | `priority` | `Priority.valueOf` | Obligatorio, `LOW`, `NORMAL` o `HIGH` |

El tenant sale de la cabecera `X-Tenant-Id` -> `TenantId.of`. Cualquier violación de estas reglas es
un error estructural: `400` para toda la solicitud.

## Salida

| DTO | Campo | Origen |
|-----|-------|--------|
| `BatchAcceptedResponse` | `batchId` | `BatchAcceptedResult.batchId().value()` |
| | `results` | `BatchAcceptedResult.results()`, en el mismo orden |
| `BatchItemResultResponse` | `externalId` | `BatchItemResult.externalId().value()` |
| | `outcome` | `BatchItemResult.outcome().name()` (`ACCEPTED`, `DUPLICATE`, `REJECTED`, `FAILED`) |
| | `notificationId` | `BatchItemResult.notificationId().value()`, `null` si `REJECTED` o `FAILED` |
| | `rejectionReason` | `BatchItemResult.rejectionReason()`, solo si `REJECTED` o `FAILED` |
