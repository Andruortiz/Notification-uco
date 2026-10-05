---

description: "Task list for HU2-073 sincronizar configuración transversal de Parámetros"
---

# Tasks: Sincronizar configuración transversal de Parámetros

**Input**: Design documents from `/specs/019-sincronizar-parametros/`

**Prerequisites**: plan.md (Aprobado, v1, 2026-10-05), spec.md, research.md, data-model.md,
contracts/api-notificaciones-cambios.md, contracts/parametros-fuente-provisional.md, quickstart.md.

**Tests**: exigidas (Principio IV y research D13). Orden por tarea: prueba que falla por la razón correcta ->
implementar -> ejecutar la clase -> `./mvnw -B -ntp spotless:apply`. Toda prueba que verifique ausencia de algo
(un valor que no cambia, una clave que no aparece, un rechazo que no altera la versión) lleva un control
positivo (el mismo escenario con el dato válido o presente). Hay una prueba por cada SC (D13); SC-002 y SC-007
afirman con una aserción explícita de `Duration`. Las reglas entre parámetros (b), (c) y (d) se prueban con
pruebas unitarias sobre instantáneas sintéticas y una prueba de arranque fallido; la regla (a) con E2E (D8, Q5).

**Organization**: una fase por historia de usuario, en el orden de dependencia técnica (no de prioridad): la
validación (US3) antes de la adopción (US2) y de la última conocida (US1), porque ambas la reutilizan. Las
tareas de la clave `dispatch.max-attempts` están agrupadas en la Phase 8 y marcadas **[BLOQUEADA-E4]**: no
pueden implementarse hasta que la entrega E4 de la spec 017 (ack manual y cabecera `x-dispatch-attempt`) esté
fusionada en `develop`. Todo lo demás se entrega sin E4. El descriptor `dispatch.max-attempts` SÍ se registra
desde la Phase 2, para no cambiar el contrato después.

## Path Conventions

Las rutas son relativas a la raíz del repositorio y están escritas completas. Los paquetes Java viven bajo
`core/src/main/java/co/edu/uco/notification/core/`,
`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/` y sus equivalentes `src/test/java`.
Módulos reales: `core`, `infrastructure`, `utils`. No se usan `infra-main/` ni `infra-test/`.

**Reglas de código**: cero comentarios explicativos en código nuevo o tocado (Principio III);
`./mvnw -B -ntp spotless:apply` tras crear o modificar archivos `.java`; `core` sin Spring (Principio I).

---

## Phase 1: Setup

- [x] T001 Confirmar la línea base en la rama `feature/HU2-073-sincronizar-parametros`: `./mvnw -B -ntp spotless:check` y `./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false` en verde (con `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` y Docker encendido) y anotar el resultado en `specs/019-sincronizar-parametros/tasks.md`.
  - Resultado (2026-10-05, rama `feature/HU2-073-sincronizar-parametros` sin cambios locales, Docker encendido, `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`): `spotless:check` BUILD SUCCESS; `HexagonalArchitectureTest` 3 pruebas y `ModularityTests` 2 pruebas, 5/5 en verde.
