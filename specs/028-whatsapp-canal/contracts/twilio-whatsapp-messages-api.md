# Contrato de salida: Twilio Messages API para WhatsApp

**Feature**: 028-whatsapp-canal | Contrato del tercero que consume `TwilioWhatsAppNotificationProvider`.
Verificado el 2026-10-21: `ContentSid`, `ContentVariables` y tope de `Body` (1600). Pendientes de verificar uno a uno: los códigos de error distintos de 63016.

## Petición

`POST {base-url}/2010-04-01/Accounts/{AccountSid}/Messages.json`
`Authorization: Basic base64(AccountSid:AuthToken)` · `Content-Type: application/x-www-form-urlencoded`
· `Accept: application/json`

| Campo | Texto libre | Plantilla |
|---|---|---|
| `To` | `whatsapp:+<destinatario>` | igual |
| `From` | `whatsapp:+<origen>` | igual |
| `Body` | cuerpo de la notificación | ausente |
| `ContentSid` | ausente | `templateId` (forma `HX` + 32 hex, verificado) |
| `ContentVariables` | ausente | JSON de las variables (verificado; exige `ContentSid`) |

Sin cabecera de idempotencia: Twilio no la documenta para esta API (verificado 2026-10-21); un reintento
reenvía el mensaje (research.md, Decisión 6).

El asunto de la notificación no se envía. No se envían `StatusCallback` ni `MediaUrl` (Out of Scope).

## Respuesta y clasificación

La categoría la decide únicamente el código HTTP, con la tabla de `TwilioResponseClassifier`:

| HTTP | Categoría |
|---|---|
| 2xx | aceptada (`sid` del mensaje) |
| 408, 429 | recuperable |
| otros 4xx | permanente |
| 3xx, 5xx | recuperable |
| tiempo agotado, sin conexión, cuerpo ilegible | recuperable |

El cuerpo de error trae `code` (entero) y `message`; **solo `code` se lee**. `message` puede contener el
número completo y nunca se registra. El `code` se traduce a una etiqueta de diagnóstico (research.md,
Decisión 5), que no cambia la categoría.

## Datos que nunca salen en registros

Identificador de cuenta, token, la URL de la petición, el cuerpo, el valor de cualquier variable de
plantilla, el número completo y el mensaje de error del proveedor.

## Fuera de este contrato

Callbacks de estado, mensajes entrantes, `MediaUrl`, plantillas con botones o listas y la gestión de
plantillas (Out of Scope de la spec).
