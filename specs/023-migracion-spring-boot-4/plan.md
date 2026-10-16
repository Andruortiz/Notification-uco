# Implementation Plan: Migración del servicio a Spring Boot 4.1 sobre Java 25 (HU2-106)

**Branch**: `feature/HU2-106-migracion-spring-boot-4` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/023-migracion-spring-boot-4/spec.md`

## Estado del plan

**Estado**: Aprobado (2026-10-16)

**Versión del plan**: 1

## Summary

Subir el servicio de Spring Boot 3.5.16 a 4.1.1 (Spring Framework 7, Jackson 3, Spring Modulith 2.1.1, springdoc 3.1.1,
Testcontainers 2.0.5) sobre Java 25, de modo que el classpath tenga una sola línea de plataforma y el starter del PEP
(construido sobre Boot 4.1) arranque sin conflictos. Es una migración de plataforma: ningún endpoint, evento, métrica,
traza ni campo de log cambia. El trabajo se ordena por módulo (`utils` y `core` primero, `infrastructure` después), con
pruebas de guardia nuevas que convierten los criterios de aceptación en comprobaciones automáticas, y termina con el ADR
que reemplaza a ADR-0002 y la nota de cambios de comportamiento.

El enfoque se apoya en un spike desechable (copia del repositorio en el directorio temporal de la sesión, compilada con JDK 25
y probada con Docker) que dimensionó el trabajo con datos reales; los hallazgos están en `research.md` y en la sección
"Evidencia del spike" más abajo.

## Technical Context

**Language/Version**: Java 25 (ADR-0011), `maven.compiler.release=25`

**Primary Dependencies**: Spring Boot 4.1.1 / WebFlux sobre Spring Framework 7.0.9, Reactor 2025.0.7, Spring Data MongoDB
reactivo (Spring Data 2026.0.1), Spring AMQP 4.1.1, Spring Modulith 2.1.1, springdoc 3.1.1, Jackson 3 (`tools.jackson`, 3.1.5)
con `jackson-annotations` 2.x, Micrometer 1.17.1 / Micrometer Tracing 1.7.1 / OpenTelemetry 1.62.0, logstash-logback-encoder
9.0, networknt json-schema-validator 3.0.8, jjwt 0.13.0 (Jackson 2 transitivo), Azure SDK BOM 1.3.9, starter del PEP 0.1.3

**Storage**: MongoDB (sin cambios de esquema ni de datos); RabbitMQ (sin cambios de topología)

**Testing**: JUnit 6 (BOM de Boot 4.1.1), Mockito 5.23 (`@MockitoBean`/`@MockitoSpyBean`), `StepVerifier`, `WebTestClient`,
Testcontainers 2.0.5, ArchUnit 1.5.1, Spring Modulith test

**Target Platform**: contenedor Linux (imagen `eclipse-temurin:25-jre`), réplicas múltiples

**Project Type**: microservicio hexagonal de tres módulos Maven: `utils`, `core`, `infrastructure`

**Performance Goals**: sin cambio; arranque <= 30 s (RNF-12)

**Constraints**: comportamiento observable idéntico (FR-006, FR-009); `core` sin Spring; sin comentarios en código nuevo;
cobertura >= 80 % líneas / >= 70 % ramas

**Scale/Scope**: 319 archivos Java (core 170 + 74 pruebas, utils 6 + 5, infrastructure 143 + 145); 935 pruebas hoy

## Constitution Check

*GATE: debe pasar antes de la investigación; se reevalúa tras el diseño.*

| Principio | Evaluación |
|---|---|
| I. Hexagonal | Sin cambios de dependencias entre capas. `core` ya importa Jackson y networknt (no son Spring); sigue sin importar Spring. `HexagonalArchitectureTest` se ejecuta en cada punto de verificación. **Pasa.** |
| II. Contract-first | No hay endpoints nuevos. El YAML del contrato no se toca (SC-004). **Pasa.** |
| III. Sin comentarios | El código modificado no añade comentarios; las explicaciones viven en esta carpeta. **Pasa.** |
| IV. Calidad verificada | Cada FR/SC tiene prueba automatizada (ver "Verificación por criterio"); incluye E2E del flujo completo (tarea explícita). `verify` completo al cerrar. **Pasa.** |
| V. Trazabilidad en git | Commits de una línea `tipo(ámbito): descripción`, sin pie de atribución, solo locales. **Pasa.** |
| VI. spec-kit | Spec y plan aprobados por el usuario antes de `tasks`/`implement`. **Pendiente de aprobación.** |
| VII. Sin atajos | Jackson 2 transitivo (EX-01) y cada pin de seguridad quedan registrados con dueño y fecha; nada se deja con `@Disabled` ni umbrales relajados. **Pasa con excepciones registradas.** |
| VIII/IX. Confiabilidad y observabilidad | Ack manual y DLQ, métricas, trazas y logs se conservan y se prueban (CC-05, CC-07 son los riesgos). **Pasa.** |
| Restricciones técnicas | El texto "Spring Boot 3 sobre WebFlux/Reactor (ADR-0002, ADR-0003)" de la constitución se actualiza a Spring Boot 4 y al ADR-0012; es una aclaración de versión de plataforma (PATCH, 1.2.2) con Sync Impact Report. **Pasa.** |

Reevaluación tras el diseño: sin violaciones nuevas. No hay entradas en Complexity Tracking.

## Alcance e inventario de cambios (con datos)

### Archivos afectados (medidos con búsquedas en el repositorio y confirmados en el spike)

| Cambio | core | utils | infra producción | infra pruebas |
|---|---|---|---|---|
| `com.fasterxml.jackson.{databind,core}` -> `tools.jackson.*` | 1 | 0 | 8 | 25 |
| Anotaciones `com.fasterxml.jackson.annotation` (sin cambio) | 0 | 0 | 9 | 0 |
| `new ObjectMapper()` -> `JsonMapper.builder()...` | 1 | 0 | 3 | 3 |
| `catch (IOException)` alrededor de Jackson -> `JacksonException` | 1 | 0 | 4 | 3 |
| `JsonNode.fields()/fieldNames()`, `configure`, `findAndRegisterModules` | 0 | 0 | 1 | 5 |
| networknt 1.x -> 3.x (`SchemaRegistry`, `Schema`, `Error`) | 1 | 0 | 0 | 0 |
| `actuate.health` -> `health.contributor` | 0 | 0 | 2 | 1 |
| `autoconfigure.amqp` -> `amqp.autoconfigure` | 0 | 0 | 3 | 2 |
| `@MockBean`/`@SpyBean` -> `@MockitoBean`/`@MockitoSpyBean` | 0 | 0 | 0 | 15 |
| `DataMongoTest` / `WebFluxTest` / `AutoConfigureObservability` -> nuevos paquetes | 0 | 0 | 0 | 9 / 5 / 5 |
| Testcontainers 2 (artefactos + paquetes `mongodb`, `rabbitmq`) | 0 | 0 | 0 | 48 |
| API de Spring AMQP 4 (`getMessageCount` long, `RabbitMessageSenderContext`) | 0 | 0 | 0 | 2 |
| `EnvironmentPostProcessor` (interfaz y `spring.factories`) | 0 | 0 | 2 + 1 recurso | 1 |
| Propiedades renombradas en yml y pruebas | 0 | 0 | 2 yml | 3 |

Cero cambios de código en `utils`. En `core` el único archivo afectado es `ContentSchemaValidator` (la API de networknt y
Jackson), más su prueba si la salida de mensajes cambia.

### Cambios en `pom.xml` (raíz)

- `spring-boot.version` 3.5.16 -> 4.1.1; `spring-modulith.version` -> 2.1.1; `springdoc.version` -> 3.1.1;
  `logstash-logback-encoder.version` -> 9.0; `json-schema-validator.version` -> 3.0.8; `azure-sdk-bom.version` -> 1.3.9.
- Eliminar el `import` de `com.fasterxml.jackson:jackson-bom` 2.22.3 y el de `resilience4j-bom` y su propiedad.
- Sobrescribir por propiedad los pines vigentes que superan al BOM (Netty, amqp-client, `jackson-2-bom`); retirar los que
  el BOM iguale (R-4). Mantener `okhttp-bom`, BouncyCastle y los `jjwt-*` gestionados.
- Añadir `maven-enforcer-plugin` (regla `bannedDependencies`) o equivalente que falle la construcción si aparece un artefacto
  de Boot 3.x, Modulith 1.x, springdoc 2.x o `resilience4j-spring-boot3` (SC-002). La alternativa es una prueba de
  arquitectura que lea `dependency:tree`; se decide en la tarea (preferencia: enforcer, no requiere código).

### Cambios en `infrastructure/pom.xml`

- Retirar `resilience4j-spring-boot3` y `spring-retry`.
- Añadir `spring-boot-starter-micrometer-metrics`; conservar `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-otlp`, `context-propagation`.
- Pruebas: añadir `spring-boot-starter-webflux-test`, `spring-boot-starter-data-mongodb-reactive-test`,
  `spring-boot-starter-micrometer-metrics-test`, `spring-boot-micrometer-tracing-test`; renombrar `org.testcontainers:*` a
  `testcontainers-junit-jupiter`, `-mongodb`, `-rabbitmq`, `-minio`.
- Mantener el repositorio y la dependencia del starter del PEP (prerrequisito ya presente).

### Configuración

`application.yml`: `spring.data.mongodb.uri` -> `spring.mongodb.uri`; `spring.codec.max-in-memory-size` ->
`spring.http.codecs.max-in-memory-size`; eliminar `management.endpoint.prometheus.enabled` (o migrar a `access`). Código y
pruebas con `management.otlp.tracing.endpoint` -> `management.opentelemetry.tracing.export.otlp.endpoint`. `META-INF/spring.factories`:
registrar los dos `EnvironmentPostProcessor` bajo `org.springframework.boot.EnvironmentPostProcessor` e implementar la interfaz
`org.springframework.boot.EnvironmentPostProcessor`. `logback-spring.xml` y `application-local.yml`: sin cambios previstos
(se confirma en las pruebas de log).

### Documentación y pipeline

ADR-0012 (reemplaza ADR-0002), actualizar ADR-0002 (estado "Reemplazado por ADR-0012"), constitución 1.2.2 (texto de
"Restricciones técnicas"), plantilla `plan-template.md` (ejemplo "Spring Boot 3"), README si cita Boot 3 (no cita), nota de
cambios de comportamiento. `Dockerfile` y workflows ya están en Java 25; se verifican sin cambio esperado. Dependabot ignora
mayores, de modo que Boot 4 es una subida manual y no requiere cambio en `dependabot.yml`.

## Orden de migración (por módulo y con puntos de verificación)

La rama no tiene estado intermedio publicable: los commits siguen el orden de abajo, cada uno compila su ámbito, pero la
suite completa solo es exigible al final de la Fase 4. Todos los cambios del árbol de trabajo actual (prerrequisitos) se
commitean antes como un commit aparte decidido por el usuario.

**Fase 0 — Línea base y guardias (antes de migrar nada)**
1. Capturar documentos de referencia con la versión anterior: JSON de un evento de dominio y de cada mensaje consumido,
   catálogo de nombres de métrica tras ejercitar los flujos, respuesta de error de contenido inválido. Se guardan como recursos
   de prueba.
2. Escribir las pruebas de guardia que fallan hoy y deben pasar al final: importaciones prohibidas (SC-003), claves de
   configuración eliminadas (SC-008), `EnvironmentPostProcessor` se ejecuta (FR-008), contexto completo arranca con el
   starter (FR-011), regla `bannedDependencies` (SC-002), contrato de mensajes de RabbitMQ (FR-004), texto de `details` de
   contenido inválido (FR-005).

**Fase 1 — `utils` y `core` (sin Spring)**
3. `pom.xml` raíz (versiones y BOM). `utils`: sin cambio de código; compila y sus 32 pruebas pasan.
4. `core`: `ContentSchemaValidator` a networknt 3.0.8 + Jackson 3; resolver CC-01 (ruta `attachments[N]` e idioma fijo) hasta
   que `ContentSchemaValidatorTest` pase sin tocar sus aserciones; las 714 pruebas de `core` en verde.
   Verificación: `./mvnw -pl core test` y `HexagonalArchitectureTest`.

**Fase 2 — `infrastructure`, producción**
5. `infrastructure/pom.xml` (dependencias, retiros, nuevas).
6. Paquetes relocalizados (health, amqp), Jackson 3 en los 8 archivos, catches de `JacksonException`, constructores de
   `JsonMapper`, `EnvironmentPostProcessor`, `spring.factories`, `application.yml`.
   Verificación: `clean compile`, arranque del contexto con el PEP deshabilitado.

**Fase 3 — `infrastructure`, pruebas**
7. Anotaciones de prueba (`@MockitoBean`, slices), Testcontainers 2, API de AMQP, `JsonNode`, propiedades en pruebas.
8. Pruebas de slice (`@WebFluxTest`): confirmar si requieren `@AutoConfigureWebTestClient` (por verificar al ejecutar).
   Verificación: pruebas por clase, luego `infrastructure` completo con Docker.

**Fase 4 — Puerta completa**
9. `./mvnw -B -ntp clean verify` (con las dos pruebas de DLQ aparte si el orden local las rompe); corregir hasta verde;
   revisar `jacoco` y `spotbugs`. `spotless:apply` tras cada archivo `.java` tocado.
10. Prueba E2E explícita de la historia: arranque completo con el starter del PEP y recorrido de un flujo de punta a punta
    (aceptar, despachar, evento en RabbitMQ, métrica, traza, log) contrastado con los documentos de referencia (Principio IV).

**Fase 5 — Documentación y pipeline**
11. ADR-0012, ADR-0002, constitución 1.2.2, nota de cambios de comportamiento, excepciones EX-01 y pines.
12. Credenciales de GitHub Packages en CI y en el `Dockerfile` (riesgo 11): `setup-java` con `server-id: github` y secreto, y `--mount=type=secret` en la etapa de construcción. Construir la imagen localmente; el pipeline lo ejecuta el usuario al hacer push.

## Estrategia para conservar el comportamiento observable

1. **Referencias antes de migrar**: los documentos de la Fase 0 se capturan con la versión actual del servicio, no se
   reconstruyen a mano.
2. **Productor real, no JSON a mano** (trampa HU2-072): las pruebas de eventos publican con `NotificationEventPublisherPort.publish`
   y un `DomainEvent` real, y comparan contra la referencia; `notificationId` debe seguir saliendo como `{"value": ...}`.
3. **Propiedades**: la guardia SC-008 lee los metadatos de configuración de Boot 4 del classpath y falla si una clave de
   `application*.yml` está marcada como eliminada, para que un renombrado olvidado no pase en silencio.
4. **Excepciones**: cada `catch` alrededor de Jackson tiene una prueba con entrada malformada que afirma el mismo destino que
   hoy (mapa vacío, DLQ, descarte con log).
5. **Orden de campos**: si la referencia detecta diferencia por `SORT_PROPERTIES_ALPHABETICALLY`, se decide con el usuario
   entre desactivarlo en el mapeador de Boot (`spring.jackson.mapper.sort-properties-alphabetically=false`, nombre por
   verificar contra los metadatos) o aceptarlo documentado; por defecto se desactiva para mantener la salida idéntica.
6. **Dependencias de observabilidad**: no se adopta `spring-boot-starter-opentelemetry` para no activar exportación de métricas
   OTLP no existente hoy.

## Verificación por criterio de aceptación

| Criterio de la historia | Cómo se comprueba | Cuándo |
|---|---|---|
| 1. Compila y corre en Boot 4.1 / Java 25 sin Boot 3.x | `clean compile`, regla `bannedDependencies`, `dependency:tree` | Fase 2 y 4 |
| 2. Dependencias alineadas con el BOM | `dependency:tree` y registro de pines (EX/Pins); ningún `<version>` explícito sin motivo en los `pom` | Fase 1-2 |
| 3. Jackson 3 y Spring 7, sin APIs removidas | Guardia de importaciones (SC-003) + compilación con `-Xlint:deprecation` revisando `forRemoval` | Fase 0, 3, 4 |
| 4. `verify` en verde | `./mvnw -B -ntp clean verify` completo | Fase 4 |
| 5. Arquitectura | `HexagonalArchitectureTest`, `NoHardcodedChannelTest`, `ModularityTests` tras cada fase | Todas |
| 6. Contrato y comportamiento sin cambio | Prueba de contrato de operaciones + pruebas de errores existentes + `git diff` del YAML | Fase 3, 4 |
| 7. Métricas, trazas y logs | `MetricsE2ETest`, `TracingE2ETest`, `StructuredLogLayoutTest`, catálogo de referencia | Fase 3, 4 |
| 8. Arranque con el starter | Prueba de contexto completo con PEP deshabilitado y habilitado | Fase 0 (falla) y 4 (pasa) |
| 9. Imagen y pipeline | `docker build` local y arranque; pipeline del usuario tras el push | Fase 5 |
| 10. ADR y nota | Archivos en `docs/adr/` y `specs/023-migracion-spring-boot-4/` | Fase 5 |

## Riesgos

| # | Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|---|
| 1 | Propiedades renombradas con nivel `error` ignoradas en silencio (Mongo, códecs 8 MB, OTLP) | Alta (confirmada) | Alto: conexión a Mongo equivocada, adjuntos rechazados, trazas perdidas | Guardia SC-008 + pruebas de efecto real |
| 2 | `EnvironmentPostProcessor` antiguo no se ejecuta (Key Vault, OTLP) | Alta | Alto: secretos sin cargar | Interfaz nueva + `spring.factories` + prueba que falla si no corre |
| 3 | Cambio del texto/formato de error de networknt 3 (CC-01) | Confirmada | Medio: cambia `details` visible al cliente | `PathType.LEGACY` o formateador propio, idioma fijo, prueba |
| 4 | `catch (IOException)` que deja de capturar errores de Jackson | Confirmada | Alto en arranque (`ConfigurationConfig`) y en consumidores | Revisión de los 8 sitios con pruebas de entrada malformada |
| 5 | Orden alfabético de JSON de Jackson 3 | Alta | Bajo-medio | Referencias; desactivar la característica si hay diferencia |
| 6 | Pines por CVE reabren vulnerabilidades al alinear con el BOM | Media | Alto | Tabla de pines; conservar los que superen al BOM; escaneo del pipeline |
| 7 | Modulith 2.1.1 incompatible con Boot 4.1.1 (supuesto no confirmado) | Baja-media | Medio | `ModularityTests` en Fase 1; alternativa 2.1.0 / 2.0.x |
| 8 | springdoc 3.1.1 o sus propiedades (`springdoc.swagger-ui.url`) cambian el comportamiento de `/swagger-ui` | Media | Bajo | Prueba de humo del endpoint si existe; si no, verificación manual documentada |
| 9 | Pruebas de slice (`@WebFluxTest`) dejan de configurar `WebTestClient` | Media | Medio | Añadir `@AutoConfigureWebTestClient`; cubierto por las 5 pruebas |
| 10 | Dos pruebas de DLQ inestables localmente | Conocida | Bajo | Broker desechable en `5673`; deciden en CI |
| 11 | El starter vive en GitHub Packages (`maven.pkg.github.com/seguridad-uco/securitybaseline`), que exige autenticación para resolver artefactos Maven. Verificado: `ci-cd.yml` no configura `server-id`/credenciales en `setup-java` y el `Dockerfile` no pasa credenciales a `dependency:go-offline` ni a `package` (el `|| true` oculta el fallo del primero). | Alta (probable) | Alto: el pipeline y la imagen (criterio 9) no podrían resolver el starter | Tarea explícita: `settings.xml` con servidor `github` generado en CI desde un secreto con permiso `read:packages` sobre el paquete (`setup-java` con `server-id`/`server-password`) y secreto de BuildKit en el `Dockerfile`; pregunta abierta Q-A para el usuario (qué credencial usar y si el paquete es visible para este repositorio). |
| 12 | Jackson 2 y 3 coexisten y `JsonMapper` y `ObjectMapper` de Jackson 2 aparecen en el contexto | Baja | Medio | Verificar beans de mapeador en la prueba de contexto; `preferred-json-mapper` explícito si hace falta |
| 13 | Tiempo: 10 minutos por `verify` completo y ciclos de corrección | Alta | Bajo | Ejecutar por clase en el ciclo corto; `verify` en segundo plano |

## Estimación de tamaño

| Módulo | Archivos a tocar | Esfuerzo | Notas |
|---|---|---|---|
| raíz (`pom.xml`, enforcer, docs, ADR, constitución) | 1 pom + ~5 documentos | S-M | La mayor parte es decisión sobre pines y redacción |
| `utils` | 0 | XS | Solo recompilación |
| `core` | 1 (+1 prueba posible) | S | Una clase, riesgo en el formato de mensajes |
| `infrastructure` producción | ~16 (8 Jackson, 5 paquetes, 2 EPP, 2 yml, `spring.factories`) | M | Sin lógica nueva, mucho cuidado en catches y propiedades |
| `infrastructure` pruebas | ~100 (25 Jackson, 15 mock, 19 slices, 48 Testcontainers, 2 AMQP, 3 propiedades; muchos se solapan) | M-L | Cambios mecánicos; el riesgo está en las pruebas nuevas de guardia (8-10 clases) |
| Verificación (`verify`, DLQ, imagen) | n/a | M | Varias vueltas de 10 minutos |
| **Total** | | **XL** (consistente con la historia) | Calculado, no heredado: ~120 archivos con cambios mecánicos y ~10 pruebas nuevas |

## Excepciones y pendientes con dueño y fecha (Principio VII)

| Id | Descripción | Dueño | Vence |
|---|---|---|---|
| EX-01 | Jackson 2 transitivo por `jjwt-jackson` 0.13.0 y Azure SDK | Equipo del servicio | 2027-01-15 |
| PIN-01..04 | Netty, amqp-client, Jackson 2, BouncyCastle/OkHttp por encima del BOM | Equipo del servicio | Al publicarse un BOM de Boot que los iguale, revisión mensual |
| EX-02 | Dos pruebas de DLQ decididas por CI si localmente fallan por orden de ejecución | Equipo del servicio | Sin cambio respecto a hoy |

## Project Structure

### Documentation (this feature)

```text
specs/023-migracion-spring-boot-4/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── compatibilidad-observable.md
├── checklists/
│   └── requirements.md
├── cambios-de-comportamiento.md   # se crea en la implementación (Fase 5)
└── tasks.md                       # /speckit-tasks (no creado por este paso)
```

### Source Code (repository root)

```text
pom.xml
core/src/main/java/co/edu/uco/notification/core/domain/policy/ContentSchemaValidator.java
infrastructure/pom.xml
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rabbit/{AttachmentScanListener,ParametersEventListener}.java
├── adapter/out/rabbit/{AttachmentScanRequestRabbitPublisher,NotificationRabbitPublisher,NotificationUpdatesRabbitAdapter}.java
├── adapter/out/provider/FcmAccessTokenProvider.java
├── adapter/out/storage/AttachmentStorageHealthIndicator.java
├── adapter/out/antivirus/ClamAvHealthIndicator.java
└── config/{ConfigurationConfig,FcmCredentials,RabbitRetryConfig,ParametersEventsRabbitConfig,AttachmentScanRabbitConfig,
            KeyVaultEnvironmentPostProcessor,OtlpEndpointEnvironmentPostProcessor}.java
