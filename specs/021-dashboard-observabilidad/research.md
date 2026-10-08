# Research: Dashboard de observabilidad (021)

Fecha de verificación: 2026-10-08. Solo se listan hechos comprobados en el repositorio o con una herramienta;
lo no comprobado se marca así.

## R1 — Nombres reales de métricas (verificado en `MicrometerNotificationMetrics`)

| Micrometer | Prometheus | Tipo | Etiquetas emitidas |
|---|---|---|---|
| `notification.accepted` | `notification_accepted_total` | contador | `channel` |
| `notification.attempts` | `notification_attempts_total` | contador | `channel`, `provider` |
| `notification.dispatched` | `notification_dispatched_total` | contador | `channel`, `provider`, `result` |
| `notification.provider.duration` | `notification_provider_duration_seconds_{bucket,count,sum,max}` | temporizador con histograma | `provider`, `result` |
| `notification.errors` | `notification_errors_total` | contador | `errorCode`, `failureCategory`, `channel`, `provider` |
| `notification.configuration.version` | `notification_configuration_version` | gauge | (sin etiquetas propias; verificar en la captura) |

Hallazgos:

1. `result` solo toma `delivered`, `recoverable` y `failed` (`resultLabel`); el contrato de la 020 y
   `MicrometerNotificationMetricsTest` mencionan `discarded` pero ningún código lo emite (la prueba afirma 0).
   El tablero no depende de `discarded`.
2. `channel` y `provider` en `notification.errors` valen `none` cuando el error no es de despacho.
3. Los contadores se registran de forma perezosa: la serie no existe hasta el primer incremento. Los
   paneles deben tolerar series ausentes y la prueba E2E debe generar tráfico para cada una.
4. Las etiquetas `errorCode` y `failureCategory` llevan mayúsculas (camelCase); Prometheus las conserva tal
   cual.

## R2 — Métricas técnicas

- `http.server.requests` tiene histograma (`application.yml`): `http_server_requests_seconds_bucket`, con
  etiquetas estándar `uri` (plantilla), `method`, `status`, `outcome`. `POST /notifications` responde 202
  (`NotificationController`, línea 76) y `MetricsE2ETest` ya afirma `uri="/notifications"`.
- Listener de RabbitMQ: la observación de Spring AMQP 3.1.7 se llama `spring.rabbit.listener` (verificado en
  el `.class` de `RabbitListenerObservation` del jar 3.1.7 en `~/.m2`), o sea `spring_rabbit_listener_seconds_*`.
  `application.yml` activa el histograma para `spring.rabbitmq.listener`, nombre que NO coincide: es
  probable que no haya `_bucket` del listener. Hasta confirmarlo con una captura real, el tablero usa solo
  `_count`, `_sum` y `_max` del listener (duración media), no percentiles. NO VERIFICADO con una captura
  en vivo (Docker apagado al momento de escribir esto); la primera tarea de la implementación la obtiene.
- Métricas del cliente RabbitMQ (`rabbitmq_*`): NO VERIFICADAS; no se usan hasta capturarlas.
- JVM: `jvm_memory_used_bytes`, `jvm_threads_live_threads` ya las afirma `MetricsE2ETest`;
  `process_cpu_usage` y `system_cpu_usage`: NO VERIFICADAS con captura (las autoconfigura Boot).

## R3 — Compose: credenciales requeridas y perfiles (verificado con `docker compose config`)

Un `${GRAFANA_ADMIN_PASSWORD:?mensaje}` dentro de un servicio con `profiles: [observability]` NO se
omite al levantar sin el perfil: Compose interpola todo el archivo y falla con "required variable ... is
missing a value", lo que rompería `docker compose up -d mongodb rabbitmq` para quien no use Grafana.
Alternativas:

