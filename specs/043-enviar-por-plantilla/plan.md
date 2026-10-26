# Implementation Plan: Enviar por templateId y variables (HU2-127)

**Branch**: `feature/HU2-127-enviar-por-plantilla` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion

**Version del plan**: 2 (correcciones de nivel 2 del 2026-10-25)

## Summary

`SendNotificationService` gana un modo de contenido por plantilla. Si la solicitud trae `templateId`, tras consultar duplicados
resuelve valores (D4, si hay `event`), llama al `RenderTemplateUseCase` unico de D1 (con bloques de D7) y construye el
`NotificationContent` con el texto renderizado. Sigue el flujo existente (esquema del canal, adjuntos, guarda de E10, persistencia,
evento, encolado). Sin `templateId` el flujo no cambia.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | **Testing**: JUnit 5, Mockito, `StepVerifier`, Testcontainers, `WebTestClient`
**Alcance**: backend; la UI (D3 "Backend y UI") solo necesita los campos nuevos del contrato.
**Orden de la cadena de plantillas**: D2, D7, D3, D4, D5 (ver Riesgos: conflicto con la dependencia de D3 sobre D4).

## Constitution Check (2.0.1)

- I Hexagonal: la logica va en `core` y depende de puertos de entrada (`RenderTemplateUseCase`, `ResolveTemplateValuesUseCase`), sin Spring. REST y Mongo solo en `infrastructure`. Cumple.
- II Contrato primero: no hay ruta nueva, pero cambia `SendNotificationRequest`. El YAML lo edita Contrato y el DTO se escribe tras su merge (ver "Cambios de contrato"). Cumple con dependencia.
- III Sin comentarios en codigo nuevo. Cumple.
- IV E2E `TemplateSendE2ETest` (SC-001 a SC-004, SC-008), unitarias con `StepVerifier`, `Duration` en SC-005. Cobertura >=80 % lineas / >=70 % ramas.
- V Commits de una linea. VI Plan aprobado antes de `tasks`.
- VII Excepcion: wiring de `UseCaseConfig` (nuevos argumentos de `SendNotificationService`) y bloqueo por el YAML. Dueno: Equipo de backend. Fecha: 2026-12-15.
- VIII Confiabilidad: el render ocurre antes de persistir; despues de aceptar se usa solo el texto persistido. Consumidores RabbitMQ: no se tocan.

## Diseno

### core

- **Renderizador unico** (FR-014): `port/in/RenderTemplateUseCase` (D1) gana `Mono<ResolvedTemplate> resolve(TenantId, TemplateId, ChannelType expected, Map<String,String> values, RenderScope scope)`.
  `render` delega en `resolve`. `resolve` valida el canal antes de sustituir (`TemplateChannelMismatchException`, NTF-1012) y aplica bloques de D7.
  `ResolvedTemplate(subject, body, channelType, category, critical)` es un record nuevo en `port/in`; `RenderedTemplate` no cambia (D2 sigue intacto).
  `Template.render(values)` no se extrae ni se llama fuera de `RenderTemplateService`. El lote no tiene regla de render propia.
- **RenderScope** (lote, FR-008, SC-005): record en `core` con mapas `TemplateId -> Mono<Template>`, `BlockId -> Mono<Block>` y `TemplateId -> Mono<MappingDefinition>`, con `.cache()`.
  `SendNotificationBatchService` crea un scope por lote y lo pasa a `send`; un envio unitario crea uno vacio. Asi plantilla, bloques y mapeo se leen una vez por lote.
