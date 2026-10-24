# Implementation Plan: Linea de tiempo por notificacion (HU2-118)

**Branch**: `feature/HU2-118-linea-tiempo-notificacion` | **Spec**: [spec.md](spec.md)

## Estado del plan

Pendiente de aprobacion. No se genera `tasks.md` hasta que se apruebe.

## Summary

Ampliar el intento de entrega con latencia, causa (enum cerrado) y `correlationId`; persistirlos; y exponer
`GET /notifications/timeline?notificationId=` para OPERADOR o superior, con destinatario enmascarado salvo ADMINISTRADOR.
La causa se deriva en el despacho a partir de la categoria del fallo, sin cambiar `NotificationSenderPort`.

## Technical Context

Java 25, Spring Boot 4.1 WebFlux, Mongo reactivo. Pruebas: JUnit 5 + `StepVerifier`, Testcontainers, `WebTestClient`.

## Constitution Check

- I (hexagonal): enum `FailureCause` y campos nuevos en `core/domain`; el controller solo traduce. Sin Spring en `core`.
- II (contrato primero): el endpoint nuevo NO se agrega en este trabajo al OpenAPI por indicacion expresa; el controller
  se implementa despues de que Contrato publique la operacion. Dependencia registrada, ver Riesgos.
- III: cero comentarios. IV: tarea E2E explicita (abajo). V: commits de una linea. VII: sin excepciones abiertas.
- Consumidores RabbitMQ: no se agregan ni se modifican.

## Diseno

### Core

- `FailureCause` (enum en `core/domain/valueobject`): `PROVIDER_REJECTED` (resultado permanente del proveedor),
  `PROVIDER_TEMPORARY` (resultado recuperable), `PROVIDER_DISABLED`, `ATTACHMENTS_UNSUPPORTED`, `RETRIES_EXHAUSTED`
  (ultimo recuperable que agota reintentos), `UNKNOWN`.
- `DeliveryAttempt`: nuevos componentes `Long latencyMillis`, `FailureCause failureCause`, `CorrelationId correlationId`,
  todos opcionales; las fabricas `of(...)` actuales se conservan (nulos) para no romper llamadores ni historicos.
- `Notification.markDelivered/markRecoverable/markRetriesExhausted/markFailed`: sobrecarga que recibe un
  `AttemptDetail(latencyMillis, failureCause)`; el `correlationId` del intento se toma de la notificacion.
- `DispatchNotificationService`: mide la latencia ya calculada en `attempt(...)` (`System.nanoTime`), la propaga en
  `Outcome` y mapea causa: `ProviderDisabledException` -> `PROVIDER_DISABLED`; adjuntos no soportados ->
  `ATTACHMENTS_UNSUPPORTED`; `PERMANENT_FAILURE` -> `PROVIDER_REJECTED`; `RECOVERABLE_FAILURE` -> `PROVIDER_TEMPORARY`
  o `RETRIES_EXHAUSTED` al agotar. La ruta de reintento manual aplica lo mismo.
- Consulta: se agrega `getTimeline(GetNotificationTimelineQuery)` a `GetNotificationStatusUseCase` y a
  `GetNotificationStatusService` (ya cableado con el repositorio; evita tocar `UseCaseConfig`). Devuelve
  `NotificationTimelineView` (cabecera + intentos ordenados por `occurredOn`). Filtra por `tenantId`; si no coincide, error
  de no encontrado existente.

### Infrastructure

- `DeliveryAttemptDocument`: campos `latencyMillis`, `failureCause` (String), `correlationId` (String); `NotificationDocumentMapper`
  tolera nulos (documentos previos).
- `NotificationTimelineResponse` + `TimelineAttemptResponse`; el enmascarado usa `LogSanitizer.maskRecipient` (`utils`) y
  se aplica en el controller segun `principal.role()`: ADMINISTRADOR completo, resto enmascarado.
- `NotificationController`: `@GetMapping("/timeline")` con `notificationId` por query; reutiliza el patron de `/detail`.
- `RouteAuthorizationPolicy`: `rule(GET, "/notifications/timeline", Role.OPERADOR)`.
- Logs: `DISPATCH_*` nuevos o existentes incluyen `correlationId` y `failureCause` (codigo), nunca el mensaje de la excepcion
  del proveedor ni el destinatario.

### Lista para Contrato y PDP (rutas literales)

| Metodo | Ruta | Rol minimo | Notas |
|---|---|---|---|
| GET | /notifications/timeline | OPERADOR | parametro de consulta `notificationId`; respuesta en `spec.md` |

Sin otras rutas nuevas. El esquema de `deliveryAttempts` de la busqueda NO cambia (se pregunta abajo si ampliarlo).

## Pruebas (resumen; el desglose va en `tasks.md`)

- Unitarias `core`: `DeliveryAttemptTest`, `NotificationTest` (campos nuevos), `DispatchNotificationServiceTest` (causa y
  latencia por rama), `GetNotificationStatusServiceTest` (orden, tenant ajeno, sin intentos).
- Integracion: `NotificationMongoAdapterTest` y `NotificationDocumentMapperTest` (ida y vuelta; documento legado sin campos).
- E2E explicito `NotificationTimelineE2ETest` (`WebTestClient` + Mongo + RabbitMQ): despacho real con proveedor simulado que
  falla y luego acepta; verifica intentos, causa, latencia, `correlationId`; OPERADOR ve enmascarado, ADMINISTRADOR completo,
  CLIENTE 403; dos tenants -> 404 sin datos; sembrar un mensaje crudo de proveedor y afirmar que no aparece en respuesta ni
  en logs capturados; asercion `Duration` < 1 s para 20 intentos (SC-001).
- `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Riesgos

- Contrato primero: el controller no debe fusionarse antes de la operacion en el OpenAPI; `SchemaConformanceTest` fallara si
  se desfasan.
- Con PEP activo el rol lo decide el PDP: sin aprovisionar el recurso (HU2-099) el acceso se niega o se concede mal.
- Latencia solo mide la llamada al proveedor, no la cola.
- Causa gruesa: no distingue timeout de caida del proveedor.
