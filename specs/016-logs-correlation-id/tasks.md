# Tasks: Logs estructurados con identificador de correlación (HU2-056)

**Input**: `specs/016-logs-correlation-id/` (spec.md, plan.md aceptado, research.md, data-model.md, contracts/openapi-delta.md, quickstart.md)

**Formato**: `- [ ] Txxx [P?] [USn?] descripción con ruta`. Orden por tarea: prueba, verla fallar, implementar, ejecutar la clase, `spotless:apply`. Cada bloque (B1..B9) cierra con un commit local de una línea.

**Nota de entorno**: no hay Docker local. Las pruebas marcadas (DOCKER) se escriben pero no se ejecutan aquí; T036 queda abierta hasta que CI ejecute `verify` completo.

## Phase 1: Contrato primero (B1)

- [x] T001 Aplicar `contracts/openapi-delta.md` en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: componente `CorrelationIdHeader` y `traceparent` en todas las respuestas (incluidas 4xx/5xx y SSE), parámetros de solicitud `X-Correlation-Id` y `traceparent`, `correlationId` en `ErrorResponse` (requerido) y `NotificationStatusResponse` (opcional).

## Phase 2: Fundamentos en utils (B2)

- [x] T002 [P] Confirmar `CorrelationId` y `CorrelationIdTest` existentes en `utils/src/main/java/co/edu/uco/notification/utils/CorrelationId.java` y añadir casos de salto de línea y longitud 65 en `utils/src/test/.../CorrelationIdTest.java`.
- [x] T003 [P] Prueba y clase `TraceParent` (valida formato W3C, `fromOrNull`, constantes de cabecera HTTP/AMQP/MDC) en `utils/src/main/java/co/edu/uco/notification/utils/TraceParent.java` y `utils/src/test/.../TraceParentTest.java`.
- [x] T004 [P] Prueba y clase `LogSanitizer` (`maskRecipient`, `redact`, `safe` con neutralización de caracteres de control y límite de longitud) en `utils/src/main/java/co/edu/uco/notification/utils/LogSanitizer.java` y `LogSanitizerTest.java`.
- [x] T005 [P] Enum `FailureCategory` (`RECOVERABLE_PROVIDER`, `RECOVERABLE_INFRASTRUCTURE`, `PERMANENT_BUSINESS`) en `utils/src/main/java/co/edu/uco/notification/utils/FailureCategory.java`.

## Phase 3: US1 núcleo, aceptación y eventos con identificador (B3, B4)

**Objetivo**: el id nace en la aceptación y viaja en agregado, eventos, vista de estado y persistencia.

- [x] T006 [US1] Pruebas en `core/src/test/.../domain/NotificationTest.java`: `Notification.accept(routing, details, correlationId)` fija el id y `NotificationAccepted` lo lleva; todos los eventos posteriores (`markQueued`, `markDelivered`, `markRecoverable`, `markFailed`, `markRetriesExhausted`, `requeue`, `discard`) llevan el id persistido y el `tenantId`.
- [x] T007 [US1] Implementar en `core/src/main/java/co/edu/uco/notification/core/domain/Notification.java`: sobrecarga `accept` con id, eliminar `assignCorrelationId` (H12); añadir `correlationId` y `tenantId` a `DomainEvent` y a los 7 records en `core/src/main/java/co/edu/uco/notification/core/domain/event/`, y actualizar sus pruebas.
- [x] T008 [US1] `SendNotificationService` y `SendNotificationBatchService` pasan el id del comando a `accept`; pruebas con `StepVerifier` en `core/src/test/.../usecase/SendNotificationServiceTest.java` y `SendNotificationBatchServiceTest.java` (id del comando persistido; lote conserva el id por elemento).
- [x] T009 [US1] [P] `NotificationStatusView` expone `correlationId`; prueba en `core/src/test/.../usecase/GetNotificationStatusServiceTest.java`.
- [x] T010 [US1] Persistencia: pruebas de roundtrip del mapper con id no nulo y documento antiguo sin campo en `infrastructure/src/test/.../adapter/out/mongo/NotificationDocumentMapperTest.java` y ampliar `NotificationMongoAdapterTest.java` (DOCKER); ajustar `NotificationDocument.java` y `NotificationDocumentMapper.java`.
- [x] T011 Ejecutar `./mvnw -B -ntp -pl utils,core test`, `spotless:apply`; commits: `feat(utils): LogSanitizer, TraceParent y FailureCategory (HU2-056)` y `feat(core): correlationId y tenantId en eventos y agregado (HU2-056)` y `feat(persistencia): persistir correlationId de la notificacion (HU2-056)`.

