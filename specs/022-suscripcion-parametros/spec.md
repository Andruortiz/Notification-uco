# Feature Specification: Suscripción por evento al Componente de Parámetros (HU2-022)

**Feature Branch**: `feature/022-suscripcion-parametros`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "construyámoslo, después lo modificamos" -- un adaptador de entrada por evento
que se suscribe a los cambios publicados por el Componente de Parámetros, aunque el contrato (SUP-02) no esté
cerrado: se construye contra un contrato SUPUESTO, aislado, y se ajusta cuando el otro equipo lo defina.

Trazabilidad: CU-11 (Sincronizar configuración transversal), RF-24, SUP-02 (mecanismo de publicación sin
cerrar), continuación de la spec 019 (`specs/019-sincronizar-parametros`), cuya decisión D7 dejó la publicación
por evento fuera "por ahora" y registró la excepción del Principio VII con vencimiento 2026-11-15.
Principios involucrados: I (hexagonal), IV (E2E), VII (sin atajos), IX (observabilidad), y las restricciones de
ack manual con DLQ y de secretos fuera del repositorio.

Contexto: hoy el servicio se entera de un cambio de configuración solo por sondeo (`notification.parameters`,
cada 30 s por defecto) y solo si hay una dirección base de Parámetros configurada. Un cambio puede tardar hasta
un intervalo completo en regir. Esta historia añade un segundo transporte de entrada, por evento, que reutiliza
el mismo caso de uso de la 019 (validación, versión, atomicidad, persistencia de la última conocida,
eventos de log), sin tocar el núcleo de reglas y sin quitar el sondeo.

## Clarifications

### Session 2026-10-08

Las cinco decisiones abiertas quedaron CONFIRMADAS el 2026-10-08, cuando el usuario aprobó el plan que las
contiene, con la recomendación propuesta (ver Assumptions, S-E1 a S-E5).

- Q1: ¿El evento trae los valores o solo avisa y se consulta por HTTP? -> CONFIRMADA: trae los valores
  (`{version, values}`, misma forma que la respuesta HTTP) y se aplican directamente.
- Q2: ¿Mismo broker RabbitMQ del servicio o uno distinto de Parámetros? -> CONFIRMADA: el mismo broker
  que ya usa el servicio, con cola propia.
- Q3: ¿Mensaje ilegible u obsoleto: DLQ o descartar con evento? -> CONFIRMADA: ilegible a DLQ; bien formado
  pero rechazado por validación u obsoleto se confirma (ack) y queda como evento de log.
- Q4: ¿Qué pasa con el sondeo cuando la suscripción está activa? -> CONFIRMADA: se conserva igual, mismo
  intervalo, como reconciliación y respaldo.
- Q5: ¿El listener exige autenticación o firma del mensaje? -> CONFIRMADA: no en esta entrega; se apoya en el
  control de acceso del broker y se registra como excepción con dueño y fecha.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Adoptar un cambio publicado en segundos, sin esperar al sondeo (Priority: P1)

Cuando el Componente de Parámetros publica un cambio de configuración, el servicio lo recibe por su
suscripción y lo adopta de inmediato con las mismas garantías que un cambio obtenido por sondeo: se valida
completo, se aplica de forma atómica, se persiste como última conocida y deja un evento de log.

**Why this priority**: Es el valor de la historia; reduce la latencia de adopción de "hasta un intervalo de
sondeo" a segundos, que es lo que RF-24 pide al hablar de "recibir un cambio publicado".

**Independent Test**: Con el sondeo desactivado en la práctica (intervalo muy largo), publicar un mensaje
`{version, values}` en el exchange de Parámetros y comprobar que `GET /configuration` muestra la versión nueva
y el valor nuevo dentro del límite fijado en SC-001.

**Acceptance Scenarios**:

1. **Given** una versión vigente 3, **When** llega por la suscripción la versión 4 con un valor válido,
   **Then** la configuración vigente pasa a la versión 4 con origen Parámetros y se confirma el mensaje.
2. **Given** un cambio recibido por evento, **When** se adopta, **Then** se persiste como última conocida y se
   registra `CONFIG_APPLIED` con un identificador de correlación `param-...`.
3. **Given** un cambio de un parámetro que requiere reinicio, **When** llega por evento, **Then** se registra
   como pendiente de reinicio igual que por sondeo.

---

