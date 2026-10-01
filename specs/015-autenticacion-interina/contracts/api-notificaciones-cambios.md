# Cambios en el contrato público — `api-notificaciones.yaml`

**Feature**: 015-autenticacion-interina | **Date**: 2026-09-30

Archivo: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`. Se edita antes que
el filtro y los controladores (Principio II). Ningún `operationId`, campo ni tipo existente cambia de
nombre; el cambio es de mecanismo de autenticación, no de forma de los datos de negocio.

| Elemento | Cambio |
|---|---|
| `components.securitySchemes.BearerAuth` (nuevo) | `type: http`, `scheme: bearer`, `bearerFormat: JWT`. Descripción: token interino emitido y firmado por este mismo servicio (HS256); se reemplazará por la identidad resuelta contra la plataforma de Seguridad externa cuando DEP-01 se cierre — la forma del token (claims `sub`, `tenantId`, `role`) es la misma que usará el adaptador definitivo. |
| `security` (raíz del documento) | `- BearerAuth: []` — aplica por defecto a toda operación que no declare lo contrario. |
| `components.parameters.TenantId` | Eliminado de cada operación que lo referenciaba. El parámetro en sí se retira de `components.parameters` (ya no se lee de un header; el tenant sale del token). |
| Cada operación bajo `/notifications`, `/notifications:subscribe`, `/notifications:sendBatch`, `/notifications/{id}`, `/notifications/{id}:retry`, `/attachment-uploads`, `/attachment-uploads/{uploadId}:complete`, `/attachment-uploads/{uploadId}`, `/channels`, `/providers`, `/channels:register`, `/providers:register`, `/recipients/{recipientId}/preferences`, `/recipients/{recipientId}:updatePreferences` | + respuesta `401` con `ErrorResponse`: sin token, token mal formado, firma inválida, expirado, o claims obligatorias (`sub`, `tenantId`, `role`) ausentes o con `role` fuera de `ADMINISTRADOR`/`OPERADOR`/`CLIENTE`. El cuerpo nunca detalla la causa criptográfica exacta del rechazo. |
| Operaciones con rol mínimo `OPERADOR` o superior (hoy solo `GET /notifications`; `POST /notifications/{id}:retry` ya documentada como planificada) | + respuesta `403` con `ErrorResponse`: token válido pero rol insuficiente para la operación. |
| `GET /notifications` — `description` | + nota: requiere rol `OPERADOR` o `ADMINISTRADOR`; `GET /notifications/{id}` (consulta puntual) basta con `CLIENTE`. |
| `GET /notifications:subscribe` — `parameters` | + parámetro de consulta opcional `access_token` (`in: query`, `schema: string`), documentado como la única excepción al mecanismo `Authorization: Bearer`: el `EventSource` nativo del navegador no permite enviar headers custom. Descripción explícita de la implicación de seguridad: un token en la URL puede quedar registrado en logs de acceso de intermediarios; el servicio no registra esta query string en sus propios logs. Si ambos (header y query param) están presentes, el header tiene prioridad. |
| `/channels:register`, `/providers:register` | + nota en `description`: requieren rol `ADMINISTRADOR`; la operación sigue bloqueada/planificada (sin controlador) independientemente del mecanismo de autenticación — el mapeo de rol queda documentado para cuando exista. |
| `/notifications/{id}:retry` | + nota en `description`: requiere rol `OPERADOR` o superior; la operación sigue planificada (sin controlador) — el mapeo de rol queda documentado para cuando exista. |
| `/recipients/{recipientId}/preferences`, `/recipients/{recipientId}:updatePreferences` | + nota en `description`: requieren rol `CLIENTE` o superior; la operación sigue planificada (sin controlador) — el mapeo de rol queda documentado para cuando exista. |
| `info.description` | Nota añadida: la autenticación usa `Authorization: Bearer <JWT>` desde HU2-096; el header `X-Tenant-Id` queda retirado de la API pública. |

## Ejemplo de respuesta `401`

```json
{ "message": "missing or invalid bearer token" }
```

## Ejemplo de respuesta `403`

```json
{ "message": "role CLIENTE does not satisfy the minimum role OPERADOR required for this operation" }
```

## Fuera de alcance de este cambio de contrato

- No se agrega ninguna operación de emisión de tokens (`POST /auth/token` o similar) — ver Decisión 2
  de `research.md` y la sección de Assumptions de `spec.md`.
- Los `operationId`, esquemas de request/response de negocio y códigos `400`/`409`/`413`/`503` ya
  existentes no cambian.
