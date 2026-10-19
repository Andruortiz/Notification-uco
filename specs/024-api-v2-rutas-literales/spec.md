# Feature Specification: Contrato v2 de la API con rutas literales protegibles por el PEP/PDP (HU2-098)

**Feature Branch**: `feature/rutas-sin-dos-puntos` (rama de trabajo ya activa; el nombre HU2-098 aplica a la historia)

**Created**: 2026-10-18

**Status**: Borrador (specify y clarify completos; pendiente de aprobación del plan)

**Input**: User description: "Como equipo de desarrollo, quiero una versión 2 del contrato de la API cuyas rutas sean todas
literales (sin ':' ni '{}'), con respuestas completamente tipadas y los errores de seguridad documentados, para poder
registrar cada ruta como recurso protegido en el PDP y generar clientes tipados sin sorpresas."

Trazabilidad: HU2-098 (esta historia), HU2-087 (campos `required` en las respuestas, absorbida), HU2-101 y HU2-103 (solo la
parte de contrato: cookie de sesión y errores de seguridad documentados), HU2-055 (autenticar contra el componente de
seguridad; esta historia es su prerrequisito), HU2-099 (carga de recursos en el PDP; consume la lista canónica que esta
historia produce), HU2-106 (migración a Spring Boot 4.1, ya integrada). Principios involucrados: II (contrato primero;
esta historia lo enmienda), III (sin comentarios), IV (calidad verificada), V (commits), VI (aprobación), VII (sin atajos).

## Contexto y evidencia

El starter `security-pep-integration-spring-boot-starter` 0.1.3 envía al PEP, por cada solicitud,
`POST /internal/v1/embedded-access-decisions` con `applicationName`, `environment`, `method` y `path` igual a
`URI.getRawPath()` de la solicitud real (literal, sin query string). Reenvía `Authorization: Bearer`, la cookie
`SECURITY_BASELINE_SESSION`, `X-Application-Credential`, `X-Request-Id` y `X-Correlation-Id`. Sus respuestas internas son
`TOKEN_INVALID`, `ACCESS_DENIED` y `PEP_UNAVAILABLE`, con la cabecera `X-Decision-Id`.

El PDP 0.1.6 resuelve el recurso por igualdad exacta (`applicationId` + `path` + `method`). No hay plantillas ni
comodines. `ResourcePath` solo admite `/`, letras, dígitos y `. _ ~ { } -`, debe iniciar con `/` y no admite espacios,
barras dobles, query ni fragment. Rechaza `:` y `*` con 400 y registrar una ruta con `{}` produce un 500
(`IllegalArgumentException`). Consecuencia: solo se pueden proteger rutas literales; los identificadores viajan en la
query string o en el cuerpo.

Estado del contrato en `main` (f29e3e7): 16 rutas y 17 operaciones; 6 son literales, 4 usan `:accion` (`:subscribe`,
`:subscribeTicket`, `:sendBatch`, `/attachment-uploads/{uploadId}:complete`), 7 usan `{}` o `:accion` con identificador
(`/notifications/{id}`, `/notifications/{id}:retry`, `/attachment-uploads/{uploadId}`, `/channels:register`,
`/providers:register`, `/recipients/{recipientId}/preferences`, `/recipients/{recipientId}:updatePreferences`). Cinco
operaciones están documentadas y no implementadas (reintento manual, registrar canal, registrar proveedor, leer y
actualizar preferencias). El contrato solo declara Bearer, no la cookie de sesión; no documenta los errores del PEP; y
cinco esquemas de respuesta no declaran `required`.

Hay trabajo del usuario sin commitear en la rama que ya renombra cuatro rutas (`/notifications/batches`,
`/notifications/stream`, `/notifications/stream/tickets`, `/attachment-upload-completions`). Esta historia lo adopta tal
cual como parte del contrato v2 (Q3) y debe commitearse antes de implementar.

## Clarifications

