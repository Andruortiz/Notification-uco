# Feature Specification: Enviar un lote de notificaciones por HTTP

**Feature Branch**: `feature/HU2-021-exponer-envio-lote`

**Created**: 2026-09-26

**Status**: Draft

**Input**: User description: "HU2-021. Como sistema cliente, quiero enviar un lote de notificaciones en
una sola solicitud, para seguirlas como unidad. El caso de uso de envío por lote ya existe en el
núcleo, pero ningún punto de entrada HTTP lo expone. Alcance: solo el adaptador de entrada que
publique la operación `sendNotificationBatch` (`POST /notifications:sendBatch`) tal como ya está
definida en el contrato público, con la cabecera `X-Tenant-Id` y respuesta `202 Accepted`. El caso de
uso del núcleo no se modifica."

## Clarifications

### Session 2026-09-26

- Q: El contrato promete que "un elemento inválido no bloquea a los demás", pero hay elementos que ni
  siquiera se pueden representar como una solicitud de notificación (campo obligatorio ausente,
  prioridad desconocida). ¿Se rechazan individualmente o se rechaza el lote completo? → A: Se
  distinguen dos niveles. Los errores estructurales (el lote no cumple el esquema del contrato:
  campos obligatorios ausentes o vacíos, prioridad fuera del enum, lista vacía) rechazan la solicitud
  completa con 400, igual que `POST /notifications` rechaza una solicitud mal formada, y no se acepta
  ningún elemento. Las validaciones de negocio que dependen del catálogo (canal inexistente o
  deshabilitado, contenido que no cumple la forma del canal) se resuelven elemento por elemento como
  `REJECTED`. Motivo: un elemento sin `externalId` válido no tiene cómo reportarse individualmente, y
  resolver la validación estructural por elemento obligaría al adaptador HTTP a reimplementar la
  lógica de agregación que ya vive en el caso de uso. Reenviar el lote corregido es seguro gracias a
  la idempotencia por `externalId`. El contrato documenta la respuesta 400. Decisión tomada por el
  autor con la aprobación general del usuario para completar la API; queda a revisión en el PR.
- Q: ¿Debe esta historia fijar un número máximo de elementos por lote? → A: No. El contrato no fija
  un máximo y hacerlo es una decisión de cuotas por tenant; el tamaño queda acotado por el límite de
  tamaño de cuerpo que el servicio ya aplica a toda solicitud (ver Assumptions).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enviar varias notificaciones en una sola solicitud y recibir el resultado de cada una (Priority: P1)

Como sistema cliente, quiero enviar un lote de notificaciones en una sola solicitud y recibir, en la
misma respuesta, un identificador del lote y el resultado individual de cada elemento (aceptado,
duplicado o rechazado, con el identificador de la notificación creada o el motivo del rechazo), para
seguir el lote como una unidad sin tener que hacer una solicitud por notificación.

**Why this priority**: Es la historia completa. Hoy la capacidad de envío por lote existe en el
núcleo pero ningún cliente puede usarla: el contrato publica la operación y el servicio no la atiende.

**Independent Test**: Enviar un lote de varios elementos válidos a un canal habilitado, confirmar que
la respuesta es "aceptado" con un resultado por elemento en el mismo orden del lote, y que cada
notificación aceptada llega a su estado terminal de entrega a través del flujo normal de despacho.

**Acceptance Scenarios**:

1. **Given** un lote de varios elementos válidos dirigidos a canales habilitados, **When** el sistema
   cliente lo envía, **Then** la respuesta indica "aceptado", incluye un identificador de lote y un
   resultado `ACCEPTED` por cada elemento, con su `externalId` y el identificador de la notificación
   creada, en el mismo orden en que los elementos venían en la solicitud.
2. **Given** un lote aceptado, **When** el sistema cliente consulta el estado de cualquiera de las
   notificaciones devueltas, **Then** cada una existe para su tenant y progresa por el flujo normal
   de despacho hasta su estado terminal, exactamente igual que una notificación enviada de forma
   individual.
3. **Given** un lote que trae su propio identificador de lote, **When** se acepta, **Then** la
   respuesta devuelve ese mismo identificador; **Given** un lote sin identificador, **Then** la
   respuesta devuelve uno generado por el sistema.
4. **Given** un lote en el que un elemento apunta a un canal inexistente o deshabilitado, o cuyo
   contenido no cumple la forma de contenido del canal, **When** se envía, **Then** ese elemento
   aparece como `REJECTED` con un motivo legible y sin identificador de notificación, y los demás
   elementos del lote se aceptan con normalidad (un elemento inválido no bloquea a los demás).
5. **Given** un elemento cuyo `externalId` ya fue aceptado antes para el mismo tenant, **When** llega
   dentro de un lote, **Then** aparece como `DUPLICATE` con el identificador de la notificación ya
   existente y no se crea una segunda notificación.
6. **Given** dos tenants distintos que envían lotes con los mismos `externalId`, **When** ambos lotes
   se aceptan, **Then** cada tenant obtiene sus propias notificaciones (`ACCEPTED`, no `DUPLICATE`), y
   ningún tenant puede consultar las notificaciones creadas por el lote del otro.

---

### Edge Cases

- **Solicitud estructuralmente inválida**: un lote sin elementos, un lote al que le falta la lista de
  elementos, o un elemento al que le falta un campo obligatorio del contrato, trae un campo
  obligatorio vacío o una prioridad fuera de los valores conocidos, se rechaza completo con un error
  de solicitud inválida (400) y un mensaje legible; no se acepta ningún elemento. Ver Clarifications.
