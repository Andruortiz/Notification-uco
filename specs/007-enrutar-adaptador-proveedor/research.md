# Research: Enrutar cada notificación al adaptador de su proveedor por providerId

**Feature**: `007-enrutar-adaptador-proveedor` | **Date**: 2026-09-21

## Punto de partida (código real, leído antes de decidir)

- `DispatchNotificationService` recibe **un** `NotificationSenderPort` por constructor y llama siempre
  a ese mismo bean (`attemptSend` → `notificationSenderPort.send(notification)`).
  `route.preferredProvider()` solo se pasa a `applyOutcome(...)` para anotar el `ProviderId` del
  intento: hoy el identificador registrado es el que declara el catálogo, no necesariamente el de
  quien envió. Con un solo adaptador coinciden por casualidad.
- `ChannelRoute` (record en `core/port/out`) ya expone `providers()` (lista ordenada) y
  `preferredProvider()` (el primero). No hace falta cambiarlo.
- `SimulatedNotificationProvider` (`infrastructure/adapter/out/provider`) es hoy el único
  `NotificationSenderPort`. No declara ningún identificador: su `providerId` efectivo es "el que diga
  el catálogo".
- El catálogo siembra `EMAIL → [simulated]` desde `application.yml`
  (`notification.catalog.channels.EMAIL.providers`), y `ChannelCatalogSeeder` solo siembra si la
  colección está vacía; `ChannelCatalogRefresher` recarga cada 30 s a `ChannelCatalogCache`.
- Ante un fallo **del proveedor**, hoy: `send(...)` devuelve `AttemptResult`
  (`ACCEPTED`/`RECOVERABLE_FAILURE`/`PERMANENT_FAILURE`), la notificación se marca, **se persiste** y
  se publican eventos; el `Mono` de `dispatch` completa y el consumidor hace ack.
- Ante **ruta inexistente**, hoy: `ChannelNotAvailableException` → el `Mono` falla →
  `NotificationDispatchListener.onMessage` relanza (usa `.block()`) →
  `StatefulRetryOperationsInterceptor` (3 intentos) → `RepublishMessageRecoverer` → DLQ con el header
  `x-exception-message`. El cuerpo del mensaje es el `notificationId` y el publicador real ya fija
  `messageId = notificationId` (requisito del reintento *stateful*). La notificación queda `PENDING`.

Esas dos formas de fallar, ya existentes y ya distintas entre sí, son la base de la distinción que
pide FR-006: **el fallo del proveedor persiste un intento; el fallo de despacho no toca la
notificación y deja rastro en la DLQ.**

## Decisión 1 — El registro es una clase de `core`, no un puerto nuevo

**Decisión**: `NotificationSenderRegistry`, clase final en
`core/src/main/java/.../core/port/out/`, construida con la colección de `NotificationSenderPort`
disponibles. Expone `resolve(ProviderId)`. `DispatchNotificationService` pasa a depender de ella en
lugar de un `NotificationSenderPort` suelto. En `infrastructure`, `UseCaseConfig` la construye con el
`List<NotificationSenderPort>` que Spring inyecta automáticamente con todos los beans de ese tipo.

**Rationale**:

- Las dos reglas de resolución (identificador desconocido, identificador duplicado) son reglas del
  núcleo con consecuencias de negocio; en `core` se prueban con dos adaptadores falsos y sin Spring,
  que es literalmente lo que pide el criterio de aceptación de la historia.
- Agregar un adaptador sigue siendo "una clase nueva con `@Component`": Spring la incorpora a la lista
  inyectada sin tocar `core` ni `UseCaseConfig` (ADR-0009, FR-007, SC-005).
- No necesita ser un puerto: un puerto abstrae *comunicación con el exterior*, y aquí no hay ningún
  sistema externo nuevo — es composición de puertos ya existentes.

**Alternativas consideradas**:

- *Puerto nuevo `NotificationSenderRegistryPort` implementado en `infrastructure`*: dejaría la lógica
  de fallo (lo más delicado de la historia) fuera de `core` y sin prueba unitaria en `core`; además
  un puerto cuyo método devuelve otro puerto es indirección sin valor.
- *Inyectar `Map<ProviderId, NotificationSenderPort>` directamente en `DispatchNotificationService`*:
  no hay dónde detectar identificadores duplicados (un `Map` ya los colapsó) y cada consumidor futuro
  del mapa tendría que repetir el tratamiento del caso desconocido.

