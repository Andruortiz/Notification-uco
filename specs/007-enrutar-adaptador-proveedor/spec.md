# Feature Specification: Enrutar cada notificación al adaptador de su proveedor por providerId

**Feature Branch**: `007-enrutar-adaptador-proveedor`

**Created**: 2026-09-21

**Status**: Draft

**Input**: User description: "Como componente, quiero elegir el adaptador de envío según el proveedor
preferente que devuelve el catálogo, para poder tener más de un proveedor por canal y que el proveedor
registrado sea el que de verdad envía."

## Clarifications

### Session 2026-09-21

Las tres preguntas siguientes se identificaron en la revisión de ambigüedad y quedaron **confirmadas
por el usuario** al aprobar el plan.

- Q: ¿Qué le pasa a una notificación cuyo proveedor preferente no corresponde a ningún proveedor
  disponible? → A: se trata como error de configuración, no de entrega: el despacho falla, no se
  registra intento, la notificación conserva su estado y el mensaje termina en la cola de mensajes
  muertos con la causa. Alternativa descartada: marcarla como fallida definitivamente, que la haría
  indistinguible de un rechazo permanente del proveedor. Afecta a FR-004, FR-005, FR-006 y a la User
  Story 2.
- Q: ¿Cuándo debe fallar el sistema si dos proveedores disponibles declaran el mismo `providerId`? → A:
  al arrancar, no al despachar — un arranque que falla es visible de inmediato, mientras que un fallo
  en el despacho aparecería solo cuando llegue una notificación de ese canal. Afecta a FR-009.
- Q: ¿El `providerId` de cada proveedor de envío es una constante que el propio proveedor declara, o
  un valor configurable por entorno? → A: una constante que el propio proveedor declara; el catálogo es
  el único punto de configuración. Un identificador configurable permitiría que el mismo despliegue
  cambiara de destino sin tocar el catálogo, que es justo la indirección que esta historia busca
  eliminar. Afecta a FR-001 y a las Assumptions.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El proveedor declarado en el catálogo es el que envía (Priority: P1)

Como responsable de la configuración del servicio, quiero que la notificación salga por el proveedor
que el catálogo declara como preferente para su canal, para que la configuración del catálogo tenga
efecto real sobre la entrega y no sea solo una anotación en el historial.

**Why this priority**: Es el núcleo de la historia. Hoy el catálogo declara un proveedor preferente y
el sistema envía siempre por el mismo mecanismo de envío, independientemente de lo declarado; el
identificador que queda registrado en el intento de entrega puede no corresponder a quien realmente
envió. Sin esto, tener más de un proveedor por canal es imposible.

**Independent Test**: Con dos proveedores de envío disponibles y distinguibles, declarar en el
catálogo a uno de ellos como preferente para un canal, aceptar una notificación de ese canal y
confirmar que el envío lo ejecutó ese proveedor y no el otro; repetir cambiando el preferente en el
catálogo y confirmar que el proveedor efectivo cambia en consecuencia.

**Acceptance Scenarios**:

1. **Given** dos proveedores de envío disponibles ("A" y "B") y un canal cuyo catálogo declara a "A"
   como preferente, **When** se despacha una notificación de ese canal, **Then** el envío lo ejecuta
   "A", "B" no recibe nada, y el intento de entrega queda registrado con el identificador "A".
2. **Given** la misma configuración, **When** el catálogo pasa a declarar a "B" como preferente para
   ese canal, **Then** las notificaciones siguientes de ese canal las envía "B" sin necesidad de
   modificar ni desplegar código nuevo.
3. **Given** un canal cuyo catálogo declara una lista de proveedores, **When** se despacha una
   notificación de ese canal, **Then** se usa el primero de la lista (el preferente) y ningún otro de
   la lista recibe la notificación en el mismo despacho.
4. **Given** el proveedor simulado y un proveedor adicional disponibles al mismo tiempo, **When** el
   catálogo declara al simulado como preferente, **Then** el flujo de punta a punta sigue funcionando
   exactamente como antes de esta historia.

