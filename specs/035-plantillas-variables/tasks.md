# Tasks: Plantillas con variables por canal e idioma (HU2-119)

**Entrada**: [spec.md](spec.md), [plan.md](plan.md). El contrato ya esta publicado en `api-notificaciones.yaml` (no se edita aqui).
Orden: dominio, casos de uso, adaptadores, seguridad, pruebas, cierre. Wiring en `TemplateConfig` propia (no en `UseCaseConfig`).

## Fase 1: Dominio (core)

- [ ] T001 `ErrorCode`: 1007, 1008, 1009, 1010 y 3050 a 3053 con los numeros del contrato (utils).
- [ ] T002 `domain/template`: `TemplateId`, `TemplateCategory` (`critical()`), `TemplateStatus`, `TemplateVariable`, `TemplateEngine` (detect/render, una pasada, regex acotada) y `Template` (crear, actualizar, archivar, limites, declaradas vs detectadas) [FR-001..FR-004, FR-008, FR-012].
- [ ] T003 Excepciones de `core/exception` para plantillas (no encontrada, duplicada, version, archivada, sintaxis, no declarada, limite, faltantes).

## Fase 2: Casos de uso (core)

- [ ] T004 Puerto de salida `TemplateRepository` y `TemplateSearchCriteria`; puertos de entrada y comandos [FR-009].
- [ ] T005 Servicios: crear (valida canal con `ChannelCatalogPort`), actualizar, archivar, consultar, detectar y renderizar [FR-005..FR-007, FR-010].

## Fase 3: Adaptadores (infrastructure)

- [ ] T006 `TemplateDocument` con indice unico `(tenantId, key, channel, language)` y `TemplateMongoAdapter` (unicidad, version optimista, filtros, paginacion) [FR-006, FR-012].
- [ ] T007 `TemplateController`, DTOs y `TemplateExceptionHandler` (409 por `code`, 422 con `missingVariables`) [FR-010, FR-014].
- [ ] T008 `TemplateConfig` (beans de los 6 casos de uso).
- [ ] T009 `RouteAuthorizationPolicy`: 7 reglas (OPERADOR escribe, CLIENTE lee y renderiza) [FR-013].

## Fase 4: Pruebas

- [ ] T010 Unitarias `core`: `TemplateEngineTest` (duplicados, espacios, mas de 5 espacios, sintaxis, valor `{{x}}` literal, SC-004 con `Duration` y caso adverso), `TemplateTest`, servicios con `StepVerifier`.
- [ ] T011 `TemplateMongoRepositoryTest` (Testcontainers): indice unico, aislamiento por tenant, filtros, version.
- [ ] T012 `RouteAuthorizationPolicyTest`: una prueba por ruta.
- [ ] T013 E2E `TemplateE2ETest` (`RANDOM_PORT`, Mongo, dos tenants, control positivo): crear, opcional, listar, 422 con lista completa, 409 por codigo, archivar, aislamiento [SC-001..SC-003].
- [ ] T014 Privacidad SC-005: valor centinela ausente de logs capturados y del documento Mongo tras renderizar.
- [ ] T015 `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Fase 5: Cierre

- [ ] T016 `spotless:apply`, compilacion y pruebas dirigidas; `verify` completo queda para el cierre del nivel (CI).

## Excepciones (Principio VII)

- Registro de los 7 recursos en el PDP (`protected-resources.json` no existe en esta rama): pendiente de integracion. Dueno: Equipo de backend. Fecha: 2026-12-15.
