# Feature Specification: Ver notificaciones en tiempo real en el dashboard

**Feature Branch**: `006-dashboard-tiempo-real`

**Created**: 2026-09-14

**Status**: Draft

**Input**: User description: "Como operador, quiero ver las notificaciones aparecer en el dashboard a
medida que se aceptan/despachan/entregan, sin refrescar la página"

## Clarifications

### Session 2026-09-15

- Q: ¿El feed en tiempo real debe reflejar solo las notificaciones que cumplen los filtros de
  búsqueda activos del operador, o siempre debe mostrar todas las notificaciones del tenant sin
  importar los filtros? → A: El feed en tiempo real llega siempre para todo el tenant del operador;
  los filtros activos determinan dinámicamente qué subconjunto de ese feed es visible. Cuando hay
  filtros aplicados, una notificación puede entrar, actualizarse o salir de la vista visible en
  cualquier momento en que cambie su estado o cualquier atributo relevante para esos filtros — sin
  que el operador tenga que repetir la búsqueda.
- Q: Si la conexión en tiempo real del operador se cae temporalmente (red inestable, laptop en
  suspensión) y se reconecta después, ¿qué debe pasar con los cambios de estado ocurridos durante la
  desconexión? → A: Resincronización automática — al reconectar, el dashboard vuelve a cargar el
  estado actual completo (equivalente a repetir la búsqueda vigente) y retoma las actualizaciones en
  vivo; ningún cambio de estado queda permanentemente oculto.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver el ciclo de vida de una notificación sin refrescar (Priority: P1)

Como operador, quiero que las notificaciones visibles en el dashboard reflejen su estado actual
(aceptada, en proceso de despacho, entregada, recuperable, fallida o descartada) a medida que cambia,
sin tener que recargar la página ni repetir manualmente una búsqueda, para poder monitorear el flujo
de notificaciones de mi tenant en tiempo real.

**Why this priority**: Es el valor central de la historia — sin esto, el dashboard sigue siendo una
foto fija que exige refrescos manuales constantes para detectar incidencias, lo cual es inviable para
monitoreo operativo continuo.

**Independent Test**: Con el dashboard abierto y sin realizar ninguna acción, aceptar una
notificación desde el sistema y dejar que progrese por su ciclo de vida (por ejemplo hasta
`DELIVERED`, o hasta `FAILED` tras agotar reintentos); confirmar que cada transición de estado se
refleja en el dashboard sin recargar la página ni repetir la búsqueda.

**Acceptance Scenarios**:

1. **Given** el dashboard abierto mostrando una notificación en estado `PENDING`, **When** el sistema
   la despacha y pasa a `IN_PROCESS`, **Then** el dashboard actualiza el estado visible de esa
   notificación sin que el operador recargue la página.
2. **Given** una notificación visible en el dashboard, **When** su entrega se confirma y pasa a
   `DELIVERED`, **Then** el dashboard refleja ese cambio en cuestión de segundos.
3. **Given** una notificación visible en el dashboard, **When** un intento de entrega falla de forma
   transitoria y la notificación pasa a `RECOVERABLE`, y luego un reintento exitoso la lleva a
   `DELIVERED`, **Then** el dashboard muestra cada transición en orden, no solo el estado final.
4. **Given** el dashboard abierto sin ninguna notificación nueva ocurriendo, **When** pasa el tiempo,
   **Then** la conexión en tiempo real permanece activa sin requerir intervención del operador.

---

### User Story 2 - La vista filtrada se mantiene al día automáticamente (Priority: P2)

Como operador con filtros de búsqueda aplicados (destinatario, canal, estado o rango de fechas),
quiero que la lista visible se actualice sola cuando una notificación empieza o deja de cumplir esos
filtros, para no tener que volver a ejecutar la búsqueda cada vez que sospecho que algo cambió.

**Why this priority**: Complementa la historia principal — sin esto, un operador que ya acotó su
vista a un problema específico (ej. `status=FAILED` de un canal) perdería la actualización en vivo en
cuanto aplicara un filtro, obligándolo a alternar entre "ver todo en tiempo real" y "buscar lo que me
interesa".

**Independent Test**: Aplicar un filtro (ej. `status=RECOVERABLE`) sobre un conjunto de notificaciones
con distintos estados; forzar que una notificación fuera de la vista pase a `RECOVERABLE` y otra que
sí es visible pase a `DELIVERED`; confirmar que la primera aparece en la vista y la segunda desaparece,
ambas sin recargar ni repetir la búsqueda.

**Acceptance Scenarios**:

1. **Given** una vista filtrada por `status=FAILED`, **When** una notificación fuera de esa vista pasa
   a `FAILED`, **Then** aparece en la vista visible sin acción del operador.
2. **Given** una notificación visible en una vista filtrada por `status=RECOVERABLE`, **When** esa
   notificación pasa a `DELIVERED`, **Then** desaparece de la vista visible sin acción del operador.
