# Feature Specification: Sesión por cookie del BFF/PDP y CORS con credenciales (HU2-101)

**Feature Branch**: `feature/HU2-101-sesion-cookie` (parte de `feature/rutas-sin-dos-puntos`, PR #93 abierto)

**Created**: 2026-10-19

**Status**: Implementado (PR #94); pendiente T023, verificación manual con cookie real contra el PDP

**Input**: User description: "Como usuario del panel web de Notification-uco, quiero que el backend acepte la cookie de
sesión `SECURITY_BASELINE_SESSION` emitida por la plataforma de Seguridad (BFF/PDP) además del Bearer, y que el navegador
pueda enviarla con CORS con credenciales, para operar el panel con mi sesión sin pegar un token a mano."

Trazabilidad: HU2-101 (esta historia), HU2-098 (contrato v2; ya documenta el esquema `SessionCookie`), HU2-096
(autenticación interina por Bearer, de donde sale el modelo de principal), HU2-106 (starter del PEP ya integrado),
HU2-055 (integración definitiva con la plataforma de Seguridad; esta historia no la reemplaza). Principios involucrados:
I (core sin framework, identidad por puerto), II (contrato primero), III (sin comentarios), IV (E2E y aislamiento por
tenant), V (commits), VI (aprobación), VII (sin atajos: lo provisional se registra como excepción).

## Contexto y evidencia

- El starter `security-pep-integration-spring-boot-starter` 0.1.3 acepta como credencial un único `Authorization: Bearer`
  o una cookie que contenga `SECURITY_BASELINE_SESSION=`; reenvía ambos al PEP y, si la decisión es positiva, solo conserva
  la cabecera `X-Decision-Id`. Descarta el cuerpo de la decisión: no expone tenant, usuario ni roles al servicio.
- Su filtro corre con orden `HIGHEST_PRECEDENCE + 100`. El `AuthenticationWebFilter` del servicio corre antes
  (`HIGHEST_PRECEDENCE + 10`), solo lee `Authorization: Bearer` y responde 401 sin él, de modo que una petición con solo
  cookie nunca llega al PEP.
- De la credencial el servicio necesita tenant, identificador de usuario y rol (`AuthenticatedPrincipal`); el aislamiento
  por tenant de todo el servicio depende de ese principal.
- `CorsConfig` no declara credenciales, así que un navegador no puede enviar la cookie en una llamada entre orígenes.
- El PDP expone `GET /api/v1/session`; con una cookie válida (verificado por el usuario el 2026-10-19) responde
  `{"code":"SESSION_ACTIVE","message":"Authenticated session","data":{"subject","name","email","tenantId"},...}`. `name` puede
  venir vacío, no hay roles y el tenant efectivo es `data.tenantId`. Sin cookie responde 401.
- El panel envía la cookie con `credentials: 'include'` y hoy pasa por el proxy de Vite (mismo origen), de modo que CORS
  solo es necesario si se llama al servicio directamente desde otro origen.

## Clarifications

### Session 2026-10-19

Resueltas con la aprobación del plan (2026-10-19; análisis en `research.md`):

- Q: ¿De dónde sale la identidad de una petición autenticada solo por cookie? A: opción C, el servicio consulta
  `GET /api/v1/session` al PDP con la cookie, como puente documentado como excepción; la opción A (el starter expone la
  identidad) es el objetivo.
- Q: ¿Qué rol tiene una sesión de cookie? A: `CLIENTE`, igual que el Bearer con el PEP activo; la autorización fina por ruta
  y rol la hace el PEP y la cookie solo se acepta con el PEP activo.

Siguen abiertas con el equipo de seguridad:

- Si el backend puede llamar `GET /api/v1/session` servicio a servicio y si exige `X-Application-Credential`.
- Tolerancia entre el cierre de sesión o revocación en el PDP y el rechazo en el servicio. Mientras no se confirme no se
  cachea la identidad (cada petición con cookie consulta la sesión).
- Fecha de la opción A.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Operar con la cookie de sesión (Priority: P1)

Un operador con sesión iniciada en la plataforma de Seguridad usa el panel; el navegador adjunta la cookie de sesión y el
servicio lo identifica con su tenant y su usuario sin que haga falta un Bearer.

**Why this priority**: Es el objetivo de la historia; sin esto el panel no funciona con el inicio de sesión real.

**Independent Test**: Petición con solo cookie válida a una operación de lectura; responde 200 con los datos del tenant de
la sesión.

**Acceptance Scenarios**:

1. **Given** una cookie de sesión válida de un usuario del tenant A, **When** consulta sus notificaciones, **Then** recibe
   solo datos del tenant A.
2. **Given** una petición sin Bearer ni cookie, **When** llama a una operación protegida, **Then** recibe 401 con
   `AUTHENTICATION_REQUIRED`.
3. **Given** una cookie inválida, expirada o revocada, **When** llama a una operación protegida, **Then** recibe 401 y no
   se ejecuta ningún caso de uso.
4. **Given** una cookie válida del tenant A y una consulta por identificador de una notificación del tenant B, **When** se
   consulta el detalle, **Then** el resultado es el mismo que para un identificador inexistente (sin cruce entre tenants).

---

### User Story 2 - El Bearer sigue funcionando y tiene precedencia (Priority: P1)

Los clientes sistema que ya usan Bearer no cambian su comportamiento; si una petición trae Bearer y cookie, se usa el Bearer.

**Why this priority**: Evita una regresión del contrato 2.0.0 y define sin ambigüedad cuál credencial manda.

**Independent Test**: Suite existente de autenticación en verde; prueba nueva con ambas credenciales de tenants distintos.

**Acceptance Scenarios**:

1. **Given** un Bearer válido del tenant A y una cookie válida del tenant B, **When** se llama, **Then** la identidad es la
   del tenant A.
2. **Given** un Bearer inválido y una cookie válida, **When** se llama, **Then** recibe 401 (no se cae a la cookie).
3. **Given** el flujo de suscripción en vivo con ticket, **When** no hay Bearer ni cookie, **Then** el ticket sigue
   funcionando como hoy.

---

### User Story 3 - CORS con credenciales para orígenes permitidos (Priority: P2)

Un navegador en un origen permitido puede llamar al servicio con cookies; un origen no permitido no recibe permiso.

**Why this priority**: Necesario solo si el panel llama directamente al servicio; el proxy lo vuelve opcional hoy.

**Independent Test**: Preflight y petición real desde un origen permitido y desde uno no permitido.

**Acceptance Scenarios**:

1. **Given** un origen configurado como permitido, **When** el navegador hace el preflight con credenciales, **Then** la
   respuesta permite credenciales y refleja ese origen exacto (nunca `*`).
2. **Given** un origen no configurado, **When** hace el preflight, **Then** no se concede permiso.
3. **Given** una configuración de orígenes con comodín `*`, **When** arranca el servicio, **Then** el arranque falla de
   forma explícita en vez de combinar comodín y credenciales.

---

### User Story 4 - Contrato y documentación coherentes (Priority: P2)

El contrato y el README dejan de decir que el modo interino solo acepta Bearer y describen la precedencia y los errores.

**Why this priority**: Principio II; el contrato es la fuente de verdad de los clientes.

**Independent Test**: Prueba de contrato sobre la descripción del esquema `SessionCookie` y la seguridad global.

**Acceptance Scenarios**:

1. **Given** el contrato, **When** se lee `SessionCookie`, **Then** indica que el servicio también la acepta, su
   precedencia frente al Bearer y que el PEP la valida.
2. **Given** el contrato, **When** se leen las respuestas 401, **Then** son las mismas para Bearer y cookie.

## Edge Cases

- Cookie con el nombre correcto pero valor vacío o con caracteres no válidos.
- Cabecera `Cookie` con varias cookies, la de sesión no en primera posición, o con un nombre parecido
  (`XSECURITY_BASELINE_SESSION`).
- Varias cabeceras `Authorization` o esquema distinto de Bearer junto con una cookie válida.
- El PDP no responde o tarda: debe darse un rechazo controlado y distinguible de "sesión inválida", sin colgar la petición.
- El PDP devuelve una sesión sin `tenantId` o con una forma inesperada: se rechaza, nunca se asume un tenant.
- Preflight `OPTIONS` sin cookie (el navegador no la envía): sigue exento de autenticación.
- Rutas exentas (`/actuator`, documentación) siguen sin exigir credencial.
- Petición con solo cookie y PEP desactivado (`security.pep.enforcement.enabled=false`): el servicio no puede delegar la
  validación; debe definirse si se acepta o se rechaza la cookie.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El servicio MUST aceptar la cookie `SECURITY_BASELINE_SESSION` como credencial alternativa al Bearer en todas
  las operaciones protegidas.
- **FR-002**: El servicio MUST derivar tenant y usuario de una petición con cookie únicamente de una fuente verificada por la
  plataforma de Seguridad (nunca de cabeceras o parámetros del cliente), y producir el mismo principal que produce el Bearer.
- **FR-003**: El servicio MUST aplicar la precedencia Bearer sobre cookie; un Bearer presente e inválido MUST dar 401 sin
  intentar la cookie.
- **FR-004**: El servicio MUST responder 401 con el cuerpo `AUTHENTICATION_REQUIRED` ya documentado cuando no hay credencial
  o la sesión es inválida, expirada o revocada.
- **FR-005**: El servicio MUST distinguir "sesión inválida" (401) de "plataforma de sesiones no disponible" (503 con error
  documentado), sin dejar la petición colgada: debe haber un tiempo límite acotado.
- **FR-006**: Una sesión por cookie MUST quedar sujeta a la misma autorización por ruta y rol que el Bearer, con el rol
  `CLIENTE` y la autorización fina delegada al PEP, que debe estar activo.
- **FR-007**: El servicio MUST registrar los rechazos por cookie con el mismo formato de log de rechazos de autenticación
  (motivo categórico, tenant si se conoce) y MUST NOT registrar el valor de la cookie.
- **FR-008**: El servicio MUST permitir CORS con credenciales únicamente para los orígenes explícitamente configurados, MUST
  reflejar el origen exacto y MUST fallar al arrancar si la lista de orígenes contiene `*` con credenciales habilitadas.
- **FR-009**: El servicio MUST exponer en CORS las cabeceras ya expuestas (`X-Correlation-Id`) y MUST NOT ampliar métodos ni
  cabeceras permitidas más allá de lo necesario.
- **FR-010**: El flujo de suscripción en vivo por ticket MUST seguir funcionando sin Bearer ni cookie, y con cookie MUST
  autenticar como cualquier otra operación.
- **FR-011**: El contrato MUST actualizar la descripción de `SessionCookie` y la de seguridad global antes del código
  (Principio II), y una prueba de contrato MUST fallar si vuelven a afirmar que solo se acepta Bearer.
- **FR-012**: La resolución de identidad por cookie MUST estar detrás de un puerto de salida de `core` sin dependencias de
  framework; el adaptador vive en `infrastructure`.
- **FR-013**: Si se cachea la identidad resuelta, el servicio MUST acotarla por tiempo y MUST NOT servir una sesión cerrada
  más allá de la tolerancia acordada; mientras no esté confirmada, no se cachea.

### Key Entities

- **Sesión de cookie**: credencial opaca emitida por la plataforma de Seguridad; el servicio no la interpreta, solo la reenvía
  a quien puede validarla.
- **Identidad de sesión**: tenant, usuario y (según decisión) roles asociados a la sesión; se traduce al principal que ya
  usa el servicio.
- **Origen permitido**: origen de navegador autorizado por configuración a llamar con credenciales.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un operador con sesión iniciada completa en el panel el flujo de listar y enviar notificaciones sin aportar un
  token manual (E2E automatizado con cookie).
- **SC-002**: En pruebas con dos tenants, ninguna petición con cookie de un tenant devuelve, modifica ni confirma la existencia
  de datos del otro (0 cruces).
- **SC-003**: El 100 % de las pruebas de autenticación por Bearer existentes sigue en verde sin modificaciones de aserciones.
- **SC-004**: Una sesión inválida o expirada se rechaza con 401 en menos de 2 s, y la indisponibilidad de la plataforma de
  sesiones se responde con 503 en menos de 5 s (aserción explícita de `Duration`).
- **SC-005**: 0 orígenes no configurados reciben permiso CORS con credenciales; 1 origen configurado lo recibe con el origen
  exacto (pruebas de preflight positiva y negativa).
- **SC-006**: Cobertura ≥ 80 % líneas y ≥ 70 % ramas con `verify` en verde.

## Assumptions

- La plataforma de Seguridad sigue siendo la única fuente de verdad de la sesión; el servicio no emite ni firma cookies.
- El PEP (starter 0.1.3) permanece activo en los entornos con sesión por cookie y es quien autoriza el acceso por ruta.
- El nombre de la cookie es fijo (`SECURITY_BASELINE_SESSION`).
- Los orígenes permitidos se configuran por entorno; por defecto solo el origen de desarrollo del panel.
- Esta historia no cambia el modo de Bearer ni la integración definitiva de HU2-055.
