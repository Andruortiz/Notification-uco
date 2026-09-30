# Data Model: Autenticación interina del servicio

**Feature**: 015-autenticacion-interina | **Date**: 2026-09-30

No hay persistencia nueva (ninguna colección Mongo, ningún estado en RabbitMQ). Esta historia agrega
tipos de dominio en `core` y estructuras de configuración en `infrastructure`; el "modelo de datos" es
la forma del token y de la identidad resuelta.

## `Role` (nuevo, `core/domain/valueobject/Role.java`)

Enum con tres valores, en orden jerárquico descendente: `ADMINISTRADOR`, `OPERADOR`, `CLIENTE`.

- **Invariante**: `ADMINISTRADOR ⊇ OPERADOR ⊇ CLIENTE` — un rol superior satisface cualquier
  requisito de un rol inferior.
- **Comportamiento**: `satisfies(Role required)` — verdadero si `this` es igual o jerárquicamente
  superior a `required` (comparación por posición declarada en el enum, `ADMINISTRADOR` primero).
- **Parsing**: `Role.of(String)` — lanza una excepción de dominio (no `IllegalArgumentException`
  genérica de `Enum.valueOf`) si el valor no coincide exactamente con uno de los tres nombres; el
  filtro trata esa excepción igual que cualquier otra claim inválida → `401` (FR-005), nunca `403`.

## `AuthenticatedPrincipal` (nuevo, `core/domain/valueobject/AuthenticatedPrincipal.java`)

Record inmutable, resultado de validar un token. Campos:

| Campo | Tipo | Notas |
|---|---|---|
| `subject` | `String` | Claim `sub` — identificador del sistema cliente emisor. No vacío. |
| `tenantId` | `TenantId` | Claim `tenantId`, ya envuelta en el value object existente. |
| `role` | `Role` | Claim `role`, ya validada contra el conjunto permitido. |

No tiene comportamiento propio más allá de las validaciones de sus componentes (reutiliza
`Preconditions.requireNonBlank` como el resto de value objects del proyecto).

## `TokenValidationPort` (nuevo, `core/port/out/TokenValidationPort.java`)

```java
public interface TokenValidationPort {
  Mono<AuthenticatedPrincipal> validate(String rawToken);
}
```

- `rawToken`: el valor crudo del header `Authorization` sin el prefijo `Bearer ` (el filtro lo extrae
  antes de invocar el puerto).
- Contrato de error: el `Mono` termina en error con `InvalidTokenException` (nuevo,
  `core/exception/InvalidTokenException.java`, `RuntimeException` sin más jerarquía) ante cualquier
  motivo de rechazo — firma inválida, token expirado, claims faltantes o rol desconocido. El puerto no
  distingue el motivo exacto en su tipo de retorno porque el filtro siempre responde `401` igual sin
  importar la causa (FR-002 a FR-005 y FR-013): no hay necesidad de granularidad adicional y evita
  filtrar detalles de la causa criptográfica del rechazo en la respuesta HTTP.

## Adaptador `LocalJwtTokenValidationAdapter` (nuevo, `infrastructure/adapter/out/security/local/`)

Implementa `TokenValidationPort` con JJWT (Decisión 1 de `research.md`). Configuración vía
`@ConfigurationProperties` o `@Value`:

| Propiedad | Origen | Notas |
|---|---|---|
| `AUTH_JWT_HS256_SECRET` | variable de entorno | Nunca versionado; `.env.example` documenta solo un valor de ejemplo marcado como no-productivo. |
| `AUTH_JWT_TTL_MINUTES` | variable de entorno, opcional | Solo lo usa `LocalJwtTokenIssuer` al acuñar; el adaptador de validación no lo necesita, valida `exp` tal como venga en el token. Por defecto 720. |

## `LocalJwtTokenIssuer` (nuevo, `infrastructure/adapter/out/security/local/`)

Clase Java plana (sin anotaciones de Spring), reutilizada directamente desde pruebas (Decisión 2 de
`research.md`). Método principal:

```java
String issue(TenantId tenantId, Role role, String subject, Duration ttl)
```

Produce un JWT firmado con el mismo secreto HS256 configurado, con claims `sub`, `tenantId`, `role`,
`iat`, `exp`.

## `AuthenticationWebFilter` (nuevo, `infrastructure/adapter/in/web/`)

No es una entidad de datos, pero define el flujo de resolución:

1. Excluir `OPTIONS` y las rutas exentas (Decisión 4 de `research.md`).
2. Extraer el header `Authorization`; si falta o no tiene el prefijo `Bearer `, responder `401`.
   Excepción: en `GET /notifications:subscribe`, si falta el header, intentar el parámetro de consulta
   `access_token` (Decisión 5 de `research.md`).
3. Invocar `TokenValidationPort.validate(rawToken)`.
4. Si el `Mono` termina en error (`InvalidTokenException` o cualquier otro), responder `401`.
5. Si el `Mono` completa, verificar el rol contra la tabla de enrutamiento
   (`RouteAuthorizationPolicy`, mapa estático método+path → `Role` mínimo, FR-009/FR-010). Si el rol no
   alcanza, responder `403`.
6. Si el rol alcanza, colocar el `AuthenticatedPrincipal` en un atributo del `ServerWebExchange` y
   continuar la cadena (`chain.filter(exchange)`).

## Controladores existentes — cambio de firma (no de contrato de negocio)

Cada controlador listado reemplaza `@RequestHeader("X-Tenant-Id") final String tenantId` por
`final AuthenticatedPrincipal principal`, resuelto por `AuthenticatedPrincipalArgumentResolver`
(Decisión 3 de `research.md`). El resto de cada método no cambia — donde antes se envolvía
`TenantId.of(tenantId)`, ahora se usa directamente `principal.tenantId()`.

Afectados: `NotificationController`, `NotificationBatchController`, `AttachmentUploadController`,
`ChannelCatalogController`, `NotificationLiveUpdatesController`.

## Sin cambios

- Ningún caso de uso de `core/usecase` cambia de firma ni de comportamiento.
- Ningún documento Mongo, ninguna colección nueva, ningún mensaje de RabbitMQ.
- El aislamiento multi-tenant de datos ya existente no se toca.
