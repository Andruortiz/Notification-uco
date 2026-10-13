# Phase 1 — Data Model: Integrar Firebase Cloud Messaging como primer proveedor real de PUSH

**Feature**: 010-fcm-proveedor-push | **Date**: 2026-09-24

## Resumen

**Cero cambios de forma** en lo persistido: ninguna colección, documento, campo, índice ni migración
nueva. **Cero cambios en el dominio** (`core`), incluido `Recipient` (research.md, Decisión 6). Lo único
que cambia en Mongo es el **contenido sembrado** del catálogo: aparece un documento para el canal PUSH en
`channel_catalog`, con la forma que el documento ya tiene.

## Catálogo — documento nuevo en `channel_catalog`

Forma existente (`ChannelCatalogDocument`): `(_id = channelType, providers: [string], contentSchema: string)`.

Documento sembrado en un catálogo vacío con la configuración por defecto:

```json
{
  "_id": "PUSH",
  "providers": ["simulated", "fcm"],
  "contentSchema": "{\"type\":\"object\",\"required\":[\"body\"],\"properties\":{\"subject\":{\"maxLength\":100},\"body\":{\"type\":\"string\",\"maxLength\":900}}}"
}
```

| Campo | Regla | Origen |
|---|---|---|
| `_id` | `PUSH` | clave del mapa `notification.catalog.channels` |
| `providers` | lista no vacía; el primero es el preferente | `NOTIFICATION_PUSH_PROVIDERS`, por defecto `simulated,fcm` (FR-010) |
| `contentSchema` | JSON Schema 2020-12 validado en la aceptación | literal versionado en `application.yml` (Q2) |

Reglas que expresa la forma de contenido (validadas por `ContentSchemaValidator`, sin cambios):

- `subject`: opcional; si viene, **a lo sumo 100 caracteres** (puntos de código). Es el título visible.
- `body`: obligatorio, texto, **a lo sumo 900 caracteres**.
- Excederlos → `InvalidContentException` → `400` en la aceptación; nada se persiste (FR-013, SC-013).

Los documentos `EMAIL` y `SMS` **no cambian**.

**Entornos ya sembrados**: la siembra no actúa sobre un catálogo con datos; el documento PUSH se da de
alta a mano (`quickstart.md § 4`).

## Proveedor real de push — configuración, no entidad

`FcmProviderProperties` (`notification.provider.fcm`), no persistida:

| Propiedad | Variable de entorno | Obligatoria | Por defecto | Validación al arrancar |
|---|---|---|---|---|
| `credentials-json` | `FCM_CREDENTIALS_JSON` | una de las dos | — | contenido de la cuenta de servicio |
| `credentials-file` | `FCM_CREDENTIALS_FILE` | una de las dos | — | ruta legible de un secreto montado |
| `base-url` | `FCM_BASE_URL` | no | `https://fcm.googleapis.com` | — |
| `token-url` | `FCM_TOKEN_URL` | no | `https://oauth2.googleapis.com/token` | — |
| `timeout-ms` | `FCM_TIMEOUT_MS` | no | `10000` | — |
| `connect-timeout-ms` | `FCM_CONNECT_TIMEOUT_MS` | no | `5000` | — |

Validación completa y orden de motivos: research.md, Decisión 3. La configuración versionada declara las
dos propiedades de credencial **sin valor**.

### Cuenta de servicio (solo en memoria)

`FcmServiceAccount`, derivada de la credencial al arrancar:

| Dato | Campo del JSON | Uso | Se registra |
|---|---|---|---|
| identificador del proyecto | `project_id` | ruta del envío | no (no es secreto, pero no aporta al diagnóstico) |
| correo de la cuenta | `client_email` | *claim* `iss` de la aserción | **nunca** |
| clave privada | `private_key` (PKCS#8, RSA) | firma RS256 de la aserción; no sale del objeto | **nunca** |

Se exige `type = service_account`. Otros campos del JSON (`private_key_id`, `client_id`, `auth_uri`,
`token_uri`, …) se ignoran; en particular, la URL de canje la fija `token-url`, no el JSON.

### Autorización temporal (solo en memoria)

| Dato | Origen | Vida |
|---|---|---|
| token de acceso | respuesta del canje (`access_token`) | hasta `expires_in − 300 s`; invalidado ante un `401` del envío |

Nunca se persiste, se registra ni se publica.

## Entidades existentes — sin cambios de forma

- **`Notification`**: el canal PUSH usa los mismos campos. `recipient.address` lleva el identificador de
  dispositivo, opaco, tal como llegó (Q1); `content.subject` es el título opcional; `content.body` ya
  validado contra la forma del canal.
- **`DeliveryAttempt`**: se registra con `providerId = fcm` cuando envía este proveedor (FR-009). El
  identificador del mensaje del proveedor y el `errorCode` **no** se persisten: solo se registran en el
  log. Guardarlos exigiría un campo nuevo en el intento (research.md, Decisión 5).
- **`AttemptResult`**: sin cambios; esta historia define su derivación (research.md, Decisión 5).

## Transiciones de estado

Sin transiciones nuevas. Cómo acaba cada camino de esta historia:

| Situación | Intento registrado | Estado final | Mensaje de despacho |
|---|---|---|---|
| Proveedor acepta | `ACCEPTED`, `fcm` | `DELIVERED` | ack |
| `400/401/403/404/4xx` del envío (incluye `UNREGISTERED`, `INVALID_ARGUMENT`, `SENDER_ID_MISMATCH`) | `PERMANENT_FAILURE` | `FAILED` | ack |
| Canje rechazado (`400/401`, credenciales) | `PERMANENT_FAILURE`, sin envío | `FAILED` | ack |
| `408/429/5xx/3xx`, tiempo agotado, conexión (envío o canje) | `RECOVERABLE_FAILURE` | `RECOVERABLE` (o agotado según `RetryPolicy`) | ack |
| Proveedor deshabilitado | ninguno | sin cambios (`PENDING`) | reintentos del consumidor → DLQ con la causa |
| Asunto > 100 o cuerpo > 900 caracteres | — (no se acepta) | — (no existe) | ninguno |
