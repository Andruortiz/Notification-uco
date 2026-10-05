# Quickstart: Autenticación interina del servicio

**Feature**: 015-autenticacion-interina | **Date**: 2026-09-30

## Prerrequisitos

- `docker compose up -d mongodb rabbitmq`
- Variable de entorno `AUTH_JWT_HS256_SECRET` exportada (ver `.env.example` para un valor de ejemplo
  solo-desarrollo; nunca uno real en un entorno compartido).
- `./mvnw -pl infrastructure spring-boot:run`

## 1. Confirmar el rechazo sin token (fail-closed)

```bash
curl -i -X POST http://localhost:8060/notifications \
  -H "Content-Type: application/json" \
  -d '{"externalId":"x-1","channelType":"EMAIL","recipientId":"r-1","recipientAddress":"a@b.com","subject":"s","body":"b","priority":"NORMAL","attachments":[]}'
```

Resultado esperado: `401`, sin invocar el caso de uso de envío (no aparece la notificación en
`GET /notifications/{id}` para ningún id).

## 2. Generar un token de prueba para un tenant y rol dados

Usando `LocalJwtTokenIssuer` (mismo mecanismo que usan los tests de contrato/E2E) desde una clase de
soporte de pruebas, o el equivalente documentado en el README de desarrollo una vez implementado:

```java
String token = new LocalJwtTokenIssuer(secret)
    .issue(TenantId.of("tenant-a"), Role.CLIENTE, "sistema-cliente-demo", Duration.ofHours(1));
```

## 3. Enviar una notificación con un token `CLIENTE` válido

```bash
curl -i -X POST http://localhost:8060/notifications \
  -H "Authorization: Bearer $TOKEN_TENANT_A_CLIENTE" \
  -H "Content-Type: application/json" \
  -d '{"externalId":"x-1","channelType":"EMAIL","recipientId":"r-1","recipientAddress":"a@b.com","subject":"s","body":"b","priority":"NORMAL","attachments":[]}'
```

Resultado esperado: `202`, y la notificación queda registrada bajo `tenant-a` (el del token),
independientemente de si se envía o no un header `X-Tenant-Id` distinto en la misma solicitud.

## 4. Confirmar el rechazo por rol insuficiente (403)

```bash
curl -i http://localhost:8060/notifications \
  -H "Authorization: Bearer $TOKEN_TENANT_A_CLIENTE"
```

Resultado esperado: `403` — `GET /notifications` (histórico/trazabilidad) exige rol `OPERADOR` o
superior; un token `CLIENTE` no alcanza.

```bash
curl -i http://localhost:8060/notifications \
  -H "Authorization: Bearer $TOKEN_TENANT_A_OPERADOR"
```

Resultado esperado: `200`.

## 5. Confirmar que el tenant del token nunca se mezcla entre tenants

Repetir el paso 3 con un token de `tenant-b` y confirmar, vía `GET /notifications/{id}` con el token de
`tenant-a`, que la notificación de `tenant-b` no es visible (comportamiento ya garantizado por el
aislamiento multi-tenant existente, ahora alimentado por el token).

## 6. SSE con ticket de un solo uso

El parámetro `access_token` ya no se acepta en `GET /notifications:subscribe`. Quien no pueda enviar
cabeceras (`EventSource` nativo) pide primero un ticket con su credencial normal:

```bash
TICKET=$(curl -s -X POST -H "Authorization: Bearer $TOKEN_TENANT_A_CLIENTE"   "http://localhost:8060/notifications:subscribeTicket" | jq -r .ticket)
curl -N "http://localhost:8060/notifications:subscribe?ticket=$TICKET"
```

Resultado esperado: `200`, stream `text/event-stream` con la foto inicial de las notificaciones de
`tenant-a` y luego los eventos en vivo. El ticket vale 30 segundos y se consume al usarlo: repetir el
segundo `curl` con el mismo ticket, o con `?access_token=...`, responde `401`. La cabecera
`Authorization`, si llega, tiene prioridad sobre `ticket`.

## Validación automatizada equivalente

Cada paso de este quickstart tiene una prueba automatizada correspondiente en
`AuthenticationInterinaE2ETest` y `SubscriptionTicketE2ETest` (ver `tasks.md`) — este documento es una guía de verificación manual,
no sustituye esa prueba.
