# Research: Rutas públicas del PEP (HU2-103)

## Cómo evalúa el starter 0.1.3 `public-paths`

Inspección con `javap -c` de `PepEnforcementWebFilter` y `PepEnforcementProperties`:

- Se parsea cada entrada con `PathPatternParser` y se compara con `exchange.getRequest().getPath()` mediante
  `PathPattern.matches`: coincidencia completa (no prefijo), con `*`, `**` y `/**` finales.
- La propiedad es `List<String>` de un record: Spring la separa por comas. Cada entrada debe empezar por `/`
  (si no, el arranque falla con `Each public path must start with '/'`).
- No se considera el método HTTP.
- Las peticiones preflight CORS pasan sin evaluar.
- Con ruta no pública exige `Authorization: Bearer` único o cookie `SECURITY_BASELINE_SESSION`; si falta, 401 `TOKEN_INVALID`
  sin consultar al PEP.

## Decisión: Swagger UI

El contrato y `/v3/api-docs` describen la forma del API, no contienen datos, y los necesitan gateways, el cargador del PDP e
integradores: públicos en todos los entornos. La UI es una aplicación web con activos estáticos y un "Try it out" que no aporta
valor en producción y amplía la superficie (activos de terceros, redirecciones). Decisión: Swagger UI solo en perfiles no
productivos. Por defecto `springdoc.swagger-ui.enabled=${SWAGGER_UI_ENABLED:false}` y sus rutas no son públicas; el perfil
`local` lo habilita y las añade a `public-paths`. Para cambiarlo en otro entorno: `SWAGGER_UI_ENABLED=true` y
`SECURITY_PEP_PUBLIC_PATHS` con las rutas de la UI.

## Actuator

El puerto de gestión (8061) comparte los filtros web con el contexto principal. Una ejecución previa de
`PublicRoutesPepE2ETest` con el PEP activo mostró 401 en `/actuator/health/liveness`, `/actuator/health/readiness` y
`/actuator/prometheus` en el 8061 (solo `/actuator/health` exacto era público). Por eso los valores por defecto incluyen
`/actuator/health`, `/actuator/health/**` y `/actuator/prometheus`; el 8060 no sirve `/actuator` (404) y el
`AuthenticationWebFilter` ignora esas entradas, de modo que en el 8060 siguen exigiendo credencial.

## swagger-config

`/v3/api-docs/swagger-config` solo existe con la UI habilitada (404 sin ella, comprobado en una ejecución previa), por lo que
se trata como ruta de la interfaz y solo es pública fuera de producción.
