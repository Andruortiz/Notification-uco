# Implementation Plan: Sesión por cookie del BFF/PDP y CORS con credenciales (HU2-101)

**Branch**: `feature/HU2-101-sesion-cookie` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/025-sesion-cookie-bff/spec.md`

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

## Summary

Aceptar la cookie `SECURITY_BASELINE_SESSION` además del Bearer y permitir CORS con credenciales. Decisión (ver
[research.md](research.md)): la identidad de una sesión de cookie se obtiene consultando `GET /api/v1/session` al PDP desde un
adaptador de salida detrás de un puerto de `core` (opción C), como puente documentado como excepción hasta que el starter del
PEP exponga la identidad (opción A). La opción B queda descartada por depender de un BFF que no existe. El rol de la sesión es
`CLIENTE` y la cookie solo se acepta con el PEP activo, igual que el Bearer. Dos puntos dependen de seguridad y quedan como
preguntas abiertas: la forma real de la respuesta de sesión y la tolerancia de revocación.

## Technical Context

**Language/Version**: Java 25 (Spring Boot 4.1, tras HU2-106)

**Primary Dependencies**: Spring WebFlux (`WebClient`), Reactor, `security-pep-integration-spring-boot-starter` 0.1.3, Jackson 3

**Storage**: N/A (sin estado nuevo; caché diferida)

**Testing**: JUnit 5, Mockito, `StepVerifier`, servidor HTTP simulado para el PDP en la prueba de integración del adaptador,
Testcontainers (Mongo, RabbitMQ) en la E2E, ArchUnit y Modulith existentes

**Target Platform**: servicio WebFlux en el puerto 8060

**Project Type**: microservicio hexagonal (`core`, `infrastructure`, `utils`)

**Performance Goals**: rechazo de sesión inválida < 2 s; indisponibilidad del PDP responde 503 en < 5 s (SC-004)

**Constraints**: sin bloqueo de hilos; el valor de la cookie nunca se registra; el tenant solo sale de la sesión verificada

**Scale/Scope**: 1 puerto, 1 adaptador, 1 filtro modificado, 1 configuración CORS, contrato y README

## Constitution Check

Constitución 2.0.0.

| Principio | Evaluación |
|---|---|
| I Hexagonal | Cumple: `SessionIdentityPort` y `SessionCredential` en `core` sin Spring; el adaptador HTTP en `infrastructure`. `HexagonalArchitectureTest` debe seguir verde |
| II Contract-first | Cumple: se edita `api-notificaciones.yaml` (descripción de `SessionCookie`, seguridad global, respuesta 503 `AUTHENTICATION_UNAVAILABLE`) antes del código. No hay rutas nuevas, todas siguen literales |
| III Sin comentarios | Cumple: ningún comentario explicativo en código nuevo |
| IV Calidad | Cumple: E2E con cookie y dos tenants, pruebas de unidad e integración, aserciones de `Duration` para SC-004, control positivo en las pruebas de "no cruza nada" |
| V Git | Commits de una línea, sin trailer |
| VI Aprobación | Plan en `Pendiente`; no se pasa a implementar sin aprobación del usuario |
| VII Sin atajos | La opción C es provisional: se registra como excepción con dueño y fecha (research.md). La caché sin tolerancia confirmada no se implementa |
| VIII/IX | No aplica / se conserva el log de rechazo con `correlationId` y sin el valor de la cookie |

No hay violaciones que justificar.

## Diseño

### core (sin framework)

- `domain/valueobject/SessionCredential`: record con el valor de la cookie; `toString` no lo revela; rechaza vacío.
- `port/out/SessionIdentityPort`: `Mono<AuthenticatedPrincipal> resolve(SessionCredential credential)`. Error de sesión inválida
  como `InvalidTokenException` (motivo nuevo `INVALID_SESSION` si hace falta un motivo categórico) y de indisponibilidad como
  `AuthenticationUnavailableException` nueva.

### infrastructure

- `adapter/out/security/session/PdpSessionIdentityAdapter`: `WebClient` contra `notification.auth.session.url`, `GET
  /api/v1/session` reenviando solo `Cookie: SECURITY_BASELINE_SESSION=<valor>` y `X-Correlation-Id`; tiempo límite configurable
  (3 s); traduce 200 a `AuthenticatedPrincipal(subject, TenantId(tenantId), Role.CLIENTE)`; 401, 403 y cuerpo sin `subject` o
  `tenantId` a sesión inválida; error de red, tiempo agotado y 5xx a indisponible. Condicionado a `PepDelegation.isActive`.
- `AuthenticationWebFilter`: tras el chequeo de Bearer, si no hay `Authorization` y hay cookie (por nombre exacto) usa el puerto;
  si no hay ninguna y es el stream usa el ticket. Rechaza la cookie con 401 si el PEP no está activo. Nuevo
  `RejectionReason` para sesión inválida e indisponible y respuesta 503 `AUTHENTICATION_UNAVAILABLE`.
- `CorsConfig`: `allowCredentials(true)`; `CorsOriginsGuard` falla al arrancar si `allowed-origins` contiene `*`.
- `application.yml`: `notification.auth.session.{url,path,timeout}` con variables de entorno; sin secretos.
- `api-notificaciones.yaml` y README: descripción de la cookie, precedencia, errores 401/503.

### Source Code

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/valueobject/SessionCredential.java
├── exception/AuthenticationUnavailableException.java
└── port/out/SessionIdentityPort.java
core/src/test/java/co/edu/uco/notification/core/domain/valueobject/SessionCredentialTest.java

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/web/AuthenticationWebFilter.java            (modificado)
├── adapter/out/security/session/PdpSessionIdentityAdapter.java
├── config/AuthSessionProperties.java
├── config/CorsConfig.java                                 (modificado)
└── config/CorsOriginsGuard.java
infrastructure/src/main/resources/application.yml           (modificado)
infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml (modificado)
infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── adapter/out/security/session/PdpSessionIdentityAdapterTest.java
├── adapter/in/web/AuthenticationWebFilterSessionTest.java
├── config/CorsCredentialsTest.java
├── contract/SessionCookieContractTest.java
└── adapter/in/rest/SessionCookieE2ETest.java
```

**Structure Decision**: puerto en `core`, adaptador HTTP y filtro en `infrastructure`, como el resto de la autenticación
(`TokenValidationPort` y `PlatformJwtTokenValidationAdapter`).

## Riesgos

1. La forma de `GET /api/v1/session` no está verificada (research E4, E5). Si difiere, solo cambia el adaptador y su doble de
   prueba, pero el E2E podría pasar contra un doble incorrecto. Mitigación: contrato del doble en `contracts/session-response.md`
   y verificación manual con una cookie real antes de cerrar la historia.
2. Doble consulta al PDP por petición (nuestra sesión y la decisión del PEP): más latencia y más carga; la caché se difiere.
3. Acoplamiento a un endpoint pensado para el frontend. Mitigación: puerto y excepción temporal registrada.
4. Revocación: sin caché es inmediata; con caché habría una ventana. Pregunta abierta.
5. El flujo con ticket de suscripción convive con la cookie: la precedencia está definida y probada.
6. CORS con credenciales mal configurado (comodín) abriría la API a cualquier origen: guardia de arranque y prueba negativa.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Nuevo puerto `SessionIdentityPort` aparte de `TokenValidationPort` | La credencial es opaca y se resuelve por consulta remota, no por verificación de firma | Reutilizar `TokenValidationPort` mezclaría un token firmado con una cookie opaca y dificultaría reemplazarlo por la opción A |
