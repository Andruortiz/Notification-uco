# Feature Specification: Reintentar envío manualmente y distinguir el reintento manual del automático

**Feature Branch**: `feature/HU2-027-reintentar-envio-manual`

**Created**: 2026-09-26

**Status**: Clarificado — Q1–Q4 con respuesta recomendada adoptada (2026-09-26)

**Input**: User description: "HU2-027 — Como operador, quiero forzar un nuevo intento sobre una
notificación FAILED o RECOVERABLE. HU2-028 — Como operador, quiero que quede registrado si el
reintento fue manual o automático."

**Trazabilidad**: CU-06 · Épica D (Trazabilidad y auditoría) · HU2-027 con HU2-028 como
complemento de la misma historia. Operación pública `retryNotification`
(`POST /notifications/{id}:retry`), ya declarada en el contrato de la API.

## Clarifications

### Session 2026-09-26

No fue posible consultar al usuario durante la redacción. Cada pregunta se registra con la respuesta
recomendada, que se adopta para seguir adelante (el usuario dio aprobación general para completar la
API); si alguna cambia al revisar el PR, se indica qué partes afecta.

- Q: ¿La respuesta del reintento espera el resultado del nuevo intento ante el proveedor, o solo
  confirma que la notificación volvió a la cola? → A: **Solo confirma** (202 con la notificación en
  PENDING). El intento se hace de forma asíncrona por la misma cola de despacho que cualquier otro
  envío, con sus reintentos de mensaje y su cola de mensajes muertos. Esperar al proveedor dentro de
  la solicitud HTTP ataría la latencia de la API a la del proveedor y saltaría las garantías de la
  cola. Afecta a: FR-003, FR-004, Assumptions.
- Q: ¿Un intento manual consume el presupuesto de reintentos automáticos de la notificación, o lo
  reinicia? → A: **Lo consume, no lo reinicia**. La política de reintentos cuenta todos los fallos
  recuperables sin mirar su origen, igual que hoy. Una notificación que agotó sus reintentos y se
  reintenta a mano vuelve a FAILED si el intento manual falla de forma recuperable; así un operador
  no puede, sin querer, reabrir un ciclo completo de reintentos automáticos con un solo clic.
  Alternativa descartada: reiniciar el presupuesto (convierte cada reintento manual en hasta N
  intentos). Afecta a: FR-012, Edge Cases.
- Q: ¿Qué recibe el operador cuando su reintento pierde una carrera con otro reintento (manual o
  automático) sobre la misma notificación? → A: **400**, igual que un estado que no admite
  reintento: cuando la solicitud se resuelve, la notificación ya volvió a PENDING. El contrato no
  declara 409 y se sigue al pie de la letra. Afecta a: FR-010, Edge Cases, SC-003.
- Q: ¿Dónde queda registrado que un reintento fue manual: solo en el intento de entrega que
  produce, o además como un registro propio de "solicitud de reintento"? → A: **En el intento de
  entrega** (su origen pasa a MANUAL). El histórico de intentos ya expone el origen y no hace falta
  un campo ni un evento nuevo. Consecuencia aceptada: si el despacho del reintento no llega a
  producir un intento (por ejemplo, el canal quedó deshabilitado y el mensaje termina en la cola de
  mensajes muertos), el histórico no muestra el reintento manual; el mensaje muerto sí lo conserva.
  Afecta a: FR-007, Key Entities.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Forzar un nuevo intento sobre una notificación fallida (Priority: P1)

Un operador ve en el histórico que una notificación de su tenant terminó en FAILED (por ejemplo,
el proveedor la rechazó mientras su configuración estaba mal) o que está en RECOVERABLE esperando
el próximo reintento automático. Una vez corregida la causa, el operador pide un nuevo intento sin
tener que reenviar la notificación desde el sistema cliente. El componente acepta la solicitud,
devuelve la notificación a la cola de despacho y el nuevo intento se ejecuta por el mismo camino
que cualquier otro despacho.

**Why this priority**: es el corazón de la historia. Sin él, una notificación FAILED es un callejón
sin salida y una RECOVERABLE solo avanza al ritmo del reintento automático.

**Independent Test**: provocar una notificación FAILED, pedir el reintento, y comprobar que la
respuesta es de aceptación con estado PENDING y que la notificación termina entregada tras un
nuevo intento real contra el proveedor.

**Acceptance Scenarios**:

1. **Given** una notificación del tenant en FAILED, **When** el operador pide el reintento,
   **Then** recibe una aceptación (202) con el estado actual de la notificación en PENDING, y la
   notificación vuelve a intentarse ante el proveedor.
2. **Given** una notificación del tenant en RECOVERABLE, **When** el operador pide el reintento,
   **Then** recibe una aceptación (202) con estado PENDING y el intento se hace de inmediato, sin
   esperar el plazo del reintento automático.
3. **Given** una notificación reintentada manualmente, **When** el proveedor la acepta,
   **Then** su estado pasa a DELIVERED.
