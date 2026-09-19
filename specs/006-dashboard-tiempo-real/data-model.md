# Phase 1 Data Model: Ver notificaciones en tiempo real en el dashboard

Ninguna colección ni campo nuevo se persiste en MongoDB — `Notification`, `DeliveryAttempt` y
`NotificationDocument` ya existen con todo lo que esta historia necesita exponer. Lo nuevo son 3
eventos de dominio (que cierran una brecha ya existente, ver `research.md` Decisión 3) y los tipos de
**entrada** (query) y **salida** (vista en vivo) del nuevo puerto de suscripción, todos en memoria.

## Eventos de dominio nuevos (`core/domain/event`)

Mismo shape que los 4 ya existentes (`NotificationAccepted`, `NotificationQueued`,
`NotificationDelivered`, `NotificationFailed`) — sin campos nuevos, sin tenantId embebido (ver
`research.md` Decisión 4 sobre por qué la rehidratación desde Mongo hace innecesario enriquecerlos).

| Evento | Se registra en | Transición de estado |
|---|---|---|
| `NotificationRecoverable` | `Notification.markRecoverable(...)` | → `RECOVERABLE` |
| `NotificationRequeued` | `Notification.requeue()` | `RECOVERABLE`/`FAILED` → `PENDING` |
| `NotificationDiscarded` | `Notification.discard()` | → `DISCARDED` |

Cada uno: `record NotificationXxx(NotificationId notificationId, Instant occurredOn) implements DomainEvent`,
con la misma validación de no-nulos (`Preconditions.requireNonNull`) que sus 4 hermanos. El `sealed
interface DomainEvent` extiende su lista `permits` a los 7 tipos.

## Entrada: `SubscribeToNotificationUpdatesQuery` (`core/port/in`)

Construido por el controller a partir de la solicitud `GET /notifications:subscribe`. Mismos campos
de filtro que `SearchNotificationsQuery` (HU2-025), sin `limit`/`offset` — una suscripción en vivo no
pagina, muestra el conjunto vigente completo (acotado igual que la búsqueda, ver más abajo).

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `tenantId` | `TenantId` | Sí | Aísla la suscripción — nunca se omite (FR-002) |
| `recipientId` | `RecipientId` (nullable) | No | Igual semántica que en la búsqueda |
| `channelType` | `ChannelType` (nullable) | No | Igual semántica que en la búsqueda |
| `status` | `NotificationStatus` (nullable) | No | Igual semántica que en la búsqueda |
| `from` | `Instant` (nullable) | No | Igual semántica que en la búsqueda (sobre `acceptedAt`) |
| `to` | `Instant` (nullable) | No | Igual semántica que en la búsqueda (sobre `acceptedAt`) |

Se traduce internamente a un `NotificationSearchCriteria` (reutilizado, con un límite fijo de 200 para
la foto inicial — el mismo tope máximo que ya usa la búsqueda paginada, ver `research.md` Decisión 6)
para tanto la foto inicial como la evaluación en memoria de cada actualización en vivo
(`NotificationSearchCriteria.matches(Notification)`, ver `research.md` Decisión 5).

## Salida: `LiveUpdateAction` (`core/port/in`)

Enum con dos valores:

| Valor | Significado |
|---|---|
| `UPSERT` | La notificación cumple los filtros activos — el dashboard debe mostrarla o actualizarla |
| `REMOVE` | La notificación dejó de cumplir los filtros activos — el dashboard debe quitarla de la vista |

## Salida: `NotificationLiveUpdate` (`core/port/in`)

Un elemento por evento entregado al dashboard suscrito — lo que emite
`SubscribeToNotificationUpdatesUseCase.subscribe(...)`.

| Campo | Tipo | Notas |
|---|---|---|
| `notification` | `NotificationSearchResult` | Mismo tipo ya usado por la búsqueda (HU2-025) — historial completo de intentos incluido |
| `action` | `LiveUpdateAction` | `UPSERT` o `REMOVE`, según `NotificationSearchCriteria.matches(...)` |

La foto inicial (al conectar o reconectar) se emite como una serie de `NotificationLiveUpdate` con
`action = UPSERT`, una por cada notificación vigente que cumple los filtros — ver `research.md`
Decisión 6.

## Puerto de salida nuevo: `NotificationUpdatesPort` (`core/port/out`)

| Método | Firma | Notas |
|---|---|---|
| `updates` | `Flux<NotificationId> updates()` | Flujo caliente (multicast) de ids de notificaciones que acaban de cambiar — una por cada `DomainEvent` recibido del fanout, sin importar el tipo concreto de evento (ver `research.md` Decisión 4: el consumidor no necesita saber cuál transición ocurrió, solo qué notificación revisar) |

Implementado por `NotificationUpdatesRabbitAdapter` (`infrastructure/adapter/out/rabbit`) — recibe
mensajes de la cola anónima vinculada al fanout `notification.events.exchange`, deserializa
únicamente el campo `notificationId` (ignorando el resto, sin necesitar distinguir el tipo concreto
del evento), y lo emite a un `Sinks.Many<NotificationId>` compartido por todas las conexiones SSE de
esa réplica.

## Diagrama de flujo (una réplica)

```text
Notification.markRecoverable()/.requeue()/.discard()/... (registra DomainEvent)
        │
        ▼
NotificationRepository.save(...)  →  eventPublisherPort.publish(events)
        │                                     │
        │                                     ▼
        │                        RabbitMQ: notification.events.exchange (fanout)
        │                                     │
        │                    ┌────────────────┴────────────────┐
        │                    ▼ (réplica A)                     ▼ (réplica B)
        │         cola anónima A → Sinks.Many A       cola anónima B → Sinks.Many B
        │                    │                                     │
        │                    ▼                                     ▼
        │      SubscribeToNotificationUpdatesService      SubscribeToNotificationUpdatesService
        │        (findById + matches + UPSERT/REMOVE)       (findById + matches + UPSERT/REMOVE)
        │                    │                                     │
        │                    ▼                                     ▼
        │         GET /notifications:subscribe (SSE)      GET /notifications:subscribe (SSE)
        │            operador conectado a A                  operador conectado a B
        ▼
   MongoDB `notifications` (fuente de verdad que ambas réplicas leen vía findById)
```
