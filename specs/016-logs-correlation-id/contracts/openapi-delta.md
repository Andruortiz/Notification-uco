# Contract delta: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`

Principio II: estos cambios se aplican al contrato antes de tocar controladores.

Ya presente (sin commit): parámetro de cabecera `X-Correlation-Id` en las operaciones.

Por añadir o ajustar:

1. Componente reutilizable `CorrelationIdHeader` (respuesta) con cadena de 1 a 64 caracteres
   `^[A-Za-z0-9._-]{1,64}$`, referenciado en **todas** las respuestas, incluidas `4xx` y `5xx`
   (FR-003).
2. Parámetro de solicitud `X-Correlation-Id` opcional; descripción: si falta o no cumple el patrón,
   el servicio genera uno (FR-001/FR-002).
3. `NotificationStatusResponse.correlationId` (cadena, opcional por registros antiguos) — FR-013.
4. `ErrorResponse.correlationId` (cadena, requerida) — FR-003.
5. Respuestas `401` y `403` de la autenticación interina incluyen la cabecera.
6. `GET /notifications:subscribe` (SSE): cabecera presente en la respuesta de la conexión.

No hay endpoints nuevos.
