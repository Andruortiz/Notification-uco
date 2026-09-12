# Feature Specification: Reencolar notificaciones recuperables vencidas (HU2-030)

**Feature Branch**: `feature/HU2-030-reencolar-notificaciones-recoverable`

**Created**: 2026-09-11

**Status**: Draft

**Input**: User description: "Como componente, quiero reencolar periódicamente las notificaciones en estado RECOVERABLE cuyo tiempo de espera ya venció, para no perder envíos tras agotar los reintentos inmediatos (HU2-030)"

**Nota de proceso**: la implementación de esta historia se construyó en paralelo a la formalización de este spec, por decisión explícita del autor (Principio VII, "Sin atajos" — se documenta como excepción, no se oculta el orden real). El spec, el plan y las tareas se completan y aprueban aquí antes de que la historia se dé por cerrada.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reintento automático sin intervención manual (Priority: P1)

Como operador de la plataforma, quiero que las notificaciones que fallaron de forma recuperable se reintenten automáticamente después de un tiempo de espera creciente, para no depender de que alguien las reintente a mano y no perder notificaciones aceptadas.

**Why this priority**: Hoy es la única vía de recuperación real — sin esto, una notificación en `RECOVERABLE` queda estancada indefinidamente salvo que un operador intervenga manualmente. Es la pieza que cumple la garantía "ninguna notificación aceptada se pierde" (RNF-05) de forma automática, no como promesa.

**Independent Test**: Se puede probar guardando una notificación en estado `RECOVERABLE` con un intento previo cuyo tiempo de espera ya venció, ejecutando el proceso de reencolado, y verificando que la notificación vuelve a estado `PENDING` y queda disponible para un nuevo intento de despacho.

**Acceptance Scenarios**:

1. **Given** una notificación en estado `RECOVERABLE` cuyo tiempo de espera calculado ya venció, **When** corre el proceso de reencolado, **Then** la notificación pasa a `PENDING` y se encola para un nuevo intento de despacho.
2. **Given** una notificación en estado `RECOVERABLE` cuyo tiempo de espera todavía no vence, **When** corre el proceso de reencolado, **Then** la notificación permanece en `RECOVERABLE`, sin cambios.
3. **Given** no existen notificaciones en estado `RECOVERABLE`, **When** corre el proceso de reencolado, **Then** no ocurre ninguna acción y el proceso termina sin error.
4. **Given** una notificación quedó persistida en `PENDING` pero el proceso cayó antes de encolarla para despacho (huérfana — sin ningún intento de entrega registrado, y con más tiempo transcurrido desde su aceptación que un umbral corto), **When** corre el proceso de reencolado, **Then** se vuelve a encolar para despacho sin cambiar su estado (ya está correctamente en `PENDING`).
5. **Given** una notificación en `PENDING` fue aceptada hace muy poco tiempo (dentro del umbral corto) y todavía no tiene ningún intento, **When** corre el proceso de reencolado, **Then** no se toca — se asume que el flujo normal de despacho todavía no tuvo tiempo de procesarla.

---

### Edge Cases

