# Implementation Plan: Failover entre proveedores del canal

**Branch**: `feature/HU2-115-failover-proveedores` | **Spec**: [spec.md](spec.md)

## Resumen

El despacho recorre la ruta ordenada del canal: omite proveedores deshabilitados o con circuito
abierto, y conmuta al siguiente solo ante `RECOVERABLE_FAILURE` (FR-001, FR-002, FR-003). Los fallos
previos al resultado final se anexan como intentos marcados `failedOver` (FR-004); el circuito vive en
un decorador de infraestructura; `simulated` se habilita solo por perfil (FR-008).

## Technical Context

Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo. Pruebas: JUnit 5 + Mockito + `StepVerifier`;
Testcontainers para integración y E2E. Cobertura >= 80 % líneas / 70 % ramas.

## Constitution Check

| Principio | Estado |
|---|---|
| I Hexagonal | El bucle de failover va en `core` (`DispatchNotificationService`), sin framework. El circuito es un decorador de `NotificationSenderPort` en `infrastructure`; `core` solo conoce una excepción propia `ProviderCircuitOpenException`. |
| II Contract-first | No hay endpoint nuevo. Cambia el esquema `DeliveryAttempt`: lo aplica Contrato (ver Peticiones). |
| III Sin comentarios | Se respeta. |
| IV Calidad | Unitarias en `core`, integración del decorador, E2E del failover y de `simulated` deshabilitado, ArchUnit en verde. |
| V Git | Commits de una línea. |
| VII Sin atajos | Sin excepciones abiertas; la dependencia del circuito se pide, no se reemplaza por un parche. |
| VIII Confiabilidad | Un fallo recuperable nunca se pierde: o se conmuta, o queda `RECOVERABLE`/reintento/DLQ. `simulated` fuera de desarrollo no entrega. Sin excepción al ack manual + DLQ: no se toca el consumidor. |
| IX Observabilidad | Cada proveedor probado deja intento y métrica `dispatchAttempted`. |

## Diseño

### core

- `DeliveryAttempt` y `Notification`: ver "Tipos compartidos". `recoverableAttemptsInCurrentCycle()`
  excluye los intentos con `failedOver=true` (FR-006).
- `ProviderCircuitOpenException` (nueva, `core/exception`) con el `providerId`.
- `DispatchNotificationService.sendThrough`: sustituye el envío al preferente por un recorrido secuencial
  (`concatMap`, no `flatMap`) sobre `route.providers()`:
  1. Resuelve cada `ProviderId` con `registry.find`; un proveedor sin adaptador se omite sin intento si
     hay otros invocables (FR-013) y, si ninguno lo es, aborta con `ProviderNotAvailableException`
     como en 007.
  2. Omite el proveedor si `disabledReason()` está presente o si `send` falla con
     `ProviderCircuitOpenException` o `ProviderDisabledException` (sin intento, sin métrica de fallo).
  3. `ACCEPTED` o `PERMANENT_FAILURE`: termina con ese `Outcome`.
  4. `RECOVERABLE_FAILURE`: si queda candidato, guarda su `ProviderResult` en la lista de conmutados de la ronda y
     continúa; si es el último, termina con ese `Outcome`.
  5. Si ningún candidato fue invocable: error de configuración como hoy (`ProviderDisabledException` o
     `ProviderNotAvailableException`), que libera la reserva y sigue el camino existente.
  - Error de transporte: toda excepción del envío salvo `ProviderDisabledException` y
    `ProviderCircuitOpenException` se mapea a `RECOVERABLE_FAILURE`, deja intento del proveedor que la
    lanzó, y cuenta `dispatchAttempted` RECOVERABLE (FR-012). Si todos terminan así, el resultado es el
    del último (FR-009).
  - Todos con circuito abierto: se lanza `AllProvidersCircuitOpenException` (nueva, `core/exception`).
    El `doOnError` actual de `ProviderDisabledException` (líneas 111-115, emite PERMANENT_FAILURE) NO se
    aplica a esta excepción ni a `ProviderCircuitOpenException`: sin métrica de intento, sin intentos,
    estado intacto. Pasa por `releaseAndPropagate` y el consumidor aplica el reintento/DLQ existente
    (FR-011).
  - Los intentos conmutados viven en memoria (`Outcome`) hasta `persistAndPublish`; si el guardado
    falla se pierden y la reentrega puede repetir el envío por el proveedor A (FR-015).

