# Feature Specification: Adjuntar un archivo a una notificación

**Feature Branch**: `feature/HU2-092-adjuntar-archivo-notificacion`

**Created**: 2026-09-28

**Updated**: 2026-10-03

**Status**: Plan v3 aceptado. Q1 (cómo viaja el archivo), la decisión reservada por el usuario, se
reabrió el 2026-09-29: la referencia https confirmada el 2026-09-28 queda **superada** por un modelo
híbrido por tamaño (embebido hasta 1 MB; por encima, subida a un almacén propio del servicio). Q2–Q5 siguen
confirmadas tal como se aceptaron el 2026-09-28.

**Enmienda 2026-10-03 (fix/HU2-092-hallazgos-revision)**: la historia ya está en `develop`. Una revisión
independiente encontró defectos de robustez y seguridad en lo implementado; se corrigen con FR-027 a
FR-040, SC-015 a SC-022 y la User Story 8, sin renumerar nada de lo existente. Q6 a Q9 de la sesión
2026-10-03 fueron resueltas por el usuario el 2026-10-03; la enmienda espera su aprobación explícita.

**Input**: User description: "Como sistema cliente, quiero poder adjuntar un archivo a una notificación,
para enviar comprobantes, documentos o imágenes junto con el mensaje de una forma segura."

**Trazabilidad**: CU-01 (aceptar una notificación), épica A — Ingesta y ciclo de vida, Fase 3. Requisito
nuevo: no forma parte de la línea base de requisitos funcionales y no funcionales del proyecto; se agrega
por decisión del equipo. Cubre el modelo del adjunto, su recepción (embebido o por subida al almacén del
servicio), su verificación (tipo real, análisis antivirus, huella) en la aceptación y el contrato genérico.
La entrega real del adjunto al destinatario por cada proveedor (correo, SMS y push) queda para HU2-093,
HU2-094 y HU2-095, que esta historia no bloquea ni implementa.

## Clarifications

### Session 2026-09-28 — confirmadas (2026-09-28)

No fue posible consultar al usuario durante la redacción, así que cada pregunta se registró con una
respuesta recomendada. El usuario confirmó las cinco al aprobar el plan v1. Q1 se reabrió el 2026-09-29
(sesión siguiente); Q2–Q5 siguen vigentes.

- **Q1 — ¿El archivo viaja embebido en la propia solicitud de notificación (su contenido codificado en
  texto) o como una referencia (una dirección web) a un archivo que el cliente ya aloja en otro lugar?**
  - Opciones planteadas: (A) embebido; (B) referencia https alojada por el cliente; (C) ambas, a elección
    del cliente por adjunto.
  - Respuesta confirmada el 2026-09-28: **B — referencia**. **Superada** el 2026-09-29; ver la sesión
    siguiente.
- **Q2 — ¿Una notificación lleva a lo sumo un adjunto, o puede llevar varios hasta un máximo por canal?**
  - Opciones: (A) exactamente cero o uno; (B) una lista, con un máximo por canal y un tope global de 5;
    (C) una lista sin máximo por canal, solo el tope global.
  - **Respuesta confirmada por el usuario**: **B**. El contrato nace como lista y no hay que
    romperlo cuando el correo necesite factura y comprobante juntos; cada canal fija su máximo (el push, por
    ejemplo, solo admitirá una imagen). La historia sigue cubriendo el caso "un archivo" como el más común.
  - Afecta a: FR-001, FR-003, FR-005, FR-006, Edge Cases.
- **Q3 — ¿La declaración de adjuntos de cada canal se escribe dentro de la forma de contenido del canal
  (el mismo esquema donde hoy se fija el largo máximo del mensaje) o como una sección propia del canal en
  el catálogo, y qué canales aceptan adjuntos con la configuración por defecto?**
  - Opciones: (A) dentro de la forma de contenido; un canal acepta adjuntos solo si su forma de contenido
    los declara; la configuración por defecto no los declara en ningún canal; (B) una sección propia del
    canal (tipos, tamaño, cantidad), con la misma regla por defecto; (C) como A, pero la configuración por
    defecto los habilita en EMAIL.
  - **Respuesta confirmada por el usuario**: **A**. Es el mismo mecanismo que ya fija el largo
    máximo del mensaje, la consulta del catálogo ya lo muestra sin cambiar su contrato y extender la
    validación de la forma de contenido es exactamente el área que esta historia toca. Por defecto ningún
    canal acepta adjuntos porque ningún proveedor real sabe enviarlos todavía; cada historia de canal
    habilitará el suyo junto con su proveedor.
  - Afecta a: FR-003, FR-004, FR-011, User Story 3, Assumptions.
- **Q4 — ¿Qué pasa en el despacho con una notificación con adjunto cuando el proveedor elegido no sabe
  enviar adjuntos?**
  - Opciones: (A) falla definitivamente sin enviarse, sin reintentos, con el proveedor en su historial; (B) se envía
    sin el adjunto; (C) no es parte de esta historia.
  - **Respuesta confirmada por el usuario**: **A**. B es una entrega a medias silenciosa,
    contraria a la historia; C deja abierta esa misma entrega a medias en cuanto un operador declare
    adjuntos en un canal cuyo proveedor aún no los soporta. Cada proveedor declara si sabe enviar adjuntos;
    hoy solo el simulado.
  - Afecta a: User Story 5, FR-014, SC-006.
- **Q5 — En un envío en lote, ¿un elemento con un adjunto inválido se rechaza solo, o rechaza el lote
  completo?**
  - Opciones: (A) se rechaza solo ese elemento, con su motivo, y los demás siguen; (B) se rechaza el lote.
  - **Respuesta confirmada por el usuario**: **A**. Es la regla que el lote ya aplica a un
    contenido inválido: cada elemento se valida por separado y un elemento inválido no bloquea a los demás.
    "Rechazar completa" se refiere a la notificación del elemento, que nunca se acepta sin su adjunto.
  - Afecta a: FR-015, Edge Cases.

### Session 2026-09-29 — Q1 reabierta por el usuario