- **Identificador de lote vacío**: un `batchId` enviado pero en blanco es un valor mal formado, no una
  ausencia; se rechaza con 400 como cualquier otro campo mal formado.
- **Falta la cabecera de tenant**: la solicitud se rechaza con 400 sin procesar ningún elemento,
  igual que el resto de operaciones.
- **Todos los elementos rechazados por negocio**: la respuesta sigue siendo "aceptado" (202) con un
  resultado `REJECTED` por cada elemento; el lote como solicitud fue procesado, aunque ninguno de sus
  elementos haya producido una notificación.
- **El mismo `externalId` repetido dentro de un mismo lote**: la regla de idempotencia por
  `(tenant, externalId)` aplica igual que entre solicitudes; el comportamiento concurrente de este
  caso pertenece al caso de uso del núcleo, fuera del alcance de esta historia (ver Assumptions).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema MUST atender la operación `sendNotificationBatch` en la ruta y con el
  esquema de solicitud y respuesta definidos en el contrato público, identificando al tenant por la
  cabecera `X-Tenant-Id`.
- **FR-002**: El sistema MUST responder "aceptado" (202) con el identificador del lote y la lista de
  resultados por elemento cuando la solicitud es estructuralmente válida, sin importar cuántos
  elementos resulten rechazados por reglas de negocio.
- **FR-003**: El sistema MUST devolver exactamente un resultado por elemento, en el mismo orden de
  la solicitud, con su `externalId`, su resultado (`ACCEPTED`, `DUPLICATE` o `REJECTED`), el
  identificador de la notificación cuando no fue rechazado y el motivo cuando sí lo fue.
- **FR-004**: El sistema MUST conservar el identificador de lote enviado por el cliente y generar uno
  cuando el cliente no lo envía.
- **FR-005**: El sistema MUST procesar cada elemento con las mismas reglas que un envío individual:
  validación contra el catálogo, idempotencia por `(tenant, externalId)`, persistencia previa a la
  respuesta y encolado para despacho.
- **FR-006**: El sistema MUST rechazar la solicitud completa con 400 y un mensaje legible cuando el
  lote no cumple el esquema del contrato (lista vacía o ausente, campo obligatorio ausente o vacío,
  prioridad desconocida, `batchId` en blanco, cabecera de tenant ausente), sin aceptar ningún
  elemento.
- **FR-007**: El sistema MUST aislar los lotes por tenant: la idempotencia y la visibilidad de las
  notificaciones creadas por un lote nunca cruzan de un tenant a otro.
- **FR-008**: La descripción de la operación en el contrato público MUST indicar que está
  implementada y documentar la respuesta 400 de solicitud estructuralmente inválida.

### Key Entities

- **Lote**: agrupación de solicitudes de notificación enviadas en una sola petición; se identifica
  con un `batchId` (propio del cliente o generado). En esta historia el lote existe como unidad en la
  respuesta; su identificador no se persiste ni se puede consultar después.
- **Resultado por elemento**: para cada elemento del lote, su `externalId`, su resultado (`ACCEPTED`,
  `DUPLICATE`, `REJECTED`), el identificador de la notificación (salvo si fue rechazado) y el motivo
  del rechazo (solo si fue rechazado).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un sistema cliente puede enviar un lote de al menos 3 notificaciones en una sola
  solicitud y recibir un resultado por elemento, en el mismo orden, sin hacer ninguna solicitud
  adicional.
- **SC-002**: El 100 % de las notificaciones aceptadas por un lote llegan a su estado terminal de
  entrega por el mismo flujo que una notificación individual, verificado de punta a punta.
- **SC-003**: En un lote con elementos válidos y elementos rechazados por negocio, el 100 % de los
  elementos válidos se aceptan; ningún rechazo individual impide la aceptación de los demás.
- **SC-004**: Cero notificaciones creadas o visibles entre tenants distintos a partir de lotes con
  los mismos `externalId`, verificado con dos tenants.
- **SC-005**: Una solicitud estructuralmente inválida no crea ninguna notificación (0 registros
  nuevos) y recibe un error de solicitud inválida.

## Assumptions

- El caso de uso de envío por lote del núcleo está completo y probado; esta historia no lo modifica.
  El orden de los resultados, la generación del identificador de lote y la clasificación por elemento
  son responsabilidad suya.
- El contrato público ya define la operación y sus esquemas; esta historia solo agrega la respuesta
  400 y actualiza la descripción para indicar que está implementada.
- No hay un tamaño máximo explícito de lote en el contrato; el tamaño queda acotado por el límite de
  tamaño de cuerpo de solicitud que ya aplica el servicio a cualquier operación. Fijar un máximo de
  elementos por lote es una decisión de producto (cuotas por tenant) fuera de esta historia.
- El identificador de lote no se persiste junto a las notificaciones; consultar notificaciones por
  lote no forma parte de esta historia ni del contrato actual.
- El comportamiento cuando un mismo `externalId` aparece dos veces en el mismo lote, procesadas de
  forma concurrente, depende del caso de uso del núcleo y de la restricción única de persistencia; si
  se observa un defecto, se reporta y se documenta como pendiente, no se corrige en esta historia.
- Mientras la autenticación siga bloqueada, el tenant se toma de `X-Tenant-Id`, igual que en el resto
  de la API.
