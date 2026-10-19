# Tasks: Rutas públicas del PEP (HU2-103)

**Input**: `specs/027-rutas-publicas-pep/` (spec.md, plan.md, research.md)

**Prerequisites**: plan.md en estado `Aprobado`.

Rutas base: `API` = `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`;
`IMain` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure`;
`IRes` = `infrastructure/src/main/resources`;
`ITest` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure`.

## Phase 1: Contrato primero

- [ ] T001 `API`: añadir `x-public-routes` (contrato, api-docs, swagger-config siempre; Swagger UI solo no productivo), `security: []`
  explicado en la descripción y punto 10 del registro de cambios.

## Phase 2: Pruebas

- [ ] T002 `ITest/adapter/in/web/PublicRoutesTest`: patrones completos (no prefijo), exclusión de `/actuator`, lista vacía.
- [ ] T003 `ITest/contract/PublicRoutesConsistencyTest`: contrato, `application.yml`, `application-local.yml` y filtro coinciden;
  el catálogo de recursos protegidos no incluye rutas públicas.
- [ ] T004 `ITest/adapter/in/rest/PublicRoutesPepE2ETest`: PEP activo con configuración por defecto, 200 en contrato y api-docs sin
  credencial, 401 en cada operación del contrato, en rutas con prefijo común y en la UI; control positivo con cookie; puerto de
  gestión.
- [ ] T005 `ITest/adapter/in/rest/PublicRoutesLocalPepE2ETest`: perfil `local`, la UI responde sin credencial y el negocio sigue en 401.

## Phase 3: Implementación

- [ ] T006 `IMain/adapter/in/web/PublicRoutes` y `AuthenticationWebFilter`: exenciones de documentación derivadas de
  `security.pep.enforcement.public-paths`.
- [ ] T007 `IRes/application.yml` y `application-local.yml`: `public-paths` por defecto, `springdoc.swagger-ui.enabled`.

## Phase 4: Documentación y verificación

- [ ] T008 README y `specs/015-autenticacion-interina/quickstart.md`: rutas públicas y variables.
- [ ] T009 `spotless:apply`, arquitectura y `verify` completo.
