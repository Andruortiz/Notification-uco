# Implementation Plan: Bloques reutilizables en plantillas (HU2-130)

**Branch**: `feature/HU2-130-bloques-reutilizables` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion | **Version del plan**: 2

## Summary

`TemplateBlock` en `core`, `BlockExpander` por niveles con topes, puerto y adaptador Mongo, controller. `RenderTemplateService` (D1) expande antes de sustituir variables.

## Technical Context

Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | Testing: JUnit 5, Mockito, `StepVerifier`, Testcontainers (Mongo), `WebTestClient` |
Alcance: backend; la UI (Front-Notification-mfe) consume la tabla de rutas de [spec.md](spec.md).

## Constitution Check (2.0.1)

- I: motor, expansor, reglas y casos de uso en `core` sin Spring; Mongo y REST en `infrastructure`. Cumple.
- II: 5 rutas literales, ids en query o cuerpo, `x-status: planned`, `x-allowed-roles` y `operationId` explicito. El YAML lo edita Contrato; las tareas del controller quedan bloqueadas hasta su merge. Cumple con dependencia.
- III: sin comentarios. IV: E2E explicito (`TemplateBlockE2ETest`), dos tenants, `Duration` para SC-004, cobertura >=80 % / >=70 %.
- V: commits de una linea. VI: plan aprobado antes de `tasks`.
- VII: wiring (`UseCaseConfig`) y `pom.xml` fuera de esta historia. Dueno: Equipo de backend. Fecha: 2026-12-15.
- VIII/IX: sin envio ni consumidores RabbitMQ; sin excepcion. Logs de error con tenant, clave de bloque y de plantilla; nunca valores.

## Diseno

### Motor de resolucion (decision central)

Dos fases puras: (1) `BlockExpander.expand(body, loader)` sustituye `{{> clave}}`; (2) `TemplateEngine.render` de D1 sustituye variables en una pasada. Los valores llegan en la fase 2, por eso nunca se expanden.

- Renderizador unico (FR-014): `RenderTemplateService` (D1) es dueno de `ResolvedTemplate(subject, body, channelType, category, critical, variables, blockRefs)`, forma de D3. Expande con `BlockExpander` y llama a `TemplateEngine.render`. Entradas: plantilla guardada y contenido (introducida por D2; D7 la reutiliza en la vista previa). D2, D3 y D5 usan solo ese resultado; D4 es pura.
- Un solo patron compuesto `\{\{\s{0,5}>\s{0,5}([A-Za-z0-9._-]{1,60})\s{0,5}\}\}` con cuantificadores acotados; sin retroceso catastrofico.
- Iterativo por niveles, sin recursion: nivel 0 = cuerpo de la plantilla; se recogen las claves del nivel, se cargan en lote con
  `TemplateBlockRepository.findActiveByKeys(tenant, keys, channel, language)` (una consulta por nivel, maximo 3), se expande y se repite.
- Cada bloque se incluye por ruta; si una clave ya esta en la ruta actual -> `BlockCycleException` (409 al renderizar, 400 al guardar con la ruta).
- Topes comprobados durante la expansion, no despues: profundidad 3 (`BlockDepthExceededException`), 10 inclusiones por documento,
  30 expansiones por render, longitud acumulada <= 20000 (`BlockExpansionTooLargeException`). Esto corta la bomba 10x10x10.
- Al guardar plantilla o bloque se ejecuta el mismo expansor en modo validacion (con variables vacias) para garantizar que todo lo
  guardado es renderizable; deteccion de ciclos y profundidad salen del mismo codigo, no hay un segundo algoritmo.
- Variables efectivas = propias del documento + las de sus bloques transitivos; obligatoria si cualquier fuente la marca obligatoria.
  Si el total supera 50 -> `TEMPLATE_LIMIT_EXCEEDED`.
- Bloque ausente, archivado o de otro canal/idioma/tenant: al guardar, `BLOCK_NOT_FOUND` 400; al renderizar, `BLOCK_NOT_FOUND` 409 si falta y `BLOCK_ARCHIVED` 409 si esta archivado.

