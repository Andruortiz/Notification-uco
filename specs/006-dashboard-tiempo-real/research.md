# Phase 0 Research: Ver notificaciones en tiempo real en el dashboard

## Decisión 1 — Transporte: Server-Sent Events (SSE) sobre WebFlux, no WebSocket

**Decision**: `GET /notifications:subscribe` devuelve `Flux<ServerSentEvent<NotificationLiveUpdateResponse>>`
(`Content-Type: text/event-stream`), una conexión HTTP de larga duración por operador/pestaña.

**Rationale**:
- El canal es estrictamente unidireccional (servidor → dashboard) — nada que el operador necesite
  enviar de vuelta sobre esta misma conexión. SSE es exactamente ese caso de uso; WebSocket resuelve
  un problema más general (bidireccional) que esta historia no tiene.
- Spring WebFlux soporta SSE de forma nativa (`Flux<ServerSentEvent<T>>`) sin dependencias nuevas —
  el proyecto ya es 100 % reactivo (Restricciones técnicas de la constitución).
- Un navegador reconecta un `EventSource` (cliente SSE nativo) automáticamente cuando la conexión se
  cae, sin código adicional en el frontend — esto es precisamente el disparador que hace que FR-006
  (resincronización tras reconexión) se resuelva solo (ver Decisión 6).
- CORS ya permite `GET` desde el origen del dashboard (`CorsConfig.java`) — no requiere cambios.

**Alternatives considered**:
- **WebSocket**: descartado — requiere un modelo de conexión distinto (handshake `Upgrade`, manejo de
  frames bidireccional) para un canal que solo necesita enviar en una dirección; más superficie para
  un beneficio que esta historia no pide.
- **Long polling**: descartado — mayor latencia percibida (SC-001 exige ≤5 s) y mayor carga (una
  solicitud HTTP nueva por ciclo) que una conexión persistente.

## Decisión 2 — Difusión entre réplicas: reutilizar el `FanoutExchange` ya existente

**Decision**: Cada réplica declara, al arrancar, una cola anónima/exclusiva/auto-eliminable
(`AnonymousQueue` de Spring AMQP) vinculada al `FanoutExchange` `notification.events.exchange` —
exchange que **ya existe** en `RabbitConfig.java` y que **ya recibe** cada `DomainEvent` publicado
por `NotificationRabbitPublisher.publish(...)`, pero que hoy no tiene ningún consumidor. Cada evento
recibido se reenvía a un `Sinks.Many<NotificationId>` local, compartido por todas las conexiones SSE
activas de esa réplica.

**Rationale**:
- Es la única pieza de este diseño que necesitaba resolver un problema real de multi-réplica: un
  operador conectado a la réplica A debe enterarse de un cambio persistido por la réplica B. Un
  fanout es exactamente el patrón AMQP para "entregar una copia de cada mensaje a cada consumidor
  independiente", que es lo que cada réplica necesita ser.
- Cero infraestructura nueva: la exchange ya está declarada y ya es el destino de cada evento de
  dominio publicado hoy (aunque nadie la consuma) — encender el primer consumidor no requiere tocar
  `docker-compose.yml`, agregar Redis, ni convertir Mongo en un replica set.
- Una cola anónima por réplica (no una cola compartida con nombre fijo) es intencional: si todas las
  réplicas compitieran por una única cola con nombre, cada evento llegaría a una sola réplica (patrón
  work-queue, como ya ocurre con `notification.dispatch.queue`) — exactamente lo que **no** queremos
  aquí, porque el operador conectado a cualquier réplica necesita ver **todos** los eventos de su
  tenant, no una porción arbitraria repartida entre réplicas.

**Alternatives considered**:
- **Redis Pub/Sub**: descartado — logra la misma semántica de difusión, pero introduce un componente
  de infraestructura nuevo que hoy no existe en `docker-compose.yml` ni en el stack (Redis solo
  aparece en la constitución como un pendiente diferido para el limitador de tasa por proveedor, Fase
  5, sin construir todavía). No se justifica agregarlo cuando la exchange ya declarada resuelve lo
  mismo con cero piezas nuevas.
- **MongoDB Change Streams**: descartado para esta historia — requiere que Mongo corra como replica
  set (aunque sea de un solo nodo); `docker-compose.yml` hoy levanta `mongo:7` sin `--replSet` ni
  `rs.initiate()`. Habilitarlo es un cambio de infraestructura de despliegue ortogonal a esta
  historia. Además, ya era necesario tocar el modelo de dominio para cerrar la brecha de eventos
  (Decisión 3) — reutilizar esa misma vía es más simple que agregar un mecanismo de observación
  paralelo sobre la colección.

## Decisión 3 — Cerrar la brecha de eventos de dominio: 3 transiciones que hoy no emiten nada

