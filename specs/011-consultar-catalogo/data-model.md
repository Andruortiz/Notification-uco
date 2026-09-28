# Data Model: Consultar el catálogo de canales y proveedores

Esta historia no persiste nada ni cambia la colección `channel_catalog`. Todo lo de abajo son vistas de
lectura derivadas de la vista del catálogo que usa el enrutamiento (`ChannelCatalogCache`) y del registro
de adaptadores (`NotificationSenderRegistry`).

## Fuentes existentes (sin cambios de forma)

| Fuente | Forma | Dónde |
|---|---|---|
| Documento de catálogo | `{_id: <CANAL>, providers: [String], contentSchema: String?}` | `channel_catalog` (MongoDB) |
| Ruta de canal | `ChannelRoute(ChannelType channelType, List<ProviderId> providers, String contentSchema)`; `providers` no vacío | `core/port/out` |
| Vista del enrutamiento | `Map<String, ChannelRoute>` con clave = canal en mayúsculas | `ChannelCatalogCache.snapshot()` |
| Adaptador de proveedor | `NotificationSenderPort` con `providerId()` | `infrastructure/adapter/out/provider` |

## Cambios en puertos de `core`

| Tipo | Cambio |
|---|---|
| `ChannelCatalogPort` | + `Flux<ChannelRoute> findAllRoutes()` — todas las rutas de la vista del enrutamiento, con `channelType` = clave normalizada (mayúsculas). |
| `NotificationSenderPort` | + `Optional<String> disabledReason()` — abstracto; vacío = habilitado. |
| `NotificationSenderRegistry` | + `Optional<NotificationSenderPort> find(ProviderId)`; + `Set<ProviderId> providerIds()`. `resolve` no cambia. |

## Vistas nuevas (`core/port/in`)

### ProviderStatus (enum)

| Valor | Condición | `statusReason` |
|---|---|---|
| `ENABLED` | hay adaptador y `disabledReason()` vacío | `null` |
| `DISABLED` | hay adaptador y `disabledReason()` presente | el motivo del adaptador |
| `MISSING_ADAPTER` | el registro no tiene adaptador para el `providerId` | `no notification sender registered for this provider` |

No hay transiciones: el estado se calcula en cada consulta a partir de datos fijados al arrancar la réplica.

### ChannelView

| Campo | Tipo | Regla |
|---|---|---|
| `channelType` | `ChannelType` | clave de la vista del enrutamiento (mayúsculas) |
| `contentSchema` | `String` (nullable) | `null` si el guardado es `null` o en blanco |
| `providers` | `List<ChannelProviderView>` | orden de preferencia guardado; nunca vacía (heredado de `ChannelRoute`) |

### ChannelProviderView

| Campo | Tipo | Regla |
|---|---|---|
| `providerId` | `ProviderId` | tal como está en el catálogo |
| `preferenceOrder` | `int` | 1-based; 1 = el que usa el despacho |
| `status` | `ProviderStatus` | según la tabla anterior |
| `statusReason` | `String` (nullable) | según la tabla anterior |

### ProviderView

| Campo | Tipo | Regla |
|---|---|---|
| `providerId` | `ProviderId` | unión de `registry.providerIds()` y los proveedores de todas las rutas |
| `status` | `ProviderStatus` | misma derivación que en `ChannelProviderView` |
| `statusReason` | `String` (nullable) | idem |
| `channels` | `List<ProviderChannelView>` | vacía si ningún canal lo nombra |

### ProviderChannelView

| Campo | Tipo | Regla |
|---|---|---|
| `channelType` | `ChannelType` | canal que nombra al proveedor |
| `preferenceOrder` | `int` | posición 1-based del proveedor en ese canal; un proveedor repetido en un canal produce una entrada por posición |

## Orden (FR-006)

- `listChannels`: por `channelType.value()`; dentro de cada canal, por `preferenceOrder`.
- `listProviders`: por `providerId.value()`; dentro de cada proveedor, por `channelType.value()` y luego
  `preferenceOrder`.

## Validación

- Las vistas validan no nulos con `Preconditions` (como los records existentes) y copian listas con
  `List.copyOf`. `preferenceOrder >= 1`.
- `statusReason` es `null` si y solo si `status == ENABLED` (invariante del record).
