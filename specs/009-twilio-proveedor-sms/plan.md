# Implementation Plan: Integrar Twilio como primer proveedor real de SMS

**Branch**: `feature/HU2-090-twilio-sms` | **Date**: 2026-09-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-twilio-proveedor-sms/spec.md`

## Estado del plan

**Estado**: Aceptado

**Versión del plan**: 1

## Summary

Hoy el canal SMS **no existe**: el catálogo solo declara `EMAIL` y una notificación SMS se rechaza en la
aceptación. Esta historia lo abre con un proveedor real, repitiendo el patrón que dejó el proveedor real
de correo (HU2-089) sobre el registro de adaptadores de HU2-088.

El plan: **un adaptador dedicado** (`TwilioNotificationProvider`, `providerId = twilio`) que envía el SMS
por la API REST de mensajes del proveedor con un `WebClient` reactivo y tiempo de espera explícito. Cinco
piezas alrededor:

1. **Habilitación.** Credenciales solo por variables de entorno (`TWILIO_ACCOUNT_SID`,
   `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`). Si falta alguna, o el identificador de cuenta o el número
   de origen están mal formados, el adaptador se registra igual, avisa una vez con el motivo y cada envío
   devuelve la `ProviderDisabledException` que ya existe en `core`. La validación de formato evita que un
   error de configuración queme cada notificación como fallo permanente (research.md, Decisión 3).
2. **Clasificación.** `TwilioResponseClassifier`, estático y puro; decide solo el código HTTP (Q4). El
   `code` numérico del proveedor se registra, no clasifica (research.md, Decisión 5).
3. **Canal y forma de contenido.** El catálogo declara `SMS` con proveedores `simulated, twilio`
   configurables por entorno y una forma de contenido propia: cuerpo obligatorio de hasta 160 caracteres,
   asunto admitido e ignorado (Q1, Q2). La valida en la aceptación el `ContentSchemaValidator` que ya
   existe: un cuerpo más largo es un `400` y nunca se trunca. **Cero cambios en `core`** (Decisión 4).
4. **Destinatario.** El adaptador comprueba el formato internacional antes de llamar; si no lo cumple,
   fallo permanente sin petición (Q3). La regla y el enmascarado del número (`***` + últimos 4) viven en
   `utils` para usarse desde el adaptador y desde las propiedades sin ciclo de módulos (Decisiones 6 y 8).
5. **No filtrado.** Además de lo que ya hace el correo, no se registra la URL (lleva el identificador de
   cuenta), ni el mensaje de excepciones de transporte, ni el `message` de los errores del proveedor (lleva
   el número completo) (Decisión 9).

**Hallazgo que obliga a tocar el adaptador del correo**: un segundo bean `WebClient` hace ambigua la
inyección de `BrevoNotificationProvider`, porque el proyecto no compila con `-parameters`. Ambos
adaptadores ganan un `@Qualifier` (research.md, Decisión 2). Sin esto, el contexto de Spring no arranca.

`DispatchNotificationService`, `NotificationSenderPort`, `SimulatedNotificationProvider`, el consumidor de
RabbitMQ y la estructura de `api-notificaciones.yaml` **no se tocan**; del contrato público solo cambian
cuatro descripciones (Decisión 14).

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: **ninguna nueva en `pom.xml`**. `WebClient`/`reactor-netty` vienen con
`spring-boot-starter-webflux`; el validador de JSON Schema ya está en `core`; el servidor de pruebas usa
`com.sun.net.httpserver` del JDK.

**Storage**: sin cambios de forma. Solo un documento sembrado nuevo (`channel_catalog`, `_id = SMS`) con
la forma existente (data-model.md).

**Testing**: JUnit 5 + Mockito + `StepVerifier`. Unitario puro para `PhoneNumbers` y el clasificador;
adaptador contra un servidor HTTP local real; **dos clases E2E nuevas** con Testcontainers (Mongo +
RabbitMQ) + `WebTestClient`: una con credenciales (flujo completo, límite, destinatario, no filtrado) y
otra con la configuración por defecto (catálogo sembrado, SMS por el simulado, proveedor deshabilitado),
porque necesitan contextos de Spring distintos — la misma división que hizo el correo.

**Target Platform**: mismo deployable. Tres variables nuevas obligatorias para enviar de verdad y cuatro
opcionales (`TWILIO_BASE_URL`, `TWILIO_TIMEOUT_MS`, `TWILIO_CONNECT_TIMEOUT_MS`,
`NOTIFICATION_SMS_PROVIDERS`).

**Project Type**: hexagonal — un adaptador de salida nuevo en `infrastructure`, una utilidad pura en
`utils`, configuración de catálogo. Ninguna regla de negocio cambia de lugar.

**Performance Goals**: sin objetivo propio. Restricción de acotación: ninguna llamada excede su tiempo de
espera (10 s por defecto), porque el consumidor de RabbitMQ bloquea un hilo por despacho (borde
preexistente).

**Constraints**: `core` sin Spring y, en esta historia, sin cambios (Principio I); cero comentarios
explicativos (Principio III); cliente reactivo, nunca bloqueante (ADR-0003); ninguna credencial, cuerpo o
número completo en código, configuración versionada, registros o mensajes (RNF-09); ninguna prueba llama
al proveedor real.

**Scale/Scope**: `core`: 0 cambios. `utils`: 1 clase + su prueba. `infrastructure` producción: 4 clases
nuevas (adaptador, propiedades, clasificador, configuración del `WebClient`) + 1 record de respuesta, 1
anotación en `BrevoNotificationProvider`, `application.yml` y descripciones de `api-notificaciones.yaml`.
Pruebas: 1 ayuda renombrada (`FakeBrevoServer` → `FakeProviderServer`, con sus referencias), 4 clases
nuevas. Sin migración de datos.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Re-evaluado después de Phase 1: sin cambios, sigue en PASS.

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. `core` no cambia: se reutilizan
  `NotificationSenderPort`, `ProviderDisabledException` y `ContentSchemaValidator`. Todo el conocimiento
  del proveedor (HTTP, formulario, autenticación, clasificación) vive en `infrastructure`. `PhoneNumbers`
  va a `utils`, que es Java puro. `HexagonalArchitectureTest` y `ModularityTests` deben seguir en verde;
  `TwilioProviderProperties` va a `config` para no crear el ciclo que ya se evitó con el correo.
- **II. Contract-first** — PASS. Ningún endpoint nuevo. El contrato público solo cambia descripciones, y se
  editan **antes** del código (`contracts/api-notificaciones-cambios.md`). El contrato de salida del
  tercero queda en `contracts/twilio-messages-api.md` antes del adaptador.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). El porqué de cada fila
  del clasificador, del literal de la forma de contenido y de la expresión del número vive en research.md.
  Atención al literal JSON de `application.yml`: no se comenta.
- **IV. Calidad verificada, no declarada** — PASS condicionado. Dos clases E2E explícitas en `tasks.md`
  (`TwilioSmsDeliveryE2ETest`, `TwilioDisabledProviderE2ETest`). SC-006 con aserción de `Duration`; SC-004
  inspeccionando registros y mensajes reales, con control positivo (el número enmascarado sí aparece);
  SC-008 comprobando que no se persistió nada, no solo el `400`. Cobertura ≥80 % líneas / ≥70 % ramas con
  `./mvnw -B -ntp verify` completo, incluido el módulo `utils`.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-090-twilio-sms`, commits de una línea en español sin
  tildes, artefactos de spec-kit en la misma rama. Los cambios ajenos (`Recipient.java`,
  `spring.application.name`) no entran en ningún commit (research.md, Decisión 13).
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PENDIENTE. Spec y plan en estado
  Pendiente; Q1–Q4 registradas con respuesta recomendada y pendientes de confirmación. No se genera
  `tasks.md` ni se implementa nada hasta que el usuario cambie `## Estado del plan` a Aceptado.
