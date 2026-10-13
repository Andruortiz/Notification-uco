# Phase 0 — Research: Integrar Firebase Cloud Messaging como primer proveedor real de PUSH

**Feature**: 010-fcm-proveedor-push | **Date**: 2026-09-24

Cada decisión se numera y se referencia desde `plan.md`. Las preguntas Q1–Q4 de
`spec.md § Clarifications` están **pendientes de confirmación**; las decisiones que se apoyan en ellas lo
indican y dicen qué cambia si la respuesta final es otra.

El punto de partida es el patrón que dejaron los proveedores reales de correo (`specs/008-brevo-proveedor-correo/`)
y de SMS (`specs/009-twilio-proveedor-sms/`, en la rama `feature/HU2-090-twilio-sms`): un adaptador
dedicado por proveedor, propiedades en `infrastructure.config`, clasificador estático puro,
deshabilitación con motivo mediante `ProviderDisabledException`, un bean `WebClient` por proveedor con su
`@Qualifier` y un servidor HTTP del JDK (`FakeProviderServer`) que simula al proveedor en las pruebas.
Esta historia lo repite y solo documenta aquí lo que **difiere** o lo que el patrón no cubría. La
diferencia de fondo es la autenticación: el proveedor no acepta una clave fija en cada petición, sino una
autorización temporal que se obtiene firmando una aserción con la clave privada de una cuenta de servicio.

---

## Decisión 0 — Punto de partida de la rama: depende de HU2-090

**Hallazgo (verificado en el código, no asumido)**: `feature/HU2-091-fcm-push` se creó desde `develop`, y
`develop` **todavía no contiene** HU2-090. En esta rama:

- no existe `FakeProviderServer` (sigue llamándose `FakeBrevoServer`);
- `BrevoNotificationProvider` recibe su `WebClient` **sin** `@Qualifier`;
- `application.yml` no declara el canal SMS ni el bloque `notification.provider.twilio`;
- el directorio `specs/009-*` no existe (por eso esta historia usa `specs/010-*`: la numeración
  secuencial de esta rama habría repetido el `009` de HU2-090).

**Decision**: el diseño se construye **sobre HU2-090**. Antes de ejecutar `/speckit-tasks` y
`/speckit-implement`, esta rama debe contener HU2-090:

1. si HU2-090 ya está mergeada en `develop`: `git fetch` y rebase de esta rama sobre `origin/develop`;
2. si no: rebase sobre `origin/feature/HU2-090-twilio-sms` (rama apilada) y, cuando HU2-090 se mergee por
   squash, rebase otra vez sobre `develop` antes de abrir el PR.

La decisión de cuál aplicar es del usuario. Los commits de esta fase son solo de `specs/010-*`, así que
el rebase no tiene conflictos.

**Alternatives considered**: implementar ahora sobre `develop` repitiendo el renombrado del servidor
simulado y el `@Qualifier` del correo — duplicaría cambios de HU2-090 y garantizaría conflictos en el
merge del segundo PR.

---

## Decisión 1 — SDK oficial (`firebase-admin`) frente a la API REST v1 con `WebClient`

Esta vez sí hay una razón real para considerar el SDK: la autenticación del proveedor es OAuth 2.0 con
cuenta de servicio (firmar un JWT con la clave privada, canjearlo por un token de acceso de una hora,
renovarlo), algo que Brevo y Twilio no tenían. Se compararon tres opciones.

### (a) SDK oficial `com.google.firebase:firebase-admin`

A favor:

- Resuelve la autenticación, el canje y la renovación del token de acceso; mapea los errores a un
  enumerado (`MessagingErrorCode.UNREGISTERED`, `INVALID_ARGUMENT`, `QUOTA_EXCEEDED`, `UNAVAILABLE`,
  `INTERNAL`, `SENDER_ID_MISMATCH`, `THIRD_PARTY_AUTH_ERROR`).
- Mantenido por el proveedor; sigue los cambios del API sin intervención.

En contra:

- **Bloqueante por diseño.** `FirebaseMessaging.send(...)` bloquea; `sendAsync(...)` devuelve un
  `ApiFuture` que se ejecuta en un pool de hilos propio del SDK sobre `google-http-client`, que hace E/S
  bloqueante. Se puede envolver en un `Mono` sin bloquear el hilo reactivo, pero el trabajo real sale de
  reactor-netty: los tiempos de espera se configuran aparte (`FirebaseOptions` connect/read timeout), la
  concurrencia la limita un pool que no controlamos y el ADR-0003 quedaría cumplido solo en la forma.
  Requeriría una excepción documentada al ADR-0003 con dueño y fecha.