**Decision**: Agregar `NotificationRecoverable`, `NotificationRequeued` y `NotificationDiscarded` al
`sealed interface DomainEvent` (mismo shape que los 4 ya existentes: `notificationId` + `occurredOn`,
sin campos nuevos), y registrar el evento correspondiente dentro de `Notification.markRecoverable()`,
`.requeue()` y `.discard()` — los tres métodos de transición que hoy mutan `status` pero no llaman a
`eventRecorder.registerEvent(...)`.

**Rationale**:
- `spec.md` (FR-001) exige que el feed cubra las 6 transiciones del ciclo de vida, no solo las 4 que
  ya emiten eventos hoy. Sin este cierre, un operador nunca vería en vivo que una notificación pasó a
  `RECOVERABLE`, fue reencolada, o terminó `DISCARDED` — el feed estaría incompleto por una brecha
  preexistente del modelo, no por una limitación del mecanismo de transporte.
- Es un cambio aditivo y de bajo riesgo: `pullEvents()`/`publish(events)` ya se invocan genéricamente
  en los tres flujos de uso afectados (`DispatchNotificationService.saveAndPublish`,
  `RequeuePendingNotificationsService.requeueAndPublish`) — hoy simplemente publican una lista vacía
  para esas transiciones. Ningún caso de uso necesita cambiar su lógica de publicación; solo el
  agregado `Notification` necesita registrar el evento que ya le corresponde por su propio contrato
  (los métodos ya reciben `AttemptOrigin`/`ProviderId` y ya mutan `status`, exactamente como los
  cuatro métodos hermanos que sí registran evento).
- Ningún test existente depende de que estas transiciones NO emitan evento (`NotificationTest` solo
  verifica el `status` resultante) — confirmado por lectura directa de los tests actuales.
- `discard()` no tiene hoy ningún llamador en código de producción (el mecanismo de DLQ de HU2-040
  republica el mensaje a nivel de RabbitMQ pero nunca marca el agregado como `DISCARDED` en Mongo) —
  es una brecha preexistente y distinta, fuera de alcance de esta historia (ver nota al final). Se
  agrega igualmente el registro del evento en `discard()` porque el método ya existe con ese
  contrato — si en el futuro algo lo invoca, el evento se publica sin cambios adicionales; no hacerlo
  dejaría una inconsistencia silenciosa entre las transiciones que sí notifican y las que no.

**Alternatives considered**:
- **No agregar eventos nuevos, y en su lugar disparar el feed únicamente vía un Mongo Change Stream**
  sobre el campo `status`: se descartó por la misma razón de la Decisión 2 (requiere replica set) y
  porque no evita tener que decidir qué hacer con `discard()`/`requeue()` de todas formas — sin un
  evento propio, el log tampoco distingue "reencolado manualmente" de "reencolado automático" ni
  registra cuándo se alcanzó `DISCARDED`, la misma pérdida de trazabilidad (Principio IX) que motivó
  cerrar la brecha en primer lugar.

## Decisión 4 — Contenido de cada actualización en vivo: rehidratar desde Mongo, no enriquecer el evento

**Decision**: El evento de RabbitMQ sigue siendo mínimo (`notificationId` + `occurredOn`, igual que
hoy). Al recibirlo, `SubscribeToNotificationUpdatesService` llama a `NotificationRepository.findById`
(puerto de salida ya existente, ya usado por `GetNotificationStatusUseCase`) para obtener el estado
persistido más reciente, y lo proyecta a `NotificationSearchResult` — el mismo tipo que ya devuelve la
búsqueda (HU2-025), con el historial completo de intentos.

**Rationale**:
- Evita modificar los 4 eventos de dominio ya existentes (y sus pruebas) solo para transportar campos
  que la búsqueda ya sabe calcular a partir del id — `tenantId`, `recipientId`, `channelType`,
  `status` e historial completo ya existen en `Notification`/`NotificationDocument`.
- Al mostrar siempre el estado **persistido más reciente** en vez de un valor capturado en el momento
  del evento, el dashboard nunca puede mostrar un estado obsoleto por una carrera entre dos eventos
  cercanos en el tiempo — siempre refleja la verdad actual de la base de datos.
- Reutiliza exactamente la forma (`NotificationSearchResult`) que ya expone la búsqueda manual — el
  dashboard renderiza el mismo shape de fila sin importar si vino de una búsqueda o de una
  actualización en vivo.

**Alternatives considered**:
- **Enriquecer cada `DomainEvent` con tenantId + snapshot completo en el momento de la transición**:
  se descartó porque exige modificar los 4 eventos ya existentes (y sus pruebas), y porque el estado
  `IN_PROCESS` de todas formas nunca se persiste de forma independiente hoy (ver Technical Context en
  `plan.md`) — enriquecer el evento no lo haría observable como una fila separada de todas formas, ya
  que el propio flujo de despacho lo sobrescribe antes de guardar. La rehidratación desde Mongo es más
  simple y da exactamente la misma garantía de completitud (historial completo de intentos) con menos
  cambios.

## Decisión 5 — Filtrado en vivo: una sola definición de "coincide con los filtros", dos ejecuciones

