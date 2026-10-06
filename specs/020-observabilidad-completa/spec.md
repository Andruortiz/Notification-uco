# Feature Specification: Observabilidad completa (métricas, ErrorCode, trazado y correlación con proveedores) (020)

**Feature Branch**: `feature/020-observabilidad-completa`

**Created**: 2026-10-05

**Status**: Draft (clarificaciones pendientes de confirmación del usuario)

**Input**: User description: "Entregar ahora, sin diferir, las cuatro excepciones del Principio VII que dejó
la spec 016: métricas técnicas y de negocio de RNF-10, reenvío del identificador de correlación a los
proveedores, catálogo numérico `ErrorCode` y Micrometer Tracing / SDK de OpenTelemetry."

Trazabilidad: RNF-10 (métricas técnicas y de negocio expuestas), RNF-07 (auditabilidad de extremo a
extremo), Principio IX (observabilidad y trazabilidad) y Principio VII (sin soluciones temporales).
Cierra las excepciones de `specs/016-logs-correlation-id` (dueño: equipo de desarrollo del componente,
fecha 2026-10-15). RNF-11 (sondas de salud) ya está cubierto por los grupos `liveness` y `readiness`;
RNF-12 y RNF-15 quedan fuera de esta spec.

Contexto: la spec 016 entregó logs JSON de una línea, `correlationId`/`tenantId`/`notificationId` como
campos, `LogSanitizer`, `FailureCategory` (tres valores) y el transporte de `traceparent` sin
interpretarlo. Hoy el servicio no expone métricas de negocio, no tiene identificadores de error
estables (los fallos se distinguen solo por `FailureCategory` y mensajes de texto) y el id de correlación
no sale hacia los proveedores. Solo existe una métrica propia (`notification.configuration.version`,
HU2-073) y los endpoints de actuator expuestos por defecto son los de salud.

## Clarifications

### Session 2026-10-05

Preguntas que requieren decisión del usuario. No fueron respondidas: cada una lleva una respuesta
recomendada marcada como **pendiente de confirmación**. El plan se escribe sobre las recomendaciones y
se revisa si el usuario decide otra cosa.

- Q1: ¿Backend de métricas y forma de exponerlas? -> Recomendado (pendiente de confirmación):
  Micrometer con registro Prometheus, extraído por la plataforma en `/actuator/prometheus`. Alternativa:
  empujar métricas por OTLP.
- Q2: ¿Protección del endpoint de métricas? Hoy `AuthenticationWebFilter` exime `/actuator` completo. ->
  Recomendado (pendiente de confirmación): puerto de gestión separado (`management.server.port`,
  por defecto 8061) no publicado fuera del clúster; el puerto de la API (8060) no expone `metrics` ni
  `prometheus`.
- Q3: ¿Qué métricas de negocio? -> Recomendado (pendiente de confirmación): contadores
  `notification.accepted`, `notification.dispatched` (resultado: `delivered`, `recoverable`, `failed`,
  `discarded`), `notification.attempts` y `notification.provider.duration` (temporizador), etiquetados
  por `channel` y `provider`; sin etiqueta `tenantId` (cardinalidad no acotada). Ver FR-001 a FR-005.
- Q4: ¿Formato del `ErrorCode`? -> Recomendado (pendiente de confirmación): `enum` en `utils` con un
  entero estable por código y su representación textual `NTF-<entero>` (rangos: 1xxx validación, 2xxx
  autenticación y autorización, 3xxx negocio, 4xxx proveedor, 5xxx infraestructura); cada código
  declara su `FailureCategory`. Alternativa: códigos alfanuméricos por dominio (`NTF-VAL-001`).
- Q5: ¿Se expone `ErrorCode` al cliente? -> Recomendado (pendiente de confirmación): sí, como campo
  `code` del `ErrorResponse` (cambio aditivo y compatible) además del log y las métricas.
