# Contrato operativo: métricas y trazas

Propuesto; pendiente de confirmación (Q1 a Q3, Q7). Los nombres siguen la convención de Micrometer;
Prometheus los muestra con puntos convertidos a guion bajo y sufijos `_total` y `_seconds`.

## Exposición

| Aspecto | Valor |
|---|---|
| Puerto de gestión | `MANAGEMENT_PORT`, por defecto 8061; no se publica fuera del clúster |
| Endpoints | `/actuator/health` (liveness y readiness) y `/actuator/prometheus` |
| Puerto de la API (8060) | No sirve `prometheus` ni `metrics` |
| Trazas | OTLP por HTTP si `OTEL_EXPORTER_OTLP_ENDPOINT` (o la propiedad equivalente) está definido; si no, sin exportador |
| Muestreo | `management.tracing.sampling.probability` por variable de entorno; 0.1 en producción, 1.0 en local y pruebas |

## Métricas de negocio

| Nombre | Tipo | Etiquetas | Se incrementa |
|---|---|---|---|
| `notification.accepted` | contador | `channel` | Una vez por notificación aceptada y persistida (lote: por elemento; reaceptación idempotente: no) |
| `notification.attempts` | contador | `channel`, `provider` | Una vez por intento de despacho |
| `notification.dispatched` | contador | `channel`, `provider`, `result` (`delivered`, `recoverable`, `failed`, `discarded`) | Una vez por resultado de un intento |
| `notification.provider.duration` | temporizador con histograma | `provider`, `result` | Una vez por llamada al proveedor |
| `notification.errors` | contador | `errorCode`, `failureCategory` | Una vez por fallo registrado |

## Métricas técnicas (autoconfiguradas por Boot, sin código propio)

`http.server.requests` con histograma de percentiles, métricas del contenedor y del cliente de
RabbitMQ, JVM, hilos y memoria, y `notification.configuration.version` (HU2-073).

## Prohibido en etiquetas y atributos

`tenantId`, `notificationId`, `correlationId` (salvo atributo de tramo, nunca etiqueta de métrica),
destinatario, contenido, credenciales, tokens.

## Campos de log y atributos de tramo

| Señal | Campos |
|---|---|
| Log | `correlationId`, `tenantId`, `notificationId`, `traceId`, `spanId`, `errorCode`, `failureCategory` |
| Tramo | `correlationId`, `provider`, `channel`, `result` |

Tramos: solicitud REST, publicación a cola, consumo, despacho, llamada HTTP a cada proveedor.
