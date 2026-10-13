# Quickstart: Reencolar notificaciones recuperables vencidas (HU2-030)

Guía de validación manual end-to-end — no reemplaza la prueba automatizada exigida en `tasks.md`, sirve para comprobar el flujo completo con la app corriendo de verdad.

## Prerrequisitos

- `docker compose up -d mongodb rabbitmq` (infraestructura local)
- Variables de entorno mínimas ya documentadas en `.env.example`

## Configuración para forzar el escenario

Arranca el servicio con estas dos variables, para que el proveedor simulado siempre falle de forma recuperable y el scheduler corra rápido (fácil de observar sin esperar 30s reales):

```bash
SIMULATED_PROVIDER_RESULT=RECOVERABLE_FAILURE \
NOTIFICATION_REQUEUE_INTERVAL_MS=5000 \
./mvnw -pl infrastructure spring-boot:run
```

## Pasos

1. **Aceptar una notificación**:
   ```bash
   curl -X POST http://localhost:8060/notifications \
     -H "X-Tenant-Id: tenant-1" \
     -H "Content-Type: application/json" \
     -d '{"externalId":"quickstart-1","channelType":"EMAIL","recipientId":"r1","recipientAddress":"a@b.com","subject":"Test","body":"Body","priority":"NORMAL"}'
   ```
   Guarda el `id` de la respuesta (202).

2. **Esperar el primer intento automático** (el dispatch inicial vía RabbitMQ es casi inmediato). Consulta el estado:
   ```bash
   curl http://localhost:8060/notifications/{id} -H "X-Tenant-Id: tenant-1"
   ```
   Debe mostrar `"status": "RECOVERABLE"` — el proveedor simulado configurado arriba siempre falla de forma recuperable.

3. **Esperar el tiempo de espera (backoff) + el intervalo del scheduler**. Con 1 intento recuperable, `RetryPolicy.nextBackoff(1)` = 30s (valor base) — espera ~35-40s en total para dar margen al scheduler (que corre cada 5s con la config de arriba).

4. **Consultar de nuevo el estado**:
   ```bash
   curl http://localhost:8060/notifications/{id} -H "X-Tenant-Id: tenant-1"
   ```
   Resultado esperado: la notificación pasó por un nuevo ciclo de despacho automáticamente — vuelve a `RECOVERABLE` (porque el proveedor simulado sigue configurado para fallar), pero con un **segundo** `DeliveryAttempt` registrado en su historial, con timestamp posterior al primero. Eso confirma que el reencolado automático ocurrió sin ninguna intervención manual.

## Qué prueba esto

Confirma en la app real (no solo en tests) los criterios de aceptación 1 y 3 del spec: una notificación vencida se reencola y se vuelve a intentar sin intervención de un operador, y el sistema no falla si no hay nada que reencolar entre ciclos.
