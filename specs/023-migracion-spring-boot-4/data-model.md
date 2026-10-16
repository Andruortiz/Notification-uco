# Data Model: catálogos de la migración (HU2-106)

La migración no cambia ningún dato persistido ni ningún esquema de MongoDB. Este documento define los catálogos de
trabajo que la implementación mantiene como artefactos versionados en `specs/023-migracion-spring-boot-4/` y que alimentan
la nota de cambios de comportamiento (FR-015, FR-016).

## Sobrescritura de versión (pin)

| Campo | Descripción |
|---|---|
| `artefacto` | Coordenadas `grupo:artefacto` o propiedad del BOM sobrescrita |
| `versionBom` | Versión que gestiona el BOM de Boot 4.1.1 |
| `versionFijada` | Versión que fija el repositorio |
| `motivo` | CVE o incompatibilidad que lo justifica (enlace al PR de origen) |
| `dueño` | Persona o equipo responsable |
| `retiroCuando` | Condición verificable de retiro (por ejemplo "BOM de Boot >= versionFijada") |

Pines iniciales: Netty 4.2.19.Final, amqp-client 5.37.0, Jackson 2 2.22.3, BouncyCastle 1.86, OkHttp 4.12.0 (ver research R-4).
Regla: un pin cuyo `versionBom` sea mayor o igual a `versionFijada` se retira.

## Cambio de comportamiento

| Campo | Descripción |
|---|---|
| `id` | `CC-NN` |
| `área` | API, RabbitMQ, métricas, trazas, logs, arranque, configuración, pruebas |
| `antes` / `después` | Comportamiento observable en cada versión |
| `causa` | Cambio de Boot/Spring/Jackson/librería que lo produce |
| `detectadoPor` | Prueba automatizada que lo detecta |
| `resolución` | Conservado igual / aceptado con aprobación / excepción con dueño y fecha |

Cambios ya identificados en la investigación (se confirman o descartan al implementar):

| id | Área | Resumen | Causa |
|---|---|---|---|
| CC-01 | API | El mensaje de `InvalidContentException` (detalle del 422) pierde la ruta `attachments[N]`, la sustituye por `/attachments/N/...` y sale en el idioma de la JVM | networknt 3.x |
| CC-02 | Configuración | `spring.data.mongodb.uri` pasa a `spring.mongodb.uri`; la antigua se ignora | Boot 4 |
| CC-03 | Configuración | `spring.codec.max-in-memory-size` pasa a `spring.http.codecs.max-in-memory-size` | Boot 4 |
| CC-04 | Trazas | `management.otlp.tracing.endpoint` pasa a `management.opentelemetry.tracing.export.otlp.endpoint` | Boot 4 |
| CC-05 | Arranque | `EnvironmentPostProcessor` antiguo (`org.springframework.boot.env`, obsoleto para retirada) no se registra bajo la clave nueva | Boot 4 |
| CC-06 | RabbitMQ/API | Orden alfabético de propiedades en JSON por defecto | Jackson 3 |
| CC-07 | Flujo | Los `catch (IOException)` alrededor de Jackson dejan de capturar (excepciones no verificadas) | Jackson 3 |
| CC-08 | Contexto | No existe bean `com.fasterxml.jackson.databind.ObjectMapper` | Boot 4 |
| CC-09 | Métricas | `management.endpoint.prometheus.enabled` desaparece | Boot 4 |

## Excepción registrada (Principio VII)

| Campo | Descripción |
|---|---|
| `id` | `EX-NN` |
| `requisito` | FR/SC al que se desvía |
| `descripción` | Qué queda distinto y por qué |
| `dueño` | Responsable |
| `vence` | Fecha de revisión |

Excepción inicial prevista: **EX-01** Jackson 2 transitivo por `jjwt-jackson` 0.13.0 y Azure SDK (FR-003/FR-001), dueño: equipo
del servicio, vence 2027-01-15.

Hallazgos adicionales del spike: **CC-10** la frase de estado de HTTP 413 pasa de "Payload Too Large" a "Content Too Large"
(Spring Framework 7; el código 413 no cambia); **CC-11** `QueueInformation.getMessageCount()` devuelve `long`.
