# Feature Specification: Enviar notificaciones por el canal WhatsApp

**Feature Branch**: `feature/HU2-112-whatsapp-canal`

**Created**: 2026-10-21

**Status**: Clarify cerrado — Q1 a Q4 adoptadas con la opción A por el usuario (2026-10-21); pendiente de aprobación del plan

**Input**: User description: "Como sistema cliente, quiero enviar una notificación por el canal WhatsApp,
para llegar al destinatario por el medio con mayor penetración en Colombia."

**Trazabilidad**: CU-03 · ADR-28 (integración WhatsApp Business: API oficial detrás de un adaptador,
validación del webhook, plantillas, reintentos e idempotencia; aún por documentar), ADR-05, ADR-23 ·
requisito de la ventana de 24 h de WhatsApp. Misma estructura que HU2-089 (correo), HU2-090 (SMS) y
HU2-091 (push). El proveedor es Twilio (Q1); reutiliza la cuenta y buena parte del cliente de
HU2-090. Prioridad Deseable, tamaño L, épica C, fase 5.

**Directorio**: la numeración 027 ya está ocupada por `027-rutas-publicas-pep`; esta spec toma el
siguiente número libre.

## Clarifications

### Session 2026-10-21 — respuestas adoptadas (2026-10-21)

El usuario confirmó la opción A en las cuatro preguntas y todos los valores por defecto de la spec el
2026-10-21. Cada pregunta conserva sus opciones; la **Respuesta adoptada** es la que rige.

- **Q1 — ¿Qué proveedor entrega los mensajes de WhatsApp?**
  - Opciones: (A) **Twilio** (WhatsApp sobre la misma cuenta y API que el SMS de HU2-090); (B) **Meta
    Cloud API directo** (proveedor oficial, sin intermediario); (C) ambos detrás del mismo puerto, con
    uno preferente.
  - Implicaciones: A reutiliza autenticación, clasificación de respuestas y la prueba de humo de
    HU2-090, el onboarding es más corto (sandbox inmediato) y el costo por mensaje suma el recargo del
    intermediario; las plantillas se aprueban igualmente por Meta. B elimina el recargo y es la fuente
    primaria de plantillas, ventana y estados, pero exige alta de cuenta Business verificada, número
    propio, token de sistema de larga duración, y un cliente y una clasificación de errores nuevos. C
    duplica el trabajo y excede una historia de tamaño L.
  - **Respuesta adoptada: A (Twilio)**. Razonamiento: es el camino con menos piezas nuevas y menor
    riesgo, el puerto deja a B como adaptador futuro sin tocar el núcleo, y el modelo de contenido de
    esta spec (FR-004 a FR-007) no depende del proveedor. Revisar si el costo por mensaje o la
    verificación de negocio hacen inviable el intermediario.
  - Afecta a: FR-001, FR-003, FR-008, SC-010, Assumptions, Risks.
- **Q2 — ¿Cómo sabe el sistema si la ventana de 24 h de un destinatario está abierta?** La ventana se
  abre cuando el destinatario escribe al número del negocio; dentro de ella se admite texto libre, fuera
  solo plantillas aprobadas. Hoy el componente no recibe mensajes entrantes (fuera de alcance en HU2-090).
  - Opciones: (A) **el sistema no rastrea la ventana**: el cliente elige por notificación entre texto
    libre y plantilla; un texto libre fuera de la ventana lo rechaza el proveedor y se registra como
    fallo permanente con motivo explícito; (B) el sistema rastrea la ventana consumiendo los mensajes
    entrantes del proveedor (requiere el webhook de Q3 en modo entrante) y decide solo, degradando a
    plantilla o rechazando en la aceptación; (C) solo plantillas, sin texto libre.
  - Implicaciones: A no añade infraestructura entrante, cumple la regla legal porque nunca se envía
    texto libre sin que el proveedor lo permita, y traslada al cliente el conocimiento de la
    conversación; B da la mejor experiencia pero crece a historia XL y depende de Q3; C es lo más simple
    y siempre válido, pero impide responder dentro de la conversación.
  - **Respuesta adoptada: A**, con la plantilla siempre disponible. Rige FR-005 y FR-006.
  - Afecta a: FR-005, FR-006, User Story 2, Edge Cases, Risks.
