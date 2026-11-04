 # Implementation Plan: Enviar notificaciones por el canal WhatsApp

**Branch**: `feature/HU2-112-whatsapp-canal` | **Date**: 2026-10-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/028-whatsapp-canal/spec.md`

## Estado del plan

**Estado**: Aprobado por el usuario (2026-10-21)

**Versión del plan**: 1

Aprobado con `providerTemplate` y el ajuste de FR-007. Siguiente paso: `tasks.md`.

## Summary

Hoy el catálogo declara `EMAIL`, `SMS` y `PUSH`; una notificación `WHATSAPP` se rechaza en la aceptación. Esta
historia abre el canal con **Twilio** (Q1-A) repitiendo el patrón de SMS (spec 009) y push (spec 010), pero a
diferencia de ellos **no se puede hacer sin tocar `core`**: la notificación necesita poder llevar una
**plantilla** en lugar de un cuerpo (research.md, Decisión 3).

Piezas del plan:

1. **Modelo de contenido (`core`)**. `NotificationContent` gana `providerTemplate` opcional (`ProviderTemplateReference`:
   `templateId` + variables); invariante: exactamente uno de cuerpo o plantilla. `ContentSchemaValidator`
   rechaza una plantilla en un canal cuyo esquema no declare `providerTemplate`, con el mismo patrón que ya usa para
   los adjuntos. Correo, SMS y push no cambian.
2. **Adaptador** `TwilioWhatsAppNotificationProvider` (`providerId = twilio-whatsapp`), detrás de
   `NotificationSenderPort`. Mismo endpoint de mensajes de Twilio con `whatsapp:` en origen y destino;
   texto libre por `Body`, plantilla por `ContentSid` + `ContentVariables` (Decisión 2). Reutiliza el
   clasificador por código HTTP, el record de respuesta, los registros y los clientes HTTP de los demás
   adaptadores.
3. **Habilitación visible**. `TwilioWhatsAppProviderProperties`: sin las credenciales o con formato inválido,
   el proveedor se registra, avisa una vez con el motivo y cada envío lanza `ProviderDisabledException`
   (FR-004, FR-005). Credenciales de cuenta compartidas con SMS, número de origen propio (Decisión 8).
4. **Ventana de 24 h (Q2-A)**. El sistema no la rastrea. Un texto libre rechazado por estar fuera de ella
   termina `PERMANENT_FAILURE` sin reintentos y el adaptador registra la etiqueta `outside-session-window`
   derivada del código del proveedor (Decisión 5). La categoría sigue decidiéndola el código HTTP.
5. **Idempotencia**. Twilio no documenta una cabecera de idempotencia para su API de mensajes (verificado
   2026-10-21), así que el adaptador no envía ninguna. FR-011 y SC-008 pasan a riesgo residual documentado:
   un reintento tras un tiempo de espera ambiguo puede duplicar el mensaje (Decisión 6).
6. **Canal y contrato**. Catálogo `WHATSAPP` con esquema de contenido propio (Decisión 9), contrato público
   con el campo `providerTemplate` y `body` opcional (Principio II: se edita primero), registro del proveedor en la
   configuración gestionada (timeouts) y en los límites por proveedor.
7. **Fuera de alcance (Q3-A, Q4-A)**: sin webhook, sin rastreo de ventana, sin adjuntos. Ninguna ruta
   pública nueva.

**Ajuste de spec descubierto al planear**: el componente no tiene registro de plantillas, así que "variable
obligatoria ausente" no se puede rechazar en la aceptación. FR-007, la historia 2 y SC-007 ya se ajustaron
(Decisión 4); lo decide el proveedor y termina en fallo permanente.

`DispatchNotificationService`, el consumidor de RabbitMQ, `RabbitConfig`, `SimulatedNotificationProvider` y
los adaptadores de correo, SMS y push **no se tocan**.

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: **ninguna nueva en `pom.xml`**. `WebClient`/`reactor-netty`, el validador de JSON
Schema y el servidor de pruebas HTTP del JDK (`FakeProviderServer`) ya están.

**Storage**: Mongo. Un campo anidado nuevo y nulo en el documento de notificación (`providerTemplate`), `body` puede
ser nulo en documentos nuevos con plantilla; un documento sembrado nuevo en `channel_catalog`
(`_id = WHATSAPP`). Sin migración (data-model.md).

**Testing**: JUnit 5 + Mockito + `StepVerifier`. Unitarias en `core` (invariante del contenido, validador) y
en `infrastructure` (clasificación de etiquetas, adaptador contra un servidor HTTP local, propiedades); **dos
clases E2E** con Testcontainers (Mongo + RabbitMQ) + `WebTestClient`: una con credenciales y otra con la
configuración por defecto, porque requieren contextos distintos (misma división que SMS y push).

**Target Platform**: mismo deployable. Variables nuevas: `TWILIO_WHATSAPP_FROM_NUMBER` (obligatoria para enviar
de verdad) y opcionales `TWILIO_WHATSAPP_BASE_URL`, `TWILIO_WHATSAPP_TIMEOUT_MS`,
`TWILIO_WHATSAPP_CONNECT_TIMEOUT_MS`, `NOTIFICATION_WHATSAPP_PROVIDERS`. Reutiliza `TWILIO_ACCOUNT_SID` y
`TWILIO_AUTH_TOKEN`.

**Project Type**: hexagonal. Un cambio acotado de modelo en `core` (sin Spring), un adaptador de salida
nuevo y configuración en `infrastructure`.

**Performance Goals**: sin objetivo propio. Restricción de acotación: ninguna llamada excede su tiempo de
espera (10 s por defecto, parámetro gestionado).

**Constraints**: `core` sin Spring (Principio I); cero comentarios explicativos (III); cliente reactivo,
nunca bloqueante; ninguna credencial, cuerpo, variable ni número completo en código, configuración
versionada, registros o mensajes; ninguna prueba llama al proveedor real.

**Scale/Scope**: `core`: 1 value object nuevo, 3 clases modificadas (`NotificationContent`,
`ContentSchemaValidator`, `SendNotificationService`) y `ParameterRegistry`. `infrastructure` producción: 2
clases nuevas de adaptador/propiedades (+ 1 helper de etiquetas), cambios en el DTO REST de envío, mapper y
documento Mongo, `ConfigurationConfig`, `ProviderContentLimits`, `ProviderLogs`, `application.yml` y
`api-notificaciones.yaml`. Pruebas: unas 8 clases nuevas o ampliadas. Sin migración de datos.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Re-evaluado después de Phase 1: sin cambios, sigue en PASS (VI pendiente de aprobación).

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. Cambios en `core` solo de modelo y validación, en Java
  puro (`ProviderTemplateReference` es un record; el validador ya usaba su biblioteca de esquemas). Todo lo de Twilio
  (formulario, `ContentSid`, etiquetas de error, forma `HX…`) vive en el adaptador.
  `core` no aprende qué es WhatsApp: solo que un contenido puede ser una plantilla. `HexagonalArchitectureTest`
  y `ModularityTests` deben seguir en verde.
- **II. Contract-first** — PASS. Ningún endpoint nuevo. Los cambios al contrato público
  (`contracts/api-notificaciones-cambios.md`) se editan en el YAML **antes** del código; el contrato de
  salida del tercero queda en `contracts/twilio-whatsapp-messages-api.md`.
- **III. Cero comentarios explicativos** — PASS (a verificar). El porqué vive en research.md; el literal JSON
  del esquema en `application.yml` no se comenta.
- **IV. Calidad verificada, no declarada** — PASS condicionado. Tareas E2E explícitas:
  `WhatsAppDeliveryE2ETest` y `WhatsAppDisabledProviderE2ETest`. SC-006 con aserción de `Duration`; SC-004
  inspeccionando registros y mensajes reales con control positivo (el número enmascarado sí aparece); SC-008
  comprobando las cabeceras recibidas por el servidor falso; SC-011 con dos tenants; SC-012 comprobando que
  no se persistió nada, no solo el `400`. Cobertura ≥80 % líneas / ≥70 % ramas con `./mvnw -B -ntp verify`.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-112-whatsapp-canal`, commits de una línea en español
  sin tildes y sin trailer, artefactos de spec-kit en la misma rama; cambios ajenos fuera de los commits
  (Decisión 13).
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PASS. Spec con Q1-Q4 adoptadas y plan aprobado el 2026-10-21.
- **VII. Sin atajos** — PASS, con pendientes documentados con dueño y fecha: ventana de 24 h delegada al
  cliente (2026-12-31); webhook de estados y su validación diferidos, ADR-28 (2026-12-31); proveedor
  intermediario (2026-12-31); duplicado tras un reintento por falta de idempotencia del proveedor (aceptado 2026-10-21); canal
  `WHATSAPP` ausente en entornos ya sembrados, con paso manual (Decisión 12, 2026-10-31); costo por
  conversación y aprobación de plantillas (2026-11-30); límite de tasa y gestión de secretos (2026-11-30).
  Dueño de todos: andrualv.
