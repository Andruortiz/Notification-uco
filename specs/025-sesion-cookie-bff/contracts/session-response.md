# Contratos: HU2-101

## 1. Respuesta de la sesión del PDP (verificada)

Verificada por el usuario en el navegador el 2026-10-19 con una cookie válida. Es el único contrato que el adaptador y su doble
de prueba reproducen.

`GET {session.url}/api/v1/session` con `Cookie: SECURITY_BASELINE_SESSION=<valor>`:

```json
{
  "code": "SESSION_ACTIVE",
  "message": "Authenticated session",
  "data": { "subject": "<uuid>", "name": "", "email": "<correo>", "tenantId": "universidad-uco" },
  "timestamp": "...",
  "requestId": "...",
  "correlationId": "..."
}
```

- `data.name` puede venir vacío; el servicio no lo usa.
- No hay roles. El tenant efectivo es `data.tenantId`.
- Sin cookie: 401 `application/problem+json` con `code: UNAUTHORIZED`.

El adaptador exige `data.subject` y `data.tenantId` no vacíos; cualquier otra forma se trata como sesión inválida. Ignora los
campos que no usa.

Sigue sin verificar: si el backend puede llamar este endpoint servicio a servicio y si exige `X-Application-Credential`, los
códigos de error distintos de 401 y el comportamiento ante una sesión revocada.

## 2. Contrato de la API del servicio (cambios en `api-notificaciones.yaml`)

Se edita antes del código (Principio II). No hay rutas nuevas.

- `components.securitySchemes.SessionCookie.description`: el servicio acepta la cookie; precedencia Bearer > cookie; el PEP la
  valida y debe estar activo; el rol efectivo es `CLIENTE`.
- Descripción global de seguridad: retirar la frase "el modo interino solo acepta Bearer".
- Nueva respuesta reutilizable `AuthenticationUnavailable` (503) con el esquema `ErrorResponse`, código
  `NTF-2003`, añadida a las operaciones protegidas junto al 401 existente.
- 401 sin cambio de forma para Bearer y cookie (`AUTHENTICATION_REQUIRED`).

## 3. CORS

| Entrada | Respuesta |
|---|---|
| Origen configurado + preflight | `Access-Control-Allow-Origin: <origen exacto>`, `Access-Control-Allow-Credentials: true`, `Vary: Origin` |
| Origen no configurado | sin permisos CORS |
| Lista de orígenes con `*` | el servicio no arranca |
