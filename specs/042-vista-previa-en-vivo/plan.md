# Implementation Plan: Vista previa en vivo de plantillas (HU2-126)

**Branch**: `feature/HU2-126-vista-previa-en-vivo` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion | **Version del plan**: 2 (correcciones aprobadas del lote, 2026-10-25)

## Summary

Se agrega un endpoint de vista previa sin estado, `POST /template-previews`, que pasa por el renderizador unico de `core` (no por
`TemplateEngine` directo), un calculador de segmentos SMS puro (`SmsSegmenter`, compartido con D3) y un campo `sampleValues` en el
agregado `Template`. Sin puertos de salida nuevos.

## Dependencia

Todo el codigo se apoya en HU2-119 ([plan 035](../035-plantillas-variables/plan.md)): `Template`, `TemplateEngine`, `RenderTemplateUseCase`,
`TemplateRepository`, `TemplateController` y documento Mongo `templates`. D2 no se implementa hasta que D1 este integrada en `main`.

Renderizador unico: lo forman `RenderTemplateUseCase` (D1), la expansion de bloques (D7) y `ResolvedTemplate` con `resolve` (D3). D2 no
modifica `TemplateEngine` ni la expansion de bloques.

**Decision pendiente (bloquea `tasks`)**: el renderizador de D1 y D3 resuelve por `templateId`; la vista previa recibe contenido sin
guardar y necesita ademas un modo que devuelva faltantes en vez de 422. Opciones:

- A (recomendada): D2 añade al servicio `RenderTemplateUseCase` una entrada por contenido y el modo tolerante; D3 y D7 la reutilizan
  dentro de `resolve`. Rompe el orden D2, D7, D3, D4, D5 solo en que D2 toca el servicio (no el motor).
- B: D2 espera a que D3 entregue la entrada por contenido. Se mantiene "D2 solo añade `sampleValues` y la ruta", pero D2 no puede
  integrarse antes que D3.

El usuario decide A o B. Mientras, la cadena de integracion es D2, D7, D3, D4, D5 (lote, seccion 4).

## Constitution Check (2.0.1)

- I Hexagonal: calculo y caso de uso en `core` sin Spring; REST y Mongo solo en `infrastructure`. Cumple.
- II Contrato primero: 1 ruta literal nueva y 4 esquemas ampliados (abajo). Las tareas del controller quedan bloqueadas hasta que Contrato
  fusione el YAML.
- III Sin comentarios. IV E2E explicito (`TemplatePreviewE2ETest`), dos tenants, `Duration` para SC-004, cobertura 80/70.
- V Commits de una linea. VI Plan aprobado antes de `tasks`. Consumidores RabbitMQ: ninguno. Sin excepcion.
- VII Excepcion heredada de D1: wiring en `UseCaseConfig`. Dueno: Equipo de backend. Fecha: 2026-12-15.

## Diseno

### core

- `domain/template/SmsSegmenter` (utilitaria estatica, como `TemplateEngine`): `segment(String) -> SmsSegmentation`. Tabla GSM 03.38
  basica y de extension como constantes; un solo recorrido por puntos de codigo. Basica incluye `é è ù ì ò à É ñ Ñ ü Ü ä ö ¡ ¿ Ç`; no incluye
  `á í ó ú`, sus mayusculas ni `ç` minuscula. Extension: `^ { } \ [ ] ~ | €` y salto de pagina (2 unidades cada una). Si algun punto de
  codigo esta fuera de ambas tablas, UCS-2 con unidades UTF-16 (70 y 67).
- `domain/template/SmsSegmentation` (record: encoding, units, unitsPerSegment, segments, exceedsSingleSegment) y enum `SmsEncoding`.
- `domain/template/TemplatePreview` (record: subject, body, missingVariables, characterCount, subjectCharacterCount, `SmsSegmentation sms`
  nulable).
- Puerto de entrada `PreviewTemplateUseCase`: `preview(PreviewCommand) -> Mono<TemplatePreview>`. El servicio delega el renderizado en el
  renderizador unico (sintaxis invalida lanza `InvalidTemplateSyntaxException`, bloques de D7, faltantes); despues cuenta caracteres y,
  si el canal es `SMS` (comparacion con el valor del catalogo en mayusculas), segmenta el cuerpo renderizado. Valida los limites de D1
  (reusa su verificador). No llama a `TemplateEngine` directamente.
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

### Extensibilidad (no se construye)

