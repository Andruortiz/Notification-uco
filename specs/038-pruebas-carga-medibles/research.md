# Research: pruebas de carga (HU2-122)

## D1 - Herramienta

Decisión: k6. Descartadas: Gatling (pide Maven/Scala o Java en el repo, choca con "fuera de los módulos Maven") y JMeter (XML pesado).
Motivo: un binario, script JS corto, escenario `constant-arrival-rate` (tasa fija, la forma correcta de demostrar "500 por minuto"), umbrales que fijan el código de salida y salida a Prometheus por remote-write.
Costo: dependencia de CI (`grafana/setup-k6-action` o imagen `grafana/k6`), versión fijada por digest.

## D2 - Identidad de la prueba

El tenant sale de la credencial, no de un parámetro. Caminos hoy:
- Modo `local` (JWT HS256, secreto compartido): en CI se firma con un secreto efímero del job. Viable ahora, pero lo retira otra historia.
- Sesión del PDP (cookie `SECURITY_BASELINE_SESSION`): requiere PDP, que no existe en CI.

Decisión de diseño: el script solo consume `LOADTEST_BEARER` o `LOADTEST_COOKIE`; cómo se obtienen es un paso del job. Retirar el modo local solo cambia ese paso. Tenant dedicado para la prueba.

## D3 - Visibilidad en el tablero

El tablero lee Prometheus. Propuesta: el script, al terminar, escribe `loadtest_p95_seconds`, `loadtest_sustained_per_minute{kind="accepted|dispatched"}` y `loadtest_verdict` por remote-write (requiere `--web.enable-remote-write-receiver` en `docker-compose.observability.yml`; no se añade Pushgateway). Paneles nuevos junto a RNF-02/03 con umbral dibujado.
Limitación: el Prometheus de CI es efímero; el tablero persistente es el local y la evidencia de CI es `resultado.json`.

## D4 - Umbrales y duración

Tasa 700/min (1,4x el mínimo: evidencia margen). Calentamiento 1 min, ventana 5 min, drenaje hasta 2 min. Umbrales: p95 <= 200 ms, 202 >= 99 %, aceptación y despacho sostenidos >= 500/min. Todo configurable por variable con esos valores por defecto.

## D5 - Despacho como medida de RNF-03

RNF-03 habla de notificaciones, no de peticiones. Con proveedor simulado el delta de `notification_dispatched_total` en la ventana demuestra que la cola no se acumula. Se consulta vía Prometheus porque el puerto 8061 no se publica.
