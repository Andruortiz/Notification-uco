# Feature Specification: Adjuntar un archivo a una notificación

**Feature Branch**: `feature/HU2-092-adjuntar-archivo-notificacion`

**Created**: 2026-09-28

**Status**: Clarificado con respuestas recomendadas — Q1–Q5 pendientes de confirmación del usuario al aprobar
el plan. Q1 (cómo viaja el archivo) es una decisión reservada expresamente por el usuario: la respuesta
registrada es solo una recomendación y el plan indica qué cambia si se elige la otra.

**Input**: User description: "Como sistema cliente, quiero poder adjuntar un archivo a una notificación,
para enviar comprobantes, documentos o imágenes junto con el mensaje de una forma segura."

**Trazabilidad**: CU-01 (aceptar una notificación), épica A — Ingesta y ciclo de vida, Fase 3. Requisito
nuevo: no forma parte de la línea base de requisitos funcionales y no funcionales del proyecto; se agrega
por decisión del equipo. Cubre solo el modelo del adjunto, su validación en la aceptación y el contrato
genérico. El envío real del adjunto por cada proveedor (correo, SMS y push) queda para historias propias de
cada canal, que esta historia no bloquea ni implementa.

## Clarifications

### Session 2026-09-28 — pendientes de confirmación

No fue posible consultar al usuario durante la redacción. Cada pregunta queda registrada con una respuesta
**recomendada**, que el usuario confirma o cambia al aprobar el plan. Para cada una se indica qué partes del
spec cambiarían con la otra respuesta.

- **Q1 — ¿El archivo viaja embebido en la propia solicitud de notificación (su contenido codificado en
  texto) o como una referencia (una dirección web) a un archivo que el cliente ya aloja en otro lugar?**
  Es la decisión que más cambia el diseño, y el usuario la reservó para esta fase.
  - **Opción A — Embebido**: el cliente envía el contenido del archivo codificado dentro de la solicitud.
    El servicio puede comprobar el tamaño real y el tipo real (por la firma de los primeros bytes) y el
    cliente no necesita alojar nada. A cambio: el servicio pasa a guardar archivos (un almacén de archivos
    aparte de la notificación, porque un archivo de varios MB no cabe con holgura en el registro de la
    notificación); el modelo de la notificación, que hoy es solo texto, gana contenido binario; las
    solicitudes crecen un tercio sobre el tamaño del archivo por la codificación, y el servicio debe
    aceptar cuerpos de hasta decenas de MB y mantenerlos en memoria mientras valida; hace falta una
    política de retención y borrado de los archivos guardados; y para SMS y push, cuyos proveedores solo
    aceptan una dirección pública del archivo, el servicio tendría además que publicar el archivo en una
    dirección accesible desde el proveedor (fuera de esta historia, pero la decisión lo impone).
  - **Opción B — Referencia**: el cliente envía una dirección web segura (https) donde ya aloja el
    archivo, junto con su nombre, tipo y tamaño declarados. La solicitud sigue siendo pequeña, el servicio
    no guarda archivos ni los descarga, y los tres proveedores previstos (correo, SMS y push) aceptan una
    dirección de archivo de forma nativa. A cambio: el tamaño y el tipo se validan sobre lo que **declara**
    el cliente, no sobre el archivo real (el proveedor rechazará al despachar un archivo que no coincida);
    el cliente es responsable de alojar el archivo y de que la dirección siga vigente durante los
    reintentos; y la dirección en sí puede contener un permiso de acceso temporal, así que se trata como
    dato sensible (nunca en registros, errores ni eventos).
  - **Opción C — Ambas**: el cliente elige por adjunto. Suma el costo de A y de B.
  - **Respuesta recomendada (pendiente de confirmación)**: **B — referencia**. Mantiene la historia en su
    alcance (modelo, validación y contrato), no introduce un almacén de archivos ni su retención, no infla
    la aceptación, y coincide con lo que consumen los tres proveedores previstos. La pérdida de A (tamaño y
    tipo verificados sobre el archivo real) se acota con el tope global, la lista de tipos permitidos y la
    regla de Q4, y queda como riesgo documentado.
  - Si se elige A: FR-001, FR-008 y FR-009 pasan a hablar del contenido del archivo; se agregan un almacén
    de archivos, la verificación del tipo real, un límite de tamaño de la solicitud y una política de
    retención; las Assumptions sobre la dirección web se retiran; y el riesgo "tamaño declarado" desaparece.
    El plan detalla el delta técnico.
  - Afecta a: FR-001, FR-008, FR-009, FR-016, Key Entities, Edge Cases, Risks.
