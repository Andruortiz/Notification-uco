# Phase 0 — Research: Integrar Brevo como primer proveedor real de correo

**Feature**: 008-brevo-proveedor-correo | **Date**: 2026-09-21

Cada decisión se numera y se referencia desde `plan.md`. Las cuatro preguntas Q1–Q4 de
`spec.md § Clarifications` siguen **pendientes de confirmación del usuario**; las decisiones que
dependen de ellas lo dicen explícitamente.

---

## Decisión 1 — Cliente HTTP: `WebClient` reactivo, nunca bloqueante

**Decision**: el adaptador usa `org.springframework.web.reactive.function.client.WebClient` sobre
`ReactorClientHttpConnector`, construido con un `HttpClient` que fija `responseTimeout` y
`ChannelOption.CONNECT_TIMEOUT_MILLIS`. El `WebClient` se construye una sola vez, en un `@Bean`
dedicado al proveedor, y se reutiliza para todas las llamadas.

**Rationale**: ADR-0003 (`docs/adr/0003-reactivo-no-bloqueante.md`) prohíbe envolver un cliente
bloqueante en `Mono.fromCallable`. `spring-boot-starter-webflux` ya está en
`infrastructure/pom.xml`, así que `WebClient` y `reactor-netty` están disponibles sin agregar
dependencias. El puerto `NotificationSenderPort.send` ya devuelve `Mono<AttemptResult>`, de modo que
el adaptador encaja sin adaptar el contrato.

**Alternatives considered**:

- `RestClient` / `RestTemplate` con `Mono.fromCallable(...).subscribeOn(boundedElastic())`: descartado
  por ADR-0003. Funciona, pero convierte una llamada externa lenta en un hilo ocupado por notificación.
- SDK oficial del proveedor: descartado. Es un cliente bloqueante, agrega una dependencia con su propia
  cadena transitiva y acopla el adaptador a la versión del SDK en lugar de a un contrato HTTP estable y
  pequeño (un solo endpoint).

**Nota sobre el borde bloqueante existente**: `NotificationDispatchListener.onMessage` hace `.block()`
sobre el caso de uso. Eso es preexistente y correcto en ese borde (hilo del contenedor de escucha de
RabbitMQ, no un hilo del event loop). Esta historia no lo cambia, pero sí es la razón por la que el
tiempo de espera del proveedor debe ser acotado y explícito (Decisión 7): sin él, un proveedor que no
responde ocupa indefinidamente un hilo de consumo.

---

## Decisión 2 — Proveedor sin credenciales: registrado, deshabilitado y con motivo explícito

**Contexto**: HU2-046 ("proveedor sin credenciales deshabilitado con motivo explícito") **no está
implementada**. El catálogo no tiene noción de proveedor habilitado ni de motivo:
`ChannelCatalogDocument` es `(channelType, providers, contentSchema)`, `ChannelRoute` es
`(channelType, List<ProviderId>, contentSchema)` y ni `ChannelCatalogSeeder` ni
`ChannelCatalogRefresher` conocen un estado `enabled`. Hay que cumplir el criterio de aceptación sin
inventar el modelo que le corresponde a HU2-046.

**Decision**: el adaptador se registra **siempre** como `@Component`, de modo que
`NotificationSenderRegistry` resuelve `brevo` aunque falten credenciales. Su estado de habilitación se
decide una vez, al construirse, a partir de las propiedades inyectadas:

- si falta la clave de acceso o el remitente verificado, el adaptador queda **deshabilitado** y emite
  **un** registro de nivel `WARN` al arrancar, que nombra el `providerId` y el motivo concreto (qué
  variable de entorno falta), sin ningún valor de credencial;
- `send(...)` de un adaptador deshabilitado devuelve `Mono.error(new ProviderDisabledException(
  providerId, motivo))` — una excepción nueva de `core`, hermana de `ProviderNotAvailableException`;
