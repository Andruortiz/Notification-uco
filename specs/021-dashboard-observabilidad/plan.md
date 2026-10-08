# Implementation Plan: Dashboard de observabilidad con Prometheus y Grafana (021)

**Branch**: `feature/021-dashboard-observabilidad` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/021-dashboard-observabilidad/spec.md`

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

<!--
  Este bloque lo edita el usuario directamente en el archivo para aprobar el plan (Pendiente ->
  Aceptado) o para marcar una revisión (incrementar Versión del plan). Ningún agente infiere ni
  declara aprobación en ningún otro lugar del documento; la aprobación es el valor de este campo,
  editado por el usuario o, bajo su instrucción directa y explícita en el chat, por la sesión
  principal -- nunca por un agente en segundo plano citando un mensaje de otra sesión como fuente de
  autorización (Principio VI).
-->

## Summary

La 020 expone las métricas pero nadie las consume. La 021 añade, sin tocar el código del servicio, un
Prometheus que raspa el puerto de gestión, un Grafana con la fuente de datos y un tablero aprovisionados
desde archivos versionados, ambos en un archivo compose adicional `docker-compose.observability.yml`, y una
puerta E2E que impide que el tablero referencie series inexistentes o datos prohibidos. El usuario
confirmó Q1 a Q5 y la elección del archivo compose adicional (Q6) el 2026-10-08.

## Decisiones confirmadas (usuario, 2026-10-08)

- Q1: alertas solo como reglas de Prometheus en `observability/alerts.yml`, sin Alertmanager ni destino
  externo. Umbral aprobado: más de 5 % de despachos `failed` durante 5 minutos. Excepción del Principio VII
  por el destino de las alertas: dueño y fecha PENDIENTES de que los fije el usuario.
- Q2: `--storage.tsdb.retention.time=31d`, volumen nombrado.
- Q3: `ObservabilityDashboardE2ETest` con verificación de existencia contra `/actuator/prometheus`,
  evaluación contra un Prometheus real en Testcontainers y control positivo, más
  `ComposeObservabilityContractTest`.
- Q4: panel de aceptadas por minuto y por réplica con línea en 500; la prueba de carga queda fuera como
  excepción del Principio VII: dueño y fecha PENDIENTES de que los fije el usuario.
- Q5: `prom/prometheus:v3.5.1` y `grafana/grafana:12.2.0`.
- Q6: Prometheus y Grafana en `docker-compose.observability.yml` (sin perfil). Credenciales en el `.env`
  como `${GRAFANA_ADMIN_USER:?mensaje}` y `${GRAFANA_ADMIN_PASSWORD:?mensaje}`, sin valor por defecto, que
  solo se exigen al incluir ese archivo (`-f docker-compose.yml -f docker-compose.observability.yml` o
  `COMPOSE_FILE` con `COMPOSE_PATH_SEPARATOR=,`). Verificado con `docker compose config` (research R3).

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.3.4 (solo para las pruebas); configuración YAML y JSON

**Primary Dependencies**: Prometheus y Grafana como imágenes; en pruebas, Jackson y SnakeYAML (ya en el
classpath de Boot) y Testcontainers (`GenericContainer`) ya presente

**Storage**: volúmenes nombrados `prometheus-data` y `grafana-data`; el servicio no cambia

**Testing**: JUnit 5, Testcontainers, `@SpringBootTest(RANDOM_PORT)`, `WebTestClient` como `MetricsE2ETest`

**Target Platform**: equipo del desarrollador con Docker Desktop (Windows/macOS) o Linux; el servicio
corre fuera de Docker

**Project Type**: artefactos de configuración versionados + pruebas en `infrastructure`; sin código
productivo nuevo

**Performance Goals**: el tablero muestra datos en menos de 30 s tras el primer raspado (SC-001); raspado
cada 15 s

**Constraints**: sin `latest`; sin credenciales por defecto; el 8061 no se publica; Prometheus y Grafana
solo en `127.0.0.1`; sin tenantId ni datos del destinatario; cero comentarios explicativos en código nuevo
(los YAML de configuración y el compose pueden documentarse en el README, no con comentarios de
justificación)

**Scale/Scope**: un servicio objetivo (varias réplicas posibles), ~20 paneles, 2 reglas

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I. Hexagonal | Sin cambios en `core` ni en adaptadores productivos. |
| II. Contract-first | Sin endpoints nuevos. El contrato del tablero va en `contracts/tablero.md`. |
| III. Sin comentarios | Código de pruebas sin comentarios; la explicación vive en spec, plan y README. Los YAML no llevan comentarios de justificación. |
| IV. Calidad | Prueba E2E bloqueante `ObservabilityDashboardE2ETest` (tarea propia), con control positivo y afirmación de `Duration` para el raspado; `verify` completo con cobertura y SpotBugs. Las pruebas de contrato del compose no necesitan Docker. |
| V. Commits | Una línea, español sin tildes. |
| VI. Aprobación | El Estado del plan es `Pendiente`; no se generan tareas ni se implementa hasta que el usuario lo cambie. |
| VII. Sin atajos | Lo que el tablero no demuestra se registra como excepción con dueño y fecha (FR-013): prueba de carga de RNF-03, histórico mensual de RNF-01 si Q2 no se acepta, destino de alertas si Q1 lo difiere. Dueño y fecha: a definir por el usuario (no se inventan). |
| IX. Observabilidad | Cierra el consumo de las métricas de RNF-10. |
| Secretos | Sin credenciales en el repo; `${VAR:?}` desde el `.env` (ignorado por git), sin valor por defecto; `.env.example` las deja vacías. |
| RabbitMQ ack/DLQ | No aplica; sin cambios en consumidores. |

Resultado: sin violaciones.

## Project Structure

### Documentation

```text
specs/021-dashboard-observabilidad/
├── spec.md
├── plan.md
├── research.md
├── contracts/tablero.md
├── checklists/requirements.md
└── tasks.md          (lo genera /speckit-tasks tras la aprobación; no existe aún)
```

### Source Code

```text
observability/
├── prometheus.yml                         (scrape host.docker.internal:8061, /actuator/prometheus)
├── alerts.yml                             (Q1)
└── grafana/
    ├── provisioning/datasources/prometheus.yml
    ├── provisioning/dashboards/dashboards.yml
    └── dashboards/notification-service.json

