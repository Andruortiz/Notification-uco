# Feature Specification: Bandeja in-app del destinatario (backend) (HU2-131)

**Feature Branch**: `feature/HU2-131-bandeja-in-app`

**Created**: 2026-10-25

**Status**: Draft (pendiente de aprobacion de spec y plan)

**Input**: "Como destinatario quiero una campana con mis notificaciones, contador y marca de leido para no perder mensajes."
Criterios: actualizacion en tiempo real con SSE; componente embebible en cualquier aplicacion del host.

## Contexto

`GET /notifications/stream` (spec 006) es un panel de operadores del tenant: no esta acotado a una persona, no tiene estado de
lectura y expone metadatos de entrega. Esta historia agrega el **backend** de la bandeja: canal `IN_APP`, almacenamiento por
tenant y destinatario, listado, contador, marca de leido, borrado, SSE por destinatario y retencion. El componente de campana
vive en `Front-Notification-mfe` (C1b) y es el limite EX-1.

Dependencias: B1 (spec 029: identidad desde token o sesion), E5 (spec 036: `RecipientIdentity`, `RecipientIdentityPort`,
borrado comun) y B2 (spec 039: roles). F4 (digest) depende de esta historia (FR-014).

## Clarifications

### Session 2026-10-25 (valores por defecto asumidos; el usuario los confirma al aprobar)

- Q1 - **Canal**: `IN_APP` es un canal mas del catalogo (08-decisiones, E6) con proveedor `in-app` sin credenciales ni red. FR-001.
- Q2 - **"Leido" es por usuario**: el estado vive en el item de cada destinatario; N destinatarios son N items. FR-003.
- Q3 - **Identidad (regla unica del lote)**: el campo `recipientIdentityId` de E5 es el que usan F1, F6 y E6. E6 acepta `subject`
  como atajo documentado: lo resuelve con `findOrCreateBySubject` y rellena `recipientIdentityId` en la notificacion antes de la
  guarda de preferencias de F1. Las operaciones del usuario resuelven la identidad desde el `subject` de la sesion
  (`findBySubject`); nunca se recibe identidad ni tenant del cliente. FR-002. Q-A queda resuelta por esta regla.
- Q4 - **Tiempo real**: SSE propio `GET /inbox-stream`, sin filtros. Reconectar emite una foto del contador y el cliente recarga la
  lista; sin `Last-Event-ID`. FR-008.
- Q5 - **Retencion**: 90 dias desde la creacion, lo lea o no el usuario (`notification.inbox.retention-days`). FR-011.
- Q6 - **Contenido**: copia de `title` (asunto de la notificacion, maximo 200) y `body` (maximo 4000). Excedido es fallo
  permanente, sin truncar. El backend no interpreta HTML; el MFE lo pinta como texto. FR-001.

## User Scenarios & Testing

### User Story 1 - Recibir en la bandeja (Priority: P1)

**Independent Test**: enviar `POST /notifications` con canal `IN_APP` al `subject` de un usuario y listar con su sesion.

1. **Given** el canal IN_APP activo, **When** una aplicacion envia una notificacion a un `subject` o `recipientIdentityId`,
   **Then** llega a estado terminal exitoso y el usuario ve un item sin leer en `GET /inbox-items`.
2. **Given** una redelivery del mismo mensaje, **Then** existe un solo item.
3. **Given** el mismo `subject` en otro tenant, **Then** el otro tenant no ve nada.
4. **Given** un `body` mayor de 4000 o un `title` mayor de 200, **Then** fallo permanente (`INBOX_CONTENT_TOO_LARGE`, 3101) y no hay item.

### User Story 2 - Campana: listado y contador (Priority: P1)

1. **Given** items leidos y sin leer, **When** `GET /inbox-items` con `status` `UNREAD` o `ALL`, **Then** solo los del usuario de
   la sesion, mas recientes primero, con cursor (maximo 50 por pagina).
2. **Given** el mismo usuario, **When** `GET /inbox-unread-counts`, **Then** el numero exacto de items sin leer.
3. **Given** dos usuarios del mismo tenant, **Then** ninguno ve ni cuenta items del otro.

### User Story 3 - Marcar como leido y borrar (Priority: P1)

1. **Given** un item propio sin leer, **When** `POST /inbox-reads` con `itemId`, **Then** 204, `readAt` fijado y el contador baja en
   uno; repetirlo responde 204 sin cambiar `readAt`.
2. **Given** un `itemId` de otro usuario, de otro tenant o inexistente, **When** se marca o se borra, **Then** la misma 404
   (`INBOX_ITEM_NOT_FOUND`, 3100) y el item ajeno queda intacto.
