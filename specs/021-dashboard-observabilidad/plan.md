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
desde archivos versionados, ambos en el `docker-compose.yml` bajo el perfil `observability`, y una puerta
E2E que impide que el tablero referencie series inexistentes o datos prohibidos. Q1 a Q5 de la spec siguen
PENDIENTES; todo lo que depende de ellas lleva la marca PROVISIONAL y se ajusta tras la respuesta.

## Decisiones (todas PROVISIONALES salvo que se indique)

- Q1 PROVISIONAL: reglas de alerta de Prometheus versionadas, sin Alertmanager ni destino externo; el
  destino queda como excepción del Principio VII.
- Q2 PROVISIONAL: `--storage.tsdb.retention.time=31d`, volumen nombrado.
- Q3 PROVISIONAL: dos mecanismos de la research R7: verificación de existencia contra `/actuator/prometheus` y
  evaluación contra un Prometheus real en contenedor, más prueba de contrato del compose.
- Q4 PROVISIONAL: panel por minuto y por réplica con línea en 500; la prueba de carga queda fuera y se
  registra como excepción con dueño y fecha que fija el usuario.
- Q5 PROVISIONAL: `prom/prometheus:v3.5.1`, `grafana/grafana:12.2.0`.
- Credenciales de Grafana por secretos de Docker desde `secrets/grafana_admin_user` y
  `secrets/grafana_admin_password` (gitignored) con `GF_SECURITY_ADMIN_*__FILE`: la alternativa `${VAR:?}`
  rompería `docker compose up mongodb rabbitmq` (research R3, verificado). Esto se aparta del patrón
  `${VAR:?}` del resto del compose y se justifica aquí; requiere confirmación del usuario.

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
| Secretos | Sin credenciales en el repo; archivos en `secrets/` ignorados por git; el README documenta cómo crearlos. |
| RabbitMQ ack/DLQ | No aplica; sin cambios en consumidores. |

Resultado: sin violaciones. Desviación declarada: credenciales por archivo en lugar de `${VAR:?}`.

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
├── alerts.yml                             (PROVISIONAL, Q1)
└── grafana/
    ├── provisioning/datasources/prometheus.yml
    ├── provisioning/dashboards/dashboards.yml
    └── dashboards/notification-service.json

docker-compose.yml                         (servicios prometheus y grafana, perfil observability, volúmenes, secretos)
.env.example                               (PROMETHEUS_PORT y GRAFANA_PORT opcionales; sin credenciales)
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
- Servicio compose: imagen fija, `profiles: [observability]`, puerto `127.0.0.1:${PROMETHEUS_PORT:-9090}:9090`,
  `extra_hosts: host.docker.internal:host-gateway`, comando con `--config.file`, retención y
  `--storage.tsdb.path`, montajes de solo lectura de la configuración, volumen `prometheus-data`,
  `restart: unless-stopped`.

### 2. Grafana

- Aprovisionamiento: fuente de datos `uid: prometheus`, URL `http://prometheus:9090`, `isDefault: true`,
  `editable: false`; proveedor de tableros apuntando a `/var/lib/grafana/dashboards` (montaje de solo
  lectura de `observability/grafana/dashboards`).
- Servicio compose: imagen fija, perfil, `127.0.0.1:${GRAFANA_PORT:-3000}:3000`, `depends_on: prometheus`,
  `GF_SECURITY_ADMIN_USER__FILE` y `GF_SECURITY_ADMIN_PASSWORD__FILE` hacia `/run/secrets/...`, sin
  registro anónimo ni de usuarios, sin analítica, volumen `grafana-data`.
- Tablero según `contracts/tablero.md`; se escribe como JSON de Grafana versionado (con `uid` estable, `schemaVersion`
  de la imagen elegida y sin `id`), no exportado a mano de una instancia viva sin revisar.

### 3. Puerta E2E (Q3, PROVISIONAL)

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

`ComposeObservabilityContractTest` (sin Docker, SnakeYAML): imágenes nuevas con etiqueta distinta de
`latest` y presente; servicios con `profiles: [observability]`; ningún `ports` con 8061; puertos de
Prometheus y Grafana con prefijo `127.0.0.1`; sin credenciales de Grafana en `environment` con valor
literal ni por defecto; los servicios existentes no tienen perfil nuevo (SC-005, SC-006).

### 4. README

Sección "Tablero de observabilidad": crear los dos archivos de `secrets/`, `docker compose --profile
observability up -d`, abrir `http://localhost:3000`, supuesto de servicio fuera de Docker, qué demuestra
cada panel de RNF y qué no (research R6), cómo parar y borrar volúmenes.

## Estrategia de pruebas

| Nivel | Prueba | Afirma |
|---|---|---|
| Unitaria | `DashboardQueriesTest` | Extracción de métricas/etiquetas; sustitución de variables; los tres negativos y el control positivo |
| Contrato | `ComposeObservabilityContractTest` | SC-005, SC-006, FR-003, FR-005 |
| E2E | `ObservabilityDashboardE2ETest` | FR-010, FR-011, SC-002, SC-003; raspado <= 2 s con `Duration`; dos tenants |
| Arquitectura | `HexagonalArchitectureTest`, `ModularityTests` | Siguen en verde |
| Manual documentado | arranque real del perfil en la máquina del autor, con captura de la salida | SC-001 y escenarios de Story 1; no sustituye a lo automatizado |

## Riesgos y brechas

1. **`${VAR:?}` en servicio con perfil rompe el arranque de todo** (verificado): se usa archivo de secretos;
   falta confirmar con `up` real el mensaje de error y los permisos del archivo en Linux.
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
10. **Dueño y fecha de las excepciones**: no los conozco; se piden al usuario.

## Complexity Tracking

Sin violaciones. Desviación a confirmar: secretos por archivo en lugar de `${VAR:?}` (ver Decisiones).
