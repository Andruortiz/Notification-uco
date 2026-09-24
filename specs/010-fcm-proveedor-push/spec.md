# Feature Specification: Integrar Firebase Cloud Messaging como primer proveedor real de PUSH

**Feature Branch**: `010-fcm-proveedor-push`

**Created**: 2026-09-24

**Status**: Plan aceptado — Q1–Q4 de Clarifications confirmadas por el usuario (2026-09-24)

**Input**: User description: "Como sistema cliente, quiero que mis notificaciones push se entreguen de verdad
a través de Firebase Cloud Messaging, para que el canal PUSH deje de depender de un proveedor simulado y
quede disponible junto a EMAIL."

**Trazabilidad**: CU-03 · RF-08, RF-10, RNF-08, RNF-09 · mismo patrón que HU2-089 (proveedor real de
correo) y HU2-090 (proveedor real de SMS).

## Clarifications

### Session 2026-09-24 — confirmadas (2026-09-24)

Las cuatro preguntas siguientes se identificaron en la revisión de ambigüedad. No fue posible consultarlas
durante la redacción, así que cada una se registró con una respuesta recomendada, y esa respuesta queda
**confirmada** tal cual por el usuario al aprobar el plan (ver `plan.md § Estado del plan`); si alguna
cambia en el futuro, se indica qué partes afecta.

- **Q1 — ¿Qué hace el componente con el identificador de dispositivo que llega como dirección del
  destinatario: lo trata como un texto opaco que solo el proveedor valida, comprueba su forma antes de
  llamar al proveedor, o ajusta el dominio para distinguirlo de un correo o un teléfono?** Es el punto
  central de la historia. Hoy la dirección del destinatario es un texto libre cuya única regla es no estar
  vacío, sin longitud máxima, y no se expone en ninguna respuesta de consulta (solo el identificador
  estable del destinatario). Un identificador de dispositivo típico mide entre 150 y 200 caracteres y cabe
  sin cambios. El proveedor **no documenta ni garantiza** el formato del identificador: lo declara opaco.
  - **Respuesta confirmada por el usuario**: **texto opaco, sin ajuste de dominio y sin
    comprobación de forma**. La dirección del destinatario se usa tal cual; el proveedor es quien decide si
    es válida, y un identificador mal formado o de otra naturaleza (un correo enviado por error al canal
    PUSH) termina en fallo permanente tras una única llamada, por la respuesta "argumento inválido" o "no
    registrado" del proveedor. Nada cambia en el dominio.
  - Por qué difiere del canal SMS, que sí comprueba el formato internacional antes de llamar: ese formato
    es un estándar público y estable; el del identificador de dispositivo no. Una comprobación propia
    podría rechazar identificadores válidos si el proveedor cambia su forma, y un falso rechazo convierte
    en fallo permanente una notificación que sí podía entregarse. Rechazar tarde un identificador inválido
    cuesta una llamada; rechazar uno válido cuesta la notificación.
  - Alternativas descartadas: (a) comprobación laxa en el despacho (solo letras, dígitos, `_`, `-` y `:`),
    como el canal SMS — ahorra una llamada con direcciones obviamente equivocadas, pero introduce una regla
    que el proveedor no respalda; (b) validar el destinatario según el canal en la aceptación — lo ideal
    para el cliente, pero exige que el núcleo conozca la forma de cada dirección, un cambio de modelo ya
    anotado como mejora futura por la historia del canal SMS; (c) añadir una longitud máxima a la dirección
    del destinatario en el dominio — hoy no hay ninguna y el identificador cabe; no es necesaria para esta
    historia.
  - Afecta a: FR-014, User Story 4, Key Entities, Edge Cases, SC-012.
