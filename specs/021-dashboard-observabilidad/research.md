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

## R2 — Métricas técnicas (captura real, 2026-10-08)

Capturado de `/actuator/prometheus` de un servicio arrancado por una prueba E2E con Mongo y RabbitMQ en
Testcontainers, tras tráfico de dos tenants (entregadas, recuperables, fallidas) y una consulta 404.

- `http_server_requests_seconds_{bucket,count,sum,max}` con etiquetas `error`, `exception`, `method`,
  `outcome`, `status`, `uri` (plantilla). `POST /notifications` aparece con `status="202"`.
- Listener de RabbitMQ: `spring_rabbit_listener_seconds_{count,sum,max}` y `spring_rabbit_listener_active_seconds_*`
  con etiqueta `spring_rabbit_listener_id`; NO hay `_bucket` (confirma que el histograma configurado como
  `spring.rabbitmq.listener` no surte efecto: la observación se llama `spring.rabbit.listener`). El tablero usa
  la duración media.
- Cliente RabbitMQ (etiqueta `name="rabbit"`): `rabbitmq_published_total`, `rabbitmq_consumed_total`,
  `rabbitmq_acknowledged_total`, `rabbitmq_rejected_total`, `rabbitmq_failed_to_publish_total`,
  `rabbitmq_unrouted_published_total`, `rabbitmq_connections`, `rabbitmq_channels`.
- JVM y proceso: `jvm_memory_used_bytes`, `jvm_threads_live_threads`, `process_cpu_usage`,
  `system_cpu_usage`, todos presentes.
- `notification_configuration_version` lleva la etiqueta `source` (`DEFAULTS`, `LAST_KNOWN`, `PARAMETERS`).
- El raspado no contiene las etiquetas `job` ni `instance`: las añade Prometheus.
- `notification_errors_total` NO apareció tras despachos fallidos: el contador solo se incrementa por fallos de
  infraestructura (`DispatchNotificationService` y `RequeuePendingNotificationsService`), no por resultados
  `failed` del proveedor. Ningún flujo E2E existente lo provoca; la prueba de la 021 lo siembra llamando al
  `NotificationMetricsPort` real (adaptador Micrometer) con dos códigos.

## R3 — Compose: credenciales requeridas (verificado con `docker compose config`, 2026-10-08)

Un `${GRAFANA_ADMIN_PASSWORD:?mensaje}` dentro de un servicio con `profiles: [observability]` NO se
omite al levantar sin el perfil: Compose interpola todo el archivo y falla con "required variable ... is
missing a value", lo que rompería `docker compose up -d mongodb rabbitmq` para quien no use Grafana.

| Opción | Pros | Contras |
|---|---|---|
| A. `${VAR:?}` en el compose con perfil | Coherente con el resto del archivo | Rompe el arranque de todo lo demás: descartada (verificado) |
| B. Secretos de Docker desde archivos en `secrets/` | Sin valor por defecto | Descartada por el usuario |
| C. Archivo `docker-compose.observability.yml` aparte con `${VAR:?}` en el `.env` | Mensaje claro; la variable solo se exige si se incluye el archivo | Dos archivos; hay que pasar `-f` o `COMPOSE_FILE` |

DECIDIDA por el usuario el 2026-10-08: C. Verificado con `docker compose config` sobre archivos de prueba
(el CLI de compose interpola sin necesitar el demonio):

- (a) solo `docker-compose.yml`: no exige la variable y la configuración se resuelve.
- (b) con el archivo adicional y la variable ausente: falla con `required variable GRAFANA_ADMIN_PASSWORD is
  missing a value: GRAFANA_ADMIN_PASSWORD is not set, define it in .env`.
- (c) con las variables definidas: se resuelve.
- (d) `COMPOSE_FILE` en el `.env`: en Windows el separador por defecto es `;`, no `:`; con `:` Compose busca un
  archivo llamado `a.yml:o.yml` y falla. Hay que fijar `COMPOSE_PATH_SEPARATOR=,` y separar con coma, que
  se verificó que funciona. El README lo documenta.

Verificado después con el archivo real y Docker en marcha: `config` sin variables falla con el mensaje, el compose base resuelve sin ellas, y el stack arranca con las variables definidas (ver tasks T007 y T009).

## R4 — Imágenes (verificado con `docker manifest inspect`)

Existen `prom/prometheus:v3.5.0`, `v3.5.1`, `v3.6.0` y `grafana/grafana:12.0.0`, `12.0.2`, `12.2.0`. Confirmadas por el usuario el 2026-10-08 (Q5): `prom/prometheus:v3.5.1` y `grafana/grafana:12.2.0`. La compatibilidad del JSON del tablero
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
5. Se separa en una prueba de contrato del compose (sin Docker): imágenes con etiqueta fija, archivo adicional
   con las credenciales como `${VAR:?}`, 8061 no publicado, sin credenciales con valor por defecto.
