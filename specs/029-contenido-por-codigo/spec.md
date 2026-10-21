# Feature Specification: Resolver el contenido de una notificación desde un código del catálogo de textos

**Feature Branch**: `029-contenido-por-codigo`

**Created**: 2026-10-21

**Status**: Borrador — specify y clarify completos; seis preguntas abiertas esperan respuesta del usuario antes del plan

**Input**: User description: "Como sistema cliente, quiero enviar una notificación referenciando una
plantilla en vez de contenido literal, para reutilizar mensajes ya aprobados" (HU2-074), junto con "Como
componente, quiero un puerto de salida y un adaptador que lean mensajes por código desde MessageUcoLab,
para resolver el contenido de una notificación sin acoplar el núcleo a ese servicio" (HU2-107).

**Trazabilidad**: RF-25 · HU2-074 (contenido por código) y HU2-107 (puerto y adaptador del catálogo de
textos). Prioridad baja; brecha de arquitectura: hoy el concepto no existe y el contenido de una
notificación exige siempre un cuerpo literal. Patrón de estructura: specs 008, 009, 010 y 011.

## Contrato externo que condiciona la historia

El catálogo de textos es el servicio MessageUcoLab, contrato 1.0.0. Estos hechos son datos del contrato y
fijan lo posible; no son decisiones de este componente:

- Es un catálogo de textos **cortos por código**, sin variables ni sustitución.
- El código tiene hasta 10 caracteres alfanuméricos; el servidor construye el código final anteponiendo
  `MSG_`. El título mide de 10 a 50 caracteres y el contenido de 10 a 100.
- Solo permite crear y consultar: no hay edición ni borrado, y la creación no devuelve el identificador.
  Un código, una vez creado, es inmutable.
- La consulta por código es `GET /messageucolab/v1/messages?code=MSG_…`, con credencial Bearer y
  `Accept: application/json` (sin esa cabecera responde 406). Todas las respuestas llevan el envoltorio
  `{data, errors}`; los errores relevantes son 401, 403, 404 y 422.
- El mensaje no tiene canal. La aplicación y el ambiente salen de un **contexto activo guardado en el
  servidor por identidad** (`PUT /me/context`), no de la petición de consulta.
- No hay webhook ni cabeceras de caché. La traducción dinámica existe pero está fuera de alcance.

## Decisiones ya tomadas

- Primera versión **sin variables ni sustitución**: el texto del catálogo se usa tal cual.
- **Sin traducción, sin caché y sin cortacircuitos** hacia el catálogo de textos: pertenecen a HU2-108 y
  HU2-109.
- El campo público nuevo se llama **`contentCode`**. Justificación: nombra lo que es (el código de un
  texto del catálogo) y no usa la palabra "plantilla", que en este componente ya designa otra cosa: la
  spec 028 añade `providerTemplate` para la plantilla aprobada por el proveedor del canal (p. ej.
  WhatsApp). Son mecanismos distintos —`providerTemplate` cambia cómo viaja el mensaje al proveedor;
  `contentCode` cambia de dónde sale el texto— y pueden combinarse sin ambigüedad. El campo tampoco lleva
  el prefijo `MSG_`, que es un detalle interno del servicio externo.

## Clarifications

### Session 2026-10-21 — preguntas abiertas (sin respuesta del usuario)

No fue posible consultarlas durante la redacción. Cada una lista opciones y una recomendación; **ninguna
está decidida**. Mientras no se respondan, los requisitos que dependen de ellas están marcados con la
pregunta de la que dependen y el plan no debe comenzar.

- **Q1 — ¿Cuándo se resuelve el contenido?**
  - (A) **Al aceptar la notificación**: se consulta el catálogo de textos antes de responder 202. Un
    código inexistente se rechaza de inmediato; una caída del servicio externo impide aceptar.
  - (B) **En el despacho**: se acepta guardando solo el código y se resuelve al enviar. Un código
    inexistente se descubre tarde, como fallo de la notificación; una caída se vuelve reintento.
  - **Recomendación: A.** El cliente se entera del error mientras todavía puede corregirlo; el contenido
    resuelto es lo que permite aplicar la validación del canal en la aceptación (Q4) y congelarlo (Q2); y
    como el catálogo no admite edición, esperar al despacho no aporta frescura. Costo aceptado: la
    disponibilidad de aceptación pasa a depender del servicio externo para las solicitudes con código.
  - Afecta a: FR-004, FR-009, FR-010, User Story 2 y 3, Edge Cases.
