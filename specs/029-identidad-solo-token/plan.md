# Implementation Plan: Identidad solo desde el token y retiro del modo local (HU2-113)

**Branch**: `feature/HU2-113-identidad-solo-token` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobación

**Versión del plan**: 1

## Summary

Se deja un único validador de Bearer (`PlatformJwtTokenValidationAdapter`), se añade la claim de cliente configurable y
opcional, las claims faltantes responden con un código propio y se retira todo el modo local. Las pruebas pasan a firmar
con un par RSA generado en memoria y el desarrollo manual usa un script que genera claves y tokens.

## Technical Context

Java 25, Spring Boot 4.1, WebFlux, jjwt (ya presente). Sin dependencias nuevas. `pom.xml` no se toca.

## Constitution Check (2.0.1)

- I Hexagonal: `AuthenticatedPrincipal` (core) gana un campo opcional sin framework; el resto vive en `infrastructure`. Cumple.
- II Contrato primero: no hay rutas nuevas; el código de error nuevo exige cambio de contrato (ver lista). Se coordina con el
  agente de Contrato antes de implementar; esta historia no edita `api-notificaciones.yaml`.
- III Sin comentarios en código nuevo. IV E2E explícito por historia (tarea en `tasks.md`) y `verify` al cierre.
- V Commits de una línea. VII Sin atajos: nombres de claim pendientes de Seguridad quedan como excepción con dueño y fecha
  (research.md, Q1).
- Consumidores RabbitMQ: no aplica.

## Decisiones

1. **Un solo validador.** Se elimina `@ConditionalOnProperty(notification.auth.mode)` del adaptador de plataforma; es el
   único `TokenValidationPort`. Falta de clave pública = fallo de arranque (ya lo hace `parsePublicKey`; se ajusta el mensaje
   para nombrar `AUTH_PLATFORM_PUBLIC_KEY`).
2. **Claim de cliente.** `AuthPlatformProperties.clientClaim` (default `azp`, `AUTH_PLATFORM_CLIENT_CLAIM`). El principal
   gana `clientId` anulable con constructor de tres argumentos conservado: hay 15 usos de `new AuthenticatedPrincipal(` y
   la afirmación de no tocarlos solo vale si ese constructor se conserva. Solo se lee si es texto; no se exige.
3. **Código de error.** Nuevo `ErrorCode.AUTHENTICATION_CLAIMS_MISSING(2004)` para `MISSING_CLAIMS`, `UNKNOWN_ROLE` y
   `MISSING_EXPIRATION`. `AuthenticationWebFilter` mapea esas razones al código nuevo (401, mensaje
   "token is missing required claims"); el resto sigue en `AUTHENTICATION_REQUIRED`.
   - `JwtFailures` hoy mapea `InvalidClaimException` (emisor o audiencia incorrectos) a `MISSING_CLAIMS`. Se crea la razón
     `INVALID_ISSUER_OR_AUDIENCE` en `InvalidTokenException.Reason` y se mapea a `AUTHENTICATION_REQUIRED`; prueba en
     `PlatformJwtTokenValidationAdapterTest` (emisor y audiencia incorrectos o ausentes) y en el filtro.
   - `AuthenticationWebFilter.logRejection` fija `AUTHENTICATION_REQUIRED`; recibe el código resuelto para que el log
     coincida con la respuesta. Prueba que captura el log del rechazo por claims faltantes.
   - Roles: se conservan los tres del código (ADMINISTRADOR, OPERADOR, CLIENTE) hasta B2.
4. **Retiro del modo local.** Se borran `LocalJwtTokenIssuer`, `LocalJwtTokenValidationAdapter`, `AuthJwtProperties`,
   `AuthJwtSecretGuard`, el bean condicional de `SecurityConfig`, las claves `notification.auth.mode` y
   `notification.auth.jwt.*` de `application.yml`, el secreto de `application-local.yml`, y las variables de `.env.example` y
   README. También: el comentario de `.gitignore` (línea 39) sobre `AUTH_JWT_HS256_SECRET`, las menciones en `docs/` y en
   `docs/diagramas/secuencias/F05-autenticacion-y-panel-en-vivo.drawio`, y `.github/workflows/ci-cd.yml`.
   - **Cambio de despliegue**: `AUTH_PLATFORM_PUBLIC_KEY` pasa a ser obligatoria siempre (decisión del usuario), incluso con
     solo cookie de sesión (spec 025). Los despliegues y el manual de despliegue deben proveerla. `ci-cd.yml` hoy solo ejecuta
     `java -version` e `id -u` sobre la imagen y no arranca la app, así que no cambia; si un job llega a arrancar la imagen,
     necesitará una clave pública de prueba generada en el propio job, sin versionar la privada. Las pruebas usan el par RSA
     efímero. B1 se implementa antes que las historias que dependen de la identidad de CI.
