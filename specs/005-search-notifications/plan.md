# Implementation Plan: Buscar notificaciones por filtros

**Branch**: `feature/HU2-025-search-notifications` | **Date**: 2026-09-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-search-notifications/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

`GET /notifications` (CU-05, `searchNotifications`) ya existe en `api-notificaciones.yaml` pero no
está implementado en ningún lado — ni puerto, ni caso de uso, ni controller — y el contrato tal como
está hoy no contempla paginación. Esta historia extiende primero el contrato (Principio II) para
agregar `limit`/`offset` y un envoltorio de respuesta paginado, y luego implementa el flujo completo:
un nuevo puerto `SearchNotificationsUseCase` en `core` que consulta `NotificationRepository` (extendido
con un método de búsqueda por filtros combinables), un adaptador Mongo con una consulta dinámica
ordenada por `acceptedAt` descendente, y el nuevo método del controller que cumple el contrato ya
existente. Es una historia de solo lectura — no persiste nada nuevo, no toca RabbitMQ.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux, Spring Data Reactive MongoDB
(`ReactiveMongoTemplate`, `Criteria`/`Query` dinámico)

**Storage**: MongoDB — misma colección `notifications` ya existente, ningún campo nuevo en el
documento; sí requiere un índice compuesto nuevo (`tenantId` + `acceptedAt`) para que el filtrado y
el orden descendente no degraden a un escaneo completo de colección a medida que crece el volumen

**Testing**: JUnit 5 + Mockito para el caso de uso (filtros combinados, paginación, orden); prueba de
integración contra MongoDB real (mismo patrón que `NotificationMongoAdapterTest`, vía Testcontainers)
para la consulta dinámica; prueba E2E de extremo a extremo (Principio IV) que acepta varias
notificaciones, busca con filtros combinados y valida la página de resultados completa incluyendo el
historial de intentos

**Target Platform**: mismo objetivo Kubernetes del resto del componente; sin cambios de despliegue

**Project Type**: hexagonal — nuevo puerto de entrada + caso de uso en `core`, extensión del puerto
`NotificationRepository` y su adaptador Mongo en `infrastructure`, nuevo método en
`NotificationController` existente

**Performance Goals**: SC-003 — una búsqueda sobre un rango de un mes en un tenant con hasta 10 000
notificaciones responde en 2 segundos o menos; depende del índice compuesto nuevo, no de optimización
a nivel de aplicación

**Constraints**: la paginación se resuelve en MongoDB (`skip`/`limit` sobre una consulta ordenada por
índice), nunca cargando el resultado completo en memoria y paginando en el adaptador o en el
controller; reactivo, no bloqueante, consistente con el resto del componente

**Scale/Scope**: una consulta nueva, un método de repositorio nuevo, un índice nuevo, una extensión de
contrato (parámetros de paginación + envoltorio de respuesta) — ninguna entidad de dominio nueva, ya
que `Notification` y `DeliveryAttempt` ya existen con todos los campos que esta historia necesita
exponer

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. Todo el trabajo nuevo es: un puerto de
  entrada + caso de uso en `core` (sin dependencias de Spring), una extensión del puerto de salida
  `NotificationRepository` (interfaz en `core`, implementación en `infrastructure`), y un método de
  controller. Ningún framework se filtra a `core`.
- **II. Contract-first, API orientada a acciones** — PASS condicionado a Phase 1: el contrato
  `api-notificaciones.yaml` YA define `GET /notifications` (CU-05) pero sin paginación — esta
  historia debe actualizar el contrato primero (agregar `limit`/`offset` y el envoltorio de
  respuesta paginado) antes de escribir el controller, tal como exige el principio. Es CRUD natural
  (`GET` para consultar), no necesita el patrón `recurso:accion`.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación).
- **IV. Calidad verificada, no declarada** — PASS condicionado: requiere una prueba E2E real que
  ejercite el flujo completo (aceptar notificaciones → buscar con filtros combinados → validar la
  página completa con historial), no solo pruebas unitarias del caso de uso. Cobertura ≥80 %/≥70 %
  para los archivos nuevos.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-025-search-notifications` creada desde
  `origin/develop`.
  Nota: la versión committeada de este principio (v1.1.0, vigente en `develop`) incluye la
  restricción "sin atribución de IA en el historial" — la instrucción de atribución activa en esta
  sesión la reemplaza (la enmienda a v1.2.0 que retira esa cláusula ya está redactada, en la rama
  `docs/constitution-v1.2.0`, aún no mergeada a `develop`).
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PASS. `spec.md` clarificado (2
  preguntas resueltas) y validado (16/16), este plan es el siguiente artefacto.
- **VII. Sin atajos** — PASS condicionado: el índice compuesto nuevo se construye como parte de esta
  historia, no se difiere — sin él, SC-003 no es alcanzable y quedaría como una promesa de
  rendimiento sin respaldo, exactamente lo que este principio prohíbe.
- **Restricciones técnicas** — N/A en su mayoría: esta historia es de solo lectura, no muta ningún
  agregado persistido (no aplica bloqueo optimista ni idempotencia de escritura), no usa RabbitMQ, y
  los resultados de búsqueda no son datos semi-estáticos cacheables (cambian con cada notificación
  nueva) — ninguna de las restricciones de concurrencia/caché existentes aplica a un endpoint de
  consulta dinámica.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/005-search-notifications/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml
└── GET /notifications: + parámetros limit/offset, + schema NotificationSearchResponse (envoltorio
    paginado), reemplazando el response actual (array plano) por el envoltorio

core/src/main/java/co/edu/uco/notification/core/port/in/
├── SearchNotificationsQuery.java       # nuevo: tenantId + filtros opcionales + limit/offset
├── SearchNotificationsUseCase.java     # nuevo: puerto de entrada
├── NotificationSearchResult.java       # nuevo: vista por notificación (historial completo)
└── NotificationSearchPage.java         # nuevo: envoltorio de página (items + hasNext)

core/src/main/java/co/edu/uco/notification/core/usecase/
└── SearchNotificationsService.java     # nuevo: implementa SearchNotificationsUseCase

core/src/main/java/co/edu/uco/notification/core/repository/
├── NotificationRepository.java         # + método search(...)
└── NotificationSearchCriteria.java     # nuevo: filtros + paginación, usado por el puerto de salida

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/
├── NotificationDocument.java           # + @CompoundIndex nuevo (tenantId + acceptedAt)
└── NotificationMongoAdapter.java       # + implementación de search(...) con Criteria dinámico

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/
├── NotificationController.java         # + método GET /notifications con @RequestParam opcionales
├── NotificationSearchResponse.java     # nuevo: DTO del envoltorio paginado
├── NotificationHistoryItemResponse.java # nuevo: DTO por notificación con historial completo
└── DeliveryAttemptResponse.java        # nuevo: DTO de un intento de entrega

core/src/test/java/co/edu/uco/notification/core/usecase/
└── SearchNotificationsServiceTest.java # nuevo

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/
└── NotificationMongoAdapterSearchTest.java # nuevo, o extiende el test existente

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/
└── NotificationControllerSearchE2ETest.java # nuevo — prueba E2E del Principio IV
```

**Structure Decision**: Sigue exactamente el patrón ya establecido por `GetNotificationStatusUseCase`
(puerto + query + vista + servicio en `core`, DTOs + controller en `infrastructure`), solo que esta
vez el resultado es una página de varias notificaciones con su historial completo en vez de una sola
notificación con su estado actual. No se crea ningún módulo ni paquete nuevo — todo cae en los
paquetes `port/in`, `usecase`, `repository` y `adapter/{in/rest,out/mongo}` ya existentes.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
