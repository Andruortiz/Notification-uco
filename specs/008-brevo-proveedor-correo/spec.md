# Feature Specification: Integrar Brevo como primer proveedor real de correo

**Feature Branch**: `008-brevo-proveedor-correo`

**Created**: 2026-09-21

**Status**: Aprobado por el usuario (2026-09-23)

**Input**: User description: "Como sistema cliente, quiero que mis notificaciones por correo se entreguen
de verdad a través de Brevo, para que el componente deje de depender del proveedor simulado."

## Clarifications

### Session 2026-09-21 — confirmadas (2026-09-23)

Las cuatro preguntas siguientes se identificaron durante la redacción del spec y en la revisión de
ambigüedad. Se redactaron con una respuesta recomendada, y esa respuesta queda confirmada tal cual
(ver `plan.md § Estado del plan`).

- **Q1 — ¿Qué hace el sistema con una notificación de correo que no trae asunto?** El contrato público
  declara el asunto como opcional ("algunos canales no lo usan"), pero el proveedor real lo exige para
  un correo transaccional sin plantilla.
  - **Respuesta confirmada por el usuario**: se trata como **fallo permanente explícito y sin llamar al
    proveedor**. La notificación queda en estado terminal con un intento registrado y un motivo que
    nombra el campo faltante; no se consume cuota del proveedor ni se envía un correo sin asunto.
  - Alternativas descartadas: (a) un asunto por defecto configurable — entrega un correo que el cliente
    no pidió y oculta un error de integración; (b) rechazar la notificación en la aceptación — cambia el
    contrato público para todos los canales, no solo para correo, y excede esta historia.
  - Afecta a: FR-012, SC-008, Edge Cases.
- **Q2 — ¿Cuál es el orden de proveedores del canal de correo por defecto, y cómo se elige por
  entorno?** El catálogo usa el primer proveedor de la lista como preferente; hoy la configuración
  siembra un solo proveedor simulado.
  - **Respuesta confirmada por el usuario**: la lista de proveedores del canal de correo se vuelve
    **configurable por entorno**, con valor por defecto `simulado, real` — es decir, en desarrollo y en
    integración continua sigue enviando el simulado (que es lo que hoy hace que las pruebas pasen sin
    credenciales) y el proveedor real queda **listado** en el catálogo, que es lo que pide el criterio de
    aceptación. En producción el entorno invierte el orden para que envíe el proveedor real.
  - Alternativas descartadas: (a) sembrar `real, simulado` como valor por defecto — rompe el flujo local
    y el de integración continua, donde no hay credenciales; (b) fijar el orden en la configuración
    versionada sin posibilidad de sobreescritura por entorno — obliga a desplegar código para cambiar de
    proveedor, justo lo que el catálogo existe para evitar.
  - Afecta a: FR-002, FR-011, SC-002, SC-007, Assumptions.
- **Q3 — ¿Cómo se clasifica un rechazo del proveedor por condición de la cuenta (crédito agotado,
  cuenta suspendida) y no por el contenido de la petición?** Los criterios de aceptación enumeran
  tiempo agotado, fallo del servidor y límite de tasa como recuperables, y destinatario o credenciales
  inválidas y petición rechazada como permanentes; una cuenta sin crédito no encaja limpiamente en
  ninguno de los dos grupos.
  - **Respuesta confirmada por el usuario**: **fallo recuperable**. La condición se resuelve sin tocar
    la notificación (recargando la cuenta) y marcarla como definitivamente fallida destruiría todas las
    notificaciones en vuelo por una causa administrativa ajena a ellas.
  - Alternativa descartada: fallo permanente — cada notificación afectada quedaría en estado terminal y
    habría que reencolarlas a mano después de recargar la cuenta.
  - Afecta a: FR-006, Edge Cases.
- **Q4 — ¿El cuerpo de la notificación se entrega al destinatario como texto plano o como documento
  enriquecido?** El contrato público acepta un único campo de cuerpo sin declarar su tipo, y el
  proveedor real distingue ambos formatos con campos distintos.
  - **Respuesta confirmada por el usuario**: **texto plano**. El contrato no declara tipo de contenido y
    entregar texto arbitrario como documento enriquecido rompe los saltos de línea y abre una vía de
    inyección de marcado desde el sistema cliente.
  - Alternativas descartadas: (a) entregar siempre como documento enriquecido — cambia lo que ve el
    destinatario según lo que el cliente escriba y exige sanear el cuerpo, que es una historia aparte;
    (b) deducir el formato del cuerpo — comportamiento implícito e imposible de predecir para el cliente.
  - Consecuencia de haber elegido documento enriquecido (descartada): habría que decidir el saneamiento del cuerpo
    y ampliar el contrato público con un tipo de contenido, lo que excede esta historia.
  - Afecta a: FR-015, Assumptions.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Las notificaciones de correo se entregan de verdad (Priority: P1)