infrastructure/src/main/resources/{application.yml,application-local.yml,META-INF/spring.factories}
infrastructure/src/test/java/...   # anotaciones, Testcontainers, Jackson, propiedades y pruebas de guardia nuevas
docs/adr/{0002-spring-boot-webflux.md,0012-spring-boot-4.md}
.specify/memory/constitution.md
```

**Structure Decision**: no se crean paquetes ni puertos nuevos; los cambios viven en los adaptadores y la configuración
existentes. Las pruebas de guardia nuevas van en `infrastructure/src/test/java/.../architecture/` (importaciones prohibidas,
propiedades eliminadas) y `.../config/` (EPP, contexto con el starter), siguiendo el patrón de `HexagonalArchitectureTest` y
`NotificationServiceApplicationTests`.

## Complexity Tracking

Sin violaciones de la constitución; no aplica.

## Evidencia del spike


Copia desechable del repositorio (fuera del árbol de trabajo), con Boot 4.1.1, JDK 25.0.3, Docker y
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`, migrada solo lo mínimo para compilar y arrancar. No es la implementación.

| Hecho | Resultado |
|---|---|
| Compilación de producción tras cambiar versiones y retirar `resilience4j-spring-boot3`/`spring-retry` | Falla en 5 archivos, todos por paquetes reubicados (health y amqp) |
| Compilación de pruebas | 30 archivos por anotaciones reubicadas; 2 por API de Spring AMQP 4; 6+ por API de Jackson 3; varios `catch (IOException)` |
| `utils` | 32 pruebas en verde sin tocar código |
| `core` con networknt 3.0.8 + Jackson 3 | 694 de 714 ejecutadas en verde sin `ContentSchemaValidatorTest`; esa clase tiene 4 fallos por CC-01 (ruta y formato del mensaje, idioma de la JVM) |
| `infrastructure`, sin migrar Jackson | Contextos completos fallan con `NoSuchBeanDefinitionException: com.fasterxml.jackson.databind.ObjectMapper` |
| `infrastructure`, con Jackson 3 migrado (ejecución interrumpida en 200 de ~230 clases) | Fallos residuales: 3 pruebas de adjuntos reciben 413 en vez de 202 (el límite de 8 MB no se aplica porque `spring.codec.max-in-memory-size` ya no existe; confirma R-7), 1 prueba espera el texto `PAYLOAD_TOO_LARGE` y Spring 7 devuelve `CONTENT_TOO_LARGE` (CC-10: cambia la frase de estado HTTP 413, no el código), `ParametersEventSubscriptionE2ETest.queueDepth` lanza `ClassCastException` Long a Integer (la profundidad de cola es `long` en AMQP 4, cambio de prueba), 1 aserción de `ObservabilityE2ETest` sin diagnosticar, y 3 clases (`CorsConfig*Test`, `NotificationControllerSearchE2ETest`) que no cargan contexto por `ACCESS_REFUSED` de RabbitMQ, que parece de entorno (credenciales del broker local) y no de la migración, pero no se verificó contra la línea base |
| No ejecutado | `DeadLetterQueueE2ETest`, `RabbitRetryConfigCustomAttemptsTest`, `verify` (Spotless, SpotBugs, JaCoCo), imagen Docker, pipeline, arranque con el PEP habilitado, `ModularityTests` contra Modulith 2.1.1 (estaba entre las clases no alcanzadas o no se inspeccionó su resultado) |
