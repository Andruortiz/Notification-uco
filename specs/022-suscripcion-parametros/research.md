# Research: Suscripción por evento al Componente de Parámetros (HU2-022)

**Feature**: 022-suscripcion-parametros | **Date**: 2026-10-08

## Estado verificado (rama basada en `develop`, tras la fusión de 019 y 020)

| Hecho | Dónde | Consecuencia |
|---|---|---|
| El único adaptador de entrada de Parámetros es `ParametersPollingScheduler` (`@Scheduled`, `notification.parameters.poll-interval-ms`, 30 000) | `adapter/in/scheduler` | El evento es un segundo adaptador de entrada; el sondeo se conserva |
| `SynchronizeConfigurationService.synchronize()` hace `source.fetchState()` -> `apply(change)` -> si `APPLIED` o `PENDING_RESTART`, `lastKnownPort.saveIfNewer(view.snapshot())`, con fallo de persistencia tragado y registrado | `core/usecase` | La rama "aplicar y persistir" no es invocable sin pasar por la fuente: hay que extraerla a un caso de uso de entrada (D1) |
| `ConfigurationChange(version, values)`; `HttpParametersSource.StateResponse(version, values)` ya valida que no falten ambos campos | `core/domain/configuration`, `adapter/out/parameters` | El mensaje del evento reutiliza la misma forma (D2) |
| `ConfigurationEventLogger.observe/logOutcome/logFailure` ya registra `CONFIG_APPLIED`, `CONFIG_REJECTED`, `CONFIG_IGNORED` (este último una vez por versión y motivo) y `newCorrelationId()` con prefijo `param-` | `adapter/in/scheduler` | Se reutiliza tal cual; solo se añade el campo `transport` (D8) |
| `AttachmentScanListener` + `ManualAckSettler` + `AttachmentScanRabbitConfig` son el patrón: cola durable con `x-dead-letter-*`, DLQ propia, `RepublishMessageRecoverer`, fábrica de contenedor `MANUAL`, `prefetch 1`; `ManualAckSettler.deadLetter` republica con confirmación y hace `nack` sin requeue si falla | `adapter/in/rabbit`, `config` | Se imita la estructura; `ManualAckSettler` es reutilizable (paquete-privado, mismo paquete) |
| `NotificationDispatchListener` define el patrón de correlación en cabecera y el prefijo heredado | `adapter/in/rabbit` | Para el evento se usa siempre `param-` (D8) |
| Las credenciales de RabbitMQ salen de `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` sin defecto; el host y puerto tienen defectos de desarrollo ya existentes | `application.yml` | No se añade ningún valor de broker; se usa la `ConnectionFactory` existente (D4) |
| La spec 019 rechazó la publicación por evento "por ahora" y dejó la excepción del Principio VII de SUP-02, vence 2026-11-15, dueño andrualv | `specs/019.../research.md` D7, `plan.md` | Esta historia construye esa alternativa contra un contrato supuesto y no cierra la excepción: la mueve a ajustar el contrato (D9) |
| `@ConditionalOnProperty` considera presente una propiedad con valor vacío | Spring Boot | La activación usa `@ConditionalOnExpression` sobre texto no vacío (D3) |

## Decisiones

### D1. Caso de uso nuevo en `core` para recibir un cambio ya obtenido

- **Decisión**: puerto de entrada `ReceivePublishedConfigurationUseCase.receive(ConfigurationChange)` que
  aplica el cambio y persiste la última conocida (la lógica hoy privada `persistIfAdopted`).
  `SynchronizeConfigurationService` pasa a delegar en él tras `fetchState()`, de modo que sondeo y evento
  comparten una sola ruta. Sin Spring ni conocimiento del transporte (Principio I).
- **Alternativas**: llamar a `ApplyConfigurationChangeUseCase` desde el listener (rechazada: omite la
  persistencia de la última conocida y la duplicaría en infraestructura); que el evento solo dispare
  `synchronize()` (ver D2).

