# Implementation Plan: Plantillas de WhatsApp aprobadas (HU2-129)

**Branch**: `feature/HU2-129-plantillas-whatsapp-aprobadas` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobacion | **Version del plan**: 2 (correcciones del lote nivel 2 aplicadas)

## Summary

Un agregado `TemplateProviderLink` en `core` (aprobacion + publicacion), una lista de plantillas permitidas por tenant
(`ProviderTemplateAllowEntry`), un puerto de salida que lee la aprobacion al proveedor, casos de uso de vincular, sincronizar, publicar,
despublicar, consultar y gestionar la lista, la compuerta `TemplateProductionGate` que consume el despacho del envio por plantilla, un adaptador
Twilio, un adaptador Mongo y un barrido periodico. D1 no se modifica; se usa su repositorio de plantillas en lectura.

## Technical Context

Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo | **Testing**: JUnit 5, Mockito, `StepVerifier`, Testcontainers (Mongo),
`WebTestClient`, servidor HTTP simulado para Twilio | **Depende de**: D1 (`Template`, `TemplateRepository`, errores 3050-3053), spec 028
(forma `HX...`, `TwilioWhatsAppProviderProperties`, `ProviderHttpClients`), D3 (127, `SendNotificationService` y su punto de extension),
B2 (roles), F1 (preferencias) como vecinos de la integracion con despacho.

## Constitution Check (2.0.1)

- I Hexagonal: reglas, compuerta y casos de uso en `core` sin Spring; Twilio, Mongo, REST y el planificador en `infrastructure`. Cumple.
- II Contrato primero: 8 rutas literales (spec, tabla de contrato). El YAML lo edita Contrato; el controller se escribe tras su merge.
  Cumple con dependencia.
- III Sin comentarios. IV E2E explicito (`WhatsAppTemplateApprovalE2ETest`), dos tenants, aserciones de `Duration` (SC-003), cobertura 80/70.
- V Commits de una linea. VI Plan aprobado antes de `tasks`. VII Excepciones en spec "Limites" y "Edge Cases" con dueno y fecha (2026-12-15).
- VIII: la compuerta cerrada en despacho produce fallo permanente trazable, nunca perdida silenciosa. IX: log y metrica de FR-011, sin secretos.
- RabbitMQ: no se agrega ningun consumidor. La integracion con despacho es llamar la compuerta; sin excepcion de ack/DLQ.

## Diseno

### core

- `domain/template/link/`: `TemplateProviderLink` (agregado), `ProviderTemplateAllowEntry` (tenant, `providerId`, `providerTemplateId`),
  `ProviderTemplateId` (valida `^HX[0-9a-fA-F]{32}$`), `ApprovalStatus`, `PublicationStatus` (`BORRADOR`, `PUBLICADA`, `SUSPENDIDA`),
  `SyncError` (`PROVIDER_UNREACHABLE`, `PROVIDER_DISABLED`, `PROVIDER_ERROR`).
- Reglas en el agregado: `applyApproval(ProviderApproval, Instant)` (suspende si `PUBLICADA` y llega `RECHAZADA`/`PAUSADA`; no promueve
  `SUSPENDIDA`), `recordSyncFailure(SyncError, Instant)` (no toca la publicacion), `publish()` (exige `APROBADA`), `unpublish()`,
  `relink(...)` solo en `BORRADOR`; `version` incrementa en cada cambio.
- Puerto de salida `port/out/TemplateApprovalPort`: `String providerId()`; `Mono<ProviderApproval> fetch(ProviderTemplateId)`;
  `Optional<String> disabledReason()`. `ProviderApproval(status, rejectionReason, language)`; ausente en el proveedor ->
  `ProviderTemplateNotFoundException`; fallo de red/5xx -> `ProviderTemplateUnavailableException`.
- Puerto `repository/TemplateProviderLinkRepository`: `save` (con `version`), `findByTemplate(tenant, templateId)`,
  `claimDue(Instant now, Duration lease, int limit)` (actualizacion atomica condicionada que fija `leaseUntil`).
- Puerto `repository/ProviderTemplateAllowlistRepository`: `save`, `findByProviderTemplate(providerId, providerTemplateId)` (busqueda
  global para la unicidad), `findByTenant(tenant, page)`, `delete(tenant, providerTemplateId)`.
- Lee la plantilla con el `TemplateRepository` de D1 (tenant incluido) para validar canal WHATSAPP y estado `ACTIVA`.
- Puertos de entrada: `LinkProviderTemplateUseCase`, `SyncTemplateApprovalUseCase` (API y barrido), `PublishTemplateUseCase`
  (publicar/despublicar), `QueryTemplateLinkUseCase`, `ManageProviderTemplateAllowlistUseCase` (agregar, listar, quitar).
