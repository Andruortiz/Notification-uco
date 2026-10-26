# Tasks: Modelo de autorización no lineal y su aprovisionamiento (HU2-123)

**Entrada**: [spec.md](spec.md), [plan.md](plan.md). Cada tarea: prueba primero, luego implementación. Los números de
FR/SC remiten a [spec.md](spec.md).

## Fase 1: core

- [ ] T001 `Role` con los seis valores sin orden y sin `satisfies`; `RoleTest` (valores, `of`, desconocido) (FR-001).
- [ ] T002 `AuthenticatedPrincipal(subject, tenantId, Set<Role> roles, clientId)` con conjunto no vacío, constructor de un rol y `seesUnmaskedData()` (AP o AA); `AuthenticatedPrincipalTest` (FR-002).
- [ ] T003 `DeviceService` usa `seesUnmaskedData()` en lugar de `Role.ADMINISTRADOR`; `DeviceServiceTest` migrado.

## Fase 2: infrastructure, autenticación y autorización

- [ ] T004 `PlatformJwtTokenValidationAdapter` resuelve un conjunto de roles: texto o lista, desconocidos ignorados, sin ninguno conocido `UNKNOWN_ROLE` (401); `PlatformJwtTokenValidationAdapterTest` con claim texto, lista y mezcla (FR-006).
- [ ] T005 `RouteAuthorizationPolicy.allowedRolesFor(method, path)` con el mapa de las 33 operaciones del contrato, ruta no declarada solo AP y con PEP todos los roles; `AuthenticationWebFilter` permite si hay intersección y, si no, 403 `INSUFFICIENT_ROLE`; `RouteAuthorizationPolicyTest` (FR-004, FR-005).
- [ ] T006 Respuestas que enmascaran (`DeviceResponse`, `NotificationTimelineResponse`, `NotificationHistoryItemResponse`, `UndeliverableRecipientResponse`, `NotificationLiveUpdateResponse`, `NotificationSearchResponse`) reciben `boolean unmasked` desde los controladores (E5, E10, G3).
- [ ] T007 `PdpSessionIdentityAdapter` sigue con `Role.CLIENTE` (constructor de un rol); sin cambio de código, se verifica en `PdpSessionIdentityAdapterTest`.

## Fase 3: contrato (solo aquí se edita el YAML en esta historia)

- [ ] T008 `api-notificaciones.yaml`: `x-allowed-roles` en las 33 operaciones según la tabla del plan; sin `x-minimum-role`; cabecera describe `x-allowed-roles`; `info.version` 2.2.0 y entrada de changelog (FR-003).
- [ ] T009 `ApiContract` lee `x-allowed-roles`; `ApiContractMatchesControllersTest`, `ChangelogContractTest`, `SecurityDocumentationContractTest` y `RouteAuthorizationPolicyContractTest` adaptados. Prueba nueva: toda operación tiene lista no vacía, sin duplicados, con roles conocidos, y la política coincide con el contrato (FR-009, escenario 1.3).

## Fase 4: catálogo y aprovisionamiento del PDP (spec 028)

- [ ] T010 `ProtectedResourcesCatalog` emite `allowedRoles` y deja de derivar perfiles acumulativos; `ProtectedResourcesCatalogTest` (FR-007).
- [ ] T011 `scripts/provision-pdp.ps1`: valida listas (conocidas, no vacías, sin duplicados) antes de cualquier llamada; crea seis roles; vincula cada rol a los recursos `available` que lo listan; crea `PERFIL_<ROL>` con un único rol; idempotente por tenant; informa divergencias sin borrar; imprime el tenant de la sesión (FR-007, FR-008).
- [ ] T012 `FakePdpAdmin` con estado por tenant y `PdpProvisioningScriptTest` reescrito: estado = contrato (SC-003 con segunda ejecución sin escrituras), dos tenants sin cruce, esquema acumulativo informado, lista inválida sin llamadas, credencial fuera de la salida (FR-009, SC-003).
- [ ] T013 `scripts/dev-token.ps1` acepta los seis roles; `loadtest/token.js` y `README.md` usan los nombres nuevos.

## Fase 5: migración del nivel 1

- [ ] T014 Pruebas de unidad y de controlador migradas: `ADMINISTRADOR` pasa a `ADMINISTRADOR_PLATAFORMA`; `OPERADOR` que escribe plantillas pasa a `EDITOR_PLANTILLAS`; `CLIENTE` sin cambio.
- [ ] T015 Pruebas E2E migradas (`DeviceRegistryE2ETest`, `TemplateE2ETest`, `ConfigurationObservabilityE2ETest`, `ProviderHealthE2ETest`, `NotificationTimelineE2ETest`, `RecipientValidationE2ETest`, `ProviderConnectionTest*`, `NotificationRetryE2ETest`, `NotificationResendE2ETest`, `AuthenticationInterinaE2ETest`, `AuthenticationSecurityE2ETest`, `IdentityFromTokenE2ETest`, `ParametersEventSubscriptionE2ETest`, `ResponseSchemasConformanceE2ETest`). Donde el rol nuevo cambia el resultado según el contrato, la aserción se corrige y la desviación queda anotada abajo.

## Fase 6: constitución

- [ ] T016 Enmienda 2.1.0 en `.specify/memory/constitution.md` y `constitution-resumen.md` (Principio II: `x-allowed-roles` en lugar de `x-minimum-role`), fecha y entrada de versión. Commit aparte.

## Fase 7: prueba end-to-end y cierre

- [ ] T017 `RoleAuthorizationE2ETest` (`@SpringBootTest` RANDOM_PORT, Mongo y RabbitMQ en Testcontainers, tokens RS256 de `TestTokens`): para cada rol único recorre las operaciones `available` del contrato y afirma permitido o 403 `INSUFFICIENT_ROLE` (SC-001); unión de dos roles (SC-002); rol desconocido 401 (FR-006); ruta no declarada solo AP; dos tenants (FR-010, aislamiento).
- [ ] T018 Pruebas dirigidas de las clases tocadas y, en un lote final, `HexagonalArchitectureTest`, `ModularityTests`, `-pl utils,core -am test` completo.

## Desviaciones

- (se anotarán durante la implementación)

## Límites fuera de esta historia

Ver [spec.md](spec.md#límites-documentados-principio-vii): precondición de sesión con roles (Seguridad, 2026-12-15), política OPA, creación automática de perfiles, consola C1b y migración manual de PDPs ya aprovisionados (AP, 2026-11-12). `verify` completo queda para el cierre de CI.