- **Q3 — ¿Qué alcance tiene el webhook de estado de entrega y mensajes entrantes?**
  - Opciones: (A) **fuera de alcance**: "aceptada" significa que el proveedor asumió el envío, igual que
    SMS; sin webhook; (B) webhook solo de estados (enviado, entregado, leído, fallido) que actualiza el
    intento, con validación de firma; (C) webhook de estados y de mensajes entrantes (necesario para
    Q2-B).
  - Implicaciones: A mantiene el tamaño y el alcance, pero el "entregado" real queda sin saberse
    (consistente con SMS y push). B exige un endpoint público nuevo (definido primero en el contrato,
    Principio II, y declarado como ruta pública del PEP), validación criptográfica de la firma,
    protección contra repetición y correlación con el intento. C suma lo anterior más almacenamiento de
    conversación.
  - **Respuesta adoptada: A** en esta historia, y abrir una historia propia para B. El requisito de
    "validar webhook" de ADR-28 queda explícitamente diferido, con dueño y fecha en Risks.
  - Afecta a: FR-016, Out of Scope, SC-009, Risks.
- **Q4 — ¿Se admiten adjuntos multimedia (imagen, documento, audio) en WhatsApp?**
  - Opciones: (A) **solo texto y plantillas de texto**; los adjuntos de la notificación se ignoran para
    este canal por declaración de la forma de contenido (cero adjuntos admitidos), sin descarte
    silencioso porque el catálogo los rechaza en la aceptación; (B) imagen y documento por referencia
    pública, dentro del modelo de adjuntos de HU2-092; (C) todos los tipos.
  - Implicaciones: A cierra la historia sin depender de las historias de entrega de adjuntos
    (HU2-093/094/095, que cubren correo, SMS y push) y sin que el proveedor tenga que descargar de un
    almacén propio; B exige que el archivo sea alcanzable por el proveedor y añade tipos y tamaños
    propios del canal; C añade audio, video y reglas de transcodificación.
  - **Respuesta adoptada: A**, con los adjuntos como mejora posterior.
  - Afecta a: FR-009, Out of Scope, Edge Cases.

### Ajuste durante el plan (2026-10-21)

- El diseño mostró que el componente no tiene registro de plantillas, así que "variable obligatoria
  ausente" no puede detectarse en la aceptación. FR-007, la historia 2 (escenario 5) y SC-007 pasan a
  validar solo la forma; la ausencia de una variable la rechaza el proveedor como fallo permanente.

### Nota de nomenclatura: plantilla del proveedor y HU2-074

- El campo del contenido se llama `providerTemplate` (tipo `ProviderTemplateReference`): es una plantilla
  aprobada por el proveedor, que este envía tal cual. Es distinta de HU2-074 (contenido resuelto por código
  desde un catálogo de textos propio). Ambas pueden convivir. La regla de exclusión de esta historia es
  cuerpo o `providerTemplate`; HU2-074 añadirá su propio campo más adelante, sin tocar este.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Las notificaciones por WhatsApp se entregan de verdad (Priority: P1)

Como sistema cliente, quiero enviar una notificación por el canal WhatsApp y que llegue al teléfono del
destinatario a través del proveedor real, para alcanzar al destinatario por el medio que más usa.

**Why this priority**: Es la razón de ser de la historia. Hoy el catálogo no declara el canal WhatsApp:
una notificación por ese canal se rechaza en la aceptación.

**Independent Test**: Con el proveedor real como preferente del canal y credenciales presentes, aceptar
una notificación WhatsApp y confirmar que (a) el proveedor recibió una petición con el número del
destinatario, el número de origen configurado y el contenido, (b) la notificación quedó entregada y (c)
el intento quedó anotado con el identificador del proveedor real.

**Acceptance Scenarios**:

1. **Given** el canal WhatsApp con el proveedor real como preferente y credenciales válidas, **When** se
   acepta y despacha una notificación de texto libre, **Then** el proveedor recibe exactamente una
   petición con destinatario, origen y cuerpo, y la notificación queda entregada con el identificador del
   proveedor real.
2. **Given** el canal con el proveedor simulado como preferente, **When** se despacha una notificación
   WhatsApp, **Then** el proveedor real no recibe nada y se entrega por el simulado.
3. **Given** el catálogo sembrado desde cero, **When** se consulta la ruta del canal WhatsApp, **Then**
   el canal existe y lista al proveedor real.