### Session 2026-10-18

Las preguntas no pudieron hacerse al usuario; se asumió la recomendación y quedan pendientes de confirmación al aprobar
el plan (ver "Preguntas abiertas" en `plan.md`).

- Q1: ¿Nombres de lo que falta por renombrar? -> `GET /notifications/detail?notificationId=`,
  `GET /attachment-uploads/status?uploadId=`, `POST /notification-retries` con cuerpo `{notificationId}`,
  `POST /channels`, `POST /providers`, `GET /recipients/preferences?recipientId=`,
  `PUT /recipients/preferences?recipientId=`. Única desviación frente a la propuesta: el parámetro del detalle se llama
  `notificationId` (no `id`) para que coincida con el campo `notificationId` de las respuestas y del cuerpo del reintento.
- Q2: ¿Reintento como recurso (`POST /notification-retries`) o `POST /notifications/retry?id=`? -> Recurso
  `/notification-retries` con cuerpo: es la misma forma que el usuario eligió para `/attachment-upload-completions` y
  deja el identificador fuera de la URL (no aparece en logs de acceso ni en la etiqueta `uri` de las métricas).
- Q3: ¿Se respetan los nombres que el usuario ya renombró? -> Sí, tal cual.
- Q4: ¿Operaciones documentadas y no implementadas? -> Se conservan en el contrato con la extensión `x-status: planned`
  y su ruta literal v2; las implementadas llevan `x-status: available`. Una prueba exige que lo `available` sea
  exactamente lo que sirven los controladores y que nada `planned` esté servido. Retirarlas haría que los frontends
  perdieran los tipos que ya esperan y que HU2-099 no pudiera registrar el recurso antes de que exista el código.
- Q5: ¿Alias temporales de las rutas viejas o corte directo? -> Corte directo, sin alias y sin prefijo `/v2`. No hay
  clientes en producción; un alias dejaría rutas no literales servidas que el PEP no podría proteger y obligaría a eximir
  la guardia (c). La versión se expresa en `info.version: 2.0.0`.
- Q6: ¿Cómo se declara el rol mínimo? -> Cada operación lleva `x-minimum-role` (`CLIENTE`, `OPERADOR`,
  `ADMINISTRADOR`). Es la fuente de la que se deriva la lista canónica y contra la que se verifica
  `RouteAuthorizationPolicy`.
- Q7: ¿Qué forma tienen los errores del PEP en el contrato? -> Un esquema propio `PepErrorResponse` con `code`
  (`TOKEN_INVALID`, `ACCESS_DENIED`, `PEP_UNAVAILABLE`), distinto de `ErrorResponse` (catálogo `NTF-`). Las respuestas 401
  y 403 documentan ambas formas porque, con el PEP deshabilitado (modo interino), el servicio sigue devolviendo
  `NTF-2001`/`NTF-2002`. El cuerpo exacto que emite el starter se confirma leyendo su jar durante la implementación
  (tarea de verificación); si difiere, se corrige el esquema, no el starter.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Todas las rutas del contrato son literales y coinciden con el código (Priority: P1)

El equipo abre `api-notificaciones.yaml` v2 y cada ruta es una cadena fija, registrable tal cual como recurso en el PDP. Lo
que el contrato declara es exactamente lo que el servicio sirve.

**Why this priority**: Es el objetivo de la historia y lo que desbloquea HU2-055 y HU2-099. Sin rutas literales el PDP
no puede protegerlas.

**Independent Test**: Una prueba compara el conjunto (método, ruta) del contrato con el de los controladores y otra
falla si alguna ruta contiene `:` o `{`.

**Acceptance Scenarios**:

1. **Given** el contrato v2, **When** se recorre cada clave de `paths`, **Then** ninguna contiene `:`, `{`, `}`, `*`, `//`,
   espacios, query ni fragment, y todas cumplen la expresión de `ResourcePath` del PDP.
