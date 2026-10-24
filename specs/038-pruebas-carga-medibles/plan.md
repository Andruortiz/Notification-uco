# Implementation Plan: Pruebas de carga con resultado medible (HU2-122)

**Branch**: `feature/HU2-122-pruebas-carga-medibles` | **Spec**: [spec.md](spec.md) | **Decisiones**: [research.md](research.md)

## Resumen

Un script k6 en `loadtest/` genera 700 notificaciones/min durante 5 min contra la app (jar en el host) con proveedor simulado, mide p95 y 202 en el cliente, mide el despacho en Prometheus y emite veredicto con código de salida y `resultado.json`. Las métricas `k6_*` llegan al Prometheus del tablero con `-o experimental-prometheus-rw`, y el tablero gana dos paneles con umbral. La lógica de veredicto es un módulo JS puro probado con Node; k6 solo corre en el job nocturno (propuesto, no escrito). No se toca código de producción, contrato OpenAPI, wiring, `pom.xml` ni `ci-cd.yml`.

Dependencias: B1 (spec 029, identidad RS256 en CI) y E3 (`simulated` acotado) se implementan antes. Roles: 3 en el código hasta B2.

## Constitution Check

- I Hexagonal: sin cambios en `core` ni adaptadores. OK.
- II Contract-first: sin endpoints nuevos. OK.
- III Sin comentarios: aplica a JS y scripts; `NoExplanatoryCommentsTest` solo cubre Java, se respeta por revisión.
- IV Calidad: E2E explícita (tarea 5) sin k6; coherencia script/tablero bloqueante en `verify`. Sin código de producción, la cobertura no cambia.
- V Trazabilidad: commits de una línea; razonamiento aquí.
- VII Sin atajos: excepción abajo.
- VIII/IX: el despacho medido demuestra que nada se acumula bajo carga; ningún secreto en logs ni artefactos.

Excepción (Principio VII): hasta que B1 se fusione, la prueba en CI usa el modo `local` (JWT HS256 con secreto efímero y PEP desactivado). Dueño: Equipo de backend. Fecha: 2026-12-15 o la fusión de B1, lo que ocurra primero; después se pasa a RS256 con par RSA efímero.