Como sistema cliente, quiero que una notificación de correo que el componente acepta termine llegando
al buzón del destinatario a través del proveedor real de correo, para dejar de depender del proveedor
simulado, que confirma entregas que nunca ocurren.

**Why this priority**: Es la razón de ser de la historia y el primer momento en que el componente
entrega valor externo. Hoy todo el flujo funciona de punta a punta pero nadie recibe nada: el estado
`ENTREGADA` es una ficción. Sin esto, ninguna otra historia de despacho tiene sentido real.

**Independent Test**: Con el proveedor real declarado como preferente del canal de correo y sus
credenciales presentes, aceptar una notificación de correo y confirmar que (a) el proveedor real
recibió una petición de envío con el destinatario, el asunto y el cuerpo de esa notificación, (b) la
notificación quedó registrada como entregada y (c) el intento de entrega quedó anotado con el
identificador del proveedor real, no con el del simulado.

**Acceptance Scenarios**:

1. **Given** el canal de correo con el proveedor real como preferente y credenciales válidas, **When**
   se acepta y despacha una notificación de correo, **Then** el proveedor real recibe exactamente una
   petición de envío con el destinatario, el asunto y el cuerpo de esa notificación, y la notificación
   queda entregada con el identificador del proveedor real en su intento.
2. **Given** la misma configuración, **When** el proveedor acepta el envío, **Then** el intento de
   entrega queda registrado como aceptado y el estado de la notificación es el terminal de éxito, igual
   que con el proveedor simulado.
3. **Given** el canal de correo con el proveedor simulado como preferente, **When** se despacha una
   notificación, **Then** el proveedor real no recibe ninguna petición y el comportamiento es idéntico
   al de antes de esta historia.
4. **Given** una notificación de correo, **When** se construye la petición de envío, **Then** el
   remitente es el remitente verificado configurado por entorno y no un valor fijo en el código.

---

### User Story 2 - Sin credenciales el proveedor se deshabilita con un motivo visible (Priority: P1)

Como responsable de la operación, quiero que un despliegue sin credenciales del proveedor real deje
constancia explícita de que ese proveedor está deshabilitado y por qué, para enterarme al arrancar y no
al descubrir que nadie recibió sus correos.

**Why this priority**: Es la garantía de seguridad operativa de la historia y tiene la misma prioridad
que la entrega. Un proveedor que falla en silencio es peor que un proveedor ausente: el servicio parece
sano mientras las notificaciones se acumulan. Además es la condición que hace seguro incorporar el
proveedor real al catálogo antes de que existan las credenciales en todos los entornos.

**Independent Test**: Arrancar el servicio sin la credencial del proveedor real y confirmar que (a) el
arranque se completa, (b) queda un registro explícito que nombra al proveedor y el motivo concreto de
la deshabilitación, (c) ese registro no contiene ningún valor de credencial, y (d) una notificación
dirigida a ese proveedor no se da por entregada, no se pierde y deja un rastro consultable.

**Acceptance Scenarios**:

1. **Given** un despliegue sin la credencial del proveedor real, **When** el servicio arranca,
   **Then** el arranque se completa y queda constancia explícita de que ese proveedor está
   deshabilitado, nombrando el motivo (credencial ausente) y sin revelar ningún valor de credencial.
2. **Given** un despliegue sin la credencial y con el proveedor real como preferente del canal,
   **When** se despacha una notificación de correo, **Then** la notificación no se marca como
   entregada, no se marca como fallida por el proveedor, conserva su historial y el fallo queda
   registrado con el motivo de deshabilitación.
3. **Given** un despliegue sin el remitente verificado configurado, **When** el servicio arranca,
   **Then** el comportamiento es el mismo que sin credencial: deshabilitado con motivo explícito.
4. **Given** un despliegue con credencial y remitente presentes, **When** el servicio arranca,
   **Then** el proveedor real queda habilitado y no se emite ningún aviso de deshabilitación.