- **Cadena transitiva grande.** El artefacto arrastra `google-cloud-firestore` y `google-cloud-storage`
  (y con ellos gRPC, protobuf, Guava, OpenCensus/OpenTelemetry y un Netty sombreado), para usar un único
  endpoint. Aumenta la superficie de CVE, el tamaño de la imagen y el riesgo de conflictos de versión con
  el BOM de Spring Boot.
- **Probarlo contra un servidor simulado es incómodo.** La URL de envío está fijada dentro del SDK; no hay
  un emulador del proveedor de push ni una propiedad documentada para redirigirla. La única vía es
  sustituir el `HttpTransport` en `FirebaseOptions`, que es acoplarse a un detalle interno. El criterio
  de aceptación exige una E2E contra un servidor que simule al proveedor.
- **Estado global.** `FirebaseApp.initializeApp` registra instancias por nombre en un registro estático
  del proceso. Con los contextos de Spring cacheados entre clases de prueba (la misma causa de
  interferencias ya vista en las pruebas de la DLQ), aparece `FirebaseApp name [DEFAULT] already exists`
  o instancias que sobreviven a su contexto.

### (b) API REST v1 con `WebClient` y aserción JWT firmada con el JDK

```text
POST {token-url}                                 (autorización, ~1 vez por hora)
  grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<JWT RS256>
POST {base-url}/v1/projects/{project_id}/messages:send     (cada envío)
  Authorization: Bearer <access_token>
```

A favor:

- **Cero dependencias nuevas.** La firma RS256 es `java.security.Signature` (`SHA256withRSA`) sobre una
  clave PKCS#8 leída con `KeyFactory`; la codificación es `Base64.getUrlEncoder().withoutPadding()`; el
  JSON, Jackson (ya presente). No se implementa criptografía: se ensambla un JWS con primitivas del JDK.
- **Reactivo de punta a punta**, con los mismos tiempos de espera de reactor-netty para las dos llamadas.
  Sin excepción al ADR-0003.
- **Probable con `FakeProviderServer` sin cambiarlo**: `token-url` y `base-url` son propiedades; las
  pruebas levantan **dos** instancias (una simula el servicio de autorización, otra el de envío).
- Mismo patrón que los otros dos proveedores: un solo modelo mental para el equipo.

En contra:

- El flujo de autorización es código propio (unas 60 líneas): aserción, canje, caché y renovación. Es un
  flujo estándar y pequeño (RFC 7523; cinco *claims*: `iss`, `scope`, `aud`, `iat`, `exp`), pero un error
  en él solo se ve contra el proveedor real. Se mitiga con una prueba que **verifica la firma** con la
  clave pública del par generado en la prueba y comprueba cada *claim*, y con la prueba manual de humo
  contra un proyecto real (Decisión 13).
- Si el proveedor cambia el API v1, hay que adaptarlo a mano (con el SDK también habría que actualizar la
  versión).

### (c) Solo la librería de autenticación (`google-auth-library-oauth2-http`) + `WebClient` para el envío

A favor: delega aserción, canje y renovación en código del proveedor; respeta el `token_uri` del JSON,
así que también se puede probar contra un servidor simulado.

En contra: `refreshIfExpired()`/`getRequestMetadata()` son bloqueantes (sobre `google-http-client`) y
exigirían `subscribeOn(boundedElastic)` — un salto de hilo por renovación, aceptable pero es la misma
excepción al ADR-0003 en pequeño; arrastra Guava, `google-http-client`, OpenCensus y `auto-value`; y el
tiempo de espera del canje se configura por otra vía (`HttpTransportFactory`), no con las propiedades del
proveedor.

### Decision

**(b) API REST v1 con `WebClient` y aserción firmada con el JDK.** La única razón de peso para el SDK era
la autenticación, y resulta ser un flujo pequeño, estándar y totalmente verificable con primitivas del
JDK; a cambio, el SDK traería E/S bloqueante fuera de reactor-netty (excepción al ADR-0003), una cadena
transitiva de decenas de artefactos para un único endpoint, estado global que choca con la caché de
contextos de Spring en las pruebas y ninguna forma limpia de probarlo contra un servidor simulado, que es
un criterio de aceptación. La opción (c) queda como **plan de respaldo**: si la prueba de humo revela un
detalle del canje que no conviene mantener a mano, se sustituye solo el obtenedor de autorización
(Decisión 4) sin tocar adaptador, clasificador ni pruebas E2E.

---

## Decisión 2 — Contrato de salida: envío

```text
POST {base-url}/v1/projects/{project_id}/messages:send
Authorization: Bearer <access_token>
Content-Type: application/json

{"message":{"token":"<recipient.address>","notification":{"title":"<subject>","body":"<body>"}}}
```

