# Quickstart: Buscar notificaciones por filtros

Guía de validación manual una vez implementada la historia. No sustituye la prueba E2E automatizada
(`NotificationControllerSearchE2ETest`) — es para confirmar visualmente el comportamiento contra un
backend real.

## Prerrequisitos

- `docker compose up -d mongodb rabbitmq` (mismo entorno local ya usado en historias anteriores).
- El servicio corriendo con `./mvnw -pl infrastructure spring-boot:run`.

## Escenario: buscar combinando filtros y ver el historial completo

1. Aceptar 3-4 notificaciones distintas vía `POST /notifications` — variando `recipientId`,
   `channelType`, y dejando que al menos una termine en `FAILED` (forzar el fallo del proveedor
   simulado vía `SIMULATED_PROVIDER_RESULT` si hace falta).
2. `GET /notifications` sin ningún filtro (solo `X-Tenant-Id`) — confirmar que devuelve todas las
   notificaciones del tenant, la más reciente primero, cada una con su `deliveryAttempts` completo.
3. `GET /notifications?status=FAILED` — confirmar que solo aparecen las que están en ese estado.
4. `GET /notifications?recipientId=<uno de los usados>&channelType=EMAIL` — confirmar que la
   combinación de ambos filtros (AND) reduce el resultado correctamente.
5. `GET /notifications?from=<ayer>&to=<mañana>` — confirmar que el rango de fechas incluye las
   notificaciones aceptadas hoy.

## Escenario: paginación

1. Aceptar más de `limit` notificaciones (o usar `limit=1` para forzarlo con pocas).
2. `GET /notifications?limit=1` — confirmar que `items` tiene 1 elemento y `hasNext: true`.
3. `GET /notifications?limit=1&offset=1` — confirmar que devuelve el siguiente elemento (el segundo
   más reciente), no el mismo.
4. Pedir una página más allá del final — confirmar `items: []` y `hasNext: false`, no un error.

## Escenario: validación de entrada

1. `GET /notifications?from=2026-09-20T00:00:00Z&to=2026-09-01T00:00:00Z` (from posterior a to) —
   confirmar `400` con un mensaje que distinga esto de "no hay resultados".
2. `GET /notifications?limit=0` y `GET /notifications?limit=500` — confirmar `400` en ambos casos.
3. `GET /notifications?offset=-1` — confirmar `400`.

## Aislamiento por tenant

1. Aceptar notificaciones bajo dos valores distintos de `X-Tenant-Id`.
2. `GET /notifications` con cada tenant — confirmar que ninguno ve las notificaciones del otro, sin
   importar los filtros usados (SC-002).
