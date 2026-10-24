# Feature Specification: Identidad solo desde el token y retiro del modo local (HU2-113)

**Feature Branch**: `feature/HU2-113-identidad-solo-token`

**Created**: 2026-10-24

**Status**: Borrador (pendiente de aprobación de spec y plan)

**Input**: "Como SEG quiero que el servicio tome usuario, tenant y rol únicamente de las claims del token de la plataforma
para no depender de encabezados manipulables." Criterios: se elimina `X-Tenant-Id` de las rutas protegidas; el nombre de
cada claim (sujeto, tenant, rol, cliente) es configurable; un token sin las claims requeridas responde 401 con un código de
error claro. Decisión de producto: se retira por completo el modo local (JWT con secreto compartido) y la historia incluye
la migración de pruebas y del flujo de desarrollo.

Trazabilidad: HU2-096 y spec 015 (autenticación interina, quitó `X-Tenant-Id` y creó `AuthPlatformProperties`), spec 025
(identidad por cookie de sesión del PDP), dependencia DEP-01 (nombres de claims con Seguridad). Relacionada con B3a
(autorización por cliente), que no entra aún. Principios: I, II (contrato lo edita otro agente), III, IV, VII.

## Contexto y evidencia

- `X-Tenant-Id` ya no se lee en `infrastructure/src/main` (spec 015, FR-005); falta una prueba de regresión que lo fije.
- `AuthPlatformProperties` ya hace configurables `subjectClaim`, `tenantClaim` y `roleClaim`; falta la claim de cliente.
- Hoy `notification.auth.mode` vale `local` por defecto: el servicio acepta JWT HS256 con `AUTH_JWT_HS256_SECRET`
  (`LocalJwtTokenValidationAdapter`, `AuthJwtProperties`, `AuthJwtSecretGuard`, `LocalJwtTokenIssuer`). Cerca de 40 clases de
  prueba firman tokens con ese emisor a través de `TestTokens`; `application-local.yml` publica un secreto de desarrollo.
- Un token válido al que le faltan claims responde hoy `401 AUTHENTICATION_REQUIRED` con el mensaje genérico
  "missing or invalid bearer token": no distingue una claim ausente de una firma inválida.
- En el laboratorio la claim de tenant se llama `tenant`, no `tenantId` (`AUTH_PLATFORM_TENANT_CLAIM=tenant`); el valor por
  defecto del servicio sigue siendo `tenantId`.
- Con cookie de sesión (spec 025) la identidad sale de `GET /api/v1/session` del PDP; no pasa por claims de un JWT.

## Clarifications

### Session 2026-10-24

- Q: ¿Se conserva algún modo local de desarrollo? A: No. Decisión del producto: el único validador de Bearer es el de la
  plataforma (clave pública). Desarrollo y pruebas firman con un par de claves RSA propio, nunca con un secreto compartido.
- Q: ¿De dónde sale el tenant en producción? A: de la sesión del PDP (cookie) o, con Bearer, de la claim configurada; nunca
  de un encabezado ni del cuerpo.
- Q: ¿Es obligatoria la claim de cliente? A: No. Es configurable y opcional; si el token la trae, queda en el principal.
  Exigirla y usarla para autorizar es de B3a.

Decisiones del usuario (2026-10-24): la clave pública es obligatoria siempre; roles en código = 3 hasta B2; código de error
nuevo aprobado; claim de cliente opcional con campo anulable; script en PowerShell 7. B1 se implementa primero y deja la
identidad de CI (par RSA efímero).

Excepción abierta: nombres definitivos de claims con Seguridad (DEP-01). Dueño: Seguridad. Fecha: 2026-12-15.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Identidad únicamente del token (Priority: P1)

Un integrador envía un Bearer de la plataforma; el servicio toma sujeto, tenant y rol solo de las claims configuradas y
trata cualquier `X-Tenant-Id` como un encabezado desconocido.

**Why this priority**: Es el criterio central de seguridad: sin encabezados manipulables.

**Independent Test**: E2E con dos tenants: token del tenant A con `X-Tenant-Id: B`; la respuesta contiene solo datos de A.

**Acceptance Scenarios**:

1. **Given** un token válido del tenant A y el encabezado `X-Tenant-Id: B`, **When** lista notificaciones, **Then** recibe
   solo datos de A.
