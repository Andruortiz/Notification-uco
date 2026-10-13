# Cambios en el contrato público — `api-notificaciones.yaml`

**Feature**: 012-reintentar-envio-manual | **Date**: 2026-09-26

Archivo: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`.

La operación `retryNotification` (`POST /notifications/{id}:retry`) ya está declarada con su forma
definitiva. **Sin cambios estructurales**: ningún path, parámetro, esquema, campo ni código de respuesta se
agrega, quita o modifica. `202 → NotificationStatusResponse`, `400 → ErrorResponse`,
`404 → ErrorResponse` se implementan tal cual.

Se actualizan **solo descripciones** de esa operación, antes de escribir el controller (Principio II):

| Elemento | Descripción nueva (sentido) |
|---|---|
| `description` | Implementado. Solo válido si el estado actual es FAILED o RECOVERABLE. Devuelve la notificación a PENDING y la reencola para despacho asíncrono; la respuesta no espera el resultado del nuevo intento. El intento resultante queda en el histórico (`deliveryAttempts[].origin`, ver `GET /notifications`) con origen MANUAL; los intentos iniciales y los reintentos automáticos figuran como AUTOMATIC. Un reintento manual cuenta dentro del tope de reintentos automáticos y no lo reinicia. |
| `202.description` | Reintento aceptado: la notificación está en PENDING y vuelve al flujo de despacho. |
| `400.description` | El estado actual no admite reintento (PENDING, IN_PROCESS, DELIVERED o DISCARDED), incluido el caso en que otra solicitud simultánea ya la reencoló. |
| `404.description` | No existe una notificación con ese id, o no pertenece al tenant del solicitante — ambos casos responden igual, como en `GET /notifications/{id}`. |

La mención `CU-06.` se retira de la descripción de esta operación; la limpieza general de referencias
`CU-XX` del resto del contrato es de otra rama y no se toca aquí.
