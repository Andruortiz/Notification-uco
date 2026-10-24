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
  inesperado -> `PROVIDER_ERROR`. `latencyMs` = `Clock` antes/despues. `Clock` y `Duration` limite entran por constructor;
  la `Duration` viene de `notification.provider.probe.timeout` y se pasa al construir el servicio.
- Limite de una prueba en vuelo por proveedor: `AtomicBoolean` por `ProviderId` en el servicio; la segunda recibe un error de
  dominio que el controller mapea a 429. Es por replica.
- Identidad (FR-007): el caso de uso no la recibe; el controller la toma de la sesion y escribe el log estructurado con
  `correlationId`, tenant e identidad junto al resultado.
- Registro de sondas analogo a `NotificationSenderRegistry` (`ProviderProbeRegistry`).

**infrastructure** (adaptadores, una sonda cada uno, reutilizan el `WebClient` y la configuracion del proveedor)
- Brevo: `GET /v3/account`. Twilio: `GET /2010-04-01/Accounts/{accountSid}.json`. FCM: metodo nuevo
  `FcmAccessTokenProvider.probe()` que llama a `exchange()` directamente, sin el cache (`invalidate(String)` exige el token
  vigente, que no se puede leer, y no sirve); `probe()` no reemplaza el token cacheado de los envios. `simulated`: lee
  `SIMULATED_PROVIDER_RESULT`, sin red.
- Clasificacion: 2xx -> OK; 401/403 -> `AUTH_REJECTED`; error de conexion -> `UNREACHABLE`; otro status -> `PROVIDER_ERROR`.
  Nunca se propaga el cuerpo ni el mensaje de la excepcion a respuesta o log.
- `RouteAuthorizationPolicy`: regla `POST /provider-connection-tests` -> ADMINISTRADOR. Con PEP activo `minimumRoleFor`
  devuelve CLIENTE para toda ruta y decide el PDP, por eso el recurso debe registrarse tambien en el PDP (ver Endpoints).
- Controller `ProviderConnectionTestController` y DTOs `ProviderConnectionTestRequest`/`Response`; el rol se exige igual que
  los demas endpoints de ADMINISTRADOR. Propiedad `notification.provider.probe.timeout` (defecto `5s`).
- Cableado de beans (`UseCaseConfig`) lo hace quien posee el wiring: se declara en tasks como dependencia, no se edita aqui.

## Endpoints nuevos (para Contrato; rutas literales)

| Metodo | Ruta | Rol minimo | operationId sugerido | Estado |
|---|---|---|---|---|
| POST | `/provider-connection-tests` | ADMINISTRADOR | `testProviderConnection` | available |

Respuestas: 200 resultado (tambien cuando la prueba falla), 400 `VALIDATION_ERROR`, 401, 403, 429 (ya hay una prueba en
vuelo para ese proveedor; el limite es por replica), 503 `PepUnavailable`. Cabecera `X-Correlation-Id` como el resto.
Debe entrar en `protected-resources.json` y quedar vinculado a `PERFIL_ADMINISTRADOR` al re-ejecutar el script de
aprovisionamiento del PDP (HU2-099); sin eso, con PEP activo, ADMINISTRADOR no tendria el recurso.

## Contrato para la UI

Peticion `POST /provider-connection-tests`: `{ "channel": "EMAIL"|"SMS"|"PUSH", "providerId": "brevo" }`. Los valores salen de
`GET /channels` y `GET /providers`; la UI habilita "Probar" solo para ADMINISTRADOR y por cada par canal/proveedor listado.

Respuesta 200:
`{ "providerId": "brevo", "channel": "EMAIL", "status": "OK"|"FAILED", "latencyMs": 142, "errorCode": null|"AUTH_REJECTED"|... }`

La UI muestra `latencyMs` siempre y traduce `errorCode` a un texto propio; no existe ningun campo con credenciales ni detalle
crudo del proveedor. 403 -> ocultar el boton; 429 -> "prueba en curso" (el limite es por replica, asi que la UI no debe asumir exclusion global).

## Pruebas

- Unitarias `core` (JUnit + Mockito + `StepVerifier`): validacion canal/proveedor, `CREDENTIALS_MISSING`, `ADAPTER_MISSING`,
  `TIMEOUT` con `VirtualTimeScheduler`, latencia con `Clock` fijo.
- Adaptadores (doble HTTP, patron de los tests de Brevo/Twilio/FCM existentes): 2xx, 401, 500, conexion rechazada y respuesta
  tardia; el doble devuelve la credencial sentinela en el cuerpo de error y la prueba afirma que no aparece en resultado ni logs.
- Seguridad: unitaria de `RouteAuthorizationPolicy` (ADMINISTRADOR minimo) y, ademas, prueba con el modo de autorizacion
  realmente activo (PEP con doble del PDP, como en las pruebas de HU2-103): CLIENTE y OPERADOR reciben 403 y la sonda no se
  invoca; ADMINISTRADOR recibe 200 (control positivo).
- Concurrencia: dos pruebas simultaneas del mismo proveedor -> una 200 y otra 429; tras terminar, una nueva vuelve a dar 200.
- Metrica `notification.provider.probe`: prueba que se incrementa con tags `providerId` y `status` y que ninguna etiqueta
  contiene la credencial sentinela.
- Adaptador FCM: con credencial revocada en el doble, `probe()` falla aunque haya un token cacheado vigente.
- Tenant: dos tenants distintos prueban el mismo proveedor; el resultado es identico y el log de cada uno solo lleva su tenant.
- **T-E2E** `ProviderConnectionTestE2ETest` (`@SpringBootTest` RANDOM_PORT + Testcontainers Mongo y RabbitMQ + `WebTestClient`,
  patron `NotificationLiveUpdatesE2ETest`): ADMINISTRADOR prueba `simulated` en los tres canales con `ACCEPTED` y con fallo,
  asercion `Duration` < 1 s; prueba un real contra doble HTTP; CLIENTE y OPERADOR -> 403 con el modo de autorizacion activo; no se crea ninguna notificacion en Mongo; con el perfil de
  pruebas hay >= 2 proveedores por canal.
- Arquitectura: `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Dependencias y orden

Contrato anade la ruta al YAML -> wiring registra beans -> implementacion. HU2-099 (aprovisionamiento del PDP) debe re-ejecutarse
tras publicar la ruta. Sin dependencia de E1 ni E2. Dependencia D-001 (E3: `simulated.enabled` falso por defecto y catalogo con
el proveedor real primero), dueno Equipo de backend, fecha 2026-12-15: esta historia no toca `simulated` y su E2E corre con el
perfil de pruebas, donde esta habilitado. Roles: tres en el codigo hasta B2; solo ADMINISTRADOR.

## Preguntas abiertas

1. Twilio cobra o limita `GET Accounts`? Recomendacion: no cobra; mantenerlo, y documentar el limite si Twilio lo impone.
2. Persistir el historial de pruebas? Recomendacion: no en esta historia; el log estructurado basta.
3. Limite de una prueba en vuelo global (entre replicas)? Mejora futura; hoy es por replica.
