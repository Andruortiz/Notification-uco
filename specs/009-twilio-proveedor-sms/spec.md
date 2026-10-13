# Feature Specification: Integrar Twilio como primer proveedor real de SMS

**Feature Branch**: `009-twilio-proveedor-sms`

**Created**: 2026-09-24

**Status**: Plan aceptado — Q1–Q4 de Clarifications confirmadas por el usuario (2026-09-24)

**Input**: User description: "Como sistema cliente, quiero que mis notificaciones por SMS se entreguen a
través de Twilio, para que el canal SMS esté disponible."

**Trazabilidad**: CU-03 · RF-08, RF-10, RNF-08, RNF-09 · mismo patrón que HU2-089 (proveedor real de
correo).

## Clarifications

### Session 2026-09-24 — confirmadas (2026-09-24)

Las cuatro preguntas siguientes se identificaron en la revisión de ambigüedad. No fue posible
consultarlas durante la redacción, así que cada una se registró con una respuesta recomendada, y esa
respuesta queda **confirmada** tal cual por el usuario al aprobar el plan (ver `plan.md § Estado del
plan`); si alguna cambia en el futuro, se indica qué partes afecta.

- **Q1 — ¿El canal SMS define su propia forma de contenido, y qué ocurre con un asunto que el cliente
  envía en una notificación SMS?** Un SMS no tiene asunto; el contrato público ya declara el asunto como
  opcional y dice que "algunos canales (ej. SMS) no lo usan". El canal de correo hoy no declara ninguna
  forma de contenido.
  - **Respuesta confirmada por el usuario**: el canal SMS declara **su propia forma de contenido** en el catálogo:
    cuerpo obligatorio con longitud máxima (ver Q2) y asunto **admitido pero ignorado** — no se envía al
    proveedor ni se antepone al cuerpo. Es el comportamiento que el contrato público ya anuncia, así que
    no es un descarte silencioso.
  - Alternativas descartadas: (a) rechazar en la aceptación una notificación SMS con asunto — más
    estricta, pero rompe a clientes que envían la misma carga a varios canales y contradice el texto
    actual del contrato; (b) anteponer el asunto al cuerpo — altera lo que el cliente escribió y consume
    longitud sin que el cliente lo vea; (c) reutilizar la forma del canal de correo — hoy está vacía, así
    que el canal SMS quedaría sin límite de longitud.
  - Afecta a: FR-012, User Story 4, Key Entities.
- **Q2 — ¿Cuál es el límite de longitud del cuerpo de un SMS y qué se hace con una notificación que lo
  excede?** Un SMS largo se fragmenta; cada fragmento se cobra aparte. Un fragmento admite 160
  caracteres del alfabeto básico de SMS, pero solo 70 si el texto trae caracteres fuera de él (en
  español, á, í, ó, ú; también emojis), y los mensajes de varios fragmentos pierden unos caracteres por
  fragmento en la cabecera de unión.
  - **Respuesta confirmada por el usuario**: **rechazar en la aceptación, nunca truncar**, con un límite de **160
    caracteres** declarado en la forma de contenido del canal SMS (editable por entorno en el catálogo,
    sin tocar código). El cliente recibe el rechazo de inmediato con un motivo que nombra el límite; nada
    se persiste ni llega al proveedor. Costo resultante documentado: 1 fragmento para texto del alfabeto
    básico, hasta 3 si el texto trae tildes agudas u otros caracteres fuera de él, y hasta 5 en el caso
    extremo de un cuerpo hecho solo de emojis (cada emoji ocupa el doble en la codificación extendida).
  - Alternativas descartadas: (a) truncar a 160 — entrega un mensaje que el cliente no escribió, sin
    avisarle; (b) límite de 70 — garantiza un fragmento siempre, pero es demasiado corto para texto en
    español; (c) límite calculado en fragmentos según la codificación — acota el costo con exactitud,
    pero exige lógica de codificación propia que no existe y excede esta historia; (d) sin límite propio,
    solo el máximo del proveedor (1600 caracteres) — hasta 11 fragmentos por notificación sin que nadie
    lo haya decidido.
  - Afecta a: FR-011, SC-008, User Story 4, Edge Cases.
