---

description: "Task list for HU2-096 — Autenticación interina del servicio"
---

# Tasks: Autenticación interina del servicio (puerto y adaptador local, preparación de HU2-055)

**Input**: Design documents from `/specs/015-autenticacion-interina/`

**Prerequisites**: plan.md (Aceptado), spec.md, research.md, data-model.md, contracts/,
quickstart.md

**Tests**: Incluidas — Principio IV de la constitución exige cobertura verificada y al menos una
prueba E2E explícita por historia, no solo unitarias.

**Organization**: Tareas agrupadas por historia de usuario (US1/US2/US3 de `spec.md`). El filtro de
autenticación es un único componente que crece incrementalmente: US1 lo deja fail-closed (401,
extracción del token incluida la excepción SSE), US2 conecta la identidad resuelta a los
controladores (tenant y rol ya no salen del header), US3 añade la tabla de autorización por rol
(403). El registro de rechazos (FR-015) se construye en Foundational porque lo usan tanto US1 como
US3.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: US1, US2 o US3 — solo en tareas de fase de historia
- Cada tarea incluye la ruta de archivo exacta

## Path Conventions

`core/`, `infrastructure/` — estructura hexagonal ya existente del repositorio (ver
`plan.md` → Project Structure).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Contrato, dependencias y configuración base antes de tocar código de dominio o
infraestructura.

- [X] T001 Actualizar `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`
  según `specs/015-autenticacion-interina/contracts/api-notificaciones-cambios.md`: esquema
  `BearerAuth`, `security` global, retiro de `X-Tenant-Id` de `components.parameters` y de cada
  operación, respuestas `401`/`403` donde aplique, parámetro `access_token` en
  `GET /notifications:subscribe`, notas de rol mínimo en las operaciones ya expuestas y en las
  planificadas (retry, preferencias, registro de canal/proveedor). Principio II — se hace antes que
  cualquier código.
- [X] T002 Añadir `io.jsonwebtoken:jjwt-api`, `io.jsonwebtoken:jjwt-impl` (runtime) y
  `io.jsonwebtoken:jjwt-jackson` (runtime) a `infrastructure/pom.xml`, con una propiedad de versión
  nueva en el `pom.xml` raíz si el proyecto centraliza versiones ahí (revisar convención existente de
  `spring-boot.version`, `resilience4j.version`, etc.).
- [X] T003 [P] Añadir `notification.auth.jwt.hs256-secret` (`${AUTH_JWT_HS256_SECRET}`, sin valor por
  defecto) y `notification.auth.jwt.ttl-minutes` (`${AUTH_JWT_TTL_MINUTES:720}`) a
  `infrastructure/src/main/resources/application.yml`, siguiendo la convención ya usada por
  `notification.*` en ese archivo.
- [X] T004 [P] Documentar `AUTH_JWT_HS256_SECRET` en `.env.example` con un valor de ejemplo marcado
  explícitamente como solo-desarrollo (nunca un secreto real), junto a las demás variables ya
  documentadas ahí.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Tipos de dominio, puerto de salida, adaptador de validación/emisión de tokens y utilidad
de registro de rechazos — todo lo que las tres historias comparten.

**⚠️ CRITICAL**: Ninguna historia empieza hasta que esta fase esté completa.

- [X] T005 [P] Crear el enum `Role` en
  `core/src/main/java/co/edu/uco/notification/core/domain/valueobject/Role.java`
  (`ADMINISTRADOR`, `OPERADOR`, `CLIENTE`, en ese orden; método `satisfies(Role required)`; método
  estático `of(String)` que valida el nombre exacto).
- [X] T006 [P] Crear el value object `AuthenticatedPrincipal` en
  `core/src/main/java/co/edu/uco/notification/core/domain/valueobject/AuthenticatedPrincipal.java`
  (record con `subject: String`, `tenantId: TenantId`, `role: Role`; validación de `subject` no vacío
  con `Preconditions.requireNonBlank`, igual que el resto de value objects del proyecto).