- Q6: ¿Qué proveedores admiten propagar el id? -> Recomendado (pendiente de confirmación): Brevo, por
  su mecanismo de datos personalizados del mensaje; Twilio y FCM no tienen un campo de correlación
  que no sea visible al destinatario o a la app, por lo que no se envía. La verificación contra la
  documentación vigente de cada API es una tarea previa a implementar (ver `research.md`). Si algún
  proveedor no puede, se registra como excepción del Principio VII con dueño y fecha (FR-020).
- Q7: ¿Destino y muestreo del trazado? -> Recomendado (pendiente de confirmación): exportación OTLP
  por HTTP configurada por variable de entorno, desactivada si no hay endpoint, muestreo
  `management.tracing.sampling.probability` por variable de entorno con valor por defecto 0.1 en
  producción y 1.0 en local/pruebas.
- Q8: ¿`traceId` sustituye al `correlationId`? -> Recomendado (pendiente de confirmación): no. El
  `correlationId` sigue siendo el identificador de negocio (persistido, devuelto al cliente); `traceId`
  se añade como campo de log aparte. Esto reemplaza la restricción de la 016 que impedía interpretar y
  generar `traceparent` (FR-016 de la 016).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consultar métricas técnicas y de negocio (Priority: P1)

Un operador o una plataforma de monitoreo consulta el endpoint de métricas y obtiene, sin leer logs,
cuántas notificaciones se aceptaron, despacharon o fallaron por canal y proveedor, y la latencia y tasa
de error técnicas del servicio.

**Why this priority**: Es la parte pendiente de RNF-10 y de la restricción técnica de la constitución
("se exponen para consulta externa, no solo quedan registradas en el log").

**Independent Test**: Levantar el servicio, enviar notificaciones con el proveedor simulado en cada
resultado, leer el endpoint de métricas y afirmar que los contadores y temporizadores reflejan
exactamente lo enviado, por canal y proveedor.

**Acceptance Scenarios**:

1. **Given** el servicio en ejecución, **When** se aceptan 3 notificaciones de correo, **Then**
   `notification.accepted` con `channel=EMAIL` aumenta en 3.
2. **Given** un despacho con resultado entregado, recuperable o fallido, **When** termina el intento,
   **Then** `notification.dispatched` aumenta en la etiqueta de resultado correspondiente y
   `notification.attempts` en uno.
3. **Given** una llamada a un proveedor, **When** termina, **Then** `notification.provider.duration`
   registra su duración etiquetada por proveedor y resultado.
4. **Given** solicitudes HTTP a la API, **When** se consultan las métricas técnicas, **Then** existen
   latencia por percentiles, rendimiento y tasa de error de la API y del consumo de mensajería.
5. **Given** dos tenants, **When** envían notificaciones, **Then** ninguna métrica expone el
   `tenantId` ni datos del destinatario o del contenido.

---

### User Story 2 - Catálogo `ErrorCode` estable (Priority: P1)

Un integrador y un operador identifican cada fallo por un código estable, documentado y consultable,
en lugar de depender de mensajes de texto.

**Why this priority**: La respuesta de error y los logs son el contrato diagnóstico; los demás
aspectos (métricas por error, trazado) reutilizan el código.

**Independent Test**: Provocar un error de validación, uno de autorización, un rechazo permanente del
proveedor y un fallo recuperable; comprobar que la respuesta, el log y la métrica llevan el mismo código
y que ese código pertenece al catálogo.

**Acceptance Scenarios**:

1. **Given** una respuesta de error de la API, **When** se recibe, **Then** incluye `code` con un
   valor del catálogo, además de `message` y `correlationId`.
2. **Given** un fallo en cualquier punto del recorrido, **When** se registra, **Then** la entrada
   incluye `errorCode` y `failureCategory`, y la categoría coincide con la declarada por el código.
3. **Given** el catálogo, **When** se enumera, **Then** cada código es único, estable (no se
   reutiliza ni cambia de significado) y declara su categoría; una prueba falla si se duplica uno.
4. **Given** el contrato de la API, **When** se lee, **Then** el esquema de error enumera los códigos
   y el campo `code` está documentado antes de su implementación.
5. **Given** un fallo cuya causa no está catalogada, **When** se registra, **Then** se usa un código
   genérico por categoría (interno de infraestructura) y nunca el mensaje de la excepción.

---

