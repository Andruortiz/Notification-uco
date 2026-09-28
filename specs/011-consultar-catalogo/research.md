# Research: Consultar el catálogo de canales y proveedores

Todas las decisiones parten del código existente leído antes de diseñar: `ChannelCatalogPort`,
`ChannelRoute`, `NotificationSenderRegistry` y `NotificationSenderPort` en `core`; `ChannelCatalogCache`,
`ChannelCatalogRefresher`, `ChannelCatalogSeeder`, `MongoChannelCatalogAdapter` y los cuatro adaptadores de
proveedor en `infrastructure`; `NotificationController`, `NotificationExceptionHandler` y el contrato
`api-notificaciones.yaml`.

## Decisión 1 — Contenido global con identificador de tenant exigido (Q1)

- **Decisión**: ambas operaciones declaran el parámetro `X-Tenant-Id` obligatorio (el mismo componente
  `#/components/parameters/TenantId` del contrato). El controlador lo recibe con
  `@RequestHeader(name = "X-Tenant-Id", required = false)` y lo valida construyendo `TenantId`: ausente
  (`null`) o vacío → `IllegalArgumentException` de `Preconditions.requireNonBlank` → `400` con
  `ErrorResponse` por el manejador existente. Así el `400` que declara el contrato tiene siempre el cuerpo
  `ErrorResponse`; con `required = true`, la cabecera ausente produciría el cuerpo genérico de WebFlux, que
  no es el que documenta el contrato. El tenant validado **no se pasa al núcleo**: el caso de uso no
  recibe tenant porque el resultado no depende de él.
- **Rationale**: el catálogo no tiene tenant (`ChannelCatalogDocument` no tiene el campo y
  `MongoChannelCatalogAdapter.findActiveRoute` ignora el `TenantId` que recibe). Pasar un tenant que no se
  usa al caso de uso sería una firma engañosa. Exigir la cabecera mantiene la operación dentro del modelo
  de identidad de la API: todas las operaciones existentes, y las de registro de canal y proveedor ya
  descritas en el contrato, la exigen; cuando se integre la autenticación (DEP-01) la restricción a
  administradores que anuncia el contrato se añade en el mismo punto sin romper al panel.
- **Alternativas descartadas**: sin cabecera (operación fuera del modelo de identidad; cambio
  incompatible al integrar seguridad); `ChannelCatalogQuery(TenantId)` en el caso de uso (parámetro muerto
  en `core`); filtrar por tenant (no existe dato por tenant).

## Decisión 2 — Se cruza el estado real de habilitación de los adaptadores (Q2)

- **Decisión**: `NotificationSenderPort` gana el método abstracto `Optional<String> disabledReason()`.
  Los adaptadores reales devuelven el campo `disabledReason` que ya calculan al construirse
  (`BrevoProviderProperties.disabledReason()`, `TwilioProviderProperties.disabledReason()`,
  `FcmCredentials.disabledReason()`); `SimulatedNotificationProvider` devuelve `Optional.empty()`.
  `NotificationSenderRegistry` gana `Optional<NotificationSenderPort> find(ProviderId)` y
  `Set<ProviderId> providerIds()`. El estado se deriva así:
  - `find` vacío → `MISSING_ADAPTER`, motivo fijo `no notification sender registered for this provider`;
  - `disabledReason()` presente → `DISABLED`, motivo = ese texto;
  - en otro caso → `ENABLED`, sin motivo.
- **Rationale**: el cruce no requiere acoplar `core` a Spring: el registro ya vive en `core/port/out` como
  clase pura y ya contiene todos los adaptadores; el estado ya existe en cada adaptador y es exactamente el
  que hace fallar el envío (`ProviderDisabledException`) o la resolución (`ProviderNotAvailableException`),
  así que lo mostrado coincide por construcción con lo que encuentra el despacho (FR-013). Los motivos
  actuales son textos fijos que nombran propiedad y variable de entorno, nunca su valor (FR-009): se
  revisaron los tres generadores, incluidas las ramas de credencial FCM mal formada.
