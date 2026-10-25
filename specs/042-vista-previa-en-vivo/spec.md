# Feature Specification: Vista previa en vivo de plantillas (HU2-126)

**Feature Branch**: `feature/HU2-126-vista-previa-en-vivo`

**Created**: 2026-10-25

**Status**: Pendiente de aprobacion

## Contexto

Como equipo tecnico (ET) quiero ver la plantilla renderizada con datos de ejemplo, con la apariencia de cada canal, el conteo de
caracteres y un aviso si el SMS excede un segmento. Depende de HU2-119 ([spec 035](../035-plantillas-variables/spec.md)): reutiliza
su motor `TemplateEngine` y el agregado `Template`. La pantalla vive en `Front-Notification-mfe`; aqui se especifica el backend y el
contrato que la UI consumira. Decision (2026-10-24): los datos de ejemplo se guardan en la plantilla.

## Clarifications

### Session 2026-10-25

- Q: Que se previsualiza -> A: el borrador que el ET esta editando, no solo lo guardado. El endpoint recibe canal, asunto, cuerpo,
  variables y valores, y no exige que la plantilla exista. Para ver una guardada, la UI lee `GET /templates/detail` y reenvia sus campos.
- Q: Mismo motor -> A: la vista previa llama a `TemplateEngine.detect/render` de D1; no hay segunda implementacion en servidor ni en UI.
  La UI no calcula nada: muestra lo que devuelve el servidor.
- Q: Variable obligatoria sin valor -> A: no es error (el ET esta escribiendo). Responde 200, la sustituye por cadena vacia (comportamiento del
  motor) y la lista en `missingVariables`. Sintaxis invalida sigue siendo 400 `INVALID_TEMPLATE_SYNTAX`.
- Q: Donde se guardan los datos de ejemplo -> A: campo `sampleValues` (mapa nombre -> texto) en `Template`, opcional, reemplazo
  completo en `PUT /templates`. Solo nombres de variables presentes en asunto o cuerpo; si no, 400 `UNDECLARED_TEMPLATE_VARIABLE`.
  Mismos limites que `values` de D1: 50 entradas, 2000 caracteres por valor (`TEMPLATE_LIMIT_EXCEEDED`).
- Q: Datos personales en ejemplos -> A: `sampleValues` es lo unico de la plantilla que guarda valores; deben ser ficticios. D1 FR-011 sigue
  vigente para los `values` de render y de vista previa: no se persisten ni se registran. El contrato lo declara.
- Q: Calculo de segmentos SMS -> A: se aplica al canal cuyo tipo sea `SMS`. Alfabeto GSM 03.38: 160 unidades en un segmento, 153 por
  segmento si hay mas; los caracteres de extension (`^ { } \ [ ] ~ | EURO` y salto de pagina) cuentan 2. Si hay un caracter fuera del
  alfabeto GSM (tildes de mayuscula, emoji, etc.) se usa UCS-2: 70 y 67 unidades (unidades UTF-16). Excede un segmento si segmentos > 1.
- Q: Que se cuenta -> A: `characterCount` son puntos de codigo Unicode del cuerpo renderizado; `subjectCharacterCount` los del asunto.
  Para SMS ademas se devuelve el desglose de segmentos sobre el cuerpo (el SMS no lleva asunto).
- Q: Apariencia por canal -> A: el servidor devuelve el texto plano y el tipo de canal; la apariencia (burbuja SMS, correo, push) la dibuja la
  UI. El texto no esta escapado (D1 FR-014): la UI debe mostrarlo como texto, nunca como HTML.
- Q: Roles -> A: la vista previa no escribe nada; rol minimo CLIENTE, igual que `POST /template-renders`. Guardar los datos de ejemplo
  hereda OPERADOR de `PUT /templates`.

## User Scenarios & Testing

### User Story 1 - Ver el borrador renderizado con datos de ejemplo (Priority: P1)