2. **Given** un token válido del tenant A, **When** consulta por id una notificación de B, **Then** responde igual que ante
   un id inexistente.
3. **Given** claims renombradas por configuración (`tenant`, `sub`, `roles`), **When** llega un token con esos nombres,
   **Then** se acepta y se extrae la identidad correcta.

### User Story 2 - Token sin claims requeridas responde 401 claro (Priority: P1)

Un token con firma válida al que le falta sujeto, tenant o rol (o con un rol desconocido) se rechaza con un código que
indica claims ausentes, sin filtrar valores ni nombres internos de usuarios.

**Independent Test**: tres tokens firmados, cada uno sin una de las claims; los tres dan 401 con el mismo código nuevo,
y uno sin firma válida da `AUTHENTICATION_REQUIRED`.

**Acceptance Scenarios**:

1. **Given** un token firmado sin la claim de tenant, **When** llama a una ruta protegida, **Then** 401 con código
   `AUTHENTICATION_CLAIMS_MISSING` y no se ejecuta ningún caso de uso.
2. **Given** un token con el rol fuera del catálogo, **Then** el mismo 401.
3. **Given** un token sin la claim de cliente, **Then** se acepta (la claim es opcional).
4. **Given** un token expirado o con firma inválida, **Then** 401 `AUTHENTICATION_REQUIRED` (sin cambio).

### User Story 3 - Sin modo local y con flujo de desarrollo migrado (Priority: P1)

El servicio solo arranca con una clave pública de la plataforma configurada; los desarrolladores y las pruebas obtienen
tokens firmados con un par de claves RSA propio.

**Independent Test**: arranque sin `AUTH_PLATFORM_PUBLIC_KEY` falla con mensaje claro; toda la suite sigue verde sin
`AUTH_JWT_HS256_SECRET`; un token HS256 firmado con el antiguo secreto de desarrollo da 401.

**Acceptance Scenarios**:

1. **Given** el servicio sin clave pública, **When** arranca, **Then** falla con un mensaje que nombra
   `AUTH_PLATFORM_PUBLIC_KEY`.
2. **Given** un token HS256 con el antiguo secreto, **When** llega a una ruta protegida, **Then** 401.
3. **Given** el flujo de desarrollo documentado, **When** un desarrollador genera claves y token con el script, **Then**
   puede llamar al servicio levantado con el perfil `local`.

### Edge Cases

- Claim de rol como lista: se toma el rol de mayor jerarquía mapeable (comportamiento actual, se conserva).
- Claim de cliente presente pero no textual: se ignora y se registra; no invalida el token.
- Con PEP activo y sin claim de rol, el rol es `CLIENTE` (comportamiento actual, se conserva).
- Cookie de sesión: sin cambios; su tenant sale del PDP.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El servicio NO DEBE leer `X-Tenant-Id` ni ningún otro encabezado para derivar tenant, usuario o rol.
- **FR-002**: Sujeto, tenant, rol y cliente salen de claims cuyos nombres se configuran (`subject-claim`, `tenant-claim`,
  `role-claim`, `client-claim`) por propiedad y variable de entorno; el cliente es opcional.
- **FR-003**: Un token con firma válida sin sujeto, tenant o rol resoluble DEBE responder `401` con código
  `AUTHENTICATION_CLAIMS_MISSING`, cuerpo estándar con `correlationId` y sin valores del token.
- **FR-004**: El validador de Bearer es solo el de plataforma (clave pública RSA/EC); se retiran el emisor, el validador, las
  propiedades y la guarda del secreto HS256, la propiedad `notification.auth.mode` y todas las variables `AUTH_JWT_*`.
- **FR-005**: El arranque DEBE fallar si falta `notification.auth.platform.public-key`, nombrando la variable. La clave es
  obligatoria SIEMPRE, también en despliegues que solo usan cookie de sesión (spec 025). Decisión intencional del producto y
  cambio de despliegue: quien arranque la imagen (despliegues y el manual de despliegue) debe proveer
  `AUTH_PLATFORM_PUBLIC_KEY`; `.github/workflows/ci-cd.yml` solo la necesitará si un job arranca la aplicación (hoy solo
  ejecuta `java -version` e `id -u` sobre la imagen).