- **Método abstracto, no `default`**: un `default Optional.empty()` haría que un adaptador futuro que
  olvide declararlo aparezca como habilitado estando deshabilitado — justo el error que la historia quiere
  hacer visible. Abstracto obliga a decidirlo al compilar. Costo: dos dobles de prueba existentes
  (`NotificationSenderRegistryTest.FakeSender`, `ProviderRoutingE2ETest.RecordingNotificationSender`)
  implementan el método; los `Mockito.mock(NotificationSenderPort.class)` devuelven `Optional.empty()` sin
  cambios.
- **Alternativas descartadas**: diferir a otra historia (vacía la parte "y su estado"); un puerto nuevo
  `ProviderStatusPort` implementado en `infrastructure` recorriendo los beans (duplica lo que el registro ya
  sabe y abre la posibilidad de que ambos discrepen); booleano `enabled` + `missingCredentials` como la
  respuesta de registro del contrato (no representa credenciales mal formadas ni ambiguas, ni el caso sin
  adaptador).

## Decisión 3 — La consulta de proveedores es la unión de adaptadores y catálogo (Q3)

- **Decisión**: `registry.providerIds()` ∪ proveedores nombrados por alguna ruta. Cada proveedor lleva los
  canales que lo nombran con su posición (1-based); si el mismo proveedor aparece dos veces en un canal,
  aparecen las dos posiciones.
- **Rationale y alternativas**: ver spec, Q3.

## Decisión 4 — Se lee la vista que usa el enrutamiento (Q4)

- **Decisión**: `ChannelCatalogPort` gana `Flux<ChannelRoute> findAllRoutes()`.
  `MongoChannelCatalogAdapter` lo implementa sobre `ChannelCatalogCache.snapshot()`, la misma vista que usa
  `findActiveRoute`. Cada ruta se devuelve con el identificador de canal igual a la **clave** del snapshot
  (ya en mayúsculas), no con el `channelType` original del documento, que puede venir en minúsculas: así
  el identificador mostrado es el que resuelve el enrutamiento.
- **Rationale**: es la única forma de que "lo que ves es cómo se enruta" (FR-007) sea cierto. Heredado del
  refresco existente: un documento sin proveedores no llega al snapshot y no aparece; la base caída
  conserva la última vista (FR-011); ninguna lectura de la consulta toca MongoDB.
- **Alternativas descartadas**: consultar `channel_catalog` con `ReactiveMongoTemplate` (muestra rutas que
  el despacho no aplica); forzar un refresco en cada consulta (convierte una lectura en una escritura de la
  caché compartida y en carga sobre la base, y contradice FR-010).

## Decisión 5 — Un caso de uso con dos operaciones, en `core`

- **Decisión**: `QueryChannelCatalogUseCase` (`port/in`) con `Mono<List<ChannelView>> listChannels()` y
  `Mono<List<ProviderView>> listProviders()`, implementado por `QueryChannelCatalogService` (`usecase`) con
  `ChannelCatalogPort` y `NotificationSenderRegistry`. Vistas como records en `port/in`: `ChannelView`,
  `ChannelProviderView`, `ProviderView`, `ProviderChannelView`, y el enum `ProviderStatus`
  (`ENABLED`, `DISABLED`, `MISSING_ADAPTER`) junto a ellas, como `LiveUpdateAction`.
- **Rationale**: la derivación del estado y la unión son lógica de aplicación y deben probarse sin Spring
  (Principio I). Las dos vistas comparten la misma derivación de estado; separarlas en dos servicios
  duplicaría esa lógica. `Mono<List<...>>` porque el orden es parte del resultado (FR-006) y el
  controlador responde un objeto `{ items }`, no un flujo.
- **Ordenamiento**: canales por `channelType.value()`; proveedores por `providerId.value()`; proveedores
  de un canal por posición; canales de un proveedor por identificador de canal y luego por posición. Todo
  con `Comparator` natural de `String` (orden estable e independiente de la configuración regional).
- **Forma de contenido**: `null` si es `null` o en blanco (spec, Edge Cases); si no, el texto tal cual.
- **Alternativas descartadas**: dos casos de uso y dos servicios (duplicación); calcular la unión en el
  controlador (lógica de aplicación en un adaptador de entrada).

## Decisión 6 — Rutas, controlador y forma de respuesta

