# Quickstart: Suscripción por evento al Componente de Parámetros (HU2-022)

**Feature**: 022-suscripcion-parametros | **Date**: 2026-10-08

Todas las promesas medibles (SC-001 a SC-006) tienen prueba automatizada; los pasos manuales son una
demostración y no sustituyen a las pruebas.

## Pruebas automatizadas

```bash
./mvnw -B -ntp -pl core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest=ParametersEventSubscriptionE2ETest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

Docker encendido y `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` en este equipo.

| Escenario | Prueba |
|---|---|
| Adopción por evento en 5 s o menos con sondeo largo | `ParametersEventSubscriptionE2ETest` |
| Evento perdido: el sondeo converge | `ParametersEventSubscriptionE2ETest` |
| Ilegible a DLQ, rechazado y obsoleto confirmados, el válido se adopta después | `ParametersEventSubscriptionE2ETest`, `ParametersEventListenerTest` |
| Sin exchange: sin topología ni listener | `ParametersEventsInactiveTest` |
| Exchange sin cola o routing key: el arranque falla | `ParametersEventsPropertiesTest` |
| Forma del mensaje igual a la respuesta HTTP | `ParametersEventPayloadContractTest` |
| `receive` aplica y persiste; `synchronize` delega | `ReceivePublishedConfigurationServiceTest`, `SynchronizeConfigurationServiceTest` |

## Demostración manual

1. `docker compose up -d mongodb rabbitmq`.
2. Arrancar con `NOTIFICATION_PARAMETERS_EVENTS_EXCHANGE`, `NOTIFICATION_PARAMETERS_EVENTS_ROUTING_KEY` y
   `NOTIFICATION_PARAMETERS_EVENTS_QUEUE` definidas y las credenciales `RABBITMQ_*` habituales.
3. En la consola de RabbitMQ, publicar en ese exchange con esa routing key
   `{"version": 100, "values": {"requeue.interval-ms": 20000}}`.
4. `GET /configuration` con un token `ADMINISTRADOR`: `version` 100 y `requeue.interval-ms` 20000.
5. Publicar `{"version": 101, "values": {"requeue.interval-ms": 1}}`: sigue la 100 y el log trae
   `CONFIG_REJECTED`.
6. Publicar texto que no es JSON: aparece en la cola `<queue>.dlq`.

En despliegue, restringir quién puede publicar en el exchange al Componente de Parámetros (D7).
