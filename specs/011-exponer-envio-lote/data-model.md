# Data Model: Enviar un lote de notificaciones por HTTP

Sin entidades de dominio nuevas ni cambios de persistencia. Solo DTOs HTTP que traducen el contrato a
los tipos existentes de `core/port/in`.

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
| | `outcome` | `BatchItemResult.outcome().name()` (`ACCEPTED`, `DUPLICATE`, `REJECTED`) |
| | `notificationId` | `BatchItemResult.notificationId().value()`, `null` si `REJECTED` |
| | `rejectionReason` | `BatchItemResult.rejectionReason()`, solo si `REJECTED` |
