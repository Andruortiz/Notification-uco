---

description: "Task list for HU2-029 + HU2-030 — preferencias del destinatario"
---

# Tasks: Gestionar preferencias del destinatario y excluir bajas al despachar

**Input**: Design documents from `/specs/013-preferencias-destinatario/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api-notificaciones-cambios.md

**Tests**: obligatorias por la constitución (Principio IV): unitarias en `core`, integración del adaptador
contra Mongo real y una E2E explícita del flujo completo. Orden por tarea: prueba → verla fallar →
implementar → prueba en verde → `spotless:apply`.

**Organization**: por historia de usuario del spec.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: puede ir en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: US1–US4 del spec

Paquetes base: `core/src/main/java/co/edu/uco/notification/core/` (abreviado `core/…`) y
`infrastructure/src/main/java/co/edu/uco/notification/infrastructure/` (abreviado `infra/…`); pruebas en
el mismo paquete bajo `src/test/java`.

## Phase 1: Setup

- [ ] T001 Aplicar los ajustes de contracts/api-notificaciones-cambios.md en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` (descripciones de ambas operaciones, `400` en `:updatePreferences`, `required` y `updatedAt` nullable en `RecipientPreferencesResponse`, descripción de `UpdatePreferencesRequest.acceptedChannels`) antes de escribir el controlador (Principio II)

---

## Phase 2: Foundational (bloquea todas las historias)

- [ ] T002 [P] Escribir `RecipientPreferencesTest` en `core/src/test/java/co/edu/uco/notification/core/domain/RecipientPreferencesTest.java`: `defaults` (sin baja, lista vacía, `updatedAt` nulo, acepta cualquier canal), `declare` normaliza (`trim`, mayúsculas, deduplicado en orden), baja total vacía la lista y no acepta nada, lista vacía acepta todo, lista no vacía acepta solo lo listado sin distinguir mayúsculas, nulos obligatorios rechazados, `updatedAt` obligatorio en `declare`, lista inmutable
- [ ] T003 Implementar el record `RecipientPreferences` en `core/…/domain/RecipientPreferences.java` según data-model.md hasta que T002 pase
- [ ] T004 [P] Crear el puerto `RecipientPreferencePort` en `core/…/port/out/RecipientPreferencePort.java` (`findByTenantAndRecipient`, `save`)
- [ ] T005 Escribir `RecipientPreferenceMongoAdapterTest` (Testcontainers `mongo:7.0`, patrón de `NotificationMongoAdapterTest`) en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/mongo/RecipientPreferenceMongoAdapterTest.java`: vacío sin registro; guardar y leer ida y vuelta; segundo guardado reemplaza por completo (un solo documento); mismo `recipientId` en dos tenants son registros independientes; 10 guardados concurrentes distintos de la misma clave dejan exactamente un documento igual a uno de los diez (SC-009)
- [ ] T006 Implementar `RecipientPreferenceKey`, `RecipientPreferenceDocument` (colección `recipient_preferences`, `_id` compuesto) y `RecipientPreferenceMongoAdapter` en `infra/…/adapter/out/mongo/` hasta que T005 pase
- [ ] T007 Ejecutar `HexagonalArchitectureTest` y `ModularityTests` con las piezas nuevas

**Checkpoint**: dominio, puerto y persistencia listos.

---

## Phase 3: User Story 1 - Declarar y consultar preferencias (P1) 🎯 MVP

**Goal**: `GET /recipients/{recipientId}/preferences` y `POST /recipients/{recipientId}:updatePreferences`
funcionan según el contrato.

**Independent Test**: consultar sin registro → valores por defecto; reemplazar por "solo EMAIL" y por
baja total → la consulta devuelve lo guardado; entrada vacía → 400 sin cambios.

- [ ] T008 [P] [US1] Escribir `GetRecipientPreferencesServiceTest` (StepVerifier) en `core/src/test/java/co/edu/uco/notification/core/usecase/GetRecipientPreferencesServiceTest.java`: devuelve lo guardado; sin registro devuelve `defaults`; consulta por el tenant de la query
- [ ] T009 [P] [US1] Escribir `UpdateRecipientPreferencesServiceTest` (StepVerifier) en `core/src/test/java/co/edu/uco/notification/core/usecase/UpdateRecipientPreferencesServiceTest.java`: guarda lo declarado normalizado con `updatedAt` no nulo y devuelve lo guardado; baja total guarda lista vacía; el error del puerto se propaga
- [ ] T010 [US1] Crear `GetRecipientPreferencesQuery`, `GetRecipientPreferencesUseCase`, `UpdateRecipientPreferencesCommand`, `UpdateRecipientPreferencesUseCase` en `core/…/port/in/` e implementar `GetRecipientPreferencesService` y `UpdateRecipientPreferencesService` en `core/…/usecase/` hasta que T008 y T009 pasen
- [ ] T011 [US1] Registrar los beans `getRecipientPreferencesUseCase` y `updateRecipientPreferencesUseCase` en `infra/…/config/UseCaseConfig.java`
- [ ] T012 [US1] Crear `UpdatePreferencesRequest`, `RecipientPreferencesResponse` y `RecipientPreferencesController` en `infra/…/adapter/in/rest/` (rutas del contrato, encabezado `X-Tenant-Id`, entrada inválida → 400 por el `IllegalArgumentException` ya manejado)

**Checkpoint**: US1 verificable de punta a punta por la E2E (T016).

---

## Phase 4: User Story 2 - El despacho no contacta a quien se dio de baja (P1)

**Goal**: el despacho descarta (`PENDING → DISCARDED`) cuando la preferencia excluye el canal, antes de
`markQueued()`, sin llamar a ningún proveedor (research Decisión 1, opción b).

**Independent Test**: baja total → notificación DISCARDED sin petición al proveedor; destinatario sin
restricción → DELIVERED (control positivo).

- [ ] T013 [US2] Ampliar `DispatchNotificationServiceTest` en `core/src/test/java/co/edu/uco/notification/core/usecase/DispatchNotificationServiceTest.java`: nuevo colaborador `RecipientPreferencePort` (stub "sin registro" en `setUp`, las pruebas existentes siguen igual); baja total → DISCARDED guardada, `NotificationDiscarded` publicado, sin intentos, sin llamar al catálogo ni al proveedor; canal no aceptado → igual; canal aceptado con otra capitalización → DELIVERED; lista vacía → DELIVERED; error del puerto → error propagado, sin `save`, sin proveedor, notificación sigue PENDING (SC-007); notificación ya terminal y excluida → `InvalidStatusTransitionException` sin proveedor
- [ ] T014 [US2] Añadir `RecipientPreferencePort` al constructor de `DispatchNotificationService` en `core/…/usecase/DispatchNotificationService.java` y el paso de preferencias antes de `findActiveRoute` hasta que T013 pase
- [ ] T015 [US2] Pasar `RecipientPreferencePort` al bean `dispatchNotificationUseCase` en `infra/…/config/UseCaseConfig.java`

---

## Phase 5: E2E del flujo completo (US1, US2, US3, US4)

- [ ] T016 [US1] [US2] [US3] [US4] Escribir y ejecutar `RecipientPreferencesE2ETest` en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/in/rest/RecipientPreferencesE2ETest.java` (`@SpringBootTest(RANDOM_PORT)` + Mongo y RabbitMQ en Testcontainers + `WebTestClient`, proveedores de registro falsos para EMAIL y SMS como en `ProviderRoutingE2ETest`):
  - US1: consulta por defecto (SC-001); reemplazo normalizado y consulta posterior; baja total vacía la lista; retirar la baja; entrada vacía → 400 y preferencias intactas (SC-002); `recipientId` con `:` en la ruta `:updatePreferences`
  - US2: baja total → DISCARDED en ≤5 s desde la aceptación con aserción explícita de `Duration`, cero peticiones al proveedor y cero intentos (SC-003); solo EMAIL → SMS DISCARDED y EMAIL DELIVERED en la misma prueba (SC-004)
  - US3: dos tenants, mismo `recipientId`: baja en A, consulta en B por defecto, notificación de B DELIVERED y de A DISCARDED; actualización en B no altera A (SC-005)
  - US4: notificación persistida PENDING antes de la baja y encolada con el productor real (`NotificationEventPublisherPort.enqueueForDispatch`) después → DISCARDED; notificación nueva tras retirar la baja → DELIVERED; notificación ya DELIVERED no cambia tras la baja (SC-006)

