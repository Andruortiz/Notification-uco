# Feature Specification: Preferencias del destinatario por categoría y canal (HU2-132)

**Feature Branch**: `feature/HU2-132-preferencias-categoria-canal` | **Created**: 2026-10-25 | **Status**: Draft

**Input**: "Como DEST quiero elegir por qué canal recibo cada tipo de aviso para controlar lo que me llega." Criterios: las
notificaciones críticas (seguridad) no se pueden silenciar; la categoría la define la plantilla; se cierran antes las
clarificaciones Q1 a Q4 de la spec 013.

**Trazabilidad**: CU-09. Reemplaza a la spec 013 (HU2-029/030, 0 de 19 tareas, nunca implementada) y la amplía de "canal" a
"categoría y canal". Depende de B1 (tenant y sujeto salen de la sesión, spec 029), B2 (roles, spec 039), D1 (`TemplateCategory`,
spec 035, HU2-119), D3 (plantilla guardada en `Notification`, spec 043, HU2-127) y E5 (`RecipientIdentity`, spec 036, HU2-120).
Se integra después de D3. Backend únicamente; la UI vive en Front-Notification-mfe y F2 la embebe.

## Clarifications

Sesión 2026-10-25. Decisiones tomadas en el lote de aprobación; las marcadas con dueño quedan como límite o riesgo declarado.
Afectan al FR indicado.

- **Q1 (013) Momento de aplicación** (FR-007, FR-008): en el despacho, **antes** de reservar la notificación. La premisa de la 013
  ("la reserva ocurre en memoria") ya no vale: `reserveForDispatch` marca `IN_PROCESS` de forma atómica y `IN_PROCESS` no puede
  pasar a `DISCARDED`. Se lee la notificación, se evalúa, y solo si se permite se reserva. No se añade ninguna transición.
- **Q2 (013) Lista vacía** (FR-003): para una categoría, `acceptedChannels` vacío o ausente = todos los canales. "No me contacten"
  tiene una sola forma por categoría (`optedOut: true`) y una global (`optedOutAll: true`).
- **Q3 (013) Canales no validados** (FR-004): los nombres de canal se normalizan (recorte y mayúsculas, como `ChannelType`) y no se
  validan contra el catálogo; la preferencia debe sobrevivir a que un canal se deshabilite. La **categoría sí** se valida contra
  `TemplateCategory`: una desconocida da 400. El riesgo del canal mal escrito se acepta y se mitiga porque la respuesta devuelve lo
  guardado.
- **Q4 (013) Fallo de lectura** (FR-009): no se envía ni se descarta; el despacho falla y la notificación sigue `PENDING` para el
  reintento y la DLQ existentes (Principio VIII).
- **Q5 Identidad** (FR-001): el destinatario es la `RecipientIdentity` de E5 (UUID propio, por tenant), no la dirección ni el
  `recipientId` de la notificación. Con rol CLIENTE solo se opera sobre el `subject` de la sesión; ADMINISTRADOR_APLICACION puede
  indicar otro `subject` (misma regla que E5, FR-013). Se crea la identidad al primer `PUT` si no existe.
- **Q6 Cómo llega la categoría y la identidad a la notificación** (FR-007): D3 es dueña de `templateId`, `templateValues` y de la
  referencia de plantilla guardada en `Notification` (categoría y `critical`). F1 no define `templateId` ni `category`: solo añade
  `recipientIdentityId` (uuid, opcional) a la solicitud y a `Notification`, y lee la categoría de la referencia de D3. Se evalúa
  **toda** notificación que traiga `recipientIdentityId`, incluidas las `IN_APP` de E6 y las del envío por usuario de F6; F6 lo
  rellena en cada comando. **Límite**: sin identidad no se evalúa (no hay a quién aplicar la preferencia); lo asume el remitente.
  Dueño andrualv, 2026-12-15.
