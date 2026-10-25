# Implementation Plan: Enviar por templateId y variables (HU2-127)

**Branch**: `feature/HU2-127-enviar-por-plantilla` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion

**Version del plan**: 1

## Summary

`SendNotificationService` gana un modo de contenido por plantilla. Si la solicitud trae `templateId`, tras consultar duplicados llama al
`RenderTemplateUseCase` de D1, construye el `NotificationContent` con el texto renderizado y sigue el flujo existente (esquema del canal,
adjuntos, guarda de E10, persistencia, evento, encolado). Sin `templateId` el flujo no cambia.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | **Testing**: JUnit 5, Mockito, `StepVerifier`, Testcontainers, `WebTestClient` |
**Alcance**: backend; la UI (D3 "Backend y UI") solo necesita los campos nuevos del contrato.

## Constitution Check (2.0.1)

- I Hexagonal: la logica va en `core` y depende del puerto de entrada `RenderTemplateUseCase` (sin Spring). REST y Mongo solo en `infrastructure`. Cumple.
- II Contrato primero: no hay ruta nueva, pero cambia `SendNotificationRequest`. El YAML lo edita Contrato; el DTO y el mapeo se escriben tras su merge
  (ver "Cambios de contrato requeridos"). Cumple con dependencia.
- III Sin comentarios en codigo nuevo. Cumple.
- IV E2E explicito `TemplateSendE2ETest` (SC-001 a SC-004), unitarias con `StepVerifier`, `Duration` en SC-005. Cobertura >=80 % / >=70 %.
- V Commits de una linea. VI Plan aprobado antes de `tasks`.
- VII Excepcion: wiring de `UseCaseConfig` (nuevo argumento de `SendNotificationService`) y bloqueo por el YAML. Dueno: Equipo de backend. Fecha: 2026-12-15.
- VIII Confiabilidad: el render ocurre antes de persistir; un fallo de plantilla no deja nada aceptado. Despues de aceptar, todo el camino usa el texto persistido.
- Consumidores RabbitMQ: no se toca ninguno. Sin excepcion.

## Diseno

### core

- `port/in/RenderTemplateUseCase` (D1): se agrega `Mono<ResolvedTemplate> resolve(TenantId, TemplateId, Map<String,String>)`, que hace lo que `render` y
  ademas devuelve `channelType`, `category` y `critical`. `render` delega en `resolve`. `ResolvedTemplate(subject, body, channelType, category, critical)` es un record
  nuevo en `port/in`; `RenderedTemplate` no cambia (D2 sigue intacto).
- `SendNotificationCommand`: `templateId` y `templateValues` opcionales junto al contenido actual. `NotificationContent` (de HU2-112) conserva su exclusion
  `body`/`providerTemplate`; el modo plantilla se resuelve antes de construirlo, asi que `NotificationContent` solo gana `templateReference` opcional (`templateId`,
  `category`, `critical`) para FR-009.
- `SendNotificationService.send`: tras `findActiveRoute`, si hay `templateId`:
  1. validar exclusividad (FR-002) -> `TemplateContentConflictException`;
  2. `findByTenantAndExternalId` -> si existe, devolver duplicado sin renderizar (FR-007);
  3. `resolve(...)`; si `channelType` difiere -> `TemplateChannelMismatchException`;
  4. construir el contenido con `subject`/`body` renderizados y seguir con `AttachmentPolicy`, `ContentSchemaValidator`, resolucion de adjuntos y aceptacion.
  Sin `templateId` se ejecuta el camino actual sin cambios.
- Lote (`SendNotificationBatchService`): cada elemento llama a `send`. Para FR-008 la lectura se memoiza por lote: `TemplateRepository.findById` se envuelve en un
  `Map<TemplateId, Mono<Template>>` con `.cache()` creado por lote, y el render por elemento usa un metodo de dominio `Template.render(values)` (extraido del
  `RenderTemplateService` de D1: chequeo de archivada, faltantes y sustitucion), de modo que `resolve` y el lote comparten la misma regla.
  Los errores de plantilla de un elemento se traducen a `REJECTED` con `rejectionReason` = `<NTF-codigo> <nombre>[: vars]`; los demas errores siguen su tratamiento actual.
- Excepciones nuevas en `exception/`: `TemplateContentConflictException` (NTF-1011), `TemplateChannelMismatchException` (NTF-1012). Las demas son de D1.
- Metricas (FR-012) por el `MetricsPort` existente: `templateSendAccepted(channel)` y `templateSendRejected(channel, cause)`.

### infrastructure