2. **Given** las operaciones con `x-status: available`, **When** se compara su conjunto (método, ruta) con los mapeos de
   los controladores, **Then** son idénticos; ninguna operación `planned` está servida.
3. **Given** una ruta v1 con variable (`/notifications/{id}`, `/attachment-uploads/{uploadId}`), **When** se invoca con el
   identificador en la ruta, **Then** responde 404, y la consulta con el identificador en la query string responde igual
   que antes (mismo cuerpo, mismos estados).
4. **Given** un `POST /attachment-uploads` aceptado, **When** se lee la cabecera `Location`, **Then** apunta a la ruta
   literal v2 (`/attachment-uploads/status?uploadId=...`).
5. **Given** una consulta sin el parámetro obligatorio (`notificationId`, `uploadId`, `recipientId`), **When** se invoca,
   **Then** responde 400 con `ErrorResponse` y `correlationId`.

---

### User Story 2 - Hay una lista canónica de recursos protegidos derivada del contrato (Priority: P1)

El equipo que carga recursos en el PDP (HU2-099) obtiene, sin transcribir nada a mano, la lista de (método, ruta, rol
mínimo, perfil) de todo lo que debe registrarse.

**Why this priority**: Sin una fuente única, el contrato, `RouteAuthorizationPolicy` y el PDP divergen en silencio.

**Independent Test**: Una prueba deriva la lista del contrato, la compara con `RouteAuthorizationPolicy` y la escribe como
artefacto de build.

**Acceptance Scenarios**:

1. **Given** el contrato v2, **When** se deriva la lista, **Then** tiene una entrada por operación (17 en esta versión),
   sin duplicados de (método, ruta), con rol mínimo y estado en cada una.
2. **Given** cada entrada de la lista, **When** se consulta `RouteAuthorizationPolicy` en modo interino, **Then**
   devuelve el mismo rol mínimo que el contrato.
3. **Given** los tres perfiles acumulativos del PDP (CLIENTE, OPERADOR, ADMINISTRADOR), **When** se expanden los roles,
   **Then** el perfil OPERADOR incluye todo lo de CLIENTE y el ADMINISTRADOR todo lo de OPERADOR, calculado desde la lista.

---

### User Story 3 - Las respuestas están completamente tipadas (Priority: P1)

Un generador de clientes produce tipos sin campos opcionales por omisión en las respuestas que el servicio siempre llena.

**Why this priority**: Absorbe HU2-087; sin `required` los frontends reciben `T | undefined` en todo.

**Independent Test**: Una prueba toma respuestas reales de cada operación disponible y comprueba que todo campo `required`
del esquema está presente y que ningún campo obligatorio llega nulo si el esquema no lo declara `nullable`.

**Acceptance Scenarios**:

1. **Given** `SendNotificationResponse`, `NotificationStatusResponse`, `BatchItemResult`, `NotificationHistoryItem` y
   `DeliveryAttempt`, **When** se inspeccionan, **Then** cada uno declara `required` con los campos que el código
   siempre llena; los que pueden ser nulos están marcados `nullable` y no dependen de su ausencia.
2. **Given** una respuesta real de cada operación disponible, **When** se valida contra su esquema, **Then** todos los
   campos `required` están presentes.

---

### User Story 4 - Los errores de seguridad y la cookie de sesión están documentados (Priority: P2)

Quien consume el contrato conoce, para cada operación protegida, qué devuelve el PEP cuando rechaza o no responde.

**Why this priority**: Es parte de contrato de HU2-101 y HU2-103; no cambia el comportamiento del servicio hoy.

**Independent Test**: Una prueba recorre todas las operaciones y exige 401, 403 y 503 documentados con `X-Decision-Id`.

**Acceptance Scenarios**:

1. **Given** el contrato, **When** se inspecciona `components.securitySchemes`, **Then** declara `BearerAuth` y
   `SessionCookie` (`apiKey`, `in: cookie`, `name: SECURITY_BASELINE_SESSION`), y la seguridad global admite cualquiera
   de los dos.
