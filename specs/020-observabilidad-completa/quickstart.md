# Quickstart: Observabilidad completa (020)

Verificación manual complementaria; los criterios medibles tienen su prueba automatizada
(`ObservabilityE2ETest`).

1. `docker compose up -d mongodb rabbitmq`
2. Arrancar el servicio: `./mvnw -pl infrastructure spring-boot:run` (API 8060, gestión 8061).
3. Enviar una notificación con `X-Correlation-Id: demo-020` y un token válido.
4. Métricas: `curl http://localhost:8061/actuator/prometheus` y buscar `notification_accepted_total`,
   `notification_dispatched_total` y `notification_provider_duration_seconds`.
5. Error: enviar una solicitud inválida y comprobar `code` (`NTF-...`) y `correlationId` en la
   respuesta y `errorCode` en el log JSON.
6. Trazas: definir el endpoint OTLP del colector local y buscar la traza por `correlationId=demo-020`.
7. Proveedor: con `SIMULATED_PROVIDER_RESULT` en cada valor, confirmar los contadores por resultado.
8. Comprobar que `http://localhost:8060/actuator/prometheus` no responde métricas.