- **Q1 (reabierta) — ¿Cómo viaja el archivo?**
  - **Respuesta del usuario**: **híbrido por tamaño**, con umbral de 1 MB.
    - **Archivo chico (hasta 1 MB)**: viaja embebido, codificado en texto (Base64), dentro de la propia
      solicitud. El servicio lo decodifica, verifica su tipo real, lo analiza con el antivirus en la misma
      llamada y, si todo pasa, lo guarda con la notificación. Se acepta o se rechaza en la misma llamada.
    - **Archivo grande (más de 1 MB)**: el cliente pide al servicio una dirección de subida, sube el
      archivo al almacén de archivos del servicio y, al pedir la notificación, referencia esa dirección.
      El servicio solo acepta direcciones que él mismo emitió y solo si el archivo subido ya pasó el
      análisis. El archivo subido pasa por los estados "pendiente de análisis", "limpio" o "infectado".
    - **Seguridad común**: antivirus autoalojado (ClamAV); verificación del tipo real contra el contenido
      (Apache Tika), que reemplaza cualquier comprobación separada de la firma de los primeros bytes;
      huella SHA-256 de cada archivo, para integridad, auditoría, trazabilidad y para no volver a analizar
      un archivo cuya huella ya se conoce como limpia; y tenant propio en cada adjunto y cada subida,
      validado contra el de la notificación, por el antecedente de fuga entre tenants de HU2-072.
    - **Tipos permitidos**: PDF, PNG, JPG/JPEG, DOCX, XLSX, CSV y TXT. Salen GIF, WEBP y PPTX respecto de
      la lista anterior.
    - **Extensiones prohibidas** (capa adicional al tipo): `.exe` `.msi` `.bat` `.cmd` `.com` `.scr`
      `.pif` `.vbs` `.vbe` `.js` `.jse` `.wsf` `.wsh` `.ps1` `.hta` `.cpl` `.msc` `.reg` `.lnk` `.jar`
      `.dll` `.sh` `.apk` `.app` `.gadget` `.com.pif` `.msix` `.war`.
    - **Límites**: 10 MB por archivo (sin cambio), 25 MB agregados por notificación (nuevo) y 5 adjuntos
      (sin cambio).
  - **Por qué reemplaza a B**: con la referencia, el tamaño y el tipo eran los declarados por el cliente y
    el servicio no podía analizar el archivo; el modelo híbrido verifica el archivo real en los dos
    caminos sin hacer que las solicitudes de archivos grandes crezcan ni retengan decenas de MB en memoria.
  - **Detalle confirmado con el plan v3** (plan.md § Diseño): el umbral separa los caminos en los dos
    sentidos (un archivo de hasta 1 MB no se acepta por dirección y uno mayor no se acepta embebido); 1 MB
    = 1 048 576 bytes y 25 MB = 26 214 400 bytes; la dirección de subida la emite el servicio para un
    tenant y solo vale para ese tenant; una subida limpia puede usarse en varias notificaciones del mismo
    tenant.
  - Afecta a: FR-001, FR-005, FR-006, FR-007, FR-008, FR-009, FR-010, FR-013, FR-015, FR-016 y
    FR-017 a FR-026, User Stories 1, 2, 4, 6 y 7, Key Entities, Edge Cases, Out of Scope, Assumptions,
    Risks y SC-001, SC-002, SC-003, SC-007 a SC-014.

### Session 2026-10-03 — hallazgos de la revisión independiente (resueltas por el usuario el 2026-10-03)

Las opciones se plantearon con una recomendación y el usuario resolvió las cuatro el 2026-10-03.

- **Q6 — ¿Cómo se limita el tamaño y el tipo de lo que se sube con la dirección prefirmada?**
  - Opciones: (A) cambiar de PUT prefirmado a una política de subida POST de MinIO con condiciones de
    tamaño exacto (`content-length-range` = `sizeBytes`) y tipo igual al declarado; (B) mantener el PUT y
    firmar `Content-Type` y `Content-Length` como encabezados obligatorios; (C) mantener el PUT sin límite
    en la subida y reforzar solo el lado del servicio (comprobar el tamaño antes de leer y expirar el
    prefijo `uploads/` por ciclo de vida del bucket).
  - **Respuesta del usuario (2026-10-03)**: **A**. El contrato de subida pasa de PUT a un formulario
    multipart con la política firmada (`content-length-range` exacto y tipo igual al declarado); se
    actualiza primero el contrato OpenAPI (Principio II). La comprobación con MinIO real de que el
    almacén rechaza un tamaño o tipo distinto es el primer paso del plan. El servicio no está desplegado
    fuera de desarrollo, así que el cambio de contrato no rompe clientes.
  - Afecta a: FR-035, SC-020.
- **Q7 — ¿Qué se hace con las subidas abandonadas (nunca completadas) y con las que agotan sus
  reintentos?**
  - Opciones: (A) un proceso periódico del propio servicio las pasa a un estado terminal `FAILED` (motivo
    `EXPIRED` o `SCAN_EXHAUSTED`) y borra su objeto, con transición condicionada para que varias réplicas
    no se pisen; (B) solo reglas de ciclo de vida del bucket (borran el objeto) y un índice TTL de MongoDB
    (borra el documento), sin estado terminal; (C) dejarlas como riesgo declarado.
  - **Respuesta del usuario (2026-10-03)**: **A más ciclo de vida**. Un proceso periódico identifica las
    subidas que permanecen sin resolverse más allá del tiempo permitido, las marca como `FAILED` y elimina
    sus objetos asociados; además MinIO aplica una regla de ciclo de vida sobre `uploads/` que elimina los
    objetos temporales huérfanos. El proceso es seguro con varias réplicas (actualización condicionada
    atómica). Nombre real del estado inicial en el código: `PENDING_SCAN` (el usuario lo llamó
    "UPLOADING"); el spec usa `PENDING_SCAN`, y no se crea un estado nuevo.
  - Afecta a: FR-027, FR-034, SC-015, SC-021.
- **Q8 — ¿Qué responde `:complete` cuando la subida ya venció y qué ve el cliente de una subida fallida?**
  - Opciones: (A) `:complete` de una subida vencida → `409` y la subida pasa a `FAILED` (`EXPIRED`); una
    notificación que referencia una subida `FAILED` → `400`; (B) `410 Gone` en ambos casos.
  - **Respuesta del usuario (2026-10-03)**: **A**. Reutiliza los códigos que el contrato ya tiene; el
    cliente distingue el motivo por el cuerpo.
  - Afecta a: FR-027, FR-033.
- **Q9 — ¿Cómo se evita que un mensaje de escaneo se pierda o gire sin tope cuando falla el reenvío o el
  envío a la DLQ?**
  - Opciones: (A) declarar la cola de escaneo con `x-dead-letter-exchange` hacia su DLQ y, si el reenvío o
    la DLQ fallan, `basicNack(requeue=false)` para que el broker lo dirija a la DLQ; (B)
    `basicNack(requeue=true)` con pausa fija; (C) confirmaciones del publicador (publisher confirms) y ack
    solo tras la confirmación, sin cambiar la cola.
  - **Respuesta del usuario (2026-10-03)**: **A + C**, interpretación del coordinador de la
    recomendación; el usuario puede corregirla luego. La cola de escaneo hoy se declara sin DLX, así que
    un `nack` sin reencolar descartaría el mensaje: se añade el DLX. Declarar un argumento nuevo en una
    cola existente falla con `PRECONDITION_FAILED`: en desarrollo hay que eliminar y recrear la cola
    (se anota en `quickstart.md`).
  - Afecta a: FR-030, FR-031, SC-017.

Los hallazgos de la revisión que se descartaron o se redujeron al verificarlos contra el código están en
`plan.md § Hallazgos de la revisión del 2026-10-03`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enviar una notificación con un archivo chico embebido (Priority: P1)