5. **Migración de pruebas.**
   - `TestTokens` genera un `KeyPair` RSA 2048 por JVM y firma RS256; expone `PUBLIC_KEY` en Base64.
   - Un `ApplicationContextInitializer` de prueba, registrado en `src/test/resources/META-INF/spring.factories`, inyecta
     `notification.auth.platform.public-key` y los nombres de claim en todo contexto de prueba, así las ~40 clases no cambian.
     El inicializador NO inyecta nada cuando el contexto declara `test.auth.no-default-key=true`; `AuthModeSelectionTest`
     (`ApplicationContextRunner`, no pasa por `spring.factories`) y la prueba de «arranque sin clave» usan esa propiedad.
   - Las 5 pruebas de controlador y 3 de filtro que importan `LocalJwtTokenValidationAdapter` pasan a importar el de
     plataforma con `AuthPlatformProperties` de prueba.
   - Se borran `AuthJwtPropertiesTest`, `NoPublishedSecretTest` y `LocalJwtTokenValidationAdapterTest`; `AuthModeSelectionTest`
     se reduce a: arranca con clave, falla sin ella.
   - Se añade `LegacySharedSecretTokenRejectedTest`: un token firmado con secreto compartido es rechazado por el validador
     de plataforma (nombre sin `hs256`, para respetar el grep de SC-003).
   - Convención de pruebas `test.auth.no-default-key=true`: la define y documenta la tarea que crea el inicializador
     (su Javadoc de clase y `plan.md` la nombran); las tareas de `AuthModeSelectionTest` y de arranque sin clave la citan.
6. **Flujo de desarrollo.** `scripts/dev-token.ps1` (PowerShell 7, `RSA.ExportSubjectPublicKeyInfo`) crea
   `.dev-keys/` (ignorado en git), imprime el valor de `AUTH_PLATFORM_PUBLIC_KEY` y emite un RS256 con tenant, sujeto, rol y
   vigencia por parámetro. El perfil `local` no trae clave: se documenta exportarla. Alternativa documentada: sesión real del PDP.
7. **Regresión de `X-Tenant-Id` (FR-001).** `IdentityFromTokenE2ETest`: dos tenants y el encabezado enviado.
8. **Sin claves privadas versionadas (FR-006).** `NoPrivateKeyInRepositoryTest` busca `BEGIN PRIVATE KEY` en fuentes, recursos
   y scripts versionados.

## Cambios de contrato requeridos (para el agente de Contrato)

- Enumeración de códigos de error: añadir `AUTHENTICATION_CLAIMS_MISSING` (2004).
- Respuestas `401` de todas las operaciones protegidas: documentar los dos códigos posibles
  (`AUTHENTICATION_REQUIRED`, `AUTHENTICATION_CLAIMS_MISSING`).
- Descripción del esquema de seguridad Bearer: indicar claims esperadas y que no se acepta `X-Tenant-Id`; si el contrato aún
  declara `X-Tenant-Id` en alguna operación, retirarlo.

## Estructura (archivos tocados)

- `core`: `AuthenticatedPrincipal`.
- `utils`: `ErrorCode`.
- `infrastructure/main`: `AuthPlatformProperties`, `PlatformJwtTokenValidationAdapter`, `AuthenticationWebFilter`,
  `JwtFailures` (si mapea razones), `SecurityConfig`, `application*.yml`; borrados listados en la decisión 4.
- `infrastructure/test`: `TestTokens`, inicializador y `spring.factories`, pruebas de la decisión 5, E2E nuevas.
- Fuera de código: `scripts/dev-token.ps1`, `.env.example`, `README.md`, `.gitignore` (añadir `.dev-keys/`, retirar el
  comentario obsoleto), `docs/` y el `.drawio` F05, `.github/workflows/ci-cd.yml`.

## Pruebas

- Unitarias del validador: sujeto, tenant, rol ausentes; rol desconocido; cliente opcional presente/ausente/no textual;
  nombres de claim personalizados (`tenant`); HS256 rechazado.
- Filtro: mapeo de razones a códigos; cuerpo sin valores del token.
- E2E `IdentityFromTokenE2ETest` (Testcontainers, `WebTestClient`): dos tenants con `X-Tenant-Id` cruzado; 401 con el código
  nuevo sin efectos en Mongo; arranque sin clave falla (contexto).
- Arquitectura: `HexagonalArchitectureTest` y `ModularityTests` siguen en verde.

## Riesgos

- Migración amplia: cualquier clase que arme tokens a mano fuera de `TestTokens` falla; se detecta con `grep` en la
  primera tarea.
- Cambiar el código en el 401 puede romper clientes que solo conocen `AUTHENTICATION_REQUIRED`; se mitiga con el cambio de
  contrato coordinado.
- Sin Seguridad los nombres reales de claims son supuestos (research.md, Q1; excepción con dueño Seguridad, fecha 2026-12-15).
- Clave obligatoria siempre: cualquier despliegue sin ella deja de arrancar; hay que avisar a quien opera imagen y manual.
