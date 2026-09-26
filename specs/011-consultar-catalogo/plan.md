# Implementation Plan: Consultar el catálogo de canales y proveedores

**Branch**: `feature/HU2-085-consultar-catalogo` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/011-consultar-catalogo/spec.md`

## Estado del plan

**Estado**: Aceptado

**Versión del plan**: 1

## Summary

Hoy el catálogo dinámico (HU2-042) enruta cada canal hacia una lista ordenada de proveedores, pero nada
expone esa información por HTTP: el operador tiene que leer `channel_catalog` o la configuración, y el
estado de habilitación de cada proveedor solo aparece en el registro de arranque de cada réplica. Esta
historia añade dos lecturas, **contract-first**:

- **`GET /channels`** → `{ items: [ { channelType, contentSchema, providers: [ { providerId,
  preferenceOrder, status, statusReason } ] } ] }`, ordenado por canal.
- **`GET /providers`** → `{ items: [ { providerId, status, statusReason, channels: [ { channelType,
  preferenceOrder } ] } ] }`, ordenado por proveedor; unión de adaptadores del despliegue y proveedores
  nombrados por el catálogo.
- `status ∈ { ENABLED, DISABLED, MISSING_ADAPTER }`; `statusReason` es `null` si `ENABLED`, el motivo del
  adaptador si `DISABLED` (nombra la configuración, nunca su valor), o un texto fijo si `MISSING_ADAPTER`.

Las cuatro decisiones de alcance (spec, Clarifications, pendientes de confirmación):

1. **Q1 — Global con `X-Tenant-Id` exigido.** El catálogo no tiene tenant; la respuesta es idéntica para
   todos. La cabecera se exige y valida como en el resto de la API (sustituto provisional de la identidad
   hasta DEP-01), pero no llega al núcleo (research.md, Decisión 1).
2. **Q2 — Se cruza el estado real de habilitación.** `NotificationSenderPort` gana `disabledReason()`
   (abstracto) y `NotificationSenderRegistry` gana `find`/`providerIds`; el estado mostrado es por
   construcción el que encuentra el despacho (Decisión 2).
3. **Q3 — La consulta de proveedores es la unión** de adaptadores y catálogo (Decisión 3).
4. **Q4 — Se lee la vista que usa el enrutamiento** (`ChannelCatalogCache`), no la base (Decisión 4).

La lógica (derivar estado, unir, ordenar) vive en un caso de uso nuevo de `core`,
`QueryChannelCatalogService`, sin Spring; `infrastructure` aporta la lectura del snapshot, los motivos de los
adaptadores y el controlador.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB (solo a través
de la caché existente). Sin dependencias nuevas.

**Storage**: ninguno nuevo. Lectura de `ChannelCatalogCache` (poblada desde `channel_catalog` por
`ChannelCatalogRefresher`). Ninguna escritura.

**Testing**: JUnit 5, Mockito, `StepVerifier`, `@WebFluxTest`, `@SpringBootTest(RANDOM_PORT)` +
Testcontainers (MongoDB 7, RabbitMQ 3) + `WebTestClient`, ArchUnit, Spring Modulith.

**Target Platform**: contenedor en Kubernetes (varias réplicas).

**Project Type**: microservicio hexagonal — `core` (puertos, caso de uso) + `infrastructure` (adaptador de
entrada REST, adaptadores de salida existentes).

**Performance Goals**: lectura en memoria de pocas decenas de entradas; sin objetivo de latencia propio
(spec, Assumptions). SC-005 fija el único umbral de tiempo: ≤ 5 s con refresco de 1 s.

**Constraints**: sin `.block()` nuevo; ninguna lectura de MongoDB por consulta; ningún secreto en la
respuesta; `core` sin Spring.

**Scale/Scope**: 3 canales y 4 proveedores por defecto; decenas como máximo.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Re-evaluado después de Phase 1: sin cambios, PASS (con VI pendiente de aprobación).

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. El caso de uso, las vistas y el enum viven en
  `core` (`port/in`, `usecase`) sin Spring. `core/port/out` cambia de forma: `ChannelCatalogPort` +
  `findAllRoutes()`, `NotificationSenderPort` + `disabledReason()`, `NotificationSenderRegistry` +
  `find`/`providerIds`; los adaptadores los implementan, nunca al revés. `HexagonalArchitectureTest` y
  `ModularityTests` deben seguir en verde.
- **II. Contract-first** — PASS. Las dos operaciones y sus esquemas quedan escritos en
  `contracts/api-notificaciones-cambios.md` y se aplican a `api-notificaciones.yaml` como **primera tarea**,
  antes del controlador. Son lecturas de colección: REST estándar (`GET /channels`, `GET /providers`), no
  `recurso:accion`.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). El porqué de "abstracto y
  no `default`", de la clave en mayúsculas y de `required = false` vive en research.md.
- **IV. Calidad verificada, no declarada** — PASS condicionado. E2E explícita `ChannelCatalogQueryE2ETest`
  en `tasks.md`, cubriendo SC-001 a SC-005 con aserciones automatizadas (SC-005 con `Duration` explícita;
  SC-003 con dos tenants y comparación del cuerpo completo; SC-004 con control positivo). Cobertura ≥ 80 %
  líneas / ≥ 70 % ramas con `./mvnw -B -ntp verify` completo.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-085-consultar-catalogo`, commits de una línea en
  español sin tildes y sin trailers, artefactos de spec-kit en la misma rama. Los cambios ajenos
  (`Recipient.java`, `application.yml`, `.claude/`, `docs/`, `qodana.yaml`) no entran en ningún commit.
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PENDIENTE. Spec y plan en estado Pendiente;
  Q1–Q4 con respuesta recomendada, pendientes de confirmación. No se genera `tasks.md` ni se implementa
  nada hasta que el usuario cambie `## Estado del plan` a Aceptado.