---

### User Story 2 - Un proveedor sin adaptador no se pierde en silencio (Priority: P2)

Como responsable de la operación, quiero que una notificación cuyo proveedor preferente no existe
entre los proveedores disponibles deje un rastro explícito y distinguible de un fallo del proveedor,
para poder detectar el error de configuración y corregirlo sin haber perdido notificaciones.

**Why this priority**: Es la garantía de seguridad de la historia. Enrutar por identificador introduce
una forma nueva de fallar (identificador declarado que nadie atiende) que antes no existía; si ese
caso se tragara la notificación o se confundiera con "el proveedor rechazó el envío", el diagnóstico
sería imposible y se violaría la garantía de no perder notificaciones aceptadas.

**Independent Test**: Declarar en el catálogo un proveedor preferente que no corresponde a ningún
proveedor disponible, aceptar una notificación de ese canal y confirmar que (a) no se registra ningún
intento de entrega, (b) queda un rastro consultable que nombra el identificador no resuelto y (c) ese
rastro es distinguible del que deja un proveedor que rechaza un envío.

**Acceptance Scenarios**:

1. **Given** un canal cuyo catálogo declara un proveedor preferente sin ningún proveedor disponible
   que lo atienda, **When** se despacha una notificación de ese canal, **Then** el despacho falla de
   forma explícita, el fallo nombra el identificador de proveedor no resuelto y la notificación no
   queda marcada como entregada.
2. **Given** ese mismo escenario, **When** se inspecciona el historial de la notificación, **Then** no
   aparece ningún intento de entrega, a diferencia de un rechazo del proveedor, que sí registra un
   intento con su resultado.
3. **Given** un proveedor disponible que rechaza el envío de forma permanente, **When** se despacha
   una notificación hacia él, **Then** el comportamiento es el de hoy (intento registrado, estado
   terminal trazable) y NO se confunde con el caso de proveedor no resuelto.
4. **Given** una notificación cuyo despacho falló por proveedor no resuelto, **When** más tarde se
   incorpora el proveedor faltante y la notificación se vuelve a despachar, **Then** se entrega
   normalmente sin haber perdido la notificación.

---

### User Story 3 - Incorporar un proveedor nuevo sin tocar el núcleo (Priority: P3)

Como desarrollador del servicio, quiero poder incorporar un proveedor de envío nuevo agregando su
adaptador y declarándolo en el catálogo, sin modificar la lógica de despacho ni el núcleo, para que el
costo de sumar proveedores no crezca con cada uno.

**Why this priority**: Es la propiedad de extensibilidad que define el proyecto (ADR-0009). Aporta
valor a futuro más que en esta entrega, pero si el diseño de enrutamiento no la respeta, cada
proveedor nuevo obligaría a editar el despacho y la historia habría fallado su propósito.

**Independent Test**: Agregar un proveedor de envío adicional y declararlo en el catálogo; confirmar
que la notificación sale por él sin que se haya modificado ningún archivo del núcleo ni la lógica de
despacho.

**Acceptance Scenarios**:

1. **Given** el sistema en funcionamiento con un solo proveedor, **When** se incorpora un proveedor
   adicional y se lo declara preferente en el catálogo, **Then** las notificaciones salen por el nuevo
   proveedor sin haber modificado la lógica de despacho ni el núcleo.
2. **Given** dos proveedores incorporados, **When** el sistema arranca, **Then** ambos quedan
   disponibles simultáneamente y ninguno desplaza al otro.

---

### Edge Cases

- ¿Qué pasa si dos proveedores disponibles declaran el mismo identificador? El sistema debe fallar de
  forma explícita y temprana en lugar de elegir uno arbitrariamente: un enrutamiento ambiguo silencioso
  entregaría por un proveedor distinto al configurado sin que nadie lo note.
