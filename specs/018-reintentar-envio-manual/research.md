# Research: Reintentar envío manualmente (HU2-027 + HU2-028)

**Feature**: 012-reintentar-envio-manual | **Date**: 2026-09-26

## Punto de partida verificado en el código

- `DeliveryAttempt` ya tiene `origin` de tipo `AttemptOrigin { MANUAL, AUTOMATIC }`, persistido en
  `DeliveryAttemptDocument` y expuesto en `NotificationHistoryItem.deliveryAttempts[].origin` (búsqueda y
  actualizaciones en tiempo real). **Hoy nunca se usa `MANUAL`**: `DispatchNotificationService.applyOutcome`
  pasa `AttemptOrigin.AUTOMATIC` en las tres ramas.
- `StatusTransitionPolicy` permite volver a `PENDING` **solo** desde `RECOVERABLE` y `FAILED`. `PENDING`
  solo avanza a `IN_PROCESS` o `DISCARDED`; `DELIVERED` y `DISCARDED` son terminales. Es exactamente la
  regla de la historia ("solo FAILED o RECOVERABLE").
- `Notification.requeue()` ya hace `transitionTo(PENDING)` y registra `NotificationRequeued`. Lo usa el
  reintento automático (`RequeuePendingNotificationsService`).
- `NotificationRepository` ya tiene `findById`, `save` (con bloqueo optimista por `version`; el adaptador
  Mongo traduce `OptimisticLockingFailureException` a `NotificationVersionConflictException`) y
  `findByStatus`. **No hace falta ningún método nuevo en el repositorio.**
- El mensaje de despacho es solo el `notificationId` como texto, con `messageId` igual al id (lo exige el
  reintento con estado del contenedor de RabbitMQ). El consumidor `NotificationDispatchListener` llama a
  `DispatchNotificationUseCase.dispatch(NotificationId)`.
- `GetNotificationStatusService` filtra por tenant tras `findById` y responde `NotificationNotFoundException`
  (404) tanto si no existe como si es de otro tenant.
- `NotificationExceptionHandler` no mapea `InvalidStatusTransitionException` ni
  `NotificationVersionConflictException` hoy.

## Decisión 1 — Cómo se reutiliza el despacho: por la misma cola, con el origen como encabezado del mensaje

**Decisión**: el nuevo caso de uso `RetryNotificationService` **no llama al despacho directamente**. Valida,
devuelve la notificación a `PENDING`, la persiste, publica los eventos y la **encola en la misma cola de
despacho** que cualquier otro envío, con el origen del intento en un encabezado del mensaje
(`x-attempt-origin: MANUAL`). El consumidor existente lee el encabezado (ausente = `AUTOMATIC`) y llama a
`DispatchNotificationUseCase.dispatch(notificationId, origin)`. `DispatchNotificationService` usa ese origen
en `markDelivered`/`markRecoverable`/`markRetriesExhausted`/`markFailed` en lugar del `AUTOMATIC` fijo.

Cambios de firma en `core` (sin métodos paralelos, un solo camino):
- `DispatchNotificationUseCase.dispatch(NotificationId, AttemptOrigin)` reemplaza a `dispatch(NotificationId)`.
- `NotificationEventPublisherPort.enqueueForDispatch(Notification, AttemptOrigin)` reemplaza a
  `enqueueForDispatch(Notification)`. `SendNotificationService` y `RequeuePendingNotificationsService` pasan
  `AUTOMATIC` explícitamente.

**Razón**:
- El contrato dice "202 — la notificación vuelve al flujo de despacho": el reintento es asíncrono (Q1).
- La cola aporta lo que un llamado directo desde la petición HTTP saltaría: el reintento de mensaje con
  estado, la cola de mensajes muertos con el motivo, la confirmación del mensaje solo tras persistir y el
  escalado del consumo independiente de la API (Principio VIII y restricción del consumidor de mensajes).
- La lógica de despacho (resolución del proveedor en el catálogo, registro de adaptadores, clasificación,
  política de reintentos, eventos) sigue viviendo **en un solo lugar**; el caso de uso nuevo no la duplica
  (FR-009).