El ET escribe un cuerpo con `{{nombre}}`, indica un valor de ejemplo y obtiene el texto renderizado con el motor del servidor.

**Independent Test**: previsualizar `Hola {{nombre}}` con `nombre=Ana` y recibir `Hola Ana`.

**Acceptance Scenarios**:

1. **Given** un borrador con dos variables y valores para ambas, **When** se previsualiza, **Then** 200 con `subject` y `body` renderizados
   y `missingVariables` vacio.
2. **Given** una variable obligatoria sin valor, **When** se previsualiza, **Then** 200, texto con cadena vacia en su lugar y su nombre en
   `missingVariables`.
3. **Given** un valor que contiene `{{otra}}`, **When** se previsualiza, **Then** se inserta literal (una sola pasada, igual que D1).
4. **Given** el mismo texto y valores, **When** se previsualiza y se renderiza una plantilla guardada igual con `POST /template-renders`,
   **Then** el resultado es identico.
5. **Given** un cuerpo con `{{1abc}}`, **When** se previsualiza, **Then** 400 `INVALID_TEMPLATE_SYNTAX`.

---

### User Story 2 - Conteo de caracteres y aviso de segmento SMS (Priority: P1)

**Acceptance Scenarios**:

1. **Given** un cuerpo renderizado de 160 caracteres GSM en canal `SMS`, **When** se previsualiza, **Then** `sms.segments=1`,
   `sms.exceedsSingleSegment=false`, `sms.encoding=GSM7`.
2. **Given** 161 caracteres GSM, **Then** `segments=2`, `exceedsSingleSegment=true`, `unitsPerSegment=153`.
3. **Given** un cuerpo de 70 unidades con un emoji, **Then** `encoding=UCS2`, `segments=1`; con 71 unidades, `segments=2`.
4. **Given** el caracter `{` o `EURO`, **Then** cuenta 2 unidades.
5. **Given** un canal distinto de `SMS`, **Then** `sms` es `null` y siguen presentes `characterCount` y `subjectCharacterCount`.
6. **Given** que el valor de ejemplo hace crecer el texto, **Then** el conteo se hace sobre el texto renderizado, no sobre la plantilla cruda.

---

### User Story 3 - Guardar los datos de ejemplo en la plantilla (Priority: P1)

**Acceptance Scenarios**:

1. **Given** una plantilla con `nombre` y `curso`, **When** se actualiza con `sampleValues={nombre:Ana}`, **Then** `GET /templates/detail` lo devuelve.
2. **Given** `sampleValues` con una clave que no esta en el texto, **When** se guarda, **Then** 400 `UNDECLARED_TEMPLATE_VARIABLE`.
3. **Given** mas de 50 entradas o un valor de mas de 2000 caracteres, **Then** 400 `TEMPLATE_LIMIT_EXCEEDED`.
4. **Given** una plantilla creada sin `sampleValues` (D1), **When** se lee, **Then** devuelve `{}`; no se requiere migracion.
5. **Given** que el cuerpo cambia y una variable con ejemplo desaparece, **When** se actualiza con los ejemplos viejos, **Then** 400;
   la UI debe reenviar los ejemplos vigentes.

---

### User Story 4 - Aislamiento y privacidad (Priority: P1)

1. **Given** dos tenants con plantillas que usan los mismos nombres de variable, **When** cada uno guarda y lee sus ejemplos, **Then**
   ninguno ve los del otro (el tenant sale de la sesion).
2. **Given** una vista previa con un valor centinela, **Then** ni los logs ni la base lo contienen (la vista previa no persiste nada).

### Edge Cases

- Cuerpo o asunto por encima de los limites de D1, o mas de 50 valores: 400 `TEMPLATE_LIMIT_EXCEEDED`.
- Cuerpo renderizado de SMS vacio: `segments=0`, `exceedsSingleSegment=false`.
- Pareja de sustitucion UTF-16 (emoji) en UCS-2: cuenta 2 unidades, nunca se parte entre segmentos (el calculo de segmentos es aritmetico, no corta texto).
- Vista previa de canal sin ruta activa para el tenant: permitida (no es un envio); el canal solo selecciona el calculo.

