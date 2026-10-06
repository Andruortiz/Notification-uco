# Feature Specification: Sincronizar configuración transversal de Parámetros (HU2-073)

**Feature Branch**: `feature/HU2-073-sincronizar-parametros`

**Created**: 2026-10-04

**Status**: Draft

**Input**: User description: "HU2-073 — Como componente, quiero operar con la última configuración
conocida si el Componente de Parámetros no está disponible."

Trazabilidad: CU-11 (Sincronizar configuración transversal), RF-24 (actualizar la configuración
operativa al recibir un cambio publicado por el Componente de Parámetros) y SUP-02 (mecanismo de
publicación aún sin cerrar). Épica F, prioridad Deseable, tamaño L. Principios de la constitución
involucrados: I (hexagonal), VII (sin atajos), IX (observabilidad) y la restricción de configuración
frente a secretos.

Contexto: hoy los valores operativos del servicio (reintentos de despacho, tiempos de espera de los
proveedores, intervalo del reencolado) se fijan al arrancar desde el archivo de configuración y solo
cambian reiniciando. El Componente de Parámetros, a cargo de otro equipo, será el sistema de registro
de la configuración transversal, pero su contrato todavía no existe. Esta historia define cómo el
servicio declara qué parámetros pueden gestionarse, cómo adopta un cambio publicado sin reiniciar y
cómo se comporta cuando Parámetros no responde, sin acoplarse a un mecanismo de transporte concreto.

## Clarifications

### Session 2026-10-05

- Q: ¿Quién asigna el número de versión de un cambio de configuración, el Componente de Parámetros o este servicio? -> A: Parámetros asigna un entero creciente a cada cambio y el servicio ignora cualquiera con versión menor o igual a la vigente; un retroceso de valores se publica como una versión nueva con los valores anteriores.
- Q: ¿Se expone el registro de parámetros gestionables (y la versión de configuración en uso) por un endpoint HTTP de solo lectura, o queda solo para uso interno? -> A: Se expone por un endpoint de solo lectura con rol mínimo `ADMINISTRADOR`, definido primero en `api-notificaciones.yaml`; devuelve los descriptores y la versión y el origen de la configuración en uso.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Seguir operando sin Parámetros con la última configuración conocida (Priority: P1)

Un operador reinicia o escala una réplica del servicio mientras el Componente de Parámetros no está
disponible. La réplica arranca y despacha notificaciones con la última configuración válida que conoció
(o, si nunca la conoció, con los valores por defecto del archivo de configuración), sin degradar el
envío ni impedir el arranque.

**Why this priority**: Es la promesa de la historia. La indisponibilidad de Parámetros no puede
convertirse en indisponibilidad del servicio de notificaciones (Principio VIII).

**Independent Test**: Con un proveedor de configuración simulado que siempre falla, arrancar el
servicio, enviar una notificación y comprobar que se despacha con los valores vigentes; repetir tras
haber persistido una configuración previa y comprobar que se usa esa y no los valores por defecto.

**Acceptance Scenarios**:

1. **Given** una configuración válida previamente adoptada y persistida, **When** el servicio arranca y
   Parámetros no responde, **Then** opera con esa última configuración conocida y queda listo para
   recibir tráfico.
2. **Given** que nunca se obtuvo configuración de Parámetros, **When** el servicio arranca y Parámetros
   no responde, **Then** opera con los valores por defecto del archivo de configuración.
3. **Given** el servicio operando con configuración vigente, **When** Parámetros deja de estar
   disponible, **Then** el servicio sigue despachando sin interrupción y registra la indisponibilidad.
4. **Given** una indisponibilidad previa, **When** Parámetros vuelve a responder, **Then** el servicio
   retoma la sincronización sin reiniciar.

---

### User Story 2 - Adoptar un cambio publicado sin reiniciar (Priority: P1)

Cuando el Componente de Parámetros publica un cambio de un parámetro gestionable (por ejemplo, el número
máximo de intentos de despacho o el tiempo de espera de un proveedor), el servicio lo adopta en caliente.
El nuevo valor rige a partir de la siguiente operación que lo consulte; las operaciones ya iniciadas
terminan con el valor con el que comenzaron.

