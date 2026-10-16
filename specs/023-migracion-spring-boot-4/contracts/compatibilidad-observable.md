# Contrato de compatibilidad observable (HU2-106)

Esta historia no define endpoints nuevos ni modifica `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`
(Principio II). Este documento fija lo que debe seguir idéntico antes y después de la migración y la prueba que lo
garantiza. Cualquier diferencia hallada se registra en la nota de cambios de comportamiento y se resuelve revirtiendo la
diferencia o con aprobación explícita del usuario.

| Superficie | Qué no cambia | Cómo se comprueba | Estado actual |
|---|---|---|---|
| Contrato OpenAPI | Archivo `api-notificaciones.yaml` sin una sola línea modificada | `git diff --stat` sobre el archivo al cerrar; revisión en PR | Nueva comprobación (tarea) |
| Rutas, métodos y estados HTTP | Cada operación del contrato | E2E existentes por flujo (`*E2ETest` de `adapter/in/rest`) más una prueba de contrato que recorre todas las operaciones del YAML | Parcial: se añade el recorrido completo |
| Cuerpos de error | Forma y códigos de `ErrorCode` (`ErrorResponseContractTest`, `ErrorCodeE2ETest`, `ErrorResponsesE2ETest`) | Pruebas existentes en verde; texto de `details` de contenido inválido fijado por prueba nueva (CC-01) | Existentes + 1 nueva |
| Eventos de dominio en RabbitMQ | Campos, tipos y nombres del JSON publicado por `NotificationRabbitPublisher`; `notificationId` serializado como objeto `{"value": ...}` | Prueba que publica con el productor real y lee con el consumidor real (`NotificationUpdatesRabbitAdapterTest`) más una comparación contra un documento de referencia capturado antes de migrar | Existente + 1 nueva (documento de referencia) |
| Mensajes consumidos | `AttachmentScanRequest`, `ParametersEventPayload` | `ParametersEventPayloadContractTest`, `AttachmentScanListener*Test`; mensajes de referencia producidos por la versión anterior | Existentes + referencia |
| Métricas | Nombres y etiquetas expuestos en `:8061/actuator/prometheus`; histogramas de `http.server.requests` y `spring.rabbitmq.listener`; `notification.configuration.version` | `MetricsE2ETest`, `ObservabilityE2ETest`, `ConfigurationObservabilityE2ETest`; se añade un catálogo de referencia (lista de nombres) capturado antes de migrar | Existentes + referencia |
| Trazas | Exportación OTLP al destino configurado; `traceId`/`spanId` en log y en mensajes | `TracingE2ETest`, `TracingResilienceE2ETest` | Existentes |
| Logs estructurados | Campos `service`, `correlationId`, `tenantId`, `notificationId`, `traceId`, `spanId`, saneado de excepciones | `StructuredLogLayoutTest`, `LogCorrelationE2ETest` | Existentes |
| Sondas de salud | `liveness`: `livenessState`; `readiness`: `readinessState`, `mongo`, `rabbit`; `show-components: always` | `ActuatorExposureE2ETest` | Existente |
| Variables de entorno | Nombres de `MONGO_*`, `RABBITMQ_*`, `SECURITY_*`, `AUTH_*`, `NOTIFICATION_*`, `BREVO_*`, `TWILIO_*`, `FCM_*`, `MINIO_*`, `CLAMAV_*`, `TRACING_*`, `APP_PORT`, `MANAGEMENT_PORT`, `SIMULATED_PROVIDER_RESULT` | Revisión del diff de `application*.yml`: solo cambian claves de Spring, nunca `${VARIABLE:...}` | Nueva comprobación (tarea) |
