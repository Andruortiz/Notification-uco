# Implementation Plan: Sesion de la consola mediante el host (HU2-124)

**Spec**: [spec.md](spec.md) · **Rama**: `feature/HU2-124-sesion-consola-host`

## Resumen y recomendacion

Casi no hay trabajo de backend: el 401, la cookie y CORS con credenciales ya existen y tienen pruebas. Recomendacion: cerrar
la historia en este repositorio como contrato y documentacion, mas una unica prueba E2E que cubre la brecha real (sin codigo
de produccion). La implementacion del evento va al repositorio del front, bloqueada por C1b.

## Contexto tecnico

Java 25, WebFlux, Testcontainers. Sin cambios en `core`; sin codigo nuevo en `infrastructure/src/main`. Constitucion: I sin
impacto, II sin rutas nuevas, III sin comentarios, IV E2E, VII excepciones en la spec.

## Decisiones

1. No se crean endpoints de sesion ([FR-004](spec.md)); sesion y cierre son del PDP/BFF y del host.
2. Entrega de sesion: cookie `SECURITY_BASELINE_SESSION` como via principal; Bearer solo para desarrollo del host; si llegan
   ambos, manda el Bearer (aprobado).
3. El evento `console:session-expired` vive solo en el front, con la forma de `detail` de [contrato](contracts/host-mfe-session.md)
   (aprobado; lo valida el equipo del front). El servicio garantiza la forma estable del 401 (FR-001, FR-002).
4. Stream abierto: sin cierre por el servidor (excepcion VII en la spec, dueno Seguridad/Backend, 2026-12-15).

## Cobertura existente (main, verificada 2026-10-25)

- `CorsCredentialsTest` (filtro CORS): preflight de origen configurado recibe origen exacto y `Allow-Credentials: true`; origen
  no configurado no recibe ACAO ni ACAC; peticion simple con credenciales expone `X-Correlation-Id`; comodin falla al configurar.
  Cubre FR-003 a nivel de filtro.
- `CorsConfigTest`: GET con origen permitido recibe ACAO; origen no permitido recibe 403 sin ACAO.
- `SessionCookieE2ETest`: `GET /notifications` sin credencial da 401 `NTF-2001` sin consultar la plataforma; cookie desconocida o
  revocada en `/notifications` da 401 en menos de 2 s (sin comprobar `code`); stream sin credencial, con ticket o con cookie
  invalida da 401 (sin comprobar `code`); Bearer invalido no cae a la cookie; Bearer valido gana sobre la cookie de otro tenant.
- `ErrorCodeE2ETest`: `GET /notifications` sin credencial da 401 con `code` NTF-2001 y `correlationId` en cuerpo y log.
- `LogCorrelationE2ETest`: `GET /notifications/detail` sin credencial da 401 con cabecera `X-Correlation-Id`.
- `AuthenticationSecurityE2ETest`: token sin `exp` da 401.
- Brecha real: `code` y cabecera en el 401 del stream. El 401 por sesion revocada en `GET /notifications` solo tiene aserción de
  estado (`SessionCookieE2ETest`); no se añade prueba para él y queda como límite conocido. Bearer caducado sin prueba;
  `WWW-Authenticate` sin prueba (no aparece en `infrastructure/src`, el servicio hoy no la emite).

## Trabajo previsto (para tasks, tras aprobacion)

- `SessionExpiryContractE2ETest` (unico codigo de prueba nuevo; `@SpringBootTest` RANDOM_PORT, Mongo y RabbitMQ, `WebTestClient`),
  un metodo: stream con cookie revocada: 401, `code` NTF-2001, `X-Correlation-Id`, sin `WWW-Authenticate`. Se descarta el
  caso `GET /notifications` con cookie revocada: `SessionCookieE2ETest` ya cubre ese 401 (solo estado) y `ErrorCodeE2ETest` cubre
  el `code` del 401 en esa misma ruta sin credencial; la unica brecha de `code` es el stream.
- Entregar `contracts/host-mfe-session.md` al front.
- Correccion de produccion solo si la prueba revela una brecha (p. ej. el stream no devuelve `code` en el cuerpo); se reportaria
  como bug.

## Cambios de contrato requeridos (`api-notificaciones.yaml`, lo edita otro agente)

- Ninguna ruta nueva. Unico cambio: descripcion opcional del 401 en `Unauthorized` y en el 401 de `/notifications/stream`,
  que indique que el `code` estable es la senal para avisar al host y que el servicio no emite `WWW-Authenticate`. Esta
  historia no edita el YAML.

## Riesgos

- Dependencia de C1b y del nombre y forma definitivos del evento (excepcion, dueno Front, 2026-12-15).
- Bearer caducado sin prueba propia: queda como limite declarado hasta que tasks confirme si `TestTokens` puede emitir un token
  vencido dentro de la misma prueba.
