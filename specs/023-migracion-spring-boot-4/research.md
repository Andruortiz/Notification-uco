# Research: Migración a Spring Boot 4.1 sobre Java 25 (HU2-106)

Fecha: 2026-10-16. Todo lo marcado "verificado" se comprobó contra artefactos reales de Maven Central (poms, jars y
metadatos de configuración de Boot 4.1.1 descargados) o contra una copia desechable del repositorio compilada con
JDK 25 (el "spike", fuera del árbol de trabajo). Lo marcado "supuesto" no se pudo confirmar y queda como riesgo.

## R-1. Versión objetivo

- **Decisión**: Spring Boot 4.1.1 (última GA en Maven Central; el starter del PEP usa 4.1.0 como padre, misma línea menor).
- **BOM verificado (spring-boot-dependencies 4.1.1)**: Spring Framework 7.0.9, Spring Data 2026.0.1, Spring AMQP 4.1.1,
  Spring Security 7.1.1, Jackson 3 `jackson-bom` 3.1.5 y `jackson-2-bom` 2.21.5, Micrometer 1.17.1, Micrometer Tracing
  1.7.1, OpenTelemetry 1.62.0, Reactor 2025.0.7, Netty 4.2.17.Final, Logback 1.5.38, Mockito 5.23.0, JUnit 6.0.3,
  Testcontainers 2.0.5, amqp-client 5.30.0, prometheus-client 1.7.0.
- **Alternativas**: 4.1.0 (idéntica al padre del starter, pero 4.1.1 es la GA vigente); 4.2.0-M2 (hito, descartada).

## R-2. Versiones de terceros que acompañan a Boot 4.1

| Librería | Hoy | Objetivo | Estado |
|---|---|---|---|
| Spring Modulith | 1.4.13 | 2.1.1 | Verificado: existe 2.1.1 y su `bom` incluye `spring-modulith-starter-core`/`-test`. Que 2.1.x apunte a Boot 4.1 es supuesto (cadencia de versiones); el spike compiló y los tests de arquitectura no se ejecutaron aún con ella. |
| springdoc | 2.9.1 | 3.1.1 | Verificado: su pom padre usa Boot 4.1.0 y depende de `spring-boot-starter-webflux-test`, `spring-boot-actuator-autoconfigure`, `spring-boot-reactor-netty`, `spring-boot-health`. |
| logstash-logback-encoder | 8.1 | 9.0 | Verificado: 9.0 usa `tools.jackson` 3.0.1 (8.1 usa Jackson 2). Que `ShortenedThrowableConverter` siga con la misma API lo confirma `StructuredLogLayoutTest` en el spike. |
| json-schema-validator (networknt) | 1.5.9 | 3.0.8 | Verificado: la 3.x usa `tools.jackson`; la 1.5.x/2.x usa Jackson 2. API distinta (ver R-6). |
| Testcontainers | gestionada por Boot 3.5 (1.x) | 2.0.5 (BOM de Boot) | Verificado: los artefactos se renombran a `testcontainers-junit-jupiter`, `-mongodb`, `-rabbitmq`, `-minio`. Las clases `org.testcontainers.containers.{MongoDBContainer,RabbitMQContainer}` siguen existiendo (obsoletas) junto a `org.testcontainers.{mongodb,rabbitmq}.*`; `MinIOContainer` solo existe en el paquete antiguo. |
| Resilience4j | 2.4.0 (`resilience4j-spring-boot3`) | Retirar | Verificado: 0 importaciones en el código. Existe `resilience4j-spring-boot4` 2.4.0 si se quisiera conservar. |
| spring-retry | sin versión (gestionada por Boot 3.5) | Retirar | Verificado: 0 importaciones; el BOM de Boot 4.1.1 ya no lo gestiona, la dependencia sin versión rompe la resolución. Spring AMQP 4.1.1 ya no lo declara. |
| jjwt | 0.13.0 | 0.13.0 | Es la última versión publicada. `jjwt-jackson` depende de `com.fasterxml.jackson.core:jackson-databind` (Jackson 2): permanece como excepción (R-5). |
| Azure SDK BOM | 1.3.8 | 1.3.9 | Última publicada; usa Jackson 2 internamente (excepción R-5). |
| archunit-junit5 1.5.1, tika 3.3.3, minio 8.6.0, bouncycastle 1.86, rabbitmq amqp-client 5.37.0, okhttp 4.12.0, netty 4.2.19 | | | Ver R-4 (pines). |
| JaCoCo 0.8.15, SpotBugs 4.10.4.1, Spotless 2.46.1 | | sin cambio | Ya validados con JDK 25 en los prerrequisitos; se revalidan al correr `verify`. |

