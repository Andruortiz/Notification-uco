# Feature Specification: Pruebas de carga con resultado medible (HU2-122)

**Feature Branch**: `feature/HU2-122-pruebas-carga-medibles`
**Created**: 2026-10-24
**Status**: Draft
**Input**: "Como AP quiero demostrar con números el cumplimiento de RNF-03 (>= 500 por minuto por réplica) y RNF-02 para sostener la entrega. Script reproducible (k6 o Gatling) contra el servicio con proveedor simulado. El resultado queda visible en el dashboard de métricas existente. Se vuelve a ejecutar al cerrar los niveles posteriores."

Trazabilidad: RNF-02 y RNF-03 en `docs/requisitos.md`; el FR-013 de `specs/021-dashboard-observabilidad/spec.md` dejó la prueba de carga de RNF-03 fuera del tablero; métricas en `specs/020-observabilidad-completa/`.

## Contexto verificado

- RNF-02: aceptación con p95 <= 200 ms. RNF-03: >= 500 notificaciones por minuto por réplica.
- `POST /notifications` responde 202 tras persistir en PENDING; el despacho es asíncrono por RabbitMQ.
- El tablero (`observability/grafana/dashboards/notification-service.json`) ya tiene "RNF-02 Latencia de aceptacion" (p95 desde `http_server_requests_seconds`) y "RNF-03 Rendimiento por replica" (aceptadas por minuto), más `notification_dispatched_total`. Prometheus raspa `host.docker.internal:8061` cada 15 s y retiene 31 días.
- El proveedor `simulated` responde según `SIMULATED_PROVIDER_RESULT` (por defecto `ACCEPTED`) y no hace llamadas externas.
- Autenticación hoy: Bearer JWT. Modo `local` con secreto compartido `AUTH_JWT_HS256_SECRET`, o PEP con sesión del PDP (cookie `SECURITY_BASELINE_SESSION`). Otra historia en curso retira el modo `local`.
- Dato orientativo: un lote real de 100 correos con Brevo tardó cerca de 30 s de punta a punta. No es baseline de esta prueba, que usa proveedor simulado.

## Clarifications

### Session 2026-10-24

Decisiones recibidas con el encargo:

