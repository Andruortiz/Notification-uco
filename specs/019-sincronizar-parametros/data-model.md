# Data Model: Sincronizar configuración transversal de Parámetros (HU2-073)

**Feature**: 019-sincronizar-parametros | **Date**: 2026-10-05

## Registro inicial de descriptores

| Clave | Tipo | Defecto (arranque) | Rango | Ámbito | Adopción |
|---|---|---|---|---|---|
| `dispatch.max-attempts` | entero | `notification.rabbit.dispatch.max-attempts` (3) | 1 a 20 | global | en caliente (depende de E4) |
| `provider.<id>.timeout-ms` | entero (ms) | `notification.provider.<id>.timeout-ms` (10 000) | 1 000 a 60 000 | proveedor (`brevo`, `twilio`, `fcm`) | en caliente |
| `provider.<id>.connect-timeout-ms` | entero (ms) | `notification.provider.<id>.connect-timeout-ms` (5 000) | 500 a 30 000 | proveedor (`brevo`, `twilio`, `fcm`) | en caliente |
| `requeue.interval-ms` | entero (ms) | `notification.scheduler.requeue-interval-ms` (30 000) | 5 000 a 600 000 | global | en caliente |

Los rangos fueron aceptados por el usuario como punto de partida (Q4). Ningún descriptor inicial usa el modo "con reinicio";
ese modo se ejerce con un descriptor de prueba. El proveedor `simulated` no tiene tiempos gestionables.

## Entidades de `core` (`domain/configuration`)

| Entidad | Campos | Notas |
|---|---|---|
| `ParameterDescriptor` | `key`, `type` (`INTEGER`), `defaultValue`, `min`, `max`, `scope` (`GLOBAL`/`CHANNEL`/`PROVIDER`), `scopeIds`, `adoption` (`HOT`/`RESTART`) | Valida un valor y su ámbito |
| `ParameterRegistry` | lista inmutable de descriptores | Fuente única del registro expuesto y de la validación |
| `ConfigurationSnapshot` | `version` (long), `source` (`PARAMETERS`/`LAST_KNOWN`/`DEFAULTS`), `values` (mapa inmutable), `adoptedAt`, `pendingRestart` (claves) | Se reemplaza completa; nunca se muta |
| `ConfigurationChange` | `version` (long), `values` (mapa clave-valor, parcial o completo) | Se acepta o se rechaza como un todo |
| `FixedConfiguration` | proveedores habilitados por canal, espera y número de intentos del análisis, ventana de barrido, esquema de contenido por canal, límites por proveedor | Solo entrada de las reglas; no gestionable |
| `ConfigurationChangeOutcome` | `APPLIED`, `PENDING_RESTART`, `IGNORED_STALE`, `REJECTED` + motivo + claves | Lo devuelve el caso de uso |
| `CrossParameterRule` | regla (a), (b), (c), (d) | Una clase por regla |

## Puertos

| Puerto | Dirección | Operaciones |
|---|---|---|
| `ApplyConfigurationChangeUseCase` | entrada | `apply(ConfigurationChange)` |
| `SynchronizeConfigurationUseCase` | entrada | `synchronize()` (consulta la fuente y aplica) |
| `RestoreLastKnownConfigurationUseCase` | entrada | `restore()` (arranque) |
| `QueryConfigurationUseCase` | entrada | `describe()` |
| `ConfigurationView` | lectura | `snapshot()` |
| `ParametersSourcePort` | salida | `fetchState()` |
| `LastKnownConfigurationPort` | salida | `load()`, `saveIfNewer(snapshot)` |

## Persistencia (MongoDB)

Colección `configuration_last_known`, un documento:

| Campo | Tipo | Notas |
|---|---|---|
| `_id` | texto | Siempre `current` |
| `version` | long | Condición de `saveIfNewer` (`version < nueva`) |
| `values` | documento | Claves del registro |
| `schemaHash` | texto | Huella de las claves y rangos del registro; si cambia tras una actualización, el documento se descarta |
| `source` | texto | Origen al guardar |
| `adoptedAt` | fecha | |

Sin índices adicionales. Sin cambios en las colecciones existentes.

## Respuesta del endpoint

`ConfigurationResponse`: `version`, `source`, `adoptedAt`, `pendingRestart[]`, `parameters[]` con
`key`, `type`, `defaultValue`, `currentValue`, `min`, `max`, `scope`, `scopeIds`, `adoption`. Ningún campo
contiene credenciales, topología ni direcciones base.