- **Q7 Crítica** (FR-005): `TemplateCategory.critical()` (hoy `SEGURIDAD`) nunca se suprime: ni `optedOutAll`, ni un canal
  restringido. Un `PUT` que la silencie o restrinja se rechaza con 400 `CRITICAL_CATEGORY_LOCKED` (NTF-1018) en vez de ignorarse,
  para que la UI no muestre un control sin efecto.
- **Q8 Sin categoría** (FR-006): una notificación con identidad pero sin categoría solo se ve afectada por `optedOutAll`; no hay
  canal que comparar por categoría.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Declarar preferencias por categoría y canal (P1)

Como destinatario (vía el cliente que lo representa) quiero consultar y reemplazar mis preferencias: por categoría, aceptar solo
ciertos canales o no recibirla, o una baja total, para decidir qué aviso recibo y por dónde.

**Independent Test**: consultar sin registro (defaults); reemplazar con "MARKETING solo EMAIL, ACADEMICO sin SMS"; consultar y ver
lo guardado con `updatedAt`; reemplazar con `optedOutAll`; volver a defaults.

1. **Given** una identidad sin preferencias (o sin identidad), **When** consulta, **Then** 200 con las cuatro categorías, todas
   aceptando todo, `optedOutAll` falso y `updatedAt` nulo; la consulta no crea la identidad.
2. **Given** una actualización válida, **When** se guarda, **Then** la respuesta y la consulta siguiente devuelven lo guardado,
   canales normalizados y deduplicados, con `updatedAt`.
3. **Given** una categoría desconocida, un canal vacío o solo espacios, o una categoría repetida, **When** se envía, **Then** 400
   `INVALID_PREFERENCES` (NTF-1017) y lo vigente no cambia.
4. **Given** `optedOutAll` verdadero junto a listas, **When** se guarda, **Then** prevalece la baja total y las listas se guardan
   vacías.
5. **Given** una sesión CLIENTE con `subject` ajeno, **When** consulta o actualiza, **Then** 403.
6. **Given** una sesión ADMINISTRADOR_APLICACION con `subject` ajeno, **When** consulta o actualiza, **Then** 200 sobre esa identidad.

### User Story 2 - La categoría crítica no se silencia (P1)

Como responsable de seguridad quiero que los avisos de seguridad lleguen siempre, para que nadie quede sin alertas de su cuenta.

**Independent Test**: dar de baja total a un destinatario y despachar un aviso de seguridad (se entrega) y uno de marketing (se
descarta); intentar restringir `SEGURIDAD` y recibir 400.

1. **Given** `optedOutAll` y una notificación con categoría `SEGURIDAD`, **When** se despacha, **Then** se entrega.
2. **Given** un `PUT` con `SEGURIDAD` restringida a un canal o con `optedOut`, **Then** 400 `CRITICAL_CATEGORY_LOCKED`.
3. **Given** `SEGURIDAD` en el `PUT` sin restricción, **Then** se acepta y se devuelve igual.

### User Story 3 - El despacho respeta las preferencias (P1)

Como componente quiero que el despacho consulte las preferencias antes de reservar, para no contactar a quien pidió no recibir esa
categoría por ese canal.

**Independent Test**: E2E con Mongo y RabbitMQ: `MARKETING` solo EMAIL; un envío SMS termina `DISCARDED` sin llamada al proveedor ni
intento; un envío EMAIL (control positivo) termina `DELIVERED`.

1. **Given** una notificación con identidad y categoría cuya categoría está en `optedOut`, o `optedOutAll` (no crítica), o cuyo
   canal no está entre los aceptados, **When** se despacha, **Then** `DISCARDED`, sin proveedor, sin intento, publicada como
   cualquier cambio de estado.
2. **Given** una preferencia que lo permite, **Then** el despacho sigue exactamente igual que hoy.
3. **Given** una notificación sin identidad, **Then** se despacha como hoy (sin consulta de preferencias).
4. **Given** una baja declarada antes de la reserva (también antes de un reintento), **Then** se descarta. **Given** una baja
   declarada después de la reserva, **Then** el envío en curso termina `DELIVERED` (SC-010). Una notificación ya `DELIVERED` o
   `FAILED` no cambia (no retroactiva).