- **VII. Sin atajos** — PASS. Pendientes con dueño y fecha en spec.md § Risks: exposición de la
  configuración hasta DEP-01 (2026-12-31), estado de configuración vs. salud del proveedor hasta HU2-048
  (2026-12-31), réplicas con configuración distinta (2026-12-31). `disabledReason()` abstracto en lugar de
  un `default` que ocultaría un adaptador sin declarar.
- **VIII. Confiabilidad y durabilidad** — PASS, no aplica: lectura pura, no toca aceptación, despacho ni
  persistencia de notificaciones.
- **IX. Observabilidad y trazabilidad** — PASS. La historia mejora la observabilidad operativa (el estado
  de habilitación deja de estar solo en el registro de arranque). No se añaden registros por consulta; el
  registro estructurado (RNF-10) sigue siendo una brecha preexistente, fuera de alcance.
- **Restricciones técnicas** — PASS. Reactivo sin bloqueo (`Mono`/`Flux` sobre un snapshot en memoria).
  Ningún secreto en respuestas HTTP (FR-009, SC-004). Caché local por réplica ya existente; no se añade
  almacén. Extensibilidad por catálogo (ADR-0009): un adaptador nuevo aparece en `GET /providers` sin tocar
  el caso de uso. Autenticación: `X-Tenant-Id` provisional, aceptable en desarrollo mientras DEP-01 siga
  bloqueado.
- **Ack manual y DLQ de RabbitMQ** — **sin excepción que justificar**. No se toca RabbitMQ.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/011-consultar-catalogo/
├── plan.md              # This file (/speckit-plan command output)
├── spec.md              # /speckit-specify + /speckit-clarify (Q1–Q4 pendientes de confirmación)
├── research.md          # Phase 0 output — 9 decisiones
├── data-model.md        # Phase 1 output — vistas de lectura y cambios de puertos
├── quickstart.md        # Phase 1 output — validación automatizada y manual
├── contracts/
│   └── api-notificaciones-cambios.md   # GET /channels, GET /providers y esquemas nuevos
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NO lo crea /speckit-plan)
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── port/in/
│   ├── QueryChannelCatalogUseCase.java   # NUEVO: listChannels(), listProviders() -> Mono<List<...>>
│   ├── ChannelView.java                  # NUEVO: record
│   ├── ChannelProviderView.java          # NUEVO: record
│   ├── ProviderView.java                 # NUEVO: record
│   ├── ProviderChannelView.java          # NUEVO: record
│   └── ProviderStatus.java               # NUEVO: enum ENABLED, DISABLED, MISSING_ADAPTER
├── port/out/
│   ├── ChannelCatalogPort.java           # MODIFICADO: + Flux<ChannelRoute> findAllRoutes()
│   ├── NotificationSenderPort.java       # MODIFICADO: + Optional<String> disabledReason() (abstracto)
│   └── NotificationSenderRegistry.java   # MODIFICADO: + find(ProviderId), + providerIds()
└── usecase/
    └── QueryChannelCatalogService.java   # NUEVO: estado, unión y orden

