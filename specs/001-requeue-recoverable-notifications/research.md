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

**Decision**: el `save()` ya usa el bloqueo optimista existente (`@Version`, ADR-0017) — si dos réplicas compiten por la misma notificación, la segunda escritura falla con `OptimisticLockingFailureException`. Esta historia agrega manejo explícito de ese error **por notificación individual**, sin abortar el resto del lote:

```java
.flatMap(
    notification ->
        requeueAndPublish(notification)
            .onErrorResume(OptimisticLockingFailureException.class, error -> Mono.empty()))
```

**Rationale**: sin este manejo, un conflicto de versión en una sola notificación cancelaría el `Flux` completo (comportamiento por defecto de Reactor ante un error no capturado en `flatMap`), dejando sin procesar el resto del lote en esa corrida del scheduler — aunque no fueran conflictivas. Con el manejo por elemento, la notificación en conflicto simplemente se reintenta en la siguiente corrida (30s después por defecto), sin afectar a las demás. Se captura específicamente `OptimisticLockingFailureException` (un conflicto esperado y benigno bajo múltiples réplicas) — cualquier otro tipo de error no se silencia, para no ocultar un bug real (Principio VII, "Sin atajos").

**Alternatives considered**: no manejar el error (descartado — cancela el lote completo por un conflicto benigno); reintentar automáticamente el mismo elemento dentro de la misma corrida (descartado — innecesario, el próximo ciclo del scheduler ya lo vuelve a intentar).

**Nota para tasks.md**: esto actualiza el diseño de `RequeuePendingNotificationsService.requeuePending()` dado en conversación — al implementar, envolver `requeueAndPublish` con `.onErrorResume(OptimisticLockingFailureException.class, ...)` como se muestra arriba.
