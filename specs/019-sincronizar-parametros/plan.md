# Implementation Plan: Sincronizar configuración transversal de Parámetros

**Branch**: `feature/HU2-073-sincronizar-parametros` | **Date**: 2026-10-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/019-sincronizar-parametros/spec.md`

## Estado del plan

**Estado**: Aprobado (2026-10-05; aprobado por el usuario en el chat)

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

Hoy los valores operativos (tope de intentos de despacho, tiempos de espera y de conexión de los proveedores,
intervalo del reencolador) se leen una vez al arrancar. El plan introduce, en `core` y sin Spring, un registro
de descriptores de parámetros, una instantánea de configuración inmutable y versionada que se reemplaza de
forma atómica, y un validador que rechaza el cambio completo si falla cualquier clave, rango, ámbito o regla
entre parámetros. Dos puertos aíslan el transporte: uno de entrada para recibir cambios y uno de salida para
obtener el estado completo de Parámetros. Un sondeo periódico (inactivo si no hay fuente configurada) es el
adaptador por defecto. La última configuración válida se persiste en MongoDB con una actualización condicional
por versión y se usa al arrancar si Parámetros no responde.

Las operaciones leen una instantánea al iniciar y la conservan hasta terminar (research D4). Los adaptadores de
proveedor obtienen su `WebClient` por versión de valores (D5) y el reencolador usa un disparador dinámico (D6).
`GET /configuration`, de solo lectura y con rol `ADMINISTRADOR`, expone el registro, la versión y el origen.

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB, Micrometer
(ya presente por Actuator). **Ninguna dependencia nueva.**

**Storage**: MongoDB, colección nueva `configuration_last_known` (un documento). Sin cambios en las colecciones
existentes (data-model.md).

**Testing**: JUnit 5, Mockito, StepVerifier, Testcontainers (Mongo + RabbitMQ), WebTestClient, ArchUnit/Modulith,
servidores HTTP falsos para proveedor y para la fuente de Parámetros.

**Target Platform**: contenedor en Kubernetes, varias réplicas; configuración local por réplica.

**Project Type**: microservicio hexagonal (`core` + `infrastructure` + `utils`).

**Performance Goals**: SC-002, un cambio rige en menos de un intervalo de sondeo (30 s por defecto); SC-007,
arranque con Parámetros no disponible en 30 s o menos.

**Constraints**: ningún hilo bloqueante en el flujo web; el arranque no espera a Parámetros; la carga de la
última conocida tiene límite de 5 s; la persistencia es una actualización condicionada atómica.

**Scale/Scope**: cuatro grupos de parámetros iniciales; configuración global, sin ajuste por tenant.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Cumplimiento |
|---|---|
| I. Hexagonal | Descriptores, instantánea, validador, reglas, puertos y casos de uso en `core`, Java puro. Sondeo, cliente HTTP, Mongo, `WebClient` por versión y el endpoint en `infrastructure`. `HexagonalArchitectureTest` y `ModularityTests` tras la historia. Cumple |
| II. Contract-first | `GET /configuration` se define en el YAML antes del controller (contracts/api-notificaciones-cambios.md), como primera tarea de infraestructura. Cumple |
| III. Cero comentarios | Ningún comentario en código nuevo; nombres y pruebas documentan. Cumple |
| IV. Calidad verificada | Unitarias en `core`, integración de Mongo, `ConfigurationSyncE2ETest` explícito con Mongo y RabbitMQ reales; cada SC con prueba (research D13), con aserciones de `Duration` en SC-002 y SC-007; `verify` completo al final. Cumple |
| V. Trazabilidad en git | Commits de una línea; razonamiento en `specs/019-…`. Cumple |
| VI. spec-kit | specify, clarify, plan; **Estado** en `Pendiente` hasta que el usuario lo edite. tasks e implement no se ejecutan. Cumple |
| VII. Sin atajos | Lo no resuelto queda como pendiente con dueño y fecha (abajo). Cumple |
| VIII. Durabilidad | La indisponibilidad de Parámetros no impide arrancar ni despachar; la configuración inválida no se aplica. Cumple |
| IX. Observabilidad | Eventos aplicado/rechazado/ignorado/indisponible con correlación, versión y origen en log, métrica y endpoint, sin valores sensibles. Cumple |
| Restricción: secretos y configuración | El registro excluye secretos, topología y direcciones base (FR-004). Cumple |
| Restricción: actualización condicionada atómica | `saveIfNewer` con `findAndModify` condicionado a la versión. Cumple |
| Restricción: arranque en 30 s | Carga con límite de 5 s, sin trabajo pesado sincrónico; probado. Cumple |
| Restricción: caché local por réplica | La instantánea es local por réplica; la consistencia entre réplicas es eventual (spec, Supuestos). Cumple |
| Restricción: consumidor con ack manual y DLQ | Esta historia **no cambia** el modo de confirmación; lee el tope de intentos que E4 introduce. Sin excepción nueva; la excepción preexistente del modo `AUTO` es de la spec 017 |

Reevaluación tras el diseño: sin cambios; sin violaciones, por lo que no hay tabla de complejidad.

## Dependencia y riesgos

- **Dependencia de la entrega E4 (despacho confiable, spec 017) fusionada en `develop`.** El tope
  `dispatch.max-attempts` por mensaje exige el listener con ack manual y la cabecera `x-dispatch-attempt`
  (017, D8). Estado a 2026-10-05: E1 fusionada (#59); E2 y E3 en curso; E4 sin fusionar. Si E4 no está
  fusionada al implementar, las tareas de esa clave se marcan bloqueadas y se entrega el resto; el descriptor
  se registra desde el inicio para no cambiar el contrato después.
- **R-1**: el `WebClient` por versión (D5) modifica los tres adaptadores de proveedor y sus pruebas E2E.
- **R-2**: conflictos de fusión con E4 en `RabbitRetryConfig` y `NotificationDispatchListener`; rebasar sobre
  `develop` antes de tocarlos.
- **R-3**: SUP-02 sigue abierto; el adaptador HTTP (incluido por decisión del usuario) se basa en un contrato provisional (S-1), está inactivo por defecto y debe poder ajustarse al definitivo sin tocar el núcleo.
- **R-4**: los límites de contenido por proveedor (S-2) se implementan con los valores de la documentación pública, y deben confirmarse antes de producción.

### Supuestos propios del plan (aceptados por el usuario; S-1 y S-2 siguen sujetos a confirmación externa)

- **S-1**: contrato provisional de la fuente de Parámetros (`contracts/parametros-fuente-provisional.md`).
- **S-2**: tabla `ProviderContentLimits` con los límites de contenido que acepta cada proveedor.
- **S-3**: rangos de los descriptores (data-model.md).
- **S-4**: `dispatch.max-attempts` gobierna el tope por mensaje y no el `RetryPolicy` del dominio.

### Pendiente explícito (Principio VII)

| Pendiente | Dueño | Fecha de revisión |
|---|---|---|
| Sustituir el adaptador HTTP provisional por el contrato definitivo de SUP-02 y decidir sondeo frente a evento | andrualv, con el equipo del Componente de Parámetros | 2026-11-15 |
| Confirmar los valores de `ProviderContentLimits` contra la documentación vigente de Brevo, Twilio y FCM antes de producción (a 2026-10-05 solo Twilio SMS `body.maxLength` 1 600 tiene fuente; Brevo y FCM: sin fuente verificable, fuera de la tabla; ver research.md) | andrualv | antes del primer despliegue a producción |
| Descriptor gestionable para el `RetryPolicy` del dominio y para la ventana de barrido de adjuntos | andrualv | 2026-11-30 |

## Project Structure

### Documentation (this feature)

```text
specs/019-sincronizar-parametros/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── api-notificaciones-cambios.md
│   └── parametros-fuente-provisional.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks, tras aprobar el plan
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/configuration/        (descriptores, instantánea, cambio, validador, reglas, FixedConfiguration)
├── port/in/                     (Apply/Synchronize/Restore/QueryConfiguration use cases, ConfigurationView)
├── port/out/                    (ParametersSourcePort, LastKnownConfigurationPort)
├── exception/                   (ParametersUnavailableException)
└── usecase/                     (ApplyConfigurationChangeService, SynchronizeConfigurationService,
                                  RestoreLastKnownConfigurationService, QueryConfigurationService,
                                  ConfigurationHolder)

