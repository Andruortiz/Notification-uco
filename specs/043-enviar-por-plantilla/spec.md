# Feature Specification: Enviar por templateId y variables (HU2-127)

**Feature Branch**: `feature/HU2-127-enviar-por-plantilla`

**Created**: 2026-10-25

**Status**: Pendiente de aprobacion

## Contexto

Como DEV quiero enviar `templateId` mas variables para no armar el texto en cada componente. El envio unitario y por lote acepta
un modo nuevo de contenido: una plantilla de la historia HU2-119 ([spec 035](../035-plantillas-variables/spec.md)) mas un mapa de
valores. El cuerpo directo y `providerTemplate` (WhatsApp, HU2-112) se conservan; los tres modos conviven y son excluyentes entre si.
El render es el de D1 (`RenderTemplateService`); esta historia lo invoca al aceptar la notificacion.

## Clarifications

### Session 2026-10-25

- Q: Cuerpo directo -> A: se conserva sin fecha de retiro (decision del usuario, 2026-10-24). Plantilla y cuerpo conviven.
- Q: Campos nuevos -> A: `templateId` (uuid de la plantilla) y `templateValues` (mapa texto -> texto) en `SendNotificationRequest`, por lo que
  tambien en cada elemento del lote. Ids por cuerpo, nunca en ruta ([FR-001](#requirements)).
- Q: Convivencia con `providerTemplate` -> A: son cosas distintas. `providerTemplate` referencia una plantilla aprobada por el proveedor
  (WhatsApp) y se envia tal cual; `templateId` es una plantilla propia que este servicio renderiza a texto. Los tres modos (`body`,
  `providerTemplate`, `templateId`) son excluyentes: exactamente uno por envio ([FR-002](#requirements)). Vincular una plantilla propia con
  una aprobada por el proveedor es D5, fuera de aqui.
- Q: `subject` junto con `templateId` -> A: se rechaza; el asunto sale de la plantilla ([FR-002](#requirements)).
- Q: Que variante se usa -> A: `templateId` identifica una variante concreta `(clave, canal, idioma)`. No hay seleccion por idioma; el
  llamador elige el id. La plantilla debe ser del mismo canal que `channelType` ([FR-003](#requirements)).
- Q: Cuando se renderiza -> A: al aceptar, una sola vez. Se persiste el texto ya renderizado como contenido de la notificacion; los
  valores no se persisten. Cambiar o archivar la plantilla despues no altera lo aceptado; reintento automatico, reintento manual y
  reenvio de G2 (spec 033) usan el contenido persistido sin volver a renderizar ([FR-006](#requirements)).
- Q: Idempotencia -> A: con el mismo `(tenant, externalId)` ya aceptado se devuelve esa notificacion con `duplicate=true` sin
  renderizar, aunque la plantilla ya este archivada o editada ([FR-007](#requirements)).
- Q: Errores -> A: reutilizan los de D1: 404 `TEMPLATE_NOT_FOUND` (NTF-3050, tambien si es de otro tenant), 409 `TEMPLATE_ARCHIVED` (NTF-3053), 422
  `MISSING_VARIABLES` (NTF-1010) con `missingVariables` completa, 400 `TEMPLATE_LIMIT_EXCEEDED` (NTF-1009). Nuevos: 400 `TEMPLATE_CONTENT_CONFLICT`
  (NTF-1011) y 400 `TEMPLATE_CHANNEL_MISMATCH` (NTF-1012) ([FR-002](#requirements), [FR-003](#requirements)).
- Q: Lote -> A: cada elemento se renderiza por separado; un fallo de plantilla rechaza solo ese elemento, con `rejectionReason` que lleva
  el codigo y los nombres faltantes, y los demas siguen ([FR-008](#requirements)).
- Q: Categoria critica -> A: el efecto de `SEGURIDAD` en el envio (D1 lo dejo a esta historia) es solo trazabilidad: la notificacion guarda
  `templateId` y la categoria de la plantilla, sin cambiar prioridad ni ruta. Las preferencias (F1) y lo urgente (F3) consumen ese dato
  ([FR-009](#requirements)).
- Q: Rol -> A: el de `POST /notifications` (CLIENTE); no cambia el recurso del PEP.

## User Scenarios & Testing

### User Story 1 - Enviar con plantilla y valores (Priority: P1)

El DEV envia `channelType`, destinatario, `templateId` y `templateValues`; el servicio renderiza el texto y lo trata como cualquier
notificacion de ese canal.

**Independent Test**: crear una plantilla SMS `Hola {{nombre}}`, enviar con `{nombre: Ana}` y verificar que el proveedor recibe `Hola Ana`.

**Acceptance Scenarios**:

1. **Given** una plantilla activa del canal, **When** se envia con todos los valores, **Then** responde 202 y la notificacion queda con el
   texto renderizado como cuerpo (y asunto, si la plantilla lo tiene).
2. **Given** una variable opcional sin valor, **When** se envia, **Then** se sustituye por cadena vacia y se acepta.
3. **Given** una plantilla PUSH con asunto, **When** se envia, **Then** el asunto es el titulo y rigen los topes de PUSH sobre el texto renderizado.
4. **Given** un texto renderizado que excede el tope del canal (SMS 160), **When** se envia, **Then** responde 400 y nunca se trunca.
5. **Given** un valor que contiene `{{otra}}`, **When** se envia, **Then** se inserta literal.

---

### User Story 2 - Rechazos claros (Priority: P1)

**Independent Test**: enviar sin `nombre` ni `curso` obligatorias y recibir 422 con ambas en `missingVariables`, sin nada persistido ni encolado.

**Acceptance Scenarios**:

1. **Given** dos variables obligatorias ausentes, **When** se envia, **Then** responde 422 `MISSING_VARIABLES` con ambas y no se persiste ni se encola nada.
2. **Given** un `templateId` inexistente o de otro tenant, **Then** 404 con el mismo mensaje en ambos casos.
3. **Given** una plantilla archivada, **Then** 409 `TEMPLATE_ARCHIVED`.
4. **Given** `templateId` mas `body`, `subject` o `providerTemplate`, **Then** 400 `TEMPLATE_CONTENT_CONFLICT`.
5. **Given** una plantilla EMAIL y `channelType=SMS`, **Then** 400 `TEMPLATE_CHANNEL_MISMATCH`.
6. **Given** `templateValues` sin `templateId`, **Then** 400 `TEMPLATE_CONTENT_CONFLICT`.

---

### User Story 3 - Contenido directo, lotes y estabilidad (Priority: P1)

**Independent Test**: lote de 3 elementos (cuerpo directo, plantilla valida, plantilla con variable faltante): 2 aceptados y 1 rechazado, en orden.

**Acceptance Scenarios**:

1. **Given** una solicitud sin `templateId`, **When** se envia, **Then** se comporta exactamente como antes (compatibilidad).
2. **Given** un lote con elementos de plantilla y de cuerpo directo, **When** uno falla por plantilla, **Then** solo ese sale `REJECTED` y los resultados mantienen el orden.
3. **Given** una notificacion aceptada y luego la plantilla editada o archivada, **When** se consulta, se reintenta o se reenvia por otro canal (G2),
   **Then** el contenido es el aceptado originalmente.
4. **Given** el mismo `externalId` tras archivar la plantilla, **When** se reenvia la solicitud, **Then** 202 con `duplicate=true`.

---

### User Story 4 - Aislamiento por tenant (Priority: P1)

**Acceptance Scenarios**:

1. **Given** plantillas de dos tenants, **When** el tenant A envia con el `templateId` del tenant B, **Then** 404 y no se envia nada; el tenant sale
   de la sesion, nunca del cuerpo.
2. **Given** ambos tenants con plantillas propias, **When** envian en paralelo, **Then** cada notificacion usa solo su plantilla.

### Edge Cases

- Interaccion con la guarda de formato de E10 (spec 032): la guarda valida `recipientAddress`, no el contenido; ambas corren antes de persistir y su
  orden relativo no cambia el resultado. Una direccion invalida responde 400 `INVALID_RECIPIENT` aunque la plantilla sea correcta.
- Cifrado PUSH de E5 (spec 036): el cifrado del token ocurre al persistir y no toca el contenido; la plantilla no ve el token.
- Mas de 50 entradas en `templateValues` o un valor de mas de 2000 caracteres: 400 `TEMPLATE_LIMIT_EXCEEDED` (limites de D1).
- WHATSAPP con `templateId`: se envia el texto renderizado como mensaje libre; las reglas del proveedor sobre la ventana de 24 h siguen siendo suyas.
- Mismo `externalId` con otro `templateId` o valores distintos: se devuelve la notificacion original, como hoy con otro cuerpo.

## Requirements

### Functional Requirements

- **FR-001**: `SendNotificationRequest` MUST aceptar `templateId` y `templateValues` opcionales; el elemento de lote usa el mismo esquema.
- **FR-002**: Cada envio MUST llevar exactamente uno entre `body`, `providerTemplate` y `templateId`. Con `templateId`, `subject`, `body` y `providerTemplate`
  MUST NOT aparecer; `templateValues` sin `templateId` tampoco. El incumplimiento responde 400 `TEMPLATE_CONTENT_CONFLICT`; si no hay ningun modo, el 400 de contenido vigente.
- **FR-003**: El sistema MUST rechazar con 400 `TEMPLATE_CHANNEL_MISMATCH` una plantilla de canal distinto a `channelType`.
- **FR-004**: El sistema MUST renderizar con el motor de D1 en el tenant de la sesion y responder 404, 409, 422 o 400 segun [Clarifications](#clarifications).
- **FR-005**: El texto renderizado MUST validarse contra la forma de contenido del canal exactamente como un cuerpo directo (topes SMS 160, PUSH 100/900, adjuntos).
- **FR-006**: La notificacion MUST persistir el texto renderizado y MUST NOT persistir `templateValues`; reintentos y reenvios MUST usar ese texto.
- **FR-007**: Un `(tenant, externalId)` ya aceptado MUST devolver la original (`duplicate=true`) antes de consultar o renderizar la plantilla.
- **FR-008**: En lote, el fallo de plantilla de un elemento MUST rechazar solo ese elemento (`REJECTED`, `rejectionReason` con codigo y variables faltantes). Los
  elementos con el mismo `templateId` MUST resolver la plantilla una sola vez por lote.
- **FR-009**: La notificacion MUST guardar `templateId` y la categoria de la plantilla (con `critical`) como referencia; MUST NOT cambiar prioridad ni ruta por ello.
- **FR-010**: Los valores de variables MUST NOT aparecer en logs, metricas ni eventos de dominio; el cuerpo renderizado sigue el tratamiento que ya tiene el cuerpo.
- **FR-011**: Una solicitud sin `templateId` MUST comportarse igual que antes de esta historia.
- **FR-012**: Metricas por tenant y canal: envios por plantilla y rechazos por causa (`missing`, `archived`, `notfound`, `mismatch`), sin valores.

### Key Entities

- **Contenido de envio**: uno de `body` | `providerTemplate` | `templateId` + `templateValues`.
- **Referencia de plantilla en la notificacion**: `templateId`, `category`, `critical` (solo trazabilidad).

## Success Criteria

- **SC-001**: Un envio por plantilla llega al proveedor con el texto renderizado y la notificacion queda con ese cuerpo (prueba E2E `TemplateSendE2ETest`).
- **SC-002**: Con faltantes, 422 con la lista exacta y ninguna notificacion persistida ni encolada (E2E).
- **SC-003**: Con dos tenants, el `templateId` ajeno responde 404 y no cruza datos (E2E).
- **SC-004**: Editar o archivar la plantilla tras aceptar no cambia el contenido ni el reenvio; replay con el mismo `externalId` tras archivar da 202 duplicado (E2E).
- **SC-005**: Lote de 500 elementos con una plantilla lee la plantilla una vez y completa el render en menos de 200 ms con repositorio en memoria (prueba con aserciones de `Duration`).
- **SC-006**: Con un valor centinela, ni los logs capturados ni el documento Mongo contienen `templateValues`, y el centinela solo aparece en el cuerpo renderizado (prueba automatizada).
- **SC-007**: Las solicitudes sin `templateId` pasan sin cambios las pruebas de envio y lote existentes.

## Preguntas abiertas

- **P1**: Guardar `templateId` y categoria en la notificacion (FR-009) anade dos campos persistidos. Recomendacion: guardarlos ahora; F1 y F3 los necesitan y no
  hay forma de recuperarlos despues del render. Alternativa: no guardar y que F1/F3 pidan el campo cuando lleguen.
