# Implementation Plan: Pagina de estado de la plataforma (050)

**Spec**: [spec.md](spec.md) | **Rama**: `feature/HU2-134-pagina-estado-plataforma` | Se implementa despues de G1 (037).

## Resumen

Un caso de uso en `core` delega en `GetProviderHealthUseCase` (037), reduce los estados al peor global y devuelve una lista de componentes. Un controller expone `GET /platform-status`. Sin puertos de salida, adaptadores ni configuracion nuevos: `grafanaUrl` se reutiliza de 037.

## Decisiones

- D1 (FR-002): orden de gravedad `DOWN`/`CIRCUIT_OPEN` (3) > `DEGRADED` (2) > `HEALTHY` (1); `UNKNOWN` se ignora salvo que todos lo sean. Lista vacia da `UNKNOWN`. Mapeo a global: 3 `OUTAGE`, 2 `DEGRADED`, 1 `OPERATIONAL`.
- D2 (FR-003): `GetPlatformStatusService` depende del puerto de entrada de 037, no de su puerto de salida; `core` sigue sin Spring. Si 037 cambia su tipo de respuesta, solo se ajusta el mapeo.
- D3 (FR-006): errores de 037 se propagan sin transformar; el mapeo a 503 `INFRASTRUCTURE_ERROR` (5000) lo hace el manejador global existente.
- D4 (FR-005): regla `GET /platform-status` -> `OPERADOR` en `RouteAuthorizationPolicy`; lo integra quien corresponda.
- D5 (FR-008, A2): sin incidentes ni mantenimientos. Alternativa descartada: coleccion Mongo con CRUD ADMIN; costo alto sin demanda en pagina interna.
- D6: `grafanaUrl` se toma de la respuesta de 037 (misma propiedad `GRAFANA_URL`); no hay propiedades nuevas.

## Cambios de contrato requeridos (los lista Contrato; no se edita `api-notificaciones.yaml` aqui)

- `GET /platform-status`, `x-minimum-role: OPERADOR`, `x-status: available`, sin parametros, 200 `PlatformStatusResponse`; 401, 403, 503 con `ErrorResponse`.
- `PlatformStatusResponse`: `{ generatedAt, status: OPERATIONAL|DEGRADED|OUTAGE|UNKNOWN, grafanaUrl|null, components: [{ provider, channel, status: HEALTHY|DEGRADED|DOWN|UNKNOWN }] }`. Reusar por `$ref` el enum de estado de `ProviderHealth` (037).
- La descripcion de la operacion cita los limites heredados de 037 y aclara que no hay incidentes ni mantenimientos.
- Ruta literal en ingles sin `/api/v2`; tabla de cabecera de rutas nuevas actualizada.

## Estructura

```text
core/.../port/in/GetPlatformStatusUseCase.java
core/.../usecase/GetPlatformStatusService.java
core/.../domain/valueobject/PlatformStatus*.java
infrastructure/.../adapter/in/web/PlatformStatusController.java
```

Wiring en `UseCaseConfig` y regla en `RouteAuthorizationPolicy`: los integra quien corresponda.

## Pruebas (Principio IV)

- Unitaria `core` (StepVerifier, mock del caso de uso de 037): cada ramal del peor estado, `UNKNOWN` ignorado con otros veredictos, lista vacia, `CIRCUIT_OPEN` como `DOWN`, error propagado.
- E2E `PlatformStatusE2ETest` (`RANDOM_PORT` + Mongo + RabbitMQ + Prometheus, reutilizando fixtures de `ProviderHealthE2ETest`): `ACCEPTED` -> `OPERATIONAL`; `PERMANENT_FAILURE` -> `OUTAGE`; 403 `CLIENTE`; 503 con Prometheus detenido (control positivo 200 previo, cuerpo sin URL); `Duration` <=2 s; correo y token de un fallo ausentes del cuerpo.
- `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Riesgos

- Depende de que 037 exponga un caso de uso de entrada reutilizable (nombre y tipos por confirmar al implementar).
- Valor marginal sobre `GET /provider-health`: ver A4 de la spec.
- Limites de 037 heredados (scrape, proveedor desactivado).
