# Tasks: Observabilidad completa (020)

**Input**: `specs/020-observabilidad-completa/` (spec.md, plan.md aceptado, research.md, data-model.md, contracts/)

**Tests**: Solicitadas por la constitución (Principio IV): cada entrega escribe las pruebas primero, las ve fallar por la razón correcta, implementa y las ve en verde; cada entrega cierra con E2E y con `verify`.

**Organización**: cuatro entregas desplegables por separado, en orden de dependencia: E1 ErrorCode (US2), E2 métricas (US1), E3 trazado (US3), E4 proveedores (US4). Cada entrega es un conjunto de commits locales de una línea `tipo(ámbito): descripción (020)`.

## Formato: `[ID] [P?] [Story] Descripción con ruta`

- **[P]**: paralelizable (archivos distintos, sin dependencia)
- Rutas base: `utils/src/main/java/co/edu/uco/notification/utils/` (U), `core/src/main/java/co/edu/uco/notification/core/` (C), `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/` (I); pruebas en el mismo paquete bajo `src/test/java`
- Comandos: los de la tabla del repositorio; con Docker en este equipo, `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`
- Sin comentarios en el código nuevo (Principio III); tras crear o editar `.java`, `./mvnw -B -ntp spotless:apply`

## Phase 1: Setup

- [ ] T001 Confirmar línea base: `./mvnw -B -ntp clean compile` y `HexagonalArchitectureTest,ModularityTests` en verde antes de cualquier cambio; anotar en la tarea el resultado
- [ ] T002 [P] Añadir al `pom.xml` de `infrastructure` (versiones del BOM de Spring Boot) `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` y, en alcance test, `micrometer-tracing-test` y `opentelemetry-sdk-testing`; confirmar que `core` y `utils` no ganan dependencias nuevas

---

## Phase 2: E1 - ErrorCode estable (US2, P1)

**Goal**: catálogo `ErrorCode` y su uso en respuesta, log y `core`.

**Independent Test**: error de validación, autorización, rechazo permanente y fallo recuperable: respuesta, log y catálogo coinciden.

### Pruebas primero (E1)

- [ ] T003 [P] [US2] `utils/src/test/java/co/edu/uco/notification/utils/ErrorCodeTest.java`: enteros y textos únicos, `format()` = `NTF-<n>`, categoría no nula, coherencia de rangos 1xxx-5xxx, genéricos 3000/4000/5000, y que cada código tiene emisor o prueba que lo emite (FR-010, FR-014); debe fallar (no existe la clase)
- [ ] T004 [P] [US2] Pruebas unitarias con `StepVerifier` en `core/src/test/java/.../usecase/DispatchNotificationServiceTest.java` y `RequeuePendingNotificationsServiceTest.java`: el fallo se reporta con `ErrorCode` y ya no con `category=` en texto (FR-013); deben fallar
- [ ] T005 [P] [US2] `infrastructure/src/test/java/.../adapter/in/rest/NotificationExceptionHandlerTest.java`: cada excepción mapeada devuelve `code` del catálogo con `message` y `correlationId`; una excepción no catalogada devuelve el genérico de su categoría y nunca `getMessage()` (FR-011, FR-012); debe fallar
- [ ] T006 [P] [US2] Prueba de contrato `infrastructure/src/test/java/.../adapter/in/rest/ErrorResponseContractTest.java`: el esquema `ErrorResponse` del YAML tiene `code` obligatorio con la enumeración igual a `ErrorCode.values()` y todas las respuestas de error lo referencian

### Implementación (E1)