core/src/test/java/co/edu/uco/notification/core/
├── port/out/NotificationSenderRegistryTest.java   # MODIFICADO: find, providerIds; FakeSender implementa disabledReason
└── usecase/QueryChannelCatalogServiceTest.java    # NUEVO

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/
│   ├── ChannelCatalogController.java     # NUEVO: GET /channels, GET /providers
│   ├── ChannelCatalogResponse.java       # NUEVO: record { items }
│   ├── ChannelItemResponse.java          # NUEVO: record
│   ├── ChannelProviderItemResponse.java  # NUEVO: record
│   ├── ProviderCatalogResponse.java      # NUEVO: record { items }
│   ├── ProviderItemResponse.java         # NUEVO: record
│   └── ProviderChannelItemResponse.java  # NUEVO: record
├── adapter/out/catalog/
│   └── MongoChannelCatalogAdapter.java   # MODIFICADO: findAllRoutes() sobre el snapshot
├── adapter/out/provider/
│   ├── BrevoNotificationProvider.java    # MODIFICADO: disabledReason() devuelve el campo existente
│   ├── TwilioNotificationProvider.java   # MODIFICADO: idem
│   ├── FcmNotificationProvider.java      # MODIFICADO: idem
│   └── SimulatedNotificationProvider.java # MODIFICADO: disabledReason() vacío
└── config/
    └── UseCaseConfig.java                # MODIFICADO: @Bean QueryChannelCatalogUseCase

infrastructure/src/main/resources/static/openapi/
└── api-notificaciones.yaml               # MODIFICADO: primera tarea (Principio II)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/
│   ├── ChannelCatalogControllerTest.java # NUEVO: @WebFluxTest
│   └── ChannelCatalogQueryE2ETest.java   # NUEVO: E2E del Principio IV
├── adapter/out/catalog/
│   └── MongoChannelCatalogAdapterTest.java # MODIFICADO: findAllRoutes
└── adapter/out/provider/
    ├── BrevoNotificationProviderTest.java      # MODIFICADO: disabledReason presente/vacío
    ├── TwilioNotificationProviderTest.java     # MODIFICADO: idem
    ├── FcmNotificationProviderTest.java        # MODIFICADO: idem
    ├── SimulatedNotificationProviderTest.java  # MODIFICADO: vacío
    └── ProviderRoutingE2ETest.java             # MODIFICADO: RecordingNotificationSender implementa disabledReason
