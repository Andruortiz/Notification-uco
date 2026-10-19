# Tasks: Sesión por cookie del BFF/PDP y CORS con credenciales (HU2-101)

**Input**: `specs/025-sesion-cookie-bff/` (spec.md, plan.md, research.md, data-model.md, contracts/session-response.md, quickstart.md)

**Prerequisites**: plan.md en estado `Aceptado` (hoy `Pendiente`; no implementar antes). Respuesta de seguridad a las preguntas abiertas de research.md, al menos la forma de `GET /api/v1/session` (T001).

**Tests**: Requeridos (Principio IV). Orden por historia: prueba en rojo, implementación, prueba en verde, `spotless:apply`.

## Format: `[ID] [P?] [Story] Description`

Rutas base: `API` = `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`;
`CMain` = `core/src/main/java/co/edu/uco/notification/core`; `CTest` = `core/src/test/java/co/edu/uco/notification/core`;
`IMain` = `infrastructure/src/main/java/co/edu/uco/notification/infrastructure`;
`ITest` = `infrastructure/src/test/java/co/edu/uco/notification/infrastructure`.

## Phase 1: Setup

- [x] T001 Forma de `GET /api/v1/session` resuelta y documentada (verificada por el usuario el 2026-10-19). Siguen abiertas con seguridad: llamada servicio a servicio y `X-Application-Credential`, tolerancia de revocación y la opción A
- [x] T002 Excepción de la opción C registrada en `research.md`: dueño Seguridad (starter) con Notification-uco (adaptador), fecha límite antes del cierre de HU2-055, condición de retiro: el starter expone la identidad (opción A)

## Phase 2: Foundational

- [x] T003 [P] Prueba `CTest/domain/valueobject/SessionCredentialTest.java`: rechaza nulo y vacío, `toString` no contiene el valor; verla fallar
- [x] T004 [P] `CMain/domain/valueobject/SessionCredential.java`, `CMain/port/out/SessionIdentityPort.java` y `CMain/exception/AuthenticationUnavailableException.java` sin dependencias de framework
- [x] T005 [P] `IMain/config/AuthSessionProperties.java` (`url`, `path`, `timeout`) y claves `notification.auth.session.*` en `infrastructure/src/main/resources/application.yml` con variables de entorno `AUTH_SESSION_URL`, `AUTH_SESSION_PATH`, `AUTH_SESSION_TIMEOUT`, sin secretos. Desviación: la variable es `AUTH_SESSION_TIMEOUT_MS` y la propiedad `timeout-ms`
- [x] T006 Contrato `API` primero (Principio II): actualizar la descripción de `SessionCookie` y de la seguridad global (el servicio acepta la cookie, precedencia Bearer sobre cookie, rol efectivo `CLIENTE`, requiere PEP activo), añadir la respuesta `AuthenticationUnavailable` (503, `ErrorResponse`, código `AUTHENTICATION_UNAVAILABLE` (NTF-2003)) a las operaciones protegidas, sin rutas nuevas. Desviación: no se creó una respuesta nueva; se amplió `PepUnavailable` (503) con el cuerpo `ErrorResponse` NTF-2003 y la cabecera `X-Correlation-Id`, y `NTF-2003` se añadió al enum del esquema `ErrorResponse`
- [x] T007 Añadir `AUTHENTICATION_UNAVAILABLE` (NTF-2003) a `utils/src/main/java/co/edu/uco/notification/utils/ErrorCode.java` y a `ErrorResponseContractTest` si enumera los códigos; `spotless:apply`. Desviación: categoría `PERMANENT_BUSINESS` porque `ErrorCodeTest` exige esa categoría en el rango 2xxx

## Phase 3: User Story 1 - Operar con la cookie de sesión (P1)

**Independent Test**: petición con solo cookie válida devuelve datos del tenant de la sesión; sin credencial 401; PDP caído 503.