---

## Phase 6: Polish & Cross-Cutting

- [ ] T017 `./mvnw -B -ntp spotless:apply` y revisión del diff: cero comentarios explicativos (Principio III), sin citas a herramientas externas
- [ ] T018 `./mvnw -B -ntp clean verify` completo en verde (pruebas, Spotless, SpotBugs/FindSecBugs, cobertura ≥80 % líneas / ≥70 % ramas)
- [ ] T019 Marcar tareas y anotar desviaciones en este archivo

---

## Dependencies & Execution Order

- T001 antes de T012 (contract-first).
- T002 → T003; T004 en paralelo; T005 → T006 (dependen de T003/T004). T007 tras T006.
- US1 (T008–T012) y US2 (T013–T015) dependen de la fase 2 y son independientes entre sí; ambas tocan
  `UseCaseConfig.java` (T011, T015), así que esas dos van en serie.
- T016 depende de T012 y T015. T017–T019 al final.

## Parallel Opportunities

- T002 y T004; T008 y T009; T013 puede escribirse en paralelo con T008–T012.

## Implementation Strategy

MVP = fase 2 + US1 (preferencias consultables y editables). US2 da el valor protector; las pruebas E2E
de US3/US4 no requieren código adicional porque la clave compuesta y la evaluación en cada despacho ya
las cumplen por diseño, pero cada garantía tiene su aserción propia.

## Notes

- Commits de una sola línea en español, sin tildes y sin atribución.
- Sin push ni PR.
