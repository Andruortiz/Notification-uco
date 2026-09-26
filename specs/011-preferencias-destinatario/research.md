# Research: Gestionar preferencias del destinatario y excluir bajas al despachar

**Feature**: 011-preferencias-destinatario | **Date**: 2026-09-26

## Decisión 1 — Momento de la exclusión: en el despacho, antes de `markQueued()` (opción b)

**Contexto verificado en el código** (no asumido):

- `StatusTransitionPolicy` permite `PENDING → {IN_PROCESS, DISCARDED}` e `IN_PROCESS → {DELIVERED,
  RECOVERABLE, FAILED}`. No existe `IN_PROCESS → DISCARDED`.
- `DispatchNotificationService.dispatch` hace, en este orden: `findById` → `findActiveRoute` →
  `notificationSenderRegistry.resolve(preferredProvider)` → `sendThrough`, y es dentro de `sendThrough`
  donde se llama `notification.markQueued()` (en memoria, sin persistir) inmediatamente antes de
  `sender.send(notification)`. El estado `IN_PROCESS` nunca se persiste por separado: la única escritura
  ocurre después del resultado del proveedor (`saveAndPublish`).
- Por tanto, en cualquier punto antes de `sendThrough` la notificación leída sigue en `PENDING` y
  `Notification.discard()` (que ya existe, registra `NotificationDiscarded` y hoy no se usa en
  producción) es una transición válida.

**Decisión**: consultar `RecipientPreferencePort` en `DispatchNotificationService` justo después de
`findById` y antes de `findActiveRoute`. Si la preferencia excluye el canal: `notification.discard()` y
el mismo `saveAndPublish` que ya usa el resto del despacho. Si no: el flujo existente sin cambios.

**Rationale**:

- Cumple el texto literal de HU2-030 (el despacho consulta las preferencias antes de enviar).
- No toca `StatusTransitionPolicy` ni `StatusTransitionPolicyTest`.
- No reordena nada existente: es un paso nuevo delante de la búsqueda de la ruta. Las pruebas actuales
  de `DispatchNotificationServiceTest` solo necesitan el nuevo colaborador en el constructor (con un
  stub "sin preferencias").
- La preferencia se evalúa en cada despacho, incluidos los reencolados por el scheduler y los
  reintentos tras `RECOVERABLE → PENDING`, así que una baja posterior a la aceptación también se
  respeta (User Story 4).
- Se consulta antes que la ruta porque no contactar al destinatario no depende de que el canal esté
  disponible, y evita una consulta al catálogo que no se va a usar.

**Alternativas consideradas**:

- (a) En `SendNotificationService` (aceptación): coherente con la política de estados, pero una baja
  declarada entre la aceptación y el despacho, o antes de un reintento, no se respetaría; además
  cambiaría la respuesta de aceptación (y del lote) y revelaría al sistema cliente, en la respuesta
  síncrona, la situación del destinatario. Descartada.
- (c) Ampliar `StatusTransitionPolicy` con `IN_PROCESS → DISCARDED`: innecesaria porque (b) es viable;
  cambiaría una regla de dominio probada y abriría transiciones que ningún otro flujo necesita.
  Descartada.

## Decisión 2 — Semántica de la lista de canales: vacía = sin restricción

**Decisión**: `acceptedChannels` vacía o ausente, con `optedOutAll = false`, significa "acepta todos
los canales". Con `optedOutAll = true` la lista se ignora y se guarda vacía. La consulta de un
destinatario sin registro devuelve `optedOutAll = false`, `acceptedChannels = []`, `updatedAt = null`.

**Rationale**: una única forma de decir "no me contacten" (la baja total); la forma por defecto que
devuelve la consulta, si se reenvía tal cual a la actualización, no cambia el comportamiento; no hay que
distinguir `null` de `[]` en el JSON.

**Alternativas**: `[]` = ninguno y ausente = todos (dos significados según una diferencia sutil del
JSON); devolver en la consulta por defecto la lista de canales del catálogo (acopla la respuesta al
catálogo y a su tenant, y una preferencia guardada así dejaría fuera canales declarados después).

## Decisión 3 — Normalización y validación de los nombres de canal

**Decisión**: cada entrada se recorta, se pasa a mayúsculas (`Locale.ROOT`) y se deduplica conservando
el orden de llegada. Una entrada `null`, vacía o solo espacios rechaza toda la actualización con `400`
(la construcción de `ChannelType` ya lanza `IllegalArgumentException`, que
`NotificationExceptionHandler` ya traduce a `400`). No se valida contra el catálogo.

**Rationale**: el catálogo ya compara canales en mayúsculas (`MongoChannelCatalogAdapter` hace
`channel.value().toUpperCase()`); la comparación del despacho usa la misma regla para que `email` en la
notificación y `EMAIL` en la preferencia sean el mismo canal. No validar contra el catálogo evita
rechazar preferencias sobre canales deshabilitados temporalmente y no acopla la historia al catálogo
(que otra rama en paralelo está ampliando).

## Decisión 4 — Persistencia: colección nueva con clave compuesta `_id = {tenantId, recipientId}`

