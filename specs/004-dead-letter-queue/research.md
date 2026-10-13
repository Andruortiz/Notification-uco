# Phase 0 Research: Cola de mensajes muertos (DLQ) para el despacho

## Decisión 1 — Mecanismo de reintento y dead-lettering

**Decision**: Envolver `NotificationDispatchListener.onMessage` con un
`StatefulRetryOperationsInterceptor` (Spring Retry, vía `RetryInterceptorBuilder.stateful()`)
configurado con `maxAttempts` = `notification.rabbit.dispatch.max-attempts` (default 3), enganchado al
`SimpleRabbitListenerContainerFactory` mediante `setAdviceChain(...)`. Al agotar los intentos, el
interceptor delega en un `RepublishMessageRecoverer` (Spring AMQP) que publica el mensaje agotado al
exchange/routing-key de la cola de mensajes muertos, agregando automáticamente los headers
`x-exception-message`, `x-exception-stacktrace`, `x-original-exchange` y `x-original-routingKey`.

**Rationale**:
- Es el patrón canónico y documentado de Spring AMQP para exactamente este problema (poison message /
  reintento acotado antes de dead-letter) — no requiere reinventar conteo de intentos ni manejo de
  headers de causa a mano.
- El reintento es **stateful** (no stateless) porque cada intento en un `StatefulRetryOperationsInterceptor`
  vuelve a pasar por el ciclo de ack/nack del broker (usa una clave de mensaje para reconocer reintentos
  del mismo mensaje lógico), lo cual es más seguro ante caídas del proceso a mitad de un reintento que
  la variante stateless (que reintenta todo en memoria antes de cualquier ack) — evita perder el conteo
  si el pod muere entre el intento 2 y el 3.
- `RepublishMessageRecoverer` resuelve FR-004 (conservar causa y contexto original) sin código propio:
  los headers que agrega son exactamente lo que un operador necesita para diagnosticar en la
  RabbitMQ Management UI.
- No requiere ningún cambio en `core`: todo el mecanismo es configuración declarativa de beans en
  `infrastructure/config`, coherente con el Principio I.

**Alternatives considered**:
- **Dead-lettering nativo de RabbitMQ** (`x-dead-letter-exchange` en la cola original + TTL por cola de
  reintento escalonada + inspección manual del header `x-death` para contar intentos y decidir
  nack-sin-requeue). Descartado: requiere modelar N colas de reintento con TTLs distintos (una por
  intento) para lograr backoff, es significativamente más topología que mantener, y el conteo vía
  `x-death` es más frágil de testear que una interceptor Java directamente unit-testeable.