2. **Given** cada operación protegida, **When** se inspeccionan sus respuestas, **Then** documenta 401 (`TOKEN_INVALID`),
   403 (`ACCESS_DENIED`) y 503 (`PEP_UNAVAILABLE`), y las tres declaran la cabecera `X-Decision-Id`.
3. **Given** que el filtro de autenticación aún no acepta la cookie (código de HU2-101), **When** se lee la descripción
   del esquema, **Then** indica que la cookie la valida el PEP y que el modo interino solo acepta Bearer.

---

### User Story 5 - Los clientes tienen una guía exacta de migración (Priority: P2)

Los equipos de frontend reciben el registro de cambios con la tabla ruta vieja -> ruta nueva y qué cambió en cada
operación.

**Why this priority**: El cambio es incompatible y los frontends generan tipos desde el contrato.

**Independent Test**: Una prueba verifica que el registro de cambios lista cada ruta de la tabla v1 -> v2 y que cada ruta
nueva existe en el contrato.

**Acceptance Scenarios**:

1. **Given** el contrato `info.version` 2.0.0, **When** se abre el registro de cambios, **Then** contiene la tabla de las
   17 operaciones (método, ruta v1, ruta v2, cambio de identificador y de verbo).
2. **Given** README y documentos de `docs/` que citan rutas v1, **When** se busca `:` de acción o `{`, **Then** ya no hay
   coincidencias en documentación vigente (los artefactos históricos de `specs/001..023` no se tocan).

### Edge Cases

- Una ruta v1 invocada tras el corte (`GET /notifications/{id}`) cae en 404 (o 405 si coincide una ruta literal con otro
  método); no hay redirección. Con el PEP activo, el PDP la deniega por no ser un recurso registrado.
- `GET /notifications/detail` sin parámetro o con un valor que no es UUID: 400 `ErrorResponse`, igual que hoy con un id
  mal formado.
- `GET /notifications/stream` y `GET /notifications/detail` comparten prefijo; al no haber variable de ruta no existe
  ambigüedad de resolución.
- Un identificador con caracteres reservados en la query (`recipientId` con `&`, `%`, espacios) debe codificarse; la
  prueba incluye un caso con codificación porcentual.
- La métrica `http.server.requests` etiqueta `uri` con la plantilla de la ruta: pasa de `/notifications/{id}` a
  `/notifications/detail`; tableros o alertas que filtren por la plantilla anterior deben revisarse.
- `RouteAuthorizationPolicy` en modo delegado al PEP devuelve CLIENTE para todo; la coherencia (b) se verifica en modo
  interino.
- Las operaciones `planned` no deben servirse ni siquiera con 501; si aparece un controlador para una de ellas sin quitar
  `x-status: planned`, la prueba (a) falla.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Toda clave de `paths` del contrato v2 DEBE ser literal: solo `/`, letras, dígitos y `. _ ~ -`, iniciando con
  `/`, sin `:`, `{`, `}`, `*`, `//`, espacios, query ni fragment.
- **FR-002**: Los identificadores de recurso que hoy viajan en la ruta DEBEN pasar a la query string (consultas) o al cuerpo
  (comandos), según la tabla definitiva de `plan.md`.
- **FR-003**: El servicio DEBE servir exactamente las operaciones `x-status: available` y ninguna operación `planned`.
- **FR-004**: El servicio NO DEBE servir ninguna ruta v1 con `:` o variable (corte directo, sin alias).
- **FR-005**: `Location` de `POST /attachment-uploads` DEBE apuntar a la ruta literal v2.
- **FR-006**: Cada operación DEBE declarar `x-minimum-role` y `x-status`.
- **FR-007**: DEBE existir una derivación automatizada de la lista canónica (método, ruta, rol mínimo, estado, perfil
  acumulativo) a partir del contrato, disponible como artefacto de build para HU2-099.
