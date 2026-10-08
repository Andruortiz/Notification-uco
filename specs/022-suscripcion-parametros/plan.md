# Implementation Plan: Suscripción por evento al Componente de Parámetros

**Branch**: `feature/022-suscripcion-parametros` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/022-suscripcion-parametros/spec.md`

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

<!--
  Este bloque lo edita el usuario directamente en el archivo para aprobar el plan (Pendiente ->
  Aceptado) o para marcar una revisión (incrementar Versión del plan). Ningún agente infiere ni
  declara aprobación en ningún otro lugar del documento; la aprobación es el valor de este campo,
  editado por el usuario o, bajo su instrucción directa y explícita en el chat, por la sesión
  principal -- nunca por un agente en segundo plano citando un mensaje de otra sesión como fuente de
  autorización (Principio VI).
-->

## Summary

El servicio solo se entera de un cambio de configuración por sondeo. El plan añade un segundo adaptador de
entrada: un listener de RabbitMQ con ack manual sobre una cola propia atada a un exchange de Parámetros, que
lee `{version, values}` (la misma forma de la fuente HTTP) y delega en un caso de uso de `core` nuevo,
`ReceivePublishedConfigurationUseCase`, extraído de `SynchronizeConfigurationService` para que sondeo y evento
compartan la misma ruta de validación, versión, aplicación atómica y persistencia de la última conocida. El
contrato (exchange, routing key, cola) es SUPUESTO, está aislado en el adaptador y en
`notification.parameters.events.*`, y la suscripción solo existe si hay exchange configurado. El sondeo se
conserva como reconciliación. Mensaje ilegible a DLQ; rechazado u obsoleto se confirma con evento de log.

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring AMQP (ya presente). **Ninguna dependencia
nueva.**

**Storage**: MongoDB, colección `configuration_last_known` existente. Sin cambios de esquema.

**Testing**: JUnit 5, Mockito, StepVerifier, Testcontainers (Mongo + RabbitMQ), WebTestClient, ArchUnit/Modulith.

**Target Platform**: contenedor en Kubernetes, varias réplicas, configuración local por réplica.

**Project Type**: microservicio hexagonal (`core` + `infrastructure` + `utils`).

**Performance Goals**: SC-001, adopción por evento en 5 s o menos; SC-005, arranque sin suscripción en 30 s o
menos.

**Constraints**: el listener bloquea solo el hilo del contenedor de Rabbit (patrón de `AttachmentScanListener`),
nunca el flujo web; ack manual; el arranque no espera al broker; credenciales sin valores literales.

**Scale/Scope**: un mensaje de configuración es poco frecuente; concurrencia 1 por réplica.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Cumplimiento |
|---|---|
| I. Hexagonal | `ReceivePublishedConfigurationUseCase` y su servicio en `core`, Java puro, sin conocer exchange ni JSON. Listener, propiedades y topología en `infrastructure`. `HexagonalArchitectureTest` y `ModularityTests` tras la historia. Cumple |
| II. Contract-first | Sin endpoint nuevo ni cambio en `api-notificaciones.yaml`; el contrato del mensaje queda en `contracts/parametros-evento-supuesto.md`. No aplica |
| III. Cero comentarios | Ningún comentario en código nuevo. Cumple |
| IV. Calidad verificada | Unitarias en `core`, prueba del listener, prueba de inactividad y `ParametersEventSubscriptionE2ETest` con Mongo y RabbitMQ reales y aserción de `Duration` <= 5 s; `verify` completo al final. Cumple |
| V. Trazabilidad en git | Commits de una línea; razonamiento en `specs/022-…`. Cumple |
| VI. spec-kit | specify, clarify (preguntas abiertas devueltas al usuario), plan; Estado `Pendiente`. tasks e implement no se ejecutan. Cumple |
| VII. Sin atajos | El contrato supuesto es una excepción con dueño y fecha (abajo). Cumple |
| VIII. Durabilidad | La caída del broker o de la suscripción no impide arrancar ni despachar; el sondeo converge. Cumple |
| IX. Observabilidad | Eventos aplicado/rechazado/ignorado con correlación `param-`, versión, transporte; sin valores rechazados ni secretos. Cumple |
| Restricción: secretos y configuración | Sin credenciales ni URL literales; exchange, routing key y cola sin defecto. Cumple |
| Restricción: actualización condicionada atómica | La persistencia sigue siendo `saveIfNewer` condicionado a versión. Cumple |
| Restricción: consumidor con ack manual y DLQ | `MANUAL`, DLQ propia, ack solo tras decidir el destino, reintento acotado. **Sin excepción** |
| Restricción: arranque y apagado | Topología condicionada; el contenedor termina el mensaje en curso antes de cerrar. Cumple |

Reevaluación tras el diseño: sin violaciones; no hay tabla de complejidad.

## Decisiones propuestas y preguntas abiertas para el usuario

Detalle y alternativas en [research.md](./research.md) (D2, D4, D6, D7, D9). Cada una lleva recomendación; ninguna
está confirmada por el usuario.

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | ¿El evento trae los valores o solo avisa? | Trae los valores y se aplican directamente (funciona sin fuente HTTP; latencia de segundos). Variante: solo avisar y llamar `synchronize()` |
| Q2 | ¿Mismo broker o el de Parámetros? | El mismo broker del servicio, con cola y DLQ propias |
| Q3 | ¿Mensaje inválido u obsoleto? | Ilegible a DLQ; rechazado por validación y obsoleto: ack + `CONFIG_REJECTED` / `CONFIG_IGNORED`, sin DLQ; fallo inesperado: reintento acotado y DLQ |
| Q4 | ¿Y el sondeo? | Se conserva con el mismo intervalo; sin lógica automática |
| Q5 | ¿Autenticación del emisor? | No en esta entrega; control de acceso del broker + mismo validador; excepción con dueño y fecha |

## Dependencia y riesgos

- **Dependencia**: spec 019 (fusionada) y el RabbitMQ existente. No depende de la 021.
- **R-1**: contrato supuesto equivocado. Aislado en `ParametersEventListener`, `ParametersEventPayload`,
  `ParametersEventsProperties` y `ParametersEventsRabbitConfig`.
- **R-2**: el servicio declara el exchange; si Parámetros lo declara distinto, la declaración falla.
  `exchange-type` configurable y diagnóstico en el arranque.
- **R-3**: emisor no autenticado (Q5). Rango, validador y versión acotan el daño; el sondeo reconcilia.
- **R-4**: la extracción del caso de uso cambia el constructor de `SynchronizeConfigurationService`; sus
  pruebas y `UseCaseConfig` se ajustan en la misma tarea.
- **R-5**: pruebas de RabbitMQ en el mismo proceso que otras con contextos en caché; el E2E usa su propio
  contexto y contenedores.

### Pendiente explícito (Principio VII)

| Pendiente | Dueño | Fecha de revisión |
|---|---|---|
| Ajustar el contrato supuesto del evento (exchange, tipo, routing key, cola, forma, valores o aviso, broker, autenticación del emisor, destino de rechazados) al contrato definitivo de SUP-02 | Equipo de desarrollo del componente (Notification-uco), con el equipo del Componente de Parámetros | 2026-11-15 |

La excepción de la 019 sobre SUP-02 (mismo vencimiento) se atiende en conjunto con esta.

## Project Structure

### Documentation (this feature)

```text
specs/022-suscripcion-parametros/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/parametros-evento-supuesto.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks, tras aprobar el plan
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── port/in/ReceivePublishedConfigurationUseCase.java            (nuevo)
└── usecase/ReceivePublishedConfigurationService.java            (nuevo)
    usecase/SynchronizeConfigurationService.java                 (delega en el anterior)