## R-3. Cambios de módulos y paquetes de Boot 4 que afectan al código (verificado en los jars 4.1.1)

Boot 4 partió `spring-boot-autoconfigure` en módulos por tecnología. Con el spike compilado en JDK 25, el código de
producción falla en 5 archivos y las pruebas en 30 más:

| Antes (3.5) | Después (4.1.1) | Archivos |
|---|---|---|
| `org.springframework.boot.actuate.health.{Health,ReactiveHealthIndicator}` | `org.springframework.boot.health.contributor.*` (módulo `spring-boot-health`) | 2 producción (`AttachmentStorageHealthIndicator`, `ClamAvHealthIndicator`) + 1 prueba |
| `org.springframework.boot.autoconfigure.amqp.{RabbitProperties,SimpleRabbitListenerContainerFactoryConfigurer}` | `org.springframework.boot.amqp.autoconfigure.*` (módulo `spring-boot-amqp`) | 3 producción (`RabbitRetryConfig`, `ParametersEventsRabbitConfig`, `AttachmentScanRabbitConfig`) + pruebas |
| `org.springframework.boot.test.mock.mockito.{MockBean,SpyBean}` | `org.springframework.test.context.bean.override.mockito.{MockitoBean,MockitoSpyBean}` (Spring Framework) | 15 pruebas |
| `...test.autoconfigure.data.mongo.DataMongoTest` | `org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest` (`spring-boot-data-mongodb-test`) | 9 pruebas |
| `...test.autoconfigure.web.reactive.WebFluxTest` | `org.springframework.boot.webflux.test.autoconfigure.WebFluxTest` (`spring-boot-webflux-test`) | 5 pruebas |
| `...test.autoconfigure.actuate.observability.AutoConfigureObservability` | `org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics` (y `AutoConfigureTracing` en `...tracing.test.autoconfigure`) | 5 pruebas |
| `@AutoConfigureWebTestClient` | `org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient` (`spring-boot-webtestclient`) | por confirmar en las pruebas de slice |
| `LocalServerPort`, `LocalManagementPort`, `SpringBootTest`, `TestConfiguration`, `ApplicationContextRunner`, `AssertableApplicationContext` | sin cambio de paquete (`spring-boot-test`) | 0 |
| `ServiceConnection` (`spring-boot-testcontainers`) | sin cambio | 0 |
| `ConditionalOnProperty`, `ConditionalOnExpression`, `SpringBootApplication`, `ConfigurationProperties`, `EnableConfigurationProperties`, `DurationStyle` | sin cambio | 0 |
| `org.springframework.boot.env.EnvironmentPostProcessor` | existe además `org.springframework.boot.EnvironmentPostProcessor`; en el `spring.factories` de Boot 4 el contrato se registra bajo la clave `org.springframework.boot.EnvironmentPostProcessor` | 2 producción (`KeyVaultEnvironmentPostProcessor`, `OtlpEndpointEnvironmentPostProcessor`) + `META-INF/spring.factories`. **Riesgo alto**: ver R-7. |

Cambios de API de Spring AMQP 4 hallados al compilar las pruebas:
`QueueInformation.getMessageCount()` devuelve `long`; `RabbitMessageSenderContext` recibe un cuarto argumento
(routing key). Afectan solo a `AttachmentScanListenerTest` y `ObservationCorrelationFilterTest`.

## R-4. Pines de seguridad (PR #70 y #71) frente al BOM de Boot 4.1.1

