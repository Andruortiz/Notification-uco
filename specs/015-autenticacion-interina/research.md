# Research: Autenticación interina del servicio

**Feature**: 015-autenticacion-interina | **Date**: 2026-09-30

## Decisión 1 — Algoritmo de firma del JWT: HS256 con secreto compartido

**Decisión**: HS256 (HMAC-SHA256), secreto simétrico inyectado por variable de entorno
(`AUTH_JWT_HS256_SECRET`), nunca versionado.

**Rationale**: en este paso interino, el mismo servicio es a la vez el único verificador del token
y el único proceso de confianza capaz de acuñarlo (manualmente, fuera de banda, o desde las pruebas
automatizadas) — no existe todavía un emisor externo distinto que necesite verificar sin conocer un
secreto compartido. Un par de claves asimétrico (RS256/ES256) solo aporta valor cuando el emisor y el
verificador son partes distintas que no confían la una en la otra con el material de firma — que es
exactamente el escenario de la integración real (bloqueada) contra la plataforma de Seguridad externa,
no el de esta historia. Introducir un par de claves ahora añadiría superficie operativa (generación,
rotación, distribución de la clave privada al mecanismo de emisión de pruebas) sin reducir ningún
riesgo real dentro del alcance de HU2-096, y ese mismo material de firma quedaría descartado por
completo cuando la integración real reemplace este adaptador — contradice el principio de no
sobre-construir una pieza interina (Principio VII, aplicado en sentido inverso: tampoco se gasta
esfuerzo en una robustez que el reemplazo futuro no hereda).

**Alternatives considered**:
- **RS256 / par de claves asimétrico propio**: rechazado por lo anterior — más complejidad operativa
  (gestión de clave privada, distribución, posible necesidad de exponerla al mecanismo de pruebas) sin
  beneficio de seguridad adicional dado que emisor y verificador son la misma parte de confianza en
  este paso interino.
- **Llamar al futuro componente de Seguridad en cada request (sin JWT local)**: rechazado explícitamente
  por la historia de origen — no cumple RNF-02 (aceptación ≤200ms p95) y además esa integración sigue
  bloqueada.

**Implementación**: librería `io.jsonwebtoken` (JJWT) — `jjwt-api`, `jjwt-impl`, `jjwt-jackson` — Java
puro, sin dependencia de Spring Security. Añadida como dependencia nueva en `infrastructure/pom.xml`
únicamente (no en `core`, que sigue sin depender de ningún framework ni librería de terceros para esto
— `core` solo declara el puerto `TokenValidationPort` y los tipos de dominio).

**Caveat documentado explícitamente para aprobación del usuario**: esta decisión reemplaza uno de los
dos `[NEEDS CLARIFICATION]` dejados abiertos en `spec.md`. Se presenta aquí con su análisis de
tradeoffs, tal como lo pidió el encargo de la historia, y queda sujeta a la aprobación del plan
(Principio VI) — no es una decisión unilateral definitiva hasta que el usuario apruebe este documento.

## Decisión 2 — Mecanismo de tokens para desarrollo y pruebas

**Decisión**: una clase Java reutilizable en código de producción —
`LocalJwtTokenIssuer` (`infrastructure/src/main/java/.../adapter/out/security/local/`), sin exponerse
nunca como endpoint HTTP ni como bean condicionado por perfil. Las pruebas de contrato/E2E la
instancian directamente (`new LocalJwtTokenIssuer(secret).issue(tenantId, role, subject, ttl)`) para
acuñar tokens válidos de cualquier combinación de tenant y rol, usando el mismo secreto HS256 que el
adaptador de validación (`LocalJwtTokenValidationAdapter`) lee de la misma variable de entorno,
fijada en las propiedades de prueba (nunca un secreto real).