- **Q3 — ¿Dónde se valida que el destinatario de un SMS sea un número telefónico en formato
  internacional?** Hoy el destinatario es un texto libre sin validación por canal; el proveedor exige el
  formato internacional (signo `+`, código de país y número, hasta 15 dígitos).
  - **Respuesta confirmada por el usuario**: **en el despacho, antes de llamar al proveedor**. Si el número no tiene
    formato internacional, el intento se registra como **fallo permanente sin llamar al proveedor**,
    con un motivo que nombra el dato (no su valor). Es el mismo patrón que el proveedor real de correo
    aplica a una notificación sin asunto.
  - Alternativas descartadas: (a) validar en la aceptación — es lo ideal para el cliente, pero exige que
    el núcleo valide el destinatario según el canal, un cambio de modelo que excede esta historia y se
    anota como mejora; (b) dejarlo al proveedor — el resultado final es el mismo fallo permanente, pero
    gasta una llamada y depende de un mensaje de error que puede traer el número completo.
  - Afecta a: FR-013, SC-011, User Story 4, Edge Cases.
- **Q4 — ¿La clasificación de la respuesta del proveedor usa solo el código de respuesta HTTP o también
  el código de error propio del proveedor?** El proveedor devuelve el mismo código HTTP de "petición
  rechazada" para causas muy distintas: número inválido, número dado de baja, número no verificado en
  una cuenta de prueba, y también alguna condición transitoria del número de origen (por ejemplo, cola
  de envío llena).
  - **Respuesta confirmada por el usuario**: **solo el código HTTP decide la categoría**, igual que con el proveedor
    real de correo. El código de error numérico del proveedor se **registra** para el diagnóstico (y
    distingue baja de número inválido en los registros), pero no cambia la categoría. Consecuencia
    aceptada: una condición transitoria que el proveedor comunique como "petición rechazada" termina en
    fallo permanente; es recuperable a mano porque la política de estados admite volver de fallida a
    pendiente, y se revisa junto con el límite de tasa por proveedor (HU2-039).
  - Alternativa descartada: una lista de códigos de error del proveedor tratados como recuperables —
    más precisa, pero acopla la clasificación a un catálogo externo de códigos y obliga a mantenerlo.
  - Afecta a: FR-006, FR-008, User Story 3, Risks.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Las notificaciones por SMS se entregan de verdad (Priority: P1)

Como sistema cliente, quiero enviar una notificación por el canal SMS y que termine llegando al teléfono
del destinatario a través del proveedor real de SMS, para contar con un canal que hoy el componente no
ofrece.

**Why this priority**: Es la razón de ser de la historia. Hoy el catálogo solo declara el canal de
correo: una notificación SMS se rechaza en la aceptación porque el canal no existe. Sin esto no hay
canal SMS.

**Independent Test**: Con el proveedor real declarado como preferente del canal SMS y sus credenciales
presentes, aceptar una notificación SMS y confirmar que (a) el proveedor real recibió una petición de
envío con el número del destinatario, el número de origen configurado y el cuerpo de esa notificación,
(b) la notificación quedó entregada y (c) el intento de entrega quedó anotado con el identificador del
proveedor real.

**Acceptance Scenarios**:

1. **Given** el canal SMS con el proveedor real como preferente y credenciales válidas, **When** se
   acepta y despacha una notificación SMS, **Then** el proveedor real recibe exactamente una petición de
   envío con el número del destinatario, el número de origen configurado y el cuerpo de esa
   notificación, y la notificación queda entregada con el identificador del proveedor real en su
   intento.
2. **Given** el canal SMS con el proveedor simulado como preferente, **When** se despacha una
   notificación SMS, **Then** el proveedor real no recibe ninguna petición y la notificación se entrega
   por el simulado, igual que cualquier otro canal hoy.
3. **Given** una notificación SMS, **When** se construye la petición de envío, **Then** el número de
   origen es el configurado por entorno y no un valor fijo en el código.
4. **Given** el catálogo sembrado desde cero con la configuración versionada, **When** se consulta la
   ruta del canal SMS, **Then** el canal existe y lista al proveedor real entre sus proveedores.

---

### User Story 2 - Sin credenciales el proveedor se deshabilita con un motivo visible (Priority: P1)

Como responsable de la operación, quiero que un despliegue sin las credenciales del proveedor real de
SMS deje constancia explícita de que está deshabilitado y por qué, para enterarme al arrancar y no al
descubrir que nadie recibió sus mensajes.

