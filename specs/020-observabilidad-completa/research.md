# Research: Observabilidad completa (020)

Las decisiones Q1 a Q8 de `spec.md` (Clarifications) fueron CONFIRMADAS por el usuario el
2026-10-05; este documento conserva el análisis de alternativas.

## Estado heredado de la 016 (no se duplica)

- Entregado: `CorrelationId` (utils), `CorrelationIdWebFilter`, `CorrelationContext` y puente Reactor a
  MDC, `LogContext`, `LogFields`, `LogSanitizer`, `FailureCategory`, `TraceParent` (solo transporte),
  `logstash-logback-encoder` y `logback-spring.xml` JSON, `x-correlation-id` y `traceparent` en AMQP,
  `Notification.correlationId` persistido, `correlationId` en `ErrorResponse` y en el estado.
- Ya presente en el classpath de `infrastructure`: `spring-boot-starter-actuator`, `context-propagation`
  y `resilience4j-spring-boot3`. Única métrica propia: `notification.configuration.version`
  (`ConfigurationConfig`, `MeterBinder`).
- Exposición actual: solo `health` (grupos `liveness` y `readiness`); `AuthenticationWebFilter` exime
  todo `/actuator`.
- Eventos de dominio llevan `notificationId`, `tenantId`, `correlationId`, `occurredOn`; no llevan
  canal ni proveedor, por eso las métricas de negocio no pueden derivarse solo de los eventos.
- `core` registra fallos con `System.Logger` y texto `category=` (`DispatchNotificationService`,
  `RequeuePendingNotificationsService`).

## D1 — Backend y exposición de métricas (CONFIRMADA 2026-10-05: Prometheus por pull)

| Opción | Pros | Contras |
|---|---|---|
| A. Registro Prometheus, `/actuator/prometheus` (recomendada) | Estándar en Kubernetes; sin colector adicional; Boot lo autoconfigura | Dependencia nueva `micrometer-registry-prometheus` en `infrastructure` |
| B. Exportar métricas por OTLP | Un solo canal de salida con las trazas | Requiere colector con soporte de métricas; menos legible para depurar localmente |
| C. Solo `/actuator/metrics` | Sin dependencia | Formato propio de Boot; no lo consume una plataforma estándar |

Protección (CONFIRMADA 2026-10-05, sin exención indiscriminada de `/actuator/**`): puerto de gestión separado (`management.server.port`, 8061)
con `health` y `prometheus`; la API (8060) deja de exponer actuator. Riesgo: las sondas hoy apuntan
al 8060; moverlas obliga a cambiar los manifiestos de la plataforma. Alternativa de menor impacto:
mantener un solo puerto y exigir autenticación (rol `ADMINISTRADOR`) a `/actuator/prometheus` en lugar
de eximirlo, lo que obliga al scraper a presentar un JWT y deja `health` exento. Se recomienda el
puerto separado; se decide en la confirmación.

## D2 — Cómo instrumentar el negocio sin que `core` conozca Micrometer

| Opción | Pros | Contras |
|---|---|---|
| A. Puerto de salida `NotificationMetricsPort` en `core`, adaptador Micrometer en `infrastructure` (recomendada) | Cumple Principio I; llega a canal, proveedor, resultado y duración; fácil de probar con un registro simple | Cambia constructores de los casos de uso y `UseCaseConfig` |
| B. Decorar `NotificationEventPublisherPort` y contar por evento | No toca los casos de uso | Los eventos no llevan canal ni proveedor; ampliar 7 records es un cambio mayor y acopla eventos a métricas |
| C. Micrometer directo en `core` | Menos código | Viola Principio I |
| D. Instrumentar en los adaptadores (listeners, proveedores) | Sin puerto | La duplicación entre adaptadores y se pierde "aceptada" en REST/lote |

Decisión recomendada: A. Los métodos del puerto trabajan con tipos del dominio
(`ChannelType`, `ProviderId`, `AttemptResult`, `Duration`, `ErrorCode`), nunca con cadenas
libres. La duración de proveedor se mide en `core` alrededor de `NotificationSenderPort.send` o en el
decorador de cada adaptador; se mide en el caso de uso para no repetirla en tres adaptadores.

