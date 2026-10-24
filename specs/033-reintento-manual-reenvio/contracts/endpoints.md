# Endpoints para el carril Contrato (HU2-117)

No se edita `api-notificaciones.yaml` desde esta historia. Cambios que el contrato debe recibir antes de los controllers:

| Método y ruta | Cambio | Rol mínimo | Estado |
|---|---|---|---|
| `POST /notification-retries` | Existe; pasar de `planned` a `available`; documentar que no reinicia el contador, que 409 es la carrera perdida y que 400 es el estado no admitido | OPERADOR | available |
| `POST /notification-resends` | Nueva | OPERADOR | available |

## POST /notification-resends

- `operationId`: `resendNotification`. Parámetros comunes: `CorrelationId`, `TraceParent`.
- Cuerpo (todos obligatorios): `notificationId` (uuid), `channelType` (texto del catálogo), `recipient` (dirección en el canal nuevo; dato personal, no se devuelve).
- 202: `NotificationStatusResponse` de la notificación nueva (PENDING).
- 400: estado distinto de FAILED, canal igual al original o no disponible, dirección inválida.
- 401, 403, 503: respuestas comunes del PEP. 404: no existe para el tenant. 409: reenvío ya hecho por ese canal o modificación concurrente. 500.
- Alta en la tabla de migración y en la lista de rutas del PDP (`protected-resources.json` se genera del contrato).

Fuera del YAML, mismo carril: regla `POST /notification-resends` OPERADOR en `RouteAuthorizationPolicy` (la agrega esta historia).
