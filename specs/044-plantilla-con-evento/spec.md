# Feature Specification: Armar la plantilla con el evento recibido (HU2-128)

**Feature Branch**: `feature/HU2-128-plantilla-con-evento`

**Created**: 2026-10-25

**Status**: Pendiente de aprobacion

**Depende de**: D1 (spec 035, plantillas con variables). Convive con D3 (spec 043, envio por `templateId`), que consume lo definido aqui.

## Contexto

Como equipo tecnico (ET) quiero mapear los campos del evento entrante a las variables de la plantilla para generar mensajes con lo que
llegue. Decision del usuario (2026-10-24): el "evento" es el mismo envio con variables; no hay endpoint de ingestion nuevo. Un
**evento** es un objeto JSON que la aplicacion cliente adjunta al envio por `templateId` (campo definido por D3). Un **mapeo** dice,
para cada variable de la plantilla, de que ruta del evento sale su valor y que valor usar si la ruta no existe. Esta historia define el
mapeo y su resolucion `evento -> valores`; D3 invoca la resolucion y D1 renderiza.

## Clarifications

### Session 2026-10-25

- Q: Que es el evento -> A: un objeto JSON dentro del envio por `templateId` (campo `event`, lo declara D3); sin endpoint ni cola de ingestion.
- Q: JSONPath o rutas simples -> A: rutas simples de segmentos separados por `.`. Un segmento es un nombre `[A-Za-z_][A-Za-z0-9_-]{0,39}`
  o un indice de arreglo `[0-9]{1,3}` (`usuario.nombre`, `items.0.sku`). Sin comodines, filtros, regex ni expresiones: la ruta se recorre
  segmento a segmento y nunca se evalua. Maximo 8 segmentos y 200 caracteres. ([FR-002](#functional-requirements))
- Q: Quien configura y versiona -> A: el ET (OPERADOR) guarda un unico mapeo por plantilla (`templateId`), con `version` optimista; los
  cambios de configuracion quedan auditables cuando exista B6 (esta historia no los audita). Cada variante canal/idioma tiene su mapeo. ([FR-007](#functional-requirements))
- Q: Valor por defecto -> A: texto fijo de configuracion (hasta 2000 caracteres), usado cuando la ruta no existe, vale `null` o no es un
  escalar. No debe contener datos personales (es configuracion, se persiste). ([FR-004](#functional-requirements))
- Q: Conversion a texto -> A: cadena tal cual; numero en notacion decimal sin exponente; booleano `true`/`false`. Objeto y arreglo no son
  escalares: cuentan como ausentes. La cadena vacia es un valor presente, igual que en D1 FR-007.
- Q: Tamano y limites del evento -> A: hasta 32768 caracteres serializados, profundidad 10; hasta 50 mapeos por plantilla; valor resuelto
  hasta 2000 caracteres. Excederlos responde 400 `EVENT_LIMIT_EXCEEDED` / `MAPPING_LIMIT_EXCEEDED`.
- Q: Precedencia con valores explicitos -> A: si D3 recibe `variables` y `event` a la vez, el valor explicito de una variable gana sobre
  el mapeado. La regla se aplica en D3; aqui solo se publica la resolucion como puerto.
- Q: Variable sin valor tras el mapeo -> A: no se duplica el chequeo: D1 responde 422 `missingVariables` al renderizar. La resolucion
  devuelve solo las variables que pudo resolver.
- Q: Roles -> A: guardar mapeo OPERADOR; leer mapeo y resolver CLIENTE (patron de D1 FR-013).

## User Scenarios & Testing

### User Story 1 - Definir el mapeo de una plantilla (Priority: P1)

El ET asocia a cada variable de una plantilla una ruta del evento y, opcionalmente, un valor por defecto.

**Independent Test**: sobre una plantilla con `{{nombre}}` y `{{curso}}`, guardar `nombre <- usuario.nombre` y `curso <- matricula.curso`
con por defecto `su curso`, y leer el mapeo con su `version`.

**Acceptance Scenarios**:

1. **Given** una plantilla activa del tenant, **When** se guarda un mapeo valido, **Then** responde 200 con el mapeo y `version`;
   leerlo lo devuelve igual.
2. **Given** un mapeo para una variable que la plantilla no declara, **When** se guarda, **Then** 400 `UNKNOWN_MAPPING_VARIABLE`
   con el nombre.
3. **Given** una ruta con `$`, `[`, `*`, espacios o mas de 8 segmentos, **When** se guarda, **Then** 400 `INVALID_MAPPING_PATH` con la
   posicion del mapeo.
4. **Given** una variable mapeada dos veces o mas de 50 mapeos, **When** se guarda, **Then** 400 `MAPPING_LIMIT_EXCEEDED`.
5. **Given** una `version` desfasada, **When** se guarda, **Then** 409 `TEMPLATE_MAPPING_VERSION_CONFLICT`.
6. **Given** una plantilla archivada, **When** se guarda, **Then** 409 `TEMPLATE_ARCHIVED`.

---

### User Story 2 - Resolver un evento a valores (Priority: P1)

Dado un evento, el sistema produce el mapa `variable -> texto` que D3 entrega al render de D1.

**Independent Test**: con el mapeo de la historia 1, resolver `{"usuario":{"nombre":"Ana"}}` y obtener `nombre=Ana`, `curso=su curso`.

**Acceptance Scenarios**:

1. **Given** una ruta anidada presente, **When** se resuelve, **Then** el valor sale de esa ruta.
2. **Given** una ruta de arreglo `items.0.sku`, **When** el evento tiene el indice, **Then** se toma; si el indice excede el arreglo, es ausente.
3. **Given** una ruta ausente, `null` u objeto, **When** hay defecto, **Then** se usa el defecto; **When** no lo hay, **Then** la variable
   no aparece en el resultado.
4. **Given** una cadena vacia en el evento y un defecto, **When** se resuelve, **Then** gana la cadena vacia (presente).
5. **Given** un valor del evento que contiene `{{otra}}`, **When** se resuelve y se renderiza, **Then** el texto queda literal (D1 FR-008).
6. **Given** un evento por encima de los limites, **When** se resuelve, **Then** 400 `EVENT_LIMIT_EXCEEDED` sin eco de su contenido.
7. **Given** una plantilla sin mapeo, **When** se resuelve, **Then** el resultado es vacio (D1 reportara los faltantes).

---

### User Story 3 - Flujo completo hasta el mensaje (Priority: P1)

Con D1, el mapeo y la resolucion, un evento produce el texto final y un evento incompleto produce 422 con la lista de faltantes.

**Independent Test**: E2E: crear plantilla, guardar mapeo, resolver el evento y renderizar con los valores; repetir con un evento
incompleto y obtener 422 `missingVariables`.

**Acceptance Scenarios**:

1. **Given** plantilla `Hola {{nombre}}, curso {{curso}}`, mapeo y evento completo, **When** se resuelve y se renderiza, **Then** el texto
   sale con los valores del evento.
2. **Given** el mismo caso sin `nombre` en el evento y sin defecto, **When** se renderiza, **Then** 422 listando `nombre`.

---

### User Story 4 - Aislamiento por tenant (Priority: P1)

Un tenant no lee, escribe ni resuelve mapeos de plantillas de otro. El tenant sale de la sesion del PDP.

**Acceptance Scenarios**:

1. **Given** una plantilla y mapeo del tenant A, **When** el tenant B lee, guarda o resuelve con ese `templateId`, **Then** 404.
2. **Given** dos tenants con plantillas de la misma clave, canal e idioma, **When** cada uno guarda su mapeo, **Then** cada resolucion usa
   solo el suyo (con control positivo: la del propietario si funciona).

### Edge Cases

- Si D1 elimina o renombra una variable despues de guardar el mapeo, el mapeo de esa variable se ignora al resolver; no rompe el envio.
- Un evento no es parte del mapeo: nunca se persiste ni se escribe en logs, metricas ni errores (FR-009).
- Ruta con segmento numerico sobre un objeto, o nombre sobre un arreglo: ausente, sin error.
- Cuerpos con campos desconocidos o duplicados en JSON: se rechazan como en el resto del contrato (400).

## Requirements

### Functional Requirements

- **FR-001**: El sistema MUST guardar, por plantilla del tenant, un mapeo de hasta 50 entradas `{variable, path, defaultValue?}`, una por variable,
  y reemplazar el mapeo completo en cada guardado.
- **FR-002**: Las rutas MUST ser segmentos separados por `.` conforme a Clarifications; el recorrido MUST ser lineal en el numero de
  segmentos, sin regex ni evaluacion de expresiones.
- **FR-003**: El guardado MUST rechazar: variable no declarada en la plantilla (`UNKNOWN_MAPPING_VARIABLE`), ruta invalida
  (`INVALID_MAPPING_PATH`), duplicados o conteos excedidos (`MAPPING_LIMIT_EXCEEDED`), plantilla archivada (409 `TEMPLATE_ARCHIVED`).
- **FR-004**: La resolucion MUST producir, por mapeo, el texto de la ruta si es un escalar presente; si no, el defecto; si tampoco hay, omitir la variable.
- **FR-005**: La resolucion MUST ser pura y solo producir el mapa; el render y la validacion de faltantes siguen en D1. Ignora mapeos de variables que la plantilla ya no declara.
- **FR-006**: El evento MUST respetar 32768 caracteres serializados y profundidad 10 (`EVENT_LIMIT_EXCEEDED`); el valor resuelto, 2000 caracteres.
- **FR-007**: El guardado MUST usar control optimista por `version`; leer devuelve el mapeo y su `version` (vacio con `version=0` si no existe).
- **FR-008**: El tenant MUST salir de la sesion; toda lectura y escritura MUST filtrar por el y por `templateId`.
- **FR-009**: Los valores del evento y los valores resueltos MUST NOT persistirse ni aparecer en logs, metricas ni mensajes de error; el defecto
  es configuracion y se persiste.
- **FR-010**: Guardar MUST requerir OPERADOR; leer y resolver, CLIENTE. Un rol superior satisface el minimo.
- **FR-011**: El nucleo MUST exponer la resolucion como puerto de entrada (`ResolveTemplateValuesUseCase`) para que D3 la use sin pasar por HTTP.

### Key Entities

- **Mapeo de plantilla**: id, tenant, `templateId`, entradas, `version`, fechas.
- **Entrada**: `variable`, `path`, `defaultValue` opcional.
- **Evento**: objeto JSON efimero; solo existe durante la peticion.

## Contrato consumido por la UI (sin tocar `api-notificaciones.yaml`; ver plan)

Rutas literales; `templateId` en query o cuerpo. La UI vive en Front-Notification-mfe: aqui solo se garantiza este contrato.

| Operacion | Ruta | Rol minimo | Notas |
|---|---|---|---|
| PUT | `/template-mappings` | OPERADOR | `{templateId, version, mappings[]}` -> mapeo; 400, 404, 409 |
| GET | `/template-mappings` | CLIENTE | `templateId` en query; 200 (vacio si no existe), 404 si la plantilla no existe |
| POST | `/template-mapping-resolutions` | CLIENTE | `{templateId, event}` -> `{values}`; 400, 404 |

## Success Criteria

- **SC-001**: Con el flujo de la historia 3, el texto final contiene los valores del evento y el evento incompleto responde 422 con la lista exacta (E2E).
- **SC-002**: Con dos tenants nada cruza en lectura, escritura ni resolucion, con control positivo (E2E).
- **SC-003**: Resolver 50 mapeos sobre un evento de 32768 caracteres y profundidad 10 tarda menos de 200 ms, tambien con rutas adversas
  (200 caracteres de puntos o `a.a.a...`) (prueba con aserciones de `Duration`).
- **SC-004**: Tras resolver con un valor centinela, ni los logs capturados ni los documentos Mongo lo contienen (prueba automatizada).
- **SC-005**: Ninguna ruta con sintaxis de JSONPath, comodines, filtros o expresiones se acepta (prueba parametrizada).

## Preguntas abiertas

- **Q1**: Mapeo como recurso aparte (`/template-mappings`, recomendado: no toca el contrato ni el agregado de D1) o embebido en la plantilla
  de D1 (un solo `PUT /templates`). Se implementa lo recomendado salvo indicacion.
- **Q2**: Precedencia `variables` explicitas sobre `event` (Clarifications) debe confirmarse con la spec de D3.