Como sistema cliente, quiero incluir uno o varios archivos de hasta 1 MB (un comprobante, un documento o
una imagen) dentro de la propia solicitud de notificación, por un canal que acepta adjuntos, para que el
destinatario los reciba junto con el mensaje sin que yo tenga que alojarlos en otro lugar.

**Why this priority**: Es la razón de ser de la historia y el camino más común (comprobantes y facturas
suelen pesar menos de 1 MB). Sin ella no hay forma de asociar un archivo a una notificación.

**Independent Test**: Con un canal que declara aceptar adjuntos PDF y el proveedor simulado, pedir una
notificación con un PDF limpio de 500 KB embebido y confirmar que se acepta, que la notificación guardada
conserva el adjunto (nombre, tipo, tamaño, huella y contenido) y que llega a entregada con el mismo
contenido.

**Acceptance Scenarios**:

1. **Given** un canal que acepta adjuntos PDF de hasta 5 MB, **When** el cliente pide una notificación con
   un PDF limpio de 500 KB embebido, **Then** la notificación se acepta y queda registrada con su adjunto
   y su huella.
2. **Given** una notificación aceptada con adjunto, **When** se despacha por un proveedor que sabe enviar
   adjuntos, **Then** el proveedor recibe la notificación con sus adjuntos completos, en el orden enviado
   y con el mismo contenido.
3. **Given** una notificación sin adjunto, **When** el cliente la pide por cualquier canal, **Then** se
   comporta exactamente igual que antes de esta historia.
4. **Given** la misma notificación (mismo identificador externo del cliente) pedida dos veces con adjunto,
   **When** llega la segunda con adjuntos válidos, **Then** se devuelve la primera como duplicada, igual
   que sin adjunto, y sus adjuntos no cambian.
5. **Given** una notificación con adjunto que queda pendiente de reintento, **When** se reintenta,
   **Then** el proveedor vuelve a recibir los mismos adjuntos.

---

### User Story 2 - Rechazar completo lo que no se admite (Priority: P1)

Como sistema cliente, quiero que una notificación con un adjunto inválido (demasiado grande, de un tipo no
admitido, con un contenido que no coincide con el tipo declarado, con una extensión prohibida, con
software malicioso, en mayor cantidad o volumen del permitido, o para un canal que no acepta adjuntos) se
rechace completa en el momento de pedirla, con un motivo claro, para no creer que se envió algo que el
destinatario nunca recibirá completo o que no debería recibir.

**Why this priority**: Aceptar la notificación sin el adjunto, o con un adjunto que el canal no puede
entregar o que es peligroso, es una entrega a medias silenciosa o un riesgo para el destinatario.

**Independent Test**: Pedir, por separado, una notificación por cada regla de FR-006 y confirmar que todas
se rechazan con un motivo que identifica el adjunto y la regla incumplida, y que ninguna queda registrada
ni encolada.

**Acceptance Scenarios**:

1. **Given** un canal que acepta adjuntos de hasta 5 MB, **When** el cliente pide una notificación con un
   adjunto de 6 MB, **Then** la notificación completa se rechaza como solicitud inválida y no queda
   registrada ni encolada.
2. **Given** un canal que acepta solo PDF, **When** el cliente pide una notificación con una imagen,
   **Then** se rechaza completa con un motivo que nombra el tipo no admitido.
3. **Given** un canal que no declara aceptar adjuntos, **When** el cliente pide una notificación con un
   adjunto, **Then** se rechaza completa con un motivo que dice que el canal no acepta adjuntos.
4. **Given** un adjunto sin nombre, sin tipo, con tamaño cero o negativo, con contenido y dirección a la
   vez o sin ninguno de los dos, o con un contenido que no es texto codificado válido, **When** el cliente
   lo envía, **Then** se rechaza completa como solicitud inválida.
5. **Given** un canal que admite hasta 2 adjuntos, **When** el cliente envía 3, **Then** se rechaza
   completa.
6. **Given** un rechazo por adjunto inválido, **When** el cliente lee el motivo, **Then** el motivo
   identifica qué adjunto (por su posición) y qué regla falló, y no contiene el contenido del archivo ni
   la dirección de subida.
7. **Given** un archivo embebido que contiene el archivo de prueba estándar de antivirus (EICAR), **When**
   el cliente lo envía, **Then** se rechaza completa con un motivo de software malicioso.
8. **Given** un archivo embebido declarado como PDF cuyo contenido real es otro tipo, **When** el cliente
   lo envía, **Then** se rechaza completa con un motivo de tipo que no coincide.
9. **Given** un archivo con una extensión prohibida (por ejemplo `factura.pdf.exe`), **When** el cliente
   lo envía, **Then** se rechaza completa aunque el tipo declarado esté permitido.
10. **Given** adjuntos que en total superan 25 MB, **When** el cliente los envía, **Then** se rechaza
    completa aunque cada uno cumpla su límite individual.

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

Como responsable de la operación, quiero que el contenido de un archivo adjunto y la dirección de subida
que el servicio emite nunca aparezcan en los registros del servicio, en los mensajes de error, en los
eventos que el servicio publica ni en las consultas, y que solo se registren su nombre, tipo, tamaño y
huella, para no filtrar documentos de los destinatarios (comprobantes, identificaciones, facturas).

**Why this priority**: "De una forma segura" es parte de la historia. El contenido de un comprobante en un
registro lo expone a cualquiera que lea ese registro; la dirección de subida es una credencial temporal
que permite escribir en el almacén mientras está vigente.

**Independent Test**: Enviar un adjunto embebido con un contenido reconocible, una vez válido y otra vez
inválido, y emitir una dirección de subida; capturar los registros, las respuestas de error, los eventos
publicados, el mensaje de despacho y las consultas; confirmar que ni el contenido ni la dirección aparecen
en ninguno, mientras que el nombre, el tipo, el tamaño y la huella sí aparecen en el registro (control
positivo).

**Acceptance Scenarios**:

1. **Given** una notificación aceptada con adjunto, **When** se revisan los registros del servicio,
   **Then** aparecen el nombre, el tipo, el tamaño y la huella del adjunto, y no su contenido.
2. **Given** una notificación rechazada por su adjunto, **When** se revisan la respuesta y los registros,
   **Then** ninguno contiene el contenido del archivo ni la dirección de subida.
3. **Given** cualquier notificación con adjunto, **When** se revisan los eventos publicados, el mensaje de
   despacho y las consultas de estado, histórico y actualizaciones en vivo, **Then** ninguno contiene el
   contenido del archivo ni la dirección de subida.
4. **Given** una dirección de subida emitida, **When** se revisan los registros y la consulta de estado de
   la subida, **Then** ninguno contiene la dirección.

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

### User Story 6 - Adjuntar un archivo grande mediante una subida al servicio (Priority: P1)

Como sistema cliente, quiero pedir al servicio una dirección de subida para un archivo de más de 1 MB,
subirlo ahí, saber cuándo terminó su análisis y referenciarlo después en una o varias notificaciones, para
adjuntar documentos grandes sin inflar la solicitud de notificación y con la misma garantía de análisis
que los archivos chicos.

