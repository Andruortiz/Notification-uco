# Contratos: HU2-101

## 1. Respuesta esperada de la sesión del PDP (suposición no verificada)

Fuente: tipos del frontend de referencia de la plataforma de Seguridad; el PDP real no se pudo consultar sin cookie válida
(responde 401). Debe confirmarla el equipo de seguridad antes de cerrar la historia.

`GET {session.url}/api/v1/session` con `Cookie: SECURITY_BASELINE_SESSION=<valor>`:

```json
{
  "code": "OK",
  "message": "...",
  "data": { "subject": "u-123", "name": "Ana", "email": "ana@example.com", "tenantId": "tenant-a" },
  "timestamp": "...",
  "requestId": "...",
  "correlationId": "..."
}
```

Sin cookie válida: 401 `application/problem+json` con `code: UNAUTHORIZED` (verificado en el entorno local).

El adaptador exige `data.subject` y `data.tenantId` no vacíos; cualquier otra forma se trata como sesión inválida.

## 2. Contrato de la API del servicio (cambios en `api-notificaciones.yaml`)

Se edita antes del código (Principio II). No hay rutas nuevas.

- `components.securitySchemes.SessionCookie.description`: el servicio acepta la cookie; precedencia Bearer > cookie; el PEP la
  valida y debe estar activo; el rol efectivo es `CLIENTE`.
- Descripción global de seguridad: retirar la frase "el modo interino solo acepta Bearer".
- Nueva respuesta reutilizable `AuthenticationUnavailable` (503) con el esquema `ErrorResponse`, código
  `AUTHENTICATION_UNAVAILABLE`, añadida a las operaciones protegidas junto al 401 existente.
- 401 sin cambio de forma para Bearer y cookie (`AUTHENTICATION_REQUIRED`).

## 3. CORS

| Entrada | Respuesta |
|---|---|
| Origen configurado + preflight | `Access-Control-Allow-Origin: <origen exacto>`, `Access-Control-Allow-Credentials: true`, `Vary: Origin` |
| Origen no configurado | sin permisos CORS |
| Lista de orígenes con `*` | el servicio no arranca |
