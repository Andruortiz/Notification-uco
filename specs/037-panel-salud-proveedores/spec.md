# Feature Specification: Panel de salud de proveedores (037)

**Feature Branch**: `feature/HU2-121-panel-salud-proveedores`
**Created**: 2026-10-24
**Status**: Draft
**Historia**: HU2-121 (rol OP, P1). Se alimenta de las métricas de [020](../020-observabilidad-completa/spec.md#FR-003) y del Prometheus de [021](../021-dashboard-observabilidad/spec.md#FR-006).

## Clarifications

- Q1: Vista propia ligera: una tabla por proveedor leída de Prometheus, con enlace a Grafana. No es un tablero nuevo ni un motor de alertas.
- Q2: Sin métricas nuevas. Todo se deriva de `notification_dispatched_total`, `notification_provider_duration_seconds` y `notification_errors_total` (020). Si falta una señal, se documenta y no se instrumenta aquí.
- Q3: Las métricas no llevan `tenantId` ([020 FR-008](../020-observabilidad-completa/spec.md)). La salud es global de la plataforma, no por tenant: es el estado del proveedor, no del cliente. Ver supuesto A1.
- Q4: "Último error" es el código `NTF-n` del catálogo `ErrorCode` con su categoría y el instante aproximado; nunca texto del proveedor ni datos personales.
- Q5: Rol mínimo `OPERADOR`. El enlace a Grafana solo se entrega a quien lo ve permitido por política (A3).
- Q6: Fuera de alcance: página de estado pública (H6) y alertas (G9); dependen de este contrato pero no se construyen.

## User Scenarios & Testing

### User Story 1 - Ver la salud de cada proveedor (Priority: P1)

Como OP quiero ver, por proveedor, su estado, tasa de éxito, latencia y último error para detectar problemas antes de que los clientes los reporten.

**Independent Test**: Con Prometheus real y el proveedor simulado en `ACCEPTED`, luego en fallo, consultar el panel y comprobar que la tasa de éxito, la latencia y el último error reflejan lo despachado.

**Acceptance Scenarios**:

1. **Given** despachos recientes de un proveedor, **When** el OP consulta el panel, **Then** ve una fila por proveedor con canal, estado, tasa de éxito (%) y latencia p95 de la ventana.
2. **Given** un proveedor con fallos recuperables recientes, **When** se consulta, **Then** el estado es `DEGRADED` y el último error muestra su código `NTF-n`, categoría e instante.
3. **Given** un proveedor sin despachos en la ventana, **When** se consulta, **Then** el estado es `UNKNOWN` y no se inventa tasa ni latencia (campos nulos).
4. **Given** el contrato de respuesta, **When** se inspecciona, **Then** ningún campo contiene texto crudo de proveedor, destinatario, `tenantId` ni credenciales.

### User Story 2 - Saltar a Grafana (Priority: P2)

Como OP quiero un enlace al tablero de Grafana de 021 para profundizar en un proveedor.

**Acceptance Scenarios**:

1. **Given** Grafana configurado, **When** se consulta el panel, **Then** la respuesta incluye la URL del tablero con el proveedor como variable.
2. **Given** Grafana no configurado, **When** se consulta, **Then** el campo es nulo y la tabla sigue funcionando.

### User Story 3 - Degradación ordenada si Prometheus no responde (Priority: P2)

**Acceptance Scenarios**:

1. **Given** Prometheus caído o lento, **When** se consulta, **Then** la respuesta es 503 con código `NTF-` de infraestructura y `correlationId`, sin cuelgue más allá del tiempo límite.

### Edge Cases

- Proveedor que aparece en el catálogo pero nunca despachó: aparece como `UNKNOWN` (si el catálogo es consultable) o no aparece (A2).
- Contadores reiniciados por reinicio de réplica: `increase()` los tolera; la tabla no muestra tasas negativas.
- Varias réplicas: Prometheus agrega con `sum by (provider, channel)`.

## Requirements

- **FR-001**: El servicio MUST exponer `GET /provider-health` que devuelve una fila por proveedor/canal con `provider`, `channel`, `status` (`HEALTHY`, `DEGRADED`, `DOWN`, `UNKNOWN`), `successRate`, `latencyP95Millis`, `lastError` (`code`, `category`, `at`) y `windowSeconds`.
- **FR-002**: Todo valor MUST calcularse con consultas a Prometheus sobre las métricas 020; no se registran métricas nuevas ni se persiste estado.
- **FR-003**: `status` MUST derivarse de umbrales configurables sobre la tasa de éxito y el volumen mínimo de la ventana; sin volumen suficiente es `UNKNOWN`. Los valores por defecto se fijan en el plan.
- **FR-004**: `lastError.code` MUST ser un valor del catálogo `ErrorCode` y `category` su `FailureCategory`; nunca mensaje de excepción ni cuerpo del proveedor.
- **FR-005**: La consulta a Prometheus MUST pasar por un puerto de salida de `core`; el adaptador HTTP vive en `infrastructure`; `core` no depende de Spring.
- **FR-006**: El endpoint MUST requerir rol `OPERADOR`; el `tenantId` y el rol salen de la sesión del PDP, nunca de parámetros de la petición.
- **FR-007**: Los datos personales MUST aparecer enmascarados salvo para `ADMINISTRADOR`. Este panel no expone ninguno; la prueba lo afirma.
- **FR-008**: La respuesta MUST incluir `grafanaUrl` (nulo si no está configurada) y MUST tener un tiempo límite de consulta a Prometheus configurable; al excederlo responde 503.
- **FR-009**: Los nombres de proveedor y las etiquetas usadas en PromQL MUST ser de catálogo cerrado o validarse contra el patrón del catálogo para evitar inyección de PromQL.
- **FR-010**: Contrato: el endpoint nuevo `GET /provider-health` lo define el equipo de Contrato en `api-notificaciones.yaml` antes del controller (Principio II). Esta historia no edita ese archivo.

## Success Criteria

- **SC-001**: Con Prometheus alcanzable, la respuesta llega en ≤2 s (aserción explícita de `Duration` en la prueba E2E).
- **SC-002**: En la prueba E2E con proveedor simulado, la tasa de éxito calculada coincide con lo despachado (±0), y un fallo inducido pasa el estado a `DEGRADED` o `DOWN` en la siguiente consulta tras un scrape.
- **SC-003**: Ninguna respuesta contiene texto fuera del catálogo en `lastError`; verificado con un error cuyo mensaje incluye un correo y un token.
- **SC-004**: Un usuario con rol `CLIENTE` recibe 403; con `OPERADOR`, 200.

## Assumptions

- **A1**: La salud es de plataforma (sin tenant), porque las métricas no llevan `tenantId`. Un tenant no ve a otros: el panel no contiene datos de tenant. Si se quisiera salud por tenant, haría falta una métrica nueva (fuera de alcance).
- **A2**: La lista de proveedores sale de las etiquetas presentes en Prometheus, no del catálogo Mongo, para no duplicar fuentes.
- **A3**: La UI vive en `Front-Notification-mfe` (repo aparte); aquí solo se define el contrato que consumirá.
- **A4**: El instante de `lastError.at` es aproximado a la resolución del paso de `query_range` (60 s) porque los contadores no guardan marca de tiempo.
- **A5**: Prometheus es alcanzable desde el servicio por URL configurable (`PROMETHEUS_URL`), sin credenciales en repositorio.
