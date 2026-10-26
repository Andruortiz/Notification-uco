# Tasks: Modelo de autorización no lineal y su aprovisionamiento (HU2-123)

**Entrada**: [spec.md](spec.md), [plan.md](plan.md). Cada tarea: prueba primero, luego implementación. Los números de
FR/SC remiten a [spec.md](spec.md).

## Fase 1: core

- [x] T001 `Role` con los seis valores sin orden y sin `satisfies`; `RoleTest` (valores, `of`, desconocido) (FR-001).
- [x] T002 `AuthenticatedPrincipal(subject, tenantId, Set<Role> roles, clientId)` con conjunto no vacío, constructor de un rol y `seesUnmaskedData()` (AP o AA); `AuthenticatedPrincipalTest` (FR-002).
- [x] T003 `DeviceService` usa `seesUnmaskedData()` en lugar de `Role.ADMINISTRADOR`; `DeviceServiceTest` migrado.

## Fase 2: infrastructure, autenticación y autorización

- [x] T004 `PlatformJwtTokenValidationAdapter` resuelve un conjunto de roles: texto o lista, desconocidos ignorados, sin ninguno conocido `UNKNOWN_ROLE` (401); `PlatformJwtTokenValidationAdapterTest` con claim texto, lista y mezcla (FR-006).
- [x] T005 `RouteAuthorizationPolicy.allowedRolesFor(method, path)` con el mapa de las 33 operaciones del contrato, ruta no declarada solo AP y con PEP todos los roles; `AuthenticationWebFilter` permite si hay intersección y, si no, 403 `INSUFFICIENT_ROLE`; `RouteAuthorizationPolicyTest` (FR-004, FR-005).
- [x] T006 Respuestas que enmascaran (`DeviceResponse`, `NotificationTimelineResponse`, `NotificationHistoryItemResponse`, `UndeliverableRecipientResponse`, `NotificationLiveUpdateResponse`, `NotificationSearchResponse`) reciben `boolean unmasked` desde los controladores (E5, E10, G3).
- [x] T007 `PdpSessionIdentityAdapter` sigue con `Role.CLIENTE` (constructor de un rol); sin cambio de código, se verifica en `PdpSessionIdentityAdapterTest`.

## Fase 3: contrato (solo aquí se edita el YAML en esta historia)

- [x] T008 `api-notificaciones.yaml`: `x-allowed-roles` en las 33 operaciones según la tabla del plan; sin `x-minimum-role`; cabecera describe `x-allowed-roles`; `info.version` 2.2.0 y entrada de changelog (FR-003).
- [x] T009 `ApiContract` lee `x-allowed-roles`; `ApiContractMatchesControllersTest`, `ChangelogContractTest`, `SecurityDocumentationContractTest` y `RouteAuthorizationPolicyContractTest` adaptados. Prueba nueva: toda operación tiene lista no vacía, sin duplicados, con roles conocidos, y la política coincide con el contrato (FR-009, escenario 1.3).

## Fase 4: catálogo y aprovisionamiento del PDP (spec 028)

- [x] T010 `ProtectedResourcesCatalog` emite `allowedRoles` y deja de derivar perfiles acumulativos; `ProtectedResourcesCatalogTest` (FR-007).
- [x] T011 `scripts/provision-pdp.ps1`: valida listas (conocidas, no vacías, sin duplicados) antes de cualquier llamada; crea seis roles; vincula cada rol a los recursos `available` que lo listan; crea `PERFIL_<ROL>` con un único rol; idempotente por tenant; informa divergencias sin borrar; imprime el tenant de la sesión (FR-007, FR-008).
- [x] T012 `FakePdpAdmin` con estado por tenant y `PdpProvisioningScriptTest` reescrito: estado = contrato (SC-003 con segunda ejecución sin escrituras), dos tenants sin cruce, esquema acumulativo informado, lista inválida sin llamadas, credencial fuera de la salida (FR-009, SC-003).
- [x] T013 `scripts/dev-token.ps1` acepta los seis roles; `loadtest/token.js` y `README.md` usan los nombres nuevos.

## Fase 5: migración del nivel 1