4. **Given** una notificación en PENDING, IN_PROCESS, DELIVERED o DISCARDED, **When** el operador
   pide el reintento, **Then** recibe un rechazo (400) que explica que el estado actual no admite
   reintento, y la notificación queda exactamente como estaba (ni estado ni intentos cambian, ni se
   encola nada).
5. **Given** un identificador que no existe, **When** el operador pide el reintento, **Then**
   recibe "no encontrada" (404).

---

### User Story 2 - Aislamiento por tenant en el reintento (Priority: P1)

El reintento solo actúa sobre notificaciones del tenant que lo solicita. Un tenant no puede
reintentar, ni siquiera descubrir, una notificación de otro tenant.

**Why this priority**: es una garantía de seguridad multi-tenant; su ausencia permitiría a un
cliente provocar envíos en nombre de otro.

**Independent Test**: con dos tenants, pedir el reintento de una notificación FAILED del tenant A
usando la identidad del tenant B; comprobar que la respuesta es idéntica a la de un identificador
inexistente y que la notificación del tenant A no cambia.

**Acceptance Scenarios**:

1. **Given** una notificación FAILED del tenant A, **When** el tenant B pide su reintento,
   **Then** recibe 404 con el mismo mensaje que para un identificador inexistente, y la
   notificación del tenant A sigue en FAILED, sin intentos nuevos y sin que llegue nada al
   proveedor.
2. **Given** la misma situación, **When** el tenant A pide su reintento, **Then** recibe 202
   (control positivo: el reintento sí funciona para el dueño).

---

### User Story 3 - Distinguir en la auditoría el reintento manual del automático (Priority: P2)

Al revisar el recorrido completo de una notificación, el operador distingue qué intentos fueron
disparados por una persona y cuáles por el propio componente (el despacho inicial y los
reintentos automáticos).

**Why this priority**: es la parte de trazabilidad (HU2-028). Depende de que exista el reintento
manual (US1), pero aporta valor propio a la auditoría.

**Independent Test**: tras un intento automático fallido y un reintento manual, consultar el
histórico de la notificación y comprobar que el primer intento figura como automático y el segundo
como manual.

**Acceptance Scenarios**:

1. **Given** una notificación cuyo primer intento (automático) falló y que luego se reintentó
   manualmente, **When** el operador consulta el histórico, **Then** ve dos intentos: el primero
   con origen AUTOMATIC y el segundo con origen MANUAL.
2. **Given** una notificación reintentada manualmente cuyo intento manual vuelve a fallar de forma
   recuperable, **When** el componente la reintenta después de forma automática, **Then** ese
   intento posterior queda con origen AUTOMATIC (la marca manual aplica solo al intento que pidió
   el operador).
3. **Given** cualquier notificación despachada sin intervención de un operador, **When** se consulta
   su histórico, **Then** todos sus intentos figuran como AUTOMATIC (el comportamiento existente no
   cambia).

---

### Edge Cases

- **Dos reintentos simultáneos sobre la misma notificación**: exactamente uno se acepta (202); el
  otro recibe 400 porque, al resolverse, la notificación ya no está en un estado que admita
  reintento. Se produce un único intento nuevo ante el proveedor.
- **Reintento manual mientras el reintento automático toma la misma notificación RECOVERABLE**:
  gana uno solo; si gana el automático, el operador recibe 400 (la notificación ya volvió a PENDING)
  y el intento resultante figura como AUTOMATIC; si gana el manual, el automático la omite.
- **Reintento manual de una notificación que agotó sus reintentos automáticos**: se acepta. Si el
  intento manual vuelve a fallar de forma recuperable, la notificación regresa a FAILED (el
  presupuesto de reintentos automáticos sigue agotado; el manual no lo reinicia).
- **Reintento manual de una notificación RECOVERABLE con presupuesto restante**: si el intento
  manual falla de forma recuperable, la notificación queda RECOVERABLE y el reintento automático
  continúa; el intento manual cuenta dentro del presupuesto.
- **Canal deshabilitado o sin proveedor disponible al momento del reintento**: la solicitud de
  reintento se acepta igual (el despacho es asíncrono); el intento posterior se comporta
  exactamente como cualquier despacho con el canal deshabilitado.
- **Identificador con forma inesperada** (por ejemplo, no tiene forma de UUID): no corresponde a
  ninguna notificación y responde 404, igual que la consulta de estado hoy.
- **Falta el encabezado de tenant**: la solicitud se rechaza como cualquier otra operación que lo
  exige.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El componente MUST exponer la operación pública de reintento manual de una
  notificación, identificada por su identificador y por el tenant solicitante, tal como la declara
  el contrato de la API.
- **FR-002**: El reintento MUST aceptarse solo si el estado actual de la notificación es FAILED o
  RECOVERABLE. La regla sale de la misma política de transiciones de estado que ya rige el
  dominio, no de una lista paralela.
- **FR-003**: Al aceptar el reintento, el componente MUST devolver la notificación al estado
  PENDING, persistir el cambio y encolarla para despacho asíncrono por el mismo camino que cualquier
  otro despacho, antes de responder.
