# Feature Specification: Logs estructurados con identificador de correlación (HU2-056)

**Feature Branch**: `feature/HU2-056-logs-correlation-id`

**Created**: 2026-10-01

**Status**: Clarificado y plan aprobado por el usuario (2026-10-01)

**Input**: User description: "HU2-056 — Como operador, quiero trazar una solicitud de punta a punta
mediante logs estructurados con un identificador de correlación."

Trazabilidad: RNF-10 (registros estructurados con identificador de correlación) y Principio IX de la
constitución (observabilidad y trazabilidad). Épica E, Fase 8. Prioridad Imprescindible, tamaño M.

Contexto: el servicio no tenía infraestructura de logging propia cuando se registró la brecha de
RNF-10. Existe una implementación preliminar sin confirmar en la rama (identificador de correlación en
solicitudes REST, mensajes RabbitMQ y persistencia, y un patrón de log de texto con el identificador).
Esta especificación define el comportamiento esperado completo; la reconciliación con lo ya construido
se hace en `plan.md`.

## Clarifications

### Session 2026-10-01

Las cuatro decisiones de diseño dejadas abiertas fueron resueltas por el usuario:

- Q: ¿Formato de log? -> A: JSON de una línea con `logstash-logback-encoder` (dependencia solo en
  `infrastructure`).
- Q: ¿Propagación? -> A: se mantiene `X-Correlation-Id` y además se propaga `traceparent` para
  compatibilidad futura con OpenTelemetry. Alcance mínimo: aceptar y reenviar `traceparent` (REST de
  entrada, MDC, cabecera AMQP); sin Micrometer Tracing ni SDK de OpenTelemetry.
- Q: ¿Alcance de `LogSanitizer`? -> A: el destinatario se enmascara (no se omite); el contenido y las
  credenciales nunca se registran; los caracteres de control se neutralizan.
- Q: ¿Niveles? -> A: INFO para operaciones exitosas, WARN para rechazos 401/403, ERROR para fallos de
  sistema. Los fallos recuperables de proveedor se registran en WARN: supuesto del agente, no
  confirmado por el usuario.
- Excepciones del Principio VII (métricas de RNF-10, reenvío del id a proveedores, catálogo
  `ErrorCode`): dueño equipo de desarrollo del componente, fecha 2026-10-15.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reconstruir el recorrido de una solicitud con un solo identificador (Priority: P1)

Un operador recibe el reporte de una notificación que no llegó. Con un único identificador de
correlación busca en los logs y obtiene, en orden temporal, todas las entradas de la solicitud de
aceptación, la publicación a la cola, el consumo, el intento de envío al proveedor y el resultado
terminal, aunque hayan sido producidas por réplicas distintas del servicio.

**Why this priority**: Es la promesa de la historia y de RNF-10; sin esto los demás aspectos
(formato, sanitización) no cumplen su propósito.

**Independent Test**: Enviar una notificación por la API con un identificador de correlación conocido,
esperar el resultado terminal con el proveedor simulado y comprobar que todas las entradas de log del
flujo contienen ese identificador y que ninguna entrada del flujo carece de él.

**Acceptance Scenarios**:

1. **Given** una solicitud de envío con el identificador de correlación `abc-123`, **When** la
   notificación se acepta, se publica, se consume y se despacha, **Then** cada entrada de log de ese
   recorrido contiene `abc-123`.
2. **Given** una solicitud sin identificador de correlación, **When** el servicio la procesa,
   **Then** el servicio genera uno, lo devuelve en la respuesta y lo usa en todo el recorrido.
3. **Given** una solicitud con un identificador que no cumple el formato permitido (caracteres fuera
   del conjunto o longitud mayor a 64), **When** llega al servicio, **Then** se descarta y se genera
   uno nuevo; el valor original nunca se escribe en los logs.