4. **Given** el número de origen y el destinatario, **When** se construye la petición, **Then** el origen
   viene de la configuración por entorno y el destinatario conserva su formato internacional.

---

### User Story 2 - Fuera de la ventana de 24 h solo viajan plantillas aprobadas (Priority: P1)

Como responsable de cumplimiento, quiero que el componente nunca presente como entregable un texto libre
fuera de la ventana de 24 h, y que ofrezca el envío por plantilla aprobada, para cumplir la regla de
WhatsApp y evitar sanciones sobre el número del negocio.

**Why this priority**: Es el requisito legal y operativo propio de este canal. Sin él, el canal funciona
solo mientras el destinatario haya escrito en las últimas 24 h.

**Independent Test**: Enviar una notificación con plantilla (identificador de plantilla y variables) y
confirmar que el proveedor recibe la plantilla y sus variables, sin cuerpo libre; enviar un texto libre
que el proveedor rechaza por estar fuera de la ventana y confirmar fallo permanente con motivo explícito
y sin reintentos.

**Acceptance Scenarios**:

1. **Given** una notificación WhatsApp con identificador de plantilla y variables, **When** se despacha,
   **Then** la petición al proveedor lleva la plantilla y las variables, y no un cuerpo libre.
2. **Given** una notificación WhatsApp con plantilla y también cuerpo libre, **When** se acepta, **Then**
   se rechaza en la aceptación por ambigüedad, con motivo que nombra los dos campos.
3. **Given** una notificación de texto libre, **When** el proveedor la rechaza por estar fuera de la
   ventana, **Then** el intento se registra como fallo permanente, no se reintenta y el motivo nombra la
   causa "fuera de la ventana" sin revelar contenido ni número (Q2).
4. **Given** una plantilla no aprobada o inexistente, **When** el proveedor la rechaza, **Then** el
   intento es fallo permanente con el código del proveedor registrado.
5. **Given** una notificación con plantilla cuya referencia viene vacía o cuyas variables no cumplen la
   forma declarada por el canal (nombres vacíos, valores no textuales, más del máximo), **When** se envía
   a aceptar, **Then** se rechaza en la aceptación con motivo que nombra el campo; si la plantilla exige
   una variable que el cliente omitió, la rechaza el proveedor y el intento es fallo permanente con el
   código registrado.

---

### User Story 3 - Sin credenciales el proveedor se deshabilita con un motivo visible (Priority: P1)

Como responsable de la operación, quiero que un despliegue sin las credenciales del proveedor deje
constancia explícita de que está deshabilitado y por qué, para enterarme al arrancar.

**Why this priority**: Misma garantía que correo y SMS; permite listar el proveedor en el catálogo antes
de tener credenciales en todos los entornos, incluidos desarrollo e integración continua.

**Independent Test**: Arrancar sin alguna credencial y confirmar que (a) el arranque se completa, (b)
queda un registro que nombra al proveedor y la variable ausente, (c) sin valores de credencial y (d) una
notificación dirigida a él no se da por entregada, no se pierde y deja rastro consultable.

**Acceptance Scenarios**:

1. **Given** un despliegue sin alguna credencial requerida, o con una mal formada, **When** arranca,
   **Then** el arranque se completa y queda constancia explícita de deshabilitación nombrando el dato
   afectado, sin su valor.
2. **Given** un proveedor deshabilitado y preferente, **When** se despacha una notificación WhatsApp,
   **Then** no se marca como entregada ni como fallida por el proveedor, conserva su historial sin
   intentos nuevos y el fallo queda registrado con el motivo.
3. **Given** credenciales completas y bien formadas, **When** arranca, **Then** el proveedor queda
   habilitado y no se emite aviso de deshabilitación.

---

### User Story 4 - Reintentos idempotentes y recorrido de intentos como en los demás canales (Priority: P2)

Como responsable de la operación, quiero que cada respuesta del proveedor se traduzca a aceptada, fallo
recuperable o fallo permanente, y que un reintento no produzca un mensaje duplicado cuando el proveedor
permite evitarlo, para que los reintentos se disparen solo cuando sirven.

**Why this priority**: Reintentar un número sin WhatsApp o una plantilla rechazada gasta cuota y termina
igual; no reintentar un fallo temporal pierde mensajes. Depende de la entrega básica (P1).

