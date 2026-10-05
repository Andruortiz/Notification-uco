# Research: Sincronizar configuración transversal de Parámetros (HU2-073)

**Feature**: 019-sincronizar-parametros | **Date**: 2026-10-05

Estado verificado del código (rama basada en `develop` tras la fusión de 017-E1) y decisiones de diseño.

## Estado verificado

| Hecho | Dónde | Consecuencia |
|---|---|---|
| Todos los valores operativos se leen una sola vez al arrancar | `application.yml`; `@ConfigurationProperties` de Brevo, Twilio y FCM; `@Value` en `RabbitRetryConfig`; `@Scheduled(fixedDelayString=...)` | Hay que cambiar el punto de lectura de cada uno (D4, D5, D6) |
| Hay dos conceptos de "intentos máximos" | `RetryPolicy` (dominio, 5, ciclo `RECOVERABLE`) y `notification.rabbit.dispatch.max-attempts` (3, reintento de mensaje) | La spec habla del segundo (D3) |
| El interceptor de reintento del listener se construye una vez con un entero fijo | `RabbitRetryConfig.notificationDispatchRetryInterceptor` | Imposible adoptar en caliente sin la entrega E4 (ack manual con `x-dispatch-attempt`); ver Dependencia |
| El `WebClient` de cada proveedor se construye con `CONNECT_TIMEOUT_MILLIS` y `responseTimeout` fijos | `BrevoWebClientConfig`, `TwilioWebClientConfig`, `FcmProviderConfig` | El tiempo de conexión es una opción del canal Netty; no se puede cambiar por petición (D5) |
| El reencolador usa `fixedDelayString` evaluado al arrancar | `PendingNotificationSchedulerAdapter` | Hay que sustituirlo por un disparador dinámico (D6) |
| El catálogo se refresca con un `@Scheduled` y un caché local que se reemplaza completo | `ChannelCatalogRefresher`, `ChannelCatalogCache` | Patrón a imitar para el sondeo y el reemplazo atómico |
| Toda ruta sin regla exige `ADMINISTRADOR` | `RouteAuthorizationPolicy.minimumRoleFor` | Aun así se añade la regla explícita (legibilidad y prueba) |
| El contrato ya trae `/channels:register` y `/providers:register` como "bloqueados" | `api-notificaciones.yaml` | Precedente de operaciones identificadas como planificadas (Principio II) |
| No existe ningún límite de proveedor declarado en código | búsqueda en `core` e `infrastructure` | La regla (d) necesita una fuente (D8, decisión Q3) |

## Decisiones

### D1. Núcleo: descriptores, instantánea y validador en Java puro

- **Decisión**: en `core`, `domain/configuration/`: `ParameterDescriptor` (clave, tipo, valor por defecto,
  rango, ámbito, modo de adopción), `ParameterRegistry`, `ConfigurationSnapshot` (inmutable, versión, origen,
  valores, fecha de adopción), `ConfigurationChange` (versión + mapa clave-valor), `FixedConfiguration`
  (valores de arranque que participan en las reglas pero no son gestionables) y `ConfigurationValidator` con
  una `CrossParameterRule` por cada regla (a)-(d). Un `ConfigurationHolder` (`AtomicReference` de la
  instantánea) implementa la vista de lectura `ConfigurationView`. Sin Spring.
- **Alternativas**: usar `@RefreshScope` / Spring Cloud Config (rechazada: acopla el núcleo a un framework y
  al transporte, viola Principio I y FR-024); un `Map` mutable compartido (rechazada: permite ver mezclas de
  versiones, viola FR-005).

### D2. Puertos y casos de uso

- **Decisión**:
  - Entrada: `ApplyConfigurationChangeUseCase` (recibe `ConfigurationChange`, devuelve un
    `ConfigurationChangeOutcome`: `APPLIED`, `PENDING_RESTART`, `IGNORED_STALE`, `REJECTED` con motivo),
    `QueryConfigurationUseCase` (descriptores + versión + origen), `SynchronizeConfigurationUseCase`
    (consulta la fuente y aplica) y `RestoreLastKnownConfigurationUseCase` (arranque).
  - Salida: `ParametersSourcePort` (`fetchState(): Mono<ConfigurationChange>`, falla con una excepción de
    indisponibilidad) y `LastKnownConfigurationPort` (`load()`, `saveIfNewer(snapshot)`).
- **Alternativas**: un único puerto con ambas direcciones (rechazada: FR-024 pide uno de entrada y uno de
  salida, y el transporte por evento futuro reutilizará el de entrada sin tocar la fuente).

