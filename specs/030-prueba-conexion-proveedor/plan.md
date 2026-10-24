# Implementation Plan: Prueba de conexion del proveedor (HU2-114)

**Branch**: `feature/HU2-114-prueba-conexion-proveedor` | **Spec**: [spec.md](spec.md)

## Resumen

Un puerto de salida `ProviderProbePort` que cada adaptador implementa, un caso de uso en `core` que valida canal/proveedor,
mide latencia y aplica el limite de tiempo, y un endpoint `POST` de solo ADMINISTRADOR que devuelve el resultado.

## Constitution Check

- I Hexagonal: `core` define `ProviderProbePort` y `TestProviderConnectionUseCase`; los adaptadores HTTP viven en
  `infrastructure`. Sin Spring en `core`. La latencia usa un `Clock` inyectado.
- II Contract-first: ruta literal `/provider-connection-tests`, sin `:` ni `{}`; el YAML lo anade Contrato antes del controller.
- III Sin comentarios. IV E2E explicito (T-E2E abajo). V commits de una linea. VII sin excepciones nuevas.
- VIII/IX: no hay mensajeria; no aplica RabbitMQ ni ack/DLQ. Metrica `notification.provider.probe` (tags `providerId`,
  `status`) y log estructurado segun FR-007.

## Diseno

**core**
- `port/out/ProviderProbePort`: `ProviderId providerId()`; `Mono<ProbeOutcome> probe()`. `ProbeOutcome`: `OK` o
  `Failed(ProbeErrorCode)`. No devuelve texto del proveedor, solo el codigo cerrado de [FR-004](spec.md#functional-requirements).
- `port/in/TestProviderConnectionUseCase`: `Mono<ProviderProbeResult> test(Channel channel, ProviderId providerId)`.
- Servicio: verifica que el canal exista y que el proveedor este en su catalogo (`ChannelCatalogPort`); si no, error de
  validacion. Si no hay sonda registrada -> `ADAPTER_MISSING`. Si `NotificationSenderPort.disabledReason()` esta presente ->
  `CREDENTIALS_MISSING` sin llamar. Si no, `probe().timeout(limite)` con `TimeoutException` -> `TIMEOUT`; cualquier otro error
  inesperado -> `PROVIDER_ERROR`. `latencyMs` = `Clock` antes/despues.
- Registro de sondas analogo a `NotificationSenderRegistry` (`ProviderProbeRegistry`).

**infrastructure** (adaptadores, una sonda cada uno, reutilizan el `WebClient` y la configuracion del proveedor)
- Brevo: `GET /v3/account`. Twilio: `GET /2010-04-01/Accounts/{accountSid}.json`. FCM: renueva el token de acceso sin usar
  el cache (`invalidate` + `accessToken`). `simulated`: lee `SIMULATED_PROVIDER_RESULT`, sin red.
- Clasificacion: 2xx -> OK; 401/403 -> `AUTH_REJECTED`; error de conexion -> `UNREACHABLE`; otro status -> `PROVIDER_ERROR`.
  Nunca se propaga el cuerpo ni el mensaje de la excepcion a respuesta o log.
- `RouteAuthorizationPolicy`: regla `POST /provider-connection-tests` -> ADMINISTRADOR.
- Controller `ProviderConnectionTestController` y DTOs `ProviderConnectionTestRequest`/`Response`; el rol se exige igual que
  los demas endpoints de ADMINISTRADOR. Propiedad `notification.provider.probe.timeout` (defecto `5s`).
- Cableado de beans (`UseCaseConfig`) lo hace quien posee el wiring: se declara en tasks como dependencia, no se edita aqui.

## Endpoints nuevos (para Contrato; rutas literales)

| Metodo | Ruta | Rol minimo | operationId sugerido | Estado |
|---|---|---|---|---|
| POST | `/provider-connection-tests` | ADMINISTRADOR | `testProviderConnection` | available |

Respuestas: 200 resultado (tambien cuando la prueba falla), 400 `VALIDATION_ERROR`, 401, 403, 429 (si se aprueba la limitacion),
503 `PepUnavailable`. Cabecera `X-Correlation-Id` como el resto. Debe entrar en `protected-resources.json` y quedar vinculado a
`PERFIL_ADMINISTRADOR` en el aprovisionamiento del PDP (HU2-099).

## Contrato para la UI

Peticion `POST /provider-connection-tests`: `{ "channel": "EMAIL"|"SMS"|"PUSH", "providerId": "brevo" }`. Los valores salen de
`GET /channels` y `GET /providers`; la UI habilita "Probar" solo para ADMINISTRADOR y por cada par canal/proveedor listado.

Respuesta 200:
`{ "providerId": "brevo", "channel": "EMAIL", "status": "OK"|"FAILED", "latencyMs": 142, "errorCode": null|"AUTH_REJECTED"|... }`

La UI muestra `latencyMs` siempre y traduce `errorCode` a un texto propio; no existe ningun campo con credenciales ni detalle
crudo del proveedor. 403 -> ocultar el boton; 429 -> "prueba en curso".

## Pruebas

- Unitarias `core` (JUnit + Mockito + `StepVerifier`): validacion canal/proveedor, `CREDENTIALS_MISSING`, `ADAPTER_MISSING`,
  `TIMEOUT` con `VirtualTimeScheduler`, latencia con `Clock` fijo.
- Adaptadores (doble HTTP, patron de los tests de Brevo/Twilio/FCM existentes): 2xx, 401, 500, conexion rechazada y respuesta
  tardia; el doble devuelve la credencial sentinela en el cuerpo de error y la prueba afirma que no aparece en resultado ni logs.
- Seguridad: `RouteAuthorizationPolicy` -> CLIENTE y OPERADOR reciben 403 y la sonda no se invoca (control positivo con
  ADMINISTRADOR en la misma clase).
- Tenant: dos tenants distintos prueban el mismo proveedor; el resultado es identico y el log de cada uno solo lleva su tenant.
- **T-E2E** `ProviderConnectionTestE2ETest` (`@SpringBootTest` RANDOM_PORT + Testcontainers Mongo y RabbitMQ + `WebTestClient`,
  patron `NotificationLiveUpdatesE2ETest`): ADMINISTRADOR prueba `simulated` en los tres canales con `ACCEPTED` y con fallo,
  asercion `Duration` < 1 s; prueba un real contra doble HTTP; CLIENTE -> 403; no se crea ninguna notificacion en Mongo; el
  catalogo por defecto tiene >= 2 proveedores por canal.
- Arquitectura: `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Dependencias y orden

Contrato anade la ruta al YAML -> wiring registra beans -> implementacion. HU2-099 (aprovisionamiento del PDP) debe re-ejecutarse
tras publicar la ruta. Sin dependencia de E1 ni E2.

## Preguntas abiertas

1. Limitar a una prueba en vuelo por proveedor (429)? Recomendacion: si, con un `AtomicBoolean` por proveedor; evita usar la
   sonda como amplificador contra la API del proveedor.
2. Twilio cobra o limita `GET Accounts`? Recomendacion: no cobra; mantenerlo, y documentar el limite si Twilio lo impone.
3. FCM: la sonda omite el cache del token. Recomendacion: si, de lo contrario una credencial revocada aparece OK hasta que
   caduque el token.
4. Persistir el historial de pruebas? Recomendacion: no en esta historia; el log estructurado basta (YAGNI).
5. `simulated` hoy es `@Component` sin perfil; la restriccion a local/pruebas es transversal. Recomendacion: no tocarla aqui,
   pero la prueba E2E debe correr con el perfil de pruebas.