3. **Given** una vista sin ningún filtro aplicado, **When** cualquier notificación del tenant cambia
   de estado, **Then** el cambio se refleja en la vista, ya que ningún filtro la excluye.

---

### User Story 3 - Recuperar la vista tras una desconexión temporal (Priority: P3)

Como operador cuya conexión al dashboard se interrumpe brevemente (por red inestable o el equipo
suspendido), quiero que al reconectarse el dashboard se ponga al día automáticamente con todo lo que
ocurrió mientras estuve desconectado, para no perder de vista un cambio de estado crítico ocurrido
durante ese lapso.

**Why this priority**: Es un caso de continuidad importante para confiabilidad, pero de menor
frecuencia e impacto inmediato que ver las actualizaciones en vivo (US1) o que la vista filtrada se
mantenga correcta (US2); el dashboard ya es útil sin esta historia, solo que con una ventana de riesgo
en reconexiones.

**Independent Test**: Con el dashboard abierto, simular una interrupción de la conexión en tiempo
real; mientras está desconectado, cambiar el estado de una o más notificaciones visibles y no
visibles; reconectar y confirmar que la vista queda igual que si se hubiera repetido la búsqueda en
ese momento, sin cambios de estado permanentemente perdidos.

**Acceptance Scenarios**:

1. **Given** el dashboard desconectado temporalmente de la actualización en tiempo real, **When** una
   notificación visible cambia de estado durante la desconexión, **Then** al reconectar el dashboard
   muestra su estado más reciente, no el que tenía antes de desconectarse.
2. **Given** el dashboard desconectado temporalmente, **When** una notificación empieza a cumplir los
   filtros activos durante la desconexión, **Then** al reconectar aparece en la vista como si el
   cambio hubiera ocurrido con la conexión activa.
3. **Given** una reconexión exitosa, **When** se completa la resincronización, **Then** el dashboard
   retoma la recepción de actualizaciones en vivo sin requerir que el operador recargue la página.

---

### Edge Cases

- ¿Qué pasa si dos o más operadores del mismo tenant tienen el dashboard abierto a la vez? Cada uno
  recibe las mismas actualizaciones en tiempo real de forma independiente, sin que la vista de uno
  afecte a la del otro.
- ¿Qué pasa si una notificación cambia de estado más de una vez en un lapso muy corto (ej.
  `IN_PROCESS` → `RECOVERABLE` → `IN_PROCESS` → `DELIVERED` en segundos)? El dashboard refleja el
  estado vigente y el historial completo de intentos, sin quedarse mostrando un estado intermedio ya
  superado.
- ¿Qué pasa si un operador de un tenant recibe, por error de configuración, un evento de otro tenant?
  El sistema nunca debe entregarlo — es la misma garantía de aislamiento que ya aplica a la búsqueda
  (ver FR-002).
- ¿Qué pasa si el operador cambia sus filtros activos mientras la conexión en tiempo real sigue
  abierta? La vista visible se recalcula de inmediato contra el nuevo filtro, y el feed en tiempo real
  continúa entregando actualizaciones para el nuevo conjunto filtrado.
- ¿Qué pasa si el operador cierra la pestaña del dashboard sin cerrar sesión explícitamente? El
  sistema detecta que la conexión ya no está activa y deja de intentar enviarle actualizaciones.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE entregar a un dashboard suscrito, a medida que ocurre, cada transición
  de estado de una notificación (`PENDING`, `IN_PROCESS`, `DELIVERED`, `RECOVERABLE`, `FAILED`,
  `DISCARDED`) que pertenezca al tenant de ese dashboard, sin que el operador tenga que recargar la
  página ni repetir una búsqueda.
- **FR-002**: El feed en tiempo real DEBE estar aislado por tenant — un dashboard nunca recibe eventos
  de notificaciones de un tenant distinto al suyo, con la misma garantía que ya aplica a la búsqueda
  (Principio de aislamiento por tenant).
- **FR-003**: Cuando el operador tiene filtros de búsqueda activos (destinatario, canal, estado o
  rango de fechas), el sistema DEBE asegurar que la vista visible del dashboard solo contenga, en todo
  momento, las notificaciones que cumplen esos filtros — aplicando la misma lógica de filtrado que la
  búsqueda manual.
- **FR-004**: Cuando el cambio de estado (u otro atributo filtrable) de una notificación hace que deje
  de cumplir los filtros activos del operador, el sistema DEBE hacer que desaparezca de la vista
  visible sin intervención del operador; cuando un cambio hace que una notificación empiece a
  cumplirlos, el sistema DEBE hacer que aparezca en la vista sin intervención del operador.
- **FR-005**: Cuando una notificación ya visible en el dashboard cambia de estado, el sistema DEBE
  actualizar esa notificación en su lugar (estado vigente e historial de intentos), no solo agregar un
  elemento nuevo a la lista.
