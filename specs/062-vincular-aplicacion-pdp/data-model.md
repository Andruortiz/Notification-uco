# Data Model: HU2-146

## ApplicationBinding (colección `application_bindings`)

| Campo | Tipo | Regla |
|---|---|---|
| id | string | generado |
| tenantId | string | de la sesión; nunca del cliente |
| applicationId | string | `id` del PDP, no vacío, hasta 100 caracteres |
| applicationName | string | nombre del PDP al vincular |
| createdBy | string | `subject` de la sesión |
| createdAt | instante | UTC |

Índice único `(tenantId, applicationId)` (FR-004). Sin estados: existe o no existe.

## ApplicationBindingAuditEntry (colección `application_binding_audit`)

| Campo | Tipo | Regla |
|---|---|---|
| id | string | generado |
| tenantId, applicationId | string | copia al momento del cambio |
| action | `VINCULADA` \| `DESVINCULADA` | |
| actor | string | `subject` de la sesión |
| occurredAt | instante | UTC |
| correlationId | string | de `CorrelationContext` |

Solo inserciones; índice `(tenantId, occurredAt)` para la lectura. Nunca guarda la cookie.
