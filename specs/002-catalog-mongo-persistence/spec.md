# Feature Specification: Catálogo de canales persistido en Mongo

**Feature Branch**: `feature/HU2-107-catalogo-mongo`

**Created**: 2026-09-11

**Status**: Draft

**Input**: User description: "Como equipo de desarrollo, quiero un modelo de datos persistido en Mongo para el catálogo, para reemplazar el application.yml estático"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Cambiar el catálogo sin redeploy (Priority: P1)

Como equipo operador de la plataforma, quiero que la configuración de canales, proveedores y schema de contenido viva en una base de datos en lugar de en `application.yml`, para poder corregir o ampliar el catálogo sin reconstruir ni redesplegar el servicio.

**Why this priority**: Es la razón de ser de la historia — hoy cualquier cambio al catálogo (agregar un proveedor, corregir un schema) exige modificar `application.yml` y redesplegar todas las réplicas. Mover el catálogo a Mongo es lo que permite que ADR-0009 ("agregar un canal o proveedor no debe requerir modificar el núcleo") se cumpla también en operación, no solo en el código.

**Independent Test**: Se puede probar insertando una entrada de catálogo directamente en la colección de Mongo (sin tocar `application.yml` ni reiniciar el servicio) y verificando que, tras el intervalo de actualización configurado, el servicio la usa para resolver el canal correspondiente.

**Acceptance Scenarios**:

1. **Given** existe una entrada de catálogo para el canal `EMAIL` en la colección de Mongo, **When** el sistema necesita resolver la ruta activa para `EMAIL`, **Then** devuelve los proveedores y el schema de contenido definidos en esa entrada.
2. **Given** una entrada de catálogo se actualiza directamente en Mongo (por ejemplo, se agrega un proveedor a `EMAIL`), **When** transcurre el intervalo de actualización configurado, **Then** todas las réplicas del servicio reflejan la nueva configuración sin necesidad de reiniciar.
3. **Given** no existe ninguna entrada de catálogo para un canal solicitado, **When** el sistema intenta resolver la ruta activa para ese canal, **Then** no encuentra ruta activa — mismo comportamiento que hoy cuando el canal no aparece en `application.yml`.
4. **Given** el servicio arranca por primera vez contra un ambiente que antes usaba el catálogo estático, **When** se aplica la migración de datos de esta historia, **Then** el catálogo en Mongo contiene exactamente las mismas entradas (canal, proveedores, schema) que tenía `application.yml`, de modo que el comportamiento observable no cambia.

---

### User Story 2 - Resistencia a caídas de Mongo (Priority: P2)

Como equipo operador de la plataforma, quiero que una caída temporal de la base de datos no impida despachar notificaciones con la última configuración de catálogo conocida, para no introducir una nueva dependencia dura en el camino crítico de envío.

**Why this priority**: El catálogo se consulta en el camino de despacho de cada notificación (`ChannelCatalogPort.findActiveRoute`); si pasa a depender de una llamada síncrona a Mongo en cada envío sin ningún resguardo, cualquier degradación de Mongo se convierte en una degradación del despacho completo — justo lo que la restricción técnica de caché local con invalidación por evento y TTL de respaldo (ya definida en la constitución) busca evitar.

**Independent Test**: Se puede probar teniendo el catálogo ya cargado en el servicio, simulando que Mongo deja de responder, y verificando que el servicio sigue resolviendo rutas activas usando la última configuración conocida hasta que Mongo vuelve a estar disponible.

**Acceptance Scenarios**:

1. **Given** el servicio ya cargó el catálogo desde Mongo al menos una vez, **When** Mongo deja de responder temporalmente, **Then** el servicio sigue resolviendo rutas activas usando la última configuración conocida.
2. **Given** Mongo vuelve a estar disponible después de una caída, **When** transcurre el intervalo de actualización configurado, **Then** el servicio retoma la lectura de cambios del catálogo con normalidad.

---

### Edge Cases