**Independent Test**: Simular cada familia de respuesta del proveedor y confirmar la categoría y el
estado esperados; reentregar el mismo despacho y comprobar que el proveedor recibe la misma clave de
idempotencia o que el riesgo residual queda documentado si el proveedor no la admite.

**Acceptance Scenarios**:

1. **Given** el proveedor habilitado, **When** acepta el envío, **Then** el intento queda aceptado.
2. **Given** el proveedor habilitado, **When** rechaza por número sin WhatsApp, número inválido,
   plantilla rechazada, ventana cerrada, petición malformada o credenciales inválidas, **Then** el
   intento es fallo permanente y no se reintenta.
3. **Given** el proveedor habilitado, **When** responde con fallo temporal o límite de tasa, **Then** el
   intento es fallo recuperable y entra en el camino de reintentos existente.
4. **Given** una llamada que excede el tiempo de espera configurado o sin conexión, **When** se
   despacha, **Then** se corta en ese plazo y es fallo recuperable.
5. **Given** un despacho reentregado, **When** se reenvía al proveedor, **Then** la petición lleva un
   identificador de idempotencia derivado de la notificación y del número de intento, estable entre
   reentregas del mismo intento (si el proveedor elegido no lo admite, se
   documenta como riesgo residual).
6. **Given** varios intentos de una misma notificación, **When** se consulta su historial, **Then**
   aparecen en orden con proveedor, categoría y motivo, igual que en los demás canales.

---

### User Story 5 - Nada sensible sale del componente (Priority: P2)

Como responsable de seguridad, quiero que ni las credenciales, ni el contenido, ni los valores de las
variables de plantilla, ni el número completo del destinatario aparezcan en registros ni mensajes
internos, para que la integración no abra una fuga de datos personales.

**Why this priority**: Un número de teléfono es dato personal directo y el contenido de WhatsApp suele ser
sensible. Es P2 porque no bloquea la entrega, pero su incumplimiento sí bloquearía producción.

**Independent Test**: Ejecutar un despacho completo, capturar registros y mensajes publicados, y
confirmar que no aparecen credenciales, cuerpo, valores de variables ni el número completo (a lo sumo
los últimos cuatro dígitos).

**Acceptance Scenarios**:

1. **Given** un despacho completo, **When** se revisan los registros, **Then** no aparecen credenciales
   ni formas derivadas, ni cuerpo, ni valores de variables, y el número, si aparece, va enmascarado.
2. **Given** el mismo despacho, **When** se revisan los mensajes publicados internamente, **Then** no
   aparece ninguna credencial, contenido ni número.
3. **Given** un despacho que falla, **When** se revisa el registro, **Then** contiene notificación,
   tenant, proveedor, categoría y código de error del proveedor si lo hay, y ningún dato prohibido.

---

### User Story 6 - El contenido de un mensaje WhatsApp respeta la forma y el tamaño del canal (Priority: P3)

Como sistema cliente, quiero saber al enviar si mi contenido no cabe o no es válido en el canal, para no
descubrirlo tras el despacho.

**Why this priority**: Mejora la experiencia del cliente; la entrega (P1) aporta valor sin ella.

**Independent Test**: Enviar un cuerpo que excede el límite del canal y otro justo en el límite; enviar
una notificación con adjuntos y confirmar rechazo.

**Acceptance Scenarios**:

1. **Given** el canal WhatsApp, **When** se envía un cuerpo libre de exactamente el límite declarado,
   **Then** se acepta; con un carácter más, se rechaza en la aceptación con motivo que nombra el límite,
   sin persistir ni truncar.
2. **Given** el canal WhatsApp, **When** se envía una notificación con adjunto, **Then** se rechaza en
   la aceptación con motivo que nombra que el canal no admite adjuntos.
3. **Given** el canal WhatsApp, **When** se envía una notificación con asunto, **Then** se acepta y el
   asunto no viaja al proveedor, igual que SMS.
4. **Given** un destinatario sin formato telefónico internacional, **When** se despacha, **Then** el
   intento es fallo permanente sin llamar al proveedor.

---

### Edge Cases

- **Proveedor listado pero deshabilitado**: la notificación no se entrega ni se marca fallida por el
  proveedor; el despacho falla de forma trazable con el motivo. Error de configuración, no de entrega.
