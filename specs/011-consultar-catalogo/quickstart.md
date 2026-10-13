# Quickstart: Consultar el catálogo de canales y proveedores

Guía de validación. La verificación que cuenta es la automatizada (`ChannelCatalogQueryE2ETest` y
`./mvnw -B -ntp verify`); los pasos manuales de abajo sirven para ver la respuesta real, no sustituyen
ninguna prueba.

## Prerrequisitos

- Docker Desktop en marcha (`docker info` responde).
- En este equipo, `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` exportada antes de `./mvnw` (Testcontainers
  1.19.8 con Docker Engine 29).

## Pruebas automatizadas

```bash
export JAVA_TOOL_OPTIONS="-Dapi.version=1.44"

./mvnw -B -ntp -pl core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest=ChannelCatalogControllerTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest=ChannelCatalogQueryE2ETest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

Resultado esperado: todo en verde; cobertura ≥ 80 % líneas y ≥ 70 % ramas en `core` e `infrastructure`
(`<módulo>/target/site/jacoco/`).

## Validación manual (opcional)

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run
```

1. `curl -s -H "X-Tenant-Id: tenant-1" http://localhost:8060/channels` → `EMAIL`, `PUSH` y `SMS` en ese
   orden; en cada uno `simulated` en la posición 1 `ENABLED`, y el proveedor real (brevo, fcm, twilio) en
   la posición 2, `DISABLED` con un motivo que nombra la variable de entorno ausente si no está definida.
2. `curl -s -H "X-Tenant-Id: tenant-1" http://localhost:8060/providers` → `brevo`, `fcm`, `simulated`,
   `twilio`, con `simulated` en los tres canales.
3. Repetir 1 con `X-Tenant-Id: tenant-2` → cuerpo idéntico.
4. `curl -s -i http://localhost:8060/channels` (sin cabecera) → `400` con `{"message": ...}`.
5. Añadir un canal con un proveedor inexistente directamente en MongoDB
   (`db.channel_catalog.insertOne({_id: "WHATSAPP", providers: ["ghost"]})`), esperar el refresco (30 s
   por defecto) y repetir 1 y 2 → `WHATSAPP` con `ghost` en `MISSING_ADAPTER`. Borrar el documento al
   terminar.

La forma exacta de las respuestas está en [contracts/api-notificaciones-cambios.md](contracts/api-notificaciones-cambios.md)
y las reglas de cada campo en [data-model.md](data-model.md).