core/src/test/java/co/edu/uco/notification/core/usecase/
├── ReceivePublishedConfigurationServiceTest.java                (nuevo)
└── SynchronizeConfigurationServiceTest.java                     (ajustado)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rabbit/ParametersEventListener.java               (nuevo)
├── adapter/in/rabbit/ParametersEventPayload.java                (nuevo)
├── adapter/in/scheduler/ConfigurationEventLogger.java           (campo transport)
├── adapter/in/scheduler/ParametersPollingScheduler.java         (transport = poll)
└── config/ParametersEventsProperties.java, ParametersEventsRabbitConfig.java, UseCaseConfig.java

utils/src/main/java/co/edu/uco/notification/utils/ErrorCode.java (PARAMETERS_EVENT_UNREADABLE)
infrastructure/src/main/resources/application.yml                (notification.parameters.events.*, sin defectos)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── ParametersEventSubscriptionE2ETest.java
├── ParametersEventsInactiveTest.java
└── adapter/in/rabbit/ParametersEventListenerTest.java, ParametersEventPayloadContractTest.java
    config/ParametersEventsPropertiesTest.java
```

**Structure Decision**: núcleo y puerto de entrada nuevo en `core`; listener, propiedades y topología en
`infrastructure`, siguiendo `AttachmentScanListener` y `AttachmentScanRabbitConfig`. Se reutilizan
`ManualAckSettler` y `ConfigurationEventLogger`.
