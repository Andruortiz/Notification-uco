# Phase 0 Research: Catálogo de canales persistido en Mongo

## 1. Cómo se lee el catálogo en cada operación (evitar acoplar el camino de despacho a una llamada síncrona a Mongo)

**Decision**: `ChannelCatalogPort` se implementa sobre una **caché local en memoria** por réplica (un snapshot inmutable `Map<String, ChannelRoute>` detrás de un `AtomicReference`), poblada y refrescada por un componente separado que sí consulta Mongo. El camino de despacho (`SendNotificationService`, `DispatchNotificationService`) nunca toca Mongo directamente para resolver el catálogo — siempre lee del snapshot en memoria.

**Rationale**: es la aplicación directa de la restricción técnica ya definida en la constitución y en ADR-0010 ("caché de lecturas semi-estáticas... local por réplica... nunca un almacén compartido"). Separar "quién refresca" de "quién resuelve" hace que FR-005 (resiliencia ante caída de Mongo) sea gratis: si el refresco falla, el snapshot simplemente no se reemplaza, y el flujo de despacho ni se entera.

**Alternatives considered**:
- Consultar Mongo en cada `findActiveRoute`: descartado — convierte una dependencia hoy inexistente (el catálogo es en memoria, viene de `application.yml`) en una llamada de red síncrona en el camino crítico de cada envío/despacho, justo lo que ADR-0010 buscaba evitar.
- Cache-aside con TTL por entrada (ej. Caffeine con expiración): más potente de lo necesario — el catálogo completo es pequeño (unos pocos canales), no hay razón para evictar entradas individuales; un snapshot único reemplazado atómicamente es más simple y ya cumple el requisito.

## 2. Mecanismo de refresco — invalidación por evento vs. solo TTL

**Decision**: el refresco es **solo por sondeo periódico** (`@Scheduled(fixedDelayString = ...)`, mismo patrón ya usado por `PendingNotificationSchedulerAdapter` en HU2-030), sin invalidación por evento en esta historia.

**Rationale**: la invalidación por evento descrita en ADR-0010 apunta naturalmente a **Change Streams** de MongoDB, pero esos requieren que Mongo corra como *replica set* (o *sharded cluster*) — el `docker-compose.yml` local actual levanta una instancia standalone de `mongo:7`, sin `--replSet`. Introducir esa reconfiguración (y sus implicaciones operativas en producción) es un cambio de infraestructura que esta historia no necesita para cumplir su objetivo: el dato tolera "segundos de vencimiento" (ADR-0010, literal), y un sondeo cada 30s ya cumple eso. El TTL de respaldo pasa a ser, en esta historia, el único mecanismo — una decisión explícita y documentada (Principio VII), no un olvido.

**Alternatives considered**:
- MongoDB Change Streams: descartado por ahora — requiere replica set, fuera de alcance de esta historia; queda como mejora futura si el intervalo de sondeo alguna vez resulta insuficiente.
- Invalidación vía evento de aplicación (ej. publicar en RabbitMQ cuando alguien edita el catálogo): descartado — esta historia no expone ninguna vía de escritura (FR-008), no hay quién publique el evento.

## 3. Unicidad por canal

**Decision**: el documento Mongo usa el propio `channelType` como `_id` (ej. `"EMAIL"`), en lugar de un id generado más un índice único adicional sobre el canal.

**Rationale**: Mongo ya garantiza unicidad e indexación sobre `_id` de forma nativa — usar el canal como clave natural cumple FR-007 sin declarar un `@CompoundIndex` adicional (a diferencia de `NotificationDocument`, donde la clave natural es compuesta `(tenantId, externalId)` y sí amerita un índice separado del `_id` generado).

**Alternatives considered**: `_id` autogenerado + índice único sobre `channelType`: descartado — agrega un campo y un índice sin necesidad, cuando la clave natural ya es un identificador simple y estable.

## 4. Migración de los datos existentes en `application.yml`

**Decision**: un componente de arranque (`ApplicationRunner`, reactivo/no bloqueante) que, si la colección `channel_catalog` está vacía, migra las entradas ya definidas en `notification.catalog.channels` (leídas con la `ChannelCatalogProperties` ya existente) hacia la nueva colección. `ChannelCatalogProperties` se conserva como fuente de la semilla — deja de usarse como fuente de verdad en runtime (ese rol pasa a `MongoChannelCatalogAdapter`).

**Rationale**: es un conjunto de datos pequeño (unos pocos canales) y de una sola vez por ambiente — no justifica introducir un framework de migraciones nuevo (Mongock, Flyway) que hoy no existe en el proyecto. Reutilizar `ChannelCatalogProperties` evita reescribir el parseo de `application.yml` que ya existe y ya está probado.

**Alternatives considered**:
- Mongock/Flyway: descartado — dependencia nueva desproporcionada para un documento por canal, sin otro caso de uso en el proyecto que la justifique todavía.
- Script manual (`mongosh`) documentado en un README: descartado — no es repetible ni verificable en CI, y esta historia sí necesita una prueba E2E (Principio IV) que demuestre que la migración deja el comportamiento observable sin cambios (FR-006, SC-002).

## 5. Alcance de escritura y multi-tenant (decisiones ya tomadas con el usuario antes de escribir el spec)

**Decision**: sin API de administración del catálogo en esta historia (FR-008); catálogo global, no por tenant (FR-003).

**Rationale**: confirmado explícitamente por el usuario en `/speckit-specify` — ver Assumptions en `spec.md`. No se re-investigan aquí.
