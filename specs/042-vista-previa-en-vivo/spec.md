# Feature Specification: Vista previa en vivo de plantillas (HU2-126)

**Feature Branch**: `feature/HU2-126-vista-previa-en-vivo`

**Created**: 2026-10-25

**Status**: Pendiente de aprobacion

## Contexto

Como equipo tecnico (ET) quiero ver la plantilla renderizada con datos de ejemplo, con la apariencia de cada canal, el conteo de
caracteres y un aviso si el SMS excede un segmento. Depende de HU2-119 ([spec 035](../035-plantillas-variables/spec.md)) y de la cadena
de plantillas D2, D7, D4 (parte event), D3, D5: reutiliza el renderizador unico de `core` y el agregado `Template`. La pantalla vive en
`Front-Notification-mfe`; aqui se especifica el backend y el contrato que la UI consumira. Decision (2026-10-24): los datos de ejemplo
se guardan en la plantilla.

## Clarifications

### Session 2026-10-25

- Q: Que se previsualiza -> A: el borrador que el ET esta editando, no solo lo guardado. El endpoint recibe canal, asunto, cuerpo,
  variables y valores, y no exige que la plantilla exista. Para ver una guardada, la UI lee `GET /templates/detail` y reenvia sus campos.
- Q: Mismo motor (opcion A, aprobada) -> A: la vista previa usa `RenderTemplateService` de D1, el renderizador unico de `core`. D2 le
  añade una entrada por contenido (sin `templateId`) y un modo tolerante que devuelve los faltantes sin 422; D7 y D3 la reutilizan. D2 no
  toca `TemplateEngine` ni hay segunda implementacion en servidor o UI. La UI no calcula nada.
- Q: Tipo de resultado -> A: `ResolvedTemplate(subject, body, channelType, category, critical, variables, blockRefs)`, unico, dueño
  `RenderTemplateService`. D2 lo usa sin redefinirlo; en la vista previa `category` y `critical` van nulos.
- Q: Cadena de plantillas -> A: D2, D7, D4 (parte event), D3, D5. `NoDirectTemplateRenderTest` la crea D2 y prohibe `TemplateEngine.render`
  fuera del renderizador unico; D3 y D7 solo añaden casos.
- Q: Variable obligatoria sin valor -> A: 200, cadena vacia en su lugar y nombre en `missingVariables`. Sintaxis invalida: 400
  `INVALID_TEMPLATE_SYNTAX`.
- Q: Donde se guardan los datos de ejemplo -> A: campo `sampleValues` (mapa nombre -> texto) en `Template`, opcional, reemplazo completo
  en `PUT /templates`. Solo nombres presentes en asunto o cuerpo; si no, 400 `UNDECLARED_TEMPLATE_VARIABLE`. Limites de D1 para `values`:
  50 entradas, 2000 caracteres por valor (`TEMPLATE_LIMIT_EXCEEDED`).
- Q: Datos personales en ejemplos -> A: `sampleValues` es lo unico que guarda valores y deben ser ficticios. Los `values` de render y de
  vista previa no se persisten ni registran (D1 FR-011).
- Q: Calculo de segmentos SMS -> A: se aplica al canal de tipo `SMS`. Alfabeto GSM 03.38 basico: 160 unidades en un segmento, 153 por
  segmento si hay mas. Los caracteres de extension (`^ { } \ [ ] ~ | €` y salto de pagina) cuentan 2. Del español entran en el basico
  `é è ù ì ò à É Ç ñ Ñ ü Ü ä ö ¡ ¿`; NO entran `á í ó ú`, sus mayusculas ni la `ç` minuscula. Un texto con una de esas letras, o con un
  emoji, cae en UCS-2: 70 unidades en un segmento y 67 por segmento si hay mas, en unidades UTF-16. Excede un segmento si segmentos > 1.
- Q: Tope de SMS frente a D3 -> A: D2 y D3 usan el mismo `SmsSegmenter` de `core`. D2 solo informa: una vista previa nunca responde 400
  por tamaño. La regla de rechazo es de D3 (su FR-005): un envio con plantilla SMS cuyo texto renderizado excede un segmento responde 400
  con el codigo de tope de contenido vigente del canal, sin truncar. Sustituye el tope fijo de 160 caracteres de la ruta por plantilla
  (en UCS-2 equivaldria a 70). El cuerpo directo de HU2-112 conserva sus 160 caracteres: asimetria declarada en D3.
