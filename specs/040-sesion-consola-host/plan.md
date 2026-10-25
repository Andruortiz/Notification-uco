# Implementation Plan: Sesion de la consola mediante el host (HU2-124)

**Spec**: [spec.md](spec.md) · **Rama**: `feature/HU2-124-sesion-consola-host`

## Resumen y recomendacion

Casi no hay trabajo de backend: el 401, la cookie y CORS con credenciales ya existen. Recomendacion: cerrar la historia
en este repositorio como contrato y documentacion mas una prueba E2E de regresion (sin codigo de produccion), y mover la
implementacion del evento al repositorio del front, bloqueada por C1b.

## Contexto tecnico

Java 25, WebFlux, Testcontainers. Sin cambios en `core`; sin codigo nuevo en `infrastructure/src/main`. Constitucion: I sin
impacto, II sin rutas nuevas, III sin comentarios, IV E2E, VII excepciones en la spec.

## Decisiones

1. No se crean endpoints de sesion ([FR-004](spec.md)); sesion y cierre son del PDP/BFF y del host.
2. El evento de 401 vive solo en el front; el servicio garantiza la forma estable del 401 (FR-001, FR-002).
3. Stream abierto: sin cierre por el servidor (excepcion VII en la spec).

## Trabajo previsto (para tasks, tras aprobacion)

- `SessionExpiryContractE2ETest` (`@SpringBootTest` RANDOM_PORT, Mongo y RabbitMQ, `WebTestClient`): sin credencial, cookie
  invalida y Bearer expirado en una ruta protegida y en el stream -> 401 con `code` y `X-Correlation-Id`, sin
  `WWW-Authenticate`; preflight con origen permitido y no permitido. Si ya hay pruebas equivalentes se reutilizan y la
  tarea cubre solo la brecha (se verifica en tasks).
- Entregar `contracts/host-mfe-session.md` al front.
- Correccion de produccion solo si la prueba revela una brecha (p. ej. `WWW-Authenticate`); se reportaria como bug.

## Cambios de contrato requeridos (`api-notificaciones.yaml`, lo edita otro agente)

- Ninguna ruta nueva. Opcional: en `Unauthorized` y en el 401 de `/notifications/stream`, indicar que el `code` estable es la
  senal para avisar al host y que el servicio no emite `WWW-Authenticate`.

## Riesgos

- Dependencia de C1b y del nombre definitivo del evento (excepcion, dueno Front, 2026-12-15).