- **Q2 — ¿El canal PUSH declara su propio límite de título y cuerpo, y qué se hace con una notificación
  que lo excede?** El proveedor admite como máximo 4096 bytes de contenido por mensaje y rechaza lo que lo
  supera como "argumento inválido". Si el componente no limita antes, una notificación demasiado grande se
  acepta y luego termina fallida.
  - **Respuesta confirmada por el usuario**: **rechazar en la aceptación, nunca truncar**, con
    la forma de contenido del canal PUSH: asunto (título) opcional de **hasta 100 caracteres** y cuerpo
    obligatorio de **hasta 900 caracteres**, ambos editables por entorno sin tocar código. La suma, 1000
    caracteres, cabe en 4096 bytes aun si cada carácter ocupa el máximo de 4 bytes. El cliente recibe el
    rechazo de inmediato con un motivo que nombra el límite; nada se persiste ni llega al proveedor.
  - Alternativas descartadas: (a) sin límite propio — un contenido grande se acepta y acaba en fallo
    permanente después; (b) un único límite de cuerpo mayor (por ejemplo 4000 caracteres) — no garantiza
    caber en bytes con texto acentuado o emojis; (c) truncar — entrega algo que el cliente no escribió.
  - Afecta a: FR-012, FR-013, User Story 4, SC-013.
- **Q3 — ¿Un único proyecto del proveedor para todo el despliegue, o credenciales por tenant?** Un
  identificador de dispositivo solo es válido para el proyecto del proveedor al que pertenece la aplicación
  que lo emitió; con otras credenciales el proveedor lo rechaza por "remitente no coincidente".
  - **Respuesta confirmada por el usuario**: **un único proyecto por despliegue** en esta
    historia, igual que los proveedores de correo y SMS usan una sola cuenta. Solo las aplicaciones de ese
    proyecto reciben push; un identificador de otro proyecto termina en fallo permanente. Credenciales por
    tenant queda fuera de alcance y anotado como riesgo con dueño y fecha.
  - Alternativa descartada: credenciales por tenant — exige un modelo de configuración por tenant que no
    existe para ningún proveedor y excede la historia.
  - Afecta a: Assumptions, Edge Cases, Out of Scope, Risks.
- **Q4 — ¿Por qué medio llega la credencial: el contenido de la cuenta de servicio en una variable de
  entorno, la ruta de un archivo montado como secreto, o ambos?** Los orquestadores montan secretos como
  archivos; en desarrollo y en integración continua es más cómodo una variable.
  - **Respuesta confirmada por el usuario**: **ambos, excluyentes**. Se acepta el contenido
    por variable de entorno o la ruta de un archivo montado; si llegan los dos a la vez, el proveedor queda
    deshabilitado con un motivo explícito que lo dice, en lugar de elegir uno en silencio.
  - Alternativas descartadas: (a) solo variable de entorno — obliga a copiar una clave privada multilínea
    en una variable en entornos que ya montan secretos como archivos; (b) solo archivo — complica el
    desarrollo local y la integración continua; (c) ambos con prioridad de uno — una configuración
    contradictoria pasaría inadvertida.
  - Afecta a: FR-003, FR-004, User Story 2.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Las notificaciones push se entregan de verdad (Priority: P1)

Como sistema cliente, quiero enviar una notificación por el canal PUSH al dispositivo de un destinatario y
que el proveedor real de push la entregue, para contar con un canal que hoy el componente no ofrece.

**Why this priority**: Es la razón de ser de la historia. Hoy el catálogo no declara el canal PUSH: una
notificación push se rechaza en la aceptación porque el canal no existe. Sin esto no hay canal PUSH.

**Independent Test**: Con el proveedor real declarado como preferente del canal PUSH y sus credenciales
presentes, aceptar una notificación push y confirmar que (a) el proveedor real recibió una petición de envío
dirigida al identificador de dispositivo del destinatario con el contenido de esa notificación, (b) la
notificación quedó entregada y (c) el intento de entrega quedó anotado con el identificador del proveedor
real.

**Acceptance Scenarios**:

1. **Given** el canal PUSH con el proveedor real como preferente y credenciales válidas, **When** se acepta
   y despacha una notificación push, **Then** el proveedor real recibe exactamente una petición de envío
   dirigida al identificador de dispositivo del destinatario con el contenido de esa notificación, y la
   notificación queda entregada con el identificador del proveedor real en su intento.
2. **Given** el canal PUSH con el proveedor simulado como preferente, **When** se despacha una notificación
   push, **Then** el proveedor real no recibe ninguna petición y la notificación se entrega por el simulado.
3. **Given** el catálogo sembrado desde cero con la configuración versionada, **When** se consulta la ruta
   del canal PUSH, **Then** el canal existe y lista al proveedor real entre sus proveedores.