- **Valores** (FR-013): si hay `event`, `ResolveTemplateValuesUseCase` (D4) produce `mapped`; los valores finales son `mapped` con `templateValues` encima (el explicito gana). Sin `event`, solo `templateValues`.
- **`SendNotificationService.send`**, tras `findActiveRoute` y con `templateId`:
  1. exclusividad (FR-002) -> `TemplateContentConflictException` (NTF-1011);
  2. `findByTenantAndExternalId`; si existe, duplicado sin renderizar (FR-007);
  3. valores (D4 si hay `event`, memoizado en scope) y `resolve(...)` (canal, render);
  4. si el canal es WHATSAPP, consultar el punto de extension de D5 (`ProviderTemplateLinkPort`, FR-015): con vinculo, el contenido es `providerTemplate`; sin vinculo, texto libre;
  5. si es SMS, `SmsSegmenter` (D2) debe dar un solo segmento; si no, 400 con el tope vigente del canal, sin truncar (FR-005);
  6. construir el contenido y seguir con `AttachmentPolicy`, `ContentSchemaValidator`, adjuntos y aceptacion.
  Sin `templateId` se ejecuta el camino actual sin cambios.
- **Referencia guardada** (FR-009): `NotificationContent` gana `templateReference` opcional (`templateId`, `category`, `critical`). D3 es duena de esa referencia; F1 solo anade `recipientIdentityId` y lee la categoria.
- **Lote** (`SendNotificationBatchService`): cada elemento llama a `send` con el scope. Los errores de plantilla de un elemento se traducen a `REJECTED` con
  `rejectionReason` = `<NTF-codigo> <nombre>[: vars]`; los demas errores siguen su tratamiento actual.
- **Excepciones nuevas** en `exception/`: `TemplateContentConflictException` (NTF-1011), `TemplateChannelMismatchException` (NTF-1012). Las demas son de D1.
- **Metricas** (FR-012) por el `MetricsPort` existente: `templateSendAccepted(channel)` y `templateSendRejected(channel, cause)`, sin valores (FR-010).

### infrastructure

- `adapter/in/rest`: `SendNotificationRequest` recibe `templateId`, `templateValues` y `event`. El compact constructor que exige cuerpo o `providerTemplate` pasa a exigir exactamente uno de los tres (FR-002). El mapeo de errores reutiliza el de D1 y agrega NTF-1011 y NTF-1012.
- `adapter/out/mongo`: el documento de notificacion guarda `templateId`, `templateCategory`, `templateCritical` (nulos si no hubo plantilla); nunca `templateValues` ni `event`. Documentos antiguos: nulos, sin migracion.
- Puntos de extension: `ProviderTemplateLinkPort` (D5) con adaptador que devuelve vacio hasta que D5 lo implemente. Limite: vinculo siempre vacio; upgrade: D5 (dueno: Equipo de backend).
- `RouteAuthorizationPolicy` y `protected-resources.json`: sin cambios (la ruta ya es CLIENTE).
- Excepcion VII (wiring): `UseCaseConfig` inyecta `RenderTemplateUseCase`, `ResolveTemplateValuesUseCase` y el puerto de D5. Dueno: Equipo de backend. Fecha: 2026-12-15.

### Interacciones