- el `DispatchNotificationService` **no cambia**: la señal de error aborta el `flatMap` antes de
  `saveAndPublish`, así que no se persiste ningún intento ni cambio de estado y el mensaje recorre el
  camino de fallo de despacho que ya existe (reintentos del consumidor → DLQ con la causa en el header).

**Detalle que la revisión debe verificar, no asumir**: `DispatchNotificationService.sendThrough` llama
`notification.markQueued()` **antes** de `sender.send(notification)`. Esa mutación es solo en memoria
sobre la instancia recién leída de Mongo; como `send` falla, `saveAndPublish` no se ejecuta y nada se
escribe ni se publica. La notificación almacenada sigue en `PENDING` y sin intentos, que es lo que
exigen FR-005 y la prueba. Es el mismo comportamiento observable que el de un proveedor sin adaptador
(HU2-088), y por eso esta historia no necesita tocar `core/usecase`.

**Rationale**: separa "error de configuración" de "el proveedor rechazó el envío", que es la distinción
que HU2-088 ya estableció y que el Principio IX exige. Marcar la notificación como fallida quemaría
notificaciones válidas por una causa ajena a ellas; devolver `ACCEPTED` sería una mentira; no registrar
el bean dejaría el motivo reducido a "no hay adaptador para brevo", que no nombra la causa real.

**Alternatives considered**:

- **A. No registrar el bean sin credenciales** (`@ConditionalOnProperty` sobre la clave): más simple,
  pero el rastro queda como `ProviderNotAvailableException: brevo`, indistinguible de un `providerId`
  mal escrito en el catálogo. Incumple "motivo explícito".
- **B. Registrar el bean y devolver `PERMANENT_FAILURE`**: descartado. Convierte un error de
  configuración en pérdida de notificaciones (Principio VIII) y confunde el diagnóstico.
- **C. Agregar `enabled` + `disabledReason` al catálogo**: es exactamente el modelo de HU2-046.
  Descartado aquí por invadir esa historia y por obligar a migrar el documento de catálogo, su siembra
  y su refresco para una necesidad que un adaptador puede resolver solo.
- **D. Exponer el motivo en `/actuator/health` como componente propio**: descartado por dos razones
  concretas. Primera, semántica: un proveedor deshabilitado a propósito no debe tumbar la sonda de
  readiness y sacar la réplica de rotación. Segunda, visibilidad: `management.endpoint.health.show-details`
  está en su valor por defecto (`never`) y `application.yml` no configura `management`, así que el
  detalle no se vería sin ampliar la superficie expuesta del actuator. El motivo queda visible en el
  registro de arranque, en el registro del fallo de despacho y en el header de causa de la DLQ, que son
  tres rastros suficientes y ya existentes. Si HU2-046 decide exponerlo por actuator, ese es su lugar.

**Alcance declarado**: esta historia entrega la deshabilitación con motivo **para un adaptador**. La
noción general de proveedor deshabilitado en el catálogo sigue siendo de HU2-046.

---

## Decisión 3 — Convivencia de `simulated` y `brevo` en el catálogo

**Decision**: el canal `EMAIL` pasa a declarar **los dos** proveedores, con la lista configurable por
entorno y un valor por defecto seguro:

```yaml
notification:
  catalog:
    channels:
      EMAIL:
        providers: ${NOTIFICATION_EMAIL_PROVIDERS:simulated,brevo}
```

Spring Boot enlaza una cadena separada por comas a `List<String>`, así que un único valor de entorno
(`NOTIFICATION_EMAIL_PROVIDERS=brevo,simulated`) basta para invertir la preferencia en producción sin
tocar código ni el formato del catálogo.

**Rationale**: cumple el criterio "el canal EMAIL lista a `brevo` entre sus proveedores" sin romper
desarrollo local ni integración continua, donde no hay credenciales: el preferente sigue siendo
`simulated` y `brevo` queda listado pero no elegido. Evita además el riesgo de despliegue que HU2-088
dejó anotado (un `providerId` en el catálogo sin adaptador que lo atienda): a partir de esta historia
**ambos** identificadores tienen adaptador registrado, así que `ProviderNotAvailableException` deja de
ser alcanzable por esta vía.