- [X] T007 [P] Crear `InvalidTokenException` en
  `core/src/main/java/co/edu/uco/notification/core/exception/InvalidTokenException.java`
  (`RuntimeException` simple, sin más jerarquía, siguiendo la forma de las excepciones ya existentes
  en ese paquete).
- [X] T008 [P] [US1] Unit test `RoleTest` en
  `core/src/test/java/co/edu/uco/notification/core/domain/valueobject/RoleTest.java` — verificar la
  jerarquía completa `ADMINISTRADOR.satisfies(OPERADOR)==true`,
  `CLIENTE.satisfies(OPERADOR)==false`, `Role.of("invalido")` lanza excepción. Escribir primero,
  confirmar que falla por no existir `Role` todavía.
- [X] T009 [P] [US1] Unit test `AuthenticatedPrincipalTest` en
  `core/src/test/java/co/edu/uco/notification/core/domain/valueobject/AuthenticatedPrincipalTest.java`
  — `subject` en blanco lanza excepción. Escribir primero.
- [X] T010 Crear `TokenValidationPort` en
  `core/src/main/java/co/edu/uco/notification/core/port/out/TokenValidationPort.java`
  (`Mono<AuthenticatedPrincipal> validate(String rawToken)`, sin importar JJWT ni Spring — depende de
  T006).
- [X] T011 [P] Crear `RejectionReason` en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/RejectionReason.java`
  (enum: `MISSING_TOKEN`, `MALFORMED_TOKEN`, `INVALID_SIGNATURE`, `EXPIRED`, `MISSING_CLAIMS`,
  `UNKNOWN_ROLE`, `INSUFFICIENT_ROLE`).
- [X] T012 [P] Crear `AuthenticationLogFormatter` en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationLogFormatter.java`
  — misma forma que `AttachmentLogFormatter` (sanitiza `tenantId`, que puede ser `null`; compone la
  línea de log con `RejectionReason`; nunca recibe el token ni el secreto como parámetro).
- [X] T013 [P] Unit test `AuthenticationLogFormatterTest` en
  `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationLogFormatterTest.java`
  — `tenantId` nulo se formatea sin lanzar excepción; caracteres de control se sanitizan igual que
  `AttachmentLogFormatter`. Escribir primero.
- [X] T014 Crear `LocalJwtTokenIssuer` en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/security/local/LocalJwtTokenIssuer.java`
  — clase Java plana (sin anotaciones Spring), constructor con el secreto HS256, método
  `issue(TenantId tenantId, Role role, String subject, Duration ttl)` que produce un JWT firmado con
  claims `sub`, `tenantId`, `role`, `iat`, `exp` (JJWT).
- [X] T015 Crear `LocalJwtTokenValidationAdapter implements TokenValidationPort` en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/security/local/LocalJwtTokenValidationAdapter.java`
  — verifica firma HS256, expiración y presencia/validez de `sub`/`tenantId`/`role`; cualquier fallo
  termina el `Mono` en error con `InvalidTokenException` (depende de T007, T010, T014).
- [X] T016 [US1] Unit test `LocalJwtTokenValidationAdapterTest` en
  `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/security/local/LocalJwtTokenValidationAdapterTest.java`
  — casos: token válido (emitido con `LocalJwtTokenIssuer`, nunca JSON armado a mano) → completa con
  el `AuthenticatedPrincipal` esperado; firma con secreto distinto, expirado, `tenantId`/`role`
  faltante, `role` desconocido → cada uno termina en `InvalidTokenException`. Escribir primero,
  confirmar que falla por no existir el adaptador.