**Why this priority**: Misma garantía de seguridad operativa que ya tiene el proveedor real de correo.
Es la condición que permite listar el proveedor en el catálogo antes de que existan credenciales en
todos los entornos, incluidos desarrollo local e integración continua.

**Independent Test**: Arrancar el servicio sin alguna de las tres credenciales (identificador de
cuenta, token de autenticación, número de origen) y confirmar que (a) el arranque se completa, (b)
queda un registro explícito que nombra al proveedor y la variable ausente, (c) ese registro no contiene
ningún valor de credencial y (d) una notificación dirigida a ese proveedor no se da por entregada, no
se pierde y deja un rastro consultable.

**Acceptance Scenarios**:

1. **Given** un despliegue sin el identificador de cuenta, sin el token de autenticación o sin el número
   de origen, **When** el servicio arranca, **Then** el arranque se completa y queda constancia explícita
   de que el proveedor está deshabilitado, nombrando cuál falta y sin revelar ningún valor.
2. **Given** un despliegue con un número de origen o un identificador de cuenta con formato inválido,
   **When** el servicio arranca, **Then** el comportamiento es el mismo: deshabilitado con motivo
   explícito que nombra el dato mal formado, sin revelar su valor.
3. **Given** un despliegue deshabilitado y con el proveedor real como preferente del canal SMS, **When**
   se despacha una notificación SMS, **Then** la notificación no se marca como entregada ni como fallida
   por el proveedor, conserva su historial sin intentos nuevos y el fallo queda registrado con el motivo
   de deshabilitación.
4. **Given** un despliegue con las tres credenciales presentes y bien formadas, **When** el servicio
   arranca, **Then** el proveedor queda habilitado y no se emite ningún aviso de deshabilitación.

---

### User Story 3 - Los fallos del proveedor se clasifican y no se confunden entre sí (Priority: P2)

Como responsable de la operación, quiero que cada respuesta del proveedor real de SMS se traduzca a una
de las tres categorías que el componente ya entiende — aceptada, fallo recuperable o fallo permanente —
para que los reintentos se disparen solo cuando sirven.

**Why this priority**: Reintentar un número inexistente o dado de baja gasta cuota y termina igual; no
reintentar un fallo temporal pierde mensajes que habrían salido al segundo intento. Depende de la
entrega básica (P1).

**Independent Test**: Simular cada familia de respuesta del proveedor y confirmar que cada una produce
exactamente la categoría esperada y el estado de notificación correspondiente.

**Acceptance Scenarios**:

1. **Given** el proveedor habilitado, **When** el proveedor acepta el envío, **Then** el intento se
   registra como aceptado.
2. **Given** el proveedor habilitado, **When** el proveedor rechaza el envío por número de destino
   inválido, por número dado de baja o en lista de exclusión, o por petición malformada, **Then** el
   intento se registra como fallo permanente y la notificación no se reintenta.
3. **Given** el proveedor habilitado, **When** el proveedor rechaza el envío por credenciales inválidas,
   **Then** el intento se registra como fallo permanente y el motivo queda registrado sin revelar
   ninguna credencial.
4. **Given** el proveedor habilitado, **When** el proveedor responde con un fallo temporal propio o con
   un límite de tasa alcanzado, **Then** el intento se registra como fallo recuperable y la notificación
   entra en el camino de reintentos existente.
5. **Given** el proveedor habilitado, **When** el proveedor no responde dentro del tiempo de espera
   configurado, **Then** la llamada se corta en ese plazo y el intento se registra como fallo
   recuperable.
6. **Given** el proveedor habilitado, **When** no es posible establecer la conexión, **Then** el intento
   se registra como fallo recuperable.

---

### User Story 4 - El contenido de un SMS respeta la forma y el tamaño del canal (Priority: P2)

Como sistema cliente, quiero saber en el momento de enviar si mi notificación SMS no cabe en el canal,
para no pagar un mensaje fragmentado que no esperaba ni recibir un mensaje recortado.

**Why this priority**: Un SMS largo se fragmenta y cada fragmento se cobra aparte. Sin un límite
explícito, el costo por notificación no está acotado. Es P2 porque la entrega (P1) aporta valor aunque
el límite se ajuste después.

