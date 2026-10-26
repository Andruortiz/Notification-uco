# Implementation Plan: Armar la plantilla con el evento recibido (HU2-128)

**Branch**: `feature/HU2-128-plantilla-con-evento` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion

**Versión del plan**: 2 (correcciones 2026-10-25: rutas y roles, códigos NTF, nombres alineados con D3, límites y auditoría)

## Summary

Se agrega en `core` un mapeo `evento -> variables` por plantilla: un value object `EventPath` que recorre rutas simples sobre
`Map`/`List`, un agregado `TemplateMapping`, tres casos de uso (guardar, consultar, resolver), un puerto de persistencia y un adaptador
Mongo con un controller de tres rutas. No hay ingestion nueva: D3 llama `ResolveTemplateValuesUseCase` y luego `RenderTemplateUseCase` de D1.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | **Testing**: JUnit 5, Mockito, `StepVerifier`, Testcontainers (Mongo),
`WebTestClient` | **Alcance**: backend; la UI (Front-Notification-mfe) consume la tabla de rutas de [spec.md](spec.md).

## Constitution Check (2.0.1)

- I Hexagonal: `EventPath`, reglas, resolucion y casos de uso en `core` sin Spring ni Jackson (el evento llega como `Map<String,Object>`
  de tipos Java planos); Mongo y REST solo en `infrastructure`. Cumple.
- II Contrato primero: 3 rutas literales sin `:` ni `{}`, ids en query o cuerpo, con `operationId`, `x-status: planned` y `x-allowed-roles` (B2 entra primero). El YAML lo edita Contrato;
  las tareas del controller quedan bloqueadas hasta su merge. Cumple con dependencia.
- III Sin comentarios. IV E2E explicito (`TemplateEventMappingE2ETest`), dos tenants, `Duration` en SC-003; cobertura >=80 % / >=70 %.
- V Commits de una linea. VI Plan aprobado antes de `tasks`.
- VII Excepcion: wiring (`UseCaseConfig`) y entradas de `RouteAuthorizationPolicy` / `protected-resources.json` se integran junto con D1 y D3 al
  consolidar el nivel. Dueno: Equipo de backend. Fecha: 2026-12-15.
- Consumidores RabbitMQ: no se toca ninguno. Sin excepcion. Sin canal de ingestion nuevo (decision D4).

## Diseño

### core

- `domain/template/mapping/`: `TemplateMapping` (agregado: `TemplateMappingId`, tenant, `TemplateId`, `List<MappingEntry>`, `version`),
  `MappingEntry(variable, EventPath path, defaultValue)`.
- `EventPath`: `parse(String)` valida con parseo manual por caracteres (sin regex) los segmentos `[A-Za-z_][A-Za-z0-9_-]{0,39}` o `[0-9]{1,3}`,
  max 8 segmentos y 200 caracteres -> `InvalidMappingPathException`. `extract(Object root) -> Optional<String>`: bucle sobre segmentos;
  `Map` por nombre, `List` por indice; escalar `String`/`Number`(`BigDecimal.toPlainString`)/`Boolean` -> texto; otro tipo o `null` -> vacio.
  Costo O(segmentos): no hay recorrido del evento ni backtracking.
- `EventLimits`: valida profundidad 10 y largo serializado aproximado 32768 con un recorrido iterativo (pila explicita, sin recursion) que
  suma largos de cadenas, claves y numeros; corta al exceder -> `EventLimitExceededException` (sin contenido en el mensaje).
- Reglas en `TemplateMapping`: variable debe estar entre las declaradas de la `Template` (D1), una entrada por variable, <=50, defecto <=2000.
- Casos de uso (puertos de entrada): `SaveTemplateMappingUseCase`, `QueryTemplateMappingUseCase`, `ResolveTemplateValuesUseCase`
  (`resolve(tenant, templateId, Map<String,Object>) -> Mono<Map<String,String>>`).
- Resolucion: carga plantilla (puerto de D1, con tenant; 404 si ajena), carga mapeo, ignora variables que la plantilla ya no declara, aplica
  `extract` con defecto, descarta valores de mas de 2000 caracteres con `EventLimitExceededException`.
- Puerto de salida: `repository/TemplateMappingRepository` (`save`, `findByTemplate(tenant, templateId)`), siempre con tenant. Reutiliza el
  puerto de lectura de plantillas de D1 (`TemplateRepository.findById(tenant, id)`); no se agrega acceso a plantillas nuevo.
- Excepciones: `InvalidMappingPathException`, `UnknownMappingVariableException`, `MappingLimitExceededException`,
  `EventLimitExceededException`, `TemplateMappingVersionConflictException`; reusa `TemplateNotFoundException` y `TemplateArchivedException` de D1.

### infrastructure

- `adapter/out/mongo`: coleccion `template_mappings`, indice unico `(tenantId, templateId)`; `version` con comparacion explicita para 409.
- `adapter/in/rest`: `TemplateMappingController` y DTOs (records). Errores 400 con `code` (`INVALID_MAPPING_PATH` 1013, `UNKNOWN_MAPPING_VARIABLE`
  1014, `MAPPING_LIMIT_EXCEEDED` 1015, `EVENT_LIMIT_EXCEEDED` 1016), 404, 409 (`TEMPLATE_MAPPING_VERSION_CONFLICT` 3054, `TEMPLATE_ARCHIVED`). Tenant del principal de sesion.
  El mensaje de error nunca incluye valores del evento; solo el indice del mapeo o el nombre de variable.
