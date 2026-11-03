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

E3 -> G3 -> G2 -> E10 -> E5. E3 rediseña de una vez los tipos compartidos (plan 031, seccion "Tipos compartidos") y esta
historia se rebasa tras E3. Nombres finales de E3: `ProviderResult(result, providerId, latencyMillis, messageId, cause)` en
`core.port.out` (`NotificationSenderPort.send` devuelve `Mono<ProviderResult>`), `Outcome(ProviderResult last,
List<ProviderResult> failedOver)`, `AttemptRecord(result, origin, providerId, failedOver, latencyMillis, failureCause,
correlationId, messageId)`; `Notification.mark*` y `recordFailedOver` reciben un `AttemptRecord`; `DeliveryAttempt`,
`DeliveryAttemptDocument` y el mapper ya traen `failedOver`, `latencyMillis`, `failureCause`, `correlationId` y `messageId`.

### Core

- Esta historia no añade campos ni firmas: rellena `latencyMillis`, `failureCause` y `correlationId`, que E3 deja nulos, y
  los expone en la linea de tiempo. Los intentos conmutados ya conservan latencia y causa en `Outcome.failedOver`.
- `FailureCause` (enum de E3 en `core/domain/valueobject`, con `TRANSPORT_ERROR` y `PROVIDER_ERROR`): se reutiliza y se
  amplia con `PROVIDER_REJECTED` (resultado permanente del proveedor), `PROVIDER_TEMPORARY` (resultado recuperable),
  `ATTACHMENTS_UNSUPPORTED`, `RETRIES_EXHAUSTED` (ultimo recuperable que agota reintentos), `UNKNOWN`. Los valores de E3 se
  conservan tal cual para los fallos que E3 clasifique (transporte, error del proveedor); la tabla siguiente aplica cuando
  el proveedor solo devuelve el resultado sin causa. No incluye `PROVIDER_DISABLED`: `ProviderDisabledException` y `ChannelNotAvailableException`
  hoy no generan intento (solo metrica y `releaseAndPropagate`); si E3/E5 los convierten en intento, se agrega el codigo
  entonces.
- Tabla de causas (E5 agregara `AttemptResult.INVALID_RECIPIENT`):

  | Origen | Causa |
  |---|---|
  | `PERMANENT_FAILURE` | `PROVIDER_REJECTED` |
  | `RECOVERABLE_FAILURE` | `PROVIDER_TEMPORARY` |
  | recuperable que agota reintentos sin causa o con `PROVIDER_TEMPORARY` | `RETRIES_EXHAUSTED` |
  | recuperable que agota reintentos con causa tecnica (`TRANSPORT_ERROR`, `PROVIDER_ERROR`) | se conserva la causa raiz |
  | adjuntos no soportados | `ATTACHMENTS_UNSUPPORTED` |
  | `INVALID_RECIPIENT` (E5) | `INVALID_RECIPIENT`, que E5 agrega al enum |

- Los historicos quedan en nulo. El `correlationId` del intento (`String` en `AttemptRecord`) es el de la notificacion.
- `DispatchNotificationService`: mide la latencia ya calculada en `attempt(...)` (`System.nanoTime`), la pone en el
  `ProviderResult`, completa `cause` segun la tabla cuando viene nula y construye el `AttemptRecord` con `latencyMillis`,
  `failureCause` y `correlationId`. Solo despacho automatico: `applyOutcome` pasa siempre `AUTOMATIC`;
  `retryManually()` no tiene llamadores y el registro manual queda para G2.
- Consulta: se agrega `getTimeline(GetNotificationTimelineQuery)` a `GetNotificationStatusUseCase` y a
  `GetNotificationStatusService` (ya cableado con el repositorio; evita tocar `UseCaseConfig`). Devuelve
  `NotificationTimelineView` (cabecera + intentos ordenados por `occurredOn`). Filtra por `tenantId`; si no coincide, error
  de no encontrado existente.

### Infrastructure

- `DeliveryAttemptDocument` y `NotificationDocumentMapper` ya traen los campos desde E3 y toleran nulos (documentos previos);
  aqui solo se prueba la ida y vuelta con valores.
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

## Pendientes de integracion

- G2 debe ampliar la firma de `recordOf` en `DispatchNotificationService` para pasar `AttemptOrigin.MANUAL`.
- El script del PDP (HU2-099) debe registrar `GET /notifications/timeline` (rol OPERADOR) en su cableado.
- La latencia de un fallo de transporte se mide desde que se invoca al proveedor hasta el error; el fallo por adjuntos no soportados no tiene llamada y queda sin latencia.
