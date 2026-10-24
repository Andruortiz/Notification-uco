# Implementation Plan: Validacion de destinatarios y rebotes duros (HU2-116)

**Spec**: [spec.md](spec.md) | **Rama**: `feature/HU2-116-validacion-destinatarios`

## Diseno

Dos mecanismos independientes (ver [spec.md](spec.md#contexto)):

1. **Formato (sincrono, `core`)**: `RecipientFormatPolicy` en `core/domain/policy` (junto a `AttachmentPolicy`),
   invocada en `SendNotificationService.validateAndProceed` tras resolver la ruta del canal y antes de
   `attachmentResolver`. Lanza `InvalidRecipientException`. Telefono usa `PhoneNumbers.isE164`; correo, nuevo
   `EmailAddresses.isValid` / `normalize` en `utils` (regla acotada, sin dependencias nuevas). El lote reutiliza el
   camino: `SendNotificationBatchService` incluye la nueva excepcion entre las rechazables para producir item `rejected`.
2. **Rebote (asincrono)**: webhook de Brevo -> `RegisterRecipientBounceUseCase` (puerto de entrada) ->
   `UndeliverableRecipientRepository` (puerto de salida, Mongo). `SendNotificationService` consulta el repositorio tras
   la politica de formato y lanza `RecipientUndeliverableException`.

## Donde se guarda la marca (coexistencia con E5 y F6)

Coleccion propia `undeliverable_recipients`, clave unica `(tenantId, channelType, normalizedAddress)`. No se anade
campo a `Notification`, `Recipient` ni `RecipientId`, y no se crea directorio de destinatarios. E5 separara la identidad
del destinatario de su direccion y F6 creara un directorio: ambos podran referenciar esta coleccion por la direccion
normalizada, o absorberla migrando los documentos a un campo `deliverability` del destinatario. Hasta entonces es la
unica fuente. Excepcion Principio VII: dueno E5/F6, revisar al cerrar E5 (fecha a fijar por el usuario).

## Piezas por modulo

- `utils`: `EmailAddresses` (+ prueba).
- `core`: `RecipientFormatPolicy`, `InvalidRecipientException`, `RecipientUndeliverableException`,
  `UndeliverableRecipient` (record), `UndeliverableRecipientRepository`, `RegisterRecipientBounceUseCase` y servicio,
  `LiftUndeliverableMarkUseCase`, `FindUndeliverableMarkQuery`; metricas nuevas en `NotificationMetricsPort`.
- `infrastructure`: `UndeliverableRecipientMongoAdapter` + documento con indice unico (upsert atomico = idempotencia);
  `BrevoBounceWebhookController` (valida `BREVO_WEBHOOK_SECRET`; `hard_bounce`, `invalid_email`, `blocked` son duros;
  `soft_bounce`, `deferred` se ignoran; tenant resuelto por el `messageId` del proveedor guardado en el intento de
  despacho); controller de consulta/levantamiento; manejadores y `ErrorCode` en `NotificationExceptionHandler`;
  enmascarado de direccion en respuestas segun rol; emision de rebote simulada solo con `@Profile({"local","test"})`.
- Wiring (`UseCaseConfig`) lo hace quien lo tenga asignado; aqui solo se lista.

## Cambios de contrato para Contrato (no se edita `api-notificaciones.yaml`)

- Envio unitario y lote: respuestas 400 `INVALID_RECIPIENT` y 409 `RECIPIENT_UNDELIVERABLE`; nuevos codigos en el enum de errores.
- `GET /api/v2/destinatarios/no-entregables/consultar?channel=&address=` (`x-minimum-role: OPERADOR`).
- `POST /api/v2/destinatarios/no-entregables/levantar`, cuerpo `{channel,address}` (`x-minimum-role: ADMINISTRADOR`).
- `POST /api/v2/proveedores/brevo/rebotes`: ruta publica para el PEP (como en spec 027), autenticada por secreto.
- Todas con `x-status` y rutas literales (Principio II).

## Constitucion

- I: politica y casos de uso sin Spring; webhook y Mongo en `infrastructure`; `HexagonalArchitectureTest` y `ModularityTests` en verde.
- II: contrato primero; tareas de controller dependen de la entrega de Contrato.
- IV: E2E `RecipientValidationE2ETest` (formato rechazado sin persistir; rebote -> bloqueo -> levantar; dos tenants;
  control positivo con direccion valida; aserciones de `Duration` para SC-004), integracion del adaptador Mongo con
  Testcontainers, unitarias con `StepVerifier`. El rebote en pruebas entra por el controller real del webhook.
- VIII/IX: el webhook responde 2xx solo tras persistir la marca; si falla, 5xx para que Brevo reintente. Logs y metricas
  con tenant y `notificationId`, direccion enmascarada.
- Sin consumidor RabbitMQ nuevo: no aplica ack manual ni DLQ.
- Seguridad: secreto solo por variable de entorno, comparacion en tiempo constante, sin PII en logs.

## Riesgos

- Depende de que Brevo envie `messageId` y de que el intento de despacho lo conserve (verificar; si no, anadir el
  campo toca el documento existente).
- Regla de correo demasiado estricta rechazaria direcciones validas: se usa una regla pragmatica y la verificacion real
  queda en el proveedor.
