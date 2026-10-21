# Phase 0 — Research: Enviar notificaciones por el canal WhatsApp

**Feature**: 028-whatsapp-canal | **Date**: 2026-10-21

Q1 a Q4 de la spec están resueltas (opción A). Aquí se resuelve lo que el diseño todavía debía decidir. Todo
lo marcado **[verificar]** es un hecho del proveedor que no pudo confirmarse sin red desde este entorno; la
tarea correspondiente debe confirmarlo contra la documentación vigente antes de implementar y, si difiere,
ajustar la decisión y avisar.

## Decisión 1 — Un adaptador propio, `twilio-whatsapp`, y no ampliar el de SMS

**Decisión**: nuevo `TwilioWhatsAppNotificationProvider` con `providerId = twilio-whatsapp`. Reutiliza el
clasificador por código HTTP (`TwilioResponseClassifier`), el record de respuesta (`TwilioApiResponse`),
`ProviderLogs` y `ProviderHttpClients`; no toca `TwilioNotificationProvider`.

**Por qué**: FR-001 pide identificador propio. Si un único `twilio` atendiera SMS y WhatsApp, la falta del
número de origen de WhatsApp deshabilitaría también el SMS, y los tiempos de espera (parámetros gestionados
por proveedor) no podrían ajustarse por canal.

**Alternativas**: (a) ramificar por `channelType` dentro de `TwilioNotificationProvider` — menos clases, pero
acopla dos habilitaciones distintas en un solo `disabledReason`; (b) clase base común — prematura con dos
hijos que difieren en formulario, destinatario y habilitación.

**Costo asumido**: registrar el proveedor en `ParameterRegistry.MANAGED_PROVIDERS` (único cambio en `core`
para esto) y en `ConfigurationConfig` (habilitación, timeouts, límites).

## Decisión 2 — API de Twilio para WhatsApp

**Decisión**: mismo endpoint de mensajes que el SMS (`POST /2010-04-01/Accounts/{sid}/Messages.json`, formulario,
autenticación básica). Diferencias:

| Campo | Texto libre | Plantilla |
|---|---|---|
| `To` | `whatsapp:` + número internacional | igual |
| `From` | `whatsapp:` + número de WhatsApp configurado | igual |
| `Body` | cuerpo de la notificación | no se envía |
| `ContentSid` | no se envía | `templateId` de la notificación (forma `HX` + 32 hex) |
| `ContentVariables` | no se envía | JSON `{"nombre":"valor",...}` de las variables |

**[verificar]**: nombres de campo `ContentSid`/`ContentVariables` y que las variables aceptan nombres
alfanuméricos además de índices `"1","2"`. Detalle del contrato de salida en
`contracts/twilio-whatsapp-messages-api.md`.

**Por qué plantilla por `ContentSid`**: es el mecanismo vigente para plantillas aprobadas sobre Twilio; el
idioma va dentro de la plantilla y no es un campo aparte (el modelo no necesita `language`).

## Decisión 3 — Modelo de contenido: plantilla como parte del contenido, exclusión mutua en el dominio

**Hallazgo**: hoy `NotificationContent` exige `body` no vacío y no existe concepto de plantilla. Sin
ampliarlo, una notificación por plantilla no se puede representar.

**Decisión**: `NotificationContent` gana un campo opcional `template` (`TemplateReference`:
`templateId` + `variables` mapa texto→texto, inmutable). Invariante en el record: **exactamente uno** de
`body` o `template` (FR-005); ambos o ninguno lanza el error de precondición, que el controlador traduce a
`400`. Los constructores y fábricas actuales conservan su firma (cuerpo, sin plantilla), así que
correo/SMS/push no cambian.

**Quién admite plantillas**: `ContentSchemaValidator` rechaza una plantilla si el esquema del canal no
declara la propiedad `template`, igual que ya rechaza adjuntos si no declara `attachments`. Con el catálogo
por defecto, solo WhatsApp la declara; un correo con plantilla se rechaza con motivo explícito.

**Alternativas**: (a) plantilla serializada dentro de `body` — oculta la distinción y obliga a cada canal a
interpretar JSON; (b) un mapa genérico `metadata` — sin forma, sin validación, riesgo de filtrar datos;
(c) crear `TemplateNotification` como entidad aparte — duplica el ciclo de vida de `Notification`.

