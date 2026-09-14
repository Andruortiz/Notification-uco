# Feature Specification: Buscar notificaciones por filtros

**Feature Branch**: `005-search-notifications`

**Created**: 2026-09-14

**Status**: Draft

**Input**: User description: "Como operador, quiero buscar notificaciones por destinatario, canal, estado o rango de fechas."

## Clarifications

### Session 2026-09-14

- Q: ¿Una búsqueda sin filtro de fecha debe tener un tope de tamaño/paginación, o se acepta devolver
  el historial completo del tenant sin límite en esta primera versión? → A: Paginación obligatoria
  (`limit`/`offset` o equivalente) desde el inicio — cambia el contrato de `api-notificaciones.yaml`,
  pero evita cualquier riesgo de respuesta desproporcionada desde el día uno.
- Q: ¿En qué orden se devuelven los resultados cuando hay más de uno? → A: Más reciente primero
  (`acceptedAt` descendente).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Buscar notificaciones combinando filtros y ver su historial completo (Priority: P1)

Como operador, quiero buscar notificaciones por cualquier combinación de destinatario, canal, estado
o rango de fechas de aceptación, y ver el historial completo de intentos de cada resultado (no solo
su estado actual), para investigar incidencias sin necesitar de antemano el id exacto de la
notificación.

**Why this priority**: Es el único punto de entrada hoy para investigar "¿qué pasó con las
notificaciones de este destinatario/canal/periodo?" sin tener el id — sin esto, un operador no tiene
forma de encontrar el registro de una notificación fallida salvo que alguien le pase el id
directamente. Es también el requisito que deja el frontend (`Listado.tsx`) sin datos reales que
mostrar.

**Independent Test**: Aceptar varias notificaciones con distintos destinatarios, canales y estados
(incluyendo al menos una fallida tras agotar reintentos), buscar combinando dos o más filtros, y
confirmar que la respuesta solo incluye las que cumplen todos los filtros aplicados, cada una con su
historial completo de intentos.

**Acceptance Scenarios**:

1. **Given** varias notificaciones aceptadas con distintos estados, **When** el operador busca sin
   ningún filtro, **Then** el sistema devuelve todas las notificaciones del tenant solicitante, cada
   una con su historial completo de intentos.
2. **Given** notificaciones de varios destinatarios, **When** el operador busca filtrando por un
   `recipientId` específico, **Then** el sistema devuelve solo las notificaciones de ese destinatario.
3. **Given** notificaciones en distintos estados, **When** el operador busca filtrando por
   `status=FAILED`, **Then** el sistema devuelve solo las que están actualmente en ese estado.
4. **Given** notificaciones aceptadas en distintas fechas, **When** el operador busca con un rango
   `from`/`to`, **Then** el sistema devuelve solo las aceptadas dentro de ese rango, inclusive en
   ambos extremos.
5. **Given** dos o más filtros aplicados a la vez (ej. canal + estado + rango de fechas), **When** el
   operador ejecuta la búsqueda, **Then** el sistema devuelve solo las notificaciones que cumplen
   *todos* los filtros combinados (AND, no OR).
6. **Given** ningún resultado cumple los filtros, **When** el operador ejecuta la búsqueda, **Then**
   el sistema devuelve una lista vacía, no un error.
7. **Given** una notificación con varios intentos de entrega (ej. un fallo transitorio seguido de un
   reintento exitoso), **When** aparece en los resultados de una búsqueda, **Then** se incluyen todos
   sus intentos en orden cronológico, no solo el último.
8. **Given** notificaciones de dos tenants distintos, **When** un operador de un tenant ejecuta la
   búsqueda, **Then** nunca aparece ninguna notificación del otro tenant, sin importar los filtros
   usados.
9. **Given** un tenant con más notificaciones que el tamaño de página, **When** el operador ejecuta la
   búsqueda sin pedir una página específica, **Then** el sistema devuelve la primera página, con las
   notificaciones más recientes (`acceptedAt` descendente) primero, y una forma de pedir la siguiente
   página.

---

### Edge Cases

- ¿Qué pasa si el rango de fechas es inválido (`from` posterior a `to`)? El sistema rechaza la
  solicitud con un error claro en vez de devolver una lista vacía silenciosa que el operador podría
  confundir con "no hay resultados".
- ¿Qué pasa si un filtro tiene un valor que no corresponde a ningún valor real (ej. un `channelType`
  que no existe en el catálogo, o un `status` fuera del enum conocido)? Se trata igual que cualquier
  otro filtro sin coincidencias — lista vacía, no error — salvo que el valor esté mal formado a nivel
  de tipo (ver clarificación de validación de entrada más abajo si aplica).
- ¿Qué pasa si el tenant tiene un volumen muy grande de notificaciones y la búsqueda no tiene ningún
  filtro? La paginación obligatoria (FR-007) lo cubre — la respuesta nunca crece sin límite, sin
  importar cuántas notificaciones tenga el tenant.
