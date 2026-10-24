# Research: pruebas de carga (HU2-122)

## D1 - Herramienta

Decisión: k6. Descartadas: Gatling (pide Maven/Scala o Java en el repo) y JMeter (XML pesado).
Motivo: un binario, escenario `constant-arrival-rate`, umbrales que fijan el código de salida y salida a Prometheus con `-o experimental-prometheus-rw`.
k6 no entra en `verify`: se instala solo en el job nocturno y en ejecución manual. La lógica de veredicto es un módulo JS puro probado con Node.

## D2 - Identidad de la prueba

El tenant sale de la credencial. B1 (spec 029) se implementa antes: tras B1 el Bearer es un JWT RS256 firmado con un par RSA efímero generado en el job (SC-007 de B1). Hasta B1 se usa el modo `local` con secreto efímero y PEP desactivado, como excepción con dueño y fecha en `plan.md`. El script solo lee `LOADTEST_BEARER`. Tenant dedicado para la prueba.

## D3 - Visibilidad en el tablero

`handleSummary` de k6 no hace remote-write, así que se usa `-o experimental-prometheus-rw` con Gauge y Trend propios (`k6_*`) hacia el Prometheus del tablero (`--web.enable-remote-write-receiver` en `docker-compose.observability.yml`). Descartado Pushgateway: no existe en la pila. El Prometheus de CI es efímero; la evidencia de CI es `resultado.json`.

## D4 - Umbrales y duración

Tasa 700/min, calentamiento 1 min, ventana 5 min, drenaje hasta 2 min. Umbrales: p95 <= 200 ms, 202 >= 99 %, despacho >= 500/min. No se umbraliza la aceptación en peticiones/min: con tasa de llegada fija es tautológica.

## D5 - Despacho como medida de RNF-03

Se mide `notification_dispatched_total{result="delivered",provider="simulated"}` con `increase()` desde el inicio de la ventana medida (no del calentamiento), con margen de 2 scrapes (15 s) y reinicio de contador como ejecución inválida. Se consulta vía Prometheus: 8061 no se publica y solo se raspa con la app como jar en el host.

## D6 - Proveedor simulado

Ningún endpoint revela el proveedor activo, así que la guarda del script es declarativa (localhost + flag). La garantía real la da E3 (`simulated.enabled` falso por defecto).