## Requirements

- **FR-001**: El sistema MUST exponer una vista previa que reciba canal, asunto opcional, cuerpo, variables declaradas y valores, sin
  requerir plantilla guardada, y devuelva asunto y cuerpo renderizados con `TemplateEngine` de D1.
- **FR-002**: La vista previa MUST NOT devolver 422 por variables faltantes; MUST listarlas en `missingVariables`. Sintaxis invalida: 400.
- **FR-003**: La respuesta MUST incluir `characterCount` (cuerpo) y `subjectCharacterCount` en puntos de codigo.
- **FR-004**: Para canal `SMS` la respuesta MUST incluir `sms {encoding, units, unitsPerSegment, segments, exceedsSingleSegment}` segun
  Clarifications; para otros canales `sms=null`.
- **FR-005**: `Template` MUST aceptar `sampleValues` opcional en `POST /templates` y `PUT /templates`, devolverlo en las lecturas y validar
  claves y limites (Clarifications). Plantillas existentes sin el campo leen `{}`.
- **FR-006**: Los `values` de la vista previa MUST NOT persistirse ni registrarse en logs ni metricas; solo `sampleValues` se guarda.
- **FR-007**: La vista previa MUST requerir rol CLIENTE; guardar ejemplos, OPERADOR (heredado). Tenant tomado de la sesion.
- **FR-008**: El calculo de segmentos MUST vivir en `core`, sin frameworks, y ser la unica implementacion; la UI no lo repite.

### Key Entities

- **Template** (D1): agrega `sampleValues` (mapa nombre -> texto).
- **TemplatePreview** (no se persiste): asunto, cuerpo, `missingVariables`, conteos, `SmsSegmentation`.
- **SmsSegmentation**: encoding (`GSM7`/`UCS2`), unidades, unidades por segmento, segmentos, `exceedsSingleSegment`.

## Contrato consumido por la UI (sin tocar `api-notificaciones.yaml`; ver plan)

| Operacion | Ruta | Rol minimo | Notas |
|---|---|---|---|
| POST | `/template-previews` | CLIENTE | `{channel, subject?, body, variables?, values}` -> vista previa; 400; sin escritura |
| POST/PUT | `/templates` | OPERADOR | D1 + `sampleValues` |
| GET | `/templates`, `/templates/detail` | CLIENTE | D1 + `sampleValues` en el detalle |

Pantalla (repo `Front-Notification-mfe`, fuera de alcance): editor, panel de valores de ejemplo precargado con `sampleValues`, llamada
con rebote a `/template-previews`, contador y aviso de segmentos tomados de la respuesta.

## Success Criteria

- **SC-001**: Una vista previa y un render de D1 con el mismo texto y valores devuelven el mismo `body` (prueba E2E).
- **SC-002**: Los limites 160/161 (GSM), 70/71 (UCS-2), extension de 2 unidades y mezcla con emoji dan los segmentos esperados (unitarias).
- **SC-003**: Con dos tenants, `sampleValues` no cruza (prueba E2E).
- **SC-004**: Una vista previa de 20000 caracteres y 50 variables responde en menos de 200 ms de calculo, con aserciones de `Duration`.
- **SC-005**: Con un valor centinela en la vista previa, ni los logs capturados ni Mongo lo contienen (prueba automatizada).

## Preguntas abiertas

- **P1**: Rol de la vista previa. Recomendacion: CLIENTE como el render; OPERADOR si se quiere limitarla a quien edita.
- **P2**: Valor por defecto de un ejemplo faltante en la UI: la UI propone el `sampleValues` guardado; si no hay, cadena vacia.
  Recomendacion: sin generar ejemplos automaticos en el servidor.
