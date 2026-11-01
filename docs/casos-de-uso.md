# Casos de uso — Notification-uco

Formato: Actor / Puerto de entrada / Precondición / Postcondición / Trazabilidad / Flujo principal /
Flujos alternos. Portado desde Notion (página "casos-de-uso", estado 2026-08-29/31) el 2026-09-11.

## CU-01 · Enviar notificación

| Campo | Contenido |
|---|---|
| Actor | Sistema cliente |
| Puerto de entrada | `SendNotificationUseCase.send(SendNotificationCommand)` |
| Precondición | Sistema cliente autenticado (o `X-Tenant-Id` válido mientras el placeholder de Seguridad esté vigente) y el canal existe en el catálogo |
| Postcondición | La notificación queda aceptada, registrada y encolada para despacho |
| Trazabilidad | RF-01, RF-03 a RF-06, RF-23 |

**Flujo principal:** 1) el sistema cliente envía canal, destinatario, contenido, prioridad y un
`externalId` propio · 2) se valida el canal contra `ChannelCatalogPort.findActiveRoute` · 3) si ya
existe una notificación con el mismo `(tenantId, externalId)` se devuelve esa, sin duplicar · 4) si
no existe, se construye el agregado (`Notification.accept`), se persiste en `PENDING` y se encola ·
5) se responde con el identificador de seguimiento.

**Flujos alternos:** canal inexistente o deshabilitado → `ChannelNotAvailableException`. Contenido
que no cumple el esquema → se rechaza en validación de entrada, antes de tocar el dominio.

## CU-02 · Enviar lote de notificaciones

| Campo | Contenido |
|---|---|
| Actor | Sistema cliente |
| Puerto de entrada | `SendNotificationBatchUseCase.sendBatch(SendNotificationBatchCommand)` |
| Precondición | Sistema cliente autenticado y todos los canales del lote existen |
| Postcondición | El lote queda registrado y sus notificaciones encoladas |
| Trazabilidad | RF-02 |

**Flujo principal:** 1) se envía un conjunto de notificaciones en una sola solicitud, con id de lote
propio o generado · 2) cada elemento se valida contra el esquema de su canal · 3) cada elemento
válido sigue el camino de CU-01 (idempotencia incluida) · 4) se responde con el id del lote y el
resultado individual de cada elemento.

**Flujos alternos:** uno o más elementos inválidos → el lote se acepta parcialmente, se informa el
detalle por elemento. **Nota de diseño:** no es un bucle sobre CU-01 a nivel de adaptador — el lote
es su propia unidad de seguimiento.

## CU-03 · Despachar notificación

| Campo | Contenido |
|---|---|
| Actor | El propio componente (`NotificationDispatchListener`) |
| Puerto de entrada | `DispatchNotificationUseCase.dispatch(NotificationId)` |
| Precondición | Existe una notificación encolada y un proveedor habilitado para su canal |
| Postcondición | La notificación alcanza un estado final o queda pendiente de reintento |
| Trazabilidad | RF-07 a RF-14 |

**Flujo principal:** 1) se toma la notificación de la cola · 2) se consulta
`ChannelCatalogPort.findActiveRoute` y el `providerId` preferente · 3) `NotificationSenderPort.send`
invoca al adaptador · 4) según el `SendOutcome`, se invoca `markDelivered`, `markRecoverable` o
`markFailed`.

**Flujos alternos:** fallo recuperable → reintento con espera creciente (`RetryPolicy`). Proveedor
preferente sigue fallando → conmutación al siguiente proveedor (RF-12, puerto y lógica pendientes —
ver Fase 6). Reintentos agotados → `RECOVERABLE`, a la espera del reencolado
(`RequeuePendingNotificationsService`, HU2-030 — ya construido). Fallo definitivo → `FAILED`.

## CU-04 · Consultar estado de una notificación

| Campo | Contenido |
|---|---|
| Actor | Sistema cliente, operador |
| Puerto de entrada | `GetNotificationStatusUseCase.getStatus(GetNotificationStatusQuery)` |
| Precondición | Se dispone del identificador y de permiso para consultarlo |
| Postcondición | El actor conoce estado, canal, proveedor y marca de tiempo |
| Trazabilidad | RF-16 |