4. **Given** el proveedor real habilitado, **When** se despachan varias notificaciones push seguidas,
   **Then** la autorización ante el proveedor se obtiene una vez y se reutiliza mientras siga vigente, en
   lugar de pedirse de nuevo en cada envío.

---

### User Story 2 - Sin credenciales el proveedor se deshabilita con un motivo visible (Priority: P1)

Como responsable de la operación, quiero que un despliegue sin las credenciales del proveedor real de push,
o con credenciales ilegibles, deje constancia explícita de que está deshabilitado y por qué, para enterarme
al arrancar y no al descubrir que ningún dispositivo recibió nada.

**Why this priority**: Misma garantía operativa que ya tienen los proveedores reales de correo y de SMS. Es
la condición que permite listar el proveedor en el catálogo antes de que existan credenciales en todos los
entornos, incluidos desarrollo local e integración continua.

**Independent Test**: Arrancar el servicio sin credenciales, y luego con credenciales ilegibles o
incompletas, y confirmar que (a) el arranque se completa, (b) queda un registro explícito que nombra al
proveedor y el motivo, (c) ese registro no contiene ninguna parte de la credencial y (d) una notificación
dirigida a ese proveedor no se da por entregada, no se pierde, deja un rastro consultable y el proveedor
real no recibe ninguna petición.

**Acceptance Scenarios**:

1. **Given** un despliegue sin credenciales del proveedor, **When** el servicio arranca, **Then** el
   arranque se completa y queda constancia explícita de que el proveedor está deshabilitado, nombrando el
   dato ausente y sin revelar ningún valor.
2. **Given** un despliegue con credenciales ilegibles, incompletas o de un tipo que no corresponde, **When**
   el servicio arranca, **Then** el comportamiento es el mismo: deshabilitado con motivo explícito que
   nombra qué falla, sin revelar ninguna parte de la credencial.
3. **Given** un despliegue deshabilitado y con el proveedor real como preferente del canal PUSH, **When** se
   despacha una notificación push, **Then** la notificación no se marca como entregada ni como fallida por
   el proveedor, conserva su historial sin intentos nuevos, el fallo queda registrado con el motivo de
   deshabilitación y el proveedor real no recibe ninguna petición.
4. **Given** un despliegue con credenciales completas y legibles, **When** el servicio arranca, **Then** el
   proveedor queda habilitado y no se emite ningún aviso de deshabilitación.

---

### User Story 3 - Los fallos del proveedor se clasifican y no se confunden entre sí (Priority: P2)

Como responsable de la operación, quiero que cada respuesta del proveedor real de push se traduzca a una de
las tres categorías que el componente ya entiende — aceptada, fallo recuperable o fallo permanente — para
que los reintentos se disparen solo cuando sirven.

**Why this priority**: Reintentar contra un dispositivo que ya no existe gasta cuota y termina igual; no
reintentar una indisponibilidad temporal pierde notificaciones que habrían salido al segundo intento.
Depende de la entrega básica (P1).

**Independent Test**: Simular cada familia de respuesta del proveedor y confirmar que cada una produce
exactamente la categoría esperada y el estado de notificación correspondiente.

**Acceptance Scenarios**:

1. **Given** el proveedor habilitado, **When** el proveedor acepta el envío, **Then** el intento se registra
   como aceptado.
2. **Given** el proveedor habilitado, **When** el proveedor responde que el identificador de dispositivo ya
   no está registrado (la aplicación se desinstaló o el usuario revocó el permiso), **Then** el intento se
   registra como fallo permanente y la notificación no se reintenta.
3. **Given** el proveedor habilitado, **When** el proveedor rechaza la petición por argumento inválido
   (identificador de dispositivo mal formado, contenido demasiado grande u otro dato inválido), **Then** el
   intento se registra como fallo permanente.
4. **Given** el proveedor habilitado, **When** el proveedor o su servicio de autorización rechaza las
   credenciales, **Then** el intento se registra como fallo permanente y el motivo queda registrado sin
   revelar ninguna credencial.
5. **Given** el proveedor habilitado, **When** el proveedor responde que no está disponible, que tuvo un
   error interno o que se excedió la cuota, **Then** el intento se registra como fallo recuperable y la
   notificación entra en el camino de reintentos existente.