- E10 (032): la guarda de formato sigue donde la deje esa historia, antes de persistir. Prueba unitaria: direccion invalida responde 400 aunque la plantilla sea valida.
- E5 (036): sin cambios; el cifrado PUSH actua al persistir sobre token y `recipientId`.
- G2 (033): reenvio copia el contenido persistido; no se renderiza. E2E: archivar y reenviar.
- HU2-112: `providerTemplate` no cambia; la exclusividad se valida en el DTO y en el servicio.
- D2 (`SmsSegmenter`, sin cambios), D4 (`ResolveTemplateValuesUseCase`, memoizado en scope), D5 (vinculo plantilla propia - aprobada; aqui solo el punto de extension).
- Asimetria SMS: cuerpo directo con tope de 160 caracteres (HU2-112); renderizado con un segmento (D2). Declarada; alinear es decision posterior (dueno: backend).

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` (`StepVerifier`) | `SendNotificationServiceTest`: modo plantilla feliz, 422, 404, archivada, canal distinto antes de render, conflicto de contenido (incluye `event` sin `templateId`), duplicado sin render, SMS de dos segmentos, sin plantilla igual que antes |
| Unitaria precedencia | `event` y `templateValues` en conflicto: gana el explicito (SC-008) |
| Unitaria lote | elementos mixtos y orden; con Mockito `verify(times(1))` en plantilla, bloques y mapeo por `RenderScope`; 500 elementos con `Duration` < 200 ms (SC-005) |
| Arquitectura | nueva `NoDirectTemplateRenderTest` en `infrastructure/.../architecture` (mismo patron que `NoHardcodedChannelTest`): prohibe `TemplateEngine.render` fuera de `RenderTemplateService`; ademas `HexagonalArchitectureTest` y `ModularityTests` en verde |
| Integracion adaptador | `NotificationMongoRepositoryTest`: persiste `templateId`/categoria/critical, no persiste valores ni `event`, lee documentos antiguos |
| E2E | `TemplateSendE2ETest` (`RANDOM_PORT`, Mongo y RabbitMQ, `simulated`): SC-001 a SC-004, SC-008 (SMS de dos segmentos 400; precedencia), dos tenants, lote mixto, reenvio tras archivar |
| Privacidad | SC-006 con captura de logs y lectura del documento |
| Regresion | los E2E de envio y lote existentes sin cambios (SC-007) |

## Cambios de contrato requeridos

`api-notificaciones.yaml` (lo edita Contrato; no se toca aqui). Todo con `x-status: planned` hasta implementar:

1. `SendNotificationRequest`: `templateId` (uuid, nullable), `templateValues` (`additionalProperties: string`, `maxProperties: 50`, valor `maxLength: 2000`, nullable) y `event` (objeto libre, nullable, solo con `templateId`).
2. Quitar `body` de `required`; modelar con `oneOf` la exclusividad `body` | `providerTemplate` | `templateId`; documentar que `subject` y `templateValues`/`event` no van sin `templateId`.
3. `POST /notifications` y lote: respuestas `404` (`TEMPLATE_NOT_FOUND`) y `422` (`MISSING_VARIABLES` con `missingVariables`); ampliar el 400 (conflicto de contenido, canal distinto, limites, tope SMS) y el 409 (`TEMPLATE_ARCHIVED`).
4. `ErrorResponse.code`: agregar `NTF-1011` `TEMPLATE_CONTENT_CONFLICT` y `NTF-1012` `TEMPLATE_CHANNEL_MISMATCH` al enum y a la descripcion.
5. `BatchItemResult.rejectionReason`: documentar que un fallo de plantilla rechaza solo el elemento y el texto empieza por el codigo NTF y lleva las variables faltantes.
6. `GET /notifications/detail` no expone la referencia de plantilla; solo se persiste. Sin cambio de esquema de respuesta.

## Riesgos y dependencias

- **Conflicto de orden (decision pendiente)**: el orden de integracion es D2, D7, D3, D4, D5, pero D3 invoca `ResolveTemplateValuesUseCase` de D4. Al integrar D3, ese puerto aun no existe. Opciones:
  (a) integrar la parte de `event` de D4 antes de D3 (recomendado; se cambia el orden solo para ese puerto);
  (b) D3 define el puerto con implementacion vacia hasta D4 (limite: `event` sin efecto; upgrade: D4; dueno: Equipo de backend; fecha: 2026-12-15).
- Depende de D1 (HU2-119, `RenderTemplateUseCase`, `Template`, errores de plantilla), D2 (`SmsSegmenter`, `RenderedTemplate`) y HU2-112 (`providerTemplate`). Ninguna esta en la base de la rama hoy; esta historia se implementa despues de ellas.
- Toca los mismos archivos que E10 (`SendNotificationService`), G2 (reenvio) y E5 (documento de notificacion): integrar en orden y resolver conflictos al rebasar.
- Cambio compartido con D1: `resolve`, `ResolvedTemplate` y `RenderScope`. Quitar `body` de `required` es visible para generadores de cliente.