- Q: ¿Con qué frecuencia corre? A: cada noche, como job programado de CI; no bloquea PR ni `verify`.
- Q: ¿Dónde vive el script? A: fuera del producto, en `loadtest/` en la raíz, sin tocar módulos Maven ni `pom.xml` ([FR-001](#functional-requirements)).
- Q: ¿Qué es el resultado? A: evidencia, artefacto de CI ([FR-007](#functional-requirements)).
- Q: ¿Contra qué entorno? A: pila del `docker-compose.yml` con proveedor `simulated`, nunca producción ([FR-002](#functional-requirements)).

Propuestas del autor pendientes de aprobación (ver `research.md`): herramienta k6 (D1), identidad de la prueba (D2), visibilidad en el tablero (D3), umbrales y duración (D4). Preguntas abiertas en `plan.md#preguntas-abiertas`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Demostrar RNF-02 y RNF-03 con una ejecución reproducible (Priority: P1)

Como AP ejecuto un único comando contra la pila local y obtengo un veredicto numérico: notificaciones por minuto aceptadas y despachadas, y p95 de aceptación, frente a 500/min y 200 ms.

**Why this priority**: es el objetivo de la historia; sin veredicto numérico no hay evidencia.

**Independent Test**: con la pila y proveedor simulado arriba, el script termina con código 0 si se cumplen los umbrales y distinto de 0 si no.

**Acceptance Scenarios**:

1. **Given** la pila con `simulated=ACCEPTED` y una réplica, **When** el script mantiene una tasa de llegada constante superior a 500/min durante la ventana, **Then** >= 99 % de las respuestas son 202 y el p95 es <= 200 ms.
2. **Given** la misma ejecución, **When** termina la ventana, **Then** lo aceptado queda despachado dentro del margen de drenaje y la tasa sostenida de despacho es >= 500/min.
3. **Given** un umbral inalcanzable, **When** corre el script, **Then** falla con código distinto de 0 e indica cuál umbral se incumplió (control negativo).

### User Story 2 - Resultado visible en el tablero existente (Priority: P1)

Como AP abro Grafana y veo, junto a los paneles de RNF-02 y RNF-03, el resultado de la última prueba contra su umbral.

**Why this priority**: criterio de aceptación explícito de la historia.

**Independent Test**: tras una ejecución con la pila de observabilidad arriba, las consultas del tablero devuelven los valores de la prueba; la prueba E2E del tablero sigue en verde.

**Acceptance Scenarios**:

1. **Given** una ejecución terminada, **When** abro el tablero, **Then** veo tasa sostenida y p95 de la última prueba con su umbral dibujado.
2. **Given** ninguna prueba ejecutada, **When** abro el tablero, **Then** los paneles nuevos muestran "sin datos" sin romper el resto.

### User Story 3 - Ejecución nocturna con evidencia archivada (Priority: P2)

Como AP quiero que la prueba corra cada noche y deje su resultado como artefacto de CI para comparar entre cierres de nivel.

**Why this priority**: sostiene la entrega en el tiempo; depende de las historias 1 y 2.

**Independent Test**: el job propuesto en `plan.md` se lanza con `workflow_dispatch` y publica el artefacto.

**Acceptance Scenarios**:

1. **Given** el job programado, **When** corre, **Then** publica `resultado.json` con fecha y commit como artefacto retenido.
2. **Given** un umbral incumplido, **When** termina, **Then** el job queda en rojo sin afectar los PR.

### Edge Cases

- Proveedor distinto de `simulated`: el script se niega a generar carga ([FR-002](#functional-requirements)).
- Datos de ejecuciones previas: `externalId` único por iteración y tenant propio de la prueba; el despacho se mide como incremento (delta) de `notification_dispatched_total`, no como valor absoluto.
- Prometheus no disponible: la prueba falla con mensaje explícito; no hay veredicto de despacho sin la métrica del servidor.
- El puerto de gestión 8061 no se publica (spec 021, FR-005): las métricas del servidor se leen siempre vía Prometheus.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El script MUST vivir en `loadtest/` en la raíz, fuera de `core`, `infrastructure` y `utils`, sin dependencias Maven nuevas.
- **FR-002**: El script MUST negarse a generar carga si el objetivo no confirma proveedor `simulated` con resultado `ACCEPTED` (variable de guarda explícita más verificación), y MUST NOT apuntar por defecto a un host distinto de `localhost`.
- **FR-003**: El script MUST generar una tasa de llegada constante configurable (por defecto 700 por minuto) durante una ventana configurable (por defecto 5 min) sobre `POST /notifications`, con cuerpo válido, `externalId` único y canal EMAIL.
- **FR-004**: El script MUST autenticarse con un Bearer o cookie recibidos por variable de entorno; MUST NOT escribirlos en logs, resumen ni artefactos.
- **FR-005**: El veredicto MUST fallar si: p95 de la petición > 200 ms (RNF-02); ratio de 202 < 99 %; aceptación sostenida < 500/min; o despacho (incremento de `notification_dispatched_total` en ventana más drenaje) < 500/min (RNF-03).
- **FR-006**: El resultado MUST publicarse en Prometheus con métricas `loadtest_*` y mostrarse en el tablero existente con su umbral, sin tenant ni notificationId como etiquetas ([FR-009 de la spec 021](../021-dashboard-observabilidad/spec.md)).
- **FR-007**: Cada ejecución MUST producir `resultado.json` (fecha, commit, parámetros, mediciones, umbrales, veredicto) sin secretos, para archivar como artefacto de CI.
- **FR-008**: Una prueba E2E bloqueante en `verify` MUST comprobar que las métricas `loadtest_*` y los umbrales del tablero coinciden con los que emite el script, con un control positivo (una referencia inexistente se detecta), como en la spec 021.
- **FR-009**: `loadtest/README.md` MUST documentar ejecución local y en CI, variables, y cómo repetirla al cerrar niveles posteriores.
- **FR-010**: La documentación MUST declarar el alcance: una réplica con proveedor simulado; no demuestra rendimiento con proveedores reales ni con varias réplicas.

### Key Entities

- **Ejecución de carga**: parámetros, mediciones del cliente (k6), mediciones del servidor (Prometheus), veredicto.
- **Umbral**: valor numérico de RNF-02/RNF-03 compartido entre script y tablero.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Una ejecución con valores por defecto dura <= 10 min, incluidos ventana y drenaje.
- **SC-002**: Dos ejecuciones sobre el mismo commit dan el mismo veredicto y tasas dentro de +-10 %.
- **SC-003**: El control negativo termina con código distinto de 0 en el 100 % de los casos probados.
- **SC-004**: Ningún secreto aparece en `resultado.json`, salida del script ni artefacto (verificado por prueba).
- **SC-005**: El job nocturno no añade tiempo ni dependencias al flujo de PR.

## Assumptions

- Una réplica, MongoDB y RabbitMQ del compose; el runner de CI sostiene 700/min con proveedor simulado.
- Existe un modo de obtener un Bearer o cookie válidos en el entorno de la prueba (`plan.md#preguntas-abiertas`, Q1).
- El job nocturno lo incorpora quien administra el CI compartido; esta historia solo lo propone.
