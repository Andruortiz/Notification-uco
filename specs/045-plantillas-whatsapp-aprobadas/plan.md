# Implementation Plan: Plantillas de WhatsApp aprobadas (HU2-129)

**Branch**: `feature/HU2-129-plantillas-whatsapp-aprobadas` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion | **Version del plan**: 1

## Summary

Un agregado `TemplateProviderLink` en `core` (aprobacion + publicacion), un puerto de salida que lee la aprobacion al proveedor, casos
de uso de vincular, sincronizar, publicar, despublicar y consultar, una compuerta que consume el envio por plantilla (D3), un adaptador
Twilio, un adaptador Mongo y un barrido periodico. D1 no se modifica; se usa su repositorio de plantillas en lectura.

## Technical Context

Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | **Testing**: JUnit 5, Mockito, `StepVerifier`, Testcontainers (Mongo),
`WebTestClient`, servidor HTTP simulado para Twilio | **Depende de**: D1 (`Template`, `TemplateRepository`, errores 3050-3053), spec 028
(forma `HX...`, `TwilioWhatsAppProviderProperties`, `ProviderHttpClients`), E3/C3 solo como vecinos (ver Puntos compartidos).

## Constitution Check (2.0.1)

- I Hexagonal: reglas de estado, compuerta y casos de uso en `core` sin Spring; Twilio, Mongo, REST y el planificador en `infrastructure`. Cumple.
- II Contrato primero: 5 rutas literales, ids en query o cuerpo. El YAML lo edita Contrato; el controller se escribe tras su merge y hasta
  entonces sus tareas quedan bloqueadas. Cumple con dependencia.
- III Sin comentarios. IV E2E explicito (`WhatsAppTemplateApprovalE2ETest`), dos tenants, aserciones de `Duration` (SC-003), cobertura 80/70.
- V Commits de una linea. VI Plan aprobado antes de `tasks`. VII Excepciones en spec "Limites" con dueno y fecha (2026-12-15).
- VIII: la compuerta cerrada en despacho produce fallo permanente trazable, nunca perdida silenciosa. IX: log y metrica de FR-011, sin secretos.
- RabbitMQ: no se agrega ningun consumidor. La integracion con despacho es llamar la compuerta; sin excepcion de ack/DLQ.

## Diseno

### core

- `domain/template/link/`: `TemplateProviderLink` (agregado), `ProviderTemplateId` (valida `^HX[0-9a-fA-F]{32}$`), `ApprovalStatus`
  (`NO_ENVIADA`, `PENDIENTE`, `APROBADA`, `RECHAZADA`, `PAUSADA`), `PublicationStatus` (`BORRADOR`, `PUBLICADA`, `SUSPENDIDA`),
  `SyncError` (`PROVIDER_UNREACHABLE`, `PROVIDER_DISABLED`, `PROVIDER_ERROR`).
- Reglas en el agregado: `applyApproval(ProviderApproval, Instant)` (suspende si `PUBLICADA` y llega `RECHAZADA`/`PAUSADA`; no promueve
  `SUSPENDIDA`), `recordSyncFailure(SyncError, Instant)` (no toca la publicacion), `publish()` (exige `APROBADA`), `unpublish()`,
  `relink(...)` solo en `BORRADOR`; `version` incrementa en cada cambio.
- Puerto de salida `port/out/TemplateApprovalPort`: `String providerId()`; `Mono<ProviderApproval> fetch(ProviderTemplateId)`;
  `Optional<String> disabledReason()`. `ProviderApproval(status, rejectionReason, language)`; ausente en el proveedor ->
  `ProviderTemplateNotFoundException`; fallo de red/5xx -> `ProviderTemplateUnavailableException`.
- Puerto de salida `repository/TemplateProviderLinkRepository`: `save` (con `version`), `findByTemplate(tenant, templateId)`,
  `findByProviderTemplate(providerId, providerTemplateId)`, `claimDue(Instant now, Duration lease, int limit)` (actualizacion atomica
  condicionada que fija `leaseUntil`; devuelve los vinculos tomados). Todo con tenant salvo `claimDue` y la busqueda de unicidad.