**Why this priority**: Sin este camino, los archivos de más de 1 MB (hasta el tope de 10 MB) no pueden
adjuntarse en absoluto.

**Independent Test**: Pedir una dirección de subida para un PDF limpio de 5 MB, subirlo, avisar que la
subida terminó, esperar a que la subida quede "limpia", pedir una notificación que la referencie y
confirmar que se acepta y llega a entregada con el mismo contenido (misma huella).

**Acceptance Scenarios**:

1. **Given** un archivo permitido de 5 MB, **When** el cliente pide una dirección de subida con su nombre,
   tipo y tamaño, **Then** recibe un identificador de subida, una dirección de subida y su vencimiento, y
   la subida queda "pendiente de análisis".
2. **Given** una dirección emitida, **When** el cliente sube el archivo y avisa que terminó, **Then** el
   servicio lo analiza y la subida pasa a "limpia" o a "infectada".
3. **Given** una subida "limpia", **When** el cliente pide una notificación que la referencia con el mismo
   nombre, tipo y tamaño, **Then** la notificación se acepta.
4. **Given** una subida todavía "pendiente de análisis", **When** el cliente pide una notificación que la
   referencia, **Then** se rechaza como un conflicto que puede reintentarse más tarde.
5. **Given** una subida "infectada" (por ejemplo, el archivo de prueba EICAR), **When** el cliente pide una
   notificación que la referencia, **Then** se rechaza como solicitud inválida.
6. **Given** una dirección https que el servicio no emitió, **When** el cliente la usa como referencia,
   **Then** se rechaza como solicitud inválida, sin que el servicio la descargue.
7. **Given** un aviso de subida terminada sin que el archivo esté en el almacén, o con un tamaño distinto
   del declarado, **When** el cliente lo envía, **Then** se rechaza y la subida sigue pendiente, para que
   el cliente pueda volver a subirlo mientras la dirección esté vigente.
8. **Given** una subida "limpia", **When** alguien intenta reemplazar el archivo con la dirección de subida
   aún vigente, **Then** la notificación nunca usa un contenido distinto del analizado.

---

### User Story 7 - Ningún tenant ve ni usa los archivos de otro (Priority: P1)

Como responsable de la operación, quiero que cada adjunto y cada subida pertenezcan a un solo tenant y que
un tenant no pueda consultar, completar, referenciar ni siquiera saber si existe una subida de otro, para
que la fuga entre tenants ocurrida en HU2-072 no se repita con documentos de destinatarios.

**Why this priority**: Un comprobante de un tenant entregado o visible para otro es un incidente de
seguridad, no un defecto menor.

**Independent Test**: Con dos tenants, A sube un archivo y lo deja "limpio"; B intenta consultarlo,
completarlo y referenciarlo en una notificación; confirmar que todas las respuestas de B son las mismas que
para una subida inexistente y que ninguna respuesta, registro ni documento de B contiene datos de A;
confirmar que A sí puede usarla (control positivo).

**Acceptance Scenarios**:

1. **Given** una subida de A, **When** B consulta su estado o avisa que terminó, **Then** recibe la misma
   respuesta que para una subida inexistente.
2. **Given** una subida "limpia" de A, **When** B pide una notificación que la referencia, **Then** se
   rechaza con el mismo motivo que una dirección que el servicio no emitió.
3. **Given** un archivo que A ya subió y que quedó analizado, **When** B envía el mismo archivo, **Then** el
   análisis de B no reutiliza el resultado de A ni revela que A lo subió.
4. **Given** una subida "limpia" de A, **When** A pide una notificación que la referencia, **Then** se
   acepta (control positivo).

---

### User Story 8 - Una subida nunca queda atascada ni se pierde su mensaje (Priority: P1)

Como responsable de la operación, quiero que toda subida llegue a un estado final con un motivo registrado
y que ningún mensaje de escaneo se pierda, gire sin tope o salte la cola de mensajes muertos, para no
tener subidas "pendientes de análisis" para siempre ni escaneos que nadie atiende.

**Why this priority**: Hoy una subida cuyo objeto desaparece, cambia de tamaño o pierde la transición queda
pendiente sin rastro, y un fallo del reenvío del escaneo puede reencolar el mensaje indefinidamente.

**Independent Test**: Provocar cada causa de fallo (objeto ausente, tamaño distinto, transición perdida,
subida vencida, mensaje ilegible, fallo del reenvío) y confirmar que la subida termina en `FAILED` con su
motivo, o que el mensaje llega a la DLQ con el número exacto de intentos, con un control positivo (una
subida sana sigue llegando a `CLEAN`).

**Acceptance Scenarios**:

1. **Given** una subida cuyo objeto ya no está al escanear, **When** corre el escaneo, **Then** la subida
   pasa a `FAILED` con motivo `OBJECT_MISSING` y queda registrado.
2. **Given** una subida cuyo objeto tiene un tamaño distinto del declarado, **When** corre el escaneo,
   **Then** no se lee el objeto, se descarta y la subida pasa a `FAILED` con motivo `SIZE_MISMATCH`.
3. **Given** un fallo al registrar la transición, **When** ocurre, **Then** el objeto no se borra antes de
   que la transición se haya persistido y la subida no queda pendiente sin remedio.
4. **Given** dos `:complete` simultáneos de la misma subida, **When** se procesan, **Then** se publica un
   solo escaneo y ambos responden el estado vigente de la subida.
5. **Given** una subida vencida, **When** se llama a `:complete`, **Then** se rechaza y la subida pasa a
   `FAILED` con motivo `EXPIRED`.
6. **Given** un mensaje de escaneo ilegible, **When** lo recibe el consumidor, **Then** va a la DLQ en el
   primer intento, sin reintentos.
7. **Given** que el reenvío del mensaje o el envío a la DLQ lanzan una excepción, **When** ocurre, **Then**
   el mensaje no se confirma en silencio ni se reencola sin tope: termina en la DLQ.

---

### Edge Cases

- **Tamaño exactamente igual al límite del canal**: se acepta; el límite es inclusivo, igual que el largo
  máximo del mensaje.
- **Archivo de exactamente 1 MB (1 048 576 bytes)**: va embebido; uno de 1 048 577 bytes va por subida.
  Un archivo chico enviado por dirección, o uno grande enviado embebido, se rechaza.
- **Suma exactamente igual a 25 MB (26 214 400 bytes)**: se acepta; un byte más se rechaza.
- **Tipo declarado con mayúsculas o parámetros** (por ejemplo `application/PDF` o
  `application/pdf; charset=binary`): se compara el tipo sin distinguir mayúsculas y sin parámetros.
- **CSV cuyo contenido se detecta como texto plano**: se acepta como CSV; los detectores de tipo no
  distinguen un CSV de un texto plano por el contenido.
