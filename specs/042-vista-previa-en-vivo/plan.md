# Implementation Plan: Vista previa en vivo de plantillas (HU2-126)

**Branch**: `feature/HU2-126-vista-previa-en-vivo` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion | **Version del plan**: 3 (segundo pase: decision A aprobada, errores de bloques, `ResolvedTemplate` unico)

## Summary

Se agrega un endpoint de vista previa sin estado, `POST /template-previews`, que pasa por el renderizador unico de `core` (no por
`TemplateEngine` directo), un calculador de segmentos SMS puro (`SmsSegmenter`, compartido con D3) y un campo `sampleValues` en el
agregado `Template`. Sin puertos de salida nuevos.

## Dependencia

Todo el codigo se apoya en HU2-119 ([plan 035](../035-plantillas-variables/plan.md)): `Template`, `TemplateEngine`, `RenderTemplateService`
(renderizador unico), `TemplateRepository`, `TemplateController` y documento Mongo `templates`. D2 no se implementa hasta que D1 este
integrada en `main`.

Decision A aprobada (2026-10-25): `RenderTemplateService` (de D1, no el motor) gana una entrada por contenido, sin `templateId`, y un modo
tolerante que devuelve faltantes sin lanzar 422. D2 la introduce; D7 y D3 la reutilizan. D2 no modifica `TemplateEngine`.

Orden de la cadena de plantillas y de integracion: D2, D7, D4 (parte event), D3, D5.

Forma unica de `ResolvedTemplate`: los specs de D7 (`ResolvedTemplate(text, variables, blockRefs)`) y de D3 (`ResolvedTemplate(subject,
body, channelType, category, critical)`) divergen. Se fija una sola: `ResolvedTemplate(subject, body, channelType, category, critical,
variables, blockRefs)`, dueño `RenderTemplateService` de D1. D2 la usa sin redefinirla. D7 y D3 deben adoptarla en sus specs (no se
cambian aqui).

## Constitution Check (2.0.1)

- I Hexagonal: calculo y caso de uso en `core` sin Spring; REST y Mongo solo en `infrastructure`. Cumple.
- II Contrato primero: 1 ruta literal nueva, 3 esquemas de D1 ampliados con `sampleValues` (`CreateTemplateRequest`,
  `UpdateTemplateRequest`, `TemplateResponse`) y 3 esquemas nuevos (`PreviewTemplateRequest`, `TemplatePreviewResponse`,
  `SmsSegmentation`). Las tareas del controller quedan bloqueadas hasta que Contrato fusione el YAML.
- III Sin comentarios. IV E2E explicito (`TemplatePreviewE2ETest`), dos tenants, `Duration` para SC-004, cobertura 80/70.
- V Commits de una linea. VI Plan aprobado antes de `tasks`. Consumidores RabbitMQ: ninguno. Sin excepcion.
- VII Excepcion heredada de D1: wiring en `UseCaseConfig`. Dueno: Equipo de backend. Fecha: 2026-12-15.

## Diseno

### core

- `domain/template/SmsSegmenter` (utilitaria estatica, como `TemplateEngine`): `segment(String) -> SmsSegmentation`. Tabla GSM 03.38
  basica y de extension como constantes; un solo recorrido por puntos de codigo. Basica incluye `é è ù ì ò à É Ç ñ Ñ ü Ü ä ö ¡ ¿`; no
  incluye `á í ó ú`, sus mayusculas ni `ç` minuscula. Extension: `^ { } \ [ ] ~ | €` y salto de pagina (2 unidades cada una). Si algun
  punto de codigo esta fuera de ambas tablas, UCS-2 con unidades UTF-16 (70 y 67).
- `domain/template/SmsSegmentation` (record: encoding, units, unitsPerSegment, segments, exceedsSingleSegment) y enum `SmsEncoding`.
- `domain/template/TemplatePreview` (record: subject, body, missingVariables, characterCount, subjectCharacterCount, `SmsSegmentation sms`
  nulable).
