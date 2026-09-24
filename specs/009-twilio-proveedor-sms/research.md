# Phase 0 — Research: Integrar Twilio como primer proveedor real de SMS

**Feature**: 009-twilio-proveedor-sms | **Date**: 2026-09-24

Cada decisión se numera y se referencia desde `plan.md`. Las preguntas Q1–Q4 de
`spec.md § Clarifications` están **pendientes de confirmación**; las decisiones que se apoyan en ellas
lo indican y dicen qué cambia si la respuesta final es otra.

El punto de partida es el patrón que dejó el proveedor real de correo (`specs/008-brevo-proveedor-correo/`,
ya integrado en `develop`): un adaptador dedicado por proveedor, propiedades en `infrastructure.config`,
clasificador estático puro, deshabilitación con motivo mediante `ProviderDisabledException` y un
servidor HTTP del JDK que simula al proveedor en las pruebas. Esta historia lo repite y solo documenta
aquí lo que **difiere** o lo que el patrón no cubría.

---

## Decisión 1 — Contrato de salida: API REST de mensajes, formulario y autenticación básica

**Decision**: el adaptador llama a

```text
POST {base-url}/2010-04-01/Accounts/{accountSid}/Messages.json
Content-Type: application/x-www-form-urlencoded
Authorization: Basic base64(accountSid:authToken)

To=<recipient.address>&From=<from-number>&Body=<content.body>
```

con un `WebClient` reactivo (ADR-0003), igual que el proveedor de correo. Detalle en
`contracts/twilio-messages-api.md`.

**Diferencias con el proveedor de correo que el diseño debe respetar**:

- El cuerpo **no es JSON** sino formulario (`BodyInserters.fromFormData`), así que no hace falta un
  `record` de petición: se arma un `MultiValueMap` de tres campos.
- La credencial viaja en **dos** sitios: el identificador de cuenta en la **ruta** y la pareja
  identificador:token en la cabecera `Authorization`. Consecuencia para el no filtrado (Decisión 9): no
  basta con no registrar cabeceras; tampoco puede registrarse la URL de la petición, y las pruebas deben
  buscar también la forma codificada en base64 de la pareja.
- La respuesta sí se lee (a diferencia del proveedor de correo, que solo usaba el código de estado),
  pero **solo** para extraer dos campos que ayudan al diagnóstico y no son sensibles: `sid` en una
  aceptación y `code` numérico en un rechazo (Decisión 5). Nunca el campo `message` del error, que el
  proveedor redacta incluyendo el número completo.

**Alternatives considered**:

- SDK oficial del proveedor: descartado por la misma razón que con el correo — cliente bloqueante,
  cadena transitiva propia y acoplamiento a su versión para un único endpoint.
- Servicio de mensajería del proveedor (`MessagingServiceSid` en lugar de `From`): permite grupos de
  números y remitentes alfanuméricos, pero el criterio de aceptación habla de "número de origen" y es un
  recurso más que configurar en la cuenta. Fuera de alcance (spec.md, Out of Scope).
- Claves de API del proveedor (`SK...` + secreto) en lugar del token de la cuenta: son la práctica
  recomendada para producción porque se revocan por separado; se deja anotado para HU2-052. El adaptador
  no necesita cambiar para usarlas: el usuario de la autenticación básica pasaría a ser la clave y el
  identificador de cuenta seguiría en la ruta. No se implementa ahora para no ampliar la configuración.

---

## Decisión 2 — Segundo bean `WebClient`: calificadores explícitos en ambos adaptadores

**Hallazgo que condiciona el diseño (verificado en el código, no asumido)**: `BrevoWebClientConfig`
declara el único bean de tipo `WebClient` del contexto, y `BrevoNotificationProvider` lo recibe por tipo
en su constructor (parámetro `brevoWebClient`, sin `@Qualifier`). El `pom.xml` raíz **no** hereda de
`spring-boot-starter-parent` ni configura `-parameters` en el compilador, así que Spring 6.1 no puede
resolver la ambigüedad por nombre de parámetro. En cuanto exista un segundo bean `WebClient` para
este proveedor, el contexto **no arranca** (`NoUniqueBeanDefinitionException`) y todas las pruebas
`@SpringBootTest` fallan.

