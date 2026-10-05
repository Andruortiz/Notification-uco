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
| Cambio válido adoptado en menos de un intervalo, el listener usa el nuevo tope | `ConfigurationSyncE2ETest` (bloqueada por E4) |
| Cambio inválido rechazado completo, versión intacta, evento con motivo | `ConfigurationSyncE2ETest`, `ConfigurationValidatorTest` |
| Reglas (b), (c), (d) y arranque fallido con defectos inválidos | `ConfigurationValidatorTest`, `ConfigurationStartupTest` |
| Operación en curso conserva su valor | `ConfigurationHolderTest`, `ConfigurationSyncE2ETest` |
| Versión menor o igual ignorada; reversión con versión nueva | `ApplyConfigurationChangeServiceTest` |
| `GET /configuration`: roles, contenido exacto, sin secretos | `ConfigurationSyncE2ETest`, `ParameterRegistryTest` |
| `saveIfNewer` no retrocede; documento corrupto descartado | `LastKnownConfigurationMongoAdapterTest` |
| Arranque con almacén lento en 30 s o menos | `ConfigurationStartupTest` |

## Demostración manual (opcional)

1. `docker compose up -d mongodb rabbitmq` y arrancar el servicio sin `notification.parameters.base-url`.
2. `GET /configuration` con un token `ADMINISTRADOR`: `source: DEFAULTS`, `version: 0`, cuatro grupos de
   parámetros.
3. Con un servidor HTTP falso en `base-url` que responda `{"version":1,"values":{"requeue.interval-ms":10000}}`,
   esperar un intervalo de sondeo: `GET /configuration` muestra `version: 1`, `source: PARAMETERS`.
4. Detener el servidor falso: el log registra `PARAMETERS_UNAVAILABLE` una vez y el servicio sigue despachando.
5. Reiniciar el servicio con el servidor detenido: `source: LAST_KNOWN`, `version: 1`.
6. Responder `{"version":2,"values":{"provider.brevo.timeout-ms":20000}}` con el intervalo de reencolado en
   10 000: se rechaza (regla a), `version` sigue en 1 y el log trae `CONFIG_REJECTED`.