**Why this priority**: Es el objetivo funcional de RF-24; sin adopción en caliente, el componente de
Parámetros no aporta valor sobre el archivo de configuración.

**Independent Test**: Publicar un cambio de intentos máximos de despacho y comprobar que la siguiente
notificación fallida se reintenta según el nuevo valor sin haber reiniciado el servicio, y que una
operación en curso al momento del cambio no cambia de valor a mitad de ejecución.

**Acceptance Scenarios**:

1. **Given** un valor vigente de intentos máximos de 3, **When** Parámetros publica 5, **Then** la
   siguiente notificación despachada admite hasta 5 intentos.
2. **Given** una operación en curso, **When** se adopta un cambio, **Then** esa operación completa con
   el valor que leyó al iniciar.
3. **Given** un cambio de tiempo de espera de un proveedor, **When** se adopta, **Then** las siguientes
   llamadas a ese proveedor usan el nuevo tiempo de espera.
4. **Given** un cambio del intervalo de reencolado, **When** se adopta, **Then** el siguiente ciclo del
   reencolador usa el nuevo intervalo.
5. **Given** un cambio sobre un parámetro marcado como que requiere reinicio, **When** se recibe,
   **Then** se acepta y se registra como pendiente de reinicio, sin alterar el valor vigente.

---

### User Story 3 - Rechazar configuración inválida conservando la vigente (Priority: P1)

Un cambio publicado por error (un valor fuera de rango, de tipo equivocado o que rompe una regla entre
parámetros) no puede dejar al servicio en un estado inconsistente. El servicio valida el cambio completo
antes de aplicarlo; si algo falla, lo rechaza entero, conserva la configuración vigente y deja un
registro diagnosticable.

**Why this priority**: Aplicar configuración parcialmente válida puede causar fallos silenciosos en el
despacho; la validación previa es lo que hace seguro el cambio en caliente.

**Independent Test**: Publicar un cambio que mezcla un valor válido con uno inválido y comprobar que
ninguno de los dos se aplica, que la versión vigente no cambia y que existe un registro del rechazo con
el motivo y el identificador de correlación.

**Acceptance Scenarios**:

1. **Given** un cambio con un valor fuera del rango declarado, **When** se recibe, **Then** se rechaza
   completo y la configuración vigente no cambia.
2. **Given** un cambio que deja un tiempo de espera de proveedor mayor o igual al intervalo de
   reencolado, **When** se recibe, **Then** se rechaza por violar la regla entre parámetros.
3. **Given** un cambio que deja una ventana de barrido de adjuntos menor o igual a la espera de análisis
   multiplicada por sus intentos, **When** se recibe, **Then** se rechaza.
4. **Given** un cambio que deshabilitaría todos los proveedores de un canal, **When** se recibe,
   **Then** se rechaza.
5. **Given** un cambio que fija un límite de contenido de un canal por encima de lo que acepta su
   proveedor, **When** se recibe, **Then** se rechaza.
6. **Given** un cambio con una clave desconocida, **When** se recibe, **Then** se rechaza completo.

---

### User Story 4 - Declarar al Componente de Parámetros qué puede gestionar (Priority: P2)

El equipo del Componente de Parámetros necesita saber qué parámetros del servicio de notificaciones son
gestionables y bajo qué reglas. El servicio mantiene un registro de descriptores de parámetros que
declara, para cada uno, su clave, tipo, valor por defecto, rango permitido, ámbito (global, por canal o
por proveedor) y si se adopta en caliente o requiere reinicio, y lo expone para consulta.

**Why this priority**: Es el insumo para cerrar el contrato con el otro equipo (SUP-02) y evita que
Parámetros publique claves que el servicio no entiende. Puede entregarse después de que la adopción
funcione, pero la validación de las historias anteriores depende del mismo registro.

**Independent Test**: Consultar el registro y comprobar que contiene exactamente los parámetros
gestionables iniciales con sus metadatos completos, y que no contiene secretos, topología ni
direcciones base.

**Acceptance Scenarios**:

1. **Given** el servicio en ejecución, **When** se consulta el registro de parámetros, **Then** se
   devuelve cada parámetro gestionable con clave, tipo, valor por defecto, rango, ámbito y modo de
   adopción.
