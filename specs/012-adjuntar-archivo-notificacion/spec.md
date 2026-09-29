# Feature Specification: Adjuntar un archivo a una notificación

**Feature Branch**: `feature/HU2-092-adjuntar-archivo-notificacion`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "Como sistema cliente, quiero poder adjuntar un archivo a una notificación,
para enviar comprobantes, documentos o imágenes junto con el mensaje de una forma segura."

**Trazabilidad**: CU-01 (aceptar una notificación), épica A — Ingesta y ciclo de vida, Fase 3. Requisito
nuevo: no forma parte de la línea base de requisitos funcionales y no funcionales del proyecto; se agrega
por decisión del equipo. Cubre solo el modelo del adjunto, su validación en la aceptación y el contrato
genérico. El envío real del adjunto por cada proveedor (correo, SMS y push) queda para historias propias de
cada canal, que esta historia no bloquea ni implementa.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enviar una notificación con un archivo adjunto (Priority: P1)

Como sistema cliente, quiero incluir un archivo (un comprobante, un documento o una imagen) al pedir una
notificación por un canal que acepta adjuntos, para que el destinatario lo reciba junto con el mensaje sin
tener que buscarlo por otro medio.

**Why this priority**: Es la razón de ser de la historia. Sin ella no hay forma de asociar un archivo a una
notificación.

**Independent Test**: Con un canal que declara aceptar adjuntos de tipo PDF hasta un tamaño dado, pedir una
notificación con un PDF dentro del límite y confirmar que se acepta, que la notificación guardada conserva
el adjunto (nombre, tipo y tamaño) y que llega a entregada por el proveedor simulado.

**Acceptance Scenarios**:

1. **Given** un canal que acepta adjuntos PDF de hasta 5 MB, **When** el cliente pide una notificación con
   un PDF de 1 MB, **Then** la notificación se acepta y queda registrada con su adjunto.
2. **Given** una notificación aceptada con adjunto, **When** se despacha por un proveedor que sabe enviar
   adjuntos, **Then** el proveedor recibe la notificación con el adjunto completo.
3. **Given** una notificación sin adjunto, **When** el cliente la pide por cualquier canal, **Then** se
   comporta exactamente igual que antes de esta historia.
4. **Given** la misma notificación (mismo identificador externo del cliente) pedida dos veces con adjunto,
   **When** llega la segunda, **Then** se devuelve la primera como duplicada, igual que sin adjunto.

---

### User Story 2 - Rechazar completo lo que el canal no admite (Priority: P1)

Como sistema cliente, quiero que una notificación con un adjunto inválido (demasiado grande, de un tipo no
admitido o para un canal que no acepta adjuntos) se rechace completa en el momento de pedirla, con un motivo
claro, para no creer que se envió algo que el destinatario nunca recibirá completo.

**Why this priority**: Aceptar la notificación sin el adjunto, o con un adjunto que el canal no puede
entregar, es una entrega a medias silenciosa: el cliente cree que el comprobante salió y no salió.

**Independent Test**: Pedir, por separado, una notificación con un adjunto que excede el límite del canal,
otra con un tipo no admitido y otra por un canal que no declara adjuntos; confirmar que las tres se
rechazan como solicitud inválida con un motivo que identifica el adjunto y la regla incumplida, y que
ninguna queda registrada ni encolada.

**Acceptance Scenarios**:

1. **Given** un canal que acepta adjuntos de hasta 5 MB, **When** el cliente pide una notificación con un
   adjunto de 6 MB, **Then** la notificación completa se rechaza como solicitud inválida y no queda
   registrada ni encolada.
2. **Given** un canal que acepta solo PDF, **When** el cliente pide una notificación con una imagen,
   **Then** se rechaza completa con un motivo que nombra el tipo no admitido.
3. **Given** un canal que no declara aceptar adjuntos, **When** el cliente pide una notificación con un
   adjunto, **Then** se rechaza completa con un motivo que dice que el canal no acepta adjuntos.