- [x] T002 Comprobar que `.specify/feature.json` apunta a `specs/019-sincronizar-parametros`, consultar el estado de la entrega E4 de `specs/017-Fix-specs-012-016/` (fusionada o no en `develop`) y anotar en `specs/019-sincronizar-parametros/tasks.md` la fecha y el resultado: decide si la Phase 8 se ejecuta o queda bloqueada.
  - Resultado (2026-10-05): `.specify/feature.json` no existe en este worktree (está en `.specify/.gitignore` y lo escriben los scripts de spec-kit); no se crea a mano y no afecta la implementación. Estado de E4 de la spec 017: SIN fusionar (en `develop` están E1 #59 y E3 #60; E2, E4 y E5 van en el PR #61 abierto, `fix/017-e5-errores-comentarios`). Decisión: la Phase 8 (T051 a T055) queda BLOQUEADA y no se ejecuta.
- [x] T003 Reunir los límites de contenido por canal que acepta cada proveedor (Brevo, Twilio, FCM) y registrarlos en `specs/019-sincronizar-parametros/research.md` (sección de límites de D8) con URL de la documentación y fecha de consulta por cada valor. Un valor sin fuente verificable NO se registra ni se incluye en la tabla: se deja fuera y se anota "sin fuente verificable" en la misma sección y en el pendiente de `ProviderContentLimits` de `specs/019-sincronizar-parametros/plan.md`, sin cambiar su estado.
  - Resultado (2026-10-05): solo Twilio SMS `body.maxLength` = 1 600 caracteres tiene fuente verificable (URL y fecha en `research.md`). Brevo y FCM: "sin fuente verificable", fuera de la tabla; anotado en `research.md` y en el pendiente de `plan.md`.

---

## Phase 2: Foundational (bloquea todas las historias)

**Purpose**: modelo del núcleo, puertos, registro y contenedor atómico de la instantánea. Java puro en `core`.

### Tests (escribir primero y verlos fallar)

- [x] T004 [P] Crear `core/src/test/java/co/edu/uco/notification/core/domain/configuration/ParameterRegistryTest.java` (SC-006 y FR-001, FR-003, FR-004): el registro contiene exactamente `dispatch.max-attempts`, `provider.<id>.timeout-ms` y `provider.<id>.connect-timeout-ms` para `brevo`, `twilio` y `fcm`, y `requeue.interval-ms`, con tipo, defecto, rango, ámbito, `scopeIds` y adopción de `specs/019-sincronizar-parametros/data-model.md`; ninguna clave contiene `secret`, `password`, `token`, `credential`, `queue`, `exchange` ni `base-url`; control positivo: un registro de prueba con una clave `provider.brevo.api-key` sí es detectado por la misma comprobación.
- [x] T005 [P] Crear `core/src/test/java/co/edu/uco/notification/core/usecase/ConfigurationHolderTest.java` (SC-004, FR-005, FR-011): una operación que capturó `snapshot()` conserva todos sus valores y su versión tras 1 000 reemplazos concurrentes (varios hilos); ninguna lectura observa una mezcla de dos versiones (valores de la misma versión coherentes entre sí); control positivo: una captura nueva posterior al reemplazo sí ve la versión nueva.

### Implementation

- [x] T006 Crear en `core/src/main/java/co/edu/uco/notification/core/domain/configuration/` los registros inmutables `ParameterDescriptor` (con validación de valor y ámbito), `ConfigurationSnapshot`, `ConfigurationChange`, `ConfigurationChangeOutcome` (estados `APPLIED`, `PENDING_RESTART`, `IGNORED_STALE`, `REJECTED`, con motivo, claves, versión anterior y nueva), `ConfigurationSource` (`PARAMETERS`, `LAST_KNOWN`, `DEFAULTS`), `ParameterScope`, `AdoptionMode` y `FixedConfiguration` (proveedores habilitados por canal, espera e intentos del análisis de adjuntos, ventana de barrido, esquema de contenido por canal, límites por proveedor) según `specs/019-sincronizar-parametros/data-model.md`.
  - Desviación: los descriptores del registro son concretos (una entrada por proveedor, p. ej. `provider.brevo.timeout-ms` con `scopeIds` [brevo]) y no una plantilla `provider.<id>.*`, para que cada entrada tenga su propio valor vigente en la respuesta del endpoint (8 descriptores en total). `FixedConfiguration` recibe los límites de contenido de canal ya extraídos del esquema (clave `campo.maxLength` o `campo.maximum`) y los límites por proveedor y canal; el esquema JSON lo interpreta infraestructura. Se añadieron `ParameterType`, `ValidationResult`, `ConfigurationDescription` y `ParameterDescription` (modelo de lectura del puerto de consulta).
- [x] T007 Crear `core/src/main/java/co/edu/uco/notification/core/domain/configuration/ParameterRegistry.java` con los descriptores iniciales de `specs/019-sincronizar-parametros/data-model.md` (incluido `dispatch.max-attempts`, 1 a 20, global) y un constructor que acepta descriptores adicionales para pruebas (descriptor de modo `RESTART`).
- [x] T008 Crear `core/src/main/java/co/edu/uco/notification/core/port/in/ConfigurationView.java` y `core/src/main/java/co/edu/uco/notification/core/usecase/ConfigurationHolder.java` (`AtomicReference` de una instantánea inmutable, `snapshot()` y reemplazo atómico), sin Spring.
  - `ConfigurationHolder` añade `compareAndSet`, que usa el servicio de la Phase 3.
- [x] T009 [P] Crear los puertos `core/src/main/java/co/edu/uco/notification/core/port/in/ApplyConfigurationChangeUseCase.java`, `core/src/main/java/co/edu/uco/notification/core/port/in/SynchronizeConfigurationUseCase.java`, `core/src/main/java/co/edu/uco/notification/core/port/in/RestoreLastKnownConfigurationUseCase.java`, `core/src/main/java/co/edu/uco/notification/core/port/in/QueryConfigurationUseCase.java`, `core/src/main/java/co/edu/uco/notification/core/port/out/ParametersSourcePort.java` (`fetchState(): Mono<ConfigurationChange>`) y `core/src/main/java/co/edu/uco/notification/core/port/out/LastKnownConfigurationPort.java` (`load()`, `saveIfNewer(snapshot)`), y la excepción `core/src/main/java/co/edu/uco/notification/core/exception/ParametersUnavailableException.java`.
  - `LastKnownConfigurationPort.saveIfNewer` devuelve `Mono<Boolean>` (true si escribió).
- [x] T010 Ejecutar `./mvnw -B -ntp spotless:apply` y `./mvnw -B -ntp -pl core test -Dtest='ParameterRegistryTest,ConfigurationHolderTest'` en verde.
  - Resultado real: `ParameterRegistryTest` 9 pruebas y `ConfigurationHolderTest` 3 pruebas, 12/12 en verde. Antes de implementar, la compilación fallaba por las clases ausentes (`ConfigurationSnapshot`, `ConfigurationHolder`). Hallazgo de la prueba: el control de nombres sensibles con `contains("queue")` marcaba `requeue.interval-ms`; se cambió a coincidencia por segmentos.

**Checkpoint**: modelo, puertos, registro y contenedor listos.

---

## Phase 3: User Story 3 - Rechazar configuración inválida conservando la vigente (P1)

**Goal**: el cambio completo se valida antes de aplicarse; si algo falla se rechaza entero y la versión vigente no cambia.

**Independent Test**: un cambio con un valor válido y otro inválido no aplica ninguno, conserva la versión y deja el motivo.

### Tests for US3 (escribir primero y verlos fallar)

- [x] T011 [P] [US3] Crear `core/src/test/java/co/edu/uco/notification/core/domain/configuration/ConfigurationValidatorTest.java` (SC-003, FR-015 a FR-017) parametrizada: clave desconocida, tipo equivocado, valor por debajo y por encima del rango, ámbito no aplicable (proveedor no configurado) y cambio mixto válido+inválido que se rechaza completo; cada caso con su control positivo válido.
- [x] T012 [P] [US3] Ampliar `core/src/test/java/co/edu/uco/notification/core/domain/configuration/ConfigurationValidatorTest.java` con las reglas (a) a (d) sobre instantáneas y `FixedConfiguration` sintéticas (research D8, Q5): (a) tiempo de espera de proveedor >= intervalo del reencolador se rechaza; (b) ventana de barrido <= espera de análisis por intentos se rechaza; (c) canal sin proveedor habilitado se rechaza; (d) límite de contenido de canal por encima del que acepta su proveedor se rechaza; cada regla con el caso límite exacto y un control positivo que cumple.
- [x] T013 [P] [US3] Crear `core/src/test/java/co/edu/uco/notification/core/usecase/ApplyConfigurationChangeServiceTest.java` con `StepVerifier`: un cambio válido da `APPLIED` y sube la versión; un cambio inválido da `REJECTED` con motivo y la versión vigente y los valores no cambian (control positivo: el mismo cambio corregido sí se aplica); versión menor o igual a la vigente da `IGNORED_STALE` y no produce versión nueva (control positivo: versión mayor se aplica); una reversión publicada como versión nueva mayor se aplica; un cambio sobre un descriptor `RESTART` da `PENDING_RESTART`, lista la clave y no altera el valor vigente; dos cambios concurrentes se procesan en orden y cada uno parte de la versión vigente en ese momento, sin estado intermedio.
  - Se añadió el apoyo de prueba `ConfigurationFixtures` (instantánea por defecto, `FixedConfiguration` sintética) en `core/src/test/java/co/edu/uco/notification/core/domain/configuration/`.

### Implementation for US3

- [x] T014 [US3] Crear `core/src/main/java/co/edu/uco/notification/core/domain/configuration/CrossParameterRule.java`, las cuatro reglas (`ProviderTimeoutBelowRequeueIntervalRule`, `AttachmentSweepWindowRule`, `ChannelHasEnabledProviderRule`, `ContentLimitWithinProviderLimitRule`) y `core/src/main/java/co/edu/uco/notification/core/domain/configuration/ConfigurationValidator.java` (`validate(candidato, FixedConfiguration)`: clave, tipo, rango, ámbito y luego las reglas sobre la unión de valores gestionables y fijos), todo en el mismo paquete.
  - Desviación: el validador expone dos operaciones, `validate(actual, cambio, fixed)` para un cambio publicado y `validate(candidata, fixed)` para una instantánea completa (defectos y última conocida); además del rango y tipo, el ámbito `PROVIDER` exige que el proveedor esté en `FixedConfiguration.configuredProviders`. Las reglas implementan `CrossParameterRule` (`name()` y `violation(valores, fixed)`) y cada violación lleva el nombre de la regla.
- [x] T015 [US3] Crear `core/src/main/java/co/edu/uco/notification/core/usecase/ApplyConfigurationChangeService.java`: serializa los cambios con `concatMap` o un sumidero de un solo consumidor, ignora versiones menores o iguales, valida el cambio completo, reemplaza la instantánea con `ConfigurationHolder`, separa las claves `RESTART` como pendientes y devuelve el `ConfigurationChangeOutcome` con versiones y claves, sin Spring.
  - Desviación: en lugar de `concatMap` o un sumidero, la serialización usa un bucle de comparar y fijar (`ConfigurationHolder.compareAndSet`) sobre la instantánea vigente: cada cambio se evalúa contra la versión vigente en ese instante, se reintenta si otro lo adelantó y no se pierde ninguna actualización; sin bloqueos. Un cambio aceptado solo con claves `RESTART` sube la versión, deja el valor vigente y lista las claves en `pendingRestart`; uno mixto devuelve `APPLIED` con las claves `RESTART` en `pendingRestartKeys`.
- [x] T016 [US3] Ejecutar `./mvnw -B -ntp spotless:apply` y `./mvnw -B -ntp -pl core test` en verde.
  - Resultado real: `./mvnw -B -ntp -pl core test` 620 pruebas, 0 fallos (incluye `ConfigurationValidatorTest` 31, `ApplyConfigurationChangeServiceTest` 8). Antes de implementar la compilación fallaba por las clases ausentes. Hallazgo de la prueba: un valor válido de `requeue.interval-ms` = 5 000 viola correctamente la regla (a) con los tiempos por defecto; el caso válido de la prueba pasó a 10 001.

### Defaults del arranque y reglas (b), (c), (d) por arranque fallido (US3)

- [ ] T017 [P] [US3] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/ConfigurationStartupTest.java` (`ApplicationContextRunner`, FR-019): con defectos que violan cada una de las reglas (b), (c) y (d) el contexto no arranca y el mensaje identifica la regla; con defectos válidos arranca con `source=DEFAULTS` y versión 0 (control positivo). Esta clase se amplía en la Phase 4 con SC-007.
- [ ] T018 [US3] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/ProviderContentLimits.java` solo con los valores registrados con URL y fecha de consulta en `specs/019-sincronizar-parametros/research.md` (T003); si T003 no dejó ningún valor verificable, la tabla se crea vacía y la regla (d) no impone límite a ese canal, anotándolo en la tarea. Verificar que la tabla no contiene ningún valor sin su fuente documentada.
- [ ] T019 [US3] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/ConfigurationConfig.java` con los beans `ParameterRegistry`, `FixedConfiguration` (construida desde las propiedades de arranque y `ProviderContentLimits`; ver `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/AttachmentProperties.java` y los `*ProviderProperties`), `ConfigurationHolder` con la instantánea `DEFAULTS` inicial leída de `infrastructure/src/main/resources/application.yml` (`notification.rabbit.dispatch.max-attempts`, `notification.provider.<id>.timeout-ms` y `connect-timeout-ms`, `notification.scheduler.requeue-interval-ms`) y la validación de los defectos que hace fallar el arranque con la regla incumplida; registrar `ApplyConfigurationChangeService` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/UseCaseConfig.java`.
- [ ] T020 [US3] Ejecutar `./mvnw -B -ntp spotless:apply` y `./mvnw -B -ntp -pl infrastructure -am test -Dtest=ConfigurationStartupTest -Dsurefire.failIfNoSpecifiedTests=false` en verde.

**Checkpoint**: la validación funciona de extremo a extremo en `core` y en el arranque; falta la fuente y el E2E.

---

## Phase 4: User Story 1 - Operar con la última configuración conocida (P1) - MVP

**Goal**: el servicio arranca y despacha aunque Parámetros no responda, con la última configuración válida o con los defectos; retoma la sincronización al volver.

**Independent Test**: con una fuente que siempre falla, arrancar sin configuración previa (defectos) y con una persistida (última conocida) y despachar una notificación.

### Tests for US1 (escribir primero y verlos fallar)

- [ ] T021 [P] [US1] Crear `core/src/test/java/co/edu/uco/notification/core/usecase/RestoreLastKnownConfigurationServiceTest.java`: una última conocida válida se adopta con `source=LAST_KNOWN`; ausente, corrupta o que no supera el validador se descarta y se usan los defectos (control positivo: la misma válida se adopta); almacén que falla o excede el límite se registra y se usan los defectos sin propagar el error.
- [ ] T022 [P] [US1] Crear `core/src/test/java/co/edu/uco/notification/core/usecase/SynchronizeConfigurationServiceTest.java`: un estado de la fuente se aplica con `ApplyConfigurationChangeUseCase` y se persiste con `saveIfNewer` solo si se aplicó; `ParametersUnavailableException` conserva la instantánea y no persiste; control positivo: tras la recuperación el siguiente ciclo aplica el cambio.
- [ ] T023 [P] [US1] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/LastKnownConfigurationMongoAdapterTest.java` (Testcontainers de Mongo, sin mocks): `saveIfNewer` con versión menor o igual no sobrescribe (control positivo: con mayor sí); dos escrituras concurrentes dejan la mayor; un documento con `schemaHash` distinto o con `values` corrupto devuelve vacío en `load()` (control positivo: uno íntegro se devuelve); el documento no contiene credenciales.
- [ ] T024 [P] [US1] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/parameters/HttpParametersSourceTest.java` con un servidor HTTP falso: `GET {base-url}/notification-service/configuration` con 200 y `{"version":12,"values":{...}}` produce un `ConfigurationChange`; estado distinto de 200, tiempo de espera y cuerpo ilegible producen `ParametersUnavailableException` (control positivo: el 200 válido).
- [ ] T025 [P] [US1] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/scheduler/ParametersPollingSchedulerTest.java`: invoca la sincronización en cada intervalo; sin `base-url` el bean de `ParametersSourcePort` es `NoParametersSource` y el servicio opera con defectos; un ciclo fallido no detiene el sondeo y el siguiente ciclo se ejecuta (control positivo: tras la recuperación el cambio se aplica). El registro de transiciones se prueba en T045.
- [ ] T026 [US1] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/ConfigurationStartupTest.java` con SC-007: con el almacén de la última conocida lento (retardo de prueba mayor que el límite de 5 s) y la fuente caída, el contexto arranca y la aplicación queda lista afirmando con `Duration` que el arranque tarda 30 s o menos; control positivo: con el almacén rápido arranca con `source=LAST_KNOWN`.
- [ ] T027 [US1] Crear el E2E `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/ConfigurationSyncE2ETest.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Testcontainers` con Mongo y RabbitMQ, `WebTestClient`, fuente de Parámetros falsa que siempre falla) para SC-001: arranca dos veces, sin configuración previa (usa defectos, `source=DEFAULTS`) y con una persistida (usa `LAST_KNOWN`); en ambos `POST /notifications` se despacha con el proveedor simulado; al volver la fuente se retoma la sincronización sin reiniciar (control positivo). Las promesas de otras historias se añaden a esta misma clase en las Phases 5 y 6.

