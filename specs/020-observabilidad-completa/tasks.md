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

- [x] T001 Confirmar línea base: `./mvnw -B -ntp clean compile` y `HexagonalArchitectureTest,ModularityTests` en verde antes de cualquier cambio; anotar en la tarea el resultado

  Hecho: `clean compile` en verde; `HexagonalArchitectureTest` (3) y `ModularityTests` (2) en verde, 5 pruebas, 0 fallos, antes de cualquier cambio.
- [x] T002 [P] Confirmar que `core` y `utils` no ganan dependencias de Micrometer ni OpenTelemetry y que las dependencias de cada entrega se añaden solo en `infrastructure` y en la entrega que las usa (Prometheus en E2, tracing y OTLP en E3), para que cada entrega sea desplegable sola

  Hecho: `core/pom.xml` y `utils/pom.xml` no declaran Micrometer, OpenTelemetry ni actuator, y `core/src` y `utils/src` no importan `io.micrometer` ni `io.opentelemetry` (Grep sin resultados). E1 no añade ninguna dependencia a ningún pom.

---

## Phase 2: E1 - ErrorCode estable (US2, P1)

**Goal**: catálogo `ErrorCode` y su uso en respuesta, log y `core`.

**Independent Test**: error de validación, autorización, rechazo permanente y fallo recuperable: respuesta, log y catálogo coinciden.

### Pruebas primero (E1)

- [x] T003 [P] [US2] `utils/src/test/java/co/edu/uco/notification/utils/ErrorCodeTest.java`: enteros y textos únicos, `format()` = `NTF-<n>`, categoría no nula, coherencia de rangos 1xxx-5xxx, genéricos 3000/4000/5000, y que cada código tiene emisor o prueba que lo emite (FR-010, FR-014); debe fallar (no existe la clase)

  Hecho: `ErrorCodeTest` (6 pruebas) escrita antes de la clase y vista fallar por compilación (`ErrorCode` no existía); el control de emisores escanea los fuentes de producción de los tres módulos buscando `ErrorCode.<NOMBRE>`; los tres genéricos (3000, 4000, 5000) se excluyen de ese escaneo y se comprueban por `genericFor(`, que es su único punto de uso. En verde tras T008 y T009-T011.
- [x] T004 [P] [US2] Pruebas unitarias con `StepVerifier` en `core/src/test/java/.../usecase/DispatchNotificationServiceTest.java` y `RequeuePendingNotificationsServiceTest.java`: el fallo se reporta con `ErrorCode` y ya no con `category=` en texto (FR-013); deben fallar

  Hecho: se añadieron 2 pruebas a `DispatchNotificationServiceTest` y 4 a `RequeuePendingNotificationsServiceTest` con `LogCapture`, que afirman `errorCode=NTF-<n>` y la ausencia de `category=` en cada punto de log de `core`. Fallaron por compilación antes de existir `ErrorCode`. Desviación: las dos clases no usan `StepVerifier` para el log, que es un efecto lateral; `StepVerifier` se usa para el `Mono` como en el resto de la clase. En `core` el código viaja en el texto del mensaje porque `LogFields` es de `infrastructure`.
- [x] T005 [P] [US2] `infrastructure/src/test/java/.../adapter/in/rest/NotificationExceptionHandlerTest.java`: cada excepción mapeada devuelve `code` del catálogo con `message` y `correlationId`; una excepción no catalogada devuelve el genérico de su categoría y nunca `getMessage()` (FR-011, FR-012); debe fallar

  Hecho: `NotificationExceptionHandlerTest` ampliada (11 pruebas): cada excepción mapeada devuelve su `code`, una excepción no catalogada devuelve `NTF-5000` con mensaje genérico sin `getMessage()`, un `ResponseStatusException` 5xx usa el genérico de infraestructura y cada respuesta mapeada se registra una vez con `errorCode` y `failureCategory`. Desviación: dos aserciones previas que exigían "ningún log" en respuestas mapeadas (`aResponseStatusException...` y `existingMappingsAreUnchanged`) se modificaron, porque FR-011 y SC-002 exigen que el log lleve el mismo código que la respuesta.