**Independent Test**: Enviar una notificación SMS cuyo cuerpo excede el límite del canal y confirmar que
se rechaza en la aceptación con un motivo que nombra el límite, sin quedar persistida y sin llegar al
proveedor; y enviar otra justo en el límite y confirmar que se acepta.

**Acceptance Scenarios**:

1. **Given** el canal SMS, **When** se envía una notificación con cuerpo de exactamente 160
   caracteres, **Then** se acepta.
2. **Given** el canal SMS, **When** se envía una notificación con cuerpo de 161 caracteres o más,
   **Then** se rechaza en la aceptación con un motivo que nombra el límite, no se persiste, no se
   trunca y el proveedor no recibe nada (Q2).
3. **Given** el canal SMS, **When** se envía una notificación que trae asunto, **Then** se acepta, y la
   petición al proveedor lleva solo el cuerpo: el asunto no se envía ni se antepone (Q1).
4. **Given** una notificación SMS cuyo destinatario no tiene formato de número telefónico internacional,
   **When** se despacha por el proveedor real, **Then** el intento se registra como fallo permanente,
   la notificación termina fallida y el proveedor no recibe ninguna petición (Q3).

---

### User Story 5 - Nada sensible sale del componente (Priority: P2)

Como responsable de seguridad, quiero que ni las credenciales del proveedor, ni el contenido del SMS, ni
el número completo del destinatario aparezcan en los registros ni en los mensajes internos, para que la
integración no abra una vía de fuga de datos personales.

**Why this priority**: Un número de teléfono es un dato personal directo. Es P2 solo porque no bloquea
la entrega; su incumplimiento sí bloquearía la puesta en producción.

**Independent Test**: Ejecutar un despacho completo por el proveedor real, capturar todos los registros
emitidos y los mensajes que el sistema publica internamente, y confirmar que en ninguno aparece el
valor de una credencial, el cuerpo del SMS ni el número completo del destinatario; el número puede
aparecer solo enmascarado, con a lo sumo sus últimos cuatro dígitos visibles.

**Acceptance Scenarios**:

1. **Given** un despacho completo por el proveedor real, **When** se revisan los registros, **Then** no
   aparece el identificador de cuenta, el token de autenticación ni ninguna forma derivada de ambos.
2. **Given** el mismo despacho, **When** se revisan los registros, **Then** no aparece el cuerpo del SMS
   y el número del destinatario aparece, si aparece, enmascarado con a lo sumo sus últimos cuatro dígitos
   visibles.
3. **Given** el mismo despacho, **When** se revisan los mensajes que el sistema publica internamente,
   **Then** no aparece ninguna credencial, ni el cuerpo, ni el número del destinatario.
4. **Given** un despacho que falla, **When** se revisa el registro del fallo, **Then** contiene
   identificador de notificación, tenant, proveedor, categoría del fallo y el código de error del
   proveedor si lo hay, y ninguno de los datos prohibidos.

---

### Edge Cases

- **¿Qué pasa si el proveedor real está listado pero deshabilitado?** La notificación no se entrega, no
  se marca como fallida por el proveedor y conserva su historial; el despacho falla de forma trazable con
  el motivo de deshabilitación. Error de configuración, no de entrega.
- **¿Qué pasa si el destinatario se dio de baja (respondió STOP) o está en una lista de exclusión?** El
  proveedor lo rechaza y se clasifica como fallo permanente: reintentar no cambia el resultado y
  reintentarlo sería insistir contra la voluntad del destinatario.
- **¿Qué pasa si el cuerpo contiene caracteres fuera del alfabeto básico de SMS (tildes como á, í, ó, ú,
  o emojis)?** El proveedor usa una codificación que reduce la capacidad de cada fragmento a 70
  caracteres (67 en mensajes de varios fragmentos): un cuerpo de 160 caracteres con tildes cuesta hasta
  3 fragmentos, y uno hecho solo de emojis hasta 5. Es el costo máximo aceptado con el límite por
  defecto (Q2); no se reescribe el texto del cliente.
- **¿Qué pasa si una notificación SMS trae asunto?** Se acepta y el asunto no viaja al proveedor (Q1).
- **¿Qué pasa si el cuerpo excede el límite del canal?** Rechazo inmediato en la aceptación, sin
  persistir y sin truncar (Q2).