| Opción | Pros | Contras |
|---|---|---|
| A. `${VAR:?}` en el compose con perfil | Coherente con el resto del archivo | Rompe el arranque de todo lo demás: descartada |
| B. Secretos de Docker desde archivos en `secrets/` (ya ignorado por git) + `GF_SECURITY_ADMIN_USER__FILE` y `GF_SECURITY_ADMIN_PASSWORD__FILE` | Sin valor por defecto; `compose config` sin perfil funciona (verificado); el secreto no entra al entorno del contenedor | Mensaje de error de archivo faltante menos claro: se documenta y se verifica en la implementación |
| C. Archivo `docker-compose.observability.yml` aparte con `${VAR:?}` | Mensaje claro | Se aparta del pedido "bajo un perfil"; dos archivos |

Recomendada: B. Pendiente de verificar en la implementación que `up` con el perfil y el archivo ausente falla
con un error comprensible y que Grafana lee el archivo (permisos en Linux).

## R4 — Imágenes (verificado con `docker manifest inspect`)

Existen `prom/prometheus:v3.5.0`, `v3.5.1`, `v3.6.0` y `grafana/grafana:12.0.0`, `12.0.2`, `12.2.0`. Propuesta
sujeta a Q5: `prom/prometheus:v3.5.1` y `grafana/grafana:12.2.0`. La compatibilidad del JSON del tablero
con esa versión de Grafana se comprueba cargándolo en la implementación (no verificado).

## R5 — Alcance del anfitrión

- Windows y macOS con Docker Desktop resuelven `host.docker.internal`; Linux no. Se añade
  `extra_hosts: ["host.docker.internal:host-gateway"]` al servicio de Prometheus (válido en Docker 20.10+).
- El puerto de gestión no fija dirección (`management.server.address` ausente): escucha en todas las
  interfaces del equipo. No se cambia en esta historia (es config del servicio, fuera de alcance); se
  anota como riesgo: en un equipo en red, el 8061 es alcanzable sin autenticación. NO VERIFICADO si
  Docker Desktop alcanzaría un enlace solo a 127.0.0.1.

## R6 — Cómo se mide cada RNF con lo que existe

| RNF | Consulta | Límite honesto |
|---|---|---|
| RNF-02 | p95 de `http_server_requests_seconds_bucket{uri="/notifications",method="POST",status="202"}` | El p95 se interpola entre fronteras fijas de cubo; la frontera más cercana a 0,2 s no es exactamente 0,2 s |
| RNF-03 | `sum by (instance) (rate(notification_accepted_total[1m])) * 60` frente a 500 | Mide el ritmo que hubo, no la capacidad máxima: sin prueba de carga no demuestra que 500/min sea sostenible (Q4) |
| RNF-01 | `avg_over_time(up{job="notification-service"}[$__range])` frente a 0,995 | `up` mide si el raspado funcionó, no la disponibilidad real para clientes; el mes completo exige retención >= 31 días (Q2) |
| RNF-10 | Existencia de las series de negocio y técnicas, por etiqueta | Lo asegura la prueba E2E (SC-002), no el panel |

## R7 — Forma de la puerta E2E (Q3)

1. Existencia: levantar el servicio como `MetricsE2ETest`, generar tráfico que produzca todas las series
   (aceptada, entregada, recuperable, fallida, error), leer `/actuator/prometheus`, extraer de cada `expr`
   los nombres de métrica y de etiqueta, y comprobar que están. Una consulta sobre una métrica inexistente
   devuelve resultado vacío y éxito en Prometheus, así que la validación con un Prometheus real SOLO no
   basta.
2. Sintaxis: un contenedor Prometheus (la misma imagen fijada) con la configuración versionada y el
   servicio de la prueba expuesto con `Testcontainers.exposeHostPorts`, evaluando cada `expr` por la API
   de consultas; un error de sintaxis devuelve `status: error`. También carga las reglas y confirma que están
   en `/api/v1/rules`.
3. Variables de Grafana (`$__rate_interval`, `$instance`, `$__range`) se sustituyen por valores fijos antes
   de evaluar; la sustitución es parte de la prueba.
4. Control positivo: un tablero sintético con una métrica inexistente tiene que hacer fallar el verificador.
5. Se separa en una prueba de contrato del compose (sin Docker): imágenes con etiqueta fija, perfil
   presente, 8061 no publicado, sin credenciales con valor por defecto.