5. **Given** el almacén de preferencias no disponible, **Then** cero peticiones al proveedor, la notificación sigue `PENDING` y el
   mensaje entra en reintento y DLQ.

### User Story 4 - Aislamiento por tenant y borrado (P2)

Como responsable quiero que las preferencias no crucen tenants y se borren con la identidad, para cumplir habeas data.

1. **Given** dos tenants con el mismo `subject`, **When** uno declara baja total, **Then** el otro ve defaults y sus envíos se
   entregan.
2. **Given** `POST /device-erasures` de E5 para un `subject`, **Then** también desaparecen sus preferencias.

### Edge Cases

- Dos `PUT` simultáneos del mismo destinatario: un único registro, igual a uno de los dos.
- Un `PUT` reemplaza solo los campos de F1 y conserva los que F3 y F5 agreguen al mismo documento.
- Reentrega de un despacho ya descartado: se comporta como cualquier reentrega de una notificación terminal.
- La baja durante un envío en curso no lo interrumpe; rige desde el siguiente despacho.
- Plantilla archivada o inexistente al aceptar: el rechazo es el de D3; F1 no lo redefine.

## Requirements *(mandatory)*

- **FR-001**: `GET` y `PUT /recipients/preferences` operan sobre la `RecipientIdentity` del `subject` (Q5). Roles permitidos:
  CLIENTE (solo su propio `subject`) y ADMINISTRADOR_APLICACION (cualquier `subject`), según B2.
- **FR-002**: Sin registro, la consulta devuelve defaults y nunca falla; indica `updatedAt` nulo.
- **FR-003**: El `PUT` reemplaza por completo `optedOutAll` y, por categoría, `optedOut` y `acceptedChannels` (vacío = todos). La
  respuesta devuelve siempre las cuatro categorías tal como quedaron.
- **FR-004**: Canales normalizados y deduplicados; entrada vacía rechaza todo con 400; categoría desconocida o repetida, 400
  `INVALID_PREFERENCES` (NTF-1017) (Q3).
- **FR-005**: La categoría crítica no se suprime nunca y su restricción se rechaza con `CRITICAL_CATEGORY_LOCKED` (NTF-1018) (Q7).
- **FR-006**: Regla única de supresión, no crítica: `optedOutAll` **o** `optedOut` de la categoría **o** (`acceptedChannels` no
  vacío y sin el canal, sin distinguir mayúsculas). Sin categoría solo cuenta `optedOutAll` (Q8).
- **FR-007**: Antes de reservar, el despacho lee la notificación y, si trae `recipientIdentityId`, evalúa FR-006 (Q1, Q6). Orden de
  guardas: primero esta evaluación (`DISCARDED`, sin proveedor); después la compuerta de D5 (fallo permanente). Una notificación
  descartada aquí no llega a evaluar D5. Si suprime: `DISCARDED`, persistida y publicada, sin proveedor ni intento.
- **FR-008**: La evaluación ocurre en cada despacho, incluidos los reintentos, con la preferencia vigente; no altera terminales. Una
  baja posterior a la reserva no interrumpe el envío ya reservado; rige desde el siguiente despacho.
- **FR-009**: Si la lectura de preferencias falla, el despacho falla trazable y la notificación conserva `PENDING` (Q4).
- **FR-010**: Preferencias por `(tenantId, recipientIdentityId)`, único; nada cruza tenants; sin direcciones, tokens ni `subject` en
  logs, métricas ni respuestas.
- **FR-011**: Dos `PUT` concurrentes no duplican ni mezclan; el `PUT` solo escribe campos de F1 (extensible por F3, F4, F5).
- **FR-012**: El borrado por titular elimina también las preferencias de esa identidad. F1 registra un `RecipientDataEraser` en el
  `EraseRecipientDataUseCase` único que introduce F6; no pide cambios sueltos a E5.
- **FR-013**: Cada descarte registra un log y un contador `notification.preferences.suppressed{reason,category}` sin identificadores.
- **FR-014**: La aceptación no cambia salvo por el campo opcional `recipientIdentityId` (Q6); una notificación para un destinatario
  dado de baja se acepta como cualquier otra.