- **VII. Sin atajos** — PASS, con pendientes documentados con dueño y fecha, ninguno tapado: SMS duplicado
  sin idempotencia del proveedor (Decisión 12, 2026-10-31); canal SMS ausente en entornos ya sembrados,
  con paso manual (Decisión 4, 2026-10-31); condiciones transitorias clasificadas como permanentes (Q4,
  2026-11-30); validación del destinatario en el despacho y no en la aceptación (Q3, 2026-12-31); cuenta de
  prueba y registro A2P 10DLC (2026-11-30); HU2-038, HU2-039, HU2-052 (Decisión 15).
- **VIII. Confiabilidad y durabilidad** — PASS. Proveedor deshabilitado o mal configurado → notificación
  intacta y trazable en la DLQ, nunca quemada como fallida (por eso se valida el formato de las
  credenciales al arrancar). Fallo de transporte → intento recuperable y reintentos existentes. Fallo
  permanente → terminal pero trazable y reencolable a mano (`FAILED → PENDING`). Cuerpo demasiado largo →
  rechazado **antes** de aceptarse, así que nunca hay una notificación aceptada que se pierda por eso. El
  riesgo abierto es el inverso — duplicado — y está documentado.
- **IX. Observabilidad y trazabilidad** — PASS. Cada despacho registra `notificationId`, `tenantId`,
  `providerId`, categoría, código HTTP y, según el caso, `providerMessageId` o `providerErrorCode`, con el
  número enmascarado. Proveedor deshabilitado, fallo recuperable, fallo permanente del proveedor y
  destinatario mal formado son distinguibles por tipo y por efecto. El registro estructurado a nivel de
  aplicación (RNF-10) sigue siendo una brecha preexistente, fuera de alcance.