- Otros canales con limites (push, WhatsApp) agregan un calculador junto a `SmsSegmenter` y un campo opcional en la respuesta.
- D6 (versionado) reutiliza `PreviewTemplateUseCase`. D7 (bloques) ya queda dentro del renderizador unico: la vista previa lo hereda sin cambio.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` | `SmsSegmenter` con los escenarios 4 a 7 de la Historia 2 (textos reales, 70/71 UCS-2, 160/161 GSM, `€`/`{`, emoji, vacio); `PreviewTemplateUseCase` (faltantes, valor con `{{x}}`, canal no SMS, sintaxis invalida, limites) con `StepVerifier`; reglas de `sampleValues` en `Template` |
| Integracion adaptador | `TemplateMongoRepositoryTest` ampliado: `sampleValues` ida y vuelta y documento antiguo sin el campo |
| E2E | `TemplatePreviewE2ETest` (`RANDOM_PORT`, Mongo): guardar ejemplos, leerlos, vista previa SMS de 1 y 2 segmentos con textos reales, igualdad con `/template-renders`, dos tenants |
| Rendimiento | SC-004 con `Duration` < 200 ms, incluido `"{{" + " ".repeat(19990)` |
| Privacidad | SC-005: logs y Mongo tras una vista previa con valor centinela |
| Seguridad de ruta | `POST /template-previews` permitido a los tres roles de B2 y denegado a CLIENTE y OPERADOR |
| Arquitectura | `NoDirectTemplateRenderTest` (en `infrastructure`, mismo patron que `NoHardcodedChannelTest`): prohibe `TemplateEngine.render` fuera del renderizador unico. D3 reutiliza esta prueba, no la duplica. Mas `HexagonalArchitectureTest` y `ModularityTests` |

## Cambios de contrato requeridos (los aplica Contrato, no este agente)

1. Ruta nueva `/template-previews` `POST`, `operationId: previewTemplate`, `x-status: planned`, `x-allowed-roles: [EDITOR_PLANTILLAS,
   ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA]` (sin `x-minimum-role`), respuestas 200, 400 (`INVALID_TEMPLATE_SYNTAX`,
   `TEMPLATE_LIMIT_EXCEEDED`), 401, 403, 503, 500; sin 404, 409 ni 422.
2. Esquema `PreviewTemplateRequest`: `channel` (string, requerido), `subject` (nulable, max 200), `body` (1-20000), `variables`
   (max 50, `TemplateVariable`), `values` (objeto de strings, `maxProperties: 50`, valores `maxLength: 2000`).
3. Esquemas `TemplatePreviewResponse` (`subject`, `body`, `missingVariables[]`, `characterCount`, `subjectCharacterCount`, `sms`
   nulable) y `SmsSegmentation` (`encoding` enum `GSM7|UCS2`, `units`, `unitsPerSegment`, `segments`, `exceedsSingleSegment`).
4. `sampleValues` (objeto de strings, `maxProperties: 50`, valores `maxLength: 2000`, opcional) en `CreateTemplateRequest`,
   `UpdateTemplateRequest` y `TemplateResponse` (en la respuesta, requerido y `{}` por defecto). Descripcion: datos ficticios; es lo unico
   que la plantilla guarda de valores. Roles: los de `PUT /templates` en B2.
5. Fila nueva en la tabla de rutas (cabecera del YAML) para HU2-126.
6. Sin codigos de error nuevos: se reutilizan NTF-1007, 1008 y 1009 (`INVALID_TEMPLATE_SYNTAX`, `UNDECLARED_TEMPLATE_VARIABLE`,
   `TEMPLATE_LIMIT_EXCEEDED`).

## Riesgos y dependencias

- Bloqueo por D1 integrada, por la decision pendiente del renderizador y por el YAML de Contrato (Principio II).
- Compartido con D1: el cambio a `Template`, a sus tres DTOs y al documento Mongo; los limites de D1 no cambian (se reusan los de `values`).
- Roles: `ADMINISTRADOR_APLICACION` puede previsualizar pero no guardar `sampleValues` (tabla de B2). Confirmar con B2 antes de `tasks`.
- La UI debe mostrar el texto devuelto como texto plano (D1 FR-014); mostrarlo como HTML abriria XSS.
- Tabla GSM 03.38 sin variantes por idioma (Turkish/Spanish shift tables): fuera de alcance; el calculo es conservador (mas segmentos).
  Asimetria declarada con el cuerpo directo de HU2-112 (160 caracteres), ver D3.