- **Q2 — ¿Qué se guarda en la notificación?**
  - (A) Solo el código; el texto se vuelve a leer cuando haga falta.
  - (B) El código **y** el título y contenido resueltos, congelados al momento de resolver.
  - **Recomendación: B.** El catálogo es inmutable por código, pero no hay garantía de que no se retire o
    recree un código en otro ambiente; guardar lo resuelto deja la auditoría completa, permite reintentos
    sin depender del servicio externo y evita que NotificationContent exija un cuerpo que no tiene. Los
    textos del catálogo son institucionales y cortos, sin datos del destinatario. Depende de Q1: si se
    elige B en Q1, esta recomendación se vuelve casi obligatoria.
  - Afecta a: FR-005, FR-006, Key Entities.
- **Q3 — ¿Con qué identidad se consulta al catálogo en un servicio multi-tenant?**
  - (A) **Una credencial por tenant**, con el contexto activo de cada una fijado una vez fuera de la
    ruta de consulta (provisión), de modo que la consulta nunca cambia de contexto.
  - (B) **Una sola credencial del servicio** que cambia el contexto activo (`PUT /me/context`) antes de
    cada consulta según el tenant. Riesgo: el contexto es estado del servidor compartido por esa
    identidad, así que dos consultas concurrentes de tenants distintos pueden pisarse y devolver el texto
    del tenant equivocado.
  - (C) **Una sola credencial y un solo contexto fijo** para todos los tenants: el catálogo se trata como
    común a la plataforma.
  - **Recomendación: A**, por ser la única que conserva el aislamiento por tenant sin estado compartido
    mutable; B se descarta por la carrera descrita. C solo es aceptable si se confirma que el catálogo de
    textos es deliberadamente compartido; en ese caso se documenta como excepción con dueño y fecha.
    Requiere una prueba con dos tenants en cualquier caso (SC-004).
  - Afecta a: FR-012, FR-013, SC-004, Risks.
- **Q4 — ¿Qué pasa con el límite de longitud (título hasta 50, contenido hasta 100) frente a canales con
  límite menor?**
  - (A) Aplicar al texto resuelto **la misma validación de forma y longitud que ya aplica el canal al
    contenido literal**, en la aceptación, y rechazar si no cabe (nunca truncar).
  - (B) No validar y dejar que falle el proveedor.
  - **Recomendación: A.** Es coherente con el rechazo sin truncar de la spec 009. Observación: con los
    límites actuales (SMS 160) ningún texto de hasta 100 caracteres lo excede, así que hoy el rechazo
    solo se dispararía si un entorno baja el límite de un canal por catálogo. Depende de Q1: si se elige
    resolver en el despacho (B), esta validación pasa a ser fallo permanente del despacho.
  - Afecta a: FR-007, SC-005, Edge Cases.
- **Q5 — ¿Cómo se relaciona un código con el canal, dado que el mensaje no tiene canal?**
  - (A) **Sin relación**: el código es agnóstico del canal; el cliente elige el canal en la solicitud como
    hoy y el mismo código puede usarse en cualquiera.
  - (B) Una tabla de configuración que restringe qué códigos pueden usarse en qué canales.
  - (C) Inferir el canal del tipo o la categoría del mensaje en el catálogo de textos.
  - **Recomendación: A.** Es lo único que el contrato respalda; B añade una tabla que mantener sin que
    nadie la haya pedido y C acopla el núcleo a campos del servicio externo que el puerto debe ocultar.
    El título se usa como asunto y el contenido como cuerpo; un canal sin asunto lo ignora, como con el
    contenido literal.
  - Afecta a: FR-005, Key Entities.
- **Q6 — ¿Cómo se comportan y clasifican el código inexistente y el servicio caído?**
  - (A) **Código inexistente (404) y código con formato inválido (422)** → fallo **permanente**, atribuible
    al cliente. **Tiempo agotado, imposibilidad de conectar y errores 5xx** → **recuperable**.
    **401 y 403** → recuperable para la notificación (no es culpa del cliente y se corrige operando el
    servicio), con registro visible y sin revelar la credencial.
  - (B) Tratar también 404 como recuperable, por si el código se acaba de crear.
  - (C) Tratar 401 y 403 como permanentes.
  - **Recomendación: A.** La creación en el catálogo es síncrona y no hay retraso de propagación
    documentado, así que reintentar un 404 solo gasta llamadas; una credencial rota se arregla sin tocar
    la notificación. Bajo Q1-A, "permanente" se traduce en rechazo inmediato al cliente y "recuperable"
    en una respuesta de servicio no disponible; bajo Q1-B, en el estado de la notificación y su reintento.
  - Afecta a: FR-008, FR-009, FR-010, SC-003.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enviar una notificación referenciando un código (Priority: P1)

