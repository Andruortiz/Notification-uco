# Feature Specification: Rutas públicas del PEP para el contrato y Swagger (HU2-103)

**Feature Branch**: `feature/HU2-103-rutas-publicas-pep`

**Created**: 2026-10-19

**Status**: Draft

## Contexto

El starter del PEP (`security-pep-integration-spring-boot-starter` 0.1.3) responde 401 `TOKEN_INVALID` a toda petición sin
Bearer ni cookie salvo las que casan con `security.pep.enforcement.public-paths`. Hoy el valor por defecto es solo
`/actuator/health`. El `AuthenticationWebFilter` del servicio (HIGHEST_PRECEDENCE+10) ya exime `/v3/api-docs`,
`/swagger-ui`, `/swagger-ui.html` y `/openapi`, pero con el PEP activo el filtro del starter (HIGHEST_PRECEDENCE+100) corre
a continuación y responde 401 a esas rutas, de modo que el contrato OpenAPI y Swagger quedan inaccesibles sin sesión. Además
las dos listas son independientes y pueden divergir.

## User Scenarios & Testing

### User Story 1 - El contrato OpenAPI se descarga sin credencial (Priority: P1)

Un integrador, un gateway o el cargador del PDP descargan el contrato sin sesión.

**Acceptance Scenarios**:

1. **Given** el PEP activo, **When** `GET /openapi/api-notificaciones.yaml` sin credencial, **Then** 200 con el contrato.
2. **Given** el PEP activo, **When** `GET /v3/api-docs` sin credencial, **Then** 200 (`/v3/api-docs/swagger-config` es de la
   interfaz y solo es pública fuera de producción).
3. **Given** el PEP activo, **When** se invoca sin credencial cualquier operación de negocio del contrato, **Then** 401.
4. **Given** el PEP activo, **When** se pide sin credencial una ruta que solo comparte prefijo con una pública
   (`/openapi/otro.yaml`, `/v3/api-docs-x`), **Then** 401.

### User Story 2 - Swagger UI público solo fuera de producción (Priority: P2)

**Acceptance Scenarios**:

1. **Given** la configuración por defecto (producción), **When** `GET /swagger-ui.html` sin credencial, **Then** 401 y la UI
   no se sirve.
2. **Given** el perfil `local`, **When** `GET /swagger-ui.html` sin credencial, **Then** la UI responde.
3. **Given** `SWAGGER_UI_ENABLED` y `SECURITY_PEP_PUBLIC_PATHS`, **When** se definen, **Then** cambian la exposición sin tocar
   código.

### User Story 3 - Una sola fuente de verdad (Priority: P1)

**Acceptance Scenarios**:

1. **Given** el contrato (`x-public-routes`), `application.yml`, `application-local.yml` y la lista del filtro del servicio,
   **When** corre la prueba de consistencia, **Then** coinciden; si divergen, la prueba falla.
2. **Given** el catálogo de recursos protegidos del build, **When** se genera, **Then** no incluye las rutas públicas
   (16 operaciones).

## Requirements

- **FR-001**: El contrato MUST declarar las rutas públicas en `x-public-routes` (método, ruta, exposición `always` o
  `non-production`), sin añadirlas a `paths`.
- **FR-002**: `security.pep.enforcement.public-paths` MUST tener por defecto solo lectura de documentación: contrato y
  `/v3/api-docs`, más las sondas de gestión que el estudio determine.
- **FR-003**: El `AuthenticationWebFilter` MUST derivar sus exenciones de documentación de esa misma propiedad.
- **FR-004**: Swagger UI MUST estar deshabilitado y fuera de las rutas públicas por defecto, y habilitado en el perfil `local`.
- **FR-005**: README y guías MUST documentar rutas públicas y variables.

## Success Criteria

- **SC-001**: E2E con PEP activo: 200 en contrato y api-docs sin credencial y 401 en toda operación de negocio sin credencial.
- **SC-002**: La prueba de consistencia compara contrato, ambos YAML y el filtro.
