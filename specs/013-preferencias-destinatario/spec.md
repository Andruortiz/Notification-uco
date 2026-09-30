# Feature Specification: Gestionar preferencias del destinatario y excluir bajas al despachar

**Feature Branch**: `feature/HU2-029-preferencias-destinatario`

**Created**: 2026-09-26

**Status**: Draft

**Input**: User description: "HU2-029 — Como destinatario final, quiero declarar por qué canales acepto ser
contactado o darme de baja total. HU2-030 — Como componente, quiero que el despacho consulte las
preferencias del destinatario antes de enviar, para no contactar a quien ya se dio de baja de ese canal."

**Trazabilidad**: CU-09 · HU2-029 (gestionar preferencias) + HU2-030 (excluir bajas en el despacho, su
complemento). Las dos historias se especifican juntas porque una sin la otra no tiene valor observable:
guardar preferencias que nadie consulta no protege al destinatario, y consultar preferencias que nadie
puede declarar no excluye a nadie.

## Clarifications

### Session 2026-09-26 — respuestas recomendadas, pendientes de revisión del usuario

No fue posible consultar al usuario durante la redacción. Cada pregunta se registra con la respuesta
recomendada y la historia avanza con ella, como en historias anteriores; si el usuario cambia alguna al
revisar, se indica qué partes afecta.

