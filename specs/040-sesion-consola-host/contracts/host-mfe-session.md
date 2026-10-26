# Contrato host-MFE de sesion (propuesta para el front)

Version 0.2, 2026-10-25. Consumidor: Front-Notification-mfe. Estado: borrador hasta C1b; correcciones aprobadas 2026-10-25.

## Entrega de sesion
- El host autentica contra el BFF/PDP; la cookie `SECURITY_BASELINE_SESSION` es la via principal y viaja sola (`credentials: include`) al servicio.
- Opcional `authToken` (Bearer) solo para desarrollo del host; si llegan ambos, el Bearer manda.

## Cierre de sesion
- Lo muestra y ejecuta el host (`showLogout` en el host, nunca en la consola). La consola no llama a ningun logout.
- Al cerrar, el host desmonta la consola; la consola cierra su `EventSource`.

## Evento ante 401
- Evento DOM `console:session-expired` (CustomEvent, `bubbles`, `composed`) en el elemento raiz de la consola.
- `detail`: `{ code: "NTF-2001" | "NTF-2004" | "UNKNOWN", correlationId: string|null, source: "http" | "stream" }`.
- Forma aprobada; nombre y `detail` a validar por el equipo del front antes de 2026-12-15 (SC-002).
- Sin tokens, cookies ni cuerpo de la respuesta en `detail`.
- Se emite una vez por rafaga de 401; la consola no reintenta ni muestra login; espera que el host reinicie la sesion.
- 403 y 503 no emiten este evento.

## Limites
- No define el flujo de login del host ni la renovacion de sesion.
- Stream abierto al caducar: sin cierre por el servidor (excepcion VII, dueno Seguridad/Backend, 2026-12-15); el error o la reconexion con 401 emite el evento.
