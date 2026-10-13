# Data Model: Enrutar cada notificación al adaptador de su proveedor por providerId

**Feature**: `007-enrutar-adaptador-proveedor` | **Date**: 2026-09-21

Esta historia **no introduce ninguna entidad persistida, colección, documento ni campo nuevo**. Lo que
cambia es una relación en tiempo de ejecución: un `ProviderId` deja de ser solo una etiqueta y pasa a
resolver a un adaptador concreto.

## Tipos existentes que participan (sin cambios de forma)

| Tipo | Dónde vive | Rol en esta historia |
|------|-----------|----------------------|
| `ProviderId(String value)` | `core/domain/valueobject` | Clave de resolución. Ya valida no-blanco y tiene `equals`/`hashCode` de record, requisito para usarlo como clave de `Map`. |
| `ChannelRoute(channelType, providers, contentSchema)` | `core/port/out` | Fuente del proveedor preferente (`providers.get(0)`). Sin cambios. |
| `DeliveryAttempt(occurredOn, result, origin, providerId)` | `core/domain` | Sigue igual; su `providerId` ahora corresponde con certeza al adaptador que envió. |
| `AttemptResult` | `core/domain/valueobject` | Sigue siendo el único vocabulario para *fallo del proveedor*. Un proveedor no resuelto NO produce ningún `AttemptResult`. |

## Tipos nuevos

### `NotificationSenderPort` (modificado)

```text
Mono<AttemptResult> send(Notification notification)
ProviderId providerId()                               // NUEVO
```

Regla: `providerId()` debe ser estable (mismo valor en toda la vida del proceso) y no nulo. Todo
adaptador de envío queda obligado por el compilador a declararlo.

### `NotificationSenderRegistry` (nuevo, `core/port/out`)

- **Estado**: `Map<ProviderId, NotificationSenderPort>` inmutable, construido una vez.
- **Construcción**: recibe la colección de adaptadores disponibles.
  - Rechaza una colección nula.
  - Rechaza un adaptador con `providerId()` nulo.
  - Rechaza dos adaptadores con el mismo `providerId` (`IllegalArgumentException` nombrando el
    identificador duplicado) — FR-009 / SC-007: el contexto no arranca.
  - Una colección vacía es válida: el servicio arranca y el fallo se manifiesta al despachar.
- **Operación**: `resolve(ProviderId) -> NotificationSenderPort`.
  - Identificador conocido → el adaptador correspondiente.
  - Identificador desconocido → lanza `ProviderNotAvailableException` (FR-004, FR-005).
  - Identificador nulo → `NullPointerException` vía `Preconditions.requireNonNull`, consistente con el
    resto de `core`.

### `ProviderNotAvailableException` (nueva, `core/exception`)

- `RuntimeException`, igual que `ChannelNotAvailableException` y `NotificationNotFoundException`.
- Construida con el `ProviderId` no resuelto; el mensaje **debe** contener el valor del identificador,
  porque ese mensaje es lo que termina en el header `x-exception-message` de la DLQ y es la única
  pista del diagnóstico (FR-005).

## Relación resultante

```text
ChannelRoute.preferredProvider() ──(ProviderId)──▶ NotificationSenderRegistry.resolve(...)
                                                            │
                                     conocido ──────────────┼──────────────▶ NotificationSenderPort concreto
                                                            │                        │
                                     desconocido ───────────┘                        ▼
                                              │                              Mono<AttemptResult>
                                              ▼                                      │
                                ProviderNotAvailableException                        ▼
                                              │                          DeliveryAttempt persistido
                                              ▼                          (estado de la notificación cambia)
                        sin intento, sin cambio de estado,
                        mensaje a la DLQ con la causa
```

Las dos ramas inferiores son los dos modos de fallo que FR-006 exige mantener distinguibles: la
izquierda no deja huella en la notificación (solo en la DLQ), la derecha sí deja intento y estado.

## Estados y transiciones

Sin cambios. Esta historia no agrega, elimina ni redefine ningún `NotificationStatus` ni ninguna
transición. El caso de proveedor no resuelto se caracteriza precisamente por **no** producir
transición alguna.