- ¿Qué pasa si una entrada de catálogo en Mongo queda mal formada (por ejemplo, lista de proveedores vacía)? El sistema la trata como si esa ruta no existiera, igual que hoy cuando `ChannelEntry.providers()` está vacío o nulo en `application.yml` — no debe tumbar el arranque ni el despacho de otros canales.
- ¿Qué pasa si dos entradas de catálogo coinciden en el mismo canal (duplicado)? Es un estado inconsistente que no debería ocurrir; el sistema debe tener una forma determinística de resolverlo (por ejemplo, restricción de unicidad a nivel de base de datos) en lugar de comportarse de forma impredecible según cuál lea primero.
- ¿Qué pasa la primera vez que el servicio arranca contra un ambiente nuevo (sin datos de catálogo aún cargados)? Debe comportarse igual que hoy con un `application.yml` sin canales definidos: ninguna ruta activa disponible, sin error de arranque.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE resolver la ruta activa de un canal (proveedores y schema de contenido) a partir de datos persistidos en Mongo, en lugar de a partir de `application.yml`.
- **FR-002**: El sistema DEBE seguir exponiendo la misma capacidad que hoy (`ChannelCatalogPort.findActiveRoute`) sin cambiar el contrato visible para quienes ya lo consumen (`SendNotificationService`, `DispatchNotificationService`).
- **FR-003**: El catálogo persistido DEBE ser global — una única configuración por canal, compartida por todos los tenants, igual que el comportamiento actual (el parámetro `tenantId` se sigue recibiendo pero no filtra datos en esta historia).
- **FR-004**: Un cambio en los datos de catálogo persistidos en Mongo DEBE reflejarse en todas las réplicas del servicio dentro de un intervalo de actualización acotado y configurable, sin requerir reinicio ni redespliegue.
- **FR-005**: El sistema DEBE seguir resolviendo rutas activas usando la última configuración de catálogo conocida cuando Mongo no está disponible temporalmente, en lugar de fallar el despacho por esa causa.
- **FR-006**: El sistema DEBE proveer una migración de los datos que hoy existen en `application.yml` (`notification.catalog.channels`) hacia la nueva colección de Mongo, de modo que un ambiente existente conserve el mismo comportamiento observable tras el cambio.
- **FR-007**: El sistema DEBE garantizar unicidad por canal en los datos de catálogo persistidos, para que no puedan coexistir dos entradas activas para el mismo canal.
- **FR-008**: Esta historia NO incluye una API ni herramienta para crear, actualizar o desactivar entradas de catálogo en runtime — la carga de datos se hace por migración/semilla directamente contra Mongo; exponer esa gestión queda para una historia futura.
- **FR-009**: Una entrada de catálogo mal formada (sin proveedores, por ejemplo) DEBE tratarse como ruta inexistente para ese canal, sin afectar la resolución de otros canales ni el arranque del servicio.

### Key Entities

- **ChannelCatalogEntry**: representa la configuración activa de un canal — el canal (`ChannelType`), la lista ordenada de proveedores candidatos (`ProviderId`) y el schema de validación de contenido asociado. Es el equivalente persistido de `ChannelCatalogProperties.ChannelEntry`, con el canal como identificador único.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un cambio en la configuración de un canal (agregar/quitar un proveedor, cambiar el schema) se aplica en todas las réplicas del servicio, dentro del intervalo acotado y configurable de FR-004, sin ningún redespliegue ni reinicio.
- **SC-002**: El comportamiento de resolución de rutas activas observado por quienes envían y despachan notificaciones es idéntico antes y después de la migración, para el mismo conjunto de canales configurados.
- **SC-003**: Una interrupción temporal de la base de datos no provoca ninguna falla de despacho atribuible a la resolución del catálogo, mientras el servicio ya tenía una configuración cargada previamente.
- **SC-004**: Cero canales duplicados o en estado inconsistente en el catálogo persistido en cualquier momento.

## Assumptions

- El catálogo sigue siendo global (una sola configuración compartida por todos los tenants) — el soporte de catálogos por tenant queda explícitamente fuera de alcance de esta historia (decisión confirmada por el usuario).
- Esta historia no expone ninguna API de administración del catálogo; la carga y actualización de datos se hace directamente contra Mongo (migración, semilla o intervención manual) hasta que una historia futura defina esa gestión.
- El intervalo de actualización acotado (FR-004) se apoya en la estrategia de caché local por réplica con invalidación por evento y TTL de respaldo, ya establecida como restricción técnica en la constitución del proyecto — esta historia la aplica al caso concreto del catálogo de canales, no diseña una estrategia nueva.
- `ChannelCatalogPort` y su firma actual (incluido el parámetro `tenantId`, hoy sin uso) no cambian en esta historia.
- La migración de datos (FR-006) es un paso único de transición; una vez completada, `application.yml` deja de ser la fuente de verdad del catálogo pero puede conservarse temporalmente como referencia hasta confirmar la migración en cada ambiente.
