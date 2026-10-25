# Feature Specification: Plantillas con variables por canal e idioma (HU2-119)

**Feature Branch**: `feature/HU2-119-plantillas-variables`

**Created**: 2026-10-24

**Status**: Pendiente de aprobacion

## Contexto

Como equipo tecnico (ET) quiero armar plantillas con variables (`{{nombre}}`) por canal e idioma para reutilizar mensajes.
Las plantillas viven en este servicio y pertenecen a un tenant. La categoria del aviso se define en la plantilla. Otras historias
dependen de esta (vista previa, envio por `templateId`, localizacion): el modelo debe poder crecer sin construirlas aqui.

## Clarifications

### Session 2026-10-24

- Q: Motor de variables -> A: sustitucion textual de `{{nombre}}`, sin condicionales, bucles ni expresiones; el valor se inserta como
  texto y nunca se evalua. Nombre valido: `[A-Za-z][A-Za-z0-9_]*` (max 40). Se toleran hasta 5 espacios dentro de las llaves a cada lado (`{{ nombre }}`);
  con mas de 5 (`{{      nombre}}`) la sintaxis es invalida y responde 400 `INVALID_TEMPLATE_SYNTAX`.
- Q: Render en esta historia -> A: `POST /template-renders` se expone en D1 (decision del coordinador); D2 lo enriquece.
- Q: Categoria (F1) -> A: valor cerrado `ACADEMICO`, `ADMINISTRATIVO`, `MARKETING`, `SEGURIDAD`; `SEGURIDAD` es critica. Obligatoria.
- Q: Idioma -> A: etiqueta BCP 47 corta (`es`, `en`, `es-CO`), obligatoria, sin idioma por defecto implicito.
- Q: Identidad -> A: una plantilla es unica por `(tenant, clave, canal, idioma)`; la clave agrupa las variantes de canal e idioma.
- Q: Quien escribe -> A: ET = OPERADOR para escribir y CLIENTE para leer y renderizar (decision del usuario, 2026-10-24; los tres roles
  vigentes son ADMINISTRADOR, OPERADOR y CLIENTE). Sigue el patron existente: `POST /notification-retries` es OPERADOR y `GET /channels` es CLIENTE.
- Q: Texto del render -> A: texto plano. El escape propio de cada canal (por ejemplo HTML en correo) lo hace el adaptador del canal
  en la vista previa o el envio; esta historia no escapa.
- Q: Limites -> A: clave 1-60 caracteres `[A-Za-z0-9._-]`, idioma 2-10, asunto hasta 200, cuerpo hasta 20000, hasta 50 variables, `size` del
  listado 1-100 (por defecto 20).

## User Scenarios & Testing

### User Story 1 - Crear y editar plantillas con variables (Priority: P1)

El ET crea una plantilla con clave, categoria, canal, idioma, asunto opcional y cuerpo. El sistema detecta las variables del
asunto y del cuerpo, y el ET declara cada una obligatoria u opcional.

**Independent Test**: crear una plantilla con `{{nombre}}` y `{{curso}}`, declarar `curso` opcional, leerla y ver ambas variables
con su obligatoriedad.

**Acceptance Scenarios**:

1. **Given** un cuerpo `Hola {{nombre}}, tu curso {{curso}}`, **When** se crea sin declarar variables, **Then** se detectan
   `nombre` y `curso`, ambas obligatorias por defecto.
2. **Given** la misma plantilla, **When** se declara `curso` opcional, **Then** la lectura devuelve `nombre` obligatoria y `curso`
   opcional.
3. **Given** una variable declarada que no aparece en el texto, **When** se guarda, **Then** responde 400 con `code=UNDECLARED_TEMPLATE_VARIABLE` y el nombre sobrante.
4. **Given** una plantilla con la misma clave, canal e idioma ya existente en el tenant, **When** se crea, **Then** responde 409 con `code=TEMPLATE_ALREADY_EXISTS`.
5. **Given** un canal sin ruta activa para el tenant (`ChannelCatalogPort.findActiveRoute(ChannelType, TenantId)` vacio), **When** se crea,
   **Then** responde 400 con `code=CHANNEL_NOT_AVAILABLE`.
