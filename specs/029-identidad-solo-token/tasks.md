# Tasks: Identidad solo desde el token y retiro del modo local (HU2-113)

Spec: [spec.md](spec.md) | Plan: [plan.md](plan.md). Todas las rutas son relativas a la raíz del repositorio.

## Fase 1: Preparación

- [ ] T001 `grep` de `Jwts.builder`, `Keys.hmac`, `LocalJwt`, `AuthJwt`, `AUTH_JWT` y `hs256` sobre `infrastructure/src/test` y `docs`: listar clases que arman tokens fuera de `TestTokens`; resultado fija el alcance de T012-T014.

## Fase 2: Código principal (bloquea las pruebas)

- [ ] T002 `core`: `AuthenticatedPrincipal` gana `clientId` anulable, conservando el constructor de tres argumentos.
- [ ] T003 `utils`: `ErrorCode.AUTHENTICATION_CLAIMS_MISSING(2004)`.
- [ ] T004 `AuthPlatformProperties`: `clientClaim` (default `azp`, `AUTH_PLATFORM_CLIENT_CLAIM`).
- [ ] T005 `InvalidTokenException.Reason.INVALID_ISSUER_OR_AUDIENCE`; `JwtFailures` mapea `InvalidClaimException` a esa razón.
- [ ] T006 `PlatformJwtTokenValidationAdapter`: sin `@ConditionalOnProperty`, mensaje de falta de clave que nombra `AUTH_PLATFORM_PUBLIC_KEY`, lectura opcional de la claim de cliente.
- [ ] T007 `AuthenticationWebFilter` y `RejectionReason`: `MISSING_CLAIMS`, `UNKNOWN_ROLE` y `MISSING_EXPIRATION` responden `AUTHENTICATION_CLAIMS_MISSING` (401, "token is missing required claims"); `INVALID_ISSUER_OR_AUDIENCE` responde `AUTHENTICATION_REQUIRED`; el log lleva el código resuelto (FR-012).
- [ ] T008 Retiro del modo local: borrar `LocalJwtTokenIssuer`, `LocalJwtTokenValidationAdapter`, `AuthJwtProperties`, `AuthJwtSecretGuard`; simplificar `SecurityConfig`; limpiar `application.yml` y `application-local.yml`.

## Fase 3: Migración de pruebas

- [ ] T009 `TestTokens`: par RSA 2048 por JVM, firma RS256, `PUBLIC_KEY` en Base64.
- [ ] T010 Inicializador de contexto de prueba + `META-INF/spring.factories` que inyecta clave pública y nombres de claim salvo `test.auth.no-default-key=true` (convención documentada en su Javadoc de clase y en `plan.md`).
- [ ] T011 Migrar las 5 pruebas de controlador y 3 de filtro al validador de plataforma con `AuthPlatformProperties` de prueba; ajustar `AuthenticationSecurityE2ETest`, `PublicRoutesPepE2ETest` y `PlatformJwtTokenValidationAdapterTest`.
- [ ] T012 Borrar `AuthJwtPropertiesTest`, `NoPublishedSecretTest`, `LocalJwtTokenValidationAdapterTest`; reducir `AuthModeSelectionTest` (arranca con clave, falla sin ella, con `test.auth.no-default-key=true`).
- [ ] T013 Pruebas unitarias del validador: sujeto, tenant, rol ausentes; rol desconocido; cliente presente/ausente/no textual; claims personalizadas; emisor/audiencia incorrectos dan `AUTHENTICATION_REQUIRED`.
- [ ] T014 Prueba del filtro: mapeo de razones a códigos, cuerpo sin valores del token, log con el mismo código.
- [ ] T015 `LegacySharedSecretTokenRejectedTest` y `NoPrivateKeyInRepositoryTest`.

## Fase 4: E2E

- [ ] T016 E2E `IdentityFromTokenE2ETest` (Testcontainers, `WebTestClient`): dos tenants con `X-Tenant-Id` cruzado; 401 `NTF-2004` sin efectos en Mongo; token HS256 antiguo da 401.

## Fase 5: Flujo de desarrollo y documentación

- [ ] T017 `scripts/dev-token.ps1` (PowerShell 7): genera `.dev-keys/`, imprime `AUTH_PLATFORM_PUBLIC_KEY` y emite RS256.
- [ ] T018 `.gitignore` (añadir `.dev-keys/`, retirar comentario obsoleto), `.env.example`, `README.md`, `docs/`, `F05-autenticacion-y-panel-en-vivo.drawio`, `.github/workflows/ci-cd.yml` (solo si hace falta), `quickstart.md`.

## Fase 6: Cierre

- [ ] T019 `spotless:apply`, `spotless:check compile`, pruebas dirigidas y de arquitectura; `grep` de SC-003.
- [ ] T020 `./mvnw -B -ntp verify` en segundo plano, una sola vez, por quien integra el nivel (cobertura >=80 % / >=70 %).
