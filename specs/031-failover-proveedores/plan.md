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

- `DeliveryAttempt`: nuevo componente `boolean failedOver` (por defecto `false`; las fábricas `of`
  existentes lo conservan en `false`).
- `Notification`:
  - nuevo `recordFailedOver(origin, providerId)`: anexa un intento `RECOVERABLE_FAILURE` con
    `failedOver=true`, sin transición de estado ni evento.
  - `recoverableAttemptsInCurrentCycle()` excluye los `failedOver` (FR-006).
- `ProviderCircuitOpenException` (nueva, `core/exception`) con el `providerId`.
- `DispatchNotificationService.sendThrough`: sustituye el envío al preferente por un recorrido secuencial
  (`concatMap`, no `flatMap`) sobre `route.providers()`:
  1. Resuelve cada `ProviderId` con `registry.resolve` (sin adaptador: `ProviderNotAvailableException`,
     igual que 007 si es el caso de todos).
  2. Omite el proveedor si `disabledReason()` está presente o si `send` falla con
     `ProviderCircuitOpenException` o `ProviderDisabledException` (sin intento, sin métrica de fallo).
  3. `ACCEPTED` o `PERMANENT_FAILURE`: termina con ese `Outcome`.
  4. `RECOVERABLE_FAILURE`: si queda candidato, guarda el `providerId` fallido en una lista de la ronda y
     continúa; si es el último, termina con ese `Outcome`.
  5. Si ningún candidato fue invocable: error de configuración como hoy (`ProviderDisabledException` o
     `ProviderNotAvailableException`), que libera la reserva y sigue el camino existente.
  - `Outcome` gana `List<ProviderId> failedOver`; `persistAndPublish` llama a `recordFailedOver` por
    cada uno antes de `applyOutcome`, de modo que el orden del historial es fallidos y luego final.
  - Error de transporte (excepción distinta de las anteriores) se mapea a `RECOVERABLE_FAILURE` para la
    conmutación.

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
- `application.yml`: `notification.provider.simulated.enabled: ${SIMULATED_PROVIDER_ENABLED:false}`;
  `application-local.yml`: `enabled: true`. Las pruebas existentes que dependen de `simulated` pasan a
  habilitarlo (propiedad de prueba o `@ActiveProfiles`), ver Riesgos.
- `DeliveryAttemptDocument` y `NotificationDocumentMapper`: campo `failedOver`; los documentos viejos
  sin el campo se leen como `false`. `DeliveryAttemptResponse`: expone `failedOver`.

## Pruebas

| Nivel | Qué |
|---|---|
| Unitaria `core` (`DispatchNotificationServiceTest`) | A recuperable y B acepta; A y B recuperables; A permanente sin conmutar; A acepta y B sin invocar; deshabilitado y circuito abierto omitidos sin intento; todos omitidos y error; orden del historial; el presupuesto de reintentos no cuenta `failedOver` (FR-006). `StepVerifier`. |
| Unitaria `core` (`NotificationTest`) | `recordFailedOver` y `recoverableAttemptsInCurrentCycle`. |
| Unitaria infra | `CircuitBreakerNotificationSenderTest`: abre al umbral, no invoca abierto, semiabierto cierra con éxito, permanentes no abren, circuitos independientes. Tiempo de omisión con aserción de `Duration` (SC-002). |
| Integración | Mapeo Mongo de `failedOver` con Testcontainers, incluyendo documento viejo sin el campo. |
| Propiedad/Config | `simulated` deshabilitado con el perfil por defecto, habilitado con `local` y con la propiedad de pruebas (SC-003). |
| E2E (`ProviderFailoverE2ETest`) | `@SpringBootTest` + Testcontainers Mongo y RabbitMQ + `WebTestClient`: crear notificación por la API con ruta `[failing-test, simulated]`, esperar `DELIVERED`, consultar historial y afirmar fallido + entregador (SC-001, SC-004); caso sin habilitar `simulated`: queda `RECOVERABLE` y `simulated` no entrega (SC-003); dos tenants: el failover de uno no altera al otro (FR-010). El proveedor de prueba que falla es un bean de `src/test` con `providerId` propio. |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` en verde. |

Control positivo: el caso "simulated deshabilitado" se acompaña del caso habilitado en la misma clase.

## Peticiones a otros equipos

- **Dependencia (pom.xml)**: `io.github.resilience4j:resilience4j-circuitbreaker` y
  `resilience4j-reactor`, en `infrastructure`, versión alineada al BOM del proyecto. No se usa
  `resilience4j-spring-boot3` (prohibido por el enforcer del `pom.xml`). Alternativa si se rechaza:
  circuito propio de unas 60 líneas con ventana deslizante en `infrastructure`, con más código y más
  pruebas que mantener.
- **Contrato (`api-notificaciones.yaml`)**: añadir `failedOver: boolean` (no requerido, por defecto
  `false`) al esquema `DeliveryAttempt`. Sin endpoints nuevos.
- **Wiring (`UseCaseConfig`)**: sin cambios previstos.

## Riesgos

- Las pruebas existentes que asumen `simulated` siempre habilitado fallarán hasta habilitarlo; afecta a
  `FixedConfiguration` y a los E2E. Mitigación: `application-test` o propiedad en la base común de
  pruebas, un único cambio.
- El catálogo por defecto declara `simulated` primero (`simulated,brevo`, `simulated,twilio`,
  `simulated,fcm`). Fuera de desarrollo `simulated` se omitirá y el real queda primero de hecho, pero el
  orden declarado es engañoso; conviene reordenar el catálogo o los valores por defecto del env.
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