Como sistema cliente, quiero enviar una notificación indicando el código de un texto ya aprobado en lugar
del cuerpo literal, para reutilizar mensajes institucionales sin copiarlos en cada llamada.

**Why this priority**: Es la razón de ser de la historia (RF-25). Sin esto no hay nada que entregar.

**Independent Test**: Con el catálogo de textos simulado fiel al contrato y un código existente, enviar
una notificación con `contentCode` y confirmar que se acepta y que el proveedor recibe el título como
asunto y el contenido como cuerpo del código referenciado.

**Acceptance Scenarios**:

1. **Given** un código existente en el catálogo de textos, **When** el cliente envía una notificación con
   `contentCode` y sin cuerpo ni asunto literales, **Then** la notificación se acepta y se entrega con el
   título del texto como asunto y su contenido como cuerpo.
2. **Given** una solicitud con `contentCode` y además un cuerpo o un asunto literales, **When** se acepta,
   **Then** se rechaza de inmediato indicando que el contenido literal y el código son excluyentes; no se
   persiste nada.
3. **Given** una solicitud sin cuerpo y sin `contentCode`, **When** se acepta, **Then** se rechaza con el
   mismo error de validación que hoy produce un cuerpo ausente.
4. **Given** una solicitud con `contentCode` y adjuntos, **When** se acepta, **Then** los adjuntos siguen
   el comportamiento actual: el código solo sustituye asunto y cuerpo.
5. **Given** una solicitud en lote, **When** cada elemento trae `contentCode`, **Then** cada uno se
   resuelve y se acepta o rechaza de forma independiente, igual que hoy con el contenido literal.
6. **Given** una solicitud con un cuerpo literal (comportamiento actual), **When** se acepta, **Then**
   nada cambia y el catálogo de textos no recibe ninguna llamada.

---

### User Story 2 - El código se valida y se rechaza lo que no existe (Priority: P1)

Como sistema cliente, quiero un error claro cuando el código no tiene el formato esperado o no existe,
para corregirlo sin adivinar.

**Why this priority**: Es el camino de error más probable y define cómo se comporta la integración ante
datos del cliente. Depende de Q1 y Q6.

**Independent Test**: Enviar un código con formato inválido, uno con prefijo `MSG_` y uno inexistente, y
confirmar cada rechazo y que no se creó ninguna notificación.

**Acceptance Scenarios**:

1. **Given** un `contentCode` de más de 10 caracteres, con caracteres no alfanuméricos o vacío, **When**
   se acepta, **Then** se rechaza por formato sin llamar al catálogo de textos.
2. **Given** un `contentCode` que empieza por `MSG_`, **When** se acepta, **Then** se rechaza por formato:
   el prefijo es un detalle interno y el cliente nunca lo escribe ni lo recibe.
3. **Given** un código con formato válido que el catálogo no conoce, **When** se resuelve, **Then** el
   cliente recibe un error identificable de código inexistente y no se persiste nada (Q1-A y Q6-A).
4. **Given** cualquier error del catálogo de textos, **When** se informa al cliente, **Then** la respuesta
   no contiene el prefijo `MSG_`, ni la URL ni la credencial del servicio, ni su texto de error crudo.

---

### User Story 3 - Una caída del catálogo de textos no se confunde con un error del cliente (Priority: P1)

Como responsable de la operación, quiero que un fallo del servicio de textos se distinga de un código
inválido, para que el cliente reintente solo cuando sirve.

**Why this priority**: Sin esta distinción una caída transitoria produciría rechazos definitivos
injustificados, o un error de credenciales quedaría atribuido al cliente.

**Independent Test**: Simular cada familia de respuesta del servicio de textos (200, 401, 403, 404, 422,
5xx, demora, conexión rechazada) y confirmar la categoría resultante.

**Acceptance Scenarios**:

1. **Given** el servicio de textos responde 404 o 422, **When** se resuelve, **Then** se clasifica como
   fallo permanente (Q6-A).
