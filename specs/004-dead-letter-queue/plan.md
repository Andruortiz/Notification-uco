# Implementation Plan: Cola de mensajes muertos (DLQ) para el despacho

**Branch**: `feature/HU2-040-dead-letter-queue` | **Date**: 2026-09-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-dead-letter-queue/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Un mensaje de despacho (`NotificationDispatchListener.onMessage`) cuyo procesamiento falla con una
excepción real (no un resultado de negocio `RECOVERABLE`/`FAILED`) hoy se reintenta indefinidamente
por el comportamiento por defecto de Spring AMQP (`defaultRequeueRejected=true`). Esta historia envuelve
la invocación del listener con un `StatefulRetryOperationsInterceptor` (reintento en memoria, sin
volver a pasar por el broker) que permite hasta 3 intentos configurables
(`notification.rabbit.dispatch.max-attempts`) y, al agotarlos, delega en un `RepublishMessageRecoverer`
que publica el mensaje agotado — junto con la causa y el stacktrace en headers — a una nueva cola de
mensajes muertos (`notification.dispatch.dlq.queue`) declarada en `RabbitConfig`. Todo el mecanismo es
infraestructura pura: no toca `core`, no cambia el contrato HTTP, y no interfiere con el camino de
negocio `RetryPolicy`/`RECOVERABLE`/`FAILED` ya construido.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / Spring AMQP (`spring-rabbit`) — `RetryInterceptorBuilder`,
`StatefulRetryOperationsInterceptor`, `RepublishMessageRecoverer`; Reactor solo indirectamente (el
listener sigue bloqueando con `.block()`, comportamiento preexistente que esta historia no cambia)

**Storage**: N/A — no se persiste ningún documento nuevo en MongoDB; el estado del mensaje muerto vive
únicamente en la cola de RabbitMQ

**Testing**: JUnit 5 + Mockito para la lógica de conteo/recuperación; un `@SpringBootTest` de integración
contra un broker RabbitMQ real (local vía `docker compose up -d rabbitmq`, en CI vía un contenedor de
servicio nuevo en `ci.yml`) que fuerza una excepción determinística y verifica que el mensaje aparece en
la cola de mensajes muertos con los headers de causa — la prueba E2E que exige el Principio IV

**Target Platform**: mismo objetivo Kubernetes del resto del componente; sin cambios de despliegue

**Project Type**: adaptador de infraestructura (`infrastructure/adapter/in/rabbit`,
`infrastructure/config`) — sin cambios en `core`

**Performance Goals**: sin objetivo nuevo — el camino feliz (mensaje procesado con éxito al primer
intento) no cambia su latencia; el camino de fallo repetido añade como máximo 2 reintentos en memoria
antes de mover el mensaje, acotado por `max-attempts`

**Constraints**: el movimiento a la cola de mensajes muertos NO debe perder el mensaje si el propio
`RepublishMessageRecoverer.recover()` falla (ej. RabbitMQ no disponible) — debe propagar la excepción
para que el contenedor rechace y reencole el mensaje original en vez de descartarlo en silencio (edge
case de la especificación)

**Scale/Scope**: una cola y un exchange nuevos, una propiedad de configuración nueva
(`max-attempts`, default 3); ningún cambio en el catálogo de canales/proveedores ni en el contrato HTTP

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. El mecanismo entero vive en
  `infrastructure` (config de Spring AMQP + topología RabbitMQ). `core` no se toca: no hay puerto,
  caso de uso ni entidad de dominio nuevos, porque "reintentar procesamiento N veces y mover a otra
  cola" es un detalle del adaptador de mensajería, no una regla de negocio.
- **II. Contract-first, API orientada a acciones** — N/A. No se expone ni modifica ningún endpoint
  HTTP; `api-notificaciones.yaml` no cambia.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). Sin comentarios `//`
  nuevos; el razonamiento vive en este plan y en el spec.
- **IV. Calidad verificada, no declarada** — PASS condicionado a Phase 0: se requiere una prueba E2E
  real contra RabbitMQ (no solo mocks de `StatefulRetryOperationsInterceptor`), lo cual exige que
  `ci.yml` levante un servicio RabbitMQ — hoy no lo hace. Esto se resuelve como decisión de Phase 0
  research (ver research.md) y se materializa como tarea explícita en `tasks.md`.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-040-dead-letter-queue` creada desde
  `origin/develop` (ya incluye el merge de CORS, HU2-071).
  Nota: la versión committeada de este principio nombra la restricción de atribución de IA en el
  historial; la instrucción de atribución vigente en esta sesión la reemplaza — ver
  `.specify/memory/constitution.md` (enmienda pendiente de commit, no tocada por esta historia).
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PASS. `spec.md` clarificado y validado
  (12/12), este plan es el siguiente artefacto versionado en `specs/004-dead-letter-queue/`.
- **VII. Sin atajos** — PASS condicionado. No se acepta dejar el requisito de prueba E2E como "solo
  unitaria" para evitar tocar CI — si Phase 0 concluye que añadir el servicio RabbitMQ a CI es
  necesario, se hace explícito como tarea, no se omite.
- **Restricciones técnicas** — PASS. No hay mutación de un agregado persistido con réplicas
  concurrentes (no se toca `NotificationDocument`), por lo que el bloqueo optimista y las
  actualizaciones condicionadas atómicas no aplican aquí. El conteo de intentos es en memoria, por
  mensaje individual, sin estado compartido entre réplicas — consistente con el Edge Case ya resuelto
  en el spec (no se requiere un almacén compartido).

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/004-dead-letter-queue/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `contracts/` directory — esta historia no expone ni modifica ninguna interfaz externa (ver Phase 1
más abajo).

### Source Code (repository root)

```text
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/
├── RabbitConfig.java                  # + bean(s) de topología DLQ (exchange/queue/binding)
├── RabbitTopologyProperties.java      # + registro Dlq(exchange, routingKey, queue)
└── RabbitRetryConfig.java             # nuevo: adviceChain (StatefulRetryOperationsInterceptor +
                                        #   RepublishMessageRecoverer) enganchado al
                                        #   SimpleRabbitListenerContainerFactory

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/rabbit/
└── NotificationRabbitPublisher.java   # + fija MessageProperties.messageId al publicar (no previsto
                                        #   originalmente; ver research.md, Notas de implementación #1)

infrastructure/src/main/resources/application.yml
└── notification.rabbit.dispatch.max-attempts: ${...:3}
└── notification.rabbit.dlq.{exchange,routing-key,queue}: ...

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/
├── RabbitRetryConfigTest.java         # unitaria: N intentos agota antes de recover, éxito antes de N
                                        #   nunca llega al recoverer
└── DeadLetterQueueE2ETest.java        # @SpringBootTest contra RabbitMQ real: mensaje envenenado
                                        #   termina en la cola de mensajes muertos con headers de causa

.github/workflows/ci.yml
└── + servicio rabbitmq:3-management para que la prueba E2E anterior corra en CI
```

**Structure Decision**: Todo el trabajo cae en `infrastructure` (configuración de Spring AMQP y
topología RabbitMQ) más un ajuste de pipeline CI; no se crea ningún directorio nuevo en `core` porque
no hay puerto, caso de uso ni entidad de dominio involucrados — el mecanismo de reintento/dead-letter es
enteramente un detalle del adaptador de mensajería.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