- `project_id` sale de la cuenta de servicio (Q3: un único proyecto por despliegue). No se añade una
  variable para sobrescribirlo: la cuenta de servicio que genera la consola pertenece al proyecto.
- `title` se omite cuando la notificación no trae asunto (`@JsonInclude(NON_NULL)`), FR-012.
- No se envían `data`, `android`, `apns`, `webpush`, `fcm_options`, `topic` ni `condition`, ni ningún dato
  del cliente (`tenantId`, `externalId`, `recipientId`, `priority`). Fuera de alcance (spec.md).
- Detalle en `contracts/fcm-http-v1-api.md`.

**Por qué mensaje de notificación y no de datos**: el sistema operativo lo muestra sin código propio de
la aplicación cliente (spec.md, Assumptions). Un mensaje de datos exige que la aplicación lo interprete y
no hay en el contrato público un campo para esos datos.

---

## Decisión 3 — Credenciales: medios de entrega, carga y deshabilitación con motivo (Q4)

**Propiedades** (`FcmProviderProperties`, en `infrastructure.config`, igual que las de los otros dos
proveedores para no crear el ciclo `adapter -> config -> adapter` que detecta `ModularityTests`):

```text
@ConfigurationProperties("notification.provider.fcm")
record FcmProviderProperties(String credentialsJson, String credentialsFile, String baseUrl,
                             String tokenUrl, Long timeoutMs, Long connectTimeoutMs)
```

- Valores por defecto en el constructor compacto con `Long` envuelto (evita
  `BX_UNBOXING_IMMEDIATELY_REBOXED`). Sin colecciones: `EI_EXPOSE_REP` no aplica.
- `toString()` sobrescrito: **nunca** incluye `credentialsJson` (la clave privada entera) ni
  `credentialsFile`.

**Carga** (`FcmCredentials.load(properties)`, en `infrastructure.config`, expuesta como bean por
`FcmProviderConfig`): produce **o** una `FcmServiceAccount` **o** un motivo de deshabilitación, nunca
ambos. Se evalúa una vez al arrancar, en el hilo de arranque de Spring (la lectura del archivo es E/S de
arranque, no del hilo reactivo). Primer motivo aplicable, en este orden:

| # | Condición | Motivo (nombra propiedad y variable, nunca el valor) |
|---|---|---|
| 1 | `credentials-json` **y** `credentials-file` presentes | ambiguo: definir solo uno (Q4) |
| 2 | ninguno presente | falta `FCM_CREDENTIALS_JSON` o `FCM_CREDENTIALS_FILE` |
| 3 | archivo inexistente o ilegible | `credentials-file` ilegible |
| 4 | contenido no es JSON | credencial no es JSON válido |
| 5 | `type` distinto de `service_account` | se esperaba una cuenta de servicio |
| 6 | falta `project_id`, `client_email` o `private_key` | nombra el campo ausente |
| 7 | `private_key` no es una clave RSA PKCS#8 legible | clave privada ilegible |

- **Nunca** se registra el mensaje de la excepción de Jackson ni de `KeyFactory`: el de Jackson incluye
  un fragmento del texto de entrada, que aquí es la clave privada. El motivo es un texto fijo por fila.
- Validar la clave al arrancar (fila 7) y no en el primer envío evita, como en el SMS, que un error de
  despliegue queme cada notificación como fallo permanente (Principio VIII): deshabilitado, la
  notificación queda intacta y termina en la DLQ con la causa.
- **Archivo montado**: se lee con `FileSystemResource#getContentAsString(UTF_8)`. Riesgo: FindSecBugs
  puede señalar `PATH_TRAVERSAL_IN` por una ruta que viene de configuración. La ruta la fija el operador,
  no un usuario; si SpotBugs la señala, se documenta en `plan.md` una exclusión acotada a esa clase y ese
  patrón, con su justificación, y se reporta al usuario antes de añadirla (no se silencia).
- **`FcmServiceAccount`** no es un `record` y **no expone la clave privada**: guarda `projectId`,
  `clientEmail` y la `PrivateKey`, y ofrece `sign(byte[])` que devuelve la firma RS256. Así la clave no sale
  del objeto, no hay `EI_EXPOSE_REP` por devolverla y su `toString()` no la imprime (el `toString()` de
  algunas implementaciones de `PrivateKey` del JDK vuelca módulo y exponente).
- Si hay motivo, el adaptador se registra igual como `@Component`, emite **un** `WARN` al construirse y
  cada `send(...)` devuelve `Mono.error(new ProviderDisabledException(fcm, motivo))`. **No** se crea una
  excepción nueva: `ProviderDisabledException` ya existe en `core` y es genérica.

