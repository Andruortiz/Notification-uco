# Phase 0 Research: Reencolar notificaciones recuperables vencidas (HU2-030)

Sin `[NEEDS CLARIFICATION]` pendientes en el Technical Context — esta sección documenta las decisiones técnicas ya tomadas (algunas en conversación previa a este spec, per la nota de proceso), no resuelve ambigüedades nuevas.

## 1. Mecanismo de disparo periódico

**Decision**: Spring `@Scheduled` (`fixedDelayString`) dentro del propio proceso del componente, sin infraestructura externa de scheduling.

**Rationale**: `@Scheduled`/`@EnableScheduling` ya están disponibles en `spring-context`, sin dependencia nueva. El intervalo es corto (segundos), y el componente ya corre de forma continua — no amerita un scheduler externo.

**Alternatives considered**:
- Kubernetes CronJob separado: agrega una pieza operativa adicional (imagen, manifiesto, coordinación) para un intervalo de segundos; no comparte el mismo proceso reactivo ni el pool de conexiones ya configurado.
- Quartz: más potente de lo que este caso necesita (un intervalo fijo simple, no scheduling complejo con cron expressions ni persistencia de jobs).

## 2. Política de espera (backoff) para el reencolado

**Decision**: reutilizar `RetryPolicy.nextBackoff(recoverableAttemptCount)`, la misma que ya usa `DispatchNotificationService` para el reintento inmediato — no se crea una política nueva.

**Rationale**: usar dos políticas de backoff distintas (una para el reintento inmediato en el flujo de despacho, otra para el reencolado periódico) generaría inconsistencia — la misma notificación esperaría tiempos distintos según quién la reintente. `nextBackoff` ya existe en el dominio y ya está probado; hasta ahora nadie lo invocaba, era la pieza faltante que esta historia conecta.

**Alternatives considered**: intervalo fijo simple sin crecimiento — descartado porque pierde la propiedad ya aprobada de "esperar más si el proveedor sigue fallando" (RF-14).

## 3. Alcance de la consulta — sin filtrar por tenant

**Decision**: `findByStatus(RECOVERABLE)` consulta de forma transversal, sin acotar por `tenantId`.

**Rationale**: es una operación de mantenimiento interna del propio componente, no una acción en nombre de un tenant específico — coherente con que el actor de esta historia es "el propio componente", no un sistema cliente.

**Alternatives considered**: ninguna real — no hay ambigüedad, es consistente con que las demás operaciones internas (no disparadas por un sistema cliente) tampoco se acotan por tenant.

## 4. Concurrencia entre réplicas — manejo del conflicto de versión por elemento

**Decision**: el `save()` ya usa el bloqueo optimista existente (`@Version`, ADR-0010 — `docs/adr/0010-concurrencia-replicas-cache.md`) — si dos réplicas compiten por la misma notificación, la segunda escritura falla. `NotificationMongoAdapter.save()` traduce el `OptimisticLockingFailureException` de Spring a una excepción de dominio propia, `NotificationVersionConflictException` (en `core/exception/`), y `RequeuePendingNotificationsService` maneja esa excepción de dominio **por notificación individual**, sin abortar el resto del lote:

```java
.flatMap(
    notification ->
        requeueAndPublish(notification)
            .onErrorResume(NotificationVersionConflictException.class, error -> Mono.empty()))
```

**Rationale**: sin este manejo, un conflicto de versión en una sola notificación cancelaría el `Flux` completo (comportamiento por defecto de Reactor ante un error no capturado en `flatMap`), dejando sin procesar el resto del lote en esa corrida del scheduler. Se captura específicamente `NotificationVersionConflictException` (un conflicto esperado y benigno bajo múltiples réplicas) — cualquier otro tipo de error no se silencia, para no ocultar un bug real (Principio VII, "Sin atajos"). La traducción de la excepción de Spring a una excepción de dominio ocurre en el **adaptador** (`infrastructure`), no en `core` — capturar `OptimisticLockingFailureException` directamente en el caso de uso habría violado el Principio I (core sin dependencias de framework). Este ajuste se descubrió necesario durante la implementación, no estaba previsto en la primera versión de este documento.

**Alternatives considered**: no manejar el error (descartado — cancela el lote completo por un conflicto benigno); capturar `OptimisticLockingFailureException` directamente en `core` (descartado — viola el Principio I); reintentar automáticamente el mismo elemento dentro de la misma corrida (descartado — innecesario, el próximo ciclo del scheduler ya lo vuelve a intentar).

## 5. Notificaciones `PENDING` huérfanas — el hallazgo del 2026-08-29

**Decision**: además de las `RECOVERABLE` vencidas, el reconciliador también recoge notificaciones `PENDING` con **cero intentos de entrega** y con `acceptedAt` más antiguo que un umbral corto configurable (`notification.scheduler.pending-orphan-threshold-ms`, por defecto 60s). Para esas, no se llama `Notification.requeue()` (ya están correctamente en `PENDING`) — solo se vuelve a invocar `NotificationEventPublisherPort.enqueueForDispatch(notification)`.

**Rationale**: este caso ya estaba documentado en la fila de backlog de HU2-030 desde el 2026-08-29, antes de que este spec se formalizara — se pasó por alto en la primera implementación de esta historia (que solo cubría `RECOVERABLE`). Si el proceso cae entre `NotificationRepository.save()` (que deja la notificación en `PENDING`) y `NotificationEventPublisherPort.enqueueForDispatch()`, la notificación queda persistida pero nunca se encola — violando RNF-05. La condición "cero intentos de entrega" distingue este caso del de una `PENDING` que llegó ahí por un reencolado ya exitoso desde `RECOVERABLE` (esa sí tiene al menos un intento registrado) — sin esa distinción, el reconciliador reencolaría notificaciones que ya están correctamente en curso. El umbral de tiempo evita tocar notificaciones recién aceptadas que el flujo normal de despacho todavía no tuvo tiempo de procesar.

**Alternatives considered**:
- **Un único método de puerto `findPendingForRetry(Instant before)` que combine ambos casos en una sola consulta Mongo** (sugerido en la nota original del backlog): descartado por ahora en favor de dos llamadas separadas a `findByStatus` — más simple de razonar y probar de forma independiente; se puede optimizar a una sola consulta después si el volumen lo justifica.
- **Detectar huérfanas por un campo `enqueuedAt` explícito** en vez de "cero intentos + `acceptedAt` viejo": descartado — habría requerido un campo nuevo en el agregado y en el documento de Mongo; la combinación de campos ya existentes es suficiente para distinguir el caso sin ampliar el modelo de datos.
- **No manejar errores de `enqueueForDispatch` por elemento**: descartado por la misma razón que en la sección 4 — un fallo transitorio de RabbitMQ en una notificación no debe cancelar el resto del lote.

**Nota**: esta sección se agregó el 2026-09-11, después de que la implementación inicial (solo `RECOVERABLE`) ya estaba escrita y a punto de subirse a PR — el hallazgo se encontró al revisar el backlog de Notion antes de abrir el PR, no durante el diseño original. Se corrigió antes de mergear, no después.