### Implementation for US1

- [ ] T028 [US1] Crear `core/src/main/java/co/edu/uco/notification/core/usecase/RestoreLastKnownConfigurationService.java` y `core/src/main/java/co/edu/uco/notification/core/usecase/SynchronizeConfigurationService.java` y registrarlos en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/UseCaseConfig.java`.
- [ ] T029 [US1] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/LastKnownConfigurationDocument.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/LastKnownConfigurationMongoAdapter.java` (colección `configuration_last_known`, `_id` `current`, `saveIfNewer` con `findAndModify` condicionado a `version < nueva`, `schemaHash`, carga con límite de 5 s).
- [ ] T030 [US1] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/parameters/ParametersProperties.java` (`notification.parameters.base-url` vacío por defecto, `poll-interval-ms` 30 000, tiempos de espera), `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/parameters/NoParametersSource.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/parameters/HttpParametersSource.java` contra `specs/019-sincronizar-parametros/contracts/parametros-fuente-provisional.md`, inactivo salvo que `base-url` esté definida; la ruta, la autenticación y la forma de la versión viven solo en esta clase y en `ParametersProperties`. Añadir las claves en `infrastructure/src/main/resources/application.yml`.
- [ ] T031 [US1] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/scheduler/ParametersPollingScheduler.java` (contexto de correlación con prefijo `param-`, igual que `sched-`; el registro de eventos se conecta en T049) y el arranque que invoca `RestoreLastKnownConfigurationUseCase` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/ConfigurationConfig.java` sin bloquear más de 5 s ni impedir el arranque.
- [ ] T032 [US1] Ejecutar `./mvnw -B -ntp spotless:apply` y las clases de esta fase: `RestoreLastKnownConfigurationServiceTest`, `SynchronizeConfigurationServiceTest`, `LastKnownConfigurationMongoAdapterTest`, `HttpParametersSourceTest`, `ParametersPollingSchedulerTest`, `ConfigurationStartupTest` y `ConfigurationSyncE2ETest`, todas en verde.

**Checkpoint**: MVP entregable sin E4 (la promesa de la historia: operar sin Parámetros).

---

## Phase 5: User Story 2 - Adoptar un cambio publicado sin reiniciar (P1)

**Goal**: tiempos de proveedor e intervalo del reencolador se adoptan en caliente; las operaciones en curso conservan su valor. (La clave `dispatch.max-attempts` está en la Phase 8, bloqueada por E4.)

**Independent Test**: publicar un cambio de `requeue.interval-ms` o de un tiempo de espera de proveedor y comprobar que rige en menos de un intervalo de sondeo y que una llamada en vuelo no cambia.

### Tests for US2 (escribir primero y verlos fallar)

- [ ] T033 [P] [US2] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/ProviderHttpClientsTest.java`: se entrega un `WebClient` por (proveedor, espera, conexión); los mismos valores devuelven el mismo cliente (control positivo), valores distintos producen uno nuevo y el anterior sigue vivo para las llamadas en vuelo.
- [ ] T034 [P] [US2] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/scheduler/PendingNotificationSchedulerAdapterTest.java`: el siguiente ciclo se programa con el intervalo de la instantánea vigente al terminar el ciclo anterior; un cambio de `requeue.interval-ms` se refleja en el ciclo siguiente (control positivo: sin cambio conserva el intervalo).
- [ ] T035 [US2] Ampliar `ConfigurationSyncE2ETest` (`infrastructure/src/test/java/co/edu/uco/notification/infrastructure/ConfigurationSyncE2ETest.java`) para SC-002 y SC-004, con intervalo de sondeo de 1 s en la prueba y una fuente falsa que publica la versión 1: afirma con `Duration` que el cambio de `requeue.interval-ms` rige en menos de un intervalo de sondeo más un margen declarado en la prueba y que `GET /configuration` (tras T048) o el holder muestran la versión 1; control positivo: antes de publicar rige el valor por defecto. Para SC-004: un proveedor HTTP falso lento recibe la llamada, se publica un cambio del tiempo de espera durante la llamada y esa llamada termina con el valor con que empezó, mientras la siguiente usa el nuevo (control positivo).
- [ ] T036 [US2] Revisar que siguen en verde sin cambios de aserción `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoEmailDeliveryE2ETest.java`, `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/TwilioSmsDeliveryE2ETest.java` y `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/FcmPushDeliveryE2ETest.java` tras el cambio de T038 (riesgo R-1); ejecutarlas antes de empezar para fijar la línea base.

### Implementation for US2

- [ ] T037 [US2] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/ProviderHttpClients.java`: entrega el `WebClient` de cada proveedor para la instantánea recibida (`CONNECT_TIMEOUT_MILLIS` y `responseTimeout`), con un mapa por (proveedor, espera, conexión).
- [ ] T038 [US2] Cambiar `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/BrevoNotificationProvider.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/TwilioNotificationProvider.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/FcmNotificationProvider.java` para que lean `ConfigurationView.snapshot()` una vez al iniciar la llamada y obtengan su `WebClient` de `ProviderHttpClients`; adaptar `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/BrevoWebClientConfig.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/TwilioWebClientConfig.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/FcmProviderConfig.java` para que dejen de fijar los tiempos.
- [ ] T039 [US2] Cambiar `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/scheduler/PendingNotificationSchedulerAdapter.java` para registrarse con un `SchedulingConfigurer` y un `Trigger` que calcula el siguiente instante con el `requeue.interval-ms` de la instantánea vigente al terminar cada ciclo; el sondeo de Parámetros conserva su intervalo de arranque.
- [ ] T040 [US2] Ejecutar `./mvnw -B -ntp spotless:apply` y `ProviderHttpClientsTest`, `PendingNotificationSchedulerAdapterTest`, los tres E2E de proveedor, `ProviderRoutingE2ETest` y `ConfigurationSyncE2ETest` en verde.