## Decisión 2 — Cada adaptador declara su `providerId` en el propio puerto

**Decisión**: agregar `ProviderId providerId()` a `NotificationSenderPort`.
`SimulatedNotificationProvider` devuelve `ProviderId.of("simulated")`.

**Rationale**: el compilador obliga a que todo adaptador nuevo declare su identidad; no hay forma de
registrar un adaptador anónimo. El valor `simulated` es exactamente el que ya siembra
`application.yml` para `EMAIL`, así que el comportamiento observable no cambia en un entorno limpio.

**Alternativas consideradas**:

- *Nombre del bean de Spring como identificador*: acopla un dato de negocio (el proveedor que el
  catálogo declara) a un detalle del contenedor, y no lo verifica el compilador.
- *Anotación propia (`@Provider("simulated")`)*: obliga a leer metadatos en `infrastructure` y deja
  que un adaptador sin anotar compile.
- *Mapa `providerId → bean` en `application.yml`*: crea una segunda fuente de verdad junto al
  catálogo, que es justo lo que esta historia viene a eliminar.

**Costo conocido**: `NotificationSenderPort` gana un método, así que todo doble de prueba debe
declararlo (ver Decisión 6).

## Decisión 3 — `providerId` sin adaptador: excepción de despacho, no intento fallido

**Decisión**: `NotificationSenderRegistry.resolve(...)` lanza
`ProviderNotAvailableException(ProviderId)` (nueva, en `core/exception`, `RuntimeException` como las
demás). `DispatchNotificationService` resuelve el adaptador **antes** de `notification.markQueued()`,
de modo que ante el fallo no se registra intento, no se muta el estado y no se persiste nada. El
error viaja por el camino de fallo de despacho que ya existe: consumidor → reintentos → DLQ con
`x-exception-message` nombrando el `providerId` y cuerpo = `notificationId`.

**Rationale**:

- Cumple FR-004/FR-005/FR-006: el rastro queda (DLQ + causa), la notificación no se pierde ni se da
  por entregada, y es distinguible de un fallo del proveedor porque **no hay intento registrado** y
  porque el tipo de excepción es propio.
- Reutiliza infraestructura ya probada (HU2-040) en lugar de inventar un canal de error nuevo.
- Un error de configuración no debe quemar la notificación: al incorporar el adaptador faltante, la
  notificación pendiente se despacha normalmente.

**Alternativas consideradas**:

- *Marcar la notificación `FAILED` con un intento `PERMANENT_FAILURE`*: rechazada. `DeliveryAttempt`
  no tiene campo de motivo, así que en la base quedaría idéntica a un rechazo permanente del
  proveedor — justo la distinción que FR-006 exige. Además convierte un error de configuración
  corregible en un estado terminal.
- *Marcar `RECOVERABLE`*: rechazada. Consume el presupuesto de reintentos de la política de negocio
  por una causa que ningún reintento va a resolver, y contamina el historial con intentos que nunca
  ocurrieron.

**Limitación conocida, documentada y no oculta (Principio VII)**: la notificación queda `PENDING` sin
intentos, así que `RequeuePendingNotificationsService` la reencolará cada ciclo (por defecto 30 s,
umbral de huérfana 60 s) mientras el catálogo siga mal configurado, generando entradas repetidas en la
DLQ. Es el comportamiento que ya tiene hoy `ChannelNotAvailableException` en el mismo punto del flujo,
no una regresión de esta historia. Acotar el reencolado de pendientes que fallan siempre es un
problema del mecanismo de recuperación; queda como excepción explícita con dueño (el autor de la
historia de failover/HU2-048) y fecha de revisión (al planificar HU2-048).

## Decisión 4 — Identificadores duplicados: el arranque falla

**Decisión**: el constructor de `NotificationSenderRegistry` agrupa por `providerId` y usa
`Preconditions.requireTrue(...)` (→ `IllegalArgumentException`) nombrando el identificador duplicado.
Como el registro se construye en un `@Bean` de `UseCaseConfig`, el contexto de Spring no arranca.

**Rationale**: FR-009 y SC-007. Un registro ambiguo enviaría por un proveedor distinto al configurado
sin que nadie lo note; fallar al arrancar lo hace imposible. Detectarlo en el despacho retrasaría el
diagnóstico hasta que llegara tráfico de ese canal.