6. **Given** un cuerpo con `{{1abc}}` o llaves sin cerrar, **When** se guarda, **Then** responde 400 con `code=INVALID_TEMPLATE_SYNTAX`.

---

### User Story 2 - Rechazar el uso con variables faltantes (Priority: P1)

Al renderizar una plantilla con valores, toda variable obligatoria sin valor produce 422 con la lista completa de faltantes.

**Independent Test**: renderizar sin `nombre` y sin `curso` obligatorias y recibir 422 con ambos nombres.

**Acceptance Scenarios**:

1. **Given** dos variables obligatorias, **When** se renderiza sin valores, **Then** responde 422 con `missingVariables` que lista ambas.
2. **Given** una variable opcional sin valor, **When** se renderiza, **Then** se sustituye por cadena vacia y responde 200.
3. **Given** valores para variables no declaradas, **When** se renderiza, **Then** se ignoran.
4. **Given** un valor que contiene `{{otra}}`, **When** se renderiza, **Then** el valor se inserta literal, sin segunda pasada.

---

### User Story 3 - Consultar y archivar plantillas (Priority: P2)

Listar con filtros por categoria, canal e idioma, ver el detalle y archivar una plantilla (deja de ser utilizable, no se borra).

**Acceptance Scenarios**:

1. **Given** plantillas en dos idiomas, **When** se lista filtrando `language=en`, **Then** solo aparecen las de ese idioma.
2. **Given** una plantilla archivada, **When** se renderiza, **Then** responde 409 con `code=TEMPLATE_ARCHIVED`; sigue visible en el listado con estado `ARCHIVADA`.

---

### User Story 4 - Aislamiento por tenant (Priority: P1)

Un tenant no ve, edita ni renderiza plantillas de otro. El tenant sale de la sesion del PDP, nunca del cuerpo ni de la query.

**Acceptance Scenarios**:

1. **Given** plantillas de dos tenants, **When** uno lista o consulta por `templateId` ajeno, **Then** no aparece / 404.
2. **Given** la misma clave, canal e idioma en dos tenants, **When** ambos crean, **Then** ambos tienen exito.

### Edge Cases

- Edicion concurrente: la actualizacion lleva `version`; si no coincide, 409 con `code=TEMPLATE_VERSION_CONFLICT`.
- Cuerpo vacio, o cualquier campo o conteo por encima de los limites de Clarifications: 400 con `code=TEMPLATE_LIMIT_EXCEEDED`.
- Plantilla `SEGURIDAD`: queda marcada critica en la lectura (`critical=true`); su efecto en el envio lo define la historia de envio.

## Requirements

### Functional Requirements

- **FR-001**: El sistema MUST crear plantillas con clave, categoria, canal, idioma, asunto opcional y cuerpo.
- **FR-002**: El sistema MUST detectar las variables `{{nombre}}` del asunto y del cuerpo, sin duplicados, en orden de aparicion.
- **FR-003**: El ET MUST poder declarar cada variable obligatoria u opcional; lo no declarado es obligatorio.
- **FR-004**: El sistema MUST rechazar con 400 las variables declaradas ausentes del texto (`UNDECLARED_TEMPLATE_VARIABLE`) y la
  sintaxis invalida (`INVALID_TEMPLATE_SYNTAX`).