6. **Given** el proveedor habilitado, **When** el proveedor o su servicio de autorización no responden
   dentro del tiempo de espera configurado, o no es posible conectar, **Then** la llamada se corta en ese
   plazo y el intento se registra como fallo recuperable.

---

### User Story 4 - El destinatario y el contenido de un push respetan la forma del canal (Priority: P2)

Como sistema cliente, quiero saber qué dirección y qué contenido admite el canal PUSH, para enviar una
notificación que el dispositivo pueda mostrar y enterarme pronto si no cabe.

**Why this priority**: Un push no se dirige a una dirección con forma de canal (correo, teléfono) sino a un
identificador de dispositivo que emite el propio proveedor a la aplicación cliente. Hay que fijar qué
hace el componente con ese dato y con el asunto y el cuerpo de la notificación. Es P2 porque la entrega
(P1) aporta valor aunque estos límites se ajusten después.

**Independent Test**: Enviar notificaciones push con y sin asunto, con un cuerpo dentro y fuera del límite
del canal y con un identificador de dispositivo que el proveedor no reconoce, y confirmar en cada caso el
resultado en la aceptación, lo que recibe el proveedor y el estado final.

**Acceptance Scenarios**:

1. **Given** el canal PUSH, **When** se envía una notificación con asunto y cuerpo, **Then** se acepta y el
   proveedor recibe el asunto como título visible y el cuerpo como texto del push.
2. **Given** el canal PUSH, **When** se envía una notificación sin asunto, **Then** se acepta y el
   proveedor recibe solo el cuerpo.
3. **Given** el canal PUSH, **When** se envía una notificación con un asunto de exactamente 100 caracteres
   y un cuerpo de exactamente 900, **Then** se acepta.
4. **Given** el canal PUSH, **When** se envía una notificación con asunto de 101 caracteres o más, o con
   cuerpo de 901 caracteres o más, **Then** se rechaza en la aceptación con un motivo que nombra el
   límite, no se persiste, no se trunca y el proveedor no recibe nada (Q2).
5. **Given** una notificación push cuyo destinatario es un texto que el proveedor no reconoce como
   identificador de dispositivo (por ejemplo, un correo enviado por error al canal PUSH), **When** se
   despacha por el proveedor real, **Then** el componente lo envía tal cual sin comprobar su forma, el
   proveedor lo rechaza, el intento se registra como fallo permanente y la notificación termina fallida
   sin reintentos (Q1).
6. **Given** un identificador de dispositivo real de entre 150 y 200 caracteres, **When** se acepta la
   notificación, **Then** se acepta y se persiste sin cambios en la forma del destinatario (Q1).

---

### User Story 5 - Nada sensible sale del componente (Priority: P2)

Como responsable de seguridad, quiero que ni las credenciales del proveedor, ni la autorización temporal
que se obtiene con ellas, ni el contenido del push, ni el identificador de dispositivo completo aparezcan en
los registros ni en los mensajes internos, para que la integración no abra una vía de fuga.

**Why this priority**: La credencial del proveedor es una clave privada que permite enviar push a todos los
dispositivos de la aplicación; el identificador de dispositivo permite dirigirse a una persona concreta. Es
P2 solo porque no bloquea la entrega; su incumplimiento sí bloquearía la puesta en producción.

**Independent Test**: Ejecutar un despacho completo por el proveedor real, capturar todos los registros
emitidos y los mensajes que el sistema publica internamente, y confirmar que en ninguno aparece ninguna
parte de la credencial, la autorización temporal, el título ni el cuerpo del push, ni el identificador de
dispositivo completo; el identificador puede aparecer solo enmascarado.

**Acceptance Scenarios**:

1. **Given** un despacho completo por el proveedor real, **When** se revisan los registros, **Then** no
   aparece ninguna parte de la credencial ni la autorización temporal obtenida con ella.
2. **Given** el mismo despacho, **When** se revisan los registros, **Then** no aparecen el título ni el
   cuerpo del push, y el identificador de dispositivo aparece, si aparece, enmascarado con a lo sumo sus
   últimos cuatro caracteres visibles.
3. **Given** el mismo despacho, **When** se revisan los mensajes que el sistema publica internamente,
   **Then** no aparece ninguna credencial, ni el contenido, ni el identificador de dispositivo.