**Costo asumido**: cambia `core` (`NotificationContent`, `TemplateReference`, `ContentSchemaValidator`,
`SendNotificationService`), el DTO REST de envío y de lote, el documento Mongo y su mapper, y el contrato.
Es el cambio que hace de esta historia una L. Los documentos persistidos existentes no cambian (`body` ya
existe, `template` es nuevo y nulo).

## Decisión 4 — No hay registro de plantillas: la forma se valida, la semántica la valida el proveedor

**Hallazgo**: la spec original pedía rechazar en la aceptación la falta de una variable obligatoria. El
componente no conoce las plantillas aprobadas (Out of Scope: ciclo de vida de plantillas), así que no puede.

**Decisión**: en la aceptación se valida solo la forma declarada en el esquema del canal (`templateId`
presente y acotado; `variables` con a lo sumo 20 entradas, valores texto de a lo sumo 1024 caracteres). La
spec se ajustó (FR-007, historia 2 escenario 5, SC-007). Una variable ausente o una plantilla
inexistente/no aprobada las rechaza el proveedor con `400` → fallo permanente, con el código del
proveedor registrado.

**Adaptador**: antes de llamar, comprueba que `templateId` tenga la forma de `ContentSid`; si no, fallo
permanente sin petición con `reason=invalid-template-format`, igual que el destinatario. La forma `HX…` es
específica de Twilio, por eso vive en el adaptador y no en el esquema del canal: cambiar de proveedor no
obliga a cambiar el catálogo.

## Decisión 5 — Ventana de 24 h: el cliente decide; el rechazo se distingue por código y se nombra

**Decisión (Q2-A)**: el sistema no rastrea la ventana. Un texto libre rechazado por estar fuera de ella
termina `PERMANENT_FAILURE` porque la categoría la decide el código HTTP (`400`), como en SMS. Para no dejar
el motivo genérico (FR-006), el adaptador deriva una etiqueta no sensible desde el `code` del proveedor y la
registra: `63016` → `outside-session-window`. Otros códigos relevantes se etiquetan igual:

| `code` (Twilio) | Etiqueta registrada | Categoría (por HTTP) |
|---|---|---|
| 63016 | `outside-session-window` | permanente |
| 63024, 21211 | `invalid-recipient` | permanente |
| 63015 | `recipient-not-in-sandbox` | permanente |
| 63007, 63112 | `sender-not-configured` | permanente |
| 20003 | `invalid-credentials` | permanente |
| otro / ausente | `unclassified` | por HTTP |

**[verificar]** la lista contra la documentación de errores de Twilio al implementar. La etiqueta es solo
diagnóstico: nunca cambia la categoría (coherente con la Decisión 5 de la spec 009). Se registra
`providerErrorCode` además de la etiqueta.

**Alternativa descartada**: reclasificar 63016 como recuperable para "esperar a que se abra la ventana" —
reintentar un envío que el proveedor ya dijo que no se admite no lo habilita, y gasta cuota.

## Decisión 6 — Idempotencia: cabecera de Twilio + clave derivada, con riesgo residual explícito

**Decisión (FR-011)**: la petición lleva la cabecera de idempotencia de Twilio con la clave
`{notificationId}:{ciclo}:{n}` donde `n` es la cantidad de intentos ya registrados en la notificación. Una
reentrega del mismo despacho ve la misma notificación sin intento nuevo guardado, por tanto la misma clave;
el intento siguiente (tras un fallo recuperable persistido) lleva otra.

**[verificar]**: nombre exacto de la cabecera (`I-Twilio-Idempotency-Token`), su disponibilidad para la API
de mensajes y para el canal WhatsApp, su ventana de validez y que un reenvío con la misma clave devuelve la
respuesta original. **Si no está disponible o no aplica a WhatsApp, la tarea correspondiente no la
simula**: el adaptador no envía clave, FR-011 y SC-008 se reducen a documentar el riesgo y se avisa para
re-aprobar. El riesgo de duplicado ya está registrado (Risks).

