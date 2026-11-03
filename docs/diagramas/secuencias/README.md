# Diagramas de secuencia por flujo

Cada flujo tiene su diagrama editable en draw.io (`.drawio`). Se dibujaron a partir del código de
`develop` a 2026-10-06. Cuando un flujo cambia, se actualiza su diagrama en el mismo PR.

## Flujos dibujados

| Flujo | Diagrama | Punto de entrada | Specs |
|---|---|---|---|
| F01 Envío y despacho (incluye el reintento manual, planificado) | [`F01-envio-y-despacho.drawio`](F01-envio-y-despacho.drawio) | `POST /notifications`, `GET /notifications/detail`, `POST /notification-retries` (018) | 001, 004, 007, 017 (E4), 018 |
| F02 Reencolado y recuperación | [`F02-reencolado-y-recuperacion.drawio`](F02-reencolado-y-recuperacion.drawio) | Planificador | 001, 017 (E4) |
| F03 Adjuntos: subida y escaneo | [`F03-adjuntos-subida-y-escaneo.drawio`](F03-adjuntos-subida-y-escaneo.drawio) | `POST /attachment-uploads`, `POST /attachment-upload-completions` | 012 |
| F04 Lote y adjuntos en el envío | [`F04-lote-y-adjuntos-en-envio.drawio`](F04-lote-y-adjuntos-en-envio.drawio) | `POST /notifications/batches` | 012, 014, 017 (E3) |
| F05 Autenticación y panel en vivo | [`F05-autenticacion-y-panel-en-vivo.drawio`](F05-autenticacion-y-panel-en-vivo.drawio) | Cualquier ruta, `POST /notifications/stream/tickets`, `GET /notifications/stream` | 006, 015, 017 (E1, E2) |
| F09 Sincronización de parámetros (incluye el aviso por evento, planificado) | [`F09-sincronizacion-de-parametros.drawio`](F09-sincronizacion-de-parametros.drawio) | Arranque, sondeo periódico, `GET /configuration`; evento de cambio de Parámetros (planificado) | 019 |

F01 es el flujo base: F02, F03 y F04 lo referencian en lugar de repetirlo. Los pasos en naranja
(F09, sección 5) están planificados y todavía no existen en el código.

## Flujos pendientes de dibujar

| Flujo | Cuándo |
|---|---|
| F06 Enrutado por proveedor y adaptadores (Brevo, Twilio, FCM) | Ya implementado |
| F07 Búsqueda de notificaciones y consulta del catálogo | Ya implementado (005, 011) |
| F08 Arranque del servicio (Key Vault, guarda del secreto, siembra del catálogo) | Ya implementado |
| F10 Trazado y métricas | Ya implementado (020, PR #63) |
| F12 Preferencias del destinatario | Al implementar la spec 013 |
