# Feature Specification: Bandeja in-app del destinatario (backend) (HU2-131)

**Feature Branch**: `feature/HU2-131-bandeja-in-app`

**Created**: 2026-10-25

**Status**: Draft (pendiente de aprobacion de spec y plan)

**Input**: "Como destinatario quiero una campana con mis notificaciones, contador y marca de leido para no perder mensajes."
Criterios: actualizacion en tiempo real con SSE; componente embebible en cualquier aplicacion del host.

## Contexto

Hoy `GET /notifications/stream` (spec 006) es un panel de operadores: lista las notificaciones del tenant con filtros. No sirve
como bandeja: no esta acotado a una persona, no tiene estado de lectura y expone metadatos de entrega. Esta historia agrega el
**backend** de la bandeja: canal `IN_APP` del catalogo con proveedor interno, almacenamiento por tenant y destinatario,
listado, contador, marca de leido, borrado, SSE por destinatario, retencion y borrado de datos personales. El componente de
campana y el contrato host-MFE viven en `Front-Notification-mfe` (C1b, "Veremos") y quedan como limite (ver Excepciones).

Dependencias leidas: B1 (spec 029: identidad solo desde el token/sesion; `AuthenticatedPrincipal` con subject y tenant) y E5
(spec 036: `RecipientIdentity(recipientIdentityId, tenantId, subject?)`, 404 uniforme, cifrado, retencion). F4 (digest) depende de
esta historia: ver FR-014.

## Clarifications

### Session 2026-10-25 (valores por defecto asumidos; el usuario los confirma al aprobar)

- Q1 - **Canal**: `IN_APP` es un canal del catalogo (decision de producto 08-decisiones, E6) con un proveedor interno `in-app`
  sin credenciales ni red. Afecta FR-001.
- Q2 - **"Leido" es por usuario**: el estado vive en el item de la bandeja de cada destinatario, no en la `Notification`. Una
  notificacion enviada a N destinatarios son N items. Afecta FR-003.
- Q3 - **Quien es el destinatario**: el `recipientId` de la notificacion IN_APP es el `subject` del usuario; el proveedor lo
  resuelve a la `RecipientIdentity` de E5 (la crea si no existe). Las operaciones del usuario resuelven la identidad desde el
  `subject` de la sesion; nunca se recibe identidad ni tenant del cliente. **Pregunta abierta Q-A** (ver abajo). Afecta FR-002.
- Q4 - **Tiempo real**: SSE propio `GET /inbox-stream`, distinto del panel de operadores; sin parametros de filtro ni de
  destinatario. Reconectar emite una foto del contador y el cliente recarga la lista; sin `Last-Event-ID`. Afecta FR-008.
- Q5 - **Retencion**: 90 dias desde la creacion del item, leido o no, configurable (`notification.inbox.retention-days`). El
  usuario puede borrar sus items; un administrador borra por titular. Afecta FR-011, FR-012.
- Q6 - **Contenido**: se guarda una copia de `subject` y `body` de la notificacion (maximo 200 y 4000 caracteres; excedido es
  fallo permanente del intento, sin truncar). El backend no interpreta HTML; el MFE debe pintarlo como texto. Afecta FR-001.

## User Scenarios & Testing

### User Story 1 - Recibir en la bandeja (Priority: P1)

**Independent Test**: enviar `POST /notifications` con canal `IN_APP` al `subject` de un usuario y listar con su sesion.

1. **Given** el canal IN_APP activo, **When** una aplicacion envia una notificacion a un `subject`, **Then** la notificacion
   llega a un estado terminal exitoso y el usuario ve un item sin leer en `GET /inbox-items`.
2. **Given** una redelivery del mismo mensaje, **When** el proveedor se ejecuta dos veces, **Then** existe un solo item.
3. **Given** el mismo `subject` en otro tenant, **When** se envia en un tenant, **Then** el otro tenant no ve nada.
4. **Given** un cuerpo mayor de 4000 caracteres, **When** se despacha, **Then** el intento es fallo permanente y no hay item.

### User Story 2 - Campana: listado y contador (Priority: P1)

1. **Given** items leidos y sin leer, **When** `GET /inbox-items` con `status` `UNREAD` o `ALL`, **Then** devuelve solo los del
   usuario de la sesion, mas recientes primero, con paginacion por cursor (maximo 50 por pagina).
2. **Given** el mismo usuario, **When** `GET /inbox-unread-counts`, **Then** devuelve el numero exacto de items sin leer.
3. **Given** dos usuarios del mismo tenant, **When** cada uno lista, **Then** ninguno ve ni cuenta items del otro.