### D2. Q1: el evento trae los valores y se aplican directamente

- **Recomendación**: aplicar directamente el `{version, values}` del mensaje.
- **Razonamiento**: (1) funciona sin fuente HTTP (FR-025 de la spec 019: `NoParametersSource` es el caso por
  defecto), donde un "aviso" no tendría a quién consultar; (2) latencia de segundos sin una consulta extra y
  sin depender de que Parámetros esté arriba cuando publica; (3) el mensaje pasa por el mismo validador y el
  mismo control de versión, así que un valor malo se rechaza igual; (4) menor superficie de fallo.
- **Contra**: confía el contenido a quien pueda publicar en el exchange (ver D7, Q5). Con "solo avisar", el
  contenido llegaría siempre por el canal HTTP del contrato de la 019, lo que da una barrera de autenticación
  "gratis" y orden total (la fuente devuelve siempre el último estado).
- **Alternativa viable**: disparar `synchronize()` al recibir el aviso, sin leer el cuerpo salvo para
  trazar. Se conserva como variante si el usuario prefiere seguridad sobre latencia o si el equipo de
  Parámetros solo publica avisos. Cambia únicamente el cuerpo del listener (una línea), no el resto.

### D3. Activación y configuración

- **Decisión**: propiedades `notification.parameters.events.{exchange, routing-key, queue, dlq-exchange,
  dlq-routing-key, dlq-queue, exchange-type, max-attempts, consumer-concurrency}` en un record
  `ParametersEventsProperties`. `exchange` vacío = inactivo. Sin valores por defecto literales en el
  repositorio para exchange, routing key ni cola (`${NOTIFICATION_PARAMETERS_EVENTS_EXCHANGE:}` etc.).
  Si `exchange` no está vacío, `routing-key` y `queue` son obligatorios (falla el arranque identificando la
  propiedad). Los nombres de la DLQ se derivan de `queue` si no se indican. `exchange-type` toma por defecto
  `topic`; `max-attempts` 3; `consumer-concurrency` 1.
- Los beans de topología, el listener y la fábrica del contenedor se condicionan con
  `@ConditionalOnExpression("!'${notification.parameters.events.exchange:}'.trim().isEmpty()")`.
- **Alternativas**: `@ConditionalOnProperty` (rechazada: el valor vacío cuenta como presente).

### D4. Q2: mismo broker, cola propia

- **Recomendación**: el mismo RabbitMQ del servicio, con su `ConnectionFactory`, cola y DLQ propias.
- **Razonamiento**: el servicio ya tiene conexión, credenciales inyectadas, sonda de salud y apagado
  ordenado sobre ese broker; un segundo `ConnectionFactory` duplica secretos, sondas y el ciclo de apagado y
  exige variables nuevas sin que exista aún el contrato. La mayoría de los despliegues comparten el broker de
  la plataforma y el aislamiento se hace por vhost/usuario.
- **Contra**: si Parámetros usa otro broker, hará falta un segundo `ConnectionFactory` y un contenedor con
  esa fábrica; es un cambio contenido en `ParametersEventsRabbitConfig` y en las propiedades.
- **Riesgo**: el servicio declara el exchange; si Parámetros lo declara con otro tipo o argumentos, la
  declaración falla (`PRECONDITION_FAILED`). Mitigación: el tipo es configurable (`exchange-type`) y la falla se
  diagnostica en el arranque.

### D5. Topología y consumo (patrón de `AttachmentScanRabbitConfig`)

- **Decisión**: exchange configurable (`topic` por defecto, durable); cola durable con
  `x-dead-letter-exchange` y `x-dead-letter-routing-key`; DLQ con su exchange directo y binding; fábrica de
  contenedor `MANUAL`, `prefetch 1`, concurrencia 1 por defecto (un solo consumidor por réplica conserva el
  orden de llegada; `ApplyConfigurationChangeService` ya serializa); `RepublishMessageRecoverer` hacia la DLQ.
  `ManualAckSettler` se reutiliza con una cabecera de intento propia (`x-parameters-event-attempt`).