- [X] T017 Crear `SecurityConfig` en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/SecurityConfig.java`
  — expone `TokenValidationPort` (bean `LocalJwtTokenValidationAdapter`, leyendo
  `notification.auth.jwt.hs256-secret`) y, si se decide exponerlo para conveniencia de desarrollo
  manual, `LocalJwtTokenIssuer` (depende de T014, T015).
  **Desviación**: `ModularityTests` (Spring Modulith) rechaza que `config` dependa directamente de un
  tipo no expuesto de un paquete anidado de `adapter` (mismo patrón que ya aplica a
  `MinioAttachmentStorageAdapter`/`TwilioNotificationProvider`). En vez de un método `@Bean` en
  `SecurityConfig` que instancia `LocalJwtTokenValidationAdapter`, la clase se anota `@Component`
  directamente (constructor `AuthJwtProperties`, con un segundo constructor `String` para uso directo
  desde pruebas) y Spring la descubre por component scan; `SecurityConfig` solo registra
  `AuthJwtProperties` nueva (`@ConfigurationProperties(prefix = "notification.auth.jwt")`) vía
  `@EnableConfigurationProperties`, igual que el resto de adaptadores del repositorio. `LocalJwtTokenIssuer`
  no se expone como bean (no lo necesita ningún componente productivo); las pruebas lo instancian
  directamente con `new`.
- [X] T018 Confirmar que `HexagonalArchitectureTest`/`ModularityTests`
  (`./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false`)
  siguen en verde con los tipos nuevos de `core` y `infrastructure`.

**Checkpoint**: puerto, adaptador, tipos de dominio y utilidad de log listos — las historias pueden
empezar.

---

## Phase 3: User Story 1 - Rechazar solicitudes sin identidad verificable (Priority: P1) 🎯 MVP

**Goal**: toda solicitud a un endpoint protegido sin un token válido (ausente, mal formado, firma
inválida, expirado, o con claims obligatorias faltantes/`role` desconocido) recibe `401` antes de
tocar cualquier caso de uso, y el rechazo queda registrado (FR-015) sin exponer el token.

**Independent Test**: instanciar `AuthenticationWebFilter` con un `TokenValidationPort` real
(respaldado por `LocalJwtTokenValidationAdapter`) y un `WebFilterChain` espía, y verificar con
`MockServerWebExchange`/`MockServerHttpRequest` que cada variante de token inválido responde `401` sin
invocar `chain.filter(...)`, mientras que un token válido sí lo invoca — sin necesidad de un
`@SpringBootTest` completo ni de los controladores ya modificados (US2).

### Tests for User Story 1 ⚠️

> **Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [ ] T019 [P] [US1] Unit test `AuthenticationWebFilterTest` (casos de rechazo) en
  `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationWebFilterTest.java`
  — sin header `Authorization` → `401` + log `MISSING_TOKEN`/`tenantId=null`; header sin prefijo
  `Bearer ` → `401`; token sintácticamente inválido → `401` + log `MALFORMED_TOKEN`; token expirado →
  `401` + log `EXPIRED`; token con claim faltante → `401` + log `MISSING_CLAIMS`; token con `role`
  desconocido → `401` + log `UNKNOWN_ROLE`; ninguno de estos casos invoca `chain.filter(...)` ni
  expone el token/secreto en el log capturado.
- [ ] T020 [P] [US1] Ampliar `AuthenticationWebFilterTest` con los casos de ruta exenta: `OPTIONS` a
  cualquier path, `GET /actuator/health`, `GET /v3/api-docs` (o la ruta real que sirva springdoc) y
  `GET /openapi/api-notificaciones.yaml` completan sin exigir token.
- [ ] T021 [P] [US1] Ampliar `AuthenticationWebFilterTest` con el caso `GET /notifications:subscribe`
  sin header `Authorization` pero con `?access_token=<token-válido>` → completa (invoca
  `chain.filter(...)`), y con el caso en que ambos están presentes → el header tiene prioridad.

### Implementation for User Story 1

- [ ] T022 [US1] Implementar `AuthenticationWebFilter` (`implements WebFilter, Ordered`) en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticationWebFilter.java`
  — excluye `OPTIONS` y las rutas exentas (T020); extrae el token del header `Authorization` o, solo
  para `GET /notifications:subscribe`, del parámetro `access_token` si falta el header (T021); invoca
  `TokenValidationPort.validate(...)`; en error, clasifica el `RejectionReason`, registra con
  `AuthenticationLogFormatter` y responde `401`; en éxito, coloca el `AuthenticatedPrincipal` en un
  atributo del `ServerWebExchange` y continúa la cadena (todavía sin verificar rol — eso es US3).
  Orden posterior a `CorsWebFilter` (depende de T010, T011, T012, T015).
