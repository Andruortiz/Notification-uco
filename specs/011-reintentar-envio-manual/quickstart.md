# Quickstart: Reintentar envío manualmente (HU2-027 + HU2-028)

**Feature**: 011-reintentar-envio-manual | **Date**: 2026-09-26

Guía de validación. Todas las promesas medibles del spec (SC-001 a SC-005) tienen prueba automatizada; los
pasos manuales de abajo son solo una demostración.

## Pruebas automatizadas

```bash
# Unitarias de core (caso de uso, despacho con origen)
./mvnw -B -ntp -pl core test

# E2E del flujo completo (Docker encendido; ver JAVA_TOOL_OPTIONS en la guía del repo)
./mvnw -B -ntp -pl infrastructure -am test -Dtest=NotificationRetryE2ETest -Dsurefire.failIfNoSpecifiedTests=false

# Puerta completa
./mvnw -B -ntp verify
```

| Escenario | Prueba |
|---|---|
| FAILED → reintento → 202 PENDING → DELIVERED en ≤ 5 s, histórico AUTOMATIC + MANUAL | `NotificationRetryE2ETest` |
| Estado que no admite reintento → 400 sin efectos | `NotificationRetryE2ETest`, `RetryNotificationServiceTest` |
| Otro tenant → 404 idéntico a inexistente, sin efectos; el dueño sí puede | `NotificationRetryE2ETest`, `RetryNotificationServiceTest` |
| Dos reintentos simultáneos → un 202, un 400, un solo intento ante el proveedor | `NotificationRetryE2ETest`, `RetryNotificationServiceTest` |
| Encabezado de origen: escrito por el publicador, leído por el consumidor, ausente = AUTOMATIC | `NotificationRabbitPublisherTest`, `NotificationDispatchListenerTest` |
| El origen recibido llega al intento en las cuatro ramas de resultado | `DispatchNotificationServiceTest` |

## Demostración manual (opcional)

1. `docker compose up -d mongodb rabbitmq` y arrancar con `SIMULATED_PROVIDER_RESULT=PERMANENT_FAILURE`
   (`./mvnw -pl infrastructure spring-boot:run`).
2. `POST /notifications` con `X-Tenant-Id: tenant-1` y un correo; esperar `GET /notifications/{id}` →
   `FAILED`.
3. Reiniciar el servicio con `SIMULATED_PROVIDER_RESULT=ACCEPTED`.
4. `POST /notifications/{id}:retry` con `X-Tenant-Id: tenant-1` → `202`, `status: PENDING`.
5. `GET /notifications?recipientId=...` con el mismo tenant → `status: DELIVERED`, dos intentos: el primero
   `origin: AUTOMATIC`, `result: PERMANENT_FAILURE`; el segundo `origin: MANUAL`, `result: ACCEPTED`.
6. Repetir el paso 4 → `400` (ya está DELIVERED). Con `X-Tenant-Id: tenant-2` → `404`.