### D6. Q3: manejo de mensaje inválido u obsoleto

| Caso | Destino | Razón |
|---|---|---|
| No es JSON, falta `version` o `values`, tipos erróneos | DLQ (`settler.deadLetter`), error `PARAMETERS_EVENT_UNREADABLE`, ack tras republicar | Determinista: reintentar no lo arregla; la DLQ conserva el mensaje para diagnóstico y reproceso manual |
| Bien formado, rechazado por validación (`REJECTED`) | `ack`, evento `CONFIG_REJECTED` con motivo y claves, sin valores, sin DLQ | Determinista; ya hay un registro estructurado. Mandarlo a DLQ duplicaría la traza y generaría ruido operativo por un error de contenido de Parámetros |
| `IGNORED_STALE` (versión menor o igual) | `ack`, `CONFIG_IGNORED` | Es el caso normal de la entrega at-least-once, de reordenamientos y de la convivencia con el sondeo; no es un error |
| Fallo inesperado (excepción no prevista) | `settler.handleFailure`: republica con intento+1 y, al llegar a `max-attempts`, DLQ | Mismo patrón que el escaneo |
| Fallo al persistir la última conocida | Ya tragado por `receive` (el cambio queda adoptado); `ack` | Igual que el sondeo |

- **Recomendación**: la anterior. **Alternativa**: mandar también los `REJECTED` a la DLQ para tener un lugar
  único donde revisar cambios malos; se descarta por ahora y se revisa al cerrar el contrato.

### D7. Q5: autenticación del emisor

- **Recomendación**: no verificar firma en esta entrega; apoyarse en el control de acceso del broker y registrar
  la limitación como excepción con dueño y fecha.
- **Razonamiento**: cualquier firma o token en el mensaje (JWS, HMAC, cabecera de identidad) es parte del
  contrato que define el otro equipo; inventarlo ahora añadiría un secreto compartido y un formato que
  habría que deshacer. Lo que sí se hace: validar el cambio completo con el mismo validador (un emisor no
  autorizado solo puede publicar valores válidos dentro de los rangos) y exigir que el usuario del servicio
  solo pueda consumir de su cola mientras el exchange tiene permisos de escritura restringidos a Parámetros
  (requisito de despliegue, documentado en quickstart.md).
- **Riesgo residual**: quien pueda publicar en el exchange puede cambiar timeouts e intentos dentro de los
  rangos. Mitigado por rango, por la serialización de versión y por el sondeo (la versión que publique la
  fuente HTTP autenticada, si es mayor, prevalece). Si el usuario exige firma desde el inicio, la variante D2
  "solo avisar" la resuelve sin definir ningún formato de firma.

### D8. Observabilidad

- `ConfigurationEventLogger` se reutiliza; se añade un parámetro `transport` (`poll` o `event`) a los eventos
  `CONFIG_APPLIED`, `CONFIG_REJECTED` y `CONFIG_IGNORED` (FR-017). El adaptador del evento crea el contexto de
  correlación con `ConfigurationEventLogger.newCorrelationId()` (prefijo `param-`), ignora cualquier
  correlación de cabecera de un emisor no confiable y no registra el cuerpo del mensaje. `PARAMETERS_UNAVAILABLE`
  y `PARAMETERS_RECOVERED` siguen siendo del sondeo (hablan de disponibilidad de la fuente HTTP).
- Código de error nuevo `PARAMETERS_EVENT_UNREADABLE` (categoría permanente de negocio) en `ErrorCode`.
- Métrica: sin métrica nueva en esta historia; los contadores de mensajes por resultado se anotan como
  mejora posible, no como deuda oculta (Principio VII: es alcance no pedido, no un atajo).

### D9. Q4: sondeo y excepción de Principio VII

