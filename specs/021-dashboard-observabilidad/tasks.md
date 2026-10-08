# Tasks: Dashboard de observabilidad con Prometheus y Grafana (021)

**Input**: `specs/021-dashboard-observabilidad/` (spec.md, plan.md Aceptado el 2026-10-08, research.md, contracts/tablero.md)

**Formato**: `- [ ] Txxx [P?] [USn?] Descripción con ruta`. Solo la sesión que implementa marca los checkboxes.

## Phase 1: Captura y base

- [ ] T001 Capturar un raspado real de `/actuator/prometheus` (Docker con Mongo y RabbitMQ, servicio arriba, tráfico de dos tenants) y registrar en `specs/021-dashboard-observabilidad/research.md` los nombres reales de `spring_rabbit_listener_*`, `rabbitmq_*`, `process_cpu_usage`, `system_cpu_usage` y las etiquetas de `notification_configuration_version`; ajustar las consultas PROVISIONALES de `specs/021-dashboard-observabilidad/contracts/tablero.md`
- [ ] T002 [P] Crear `observability/prometheus.yml` (job `notification-service`, `/actuator/prometheus`, objetivo `host.docker.internal:8061`, intervalo 15s, `rule_files`)
- [ ] T003 [P] Crear `observability/grafana/provisioning/datasources/prometheus.yml` (uid `prometheus`, URL `http://prometheus:9090`, por defecto, no editable)
- [ ] T004 [P] Crear `observability/grafana/provisioning/dashboards/dashboards.yml` (proveedor de archivos hacia `/var/lib/grafana/dashboards`)

## Phase 2: US1 - Levantar el tablero y verlo con datos (P1)

**Prueba independiente**: con las variables definidas, el compose adicional resuelve, Prometheus marca el objetivo arriba y Grafana carga el tablero aprovisionado.

- [ ] T005 [US1] Crear `docker-compose.observability.yml` con `prometheus` (`prom/prometheus:v3.5.1`, retención 31d, `extra_hosts` host-gateway, `127.0.0.1:${PROMETHEUS_PORT:-9090}:9090`, volumen `prometheus-data`) y `grafana` (`grafana/grafana:12.2.0`, `127.0.0.1:${GRAFANA_PORT:-3000}:3000`, `GF_SECURITY_ADMIN_USER`/`PASSWORD` con `${VAR:?mensaje}`, sin registro anónimo ni de usuarios, sin analítica, volumen `grafana-data`, montajes de solo lectura); sin publicar 8061
- [ ] T006 [P] [US1] Añadir al `README.md` la sección "Tablero de observabilidad" (variables del `.env`, `-f` o `COMPOSE_FILE` con `COMPOSE_PATH_SEPARATOR=,`, supuesto de servicio fuera de Docker, lectura de cada panel de RNF y sus límites, excepciones del Principio VII con dueño y fechas 2026-11-05 y 2026-11-12, parada y borrado de volúmenes)
- [ ] T007 [US1] Verificar con `docker compose config`: sin el archivo adicional no se exige la variable; con el archivo y la variable ausente falla con el mensaje; con ambas se resuelve; registrar el resultado real

## Phase 3: US2 - Tablero con paneles por RNF (P1)

**Prueba independiente**: cada panel de `contracts/tablero.md` existe con consulta y umbral.

- [ ] T008 [US2] Crear `observability/grafana/dashboards/notification-service.json` con uid estable, variable `instance`, filas Estado, RNF-02, RNF-03, RNF-01, RNF-10 (panel de texto), Negocio y Técnico según `contracts/tablero.md` ajustado por T001; umbrales en 0,2 s, 500/min y 99,5 %; sin `tenantId` ni datos del destinatario
- [ ] T009 [US2] Verificar que Grafana 12.2.0 carga el JSON (levantar el stack y consultar la API de Grafana por tableros aprovisionados); si Docker no lo permite, dejar explícito que no se verificó

## Phase 4: US3 - Puerta E2E y contrato (P2)

**Prueba independiente**: las pruebas pasan con el tablero real y fallan con tableros sintéticos erróneos.

- [ ] T010 [P] [US3] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/support/PrometheusExpression.java` (extrae nombres de métrica y etiquetas de selector y agrupación de un `expr`) y `DashboardQueries.java` (lee el JSON y `alerts.yml`, sustituye `$__rate_interval`, `$__range` e `$instance`)
- [ ] T011 [P] [US3] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/observability/DashboardQueriesTest.java`: extracción, sustitución, y negativos con tableros sintéticos (métrica inexistente, etiqueta inexistente, `tenantId`) que hacen fallar al verificador (control positivo)
- [ ] T012 [P] [US3] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/observability/ComposeObservabilityContractTest.java` (SnakeYAML, ambos archivos): imágenes sin `latest`, servicios solo en el archivo adicional, `docker-compose.yml` sin `GRAFANA_*`, credenciales con `${VAR:?` sin `:-`, sin puerto 8061, puertos en `127.0.0.1`, retención 31d
- [ ] T013 [US3] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/ObservabilityDashboardE2ETest.java` (E2E, Principio IV): `@SpringBootTest(RANDOM_PORT)` con Mongo y RabbitMQ en Testcontainers, tráfico de dos tenants que produzca aceptada, entregada, recuperable, fallida y un error; afirma con `Duration` el raspado <= 2 s; cada métrica y etiqueta del tablero y de las reglas existe en el raspado; sin nombres prohibidos y sin tenant ni destinatario centinela en las series; Prometheus real (`prom/prometheus:v3.5.1`) con `exposeHostPorts` evalúa cada consulta con `status: success`, lista las reglas y marca el objetivo arriba; el mismo verificador sobre un tablero adulterado falla
- [ ] T014 [US3] Ejecutar `./mvnw -B -ntp spotless:apply` tras crear los `.java`

## Phase 5: US4 - Alertas (P3)

- [ ] T015 [US4] Crear `observability/alerts.yml` con `NotificationDispatchFailedHigh` (más de 5 % de `failed` en 5m, para 5m) y `NotificationServiceDown`; referenciada desde `prometheus.yml`; cubierta por T013

## Phase 6: Cierre

- [ ] T016 Actualizar `.env.example` si T005 añade variables (ya incluye `GRAFANA_*`, puertos y `COMPOSE_*`)
- [ ] T017 Ejecutar `./mvnw -B -ntp verify` completo con `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` (incluye Spotless, SpotBugs, cobertura, `HexagonalArchitectureTest` y `ModularityTests`); registrar comando y salida reales; si `DeadLetterQueueE2ETest` o `RabbitRetryConfigCustomAttemptsTest` fallan por el orden de ejecución, repetir excluyéndolas y decirlo
- [ ] T018 Anotar en el informe final los hallazgos de la 020 que no se corrigen aquí (histograma `spring.rabbitmq.listener` frente a `spring.rabbit.listener`, `discarded` sin emitir, 8061 en todas las interfaces)

## Dependencias

T001 antes de T008 y T013. T005 antes de T007, T009 y T012. T008 antes de T013. T010 antes de T011 y T013. T015 antes de T013. T014 y T017 al final. [P]: T002-T004; T010-T012.

## Estrategia

MVP = US1 + US2; US3 es la puerta bloqueante del Principio IV; US4 pequeña. Las pruebas se ejecutan una vez al final (T017), salvo lo mínimo para validar un paso.