- [ ] T007 [US2] Contrato primero: añadir `code` (enum `NTF-<n>`, `required`) a `ErrorResponse` en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` y revisar que ninguna respuesta de error defina un esquema propio (Principio II, `contracts/api-notificaciones-cambios.md`)
- [ ] T008 [US2] Crear `U/ErrorCode.java` (entero estable, `format()`, `category()` explícito, rangos Q4 y genéricos 3000/4000/5000) con los códigos de todos los puntos de fallo actuales
- [ ] T009 [US2] Sustituir los mensajes `category=` por `ErrorCode` en `C/usecase/DispatchNotificationService.java` y `C/usecase/RequeuePendingNotificationsService.java`; las excepciones de dominio pueden llevar el código sin depender de frameworks
- [ ] T010 [US2] `I/adapter/in/rest/NotificationExceptionHandler.java` y el record de respuesta de error: mapear cada excepción a su `ErrorCode` y emitir `code`; genérico por categoría para lo no catalogado
- [ ] T011 [P] [US2] Registrar `errorCode` y `failureCategory` (derivada del código) vía `LogFields` en `I/adapter/out/provider/ProviderLogs.java`, `I/adapter/in/rabbit/NotificationDispatchListener.java`, `ManualAckSettler.java`, `AttachmentScanListener.java` y el adaptador de MinIO; añadir `errorCode` a `LogFields` y a `logback-spring.xml`
- [ ] T012 [US2] Ejecutar `spotless:apply` y las clases de T003-T006 en verde
- [ ] T013 [US2] E2E `infrastructure/src/test/java/.../ErrorCodeE2ETest.java` (`@SpringBootTest` RANDOM_PORT, Mongo, RabbitMQ, `WebTestClient`): validación, 401/403, rechazo permanente del proveedor simulado y fallo recuperable; en cada caso `code` de la respuesta = `errorCode` del log = catálogo (SC-002); mensajes publicados con `NotificationEventPublisherPort.publish`
- [ ] T014 [US2] Entrega E1: `./mvnw -B -ntp verify` completo en verde (en segundo plano, timeout alto; anotar cualquier exclusión de las dos pruebas de DLQ) y commit `feat(errorcode): catalogo ErrorCode y campo code en errores (020)`

**Checkpoint E1**: ErrorCode desplegable por sí solo.

---

## Phase 3: E2 - Métricas y exposición de actuator (US1, P1)

**Goal**: métricas técnicas y de negocio en Prometheus por pull, en el puerto de gestión 8061, sin exponer nada más de actuator.

**Independent Test**: enviar notificaciones con cada resultado simulado y leer `/actuator/prometheus` en 8061; los contadores coinciden exactamente.

### Pruebas primero (E2)

- [ ] T015 [P] [US1] Unitarias con `StepVerifier` en `core/src/test/java/.../usecase/` de `SendNotificationServiceTest`, `SendNotificationBatchServiceTest`, `DispatchNotificationServiceTest` y `RequeuePendingNotificationsServiceTest` con `NotificationMetricsPort` simulado: una llamada por hecho, la reaceptación idempotente no cuenta, el lote cuenta notificaciones, proveedor deshabilitado cuenta `failed` sin duración (FR-002, FR-003); deben fallar
- [ ] T016 [P] [US1] `infrastructure/src/test/java/.../adapter/out/metrics/MicrometerNotificationMetricsTest.java` con `SimpleMeterRegistry`: nombres y etiquetas de `contracts/metricas-y-trazas.md` (`accepted`, `attempts`, `dispatched`, `provider.duration`, `errors`), cardinalidad cerrada y ninguna etiqueta prohibida (FR-008), con control positivo de que sí se emiten; debe fallar
- [ ] T017 [P] [US1] `infrastructure/src/test/java/.../ActuatorExposureE2ETest.java`: con puertos 8060 y 8061 reales, `health` (liveness y readiness) y `prometheus` responden solo en 8061 (control positivo); `env`, `beans`, `heapdump`, `configprops`, `loggers`, `threaddump`, `mappings`, `metrics` e `info` no responden contenido ni en 8061 ni en 8060; 8060 no sirve ninguna ruta de actuator (FR-023 a FR-025, SC-009); debe fallar
- [ ] T018 [P] [US1] Actualizar `infrastructure/src/test/java/.../adapter/in/web/AuthenticationWebFilterTest.java` y `adapter/in/rest/AuthenticationSecurityE2ETest.java`: `/actuator/**` ya no queda eximido de forma indiscriminada en el puerto principal (FR-024); deben fallar

### Implementación (E2)

- [ ] T019 [US1] Crear `C/port/out/NotificationMetricsPort.java` (métodos `void` con tipos del dominio: aceptada por canal, intento despachado con proveedor, resultado y duración, fallo con `ErrorCode`)
- [ ] T020 [US1] Cablear el puerto en `C/usecase/SendNotificationService.java`, `SendNotificationBatchService.java`, `DispatchNotificationService.java` (duración alrededor de `NotificationSenderPort.send`) y `RequeuePendingNotificationsService.java`
- [ ] T021 [US1] Crear `I/adapter/out/metrics/MicrometerNotificationMetrics.java` con `MeterRegistry` y cablearlo en `I/config/UseCaseConfig.java`; temporizador con histograma para p95/p99 (FR-004)
- [ ] T022 [US1] `infrastructure/src/main/resources/application.yml`: `management.server.port=${MANAGEMENT_PORT:8061}`, `management.endpoints.web.exposure.include=health,prometheus` (lista explícita, sin comodines), `management.endpoint.prometheus.enabled`, histograma de percentiles de `http.server.requests` y de las métricas de listeners Rabbit (FR-006, FR-023)
- [ ] T023 [US1] `I/adapter/in/web/AuthenticationWebFilter.java`: eliminar `/actuator` de las rutas exentas del puerto principal (FR-024); confirmar que el puerto de gestión no hereda el filtro
- [ ] T024 [P] [US1] Despliegue (FR-026): documentar el puerto 8061, `MANAGEMENT_PORT` y `/actuator/prometheus` en `README.md`; exponer y comprobar salud en 8061 en `docker-compose.yml` (sin publicarlo fuera si no hace falta); añadir `EXPOSE 8061` al `Dockerfile` junto a 8060; revisar que las sondas de `specs/` y `quickstart.md` apunten a 8061
- [ ] T025 [US1] Ejecutar `spotless:apply` y las clases de T015-T018 en verde, más `HexagonalArchitectureTest` y `ModularityTests` (core sin Micrometer)
- [ ] T026 [US1] E2E `infrastructure/src/test/java/.../MetricsE2ETest.java`: N notificaciones por canal y por resultado simulado, contadores del endpoint de 8061 exactos (SC-001); dos tenants sin `tenantId` en ninguna etiqueta (SC-008); datos centinela (destinatario, contenido, credencial, token) con 0 apariciones junto a control positivo (SC-005); la consulta responde en `Duration` ≤ 2 s afirmada (SC-004)
- [ ] T027 [US1] Entrega E2: `./mvnw -B -ntp verify` completo en verde y commit `feat(metricas): metricas de negocio y puerto de gestion 8061 (020)`

**Checkpoint E2**: métricas y exposición mínima de actuator desplegables.

---

## Phase 4: E3 - Trazado con Micrometer Tracing y OpenTelemetry (US3, P2)

**Goal**: tramos REST, publicación, consumo, despacho y proveedor en una traza con `correlationId` como atributo y `traceId` en los logs.

**Independent Test**: E2E con exportador en memoria; mismo `traceId` en todos los tramos y logs.

### Pruebas primero (E3)

- [ ] T028 [P] [US3] `infrastructure/src/test/java/.../config/ObservationCorrelationFilterTest.java`: el `correlationId` validado se añade como atributo del tramo, los demás valores pasan por `LogSanitizer`, ningún atributo contiene destinatario, contenido, credenciales ni tokens (FR-016, FR-018); debe fallar
- [ ] T029 [P] [US3] `infrastructure/src/test/java/.../adapter/out/rabbit/NotificationRabbitPublisherTest.java`: el mensaje publicado lleva una sola cabecera `traceparent` válida proveniente de la observación y ninguna estampada a mano; debe fallar
- [ ] T030 [P] [US3] `infrastructure/src/test/java/.../TracingResilienceE2ETest.java`: sin endpoint OTLP el servicio arranca sin exportador ni reintentos; con endpoint inalcanzable la aceptación y el despacho no fallan, no pierden notificaciones y el p95 de aceptación no empeora más de 10 % frente a la línea base medida en la misma prueba, con aserción de `Duration` (FR-017, SC-006); debe fallar

### Implementación (E3)

- [ ] T031 [US3] `I/config/ObservabilityConfig.java` (nuevo): observación en REST, `RabbitTemplate`, contenedor de listeners (`observation-enabled`) y `ObservationFilter` del `correlationId`; `spring.reactor.context-propagation=auto` verificando que no choca con `Hooks.enableAutomaticContextPropagation()` ni con `CorrelationContextConfig`
- [ ] T032 [US3] Retirar el estampado y la restauración manuales de `traceparent` en `I/adapter/out/rabbit/NotificationRabbitPublisher.java`, `I/adapter/in/rabbit/NotificationDispatchListener.java`, `AttachmentScanListener.java`, `I/adapter/in/web/CorrelationIdWebFilter.java`, `I/config/CorrelationContext.java`, `LogContext.java` y `LogFields.java`; el tramo de consumo se cierra tras el ack/nack de `ManualAckSettler`
- [ ] T033 [US3] Decidir y ejecutar el destino de `U/TraceParent.java` y `TraceParentTest.java`: conservar solo si sigue habiendo un consumidor real; si no, eliminarlos junto con sus pruebas y las de `AttachmentScanListenerCorrelationTest`, `NotificationDispatchListenerTest`, `NotificationControllerTest` y `CorrelationIdWebFilterTest` que los usen (Principio VII)
- [ ] T034 [US3] Verificar que los `WebClient` de Brevo, Twilio y FCM (`I/adapter/out/provider/ProviderHttpClients.java`, `FcmProviderConfig.java`) usan el `WebClient.Builder` autoconfigurado y ajustar si no
- [ ] T035 [US3] `application.yml` y `logback-spring.xml`: `management.otlp.tracing.endpoint` vacío por defecto (sin exportador), transporte OTLP/HTTP, `management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:1.0}` en local y 0.1 en el perfil de producción (Q7); `traceId` y `spanId` del MDC en el JSON del log
- [ ] T036 [US3] Ejecutar `spotless:apply` y las clases de T028-T030 en verde
- [ ] T037 [US3] E2E `infrastructure/src/test/java/.../TracingE2ETest.java` con exportador en memoria: con `traceparent` válido, sin él y con uno inválido (se descarta sin escribirse en logs); 100 % de tramos (REST, publicación, consumo, despacho, proveedor) y de logs del recorrido con el mismo `traceId` (SC-003); `correlationId` como atributo del tramo; dos solicitudes concurrentes sin mezcla (SC-008); mensajes producidos con `NotificationEventPublisherPort.publish`
- [ ] T038 [US3] E2E integrado `infrastructure/src/test/java/.../ObservabilityE2ETest.java`: aceptación, publicación, consumo, despacho simulado, lectura de métricas, trazas y logs, afirmando los tres señales con el mismo `correlationId` y `traceId` (FR-022)
- [ ] T039 [US3] Entrega E3: `./mvnw -B -ntp verify` completo en verde y commit `feat(trazado): micrometer tracing y opentelemetry con correlacion (020)`

**Checkpoint E3**: trazado desplegable; el `traceparent` manual de la 016 ya no existe.

---

## Phase 5: E4 - Correlación con proveedores (US4, P3)

**Goal**: reenviar el `correlationId` solo donde la documentación vigente del proveedor lo admita.

**Independent Test**: servidor HTTP simulado por proveedor; la solicitud saliente lleva el id solo en los que lo soportan.

- [ ] T040 [US4] PRIMERA TAREA: verificar en la documentación vigente de Brevo (API v3 `smtp/email`: `tags`, `headers`, `params`), Twilio (Messages API) y FCM (HTTP v1) si admiten enviar o recibir un identificador de correlación útil y no visible para el destinatario; completar la tabla de `research.md` (D5) con fuente y fecha de consulta por proveedor y el resultado "soportado" o "no soportado por el proveedor"; no se escribe código antes ni se asume soporte (FR-019, Q6)
- [ ] T041 [P] [US4] Para cada proveedor soportado según T040, prueba `infrastructure/src/test/java/.../adapter/out/provider/<Proveedor>CorrelationForwardingTest.java` con servidor HTTP simulado: la solicitud lleva el `correlationId` persistido y validado por `CorrelationId`; id inválido o ausente no se envía y se usa el persistido; reencolado reenvía el mismo id (FR-020, FR-021); debe fallar
- [ ] T042 [P] [US4] Para cada proveedor no soportado según T040, prueba equivalente que afirma que la solicitud saliente no lleva el id (SC-007), con el control positivo de T041 en la misma clase de prueba o suite
- [ ] T043 [US4] Implementar el reenvío solo en los adaptadores soportados (previsiblemente `I/adapter/out/provider/BrevoEmailRequest.java` y `BrevoNotificationProvider.java`, conservando `Idempotency-Key`), con `Notification.correlationId` y `CorrelationId`; log del intento que permita cruzar `correlationId` con el id de mensaje del proveedor
- [ ] T044 [US4] Registrar en `research.md` los proveedores "no soportado por el proveedor" con su evidencia; confirmar que no se abre excepción del Principio VII (si T040 muestra ninguno o solo Brevo) y avisar al usuario del resultado
- [ ] T045 [US4] E2E `infrastructure/src/test/java/.../ProviderCorrelationE2ETest.java`: flujo completo con proveedor HTTP simulado y `correlationId` conocido; 100 % de solicitudes con id en los soportados, 0 en los demás (SC-007)
- [ ] T046 [US4] Entrega E4: `./mvnw -B -ntp verify` completo en verde y commit `feat(proveedores): reenvio del correlationId a proveedores soportados (020)`

---

## Phase 6: Cierre

- [ ] T047 Verificación final de la historia: `./mvnw -B -ntp verify` completo (en segundo plano), cobertura ≥80 % líneas y ≥70 % ramas, Spotless y SpotBugs en verde, `HexagonalArchitectureTest` y `ModularityTests` en verde; ejecutar `quickstart.md` contra el servicio y reportar tareas sin marcar, desviaciones y las dos pruebas de DLQ si se excluyeron

---

## Dependencias y orden

- Setup (T001-T002) antes de todo. E1 -> E2 (las métricas de error usan `ErrorCode`) -> E3 -> E4; E4 solo depende de T040 y de E1.
- Dentro de cada entrega: pruebas (fallan) -> contrato (si aplica) -> implementación -> `spotless:apply` -> E2E -> `verify` -> commit.
- Paralelas: T003-T006, T015-T018, T028-T030, T041-T042; T024 en paralelo con T019-T023.

## Estrategia

MVP: E1 + E2 (ambas P1). E3 y E4 se entregan después, cada una verificada con `verify` antes de la siguiente. Cualquier desviación del plan se anota en la tarea correspondiente y solo el autor marca los checkboxes.