**Decision**: el bean nuevo se llama `twilioWebClient` y **los dos** adaptadores declaran
`@Qualifier` con el nombre de su bean en el parámetro del constructor. El cambio en
`BrevoNotificationProvider` es una anotación en un parámetro; `BrevoEmailDeliveryE2ETest` y
`BrevoDisabledProviderE2ETest` lo verifican al arrancar su contexto.

**Alternatives considered**:

- Que el adaptador construya su propio `WebClient` sin exponerlo como bean: evita tocar el código del
  correo, pero rompe el patrón (la configuración de tiempos de espera dejaría de vivir en `config`) y
  la próxima integración repetiría el problema.
- `@Primary` en uno de los dos beans: oculta cuál recibe cada adaptador; un tercer proveedor volvería a
  romperlo en silencio.
- Añadir `-parameters` al compilador: cambio global de construcción, fuera del alcance de la historia y
  no protege contra un renombrado del parámetro.

---

## Decisión 3 — Propiedades, habilitación y motivo explícito

**Decision**: `TwilioProviderProperties` en `infrastructure.config` (no en `adapter.out.provider`), por
la misma razón que `BrevoProviderProperties`: `TwilioWebClientConfig` (módulo `config`) la consume y
dejarla en `adapter` crea el ciclo `adapter -> config -> adapter` que `ModularityTests` detecta.

```text
@ConfigurationProperties("notification.provider.twilio")
record TwilioProviderProperties(String accountSid, String authToken, String fromNumber,
                                String baseUrl, Long timeoutMs, Long connectTimeoutMs)
```

- Valores por defecto con tipos envueltos (`Long`), no primitivos, en el constructor compacto, para no
  repetir el `BX_UNBOXING_IMMEDIATELY_REBOXED` que SpotBugs ya señaló en el proveedor de correo. El
  record no tiene colecciones, así que `EI_EXPOSE_REP` no aplica.
- `disabledReason()` devuelve `Optional<String>` con el **primer** motivo aplicable, en este orden:
  falta `account-sid`; falta `auth-token`; falta `from-number`; `account-sid` sin formato de
  identificador de cuenta (`AC` + 32 hexadecimales); `from-number` sin formato internacional. El motivo
  nombra la propiedad y la variable de entorno, nunca el valor.
- El adaptador se registra **siempre** como `@Component`; si hay motivo, emite **un** `WARN` al
  construirse y cada `send(...)` devuelve `Mono.error(new ProviderDisabledException(twilio, motivo))`.
  `ProviderDisabledException` ya existe en `core` y es genérica: **no** se crea una específica.

**Por qué validar el formato además de la presencia (FR-004)**: un número de origen mal escrito no es un
problema de una notificación sino del despliegue. Si pasara a la llamada, el proveedor lo rechazaría
con "petición rechazada", que se clasifica como fallo permanente (Q4): **cada** notificación SMS
terminaría fallida por un error de configuración, que es exactamente lo que el Principio VIII prohíbe.
Deshabilitado, en cambio, la notificación queda intacta y en la DLQ con la causa. El mismo razonamiento
aplica al identificador de cuenta, que además forma parte de la ruta.

**Alternatives considered**: no registrar el bean sin credenciales (`@ConditionalOnProperty`) —
descartado igual que en el correo: el rastro quedaría como "no hay adaptador para twilio", sin motivo.

---

## Decisión 4 — Canal SMS en el catálogo y su forma de contenido (Q1, Q2)

**Contexto**: hoy el catálogo solo declara `EMAIL`. El canal SMS **no existe**: una notificación SMS se
rechaza en la aceptación con `ChannelNotAvailableException`. La ruta de canal ya tiene un campo
`contentSchema` (JSON Schema 2020-12) que `SendNotificationService` valida con `ContentSchemaValidator`
**en la aceptación**, antes de persistir, y cuyo fallo se traduce a `400` en el controlador y a un ítem
rechazado en el lote (`SendNotificationBatchService` delega en el mismo caso de uso). Nadie lo usa hoy.

**Decision**: `application.yml` declara el canal SMS con su lista de proveedores configurable por
entorno y su forma de contenido propia:

