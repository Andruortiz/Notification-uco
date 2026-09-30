# Quickstart: Enviar un lote de notificaciones por HTTP

La verificación de la historia la hacen las pruebas automatizadas; esta guía solo sirve para
reproducirla a mano.

## Pruebas

```bash
./mvnw -B -ntp -pl infrastructure -am test -Dtest='NotificationBatchControllerTest,NotificationBatchE2ETest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

`NotificationBatchE2ETest` necesita Docker (Testcontainers).

## Manual

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run
```

```bash
curl -i -X POST 'http://localhost:8060/notifications:sendBatch' \
  -H 'X-Tenant-Id: tenant-1' -H 'Content-Type: application/json' \
  -d '{"batchId":"lote-1","items":[
        {"externalId":"o-1","channelType":"EMAIL","recipientId":"r-1","recipientAddress":"a@example.com","body":"Hola","priority":"NORMAL"},
        {"externalId":"o-2","channelType":"FAX","recipientId":"r-2","recipientAddress":"b@example.com","body":"Hola","priority":"HIGH"}]}'
```

Esperado: `202`, `batchId` `lote-1`, `o-1` con `ACCEPTED` y `notificationId`, `o-2` con `REJECTED` y
`rejectionReason`. Repetir la misma solicitud devuelve `o-1` como `DUPLICATE` con el mismo
`notificationId`. `GET /notifications/{notificationId}` con el mismo tenant termina en `DELIVERED`.

Con `"items":[]` o un elemento sin `priority`: `400` y ninguna notificación nueva.
