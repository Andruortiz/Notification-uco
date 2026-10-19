# Research: Sesión por cookie del BFF/PDP (HU2-101)

## Evidencia verificada

Todo lo de esta sección se comprobó leyendo el jar o ejecutando contra el entorno local el 2026-10-19.

### E1. Qué hace el starter del PEP 0.1.3 (jar decompilado con `javap`)

Clase `co.edu.uco.seguridad.pep.starter.PepEnforcementWebFilter`, anotada `@Order(-2147483548)`, es decir
`HIGHEST_PRECEDENCE + 100`.

1. Si la petición es preflight CORS o la ruta coincide con `publicPaths`, deja pasar sin consultar.
2. Lee `Authorization` y `Cookie`. Considera Bearer válido solo si hay exactamente una cabecera `Authorization`, empieza con
   `Bearer ` y el resto no está en blanco. Considera cookie válida si la cabecera `Cookie` contiene el texto
   `SECURITY_BASELINE_SESSION=` (búsqueda por subcadena).
3. Sin Bearer válido ni cookie: responde 401 `TOKEN_INVALID` sin consultar al PEP.
4. Genera un `X-Request-Id` (UUID) y toma `X-Correlation-Id` del cliente si cumple `[A-Za-z0-9_.:-]{1,128}`.
5. Hace `POST {pepUrl}/internal/v1/embedded-access-decisions` con cuerpo `{applicationName, environment, path (rawPath), method}`
   y cabeceras `Authorization` (si Bearer válido), `Cookie` (completa, si existe), `X-Application-Credential`, `X-Request-Id`,
   `X-Correlation-Id`.
6. Si la respuesta es 2xx: `releaseBody()` (descarta el cuerpo) y conserva solo la cabecera `X-Decision-Id`, que copia a la
   respuesta. 401 -> `TOKEN_INVALID`, 403 -> `ACCESS_DENIED`, otro estado o error de red -> 503 `PEP_UNAVAILABLE`.
7. No deja ningún atributo en el `ServerWebExchange` ni cabecera en la petición hacia el controlador.

Conclusión: el starter no expone tenant, usuario ni roles, ni siquiera de forma accidental. La opción A no es viable con
0.1.3.

### E2. Orden de filtros en este servicio

`AuthenticationWebFilter` es `HIGHEST_PRECEDENCE + 10` y `CorsWebFilter` es `HIGHEST_PRECEDENCE`. El PEP corre en `+100`, después
del nuestro. Hoy una petición con solo cookie recibe 401 de nuestro filtro y nunca llega al PEP.

### E3. Comportamiento actual con el PEP activo

`PepDelegation.isActive` (`security.enabled` y `security.pep.enforcement.enabled`) hace que `RouteAuthorizationPolicy` devuelva
`Role.CLIENTE` como rol mínimo en toda ruta, y que `PlatformJwtTokenValidationAdapter` asigne `Role.CLIENTE` si el token no trae
rol. Es decir: con el PEP activo, la autorización por ruta ya está delegada al PEP y el rol interno es siempre `CLIENTE`. Esto
resuelve la pregunta del rol para la cookie con el mismo criterio que el Bearer.

### E4. PDP local

`http://localhost:18080` responde. `GET /api/v1/session` sin cookie: 401 `application/problem+json`
(`{"detail":"Token ausente, inválido o expirado","status":401,"title":"UNAUTHORIZED","type":"urn:security-baseline:error:unauthorized","code":"UNAUTHORIZED"}`).
`/v3/api-docs`, `/swagger-ui.html` y `/api/v1/session/openapi` también dan 401. No se dispone de una cookie válida ni de
credenciales, y no se intentó iniciar sesión. Por tanto la forma de la respuesta 200 NO se pudo observar.

### E5. Forma de la sesión (verificada por el usuario el 2026-10-19)

Con una cookie válida, `GET /api/v1/session` responde (observado por el usuario en el navegador):
`{"code":"SESSION_ACTIVE","message":"Authenticated session","data":{"subject":"<uuid>","name":"","email":"<correo>","tenantId":"universidad-uco"},"timestamp":"...","requestId":"...","correlationId":"..."}`.
`name` puede venir vacío, no hay roles y el tenant efectivo es `data.tenantId`. Sin cookie responde 401. Coincide con los tipos
del frontend de referencia (`securityBaseline-vue/src/api/contracts.ts`). El cierre de sesión es `GET /api/v1/session/logout`
y el inicio es `/oauth2/authorization/keycloak` (Keycloak).

### E6. Alcance del navegador

Las cookies no se aíslan por puerto: una cookie puesta por `localhost:18080` viaja también a `localhost:5173` y `localhost:8060`.
Por eso el panel funciona hoy a través del proxy de Vite (que reescribe `/api` a `localhost:8060`) sin CORS. CORS con
credenciales solo importa si el panel llama directamente al servicio desde otro origen (otro host o puerto con
`credentials: 'include'`).

## Decisión 1: fuente de la identidad de una sesión por cookie

### Opciones

| Opción | Descripción | Viabilidad hoy | Costo | Riesgo |
|---|---|---|---|---|
| A | Tomar la identidad de lo que el PEP/PDP entrega al autorizar | No viable con 0.1.3 (E1). Requiere que seguridad publique un starter que deje la identidad en un atributo del exchange o en cabeceras | Mínimo en este servicio una vez exista; una sola llamada al PDP por petición | Dependencia externa sin fecha; contrato nuevo |
| B | El BFF reenvía un Bearer | Requiere un BFF que convierta cookie en token. El proxy de Vite no lo hace; no hay evidencia de que el PDP emita tokens para el navegador | Ninguno en el servicio (ya funciona) | Fuera de nuestro control; mueve el problema a otro equipo y a la infraestructura |
| C | El servicio consulta `GET /api/v1/session` al PDP con la cookie | Viable con lo que existe, con la forma E5, ya verificada | Un adaptador de salida y un puerto; una llamada extra por petición al PDP (además de la del PEP) | Acoplamiento a un endpoint pensado para el frontend; latencia y disponibilidad |

