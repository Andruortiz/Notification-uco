# Cambios de comportamiento hallados en la migración a Spring Boot 4.1 (HU2-106)

Cada fila indica qué cambió, su causa, la prueba que lo detecta y la resolución. Los textos de referencia "antes" se
capturaron con Spring Boot 3.5.16 antes de migrar y viven en `core/src/test/resources/reference/` e
`infrastructure/src/test/resources/reference/`.

| Id | Qué cambió | Causa | Detectado por | Resolución |
|---|---|---|---|---|
| CC-01 | El detalle del 422 por contenido inválido perdía la ruta (`$.attachments[1].contentType: ...`) y la ruta pasaba a puntero JSON | networknt 3.x | `ContentSchemaMessageReferenceTest` | Conservado: `PathType.LEGACY` y prefijo de ubicación; el idioma sigue el de la JVM como antes |
| CC-02 | `spring.data.mongodb.uri` se ignora | Boot 4 | `RemovedPropertiesGuardTest` | `spring.mongodb.uri` |
| CC-03 | `spring.codec.max-in-memory-size` se ignora (límite 256 KB, 413 en cargas grandes) | Boot 4 | `RemovedPropertiesGuardTest`, E2E de adjuntos | `spring.http.codecs.max-in-memory-size` |
| CC-04 | `management.otlp.tracing.endpoint` se ignora (sin trazas) | Boot 4 | `RemovedPropertiesGuardTest`, `OtlpEndpointEnvironmentPostProcessorTest`, `TracingE2ETest` | `management.opentelemetry.tracing.export.otlp.endpoint` |
| CC-05 | `EnvironmentPostProcessor` registrado con el contrato anterior dejaba de ejecutarse | Boot 4 | `EnvironmentPostProcessorRegistrationTest` | Interfaz y clave nuevas en `spring.factories` |
| CC-06 | Orden alfabético de propiedades JSON por defecto | Jackson 3 | `MigrationContextTest` | `spring.jackson.mapper.sort-properties-alphabetically=false` |
| CC-07 | Excepciones de Jackson no verificadas; los `catch (IOException)` dejaban de capturar | Jackson 3 | compilación y pruebas de los consumidores | `catch` de `JacksonException`/`RuntimeException` |
| CC-08 | Sin bean `com.fasterxml.jackson.databind.ObjectMapper` | Boot 4 | arranque del contexto | Inyección de `tools.jackson.databind.ObjectMapper` (el bean es `JsonMapper`) |
| CC-09 | `management.endpoint.prometheus.enabled` ya no existe | Boot 4 | `RemovedPropertiesGuardTest` | Retirada; el acceso por defecto basta |
| CC-10 | La frase de estado de HTTP 413 pasa de "Payload Too Large" a "Content Too Large"; el código no cambia | Spring Framework 7 | prueba de adjuntos | Prueba actualizada; el código 413 es el mismo |
| CC-11 | `QueueInformation.getMessageCount()` y las propiedades de cola devuelven `long` | Spring AMQP 4 | `ParametersEventSubscriptionE2ETest`, `AttachmentScanListenerTest` | Pruebas ajustadas |

## Pines por encima del BOM de Boot 4.1.1 (Principio VII)

| Librería | Fijada | BOM de Boot | Motivo | Dueño | Retiro |
|---|---|---|---|---|---|
| Netty | 4.2.19.Final | 4.2.17.Final | CVE (PR #70) | Equipo del servicio | Cuando el BOM iguale o supere |
| amqp-client | 5.37.0 | 5.30.0 | CVE (PR #71) | Equipo del servicio | Cuando el BOM iguale o supere |
| Jackson 2 | 2.22.3 | 2.21.5 | CVE (PR #70) | Equipo del servicio | Cuando el BOM iguale o supere |
| BouncyCastle, OkHttp | 1.86, 4.12.0 | no gestionados | CVE / compatibilidad con MinIO | Equipo del servicio | Revisión mensual |

## Excepciones

| Id | Descripción | Dueño | Vence |
|---|---|---|---|
| EX-01 | Jackson 2 (`com.fasterxml.jackson`) permanece en el classpath solo como dependencia transitiva de terceros, medido con `dependency:tree -pl infrastructure -am`: `io.minio:minio` 8.6.0 (`jackson-core`, `jackson-databind`, `jackson-annotations`), `springdoc-openapi-starter-common` 3.1.1 a traves de `swagger-core-jakarta` 2.2.55 (`jackson-dataformat-yaml`), `com.azure:azure-core` 1.60.0 (`jackson-datatype-jsr310`) y `jjwt-jackson` 0.13.0 en ejecucion. El codigo del repositorio no importa Jackson 2 (`ForbiddenImportsTest`) y networknt ya usa Jackson 3. Versiones fijadas por el pin de `jackson-bom` 2.22.3 | Equipo del servicio | 2027-01-15 |

## Hallazgos adicionales de la implementación

| Id | Qué cambió | Causa | Detectado por | Resolución |
|---|---|---|---|---|
| CC-12 | Sin `spring-boot-micrometer-tracing-opentelemetry` y `spring-boot-opentelemetry` en el classpath, Boot 4 no autoconfigura el trazado aunque `micrometer-tracing-bridge-otel` y `opentelemetry-exporter-otlp` estén presentes: se perdían todas las trazas | Boot 4 modulariza la autoconfiguración | `TracingE2ETest`, `ObservabilityE2ETest`, `TracingResilienceE2ETest` | Dependencias añadidas; `management.logging.export.otlp.enabled=false` para no exportar logs por OTLP |
| CC-13 | `@AutoConfigureObservability` desaparece y la exportación de métricas y trazas en pruebas exige `@AutoConfigureMetrics` y `@AutoConfigureTracing` | Boot 4 | pruebas de observabilidad | Ambas anotaciones en las pruebas afectadas |
| CC-14 | El filtro del starter del PEP solo se registra con `security.enabled=true` y `security.pep.enforcement.application-credential` | Starter 0.1.3 | `PepStarterEnabledContextTest` | Propiedades añadidas a la prueba |

Intermitencias observadas en una ejecución completa que pasaron al repetir la clase: `AuthenticationSecurityE2ETest` (fallo al arrancar el contenedor de RabbitMQ), `ChannelCatalogControllerTest` y `ConfigurationControllerTest` (timeout de 5 s), `TwilioNotificationProviderTest` (aserción de log). La ejecución completa final fue verde.

## OkHttp

El exportador OTLP traia `opentelemetry-exporter-sender-okhttp` y con el `okhttp-jvm` 5.3.2, que duplicaba las clases `okhttp3.*` del `okhttp` 4.12.0 fijado para MinIO. Se excluye ese sender de `opentelemetry-exporter-otlp` y se usa `opentelemetry-exporter-sender-jdk` (cliente HTTP del JDK) en ejecucion; queda un solo `okhttp` (4.12.0). La exportacion de trazas la sigue demostrando `TracingE2ETest`.