- **Texto plano cuyo contenido real es HTML o un guion**: se rechaza por tipo que no coincide.
- **Doble extensión**: `factura.pdf.exe` se rechaza por extensión prohibida; `factura.exe.pdf` pasa la
  capa de extensión y lo detiene la verificación del tipo real si su contenido no es PDF. La comparación
  ignora mayúsculas y los puntos y espacios finales (`factura.EXE.`).
- **Nombre de archivo con separadores de ruta, caracteres de control, `.` o `..`, o más de 255
  caracteres**: se rechaza; el nombre es solo un nombre, nunca una ruta.
- **Contenido embebido mal codificado, con prefijo `data:` o con saltos de línea**: se rechaza.
- **Tamaño declarado distinto del tamaño real del contenido embebido**: se rechaza.
- **Referencia a una dirección que no emitió el servicio** (cualquier https externa): se rechaza sin
  descargarla.
- **Dirección de subida vencida antes de subir el archivo**: el almacén rechaza la subida; el cliente pide
  una dirección nueva.
- **Aviso de subida terminada dos veces**: el segundo devuelve el estado actual sin volver a analizar.
- **Mismo archivo (misma huella) enviado otra vez por el mismo tenant**: no se vuelve a analizar si el
  resultado anterior es reciente y de la misma versión de firmas; un resultado "infectado" se reutiliza
  siempre.
- **Antivirus no disponible o lento**: la notificación con adjuntos no se acepta (error temporal del
  servicio); las notificaciones sin adjuntos siguen funcionando.
- **Solicitud mayor al límite de tamaño del servicio (8 MB)**: se rechaza como demasiado grande.
- **Lista de adjuntos vacía**: equivale a no enviar adjuntos.
- **Más adjuntos de los que admite el canal o el tope global**: se rechaza completa (Q2).
- **Lote con un elemento con adjunto inválido o con una subida aún pendiente**: se rechaza solo ese
  elemento, con su motivo; los demás siguen (Q5).
- **Catálogo cambiado entre la aceptación y el despacho** (el canal deja de aceptar adjuntos): la
  notificación ya aceptada conserva sus adjuntos; el despacho solo exige que el proveedor sepa enviarlos
  (User Story 5).
- **Canal que declara adjuntos sin restringir tipos**: acepta los tipos de la lista global de tipos
  permitidos; nunca un tipo fuera de ella.
- **Notificación duplicada (mismo identificador externo) con un adjunto distinto**: si el nuevo adjunto es
  válido, se devuelve la original como duplicada y el nuevo adjunto no la reemplaza; si es inválido, se
  rechaza como cualquier solicitud inválida (FR-012).
- **Objeto re-subido tras `:complete`** (dirección aún vigente): el tamaño de `stat` debe coincidir con
  `sizeBytes` y no superar 10 MB antes de leer; si no, la subida pasa a `FAILED` (`SIZE_MISMATCH`).
- **Dos `:complete` concurrentes**: gana uno; el otro devuelve el estado vigente y no publica.
- **Perder la carrera al fijar la copia limpia** (otra réplica ya resolvió la subida): la copia en `clean/`
  se borra y queda registrado.
- **Segmento `.` o `..` como identificador de subida** en la dirección: se rechaza.
- **Extensiones `.docm` `.xlsm` `.html` `.svg` `.iso`**: se rechazan por extensión aunque el tipo declarado
  esté permitido.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El componente MUST permitir que una solicitud de notificación incluya una lista de adjuntos.
  Cada adjunto se describe por su nombre de archivo, su tipo de contenido y su tamaño en bytes, y lleva
  **exactamente una** de dos formas (Q1): su contenido embebido, codificado en Base64, si pesa hasta 1 MB
  (1 048 576 bytes); o la dirección de una subida emitida por el propio servicio (FR-017), si pesa más de
  1 MB.
- **FR-002**: Los adjuntos MUST ser opcionales: una notificación sin adjuntos se acepta, valida, guarda y
  despacha exactamente igual que antes de esta historia.
- **FR-003**: Cada canal del catálogo MUST poder declarar, dentro de su forma de contenido, si acepta
  adjuntos y, si los acepta, los tipos permitidos, el tamaño máximo por adjunto y la cantidad máxima (Q3).
- **FR-004**: Un canal cuya forma de contenido no declara adjuntos MUST rechazar toda notificación con
  adjuntos. La configuración por defecto no declara adjuntos en ningún canal.
- **FR-005**: El componente MUST imponer topes globales que ninguna declaración de canal puede superar: 10
  MB (10 485 760 bytes) por adjunto; 25 MB (26 214 400 bytes) sumando todos los adjuntos de una
  notificación; 5 adjuntos por notificación; una lista cerrada de tipos permitidos (PDF, PNG, JPEG, DOCX,
  XLSX, CSV y texto plano); y una lista de extensiones de archivo prohibidas (`.exe` `.msi` `.bat` `.cmd`
  `.com` `.scr` `.pif` `.vbs` `.vbe` `.js` `.jse` `.wsf` `.wsh` `.ps1` `.hta` `.cpl` `.msc` `.reg` `.lnk`
  `.jar` `.dll` `.sh` `.apk` `.app` `.gadget` `.com.pif` `.msix` `.war`), que se aplica además del tipo.
- **FR-006**: Una notificación con un adjunto que incumple cualquier regla MUST rechazarse completa en la
  aceptación. Las reglas son: canal que no acepta adjuntos; tipo fuera de lo permitido por el canal o por
  la lista global; extensión prohibida; tamaño mayor al permitido por el canal o por el tope global; suma
  de tamaños mayor al tope agregado; cantidad mayor a la permitida por el canal o por el tope global;
  nombre ausente o inválido; tipo ausente o mal formado; tamaño ausente, cero o negativo; contenido y
  dirección a la vez, o ninguno de los dos; contenido embebido mal codificado, mayor a 1 MB o de un tamaño
  distinto del declarado; dirección para un archivo de hasta 1 MB; dirección que el servicio no emitió
  para ese tenant; nombre, tipo o tamaño distintos de los de la subida referenciada; subida "infectada";
  tipo real del contenido distinto del declarado; y contenido con software malicioso. Nunca se acepta la
  notificación sin el adjunto ni con parte de sus adjuntos, y nada queda registrado ni encolado.
- **FR-007**: El motivo del rechazo MUST identificar el adjunto por su posición en la lista (y por su
  nombre cuando la regla incumplida no es el propio nombre) y la regla incumplida, y MUST NOT contener el
  contenido del archivo ni la dirección de subida.
- **FR-008**: Una notificación aceptada con adjuntos MUST conservarlos de forma duradera, en el orden
  enviado, de modo que el despacho, incluidos los reintentos y el reencolado de pendientes, disponga de
  ellos completos: el contenido embebido se guarda con la notificación en la misma escritura; el archivo
  grande queda como una copia inmutable del archivo analizado en el almacén del servicio.
- **FR-009**: Los registros del servicio, los mensajes de error, los eventos publicados, los mensajes de
  despacho y análisis, y las respuestas de consulta MUST NOT contener el contenido de un adjunto ni una
  dirección de subida. La dirección de subida solo aparece en la respuesta que la emite. Cuando un
  registro mencione un adjunto, MUST limitarse a su nombre, su tipo, su tamaño y su huella.