3. **Given** items sin leer, **When** `POST /inbox-read-alls`, **Then** 204 y el contador es 0.
4. **Given** un item propio, **When** `POST /inbox-removals` con `itemId`, **Then** 204 y desaparece fisicamente.

### User Story 4 - Tiempo real por usuario (Priority: P1)

1. **Given** un usuario con el stream abierto, **When** llega un item IN_APP para el, **Then** recibe `CREATED` con el item y el
   contador nuevo.
2. **Given** dos pestanas del mismo usuario, **When** marca leido en una, **Then** la otra recibe `READ` con el contador.
3. **Given** dos usuarios conectados (mismo tenant o tenants distintos), **When** llega un item para uno, **Then** el otro no recibe
   ningun evento (control positivo: el destinatario si lo recibe).
4. **Given** una conexion nueva, **Then** el primer evento es `SNAPSHOT` con el contador vigente.
5. **Given** credenciales invalidas, **Then** 401; el stream solo se abre con Bearer o cookie de sesion (spec 025/HU2-111).

### User Story 5 - Retencion y borrado de datos personales (Priority: P2)

1. **Given** un item con mas de 90 dias, **Then** el sistema lo elimina solo.
2. **Given** una solicitud del titular, **When** ADMINISTRADOR_APLICACION o ADMINISTRADOR_PLATAFORMA invoca `POST /inbox-erasures`
   con el `subject`, **Then** 204, se eliminan todos sus items del tenant y no queda contenido recuperable; 204 tambien si no
   tenia. El endpoint delega en el borrado comun (FR-016).
3. **Given** el mismo caso, **When** lo invoca CLIENTE, OPERADOR, AUDITOR o EDITOR_PLANTILLAS, **Then** 403.

### Edge Cases

- Usuario sin identidad previa: listado vacio y contador 0, sin crear identidad (solo el envio la crea).
- Marca de leido concurrente de dos pestanas: idempotente, un solo `readAt`.
- Broker caido al publicar el evento en vivo: el item ya esta persistido; el fallo se registra y no cambia el resultado.
- Cliente SSE lento: se descarta por contrapresion; la lista de la API es la fuente de verdad.
- `status` desconocido o `limit` fuera de 1..50: 400.

## Requirements

- **FR-001**: Canal `IN_APP` y proveedor `in-app` (`NotificationSenderPort`) que persiste un item y devuelve exito; sin llamadas
  externas ni credenciales. Persistir es la entrega; idempotente por `(tenantId, notificationId, recipientIdentityId)`. Copia
  `title` y `body` (Q6).
- **FR-002**: El item pertenece a `(tenantId, recipientIdentityId)`, tomado del campo o del atajo `subject` segun Q3, con
  `RecipientIdentityPort` de E5. Esta historia no define ese puerto.
- **FR-003**: Item = `itemId`, `notificationId`, `title`, `body`, `createdAt`, `readAt?`, `expiresAt`. Estado leido por usuario.
- **FR-004**: `GET /inbox-items` (`status`, `limit`, `cursor`), `GET /inbox-unread-counts`, `POST /inbox-reads` (`itemId`),
  `POST /inbox-read-alls`, `POST /inbox-removals` (`itemId`), `POST /inbox-erasures` (`subject`), `GET /inbox-stream` (SSE).
  Rutas literales; ids en query o cuerpo. Roles (B2): `/inbox-*` CLIENTE; `/inbox-erasures` ADMINISTRADOR_APLICACION y
  ADMINISTRADOR_PLATAFORMA.
- **FR-005**: Tenant e identidad salen siempre de la sesion o token validados; ningun parametro, encabezado ni cuerpo los fija.
- **FR-006**: Todo acceso filtra por `(tenantId, recipientIdentityId)` en la propia consulta; ajeno, de otro tenant e inexistente
  responden igual: 404 `INBOX_ITEM_NOT_FOUND` (3100).
- **FR-007**: Marcar leido y borrar son actualizaciones atomicas condicionadas por `(tenantId, identityId, itemId)`.
- **FR-008**: `GET /inbox-stream` entrega solo eventos de la identidad de la sesion, indexada por `(tenantId, subject)`. Los mensajes
  entre replicas llevan solo ids y `subject`; cada replica lee el item con el filtro del suscriptor. Eventos `SNAPSHOT`,
  `CREATED`, `READ`, `REMOVED`, todos con `unreadCount`.
- **FR-009**: Difusion entre replicas por el broker existente (patron de `NotificationUpdatesRabbitAdapter`); sink
  `directBestEffort`; sin persistencia, asi que sin ack manual ni DLQ (justificado en plan).