**Alternativa considerada**: *el último bean gana* (comportamiento natural de
`Collectors.toMap` sobrescribiendo) — rechazada por silenciosa.

## Decisión 5 — La resolución ocurre en cada despacho

**Decisión**: el `providerId` se resuelve en cada despacho a partir de la ruta que devuelve el
catálogo en ese momento; no se guarda en la notificación ni se cachea por canal.

**Rationale**: SC-002 — cambiar el preferente en el catálogo cambia el proveedor efectivo sin
desplegar ni reiniciar, dentro del intervalo de refresco ya existente. El costo es una búsqueda en un
`Map` inmutable, irrelevante.

## Decisión 6 — Impacto en las pruebas existentes

- `DispatchNotificationServiceTest` (core, 13 pruebas): el mock de `NotificationSenderPort` pasa a
  necesitar `when(sender.providerId()).thenReturn(ProviderId.of("brevo"))` porque el registro rechaza
  un adaptador sin identificador, y el servicio pasa a construirse con el registro en lugar del puerto
  suelto (afecta también a las 5 pruebas de `constructorRejectsNull...`). La ruta de prueba ya declara
  `brevo` como preferente, así que basta con que el doble declare ese mismo identificador para que
  todas las aserciones actuales (incluida la de `ProviderId.of("brevo")` en el intento) sigan siendo
  válidas — y ahora por la razón correcta.
- `SimulatedNotificationProviderTest` (infrastructure): agrega una aserción sobre `providerId()`.
- `NotificationControllerTest`, tests del catálogo y de la DLQ: sin cambios.
- `HexagonalArchitectureTest` y `ModularityTests`: sin cambios esperados; el registro vive en `core` y
  no importa nada de `infrastructure`.

## Decisión 7 — Cómo se prueba de punta a punta (Principio IV)

**Decisión**: una clase E2E nueva, `ProviderRoutingE2ETest`
(`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers` con Mongo y RabbitMQ, patrón de
`NotificationLiveUpdatesE2ETest`), con dos adaptadores falsos registrados por una
`@TestConfiguration` estática (`fake-a` y `fake-b`, cada uno registra lo que recibió) **conviviendo
con el `SimulatedNotificationProvider` real del contexto** (FR-008).

Escenarios:

1. Catálogo `EMAIL → [fake-a]` (vía propiedades `notification.catalog.channels.EMAIL.providers[0]` que
   el seeder escribe en el Mongo limpio del contenedor). `POST /notifications` → esperar →
   `GET /notifications/{id}` devuelve `DELIVERED` con `providerId = fake-a`; `fake-a` recibió
   exactamente una notificación y `fake-b` ninguna (SC-001, SC-003).
2. Canal `SMS → [fantasma]` insertado directamente en la colección del catálogo, sin adaptador que lo
   atienda. `POST /notifications` con `channelType=SMS` → el mensaje termina en la DLQ con cuerpo =
   `notificationId` y `x-exception-message` nombrando `fantasma`; `GET /notifications/{id}` sigue en
   `PENDING` y sin intentos (SC-004 y la distinción de FR-006).

**Detalle operativo**: el test fija `notification.catalog.refresh-interval-ms` a un valor bajo
(≈500 ms) para no esperar los 30 s del refresco por defecto, ya que el seeder escribe el catálogo de
forma asíncrona en el arranque (`ApplicationRunner` + `subscribe()`).

## Decisión 8 — Sin cambios en el contrato OpenAPI

**Decisión**: esta historia no agrega ni modifica ningún endpoint; el enrutamiento es interno al
despacho. `api-notificaciones.yaml` no se toca, y el Principio II no aplica por ausencia de endpoint
nuevo. Tampoco se agrega un handler REST para `ProviderNotAvailableException`: ningún camino HTTP
puede provocarla (el despacho entra por RabbitMQ), y añadir un mapeo inalcanzable sería código
especulativo.

## Riesgo de despliegue detectado (no es tarea de esta historia, pero hay que decirlo)

En un entorno cuyo catálogo en Mongo ya esté sembrado con un `providerId` que ningún adaptador
declara (por ejemplo uno sembrado a mano con `brevo`), el despacho pasará de "funcionaba con el
simulado" a fallar con `ProviderNotAvailableException`. Es el comportamiento correcto y deseado por la
historia — deja de mentir sobre quién envió —, pero conviene verificar el contenido de la colección
`channelCatalog` de cada entorno antes de desplegar.