- **FR-011**: Un token con emisor o audiencia incorrectos (`iss`/`aud`) NO es un caso de claims ausentes: responde
  `401 AUTHENTICATION_REQUIRED` (razón propia `INVALID_ISSUER_OR_AUDIENCE`), no `AUTHENTICATION_CLAIMS_MISSING`.
- **FR-012**: El log del rechazo DEBE llevar el mismo código que la respuesta (`AUTHENTICATION_CLAIMS_MISSING` o
  `AUTHENTICATION_REQUIRED`), no uno fijo.
- **FR-013**: Roles: el código mantiene los tres existentes (ADMINISTRADOR, OPERADOR, CLIENTE) hasta B2.
- **FR-006**: Ningún secreto ni clave privada se versiona; las pruebas generan su par RSA en tiempo de ejecución.
- **FR-007**: Las pruebas automáticas existentes se migran a tokens RS256 sin cambiar su intención ni su cobertura.
- **FR-008**: El flujo de desarrollo local queda documentado y provisto de un script que genera el par de claves y un
  token con tenant, sujeto y rol elegidos.
- **FR-009**: README, `.env.example`, `application*.yml`, `.gitignore` (comentario sobre `AUTH_JWT_HS256_SECRET`), `docs/` y
  el diagrama `F05-autenticacion-y-panel-en-vivo.drawio` dejan de mencionar el modo local y sus variables.
- **FR-010**: Los eventos de rechazo se registran con razón y sin el token; el nombre de la claim ausente puede registrarse.

### Key Entities

- **AuthenticatedPrincipal**: sujeto, tenant, rol y, opcionalmente, identificador de cliente.
- **AuthPlatformProperties**: clave pública, emisor, audiencia, nombres de claim (incluida la de cliente), mapeo de roles,
  tolerancia de reloj.

## Success Criteria *(mandatory)*

- **SC-001**: Una prueba E2E con dos tenants demuestra que `X-Tenant-Id` no altera el resultado (FR-001).
- **SC-002**: Pruebas unitarias del validador cubren cada claim ausente y el rol desconocido con el código nuevo; una E2E
  confirma el 401 y que no hay efectos en base de datos (FR-003).
- **SC-003**: `grep` de `hs256`, `LocalJwt`, `AuthJwt` y `AUTH_JWT` sobre fuentes, pruebas, README, `.env.example`,
  `.gitignore`, `docs/` (incluido `docs/diagramas/secuencias/F05-autenticacion-y-panel-en-vivo.drawio`) y
  `.github/workflows/` no devuelve resultados (FR-004, FR-009). Única excepción permitida: la prueba de rechazo del token
  firmado con el antiguo secreto compartido, que se llama `LegacySharedSecretTokenRejectedTest` y no usa el literal `hs256`
  ni `AUTH_JWT` (arma el token con jjwt y un secreto de prueba local).
- **SC-006**: Pruebas con nombre: `IdentityFromTokenE2ETest` (FR-001, dos tenants y `X-Tenant-Id` cruzado),
  `NoPrivateKeyInRepositoryTest` (FR-006: ningún archivo versionado contiene `BEGIN PRIVATE KEY`), unitarias de
  emisor/audiencia incorrectos que dan `AUTHENTICATION_REQUIRED` (FR-011) y del log de rechazo (FR-012).
- **SC-007**: CI dispone de una identidad utilizable: el par RSA efímero de las pruebas, sin secretos en el workflow
  (otras historias dependen de ello).
- **SC-004**: `./mvnw verify` verde sin secretos compartidos; cobertura >=80 % líneas y >=70 % ramas.
- **SC-005**: Un desarrollador llega a una llamada autenticada en menos de 5 minutos siguiendo `quickstart.md`.

## Assumptions

- La plataforma firma con RSA o EC y publica la clave en formato X.509 (soporte actual del adaptador).
- La autorización fina por ruta sigue en el PEP/PDP; esta historia no cambia roles ni permisos.
- Fuera de alcance: autorización por cliente (B3a), enmascarado de datos personales y restricción del proveedor
  `simulated` a los perfiles local y de pruebas (historias transversales aparte).
- Los cambios de contrato (código de error nuevo) los aplica el agente de Contrato; ver `plan.md`.