**Decisión**: colección `recipient_preferences`, documento
`RecipientPreferenceDocument(@Id RecipientPreferenceKey id, boolean optedOutAll, List<String>
acceptedChannels, Instant updatedAt)` con `RecipientPreferenceKey(String tenantId, String
recipientId)`. La actualización es `ReactiveMongoTemplate.save(...)`, que con un `_id` presente es un
reemplazo completo con upsert atómico por `_id`.

**Rationale**:

- La unicidad por (tenant, destinatario) la garantiza el propio `_id`, sin índice adicional que las
  pruebas deban recrear tras `dropCollection` (a diferencia de `notifications`).
- El aislamiento por tenant queda en la clave: no existe forma de leer o escribir las preferencias de
  un destinatario sin su tenant.
- Reemplazo completo = la operación es idempotente y dos actualizaciones concurrentes dejan exactamente
  una de ellas (FR-014, SC-009). MongoDB 7 reintenta internamente el upsert concurrente que choca por
  clave duplicada cuando el filtro es igualdad sobre un índice único (incluido `_id`).

**Bloqueo optimista**: la constitución exige bloqueo optimista para mutaciones de un agregado ya
persistido porque el despacho hace leer-llamar-escribir con una llamada lenta en medio. La
actualización de preferencias no lee antes de escribir ni combina con el estado anterior: es un
reemplazo completo, atómico, con semántica "gana el último". No hay actualización perdida posible en
el sentido que la regla protege (no se pisa un cambio parcial de otro escritor). El contrato público
tampoco ofrece versión ni `If-Match`. Se documenta aquí como aplicación razonada de la regla, no como
excepción.

**Alternativas**: `@Id String` generado + campos `tenantId`/`recipientId` con índice único compuesto
(misma garantía, un índice más que mantener en pruebas); `_id` como cadena concatenada
`tenantId:recipientId` (ambigua si alguno contiene el separador).

## Decisión 5 — Fallo al leer preferencias durante el despacho: fallar cerrado

**Decisión**: el error del puerto se propaga sin tocar la notificación. El consumidor RabbitMQ existente
(ack manual con reintentos y `RepublishMessageRecoverer` a la DLQ, `RabbitRetryConfig`) reintenta y, si
se agota, deriva el mensaje a la DLQ con la causa; la notificación queda `PENDING` y el scheduler de
huérfanos la reencola más tarde.

**Rationale**: enviar sin comprobar podría contactar a quien se dio de baja; descartar perdería una
notificación legítima por un fallo de infraestructura (Principio VIII). No se añade ningún consumidor
nuevo, así que la regla de ack manual + DLQ se sigue cumpliendo sin excepción.

## Decisión 6 — Puertos y casos de uso

- `core/port/out/RecipientPreferencePort`: `Mono<RecipientPreferences> findByTenantAndRecipient(TenantId,
  RecipientId)` (vacío si no hay registro) y `Mono<RecipientPreferences> save(RecipientPreferences)`.
  Se mantiene el nombre sugerido. Vive en `port/out` como los demás puertos nuevos, no en `repository`.
- `core/port/in/GetRecipientPreferencesUseCase` + `GetRecipientPreferencesQuery(tenantId, recipientId)`.
- `core/port/in/UpdateRecipientPreferencesUseCase` + `UpdateRecipientPreferencesCommand(tenantId,
  recipientId, optedOutAll, List<ChannelType> acceptedChannels)`.
- Servicios `GetRecipientPreferencesService` (vacío → `RecipientPreferences.defaults`) y
  `UpdateRecipientPreferencesService` (construye con `RecipientPreferences.declare(..., Instant.now())` y
  guarda).
- Los casos de uso devuelven el objeto de dominio `RecipientPreferences` (inmutable, sin
  comportamiento de mutación), que el controlador traduce a `RecipientPreferencesResponse`. No se crea
  una "vista" separada porque sería una copia campo a campo.

## Decisión 7 — Ruta `:updatePreferences` en WebFlux

`@PostMapping("/recipients/{recipientId}:updatePreferences")`. `PathPattern` convierte un segmento que
mezcla variable y literal en un elemento con expresión regular; el literal `:updatePreferences` ancla
el final del segmento. Es la primera operación `recurso:accion` con variable en el mismo segmento que se
implementa; la prueba E2E la ejercita de punta a punta, incluido un `recipientId` que contiene `:`.

## Decisión 8 — Ajustes al contrato (contract-first)

El contrato ya define ambas operaciones y sus esquemas. Se precisan, antes del controlador:

- `RecipientPreferencesResponse.updatedAt`: `nullable: true` — nulo si nunca se guardaron preferencias
  (no hay otra fecha verdadera que devolver).
- `RecipientPreferencesResponse`: `required: [recipientId, optedOutAll, acceptedChannels]`; descripción
  de `acceptedChannels`: vacía = sin restricción.
- `UpdatePreferencesRequest.acceptedChannels`: normalización (mayúsculas, sin duplicados), vacía o
  ausente = sin restricción, entradas vacías rechazadas.
- `POST :updatePreferences`: respuesta `400` con `ErrorResponse` (entrada de canal vacía o cuerpo
  inválido).
- Descripciones de ambas operaciones: indican el comportamiento implementado (valor por defecto,
  reemplazo completo, efecto en el despacho y aislamiento por tenant).

Ningún campo existente cambia de nombre ni de tipo.