- Entrada por contenido de `RenderTemplateService` (decision A): recibe asunto, cuerpo, canal, variables y valores, sin `templateId`;
  expande bloques (D7), sustituye y devuelve `ResolvedTemplate`. En modo tolerante, las obligatorias sin valor no lanzan 422. D3
  `resolve(templateId, ...)` carga la plantilla y delega en esta misma entrada. Ningun codigo fuera del servicio llama a
  `TemplateEngine.render` (FR-014 de D7).
- `ResolvedTemplate` (tipo unico, ver Dependencia): en la vista previa `category` y `critical` van nulos porque el borrador no las tiene.
- Puerto de entrada `PreviewTemplateUseCase`: `preview(PreviewCommand) -> Mono<TemplatePreview>`. Delega en la entrada por contenido
  (modo tolerante); calcula `missingVariables` a partir de `variables` y `values`; cuenta caracteres y, si el canal es `SMS` (comparacion
  con el valor del catalogo en mayusculas), segmenta el cuerpo renderizado. Valida los limites de D1 (reusa su verificador). Propaga
  sin traducir `InvalidTemplateSyntaxException` (400) y los errores de bloque (409). No llama a `TemplateEngine` directamente.
- Regla SMS compartida con D3: D2 solo informa (`exceedsSingleSegment`); el rechazo 400 sin truncar de un envio por plantilla lo aplica D3.
- `Template`: campo `Map<String,String> sampleValues` (inmutable, por defecto vacio), validado en `create`/`update`: claves subconjunto de
  las detectadas, 50 entradas, 2000 caracteres. Sin puerto de salida nuevo; `TemplateRepository` ya persiste el agregado completo.

### infrastructure

- `adapter/in/rest`: `TemplatePreviewController` (`POST /template-previews`) y DTOs `PreviewTemplateRequest`/`TemplatePreviewResponse`;
  `TemplateController` y DTOs de D1 ganan `sampleValues`. El tenant se toma de la sesion aunque la vista previa no consulte datos (patron D1).
- `adapter/out/mongo`: el documento `templates` agrega `sampleValues` (mapa); lectura nula -> `{}`. Sin migracion ni indice nuevo.
- Autorizacion: `POST /template-previews` con `x-allowed-roles` `EDITOR_PLANTILLAS`, `ADMINISTRADOR_APLICACION` y
  `ADMINISTRADOR_PLATAFORMA` (tabla de B2). `POST/PUT /templates` sigue en `EDITOR_PLANTILLAS` y `ADMINISTRADOR_PLATAFORMA`. Recurso nuevo
  `/template-previews` en `protected-resources.json`.
- Logs: no se registra el cuerpo de la peticion ni `values`.

### Errores de bloque en la vista previa

Sin codigos nuevos; se reutilizan los de D7, que el render devuelve con 409 (FR-005 de D7): `BLOCK_NOT_FOUND` (NTF-3080),
`BLOCK_ARCHIVED` (3088), `BLOCK_CYCLE_DETECTED` (3085), `BLOCK_DEPTH_EXCEEDED` (3086) y `BLOCK_EXPANSION_TOO_LARGE` (3087). Un bloque
ausente en una vista previa es 409 (en el guardado de D7 es 400). `{{>` en el asunto sigue siendo 400 `INVALID_TEMPLATE_SYNTAX`.

### Extensibilidad (no se construye)