- **FR-010**: La aceptación de una notificación con adjuntos, el rechazo por adjunto inválido, la emisión
  de una subida, el aviso de subida terminada y el resultado de cada análisis MUST quedar registrados con
  el tenant, los identificadores disponibles (identificador externo, notificación, subida) y el nombre,
  tipo, tamaño y huella de cada archivo; un resultado "infectado" registra además la firma detectada.
- **FR-011**: La consulta del catálogo MUST mostrar, por canal, la declaración de adjuntos vigente como
  parte de su forma de contenido.
- **FR-012**: La idempotencia por identificador externo MUST mantenerse con el mismo orden que hoy: primero
  se valida la solicitud (contenido y adjuntos, incluida su verificación) y luego se busca la duplicada.
  Una solicitud duplicada válida devuelve la notificación original sin guardar ni reemplazar sus adjuntos;
  una duplicada inválida se rechaza, igual que hoy una duplicada con contenido inválido.
- **FR-013**: Las operaciones de envío individual y en lote, y las operaciones nuevas de subida (emitir,
  avisar que terminó y consultar), MUST quedar descritas en el contrato público antes de implementarse,
  incluidos los motivos de rechazo y sus códigos.
- **FR-014**: Cada proveedor MUST declarar si sabe enviar adjuntos. Una notificación con adjuntos cuyo
  proveedor elegido no sabe enviarlos MUST terminar fallida sin enviarse y sin reintentos, con un intento
  de fallo definitivo a nombre de ese proveedor en su historial (Q4). Hoy solo el proveedor simulado
  declara saber enviarlos.
- **FR-015**: En un envío en lote, un elemento con un adjunto inválido o que referencia una subida aún
  pendiente de análisis MUST rechazarse solo, con su motivo, sin afectar a los demás elementos (Q5).
- **FR-016**: El componente MUST verificar el archivo real de cada adjunto, en los dos caminos: calcular
  su huella SHA-256, comprobar que su tipo real coincide con el declarado y está en la lista global, y
  analizarlo con el antivirus. El archivo embebido se verifica durante la aceptación, en la misma llamada;
  el archivo grande, después del aviso de subida terminada. El componente MUST NOT descargar ni abrir una
  dirección enviada por el cliente: solo lee archivos de la propia solicitud o de su propio almacén.
- **FR-017**: El componente MUST emitir, a pedido de un tenant, una dirección de subida para un archivo
  permitido de más de 1 MB y hasta 10 MB, identificado por nombre, tipo y tamaño. La respuesta incluye un
  identificador de subida, la dirección y su vencimiento (15 minutos por defecto, configurable).
- **FR-018**: Al recibir el aviso de subida terminada, el componente MUST comprobar que el archivo está en
  el almacén y que su tamaño coincide con el declarado antes de encolar su análisis; si no, rechaza el
  aviso, descarta el archivo con tamaño incorrecto y la subida sigue pendiente.
- **FR-019**: Cada subida MUST recorrer los estados "pendiente de análisis" → "limpia" o "infectada",
  solo desde "pendiente de análisis", sin volver atrás. "Infectada" es el único estado final no apto y
  registra su motivo (software malicioso, con la firma detectada, o tipo real distinto del declarado). El
  archivo de una subida infectada se descarta del almacén.
- **FR-020**: El componente MUST permitir a un tenant consultar el estado de sus subidas (estado, nombre,
  tipo, tamaño y, si ya existe, huella), nunca la dirección de subida.
- **FR-021**: Una notificación que referencia una subida MUST aceptarse solo si la dirección fue emitida
  por el servicio para el mismo tenant, el nombre, el tipo y el tamaño coinciden con los de la subida y la
  subida está "limpia". Una subida "pendiente de análisis" se rechaza como conflicto reintentable; una
  "infectada", como solicitud inválida. Una subida limpia puede usarse en varias notificaciones del mismo
  tenant.
- **FR-022**: Cada adjunto y cada subida MUST llevar su propio tenant, validado contra el tenant de la
  notificación o de la solicitud. Un tenant MUST NOT poder consultar, completar, referenciar ni conocer la
  existencia de una subida de otro tenant: la respuesta es idéntica a la de una subida inexistente, y
  ninguna respuesta, registro ni documento de un tenant contiene datos de otro.
- **FR-023**: Si el antivirus no está disponible o no responde a tiempo (10 segundos por defecto,
  configurable), la notificación con adjuntos embebidos MUST rechazarse como error temporal del servicio,
  distinto de un rechazo por adjunto inválido; nunca se acepta un archivo sin analizar. Para una subida,
  el análisis se reintenta y, agotados los reintentos, queda trazable sin cambiar de estado.
- **FR-024**: El resultado de cada análisis MUST guardarse por tenant y huella. Un resultado "infectado"
  se reutiliza siempre; uno "limpio" solo si es de la misma versión de firmas del antivirus y tiene 24
  horas o menos. Un tenant nunca reutiliza el resultado de otro.
- **FR-025**: El archivo grande que usa una notificación MUST ser exactamente el analizado: si el archivo
  cambia en el almacén entre el análisis y su fijación, el cambio se detecta y el archivo nuevo se vuelve a
  analizar.
- **FR-026**: El componente MUST aceptar solicitudes de hasta 8 MB, suficientes para 5 adjuntos
  embebidos de 1 MB codificados; una solicitud mayor se rechaza como demasiado grande.

#### Enmienda 2026-10-03 — requisitos de corrección (FR-027 a FR-040)

- **FR-027**: Toda subida MUST terminar en un estado final con motivo registrado. Se agrega el estado final
  `FAILED` (además de `CLEAN` e `INFECTED`), alcanzable solo desde `PENDING_SCAN`, con un motivo de este
  conjunto: `OBJECT_MISSING`, `SIZE_MISMATCH`, `SCAN_EXHAUSTED`, `EXPIRED`. Modifica FR-019, que decía que
  "infectada" era el único estado final no apto. Cada paso a `FAILED` se registra con tenant, `uploadId` y
  motivo (FR-010). Una notificación que referencia una subida `FAILED` se rechaza como solicitud inválida
 .
- **FR-028**: El escaneo MUST comparar el tamaño que informa el almacén (`stat`) con `sizeBytes` y con el
  tope de 10 MB **antes** de leer el objeto, y la lectura MUST estar acotada a `sizeBytes`. Con tamaño
  distinto no se lee: el objeto se descarta y la subida pasa a `FAILED` (`SIZE_MISMATCH`). Un objeto ausente
  (`stat` vacío) pasa a `FAILED` (`OBJECT_MISSING`).
- **FR-029**: El escaneo MUST persistir la transición de la subida **antes** de borrar su objeto, y ninguna
  ruta de salida puede terminar el mensaje de escaneo sin haber dejado la subida en un estado final o haber
  enviado el mensaje a reintento o DLQ. Una transición perdida (otra réplica ya resolvió la subida) no es un
  error, pero deja registro.
