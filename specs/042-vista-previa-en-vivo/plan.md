# Implementation Plan: Vista previa en vivo de plantillas (HU2-126)

**Branch**: `feature/HU2-126-vista-previa-en-vivo` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion | **Version del plan**: 1

## Summary

Se agrega un caso de uso de vista previa sin estado sobre el `TemplateEngine` de D1, un calculador de segmentos SMS puro en `core` y un
campo `sampleValues` en el agregado `Template`. Un endpoint nuevo, `POST /template-previews`. Sin puertos de salida nuevos.

## Dependencia

Todo el codigo se apoya en HU2-119 ([plan 035](../035-plantillas-variables/plan.md)): `Template`, `TemplateEngine`, `TemplateRepository`,
`TemplateController`, documento Mongo `templates` y `RouteAuthorizationPolicy`. D2 no se implementa hasta que D1 este integrada en `main`;
el cambio sobre `Template` se rebasa sobre ella.

## Constitution Check (2.0.1)

- I Hexagonal: calculo y caso de uso en `core` sin Spring; REST y Mongo solo en `infrastructure`. Cumple.
- II Contrato primero: 1 ruta literal nueva y 4 esquemas ampliados (abajo). Las tareas del controller quedan bloqueadas hasta que Contrato
  fusione el YAML.
- III Sin comentarios. IV E2E explicito (`TemplatePreviewE2ETest`), dos tenants, `Duration` para SC-004, cobertura 80/70.
- V Commits de una linea. VI Plan aprobado antes de `tasks`. Consumidores RabbitMQ: ninguno. Sin excepcion.
- VII Excepcion heredada de D1: wiring en `UseCaseConfig`. Dueno: Equipo de backend. Fecha: 2026-12-15.

## Diseno

### core

- `domain/template/SmsSegmenter` (utilitaria estatica, como `TemplateEngine`): `segment(String) -> SmsSegmentation`. Tabla de caracteres
  GSM 03.38 basica y de extension como constantes; un solo recorrido por puntos de codigo; si algun punto de codigo esta fuera de ambas
  tablas, UCS-2 con unidades UTF-16.
- `domain/template/SmsSegmentation` (record: encoding, units, unitsPerSegment, segments, exceedsSingleSegment) y enum `SmsEncoding`.
- `domain/template/TemplatePreview` (record: subject, body, missingVariables, characterCount, subjectCharacterCount, `SmsSegmentation sms`
  nulable).
- Puerto de entrada `PreviewTemplateUseCase`: `preview(PreviewCommand) -> Mono<TemplatePreview>`; el servicio hace: `detect` de asunto y
  cuerpo (lanza `InvalidTemplateSyntaxException`), faltantes = detectadas obligatorias sin valor no nulo, `render` de ambos, conteo, y
  segmentacion si el canal es `SMS` (comparacion con el valor del catalogo en mayusculas). Valida los limites de D1 (reusa su verificador).
- `Template`: campo `Map<String,String> sampleValues` (inmutable, por defecto vacio), validado en `create`/`update`: claves subconjunto de
  las detectadas, 50 entradas, 2000 caracteres. Sin puerto de salida nuevo; `TemplateRepository` ya persiste el agregado completo.

### infrastructure

- `adapter/in/rest`: `TemplatePreviewController` (`POST /template-previews`) y DTOs `PreviewTemplateRequest`/`TemplatePreviewResponse`;
  `TemplateController` y DTOs de D1 ganan `sampleValues`. El tenant se toma de la sesion aunque la vista previa no consulte datos (patron D1).
- `adapter/out/mongo`: el documento `templates` agrega `sampleValues` (mapa); lectura nula -> `{}`. Sin migracion ni indice nuevo.
- `RouteAuthorizationPolicy`: `POST /template-previews` -> CLIENTE, con su prueba de ruta. Recurso nuevo `/template-previews` en
  `protected-resources.json` de HU2-099.
- Logs: no se registra el cuerpo de la peticion ni `values`.

### Extensibilidad (no se construye)

- Otros canales con limites (push, WhatsApp) agregan un calculador junto a `SmsSegmenter` y un campo opcional en la respuesta.
- D6 (versionado) y D7 (bloques) reutilizan `PreviewTemplateUseCase`: D7 resolveria los bloques antes de llamar al motor.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` | `SmsSegmenter` (160/161, 70/71, extension 2 unidades, emoji, vacio, mezcla), `PreviewTemplateUseCase` (faltantes, valor con `{{x}}`, canal no SMS, sintaxis invalida, limites) con `StepVerifier`; reglas de `sampleValues` en `Template` |
| Integracion adaptador | `TemplateMongoRepositoryTest` ampliado: `sampleValues` ida y vuelta y documento antiguo sin el campo |
| E2E | `TemplatePreviewE2ETest` (`RANDOM_PORT`, Mongo): guardar ejemplos, leerlos, vista previa SMS de 1 y 2 segmentos, igualdad con `/template-renders`, dos tenants |
| Rendimiento | SC-004 con `Duration` < 200 ms, incluido `"{{" + " ".repeat(19990)` |
| Privacidad | SC-005: logs y Mongo tras una vista previa con valor centinela |
| Seguridad de ruta | rol de `POST /template-previews` |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` |

## Cambios de contrato requeridos (los aplica Contrato, no este agente)

1. Ruta nueva `/template-previews` `POST`, `operationId: previewTemplate`, `x-status: planned`, `x-minimum-role: CLIENTE`, respuestas
   200, 400 (`INVALID_TEMPLATE_SYNTAX`, `TEMPLATE_LIMIT_EXCEEDED`), 401, 403, 503, 500; sin 404, 409 ni 422.
2. Esquema `PreviewTemplateRequest`: `channel` (string, requerido), `subject` (nulable, max 200), `body` (1-20000), `variables`
   (max 50, `TemplateVariable`), `values` (objeto de strings, `maxProperties: 50`, valores `maxLength: 2000`).
3. Esquemas `TemplatePreviewResponse` (`subject`, `body`, `missingVariables[]`, `characterCount`, `subjectCharacterCount`, `sms`
   nulable) y `SmsSegmentation` (`encoding` enum `GSM7|UCS2`, `units`, `unitsPerSegment`, `segments`, `exceedsSingleSegment`).
4. `sampleValues` (objeto de strings, `maxProperties: 50`, valores `maxLength: 2000`, opcional) en `CreateTemplateRequest`,
   `UpdateTemplateRequest` y `TemplateResponse` (en la respuesta, requerido y `{}` por defecto). Descripcion: datos ficticios; es lo unico
   que la plantilla guarda de valores.
5. Fila nueva en la tabla de rutas (cabecera del YAML) para HU2-126.
6. Sin codigos de error nuevos: se reutilizan NTF-1007, 1008 y 1009 (`INVALID_TEMPLATE_SYNTAX`, `UNDECLARED_TEMPLATE_VARIABLE`,
   `TEMPLATE_LIMIT_EXCEEDED`).

## Riesgos y dependencias

- Bloqueo por D1 integrada y por el YAML de Contrato (Principio II).
- Compartido con D1: el cambio a `Template`, a sus tres DTOs y al documento Mongo; los limites de D1 no cambian (se reusan los de `values`).
  Si D1 aun no esta fusionada, D2 y D1 tocan los mismos archivos: serializar D1 antes que D2.
- La UI debe mostrar el texto devuelto como texto plano (D1 FR-014); mostrarlo como HTML abriria XSS.
- Tabla GSM 03.38 sin variantes por idioma (Turkish/Spanish shift tables): fuera de alcance; el calculo es conservador (mas segmentos).
