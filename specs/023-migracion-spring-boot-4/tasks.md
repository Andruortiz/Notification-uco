# Tasks: Migración del servicio a Spring Boot 4.1 sobre Java 25 (HU2-106)

**Input**: `specs/023-migracion-spring-boot-4/` (spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md)

Orden: las fases del plan. Formato: `- [ ] Txxx [P?] [USn?] descripción con ruta`. Ejecutar con `JAVA_HOME` en JDK 25 y
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`. `spotless:apply` tras crear o editar `.java`. Sin comentarios en código nuevo.
Decisiones aprobadas: Q-A secretos del repositorio `PACKAGES_USER`/`PACKAGES_TOKEN` (expuestos como las variables `GITHUB_PACKAGES_USER`/`GITHUB_PACKAGES_TOKEN`); retirar `resilience4j-spring-boot3` y
`spring-retry`; Jackson 3 en código propio (EX-01 Jackson 2 transitivo hasta 2027-01-15); conservar solo pines sobre el BOM;
ADR-0012; variables de entorno sin renombrar; texto de error de contenido idéntico al actual; orden alfabético de Jackson
desactivado.

## Fase 0 — Línea base y guardias (US1, US2, US3)

- [x] T001 [US2] Capturar con la versión actual (Boot 3.5.16) los documentos de referencia: JSON de eventos de dominio, mensajes consumidos, catálogo de nombres de métrica y mensaje de contenido inválido, en `infrastructure/src/test/resources/reference/` -- Desviación: capturadas una sola vez con Boot 3.5.16 en una copia desechable (eventos y mensajes de contenido); sin catálogo de métricas
- [x] T002 [P] [US1] Prueba de guardia de importaciones prohibidas en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/architecture/ForbiddenImportsTest.java` (SC-003)
- [x] T003 [P] [US1] Prueba de guardia de claves de configuración eliminadas en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/RemovedPropertiesGuardTest.java` (SC-008)
- [x] T004 [P] [US1] Prueba de ejecución de los `EnvironmentPostProcessor` registrados en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/EnvironmentPostProcessorRegistrationTest.java` (FR-008)
- [x] T005 [P] [US3] Prueba de contexto completo con el starter del PEP deshabilitado y habilitado en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/PepStarterContextTest.java` (FR-011) -- Implementada como `PepStarterEnabledContextTest` (habilitado) y `MigrationContextTest` (deshabilitado)
- [x] T006 [P] [US2] Prueba de contrato de mensajes de RabbitMQ contra las referencias en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/rabbit/EventPayloadReferenceTest.java` (FR-004) -- Implementada dentro de `MigrationContextTest`
- [x] T007 [P] [US2] Prueba del texto de `details` de contenido inválido en `core/src/test/java/co/edu/uco/notification/core/domain/ContentSchemaValidatorTest.java` (FR-005) -- Implementada como `ContentSchemaMessageReferenceTest`
- [ ] T008 [P] [US2] Prueba del catálogo de métricas contra la referencia en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/MetricsCatalogReferenceTest.java` (FR-009) -- No implementada: el catálogo de métricas lo cubren `MetricsE2ETest`, `ObservabilityE2ETest` y `Hu2106MigrationE2ETest`

## Fase 1 — `pom.xml`, `utils` y `core` (US1)

- [x] T009 [US1] `pom.xml` raíz: versiones (Boot 4.1.1, Modulith 2.1.1, springdoc 3.1.1, logstash 9.0, networknt 3.0.8, Azure 1.3.9), retirar imports de `jackson-bom` 2.x y `resilience4j-bom`, pines por propiedad, regla `maven-enforcer` `bannedDependencies` (SC-002)
- [x] T010 [US1] `core`: migrar `core/src/main/java/co/edu/uco/notification/core/domain/policy/ContentSchemaValidator.java` a networknt 3.0.8 y Jackson 3 conservando el texto de error actual
- [x] T011 [US1] Verificar `utils` y `core`: `./mvnw -B -ntp -pl core -am test`

## Fase 2 — `infrastructure`, producción (US1, US2, US3)

- [x] T012 [US1] `infrastructure/pom.xml`: retirar `resilience4j-spring-boot3` y `spring-retry`, añadir `spring-boot-starter-micrometer-metrics`, dependencias de prueba nuevas y artefactos `testcontainers-*` -- Desviación: se añadieron `spring-boot-micrometer-tracing-opentelemetry` y `spring-boot-opentelemetry` (sin ellos Boot 4 no autoconfigura el trazado) y `management.logging.export.otlp.enabled=false`
- [x] T013 [P] [US1] Paquetes reubicados en `AttachmentStorageHealthIndicator`, `ClamAvHealthIndicator`, `RabbitRetryConfig`, `ParametersEventsRabbitConfig`, `AttachmentScanRabbitConfig`
- [x] T014 [US1] Jackson 3 en los 8 archivos de producción de `infrastructure` (listeners, publishers, `FcmAccessTokenProvider`, `FcmCredentials`, `ConfigurationConfig`) con `catch` de `JacksonException`
- [x] T015 [US3] `EnvironmentPostProcessor` nuevo en `KeyVaultEnvironmentPostProcessor`, `OtlpEndpointEnvironmentPostProcessor` y `META-INF/spring.factories`
- [x] T016 [US2] `application.yml`: `spring.mongodb.uri`, `spring.http.codecs.max-in-memory-size`, sin `management.endpoint.prometheus.enabled`, orden alfabético de Jackson desactivado
- [x] T017 [US1] Compilar: `./mvnw -B -ntp clean compile`

## Fase 3 — `infrastructure`, pruebas (US1, US2)

- [x] T018 [P] [US1] `@MockBean`/`@SpyBean` a `@MockitoBean`/`@MockitoSpyBean` (15 archivos)
- [x] T019 [P] [US1] Slices `DataMongoTest`, `WebFluxTest`, `AutoConfigureObservability` a los paquetes nuevos (y `AutoConfigureWebTestClient` si hace falta)
- [x] T020 [P] [US1] Testcontainers 2: imports de `MongoDBContainer` y `RabbitMQContainer` (48 archivos)
- [x] T021 [P] [US1] Jackson 3 y API de AMQP 4 en las pruebas (25 archivos, `queueDepth`, `RabbitMessageSenderContext`)
- [x] T022 [P] [US2] Propiedades renombradas en pruebas (`spring.mongodb.uri`, `management.opentelemetry.tracing.export.otlp.endpoint`) y frase de estado HTTP 413
- [x] T023 [US1] Ejecutar por clase las pruebas de guardia y las que fallaron en el spike

## Fase 4 — Puerta completa y E2E (US1, US2, US3)

- [x] T024 [US3] Prueba E2E explícita: arranque completo con el starter del PEP y flujo de punta a punta contrastado con las referencias en `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/Hu2106MigrationE2ETest.java`
- [x] T025 [US1] `./mvnw -B -ntp clean verify` completo; corregir hasta verde (DLQ con broker desechable en 5673 si hace falta) -- Resultado: `BUILD SUCCESS`, 32 + 715 + 996 pruebas, 0 fallos; las dos de DLQ excluidas (broker 5673 ocupado por el del usuario)

## Fase 5 — Documentación, imagen y pipeline (US4, US5)

- [x] T026 [US4] `.github/workflows/ci-cd.yml` y `codeql.yml`: `setup-java` con `server-id: github`, `server-username`/`server-password` desde `GITHUB_PACKAGES_USER`/`GITHUB_PACKAGES_TOKEN`
- [x] T027 [US4] `Dockerfile`: secreto de BuildKit para las credenciales de GitHub Packages; documentar en README
- [x] T028 [P] [US5] ADR-0012 en `docs/adr/0012-spring-boot-4.md` y estado de `docs/adr/0002-spring-boot-webflux.md` (docs/ no se versiona) -- Archivos creados en docs/, que no está versionado
- [x] T029 [P] [US5] Constitución 1.2.2 en `.specify/memory/constitution.md` y ejemplo de `.specify/templates/plan-template.md`
- [x] T030 [P] [US5] `specs/023-migracion-spring-boot-4/cambios-de-comportamiento.md` con CC-xx, pines y excepciones
- [ ] T031 [US4] Construir la imagen localmente y comprobar el arranque -- No completada: `docker build` con los secretos llega a Maven pero GitHub Packages responde 401 con la credencial local; requiere un token con `read:packages`
