# Feature Specification: Vincular la configuración de negocio a una aplicación del PDP

**Feature Branch**: `feature/HU2-146-vincular-aplicacion-pdp`

**Created**: 2026-10-24

**Status**: Draft

**Input**: HU2-146. Como AP quiero asociar la configuración de negocio de un componente consumidor (canales, plantillas, límites) a su aplicación registrada en el PDP. La aplicación se identifica por el mismo valor que el PDP; el registro, la credencial de un solo uso y los administradores se gestionan en el PDP; cada vínculo y cambio queda en la auditoría.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Vincular un tenant a una aplicación del PDP (Priority: P1)

Un administrador (AP) elige una aplicación ya registrada en el PDP y la vincula a su tenant. El servicio guarda el identificador de la aplicación como ancla a la que se asociará la configuración de negocio del componente consumidor.

**Why this priority**: sin el vínculo no hay a qué asociar canales, plantillas ni límites; es el valor mínimo entregable.

**Independent Test**: con una aplicación existente en un PDP simulado, el administrador crea el vínculo y lo consulta; aparece con el identificador y el nombre que devuelve el PDP.

**Acceptance Scenarios**:

1. **Given** una aplicación registrada en el PDP y un administrador autenticado, **When** crea el vínculo indicando su identificador, **Then** el servicio lo confirma y lo devuelve con identificador y nombre del PDP (FR-001, FR-002).
2. **Given** un identificador que el PDP no reconoce, **When** el administrador intenta vincularlo, **Then** el servicio rechaza la solicitud sin crear nada (FR-003).
3. **Given** un vínculo existente para el mismo tenant y aplicación, **When** se repite la solicitud, **Then** se devuelve el mismo vínculo, sin duplicados (FR-004).
4. **Given** el PDP caído o lento, **When** se intenta vincular, **Then** el servicio responde indisponibilidad y no crea el vínculo (FR-003).

---

### User Story 2 - Consultar y retirar vínculos de mi tenant (Priority: P2)

El administrador lista las aplicaciones vinculadas a su tenant, con el enlace al PDP donde se gestionan registro, credencial y administradores, y puede retirar un vínculo.

**Why this priority**: permite operar el vínculo y cumple que alta y credencial viven en el PDP.

**Independent Test**: dos tenants con vínculos distintos; cada uno lista solo los suyos y retira uno sin afectar al otro.

**Acceptance Scenarios**:

1. **Given** dos vínculos en el tenant A y uno en el B, **When** el administrador de A lista, **Then** ve solo los suyos, cada uno con el enlace de gestión del PDP (FR-005, FR-006).
2. **Given** un vínculo del tenant A, **When** el administrador de B intenta retirarlo, **Then** responde "no existe" y el vínculo permanece (FR-007).
3. **Given** un vínculo propio, **When** el administrador lo retira, **Then** deja de listarse (FR-007).

---

### User Story 3 - Auditoría de cada vínculo y cambio (Priority: P2)

Cada alta y baja deja un registro de auditoría con actor, instante, tenant, aplicación y acción.

**Why this priority**: criterio de aceptación explícito de la historia.

**Independent Test**: tras crear y retirar un vínculo existen dos registros en orden; una solicitud rechazada no deja registro de éxito.

**Acceptance Scenarios**:

1. **Given** un vínculo creado, **When** se consulta la auditoría, **Then** hay un registro `VINCULADA` con actor, tenant, aplicación e instante (FR-008).
2. **Given** un vínculo retirado, **When** se consulta la auditoría, **Then** hay un registro `DESVINCULADA` y el `VINCULADA` previo se conserva (FR-008).

---

### Edge Cases