4. **Given** un despacho que falla, **When** se revisa el registro del fallo, **Then** contiene
   identificador de notificación, tenant, proveedor, categoría del fallo y el código de error del proveedor
   si lo hay, y ninguno de los datos prohibidos.
5. **Given** credenciales ilegibles al arrancar, **When** se revisa el aviso de deshabilitación, **Then** no
   contiene ningún fragmento del contenido de la credencial.

---

### Edge Cases

- **¿Qué pasa si el proveedor real está listado pero deshabilitado?** La notificación no se entrega, no se
  marca como fallida por el proveedor y conserva su historial; el despacho falla de forma trazable con el
  motivo de deshabilitación. Error de configuración, no de entrega.
- **¿Qué pasa si el identificador de dispositivo caducó o se invalidó (aplicación desinstalada, permiso
  revocado)?** El proveedor lo informa como "no registrado" y se clasifica como fallo permanente: nunca se
  reintenta indefinidamente. Avisar al sistema cliente de que ese identificador quedó inválido está fuera
  de alcance (ver Out of Scope).
- **¿Qué pasa si el identificador de dispositivo pertenece a una aplicación de otro proyecto del
  proveedor?** El proveedor lo rechaza por remitente no coincidente y se clasifica como fallo permanente
  (ver Assumptions: un único proyecto del proveedor por despliegue).
- **¿Qué pasa si la autorización temporal ante el proveedor caduca entre dos envíos?** Se renueva antes de
  caducar; varias notificaciones simultáneas no provocan varias renovaciones a la vez.
- **¿Qué pasa si el servicio de autorización del proveedor no responde o rechaza las credenciales?** Sin
  respuesta o sin conexión → fallo recuperable; rechazo de credenciales → fallo permanente. En ambos casos
  no se llega a enviar el push.
- **¿Qué pasa si la notificación trae asunto?** Se usa como título visible del push (User Story 4).
- **¿Qué pasa si el contenido excede el límite del canal?** Rechazo inmediato en la aceptación, sin
  persistir y sin truncar (Q2).
- **¿Qué pasa si, aun dentro del límite, el contenido supera el tamaño que admite el proveedor?** Solo es
  posible con caracteres de control que se codifican con más de 4 bytes; el proveedor lo rechaza como
  argumento inválido y termina en fallo permanente. Caso residual aceptado (Q2).
- **¿Qué pasa si el destinatario no es un identificador de dispositivo (un correo o un teléfono enviado
  al canal equivocado)?** Se envía tal cual y el proveedor lo rechaza: fallo permanente tras una llamada
  (Q1).
- **¿Qué pasa si llegan a la vez la credencial por variable de entorno y por archivo?** El proveedor queda
  deshabilitado con un motivo que lo dice (Q4).
- **¿Qué pasa si el proveedor acepta el envío pero el componente cae antes de registrar el resultado?** El
  mensaje se reentrega y el push puede llegar dos veces. Riesgo residual documentado (ver Risks).
- **¿Qué pasa si el proveedor responde algo que no encaja en ninguna familia conocida?** Se clasifica de
  forma conservadora: rechazo atribuible a la petición → permanente; cualquier otro caso → recuperable.
- **¿Qué pasa en un entorno que ya tiene el catálogo sembrado?** La siembra solo actúa sobre un catálogo
  vacío: el canal PUSH **no** aparece por sí solo en un entorno existente. Requiere un paso explícito de
  alta del canal en el catálogo, documentado.
- **¿Qué pasa si el identificador de dispositivo tiene cuatro caracteres o menos?** Se enmascara por
  completo en los registros.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE disponer de un proveedor de envío dedicado al proveedor real de push, con su
  propio identificador estable, distinto del simulado y de los proveedores reales de correo y de SMS.
- **FR-002**: El catálogo DEBE declarar el canal PUSH y listar al proveedor real entre sus proveedores.
- **FR-003**: La credencial del proveedor (la cuenta de servicio del proyecto) DEBE llegar exclusivamente
  por variable de entorno con su contenido o por la ruta de un secreto montado como archivo, nunca por
  ambos a la vez (Q4). NO DEBE estar en el código, en la configuración versionada, en los registros ni en
  los mensajes internos.