Cardinalidad: `channel` (3 valores), `provider` (3 + simulado), `result` (4), `errorCode` (cerrado).
`tenantId` excluido: una métrica por tenant crece sin cota; el análisis por tenant se hace con logs.

## D3 — Formato y alcance de `ErrorCode` (CONFIRMADA 2026-10-05)

Estado de los fallos hoy: `FailureCategory` (3 valores), cadenas de texto en `core`, `message`
libre en `ErrorResponse`, motivos en `AttachmentRejectionReason` y `AttemptResult`.

| Opción | Pros | Contras |
|---|---|---|
| A. `enum ErrorCode` en `utils`, entero estable y texto `NTF-<n>` por rangos de categoría (recomendada) | "Catálogo numérico" tal como lo pidió el usuario; ordenable; estable | Hay que evitar que el rango se convierta en lógica; la categoría es dato explícito, no se infiere del rango |
| B. Códigos alfanuméricos por dominio (`NTF-VAL-001`) | Autodescriptivos | No es numérico; el usuario pidió numérico |
| C. Códigos como `String` en la configuración | Editables sin compilar | Sin verificación en compilación; viola "catálogo estable" |

`ErrorCode` vive en `utils` junto a `FailureCategory` (ambos los usa `core`). El mapeo desde
excepciones y motivos concretos ocurre en `infrastructure` (`NotificationExceptionHandler`,
`ProviderLogs`, listeners); `core` solo transporta códigos.

Rangos confirmados: 1xxx validación de entrada y de formato; 2xxx autenticación y
autorización; 3xxx reglas de negocio (idempotencia, adjuntos, límites de lote, preferencias, estado
inválido); 4xxx proveedor (rechazo, tiempo de espera, 5xx, credencial, deshabilitado); 5xxx
infraestructura (MongoDB, RabbitMQ, almacenamiento, configuración).

Compatibilidad del contrato: añadir `code` al `ErrorResponse` es aditivo. Si el cliente trata
`ErrorResponse` con esquema cerrado, la compatibilidad se verifica en el cambio de contrato.

## D4 — Micrometer Tracing y OpenTelemetry

Dependencias (infrastructure): `micrometer-tracing-bridge-otel` y `opentelemetry-exporter-otlp`,
ambas gestionadas por el BOM de Spring Boot 3.3.4; `io.micrometer:micrometer-observation` ya viene
con actuator. Propiedades: `management.tracing.sampling.probability`, `management.otlp.tracing.endpoint`.

Puntos de integración y riesgos:

1. **REST (WebFlux)**: `ObservationWebFilter` de Boot continúa un `traceparent` entrante. Habilitar
   `spring.reactor.context-propagation=auto` (Boot 3.2+) para que el contexto de observación viaje por
   Reactor; ya existe `Hooks.enableAutomaticContextPropagation()` de la 016, hay que verificar que no
   haya doble registro ni choque con `CorrelationContextConfig`.
2. **AMQP**: Spring AMQP 3.x admite observación en `RabbitTemplate` y en el contenedor del listener
   (`observation-enabled`), que propaga `traceparent` por cabecera. Hoy el publicador estampa
   `traceparent` a mano desde `TraceParent`; con trazado real se debe delegar en la
   observación y retirar el estampado manual, para no tener dos orígenes de la misma cabecera
   (Principio VII: nada duplicado ni temporal). Riesgo mayor: los listeners usan `.block()` y ack manual
   (`ManualAckSettler`); el alcance del tramo de consumo hay que cerrarlo tras el ack, no antes.
3. **WebClient hacia proveedores**: el `WebClient.Builder` de Boot ya trae el instrumento de
   observación; los adaptadores construyen su `WebClient` con `Supplier<WebClient>` (Twilio) o
   directo; hay que verificar que usen el builder autoconfigurado.
4. **Planificador y reencolado**: inician traza propia; se enlaza por `correlationId` persistido,
   no por `traceId`.
5. **MDC**: el puente de OTel pone `traceId` y `spanId` en MDC; el `logback-spring.xml` JSON debe
   incluir esas claves. La política de `LogSanitizer` aplica a los atributos de tramos (los
   `KeyValues` de observación se construyen con valores ya sanitizados).