- **Q2 — ¿Una notificación lleva a lo sumo un adjunto, o puede llevar varios hasta un máximo por canal?**
  - Opciones: (A) exactamente cero o uno; (B) una lista, con un máximo por canal y un tope global de 5;
    (C) una lista sin máximo por canal, solo el tope global.
  - **Respuesta recomendada (pendiente de confirmación)**: **B**. El contrato nace como lista y no hay que
    romperlo cuando el correo necesite factura y comprobante juntos; cada canal fija su máximo (el push, por
    ejemplo, solo admitirá una imagen). La historia sigue cubriendo el caso "un archivo" como el más común.
  - Si se elige A: la cantidad máxima desaparece de FR-003 y FR-005 y el adjunto deja de ser una lista.
  - Afecta a: FR-001, FR-003, FR-005, FR-006, Edge Cases.
- **Q3 — ¿La declaración de adjuntos de cada canal se escribe dentro de la forma de contenido del canal
  (el mismo esquema donde hoy se fija el largo máximo del mensaje) o como una sección propia del canal en
  el catálogo, y qué canales aceptan adjuntos con la configuración por defecto?**
  - Opciones: (A) dentro de la forma de contenido; un canal acepta adjuntos solo si su forma de contenido
    los declara; la configuración por defecto no los declara en ningún canal; (B) una sección propia del
    canal (tipos, tamaño, cantidad), con la misma regla por defecto; (C) como A, pero la configuración por
    defecto los habilita en EMAIL.
  - **Respuesta recomendada (pendiente de confirmación)**: **A**. Es el mismo mecanismo que ya fija el largo
    máximo del mensaje, la consulta del catálogo ya lo muestra sin cambiar su contrato y extender la
    validación de la forma de contenido es exactamente el área que esta historia toca. Por defecto ningún
    canal acepta adjuntos porque ningún proveedor real sabe enviarlos todavía; cada historia de canal
    habilitará el suyo junto con su proveedor.
  - Si se elige B: FR-003 y FR-011 describen una sección nueva del canal y la consulta del catálogo gana un
    campo. Si se elige C: la regla de Q4 se vuelve la única barrera en despliegues con un proveedor real de
    correo en la posición preferente.
  - Afecta a: FR-003, FR-004, FR-011, User Story 3, Assumptions.
- **Q4 — ¿Qué pasa en el despacho con una notificación con adjunto cuando el proveedor elegido no sabe
  enviar adjuntos?**
  - Opciones: (A) falla definitivamente sin enviarse, sin reintentos, con el proveedor en su historial; (B) se envía
    sin el adjunto; (C) no es parte de esta historia.
  - **Respuesta recomendada (pendiente de confirmación)**: **A**. B es una entrega a medias silenciosa,
    contraria a la historia; C deja abierta esa misma entrega a medias en cuanto un operador declare
    adjuntos en un canal cuyo proveedor aún no los soporta. Cada proveedor declara si sabe enviar adjuntos;
    hoy solo el simulado.
  - Afecta a: User Story 5, FR-014, SC-006.
- **Q5 — En un envío en lote, ¿un elemento con un adjunto inválido se rechaza solo, o rechaza el lote
  completo?**
  - Opciones: (A) se rechaza solo ese elemento, con su motivo, y los demás siguen; (B) se rechaza el lote.
  - **Respuesta recomendada (pendiente de confirmación)**: **A**. Es la regla que el lote ya aplica a un
    contenido inválido: cada elemento se valida por separado y un elemento inválido no bloquea a los demás.
    "Rechazar completa" se refiere a la notificación del elemento, que nunca se acepta sin su adjunto.
  - Afecta a: FR-015, Edge Cases.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enviar una notificación con un archivo adjunto (Priority: P1)

