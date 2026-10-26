# Feature Specification: Enviar por templateId y variables (HU2-127)

**Feature Branch**: `feature/HU2-127-enviar-por-plantilla`

**Created**: 2026-10-25

**Status**: Pendiente de aprobacion

## Contexto

Como DEV quiero enviar `templateId` mas variables para no armar el texto en cada componente. El envio unitario y por lote acepta
un modo nuevo de contenido: una plantilla de la historia HU2-119 ([spec 035](../035-plantillas-variables/spec.md)) mas un mapa de
valores. El cuerpo directo y `providerTemplate` (WhatsApp, HU2-112) se conservan; los tres modos son excluyentes entre si.
El render lo hace solo el `RenderTemplateUseCase` de D1 al aceptar la notificacion.

## Clarifications

### Session 2026-10-25

- Q: Cuerpo directo -> A: se conserva sin fecha de retiro (decision del usuario, 2026-10-24). Plantilla y cuerpo conviven.
- Q: Campos nuevos -> A: `templateId` (uuid de la plantilla) y `templateValues` (mapa texto -> texto) en `SendNotificationRequest`, tambien en cada elemento del lote. Ids por cuerpo, nunca en ruta ([FR-001](#requirements)).
- Q: Convivencia con `providerTemplate` -> A: son cosas distintas. `providerTemplate` es una plantilla aprobada por el proveedor (WhatsApp) y se envia tal cual; `templateId` es una plantilla propia que se renderiza a texto. Exactamente uno de `body`, `providerTemplate`, `templateId` por envio ([FR-002](#requirements)). Vincular ambas es D5.
- Q: `subject` junto con `templateId` -> A: se rechaza; el asunto sale de la plantilla ([FR-002](#requirements)).
- Q: Que variante se usa -> A: `templateId` identifica una variante `(clave, canal, idioma)`; no hay seleccion por idioma ([FR-003](#requirements)).
- Q: Cuando se renderiza -> A: una sola vez, al aceptar; se persiste el texto, no los valores. Editar o archivar despues no altera lo aceptado, ni reintentos ni reenvio de G2 (spec 033) ([FR-006](#requirements)).
- Q: Idempotencia -> A: con el mismo `(tenant, externalId)` ya aceptado se devuelve la original con `duplicate=true` sin renderizar, aunque la plantilla este archivada o editada ([FR-007](#requirements)).
- Q: Errores -> A: los de D1: 404 `TEMPLATE_NOT_FOUND` (NTF-3050, tambien si es de otro tenant), 409 `TEMPLATE_ARCHIVED` (NTF-3053), 422 `MISSING_VARIABLES` (NTF-1010) con `missingVariables` completa, 400 `TEMPLATE_LIMIT_EXCEEDED` (NTF-1009). Nuevos: 400 `TEMPLATE_CONTENT_CONFLICT` (NTF-1011) y 400 `TEMPLATE_CHANNEL_MISMATCH` (NTF-1012) ([FR-002](#requirements), [FR-003](#requirements)).
- Q: Lote -> A: cada elemento se renderiza por separado; un fallo de plantilla rechaza solo ese elemento, con `rejectionReason` que empieza por el codigo NTF y lleva los nombres faltantes (por ejemplo `NTF-1010 MISSING_VARIABLES: nombre, curso`); los demas siguen ([FR-008](#requirements)).
- Q: Categoria critica -> A: solo trazabilidad; se guarda la referencia sin cambiar prioridad ni ruta; F1 y F3 la consumen ([FR-009](#requirements)).
- Q: Rol -> A: el de `POST /notifications` (CLIENTE), sin cambio en el PEP.
- Q: Propiedad de campos (2026-10-25) -> A: D3 es duena de `templateId`, `templateValues`, `event` y de la referencia de plantilla guardada en la notificacion (P1 aprobado). F1 solo anade `recipientIdentityId` y lee la categoria de esa referencia.
- Q: `event` (D4) -> A: objeto libre opcional, solo junto a `templateId`. D3 invoca `ResolveTemplateValuesUseCase` de D4 antes de renderizar; un valor explicito en `templateValues` gana sobre el mapeado ([FR-013](#requirements)).
- Q: Renderizador unico -> A: un solo `RenderTemplateUseCase` (con expansion de bloques de D7). Nada fuera de ese servicio llama a `TemplateEngine.render` ([FR-014](#requirements)). Orden de integracion: D2, D7, D3, D4, D5.
- Q: WHATSAPP -> A: punto de extension. Con canal WHATSAPP y vinculo aprobado de D5, el contenido sale como `providerTemplate`; D5 asume esa integracion y la compuerta `TemplateProductionGate`. Sin vinculo, texto libre ([FR-015](#requirements)).
- Q: Tope de SMS -> A: el texto renderizado se mide con el `SmsSegmenter` de D2 y debe caber en un segmento (GSM-7 160, UCS-2 70). Si no cabe, 400 con el codigo de tope de contenido vigente del canal, sin truncar ([FR-005](#requirements)). El cuerpo directo conserva su tope de 160 caracteres; la diferencia queda registrada en el plan.
- Q: Contrato -> A: `x-status: planned` en lo que cambie hasta implementar.

## User Scenarios & Testing

### User Story 1 - Enviar con plantilla y valores (Priority: P1)

El DEV envia `channelType`, destinatario, `templateId` y `templateValues`; el servicio renderiza el texto y lo trata como cualquier notificacion de ese canal.

**Independent Test**: crear una plantilla SMS `Hola {{nombre}}`, enviar con `{nombre: Ana}` y verificar que el proveedor recibe `Hola Ana`.

**Acceptance Scenarios**:

1. **Given** una plantilla activa del canal, **When** se envia con todos los valores, **Then** responde 202 y la notificacion queda con el texto renderizado como cuerpo (y asunto, si la plantilla lo tiene).
2. **Given** una variable opcional sin valor, **When** se envia, **Then** se sustituye por cadena vacia y se acepta.
3. **Given** una plantilla PUSH con asunto, **When** se envia, **Then** el asunto es el titulo y rigen los topes de PUSH sobre el texto renderizado.
4. **Given** un texto renderizado que no cabe en un segmento SMS, **When** se envia, **Then** responde 400 y nunca se trunca.
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
6. **Given** `templateValues` o `event` sin `templateId`, **Then** 400 `TEMPLATE_CONTENT_CONFLICT`.

---

### User Story 3 - Contenido directo, lotes y estabilidad (Priority: P1)

**Independent Test**: lote de 3 elementos (cuerpo directo, plantilla valida, plantilla con variable faltante): 2 aceptados y 1 rechazado, en orden.

**Acceptance Scenarios**:

1. **Given** una solicitud sin `templateId`, **When** se envia, **Then** se comporta exactamente como antes (compatibilidad).
2. **Given** un lote con elementos de plantilla y de cuerpo directo, **When** uno falla por plantilla, **Then** solo ese sale `REJECTED` y el orden se mantiene.
3. **Given** una notificacion aceptada y luego la plantilla editada o archivada, **When** se consulta, se reintenta o se reenvia por otro canal (G2), **Then** el contenido es el aceptado originalmente.
4. **Given** el mismo `externalId` tras archivar la plantilla, **When** se reenvia la solicitud, **Then** 202 con `duplicate=true`.

---

### User Story 4 - Aislamiento por tenant (Priority: P1)

**Acceptance Scenarios**:

1. **Given** plantillas de dos tenants, **When** el tenant A envia con el `templateId` del tenant B, **Then** 404 y no se envia nada; el tenant sale de la sesion, nunca del cuerpo.
2. **Given** ambos tenants con plantillas propias, **When** envian en paralelo, **Then** cada notificacion usa solo su plantilla.

### Edge Cases

- Guarda de formato de E10 (spec 032): valida `recipientAddress`, no el contenido. Una direccion invalida responde 400 `INVALID_RECIPIENT` aunque la plantilla sea correcta.
- Mas de 50 entradas en `templateValues` o un valor de mas de 2000 caracteres: 400 `TEMPLATE_LIMIT_EXCEEDED` (limites de D1).
- WHATSAPP con `templateId` sin vinculo D5: se envia el texto renderizado como mensaje libre; la ventana de 24 h sigue siendo regla del proveedor.

## Requirements

### Functional Requirements

- **FR-001**: `SendNotificationRequest` MUST aceptar `templateId` y `templateValues` opcionales; el elemento de lote usa el mismo esquema.
- **FR-002**: Cada envio MUST llevar exactamente uno entre `body`, `providerTemplate` y `templateId`. Con `templateId`, `subject`, `body` y `providerTemplate` MUST NOT aparecer; `templateValues` o `event` sin `templateId` tampoco. El incumplimiento responde 400 `TEMPLATE_CONTENT_CONFLICT`; si no hay ningun modo, el 400 de contenido vigente.
- **FR-003**: El sistema MUST rechazar con 400 `TEMPLATE_CHANNEL_MISMATCH` una plantilla de canal distinto a `channelType`, antes de renderizar.
- **FR-004**: El sistema MUST renderizar en el tenant de la sesion y responder 404, 409, 422 o 400 segun [Clarifications](#clarifications).
- **FR-005**: El texto renderizado MUST validarse con la forma de contenido del canal (PUSH 100/900, adjuntos) y, en SMS, MUST caber en un segmento segun el `SmsSegmenter` de D2. Si no cabe, 400 sin truncar.
- **FR-006**: La notificacion MUST persistir el texto renderizado y MUST NOT persistir `templateValues` ni `event`; reintentos y reenvios MUST usar ese texto.
- **FR-007**: Un `(tenant, externalId)` ya aceptado MUST devolver la original (`duplicate=true`) antes de consultar o renderizar la plantilla.
- **FR-008**: En lote, el fallo de plantilla de un elemento MUST rechazar solo ese elemento (`REJECTED`, `rejectionReason` con codigo y variables faltantes). Los elementos con el mismo `templateId` MUST resolver la plantilla una sola vez por lote.
- **FR-009**: La notificacion MUST guardar `templateId`, la categoria y `critical` de la plantilla como referencia; MUST NOT cambiar prioridad ni ruta por ello.
- **FR-010**: Los valores de variables MUST NOT aparecer en logs, metricas ni eventos de dominio; el cuerpo renderizado sigue el tratamiento que ya tiene el cuerpo.
- **FR-011**: Una solicitud sin `templateId` MUST comportarse igual que antes de esta historia.
- **FR-012**: Metricas por tenant y canal: envios por plantilla y rechazos por causa (`missing`, `archived`, `notfound`, `mismatch`), sin valores.
- **FR-013**: `SendNotificationRequest` MUST aceptar `event` (objeto libre, solo con `templateId`). Los valores mapeados por D4 se combinan con `templateValues` y el explicito gana. El mapeo se memoiza por lote.
- **FR-014**: El render MUST hacerse solo con `RenderTemplateUseCase`; una prueba de arquitectura prohibe `TemplateEngine.render` fuera de ese servicio. Plantilla y bloques se cachean por lote.
- **FR-015**: Con canal WHATSAPP y vinculo de D5, el contenido MUST salir como `providerTemplate`. La integracion y la compuerta son de D5; D3 deja solo el punto de extension.

### Key Entities

- **Contenido de envio**: uno de `body` | `providerTemplate` | `templateId` + `templateValues` (+ `event` opcional).
- **Referencia de plantilla en la notificacion**: `templateId`, `category`, `critical` (solo trazabilidad).

## Success Criteria

- **SC-001**: Un envio por plantilla llega al proveedor con el texto renderizado y la notificacion queda con ese cuerpo (prueba E2E `TemplateSendE2ETest`).
- **SC-002**: Con faltantes, 422 con la lista exacta y ninguna notificacion persistida ni encolada (E2E).
- **SC-003**: Con dos tenants, el `templateId` ajeno responde 404 y no cruza datos (E2E).
- **SC-004**: Editar o archivar la plantilla tras aceptar no cambia el contenido ni el reenvio; replay con el mismo `externalId` tras archivar da 202 duplicado (E2E).
- **SC-005**: Lote de 500 elementos con una plantilla lee la plantilla una vez y completa el render en menos de 200 ms con repositorio en memoria (prueba con aserciones de `Duration`).
- **SC-006**: Con un valor centinela, ni los logs capturados ni el documento Mongo contienen `templateValues`, y el centinela solo aparece en el cuerpo renderizado (prueba automatizada).
- **SC-007**: Las solicitudes sin `templateId` pasan sin cambios las pruebas de envio y lote existentes.
- **SC-008**: Con `event` y `templateValues` en conflicto gana el explicito; un SMS renderizado de dos segmentos responde 400 (unitarias y E2E).

## Preguntas abiertas

Ninguna. P1 (guardar la referencia de plantilla) fue aprobada el 2026-10-25.
