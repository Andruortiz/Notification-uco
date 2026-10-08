# Contrato SUPUESTO del evento de cambio de configuración (Supuesto S-E0)

**Estado**: SUPUESTO. No es el contrato de SUP-02; el equipo del Componente de Parámetros no lo ha publicado.
Lo propone este servicio para poder construir y probar el adaptador de entrada por evento. Se ajusta o se
reemplaza cuando SUP-02 se cierre. Excepción del Principio VII: dueño, equipo de desarrollo del componente
(Notification-uco); fecha, 2026-11-15, la misma de SUP-02. No define ningún endpoint, por lo que
`api-notificaciones.yaml` no cambia (Principio II no aplica).

## Topología

| Elemento | Quién lo declara | Propiedad | Defecto |
|---|---|---|---|
| Exchange de Parámetros (durable, tipo configurable) | este servicio, de forma idempotente | `notification.parameters.events.exchange`, `.exchange-type` | exchange: ninguno (inactivo); tipo: `topic` |
| Routing key | n/a | `notification.parameters.events.routing-key` | ninguno (obligatoria si hay exchange) |
| Cola propia del servicio, durable, con `x-dead-letter-*` | este servicio | `notification.parameters.events.queue` | ninguno (obligatoria si hay exchange) |
| Exchange, routing key y cola de mensajes muertos | este servicio | `.dlq-exchange`, `.dlq-routing-key`, `.dlq-queue` | derivados del nombre de la cola |
| Intentos por mensaje ante fallo inesperado | n/a | `.max-attempts` | 3 |
| Concurrencia del consumidor | n/a | `.consumer-concurrency` | 1 |

Variables de entorno en `application.yml`: `NOTIFICATION_PARAMETERS_EVENTS_EXCHANGE`,
`NOTIFICATION_PARAMETERS_EVENTS_ROUTING_KEY`, `NOTIFICATION_PARAMETERS_EVENTS_QUEUE` y equivalentes; todas con
valor por defecto vacío. El broker y sus credenciales son los existentes (`RABBITMQ_*`); este contrato no añade
ninguno.

## Mensaje

Tipo de contenido JSON, publicado por Parámetros con la routing key anterior:

```json
{
  "version": 12,
  "values": {
    "dispatch.max-attempts": 5,
    "provider.brevo.timeout-ms": 8000,
    "requeue.interval-ms": 20000
  }
}
```

- Misma forma que la respuesta `200` de la fuente HTTP provisional de la 019 (`parametros-fuente-provisional.md`).
- `version`: entero creciente asignado por Parámetros (FR-013 de la 019); `values`: parcial o completo; las
  claves ausentes conservan el valor vigente; claves desconocidas rechazan el cambio completo.
- Sin cabecera obligatoria. Si llega una cabecera de correlación, se ignora (D8).

## Resultado por mensaje

| Caso | Resultado | Destino del mensaje | Evento de log |
|---|---|---|---|
| Aplicado o pendiente de reinicio | adoptado y persistido | ack | `CONFIG_APPLIED` |
| Versión menor o igual a la vigente | sin efecto | ack | `CONFIG_IGNORED` |
| Rechazado por validación | sin efecto | ack | `CONFIG_REJECTED` |
| Ilegible o sin `version` / `values` | sin efecto | DLQ, luego ack | error `PARAMETERS_EVENT_UNREADABLE` |
| Fallo inesperado | reintento hasta `max-attempts` | DLQ al agotar | `MESSAGE_ATTEMPT_FAILED` / `MESSAGE_ATTEMPTS_EXHAUSTED` |

## Requisitos de despliegue (no verificables por el servicio)

- Solo Parámetros debe poder publicar en el exchange; el usuario del servicio necesita permiso de lectura
  sobre su cola y de configuración sobre los elementos que declara. La identidad del emisor no se verifica en
  el mensaje (D7).

## Preguntas que dependen del otro equipo

1. Nombre y tipo del exchange, routing key y si cada réplica debe recibir el evento (cola por réplica) o
   basta una (cola compartida más sondeo).
2. Si el evento trae valores o solo avisa (Q1).
3. Broker compartido o propio de Parámetros (Q2).
4. Mecanismo de autenticación del emisor, si lo exigen (Q5).
5. Si habrá un mensaje de estado completo periódico, que haría innecesario parte del sondeo.
