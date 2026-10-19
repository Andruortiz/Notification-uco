# Tasks: Contrato v2 de la API con rutas literales protegibles por el PEP/PDP (HU2-098)

**Input**: `specs/024-api-v2-rutas-literales/` (spec.md, plan.md aprobado 2026-10-19, research.md, quickstart.md, contracts/rutas-v1-v2.md)

**Prerequisites**: plan.md aprobado; rama con los cuatro renombres ya aplicados (commit 6646bb6).

**Tests**: Requeridos (Principio IV). Orden por historia: prueba en rojo, implementación, prueba en verde.

## Format: `[ID] [P?] [Story] Description`

Rutas base: `API` = `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`;
`IMain` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure`;
`ITest` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure`.

## Phase 1: Setup

- [x] T001 Enmendar el Principio II de `.specify/memory/constitution.md` (rutas literales, identificadores en query o cuerpo, retirar `recurso:accion` y `GET /notifications/{id}`), subir la versión de 1.2.2 a 2.0.0 y completar el Sync Impact Report
- [x] T002 Leer el jar `~/.m2/repository/co/edu/uco/security-pep-integration-spring-boot-starter/0.1.3` y fijar en `research.md` la forma exacta de los errores 401/403/503 del PEP (campos y cabecera `X-Decision-Id`)

## Phase 2: Foundational

- [x] T003 Crear `ITest/contract/ApiContract.java`: cargador SnakeYAML del contrato con `operations()` (método, ruta, rol mínimo, estado, operationId, respuestas) y `schemas()`, reutilizando el cargador de `ErrorResponseContractTest`

## Phase 3: User Story 1 - Rutas literales que coinciden con el código (P1)

**Independent Test**: guardia de literalidad y comparación contrato/controladores.

- [x] T004 [US1] Prueba (c) `ITest/contract/ApiContractLiteralPathsTest.java`: ninguna clave de `paths` con `:`, `{`, `}`, `*`, `//`, espacio; cumple `^/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$`; control positivo con una ruta sintética con `:`; verla fallar contra el contrato v1
- [x] T005 [US1] Contrato `API`: rutas v2 de las 17 operaciones, parámetros de query (`notificationId`, `uploadId`, `recipientId`), cuerpos de `/notification-retries` y `/attachment-upload-completions`, `POST /channels`, `POST /providers`, `PUT /recipients/preferences`, `Location` de la subida, `x-status` y `x-minimum-role` en cada operación
- [x] T006 [US1] Prueba (a) `ITest/contract/ApiContractMatchesControllersTest.java`: compara (método, ruta) de `RequestMappingHandlerMapping` con las `available`; ninguna `planned` servida; 404 en `/notifications/{id}` y `/attachment-uploads/{id}`; verla fallar antes del código
- [x] T007 [US1] `IMain/adapter/in/rest/NotificationController.java`: `getStatus` pasa a `GET /notifications/detail` con `@RequestParam notificationId` (sin `@PathVariable`)
- [x] T008 [US1] `IMain/adapter/in/rest/AttachmentUploadController.java`: `get` pasa a `GET /attachment-uploads/status` con `@RequestParam uploadId`; `Location` = `/attachment-uploads/status?uploadId=<id>`
- [x] T009 [US1] Migrar las pruebas existentes con rutas v1 (`/notifications/{id}`, `/attachment-uploads/<id>`, concatenaciones) en `ITest/**`
- [x] T010 [US1] E2E `ITest/contract/ApiV2RoutesE2ETest.java (con ITest/contract/ApiV2E2ESupport.java que comparte contenedores y contexto con la E2E de conformidad; desviacion de ubicacion)`: enviar, detalle por query, subida con `Location`, `status`, completar por cuerpo; 400 por parámetro faltante; 404 en rutas v1; `recipientId`-estilo con codificación porcentual; dos tenants sin cruce por identificador en query

## Phase 4: User Story 2 - Lista canónica de recursos protegidos (P1)