- Sesión sin permiso de administrador en el PDP sobre esa aplicación: el PDP responde 403 y el servicio lo propaga como prohibido (FR-003).
- Nombre vacío o repetido en el PDP: la aplicación se identifica siempre por su identificador, nunca por el nombre (FR-002).
- Fallo al escribir la auditoría: el vínculo no se confirma (FR-008).
- Dos solicitudes concurrentes del mismo vínculo: solo existe uno (FR-004).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El servicio MUST permitir a un administrador vincular el tenant de su sesión a una aplicación del PDP indicando su identificador.
- **FR-002**: El vínculo MUST guardar identificador y nombre tal como los entrega el PDP; el identificador es la clave de comparación.
- **FR-003**: Antes de crear el vínculo el servicio MUST comprobar en el PDP que la aplicación existe, con la sesión del propio administrador. Inexistente: rechazo. Sin permiso: prohibido. PDP inaccesible o fuera de plazo: indisponible. En ningún caso se crea el vínculo.
- **FR-004**: MUST existir como máximo un vínculo por tenant y aplicación, también bajo concurrencia; repetir la creación devuelve el existente.
- **FR-005**: Un administrador MUST poder listar los vínculos de su tenant y solo los de su tenant.
- **FR-006**: Cada vínculo listado MUST incluir el enlace al PDP donde se gestionan registro, credencial y administradores; el servicio no crea aplicaciones, no emite ni guarda credenciales y no administra administradores.
- **FR-007**: Un administrador MUST poder retirar un vínculo de su tenant; retirar uno ajeno o inexistente responde "no existe" sin revelar su existencia.
- **FR-008**: Cada alta y baja MUST dejar un registro de auditoría de solo agregado (actor, tenant, aplicación, acción, instante, correlación) escrito antes de confirmar; nunca contiene la cookie de sesión ni credenciales.
- **FR-009**: Las operaciones MUST requerir el rol ADMINISTRADOR y ejecutarse en el tenant de la sesión; el tenant nunca se toma del cuerpo ni de la query.
- **FR-010**: El vínculo es el ancla de la configuración de negocio; asociar efectivamente canales, plantillas y límites a él queda fuera de esta historia (ver Assumptions).

### Key Entities

- **Vínculo de aplicación**: tenant, identificador de aplicación del PDP, nombre del PDP, creador, instante de creación. Único por (tenant, aplicación).
- **Registro de auditoría de vínculo**: tenant, identificador de aplicación, acción (`VINCULADA` o `DESVINCULADA`), actor, instante, correlación. Solo se agrega.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de las altas y bajas confirmadas tiene exactamente un registro de auditoría; ninguna operación rechazada deja uno de éxito (prueba automatizada).
- **SC-002**: Con dos tenants, ningún listado, retiro ni auditoría devuelve o modifica datos del otro (prueba automatizada con dos tenants).
- **SC-003**: Veinte solicitudes concurrentes de la misma alta producen un único vínculo (prueba automatizada).
- **SC-004**: Con el PDP detenido, el alta responde indisponible en un máximo de 5 s sin crear vínculo (prueba automatizada con aserción de `Duration`).
- **SC-005**: Un administrador completa el alta en una sola solicitud cuando la aplicación ya existe en el PDP.

## Assumptions

- Dependencias: B3a (gestión de aplicaciones en la consola) y B6 (auditoría). B6 no existe en el código; esta historia incluye un registro mínimo de solo agregado detrás de un puerto para que B6 lo sustituya o consuma. Se documenta como excepción con dueño y fecha en el plan (Principio VII).
- El PDP 0.1.6 solo acepta la cookie de sesión de un administrador en su API de administración; se reutiliza la sesión de quien llama, sin token técnico. No se ha probado contra un PDP real.
- Preguntas abiertas para Seguridad (pendientes; se aplica el valor por defecto):
  - ¿`applicationId` equivale a `client_id`? Por defecto no: se usa el `id` que devuelve la API del PDP; si la respuesta es sí, cambia solo la comparación.
  - ¿Una aplicación puede estar en varios tenants? Por defecto sí: la unicidad es por (tenant, aplicación).
- Plantillas y límites por aplicación no existen aún como configuración del servicio: el vínculo es el ancla, no la asociación completa.
- La URL de gestión del PDP sale de la configuración del despliegue.
- Fuera de alcance: crear aplicaciones, credenciales, administradores y la pantalla de la consola.
