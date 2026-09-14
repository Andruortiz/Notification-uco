# Feature Specification: Cola de mensajes muertos (DLQ) para el despacho

**Feature Branch**: `004-dead-letter-queue`

**Created**: 2026-09-13

**Status**: Draft

**Input**: User description: "Como componente, quiero una cola de mensajes muertos para los que fallan repetidamente, para no reintentar infinitamente ni perder silenciosamente una notificación aceptada (RNF-05)."

## Clarifications

### Session 2026-09-13

- Q: Cuando un mensaje agota sus intentos y se mueve a la cola de mensajes muertos, ¿necesita el sistema avisar activamente a un operador (alerta), o basta con que el mensaje quede visible ahí para que alguien lo revise cuando quiera? → A: Solo visibilidad pasiva — el mensaje queda en la cola para quien lo revise (ej. RabbitMQ Management UI, ya disponible); no se construye ningún canal de alerta nuevo.
- Q: ¿Cuántos intentos de procesamiento debería permitir el sistema antes de mover un mensaje a la cola de mensajes muertos, por defecto? → A: 3 intentos — más agresivo que el número que usa `RetryPolicy` para dar de baja una notificación a `FAILED` (5), a propósito: un fallo de *procesamiento* repetido es más probable que sea un bug determinístico que uno de negocio, así que detectarlo antes tiene más valor que tolerar unos pocos reintentos de más.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - No perder una notificación cuyo procesamiento falla repetidamente (Priority: P1)

Como componente, quiero que un mensaje de despacho cuyo procesamiento falla de forma repetida (un error al ejecutarlo, no un resultado normal del envío al proveedor) se mueva a una cola separada después de un número limitado de intentos, para que la notificación aceptada quede visible para investigación en vez de perderse en silencio o reintentarse para siempre.

**Why this priority**: Es la garantía central de RNF-05 aplicada al único punto ciego que queda hoy en el camino de despacho — sin esto, un mensaje "envenenado" consume recursos indefinidamente y nadie se entera de que existe.

**Independent Test**: Forzar una excepción determinística en el procesamiento de un mensaje de despacho (por ejemplo, un dato que el componente no puede interpretar) y confirmar que, tras el número de intentos configurado, el mensaje aparece en la cola de mensajes muertos en vez de seguir reintentándose indefinidamente en la cola original.

**Acceptance Scenarios**:

1. **Given** un mensaje de despacho falla su procesamiento de forma determinística, **When** se reintenta el número máximo de veces configurado, **Then** el mensaje se mueve a la cola de mensajes muertos y deja de reintentarse en la cola original.
2. **Given** un mensaje de despacho falla una vez por una causa transitoria y luego se procesa con éxito, **When** ocurre el reintento exitoso, **Then** el mensaje nunca llega a la cola de mensajes muertos.
3. **Given** un mensaje llega a la cola de mensajes muertos, **When** un operador lo revisa, **Then** puede identificar la notificación original y la causa del fallo repetido.

---

### User Story 2 - Configurar el número de intentos antes de dar por muerto un mensaje (Priority: P2)

Como equipo de desarrollo, quiero configurar cuántos intentos de procesamiento se permiten antes de mover un mensaje a la cola de mensajes muertos, sin recompilar, para poder ajustar ese número según lo que la operación real muestre.

**Why this priority**: Evita hardcodear un número arbitrario, pero no bloquea el valor central de la historia 1 — el mecanismo de dead-letter funciona igual con cualquier número.

**Independent Test**: Cambiar la variable de entorno del número máximo de intentos y confirmar que el comportamiento respeta el nuevo valor.

**Acceptance Scenarios**:

1. **Given** un número de intentos configurado, **When** un mensaje falla exactamente esa cantidad de veces, **Then** se mueve a la cola de mensajes muertos, ni antes ni después.

---

### Edge Cases