2. **Given** el servicio responde 5xx, no responde en el tiempo de espera o no acepta la conexión,
   **When** se resuelve, **Then** se clasifica como recuperable, la llamada se corta dentro del plazo
   configurado y el cliente recibe una señal de servicio no disponible (Q6-A, Q1-A).
3. **Given** el servicio responde 401 o 403, **When** se resuelve, **Then** se clasifica como recuperable
   para la notificación, queda un registro que nombra el servicio y el código de estado, nunca la
   credencial, y el cliente no recibe detalles de credenciales (Q6-A).
4. **Given** una respuesta 200 con un `data` vacío, con campos ausentes o con un contenido que no cumple
   las reglas del contrato, **When** se resuelve, **Then** se clasifica como fallo permanente del
   servicio externo, no como éxito.

---

### User Story 4 - Cada tenant solo ve lo suyo y la credencial no se filtra (Priority: P2)

Como responsable de seguridad, quiero que la identidad usada frente al catálogo de textos respete el
aislamiento entre tenants y que ninguna credencial salga del componente.

**Why this priority**: Es una garantía de aislamiento que la constitución exige probar con dos tenants. Es
P2 solo porque no bloquea la funcionalidad básica; sin ella no se puede liberar. Depende de Q3.

**Independent Test**: Con dos tenants configurados contra el servidor simulado, cada uno con textos
distintos para el mismo código, enviar una notificación por cada tenant, de forma concurrente, y
confirmar que cada una recibe el texto de su tenant; capturar registros y mensajes internos y confirmar
que no aparece ninguna credencial.

**Acceptance Scenarios**:

1. **Given** dos tenants cuyo catálogo define el mismo código con textos distintos, **When** ambos envían
   en paralelo, **Then** cada notificación lleva únicamente el texto de su tenant, repetido en ráfagas
   (Q3).
2. **Given** un tenant sin identidad configurada para el catálogo de textos, **When** envía con
   `contentCode`, **Then** se rechaza con un error de configuración del servicio, no con un error del
   cliente, y no se usa la identidad de otro tenant.
3. **Given** un despacho completo con código, **When** se revisan registros y mensajes internos, **Then**
   no aparece ninguna credencial del catálogo de textos.
4. **Given** un despliegue sin la URL base, sin la credencial o sin el contexto, **When** el servicio
   arranca, **Then** el arranque se completa, queda un registro que nombra el dato ausente sin su valor, y
   una solicitud con `contentCode` falla de forma trazable como error de configuración.

---

### User Story 5 - El núcleo no conoce MessageUcoLab (Priority: P2)

Como mantenedor, quiero que el núcleo dependa de un puerto neutral, para poder reemplazar el servicio de
textos sin tocar la lógica de negocio.

**Why this priority**: Es el criterio central de HU2-107 y el Principio I de la constitución.

**Independent Test**: Las pruebas de arquitectura siguen en verde y no hay en `core` ningún tipo, nombre
de campo ni prefijo propio de MessageUcoLab.

**Acceptance Scenarios**:

1. **Given** el puerto de salida `ContentSourcePort`, **When** se inspecciona, **Then** recibe un código y
   devuelve título y contenido, sin tipos del servicio externo ni del envoltorio `{data, errors}`.
2. **Given** el adaptador, **When** consulta, **Then** antepone `MSG_`, envía `Authorization: Bearer` y
   `Accept: application/json`, y desenvuelve el `{data, errors}` y sus errores 401, 403, 404 y 422 hacia
   los resultados del puerto.
3. **Given** `HexagonalArchitectureTest` y `ModularityTests`, **When** se ejecutan, **Then** pasan.

---

### User Story 6 - Un doble fiel al contrato sirve a pruebas de contrato y de punta a punta (Priority: P2)

Como mantenedor, quiero un servidor de pruebas que se comporte como el contrato real, para que ninguna
prueba automatizada llame al servicio real.

**Why this priority**: Sin doble no hay forma de probar los caminos de error ni el aislamiento por tenant.

**Independent Test**: El mismo conjunto de pruebas de contrato pasa contra el doble y es la base de la
prueba de punta a punta.

**Acceptance Scenarios**:

1. **Given** el doble `FakeMessageUcoLabServer`, **When** recibe una consulta sin Bearer o con uno
   inválido, **Then** responde 401 con el envoltorio de error del contrato; sin `Accept: application/json`
   responde 406.