docker-compose.observability.yml           (servicios prometheus y grafana, volúmenes, credenciales con ${VAR:?})
.env.example                               (GRAFANA_ADMIN_USER y GRAFANA_ADMIN_PASSWORD vacías, puertos opcionales, COMPOSE_FILE comentado)
README.md                                  (sección "Tablero de observabilidad")

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── ObservabilityDashboardE2ETest.java     (gate E2E, Q3)
└── support/
    ├── DashboardQueries.java              (lectura del JSON y sustitución de variables)
    └── PrometheusExpression.java          (extracción de métricas y etiquetas de un expr)
infrastructure/src/test/java/.../observability/ComposeObservabilityContractTest.java
infrastructure/src/test/java/.../observability/DashboardQueriesTest.java   (control positivo y negativos con tableros sintéticos)
```

**Structure Decision**: directorio `observability/` en la raíz, junto a `docker-compose.yml`, porque son
artefactos de entorno y no de ningún módulo Maven; las pruebas viven en `infrastructure` y leen
`../observability` desde el directorio del módulo.

## Diseño

### 1. Prometheus

- `prometheus.yml`: `scrape_interval: 15s`, un job `notification-service`, `metrics_path:
  /actuator/prometheus`, objetivo `host.docker.internal:8061`, `rule_files: [/etc/prometheus/alerts.yml]`.
- Servicio compose (en el archivo adicional): imagen fija, puerto `127.0.0.1:${PROMETHEUS_PORT:-9090}:9090`,
  `extra_hosts: host.docker.internal:host-gateway`, comando con `--config.file`, retención y
  `--storage.tsdb.path`, montajes de solo lectura de la configuración, volumen `prometheus-data`,
  `restart: unless-stopped`.

### 2. Grafana

- Aprovisionamiento: fuente de datos `uid: prometheus`, URL `http://prometheus:9090`, `isDefault: true`,
  `editable: false`; proveedor de tableros apuntando a `/var/lib/grafana/dashboards` (montaje de solo
  lectura de `observability/grafana/dashboards`).
- Servicio compose (en el archivo adicional): imagen fija, `127.0.0.1:${GRAFANA_PORT:-3000}:3000`, `depends_on: prometheus`,
  `GF_SECURITY_ADMIN_USER: ${GRAFANA_ADMIN_USER:?mensaje}` y `GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?mensaje}`, sin
  registro anónimo ni de usuarios, sin analítica, volumen `grafana-data`.
- Tablero según `contracts/tablero.md`; se escribe como JSON de Grafana versionado (con `uid` estable, `schemaVersion`
  de la imagen elegida y sin `id`), no exportado a mano de una instancia viva sin revisar.

### 3. Puerta E2E (Q3)

`ObservabilityDashboardE2ETest` (`@SpringBootTest(RANDOM_PORT)`, Mongo y RabbitMQ con Testcontainers, remitente
sonda como `MetricsE2ETest`):

1. Genera tráfico en dos tenants que produzca: aceptada, entregada, recuperable, fallida y al menos un
   `notification.errors`; sin ese tráfico los contadores no existen (research R1.3).
2. Raspa `/actuator/prometheus`, afirma con `Duration` que el raspado responde en <= 2 s.
3. Existencia: por cada `expr` del tablero y de `alerts.yml`, cada nombre de métrica y cada etiqueta de
   selector o de agrupación existe en el raspado (para el histograma, `_bucket` está presente).