- [x] T006 [P] [US2] Prueba de contrato `infrastructure/src/test/java/.../adapter/in/rest/ErrorResponseContractTest.java`: el esquema `ErrorResponse` del YAML tiene `code` obligatorio con la enumeración igual a `ErrorCode.values()` y todas las respuestas de error lo referencian

  Hecho: `ErrorResponseContractTest` (4 pruebas, lee el YAML con SnakeYAML): `code` y `correlationId` obligatorios, enumeración igual a `ErrorCode.values()`, todas las respuestas 4xx y 5xx de las operaciones referencian `ErrorResponse` y también las respuestas reutilizables de `components`.

### Implementación (E1)

- [x] T007 [US2] Contrato primero: añadir `code` (enum `NTF-<n>`, `required`) a `ErrorResponse` en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` y revisar que ninguna respuesta de error defina un esquema propio (Principio II, `contracts/api-notificaciones-cambios.md`)

  Hecho: `ErrorResponse` gana `code` (enum con los 44 códigos, `required`) en `api-notificaciones.yaml`, y la respuesta 413 de `POST /notifications`, que no declaraba cuerpo, ahora referencia `ErrorResponse`. Ninguna respuesta define esquema de error propio.
- [x] T008 [US2] Crear `U/ErrorCode.java` (entero estable, `format()`, `category()` explícito, rangos Q4 y genéricos 3000/4000/5000) con los códigos de todos los puntos de fallo actuales

  Hecho: `utils/ErrorCode.java` con 44 códigos (1xxx a 5xxx, genéricos 3000, 4000 y 5000, `format()`, `category()` explícito y `genericFor(FailureCategory)`).
- [x] T009 [US2] Sustituir los mensajes `category=` por `ErrorCode` en `C/usecase/DispatchNotificationService.java` y `C/usecase/RequeuePendingNotificationsService.java`; las excepciones de dominio pueden llevar el código sin depender de frameworks

  Hecho: `DispatchNotificationService` y `RequeuePendingNotificationsService` usan `errorCode=NTF-<n>` en lugar de `category=`; `REQUEUE_FAILED`, `ENQUEUE_FAILED`, `REQUEUE_PASS_FAILED`, `DISPATCH_STUCK_IN_PROCESS_RELEASED`, `DISPATCH_RESERVATION_NOT_RELEASED` y `DISPATCH_EVENTS_NOT_PUBLISHED`. Desviación: las excepciones de dominio no llevan el código (el plan lo dejaba como posibilidad); el handler las mapea por tipo, sin cambiar `core`.
- [x] T010 [US2] `I/adapter/in/rest/NotificationExceptionHandler.java` y el record de respuesta de error: mapear cada excepción a su `ErrorCode` y emitir `code`; genérico por categoría para lo no catalogado

  Hecho: `NotificationExceptionHandler` centraliza la respuesta en `respond(...)`, que emite `code` y registra cada error mapeado en INFO (4xx) o WARN (5xx) con `errorCode`, `failureCategory`, `status` y `correlationId`; lo no catalogado usa `ErrorCode.genericFor(RECOVERABLE_INFRASTRUCTURE)`. `AuthenticationWebFilter` también emite `code` (`NTF-2001` y `NTF-2002`) y lo registra. `ErrorResponse` pasa a `(code, message, correlationId)`.
- [x] T011 [P] [US2] Registrar `errorCode` y `failureCategory` (derivada del código) vía `LogFields` en `I/adapter/out/provider/ProviderLogs.java`, `I/adapter/in/rabbit/NotificationDispatchListener.java`, `ManualAckSettler.java`, `AttachmentScanListener.java` y el adaptador de MinIO; añadir `errorCode` a `LogFields` y a `logback-spring.xml`

  Hecho: `LogFields.failure(ErrorCode, ...)` registra `errorCode` y `failureCategory` derivada del código; sustituye a `fields(FAILURE_CATEGORY, ...)` en `ProviderLogs`, `NotificationDispatchListener`, `ManualAckSettler`, `AttachmentScanListener`, `NotificationRabbitPublisher`, `NotificationUpdatesRabbitAdapter`, `AbandonedUploadsSchedulerAdapter`, `ConfigurationEventLogger` y `MinioAttachmentStorageAdapter`. Desviación: `logback-spring.xml` no cambia, porque `errorCode` viaja como campo de marcador y el codificador JSON ya lo serializa; solo las claves de MDC requieren `includeMdcKeyName`.
- [x] T012 [US2] Ejecutar `spotless:apply` y las clases de T003-T006 en verde

  Hecho: `spotless:apply` ejecutado; `ErrorCodeTest` (6), `NotificationExceptionHandlerTest` (11), `ErrorResponseContractTest` (4), `DispatchNotificationServiceTest` y `RequeuePendingNotificationsServiceTest` en verde, junto con `AuthenticationWebFilterTest` (27), `ManualAckSettlerTest`, `NotificationDispatchListenerTest`, `AttachmentScanListenerTest`, `NotificationRabbitPublisherLoggingTest` y `StructuredLogLayoutTest`.
- [x] T013 [US2] E2E `infrastructure/src/test/java/.../ErrorCodeE2ETest.java` (`@SpringBootTest` RANDOM_PORT, Mongo, RabbitMQ, `WebTestClient`): validación, 401/403, rechazo permanente del proveedor simulado y fallo recuperable; en cada caso `code` de la respuesta = `errorCode` del log = catálogo (SC-002); mensajes publicados con `NotificationEventPublisherPort.publish`

  Hecho: `ErrorCodeE2ETest` (5 pruebas, Mongo y RabbitMQ reales, `WebTestClient`) en verde: validación (`NTF-1003`), 401 (`NTF-2001`) y 403 (`NTF-2002`) con `code` de la respuesta = `errorCode` del log con el mismo `correlationId` y la categoría del catálogo; rechazo permanente (`NTF-3020`) y fallo recuperable (`NTF-4002`) con un `NotificationSenderPort` controlable, esperando el estado final por la API. Desviación: el proveedor simulado no sirve porque su resultado es fijo por contexto; la prueba registra un sender `controllable` y el flujo de aceptación es el real (el publicador real emite los eventos, no se arma ningún mensaje a mano).
- [x] T014 [US2] Entrega E1: `./mvnw -B -ntp verify` completo en verde (en segundo plano, timeout alto; anotar cualquier exclusión de las dos pruebas de DLQ) y commit `feat(errorcode): catalogo ErrorCode y campo code en errores (020)`

  Hecho: `./mvnw -B -ntp clean verify` completo en verde (BUILD SUCCESS): utils 35, core 688, infrastructure 866 pruebas, 0 fallos, 0 errores; Spotless, SpotBugs y umbrales de JaCoCo en verde; sin exclusiones, las dos pruebas de DLQ incluidas. Se usó el broker del `docker-compose` del proyecto en el 5673 con `RABBITMQ_USERNAME=notification` (con `guest` fallaban por autenticación, no por el código).

**Checkpoint E1**: ErrorCode desplegable por sí solo.

---

## Phase 3: E2 - Métricas y exposición de actuator (US1, P1)

**Goal**: métricas técnicas y de negocio en Prometheus por pull, en el puerto de gestión 8061, sin exponer nada más de actuator.

**Independent Test**: enviar notificaciones con cada resultado simulado y leer `/actuator/prometheus` en 8061; los contadores coinciden exactamente.

### Pruebas primero (E2)

- [x] T015 [P] [US1] Unitarias con `StepVerifier` en `core/src/test/java/.../usecase/` de `SendNotificationServiceTest`, `SendNotificationBatchServiceTest`, `DispatchNotificationServiceTest` y `RequeuePendingNotificationsServiceTest` con `NotificationMetricsPort` simulado: una llamada por hecho, la reaceptación idempotente no cuenta, el lote cuenta notificaciones, proveedor deshabilitado cuenta `failed` sin duración (FR-002, FR-003); deben fallar

  Hecho: pruebas ampliadas con `NotificationMetricsPort` simulado en `SendNotificationServiceTest` (25), `SendNotificationBatchServiceTest` (19), `DispatchNotificationServiceTest` (44) y `RequeuePendingNotificationsServiceTest` (26): una llamada por hecho, la reaceptacion idempotente no cuenta, el lote cuenta por notificacion y el proveedor deshabilitado cuenta `failed` sin duracion; escritas antes de la implementacion, en verde en el `verify` final. No se ejecutaron en rojo por la orden de escribir todo antes de ejecutar nada.
- [x] T016 [P] [US1] `infrastructure/src/test/java/.../adapter/out/metrics/MicrometerNotificationMetricsTest.java` con `SimpleMeterRegistry`: nombres y etiquetas de `contracts/metricas-y-trazas.md` (`accepted`, `attempts`, `dispatched`, `provider.duration`, `errors`), cardinalidad cerrada y ninguna etiqueta prohibida (FR-008), con control positivo de que sí se emiten; debe fallar

  Hecho: `MicrometerNotificationMetricsTest` (7 pruebas, `SimpleMeterRegistry`): nombres y etiquetas del contrato, cardinalidad cerrada, ninguna etiqueta prohibida y control positivo. Desviacion: la prueba del histograma usa `PrometheusMeterRegistry`, porque `SimpleMeterRegistry` no emite histograma agregable. En verde.
- [x] T017 [P] [US1] `infrastructure/src/test/java/.../ActuatorExposureE2ETest.java`: con puertos 8060 y 8061 reales, `health` (liveness y readiness) y `prometheus` responden solo en 8061 (control positivo); `env`, `beans`, `heapdump`, `configprops`, `loggers`, `threaddump`, `mappings`, `metrics` e `info` no responden contenido ni en 8061 ni en 8060; 8060 no sirve ninguna ruta de actuator (FR-023 a FR-025, SC-009); debe fallar

  Hecho: `ActuatorExposureE2ETest` (9 pruebas, puertos 8060 y 8061 reales): `health` liveness, readiness y agregado, y `prometheus` responden en 8061 sin credenciales; `env`, `beans`, `heapdump`, `configprops`, `loggers`, `threaddump`, `mappings`, `metrics` e `info` dan 404 en 8061; en 8060 toda ruta de actuator da 401 sin token y 404 con token valido; la API de negocio no responde en 8061. Desviacion: la clase lleva `@AutoConfigureObservability`, porque `@SpringBootTest` desactiva por defecto el exportador Prometheus. En verde.
- [x] T018 [P] [US1] Actualizar `infrastructure/src/test/java/.../adapter/in/web/AuthenticationWebFilterTest.java` y `adapter/in/rest/AuthenticationSecurityE2ETest.java`: `/actuator/**` ya no queda eximido de forma indiscriminada en el puerto principal (FR-024); deben fallar

  Hecho: `AuthenticationWebFilterTest` (30) y `AuthenticationSecurityE2ETest` (4) actualizadas: `/actuator/**` ya no queda eximido en el puerto principal; `AttachmentHealthIndicatorsTest` (3) pasa a `@LocalManagementPort`. Se anadieron 3 pruebas del filtro para el contexto de gestion (exento solo `/actuator` y solo en el contexto hijo). En verde.

### Implementación (E2)

- [x] T019 [US1] Crear `C/port/out/NotificationMetricsPort.java` (métodos `void` con tipos del dominio: aceptada por canal, intento despachado con proveedor, resultado y duración, fallo con `ErrorCode`)

  Hecho: `NotificationMetricsPort` en `core/port/out` sin dependencias de Micrometer, con `notificationAccepted`, `dispatchAttempted`, `providerCalled` y dos sobrecargas de `errorRecorded`.
- [x] T020 [US1] Cablear el puerto en `C/usecase/SendNotificationService.java`, `SendNotificationBatchService.java`, `DispatchNotificationService.java` (duración alrededor de `NotificationSenderPort.send`) y `RequeuePendingNotificationsService.java`

  Hecho: puerto cableado en `SendNotificationService` (el lote lo recorre por delegar en el caso de uso individual, por lo que cuenta por notificacion y la reaceptacion idempotente no cuenta), `DispatchNotificationService` (duracion alrededor de `send`, proveedor deshabilitado cuenta `failed` sin duracion, errores con `ErrorCode`) y `RequeuePendingNotificationsService`. Desviacion: `SendNotificationBatchService` no cambia.
- [x] T021 [US1] Añadir `micrometer-registry-prometheus` al `pom.xml` de `infrastructure` (versión del BOM) y crear `I/adapter/out/metrics/MicrometerNotificationMetrics.java` con `MeterRegistry` y cablearlo en `I/config/UseCaseConfig.java`; temporizador con histograma para p95/p99 (FR-004)

  Hecho: `micrometer-registry-prometheus` anadida a `infrastructure/pom.xml` (version del BOM) y `MicrometerNotificationMetrics` con temporizador con histograma. Desviacion: el adaptador es un `@Component` y no un `@Bean` de `UseCaseConfig`, porque el bean en `config` creaba un ciclo `config` <-> `adapter` que `ModularityTests` rechaza.
- [x] T022 [US1] `infrastructure/src/main/resources/application.yml`: `management.server.port=${MANAGEMENT_PORT:8061}`, `management.endpoints.web.exposure.include=health,prometheus` (lista explícita, sin comodines), `management.endpoint.prometheus.enabled`, histograma de percentiles de `http.server.requests` y de las métricas de listeners Rabbit (FR-006, FR-023)

  Hecho: `application.yml`: `management.server.port=${MANAGEMENT_PORT:8061}`, exposicion `health,prometheus` explicita, `prometheus.enabled`, histograma de `http.server.requests` y `spring.rabbitmq.listener`. `application.properties` de pruebas fija `management.server.port=0`.
- [x] T023 [US1] `I/adapter/in/web/AuthenticationWebFilter.java`: eliminar `/actuator` de las rutas exentas del puerto principal (FR-024); confirmar que el puerto de gestión no hereda el filtro

  Hecho: `/actuator` retirado de las rutas exentas del puerto principal. Hallazgo: el contexto hijo de gestion SI hereda el filtro (respondia 401 en 8061); el filtro ahora exime `/actuator` solo cuando `exchange.getApplicationContext().getParent() != null`, es decir, en el contexto de gestion, y las pruebas lo afirman.
- [x] T024 [P] [US1] Despliegue (FR-026): documentar el puerto 8061, `MANAGEMENT_PORT` y `/actuator/prometheus` en `README.md`; exponer y comprobar salud en 8061 en `docker-compose.yml` (sin publicarlo fuera si no hace falta); añadir `EXPOSE 8061` al `Dockerfile` junto a 8060; revisar que las sondas de `specs/` y `quickstart.md` apunten a 8061

  Hecho: README con la seccion 'Metricas y puerto de gestion' (8061, `MANAGEMENT_PORT`, `/actuator/prometheus`, endpoints no expuestos) y `EXPOSE 8060 8061` en el `Dockerfile`. Desviacion: `docker-compose.yml` no cambia, porque solo levanta MongoDB, RabbitMQ, MinIO y ClamAV y no contiene el servicio, asi que no hay nada que exponer ni comprobar alli. `quickstart.md` de la 020 ya apuntaba a 8061; las sondas de specs anteriores no se tocan.
- [x] T025 [US1] Ejecutar `spotless:apply` y las clases de T015-T018 en verde, más `HexagonalArchitectureTest` y `ModularityTests` (core sin Micrometer)

  Hecho: `spotless:apply` ejecutado; las clases de T015-T018, `HexagonalArchitectureTest` (3) y `ModularityTests` (2) en verde; `core` sin Micrometer.
- [x] T026 [US1] E2E `infrastructure/src/test/java/.../MetricsE2ETest.java`: N notificaciones por canal y por resultado simulado, contadores del endpoint de 8061 exactos (SC-001); dos tenants sin `tenantId` en ninguna etiqueta (SC-008); datos centinela (destinatario, contenido, credencial, token) con 0 apariciones junto a control positivo (SC-005); la consulta responde en `Duration` ≤ 2 s afirmada (SC-004); existen `http.server.requests` con percentiles, métricas del consumo Rabbit y de JVM (FR-006, escenario 4 de US1)

  Hecho: `MetricsE2ETest` (5 pruebas, Mongo y RabbitMQ reales): contadores exactos por canal y resultado, dos tenants sin etiqueta `tenantId`, datos centinela con 0 apariciones y control positivo, consulta en `Duration` <= 2 s afirmada, y `http.server.requests` con percentiles, metricas del consumo Rabbit y JVM. Lleva `@AutoConfigureObservability`. En verde.
- [x] T027 [US1] Entrega E2: `./mvnw -B -ntp verify` completo en verde y commit `feat(metricas): metricas de negocio y puerto de gestion 8061 (020)`

  Hecho: `./mvnw -B -ntp clean verify` completo en verde (BUILD SUCCESS, 27:32 min): utils 35, core 709, infrastructure 890 pruebas, 0 fallos, 0 errores; umbrales de JaCoCo cumplidos en los tres modulos, Spotless y SpotBugs en verde; sin exclusiones, las dos de DLQ incluidas, con el broker del compose en 5673 y usuario `notification`.

**Checkpoint E2**: métricas y exposición mínima de actuator desplegables.

---

## Phase 4: E3 - Trazado con Micrometer Tracing y OpenTelemetry (US3, P2)

**Goal**: tramos REST, publicación, consumo, despacho y proveedor en una traza con `correlationId` como atributo y `traceId` en los logs.

**Independent Test**: E2E con exportador en memoria; mismo `traceId` en todos los tramos y logs.

### Pruebas primero (E3)

- [x] T028 [P] [US3] `infrastructure/src/test/java/.../config/ObservationCorrelationFilterTest.java`: el `correlationId` validado se añade como atributo del tramo, los demás valores pasan por `LogSanitizer`, ningún atributo contiene destinatario, contenido, credenciales ni tokens (FR-016, FR-018); debe fallar
- [x] T029 [P] [US3] `infrastructure/src/test/java/.../adapter/out/rabbit/NotificationRabbitPublisherTest.java`: el mensaje publicado lleva una sola cabecera `traceparent` válida proveniente de la observación y ninguna estampada a mano; debe fallar
- [x] T030 [P] [US3] `infrastructure/src/test/java/.../TracingResilienceE2ETest.java`: sin endpoint OTLP el servicio arranca sin exportador ni reintentos; con endpoint inalcanzable la aceptación y el despacho no fallan, no pierden notificaciones y el p95 de aceptación no empeora más de 10 % frente a la línea base medida en la misma prueba, con aserción de `Duration` (FR-017, SC-006); debe fallar

### Implementación (E3)

- [x] T030a [US3] Añadir al `pom.xml` de `infrastructure` (versiones del BOM) `micrometer-tracing-bridge-otel` y `opentelemetry-exporter-otlp`, y en alcance test `micrometer-tracing-test` y `opentelemetry-sdk-testing`
- [x] T031 [US3] `I/config/ObservabilityConfig.java` (nuevo): observación en REST, `RabbitTemplate`, contenedor de listeners (`observation-enabled`) y `ObservationFilter` del `correlationId`; `spring.reactor.context-propagation=auto` verificando que no choca con `Hooks.enableAutomaticContextPropagation()` ni con `CorrelationContextConfig`
- [x] T032 [US3] Retirar el estampado y la restauración manuales de `traceparent` en `I/adapter/out/rabbit/NotificationRabbitPublisher.java`, `I/adapter/in/rabbit/NotificationDispatchListener.java`, `AttachmentScanListener.java`, `I/adapter/in/web/CorrelationIdWebFilter.java`, `I/config/CorrelationContext.java`, `LogContext.java` y `LogFields.java`; el tramo de consumo se cierra tras el ack/nack de `ManualAckSettler`
- [x] T033 [US3] Tras T032, buscar consumidores de `U/TraceParent.java` con `Grep`; si queda alguno de producción distinto de pruebas, conservarlo; si no, eliminar `TraceParent` y `TraceParentTest.java` junto con sus pruebas y las de `AttachmentScanListenerCorrelationTest`, `NotificationDispatchListenerTest`, `NotificationControllerTest` y `CorrelationIdWebFilterTest` que los usen (Principio VII)
- [x] T034 [US3] Verificar que los `WebClient` de Brevo, Twilio y FCM (`I/adapter/out/provider/ProviderHttpClients.java`, `FcmProviderConfig.java`) usan el `WebClient.Builder` autoconfigurado y ajustar si no
- [x] T035 [US3] `application.yml` y `logback-spring.xml`: `management.otlp.tracing.endpoint` vacío por defecto (sin exportador), transporte OTLP/HTTP, `management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:1.0}` en local y 0.1 en el perfil de producción (Q7); `traceId` y `spanId` del MDC en el JSON del log
- [x] T036 [US3] Ejecutar `spotless:apply` y las clases de T028-T030 en verde
- [x] T037 [US3] E2E `infrastructure/src/test/java/.../TracingE2ETest.java` con exportador en memoria: con `traceparent` válido, sin él y con uno inválido (se descarta sin escribirse en logs); 100 % de tramos (REST, publicación, consumo, despacho, proveedor) y de logs del recorrido con el mismo `traceId` (SC-003); `correlationId` como atributo del tramo; dos solicitudes concurrentes sin mezcla (SC-008); un reencolado del planificador inicia traza propia y conserva el `correlationId` persistido; mensajes producidos con `NotificationEventPublisherPort.publish`
- [x] T038 [US3] E2E integrado `infrastructure/src/test/java/.../ObservabilityE2ETest.java`: aceptación, publicación, consumo, despacho simulado, lectura de métricas, trazas y logs, afirmando los tres señales con el mismo `correlationId` y `traceId` (FR-022)
- [x] T039 [US3] Entrega E3: `./mvnw -B -ntp verify` completo en verde y commit `feat(trazado): micrometer tracing y opentelemetry con correlacion (020)`

**Checkpoint E3**: trazado desplegable; el `traceparent` manual de la 016 ya no existe.

---

## Phase 5: E4 - Correlación con proveedores (US4, P3)

**Goal**: reenviar el `correlationId` solo donde la documentación vigente del proveedor lo admita.

**Independent Test**: servidor HTTP simulado por proveedor; la solicitud saliente lleva el id solo en los que lo soportan.

- [x] T040 [US4] PRIMERA TAREA: verificar en la documentación vigente de Brevo (API v3 `smtp/email`: `tags`, `headers`, `params`), Twilio (Messages API) y FCM (HTTP v1) si admiten enviar o recibir un identificador de correlación útil y no visible para el destinatario; completar la tabla de `research.md` (D5) con fuente y fecha de consulta por proveedor y el resultado "soportado" o "no soportado por el proveedor"; no se escribe código antes ni se asume soporte (FR-019, Q6)
- [x] T041 [P] [US4] Para cada proveedor soportado según T040, prueba `infrastructure/src/test/java/.../adapter/out/provider/<Proveedor>CorrelationForwardingTest.java` con servidor HTTP simulado: la solicitud lleva el `correlationId` persistido y validado por `CorrelationId`; id inválido o ausente no se envía y se usa el persistido; reencolado reenvía el mismo id (FR-020, FR-021); debe fallar
- [x] T042 [P] [US4] Para cada proveedor no soportado según T040, prueba equivalente que afirma que la solicitud saliente no lleva el id (SC-007), con el control positivo de T041 en la misma clase de prueba o suite
- [x] T043 [US4] Implementar el reenvío solo en los adaptadores soportados (previsiblemente `I/adapter/out/provider/BrevoEmailRequest.java` y `BrevoNotificationProvider.java`, conservando `Idempotency-Key`), con `Notification.correlationId` y `CorrelationId`; log del intento que permita cruzar `correlationId` con el id de mensaje del proveedor
- [x] T044 [US4] Registrar en `research.md` los proveedores "no soportado por el proveedor" con su evidencia; confirmar que no se abre excepción del Principio VII (si T040 muestra ninguno o solo Brevo) y avisar al usuario del resultado
- [x] T045 [US4] E2E `infrastructure/src/test/java/.../ProviderCorrelationE2ETest.java`: flujo completo con proveedor HTTP simulado y `correlationId` conocido; 100 % de solicitudes con id en los soportados, 0 en los demás (SC-007)
- [x] T046 [US4] Entrega E4: `./mvnw -B -ntp verify` completo en verde y commit `feat(proveedores): reenvio del correlationId a proveedores soportados (020)`

---

## Phase 6: Cierre

- [x] T047 Verificación final de la historia: `./mvnw -B -ntp verify` completo (en segundo plano), cobertura ≥80 % líneas y ≥70 % ramas, Spotless y SpotBugs en verde, `HexagonalArchitectureTest` y `ModularityTests` en verde; ejecutar `quickstart.md` contra el servicio y reportar tareas sin marcar, desviaciones y las dos pruebas de DLQ si se excluyeron
  - Resultado 2026-10-07: el CI del PR #63 pasó en verde el job `Test` (`./mvnw test` completo, con `HexagonalArchitectureTest` y `ModularityTests`, sin excluir ninguna prueba de DLQ) y el job `Code Quality` (Spotless, SpotBugs y los umbrales de cobertura con los datos de ese `Test`), además de SonarCloud, CodeQL y Trivy. El `verify` local no quedó en verde por el entorno: cinco clases (`NotificationControllerSearchE2ETest`, `CorsConfigTest`, `CorsConfigCustomOriginTest`, `DeadLetterQueueE2ETest`, `RabbitRetryConfigCustomAttemptsTest`) no cargan contexto porque el RabbitMQ fijo de `localhost:5673` rechaza las credenciales de prueba, y `TracingResilienceE2ETest` falló una vez por carga de la máquina y pasó al repetirla sola; por eso el porcentaje de cobertura no se leyó en local y se da por cumplido por el job `Code Quality`.
  - Desviaciones: el primer CI falló en `ErrorCodeE2ETest` (el log se emite después de guardar el estado) y en `DispatchIdempotencyE2ETest` (de la 017: el fallo de encolado inyectado lo podía consumir otra notificación huérfana); se corrigieron en `3e2be62`. No quedan tareas sin marcar en este archivo. El quickstart se ejecutó completo; el resultado y dos observaciones están en `quickstart.md`.

---

## Dependencias y orden

- Setup (T001-T002) antes de todo. E1 -> E2 (las métricas de error usan `ErrorCode`) -> E3 -> E4; E4 solo depende de T040 y de E1.
- Dentro de cada entrega: pruebas (fallan) -> contrato (si aplica) -> implementación -> `spotless:apply` -> E2E -> `verify` -> commit.
- Paralelas: T003-T006, T015-T018, T028-T030, T041-T042; T024 en paralelo con T019-T023.

## Estrategia

MVP: E1 + E2 (ambas P1). E3 y E4 se entregan después, cada una verificada con `verify` antes de la siguiente. Cualquier desviación del plan se anota en la tarea correspondiente y solo el autor marca los checkboxes.