- **FR-008**: `RouteAuthorizationPolicy` DEBE devolver, en modo interino, el rol mínimo declarado en el contrato para cada
  operación; las rutas no declaradas conservan el valor por defecto ADMINISTRADOR.
- **FR-009**: Los esquemas `SendNotificationResponse`, `NotificationStatusResponse`, `BatchItemResult`,
  `NotificationHistoryItem` y `DeliveryAttempt` DEBEN declarar `required` con los campos que el código siempre llena.
- **FR-010**: El contrato DEBE declarar el esquema de seguridad de cookie `SECURITY_BASELINE_SESSION` y aceptar Bearer o
  cookie a nivel global.
- **FR-011**: Toda operación protegida DEBE documentar 401 `TOKEN_INVALID`, 403 `ACCESS_DENIED` y 503 `PEP_UNAVAILABLE`,
  con la cabecera `X-Decision-Id`.
- **FR-012**: `info.version` DEBE ser `2.0.0` y DEBE existir un registro de cambios con la tabla v1 -> v2 y las
  incompatibilidades.
- **FR-013**: La constitución DEBE enmendarse (Principio II): el patrón `recurso:accion` se sustituye por rutas literales
  con identificadores en query o cuerpo; la nueva versión se registra con Sync Impact Report.
- **FR-014**: README y documentos vigentes de `docs/` DEBEN citar solo rutas v2.
- **FR-015**: Los códigos de estado, cuerpos y semántica de cada operación disponible NO DEBEN cambiar, salvo el medio por
  el que viaja el identificador.

### Key Entities

- **Operación del contrato**: método + ruta literal + rol mínimo + estado (`available`/`planned`).
- **Recurso protegido (canónico)**: proyección de una operación para el PDP: método, ruta, rol mínimo, perfil acumulativo,
  estado.
- **Registro de cambios**: tabla de migración v1 -> v2 por operación.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % de las claves de `paths` pasan la guardia de literalidad (0 con `:`, `{` o `}`); lo verifica una
  prueba automatizada.
- **SC-002**: El conjunto (método, ruta) de operaciones `available` es idéntico al de los controladores; la prueba falla
  si diverge en cualquier sentido.
- **SC-003**: 17 de 17 operaciones tienen `x-minimum-role`, `x-status` y 401/403/503 documentados; verificado por prueba.
- **SC-004**: 5 de 5 esquemas de respuesta nombrados declaran `required`, y las respuestas reales de las 12 operaciones
  disponibles los cumplen; verificado por prueba contra respuestas de los flujos E2E.
- **SC-005**: La lista canónica se genera en cada build, tiene 17 entradas y el rol coincide con
  `RouteAuthorizationPolicy` en todas.
- **SC-006**: `./mvnw -B -ntp verify` en verde con cobertura >= 80 % líneas y >= 70 % ramas, y `HexagonalArchitectureTest`
  y `ModularityTests` sin cambios.
- **SC-007**: 0 coincidencias de rutas v1 con `:` de acción o variable en README y en documentos vigentes de `docs/`.

## Assumptions

- No hay clientes en producción; el corte directo no afecta a nadie fuera del equipo y de los frontends en desarrollo.
- El rol mínimo de cada ruta no cambia con respecto a `RouteAuthorizationPolicy` actual.
- El PDP admite `-` en `ResourcePath` (confirmado por la evidencia), por lo que `/attachment-upload-completions` y
  `/notification-retries` son registrables.
- El cuerpo exacto de las respuestas internas del starter se confirma en implementación (Q7).

## Exclusiones

- Implementar reintento manual, registro de canal/proveedor y preferencias (HU2-027, HU2-044/045, HU2-029).
- Aceptar la cookie en el filtro de autenticación (código de HU2-101).
- Cargar los recursos en el PDP (HU2-099).
- Autenticar contra el componente de seguridad (HU2-055).