- **Contador propio en MongoDB** (persistir intentos de procesamiento por mensaje). Descartado:
  introduce estado compartido y una escritura a Mongo en el camino caliente de cada intento fallido,
  contradice el Edge Case ya resuelto en el spec ("cada mensaje cuenta sus propios intentos de forma
  independiente, sin coordinación entre réplicas") y no aporta nada que el conteo en memoria del
  interceptor no resuelva ya.

## Decisión 2 — Cómo probar el flujo end-to-end (Principio IV)

**Decision**: Añadir un servicio `rabbitmq` (imagen `rabbitmq:3-management`, con healthcheck) al job
`build` de `.github/workflows/ci.yml`, y escribir un `@SpringBootTest(webEnvironment = NONE)` que:
1. Publica un mensaje de despacho cuyo `notificationId` fuerza una excepción determinística en
   `DispatchNotificationUseCase` (mock/stub del puerto, igual que en `NotificationDispatchListenerTest`
   pero con un contenedor Spring real para que el `adviceChain` esté activo).
2. Espera (con `Awaitility` o `RabbitTemplate.receive` con timeout corto) a que el mensaje aparezca en
   `notification.dispatch.dlq.queue`.
3. Verifica los headers `x-exception-message` y `x-original-routingKey` en el mensaje recibido.

**Rationale**:
- El Principio IV exige "al menos una prueba end-to-end que ejercite el flujo completo... contra el
  proveedor simulado o el adaptador real correspondiente" — el adaptador real aquí es RabbitMQ mismo;
  una prueba que solo mockea `RetryOperationsInterceptor` no prueba que el binding, el exchange y el
  `RepublishMessageRecoverer` realmente entreguen el mensaje a la cola correcta.
- `ci.yml` hoy no levanta ningún servicio (`./mvnw verify` corre sin Mongo ni RabbitMQ reales — todas
  las pruebas actuales que tocan infraestructura usan mocks, incluidas `CorsConfigTest`/
  `CorsConfigCustomOriginTest`, que mockean el caso de uso para evitar tocar Mongo). Esta historia es la
  primera que necesita un broker real en CI porque el comportamiento a probar vive en la interacción
  con RabbitMQ, no solo en código Java invocable con un mock.
- GitHub Actions `services:` corre sobre el runner Linux (`ubuntu-latest`) nativamente vía Docker — no
  aplica la limitación de Testcontainers en Windows (bug de detección de pipe con nombre) ya documentada
  para el entorno local de este desarrollador; por eso la prueba puede correr en CI sin depender de
  Testcontainers en absoluto, usando `spring-rabbit` apuntando directo a `localhost:5672`.
- Localmente, el desarrollador ya tiene `docker compose up -d rabbitmq` disponible y probado en esta
  misma sesión — no se necesita infraestructura nueva para ejecutar la prueba en su máquina.

**Alternatives considered**:
- **Solo prueba unitaria del interceptor con mocks** (verificar que `recover()` se invoca tras N
  fallos). Descartado como prueba *única*: cumpliría cobertura de línea pero no es una prueba E2E real
  del Principio IV — no probaría que el mensaje efectivamente llega a la cola de RabbitMQ correcta con
  los headers correctos. Se mantiene como complemento (más rápida, sin broker) pero no sustituye la E2E.
- **Testcontainers para RabbitMQ**. Descartado para este entorno: ya documentado que Testcontainers no
  corre localmente en esta máquina Windows (bug de detección de named pipe) — forzarlo solo para esta
  historia crearía una prueba que pasa en CI pero nunca se puede correr localmente, lo cual dificulta el
  desarrollo iterativo de la prueba misma.

## Decisión 3 — Dónde vive la configuración de `max-attempts`

**Decision**: `@Value("${notification.rabbit.dispatch.max-attempts:3}")` en el nuevo
`RabbitRetryConfig`, siguiendo el mismo patrón que `notification.scheduler.pending-orphan-threshold-ms`
(un valor de comportamiento simple, no una jerarquía de sub-propiedades). La topología nueva (exchange,
routing key, queue de la DLQ) se añade a `RabbitTopologyProperties` como un nuevo record anidado `Dlq`,
igual que el `Dispatch` existente, porque ahí sí hay una jerarquía de 3 campos relacionados.

**Rationale**: Consistente con el precedente ya establecido en el proyecto — valores de comportamiento
sueltos usan `@Value`, agrupaciones estructuradas de nombres de topología usan
`@ConfigurationProperties`. No introduce un tercer patrón.

**Alternatives considered**: Meter `max-attempts` dentro de `RabbitTopologyProperties` también —
descartado porque esa clase modela nombres de topología (strings), no comportamiento numérico; mezclar
ambos degradaría la cohesión del record sin ningún beneficio.

## Notas de implementación (post-`/speckit-implement`)

Tres detalles que no eran predecibles desde el plan y se descubrieron corriendo el código contra un
RabbitMQ real; se documentan aquí porque cambian ligeramente el diseño descrito arriba.

1. **`NotificationRabbitPublisher` necesitó fijar `MessageProperties.messageId`.** El generador de
   clave por defecto de `StatefulRetryOperationsInterceptor` (vía Spring AMQP) usa
   `message.getMessageProperties().getMessageId()` para correlacionar reintentos del mismo mensaje
   lógico entre redeliveries. El publisher original no fijaba ningún `messageId`, así que cada
   redelivery se habría tratado como un mensaje nuevo — el conteo de intentos nunca habría avanzado.
   Se agregó un `MessagePostProcessor` en `enqueueForDispatch` que fija el `messageId` al
   `notificationId` (ya único por diseño). No era un archivo listado en plan.md; se documenta aquí en
   vez de dejarlo como un cambio silencioso.
2. **Con `max-attempts = N`, la recuperación necesita `N + 1` entregas del broker, no `N`.**
   `StatefulRetryOperationsInterceptor` decide si una entrega puede ejecutar el procesamiento
   ANTES de invocarlo, usando el conteo de fallos previos. Esto significa que la lógica de negocio
   (`DispatchNotificationUseCase.dispatch`) se invoca exactamente `N` veces — cumpliendo FR-002 al
   pie de la letra — pero se necesita una redelivery adicional (la `N+1`) para que el interceptor
   reconozca el agotamiento y mueva el mensaje a la DLQ, sin volver a invocar el procesamiento en
   esa última entrega. Verificado empíricamente con `max-attempts=3` (recuperación en la 4ª entrega) y
   `max-attempts=1` (recuperación en la 2ª entrega). No cambia el cumplimiento de FR-002/SC-001 (el
   procesamiento real nunca excede el límite configurado), pero un operador que cuente redeliveries en
   la Management UI verá una más de las esperadas — anotado también en quickstart.md.
3. **`RabbitConfig` necesitó `@Qualifier` explícito.** Al agregar un segundo bean `Queue` y un segundo
   bean `DirectExchange` (para la DLQ), Spring dejó de poder resolver por tipo los parámetros de
   `notificationDispatchBinding` — antes había un solo candidato de cada tipo y el emparejamiento por
   nombre de parámetro no hacía falta. Se agregó `@Qualifier(nombreDelBean)` a los cuatro parámetros de
   tipo `Queue`/`DirectExchange` en los dos métodos `@Bean` de tipo `Binding`, en vez de depender de
   una resolución implícita frágil.