2. **Given** el doble, **When** recibe un código sin el prefijo `MSG_` o con un formato inválido, **Then**
   responde 422; un código válido ausente responde 404; uno existente, 200 con `data` de un elemento.
3. **Given** el doble, **When** se configuran credenciales distintas, **Then** cada una ve su propio
   conjunto de textos, con permisos y contexto propios, reproduciendo el modelo de identidad y contexto
   del servicio real.
4. **Given** el doble, **When** se le pide simular lentitud, 5xx o conexión rechazada, **Then** lo hace de
   forma determinista para las pruebas de tiempo.

---

### Edge Cases

- **Código con minúsculas o mezcla** (por ejemplo `welcome`): el contrato no precisa si el servidor
  normaliza mayúsculas. Hasta confirmarlo, el componente envía el código tal como lo recibe y conserva la
  distinción; ver Assumptions.
- **Código con espacios** (el servidor los convierte en guion bajo): el cliente no puede enviarlos; se
  rechazan por formato para no depender de esa conversión.
- **El catálogo devuelve más de un elemento para el código**, o un elemento con otro código: se trata como
  respuesta inconsistente (fallo permanente del servicio externo), nunca se elige uno.
- **El texto devuelto no cumple las longitudes del contrato** (título de 51 o contenido de 101): se acepta
  si cumple las reglas del canal; el límite del servicio externo no se reimplementa aquí.
- **El contenido resuelto es muy corto o es solo espacios**: lo rechaza el invariante de cuerpo no vacío
  de la notificación, como fallo permanente del servicio externo.
- **Contexto activo caducado o cambiado del lado del servicio externo**: el siguiente intento puede ver
  otro catálogo o un 404; sin cacheo ni sincronización (HU2-108/109) es un riesgo conocido (ver Risks).
- **Notificación aceptada con texto congelado y código retirado después**: no afecta, porque se conserva
  lo resuelto (Q2-B).
- **Reintento manual o automático de una notificación creada con código**: reutiliza lo guardado, no
  vuelve a consultar el catálogo (Q2-B); si se elige Q2-A, vuelve a consultar y puede fallar por 404.
- **Solicitud con `contentCode` por un canal que no usa asunto**: el título se ignora igual que un asunto
  literal.
- **Combinación con `providerTemplate` (spec 028)**: ambos pueden coexistir; el texto del código se usa
  como contenido de la plantilla de proveedor cuando esta lo admita. Si el canal exige variables de la
  plantilla, el texto sin variables puede no ser válido; esa combinación no se valida aquí.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La solicitud de envío (individual y por lote) DEBE aceptar un campo opcional `contentCode`
  que referencia un texto del catálogo de textos. Se define primero en el contrato público, antes de
  cualquier controlador.
- **FR-002**: `contentCode` y el contenido literal (asunto y cuerpo) DEBEN ser excluyentes: se exige
  exactamente uno de los dos. Una solicitud con ambos o con ninguno DEBE rechazarse en la aceptación con
  un error de validación que lo explique. Los adjuntos son compatibles con ambos.
- **FR-003**: `contentCode` DEBE validarse por formato antes de cualquier llamada externa: de 1 a 10
  caracteres alfanuméricos, sin espacios, sin guion bajo y sin el prefijo `MSG_`.
- **FR-004**: *(depende de Q1)* El sistema DEBE resolver `contentCode` a un título y un contenido
  consultando el catálogo de textos a través del puerto, en el momento que fije Q1.
- **FR-005**: *(depende de Q2, Q5)* El título resuelto DEBE usarse como asunto y el contenido como cuerpo
  de la notificación, sin importar el canal. El canal sigue siendo el que elige el cliente.
- **FR-006**: *(depende de Q2)* La notificación DEBE conservar el `contentCode` que se usó, y, si Q2-B, el
  título y el contenido resueltos congelados.
- **FR-007**: *(depende de Q4)* El texto resuelto DEBE someterse a la misma validación de forma y
  longitud que el canal aplica al contenido literal; si no cabe, DEBE rechazarse sin truncar.
- **FR-008**: *(depende de Q6)* Cada resultado del catálogo de textos DEBE clasificarse en exactamente
  una categoría: encontrado, fallo permanente (código inexistente, código inválido, respuesta
  inconsistente) o fallo recuperable (tiempo agotado, conexión, 5xx, y credencial rechazada).
