# Implementation Plan: Enviar por templateId y variables (HU2-127)

**Branch**: `feature/HU2-127-enviar-por-plantilla` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion

**Version del plan**: 3 (segunda pasada de correcciones del 2026-10-25)

## Summary

`SendNotificationService` gana un modo plantilla: tras duplicados, resuelve valores (D4, si hay `event`), llama al `RenderTemplateUseCase` unico de D1
y construye el `NotificationContent` con el texto renderizado. El resto del flujo no cambia.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | **Testing**: JUnit 5, Mockito, `StepVerifier`, Testcontainers, `WebTestClient`
**Alcance**: backend; la UI solo necesita los campos nuevos del contrato.
**Cadena de integracion**: D2, D7, D4 (parte `event`, dependencia previa), D3, D5.

## Constitution Check (2.0.1)

- I Hexagonal: `core` depende de puertos de entrada (`RenderTemplateUseCase`, `ResolveTemplateValuesUseCase`), sin Spring. Cumple.
- II Contrato primero: cambia `SendNotificationRequest`; el YAML lo edita Contrato y el DTO se escribe tras su merge. Cumple con dependencia.
- III Sin comentarios en codigo nuevo. Cumple.
- IV `TemplateSendE2ETest`, unitarias con `StepVerifier`, `Duration` en SC-005. Cobertura >=80 % lineas / >=70 % ramas.
- V Commits de una linea. VI Plan aprobado antes de `tasks`.
- VII Excepcion: wiring de `UseCaseConfig` y bloqueo por el YAML. Dueno: Equipo de backend. Fecha: 2026-12-15.
- VIII Confiabilidad: el render ocurre antes de persistir; despues de aceptar se usa solo el texto persistido. Consumidores RabbitMQ: no se tocan.

## Diseno

### core

- **Renderizador unico** (FR-014): `port/in/RenderTemplateUseCase` (D1) gana `Mono<ResolvedTemplate> resolve(TenantId, TemplateId, ChannelType expected, Map<String,String> values, RenderScope scope)`.
  `render` delega en `resolve`. `resolve` valida el canal antes de sustituir (NTF-1012) y aplica bloques de D7.
  `ResolvedTemplate(subject, body, channelType, category, critical, variables, blockRefs)` lo define `RenderTemplateService` de D1; D7 no define otro.
  `Template.render` solo se llama dentro de `RenderTemplateService`.
- **RenderScope** (lote, FR-008, SC-005): record en `core` con mapas `TemplateId -> Mono<Template>`, `BlockId -> Mono<Block>` y `TemplateId -> Mono<MappingDefinition>`, con `.cache()`.
  El lote crea un scope y lo pasa a `send`; un envio unitario crea uno vacio.
- **Valores** (FR-013): con `event`, `ResolveTemplateValuesUseCase` (D4) produce `mapped`; los finales son `mapped` con `templateValues` encima (el explicito gana).
- **`SendNotificationService.send`**, tras `findActiveRoute` y con `templateId`:
  1. exclusividad (FR-002) -> `TemplateContentConflictException` (NTF-1011);
  2. `findByTenantAndExternalId`; si existe, duplicado sin renderizar (FR-007);
  3. valores y `resolve(...)`: canal (NTF-1012), render y bloques (409 `BLOCK_*` y 422 de bloque, como en D7);
  4. si la categoria es SEGURIDAD y el rol no es ADMINISTRADOR_APLICACION ni ADMINISTRADOR_PLATAFORMA -> `TemplateCategoryForbiddenException` (403 `INSUFFICIENT_ROLE`, NTF-2002) (FR-016);
  5. si es SMS, `SmsSegmenter` (D2) debe dar un segmento; si no, `INVALID_CONTENT` (NTF-1001), sin truncar (FR-005);
  6. construir el contenido y seguir con `AttachmentPolicy`, `ContentSchemaValidator`, adjuntos y aceptacion.
  WHATSAPP no se consulta al aceptar (FR-015). Sin `templateId`, camino actual.
