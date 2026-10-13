# Phase 1 Data Model: Catálogo de canales persistido en Mongo

## Entidad nueva: `ChannelCatalogEntry` (persistida como `ChannelCatalogDocument`)

Equivalente persistido de `ChannelCatalogProperties.ChannelEntry` — la configuración activa de un canal.

| Campo | Tipo | Regla |
|---|---|---|
| `channelType` (`_id`) | `String` | Clave natural — nombre del canal (ej. `"EMAIL"`). Único por construcción (es el `_id` del documento) — cumple FR-007. |
| `providers` | `List<String>` | Lista ordenada de `ProviderId` candidatos; el primero es el preferido (`ChannelRoute.preferredProvider()`, ya existente). Vacía o ausente ⇒ el canal se trata como sin ruta activa (edge case ya documentado en `spec.md`). |
| `contentSchema` | `String` (nullable) | Referencia al schema de validación de contenido para ese canal — mismo campo que hoy en `ChannelCatalogProperties.ChannelEntry.contentSchema()`. |

Colección: `channel_catalog`.

Sin campo de versión (`@Version`): esta historia no expone ninguna vía de escritura concurrente (FR-008) — la única escritura es la migración de arranque, que corre una sola vez por ambiente.

Sin transiciones de estado — es dato de referencia, no un agregado de dominio con ciclo de vida.

## Snapshot en memoria (derivado, no persistido)

Cada réplica mantiene `Map<String, ChannelRoute>` (canal → `ChannelRoute` ya existente en `core/port/out/ChannelRoute.java`) detrás de un `AtomicReference`, reconstruido completo en cada refresco exitoso desde Mongo. No es una entidad de dominio ni se persiste — es el mecanismo de caché descrito en `research.md` (punto 1).

## Reutilizado sin cambios

- **`ChannelRoute`** (`core/port/out/ChannelRoute.java`): tipo de retorno de `ChannelCatalogPort.findActiveRoute`, sin cambios.
- **`ChannelCatalogPort`** (`core/port/out/ChannelCatalogPort.java`): firma sin cambios — `Mono<ChannelRoute> findActiveRoute(ChannelType channel, TenantId tenantId)`. `tenantId` se sigue recibiendo pero no filtra (FR-003).
- **`ChannelCatalogProperties`** (`infrastructure/adapter/out/catalog/`): se conserva, pero cambia de rol — deja de ser la fuente de verdad en runtime y pasa a ser únicamente la fuente de la semilla de migración (`research.md`, punto 4).

## Configuración nueva

| Propiedad | Dónde | Valor por defecto |
|---|---|---|
| `notification.catalog.refresh-interval-ms` | `application.yml` | `30000` (30s), configurable vía `NOTIFICATION_CATALOG_REFRESH_INTERVAL_MS` — intervalo del sondeo que refresca el snapshot en memoria desde Mongo (FR-004). |
