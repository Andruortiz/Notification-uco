---

description: "Task list for HU2-022 suscripción por evento al Componente de Parámetros"
---

# Tasks: Suscripción por evento al Componente de Parámetros

**Input**: Design documents from `/specs/022-suscripcion-parametros/`

**Prerequisites**: plan.md (Aceptado, v1, 2026-10-08), spec.md, research.md, data-model.md,
contracts/parametros-evento-supuesto.md, quickstart.md.

**Restricción de esta entrega**: el usuario pidió NO ejecutar pruebas. Se escribió todo el código de producción y
todos los archivos de prueba, y solo se comprobó que compilan (`-DskipTests compile` y `test-compile`) y el
formato (`spotless:apply`). Las tareas de ejecución de pruebas, cobertura, E2E, `verify` y quickstart quedan SIN
marcar con la nota "pendiente: el usuario pidió no ejecutar pruebas". Ningún SC ni gate que dependa de haber
corrido pruebas se da por cumplido. El orden "prueba que falla -> implementar -> prueba en verde" no pudo
cumplirse: las pruebas se escribieron junto con el código y no se vieron fallar.

**Reglas de código**: cero comentarios explicativos en código nuevo o tocado (Principio III);
`./mvnw -B -ntp spotless:apply` tras crear o modificar `.java`; `core` sin Spring (Principio I).

Los paquetes Java viven bajo `core/src/main/java/co/edu/uco/notification/core/`,
`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/` y sus equivalentes `src/test/java`.

---

## Phase 1: Núcleo (US1, US2 -- ruta compartida)

- [X] T001 `core/.../port/in/ReceivePublishedConfigurationUseCase.java`: puerto de entrada
  `receive(ConfigurationChange)`.
- [X] T002 `core/.../usecase/ReceivePublishedConfigurationService.java`: aplica el cambio y persiste la última
  conocida con la lógica extraída de `SynchronizeConfigurationService`; el fallo de persistencia se registra y no
  deshace el cambio.
- [X] T003 `core/.../usecase/SynchronizeConfigurationService.java`: `synchronize()` = `fetchState()` +
  `receive(change)`; constructor reducido a fuente y caso de uso de recepción.
- [X] T004 `core/src/test/.../usecase/ReceivePublishedConfigurationServiceTest.java`: aplicado y persistido,
  rechazado sin persistir, obsoleto y duplicado sin persistir de nuevo, almacén caído no deshace el cambio.
  (escrita, no ejecutada)
- [X] T005 `core/src/test/.../usecase/SynchronizeConfigurationServiceTest.java` y
  `infrastructure/src/test/.../adapter/in/scheduler/ParametersPollingSchedulerTest.java`: construcción ajustada al
  nuevo constructor; comportamiento esperado idéntico. (ajustadas, no ejecutadas)

## Phase 2: Configuración y topología (US4)

- [X] T006 `infrastructure/.../config/ParametersEventsProperties.java`: record de
  `notification.parameters.events.*`; inactivo sin exchange; falla nombrando `routing-key` o `queue`; defectos
  `topic`, 3 intentos, concurrencia 1; nombres de DLQ derivados de la cola.
- [X] T007 `infrastructure/.../adapter/out/parameters/ParametersConfig.java`: registra
  `ParametersEventsProperties` siempre, para que la validación de arranque no dependa de la activación.
- [X] T008 `infrastructure/.../config/ParametersEventsRabbitConfig.java`: exchange configurable, cola durable con
  DLX, DLQ con su exchange y binding, recuperador de republicación y fábrica de contenedor `MANUAL` con
  `prefetch 1`; todo condicionado a exchange no vacío (`@ConditionalOnExpression`).
- [X] T009 `infrastructure/src/main/resources/application.yml`: `notification.parameters.events.*` con variables
  de entorno `NOTIFICATION_PARAMETERS_EVENTS_*`, sin valores por defecto para exchange, routing key ni cola.
- [X] T010 `utils/.../ErrorCode.java` (`PARAMETERS_EVENT_UNREADABLE`, 3012, permanente de negocio) y la
  enumeración de códigos de `api-notificaciones.yaml` (`NTF-3012`), porque `ErrorResponseContractTest` exige que
  ambas coincidan. No se añade ningún endpoint.

## Phase 3: Listener y observabilidad (US1, US2, US3)

- [X] T011 `infrastructure/.../adapter/in/rabbit/ParametersEventPayload.java`: record `(version, values)` con copia
  defensiva y `toChange()` que falla si falta `version` o `values`.
- [X] T012 `infrastructure/.../adapter/in/rabbit/ParametersEventListener.java`: `@RabbitListener` con ack manual;
  ilegible a DLQ; aplicado, rechazado u obsoleto se confirma con evento; fallo inesperado por
  `ManualAckSettler.handleFailure`; correlación siempre `param-...` generada, ignorando cabeceras del emisor.
- [X] T013 `infrastructure/.../adapter/in/scheduler/ConfigurationEventLogger.java`: campo `transport` (`poll` o
  `event`) en `CONFIG_APPLIED`, `CONFIG_REJECTED` y `CONFIG_IGNORED`; `PARAMETERS_RECOVERED` solo se declara con
  el transporte `poll`.
