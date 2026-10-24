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
- II (contrato primero): el carril Contrato publica `GET /notifications/timeline` en `api-notificaciones.yaml` antes de
  que se implemente el controller; este trabajo no edita ese archivo.
- III: cero comentarios. IV: tarea E2E explicita (abajo). V: commits de una linea.
- VII (excepcion): la dependencia de que Contrato publique la operacion y de E3/G2 se acepta de forma temporal. Dueño:
  Equipo de backend. Fecha limite: 2026-12-15.
- Consumidores RabbitMQ: no se agregan ni se modifican.

## Diseno

### Orden de integracion

E3 -> G3 -> G2 -> E10 -> E5. E3 rediseña de una vez `DeliveryAttempt`, `DeliveryAttemptDocument`,
`NotificationDocumentMapper`, `Notification.mark*`, `AttemptResult` y `Outcome`. Todo lo siguiente es un incremento sobre
ese rediseño (se rebasa tras E3); no se redefine `failedOver`.

### Core

- `FailureCause` (enum en `core/domain/valueobject`): `PROVIDER_REJECTED` (resultado permanente del proveedor),
  `PROVIDER_TEMPORARY` (resultado recuperable), `ATTACHMENTS_UNSUPPORTED`, `RETRIES_EXHAUSTED` (ultimo recuperable que
  agota reintentos), `UNKNOWN`. No incluye `PROVIDER_DISABLED`: `ProviderDisabledException` y `ChannelNotAvailableException`
  hoy no generan intento (solo metrica y `releaseAndPropagate`); si E3/E5 los convierten en intento, se agrega el codigo
  entonces.
- Tabla de causas (E5 agregara `AttemptResult.INVALID_RECIPIENT`):

  | Origen | Causa |
  |---|---|
  | `PERMANENT_FAILURE` | `PROVIDER_REJECTED` |
  | `RECOVERABLE_FAILURE` | `PROVIDER_TEMPORARY` |
  | recuperable que agota reintentos | `RETRIES_EXHAUSTED` |
  | adjuntos no soportados | `ATTACHMENTS_UNSUPPORTED` |
  | `INVALID_RECIPIENT` (E5) | `INVALID_RECIPIENT`, que E5 agrega al enum |

- `DeliveryAttempt`: incremento con `Long latencyMillis`, `FailureCause failureCause`, `CorrelationId correlationId`,
  opcionales, sobre la forma resultante de E3; los historicos quedan en nulo.
- `Notification.mark*`: incremento sobre las firmas de E3 (hoy 4: `markDelivered`, `markRecoverable`,
  `markRetriesExhausted`, `markFailed`) con un `AttemptDetail(latencyMillis, failureCause)`; el `correlationId` del intento
  es el de la notificacion.
- `DispatchNotificationService`: mide la latencia ya calculada en `attempt(...)` (`System.nanoTime`), la propaga en
  `Outcome` (de E3) y mapea la causa segun la tabla. Solo despacho automatico: `applyOutcome` pasa siempre `AUTOMATIC`;
  `retryManually()` no tiene llamadores y el registro manual queda para G2.
- Consulta: se agrega `getTimeline(GetNotificationTimelineQuery)` a `GetNotificationStatusUseCase` y a
  `GetNotificationStatusService` (ya cableado con el repositorio; evita tocar `UseCaseConfig`). Devuelve
  `NotificationTimelineView` (cabecera + intentos ordenados por `occurredOn`). Filtra por `tenantId`; si no coincide, error
  de no encontrado existente.

### Infrastructure

- `DeliveryAttemptDocument`: campos `latencyMillis`, `failureCause` (String), `correlationId` (String); `NotificationDocumentMapper`
  tolera nulos (documentos previos).
- `NotificationTimelineResponse` + `TimelineAttemptResponse`; el enmascarado usa `LogSanitizer.maskRecipient` (`utils`) y
  se aplica en el controller segun `principal.role()`: ADMINISTRADOR completo, resto enmascarado.
- `NotificationController` (`adapter/in/rest`): `@GetMapping("/timeline")` con `notificationId` por query; reutiliza el
  patron de `/detail`.
- `RouteAuthorizationPolicy` (`adapter/in/web`): `rule(GET, "/notifications/timeline", Role.OPERADOR)`.
- Matriz de roles: `GET /notifications/detail` sigue en CLIENTE; `GET /notifications/timeline` es OPERADOR. La politica local
  y la lista de recursos del PDP (script HU2-099) cambian a la vez, en el mismo cambio. Hay 3 roles en el codigo hasta B2.
- Logs: `DISPATCH_*` nuevos o existentes incluyen `correlationId` y `failureCause` (codigo), nunca el mensaje de la excepcion
  del proveedor ni el destinatario.

### Lista para Contrato y PDP (rutas literales)

| Metodo | Ruta | Rol minimo | Notas |
|---|---|---|---|
| GET | /notifications/timeline | OPERADOR | parametro de consulta `notificationId`; respuesta en `spec.md` |

Sin otras rutas nuevas. El esquema de `deliveryAttempts` de la busqueda NO cambia (se pregunta abajo si ampliarlo).

## Pruebas (resumen; el desglose va en `tasks.md`)

- Unitarias `core`: `DeliveryAttemptTest`, `NotificationTest` (campos nuevos), `DispatchNotificationServiceTest` (causa y
  latencia por rama automatica), `GetNotificationStatusServiceTest` (orden, tenant ajeno, sin intentos).
- Integracion: `NotificationMongoAdapterTest` y `NotificationDocumentMapperTest` (ida y vuelta; documento legado sin campos).
- E2E explicito `NotificationTimelineE2ETest` (`WebTestClient` + Mongo + RabbitMQ): despacho real con proveedor simulado que
  falla y luego acepta (sin origen `MANUAL`, que depende de G2); verifica intentos, causa, latencia, `correlationId`;
  OPERADOR ve enmascarado, ADMINISTRADOR completo, CLIENTE 403; dos tenants -> 404 sin datos; sembrar un mensaje crudo de
  proveedor y afirmar que no aparece en respuesta ni en logs capturados, con control positivo de que ese log si recoge las
  lineas `DISPATCH_*`; asercion `Duration` < 1 s para 20 intentos (SC-001).
- `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Riesgos

- Contrato primero: el controller no debe fusionarse antes de la operacion en el OpenAPI; `SchemaConformanceTest` fallara si
  se desfasan. Publica el carril Contrato (excepcion VII, dueño Equipo de backend, 2026-12-15).
- Se implementa tras E3; antes habria conflicto con su rediseño de `DeliveryAttempt`.
- Con PEP activo el rol lo decide el PDP: sin aprovisionar el recurso (HU2-099) el acceso se niega o se concede mal.
- Latencia solo mide la llamada al proveedor, no la cola.
- Causa gruesa: no distingue timeout de caida del proveedor.
