# Data Model: Logs estructurados con identificador de correlación (HU2-056)

## CorrelationId (utils, existe sin commit)

- `value`: cadena 1 a 64 caracteres, patrón `[A-Za-z0-9._-]`.
- Constantes: cabecera HTTP `X-Correlation-Id`, clave de contexto `correlationId`, cabecera AMQP
  `x-correlation-id`.
- Reglas: `fromOrNew` devuelve uno nuevo si el candidato es nulo o inválido; inmutable.

## Notification (core, modificado sin commit)

- Añade `correlationId` (puede ser nulo en registros antiguos). Se asigna una sola vez al aceptar.
- Persistencia: campo `correlationId` en `NotificationDocument`; documentos antiguos se leen como nulo.

## DomainEvent y 7 records (core, implementado)

- Añaden `correlationId` y `tenantId` (además de `notificationId` y `occurredOn`).
- Regla: los eventos de una misma notificación comparten el `correlationId` con el que fue aceptada;
  `tenantId` nunca nulo; `correlationId` nulo solo para notificaciones anteriores a la historia.

## NotificationStatusView / Response (core + REST, implementado)

- Añade `correlationId` (opcional para registros antiguos).

## ErrorResponse (REST, implementado)

- Añade `correlationId` (siempre presente: lo ha generado el filtro).

## LogSanitizer (utils, implementado)

- `maskRecipient(String)`: devuelve forma enmascarada; nulo devuelve marcador.
- `redact(String)`: devuelve marcador constante, nunca el valor.
- `safe(String)`: sustituye caracteres de control; limita longitud.

## FailureCategory (utils, implementado)

- `RECOVERABLE_PROVIDER`, `RECOVERABLE_INFRASTRUCTURE`, `PERMANENT_BUSINESS`.

## Entrada de log (salida, no persistida)

Campos: `timestamp`, `level`, `logger`, `message`, `correlationId`, `tenantId`, `notificationId`,
`traceparent` (cuando llega), `event` (hitos de ciclo de vida), `failureCategory` (cuando aplica), `stack_trace` (sanitizada). Los tres identificadores son campos
independientes, no parte del mensaje.
