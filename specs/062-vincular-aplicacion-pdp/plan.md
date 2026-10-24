# Implementation Plan: Vincular la configuración de negocio a una aplicación del PDP

**Branch**: `feature/HU2-146-vincular-aplicacion-pdp` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

## Summary

Un administrador vincula su tenant a una aplicación del PDP: el servicio valida la existencia con la sesión del propio administrador (cookie `SECURITY_BASELINE_SESSION` reenviada al PDP), guarda el vínculo único por (tenant, aplicación) y escribe un registro de auditoría de solo agregado. Tres operaciones REST (alta, listado, baja) más lectura de auditoría. Decisiones y alternativas en [research.md](research.md).

## Technical Context

**Language/Version**: Java 25
**Primary Dependencies**: Spring Boot 4.1 WebFlux, WebClient (ya usado por `PdpSessionIdentityAdapter`), Spring Data Reactive MongoDB
**Storage**: MongoDB, colecciones `application_bindings` (índice único `(tenantId, applicationId)`) y `application_binding_audit` (solo inserciones)
**Testing**: JUnit 5 + Mockito + StepVerifier en `core`; Testcontainers Mongo para el adaptador; E2E `ApplicationBindingE2ETest` con Mongo, RabbitMQ y un PDP simulado (WireMock o servidor HTTP embebido ya usado en pruebas de sesión; se confirma al implementar)
**Target Platform**: Kubernetes, varias réplicas
**Project Type**: microservicio hexagonal (`core` + `infrastructure`)
**Performance Goals**: alta ≤5 s incluso con PDP caído (timeout propio configurable, por defecto 3 s) (SC-004)
**Constraints**: sin bloqueo de hilos; nunca registrar ni persistir la cookie; el tenant sale solo de la sesión
**Scale/Scope**: decenas de vínculos por tenant; listado sin paginación (tope documentado de 100)

## Constitution Check

- I Hexagonal: `core` define `ApplicationBindingRepository`, `PdpApplicationCatalogPort` (consulta de aplicación con `SessionCredential`) y `ApplicationBindingAuditPort`; sin Spring en `core`. OK.
- II Contract-first: rutas literales `/application-bindings` y `/application-bindings/audit`; ids en cuerpo o query; `x-status` y `x-minimum-role: ADMINISTRADOR`; el YAML se edita antes del controller. OK.
- III Sin comentarios: se cumple. OK.
- IV Calidad: tarea E2E explícita; pruebas con dos tenants, concurrencia (SC-003) y `Duration` (SC-004). OK.
- V Commits de una línea. OK.
- VII Excepciones: ver abajo.
- VIII Confiabilidad: ver orden de escritura en research.md D4; la auditoría se escribe antes de confirmar y el alta compensa si falla. OK.
- IX Observabilidad: logs con tenant, correlación y applicationId; nunca la cookie.
- RabbitMQ: no se agrega consumidor ni publicación; no aplica la regla de ack/DLQ.

**Excepciones (Principio VII)**:

| Excepción | Motivo | Dueño | Fecha límite |
|---|---|---|---|
| Auditoría mínima propia en lugar de B6 | B6 no existe en el código | Equipo de backend | Reemplazar o consumir al entregar B6; revisar 2026-12-15 |
| Validación contra el PDP con la sesión del administrador | PDP 0.1.6 sin token técnico; no probado contra un PDP real | Seguridad (pregunta abierta) | Revisar 2026-12-15 |

Gate previo y posterior al diseño: sin violaciones.

## Project Structure

### Documentation

```text
specs/062-vincular-aplicacion-pdp/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/application-bindings.yaml
```

### Source Code

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/ApplicationBinding.java, ApplicationBindingAuditEntry.java, valueobject/ApplicationId.java
├── port/in/BindApplicationUseCase, ListApplicationBindingsUseCase, UnbindApplicationUseCase, ListApplicationBindingAuditUseCase
├── port/out/PdpApplicationCatalogPort, ApplicationBindingAuditPort
├── repository/ApplicationBindingRepository.java
├── exception/ApplicationNotFoundException, PdpUnavailableException, ApplicationBindingNotFoundException
└── usecase/BindApplicationService, ListApplicationBindingsService, UnbindApplicationService, ListApplicationBindingAuditService

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/ApplicationBindingController.java (+ request/response records), regla de ruta en RouteAuthorizationPolicy, mapeos en NotificationExceptionHandler
├── adapter/out/pdp/PdpApplicationCatalogAdapter.java
├── adapter/out/mongo/ApplicationBinding*Document/MongoAdapter, ApplicationBindingAudit*Document/MongoAdapter
└── config/PdpAdminProperties.java (base-url, applications-path, timeout, console-url), cableado en UseCaseConfig
infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml
```

**Structure Decision**: sigue el patrón de `AttachmentUpload` (puerto de repositorio en `core`, adaptador Mongo, controller en `adapter/in/rest`) y reutiliza `WebClient` y `SessionCredential` de la autenticación por sesión.

## Pruebas

- Unitarias de los cuatro servicios en `core` con StepVerifier: alta nueva, repetida (sin segunda auditoría), inexistente, sin permiso, PDP caído, fallo de auditoría con compensación, baja ajena.
- Integración `ApplicationBindingMongoAdapterTest` y `PdpApplicationCatalogAdapterTest` (404, 403, timeout, JSON ilegible).
- E2E `ApplicationBindingE2ETest`: alta, repetición, listado con dos tenants, baja, auditoría, 20 altas concurrentes, PDP detenido con aserción `Duration` ≤5 s.
- `HexagonalArchitectureTest` y `ModularityTests` siguen en verde.