## Phase 4: US2 formato estructurado y contexto (B5)

**Objetivo**: JSON de una línea, campos independientes, MDC sin fuga entre peticiones.

- [x] T012 [US2] Añadir `net.logstash.logback:logstash-logback-encoder` a `infrastructure/pom.xml` (versión gestionada con propiedad en el POM raíz si procede).
- [x] T013 [US2] Prueba `StructuredLogLayoutTest` en `infrastructure/src/test/.../config/`: cada línea parsea como JSON; incluye `timestamp`, `level`, `logger`, `message`, `correlationId`, `tenantId`, `notificationId`, `traceparent`; una excepción con valor centinela sale sanitizada.
- [x] T014 [US2] Reemplazar `infrastructure/src/main/resources/logback-spring.xml` por salida JSON (appender asíncrono) y crear el converter de throwable sanitizado en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/SanitizingThrowableConverter.java`.
- [x] T015 [US2] Pruebas y clase `LogContext` (abre `correlationId`, `tenantId`, `notificationId` en MDC y restaura el estado previo) en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/LogContext.java`; ampliar `CorrelationContext` y `CorrelationContextConfig` para `tenantId`, `notificationId` y `traceparent`.
- [x] T016 [US1] Ampliar `CorrelationIdWebFilter` y `CorrelationIdWebFilterTest`: `traceparent` válido/inválido, id con salto de línea descartado, cabecera presente en 401 de `AuthenticationWebFilter`, y **ausencia de fuga**: dos peticiones concurrentes con ids distintos nunca ven el id ajeno en MDC (FR-014, H8).
- [x] T017 [US2] `AuthenticationWebFilter` agrega `tenantId` al contexto tras autenticar y los rechazos 401/403 se registran en WARN con `correlationId`; ajustar `AuthenticationWebFilterTest`.
- [x] T018 Commits: `feat(logs): salida JSON y contexto de correlacion (HU2-056)`.

## Phase 5: US1 mensajería, planificador y listeners (B6)

- [x] T019 [US1] `NotificationRabbitPublisher`: `publish(events)` toma id y tenant del evento (H7), `enqueueForDispatch` estampa el id persistido y `traceparent`; ampliar `NotificationRabbitPublisherTest` produciendo con `DomainEvent` reales, incluido un evento con id distinto del contexto.
- [x] T020 [US1] `AttachmentScanRequestRabbitPublisher`: igual con prueba en `AttachmentScanRequestRabbitPublisherTest.java`.
- [x] T021 [US1] `NotificationDispatchListener`: eliminar `onMessage(String)`, respaldo `legacy-<uuid>` si falta cabecera, limpiar MDC al terminar; pruebas en `NotificationDispatchListenerTest.java` (con cabecera, sin cabecera, limpieza de MDC tras el mensaje, excepción).
- [x] T022 [US1] `AttachmentScanListener`: mismo tratamiento; pruebas en `AttachmentScanListenerTest.java`.
- [x] T023 [US1] `PendingNotificationSchedulerAdapter`: prueba nueva `PendingNotificationSchedulerAdapterTest.java` (id `sched-` válido contra el patrón; no sobrescribe el id persistido de la notificación reencolada).
- [x] T024 [US1] `NotificationUpdatesRabbitAdapter`: tolerar los campos nuevos del evento; prueba con productor real (`NotificationEventPublisherPort.publish`) en `NotificationUpdatesRabbitAdapterTest.java` (DOCKER).
- [x] T025 Commit: `feat(mensajeria): propagar correlationId y traceparent por RabbitMQ (HU2-056)`.

## Phase 6: US3 y US4 sanitización, niveles y registros de ciclo de vida (B7)