- **FR-010**: Un fallo al publicar el evento en vivo nunca cambia el resultado del intento ni pierde el item (Principio VIII).
- **FR-011**: Retencion 90 dias por indice TTL sobre `expiresAt`; borrado fisico por vencimiento, por el usuario y por titular.
- **FR-012**: Base legal, retencion y borrado en "Datos personales"; `title` y `body` nunca aparecen en logs, metricas ni mensajes
  del broker.
- **FR-013**: Metricas `inbox.items.created`, `inbox.items.read`, `inbox.stream.connections` (sin etiquetas de usuario) y logs con
  `tenantId`, `notificationId` e `itemId`.
- **FR-014**: Puerto estable para F4: un digest se emite como notificacion normal por IN_APP; tipos compartidos `InboxEntry` e
  `InboxPort` (plan).
- **FR-015**: Cambios de contrato listados en `plan.md`, todos con `x-status: planned`; esta historia no edita
  `api-notificaciones.yaml`.
- **FR-016**: El borrado por titular es un contribuyente `RecipientDataEraser` registrado en el `EraseRecipientDataUseCase` unico
  que introduce F6. La bandeja no tiene caso de uso de borrado propio.
- **FR-017**: Errores de dominio: `INBOX_ITEM_NOT_FOUND` (3100) y `INBOX_CONTENT_TOO_LARGE` (3101, fallo permanente).

## Datos personales

- **Dato tratado**: contenido de los mensajes dirigidos a una persona (`title`, `body`) y su `subject` seudonimo.
- **Base legal (supuesto a confirmar por el responsable de datos)**: ejecucion del servicio que el titular usa, Ley 1581 de 2012;
  finalidad unica: mostrar al titular sus avisos.
- **Retencion**: 90 dias desde la creacion; sin copia en otras colecciones. `body` queda en claro ese plazo (EX-5).
- **Borrado**: fisico; por usuario, por vencimiento (TTL) y por titular. La auditoria guarda solo un resumen sin contenido.
- **Acceso**: solo el propio usuario; el administrador borra pero no lee bandejas ajenas.

## Success Criteria

- **SC-001**: Con el stream abierto, `CREATED` llega en menos de 5 s desde `POST /notifications` (asercion de `Duration`).
- **SC-002**: Con dos tenants con el mismo `subject` y dos usuarios del mismo tenant, ninguna operacion (listar, contar, leer, borrar,
  SSE, erasure) de uno modifica ni revela datos del otro; control positivo en cada caso (prueba automatizada).
- **SC-003**: 20 marcas de leido concurrentes del mismo item dejan un solo `readAt` y el contador baja exactamente en uno.
- **SC-004**: Con redelivery forzada del despacho, queda exactamente un item por notificacion.
- **SC-005**: 50 ejecuciones secuenciales de listar, contar y marcar con 200 items: p95 menor de 300 ms.
- **SC-006**: Ningun log ni mensaje del broker contiene `title` ni `body` del item; el `subject` seudonimo del evento si puede viajar.
  Control positivo: la captura registra el envio y el usuario ve el contenido por la API.
- **SC-007**: El indice TTL tiene `expireAfter` igual a la retencion configurada y `POST /inbox-erasures` borra al instante.

## Excepciones y limites (Principio VII)

- **EX-1**: Componente de campana, montaje en el host y contrato host-MFE: fuera de este repositorio (`Front-Notification-mfe`, C1b).
  Dueno: equipo del MFE. Fecha: 2026-12-15.
- **EX-2**: Perfil del usuario final en el PDP para `/inbox-*` y CORS con credenciales hacia el origen del host. Dueno: Seguridad.
  Fecha: 2026-12-15.
- **EX-3**: Confirmacion de la base legal. Dueno: responsable de datos. Fecha: 2026-12-15.
- **EX-4**: Reanudacion SSE con `Last-Event-ID` y limite de items por usuario: no se construyen; la lista de la API es la fuente de
  verdad. Dueno: backend notificaciones. Fecha: 2026-12-15.
- **EX-5**: `body` en claro 90 dias en `inbox_items`, incluidos OTP y avisos de seguridad. Limite aceptado por la retencion de 90
  dias; alternativa: cifrado AES-GCM de E5 sobre `body`. Dueno: Seguridad. Fecha: 2026-12-15.

## Preguntas abiertas

Ninguna. Q-A queda resuelta por Q3.

## Out of Scope

Componente visual y contrato host-MFE (EX-1), preferencias por categoria (F1), digest (F4), fan-out por persona (F6), push (FCM)
y edicion de `api-notificaciones.yaml` (lo hace Contrato).