- **FR-005**: El sistema MUST validar que el canal tenga ruta activa para el tenant y que la categoria sea una de las cuatro.
- **FR-006**: El sistema MUST garantizar unicidad por `(tenant, clave, canal, idioma)` y responder 409 al duplicar.
- **FR-007**: El render MUST responder 422 con `missingVariables` (todas las faltantes) si falta una obligatoria; opcionales vacias. Una variable cuenta como entregada con cualquier valor no nulo, incluida la cadena vacia; solo ausente o `null` es faltante. El mapa `values` admite hasta 50 entradas y 2000 caracteres por valor; si se excede, 400 `TEMPLATE_LIMIT_EXCEEDED`. El idioma se normaliza a minusculas (`es` y `ES` son la misma plantilla).
- **FR-008**: El render MUST sustituir en una sola pasada y tratar los valores como texto literal.
- **FR-009**: El sistema MUST permitir listar (filtros categoria, canal, idioma, estado), consultar detalle, actualizar y archivar.
- **FR-010**: El tenant MUST salir de la sesion; toda consulta y escritura MUST filtrar por el.
- **FR-011**: Los valores de variables MUST NOT persistirse ni aparecer en logs ni metricas; las plantillas no almacenan datos personales.
- **FR-012**: La actualizacion MUST usar control optimista por `version`.
- **FR-013**: Crear, actualizar y archivar MUST requerir rol OPERADOR; listar, consultar y renderizar, CLIENTE. Un rol superior satisface el minimo.
- **FR-014**: El render MUST devolver texto plano sin escapar; el escape por canal es responsabilidad del adaptador del canal.

### Key Entities

- **Plantilla**: id, tenant, clave, categoria, canal, idioma, asunto, cuerpo, variables, estado (`ACTIVA`/`ARCHIVADA`), version, fechas.
- **Variable**: nombre, obligatoria (booleano).
- **Categoria**: `ACADEMICO`, `ADMINISTRATIVO`, `MARKETING`, `SEGURIDAD` con indicador `critical`.

## Contrato consumido por la UI (sin tocar `api-notificaciones.yaml`; lo define Contrato)

Rutas literales; identificadores en query o cuerpo. Errores con el formato de error ya usado por el servicio.

| Operacion | Ruta | Rol minimo | Notas |
|---|---|---|---|
| POST | `/templates` | OPERADOR | 201; 400, 409 |
| PUT | `/templates` | OPERADOR | `templateId` y `version` en el cuerpo; 404, 409 |
| GET | `/templates` | CLIENTE | filtros `category`, `channel`, `language`, `status`, `page`, `size` (maximo 100) |
| GET | `/templates/detail` | CLIENTE | `templateId` en query; 404 |
| POST | `/template-archivals` | OPERADOR | `templateId` en el cuerpo; 404 |
| POST | `/template-variable-detections` | OPERADOR | `{subject, body}` -> variables detectadas, sin guardar; 400 |
| POST | `/template-renders` | CLIENTE | `{templateId, values}` -> `{subject, body}`; 422 `missingVariables`, 404, 409 |

## Success Criteria

- **SC-001**: Una plantilla con N variables devuelve las N detectadas con su obligatoriedad (prueba E2E).
- **SC-002**: Render con faltantes responde 422 con la lista exacta, sin omitir ninguna (prueba E2E).
- **SC-003**: Con dos tenants, ninguna lectura, escritura ni render cruza datos (prueba E2E).
- **SC-004**: Detectar y renderizar una plantilla de 20000 caracteres y 50 variables tarda menos de 200 ms, tambien con entradas adversas
  como `"{{" + " ".repeat(19990)` (prueba automatizada con aserciones de `Duration`).
- **SC-005**: Tras renderizar con un valor centinela, ni los logs capturados ni el documento Mongo lo contienen (prueba automatizada).

## Preguntas abiertas

- **P3**: Variable opcional sin valor: cadena vacia o valor por defecto declarado. Recomendacion: cadena vacia ahora; el valor por
  defecto cabe despues en `Variable` sin romper el contrato.
- **P4**: Enmascarar en el render los valores que son datos personales. Recomendacion: no en D1 (el llamador aporta los valores y
  no se resuelve ningun dato del destinatario); lo decide D2 al mostrar vista previa con destinatarios reales.
- **P5**: Escapar llaves literales (`\{{`). Recomendacion: fuera de alcance; abrir historia si se pide.