- [ ] T023 [US1] Registrar `AuthenticationWebFilter` como `@Bean` en `SecurityConfig`
  (`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/SecurityConfig.java`,
  T017).
- [ ] T024 [US1] Confirmar cobertura ≥80 %/≥70 % (Principio IV) de los archivos nuevos de esta
  historia con `./mvnw -B -ntp -pl infrastructure -am test -Dtest=AuthenticationWebFilterTest -Dsurefire.failIfNoSpecifiedTests=false`
  y revisión del reporte JaCoCo.

**Checkpoint**: el fail-closed funciona de forma aislada y verificable; los controladores existentes
todavía leen `X-Tenant-Id` (se reemplaza en US2) — una solicitud con token válido pero sin ese header
puede fallar con `400` en el controlador durante esta fase intermedia, lo cual es esperado y se
resuelve en US2, no en producción (todas las historias se entregan en la misma implementación antes
de dar la HU por terminada).

---

## Phase 4: User Story 2 - Resolver tenant y rol desde el token, no desde un header arbitrario (Priority: P1)

**Goal**: los cinco controladores REST existentes dejan de leer `@RequestHeader("X-Tenant-Id")` y
usan el `tenantId`/`role` ya resueltos por el filtro; el header deja de tener cualquier efecto.

**Independent Test**: enviar la misma solicitud dos veces con el mismo token válido (`tenantId=A`) y
distintos valores de `X-Tenant-Id` (incluida su ausencia); el resultado observable (bajo qué tenant
queda la notificación, o qué notificación se puede consultar) es idéntico en ambos casos y corresponde
siempre a `A`.

### Tests for User Story 2 ⚠️

- [ ] T025 [P] [US2] Unit test `AuthenticatedPrincipalArgumentResolverTest` en
  `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticatedPrincipalArgumentResolverTest.java`
  — `supportsParameter` reconoce un parámetro de tipo `AuthenticatedPrincipal`; `resolveArgument`
  devuelve el valor puesto por el filtro en el atributo del exchange. Escribir primero.
- [ ] T026 [US2] E2E `AuthenticationInterinaE2ETest` (primera tanda de escenarios,
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers Mongo/RabbitMQ + `WebTestClient`)
  en
  `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/e2e/AuthenticationInterinaE2ETest.java`
  — `POST /notifications` con token `tenantId=A` y header `X-Tenant-Id=B` (o sin header) registra la
  notificación bajo `A`; `GET /notifications/{id}` con el token de `A` la encuentra; un token de
  `tenantId=B` no la encuentra (reutiliza el aislamiento multi-tenant ya existente). Usar
  `LocalJwtTokenIssuer` para generar los tokens, nunca JSON armado a mano. Escribir primero, confirmar
  que falla (los controladores todavía leen el header).

### Implementation for User Story 2

- [ ] T027 [US2] Crear `AuthenticatedPrincipalArgumentResolver implements HandlerMethodArgumentResolver`
  en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/AuthenticatedPrincipalArgumentResolver.java`
  — resuelve por tipo (`AuthenticatedPrincipal`) leyendo el atributo que deja `AuthenticationWebFilter`
  en el `ServerWebExchange` (depende de T006, T022).
- [ ] T028 [US2] Crear `WebFluxConfig implements WebFluxConfigurer` en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/WebFluxConfig.java` que
  registra `AuthenticatedPrincipalArgumentResolver` vía `configureArgumentResolvers` (depende de T027).
- [ ] T029 [P] [US2] Modificar `NotificationController`
  (`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationController.java`)
  — reemplazar cada `@RequestHeader("X-Tenant-Id") final String tenantId` por
  `final AuthenticatedPrincipal principal`, usando `principal.tenantId()` donde antes se envolvía
  `TenantId.of(tenantId)`.
- [ ] T030 [P] [US2] Modificar `NotificationBatchController`
  (`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationBatchController.java`)
  — mismo reemplazo.
- [ ] T031 [P] [US2] Modificar `AttachmentUploadController`
  (`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/AttachmentUploadController.java`)
  — mismo reemplazo en sus tres métodos.
