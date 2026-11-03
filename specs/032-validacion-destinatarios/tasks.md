# Tasks: Validacion de destinatarios y rebotes duros (HU2-116)

**Entrada**: [spec.md](spec.md), [plan.md](plan.md). Cada tarea: prueba primero, luego implementacion.

## Fase 1: utils

- [x] T001 `EmailAddresses` (`isValid`, `normalize`, `mask`) y `EmailAddressesTest` con umbral de 10 000 validaciones en menos de 1 s (FR-002, FR-008, SC-004).
- [x] T002 Filas `INVALID_RECIPIENT` (1006), `WEBHOOK_SECRET_INVALID` (2005) y `RECIPIENT_UNDELIVERABLE` (3040) en `ErrorCode`. La fila `INVALID_RECIPIENT` no se anade a `FailureCause`: la validacion es sincrona y nunca llega a un intento.

## Fase 2: core

- [x] T003 `RecipientFormatPolicy`, `InvalidRecipientException`, `RecipientUndeliverableException` y `RecipientFormatPolicyTest` (FR-001, FR-002, FR-013).
- [x] T004 `UndeliverableRecipient`, `UndeliverableRecipientRepository`, `ProviderMessageLookupPort`, `MessageRecipientRef` y puertos de entrada (`RegisterRecipientBounceUseCase`, `FindUndeliverableMarkQuery`, `LiftUndeliverableMarkUseCase`).
- [x] T005 `RecipientGuardedSendNotificationService`, `RecipientBounceService`, `UndeliverableMarkService` y `RecipientGuardTest` con `StepVerifier` (FR-003 a FR-006, SC-003).
- [x] T006 `SendNotificationBatchService` trata las dos excepciones nuevas como item `rejected`; metodos `recipientRejected` y `recipientMarkedUndeliverable` en `NotificationMetricsPort` (FR-012).

## Fase 3: infrastructure

- [x] T007 `UndeliverableRecipientMongoAdapter` con upsert atomico y `UndeliverableRecipientMongoAdapterTest` con Testcontainers: concurrencia, aislamiento por tenant y consulta por `messageId` (FR-005, FR-007).
- [x] T008 `NotificationMongoAdapter` implementa `ProviderMessageLookupPort`; indice sobre `deliveryAttempts.messageId`.
- [x] T009 Brevo devuelve `messageId` del proveedor; `simulated` genera `simulated-<notificationId>` y `SimulatedNotificationProviderTest` lo afirma (FR-007, FR-011).
- [x] T010 `BrevoBounceWebhookController` con secreto en cabecera `X-Brevo-Webhook-Secret` comparado con `MessageDigest.isEqual`, `UndeliverableRecipientController` (consulta y levantamiento con enmascarado por rol), manejadores y `RouteAuthorizationPolicy` (FR-008 a FR-010).
- [x] T011 `RecipientValidationConfig` (configuracion propia), `notification.brevo.webhook-secret`, ruta publica en `SECURITY_PEP_PUBLIC_PATHS` (ambos perfiles) y `BREVO_WEBHOOK_SECRET` en `.env.example`.
- [x] T012 Metricas `notification.recipient.rejected` y `notification.recipient.undeliverable.marked` en `MicrometerNotificationMetrics`.

## Fase 4: end-to-end y cierre

- [x] T013 E2E `RecipientValidationE2ETest`: formato rechazado sin persistir ni llamar al proveedor; lote con items invalidos; rebote duro por el webhook publico con control positivo y blando; bloqueo en menos de 5 s sin llamar al proveedor; idempotencia; consulta enmascarada; levantamiento solo ADMINISTRADOR; dos tenants; sin direccion ni secreto en logs (SC-001 a SC-003).
- [x] T014 `HexagonalArchitectureTest`, `ModularityTests` y pruebas dirigidas de regresion en verde (ver informe).
- [ ] T015 `./mvnw verify` completo: lo ejecuta el agente de integracion tras el cableado de contrato, no esta historia.

## Desviaciones del plan

- La guarda se aplica con un decorador `RecipientGuardedSendNotificationService` registrado `@Primary` en `RecipientValidationConfig`, no dentro de `SendNotificationService`, para no alterar `UseCaseConfig`. Valida formato antes de resolver el canal. Pendiente de integracion: plegarla en `SendNotificationService` al cablear.
- La consulta por `messageId` vive en un puerto propio (`ProviderMessageLookupPort`) y no en `NotificationRepository`, y el campo indexado es `deliveryAttempts.messageId` (nombre real del documento).
- No hay `SimulatedBounceController`: el mismo webhook admite el `messageId` sintetico `simulated-<notificationId>` (FR-011), sin ruta extra ni dependencia de perfil.
- El nombre de la cabecera del secreto (`X-Brevo-Webhook-Secret`) lo fija esta historia; el contrato solo dice "una cabecera".
- Metricas con la etiqueta `tenant` y nombres `notification.recipient.rejected` / `notification.recipient.undeliverable.marked` (convencion de puntos de los contadores existentes).