- **FR-009**: *(depende de Q1, Q6)* El fallo permanente DEBE traducirse en un rechazo inmediato y claro al
  cliente, sin persistir la notificación; el fallo recuperable DEBE traducirse en una señal de servicio no
  disponible que el cliente pueda reintentar.
- **FR-010**: Toda llamada al catálogo de textos DEBE tener un tiempo de espera explícito y configurable;
  una llamada que lo supere DEBE cortarse y clasificarse como recuperable.
- **FR-011**: El núcleo DEBE depender únicamente de un puerto de salida `ContentSourcePort` que, dado un
  código, devuelve título y contenido o un resultado de fallo categorizado, sin tipos, nombres de campo,
  prefijos ni envoltorios de MessageUcoLab.
- **FR-012**: El adaptador DEBE consultar `GET /messageucolab/v1/messages?code=MSG_{código}` con
  `Authorization: Bearer` y `Accept: application/json`, anteponer `MSG_` por sí mismo y entender el
  envoltorio `{data, errors}` con sus errores 401, 403, 404 y 422. El prefijo `MSG_` NO DEBE aparecer en
  la API pública, en eventos ni en mensajes de error al cliente.
- **FR-013**: *(depende de Q3)* La URL base, la credencial y el contexto DEBEN llegar por variables de
  entorno; NO DEBEN estar en el código, en la configuración versionada, en los registros ni en los
  mensajes internos. La identidad usada frente al catálogo DEBE respetar el aislamiento entre tenants.
- **FR-014**: Si falta la URL base, la credencial o el contexto, el servicio DEBE seguir arrancando; la
  función de contenido por código DEBE quedar deshabilitada con un motivo explícito que nombre el dato
  ausente. Una solicitud con `contentCode` en ese estado DEBE fallar de forma trazable como error de
  configuración y no como error del cliente.
- **FR-015**: Los registros del adaptador DEBEN incluir identificador de notificación o de solicitud,
  tenant, código de estado y categoría del resultado; NUNCA la credencial, ni el cuerpo del texto
  resuelto.
- **FR-016**: El comportamiento con contenido literal DEBE permanecer idéntico, sin llamadas al catálogo
  de textos.
- **FR-017**: La historia NO DEBE incluir variables ni sustitución, traducción, caché, cortacircuito ni
  reintento propio hacia el catálogo de textos (ver Out of Scope).
- **FR-018**: DEBE existir un doble de prueba fiel al contrato, `FakeMessageUcoLabServer`, que reproduzca
  autenticación Bearer, `Accept`, envoltorio `{data, errors}`, prefijo `MSG_`, errores 401, 403, 404, 406 y
  422, identidad con contexto propio y fallos simulables; ninguna prueba automatizada DEBE llamar al
  servicio real.

### Key Entities *(include if feature involves data)*

- **Código de contenido** (`contentCode`): texto de 1 a 10 caracteres alfanuméricos que identifica un
  texto del catálogo. Sin prefijo. Es un valor del cliente, distinto de `providerTemplate`.
- **Contenido resuelto**: par título y contenido devuelto por el puerto para un código. Se mapea a asunto
  y cuerpo de la notificación. No lleva campos de MessageUcoLab (tipo, categoría, aplicación,
  funcionalidad).
- **Resultado de resolución**: encontrado, fallo permanente o fallo recuperable, con un motivo que no
  contiene credenciales ni datos del servicio externo.
- **ContentSourcePort**: puerto de salida del núcleo. Su adaptador en infraestructura habla con
  MessageUcoLab.
- **Notificación**: entidad existente. Conserva el `contentCode` (y, según Q2, el texto resuelto).
  `NotificationContent` sigue exigiendo cuerpo no vacío: lo recibe ya resuelto.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con un código existente en el doble, el 100 % de las notificaciones enviadas con
  `contentCode` se aceptan y el proveedor recibe asunto igual al título y cuerpo igual al contenido
  del código, verificado en una prueba de punta a punta.
- **SC-002**: Una solicitud con `contentCode` y cuerpo literal, o con ninguno de los dos, produce un
  rechazo de validación en el 100 % de los casos y cero notificaciones persistidas.
- **SC-003**: Cada familia de respuesta del catálogo de textos (200, 401, 403, 404, 422, 5xx, demora,
  conexión rechazada, `data` vacío o inconsistente) produce la categoría esperada en el 100 % de los casos,
  verificado automáticamente.