4. **Given** una notificación aceptada con el identificador `abc-123`, **When** el consumo se
   reintenta o se redirige a la cola de mensajes fallidos, **Then** las entradas de los reintentos y
   del descarte conservan `abc-123`.

---

### User Story 2 - Logs legibles por máquina con contexto de negocio (Priority: P1)

Un operador usa un agregador de logs para filtrar por identificador de correlación, tenant o
notificación. Cada entrada se emite en un formato estructurado parseable por máquina y los logs ligados
al ciclo de vida de una notificación incluyen identificador de correlación, `tenantId` y
`notificationId` como campos separados.

**Why this priority**: La constitución prohíbe el texto plano libre y exige los tres identificadores en
todo log del ciclo de vida.

**Independent Test**: Capturar la salida de log de un flujo completo y verificar, con un parser del
formato elegido, que cada entrada se parsea y que las del ciclo de vida de la notificación exponen los
tres campos.

**Acceptance Scenarios**:

1. **Given** el servicio en ejecución, **When** emite cualquier entrada de log, **Then** la entrada es
   parseable por máquina como JSON de una línea por evento.
2. **Given** una notificación del tenant `A`, **When** se registra su aceptación, cada cambio de estado
   y su resultado terminal, **Then** cada entrada incluye `correlationId`, `tenantId` y
   `notificationId` como campos distintos.
3. **Given** dos tenants procesando notificaciones en paralelo, **When** se filtran los logs por el
   `tenantId` de uno, **Then** no aparece ningún dato de la notificación del otro.

---

### User Story 3 - Ningún dato sensible en los logs (Priority: P1)

Los logs son accesibles a más personas que los datos de negocio. Ninguna entrada contiene dirección del
destinatario en claro, cuerpo del mensaje, credenciales de proveedor ni tokens.

**Why this priority**: Incumplirlo es un incidente de seguridad; la constitución lo prohíbe.

**Independent Test**: Ejecutar un flujo con valores centinela reconocibles (dirección, texto del
mensaje, credencial simulada) y afirmar que ningún centinela aparece en la salida de log, ni siquiera
en trazas de excepciones.

**Acceptance Scenarios**:

1. **Given** una notificación cuyo destinatario es `usuario@ejemplo.com`, **When** se registra
   cualquier evento de su ciclo de vida, **Then** la dirección aparece solo en la forma definida por
   la política de sanitización: enmascarada. El contenido del mensaje y las credenciales nunca se
   registran y los caracteres de control se neutralizan.
2. **Given** un fallo del proveedor cuya respuesta incluye la credencial usada, **When** se registra el
   error, **Then** la credencial no aparece en el log.
3. **Given** una solicitud con cabecera `Authorization`, **When** se registra la solicitud, **Then** el
   token no aparece.

---

### User Story 4 - Distinguir fallos recuperables de permanentes por nivel de log (Priority: P2)

Un operador filtra por nivel para identificar qué requiere acción: errores de infraestructura o
proveedor distinguibles de errores permanentes de negocio.

**Why this priority**: El Principio IX lo exige, pero la trazabilidad (P1) aporta valor aun sin esta
distinción fina.

**Independent Test**: Provocar un fallo recuperable (proveedor simulado con error transitorio) y uno
permanente (rechazo del proveedor) y verificar nivel y categoría de cada entrada.

**Acceptance Scenarios**:

1. **Given** la aceptación de una notificación o un cambio de estado, **When** se registra, **Then**
   el nivel es el definido para hitos de negocio en la política de niveles (ver `Assumptions`).
2. **Given** un fallo recuperable, **When** se registra, **Then** su nivel y categoría lo distinguen de
   un fallo permanente.

---

### User Story 5 - El cliente conoce el identificador de su solicitud (Priority: P2)

Un sistema cliente recibe el identificador de correlación en la respuesta y puede consultarlo después
junto con el estado de la notificación, para dárselo a un operador al reportar un problema.