- **FR-030**: El consumidor de escaneo MUST enviar directamente a la DLQ, sin reintentos, un mensaje
  irrecuperable (cuerpo no interpretable, identificadores inválidos). Un objeto que cambió durante la
  lectura (`AttachmentObjectChangedException`) sigue reintentándose, porque FR-025 exige volver a analizar
  el archivo nuevo. Los reintentos de errores recuperables respetan el máximo de intentos configurado
 .
- **FR-031**: Si el reenvío del mensaje a reintento o el envío a la DLQ fallan, el consumidor MUST NOT
  confirmar el mensaje en silencio ni dejarlo reencolarse sin pasar por el contador de intentos: el mensaje
  termina en la DLQ. El consumo es "al menos una vez" e idempotente por diseño (la transición exige
  `PENDING_SCAN`), así que un duplicado por un ack fallido después de publicar no tiene efecto (depende de
  Q9).
- **FR-032**: `:complete` MUST respetar el resultado de la transición condicionada: solo quien la gana
  publica el escaneo; quien la pierde devuelve el estado vigente leído del repositorio. La respuesta MUST
  llevar la versión y el estado posteriores a la transición, no los previos.
- **FR-033**: `:complete` sobre una subida vencida (`expiresAt` anterior al instante actual, medido con el
  reloj inyectado) MUST rechazarse y llevar la subida a `FAILED` (`EXPIRED`).
- **FR-034**: Un proceso periódico MUST identificar las subidas que permanecen en `PENDING_SCAN` más allá del
  tiempo permitido (vencidas) y las que agotan los reintentos de escaneo, marcarlas `FAILED` (`EXPIRED` o
  `SCAN_EXHAUSTED`) y eliminar sus objetos; además, MinIO MUST aplicar una regla de ciclo de vida sobre
  `uploads/` que elimine los objetos temporales huérfanos. El proceso MUST ser seguro con varias réplicas
  (actualización condicionada atómica).
- **FR-035**: La dirección de subida MUST limitar lo que se puede escribir con ella al tamaño y al tipo
  declarados, y lo que se escriba tras `:complete` MUST NOT afectar a ninguna notificación
  ni quedar sin vigilancia: el objeto huérfano de `uploads/` se descarta al resolverse la subida y por ciclo
  de vida del bucket.
- **FR-036**: El adaptador de almacén MUST calcular el vencimiento de la dirección con el reloj inyectado,
  no con `Instant.now()`. Si en el escaneo la copia limpia se hace y la transición se pierde, la copia MUST
  borrarse y registrarse.
- **FR-037**: `AttachmentUploadController` MUST construir el identificador de subida dentro del flujo
  reactivo (`Mono.defer`) en `complete` y `get`, de modo que un identificador inválido produzca el mismo
  error que cualquier otro fallo del flujo y no una excepción síncrona.
- **FR-038**: La lista de extensiones prohibidas (FR-005) MUST incluir además `.docm` `.xlsm` `.html`
  `.svg` `.iso`, y `.htm` por ser equivalente a `.html` (confirmado por el usuario el 2026-10-03).
- **FR-039**: La extracción del identificador de subida desde una clave o dirección MUST rechazar los
  segmentos `.` y `..`.
- **FR-040**: El código de adjuntos MUST NOT contener comentarios explicativos ni Javadoc en clases
  internas o records (Principio III), y los artefactos de la historia MUST reflejar la autenticación
  vigente (JWT interino con roles; el tenant sale del token y no de `X-Tenant-Id`).

### Key Entities *(include if feature involves data)*

- **Adjunto**: archivo asociado a una notificación. Atributos: tenant, nombre de archivo, tipo de
  contenido normalizado, tamaño en bytes verificado, huella SHA-256 y origen: contenido embebido (hasta 1
  MB) o referencia a una subida limpia (más de 1 MB). Pertenece a una sola notificación y se guarda con
  ella. El contenido es un dato sensible.
- **Subida de archivo**: archivo grande que un tenant sube al almacén del servicio. Atributos: tenant,
  identificador, nombre, tipo, tamaño, estado ("pendiente de análisis", "limpia", "infectada", "fallida"), motivo si
  está infectada o fallida, huella, fechas de emisión, vencimiento, aviso y análisis. Su dirección de subida es una
  credencial temporal y no se guarda.
- **Resultado de análisis**: veredicto del antivirus para una huella dentro de un tenant, con la versión
  de firmas y la fecha del análisis.
- **Declaración de adjuntos del canal**: parte de la forma de contenido del canal en el catálogo. Indica
  los tipos permitidos, el tamaño máximo por adjunto y la cantidad máxima. Ausente = el canal no acepta
  adjuntos.
- **Topes globales de adjuntos**: límites del servicio (tamaño por adjunto, tamaño agregado, cantidad por
  notificación, lista de tipos permitidos y lista de extensiones prohibidas) que acotan cualquier
  declaración de canal.
- **Capacidad de adjuntos del proveedor**: declaración de cada proveedor sobre si sabe enviar adjuntos.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de las notificaciones con adjuntos válidos para su canal, embebidos o por subida,
  se acepta y llega a entregada por el proveedor simulado con los mismos nombres, tipos, tamaños y huellas
  que envió el cliente, en el mismo orden (verificado de punta a punta).
- **SC-002**: El 100 % de las notificaciones con un adjunto inválido (una por cada regla de FR-006) se
  rechaza en la aceptación, y en cero casos queda una notificación registrada o encolada por ellas.
- **SC-003**: Cero apariciones de un contenido embebido reconocible y de una dirección de subida en los
  registros, las respuestas de error, los eventos publicados, los mensajes de despacho y análisis y las
  respuestas de consulta capturados durante las pruebas de punta a punta, mientras el nombre, el tipo, el
  tamaño y la huella sí aparecen en los registros (control positivo).
- **SC-004**: Las notificaciones sin adjunto no cambian de comportamiento: todas las pruebas existentes de
  aceptación, lote, despacho y consulta siguen pasando sin modificar sus expectativas.
- **SC-005**: Un cambio en la declaración de adjuntos de un canal se aplica a la aceptación en menos de 5
  segundos cuando el refresco del catálogo está configurado cada segundo.
- **SC-006**: En cero casos una notificación con adjuntos queda entregada por un proveedor que no declara
  saber enviarlos; con ese proveedor, una notificación sin adjuntos sí queda entregada (control positivo).
- **SC-007**: En un lote con un elemento con adjunto inválido, otro con una subida pendiente y otro
  válido, los dos primeros se rechazan con su motivo y el válido se acepta.
- **SC-008**: El 100 % de los archivos de prueba antivirus (EICAR) se detiene: embebidos, rechazados en la
  aceptación; subidos, la subida termina "infectada" y la notificación que la referencia se rechaza. En
  cero casos se acepta una notificación con ese archivo.