- **Restricciones técnicas** — PASS. `WebClient` sobre `ReactorClientHttpConnector`, sin `.block()` nuevo.
  Ninguna credencial versionada: las propiedades obligatorias se declaran vacías. La forma de contenido y
  la lista de proveedores son configuración estructural no sensible, versionada y sobreescribible por
  entorno. Extensibilidad por catálogo (ADR-0009): el canal y su límite se declaran, no se programan. Sin
  cambios en bloqueo optimista ni en idempotencia de aceptación.
- **Ack manual y DLQ de RabbitMQ** — **sin excepción que justificar**. No se toca `RabbitConfig`,
  `RabbitRetryConfig` ni `NotificationDispatchListener`.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/009-twilio-proveedor-sms/
├── plan.md              # This file (/speckit-plan command output)
├── spec.md              # /speckit-specify + /speckit-clarify (Q1–Q4 pendientes de confirmación)
├── research.md          # Phase 0 output — 16 decisiones
├── data-model.md        # Phase 1 output — sin cambios de forma; documento SMS sembrado
├── quickstart.md        # Phase 1 output — validación, alta manual del canal, prueba de humo
├── contracts/
│   ├── twilio-messages-api.md          # contrato de SALIDA hacia el proveedor
│   └── api-notificaciones-cambios.md   # cambios (solo descripciones) del contrato público
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NO lo crea /speckit-plan)
```

### Source Code (repository root)

```text
utils/src/main/java/co/edu/uco/notification/utils/
└── PhoneNumbers.java                     # NUEVO: isE164(String), mask(String); Java puro

utils/src/test/java/co/edu/uco/notification/utils/
└── PhoneNumbersTest.java                 # NUEVO

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── TwilioNotificationProvider.java       # NUEVO: @Component, NotificationSenderPort, providerId twilio;
│                                         # @Qualifier("twilioWebClient")
├── TwilioResponseClassifier.java         # NUEVO: estatico y puro, status/error -> AttemptResult
├── TwilioApiResponse.java                # NUEVO: record (sid, code), ignoreUnknown; sin message
├── BrevoNotificationProvider.java        # MODIFICADO: @Qualifier("brevoWebClient") en el constructor
└── SimulatedNotificationProvider.java    # SIN CAMBIOS

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/
├── TwilioProviderProperties.java         # NUEVO: @ConfigurationProperties("notification.provider.twilio")
│                                         # + disabledReason(); defaults con Long, no long
└── TwilioWebClientConfig.java            # NUEVO: @Bean twilioWebClient con responseTimeout y connect
                                          # timeout; sin wiretap

