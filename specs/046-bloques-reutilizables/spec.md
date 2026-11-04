# Feature Specification: Bloques reutilizables en plantillas (HU2-130)

**Feature Branch**: `feature/HU2-130-bloques-reutilizables`

**Created**: 2026-10-25

**Status**: Pendiente de aprobacion

**Depende de**: D1 (spec 035, `Template` y `TemplateEngine`), B2 (spec 039, roles), B6 (spec 041, `RecordAuditEntryUseCase`). Alimenta a C7 (tema y marca) y H4 (catalogo institucional). Orden de la cadena de plantillas: D2, D7, D4 (evento), D3, D5.

## Contexto

Como equipo tecnico (ET) quiero componer plantillas con bloques compartidos (cabecera, pie legal) para mantener coherencia.
Un bloque es un fragmento de texto con nombre, propio de un tenant, que una plantilla (o otro bloque) incluye con `{{> clave}}`.
Esta historia solo define bloques y su resolucion; no cambia como se envia (D3), se previsualiza (D2) ni se mapea (D4).

## Clarifications

### Session 2026-10-25

- Q: Que bloques y quien los mantiene -> A: el sistema no fija un catalogo: el ET crea los que necesite por clave (`cabecera`, `pie-legal`). Escriben EDITOR_PLANTILLAS o ADMINISTRADOR_PLATAFORMA; leen los cinco roles de la tabla de Contrato. No hay bloques globales entre tenants; H4 podra sembrarlos por tenant usando esta API.
- Q: Identidad -> A: unico por `(tenant, clave, canal, idioma)`, con las mismas reglas de clave (1-60, `[A-Za-z0-9._-]`) e idioma (BCP 47 corto, minusculas) que `Template`. La inclusion en una plantilla `(EMAIL, es)` resuelve el bloque de ese mismo canal e idioma; sin respaldo a otro idioma (D8 queda fuera).
- Q: Sintaxis -> A: `{{> clave}}` con hasta 5 espacios a cada lado de `>` y de la clave; no choca con variables porque un nombre de variable empieza por letra. Solo en el cuerpo; en el asunto es `INVALID_TEMPLATE_SYNTAX`.
- Q: Un cambio en un bloque afecta a plantillas ya publicadas -> A: si. La expansion ocurre en cada render con la version vigente del bloque; ese es el objetivo (coherencia). No se congelan instantaneas.
- Q: Anidacion -> A: un bloque puede incluir otros; profundidad maxima 3 niveles de bloque bajo la plantilla.
- Q: Ciclos -> A: se rechazan al guardar (`BLOCK_CYCLE_DETECTED`, 400, con la ruta del ciclo) y se vuelven a detectar al renderizar (409, nunca bucle ni desbordamiento).
- Q: Limites -> A: cuerpo de bloque hasta 5000 caracteres, hasta 10 inclusiones por documento, hasta 30 expansiones por render, texto expandido hasta 20000 (el limite de `Template`). Se verifica durante la expansion y se aborta al excederse. Un bloque declara variables como una plantilla (hasta 50).
- Q: Variables -> A: las variables de un bloque se suman a las de la plantilla que lo incluye. Es obligatoria si cualquier fuente la declara obligatoria. El total efectivo no excede 50.
- Q: Texto -> A: igual que D1: texto plano sin escapar; el escape por canal es del adaptador. Los valores nunca se interpretan como `{{> ...}}`.

### Correcciones aprobadas 2026-10-25

- Aprobados: profundidad 3, 5000 caracteres por bloque y `BLOCK_REQUIRED_VARIABLE_IN_USE` (409).
- Codigos NTF fijos: `BLOCK_NOT_FOUND` 3080, `BLOCK_ALREADY_EXISTS` 3081, `BLOCK_VERSION_CONFLICT` 3082, `BLOCK_IN_USE` 3083, `BLOCK_REQUIRED_VARIABLE_IN_USE` 3084, `BLOCK_CYCLE_DETECTED` 3085, `BLOCK_DEPTH_EXCEEDED` 3086, `BLOCK_EXPANSION_TOO_LARGE` 3087, `BLOCK_ARCHIVED` 3088. `BLOCK_NOT_FOUND` es 400 al guardar y 409 al renderizar.
- Renderizador unico: `RenderTemplateService` (D1) es dueno de `ResolvedTemplate` (forma de D3: subject, body, channelType, category, critical, mas variables y blockRefs). Tiene entrada por plantilla guardada y por contenido (introducida por D2); D7 reutiliza la de contenido para la vista previa. D2, D3 y D5 renderizan solo por ahi; D4 es pura y no renderiza (FR-014).

## User Scenarios & Testing

### User Story 1 - Crear y mantener bloques (Priority: P1)

El ET crea un bloque con clave, canal, idioma y cuerpo (puede llevar variables), lo lista, lo consulta, lo actualiza y lo archiva.

