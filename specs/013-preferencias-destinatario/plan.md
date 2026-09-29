# Implementation Plan: Gestionar preferencias del destinatario y excluir bajas al despachar

**Branch**: `feature/HU2-029-preferencias-destinatario` | **Date**: 2026-09-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-preferencias-destinatario/spec.md`

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

HU2-029 añade las preferencias de contacto de un destinatario (baja total o lista de canales
aceptados) por (tenant, destinatario), con las dos operaciones que el contrato ya declara:
`GET /recipients/{recipientId}/preferences` y `POST /recipients/{recipientId}:updatePreferences`. Sin
registro, el destinatario acepta todos los canales. HU2-030 hace que `DispatchNotificationService`
consulte esas preferencias **antes de marcar la notificación en proceso** (opción b de research,
Decisión 1): si el canal queda excluido, la notificación pasa `PENDING → DISCARDED` (transición ya
permitida) por el mismo `saveAndPublish` del despacho, sin llamar a ningún proveedor. Todo lo demás
del despacho queda igual. Persistencia en una colección Mongo nueva con `_id` compuesto
`{tenantId, recipientId}` y reemplazo completo atómico.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB. Nada nuevo en
`pom.xml`.

**Storage**: MongoDB, colección nueva `recipient_preferences` (research Decisión 4). Sin cambios en
`notifications`.

**Testing**: JUnit 5 + Mockito + `StepVerifier` en `core` (`RecipientPreferencesTest`,
`GetRecipientPreferencesServiceTest`, `UpdateRecipientPreferencesServiceTest`,
`DispatchNotificationServiceTest` ampliado); integración del adaptador contra Mongo real con
Testcontainers (`RecipientPreferenceMongoAdapterTest`); E2E `RecipientPreferencesE2ETest`
(`@SpringBootTest(RANDOM_PORT)` + Mongo y RabbitMQ en Testcontainers + `WebTestClient`, proveedores
de registro falsos como en `ProviderRoutingE2ETest`).

**Target Platform**: el mismo contenedor, N réplicas. Las preferencias se leen de Mongo en cada
despacho (sin caché local): un cambio rige en todas las réplicas desde la siguiente lectura.

**Project Type**: hexagonal — puerto de salida y dos puertos de entrada nuevos en `core`, adaptador
Mongo y controlador REST nuevos en `infrastructure`.

**Performance Goals**: SC-003 — una notificación para un destinatario dado de baja queda DISCARDED en
≤5 s desde su aceptación. El despacho añade una lectura por clave primaria.

**Constraints**: reactivo, no bloqueante; el despacho no debe enviar si no pudo leer las preferencias
(research Decisión 5); ninguna regla nueva en `StatusTransitionPolicy`.

**Scale/Scope**: un documento por (tenant, destinatario) que haya declarado preferencias.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Arquitectura hexagonal** — PASS. `RecipientPreferences`, `RecipientPreferencePort`, los casos de
  uso y los servicios son Java puro + Reactor en `core`. El adaptador Mongo y el controlador viven en
  `infrastructure`. `HexagonalArchitectureTest` y `ModularityTests` se ejecutan como parte de la
  historia.
- **II. Contract-first** — PASS. Las operaciones ya están en el contrato; los ajustes (400,
  `updatedAt` nullable, descripciones) se escriben en `api-notificaciones.yaml` antes que el
  controlador (contracts/api-notificaciones-cambios.md). `:updatePreferences` sigue el patrón
  `recurso:accion` ya declarado.
- **III. Cero comentarios explicativos** — PASS, a verificar en la revisión del diff.
- **IV. Calidad verificada** — PASS condicionado: E2E `RecipientPreferencesE2ETest` del flujo completo
  (declarar → aceptar → despachar → DISCARDED/DELIVERED) con control positivo, aserción explícita de
  `Duration` para SC-003, dos tenants para SC-005; `verify` completo con cobertura ≥80 %/≥70 %.
- **V. Trazabilidad en git** — rama `feature/HU2-029-preferencias-destinatario`, commits de una línea.
- **VI. Spec-kit** — spec con 4 clarificaciones registradas con respuesta recomendada. El campo
  "Estado del plan" queda en Pendiente: lo cambia solo el usuario.
- **VII. Sin atajos** — los riesgos (sin historial de consentimiento, sin autenticación del
  destinatario, canal mal escrito) están en spec § Risks con dueño y fecha.
- **VIII. Confiabilidad** — PASS. Un fallo al leer preferencias no envía ni descarta: la notificación
  sigue PENDING y la recuperan los reintentos, la DLQ y el scheduler existentes. El descarte se
  persiste antes de confirmar el mensaje, como cualquier otro resultado del despacho.
- **IX. Observabilidad** — PASS. El descarte publica `NotificationDiscarded` por el mismo camino que
  el resto de transiciones: histórico, estado y flujo en vivo lo muestran sin cambios.
- **Restricciones técnicas** — sin consumidor RabbitMQ nuevo (la regla de ack manual + DLQ no cambia);
  sin credenciales nuevas; bloqueo optimista: la actualización de preferencias es un reemplazo
  completo sin lectura previa, razonado en research Decisión 4; aislamiento por tenant en la clave
  primaria.

Re-check post-diseño: sin cambios; ninguna violación que justificar.

## Project Structure

### Documentation (this feature)

```text
specs/013-preferencias-destinatario/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/api-notificaciones-cambios.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml   # ajustes de contracts/

core/src/main/java/co/edu/uco/notification/core/
├── domain/RecipientPreferences.java                         # nuevo
├── port/out/RecipientPreferencePort.java                    # nuevo
├── port/in/GetRecipientPreferencesQuery.java                # nuevo
├── port/in/GetRecipientPreferencesUseCase.java              # nuevo
├── port/in/UpdateRecipientPreferencesCommand.java           # nuevo
├── port/in/UpdateRecipientPreferencesUseCase.java           # nuevo
├── usecase/GetRecipientPreferencesService.java              # nuevo
├── usecase/UpdateRecipientPreferencesService.java           # nuevo
└── usecase/DispatchNotificationService.java                 # + RecipientPreferencePort, paso previo a la ruta

core/src/test/java/co/edu/uco/notification/core/
├── domain/RecipientPreferencesTest.java
├── usecase/GetRecipientPreferencesServiceTest.java
├── usecase/UpdateRecipientPreferencesServiceTest.java
└── usecase/DispatchNotificationServiceTest.java             # + casos de exclusión y fallo del puerto

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/out/mongo/RecipientPreferenceDocument.java       # nuevo
├── adapter/out/mongo/RecipientPreferenceKey.java            # nuevo
├── adapter/out/mongo/RecipientPreferenceMongoAdapter.java   # nuevo
├── adapter/in/rest/RecipientPreferencesController.java      # nuevo
├── adapter/in/rest/UpdatePreferencesRequest.java            # nuevo
├── adapter/in/rest/RecipientPreferencesResponse.java        # nuevo
└── config/UseCaseConfig.java                                # + 2 beans, + puerto en el despacho

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── adapter/out/mongo/RecipientPreferenceMongoAdapterTest.java   # Testcontainers Mongo
└── adapter/in/rest/RecipientPreferencesE2ETest.java             # E2E
```

**Structure Decision**: mismo reparto hexagonal que el resto del servicio. El puerto va en
`core/port/out` (no en `core/repository`, que hoy solo contiene el repositorio del agregado
`Notification`).

## Complexity Tracking

Sin violaciones que justificar.
