# Implementation Plan: Pruebas de carga con resultado medible (HU2-122)

**Branch**: `feature/HU2-122-pruebas-carga-medibles` | **Spec**: [spec.md](spec.md) | **Decisiones**: [research.md](research.md)

## Resumen

Un script k6 en `loadtest/` genera 700 notificaciones/min durante 5 min contra la pila del compose con proveedor simulado, mide p95 y 202 en el cliente, mide el despacho en Prometheus y emite un veredicto con código de salida y `resultado.json`. Las métricas `loadtest_*` llegan al Prometheus del tablero, que gana dos paneles con umbral. Un job nocturno (propuesto, no escrito) corre todo en CI y archiva el artefacto. No se toca código de producción, contrato OpenAPI, wiring ni `pom.xml`.

## Constitution Check

- I Hexagonal: sin cambios en `core` ni adaptadores. OK.
- II Contract-first: sin endpoints nuevos; no se toca `api-notificaciones.yaml`. OK.
- III Sin comentarios: aplica al JS y a los scripts; la prueba `NoExplanatoryCommentsTest` solo cubre Java, así que se respeta por revisión. OK.
- IV Calidad: E2E explícita (T-E2E) y prueba de coherencia script/tablero bloqueante en `verify`; la prueba de carga misma es nocturna, no de PR. La cobertura no cambia (sin código de producción).
- V Trazabilidad: commits de una línea; razonamiento aquí.
- VII Sin atajos: excepciones y dueños en la tabla de abajo.
- VIII/IX: el veredicto de despacho verifica que nada se pierde ni se acumula bajo carga; los logs de k6 no llevan secretos.

Excepciones (Principio VII):

