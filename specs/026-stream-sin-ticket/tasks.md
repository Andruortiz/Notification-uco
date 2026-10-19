# Tasks: Retirar el ticket de suscripción del stream (HU2-111)

**Input**: `specs/026-stream-sin-ticket/` (spec.md, plan.md)

**Prerequisites**: plan.md en estado `Aprobado`.

Rutas base: `API` = `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`;
`CMain` = `core/src/main/java/co/edu/uco/notification/core`;
`IMain` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure`;
`ITest` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure`.

## Phase 1: Contrato primero

- [x] T001 `API`: retirar `POST /notifications/stream/tickets`, `SubscriptionTicketResponse` y el parámetro `ticket`; documentar
  Bearer o cookie en el stream; punto 9 del registro de cambios y fila de la tabla de migración.

## Phase 2: Pruebas

- [x] T002 Actualizar guardias de contrato: `ProtectedResourcesCatalogTest` (16 operaciones, 11 disponibles),
  `ApiContractLiteralPathsTest` (12 rutas, 16 operaciones), `SecurityDocumentationContractTest`,
  `RouteAuthorizationPolicyContractTest`, `ApiContractMatchesControllersTest`, `ChangelogContractTest` (prueba nueva del retiro).
- [x] T003 `ITest/adapter/in/web/AuthenticationWebFilterTest`, `AuthenticationWebFilterSessionTest`,
  `CorrelationIdWebFilterTest`, `RouteAuthorizationPolicyTest`: quitar el flujo de ticket; el ticket sin credenciales da 401.
- [x] T004 E2E: en `SessionCookieE2ETest` prueba de stream sin credenciales, con `?ticket=`, sesión inválida, ruta de tickets
  inexistente y plataforma caída (503); se conserva la prueba existente de dos tenants por cookie.
  `AuthenticationInterinaE2ETest`: el ticket ya no autentica. `ResponseSchemasConformanceE2ETest`: sin el esquema retirado.

## Phase 3: Implementación

- [x] T005 Borrar de `core` el puerto, caso de uso, respuesta emitida, huella y su prueba.
- [x] T006 Borrar de `infrastructure` controlador, respuesta, documento y adaptador Mongo con sus pruebas.
- [x] T007 `IMain/adapter/in/web/AuthenticationWebFilter`, `RejectionReason`, `RouteAuthorizationPolicy`, `IMain/config/UseCaseConfig`
  y `application.yml`: retirar la rama y la configuración del ticket.
- [x] T008 Retirar `SubscriptionTicketPort` de los `@MockitoBean` de las pruebas de controladores.

## Phase 4: Documentación y verificación

- [x] T009 README y `specs/015-autenticacion-interina/quickstart.md` con el flujo por cookie.
- [ ] T010 `spotless:apply`, arquitectura (`HexagonalArchitectureTest`, `ModularityTests`) y `verify` completo; grep sin
  referencias a ticket de suscripción en `core` ni `infrastructure/src/main`.
