# Feature Specification: Retirar el ticket de suscripción del stream a favor de la sesión (HU2-111)

**Feature Branch**: `feature/HU2-111-stream-sin-ticket`

**Created**: 2026-10-19

**Status**: Draft

## Contexto

Con el PEP activo, el filtro del starter (HIGHEST_PRECEDENCE+100) responde 401 `TOKEN_INVALID` a toda petición sin Bearer ni
cookie salvo rutas públicas. El flujo `GET /notifications/stream?ticket=` nunca llega al servicio: el `EventSource` nativo no
puede enviar cabeceras y el ticket viaja en la query. Tras HU2-101 el servicio acepta la cookie `SECURITY_BASELINE_SESSION`
y el stream funciona con ella (la cookie sí la envía un `EventSource` con `withCredentials`). El ticket queda sin función.

Decisión del usuario: descartar el ticket a favor de la cookie. Es un cambio incompatible del contrato 2.0.0.

## User Scenarios & Testing

### User Story 1 - El stream se autentica solo con Bearer o cookie (Priority: P1)

Un panel en vivo abre `GET /notifications/stream` con la cookie de sesión (o con Bearer) y recibe la foto inicial y los cambios
de su tenant.

**Why this priority**: es el único camino viable de autenticación del stream con el PEP activo.

**Independent Test**: E2E con dos tenants: cada sesión recibe solo los eventos de su tenant.

**Acceptance Scenarios**:

1. **Given** una sesión válida del tenant A, **When** abre el stream con la cookie, **Then** recibe 200 y solo eventos de A.
2. **Given** sesiones de A y B, **When** ambas abren el stream, **Then** ninguna recibe eventos de la otra.
3. **Given** ninguna credencial, **When** abre el stream, **Then** 401 sin consultar a la plataforma de sesiones.
4. **Given** una cookie desconocida o revocada, **When** abre el stream, **Then** 401.
5. **Given** la plataforma de sesiones caída o lenta, **When** abre el stream con cookie, **Then** 503.
6. **Given** el parámetro `?ticket=` sin Bearer ni cookie, **When** abre el stream, **Then** 401 (el parámetro se ignora).

### User Story 2 - El contrato y la documentación dejan de ofrecer el ticket (Priority: P1)

**Acceptance Scenarios**:

1. **Given** el contrato OpenAPI, **When** se lee, **Then** no define `POST /notifications/stream/tickets`, `SubscriptionTicketResponse`
   ni el parámetro `ticket`, y el registro de cambios documenta el retiro incompatible.
2. **Given** `POST /notifications/stream/tickets`, **When** se invoca, **Then** el servicio no la sirve (4xx).
3. **Given** el catálogo de recursos protegidos del build, **When** se genera, **Then** tiene 16 operaciones en 12 rutas.

## Requirements

- **FR-001**: `GET /notifications/stream` MUST autenticarse por Bearer o cookie, con la precedencia ya vigente (Bearer manda).
- **FR-002**: Sin credenciales MUST responder 401; sesión inválida 401; plataforma de sesiones caída 503.
- **FR-003**: El servicio MUST NOT servir `POST /notifications/stream/tickets` ni aceptar `ticket` como credencial.
- **FR-004**: Se MUST retirar el puerto `SubscriptionTicketPort`, su caso de uso, adaptador Mongo, controlador, respuesta,
  objeto de valor, propiedad `notification.auth.subscription-ticket.ttl-seconds` y la regla de `RouteAuthorizationPolicy`.
- **FR-005**: El contrato MUST actualizarse antes que el código, con el cambio incompatible en el registro de cambios.
- **FR-006**: El README y los quickstarts que mencionan el ticket MUST reflejar el flujo por cookie.
- **FR-007**: Un tenant MUST NOT recibir eventos de otro por el stream (prueba con dos tenants).

## Success Criteria

- **SC-001**: Ninguna referencia a ticket de suscripción queda en `core` ni en `infrastructure/src/main`.
- **SC-002**: Sin credenciales, el stream responde 401 en menos de 2 s; con plataforma caída, 503 en menos de 5 s.
- **SC-003**: `verify` completo en verde con cobertura mínima (80 % líneas, 70 % ramas).

## Assumptions

- La colección Mongo `subscription_tickets` existente en despliegues previos se elimina por el equipo de operaciones; el
  servicio ya no la lee ni escribe (los tickets caducaban en 30 s por índice TTL).
- Frontend queda fuera de alcance; el cambio requerido se lista en el informe de la historia.