- **Decisión**: `GET /channels` y `GET /providers` (REST estándar: son lecturas de colección, Principio II),
  en un controlador nuevo `ChannelCatalogController` en `adapter/in/rest`, separado de
  `NotificationController` (`@RequestMapping("/notifications")`). DTOs como records en el mismo paquete.
  Respuesta envuelta en `{ "items": [...] }`, igual que `NotificationSearchResponse`, para poder añadir
  metadatos sin romper al panel. Forma exacta en `contracts/api-notificaciones-cambios.md`.
- **Campos nulos**: `contentSchema` y `statusReason` se serializan como `null` explícito, igual que
  `providerId` en `NotificationStatusResponse`.
- **Alternativas descartadas**: arreglo desnudo (no extensible); `GET /catalog` único (el contrato ya
  organiza el catálogo en `/channels` y `/providers`); `GET /channels/{id}` y `GET /providers/{id}` (fuera
  de alcance).

## Decisión 7 — Sin conmutación implícita en la respuesta

- **Decisión**: la respuesta no marca un proveedor como "en uso" ni calcula si el canal "puede enviar". El
  contrato documenta que hoy el despacho usa solo la posición 1 (`DispatchNotificationService.attemptSend`
  resuelve `route.preferredProvider()` y nada más).
- **Rationale**: un indicador derivado a nivel canal sería un estado de canal que el dominio no tiene
  (spec, Out of Scope); el panel lo deriva de `providers[0].status`.

## Decisión 8 — Estrategia de prueba

- **Unitarias `core`**: `QueryChannelCatalogServiceTest` con `StepVerifier` (tres estados, unión, orden,
  duplicados, esquema en blanco, catálogo vacío con adaptadores); `NotificationSenderRegistryTest`
  ampliado (`find`, `providerIds`).
- **Adaptadores**: `MongoChannelCatalogAdapterTest` (`findAllRoutes` con clave en mayúsculas y snapshot
  vacío); pruebas de `disabledReason()` en las cuatro clases de prueba de proveedor existentes.
- **Controlador**: `ChannelCatalogControllerTest` con `@WebFluxTest` (mapeo de campos, nulos explícitos,
  `400` sin cabecera y con cabecera vacía).
- **E2E**: `ChannelCatalogQueryE2ETest` (`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` con MongoDB y
  RabbitMQ + `WebTestClient`), refresco del catálogo cada 1000 ms. Cubre SC-001 (la posición 1 de EMAIL
  es `simulated` `ENABLED` y una notificación EMAIL aceptada por HTTP termina `DELIVERED` con ese
  `providerId`), SC-002 (para cada proveedor listado, el bean `NotificationSenderRegistry` del contexto:
  `ENABLED` → `send` emite un resultado; `DISABLED` → `send` falla con `ProviderDisabledException`;
  `MISSING_ADAPTER` → `resolve` lanza `ProviderNotAvailableException`), SC-003 (dos tenants, cuerpos
  idénticos byte a byte y sin el identificador de tenant), SC-004 (credencial parcial reconocible
  configurada; el valor no aparece, el nombre de la variable ausente sí — control positivo), SC-005
  (documento modificado en MongoDB; aserción explícita `Duration` ≤ 5 s hasta verlo en `GET /channels`, y
  en ese instante `findActiveRoute` ya devuelve la ruta nueva), FR-010 (documentos de `channel_catalog`
  idénticos antes y después de las consultas) y el `400` sin cabecera.
- **Aislamiento del estado del catálogo entre pruebas**: cada prueba que modifica `channel_catalog` lo
  restaura a los tres documentos por defecto en `@BeforeEach` y espera a que `GET /channels` los refleje
  antes de empezar, en lugar de depender del orden de ejecución.
- **Trampas de HU2-072 revisadas**: no hay consumidores nuevos ni pruebas de "no llega nada"; el
  aislamiento por tenant aquí es "respuestas idénticas", y se comprueba el cuerpo completo, no un campo.

## Decisión 9 — Pendientes y cambios ajenos

- Sin excepción a ack manual/DLQ: no se toca RabbitMQ.
- Riesgos con dueño y fecha en spec.md § Risks (exposición hasta DEP-01, estado de configuración vs. salud,
  réplicas con configuración distinta).
- `Recipient.java`, `application.yml`, `.claude/`, `docs/` y `qodana.yaml` tienen cambios sin commitear
  ajenos a esta historia: no entran en ningún commit. Esta historia no necesita tocar `application.yml`.