- **FR-006**: Al reconectarse después de una interrupción temporal de la conexión en tiempo real, el
  sistema DEBE resincronizar automáticamente la vista del operador al estado actual completo
  (equivalente a repetir la búsqueda vigente con los filtros activos) antes de retomar la entrega de
  actualizaciones en vivo, de forma que ningún cambio de estado quede permanentemente oculto.
- **FR-007**: El sistema DEBE soportar que varios operadores del mismo tenant mantengan el dashboard
  abierto de forma simultánea, entregando a cada uno las actualizaciones en tiempo real de forma
  independiente.
- **FR-008**: El sistema DEBE detectar cuándo la conexión en tiempo real de un dashboard ya no está
  activa (ej. pestaña cerrada, pérdida de red prolongada) y DEBE dejar de intentar enviarle
  actualizaciones a partir de ese momento.
- **FR-009**: El sistema NO DEBE requerir que el operador realice sondeo manual (refrescar o repetir
  la búsqueda periódicamente) para mantener la vista del dashboard al día.

### Key Entities *(include if feature involves data)*

- **Notificación**: entidad ya existente (ver historias previas) — esta historia no agrega campos
  nuevos, solo expone sus cambios de estado como eventos entregados en tiempo real, además de como
  resultado de una búsqueda manual.
- **Actualización en tiempo real**: representa un cambio de estado (o de un atributo relevante para
  los filtros) de una notificación, entregado a un dashboard suscrito; identifica la notificación
  afectada, su tenant, su nuevo estado y el momento en que ocurrió. No se persiste como entidad nueva
  — es una proyección en tiempo real del mismo historial de intentos que ya existe.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un operador con el dashboard abierto ve reflejado un cambio de estado de una
  notificación en 5 segundos o menos desde que ese cambio ocurre en el sistema, sin realizar ninguna
  acción manual.
- **SC-002**: Ninguna actualización en tiempo real, bajo ninguna circunstancia, llega a un dashboard
  de un tenant distinto al de la notificación afectada.
- **SC-003**: Tras una desconexión temporal de hasta varios minutos, un operador que reconecta ve su
  vista completamente al día (sin cambios de estado faltantes) en menos de 5 segundos desde que la
  conexión se restablece.
- **SC-004**: Un operador puede seguir el ciclo de vida completo de una notificación, desde que se
  acepta hasta que se entrega o falla definitivamente, sin realizar ni un solo refresco manual de
  página ni una sola repetición manual de búsqueda.
- **SC-005**: El sistema mantiene actualizaciones en tiempo real funcionando correctamente con al
  menos 50 sesiones de dashboard concurrentes por tenant, sin que la latencia de SC-001 se degrade.

## Assumptions

- El conjunto de transiciones de estado que se entregan en vivo es el ciclo de vida completo de una
  notificación (`PENDING`, `IN_PROCESS`, `DELIVERED`, `RECOVERABLE`, `FAILED`, `DISCARDED`), no solo
  las tres mencionadas literalmente en la solicitud ("aceptan/despachan/entregan") — un dashboard
  operativo pierde valor de monitoreo si oculta los estados de falla o descarte.
- El mecanismo técnico concreto para entregar las actualizaciones en tiempo real (ej. WebSocket,
  Server-Sent Events, u otro) es una decisión de `/speckit-plan`, no de este spec — el requisito de
  negocio es "el dashboard se mantiene al día sin acción del operador", no un protocolo específico.
- El aislamiento por tenant del feed en tiempo real reutiliza el mismo mecanismo ya usado por el resto
  de la API (actualmente el header `X-Tenant-Id`, placeholder mientras se resuelve la autenticación
  real) — esta historia no introduce un modelo de autorización distinto.
- Los filtros que determinan qué notificaciones son visibles en tiempo real son los mismos ya
  definidos para la búsqueda (`recipientId`, `channelType`, `status`, rango de fechas) — esta historia
  no agrega nuevas dimensiones de filtrado.
- El conjunto inicial de notificaciones que ve el operador al abrir el dashboard se obtiene mediante la
  capacidad de búsqueda ya existente; esta historia solo agrega la capa de actualización en vivo sobre
  esa vista inicial.
- "Resincronización automática" (FR-006) significa recargar la vista filtrada vigente equivalente a
  repetir la búsqueda existente, no un registro de eventos que permita reproducir cada cambio ocurrido
  durante la desconexión en orden exacto.
- Los valores de SC-001, SC-003 y SC-005 (5 segundos, 5 segundos, 50 sesiones concurrentes) son
  referencias razonables para una herramienta operativa interna, no RNF ya documentados con esos
  números exactos — se ajustan si en `/speckit-plan` se determina que la infraestructura necesaria
  para cumplirlos requiere trabajo adicional no cubierto por esta historia.