- El origen viaja con la unidad de trabajo que produce el intento, así que sobrevive a las reentregas del
  broker (los encabezados se conservan) y queda en el mensaje muerto si el despacho agota sus intentos,
  que es el único rastro del reintento manual cuando no llega a producir un intento (Q4).
- Mensajes antiguos sin encabezado se tratan como `AUTOMATIC`: despliegue sin migración (Assumptions).

**Alternativas descartadas**:
- **(a) Llamar a `DispatchNotificationService` desde el controller o desde el caso de uso de reintento.**
  Haría la respuesta síncrona (contradice el 202 del contrato), ataría la latencia de la API a la del
  proveedor, y un fallo de transporte durante el envío no pasaría por el reintento de mensaje ni por la
  cola de mensajes muertos. Además dejaría dos puntos de entrada al despacho con garantías distintas.
- **(b) Guardar en el agregado el origen del próximo intento** (campo `nextAttemptOrigin` en
  `Notification` y en `NotificationDocument`). Robusto frente a una futura recuperación de mensajes
  perdidos, pero cambia el modelo persistido, la firma de `reconstitute` (usada en decenas de pruebas) y
  el mapeo Mongo, para un dato que solo importa entre el encolado y el intento. Se puede adoptar más
  adelante si la recuperación de huérfanos (ver Pendiente explícito en `plan.md`) necesita re-encolar
  conservando el origen.
- **(c) Método paralelo `enqueueForManualDispatch` / `dispatchManually`.** Menos pruebas a tocar, pero dos
  caminos casi idénticos en el puerto y en el caso de uso; el parámetro explícito deja el origen visible
  en cada llamada existente.
- **(d) Una cola separada para reintentos manuales.** Topología nueva, otro consumidor, otra DLQ, para
  transportar un solo dato.

## Decisión 2 — La regla "admite reintento" sale de `StatusTransitionPolicy`

**Decisión**: el caso de uso comprueba `StatusTransitionPolicy.canTransition(status, PENDING)`; si es falso,
responde `NotificationNotRetryableException` (nueva, en `core/exception`, mensaje con id y estado actual)
sin tocar nada. Si es verdadero, llama a `notification.requeue()`.

**Razón**: la política ya es la fuente de verdad de las transiciones; una lista `{FAILED, RECOVERABLE}`
paralela en el caso de uso podría divergir. Se comprueba antes de mutar para no depender de capturar
`InvalidStatusTransitionException` (que hoy no tiene traducción HTTP y es un error de programación en otros
flujos). Una prueba unitaria recorre los seis estados y fija el resultado (FAILED y RECOVERABLE → aceptado;
los otros cuatro → 400).

**Alternativa descartada**: mapear `InvalidStatusTransitionException` a 400 en el manejador global —
convertiría en 400 cualquier transición inválida futura de cualquier flujo, que es un error interno, no
del cliente.

## Decisión 3 — Concurrencia: bloqueo optimista existente, perdedor → 400

**Decisión**: el guardado usa el `version` que ya tiene el agregado. Si otra escritura ganó (otro
reintento manual o el reintento automático sobre una RECOVERABLE), `save` falla con
`NotificationVersionConflictException`; el caso de uso la traduce a `NotificationNotRetryableException`
(400) y **no encola nada** (Q3).

**Razón**: el único escritor concurrente posible sobre una FAILED/RECOVERABLE es otro "volver a PENDING";
si perdimos, la notificación ya está en PENDING y la respuesta coherente con el contrato es "el estado
actual no admite reintento". Es la actualización condicionada atómica que exige la constitución, sin
bloqueo pesimista. El reintento automático ya ignora su propio conflicto (`onErrorResume`), así que si
gana el manual, el automático la omite.

## Decisión 4 — Respuesta: la misma vista que la consulta de estado

**Decisión**: el caso de uso devuelve `NotificationStatusView` construida con la misma regla que
`GetNotificationStatusService` (proveedor y marca de tiempo del último intento, o `acceptedAt` si no hay
intentos). La regla se mueve a una fábrica estática `NotificationStatusView.from(Notification)` y ambos
servicios la usan; el controller responde `202` con `NotificationStatusResponse.from(view)`.

