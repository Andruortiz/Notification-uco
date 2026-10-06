# Implementation Plan: Observabilidad completa (020)

**Branch**: `feature/020-observabilidad-completa` | **Date**: 2026-10-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/020-observabilidad-completa/spec.md`

## Estado del plan

**Estado**: Aceptado

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

La 020 entrega las cuatro excepciones del Principio VII que dejó la 016: métricas técnicas y de negocio,
catálogo `ErrorCode`, trazado con Micrometer Tracing y OpenTelemetry, y reenvío del `correlationId` a los
proveedores que lo admitan. El usuario CONFIRMÓ las decisiones Q1 a Q8 de `spec.md` el 2026-10-05, tal como las decidió, y el plan
refleja esas decisiones (resumen en la sección "Decisiones confirmadas").

Enfoque: `core` solo gana un puerto de salida `NotificationMetricsPort` y usa `ErrorCode` (de `utils`);
todo lo de Micrometer, Prometheus, OpenTelemetry y OTLP vive en `infrastructure`.

## Decisiones confirmadas (usuario, 2026-10-05)

- Q1: Prometheus por pull en `/actuator/prometheus`.
- Q2: puerto de gestión 8061 separado. No se exime `/actuator/**` indiscriminadamente en
  `AuthenticationWebFilter`: el puerto de gestión expone solo `health` (sondas `liveness` y `readiness`) y
  `prometheus`; la exención del filtro en el puerto principal se limita a lo estrictamente necesario
  (ninguna ruta de actuator). `env`, `beans`, `heapdump`, `configprops`, `loggers`, `threaddump`,
  `mappings` y demás no se exponen ni en 8061 ni en 8060, con pruebas (FR-023 a FR-026, SC-009).
- Q3: métricas `notification.accepted`, `attempts`, `dispatched`, `provider.duration` y `errors` con
  dimensiones canal y proveedor, sin `tenantId`.
- Q4: `enum ErrorCode` en `utils`, entero estable más `NTF-<n>`, rangos 1xxx a 5xxx por categoría.
- Q5: campo aditivo `code` en `ErrorResponse`.
- Q6: no se asume soporte de proveedores; la primera tarea verifica la documentación vigente de Brevo,
  Twilio y FCM; se implementa solo donde esté soportado y se documenta con evidencia (fuente y fecha) lo
  demás. Si ninguno o solo Brevo, no se abre excepción del Principio VII.
- Q7: OTLP/HTTP solo con endpoint configurado; muestreo 1.0 local y 0.1 producción.
- Q8: `traceId` y `correlationId` con propósitos distintos; sustituye el FR-016 de la spec 016.

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.3.4 (WebFlux, Reactor), Micrometer 1.13, Micrometer Tracing 1.3

**Primary Dependencies**: se añaden en `infrastructure` `micrometer-registry-prometheus`,
`micrometer-tracing-bridge-otel` y `opentelemetry-exporter-otlp` (versiones del BOM de Spring Boot);
en pruebas, `micrometer-tracing-test` o un exportador en memoria de OpenTelemetry

**Storage**: N/A (sin cambios de esquema; `correlationId` ya está persistido)

**Testing**: JUnit 5, Mockito, StepVerifier, Testcontainers (Mongo, RabbitMQ), WebTestClient, ArchUnit;
servidor HTTP simulado por proveedor para el reenvío del id

**Target Platform**: Kubernetes, varias réplicas; puerto de API 8060, puerto de gestión 8061 (Q2)

**Project Type**: microservicio hexagonal (`core`, `infrastructure`, `utils`)

**Performance Goals**: RNF-02 sin degradación (p95 de aceptación ≤ 200 ms; SC-006 admite hasta +10 %
sobre la línea base medida en la misma prueba); RNF-03 sin cambios

**Constraints**: no bloquear hilos reactivos; exportación de trazas asíncrona; sin datos sensibles en
etiquetas ni atributos; cardinalidad de métricas acotada

**Scale/Scope**: 3 canales, 3 proveedores reales más el simulado, 7 eventos de dominio, ~10 clases de
adaptador afectadas, 1 puerto nuevo en `core`

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I. Hexagonal | `core` sin Micrometer ni OpenTelemetry: solo `NotificationMetricsPort` (tipos del dominio) y `ErrorCode` de `utils`. `HexagonalArchitectureTest` y `ModularityTests` en verde. |
| II. Contrato primero | `ErrorResponse.code` y su enumeración se definen en `api-notificaciones.yaml` antes del controller y del handler. El endpoint de métricas no es parte del contrato de la API de negocio; se documenta en `contracts/metricas-y-trazas.md`. |
| III. Sin comentarios | Código nuevo sin comentarios; Javadoc solo si una herramienta lo exige. |
| IV. Pruebas | E2E explícito (tarea propia), pruebas con dos tenants, afirmaciones de `Duration` para SC-004 y SC-006; cobertura ≥80 % líneas y ≥70 % ramas por `verify`. |
| V. Commits | Una línea, español sin tildes, `tipo(ámbito): descripción`. |
| VI. Aprobación | El usuario confirmó Q1 a Q8 y aprobó el plan el 2026-10-05; el Estado del plan es `Aceptado`, por lo que se generan tareas y luego se implementa. |
| VII. Sin soluciones temporales | Cierra las cuatro excepciones de la 016. Lo que un proveedor no soporte se documenta como "no soportado por el proveedor" con evidencia (fuente y fecha); no abre excepción (Q6). Se retira el estampado manual de `traceparent` en favor de la observación. |
| IX. Observabilidad | Completa RNF-10: métricas, código de error y traza; `correlationId` y `traceId` cruzan logs y tramos, y el `correlationId` llega al proveedor que lo admita. |
| RabbitMQ ack manual y DLQ | Sin cambios en el modelo de ack; el tramo de consumo se cierra tras el ack/nack. Sin excepciones. |

Resultado: sin violaciones; sin entradas en Complexity Tracking.

## Diseño

### 1. `ErrorCode` (utils) y su uso

- `utils/ErrorCode`: enum con entero estable, `format()` -> `NTF-<n>` y `category()` -> `FailureCategory`
  como dato explícito. Prueba de catálogo: unicidad de enteros y de textos, categoría no nula, coherencia
  de rangos (Q4), y que cada código tiene un emisor o una prueba que lo emite (FR-014).
- `core`: `DispatchNotificationService` y `RequeuePendingNotificationsService` sustituyen
  `"... category=" + FailureCategory...` por `ErrorCode`; las excepciones de dominio pueden llevar el
  código (sin framework).
- `infrastructure`: `NotificationExceptionHandler` mapea cada excepción a su `ErrorCode` y emite `code`;
  `ProviderLogs`, listeners, `ManualAckSettler`, `AttachmentScanListener` y el adaptador de MinIO
  registran `errorCode` y `failureCategory` mediante `LogFields`. Un fallo sin mapeo usa el código
  genérico de su categoría (5000 infraestructura, 4000 proveedor, 3000 negocio), nunca `getMessage()`.
- Contrato: `ErrorResponse` gana `code` (enum de los códigos del catálogo) en el YAML antes del handler.

### 2. Métricas

- `core/port/out/NotificationMetricsPort` (métodos de negocio: aceptada, intento despachado con
  resultado, fallo con código, duración de proveedor). Retornos `void`: el registro de métricas no puede
  fallar ni volverse reactivo.
- `infrastructure/adapter/out/metrics/MicrometerNotificationMetrics` con `MeterRegistry`; nombres y
  etiquetas según `contracts/metricas-y-trazas.md`. Ningún valor de etiqueta sale de texto libre.
- Cableado en `UseCaseConfig` (`SendNotificationService`, `SendNotificationBatchService` para el conteo
  de aceptadas, `DispatchNotificationService`, `RequeuePendingNotificationsService`).
- Métricas técnicas: se activan `http.server.requests` con histograma de percentiles
  (`management.metrics.distribution.percentiles-histogram`), las del contenedor Rabbit y las de JVM que
  ya autoconfigura Boot; no se escribe código propio para ellas.
- Exposición: `micrometer-registry-prometheus`; `management.endpoints.web.exposure.include=health,prometheus`
  (lista explícita, sin comodines); `management.server.port=${MANAGEMENT_PORT:8061}`. El puerto principal
  no sirve actuator y `AuthenticationWebFilter` elimina la exención indiscriminada de `/actuator`,
  limitándola a lo estrictamente necesario. Pruebas: `env`, `beans`, `heapdump`, `configprops`, `loggers`,
  `threaddump` y `mappings` no responden ni en 8061 ni en 8060; `health` y `prometheus` sí en 8061.
- Despliegue: README, `docker-compose.yml`, `Dockerfile` (`EXPOSE 8061` junto a 8060) y las sondas de la
  plataforma pasan al puerto de gestión (cambio coordinado, ver Riesgos).
- Errores: el contador `notification.errors` lleva `errorCode`, `failureCategory`, `channel` y `provider`
  (estos últimos solo en el despacho).

### 3. Trazado

- Dependencias de Micrometer Tracing + puente OTel + OTLP; `spring.reactor.context-propagation=auto`.
- Observación automática en REST (`ObservationWebFilter`), `RabbitTemplate` y contenedor de listeners
  (`observation-enabled`), y `WebClient` de proveedores (verificar que usen el `WebClient.Builder` de
  Boot; si no, se ajusta la construcción).
- `ObservationFilter` en `infrastructure/config` que añade el `correlationId` (ya validado) como
  atributo de baja cardinalidad del tramo y sanea los demás valores con `LogSanitizer`.
- `logback-spring.xml`: incluir `traceId` y `spanId` del MDC en el JSON.
- `traceparent`: se retira el estampado y la restauración manuales de la cabecera AMQP; la
  observación la propaga. `utils/TraceParent` se conserva solo si sigue habiendo un consumidor (validación
  del valor de entrada); en caso contrario se elimina junto con sus pruebas (Principio VII).
- Sin endpoint OTLP, no hay exportador (propiedad vacía por defecto); el muestreo se lee de
  `OTEL_TRACES_SAMPLER_ARG`/`management.tracing.sampling.probability` (Q7).

### 4. Reenvío a proveedores

- Primera tarea de la entrega: verificar la documentación vigente de Brevo, Twilio y FCM y fijar en
  `research.md`, con fuente y fecha de consulta, qué campo admite cada una (D5); no se asume soporte y
  no se escribe código antes.
- Brevo: añadir el campo de correlación elegido a `BrevoEmailRequest` (junto al `Idempotency-Key`
  existente, que se conserva) con el valor de `Notification.correlationId`, validado por
  `CorrelationId`.
- Proveedor sin soporte verificado (previsiblemente Twilio y FCM): sin cambios de solicitud; queda
  "no soportado por el proveedor" con evidencia en `research.md` y una aserción de que la solicitud
  saliente no lo lleva (SC-007). Si el resultado es ninguno o solo Brevo, no hay excepción del
  Principio VII.

## Project Structure

### Documentation (this feature)

```text
specs/020-observabilidad-completa/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── api-notificaciones-cambios.md
│   └── metricas-y-trazas.md
├── checklists/requirements.md
└── tasks.md          (lo genera /speckit-tasks tras la aprobación; no existe aún)
```

### Source Code (repository root)

```text
utils/src/main/java/co/edu/uco/notification/utils/
└── ErrorCode.java                                   (nuevo)
utils/src/test/java/co/edu/uco/notification/utils/ErrorCodeTest.java

core/src/main/java/co/edu/uco/notification/core/
├── port/out/NotificationMetricsPort.java            (nuevo)
└── usecase/{SendNotificationService,SendNotificationBatchService,
             DispatchNotificationService,RequeuePendingNotificationsService}.java   (cambian)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/out/metrics/MicrometerNotificationMetrics.java   (nuevo)
├── adapter/in/rest/NotificationExceptionHandler.java        (code)
├── adapter/in/rabbit/*, adapter/out/rabbit/*                (errorCode, observación; sin traceparent manual)
├── adapter/out/provider/{BrevoEmailRequest,BrevoNotificationProvider,ProviderLogs}.java
├── adapter/in/web/AuthenticationWebFilter.java              (sin exención indiscriminada de /actuator)
├── config/{UseCaseConfig,ObservabilityConfig(nuevo),CorrelationContextConfig}.java
└── resources/{application.yml,logback-spring.xml,static/openapi/api-notificaciones.yaml}

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── ObservabilityE2ETest.java                        (E2E: métricas, trazas, logs, errores)
├── ActuatorExposureE2ETest.java                     (8061 solo health y prometheus; 8060 nada)
├── adapter/out/metrics/MicrometerNotificationMetricsTest.java
└── adapter/out/provider/*CorrelationForwardingTest.java
```

**Structure Decision**: un puerto de salida en `core` y un adaptador Micrometer en `infrastructure`;
`ErrorCode` en `utils` junto a `FailureCategory`; el trazado es configuración y filtros de
observación en `infrastructure`, sin tocar `core`.

## Estrategia de pruebas

| Nivel | Prueba | Afirma |
|---|---|---|
| Unitaria `utils` | `ErrorCodeTest` | Unicidad, categoría, formato `NTF-n`, rangos (FR-010, FR-014) |
| Unitaria `core` | pruebas de los cuatro casos de uso con `NotificationMetricsPort` simulado, `StepVerifier` | Una llamada por hecho; reaceptación idempotente no cuenta; lote cuenta notificaciones (FR-002, FR-003) |
| Integración adaptador | `MicrometerNotificationMetricsTest` con `SimpleMeterRegistry` | Nombres, etiquetas cerradas, sin etiquetas prohibidas (FR-008) |
| Integración proveedor | servidor HTTP simulado por proveedor | Brevo lleva el id; Twilio y FCM no (SC-007) |
| E2E | `ObservabilityE2ETest` (`@SpringBootTest`, Mongo, RabbitMQ, `WebTestClient`, exportador de trazas en memoria) | SC-001 a SC-005, SC-008 con dos tenants; mismo `correlationId` y `traceId` en log, tramos y métricas; el puerto público no sirve métricas; `Duration` afirmada para el endpoint de métricas (SC-004) |
| E2E exposición | `ActuatorExposureE2ETest` (puertos 8060 y 8061 reales) | `env`, `beans`, `heapdump`, `configprops`, `loggers`, `threaddump`, `mappings` ausentes en ambos; `health` y `prometheus` en 8061 (FR-023 a FR-025, SC-009) |
| Resiliencia | arranque con endpoint OTLP inalcanzable | La aceptación y el despacho no fallan ni se retrasan (SC-006) |
| Arquitectura | `HexagonalArchitectureTest`, `ModularityTests` | `core` sin Micrometer ni OpenTelemetry |

Control positivo: la prueba de "ningún dato sensible en métricas/atributos" corre junto a una que
demuestra que las métricas sí se emiten. Los mensajes de las pruebas de consumo se producen con
`NotificationEventPublisherPort.publish`, no con JSON armado a mano.

## Riesgos

1. **Mover las sondas al puerto de gestión** rompe los manifiestos de la plataforma si no se coordina.
   Mitigación: decisión confirmada (Q2); tareas explícitas de README, compose y Dockerfile; aviso al
   equipo de plataforma en el informe de la entrega.
2. **Doble origen de `traceparent`** (manual de la 016 y observación). Mitigación: retirar el manual
   en la misma historia y probar que un mensaje lleva una sola cabecera válida.
3. **Contexto Reactor y `.block()`** en listeners con ack manual: el tramo puede cerrarse antes del ack
   o perder el contexto. Mitigación: prueba E2E de continuidad de `traceId` publicación a consumo.
4. **Proveedores sin campo de correlación** (probablemente Twilio y FCM): el requisito condicional se
   cumple solo donde la documentación vigente lo admita; el resto se documenta con evidencia y no abre
   excepción (Q6).
5. **Cambio de contrato `ErrorResponse`**: aditivo, pero los clientes con esquema cerrado se ven
   afectados. Mitigación: documentar en `contracts/api-notificaciones-cambios.md` y verificar
   compatibilidad.
6. **Rendimiento**: histogramas y trazado añaden costo por solicitud. Mitigación: muestreo, exportación
   asíncrona, y la medición de SC-006 en la propia prueba.
7. **Cardinalidad**: un código nuevo o un proveedor nuevo agrega series; mitigado por catálogo cerrado
   y la prueba de etiquetas prohibidas.
8. **Alcance grande** (cuatro entregas independientes): se ordenan por valor y dependencia, y cada una
   es desplegable sola: ErrorCode, luego métricas (usa `errorCode`), luego trazado, luego proveedores.
9. **Local con Docker** (Testcontainers 1.19.8 y Engine 29): requiere `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`;
   `verify` completo tarda ~10 minutos.

## Complexity Tracking

Sin violaciones que justificar.