- **Destinatario sin cuenta de WhatsApp**: el proveedor rechaza y es fallo permanente; reintentar no
  cambia el resultado.
- **Destinatario que bloqueó al negocio o cuyo número no aceptó las condiciones**: fallo permanente.
- **Texto libre fuera de la ventana**: fallo permanente con motivo explícito; el cliente debe reenviar
  como plantilla (Q2).
- **Plantilla con idioma o variables que no coinciden con la aprobada**: rechazada por el proveedor,
  fallo permanente con código registrado.
- **Número de calidad limitada o límite de conversaciones por nivel alcanzado**: el proveedor lo
  comunica como límite de tasa o rechazo; si es límite de tasa, recuperable.
- **Entrega duplicada tras una caída entre la aceptación del proveedor y el registro**: el mensaje puede
  enviarse dos veces salvo que la clave de idempotencia lo evite; riesgo residual documentado.
- **Respuesta del proveedor que no encaja en ninguna familia**: rechazo atribuible a la petición →
  permanente; cualquier otro caso → recuperable.
- **Entorno con catálogo ya sembrado**: la siembra solo actúa sobre un catálogo vacío; el canal WhatsApp
  requiere un paso explícito de alta documentado.
- **Número de cuatro dígitos o menos**: se enmascara por completo en los registros.
- **Cuenta de pruebas del proveedor**: solo entrega a números autorizados y en un entorno restringido;
  apta para humo, no para producción.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE disponer de un proveedor de envío dedicado a WhatsApp, con identificador
  estable propio, detrás del puerto de envío existente, sin que el núcleo conozca al proveedor.
- **FR-002**: El catálogo DEBE declarar el canal WhatsApp y listar al proveedor real entre sus
  proveedores; el canal DEBE existir como valor admitido de canal en el contrato de aceptación.
- **FR-003**: Las credenciales del proveedor DEBEN llegar exclusivamente por variables de entorno o un
  mecanismo equivalente de secretos. NO DEBEN estar en el código, la configuración versionada, los
  registros ni los mensajes internos.
- **FR-004**: Si falta alguna credencial requerida, o el número de origen o el identificador de cuenta
  tienen formato inválido, el proveedor DEBE quedar deshabilitado con un motivo explícito que nombre el
  dato afectado y el servicio DEBE seguir arrancando. La deshabilitación NUNCA DEBE ser silenciosa.
- **FR-005**: El contenido de una notificación WhatsApp DEBE ser exactamente uno de dos: cuerpo de texto
  libre, o referencia a una plantilla aprobada con sus variables. Enviar ambos o ninguno DEBE rechazarse
  en la aceptación con un motivo que nombre los campos.
- **FR-006**: Un texto libre rechazado por el proveedor por estar fuera de la ventana de 24 h DEBE
  registrarse como fallo permanente con un motivo explícito, y NUNCA DEBE convertirse en silencio en una
  plantilla ni reintentarse.
- **FR-007**: Una notificación con plantilla DEBE trasladar al proveedor la referencia de la plantilla y
  sus variables, sin cuerpo libre, y DEBE rechazarse en la aceptación si la referencia o la forma de las
  variables incumplen lo declarado por el canal. La verificación de variables obligatorias de cada plantilla
  corresponde al proveedor: el componente no mantiene un registro de plantillas.
- **FR-008**: El sistema DEBE traducir cada respuesta del proveedor a exactamente una de las tres
  categorías existentes (aceptada, fallo recuperable, fallo permanente). Como mínimo: aceptación →
  aceptada; tiempo agotado, imposibilidad de conectar, fallo temporal y límite de tasa → recuperable;
  número inválido, sin WhatsApp, plantilla rechazada o inexistente, ventana cerrada, petición malformada
  y credenciales inválidas → permanente. La categoría la decide el código de respuesta del protocolo; el
  código de error propio del proveedor se registra sin alterarla.
- **FR-009**: El canal DEBE declarar en el catálogo su forma de contenido: cuerpo libre con longitud
  máxima configurable por entorno, asunto opcional e ignorado por el proveedor, y cero adjuntos
  admitidos. Un cuerpo que exceda el límite o una notificación con adjuntos DEBEN rechazarse en la
  aceptación con motivo explícito; NUNCA DEBE truncarse.