infrastructure/src/main/resources/
├── application.yml                       # MODIFICADO: canal SMS (proveedores + forma de contenido) y
│                                         # bloque notification.provider.twilio sin valores de credencial
└── static/openapi/api-notificaciones.yaml # MODIFICADO: solo descripciones (Decision 14)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── FakeProviderServer.java               # RENOMBRADO desde FakeBrevoServer.java (git mv), sin cambios de
│                                         # comportamiento
├── BrevoNotificationProviderTest.java    # MODIFICADO: solo la referencia al servidor renombrado
├── BrevoEmailDeliveryE2ETest.java        # MODIFICADO: idem
├── TwilioResponseClassifierTest.java     # NUEVO
├── TwilioNotificationProviderTest.java   # NUEVO
├── TwilioSmsDeliveryE2ETest.java         # NUEVO: E2E del Principio IV con credenciales de prueba
└── TwilioDisabledProviderE2ETest.java    # NUEVO: E2E con la configuracion por defecto
```

**Structure Decision**: sin módulos ni paquetes nuevos. El adaptador vive junto a los otros dos en
`adapter/out/provider`; propiedades y `WebClient` en `infrastructure/config`, como los del correo; la
regla del número en `utils`, único lugar que pueden usar a la vez `adapter` y `config` sin ciclo. `core`
no gana ningún concepto de "SMS" ni de "teléfono".

## Diseño del adaptador

Orden dentro de `send(notification)`:

1. **Deshabilitado** → `Mono.error(ProviderDisabledException)`. Sin llamada, sin intento, sin cambio de
   estado. Decidido una sola vez al construirse.
2. **Destinatario sin formato internacional** → `Mono.just(PERMANENT_FAILURE)` sin llamar, con
   `reason=invalid-recipient-format` en el registro y sin el valor (Q3). Verificado con un hecho
   observable: la prueba afirma `FakeProviderServer.requests().isEmpty()`, no solo el resultado
   (research.md, Decisión 6).
3. **Construir el formulario** `To`, `From`, `Body`. El asunto no se usa (Q1). El cuerpo llega ya
   validado contra la forma del canal (Q2).
4. **Llamar** `POST /2010-04-01/Accounts/{accountSid}/Messages.json` con autenticación básica. Tiempos de
   espera fijados en el `HttpClient` del bean.
5. **Leer y clasificar**: `exchangeToMono` lee el cuerpo como `TwilioApiResponse` (tolerando cuerpo
   vacío o ilegible) y `TwilioResponseClassifier.classifyStatus` decide con el código HTTP; en el camino de
   error, `onErrorResume` con `classifyError` → `RECOVERABLE_FAILURE`.
6. **Registrar** según research.md, Decisión 8: identificadores, categoría, código HTTP, `sid` o `code`,
   número enmascarado. Nunca credenciales, URL, cuerpo, número completo ni mensajes de error.

El adaptador **no** reintenta por su cuenta: los reintentos son del consumidor y de `RetryPolicy`.

## Orden de implementación y puntos de control

Orden por tarea: escribir la prueba, verla fallar por la razón correcta, implementar, ejecutar la clase,
`spotless:apply`.

1. **Contrato público** — descripciones de `api-notificaciones.yaml` (Principio II, antes del código).
2. **`PhoneNumbers` + `PhoneNumbersTest`** en `utils`.
3. **`TwilioResponseClassifier` + su prueba** — toda la tabla de la Decisión 5.
4. **Renombrar `FakeBrevoServer` → `FakeProviderServer`** y actualizar referencias; correr
   `BrevoNotificationProviderTest` para confirmar que no cambió nada.
5. **`TwilioProviderProperties`, `TwilioWebClientConfig`, `@Qualifier` en ambos adaptadores** — correr
   `BrevoEmailDeliveryE2ETest` inmediatamente: es la prueba que demuestra que el contexto sigue
   arrancando con dos `WebClient`.
6. **`TwilioApiResponse`, `TwilioNotificationProviderTest`, `TwilioNotificationProvider`.** El caso de
   destinatario mal formado afirma `FakeProviderServer.requests().isEmpty()` además del resultado
   (research.md, Decisión 6).
7. **`application.yml`** siguiendo research.md, Decisión 13 (`git stash push` del cambio ajeno antes de
   editar, commit solo de lo propio, `git stash pop` al final de la historia; conflicto → se reporta).
8. **`TwilioSmsDeliveryE2ETest` y `TwilioDisabledProviderE2ETest`** — tareas E2E explícitas del
   Principio IV. `TwilioDisabledProviderE2ETest` afirma `FakeProviderServer.requests().isEmpty()`
   además de que la notificación no se marca entregada ni fallida (research.md, Decisión 6).
9. **Regresión**: `ProviderRoutingE2ETest` (guarda su propio documento SMS sobre el sembrado),
   `ChannelCatalogE2ETest`, pruebas del correo, `HexagonalArchitectureTest`, `ModularityTests`.
10. **`./mvnw -B -ntp verify` completo y en verde.** Si las únicas que fallan son `DeadLetterQueueE2ETest`
    y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo explícitamente: sobre esas
    dos decide CI.

## Riesgos de esta implementación

| Riesgo | Cómo lo acota el plan |
|---|---|
| El segundo `WebClient` rompe el arranque del contexto. | `@Qualifier` en ambos adaptadores (Decisión 2) y ejecución de `BrevoEmailDeliveryE2ETest` en el paso 5, antes de seguir. |
| Sembrar SMS rompe pruebas que dan por hecho que SMS no existe. | Revisado: `ProviderRoutingE2ETest` y `ChannelCatalogE2ETest` guardan su propio documento SMS (sobrescribe por `_id`) o parten de colección vacía; ninguna prueba espera `400` por SMS inexistente. Se confirma en el paso 9. |
| La prueba de no filtrado filtra credenciales en su propia salida. | Valores inventados; las aserciones comprueban ausencia sin imprimir el valor buscado. |
| El validador cuenta caracteres distinto de como cobra el proveedor. | Documentado (Decisión 4) como costo máximo aceptado; las pruebas usan texto ASCII de 160/161 para fijar la regla, no la facturación. |
| El literal JSON de la forma de contenido se rompe al editarse en YAML. | Comillas simples en YAML y una prueba E2E que envía 160 y 161 caracteres con la configuración por defecto. |
| La cobertura de ramas baja por la lectura tolerante del cuerpo de respuesta. | `TwilioNotificationProviderTest` cubre cuerpo válido, vacío e ilegible en aceptación y en rechazo. `TwilioApiResponse` y `TwilioProviderProperties` los recorren las pruebas; no se escriben pruebas solo para el porcentaje. |

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
