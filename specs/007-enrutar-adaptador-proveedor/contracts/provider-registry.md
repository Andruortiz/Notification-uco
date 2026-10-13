# Contrato: registro de proveedores de envío

**Feature**: `007-enrutar-adaptador-proveedor` | **Date**: 2026-09-21

## Contrato HTTP

**Ninguno.** Esta historia no agrega ni modifica operaciones de la API: el enrutamiento ocurre dentro
del despacho, que entra por RabbitMQ. `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`
no se toca. El Principio II (contract-first) no aplica por ausencia de endpoint nuevo; se deja escrito
para que la revisión no lo interprete como un contrato omitido.

La única superficie observable por un cliente HTTP que cambia de significado es el campo `providerId`
de `GET /notifications/{id}`: ya declarado en el contrato, ahora garantiza que su valor es el del
proveedor que realmente ejecutó el envío.

## Contrato interno: el puerto de envío

```java
public interface NotificationSenderPort {
  Mono<AttemptResult> send(Notification notification);
  ProviderId providerId();
}
```

Obligaciones de todo implementador:

| # | Obligación |
|---|-----------|
| 1 | `providerId()` devuelve un valor no nulo y **estable** durante toda la vida del proceso. |
| 2 | Dos implementadores distintos nunca devuelven el mismo `providerId` (se verifica al arrancar). |
| 3 | `send(...)` señala un fallo **del proveedor** como `AttemptResult` (`RECOVERABLE_FAILURE` o `PERMANENT_FAILURE`), no como excepción. Un `Mono` en error se trata como fallo de despacho, no como intento fallido. |
| 4 | El valor de `providerId()` es el mismo que el catálogo usa para declarar ese proveedor en un canal. |

## Contrato interno: el registro

```java
public final class NotificationSenderRegistry {
  public NotificationSenderRegistry(Collection<NotificationSenderPort> senders);
  public NotificationSenderPort resolve(ProviderId providerId);
}
```

| Entrada | Resultado esperado |
|---------|--------------------|
| Colección con dos adaptadores de identificadores distintos, se pide uno de ellos | Devuelve exactamente ese adaptador; el otro no recibe nada. |
| Se pide un identificador que nadie declara | Lanza `ProviderNotAvailableException`, cuyo mensaje contiene el valor del identificador. |
| Se pide `null` | `NullPointerException` (`Preconditions.requireNonNull`). |
| Construcción con dos adaptadores del mismo identificador | `IllegalArgumentException` nombrando el identificador duplicado — el contexto de Spring no arranca. |
| Construcción con un adaptador cuyo `providerId()` es `null` | `NullPointerException`. |
| Construcción con colección vacía | Válida; el servicio arranca y todo `resolve(...)` falla con `ProviderNotAvailableException`. |
| Construcción con colección `null` | `NullPointerException`. |

## Contrato de cableado (infrastructure)

Incorporar un proveedor nuevo debe requerir **exactamente** dos cosas, y ninguna más:

1. Una clase `@Component` en `infrastructure/adapter/out/provider/` que implemente
   `NotificationSenderPort` y declare su `providerId()`.
2. Declarar ese `providerId` en el catálogo del canal (documento en Mongo o semilla de
   `application.yml`).

No debe requerir editar `core`, `DispatchNotificationService` ni `UseCaseConfig`. Esta propiedad es
verificable: el `@Bean` del registro recibe `List<NotificationSenderPort>`, que Spring completa con
todos los beans de ese tipo.

## Contrato del fallo por proveedor no resuelto

| Aspecto | Fallo del proveedor | Proveedor no resuelto |
|---------|--------------------|------------------------|
| Señal | `AttemptResult` devuelto por `send(...)` | `ProviderNotAvailableException` |
| Intento registrado | Sí, con su resultado | No |
| Estado de la notificación | Cambia (`DELIVERED`/`RECOVERABLE`/`FAILED`) | No cambia (sigue `PENDING`) |
| Escritura en Mongo | Sí | No |
| Ack del consumidor | Sí (procesamiento completo) | No; reintentos y luego DLQ |
| Rastro | Historial de intentos de la notificación | Mensaje en la DLQ: cuerpo = `notificationId`, header `x-exception-message` = causa con el `providerId` |
