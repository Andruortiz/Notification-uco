# Implementation Plan: Bandeja in-app del destinatario (backend) (HU2-131)

**Branch**: `feature/HU2-131-bandeja-in-app` | **Spec**: [spec.md](spec.md)

## Resumen

Canal `IN_APP` con proveedor `in-app` que escribe `inbox_items` por `(tenantId, recipientIdentityId)`; casos de uso de listado,
contador, lectura y borrado; SSE `GET /inbox-stream` por identidad, con difusion entre replicas por el broker existente llevando
solo ids. Retencion TTL. Sin UI.

## Constitution Check

- I Hexagonal: modelo, casos de uso y puertos en `core`; Mongo, Rabbit y WebFlux en `infrastructure`. `HexagonalArchitectureTest`
  y `ModularityTests` verdes.
- II Contrato primero: operaciones de la seccion Contrato antes de los controladores; rutas literales.
- III Sin comentarios. IV E2E `InboxE2ETest`; `verify` completo solo al cierre. V Commits de una linea.
- VII Excepciones EX-1 a EX-6 en la spec, con dueno y fecha. VIII/IX: decisiones 4, 6 y 8.
- Consumidor RabbitMQ nuevo (difusion en vivo): **excepcion a ack manual y DLQ (EX-6)**. Es una cola exclusiva por replica,
  auto-borrable, sin persistencia ni efecto de negocio. Perder un mensaje equivale a un cliente desconectado y la lista de la API
  lo corrige. Mismo criterio que `NotificationUpdatesRabbitAdapter` (spec 006). Es el unico consumidor nuevo.

## Decisiones

1. **Canal y proveedor**: `IN_APP` se agrega al catalogo como un canal mas (alta existente; verificar en la primera tarea).
   `InAppNotificationProvider` implementa `NotificationSenderPort` (`providerId` `in-app`, sin adjuntos, `disabledReason` vacio).
   Exito = item persistido.
2. **Modelo**: `InboxEntry(itemId UUID, tenantId, recipientIdentityId, notificationId, title, body, createdAt, readAt?, expiresAt)`
   en `core/domain`. Coleccion `inbox_items`; indices: unico `(tenantId, notificationId, recipientIdentityId)` (idempotencia),
   `(tenantId, recipientIdentityId, readAt, createdAt desc)` (listado y contador) y TTL sobre `expiresAt`
   (`expireAfterSeconds: 0`). `expiresAt = createdAt + retention-days`; cambiar la retencion no exige `collMod`.
3. **Identidad (regla unica del lote, spec Q3)**: el campo `recipientIdentityId` es el explicito. E6 resuelve el atajo `subject`
   con `RecipientIdentityPort` de E5: `findOrCreateBySubject` al enviar y `findBySubject` al leer (sin crear). E6 no define ni
   implementa ese puerto ni un adaptador propio.
4. **Idempotencia y Principio VIII**: `append` es un upsert `setOnInsert` por la clave unica; la redelivery no duplica. Si el
   guardado falla, el intento es RECOVERABLE y entra al ciclo de reintento existente. La publicacion del evento va despues del
   guardado con `onErrorResume` (registra y sigue).
5. **Puertos** (`core/port`): in: `ListInboxItemsUseCase`, `CountUnreadInboxUseCase`, `MarkInboxItemReadUseCase`,
   `MarkAllInboxReadUseCase`, `RemoveInboxItemUseCase`, `SubscribeToInboxUseCase`. Out: `InboxPort` (`append`, `list`,
   `countUnread`, `markRead` -> `Mono<Boolean>`, `markAllRead`, `remove` -> `Mono<Boolean>`, `eraseByIdentity`, `findById`) e
   `InboxEventsPort` (`publish(InboxEvent)`, `events(tenantId, identityId)`). `InboxEvent(type {CREATED, READ, REMOVED}, tenantId,
   subject, itemIds)`: sin contenido. Puerto estable para F4: `InboxEntry` + `InboxPort.append`.