4. **Given** un adjunto sin nombre, sin tipo o con tamaño cero o negativo, **When** el cliente lo envía,
   **Then** se rechaza completa como solicitud inválida.
5. **Given** un rechazo por adjunto inválido, **When** el cliente lee el motivo, **Then** el motivo
   identifica qué adjunto y qué regla falló, y no contiene el contenido del archivo.

---

### User Story 3 - Cada canal declara qué adjuntos admite (Priority: P2)

Como operador del servicio, quiero declarar por canal en el catálogo si acepta adjuntos, de qué tipos y
hasta qué tamaño, igual que hoy declaro el largo máximo del mensaje, para ajustar las reglas de cada canal
a lo que sus proveedores pueden entregar sin cambiar el núcleo del servicio.

**Why this priority**: Es lo que hace la historia extensible por canal: las historias de correo, SMS y push
solo tendrán que ajustar la declaración de su canal y su proveedor. Sin esto las reglas quedarían fijas en
el código.

**Independent Test**: Cambiar en el catálogo el tamaño máximo de adjunto de un canal y confirmar que, tras
el refresco del catálogo, un adjunto que antes se aceptaba ahora se rechaza (y viceversa), sin reiniciar el
servicio; y que la consulta del catálogo muestra la nueva declaración.

**Acceptance Scenarios**:

1. **Given** un canal que declara aceptar PDF y PNG hasta 5 MB, **When** se consulta el catálogo, **Then**
   la declaración de adjuntos del canal aparece junto a su forma de contenido.
2. **Given** un cambio guardado en la declaración de adjuntos de un canal, **When** el catálogo se
   refresca, **Then** la aceptación aplica la nueva regla.
3. **Given** un canal que declara un tamaño máximo por encima del tope global del servicio, **When** un
   cliente envía un adjunto entre ambos, **Then** se rechaza: el tope global siempre manda.

---

### User Story 4 - El contenido del adjunto nunca queda expuesto (Priority: P1)

Como responsable de la operación, quiero que el contenido del archivo adjunto nunca aparezca en los
registros del servicio, en los mensajes de error ni en los eventos que el servicio publica, y que solo se
registren su nombre, tipo y tamaño, para no filtrar documentos de los destinatarios (comprobantes,
identificaciones, facturas).

**Why this priority**: "De una forma segura" es parte de la historia. Un comprobante en un registro es una
fuga de datos personales que dura lo que dure la retención de los registros.

**Independent Test**: Enviar un adjunto con un contenido reconocible, una vez válido y otra vez inválido;
capturar los registros, la respuesta de error y los eventos publicados; confirmar que el contenido
reconocible no aparece en ninguno, mientras que el nombre, el tipo y el tamaño sí aparecen en el registro
(control positivo).

**Acceptance Scenarios**:

1. **Given** una notificación aceptada con adjunto, **When** se revisan los registros del servicio,
   **Then** aparecen el nombre, el tipo y el tamaño del adjunto y no su contenido.
2. **Given** una notificación rechazada por su adjunto, **When** se revisan la respuesta y los registros,
   **Then** ninguno contiene el contenido del adjunto.
3. **Given** cualquier notificación con adjunto, **When** se revisan los eventos publicados y el mensaje
   de despacho, **Then** ninguno contiene el contenido del adjunto.

---

### User Story 5 - Nunca entregar una notificación sin su adjunto (Priority: P2)

Como sistema cliente, quiero que una notificación con adjunto nunca se marque como entregada si el
proveedor que la despacha no sabe enviar adjuntos, para que un estado "entregada" signifique siempre
"entregada con su archivo".

**Why this priority**: Hoy ningún proveedor real sabe enviar adjuntos (eso llega con las historias por
canal). Si un operador declara adjuntos en un canal cuyo proveedor aún no los soporta, la notificación no
debe salir sin el archivo.

