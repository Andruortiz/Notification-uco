# Tasks: Pruebas de carga con resultado medible (HU2-122)

**Entradas**: [spec.md](spec.md), [plan.md](plan.md), [research.md](research.md). Sin cambios de contrato, wiring ni `pom.xml`.

Desviaciones frente al plan, ya reflejadas en las tareas:

- B1 ya esta implementada: no hay excepcion del modo `local`; el Bearer es un JWT RS256 firmado con un par RSA efimero (`loadtest/token.js`).
- La guarda de entorno y la deteccion de secretos viven en `veredicto.js` (pura y probada); `run.sh`/`run.ps1` solo fijan variables de k6.
- El veredicto de despacho sale de umbrales k6 sobre Gauge/Rate emitidos en `teardown()`; asi el codigo de salida de k6 lo fija k6 (FR-005).
- `DashboardQueries` ignora las series `k6_*` (las emite k6, no el servicio) para que `ObservabilityDashboardE2ETest` siga en verde.

## Fase 1: Modulo de veredicto (prueba primero)

- [ ] T001 `loadtest/package.json` (type module) y `loadtest/veredicto.test.js` con `node --test` en rojo: umbrales, `evaluar`, reinicio de contador invalida, guarda de entorno, deteccion de secretos con control positivo, `construirResultado` sin secretos (SC-004).
- [ ] T002 `loadtest/veredicto.js`, `loadtest/evaluar.js` (CLI stdin/stdout, codigo de salida) y `loadtest/token.js` (par RSA efimero, Bearer RS256).

## Fase 2: Coherencia script/tablero (prueba primero)

- [ ] T003 `LoadTestDashboardCoherenceTest` (Java, sin k6 ni Docker) en rojo: nombres `k6_*` y umbrales del tablero contra `veredicto.js`, control positivo con referencia inexistente, flag de remote-write, `ci-cd.yml` sin k6.

## Fase 3: Entrega

- [ ] T004 `loadtest/notificaciones.js`, `run.sh`, `run.ps1`.
- [ ] T005 `--web.enable-remote-write-receiver` en `docker-compose.observability.yml`; dos paneles en el tablero; `DashboardQueries` ignora `k6_*`.
- [ ] T006 `loadtest/README.md` (Node 20+, alcance una replica, app como jar en el host por 8061) y enlace desde el README principal.
- [ ] T007 `.github/workflows/carga-nocturna.yml` (cron 06:00 UTC, `workflow_dispatch`, artefacto 90 dias, doble ejecucion); `ci-cd.yml` intacto.

## Fase 4: E2E y cierre

- [ ] T008 **E2E** `LoadTestVerdictE2ETest` (sin k6): `@SpringBootTest` + Testcontainers Mongo y RabbitMQ + `WebTestClient` a tasa corta (10 s); mediciones reales (p95, 202, delta de `notification.dispatched`) evaluadas con `evaluar.js` en Node; control negativo con umbral inalcanzable.
- [ ] T009 `spotless:apply`, `spotless:check compile`, pruebas dirigidas (Node, T003, T008, `ObservabilityDashboardE2ETest`); `verify` unico en segundo plano lo lanza quien integra el nivel.
