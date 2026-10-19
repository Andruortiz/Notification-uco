# Tasks: Rutas públicas del PEP (HU2-103)

**Input**: `specs/027-rutas-publicas-pep/` (spec.md, plan.md, research.md)

**Prerequisites**: plan.md en estado `Aprobado`.

Rutas base: `API` = `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`;
`IMain` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure`;
`IRes` = `infrastructure/src/main/resources`;
`ITest` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure`.

## Phase 1: Contrato primero

- [x] T001 `API`: añadir `x-public-routes` (contrato y api-docs siempre; swagger-config y Swagger UI solo no productivo), `security: []`
  explicado en la descripción y punto 10 del registro de cambios.

## Phase 2: Pruebas

- [ ] T002 `ITest/adapter/in/web/PublicRoutesTest`: patrones completos (no prefijo), exclusión de `/actuator`, lista vacía. Escrita; pendiente de verificación en CI.
- [ ] T003 `ITest/contract/PublicRoutesConsistencyTest`: contrato, `application.yml`, `application-local.yml` y filtro coinciden;
  el catálogo de recursos protegidos no incluye rutas públicas. Escrita; pendiente de verificación en CI.
- [ ] T004 `ITest/adapter/in/rest/PublicRoutesPepE2ETest`: PEP activo con configuración por defecto, 200 en contrato y api-docs sin
  credencial, 401 en cada operación del contrato, en rutas con prefijo común y en la UI; control positivo con cookie; puerto de
  gestión. Escrita; pendiente de verificación en CI.
- [ ] T005 `ITest/adapter/in/rest/PublicRoutesLocalPepE2ETest`: perfil `local`, la UI responde sin credencial y el negocio sigue en 401. Escrita; pendiente de verificación en CI.

## Phase 3: Implementación

- [x] T006 `IMain/adapter/in/web/PublicRoutes` y `AuthenticationWebFilter`: exenciones de documentación derivadas de
  `security.pep.enforcement.public-paths`.
- [x] T007 `IRes/application.yml` y `application-local.yml`: `public-paths` por defecto, `springdoc.swagger-ui.enabled`.

## Phase 4: Documentación y verificación

- [x] T008 README (el quickstart de 015 no se tocó): rutas públicas y variables.
- [ ] T009 `spotless:apply` (hecho), arquitectura y `verify` completo: pendiente de verificación en CI (por instrucción del usuario no se ejecutaron pruebas tras una primera corrida parcial).