**Decision**: Se agrega un método `matches(Notification)` a `NotificationSearchCriteria` (el mismo
tipo que ya encapsula `recipientId`/`channelType`/`status`/`from`/`to`/`tenantId` para la búsqueda),
evaluado en memoria por `SubscribeToNotificationUpdatesService` sobre cada notificación rehidratada.
La búsqueda paginada (HU2-025) sigue traduciendo esos mismos campos a una consulta dinámica de Mongo
en `NotificationMongoAdapter`; la suscripción en vivo evalúa los mismos campos en memoria contra un
único documento ya cargado.

**Rationale**:
- El operador espera que "lo que ve en una búsqueda" y "lo que se actualiza en vivo cuando esos
  mismos filtros están activos" signifiquen exactamente lo mismo (FR-003) — centralizar la definición
  del filtro en un solo tipo (`NotificationSearchCriteria`) evita que las dos ejecuciones (consulta
  Mongo vs. predicado en memoria) diverjan silenciosamente con el tiempo.
- Es la base de FR-004 (entrar/salir de la vista): cuando `matches(...)` cambia de `true` a `false`
  entre el estado anterior y el nuevo para una notificación visible, el caso de uso emite
  `LiveUpdateAction.REMOVE`; cuando cambia de `false` a `true`, emite `UPSERT`.

**Alternatives considered**:
- **Duplicar la lógica de filtrado directamente dentro de `SubscribeToNotificationUpdatesService`**:
  descartado — es exactamente el tipo de duplicación de una regla de negocio (qué cuenta como "cumple
  los filtros") que se vuelve inconsistente si alguien cambia una sola de las dos copias.

## Decisión 6 — Resincronización tras reconexión (FR-006): ninguna, porque no hace falta

**Decision**: Cada solicitud a `GET /notifications:subscribe` — sea la primera conexión del operador o
una reconexión tras una caída — ejecuta primero la misma búsqueda no paginada (reutilizando
`NotificationRepository.search`, acotada al mismo tope de 200 resultados que ya usa HU2-025) para
emitir la foto vigente completa como una serie de eventos `UPSERT`, y solo después continúa con el
`Flux` de actualizaciones en vivo.

**Rationale**:
- Un `EventSource` de navegador reconecta automáticamente emitiendo una solicitud GET nueva — para el
  servidor, una reconexión **es** indistinguible de una conexión nueva. Si cada conexión nueva siempre
  arranca reproduciendo el estado vigente, la resincronización pedida por FR-006 queda resuelta sin
  necesidad de un log de eventos, un cursor de reanudación, ni ningún estado de sesión del lado del
  servidor — exactamente la lectura más simple de "resincronización automática" que se confirmó con el
  usuario en la clarificación del spec.
- No persiste ningún estado por conexión — coherente con que el servicio pueda reiniciarse o
  escalar/desescalar réplicas sin lógica especial de recuperación de sesión.

**Alternatives considered**:
- **Cursor/log de eventos con reanudación exacta** (ej. `Last-Event-ID` de SSE + un almacén de eventos
  ordenados): descartado explícitamente en la clarificación del spec ("no un registro de eventos que
  permita reproducir cada cambio... en orden exacto") — más infraestructura para una garantía que la
  resincronización completa ya cubre igual de bien para este caso de uso.

## Decisión 7 — Conexiones inactivas y keep-alive (FR-008)

**Decision**: El `Flux<ServerSentEvent<...>>` intercala un comentario SSE de keep-alive cada 15
segundos (`Flux.interval` combinado con el flujo de actualizaciones) para: (a) evitar que un proxy
intermedio cierre una conexión inactiva por timeout, y (b) permitir que Reactor detecte y limpie una
conexión cuyo cliente ya se desconectó (cancelación de la suscripción del lado del servidor), sin
esperar un timeout largo del contenedor HTTP.

**Rationale**: Es el patrón estándar para conexiones SSE de larga duración; sin esto, un operador con
el dashboard abierto pero sin actividad de notificaciones podría ver su conexión cerrada
silenciosamente por un proxy/balanceador con un timeout de inactividad más corto que la duración real
de la sesión.

**Alternatives considered**: Ninguno — es una práctica estándar sin trade-off relevante para este
alcance.

## Nota fuera de alcance: `discard()` nunca se invoca en código de producción

Durante la investigación se confirmó que `Notification.discard()` (transición a `DISCARDED`) no tiene
ningún llamador fuera de las pruebas unitarias — el mecanismo de DLQ (HU2-040) republica el mensaje a
nivel de RabbitMQ pero nunca marca el agregado correspondiente como `DISCARDED` en MongoDB. Esta
historia cierra la brecha de **eventos** (Decisión 3) para que, si el estado se alcanza, se notifique
en vivo — pero no resuelve el hecho de que hoy nada hace que se alcance. Es una inconsistencia
preexistente entre el catálogo de estados documentado (`NotificationStatus.DISCARDED`,
`api-notificaciones.yaml`) y el flujo real de DLQ, no introducida por esta historia y fuera de su
alcance corregir aquí.