2. **Given** un parámetro de ámbito por proveedor, **When** se consulta, **Then** el registro indica el
   ámbito y para qué proveedores aplica.
3. **Given** el registro, **When** se revisa su contenido, **Then** no incluye credenciales ni claves
   de acceso.

---

### User Story 5 - Saber qué configuración está usando cada réplica (Priority: P2)

Un operador necesita confirmar qué versión de la configuración está usando una réplica y qué cambios se
aplicaron o rechazaron, para diagnosticar comportamientos distintos entre réplicas o tras un cambio.

**Why this priority**: Con adopción en caliente, sin esta visibilidad un cambio es una variable oculta
(Principio IX).

**Independent Test**: Aplicar un cambio válido y otro inválido y comprobar que la versión en uso se
expone y que cada evento queda registrado con su resultado y su identificador de correlación.

**Acceptance Scenarios**:

1. **Given** una configuración vigente, **When** se consulta el estado operativo, **Then** se expone su
   versión y su origen (Parámetros, última conocida o valores por defecto).
2. **Given** un cambio aplicado, **When** termina la adopción, **Then** se registra un evento con la
   versión anterior, la nueva y las claves modificadas.
3. **Given** un cambio rechazado, **When** termina la validación, **Then** se registra un evento con el
   motivo, sin incluir valores sensibles.

---

### Edge Cases

- Parámetros publica un cambio mientras otro se está validando: se procesan en orden y cada uno parte de
  la versión vigente en ese momento; nunca se aplica un estado intermedio.
- Parámetros publica una versión menor que la vigente (reordenamiento o reintento): se ignora y se
  registra.
- Parámetros publica el mismo cambio dos veces (misma versión): la segunda vez se ignora y no produce una
  nueva versión.
- Parámetros necesita revertir un valor: publica una versión nueva, mayor que la vigente, con los valores
  anteriores; nunca reutiliza ni reduce un número de versión.
- La última configuración conocida persistida está corrupta o ya no cumple las reglas actuales (por
  ejemplo, tras una actualización del servicio): se descarta, se registra y se usan los valores por
  defecto.
- El almacén de la última configuración conocida no está disponible al arrancar: el servicio arranca con
  los valores por defecto y lo registra, sin impedir el arranque ni el despacho.
- Varias réplicas leen versiones distintas durante una ventana breve: es aceptable y transitorio; cada
  réplica expone la versión que usa.
- Una operación consulta el mismo parámetro varias veces durante su ejecución: usa un único valor
  coherente, no puede ver dos versiones distintas.
- El valor por defecto del archivo de configuración incumple una regla entre parámetros: el arranque
  falla con un mensaje claro, en lugar de operar con una configuración inconsistente.
- Parámetros publica una clave que existe pero cuyo ámbito no corresponde (por ejemplo, un proveedor no
  configurado): se rechaza.

## Requirements *(mandatory)*

### Functional Requirements

**Registro de parámetros**

- **FR-001**: El sistema MUST mantener un registro de descriptores de parámetros gestionables, cada uno
  con clave, tipo, valor por defecto, rango permitido, ámbito (global, por canal o por proveedor) y modo
  de adopción (en caliente o con reinicio).
- **FR-002**: El sistema MUST exponer el registro de descriptores mediante un endpoint HTTP de solo lectura
  con rol mínimo `ADMINISTRADOR`, definido primero en el contrato (`api-notificaciones.yaml`), para su
  consulta por el Componente de Parámetros y por administradores. La respuesta MUST NOT incluir secretos ni
  valores de credenciales.
- **FR-003**: El registro MUST contener inicialmente solo: intentos máximos de despacho, tiempo de espera
  y tiempo de conexión de cada proveedor, e intervalo del reencolador.
- **FR-004**: El registro MUST NOT incluir secretos ni credenciales, topología de colas y exchanges, ni
  direcciones base de proveedores; esos valores siguen siendo configuración de arranque.

**Configuración vigente**

- **FR-005**: El sistema MUST representar la configuración vigente como una instantánea inmutable, con
  versión, que se reemplaza de forma atómica; ninguna operación puede observar una mezcla de dos
  versiones.