**Independent Test**: Declarar adjuntos en un canal cuyo proveedor preferente no sabe enviarlos, pedir una
notificación con adjunto y confirmar que termina fallida sin haberse enviado, con el motivo trazable.

**Acceptance Scenarios**:

1. **Given** una notificación aceptada con adjunto y un proveedor preferente que no sabe enviar adjuntos,
   **When** se despacha, **Then** [NEEDS CLARIFICATION: ¿qué pasa en el despacho con una notificación con
   adjunto cuando el proveedor elegido no sabe enviar adjuntos: falla sin enviarse, se envía sin el
   adjunto, o no es parte de esta historia?]

---

### Edge Cases

- **Tamaño exactamente igual al límite del canal**: se acepta; el límite es inclusivo, igual que el largo
  máximo del mensaje.
- **Tipo declarado con mayúsculas o parámetros** (por ejemplo `application/PDF` o
  `application/pdf; charset=binary`): se compara el tipo sin distinguir mayúsculas y sin parámetros.
- **Nombre de archivo con separadores de ruta, caracteres de control o más de 255 caracteres**: se rechaza;
  el nombre es solo un nombre, nunca una ruta.
- **Más adjuntos de los que admite el canal**: se rechaza completa. [NEEDS CLARIFICATION: ¿una
  notificación lleva a lo sumo un adjunto, o puede llevar varios hasta un máximo por canal?]
- **Lote de notificaciones con un elemento con adjunto inválido**: [ver Clarifications]
- **Catálogo cambiado entre la aceptación y el despacho** (el canal deja de aceptar adjuntos): la
  notificación ya aceptada conserva su adjunto; qué ocurre en el despacho lo gobierna la User Story 5.
- **Canal que declara aceptar adjuntos pero sin ningún tipo permitido**: equivale a no aceptar adjuntos.
- **Notificación duplicada (mismo identificador externo) con un adjunto distinto**: se devuelve la
  original como duplicada; el nuevo adjunto no se valida contra la original ni la reemplaza.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El componente MUST permitir que una solicitud de notificación incluya un archivo adjunto
  descrito por su nombre, su tipo de contenido y su tamaño en bytes, además del propio archivo.
  [NEEDS CLARIFICATION: ¿el archivo viaja embebido en la propia solicitud (su contenido codificado) o como
  una referencia (dirección) a un archivo que el cliente ya aloja en otro lugar?]
- **FR-002**: El adjunto MUST ser opcional: una notificación sin adjunto se acepta, valida, guarda y
  despacha exactamente igual que antes de esta historia.
- **FR-003**: Cada canal del catálogo MUST poder declarar si acepta adjuntos y, si los acepta, los tipos de
  contenido permitidos, el tamaño máximo por adjunto y la cantidad máxima, con el mismo mecanismo con que
  hoy declara el largo máximo del mensaje.
- **FR-004**: Un canal que no declara aceptar adjuntos MUST rechazar toda notificación con adjunto.
- **FR-005**: El componente MUST imponer un tope global de tamaño por adjunto y de cantidad de adjuntos por
  notificación, que ninguna declaración de canal puede superar.
- **FR-006**: Una notificación con un adjunto que incumple cualquier regla (canal que no acepta adjuntos,
  tipo no permitido, tamaño mayor al permitido, cantidad mayor a la permitida, nombre inválido, tipo o
  tamaño ausentes o mal formados) MUST rechazarse completa en la aceptación, como solicitud inválida. Nunca
  se acepta la notificación sin el adjunto ni con el adjunto recortado, y nada queda registrado ni
  encolado.
- **FR-007**: El motivo del rechazo MUST identificar el adjunto (por su posición y su nombre) y la regla
  incumplida, y MUST NOT contener el contenido del adjunto.
- **FR-008**: Una notificación aceptada con adjunto MUST conservar su adjunto (nombre, tipo, tamaño y
  archivo) de forma duradera, de modo que el despacho, incluidos los reintentos y el reencolado de
  pendientes, disponga del adjunto completo.