**Razón**: el contrato declara `NotificationStatusResponse` para el 202; un `GET /notifications/{id}`
inmediatamente posterior debe devolver lo mismo. Mover la regla evita duplicarla.

## Decisión 5 — Presupuesto de reintentos sin cambios

**Decisión**: `RetryPolicy` y el conteo de fallos recuperables (`DispatchNotificationService`,
`RequeuePendingNotificationsService`) no cambian: cuentan todos los `RECOVERABLE_FAILURE`
sin mirar el origen (Q2).

**Consecuencias verificadas** (con `maxAttempts = 5` por defecto):
- FAILED por agotamiento (5 fallos recuperables) → reintento manual → fallo recuperable: intento 6 ≥ 5 →
  `markRetriesExhausted(MANUAL)` → FAILED.
- FAILED por fallo permanente (0 recuperables) → reintento manual → fallo recuperable: intento 1 →
  RECOVERABLE; el reintento automático continúa con origen AUTOMATIC.
- RECOVERABLE con 2 recuperables → manual → fallo recuperable: intento 3 → RECOVERABLE; el plazo del
  siguiente automático se calcula con los 3 fallos.

## Decisión 6 — Nombre del encabezado y dónde vive

**Decisión**: `x-attempt-origin`, valor `MANUAL` o `AUTOMATIC` (nombre del enum). La constante vive en
`infrastructure/config/RabbitTopologyProperties` como `ATTEMPT_ORIGIN_HEADER` (la topología de mensajes
ya se describe ahí y la usan tanto el publicador como las pruebas), para que el adaptador de entrada
(`adapter.in.rabbit`) y el de salida (`adapter.out.rabbit`) no dependan entre sí. El publicador **siempre**
escribe el encabezado (también `AUTOMATIC`), así cada mensaje nuevo es explícito; el consumidor trata la
ausencia como `AUTOMATIC` y un valor desconocido como mensaje inválido (`IllegalArgumentException` → el
mensaje agota sus reintentos y termina en la DLQ con su motivo, en vez de despacharse con un origen
inventado).

## Decisión 7 — Estrategia de pruebas

| Promesa | Prueba |
|---|---|
| FR-002/FR-005/SC-005: solo FAILED y RECOVERABLE; los otros cuatro estados → 400 sin guardar ni encolar | `RetryNotificationServiceTest` (parametrizada por estado, `verify(never())` sobre `save` y `enqueueForDispatch`) |
| FR-003/FR-004: PENDING, guardado, eventos publicados, encolado con `MANUAL`, vista devuelta | `RetryNotificationServiceTest` + E2E |
| FR-006/SC-004: otro tenant → 404 idéntico a inexistente, sin efectos | Unitaria + E2E con dos tenants y control positivo (el dueño sí recibe 202) |
| FR-010/SC-003: conflicto → 400 sin encolar; dos solicitudes simultáneas → una sola aceptación y un solo intento ante el proveedor | Unitaria (conflicto simulado) + E2E con dos solicitudes en paralelo y conteo de llamadas al proveedor falso |
| FR-007/FR-008/SC-002: el intento del reintento es MANUAL; los demás AUTOMATIC | `DispatchNotificationServiceTest` (origen propagado en las cuatro ramas), `NotificationDispatchListenerTest` (encabezado → origen, ausente → AUTOMATIC, desconocido → error), `NotificationRabbitPublisherTest` (el `MessagePostProcessor` escribe el encabezado y el `messageId`), y el E2E, que es la prueba del viaje completo productor real → broker real → consumidor real → intento persistido, leyendo el histórico por la búsqueda |
| SC-001: entregada en ≤ 5 s tras aceptar el reintento | E2E con aserción explícita de `Duration` |
| Controller: ruta `/{id}:retry`, 202/400/404 | `NotificationControllerTest` + E2E |

El E2E usa `FakeProviderServer` como proveedor de correo real (`brevo`) para que el primer intento falle
de forma permanente (400) y el reintento manual se acepte (201), recorriendo aceptación → fallo → reintento
→ entrega. El publicador se prueba produciendo el mensaje con el productor real, nunca con un mensaje
armado a mano (lección de HU2-072).
