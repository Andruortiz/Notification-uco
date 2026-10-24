# Feature Specification: Failover entre proveedores del canal

**Feature Branch**: `feature/HU2-115-failover-proveedores`

**Created**: 2026-10-24

**Status**: Draft

**Input**: Como AP quiero que, si un proveedor falla, el servicio pase al siguiente automáticamente
(RF-12) para no perder envíos. Aplica sobre la ruta ordenada del canal e incluye circuit breaker; la
notificación registra qué proveedor entregó y cuáles fallaron; `simulated` solo está habilitado en
desarrollo.

Base: [007](../007-enrutar-adaptador-proveedor/spec.md) enruta por el primer proveedor del catálogo y
dejó el failover fuera de alcance; este spec lo completa.

## Clarifications

### Session 2026-10-24

Decididas por quien encarga la historia:

- El failover ocurre solo ante `RECOVERABLE_FAILURE` y siempre dentro del mismo canal.
- `simulated` se deshabilita por perfil: habilitado solo en local y pruebas.
- El segundo proveedor real se evalúa aparte; mientras tanto el failover se prueba con `simulated`.

Resueltas por el autor (sujetas a aprobación):

- Q: ¿Un `PERMANENT_FAILURE` conmuta? -> A: No. Es un rechazo definitivo del contenido o destinatario;
  otro proveedor no lo corregiría y duplicaría el rechazo. Se conserva el comportamiento de hoy.
- Q: ¿Un proveedor presente en la ruta pero deshabilitado (sin credenciales, o `simulated` fuera de
  desarrollo) se intenta? -> A: No. Se omite sin registrar intento y se pasa al siguiente. Si la ruta
  completa queda sin proveedor habilitado, se mantiene el fallo explícito de configuración de 007.
- Q: ¿Un proveedor con circuito abierto se intenta? -> A: No. Se omite sin llamarlo y sin registrar
  intento (no hubo envío); si todos los candidatos están abiertos o deshabilitados, el despacho falla
  sin cambiar el estado y el mensaje sigue el camino de reintento/DLQ existente.
- Q: ¿Los intentos de conmutación gastan el presupuesto de reintentos de la política? -> A: No. La
  política cuenta rondas de despacho, no proveedores probados dentro de una ronda.
- Q: ¿Qué abre el circuito? -> A: fallos recuperables y errores de transporte del proveedor; los
  `PERMANENT_FAILURE` no cuentan porque no indican caída del proveedor.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Conmutar al siguiente proveedor ante fallo recuperable (Priority: P1)

Como AP quiero que un fallo recuperable del proveedor preferente haga que el servicio pruebe el
siguiente de la ruta del canal, para no perder el envío.

**Why this priority**: Es el núcleo de RF-12; sin esto una caída de proveedor detiene el canal.

**Independent Test**: Ruta `[A, B]` con A devolviendo recuperable y B aceptando: la notificación queda
entregada por B en un solo despacho.

**Acceptance Scenarios**:

1. **Given** una ruta `[A, B]` y A responde `RECOVERABLE_FAILURE`, **When** se despacha, **Then** se
   envía por B en el mismo despacho y, si B acepta, la notificación queda `DELIVERED` por B.
2. **Given** A y B responden `RECOVERABLE_FAILURE`, **When** se despacha, **Then** la notificación
   queda `RECOVERABLE` (o `FAILED` si la política se agota) como hoy, con ambos fallos registrados.
3. **Given** A responde `PERMANENT_FAILURE`, **When** se despacha, **Then** B no recibe nada y la
   notificación queda `FAILED` como hoy.
4. **Given** una ruta con un único proveedor habilitado que falla de forma recuperable, **When** se
   despacha, **Then** el comportamiento es idéntico al de hoy.
5. **Given** A acepta, **When** se despacha, **Then** B no se invoca.
6. **Given** un proveedor del canal EMAIL falla, **When** se conmuta, **Then** solo se consideran
   proveedores de la ruta de EMAIL; nunca de otro canal.

---

### User Story 2 - Registro de quién entregó y quién falló (Priority: P1)

Como AP quiero ver en la notificación qué proveedor entregó y cuáles fallaron, para diagnosticar y
auditar el envío.

**Why this priority**: Principios VIII y IX: el recorrido debe poder reconstruirse; sin registro el
failover es invisible.

**Independent Test**: Tras el escenario 1 de la US1, la consulta de la notificación muestra un intento
fallido de A y uno aceptado de B, en ese orden.

**Acceptance Scenarios**:

1. **Given** un failover de A a B, **When** se consulta el historial, **Then** aparece un intento
   recuperable con proveedor A, marcado como conmutado, seguido del intento aceptado con proveedor B.
2. **Given** un failover, **When** se consulta la notificación, **Then** el proveedor que entregó es el
   del intento aceptado y los fallidos son los intentos anteriores de esa ronda.
3. **Given** un failover completado, **When** se consultan las métricas, **Then** cada proveedor
   probado cuenta su intento con su resultado.

---

### User Story 3 - Circuit breaker por proveedor (Priority: P2)

Como AP quiero que un proveedor que falla de forma sostenida deje de recibir envíos por un tiempo,
para no gastar latencia ni cuota en un proveedor caído.

**Why this priority**: Protege el tiempo de despacho cuando el proveedor preferente está caído, pero el
failover ya aporta valor sin él.

**Independent Test**: Provocar fallos hasta el umbral y comprobar que el siguiente despacho no invoca
al proveedor abierto y sí al siguiente; tras la espera, el circuito admite una prueba.

