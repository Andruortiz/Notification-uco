# Feature Specification: Panel de salud de proveedores (037)

**Feature Branch**: `feature/HU2-121-panel-salud-proveedores`
**Created**: 2026-10-24
**Status**: Draft (corregido tras revisión)
**Historia**: HU2-121 (rol OP, P1). Se alimenta de las métricas de [020](../020-observabilidad-completa/spec.md#FR-003) y del Prometheus de [021](../021-dashboard-observabilidad/spec.md#FR-006). Se implementa después de E3 (circuito y failedOver).

## Clarifications

- Q1: Vista propia ligera: una tabla por proveedor leída de Prometheus, con enlace a Grafana. No es un tablero nuevo ni un motor de alertas.
- Q2: Sin métricas nuevas ni cambios a 020. Todo se deriva de `notification_dispatched_total{channel,provider,result}` y `notification_provider_duration_seconds{provider,result}`. `notification_errors_total` NO sirve para proveedores: registra errores de despacho (unos con `provider=none` y otros, como `DISPATCH_EVENTS_NOT_PUBLISHED`, con proveedor) y de reencolado, pero no los fallos de la llamada al proveedor.
- Q3: Las métricas no llevan `tenantId` ([020 FR-008](../020-observabilidad-completa/spec.md)). La salud es global de plataforma (A1).
- Q4: "Último error" se limita a lo derivable de `result` (`recoverable` o `failed`) y su instante aproximado. No hay código `NTF-n` de proveedor ni texto del proveedor. El esquema deja `code` y `category` opcionales para cuando una historia posterior instrumente errores por proveedor.
- Q5: Rol mínimo `OPERADOR` (el código tiene 3 roles hasta que B2 cambie el modelo).
- Q6: Fuera de alcance: página de estado (H6), alertas (G9), y el circuito abierto y `failedOver` (E3). El esquema los admite como extensión: estado `CIRCUIT_OPEN` y campo `failedOver`, sin construirlos aquí.

## User Scenarios & Testing

### User Story 1 - Ver la salud de cada proveedor (Priority: P1)

Como OP quiero ver, por proveedor y canal, estado, tasa de éxito, latencia y último error para detectar problemas antes de que los clientes los reporten.

**Independent Test**: Con Prometheus real y el proveedor simulado en `ACCEPTED` y luego en `PERMANENT_FAILURE`, consultar el panel y comprobar tasa, latencia y último error.

**Acceptance Scenarios**:

1. **Given** despachos recientes, **When** el OP consulta, **Then** ve una fila por proveedor/canal con estado, tasa de éxito y latencia p95.
2. **Given** un proveedor con intentos `recoverable` o `failed` recientes, **When** se consulta, **Then** el estado es `DEGRADED` o `DOWN` y `lastError` indica `result` (`recoverable` o `failed`) y el instante.
3. **Given** un proveedor sin volumen suficiente en la ventana, **When** se consulta, **Then** el estado es `UNKNOWN` y tasa y latencia son nulas.
4. **Given** un proveedor desactivado por configuración o falta de credenciales, **When** se consulta, **Then** sale `DOWN` si tiene tráfico: límite documentado, porque la métrica no distingue `ProviderDisabledException` de un fallo (ver FR-003); vence 2026-12-15 (A6).
5. **Given** el contrato, **When** se inspecciona, **Then** ningún campo contiene texto crudo del proveedor, destinatario, `tenantId` ni credenciales.

### User Story 2 - Saltar a Grafana (Priority: P2)

1. **Given** Grafana configurado, **When** se consulta, **Then** la respuesta incluye la URL del tablero `notification-service` (021), única para todo el panel.
2. **Given** Grafana no configurado, **When** se consulta, **Then** `grafanaUrl` es nulo y la tabla funciona.

### User Story 3 - Degradación ordenada si Prometheus no responde (Priority: P2)

1. **Given** Prometheus caído o lento, **When** se consulta, **Then** responde 503 con `INFRASTRUCTURE_ERROR` (5000) y `correlationId`, dentro del tiempo límite, sin la URL de Prometheus en cuerpo ni logs de respuesta.

### Edge Cases

- Contadores reiniciados por reinicio de réplica: `increase()` los tolera.
- Varias réplicas: agregación con `sum by`.
- Proveedor configurado pero inactivo, o que nunca despachó: no aparece (A2). El catálogo real cambia con E3; se revisa entonces (real primero).

## Requirements

- **FR-001**: El servicio MUST exponer `GET /provider-health` con, por proveedor/canal: `provider`, `channel`, `status` (`HEALTHY`, `DEGRADED`, `DOWN`, `UNKNOWN`; `CIRCUIT_OPEN` reservado), `successRate`, `sampleCount`, `latencyP95Millis`, `lastError` (`result`, `at`; `code` y `category` opcionales reservados) y `windowSeconds`. `failedOver` reservado como extensión.
- **FR-002**: Todo valor MUST calcularse con consultas a Prometheus sobre las métricas de Q2; no se registran métricas ni se persiste estado.
- **FR-003**: Semántica de la tasa: `delivered / (delivered + failed)` por `provider,channel` en la ventana; los intentos `recoverable` se excluyen del denominador porque son reintentos de la misma notificación y no un veredicto del proveedor, pero cuentan como señal de degradación (si `recoverable` supera el umbral configurable de la proporción de intentos, el estado baja a `DEGRADED`). El volumen es `delivered + failed`; si es menor que `minSamples`, el estado es `UNKNOWN` aunque `recoverable` sea alto (UNKNOWN prevalece sobre DEGRADED). Los fallos por proveedor desactivado (`ProviderDisabledException`) no se distinguen en la métrica; se excluyen si el plan encuentra una señal para hacerlo; si no, queda como límite documentado y el estado `DOWN` de un proveedor desactivado se anota en el contrato (D3 del plan).
- **FR-004**: `lastError` MUST derivarse solo de `result` y el instante; nunca mensaje de excepción ni cuerpo del proveedor.
- **FR-005**: La consulta MUST pasar por un puerto de salida de `core`; el adaptador HTTP vive en `infrastructure`; `core` sin Spring.
- **FR-006**: El endpoint MUST requerir rol `OPERADOR` mediante la regla de ruta del PEP/PDP (`RouteAuthorizationPolicy`); la identidad llega por la sesión del PDP y no de parámetros. El panel no usa `tenantId` porque la salud es global.
- **FR-007**: El enmascarado de datos personales por rol queda sin objeto para este endpoint: no expone datos personales. Una prueba lo afirma; el requisito se cumple por ausencia de datos.
- **FR-008**: La respuesta MUST incluir `grafanaUrl` (nulo si no hay) y la consulta a Prometheus MUST tener tiempo límite configurable; al excederlo, 503.
- **FR-009**: Todo `provider` y `channel` leídos de Prometheus MUST validarse contra el patrón del catálogo (`^[a-z0-9][a-z0-9_-]{0,63}$`) antes de devolverse o de usarse en una URL; los inválidos se descartan. La URL de Prometheus solo viene de configuración, nunca de la petición, y nunca aparece en el 503 ni en logs de respuesta.
- **FR-010**: El endpoint nuevo `GET /provider-health` lo define Contrato en `api-notificaciones.yaml` antes del controller (Principio II). Esta historia no edita ese archivo. La latencia p95 solo existe por proveedor (la métrica no tiene `channel`): se repite en cada canal del mismo proveedor y el contrato lo documenta.

## Success Criteria

- **SC-001**: Con Prometheus alcanzable, la respuesta llega en ≤2 s (aserción de `Duration` en la E2E).
- **SC-002**: En la E2E con proveedor simulado, la tasa coincide con lo despachado y un lote en `PERMANENT_FAILURE` baja el estado a `DOWN` en la consulta siguiente a un scrape, con `lastError.result = failed`.
- **SC-003**: Ninguna respuesta contiene texto fuera de los campos definidos; verificado con un fallo cuyo mensaje incluye un correo y un token, y con una etiqueta `provider` inválida inyectada que se descarta.
- **SC-004**: `CLIENTE` recibe 403; `OPERADOR`, 200.

## Assumptions

- **A1**: Salud global de plataforma; por tenant requeriría una métrica nueva (fuera de alcance).
- **A2**: Los proveedores salen de las etiquetas de Prometheus: los configurados pero inactivos o sin tráfico no aparecen (en vez de `UNKNOWN`). Límite aceptado; el catálogo de `application.yml` cambia con E3 y se reevalúa entonces.
- **A3**: La UI vive en `Front-Notification-mfe` (repo aparte); aquí solo el contrato.
- **A4**: `lastError.at` tiene resolución de 60 s porque los contadores no guardan marca de tiempo.
- **A5**: `PROMETHEUS_URL` y `GRAFANA_URL` por variables de entorno.
- **A6**: Excepción (Principio VII): el código de error por proveedor y la exclusión de proveedores desactivados dependen de instrumentación futura. Dueño: Equipo de backend; fecha: 2026-12-15.
