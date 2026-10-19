# Modelo de datos: HU2-101

Sin persistencia nueva.

| Elemento | Capa | Campos | Reglas |
|---|---|---|---|
| `SessionCredential` | core, value object | `value` | No vacío; `toString` no revela el valor; sin framework |
| `SessionIdentityPort` | core, puerto de salida | `resolve(SessionCredential) -> Mono<AuthenticatedPrincipal>` | Error de sesión inválida o de indisponibilidad, distinguibles |
| `AuthenticationUnavailableException` | core | motivo | Se traduce a 503 `AUTHENTICATION_UNAVAILABLE` |
| `AuthSessionProperties` | infrastructure | `url`, `path` (`/api/v1/session`), `timeout` (3 s) | `url` obligatoria si hay PEP activo |
| `AuthenticatedPrincipal` | core, existente | `subject`, `tenantId`, `role` | Para cookie: `role = CLIENTE`; `tenantId` y `subject` de la sesión |

Transiciones: ninguna. La sesión vive en la plataforma de Seguridad; el servicio no la guarda.

Mapeo de la respuesta de sesión (forma verificada, ver contracts/session-response.md) a principal:

| Campo de sesión | Principal |
|---|---|
| `data.subject` | `subject` (obligatorio) |
| `data.tenantId` | `TenantId` (obligatorio) |
| `data.name`, `data.email` | no se usan; no se registran |
