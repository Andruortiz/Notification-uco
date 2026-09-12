# Phase 1 Data Model: Reencolar notificaciones recuperables vencidas (HU2-030)

Esta historia no introduce entidades ni value objects nuevos — reutiliza el agregado `Notification` y la entidad `DeliveryAttempt` ya existentes. Documenta qué campos de ellos participan en la decisión "¿está vencida?".

## Entidades reutilizadas

### `Notification` (agregado existente)

Campos relevantes para esta historia:

| Campo | Uso en esta historia |
|---|---|
| `status` | Filtro de selección — solo interesan las que están en `RECOVERABLE` |
| `deliveryAttempts()` | Se usa el último intento para saber cuándo fue el último fallo |
| `version` | Bloqueo optimista — evita que dos réplicas reencolen la misma notificación a la vez (ver `research.md`, punto 4) |

Transición de estado que dispara esta historia: `RECOVERABLE` → `PENDING`, vía el método ya existente `Notification.requeue()`. No se agrega ningún estado nuevo ni ninguna transición nueva a `StatusTransitionPolicy`.

### `DeliveryAttempt` (entidad existente)

Campos relevantes:

| Campo | Uso en esta historia |
|---|---|
| `occurredOn()` | Marca de tiempo del último intento — base para calcular si venció el tiempo de espera |
| `result()` | Se cuentan los intentos con `RECOVERABLE_FAILURE` para saber cuántos reintentos lleva (mismo cálculo que `DispatchNotificationService.recoverableAttemptCount`) |

## Regla de negocio (derivada, no persistida)

"¿Está vencida?" no es un campo almacenado — se calcula en el momento de la consulta:

```
recoverableAttemptCount = cantidad de DeliveryAttempt con result = RECOVERABLE_FAILURE
tiempoDeEspera = RetryPolicy.nextBackoff(recoverableAttemptCount)
vencida = ahora >= últimoIntento.occurredOn() + tiempoDeEspera
```

## Configuración nueva (no es una entidad de dominio, pero sí un dato nuevo)

| Propiedad | Dónde | Valor por defecto |
|---|---|---|
| `notification.scheduler.requeue-interval-ms` | `application.yml` | `30000` (30s), configurable vía `NOTIFICATION_REQUEUE_INTERVAL_MS` |
