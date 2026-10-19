# Implementation Plan: Rutas públicas del PEP (HU2-103)

**Branch**: `feature/HU2-103-rutas-publicas-pep` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Aprobado

**Versión del plan**: 1

## Summary

Declarar en el contrato las rutas de documentación públicas, fijar `public-paths` por defecto con el mínimo de exposición,
derivar de esa propiedad las exenciones del `AuthenticationWebFilter` y blindar la coherencia con una prueba.

## Constitution Check (2.0.0)

- I Hexagonal: solo `infrastructure` (adaptador web y configuración). Cumple.
- II Contrato primero: `x-public-routes` en `api-notificaciones.yaml` antes del código. Cumple.
- III Sin comentarios. IV E2E con PEP activo y prueba de consistencia. V Commits de una línea. VII Sin excepciones.
- Consumidores RabbitMQ: no se tocan.

## Hallazgos del starter (javap, versión 0.1.3)

- Separador: coma (binding de `List<String>` del record `PepEnforcementProperties`); cada entrada debe empezar por `/`.
- Patrones: `PathPattern` de Spring sobre la ruta de la petición; coincidencia completa, no prefijo (`/openapi` no cubre
  `/openapi/x.yaml`); admite `*` y `/**`.
- No distingue método: una ruta pública lo es para todos los métodos.
- Se evalúa antes de exigir credencial; las preflight CORS pasan siempre.
- `/actuator/health` no cubre `/actuator/health/liveness`; el comportamiento real en el puerto de gestión se verifica en E2E.

## Decisiones

1. Rutas públicas siempre: `GET /openapi/api-notificaciones.yaml`, `GET /v3/api-docs`, `GET /v3/api-docs/swagger-config`
   (estructura del API sin datos). Swagger UI (`/swagger-ui.html`, `/swagger-ui/**`) solo en `local` (ver research.md).
2. `AuthenticationWebFilter` recibe `PublicRoutes` (lista de `PathPattern` de la misma propiedad, sin las `/actuator`, que
   siguen resueltas por contexto de gestión).
3. `x-public-routes` no entra en `paths`, de modo que el catálogo de recursos del PDP sigue en 16 operaciones.
4. Versión del contrato 2.0.0 se mantiene (no publicada); punto 10 del registro de cambios.

## Project Structure

- Contrato: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`
- Nuevo: `IMain/adapter/in/web/PublicRoutes`; editados `AuthenticationWebFilter`, `application.yml`, `application-local.yml`.
- Pruebas: `PublicRoutesConsistencyTest`, `PublicRoutesTest`, `PublicRoutesPepE2ETest`, `PublicRoutesLocalPepE2ETest`.