5. **Given** cualquiera de los dos despliegues, **When** se revisan los registros y los mensajes que el
   sistema publica internamente, **Then** en ninguno aparece el valor de la credencial.

---

### User Story 3 - Los fallos del proveedor se clasifican y no se confunden entre sí (Priority: P2)

Como responsable de la operación, quiero que cada respuesta del proveedor real se traduzca a una de las
tres categorías que el componente ya entiende — aceptada, fallo recuperable o fallo permanente — para
que los reintentos se disparen solo cuando sirven y una notificación irrecuperable no consuma reintentos
inútiles.

**Why this priority**: Sin clasificación correcta, el mecanismo de reintentos existente se vuelve
dañino: reintentar un destinatario inexistente gasta cuota y termina igual, y no reintentar un fallo
temporal del proveedor pierde entregas que habrían funcionado al segundo intento. Es P2 porque depende
de que la entrega básica (P1) funcione.

**Independent Test**: Simular cada familia de respuesta del proveedor (aceptación, rechazo por
destinatario inválido, rechazo por autenticación, límite de tasa, fallo temporal del proveedor, tiempo
agotado, imposibilidad de conectar) y confirmar que cada una produce exactamente la categoría esperada
y el estado de notificación correspondiente.

**Acceptance Scenarios**:

1. **Given** el proveedor real habilitado, **When** el proveedor acepta el envío, **Then** el intento
   se registra como aceptado.
2. **Given** el proveedor real habilitado, **When** el proveedor rechaza el envío por destinatario
   inválido o por petición malformada, **Then** el intento se registra como fallo permanente y la
   notificación no se reintenta.
3. **Given** el proveedor real habilitado, **When** el proveedor responde con un fallo temporal propio o
   con un límite de tasa alcanzado, **Then** el intento se registra como fallo recuperable y la
   notificación entra en el camino de reintentos existente.
4. **Given** el proveedor real habilitado, **When** el proveedor no responde dentro del tiempo de espera
   configurado, **Then** la llamada se corta en ese plazo, el intento se registra como fallo recuperable
   y el despacho no queda bloqueado indefinidamente.
5. **Given** el proveedor real habilitado, **When** no es posible establecer la conexión con el
   proveedor, **Then** el intento se registra como fallo recuperable.
6. **Given** el proveedor real habilitado, **When** el proveedor rechaza el envío por credenciales
   inválidas, **Then** el intento se registra como fallo permanente y el motivo queda registrado sin
   revelar la credencial.

---

### User Story 4 - Nada sensible sale del componente (Priority: P2)

Como responsable de seguridad, quiero que ni la credencial del proveedor ni el contenido de las
notificaciones aparezcan en los registros ni en los mensajes internos del sistema, para que la
integración con un proveedor externo no abra una vía de fuga de datos.

**Why this priority**: Es un requisito no funcional obligatorio del proyecto y esta historia es la
primera que introduce una credencial de un tercero. Es P2 solo porque no bloquea la entrega; su
incumplimiento, en cambio, sí bloquearía la puesta en producción.

**Independent Test**: Ejecutar un despacho completo con el proveedor real habilitado, capturar todos
los registros emitidos y todos los mensajes que el sistema publica internamente, y confirmar que en
ninguno aparece el valor de la credencial, el asunto, el cuerpo ni la dirección del destinatario.

**Acceptance Scenarios**:

1. **Given** un despacho completo por el proveedor real, **When** se revisan los registros emitidos,
   **Then** no aparece el valor de la credencial en ninguna forma.
2. **Given** un despacho completo por el proveedor real, **When** se revisan los registros emitidos,
   **Then** no aparece el asunto ni el cuerpo de la notificación.
3. **Given** un despacho completo por el proveedor real, **When** se revisan los mensajes que el sistema
   publica internamente, **Then** no aparece la credencial ni el contenido de la notificación.
4. **Given** un despacho que falla, **When** se revisa el registro del fallo, **Then** contiene
   identificador de notificación, tenant, proveedor y categoría del fallo — suficiente para
   diagnosticar — y ninguno de los datos prohibidos.

---

### User Story 5 - Un mensaje repetido no produce dos correos (Priority: P3)

Como sistema cliente, quiero que una caída del componente justo después de que el proveedor aceptó un
envío no haga que el destinatario reciba el mismo correo dos veces, para que "al menos una vez" no se
traduzca en correos duplicados visibles.