Como sistema cliente, quiero incluir uno o varios archivos (un comprobante, un documento o una imagen) al
pedir una notificación por un canal que acepta adjuntos, para que el destinatario los reciba junto con el
mensaje sin tener que buscarlos por otro medio.

**Why this priority**: Es la razón de ser de la historia. Sin ella no hay forma de asociar un archivo a una
notificación.

**Independent Test**: Con un canal que declara aceptar adjuntos PDF hasta un tamaño dado y el proveedor
simulado, pedir una notificación con un PDF dentro del límite y confirmar que se acepta, que la
notificación guardada conserva el adjunto (nombre, tipo, tamaño y archivo) y que llega a entregada.

**Acceptance Scenarios**:

1. **Given** un canal que acepta adjuntos PDF de hasta 5 MB, **When** el cliente pide una notificación con
   un PDF de 1 MB, **Then** la notificación se acepta y queda registrada con su adjunto.
2. **Given** una notificación aceptada con adjunto, **When** se despacha por un proveedor que sabe enviar
   adjuntos, **Then** el proveedor recibe la notificación con sus adjuntos completos y en el orden enviado.
3. **Given** una notificación sin adjunto, **When** el cliente la pide por cualquier canal, **Then** se
   comporta exactamente igual que antes de esta historia.
4. **Given** la misma notificación (mismo identificador externo del cliente) pedida dos veces con adjunto,
   **When** llega la segunda con adjuntos válidos, **Then** se devuelve la primera como duplicada, igual
   que sin adjunto, y sus adjuntos no cambian.
5. **Given** una notificación con adjunto que queda pendiente de reintento, **When** se reintenta,
   **Then** el proveedor vuelve a recibir los mismos adjuntos.

---

### User Story 2 - Rechazar completo lo que el canal no admite (Priority: P1)

Como sistema cliente, quiero que una notificación con un adjunto inválido (demasiado grande, de un tipo no
admitido, en mayor cantidad de la permitida o para un canal que no acepta adjuntos) se rechace completa en
el momento de pedirla, con un motivo claro, para no creer que se envió algo que el destinatario nunca
recibirá completo.

**Why this priority**: Aceptar la notificación sin el adjunto, o con un adjunto que el canal no puede
entregar, es una entrega a medias silenciosa: el cliente cree que el comprobante salió y no salió.

**Independent Test**: Pedir, por separado, una notificación por cada regla de FR-006 y confirmar que todas
se rechazan como solicitud inválida con un motivo que identifica el adjunto y la regla incumplida, y que
ninguna queda registrada ni encolada.

**Acceptance Scenarios**:

1. **Given** un canal que acepta adjuntos de hasta 5 MB, **When** el cliente pide una notificación con un
   adjunto de 6 MB, **Then** la notificación completa se rechaza como solicitud inválida y no queda
   registrada ni encolada.
2. **Given** un canal que acepta solo PDF, **When** el cliente pide una notificación con una imagen,
   **Then** se rechaza completa con un motivo que nombra el tipo no admitido.
3. **Given** un canal que no declara aceptar adjuntos, **When** el cliente pide una notificación con un
   adjunto, **Then** se rechaza completa con un motivo que dice que el canal no acepta adjuntos.
4. **Given** un adjunto sin nombre, sin tipo, con tamaño cero o negativo, o con una referencia que no es
   una dirección https válida, **When** el cliente lo envía, **Then** se rechaza completa como solicitud
   inválida.
5. **Given** un canal que admite hasta 2 adjuntos, **When** el cliente envía 3, **Then** se rechaza
   completa.
