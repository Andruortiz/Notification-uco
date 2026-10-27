# Tasks: Vista previa en vivo de plantillas (HU2-126)

**Input**: [spec.md](spec.md), [plan.md](plan.md). Rutas base: `core/src/main/java/co/edu/uco/notification/core/` (abrev. `CORE/`), `core/src/test/java/...` (`CORE_T/`), `infrastructure/src/main/java/co/edu/uco/notification/infrastructure/` (`INF/`), `infrastructure/src/test/java/co/edu/uco/notification/infrastructure/` (`INF_T/`).

**Tests**: incluidos. La spec exige pruebas por SC/FR y el Principio IV exige una E2E explícita por historia. Orden en cada tarea de prueba: escribir, ver fallar por la razón correcta, implementar.

**Organización**: por historia (US1 a US4 = User Stories 1 a 4 de spec.md). Cada tarea termina con criterio de hecho.

**Formato de desvíos**: se anotan en la tarea afectada, no aquí.

## Phase 1: Setup

- [ ] T001 Línea base: ejecutar en `infrastructure` `ApiContractMatchesControllersTest`, `ApiV2RoutesE2ETest`, `ChangelogContractTest`, `ErrorCodeTest` (utils) y confirmar verde antes de tocar nada. Criterio: log filtrado sin FAIL.

## Phase 2: Foundational (bloquea todas las historias)