**Checkpoint**: adopción en caliente de tiempos de proveedor y reencolador, sin E4.

---

## Phase 6: User Story 3 (E2E) - Rechazo end-to-end

- [ ] T041 [US3] Ampliar `ConfigurationSyncE2ETest` (`infrastructure/src/test/java/co/edu/uco/notification/infrastructure/ConfigurationSyncE2ETest.java`) para SC-003 por publicación real: la fuente falsa publica la versión 2 con `provider.brevo.timeout-ms` mayor o igual al intervalo del reencolador (regla (a)) y se rechaza completa; publica una clave desconocida y se rechaza; en ambos la versión vigente no cambia y el log trae `CONFIG_REJECTED` con motivo y correlación, sin valores sensibles; control positivo: una versión 3 válida se aplica.

---

## Phase 7: User Stories 4 y 5 - Registro expuesto y configuración visible (P2)

**Goal**: consultar descriptores, versión y origen por `GET /configuration` (solo `ADMINISTRADOR`); cada cambio aplicado, rechazado o ignorado queda registrado.

**Independent Test**: con un token `ADMINISTRADOR` el endpoint devuelve los cuatro grupos de parámetros sin secretos y la versión y el origen en uso.

### Contract first (Principio II)

- [ ] T042 [US4] Editar `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: añadir `GET /configuration` (`getConfiguration`, rol `ADMINISTRADOR`, 200 `ConfigurationResponse`, 401, 403), los esquemas `ConfigurationResponse` y `ParameterDescriptorResponse`, la etiqueta `Configuración` y la mención del rol en la descripción general, exactamente como indica `specs/019-sincronizar-parametros/contracts/api-notificaciones-cambios.md`. Debe commitearse antes de T046.

### Tests for US4 y US5 (escribir primero y verlos fallar)

- [ ] T043 [P] [US4] Crear `core/src/test/java/co/edu/uco/notification/core/usecase/QueryConfigurationServiceTest.java`: devuelve los descriptores, el valor vigente de cada uno, la versión, el origen, `adoptedAt` y las claves pendientes de reinicio; control positivo: tras aplicar un cambio refleja la versión nueva.
- [ ] T044 [P] [US4] Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/web/RouteAuthorizationPolicyTest.java`: `GET /configuration` exige `ADMINISTRADOR`.
- [ ] T045 [US5] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/scheduler/ConfigurationEventLoggerTest.java` (FR-021 a FR-023): `CONFIG_APPLIED` con versión anterior, nueva y claves; `CONFIG_REJECTED` con motivo y claves sin valores; `CONFIG_IGNORED` con la versión; `PARAMETERS_UNAVAILABLE` solo en la transición y `PARAMETERS_RECOVERED` al volver, no en cada ciclo fallido (control positivo: la primera transición sí se registra una vez); todos con identificador de correlación; ninguna línea contiene valores de credenciales de prueba (control positivo: el evento aplicado sí aparece en el log capturado); la métrica `notification.configuration.version` con etiqueta `source` refleja la versión.
- [ ] T046 [US4] Ampliar `ConfigurationSyncE2ETest` (`infrastructure/src/test/java/co/edu/uco/notification/infrastructure/ConfigurationSyncE2ETest.java`) para SC-005 y SC-006: `ADMINISTRADOR` recibe 200, `OPERADOR` y `CLIENTE` 403 y sin token 401; el cuerpo contiene exactamente los cuatro grupos de parámetros y ninguna cadena de las credenciales de prueba ni de las direcciones base; tras un cambio aplicado y uno rechazado, el endpoint muestra versión y origen y el log muestra ambos eventos con su resultado (control positivo: el cambio aplicado sí cambia la versión mostrada).

### Implementation for US4 y US5

- [ ] T047 [US4] Crear `core/src/main/java/co/edu/uco/notification/core/usecase/QueryConfigurationService.java` y registrarlo en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/UseCaseConfig.java`.
- [ ] T048 [US4] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/ConfigurationController.java`, `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/ConfigurationResponse.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/ParameterDescriptorResponse.java` y añadir la regla explícita en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/RouteAuthorizationPolicy.java`.
- [ ] T049 [US5] Crear `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/scheduler/ConfigurationEventLogger.java` (eventos `CONFIG_APPLIED`, `CONFIG_REJECTED`, `CONFIG_IGNORED`, `PARAMETERS_UNAVAILABLE`, `PARAMETERS_RECOVERED` en el formato estructurado existente de `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/LogFields.java`) y el gauge `notification.configuration.version` en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/ConfigurationConfig.java`; conectarlo al resultado de `ParametersPollingScheduler`.
- [ ] T050 [US4] Ejecutar `./mvnw -B -ntp spotless:apply` y `QueryConfigurationServiceTest`, `RouteAuthorizationPolicyTest`, `ConfigurationEventLoggerTest` y `ConfigurationSyncE2ETest` en verde.

---

## Phase 8: Tareas BLOQUEADAS por la entrega E4 (spec 017: ack manual y cabecera `x-dispatch-attempt`)

**Estado**: **BLOQUEADAS** hasta que E4 esté fusionada en `develop` (estado a 2026-10-05: E4 sin fusionar). No se implementan ni se marcan sin E4. Si T002 confirma E4 fusionada, rebasar sobre `develop` antes de empezar (riesgo R-2: conflicto en `RabbitRetryConfig` y `NotificationDispatchListener`). El descriptor `dispatch.max-attempts` ya está registrado y validado desde la Phase 2; esta fase solo cambia el punto de lectura.

**Goal**: el tope de intentos por mensaje se lee de la instantánea vigente en cada mensaje (US2, escenarios 1 y 2).

- [ ] T051 [P] [US2] **[BLOQUEADA-E4]** Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/NotificationDispatchListenerTest.java`: el tope de intentos se toma de `snapshot()` al iniciar el mensaje con la cabecera `x-dispatch-attempt`; un cambio de 3 a 5 admite hasta 5 intentos en el mensaje siguiente; un mensaje en curso conserva el valor que leyó (control positivo: un mensaje nuevo usa el valor nuevo).
- [ ] T052 [P] [US2] **[BLOQUEADA-E4]** Ampliar `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfigTest.java` y `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfigCustomAttemptsTest.java` según el diseño de E4: el tope dejó de ser un entero fijo del interceptor y se resuelve por mensaje desde `ConfigurationView`.
- [ ] T053 [US2] **[BLOQUEADA-E4]** Cambiar `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfig.java` y `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rabbit/NotificationDispatchListener.java` para leer `dispatch.max-attempts` de `ConfigurationView.snapshot()` una vez por mensaje; no cambiar el modo de confirmación ni la DLQ. Solo tras rebasar sobre `develop` con E4.
- [ ] T054 [US2] **[BLOQUEADA-E4]** Ampliar `ConfigurationSyncE2ETest` (`infrastructure/src/test/java/co/edu/uco/notification/infrastructure/ConfigurationSyncE2ETest.java`) para SC-002 con el listener: con intervalo de sondeo de 1 s se publica `dispatch.max-attempts` 5 sobre un valor de 3, se afirma con `Duration` que rige en menos de un intervalo más margen y que el siguiente mensaje fallido de un proveedor falso se reintenta 5 veces antes de la DLQ (control positivo: antes del cambio se reintenta 3 veces).
- [ ] T055 [US2] **[BLOQUEADA-E4]** Ejecutar `./mvnw -B -ntp spotless:apply`, `NotificationDispatchListenerTest`, `RabbitRetryConfigTest`, `RabbitRetryConfigCustomAttemptsTest`, `DeadLetterQueueE2ETest` (con el broker en `localhost:5673` si hace falta) y `ConfigurationSyncE2ETest`, todas en verde.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T056 Ejecutar `./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false` en verde y comprobar que `core` no importa Spring ni ningún framework.
- [ ] T057 Comprobar con `grep -rnE "^\s*(//|/\*|\*)" ` sobre los archivos `.java` creados o tocados por esta historia que no hay comentarios explicativos (Principio III) y que ningún artefacto versionado cita Notion.
- [ ] T058 Ejecutar `./mvnw -B -ntp spotless:apply` y luego `./mvnw -B -ntp clean verify` completo (con `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`, en segundo plano) confirmando cobertura >= 80 % líneas y >= 70 % ramas, Spotless, SpotBugs y FindSecBugs en verde; si solo fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y anotarlo; si no es ejecutable en local, anotar que lo decide CI.
- [ ] T059 Ejecutar la demostración manual de `specs/019-sincronizar-parametros/quickstart.md` contra un servicio local aislado y anotar el resultado de cada paso en ese archivo.
- [ ] T060 Revisar que cada SC (SC-001 a SC-007) tiene su prueba citada en `specs/019-sincronizar-parametros/quickstart.md`, que la Phase 8 está cerrada o anotada como bloqueada con motivo, y que las excepciones del Principio VII del plan (contrato definitivo SUP-02 el 2026-11-15, valores de `ProviderContentLimits` antes de producción, descriptor del `RetryPolicy` el 2026-11-30) siguen vigentes con dueño y fecha.

