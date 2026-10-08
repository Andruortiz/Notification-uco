# Data Model: Suscripción por evento al Componente de Parámetros (HU2-022)

**Feature**: 022-suscripcion-parametros | **Date**: 2026-10-08

No hay colecciones ni campos persistidos nuevos: el cambio adoptado se guarda en `configuration_last_known`
(spec 019, sin modificaciones). Los descriptores, rangos y reglas de la 019 no cambian.

## `core`

| Elemento | Tipo | Cambio |
|---|---|---|
| `ReceivePublishedConfigurationUseCase` | puerto de entrada (nuevo) | `receive(ConfigurationChange): Mono<ConfigurationChangeOutcome>`: aplica y persiste la última conocida |
| `ReceivePublishedConfigurationService` | caso de uso (nuevo) | Contiene la lógica hoy privada de `SynchronizeConfigurationService.persistIfAdopted` |
| `SynchronizeConfigurationService` | caso de uso (modificado) | `synchronize()` = `source.fetchState()` + `receive(change)`; deja de depender de `LastKnownConfigurationPort` y `ConfigurationView` |
| `ConfigurationChange`, `ConfigurationChangeOutcome` | dominio | Sin cambios |

## `infrastructure`

| Elemento | Ubicación | Descripción |
|---|---|---|
| `ParametersEventPayload` | `adapter/in/rabbit` | Record `(Long version, Map<String,Object> values)`; copia defensiva e inmutable del mapa; `toChange()` falla si falta `version` o `values` |
| `ParametersEventListener` | `adapter/in/rabbit` | `@RabbitListener` con ack manual; lee, decide el destino (contracts) y confirma |
| `ParametersEventsProperties` | `config` | Record de `notification.parameters.events.*`; `isActive()`; validación de obligatorias |
| `ParametersEventsRabbitConfig` | `config` | Exchange, cola con DLX, DLQ, bindings, recuperador y fábrica `MANUAL` (condicionados a exchange no vacío) |
| `ConfigurationEventLogger` | `adapter/in/scheduler` | Añade `transport` a los eventos aplicado, rechazado e ignorado |
| `ErrorCode.PARAMETERS_EVENT_UNREADABLE` | `utils` | Permanente de negocio |

## Mensaje

Ver `contracts/parametros-evento-supuesto.md`. No se persiste.