6. **Given** un rechazo por adjunto inválido, **When** el cliente lee el motivo, **Then** el motivo
   identifica qué adjunto (por su posición) y qué regla falló, y no contiene la referencia al archivo.

---

### User Story 3 - Cada canal declara qué adjuntos admite (Priority: P2)

Como operador del servicio, quiero declarar por canal en el catálogo si acepta adjuntos, de qué tipos, de
qué tamaño máximo y cuántos, en la misma forma de contenido donde hoy declaro el largo máximo del mensaje
(Q3), para ajustar las reglas de cada canal a lo que sus proveedores pueden entregar sin cambiar el núcleo
del servicio.

**Why this priority**: Es lo que hace la historia extensible por canal: las historias de correo, SMS y push
solo tendrán que ajustar la declaración de su canal y su proveedor. Sin esto las reglas quedarían fijas en
el código.

**Independent Test**: Cambiar en el catálogo el tamaño máximo de adjunto de un canal y confirmar que, tras
el refresco del catálogo, un adjunto que antes se aceptaba ahora se rechaza, sin reiniciar el servicio; y
que la consulta del catálogo muestra la nueva declaración.

**Acceptance Scenarios**:

1. **Given** un canal que declara aceptar PDF y PNG hasta 5 MB, **When** se consulta el catálogo, **Then**
   la declaración de adjuntos aparece dentro de la forma de contenido del canal.
2. **Given** un cambio guardado en la declaración de adjuntos de un canal, **When** el catálogo se
   refresca, **Then** la aceptación aplica la nueva regla.
3. **Given** un canal que declara un tamaño máximo por encima del tope global del servicio, **When** un
   cliente envía un adjunto entre ambos, **Then** se rechaza: el tope global siempre manda.
4. **Given** la configuración por defecto, **When** un cliente envía un adjunto por EMAIL, SMS o PUSH,
   **Then** se rechaza porque ningún canal declara adjuntos todavía.

---

### User Story 4 - El archivo adjunto nunca queda expuesto (Priority: P1)

Como responsable de la operación, quiero que la referencia al archivo adjunto nunca aparezca en los
registros del servicio, en los mensajes de error, en los eventos que el servicio publica ni en las
consultas, y que solo se registren su nombre, tipo y tamaño, para no filtrar documentos de los
destinatarios (comprobantes, identificaciones, facturas).

**Why this priority**: "De una forma segura" es parte de la historia. Una dirección con un permiso de
acceso temporal en un registro permite descargar el documento a cualquiera que lea ese registro mientras
el permiso esté vigente.

**Independent Test**: Enviar un adjunto con una referencia reconocible, una vez válido y otra vez inválido;
capturar los registros, la respuesta de error, los eventos publicados y el mensaje de despacho; confirmar
que la referencia no aparece en ninguno, mientras que el nombre, el tipo y el tamaño sí aparecen en el
registro (control positivo).

**Acceptance Scenarios**:

1. **Given** una notificación aceptada con adjunto, **When** se revisan los registros del servicio,
   **Then** aparecen el nombre, el tipo y el tamaño del adjunto y no su referencia.
2. **Given** una notificación rechazada por su adjunto, **When** se revisan la respuesta y los registros,
   **Then** ninguno contiene la referencia al archivo.
3. **Given** cualquier notificación con adjunto, **When** se revisan los eventos publicados, el mensaje de
   despacho y las consultas de estado, histórico y actualizaciones en vivo, **Then** ninguno contiene la
   referencia al archivo.

---

### User Story 5 - Nunca entregar una notificación sin su adjunto (Priority: P2)

Como sistema cliente, quiero que una notificación con adjunto nunca se marque como entregada si el
proveedor que la despacha no sabe enviar adjuntos, para que un estado "entregada" signifique siempre
"entregada con sus archivos" (Q4).

**Why this priority**: Hoy ningún proveedor real sabe enviar adjuntos (eso llega con las historias por
canal). Si un operador declara adjuntos en un canal cuyo proveedor aún no los soporta, la notificación no
debe salir sin el archivo.