---

## Dependencies & Execution Order

- Phase 1 antes de todo; T002 decide si la Phase 8 puede ejecutarse. T003 (fuentes de límites) antes de T018.
- Phase 2 (Foundational) bloquea todas las historias.
- **Orden técnico**: Phase 3 (US3, validación y defectos) -> Phase 4 (US1, última conocida, fuente y sondeo; MVP) -> Phase 5 (US2, adopción en caliente) -> Phase 6 (E2E de rechazo) -> Phase 7 (US4 y US5, endpoint y eventos) -> Phase 9. La Phase 8 depende de E4 y de la Phase 5.
- Las Phases 5 y 7 dependen de la Phase 4 porque su E2E usa la fuente falsa y el sondeo; T035 usa `GET /configuration` solo como opción y puede afirmar sobre el holder hasta que T048 exista.
- **Contrato primero**: T042 antes de T046, T047 y T048; el YAML se commitea antes del controller.
- **Dependencia externa E4**: T051 a T055 solo tras la fusión de E4 de la spec 017 en `develop`. Riesgo R-2: `RabbitRetryConfig` y `NotificationDispatchListener` son los archivos que E4 también modifica; no tocarlos en esta historia antes de E4, y rebasar sobre `develop` inmediatamente antes de T053. Ninguna otra fase toca esos dos archivos.
- T038 modifica los tres adaptadores de proveedor y sus E2E existentes (riesgo R-1): fijar su línea base con T036 antes.
- Dentro de cada fase: contrato (si aplica) -> pruebas que fallan -> implementación -> `spotless:apply` -> ejecutar clases -> E2E.
- `ConfigurationSyncE2ETest` se amplía en T027, T035, T041, T046 y T054; esas tareas no son paralelas entre sí.