### User Story 2 - Un evento perdido no deja al servicio desactualizado (Priority: P1)

Si el broker pierde un mensaje, la suscripción estuvo caída o el servicio arrancó después de la publicación, el
sondeo HTTP existente sigue reconciliando el estado completo y el servicio converge a la versión correcta.

**Why this priority**: El contrato es supuesto y la entrega por evento no es garantía de estado; sin
reconciliación, un solo mensaje perdido deja a una réplica con configuración vieja de forma indefinida
(Principio VIII).

**Independent Test**: Publicar un cambio en la fuente HTTP sin publicarlo como evento y comprobar que el
sondeo lo adopta; publicar luego el mismo cambio como evento y comprobar que se ignora como obsoleto.

**Acceptance Scenarios**:

1. **Given** la suscripción activa y una fuente HTTP configurada, **When** el cambio solo está disponible por
   HTTP, **Then** el sondeo lo adopta como hoy.
2. **Given** que el sondeo ya adoptó la versión 5, **When** llega por evento la versión 5 o menor, **Then**
   se ignora, se confirma el mensaje y no se produce una versión nueva.
3. **Given** la suscripción activa y sin fuente HTTP configurada, **When** llega un cambio por evento,
   **Then** se adopta igualmente (el evento no depende de la fuente HTTP).

---

### User Story 3 - Mensajes inválidos sin reintentos infinitos ni pérdida silenciosa (Priority: P1)

Un mensaje ilegible, incompleto o con un cambio inválido no puede detener el consumo ni quedar reintentándose
sin fin. Cada caso termina en un destino determinista y queda trazado.

**Why this priority**: Es la restricción de ack manual y DLQ de la constitución aplicada a este consumidor, y
la protección contra un mensaje venenoso que bloquee las actualizaciones siguientes.

**Independent Test**: Publicar, en este orden, un mensaje no JSON, un mensaje sin `version`, un cambio con un
valor fuera de rango y un cambio válido; comprobar el destino de cada uno y que el válido se adopta después.

**Acceptance Scenarios**:

1. **Given** un mensaje que no es JSON o no trae `version` o `values`, **When** llega, **Then** se envía a la
   cola de mensajes muertos con el motivo, se confirma el original y se registra el error.
2. **Given** un cambio bien formado con un valor inválido, **When** llega, **Then** se rechaza completo, la
   versión vigente no cambia, se registra `CONFIG_REJECTED` con el motivo y se confirma el mensaje sin DLQ.
3. **Given** un cambio con versión menor o igual a la vigente, **When** llega, **Then** se registra
   `CONFIG_IGNORED` (una vez por versión y motivo) y se confirma el mensaje.
4. **Given** un fallo inesperado al procesar, **When** se agotan los intentos configurados, **Then** el mensaje
   va a la cola de mensajes muertos.
5. **Given** un mensaje inválido seguido de uno válido en la misma cola, **When** se procesan, **Then** el
   válido se adopta (el inválido no bloquea la cola).

---

### User Story 4 - Activar la suscripción solo por configuración y ajustarla al contrato definitivo (Priority: P2)

El equipo del servicio puede encender la suscripción indicando exchange, routing key y cola, y puede ajustar el
contrato cuando el equipo de Parámetros lo cierre tocando solo el adaptador y su configuración. Sin exchange
configurado, el servicio se comporta exactamente como hoy.

**Why this priority**: Garantiza que construir contra un contrato supuesto no se vuelva un atajo permanente
(Principio VII) ni afecte a quien no usa la suscripción.

**Independent Test**: Arrancar sin exchange configurado y comprobar que no existe cola, listener ni binding de
Parámetros; arrancar con exchange y comprobar que sí. Arrancar con exchange y sin cola o routing key y
comprobar que el arranque falla con un mensaje claro.

**Acceptance Scenarios**:

1. **Given** ningún exchange configurado, **When** arranca el servicio, **Then** no se declara topología de
   Parámetros ni se crea el listener, y el sondeo funciona como hoy.
2. **Given** un exchange configurado, **When** arranca, **Then** se declaran la cola propia, su DLQ y el binding
   por routing key, y el listener queda consumiendo con confirmación manual.
3. **Given** un exchange configurado sin routing key o sin cola, **When** arranca, **Then** el arranque falla
   identificando la propiedad faltante.