- [ ] T032 [P] [US2] Modificar `ChannelCatalogController`
  (`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/ChannelCatalogController.java`)
  — reemplazar el parámetro opcional `X-Tenant-Id` y el método privado `requireTenant` (ya innecesario,
  el filtro garantiza la presencia) por `final AuthenticatedPrincipal principal` en ambos métodos.
- [ ] T033 [P] [US2] Modificar `NotificationLiveUpdatesController`
  (`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationLiveUpdatesController.java`)
  — mismo reemplazo; el log de debug usa `principal.tenantId().value()` en vez de `tenantId`.
- [ ] T034 [US2] Confirmar cobertura ≥80 %/≥70 % de los archivos nuevos/modificados de esta historia.

**Checkpoint**: el tenant y el rol resueltos por el token gobiernan toda la API pública; el header
`X-Tenant-Id` ya no tiene ningún efecto observable en ningún controlador.

---

## Phase 5: User Story 3 - Impedir operaciones fuera del rol del llamador (Priority: P2)

**Goal**: una solicitud con un token válido pero con rol insuficiente para la operación recibe `403`
en los endpoints donde el mapeo de roles ya aplica (hoy: `GET /notifications` exige `OPERADOR` o
superior; el resto de operaciones expuestas alcanza con `CLIENTE`).

**Independent Test**: generar dos tokens válidos para el mismo tenant con roles distintos (`CLIENTE` y
`OPERADOR`) y comparar la respuesta a `GET /notifications`: `CLIENTE` recibe `403`, `OPERADOR` recibe
`200`.

### Tests for User Story 3 ⚠️

- [ ] T035 [P] [US3] Unit test `RouteAuthorizationPolicyTest` en
  `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/RouteAuthorizationPolicyTest.java`
  — `GET /notifications` exige `OPERADOR`; `POST /notifications`, `POST /notifications:sendBatch`,
  `GET /notifications/{id}`, el ciclo de `/attachment-uploads`, `GET /notifications:subscribe`,
  `GET /channels` y `GET /providers` exigen como mínimo `CLIENTE`; una ruta no reconocida no debe
  autorizar por omisión (falla cerrada también aquí). Escribir primero.
- [ ] T036 [P] [US3] Ampliar `AuthenticationWebFilterTest` con los casos de rol insuficiente (token
  `CLIENTE` contra `GET /notifications` → `403` + log `INSUFFICIENT_ROLE`/`tenantId` conocido) y de
  rol suficiente (`OPERADOR` y `ADMINISTRADOR` → `chain.filter(...)` invocado). Escribir primero.
- [ ] T037 [US3] Completar `AuthenticationInterinaE2ETest` con los escenarios de `quickstart.md`
  pendientes: rechazo `403` de `GET /notifications` con token `CLIENTE` y éxito con token `OPERADOR`
  (reutilizando el E2E de T026); suscripción SSE con `access_token` en query (`GET
  /notifications:subscribe?access_token=...`) recibiendo la foto inicial; y una aserción explícita de
  que ningún log emitido durante la prueba contiene el valor crudo de ningún token usado (SC-006).