- **FR-004**: Si la credencial falta, llega por los dos medios a la vez, no se puede leer, no se puede
  interpretar, no es del tipo esperado o le falta alguno de los datos imprescindibles, el proveedor DEBE
  quedar deshabilitado con un motivo
  explícito que nombre qué falla, y el servicio DEBE seguir arrancando. La deshabilitación NUNCA DEBE ser
  silenciosa y el motivo NUNCA DEBE contener un fragmento de la credencial.
- **FR-005**: Una notificación dirigida a un proveedor deshabilitado NO DEBE darse por entregada, NO DEBE
  marcarse como fallida por el proveedor, NO DEBE perderse y NO DEBE producir ninguna petición al
  proveedor; el despacho DEBE fallar de forma trazable nombrando el motivo, sin registrar intento de
  entrega.
- **FR-006**: El sistema DEBE traducir cada respuesta del proveedor a exactamente una de las tres
  categorías existentes. Como mínimo: aceptación → aceptada; indisponibilidad, error interno del
  proveedor, cuota excedida, tiempo agotado e imposibilidad de conectar → fallo recuperable; dispositivo no
  registrado, argumento inválido y credenciales inválidas → fallo permanente.
- **FR-007**: Toda llamada al proveedor, incluida la que obtiene la autorización temporal, DEBE tener un
  tiempo de espera explícito y configurable; una llamada que lo supere DEBE cortarse y clasificarse como
  fallo recuperable.
- **FR-008**: Los registros del proveedor NO DEBEN incluir el título ni el cuerpo del push, el
  identificador de dispositivo completo, ninguna parte de la credencial ni la autorización temporal. El
  identificador de dispositivo, si se registra, DEBE ir enmascarado con a lo sumo sus últimos cuatro
  caracteres visibles. Los registros SÍ DEBEN incluir identificador de notificación, tenant, identificador
  de proveedor, categoría del resultado y, en un rechazo, el código de error del proveedor; NUNCA el texto
  libre del mensaje de error del proveedor.
- **FR-009**: El intento de entrega DEBE quedar registrado con el identificador del proveedor real cuando
  sea ese proveedor el que ejecutó el envío.
- **FR-010**: La lista de proveedores del canal PUSH DEBE poder configurarse por entorno sin modificar
  código, con un valor por defecto seguro para un entorno sin credenciales (el simulado como preferente),
  igual que los canales de correo y SMS.
- **FR-011**: La autorización temporal que se obtiene con la credencial DEBE reutilizarse mientras siga
  vigente y renovarse antes de caducar; una renovación fallida NO DEBE quedar memorizada, de modo que el
  siguiente envío vuelva a intentarla.
- **FR-012**: El canal PUSH DEBE declarar su propia forma de contenido: cuerpo obligatorio y asunto
  opcional, que el proveedor recibe como título visible cuando viene y que no se envía cuando falta.
- **FR-013**: La forma de contenido del canal PUSH DEBE limitar el asunto a 100 caracteres y el cuerpo a
  900 por defecto, modificables por entorno sin cambiar código. Una notificación que exceda cualquiera de
  los dos DEBE rechazarse en la aceptación con un motivo que nombre el límite; NUNCA DEBE truncarse (Q2).
- **FR-014**: La dirección del destinatario de una notificación push DEBE tratarse como un identificador
  de dispositivo opaco: el sistema NO DEBE comprobar su forma ni transformarla, y DEBE enviarla tal cual
  al proveedor, que decide si es válida. El dominio del destinatario NO cambia (Q1).
- **FR-015**: El proveedor simulado DEBE seguir disponible y operativo, y los proveedores reales de correo
  y de SMS DEBEN seguir funcionando exactamente igual.
- **FR-016**: El sistema NO DEBE, en esta historia, aplicar límites de tasa propios, cortacircuitos, ni
  conmutación al siguiente proveedor, ni avisar al sistema cliente de identificadores de dispositivo
  invalidados.

### Key Entities *(include if feature involves data)*

- **Proveedor real de push**: nuevo proveedor de envío. Se identifica por un identificador estable propio y
  conoce la cuenta de servicio de un proyecto del proveedor, inyectada por entorno. No persiste entidades
  nuevas.
