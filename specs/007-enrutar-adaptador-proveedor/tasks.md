---

description: "Task list for 007-enrutar-adaptador-proveedor (HU2-088)"
---

# Tasks: Enrutar cada notificación al adaptador de su proveedor por providerId

**Input**: Design documents from `/specs/007-enrutar-adaptador-proveedor/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/provider-registry.md, quickstart.md

**Tests**: obligatorias. La historia exige explícitamente pruebas unitarias con dos adaptadores falsos
y con un `providerId` desconocido, y la constitución (Principio IV) exige una prueba E2E del flujo.

**Organization**: agrupadas por historia de usuario. El orden dentro de cada tarea es siempre: escribir
la prueba, verla fallar por la razón correcta, implementar, ejecutar la clase de prueba,
`spotless:apply`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de usuario a la que pertenece (US1, US2, US3)

## Path Conventions

Módulos reales del repositorio: `core/`, `infrastructure/`, `utils/`.

---

## Phase 1: Setup

**Purpose**: partir de una base verde y conocida. No hay dependencias nuevas que agregar.

- [x] T001 Confirmar la base verde antes de tocar nada: `./mvnw -B -ntp -pl core test` (256 pruebas en verde antes de empezar)
- [x] T002 Confirmar que no hace falta ninguna dependencia nueva en `pom.xml`, `core/pom.xml` ni `infrastructure/pom.xml` (el plan no introduce ninguna)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: el registro y la identidad de los adaptadores. Ninguna historia puede avanzar sin esto.

**⚠️ CRITICAL**: bloquea US1, US2 y US3.

- [x] T003 Escribir `NotificationSenderRegistryTest` en `core/src/test/java/co/edu/uco/notification/core/port/out/NotificationSenderRegistryTest.java` con dos adaptadores falsos (`fake-a`, `fake-b`): resolver `fake-a` devuelve ese adaptador y no el otro; `providerId` desconocido lanza `ProviderNotAvailableException` con el identificador en el mensaje; `providerId` nulo lanza `NullPointerException`; construir con dos adaptadores del mismo identificador lanza `IllegalArgumentException` nombrando el duplicado; adaptador con `providerId()` nulo y colección nula lanzan `NullPointerException`; colección vacía construye pero todo `resolve` falla. Verla fallar (no compila todavía). **Desviación**: la prueba no afirma un accesor `providerId()` sobre la excepción (ver T004); comprueba el identificador dentro del mensaje
- [x] T004 Crear `ProviderNotAvailableException` en `core/src/main/java/co/edu/uco/notification/core/exception/ProviderNotAvailableException.java` — `RuntimeException` construida con `ProviderId`, con el valor del identificador en el mensaje (ese texto acaba en el header de causa de la DLQ). **Desviación**: no guarda el `ProviderId` como campo. Un campo no serializable en una clase `Serializable` (toda `RuntimeException` lo es) dispara `SE_BAD_FIELD` de SpotBugs, que es bloqueante en `verify`; además ninguna excepción de `core` expone accesores hoy
- [x] T005 Agregar `ProviderId providerId()` a `core/src/main/java/co/edu/uco/notification/core/port/out/NotificationSenderPort.java`
- [x] T006 Crear `NotificationSenderRegistry` en `core/src/main/java/co/edu/uco/notification/core/port/out/NotificationSenderRegistry.java`: clase final, `Map<ProviderId, NotificationSenderPort>` inmutable construido desde `Collection<NotificationSenderPort>`, detección de duplicados con `Preconditions.requireTrue`, `resolve(ProviderId)`
- [x] T007 Ejecutar `./mvnw -B -ntp -pl core test -Dtest=NotificationSenderRegistryTest -Dsurefire.failIfNoSpecifiedTests=false` en verde (7 pruebas) y luego `./mvnw -B -ntp spotless:apply`
- [x] T008 Declarar la identidad del adaptador simulado en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/SimulatedNotificationProvider.java`: `providerId()` devuelve `ProviderId.of("simulated")`, el mismo valor que `application.yml` siembra para `EMAIL`
- [x] T009 [P] Agregar la aserción de `providerId()` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/SimulatedNotificationProviderTest.java`
- [x] T010 Cablear el registro en `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/UseCaseConfig.java`: `@Bean NotificationSenderRegistry notificationSenderRegistry(List<NotificationSenderPort> senders)` y el bean de despacho recibiendo el registro en lugar del puerto suelto

**Checkpoint**: el registro existe, está probado y cableado; el simulado declara su identidad.

---

## Phase 3: User Story 1 - El proveedor declarado en el catálogo es el que envía (Priority: P1) 🎯 MVP

**Goal**: el despacho envía por el adaptador del proveedor preferente de la ruta, y el `providerId`
anotado en el intento es el de quien realmente envió.

**Independent Test**: con dos adaptadores falsos y el catálogo apuntando a uno de ellos, la
notificación sale por ese y no por el otro, y termina `DELIVERED` con ese `providerId`.

### Tests for User Story 1

- [x] T011 [US1] Actualizar `core/src/test/java/co/edu/uco/notification/core/usecase/DispatchNotificationServiceTest.java`: el doble de `NotificationSenderPort` declara `when(sender.providerId()).thenReturn(ProviderId.of("brevo"))` (la ruta de prueba ya usa `brevo`), el servicio se construye con `NotificationSenderRegistry`, y las 5 pruebas de `constructorRejectsNull...` pasan a validar el registro. Verla fallar
- [x] T012 [US1] Agregar a `DispatchNotificationServiceTest` una prueba con **dos** adaptadores falsos registrados (`brevo` y `otro`) que confirme que solo el preferente de la ruta recibe la notificación y que el intento queda anotado con ese mismo `providerId`. Verla fallar

### Implementation for User Story 1

- [x] T013 [US1] Modificar `core/src/main/java/co/edu/uco/notification/core/usecase/DispatchNotificationService.java`: sustituir la dependencia `NotificationSenderPort` por `NotificationSenderRegistry` y enviar por el adaptador resuelto desde `route.preferredProvider()`
- [x] T014 [US1] Ejecutar `./mvnw -B -ntp -pl core test` en verde (265 pruebas) y `./mvnw -B -ntp spotless:apply`

**Checkpoint**: el enrutamiento funciona y está probado en `core`.

---

## Phase 4: User Story 2 - Un proveedor sin adaptador no se pierde en silencio (Priority: P2)

**Goal**: un `providerId` que nadie atiende produce un fallo de despacho trazable, sin intento
registrado y sin cambio de estado — distinguible de un fallo del proveedor.

**Independent Test**: con el catálogo apuntando a un identificador inexistente, el despacho falla, la
notificación sigue `PENDING` sin intentos, y el mensaje llega a la DLQ con la causa nombrando ese
identificador.

### Tests for User Story 2

- [x] T015 [US2] Agregar a `DispatchNotificationServiceTest` una prueba de que un preferente sin adaptador falla con `ProviderNotAvailableException`, **no registra intento**, no cambia el estado (`PENDING`) y nunca llama a `notificationRepository.save(...)` ni a `eventPublisherPort.publish(...)`. Verla fallar

### Implementation for User Story 2

- [x] T016 [US2] Asegurar en `DispatchNotificationService.attemptSend(...)` que la resolución del adaptador ocurre **antes** de `notification.markQueued()`, de modo que el fallo no mute la notificación; hacer explícita la conversión a señal de error del `Mono` (`Mono.fromCallable(...)`) en lugar de depender de que `flatMap` capture la excepción sincrónica
- [x] T017 [US2] Ejecutar `./mvnw -B -ntp -pl core test -Dtest=DispatchNotificationServiceTest -Dsurefire.failIfNoSpecifiedTests=false` en verde (15 pruebas) y `./mvnw -B -ntp spotless:apply`

**Checkpoint**: los dos modos de fallo son distinguibles y están probados.

---

## Phase 5: User Story 3 - Incorporar un proveedor nuevo sin tocar el núcleo (Priority: P3)

**Goal**: agregar un adaptador es agregar un `@Component` y declararlo en el catálogo; el simulado
sigue operativo junto a otros.

**Independent Test**: dos adaptadores falsos registrados por una `@TestConfiguration` conviven con el
`SimulatedNotificationProvider` real del contexto y el enrutamiento funciona sin haber tocado `core`.

- [x] T018 [US3] Confirmar con `./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false` que el registro en `core` no introduce ninguna dependencia hacia `infrastructure` ni hacia Spring (criterio de aceptación explícito de la historia) — 5 pruebas en verde
- [x] T019 [US3] Verificar que la coexistencia del simulado con los dos adaptadores falsos queda cubierta por la E2E de T020. **Desviación**: se hizo explícita con una prueba propia, `keepsTheSimulatedProviderAvailableAlongsideTheOtherAdapters`, en vez de dejarla implícita en el arranque del contexto

**Checkpoint**: la propiedad de extensibilidad (ADR-0009) queda verificada, no solo declarada.

---

## Phase 6: Prueba end-to-end (Principio IV) — obligatoria

- [x] T020 [US1] [US2] [US3] Crear `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/ProviderRoutingE2ETest.java`: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers` (Mongo + RabbitMQ, patrón de `NotificationLiveUpdatesE2ETest`), `@TestConfiguration` estática con dos `NotificationSenderPort` falsos (`fake-a`, `fake-b`) que registran lo recibido, conviviendo con el `SimulatedNotificationProvider` real. Propiedades del test: catálogo `EMAIL → [fake-a]` vía `notification.catalog.channels.EMAIL.providers[0]` y `notification.catalog.refresh-interval-ms` bajo (≈500 ms), porque el seeder escribe de forma asíncrona y el refresco por defecto es de 30 s (`ChannelCatalogRefresher.refresh()` es *package-private*, no se puede invocar desde este paquete).
      Escenario 1 (enrutamiento): `POST /notifications` → `GET /notifications/{id}` devuelve `DELIVERED` con `providerId = fake-a`; `fake-a` recibió exactamente una notificación y `fake-b` ninguna.
      Escenario 2 (proveedor no resuelto): insertar en el catálogo `SMS → [fantasma]`, `POST /notifications` con `channelType=SMS` → el mensaje llega a la DLQ con cuerpo igual al `notificationId` y header `x-exception-message` nombrando `fantasma`, y la notificación sigue `PENDING` sin intentos
