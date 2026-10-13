# Contrato de salida — Envío de SMS por la API de mensajes de Twilio

**Feature**: 009-twilio-proveedor-sms | **Date**: 2026-09-24

Contrato de **salida**: el componente actúa como cliente y el contrato lo define el tercero. Se documenta
antes de escribir el adaptador, que es la disciplina del Principio II aplicada a la dirección que
corresponde. Los cambios (solo descriptivos) del contrato público de entrada están en
`api-notificaciones-cambios.md`.

## Operación

```text
POST {base-url}/2010-04-01/Accounts/{accountSid}/Messages.json
```

`base-url` es configurable (`TWILIO_BASE_URL`, por defecto `https://api.twilio.com`); en las pruebas
apunta al servidor local que simula al proveedor. `{accountSid}` es `TWILIO_ACCOUNT_SID`.

### Cabeceras

| Cabecera | Valor | Nota |
|---|---|---|
| `Authorization` | `Basic base64(TWILIO_ACCOUNT_SID:TWILIO_AUTH_TOKEN)` | **Secreto.** Nunca se registra. |
| `Content-Type` | `application/x-www-form-urlencoded` | |
| `Accept` | `application/json` | |

La URL también contiene el identificador de cuenta: **no se registra** la URL de la petición ni el
mensaje de las excepciones de transporte, que la incluyen.

### Cuerpo (formulario)

| Campo | Valor | Regla |
|---|---|---|
| `To` | `notification.recipient().address()` | formato internacional; si no lo cumple, no se llega a construir la petición (Q3) |
| `From` | `TWILIO_FROM_NUMBER` | validado al arrancar; si no es válido, el proveedor está deshabilitado |
| `Body` | `notification.content().body()` | ≤ 160 caracteres, garantizado por la forma de contenido del canal (Q2) |

No se envían: el asunto (Q1), `MessagingServiceSid`, `StatusCallback`, `MediaUrl`, `ValidityPeriod`,
`ScheduleType`, ni ningún dato del cliente (`tenantId`, `externalId`, `recipientId`, `priority`).

### Respuestas

Aceptación — `201 Created`:

```json
{ "sid": "SMxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "status": "queued", "...": "..." }
```

Rechazo — `4xx`/`5xx`:

```json
{ "code": 21211, "message": "The 'To' number +57300... is not a valid phone number.", "more_info": "...", "status": 400 }
```

El adaptador lee **solo** `sid` (aceptación) y `code` (rechazo), para registrarlos. `message` y
`more_info` no se leen: el proveedor redacta `message` con el número completo.

### Clasificación

La tabla completa (incluidos los errores de transporte) está en `research.md`, Decisión 5, y es la
especificación de `TwilioResponseClassifier`. Solo decide el código HTTP (Q4). Resumen:

| Familia | Categoría |
|---|---|
| `2xx` | `ACCEPTED` |
| `400`, `401`, `403`, `404`, resto de `4xx` salvo `408` y `429` | `PERMANENT_FAILURE` |
| `408`, `429`, `3xx`, `5xx` | `RECOVERABLE_FAILURE` |
| Tiempo de espera agotado, error de conexión, cualquier otra excepción | `RECOVERABLE_FAILURE` |

## Advertencias sobre el contrato del tercero

- **Sin idempotencia del lado del proveedor** conocida para la creación de mensajes: no se envía clave
  (research.md, Decisión 12). Riesgo de SMS duplicado documentado.
- **Cuenta de prueba**: solo entrega a números verificados (rechazo `400`, código 21608, para el resto) y
  antepone "Sent from your Twilio trial account - " al cuerpo.
- **Aceptado ≠ entregado**: `201` significa que el mensaje quedó en cola del proveedor. La entrega real se
  informa de forma asíncrona (fuera de alcance).
- **Límites de tasa** por número de origen y por cuenta: se comunican con `429`. Sin límite propio hasta
  HU2-039.
- **Producción en EE.UU.**: exige registro A2P 10DLC del número de origen.

## Contrato interno

`NotificationSenderPort` **no cambia**. `ProviderDisabledException` (ya existente, genérica por
`ProviderId` + motivo) es la señal de proveedor deshabilitado: no se registra intento, no se altera el
estado persistido y el mensaje termina en la DLQ con la causa.
