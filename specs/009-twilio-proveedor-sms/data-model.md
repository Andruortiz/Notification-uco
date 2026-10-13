# Phase 1 — Data Model: Integrar Twilio como primer proveedor real de SMS

**Feature**: 009-twilio-proveedor-sms | **Date**: 2026-09-24

## Resumen

**Cero cambios de forma** en lo persistido: ninguna colección, documento, campo, índice ni migración
nueva. **Cero cambios en el dominio** (`core`). Lo único que cambia en Mongo es el **contenido sembrado**
del catálogo: aparece un documento nuevo para el canal SMS en `channel_catalog`, con la forma que el
documento ya tiene.

## Catálogo — documento nuevo en `channel_catalog`

Forma existente (`ChannelCatalogDocument`): `(_id = channelType, providers: [string], contentSchema: string)`.

Documento sembrado en un catálogo vacío con la configuración por defecto:

```json
{
  "_id": "SMS",
  "providers": ["simulated", "twilio"],
  "contentSchema": "{\"type\":\"object\",\"required\":[\"body\"],\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":160}}}"
}
```

| Campo | Regla | Origen |
|---|---|---|
| `_id` | `SMS` | clave del mapa `notification.catalog.channels` |
| `providers` | lista no vacía; el primero es el preferente | `NOTIFICATION_SMS_PROVIDERS`, por defecto `simulated,twilio` (FR-010) |
| `contentSchema` | JSON Schema 2020-12 validado en la aceptación | literal versionado en `application.yml` (Q1, Q2) |

Reglas que expresa la forma de contenido (validadas por `ContentSchemaValidator`, sin cambios):

- `body`: obligatorio, texto, **a lo sumo 160 caracteres** (puntos de código Unicode). Excederlo →
  `InvalidContentException` → `400` en la aceptación; nada se persiste (FR-011, SC-008).
- `subject`: sin restricción; se admite y el adaptador del proveedor real no lo envía (FR-012).

El documento del canal `EMAIL` **no cambia**.

**Entornos ya sembrados**: la siembra no actúa sobre un catálogo con datos; el documento SMS debe darse de
alta a mano (`quickstart.md § Alta del canal SMS en un entorno existente`).

## Proveedor real de SMS — configuración, no entidad

`TwilioProviderProperties` (`notification.provider.twilio`), no persistida:

| Propiedad | Variable de entorno | Obligatoria | Por defecto | Validación al arrancar |
|---|---|---|---|---|
| `account-sid` | `TWILIO_ACCOUNT_SID` | sí | — | presente; `AC` + 32 hexadecimales |
| `auth-token` | `TWILIO_AUTH_TOKEN` | sí | — | presente |
| `from-number` | `TWILIO_FROM_NUMBER` | sí | — | presente; formato internacional `^\+[1-9]\d{1,14}$` |
| `base-url` | `TWILIO_BASE_URL` | no | `https://api.twilio.com` | — |
| `timeout-ms` | `TWILIO_TIMEOUT_MS` | no | `10000` | — |
| `connect-timeout-ms` | `TWILIO_CONNECT_TIMEOUT_MS` | no | `5000` | — |

Un fallo de validación deja el proveedor **deshabilitado** con el primer motivo aplicable (research.md,
Decisión 3). La configuración versionada declara las obligatorias **sin valor**.

## Entidades existentes — sin cambios de forma

- **`Notification`**: el canal SMS usa los mismos campos. `recipient.address` lleva el número en formato
  internacional; `content.subject` puede venir y se ignora; `content.body` ya validado contra la forma
  del canal.
- **`DeliveryAttempt`**: se registra con `providerId = twilio` cuando envía este proveedor (FR-009). El
  identificador de mensaje del proveedor (`sid`) **no** se persiste: solo se registra en el log. Guardarlo
  exigiría un campo nuevo en el intento, que corresponde a la historia de confirmaciones de entrega.
- **`AttemptResult`**: sin cambios; esta historia define su derivación (research.md, Decisión 5).

## Transiciones de estado

Sin transiciones nuevas. Cómo acaba cada camino de esta historia:

| Situación | Intento registrado | Estado final | Mensaje de despacho |
|---|---|---|---|
| Proveedor acepta | `ACCEPTED`, `twilio` | `DELIVERED` | ack |
| Rechazo `400/401/403/404/4xx` | `PERMANENT_FAILURE` | `FAILED` | ack |
| Destinatario sin formato internacional | `PERMANENT_FAILURE`, sin llamada | `FAILED` | ack |
| `408/429/5xx/3xx`, tiempo agotado, conexión | `RECOVERABLE_FAILURE` | `RECOVERABLE` (o agotado según `RetryPolicy`) | ack |
| Proveedor deshabilitado | ninguno | sin cambios (`PENDING`) | reintentos del consumidor → DLQ con la causa |
| Cuerpo > 160 caracteres | — (no se acepta) | — (no existe) | ninguno |
