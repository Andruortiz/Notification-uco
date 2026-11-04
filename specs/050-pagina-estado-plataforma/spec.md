# Feature Specification: Pagina de estado de la plataforma (050)

**Feature Branch**: `feature/HU2-134-pagina-estado-plataforma`
**Created**: 2026-10-25
**Status**: Draft
**Historia**: HU2-134 (rol DEV, P3). Depende de G1 ([037](../037-panel-salud-proveedores/spec.md), `GET /provider-health`, implementada en otra rama). Fuera de alcance: alertas operativas (G9, Alertmanager).

## Clarifications

- Q1: Pagina INTERNA, no publica (decision H6). Al ser interna, Grafana ya cubre el detalle: esta historia se limita a un estado global de un vistazo.
- Q2: Sin metricas nuevas, sin consultas nuevas a Prometheus y sin persistencia. El estado se deriva del resultado de `GetProviderHealthUseCase` de 037 (reuso, no duplicacion).
- Q3: Incidentes y mantenimientos quedan FUERA (A2). Un incidente lo detectan y notifican las alertas de G9; un mantenimiento se marca como anotacion en Grafana. Construir un registro propio exigiria CRUD, persistencia, roles de escritura y UI de edicion, sin demanda que lo justifique en una pagina interna.
- Q4: Rol minimo `OPERADOR`, igual que 037. La respuesta es global de plataforma, sin `tenantId` ni datos personales.
- Q5: Los componentes son los proveedores/canales de 037. Mongo y RabbitMQ no se consultan aqui: su sonda real vive en `/actuator/health` (constitucion, restricciones tecnicas) y no se duplica.

## User Scenarios & Testing

### User Story 1 - Ver el estado global de un vistazo (Priority: P1)

Como DEV quiero un estado global de la plataforma, con el de cada componente, para saber en segundos si algo falla antes de abrir Grafana.

**Independent Test**: Con proveedor simulado en `ACCEPTED` y luego en `PERMANENT_FAILURE`, consultar y comprobar el estado global y el del componente.

**Acceptance Scenarios**:

1. **Given** todos los componentes `HEALTHY`, **When** se consulta, **Then** `status` global es `OPERATIONAL`.
2. **Given** algun componente `DEGRADED` y ninguno `DOWN`, **Then** el global es `DEGRADED`.
3. **Given** algun componente `DOWN`, **Then** el global es `OUTAGE`.
4. **Given** ningun componente con volumen suficiente (todos `UNKNOWN` o lista vacia), **Then** el global es `UNKNOWN`; los `UNKNOWN` no empeoran el global si otros componentes tienen veredicto.
5. **Given** cualquier respuesta, **Then** no contiene texto crudo del proveedor, destinatario, `tenantId` ni credenciales.

### User Story 2 - Saltar al detalle (Priority: P2)

1. **Given** Grafana configurado, **When** se consulta, **Then** la respuesta incluye `grafanaUrl`; sin Grafana es nulo.

### User Story 3 - Degradacion ordenada (Priority: P2)

1. **Given** Prometheus caido o lento, **When** se consulta, **Then** 503 `INFRASTRUCTURE_ERROR` (5000) con `correlationId`, sin la URL de Prometheus (misma politica que [037 FR-008/FR-009](../037-panel-salud-proveedores/spec.md)).

### Edge Cases

- `CIRCUIT_OPEN` (reservado por 037): si llega, cuenta como `DOWN` para el global.
- Proveedor sin trafico: no aparece (limite heredado de [037 A2](../037-panel-salud-proveedores/spec.md)).

## Requirements

- **FR-001**: El servicio MUST exponer `GET /platform-status` con `generatedAt`, `status` global (`OPERATIONAL`, `DEGRADED`, `OUTAGE`, `UNKNOWN`), `components` (`provider`, `channel`, `status`) y `grafanaUrl` (nulo si no hay).
- **FR-002**: El estado global MUST calcularse en `core` como el peor estado entre componentes con veredicto (`DOWN`/`CIRCUIT_OPEN` > `DEGRADED` > `HEALTHY`); `UNKNOWN` solo si ningun componente tiene veredicto.
- **FR-003**: MUST obtener los componentes invocando el caso de uso de 037 (`GetProviderHealthUseCase`); no define puertos de salida nuevos, ni registra metricas, ni persiste estado.
- **FR-004**: Los campos de `components` son un subconjunto de 037 (sin tasa, latencia ni `lastError`); el detalle se consulta en `GET /provider-health` o Grafana.
- **FR-005**: El endpoint MUST requerir `OPERADOR` por la regla de ruta del PEP/PDP (`RouteAuthorizationPolicy`); sin `tenantId`.
- **FR-006**: Las fallas de 037 (503) MUST propagarse sin reinterpretarse ni filtrar la URL de Prometheus.
- **FR-007**: El endpoint nuevo `GET /platform-status` lo define Contrato en `api-notificaciones.yaml` antes del controller (Principio II); esta historia no edita ese archivo.
- **FR-008**: Incidentes y mantenimientos NO se implementan (A2).

## Success Criteria

- **SC-001**: Con Prometheus alcanzable, respuesta en <=2 s (asercion de `Duration` en la E2E).
- **SC-002**: En la E2E con proveedor simulado, `ACCEPTED` produce `OPERATIONAL` y un lote en `PERMANENT_FAILURE` produce `OUTAGE` tras un scrape.
- **SC-003**: `CLIENTE` recibe 403; `OPERADOR`, 200.
- **SC-004**: Ninguna respuesta contiene texto fuera de los campos definidos (prueba con fallo cuyo mensaje incluye correo y token).

## Assumptions

- **A1**: UI en `Front-Notification-mfe` (repo aparte); aqui solo el backend. La UI sondea cada 15 s.
- **A2**: Excepcion (Principio VII): incidentes y mantenimientos propios quedan fuera; se cubren con G9 y anotaciones de Grafana. Reabrir si hay demanda de comunicacion a terceros (pagina publica). Dueno: Equipo de backend; fecha: 2026-12-15.
- **A3**: Hereda los limites de 037 (proveedor desactivado con trafico sale `DOWN`, retraso de scrape, sin trafico no aparece; 037 A6, 2026-12-15).
- **A4**: Pregunta abierta para el usuario: dado que es interna, basta enlazar Grafana y 037; esta historia solo se justifica si se quiere un indicador global unico. Si no, cancelarla y marcar HU2-134 como cubierta por G1.