| Pin | Valor del repositorio | BOM Boot 4.1.1 | Decisión |
|---|---|---|---|
| Netty | 4.2.19.Final | 4.2.17.Final | Conservar el pin (supera al BOM) hasta que Boot lo iguale; propiedad `netty.version`. |
| amqp-client | 5.37.0 | 5.30.0 | Conservar el pin (propiedad `rabbit-amqp-client.version` del BOM). |
| Jackson 2 | 2.22.3 | `jackson-2-bom` 2.21.5 | Conservar para el Jackson 2 transitivo (jjwt, Azure SDK, annotations). Propiedad `jackson-2-bom.version`. |
| Jackson 3 | n/a | `jackson-bom` 3.1.5 (existe 3.2.3) | Seguir el BOM; evaluar el pin a 3.1.7+ si hay CVE (a confirmar al implementar). |
| BouncyCastle 1.86, OkHttp 4.12.0 | pines propios | no gestionados por el BOM / OkHttp lo usa MinIO | Se conservan sin cambio. |

Los pines se aplican como propiedades que sobrescriben las del BOM de Boot (patrón documentado de Boot) y no con un
`import` de BOM antes del de Boot, para que la intención quede visible. Cada pin se anota en la nota de cambios con motivo
y condición de retiro (Principio VII). El `import` actual de `com.fasterxml.jackson:jackson-bom` 2.22.3 se elimina.

## R-5. Jackson 3: decisiones

- Anotaciones: siguen en `com.fasterxml.jackson.annotation` (Jackson 3 reutiliza `jackson-annotations` 2.x). Los 9
  archivos de producción de `infrastructure` que solo importan anotaciones (`@JsonInclude`, `@JsonIgnoreProperties`, `@JsonProperty`) **no cambian**.
- `databind` y `core`: 34 archivos (1 en `core`, 8 en `infrastructure` producción, 25 en pruebas) cambian de `com.fasterxml.jackson.{databind,core}` a `tools.jackson.{databind,core}`
  (`ObjectMapper`, `JsonNode`, `ObjectNode`, `ArrayNode`: 20+34+2+1 importaciones; `JsonProcessingException` pasa a
  `tools.jackson.core.JacksonException`).
- **Excepciones no verificadas**: `JacksonException` extiende `RuntimeException`. Cada `catch (IOException)` que envolvía una
  llamada de Jackson deja de compilar (el compilador lo detecta como "never thrown") y el que se silencia con un cambio
  incorrecto altera el flujo: `ConfigurationConfig` (esquemas de catálogo mal formados devolvían un mapa vacío y ahora
  habrían tumbado el arranque), `AttachmentScanListener`, `ParametersEventListener`, `NotificationUpdatesRabbitAdapter`
  (descarte de mensajes ilegibles). Cada sitio necesita una prueba que ejercite el mensaje malformado.
- **Bean de contexto**: Boot 4 ya no publica un `com.fasterxml.jackson.databind.ObjectMapper`; publica
  `tools.jackson.databind.json.JsonMapper`. Cinco clases lo reciben por constructor (`AttachmentScanListener`,
  `ParametersEventListener`, `AttachmentScanRequestRabbitPublisher`, `NotificationRabbitPublisher`,
  `NotificationUpdatesRabbitAdapter`). Verificado en el spike: sin migrarlas, 7 contextos fallan con
  `NoSuchBeanDefinitionException: com.fasterxml.jackson.databind.ObjectMapper`. Tras migrar el import, el bean `JsonMapper`
  satisface `ObjectMapper` (subclase).
- `new ObjectMapper()` ya no es la forma de construir un mapeador: se usa `JsonMapper.builder().build()` (4 sitios:
  `ContentSchemaValidator`, `ConfigurationConfig`, `FcmAccessTokenProvider`, `FcmCredentials`).
- API renombrada hallada: `JsonNode.fields()` -> `properties()`, `fieldNames()` -> `propertyNames()`,
  `ObjectMapper.configure(...)` -> builder (`disable(...)`), `findAndRegisterModules()` no hace falta (el soporte de
  `java.time` viene en databind).
