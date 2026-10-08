# Contrato del tablero: paneles, consultas y umbrales (021)

Las consultas se ajustaron tras la captura real del 2026-10-08 (research R2) y las verifica `ObservabilityDashboardE2ETest`. `job` e `instance` los añade Prometheus.
`$__rate_interval`, `$__range` e `$instance` son variables de Grafana; la prueba E2E las sustituye
(`$instance` por `.*`). Datasource con UID fijo `prometheus`. Job de raspado: `notification-service`.

## Filas y paneles

| Fila | Panel | Consulta | Umbral |
|---|---|---|---|
| Estado | Servicio accesible | `up{job="notification-service"}` | 1 = verde, 0 = rojo |
| RNF-02 | p95 de aceptación (s) | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="notification-service",uri="/notifications",method="POST",status="202"}[$__rate_interval])))` | rojo > 0,2 |
| RNF-02 | Solicitudes de aceptación por segundo | `sum(rate(http_server_requests_seconds_count{job="notification-service",uri="/notifications",method="POST"}[$__rate_interval]))` | n/a |
| RNF-03 | Aceptadas por minuto y por réplica | `sum by (instance) (rate(notification_accepted_total{job="notification-service",instance=~"$instance"}[1m])) * 60` | línea en 500 |
| RNF-03 | Intentos de despacho por minuto y por réplica | `sum by (instance) (rate(notification_attempts_total{job="notification-service",instance=~"$instance"}[1m])) * 60` | línea en 500 |
| RNF-01 | Disponibilidad en la ventana (%) | `avg_over_time(up{job="notification-service"}[$__range]) * 100` | rojo < 99,5 |
| RNF-10 | Texto: qué señales exige RNF-10 y dónde se ven | (sin consulta) | n/a |
| Negocio | Aceptadas por canal | `sum by (channel) (rate(notification_accepted_total[$__rate_interval]))` | n/a |
| Negocio | Intentos por canal y proveedor | `sum by (channel, provider) (rate(notification_attempts_total[$__rate_interval]))` | n/a |
| Negocio | Despachos por resultado | `sum by (result) (rate(notification_dispatched_total[$__rate_interval]))` | n/a |
| Negocio | Proporción de fallidos | `sum(rate(notification_dispatched_total{result="failed"}[$__rate_interval])) / sum(rate(notification_dispatched_total[$__rate_interval]))` | n/a (Q1) |
| Negocio | p95 de duración del proveedor por proveedor | `histogram_quantile(0.95, sum by (le, provider) (rate(notification_provider_duration_seconds_bucket[$__rate_interval])))` | n/a |
| Negocio | Errores por código | `sum by (errorCode) (rate(notification_errors_total[$__rate_interval]))` | n/a |
| Negocio | Errores por categoría de fallo | `sum by (failureCategory) (rate(notification_errors_total[$__rate_interval]))` | n/a |
| Técnico | Rendimiento HTTP por uri y estado | `sum by (uri, status) (rate(http_server_requests_seconds_count{job="notification-service"}[$__rate_interval]))` | n/a |
| Técnico | Tasa de error 5xx | `sum(rate(http_server_requests_seconds_count{job="notification-service",status=~"5.."}[$__rate_interval])) / sum(rate(http_server_requests_seconds_count{job="notification-service"}[$__rate_interval]))` | n/a |
| Técnico | Duración media del consumo de RabbitMQ | `sum(rate(spring_rabbit_listener_seconds_sum[$__rate_interval])) / sum(rate(spring_rabbit_listener_seconds_count[$__rate_interval]))` | n/a |
| Técnico | Mensajes de RabbitMQ por segundo | `sum(rate(rabbitmq_published_total[$__rate_interval]))`, `rabbitmq_consumed_total`, `rabbitmq_rejected_total` | n/a |
| Técnico | Uso de CPU | `process_cpu_usage{job="notification-service"}`, `system_cpu_usage{job="notification-service"}` | n/a |
| Técnico | Memoria heap usada | `sum by (instance) (jvm_memory_used_bytes{job="notification-service",area="heap"})` | n/a |
| Técnico | Hilos vivos | `jvm_threads_live_threads{job="notification-service"}` | n/a |

Variable de plantilla: `instance` = `label_values(up{job="notification-service"}, instance)`, selección
múltiple, valor por defecto todas.

## Reglas de alerta (Q1, umbral aprobado por el usuario)

| Regla | Expresión | Para |
|---|---|---|
| `NotificationDispatchFailedHigh` | `sum(increase(notification_dispatched_total{result="failed"}[5m])) / clamp_min(sum(increase(notification_dispatched_total[5m])), 1) > 0.05` | 5m |
| `NotificationServiceDown` | `up{job="notification-service"} == 0` | 1m |

El umbral de 5 % en 5 minutos fue propuesto por el autor y aprobado por el usuario el 2026-10-08.

## Prohibido

Cualquier mención de `tenantId`, `notificationId`, `correlationId`, `recipient`, `body`, `subject`, en
consultas, etiquetas, leyendas, variables, descripciones o títulos.