- Otros canales con limites (push, WhatsApp) agregan un calculador junto a `SmsSegmenter` y un campo opcional en la respuesta.
- D6 (versionado) reutiliza `PreviewTemplateUseCase`. D7 (bloques) ya queda dentro del renderizador: la vista previa lo hereda sin cambio.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` | `SmsSegmenter` con los escenarios 4 a 7 de la Historia 2 (textos reales, 70/71 UCS-2, 160/161 GSM, `€`/`{`, emoji, vacio); `PreviewTemplateUseCase` (faltantes, valor con `{{x}}`, canal no SMS, sintaxis invalida, limites, propagacion de `BLOCK_*` como 409) con `StepVerifier`; reglas de `sampleValues` en `Template` |
| Integracion adaptador | `TemplateMongoRepositoryTest` ampliado: `sampleValues` ida y vuelta y documento antiguo sin el campo |
| E2E | `TemplatePreviewE2ETest` (`RANDOM_PORT`, Mongo): guardar ejemplos, leerlos, vista previa SMS de 1 y 2 segmentos con textos reales, igualdad con `/template-renders`, un bloque inexistente en 409, dos tenants |
| Rendimiento | SC-004 con `Duration` < 200 ms, incluido `"{{" + " ".repeat(19990)` |
| Privacidad | SC-005: logs y Mongo tras una vista previa con valor centinela |
| Seguridad de ruta | `POST /template-previews` permitido a los tres roles de B2 y denegado a CLIENTE y OPERADOR |
| Arquitectura | `NoDirectTemplateRenderTest`, creada por D2 en `infrastructure/.../architecture` (patron de `NoHardcodedChannelTest`): prohibe `TemplateEngine.render` fuera de `RenderTemplateService`. D3 y D7 solo añaden casos a esta clase, no crean otra. Mas `HexagonalArchitectureTest` y `ModularityTests` |

## Cambios de contrato requeridos (los aplica Contrato, no este agente)

1. Ruta nueva `/template-previews` `POST`, `operationId: previewTemplate`, `x-status: planned`, `x-allowed-roles: [EDITOR_PLANTILLAS,
   ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA]` (sin `x-minimum-role`), respuestas 200, 400 (`INVALID_TEMPLATE_SYNTAX`,
   `TEMPLATE_LIMIT_EXCEEDED`), 409 (`BLOCK_NOT_FOUND`, `BLOCK_ARCHIVED`, `BLOCK_CYCLE_DETECTED`, `BLOCK_DEPTH_EXCEEDED`,
   `BLOCK_EXPANSION_TOO_LARGE`), 401, 403, 503, 500; sin 404 ni 422.
2. Esquema `PreviewTemplateRequest`: `channel` (string, requerido), `subject` (nulable, max 200), `body` (1-20000), `variables`
   (max 50, `TemplateVariable`), `values` (objeto de strings, `maxProperties: 50`, valores `maxLength: 2000`).
3. Esquemas `TemplatePreviewResponse` (`subject`, `body`, `missingVariables[]`, `characterCount`, `subjectCharacterCount`, `sms`
   nulable) y `SmsSegmentation` (`encoding` enum `GSM7|UCS2`, `units`, `unitsPerSegment`, `segments`, `exceedsSingleSegment`).
4. `sampleValues` (objeto de strings, `maxProperties: 50`, valores `maxLength: 2000`, opcional) en `CreateTemplateRequest`,
   `UpdateTemplateRequest` y `TemplateResponse` (en la respuesta, requerido y `{}` por defecto). Descripcion: datos ficticios; es lo unico
   que la plantilla guarda de valores. Roles: los de `PUT /templates` en B2.
5. Fila nueva en la tabla de rutas (cabecera del YAML) para HU2-126.
6. Sin codigos de error nuevos: se reutilizan NTF-1007, 1008, 1009 (D1) y 3080, 3085, 3086, 3087, 3088 (D7).

## Riesgos y dependencias

- Bloqueo por D1 integrada (incluido `RenderTemplateService`) y por el YAML de Contrato (Principio II).
- D7 y D3 deben adoptar la forma unica de `ResolvedTemplate`; si no, D2 no compila contra ambas. Cambio en sus specs, pendiente.
- Compartido con D1: el cambio a `Template`, a sus tres DTOs y al documento Mongo; los limites de D1 no cambian (se reusan los de `values`).
- Roles: `ADMINISTRADOR_APLICACION` puede previsualizar pero no guardar `sampleValues` (tabla de B2). Confirmar con B2 antes de `tasks`.
- La UI debe mostrar el texto devuelto como texto plano (D1 FR-014); mostrarlo como HTML abriria XSS.
- Tabla GSM 03.38 sin variantes por idioma (Turkish/Spanish shift tables): fuera de alcance; el calculo es conservador (mas segmentos).
  Asimetria declarada con el cuerpo directo de HU2-112 (160 caracteres), ver D3.