- Lee la plantilla con el `TemplateRepository` de D1 (tenant incluido) para validar canal WHATSAPP y estado `ACTIVA`.
- Puertos de entrada: `LinkProviderTemplateUseCase`, `SyncTemplateApprovalUseCase` (usado por la API y por el barrido),
  `PublishTemplateUseCase` (publicar/despublicar), `QueryTemplateLinkUseCase`, `TemplateProductionGate` (`Mono<Boolean> isOpen(tenant,
  templateId)`; WHATSAPP exige `ACTIVA` + `PUBLICADA` + `APROBADA`, otros canales abren; si la plantilla no existe, cerrada).
- Publicar siempre llama `fetch` y compara; no usa la copia local. Un vinculo en `SUSPENDIDA` publica solo si `fetch` da `APROBADA`.
- Excepciones nuevas en `exception/`: `ProviderTemplateNotFoundException`, `ProviderTemplateAlreadyLinkedException`,
  `ProviderTemplateNotApprovedException(status, reason)`, `ProviderTemplateUnavailableException`, `TemplatePublishedException`,
  `TemplateNotWhatsAppException`. Se reutilizan las de D1 (`TemplateNotFound`, `VersionConflict`, `Archived`, `UndeclaredTemplateVariable`).

### infrastructure

- `adapter/out/provider/TwilioTemplateApprovalAdapter` (implementa `TemplateApprovalPort`, `providerId = twilio-whatsapp`): usa
  `TwilioWhatsAppProviderProperties` y `ProviderHttpClients` de spec 028 con `baseUrl` propio de la API de contenido
  (`notification.whatsapp-templates.twilio.content-base-url`, defecto `https://content.twilio.com`). Consulta aprobacion del contenido y
  su idioma; mapa `received/pending -> PENDIENTE`, `approved -> APROBADA`, `rejected -> RECHAZADA`, `paused/disabled -> PAUSADA`,
  `unsubmitted -> NO_ENVIADA`, otro -> `PAUSADA` con log. 404 -> no encontrado; 401/403/5xx/red -> no disponible. No propaga cuerpos
  ni mensajes de excepcion al log ni a la respuesta; `rejectionReason` truncado a 200. **El endpoint y los nombres de campo de Twilio
  se confirman contra su documentacion al implementar (no verificados aqui).**
- `adapter/out/mongo`: coleccion `template_provider_links`; indice unico `(tenantId, templateId)` y unico
  `(providerId, providerTemplateId)`; indice `(publicationStatus, approvalStatus, leaseUntil)` para el barrido. `claimDue` con
  `findAndModify`.
- `adapter/in/scheduler/TemplateApprovalSweeper`: `Flux.interval(notification.whatsapp-templates.sync.interval)` con `concatMap` sobre
  `claimDue`; `onErrorContinue` por vinculo; metrica y log; arrendamiento = 2 x intervalo. Apagado ordenado al cerrar el contexto.
- `adapter/in/rest`: `TemplateProviderLinkController` y DTOs (records). Mapeo: 400 validacion y `variableMap`; 404 plantilla o vinculo o
  proveedor; 409 con `code` distinto por causa (`TEMPLATE_VERSION_CONFLICT` de D1, mas los nuevos); 503 `PROVIDER_TEMPLATE_UNAVAILABLE`.
  Tenant del principal de sesion.
- `RouteAuthorizationPolicy`: 5 entradas (metodo, ruta) -> Role (OPERADOR escritura, CLIENTE `GET`); el defecto es ADMINISTRADOR. Los 5
  recursos se registran en `protected-resources.json` y se re-ejecuta el script de HU2-099 para que el PDP los vincule. Una prueba por ruta.
- Seguridad: Twilio ya exige credenciales por variables de entorno (spec 028); este adaptador no agrega secretos. Sin credenciales queda
  deshabilitado con motivo y responde 503.