- **FR-010**: Toda llamada al proveedor DEBE tener un tiempo de espera explícito y configurable; una
  llamada que lo supere DEBE cortarse y clasificarse como recuperable.
- **FR-011**: La petición al proveedor DEBE incluir una clave de idempotencia estable derivada de la
  notificación y del intento, de modo que una reentrega del mismo intento no genere un segundo mensaje
  cuando el proveedor lo permita. Si el proveedor elegido no la admite, DEBE documentarse como riesgo
  residual con dueño y fecha.
- **FR-012**: Antes de llamar al proveedor, el sistema DEBE comprobar que el destinatario tiene formato
  telefónico internacional; si no, el intento DEBE ser fallo permanente sin llamar al proveedor, con
  motivo que nombre el dato y no su valor.
- **FR-013**: Los registros NO DEBEN incluir cuerpo, valores de variables de plantilla, número completo
  ni credenciales. El número, si se registra, DEBE ir enmascarado con a lo sumo sus últimos cuatro
  dígitos. Los registros SÍ DEBEN incluir notificación, tenant, proveedor, categoría y, en un rechazo,
  el código de error del proveedor; NUNCA el texto del mensaje de error del proveedor.
- **FR-014**: El intento DEBE quedar registrado con el identificador del proveedor real cuando sea ese
  proveedor el que ejecutó el envío, y el recorrido de intentos DEBE comportarse como en los demás
  canales (orden, categoría, motivo, reintentos con la política existente).
- **FR-015**: La lista de proveedores del canal DEBE poder configurarse por entorno sin modificar código,
  con un valor por defecto seguro para un entorno sin credenciales (simulado como preferente).
- **FR-016**: El sistema NO DEBE, en esta historia, exponer un endpoint de webhook ni consumir estados de
  entrega ni mensajes entrantes del proveedor.
- **FR-017**: Los proveedores simulado, de correo, de SMS y de push DEBEN seguir funcionando exactamente
  igual, y las pruebas de arquitectura hexagonal y de modularidad DEBEN seguir en verde.
- **FR-018**: El aislamiento por tenant DEBE conservarse: la ruta del canal WhatsApp se resuelve por el
  catálogo del tenant de la notificación y ninguna plantilla, credencial o dato de un tenant DEBE cruzar
  a otro.

### Key Entities *(include if feature involves data)*

- **Proveedor real de WhatsApp**: nuevo proveedor de envío con identificador estable propio; conoce
  credenciales y número de origen inyectados por entorno. No persiste entidades nuevas.
- **Ruta del canal WhatsApp**: nueva entrada del catálogo (entidad existente) con lista de proveedores
  (por defecto: simulado, real) y forma de contenido propia (cuerpo libre o plantilla, límite de
  longitud, asunto ignorado, cero adjuntos).
- **Plantilla del proveedor** (`providerTemplate`, tipo `ProviderTemplateReference`): referencia a una
  plantilla aprobada por el proveedor (`templateId`) y un conjunto de variables con nombre; viaja dentro de
  la notificación y no se almacena como entidad propia.
- **Resultado del intento**: entidad existente; esta historia define cómo se deriva de las respuestas del
  proveedor de WhatsApp, incluida la clave de idempotencia.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el proveedor real habilitado y preferente, el 100 % de las notificaciones WhatsApp
  despachadas producen exactamente una petición al proveedor con destinatario, origen y contenido, y
  terminan entregadas con el identificador del proveedor real.
- **SC-002**: En un entorno sembrado desde cero, el canal WhatsApp existe en el catálogo y lista al
  proveedor real.
- **SC-003**: En un arranque sin alguna credencial o con una mal formada, el 100 % de los arranques se
  completan y dejan exactamente un registro que nombra al proveedor y el dato afectado, sin su valor.
- **SC-004**: Cero apariciones de credenciales, cuerpo, valores de variables y número completo en
  registros y mensajes internos durante un despacho completo, verificado automáticamente.
- **SC-005**: Cada familia de respuesta del proveedor (aceptación, número sin WhatsApp, número inválido,
  plantilla rechazada, ventana cerrada, credenciales inválidas, límite de tasa, fallo temporal, tiempo
  agotado, sin conexión) produce la categoría esperada en el 100 % de los casos, verificado
  automáticamente.
- **SC-006**: Ninguna llamada al proveedor excede el tiempo de espera configurado; una respuesta más
  lenta se corta dentro de ese plazo y es recuperable, verificado con una aserción explícita de duración.
