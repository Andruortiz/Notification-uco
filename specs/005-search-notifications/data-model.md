# Phase 1 Data Model: Buscar notificaciones por filtros

Ninguna entidad de dominio nueva se persiste — `Notification` y `DeliveryAttempt`
(`core/domain/Notification.java`, `core/domain/DeliveryAttempt.java`) ya existen con todos los campos
que esta historia necesita exponer. Lo nuevo son tipos de **consulta** (entrada) y **vista** (salida),
todos en memoria, sin colección Mongo propia.

## Entrada: `NotificationSearchCriteria` (`core/repository`)

Parámetro del método nuevo de `NotificationRepository`, construido por el caso de uso a partir de
`SearchNotificationsQuery` (puerto de entrada).

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `tenantId` | `TenantId` | Sí | Aísla la búsqueda — nunca se omite (FR-005) |
| `recipientId` | `RecipientId` (nullable) | No | Filtro de igualdad |
| `channelType` | `ChannelType` (nullable) | No | Filtro de igualdad |
| `status` | `NotificationStatus` (nullable) | No | Filtro de igualdad |
| `from` | `Instant` (nullable) | No | Límite inferior de `acceptedAt`, inclusive |
| `to` | `Instant` (nullable) | No | Límite superior de `acceptedAt`, inclusive |
| `limit` | `int` | Sí | Validado en `[1, 200]` antes de llegar al repositorio (FR-010) |
| `offset` | `int` | Sí | Validado en `≥ 0` |

Invariante validada al construir el record (como el resto de los value objects del proyecto): `from`
no puede ser posterior a `to` cuando ambos están presentes (FR-008) — se valida en el caso de uso, no
en el controller, para que la regla de negocio no dependa de dónde se invoque.

## Salida: `NotificationSearchResult` (`core/port/in`)

Un elemento por notificación encontrada — corresponde 1:1 al schema `NotificationHistoryItem` ya
existente en `api-notificaciones.yaml`.

| Campo | Tipo | Origen |
|---|---|---|
| `notificationId` | `NotificationId` | `Notification.notificationId()` |
| `externalId` | `ExternalId` | `Notification.externalId()` |
| `recipientId` | `RecipientId` | `Notification.recipientId()` |
| `channelType` | `ChannelType` | `Notification.channelType()` |
| `status` | `NotificationStatus` | `Notification.status()` |
| `acceptedAt` | `Instant` | `Notification.acceptedAt()` |
| `deliveryAttempts` | `List<DeliveryAttempt>` | `Notification.deliveryAttempts()` — historial completo, no solo el último (FR-004) |

## Salida: `NotificationSearchPage` (`core/port/in`)

Envoltorio de la página completa — lo que devuelve `SearchNotificationsUseCase.search(...)`.

| Campo | Tipo | Notas |
|---|---|---|
| `items` | `List<NotificationSearchResult>` | Máximo `limit` elementos, ordenados por `acceptedAt` descendente (FR-009) |
| `limit` | `int` | Eco del límite efectivamente aplicado |
| `offset` | `int` | Eco del offset efectivamente aplicado |
| `hasNext` | `boolean` | `true` si existen más resultados después de esta página (Decisión 1 de research.md) |

## Índice MongoDB nuevo (no es una entidad, pero sí un cambio de esquema físico)

`NotificationDocument` (`infrastructure/adapter/out/mongo`) gana un `@CompoundIndex` nuevo:

```java
@CompoundIndex(name = "tenant_acceptedAt", def = "{'tenantId': 1, 'acceptedAt': -1}")
```

Ningún campo del documento cambia — solo se agrega este índice, adicional al ya existente
(`tenant_external_unique`). Ver research.md, Decisión 3, para la justificación.