**Independent Test**: Declarar adjuntos en un canal cuyo proveedor preferente no sabe enviarlos, pedir una
notificación con adjunto y confirmar que termina fallida sin haberse enviado y sin reintentos, con el
proveedor en su historial; y que una notificación sin adjunto por el mismo canal sí se envía (control positivo).

**Acceptance Scenarios**:

1. **Given** una notificación aceptada con adjunto y un proveedor preferente que no sabe enviar adjuntos,
   **When** se despacha, **Then** el proveedor no la recibe y la notificación termina fallida, sin
   reintentos, con un intento de fallo definitivo a nombre de ese proveedor en su historial.
2. **Given** el mismo canal y proveedor, **When** se despacha una notificación sin adjunto, **Then** se
   envía normalmente.

---

### Edge Cases

- **Tamaño exactamente igual al límite del canal**: se acepta; el límite es inclusivo, igual que el largo
  máximo del mensaje.
- **Tipo declarado con mayúsculas o parámetros** (por ejemplo `application/PDF` o
  `application/pdf; charset=binary`): se compara el tipo sin distinguir mayúsculas y sin parámetros.
- **Nombre de archivo con separadores de ruta, caracteres de control, `.` o `..`, o más de 255
  caracteres**: se rechaza; el nombre es solo un nombre, nunca una ruta.
- **Referencia que no es https, que no tiene servidor, que incluye usuario o contraseña, o que supera 2048
  caracteres**: se rechaza.
- **Lista de adjuntos vacía**: equivale a no enviar adjuntos.
- **Más adjuntos de los que admite el canal o el tope global**: se rechaza completa (Q2).
- **Lote con un elemento con adjunto inválido**: se rechaza solo ese elemento, con su motivo; los demás
  siguen (Q5).
- **Catálogo cambiado entre la aceptación y el despacho** (el canal deja de aceptar adjuntos): la
  notificación ya aceptada conserva sus adjuntos; el despacho solo exige que el proveedor sepa enviarlos
  (User Story 5).
- **Canal que declara adjuntos sin restringir tipos**: acepta los tipos de la lista global de tipos
  permitidos; nunca un tipo fuera de ella.
- **Notificación duplicada (mismo identificador externo) con un adjunto distinto**: si el nuevo adjunto es
  válido, se devuelve la original como duplicada y el nuevo adjunto no la reemplaza; si es inválido, se
  rechaza como cualquier solicitud inválida (FR-012).
- **Referencia vencida o archivo borrado por el cliente antes del despacho**: el proveedor no podrá
  obtener el archivo; el resultado es el que el proveedor reporte (fuera del control de esta historia).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El componente MUST permitir que una solicitud de notificación incluya una lista de adjuntos.
  Cada adjunto se describe por su nombre de archivo, su tipo de contenido, su tamaño en bytes y una
  referencia https al archivo, que aloja el cliente (Q1, recomendada B).
- **FR-002**: Los adjuntos MUST ser opcionales: una notificación sin adjuntos se acepta, valida, guarda y
  despacha exactamente igual que antes de esta historia.
- **FR-003**: Cada canal del catálogo MUST poder declarar, dentro de su forma de contenido, si acepta
  adjuntos y, si los acepta, los tipos permitidos, el tamaño máximo por adjunto y la cantidad máxima (Q3).
- **FR-004**: Un canal cuya forma de contenido no declara adjuntos MUST rechazar toda notificación con
  adjuntos. La configuración por defecto no declara adjuntos en ningún canal.
- **FR-005**: El componente MUST imponer topes globales que ninguna declaración de canal puede superar: 10
  MB por adjunto, 5 adjuntos por notificación y una lista cerrada de tipos de documento e imagen
  permitidos.
- **FR-006**: Una notificación con un adjunto que incumple cualquier regla MUST rechazarse completa en la
  aceptación, como solicitud inválida. Las reglas son: canal que no acepta adjuntos; tipo fuera de lo
  permitido por el canal o por la lista global; tamaño mayor al permitido por el canal o por el tope
  global; cantidad mayor a la permitida por el canal o por el tope global; nombre ausente o inválido; tipo
  ausente o mal formado; tamaño ausente, cero o negativo; referencia ausente o que no es una dirección
  https válida. Nunca se acepta la notificación sin el adjunto ni con parte de sus adjuntos, y nada queda
  registrado ni encolado.