4. **Given** el contrato definitivo distinto del supuesto, **When** se ajusta, **Then** el cambio se limita al
   adaptador de entrada y a `notification.parameters.events.*`; los casos de uso de `core` no se modifican.

---

### Edge Cases

- El broker no está disponible al arrancar: el servicio arranca igual y el listener reintenta la conexión; el
  sondeo y la última conocida cubren la configuración (no se bloquea el arranque, 30 s).
- Dos mensajes llegan desordenados (versión 6 antes que 5): se adopta la 6 y la 5 se ignora como obsoleta.
- El mismo mensaje llega dos veces (redelivery): la segunda vez se ignora por versión; no hay efecto doble.
- Réplicas múltiples: cada réplica tiene el mismo comportamiento; las dos pueden recibir el evento si cada una
  tiene su cola, o una sola si comparten cola (ver S-E6); el sondeo converge las demás.
- Llegada de claves desconocidas o de otro servicio en `values`: se rechaza completo (FR-016 de la 019).
- La persistencia de la última conocida falla: el cambio sigue adoptado y el mensaje se confirma (mismo
  comportamiento que el sondeo en la 019).
- Cola y exchange ya existen con argumentos distintos a los declarados: el declarante falla y la excepción
  queda diagnosticada en el arranque con la propiedad a revisar.
- Un mensaje válido llega mientras otro cambio se aplica: se serializa como en la 019 (D10).

## Requirements *(mandatory)*

### Functional Requirements

**Suscripción**

- **FR-001**: El sistema MUST consumir, desde una cola propia atada al exchange de Parámetros, mensajes con
  la forma `{"version": <entero>, "values": {<clave>: <valor>}}`, la misma que devuelve la fuente HTTP.
- **FR-002**: El exchange, la routing key y la cola MUST ser configurables bajo `notification.parameters.events.*`
  sin valores por defecto; la suscripción MUST estar inactiva si no hay exchange configurado.
- **FR-003**: Con la suscripción inactiva, el servicio MUST comportarse exactamente como antes de esta
  historia: sin cola, listener ni binding adicionales.
- **FR-004**: Con exchange configurado sin routing key o sin cola, el arranque MUST fallar con un mensaje que
  identifique la propiedad.
- **FR-005**: El contrato del mensaje MUST estar aislado en un único adaptador de entrada y su configuración;
  ningún tipo de `core` MUST conocer el exchange, la cola ni el formato JSON del evento.
- **FR-006**: El listener MUST reutilizar los casos de uso de la 019 para validar y aplicar el cambio; MUST NOT
  duplicar reglas de versión, validación ni persistencia.

**Confirmación y errores**

- **FR-007**: El listener MUST usar confirmación manual y MUST confirmar un mensaje solo después de que su
  resultado quede decidido (aplicado, ignorado, rechazado o enviado a DLQ).
- **FR-008**: Un mensaje ilegible o sin `version` o `values` MUST enviarse a la cola de mensajes muertos con
  el motivo y MUST NOT reintentarse.
- **FR-009**: Un cambio bien formado rechazado por validación MUST confirmarse sin DLQ y registrarse como
  `CONFIG_REJECTED`; uno obsoleto MUST confirmarse y registrarse como `CONFIG_IGNORED`.
- **FR-010**: Un fallo inesperado al procesar MUST reintentarse hasta el máximo configurado y luego MUST ir a
  la cola de mensajes muertos con trazabilidad del motivo.
- **FR-011**: Un mensaje que no se pueda procesar MUST NOT bloquear el consumo de los siguientes.

**Convivencia con el sondeo**

- **FR-012**: El sondeo HTTP MUST conservarse sin cambios de comportamiento como reconciliación y respaldo.
- **FR-013**: La idempotencia MUST apoyarse en la versión: un cambio con versión menor o igual a la vigente no
  produce efecto, sin importar por qué transporte llegue.
- **FR-014**: Un cambio adoptado por evento MUST persistirse como última conocida con la misma condición de
  versión que el adoptado por sondeo.

**Observabilidad**

- **FR-015**: Cada mensaje procesado MUST registrar los eventos `CONFIG_APPLIED`, `CONFIG_REJECTED` o
  `CONFIG_IGNORED` con un identificador de correlación `param-...`, mediante `ConfigurationEventLogger`.
- **FR-016**: Los registros MUST NOT contener valores de parámetros de un cambio rechazado, credenciales ni el
  cuerpo completo del mensaje.
