# Feature Specification: Sesion de la consola mediante el host (HU2-124)

**Feature Branch**: `feature/HU2-124-sesion-consola-host`
**Created**: 2026-10-25
**Status**: Borrador (pendiente de aprobacion de spec y plan)

**Input**: "Como AP quiero entrar a la consola ya autenticado desde el host de la plataforma para no iniciar sesion dos
veces." Criterios: el microfrontend recibe la sesion del host y respeta el cierre de sesion; ante un 401 la consola avisa
al host en lugar de mostrar un login propio. Depende de B1 (spec 029) y C1b (front, sin construir).

Trazabilidad: spec 025 (cookie `SECURITY_BASELINE_SESSION`), HU2-111 (stream sin ticket), spec 029 (codigos NTF-2001/2004).
Principios: II, IV, VII. La historia es casi toda del repositorio Front-Notification-mfe; aqui solo se fija lo que el
servicio debe garantizar.

## Contexto y evidencia

- El contrato vigente ya documenta `SessionCookie` y la respuesta 401 reutilizable `Unauthorized` (`ErrorResponse` con
  `NTF-2001` o `NTF-2004`; con PEP, `PepErrorResponse`). No existe endpoint de login ni de logout en este servicio: la
  sesion y su cierre son del PDP/BFF.
- `CorsConfig` ya permite `allowCredentials=true`, metodos GET/POST/PUT/DELETE, cabeceras `*` y expone `X-Correlation-Id`;
  los origenes salen de `NOTIFICATION_CORS_ALLOWED_ORIGINS` (no admite comodin con credenciales).
- `GET /notifications/stream` se autentica con Bearer o cookie; con sesion invalida responde 401 al conectar.
- Sin verificar: que pasa con un stream ya abierto cuando la sesion caduca (el servicio valida al conectar).

## Clarifications

### Session 2026-10-25

- Q: Quien muestra el cierre de sesion? A: el host (decision de producto). La consola no tiene boton ni login propios.
- Q: Que evento emite la consola ante 401? A: contrato host-MFE en `contracts/host-mfe-session.md`; lo implementa el front.
- Q: Token fijo (`authToken`) o cookie? A (propuesta, a confirmar): el host entrega la sesion por cookie del BFF; `authToken`
  solo como Bearer opcional para pruebas del host; si ambos llegan, el Bearer manda (precedencia ya vigente en el servicio).
- Q: Conexion SSE abierta al caducar la sesion? A (propuesta): el servicio no la cierra; la consola trata el siguiente
  error o reconexion con 401 como sesion caducada y avisa al host. Cerrarla desde el servidor queda como excepcion VII.

## User Scenarios & Testing

### User Story 1 - 401 consistente y distinguible (P1)

La consola necesita distinguir "sesion caducada" de otros errores para avisar al host.

**Independent Test**: E2E del servicio: sin credencial, con cookie invalida y con Bearer expirado, una ruta protegida y el
stream responden 401 con `code` estable (`NTF-2001`) y `X-Correlation-Id`; claims ausentes, `NTF-2004`.

### User Story 2 - La consola del host llama con credenciales (P1)

**Independent Test**: E2E: peticion con `Origin` permitido, cookie y preflight; la respuesta trae
`Access-Control-Allow-Credentials: true` y el origen exacto; un origen no listado no recibe cabeceras CORS.

### User Story 3 - Contrato host-MFE publicado (P1)

El front dispone de un documento versionado con evento ante 401, entrega de sesion y cierre de sesion, con limites
explicitos.

## Requirements

- **FR-001**: Toda ruta protegida y el stream responden 401 con `ErrorResponse` y `code` en {`NTF-2001`, `NTF-2004`} (o
  `PepErrorResponse` con PEP activo); nunca HTML, redireccion ni cuerpo vacio.
- **FR-002**: El 401 no incluye `WWW-Authenticate` con desafio que dispare un dialogo del navegador.
- **FR-003**: CORS permite credenciales solo para origenes configurados explicitamente.
- **FR-004**: El servicio no ofrece login ni logout propios; no se crean endpoints de sesion nuevos.
- **FR-005**: `contracts/host-mfe-session.md` define evento ante 401, entrega de sesion, cierre y limites.
- **FR-006**: Lo que dependa de C1b queda como excepcion Principio VII con dueno y fecha.

## Success Criteria

- **SC-001**: Prueba E2E automatizada de FR-001..FR-003 en verde (credenciales invalidas y preflight).
- **SC-002**: El documento de contrato esta revisado por el equipo del front antes de 2026-12-15.

## Excepciones (Principio VII)

- Pruebas con host real y evento 401 en el navegador: dependen de C1b. Dueno: equipo Front. Fecha: 2026-12-15.
- Cierre del stream por el servidor al caducar la sesion: no se hace. Dueno: Seguridad/Backend. Fecha: 2026-12-15.