**Si Q4 cambia**: "solo variable" o "solo archivo" eliminan una propiedad y las filas 1 y 3; "ambos con
prioridad" cambia la fila 1 de motivo a regla de precedencia. Nada más se mueve.

---

## Decisión 4 — Autorización temporal: aserción, canje, caché y renovación

**Obtenedor de autorización** `FcmAccessTokenProvider` (clase del paquete `adapter.out.provider`, no bean:
la construye el adaptador con su `WebClient`, la cuenta de servicio y las propiedades).

- **Aserción**: cabecera `{"alg":"RS256","typ":"JWT"}`; *claims* `iss = client_email`,
  `scope = https://www.googleapis.com/auth/firebase.messaging`, `aud = token-url`, `iat = ahora`,
  `exp = iat + 3600`. Firma con `FcmServiceAccount.sign`. Firmar es CPU (del orden de un milisegundo) una
  vez por hora: no bloquea.
- **Canje**: `POST {token-url}` con formulario `grant_type` + `assertion`. Respuesta
  `{"access_token":"...","expires_in":3599,"token_type":"Bearer"}` leída en un record que **no** se
  registra nunca.
- **Caché**: el token se reutiliza hasta `expires_in − 300 s` (margen de renovación, FR-011). Una sola
  obtención en vuelo aunque haya envíos concurrentes (`Mono.cacheInvalidateIf` o equivalente sobre un
  `Mono` compartido, reactor-core 3.6). Un canje **fallido no se memoriza**: el siguiente envío lo
  reintenta.
- **Invalidación**: una respuesta `401` del envío invalida el token en caché, para que el siguiente envío
  obtenga otro (cubre una revocación o un reloj desviado).
- **Fallo del canje**: se clasifica con la **misma tabla** de la Decisión 5 a partir del código HTTP del
  servicio de autorización (credenciales rechazadas `400 invalid_grant` / `401 invalid_client` → permanente;
  `5xx`, `429`, tiempo agotado, conexión → recuperable). En ningún caso se llega a enviar el push.
- **Prueba de la reutilización (SC-008)** sin reloj inyectado: el servidor simulado de autorización
  responde `expires_in` de 3600 s y diez envíos producen **una** petición de autorización; con
  `expires_in` menor que el margen, cada envío obtiene una nueva.

**Alternatives considered**: renovar en segundo plano con un temporizador — añade un hilo de vida propia
y estado de arranque sin ventaja frente a renovar al primer uso tras el margen; pedir la autorización en
cada envío — duplica la latencia y agota la cuota del servicio de autorización.

---

## Decisión 5 — Clasificación de la respuesta

**Decision**: `FcmResponseClassifier`, clase final con métodos estáticos puros (`classifyStatus(int)`,
`classifyError(Throwable)`), igual que los otros dos. **Decide el código HTTP**, como con SMS (Q4 de
HU2-090). El `errorCode` del proveedor se registra, no clasifica. Aquí no se pierde nada: el proveedor
asigna a cada `errorCode` un código HTTP distinto y la tabla coincide con la categoría que pide el
criterio de aceptación.

| Resultado | `AttemptResult` | `errorCode` del proveedor que cae aquí |
|---|---|---|
| `2xx` | `ACCEPTED` | — (respuesta `{"name":"projects/.../messages/<id>"}`) |
| `3xx` | `RECOVERABLE_FAILURE` | inesperado; no se siguen redirecciones |
| `400` | `PERMANENT_FAILURE` | `INVALID_ARGUMENT` (identificador mal formado, contenido > 4096 bytes, campo inválido); en el canje, `invalid_grant` |
| `401` | `PERMANENT_FAILURE` | `UNAUTHENTICATED`, `THIRD_PARTY_AUTH_ERROR` (credencial de APNs/web push del proyecto); en el canje, `invalid_client`. Criterio de aceptación literal: credenciales inválidas. Invalida la caché (Decisión 4) |
| `403` | `PERMANENT_FAILURE` | `SENDER_ID_MISMATCH` (identificador de otro proyecto, Q3), `PERMISSION_DENIED` |
| `404` | `PERMANENT_FAILURE` | `UNREGISTERED` (aplicación desinstalada, permiso revocado, identificador caducado), `NOT_FOUND` |
| `408` | `RECOVERABLE_FAILURE` | tiempo de espera del servidor |
| `429` | `RECOVERABLE_FAILURE` | `QUOTA_EXCEEDED`, `RESOURCE_EXHAUSTED` |
| resto de `4xx` | `PERMANENT_FAILURE` | atribuible a la petición |
| `500` | `RECOVERABLE_FAILURE` | `INTERNAL` |
| `503` y resto de `5xx` | `RECOVERABLE_FAILURE` | `UNAVAILABLE` |
| tiempo de espera del cliente agotado | `RECOVERABLE_FAILURE` | FR-007 |
| conexión, DNS, TLS, cualquier otra excepción | `RECOVERABLE_FAILURE` | conservador |

