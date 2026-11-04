# Implementation Plan: Preferencias por categoría y canal (HU2-132)

**Branch**: `feature/HU2-132-preferencias-categoria-canal` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobación | **Versión del plan**: 2 (correcciones del lote 2026-10-25)

## Summary

Agregado `RecipientPreferences` en `core`, casos de uso de consulta y reemplazo, un `PreferenceGate` que el despacho invoca antes de
reservar, repositorio Mongo y controller sobre `/recipients/preferences`. Se apoya en tipos de otras ramas (no están en esta base):
`TemplateCategory` y el repositorio de plantillas (D1, spec 035), la referencia de plantilla que guarda `Notification` (D3, spec 043),
`RecipientIdentity` y su puerto (E5, spec 036), sesión con `tenantId`, `subject` y roles (B1 y B2). F1 se integra **después de D3**:
ambas editan `SendNotificationService`, el comando de envío y `Notification`. Los nombres exactos se confirman al rebasar; si difieren,
se ajusta el plan.

## Constitution Check (2.0.1)

- I Hexagonal: reglas, gate y casos de uso en `core` sin Spring; Mongo y REST en `infrastructure`. Prueba de arquitectura en verde.
- II Contrato primero: cambios listados abajo; las tareas del controller quedan bloqueadas hasta que Contrato los publique.
- III Sin comentarios. IV E2E `RecipientPreferencesE2ETest`, dos tenants, `Duration` en SC-002, `verify` al cierre. V Commits de una línea.
- VII Excepciones: ver Riesgos. VIII/IX: FR-009, FR-013. Sin consumidores RabbitMQ nuevos; el consumidor de despacho existente conserva
  ack manual y DLQ, y el fallo de lectura de F1 se propaga a ese camino. Sin excepción.

## Diseño

### core

- `domain/preference/`: `RecipientPreferences(tenantId, recipientIdentityId, optedOutAll, Map<TemplateCategory, CategoryPreference>,
  updatedAt)`; `CategoryPreference(optedOut, Set<ChannelType> acceptedChannels)`; `PreferenceDecision(ALLOW | SUPPRESSED_OPTED_OUT_ALL |
  SUPPRESSED_CATEGORY | SUPPRESSED_CHANNEL)`. `defaults(tenant, identity)` devuelve el estado sin restricción.
- Método puro `decide(TemplateCategory nullable, ChannelType)`: si `category != null && category.critical()` -> `ALLOW`; si `category`
  es nula, solo aplica `optedOutAll` (FR-006, Q8); si no, aplica FR-006 completo. La validación de `PUT` (crítica bloqueada, categoría
  repetida, canal vacío) vive en `RecipientPreferences.replace(...)`.
- Excepciones: `CriticalCategoryLockedException` (NTF-1018), `InvalidPreferencesException` (NTF-1017); reusan la jerarquía existente.
- Puertos de entrada: `QueryRecipientPreferencesUseCase.find(tenant, subject)`, `ReplaceRecipientPreferencesUseCase.replace(tenant,
  subject, cmd)`.
- Puerto de salida: `repository/RecipientPreferencesRepository` (`findByIdentity`, `replaceFields` upsert atómico, `deleteByIdentity`),
  siempre con tenant. La identidad la resuelve el `RecipientIdentityPort` de E5 (`findBySubject` en consulta, `findOrCreateBySubject`
  en `PUT`); F1 no lo define.
- `PreferenceGate` (servicio de dominio): `evaluate(Notification)` -> `Mono<PreferenceDecision>`; `ALLOW` si la notificación no trae
  `recipientIdentityId`; si no, carga preferencias y llama `decide(notification.category(), notification.channelType())`, donde la
  categoría sale de la referencia de plantilla que guarda D3 (F1 no la escribe).
- `Notification` gana solo `recipientIdentityId` (nullable), inmutable tras aceptar. `SendNotificationService` (de D3) acepta
  `recipientIdentityId` opcional; `templateId`, `templateValues` y la referencia de plantilla son de D3.
- `DispatchNotificationService.dispatch`, en este orden: `findById` -> `PreferenceGate.evaluate` (suprime: `notification.discard()`,
  `save` con la versión leída, evento, métrica y log, sin proveedor) -> compuerta de D5 (fallo permanente) -> `reserveForDispatch`
  (flujo actual). Una notificación descartada por preferencias no llega a evaluar D5. Si dos réplicas compiten, la reserva
  condicionada o el `save` versionado deja un solo ganador; el perdedor sigue el camino de redelivery ignorado. Error de lectura ->
  `Mono.error`, sin tocar la notificación (FR-009).

### infrastructure

- `adapter/out/mongo`: colección `recipient_preferences`, índice único `(tenantId, recipientIdentityId)`. `replaceFields` hace
  `findAndModify` con `$set` solo de `optedOutAll`, `categories`, `updatedAt` y `upsert`, de modo que F3 y F5 puedan agregar campos
  al mismo documento sin que el `PUT` los borre (FR-011). `NotificationDocument` añade solo `recipientIdentityId`.
- `adapter/in/rest`: `RecipientPreferencesController` (`GET`/`PUT /recipients/preferences`), DTOs record, mapeo 400
  (`INVALID_PREFERENCES`, `CRITICAL_CATEGORY_LOCKED`), 403 (CLIENTE con `subject` ajeno), 404 de plantilla heredado de D3. Tenant y
  `subject` del principal de sesión (B1); `subject` de la query solo para ADMINISTRADOR_APLICACION.
