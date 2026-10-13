# Data Model: Corregir los hallazgos de la revisión de las specs 011 a 016

**Feature**: 017-Fix-specs-012-016 | **Fecha**: 2026-10-05

Solo se describe lo que cambia. Los nombres de clases y colecciones son los de `develop`.

## 1. Notificación (`Notification`, colección `notifications`)

Campos nuevos, todos opcionales al leer para que los documentos existentes sigan siendo válidos:

| Campo | Tipo | Significado | Valor al leer un documento antiguo |
|---|---|---|---|
| `dispatchReservedAt` | instante | Cuándo el despacho reservó la notificación (`PENDING -> IN_PROCESS`) | nulo |
| `pendingSince` | instante | Cuándo entró por última vez a `PENDING`; criterio de huérfano | `acceptedAt` |
| `currentCycle` | entero >= 1 | Ciclo de intentos vigente; el reintento manual lo incrementa | 1 |

`DeliveryAttempt` gana `cycle` (entero >= 1). Un intento antiguo sin `cycle` se lee como ciclo 1.

### Reglas

- El conteo de intentos recuperables, `RetryPolicy.shouldGiveUp(n)` y `nextBackoff(n)` se evalúan solo sobre
  los intentos del `currentCycle`. El historial completo se conserva.
- `pendingSince` se fija en cada transición a `PENDING` (aceptación, reencolado y reintento manual).
- `dispatchReservedAt` se fija al reservar y se limpia al guardar un resultado o al liberar la reserva.
- `version` sigue siendo la versión optimista; la reserva y la liberación la incrementan, y el servicio usa
  siempre la instancia devuelta por la operación anterior.

### Transiciones de estado (sin estados nuevos)

```text
PENDING --reserveForDispatch--> IN_PROCESS --resultado--> DELIVERED | RECOVERABLE | FAILED
IN_PROCESS --releaseReservation (excepción del proveedor antes de un resultado)--> PENDING
IN_PROCESS --barrido (dispatchReservedAt > 10 min)--> RECOVERABLE
RECOVERABLE --reencolado--> PENDING      FAILED --reintento manual (018)--> PENDING [currentCycle + 1]
```

Una reentrega sobre `IN_PROCESS`, `RECOVERABLE`, `DELIVERED`, `FAILED` o `DISCARDED` no cambia nada y se
ignora con un registro INFO.

### Índices

| Colección | Índice | Motivo |
|---|---|---|
| `notifications` | `{status: 1, pendingSince: 1}` (nuevo) | Consultas de huérfanos y de `IN_PROCESS` atascadas con límite de lote (B-15) |
| `notifications` | `{tenantId: 1, externalId: 1}` único (existente) | Sin cambios |

La consulta de huérfanos acepta `pendingSince` ausente usando `acceptedAt` como respaldo, para cubrir los
documentos anteriores a este cambio.

## 2. Puerto `NotificationRepository` (core)

Métodos nuevos, implementados en `NotificationMongoAdapter` con `ReactiveMongoTemplate.findAndModify`:

| Método | Contrato |
|---|---|
| `reserveForDispatch(id)` | Si la notificación está en `PENDING`, la pasa a `IN_PROCESS`, fija `dispatchReservedAt` y devuelve el documento actualizado; si no, devuelve vacío. Atómico entre réplicas |
| `releaseReservation(id)` | Si está en `IN_PROCESS`, vuelve a `PENDING`, fija `pendingSince` y limpia `dispatchReservedAt`; devuelve la notificación o vacío |
| `claimForRequeue(threshold, limit)` | Toma hasta `limit` notificaciones `PENDING` con `pendingSince` anterior al umbral, adelantando `pendingSince` a ahora en la misma operación; cada una la recibe una sola réplica |
| `claimStuckInProcess(threshold, limit)` | Toma `IN_PROCESS` con `dispatchReservedAt` anterior al umbral y las pasa a `RECOVERABLE` registrando el motivo |

`findByStatus` queda sin uso en los flujos periódicos.

## 3. Registro del lote (`NotificationBatchRecord`, colección `notification_batches`)

- Clave única existente `tenant_batch_unique` `{tenantId, batchId}`. Sin cambios de esquema.
- Puerto `NotificationBatchRepository`: nuevo `findByTenantAndBatchId(tenantId, batchId)` que devuelve el
  resultado original.
- `BatchAcceptedResult` gana `trackingSaved` (booleano): `true` si el registro quedó guardado o ya existía,
  `false` si el guardado falló.
- `BatchItemResult.failed(externalId, reason)` recibe un motivo fijo (`Internal error`); la causa se registra,
  no se persiste ni se devuelve.
- Constante `BatchLimits.MAX_ITEMS = 500` en `core`.

## 4. Ticket de suscripción (`SubscriptionTicket`, colección nueva `subscription_tickets`)

| Campo | Tipo | Notas |
|---|---|---|
| `_id` | cadena | Huella SHA-256 del ticket en base64url; el ticket en claro nunca se guarda |
| `tenantId` | cadena | Tenant del principal que lo pidió |
| `role` | cadena | Rol del principal |
| `subject` | cadena | Sujeto del principal, para el registro |
| `expiresAt` | instante | Emisión + 30 s |

- Índice TTL sobre `expiresAt` con `expireAfterSeconds: 60`. Como el TTL de MongoDB corre cada minuto, la
  consulta de consumo también exige `expiresAt > ahora`.
- Consumo: `findAndRemove` por `_id` y `expiresAt > ahora`. Devuelve el principal una sola vez.
- Puerto de salida `SubscriptionTicketPort` (`issue(principal)`, `consume(ticket)`) en `core`; adaptador Mongo en
  `infrastructure`. Caso de uso `IssueSubscriptionTicketUseCase`.
- El ticket en claro mide 43 caracteres base64url (32 bytes aleatorios de `SecureRandom`).

## 5. Propiedades de autenticación

| Propiedad | Regla de arranque |
|---|---|
| `notification.auth.jwt.hs256-secret` | Obligatoria en modo `local`; mínimo 32 bytes; distinta del valor de desarrollo; el valor de desarrollo solo se acepta con el perfil `local` |
| `notification.auth.jwt.ttl-minutes` | Entre 1 y 1440 |
| Claim `exp` | Obligatoria en todos los modos; un token sin ella se rechaza |

## 6. Cargas de adjuntos

Sin cambios de esquema. `ScanState`, `AttachmentRejectionReason` y las transiciones por CAS ya cubren
`FAILED` con `OBJECT_MISSING`, `SIZE_MISMATCH`, `SCAN_EXHAUSTED` y `EXPIRED`. El listener, al agotar los
intentos con `AttachmentObjectChangedException`, llama al caso de uso de fallo existente para pasar la carga a
`FAILED/SCAN_EXHAUSTED`.