- **Referencia guardada** (FR-009): `NotificationContent` gana `templateReference` opcional (`templateId`, `category`, `critical`). D3 es duena; F1 solo anade `recipientIdentityId`.
- **Despacho WHATSAPP** (FR-015): el despacho, si el contenido vino de `templateId` y el canal es WHATSAPP, consulta `ProviderTemplateLinkPort` (D5). Con vinculo publicado usa `providerTemplate`; sin vinculo, fallo permanente con el mecanismo de despacho existente.
- **Lote** (`SendNotificationBatchService`): cada elemento llama a `send` con el scope. Los errores de plantilla de un elemento se traducen a `REJECTED` con `rejectionReason` = `<NTF-codigo> <nombre>[: vars]`.
- **Excepciones nuevas** en `exception/`: `TemplateContentConflictException` (NTF-1011), `TemplateChannelMismatchException` (NTF-1012), `TemplateCategoryForbiddenException` (403, NTF-2002).
- **Metricas** (FR-012) por `MetricsPort`: `templateSendAccepted(channel)` y `templateSendRejected(channel, cause)`, sin valores (FR-010).

### infrastructure

- `adapter/in/rest`: `SendNotificationRequest` recibe `templateId`, `templateValues` y `event`. El compact constructor pasa a exigir exactamente uno de `body`, `providerTemplate`, `templateId` (FR-002). Mapeo de errores: los de D1 mas NTF-1011, NTF-1012, NTF-1001 y 403.
- `adapter/out/mongo`: el documento guarda `templateId`, `templateCategory`, `templateCritical` (nulos sin plantilla); nunca `templateValues` ni `event`. Documentos antiguos: nulos, sin migracion.
- `ProviderTemplateLinkPort` (D5) con adaptador que devuelve vacio hasta D5. Limite: todo envio WHATSAPP por `templateId` falla en despacho hasta D5. Dueno: Equipo de backend; fecha: 2026-12-15.
- Precondicion de roles: el principal de sesion llega por B2 (spec 039); hoy el PDP solo entrega CLIENTE, asi que la categoria SEGURIDAD responde 403 en produccion hasta entonces.
- `RouteAuthorizationPolicy` y `protected-resources.json`: sin cambios en esta historia.
- Excepcion VII (wiring): `UseCaseConfig` inyecta `RenderTemplateUseCase`, `ResolveTemplateValuesUseCase` y `ProviderTemplateLinkPort`.

### Interacciones