Alcance una réplica / proveedor simulado ([FR-010](spec.md#functional-requirements)): dueño AP, revisar al cerrar el nivel que introduzca autoescalado.

## Estructura (fuera de los módulos Maven)

```text
loadtest/
  veredicto.js          módulo puro: umbrales y decisión (sin k6)
  veredicto.test.js     pruebas con node --test
  notificaciones.js     escenario k6, Gauge/Trend k6_*, handleSummary -> resultado.json
  run.sh / run.ps1      guarda de entorno y lanzamiento
  README.md
observability/grafana/dashboards/notification-service.json   dos paneles nuevos
docker-compose.observability.yml                              --web.enable-remote-write-receiver
infrastructure/src/test/java/.../LoadTestDashboardCoherenceTest.java
```

## Diseño del script

1. **Guarda ([FR-002](spec.md#functional-requirements))**: host `localhost` y `LOADTEST_CONFIRM_SIMULATED=true`; otro host exige `LOADTEST_ALLOW_REMOTE=true`. Es declarativa: no hay endpoint que revele el proveedor, y se dice así en el README. La garantía real es E3.
2. **Escenarios**: calentamiento de 1 min a la misma tasa y sin umbrales; luego `constant-arrival-rate` de 700/min durante 5 min (`preAllocatedVUs` 50).
3. **Petición**: `POST /notifications`, `externalId = run-<id>-<iter>`, EMAIL, destinatario sintético, `Authorization: Bearer ${LOADTEST_BEARER}`.
4. **Umbrales k6**: `http_req_duration{expected_response:true}: p(95)<200` y `checks: rate>0.99` (202). "Aceptación >= 500/min" no se verifica: con tasa de llegada fija sería tautológica.
5. **Despacho**: Prometheus es la fuente. Instante de inicio = fin del calentamiento; fin = fin de la ventana más drenaje (sondeo hasta 2 min). Se consulta `sum(increase(notification_dispatched_total{result="delivered",provider="simulated"}[<ventana>]))` evaluada con 2 scrapes (30 s) de margen tras el fin; dividido por los minutos de ventana debe ser >= 500. Si el contador baja respecto al inicio (reinicio), la ejecución falla como inválida. Sin Prometheus alcanzable, `setup()` aborta.
6. **Salida**: Gauge/Trend `k6_*` por `-o experimental-prometheus-rw` (`K6_PROMETHEUS_RW_SERVER_URL`); `handleSummary` solo escribe `resultado.json` (sin cabeceras ni credenciales). El código de salida lo fija k6.

## Tablero

Dos paneles en la fila de RNF-02 y RNF-03: "Última prueba de carga: p95 (s)" con umbral 0,2 y "Última prueba de carga: despachadas por minuto" con umbral 500, sobre las series `k6_*`. Sin etiquetas de tenant ni de id; sin datos se muestra vacío sin afectar los demás paneles.

## Tareas previstas (las detalla `/speckit-tasks` tras la aprobación)

1. `veredicto.test.js` en rojo, luego `veredicto.js`.
2. Prueba de coherencia (Java, sin k6) que falla: lee tablero y `veredicto.js` y compara nombres `k6_*` y umbrales; control positivo con referencia inexistente.
3. `notificaciones.js`, `run.sh`, `run.ps1`.
4. Remote-write receiver en `docker-compose.observability.yml`; paneles; `loadtest/README.md` con el prerrequisito Node 20+ y el alcance "app como jar en el host" y enlace desde el README principal.
5. **T-E2E (Principio IV, sin k6)**: `@SpringBootTest(RANDOM_PORT)` con Mongo y RabbitMQ en Testcontainers; el test envía las peticiones con `WebTestClient` a la tasa corta del escenario (10 s) y evalúa el módulo de veredicto con Node sobre las mediciones reales (p95, 202, delta de `notification_dispatched_total` leído del registro de métricas del propio contexto), con control negativo (umbral inalcanzable da veredicto de fallo). Node 20+ es prerrequisito documentado de `verify` (ubuntu-latest lo trae preinstalado), sin fallback en Java.
6. Prueba de ausencia de secretos en `resultado.json` con control positivo (SC-004).
7. Cierre: `spotless:apply`, `verify` único en segundo plano, ejecución real con k6 y valores por defecto, resultado en el cuerpo del PR.

## Job nocturno propuesto (no se escribe aquí)

Workflow aparte `carga-nocturna.yml`, de quien administra CI. `ci-cd.yml` solo corre en `pull_request` y tags, y `codeql.yml` los domingos 01:30, así que 06:00 UTC no choca con ninguno.

- `on: schedule` (cron 06:00 UTC) y `workflow_dispatch`.
- Pasos: checkout; JDK 25; `./mvnw -B -ntp -DskipTests package`; `docker compose up -d mongodb rabbitmq` y Prometheus con `-f docker-compose.observability.yml`; arrancar el jar en el host con `SIMULATED_PROVIDER_RESULT=ACCEPTED` y el perfil que dejen B1 y E3 (hasta B1: `local`, PEP desactivado, secreto HS256 efímero); firmar el Bearer; k6 por `grafana/setup-k6-action` fijado por SHA; `loadtest/run.sh`; `actions/upload-artifact` de `resultado.json`, `retention-days: 90`.
- `timeout-minutes: 20`, `permissions: contents: read`, sin secretos del repositorio.
- Ante rojo, se repite una vez (`workflow_dispatch`) antes de declarar regresión; se exigen dos ejecuciones concordantes.

## Preguntas abiertas

- **Q1 - Dónde corre la app en la prueba.** Recomendación: jar en el host (única forma en que Prometheus raspa 8061 hoy); contenerizarla requiere red compartida y está fuera de alcance.
- **Q2 - Tablero persistente vs CI efímero.** Recomendación: el criterio de visibilidad se cumple con el Prometheus local del AP; en CI la evidencia es `resultado.json`. No publicar un Prometheus de larga vida.
- **Q3 - Tasa objetivo.** Recomendación: 700/min; si el runner no la sostiene, bajar a 600 antes que relajar el 500.
- **Q4 - Dependencia de Node en `verify`.** Cerrada: Node 20+ es prerrequisito documentado en `loadtest/README.md` y en este plan; sin fallback en Java.
- **Q5 - Etiquetas de despacho.** Resuelta con el código: `notification.dispatched` tiene `channel`, `provider` y `result` (`delivered`, `recoverable`, `failed`); se suma `result="delivered",provider="simulated"`.

## Riesgos

- Falsos rojos por variabilidad del runner: calentamiento, margen de tasa y doble ejecución.
- Medición de despacho depende del scrape de 15 s: margen de 2 scrapes y ventana de 5 min.
- Nombres `k6_*` acoplados al tablero: los protege la prueba de coherencia.
- Credenciales enmascaradas en CI y nunca impresas.