- `adapter/in/rest`: `SendNotificationRequest` (record) recibe `templateId` y `templateValues`; el compact constructor de HU2-112 que exige cuerpo o
  `providerTemplate` pasa a exigir exactamente uno de los tres. El mapeo de errores reutiliza el de D1 (404, 409, 422 con `missingVariables`, 400) y agrega 1011 y 1012.
  `POST /notifications` y el lote ganan las respuestas 404 y 422 en el contrato.
- `adapter/out/mongo`: el documento de notificacion guarda `templateId`, `templateCategory`, `templateCritical` (nulos si no hubo plantilla); nunca `templateValues`.
  Lectura de documentos antiguos sin los campos: nulos, sin migracion.
- `RouteAuthorizationPolicy` y `protected-resources.json`: sin cambios (la ruta ya es CLIENTE).
- Excepcion VII (wiring): `UseCaseConfig` inyecta `RenderTemplateUseCase` en `SendNotificationService`. Dueno: Equipo de backend. Fecha: 2026-12-15.

### Interacciones

- E10 (032): la guarda de formato sigue donde la deje esa historia, antes de persistir; D3 no la reordena. La prueba unitaria fija que una direccion invalida responde 400 aunque la plantilla sea valida.
- E5 (036): sin cambios. El cifrado PUSH actua al persistir sobre token y `recipientId`; el contenido renderizado va en claro como cualquier cuerpo.
- G2 (033): reintento y reenvio copian el contenido persistido; no se invoca `RenderTemplateUseCase`. Una prueba E2E archiva la plantilla y reenvia.
- HU2-112: `providerTemplate` no cambia; la exclusividad se valida en el DTO y en el servicio.
- D5: el vinculo plantilla propia - plantilla aprobada del proveedor lo resuelve D5; aqui WHATSAPP solo recibe texto libre.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` (`StepVerifier`) | `SendNotificationServiceTest`: modo plantilla feliz, 422, 404, archivada, canal distinto, conflicto de contenido, duplicado sin render, esquema del canal sobre el texto renderizado, sin plantilla igual que antes |
| Unitaria lote | elementos mixtos, orden, plantilla leida una vez, 500 elementos con `Duration` < 200 ms (SC-005) |
| Integracion adaptador | `NotificationMongoRepositoryTest`: persiste `templateId`/categoria, no persiste valores, lee documentos antiguos |
| E2E | `TemplateSendE2ETest` (`RANDOM_PORT`, Mongo y RabbitMQ, `simulated`): SC-001 a SC-004, dos tenants, lote mixto, reenvio tras archivar |
| Privacidad | SC-006 con captura de logs y lectura del documento |
| Regresion | los E2E de envio y lote existentes sin cambios (SC-007) |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` en verde |

## Cambios de contrato requeridos

`api-notificaciones.yaml` (lo edita Contrato; no se toca aqui):

1. `SendNotificationRequest`: agregar `templateId` (string, uuid, nullable) y `templateValues` (objeto `additionalProperties: string`, `maxProperties: 50`, valores `maxLength: 2000`, nullable).
2. `SendNotificationRequest.required`: quitar `body`; documentar y modelar con `oneOf` la exclusividad `body` | `providerTemplate` | `templateId`; documentar que `subject` no va con `templateId`.
   Revisar que no choque con la rama HU2-112 si ya agrego `providerTemplate` al esquema.
3. `POST /notifications`: agregar respuestas `404` (`TEMPLATE_NOT_FOUND`) y `422` (`MISSING_VARIABLES` con `missingVariables`); ampliar el 400 (conflicto de contenido, canal distinto, limites) y el 409 (`TEMPLATE_ARCHIVED`).
4. `ErrorResponse.code`: agregar `NTF-1011` `TEMPLATE_CONTENT_CONFLICT` y `NTF-1012` `TEMPLATE_CHANNEL_MISMATCH` al enum y a la descripcion.
5. `POST /notifications/batches` y `BatchItemResult.rejectionReason`: documentar que un fallo de plantilla rechaza solo el elemento y el texto lleva codigo y variables faltantes.
6. Si se acepta P1: la respuesta de detalle (`GET /notifications/detail`) no expone la referencia; solo se persiste. Sin cambio de esquema de respuesta.

## Riesgos y dependencias

- Depende de D1 (HU2-119, `RenderTemplateService`, `Template`, errores de plantilla) y de HU2-112 (`providerTemplate`); ninguna esta en la base de la rama hoy.
  Esta historia se implementa despues de que ambas esten integradas.
- Toca los mismos archivos que E10 (`SendNotificationService`), G2 (reenvio) y E5 (documento de notificacion): integrar en orden y resolver conflictos al rebasar.
- Cambio compartido con D1/D2: el metodo `resolve` y `ResolvedTemplate`. D2 no se ve afectada.
- Quitar `body` de `required` es un cambio visible para generadores de cliente; el comportamiento sin `templateId` es identico.