### User Story 3 - Trazado distribuido con Micrometer Tracing y OpenTelemetry (Priority: P2)

Un operador abre una traza en su herramienta de trazado y ve, como tramos de una misma traza, la
solicitud REST, la publicación a la cola, el consumo, el despacho y la llamada HTTP al proveedor, con el
`correlationId` como atributo para saltar de la traza al log y viceversa.

**Why this priority**: Complementa los logs con latencias por tramo, pero el recorrido ya es
reconstruible por `correlationId` sin él.

**Independent Test**: E2E con un exportador en memoria: enviar una notificación y comprobar que todos
los tramos comparten el `traceId`, que el `traceId` aparece en los logs del recorrido y que el
`correlationId` es atributo de los tramos.

**Acceptance Scenarios**:

1. **Given** una solicitud con `traceparent` válido, **When** se procesa, **Then** los tramos del
   servicio continúan esa traza (mismo `traceId`) y el log del recorrido incluye `traceId` y `spanId`.
2. **Given** una solicitud sin `traceparent`, **When** se procesa, **Then** el servicio inicia una
   traza y el `traceId` es el mismo en el consumo asíncrono del mensaje.
3. **Given** un `traceparent` inválido, **When** llega, **Then** se descarta (como hoy) y se inicia
   una traza nueva; el valor original nunca se escribe en los logs.
4. **Given** que no hay endpoint de exportación configurado, **When** el servicio arranca, **Then**
   arranca igual, sin errores ni reintentos de exportación, y los logs siguen llevando `traceId`.
5. **Given** dos solicitudes concurrentes, **When** se procesan, **Then** ningún tramo ni log lleva el
   `traceId` de la otra.
6. **Given** un tramo, **When** se inspeccionan sus atributos, **Then** no contienen destinatario,
   contenido, credenciales ni tokens.

---

### User Story 4 - Reenviar el identificador de correlación al proveedor (Priority: P3)

Cuando soporte indica que un proveedor no entregó un mensaje, el operador puede buscar en el panel del
proveedor por el `correlationId` y cruzarlo con los logs del servicio.

**Why this priority**: Depende de que cada API lo permita; aporta valor operativo pero no bloquea las
demás.

**Independent Test**: Contra un servidor HTTP simulado por proveedor, enviar una notificación y
comprobar que la solicitud saliente lleva el identificador en el campo admitido; para los proveedores
sin campo admitido, comprobar que la solicitud no lo lleva y que está documentado.

**Acceptance Scenarios**:

1. **Given** un proveedor cuya API admite un campo de correlación, **When** se despacha, **Then** la
   solicitud saliente lleva el `correlationId` ya validado por `CorrelationId`.
2. **Given** un proveedor sin ese campo, **When** se despacha, **Then** no se envía ni se improvisa un
   canal alternativo visible para el destinatario, y el motivo consta en `research.md`.
3. **Given** un identificador inválido o ausente, **When** se despacha, **Then** se usa el persistido de
   la notificación (nunca uno distinto) y nunca un valor sin validar.
4. **Given** el fallo o el rechazo del proveedor, **When** se registra, **Then** el log del intento
   permite cruzar el `correlationId` con el identificador de mensaje del proveedor.

---

### Edge Cases

- Cardinalidad: ninguna etiqueta de métrica admite valores no acotados (`tenantId`, `notificationId`,
  `correlationId`, destinatario). Solo `channel`, `provider`, `result`, `errorCode` y valores de
  catálogo cerrado.
- Exposición: el endpoint de métricas no devuelve nada sensible ni los valores de configuración; y no
  es accesible desde el puerto público de la API.
- Rendimiento: instrumentar no puede romper RNF-02 (≤ 200 ms p95 de aceptación) ni bloquear hilos
  reactivos; el exportador de trazas es asíncrono y por lotes.
- Pérdida de la traza: si el colector no responde, el servicio degrada a solo logs y métricas; nunca
  falla ni retrasa una notificación por culpa del trazado.
- Mensajes en vuelo publicados antes del despliegue: sin `traceparent` válido se inicia una traza
  nueva en el consumo; sin `x-correlation-id` rige la regla de la 016.