### User Story 3 - Marcar como leido y borrar (Priority: P1)

1. **Given** un item propio sin leer, **When** `POST /inbox-reads` con `itemId`, **Then** 204, `readAt` queda fijado y el
   contador baja en uno; repetirlo responde 204 sin cambiar `readAt`.
2. **Given** un `itemId` de otro usuario, de otro tenant o inexistente, **When** se marca o se borra, **Then** la misma 404 en
   los tres casos y el item ajeno queda intacto.
3. **Given** items sin leer, **When** `POST /inbox-read-alls`, **Then** 204 y el contador es 0.
4. **Given** un item propio, **When** `POST /inbox-removals` con `itemId`, **Then** 204 y desaparece fisicamente.

### User Story 4 - Tiempo real por usuario (Priority: P1)

1. **Given** un usuario con el stream abierto, **When** llega una notificacion IN_APP para el, **Then** recibe un evento
   `CREATED` con el item y el contador nuevo.
2. **Given** el mismo usuario con dos pestanas, **When** marca leido en una, **Then** la otra recibe `READ` con el contador.
3. **Given** dos usuarios conectados (mismo tenant y tambien en tenants distintos), **When** llega un item para uno, **Then**
   el otro no recibe ningun evento (con control positivo: el destinatario si lo recibe).
4. **Given** una conexion nueva, **Then** el primer evento es `SNAPSHOT` con el contador vigente.
5. **Given** credenciales invalidas, **Then** 401; el stream solo se abre con Bearer o la cookie de sesion (spec 025/HU2-111).

### User Story 5 - Retencion y borrado de datos personales (Priority: P2)

1. **Given** un item con mas de 90 dias, **When** vence, **Then** el sistema lo elimina solo.
2. **Given** una solicitud del titular, **When** un ADMINISTRADOR invoca `POST /inbox-erasures` con el `subject`, **Then** 204,
   se eliminan todos sus items del tenant y no queda contenido recuperable; 204 tambien si no tenia.
3. **Given** el mismo caso, **When** lo invoca CLIENTE u OPERADOR, **Then** 403.

### Edge Cases

- Usuario sin identidad previa que consulta: listado vacio y contador 0, sin crear identidad (solo el envio la crea).
- Marca de leido concurrente de dos pestanas: idempotente, un solo `readAt`.
- Broker caido al publicar el evento en vivo: el item ya esta persistido; el fallo del evento se registra y no cambia el
  resultado del intento (el usuario lo ve al recargar).
- Cliente SSE lento: se descarta por contrapresion (best effort); la lista de la API es la fuente de verdad.
- `status` desconocido o `limit` fuera de rango (1..50): 400.

## Requirements

- **FR-001**: Existe el canal `IN_APP` y el proveedor `in-app` (`NotificationSenderPort`) que persiste un item y devuelve exito;
  sin llamadas externas ni credenciales. Persistir el item es la entrega; es idempotente por `(tenantId, notificationId)`.
- **FR-002**: El item pertenece a `(tenantId, recipientIdentityId)`; la identidad la resuelve el proveedor desde el `recipientId`
  (Q3) mediante un puerto de resolucion de E5.
- **FR-003**: Item = `itemId`, `notificationId`, `subject`, `body`, `createdAt`, `readAt?`, `expiresAt`. Estado leido por usuario.
- **FR-004**: `GET /inbox-items` (parametros `status`, `limit`, `cursor`), `GET /inbox-unread-counts`, `POST /inbox-reads`
  (`itemId`), `POST /inbox-read-alls`, `POST /inbox-removals` (`itemId`), `POST /inbox-erasures` (`subject`, solo
  ADMINISTRADOR), `GET /inbox-stream` (SSE). Rutas literales; ids en query o cuerpo.
- **FR-005**: Tenant e identidad salen siempre de la sesion o token validados; ningun parametro, encabezado ni cuerpo los fija.
- **FR-006**: Todo acceso filtra por `(tenantId, recipientIdentityId)` en la propia consulta; recurso ajeno, de otro tenant e
  inexistente responden igual (404).
- **FR-007**: Marcar leido y borrar son actualizaciones atomicas condicionadas por `(tenantId, identityId, itemId)`.
- **FR-008**: `GET /inbox-stream` entrega solo eventos de la identidad de la sesion. La suscripcion se indexa por
  `(tenantId, subject)` de la sesion; los mensajes de difusion entre replicas llevan solo ids y `subject` (sin contenido) y cada
  replica lee el item con el filtro `(tenantId, identidad)` del suscriptor. Eventos: `SNAPSHOT`, `CREATED`, `READ`, `REMOVED`, todos con `unreadCount`.
