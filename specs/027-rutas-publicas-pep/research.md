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

El puerto de gestión (8061) comparte los filtros web con el contexto principal; el resultado del PEP sobre `/actuator/**`
en ese puerto se verifica en `PublicRoutesPepE2ETest` y determina si las sondas y `prometheus` deben figurar en `public-paths`
(el puerto 8060 no sirve `/actuator`, así que declararlas allí públicas solo produce 404).
