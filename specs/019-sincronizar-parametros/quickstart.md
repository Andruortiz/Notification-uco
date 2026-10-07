# Quickstart: Sincronizar configuración transversal de Parámetros (HU2-073)

**Feature**: 019-sincronizar-parametros | **Date**: 2026-10-05

Todas las promesas medibles (SC-001 a SC-007) tienen prueba automatizada; los pasos manuales son una
demostración.

## Pruebas automatizadas

```bash
./mvnw -B -ntp -pl core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest=ConfigurationSyncE2ETest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

Docker encendido y `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` en este equipo.

| Escenario | Prueba |
|---|---|
| Arranque sin Parámetros: valores por defecto y última conocida | `ConfigurationSyncE2ETest` |
| Cambio de `requeue.interval-ms` adoptado en menos de un intervalo de sondeo más margen | `ConfigurationSyncE2ETest` |
| Cambio de `dispatch.max-attempts` rige en el listener en el mensaje siguiente | `ConfigurationDispatchAttemptsE2ETest` |
| Cambio de tiempo de espera de proveedor: la llamada en vuelo conserva su valor, la siguiente usa el nuevo | `ConfigurationProviderTimeoutsE2ETest` |
| Cambio inválido rechazado completo, versión intacta, evento con motivo | `ConfigurationSyncE2ETest`, `ConfigurationValidatorTest` |
| Reglas (b), (c), (d): unitarias sobre instantáneas sintéticas y arranque fallido con defectos inválidos (la regla (a) va por E2E) | `ConfigurationValidatorTest`, `ConfigurationStartupTest` |
| Operación en curso conserva su valor | `ConfigurationHolderTest`, `ConfigurationSyncE2ETest` |
| Versión menor o igual ignorada; reversión con versión nueva | `ApplyConfigurationChangeServiceTest` |
| `GET /configuration`: roles, contenido exacto, sin secretos | `ConfigurationObservabilityE2ETest`, `ParameterRegistryTest` |
| `PARAMETERS_UNAVAILABLE` solo en la transición y `PARAMETERS_RECOVERED` una vez | `ParametersPollingSchedulerTest`, `ConfigurationEventLoggerTest` |
| `saveIfNewer` no retrocede; documento corrupto descartado | `LastKnownConfigurationMongoAdapterTest` |
| Arranque con almacén lento en 30 s o menos | `ConfigurationStartupTest` |

El `verify` completo del 2026-10-05 pasó en verde (T058 de `tasks.md`); la fuente de Parámetros
está inactiva por defecto y solo se activa con `notification.parameters.base-url`. Las propiedades
`notification.parameters.*` y `GET /configuration` están descritas en el `README.md`.

## Demostración manual (opcional)

1. `docker compose up -d mongodb rabbitmq` y arrancar el servicio sin `notification.parameters.base-url` (fuente inactiva).
2. `GET /configuration` con un token `ADMINISTRADOR`: `source: DEFAULTS`, `version: 0`, cuatro grupos de
   parámetros.
3. Con un servidor HTTP falso en `base-url` que responda `{"version":1,"values":{"requeue.interval-ms":10000}}`,
   esperar un intervalo de sondeo: `GET /configuration` muestra `version: 1`, `source: PARAMETERS`.
4. Detener el servidor falso: el log registra `PARAMETERS_UNAVAILABLE` una vez y el servicio sigue despachando.
5. Reiniciar el servicio con el servidor detenido: `source: LAST_KNOWN`, `version: 1`.
6. Responder `{"version":2,"values":{"provider.brevo.timeout-ms":20000}}` con el intervalo de reencolado en
   10 000: se rechaza (regla a), `version` sigue en 1 y el log trae `CONFIG_REJECTED`.

Resultado de la demostración manual: pendiente, requiere ejecutar el servicio (decisión del usuario).