- **FR-009**: Difusion entre replicas por el broker ya existente (patron de `NotificationUpdatesRabbitAdapter`); sink de larga
  vida `directBestEffort`; el consumidor no persiste, asi que no requiere ack manual ni DLQ (justificado en plan).
- **FR-010**: Un fallo al publicar el evento en vivo nunca cambia el resultado del intento ni pierde el item (Principio VIII).
- **FR-011**: Retencion 90 dias por indice TTL sobre `expiresAt`; borrado fisico por vencimiento, por el usuario y por titular.
- **FR-012**: Base legal, retencion y borrado documentados en "Datos personales"; el contenido nunca aparece en logs, metricas
  ni mensajes del broker.
- **FR-013**: Metricas `inbox.items.created`, `inbox.items.read`, `inbox.stream.connections` (sin etiquetas de usuario) y logs con
  `tenantId`, `notificationId` e `itemId`.
- **FR-014**: Puerto estable para F4: un digest se emite como una notificacion normal por el canal IN_APP; los tipos
  compartidos son `InboxEntry` y `InboxPort` (ver plan). F4 no necesita otra API.
- **FR-015**: Cambios de contrato listados en `plan.md`; esta historia no edita `api-notificaciones.yaml`.

## Datos personales

- **Dato tratado**: contenido de los mensajes dirigidos a una persona y su `subject`.
- **Base legal (supuesto a confirmar por el responsable de datos)**: ejecucion del servicio que el titular utiliza en la
  aplicacion que lo notifica, Ley 1581 de 2012; finalidad unica: mostrar al titular sus avisos.
- **Retencion**: 90 dias desde la creacion; el usuario puede borrar antes; sin copia en otras colecciones.
- **Borrado**: fisico; por usuario (`/inbox-removals`), por vencimiento (TTL) y por titular (`/inbox-erasures`). La auditoria
  guarda solo un resumen sin contenido.
- **Acceso**: solo el propio usuario; el administrador borra pero no lee bandejas ajenas.

## Success Criteria

- **SC-001**: Con el stream abierto, el evento `CREATED` llega en menos de 5 s desde `POST /notifications` (asercion de `Duration`).
- **SC-002**: Con dos tenants con el mismo `subject` y dos usuarios del mismo tenant, ninguna operacion (listar, contar, leer,
  borrar, SSE, erasure) de uno modifica ni revela datos del otro; control positivo en cada caso (prueba automatizada).
- **SC-003**: 20 marcas de leido concurrentes del mismo item dejan un solo `readAt` y el contador baja exactamente en uno.
- **SC-004**: Con redelivery forzada del despacho, queda exactamente un item por notificacion.
- **SC-005**: Con 50 ejecuciones secuenciales de listar, contar y marcar con 200 items, el p95 calculado en la prueba es
  menor de 300 ms.
- **SC-006**: Ningun log ni mensaje del broker contiene `subject` o `body` del item; la prueba tiene control positivo (la
  captura si registra el envio y el usuario si ve el contenido por la API).
- **SC-007**: El indice TTL existe con `expireAfter` igual a la retencion configurada y `POST /inbox-erasures` borra al instante.

## Excepciones y limites (Principio VII)

- **EX-1**: Componente de campana, montaje en el host y contrato host-MFE (tema, sesion, eventos): fuera de este repositorio,
  `Front-Notification-mfe` y C1b. Dueno: equipo del MFE. Fecha: 2026-12-15.
- **EX-2**: Perfil del usuario final en el PDP para las rutas `/inbox-*` (hoy CLIENTE son componentes consumidores) y
  configuracion CORS con credenciales hacia el origen del host. Dueno: Seguridad. Fecha: 2026-12-15.
- **EX-3**: Confirmacion de la base legal. Dueno: responsable de datos. Fecha: 2026-12-15.
- **EX-4**: Reanudacion de SSE con `Last-Event-ID` y limite de items por usuario: no se construyen; la lista de la API es la
  fuente de verdad. Dueno: backend notificaciones. Fecha: 2026-12-15.

## Preguntas abiertas

- **Q-A**: confirmar que el remitente identifica al destinatario IN_APP por `subject` (por defecto) y no por el id opaco de E5.
  Si se prefiere el id opaco, F6 debe resolver `subject` a identidad antes de enviar.

## Out of Scope

Componente visual y contrato host-MFE (EX-1), preferencias por categoria (F1), digest (F4), fan-out por persona (F6), push
(FCM) y edicion de `api-notificaciones.yaml` (lo hace Contrato).