- [x] T014 Pruebas de unidad y de controlador migradas: `ADMINISTRADOR` pasa a `ADMINISTRADOR_PLATAFORMA`; `OPERADOR` que escribe plantillas pasa a `EDITOR_PLANTILLAS`; `CLIENTE` sin cambio.
- [x] T015 Pruebas E2E migradas (`DeviceRegistryE2ETest`, `TemplateE2ETest`, `ConfigurationObservabilityE2ETest`, `ProviderHealthE2ETest`, `NotificationTimelineE2ETest`, `RecipientValidationE2ETest`, `ProviderConnectionTest*`, `NotificationRetryE2ETest`, `NotificationResendE2ETest`, `AuthenticationInterinaE2ETest`, `AuthenticationSecurityE2ETest`, `IdentityFromTokenE2ETest`, `ParametersEventSubscriptionE2ETest`, `ResponseSchemasConformanceE2ETest`). Donde el rol nuevo cambia el resultado según el contrato, la aserción se corrige y la desviación queda anotada abajo.

## Fase 6: constitución

- [x] T016 Enmienda 2.1.0 en `.specify/memory/constitution.md` y `constitution-resumen.md` (Principio II: `x-allowed-roles` en lugar de `x-minimum-role`), fecha y entrada de versión. Commit aparte.

## Fase 7: prueba end-to-end y cierre

- [x] T017 `RoleAuthorizationE2ETest` (`@SpringBootTest` RANDOM_PORT, Mongo y RabbitMQ en Testcontainers, tokens RS256 de `TestTokens`): para cada rol único recorre las operaciones `available` del contrato y afirma permitido o 403 `INSUFFICIENT_ROLE` (SC-001); unión de dos roles (SC-002); rol desconocido 401 (FR-006); ruta no declarada solo AP; dos tenants (FR-010, aislamiento).
- [x] T018 Pruebas dirigidas de las clases tocadas y, en un lote final, `HexagonalArchitectureTest`, `ModularityTests`, `-pl utils,core -am test` completo.

## Desviaciones

- T005 y T006: `AuthenticationWebFilter` usa `Collections.disjoint` y el 403 conserva `INSUFFICIENT_ROLE`; el mensaje cambió a "none of the roles of the principal is allowed for this operation".
- T008: `GET /templates` y `GET /templates/detail` no incluyen AUDITOR (la tabla del plan lista CL, OP, ET, AA, AP); `/channels` y `/providers` GET sí, por ser "los seis".
- T009: `/recipients/preferences` (planned) lleva `[CLIENTE, ADMINISTRADOR_APLICACION]` con el criterio "AA solo para subject ajeno" pendiente de la rama por sujeto (sin implementar; la operación no se sirve).
- T006: `/devices` solo admite CLIENTE según la tabla; la rama de subject ajeno de `DeviceService` (antes admin) queda inalcanzable por ruta hasta la precondicion de Seguridad. `DeviceRegistryE2ETest` ahora afirma 403 para AP en `/devices`.
- T014 y T015: pruebas de adjuntos (`ApiV2E2ESupport`, `ApiV2RoutesE2ETest`, `ResponseSchemasConformanceE2ETest`) usan CLIENTE, porque CL y AA son los unicos roles de la lista de adjuntos y AP no. `ApiV2RoutesE2ETest.thePlannedOperationsAreNotServed` deja de probar `/notification-retries` (available desde 2.1.0; la lista estaba desfasada) y las pruebas de preferencias usan CLIENTE.
- Regla de categoria SEGURIDAD (critica) para AA y AP: no existe codigo que la aplique en nivel 1 (solo el flag `critical` de `TemplateCategory`); queda cubierta por la precondicion de Seguridad (2026-12-15), como dice el plan. `POST /notifications` admite CL, por lo que la rama queda abierta hasta esa fecha.
- T018: `verify` completo no se ejecuto en esta sesion (instruccion del lote); se corrieron pruebas dirigidas y `-pl utils,core -am test` completo.
- Constitucion: enmienda 2.1.0 en commit aparte (T016).
- Sin `protected-resources.json` versionado: el catalogo se genera en `target/contract` por `ProtectedResourcesCatalogTest`, como antes.

## Límites fuera de esta historia

Ver [spec.md](spec.md#límites-documentados-principio-vii): precondición de sesión con roles (Seguridad, 2026-12-15), política OPA, creación automática de perfiles, consola C1b y migración manual de PDPs ya aprovisionados (AP, 2026-11-12). `verify` completo queda para el cierre de CI.