- ¿Qué pasa si el propio movimiento de un mensaje a la cola de mensajes muertos falla (ej. RabbitMQ no disponible en ese instante)? El sistema no debe perder el mensaje original en silencio — debe seguir intentando el movimiento, nunca descartarlo.
- ¿Qué pasa con un mensaje que falla por un motivo transitorio del PROVEEDOR externo (`RECOVERABLE`), no por un error de procesamiento? Ese caso ya está cubierto por el mecanismo de reintento con espera creciente existente (`RetryPolicy`) — la cola de mensajes muertos es para fallos de PROCESAMIENTO del propio componente, no para resultados de negocio normales del despacho. Esta historia no cambia nada de ese camino ya construido.
- ¿Qué pasa si dos réplicas procesan mensajes que fallan al mismo tiempo? Cada mensaje cuenta sus propios intentos de forma independiente — no hay conteo compartido entre réplicas en esta historia (ver Assumptions).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE distinguir entre un fallo de envío hacia el proveedor externo (que ya se maneja como `RECOVERABLE`/`FAILED` del dominio) y un fallo en el PROCESAMIENTO del propio mensaje de despacho (una excepción no esperada al ejecutar el caso de uso).
- **FR-002**: El sistema DEBE reintentar el procesamiento de un mensaje que falla por un número limitado y configurable de veces (3 por defecto) antes de considerarlo definitivamente fallido a nivel de procesamiento.
- **FR-003**: El sistema DEBE mover un mensaje que agotó sus intentos de procesamiento a una cola de mensajes muertos separada, en vez de perderlo o reintentarlo indefinidamente.
- **FR-004**: El sistema DEBE conservar en la cola de mensajes muertos suficiente información para identificar la notificación original y la causa del fallo repetido.
- **FR-005**: El número de intentos antes de mover un mensaje a la cola de mensajes muertos DEBE ser configurable sin recompilar, con 3 como valor por defecto.
- **FR-006**: El sistema NO DEBE mover a la cola de mensajes muertos un mensaje que se termina resolviendo con éxito, sin importar cuántos intentos previos haya tenido.
- **FR-007**: El sistema DEBE hacer visible el contenido de la cola de mensajes muertos para consulta pasiva por un operador (ej. mediante la interfaz de administración de la cola de mensajes ya disponible); NO DEBE requerirse ningún canal de alerta activa nuevo — queda fuera de alcance de esta historia.

### Key Entities

- **Mensaje de despacho**: el mensaje de la cola de RabbitMQ que dispara el despacho de una notificación (ya existente); esta historia define qué pasa cuando su procesamiento falla repetidamente.
- **Cola de mensajes muertos**: destino nuevo para los mensajes de despacho que agotaron sus intentos de procesamiento, con suficiente información para diagnosticar la causa.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Ningún mensaje de despacho se reintenta más allá del número de intentos configurado antes de moverse a la cola de mensajes muertos.
- **SC-002**: Ninguna notificación aceptada desaparece sin dejar rastro cuando su procesamiento falla repetidamente — siempre queda visible, ya sea en el flujo normal o en la cola de mensajes muertos.
- **SC-003**: Un mensaje que falla una vez y luego se procesa con éxito nunca llega a la cola de mensajes muertos.

## Assumptions

- Esta historia cubre fallos de PROCESAMIENTO del mensaje de despacho (una excepción real al ejecutar el caso de uso) — no los fallos de negocio ya cubiertos por `RECOVERABLE`/`FAILED` del dominio, que siguen su propio camino ya construido, sin cambios.
- No se incluye una interfaz de administración *propia* para revisar o reprocesar mensajes desde la cola de mensajes muertos — la interfaz de administración de la cola de mensajes ya existente (disponible en el entorno vía `docker-compose.yml`) es suficiente para la visibilidad pasiva que exige esta historia; reprocesar manualmente desde ahí queda para una historia futura.
- No se requiere ningún mecanismo de alerta activa (email, Slack, etc.) cuando un mensaje llega a la cola de mensajes muertos — sería adelantar la infraestructura de observabilidad/alertas, que sigue sin construirse en el proyecto.
- El conteo de intentos es por mensaje individual — no hay coordinación de conteo entre réplicas concurrentes que puedan procesar mensajes relacionados al mismo tiempo.