**Depende de Q2** (`spec.md § Clarifications`). Si el usuario prefiere `brevo` como preferente por
defecto en la configuración versionada, hay que invertir el valor por defecto y asumir que el flujo
local y el de CI necesitan `NOTIFICATION_EMAIL_PROVIDERS=simulated` explícito, o fallarán.

**Hallazgo que condiciona el despliegue (verificar antes de dar la historia por cerrada)**:
`ChannelCatalogSeeder.seed()` siembra **solo si la colección está vacía**
(`.count(...).filter(count -> count == 0)`). Por tanto, agregar `brevo` a la configuración **no**
actualiza el catálogo de un entorno que ya lo tiene sembrado (un Mongo de desarrollo reutilizado,
integración, producción). Consecuencias:

- las pruebas con Testcontainers arrancan con Mongo vacío, así que ven el catálogo nuevo: verde en CI
  aunque el entorno real no se actualice;
- cualquier entorno preexistente necesita un paso manual de actualización del documento
  `channel_catalog` del canal `EMAIL`.

**Decision**: se documenta ese paso manual en `quickstart.md` y se declara como limitación conocida
(Principio VII) en lugar de cambiar la siembra a "upsert". Convertir la siembra en upsert cambiaría el
comportamiento de una pieza compartida (pisaría cualquier edición del catálogo hecha en caliente, que es
justo lo que HU2-025/HU2-088 asumen posible) y merece su propia historia.
**Excepción documentada** — dueño: andrualv. Fecha de revisión: 2026-10-31.

---

## Decisión 4 — Mapeo de la respuesta del proveedor a `AttemptResult`

**Decision**: la clasificación es una función pura y total sobre el resultado de la llamada:

| Resultado de la llamada | `AttemptResult` | Razón |
|---|---|---|
| `201 Created` | `ACCEPTED` | La documentación del proveedor lo define como "enviado". |
| `202 Accepted` | `ACCEPTED` | La documentación lo define como "programado": el proveedor asumió la responsabilidad del envío. `ACCEPTED` significa "el proveedor aceptó", no "el destinatario lo recibió" — que es la misma semántica que ya tiene el proveedor simulado. |
| Resto de `2xx` | `ACCEPTED` | Conservador: el proveedor no rechazó. |
| `3xx` | `RECOVERABLE_FAILURE` | No se siguen redirecciones; es una respuesta inesperada. Si es permanente, los reintentos se agotan y la notificación termina en fallo trazable. |
| `400 Bad Request` | `PERMANENT_FAILURE` | Petición malformada o destinatario inválido: reintentar da el mismo resultado. |
| `401 Unauthorized`, `403 Forbidden` | `PERMANENT_FAILURE` | Criterio de aceptación explícito ("credenciales inválidas"). Ver riesgo abajo. |
| `402 Payment Required` | `RECOVERABLE_FAILURE` | Condición de cuenta, no de la notificación (**depende de Q3**). |
| `404 Not Found` | `PERMANENT_FAILURE` | Ruta o recurso inexistente: error de integración, no transitorio. |
| `408 Request Timeout` | `RECOVERABLE_FAILURE` | Fallo temporal. |
| `429 Too Many Requests` | `RECOVERABLE_FAILURE` | Límite de tasa alcanzado. |
| Resto de `4xx` | `PERMANENT_FAILURE` | Atribuible a la petición. |
| `5xx` | `RECOVERABLE_FAILURE` | Fallo temporal del proveedor. |
| Tiempo de espera del cliente agotado | `RECOVERABLE_FAILURE` | FR-007. |
| Error de conexión, DNS o TLS | `RECOVERABLE_FAILURE` | Fallo de transporte. |
| Cualquier otra excepción | `RECOVERABLE_FAILURE` | Conservador: reintentar es menos dañino que descartar. |

