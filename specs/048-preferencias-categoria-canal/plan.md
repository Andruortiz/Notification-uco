# Implementation Plan: Preferencias por categoría y canal (HU2-132)

**Branch**: `feature/HU2-132-preferencias-categoria-canal` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobación | **Versión del plan**: 1

## Summary

Agregado `RecipientPreferences` en `core`, casos de uso de consulta y reemplazo, un `PreferenceGate` que el despacho invoca antes de
reservar, repositorio Mongo y controller sobre `/recipients/preferences`. Se apoya en tipos de otras ramas (no están en esta base):
`TemplateCategory` y el repositorio de plantillas (D1, spec 035), `RecipientIdentity` y su repositorio (E5, spec 036), sesión con
`tenantId` y `subject` (B1, spec 029). Los nombres exactos se confirman al rebasar sobre ellas; si difieren, se ajusta el plan.

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
- Método puro `decide(TemplateCategory nullable, ChannelType)`: si `category != null && category.critical()` -> `ALLOW`; luego aplica
  FR-006. La validación de `PUT` (crítica bloqueada, categoría repetida, canal vacío) vive en `RecipientPreferences.replace(...)`.
- Excepciones: `CriticalCategoryLockedException`, `InvalidPreferencesException` (reusa la jerarquía existente).
- Puertos de entrada: `QueryRecipientPreferencesUseCase.find(tenant, subject)`, `ReplaceRecipientPreferencesUseCase.replace(tenant, subject, cmd)`.
- Puerto de salida: `repository/RecipientPreferencesRepository` (`findByIdentity`, `replaceFields` upsert atómico, `deleteByIdentity`),
  siempre con tenant. La identidad se resuelve con el repositorio de E5 (`findBySubject` en consulta, `findOrCreate` en `PUT`).
- `PreferenceGate` (servicio de dominio): `evaluate(Notification)` -> `Mono<PreferenceDecision>`; `ALLOW` si no hay identidad; si no,
  carga preferencias y llama `decide(notification.category(), notification.channelType())`.
- `Notification` gana `category` (nullable) y `recipientIdentityId` (nullable), inmutables tras aceptar. `SendNotificationService`
  acepta `templateId` y `recipientIdentityId` opcionales y resuelve la categoría con el repositorio de plantillas (D1) de ese tenant.
- `DispatchNotificationService.dispatch`: `findById` -> `PreferenceGate.evaluate`; `ALLOW` -> `reserveForDispatch` (flujo actual);
  suprimida -> `notification.discard()` (PENDING a DISCARDED), `save` con la versión leída, publicar evento, métrica y log. Si dos
  réplicas compiten, la reserva condicionada o el `save` versionado deja un solo ganador; el perdedor sigue el camino de redelivery
  ignorado. Error de lectura -> `Mono.error`, sin tocar la notificación (FR-009).

### infrastructure

- `adapter/out/mongo`: colección `recipient_preferences`, índice único `(tenantId, recipientIdentityId)`. `replaceFields` hace
  `findAndModify` con `$set` solo de `optedOutAll`, `categories`, `updatedAt` y `upsert`, de modo que F3 y F5 puedan agregar campos
  al mismo documento sin que el `PUT` los borre (FR-011). `NotificationDocument` guarda `category` y `recipientIdentityId`.
- `adapter/in/rest`: `RecipientPreferencesController` (`GET`/`PUT /recipients/preferences`), DTOs record, mapeo 400
  (`INVALID_PREFERENCES`, `CRITICAL_CATEGORY_LOCKED`), 403 (`subject` ajeno), 404 de plantilla heredado de D1. Tenant y `subject` del
  principal de sesión (B1); `subject` de la query solo para ADMINISTRADOR.
- `RouteAuthorizationPolicy`: `GET` y `PUT` -> CLIENTE; registrar ambos recursos con el script de HU2-099 (`protected-resources.json`).
- Adaptador de borrado: el caso de uso de borrado por titular de E5 invoca `RecipientPreferencesRepository.deleteByIdentity` (cambio
  pequeño en código de E5; se coordina al integrar, ver Riesgos).
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
| Unitaria `core` (`StepVerifier`) | `decide` (tabla: crítica, `optedOutAll`, categoría, canal, sin categoría, mayúsculas), `replace` (400s, normalización), `PreferenceGate`, despacho con gate simulado (suprime, permite, error) |
| Integración adaptador | `RecipientPreferencesMongoRepositoryTest` (Testcontainers): índice único, `$set` parcial conserva campo ajeno, aislamiento por tenant, 10 `PUT` concurrentes |
| E2E | `RecipientPreferencesE2ETest` (`RANDOM_PORT`, Mongo y RabbitMQ): flujos de US1 a US4 con el evento real publicado por `NotificationEventPublisherPort`, control positivo en cada prueba de "no llega", dos tenants, `Duration` ≤5 s, almacén caído, borrado por E5 |
| Seguridad de ruta | una prueba por ruta en `RouteAuthorizationPolicy`; CLIENTE con `subject` ajeno da 403 |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` |

## Cambios de contrato requeridos (api-notificaciones.yaml; no editado aquí)

1. `/recipients/preferences` (GET y PUT, hoy `planned`): reemplazar el parámetro `recipientId` por `subject` opcional (solo ADMINISTRADOR);
   pasar `x-status` a `implemented` al publicarse; mantener `x-minimum-role: CLIENTE`.
2. `UpdatePreferencesRequest`: `{optedOutAll: boolean, categories: [{category: TemplateCategory, optedOut: boolean, acceptedChannels: string[]}]}`;
   se retira `acceptedChannels` global. `RecipientPreferencesResponse`: añade `recipientIdentityId` (nulo sin registro), `categories`
   (siempre las cuatro, con `locked: true` en la crítica) y conserva `optedOutAll` y `updatedAt`. Reutiliza `TemplateCategory` de D1.
3. Respuestas 400 con `code` `INVALID_PREFERENCES` y `CRITICAL_CATEGORY_LOCKED`; 403 por `subject` ajeno.
4. Solicitud de envío (`/notifications`, `/notifications/batches`): añadir `templateId` (uuid, opcional) y `recipientIdentityId` (uuid,
   opcional). Coordinar con D3 para que `templateId` tenga una sola definición.
5. Descripciones de `/recipients/preferences` actualizadas: ya no se describe la 013 (preferencia solo por canal).

## Riesgos y excepciones (Principio VII)

- Bloqueo por el YAML (Contrato) y por la integración de B1, D1 y E5 en `main`: sin ellas la rama no compila. Dueño: andrualv, 2026-12-15.
- Cambio en el borrado de E5 para eliminar preferencias: dueño del cambio, quien integre E5; fecha 2026-12-15. Si E5 ya está en `main`,
  es una tarea de esta historia.
- Lectura extra de la notificación y de preferencias por despacho: sin caché. Dueño andrualv, 2026-12-15 (medir en la prueba de carga).
- Sin historial de consentimiento ni base legal validada: ver spec. Dueño andrualv, 2026-12-31.