- Reintentos y reencolado: cada intento es un tramo hijo; el reencolado del planificador inicia traza
  propia enlazada por `correlationId` persistido.
- Códigos de error: un fallo nuevo sin código asignado no puede llegar a producción; la prueba de
  catálogo y la de arquitectura lo impiden.
- Lote: el contador de aceptadas cuenta notificaciones, no solicitudes.
- Proveedor deshabilitado o rechazo previo a la llamada: cuenta como resultado `failed` sin generar
  duración de proveedor.

## Requirements *(mandatory)*

### Functional Requirements

**Métricas**

- **FR-001**: El servicio MUST exponer métricas en un endpoint de consulta externa en formato
  Prometheus, en un puerto de gestión no público (Q1, Q2).
- **FR-002**: El servicio MUST registrar el contador `notification.accepted` por `channel`, contando
  cada notificación aceptada (incluidas las de lote) una sola vez; una reaceptación idempotente no
  incrementa el contador.
- **FR-003**: El servicio MUST registrar `notification.dispatched` por `channel`, `provider` y `result`,
  y `notification.attempts` por `channel` y `provider`, una vez por intento de despacho.
- **FR-004**: El servicio MUST registrar un temporizador `notification.provider.duration` por
  `provider` y `result`, con histograma para p95 y p99.
- **FR-005**: El servicio MUST registrar métricas de errores por `errorCode` y `failureCategory` sin
  etiquetas de cardinalidad no acotada.
- **FR-006**: Las métricas técnicas MUST cubrir latencia (percentiles), rendimiento y tasa de error de
  la API y del consumo de mensajería, más estado del proceso (JVM, hilos, memoria) y de los clientes
  de MongoDB y RabbitMQ en la medida en que la plataforma ya los instrumente.
- **FR-007**: La instrumentación de negocio MUST pasar por un puerto de salida de `core` sin
  dependencias de Micrometer; el adaptador con Micrometer vive solo en `infrastructure`.
- **FR-008**: Ninguna métrica MUST llevar `tenantId`, `notificationId`, `correlationId`, destinatario,
  contenido ni credenciales como etiqueta o valor.
- **FR-009**: El contrato operativo (nombres, etiquetas, unidades, puerto) MUST documentarse en
  `contracts/` antes de implementarse.

**ErrorCode**

- **FR-010**: Existe un catálogo `ErrorCode` en `utils` con valor entero estable, representación
  textual `NTF-<entero>` y `FailureCategory` asociada; los valores nunca se reutilizan (Q4).
- **FR-011**: Toda respuesta de error de la API MUST incluir `code` (Q5) junto con `message` y
  `correlationId`; el esquema `ErrorResponse` MUST definirse en `api-notificaciones.yaml` antes de su
  implementación y enumerar los códigos.
- **FR-012**: Todo log de fallo MUST incluir `errorCode` y `failureCategory`; la categoría MUST
  derivarse del código, no declararse aparte.
- **FR-013**: Los mensajes concatenados con `category=` en `core` (`DispatchNotificationService`,
  `RequeuePendingNotificationsService`) MUST sustituirse por el código del catálogo; `core` no puede
  depender del logger de infraestructura.
- **FR-014**: Una prueba MUST impedir códigos duplicados, categorías ausentes y códigos sin uso
  (todo código del catálogo tiene al menos un punto de emisión o una prueba que lo emite).

**Trazado**

- **FR-015**: El servicio MUST emitir tramos con Micrometer Tracing y el puente de OpenTelemetry para
  la solicitud REST, la publicación y el consumo de mensajes, el despacho y la llamada HTTP a cada
  proveedor, y exportarlos por OTLP cuando haya endpoint configurado (Q7). Las dependencias
  de Micrometer Tracing y OpenTelemetry viven solo en `infrastructure`.
- **FR-016**: `traceId` y `spanId` MUST agregarse como campos de log; el `correlationId` MUST
  añadirse como atributo de cada tramo del recorrido (Q8).