La clasificación vive en una clase propia (`BrevoResponseClassifier`), separada del adaptador, para que
las quince filas se puedan probar sin levantar un servidor ni un contexto de Spring y para acotar la
complejidad ciclomática del adaptador (RNF-15).

**Rationale**: el conjunto es total (no hay respuesta sin categoría) y el caso por defecto es el
conservador. Los tres grupos coinciden con los que ya consume `DispatchNotificationService`.

**Riesgo anotado, no resuelto**: clasificar `401/403` como permanente implica que una clave rotada o
vencida lleva a estado terminal a todas las notificaciones que se despachen mientras dure el problema.
Se acepta porque (a) es el criterio de aceptación literal de la historia, (b) el fallo es plenamente
trazable, y (c) `StatusTransitionPolicy` admite `FAILED → PENDING`, de modo que esas notificaciones se
pueden reencolar una vez corregida la credencial. Si el usuario prefiere tratarlo como error de
configuración (misma vía que Decisión 2), es un cambio de una fila de la tabla y una prueba.

**Alternatives considered**: mapear por el cuerpo del error del proveedor (campo `code`) en lugar del
código HTTP. Descartado: obliga a acoplarse a un catálogo de códigos no versionado del proveedor y a
parsear el cuerpo de error, que es precisamente el dato que no queremos registrar.

---

## Decisión 5 — Construcción del correo a partir de `Notification`

**Campos disponibles hoy** (`core/domain/Notification.java`): `notificationId`, `tenantId`,
`externalId`, `channelType`, `recipientId`, `recipient` (`Recipient(String address)`), `content`
(`NotificationContent(String subject, String body)` — **`subject` es opcional y puede ser `null`**),
`priority`, `acceptedAt`, `status`, `deliveryAttempts`, `version`.

**Decision**: el cuerpo de la petición se arma así:

| Campo del proveedor | Origen | Nota |
|---|---|---|
| `sender.email` | variable de entorno `BREVO_SENDER_EMAIL` | remitente verificado; sin él el proveedor queda deshabilitado (Decisión 2) |
| `sender.name` | variable de entorno `BREVO_SENDER_NAME` | opcional; se omite si está vacía |
| `to[0].email` | `notification.recipient().address()` | un único destinatario por notificación |
| `subject` | `notification.content().subject()` | si está vacío → fallo permanente sin llamar (Decisión 6) |
| `textContent` | `notification.content().body()` | texto plano (**depende de Q4**) |
| `headers["Idempotency-Key"]` | `notification.notificationId().value()` | Decisión 8 |

**Campos que NO se envían**: `tenantId`, `externalId`, `recipientId`, `priority`, `tags`, `params`,
`htmlContent`, `templateId`. Razón: `tenantId` y `externalId` son datos del cliente que no aportan nada
al envío y sí ampliarían lo que el tercero almacena; el resto queda fuera de alcance.

**Ningún campo nuevo hace falta en el dominio.** `ChannelRoute.contentSchema` existe pero está vacío en
la siembra actual y esta historia no lo usa.

---

## Decisión 6 — Notificación sin asunto

**Decision** (**depende de Q1**): si el asunto es nulo o está en blanco, el adaptador **no llama al
proveedor** y devuelve `Mono.just(AttemptResult.PERMANENT_FAILURE)`, registrando un motivo que nombra
el campo faltante (no su valor).

**Rationale**: el proveedor exige asunto para un correo transaccional sin plantilla; llamar sabiendo que
va a fallar gasta cuota y latencia. A diferencia de la falta de credenciales, esta condición es de **esa
notificación concreta**, así que sí corresponde un intento registrado y un estado terminal: es un dato
de entrada inválido, no un error de configuración del despliegue.

**Contraste deliberado con la Decisión 2**: falta de credenciales → error de configuración, sin intento;
falta de asunto → dato inválido de la notificación, con intento permanente. Que las dos rutas sean
distintas es intencional y el Principio IX lo exige.

