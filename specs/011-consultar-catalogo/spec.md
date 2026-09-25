# Feature Specification: Consultar el catálogo de canales y proveedores

**Feature Branch**: `feature/HU2-085-consultar-catalogo`

**Created**: 2026-09-25

**Status**: Draft — Q1–Q4 de Clarifications registradas con respuesta recomendada, pendientes de
confirmación del usuario al aprobar el plan

**Input**: User description: "Como operador, quiero consultar los canales y proveedores registrados en el
catálogo y su estado, para saber cómo se enrutan las notificaciones sin entrar a la base."

**Trazabilidad**: CU-07 y CU-08 (solo lectura) · RF-19, RF-20 · destraba la pantalla del panel de
administración que consulta el catálogo de canales y proveedores. Lectura pura sobre el catálogo dinámico
que ya existe (HU2-042); no depende de las historias de registro de canal y proveedor (HU2-044, HU2-045).

## Clarifications

### Session 2026-09-25 — respuestas recomendadas, pendientes de confirmación

Las preguntas siguientes se identificaron al redactar el spec. No fue posible consultarlas durante la
redacción, así que cada una se registra con una respuesta recomendada que el resto del documento ya aplica.
Quedan pendientes de confirmación: el usuario las confirma o las cambia al aprobar el plan
(`plan.md § Estado del plan`). Para cada una se indica qué partes cambian si la respuesta es otra.

- **Q1 — ¿La consulta del catálogo es por tenant o global? ¿Exige el identificador de tenant del
  solicitante?** Hoy el catálogo no tiene ningún concepto de tenant: es una tabla técnica de enrutamiento
  (canal → proveedores en orden de preferencia + forma de contenido) compartida por todo el despliegue. La
  resolución de ruta recibe el tenant pero no lo usa. Las demás operaciones de la API, incluidas las de
  registro de canal y proveedor ya descritas en el contrato, exigen el identificador de tenant.
  - **Respuesta recomendada**: **contenido global, identificador de tenant exigido**. La consulta exige el
    identificador de tenant igual que toda la API (sin él, o vacío, se rechaza como solicitud inválida),
    pero la respuesta es la misma para cualquier tenant: el catálogo es del despliegue, no de un cliente.
    Esta historia no inventa datos de catálogo por tenant y nada en la respuesta identifica ni depende del
    tenant que pregunta.
  - Por qué: (a) consistencia — sería la única operación de la API sin identidad del solicitante; (b) la
    constitución exige autenticación en toda operación una vez se integre el componente de seguridad, y el
    identificador de tenant es hoy el sustituto provisional de esa identidad; exigirlo desde ahora evita
    un cambio incompatible para el panel cuando llegue la autenticación y la restricción a
    administradores que el contrato ya anuncia para las operaciones del catálogo; (c) el panel ya envía
    ese identificador en todas sus llamadas.
  - Alternativas descartadas: (a) sin identificador — coherente con la naturaleza global del dato, pero
    deja la operación fuera del modelo de identidad de la API y obliga a romper a los clientes cuando se
    integre la seguridad; (b) filtrar por tenant — no hay ningún dato por tenant que filtrar; hacerlo
    exigiría inventar un modelo de catálogo por tenant que el dominio no tiene.
  - Si cambia a (a): FR-008 pasa a "no exige identificador", se retira el escenario 3 de la User Story 4 y
    SC-003 se reduce a "la respuesta no contiene datos de ningún tenant".
  - Afecta a: FR-008, User Story 4, SC-003, Risks.