- [x] T011 [US2] Prueba (b) `ITest/contract/RouteAuthorizationPolicyContractTest.java`: `minimumRoleFor` coincide con `x-minimum-role` para cada operación; ruta desconocida da ADMINISTRADOR
- [x] T012 [US2] `IMain/adapter/in/web/RouteAuthorizationPolicy.java`: tabla (método, ruta literal) a rol, sin expresiones regulares
- [x] T013 [US2] Prueba (d) `ITest/contract/ProtectedResourcesCatalogTest.java`: 17 entradas sin duplicados, rutas aptas, perfiles acumulativos calculados; escribe `infrastructure/target/contract/protected-resources.json`

## Phase 5: User Story 3 - Respuestas tipadas (P1)

- [x] T014 [US3] Contrato `API`: `required` en `SendNotificationResponse`, `NotificationStatusResponse`, `BatchItemResult`, `NotificationHistoryItem` y `DeliveryAttempt`; `nullable` pasa a `type: [..., "null"]` en esos cinco esquemas, verificado contra los records
- [x] T015 [US3] E2E `ITest/contract/ResponseSchemasConformanceE2ETest.java`: respuestas reales del servicio (enviar, detalle, búsqueda, lote, subida, catálogos, configuración) validadas contra `required`, nulabilidad y enums del contrato

## Phase 6: User Story 4 - Seguridad documentada (P2)

- [x] T016 [US4] Contrato `API`: esquema `SessionCookie` (`apiKey`, cookie `SECURITY_BASELINE_SESSION`), seguridad global Bearer o cookie, `PepErrorResponse` según T002, respuestas 401/403/503 con `X-Decision-Id` en las 17 operaciones (401/403 con `oneOf` `PepErrorResponse`/`ErrorResponse`)
- [x] T017 [US4] Ajustar `ITest/contract/ErrorResponseContractTest.java` para aceptar `PepErrorResponse` en 401/403/503 y `oneOf` en 401/403
- [x] T018 [US4] Prueba `ITest/contract/SecurityDocumentationContractTest.java`: `SessionCookie`, 401/403/503 y `X-Decision-Id` en las 17 operaciones

## Phase 7: User Story 5 - Guía de migración (P2)

- [x] T019 [US5] Contrato `API`: `info.version` 2.0.0 y registro de cambios (incompatibilidades y tabla v1 a v2 de las 17 operaciones)
- [x] T020 [US5] Prueba `ITest/contract/ChangelogContractTest.java`: versión 2.0.0 y changelog menciona las 17 rutas v1 y v2
- [x] T021 [US5] README.md con rutas v2 y prueba `ITest/contract/DocsRoutesGuardTest.java` sobre README y documentos versionados vigentes; documentar para el usuario los cambios requeridos en `docs/` (no versionado)

## Phase 8: Polish

- [ ] T022 `./mvnw -B -ntp spotless:apply` y pruebas `HexagonalArchitectureTest` y `ModularityTests`
- [ ] T023 `./mvnw -B -ntp verify` completo (RabbitMQ desechable en 5674, excluyendo las dos pruebas de DLQ) y corrección de lo que falle
- [ ] T024 Cierre: revisar tareas, desviaciones y excepciones del plan; informe

## Desviaciones registradas

- La base 6646bb6 referenciaba AttachmentUploadCompletionController y CompleteAttachmentUploadRequest sin incluirlos; se agregaron en el commit 5891796 antes de seguir (no estaban en el plan).
- El contrato absorbe el 400 por parametro faltante en detalle y estado de subida, y la E2E de conformidad cubre las 11 operaciones disponibles distintas de GET /notifications/stream, que ya cubren las E2E del panel en vivo.
- docs/ no esta versionado: sus cambios se entregan como lista al usuario (T021).

## Dependencies

T001, T002 antes de T016. T003 antes de todas las pruebas de contrato. T004 y T006 (rojo) antes de T005/T007/T008. T012 depende de T005. T009 depende de T007 y T008. T013 depende de T005. T023 al final.

## Notes

Commits de una línea, sin atribución. Cero comentarios en código nuevo. Sin cambios en `pom.xml`.