**Alternatives considered**: asunto por defecto configurable (entrega un correo que el cliente no pidió);
validación en la aceptación (cambia el contrato público para todos los canales; hoy
`api-notificaciones.yaml` declara `subject` como `nullable: true` y no está en `required`).

---

## Decisión 7 — Tiempo de espera explícito

**Decision**: `notification.provider.brevo.timeout-ms` (variable `BREVO_TIMEOUT_MS`, por defecto
`10000`) fija `responseTimeout` del `HttpClient`; el tiempo de conexión se fija aparte en 5 s
(`BREVO_CONNECT_TIMEOUT_MS`, por defecto `5000`). Superar cualquiera de los dos se clasifica como
`RECOVERABLE_FAILURE` (Decisión 4).

**Rationale**: con un máximo de 3 intentos de despacho (`NOTIFICATION_DISPATCH_MAX_ATTEMPTS`), el peor
caso por notificación queda acotado en torno a 30 s de hilo de consumo ocupado, muy por debajo de
cualquier expectativa de bloqueo indefinido. El valor es configurable porque el número correcto depende
del entorno y de la latencia real del proveedor, que solo se conoce tras la prueba de humo.

**Alternatives considered**: `Mono.timeout(Duration)` encima de la llamada. Descartado como mecanismo
único: corta el `Mono` pero deja la conexión abierta del lado de reactor-netty. `responseTimeout` del
`HttpClient` cierra el canal, que es lo que de verdad libera el recurso. Se usa el del `HttpClient`.

---

## Decisión 8 — Clave de idempotencia

**Decision**: cada petición lleva `headers: { "Idempotency-Key": "<notificationId>" }` dentro del cuerpo
JSON (el proveedor documenta `headers` como un mapa de cabeceras a propagar, y su ejemplo usa
literalmente `"Idempotency-Key":"abc-123"`). El valor es el identificador de la notificación, estable
entre reintentos de la misma notificación y distinto entre notificaciones.

**Estado de la hipótesis**: la documentación del proveedor **no define la semántica** de esa clave — no
dice si deduplica, durante cuánto tiempo, ni qué responde ante una repetición. Por tanto:

- la prueba automatizada verifica **lo único verificable sin la cuenta real**: que la clave viaja, que
  es la misma en dos despachos de la misma notificación y distinta entre notificaciones (SC-009);
- **no** se afirma en ningún artefacto que el duplicado esté resuelto;
- la comprobación de si el proveedor realmente deduplica es un paso explícito de la prueba manual de
  humo (`quickstart.md`), enviando dos veces la misma clave y observando si llegan uno o dos correos.

**Riesgo residual documentado** (Principio VII): entrega "al menos una vez" + deduplicación no
garantizada = un correo puede duplicarse si el componente cae entre que el proveedor acepta y que el
resultado se persiste. Dueño: andrualv. Fecha de revisión, tras la prueba de humo: 2026-10-31.

**Alternatives considered**: deduplicación propia con una marca persistida antes de llamar al proveedor
("intento en curso"). Resolvería el caso de verdad, pero requiere decidir vencimiento y limpieza de esas
marcas y una escritura extra por despacho: es una historia propia, no un añadido a esta.

---

## Decisión 9 — Nada sensible en registros ni en mensajes internos

**Decision**:

- El adaptador registra, por despacho: `notificationId`, `tenantId`, `providerId`, la categoría de
  resultado y el código de estado HTTP. Nunca: clave de acceso, asunto, cuerpo ni dirección del
  destinatario.
- El `WebClient` se construye **sin** `wiretap` y sin filtros de registro de peticiones. Habilitar
  `logging.level.reactor.netty.http.client=DEBUG` volcaría cabeceras y cuerpo: queda anotado como
  advertencia operativa en `quickstart.md`, no como algo que el código pueda impedir.
- La clave se guarda en un `record` de propiedades de configuración. La superficie expuesta del actuator
  es la de por defecto (solo `health`), y en cualquier caso `configprops`/`env` sanean por nombre las
  claves que contienen `key`, `secret`, `password` o `token`, y la propiedad se llama `api-key`.