- Q: Errores de bloques (D7) en la vista previa -> A: se propagan los del render: 409 `BLOCK_NOT_FOUND` (3080), `BLOCK_ARCHIVED` (3088),
  `BLOCK_CYCLE_DETECTED` (3085), `BLOCK_DEPTH_EXCEEDED` (3086) y `BLOCK_EXPANSION_TOO_LARGE` (3087). Un bloque ausente aqui es 409; en el
  guardado de D7 es 400. No se guarda nada.
- Q: Que se cuenta -> A: `characterCount` son puntos de codigo Unicode del cuerpo renderizado; `subjectCharacterCount` los del asunto.
  Para SMS se devuelve ademas el desglose de segmentos sobre el cuerpo.
- Q: Apariencia por canal -> A: el servidor devuelve texto plano y tipo de canal; la UI dibuja la apariencia y muestra el texto como
  texto, nunca como HTML (D1 FR-014).
- Q: Roles (aprobado, B2 entra primero) -> A: la vista previa permite `EDITOR_PLANTILLAS`, `ADMINISTRADOR_APLICACION` y
  `ADMINISTRADOR_PLATAFORMA` (`x-allowed-roles` de B2); no CLIENTE ni OPERADOR. Guardar `sampleValues` hereda los roles de `PUT /templates`
  en B2 (`EDITOR_PLANTILLAS` y `ADMINISTRADOR_PLATAFORMA`). Consecuencia: `ADMINISTRADOR_APLICACION` previsualiza pero no guarda ejemplos;
  a confirmar con B2.
- Q: Ejemplos automaticos (P2) -> A: el servidor no genera ejemplos. La UI propone los `sampleValues` guardados; si no hay, cadena vacia.

## User Scenarios & Testing

### User Story 1 - Ver el borrador renderizado con datos de ejemplo (Priority: P1)

El ET escribe un cuerpo con `{{nombre}}`, indica un valor de ejemplo y obtiene el texto renderizado con el renderizador del servidor.

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

**Acceptance Scenarios** (textos reales en espanol):

1. **Given** `Hola Ana, tu codigo es 123456` en canal `SMS`, **Then** `encoding=GSM7`, `units=29`, `segments=1`,
   `exceedsSingleSegment=false`.
2. **Given** `Hola Ana, tu código es 123456` (con `ó`), **Then** `encoding=UCS2`, `units=29`, `unitsPerSegment=70`, `segments=1`.
3. **Given** `Tu contraseña es 1234` (`ñ` esta en GSM), **Then** `encoding=GSM7`, `units=21`. **Given** `contraseña, tu codigo está listo`
   (con `á`), **Then** `encoding=UCS2`.
4. **Given** `Ana, tu pedido está listo para retirar hoy en la sede norte del campus` (70 unidades, con `á`), **Then** `UCS2`,
   `segments=1`. Con un punto final (71 unidades), **Then** `segments=2`, `unitsPerSegment=67`, `exceedsSingleSegment=true`.
5. **Given** `Hola Ana, tu pedido ya esta listo para retirar en la sede central del campus norte. Gracias por tu compra y hasta pronto,
   te esperamos en el mostrador! Gracias.` (160 unidades GSM), **Then** `segments=1`. Con `Gracias!!` en lugar de `Gracias.` (161
   unidades), **Then** `segments=2`, `unitsPerSegment=153`.
6. **Given** `Total: 10 € {x}`, **Then** `encoding=GSM7`, `units=18` (`€` y `{` cuentan 2 unidades cada uno).
7. **Given** `Hola Ana 😀 tu cita es el lunes`, **Then** `encoding=UCS2`, `units=31`, `characterCount=30` (el emoji es un punto de codigo
   y dos unidades UTF-16).
8. **Given** un canal distinto de `SMS`, **Then** `sms` es `null` y siguen presentes `characterCount` y `subjectCharacterCount`.
9. **Given** que el valor de ejemplo hace crecer el texto, **Then** el conteo se hace sobre el texto renderizado, no sobre la plantilla cruda.

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
- Pareja de sustitucion UTF-16 (emoji) en UCS-2: cuenta 2 unidades y 1 punto de codigo; nunca se parte entre segmentos.
- Vista previa de canal sin ruta activa para el tenant: permitida (no es un envio); el canal solo selecciona el calculo.
- Bloque ausente, archivado o en ciclo dentro de la vista previa: 409 con su codigo de bloque; no se guarda nada.