- **Q2 — ¿La respuesta incluye el estado real de habilitación de cada proveedor, y el motivo cuando no
  está habilitado?** Hoy el catálogo guardado no sabe si un proveedor puede enviar: esa información vive en
  cada adaptador de proveedor, que al arrancar se declara deshabilitado con un motivo si le falta o tiene
  mal formada una credencial. Además, el catálogo puede nombrar un proveedor para el que el despliegue no
  tiene adaptador (por ejemplo, un error de escritura en la configuración); el despacho lo descubre recién
  al intentar enviar.
  - **Respuesta recomendada**: **sí, se cruza**. Cada proveedor mostrado lleva uno de tres estados:
    **habilitado**, **deshabilitado** (con el motivo que ya declara su adaptador, que nombra la
    configuración ausente o mal formada y nunca su valor) o **sin adaptador** (el catálogo lo nombra pero
    el despliegue no sabe enviar por él). No se inventa un estado para el canal: un canal existe en el
    catálogo o no existe.
  - Por qué: "y su estado" es parte de la historia, y el único estado que existe hoy es el del proveedor.
    Sin él, el operador ve "EMAIL → brevo" y no puede saber que todo correo está fallando porque a ese
    proveedor le falta la credencial — que es justamente lo que necesita saber sin entrar a la base ni a
    los registros. El costo de diseño es acotado: el motivo ya existe en cada adaptador y el registro de
    adaptadores por identificador de proveedor ya es parte del núcleo.
  - Alternativas descartadas: (a) diferirlo a otra historia — deja vacía la mitad de la historia y obliga
    a rehacer la pantalla del panel después; (b) un booleano "habilitado" más una lista de "credenciales
    faltantes", como anticipa la respuesta de registro de proveedor del contrato — no representa
    credenciales mal formadas o ambiguas, que no son "faltantes", ni el caso sin adaptador.
  - Si cambia a (a): se retiran la User Story 2, FR-002 a FR-004, FR-013 y SC-002; la respuesta queda en
    canal → proveedores en orden → forma de contenido.
  - Afecta a: User Stories 1–3, FR-002 a FR-005, FR-013, SC-002, SC-004, Key Entities.
- **Q3 — ¿Qué proveedores lista la consulta de proveedores?** Hay dos fuentes: los que nombra el catálogo
  y los que tienen adaptador en el despliegue. No siempre coinciden.
  - **Respuesta recomendada**: **la unión de ambas**. Un proveedor con adaptador que ningún canal usa
    aparece sin canales; un proveedor que el catálogo nombra sin adaptador aparece con estado "sin
    adaptador". Cada proveedor indica en qué canales aparece y en qué posición de preferencia.
  - Alternativas descartadas: (a) solo los del catálogo — oculta que un proveedor está disponible pero no
    enrutado; (b) solo los que tienen adaptador — oculta los errores de escritura del catálogo, que son la
    causa de fallo más difícil de ver.
  - Afecta a: User Story 3, FR-005.
- **Q4 — ¿La consulta muestra lo guardado en la base o lo que el enrutamiento está usando en ese
  momento?** El enrutamiento no lee la base en cada envío: usa una vista del catálogo cargada en memoria
  que se refresca periódicamente (cada 30 s por defecto).
  - **Respuesta recomendada**: **lo que usa el enrutamiento**. La consulta lee la misma vista que usa el
    despacho: lo que el operador ve es exactamente cómo se está enrutando. Un cambio en la base aparece en
    la consulta en el mismo refresco en que el enrutamiento empieza a usarlo, no antes. Un canal guardado
    sin ningún proveedor no es enrutable y no aparece, igual que no existe para el envío.
  - Alternativa descartada: leer la base directamente — mostraría una ruta que el despacho todavía no
    aplica (o que nunca aplicará, como un canal sin proveedores), contradiciendo el propósito de la
    historia.
  - Afecta a: FR-007, FR-011, Edge Cases, SC-005.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver cómo se enruta cada canal (Priority: P1)

Como operador, quiero consultar los canales del catálogo con sus proveedores en orden de preferencia y la
forma de contenido que exige cada uno, para saber por dónde sale una notificación de cada canal sin entrar a
la base.

**Why this priority**: Es la razón de ser de la historia y lo que desbloquea la pantalla del panel. Hoy la
única forma de saberlo es leer la colección del catálogo o la configuración del despliegue.

**Independent Test**: Con el catálogo sembrado por la configuración por defecto, consultar los canales y
confirmar que aparecen EMAIL, PUSH y SMS, cada uno con sus proveedores en el orden guardado, la posición de
preferencia de cada uno y su forma de contenido (o su ausencia).

**Acceptance Scenarios**:

1. **Given** el catálogo con EMAIL → [simulated, brevo], **When** el operador consulta los canales,
   **Then** EMAIL aparece con simulated en la posición 1 y brevo en la posición 2, en ese orden.
2. **Given** un canal con forma de contenido declarada, **When** se consulta, **Then** la forma de
   contenido aparece tal como está guardada; **Given** un canal sin forma de contenido, **Then** aparece
   explícitamente como ausente.
