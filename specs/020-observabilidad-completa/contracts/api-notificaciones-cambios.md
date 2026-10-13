# Cambios al contrato de la API (api-notificaciones.yaml)

Se aplican al YAML antes de tocar el handler (Principio II). Confirmado por el usuario el 2026-10-05 (Q5).

## ErrorResponse

Hoy: `message`, `correlationId` (obligatorio). Cambio aditivo:

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `code` | string, enum `NTF-<n>` con los códigos del catálogo | sí | Código estable del error |

- `required`: `correlationId`, `code`.
- Las respuestas 400, 401, 403, 404, 409, 429 y 5xx de todas las operaciones referencian este esquema;
  se revisa que ninguna defina un esquema de error propio.
- Compatibilidad: aditivo; los clientes con esquema cerrado deben tolerar el campo nuevo.

## Sin cambios

Ninguna operación nueva. El endpoint de métricas no pertenece al contrato de la API de negocio:
se documenta en `metricas-y-trazas.md` y se sirve en el puerto de gestión.