- E10 (032): la guarda de formato sigue antes de persistir. Prueba: direccion invalida responde 400 aunque la plantilla sea valida.
- E5 (036): sin cambios; el cifrado PUSH actua al persistir sobre token y `recipientId`.
- G2 (033): reenvio copia el contenido persistido; no renderiza. E2E: archivar y reenviar.
- HU2-112: `providerTemplate` no cambia; la exclusividad se valida en DTO y servicio.
- D2 (`SmsSegmenter`), D4 (memoizado en scope), D5 (`ProviderTemplateLinkPort`), B2 (roles).
- Asimetria SMS: cuerpo directo con 160 caracteres (HU2-112); renderizado con un segmento (D2). Declarada; alinear es decision posterior.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` (`StepVerifier`) | `SendNotificationServiceTest`: feliz, 422, 404, archivada, canal distinto antes de render, conflicto (incluye `event` sin `templateId`), duplicado sin render, SMS de dos segmentos (`INVALID_CONTENT`), SEGURIDAD con CLIENTE (403) y con ADMINISTRADOR_APLICACION (ok), error de bloque (409/422), sin plantilla igual que antes |
| Unitaria precedencia | `event` y `templateValues` en conflicto: gana el explicito (SC-008) |
| Unitaria lote | elementos mixtos y orden; `verify(times(1))` en plantilla, bloques y mapeo por `RenderScope`; 500 elementos con `Duration` < 200 ms (SC-005) |
| Unitaria metricas (FR-012) | `MetricsPort` mockeado: `templateSendAccepted(channel)` y `templateSendRejected(channel, cause)` para `missing`, `archived`, `notfound`, `mismatch`; ningun argumento contiene valores |
| Unitaria despacho WHATSAPP | sin vinculo: fallo permanente; con vinculo (stub): `providerTemplate` |
| Arquitectura | `NoDirectTemplateRenderTest` la crea D2 y D3 solo anade casos; `HexagonalArchitectureTest` y `ModularityTests` en verde |
| Integracion adaptador | `NotificationMongoRepositoryTest`: persiste `templateId`/categoria/critical, no persiste valores ni `event`, lee documentos antiguos |
| E2E | `TemplateSendE2ETest` (`RANDOM_PORT`, Mongo y RabbitMQ, `simulated`): SC-001 a SC-004, SC-008, 403 de SEGURIDAD con CLIENTE, WHATSAPP sin vinculo (202 y fallo en despacho), errores de bloque, dos tenants, lote mixto, reenvio tras archivar |
| Privacidad | SC-006 con captura de logs y lectura del documento |
| Regresion | E2E de envio y lote existentes sin cambios (SC-007) |

## Cambios de contrato requeridos

`api-notificaciones.yaml` (lo edita Contrato; no se toca aqui). Todo con `x-status: planned` hasta implementar:

1. `SendNotificationRequest`: `templateId` (uuid, nullable), `templateValues` (`additionalProperties: string`, `maxProperties: 50`, valor `maxLength: 2000`, nullable) y `event` (objeto libre, nullable, solo con `templateId`).
2. Quitar `body` de `required`; `oneOf` para la exclusividad `body` | `providerTemplate` | `templateId`; documentar que `subject`, `templateValues` y `event` no van sin `templateId`.
3. `POST /notifications` y lote: `404` (`TEMPLATE_NOT_FOUND`), `422` (`MISSING_VARIABLES` con `missingVariables`, y 422 de bloque), `409` (`TEMPLATE_ARCHIVED` y `BLOCK_*`), `403` (SEGURIDAD, `INSUFFICIENT_ROLE` NTF-2002), `400` (conflicto de contenido, canal distinto, limites, `INVALID_CONTENT` NTF-1001).
4. `ErrorResponse.code`: agregar `NTF-1011` `TEMPLATE_CONTENT_CONFLICT` y `NTF-1012` `TEMPLATE_CHANNEL_MISMATCH`; el 403 reutiliza `INSUFFICIENT_ROLE` (NTF-2002).
5. `BatchItemResult.rejectionReason`: un fallo de plantilla rechaza solo el elemento y el texto empieza por el codigo NTF y lleva las variables faltantes.
6. `POST /notifications` y lote documentan roles CLIENTE, ADMINISTRADOR_APLICACION y ADMINISTRADOR_PLATAFORMA.
7. `GET /notifications/detail` no expone la referencia de plantilla.

## Riesgos y dependencias

- **Dependencia previa**: D4 (parte `event`, `ResolveTemplateValuesUseCase`) se integra antes que D3 (decision del usuario). D3 no define ni stubs ese puerto.
- Depende de D1 (HU2-119), D2, HU2-112, D5 y B2 (roles, spec 039). Ninguna esta en la base de la rama hoy.
- **Roles (B2)**: sin roles reales, SEGURIDAD responde 403 en produccion hasta que B2 entregue el principal.
- Toca los mismos archivos que E10 (`SendNotificationService`), G2 (reenvio) y E5 (documento de notificacion): integrar en orden y resolver conflictos al rebasar.
- Cambio compartido con D1: `resolve`, `ResolvedTemplate`, `RenderScope`. Quitar `body` de `required` es visible para generadores de cliente.
