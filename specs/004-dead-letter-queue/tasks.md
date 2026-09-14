---

description: "Task list template for feature implementation"
---

# Tasks: Cola de mensajes muertos (DLQ) para el despacho

**Input**: Design documents from `/specs/004-dead-letter-queue/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [quickstart.md](./quickstart.md)

**Tests**: Incluidas — el Principio IV de la constitución exige al menos una prueba end-to-end
bloqueante para todo flujo observable, y esta historia además introduce el primer caso del proyecto
que necesita un broker RabbitMQ real en CI (ver research.md, Decisión 2).

**Organization**: Tareas agrupadas por historia de usuario (US1, US2) para permitir implementación y
prueba independientes de cada una.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: A qué historia de usuario pertenece la tarea (US1, US2)
- Cada descripción incluye la ruta de archivo exacta

## Path Conventions

Todo el trabajo cae en `infrastructure/` (config de Spring AMQP y topología RabbitMQ) más
`.github/workflows/ci.yml` — no se toca `core/` (ver plan.md, Constitution Check, Principio I).

---

## Phase 1: Setup

**Purpose**: Confirmar dependencias antes de tocar código de producción

- [x] T001 Confirmar que `spring-boot-starter-amqp` (ya presente en `infrastructure/pom.xml`) expone
      `RabbitAdmin`/`SimpleRabbitListenerContainerFactory` sin necesitar un starter adicional
- [x] T002 Añadir `spring-retry` como dependencia explícita en `infrastructure/pom.xml` —
      `RetryInterceptorBuilder`/`StatefulRetryOperationsInterceptor` requieren sus clases en el
      classpath de compilación y no está confirmado que `spring-boot-starter-amqp` lo arrastre como
      transitiva no-opcional
- [x] T003 [P] Confirmar que la configuración existente de Spotless/SpotBugs/FindSecBugs en
      `infrastructure/pom.xml` cubre automáticamente los paquetes nuevos bajo
      `infrastructure/config` (no se espera cambio, solo verificación)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Topología RabbitMQ y configuración base que ambas historias necesitan

**⚠️ CRITICAL**: Ninguna historia puede implementarse hasta que esta fase esté completa

- [x] T004 [P] Añadir el record anidado `Dlq(String exchange, String routingKey, String queue)` a
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/RabbitTopologyProperties.java`,
      siguiendo el mismo estilo que el `Dispatch` existente
- [x] T005 [P] Añadir las propiedades `notification.rabbit.dlq.{exchange,routing-key,queue}` (valores
      fijos: `notification.dispatch.dlq.exchange` / `notification.dispatch.dlq` /
      `notification.dispatch.dlq.queue`) y `notification.rabbit.dispatch.max-attempts:
      ${NOTIFICATION_DISPATCH_MAX_ATTEMPTS:3}` a `infrastructure/src/main/resources/application.yml`
- [x] T006 Añadir los beans `@Bean DirectExchange notificationDispatchDlqExchange`,
      `@Bean Queue notificationDispatchDlqQueue` y `@Bean Binding notificationDispatchDlqBinding` a
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/RabbitConfig.java`,
      con la misma forma que los beans de despacho existentes (depende de T004) — requirió además
      `@Qualifier` explícito en los parámetros de `notificationDispatchBinding`/
      `notificationDispatchDlqBinding`, ya que con dos beans `Queue`/`DirectExchange` en el contexto
      Spring dejó de poder desambiguar solo por nombre de parámetro (ver Notas de implementación)
- [x] T007 Confirmar que `HexagonalArchitectureTest` (o el test de arquitectura equivalente) sigue en
      verde — ningún archivo de `core/` cambia en esta historia

**Checkpoint**: Topología DLQ declarada y verificada — las historias de usuario pueden empezar

---

## Phase 3: User Story 1 - No perder una notificación cuyo procesamiento falla repetidamente (Priority: P1) 🎯 MVP

**Goal**: Un mensaje de despacho cuyo procesamiento falla con una excepción real se reintenta hasta 3
veces en memoria y, al agotar los intentos, se mueve a `notification.dispatch.dlq.queue` con la causa
en los headers — sin quedar en reintento infinito ni perderse en silencio.

**Independent Test**: Forzar una excepción determinística en `DispatchNotificationUseCase.dispatch`
(ej. un `notificationId` inexistente) y confirmar que, tras 3 intentos, el mensaje aparece en
`notification.dispatch.dlq.queue` con `x-exception-message` poblado, y que
`notification.dispatch.queue` deja de reintentarlo.

### Tests for User Story 1 ⚠️

> **NOTE: Escribir estas pruebas PRIMERO, confirmar que fallan antes de implementar**

- [x] T008 [P] [US1] Unit test en
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfigTest.java`:
      con `max-attempts=3`, 3 fallos consecutivos invocan el recoverer exactamente una vez; un éxito en
      el intento 2 nunca invoca el recoverer (Acceptance Scenario 1 y 2)