## Requirements

- **FR-001**: El sistema MUST exponer una vista previa que reciba canal, asunto opcional, cuerpo, variables declaradas y valores, sin
  requerir plantilla guardada, y devuelva asunto y cuerpo renderizados por `RenderTemplateService` (Clarifications).
- **FR-002**: La vista previa MUST NOT devolver 422 por variables faltantes; MUST listarlas en `missingVariables`. Sintaxis invalida: 400.
  Errores de bloque: 409 (Clarifications).
- **FR-003**: La respuesta MUST incluir `characterCount` (cuerpo) y `subjectCharacterCount` en puntos de codigo.
- **FR-004**: Para canal `SMS` la respuesta MUST incluir `sms {encoding, units, unitsPerSegment, segments, exceedsSingleSegment}` segun
  Clarifications; para otros canales `sms=null`. La vista previa solo informa; no rechaza por tamaño.
- **FR-005**: `Template` MUST aceptar `sampleValues` opcional en `POST /templates` y `PUT /templates`, devolverlo en las lecturas y validar
  claves y limites (Clarifications). Plantillas existentes sin el campo leen `{}`.
- **FR-006**: Los `values` de la vista previa MUST NOT persistirse ni registrarse en logs ni metricas; solo `sampleValues` se guarda.
- **FR-007**: La vista previa MUST requerir `x-allowed-roles` `EDITOR_PLANTILLAS`, `ADMINISTRADOR_APLICACION` o
  `ADMINISTRADOR_PLATAFORMA` (B2), nunca CLIENTE. Guardar `sampleValues` hereda los roles de `PUT /templates`. Tenant tomado de la sesion.
- **FR-008**: El calculo de segmentos MUST vivir en `core` (`SmsSegmenter`), sin frameworks, y ser la unica implementacion; D3 lo reutiliza
  y la UI no lo repite.
- **FR-009**: El renderizado MUST hacerse solo por `RenderTemplateService`. `NoDirectTemplateRenderTest` (creada por D2; D3 y D7 solo
  añaden casos) MUST prohibir `TemplateEngine.render` fuera de ese servicio. D2 no modifica `TemplateEngine`.

### Key Entities

- **Template** (D1): agrega `sampleValues` (mapa nombre -> texto).
- **ResolvedTemplate** (unico, dueño `RenderTemplateService`): subject, body, channelType, category, critical, variables, blockRefs.
  D2, D3 y D7 lo usan con esa forma.
- **TemplatePreview** (no se persiste): asunto, cuerpo, `missingVariables`, conteos, `SmsSegmentation`.
- **SmsSegmentation**: encoding (`GSM7`/`UCS2`), unidades, unidades por segmento, segmentos, `exceedsSingleSegment`.

## Contrato consumido por la UI (sin tocar `api-notificaciones.yaml`; ver plan)

Roles segun la tabla de B2 (`x-allowed-roles`).

| Operacion | Ruta | Roles permitidos | Notas |
|---|---|---|---|
| POST | `/template-previews` | EDITOR_PLANTILLAS, ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA | `{channel, subject?, body, variables?, values}`; 400 y 409 (bloques); sin escritura |
| POST/PUT | `/templates` | EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA | D1 + `sampleValues` |
| GET | `/templates`, `/templates/detail` | lecturas de B2 | D1 + `sampleValues` en el detalle |

Pantalla (repo `Front-Notification-mfe`, fuera de alcance): editor, valores de ejemplo precargados con `sampleValues`, llamada a
`/template-previews`, contador y aviso de segmentos tomados de la respuesta.

## Success Criteria

- **SC-001**: Una vista previa y un render de D1 con el mismo texto y valores devuelven el mismo `body` (prueba E2E).
- **SC-002**: Los escenarios 4 a 7 de la Historia 2 (textos reales en espanol con `á`, 70/71 unidades UCS-2, 160/161 GSM, `€` y `{`,
  emoji) dan los segmentos y conteos esperados (pruebas unitarias de `SmsSegmenter`).
- **SC-003**: Con dos tenants, `sampleValues` no cruza (prueba E2E).
- **SC-004**: Una vista previa de 20000 caracteres y 50 variables responde en menos de 200 ms de calculo, con aserciones de `Duration`.
- **SC-005**: Con un valor centinela en la vista previa, ni los logs capturados ni Mongo lo contienen (prueba automatizada).