- ¿Qué pasa si no hay ningún proveedor disponible en el arranque? El sistema debe seguir arrancando; el
  fallo se manifiesta en el despacho, con la misma trazabilidad del caso de proveedor no resuelto.
- ¿Qué pasa si el catálogo declara varios proveedores para un canal y el preferente no está disponible
  pero el segundo sí? En esta historia NO se conmuta al segundo: se trata como proveedor no resuelto.
  Elegir el siguiente proveedor ante un fallo es una historia aparte (ver Out of Scope).
- ¿Qué pasa si el catálogo cambia el proveedor preferente mientras hay notificaciones en vuelo? Cada
  despacho resuelve su proveedor en el momento en que ocurre; una notificación ya enviada no se
  reenvía por el proveedor nuevo.
- ¿Qué pasa con notificaciones que fallaron por proveedor no resuelto y siguen pendientes cuando el
  proveedor se incorpora? Se despachan normalmente al reintentarse; el error de configuración no las
  marca como definitivamente fallidas.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE mantener un registro de los proveedores de envío disponibles, en el que
  cada proveedor se identifica con el mismo identificador (`providerId`) que usa el catálogo de canales
  para declarar los proveedores de un canal.
- **FR-002**: Al despachar una notificación, el sistema DEBE enviarla a través del proveedor preferente
  declarado por el catálogo para el canal y tenant de esa notificación, y no a través de un proveedor
  fijo decidido en tiempo de compilación o de cableado.
- **FR-003**: El identificador de proveedor que queda registrado en el intento de entrega DEBE ser el
  del proveedor que efectivamente ejecutó el envío.
- **FR-004**: Cuando el proveedor preferente declarado por el catálogo no corresponde a ningún
  proveedor disponible, el sistema NO DEBE enviar la notificación por ningún otro proveedor, NO DEBE
  darla por entregada y NO DEBE descartarla en silencio.
- **FR-005**: En ese caso el sistema DEBE producir un fallo de despacho explícito y trazable que
  identifique el `providerId` que no se pudo resolver, siguiendo el mismo camino de trazabilidad que ya
  tienen los fallos de despacho (reintentos del consumidor y cola de mensajes muertos con la causa).
- **FR-006**: El fallo por proveedor no resuelto DEBE ser distinguible del fallo de un proveedor: un
  fallo del proveedor registra un intento de entrega con su resultado (recuperable o permanente) y
  lleva a la notificación a un estado acorde; un proveedor no resuelto NO registra intento de entrega y
  no altera el estado de la notificación.
- **FR-007**: Incorporar un proveedor de envío nuevo DEBE requerir únicamente aportar su adaptador y
  declararlo en el catálogo; NO DEBE exigir modificar el núcleo ni la lógica de despacho (ADR-0009).
- **FR-008**: El proveedor simulado DEBE seguir disponible y operativo cuando existan otros proveedores
  registrados, sin que uno desplace al otro, y DEBE seguir siendo el que envía cuando el catálogo lo
  declare preferente.
- **FR-009**: Si dos proveedores disponibles declaran el mismo `providerId`, el sistema DEBE fallar al
  arrancar, de forma explícita y nombrando el identificador duplicado, en lugar de arrancar y resolver
  la ambigüedad eligiendo uno de manera arbitraria en cada despacho.
- **FR-010**: El sistema NO DEBE, en esta historia, conmutar al siguiente proveedor de la lista del
  catálogo ante un fallo del preferente; el comportamiento ante un fallo del proveedor es exactamente
  el que existe hoy.

### Key Entities *(include if feature involves data)*

- **Proveedor de envío**: capacidad de entregar una notificación a través de un canal concreto. Se
  identifica por un `providerId` estable, el mismo valor con el que el catálogo lo declara. Esta
  historia no persiste entidades nuevas.
- **Ruta de canal**: entidad ya existente. Declara, para un canal, la lista ordenada de `providerId`
  admitidos; el primero es el preferente. Esta historia no cambia su forma, solo hace que su proveedor
  preferente determine quién envía.