### Tipos compartidos (nombres finales)

Esta historia hace el rediseño único; G3, G2, E10 y E5 solo rellenan campos, en ese orden
(E3 -> G3 -> G2 -> E10 -> E5), y no se implementan aquí.

`core.port.out`:

- `ProviderResult(AttemptResult result, ProviderId providerId, Long latencyMillis, String messageId,
  FailureCause cause)`: resultado de envío. Los tres últimos son opcionales (nulos) hasta que G3
  (latencia, causa) y E10 (messageId) los rellenen. Fábricas: `ProviderResult.of(result, providerId)` y
  `ProviderResult.of(result)` (proveedor nulo; lo completa el servicio que conoce el sender).
- `NotificationSenderPort.send(Notification)` pasa a `Mono<ProviderResult>`. Cambian
  `BrevoNotificationProvider`, `TwilioNotificationProvider`, `FcmNotificationProvider`,
  `SimulatedNotificationProvider`, `ObservedNotificationSender` y `CircuitBreakerNotificationSender`.
  Los `*ResponseClassifier` siguen devolviendo `AttemptResult` y el adaptador los envuelve con
  `ProviderResult.of(result)`, así sus pruebas no cambian. Las pruebas de adaptador que afirman
  `expectNext(AttemptResult.X)` pasan a `.map(ProviderResult::result)`; las que mockean el puerto
  (`DispatchNotificationServiceTest`) devuelven `ProviderResult.of(X)`.
- `AttemptRecord(AttemptResult result, AttemptOrigin origin, ProviderId providerId, boolean failedOver,
  Long latencyMillis, FailureCause failureCause, String correlationId, String messageId)`. Los cuatro
  últimos opcionales: `latencyMillis`, `failureCause` y `correlationId` reservados a G3, `messageId` a
  E10; `failedOver` lo fija E3. Fábricas `AttemptRecord.of(result, origin, providerId)` (failedOver falso,
  resto nulo) y `AttemptRecord.from(ProviderResult, origin)`.

`core.domain.valueobject`:

- `FailureCause` (enum nuevo) con `TRANSPORT_ERROR` y `PROVIDER_ERROR`; G3 añade los que necesite.

`core.domain`:

- `DeliveryAttempt(Instant occurredOn, AttemptResult result, AttemptOrigin origin, ProviderId providerId,
  int cycle, boolean failedOver, Long latencyMillis, FailureCause failureCause, String correlationId,
  String messageId)`. Se conservan las fábricas `of(...)` de cuatro y cinco argumentos (`failedOver`
  falso, opcionales nulos) y se añade `DeliveryAttempt.of(Instant, AttemptRecord, int cycle)`.
- `Notification.markDelivered/markRecoverable/markRetriesExhausted/markFailed(AttemptRecord)` y
  `recordFailedOver(AttemptRecord)` (intento recuperable con `failedOver=true`, sin transición ni
  evento). Cada `mark*` fija el resultado que le corresponde; `Notification` no recibe un `AttemptResult`
  suelto (E5 asume esto). G2 aporta `origin` por el mismo registro; E5 añade un `mark*` nuevo si lo
  necesita.
- `DispatchNotificationService` (privado): `Outcome(ProviderResult last, List<ProviderResult> failedOver)`.
  `FailedProvider` no existe: los conmutados conservan latencia, causa y messageId.

Infraestructura:

- `DeliveryAttemptDocument` gana `failedOver`, `latencyMillis`, `failureCause`, `correlationId` y
  `messageId`; `NotificationDocumentMapper` los copia en ambos sentidos. Un documento viejo sin esos
  campos se lee con `failedOver=false` y opcionales nulos.
- `AttemptResult` sigue enum cerrado; E5 añade `INVALID_RECIPIENT`. Se deben revisar TODOS los `switch`
  y mapeos sobre `AttemptResult`, entre ellos `MicrometerNotificationMetrics.resultLabel` (exhaustivo
  sin `default`), `ProviderLogs`, `applyOutcome` (lo no reconocido se trata como fallo permanente) y
  `DeliveryAttemptDocument`.

