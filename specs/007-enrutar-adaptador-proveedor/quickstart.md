# Quickstart: validar el enrutamiento por providerId

**Feature**: `007-enrutar-adaptador-proveedor` | **Date**: 2026-09-21

Guía de validación. La verificación vinculante es automatizada (ver "Pruebas que deciden"); lo manual
es para inspeccionar el comportamiento con el servicio arriba.

## Prerrequisitos

- Docker en marcha (las pruebas E2E y de integración usan Testcontainers).
- Dependencias locales: `docker compose up -d mongodb rabbitmq`.
- En este equipo, exportar `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` antes de `./mvnw` para que
  Testcontainers funcione con el Docker Engine instalado. No usar `-DargLine`: reemplaza el agente de
  JaCoCo y la cobertura sale falsamente baja.

## Pruebas que deciden (Principio IV)

```bash
# Unitarias del registro y del despacho (core)
./mvnw -B -ntp -pl core test

# E2E de enrutamiento: dos adaptadores falsos + el simulado conviviendo, y proveedor no resuelto
./mvnw -B -ntp -pl infrastructure -am test -Dtest=ProviderRoutingE2ETest -Dsurefire.failIfNoSpecifiedTests=false

# Arquitectura hexagonal y modularidad (criterio de aceptación explícito de la historia)
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false

# Puerta completa, igual que CI (incluye cobertura ≥80 % líneas / ≥70 % ramas)
./mvnw -B -ntp verify
```

Resultado esperado de `ProviderRoutingE2ETest`:

1. Con `EMAIL → [fake-a]`, la notificación aceptada termina `DELIVERED` con `providerId = fake-a`;
   `fake-a` recibió una notificación y `fake-b` ninguna.
2. Con `SMS → [fantasma]` (sin adaptador), el mensaje termina en la cola de mensajes muertos con
   cuerpo igual al `notificationId` y el header de causa nombrando `fantasma`; la notificación sigue
   `PENDING` y sin intentos.

## Comprobación manual con el servicio arriba (opcional)

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run     # puerto 8060
```

### 1. El camino feliz sigue funcionando con el simulado

```bash
curl -i -X POST http://localhost:8060/notifications \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-1' \
  -d '{"externalId":"quickstart-1","channelType":"EMAIL","recipientId":"r-1",
       "recipientAddress":"alice@example.com","subject":"S","body":"B","priority":"NORMAL"}'

curl -s http://localhost:8060/notifications/<notificationId> -H 'X-Tenant-Id: tenant-1'
```

Esperado: `status = DELIVERED`, `providerId = simulated` — el mismo valor que
`notification.catalog.channels.EMAIL.providers` siembra en `application.yml`.

### 2. Un proveedor que nadie atiende deja rastro y no pierde la notificación

Apuntar un canal a un identificador inexistente en la colección del catálogo:

```bash
docker exec -it <contenedor-mongo> mongosh notification \
  --eval 'db.channelCatalog.updateOne({_id:"EMAIL"},{$set:{providers:["fantasma"]}})'
```

Esperar el refresco del catálogo (por defecto 30 s, `notification.catalog.refresh-interval-ms`) y
repetir el `POST` con otro `externalId`.

Esperado:

- La API responde `202 Accepted` (la aceptación no depende del adaptador).
- `GET /notifications/{id}` sigue en `PENDING`, con `deliveryAttempts` vacío — **no** aparece un
  intento fallido: así se distingue de un rechazo del proveedor.
- El mensaje aparece en `notification.dispatch.dlq.queue` (consola de RabbitMQ en
  http://localhost:15673) con el header `x-exception-message` nombrando `fantasma`.
- Al restaurar `providers:["simulated"]` y reencolar, la notificación se entrega: el error de
  configuración no la quemó.

### 3. Dos adaptadores con el mismo identificador no arrancan

Es una comprobación de código, no de operación: si dos beans declaran el mismo `providerId`, el
arranque falla con un `IllegalArgumentException` que nombra el identificador duplicado. La cubre
`NotificationSenderRegistryTest`; no hay forma de provocarlo por configuración.

## Después de tocar código Java

```bash
./mvnw -B -ntp spotless:apply     # normaliza formato y CRLF
```
