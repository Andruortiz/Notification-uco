# Implementation Plan: Enrutar cada notificación al adaptador de su proveedor por providerId

**Branch**: `feature/HU2-088-enrutar-adaptador-por-proveedor` | **Date**: 2026-09-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-enrutar-adaptador-proveedor/spec.md`

## Summary

Hoy `DispatchNotificationService` recibe **un** `NotificationSenderPort` y siempre llama a ese bean;
`ChannelRoute.preferredProvider()` solo se usa para anotar el `ProviderId` del intento. Con un único
adaptador eso pasa desapercibido, pero el identificador que se guarda es el que declara el catálogo,
no el de quien realmente envió — y en cuanto existan dos beans el cableado por tipo deja de
compilar/resolver de forma determinista.

El plan: (1) `NotificationSenderPort` gana `ProviderId providerId()`, de modo que cada adaptador
declara su identidad; (2) una clase nueva de `core`, `NotificationSenderRegistry`, recibe la colección
de adaptadores y resuelve `providerId → adaptador`, fallando al construirse si hay identificadores
duplicados y lanzando `ProviderNotAvailableException` si nadie atiende un identificador; (3)
`DispatchNotificationService` pasa a depender del registro y resuelve el adaptador del proveedor
preferente **antes** de tocar la notificación, así que un proveedor no resuelto no registra intento ni
muta estado y viaja por el camino de fallo de despacho ya existente (reintentos → DLQ con la causa);
(4) `UseCaseConfig` construye el registro con el `List<NotificationSenderPort>` que Spring inyecta,
por lo que incorporar un adaptador nuevo es agregar un `@Component` y declararlo en el catálogo, sin
tocar `core` (ADR-0009). El `SimulatedNotificationProvider` declara `simulated`, que es el valor que
`application.yml` ya siembra para `EMAIL`: en un entorno limpio el comportamiento observable no
cambia.

Fuera de alcance explícito: conmutar al siguiente proveedor de la lista ante un fallo (HU2-048).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Reactor (`Mono`, ya parte del vocabulario de los puertos de `core`); Spring
Boot 3 solo en `infrastructure`, y únicamente para inyectar la lista de beans
`List<NotificationSenderPort>` en el `@Bean` que construye el registro. Ninguna dependencia nueva en
`pom.xml`.

**Storage**: ninguna colección, documento ni campo nuevo. Se lee el catálogo existente
(`ChannelCatalogPort` → `ChannelCatalogCache`) y se persiste la notificación exactamente como hoy.

**Testing**: JUnit 5 + Mockito + `StepVerifier` en `core` (registro con dos adaptadores falsos, caso
desconocido, caso duplicado, y `DispatchNotificationServiceTest` actualizado); una prueba de unidad en
`infrastructure` para el `providerId` del simulado; una prueba E2E nueva con Testcontainers (Mongo +
RabbitMQ) que cubre enrutamiento correcto y proveedor no resuelto (ver research.md, Decisión 7);
`HexagonalArchitectureTest` y `ModularityTests` deben seguir en verde.

**Target Platform**: mismo deployable Kubernetes. El registro es inmutable y se construye una vez al
arrancar; no introduce estado compartido entre réplicas.

**Project Type**: hexagonal — cambio en un puerto de salida existente + una clase de composición y una
excepción nuevas en `core`; en `infrastructure`, solo cableado y la declaración de identidad del
adaptador simulado.

**Performance Goals**: sin impacto medible. La resolución es una búsqueda en un `Map` inmutable por
despacho (Decisión 5 de research.md).

**Constraints**: `core` sin Spring (Principio I); cero comentarios explicativos (Principio III); la
resolución debe ocurrir antes de `markQueued()` para garantizar FR-006; el flujo de despacho sigue
siendo no bloqueante.

**Scale/Scope**: 3 archivos nuevos en `core` (registro, excepción, y sus pruebas), 2 archivos
modificados en `core`, 3 modificados en `infrastructure`, 1 clase E2E nueva. Sin migración de datos.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. Todo lo nuevo vive en `core` sin importar
  nada de Spring ni de `infrastructure`: `NotificationSenderRegistry` solo conoce `ProviderId` y
  `NotificationSenderPort`. La lista de beans la arma `UseCaseConfig` en `infrastructure`, que es
  precisamente el lugar donde el contenedor tiene permiso de existir. `HexagonalArchitectureTest`
  cubre esto y debe seguir en verde.
- **II. Contract-first** — NO APLICA. La historia no agrega ni cambia ningún endpoint;
  `api-notificaciones.yaml` no se toca (research.md, Decisión 8). Se deja constancia para que la
  revisión no lo lea como un contrato omitido.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). Todo el razonamiento
  de esta historia vive en `specs/007-enrutar-adaptador-proveedor/`.
- **IV. Calidad verificada, no declarada** — PASS condicionado: exige la clase E2E nueva descrita en
  Testing (dos adaptadores falsos conviviendo con el simulado + escenario de proveedor no resuelto),
  no solo las unitarias del registro. Cobertura ≥80 % líneas / ≥70 % ramas verificada con
  `./mvnw -B -ntp verify`.
- **V. Trazabilidad en git** — rama `feature/HU2-088-enrutar-adaptador-por-proveedor`, commits de una
  sola línea; los artefactos de spec-kit se commitean en esta misma rama.
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PASS con salvedad explícita: `spec.md`
  quedó con tres decisiones marcadas como *pendientes de confirmación* en su sección
  `## Clarifications` porque no hubo interacción con el usuario en la sesión. El plan implementa el
  valor por defecto de cada una; la aprobación del usuario sobre este plan es también la confirmación
  de esas tres decisiones.