- **¿Qué pasa si el destinatario no es un número en formato internacional (por ejemplo, un correo
  enviado por error al canal SMS)?** Fallo permanente en el despacho, sin llamar al proveedor (Q3).
- **¿Qué pasa si el proveedor comunica una condición transitoria con el código de "petición
  rechazada"?** Termina en fallo permanente, con el código de error del proveedor en el registro;
  recuperable a mano (Q4).
- **¿Qué pasa con una cuenta de prueba del proveedor?** Solo entrega a números verificados en la cuenta
  y antepone un texto propio al mensaje. Enviar a un número no verificado se rechaza y se clasifica como
  fallo permanente. Adecuado para la prueba de humo y la demo, no para producción.
- **¿Qué pasa si el proveedor acepta el envío pero el componente cae antes de registrar el resultado?**
  El mensaje se reentrega y el SMS puede enviarse dos veces. Riesgo residual documentado (ver Risks).
- **¿Qué pasa si el proveedor responde algo que no encaja en ninguna familia conocida?** Se clasifica de
  forma conservadora: rechazo atribuible a la petición → permanente; cualquier otro caso → recuperable.
- **¿Qué pasa en un entorno que ya tiene el catálogo sembrado?** La siembra solo actúa sobre un catálogo
  vacío: el canal SMS **no** aparece por sí solo en un entorno existente. Requiere un paso explícito de
  alta del canal en el catálogo, documentado.
- **¿Qué pasa si el número del destinatario tiene cuatro dígitos o menos?** Se enmascara por completo en
  los registros.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE disponer de un proveedor de envío dedicado al proveedor real de SMS, con su
  propio identificador estable, distinto del simulado y del proveedor real de correo.
- **FR-002**: El catálogo DEBE declarar el canal SMS y listar al proveedor real entre sus proveedores.
- **FR-003**: Las credenciales del proveedor (identificador de cuenta, token de autenticación y número de
  origen) DEBEN llegar exclusivamente por variables de entorno o un mecanismo equivalente de secretos. NO
  DEBEN estar en el código, en la configuración versionada, en los registros ni en los mensajes internos.
- **FR-004**: Si falta alguna de las tres credenciales, o el número de origen o el identificador de
  cuenta no tienen un formato válido, el proveedor DEBE quedar deshabilitado con un motivo explícito que
  nombre el dato afectado, y el servicio DEBE seguir arrancando. La deshabilitación NUNCA DEBE ser
  silenciosa.
- **FR-005**: Una notificación dirigida a un proveedor deshabilitado NO DEBE darse por entregada, NO DEBE
  marcarse como fallida por el proveedor y NO DEBE perderse; el despacho DEBE fallar de forma trazable
  nombrando el motivo, sin registrar intento de entrega.
- **FR-006**: El sistema DEBE traducir cada respuesta del proveedor a exactamente una de las tres
  categorías existentes. Como mínimo: aceptación → aceptada; tiempo agotado, imposibilidad de conectar,
  fallo temporal del proveedor y límite de tasa → fallo recuperable; número de destino inválido, número
  dado de baja o en lista de exclusión, petición malformada y credenciales inválidas → fallo permanente.
  La categoría la decide únicamente el código de respuesta del protocolo; el código de error propio del
  proveedor se registra pero no altera la categoría (Q4).
- **FR-007**: Toda llamada al proveedor DEBE tener un tiempo de espera explícito y configurable; una
  llamada que lo supere DEBE cortarse y clasificarse como fallo recuperable.
- **FR-008**: Los registros del proveedor NO DEBEN incluir el cuerpo del SMS, el número completo del
  destinatario ni ninguna credencial. El número, si se registra, DEBE ir enmascarado con a lo sumo sus
  últimos cuatro dígitos visibles. Los registros SÍ DEBEN incluir identificador de notificación, tenant,
  identificador de proveedor, categoría del resultado y, en un rechazo, el código de error numérico del
  proveedor; NUNCA el texto del mensaje de error del proveedor, que puede contener el número completo.
- **FR-009**: El intento de entrega DEBE quedar registrado con el identificador del proveedor real cuando
  sea ese proveedor el que ejecutó el envío.
