# Implementation Plan: CORS para el dashboard de frontend

**Branch**: `feature/HU2-071-cors-frontend` | **Date**: 2026-09-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-cors-configuration/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Configurar CORS en el backend (WebFlux) para que el dashboard de frontend (`Front-Notification`, repositorio separado, `http://localhost:5173` en desarrollo) pueda llamar la API REST directamente desde el navegador. Un bean `CorsWebFilter` con la lista de orígenes permitidos externalizada por propiedad (`notification.cors.allowed-origins`, default `http://localhost:5173`) — sin dependencia nueva, `spring-boot-starter-webflux` ya trae el soporte de CORS reactivo.

## Technical Context

**Language/Version**: Java 21 (ADR-0001)

**Primary Dependencies**: Spring Boot 3 / WebFlux — `CorsWebFilter`/`CorsConfiguration` de `spring-web`, ya en el classpath; sin dependencia nueva

**Storage**: N/A — esta historia no toca persistencia

**Testing**: JUnit 5, `WebTestClient` contra el contexto reactivo real (no mock) para confirmar las cabeceras CORS en la respuesta

**Target Platform**: igual que el resto del componente (Kubernetes)

**Project Type**: Microservicio hexagonal — historia puramente de `infrastructure` (configuración transversal de la capa de entrada HTTP); `core` no se toca ni se entera de que existe

**Performance Goals**: ninguno nuevo — un filtro que evalúa el origen de la petición, costo despreciable frente al resto del pipeline

**Constraints**: sin dependencia nueva; la lista de orígenes permitidos debe ser configurable sin recompilar (FR-002)

**Scale/Scope**: cubre toda la superficie de la API de forma transversal (FR-005), no ruta por ruta

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principio I (Arquitectura hexagonal)**: cumple — el filtro CORS vive 100% en `infrastructure/config`; `core` no gana ninguna dependencia ni se modifica.
- **Principio II (Contract-first)**: no aplica — esta historia no define ni modifica ningún endpoint del contrato de negocio; CORS es configuración de transporte sobre endpoints que ya existen o existirán, no un contrato en sí mismo. No se genera `contracts/`.
- **Principio III (Cero comentarios)**: cumple — código sin comentarios explicativos.
- **Principio IV (Calidad verificada)**: requiere prueba E2E bloqueante — con `WebTestClient` contra el contexto real, confirmar que una petición `OPTIONS`/`GET` desde un origen permitido recibe las cabeceras `Access-Control-Allow-*` esperadas, y que un origen no permitido no las recibe (SC-001, SC-002). Cobertura ≥80 %/≥70 % para `CorsConfig`.
- **Principio V (Trazabilidad git)**: rama `feature/HU2-071-cors-frontend` (HU2-071, renumerado 2026-09-13 desde el HU2-136 original), creada desde `develop`, commits de una sola línea.
- **Principio VI (spec-kit)**: spec ya aprobado (checklist 12/12); este plan se aprueba antes de generar `tasks.md`.
- **Principio VII (Sin atajos)**: no aplica ningún atajo — la configuración cubre toda la API desde el inicio (FR-005), no se deja para después.
- **Restricciones técnicas**: no aplica bloqueo optimista/idempotencia (no hay persistencia); no aplica la estrategia de caché local (esta historia no introduce ningún dato semi-estático que cachear — es configuración de arranque, leída una vez).

**Resultado del gate**: PASS. No hay violaciones que requieran justificación — se omite Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/003-cors-configuration/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── quickstart.md         # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No se genera `data-model.md` — esta historia no introduce ninguna entidad de dominio. No se genera `contracts/` — no expone ni modifica ningún contrato de API (FR-005 es transversal, no un endpoint).

### Source Code (repository root)

```text
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/
└── CorsConfig.java                    # nuevo — @Configuration, bean CorsWebFilter

infrastructure/src/main/resources/
└── application.yml                    # modificado — notification.cors.allowed-origins

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/
└── CorsConfigTest.java                # nuevo — E2E con WebTestClient contra el contexto real
```

**Structure Decision**: Toda la historia vive en `infrastructure/config/` — es configuración transversal pura, consistente con que `core` no se toca. No hay adaptador de entrada/salida nuevo, ni cambio de contrato.

## Complexity Tracking

> Sin violaciones del Constitution Check — no aplica.
