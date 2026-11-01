# ADR-0002 — Spring Boot 3 sobre WebFlux/Reactor

**Estado**: Reemplazado por ADR-0012 (2026-10-16)
**Referenciado en**: `.specify/memory/constitution.md` ("Restricciones técnicas")

## Contexto

El componente debe procesar un volumen alto de notificaciones (miles a cientos de miles), con
operaciones de E/S intensivas hacia MongoDB, RabbitMQ y proveedores externos vía HTTP.

## Decisión

Usar Spring Boot 3 sobre WebFlux/Reactor como stack del componente, en vez de Spring MVC clásico
(servlet, bloqueante).

## Consecuencias

- Todo el pipeline usa `Mono`/`Flux` de punta a punta: controllers, casos de uso, repositorio, cliente de proveedor.
- Persistencia con Spring Data **Reactive** MongoDB (`ReactiveMongoTemplate`), no el driver bloqueante.
- El cliente de RabbitMQ y el listener de despacho también son reactivos (`NotificationRabbitPublisher`, `NotificationDispatchListener`).
- Las pruebas usan `StepVerifier` en vez de aserciones síncronas directas sobre el resultado.
- Ningún adaptador puede usar una librería bloqueante sin envolverla explícitamente (`Mono.fromCallable` + `Schedulers.boundedElastic()`) — ver ADR-0003.

## Alternativas consideradas

- **Spring MVC clásico** (bloqueante, un hilo por request): descartada — no escala a miles/cientos de miles de notificaciones sin un pool de hilos del sistema operativo desproporcionadamente grande.
- **Hilos virtuales (Java 21) + Spring MVC**: evaluada y descartada esta sesión (2026-09-07) — ver `ADR-0003` para el razonamiento específico de esa comparación.
