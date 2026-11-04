# Feature Specification: Plantillas de WhatsApp aprobadas (HU2-129)

**Feature Branch**: `feature/HU2-129-plantillas-whatsapp-aprobadas`

**Created**: 2026-10-25

**Status**: Pendiente de aprobacion

## Contexto

Como equipo tecnico (ET) quiero vincular una plantilla propia (D1, spec 035) con la plantilla que el proveedor de WhatsApp ya aprobo
y ver su estado de aprobacion, para saber cuando puedo usarla. WhatsApp solo admite en conversaciones iniciadas por la empresa
plantillas aprobadas; hoy el envio con `providerTemplate` (spec 028) acepta cualquier `templateId` con forma valida y el rechazo
llega tarde, como fallo permanente del proveedor. Esta historia mueve esa verificacion antes del envio y la hace visible.

Alcance: solo backend. La UI vive en Front-Notification-mfe y consume la tabla de rutas de este documento.

## Clarifications

### Session 2026-10-25

- Q: Proveedor de las plantillas aprobadas -> A: Twilio ahora (supuesto: decision del usuario de 2026-10-24, no respaldada en el lote nivel 2; confirmar); si E1 elige Meta se cambia solo el
  adaptador de salida de [FR-009](#functional-requirements). Todo lo que dependa de E1 queda como limite en "Limites".
- Q: Que significa "publicar para produccion" (D1 solo tiene `ACTIVA`/`ARCHIVADA`, sin publicacion) -> A: la publicacion es un estado
  del vinculo, no un estado nuevo de la plantilla D1: `BORRADOR`, `PUBLICADA`, `SUSPENDIDA`. Solo una plantilla WHATSAPP necesita
  publicarse; las de otros canales no se ven afectadas. D1 no cambia.
- Q: Donde se aplica el bloqueo -> A: en la publicacion y en el despacho del envio por plantilla (D3, HU2-127), nunca al aceptar.
  Solo un envio por `templateId` exige vinculo `PUBLICADA`; sin vinculo falla como permanente en el despacho. El cuerpo directo (HU2-112)
  no cambia. El render
  y la vista previa (D1, D2) siguen funcionando con plantillas sin aprobar: son borradores.
- Q: Como se sincroniza el estado -> A: bajo demanda (`POST /template-approval-syncs`), al publicar (siempre con lectura fresca) y por
  un barrido periodico (`notification.whatsapp-templates.sync.interval`, defecto 15 min) sobre vinculos `PENDIENTE`, o `APROBADA`
  con publicacion `PUBLICADA`. Sin callbacks del proveedor en esta historia (ver "Limites"). El barrido es seguro con varias replicas
  porque cada vinculo se toma con una actualizacion atomica condicionada (arrendamiento corto).
- Q: Plantilla rechazada que estaba en uso -> A: si el barrido o una sincronizacion detecta `RECHAZADA` o `PAUSADA` sobre un vinculo
  `PUBLICADA`, pasa a `SUSPENDIDA` en el acto: la compuerta bloquea el envio nuevo y queda un log y una metrica. Las notificaciones
  ya aceptadas con esa plantilla no se pierden: el despacho recibe la compuerta cerrada como fallo permanente trazable
  (Principio VIII), sin failover (otro proveedor tampoco tiene la plantilla aprobada). Nunca vuelve sola a `PUBLICADA`: exige publicar
  de nuevo, con el estado en `APROBADA`.
- Q: Idioma -> A: la plantilla del proveedor lleva el idioma con que se aprobo; el vinculo lo guarda como informativo
  (`providerLanguage`) cuando el proveedor lo informa. No bloquea (las etiquetas difieren: `es_MX` frente a `es-CO`); ver P2.
- Q: Variables -> A: el proveedor usa variables posicionales (`1`, `2`). El vinculo declara `variableMap` (posicion -> nombre de variable
  de la plantilla D1) para que D3 construya `providerTemplate.variables`. Cada nombre debe ser variable declarada de la plantilla.
- Q: Que plantillas del proveedor se pueden vincular -> A: solo las de una lista (`ProviderTemplateAllowEntry`) que gestiona el
  ADMINISTRADOR_PLATAFORMA o el ADMINISTRADOR_APLICACION del tenant; no se acepta un `HX...` libre. Al agregar a la lista se verifica que
  exista en el proveedor.
- Q: Aislamiento -> A: la cuenta del proveedor es de plataforma, no de tenant. Un `providerTemplateId` solo puede estar en la lista de un
  tenant a la vez (unico en `(providerId, providerTemplateId)`). «No existe en el proveedor», «no esta en la lista de este tenant» y
  «listada por otro tenant» responden el mismo 404 uniforme `PROVIDER_TEMPLATE_NOT_FOUND` (NTF-3071), sin revelar si esta en uso.
- Q: Roles (B2 entra primero) -> A: ver FR-012 y la tabla de rutas; roles de B2: ADMINISTRADOR_PLATAFORMA, ADMINISTRADOR_APLICACION,
  EDITOR_PLANTILLAS, OPERADOR, AUDITOR, CLIENTE.
- Q: Integracion con D3 y despacho -> A: D5 es duena de `TemplateProductionGate`, del uso de `variableMap` para
  `providerTemplate.variables` y del desvio a `providerTemplate` en WHATSAPP; se integra despues de D3 y edita solo el despacho de
  `SendNotificationService`; D3 deja el punto de extensión. Orden de guardas previas al despacho: primero las preferencias de F1 (`DISCARDED`) y despues la
  compuerta de D5 (fallo permanente).
- Q: Prueba E2E -> A: proveedor doble. Un servidor HTTP simulado imita la consulta de aprobacion; ninguna prueba toca Twilio real.

## User Scenarios & Testing

### User Story 1 - Vincular y ver el estado de aprobacion (Priority: P1)

El ET vincula una plantilla WHATSAPP de D1 con el identificador de la plantilla aprobada en el proveedor y ve su estado.

**Independent Test**: vincular, leer el vinculo con `APROBADA` y `lastSyncedAt`.

**Acceptance Scenarios**:

1. **Given** una plantilla WHATSAPP `ACTIVA` y un `providerTemplateId` con forma `HX` + 32 hexadecimales, **When** se vincula, **Then**
   responde 201 con el estado leido del proveedor en ese momento y publicacion `BORRADOR`.
2. **Given** una plantilla de canal distinto de WHATSAPP o `ARCHIVADA`, **When** se vincula, **Then** 400/409 con codigo propio.
3. **Given** un identificador que el proveedor no conoce, que no esta en la lista del tenant o que lista otro tenant, **When** se vincula
   o se agrega a la lista, **Then** el mismo 404 `PROVIDER_TEMPLATE_NOT_FOUND` (NTF-3071) y no se guarda nada.
4. **Given** una plantilla del proveedor en la lista del tenant, **When** se vincula, **Then** 201; sin estar en la lista, 404 como arriba.
5. **Given** un vinculo en `BORRADOR`, **When** se vincula otro identificador, **Then** reemplaza al anterior; en `PUBLICADA` o
   `SUSPENDIDA`, 409 `TEMPLATE_PUBLISHED` (despublicar primero).
6. **Given** `variableMap` con un nombre que la plantilla no declara, **When** se vincula, **Then** 400 `UNDECLARED_TEMPLATE_VARIABLE`.

---

### User Story 2 - No publicar sin aprobacion (Priority: P1)

**Independent Test**: publicar con el proveedor en `PENDIENTE` y recibir 409; aprobar en el doble, publicar y recibir 200.

**Acceptance Scenarios**:

1. **Given** estado `PENDIENTE`, `RECHAZADA` o `PAUSADA`, **When** se publica, **Then** 409 `PROVIDER_TEMPLATE_NOT_APPROVED` con el estado y,
   si existe, el motivo de rechazo; el vinculo sigue `BORRADOR`.
2. **Given** que la copia local dice `APROBADA` pero el proveedor ya la rechazo, **When** se publica, **Then** la publicacion relee
   al proveedor, ve `RECHAZADA` y responde 409 (nunca se publica con dato rancio).
3. **Given** estado `APROBADA` en lectura fresca, **When** se publica, **Then** 200 con `PUBLICADA`.
4. **Given** el proveedor inalcanzable o deshabilitado, **When** se publica, **Then** 503 `PROVIDER_TEMPLATE_UNAVAILABLE`; no se publica.
5. **Given** una plantilla WHATSAPP sin vinculo o en `BORRADOR`, **When** el despacho consulta la compuerta, **Then** cerrada; para canales distintos
   de WHATSAPP, abierta.

---

### User Story 3 - Sincronizar el estado y reaccionar al rechazo (Priority: P1)

**Independent Test**: publicada y aprobada; el doble pasa a `RECHAZADA`; tras una sincronizacion el vinculo es `SUSPENDIDA` y la
compuerta se cierra.

**Acceptance Scenarios**:

1. **Given** un vinculo `PENDIENTE`, **When** el proveedor lo aprueba y corre la sincronizacion, **Then** pasa a `APROBADA`
   (sigue en `BORRADOR`: aprobar no publica).
2. **Given** `PUBLICADA` y el proveedor responde `RECHAZADA` o `PAUSADA`, **When** corre la sincronizacion, **Then** `SUSPENDIDA`,
   compuerta cerrada, y la lectura incluye `rejectionReason` si lo hay.
3. **Given** que el proveedor falla durante el barrido, **When** termina, **Then** se conserva el ultimo estado conocido, se marca
   `lastSyncError` (codigo cerrado) y el vinculo `PUBLICADA` no se suspende por un fallo del proveedor.
4. **Given** dos replicas corriendo el barrido, **When** coinciden, **Then** cada vinculo se consulta una sola vez por ciclo.
5. **Given** `SUSPENDIDA` y el proveedor de nuevo en `APROBADA`, **When** se sincroniza, **Then** sigue `SUSPENDIDA` hasta publicar.

---

### User Story 4 - Despublicar y consultar (Priority: P2)

1. **Given** `PUBLICADA` o `SUSPENDIDA`, **When** se despublica, **Then** `BORRADOR` y la compuerta se cierra.
2. **Given** una plantilla D1 que se archiva, **When** se consulta la compuerta, **Then** cerrada (el vinculo se conserva).

---

### User Story 5 - Aislamiento por tenant (Priority: P1)

Un tenant no lee, sincroniza, publica ni ve vinculos de otro. El tenant sale de la sesion del PDP, nunca del cuerpo ni de la query.

1. **Given** vinculos de dos tenants, **When** uno consulta o publica por `templateId` ajeno, **Then** 404.
2. **Given** el barrido, **When** actualiza vinculos de dos tenants, **Then** cada cambio queda solo en su tenant.

### Edge Cases

- Edicion concurrente: el vinculo lleva `version`; la mutacion de usuario con version distinta responde 409 `TEMPLATE_VERSION_CONFLICT`
  (codigo de D1) y el barrido reintenta una vez sobre la lectura nueva.
- Editar el cuerpo de la plantilla D1 de un vinculo `PUBLICADA`, o cambiar un bloque de D7 que ella use, no la despublica: limite
  documentado, lo resuelve D6 (nivel 3). Dueno: Equipo de backend. Fecha: 2026-12-15.
- Credenciales de Twilio ausentes: el adaptador de aprobacion queda deshabilitado con motivo (como el de envio); vincular y publicar
  responden 503; la compuerta de un vinculo ya `PUBLICADA` no se cierra por eso.
- Ningun log, metrica ni respuesta lleva credenciales ni el cuerpo crudo del proveedor; `rejectionReason` se trunca a 200 caracteres.

## Requirements

### Functional Requirements

- **FR-001**: El sistema MUST vincular una plantilla WHATSAPP `ACTIVA` del tenant solo con un `providerTemplateId` de la lista del tenant
  (`HX` + 32 hexadecimales, como valida el adaptador de envio, spec 028) y un `variableMap` opcional, leyendo antes el estado al proveedor.
- **FR-001b**: El sistema MUST permitir al ADMINISTRADOR_PLATAFORMA y al ADMINISTRADOR_APLICACION agregar, quitar y listar las entradas de
  esa lista; quitar una entrada en uso por un vinculo `PUBLICADA` responde 409 `TEMPLATE_PUBLISHED`.
- **FR-002**: El sistema MUST guardar por vinculo: `approvalStatus` (`NO_ENVIADA`, `PENDIENTE`, `APROBADA`, `RECHAZADA`, `PAUSADA`),
  `rejectionReason`, `providerLanguage`, `publicationStatus`, `lastSyncedAt`, `lastSyncError`, `version`.
- **FR-003**: Una plantilla WHATSAPP no aprobada MUST NOT publicarse: la publicacion exige `APROBADA` en lectura fresca al proveedor.
- **FR-004**: El sistema MUST ofrecer a `core` una compuerta `(tenant, templateId) -> abierta|cerrada` que solo abre cuando la plantilla D1
  esta `ACTIVA`, es WHATSAPP, el vinculo esta `PUBLICADA` y `APROBADA`; para otros canales abre siempre. Se consulta solo en el despacho, nunca al aceptar.
- **FR-005**: El sistema MUST sincronizar el estado bajo demanda, al publicar y periodicamente; los fallos del proveedor conservan el
  ultimo estado y registran `lastSyncError`.
- **FR-006**: Un vinculo `PUBLICADA` cuya sincronizacion devuelve `RECHAZADA` o `PAUSADA` MUST pasar a `SUSPENDIDA` y solo volver a
  `PUBLICADA` por una publicacion explicita con `APROBADA`.
- **FR-007**: El sistema MUST garantizar un `providerTemplateId` por un solo tenant, con indice unico `(providerId, providerTemplateId)` en
  la lista, y responder 404 uniforme (NTF-3071) en los tres casos de la Clarificacion de aislamiento.
- **FR-008**: Toda consulta y escritura MUST filtrar por el tenant de la sesion; el barrido opera por tenant del vinculo.
- **FR-009**: La lectura de aprobacion MUST pasar por un puerto de salida de `core` con un adaptador Twilio; Meta sustituiria solo el adaptador.
- **FR-010**: El barrido MUST ser seguro con varias replicas (arrendamiento atomico) y no solaparse consigo mismo.
- **FR-011**: Las transiciones a `PUBLICADA`, `SUSPENDIDA` y `BORRADOR` MUST registrarse con log estructurado (`tenantId`, `templateId`,
  `correlationId`, estado) y metrica `notification.whatsapp.template.approval` (tags `status`); sin secretos.
- **FR-012**: Roles permitidos (B2): vincular y sincronizar, EDITOR_PLANTILLAS o ADMINISTRADOR_PLATAFORMA; publicar y despublicar,
  ADMINISTRADOR_APLICACION o ADMINISTRADOR_PLATAFORMA; consultar el vinculo, CLIENTE, OPERADOR, EDITOR_PLANTILLAS, ADMINISTRADOR_APLICACION
  o ADMINISTRADOR_PLATAFORMA; gestionar la lista, ADMINISTRADOR_PLATAFORMA o ADMINISTRADOR_APLICACION.
- **FR-013**: La mutacion de usuario MUST usar control optimista por `version`.
- **FR-014**: D5 MUST integrar la compuerta solo en el despacho de `SendNotificationService` (WHATSAPP, envio por `templateId`): si esta
  cerrada, falla como permanente trazable, despues de las preferencias de F1; y MUST armar `providerTemplate` con `variableMap` en lugar
  del cuerpo. La aceptacion no consulta la compuerta.

### Key Entities

- **Vinculo de plantilla (`TemplateProviderLink`)**: tenant, `templateId` (D1), `providerId`, `providerTemplateId`, `variableMap`, campos de FR-002.
  Coleccion propia; D1 no cambia.
- **Estado de aprobacion**: valor cerrado traducido desde el proveedor; los estados desconocidos del proveedor se tratan como `PAUSADA`
  (no utilizable) y se registran.
- **Compuerta**: consulta de solo lectura que el despacho invoca antes de armar el envio.

## Contrato consumido por la UI (sin tocar `api-notificaciones.yaml`; lo define Contrato)

Todas con `x-status: planned`.

| Operacion | Ruta | operationId | Roles permitidos | Notas |
|---|---|---|---|---|
| POST | `/template-provider-links` | `linkProviderTemplate` | EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA | `{templateId, providerTemplateId, variableMap}`; 201; 400, 404 (3071), 409, 503 |
| GET | `/template-provider-links` | `getTemplateProviderLink` | CLIENTE, OPERADOR, EDITOR_PLANTILLAS, ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA | `templateId` en query; 404 |
| POST | `/template-approval-syncs` | `syncTemplateApproval` | EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA | `{templateId}`; 200 con el vinculo; 404, 503 |
| POST | `/template-publications` | `publishTemplate` | ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA | `{templateId, version}`; 200; 404, 409 `PROVIDER_TEMPLATE_NOT_APPROVED`, 503 |
| POST | `/template-unpublications` | `unpublishTemplate` | ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA | `{templateId, version}`; 200; 404, 409 |
| POST | `/provider-template-allowlist-entries` | `addProviderTemplateAllowEntry` | ADMINISTRADOR_PLATAFORMA, ADMINISTRADOR_APLICACION | `{providerTemplateId}`; 201; 404 (3071), 503 |
| GET | `/provider-template-allowlist-entries` | `listProviderTemplateAllowEntries` | ADMINISTRADOR_PLATAFORMA, ADMINISTRADOR_APLICACION | paginado; 200 |
| POST | `/provider-template-allowlist-removals` | `removeProviderTemplateAllowEntry` | ADMINISTRADOR_PLATAFORMA, ADMINISTRADOR_APLICACION | `{providerTemplateId}`; 204; 404, 409 `TEMPLATE_PUBLISHED` |

## Success Criteria

- **SC-001**: Vincular, ver `PENDIENTE`, aprobar en el doble, sincronizar, publicar y consultar: flujo completo contra el proveedor doble (E2E).
- **SC-002**: Publicar con `PENDIENTE`, `RECHAZADA` y `PAUSADA` responde 409 y no cambia el estado; con copia local `APROBADA` y proveedor
  `RECHAZADA` tambien (E2E).
- **SC-003**: Una plantilla publicada que el proveedor rechaza queda `SUSPENDIDA` y la compuerta cerrada en un ciclo de barrido: la prueba
  fija el intervalo en 1 s y afirma con `Duration` que ocurre en menos de 5 s (E2E).
- **SC-004**: Con dos tenants, ninguna lectura, escritura, publicacion ni barrido cruza datos, y un mismo `providerTemplateId` no se vincula
  dos veces (E2E).
- **SC-005**: Ni logs ni respuestas contienen el token de Twilio ni el cuerpo crudo del proveedor (prueba automatizada con centinela).
- **SC-006**: Con dos instancias del servicio de barrido sobre la misma base, el doble recibe una sola consulta por vinculo y ciclo
  (prueba de integracion con Mongo).

## Limites (dependen de E1 o quedan fuera)

- **E1 sin cerrar**: si elige Meta, cambian el adaptador, el mapa de estados y la forma del identificador (`HX...` pasa a nombre + idioma de
  Meta). El resto (vinculo, compuerta, publicacion) no cambia. La verificacion del negocio en Meta no se modela.
- Sin callbacks del proveedor; el retardo maximo de deteccion es el intervalo del barrido. Excepcion (Principio VII): dueno Equipo de
  backend, fecha 2026-12-15 para decidir con E1 si se agregan callbacks.
- Alta y envio de la plantilla a revision del proveedor (crear el contenido en Twilio) quedan fuera: se vincula una ya creada en su consola.
- Wiring (`UseCaseConfig`) y `pom.xml` los edita quien posee el wiring. Dueno: Equipo de backend. Fecha: 2026-12-15.
- Base legal y retencion: esta historia no guarda datos personales (solo identificadores de plantilla y estado). Si cambia, decide
  el Responsable de datos. Dueno: Responsable de datos. Fecha: 2026-12-15.

## Preguntas abiertas

- Resueltas en la revision del lote nivel 2 (decision del usuario, 2026-10-25): P1 (cuerpo D1 o bloque D7 editado tras publicar) se deja a
  D6, con dueno y fecha en Edge Cases; P2 (idioma) no bloquea, solo informativo.
- **P3**: Nombre `BORRADOR/PUBLICADA/SUSPENDIDA` frente a un estado en D1 cuando llegue D6. Recomendacion: reutilizar el de D6 si lo define.
