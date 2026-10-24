# Implementation Plan: Validacion de destinatarios y rebotes duros (HU2-116)

**Spec**: [spec.md](spec.md) | **Rama**: `feature/HU2-116-validacion-destinatarios`

Orden de integracion: E3 -> G3 -> G2 -> E10 -> E5. Esta historia se monta sobre el rediseno de E3.

## Diseno

Dos mecanismos independientes (ver [spec.md](spec.md#contexto)):

1. **Formato (sincrono, `core`)**: `RecipientFormatPolicy` en `core/domain/policy` (junto a `AttachmentPolicy`), invocada en
   `SendNotificationService.validateAndProceed` tras resolver la ruta del canal y antes de `attachmentResolver`. Lanza
   `InvalidRecipientException`. Canal SMS: `PhoneNumbers.isE164`. Canal correo: `EmailAddresses.isValid` /
   `normalize` (recorte y minusculas). Otros canales: sin validacion (FR-013). El canal se distingue por `ChannelType.value()`
   del catalogo ("SMS" y el de correo); no se ramifica por proveedor.
2. **Rebote (asincrono)**: webhook -> `RegisterRecipientBounceUseCase` -> `UndeliverableRecipientRepository` (Mongo).
   `SendNotificationService` consulta el repositorio tras la politica de formato y lanza
   `RecipientUndeliverableException`.

`SendNotificationBatchService` (lineas ~118-121, lista de excepciones rechazables) incorpora `InvalidRecipientException` y
`RecipientUndeliverableException` para producir items `rejected`.

## messageId del proveedor

Hoy `BrevoNotificationProvider` solo escribe `body.messageId()` en el log; `DeliveryAttempt` no lo tiene y el puerto
devuelve `AttemptResult` sin id, por lo que el tenant del webhook no se puede resolver. E3 hace un rediseno unico de
`DeliveryAttempt`, `DeliveryAttemptDocument`, `Notification.mark*`, `AttemptResult` y `Outcome`; E10 no redefine nada de eso.
Sobre ese rediseno E10 anade unicamente:

- Forma de E3 (spec 031, propuesta con nombres por confirmar): `Outcome(ProviderResult last, List<FailedProvider> failedOver)`,
  `ProviderResult(AttemptResult result, ProviderId providerId)`, `Notification.mark*` con un unico `AttemptRecord`, y
  `DeliveryAttempt`, `DeliveryAttemptDocument` y su mapper construidos desde `AttemptRecord` con campos nuevos opcionales.
- `messageId` (String, opcional) como componente nuevo de `ProviderResult`, de `AttemptRecord` y de `DeliveryAttempt`
  (y su campo en `DeliveryAttemptDocument`), rellenado por Brevo desde `BrevoApiResponse.messageId()`; `simulated` genera uno
  sintetico. Los documentos viejos se leen sin el campo. Se persiste el del proveedor que acepto, tambien tras un failover.
- Indice Mongo sobre `attempts.messageId` en `NotificationDocument` y consulta
  `NotificationRepository.findTenantAndAddressByProviderMessageId` (devuelve tenant, canal y direccion).

Si E3 ya incluye un identificador del proveedor, E10 reutiliza ese campo y solo agrega el indice y la consulta. Sin este
cambio FR-007 y SC-002 no se cumplen.

## Donde se guarda la marca (coexistencia con E5 y F6)

Coleccion propia `undeliverable_recipients`, clave unica `(tenantId, channelType, normalizedAddress)`. No se anade campo a
`Notification`, `Recipient` ni `RecipientId`, y no se crea directorio de destinatarios. E5 separara la identidad del
destinatario de su direccion y F6 creara un directorio: ambos podran referenciar esta coleccion por la direccion
normalizada, o absorberla migrando los documentos a un campo `deliverability` del destinatario.
Excepcion Principio VII: dueno E5, fecha de revision 2026-12-15.

## Contrato y rutas (rutas literales, sin prefijo, spec 024)

Cambios para Contrato en `api-notificaciones.yaml` (no se edita aqui). Las nuevas rutas no chocan con `/providers`
existente (`GET`/`POST /providers` es otro path) ni con `/recipients/preferences`.

- `GET /recipients/undeliverable-lookups?channel=&address=`: `x-minimum-role: OPERADOR`, `x-status: available`.
- `POST /recipients/undeliverable-releases`, cuerpo `{channel,address}`: `x-minimum-role: ADMINISTRADOR`, `x-status: available`.
- `POST /providers/brevo/bounce-events`: va en `x-public-routes` con `security: []` (no en `paths`, por lo que no necesita
  `x-minimum-role` ni `x-status` ni se registra en el PDP) y se anade a `SECURITY_PEP_PUBLIC_PATHS` (`application.yml` ~l.77)
  y a su valor por defecto. Se protege con el secreto de FR-009. Se declara con `exposure: always`.
- Envio unitario y lote: respuestas 400 `INVALID_RECIPIENT` y 409 `RECIPIENT_UNDELIVERABLE`; nuevos valores en el enum de errores.
- `RouteAuthorizationPolicy` (`infrastructure/.../in/web`) necesita las dos reglas de rol nuevas (por defecto cae en
  ADMINISTRADOR); el webhook queda fuera por ser publico. Roles en el codigo: los 3 actuales hasta B2.

## Piezas por modulo

- `utils`: `EmailAddresses` (`isValid`, `normalize`, `mask`) + pruebas.
- `core`: `RecipientFormatPolicy`, `InvalidRecipientException`, `RecipientUndeliverableException`, `UndeliverableRecipient`,
  `UndeliverableRecipientRepository`, `RegisterRecipientBounceUseCase` y servicio, `LiftUndeliverableMarkUseCase`,
  `FindUndeliverableMarkQuery`; metodos nuevos en `NotificationMetricsPort`.
- `infrastructure`:
  - `UndeliverableRecipientMongoAdapter` con upsert atomico sobre el indice unico (idempotencia).
  - `BrevoBounceWebhookController`: lee `BREVO_WEBHOOK_SECRET`, compara con `MessageDigest.isEqual`; 401 si falta o no
    coincide; clasifica `hard_bounce`, `invalid_email`, `blocked` como duros y descarta `soft_bounce` y `deferred`; resuelve
    tenant por `messageId`; 2xx solo tras persistir, 5xx si falla. Variable en `application.yml` (`notification.brevo.webhook-secret`)
    y `.env.example`. Sin secreto configurado el endpoint responde 401 siempre.
  - Controller de consulta y levantamiento. Rol: el controller lee los roles de la sesion del PDP (misma fuente que usa
    `RouteAuthorizationPolicy`) y pasa un booleano `fullAddress` al mapper de respuesta; `core` no conoce roles.
  - Simulado (FR-011): `SimulatedBounceController`, bean con `@Profile({"local","test"})`, ruta de prueba
    `POST /providers/simulated/bounce-events` que construye el mismo evento y lo entrega al caso de uso. No existe en otros perfiles ni se declara en el contrato.
  - Manejadores y `ErrorCode` en `NotificationExceptionHandler`.
- Metricas nuevas en `NotificationMetricsPort`: `recipientRejected(channel, reason)` -> contador
  `notification_recipient_rejected_total{tenant,channel,reason}` (reason = `format` | `undeliverable`) y
  `recipientMarkedUndeliverable(channel)` -> `notification_recipient_undeliverable_marked_total{tenant,channel}`.
- Wiring (`UseCaseConfig`) lo hace quien lo tenga asignado.

## Constitucion

- I: politica y casos de uso sin Spring; `HexagonalArchitectureTest` y `ModularityTests` en verde.
- II: contrato primero; las tareas de controllers dependen de la entrega de Contrato.
- IV: E2E `RecipientValidationE2ETest` (formato rechazado sin persistir; rebote por el controller real -> bloqueo ->
  levantar; dos tenants; control positivo con direccion valida), integracion del adaptador Mongo y de la consulta por
  `messageId` con Testcontainers, unitarias con `StepVerifier`. SC-004 como prueba de umbral amplio (10 000 en < 1 s).
- VIII/IX: logs y metricas con tenant y `notificationId`, direccion enmascarada, sin secreto ni cuerpo del webhook.
- Sin consumidor RabbitMQ nuevo: no aplica ack manual ni DLQ.

## Riesgos

- Depende de la forma final del rediseno de E3 para colocar `messageId`; si cambia, se ajusta solo este plan.
- Regla de correo demasiado estricta: se usa una regla pragmatica y la verificacion real queda en el proveedor.
- El webhook es publico para el PEP y solo lo protege el secreto: rotacion por variable de entorno.
