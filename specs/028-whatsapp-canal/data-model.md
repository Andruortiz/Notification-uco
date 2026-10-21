# Phase 1 — Data Model: Enviar notificaciones por el canal WhatsApp

**Feature**: 028-whatsapp-canal | **Date**: 2026-10-21

## Resumen

Un cambio de forma real, pequeño y compatible hacia atrás: la notificación puede llevar una **plantilla**
en lugar de un cuerpo. Ninguna colección nueva, ningún índice nuevo, ninguna migración. Más un documento
sembrado nuevo en el catálogo y un proveedor nuevo (configuración, no entidad).

## Dominio (`core`)

### `ProviderTemplateReference` (value object nuevo)

| Campo | Tipo | Regla |
|---|---|---|
| `templateId` | texto | obligatorio, no vacío |
| `variables` | mapa texto→texto | nunca nulo (vacío si no hay); copia inmutable; nombres no vacíos; valores no nulos |

Los límites de longitud y cantidad (64, 20, 1024) no son del dominio: los fija el esquema del canal.

### `NotificationContent` (modificado)

Campos: `subject`, `body`, `attachments`, **`providerTemplate`** (nuevo, opcional).

| Regla | Origen |
|---|---|
| Exactamente uno de `body` (no vacío) o `providerTemplate` | FR-005; antes `body` era siempre obligatorio |
| Largo total (asunto + cuerpo) ≤ 32 768 | existente, sin cambios |
| Una plantilla no lleva adjuntos | consecuencia del esquema WHATSAPP (sin `attachments`) |

Constructores y fábricas actuales (`of(body)`, `of(subject, body)`, `of(subject, body, attachments)`)
conservan su firma y significado. Se añade una fábrica para plantilla.

### `ContentSchemaValidator` (modificado)

- Si el contenido trae plantilla y el esquema del canal no declara la propiedad `providerTemplate`, rechaza con
  `InvalidContentException` y el motivo `channel does not accept provider templates` (mismo patrón que adjuntos).
- El nodo que valida contra el esquema incluye `providerTemplate` (con `templateId` y `variables`) cuando existe y
  omite `body` cuando es nulo, para que `maxLength`, `maxProperties` y el resto del esquema apliquen.

## Persistencia (Mongo)

`NotificationDocument` gana un campo anidado **`providerTemplate`** (`templateId`, `variables`), nulo en todo
documento existente. `body` pasa a poder ser nulo en documentos nuevos con plantilla. El mapper lo
reconstruye. Los valores de las variables se persisten (como el cuerpo) y no se registran ni se publican.

## Catálogo — documento nuevo en `channel_catalog`

```json
{
  "_id": "WHATSAPP",
  "providers": ["simulated", "twilio-whatsapp"],
  "contentSchema": "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":1600},\"providerTemplate\":{\"type\":\"object\",\"required\":[\"templateId\"],\"properties\":{\"templateId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64},\"variables\":{\"type\":\"object\",\"maxProperties\":20,\"additionalProperties\":{\"type\":\"string\",\"maxLength\":1024}}}}}}"
}
```

| Campo | Regla | Origen |
|---|---|---|
| `_id` | `WHATSAPP` | clave de `notification.catalog.channels` |
| `providers` | lista no vacía; el primero es el preferente | `NOTIFICATION_WHATSAPP_PROVIDERS`, defecto `simulated,twilio-whatsapp` (FR-015) |
| `contentSchema` | JSON Schema 2020-12 | literal versionado (FR-009) |

Los documentos `EMAIL`, `SMS` y `PUSH` no cambian. En entornos ya sembrados, el alta es manual
(`quickstart.md`).

## Proveedor real de WhatsApp — configuración, no entidad

`TwilioWhatsAppProviderProperties` (`notification.provider.twilio-whatsapp`), no persistida:

| Propiedad | Variable de entorno | Obligatoria | Por defecto | Validación al arrancar |
|---|---|---|---|---|
| `account-sid` | `TWILIO_ACCOUNT_SID` | sí | — | presente; `AC` + 32 hexadecimales |
| `auth-token` | `TWILIO_AUTH_TOKEN` | sí | — | presente |
| `from-number` | `TWILIO_WHATSAPP_FROM_NUMBER` | sí | — | presente; formato internacional `^\+[1-9]\d{1,14}$` |
| `base-url` | `TWILIO_WHATSAPP_BASE_URL` | no | `https://api.twilio.com` | — |
| `timeout-ms` | `TWILIO_WHATSAPP_TIMEOUT_MS` | no | `10000` | — |
| `connect-timeout-ms` | `TWILIO_WHATSAPP_CONNECT_TIMEOUT_MS` | no | `5000` | — |

Un fallo deja el proveedor **deshabilitado** con el primer motivo aplicable, sin valores. Los timeouts se
exponen además como parámetros gestionados `provider.twilio-whatsapp.timeout-ms` y
`provider.twilio-whatsapp.connect-timeout-ms` (rango 1 000–60 000 y 500–30 000, como los demás proveedores).

## Entidades existentes — sin cambios de forma

- **`DeliveryAttempt`**: se registra con `providerId = twilio-whatsapp`. El `sid` del mensaje solo se
  registra en el log, como en SMS.
- **`AttemptResult`**: sin cambios; su derivación se define en `research.md` (Decisiones 2 y 5).

## Transiciones de estado

Sin transiciones nuevas.

| Situación | Intento registrado | Estado final | Mensaje de despacho |
|---|---|---|---|
| Proveedor acepta | `ACCEPTED`, `twilio-whatsapp` | `DELIVERED` | ack |
| Rechazo 4xx (incluye ventana cerrada, plantilla rechazada, número sin WhatsApp) | `PERMANENT_FAILURE` | `FAILED` | ack |
| Destinatario o `templateId` con forma inválida | `PERMANENT_FAILURE`, sin llamada | `FAILED` | ack |
| `408/429/5xx/3xx`, tiempo agotado, conexión | `RECOVERABLE_FAILURE` | `RECOVERABLE` (o agotado según `RetryPolicy`) | ack |
| Proveedor deshabilitado | ninguno | sin cambios (`PENDING`) | reintentos del consumidor → DLQ con la causa |
| Cuerpo > límite, ambos o ninguno de cuerpo/plantilla, adjunto, plantilla en canal que no la declara | — (no se acepta) | — | ninguno |
