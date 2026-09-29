# Implementation Plan: Enviar un lote de notificaciones por HTTP

**Branch**: `feature/HU2-021-exponer-envio-lote` | **Date**: 2026-09-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/014-exponer-envio-lote/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

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

`POST /notifications:sendBatch` (`sendNotificationBatch`) ya está en `api-notificaciones.yaml` y el
caso de uso `SendNotificationBatchUseCase` ya está implementado, probado y cableado como bean en
`UseCaseConfig`, pero ningún adaptador de entrada lo expone. Esta historia agrega ese adaptador: un
controller nuevo en `adapter/in/rest` que recibe `SendNotificationBatchRequest`, lo traduce a
`SendNotificationBatchCommand`, delega en el caso de uso y responde `202` con
`BatchAcceptedResponse`. Antes del controller se actualiza el contrato (Principio II): la descripción
de la operación pasa a indicar que está implementada y se documenta la respuesta `400` de solicitud
estructuralmente inválida decidida en `spec.md § Clarifications`. El núcleo no se modifica.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor (sin dependencias nuevas)

**Storage**: N/A para esta historia; la persistencia la hace el caso de uso existente a través de
`NotificationRepository` (MongoDB, restricción única `(tenantId, externalId)` ya existente)

**Testing**: `@WebFluxTest` con el caso de uso simulado para el mapeo HTTP y los rechazos 400 (mismo
patrón que `NotificationControllerTest`); una prueba E2E `NotificationBatchE2ETest` con
`@SpringBootTest(RANDOM_PORT)` + Testcontainers (MongoDB y RabbitMQ) + `WebTestClient` que recorre el
flujo completo lote -> aceptación -> despacho -> `DELIVERED` y cubre rechazo por elemento,
duplicado, aislamiento entre dos tenants y rechazo estructural sin registros nuevos

**Target Platform**: el mismo del componente; sin cambios de despliegue ni de configuración

**Project Type**: adaptador de entrada en `infrastructure` sobre un puerto de entrada existente

**Performance Goals**: sin objetivo nuevo; el lote procesa hasta 16 elementos en paralelo, límite ya
fijado por `SendNotificationBatchService`

**Constraints**: reactivo, no bloqueante; el adaptador no reimplementa lógica de agregación ni de
idempotencia (vive en el caso de uso); los errores estructurales se lanzan antes de invocar el caso
de uso para que ningún elemento se persista

**Scale/Scope**: 1 controller nuevo, 3 records DTO nuevos, 1 cambio de contrato, 2 clases de prueba

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. Todo el código nuevo está en
  `infrastructure/adapter/in/rest` y depende solo del puerto de entrada `SendNotificationBatchUseCase`
  y de los tipos de `core/port/in` y `core/domain/valueobject`. `core` no cambia.
- **II. Contract-first** — PASS. La operación ya existe en el contrato con el patrón
  `recurso:accion`; el cambio de contrato (descripción "implementado" + respuesta `400`) se commitea
  antes que el controller.
- **III. Cero comentarios explicativos** — PASS (se verifica en implementación).
- **IV. Calidad verificada** — PASS condicionado a `NotificationBatchE2ETest` (E2E explícita del
  flujo completo contra el proveedor simulado) y a `./mvnw -B -ntp verify` en verde con los umbrales de
  cobertura.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-021-exponer-envio-lote` desde `origin/develop`,
  commits de una línea.
- **VI. Spec-kit** — spec y clarify completos; este plan queda en estado `Pendiente` hasta que el
  usuario lo marque.
- **VII. Sin atajos** — PASS con un pendiente documentado abajo (Riesgos conocidos): el caso de un
  `externalId` repetido dentro del mismo lote depende del caso de uso del núcleo, fuera de alcance.
- **VIII. Confiabilidad** — PASS. El `202` solo se devuelve después de que el caso de uso persistió y
  encoló cada elemento aceptado (el `Mono` del caso de uso completa tras `save` + `enqueueForDispatch`).
- **Restricciones técnicas / RabbitMQ** — N/A. No se agrega ningún consumidor; el encolado usa el
  productor existente.

No violations requiring justification.

## Diseño

- **Controller nuevo `NotificationBatchController`** con `@PostMapping("/notifications:sendBatch")`,
  mismo criterio que `NotificationLiveUpdatesController` (cada operación `recurso:accion` en su propio
  controller con la ruta completa). No se toca `NotificationController` ni su prueba.
- **DTOs**: `SendNotificationBatchRequest(String batchId, List<SendNotificationRequest> items)`
  reutiliza el DTO de elemento existente, igual que el contrato reutiliza el schema
  `SendNotificationRequest`. `BatchAcceptedResponse(String batchId, List<BatchItemResultResponse>
  results)` y `BatchItemResultResponse(String externalId, String outcome, String notificationId,
  String rejectionReason)` con fábricas `from(...)` como `SendNotificationResponse`.
- **Mapeo y validación estructural** (en el controller, antes de llamar al caso de uso): `items`
  nulo o vacío, elemento nulo, `priority` ausente o fuera del enum, y cualquier campo obligatorio en
  blanco producen `IllegalArgumentException`, que `NotificationExceptionHandler` ya traduce a `400`.
  `batchId` ausente se pasa como `null` (el caso de uso genera uno); `batchId` presente pero en blanco
  falla en `BatchId.of`. La prioridad se valida con `Preconditions.requireNonBlank` antes de
  `Priority.valueOf`, porque `valueOf(null)` lanza `NullPointerException` y terminaría en `500`.
- **Errores de negocio por elemento**: ya los resuelve el caso de uso (`ChannelNotAvailableException`
  e `InvalidContentException` se convierten en `REJECTED`); el adaptador solo los serializa.

## Riesgos conocidos

- **`externalId` repetido en el mismo lote**: el caso de uso procesa hasta 16 elementos en paralelo y
  la idempotencia se decide con "buscar y luego guardar"; dos elementos con el mismo `externalId`
  pueden pasar ambos la búsqueda y el segundo chocar con la restricción única. Si la prueba E2E lo
  confirma, se reporta como defecto del caso de uso (fuera de alcance) y se registra como pendiente con
  dueño y fecha en `tasks.md`; no se parchea en el adaptador.

## Project Structure

### Documentation (this feature)

```text
specs/014-exponer-envio-lote/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/send-notification-batch.yaml
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml
└── POST /notifications:sendBatch: descripción "implementado" + respuesta 400

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/
├── NotificationBatchController.java     # nuevo
├── SendNotificationBatchRequest.java    # nuevo
├── BatchAcceptedResponse.java           # nuevo
└── BatchItemResultResponse.java         # nuevo

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/
├── NotificationBatchControllerTest.java # nuevo, @WebFluxTest
└── NotificationBatchE2ETest.java        # nuevo, E2E del Principio IV
```

**Structure Decision**: Solo `infrastructure/adapter/in/rest`, siguiendo el patrón de
`NotificationController` (DTO record + fábrica `from`, `X-Tenant-Id`, `ResponseEntity` con `202`) y
el de `NotificationLiveUpdatesController` (controller propio para una operación `recurso:accion`).

## Complexity Tracking

Ninguna violación.