- [X] T014 `infrastructure/.../config/UseCaseConfig.java`: bean de `ReceivePublishedConfigurationUseCase` y
  `SynchronizeConfigurationUseCase` recableado.
- [X] T015 `README.md`: sección de la suscripción por evento y su contrato supuesto.

## Phase 4: Pruebas escritas (no ejecutadas)

- [X] T016 `infrastructure/src/test/.../config/ParametersEventsPropertiesTest.java`: inactivo, exchange en blanco,
  propiedad faltante nombrada, defectos y derivados, límites no positivos. (escrita, no ejecutada)
- [X] T017 `infrastructure/src/test/.../adapter/in/rabbit/ParametersEventPayloadContractTest.java`: el mismo JSON
  literal del contrato supuesto lo leen `ParametersEventPayload` y `HttpParametersSource.StateResponse` con el mismo
  cambio; forma serializada; version o values ausentes. (escrita, no ejecutada)
- [X] T018 `infrastructure/src/test/.../adapter/in/rabbit/ParametersEventListenerTest.java`: aplicado,
  rechazado, obsoleto, no JSON y sin version o values a DLQ, fallo inesperado bajo y en el límite, republicación
  fallida con `nack` sin requeue, cabecera de correlación ignorada. (escrita, no ejecutada)
- [X] T019 `infrastructure/src/test/.../ParametersEventsInactiveTest.java`: sin exchange no existen colas,
  bindings, fábrica ni listener; con exchange sí existen; exchange sin cola o routing key falla el arranque.
  (escrita, no ejecutada)
- [X] T020 `infrastructure/src/test/.../adapter/in/scheduler/ConfigurationEventLoggerTest.java`: transporte por
  defecto `poll`, `event` cuando se informa, el evento no declara recuperada la fuente HTTP. (escrita, no ejecutada)
- [X] T021 **E2E (Principio IV)** `infrastructure/src/test/.../ParametersEventSubscriptionE2ETest.java`:
  Mongo y RabbitMQ reales; publica con el mismo tipo `ParametersEventPayload` y verifica `GET /configuration`:
  adopción por evento con aserción de `Duration` <= 5 s y persistencia como última conocida; obsoleto y duplicado
  ignorados; ilegibles a la DLQ sin bloquear el siguiente válido; rechazado confirmado sin valores en el log;
  evento perdido recuperado por el sondeo con `Duration` < intervalo + margen; evento con versión ya adoptada
  ignorado. Cada "no cambia" lleva control positivo (la versión válida siguiente sí se adopta). (escrita, no
  ejecutada)

## Phase 5: Verificación (PENDIENTE -- el usuario pidió no ejecutar pruebas)

- [ ] T022 Ejecutar `./mvnw -B -ntp -pl core test`. pendiente: el usuario pidió no ejecutar pruebas
- [ ] T023 Ejecutar las clases nuevas de `infrastructure` (`ParametersEventsPropertiesTest`,
  `ParametersEventPayloadContractTest`, `ParametersEventListenerTest`, `ParametersEventsInactiveTest`,
  `ConfigurationEventLoggerTest`, `ParametersPollingSchedulerTest`). pendiente: el usuario pidió no ejecutar
  pruebas
- [ ] T024 Ejecutar `ParametersEventSubscriptionE2ETest` con Docker (SC-001 a SC-004, SC-006). pendiente: el
  usuario pidió no ejecutar pruebas
- [ ] T025 Ejecutar `HexagonalArchitectureTest` y `ModularityTests`. pendiente: el usuario pidió no ejecutar
  pruebas
- [ ] T026 Ejecutar `./mvnw -B -ntp verify` completo (cobertura 80 % líneas y 70 % ramas, Spotless, SpotBugs,
  FindSecBugs). pendiente: el usuario pidió no ejecutar pruebas
- [ ] T027 Recorrer la demostración manual de `quickstart.md`. pendiente: el usuario pidió no ejecutar pruebas
- [ ] T028 Confirmar SC-005 (arranque <= 30 s sin exchange) en `verify`; `ParametersEventsInactiveTest` solo
  cubre la ausencia de beans, no el arranque completo. pendiente: el usuario pidió no ejecutar pruebas

## Desviaciones del plan

- **Reintento por fallo inesperado**: `ManualAckSettler` republica al intercambio por defecto con la clave igual
  al nombre de la cola, no al exchange de Parámetros, para no difundir el reintento a otras colas atadas a ese
  exchange (plan D5 solo decía "reutilizar el settler").
- **`ConfigurationEventLogger`**: no se añadió clave de contexto para el transporte; el listener llama
  `logOutcome(outcome, correlationId, "event")` y `observe` conserva `poll`. No se usa `observe` en el listener
  porque su `logFailure` emite `PARAMETERS_UNAVAILABLE`, que habla de la fuente HTTP.
- **Código de error**: ademas de `ErrorCode` se actualizó la enumeración de códigos del YAML, exigida por
  `ErrorResponseContractTest`; el plan no lo mencionaba.
- **`ParametersEventsInactiveTest`** cubre solo la ausencia de topología y listener; el límite de arranque de
  30 s (SC-005) no tiene prueba de arranque propia y queda como T028.
- **Pendiente explícito (Principio VII)**: ajustar el contrato supuesto al definitivo de SUP-02; dueño equipo de
  desarrollo del componente; fecha 2026-11-15 (plan.md).