**`UNREGISTERED` nunca se reintenta**: es `PERMANENT_FAILURE`, la notificación termina `FAILED` y el
consumidor hace ack. Avisar al sistema cliente de que ese identificador quedó inválido **no** se hace en
esta historia (spec.md, Out of Scope): hoy el intento persistido guarda solo resultado, origen y
proveedor, no el motivo, así que el cliente ve `FAILED` sin saber que fue por identificador inválido.
Una historia aparte podría (a) persistir el código de error del proveedor en el intento y exponerlo en la
consulta, o (b) publicar un evento "identificador de dispositivo invalidado" para que el cliente lo
depure. Se anota como riesgo con dueño y fecha (Decisión 14).

**Consecuencia aceptada del `401` permanente**: una credencial revocada deja fallidas las notificaciones
que se despachen mientras dure el problema, igual que con correo y SMS; recuperables a mano porque
`StatusTransitionPolicy` admite `FAILED → PENDING`. Un `401` por un token caducado es prácticamente
imposible con el margen de 300 s, y si ocurre, la invalidación de la caché evita que se repita.

**Lectura de la respuesta**: `FcmSendResponse(String name)` en la aceptación y
`FcmErrorResponse(Error error)` con `Error(String status, List<Detail> details)` y
`Detail(String errorCode)` en el rechazo, con `@JsonIgnoreProperties(ignoreUnknown = true)` y **sin**
`message`: el texto libre del error nunca llega a un objeto Java. `details` se copia con `List.copyOf` en
el constructor compacto y en el accesor sobrescrito (`EI_EXPOSE_REP`/`EI_EXPOSE_REP2`). Cuerpo ausente o
ilegible → misma categoría (decide el HTTP), sin código que registrar.

---

## Decisión 6 — El identificador de dispositivo como destinatario (Q1, punto central)

**Hallazgos en el código**:

- `Recipient(String address)` solo exige no vacío; no tiene longitud máxima ni forma. El mensaje de la
  precondición en `HEAD` es genérico ("Recipient address must not be blank").
- La dirección se persiste en `NotificationDocument.recipientAddress` y **no** se expone en ninguna
  respuesta de consulta, historial ni actualización en vivo (todas usan `recipientId`). No hay índice
  sobre ella.
- El contrato público no fija longitud ni formato de `recipientAddress`.
- Un identificador de dispositivo del proveedor mide hoy entre ~150 y ~200 caracteres (letras, dígitos,
  `_`, `-`, `:`), pero el proveedor lo declara opaco y no documenta ni garantiza su formato.

**Decision (Q1, pendiente de confirmación)**: **cero cambios de dominio y ninguna comprobación de forma**.
El adaptador envía `notification.recipient().address()` tal cual en `message.token`. Un identificador
inválido o de otra naturaleza termina en `PERMANENT_FAILURE` por la respuesta del proveedor
(`400 INVALID_ARGUMENT` o `404 UNREGISTERED`), tras una única llamada, sin reintentos. Por tanto **no hay
camino de "destinatario inválido sin llamar"** en este adaptador; el único camino sin llamada es el de
proveedor deshabilitado, y ese sí se verifica con `requests().isEmpty()` en **los dos** servidores
simulados (Decisión 11).

**Por qué no la comprobación laxa del SMS**: el formato internacional de un teléfono es un estándar
estable; el identificador de dispositivo no. Una expresión propia (por ejemplo `^[A-Za-z0-9_:-]+$`)
atraparía un correo enviado al canal equivocado, pero si el proveedor emite algún día un carácter nuevo,
cada notificación a esos dispositivos terminaría en fallo permanente sin haber llamado — el falso negativo
cuesta la notificación, mientras que el falso positivo que evita solo cuesta una llamada.

**"Retoque justo a tiempo" evaluado y descartado**: como con `RecipientId`/`providerId` en fases
anteriores, se revisó si `Recipient` necesitaba un ajuste. No lo necesita: acepta el identificador sin
forzar ninguna validación que no le corresponda. Una longitud máxima en `Recipient` sería razonable como
defensa general, pero no la exige esta historia y afectaría a todos los canales; queda fuera.

**Observación para el usuario, no para esta historia**: el árbol de trabajo tiene un cambio ajeno sin
commitear en `Recipient.java` que reemplaza el mensaje genérico por "El correo electronico no puede estar
vacio". `Recipient` es el destinatario de **todos** los canales; con SMS y PUSH disponibles, una
notificación push con destinatario vacío recibiría un mensaje que habla de correo. No se toca ni se
commitea aquí; se reporta.