- `RouteAuthorizationPolicy`: agregar con `x-allowed-roles` de [spec.md](spec.md#contrato-consumido-por-la-ui-sin-tocar-api-notificacionesyaml-ver-plan) (PUT, GET y POST de resoluciones), mas una prueba por ruta; registrar los 3 recursos en `protected-resources.json` para el script de HU2-099.
- Logs: ninguna linea de resolucion imprime el evento ni los valores; solo `tenantId`, `templateId`, conteo de variables resueltas y duracion.

### Interaccion con otras historias

- **D3 (043)**: declara el campo `event` y `templateValues` del envio, y aplica la regla `templateValues` explicitos > `event` mapeado. Antes de
  renderizar, D3 llama `ResolveTemplateValuesUseCase`; D4 no toca el envio. Orden de la cadena: D2, D7, D3, D4, D5. Punto compartido: nombre y firma del puerto, y que el 422 de faltantes lo emite D1.
- **D1 (035)**: dependencia dura (`Template`, `TemplateId`, `TemplateRepository`, `TemplateEngine.render`). D4 no modifica `Template`;
  D1 deja `TemplateVariable` libre de defectos (su P3) y el defecto de D4 vive en el mapeo, por eso no hay conflicto.
- **D2 (042)**: opcional; la vista previa puede llamar la resolucion con un evento de ejemplo.
- **B6 (041)**: cuando exista, auditara los guardados de `/template-mappings` como cambio de configuracion, sin before/after del `defaultValue`
  (se registran `templateId`, `version` y conteo de mapeos). El defecto debe ser ficticio (FR-009).

### Seguridad

- Sin regex ni evaluacion en el mapeo (`EventPath` es parseo manual y cursor lineal): descarta ReDoS e inyeccion de expresiones.
- Limites de evento antes de resolver; profundidad iterativa para evitar `StackOverflowError`. El limite de profundidad (10) y de tamano (32768) lo impone `EventLimits` en `core`.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` | `EventPath` (parse valido e invalido parametrizado: `$`, `[`, `*`, `..`, vacia, 9 segmentos; extract anidado, indice, tipos), `EventLimits` (profundidad 10 aceptada, 11 rechazada; 32768 caracteres), reglas de `TemplateMapping`, casos de uso con repositorios simulados |
| Integracion adaptador | `TemplateMappingMongoRepositoryTest` (Testcontainers): indice unico, aislamiento por tenant, version |
| E2E | `TemplateEventMappingE2ETest` (`RANDOM_PORT`, Mongo): crear plantilla (D1), guardar mapeo, resolver, renderizar con D1, 422 de faltantes, 409 de version, dos tenants con control positivo |
| Rendimiento | SC-003 con aserciones de `Duration` (< 200 ms), incluido el caso adverso de rutas largas |
| Privacidad | SC-004: captura de logs y lectura de colecciones Mongo tras resolver con un valor centinela |
| Seguridad de ruta | una prueba por ruta de `RouteAuthorizationPolicy` |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` en verde |

Cuando D3 se integre, su E2E de envio con `event` cubre el extremo `evento -> mensaje enviado`; D4 no lo duplica.

## Cambios de contrato requeridos (para Contrato; no se edita `api-notificaciones.yaml` aqui)

- Nuevas rutas con `x-status: planned` y `operationId` (`saveTemplateMapping`, `getTemplateMapping`, `resolveTemplateMapping`): `PUT /template-mappings`,
  `GET /template-mappings?templateId=`, `POST /template-mapping-resolutions`, con `x-allowed-roles` y errores de [spec.md](spec.md); registrarlas en el cuadro de HUs de la cabecera.
- Esquemas: `SaveTemplateMappingRequest {templateId, version, mappings[<=50]}`, `TemplateMappingEntry {variable, path (maxLength 200, patron de segmentos), defaultValue (maxLength 2000)}`,
  `TemplateMappingResponse {templateId, version, mappings[]}`, `ResolveTemplateValuesRequest {templateId, event (object, additionalProperties)}`,
  `ResolveTemplateValuesResponse {values: map string->string}`.
- Para D3: `SendNotificationRequest` (o su variante por plantilla) debe declarar `event` como objeto libre; texto de la descripcion lo fija D3.
- Los codigos de error nuevos listados en el spec se anaden al catalogo de errores.

## Riesgos y dependencias

- Bloqueo por el YAML de Contrato (Principio II), por D1 (modelo `Template`) y por el wiring externo (Equipo de backend, 2026-12-15).
- Si el usuario elige embebido (spec Q1), el mapeo pasa a `PUT /templates` de D1 y esta historia se reduce a `EventPath` y la resolucion.
- Evento de hasta 32 KB dentro del cuerpo de envio: el limite de 8 MB del servicio no lo protege; se valida en el nucleo.