core/src/test/java/co/edu/uco/notification/core/
├── domain/configuration/        (ConfigurationValidatorTest, ParameterRegistryTest, reglas)
└── usecase/                     (un test por servicio, ConfigurationHolderTest concurrente)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/             (ConfigurationController, ConfigurationResponse, ParameterDescriptorResponse)
├── adapter/in/scheduler/        (ParametersPollingScheduler; PendingNotificationSchedulerAdapter dinámico)
├── adapter/in/web/              (RouteAuthorizationPolicy: regla GET /configuration)
├── adapter/out/parameters/      (HttpParametersSource, NoParametersSource, ParametersProperties)
├── adapter/out/mongo/           (LastKnownConfigurationDocument, LastKnownConfigurationMongoAdapter)
├── adapter/out/provider/        (ProviderHttpClients, ProviderContentLimits; Brevo/Twilio/Fcm usan la instantánea)
└── config/                      (ConfigurationConfig: registro, FixedConfiguration, arranque, métrica;
                                  UseCaseConfig; RabbitRetryConfig y NotificationDispatchListener, tras E4)

infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml   (GET /configuration)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── ConfigurationSyncE2ETest.java
├── ConfigurationStartupTest.java
└── adapter/out/mongo/LastKnownConfigurationMongoAdapterTest.java
```

**Structure Decision**: núcleo y puertos en `core`; transporte, persistencia y exposición en `infrastructure`,
siguiendo la estructura del catálogo (`adapter/out/catalog`) y del reencolador (`adapter/in/scheduler`).

## Decisiones del usuario sobre las preguntas abiertas

Las cinco preguntas abiertas del borrador quedaron resueltas por el usuario; no quedan preguntas abiertas.
El estado de aprobación del plan sigue siendo el del encabezado.

| # | Decisión | Dónde se refleja |
|---|---|---|
| Q1 | Se incluye el adaptador HTTP (`HttpParametersSource`) contra el contrato provisional, inactivo por defecto. El contrato definitivo de SUP-02 sigue abierto (Principio VII, vence 2026-11-15, dueño andrualv); el adaptador debe poder ajustarse al definitivo sin tocar el núcleo. | research D4; contracts/parametros-fuente-provisional.md; Pendiente explícito |
| Q2 | "Intentos máximos de despacho" es el tope de reintento por mensaje (`notification.rabbit.dispatch.max-attempts`, defecto 3), no el `RetryPolicy` del dominio. | research D3 |
| Q3 | Se implementa la tabla `ProviderContentLimits` con los valores de la documentación pública de Brevo, Twilio y FCM; los valores deben confirmarse antes de producción. | research D8 y sección de límites |
| Q4 | Se aceptan los rangos de data-model.md como punto de partida. | data-model.md |
| Q5 | Las reglas (b), (c) y (d) se prueban con pruebas unitarias sobre instantáneas sintéticas y una prueba de arranque fallido; la regla (a) con E2E. | research D8; quickstart.md |