6. **Aislamiento (FR-006)**: toda operacion de `InboxPort` recibe `(tenantId, identityId)` obligatorios y los aplica en la misma
   consulta. `markRead`: `updateOne` con filtro de los tres campos y pipeline `$ifNull`; `matchedCount == 0` -> 404 uniforme.
   Ninguna firma de lectura acepta solo `itemId`.
7. **SSE** (`InboxStreamController`, `GET /inbox-stream`): la clave de suscripcion es `(tenantId, subject)` de la sesion; conectar
   no crea identidad. Orden obligatorio: suscribirse al hub, luego emitir `SNAPSHOT` (contador por `findBySubject`; 0 si no hay
   identidad). Keep-alive cada 20 s como el stream actual. Metrica de conexiones activas.
8. **Difusion entre replicas** (`InboxRabbitAdapter`): exchange fanout `notification.inbox.events` (nuevo), cola exclusiva
   auto-delete por replica. El mensaje es el `InboxEvent` serializado por el productor real: ids y `subject` (seudonimo), nunca
   contenido. Cada replica mantiene `ConcurrentHashMap<(tenantId, subject), Sinks.Many<InboxEvent>>` con
   `Sinks.many().multicast().directBestEffort()`; al entregar resuelve la identidad con `findBySubject`, lee el item con el filtro
   `(tenantId, identityId, itemId)` y calcula `unreadCount`.
9. **Contador y paginacion**: `countDocuments` sobre `(tenantId, recipientIdentityId, readAt)`; cursor opaco (base64 de
   `createdAt|itemId`); `limit` 1..50 (20 por defecto); orden `createdAt desc, itemId desc`.
10. **Retencion y borrado**: `notification.inbox.retention-days` (90). El TTL de Mongo purga cada ~60 s; no hay contador de
    expirados. El borrado por titular no es un endpoint con logica propia: `InboxRecipientDataEraser` (core, `application`)
    es un contribuyente de `EraseRecipientDataUseCase` (F6). `POST /inbox-erasures` delega en ese caso de uso.
11. **Seguridad**: rutas bajo el PEP con `x-allowed-roles` (B2): CLIENTE en `/inbox-*` salvo erasures; ADMINISTRADOR_APLICACION y
    ADMINISTRADOR_PLATAFORMA en `/inbox-erasures`. Ninguna otra ruta de E6 usa roles de administracion (coincide con B2). Identidad: B1 (token) o cookie de sesion (HU2-111) via
    `AuthenticatedPrincipal`. Credencial nunca en la URL. CORS: solo configurar el origen del host (EX-2; `CorsConfig` ya permite credenciales con origenes explicitos, B5).
12. **Limites y errores**: validados en `InAppNotificationProvider`: `title` 200, `body` 4000. Excedido: `INBOX_CONTENT_TOO_LARGE`
    (3101), fallo permanente sin item. Recurso no visible para el usuario: `INBOX_ITEM_NOT_FOUND` (3100), 404. Cuerpo en claro
    durante 90 dias: EX-5.

## Estructura

- `core`: `domain/InboxEntry`, `domain/valueobject/{InboxItemId,InboxEvent,InboxEventType}`, `port/in/*UseCase` (arriba),
  `port/out/{InboxPort,InboxEventsPort}`, `application/*Service`, `application/InboxRecipientDataEraser`.
- `infrastructure`: `adapter/out/provider/InAppNotificationProvider`, `adapter/out/mongo/{InboxItemDocument,InboxMongoAdapter}`,
  `adapter/out/rabbit/InboxRabbitAdapter`, `adapter/in/rest/{InboxController,InboxStreamController}` + DTOs, `InboxProperties`,
  cableado en `UseCaseConfig` (lo hace Cableado) e `InboxConfig`. No hay adaptador de identidad: lo aporta E5.

