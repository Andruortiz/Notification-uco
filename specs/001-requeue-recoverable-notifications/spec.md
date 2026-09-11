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

---

### Edge Cases

- ¿Qué pasa si dos réplicas del componente ejecutan el reencolado casi al mismo tiempo sobre la misma notificación? El bloqueo optimista ya vigente (ADR-0017) debe evitar que ambas la reencolen dos veces sobre la misma versión.
- ¿Qué pasa con una notificación que ya agotó todos sus reintentos permitidos? No es candidata — esa notificación ya transicionó a `FAILED`, no permanece en `RECOVERABLE`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE identificar todas las notificaciones que se encuentran en estado `RECOVERABLE`.
- **FR-002**: El sistema DEBE calcular, para cada notificación `RECOVERABLE`, si el tiempo de espera desde su último intento ya venció, usando la misma política de reintentos con espera creciente ya vigente en el despacho automático.
- **FR-003**: El sistema DEBE reencolar (transicionar a `PENDING`) únicamente las notificaciones cuyo tiempo de espera ya venció.
- **FR-004**: El sistema NO DEBE modificar notificaciones `RECOVERABLE` cuyo tiempo de espera todavía no venció.
- **FR-005**: El sistema DEBE ejecutar esta revisión de forma periódica y automática, sin que un operador la dispare manualmente.
- **FR-006**: Cada notificación reencolada DEBE quedar disponible para que el mecanismo de despacho existente intente entregarla de nuevo.
- **FR-007**: El intervalo entre revisiones DEBE poder configurarse sin necesidad de cambiar código.

### Key Entities

- **Notification**: el agregado existente — su estado (`RECOVERABLE`/`PENDING`) y su historial de intentos determinan si es candidata a reencolar.
- **Intento de entrega (histórico)**: la marca de tiempo del último intento es la base para calcular si el tiempo de espera venció.
- **Política de reintentos**: la misma política de espera creciente ya usada en el despacho automático — no se diseña una nueva.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Toda notificación que pasa a estado recuperable recibe un nuevo intento automático dentro de la ventana de espera calculada, sin que un operador tenga que intervenir.
- **SC-002**: Ninguna notificación queda indefinidamente en estado recuperable mientras el componente opera con normalidad.
- **SC-003**: Ninguna notificación se reintenta antes de que su tiempo de espera calculado haya vencido.

## Assumptions

- Se reutiliza la política de reintentos con espera creciente ya existente — no se diseña una nueva.
- El disparo periódico corre dentro del mismo proceso del componente, no como un job externo separado.
- Alertar a un operador cuando una notificación agota definitivamente sus reintentos queda fuera de alcance de esta historia — ya es responsabilidad del flujo existente que la transiciona a `FAILED`.
