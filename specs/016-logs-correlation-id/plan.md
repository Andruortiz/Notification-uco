# Implementation Plan: Logs estructurados con identificador de correlación (HU2-056)

**Branch**: `feature/HU2-056-logs-correlation-id` | **Date**: 2026-10-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/016-logs-correlation-id/spec.md`

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

HU2-056 cierra la brecha de RNF-10 para logs: un identificador de correlación que viaja desde la
solicitud REST hasta el resultado terminal, y logs en formato estructurado con `correlationId`,
`tenantId` y `notificationId` como campos, sin datos sensibles. Ya existe una implementación
preliminar sin confirmar (identificador, filtro, propagación a MDC y a cabeceras de mensajería,
persistencia). Este plan la reconcilia con la spec: conserva la propagación, y añade lo que falta
(formato estructurado, `LogSanitizer`, `tenantId`/`notificationId` como campos, identificador en
eventos de dominio, en la consulta de estado y en los errores, y una prueba E2E).

Cuatro decisiones quedan **pendientes del usuario** (ver `research.md`): formato de log, propagación,
alcance del sanitizador y política de niveles. Este plan fija una **recomendación** para cada una para
poder dimensionar el trabajo; si el usuario elige otra, `research.md` indica qué cambia. Hasta
entonces el plan no debe aprobarse.

## Reconciliación con la implementación preliminar

### Se conserva

| Pieza preliminar | Motivo |
|---|---|
| `utils/CorrelationId` (+ `CorrelationIdTest`) | Cumple FR-001/FR-002: patrón `[A-Za-z0-9._-]{1,64}`, `fromOrNew` descarta valores inválidos. |
| `CorrelationIdWebFilter` (orden `HIGHEST_PRECEDENCE+5`) | Corre antes de `AuthenticationWebFilter` (`+10`), por lo que los `401/403` también devuelven la cabecera (FR-003). |
| `CorrelationContext` y `CorrelationContextConfig` (contexto Reactor a MDC, `context-propagation`) | Cumple FR-004/FR-014. Se amplía para registrar también `tenantId` y `notificationId`. |
| Cabecera AMQP `x-correlation-id` estampada en `NotificationRabbitPublisher` y `AttachmentScanRequestRabbitPublisher`; restauración en `NotificationDispatchListener` y `AttachmentScanListener` | Cumple FR-004/FR-006 para mensajes. |
| `Notification.correlationId`, comandos con `correlationId`, persistencia en `NotificationDocument`/mapper | Cumple FR-005. Los constructores anteriores de los comandos se conservan. |
| Planificador con identificador `sched-<uuid>` | Cumple el caso de borde del planificador (válido contra el patrón, 42 caracteres). |
| `CorsConfig` expone `X-Correlation-Id` | Necesario para clientes de navegador. |
| Parámetro `X-Correlation-Id` en OpenAPI | Se extiende (ver `contracts/`). |
| `CorrelationIdWebFilterTest`, `NotificationRabbitPublisherTest` ampliado | Se conservan y se amplían. |

### Cambia

| Pieza preliminar | Cambio | Contradicción con la spec |
|---|---|---|
| `logback-spring.xml` con patrón de texto y `[cid=%X{correlationId:-}]` | Se reemplaza por salida estructurada (decisión 1). | **Sí.** FR-007 y la constitución (Restricciones técnicas, RNF-10) prohíben texto plano libre; el patrón actual es texto con un campo incrustado. No cumple la constitución aunque propague el identificador. |
| `NotificationDispatchListener.onMessage(String)` (sobrecarga de un argumento que delega con `null`) | Se elimina; las pruebas usan la firma con cabecera. | No contradice la spec; es código de compatibilidad sin uso de producción (Principio VII: nada temporal definitivo). |
| Logs existentes (18 puntos con `LOGGER` y concatenación `clave=valor` en mensaje) | Pasan a campos estructurados; los datos de destinatario/contenido pasan por `LogSanitizer`. | Parcial: ya incluyen `tenantId`/`notificationId` pero dentro del texto, no como campos. |
| `AttachmentLogFormatter.safe`, `AuthenticationLogFormatter.safe`, `mask` de Twilio/FCM (tres sanitizaciones duplicadas) | Se consolidan en `LogSanitizer` (utils). | No contradice; elimina duplicación y fija una sola política (FR-010). |
| Brevo y proveedores | Revisión: ningún log de respuesta del proveedor ni excepción lleva credencial o destinatario completo. | La spec exige 0 centinelas (SC-004); hoy no está probado. |

### Falta (brechas conocidas, con requisito que las exige)

| Brecha | Requisito | Tratamiento |
|---|---|---|
| Los 8 eventos de dominio no llevan `correlationId` (solo cabecera AMQP) ni `tenantId` | FR-006, FR-008 | Añadir `correlationId` y `tenantId` a `DomainEvent` y sus 7 records concretos (cambio en `core`). Es el único punto donde los hitos de ciclo de vida se registran con los tres identificadores sin meter logging en `core`. |
| `NotificationStatusView`/`NotificationStatusResponse` no exponen `correlationId` | FR-013 | Añadir campo (core + REST + OpenAPI). |
| `ErrorResponse` no lo incluye | FR-003 | Añadir campo `correlationId` leído del contexto en `NotificationExceptionHandler` y `AuthenticationWebFilter`. |
| `LogSanitizer` no existe | FR-009, FR-010 | Nueva clase en `utils` (Java puro, usable desde `core`). |
| `ErrorCode`/categoría de fallo no existe | FR-012 | Proponer enum mínimo `FailureCategory` (recuperable de proveedor, recuperable de infraestructura, permanente de negocio) como campo `failureCategory` del log; un catálogo numérico `ErrorCode` queda fuera (ver Excepciones). |
| Formato de log es texto plano | FR-007 | Ver decisión 1. |
| No hay `tenantId`/`notificationId` en MDC | FR-008 | `tenantId`: lo registra el filtro de autenticación tras resolver el principal. `notificationId`: lo registra cada listener y se pasa como campo en los puntos de log. |
| No hay E2E de punta a punta | Principio IV, SC-001..SC-006 | `LogCorrelationE2ETest` (ver Testing). Requiere Docker, no verificable en el entorno donde se escribió la implementación preliminar. |
| Proveedores solo heredan MDC, no reenvían el id | Supuesto de spec | Fuera de alcance; excepción documentada. |
| Contexto Spring completo, Mongo y Rabbit | — | La implementación preliminar nunca se ejecutó contra ellos; se verifican en `verify` con Docker. |
| Cola de espera y reintentos: el id llega al reintento y al descarte (US1.4) | FR-004 | Verificar con la prueba de DLQ; los reintentos republican el mismo mensaje con sus cabeceras. Si el broker de reintento pierde la cabecera, se corrige. |

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.3.4 / WebFlux, Reactor, `io.micrometer:context-propagation`
(ya añadida). Recomendada y pendiente de decisión: `net.logstash.logback:logstash-logback-encoder`
solo en `infrastructure/pom.xml`, porque Boot 3.3.4 no trae logging estructurado (llega en 3.4).

**Storage**: MongoDB; `Notification.correlationId` ya persiste. Documentos antiguos sin el campo se
leen como nulo.

**Testing**: JUnit 5 + Mockito + `StepVerifier` (`utils`, `core`); Testcontainers Mongo/RabbitMQ para
adaptadores; E2E `@SpringBootTest(RANDOM_PORT)` + `WebTestClient`; captura de logs con un appender de
prueba sobre la salida real del layout estructurado; `HexagonalArchitectureTest` y `ModularityTests`.

**Target Platform**: Kubernetes, N réplicas; el id es opaco y sin estado compartido.

**Project Type**: hexagonal. `utils`: `CorrelationId`, `LogSanitizer`, `FailureCategory`. `core`:
eventos y vista de estado con el identificador (sin logging ni dependencia de framework).
`infrastructure`: filtro, contexto, layout de log, puntos de log.

**Performance Goals**: sin degradación apreciable de la aceptación (≤200 ms p95 vigente de la
autenticación interina); el layout JSON no bloquea el hilo reactivo (appender asíncrono).

**Constraints**: ningún comentario explicativo en código nuevo; ninguna dependencia nueva en `core`;
contrato OpenAPI primero.

**Scale/Scope**: 3 canales, N tenants; ~18 puntos de log existentes más los hitos de ciclo de vida.

## Constitution Check

| Principio / restricción | Evaluación |
|---|---|
| I. Hexagonal | Cumple. `core` solo recibe campos en eventos y vista; el logging vive en `infrastructure`. `LogSanitizer` en `utils` (sin framework). Riesgo: poner logging en casos de uso rompería el principio; el plan lo evita registrando los hitos desde el adaptador de publicación de eventos. |
| II. Contract-first | Se actualiza `api-notificaciones.yaml` antes del código de respuestas/errores (primera tarea de contrato). |
| III. Sin comentarios | Aplica a todo el código nuevo. Nota: `logback-spring.xml` no lleva comentarios. |
| IV. Pruebas | E2E explícito, unitarias, integración de adaptador, aserciones `Duration` donde aplique, prueba de dos tenants, centinelas. Cobertura ≥80/70 por `verify`. |
| V. Commits | Una línea, español sin tildes, `tipo(ambito): descripcion`; sin razonamiento en el commit. |
| VI. Aprobación | Plan queda en `Pendiente`; no se generan tareas. |
| VII. Sin soluciones temporales | Ver Excepciones: lo diferido tiene dueño y fecha por fijar por el usuario. |
| IX. Observabilidad | Es el principio que esta historia implementa. |
| Restricción de logs (RNF-10) | La preliminar **no cumple** (texto plano); el plan corrige. |
| Consumidores RabbitMQ: ack manual y DLQ | Esta historia solo añade MDC y lectura de cabecera en `NotificationDispatchListener` y `AttachmentScanListener`; no cambia su política de ack ni de DLQ. Si al implementar se requiere tocarla se justificará aquí. |
| Secretos fuera de logs | Cumple por `LogSanitizer` + prueba de centinelas. |

Resultado: sin violaciones tras diseño; sin entradas en Complexity Tracking.

## Diseño propuesto (resumen)

1. **Formato** (decisión 1, recomendación A): JSON de una línea con `logstash-logback-encoder`, con
   campos `timestamp`, `level`, `logger`, `message`, `correlationId`, `tenantId`, `notificationId`,
   `failureCategory`, `stack_trace` sanitizada. Perfil de prueba con el mismo layout.
2. **Propagación** (decisión 2, recomendación: conservar lo existente): `X-Correlation-Id` en HTTP,
   `x-correlation-id` en AMQP, contexto Reactor puenteado a MDC. El `tenantId` se añade al contexto
   tras autenticar.
3. **Hitos de ciclo de vida**: un único componente en `infrastructure` (el publicador de eventos) emite
   un log INFO/WARN/ERROR por cada `DomainEvent`, tomando `correlationId`, `tenantId`,
   `notificationId` del propio evento (por eso se amplían los eventos). Esto reconstruye aceptación,
   encolado, entrega, fallo, recuperable, reencolado y descarte.
4. **Sanitizador** (decisión 3, recomendación A): `LogSanitizer.maskRecipient`, `omitContent`,
   `redactSecret`, `safe` (caracteres de control). Contenido y credenciales: nunca se registran;
   destinatario: enmascarado. Throwables: converter de Logback que pasa el stack trace por el
   sanitizador.
5. **Niveles** (decisión 4, recomendación: política provisional de la spec): INFO hitos; DEBUG detalle;
   WARN recuperable; ERROR permanente/infraestructura no recuperada.
6. **Cliente**: `correlationId` en consulta de estado y en `ErrorResponse`.

## Project Structure

### Documentation (this feature)

```text
specs/016-logs-correlation-id/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── openapi-delta.md
├── checklists/requirements.md
└── spec.md
```

### Source Code (repository root)

```text
utils/src/main/java/co/edu/uco/notification/utils/
├── CorrelationId.java                (existe, sin commit)
├── LogSanitizer.java                 (nuevo)
└── FailureCategory.java              (nuevo)