### infrastructure

- `CircuitBreakerNotificationSender` (nuevo, `adapter/out/provider`): decora `NotificationSenderPort`
  con `CircuitBreaker` de resilience4j (operador reactor). Cuenta como fallo `RECOVERABLE_FAILURE` y
  excepciones; `PERMANENT_FAILURE` y `ACCEPTED` cuentan como éxito. Con circuito abierto emite
  `ProviderCircuitOpenException`.
- `ProviderCircuitProperties` (`notification.provider.circuit-breaker.*`): `failure-rate-threshold`
  (50), `minimum-calls` (5), `sliding-window-size` (10), `open-wait` (30s), `half-open-calls` (2). Un
  circuito por `providerId`, creado en el decorador.
- `ObservedProviderConfig.notificationSenderRegistry`: apila `CircuitBreakerNotificationSender` entre
  el proveedor y `ObservedNotificationSender`. Es cableado de proveedores, no `UseCaseConfig`.
- `SimulatedProviderProperties`: nuevo `enabled` (por defecto `false`).
  `SimulatedNotificationProvider.disabledReason()` devuelve un motivo cuando `enabled=false`; `send`
  devuelve `ProviderDisabledException`, igual que Brevo.
- `ConfigurationConfig.fixedConfiguration`: añade `SIMULATED` a `enabledProviders` solo si
  `simulated.enabled`.
- Los circuitos son por `providerId` y globales por réplica, no por tenant.
- Catálogo por defecto reordenado: `${NOTIFICATION_EMAIL_PROVIDERS:brevo,simulated}`,
  `${NOTIFICATION_SMS_PROVIDERS:twilio,simulated}`, `${NOTIFICATION_PUSH_PROVIDERS:fcm,simulated}`
  (FR-014).
- `ChannelCatalogSeeder` hoy solo registra las diferencias de un catálogo ya almacenado. Tarea del plan:
  si la lista almacenada de un canal es exactamente el orden antiguo por defecto (`simulated,<real>`) y
  la configurada es el nuevo, el seeder la actualiza; cualquier otra divergencia se sigue solo
  registrando. Pruebas en `ChannelCatalogSeederTest` (antiguo se actualiza; personalizado no se toca) y
  cobertura en `ChannelCatalogE2ETest`.
- `application.yml`: `notification.provider.simulated.enabled: ${SIMULATED_PROVIDER_ENABLED:false}`;
  `application-local.yml`: `enabled: true`. Las pruebas existentes que dependen de `simulated` pasan a
  habilitarlo (propiedad de prueba o `@ActiveProfiles`), ver Riesgos.
- `DeliveryAttemptResponse`: expone `failedOver` (petición de contrato).

## Pruebas