**Alternativa**: construir la clave con el `externalId`. Descartada: el `externalId` identifica la
notificación, no el intento; un reintento legítimo reutilizaría la clave y el proveedor lo trataría como
repetición.

## Decisión 7 — Destinatario y formato

**Decisión (FR-012)**: se reutiliza `PhoneNumbers.isE164` y `PhoneNumbers.mask`. El adaptador antepone
`whatsapp:`; el cliente envía solo el número internacional, igual que en SMS, para que el contrato del
destinatario sea el mismo en ambos canales.

## Decisión 8 — Configuración

**Decisión**: `TwilioWhatsAppProviderProperties` (`notification.provider.twilio-whatsapp`), independiente.
Reutiliza las variables `TWILIO_ACCOUNT_SID` y `TWILIO_AUTH_TOKEN` (misma cuenta), y suma
`TWILIO_WHATSAPP_FROM_NUMBER`, `TWILIO_WHATSAPP_BASE_URL`, `TWILIO_WHATSAPP_TIMEOUT_MS`,
`TWILIO_WHATSAPP_CONNECT_TIMEOUT_MS` y `NOTIFICATION_WHATSAPP_PROVIDERS` (por defecto
`simulated,twilio-whatsapp`). Habilitación y formato igual que Twilio SMS: identificador de cuenta `AC` + 32
hex; número de origen en formato internacional; cualquier falla → deshabilitado con motivo y sin valores.

**Por qué no compartir el record de propiedades con SMS**: los dos pueden deshabilitarse por separado
(Decisión 1); compartirlo mezclaría el motivo de uno con el otro.

## Decisión 9 — Esquema de contenido del canal y límite

**Decisión (FR-009)**: el canal `WHATSAPP` se siembra con este esquema versionado en `application.yml`
(comillas simples YAML):

`{"type":"object","properties":{"body":{"type":"string","maxLength":1600},"template":{"type":"object","required":["templateId"],"properties":{"templateId":{"type":"string","minLength":1,"maxLength":64},"variables":{"type":"object","maxProperties":20,"additionalProperties":{"type":"string","maxLength":1024}}}}}}`

- Sin `attachments`: `ContentSchemaValidator` ya rechaza adjuntos (Q4) sin código nuevo.
- `subject` sin restricción: se admite y se ignora, como SMS.
- `body.maxLength` 1600 coincide con el máximo que el proveedor acepta; se registra en
  `ProviderContentLimits` (`twilio-whatsapp`, `WHATSAPP`, `body.maxLength`, 1600) para que la regla
  `ContentLimitWithinProviderLimitRule` impida configurar un límite mayor. **[verificar]** el máximo vigente.
  WhatsApp admite hasta 4096 por mensaje, pero el intermediario limita el cuerpo; se usa el menor.

## Decisión 10 — Webhook, adjuntos y ventana: fuera de alcance, sin código

**Decisión (Q3-A, Q4-A)**: no se añade endpoint ni cambios en las rutas públicas del PEP. La
"validación del webhook" que pide ADR-28 queda diferida con dueño y fecha (Risks de la spec). Los adjuntos
se rechazan por el mecanismo existente. `supportsAttachments()` devuelve `false`.

## Decisión 11 — Registro y no filtrado

**Decisión (FR-013)**: se usa `ProviderLogs` existente. Se suma un campo `reason` (etiqueta de la Decisión 5)
a `rejectedByProvider`. Nunca se registran: URL (lleva el identificador de cuenta), cuerpo, `templateId`
junto a valores de variables, variables, número completo, cabecera de idempotencia ni mensaje de error del
proveedor. El `templateId` no es dato personal y sí puede registrarse; las variables, no.

## Decisión 12 — Siembra y entornos existentes

**Decisión**: la siembra solo actúa sobre catálogo vacío; el canal `WHATSAPP` aparece en entornos nuevos y
requiere alta manual en los existentes (documento en `quickstart.md`), igual que SMS y push. Dueño y fecha en
el plan.

## Decisión 13 — Cambios ajenos en el árbol de trabajo

Specs 019, 024, 025, 026, `.claude/` y `docs/` tienen cambios sin commitear que no son de esta historia.
Ningún commit los incluye; los commits usan `git add` de archivos concretos.