**Si Q1 cambia**: a "comprobación laxa en el despacho" → se añade una expresión y una rama
`PERMANENT_FAILURE` sin llamada (con su prueba de `requests().isEmpty()`), sin tocar nada más; a
"validar en la aceptación" → historia propia de validación de destinatario por canal.

---

## Decisión 7 — Canal PUSH en el catálogo y su forma de contenido (Q2)

**Contexto**: el catálogo declara `EMAIL` (y `SMS` tras HU2-090). `PUSH` **no existe** en el código ni en
las pruebas (búsqueda de `PUSH` sin resultados): una notificación push hoy se rechaza con
`ChannelNotAvailableException`. `ContentSchemaValidator` construye `{"subject": <texto o null>, "body":
<texto>}` y lo valida en la aceptación contra el `contentSchema` del canal.

**Decision**:

```yaml
notification:
  catalog:
    channels:
      PUSH:
        providers: ${NOTIFICATION_PUSH_PROVIDERS:simulated,fcm}
        content-schema: '{"type":"object","required":["body"],"properties":{"subject":{"maxLength":100},"body":{"type":"string","maxLength":900}}}'
```

- `subject` **sin** `type`: `maxLength` solo aplica a textos, así que un asunto ausente (`null`) pasa y
  uno de 101 caracteres o más se rechaza.
- **Por qué 100 + 900**: el proveedor admite como máximo 4096 bytes de contenido. El validador cuenta
  puntos de código; en UTF-8 cada uno ocupa hasta 4 bytes, así que 1000 puntos de código ocupan como
  máximo 4000 bytes, más unas decenas de bytes de claves JSON. Los caracteres de control, que el JSON
  escapa en 6 bytes, son el único caso residual que podría excederlo; el proveedor lo rechazaría como
  `400` → permanente (spec.md, Edge Cases).
- Rechazo en la aceptación (`InvalidContentException` → `400`), nada persistido, nunca truncado; mismo
  mecanismo que el límite de SMS. **Cero cambios en `core`.**

**Siembra en entornos existentes**: `ChannelCatalogSeeder` actúa solo sobre un catálogo vacío. Un
entorno ya sembrado **no** obtiene el canal PUSH; paso manual en `quickstart.md`. Excepción ya abierta
por las historias de correo y SMS — dueño: andrualv. Fecha de revisión: 2026-10-31.

**Si Q2 cambia**: los números viven en un literal de `application.yml` y en las pruebas de límite;
"sin límite propio" elimina el literal y SC-013.

---

## Decisión 8 — Tercer bean `WebClient`: `fcmWebClient` con su `@Qualifier`

**Hallazgo (verificado en `feature/HU2-090-twilio-sms`)**: hay dos beans `WebClient` (`brevoWebClient`,
`twilioWebClient`) y cada adaptador declara `@Qualifier` con el nombre del suyo; ningún otro punto de
producción inyecta `WebClient` por tipo. El proyecto no compila con `-parameters`, así que Spring no
desambigua por nombre de parámetro.

**Decision**: `FcmProviderConfig` declara `@Bean WebClient fcmWebClient(FcmProviderProperties)` con
`responseTimeout` y `CONNECT_TIMEOUT_MILLIS` (sin `wiretap`), y el constructor de
`FcmNotificationProvider` usa `@Qualifier("fcmWebClient")`. El mismo `WebClient` sirve para el canje y
para el envío (la URL del canje es absoluta), de modo que ambas llamadas tienen el mismo tiempo de espera.
Verificación inmediata tras crearlo: `BrevoEmailDeliveryE2ETest` y `TwilioSmsDeliveryE2ETest` arrancan su
contexto con tres `WebClient`.

---

## Decisión 9 — Tiempo de espera explícito

**Decision**: igual que los otros dos. `notification.provider.fcm.timeout-ms` (`FCM_TIMEOUT_MS`, por
defecto `10000`) → `responseTimeout`; `connect-timeout-ms` (`FCM_CONNECT_TIMEOUT_MS`, por defecto `5000`) →
`CONNECT_TIMEOUT_MILLIS`. Superarlo → `RECOVERABLE_FAILURE`, en el canje o en el envío.

Cuando hay que renovar la autorización, un envío hace dos llamadas en serie, cada una acotada: el peor
caso es el doble del tiempo de espera (20 s por defecto) una vez por hora. Se documenta; no se añade un
`Mono.timeout` global porque no cierra el canal de reactor-netty (motivo ya registrado en el correo).

**Prueba (SC-006)**, con aserción explícita de `Duration` en los dos puntos: servidor de envío con retardo
de 3 s contra 500 ms de espera → resultado en menos de 2 s y `RECOVERABLE_FAILURE`; servidor de
autorización con el mismo retardo → igual, y el servidor de envío con **cero** peticiones.

