# Implementation Plan: Bandeja in-app del destinatario (backend) (HU2-131)

**Branch**: `feature/HU2-131-bandeja-in-app` | **Spec**: [spec.md](spec.md)

## Resumen

Canal `IN_APP` con proveedor interno `in-app` que escribe en una coleccion `inbox_items` por `(tenantId, recipientIdentityId)`;
casos de uso de listado, contador, lectura y borrado; SSE `GET /inbox-stream` por identidad, con difusion entre replicas por el
broker existente llevando solo ids. Retencion TTL. Nada de UI.

## Constitution Check

- I Hexagonal: modelo, casos de uso y puertos en `core`; Mongo, Rabbit y WebFlux en `infrastructure`. `HexagonalArchitectureTest`
  y `ModularityTests` verdes.
- II Contrato primero: operaciones pedidas a Contrato (seccion Contrato) antes de los controladores; rutas literales.
- III Sin comentarios. IV E2E explicita `InboxE2ETest`; `verify` completo solo al cierre. V Commits de una linea.
- VII Excepciones EX-1 a EX-4 en la spec, con dueno y fecha. VIII/IX: Decisiones 3, 6 y 9.
- Consumidor RabbitMQ nuevo (difusion en vivo): **excepcion a ack manual y DLQ**, justificada: es una cola exclusiva por
  replica, auto-borrable, sin persistencia ni efecto de negocio (no escribe nada; solo empuja a conexiones SSE ya abiertas).
  Perder un mensaje equivale a un cliente desconectado y la lista de la API lo corrige. Mismo criterio que
  `NotificationUpdatesRabbitAdapter` (spec 006). Es el unico consumidor nuevo.

## Decisiones

1. **Canal y proveedor**: `ChannelType` es un valor libre (`record ChannelType(String)`); `IN_APP` se agrega al catalogo como
   un canal mas (mecanismo de siembra/alta ya existente; verificar en la primera tarea) y `InAppNotificationProvider`
   implementa `NotificationSenderPort` (`providerId` `in-app`, `supportsAttachments` falso, `disabledReason` vacio). Exito
   = item persistido. Adjuntos en una notificacion IN_APP: rechazados por la validacion de canal existente (sin soporte).
2. **Modelo**: `InboxEntry(itemId UUID, tenantId, recipientIdentityId, notificationId, subject, body, createdAt, readAt?,
   expiresAt)` en `core/domain`. Documento Mongo `inbox_items`; indices: unico `(tenantId, notificationId, recipientIdentityId)`
   (idempotencia), `(tenantId, recipientIdentityId, readAt, createdAt desc)` (listado y contador) y TTL sobre `expiresAt`
   (`expireAfterSeconds: 0`). `expiresAt = createdAt + retention-days`; asi cambiar la retencion no exige `collMod`.
3. **Idempotencia y Principio VIII**: `append` es un upsert `setOnInsert` por la clave unica; la redelivery no duplica. Si el
   guardado falla, el intento es RECOVERABLE y entra al ciclo de reintento existente. La publicacion del evento va despues del
   guardado con `onErrorResume` (registra y sigue).
4. **Identidad (E5)**: `core/port/out/RecipientIdentityPort` (nuevo, minimo): `findBySubject(tenantId, subject)` y
   `findOrCreateBySubject(tenantId, subject)`, ambos `Mono<RecipientIdentityId>`. Lo implementa E5 sobre su repositorio; si E5
   aun no esta integrada, un adaptador Mongo minimo en esta historia usa la misma coleccion y el unico parcial
   `(tenantId, subject)` de E5 (tarea de rebase: se descarta el duplicado). Lectura del usuario usa `findBySubject`
   (sin crear); solo el proveedor usa `findOrCreateBySubject`.
5. **Puertos** (`core/port`): in: `ListInboxItemsUseCase`, `CountUnreadInboxUseCase`, `MarkInboxItemReadUseCase`,
   `MarkAllInboxReadUseCase`, `RemoveInboxItemUseCase`, `EraseInboxBySubjectUseCase`, `SubscribeToInboxUseCase`. Out:
   `InboxPort` (`append`, `list`, `countUnread`, `markRead` -> `Mono<Boolean>` encontrado, `markAllRead`, `remove` ->
   `Mono<Boolean>`, `eraseByIdentity`, `findById`) y `InboxEventsPort` (`publish(InboxEvent)`, `events(tenantId, identityId)`).
   `InboxEvent(type {CREATED, READ, REMOVED}, tenantId, subject, itemIds)`: sin contenido. **Puerto estable
   para F4**: `InboxEntry` + `InboxPort.append`; F4 emite una notificacion IN_APP por la via normal.
6. **Aislamiento (FR-006)**: toda operacion de `InboxPort` recibe `(tenantId, identityId)` como parametros obligatorios y los
   aplica dentro de la misma consulta (`markRead`: `updateOne` con filtro de los tres campos y pipeline `$ifNull`;
   `matchedCount == 0` -> 404 uniforme). Prueba de arquitectura/unitaria: ninguna firma de lectura acepta solo `itemId`.
7. **SSE** (`InboxStreamController`, `GET /inbox-stream`, `text/event-stream`): la clave de suscripcion es
   `(tenantId, subject)` de la sesion, asi que conectar no crea ninguna identidad ni exige que exista (un usuario sin
   mensajes previos recibe su primer `CREATED`). Orden obligatorio: suscribirse al hub, luego emitir `SNAPSHOT` (contador por
   `findBySubject`; 0 si no hay identidad), para no perder cambios intermedios. Keep-alive cada 20 s como el stream actual.
   Metrica de conexiones activas.