- **Intento de entrega**: entidad ya existente. Conserva el `providerId` usado; esta historia hace que
  ese valor corresponda al proveedor que realmente ejecutó el envío.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con dos o más proveedores disponibles, el 100 % de las notificaciones despachadas salen
  por el proveedor preferente declarado en el catálogo para su canal, y ningún otro proveedor recibe
  esas notificaciones.
- **SC-002**: Cambiar el proveedor preferente de un canal en el catálogo cambia el proveedor que
  efectivamente envía, sin desplegar ni modificar código y sin reiniciar el servicio (dentro del
  intervalo de refresco del catálogo ya existente).
- **SC-003**: El identificador de proveedor registrado en el intento de entrega coincide con el
  proveedor que ejecutó el envío en el 100 % de los despachos.
- **SC-004**: Cero notificaciones perdidas o dadas por entregadas cuando el proveedor preferente no
  tiene proveedor disponible; el 100 % de esos casos deja un rastro consultable que nombra el
  `providerId` no resuelto.
- **SC-005**: Incorporar un proveedor de envío adicional no requiere modificar ningún archivo del
  núcleo ni de la lógica de despacho (0 archivos de `core` modificados en el cambio que lo incorpora).
- **SC-006**: El flujo de punta a punta con el proveedor simulado sigue entregando notificaciones
  exactamente igual que antes de esta historia (sin regresión observable de estado final ni de
  historial de intentos).
- **SC-007**: Una configuración con dos proveedores que declaran el mismo identificador nunca llega a
  atender tráfico: el sistema no completa su arranque y el mensaje de fallo nombra el identificador
  duplicado.

## Out of Scope

- Conmutar al siguiente proveedor de la lista del catálogo cuando el preferente falla (failover). Es
  una historia aparte; esta solo resuelve un `providerId` al proveedor que le corresponde.
- Circuit breaker, límites de tasa por proveedor y cuotas por tenant.
- Incorporar un proveedor real concreto. Esta historia deja el mecanismo listo; el proveedor real llega
  en su propia historia.
- Cambiar el formato del catálogo, su modelo de datos o su mecanismo de refresco.
- Selección de proveedor por criterios distintos al orden declarado en el catálogo (costo, prioridad de
  la notificación, geografía).

## Assumptions

- El proveedor preferente es el primero de la lista de proveedores que el catálogo declara para el
  canal, tal como ya está definido hoy. Esta historia no cambia esa definición.
- Cada proveedor de envío declara su propio `providerId` de forma estable (constante del propio
  proveedor, no configuración de entorno — ver Clarifications); el catálogo lo referencia por ese
  valor. El proveedor simulado declara el identificador `simulated`, que es el valor que la
  configuración de canales ya siembra hoy para el canal `EMAIL`.
- El tratamiento del `providerId` sin proveedor disponible y el momento del fallo por identificadores
  duplicados están resueltos y confirmados por el usuario — ver Clarifications.
- Una notificación que queda pendiente tras un fallo por proveedor no resuelto será reintentada por el
  mecanismo de recuperación de pendientes ya existente. Eso implica que un error de configuración
  persistente genera intentos repetidos (y entradas repetidas en la cola de mensajes muertos) hasta que
  se corrija; se acepta como comportamiento conocido de esta historia porque conserva la notificación,
  y acotar ese reintento es un problema del mecanismo de recuperación, no del enrutamiento.
- La validación de que el catálogo solo declare proveedores existentes se verifica en el momento del
  despacho, no en el arranque. Una validación anticipada del catálogo completo es deseable pero
  requiere decidir qué hacer al arrancar con un catálogo inválido, lo que excede esta historia.
- El aislamiento por tenant sigue viniendo de la consulta al catálogo (la ruta se resuelve por canal y
  tenant); esta historia no introduce ni relaja ninguna regla de aislamiento.
