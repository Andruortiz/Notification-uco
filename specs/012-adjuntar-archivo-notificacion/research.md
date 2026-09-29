# Research: Adjuntar un archivo a una notificación

Cada decisión indica qué se eligió, por qué y qué se descartó. Las que dependen de una pregunta de
`spec.md § Clarifications` lo dicen; esas preguntas siguen pendientes de confirmación del usuario.

## Decisión 1 — Cómo viaja el archivo (Q1, pendiente de confirmación)

- **Decisión (recomendada)**: referencia https. Cada adjunto llega como `{ fileName, contentType,
  sizeBytes, url }`. El servicio no descarga, no abre y no guarda el archivo; guarda la referencia y los
  metadatos con la notificación.
- **Por qué**:
  - Los tres proveedores previstos consumen una dirección de archivo: el de correo acepta adjuntos por
    dirección además de por contenido, el de SMS solo acepta direcciones públicas de medios y el de push
    solo acepta la imagen por dirección. Con contenido embebido, SMS y push obligarían al servicio a
    publicar el archivo en una dirección accesible desde el proveedor.
  - No aparece ningún almacén nuevo. Hoy la notificación se guarda como un único documento en MongoDB; un
    archivo de varios MB codificado en ese documento se acerca al límite de 16 MB por documento y engorda
    cada lectura del despacho, la búsqueda y las actualizaciones en vivo. Un almacén aparte (GridFS o
    almacenamiento de objetos) trae escritura en dos pasos sin atomicidad, limpieza de huérfanos y una
    política de retención que el proyecto aún no tiene.
  - La aceptación sigue siendo barata: el cuerpo sigue muy por debajo del límite de 256 KB que el
    servidor reactivo acepta por defecto, y un lote de 16 elementos concurrentes no retiene decenas de MB
    en memoria.
- **Costo aceptado**: el tamaño y el tipo son los **declarados** por el cliente. Se documenta como riesgo
  con dueño y fecha en el spec (Risks) y se acota con los topes globales (Decisión 3) y la regla de
  despacho (Decisión 6).
- **Alternativas descartadas**:
  - **Contenido embebido**: ver el delta completo en `plan.md § Si Q1 cambia a contenido embebido`.
  - **Ambas formas**: suma los costos de las dos sin un caso de uso que lo pida hoy. El contrato queda
    abierto a agregar un campo `content` excluyente con `url` más adelante sin romper a nadie.
  - **Descargar el archivo en la aceptación para verificarlo**: convierte al servicio en un cliente HTTP
    de direcciones arbitrarias (riesgo de SSRF hacia la red interna del clúster), agrega latencia y una
    dependencia de disponibilidad externa a la aceptación.

## Decisión 2 — Dónde declara cada canal sus reglas de adjuntos (Q3, pendiente de confirmación)

- **Decisión (recomendada)**: dentro de la forma de contenido del canal (`contentSchema`, JSON Schema
  2020-12), la misma que hoy fija `maxLength` del cuerpo. El canal declara una propiedad `attachments`
  de tipo arreglo; `maxItems` fija la cantidad, `items.properties.contentType.enum` los tipos y
  `items.properties.sizeBytes.maximum` el tamaño.
- **Regla por defecto cerrada**: un canal acepta adjuntos **solo** si su esquema tiene
  `properties.attachments` en la raíz. Si el esquema está vacío (hoy EMAIL) o no declara esa propiedad
  (hoy SMS y PUSH), una notificación con adjuntos se rechaza con `InvalidContentException` "channel X does
  not accept attachments". Se comprueba leyendo el árbol del esquema, no la validación: JSON Schema, por
  diseño, acepta propiedades no declaradas.
- **Por qué**: es el patrón que la historia pide seguir; `GET /channels` ya expone `contentSchema`, así
  que FR-011 se cumple sin cambiar el contrato de la consulta; la validación reutiliza
  `ContentSchemaValidator` y el refresco del catálogo existente (SC-005) sin código nuevo de caché.
- **Alternativas descartadas**: una sección propia `attachmentPolicy` en el documento del catálogo, en
  `ChannelRoute` y en `ChannelItem`. Más tipada, pero duplica el mecanismo de reglas por canal, cambia el
  contrato de la consulta del catálogo, la siembra y el refresco, y deja dos lugares donde mirar las
  reglas de un canal.
- **Configuración por defecto**: no cambia. Ningún canal declara `attachments` (Q3) porque ningún
  proveedor real sabe enviarlos; cada historia de canal habilitará el suyo. `application.yml` no se toca.

## Decisión 3 — Reglas globales y en qué momento se aplican