- Vincular: plantilla `ACTIVA` y WHATSAPP; `providerTemplateId` debe estar en la lista del tenant (si no, 3071 sin consultar al
  proveedor); luego `fetch`; `variableMap` validado contra las variables declaradas de D1 (`UndeclaredTemplateVariable`).
- Agregar a la lista: `fetch` para verificar que existe; si no existe o la lista de otro tenant lo tiene, `ProviderTemplateNotFoundException`
  (3071, mismo codigo y respuesta). Quitar: 409 `TemplatePublishedException` si un vinculo `PUBLICADA` del tenant lo usa.
- Publicar siempre llama `fetch` y compara; no usa la copia local. Un vinculo en `SUSPENDIDA` publica solo si `fetch` da `APROBADA`.
- `TemplateProductionGate` (`Mono<Boolean> isOpen(tenant, templateId)`), propiedad de D5: WHATSAPP exige `ACTIVA` + vinculo `PUBLICADA` +
  `APROBADA`; otros canales abren; plantilla inexistente, cerrada.
- Excepciones nuevas en `exception/`: `ProviderTemplateNotFoundException` (3071), `ProviderTemplateNotApprovedException(status, reason)`
  (3072), `ProviderTemplateUnavailableException` (4070), `TemplatePublishedException` (3073), `TemplateNotWhatsAppException` (3074).
  Se reutilizan las de D1 (`TemplateNotFound`, `VersionConflict`, `Archived`, `UndeclaredTemplateVariable`).

### Integracion con despacho (D5 sobre D3)

- D3 (127) expone el punto de extension `ProviderTemplateLinkPort` para WHATSAPP. D5 lo implementa y es dueña de `TemplateProductionGate`,
  del uso de `variableMap` para `providerTemplate.variables` y del desvio a `providerTemplate` en WHATSAPP.
- D5 se integra despues de D3 y edita solo el despacho de `SendNotificationService`. D3 no se modifica para esto salvo el punto de extension.
- Solo un envio por `templateId` (WHATSAPP) exige vinculo `PUBLICADA`; el cuerpo directo (HU2-112) no cambia.
- Orden de guardas en el despacho: 1) preferencias de F1 (`DISCARDED`); 2) compuerta de D5 (cerrada = fallo permanente trazable,
  sin failover de E3). Solo despues se arma `providerTemplate`.
- La aceptacion no consulta la compuerta: no hay rechazo al aceptar.

### infrastructure

- `adapter/out/provider/TwilioTemplateApprovalAdapter` (implementa `TemplateApprovalPort`, `providerId = twilio-whatsapp`): usa
  `TwilioWhatsAppProviderProperties` y `ProviderHttpClients` de spec 028 con `baseUrl` propio de la API de contenido
  (`notification.whatsapp-templates.twilio.content-base-url`, defecto `https://content.twilio.com`). Mapa `received/pending -> PENDIENTE`,
  `approved -> APROBADA`, `rejected -> RECHAZADA`, `paused/disabled -> PAUSADA`, `unsubmitted -> NO_ENVIADA`, otro -> `PAUSADA` con log.
  404 -> no encontrado; 401/403/5xx/red -> no disponible. No propaga cuerpos ni mensajes de excepcion; `rejectionReason` truncado a 200.
  **El endpoint y los nombres de campo de Twilio se confirman contra su documentacion al implementar (no verificados aqui).**
- `adapter/out/mongo`: colecciones `template_provider_links` (indice unico `(tenantId, templateId)`, unico `(providerId,
  providerTemplateId)`, indice `(publicationStatus, approvalStatus, leaseUntil)` para el barrido) y `provider_template_allow_entries`
  (unico `(providerId, providerTemplateId)`). `claimDue` con `findAndModify`.
- `adapter/in/scheduler/TemplateApprovalSweeper`: `Flux.interval(notification.whatsapp-templates.sync.interval)` con `concatMap` sobre
  `claimDue`; `onErrorContinue` por vinculo; metrica y log; arrendamiento = 2 x intervalo. Apagado ordenado al cerrar el contexto.
- `adapter/in/rest`: `TemplateProviderLinkController`, controlador de la lista y DTOs (records). Mapeo: 400 validacion y `variableMap`;
  404 plantilla o vinculo con codigo D1; 404 `PROVIDER_TEMPLATE_NOT_FOUND` (3071) para cualquier caso de `providerTemplateId`;
  409 con `code` por causa; 503 `PROVIDER_TEMPLATE_UNAVAILABLE`. Tenant del principal de sesion.
- `RouteAuthorizationPolicy`: 8 entradas (metodo, ruta) -> roles de la tabla de la spec, sin valor por defecto de rol amplio. Los 8
  recursos se registran en `protected-resources.json` y se re-ejecuta el script de HU2-099 para que el PDP los vincule. Una prueba por ruta.