3. **Given** varios canales, **When** se consultan, **Then** aparecen en orden alfabético por
   identificador, siempre en el mismo orden entre consultas.
4. **Given** el catálogo vacío, **When** se consultan los canales, **Then** la respuesta es exitosa con una
   lista vacía, no un error.

---

### User Story 2 - Saber si cada proveedor puede enviar de verdad (Priority: P1)

Como operador, quiero ver junto a cada proveedor si está habilitado, deshabilitado (y por qué) o si el
despliegue no tiene cómo enviar por él, para detectar que un canal está fallando por configuración antes de
que me lo reporte un cliente.

**Why this priority**: Un proveedor preferente deshabilitado hace que todas las notificaciones de su canal
fallen; hoy eso solo se ve en el registro de arranque de cada réplica. Es la parte "y su estado" de la
historia (Q2).

**Independent Test**: Arrancar el servicio sin credenciales de los proveedores reales, consultar los canales
y confirmar que el simulado aparece habilitado y cada proveedor real deshabilitado con un motivo que nombra
la configuración ausente; nombrar en el catálogo un proveedor inexistente y confirmar que aparece "sin
adaptador".

**Acceptance Scenarios**:

1. **Given** un proveedor cuyas credenciales están completas, **When** se consulta, **Then** aparece
   habilitado y sin motivo.
2. **Given** un proveedor al que le falta una credencial o la tiene mal formada, **When** se consulta,
   **Then** aparece deshabilitado con el mismo motivo que su adaptador registró al arrancar, que nombra la
   configuración y no contiene su valor.
3. **Given** el catálogo nombra un proveedor para el que el despliegue no tiene adaptador, **When** se
   consulta, **Then** aparece con estado "sin adaptador" y un motivo que lo dice.
4. **Given** un proveedor deshabilitado en la posición 1 de un canal, **When** se despacha una notificación
   de ese canal, **Then** el despacho la rechaza por proveedor deshabilitado — el estado mostrado es el que
   el despacho encuentra.

---

### User Story 3 - Ver cada proveedor y dónde se usa (Priority: P2)

Como operador, quiero consultar los proveedores con su estado y los canales en que aparece cada uno, para
saber qué pasa si un proveedor cae o se queda sin credenciales.

**Why this priority**: Es la vista inversa de la User Story 1; útil pero derivable de ella.

**Independent Test**: Con la configuración por defecto, consultar los proveedores y confirmar que simulated
aparece en EMAIL, PUSH y SMS en la posición 1, que brevo, fcm y twilio aparecen cada uno en su canal en la
posición 2, y que la lista está ordenada por identificador.

**Acceptance Scenarios**:

1. **Given** un proveedor usado por varios canales, **When** se consulta, **Then** aparece una sola vez con
   todos esos canales y su posición en cada uno.
2. **Given** un proveedor con adaptador que ningún canal usa, **When** se consulta, **Then** aparece con su
   estado y sin canales.
3. **Given** un proveedor nombrado por el catálogo sin adaptador, **When** se consulta, **Then** aparece con
   estado "sin adaptador" y los canales que lo nombran.

---

### User Story 4 - La consulta es global y no filtra nada sensible (Priority: P2)

Como responsable de la operación, quiero que la consulta del catálogo muestre el mismo catálogo a cualquier
tenant sin revelar credenciales, para exponerla en el panel sin abrir una fuga.

**Why this priority**: El catálogo es del despliegue (Q1) y el motivo de deshabilitación habla de
credenciales; ambos puntos deben quedar garantizados, no supuestos.

**Independent Test**: Consultar con dos identificadores de tenant distintos y confirmar respuestas
idénticas; configurar una credencial parcial con un valor reconocible y confirmar que el valor no aparece en
ninguna respuesta aunque el proveedor aparezca deshabilitado.

**Acceptance Scenarios**:

1. **Given** dos tenants distintos, **When** ambos consultan canales y proveedores, **Then** reciben
   respuestas idénticas y ninguna contiene el identificador de tenant.
2. **Given** un proveedor con una credencial presente y otra ausente, **When** se consulta, **Then** el
   motivo nombra la ausente y el valor de la presente no aparece en ninguna parte de la respuesta.
3. **Given** una consulta sin identificador de tenant, o con uno vacío, **When** llega, **Then** se rechaza
   como solicitud inválida.