- **VIII. Confiabilidad y durabilidad** — PASS. Proveedor deshabilitado o mal configurado → notificación
  intacta y trazable en la DLQ, nunca quemada como fallida. Fallo de transporte → recuperable con los
  reintentos existentes. Fallo permanente (incluida la ventana cerrada) → terminal pero trazable y
  reencolable a mano. Contenido inválido → rechazado **antes** de aceptarse.
- **IX. Observabilidad y trazabilidad** — PASS. Cada despacho registra `notificationId`, `tenantId`,
  `providerId`, categoría, código HTTP y, según el caso, `providerMessageId`, `providerErrorCode` y etiqueta
  de motivo, con el número enmascarado. Deshabilitado, recuperable, permanente del proveedor y forma inválida
  son distinguibles por tipo y efecto.
- **Restricciones técnicas** — PASS. `WebClient` reactivo sin `.block()` nuevo; ninguna credencial versionada;
  extensibilidad por catálogo (ADR-0009): el canal y su esquema se declaran, no se programan. El cambio de
  Mongo es aditivo y retrocompatible.
- **Ack manual y DLQ de RabbitMQ** — **sin excepción que justificar**. No se toca `RabbitConfig`,
  `RabbitRetryConfig` ni `NotificationDispatchListener`.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/028-whatsapp-canal/