- `RouteAuthorizationPolicy` y `protected-resources.json` (script de HU2-099): `GET` y `PUT` con roles CLIENTE y
  ADMINISTRADOR_APLICACION según la tabla de B2 (`x-allowed-roles`).
- Borrado: `RecipientPreferencesEraser` implementa `RecipientDataEraser` y se registra como contribuyente en el
  `EraseRecipientDataUseCase` único que introduce F6, invocado por `/recipient-directory-erasures` (FR-012). `/device-erasures` no
  cambia. Depende de F6 en `main`.
- Métrica `notification.preferences.suppressed{reason,category}` por `NotificationMetricsPort` (etiquetas de cardinalidad baja).

## Puertos estables para F2 a F5

`QueryRecipientPreferencesUseCase` y `RecipientPreferencesRepository` (lectura) son el contrato que consumen: F2 (UI embebible, usa
las rutas del contrato), F3 (agrega `quietHours` y zona horaria al documento y decide dentro de `PreferenceGate`, que devolverá
además `DEFER`; "urgente" = prioridad HIGH o `category.critical()`), F4 (digest, agrega modo por categoría), F5 (baja con un clic
escribe `optedOut` por categoría reutilizando el caso de uso de reemplazo parcial que F5 añadirá). `PreferenceDecision` se amplía solo
añadiendo constantes; `RecipientPreferences` tolera atributos desconocidos. F1 no construye nada de eso.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` (`StepVerifier`) | `decide` (tabla: crítica, `optedOutAll`, categoría, canal, sin categoría, mayúsculas), `replace` (400s, normalización), `PreferenceGate`, despacho con gate simulado (suprime antes de D5, permite, error) y baja tras la reserva (no interrumpe, SC-010) |
| Integración adaptador | `RecipientPreferencesMongoRepositoryTest` (Testcontainers): índice único, `$set` parcial conserva campo ajeno, aislamiento por tenant, 10 `PUT` concurrentes |
| E2E | `RecipientPreferencesE2ETest` (`RANDOM_PORT`, Mongo y RabbitMQ): flujos de US1 a US4. Las notificaciones de despacho se siembran por repositorio (con la referencia de plantilla de D3 y `recipientIdentityId`) y el disparo es el evento real publicado por `NotificationEventPublisherPort`. Control positivo en cada "no llega", dos tenants, `Duration` ≤5 s, almacén caído, baja antes de la reserva (`DISCARDED`), borrado por `EraseRecipientDataUseCase` |
| Seguridad de ruta | una prueba por ruta en `RouteAuthorizationPolicy`; CLIENTE con `subject` ajeno da 403; ADMINISTRADOR_APLICACION con `subject` ajeno da 200 |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` |

## Cambios de contrato requeridos (api-notificaciones.yaml; no editado aquí)

1. `/recipients/preferences` (GET y PUT, hoy `planned`): reemplazar el parámetro `recipientId` por `subject` opcional (solo
   ADMINISTRADOR_APLICACION); sustituir `x-minimum-role` por `x-allowed-roles: [CLIENTE, ADMINISTRADOR_APLICACION]` (decisión 1 del lote);
   se publica con `x-status: planned`; `implemented` lo marca la historia que lo implemente.
2. `UpdatePreferencesRequest`: `{optedOutAll: boolean, categories: [{category: TemplateCategory, optedOut: boolean, acceptedChannels:
   string[]}]}`; se retira `acceptedChannels` global. `RecipientPreferencesResponse`: añade `recipientIdentityId` (nulo sin registro),
   `categories` (siempre las cuatro, con `locked: true` en la crítica) y conserva `optedOutAll` y `updatedAt`. Reutiliza `TemplateCategory`
   de D1.
3. Respuestas 400 con `code` `INVALID_PREFERENCES` (NTF-1017) y `CRITICAL_CATEGORY_LOCKED` (NTF-1018); 403 por `subject` ajeno con rol
   CLIENTE.
4. Solicitud de envío (`/notifications`, `/notifications/batches`): añadir solo `recipientIdentityId` (uuid, opcional), que F6 rellena
   además de `recipientId` (dirección, sin cambios). `templateId` y la referencia de plantilla los define D3.
5. Descripciones de `/recipients/preferences` actualizadas: ya no se describe la 013 (preferencia solo por canal).

## Riesgos y excepciones (Principio VII)

- Bloqueo por el YAML (Contrato) y por la integración de B1, B2, D1, D3, E5 y F6 en `main`: sin ellas la rama no compila. Dueño:
  andrualv, 2026-12-15. Si F6 no está en `main` al implementar, el borrado (SC-009) queda como excepción con esa fecha; no se hace un
  cambio suelto en E5.
- Precondición de roles por sesión (bloqueante, decisión 3 del lote): la rama de ADMINISTRADOR_APLICACION no se cumple en producción
  hasta que Seguridad traiga los roles en la sesión o se consulte `/api/v1/authorize`. Dueño: Seguridad, 2026-12-15.
- Abuso de categoría `SEGURIDAD`: el envío por plantilla lo limita D3 a ADMINISTRADOR_APLICACION y ADMINISTRADOR_PLATAFORMA (lote, sección 6).
  F1 no restringe el envío; límite declarado. Dueño Seguridad, 2026-12-15.
- Lectura extra de la notificación y de preferencias por despacho: sin caché, aprobada. Dueño andrualv, 2026-12-15 (medir en la prueba
  de carga).
- Retención: tabla única del lote (`lote-nivel-2.md`, sección 6): mientras exista la identidad. Dueño Responsable de datos,
  2026-12-15.
- Sin historial de consentimiento: ver spec; dueño andrualv, 2026-12-31. Base legal: Responsable de datos, 2026-12-15.