- No hay nada que cambiar en RabbitMQ: `NotificationRabbitPublisher.enqueueForDispatch` publica **solo**
  el `notificationId` como cuerpo, y `publish` serializa eventos de dominio que constan de
  `notificationId` + marca de tiempo. Ninguno transporta contenido ni credenciales. Lo que esta historia
  agrega es la **verificación automatizada** de esa propiedad, que hoy no existe.

**Cómo se verifica** (SC-004): la prueba E2E engancha un `ListAppender` de Logback al logger raíz
durante el despacho y afirma que ningún evento formateado contiene la clave de acceso, el asunto, el
cuerpo ni la dirección; y enlaza una cola temporal al exchange de eventos para afirmar lo mismo sobre
los mensajes publicados.

---

## Decisión 10 — Servidor que simula al proveedor en las pruebas

**Decision**: un servidor HTTP de pruebas propio, `FakeBrevoServer`, construido sobre
`com.sun.net.httpserver.HttpServer` (módulo `jdk.httpserver`, parte del JDK 21). Registra cada petición
recibida (ruta, cabeceras, cuerpo) y permite programar el código de estado, el cuerpo y un retardo. Se
arranca en el método `@DynamicPropertySource` de cada clase de prueba, que publica su puerto como
`notification.provider.brevo.base-url`.

**Rationale**: **cero dependencias nuevas**, que es el criterio del repositorio, y la llamada sigue
siendo HTTP real sobre sockets reales con el `WebClient` real — no un mock del cliente. Cubre lo único
que las pruebas necesitan del proveedor: código de estado, retardo y captura de la petición.

**Alternatives considered**:

- `com.squareup.okhttp3:mockwebserver`: es la opción estándar y **su versión ya está gestionada** por el
  BOM de Spring Boot 3.3.4 (importa `okhttp-bom` 4.12.0), así que no haría falta fijar versión.
  Descartada por su cadena transitiva: arrastra `kotlin-stdlib` y **JUnit 4** al classpath de pruebas de
  un proyecto que es JUnit 5 puro, lo que invita a errores de anotación mezclada y amplía la superficie
  de análisis de dependencias a cambio de ~60 líneas ahorradas. Queda registrada como el reemplazo
  natural si el servidor propio crece más allá de esas líneas.
- WireMock: mismo beneficio que MockWebServer pero además exige fijar y mantener su versión (no está en
  el BOM). Descartada.
- `MockServerContainer` de Testcontainers: agrega la descarga y el arranque de una imagen más a unas
  pruebas que ya levantan Mongo y RabbitMQ, para stubear un único endpoint. Descartada por costo.
- Un `RouterFunction` de prueba dentro del mismo contexto de Spring: cero dependencias, pero el puerto
  de la aplicación solo se conoce después de arrancar el contexto, mientras que la URL base del proveedor
  debe estar resuelta antes. Descartada por ese orden.

**Desvío consciente del patrón del repositorio**: el patrón dice "integración de adaptador con
Testcontainers contra el servicio real". Aquí el servicio real es un tercero de pago y el criterio de
aceptación prohíbe llamarlo desde CI, así que el equivalente más fiel posible es un servidor HTTP local
real. Queda dicho para que la revisión no lo lea como un mock de conveniencia.

---

## Decisión 11 — Prueba manual de humo con cuenta real, fuera de CI

**Decision**: el procedimiento vive en `specs/008-brevo-proveedor-correo/quickstart.md`, sección "Prueba
manual de humo". Incluye: variables de entorno a exportar, cómo invertir la preferencia del canal, el
`curl` de aceptación, qué observar (correo recibido, estado final, `providerId` del intento), el paso de
comprobación de la clave de idempotencia (Decisión 8) y una plantilla de registro del resultado (fecha,
quién, versión del componente, resultado).