- **Valores por defecto que cambian** (verificado en bytecode de `MapperFeature` 3.1.5): `SORT_PROPERTIES_ALPHABETICALLY`
  pasa a habilitado. Orden de campos en JSON de eventos de RabbitMQ y de respuestas puede cambiar; el significado no.
  Un mapeador creado con `JsonMapper.builder()` (no el de Boot) tiene `FAIL_ON_UNKNOWN_PROPERTIES` activo; el de Boot lo
  desactiva. Los listeners reciben el de Boot; los `static MAPPER` internos no deserializan a clases propias.
- **Jackson 2 residual**: `jjwt-jackson` 0.13.0 y el Azure SDK lo usan por su cuenta, en paquetes distintos. Boot 4 gestiona
  `jackson-2-bom`. Para la autoconfiguración de códecs HTTP, `spring.http.codecs.preferred-json-mapper` por defecto elige
  Jackson 3 cuando ambos están presentes (verificado en `CodecsAutoConfiguration`: ramas `JacksonJsonCodecConfiguration` /
  `Jackson2JsonCodecConfiguration` / `NoJacksonOrJackson2Preferred`). Excepción registrada con dueño y fecha (FR-016).

## R-6. Validador de esquemas JSON (core) con networknt 3.0.8

`core` importa Jackson directamente y usa networknt (no Spring), por lo que debe migrar a la versión de networknt que
use Jackson 3 (3.0.8), o mantener Jackson 2 solo en `core`. **Decisión**: migrar (Jackson 3 en todo el código propio).
Cambios de API verificados en el jar: `JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)` ->
`SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)`; `JsonSchema` -> `Schema`;
`Set<ValidationMessage>` -> `List<Error>`.

**Cambio de comportamiento hallado en el spike (4 de las 20 pruebas de `ContentSchemaValidatorTest` fallan)**:
`Error.getMessage()` ya no antepone la ubicación de la instancia y el texto sale en el idioma por defecto de la JVM
(español con `es_MX`: "debe tener un valor máximo de ..."). El mensaje de `InvalidContentException` llega al cliente en la
respuesta de error de la API (SC-004). Con el prefijo `Error.getInstanceLocation()` el formato pasa de `attachments[0]` a
la notación de puntero JSON `/attachments/0/sizeBytes`. Resolución prevista: formatear la ubicación con
`PathType.LEGACY` o con un formateador propio que conserve `attachments[N]`, y fijar el idioma de los mensajes para que no
dependa de la JVM; la prueba de contrato de errores (E2E) fija el texto. Si no se logra la equivalencia exacta, se
documenta como cambio de comportamiento aceptado por el usuario (el texto de `details` no forma parte del contrato
OpenAPI, pero sí es observable).

## R-7. Propiedades de configuración (verificadas en los metadatos de Boot 4.1.1)

| Propiedad actual | Estado en 4.1.1 | Acción |
|---|---|---|
| `spring.data.mongodb.uri` (application.yml y 2 pruebas) | `deprecated`, nivel `error`, reemplazo `spring.mongodb.uri` | Renombrar. Si no, el servicio arranca contra `localhost:27017` ignorando `MONGO_*`. |
| `spring.data.mongodb.auto-index-creation` | vigente (módulo `spring-boot-data-mongodb`) | Sin cambio. |
| `spring.codec.max-in-memory-size: 8MB` | no existe; vigente `spring.http.codecs.max-in-memory-size` | Renombrar. Si no, el límite vuelve a 256 KB y las cargas de adjuntos fallan. |
| `management.otlp.tracing.endpoint` (en un `EnvironmentPostProcessor` y 3 usos más) | `deprecated`, nivel `error`, reemplazo `management.opentelemetry.tracing.export.otlp.endpoint` | Renombrar (código y pruebas). Si no, no se exportan trazas. |
| `management.endpoint.prometheus.enabled` | no existe; vigente `management.endpoint.prometheus.access` | Eliminar (el acceso por defecto basta) o migrar a `access`. |
| `spring.rabbitmq.{host,port,username,password,publisher-confirm-type,template.observation-enabled,listener.simple.observation-enabled}` | vigentes (módulo `spring-boot-amqp`) | Sin cambio. |
| `spring.reactor.context-propagation`, `management.tracing.sampling.probability`, `management.metrics.distribution.percentiles-histogram.*`, `management.endpoints.web.exposure.include`, `management.server.port`, `management.endpoint.health.{probes.enabled,show-components,group.*}` | vigentes | Sin cambio. |
| `springdoc.swagger-ui.url` | propiedad de springdoc 3.x | Supuesto vigente; verificar al arrancar. |