- **Q1 — ¿En qué momento se aplica la preferencia del destinatario: al aceptar la notificación, en el
  despacho antes de marcarla en proceso, o en el despacho ampliando las reglas de estado para poder
  descartar una notificación ya en proceso?** (FR-007, FR-013) Por qué importa: la regla de transiciones
  vigente solo permite PENDING → DISCARDED; una notificación EN PROCESO no se puede descartar.
  - **Respuesta recomendada**: **(b) en el despacho, antes de marcar la notificación en proceso.** Al
    revisar el despacho actual, la notificación leída está PENDING y el paso a EN PROCESO ocurre en
    memoria justo antes de llamar al proveedor; la consulta de preferencias cabe antes de ese paso sin
    reordenar nada más. Así se cumple el texto literal de HU2-030 ("el despacho consulta antes de
    enviar"), el descarte es la transición PENDING → DISCARDED ya permitida y probada, y la preferencia
    se reevalúa en cada despacho, incluidos los reintentos (una baja declarada entre la aceptación y el
    envío también se respeta).
  - Alternativas descartadas: (a) en la aceptación — deja pasar las bajas declaradas entre la aceptación
    y el despacho o antes de un reintento, y cambia la respuesta de aceptación; (c) permitir
    EN PROCESO → DISCARDED — modifica una regla de dominio ya establecida sin necesidad, porque (b) es
    viable.
  - Afecta a: FR-007, FR-008, FR-011, FR-012, FR-013, User Story 2, User Story 4.
- **Q2 — ¿Qué significa una lista de canales aceptados vacía o ausente cuando no hay baja total: "acepta
  todos los canales" o "no acepta ninguno"?** (FR-003) Por qué importa: define cómo se representa el
  valor por defecto y si existen dos formas de decir "no me contacten".
  - **Respuesta recomendada**: **vacía o ausente = sin restricción (acepta todos).** Es la misma forma que
    devuelve la consulta de un destinatario sin registro, así que guardar lo consultado no cambia nada, y
    "no me contacten por ningún canal" tiene una única forma: la baja total.
  - Alternativa descartada: vacía = ninguno y ausente = todos — obliga a distinguir ausente de vacío en
    cada cliente y duplica el significado de la baja total.
  - Afecta a: FR-002, FR-003, FR-004, FR-008, User Story 1, Edge Cases.
- **Q3 — ¿Se rechaza una actualización que nombra un canal que el catálogo no declara?** (FR-005) Por qué
  importa: un error tipográfico restringe al destinatario a un canal inexistente.
  - **Respuesta recomendada**: **no se valida contra el catálogo; se normaliza y se guarda.** La preferencia
    es del destinatario y debe sobrevivir a que un canal se deshabilite o se declare más tarde, y el
    catálogo puede variar por tenant y en el tiempo. El riesgo del error tipográfico queda anotado en
    Risks y mitigado porque la respuesta devuelve lo guardado.
  - Alternativa descartada: rechazar canales no activos en el catálogo — rechazaría preferencias válidas
    sobre canales deshabilitados temporalmente y acopla esta historia a la consulta del catálogo.
  - Afecta a: FR-005, Edge Cases, Out of Scope, Risks.
- **Q4 — Si las preferencias no se pueden leer durante el despacho, ¿se envía igual, se descarta o se
  deja la notificación sin tocar para reintentar?** (FR-010) Por qué importa: equilibra no perder
  notificaciones con no contactar a quien se dio de baja.
  - **Respuesta recomendada**: **no se envía ni se descarta; el despacho falla y la notificación sigue
    PENDING** para que el mecanismo de reintentos y la cola de mensajes muertos existentes la recuperen.
    Enviar sin comprobar podría contactar a alguien dado de baja; descartar perdería una notificación
    legítima por un fallo de infraestructura (Principio VIII).
  - Afecta a: FR-010, SC-007, Edge Cases.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El destinatario declara por qué canales acepta ser contactado o se da de baja total (Priority: P1)

Como destinatario final (a través del sistema cliente que lo representa), quiero consultar mis
preferencias de contacto vigentes y reemplazarlas por otras: aceptar solo ciertos canales, volver a
aceptarlos todos o darme de baja total, para decidir por dónde se me puede contactar.

**Why this priority**: Es la mitad declarativa de CU-09. Sin ella no existe ninguna forma de expresar una
baja, y la exclusión en el despacho (User Story 2) no tendría qué consultar.

**Independent Test**: Consultar las preferencias de un destinatario que nunca las declaró y comprobar que
acepta todos los canales; reemplazarlas por "solo EMAIL", consultarlas de nuevo y comprobar que se
devuelve exactamente lo guardado con su fecha de actualización; reemplazarlas por una baja total y
comprobar lo mismo.

**Acceptance Scenarios**:

1. **Given** un destinatario sin preferencias guardadas, **When** se consultan sus preferencias, **Then**
   la respuesta indica que no está dado de baja, que acepta todos los canales (lista de canales aceptados
   vacía, que significa "sin restricción") y que nunca se actualizaron (sin fecha de actualización). No es
   un error.
2. **Given** un destinatario cualquiera, **When** se reemplazan sus preferencias por "acepta solo EMAIL",
   **Then** la respuesta de la actualización y cualquier consulta posterior devuelven "no dado de baja,
   acepta EMAIL" con la fecha de esa actualización.
3. **Given** un destinatario que acepta solo EMAIL, **When** se reemplazan sus preferencias por una baja
   total, **Then** la consulta devuelve "dado de baja" y la lista de canales aceptados vacía.
4. **Given** un destinatario dado de baja, **When** se reemplazan sus preferencias con baja total en falso
   y sin lista de canales, **Then** vuelve a aceptar todos los canales.
5. **Given** una actualización con nombres de canal en minúsculas o repetidos (por ejemplo `email`,
   `EMAIL`, ` sms `), **When** se guarda, **Then** se almacena y devuelve cada canal una sola vez, sin
   espacios y en mayúsculas (`EMAIL`, `SMS`).
6. **Given** una actualización cuya lista de canales contiene una entrada vacía o solo espacios, **When**
   se envía, **Then** se rechaza con un motivo explícito y las preferencias vigentes no cambian.

---

### User Story 2 - El despacho no contacta a quien se dio de baja (Priority: P1)

Como componente, quiero que el despacho consulte las preferencias del destinatario antes de enviar y
descarte la notificación cuando el destinatario se dio de baja total o no acepta el canal de esa
notificación, para no contactar nunca a quien pidió no ser contactado por ese medio.

**Why this priority**: Es la garantía que da sentido a CU-09 y la que protege al destinatario. Una baja
guardada pero ignorada en el despacho es peor que no ofrecer la baja.

**Independent Test**: Declarar una baja total para un destinatario, enviar una notificación para él y
comprobar que termina descartada sin que ningún proveedor reciba una petición; en la misma prueba, enviar
una notificación para un destinatario sin restricciones y comprobar que sí se entrega (control positivo).

**Acceptance Scenarios**:

1. **Given** un destinatario dado de baja total, **When** se acepta y despacha una notificación para él por
   cualquier canal, **Then** la notificación termina DISCARDED, ningún proveedor recibe una petición y no
   se registra ningún intento de entrega.
2. **Given** un destinatario que acepta solo EMAIL, **When** se despacha una notificación para él por SMS,
   **Then** termina DISCARDED sin petición al proveedor; **When** se despacha otra por EMAIL, **Then** se
   entrega normalmente.
3. **Given** un destinatario sin preferencias guardadas, **When** se despacha una notificación para él,
   **Then** se entrega exactamente igual que antes de esta historia.
4. **Given** un destinatario dado de baja, **When** se acepta una notificación para él, **Then** la
   aceptación responde igual que para cualquier otro destinatario (aceptada, PENDING): la exclusión ocurre
   en el despacho, no en la aceptación.
5. **Given** una notificación descartada por preferencias, **When** se consulta su estado, su histórico o
   el flujo en tiempo real, **Then** aparece como DISCARDED, igual que cualquier otro cambio de estado.

---

### User Story 3 - Las preferencias de un tenant no cruzan a otro (Priority: P1)

Como responsable de la plataforma, quiero que las preferencias se guarden y apliquen por destinatario
dentro de cada tenant, para que la baja de un destinatario declarada por un sistema cliente no silencie
ni revele nada de otro sistema cliente que use el mismo identificador de destinatario.

**Why this priority**: El aislamiento multi-tenant es una garantía transversal (RNF-14). Dos sistemas
clientes pueden usar identificadores de destinatario iguales para personas distintas.

**Independent Test**: Con dos tenants y el mismo identificador de destinatario, declarar una baja total
en el primero y comprobar que (a) la consulta en el segundo devuelve los valores por defecto y (b) una
notificación del segundo tenant para ese identificador se entrega, mientras que la del primero se
descarta.

**Acceptance Scenarios**:

1. **Given** el destinatario `r-1` dado de baja en el tenant A, **When** el tenant B consulta las
   preferencias de `r-1`, **Then** recibe los valores por defecto (acepta todo, sin fecha).
2. **Given** el mismo escenario, **When** se despacha una notificación del tenant B para `r-1`, **Then** se
   entrega; y una del tenant A para `r-1` termina DISCARDED.
3. **Given** el tenant B actualiza las preferencias de `r-1`, **When** el tenant A las consulta, **Then**
   sigue viendo su propia baja, sin cambios.

---

### User Story 4 - Un cambio de preferencias rige desde el siguiente despacho (Priority: P2)

Como destinatario, quiero que mi baja se respete también en las notificaciones que ya se habían aceptado
pero todavía no se enviaron (pendientes o en espera de reintento), y que volver a aceptar un canal
reanude los envíos futuros, para que mi decisión tenga efecto inmediato.

**Why this priority**: Refina el momento en que la preferencia se aplica. Es P2 porque User Story 2 ya
cubre el caso principal (baja declarada antes de enviar).

**Independent Test**: Con una notificación aceptada y aún no despachada, declarar la baja y despacharla
después: termina DISCARDED. Retirar la baja y despachar una notificación nueva: se entrega.

**Acceptance Scenarios**:

1. **Given** una notificación aceptada y pendiente de despacho, **When** el destinatario se da de baja
   antes de que se despache, **Then** al despacharse termina DISCARDED.
2. **Given** una notificación que falló de forma recuperable y vuelve a la cola para reintento, **When** el
   destinatario se da de baja antes del reintento, **Then** el reintento la descarta en vez de enviarla.
3. **Given** una notificación ya entregada o fallida, **When** el destinatario se da de baja, **Then** esa
   notificación no cambia: la baja no es retroactiva.
4. **Given** un destinatario que retiró su baja, **When** se despacha una notificación nueva, **Then** se
   entrega.

---

### Edge Cases

- **¿Qué pasa si nunca se guardaron preferencias?** Se consideran "acepta todos los canales, no dado de
  baja", tanto en la consulta como en el despacho. Nunca es un error ni una baja.
- **¿Qué pasa si la baja total viene junto con una lista de canales?** La baja total prevalece: la lista
  se ignora y se guarda vacía.
- **¿Qué pasa si la lista de canales viene vacía o ausente sin baja total?** Significa "sin restricción":
  el destinatario acepta todos los canales (Q2).
- **¿Qué pasa si la lista nombra un canal que no existe en el catálogo?** Se guarda tal cual, normalizado;
  no restringe nada que exista hoy y aplica si ese canal se declara después (Q3).
- **¿Qué pasa si el nombre del canal de la notificación y el de la preferencia difieren solo en
  mayúsculas?** Se consideran el mismo canal, igual que el catálogo ya los trata.
- **¿Qué pasa si no se pueden leer las preferencias durante el despacho (almacén no disponible)?** No se
  envía: el despacho falla de forma trazable, la notificación sigue PENDING y entra en el mecanismo de
  reintento y cola de mensajes muertos existente. Nunca se contacta a alguien sin haber comprobado su
  preferencia.
- **¿Qué pasa si el mismo mensaje de despacho se reentrega después de descartar la notificación?** Se
  comporta como cualquier reentrega de una notificación ya terminal: la transición no es válida, no se
  contacta a nadie y el mensaje sigue el camino de reintentos y cola de mensajes muertos existente.
- **¿Qué pasa si dos actualizaciones de preferencias del mismo destinatario llegan a la vez?** Cada
  actualización reemplaza el estado completo; gana la última en escribirse y nunca queda un estado mezcla
  de ambas.
- **¿Qué pasa si el destinatario se da de baja mientras su notificación se está enviando?** El envío en
  curso no se interrumpe; la baja rige desde el siguiente despacho (Q1).
- **¿Qué pasa si la notificación pertenece a un canal cuyo catálogo no está disponible y además el
  destinatario está dado de baja?** Se descarta: la preferencia se consulta antes que la ruta del canal,
  porque no contactar al destinatario no depende de que el canal exista.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE permitir consultar las preferencias vigentes de un destinatario dentro del
  tenant solicitante, identificado por su identificador estable de destinatario.
- **FR-002**: Un destinatario sin preferencias guardadas DEBE comportarse, en la consulta y en el
  despacho, como "no dado de baja y acepta todos los canales". La consulta NO DEBE fallar por falta de
  registro y DEBE indicar que nunca se actualizaron.
- **FR-003**: El sistema DEBE permitir reemplazar por completo las preferencias de un destinatario con:
  indicador de baja total (falso por defecto) y lista de canales aceptados (vacía o ausente = sin
  restricción). La respuesta DEBE devolver las preferencias tal como quedaron guardadas, con su fecha de
  actualización.
- **FR-004**: Con baja total, la lista de canales aceptados DEBE ignorarse y guardarse vacía.
- **FR-005**: Cada nombre de canal DEBE normalizarse (sin espacios alrededor, en mayúsculas) y
  deduplicarse. Una entrada vacía o solo con espacios DEBE rechazar la actualización completa con un
  motivo explícito, sin modificar las preferencias vigentes.
- **FR-006**: Las preferencias DEBEN guardarse y aplicarse por la pareja (tenant, destinatario). Nada que
  un tenant consulte, guarde o provoque en el despacho puede depender de las preferencias de otro tenant.
- **FR-007**: Antes de intentar el envío de una notificación, el despacho DEBE consultar las preferencias
  vigentes del destinatario de esa notificación en su tenant.
- **FR-008**: Si el destinatario está dado de baja total, o su lista de canales aceptados no está vacía y
  no incluye el canal de la notificación (comparado sin distinguir mayúsculas), el despacho DEBE marcar la
  notificación como DISCARDED, persistirla, publicar el cambio como cualquier otro cambio de estado y NO
  DEBE llamar a ningún proveedor ni registrar un intento de entrega.
- **FR-009**: Si el destinatario acepta el canal de la notificación, el despacho DEBE continuar
  exactamente como antes de esta historia.
- **FR-010**: Si las preferencias no se pueden leer durante el despacho, el sistema NO DEBE enviar la
  notificación NI descartarla: el despacho DEBE fallar de forma trazable y la notificación DEBE conservar
  su estado para que el mecanismo de reintentos existente la recupere.
- **FR-011**: La preferencia DEBE evaluarse en cada despacho, incluidos los reintentos, con el valor
  vigente en ese momento. Un cambio de preferencias NO DEBE alterar notificaciones ya terminales.
- **FR-012**: La aceptación de notificaciones (individual y en lote) NO DEBE cambiar: una notificación para
  un destinatario dado de baja se acepta como cualquier otra y se descarta en el despacho.
- **FR-013**: El descarte por preferencias NO DEBE requerir ninguna regla nueva de transición de estado:
  debe ocurrir mientras la notificación aún está PENDING.
- **FR-014**: Dos actualizaciones concurrentes de las preferencias de un mismo destinatario NO DEBEN
  producir un registro duplicado ni un estado mezcla; el resultado DEBE ser exactamente una de las dos.
- **FR-015**: Las respuestas y los registros NO DEBEN exponer datos de otro tenant ni la dirección
  concreta del destinatario (correo, teléfono, identificador de dispositivo); las preferencias solo
  contienen el identificador estable del destinatario y nombres de canal.

### Key Entities *(include if feature involves data)*

- **Preferencias del destinatario**: estado de consentimiento de un destinatario dentro de un tenant.
  Atributos: tenant, identificador del destinatario, baja total (sí/no), conjunto de canales aceptados
  (vacío = sin restricción), fecha de la última actualización. Un único registro por (tenant,
  destinatario); no conserva historial de cambios.
- **Notificación**: entidad existente. Esta historia usa su estado DISCARDED, ya definido, como resultado
  del descarte por preferencias.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de las consultas de preferencias de destinatarios sin registro devuelven "acepta
  todo, no dado de baja, sin fecha de actualización", sin error.
- **SC-002**: El 100 % de las actualizaciones válidas quedan visibles, normalizadas, en la consulta
  inmediatamente posterior; una actualización con una entrada de canal vacía se rechaza y deja las
  preferencias anteriores intactas.
- **SC-003**: Una notificación para un destinatario dado de baja total, aceptada por la API, queda
  DISCARDED en 5 segundos o menos desde su aceptación, con cero peticiones a cualquier proveedor y cero
  intentos de entrega registrados, verificado automáticamente con una aserción explícita de duración.
- **SC-004**: Con un destinatario que acepta solo EMAIL, una notificación por SMS termina DISCARDED y una
  por EMAIL termina DELIVERED, en la misma prueba automatizada (control positivo).
- **SC-005**: Con dos tenants y el mismo identificador de destinatario, una baja en un tenant produce cero
  efectos en el otro: su consulta devuelve los valores por defecto y su notificación se entrega,
  verificado automáticamente.
- **SC-006**: Una notificación aceptada antes de una baja y despachada después termina DISCARDED; una
  notificación nueva tras retirar la baja se entrega.
- **SC-007**: Si las preferencias no se pueden leer durante el despacho, cero peticiones al proveedor y la
  notificación conserva su estado PENDING, verificado automáticamente.
- **SC-008**: Cero regresiones en la suite existente: los destinatarios sin preferencias se entregan igual
  que antes.
- **SC-009**: Diez actualizaciones concurrentes de las preferencias de un mismo destinatario dejan
  exactamente un registro, igual a una de las diez, verificado automáticamente.

## Out of Scope

- Historial o auditoría de cambios de consentimiento (solo se guarda el estado vigente).
- Preferencias por categoría de notificación, franjas horarias, frecuencia máxima o canal preferido.
- Enlaces de baja dentro del contenido enviado, o baja iniciada por el propio proveedor.
- Autenticación del destinatario o del sistema cliente que actúa en su nombre (CU-10, bloqueado por
  DEP-01): mientras tanto se usa el mismo encabezado de tenant provisional que el resto de la API.
- Rechazar en la aceptación una notificación para un destinatario dado de baja (Q1).
- Validar los nombres de canal de las preferencias contra el catálogo (Q3).
- Motivo de descarte guardado en la notificación: hoy DISCARDED solo se alcanza por preferencias.
- Borrar preferencias (volver al estado "sin registro"): el mismo efecto se obtiene guardando "sin baja y
  sin restricción".

## Assumptions

- El sistema cliente actúa en nombre del destinatario final: es quien llama a la API con el tenant y el
  identificador estable de destinatario que ya usa al enviar notificaciones.
- El identificador estable de destinatario ya existente es la clave de las preferencias; no se introduce
  un identificador nuevo.
- Los canales se comparan sin distinguir mayúsculas, igual que el catálogo.
- El contrato público de ambas operaciones ya está escrito; esta historia lo sigue y solo lo precisa donde
  hace falta (fecha de actualización ausente sin registro y respuesta de rechazo por entrada inválida).
- El descarte se publica por el mismo camino que cualquier otro cambio de estado, por lo que la consulta,
  el histórico y el flujo en tiempo real lo reflejan sin cambios propios.

## Risks

- **Sin historial de consentimiento**: no se puede demostrar cuándo se dio una baja anterior a la vigente.
  Dueño: andrualv. Fecha de revisión: 2026-12-31.
- **Sin autenticación del destinatario** (DEP-01): cualquier sistema con el encabezado de tenant puede
  modificar las preferencias de sus destinatarios, igual que hoy puede enviarles notificaciones. Dueño:
  andrualv. Fecha de revisión: al cerrar DEP-01.
- **Canal mal escrito en las preferencias** (Q3): un error tipográfico ("EMIAL") restringe al destinatario
  a un canal inexistente y descarta todo lo demás. Mitigado porque la respuesta devuelve lo guardado.
  Dueño: andrualv. Fecha de revisión, junto con la consulta del catálogo: 2026-11-30.