| Nivel | Qué |
|---|---|
| Unitaria `core` (`DispatchNotificationServiceTest`) | A recuperable y B acepta; A y B recuperables; A permanente sin conmutar; A acepta y B sin invocar; deshabilitado y circuito abierto omitidos sin intento; todos omitidos y error; orden del historial; el presupuesto de reintentos no cuenta `failedOver` (FR-006). `StepVerifier`. |
| Unitaria `core` (`NotificationTest`) | `recordFailedOver` y `recoverableAttemptsInCurrentCycle`. |
| Unitaria infra | `CircuitBreakerNotificationSenderTest`: abre al umbral, no invoca abierto, semiabierto cierra con éxito, permanentes no abren, circuitos independientes. Tiempo de omisión con aserción de `Duration` (SC-002). |
| Integración | Mapeo Mongo de `failedOver` con Testcontainers, incluyendo documento viejo sin el campo. |
| Propiedad/Config | `simulated` deshabilitado con el perfil por defecto, habilitado con `local` y con la propiedad de pruebas (SC-003). |
| E2E (`ProviderFailoverE2ETest`) | `@SpringBootTest` + Testcontainers Mongo y RabbitMQ + `WebTestClient`: crear notificación por la API con ruta `[failing-test, simulated]`, esperar `DELIVERED`, consultar historial y afirmar fallido + entregador (SC-001, SC-004); caso sin habilitar `simulated`: queda `RECOVERABLE` y `simulated` no entrega (SC-003); dos tenants: el failover de uno no altera al otro (FR-010). El proveedor de prueba que falla es un bean de `src/test` con `providerId` propio. |
| Unitaria `core` (`RequeuePendingNotificationsServiceTest`) | `RequeuePendingNotificationsService` (línea ~150) reutiliza `recoverableAttemptsInCurrentCycle`: prueba con intentos `failedOver` que no cuentan para el agotamiento. |
| E2E (`ProviderFailoverCircuitE2ETest`) | Circuito abierto con failover real: tras fallos hasta el umbral, A recibe 0 invocaciones y la entrega sale por B sin intento de A; aserción de `Duration` (SC-002). Caso "todos abiertos" (US3.4, FR-011): la notificación conserva su estado, sin intentos ni métrica de fallo, y el mensaje sigue el camino de reintento/DLQ. Control positivo: tras la espera el circuito admite la prueba y entrega. |
| Unitaria `core` (transporte) | Excepción de transporte en A conmuta a B y deja intento de A; excepción en todos deja el resultado del último; ruta parcial con proveedor sin adaptador se omite; ruta sin invocables aborta como 007. |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` en verde. |

Control positivo: el caso "simulated deshabilitado" se acompaña del caso habilitado en la misma clase.

## Peticiones a otros equipos

- **Orden de ejecución**: la implementación empieza después del cambio de contrato (contrato primero).
- **Dependencia (pom.xml)**: aprobada. `io.github.resilience4j:resilience4j-circuitbreaker` y
  `resilience4j-reactor` en `infrastructure`, con la versión fijada en `dependencyManagement` del
  `pom.xml` raíz; es la única dependencia nueva del nivel. No se usa `resilience4j-spring-boot3`
  (prohibido por el enforcer). Compatibilidad con Java 25 y con la versión de Reactor del proyecto: sin
  verificar; se comprueba al elegir la versión y antes de implementar.
- **Pendientes con dueño (Principio VII)**: segundo proveedor real por canal (E1/E2), dueño AP, fecha
  2026-12-15; circuito compartido entre réplicas, dueño Equipo de backend, fecha 2026-12-15.
- **Contrato (`api-notificaciones.yaml`)**: añadir `failedOver: boolean` (no requerido, por defecto
  `false`) al esquema `DeliveryAttempt`. Sin endpoints nuevos.
- **Wiring (`UseCaseConfig`)**: sin cambios previstos.

## Riesgos

- Las pruebas existentes que asumen `simulated` siempre habilitado fallarán hasta habilitarlo; afecta a
  `FixedConfiguration` y a los E2E. Mitigación: `application-test` o propiedad en la base común de
  pruebas, un único cambio.
- El catálogo por defecto se reordena (real primero); ver Diseño. El orden previo era `simulated,real`.
- Mezclar mensajería de apertura del circuito con reintentos de la cola puede duplicar espera; la
  política cuenta solo rondas (FR-006), no es un riesgo de agotar reintentos por conmutación.
- Circuito en memoria por instancia: cada réplica aprende por separado. Aceptado para esta historia.

## Estructura de archivos

```text
core/.../domain/DeliveryAttempt.java                MODIFICADO
core/.../domain/Notification.java                   MODIFICADO
core/.../exception/ProviderCircuitOpenException.java NUEVO
core/.../usecase/DispatchNotificationService.java   MODIFICADO
infrastructure/.../provider/CircuitBreakerNotificationSender.java  NUEVO
infrastructure/.../provider/ProviderCircuitProperties.java         NUEVO
infrastructure/.../provider/ObservedProviderConfig.java            MODIFICADO
infrastructure/.../provider/SimulatedNotificationProvider.java     MODIFICADO
infrastructure/.../provider/SimulatedProviderProperties.java       MODIFICADO
infrastructure/.../config/ConfigurationConfig.java                 MODIFICADO
infrastructure/.../mongo/DeliveryAttemptDocument.java, NotificationDocumentMapper.java  MODIFICADO
infrastructure/.../rest/DeliveryAttemptResponse.java                MODIFICADO
infrastructure/src/main/resources/application.yml, application-local.yml  MODIFICADO
```