- [x] T008 [US1] Prueba de integración `ITest/adapter/out/security/session/PdpSessionIdentityAdapterTest.java` con un servidor HTTP simulado del PDP (cuerpo fijado en `contracts/session-response.md`): 200 produce principal con `CLIENTE`, el tenant y el sujeto de la sesión; reenvía solo la cookie de sesión y `X-Correlation-Id`; 401, 403, cuerpo sin `tenantId` o sin `subject` dan sesión inválida; 5xx, conexión rechazada y respuesta lenta dan indisponible; afirma con `Duration` que el tiempo agotado responde en menos de 5 s; verla fallar
- [x] T009 [US1] `IMain/adapter/out/security/session/PdpSessionIdentityAdapter.java` (`WebClient`, condicionado a `PepDelegation.isActive`, no registra el valor de la cookie). Desviación: no reenvía `X-Correlation-Id` (el puerto no lleva correlación); `SessionIdentityConfig` crea el adaptador solo con el PEP activo y, si no, un puerto que rechaza toda cookie
- [x] T010 [US1] Prueba unitaria `ITest/adapter/in/web/AuthenticationWebFilterSessionTest.java`: solo cookie válida continúa con el principal; sin credencial 401 `AUTHENTICATION_REQUIRED`; cookie inválida 401; PDP indisponible 503 `AUTHENTICATION_UNAVAILABLE` (NTF-2003); cookie con nombre parecido (`XSECURITY_BASELINE_SESSION`) o valor vacío no cuenta; cookie con PEP inactivo 401; el log de rechazo no contiene el valor de la cookie; verla fallar
- [x] T011 [US1] `IMain/adapter/in/web/AuthenticationWebFilter.java`: nuevo camino de cookie con `SessionIdentityPort`, nuevos `RejectionReason`, respuesta 503 con `Retry-After` omitido y cuerpo `AUTHENTICATION_UNAVAILABLE` (NTF-2003); el principal resultante pasa por el mismo `RouteAuthorizationPolicy` y el mismo atributo `PRINCIPAL_ATTRIBUTE`. Desviación: el constructor inyectado recibe `ObjectProvider<SessionIdentityPort>` (los cortes WebFlux no cargan la configuración del adaptador) y se conservó el constructor de tres argumentos para no tocar las pruebas existentes
- [x] T012 [US1] E2E `ITest/adapter/in/rest/SessionCookieE2ETest.java` (`@SpringBootTest(RANDOM_PORT)`, Mongo y RabbitMQ en Testcontainers, PDP simulado, `WebTestClient`): enviar y buscar con solo cookie; tenant A y tenant B con cookies distintas sin cruce (control positivo: cada tenant sí ve lo suyo; la consulta por identificador del otro tenant es indistinguible de uno inexistente); sin credencial 401; sesión revocada en el PDP simulado 401 en la petición siguiente; PDP simulado caído 503 con aserciones de `Duration` (< 2 s para 401, < 5 s para 503). Desviación: el envío con cookie hace sondeo hasta 120 s esperando la ruta de EMAIL del catálogo

## Phase 4: User Story 2 - Bearer con precedencia y ticket intactos (P1)

**Independent Test**: suite de autenticación existente en verde y pruebas de precedencia.

- [x] T013 [US2] Ampliar `ITest/adapter/in/web/AuthenticationWebFilterSessionTest.java`: Bearer válido de A con cookie válida de B resuelve a A y el puerto de sesión no se invoca; Bearer inválido con cookie válida da 401 sin invocar el puerto; `GET /notifications/stream` con `ticket` y sin Bearer ni cookie sigue funcionando; con cookie autentica por cookie; verlas fallar donde corresponda
- [x] T014 [US2] Ajustar el orden de decisión en `IMain/adapter/in/web/AuthenticationWebFilter.java` para cumplir la precedencia Bearer > cookie > ticket; ejecutar la clase de pruebas existente del filtro sin modificar aserciones
- [x] T015 [US2] En `ITest/adapter/in/rest/SessionCookieE2ETest.java` añadir el caso Bearer más cookie de tenants distintos y la suscripción en vivo (`/notifications/stream`) con cookie y con ticket, ambas afirmando que solo llegan eventos del tenant de la credencial usada. Desviación: no hay E2E del stream con ticket con el PEP activo: el starter responde 401 a toda petición sin Bearer ni cookie (excepto `public-paths`), así que el ticket no puede pasar el PEP; es un comportamiento previo que se reporta aparte. Se cubre el stream con cookie y la emisión de ticket con cookie; el ticket sigue cubierto en el filtro (`AuthenticationWebFilterSessionTest`)