- Seguridad: Twilio ya exige credenciales por variables de entorno (spec 028); este adaptador no agrega secretos. Sin credenciales queda
  deshabilitado con motivo y responde 503.
- Excepcion (Principio VII): wiring (`UseCaseConfig`) y `pom.xml` fuera de esta historia. Dueno: Equipo de backend. Fecha: 2026-12-15.

## Puntos compartidos con otras historias

- **B2 (039)**: roles nuevos y `x-allowed-roles` en el contrato; las rutas de esta historia usan la tabla de la spec.
- **D1 (035)**: lee `Template` y `TemplateRepository`; reutiliza codigos 3050-3053. Si D1 cambia el nombre de esos tipos, ajustar aqui.
- **D3 (127)**: punto de extension `ProviderTemplateLinkPort`; D5 integra la compuerta y `variableMap` despues de D3.
- **Cadena de plantillas**: D2, D7, D4 (evento), D3, D5.
- **F1 (048)**: `DISCARDED` se evalua antes que la compuerta (ver Integracion con despacho).
- **D6 (versionado)**: P1 (cuerpo D1 o bloque D7 editado tras publicar) se deja a D6 (nivel 3). Dueno: Equipo de backend. Fecha: 2026-12-15.
  P3 pendiente. D5 define el estado de publicacion del vinculo, no de la plantilla.
- **E3 (115, failover)**: la compuerta cerrada es permanente: no dispara failover. Sin cambios en E3.
- **C3 (114, sondas)**: independiente; el adaptador de aprobacion no reutiliza la sonda.
- **E1**: ver "Limites" de la spec.

## Estrategia de pruebas

| Nivel | Cobertura |
|---|---|
| Unitaria `core` (`StepVerifier`) | transiciones del agregado (suspender, no promover, publicar solo con `APROBADA`), casos de uso con puertos simulados, lista (3071 uniforme), compuerta por canal y estado |
| Adaptador | `TwilioTemplateApprovalAdapterTest` contra servidor HTTP simulado: mapa de estados, 404, 5xx, truncado, sin secretos en logs (SC-005) |
| Integracion Mongo | `TemplateProviderLinkRepositoryTest` (Testcontainers): indices unicos, aislamiento por tenant, `claimDue` con dos consumidores concurrentes (SC-006) |
| E2E | `WhatsAppTemplateApprovalE2ETest` (`RANDOM_PORT`, Mongo, `WebTestClient`, proveedor doble HTTP): SC-001 a SC-004 y las 8 rutas; orden de guardas (`DISCARDED` antes que la compuerta) en el despacho |
| Seguridad de ruta | una prueba por cada una de las 8 rutas de `RouteAuthorizationPolicy` |
| Arquitectura | `HexagonalArchitectureTest` y `ModularityTests` en verde |

## Cambios de contrato requeridos (para Contrato)

- 8 operaciones nuevas, todas con `x-status: planned`, `operationId` y `x-allowed-roles` segun la tabla de la spec; 503 `PepUnavailable`
  en todas. Las 5 de vinculo y publicacion mas las 3 de la lista (`addProviderTemplateAllowEntry`, `listProviderTemplateAllowEntries`,
  `removeProviderTemplateAllowEntry`).
- Esquemas: `LinkProviderTemplateRequest`, `TemplateProviderLinkResponse`, `PublishTemplateRequest` (`templateId`, `version`),
  `SyncTemplateApprovalRequest`, `ProviderTemplateAllowEntryRequest`; enums `ProviderApprovalStatus` y `TemplatePublicationStatus`;
  `providerTemplateId` con `pattern: '^HX[0-9a-fA-F]{32}$'` (cambia si E1 elige Meta); `variableMap` objeto con `maxProperties: 20`.
- Codigos de error nuevos: 3071 PROVIDER_TEMPLATE_NOT_FOUND, 3072 PROVIDER_TEMPLATE_NOT_APPROVED, 3073 TEMPLATE_PUBLISHED,
  3074 TEMPLATE_NOT_WHATSAPP, 4070 PROVIDER_TEMPLATE_UNAVAILABLE. El 3070 lo usa el nivel 1 (RESEND_CHANNEL_INVALID) y no se toca.
  Los codigos y enums deben registrarse tambien en `ErrorCode`.

## Riesgos y dependencias

- Bloqueo por el YAML de Contrato (II), el wiring externo (2026-12-15), E1 para el adaptador definitivo y D3 (integracion posterior).
- Barrido con varias replicas: depende del arrendamiento atomico; sin el, cada replica consultaria cada vinculo (SC-006 lo cubre).
- Limites de tasa de la API de contenido de Twilio sin verificar: el volumen es bajo (decenas de plantillas) y el intervalo es configurable.
