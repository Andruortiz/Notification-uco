# Data Model: Gestionar preferencias del destinatario y excluir bajas al despachar

**Feature**: 011-preferencias-destinatario | **Date**: 2026-09-26

## Dominio (`core`)

### `RecipientPreferences` (nuevo, `core/domain`)

Record inmutable. Estado vigente del consentimiento de un destinatario dentro de un tenant.

| Campo | Tipo | Regla |
|---|---|---|
| `tenantId` | `TenantId` | obligatorio |
| `recipientId` | `RecipientId` | obligatorio |
| `optedOutAll` | `boolean` | baja total |
| `acceptedChannels` | `List<ChannelType>` | copia inmutable, cada valor en mayúsculas y sin espacios, sin duplicados, en orden de llegada; vacía si `optedOutAll` |
| `updatedAt` | `Instant` | `null` solo en el valor por defecto (nunca guardado) |

Operaciones:

- `static defaults(TenantId, RecipientId)` → `optedOutAll = false`, lista vacía, `updatedAt = null`.
- `static declare(TenantId, RecipientId, boolean optedOutAll, List<ChannelType> channels, Instant
  updatedAt)` → normaliza (`trim` + `toUpperCase(Locale.ROOT)`), deduplica y vacía la lista si
  `optedOutAll`. `updatedAt` obligatorio.
- `accepts(ChannelType channel)` → `!optedOutAll && (acceptedChannels.isEmpty() ||
  acceptedChannels contiene channel normalizado)`.

El constructor canónico también normaliza, de modo que cualquier instancia (incluida la reconstruida
desde Mongo) cumple las mismas reglas.

### `Notification` (existente, sin cambios de código)

Se usa `discard()` (PENDING → DISCARDED, registra `NotificationDiscarded`), ya existente. No se añade
ningún campo ni transición.

```text
PENDING ──(preferencia excluye el canal, en el despacho)──▶ DISCARDED   (transición ya permitida)
PENDING ──(preferencia acepta el canal)──▶ IN_PROCESS ──▶ DELIVERED | RECOVERABLE | FAILED   (sin cambios)
```

## Puertos

### `RecipientPreferencePort` (nuevo, `core/port/out`)

```text
Mono<RecipientPreferences> findByTenantAndRecipient(TenantId tenantId, RecipientId recipientId)  // vacío si no hay registro
Mono<RecipientPreferences> save(RecipientPreferences preferences)                                 // reemplazo completo
```

### Entrada (nuevos, `core/port/in`)

- `GetRecipientPreferencesQuery(TenantId tenantId, RecipientId recipientId)`
- `GetRecipientPreferencesUseCase.get(query) : Mono<RecipientPreferences>` — nunca vacío.
- `UpdateRecipientPreferencesCommand(TenantId tenantId, RecipientId recipientId, boolean optedOutAll,
  List<ChannelType> acceptedChannels)` — lista copiada, `null` → vacía.
- `UpdateRecipientPreferencesUseCase.update(command) : Mono<RecipientPreferences>`.

## Persistencia (`infrastructure/adapter/out/mongo`)

Colección `recipient_preferences`.

```json
{
  "_id": { "tenantId": "tenant-a", "recipientId": "r-1" },
  "optedOutAll": false,
  "acceptedChannels": ["EMAIL"],
  "updatedAt": { "$date": "2026-09-26T15:00:00Z" }
}
```

- `RecipientPreferenceDocument(@Id RecipientPreferenceKey id, boolean optedOutAll, List<String>
  acceptedChannels, Instant updatedAt)`
- `RecipientPreferenceKey(String tenantId, String recipientId)`
- Unicidad por `_id`; sin índices adicionales. Sin campo `version` (reemplazo completo, ver research
  Decisión 4).
- Sin migración: la colección nace vacía; la ausencia de documento es el valor por defecto.

## API (`infrastructure/adapter/in/rest`)

- `UpdatePreferencesRequest(Boolean optedOutAll, List<String> acceptedChannels)` — `null` → `false` /
  lista vacía.
- `RecipientPreferencesResponse(String recipientId, boolean optedOutAll, List<String> acceptedChannels,
  Instant updatedAt)`.