├── plan.md              # This file
├── spec.md              # specify + clarify (Q1-Q4 adoptadas)
├── research.md          # Phase 0 — 13 decisiones
├── data-model.md        # Phase 1 — plantilla en el contenido, canal WHATSAPP, propiedades
├── quickstart.md        # Phase 1 — validación, humo manual, alta manual del canal
├── contracts/
│   ├── twilio-whatsapp-messages-api.md   # contrato de SALIDA hacia el proveedor
│   └── api-notificaciones-cambios.md     # cambios del contrato público
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks) — NO lo crea /speckit-plan
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/valueobject/ProviderTemplateReference.java      # NUEVO: record (templateId, variables)
├── domain/valueobject/NotificationContent.java    # MODIFICADO: campo providerTemplate + invariante cuerpo xor plantilla
├── domain/policy/ContentSchemaValidator.java      # MODIFICADO: plantilla solo si el esquema la declara
├── domain/configuration/ParameterRegistry.java    # MODIFICADO: MANAGED_PROVIDERS += twilio-whatsapp
└── usecase/SendNotificationService.java           # MODIFICADO: conservar la plantilla al crear el contenido

core/src/test/java/co/edu/uco/notification/core/
├── domain/valueobject/NotificationContentTest.java    # AMPLIADO o NUEVO
├── domain/policy/ContentSchemaValidatorTest.java      # AMPLIADO
└── (pruebas de ParameterRegistry/proveedores gestionados ajustadas a 4 proveedores)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/out/provider/
│   ├── TwilioWhatsAppNotificationProvider.java    # NUEVO: @Component, providerId twilio-whatsapp
│   ├── WhatsAppFailureReasons.java                # NUEVO: code del proveedor -> etiqueta (pura)
│   └── ProviderLogs.java                          # MODIFICADO: campo reason en rejectedByProvider
├── adapter/in/rest/SendNotificationRequest.java   # MODIFICADO: providerTemplate; body opcional
│   (+ NotificationController y NotificationBatchController: toCommand con plantilla)
├── adapter/out/mongo/NotificationDocument.java    # MODIFICADO: campo providerTemplate
├── adapter/out/mongo/NotificationDocumentMapper.java # MODIFICADO
└── config/
    ├── TwilioWhatsAppProviderProperties.java      # NUEVO: @ConfigurationProperties + disabledReason()
    ├── ConfigurationConfig.java                   # MODIFICADO: habilitación y timeouts del proveedor nuevo
    └── ProviderContentLimits.java                 # MODIFICADO: twilio-whatsapp / WHATSAPP / body.maxLength