**Why this priority**: Es el riesgo explícito que la historia pide resolver, pero su mitigación depende
de una capacidad del proveedor externo que hay que comprobar. Es P3 porque la entrega, la
deshabilitación segura y la clasificación aportan valor aunque este punto quede con riesgo residual
documentado.

**Independent Test**: Despachar dos veces la misma notificación y confirmar que ambas peticiones al
proveedor llevan la misma clave de idempotencia, derivada del identificador de la notificación y
estable entre intentos.

**Acceptance Scenarios**:

1. **Given** una notificación, **When** se envía al proveedor, **Then** la petición incluye una clave de
   idempotencia derivada del identificador de esa notificación.
2. **Given** la misma notificación despachada dos veces, **When** se comparan las dos peticiones al
   proveedor, **Then** ambas llevan exactamente la misma clave de idempotencia.
3. **Given** dos notificaciones distintas, **When** se comparan sus peticiones, **Then** sus claves de
   idempotencia son distintas.

---

### Edge Cases

- **¿Qué pasa si el proveedor real está listado en el catálogo pero deshabilitado por falta de
  credenciales?** La notificación no se entrega, no se marca como fallida por el proveedor y conserva su
  historial; el despacho falla de forma trazable con el motivo de deshabilitación, igual que un
  proveedor sin adaptador. Es un error de configuración, no de entrega.
- **¿Qué pasa si la notificación no trae asunto?** Ver Q1 en Clarifications (confirmada):
  fallo permanente explícito sin llamar al proveedor.
- **¿Qué pasa si el proveedor acepta el envío pero el componente cae antes de registrar el resultado?**
  El mensaje se reentrega y la notificación se vuelve a despachar. La clave de idempotencia es el único
  mecanismo que puede evitar el correo duplicado, y su efecto real no está garantizado (ver Assumptions
  y Risks). Riesgo residual documentado, no resuelto.
- **¿Qué pasa si el proveedor responde con un tiempo agotado después de haber aceptado internamente el
  envío?** Se clasifica como recuperable y se reintenta, lo que puede duplicar el correo. Mismo riesgo
  residual que el punto anterior.
- **¿Qué pasa si el proveedor responde algo que no encaja en ninguna familia conocida?** Se clasifica de
  forma conservadora: si es un rechazo atribuible a la petición, fallo permanente; en cualquier otro
  caso, fallo recuperable, porque reintentar es menos dañino que descartar.
- **¿Qué pasa si la cuenta del proveedor se queda sin crédito?** Ver Q3 en Clarifications (confirmada):
  fallo recuperable.
- **¿Qué pasa en un entorno que ya tiene el catálogo sembrado?** La siembra de configuración solo actúa
  sobre un catálogo vacío, así que listar el proveedor real en la configuración **no** actualiza por sí
  sola un catálogo ya existente. Ese entorno requiere un paso explícito de actualización del catálogo
  (ver FR-011 y Assumptions).
- **¿Qué pasa si el proveedor tarda más que el tiempo de espera en cada intento?** Cada llamada se corta
  en el plazo configurado; el despacho no se bloquea y los reintentos siguen la política existente hasta
  agotarse.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE disponer de un proveedor de envío dedicado al proveedor real de correo,
  distinto del proveedor simulado y con su propio identificador estable, que entregue notificaciones de
  correo a través de ese proveedor.
- **FR-002**: El catálogo del canal de correo DEBE listar al proveedor real entre sus proveedores.
- **FR-003**: Las credenciales del proveedor real (clave de acceso y remitente verificado) DEBEN llegar
  exclusivamente por variables de entorno o un mecanismo equivalente de secretos. NO DEBEN estar en el
  código, en la configuración versionada, en los registros ni en los mensajes que el sistema publica
  internamente.
- **FR-004**: Si falta la clave de acceso o el remitente verificado, el proveedor real DEBE quedar
  deshabilitado con un motivo explícito y consultable, y el servicio DEBE seguir arrancando. La
  deshabilitación NUNCA DEBE ser silenciosa.
- **FR-005**: Una notificación dirigida a un proveedor deshabilitado NO DEBE darse por entregada, NO
  DEBE marcarse como fallida por el proveedor y NO DEBE perderse; el despacho DEBE fallar de forma
  trazable nombrando el motivo de deshabilitación, sin registrar intento de entrega.