- **FR-004**: La respuesta de aceptación MUST ser 202 con el estado de la notificación ya
  actualizado (PENDING), en la misma forma que la consulta de estado de una notificación.
- **FR-005**: Si el estado actual no admite reintento, el componente MUST responder 400 con un
  mensaje que indique el estado actual, sin modificar la notificación ni encolar nada.
- **FR-006**: Si la notificación no existe o pertenece a otro tenant, el componente MUST responder
  404 con el mismo mensaje en ambos casos, sin modificar la notificación ni encolar nada.
- **FR-007**: El intento de despacho que resulta de un reintento manual MUST registrarse con origen
  MANUAL en el histórico de intentos de la notificación.
- **FR-008**: Todo intento que no resulte de un reintento manual (despacho inicial, reintento
  automático, recuperación de notificaciones huérfanas) MUST seguir registrándose con origen
  AUTOMATIC.
- **FR-009**: El despacho de un reintento manual MUST reutilizar la misma lógica de despacho
  existente (resolución de proveedor, clasificación del resultado, política de reintentos,
  publicación de eventos), sin duplicarla.
- **FR-010**: Ante solicitudes concurrentes sobre la misma notificación (dos reintentos manuales, o
  uno manual y el automático), el componente MUST aceptar a lo sumo una y producir a lo sumo un
  intento nuevo; la solicitud manual que pierda MUST recibir 400.
- **FR-011**: El histórico de intentos consultable (búsqueda de notificaciones) MUST exponer el
  origen de cada intento, de modo que el operador distinga manual de automático.
- **FR-012**: Un intento manual MUST contar dentro del presupuesto de reintentos recuperables de la
  notificación; el reintento manual no reinicia ese presupuesto.
- **FR-013**: La descripción de la operación en el contrato de la API MUST reflejar que la
  operación está implementada.

### Key Entities

- **Notificación**: agregado con estado (PENDING, IN_PROCESS, DELIVERED, RECOVERABLE, FAILED,
  DISCARDED) e histórico de intentos. El reintento manual solo la mueve de FAILED o RECOVERABLE a
  PENDING.
- **Intento de entrega**: registro inmutable de cada intento ante un proveedor: momento, resultado
  (aceptado, fallo recuperable, fallo permanente), proveedor y **origen** (MANUAL o AUTOMATIC). El
  origen ya existe en el modelo; esta historia hace que MANUAL se use de verdad.
- **Solicitud de despacho**: la unidad de trabajo encolada para despachar una notificación. Ahora
  debe llevar consigo el origen del intento que va a producir.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Una notificación FAILED reintentada por el operador, con el proveedor ya disponible,
  termina entregada en **5 segundos o menos** desde la aceptación del reintento.
- **SC-002**: El 100 % de los intentos producidos por un reintento manual figuran como MANUAL en el
  histórico, y el 100 % de los producidos sin intervención del operador figuran como AUTOMATIC.
- **SC-003**: Dos solicitudes de reintento simultáneas sobre la misma notificación producen
  exactamente **una** aceptación y exactamente **un** intento nuevo ante el proveedor.
- **SC-004**: Ninguna solicitud de reintento de un tenant cambia el estado, los intentos ni provoca
  llamadas al proveedor sobre notificaciones de otro tenant (0 cruces), y la respuesta es
  indistinguible de la de un identificador inexistente.
- **SC-005**: Ninguna solicitud de reintento sobre un estado que no lo admite modifica la
  notificación ni provoca llamadas al proveedor (0 efectos secundarios).

## Assumptions

- El operador actúa con la identidad de tenant del encabezado provisional `X-Tenant-Id`, igual que
  el resto de la API, mientras la autenticación real sigue bloqueada por su dependencia externa. No
  hay un rol de operador distinto del sistema cliente en esta historia.
- El reintento manual es asíncrono: la respuesta confirma que la notificación volvió a la cola, no
  el resultado del nuevo intento. El resultado se consulta con la consulta de estado o el histórico.
- El histórico de intentos ya se expone con su origen en la búsqueda de notificaciones y en las
  actualizaciones en tiempo real; esta historia no agrega campos nuevos a esas respuestas.
- Los mensajes de despacho ya encolados antes del despliegue de esta historia no llevan origen; se
  tratan como AUTOMATIC.
- La recuperación de notificaciones que quedan en PENDING sin mensaje en la cola cubre hoy solo las
  que nunca tuvieron intentos. Si el encolado falla justo después de persistir el reintento, la
  notificación queda en PENDING sin mensaje, igual que ya ocurre con el reintento automático; ese
  hueco preexistente se documenta en el plan como pendiente explícito, no se resuelve en esta
  historia.

## Out of Scope

- Reintento manual en lote (varias notificaciones en una sola solicitud).
- Forzar un proveedor distinto al preferido del catálogo en el reintento.
- Reiniciar el presupuesto de reintentos automáticos.
- Registrar quién (qué persona) pidió el reintento: sin autenticación real no hay identidad de
  persona disponible.
