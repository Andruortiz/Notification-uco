# Implementation Plan: Autenticación interina del servicio (puerto y adaptador local, preparación de HU2-055)

**Branch**: `feature/HU2-096-autenticacion-interina` | **Date**: 2026-09-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/015-autenticacion-interina/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

<!--
  Este bloque lo edita el usuario directamente en el archivo para aprobar el plan (Pendiente ->
  Aceptado) o para marcar una revisión (incrementar Versión del plan). Ningún agente infiere ni
  declara aprobación en ningún otro lugar del documento; la aprobación es el valor de este campo,
  editado por el usuario o, bajo su instrucción directa y explícita en el chat, por la sesión
  principal -- nunca por un agente en segundo plano citando un mensaje de otra sesión como fuente de
  autorización (Principio VI).
-->

## Summary

HU2-096 reemplaza el header `X-Tenant-Id` (leído sin validar) por un JWT interino (`Authorization:
Bearer <JWT>`), firmado por este mismo servicio con HS256 (research Decisión 1), validado por un nuevo
puerto de salida `TokenValidationPort` que invoca un `WebFilter` reactivo (`AuthenticationWebFilter`,
sin puerto de entrada propio). El filtro resuelve tenant y rol antes del controlador, aplica fail-closed
(401) y el mapeo de roles jerárquico `ADMINISTRADOR ⊇ OPERADOR ⊇ CLIENTE` (403 si el rol no alcanza,
research Decisión 3) directamente sobre una tabla de enrutamiento propia de infraestructura, sin que
`core` ni los casos de uso existentes cambien de contrato. Los controladores REST existentes dejan de
leer `@RequestHeader("X-Tenant-Id")` y reciben un `AuthenticatedPrincipal` ya resuelto vía un
`HandlerMethodArgumentResolver` nuevo. Tokens de prueba se generan con una clase Java reutilizable
(`LocalJwtTokenIssuer`, research Decisión 2), sin exponer ningún endpoint de emisión. El contrato
OpenAPI se actualiza primero (Principio II) para declarar `BearerAuth`, retirar el parámetro
`X-Tenant-Id`, y documentar `401`/`403` y la excepción de `GET /notifications:subscribe` (token por
query param, research Decisión 5). No se implementa la integración real contra la plataforma de
Seguridad externa (PEP/PDP/OPA, DEP-01) — sigue bloqueada y fuera de alcance.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor. Nuevo: `io.jsonwebtoken` (JJWT) —
`jjwt-api`, `jjwt-impl`, `jjwt-jackson` — solo en `infrastructure/pom.xml`. Sin Spring Security.

**Storage**: N/A — sin persistencia nueva.

**Testing**: JUnit 5 + Mockito + `StepVerifier` en `core` (`RoleTest`, `AuthenticatedPrincipalTest`);
prueba de adaptador (`LocalJwtTokenValidationAdapterTest` — firma válida/inválida, expiración, claims
faltantes, usando `LocalJwtTokenIssuer` real para producir los tokens de prueba, nunca JSON armado a
mano); prueba del filtro (`AuthenticationWebFilterTest` con `WebTestClient` contra un
`@SpringBootTest` mínimo o un `ApplicationContextRunner`, verificando 401/403/200 y que los
controladores existentes siguen aceptando con un token válido); E2E
(`AuthenticationInterinaE2ETest`, `@SpringBootTest(RANDOM_PORT)` + Testcontainers Mongo/RabbitMQ +
`WebTestClient`) cubriendo fail-closed, resolución de tenant desde el token (no desde el header),
autorización por rol, y el caso SSE con `access_token` en query. Arquitectura:
`HexagonalArchitectureTest`/`ModularityTests` deben seguir en verde (el filtro y el adaptador viven en
`infrastructure`, el puerto y los value objects en `core`, sin dependencia inversa).

**Target Platform**: mismo contenedor, N réplicas. La validación es local (sin llamada de red por
request, RNF-02) y sin estado compartido entre réplicas — cada una valida con el mismo secreto
configurado por variable de entorno.

**Project Type**: hexagonal — puerto de salida nuevo en `core` (`TokenValidationPort`), value objects
nuevos en `core` (`Role`, `AuthenticatedPrincipal`), excepción nueva en `core`
(`InvalidTokenException`); adaptador, filtro, resolver de argumentos y tabla de autorización nuevos en
`infrastructure`; cinco controladores REST existentes modificados solo en su firma de entrada de
tenant.

**Performance Goals**: SC-004 — la aceptación de una notificación individual sigue en ≤200ms p95 de
extremo a extremo, incluida la validación del token (validación local HS256, sin llamada de red,
research Decisión 1 — elegida explícitamente por este motivo).

**Constraints**: fail-closed sin excepción (FR-013); `core` no depende de JJWT ni de ningún tipo de
Spring Security; contract-first (Principio II) — el YAML se edita antes que el filtro/controladores;
cero comentarios explicativos en código nuevo (Principio III).