- **FR-010**: La lista de proveedores del canal SMS DEBE poder configurarse por entorno sin modificar
  código, con un valor por defecto seguro para un entorno sin credenciales (el simulado como preferente),
  igual que el canal de correo.
- **FR-011**: El canal SMS DEBE declarar en el catálogo un límite de longitud del cuerpo de 160
  caracteres por defecto, modificable por entorno sin cambiar código. Una notificación SMS que lo exceda
  DEBE rechazarse en la aceptación con un motivo que nombre el límite; NUNCA DEBE truncarse (Q2).
- **FR-012**: El canal SMS DEBE declarar su propia forma de contenido: cuerpo obligatorio y asunto
  opcional que el proveedor real NO DEBE recibir ni anteponer al cuerpo (Q1).
- **FR-013**: Antes de llamar al proveedor, el sistema DEBE comprobar que el destinatario tiene formato
  telefónico internacional (signo `+` seguido de 2 a 15 dígitos, el primero distinto de cero); si no lo
  tiene, el intento DEBE registrarse como fallo permanente sin llamar al proveedor, con un motivo que
  nombre el dato y no su valor (Q3).
- **FR-014**: El proveedor simulado DEBE seguir disponible y operativo, y el proveedor real de correo
  DEBE seguir funcionando exactamente igual.
- **FR-015**: El sistema NO DEBE, en esta historia, aplicar límites de tasa propios, cortacircuitos, ni
  conmutación al siguiente proveedor, ni consumir confirmaciones de entrega asíncronas del proveedor.

### Key Entities *(include if feature involves data)*

- **Proveedor real de SMS**: nuevo proveedor de envío. Se identifica por un identificador estable propio
  y conoce un identificador de cuenta, un token de autenticación y un número de origen, inyectados por
  entorno. No persiste entidades nuevas.
- **Ruta del canal SMS**: nueva entrada del catálogo (entidad ya existente) con su lista de proveedores
  (por defecto: simulado, real) y su forma de contenido propia: cuerpo obligatorio de hasta 160
  caracteres, asunto opcional e ignorado por el proveedor real.
- **Resultado del intento**: entidad ya existente. Esta historia define cómo se deriva desde las
  respuestas del proveedor real de SMS.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el proveedor real habilitado y preferente, el 100 % de las notificaciones SMS
  despachadas producen exactamente una petición al proveedor con el número del destinatario, el número de
  origen y el cuerpo de esa notificación, y terminan entregadas con el identificador del proveedor real.
- **SC-002**: En un entorno sembrado desde cero, el canal SMS existe en el catálogo y lista al proveedor
  real.
- **SC-003**: En un arranque sin alguna credencial o con una mal formada, el 100 % de los arranques se
  completan y dejan exactamente un registro que nombra al proveedor y el dato afectado, sin su valor.
- **SC-004**: Cero apariciones de credenciales (en cualquier forma, incluida la combinada que se envía
  al proveedor), del cuerpo del SMS y del número completo del destinatario en los registros y en los
  mensajes internos durante un despacho completo, verificado automáticamente.
- **SC-005**: Cada familia de respuesta del proveedor (aceptación, número inválido, número dado de baja,
  credenciales inválidas, límite de tasa, fallo temporal, tiempo agotado, imposibilidad de conectar)
  produce la categoría esperada en el 100 % de los casos, verificado automáticamente.
- **SC-006**: Ninguna llamada al proveedor excede el tiempo de espera configurado; una respuesta que
  tarda más se corta dentro de ese plazo y se clasifica como recuperable, verificado con una aserción
  explícita de duración.
- **SC-007**: Con el valor por defecto de la configuración versionada y sin credenciales, cero regresiones
  en la suite existente y una notificación SMS se acepta y se entrega por el proveedor simulado.
- **SC-008**: Una notificación SMS de 161 caracteres o más produce un rechazo inmediato con un motivo que
  nombra el límite, cero notificaciones persistidas y cero peticiones al proveedor; una de exactamente
  160 caracteres se acepta.
- **SC-009**: Existe una prueba de punta a punta automatizada contra un servidor que simula al proveedor,
  y cero pruebas de la suite automatizada llaman al proveedor real.
- **SC-010**: Existe un procedimiento de prueba manual de humo con cuenta de prueba real, documentado y
  ejecutable fuera de la integración continua, con una plantilla para registrar su resultado.