- [ ] T002 Contrato en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`: `previewTemplate` pasa de `x-status: planned` a `available`; `sampleValues` pasa a `available` en `CreateTemplateRequest`, `UpdateTemplateRequest` y `TemplateResponse`; esquemas `PreviewTemplateRequest`, `TemplatePreviewResponse` y `SmsSegmentation` completos según plan.md (Cambios de contrato 1 a 4). Versión `2.3.0` a `2.3.1` con línea en `info.description`. Criterio: el YAML carga y ninguna operación ni propiedad de HU2-126 queda en `planned`.
- [ ] T003 [P] `CORE/domain/template/SmsEncoding.java` (enum `GSM7`, `UCS2`) y `CORE/domain/template/SmsSegmentation.java` (record `encoding, units, unitsPerSegment, segments, exceedsSingleSegment`). Sin comentarios. Criterio: compila.
- [ ] T004 [P] `CORE/domain/template/TemplatePreview.java` (record `subject, body, missingVariables, characterCount, subjectCharacterCount, SmsSegmentation sms`; `sms` nulo fuera de SMS). Criterio: compila.
- [ ] T005 [P] `CORE/usecase/ResolvedTemplate.java` (record `subject, body, channelType, category, critical, variables, blockRefs`), único tipo de D1/D2/D3/D7 (plan, Dependencia). `blockRefs` vacía hasta D7. Criterio: compila.
- [ ] T006 Prueba primero: `CORE_T/usecase/RenderTemplateServiceContentTest.java` con `StepVerifier` para la entrada por contenido: sin `templateId`, modo tolerante devuelve faltantes sin lanzar, modo estricto mantiene 422 de `MissingTemplateVariablesException`, `InvalidTemplateSyntaxException` se propaga, valor con `{{otra}}` se inserta literal. Criterio: falla por método inexistente.
- [ ] T007 Implementar en `CORE/usecase/RenderTemplateService.java` la entrada por contenido (canal, asunto, cuerpo, variables, valores, modo tolerante) que devuelve `ResolvedTemplate` con `category` y `critical` nulos en la vista previa; `render(tenant, templateId, values)` delega en ella sin cambiar su comportamiento de D1. `TemplateEngine` no se toca. Criterio: T006 verde y `TemplateServicesTest` verde.
- [ ] T008 Prueba primero: `INF_T/architecture/NoDirectTemplateRenderTest.java` con el patrón de `NoHardcodedChannelTest`: ningún código fuera de `RenderTemplateService` llama `TemplateEngine.render`. Criterio: verde; si hay llamadas fuera, migrarlas al servicio en la misma tarea y anotarlo aquí.

**Checkpoint**: contrato available y renderizador unico listo. Las historias pueden empezar.

## Phase 3: User Story 1 - Ver el borrador renderizado con datos de ejemplo (P1) MVP

**Goal**: `POST /template-previews` devuelve asunto y cuerpo renderizados sin persistir nada.
**Independent Test**: previsualizar `Hola {{nombre}}` con `nombre=Ana` y recibir `Hola Ana`.

- [ ] T009 [US1] Prueba primero: `CORE_T/usecase/PreviewTemplateServiceTest.java` con `StepVerifier`: AS1 (faltantes vacío), AS2 (faltante en cadena vacía y en `missingVariables`), AS3 (valor con `{{otra}}` literal), AS5 (`{{1abc}}` a `InvalidTemplateSyntaxException`), y que no llama al repositorio de escritura. Criterio: falla.
- [ ] T010 [US1] Crear `CORE/port/in/PreviewTemplateUseCase.java` (`Mono<TemplatePreview> preview(PreviewCommand)`), `CORE/port/in/PreviewCommand.java` y `CORE/usecase/PreviewTemplateService.java` que delega en T007 con modo tolerante. Sin SMS todavía (`sms` nulo). Criterio: T009 verde.
- [ ] T011 [US1] Prueba primero: `INF_T/adapter/in/rest/TemplatePreviewE2ETest.java` (`@SpringBootTest(RANDOM_PORT)`, `@Testcontainers` Mongo y RabbitMQ, `WebTestClient`) con AS1 a AS5 de US1 y SC-001 (vista previa y `POST /template-renders` con el mismo texto dan el mismo `body`). Criterio: falla por ruta inexistente.
- [ ] T012 [US1] Crear `INF/adapter/in/rest/TemplatePreviewController.java` (`POST /template-previews`, tenant de la sesión) y `INF/adapter/in/rest/TemplatePreviewDtos.java` (`PreviewTemplateRequest`, `TemplatePreviewResponse`). No se registran `values` en logs. Criterio: T011 verde en sus escenarios de US1.
- [ ] T013 [US1] Wiring del caso de uso en `INF/config/UseCaseConfig.java` (excepción de D1 heredada, plan VII). Criterio: el contexto arranca.
- [ ] T014 [US1] Recuentos y versión de contrato en `INF_T/contract/ApiContractMatchesControllersTest.java`, `INF_T/contract/ApiV2RoutesE2ETest.java` (`thePlannedOperationsAreNotServed` pasa a comprobar las que siguen planned), `INF_T/contract/ChangelogContractTest.java` (2.3.1 registrada). Criterio: las cuatro verdes.
- [ ] T015 [US1] Seguridad de ruta: `INF_T/adapter/in/web/RouteAuthorizationPolicyTest.java`, `INF_T/contract/RouteAuthorizationPolicyContractTest.java` y `INF_T/contract/ProtectedResourcesCatalogTest.java` deben cubrir `POST /template-previews` permitido a EDITOR_PLANTILLAS, ADMINISTRADOR_APLICACION y ADMINISTRADOR_PLATAFORMA, y denegado a CLIENTE y OPERADOR. Añadir casos si faltan. Criterio: verde.

**Checkpoint**: US1 funciona por E2E.

## Phase 4: User Story 2 - Conteo de caracteres y aviso SMS (P1)

**Independent Test**: `Hola Ana, tu codigo es 123456` en SMS da GSM7, 29 unidades, 1 segmento.

- [ ] T016 [US2] Prueba primero: `CORE_T/domain/template/SmsSegmenterTest.java` con los escenarios 1 a 9 de US2 y SC-002 (textos reales, 70/71 UCS-2, 160/161 GSM, `€` y `{` cuentan 2, emoji cuenta 2 UTF-16 y 1 código, vacío da `segments=0`). Criterio: falla.
- [ ] T017 [US2] Crear `CORE/domain/template/SmsSegmenter.java` (utilidad estática, un recorrido por puntos de código, tablas GSM 03.38 básica y extensión como constantes; `á í ó ú` y sus mayúsculas, y `ç` minúscula, fuera de la básica). Criterio: T016 verde.
- [ ] T018 [US2] Ampliar `PreviewTemplateServiceTest` con `characterCount`, `subjectCharacterCount` y `sms` solo para canal SMS (comparación con el valor del catálogo en mayúsculas); en `PreviewTemplateService.java` usar `SmsSegmenter` sobre el cuerpo renderizado (no la plantilla cruda). Criterio: verde.
- [ ] T019 [US2] Ampliar `TemplatePreviewE2ETest` con SMS de 1 y 2 segmentos y canal no SMS (`sms` nulo, conteos presentes), y prueba de que el conteo usa el texto renderizado. Criterio: verde.

**Checkpoint**: US2 verificable por unitaria y E2E.

## Phase 5: User Story 3 - Guardar los datos de ejemplo en la plantilla (P1)

**Independent Test**: `PUT /templates` con `sampleValues={nombre:Ana}` y `GET /templates/detail` lo devuelve.

- [ ] T020 [US3] Prueba primero: `CORE_T/domain/template/TemplateSampleValuesTest.java` (claves subconjunto de las detectadas, 50 entradas, 2000 caracteres, por defecto mapa vacío). Criterio: falla.
- [ ] T021 [US3] `CORE/domain/template/Template.java`: campo `Map<String,String> sampleValues` inmutable, validado en `create` y `update` con `UndeclaredTemplateVariableException` y `TemplateLimitExceededException`. Actualizar constructores existentes en `CORE_T/usecase/TemplateServicesTest.java`. Pasar el campo en `CORE/port/in/CreateTemplateCommand.java`, `UpdateTemplateCommand.java`, `CreateTemplateService.java` y `UpdateTemplateService.java`. Criterio: T020 y `TemplateServicesTest` verdes.
- [ ] T022 [US3] Prueba primero: `INF_T/adapter/out/mongo/TemplateMongoAdapterTest.java` (ampliar): ida y vuelta de `sampleValues` y documento antiguo sin el campo lee `{}`. Criterio: falla.
- [ ] T023 [US3] `INF/adapter/out/mongo/TemplateDocument.java` y `TemplateMongoAdapter.java`: mapear `sampleValues`; nulo a `{}`. Sin índice ni migración. Criterio: T022 verde.
- [ ] T024 [US3] `INF/adapter/in/rest/TemplateDtos.java` (`CreateTemplateRequest`, `UpdateTemplateRequest`, `TemplateResponse`) y `TemplateController.java` (mapeo). Criterio: compila; `ApiContractMatchesControllersTest` sigue verde.
- [ ] T025 [US3] Ampliar `TemplatePreviewE2ETest` con AS1 a AS5 de US3: guardar, leer en `/templates/detail`, `UNDECLARED_TEMPLATE_VARIABLE` (400), `TEMPLATE_LIMIT_EXCEEDED` (400), plantilla sin el campo lee `{}`, y cuerpo sin la variable con ejemplos viejos da 400. Criterio: verde.

**Checkpoint**: US3 por E2E.

## Phase 6: User Story 4 - Aislamiento, privacidad, límites y rendimiento (P1)

**Independent Test**: dos tenants con la misma variable no se ven los ejemplos; un valor centinela no queda en logs ni en Mongo.

- [ ] T026 [US4] Ampliar `TemplatePreviewE2ETest` con dos tenants que usan el mismo nombre de variable: cada uno guarda y lee sus ejemplos, ninguno ve los del otro (SC-003, plan Estrategia, FR-007). Criterio: verde.
- [ ] T027 [US4] Ampliar `TemplatePreviewE2ETest` con SC-005: vista previa con valor centinela; captura de logs del adaptador de logs sin el centinela y `templates` de Mongo sin el centinela tras la vista previa (FR-006). Criterio: verde; el control positivo debe demostrar que la captura sí recoge logs.
- [ ] T028 [US4] Ampliar `TemplatePreviewE2ETest` con SC-004 y `Duration`: caso válido de 20000 caracteres y 50 variables en menos de 200 ms de cálculo; y caso `"{{" + " ".repeat(19990)` que es sintaxis inválida y debe devolver 400 `INVALID_TEMPLATE_SYNTAX` en menos de 200 ms. Criterio: aserciones `Duration` verdes.
- [ ] T029 [US4] Límites de la vista previa (asunto >200, cuerpo >20000, más de 50 valores, valor >2000) respondidos con 400 `TEMPLATE_LIMIT_EXCEEDED`, reutilizando el verificador de D1. Pruebas en `PreviewTemplateServiceTest` y en `TemplatePreviewE2ETest`. Criterio: verde.
- [ ] T030 [US4] Excepción de bloques (ver plan Errores de bloque): los códigos `BLOCK_*` (3080-3088) no existen en `utils/src/main/java/co/edu/uco/notification/utils/ErrorCode.java`. D2 no los implementa. Registrar en `plan.md` una excepción del Principio VII con dueño y fecha; el dueño y la fecha quedan para que los fije el usuario. Criterio: `plan.md` actualizado y la excepción citada aquí.

**Checkpoint**: US4 cubierta por E2E de aislamiento y privacidad.

## Phase 7: Polish y cierre

- [ ] T031 `spotless:apply` sobre `core` e `infrastructure` tras crear o editar `.java`. Criterio: `spotless:check` verde.
- [ ] T032 Pruebas de arquitectura: `HexagonalArchitectureTest` y `ModularityTests` verdes.
- [ ] T033 Pruebas dirigidas (`-pl utils,core -am test` y las de infrastructure listadas en el encargo). Criterio: resultados por clase en `target/surefire-reports`. No ejecutar `verify` completo en esta fase.
- [ ] T034 Registrar en esta lista las desviaciones y tareas no marcadas. Criterio: sin tareas abiertas sin explicación.

## Dependencies

- Setup (T001) antes de todo.
- Foundational (T002 a T008) bloquea US1 a US4. T002 antes de T012 y T014. T005 y T007 antes de T010.
- US1 es MVP. US2 depende de T010. US3 depende de T007. US4 depende de US1, US2 y US3, porque la E2E es la misma clase.
- Dentro de cada historia: prueba primero, luego implementación.

## Parallel examples

- T003, T004 y T005 en paralelo (archivos distintos).
- T016 y T020 en paralelo (pruebas de `core` de historias distintas).

## Implementation strategy

MVP = Phase 1 a 3 (US1). Después US2, US3, US4 en orden, cada una con su E2E. Cierre con Phase 7.