- **SC-009**: En cero casos se acepta una notificación que referencia una subida "pendiente de análisis",
  una "infectada" o una dirección que el servicio no emitió.
- **SC-010**: Con dos tenants, cero datos del tenant A son visibles o utilizables desde el tenant B
  (consulta y aviso de subida, referencia en una notificación, reutilización de resultados de análisis,
  respuestas de error y registros), mientras A sí puede usar sus propias subidas (control positivo).
- **SC-011**: Un archivo limpio de 10 MB pasa de "pendiente de análisis" a "limpia" en 30 segundos o
  menos desde el aviso de subida terminada, con el antivirus listo.
- **SC-012**: Una notificación con 5 adjuntos embebidos limpios de 1 MB recibe respuesta de aceptación en
  5 segundos o menos, con el antivirus listo.
- **SC-013**: En cero casos una notificación queda con un archivo grande distinto del analizado: un cambio
  del archivo en el almacén después del análisis se detecta y no se fija.
- **SC-014**: Con el antivirus no disponible, el 100 % de las notificaciones con adjuntos embebidos se
  rechaza como error temporal del servicio, y en cero casos se acepta un archivo sin analizar; las
  notificaciones sin adjuntos siguen aceptándose (control positivo).
- **SC-015**: En cero casos una subida queda `PENDING_SCAN` tras agotarse su ciclo: para cada causa
  (objeto ausente, tamaño distinto, transición perdida, vencimiento, reintentos agotados) una prueba
  automatizada comprueba que termina en `FAILED` con su motivo o que su mensaje llega a la DLQ.
- **SC-016**: Un mensaje de escaneo ilegible llega a la DLQ en exactamente 1 intento, afirmado por conteo
  exacto de invocaciones.
- **SC-017**: Con fallo forzado del reenvío o del envío a la DLQ, el mensaje termina en la DLQ y no reaparece
  en la cola de escaneo, y un mensaje que siempre falla se intenta exactamente `maxAttempts` veces (conteo
  exacto, no "al menos").
- **SC-018**: Con 10 `:complete` simultáneos de la misma subida se publica exactamente 1 mensaje de escaneo
  y todas las respuestas llevan el mismo estado final.
- **SC-019**: Con un objeto cuyo tamaño real difiere del declarado, el almacén registra 0 lecturas del
  objeto y la subida termina `FAILED` (`SIZE_MISMATCH`).
- **SC-020**: Un intento de escribir con la dirección de subida un objeto de tamaño o tipo distintos de los
  declarados es rechazado por el almacén, probado con MinIO real.
- **SC-021**: Una subida emitida y nunca completada pasa a `FAILED` (`EXPIRED`) y su objeto desaparece en un
  plazo afirmado con `Duration` tras su vencimiento.
- **SC-022**: Las extensiones nuevas y los segmentos `.` y `..` se rechazan, cada uno con una prueba, y un
  control positivo (`informe.pdf`) sigue aceptándose.

## Out of Scope

- Entregar el adjunto al destinatario por un proveedor real, incluido cómo obtiene cada proveedor el
  archivo desde el almacén del servicio: correo, SMS y push tienen historias propias (HU2-093, HU2-094 y
  HU2-095), que también habilitarán la declaración de adjuntos de su canal.
- Descargar o leer archivos desde direcciones que envía el cliente.
- Descargar un adjunto por la API del servicio (no hay operación de lectura del contenido).
- Política de retención y borrado de archivos, subidas y resultados de análisis (pendiente en Risks).
- Limpieza automática de subidas abandonadas o de análisis agotados: **entra en alcance** por la enmienda
  del 2026-10-03 (FR-034).
- Transformar el archivo (redimensionar imágenes, comprimir, convertir formato).
- Mostrar los adjuntos en las consultas de estado, de histórico o de actualizaciones en vivo.
- Adjuntos distintos por destinatario dentro de un lote.
- Registro de canales por API: la declaración de adjuntos se configura igual que hoy la forma de contenido.
- Exponer el envío en lote por HTTP.

## Assumptions

- Los tipos de contenido se expresan como tipos de medio estándar y se comparan exactos tras normalizarlos,
  sin comodines. La lista global es: `application/pdf`, `image/png`, `image/jpeg`,
  `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (DOCX),
  `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (XLSX), `text/csv` y `text/plain`.
- MB significa 1 048 576 bytes en todos los límites de esta historia, igual que el tope de 10 MB de la
  versión anterior.
- La dirección de subida es una credencial temporal emitida para un tenant y un archivo; la emite el
  servicio contra una dirección pública de su almacén, alcanzable por el cliente.
- El antivirus y el almacén de archivos son servicios de infraestructura del propio servicio, como la base
  de datos y el broker; fuera de desarrollo se operan gestionados (decisión de arquitectura pendiente en
  Risks).
- La caída del antivirus o del almacén solo afecta a las notificaciones con adjuntos; por eso no sacan al
  servicio de la lista de instancias listas para recibir tráfico, aunque su estado se informa en la
  consulta de salud.
- El tenant de cada solicitud sale del token JWT interino (HU2-096), no de un encabezado `X-Tenant-Id`;
  la identidad real depende de DEP-01.
- El envío en lote está descrito en el contrato público y existe como caso de uso, pero hoy no tiene una
  operación HTTP que lo atienda. La regla de Q5 se aplica y se prueba en el caso de uso de lote.

## Risks

- **El servicio custodia documentos de destinatarios sin política de retención**: el contenido embebido
  queda en la base de datos y los archivos grandes en el almacén, sin borrado. Ninguna política futura
  puede borrar un archivo referenciado por una notificación que no esté en estado final. Dueño: andrualv.
  Revisión: 2026-12-31.
- **Subidas abandonadas y análisis agotados**: **resuelto por la enmienda del 2026-10-03** (FR-027,
  FR-034); antes quedaban "pendientes de análisis" sin limpieza. Queda abierta solo la retención de los
  archivos limpios (riesgo anterior).
- **Decisión de arquitectura del almacén y del antivirus fuera de desarrollo** (servicio gestionado,
  dimensionamiento, actualización de firmas): no está registrada. Dueño: andrualv. Revisión: antes de
  desplegar fuera de desarrollo, a más tardar 2026-12-31.
- **Costo del antivirus en pruebas y en local**: arranque lento y más de 1 GB de memoria; encarece CI.
  Dueño: andrualv. Revisión: al medir su arranque en la primera tarea de infraestructura.
- **Motivo del fallo por proveedor sin adjuntos no textual**: el historial de intentos registra el
  proveedor y el fallo definitivo, pero no guarda un motivo textual, porque los intentos no tienen hoy un
  campo de causa. Dueño: andrualv. Revisión: con la primera historia que agregue causa a los intentos, a
  más tardar 2026-12-31.
- **Registros estructurados todavía incompletos**: el servicio aún no emite registros estructurados en
  todo el ciclo de vida; esta historia agrega registros de adjuntos, subidas y análisis (FR-010) sin cerrar
  esa brecha general. Dueño: andrualv. Revisión: 2026-12-31.