**Flujo principal:** 1) se solicita el estado por identificador · 2) se verifica que el `tenantId`
del solicitante coincida con el de la notificación · 3) se devuelve `NotificationStatusView`.

**Flujos alternos:** identificador inexistente o de otro cliente → `404` sin distinguir entre ambos
casos, para no revelar si existe bajo otro cliente.

## CU-05 · Consultar histórico y trazabilidad

| Campo | Contenido |
|---|---|
| Actor | Operador |
| Puerto de entrada | `SearchNotificationHistoryUseCase.search(NotificationSearchCriteria)` |
| Precondición | Operador autenticado con permiso de consulta |
| Postcondición | El operador dispone del recorrido completo de las notificaciones del criterio |
| Trazabilidad | RF-17, RF-18 |

**Flujo principal:** 1) se busca por identificador, destinatario, canal, estado o rango de fechas ·
2) `NotificationRepository.search` devuelve las notificaciones con sus `DeliveryAttempt` · 3) se abre
una notificación puntual y se ve su recorrido completo.

**Flujos alternos:** búsqueda sin resultados → lista vacía, no error.

**Estado**: pendiente de construir (Fase 3).

## CU-06 · Reintentar envío

| Campo | Contenido |
|---|---|
| Actor | Operador |
| Puerto de entrada | `RetryNotificationUseCase.retry(RetryNotificationCommand)` |
| Precondición | Existe una notificación en `FAILED` o `RECOVERABLE` |
| Postcondición | Vuelve al flujo de despacho y el reintento queda auditado como manual |
| Trazabilidad | RF-15 |

**Flujo principal:** 1) el operador localiza la notificación y ordena su reintento · 2)
`StatusTransitionPolicy` verifica que el estado admite la transición a `PENDING` · 3) se reencola
(`Notification.requeue`) y se registra que fue manual.

**Flujos alternos:** estado no admite reintento (ej. `DELIVERED`) → `InvalidStatusTransitionException`.

**Estado**: pendiente de construir (Fase 3). Complementa, no reemplaza, al reencolado automático
(HU2-030, ya construido) — ver ADR-0010 y `docs/adr/0010-concurrencia-replicas-cache.md`.

## CU-07 · Registrar canal

| Campo | Contenido |
|---|---|
| Actor | Administrador del componente |
| Puerto de entrada | `RegisterChannelUseCase.register(RegisterChannelCommand)` |
| Precondición | El administrador tiene permiso de administración |
| Postcondición | El canal queda disponible para recibir solicitudes, sin desplegar código |
| Trazabilidad | RF-19, RF-21 |

**Flujo principal:** 1) se declara el canal, su esquema y su política de reintentos · 2) se valida
que el esquema esté bien formado · 3) `ChannelCatalogAdminPort.registerChannel` lo incorpora al
catálogo persistido.

**Flujos alternos:** canal ya existe → se ofrece actualizar. Esquema mal formado → se rechaza.
**Depende de:** el catálogo dinámico (Fase 6) — sin esa pieza, este CU no cumple "sin desplegar
código".

## CU-08 · Registrar proveedor

| Campo | Contenido |
|---|---|
| Actor | Administrador del componente |
| Puerto de entrada | `RegisterProviderUseCase.register(RegisterProviderCommand)` |
| Precondición | El adaptador del proveedor ya existe en el código (ADR-0007) y el canal existe |
| Postcondición | El proveedor queda habilitado y participa en el enrutamiento del canal |
| Trazabilidad | RF-20, RF-21 |

**Flujo principal:** 1) se selecciona el proveedor (ya descubierto por `ProviderRegistrySender`) y
los canales que atenderá, con orden de preferencia · 2) se verifica que las credenciales requeridas
estén configuradas · 3) `ChannelCatalogAdminPort.registerProvider` incorpora la asociación.

**Flujos alternos:** falta credencial → se registra deshabilitado, se informa qué falta, nunca en
silencio. No existe adaptador para ese `providerId` → se rechaza. **Importante:** este CU NO integra
un proveedor externo nuevo — eso siempre requiere escribir un adaptador `NotificationProvider`
(RNF-08, ≤1 jornada).

## CU-09 · Gestionar preferencias del destinatario