- **SC-004**: Con dos tenants que tienen el mismo código con textos distintos, 100 envíos concurrentes
  alternados llevan en el 100 % de los casos el texto de su tenant, verificado con una prueba
  automatizada de dos tenants (Q3).
- **SC-005**: Un texto resuelto que no cabe en la forma del canal se rechaza sin truncar ni persistir;
  uno que cabe en el límite exacto se acepta, verificado automáticamente (Q4).
- **SC-006**: Una llamada al catálogo de textos que tarda más que el tiempo de espera configurado se corta
  dentro de ese plazo, con aserción explícita de `Duration`, y se clasifica como recuperable.
- **SC-007**: Cero apariciones de la credencial del catálogo de textos y del prefijo `MSG_` en la API
  pública, los eventos, los registros y los mensajes de error al cliente durante un envío completo y uno
  fallido, verificado automáticamente.
- **SC-008**: Con las variables de entorno ausentes, el 100 % de los arranques se completan, dejan
  exactamente un registro que nombra el dato ausente sin su valor, y una solicitud con `contentCode`
  falla como error de configuración.
- **SC-009**: Existe una prueba de punta a punta del flujo completo (cliente, aceptación, resolución,
  despacho, proveedor) contra `FakeMessageUcoLabServer`, y cero pruebas llaman al servicio real.
- **SC-010**: Las pruebas de arquitectura y la suite existente pasan sin regresiones; `core` no contiene
  ninguna referencia a MessageUcoLab ni a `MSG_`.

## Out of Scope

- Variables y sustitución dentro del texto (el catálogo no las ofrece en 1.0.0).
- Traducción del texto, incluida la traducción dinámica del servicio externo (HU2-108).
- Caché de textos resueltos y cortacircuito hacia el catálogo de textos (HU2-109).
- Crear, editar o borrar textos en el catálogo desde este componente.
- Plantillas aprobadas por el proveedor del canal (`providerTemplate`, spec 028).
- Listado paginado del catálogo, descubrimiento de códigos o búsqueda por texto.
- Selección o provisión del contexto activo y de las credenciales en MessageUcoLab (responsabilidad del
  responsable del ambiente).
- Gestión de secretos por la plataforma (HU2-052).

## Assumptions

- La API pública se versiona de forma compatible: `contentCode` es un campo opcional nuevo y el cuerpo
  literal deja de ser obligatorio solo si hay `contentCode` (FR-002).
- El título y el contenido del catálogo (10 a 50 y 10 a 100 caracteres) cumplen cualquier límite de canal
  declarado hoy; no se reimplementan esos límites del servicio externo.
- Como el catálogo es inmutable por código, un mismo código y contexto devuelven siempre el mismo texto.
- Las mayúsculas del código se envían tal cual las escribe el cliente; si el servicio externo distingue
  mayúsculas el resultado será un 404 y no un error silencioso. Confirmar con el responsable del servicio
  antes del plan.
- No hay límite de tasa documentado en el servicio externo; sin caché cada notificación con código genera
  una llamada. Aceptable en volumen bajo (baja prioridad).
- El aislamiento por tenant en el catálogo de textos depende de la respuesta a Q3.

## Risks

- **Sin caché ni cortacircuito**: una caída o lentitud del servicio de textos afecta directamente la
  aceptación con código y puede acumular llamadas. Mitigación: tiempo de espera corto y explícito.
  Dueño: andrualv. Fecha de revisión, junto con HU2-108 y HU2-109: 2026-12-31.
- **Contexto activo compartido por identidad** (Q3): si se usa una sola credencial para varios tenants,
  el estado del servidor externo puede mezclar catálogos. Dueño: andrualv. Fecha de revisión: 2026-11-30.
- **Sin gestión de secretos de plataforma** (HU2-052): variables de entorno aceptables en desarrollo,
  insuficientes para producción. Dueño: andrualv. Fecha de revisión: 2026-11-30.
- **Contrato externo 1.0.0 sin pruebas contra la instancia real**: el doble reproduce el manual, no el
  comportamiento no documentado (mayúsculas del código, cabeceras exactas de error). Mitigación: prueba
  manual de humo con credenciales reales, fuera de integración continua. Dueño: andrualv. Fecha de
  revisión: 2026-11-30.
- **Disponibilidad de la aceptación acoplada a un servicio externo** (si Q1-A): aceptado solo para las
  solicitudes con `contentCode`. Dueño: andrualv. Fecha de revisión: 2026-12-31.