- **FR-017**: Un `traceparent` válido de entrada MUST continuarse; uno inválido MUST descartarse sin
  escribirse; el trazado nunca puede hacer fallar ni retrasar el procesamiento de una notificación.
- **FR-018**: Los atributos de los tramos MUST pasar por la política de `LogSanitizer`; ninguno
  contiene destinatario en claro, contenido, credenciales ni tokens.

**Reenvío a proveedores**

- **FR-019**: Para cada proveedor cuya API admita un campo de correlación no visible para el
  destinatario, el adaptador MUST enviar el `correlationId` persistido de la notificación (Q6).
- **FR-020**: Para cada proveedor que no lo admita, la spec MUST registrar el motivo y, si el
  requisito no puede cumplirse, una excepción del Principio VII con dueño y fecha; no se improvisa un
  canal alternativo.
- **FR-021**: El `correlationId` enviado al proveedor MUST haber pasado por `CorrelationId`
  (1 a 64 caracteres del conjunto permitido); un valor inválido no se envía.

**Trazabilidad y pruebas**

- **FR-022**: Una prueba E2E por flujo MUST recorrer aceptación, publicación, consumo, despacho
  simulado y lectura de métricas, trazas y logs, y afirmar los tres señales con el mismo
  `correlationId` y `traceId`.

### Key Entities

- **ErrorCode**: código estable del catálogo; entero, representación `NTF-<entero>`, categoría.
- **Métrica de negocio**: contador o temporizador con nombre y etiquetas de catálogo cerrado.
- **Tramo (span)**: unidad de trabajo de una traza con `traceId`, `spanId` y atributos sin datos
  sensibles.
- **Puerto de métricas**: interfaz de `core` para registrar hechos de negocio sin conocer Micrometer.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: En una prueba con N notificaciones por canal y resultado conocido, los contadores del
  endpoint de métricas coinciden exactamente con N por `channel`, `provider` y `result` (0 de desvío).
- **SC-002**: El 100 % de las respuestas de error de la API probadas incluyen `code` válido del
  catálogo, y el mismo código aparece en el log y en la métrica correspondiente.
- **SC-003**: En el E2E de trazado, el 100 % de los tramos del recorrido comparte un `traceId` y
  el mismo valor aparece en el 100 % de las entradas de log del recorrido.
- **SC-004**: La consulta del endpoint de métricas devuelve respuesta en 2 s o menos con carga de
  prueba, y el endpoint no es accesible desde el puerto público.
- **SC-005**: En una prueba con datos centinela (destinatario, contenido, credencial, token), 0
  apariciones en métricas y atributos de tramos, además de los logs ya cubiertos por la 016.
- **SC-006**: Con el colector de trazas caído o sin configurar, el tiempo de aceptación p95 no empeora
  más de 10 % respecto de la línea base y 0 notificaciones se pierden.
- **SC-007**: Para cada proveedor con campo admitido, 100 % de las solicitudes salientes de prueba
  llevan el `correlationId`; para los demás, 0 solicitudes lo llevan.
- **SC-008**: Con dos tenants concurrentes, 0 métricas o tramos exponen o mezclan datos de tenant.

## Assumptions

- Spring Boot 3.3.4 incluye Micrometer 1.13 y Micrometer Tracing 1.3; no se actualiza la plataforma.
- El colector OTLP y el servidor de métricas los provee la plataforma; su elección, retención y
  alertas quedan fuera de alcance (igual que el agregador de logs en la 016).
- La autenticación interina y el aislamiento por tenant (HU2-096) siguen vigentes; el puerto de
  gestión no requiere autenticación porque no es accesible desde fuera del clúster (Q2).
- `FailureCategory` se conserva; `ErrorCode` la complementa, no la sustituye.
- Las excepciones del Principio VII de la 016 (métricas, reenvío a proveedores, `ErrorCode`) y la de
  Micrometer Tracing/OpenTelemetry quedan cerradas al aprobarse esta historia; solo lo que un
  proveedor no pueda soportar permanece como excepción nueva con dueño (equipo de desarrollo del
  componente) y fecha acordada con el usuario.
- Los umbrales de alerta, tableros y SLO quedan fuera de alcance; solo se entregan las señales.