- **FR-006**: El sistema DEBE traducir cada respuesta del proveedor real a exactamente una de las tres
  categorías existentes: aceptada, fallo recuperable o fallo permanente. Como mínimo: aceptación del
  proveedor → aceptada; tiempo de espera agotado, imposibilidad de conectar, fallo temporal del
  proveedor y límite de tasa alcanzado → fallo recuperable; destinatario inválido, petición rechazada por
  malformada y credenciales inválidas → fallo permanente.
- **FR-007**: Toda llamada al proveedor real DEBE tener un tiempo de espera explícito y configurable;
  una llamada que lo supere DEBE cortarse y clasificarse como fallo recuperable.
- **FR-008**: Los registros que produce el proveedor real NO DEBEN incluir el contenido de la
  notificación (asunto ni cuerpo), la dirección del destinatario ni el valor de la credencial. SÍ DEBEN
  incluir identificador de notificación, tenant, identificador de proveedor y categoría del resultado.
- **FR-009**: Cada petición al proveedor real DEBE llevar una clave de idempotencia derivada del
  identificador de la notificación, estable entre reintentos de la misma notificación y distinta entre
  notificaciones distintas.
- **FR-010**: El intento de entrega DEBE quedar registrado con el identificador del proveedor real
  cuando sea ese proveedor el que ejecutó el envío.
- **FR-011**: La lista de proveedores del canal de correo DEBE poder configurarse por entorno sin
  modificar código; el valor por defecto de la configuración versionada DEBE ser seguro para un entorno
  sin credenciales (ver Q2).
- **FR-012**: Una notificación de correo sin asunto DEBE producir un fallo permanente explícito que
  nombre el campo faltante, sin llamar al proveedor (ver Q1).
- **FR-013**: El proveedor simulado DEBE seguir disponible y operativo, y DEBE seguir siendo el que
  envía mientras el catálogo lo declare preferente.
- **FR-014**: El sistema NO DEBE, en esta historia, aplicar límites de tasa propios, cortacircuitos ni
  conmutación al siguiente proveedor ante un fallo del real.
- **FR-015**: El cuerpo de la notificación DEBE entregarse al proveedor como texto plano, sin
  interpretarse como documento enriquecido (ver Q4).

### Key Entities *(include if feature involves data)*

- **Proveedor real de correo**: nuevo proveedor de envío del componente. Se identifica por un
  identificador estable propio y conoce una credencial de acceso y un remitente verificado, ambos
  inyectados por entorno. No persiste entidades nuevas.
- **Resultado del intento**: entidad ya existente (aceptada / fallo recuperable / fallo permanente).
  Esta historia define cómo se deriva desde las respuestas del proveedor real.
- **Ruta de canal**: entidad ya existente. Esta historia agrega al proveedor real a la lista de
  proveedores del canal de correo y hace esa lista configurable por entorno.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el proveedor real habilitado y declarado preferente, el 100 % de las notificaciones
  de correo despachadas producen exactamente una petición de envío al proveedor con el destinatario, el
  asunto y el cuerpo de esa notificación.
- **SC-002**: El canal de correo lista al proveedor real entre sus proveedores en el catálogo
  consultable, en un entorno sembrado desde cero.
- **SC-003**: En un arranque sin credenciales, el 100 % de los arranques se completan y dejan
  exactamente un registro que nombra al proveedor y el motivo de la deshabilitación.
- **SC-004**: Cero apariciones del valor de la credencial y del contenido de la notificación (asunto,
  cuerpo, dirección del destinatario) en los registros emitidos y en los mensajes internos durante un
  despacho completo, verificado automáticamente.
- **SC-005**: Cada una de las siete familias de respuesta del proveedor (aceptación, destinatario
  inválido, credenciales inválidas, límite de tasa, fallo temporal del proveedor, tiempo agotado,
  imposibilidad de conectar) produce la categoría esperada en el 100 % de los casos, verificado
  automáticamente.
- **SC-006**: Ninguna llamada al proveedor excede el tiempo de espera configurado; una respuesta que
  tarda más se corta dentro de ese plazo y se clasifica como recuperable, verificado con una aserción
  explícita de duración.
- **SC-007**: Con el valor por defecto de la configuración versionada y sin credenciales, el flujo de
  punta a punta sigue entregando notificaciones exactamente como antes de esta historia (cero
  regresiones en el conjunto de pruebas existente).
- **SC-008**: Una notificación de correo sin asunto termina en estado terminal de fallo permanente con
  un motivo que nombra el campo faltante, y produce cero peticiones al proveedor.
