# Cambios en el contrato público — `api-notificaciones.yaml`

**Feature**: 017-Fix-specs-012-016 | **Fecha**: 2026-10-05

Archivo: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`.

Por el Principio II, estos cambios se escriben en el contrato antes que en los controladores. Los dos
primeros son incompatibles con el contrato vigente en el caso indicado.

## 1. `POST /notifications:sendBatch` — tope y seguimiento del lote

| Elemento | Cambio |
|---|---|
| `SendNotificationBatchRequest.items` | Añadir `maxItems: 500` (hoy solo `minItems: 1`) |
| `400.description` | Incluir "más de 500 elementos"; se rechaza completo sin procesar ningún elemento |
| `BatchAcceptedResponse.trackingSaved` | Campo nuevo, booleano obligatorio. `true` si el registro del lote quedó guardado o ya existía; `false` si el guardado falló y el seguimiento del lote no está disponible |
| `description` | Un reenvío con el mismo `batchId` del mismo tenant no reprocesa los elementos: devuelve el resultado original |
| `BatchItemResult.rejectionReason` | Para `outcome: FAILED` el valor es siempre `Internal error`; los motivos de rechazo de negocio conservan su texto |

Compatibilidad: `trackingSaved` es aditivo. El tope es incompatible solo para quien envíe más de 500
elementos, que hoy no tienen límite.

## 2. Panel en vivo — ticket de un solo uso

Operación nueva:

```yaml
/notifications:subscribeTicket:
  post:
    operationId: issueSubscriptionTicket
    tags: [Notificaciones]
    summary: Obtener un ticket de un solo uso para abrir el panel en vivo
    description: >
      Devuelve un ticket opaco con vigencia corta (30 segundos) que se presenta una sola vez en
      GET /notifications:subscribe?ticket=... para clientes que no pueden enviar cabeceras (EventSource
      nativo). La credencial principal no viaja en la dirección de la conexión. Rol mínimo: CLIENTE.
    responses:
      '200': SubscriptionTicketResponse   # { ticket: string, expiresInSeconds: integer (30) }
      '401': Unauthorized
```

Cambios en `GET /notifications:subscribe`:

| Elemento | Cambio |
|---|---|
| Parámetro `access_token` | **Se retira.** Incompatible para quien lo use |
| Parámetro `ticket` | Nuevo, opcional. Se consume una sola vez |
| `401.description` | Ticket ausente, caducado, ya usado o desconocido, y también cabecera `Authorization` inválida |
| Prioridad | Si llega la cabecera `Authorization`, tiene prioridad sobre `ticket` |
| `description` | Se retira la excepción de autenticación por `access_token` y la advertencia de logs de intermediarios |

El frontend vigente envía la cabecera con `fetch-event-source` y no se ve afectado.

## 3. Respuestas de error en las operaciones que modifican datos

Se declaran en `components/responses` y se referencian desde `POST /notifications`,
`POST /notifications:sendBatch`, `POST /notifications/{id}:retry` y las operaciones de subida de adjuntos:

| Código | Cuándo | Cuerpo |
|---|---|---|
| `409 Conflict` | Conflicto de versión o clave duplicada en la solicitud | `ErrorResponse` con mensaje fijo y `correlationId` |
| `500 Internal Server Error` | Error inesperado | `ErrorResponse` con mensaje genérico y `correlationId`; nunca el mensaje interno de la excepción |

## 4. Sin cambios

`retryNotification` conserva su forma. Las descripciones de rol, 401/403 y de la regla del contador del
reintento manual las actualiza la 018 (ver `research.md`, D9). Los endpoints de catálogo, adjuntos y
búsqueda no cambian de forma.
