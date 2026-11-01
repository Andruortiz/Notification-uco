# ADR-0010 — Concurrencia entre réplicas y estrategia de caché

**Estado**: Aceptado (2026-09-07)
**Referenciado en**: `.specify/memory/constitution.md` ("Restricciones técnicas"), `NotificationDocument.java` (`@Version`, índice único `tenant_external_unique`), `RequeuePendingNotificationsService` (HU2-030)

## Contexto

Con múltiples réplicas del contenedor corriendo a la vez, dos preocupaciones de concurrencia
distintas necesitaban una decisión explícita: (1) qué pasa cuando dos réplicas intentan mutar el
mismo agregado `Notification` al mismo tiempo, y (2) qué pasa con datos que varias réplicas leen o
comparten (catálogo de canales, límites de tasa por proveedor).

## Decisión

### Parte 1 — Concurrencia entre réplicas

- Toda mutación de un agregado ya persistido usa **bloqueo optimista** (campo `version`,
  incrementado en cada escritura condicionada a su valor anterior) — nunca bloqueo pesimista, porque
  el flujo de despacho mantiene una llamada externa lenta al proveedor entre leer y guardar.
- La idempotencia de aceptación se garantiza con una **restricción única `(tenantId, externalId)` a
  nivel de base de datos**, no solo con una verificación previa en la aplicación.
- Cualquier operación de "tomar" un recurso compartido entre réplicas (ej. el scheduler de
  reintentos, HU2-030) se implementa como una **actualización condicionada atómica**
  (`findAndModify`/`UPDATE ... WHERE ... RETURNING`), nunca como leer-luego-escribir.

### Parte 2 — Estrategia de caché

- Caché de lecturas semi-estáticas (catálogo de canales, claves de validación de token) es **local
  por réplica con invalidación por evento + TTL de respaldo** — nunca un almacén compartido, porque
  el dato es pequeño y tolera segundos de vencimiento.
- Estado que debe ser **estrictamente consistente** entre réplicas (ej. un contador de límite de
  tasa por proveedor) sí requiere un **almacén compartido real** (ej. Redis + Bucket4j) — una caché
  local por réplica permitiría que cada una agote el límite completo por su cuenta.

## Consecuencias

- `NotificationMongoAdapter.save()` traduce el conflicto de versión de Spring
  (`OptimisticLockingFailureException`) a una excepción de dominio propia
  (`NotificationVersionConflictException`) en el adaptador, no en `core` — para no violar el
  Principio I (arquitectura hexagonal). Esta traducción se descubrió como necesaria al implementar
  HU2-030, no estaba prevista en el diseño original de este ADR.
- El límite de tasa por proveedor (backlog, Fase 5) queda pendiente de implementar con Redis +
  Bucket4j — el mecanismo ya está decidido aquí, falta construirlo.

## Alternativas consideradas

- **Bloqueo pesimista** para mutaciones del agregado: descartado — mantendría un lock de base de
  datos abierto durante toda la llamada externa al proveedor, que puede tardar segundos.
- **Verificación de duplicados solo en la aplicación** (sin restricción a nivel de base de datos):
  descartada — no es segura bajo condiciones de carrera entre réplicas.
- **Almacén compartido para todo tipo de caché** (incluyendo catálogo/tokens): descartado por
  costo/latencia innecesarios para datos que toleran unos segundos de inconsistencia.