- Dependencia del modo `local` para obtener el Bearer en CI, hasta que la historia que lo retira defina el reemplazo. Dueño: autor de HU2-122 junto con quien cierre esa historia. Fecha: el día en que se fusione el retiro del modo local; antes de esa fecha el job nocturno fallará de forma explícita (Q1).
- Alcance una réplica / proveedor simulado ([FR-010](spec.md#functional-requirements)). Dueño: AP. Fecha: revisar al cerrar el nivel que introduzca autoescalado.

## Estructura (todo fuera de los módulos Maven)

```text
loadtest/
  notificaciones.js     escenario k6, umbrales, handleSummary -> resultado.json
  lib/prometheus.js     lectura de dispatched (delta) y escritura de loadtest_* 
  run.sh / run.ps1      guarda de entorno, ejecución y salida
  README.md
observability/grafana/dashboards/notification-service.json   dos paneles nuevos
docker-compose.observability.yml                              --web.enable-remote-write-receiver
infrastructure/src/test/java/.../LoadTestDashboardCoherenceE2ETest.java
```

`run.sh` y `run.ps1` son delgados: validan `LOADTEST_TARGET` (por defecto `http://localhost:8060`), la guarda de proveedor simulado y la presencia de la credencial, y lanzan `k6 run`.

## Diseño del script

1. **Guarda ([FR-002](spec.md#functional-requirements))**: `LOADTEST_CONFIRM_SIMULATED=true` obligatorio y host `localhost` salvo `LOADTEST_ALLOW_REMOTE=true`. Dentro del script, `setup()` comprueba que Prometheus responde; sin él aborta.
2. **Escenarios**: `constant-arrival-rate` (rate=700/60 por segundo con `timeUnit`), `duration` 5 m, `preAllocatedVUs` 50, precedido por calentamiento de 1 min sin umbrales.
3. **Petición**: `POST /notifications` con `externalId = run-<id>-<iter>`, EMAIL, destinatario sintético, `Authorization: Bearer ${LOADTEST_BEARER}` o cookie.
4. **Umbrales k6**: `http_req_duration{expected_response:true}: p(95)<200`, `checks: rate>0.99`, `http_reqs` sostenido >= 500/min (contador dividido por la ventana en `handleSummary`).
5. **Despacho**: en `setup()` lee `sum(notification_dispatched_total)` y, tras el drenaje (sondeo cada 5 s hasta 2 min), el final; delta / minutos de ventana >= 500.
6. **Salida**: `handleSummary` escribe `resultado.json` (sin cabeceras ni credenciales) y las series `loadtest_*` por remote-write; código de salida lo fija k6.

## Tablero

Dos paneles nuevos en la fila de RNF-02 y RNF-03: "Última prueba de carga: p95 (s)" con umbral 0,2 y "Última prueba de carga: sostenido por minuto" (aceptado y despachado) con umbral 500. Sin etiquetas de tenant ni de id. `Sin datos` se muestra sin alterar los demás paneles.

## Tareas previstas (resumen; `/speckit-tasks` las detalla tras la aprobación)

1. Prueba de coherencia script/tablero que falla (rojo) por falta de paneles y de salida del script.
2. `loadtest/notificaciones.js`, `lib/prometheus.js`, `run.sh`, `run.ps1`.
3. Remote-write receiver en `docker-compose.observability.yml`; paneles en el tablero.
4. `loadtest/README.md` y enlace desde el README principal.
5. **T-E2E (Principio IV)**: levantar la pila real (Testcontainers Mongo, RabbitMQ y Prometheus; app con `RANDOM_PORT` y proveedor simulado), ejecutar el script con una ventana corta (10 s a 120/min, umbrales relajados por variable) y comprobar `resultado.json`, las series `loadtest_*` en Prometheus y el control negativo (umbral inalcanzable, salida distinta de 0). Requiere k6 en el entorno: la prueba se omite con `Assumptions.assumeTrue` solo si falta el binario y CI lo instala, de modo que no se omita en CI.
6. Prueba de ausencia de secretos en `resultado.json` y salida (SC-004), con control positivo (un valor sembrado sí se detectaría).
7. Cierre: `spotless:apply`, `verify` único en segundo plano, ejecución real del script con los valores por defecto y registro del resultado en el PR (no en `specs/`).

## Job nocturno propuesto (no se escribe en esta historia)

Para quien administra `.github/workflows/ci-cd.yml` o un workflow aparte `carga-nocturna.yml`:

- `on: schedule` (cron nocturno, 06:00 UTC) y `workflow_dispatch`; sin `pull_request`.
- Pasos: checkout; JDK 25; `./mvnw -B -ntp -DskipTests package`; `docker compose up -d mongodb rabbitmq` y `-f docker-compose.observability.yml up -d prometheus`; arrancar el jar con `SPRING_PROFILES_ACTIVE=local`, `SECURITY_PEP_ENFORCEMENT_ENABLED=false` y secreto JWT efímero; firmar el Bearer; `grafana/setup-k6-action` fijado por SHA; `loadtest/run.sh`; `actions/upload-artifact` de `loadtest/resultado.json` con `retention-days: 90`.
- `timeout-minutes: 20`, `permissions: contents: read`, sin secretos del repositorio.
- Falla en rojo ante incumplimiento; aviso por el mecanismo habitual de notificación de fallos de workflow.

## Preguntas abiertas

- **Q1 - Identidad en CI.** Con el modo `local` retirándose, ¿qué credencial usa la prueba? Recomendación: mientras exista el modo `local`, JWT efímero con PEP desactivado; al retirarse, un perfil de pruebas del propio servicio (solo `local`/`test`) que acepte un Bearer fijo, o PDP en el compose. Decidir con la historia B1 antes de implementar.
- **Q2 - `simulated` en producción.** El catálogo por defecto en `application.yml` lista `simulated,brevo`. La regla transversal pide `simulated` solo en perfiles local y de pruebas. Recomendación: no resolverlo aquí; dependemos de que esa historia acote el proveedor y la guarda FR-002 sigue siendo defensa adicional.
- **Q3 - Tablero persistente vs CI efímero.** El tablero solo muestra datos si Prometheus persiste. Recomendación: el CA se cumple con el Prometheus local (la ejecución manual del AP) y `resultado.json` como evidencia de CI; no publicar un Prometheus de larga vida para esto.
- **Q4 - Tasa objetivo.** 700/min (1,4x). Recomendación: mantenerlo; si el runner no lo sostiene, bajar a 600/min antes que relajar el umbral de 500.
- **Q5 - Dónde vive el job.** Recomendación: workflow separado `carga-nocturna.yml` para no tocar `ci-cd.yml` compartido.
- **Q6 - Mapeo de `notification_dispatched_total`.** Falta confirmar etiquetas/resultado a sumar (¿solo `ACCEPTED` o también `FAILED`?). Recomendación: sumar solo despachos con resultado exitoso del proveedor.

## Riesgos

- Variabilidad del runner de CI puede producir falsos rojos: mitigación con calentamiento, tasa con margen y dos ejecuciones antes de declarar regresión.
- La coherencia script/tablero depende de nombres de métrica: la prueba de [FR-008](spec.md#functional-requirements) la protege.
- Que la prueba E2E necesite k6 añade una dependencia al `verify`; si el equipo no la acepta, la E2E se limita a invocar la lógica de veredicto como módulo JS probado con Node (alternativa a decidir en Q-tasks).
- Credenciales: toda variable de credencial se enmascara en CI y nunca se imprime.