core/src/main/java/co/edu/uco/notification/core/
├── domain/Notification.java          (existe modificado; sin commit)
├── domain/event/*.java               (8 archivos: DomainEvent + 7 records; añadir correlationId, tenantId)
└── port/in/ y usecase/               (vista de estado con correlationId; comandos ya modificados)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/web/CorrelationIdWebFilter.java       (existe)
├── adapter/in/web/AuthenticationWebFilter.java      (tenantId al contexto; correlationId en error)
├── adapter/in/rest/                                  (respuesta de estado, ErrorResponse, handler)
├── adapter/in/rabbit/                                (listeners: notificationId a MDC; quitar sobrecarga)
├── adapter/out/rabbit/NotificationRabbitPublisher    (log de hitos desde eventos)
├── adapter/out/provider/                             (logs vía LogSanitizer)
└── config/CorrelationContext*.java, logging config   (existe + layout y converter)

infrastructure/src/main/resources/
├── logback-spring.xml                (reemplazar)
└── static/openapi/api-notificaciones.yaml

utils/src/test/ , core/src/test/ , infrastructure/src/test/
└── LogSanitizerTest, FailureCategoryTest?, eventos, LogCorrelationE2ETest, etc.
```

**Structure Decision**: sin módulos nuevos; `LogSanitizer`/`FailureCategory` en `utils` (ya
compartido por `core` e `infrastructure`).

## Testing

| Nivel | Prueba | Qué afirma |
|---|---|---|
| Unitaria `utils` | `LogSanitizerTest` | Enmascarado, omisión, caracteres de control, nulos. |
| Unitaria `core` | pruebas de eventos y `Notification` | Los eventos llevan `correlationId`/`tenantId`; `StepVerifier` en servicios. |
| Layout | `StructuredLogLayoutTest` | Cada línea parsea; contiene los campos; excepción sanitizada. |
| Adaptador Rabbit | ampliar `NotificationRabbitPublisherTest` y `NotificationUpdatesRabbitAdapterTest` | Publicar con `NotificationEventPublisherPort.publish` y un `DomainEvent` real (no JSON armado) y que el consumidor lo lea con los campos nuevos. |
| Adaptador Mongo | ampliar `NotificationMongoAdapterTest` | Persistencia y lectura del id, documento antiguo sin campo. |
| Filtro | `CorrelationIdWebFilterTest` | Válido, ausente, inválido, inyección de salto de línea, cabecera en `401`. |
| E2E | `LogCorrelationE2ETest` (`@SpringBootTest(RANDOM_PORT)`, Mongo+Rabbit, `WebTestClient`) | Flujo completo con id conocido: todas las entradas del recorrido llevan el id (SC-001); dos tenants concurrentes sin cruce (SC-003, control positivo: cada tenant sí aparece); centinelas ausentes (SC-004); salida parseable (SC-005); id en respuesta y en errores (SC-006); estado expone el id. Aserción explícita de `Duration` para la espera del estado terminal. |
| DLQ | extender `DeadLetterQueueE2ETest` | El id se conserva en reintentos y descarte. Se ejecuta en CI. |
| Arquitectura | `HexagonalArchitectureTest`, `ModularityTests` | Siguen en verde. |

## Excepciones y diferidos (Principio VII)

| Pendiente | Dueño | Fecha |
|---|---|---|
| Métricas técnicas y de negocio de RNF-10 (el resto del requisito) | por asignar por el usuario | por fijar por el usuario |
| Reenvío del identificador a proveedores (Brevo/Twilio/FCM) | por asignar por el usuario | por fijar por el usuario |
| Catálogo numérico `ErrorCode` (solo se entrega la categoría) | por asignar por el usuario | por fijar por el usuario |

El agente no inventa dueños ni fechas; el usuario debe completarlas antes de aprobar.

## Complexity Tracking

Sin violaciones que justificar.
