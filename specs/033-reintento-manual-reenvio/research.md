# Research: Reintento manual y reenvío (033)

Parte de [specs/018-reintentar-envio-manual/research.md](../018-reintentar-envio-manual/research.md); aquí solo lo que cambia.

## Verificado en el código

- `DeliveryAttempt.origin` existe y se expone; hoy el despacho siempre escribe AUTOMATIC.
- `StatusTransitionPolicy` permite volver a PENDING solo desde RECOVERABLE y FAILED.
- `Notification.requeue()` hace la transición sin tocar el ciclo. `Notification.retryManually()` (añadido después de la 018) solo admite FAILED e incrementa `currentCycle`; `recoverableAttemptsInCurrentCycle()` cuenta solo el ciclo vigente, de modo que usarlo **reinicia el presupuesto**. Nadie lo invoca en producción (solo `NotificationTest`).
- `NotificationVersionConflictException` y `NotificationAlreadyAcceptedException` ya se traducen a 409; `ChannelNotAvailableException` a 400.
- `Recipient` guarda una sola `address`; `NotificationContent` lleva los adjuntos.
- `RouteAuthorizationPolicy` ya tiene la regla `POST /notification-retries` (OPERADOR); falta la del reenvío.
- `NotificationRepository.findById` no filtra por tenant; el filtro se hace en el servicio (como `GetNotificationStatusService`).
- Los controllers obtienen el tenant de `AuthenticatedPrincipal` (sesión PDP).

## D1 — Reintento por la cola con el origen como encabezado

Diseño de 018, pero el consumidor hoy NO lee el encabezado (`dispatch(id)` fija AUTOMATIC): leerlo y propagarlo es trabajo nuevo. `RetryNotificationService` encola en la misma cola con `x-attempt-origin: MANUAL`; el consumidor lo pasa a `dispatch(id, origin)`; ausente = AUTOMATIC, valor desconocido = mensaje inválido (agota y va a la DLQ). El publicador siempre escribe el encabezado. Alternativas descartadas: despacho síncrono desde el controller, campo `nextAttemptOrigin` en el agregado (cambia el esquema; revisable si se resuelve el pendiente de huérfanos), método paralelo, cola aparte.

## D2 — El contador no se reinicia: se usa `requeue()` y se retira `retryManually()`

**Decisión**: el reintento llama `requeue()` y deja `currentCycle` en 1. `retryManually()` y sus pruebas se eliminan.

**Razón**: la decisión del usuario es no reiniciar. Con ciclo fijo, `recoverableAttemptsInCurrentCycle()` equivale al conteo total, igual que antes de `retryManually`. Consecuencias con `maxAttempts = 5`: FAILED por agotamiento + fallo recuperable manual -> FAILED; FAILED permanente + fallo recuperable manual -> RECOVERABLE (intento 1); RECOVERABLE con 2 fallos -> 3.

**Alternativa descartada**: conservar `retryManually()` para FAILED y `requeue()` para RECOVERABLE (comportamiento incoherente según el estado de origen).

## D3 — Estado y concurrencia

Estado: `canTransition(status, PENDING)` antes de mutar; falso -> `NotificationStateNotAllowedException` (400). Carrera: el `save` falla por `version` con `NotificationVersionConflictException`, que **se deja propagar** a 409 (cambia respecto a 018, que lo traducía a 400 porque el contrato no declaraba 409; ahora declara `Conflict`). No se encola nada si el guardado falla.

## D4 — Reenvío = notificación nueva por el flujo de aceptación

**Decisión**: `ResendNotificationService`: (1) buscar la original por tenant y id (404 idéntico para ajeno e inexistente); (2) exigir FAILED (400); (3) exigir canal distinto (400, `ResendChannelInvalidException`); (4) armar `SendNotificationCommand` con tenant, `recipientId` y `content` (incluye adjuntos) originales, `Priority` original, canal y `Recipient` de la solicitud, y `externalId` = `<externalId original>-resend-<canal>`; (5) `SendNotificationUseCase.send`; (6) devolver `NotificationStatusView.from` de la nueva.

**Razón**: reutiliza validación de canal habilitado (400), persistencia antes de responder, encolado, `(tenantId, externalId)` único (409 en repetición o carrera) y adjuntos. No añade campo persistido ni toca la original, así que no hay carrera con ella. La relación original-reenvío se reconstruye por el prefijo del `externalId`.

**Alternativas descartadas**: mutar el canal de la original (rompe la inmutabilidad del histórico y las garantías de auditoría); vínculo persistido `resentFrom` (cambio de esquema, sin consumidor hoy).

**Límite**: el derivado no puede exceder 255 caracteres; si lo hace, 400 sin crear nada (el contrato no declara `maxLength`; se pide a Contrato fijarlo en 255).

## D5 — Reenvío solo desde FAILED

RECOVERABLE aún puede entregarse por el reintento automático; reenviar duplicaría el mensaje al destinatario. Si el negocio quiere admitirlo, el cambio es una línea, pero hay que definir si se descarta la original.

## D6 — Datos personales

Las respuestas devuelven `NotificationStatusResponse` (sin direcciones). La dirección del reenvío entra por el cuerpo; no se incluye en mensajes de excepción ni en logs (se usa `LogSanitizer` y el log registra solo ids, canal y estado). El enmascarado por rol en consultas de detalle no pertenece a esta historia; se verifica con una prueba que busca el valor completo en cuerpo de respuesta, errores y salida de log.

## D7 — Pruebas

| Promesa | Prueba |
|---|---|
| FR-003/004, SC-005: solo FAILED/RECOVERABLE; seis estados parametrizados, `verify(never())` sobre guardar y encolar | `RetryNotificationServiceTest` |
| FR-007, SC-006: sin reinicio; FAILED por agotamiento vuelve a FAILED | `RetryNotificationServiceTest` + `DispatchNotificationServiceTest` con intentos previos + E2E |
| FR-005, SC-004: dos tenants, 404 idéntico, control positivo del dueño; reintento y reenvío | unitarias + E2E |
| FR-006, SC-003: dos solicitudes simultáneas, una aceptación, un intento (conteo en `FakeProviderServer`); dos reenvíos al mismo canal, una notificación nueva | unitaria con conflicto simulado + E2E concurrente |
| FR-008, SC-002: MANUAL/AUTOMATIC | `NotificationDispatchListenerTest`, `NotificationRabbitPublisherTest` (productor real, no JSON a mano), E2E leyendo `GET /notifications` |
| SC-001, SC-007: <= 5 s con `Duration` explícita | E2E |
| FR-009/010/011/012 | `ResendNotificationServiceTest` (estado, canal igual, canal no disponible, `externalId` derivado, original intacta) + E2E FAILED por correo -> SMS entregado |
| Rol (403 a CLIENTE) y ruta | prueba de `RouteAuthorizationPolicy` + controller test |
| FR-014, SC-008 | E2E que busca la dirección completa en respuestas, errores y log capturado |

El E2E de reintento usa `FakeProviderServer`: primer intento falla de forma permanente, el reintento se acepta. Cada E2E tiene su tarea explícita en `tasks.md`.