- ¿Qué pasa si dos réplicas del componente ejecutan el reencolado casi al mismo tiempo sobre la misma notificación? El bloqueo optimista ya vigente (ADR-0010, `docs/adr/0010-concurrencia-replicas-cache.md`) debe evitar que ambas la reencolen dos veces sobre la misma versión — y si ocurre, la notificación en conflicto se reintenta en el siguiente ciclo sin bloquear el resto del lote.
- ¿Qué pasa con una notificación que ya agotó todos sus reintentos permitidos? No es candidata — esa notificación ya transicionó a `FAILED`, no permanece en `RECOVERABLE`.
- **Hallazgo (2026-08-29, ya documentado en el backlog antes de escribir este spec)**: si el proceso cae **entre** persistir la notificación en `PENDING` y encolarla para despacho, queda huérfana — persistida pero nunca despachada, violando RNF-05 ("ninguna notificación aceptada se pierde"). Este caso es distinto del `RECOVERABLE` vencido (ese sí tuvo al menos un intento) y necesita su propio criterio de detección: `PENDING` + cero intentos de entrega + más tiempo transcurrido que un umbral corto configurable.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE identificar todas las notificaciones que se encuentran en estado `RECOVERABLE`.
- **FR-002**: El sistema DEBE calcular, para cada notificación `RECOVERABLE`, si el tiempo de espera desde su último intento ya venció, usando la misma política de reintentos con espera creciente ya vigente en el despacho automático.
- **FR-003**: El sistema DEBE reencolar (transicionar a `PENDING`) únicamente las notificaciones cuyo tiempo de espera ya venció.
- **FR-004**: El sistema NO DEBE modificar notificaciones `RECOVERABLE` cuyo tiempo de espera todavía no venció.
- **FR-005**: El sistema DEBE ejecutar esta revisión de forma periódica y automática, sin que un operador la dispare manualmente.
- **FR-006**: Cada notificación reencolada DEBE quedar disponible para que el mecanismo de despacho existente intente entregarla de nuevo.
- **FR-007**: El intervalo entre revisiones DEBE poder configurarse sin necesidad de cambiar código.
- **FR-008**: El sistema DEBE identificar también las notificaciones en estado `PENDING` sin ningún intento de entrega registrado y con más tiempo transcurrido desde su aceptación que un umbral corto configurable — el caso de una notificación "huérfana" (persistida pero nunca encolada por una caída del proceso).
- **FR-009**: El sistema DEBE volver a encolar para despacho las notificaciones huérfanas identificadas por FR-008, sin transicionar su estado (ya están correctamente en `PENDING`).
- **FR-010**: El sistema NO DEBE tocar notificaciones `PENDING` recientemente aceptadas (dentro del umbral corto) ni notificaciones `PENDING` que ya tengan al menos un intento de entrega registrado (esas llegaron a `PENDING` por un reencolado ya exitoso, no están huérfanas).

### Key Entities

- **Notification**: el agregado existente — su estado (`RECOVERABLE`/`PENDING`), su historial de intentos y su fecha de aceptación determinan si es candidata a reencolar, por cualquiera de las dos vías (FR-001 a FR-003, o FR-008 a FR-010).
- **Intento de entrega (histórico)**: la marca de tiempo del último intento es la base para calcular si el tiempo de espera venció; su ausencia total (lista vacía) es la señal de que una notificación `PENDING` nunca fue despachada.
- **Política de reintentos**: la misma política de espera creciente ya usada en el despacho automático — no se diseña una nueva.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Toda notificación que pasa a estado recuperable recibe un nuevo intento automático dentro de la ventana de espera calculada, sin que un operador tenga que intervenir.
- **SC-002**: Ninguna notificación queda indefinidamente en estado recuperable mientras el componente opera con normalidad.
- **SC-003**: Ninguna notificación se reintenta antes de que su tiempo de espera calculado haya vencido.
- **SC-004**: Ninguna notificación aceptada queda huérfana (persistida sin encolar) de forma permanente — a lo sumo permanece huérfana durante el umbral corto configurado antes de que el reconciliador la recupere.

## Assumptions

- Se reutiliza la política de reintentos con espera creciente ya existente — no se diseña una nueva.
- El disparo periódico corre dentro del mismo proceso del componente, no como un job externo separado.
- Alertar a un operador cuando una notificación agota definitivamente sus reintentos queda fuera de alcance de esta historia — ya es responsabilidad del flujo existente que la transiciona a `FAILED`.
- El umbral corto para considerar una `PENDING` como huérfana (valor por defecto: 60 segundos) es independiente del intervalo de revisión del scheduler — un valor razonable es varias veces mayor a la latencia normal de despacho, para no reencolar notificaciones que simplemente están siendo procesadas en ese momento.