- **Recomendación (sondeo)**: mismo intervalo (30 s por defecto), sin lógica automática de "intervalo más
  largo si hay evento". **Razonamiento**: el sondeo es la garantía de convergencia (SC-002); alargarlo cuando el
  evento está activo mezcla la salud de dos canales (si la suscripción se cae en silencio, el intervalo largo
  sería precisamente lo que la deja desactualizada) y añade estado a un scheduler que hoy no lo tiene. El
  operador puede subir `NOTIFICATION_PARAMETERS_POLL_INTERVAL_MS` si quiere menos tráfico; es una decisión
  explícita y no oculta.
- **Excepción (Principio VII)**: el contrato del evento es supuesto. Dueño: equipo de desarrollo del
  componente (Notification-uco). Fecha: 2026-11-15, la misma de SUP-02. Qué se ajusta al cerrarse: nombre y
  tipo de exchange, routing key, nombre de cola y política por réplica (difusión o cola compartida), forma del
  mensaje (`ParametersEventPayload`), si el evento trae valores o solo avisa (D2), autenticación del emisor
  (D7), broker (D4) y destino de los rechazados (D6). Mientras tanto la suscripción está inactiva por defecto
  y el servicio no depende de ella.

### D10. Pruebas de las promesas medibles

| Promesa | Prueba |
|---|---|
| SC-001 adopción por evento <= 5 s | `ParametersEventSubscriptionE2ETest`: Mongo y RabbitMQ reales, `poll-interval-ms` = 3 600 000, publica y espera `GET /configuration`; afirma `Duration` <= 5 s |
| SC-002 evento perdido, converge por sondeo | mismo E2E: cambio solo en la fuente HTTP falsa; `Duration` < intervalo + margen; control positivo del canal |
| SC-003 inválidos a DLQ y sin bloquear | E2E: no JSON, sin `version`, luego válido; DLQ con 2 mensajes, cola principal vacía, versión nueva adoptada |
| SC-004 obsoleto/duplicado | E2E (misma versión dos veces y versión menor) y unitaria de `ParametersEventListener` |
| SC-005 inactivo sin exchange | `ParametersEventsInactiveTest` (contexto sin exchange: ausencia de beans y de cola, arranque <= 30 s) |
| SC-006 sin secretos ni valores rechazados | E2E con `ListAppender` sobre el log del rechazo |
| Forma del mensaje igual a la HTTP | `ParametersEventPayloadContractTest`: el mismo JSON se lee con `ParametersEventPayload` y con `HttpParametersSource.StateResponse` |
| Ack manual / ningún mensaje queda sin ack | E2E: tras cada caso, `messageCount` de la cola principal es 0 y `consumerCount` 1 |
| Sin aislamiento por tenant | No aplica: configuración global por réplica (spec 019) |

El mensaje del E2E se produce con `RabbitTemplate` y el mismo `ObjectMapper` del servicio serializando el
tipo `ParametersEventPayload` (el productor real es Parámetros, que no está en este repositorio): la
trampa de HU2-072 se cubre con `ParametersEventPayloadContractTest`, que fija el JSON literal del contrato
supuesto (`{"version":12,"values":{...}}`) y exige que ambas lecturas lo acepten.
Los controles positivos: el "no se adoptó" del sondeo largo solo vale porque el mensaje válido sí se adopta
por el mismo canal en la misma prueba.

## Riesgos

- **R-1**: contrato supuesto equivocado (exchange, tipo, cola, forma). Mitigación: aislamiento en un
  adaptador + propiedades; excepción con fecha.
- **R-2**: la declaración del exchange choca con la de Parámetros (D4).
- **R-3**: confianza en el emisor sin firma (D7).
- **R-4**: `SynchronizeConfigurationService` cambia de constructor: sus pruebas y `UseCaseConfig` se ajustan.
- **R-5**: pruebas con RabbitMQ en el mismo contexto que `DeadLetterQueueE2ETest` pueden compartir contextos
  de Spring en caché; el E2E usa su propio contexto y contenedores.