- [ ] T038 [US3] Confirmar en `AuthenticationInterinaE2ETest` que una solicitud sin token a cualquiera
  de los endpoints cubiertos por esta historia responde `401` y que ninguna notificación/adjunto queda
  creado como efecto secundario (control positivo + negativo en la misma suite, no solo "no llega
  nada").

### Implementation for User Story 3

- [ ] T039 [US3] Crear `RouteAuthorizationPolicy` en
  `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/RouteAuthorizationPolicy.java`
  — tabla estática método+path → `Role` mínimo (FR-009/FR-010: `GET /notifications` → `OPERADOR`; el
  resto de rutas ya expuestas → `CLIENTE`); método `minimumRoleFor(HttpMethod method, String path)`
  que no autoriza por omisión ante una ruta no reconocida.
- [ ] T040 [US3] Extender `AuthenticationWebFilter` (T022) para, tras validar el token, consultar
  `RouteAuthorizationPolicy` y, si `principal.role().satisfies(minimo)` es falso, registrar el rechazo
  (`RejectionReason.INSUFFICIENT_ROLE`) y responder `403` en vez de continuar la cadena.
- [ ] T041 [US3] Confirmar cobertura ≥80 %/≥70 % de los archivos nuevos/modificados de esta historia.

**Checkpoint**: las tres historias de usuario funcionan juntas — fail-closed, identidad desde el
token, autorización por rol — sobre la API pública completa.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: cierre de la historia, verificación completa de calidad.

- [ ] T042 Ejecutar `./mvnw -B -ntp spotless:apply` sobre todos los archivos `.java` nuevos/modificados
  de esta historia (normaliza formato y CRLF).
- [ ] T043 Ejecutar manualmente los pasos de `specs/015-autenticacion-interina/quickstart.md` contra
  el servicio levantado localmente (`docker compose up -d mongodb rabbitmq`,
  `./mvnw -pl infrastructure spring-boot:run`) y confirmar que cada resultado esperado se cumple.
- [ ] T044 Confirmar que `HexagonalArchitectureTest`/`ModularityTests` siguen en verde con el árbol de
  archivos final de la historia.
- [ ] T045 Ejecutar `./mvnw -B -ntp clean verify` completo (con
  `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` si Testcontainers lo requiere en este entorno) y confirmar
  cobertura ≥80 % líneas/≥70 % ramas y Spotless/SpotBugs/FindSecBugs en verde antes de dar la historia
  por terminada.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias — puede empezar de inmediato.
- **Foundational (Phase 2)**: depende de Setup — bloquea las tres historias.
- **US1 (Phase 3)**: depende de Foundational. No depende de US2/US3.
- **US2 (Phase 4)**: depende de Foundational y de que `AuthenticationWebFilter` exista (T022, de US1)
  para poder leer el atributo que deja en el exchange — no es independiente de US1 en la práctica,
  aunque sus pruebas son independientes de las de US1.
- **US3 (Phase 5)**: depende de Foundational y extiende el mismo `AuthenticationWebFilter` de US1
  (T022/T040); sus pruebas son independientes de las de US2, pero comparten el mismo archivo de
  implementación.
- **Polish (Phase 6)**: depende de que las tres historias estén completas.

### Dentro de cada historia

- Pruebas antes que implementación (TDD explícito, ver Principio IV y el hábito del repositorio).
- `spotless:apply` tras crear o editar cada `.java` nuevo (hábito del repositorio, no una tarea única
  al final salvo la pasada de cierre T042).

### Parallel Opportunities

- T005, T006, T007 (tipos de `core`) en paralelo.
- T008, T009 (pruebas de `core`) en paralelo entre sí y con T011/T012 (tipos de infraestructura para
  el log).
- T019, T020, T021 (casos de `AuthenticationWebFilterTest` en US1) pueden escribirse en paralelo antes
  de T022, aunque conviene consolidarlos en el mismo archivo de prueba.
- T029–T033 (los cinco controladores de US2) en paralelo — archivos distintos, mismo cambio mecánico.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Completar Phase 1 (Setup) y Phase 2 (Foundational).
2. Completar Phase 3 (US1): fail-closed verificable de forma aislada.
3. Detenerse y validar: `AuthenticationWebFilterTest` en verde cubre todos los rechazos y las rutas
   exentas.

### Incremental Delivery

1. Setup + Foundational → base lista.
2. US1 → fail-closed aislado y probado.
3. US2 → tenant/rol resueltos del token en los cinco controladores; header sin efecto.
4. US3 → autorización por rol; historia completa.
5. Polish → `verify` completo en verde, historia lista para revisión.

---

## Notes

- [P] = archivos distintos, sin dependencias pendientes entre sí.
- Commitear por fase o grupo coherente (Setup, Foundational, cada historia, Polish) — commits de una
  sola línea, sin razonamiento en el mensaje (Principio V).
- Confirmar que cada prueba nueva falla por el motivo correcto antes de implementar.
- No se crea ningún endpoint de emisión de tokens (ver `spec.md` → Assumptions).