- **SC-007**: Una notificación con plantilla produce una petición al proveedor sin cuerpo libre y con
  todas sus variables; una con plantilla y cuerpo, o sin ninguno, se rechaza en la aceptación con cero
  peticiones al proveedor.
- **SC-008**: Una reentrega del mismo intento produce peticiones con la misma clave de idempotencia, y
  intentos distintos de la misma notificación producen claves distintas, verificado automáticamente.
- **SC-009**: Con la configuración por defecto y sin credenciales, cero regresiones en la suite
  existente y una notificación WhatsApp se acepta y entrega por el simulado.
- **SC-010**: Existe una prueba de punta a punta automatizada contra un servidor que simula al proveedor
  y cero pruebas de la suite llaman al proveedor real; existe además un procedimiento manual de humo con
  cuenta de pruebas real, ejecutable fuera de la integración continua.
- **SC-011**: Una prueba con dos tenants distintos confirma que la ruta, la plantilla y los resultados de
  uno no son visibles ni utilizables por el otro.
- **SC-012**: Una notificación con cuerpo mayor al límite, con adjunto o con destinatario sin formato
  internacional produce, respectivamente, rechazo en la aceptación (con cero notificaciones persistidas)
  y fallo permanente sin petición al proveedor.

## Out of Scope

- Webhook de estados de entrega (entregado, leído) y mensajes entrantes, incluida la validación de su
  firma y el rastreo de la ventana de 24 h por el sistema (Q2 y Q3; historia propia).
- Adjuntos multimedia y mensajes interactivos (botones, listas, ubicación) (Q4).
- Gestión del ciclo de vida de plantillas ante el proveedor (alta, aprobación, versiones): el componente
  usa plantillas ya aprobadas.
- Conmutación entre proveedores, cortacircuitos, límite de tasa propio y gestión de secretos de plataforma
  (historias existentes de la épica).
- Opt-in y preferencias del destinatario por canal.
- Verificación de negocio y registro del número ante Meta.

## Assumptions

- El usuario aporta una cuenta de Twilio, la misma de HU2-090, con WhatsApp habilitado: una cuenta de pruebas con un número de origen habilitado
  para WhatsApp y un destinatario autorizado. Sin ellos el proveedor queda deshabilitado (FR-004) y la
  historia se valida contra un servidor que lo simula.
- "Aceptada" significa que el proveedor asumió el envío, no que el teléfono lo recibió: misma semántica
  que los demás canales.
- El cliente conoce las plantillas aprobadas disponibles para su operación y decide por notificación si
  usa plantilla o texto libre (Q2-A).
- Las plantillas se identifican por nombre o identificador del proveedor más idioma; el catálogo no las
  enumera en esta historia.
- El canal se siembra con el simulado como preferente por defecto; producción invierte el orden por
  entorno.
- La siembra del catálogo actúa solo sobre un catálogo vacío; entornos ya sembrados requieren un paso
  manual documentado.
- El destinatario se transmite en formato telefónico internacional sin validación de pertenencia a
  WhatsApp previa al envío.

## Risks

- **Proveedor intermediario (Q1-A)**: recargo por mensaje y dependencia de Twilio; Meta directo queda como adaptador futuro. Dueño: andrualv. Fecha de revisión: 2026-12-31.
- **Ventana de 24 h delegada al cliente (Q2-A)**: el cliente puede enviar texto libre fuera de ventana y
  descubrir el rechazo consultando el estado. Dueño: andrualv. Fecha de revisión: 2026-12-31.
- **Webhook de estados diferido (Q3-A)**: sin confirmación real de entrega ni validación de webhook
  exigida por ADR-28. Dueño: andrualv. Fecha de revisión: 2026-12-31.
- **Mensaje duplicado tras una reentrega** si el proveedor no admite idempotencia (FR-011). Dueño:
  andrualv. Fecha de revisión: 2026-11-30.
- **Costo por conversación y aprobación de plantillas por Meta**: el costo no está acotado por esta
  historia y las plantillas tardan en aprobarse. Dueño: andrualv. Fecha de revisión: 2026-11-30.
- **Sin límite de tasa propio ni gestión de secretos de plataforma**: igual que SMS. Dueño: andrualv.
  Fecha de revisión: 2026-11-30.