**Acceptance Scenarios**:

1. **Given** un cuerpo `Universidad {{institucion}}`, **When** se crea el bloque `cabecera` (EMAIL, es), **Then** se detecta `institucion` obligatoria.
2. **Given** un bloque con la misma clave, canal e idioma en el tenant, **When** se crea, **Then** 409 `BLOCK_ALREADY_EXISTS`.
3. **Given** un canal sin ruta activa para el tenant, **When** se crea, **Then** 400 `CHANNEL_NOT_AVAILABLE` (NTF-1003).
4. **Given** una version desfasada, **When** se actualiza, **Then** 409 `BLOCK_VERSION_CONFLICT`.
5. **Given** un bloque usado por alguna plantilla activa, **When** se archiva, **Then** 409 `BLOCK_IN_USE`; sin uso, queda `ARCHIVADA`.
6. **Given** un bloque en uso, **When** una actualizacion agrega una variable obligatoria que no tenia, **Then** 409 `BLOCK_REQUIRED_VARIABLE_IN_USE` (evita romper envios existentes con 422).

---

### User Story 2 - Componer plantillas con bloques (Priority: P1)

Al guardar una plantilla (D1) cuyo cuerpo contiene `{{> pie-legal}}`, el sistema comprueba que el bloque existe y esta activo para su canal e idioma, y registra la referencia. Al renderizar, el cuerpo se expande y luego se sustituyen las variables.

**Acceptance Scenarios**:

1. **Given** `Hola {{nombre}}\n{{> pie-legal}}` y el bloque con `{{institucion}}`, **When** se renderiza con `nombre` e `institucion`, **Then** el texto contiene el bloque expandido y los valores sustituidos.
2. **Given** una plantilla que incluye un bloque inexistente, archivado o de otro idioma, **When** se guarda, **Then** 400 `BLOCK_NOT_FOUND`.
3. **Given** el bloque cambiado despues de publicar la plantilla, **When** se renderiza, **Then** usa el texto nuevo.
4. **Given** un valor `{{> pie-legal}}` aportado por el llamador, **When** se renderiza, **Then** se inserta literal, sin segunda expansion.
5. **Given** que el bloque aporta una variable obligatoria que falta, **When** se renderiza, **Then** 422 con `missingVariables` que la incluye junto a las de la plantilla.

---

### User Story 3 - Anidacion, ciclos y limites (Priority: P1)

**Acceptance Scenarios**:

1. **Given** A incluye B, **When** B pasa a incluir A (o B se incluye a si mismo), **Then** 400 `BLOCK_CYCLE_DETECTED` y B no cambia.
2. **Given** una cadena de 4 niveles de bloque, **When** se guarda el cuarto, **Then** 400 `BLOCK_DEPTH_EXCEEDED`.
3. **Given** un bloque que incluye 10 veces a otro que incluye 10 veces a otro, **When** se renderiza, **Then** 409 `BLOCK_EXPANSION_TOO_LARGE` antes de superar 30 expansiones o 20000 caracteres.
4. **Given** un ciclo creado por dos guardados concurrentes, **When** se renderiza, **Then** 409 `BLOCK_CYCLE_DETECTED`, sin bucle.

---

### User Story 4 - Aislamiento por tenant (Priority: P1)

1. **Given** el bloque `cabecera` solo en el tenant A, **When** una plantilla del tenant B lo incluye, **Then** 400 `BLOCK_NOT_FOUND`.
2. **Given** la misma clave, canal e idioma en dos tenants, **When** cada uno renderiza, **Then** cada uno obtiene su propio texto.
3. Un `blockId` ajeno en detalle, actualizacion o archivo responde 404.

### Edge Cases

- Llaves sin cerrar o `{{>}}` sin clave: `INVALID_TEMPLATE_SYNTAX`.
- Bloque vacio: 400 `TEMPLATE_LIMIT_EXCEEDED`, como en D1.
- Archivar una plantilla no afecta al bloque; archivar bloques solo se bloquea mientras haya plantillas o bloques activos que lo incluyan.

## Requirements

