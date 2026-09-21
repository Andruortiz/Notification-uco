# Contrato de salida — Correo transaccional del proveedor Brevo

**Feature**: 008-brevo-proveedor-correo | **Date**: 2026-09-21

Este es el **único** contrato que la historia introduce, y es de salida. El componente actúa como
cliente; el contrato lo define el tercero, no nosotros.

## El contrato público de entrada no cambia

`infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` **no se modifica**: esta
historia no agrega, quita ni cambia ningún endpoint, esquema ni código de respuesta. El Principio II
(contract-first) no tiene nada que exigir aquí; se deja constancia explícita para que la revisión no lo
lea como un contrato omitido.

## Operación

```text
POST {base-url}/v3/smtp/email
```

`base-url` es configurable (`BREVO_BASE_URL`, por defecto `https://api.brevo.com`). En las pruebas
apunta al servidor local que simula al proveedor.

### Cabeceras

| Cabecera | Valor | Nota |
|---|---|---|
| `api-key` | valor de `BREVO_API_KEY` | **Secreto.** Nunca se registra ni se incluye en mensajes. |
| `Content-Type` | `application/json` | |
| `Accept` | `application/json` | |

### Cuerpo

```json
{
  "sender":  { "email": "<BREVO_SENDER_EMAIL>", "name": "<BREVO_SENDER_NAME, opcional>" },
  "to":      [ { "email": "<recipient.address>" } ],
  "subject": "<content.subject>",
  "textContent": "<content.body>",
  "headers": { "Idempotency-Key": "<notificationId>" }
}
```

Reglas:

- `to` lleva **exactamente un** destinatario: una notificación del componente tiene un destinatario.
- `subject` nunca viaja vacío: si la notificación no trae asunto, el adaptador no llega a construir la
  petición (ver `research.md`, Decisión 6).
- El cuerpo va como `textContent` (texto plano), no como `htmlContent` — pendiente de Q4.
- `headers` es el mapa de cabeceras que el proveedor propaga; la documentación del proveedor usa
  `Idempotency-Key` como ejemplo pero **no define su semántica**. Ver la advertencia de abajo.
- No se envían `templateId`, `params`, `tags`, `attachment`, `cc`, `bcc`, `replyTo` ni
  `messageVersions`.

### Respuestas y su clasificación

La tabla completa (quince filas, incluidos errores de transporte) vive en `research.md`, Decisión 4, y
es la especificación de `BrevoResponseClassifier`. Resumen:

| Familia | Categoría |
|---|---|
| `201`, `202`, resto de `2xx` | `ACCEPTED` |
| `400`, `401`, `403`, `404`, resto de `4xx` salvo `408` y `429` | `PERMANENT_FAILURE` |
| `402` | `RECOVERABLE_FAILURE` (pendiente de Q3) |
| `408`, `429`, `3xx`, `5xx` | `RECOVERABLE_FAILURE` |
| Tiempo de espera agotado, error de conexión, cualquier otra excepción | `RECOVERABLE_FAILURE` |

El cuerpo de las respuestas de error **no se parsea ni se registra**.

## Advertencias sobre el contrato del tercero

- **Idempotencia no garantizada.** La documentación menciona `Idempotency-Key` solo como ejemplo dentro
  de `headers`. No dice si el proveedor deduplica, durante cuánto tiempo, ni qué responde ante una
  repetición. La clave se envía igualmente, pero ningún artefacto de esta historia afirma que el
  duplicado esté resuelto. Se comprueba en la prueba manual de humo (`quickstart.md`).
- **Límites de tasa no documentados.** La página del endpoint no publica límites. Es una de las razones
  por las que HU2-039 debe existir antes de exponer el proveedor a carga real.
- **El remitente debe estar verificado** en la cuenta del proveedor; si no, el proveedor rechaza el
  envío y el componente lo clasifica como fallo permanente.

## Contrato interno que sí cambia

`NotificationSenderPort` **no cambia**: el adaptador implementa `send(Notification)` y `providerId()`
tal como HU2-088 los dejó. Lo único nuevo en `core` es la excepción `ProviderDisabledException`, que
viaja como señal de error de `send(...)` y cuyo contrato es: no se registra intento, no se altera el
estado persistido de la notificación, el mensaje termina en la DLQ con la causa.