**Why this priority**: Cierra el ciclo cliente-operador, pero el recorrido interno funciona sin ello.

**Independent Test**: Enviar una notificación, leer el identificador de la respuesta, consultar el
estado y comprobar que expone el mismo valor.

**Acceptance Scenarios**:

1. **Given** una notificación aceptada, **When** el cliente consulta su estado, **Then** la respuesta
   incluye el identificador de correlación con el que se aceptó.
2. **Given** una respuesta de error de la API, **When** el cliente la recibe, **Then** incluye el
   identificador de correlación de esa solicitud.

---

### Edge Cases

- Notificaciones programadas disparadas por el planificador interno: no hay solicitud de origen; el
  servicio asigna un identificador propio reconocible como interno.
- Envío por lote: cada notificación del lote conserva el identificador del lote o uno derivado,
  de modo que el lote sea rastreable como unidad y cada elemento individualmente.
- Mensajes publicados antes de esta historia, sin cabecera de correlación: el consumidor genera un
  identificador y lo registra, sin descartar el mensaje.
- Identificador con saltos de línea o caracteres de control (intento de inyección de logs): se rechaza
  por formato y no se escribe.
- Cambios de hilo en el procesamiento reactivo: el identificador no se pierde ni se filtra a otra
  solicitud concurrente.
- Cancelación de la solicitud del cliente antes de terminar: las entradas posteriores del recorrido
  asíncrono conservan el identificador.
- Proveedores externos: el identificador no se envía a terceros salvo que el proveedor lo soporte y se
  decida explícitamente (ver `Assumptions`).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El servicio MUST aceptar un identificador de correlación en cada solicitud de la API y,
  si no viene o no es válido, generar uno nuevo.
- **FR-002**: Un identificador válido MUST ser una cadena de 1 a 64 caracteres del conjunto
  alfanumérico, punto, guion y guion bajo; cualquier otro valor MUST descartarse sin escribirse.
- **FR-003**: El identificador MUST devolverse al cliente en cada respuesta, incluidas las de error.
- **FR-004**: El identificador MUST propagarse automáticamente por todo el recorrido: solicitud REST,
  caso de uso, persistencia, publicación a la cola, consumo, escaneo de adjuntos, despacho al proveedor
  y reintentos, sin depender de que cada clase lo pase a mano.
- **FR-005**: El identificador MUST persistirse con la notificación y, en recorridos sin solicitud de
  origen (planificador), MUST asignarse uno interno reconocible.
- **FR-006**: Los mensajes publicados MUST llevar el identificador en una cabecera y los eventos de
  dominio MUST poder asociarse a él.
- **FR-007**: Todas las entradas de log MUST emitirse en formato estructurado parseable por máquina
  (JSON de una línea), nunca texto libre.
- **FR-008**: Toda entrada ligada al ciclo de vida de una notificación MUST incluir `correlationId`,
  `tenantId` y `notificationId` como campos independientes.
- **FR-009**: Ninguna entrada MUST contener direcciones de destinatario en claro, contenido del
  mensaje, credenciales de proveedor, tokens ni secretos, tampoco dentro de trazas de excepciones.
- **FR-010**: Existe un componente de sanitización reutilizable que aplica la política de FR-009
  (destinatario enmascarado, contenido y credenciales nunca registrados, caracteres de control
  neutralizados) y es el único camino para registrar datos de
  destinatario o contenido.
- **FR-011**: Los niveles de log MUST seguir una política documentada que distinga hitos de negocio,
  detalle interno, fallos recuperables y fallos permanentes (ver `Assumptions`).
- **FR-012**: Los errores de infraestructura o proveedor MUST distinguirse de los errores permanentes de
  negocio en el log mediante una categoría explícita.
- **FR-013**: La consulta de estado de una notificación MUST exponer el identificador de correlación.
- **FR-014**: El identificador MUST aislarse entre solicitudes concurrentes: nunca una entrada de una
  solicitud lleva el identificador de otra.