- [x] T026 [US3] Registro de hitos desde el publicador: un log por `DomainEvent` con `correlationId`, `tenantId`, `notificationId`, nivel según política (INFO éxitos, WARN recuperable, ERROR fallo permanente); prueba con appender de captura en `NotificationRabbitPublisherLoggingTest.java`.
- [x] T027 [US3] Migrar las 26 llamadas `LOGGER` de las 9 clases (`AttachmentScanListener`, `AttachmentUploadController`, `AuthenticationWebFilter`, `BrevoNotificationProvider`, `FcmNotificationProvider`, `NotificationController`, `NotificationLiveUpdatesController`, `NotificationUpdatesRabbitAdapter`, `TwilioNotificationProvider`) a campos vía `LogContext` y `LogSanitizer`; retirar `tenantId`/`notificationId` del texto; destinatario siempre con `maskRecipient`; añadir `failureCategory` en fallos; eliminar `AttachmentLogFormatter.safe`, `AuthenticationLogFormatter.safe` y las máscaras de Twilio/FCM en favor de `LogSanitizer`; ajustar sus pruebas.
- [x] T028 [US3] Prueba de centinelas por proveedor (Brevo, Twilio, FCM) con respuesta del proveedor que contiene credencial: la credencial no aparece en la salida; en `infrastructure/src/test/.../adapter/out/provider/ProviderLogSanitizationTest.java`.
- [x] T029 Commit: `feat(logs): sanitizar y estructurar los logs existentes (HU2-056)`.

## Phase 7: US5 cliente conoce el id (B8)

- [x] T030 [US5] `NotificationStatusResponse` y mapper REST exponen `correlationId`; `ErrorResponse` lo incluye en `NotificationExceptionHandler` y `AuthenticationWebFilter`; pruebas en `NotificationControllerTest.java`, `NotificationBatchControllerTest.java` y `NotificationExceptionHandlerTest.java` (ambos controllers pasan el id al comando; el lote lo conserva).
- [x] T031 Commit: `feat(api): exponer correlationId en estado y errores (HU2-056)`.

## Phase 8: Pruebas de extremo a extremo (B9)

- [x] T032 [US1] [US2] [US3] `LogCorrelationE2ETest` (`@SpringBootTest(RANDOM_PORT)`, Mongo + RabbitMQ, `WebTestClient`) en `infrastructure/src/test/.../adapter/in/rest/`: SC-001 todas las entradas del recorrido llevan el id; SC-003 dos tenants concurrentes con control positivo; SC-004 centinelas ausentes; SC-005 parseo JSON; SC-006 id en respuestas y errores; estado expone el id; aserción explícita de `Duration` en la espera del estado terminal (DOCKER).
- [x] T033 [US1] Extender `DeadLetterQueueE2ETest`/`RabbitRetryConfigCustomAttemptsTest` con el id en reintento y descarte (US1.4) (DOCKER, broker `localhost:5673`).
- [x] T034 Commit: `test(logs): e2e de correlacion y sanitizacion (HU2-056)`.

## Phase 9: Cierre

- [x] T035 Actualizar `specs/016-logs-correlation-id/research.md` y `data-model.md` con las decisiones del usuario; `spotless:apply`; `./mvnw -B -ntp -pl utils,core test`; pruebas de infraestructura ejecutables sin Docker; `HexagonalArchitectureTest` y `ModularityTests` (el contexto Spring puede requerir Docker: marcar). Commit: `docs(specs): cerrar tareas de HU2-056`.
- [X] T036 `./mvnw -B -ntp verify` completo con Docker (DOCKER, no ejecutable localmente; lo decide CI).
  Verificado 2026-10-05: CI de `develop` (ejecución del merge de #55, 2026-10-04): Build, Test, Seguridad, Code Quality e Imagen en verde (el job Test corre contra Docker); `verify` local completo con BUILD SUCCESS el 2026-10-04.

## Dependencias

B1 -> B2 -> B3/B4 -> B5 -> B6 -> B7 -> B8 -> B9 -> cierre. T016 depende de T015. T027 depende de T015 y T004. T032 depende de todo lo anterior.