### core

- `domain/template/block/`: `TemplateBlock` (agregado, record como `Template`), `TemplateBlockId`, `BlockExpander` (uso interno de `RenderTemplateService`). `ResolvedTemplate` es de D1 (ver arriba).
- `TemplateEngine` (D1): se agrega `detectBlocks(String) -> Set<String>` y `detect` ignora las coincidencias de bloque en lugar de tratarlas como llaves residuales.
- `Template` (D1): nuevo componente `blockRefs` (lista de claves, derivada del cuerpo en `resolve`); `{{>` en el asunto -> `InvalidTemplateSyntaxException`.
- Excepciones en `exception/`: `BlockNotFoundException`, `BlockAlreadyExistsException`, `BlockVersionConflictException`, `BlockInUseException`,
  `BlockRequiredVariableInUseException`, `BlockCycleException`, `BlockDepthExceededException`, `BlockExpansionTooLargeException`, `BlockArchivedException`.
- Puertos de entrada: `CreateTemplateBlockUseCase`, `UpdateTemplateBlockUseCase`, `ArchiveTemplateBlockUseCase`, `QueryTemplateBlocksUseCase`.
- Puerto de salida `repository/TemplateBlockRepository`: `save`, `findById(tenant,id)`, `findActiveByKeys`, `search(tenant,filtro,page)`,
  `existsActiveReferencing(tenant,key,channel,language)`; siempre con tenant. Las plantillas activas que referencian un bloque se consultan por `TemplateRepository.existsActiveWithBlockRef(tenant,key,channel,language)` (metodo nuevo en el puerto de D1).
- Servicios de D1 que cambian: `CreateTemplateService` y `UpdateTemplateService` validan con el expansor; `RenderTemplateService` expande
  antes de `TemplateEngine.render` y calcula faltantes sobre las variables efectivas; `DetectTemplateVariablesService` devuelve efectivas y `blockRefs`.
- Auditoria (FR-013): los servicios de bloque llaman a `RecordAuditEntryUseCase` (B6) tras guardar, solo metadatos.

### infrastructure