```

**Structure Decision**: sin módulos ni paquetes nuevos. El controlador es una clase aparte de
`NotificationController` porque su raíz no es `/notifications`; reutiliza el `NotificationExceptionHandler`
global y su `ErrorResponse`. Las vistas del caso de uso van en `port/in` como las demás vistas de lectura
(`NotificationStatusView`, `NotificationSearchPage`). `application.yml` no se toca.

## Diseño del caso de uso

`QueryChannelCatalogService(ChannelCatalogPort, NotificationSenderRegistry)`:

- **Estado** (`statusOf(ProviderId)`, privado y compartido por las dos operaciones):
  `registry.find(id)` vacío → `MISSING_ADAPTER` + motivo fijo; `sender.disabledReason()` presente →
  `DISABLED` + motivo; si no → `ENABLED` + `null`.
- **`listChannels()`**: `catalog.findAllRoutes()` → cada ruta a `ChannelView` (posición = índice + 1,
  esquema en blanco → `null`) → `collectSortedList` por `channelType.value()`.
- **`listProviders()`**: `catalog.findAllRoutes().collectList()` → mapa `ProviderId → List<ProviderChannelView>`
  con cada aparición (incluidas repeticiones) → unión de sus claves con `registry.providerIds()` → cada
  proveedor a `ProviderView` con su estado y sus canales ordenados → orden por `providerId.value()`.
- El registro es inmutable desde el arranque y el snapshot se lee una vez por consulta: una consulta ve un
  catálogo consistente aunque un refresco ocurra en paralelo.

## Diseño del controlador

- `@RestController` sin `@RequestMapping` de clase; `@GetMapping("/channels")` y `@GetMapping("/providers")`.
- `@RequestHeader(name = "X-Tenant-Id", required = false) String tenantId` → `TenantId.of(tenantId)`
  para validar (ausente o vacío → `400 ErrorResponse`), luego el caso de uso sin tenant.
- Mapeo a records con `from(...)` estáticos, como `NotificationSearchResponse.from`; `status` como
  `ProviderStatus.name()`.

## Orden de implementación y puntos de control

Orden por tarea: escribir la prueba, verla fallar por la razón correcta, implementar, ejecutar la clase,
`spotless:apply`.

1. **Contrato público** — aplicar `contracts/api-notificaciones-cambios.md` a `api-notificaciones.yaml`
   (Principio II, antes del código).
2. **`NotificationSenderPort.disabledReason()`** + los cuatro adaptadores + los dos dobles de prueba +
   pruebas de `disabledReason()` en las cuatro clases de prueba de proveedor. Compilar `core` e
   `infrastructure` antes de seguir: el método abstracto rompe cualquier implementación olvidada.
3. **`NotificationSenderRegistry.find`/`providerIds`** + `NotificationSenderRegistryTest`.
4. **`ChannelCatalogPort.findAllRoutes()`** + `MongoChannelCatalogAdapter` + `MongoChannelCatalogAdapterTest`
   (clave en mayúsculas aunque la ruta traiga el canal en minúsculas; snapshot vacío).
5. **Vistas, `QueryChannelCatalogUseCase` y `QueryChannelCatalogServiceTest` → `QueryChannelCatalogService`**
   — tres estados, unión, repeticiones, orden, esquema en blanco, catálogo vacío con adaptadores.
6. **`UseCaseConfig`** — bean del caso de uso.
7. **DTOs, `ChannelCatalogControllerTest` → `ChannelCatalogController`** — nulos explícitos, `400` sin
   cabecera y con cabecera vacía, cuerpo `ErrorResponse`.
8. **`ChannelCatalogQueryE2ETest`** — tarea E2E explícita del Principio IV (research.md, Decisión 8):
   SC-001 a SC-005, FR-010 y `400`; propiedades `notification.catalog.refresh-interval-ms=1000` y una
   credencial parcial de Twilio con valor reconocible (auth-token presente, account-sid ausente).
9. **Regresión**: `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, `ChannelCatalogResilienceE2ETest`,
   pruebas de proveedores, `NotificationControllerTest`, `HexagonalArchitectureTest`, `ModularityTests`.
10. **`./mvnw -B -ntp verify` completo y en verde.** Si las únicas que fallan son `DeadLetterQueueE2ETest`
    y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo explícitamente: sobre esas dos
    decide CI.

## Riesgos de esta implementación

| Riesgo | Cómo lo acota el plan |
|---|---|
| El método abstracto en `NotificationSenderPort` rompe implementaciones no previstas. | Búsqueda hecha: 4 adaptadores + 2 dobles de prueba; los mocks de Mockito devuelven `Optional.empty()`. Compilación completa en el paso 2 antes de seguir. |
| Un motivo de deshabilitación futuro incluye un valor de credencial y sale por HTTP. | SC-004 lo comprueba con la configuración real; los motivos actuales son textos fijos revisados (research.md, Decisión 2). Un adaptador nuevo hereda la prueba E2E si se añade a la configuración por defecto. |
| SpotBugs señala `TenantId.of(...)` con el resultado ignorado. | Si ocurre, el controlador usa el valor (por ejemplo, un método privado `requireTenant` que lo devuelve) en lugar de suprimir el aviso; no se añade exclusión sin reportarla. |
| Pruebas E2E que modifican `channel_catalog` interfieren entre sí o con otras clases. | Restauración a los tres documentos por defecto en `@BeforeEach` y espera activa a que `GET /channels` lo refleje; contexto propio con propiedades distintas. |
| Espera activa del refresco vuelve lenta o inestable la E2E. | Refresco de 1 s en la prueba; plazo de 5 s en SC-005 y de 20 s como máximo en la preparación. |
| El `400` por cabecera ausente difiere del resto de la API (cuerpo `ErrorResponse` en lugar del genérico). | Decisión consciente para que el contrato sea exacto (research.md, Decisión 1); no cambia las operaciones existentes. |

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
