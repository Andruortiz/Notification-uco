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

## Resultado de la ejecución (2026-10-07)

Jar de la rama de la 020 con `java -jar`, perfil `local`, API en 18060 y gestión en 18061 (puertos aislados),
base `demo020` (borrada al terminar), proveedores simulados, `TRACING_SAMPLING_PROBABILITY=1.0` (el defecto es 0.1)
y un colector OTLP/HTTP falso en un puerto local que guarda lo que recibe.

| Paso | Resultado |
|---|---|
| 1 y 2 | Cumple. Mongo y RabbitMQ en Docker; el servicio responde `readiness` 200 en el puerto de gestión. |
| 3 | Cumple. Con `X-Correlation-Id: demo-020` el envío responde 202 y la notificación llega a `DELIVERED` con ese `correlationId`. |
| 4 | Cumple. `notification_accepted_total{channel="EMAIL"} 1.0`, `notification_dispatched_total{...result="delivered"} 1.0` y `notification_provider_duration_seconds_count{provider="simulated",result="delivered"} 1`. |
| 5 | Cumple. Una solicitud con `externalId` vacío responde 400 con `code: NTF-1004` y `correlationId: demo-020-err`; el log JSON trae `errorCode=NTF-1004` con el mismo `correlationId`. |
| 6 | Cumple. El colector recibió 8 envíos a `/v1/traces` y los bytes contienen `demo-020` (12 apariciones) y `demo-020-err`. |
| 7 | Cumple. `RECOVERABLE_FAILURE` cuenta `result="recoverable"` y `PERMANENT_FAILURE` cuenta `result="failed"`, ambos en `notification_dispatched_total` y `notification_provider_duration_seconds_count`; `ACCEPTED` se vio en el paso 4. En el segundo caso el contador marca 2 porque la notificación recuperable de la corrida anterior se reencoló y se despachó con el nuevo resultado. |
| 8 | Cumple. El puerto de la API no entrega métricas: 401 sin token y 404 con token. |

Observaciones sin cambio de código:

- Justo tras `readiness` 200 el primer envío respondió 400 `Channel not available: EMAIL`; el catálogo termina de cargar unos segundos después (refresco de 30 s) y el reintento respondió 202. No es de la 020.
- Una ruta inexistente en la API (`/actuator/prometheus` con token) responde 404 con `code: NTF-1004`, que es el código de `INVALID_REQUEST`. Es coherente con tener un código por respuesta de error, pero un 404 de ruta lleva un código de petición inválida; decidir si merece uno propio queda a criterio del dueño de la historia.