- **FR-015**: Puerto estable para F2 a F5: `QueryRecipientPreferencesUseCase` (consulta con defaults) y `PreferenceGate.evaluate`
  (decisión con motivo), definidos en [plan.md](plan.md#puertos-estables-para-f2-f5).

## Success Criteria *(mandatory)*

- **SC-001**: Consulta sin registro devuelve defaults sin error en el 100 % de los casos (E2E).
- **SC-002**: Una notificación aceptada y suprimida queda `DISCARDED` en ≤5 s desde su aceptación, con cero peticiones al proveedor
  y cero intentos, con aserción explícita de `Duration`.
- **SC-003**: Con `MARKETING` solo EMAIL: SMS `DISCARDED` y EMAIL `DELIVERED` en la misma prueba (control positivo).
- **SC-004**: Con `optedOutAll`, `SEGURIDAD` se entrega y `MARKETING` se descarta, en la misma prueba.
- **SC-005**: Dos tenants con el mismo `subject`: cero efectos cruzados en consulta y despacho.
- **SC-006**: Con el almacén caído: cero peticiones al proveedor y estado `PENDING`.
- **SC-007**: Diez `PUT` concurrentes dejan un registro igual a uno de ellos.
- **SC-008**: Sin regresiones: notificaciones sin identidad se entregan igual que antes.
- **SC-009**: Tras `POST /device-erasures`, la consulta de preferencias de ese `subject` devuelve defaults y el documento no existe.
- **SC-010**: Una baja declarada tras la reserva no interrumpe el envío en curso (`DELIVERED`); la misma baja antes de la reserva lo
  descarta.

## Datos personales (habeas data)

Supuestos, pendientes de validación por el responsable de datos de la UCO (dueño: andrualv, 2026-12-15).

- **Dato**: preferencias de contacto ligadas a `recipientIdentityId`, que identifica indirectamente a una persona.
- **Base legal**: ejercicio del derecho del titular a decidir cómo se le contacta (consentimiento y revocación); el servicio actúa
  como encargado del tratamiento del tenant (responsable).
- **Retención**: mientras exista la identidad, en la tabla única de retención del lote; sin caducidad por inactividad, porque borrar
  una baja reactivaría el contacto no deseado.
- **Borrado**: físico, con el borrado por titular de E5 (FR-012). Sin historial de consentimiento: no se prueba cuándo se dio una
  baja anterior (riesgo abajo).

## Out of Scope

Horario de silencio (F3), digest (F4), baja con un clic y consentimiento versionado (F5), UI y embebible (F2), directorio y
fan-out por usuario (F6), historial de cambios, motivo de descarte persistido, autenticación del destinatario final, validación de
canales contra el catálogo, caché de preferencias.

## Risks

- **Precondición de roles por sesión** (bloqueante): con sesión del PDP el principal es CLIENTE; la rama ADMINISTRADOR_APLICACION no
  se cumple en producción hasta que Seguridad traiga los roles en la sesión o se consulte `/api/v1/authorize`. Dueño: Seguridad,
  2026-12-15.
- **Abuso conocido**: quien pueda elegir un `templateId` de categoría `SEGURIDAD` fuerza entrega crítica a destinatarios dados de
  baja. F1 no restringe quién envía categoría crítica; D1 y D3 deben limitar la creación de plantillas `SEGURIDAD` a
  `EDITOR_PLANTILLAS` y su uso a un rol privilegiado. Límite declarado; dueño andrualv, 2026-12-15.
- **Dependencia de D3/F6 para poblar plantilla e identidad en producción**: hasta entonces solo se evalúan las solicitudes que traigan
  `recipientIdentityId`. Dueño: andrualv, 2026-12-15.
- **Una lectura extra por despacho** (Q1, FR-007): aprobada sin caché; medir. Dueño andrualv, 2026-12-15.
- **Sin historial de consentimiento**: dueño andrualv, revisión 2026-12-31 (F5 lo aborda).