- **SC-009**: Dos despachos de la misma notificación producen dos peticiones con la misma clave de
  idempotencia; dos notificaciones distintas producen claves distintas.
- **SC-010**: Existe una prueba de punta a punta automatizada que ejercita el flujo completo contra un
  servidor que simula al proveedor, y cero pruebas de la suite automatizada llaman al proveedor real.

## Out of Scope

- Batería de pruebas de contrato común a todos los adaptadores de proveedor (HU2-038). El adaptador de
  esta historia la adoptará cuando exista.
- Gestión de secretos por la plataforma (HU2-052). Esta historia se limita a variables de entorno.
- Límites de tasa y cuotas por proveedor o por tenant (HU2-039), cortacircuitos y conmutación al
  siguiente proveedor ante un fallo (HU2-048).
- Plantillas del proveedor, adjuntos, seguimiento de aperturas y clics, webhooks de eventos del
  proveedor (entregado, rebotado, marcado como spam).
- Canales distintos del correo y proveedores de correo distintos del de esta historia.
- Cambiar el contrato público de aceptación de notificaciones, su modelo de datos o el mecanismo de
  refresco del catálogo.
- Infraestructura de registro estructurado a nivel de aplicación: esta historia se limita a no filtrar
  datos prohibidos en sus propios registros.

## Assumptions

- El usuario aporta una cuenta del proveedor con su clave de acceso y un remitente verificado, y los
  entrega por variable de entorno. Sin ellos, el proveedor queda deshabilitado (FR-004) y esta historia
  se valida igualmente contra un servidor que lo simula.
- La documentación del proveedor menciona una cabecera de clave de idempotencia únicamente como ejemplo
  y **no documenta su semántica** (si deduplica, durante cuánto tiempo, qué responde ante una
  repetición). Se envía igualmente (FR-009) pero la deduplicación **no está garantizada**: su efecto
  real solo se confirma en la prueba manual de humo con cuenta real. Hasta entonces el riesgo de correo
  duplicado tras una reentrega es un riesgo residual conocido, no un problema resuelto.
- La entrega del componente es "al menos una vez". El estado persistido de la notificación acota
  parcialmente el duplicado (una notificación ya en estado terminal no vuelve a despacharse por la vía
  normal), pero no lo elimina: la ventana entre que el proveedor acepta y que el componente persiste el
  resultado queda abierta.
- El contenido de la notificación es texto plano con un asunto opcional; no hay declaración de tipo de
  contenido por notificación. El cuerpo se entrega como texto, no como documento enriquecido (ver Q4).
  El campo de esquema de contenido que la ruta de canal ya tiene está vacío hoy y esta historia no lo
  usa.
- La siembra del catálogo actúa solo sobre un catálogo vacío. Los entornos con catálogo ya sembrado
  requieren un paso explícito de actualización para que el canal de correo liste al proveedor real; ese
  paso se documenta como parte de la historia y no se automatiza aquí.
- La prueba automatizada de punta a punta usa un servidor que simula al proveedor; la verificación
  contra la cuenta real es una prueba manual de humo documentada y ejecutada fuera de la integración
  continua.
- El aislamiento por tenant sigue viniendo de la consulta al catálogo; esta historia no introduce ni
  relaja ninguna regla de aislamiento.

## Risks

- **Correo duplicado tras una reentrega** (ver Assumptions). Mitigación parcial: clave de idempotencia
  no garantizada. Riesgo residual aceptado para esta historia; se revisa tras la prueba manual de humo.
  Dueño: andrualv. Fecha de revisión: 2026-10-31.
- **Sin límite de tasa propio** (HU2-039 no existe). Exponer el proveedor real a carga real sin él puede
  provocar una ráfaga de respuestas de límite de tasa y, por tanto, reintentos masivos. Dueño: andrualv.
  Fecha de revisión antes de exponer a carga real: 2026-11-30.
- **Sin gestión de secretos de plataforma** (HU2-052 no existe). Las variables de entorno son
  aceptables en desarrollo, insuficientes para producción. Dueño: andrualv. Fecha de revisión antes del
  despliegue productivo: 2026-11-30.
- **Sin batería de contrato de adaptadores** (HU2-038 no existe). Las garantías del adaptador se
  verifican con pruebas propias de esta historia; cuando exista la batería común, este adaptador debe
  adoptarla. Dueño: andrualv. Fecha de revisión: 2026-12-31.