**Rationale**: es un artefacto versionado de la historia, revisable en el PR, y no toca CI. Ninguna
prueba automatizada llama al proveedor real (SC-010).

---

## Decisión 12 — Cómo no mezclar el cambio ajeno de `application.yml`

**Contexto**: el árbol de trabajo tiene una modificación **ajena a esta historia** en
`infrastructure/src/main/resources/application.yml` (cambia `spring.application.name` de
`notification-service` a `notification-uco`) y otra en
`core/src/main/java/co/edu/uco/notification/core/domain/valueobject/Recipient.java`. Esta historia
necesita editar ese mismo `application.yml` (Decisión 3 y propiedades del proveedor), y `git add <ruta>`
prepara el archivo entero, incluidos los hunks ajenos.

**Decision**, por orden de preferencia:

1. **Antes** de la tarea que edita `application.yml`, el usuario commitea o guarda aparte su cambio de
   `spring.application.name`. Con el árbol limpio en ese archivo, el commit de la historia contiene solo
   sus hunks. Es la única opción que produce un historial correcto sin maniobras.
2. Si el cambio ajeno sigue presente cuando llegue esa tarea: el archivo **no se incluye en ningún
   commit**. La edición se deja en el árbol de trabajo y se reporta como paso manual pendiente, con su
   dueño y su fecha, en lugar de arrastrar un cambio ajeno al commit de la historia.

**Descartado**: `git add -p` (los flags interactivos no están disponibles en este entorno) y mover las
propiedades a un archivo o perfil aparte (`application-brevo.yml`) — no sirve, porque la lista de
proveedores del canal `EMAIL` que hay que ampliar vive en el bloque de catálogo del archivo principal y
un perfil adicional obligaría a activarlo en todos los entornos, incluidas las pruebas.

`Recipient.java` no se toca en esta historia.

---

## Decisión 13 — Fuera de alcance, con dueño y fecha (Principio VII)

Ninguna de estas tres historias existe todavía en el repositorio. Se declaran fuera de alcance y se
documentan las limitaciones que eso implica:

| Historia ausente | Limitación que deja | Dueño | Fecha de revisión |
|---|---|---|---|
| HU2-038 — batería de contrato de adaptadores | Las garantías del adaptador (clasificación, tiempo de espera, no filtrado) se verifican con pruebas propias de esta historia, no con una batería común; cuando la batería exista, este adaptador debe adoptarla. | andrualv | 2026-12-31 |
| HU2-052 — gestión de secretos por la plataforma | Las credenciales viajan por variable de entorno. Aceptable en desarrollo; insuficiente para producción, donde hacen falta rotación y almacén gestionado. | andrualv | 2026-11-30, antes del despliegue productivo |
| HU2-039 — límite de tasa por proveedor | Sin límite propio, una ráfaga puede provocar respuestas de límite de tasa del proveedor y, con ellas, reintentos masivos. No exponer el proveedor real a carga real antes de resolverlo. | andrualv | 2026-11-30, antes de exponer a carga real |

`resilience4j` ya está en `infrastructure/pom.xml` (lo usa el catálogo), de modo que HU2-039 no
necesitará dependencias nuevas; esta historia deliberadamente **no** lo usa para no adelantar decisiones
de esa historia.

---

## Decisión 14 — Lo que esta historia NO toca

- `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: no hay endpoint nuevo ni
  cambio de contrato público. El Principio II no aplica, y se deja dicho para que la revisión no lo lea
  como un contrato omitido.
- `RabbitConfig` y `RabbitRetryConfig`: el consumidor conserva su ack y su DLQ exactamente como están.
  Esta historia **no introduce ninguna excepción** a la regla de ack manual y DLQ, así que no hay nada
  que justificar por escrito en `plan.md` bajo ese título.
- `core/usecase/DispatchNotificationService`: no cambia (ver Decisión 2).
- `SimulatedNotificationProvider`: no cambia; sigue registrado y operativo (FR-013).
- `Recipient.java` y `spring.application.name`: cambios ajenos, ver Decisión 12.