- Mongo: coleccion `template_blocks`, indice unico `(tenantId, key, channel, language)`; en `templates` se guarda `blockRefs` con indice `(tenantId, blockRefs, channel, language, status)`.
- `TemplateBlockController` y DTOs (records). Mapeo al guardar: 400 `BLOCK_NOT_FOUND`, `BLOCK_CYCLE_DETECTED`, `BLOCK_DEPTH_EXCEEDED`, `BLOCK_EXPANSION_TOO_LARGE`; 409 `BLOCK_ALREADY_EXISTS`, `BLOCK_VERSION_CONFLICT`, `BLOCK_IN_USE`, `BLOCK_REQUIRED_VARIABLE_IN_USE`. Al renderizar, 409 para `BLOCK_NOT_FOUND`, `BLOCK_ARCHIVED`, ciclo, profundidad y expansion. Tenant del principal.
- `RouteAuthorizationPolicy`: 5 entradas con los roles de la tabla de [spec.md](spec.md#contrato-consumido-por-la-ui-sin-tocar-apinotificacionesyaml); recursos nuevos en `protected-resources.json` para el script de HU2-099. Recurso de auditoria `TEMPLATE_BLOCK` registrado en B6.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` | `BlockExpander` (anidado, ciclo directo/indirecto/autoinclusion, profundidad 4, 10x10, valor con `{{> x}}` literal), `TemplateEngine` (D1 intacto, `detectBlocks`), reglas de `TemplateBlock`, servicios con repositorio simulado, `StepVerifier` |
| Integracion adaptador | `TemplateBlockMongoRepositoryTest` (Testcontainers): indice unico, tenant, lote por claves, `existsActiveReferencing` |
| E2E | `TemplateBlockE2ETest` (`RANDOM_PORT`, Mongo): crear bloques, plantilla con cabecera y pie, render expandido, cambio de bloque, 422 con variable del bloque, ciclo, 409 `BLOCK_IN_USE`, dos tenants |
| Rendimiento | SC-004 con `Duration` < 200 ms, peor caso permitido y expansion adversa |
| Privacidad | SC-005: logs capturados y documentos tras valor centinela |
| Regresion D1 | SC-006: suite de D1 sin cambios en verde |
| Seguridad de ruta | una prueba por ruta y rol, incluido un rol denegado por ruta |
| Renderizador unico (SC-007) | `NoDirectTemplateRenderTest` (creada por D2); D7 añade casos de bloques y su regla ArchUnit |
| FR-006 | `BLOCK_REQUIRED_VARIABLE_IN_USE` en servicio (unitaria) y en `TemplateBlockE2ETest` |
| FR-012 | como maximo 3 consultas por render en el peor caso, con repositorio contador (unitaria) |
| Auditoria (SC-008) | en `TemplateBlockE2ETest`, registro `TEMPLATE_BLOCK` sin cuerpo tras alta, cambio y archivo |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` |

## Cambios de contrato requeridos (para Contrato)

- Cinco rutas nuevas de la tabla de [spec.md](spec.md) con tag Plantillas, `x-status: planned`, `x-allowed-roles` y `operationId` (`createTemplateBlock`, `updateTemplateBlock`, `listTemplateBlocks`, `getTemplateBlockDetail`, `archiveTemplateBlock`).
- Esquemas nuevos: `TemplateBlockResponse`, `TemplateBlockListResponse`, `CreateTemplateBlockRequest`, `UpdateTemplateBlockRequest`, `ArchiveTemplateBlockRequest`; `TemplateBlockKey` reutiliza las reglas de clave de `Template`.
- `TemplateResponse`: nuevo `blockRefs` (array de claves). Respuesta de `/template-variable-detections`: `blockRefs` y variables efectivas; documentar que la deteccion y el render resuelven bloques.
- `/templates` POST/PUT: 400 adicionales (`BLOCK_NOT_FOUND`, ciclo, profundidad, expansion). `/template-renders`: 409 adicionales y 422 con faltantes del bloque.
- Codigos NTF fijados en [spec.md](spec.md): 3080 a 3088 para los 9 `BLOCK_*`; reutilizan `TEMPLATE_LIMIT_EXCEEDED` e `INVALID_TEMPLATE_SYNTAX`; `CHANNEL_NOT_AVAILABLE` es NTF-1003.
- D2 y D3 declaran en sus rutas de render los 409 `BLOCK_NOT_FOUND`, `BLOCK_ARCHIVED`, `BLOCK_CYCLE_DETECTED`, `BLOCK_DEPTH_EXCEEDED` y `BLOCK_EXPANSION_TOO_LARGE`, y el 422 con `missingVariables` de bloques.

## Puntos compartidos con otras historias

- D1: cambios en `Template` (componente `blockRefs`), `TemplateEngine`, `TemplateRepository`, `Create/Update/Render/DetectTemplate*Service`. D7 se ramifica despues de D1.
- Cadena de plantillas en serie: D2, D7, D4 (evento), D3, D5. D2 y D7 tocan ambos `Template`, el controller y el documento Mongo; D7 se integra despues de D2.
- D2, D3 y D5 obtienen el texto final solo de `RenderTemplateService` (FR-014). D4 es pura y no renderiza. Llamar `TemplateEngine.render` sobre `template.body()` deja `{{> x}}` sin expandir y rompe SC-007.
- B2: la precondicion de roles por sesion (sesion siempre CLIENTE) impide que EDITOR_PLANTILLAS llegue en produccion; sin ella las escrituras de D7 no se usan.
- C7 y H4: crean y consumen bloques por `CreateTemplateBlockUseCase` y las rutas; no hay bloques globales.
- D5/D8: misma clave `(canal, idioma)`; D8 podria añadir respaldo de idioma sin cambiar el contrato.

## Riesgos

- Bloqueo por el YAML de Contrato y por el wiring externo (2026-12-15).
- Un cambio de bloque afecta de inmediato a sus plantillas (decision explicita).
- Ciclos por guardados concurrentes: cubiertos en render (409); no se bloquean en base de datos.