- **FR-007**: El motivo del rechazo MUST identificar el adjunto por su posición en la lista (y por su
  nombre cuando la regla incumplida no es el propio nombre) y la regla incumplida, y MUST NOT contener la
  referencia al archivo.
- **FR-008**: Una notificación aceptada con adjuntos MUST conservarlos de forma duradera, en el orden
  enviado, de modo que el despacho, incluidos los reintentos y el reencolado de pendientes, disponga de
  ellos completos.
- **FR-009**: Los registros del servicio, los mensajes de error, los eventos publicados, los mensajes de
  despacho y las respuestas de consulta MUST NOT contener la referencia al archivo. Cuando un registro
  mencione un adjunto, MUST limitarse a su nombre, su tipo y su tamaño.
- **FR-010**: La aceptación de una notificación con adjuntos y el rechazo por adjunto inválido MUST quedar
  registrados con el tenant, el identificador externo y el nombre, tipo y tamaño de cada adjunto.
- **FR-011**: La consulta del catálogo MUST mostrar, por canal, la declaración de adjuntos vigente como
  parte de su forma de contenido.
- **FR-012**: La idempotencia por identificador externo MUST mantenerse con el mismo orden que hoy: primero
  se valida la solicitud (contenido y adjuntos) y luego se busca la duplicada. Una solicitud duplicada
  válida devuelve la notificación original sin guardar ni reemplazar sus adjuntos; una duplicada inválida
  se rechaza, igual que hoy una duplicada con contenido inválido.
- **FR-013**: Las operaciones de envío individual y en lote MUST quedar descritas con los adjuntos en el
  contrato público antes de implementarse, incluidos los motivos de rechazo.
- **FR-014**: Cada proveedor MUST declarar si sabe enviar adjuntos. Una notificación con adjuntos cuyo
  proveedor elegido no sabe enviarlos MUST terminar fallida sin enviarse y sin reintentos, con un intento
  de fallo definitivo a nombre de ese proveedor en su historial (Q4). Hoy solo el proveedor simulado
  declara saber enviarlos.
- **FR-015**: En un envío en lote, un elemento con un adjunto inválido MUST rechazarse solo, con su motivo,
  sin afectar a los demás elementos (Q5).
- **FR-016**: El servicio MUST NOT descargar ni abrir el archivo referenciado durante la aceptación.

### Key Entities *(include if feature involves data)*

- **Adjunto**: archivo asociado a una notificación. Atributos: nombre de archivo, tipo de contenido
  normalizado, tamaño en bytes declarado y referencia https al archivo. Pertenece a una sola notificación,
  se guarda con ella y no se comparte. La referencia es un dato sensible.
- **Declaración de adjuntos del canal**: parte de la forma de contenido del canal en el catálogo. Indica
  los tipos permitidos, el tamaño máximo por adjunto y la cantidad máxima. Ausente = el canal no acepta
  adjuntos.
- **Topes globales de adjuntos**: límites del servicio (tamaño por adjunto, cantidad por notificación y
  lista de tipos permitidos) que acotan cualquier declaración de canal.
- **Capacidad de adjuntos del proveedor**: declaración de cada proveedor sobre si sabe enviar adjuntos.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de las notificaciones con adjuntos válidos para su canal se acepta y llega a
  entregada por el proveedor simulado con los mismos nombres, tipos, tamaños y referencias que envió el
  cliente, en el mismo orden (verificado de punta a punta).
- **SC-002**: El 100 % de las notificaciones con un adjunto inválido (una por cada regla de FR-006) se
  rechaza en la aceptación, y en cero casos queda una notificación registrada o encolada por ellas.
