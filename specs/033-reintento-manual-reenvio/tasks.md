# Tasks: Reintento manual y reenvío por otro canal (033)

**Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Dependencias externas (otros carriles, no se editan aquí): YAML `api-notificaciones.yaml` (rutas `available`, `maxLength: 255` de `externalId`), `UseCaseConfig` (aquí se usa `RetryConfig`), `ErrorCode` dedicados y rutas del PDP.

## Fase 1: Núcleo

- [x] T001 Firma `dispatch(NotificationId, AttemptOrigin)` en `DispatchNotificationUseCase` (con `dispatch(id)` por defecto AUTOMATIC) y `recordOf(result, origin, correlationId)` en `DispatchNotificationService`; prueba MANUAL/AUTOMATIC en `DispatchNotificationServiceTest` ([FR-008](./spec.md#fr-008))
- [x] T002 `NotificationEventPublisherPort.enqueueForDispatch(n, origin)`; `SendNotificationService` y `RequeuePendingNotificationsService` pasan `AttemptOrigin.AUTOMATIC`; ajuste de pruebas existentes
- [x] T003 Retirar `Notification.retryManually()` y sus tres pruebas en `NotificationTest` ([FR-007](./spec.md#fr-007))
- [x] T004 `NotificationStatusView.from(Notification)` (movido desde `GetNotificationStatusService`)
- [x] T005 `NotificationStateNotAllowedException`, `ResendChannelInvalidException`
- [x] T006 `RetryNotificationCommand/UseCase` + `RetryNotificationService` + `RetryNotificationServiceTest` (estados, dos tenants, carrera de versión, contador sin reiniciar)
- [x] T007 `ResendNotificationCommand/UseCase` + `ResendNotificationService` + `ResendNotificationServiceTest` (estado, canal igual, adjuntos, límite de `externalId`, duplicado a 409, aislamiento)

## Fase 2: Infraestructura

- [x] T008 `RabbitTopologyProperties.ATTEMPT_ORIGIN_HEADER`; `NotificationRabbitPublisher` escribe `x-attempt-origin`; prueba en `NotificationRabbitPublisherTest`
- [x] T009 `NotificationDispatchListener` lee el encabezado (ausente = AUTOMATIC, desconocido = DLQ); pruebas en `NotificationDispatchListenerTest`
- [x] T010 Dependencia externa verificada: el YAML de la base declara `/notification-retries` y `/notification-resends` (`planned`); el paso a `available` queda para Contrato
- [x] T011 `NotificationRetryController`, `NotificationResendController`, requests, manejadores 400 en `NotificationExceptionHandler`
- [x] T012 `RetryConfig` (beans de los dos casos de uso, sin tocar `UseCaseConfig`)
- [x] T013 Regla `POST /notification-resends` OPERADOR en `RouteAuthorizationPolicy` + prueba

## Fase 3: Pruebas de extremo a extremo

- [x] T014 E2E `NotificationRetryE2ETest` (Mongo + RabbitMQ): entregado en <= 5 s con origen MANUAL, presupuesto sin reiniciar, estado no admitido 400 sin efectos, dos tenants con 404 idéntico, 403 a CLIENTE, dos solicitudes simultáneas con un solo intento ([SC-001..006](./spec.md#success-criteria-mandatory))
- [x] T015 E2E `NotificationResendE2ETest`: FAILED por correo reenviada por SMS entregada en <= 5 s con la original intacta, 409 al repetir, 400 por canal igual/desconocido/estado, 404 entre tenants y 403 a CLIENTE, dos reenvíos simultáneos con una sola notificación, dirección ausente de respuesta y logs ([SC-007, SC-008](./spec.md#success-criteria-mandatory))
- [x] T016 `HexagonalArchitectureTest` y `ModularityTests` en verde
- [ ] T017 Cierre: `./mvnw -B -ntp verify` una vez en segundo plano (no ejecutado por el agente; lo corre el integrador tras el cableado)

## Desviaciones respecto al plan

- **Reenvío con adjuntos**: `SendNotificationCommand` recibe `AttachmentSubmission` (ids de carga) y no los `Attachment` ya resueltos de la original; copiarlos no es posible sin volver a subirlos. Se rechaza con 400 (`ResendChannelInvalidException`) en lugar de perder el adjunto en silencio. Pendiente con dueño Equipo de backend, revisión 2026-12-15.
- **Duplicado de `externalId`**: `SendNotificationService` devuelve `duplicate=true` y no lanza `NotificationAlreadyAcceptedException`; el reenvío convierte ese resultado en la excepción para responder 409.
- **`ErrorCode`**: no se añadieron códigos; 400 por estado usa `INVALID_STATUS_TRANSITION` y 400 por canal usa `CHANNEL_NOT_AVAILABLE`. Códigos propios, para el carril de cableado.
- **Firma por defecto**: `DispatchNotificationUseCase.dispatch(id)` se conserva como método por defecto que delega con AUTOMATIC, para no reescribir 54 llamadas de prueba; el consumidor siempre usa la forma con origen.
- **E2E de coincidencia manual contra automático** (plan, Decisiones de la revisión): no implementado; la carrera queda cubierta por el E2E de dos reintentos simultáneos y la prueba unitaria de conflicto de versión.
- **Carrera de reintento**: la solicitud que llega después de guardarse el primer reintento ve PENDING y recibe 400; solo la verdaderamente simultánea recibe 409. El E2E acepta 400 o 409 para la perdedora.