- **FR-006**: El valor de cada parámetro MUST resolverse con esta precedencia: valor publicado por
  Parámetros, luego última configuración conocida persistida, luego valor por defecto del archivo de
  configuración.
- **FR-007**: El sistema MUST persistir la última configuración válida adoptada para que sobreviva a un
  reinicio.
- **FR-008**: Si Parámetros no está disponible, el sistema MUST seguir operando con la configuración
  vigente y MUST NOT impedir el arranque ni el despacho por esa causa.
- **FR-009**: Cuando Parámetros vuelve a estar disponible, el sistema MUST reanudar la sincronización sin
  reinicio.

**Adopción de cambios**

- **FR-010**: El sistema MUST aceptar un cambio de configuración publicado por Parámetros y adoptarlo sin
  reiniciar, de modo que rija desde la siguiente operación que consulte el parámetro.
- **FR-011**: Una operación en curso MUST completarse con los valores que leyó al iniciar; un cambio no
  la altera a mitad de ejecución.
- **FR-012**: Los cambios sobre parámetros que requieren reinicio MUST aceptarse y registrarse como
  pendientes sin alterar el valor vigente.
- **FR-013**: La versión de un cambio MUST ser un entero creciente asignado por el Componente de Parámetros;
  el servicio MUST NOT inventar ni reasignar versiones. Un cambio con versión menor o igual a la vigente MUST
  ignorarse y registrarse.
- **FR-014**: Los parámetros gestionables iniciales MUST leerse de la configuración vigente en cada uso,
  no una sola vez al arrancar: intentos máximos de despacho, tiempos de espera y de conexión de cada
  proveedor, e intervalo del reencolador.

**Validación**

- **FR-015**: El sistema MUST validar el cambio completo antes de aplicarlo y, si cualquier elemento es
  inválido, rechazarlo entero conservando la configuración vigente.
- **FR-016**: La validación MUST comprobar clave conocida, tipo, rango declarado y ámbito aplicable.
- **FR-017**: La validación MUST comprobar, sobre la configuración resultante completa, estas reglas
  entre parámetros: (a) el tiempo de espera de cada proveedor es menor que el intervalo del
  reencolador; (b) la ventana de barrido de adjuntos es mayor que la espera de análisis multiplicada por
  sus intentos máximos; (c) cada canal conserva al menos un proveedor habilitado; (d) los límites de
  contenido de un canal no superan lo que acepta su proveedor.
- **FR-018**: Si la última configuración conocida persistida no supera la validación vigente, el sistema
  MUST descartarla, registrarlo y usar los valores por defecto.
- **FR-019**: Si los valores por defecto incumplen alguna regla numérica de coherencia entre parámetros, el
  sistema MUST fallar el arranque con un mensaje que identifique la regla. La regla (c) (canal sin
  proveedor habilitado) no impide el arranque: se registra como advertencia, porque un proveedor sin
  credenciales es un estado válido; los cambios publicados se siguen rechazando por todas las reglas.

**Observabilidad**

- **FR-020**: El sistema MUST exponer, en el mismo endpoint de solo lectura de FR-002, la versión de la
  configuración en uso y su origen (Parámetros, última conocida o valores por defecto), además de
  registrarlos en el log y en una métrica.
- **FR-021**: El sistema MUST registrar un evento por cada cambio aplicado (versión anterior, versión
  nueva, claves modificadas) y por cada cambio rechazado (motivo), ambos con identificador de
  correlación.
- **FR-022**: El sistema MUST registrar la indisponibilidad y la recuperación de Parámetros.
- **FR-023**: Los registros y respuestas de esta funcionalidad MUST NOT contener secretos ni credenciales.

**Arquitectura**

- **FR-024**: El núcleo MUST depender de la configuración únicamente a través de puertos propios: uno de
  entrada para recibir cambios y uno de salida para obtener el estado completo desde Parámetros; el
  mecanismo de transporte de SUP-02 queda aislado en un adaptador y puede cambiarse sin modificar el
  núcleo.
- **FR-025**: Mientras el contrato de SUP-02 no esté cerrado, el sistema MUST incluir un adaptador de
  consulta periódica sustituible y MUST operar correctamente si ningún adaptador de Parámetros está
  configurado, usando valores por defecto.

### Key Entities *(include if feature involves data)*