### Decisión: C como puente, A como objetivo

Se elige C porque es la única que se puede construir sin cambios en otros equipos. Se recomienda registrarla como excepción
temporal (Principio VII) con dueño y fecha, y pedir a seguridad la opción A (identidad expuesta por el starter), tras la cual el
adaptador de sesión se reemplaza sin tocar `core` ni los controladores, porque entra por un puerto.

### Condiciones de la decisión (se deben cumplir antes de implementar)

1. Forma de `GET /api/v1/session`: resuelta (E5, verificada por el usuario). La prueba de integración del adaptador usa un
   servidor simulado cuyo cuerpo reproduce esa respuesta (`contracts/session-response.md`).
2. Que confirmen que `/api/v1/session` es un endpoint de servicio a servicio aceptable para el backend (hoy está pensado para el
   frontend) y no requiere `X-Application-Credential` ni origen.

### Alternativas descartadas

- Decodificar la cookie localmente: es opaca y firmada por la plataforma; implicaría compartir su clave y su formato.
- Confiar en una cabecera `X-Tenant-Id` del proxy: viola FR-002 y la regla de que el tenant sale siempre de una credencial.
- Quitar nuestro filtro cuando hay cookie y dejar solo al PEP: los controladores perderían el principal y el aislamiento por
  tenant dejaría de poder garantizarse.

## Decisión 2: rol de una sesión de cookie

`Role.CLIENTE`, igual que el Bearer con el PEP activo (E3). La autorización fina por ruta y rol la hace el PEP, que recibe la
cookie completa. La cookie solo se acepta con el PEP activo (`PepDelegation.isActive`); con el PEP desactivado se rechaza con
401, porque nadie más autoriza por ruta y asignar `CLIENTE` a una sesión permitiría ver solo lo que el rol mínimo de cada ruta
ya permite, pero sin la capa que el entorno espera. Si seguridad añade roles a la sesión, el adaptador los mapea con el mapeo de
roles existente.

Pregunta abierta para seguridad: si algún día la sesión trae roles, ¿el servicio debe usarlos o seguir delegando todo al PEP?

## Decisión 3: precedencia y flujo de credenciales

Bearer (si hay cabecera `Authorization`, siempre manda y, si es inválido, 401) > cookie > ticket de suscripción (solo
`GET /notifications/stream` sin las otras dos). La detección de cookie busca la cookie por nombre exacto dentro de la cabecera,
no por subcadena, para no aceptar `XSECURITY_BASELINE_SESSION`. Se reenvía al PDP únicamente el valor de esa cookie.

## Decisión 4: caché y tolerancia de revocación

Sin caché por defecto (`cache-ttl: 0`): cada petición con cookie consulta la sesión, de modo que un cierre de sesión se refleja
al instante, a costa de una llamada extra por petición. Si seguridad acepta una tolerancia (pregunta abierta), se habilita una
caché acotada por huella SHA-256 del valor de la cookie, nunca el valor en claro, con TTL configurable. No se implementa la
caché en esta historia salvo que la tolerancia se confirme; queda como pendiente explícito.

## Decisión 5: errores

- Sesión rechazada por el PDP (401, 403 o cuerpo sin `tenantId`): 401 `AUTHENTICATION_REQUIRED`.
- PDP caído, tiempo agotado (3 s por defecto) o 5xx: 503 con el código `AUTHENTICATION_UNAVAILABLE`, nuevo, documentado en el
  contrato antes del código. No se confunde con una sesión inválida.

## Decisión 6: CORS

`allowCredentials(true)`, orígenes exactos desde `notification.cors.allowed-origins`, y arranque fallido si contiene `*`.
Métodos y cabeceras como hoy. Con `allowedHeaders("*")` y credenciales Spring refleja las cabeceras pedidas, sin comodín en la
respuesta. Se mantiene el filtro CORS en `HIGHEST_PRECEDENCE`.

## Excepción registrada (Principio VII)

| Qué | Por qué es provisional | Dueño | Fecha límite |
|---|---|---|---|
| Resolver la identidad consultando `GET /api/v1/session` al PDP | El starter 0.1.3 no expone la identidad; se sustituye por la opción A | Equipo de Seguridad (starter) con Notification-uco (adaptador) | Antes del cierre de HU2-055 (fijado el 2026-10-19) |

## Preguntas abiertas para el equipo de seguridad

1. Forma exacta y estable de `GET /api/v1/session` (200 y errores) y si el backend puede llamarlo servicio a servicio.
2. ¿Puede el starter exponer la identidad (tenant, subject, roles) tras una decisión positiva, p. ej. como atributo del exchange?
   Es la opción A y elimina la llamada extra.
3. ¿La sesión incluirá roles? ¿Se debe usar `tenantId` de la sesión como el tenant efectivo para todo el servicio?
4. Tolerancia de revocación admisible si se cachea la identidad.
5. Con el PEP activo y solo cookie, ¿`X-Application-Credential` o el origen son necesarios en la consulta de sesión?
6. Dominio y atributos de la cookie (`SameSite`, `Secure`) en entornos fuera de localhost: afectan a si el navegador la envía
   a este servicio.