**Scale/Scope**: cinco controladores REST existentes tocados en su firma; un filtro nuevo; sin cambio
de escala de datos.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Arquitectura hexagonal | Cumple: `TokenValidationPort` vive en `core/port/out`, sin tipos de JJWT ni de Spring filtrando a `core`. El adaptador (`LocalJwtTokenValidationAdapter`) y el filtro viven en `infrastructure`. Verificado por `HexagonalArchitectureTest` (sin cambios al test, debe seguir pasando). |
| II. Contract-first | Cumple: `contracts/api-notificaciones-cambios.md` documenta el cambio de `api-notificaciones.yaml` (seguridad `BearerAuth`, retiro de `X-Tenant-Id`, `401`/`403`) que se aplica antes que el código, como tarea explícita previa a tocar los controladores. |
| III. Cero comentarios explicativos | Cumple por diseño: ningún código nuevo planeado lleva comentarios `//`; el razonamiento de las seis decisiones de este paso vive en `research.md`. |
| IV. Calidad verificada | Pendiente de ejecución en `tasks.md`/`implement`: cobertura ≥80/≥70, `verify` completo, y una prueba E2E explícita (`AuthenticationInterinaE2ETest`) que cubra fail-closed, resolución de tenant y autorización por rol — no solo pruebas unitarias del filtro. |
| V. Trazabilidad en git | Cumple por convención ya seguida en esta sesión: commits de una línea, artefactos de spec-kit en la misma rama. |
| VI. Spec-kit gobernado | Este plan se detiene aquí para aprobación explícita del usuario antes de `tasks`/`implement`, incluyendo las dos decisiones de research que resuelven los `[NEEDS CLARIFICATION]` del spec. |
| VII. Sin atajos | La naturaleza interina del mecanismo (HS256, sin refresco de tokens, sin revocación) está documentada explícitamente como limitación conocida en `spec.md` (Edge Cases) y `research.md`, no oculta. No se introduce una "solución temporal" sin documentar sus límites. |
| VIII. Confiabilidad y durabilidad | No aplica cambio: esta historia no toca el ciclo de vida de una notificación aceptada, solo quién puede aceptar/consultarla. |
| IX. Observabilidad y trazabilidad | Cumple: research Decisión 5 documenta explícitamente que la query string de `GET /notifications:subscribe` (que puede llevar el token) no se registra en logs de acceso propios; ningún log nuevo incluye el token ni el secreto. |
| Restricciones técnicas — RNF-13/RNF-14 (autenticación/aislamiento vía Componente de Seguridad) | La constitución ya anticipa este momento: "mientras DEP-01 siga bloqueado, el placeholder X-Tenant-Id es aceptable en desarrollo, nunca en producción". Esta historia va más allá de lo mínimo exigido — retira el placeholder inseguro antes de que DEP-01 cierre, con un mecanismo interino verificable, en vez de esperar. No contradice la constitución; la adelanta. |
| Restricciones técnicas — caché de claves de validación de token, local por réplica | No aplica en este paso: el secreto HS256 es configuración estática por variable de entorno, no una clave remota que requiera caché con invalidación por evento. Esa restricción de la constitución queda relevante para la integración real (JWKS remoto), fuera de alcance aquí; se anota para no perderla de vista quien implemente HU2-055. |

Sin violaciones que requieran `Complexity Tracking`.

## Project Structure

### Documentation (this feature)

```text
specs/015-autenticacion-interina/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── api-notificaciones-cambios.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/valueobject/
│   ├── Role.java                        # nuevo
│   └── AuthenticatedPrincipal.java      # nuevo
├── exception/
│   └── InvalidTokenException.java       # nuevo
└── port/out/
    └── TokenValidationPort.java         # nuevo

core/src/test/java/co/edu/uco/notification/core/
├── domain/valueobject/RoleTest.java                   # nuevo
└── domain/valueobject/AuthenticatedPrincipalTest.java # nuevo

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/out/security/local/
│   ├── LocalJwtTokenValidationAdapter.java  # nuevo, implements TokenValidationPort
│   └── LocalJwtTokenIssuer.java             # nuevo, reutilizado desde tests
├── adapter/in/web/
│   ├── AuthenticationWebFilter.java             # nuevo
│   ├── RouteAuthorizationPolicy.java            # nuevo
│   └── AuthenticatedPrincipalArgumentResolver.java  # nuevo
├── config/
│   └── WebFluxConfig.java (o equivalente ya existente)  # registra el argument resolver
└── adapter/in/rest/
    ├── NotificationController.java            # modificado: firma de tenant
    ├── NotificationBatchController.java       # modificado: firma de tenant
    ├── AttachmentUploadController.java        # modificado: firma de tenant
    ├── ChannelCatalogController.java          # modificado: firma de tenant
    └── NotificationLiveUpdatesController.java # modificado: firma de tenant + token por query param

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── adapter/out/security/local/LocalJwtTokenValidationAdapterTest.java  # nuevo
├── adapter/in/web/AuthenticationWebFilterTest.java                     # nuevo
└── e2e/AuthenticationInterinaE2ETest.java                              # nuevo
```

**Structure Decision**: sigue la estructura hexagonal ya existente del repositorio — puerto y value
objects nuevos en `core` (sin dependencia de JJWT ni Spring), adaptador/filtro/resolver nuevos en
`infrastructure` bajo `adapter/out/security/local` y `adapter/in/web`, y los cinco controladores REST
ya existentes modificados solo en la forma de recibir la identidad (sin cambiar su lógica de negocio).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Sin violaciones — tabla omitida.