- **Autorización temporal**: credencial de corta vida que el proveedor emite a cambio de la cuenta de
  servicio. Vive solo en memoria del proceso, nunca se persiste ni se registra.
- **Identificador de dispositivo**: dato que el proveedor emite a la aplicación cliente instalada en un
  dispositivo y que el sistema cliente envía como dirección del destinatario. El componente no lo genera
  ni lo obtiene: lo recibe tal cual en la notificación, lo guarda en el mismo campo de dirección que un
  correo o un teléfono y lo trata como texto opaco (Q1).
- **Ruta del canal PUSH**: nueva entrada del catálogo (entidad ya existente) con su lista de proveedores
  (por defecto: simulado, real) y su forma de contenido propia: asunto opcional de hasta 100 caracteres
  (título visible) y cuerpo obligatorio de hasta 900 (Q2).
- **Resultado del intento**: entidad ya existente. Esta historia define cómo se deriva desde las respuestas
  del proveedor real de push.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el proveedor real habilitado y preferente, el 100 % de las notificaciones push despachadas
  producen exactamente una petición de envío al proveedor dirigida al identificador de dispositivo del
  destinatario con el contenido de esa notificación, y terminan entregadas con el identificador del
  proveedor real.
- **SC-002**: En un entorno sembrado desde cero, el canal PUSH existe en el catálogo y lista al proveedor
  real.
- **SC-003**: En un arranque sin credencial o con una credencial ilegible, incompleta o de otro tipo, el
  100 % de los arranques se completan y dejan exactamente un registro que nombra al proveedor y el motivo,
  sin ningún fragmento de la credencial.
- **SC-004**: Cero apariciones de la credencial (en cualquier parte), de la autorización temporal, del
  título, del cuerpo y del identificador de dispositivo completo en los registros y en los mensajes
  internos durante un despacho completo, verificado automáticamente y con control positivo de que el
  identificador enmascarado sí aparece.
- **SC-005**: Cada familia de respuesta del proveedor (aceptación, no registrado, argumento inválido,
  credenciales rechazadas, remitente no coincidente, cuota excedida, indisponible, error interno, tiempo
  agotado, imposibilidad de conectar) y del servicio de autorización (credenciales rechazadas, tiempo
  agotado) produce la categoría esperada en el 100 % de los casos, verificado automáticamente.
- **SC-006**: Ninguna llamada al proveedor ni a su servicio de autorización excede el tiempo de espera
  configurado; una respuesta que tarda más se corta dentro de ese plazo y se clasifica como recuperable,
  verificado con una aserción explícita de duración.
- **SC-007**: Con el valor por defecto de la configuración versionada y sin credenciales, cero regresiones en
  la suite existente y una notificación push se acepta y se entrega por el proveedor simulado.
- **SC-008**: Diez notificaciones push despachadas dentro de la vigencia de una autorización producen una
  sola obtención de autorización; tras forzar su caducidad, el siguiente envío obtiene una nueva.
- **SC-009**: Existe una prueba de punta a punta automatizada contra un servidor que simula al proveedor y a
  su servicio de autorización, y cero pruebas de la suite automatizada llaman al proveedor real.
- **SC-010**: Existe un procedimiento de prueba manual de humo con un proyecto real del proveedor,
  documentado y ejecutable fuera de la integración continua, con una plantilla para registrar su resultado.
- **SC-011**: Un proveedor deshabilitado produce cero peticiones al proveedor y a su servicio de
  autorización, verificado observando el servidor simulado y no solo el resultado.
- **SC-012**: Una notificación push cuyo destinatario el proveedor rechaza como no registrado o inválido
  produce exactamente una petición de envío, termina fallida y no se reintenta; la dirección llega al
  proveedor idéntica a la aceptada.
- **SC-013**: Una notificación push con asunto de 101 caracteres o más, o cuerpo de 901 o más, produce un
  rechazo inmediato con un motivo que nombra el límite, cero notificaciones persistidas y cero peticiones
  al proveedor; una con asunto de 100 y cuerpo de 900 se acepta; una sin asunto produce una petición sin
  título.

## Out of Scope

- Cómo obtiene la aplicación cliente el identificador de dispositivo y cómo lo hace llegar al sistema
  cliente: el componente lo recibe ya resuelto como dirección del destinatario. El panel administrativo del
  componente no participa.