4. **Given** cualquier número de consultas, **When** se completan, **Then** el catálogo guardado y el que
   usa el enrutamiento no cambian.

---

### Edge Cases

- **Catálogo vacío** (por ejemplo, la base no estuvo disponible al arrancar y aún no hubo un refresco
  exitoso): la consulta responde con listas vacías, igual que el enrutamiento rechaza todo canal. La
  consulta de proveedores sigue mostrando los adaptadores del despliegue, sin canales.
- **Base no disponible después de haber cargado el catálogo**: la consulta sigue respondiendo con la última
  vista cargada, igual que el enrutamiento; no devuelve error.
- **Canal guardado sin proveedores**: no es enrutable y no aparece (Q4).
- **Canal guardado en minúsculas**: aparece con su identificador en mayúsculas, que es como lo resuelve el
  enrutamiento.
- **El mismo proveedor dos veces en un canal**: aparece dos veces, en sus dos posiciones, tal como está
  guardado; la consulta no corrige el catálogo.
- **Forma de contenido vacía o solo espacios**: se muestra como ausente.
- **Proveedores en posición 2 o posterior**: se listan en su orden, pero hoy el despacho usa solo el de la
  posición 1; no hay conmutación automática al siguiente. La descripción del contrato lo dice
  explícitamente para que el panel no sugiera lo contrario.
- **Réplicas con configuración distinta**: el estado de habilitación se calcula en cada réplica al
  arrancar; la respuesta refleja la réplica que atiende. En un despliegue normal todas comparten
  configuración y coinciden.
- **Cambio en el catálogo entre dos consultas**: la segunda lo refleja solo después del siguiente refresco
  del enrutamiento (Q4).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El componente MUST permitir consultar la lista de canales del catálogo. Cada canal incluye su
  identificador, su forma de contenido (o ausente) y sus proveedores en orden de preferencia, cada uno con
  su posición (1 = preferente).
- **FR-002**: Cada proveedor mostrado MUST llevar un estado: habilitado, deshabilitado o sin adaptador.
- **FR-003**: Un proveedor deshabilitado MUST incluir el motivo que declara su adaptador. El motivo nombra
  la configuración ausente, ambigua o mal formada y nunca contiene su valor.
- **FR-004**: Un proveedor sin adaptador MUST incluir un motivo fijo que indique que el despliegue no tiene
  adaptador para ese identificador. Un proveedor habilitado no lleva motivo.
- **FR-005**: El componente MUST permitir consultar la lista de proveedores: la unión de los que tienen
  adaptador en el despliegue y los que nombra el catálogo (Q3). Cada uno incluye su estado, su motivo
  cuando corresponda y los canales en que aparece con su posición en cada uno (lista vacía si ninguno).
- **FR-006**: Ambas listas MUST tener orden determinista: canales por identificador y proveedores por
  identificador, en orden alfabético; dentro de un canal, por posición de preferencia; los canales de un
  proveedor, por identificador de canal.
- **FR-007**: La consulta MUST leer la misma vista del catálogo que usa el enrutamiento (Q4). Un cambio
  guardado aparece en la consulta en el refresco en que el enrutamiento empieza a usarlo.
- **FR-008**: La consulta MUST exigir el identificador de tenant del solicitante y rechazarla como
  solicitud inválida si falta o está vacío. La respuesta MUST ser idéntica para todos los tenants y no
  contener el identificador ni ningún dato de tenant (Q1).
- **FR-009**: Ninguna respuesta MUST contener el valor de una credencial, un token ni ningún secreto; solo
  los nombres de la configuración.
- **FR-010**: La consulta MUST ser de solo lectura: no modifica el catálogo guardado ni la vista del
  enrutamiento ni el estado de ningún proveedor.
- **FR-011**: Si el catálogo está vacío o la base no está disponible, la consulta MUST responder con éxito
  usando la última vista cargada (vacía si nunca se cargó), nunca con error.
- **FR-012**: Ambas operaciones MUST quedar descritas en el contrato público antes de implementarse,
  incluyendo que hoy el despacho usa solo el proveedor en la posición 1.
- **FR-013**: El estado mostrado para un proveedor MUST ser el que encuentra el despacho: un proveedor
  mostrado como habilitado no es rechazado por deshabilitado ni por falta de adaptador, y viceversa.