---

## Decisión 10 — Enmascarado y registros

**Enmascarado del identificador**: `***` + últimos 4 caracteres; `***` si tiene 4 o menos, o es nulo.
No revela la longitud. Solo lo usa el adaptador, así que es un método privado del adaptador (no va a
`utils`: `PhoneNumbers.mask` conserva solo dígitos y no sirve para un texto alfanumérico).

**Registros del adaptador** (Principio IX, FR-008):

- aceptación: `notificationId`, `tenantId`, `providerId`, `result`, `httpStatus`, `providerMessageId`
  (último segmento de `name`), `recipient` enmascarado;
- rechazo del proveedor: lo anterior sin `providerMessageId` y con `providerErrorCode` (`errorCode` de
  `details`, o `status` si no hay), nunca `message`;
- fallo del canje: `notificationId`, `tenantId`, `providerId`, `result`, `stage=authorization`,
  `httpStatus`, sin el cuerpo de la respuesta (puede traer `error_description` con el `client_email`);
- error de transporte: `notificationId`, `tenantId`, `providerId`, `result`, `errorType` (nombre simple
  de la excepción, nunca su mensaje).

Nunca: la credencial ni ninguna parte de ella (`private_key`, `client_email`, `private_key_id`), la
aserción firmada, el token de acceso, la cabecera `Authorization`, el título, el cuerpo, el
identificador completo.

**Mensajes internos**: RabbitMQ no cambia (el mensaje de despacho lleva solo `notificationId`; los eventos
de dominio, identificador y marca de tiempo). La historia añade la verificación automatizada.

**Advertencia operativa**: no activar `reactor.netty.http.client=DEBUG` ni `wiretap`: volcaría el token
de acceso, la aserción, el identificador y el contenido.

---

## Decisión 11 — Pruebas: dos servidores simulados y credenciales generadas en la prueba

**Servidor simulado**: `FakeProviderServer` (HU2-090) se reutiliza **sin cambios**. Como enruta todas las
rutas a la misma respuesta programada, las pruebas levantan **dos** instancias: `authServer` (simula el
servicio de autorización; responde `200` con un token inventado) y `fcmServer` (simula el envío). Las
propiedades `token-url` y `base-url` apuntan a cada una.

**Verificación de "cero llamadas"**: en el camino de proveedor deshabilitado (el único sin llamada, ver
Decisión 6), `FcmNotificationProviderTest` y `FcmDisabledProviderE2ETest` afirman
`authServer.requests().isEmpty()` **y** `fcmServer.requests().isEmpty()` además del resultado. En el
camino de fallo del canje se afirma `fcmServer.requests().isEmpty()`.

**Credenciales de prueba sin literales con forma de secreto** (lección de HU2-090, donde el escaneo de
secretos bloqueó un push por literales sintéticos con formato válido):

- el par RSA se genera **en tiempo de ejecución** con `KeyPairGenerator` (2048 bits) en una ayuda de
  pruebas `FcmTestCredentials`; el repositorio no contiene ninguna clave PEM;
- el JSON de la cuenta de servicio se construye con `ObjectMapper` a partir de esa clave; las claves y
  valores con forma reconocible se arman por concatenación, de modo que el diff nunca contenga el patrón
  completo: `"service" + "_account"`, `"private" + "_key"`, `"-----BEGIN " + "PRIVATE KEY-----"`, y un
  correo de cuenta de servicio inventado como `"fcm-test@demo-project" + ".iam.gserviceaccount" + ".com"`;
- los tokens de acceso y de dispositivo inventados tampoco imitan los prefijos reales que el proveedor usa
  para cada uno: se usan valores como `"test-access-" + "token"` y `"device-" + "token-demo-1234"`;
- la clave pública del mismo par verifica la firma de la aserción en `FcmAccessTokenProviderTest`.

**Por qué no otra pieza en `utils`**: nada de esta historia necesita compartirse entre `adapter` y
`config` salvo la cuenta de servicio, que vive en `config` y el adaptador ya puede usar.

---

## Decisión 12 — Idempotencia frente al proveedor

**Decision**: no se envía ninguna clave de idempotencia; el API de envío no ofrece deduplicación por una
clave del cliente. **Riesgo residual documentado** (Principio VII): entrega "al menos una vez" → un push
puede duplicarse si el componente cae entre que el proveedor acepta y que el resultado se persiste, o si
la respuesta no llega antes del tiempo de espera. Dueño: andrualv. Fecha de revisión: 2026-10-31. La
mitigación real (marca persistida de "intento en curso" antes de llamar) es común a todos los proveedores
y merece su propia historia.