- **Descriptor de parámetro**: declara un parámetro gestionable: clave, tipo, valor por defecto, rango,
  ámbito y modo de adopción. Es lo que el servicio expone a Parámetros.
- **Instantánea de configuración**: conjunto inmutable y versionado de los valores vigentes de todos los
  parámetros gestionables, con su origen y fecha de adopción.
- **Cambio de configuración**: conjunto de valores publicados por Parámetros con una versión entera creciente
  asignada por Parámetros; se acepta o se rechaza como un todo.
- **Última configuración conocida**: copia persistida de la última instantánea válida adoptada.
- **Evento de configuración**: registro de un cambio aplicado, rechazado o ignorado, con versiones,
  claves afectadas, motivo e identificador de correlación.
- **Regla entre parámetros**: condición que debe cumplir la configuración resultante completa para ser
  aceptada.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con Parámetros no disponible, el servicio arranca y despacha notificaciones sin fallos
  atribuibles a la configuración en el 100 % de los arranques de prueba, usando la última configuración
  conocida cuando existe.
- **SC-002**: Un cambio válido publicado por Parámetros rige para las operaciones nuevas en menos de un
  intervalo de sincronización completo, sin reiniciar el servicio.
- **SC-003**: El 100 % de los cambios inválidos probados (rango, tipo, clave desconocida y las cuatro
  reglas entre parámetros) se rechazan completos y dejan la versión vigente sin cambios.
- **SC-004**: Ninguna operación en curso cambia de valor de un parámetro durante su ejecución al adoptar
  un cambio, verificado con cambios concurrentes a operaciones activas.
- **SC-005**: Un operador identifica la versión de configuración en uso por una réplica y el último
  cambio aplicado o rechazado en menos de un minuto, sin acceso al código.
- **SC-006**: El registro de parámetros expuesto coincide con exactamente los cuatro grupos de
  parámetros gestionables iniciales y no contiene ningún secreto, verificado por una prueba automática.
- **SC-007**: El tiempo de arranque del servicio con Parámetros no disponible no supera el límite de 30
  segundos de la constitución.

## Assumptions

- El contrato del Componente de Parámetros (SUP-02) no está publicado; esta historia define el lado del
  servicio y deja el transporte detrás de un puerto. La elección definitiva entre consulta periódica y
  publicación por evento se decide cuando el otro equipo cierre el contrato; la consulta periódica es el
  adaptador por defecto, consistente con el refresco del catálogo.
- Los parámetros gestionables son de ámbito global, por canal o por proveedor; los ajustes por tenant
  quedan fuera.
- Los secretos y credenciales siguen inyectándose por variables de entorno o Key Vault; la topología de
  colas y las direcciones base requieren reinicio y no se gestionan aquí.
- Los parámetros nuevos (límites de tasa, cuotas, circuit breaker, retención) se tratarán en historias
  separadas, que añadirán sus descriptores al mismo registro.
- La configuración vigente es local por réplica (consistente con la restricción de caché de lecturas
  semi-estáticas); la última configuración conocida se persiste en MongoDB, que ya es dependencia del
  servicio.
- Se tolera una ventana breve en que réplicas distintas usen versiones distintas.
- Los intentos máximos de despacho se leen de la configuración vigente en cada mensaje. Eso depende de que el
  despacho use confirmación manual con un límite de intentos propio (entrega E4 de la spec 017), por lo que
  esta historia se implementa después de esa entrega.
- El mecanismo de transporte de SUP-02 sigue abierto; el adaptador de consulta periódica es la
  implementación inicial detrás del puerto de salida y su intervalo se decide al planificar.
- El endpoint de solo lectura del registro y de la versión en uso exige el rol `ADMINISTRADOR` y sigue el
  modelo de autenticación vigente del servicio; se define primero en `api-notificaciones.yaml`
  (Principio II).
- Las reglas entre parámetros se evalúan sobre la configuración efectiva completa, incluyendo valores
  que aún no son gestionables (por ejemplo, la ventana de barrido de adjuntos o los proveedores
  habilitados), que se leen de la configuración de arranque.
- Depende de que el catálogo de canales y proveedores (`ChannelCatalogPort`) y los adaptadores de
  proveedor ya existan, como en el estado actual.