4. Prohibidos: ninguna cadena de FR-009 aparece en el tablero ni en las reglas.
5. Sintaxis y carga: un contenedor Prometheus de la imagen fijada, con `prometheus.yml` apuntando al
   servicio de la prueba por `host.testcontainers.internal`, carga las reglas y devuelve `status: success`
   para cada consulta; `/api/v1/rules` lista las reglas; `/api/v1/targets` marca el objetivo `up`.
6. Control positivo: `DashboardQueriesTest` demuestra, con tableros sintéticos, que una métrica
   inexistente, una etiqueta inexistente y un `tenantId` hacen fallar al verificador; y en la E2E, el
   mismo verificador sobre un tablero adulterado en memoria falla.
7. Aislamiento por tenant: los datos de dos tenants de la prueba se mezclan en los contadores (sin
   etiqueta de tenant) y la prueba afirma que ninguna serie del raspado contiene los identificadores de tenant
   ni el destinatario centinela.

`ComposeObservabilityContractTest` (sin Docker, SnakeYAML, lee ambos archivos): imágenes nuevas con etiqueta
distinta de `latest` y presente; `prometheus` y `grafana` solo en el archivo adicional y ausentes de
`docker-compose.yml`, que no menciona `GRAFANA_*` (SC-005); credenciales de Grafana como `${VAR:?` sin `:-`
ni valor literal; ningún `ports` con 8061; puertos de Prometheus y Grafana con prefijo `127.0.0.1`
(SC-006).

### 4. README

Sección "Tablero de observabilidad": definir `GRAFANA_ADMIN_USER` y `GRAFANA_ADMIN_PASSWORD` en el `.env`,
`docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d` (o `COMPOSE_FILE` con
`COMPOSE_PATH_SEPARATOR=,`, imprescindible en Windows), abrir `http://localhost:3000`, supuesto de servicio fuera de Docker, qué demuestra
cada panel de RNF y qué no (research R6), cómo parar y borrar volúmenes.

## Estrategia de pruebas

| Nivel | Prueba | Afirma |
|---|---|---|
| Unitaria | `DashboardQueriesTest` | Extracción de métricas/etiquetas; sustitución de variables; los tres negativos y el control positivo |
| Contrato | `ComposeObservabilityContractTest` | SC-005, SC-006, FR-003, FR-005 |
| E2E | `ObservabilityDashboardE2ETest` | FR-010, FR-011, SC-002, SC-003; raspado <= 2 s con `Duration`; dos tenants |
| Arquitectura | `HexagonalArchitectureTest`, `ModularityTests` | Siguen en verde |
| Manual documentado | arranque real del stack en la máquina del autor, con captura de la salida | SC-001 y escenarios de Story 1; no sustituye a lo automatizado |

## Riesgos y brechas

1. **`${VAR:?}` en servicio con perfil rompe el arranque de todo** (verificado): se usa un archivo compose
   adicional (Q6). Verificado con `docker compose config` que sin el archivo no se exige la variable y que
   con el archivo y la variable ausente falla con el mensaje; NO verificado con `up` real (Docker apagado).
   `COMPOSE_FILE` en Windows exige `COMPOSE_PATH_SEPARATOR=,` (verificado).
2. **Nombre del listener de Rabbit**: `application.yml` activa el histograma para `spring.rabbitmq.listener`
   pero la observación se llama `spring.rabbit.listener` (jar de Spring AMQP 3.1.7); probablemente es
   una configuración sin efecto. Fuera de alcance de la 021; se reporta para la Bitácora. El tablero usa
   `_count/_sum`.
3. **`discarded` documentado y no emitido** (research R1.1): inconsistencia entre el contrato de la 020 y el
   código; se reporta; el tablero no depende de él.
4. **Series perezosas**: sin tráfico no hay series; los paneles muestran "sin datos". La prueba genera tráfico.
5. **Exposición del 8061 en el anfitrión**: escucha en todas las interfaces; no se cambia aquí.
6. **p95 interpolado** y **RNF-03 sin prueba de carga**: el tablero evidencia, no demuestra capacidad (research R6).
7. **Dependencia de imágenes en CI**: la E2E arranca un Prometheus real; descarga de imagen en cada runner
   y tiempo adicional (estimado de 10 a 30 s, sin medir). Si el usuario lo rechaza, queda solo la verificación
   de existencia, que no detecta consultas inválidas.
8. **Compatibilidad del JSON con Grafana 12.2**: no verificada hasta cargar el tablero en la implementación.
9. **Docker apagado en este equipo al escribir el plan**: nada de lo anterior sobre comportamiento en vivo
   se ha ejecutado salvo lo marcado como verificado en la research.
10. **Dueño y fecha de las excepciones** (prueba de carga de RNF-03 y destino de alertas): PENDIENTES de que los fije el usuario; no se inventan.

## Complexity Tracking

Sin violaciones. Se conserva `${VAR:?}` del resto del compose, pero en un archivo adicional para no exigirlo a quien no usa Grafana (Q6).