- **FR-016** (SUSTITUIDO por la spec 020, FR-015 a FR-017, el 2026-10-05: ahora el servicio interpreta,
  genera y propaga `traceparent` con Micrometer Tracing; `traceId` y `correlationId` tienen propósitos
  distintos): El servicio MUST aceptar, conservar y reenviar la cabecera `traceparent` (REST de
  entrada, contexto de log, cabecera de mensajería) sin interpretarla ni generarla; un valor que no
  cumpla el formato W3C se descarta.
- **FR-017**: Los eventos de una notificación MUST llevar el identificador de correlación persistido
  de la notificación, aun cuando el recorrido lo dispare otro proceso (planificador, reencolado); una
  notificación no puede tener dos identificadores. Si un mensaje llega sin cabecera, las entradas
  del ciclo de vida usan el identificador persistido; el consumidor solo genera uno de respaldo
  (prefijo `legacy-`) para las entradas que no tienen la notificación a mano.
- **FR-015**: El contrato de la API MUST documentar el identificador en solicitudes, respuestas y
  errores antes de su implementación.

### Key Entities

- **Identificador de correlación**: valor opaco de 1 a 64 caracteres que identifica un recorrido de
  punta a punta; generado si falta; inmutable durante el recorrido.
- **Notificación**: conserva el identificador con el que fue aceptada.
- **Entrada de log**: registro estructurado con marca de tiempo, nivel, mensaje, `correlationId` y, en
  el ciclo de vida de una notificación, `tenantId` y `notificationId`.
- **Política de sanitización**: reglas de qué datos se omiten, enmascaran o permiten en los logs.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Para el 100 % de los flujos de prueba de punta a punta, todas las entradas de log del
  recorrido completo (aceptación a resultado terminal) contienen el mismo identificador de correlación.
- **SC-002**: Un operador reconstruye el recorrido de una notificación con una sola búsqueda por
  identificador, sin cruzar fuentes adicionales.
- **SC-003**: En una prueba con dos tenants concurrentes, 0 entradas de un tenant contienen datos
  del otro, y 0 entradas llevan el identificador de otra solicitud.
- **SC-004**: En una prueba con datos centinela (dirección, contenido, credencial, token), 0
  apariciones de los centinelas en la salida de log.
- **SC-005**: El 100 % de las entradas emitidas durante el flujo de prueba se parsean sin error con el
  formato estructurado elegido.
- **SC-006**: El identificador generado o recibido aparece en la respuesta de todas las operaciones de
  la API, incluidas las respuestas de error, en el 100 % de los casos probados.

## Assumptions

- Política de niveles: INFO operaciones exitosas y cambios de estado; WARN rechazos 401/403 y fallos
  recuperables de proveedor (este último, supuesto del agente); ERROR fallos de sistema y fallos
  permanentes no recuperados; DEBUG detalle interno.
- Propagación: `X-Correlation-Id` en HTTP y `x-correlation-id` en mensajería, generado si falta;
  `traceparent` solo se transporta (FR-016).
- Los proveedores externos (correo, SMS, push) no reciben el identificador en esta historia; solo
  se registra en los logs propios. Reenviarlo se trata como mejora futura.
- Las métricas técnicas y de negocio de RNF-10 quedan fuera de esta historia; esta historia cubre solo
  logs estructurados y correlación. Excepción del Principio VII: dueño equipo de desarrollo del
  componente, fecha 2026-10-15 (igual para el reenvío a proveedores y el catálogo `ErrorCode`).
- El enmascaramiento de datos en logs no cambia los datos persistidos ni las respuestas de la API.
- La autenticación interina (HU2-096) ya existe; el `tenantId` para los logs sale de la identidad
  validada, no de un header libre.
- Los logs se consumen por un agregador externo; su elección y la retención quedan fuera de alcance.
