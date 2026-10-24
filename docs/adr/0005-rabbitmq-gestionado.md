# ADR-0005 — RabbitMQ gestionado fuera de local

**Estado**: Aceptado
**Referenciado en**: `.specify/memory/constitution.md` ("Restricciones técnicas")

## Contexto

El componente usa RabbitMQ como broker para la cola de despacho y el exchange de eventos de
dominio. Mismo razonamiento operacional que ADR-0004, aplicado a RabbitMQ.

## Decisión

RabbitMQ gestionado externamente (por la plataforma) en todo ambiente distinto a desarrollo local.
En desarrollo local, `docker-compose` levanta una instancia propia.

## Consecuencias

- El componente nunca administra clustering, alta disponibilidad ni parcheo de RabbitMQ
  directamente en ambientes gestionados.
- Las credenciales de conexión vienen por variables de entorno (`RABBITMQ_USERNAME`,
  `RABBITMQ_PASSWORD`, etc.), coherente con RNF-09.
- En desarrollo local, `docker-compose up -d rabbitmq` es responsabilidad del propio desarrollador.

## Alternativas consideradas

- **RabbitMQ autoadministrado por el componente en todo ambiente**: descartada por el mismo
  razonamiento que ADR-0004 — duplicaría trabajo operativo centralizado.
- **Usar Kafka en vez de RabbitMQ**: no evaluada formalmente — RabbitMQ ya estaba decidido antes de
  este reinicio y el patrón de colas (no streaming de eventos de alto volumen) encaja con sus
  garantías de entrega, sin necesitar las capacidades de particionado/retención de Kafka.