## Pruebas

| Nivel | Clase | Cubre |
|---|---|---|
| Unitaria core | `InboxServicesTest` | 404 uniforme, idempotencia de lectura, limites, contribuyente de borrado |
| Unitaria infra | `InAppNotificationProviderTest` | exito, limite de contenido, redelivery |
| Integracion Mongo | `InboxMongoAdapterTest` | indices unico/TTL, aislamiento dos tenants y dos usuarios, 20 marcas concurrentes |
| Integracion Rabbit | `InboxRabbitAdapterTest` | publica `InboxEvent` real, solo llega al suscriptor, control positivo |
| E2E | `InboxE2ETest` (`@SpringBootTest` + Mongo + RabbitMQ + `WebTestClient`) | envio -> `CREATED` en menos de 5 s, listar, contar, leer, `READ` en otra conexion, borrar, erasure, 2 tenants x 2 usuarios, p95, sin contenido en logs, broker caido (contenedor detenido): item persistido y resultado de la notificacion sin cambio (FR-010) |
| Arquitectura | `HexagonalArchitectureTest`, `ModularityTests` | sin regresion |

## Contrato (cambios requeridos, los aplica Contrato; todos `x-status: planned`, tag `Bandeja`)

| Ruta | Metodo | operationId | Roles (`x-allowed-roles`) | Respuestas |
|---|---|---|---|---|
| `/inbox-items` | GET | `listInboxItems` | CLIENTE | 200 `InboxItemPage`, 400, 401, 403, 503 |
| `/inbox-unread-counts` | GET | `getInboxUnreadCount` | CLIENTE | 200 `InboxUnreadCountResponse` |
| `/inbox-reads` | POST | `markInboxItemRead` | CLIENTE | 204, 400, 404 |
| `/inbox-read-alls` | POST | `markAllInboxRead` | CLIENTE | 204 |
| `/inbox-removals` | POST | `removeInboxItem` | CLIENTE | 204, 404 |
| `/inbox-erasures` | POST | `eraseInbox` | ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA | 204, 400, 403 |
| `/inbox-stream` | GET | `subscribeToInbox` | CLIENTE | 200 `text/event-stream` (`InboxStreamEvent`), 401, 503 |

Esquemas: `InboxItemResponse`, `InboxItemPage(items, nextCursor?)`, `InboxUnreadCountResponse(unreadCount)`,
`InboxItemIdRequest(itemId)`, `InboxErasureRequest(subject)`, `InboxStreamEvent(type SNAPSHOT|CREATED|READ|REMOVED, item?,
itemIds?, unreadCount)`. Documentar `IN_APP` como valor de `channelType` y el proveedor `in-app` si el contrato los enumera.
Autenticacion de `/inbox-stream` identica a `/notifications/stream` (Bearer o cookie).

## Riesgos y orden

- **E5 antes que E6**: E6 consume `RecipientIdentityPort` de E5 y no tiene adaptador propio. Si E5 no esta en `main` al
  implementar, la historia no arranca.
- **Borrado por titular y F6**: `InboxRecipientDataEraser` y `POST /inbox-erasures` dependen de `EraseRecipientDataUseCase`, que
  introduce F6. Si F6 no esta en `main` al cerrar, esa tarea se difiere con dueno y fecha; no se crea un caso de uso propio.
  El contribuyente recibe `(tenantId, recipientIdentityId)` ya resueltos; se confirma con F6.
- **Relleno de `recipientIdentityId`**: F1 lo evalua antes del despacho. El punto exacto (ingesta de `POST /notifications` o D3)
  se confirma con los responsables de D3 y F1; esta historia asume que el campo llega relleno al proveedor.
- **Subject en el broker**: `InboxEvent` lleva `subject` (decision 8). Si Seguridad exige que el broker no vea `subject`, se cambia
  por la huella HMAC de E5 sin tocar la API.