8. **Difusion entre replicas** (`InboxRabbitAdapter`): exchange fanout `notification.inbox.events` (nuevo), cola exclusiva
   auto-delete por replica; el mensaje es el `InboxEvent(type, tenantId, subject, itemIds)` serializado por el productor real:
   ids y `subject` (seudonimo), nunca contenido. Cada replica mantiene
   `ConcurrentHashMap<(tenantId, subject), Sinks.Many<InboxEvent>>` con `Sinks.many().multicast().directBestEffort()`; al
   entregar, resuelve la identidad con `findBySubject`, lee el item con el filtro `(tenantId, identityId, itemId)` y calcula
   `unreadCount`. Un evento solo se enruta a la clave de su propio `(tenantId, subject)` y la lectura repite el filtro: un
   `itemId` ajeno dentro de un evento no devolveria nada.
9. **Contador y paginacion**: `countDocuments` sobre el indice `(tenantId, identityId, readAt)`; lista con cursor opaco
   (base64 de `createdAt|itemId`), `limit` 1..50 (20 por defecto), orden `createdAt desc, itemId desc`.
10. **Retencion y borrado**: propiedad `notification.inbox.retention-days` (90). TTL de Mongo purga cada ~60 s sin evento;
    por eso no hay contador de expirados. `EraseInboxBySubjectUseCase` (ADMINISTRADOR) borra por `(tenantId, identityId)`;
    no usa `findOrCreate`. **Punto compartido con E5**: el borrado por titular de E5 (`POST /device-erasures`) debe poder
    invocar el mismo caso de uso; en esta historia se expone como endpoint propio para no acoplar ramas.
11. **Seguridad**: rutas bajo el PEP con `x-minimum-role` CLIENTE (erasures: ADMINISTRADOR). Identidad: B1 (token) o cookie de
    sesion (HU2-111) via `AuthenticatedPrincipal`. Credencial nunca en la URL. CORS con credenciales: EX-2.
12. **Limites de contenido**: validados en `InAppNotificationProvider` (subject 200, body 4000): fallo permanente sin item.

## Estructura

- `core`: `domain/InboxEntry`, `domain/valueobject/{InboxItemId,InboxEvent,InboxEventType}`,
  `port/in/*UseCase` (arriba), `port/out/{InboxPort,InboxEventsPort,RecipientIdentityPort}`, `application/*Service`.
- `infrastructure`: `adapter/out/provider/InAppNotificationProvider`, `adapter/out/mongo/{InboxItemDocument,InboxMongoAdapter}`,
  `adapter/out/rabbit/InboxRabbitAdapter`, `adapter/in/rest/{InboxController,InboxStreamController}` + DTOs,
  `InboxProperties`, cableado en `UseCaseConfig` (lo hace Cableado) y `InboxConfig` para lo propio de esta historia.

## Pruebas

| Nivel | Clase | Cubre |
|---|---|---|
| Unitaria core | `InboxServicesTest` | 404 uniforme, idempotencia de lectura, limites |
| Unitaria infra | `InAppNotificationProviderTest` | exito, limite de contenido, redelivery |
| Integracion Mongo | `InboxMongoAdapterTest` | indices unico/TTL, aislamiento dos tenants y dos usuarios, 20 marcas concurrentes |
| Integracion Rabbit | `InboxRabbitAdapterTest` | publica `InboxEvent` real, solo llega al suscriptor, control positivo |
| E2E | `InboxE2ETest` (`@SpringBootTest` + Mongo + RabbitMQ + `WebTestClient`) | envio -> `CREATED` en menos de 5 s, listar, contar, leer, `READ` en otra conexion, borrar, erasure, 2 tenants x 2 usuarios, p95, sin contenido en logs |
| Arquitectura | `HexagonalArchitectureTest`, `ModularityTests` | sin regresion |

## Contrato (cambios requeridos, los aplica Contrato; todos `x-status: planned`, tag `Bandeja`)

| Ruta | Metodo | operationId | Rol minimo | Respuestas |
|---|---|---|---|---|
| `/inbox-items` | GET | `listInboxItems` | CLIENTE | 200 `InboxItemPage`, 400, 401, 403, 503 |
| `/inbox-unread-counts` | GET | `getInboxUnreadCount` | CLIENTE | 200 `InboxUnreadCountResponse` |
| `/inbox-reads` | POST | `markInboxItemRead` | CLIENTE | 204, 404, 400 |
| `/inbox-read-alls` | POST | `markAllInboxRead` | CLIENTE | 204 |
| `/inbox-removals` | POST | `removeInboxItem` | CLIENTE | 204, 404 |
| `/inbox-erasures` | POST | `eraseInbox` | ADMINISTRADOR | 204, 400, 403 |
| `/inbox-stream` | GET | `subscribeToInbox` | CLIENTE | 200 `text/event-stream` (`InboxStreamEvent`), 401, 503 |

Esquemas: `InboxItemResponse`, `InboxItemPage(items, nextCursor?)`, `InboxUnreadCountResponse(unreadCount)`,
`InboxItemIdRequest(itemId)`, `InboxErasureRequest(subject)`, `InboxStreamEvent(type SNAPSHOT|CREATED|READ|REMOVED, item?,
itemIds?, unreadCount)`. Tambien: documentar `IN_APP` como valor de `channelType` y el proveedor `in-app` si el contrato
enumera valores. Autenticacion de `/inbox-stream` identica a `/notifications/stream` (Bearer o cookie).

## Riesgos

- E5 y B1 no estan en esta rama: compilacion contra `RecipientIdentityPort` propio hasta rebasar; la tarea de rebase es parte
  de la historia.
- `InboxEvent` lleva `tenantId` y `subject` en el broker (Decision 8); si Seguridad exige que el broker no vea `subject`, se
  cambia por la huella HMAC de E5 sin tocar la API.