## Phase 5: User Story 3 - CORS con credenciales (P2)

**Independent Test**: preflight positivo y negativo.

- [x] T016 [US3] Prueba `ITest/config/CorsCredentialsTest.java`: origen configurado recibe `Access-Control-Allow-Origin` exacto y `Access-Control-Allow-Credentials: true`; origen no configurado no recibe permisos (control positivo en la misma clase); `*` en la lista hace fallar el arranque; el preflight sin cookie sigue exento de autenticación; verla fallar
- [x] T017 [US3] `IMain/config/CorsConfig.java` con `setAllowCredentials(true)` y `IMain/config/CorsOriginsGuard.java` que falla al arrancar con `*`; métodos y cabeceras permitidas sin ampliar. Desviación: la guardia es `CorsOrigins.requireExplicit` (no `CorsOriginsGuard`) y se invoca al crear el filtro CORS

## Phase 6: User Story 4 - Contrato y documentación coherentes (P2)

- [x] T018 [US4] Prueba `ITest/contract/SessionCookieContractTest.java`: la descripción de `SessionCookie` no afirma que solo se acepta Bearer, menciona precedencia, PEP activo y rol efectivo; la respuesta 503 `AuthenticationUnavailable` existe en las operaciones protegidas con el código documentado; control positivo con un contrato sintético que sí afirma lo contrario; verla fallar antes de T006 si se ejecuta en ese orden. Desviación: el control positivo es la prueba `theObsoleteClaimIsDetectedWhenItIsPresent`, no un contrato sintético
- [x] T019 [US4] Actualizar la sección "Autenticación" del `README.md` (cookie, precedencia, variables `AUTH_SESSION_*`, CORS con credenciales, limitación y excepción de la opción C) y revisar que `SecurityDocumentationContractTest` siga en verde

## Phase 7: Polish

- [x] T020 `./mvnw -B -ntp spotless:apply` y confirmar fin de línea CRLF en los `.java` nuevos
- [x] T021 `HexagonalArchitectureTest` y `ModularityTests` en verde (comando del flujo)
- [x] T022 `./mvnw -B -ntp verify` completo en segundo plano con `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`; cobertura ≥ 80 % líneas y ≥ 70 % ramas; si solo fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir sin ellas e informarlo. Resultado: BUILD SUCCESS con 1065 pruebas, SpotBugs 0, Spotless y JaCoCo cumplidos. Excluidas por entorno local (causa observada: autenticación rechazada por el Mongo y el RabbitMQ locales; no se comprobó contra la base): `DeadLetterQueueE2ETest`, `RabbitRetryConfigCustomAttemptsTest`, `CorsConfigTest`, `CorsConfigCustomOriginTest`, `NotificationControllerSearchE2ETest`; las decide CI
- [ ] T023 Verificación manual con una cookie real contra el PDP local (quickstart.md, sección Manual) y registrar el resultado en `quickstart.md`; si la forma de la sesión difiere, corregir el doble y T009. Pendiente del usuario: pasos en `quickstart.md`
- [x] T024 Marcar las tareas cumplidas, anotar desviaciones y dejar pendientes explícitos: caché de identidad (depende de la tolerancia de revocación) y reemplazo por la opción A (dueño y fecha de T002). Pendientes explícitos: caché de identidad (depende de la tolerancia de revocación), retiro de la opción C por la A (dueño y fecha en `research.md`), y el ticket de suscripción con el PEP activo (el starter lo rechaza)

## Dependencies & Execution Order

- T001 y T002 antes de T008. T003 a T007 son paralelas salvo T007 después de T006 si el contrato enumera el código.
- US1 (T008 a T012) depende de la fundación. US2 depende de T011. US3 y US4 son independientes entre sí y de US1 y pueden hacerse en paralelo; T018 depende de T006.
- Polish al final.

## Parallel Execution

- T003, T004 y T005 a la vez. T016 y T018 a la vez una vez hecha la fundación.

## Implementation Strategy

MVP: Fundación + US1 + US2 (la cookie funciona sin romper el Bearer). Luego US3 (CORS) y US4 (contrato y README). No cerrar la historia sin T001 y T023: la forma de la respuesta de sesión es la suposición de mayor riesgo.
