# Implementation Plan: Contrato v2 de la API con rutas literales protegibles por el PEP/PDP (HU2-098)

**Branch**: `feature/rutas-sin-dos-puntos` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/024-api-v2-rutas-literales/spec.md`

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

## Summary

Publicar la versión 2.0.0 del contrato `api-notificaciones.yaml`: las 17 operaciones pasan a rutas literales (sin `:` ni
`{}`), los identificadores van en la query string o en el cuerpo, cada operación declara `x-minimum-role` y `x-status`,
los cinco esquemas de respuesta sin `required` lo declaran, se documentan la cookie `SECURITY_BASELINE_SESSION` y los
errores 401/403/503 del PEP con `X-Decision-Id`, y se deja un registro de cambios con la tabla v1 -> v2. El código se
ajusta al contrato (dos controladores con variable de ruta, `Location` de la subida, `RouteAuthorizationPolicy`) y cinco
pruebas de guardia convierten los criterios en comprobaciones automáticas, entre ellas la derivación de la lista canónica
de recursos para HU2-099. Corte directo, sin alias y sin prefijo `/v2`.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (WebFlux)

**Primary Dependencies**: Spring WebFlux, SnakeYAML (ya usado por `ErrorResponseContractTest`), Jackson 3, JUnit 5,
WebTestClient. Sin dependencias nuevas.

**Storage**: N/A

**Testing**: JUnit 5, WebTestClient, `@SpringBootTest(RANDOM_PORT)` + Testcontainers para el E2E, ArchUnit existente

**Target Platform**: Servicio detrás del PEP (starter 0.1.3) y del PDP 0.1.6

**Project Type**: Servicio hexagonal; el cambio vive en `infrastructure` (adaptadores REST y contrato). `core` no cambia.

**Performance Goals**: N/A (sin cambio de comportamiento)

**Constraints**: Rutas aptas para `ResourcePath` del PDP; cero comentarios en código nuevo; estados y cuerpos sin cambio

**Scale/Scope**: 17 operaciones, 16 rutas -> 16 rutas literales (+1 por separar `/recipients/preferences` GET/PUT en una
ruta); 2 controladores con `@PathVariable`, 1 `Location`, 1 política de rutas, ~23 clases de prueba con llamadas a rutas
v1, README y 4 documentos de `docs/`.

## Dimensionamiento con datos del código (verificado)

- Controladores con variable de ruta: `NotificationController.getStatus` (`@GetMapping("/{id}")`) y
  `AttachmentUploadController.get` (`@GetMapping("/{uploadId}")`). No hay otros `@PathVariable` en `infrastructure`.
- `Location` con ruta v1: `AttachmentUploadController` línea 55 (`"/attachment-uploads/" + uploadId`).
- `RouteAuthorizationPolicy`: 17 reglas; 6 con `matches` de regex (3 de las cuales apuntan a operaciones no implementadas:
  `:retry`, `:register` x2, `:updatePreferences`, preferencias).
- Pruebas con rutas v1: 32 llamadas `uri("/notifications/{id}", ...)` en 18 clases, ~8 referencias a
  `/attachment-uploads/<id>` y concatenaciones `"/notifications/" + id` (p. ej. `AttachmentUploadE2ETest:737`,
  `NotificationAttachmentE2ETest:597`), más `RouteAuthorizationPolicyTest` y `AuthenticationWebFilterTest`.
- Sin cambios pendientes de adaptar en `core`, `utils` ni en la mensajería.
- Documentación con rutas v1: README (ya corregido en el árbol sin commitear para stream), `docs/recomendaciones-api-frontend.html`
  (líneas 81-101 y 234-252), `docs/diagramas/03-contenedores-c4.md`, `04-secuencia-envio-despacho.md`,
  `docs/diagramas/secuencias/*.drawio` (F01, F03, F04, F05) y su README; `.specify/memory/constitution.md` (Principio II).
  `.archify/` es salida generada y no se edita.
- `http.server.requests` etiqueta `uri` con la plantilla; no hay referencias a `/notifications/{id}` en configuración de
  tableros del repositorio (grep sin coincidencias fuera de contrato, pruebas y docs), pero los tableros externos deben
  avisarse.
- `NotificationExceptionHandler` ya traduce `ResponseStatusException` (la que lanza WebFlux por parámetro faltante) a
  `ErrorResponse`; se comprueba con prueba, no se asume.

## Constitution Check

| Principio | Estado | Nota |
|---|---|---|
| I Hexagonal | Cumple | Solo cambia `infrastructure/adapter/in`. `core` intacto; `HexagonalArchitectureTest` y `ModularityTests` siguen en verde. |
| II Contract-first | Requiere enmienda | Hoy prescribe `recurso:accion` y `GET /notifications/{id}`. Esta historia lo contradice por diseño; se enmienda (T-01) antes del cambio de contrato. Versión 1.2.2 -> 2.0.0 por redefinir un principio de forma incompatible (regla de Governance). El contrato se edita primero (T-03..T-05) y el código después. |
| III Sin comentarios | Cumple | Sin comentarios nuevos. `x-` en YAML lleva `description`, no comentarios `#`. |
| IV Calidad verificada | Cumple | T-20 es el E2E explícito; 5 guardias nuevas; `verify` completo al final. |
| V Commits | Cumple | Una línea, `tipo(ámbito): descripción`, sin atribución. Los 20 archivos sin commitear del usuario se commitean aparte por él, antes. |
| VI Aprobación | Pendiente | Este plan está en `Pendiente`; no se genera `tasks.md` hasta que el usuario lo marque `Aceptado`. |
| VII Sin atajos | Cumple | Excepciones abiertas se registran en "Excepciones" con dueño y fecha. |
| RabbitMQ ack/DLQ | N/A | No se tocan consumidores. |

## Decisiones de diseño

1. **Corte directo, sin alias ni `/v2`.** Hoy no hay clientes en producción; un alias dejaría rutas con `{}` servidas que el
   PEP no puede proteger, exigiría eximirlas de la guardia (c) y duplicaría el mantenimiento de pruebas. El prefijo `/v2`
   obligaría a registrar en el PDP el doble de recursos y a reescribir la política de rutas sin beneficio mientras no haya
   dos versiones vivas. La versión viaja en `info.version`. Si en el futuro hay clientes externos, la v3 sí justificará
   coexistencia.
2. **Se respetan los cuatro nombres ya cambiados** por el usuario (`/notifications/batches`, `/notifications/stream`,
   `/notifications/stream/tickets`, `/attachment-upload-completions`). Su trabajo sin commitear es la base del plan.
3. **Reintento como recurso**: `POST /notification-retries` con `{notificationId}`.
4. **Operaciones sin implementar**: se conservan con `x-status: planned` (ver Q4 del spec). La prueba (a) compara solo las
   `available` con el código y exige que ninguna `planned` esté servida; la lista canónica incluye ambas con su estado
   para que HU2-099 elija.
5. **`PUT /recipients/preferences`** reemplaza al `POST ...:updatePreferences`: la operación sustituye las preferencias del
   destinatario (idempotente). Es un cambio de verbo en una operación `planned`, sin impacto en código.
6. **`x-minimum-role` en el contrato como fuente única**; `RouteAuthorizationPolicy` se reescribe como tabla de
   (método, ruta literal) -> rol, eliminando las expresiones regulares, y una prueba la compara con el contrato.
7. **Esquema `PepErrorResponse` separado de `ErrorResponse`.** `ErrorResponseContractTest` exige hoy que todo error 4xx/5xx
   referencie `ErrorResponse`; se ajusta para aceptar `PepErrorResponse` solo en 401/403/503 y como alternativa
   (`oneOf`) en 401/403, porque el modo interino devuelve `NTF-2001`/`NTF-2002`.
8. **`nullable: true` (sintaxis 3.0) en un contrato 3.1.0** (19 ocurrencias): se migra a `type: [..., "null"]` en los cinco
   esquemas que se tocan para que el generador de tipos no los ignore; el resto de esquemas queda para una historia aparte
   salvo que la prueba (e) muestre un campo nulo no declarado. Registrado como alcance mínimo, no como atajo: ver
   Excepciones.

## Tabla definitiva de rutas v1 -> v2

Rol = rol mínimo (jerarquía CLIENTE < OPERADOR < ADMINISTRADOR). "Código" = existe controlador en la rama. Detalle y lista
canónica en [contracts/rutas-v1-v2.md](contracts/rutas-v1-v2.md).

| # | Método | Ruta v1 (`main`) | Ruta v2 | Identificador pasa a | Rol | Estado | Código |
|---|---|---|---|---|---|---|---|
| 1 | POST | `/notifications` | `/notifications` | sin cambio | CLIENTE | available | sí |
| 2 | GET | `/notifications` | `/notifications` | sin cambio (query) | OPERADOR | available | sí |
| 3 | POST | `/notifications:sendBatch` | `/notifications/batches` | n/a | CLIENTE | available | sí (WIP del usuario) |
| 4 | GET | `/notifications/{id}` | `/notifications/detail?notificationId=` | query | CLIENTE | available | sí, por cambiar |
| 5 | POST | `/notifications/{id}:retry` | `/notification-retries` | cuerpo `{notificationId}` | OPERADOR | planned | no |
| 6 | GET | `/notifications:subscribe` | `/notifications/stream` | n/a | CLIENTE | available | sí (WIP) |
| 7 | POST | `/notifications:subscribeTicket` | `/notifications/stream/tickets` | n/a | CLIENTE | available | sí (WIP) |
| 8 | POST | `/attachment-uploads` | `/attachment-uploads` | sin cambio | CLIENTE | available | sí |
| 9 | POST | `/attachment-uploads/{uploadId}:complete` | `/attachment-upload-completions` | cuerpo `{uploadId}` | CLIENTE | available | sí (WIP) |
| 10 | GET | `/attachment-uploads/{uploadId}` | `/attachment-uploads/status?uploadId=` | query | CLIENTE | available | sí, por cambiar |
| 11 | GET | `/channels` | `/channels` | sin cambio | CLIENTE | available | sí |
| 12 | GET | `/providers` | `/providers` | sin cambio | CLIENTE | available | sí |
| 13 | GET | `/configuration` | `/configuration` | sin cambio | ADMINISTRADOR | available | sí |
| 14 | POST | `/channels:register` | `/channels` | cuerpo | ADMINISTRADOR | planned | no |
| 15 | POST | `/providers:register` | `/providers` | cuerpo | ADMINISTRADOR | planned | no |
| 16 | GET | `/recipients/{recipientId}/preferences` | `/recipients/preferences?recipientId=` | query | CLIENTE | planned | no |
| 17 | POST | `/recipients/{recipientId}:updatePreferences` | PUT `/recipients/preferences?recipientId=` | query + cuerpo | CLIENTE | planned | no |

Resultado: 12 available, 5 planned; 17 operaciones sobre 14 rutas literales distintas (`/notifications`, `/channels`,
`/providers`, `/attachment-uploads` y `/recipients/preferences` se comparten entre métodos).

## Orden de cambio

1. **Enmienda de la constitución** (Principio II) y commit del WIP del usuario (ajeno a esta historia; lo hace él).
2. **Contrato primero**: reescribir rutas, parámetros y cuerpos; `x-status`, `x-minimum-role`; `required`; seguridad;
   `info.version` 2.0.0; registro de cambios. Las pruebas de guardia (c) y la lista canónica (d) se escriben aquí y
   fallan por la razón correcta contra el código aún no adaptado (a, b).
3. **Código**: `NotificationController.getStatus` y `AttachmentUploadController.get` pasan a `@RequestParam`, rutas
   `/notifications/detail` y `/attachment-uploads/status`; `Location`; `RouteAuthorizationPolicy` por tabla.
4. **Pruebas existentes**: migrar las llamadas a rutas v1 (≈45 sitios en ≈23 clases); extender `ErrorResponseContractTest`.
5. **Documentación**: README, `docs/`, diagramas.
6. **Puerta completa**: `./mvnw -B -ntp verify`.

## Cómo se deriva la lista canónica para el PDP

- Fuente única: `paths` + `x-minimum-role` + `x-status` del YAML.
- Una clase de prueba de soporte `ApiContract` (en `infrastructure/src/test`, SnakeYAML, mismo cargador que
  `ErrorResponseContractTest`) expone `operations()` con (método, ruta, rol, estado, operationId).
- La prueba `ProtectedResourcesCatalogTest` valida la lista (sin duplicados, rutas aptas para `ResourcePath`, perfiles
  acumulativos) y escribe `infrastructure/target/contract/protected-resources.json` con
  `{method, path, minimumRole, profiles[], status, operationId}`; CI puede publicarlo como artefacto. HU2-099 decide si
  consume ese JSON o parsea el YAML (que se sirve en `/openapi/api-notificaciones.yaml`).
- Perfiles acumulativos: CLIENTE = rutas con rol CLIENTE; OPERADOR = CLIENTE + OPERADOR; ADMINISTRADOR = todas. Se calculan,
  no se mantienen a mano.
- La tabla inicial legible está en [contracts/rutas-v1-v2.md](contracts/rutas-v1-v2.md).

## Cómo se verifican los criterios

| Criterio | Prueba | Nivel |
|---|---|---|
| SC-001, FR-001 | `ApiContractLiteralPathsTest` (c): ninguna clave con `:`, `{`, `}`, `*`, `//`, espacio; cumple la regex de `ResourcePath`. Control positivo: una ruta sintética con `:` hace fallar el validador. | unitaria sobre YAML |
| SC-002, FR-003, FR-004 | `ApiContractMatchesControllersTest` (a): arranca el contexto, lee `RequestMappingHandlerMapping` y compara (método, ruta) con las `available`; ninguna `planned` servida; ninguna ruta v1 servida (404 por WebTestClient en las dos rutas v1 con variable). | integración de contexto |
| FR-008, SC-005 | `RouteAuthorizationPolicyContractTest` (b): para cada operación del contrato, `minimumRoleFor` coincide; ruta desconocida -> ADMINISTRADOR. | unitaria |
| FR-007, SC-005 | `ProtectedResourcesCatalogTest` (d). | unitaria |
| FR-009, SC-004 | `ResponseSchemasConformanceE2ETest` (e): ejecuta el flujo real (enviar, detalle, búsqueda, lote, subida, catálogo, configuración) y valida cada cuerpo contra el esquema del contrato (`required`, `nullable`, enums). Para evitar JSON armado a mano, las respuestas salen del servicio real. | E2E |
| FR-010, FR-011, SC-003 | `SecurityDocumentationContractTest`: esquema `SessionCookie`, 401/403/503 y `X-Decision-Id` en las 17 operaciones. | unitaria sobre YAML |
| FR-012 | `ChangelogContractTest`: `info.version` = 2.0.0 y el changelog menciona las 17 rutas v1 y v2. | unitaria |
| FR-002, FR-005, FR-015, US1.3-5 | E2E del flujo completo `ApiV2RoutesE2ETest`: enviar -> consultar detalle por query -> subir adjunto, verificar `Location`, `status`, completar por cuerpo; 400 por parámetro faltante; 404 en ruta v1; `recipientId` con codificación porcentual; dos tenants distintos no cruzan datos por el identificador en la query. | E2E |
| FR-013, FR-014, SC-007 | Revisión + `DocsRoutesGuardTest` (grep controlado de README y documentos vigentes de `docs/` en busca de `:accion` y `{`). | unitaria |
| SC-006 | `./mvnw -B -ntp verify` completo y `HexagonalArchitectureTest`/`ModularityTests`. | puerta |

## Estimación por tarea (borrador; el desglose definitivo lo genera `/speckit-tasks` tras la aprobación)

Tamaño: S <= 1 h, M 1-3 h, L 3-6 h.

| Id | Tarea | Tamaño |
|---|---|---|
| T-01 | Enmendar Principio II de la constitución (2.0.0) + Sync Impact Report | S |
| T-02 | `ApiContract` (cargador/modelo de operaciones) + guardia (c) + guardia de coherencia vacía fallando (rojo) | M |
| T-03 | Contrato: rutas v2, parámetros de query, cuerpos de reintento/registro/preferencias, `x-status`, `x-minimum-role` | L |
| T-04 | Contrato: `required` en 5 esquemas, `nullable` -> `type: [.., "null"]` en esos esquemas (verificar contra los records) | M |
| T-05 | Contrato: `SessionCookie`, `PepErrorResponse`, respuestas 401/403/503 + `X-Decision-Id` en 17 operaciones; ajustar `ErrorResponseContractTest` | M |
| T-06 | Contrato: `info.version` 2.0.0 + registro de cambios (`contracts/` y sección en el YAML) | S |
| T-07 | Leer el jar del starter 0.1.3 y fijar el cuerpo exacto de los errores del PEP; corregir `PepErrorResponse` si difiere | S |
| T-08 | Prueba (a) `ApiContractMatchesControllersTest` (rojo) | M |
| T-09 | Prueba (b) `RouteAuthorizationPolicyContractTest` (rojo) | S |
| T-10 | `NotificationController.getStatus` -> `/notifications/detail?notificationId=` | S |
| T-11 | `AttachmentUploadController.get` -> `/attachment-uploads/status?uploadId=` y `Location` | S |
| T-12 | `RouteAuthorizationPolicy` por tabla literal (sin regex) | S |
| T-13 | Migrar pruebas con rutas v1 (≈45 sitios, ≈23 clases, mecánico) | L |
| T-14 | Prueba (d) `ProtectedResourcesCatalogTest` + artefacto JSON | M |
| T-15 | Prueba (e) `ResponseSchemasConformanceE2ETest` | L |
| T-16 | E2E `ApiV2RoutesE2ETest` (flujo, 400, 404 v1, codificación, dos tenants, `Location`) | L |
| T-17 | README y `docs/` (HTML, md, 4 drawio, README de secuencias) con rutas v2 + `DocsRoutesGuardTest` | M |
| T-18 | `spotless:apply`, arquitectura (`HexagonalArchitectureTest`, `ModularityTests`) | S |
| T-19 | `./mvnw -B -ntp verify` completo (≈10 min; en segundo plano) y corrección de lo que falle | M |
| T-20 | Cierre: verificar tareas, desviaciones y excepciones; avisar a frontends y a quien mantiene tableros por `uri` | S |

Total aproximado: 5 L + 8 M + 7 S ≈ 25-35 h de trabajo efectivo. La parte mecánica (T-13) y el E2E de conformidad (T-15)
concentran el riesgo de tiempo.

## Riesgos

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| El WIP sin commitear del usuario entra en conflicto con la implementación | Alta si no se commitea antes | Medio | Pedirle commitearlo antes de `/speckit-implement`; las tareas parten de su estado. |
| El cuerpo real de los errores del PEP difiere del esquema `PepErrorResponse` | Media | Bajo | T-07 lo fija leyendo el jar; no se inventa. |
| Marcar `required` un campo que a veces falta rompe a los clientes generados | Media | Alto | La prueba (e) valida respuestas reales; un campo con duda se deja opcional y se registra. |
| Tableros o alertas con la etiqueta `uri="/notifications/{id}"` dejan de coincidir | Media | Medio | Avisar en T-20; la plantilla pasa a `/notifications/detail`. |
| Identificadores en query aparecen en logs de acceso de proxies | Baja | Bajo | Los IDs (UUID/uploadId/recipientId) no son secretos; el reintento y la finalización van por cuerpo. `recipientId` puede ser dato personal: revisar con seguridad si lo es. |
| El PDP rechaza algún carácter de las rutas elegidas (`-`) | Baja | Medio | Evidencia indica que `-` es válido; HU2-099 lo comprueba con un registro real. |
| `Sinks`/SSE: `GET /notifications/stream` conserva comportamiento | Baja | Bajo | Ya cubierto por `NotificationLiveUpdates*E2ETest` migradas. |
| Frontends con tipos generados rompen | Cierta | Medio | Es el propósito; changelog con tabla vieja -> nueva (T-06). |

## Preguntas abiertas para el usuario (con la recomendación ya asumida)

1. Parámetro del detalle: `notificationId` (asumido) en lugar de `id`. Recomendado por coherencia con las respuestas.
2. Reintento: `POST /notification-retries` + cuerpo (asumido) frente a `POST /notifications/retry?id=`.
3. Operaciones sin código: `x-status: planned` en el contrato (asumido) frente a retirarlas.
4. Corte directo, sin alias ni `/v2` (asumido).
5. Enmienda del Principio II a constitución 2.0.0 dentro de esta historia (asumido). Alternativa: enmienda en una historia
   previa mínima; no recomendada porque el contrato y la regla deben cambiar juntos.
6. `PUT` en lugar de `POST` para actualizar preferencias (asumido).
7. ¿Se commitea el WIP del usuario antes de `tasks`? Imprescindible.
8. Migración `nullable` 3.0 -> 3.1 solo en los cinco esquemas tocados (asumido), con el resto como excepción.

## Excepciones (Principio VII)

| Excepción | Motivo | Dueño | Fecha límite |
|---|---|---|---|
| `nullable: true` (sintaxis 3.0) permanece en esquemas distintos de los cinco tocados | Alcance de la historia; no afecta a la literalidad ni a `required` | Equipo de desarrollo | 2026-11-30 (historia de higiene del contrato) |
| El filtro de autenticación no acepta la cookie `SECURITY_BASELINE_SESSION` | Código de HU2-101, fuera de alcance | HU2-101 | Antes de HU2-055 |
| Las 5 operaciones `planned` no tienen código | Fuera de alcance (HU2-027, HU2-044/045, HU2-029) | Cada historia | Según roadmap |

## Project Structure

### Documentation (this feature)

```text
specs/024-api-v2-rutas-literales/
├── spec.md
├── plan.md
├── research.md
├── quickstart.md
├── checklists/requirements.md
└── contracts/rutas-v1-v2.md
```

### Source Code (lo que se tocará)

```text
infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/NotificationController.java
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/rest/AttachmentUploadController.java
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/in/web/RouteAuthorizationPolicy.java
infrastructure/src/test/java/co/edu/uco/notification/infrastructure/contract/   (ApiContract y pruebas nuevas)
infrastructure/src/test/java/.../adapter/in/rest/*                              (migración de rutas)
.specify/memory/constitution.md, README.md, docs/**
```

**Structure Decision**: sin código en `core`. Las pruebas de contrato viven en un paquete `contract` de pruebas de
`infrastructure`, junto a `ErrorResponseContractTest`, y reutilizan su patrón de carga del YAML.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Enmienda MAJOR de la constitución dentro de la historia | El Principio II prescribe lo que el PDP no puede proteger | Dejar la regla vigente y contradecirla en el contrato incumpliría el Principio VII |