## Parallel Opportunities

- Phase 2: T004 y T005 en paralelo; T009 en paralelo con T006 a T008 una vez fijados los nombres.
- Phase 3: T011, T012 y T013 en paralelo (archivos distintos salvo T011/T012, que comparten clase y se hacen en secuencia); T017 en paralelo con las de `core`.
- Phase 4: T021 a T025 en paralelo (clases distintas).
- Phase 5: T033 y T034 en paralelo.
- Phase 7: T043, T044 y T045 en paralelo.
- Phase 8: T051 y T052 en paralelo, y toda la fase puede avanzar en otra rama mientras E4 está en revisión solo para las pruebas; la implementación (T053) espera a E4.

## Implementation Strategy

- **MVP**: Phases 1 a 4. La promesa de la historia (operar sin Parámetros con la última configuración conocida) se cumple sin E4.
- **Entrega 1 (sin E4)**: Phases 1 a 7 y 9: validación, última conocida, sondeo, adopción de tiempos y reencolador, endpoint y eventos.
- **Entrega 2 (con E4)**: Phase 8 y repetir T058; es un cambio pequeño y aislado en `RabbitRetryConfig` y `NotificationDispatchListener`.
- Cada entrega es un PR contra `develop`, con commits de una línea y el razonamiento en el cuerpo del PR (Principio V); el usuario revisa y mergea.
- Los valores de `ProviderContentLimits` solo entran con URL y fecha de consulta; el adaptador HTTP queda inactivo por defecto hasta cerrar el contrato definitivo de SUP-02.
