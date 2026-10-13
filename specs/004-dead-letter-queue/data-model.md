# Phase 1 Data Model: Cola de mensajes muertos (DLQ) para el despacho

Esta historia no introduce ninguna entidad de dominio en `core` ni ningún documento nuevo en MongoDB —
ver Constitution Check en [plan.md](./plan.md), Principio I. El único "dato" nuevo es la forma del
mensaje tal como queda en la cola de mensajes muertos, que vive enteramente en RabbitMQ.

## Mensaje en la cola de mensajes muertos

No es una entidad persistida; es el mismo payload del mensaje de despacho original (el `notificationId`
como cuerpo, igual que hoy en `notification.dispatch.queue`), republicado por `RepublishMessageRecoverer`
con headers adicionales que `RepublishMessageRecoverer` agrega automáticamente:

| Campo (header AMQP)     | Origen                      | Propósito (FR-004)                                   |
|--------------------------|------------------------------|--------------------------------------------------------|
| body                      | idéntico al mensaje original | identificar la notificación original (`notificationId`) |
| `x-exception-message`     | `RepublishMessageRecoverer`  | causa legible del fallo repetido                        |
| `x-exception-stacktrace`  | `RepublishMessageRecoverer`  | diagnóstico técnico completo                            |
| `x-original-exchange`     | `RepublishMessageRecoverer`  | trazabilidad — de qué exchange venía                    |
| `x-original-routingKey`   | `RepublishMessageRecoverer`  | trazabilidad — routing key original                     |

**Validación de FR-004**: un operador que abre la cola de mensajes muertos en la RabbitMQ Management UI
puede leer el `notificationId` en el cuerpo y la causa en `x-exception-message` sin necesitar ninguna
herramienta adicional — satisface "conservar en la cola de mensajes muertos suficiente información para
identificar la notificación original y la causa del fallo repetido" sin crear una entidad ni un
endpoint nuevo.

## Topología RabbitMQ nueva

No es un modelo de datos en el sentido tradicional, pero documenta el "esquema" de la infraestructura
de mensajería que esta historia agrega (ver [plan.md](./plan.md) → Project Structure para los archivos):

- **Exchange**: `notification.dispatch.dlq.exchange` (direct, igual estilo que
  `notification.dispatch.exchange`)
- **Queue**: `notification.dispatch.dlq.queue`
- **Routing key**: `notification.dispatch.dlq`
- **Binding**: `notification.dispatch.dlq.queue` ↔ `notification.dispatch.dlq.exchange` con la routing
  key anterior — mismo patrón que el binding existente de `notification.dispatch.queue`

Ningún dato de esta topología requiere migración: son recursos declarativos que Spring AMQP crea al
arrancar (`RabbitAdmin`), igual que la topología de despacho existente.
