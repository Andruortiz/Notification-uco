# Quickstart: Cola de mensajes muertos (DLQ) para el despacho

Guía de validación manual del flujo completo, una vez implementada la historia. No sustituye la prueba
E2E automatizada (`DeadLetterQueueE2ETest`) — es para confirmar visualmente el comportamiento descrito
en el Acceptance Scenario 1 y 3 del spec.

## Prerrequisitos

- Contenedores locales corriendo: `docker compose up -d mongodb rabbitmq` (ya usado en esta sesión para
  desarrollo local).
- El servicio corriendo con `./mvnw -pl infrastructure spring-boot:run`.
- RabbitMQ Management UI accesible en `http://localhost:15673` (usuario/clave de `.env`).

## Escenario: un mensaje envenenado termina en la cola de mensajes muertos

1. Abrir la Management UI y confirmar que existen las colas `notification.dispatch.queue` y
   `notification.dispatch.dlq.queue` (esta última creada por esta historia).
2. Forzar un fallo de procesamiento determinístico: publicar directamente en
   `notification.dispatch.exchange` (routing key `notification.dispatch`) un `notificationId` que no
   corresponda a ninguna notificación existente en Mongo (dispara `NotificationNotFoundException` en
   `DispatchNotificationUseCase.dispatch`, una excepción real de procesamiento, no un resultado
   `RECOVERABLE`/`FAILED`).
3. Observar en los logs del servicio 3 intentos de PROCESAMIENTO del mismo mensaje (o el valor
   configurado en `NOTIFICATION_DISPATCH_MAX_ATTEMPTS`) — en la Management UI el contador de
   entregas ("redelivered") del mensaje mostrará una entrega más que ese número: el mecanismo de
   Spring AMQP necesita una redelivery adicional para reconocer el agotamiento y mover el mensaje,
   sin volver a invocar el procesamiento en esa última entrega (ver research.md, Notas de
   implementación #2).
4. Confirmar en la Management UI que:
   - `notification.dispatch.queue` vuelve a 0 mensajes listos (no quedó reintentando indefinidamente).
   - `notification.dispatch.dlq.queue` tiene 1 mensaje nuevo.
5. Abrir ese mensaje en la Management UI (Get Message(s)) y confirmar que los headers incluyen
   `x-exception-message` con el texto de `NotificationNotFoundException` y que el body contiene el
   `notificationId` original — suficiente para que un operador identifique qué notificación falló y por
   qué, sin herramientas adicionales (FR-004, Acceptance Scenario 3).

## Escenario: un fallo transitorio que luego se resuelve nunca llega a la DLQ

1. Publicar un mensaje con un `notificationId` válido.
2. Confirmar en los logs que el mensaje se procesa con éxito en el primer intento.
3. Confirmar en la Management UI que `notification.dispatch.dlq.queue` sigue en 0 mensajes
   (Acceptance Scenario 2, SC-003).

## Configuración relevante

| Propiedad                                          | Variable de entorno                     | Default |
|-----------------------------------------------------|-------------------------------------------|---------|
| `notification.rabbit.dispatch.max-attempts`         | `NOTIFICATION_DISPATCH_MAX_ATTEMPTS`      | `3`     |
| `notification.rabbit.dlq.exchange`                  | (fijo, no externalizado — ver `RabbitTopologyProperties`) | `notification.dispatch.dlq.exchange` |
| `notification.rabbit.dlq.queue`                     | (ídem)                                     | `notification.dispatch.dlq.queue` |
