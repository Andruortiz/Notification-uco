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
3. Con un servidor HTTP falso en `base-url` que responda
   `{"version":1,"values":{"requeue.interval-ms":10000,"provider.brevo.timeout-ms":8000,"provider.twilio.timeout-ms":8000,"provider.fcm.timeout-ms":8000}}`
   (los tres tiempos de proveedor bajan a 8 000 porque la regla (a) exige que cada uno sea menor que el
   intervalo de reencolado), esperar un intervalo de sondeo: `GET /configuration` muestra `version: 1`,
   `source: PARAMETERS`.
4. Detener el servidor falso: el log registra `PARAMETERS_UNAVAILABLE` una vez y el servicio sigue despachando.
5. Reiniciar el servicio con el servidor detenido: `source: LAST_KNOWN`, `version: 1`.
6. Responder `{"version":2,"values":{"provider.brevo.timeout-ms":20000}}` con el intervalo de reencolado en
   10 000: se rechaza (regla a), `version` sigue en 1 y el log trae `CONFIG_REJECTED`.

Resultado de la demostración manual (2026-10-07, jar de `develop` con el perfil `local`, base `demo019` aislada,
proveedores simulados, fuente de Parámetros falsa en un puerto local, sondeo de 3 s; la base se borró al terminar):

| Paso | Resultado |
|---|---|
| 1 y 2 | Cumple. `source: DEFAULTS`, `version: 0`, ocho parámetros con su valor por defecto. |
| 3 | Desviación del texto original: con `{"version":1,"values":{"requeue.interval-ms":10000}}` el servicio rechaza el cambio (`CONFIG_REJECTED`, regla (a): los tres tiempos de proveedor por defecto valen 10 000 y no son menores que el reencolado) y la versión sigue en 0. Es el comportamiento correcto del validador; el ejemplo era el defectuoso y quedó corregido arriba. Con el JSON corregido cumple: `version: 1`, `source: PARAMETERS` y evento `CONFIG_APPLIED` con las cuatro claves. |
| 4 | Cumple. Con la fuente detenida el log registra `PARAMETERS_UNAVAILABLE` una vez a lo largo de varios sondeos fallidos y el servicio sigue despachando (un `POST /notifications` pasó de `PENDING` a `DELIVERED` por el proveedor simulado). |
| 5 | Cumple. Reinicio con la fuente detenida: `source: LAST_KNOWN`, `version: 1`, con los valores de la versión 1. |
| 6 | Cumple. Al volver la fuente se registra `PARAMETERS_RECOVERED` y la versión 2 se rechaza (`CONFIG_REJECTED`, regla (a), `provider.brevo.timeout-ms=20000`); `version` sigue en 1. |

Observación sin cambio de código: mientras la fuente siga publicando el mismo cambio inválido, `CONFIG_REJECTED`
se registra en cada sondeo (cuatro veces en 12 s con sondeo de 3 s), a diferencia de `PARAMETERS_UNAVAILABLE`,
que solo se registra en la transición. Decidir si el rechazo repetido de una misma versión debe registrarse una vez
queda a criterio del dueño de la historia.