**Rationale**: satisface el requisito explícito de la historia ("pruebas de contrato/E2E generen
tokens válidos para distintos tenants y roles sin depender de infraestructura externa") sin añadir una
superficie HTTP nueva que necesitaría su propia protección (un endpoint que emite tokens es, por
definición, un objetivo sensible) ni un perfil Spring cuya activación accidental en producción sería
exactamente el tipo de atajo que el Principio VII prohíbe aceptar como definitivo. Al vivir como una
clase Java plana en el código de producción (sin anotaciones de framework, sin bean, sin controlador),
es trivialmente reutilizable desde cualquier test JUnit, no depende de que la aplicación esté
arrancada, y no requiere documentar una excepción de seguridad para un endpoint que solo debería
existir en desarrollo.

**Alternatives considered**:
- **Utilidad expuesta solo en perfil `local`/`test` como bean/endpoint HTTP**: rechazada — exige
  garantizar por configuración que ese perfil jamás se active en producción; una clase Java sin bean ni
  mapeo HTTP no tiene ese riesgo porque no hay nada que "desactivar", simplemente nadie la invoca fuera
  de las pruebas.
- **Tokens fijos documentados en `.env.example`**: rechazada como mecanismo único — no satisface
  "generar tokens para distintos tenants y roles" de forma abierta (un conjunto fijo de tokens
  pre-acuñados limita las combinaciones disponibles a las que alguien anticipó, y cada cambio de claims
  obligaría a regenerarlos a mano). Se conserva como complemento: `.env.example` documenta la variable
  `AUTH_JWT_HS256_SECRET` con un valor de ejemplo explícitamente marcado como solo-desarrollo, nunca un
  secreto real, para que cualquiera pueda levantar el servicio localmente y generar sus propios tokens
  con `LocalJwtTokenIssuer`.

**Caveat documentado explícitamente para aprobación del usuario**: reemplaza el segundo
`[NEEDS CLARIFICATION]` de `spec.md`, sujeto a la misma aprobación explícita del plan.

## Decisión 3 — Dónde vive la autorización por rol (401 vs. 403)

**Decisión**: todo el ciclo de autenticación (401) y autorización por rol (403) se resuelve dentro del
propio `WebFilter` (`AuthenticationWebFilter`), que escribe la respuesta directamente y corta la
cadena (`chain.filter(exchange)` nunca se invoca en el camino de rechazo). No se usa
`@RestControllerAdvice`/`@ExceptionHandler` para estos dos casos porque ese mecanismo solo intercepta
excepciones lanzadas durante el despacho a un controlador — un `WebFilter` corre antes del
`DispatcherHandler` y sus errores nunca llegan al `@RestControllerAdvice` existente
(`NotificationExceptionHandler`), que sigue intacto y sin relación con esta historia.

**Rationale**: coincide con la decisión ya tomada ("el filtro resuelve la identidad ... antes de que la
solicitud llegue al controlador") y evita que cada controlador necesite conocer el mapeo de roles por
operación — ese mapeo es, en esencia, una tabla de enrutamiento HTTP (qué rol mínimo requiere cada
combinación método+path), coherente con vivir junto al filtro en `infrastructure`, no en `core`. Los
controladores solo cambian para dejar de leer `X-Tenant-Id` y recibir en su lugar la identidad ya
resuelta.

**Mecanismo de acceso de los controladores a la identidad resuelta**: un `HandlerMethodArgumentResolver`
reactivo (`AuthenticatedPrincipalArgumentResolver`) que resuelve, por tipo, un parámetro de controlador
de tipo `AuthenticatedPrincipal` (value object nuevo en `core/domain/valueobject`, sin dependencia de
Spring) leyendo el atributo que el `AuthenticationWebFilter` deja en el `ServerWebExchange` tras validar
el token. Sustituye, uno a uno, cada `@RequestHeader("X-Tenant-Id") final String tenantId` por
`final AuthenticatedPrincipal principal` (de donde el controlador toma `principal.tenantId()`) en
`NotificationController`, `NotificationBatchController`, `AttachmentUploadController`,
`ChannelCatalogController` y `NotificationLiveUpdatesController`.

**Alternatives considered**:
- **Autorización delegada a cada controlador vía una excepción capturada por
  `NotificationExceptionHandler`**: rechazada — obligaría a cada controlador a conocer y aplicar el
  mapeo de roles explícitamente, duplicando la tabla de enrutamiento que Spring ya expresa vía
  `@RequestMapping`, y mezclaría una responsabilidad transversal con la lógica propia de cada endpoint.

## Decisión 4 — Excepciones de ruta (sin token)

**Decisión**: el filtro exime de validación las solicitudes `OPTIONS` (preflight CORS, ya resuelto por
`CorsWebFilter`) y una lista corta de rutas operativas no sensibles: `/actuator/**` (sondas de salud,
Principio IX/RNF-11) y las rutas de documentación OpenAPI que ya sirve `springdoc`
(`/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/openapi/**` donde se sirve
`api-notificaciones.yaml` estático). Ninguna ruta bajo `/notifications`, `/attachment-uploads`,
`/channels`, `/providers` ni `/recipients` queda exenta.

**Rationale**: las sondas de salud y la documentación no exponen datos de negocio ni de tenant;
exigirles un token rompería el uso estándar de esas rutas (orquestador de contenedores, navegador
humano explorando el contrato) sin ganancia de seguridad proporcional. Coincide con la práctica ya
establecida en el proyecto (`CorsConfig` ya trata `/**` de forma uniforme para CORS, y las sondas de
salud ya están pensadas para consultarse sin fricción, Principio IX).

**Orden de filtros**: `AuthenticationWebFilter` implementa `Ordered` con una precedencia posterior a
`CorsWebFilter`, de forma que una solicitud `OPTIONS` de preflight se resuelve por CORS antes de llegar
al filtro de autenticación (que además la exime explícitamente, como cinturón y tirantes).

## Decisión 5 — `GET /notifications:subscribe` (SSE) sin soporte de headers custom

**Decisión**: el token viaja como parámetro de consulta (`?access_token=<JWT>`) exclusivamente para
esta operación, documentado en el contrato OpenAPI como la única excepción a "el token viaja en el
header `Authorization`". El resto de la validación (firma, expiración, claims, rol) es idéntica a la
de cualquier otra ruta.

**Rationale**: es la opción que la propia historia ya anticipa ("token como query param con las
implicaciones de seguridad que eso trae") y es el patrón más simple compatible con `EventSource`, que
no permite headers custom. Se documenta la implicación de seguridad explícitamente: un JWT en la URL
puede quedar registrado en logs de acceso de proxies intermedios o en el historial del navegador. Se
mitiga parcialmente porque (a) el token interino tiene una expiración acotada (Decisión 1 fija un TTL
configurable, por defecto 12 h — ver `data-model.md`), y (b) el requisito de logging estructurado del
proyecto (Principio IX, RNF-10) ya prohíbe registrar el cuerpo completo de la solicitud o credenciales;
se añade explícitamente a esa regla que la query string de `GET /notifications:subscribe` no debe
registrarse en logs de acceso propios del servicio.

**Alternatives considered**:
- **Mecanismo alterno (p. ej. un token de un solo uso intercambiado antes de abrir el `EventSource`)**:
  más seguro pero añade una operación nueva (`POST /notifications:subscribe-token` o similar) y estado
  de corta vida que esta historia interina no justifica; se deja como mejora futura, no como parte de
  HU2-096.
- **No proteger este endpoint**: rechazado — viola el fail-closed general de la historia (FR-013) y
  expondría actualizaciones en vivo de notificaciones de cualquier tenant sin autenticación.

## Decisión 6 — Expiración del token (TTL)

**Decisión**: TTL configurable por variable de entorno (`AUTH_JWT_TTL_MINUTES`), con valor por defecto
de 720 minutos (12 horas) si no se configura.

**Rationale**: esta historia no construye un flujo de refresco de tokens (no está en el encargo); un
TTL demasiado corto obligaría a reacuñar tokens manualmente con mucha frecuencia durante el período
interino de uso real (mientras la integración real sigue bloqueada), mientras que un TTL demasiado
largo aumenta la ventana de riesgo si un token interino se filtra. Doce horas es un punto intermedio
razonable para un mecanismo explícitamente temporal, configurable sin cambiar código si la operación
real lo requiere distinto.

## Librerías y dependencias nuevas

- `io.jsonwebtoken:jjwt-api`, `io.jsonwebtoken:jjwt-impl` (runtime), `io.jsonwebtoken:jjwt-jackson`
  (runtime) — añadidas solo en `infrastructure/pom.xml`. Sin impacto en `core` ni en `utils`.
- Ninguna dependencia de Spring Security: el filtro es un `org.springframework.web.server.WebFilter`
  reactivo plano, registrado como `@Bean`, consistente con la decisión explícita de la historia de no
  introducir el modelo de seguridad de Spring para este paso interino.