- **FR-017**: El log MUST distinguir el transporte de origen (`event` o `poll`) de cada cambio.

**Seguridad y configuración**

- **FR-018**: Las credenciales y la dirección del broker MUST seguir viniendo de las variables de entorno
  existentes del servicio; esta historia MUST NOT añadir valores literales de credenciales o URL al repositorio.
- **FR-019**: El origen del mensaje no se autentica en esta entrega; la limitación MUST registrarse como
  excepción con dueño y fecha (Principio VII).

### Key Entities *(include if feature involves data)*

- **Mensaje de cambio de configuración (supuesto)**: JSON con `version` entera creciente y `values`, publicado
  por Parámetros; equivale a un `ConfigurationChange` de la 019. No se persiste.
- **Suscripción de Parámetros**: cola durable propia del servicio, su cola de mensajes muertos y el binding al
  exchange de Parámetros; existe solo si hay exchange configurado.
- **Resultado de un cambio**: `APPLIED`, `PENDING_RESTART`, `IGNORED_STALE` o `REJECTED`, ya definido en la 019.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un cambio válido publicado por Parámetros rige en la instantánea vigente en 5 segundos o menos
  desde su publicación, con el sondeo configurado en un intervalo mayor que esa ventana (se prueba que lo
  adoptó el evento, no el sondeo).
- **SC-002**: En el 100 % de las pruebas con el evento perdido, el sondeo converge a la versión correcta en
  menos de un intervalo de sondeo más margen.
- **SC-003**: El 100 % de los mensajes ilegibles o incompletos probados termina en la cola de mensajes muertos
  y 0 mensajes quedan sin confirmar; el siguiente mensaje válido se adopta.
- **SC-004**: El 100 % de los cambios obsoletos o duplicados probados no crea una versión nueva ni altera la
  configuración vigente.
- **SC-005**: Sin exchange configurado, 0 colas, bindings o listeners de Parámetros existen, verificado por
  una prueba automática; el arranque mantiene el límite de 30 s.
- **SC-006**: Ningún registro de los casos probados contiene credenciales ni los valores de un cambio
  rechazado, verificado por una prueba automática.

## Assumptions

- **S-E0 (contrato supuesto)**: el contrato de SUP-02 no está publicado. Este documento propone uno para
  poder construir: cola propia del servicio atada a un exchange de Parámetros; mensaje JSON `{version, values}`
  igual al de la fuente HTTP provisional de la 019 (S-1). Es SUPUESTO y se ajusta cuando el otro equipo lo
  defina (contracts/parametros-evento-supuesto.md).
- **S-E1 (Q1)**: el evento trae los valores y se aplican directamente, sin consultar HTTP, porque así funciona
  sin fuente HTTP y alcanza el objetivo de latencia.
- **S-E2 (Q2)**: se usa el broker RabbitMQ que el servicio ya tiene configurado; un broker distinto de
  Parámetros queda fuera de alcance y se evalúa al cerrar el contrato.
- **S-E3 (Q3)**: ilegible a DLQ; rechazado por validación y obsoleto se confirman con evento de log.
- **S-E4 (Q4)**: el sondeo conserva su intervalo; ajustarlo es decisión operativa por la propiedad existente.
- **S-E5 (Q5)**: sin firma ni autenticación del mensaje; el control de acceso del broker (quién puede publicar
  en el exchange) es la barrera, y el cambio se valida igual que el de la fuente HTTP.
- **S-E6**: cada réplica declara la misma cola con nombre configurable; réplicas que comparten el nombre de
  cola compiten por el mensaje y el sondeo converge a las demás. Si el equipo de Parámetros prefiere difusión a
  todas las réplicas, la cola por réplica se ajusta con el contrato definitivo.
- El exchange se asume de tipo `topic`, durable, y se declara de forma idempotente por el servicio; si el
  equipo de Parámetros lo declara con otro tipo, el tipo se ajusta por configuración.
- Esta historia no cambia los descriptores, las reglas ni los rangos de la 019, ni el endpoint
  `GET /configuration`; no añade endpoints (Principio II no aplica).
- La configuración es global por réplica, sin aislamiento por tenant; no aplica prueba de dos tenants.
- Depende de la 019 fusionada en `develop` (ya lo está) y del RabbitMQ existente.