infrastructure/src/main/resources/
├── application.yml                                # MODIFICADO: canal WHATSAPP + bloque twilio-whatsapp
└── static/openapi/api-notificaciones.yaml         # MODIFICADO PRIMERO: providerTemplate, body opcional

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── WhatsAppFailureReasonsTest.java                # NUEVO
├── TwilioWhatsAppNotificationProviderTest.java    # NUEVO: contra FakeProviderServer
├── WhatsAppDeliveryE2ETest.java                   # NUEVO: E2E del Principio IV con credenciales de prueba
└── WhatsAppDisabledProviderE2ETest.java           # NUEVO: E2E con la configuración por defecto
(+ pruebas existentes que fijan la lista de proveedores/parámetros, ajustadas)
```

**Structure Decision**: sin módulos ni paquetes nuevos. El adaptador vive junto a los otros en
`adapter/out/provider`; propiedades en `config`. El único concepto nuevo en `core` es la plantilla como forma
de contenido; no hay "WhatsApp" ni "ventana" en el dominio.

## Diseño del adaptador

Orden dentro de `send(notification)`:

1. **Deshabilitado** → `Mono.error(ProviderDisabledException)`. Sin llamada, sin intento, sin cambio de estado.
2. **Destinatario sin formato internacional** → `PERMANENT_FAILURE` sin llamar, `reason=invalid-recipient-format`
   (Decisión 7).
3. **Plantilla con `templateId` sin forma de `ContentSid`** → `PERMANENT_FAILURE` sin llamar,
   `reason=invalid-provider-template-format` (Decisión 4).
4. **Construir el formulario**: `To`, `From` con prefijo `whatsapp:`; `Body` o `ContentSid` +
   `ContentVariables`. El asunto no se usa.
5. **Llamar** con autenticación básica y sin cabecera de idempotencia (Decisión 6). Tiempos de espera del
   cliente por proveedor, gestionados por parámetros.
6. **Leer y clasificar**: el cuerpo se lee tolerante (vacío o ilegible → vacío); `classifyStatus` decide por
   HTTP; `onErrorResume` → recuperable.
7. **Registrar**: identificadores, categoría, código HTTP, `sid` o `code`, etiqueta de motivo y número
   enmascarado. Nunca credenciales, URL, cuerpo, variables, número completo ni
   mensajes de error.

El adaptador no reintenta por su cuenta.

## Orden de implementación y puntos de control

Orden por tarea: escribir la prueba, verla fallar por la razón correcta, implementar, ejecutar la clase,
`spotless:apply`.

1. **Verificado el 2026-10-21 (T001)**: campos de plantilla y tope de cuerpo confirmados; sin cabecera de
   idempotencia; códigos de error pendientes de verificar uno a uno. Ver research.md.
2. **Contrato público** — `api-notificaciones.yaml` (Principio II, antes del código).
3. **`core`**: `ProviderTemplateReference` y `NotificationContent` con su invariante, `ContentSchemaValidator`,
   `SendNotificationService`, `ParameterRegistry`; correr `-pl core test` completo (los parámetros gestionados
   pasan a cuatro proveedores).
4. **REST y Mongo**: DTO de envío, `toCommand` de envío y de lote, documento y mapper; probar el viaje de ida
   y vuelta de una notificación con plantilla.
5. **Propiedades, habilitación y configuración**: `TwilioWhatsAppProviderProperties`, `ConfigurationConfig`,
   `ProviderContentLimits`; correr las pruebas de configuración existentes.
6. **`WhatsAppFailureReasons` + prueba**, luego **adaptador + prueba** contra el servidor falso (incluye
   "cero peticiones" para destinatario y `templateId` inválidos).
7. **`application.yml`** (canal y bloque del proveedor, sin valores de credencial).
8. **`WhatsAppDeliveryE2ETest` y `WhatsAppDisabledProviderE2ETest`** — tareas E2E explícitas.
9. **Regresión**: `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, `ChannelCatalogSeederTest`,
   pruebas de correo/SMS/push, `HexagonalArchitectureTest`, `ModularityTests`.
10. **`./mvnw -B -ntp verify` completo y en verde.** Si solo fallan `DeadLetterQueueE2ETest` y
    `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo: sobre esas decide CI.

## Riesgos de esta implementación

| Riesgo | Cómo lo acota el plan |
|---|---|
| Un código de error del proveedor difiere de la tabla de etiquetas. | La etiqueta es solo diagnóstico y no cambia la categoría; los códigos se verificarán uno a uno (research.md, Decisión 5). Sin cabecera de idempotencia: duplicado posible tras un tiempo de espera ambiguo, riesgo aceptado con dueño y fecha. |
| Relajar `body` obligatorio rompe canales existentes. | La invariante es "cuerpo xor plantilla"; el esquema de correo/SMS/push no declara `providerTemplate`, así que una plantilla ahí se rechaza; SMS y push siguen exigiendo `body` por esquema. Pruebas de regresión en el paso 9. |
| Nuevo proveedor gestionado rompe pruebas que fijan tres proveedores. | Ajuste explícito en el paso 3 y 5; `ProviderViewTest` y pruebas de `ParameterRegistry`. |
| Datos personales en variables de plantilla filtrados a logs o eventos. | Prueba de no filtrado con control positivo; las variables no se pasan a `ProviderLogs`. |
| Dos credenciales compartidas con SMS llevan a deshabilitar el SMS por error de WhatsApp. | Propiedades independientes con motivo propio (Decisión 8). |
| El literal JSON del esquema se rompe al editarse en YAML. | Comillas simples y una E2E que envía 1600 y 1601 caracteres con la configuración por defecto. |
| Cobertura de ramas baja por la lectura tolerante de la respuesta. | La prueba del adaptador cubre cuerpo válido, vacío e ilegible en aceptación y rechazo. |

## Complexity Tracking

Ninguna violación — sección vacía.