6. **Degradación**: sin endpoint de OTLP no se registra exportador (Boot condiciona el
   autoconfigurado a la propiedad); con el endpoint caído, el procesador por lotes es asíncrono y
   descarta; se prueba con un endpoint inalcanzable.

Esta decisión reemplaza la restricción de la 016: FR-016 de aquella spec obligaba a "no interpretar ni
generar" `traceparent`; aquí se interpreta y se genera. `TraceParent` (utils) deja de ser dueño del
transporte AMQP; se decide en el plan si se conserva solo para validación del valor de entrada o se
elimina.

## D5 — Reenvío del correlationId a los proveedores (CONFIRMADA 2026-10-05: sin asumir soporte)

La capacidad de cada API se verifica contra su documentación vigente en la primera tarea de
implementación; esta tabla es la hipótesis de trabajo, no un hecho verificado.

| Proveedor | Campo candidato | Hipótesis | Riesgo |
|---|---|---|---|
| Brevo (correo, v3 `smtp/email`) | `headers` personalizados y/o `tags` del mensaje | Soportado: admite datos personalizados que el panel y los webhooks devuelven | Una cabecera de correo viaja al destinatario; preferir `tags` (no visibles) y límite de longitud del tag |
| Twilio (SMS) | ninguno propio de correlación; `StatusCallback` con parámetro en la URL | No hay campo que no sea parte de la URL de callback; usar la URL contamina el contrato | No soportado de forma limpia; se documenta |
| FCM (push, HTTP v1) | `data` del mensaje | `data` llega a la app del usuario; no es un campo operativo | No se envía; se documenta |

Decisión del usuario: no se asume soporte; se verifica en la documentación vigente y se implementa solo
donde esté soportado. Si ninguno o solo Brevo lo admite, no se abre excepción del Principio VII: queda
"no soportado por el proveedor" con evidencia (fuente y fecha de consulta) en la sección de
verificación siguiente.

### Verificación en la documentación vigente (se completa en la primera tarea de la entrega 4)

| Proveedor | Fuente consultada | Fecha | Resultado |
|---|---|---|---|
| Brevo | developers.brevo.com/reference/sendtransacemail (campos `tags`, `headers`, `params`) y developers.brevo.com/docs/transactional-webhooks (campo `tags` en los eventos) | 2026-10-07 | Soportado mediante `tags`: arreglo de cadenas, no visible para el destinatario, devuelto en los eventos de los webhooks transaccionales. `headers` se descarta porque viaja en el correo y `params` porque solo sirve para variables de plantilla. La documentación no fija límite de longitud ni de caracteres del tag |
| Twilio | www.twilio.com/docs/messaging/api/message-resource (parámetros de creación de Message) | 2026-10-07 | No soportado por el proveedor: la creación de un Message no tiene campo de metadatos ni de referencia de cliente; el único enlace con el sistema propio es el `sid` devuelto, y `StatusCallback` obliga a poner el id en la URL |
| FCM | firebase.google.com/docs/reference/fcm/rest/v1/projects.messages y referencia de `FcmOptions` del Admin SDK | 2026-10-07 | No soportado como identificador por mensaje: `data` es carga útil entregada a la app del usuario y `analytics_label` agrupa mensajes en las analíticas, no identifica uno. Evidencia incompleta: las páginas consultadas no mostraron el texto de `data` ni los límites de `analytics_label`, así que esta fila se basa en el propósito documentado de los campos, no en una cita |

Resultado de la verificación (2026-10-07): solo Brevo admite un identificador de correlación útil y no visible
para el destinatario (`tags`). Twilio y FCM quedan como "no soportado por el proveedor" con la evidencia de la
tabla anterior. Como solo Brevo lo admite, no se abre excepción del Principio VII; el reenvío se implementa
únicamente en el adaptador de Brevo y las pruebas de Twilio y FCM afirman que la solicitud saliente no lleva el id.

El valor se toma de `Notification.correlationId` (persistido), no del MDC, para que un reencolado desde
el planificador reenvíe el mismo identificador.

## D6 — Qué no se hace

- Sin tableros, alertas ni SLO (solo las señales).
- Sin etiqueta `tenantId` en métricas.
- Sin cambiar `FailureCategory` ni los niveles de log de la 016.
- Sin actualizar Spring Boot ni migrar a OpenTelemetry Java agent.
