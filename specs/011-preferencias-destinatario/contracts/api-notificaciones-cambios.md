# Cambios en el contrato público — `api-notificaciones.yaml`

**Feature**: 011-preferencias-destinatario | **Date**: 2026-09-26

Archivo: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`. Se edita antes del
controlador (Principio II). Las dos operaciones ya existen en el contrato; ningún path, `operationId`,
campo ni tipo cambia de nombre.

| Elemento | Cambio |
|---|---|
| `GET /recipients/{recipientId}/preferences` — `description` | Implementada: devuelve las preferencias vigentes del destinatario en el tenant del encabezado; sin registro devuelve el valor por defecto (no dado de baja, `acceptedChannels` vacía = todos los canales, `updatedAt` nulo), nunca 404. Las preferencias de un tenant no son visibles desde otro. |
| `POST /recipients/{recipientId}:updatePreferences` — `description` | Implementada: reemplaza por completo las preferencias; desde el siguiente despacho, una notificación de ese destinatario termina `DISCARDED` sin contactar a ningún proveedor si está dado de baja o si su canal no está entre los aceptados. No altera notificaciones ya terminales ni la aceptación. |
| `POST /recipients/{recipientId}:updatePreferences` — `responses` | + `400` con `ErrorResponse`: una entrada de `acceptedChannels` vacía o solo con espacios, o cuerpo ausente. Las preferencias vigentes no cambian. |
| `RecipientPreferencesResponse` | + `required: [recipientId, optedOutAll, acceptedChannels]`. `acceptedChannels`: normalizados en mayúsculas; vacía = sin restricción; siempre vacía si `optedOutAll`. `updatedAt`: `nullable: true`, nulo si nunca se guardaron preferencias. |
| `UpdatePreferencesRequest.acceptedChannels` | Descripción: vacía o ausente = acepta todos los canales; cada entrada se normaliza (sin espacios, mayúsculas, sin duplicados); una entrada vacía rechaza la actualización con 400; ignorada si `optedOutAll`. |

## Ejemplos

`GET /recipients/r-1/preferences` sin registro previo:

```json
{ "recipientId": "r-1", "optedOutAll": false, "acceptedChannels": [], "updatedAt": null }
```

`POST /recipients/r-1:updatePreferences` con `{ "acceptedChannels": ["email", " sms ", "EMAIL"] }`:

```json
{ "recipientId": "r-1", "optedOutAll": false, "acceptedChannels": ["EMAIL", "SMS"], "updatedAt": "2026-09-26T15:00:00Z" }
```

`POST /recipients/r-1:updatePreferences` con `{ "optedOutAll": true, "acceptedChannels": ["EMAIL"] }`:

```json
{ "recipientId": "r-1", "optedOutAll": true, "acceptedChannels": [], "updatedAt": "2026-09-26T15:01:00Z" }
```