- Avisar al sistema cliente de que un identificador de dispositivo quedó inválido (no registrado), o
  mantener una lista propia de identificadores invalidados. Candidata a una historia aparte.
- Batería de pruebas de contrato común a los adaptadores (HU2-038), gestión de secretos por la plataforma
  (HU2-052) y límite de tasa por proveedor (HU2-039).
- Cortacircuitos y conmutación al siguiente proveedor ante un fallo (HU2-048).
- Credenciales del proveedor por tenant (varios proyectos del proveedor en un mismo despliegue) (Q3).
- Validar la forma de la dirección del destinatario según el canal en la aceptación (Q1).
- Envío a temas, a grupos de dispositivos o por condición; mensajes solo de datos; datos personalizados,
  imagen, sonido, insignia o acciones del push; opciones específicas por plataforma; prioridad del push
  derivada de la prioridad de la notificación.
- Confirmaciones de entrega o de apertura en el dispositivo.
- Cambiar la estructura del contrato público de aceptación o el mecanismo de siembra y refresco del
  catálogo.

## Assumptions

- El usuario aporta un proyecto del proveedor con una cuenta de servicio autorizada para enviar push y una
  aplicación de prueba instalada en un dispositivo que le entregue un identificador. Sin ellos el proveedor
  queda deshabilitado (FR-004) y la historia se valida igualmente contra un servidor que lo simula.
- "Aceptada" significa que el proveedor asumió el envío, no que el dispositivo lo mostró: es la misma
  semántica que ya tienen el simulado y los proveedores reales de correo y SMS.
- El push se envía como notificación visible (título y cuerpo que el sistema operativo del dispositivo
  muestra por sí solo), no como mensaje de datos que la aplicación deba interpretar.
- La entrega del componente es "al menos una vez". El proveedor no ofrece deduplicación del envío por una
  clave del cliente, así que una reentrega tras una caída puede producir un push duplicado (ver Risks).
- El canal PUSH se siembra con el simulado como preferente por defecto, igual que los canales de correo y
  SMS, y producción invierte el orden por entorno.
- La siembra del catálogo actúa solo sobre un catálogo vacío. Los entornos ya sembrados necesitan un paso
  manual documentado para dar de alta el canal PUSH.
- Un único proyecto del proveedor por despliegue, compartido por todos los tenants (Q3). Solo las
  aplicaciones de ese proyecto pueden recibir push; un identificador emitido por la aplicación de otro
  proyecto termina en fallo permanente.
- El aislamiento por tenant sigue viniendo de la consulta al catálogo; esta historia no introduce ni relaja
  ninguna regla de aislamiento.

## Risks

- **Push duplicado tras una reentrega**: sin deduplicación del lado del proveedor. Riesgo residual aceptado
  para esta historia. Dueño: andrualv. Fecha de revisión: 2026-10-31.
- **Identificadores de dispositivo invalidados sin aviso al cliente**: el sistema cliente se entera
  consultando el estado de la notificación y seguirá enviando al mismo identificador. Dueño: andrualv.
  Fecha de revisión: 2026-12-31.
- **Un único proyecto del proveedor por despliegue** (Q3): un tenant cuya aplicación pertenece a otro
  proyecto no puede recibir push por este componente. Dueño: andrualv. Fecha de revisión: 2026-12-31.
- **Destinatario inválido detectado solo por el proveedor** (Q1): cuesta una llamada por notificación mal
  dirigida y el cliente se entera por el estado, no por la respuesta de aceptación. Dueño: andrualv. Fecha
  de revisión, junto con la validación de destinatario por canal: 2026-12-31.
- **Sin límite de tasa propio** (HU2-039): una ráfaga puede agotar la cuota del proyecto y provocar
  reintentos masivos. Dueño: andrualv. Fecha de revisión antes de exponer a carga real: 2026-11-30.
- **Sin gestión de secretos de plataforma** (HU2-052): variable de entorno o archivo montado, aceptables en
  desarrollo, insuficientes para producción. Dueño: andrualv. Fecha de revisión: 2026-11-30.
- **Sin batería de contrato de adaptadores** (HU2-038): las garantías del adaptador se verifican con pruebas
  propias. Dueño: andrualv. Fecha de revisión: 2026-12-31.