- **Decisión**: una política pura de dominio, `AttachmentPolicy` (`core/domain/policy`), con los topes
  globales como constantes, igual que `NotificationContent.MAX_LENGTH`:
  - a lo sumo **5** adjuntos por notificación;
  - `sizeBytes` presente, entre 1 y **10 485 760** (10 MiB), inclusivo;
  - `contentType` presente y, ya normalizado, dentro de la lista global: `application/pdf`, `image/png`,
    `image/jpeg`, `image/gif`, `image/webp`, `text/plain`, `text/csv`,
    `application/vnd.openxmlformats-officedocument.wordprocessingml.document`,
    `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`;
  - `fileName` presente, de 1 a 255 caracteres, sin `/`, `\`, caracteres de control, y distinto de `.` y
    `..`;
  - `url` presente, de hasta 2048 caracteres, URI absoluta con esquema `https`, con servidor y sin
    usuario ni contraseña.
- **Momento**: solo en la aceptación (`SendNotificationService`), antes de la validación por canal. **No**
  en el constructor de `Attachment` ni al reconstituir desde MongoDB: si un tope global baja mañana, las
  notificaciones ya aceptadas deben seguir cargándose y despachándose.
- **Error**: `InvalidAttachmentException(position, rule)` con mensaje `attachments[<i>]: <regla>` y, si
  el nombre es válido, `(<fileName>)`. Nunca incluye la `url`.
- **Por qué no validar en el constructor**: el constructor no conoce la posición en la lista (FR-007) y
  el mapeo desde MongoDB debe ser tolerante. `Attachment` solo normaliza el tipo y copia valores.
- **Alternativa descartada**: validar en el controlador. Dejaría la regla de negocio en `infrastructure`
  (Principio I) y el caso de uso de lote sin ella.

## Decisión 4 — Normalización del tipo de contenido

- **Decisión**: `Attachment` guarda el tipo en minúsculas, sin parámetros (`; charset=...`) y sin espacios.
  Tanto la lista global como el `enum` del canal se comparan contra ese valor; los esquemas del catálogo
  deben escribir los tipos en minúsculas (documentado en el contrato).
- **Alternativa descartada**: comodines (`image/*`). No hacen falta para los canales previstos y
  complican la regla global.

## Decisión 5 — La referencia nunca sale del servicio salvo hacia el proveedor

- **Decisión**:
  - El nodo que `ContentSchemaValidator` valida contiene `fileName`, `contentType` y `sizeBytes` de cada
    adjunto, **nunca** `url`: ningún mensaje del validador puede citarla.
  - `Attachment.toString()` se redefine para omitir la `url`; así, cualquier `toString` de
    `NotificationContent` o de un comando que acabe en un registro o en una excepción no la filtra.
  - Los registros de adjuntos (Decisión 8) solo escriben nombre, tipo y tamaño; el nombre se registra con
    los caracteres de control reemplazados, para que un nombre malicioso no parta la línea.
  - Los eventos de dominio (`NotificationAccepted`, etc.) solo llevan `notificationId` y fecha; el mensaje
    de despacho solo lleva el `notificationId`. No cambian.
  - Las respuestas de estado, búsqueda y actualizaciones en vivo no incluyen el contenido hoy y no se
    amplían (Out of Scope).
- **Dónde sí está**: en el documento de MongoDB de la notificación (necesario para despachar, FR-008) y
  en el objeto que recibe el adaptador de proveedor.

## Decisión 6 — Proveedor que no sabe enviar adjuntos (Q4, pendiente de confirmación)

- **Decisión (recomendada)**: `NotificationSenderPort` gana `boolean supportsAttachments()` **abstracto**.
  `SimulatedNotificationProvider` devuelve `true`; Brevo, Twilio y FCM devuelven `false` hasta sus
  historias. En `DispatchNotificationService.sendThrough`, si la notificación tiene adjuntos y el
  proveedor no los soporta, el resultado es `AttemptResult.PERMANENT_FAILURE` **sin llamar al
  proveedor**, y el flujo existente aplica `markFailed` (fallo definitivo, sin reintentos) y guarda el
  intento a nombre del proveedor.
- **Por qué abstracto y no `default false`**: mismo criterio que `disabledReason()` en la historia del
  catálogo: cada adaptador declara explícitamente su capacidad y un adaptador nuevo no compila hasta
  hacerlo. `default false` sería seguro (falla cerrado), pero ocultaría que el adaptador nunca pensó en
  adjuntos.
- **Por qué en el núcleo y no en cada adaptador**: una sola regla, falla cerrada, y probada una vez; si
  cada adaptador la reimplementara, uno que la olvide entregaría sin adjunto en silencio.
- **Motivo textual**: `DeliveryAttempt` no tiene campo de causa y `core` no registra. El historial guarda
  proveedor y fallo definitivo; el motivo se deduce de datos visibles. Queda como riesgo con dueño y fecha
  en el spec. Alternativas descartadas: agregar causa a `DeliveryAttempt` (cambio de modelo y de todas las
  respuestas de historial, fuera del alcance de esta historia); registrar desde `core` (rompe el patrón
  de un núcleo sin registros).
- **Rechazar ya en la aceptación si el proveedor preferente no sabe enviar adjuntos**: descartado como
  única barrera, porque el catálogo puede cambiar entre aceptación y despacho; y como barrera adicional,
  porque acopla la aceptación al registro de proveedores sin ganar garantía. La declaración del canal es
  el contrato con el cliente; el despacho es la red de seguridad.

## Decisión 7 — Orden de validación e idempotencia

- **Decisión**: se conserva el orden actual de `SendNotificationService`: ruta del canal →
  validación (ahora `AttachmentPolicy` y luego `ContentSchemaValidator`) → búsqueda de duplicada →
  aceptación. Una duplicada con adjuntos inválidos se rechaza, igual que hoy una duplicada con contenido
  inválido (FR-012 ajustado a este comportamiento).
- **Alternativa descartada**: buscar la duplicada antes de validar. Cambiaría el comportamiento existente
  sin adjuntos, fuera del alcance.

## Decisión 8 — Registros de aceptación y rechazo (FR-010)

- **Decisión**: los escribe `NotificationController` (infraestructura), solo cuando la solicitud trae
  adjuntos, con el estilo `clave=valor` que ya usan los adaptadores de proveedor:
  - aceptada: `Notification accepted with attachments tenantId=… externalId=… notificationId=…
    duplicate=… attachments=[fileName|contentType|sizeBytes, …]`;
  - rechazada: `Notification with attachments rejected tenantId=… externalId=… attachments=[…]
    reason=<mensaje de la excepción>` (el mensaje nunca contiene la `url`, Decisiones 3 y 5).
  El formateo vive en una clase auxiliar del paquete REST, `AttachmentLogFormatter`, que reemplaza
  caracteres de control del nombre.
- **Por qué en el controlador**: es el único punto que tiene a la vez el tenant, el identificador externo
  y el resultado; `core` no registra.
- **Brecha que no se cierra**: el formato estructurado (JSON) del RNF-10 sigue pendiente para todo el
  servicio (spec, Risks).

## Decisión 9 — Persistencia

- **Decisión**: subdocumentos embebidos en `notifications`: `NotificationDocument` gana
  `List<AttachmentDocument> attachments` (`fileName`, `contentType`, `sizeBytes`, `url`), en el orden
  recibido. Un documento anterior sin el campo se lee como lista vacía. Sin índices nuevos ni migración.
- **Por qué**: 5 × (≤ 2048 + ≤ 255 + tipo + tamaño) son pocos KB; se lee con la notificación en una sola
  operación, conservando el bloqueo optimista existente.

## Decisión 10 — Lote (Q5, pendiente de confirmación)

- **Decisión (recomendada)**: `SendNotificationBatchService` agrega `InvalidAttachmentException` a los
  errores que convierten un elemento en `rejected` con su motivo (hoy `ChannelNotAvailableException` e
  `InvalidContentException`). `BatchNotificationItem` ya lleva `NotificationContent`, que ahora incluye
  los adjuntos; no cambia de forma.
- **Hallazgo**: `/notifications:sendBatch` está en el contrato y el caso de uso existe con su bean, pero
  **ningún controlador lo atiende**. El contrato no lo marca como planificado o bloqueado. Esta historia
  no lo expone (fuera de alcance); SC-007 se prueba en el caso de uso. Se reporta al usuario como posible
  brecha preexistente.

## Decisión 11 — Pruebas

- **Unitarias `core`** (JUnit 5 + Mockito + `StepVerifier`): `AttachmentTest`, `AttachmentPolicyTest`
  (cada regla y cada límite inclusivo), `ContentSchemaValidatorTest` (cerrado por defecto, reglas del
  canal, mensaje sin `url`), `NotificationContentTest`, `SendNotificationServiceTest` (rechazo sin
  `save` ni `publish`), `DispatchNotificationServiceTest` (regla de Decisión 6 y control positivo),
  `SendNotificationBatchServiceTest` (SC-007).
- **Integración de adaptador** (Testcontainers, sin simulaciones de la base):
  `NotificationMongoAdapterTest` — ida y vuelta con adjuntos en orden y documento antiguo sin el campo.
- **Controlador**: `NotificationControllerTest` (`@WebFluxTest`) — mapeo de `attachments`, ausencia →
  lista vacía, `400` por `InvalidAttachmentException`, registros de aceptación y rechazo.
- **E2E** `NotificationAttachmentE2ETest` (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` con MongoDB
  y RabbitMQ + `WebTestClient`), refresco del catálogo a 1 s y dos emisores de prueba registrados como
  beans: `recording-attachments` (`supportsAttachments() = true`) y `recording-plain` (`false`), que
  guardan lo que reciben. El catálogo se escribe en `@BeforeEach` con canales de prueba y se espera a que
  `GET /channels` lo refleje. Cubre SC-001 a SC-006 y la User Story 3.4 (EMAIL por defecto rechaza).
  SC-003 captura registros (`ListAppender`), respuestas de error, eventos publicados (cola temporal
  enlazada al exchange de eventos) y las consultas de estado y búsqueda, con control positivo de nombre,
  tipo y tamaño. SC-005 afirma el plazo con `Duration`. Las notificaciones se producen por la API real,
  nunca con documentos armados a mano.
- **Arquitectura**: `HexagonalArchitectureTest` y `ModularityTests` sin cambios, deben seguir en verde.