**Acceptance Scenarios**:

1. **Given** un proveedor que alcanza el umbral de fallos configurado, **When** llega otra
   notificación, **Then** el proveedor no se invoca y se usa el siguiente, sin registrar intento.
2. **Given** un circuito abierto cuyo tiempo de espera venció, **When** llega una notificación,
   **Then** se admite una invocación de prueba; si acepta, el circuito se cierra.
3. **Given** circuitos independientes por proveedor, **When** abre el de A, **Then** el de B sigue
   cerrado.
4. **Given** todos los proveedores habilitados con circuito abierto, **When** se despacha, **Then** no
   se envía nada, no se pierde la notificación y sigue el camino de reintento/DLQ existente.

---

### User Story 4 - `simulated` solo en desarrollo (Priority: P1)

Como AP quiero que en un ambiente compartido `simulated` esté deshabilitado, para que el failover
nunca termine en un proveedor que marca entregas sin enviar.

**Why this priority**: Riesgo directo de Principio VIII: una entrega falsa es una pérdida silenciosa.

**Independent Test**: Con el perfil por defecto (sin `local`), `simulated` figura deshabilitado y un
despacho cuya ruta lo incluye nunca lo invoca; con `local` o pruebas, está habilitado.

**Acceptance Scenarios**:

1. **Given** el perfil por defecto, **When** se calcula la configuración, **Then** `simulated` no está
   entre los proveedores habilitados de ningún canal.
2. **Given** la ruta `[brevo, simulated]` fuera de desarrollo y brevo falla de forma recuperable,
   **When** se despacha, **Then** `simulated` no se invoca y la notificación queda `RECOVERABLE`.
3. **Given** el perfil `local` o el entorno de pruebas, **When** arranca, **Then** `simulated` está
   habilitado y el flujo de punta a punta funciona como antes.

### Edge Cases

- Dos hilos despachan la misma notificación: `reserveForDispatch` ya lo impide; la conmutación ocurre
  dentro de una única reserva.
- El proveedor siguiente no soporta adjuntos y la notificación los lleva: se trata como hoy (rechazo
  permanente de ese proveedor, sin conmutar más).
- El proveedor lanza un error de transporte (excepción): se trata como fallo recuperable para la
  conmutación y el circuito.
- La notificación falla en el guardado tras conmutar: aplican las garantías de persistencia de hoy; no
  se pierde el registro de la ronda.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Ante `RECOVERABLE_FAILURE` o error de transporte de un proveedor, el despacho MUST probar
  el siguiente proveedor habilitado de la ruta ordenada del mismo canal, dentro del mismo despacho.
- **FR-002**: El despacho MUST NOT conmutar ante `ACCEPTED` ni `PERMANENT_FAILURE`.
- **FR-003**: El despacho MUST omitir, sin registrar intento, los proveedores deshabilitados y los de
  circuito abierto; si no queda ninguno, MUST fallar sin cambiar el estado de la notificación.
- **FR-004**: Cada proveedor que falla de forma recuperable antes del resultado final MUST quedar
  registrado como intento en el historial, marcado como conmutado, con su `providerId`.
- **FR-005**: El proveedor que entrega MUST quedar registrado como el del intento aceptado.
- **FR-006**: Los intentos conmutados MUST NOT sumar al presupuesto de reintentos de la política.
- **FR-007**: Cada proveedor MUST tener un circuito independiente que abre al superar un umbral de
  fallos configurable y admite una prueba tras un tiempo de espera configurable.
- **FR-008**: `simulated` MUST estar deshabilitado salvo en los perfiles local y de pruebas, y su
  deshabilitación MUST reflejarse en `disabledReason` y en los proveedores habilitados por canal.
- **FR-009**: El resultado final tras agotar la ruta MUST ser el del último proveedor intentado, con
  las reglas de estado y de reintento vigentes.
- **FR-010**: Ninguna garantía de aislamiento por tenant cambia: la ruta se obtiene por canal y
  tenant como hoy y el failover no cruza tenants ni canales.

### Key Entities

- **Ruta del canal**: lista ordenada de proveedores del catálogo (007); el failover la recorre.
- **Intento de entrega**: ya existe; gana la marca de conmutado.
- **Circuito del proveedor**: estado por `providerId` (cerrado, abierto, semiabierto).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el preferente fallando de forma recuperable y el siguiente sano, el 100 % de las
  notificaciones se entregan en un solo despacho (prueba E2E sobre el flujo completo).
- **SC-002**: Con circuito abierto, el proveedor abierto recibe 0 invocaciones y el tiempo añadido al
  despacho por ese proveedor es el de una omisión, no el de un timeout (prueba automatizada con
  aserción de `Duration`).
- **SC-003**: Con el perfil por defecto, 0 notificaciones se entregan por `simulated` aunque la ruta lo
  incluya (prueba automatizada sin perfil `local`).
- **SC-004**: En todo failover el historial permite reconstruir proveedor fallido y proveedor entregador
  sin información externa (prueba E2E que lo consulta).

## Assumptions

- No se añade un segundo proveedor real; el segundo proveedor de la prueba E2E es `simulated`
  habilitado por perfil de pruebas con un primer proveedor de prueba que falla.
- La conmutación es secuencial; no hay envío en paralelo a varios proveedores.
- Umbral y espera del circuito son configuración por proveedor con valores por defecto razonables.
- Los cambios de contrato (campo de conmutado en el intento) los aplica el equipo de Contrato.