### D3. "Intentos máximos de despacho" es el tope de reintento de mensaje

- **Decisión**: la clave `dispatch.max-attempts` gobierna `notification.rabbit.dispatch.max-attempts` (el tope
  por mensaje que E4 aplica con `x-dispatch-attempt`), que es lo que la spec describe ("se leen en cada
  mensaje"). El `RetryPolicy` del dominio (5 recuperables, con backoff) sigue siendo configuración de arranque
  y se anota como candidato a un descriptor futuro.
- **Alternativas**: gobernar también `RetryPolicy` (rechazada: duplica el significado de la clave y cambia el
  comportamiento del ciclo recuperable, fuera del alcance de las historias 1-3).
- **Decisión del usuario (Q2)**: lectura confirmada; `dispatch.max-attempts` es el tope por mensaje, no el `RetryPolicy`.

### D4. Lectura por operación: una instantánea por operación

- **Decisión**: cada operación (un mensaje del listener, una llamada al proveedor, un ciclo del reencolador)
  llama una vez a `ConfigurationView.snapshot()` al iniciar y usa esa referencia hasta terminar. No hay
  getters globales que lean la instantánea vigente a mitad de la operación. Cumple FR-011 y la regla de
  "mismo valor varias veces" de los casos límite.
- **Alternativas**: leer el valor en cada uso (rechazada: dos lecturas pueden ver versiones distintas).

### D5. Tiempos de espera y conexión de proveedor: un `WebClient` por versión

- **Decisión**: un componente de infraestructura `ProviderHttpClients` entrega el `WebClient` de cada
  proveedor para la instantánea recibida. Construye un cliente por (proveedor, tiempo de espera, tiempo de
  conexión) y lo conserva en un mapa; un cambio de valores produce un cliente nuevo para las llamadas
  siguientes y las llamadas en vuelo terminan con el anterior. Los tres adaptadores (`Brevo`, `Twilio`,
  `Fcm`) dejan de recibir un `WebClient` fijo.
- **Alternativas**: `responseTimeout` por petición (rechazada como único mecanismo: el tiempo de conexión no
  admite cambio por petición); declarar el tiempo de conexión como "requiere reinicio" (rechazada: FR-003 y
  FR-014 lo incluyen entre los parámetros que se leen en cada uso); un `Mono.timeout` externo (rechazada:
  cambia la semántica de error respecto de los clasificadores existentes).

### D6. Intervalo del reencolador: disparador dinámico

- **Decisión**: `PendingNotificationSchedulerAdapter` pasa a registrarse con un `SchedulingConfigurer` y un
  `Trigger` que, al terminar cada ciclo, calcula el siguiente instante con el intervalo de la instantánea
  vigente. El sondeo de Parámetros (D7) usa su intervalo de arranque, no gestionable.
- **Alternativas**: reiniciar el `ScheduledTask` al cambiar (rechazada: requiere un oyente del cambio y
  carreras con un ciclo en curso).

### D7. Sondeo como adaptador por defecto detrás del puerto de salida

- **Decisión**: un `ParametersPollingScheduler` (adaptador de entrada) invoca
  `SynchronizeConfigurationUseCase` cada `notification.parameters.poll-interval-ms` (30 000 ms por defecto,
  igual que el refresco del catálogo; SC-002 queda como "menos de un intervalo de sondeo completo", es decir
  30 s por defecto). Sin `notification.parameters.base-url` configurada, el bean de `ParametersSourcePort` es
  un `NoParametersSource` que no hace nada y el servicio opera con los valores por defecto (FR-025). El
  contexto de correlación del ciclo lleva el prefijo `param-` (igual que `sched-`).
- **Adaptador HTTP**: la spec no trae el contrato de SUP-02. Se propone `HttpParametersSource` contra un
  contrato **provisional** (contracts/parametros-fuente-provisional.md), inactivo por defecto, marcado como
  Supuesto S-1.
- **Decisión del usuario (Q1)**: el adaptador HTTP se incluye, inactivo por defecto. El contrato definitivo de
  SUP-02 sigue abierto (Principio VII, vence 2026-11-15, dueño andrualv). El adaptador se aísla tras
  `ParametersSourcePort` y la ruta, la autenticación y la forma de la versión viven solo en `HttpParametersSource`
  y `ParametersProperties`, de modo que el contrato definitivo se adopta sin tocar el núcleo.
- **Alternativas**: publicación por evento de RabbitMQ (rechazada por ahora: SUP-02 no la fija y exigiría
  topología nueva); archivo de configuración recargable (rechazada: no es el componente de Parámetros).

### D8. Reglas entre parámetros sobre la configuración efectiva completa

- **Decisión**: `ConfigurationValidator.validate(snapshotCandidate, FixedConfiguration)` evalúa el rango,
  tipo, clave y ámbito de cada elemento y luego las reglas (a)-(d) sobre la unión de los valores gestionables y
  `FixedConfiguration`. `FixedConfiguration` la construye infraestructura desde las propiedades de arranque:
  proveedores habilitados por canal (los adaptadores con `disabledReason` vacío), espera y número de intentos
  del análisis de adjuntos, ventana de barrido, esquema de contenido de cada canal y los límites que acepta
  cada proveedor. Esos últimos no existen hoy en código: se propone una tabla `ProviderContentLimits` en
  infraestructura, con valores de la documentación de cada proveedor, marcada como Supuesto S-2.
- **Consecuencia sobre la spec (resuelta de forma conservadora)**: con solo los cuatro grupos iniciales
  gestionables, las reglas (b), (c) y (d) no pueden violarse con un cambio publicado (sus entradas son valores
  de arranque); solo pueden fallar en el arranque (FR-019). Los escenarios 3, 4 y 5 de la historia 3 se
  verifican con pruebas unitarias del validador sobre instantáneas sintéticas y con una prueba de arranque
  fallido, no con una publicación E2E. La regla (a) sí es alcanzable por publicación y se prueba E2E.
- **Decisión del usuario (Q3)**: se implementa `ProviderContentLimits` con los valores de la documentación
  pública de Brevo, Twilio y FCM. Los valores de la tabla deben confirmarse antes de producción (pendiente en
  plan.md, dueño andrualv). Fuentes: este plan no registró valores numéricos ni citas de la documentación de
  cada proveedor, y no se inventan aquí; al implementar, cada valor de la tabla lleva su fuente (URL de la
  documentación y fecha de consulta) en este documento, y los valores sin fuente verificable no se incluyen.
- **Decisión del usuario (Q5)**: las reglas (b), (c) y (d) se prueban con pruebas unitarias sobre instantáneas
  sintéticas y una prueba de arranque fallido; la regla (a) con E2E. Aceptado.
- **Alternativas**: volver gestionables la ventana de barrido y los proveedores habilitados (rechazada: la
  spec los declara fuera del registro inicial, FR-003).

### D9. Persistencia de la última configuración conocida

- **Decisión**: colección `configuration_last_known`, un documento con `_id = "current"`, campos `version`,
  `values`, `adoptedAt`, `source`, `schemaHash` (huella del registro de descriptores). `saveIfNewer` es un
  `findAndModify` con upsert condicionado a `version < nueva`, de modo que una réplica atrasada nunca pisa a
  otra (restricción de la constitución sobre actualizaciones condicionadas atómicas). Al arrancar: se carga
  con un límite de 5 s; documento ausente, corrupto, con `schemaHash` distinto o que no supere el validador,
  se descarta y se registra (FR-018); almacén no disponible, se registra y se usan los valores por defecto. El
  límite de 5 s mantiene el arranque dentro de los 30 s (SC-007).
- **Alternativas**: archivo local (rechazada: se pierde al reescalar réplicas); una colección con historial
  completo (rechazada: fuera de alcance; el historial son los eventos de log).

### D10. Orden de procesamiento y versión

- **Decisión**: `ApplyConfigurationChangeUseCase` serializa con un `Sinks`/cola de un solo consumidor (o
  `concatMap`) dentro de la réplica: cada cambio parte de la versión vigente en ese momento. Versión menor o
  igual a la vigente: `IGNORED_STALE` con evento. El reemplazo de la instantánea es `AtomicReference.set` de
  un objeto inmutable.
- **Alternativas**: bloqueo explícito (rechazada: bloqueante en un flujo reactivo).

### D11. Endpoint de solo lectura

- **Decisión**: `GET /configuration` (REST estándar, es una lectura, no una acción de negocio) con rol mínimo
  `ADMINISTRADOR`. Devuelve `version`, `source`, `adoptedAt`, `pendingRestart` (claves aceptadas sin efecto) y
  `parameters[]` con los descriptores y el valor vigente de cada uno. Se define primero en
  `api-notificaciones.yaml` (contracts/api-notificaciones-cambios.md). Es el único canal para consultar
  versión y origen (FR-020); además se registran en el log y en la métrica `notification.configuration.version`
  (gauge de Micrometer, etiqueta `source`).
- **Alternativas**: `GET /parameters` (rechazada: se confunde con el catálogo de proveedores); `/actuator`
  (rechazada: la spec pide el rol `ADMINISTRADOR` y el contrato OpenAPI).

### D12. Observabilidad sin secretos

- **Decisión**: un `ConfigurationEventLogger` registra, en el formato estructurado existente, los eventos
  `CONFIG_APPLIED` (versión anterior, nueva, claves), `CONFIG_REJECTED` (motivo y claves, sin valores),
  `CONFIG_IGNORED` (versión), `PARAMETERS_UNAVAILABLE` y `PARAMETERS_RECOVERED` (solo en la transición, para no
  inundar el log con cada ciclo fallido). Todos con el identificador de correlación del ciclo. El registro de
  descriptores no contiene ningún secreto por construcción: solo se declaran las cuatro claves iniciales.

### D13. Pruebas de las promesas medibles

| Promesa | Prueba |
|---|---|
| SC-001 arranque sin Parámetros, 100 % de los arranques de prueba | E2E con fuente que siempre falla, arranque dos veces: sin configuración previa y con una persistida |
| SC-002 cambio rige en menos de un intervalo | E2E: intervalo de sondeo de 1 s en la prueba; afirma `Duration` < intervalo + margen y que el listener usa el nuevo tope |
| SC-003 100 % de inválidos rechazados | Unitaria parametrizada (rango, tipo, clave, ámbito, reglas a-d) + E2E de la regla (a) y de clave desconocida |
| SC-004 operaciones en curso no cambian | Unitaria concurrente (instantánea capturada) + E2E con un proveedor falso lento y un cambio durante la llamada |
| SC-005 operador identifica versión y último cambio | E2E de `GET /configuration` y del log del evento |
| SC-006 registro exacto y sin secretos | Unitaria de `ParameterRegistry` y E2E del endpoint con búsqueda de los valores de credenciales de prueba |
| SC-007 arranque ≤ 30 s | E2E con almacén lento: afirma `Duration` ≤ 30 s |
| Rol mínimo | E2E: `ADMINISTRADOR` 200, `OPERADOR` y `CLIENTE` 403, sin token 401 |

No hay aislamiento por tenant en esta historia: la configuración es global por réplica (spec, Supuestos).

## Dependencia y riesgos

- **Dependencia de E4 (spec 017)**: `dispatch.max-attempts` solo puede leerse por mensaje cuando el listener
  usa ack manual con `x-dispatch-attempt`. Estado a 2026-10-05: E1 fusionada, E2 y E3 en curso, E4 sin
  fusionar. Hasta entonces, las tareas de esa clave quedan bloqueadas; el resto (registro, validación,
  persistencia, endpoint, sondeo, tiempos de proveedor, reencolador) no depende de E4.
- **Riesgo R-1**: el cambio de `WebClient` por versión (D5) toca tres adaptadores con pruebas E2E existentes
  (`BrevoEmailDeliveryE2ETest`, `TwilioSmsDeliveryE2ETest`, `FcmPushDeliveryE2ETest`); deben seguir en verde.
- **Riesgo R-2**: conflicto de fusión con E4 en `RabbitRetryConfig` y `NotificationDispatchListener`.
- **Riesgo R-3**: el contrato SUP-02 puede cambiar la forma del adaptador HTTP; el puerto aísla el cambio.

### Límites de contenido por proveedor (T003, regla (d))

Consultados el 2026-10-05. Solo se registra un valor con fuente verificable; los demás quedan fuera de la
tabla `ProviderContentLimits` y la regla (d) no impone límite a ese canal hasta que se verifiquen.

| Proveedor | Canal | Límite (clave) | Valor | Fuente | Consulta |
|---|---|---|---|---|---|
| Twilio | SMS | `body.maxLength` | 1 600 caracteres | https://www.twilio.com/docs/messaging/api/message-resource (campo `Body`: "Can be up to 1,600 characters in length") | 2026-10-05 |
| Brevo | EMAIL | tamaño total de adjuntos | sin fuente verificable | la referencia pública de `sendTransacEmail` consultada (https://developers.brevo.com/reference/sendtransacemail) no declara el tope de adjuntos y el artículo de ayuda respondió 403 | 2026-10-05 |
| FCM | PUSH | tamaño máximo del mensaje | sin fuente verificable | las páginas de documentación de FCM consultadas por HTTP no devolvieron el valor en el texto recuperado | 2026-10-05 |

Consecuencia: `ProviderContentLimits` contiene únicamente la fila de Twilio. Para Brevo y FCM no se impone límite
en la regla (d); confirmarlos queda en el pendiente de `plan.md` (dueño andrualv, antes de producción).
