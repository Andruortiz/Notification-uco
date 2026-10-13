# Data Model: Observabilidad completa (020)

Sin cambios de esquema en MongoDB ni en los mensajes de RabbitMQ (salvo retirar el estampado manual de
`traceparent`, que la observación sustituye). Rangos y formato confirmados por el usuario el 2026-10-05 (Q4); los nombres concretos se fijan al implementar.

## ErrorCode (utils)

| Atributo | Tipo | Regla |
|---|---|---|
| `number` | entero | único, estable, nunca reutilizado |
| `format()` | texto | `NTF-<number>` |
| `category()` | `FailureCategory` | dato explícito del código |

Rangos propuestos: 1xxx validación, 2xxx autenticación/autorización, 3xxx negocio, 4xxx proveedor,
5xxx infraestructura. Códigos genéricos por categoría: 3000, 4000, 5000.

Relación con `FailureCategory`: `RECOVERABLE_PROVIDER` y `PERMANENT_BUSINESS` y
`RECOVERABLE_INFRASTRUCTURE` se conservan; cada `ErrorCode` apunta a una.

## NotificationMetricsPort (core/port/out)

Hechos de negocio, sin tipos de Micrometer: notificación aceptada (canal), intento despachado (canal,
proveedor, resultado, duración), fallo con `ErrorCode`. Los métodos devuelven `void`.

## Series de métricas

Definidas en `contracts/metricas-y-trazas.md`. Etiquetas permitidas: `channel`, `provider`, `result`,
`errorCode`, `failureCategory`. Prohibidas: `tenantId`, `notificationId`, `correlationId`, destinatario,
contenido, credenciales.

## Tramo (span) y campos de log

- Atributo de tramo: `correlationId` (baja cardinalidad en el tramo raíz y los tramos de despacho).
- Campos de log nuevos: `traceId`, `spanId`, `errorCode`; se conservan `correlationId`, `tenantId`,
  `notificationId`, `failureCategory`.