### Key Entities *(include if feature involves data)*

- **Vista de canal**: identificador del canal, forma de contenido (opcional) y lista ordenada de
  proveedores con su posición y estado. Derivada del catálogo que usa el enrutamiento; no es un dato nuevo.
- **Vista de proveedor**: identificador del proveedor, estado, motivo (opcional) y lista de canales en que
  aparece con su posición. Derivada del catálogo y de los adaptadores del despliegue.
- **Estado de proveedor**: habilitado, deshabilitado o sin adaptador. Es una lectura del estado que ya
  existe en cada adaptador y en el registro de adaptadores, no un estado nuevo persistido.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El operador responde "¿por qué proveedor sale hoy una notificación del canal X y puede
  enviar?" con una sola consulta, sin acceso a la base ni a los registros: verificado de punta a punta con
  el catálogo sembrado por la configuración por defecto.
- **SC-002**: El 100 % de los proveedores nombrados por el catálogo aparece con un estado, y en cero casos
  un proveedor mostrado como habilitado es rechazado por el despacho como deshabilitado o sin adaptador,
  ni uno mostrado como deshabilitado o sin adaptador es aceptado por él (verificado, para un proveedor de
  cada estado, contra los mismos componentes que usa el despacho en el servicio en ejecución).
- **SC-003**: Dos tenants distintos reciben respuestas idénticas en ambas consultas, y ninguna respuesta
  contiene el identificador de tenant del solicitante.
- **SC-004**: Cero valores de credencial en las respuestas: con una credencial parcial de valor reconocible
  configurada, el valor no aparece en ninguna de las dos respuestas, mientras el motivo de
  deshabilitación sí aparece (control positivo).
- **SC-005**: Un cambio guardado en el catálogo aparece en la consulta en menos de 5 segundos cuando el
  refresco del enrutamiento está configurado cada segundo, y no aparece antes de que el enrutamiento lo
  use.

## Out of Scope

- Registrar, modificar o eliminar canales y proveedores (HU2-044, HU2-045).
- Catálogo por tenant, o cualquier dato de catálogo que dependa del tenant (Q1).
- Un estado propio del canal (habilitado/deshabilitado); un canal existe o no.
- Conmutación automática al siguiente proveedor, cortacircuitos o salud en vivo del proveedor (HU2-048):
  el estado mostrado es de configuración, no de disponibilidad del tercero en este momento.
- Consultar un canal o un proveedor individual por su identificador.
- Paginación: el catálogo tiene pocas decenas de entradas.
- Interpretar o validar la forma de contenido: se muestra tal como está guardada.
- La pantalla del panel de administración (otro repositorio).
- Restringir la consulta a administradores: depende del componente de seguridad.

## Assumptions

- El operador es un usuario del panel de administración; hasta que se integre el componente de seguridad,
  el identificador de tenant es el sustituto provisional de su identidad (Q1).
- Todas las réplicas de un despliegue comparten configuración, así que coinciden en el estado de
  habilitación de cada proveedor.
- El catálogo tiene pocas decenas de canales y proveedores; una respuesta completa, sin paginar, es
  adecuada.
- Los motivos de deshabilitación que declaran los adaptadores actuales ya son textos fijos que nombran la
  configuración y nunca incluyen su valor; esta historia los expone tal cual.
- La siembra y el refresco del catálogo no cambian.

## Risks

- **Configuración del despliegue visible para cualquier solicitante**: hasta que exista autenticación,
  quien envíe cualquier identificador de tenant ve los proveedores, su estado y los nombres de la
  configuración ausente (nunca sus valores). Es la misma exposición que ya tiene el resto de la API bajo el
  identificador provisional. Dueño: andrualv. Fecha de revisión, junto con la integración del componente
  de seguridad: 2026-12-31.
- **Estado de configuración, no de salud**: un proveedor habilitado puede estar caído o sin cuota; la
  consulta no lo refleja hasta que exista la historia de cortacircuitos (HU2-048). Dueño: andrualv. Fecha
  de revisión: 2026-12-31.
- **Réplicas con configuración distinta**: dos consultas seguidas pueden mostrar estados distintos si las
  atienden réplicas configuradas de forma diferente. Riesgo aceptado: es un error de despliegue que la
  consulta ayuda a detectar. Dueño: andrualv. Fecha de revisión: 2026-12-31.
