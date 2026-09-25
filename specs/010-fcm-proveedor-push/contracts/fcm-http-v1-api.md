# Contrato de salida — Envío de push por la API HTTP v1 de Firebase Cloud Messaging

**Feature**: 010-fcm-proveedor-push | **Date**: 2026-09-24

Contrato de **salida**: el componente actúa como cliente y el contrato lo define el tercero. Se documenta
antes de escribir el adaptador (Principio II aplicado a la dirección que corresponde). Los cambios (solo
descriptivos) del contrato público de entrada están en `api-notificaciones-cambios.md`.

Son dos operaciones: el **canje** de una aserción firmada por un token de acceso (una vez por hora,
aproximadamente) y el **envío** (una vez por notificación).

## 1. Canje de autorización (OAuth 2.0, concesión JWT bearer)

```text
POST {token-url}
Content-Type: application/x-www-form-urlencoded
Accept: application/json

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<JWT>
```

`token-url` es configurable (`FCM_TOKEN_URL`, por defecto `https://oauth2.googleapis.com/token`); en las
pruebas apunta a un servidor local que simula el servicio de autorización.

### Aserción (JWT firmado RS256)

| Parte | Contenido |
|---|---|
| Cabecera | `{"alg":"RS256","typ":"JWT"}` |
| `iss` | `client_email` de la cuenta de servicio |
| `scope` | `https://www.googleapis.com/auth/firebase.messaging` |
| `aud` | `token-url` |
| `iat` | instante actual, en segundos |
| `exp` | `iat + 3600` |
| Firma | `SHA256withRSA` con `private_key`, codificada base64url sin relleno |

**Secreta.** La aserción no se registra nunca.

### Respuestas

Aceptación — `200 OK`:

```json
{ "access_token": "<secreto>", "expires_in": 3599, "token_type": "Bearer" }
```

Rechazo — `400` (`invalid_grant`: clave revocada, cuenta borrada, reloj desviado) o `401`
(`invalid_client`): `{"error":"...","error_description":"..."}`. No se lee `error_description` (puede
nombrar la cuenta). Se clasifica por código HTTP con la tabla de la sección 3.

## 2. Envío

```text
POST {base-url}/v1/projects/{project_id}/messages:send
Authorization: Bearer <access_token>
Content-Type: application/json
Accept: application/json
```

`base-url` es configurable (`FCM_BASE_URL`, por defecto `https://fcm.googleapis.com`). `{project_id}` es el
de la cuenta de servicio.

### Cabeceras

| Cabecera | Valor | Nota |
|---|---|---|
| `Authorization` | `Bearer <access_token>` | **Secreto.** Nunca se registra. |
| `Content-Type` | `application/json` | |
| `Accept` | `application/json` | |

### Cuerpo

```json
{
  "message": {
    "token": "<notification.recipient().address()>",
    "notification": { "title": "<notification.content().subject()>", "body": "<notification.content().body()>" }
  }
}
```

| Campo | Origen | Regla |
|---|---|---|
| `message.token` | dirección del destinatario | opaca; se envía tal cual, sin comprobar su forma (Q1) |
| `message.notification.title` | asunto | se **omite** si la notificación no trae asunto; ≤ 100 caracteres por la forma del canal (Q2) |
| `message.notification.body` | cuerpo | ≤ 900 caracteres por la forma del canal (Q2) |

No se envían: `data`, `android`, `apns`, `webpush`, `fcm_options`, `topic`, `condition`, `validate_only`,
ni ningún dato del cliente (`tenantId`, `externalId`, `recipientId`, `priority`).

### Respuestas

Aceptación — `200 OK`:

```json
{ "name": "projects/<project_id>/messages/<message_id>" }
```

Rechazo — `4xx`/`5xx`:

```json
{
  "error": {
    "code": 404,
    "message": "<texto libre>",
    "status": "NOT_FOUND",
    "details": [ { "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode": "UNREGISTERED" } ]
  }
}
```

El adaptador lee **solo** `name` (aceptación) y `error.status` + `error.details[].errorCode` (rechazo),
para registrarlos. `message` no se lee.

## 3. Clasificación

La tabla completa, con los `errorCode` que caen en cada fila, está en `research.md`, Decisión 5, y es la
especificación de `FcmResponseClassifier`. Decide el código HTTP; aplica igual al canje y al envío.

| Familia | Categoría |
|---|---|
| `2xx` | `ACCEPTED` (en el canje: continúa al envío) |
| `400`, `401`, `403`, `404`, resto de `4xx` salvo `408` y `429` | `PERMANENT_FAILURE` |
| `408`, `429`, `3xx`, `5xx` | `RECOVERABLE_FAILURE` |
| Tiempo de espera agotado, error de conexión, cualquier otra excepción | `RECOVERABLE_FAILURE` |

## Advertencias sobre el contrato del tercero

- **Tamaño máximo**: 4096 bytes de contenido por mensaje; más → `400 INVALID_ARGUMENT`. La forma del canal
  (100 + 900 caracteres) lo evita salvo el caso residual de caracteres de control.
- **Identificadores de dispositivo**: opacos, sin formato garantizado; caducan o se invalidan al
  desinstalar la aplicación o revocar el permiso (`404 UNREGISTERED`). Pertenecen a un proyecto: con la
  credencial de otro, `403 SENDER_ID_MISMATCH`.
- **Sin idempotencia** por clave del cliente: no se envía ninguna (research.md, Decisión 12).
- **Aceptado ≠ mostrado**: `200` significa que el proveedor asumió el mensaje. La entrega al dispositivo no
  se confirma en esta historia.
- **Cuotas** por proyecto: se comunican con `429 QUOTA_EXCEEDED`. Sin límite propio hasta HU2-039.
- **API heredada**: la API "legacy" del proveedor (clave de servidor fija) ya no está disponible; la v1
  con OAuth 2.0 es la única vía.

## Contrato interno

`NotificationSenderPort` **no cambia**. `ProviderDisabledException` (ya existente, genérica por
`ProviderId` + motivo) es la señal de proveedor deshabilitado: no se registra intento, no se altera el
estado persistido y el mensaje termina en la DLQ con la causa.