El nombre de las variables de entorno de despliegue (`MONGO_*`, `RABBITMQ_*`, etc.) no cambia. Como defensa, la
implementación añade una prueba que recorre `application*.yml` y falla si una clave coincide con una propiedad marcada como
eliminada en los metadatos de Boot 4 (SC-008), y una prueba de arranque que comprueba que `MONGO_*` y `management.otlp`
tienen efecto real.

## R-8. Observabilidad en Boot 4

- Boot 4 separa en módulos: `spring-boot-micrometer-metrics` (endpoint Prometheus, histogramas), `spring-boot-micrometer-tracing`
  (+ `-opentelemetry`), `spring-boot-opentelemetry`. `spring-boot-starter-actuator` ya no trae métricas por sí solo: se
  añade `spring-boot-starter-micrometer-metrics`. Verificado: contiene `spring-boot-micrometer-metrics`.
- `spring-boot-starter-opentelemetry` agrupa `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`,
  `micrometer-registry-otlp` y metrics. **Riesgo**: `micrometer-registry-otlp` podría activar exportación de métricas por
  OTLP; el servicio hoy exporta solo trazas. Decisión: conservar las dependencias explícitas actuales (`micrometer-registry-prometheus`,
  `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`) más `spring-boot-starter-micrometer-metrics`, y no adoptar
  el starter agregado, para no cambiar lo exportado.
- Pruebas: `@AutoConfigureObservability` desaparece; `AutoConfigureMetrics` (y `AutoConfigureTracing` donde se necesite)
  reemplazan su efecto de habilitar la exportación en pruebas de slice. Hay 5 pruebas afectadas.
- `context-propagation` y `spring.reactor.context-propagation=auto` siguen vigentes (propiedad verificada).

## R-9. Pruebas

- Dependencias nuevas de prueba: `spring-boot-starter-webflux-test` (WebTestClient, `@WebFluxTest`),
  `spring-boot-starter-data-mongodb-reactive-test` (`@DataMongoTest`), `spring-boot-starter-micrometer-metrics-test`
  (`@AutoConfigureMetrics`), `spring-boot-micrometer-tracing-test`. Todas existen para 4.1.1.
- Testcontainers 2: `testcontainers-*` renombrados; mover 86 imports (48 archivos) a `org.testcontainers.mongodb.MongoDBContainer` y
  `org.testcontainers.rabbitmq.RabbitMQContainer` (las clases antiguas siguen, obsoletas; se migran por higiene, cambio mecánico).
- El workaround local `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` se mantiene; Testcontainers 2 puede no necesitarlo (se
  comprueba al ejecutar).
- Mockito 5.23 y ByteBuddy con Java 25 funcionan en el spike (714 pruebas de `core` y 32 de `utils` en verde).

## R-10. Starter del PEP

El starter (jar 0.1.3) contiene `PepEnforcementAutoConfiguration`, `PepEnforcementWebFilter`, `PepRegistrationAutoConfiguration` y
sus propiedades, y depende de `spring-boot-starter-webclient` y `spring-boot-starter-webflux`. No define `jsonComponentModule`.
Los 240 errores de la línea base eran todos `BeanDefinitionOverrideException` por mezclar Boot 3.5 con módulos de Boot 4
(`spring-boot-starter-webclient` arrastra `spring-boot-jackson`). Con el classpath en una sola línea el conflicto debe
desaparecer; US3 lo demuestra con un arranque real.

## R-11. Entorno

JDK 25.0.3 está instalado junto a JDK 21 (el `JAVA_HOME` por defecto de esta máquina es 21); el build exige 25 por
`maven.compiler.release`. Docker Desktop 29.4.3 está disponible; el spike corrió pruebas de Testcontainers con
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`.

## Resultados del spike (copia desechable, no es parte de la entrega)

Ver sección final de `plan.md` ("Evidencia del spike") para los resultados de ejecución.
