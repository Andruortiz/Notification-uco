# Implementation Plan: Plantillas con variables por canal e idioma (HU2-119)

**Branch**: `feature/HU2-119-plantillas-variables` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion

**Versión del plan**: 2

## Summary

Se agrega un agregado `Template` en `core` con un motor de plantillas puro (detectar y renderizar `{{nombre}}`), casos de uso
de CRUD, archivado y render, un puerto de persistencia, un adaptador Mongo y un controller WebFlux. El tenant sale de la sesion.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | **Testing**: JUnit 5, Mockito, `StepVerifier`,
Testcontainers (Mongo), `WebTestClient` | **Alcance**: backend; la UI (repo aparte) consume la tabla de rutas de [spec.md](spec.md).

## Constitution Check (2.0.1)

- I Hexagonal: motor, reglas y casos de uso en `core` sin Spring; Mongo y REST solo en `infrastructure`. Cumple.
- II Contrato primero: 7 rutas literales, ids en query o cuerpo. El YAML lo edita Contrato; el controller se escribe tras su merge,
  y hasta entonces las tareas del controller quedan bloqueadas. Cumple con dependencia.
- III Sin comentarios en codigo nuevo. Cumple.
- IV E2E explicito (`TemplateE2ETest`), prueba de dos tenants, aserciones de `Duration` para SC-004. Cobertura >=80 % / >=70 %.
- V Commits de una linea. VI Plan aprobado antes de `tasks`. VII Sin soluciones temporales: P3-P5 quedan como alcance explicito.
- Consumidores RabbitMQ: no se toca ninguno. Sin excepcion.

## Diseño

### core

- `domain/template/`: `Template` (agregado), `TemplateId`, `TemplateKey`, `TemplateCategory` (enum con `critical()`),
  `TemplateLanguage` (valida BCP 47 corto), `TemplateStatus`, `TemplateVariable(name, required)`.
- `domain/template/TemplateEngine`: `detect(String...) -> List<String>` y `render(text, values) -> String`, una sola pasada con
  `Pattern` `\{\{\s{0,5}([A-Za-z][A-Za-z0-9_]{0,39})\s{0,5}\}\}` (cuantificadores acotados); llaves residuales tras quitar coincidencias -> `InvalidTemplateSyntaxException`.
- Reglas en `Template`: variables declaradas subconjunto de las detectadas, detectadas no declaradas = obligatorias, limites
  (20000 caracteres, 50 variables), `archive()`, `update(...)` que incrementa `version`.
- Excepciones en `exception/`: `TemplateNotFoundException`, `TemplateAlreadyExistsException`, `TemplateVersionConflictException`,
  `TemplateArchivedException`, `InvalidTemplateException`, `MissingTemplateVariablesException(List<String>)`.
- Puertos de entrada: `CreateTemplateUseCase`, `UpdateTemplateUseCase`, `ArchiveTemplateUseCase`, `QueryTemplatesUseCase`
  (lista y detalle), `DetectTemplateVariablesUseCase`, `RenderTemplateUseCase`.
- Puerto de salida: `repository/TemplateRepository` (`save`, `findById(tenant,id)`, `search(tenant,filtro,page)`), siempre con tenant.
  El canal se valida con `ChannelCatalogPort.findActiveRoute(ChannelType, TenantId)`; vacio -> `CHANNEL_NOT_AVAILABLE`.

### infrastructure

- `adapter/out/mongo`: documento `templates`, indice unico `(tenantId, key, channel, language)` y `(tenantId, category)`;
  `version` con `@Version` o comparacion explicita para 409.
- `adapter/in/rest`: `TemplateController` y DTOs (records). Mapeo: 400 sintaxis/declaracion, 404, 409 con `code` distinto (`TEMPLATE_ALREADY_EXISTS`, `TEMPLATE_VERSION_CONFLICT`, `TEMPLATE_ARCHIVED`), 400 con `code` distinto
  (`INVALID_TEMPLATE_SYNTAX`, `UNDECLARED_TEMPLATE_VARIABLE`, `CHANNEL_NOT_AVAILABLE`, `TEMPLATE_LIMIT_EXCEEDED`),
  422 con cuerpo `{code, message, missingVariables[]}`. Tenant tomado del principal de sesion.
- `RouteAuthorizationPolicy`: agregar las 7 entradas (metodo, ruta) -> Role del contrato; el valor por defecto es ADMINISTRADOR, asi que sin
  ellas todo exigiria ese rol. Una prueba por ruta fija el rol. Con PEP activo la politica devuelve CLIENTE y decide el PDP: los 7 recursos
  se registran con el script de HU2-099 a partir de `protected-resources.json`.
- Excepcion (Principio VII): wiring (`UseCaseConfig`) y `pom.xml` quedan fuera de esta historia. Dueno: Equipo de backend. Fecha: 2026-12-15.

### Extensibilidad (no se construye)

- D2 vista previa reutiliza `TemplateEngine.render`; D3 llama `RenderTemplateUseCase`; D8 agrupa por `TemplateKey`; las categorias
  nuevas se agregan como constantes del enum; `TemplateVariable` admite un valor por defecto futuro sin cambiar el contrato.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` (`StepVerifier` donde hay `Mono`) | `TemplateEngine` (duplicados, espacios, sintaxis invalida, valor con `{{x}}` literal), reglas de `Template`, casos de uso con repositorio simulado |
| Integracion adaptador | `TemplateMongoRepositoryTest` con Testcontainers: indice unico, aislamiento por tenant, filtros |
| E2E | `TemplateE2ETest` (`RANDOM_PORT`, Mongo): crear, declarar opcional, listar, 422 con lista completa, 409, archivar, dos tenants |
| Rendimiento | SC-004 con aserciones de `Duration` (< 200 ms), incluido el caso adverso `"{{" + " ".repeat(19990)` |
| Privacidad | SC-005: captura de logs y lectura del documento Mongo tras renderizar con un valor centinela |
| Seguridad de ruta | una prueba por ruta de `RouteAuthorizationPolicy` (OPERADOR para escritura, CLIENTE para lectura y render) |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` en verde |

## Cambios de contrato requeridos

- Contrato debe anadir `maxProperties: 50` a `RenderTemplateRequest.values` y `maxLength: 2000` a sus valores, y documentar la normalizacion del idioma a minusculas.

## Riesgos y dependencias

- Bloqueo por el YAML de Contrato (Principio II) y por el wiring externo (Equipo de backend, 2026-12-15).
- Roles cerrados (usuario, 2026-10-24): OPERADOR escribe, CLIENTE lee y renderiza; rigen los tres roles actuales.
- Regex sobre texto de hasta 20000 caracteres: patron sin retroceso catastrofico; cubierto por SC-004.