- ¿Qué pasa si el cliente pide un tamaño de página fuera de un rango razonable (ej. 0, negativo, o
  desproporcionadamente grande)? El sistema rechaza la solicitud con un error claro, igual que con un
  rango de fechas inválido.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE permitir a un operador buscar notificaciones combinando cualquier
  subconjunto de estos filtros: destinatario (`recipientId`), canal (`channelType`), estado
  (`status`), y rango de fechas de aceptación (`from`/`to`).
- **FR-002**: Cuando se combinan varios filtros, el sistema DEBE aplicarlos con lógica AND — un
  resultado debe cumplir todos los filtros presentes, no basta con cumplir alguno.
- **FR-003**: Cuando no se especifica ningún filtro, el sistema DEBE devolver todas las notificaciones
  del tenant solicitante (sujeto al acotamiento de resultados de FR-007).
- **FR-004**: Cada resultado DEBE incluir el historial completo de intentos de entrega de esa
  notificación (fecha, resultado, origen manual/automático, proveedor), no solo su estado actual —
  a diferencia de la consulta de una notificación individual por id, que solo expone el estado
  vigente.
- **FR-005**: La búsqueda DEBE estar aislada por tenant — un operador nunca puede ver ni contar
  notificaciones de un tenant distinto al de su solicitud, sin importar los filtros usados.
- **FR-006**: El sistema DEBE devolver una lista vacía, no un error, cuando ningún resultado cumple
  los filtros aplicados.
- **FR-007**: El sistema DEBE paginar los resultados de la búsqueda — nunca devuelve el historial
  completo de un tenant sin límite, incluso cuando no se aplica ningún filtro.
- **FR-008**: El sistema DEBE rechazar una solicitud donde la fecha `from` sea posterior a la fecha
  `to`, con un mensaje de error que distinga ese caso de "no hay resultados".
- **FR-009**: El sistema DEBE devolver los resultados ordenados por fecha de aceptación
  (`acceptedAt`) descendente — las notificaciones más recientes primero.
- **FR-010**: El sistema DEBE aplicar un tamaño de página por defecto razonable cuando el cliente no
  especifica uno, y DEBE rechazar un tamaño de página fuera de un rango válido (ej. cero, negativo, o
  desproporcionadamente grande) en vez de aceptarlo silenciosamente.

### Key Entities *(include if feature involves data)*

- **Notificación**: entidad ya existente en el sistema (ver historias previas) — esta historia no
  agrega campos nuevos, solo expone su historial completo como resultado de búsqueda en vez de solo
  su estado actual.
- **Intento de entrega**: entidad ya existente (fecha, resultado, origen, proveedor) — esta historia
  reutiliza el historial que ya se persiste con cada notificación, no introduce un registro nuevo.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un operador encuentra el historial completo de una notificación problemática usando
  cualquier combinación de destinatario, canal, estado o fecha, sin necesitar conocer su
  identificador de antemano.
- **SC-002**: Ninguna búsqueda devuelve, bajo ninguna combinación de filtros, una notificación que
  pertenezca a un tenant distinto al del solicitante.
- **SC-003**: Una búsqueda sobre un rango de fechas de un mes en un tenant con hasta 10 000
  notificaciones responde en 2 segundos o menos.
- **SC-004**: Una búsqueda sin resultados se distingue siempre de un error de la solicitud — el
  operador nunca confunde "no hay coincidencias" con "la búsqueda falló".

## Assumptions

- Los cuatro filtros (`recipientId`, `channelType`, `status`, rango `from`/`to`) ya están definidos en
  el contrato existente (`GET /notifications`, `api-notificaciones.yaml`, CU-05); esta historia sí
  necesita extender ese contrato para agregar los parámetros de paginación y el envoltorio de
  respuesta paginada, ya que el contrato actual no los contempla (Principio II — el contrato se
  actualiza primero, antes del controller).
- El mecanismo concreto de paginación (`limit`/`offset` vs. un cursor opaco) es una decisión de
  `/speckit-plan`, no de este spec — el requisito de negocio es "paginado, más reciente primero", no
  un mecanismo específico.
- El aislamiento por tenant (`X-Tenant-Id`) sigue el mismo mecanismo ya usado por el resto de la API
  (RNF-14) — esta historia no introduce un mecanismo de autorización distinto.
- El rango de fechas filtra por la fecha de aceptación de la notificación (`acceptedAt`), no por la
  fecha del último intento — es el campo estable que no cambia con cada reintento.
- SC-003 (2 segundos, 10 000 notificaciones) es un valor de referencia razonable para una herramienta
  operativa interna, no un RNF ya documentado con ese número exacto — se ajusta si en `/speckit-plan`
  se determina que el índice de MongoDB necesario para cumplirlo requiere trabajo adicional no
  cubierto por esta historia.
