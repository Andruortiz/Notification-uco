# Implementation Plan: Reencolar notificaciones recuperables vencidas (HU2-030)

**Branch**: `feature/HU2-030-reencolar-notificaciones-recoverable` | **Date**: 2026-09-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-requeue-recoverable-notifications/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Reencolar automáticamente, de forma periódica, las notificaciones en estado `RECOVERABLE` cuyo tiempo de espera ya venció. Se reutiliza la política de reintentos con espera creciente (`RetryPolicy.nextBackoff`) ya vigente en el despacho automático — no se diseña una nueva. Un caso de uso nuevo (`RequeuePendingNotificationsService`) consulta las notificaciones `RECOVERABLE`, filtra las vencidas y las reencola; un adaptador de entrada programado (`@Scheduled`) lo dispara a intervalos configurables.

## Technical Context

**Language/Version**: Java 21 (ADR-0001)

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB, Spring Scheduling (`@Scheduled`/`@EnableScheduling`, ya incluido en `spring-context`, sin dependencia nueva)

**Storage**: MongoDB — reutiliza la colección `notifications` existente, agrega una consulta por `status`

**Testing**: JUnit 5, Mockito, StepVerifier, Testcontainers (Mongo) para la prueba E2E

**Target Platform**: Kubernetes (igual que el resto del componente)

**Project Type**: Microservicio hexagonal — historia que toca `core` (puerto + caso de uso) e `infrastructure` (consulta Mongo + adaptador de entrada programado)

**Performance Goals**: Ninguno nuevo — reutiliza el pipeline de despacho existente. Intervalo de revisión configurable, valor por defecto 30s (`notification.scheduler.requeue-interval-ms`)

**Constraints**: No bloquear hilos reactivos (el disparador `@Scheduled` invoca el pipeline reactivo de forma no bloqueante, `subscribe()` fire-and-forget); debe coexistir con múltiples réplicas sin reencolar la misma notificación dos veces

**Scale/Scope**: Opera sobre todas las notificaciones `RECOVERABLE` del sistema — es un proceso interno de mantenimiento, no una operación acotada a un tenant específico

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principio I (Arquitectura hexagonal)**: cumple — el disparador vive como adaptador de entrada nuevo (`infrastructure/adapter/in/scheduler/`), el caso de uso en `core/usecase/`, el puerto en `core/port/in/`. `core` no gana ninguna dependencia de Spring.
- **Principio II (Contract-first API)**: no aplica — esta historia no expone ni modifica ningún endpoint HTTP, es un proceso interno disparado por el propio componente.
- **Principio III (Cero comentarios)**: cumple — código sin comentarios explicativos.
- **Principio IV (Calidad verificada)**: pendiente hasta `tasks.md` — requiere cobertura ≥80/70, ArchUnit/Modulith en verde, Spotless/SpotBugs/FindSecBugs limpios, y **prueba E2E explícita** (bloqueante): guardar una notificación `RECOVERABLE` vencida, invocar `requeuePending()`, verificar que vuelve a `PENDING` y se reencola.
- **Principio V (Trazabilidad git)**: rama `feature/HU2-030-reencolar-notificaciones-recoverable`, commits de una sola línea, sin atribución de IA.
- **Principio VI (spec-kit)**: spec ya aprobado (con nota de proceso explícita sobre el orden real de construcción); este plan se aprueba antes de generar `tasks.md`.
- **Principio VII (Sin atajos)**: el orden real (implementación antes que spec formal) queda documentado explícitamente en `spec.md`, no oculto.
- **Restricciones técnicas — concurrencia entre réplicas (ADR-0017)**: la operación "tomar" una notificación `RECOVERABLE` para reencolarla usa el mismo mecanismo de bloqueo optimista ya vigente (`@Version` en `NotificationDocument`, verificado en `save()`). Si dos réplicas compiten por la misma notificación, la segunda falla con `OptimisticLockingFailureException` — comportamiento ya cubierto por el adaptador Mongo existente, no requiere código nuevo de coordinación.

**Resultado del gate**: PASS. No hay violaciones que requieran justificación — se omite Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/001-requeue-recoverable-notifications/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No se genera `contracts/` — esta historia no expone ningún contrato externo (ni API HTTP ni mensaje de cola nuevo).

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── port/in/RequeuePendingNotificationsUseCase.java        # nuevo — puerto de entrada
├── repository/NotificationRepository.java                  # modificado — + findByStatus(NotificationStatus)
└── usecase/RequeuePendingNotificationsService.java          # nuevo — caso de uso

core/src/test/java/co/edu/uco/notification/core/
└── usecase/RequeuePendingNotificationsServiceTest.java      # nuevo — pendiente de escribir

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/scheduler/PendingNotificationSchedulerAdapter.java   # nuevo — disparador @Scheduled
├── adapter/out/mongo/NotificationMongoAdapter.java                  # modificado — + findByStatus
└── config/UseCaseConfig.java                                        # modificado — + bean del caso de uso

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
└── adapter/out/mongo/NotificationMongoAdapterTest.java              # modificado — + prueba de findByStatus
    (más la prueba E2E que exige el Principio IV — se define en tasks.md)
```

**Structure Decision**: Combina Option 1 (`core`: puerto + caso de uso) y Option 2 (`infrastructure`: adaptador de entrada nuevo + extensión del adaptador Mongo existente). No se introduce ningún adaptador de entrada HTTP ni cambio de contrato OpenAPI, consistente con que el actor de esta historia es "el propio componente", no un sistema cliente externo.

## Complexity Tracking

> Sin violaciones del Constitution Check — no aplica.