- [x] T009 [P] [US1] Unit test (mismo archivo que T008) para el edge case de la especificación: si
      `RepublishMessageRecoverer.recover()` lanza (ej. broker no disponible), la excepción se propaga
      en vez de swallowearse — el mensaje original no se pierde, queda para requeue/redelivery
- [x] T010 [US1] Añadir un servicio `rabbitmq` (imagen `rabbitmq:3-management`, con healthcheck) al job
      `build` de `.github/workflows/ci.yml`, para que la prueba E2E de T011 corra en CI (research.md,
      Decisión 2)
- [x] T011 [US1] E2E test en
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/DeadLetterQueueE2ETest.java`:
      `@SpringBootTest` contra un RabbitMQ real (local vía `docker compose up -d rabbitmq`, en CI vía
      T010) que publica un mensaje envenenado y verifica que llega a
      `notification.dispatch.dlq.queue` con `x-exception-message` y `x-original-routingKey`
      (Acceptance Scenario 1 y 3; Principio IV) (depende de T006, T010) — verificado localmente contra
      el RabbitMQ real de `docker-compose.yml`: 1/1 test, 0 fallos, ~26s

### Implementation for User Story 1

- [x] T012 [US1] Implementar
      `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfig.java`:
      `StatefulRetryOperationsInterceptor` (vía `RetryInterceptorBuilder.stateful()`) con
      `maxAttempts` desde `@Value("${notification.rabbit.dispatch.max-attempts:3}")`, recoverer
      `RepublishMessageRecoverer` apuntando al exchange/routing-key de T004/T005, enganchado al
      `SimpleRabbitListenerContainerFactory` vía `setAdviceChain(...)` (depende de T004, T005, T006) —
      requirió además setear `MessageProperties.messageId` al publicar en
      `NotificationRabbitPublisher.enqueueForDispatch` (no listado originalmente en plan.md), ya que el
      generador de clave por defecto de `StatefulRetryOperationsInterceptor` necesita un `messageId`
      estable para correlacionar reintentos del mismo mensaje entre redeliveries (ver Notas de
      implementación)
- [x] T013 [US1] Confirmar cobertura ≥80 % líneas / ≥70 % ramas (Principio IV) para
      `RabbitRetryConfig.java` y los beans nuevos de `RabbitConfig.java` — ambos archivos: 0
      instrucciones/líneas/ramas sin cubrir según `jacoco.csv`; el único hueco en
      `NotificationRabbitPublisher` (2 líneas, catch de `JsonProcessingException`) es preexistente, no
      introducido por esta historia. La puerta de cobertura del *bundle* completo del módulo no pudo
      validarse localmente end-to-end porque Testcontainers no detecta Docker en esta máquina Windows
      (limitación ya documentada, no relacionada con DLQ) — se confía en que CI (Linux, con
      Testcontainers funcionando) la valide de punta a punta

**Checkpoint**: User Story 1 completamente funcional y probable de forma independiente

---

## Phase 4: User Story 2 - Configurar el número de intentos antes de dar por muerto un mensaje (Priority: P2)

**Goal**: El número de intentos antes de mover un mensaje a la cola de mensajes muertos se puede
cambiar vía `NOTIFICATION_DISPATCH_MAX_ATTEMPTS` sin recompilar.

**Independent Test**: Cambiar `NOTIFICATION_DISPATCH_MAX_ATTEMPTS` a un valor distinto de 3 (ej. 1) y
confirmar que un mensaje se mueve a la DLQ exactamente después de esa cantidad de fallos, ni antes ni
después.

### Tests for User Story 2 ⚠️

- [x] T014 [P] [US2] Unit test en
      `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/RabbitRetryConfigCustomAttemptsTest.java`:
      con `notification.rabbit.dispatch.max-attempts=1` (vía `@SpringBootTest(properties=...)`, mismo
      patrón que `CorsConfigCustomOriginTest`), un único fallo ya invoca el recoverer (Acceptance
      Scenario 1 de US2) — verificado localmente contra RabbitMQ real: 1/1 test, 0 fallos, ~19s

### Implementation for User Story 2

- [ ] T015 [US2] Validar manualmente el Escenario 2 de
      [quickstart.md](./quickstart.md) con `NOTIFICATION_DISPATCH_MAX_ATTEMPTS=1` contra el
      `docker compose` local, confirmando que no se requiere recompilar ni redesplegar código — solo
      cambiar la variable de entorno (SC-001 de la historia; sin tarea de producción nueva, ya que
      T012 ya externaliza el valor)

**Checkpoint**: User Story 1 y User Story 2 funcionan de forma independiente

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Verificaciones finales que afectan a ambas historias

- [x] T016 [P] Confirmar Spotless/SpotBugs/FindSecBugs sin hallazgos sobre todos los archivos nuevos o
      modificados de esta historia — `mvn verify` pasó ambas puertas (llegó hasta jacoco-check sin
      fallos de Spotless ni SpotBugs)
- [ ] T017 Ejecutar manualmente ambos escenarios de [quickstart.md](./quickstart.md) de punta a punta
      contra el `docker compose` local (mensaje envenenado → DLQ; mensaje exitoso → nunca DLQ) —
      queda como validación manual pendiente, igual que T010 en HU2-071 (CORS)
- [x] T018 [P] Si la implementación se desvió de alguna decisión documentada en
      [research.md](./research.md) o [data-model.md](./data-model.md), actualizar esos archivos para
      que sigan siendo la fuente de verdad del diseño — ver Notas de implementación agregadas a
      research.md (mecanismo de messageId, el redelivery extra antes de recuperar, y el `@Qualifier`
      necesario en RabbitConfig)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias — puede iniciar de inmediato
- **Foundational (Phase 2)**: depende de Setup — BLOQUEA ambas historias
- **User Story 1 (Phase 3)**: depende de Foundational — es el MVP
- **User Story 2 (Phase 4)**: depende de Foundational; sus pruebas dependen de que T012 (US1) ya exista,
  porque US2 valida un comportamiento configurable del mismo mecanismo que construye US1 — no es
  independiente en implementación, sí lo es en prueba/validación
- **Polish (Phase 5)**: depende de que US1 y US2 estén completas

### Within Each User Story

- Tests (T008, T009, T011, T014) se escriben y deben FALLAR antes de implementar T012
- Foundational (topología) antes de la implementación del interceptor
- Historia completa antes de pasar a la siguiente prioridad

### Parallel Opportunities

- T001, T002, T003 (Setup) en paralelo
- T004, T005 (Foundational) en paralelo — archivos distintos
- T008, T009 (mismo archivo de test, pero métodos independientes) pueden escribirse en paralelo antes
  de T012; T010 (cambio en `ci.yml`) es independiente y paralelizable con T008/T009
- T014 (US2) puede escribirse en paralelo con el resto de US1 una vez Foundational está listo, aunque
  solo puede pasar en verde después de T012

---

## Implementation Strategy

### MVP First (User Story 1 únicamente)

1. Completar Phase 1: Setup
2. Completar Phase 2: Foundational (bloqueante)
3. Completar Phase 3: User Story 1
4. **DETENER y VALIDAR**: correr T011 (E2E) y el Escenario 1 de quickstart.md de forma independiente
5. Esto ya satisface el requisito central de RNF-05 para el único punto ciego de despacho — User Story
   2 es una mejora de operabilidad, no un bloqueante del valor central

### Incremental Delivery

1. Setup + Foundational → topología DLQ lista
2. User Story 1 → probar de forma independiente → esto ya es el valor entregable (MVP)
3. User Story 2 → probar de forma independiente → confirma que el número de intentos es ajustable sin
   recompilar
4. Polish → cierre de calidad y validación manual

---

## Notes

- [P] tareas = archivos distintos, sin dependencias pendientes
- [Story] mapea la tarea a su historia de usuario para trazabilidad
- Verificar que las pruebas fallan antes de implementar
- Commit por tarea o grupo lógico, una sola rama para toda la historia
  (`feature/HU2-040-dead-letter-queue`, ADR-0015) — commits de una sola línea, atribución de IA según
  la instrucción vigente de esta sesión (Principio V, con la excepción ya documentada en plan.md)
- Detenerse en cada checkpoint para validar la historia de forma independiente
- Evitar: tareas vagas, conflictos de archivo simultáneos, dependencias entre historias que rompan su
  independencia de prueba