- **FR-001**: MUST crear, listar (filtros clave, canal, idioma, estado), consultar, actualizar con `version` y archivar bloques por tenant.
- **FR-002**: MUST detectar `{{> clave}}` en cuerpos de plantilla y de bloque, y variables en bloques con las reglas de D1.
- **FR-003**: Al guardar plantilla o bloque, MUST validar que cada bloque referenciado existe y esta activo para el canal e idioma, la ausencia de ciclos, profundidad <= 3 y limites de tamano, expansion y variables (FR-007).
- **FR-004**: El render MUST expandir bloques y luego sustituir variables en una sola pasada; los valores no se expanden.
- **FR-005**: El render MUST abortar con 409 ante ciclo, profundidad, bloque ausente (`BLOCK_NOT_FOUND`), bloque archivado (`BLOCK_ARCHIVED`) o limite de expansion excedido, sin recursion sin tope.
- **FR-006**: MUST impedir archivar un bloque referenciado por una plantilla o bloque activo, y agregar variables obligatorias a un bloque en uso.
- **FR-007**: Limites: bloque 5000 caracteres; 10 inclusiones por documento; 30 expansiones por render; expandido <= 20000; <= 50 variables efectivas.
- **FR-008**: La deteccion de variables y el render de plantillas sin bloques MUST comportarse exactamente como en D1.
- **FR-009**: El tenant MUST salir de la sesion; toda lectura, escritura y resolucion de bloques filtra por el.
- **FR-010**: Crear, actualizar y archivar MUST requerir EDITOR_PLANTILLAS o ADMINISTRADOR_PLATAFORMA; listar y consultar, los roles de la tabla de Contrato.
- **FR-011**: Los valores no se persisten ni se registran en logs ni metricas; los logs de error pueden llevar claves de bloque y plantilla, no valores.
- **FR-012**: La expansion MUST cargar bloques por nivel en lote (como maximo 3 consultas por render), no uno a uno.
- **FR-013**: Cada alta, actualizacion y archivo MUST registrar en B6 un evento `TEMPLATE_BLOCK` con tenant, actor, clave, canal, idioma, version y estado antes y despues. No registra el cuerpo, las variables ni sus valores.
- **FR-014**: La expansion y la sustitucion MUST ocurrir solo dentro de `RenderTemplateService`, por plantilla guardada o por contenido (D2), y devolver `ResolvedTemplate`. La prueba de arquitectura `NoDirectTemplateRenderTest` (creada por D2; D7 le añade casos) MUST impedir cualquier otra llamada a `TemplateEngine.render`.

### Key Entities

- **Bloque**: id, tenant, clave, canal, idioma, cuerpo, variables, referencias a bloques, estado (`ACTIVA`/`ARCHIVADA`), version, fechas.
- **Plantilla** (D1): agrega `blockRefs`, las claves de bloque que su cuerpo incluye.

## Contrato consumido por la UI (sin tocar `api-notificaciones.yaml`)

| Operacion | Ruta | Roles (`x-allowed-roles`) | Notas |
|---|---|---|---|
| POST | `/template-blocks` | EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA | 201; 400, 409 |
| PUT | `/template-blocks` | EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA | `blockId` y `version` en el cuerpo; 400, 404, 409 |
| GET | `/template-blocks` | CLIENTE, OPERADOR, EDITOR_PLANTILLAS, ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA | filtros `key`, `channel`, `language`, `status`, `page`, `size` (max 100) |
| GET | `/template-blocks/detail` | CLIENTE, OPERADOR, EDITOR_PLANTILLAS, ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA | `blockId` en query; 404 |
| POST | `/template-block-archivals` | EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA | `blockId` en el cuerpo; 404, 409 |
Las rutas de D1 cambian de forma compatible: `TemplateResponse` y la deteccion devuelven `blockRefs`; la deteccion y el render resuelven bloques. Ver "Cambios de contrato" en [plan.md](plan.md).

## Success Criteria

- **SC-001**: Plantilla con cabecera y pie compartidos renderiza el texto expandido; cambiar el bloque cambia el siguiente render (E2E).
- **SC-002**: Ciclo directo, indirecto, autoinclusion, 4 niveles y explosion 10x10 se rechazan con el codigo esperado y sin bucle (E2E y unitaria).
- **SC-003**: Con dos tenants nada cruza: ni la resolucion, ni el listado, ni el detalle (E2E).
- **SC-004**: El render del peor caso permitido (3 niveles, 30 expansiones, 20000 caracteres) y la expansion adversa tardan menos de 200 ms, con aserciones de `Duration`.
- **SC-005**: Tras renderizar con un valor centinela, ni los logs ni los documentos Mongo lo contienen.
- **SC-006**: Las plantillas sin bloques pasan intactas las pruebas de D1 (regresion).
- **SC-007**: `NoDirectTemplateRenderTest` falla si cualquier clase fuera de `RenderTemplateService` llama a `TemplateEngine.render` (FR-014).
- **SC-008**: Alta, cambio y archivo de un bloque dejan un registro `TEMPLATE_BLOCK` en B6 sin cuerpo ni valores (E2E).

## Preguntas abiertas

- **P1**: Bloques con HTML para correo: esta historia entrega texto plano; el HTML y su escape pertenecen al adaptador (D2/C7). Recomendacion: no abrir historia hasta que C7 lo exija.
- **P2**: Aviso al editar un bloque en uso con la lista de plantillas afectadas. Recomendacion: fuera de alcance; el 409 `BLOCK_IN_USE` ya protege el archivo.