- **FR-009**: Los registros del servicio, los mensajes de error, los eventos publicados y los mensajes de
  despacho MUST NOT contener el contenido del adjunto. Cuando un registro mencione un adjunto, MUST
  limitarse a su nombre, su tipo y su tamaño.
- **FR-010**: La aceptación de una notificación con adjunto y el rechazo por adjunto inválido MUST quedar
  registrados con el tenant, el identificador externo y el nombre, tipo y tamaño de cada adjunto.
- **FR-011**: La consulta del catálogo MUST mostrar, por canal, la declaración de adjuntos vigente.
- **FR-012**: La idempotencia por identificador externo MUST mantenerse: una solicitud duplicada devuelve
  la notificación original sin validar ni guardar el nuevo adjunto.
- **FR-013**: Las operaciones de envío individual y en lote MUST quedar descritas en el contrato público
  con el adjunto antes de implementarse, incluidos los motivos de rechazo.
- **FR-014**: Se aplica lo que decida la User Story 5 para el despacho de una notificación con adjunto por
  un proveedor que no sabe enviarlo.

### Key Entities *(include if feature involves data)*

- **Adjunto**: archivo asociado a una notificación. Atributos: nombre de archivo, tipo de contenido,
  tamaño en bytes y el archivo en sí (la forma depende de FR-001). Pertenece a una sola notificación y se
  guarda con ella; no se comparte entre notificaciones.
- **Declaración de adjuntos del canal**: parte de la definición del canal en el catálogo. Indica si el
  canal acepta adjuntos, los tipos de contenido permitidos, el tamaño máximo por adjunto y la cantidad
  máxima. Ausente = el canal no acepta adjuntos.
- **Tope global de adjuntos**: límite del servicio (tamaño por adjunto y cantidad por notificación) que
  acota cualquier declaración de canal.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de las notificaciones con un adjunto válido para su canal se acepta y llega a
  entregada por el proveedor simulado con el mismo nombre, tipo y tamaño que envió el cliente (verificado
  de punta a punta).
- **SC-002**: El 100 % de las notificaciones con un adjunto inválido (una por cada regla de FR-006) se
  rechaza en la aceptación, y en cero casos queda una notificación registrada o encolada por ellas.
- **SC-003**: Cero apariciones del contenido de un adjunto reconocible en los registros, las respuestas de
  error, los eventos publicados y los mensajes de despacho capturados durante la prueba de punta a punta,
  mientras el nombre, el tipo y el tamaño sí aparecen en los registros (control positivo).
- **SC-004**: Las notificaciones sin adjunto no cambian de comportamiento: todas las pruebas existentes de
  aceptación, lote, despacho y consulta siguen pasando sin modificar sus expectativas.
- **SC-005**: Un cambio en la declaración de adjuntos de un canal se aplica a la aceptación en menos de 5
  segundos cuando el refresco del catálogo está configurado cada segundo.

## Out of Scope

- Enviar el adjunto por un proveedor real: correo, SMS y push tienen historias propias.
- Transformar el archivo (redimensionar imágenes, comprimir, convertir formato).
- Analizar el archivo en busca de software malicioso.
- Mostrar o descargar el adjunto desde las consultas de estado, de histórico o de actualizaciones en vivo.
- Adjuntos distintos por destinatario dentro de un lote.
- Registro de canales por API: la declaración de adjuntos se configura igual que hoy la forma de contenido.

## Assumptions

- El tope global inicial es de 10 MB por adjunto; cada canal puede declarar un límite menor.
- Los tipos de contenido se expresan como tipos de medio estándar (por ejemplo `application/pdf`,
  `image/png`) y se comparan exactos, sin comodines.
- El identificador de tenant sigue siendo el sustituto provisional de la identidad del cliente.
- La configuración por defecto no declara adjuntos en ningún canal mientras ningún proveedor real sepa
  enviarlos; las pruebas habilitan un canal con el proveedor simulado.
