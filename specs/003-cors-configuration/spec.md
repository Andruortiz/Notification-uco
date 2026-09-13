# Feature Specification: CORS para el dashboard de frontend

**Feature Branch**: `feature/HU2-071-cors-frontend`

**Created**: 2026-09-13

**Status**: Draft

**Input**: User description: "Como equipo de frontend, quiero que el backend de Notification-uco acepte solicitudes CORS desde el origen del dashboard (http://localhost:5173 en desarrollo, configurable para otros ambientes), para poder consumir la API REST directamente desde el navegador sin que la política de mismo origen bloquee las llamadas."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consumir la API desde el navegador en desarrollo (Priority: P1)

Como equipo de frontend, quiero llamar la API REST de Notification-uco directamente desde el dashboard corriendo en el navegador (en un origen distinto al del backend), para poder construir y probar las pantallas contra el backend real sin que el navegador bloquee las peticiones por la política de mismo origen.

**Why this priority**: Sin esto, ninguna pantalla del frontend puede consumir datos reales del backend — es un bloqueante total, no una mejora incremental.

**Independent Test**: Se puede probar sirviendo el dashboard desde `http://localhost:5173` y haciendo una petición `fetch` directa (sin proxy) a `http://localhost:8060/notifications`; sin esta historia, el navegador la bloquea con un error de CORS antes de que la respuesta llegue a la aplicación.

**Acceptance Scenarios**:

1. **Given** el backend corre en `http://localhost:8060` con el origen `http://localhost:5173` permitido, **When** el dashboard hace una petición `fetch`/XHR directa a la API desde ese origen, **Then** la respuesta llega normalmente al frontend, sin error de CORS en la consola del navegador.
2. **Given** una petición viene de un origen que no está en la lista permitida, **When** el navegador intenta la llamada, **Then** el backend no incluye las cabeceras CORS necesarias y el navegador bloquea la respuesta (comportamiento esperado, no un error del sistema).

---

### User Story 2 - Configurar el origen permitido por ambiente (Priority: P2)

Como equipo de despliegue, quiero que el origen permitido sea configurable sin recompilar, para poder apuntar a distintos orígenes (desarrollo, staging, producción) según dónde corra el frontend.

**Why this priority**: Necesario para que la solución no quede hardcodeada a `localhost` y sirva también cuando el frontend se despliegue en un dominio real — pero no bloquea el trabajo de desarrollo local de hoy.

**Independent Test**: Cambiar la variable de entorno que define el origen permitido y confirmar que el backend refleja ese cambio al reiniciar, sin tocar código.

**Acceptance Scenarios**:

1. **Given** una variable de entorno con uno o más orígenes permitidos, **When** el backend arranca, **Then** solo esos orígenes reciben las cabeceras CORS necesarias.

---

### Edge Cases

- ¿Qué pasa si no se configura ningún origen explícitamente? El sistema debe tener un valor por defecto razonable para desarrollo local (`http://localhost:5173`), sin dejar la API abierta a cualquier origen por omisión.
- ¿Qué pasa con un método HTTP no incluido en la configuración (ej. uno nuevo que el frontend empiece a usar más adelante)? Debe rechazarse por CORS igual que un origen no permitido, hasta que se agregue explícitamente.
- ¿Aplica esto a rutas de la API que todavía no existen (ej. futuros endpoints de catálogo)? Sí — la configuración debe cubrir toda la API de forma transversal, no ruta por ruta, para no repetir este trabajo en cada historia nueva.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE aceptar solicitudes CORS (incluyendo la petición de verificación previa `OPTIONS`) desde uno o más orígenes explícitamente permitidos.
- **FR-002**: El sistema DEBE permitir configurar la lista de orígenes permitidos sin cambiar código (variable de entorno o propiedad externa), con `http://localhost:5173` como valor por defecto para desarrollo.
- **FR-003**: El sistema DEBE permitir, como mínimo, los métodos HTTP que la API ya expone (`GET`, `POST`) y las cabeceras que el cliente necesita enviar (incluyendo `X-Tenant-Id` y `Content-Type`).
- **FR-004**: El sistema NO DEBE permitir por defecto solicitudes desde cualquier origen (`*`) — la lista de orígenes permitidos debe ser explícita.
- **FR-005**: La configuración de CORS DEBE aplicar de forma transversal a toda la API expuesta, no solo a los endpoints que existen hoy.

### Key Entities

No aplica — esta historia no introduce ni modifica entidades de dominio; es configuración transversal de la capa de entrada HTTP.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Una aplicación de frontend corriendo en un origen permitido completa una llamada a la API sin ningún error de CORS visible en el navegador.
- **SC-002**: Una aplicación corriendo en un origen no permitido no logra completar la llamada (el navegador la bloquea), confirmando que la restricción es real y no un permiso abierto de facto.
- **SC-003**: Cambiar el origen permitido no requiere ningún cambio de código, solo de configuración.

## Assumptions

- El frontend (`Front-Notification`, repositorio separado) corre en `http://localhost:5173` durante desarrollo (puerto por defecto de Vite).
- Esta historia no incluye autenticación ni CSRF — sigue siendo un tema aparte, bloqueado por CU-10/DEP-01, ya documentado en el backlog.
- No se requiere soporte de credenciales (cookies) en las solicitudes CORS — la identificación del tenant viaja hoy por una cabecera propia (`X-Tenant-Id`), no por cookies de sesión.