| Campo | Contenido |
|---|---|
| Actor | Destinatario final, operador |
| Puerto de entrada | `ManageRecipientPreferencesUseCase` |
| Precondición | El destinatario está identificado |
| Postcondición | Las preferencias quedan registradas y se aplican en envíos posteriores |
| Trazabilidad | RF-22 |

**Flujo principal:** 1) se consultan las preferencias actuales (`get`) · 2) se declara por qué
canales acepta ser contactado, o baja total · 3) `RecipientPreferencePort.save` registra con marca
de tiempo.

**Flujos alternos:** baja total → CU-01/CU-03 deben excluir al destinatario de envíos no esenciales
antes de despachar.

**Estado**: pendiente de construir (Fase 3).

## CU-10 · Autenticar y autorizar cada solicitud 🔧

| Campo | Contenido |
|---|---|
| Actor | Componente de Seguridad, Sistema cliente |
| Puerto de entrada | Ninguno propio — hoy es un filtro (`SecurityPlaceholderConfiguration`), no un puerto de aplicación |
| Precondición | La solicitud llega con credencial (token o, hoy, `X-Tenant-Id`) |
| Postcondición | La solicitud continúa con `TenantId` resuelto, o se rechaza con 401/403 |
| Trazabilidad | RNF-13, RNF-14, RES-04 |

**Flujo principal:** 1) el filtro intercepta toda petición · 2) valida el token (o lee
`X-Tenant-Id` en el placeholder) · 3) resuelve `TenantId` y lo adjunta al contexto · 4) delega al
puerto de entrada correspondiente (CU-01 a CU-09).

**Flujos alternos:** token inválido/ausente → 401. Tenant sin permiso → 403. **Bloqueado por
DEP-01** del equipo de Seguridad — mientras tanto, el placeholder `X-Tenant-Id` es aceptable solo en
desarrollo.

## CU-11 · Sincronizar configuración transversal 🔧

| Campo | Contenido |
|---|---|
| Actor | Componente de Parámetros |
| Puerto de entrada | No definido — brecha |
| Precondición | Parámetros publica un cambio de configuración transversal |
| Postcondición | El componente opera con la configuración más reciente, o la última conocida si Parámetros falla |
| Trazabilidad | RF-24, SUP-02 |

**Flujo principal:** 1) Parámetros publica un cambio (mecanismo aún sin cerrar, SUP-02) · 2) el
componente lo aplica en caliente · 3) rige a partir de la siguiente operación, sin reinicio.

**Flujos alternos:** Parámetros no disponible → se opera con la última configuración conocida.
**Brecha:** diseñar `ConfigurationSyncPort` cuando el otro equipo publique el contrato. Diferido, no
descartado.

## CU-12 · Resolver contenido desde plantilla 🔧

| Campo | Contenido |
|---|---|
| Actor | Sistema cliente |
| Puerto de entrada | No definido — brecha |
| Precondición | Existe una plantilla registrada para el tipo de notificación |
| Postcondición | El contenido se resuelve desde la plantilla antes de aceptar la notificación (CU-01) |
| Trazabilidad | RF-25 (baja prioridad) |

**Flujo principal:** 1) se envía `templateId` y variables en vez de contenido literal · 2) se
resuelve la plantilla y se sustituyen variables · 3) sigue el flujo normal de CU-01.

**Flujos alternos:** plantilla inexistente o variable faltante → se rechaza. **Brecha:** el concepto
`Plantilla` está en el modelo conceptual de v1.0 sin código todavía. Diferido, no descartado.

## CU-13 · Programar envío futuro 🔧

| Campo | Contenido |
|---|---|
| Actor | Sistema cliente |
| Puerto de entrada | No definido — brecha |
| Precondición | — |
| Postcondición | La notificación queda pendiente de activación hasta la fecha programada |
| Trazabilidad | RF-26 (baja prioridad) |

**Flujo principal:** 1) se envía con fecha/hora futura de envío, sin encolar para despacho inmediato
· 2) un proceso programado la activa en la fecha indicada · 3) sigue el flujo de CU-01/CU-03.

**Flujos alternos:** fecha en el pasado → a decidir. **Brecha:** exige un campo `scheduledAt` o
estado adicional — cambia el modelo de dominio de 5 (hoy 6, incluye `DISCARDED`) estados. Diferido,
no descartado.
