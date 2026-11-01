# ADR-0003 — Reactivo no bloqueante como principio de diseño

**Estado**: Aceptado — reconfirmado en conversación el 2026-09-07
**Referenciado en**: `.specify/memory/constitution.md` ("Restricciones técnicas")

## Contexto

Elegir WebFlux (ADR-0002) no garantiza por sí solo que el código sea no bloqueante — una llamada
JDBC o de I/O síncrono colada en el pipeline reactivo degrada el rendimiento de todo el hilo
compartido. Java 21 (ADR-0001) también ofrece hilos virtuales como alternativa a la programación
reactiva para lograr alta concurrencia sin bloquear hilos del sistema operativo.

## Decisión

Todo el pipeline debe ser no bloqueante de punta a punta — ninguna operación bloqueante puede
ejecutarse en un hilo reactivo. Se evaluó reactivo (Reactor) contra hilos virtuales durante esta
sesión (2026-09-07) y se decidió **mantener reactivo** de punta a punta, sin introducir hilos
virtuales como alternativa parcial.

## Consecuencias

- El driver reactivo de MongoDB y el cliente reactivo de RabbitMQ ya están en uso — coherente con
  esta decisión.
- Cualquier adaptador de proveedor nuevo (`NotificationProvider`) debe usar un cliente HTTP
  reactivo, nunca uno bloqueante envuelto en `Mono.fromCallable` sin más.
- Un cliente bloqueante de terceros que no tenga alternativa reactiva debe aislarse explícitamente
  con `Schedulers.boundedElastic()`, nunca ejecutarse en el `event-loop` reactivo compartido.

## Alternativas consideradas

- **Hilos virtuales (Java 21) + Spring MVC clásico**: descartada — habría requerido reescribir el
  stack ya construido (WebFlux, drivers reactivos de Mongo y Rabbit) sin una ganancia clara, dado
  que el volumen de miles/cientos de miles de notificaciones ya está resuelto por el modelo
  reactivo actual. Los hilos virtuales simplifican el modelo de programación, pero no aportan nada
  que Reactor no resuelva ya aquí, y el costo de reescritura no se justificaba.
- **Modelo híbrido** (reactivo en la API, hilos virtuales en el despacho): descartada por
  complejidad — mantener dos modelos de concurrencia distintos en el mismo componente dificulta el
  razonamiento sobre dónde puede bloquearse un hilo.