- **SC-011**: Una notificación SMS con destinatario sin formato internacional termina en fallo permanente
  con cero peticiones al proveedor; una notificación SMS con asunto produce una petición al proveedor que
  no contiene el asunto.

## Out of Scope

- Batería de pruebas de contrato común a los adaptadores (HU2-038), gestión de secretos por la plataforma
  (HU2-052) y límite de tasa por proveedor (HU2-039).
- Cortacircuitos y conmutación al siguiente proveedor ante un fallo (HU2-048).
- Confirmaciones de entrega asíncronas del proveedor (entregado / no entregado al teléfono), mensajes
  entrantes, respuestas del destinatario y gestión propia de bajas.
- Identificadores de remitente alfanuméricos, servicios de mensajería del proveedor con grupos de
  números, MMS, WhatsApp u otros canales del mismo proveedor.
- Registro regulatorio de números para producción (por ejemplo A2P 10DLC en EE.UU.).
- Cambiar la estructura del contrato público de aceptación o el mecanismo de siembra y refresco del
  catálogo.

## Assumptions

- El usuario aporta una cuenta de prueba del proveedor con identificador de cuenta, token de
  autenticación y un número de origen, y al menos un número de destino verificado en esa cuenta. Sin
  ellos el proveedor queda deshabilitado (FR-004) y la historia se valida igualmente contra un servidor
  que lo simula.
- "Aceptada" significa que el proveedor asumió el envío, no que el teléfono lo recibió: es la misma
  semántica que ya tienen el simulado y el proveedor real de correo. La confirmación de entrega real es
  asíncrona y queda fuera de alcance.
- La entrega del componente es "al menos una vez". No se conoce un mecanismo documentado del proveedor
  que deduplique la creación de mensajes, así que una reentrega tras una caída puede producir un SMS
  duplicado (ver Risks).
- El canal SMS se siembra con el simulado como preferente por defecto, igual que el canal de correo, y
  producción invierte el orden por entorno.
- La siembra del catálogo actúa solo sobre un catálogo vacío. Los entornos ya sembrados necesitan un paso
  manual documentado para dar de alta el canal SMS.
- El aislamiento por tenant sigue viniendo de la consulta al catálogo; esta historia no introduce ni
  relaja ninguna regla de aislamiento.

## Risks

- **Cuenta de prueba del proveedor**: solo envía a números verificados (máximo 5) y antepone un texto
  propio al mensaje, lo que además consume capacidad del fragmento. Apta para la prueba de humo y la
  demo, no para producción. Dueño: andrualv. Fecha de revisión: 2026-11-30, antes de cualquier uso
  productivo.
- **Registro regulatorio para producción**: enviar desde números propios en EE.UU. exige registro A2P
  10DLC (cargo único más cargo mensual). Sin él, producción en EE.UU. no es viable. Dueño: andrualv.
  Fecha de revisión: 2026-11-30.
- **SMS duplicado tras una reentrega**: sin deduplicación del lado del proveedor. Riesgo residual
  aceptado para esta historia. Dueño: andrualv. Fecha de revisión: 2026-10-31.
- **Condiciones transitorias clasificadas como permanentes** (Q4): el proveedor comunica alguna condición
  transitoria del número de origen con el mismo código que un rechazo definitivo. Recuperable a mano.
  Dueño: andrualv. Fecha de revisión, junto con HU2-039: 2026-11-30.
- **Validación del destinatario en el despacho y no en la aceptación** (Q3): el cliente se entera del
  número mal formado consultando el estado, no en la respuesta de aceptación. Mejora anotada para una
  historia de validación de destinatario por canal. Dueño: andrualv. Fecha de revisión: 2026-12-31.
- **Sin límite de tasa propio** (HU2-039): una ráfaga puede provocar rechazos por límite de tasa y
  reintentos masivos. Dueño: andrualv. Fecha de revisión antes de exponer a carga real: 2026-11-30.
- **Sin gestión de secretos de plataforma** (HU2-052): variables de entorno aceptables en desarrollo,
  insuficientes para producción. Dueño: andrualv. Fecha de revisión: 2026-11-30.
- **Sin batería de contrato de adaptadores** (HU2-038): las garantías del adaptador se verifican con
  pruebas propias. Dueño: andrualv. Fecha de revisión: 2026-12-31.