- **VII. Sin atajos** — el reencolado repetido de una notificación `PENDING` cuyo proveedor no existe
  (y sus entradas repetidas en la DLQ) queda documentado como limitación conocida con dueño y fecha
  en research.md, Decisión 3 — no se tapa ni se resuelve con un parche dentro de esta historia.
- **VIII. Confiabilidad y durabilidad** — PASS y reforzado: una notificación cuyo proveedor no se
  resuelve no se pierde, no se da por entregada y deja rastro en la DLQ con su `notificationId` como
  cuerpo y el `providerId` no resuelto en el header de causa.
- **IX. Observabilidad y trazabilidad** — PASS. El fallo es distinguible por tipo
  (`ProviderNotAvailableException` vs. un `AttemptResult` de fallo), por efecto (sin intento
  registrado vs. intento persistido) y por rastro (DLQ con causa). Además, a partir de esta historia
  el `providerId` del intento corresponde al proveedor que realmente envió, que es una mejora directa
  de trazabilidad respecto de hoy.
- **Restricciones técnicas** — reactivo/no bloqueante (PASS); sin credenciales nuevas; ningún cambio
  en el bloqueo optimista ni en la idempotencia; el consumidor de RabbitMQ conserva su ack y su DLQ
  tal cual (esta historia no toca `RabbitConfig` ni `RabbitRetryConfig`, así que no hay ninguna
  excepción que justificar sobre la regla de ack manual y DLQ).

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/007-enrutar-adaptador-proveedor/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
├── checklists/          # requirements.md (/speckit-specify command output)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/port/out/
├── NotificationSenderPort.java        # MODIFICADO: + ProviderId providerId()
└── NotificationSenderRegistry.java    # NUEVO: clase final; Map<ProviderId, NotificationSenderPort>
                                       # inmutable; resolve(ProviderId); falla al construirse si hay
                                       # identificadores duplicados o nulos

core/src/main/java/co/edu/uco/notification/core/exception/
└── ProviderNotAvailableException.java # NUEVO: RuntimeException que nombra el providerId no resuelto

core/src/main/java/co/edu/uco/notification/core/usecase/
└── DispatchNotificationService.java   # MODIFICADO: depende de NotificationSenderRegistry en vez de
                                       # NotificationSenderPort; resuelve el adaptador del proveedor
                                       # preferente ANTES de markQueued(); envía por ese adaptador

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
└── SimulatedNotificationProvider.java # MODIFICADO: + providerId() = ProviderId.of("simulated")

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/
└── UseCaseConfig.java                 # MODIFICADO: + @Bean notificationSenderRegistry(
                                       #   List<NotificationSenderPort> senders); el bean de despacho
                                       #   pasa a recibir el registro

core/src/test/java/co/edu/uco/notification/core/port/out/
└── NotificationSenderRegistryTest.java  # NUEVO: dos adaptadores falsos → elige el correcto;
                                         # providerId desconocido → ProviderNotAvailableException;
                                         # identificadores duplicados → falla al construirse

core/src/test/java/co/edu/uco/notification/core/exception/
└── ProviderNotAvailableExceptionTest.java  # NUEVO (solo si el mensaje tiene lógica propia)

core/src/test/java/co/edu/uco/notification/core/usecase/
└── DispatchNotificationServiceTest.java  # MODIFICADO: el doble declara su providerId; el servicio se
                                          # construye con el registro; + prueba de que un preferente
                                          # sin adaptador no registra intento ni persiste

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── SimulatedNotificationProviderTest.java  # MODIFICADO: + aserción de providerId()
└── ProviderRoutingE2ETest.java             # NUEVO: prueba E2E del Principio IV (dos adaptadores
                                            # falsos + el simulado real conviviendo; enrutamiento
                                            # correcto y proveedor no resuelto → DLQ)
```

**Structure Decision**: No se crea módulo ni paquete nuevo. El registro se ubica junto a
`NotificationSenderPort` en `core/port/out` porque es la pieza que compone ese puerto (igual que
`ChannelRoute` vive junto a `ChannelCatalogPort`), y la excepción junto a las demás de `core`. En
`infrastructure` el cambio es de cableado, no de estructura: `UseCaseConfig` es el único punto que
conoce la lista de adaptadores, así que incorporar un proveedor nuevo no obliga a editarlo.

## Diseño del cambio en el despacho

Orden dentro de `attemptSend(notification, route)`, con el porqué de cada paso:

1. `registry.resolve(route.preferredProvider())` — **primero**. Si lanza, no se ha mutado ni
   persistido nada: la notificación conserva su estado y su historial (FR-006), y el error viaja como
   señal de error del `Mono` (el `flatMap` que lo envuelve convierte la excepción sincrónica en señal
   de error; se hará explícito con `Mono.fromCallable`/`Mono.defer` para no depender de ese detalle).
2. `notification.markQueued()` — igual que hoy, solo cuando ya hay un adaptador con el que enviar.
3. `sender.send(notification)` — por el adaptador resuelto, no por un bean fijo.
4. `applyOutcome(..., route.preferredProvider(), ...)` — el `ProviderId` anotado en el intento es el
   mismo con el que se resolvió el adaptador, así que ya no puede divergir de quien envió (FR-003).

El resto del flujo (persistencia, publicación de eventos, política de reintentos) no cambia.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