- Excepcion (Principio VII): wiring (`UseCaseConfig`) y `pom.xml` fuera de esta historia. Dueno: Equipo de backend. Fecha: 2026-12-15.

## Puntos compartidos con otras historias

- **D1 (035)**: lee `Template` y `TemplateRepository`; reutiliza codigos 3050-3053. Si D1 cambia el nombre de esos tipos, ajustar aqui.
- **D3 (127, envio por plantilla)**: debe invocar `TemplateProductionGate` y, si cerrada, rechazar con el codigo de la compuerta antes
  de encolar (400 al aceptar) y como fallo permanente en despacho; usa `variableMap` para armar `providerTemplate.variables`. D5 no edita D3.
- **D6 (versionado)**: P1/P3; D5 define el estado de publicacion del vinculo, no de la plantilla.
- **E3 (115, failover)**: la compuerta cerrada es permanente: no dispara failover. Sin cambios en E3.
- **C3 (114, sondas)**: independiente; el adaptador de aprobacion no reutiliza la sonda.
- **E1**: ver "Limites" de la spec.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` (`StepVerifier`) | transiciones del agregado (suspender, no promover, publicar solo con `APROBADA`), casos de uso con puertos simulados, compuerta por canal y estado |
| Adaptador | `TwilioTemplateApprovalAdapterTest` contra servidor HTTP simulado: mapa de estados, 404, 5xx, truncado, sin secretos en logs (SC-005) |
| Integracion Mongo | `TemplateProviderLinkRepositoryTest` (Testcontainers): indices unicos, aislamiento por tenant, `claimDue` con dos consumidores concurrentes (SC-006) |
| E2E | `WhatsAppTemplateApprovalE2ETest` (`RANDOM_PORT`, Mongo, `WebTestClient`, proveedor doble HTTP): SC-001 a SC-004 y las 5 rutas |
| Seguridad de ruta | una prueba por ruta de `RouteAuthorizationPolicy` |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` en verde |

## Cambios de contrato requeridos (para Contrato)

- 5 operaciones nuevas: `POST /template-provider-links` (`linkProviderTemplate`), `GET /template-provider-links`
  (`getTemplateProviderLink`, `templateId` en query), `POST /template-approval-syncs` (`syncTemplateApproval`), `POST /template-publications`
  (`publishTemplate`), `POST /template-unpublications` (`unpublishTemplate`); todas con `x-status`, `x-minimum-role` (OPERADOR; CLIENTE el GET) y 503 `PepUnavailable`.
- Esquemas: `LinkProviderTemplateRequest`, `TemplateProviderLinkResponse`, `PublishTemplateRequest` (`templateId`, `version`),
  `SyncTemplateApprovalRequest`; enums `ProviderApprovalStatus` y `TemplatePublicationStatus`; `providerTemplateId` con
  `pattern: '^HX[0-9a-fA-F]{32}$'` (cambia si E1 elige Meta); `variableMap` objeto con `maxProperties: 20`.
- Codigos de error nuevos, propuestos en un bloque propio para no chocar con otras historias: 3070 PROVIDER_TEMPLATE_NOT_FOUND, 3071
  PROVIDER_TEMPLATE_ALREADY_LINKED, 3072 PROVIDER_TEMPLATE_NOT_APPROVED, 3073 TEMPLATE_PUBLISHED, 3074 TEMPLATE_NOT_WHATSAPP,
  4070 PROVIDER_TEMPLATE_UNAVAILABLE. Los numeros definitivos los asigna Contrato.
- Los codigos y enums deben registrarse tambien en `ErrorCode`.

## Riesgos y dependencias

- Bloqueo por el YAML de Contrato (II), el wiring externo (2026-12-15) y E1 para el adaptador definitivo.
- Barrido con varias replicas: depende del arrendamiento atomico; sin el, cada replica consultaria cada vinculo (SC-006 lo cubre).
- Limites de tasa de la API de contenido de Twilio sin verificar: el volumen es bajo (decenas de plantillas) y el intervalo es configurable.