```yaml
notification:
  catalog:
    channels:
      SMS:
        providers: ${NOTIFICATION_SMS_PROVIDERS:simulated,twilio}
        content-schema: '{"type":"object","required":["body"],"properties":{"body":{"type":"string","maxLength":160}}}'
```

- **Q1**: el asunto no aparece en `required` ni tiene restricción: se admite y el adaptador no lo envía.
- **Q2**: `maxLength: 160` en el cuerpo. Un cuerpo de 161 o más caracteres produce
  `InvalidContentException` en la aceptación: `400` con el mensaje del validador (que nombra el límite),
  nada persistido, nada encolado, nada enviado. No se trunca en ningún punto.
- La forma de contenido es configuración estructural no sensible: se versiona en `application.yml` y se
  siembra en Mongo. Cambiar el límite en un entorno ya sembrado se hace editando el documento del
  catálogo (el refresco lo recoge), sin desplegar código — el mecanismo que ADR-0009 existe para dar.
- **Cero cambios en `core`**: el validador, la excepción y su traducción a `400` ya existen.

**Cómo cuenta el límite**: el validador cuenta **puntos de código Unicode**. El proveedor cuenta
unidades de su codificación: 160 por fragmento en el alfabeto básico de SMS (GSM-7), 70 en la extendida
(UCS-2), 153 y 67 respectivamente por fragmento cuando el mensaje se parte. De ahí el costo máximo que
registra el spec: 1 fragmento con texto básico, hasta 3 con tildes agudas (á, í, ó, ú no están en GSM-7;
é, ñ, ü sí), hasta 5 con un cuerpo solo de emojis (cada uno ocupa dos unidades UCS-2). Los caracteres de
extensión de GSM-7 (`€`, `[`, `]`, `{`, `}`, `^`, `~`, `|`, `\`) ocupan dos posiciones: un cuerpo
de 160 caracteres con alguno de ellos pasa a 2 fragmentos. Todo esto se documenta; ninguno se corrige
en esta historia (ver alternativas).

**Si Q2 cambia**: el número vive en un solo literal de `application.yml` y en las pruebas de 160/161;
truncar en lugar de rechazar sí cambiaría el diseño (habría que hacerlo en el adaptador y el spec
perdería SC-008).

**Alternatives considered**:

- Límite en fragmentos según la codificación (calcular GSM-7 frente a UCS-2): acota el costo con
  exactitud, pero exige lógica propia de codificación que no existe, y el lugar natural sería `core`
  (validación de aceptación) con un modelo de "política de contenido por canal" que no existe. Excede la
  historia; queda como mejora si el costo real lo justifica tras la prueba de humo.
- Comprobar la longitud en el adaptador: rechazaría en el despacho, después de aceptar y persistir; el
  cliente recibiría `202` y luego un fallo. Peor para el cliente y duplica la regla que ya expresa el
  catálogo.
- Regla de codificación en el esquema (`pattern` restringido a GSM-7): rechazaría cualquier texto con
  tildes agudas, inaceptable para un sistema en español.

**Siembra en entornos existentes**: `ChannelCatalogSeeder` siembra solo sobre una colección vacía. Un
entorno ya sembrado **no** obtiene el canal SMS por esta historia. A diferencia del proveedor de correo
(donde faltaba un proveedor en un canal existente), aquí falta el canal entero, así que el síntoma en un
entorno no actualizado es un `400` "canal no disponible". El paso manual va en `quickstart.md`.
**Excepción documentada** (Principio VII), la misma ya abierta por el proveedor de correo — dueño:
andrualv. Fecha de revisión: 2026-10-31.

---

## Decisión 5 — Clasificación de la respuesta (Q4)

**Decision**: `TwilioResponseClassifier`, clase final con métodos estáticos puros
(`classifyStatus(int)`, `classifyError(Throwable)`), igual que el clasificador del correo. **Solo el
código HTTP decide** (Q4):

| Resultado de la llamada | `AttemptResult` | Razón / códigos del proveedor que caen aquí |
|---|---|---|
| `2xx` (el proveedor responde `201 Created`) | `ACCEPTED` | El proveedor encoló el mensaje. "Aceptada" no es "recibida en el teléfono". |
| `3xx` | `RECOVERABLE_FAILURE` | No se siguen redirecciones; respuesta inesperada. |
| `400 Bad Request` | `PERMANENT_FAILURE` | Número de destino inválido (21211), no es móvil (21614), dado de baja o en lista de exclusión (21610), no verificado en cuenta de prueba (21608), región no habilitada (21408), cuerpo demasiado largo (21617). |
| `401 Unauthorized` | `PERMANENT_FAILURE` | Credenciales inválidas (20003). Criterio de aceptación literal. |
| `403 Forbidden` | `PERMANENT_FAILURE` | Sin permiso para la operación. |
| `404 Not Found` | `PERMANENT_FAILURE` | Cuenta inexistente en la ruta (20404): error de integración. |
| `408 Request Timeout` | `RECOVERABLE_FAILURE` | Fallo temporal. |
| `429 Too Many Requests` | `RECOVERABLE_FAILURE` | Límite de tasa (20429). |
| Resto de `4xx` | `PERMANENT_FAILURE` | Atribuible a la petición. |
| `5xx` | `RECOVERABLE_FAILURE` | Fallo temporal del proveedor. |
| Tiempo de espera del cliente agotado | `RECOVERABLE_FAILURE` | FR-007. |
| Error de conexión, DNS o TLS | `RECOVERABLE_FAILURE` | Fallo de transporte. |
| Cualquier otra excepción | `RECOVERABLE_FAILURE` | Conservador. |

Los códigos del proveedor de la tabla **no** se usan para clasificar; se citan para que la revisión
compruebe que cada causa del criterio de aceptación cae en la categoría pedida. Los códigos se tomaron
de la referencia pública de errores del proveedor y se revalidan en la prueba de humo; si alguno
difiere, la categoría no cambia porque la decide el código HTTP.

**Diferencia deliberada con el correo**: el clasificador del correo trata `402` como recuperable
(cuenta sin crédito, Q3 de esa historia). El proveedor de SMS no documenta `402`; aquí cae en "resto de
`4xx`" → permanente. No se copia la fila para no inventar semántica.

**Lectura de la respuesta**: el adaptador lee el cuerpo (máximo unos cientos de bytes) en un record
`TwilioApiResponse(String sid, Integer code)` con `@JsonIgnoreProperties(ignoreUnknown = true)`, que
**no declara** `message` ni `more_info`, de modo que el texto del error nunca llega a un objeto Java. Si
el cuerpo falta o no se puede leer, la categoría es la misma (decide el código HTTP) y simplemente no
hay `sid` o `code` que registrar.

**Riesgo anotado** (Q4, Principio VII): el proveedor comunica alguna condición transitoria del número de
origen con `400` (por ejemplo, cola del número llena). Termina en fallo permanente. Es recuperable a mano
porque `StatusTransitionPolicy` admite `FAILED → PENDING`, y el código queda en el registro para
identificarlo. Dueño: andrualv. Fecha de revisión, junto con HU2-039: 2026-11-30. Igual que en el
correo, `401` permanente implica que un token rotado deja fallidas las notificaciones que se despachen
mientras dure el problema; mismo tratamiento.

---

## Decisión 6 — Construcción del mensaje a partir de `Notification` (Q1, Q3)

| Campo del proveedor | Origen | Nota |
|---|---|---|
| `To` | `notification.recipient().address()` | debe tener formato internacional; si no, no se llama (abajo) |
| `From` | `TWILIO_FROM_NUMBER` | validado al arrancar (Decisión 3) |
| `Body` | `notification.content().body()` | ya validado contra la forma del canal en la aceptación |

**No se envían**: el asunto (Q1), `tenantId`, `externalId`, `recipientId`, `priority`, ni ningún
`StatusCallback` (las confirmaciones de entrega asíncronas están fuera de alcance).

**Destinatario sin formato internacional (Q3)**: el adaptador comprueba `^\+[1-9]\d{1,14}$` **antes** de
construir la petición. Si no cumple, devuelve `Mono.just(PERMANENT_FAILURE)` sin llamar, y registra
`reason=invalid-recipient-format` sin el valor. Es el mismo contraste que el correo estableció entre "sin
credenciales" (error de despliegue, sin intento, DLQ) y "sin asunto" (dato inválido de la notificación,
intento permanente). La expresión es deliberadamente laxa (de 2 a 15 dígitos): su objetivo es atrapar
lo que claramente no es un teléfono — un correo mandado al canal equivocado, un número local sin `+` —
y dejar al proveedor la validación fina del plan de numeración de cada país.

**Verificación de que no hubo llamada**: no basta con afirmar `PERMANENT_FAILURE` sin llamar al
proveedor — se verifica con un hecho observable, no con la ausencia de un efecto secundario que la
prueba no comprobó. `TwilioNotificationProviderTest` (destinatario mal formado) y
`TwilioDisabledProviderE2ETest` (proveedor deshabilitado) afirman `FakeProviderServer.requests().isEmpty()`
además del resultado, para que un adaptador que llamara igual y descartara la respuesta no pase la
prueba por accidente.

**Si Q3 cambia a "validar en la aceptación"**: exige que `core` valide el destinatario según el canal
(hoy `Recipient` es texto libre y la forma de contenido solo ve asunto y cuerpo). Sería una historia
propia; este adaptador conservaría su comprobación como defensa.

**Dónde vive la regla de formato**: la usan el adaptador (destinatario) y las propiedades (número de
origen, módulo `config`). Para no crear un ciclo de módulos ni duplicar la expresión, va a una clase
utilitaria pura en el módulo `utils`, junto a `Preconditions`: `PhoneNumbers` con `isE164(String)` y
`mask(String)` (Decisión 8). `utils` ya es dependencia de `core` e `infrastructure` y no depende de
ningún framework.

---

## Decisión 7 — Tiempo de espera explícito

**Decision**: idéntico al correo. `notification.provider.twilio.timeout-ms` (`TWILIO_TIMEOUT_MS`, por
defecto `10000`) fija `responseTimeout` del `HttpClient`; `connect-timeout-ms`
(`TWILIO_CONNECT_TIMEOUT_MS`, por defecto `5000`) fija `CONNECT_TIMEOUT_MILLIS`. Superar cualquiera se
clasifica como `RECOVERABLE_FAILURE`. Mismo razonamiento que el correo: `responseTimeout` cierra el canal
en reactor-netty, `Mono.timeout` no.

**Prueba (SC-006)**: el servidor simulado retarda la respuesta más que el tiempo de espera configurado en
la prueba (por ejemplo, 3 s de retardo contra 500 ms de espera) y la prueba afirma con una `Duration`
explícita que el resultado llega antes de un margen acotado (por ejemplo, < 2 s) y es
`RECOVERABLE_FAILURE`.

---

## Decisión 8 — Enmascarado del número en los registros

**Decision**: `PhoneNumbers.mask(value)` devuelve `***` seguido de los últimos cuatro dígitos
(`+573001234567` → `***4567`). Si el valor tiene cuatro dígitos o menos, o es nulo o vacío, devuelve
`***` sin dígitos. No revela la longitud ni el código de país.

**Registros del adaptador** (Principio IX, FR-008):

- aceptación: `notificationId`, `tenantId`, `providerId`, `result`, `httpStatus`, `providerMessageId`
  (`sid`), `recipient` enmascarado;
- rechazo del proveedor: lo anterior más `providerErrorCode` (`code` numérico), sin `message`;
- error de transporte: `notificationId`, `tenantId`, `providerId`, `result`, `errorType` (nombre simple
  de la excepción, nunca su mensaje: el de `WebClientRequestException` contiene la URL con el
  identificador de cuenta);
- destinatario mal formado: `reason=invalid-recipient-format`, sin el valor.

Nunca: token, identificador de cuenta, cabecera de autorización, URL, cuerpo del SMS, número completo.

---

## Decisión 9 — Nada sensible en registros ni en mensajes internos

**Decision**: las mismas medidas que el correo (sin `wiretap`, sin filtros de registro en el
`WebClient`, advertencia operativa en `quickstart.md` sobre `reactor.netty.http.client=DEBUG`), más las
dos que añade este proveedor: no registrar la URL (lleva el identificador de cuenta) ni el mensaje de las
excepciones de transporte (Decisión 8).

`auth-token` contiene `token` y lo sanean por nombre `configprops`/`env` del actuator; `account-sid` no,
pero el actuator expone solo `health` por defecto y esta historia no cambia eso.

RabbitMQ no cambia: el mensaje de despacho lleva solo el `notificationId` y los eventos de dominio solo
identificador y marca de tiempo. La historia añade la **verificación automatizada**.

**Cómo se verifica** (SC-004), igual que en el correo pero con más valores prohibidos: `ListAppender` en
el logger raíz durante el despacho y una cola temporal enlazada al exchange de eventos. Se afirma la
ausencia de: token, identificador de cuenta, `base64(sid:token)`, cuerpo del SMS, número completo del
destinatario, y el `message` que el servidor simulado devuelve en un rechazo (que la prueba redacta con
el número completo dentro, para demostrar que no se filtra). Se afirma la **presencia** de la forma
enmascarada, como control positivo de que el registro existe.

---

## Decisión 10 — Servidor que simula al proveedor: reutilizar el del correo, con nombre neutro

**Decision**: `FakeBrevoServer` no tiene nada específico del proveedor de correo: registra ruta,
cabeceras y cuerpo crudo, y permite programar estado, cuerpo y retardo. Ya contiene las dos correcciones
aprendidas (registrar la petición **antes** del retardo artificial; pool de hilos cacheado, no de un solo
hilo, para no contaminar pruebas entre sí). Se **renombra** a `FakeProviderServer` (`git mv`, mismo
paquete de pruebas) y se actualizan sus referencias en las pruebas del correo; lo usan ambos proveedores.

**Rationale**: conservar las dos correcciones en un único sitio es más seguro que copiarlas. El criterio
"un adaptador dedicado por proveedor, sin adaptador genérico" rige los adaptadores de producción, no una
ayuda de pruebas que simula HTTP.

**Alternatives considered**: copiar la clase como `FakeTwilioServer` — cero cambios en las pruebas del
correo, pero ~100 líneas duplicadas y dos sitios donde volver a introducir los mismos dos errores. Si el
usuario prefiere no tocar las pruebas del correo, es un cambio de una tarea sin efecto en el resto del
plan.

---

## Decisión 11 — Prueba manual de humo con cuenta de prueba, fuera de CI

**Decision**: procedimiento en `quickstart.md § Prueba manual de humo`: variables de entorno, cómo dar de
alta o invertir el canal SMS en el catálogo, `curl` de aceptación con un número **verificado** en la
cuenta, qué observar (SMS recibido con el prefijo de cuenta de prueba, estado `DELIVERED`, `providerId =
twilio`, `providerMessageId` en el registro coincide con el de la consola del proveedor), dos casos
negativos baratos (número no verificado → `FAILED` con código 21608 en el registro; cuerpo de 161
caracteres → `400`) y una plantilla de registro del resultado.

**Limitaciones de la cuenta de prueba** (spec.md, Risks): solo números verificados (máximo 5); prefijo
"Sent from your Twilio trial account - " que además consume capacidad del fragmento — un cuerpo de 160
caracteres llega en 2 fragmentos durante la prueba de humo, sin que eso indique un fallo del límite.
Producción en EE.UU. exige registro A2P 10DLC.

---

## Decisión 12 — Idempotencia frente al proveedor

**Decision**: no se envía ninguna clave de idempotencia. A diferencia del correo, donde el proveedor al
menos admite propagar una cabecera, no se conoce un mecanismo documentado de este proveedor que
deduplique la **creación** de mensajes por una clave del cliente. Enviar una cabecera inventada daría una
falsa sensación de protección.

**Riesgo residual documentado** (Principio VII): entrega "al menos una vez" → un SMS puede duplicarse si
el componente cae entre que el proveedor acepta y que el resultado se persiste, o si el proveedor acepta
pero la respuesta no llega antes del tiempo de espera. Dueño: andrualv. Fecha de revisión: 2026-10-31.
La mitigación real (marca persistida de "intento en curso" antes de llamar) es común a todos los
proveedores y merece su propia historia.

---

## Decisión 13 — Cómo no mezclar los cambios ajenos del árbol de trabajo

**Contexto**: el árbol tiene dos modificaciones **ajenas** a esta historia, las mismas que ya existían
durante la del correo: `spring.application.name` en `infrastructure/src/main/resources/application.yml`
y el mensaje de validación de `core/.../valueobject/Recipient.java`. Esta historia edita el mismo
`application.yml` (canal SMS y bloque del proveedor).

**Decision** (la misma que el usuario aprobó para el correo, confirmada también para esta historia):

1. Antes de la tarea que edita `application.yml`: `git stash push -- infrastructure/src/main/resources/application.yml`.
2. Editar y commitear solo los cambios de esta historia.
3. Al terminar toda la historia: `git stash pop`.
4. Si el `pop` entra en conflicto, no se resuelve dentro de la historia: se reporta el comando exacto.

`Recipient.java` no se toca ni se commitea.

**Observación para el usuario, no para esta historia**: el cambio ajeno de `Recipient.java` reemplaza el
mensaje genérico por "El correo electronico no puede estar vacio". `Recipient` es el destinatario de
**todos** los canales; con el canal SMS disponible, una notificación SMS con destinatario vacío
recibiría un mensaje que habla de correo. No se corrige aquí (no es de esta historia); se reporta.

---

## Decisión 14 — Contrato público: solo descripciones

**Decision**: `api-notificaciones.yaml` no gana ni pierde endpoints, esquemas, campos ni códigos de
respuesta. Sí se actualizan, **antes** de tocar código (Principio II), cuatro descripciones que con esta
historia quedarían falsas o incompletas:

- `channelType`: hoy dice "hoy solo EMAIL está configurado" → pasa a nombrar EMAIL y SMS;
- `recipientAddress`: para SMS, número en formato internacional (`+` y código de país);
- `subject`: SMS lo admite y lo ignora (Q1);
- `body`: la longitud máxima la fija la forma de contenido del canal; SMS, 160 caracteres (Q2), y el
  `400` ya documentado ("el contenido no cumple el esquema del canal") cubre el rechazo.

Detalle en `contracts/api-notificaciones-cambios.md`.

---

## Decisión 15 — Fuera de alcance, con dueño y fecha (Principio VII)

| Historia o capacidad ausente | Limitación que deja | Dueño | Fecha de revisión |
|---|---|---|---|
| HU2-038 — batería de contrato de adaptadores | Garantías verificadas con pruebas propias; adoptar la batería cuando exista. | andrualv | 2026-12-31 |
| HU2-052 — gestión de secretos por la plataforma | Credenciales por variable de entorno; claves de API revocables pendientes (Decisión 1). | andrualv | 2026-11-30 |
| HU2-039 — límite de tasa por proveedor | Ráfagas → `429` → reintentos masivos. No exponer a carga real antes. | andrualv | 2026-11-30 |
| Validación del destinatario por canal en la aceptación | El cliente se entera del número mal formado por el estado, no por el `400` (Q3). | andrualv | 2026-12-31 |
| Confirmaciones de entrega asíncronas del proveedor | `DELIVERED` significa "el proveedor lo encoló", no "llegó al teléfono". | andrualv | 2026-12-31 |
| Registro A2P 10DLC y cuenta de pago | Solo cuenta de prueba: números verificados y prefijo propio. | andrualv | 2026-11-30 |

---

## Decisión 16 — Lo que esta historia NO toca

- `DispatchNotificationService`, `SendNotificationService`, `ContentSchemaValidator`,
  `NotificationSenderPort`, `NotificationSenderRegistry`, `ProviderDisabledException`: sin cambios. **Cero
  cambios en `core`.**
- `RabbitConfig`, `RabbitRetryConfig`, `NotificationDispatchListener`: el consumidor conserva ack manual y
  DLQ exactamente como están. No hay excepción que justificar.
- `SimulatedNotificationProvider` y `BrevoResponseClassifier`: sin cambios. `BrevoNotificationProvider`
  solo gana el `@Qualifier` de la Decisión 2.
- `ChannelCatalogSeeder` y `ChannelCatalogRefresher`: sin cambios (Decisión 4).