- **SC-003**: Cero apariciones de una referencia reconocible en los registros, las respuestas de error,
  los eventos publicados, los mensajes de despacho y las respuestas de consulta capturados durante la
  prueba de punta a punta, mientras el nombre, el tipo y el tamaño sí aparecen en los registros (control
  positivo).
- **SC-004**: Las notificaciones sin adjunto no cambian de comportamiento: todas las pruebas existentes de
  aceptación, lote, despacho y consulta siguen pasando sin modificar sus expectativas.
- **SC-005**: Un cambio en la declaración de adjuntos de un canal se aplica a la aceptación en menos de 5
  segundos cuando el refresco del catálogo está configurado cada segundo.
- **SC-006**: En cero casos una notificación con adjuntos queda entregada por un proveedor que no declara
  saber enviarlos; con ese proveedor, una notificación sin adjuntos sí queda entregada (control positivo).
- **SC-007**: En un lote con un elemento con adjunto inválido y otro válido, el inválido se rechaza con su
  motivo y el válido se acepta.

## Out of Scope

- Enviar el adjunto por un proveedor real: correo, SMS y push tienen historias propias, que también
  habilitarán la declaración de adjuntos de su canal.
- Alojar archivos en el servicio, descargarlos o verificar su contenido real (Q1).
- Transformar el archivo (redimensionar imágenes, comprimir, convertir formato).
- Analizar el archivo en busca de software malicioso.
- Mostrar los adjuntos en las consultas de estado, de histórico o de actualizaciones en vivo.
- Adjuntos distintos por destinatario dentro de un lote.
- Registro de canales por API: la declaración de adjuntos se configura igual que hoy la forma de contenido.

## Assumptions

- Los tipos de contenido se expresan como tipos de medio estándar (por ejemplo `application/pdf`,
  `image/png`) y se comparan exactos tras normalizarlos, sin comodines.
- La lista global de tipos permitidos cubre documentos e imágenes comunes (PDF, PNG, JPEG, GIF, WEBP,
  texto plano, CSV y documentos y hojas de cálculo de ofimática) y excluye ejecutables y guiones.
- La referencia puede incluir un permiso de acceso temporal emitido por el alojamiento del cliente; es
  responsabilidad del cliente que siga vigente durante los reintentos.
- El identificador de tenant sigue siendo el sustituto provisional de la identidad del cliente.
- El envío en lote está descrito en el contrato público y existe como caso de uso, pero hoy no tiene una
  operación HTTP que lo atienda. La regla de Q5 se aplica y se prueba en el caso de uso de lote; exponerlo
  por HTTP no es parte de esta historia.

## Risks

- **Tamaño y tipo declarados, no verificados** (consecuencia de Q1-B): un cliente puede declarar un
  tamaño o un tipo que no coincide con el archivo real; el servicio lo acepta y el proveedor lo rechazará
  al despachar. Se acota con los topes globales y la regla de Q4. Dueño: andrualv. Revisión: con la
  primera historia de canal que envíe adjuntos reales, a más tardar 2026-12-31.
- **Archivos de destinatarios alojados por el cliente**: la seguridad del documento depende de cómo lo
  aloje el cliente (dirección pública o con permiso temporal). El servicio no lo publica ni lo copia.
  Dueño: andrualv. Revisión: 2026-12-31.
- **Motivo del fallo por proveedor sin adjuntos no textual**: el historial de intentos registra el
  proveedor y el fallo definitivo, pero no guarda un motivo textual, porque los intentos no tienen hoy un
  campo de causa. El motivo se deduce de dos datos visibles (la notificación tiene adjuntos y el proveedor
  no declara saber enviarlos). Dueño: andrualv. Revisión: con la primera historia que agregue causa a los
  intentos, a más tardar 2026-12-31.
- **Registros estructurados todavía incompletos**: el servicio aún no emite registros estructurados en
  todo el ciclo de vida; esta historia agrega solo los registros de aceptación y rechazo de adjuntos
  (FR-010) sin cerrar esa brecha general. Dueño: andrualv. Revisión: 2026-12-31.