- [x] T021 Ejecutar `./mvnw -B -ntp -pl infrastructure -am test -Dtest=ProviderRoutingE2ETest -Dsurefire.failIfNoSpecifiedTests=false` con Docker arriba y `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` — 3 pruebas en verde en 48,9 s — y luego `./mvnw -B -ntp spotless:apply`

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T022 Revisar que ningún archivo `.java` nuevo o modificado tenga comentarios explicativos (Principio III) — verificado sobre los 10 archivos tocados
- [x] T023 Ejecutar la puerta completa `./mvnw -B -ntp verify` (pruebas + Spotless + SpotBugs/FindSecBugs + cobertura ≥80 % líneas / ≥70 % ramas). **Resultado real**: la ejecución completa falla en `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, las dos que usan el broker compartido de `localhost:5673`. Ambas fallan igual sobre el código previo a esta historia (verificado en un worktree limpio en `d92c43f`): el broker local tiene consumidores de procesos Java anteriores a la sesión que compiten por `notification.dispatch.queue`. La puerta se repitió con `-Dtest='!DeadLetterQueueE2ETest,!RabbitRetryConfigCustomAttemptsTest'` y quedó en **BUILD SUCCESS** (265 pruebas de `core` + 87 de `infrastructure`, Spotless, SpotBugs/FindSecBugs y cobertura). CI decide sobre esas dos
- [x] T024 Confirmar la cobertura de los archivos nuevos en `core/target/site/jacoco/` e `infrastructure/target/site/jacoco/` — `NotificationSenderRegistry`, `ProviderNotAvailableException`, `DispatchNotificationService`, `SimulatedNotificationProvider` y `UseCaseConfig` quedan al 100 % de líneas y de ramas
- [x] T025 Commitear el código y los artefactos de spec-kit en la rama `feature/HU2-088-enrutar-adaptador-por-proveedor` con mensajes de una sola línea

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias
- **Foundational (Phase 2)**: depende de Setup — BLOQUEA US1, US2 y US3
- **US1 (Phase 3)**: depende de Foundational
- **US2 (Phase 4)**: depende de Foundational; se apoya en el servicio ya modificado por US1
- **US3 (Phase 5)**: depende de Foundational; su verificación real es la E2E de Phase 6
- **E2E (Phase 6)**: depende de US1 y US2
- **Polish (Phase 7)**: depende de todo lo anterior

### Within Each User Story

- La prueba se escribe primero y se ve fallar por la razón correcta antes de implementar
- `spotless:apply` después de crear o editar cualquier `.java`

### Parallel Opportunities

- T009 puede ir en paralelo con T010 (archivos distintos)
- T003 y T004 tocan archivos distintos, pero T003 no compila hasta que exista T004: escribir primero, compilar después
- Poco paralelismo real: la historia es un cambio pequeño y encadenado sobre el mismo servicio

---

## Implementation Strategy

### MVP

Phase 1 + Phase 2 + Phase 3 (US1) ya entregan el valor central: el proveedor declarado en el catálogo
es el que envía. US2 es la garantía de seguridad que impide que ese cambio introduzca una forma
silenciosa de perder notificaciones, así que en la práctica se entregan juntas.

### Notes

- `[P]` = archivos distintos, sin dependencias pendientes
- Commits de una sola línea, en español sin tildes (Principio V)
- Las tareas se marcan solo cuando están verificadas con su comando ejecutado en verde