---

## Decisión 13 — Prueba manual de humo con un proyecto real, fuera de CI

Procedimiento en `quickstart.md § 3`: crear o usar un proyecto del proveedor, generar la clave de una
cuenta de servicio, obtener un identificador de dispositivo desde una aplicación de prueba (la forma más
barata es una página web de prueba con el SDK web del proveedor, o la aplicación de ejemplo de Android;
cómo la aplicación obtiene el identificador **no** es parte de esta historia), exportar la credencial por
**archivo** y, en otra ejecución, por **variable**, enviar un push, y verificar llegada, estado
`DELIVERED`, `providerId = fcm`, registro enmascarado y ausencia de secretos. Casos negativos baratos:
identificador inventado → `FAILED` con `providerErrorCode=INVALID_ARGUMENT`; identificador de una
aplicación desinstalada → `FAILED` con `UNREGISTERED`; cuerpo de 901 caracteres → `400`. Plantilla de
registro del resultado, sin credenciales ni identificadores completos.

La prueba de humo es además la validación del flujo de autorización propio (Decisión 1): si falla ahí y
no en las pruebas, se activa el plan de respaldo (c).

---

## Decisión 14 — Fuera de alcance, con dueño y fecha (Principio VII)

| Historia o capacidad ausente | Limitación que deja | Dueño | Fecha de revisión |
|---|---|---|---|
| HU2-038 — batería de contrato de adaptadores | Garantías verificadas con pruebas propias. | andrualv | 2026-12-31 |
| HU2-052 — gestión de secretos por la plataforma | Credencial por variable o archivo montado; sin rotación gestionada. | andrualv | 2026-11-30 |
| HU2-039 — límite de tasa por proveedor | Ráfagas → `429` → reintentos masivos. | andrualv | 2026-11-30 |
| Aviso al cliente de identificadores invalidados (`UNREGISTERED`) | El cliente ve `FAILED` sin motivo y sigue enviando al mismo identificador. | andrualv | 2026-12-31 |
| Validación del destinatario por canal en la aceptación (Q1) | Un destinatario mal dirigido cuesta una llamada y se descubre por el estado. | andrualv | 2026-12-31 |
| Credenciales por tenant (Q3) | Un solo proyecto del proveedor por despliegue. | andrualv | 2026-12-31 |
| Push duplicado sin idempotencia del proveedor (Decisión 12) | Riesgo residual "al menos una vez". | andrualv | 2026-10-31 |
| Canal PUSH ausente en entornos ya sembrados (Decisión 7) | Alta manual documentada. | andrualv | 2026-10-31 |

---

## Decisión 15 — Cómo no mezclar los cambios ajenos del árbol de trabajo

Mismo procedimiento que el usuario aprobó en las historias de correo y SMS: `application.yml` tiene un
cambio ajeno (`spring.application.name`) y esta historia lo edita.

1. Antes de la tarea que edita `application.yml`: `git stash push -- infrastructure/src/main/resources/application.yml`.
2. Editar y commitear solo los cambios de esta historia.
3. Al terminar la historia: `git stash pop`.
4. Si el `pop` entra en conflicto, no se resuelve dentro de la historia: se reporta el comando exacto.

`Recipient.java` no se toca ni se commitea (ver Decisión 6).

---

## Decisión 16 — Contrato público: solo descripciones

`api-notificaciones.yaml` no gana ni pierde endpoints, esquemas, campos ni códigos. Se actualizan **antes**
del código (Principio II) las descripciones que quedarían falsas o incompletas — `channelType` (EMAIL,
SMS y PUSH), `recipientAddress` (en PUSH, el identificador de dispositivo que el proveedor emitió a la
aplicación, opaco, sin validación de forma), `subject` (en PUSH es el título visible, hasta 100
caracteres) y `body` (en PUSH, hasta 900). Detalle en `contracts/api-notificaciones-cambios.md`.

---

## Decisión 17 — Lo que esta historia NO toca

- `core`: **cero cambios** (`Recipient`, `NotificationContent`, `ContentSchemaValidator`,
  `NotificationSenderPort`, `NotificationSenderRegistry`, `ProviderDisabledException`,
  `DispatchNotificationService`).
- `RabbitConfig`, `RabbitRetryConfig`, `NotificationDispatchListener`: el consumidor conserva ack manual y
  DLQ; no hay excepción que justificar.
- `SimulatedNotificationProvider`, adaptadores de correo y SMS, sus clasificadores y `FakeProviderServer`:
  sin cambios.
- `ChannelCatalogSeeder`, `ChannelCatalogRefresher`: sin cambios.
- `pom.xml`: sin dependencias nuevas.
