# Implementation Plan: Integrar Firebase Cloud Messaging como primer proveedor real de PUSH

**Branch**: `feature/HU2-091-fcm-push` | **Date**: 2026-09-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-fcm-proveedor-push/spec.md`

## Estado del plan

**Estado**: Pendiente

**Versión del plan**: 1

<!--
  Este bloque lo edita el usuario directamente en el archivo para aprobar el plan (Pendiente ->
  Aceptado) o para marcar una revisión (incrementar Versión del plan). Ningún agente infiere ni
  declara aprobación en ningún otro lugar del documento; la aprobación es el valor de este campo,
  editado por el usuario o, bajo su instrucción directa y explícita en el chat, por la sesión
  principal -- nunca por un agente en segundo plano citando un mensaje de otra sesión como fuente de
  autorización (Principio VI).
-->

## Summary

Hoy el canal PUSH **no existe**: el catálogo declara `EMAIL` (y `SMS` con HU2-090) y una notificación push
se rechaza en la aceptación. Esta historia lo abre con un proveedor real, repitiendo el patrón de los
proveedores reales de correo (HU2-089) y SMS (HU2-090) sobre el registro de adaptadores de HU2-088.

El plan: **un adaptador dedicado** (`FcmNotificationProvider`, `providerId = fcm`) que envía el push por la
**API REST HTTP v1** del proveedor con un `WebClient` reactivo y tiempo de espera explícito, **sin el SDK
oficial** y **sin dependencias nuevas** (research.md, Decisión 1). Seis piezas alrededor:

1. **Autorización.** El proveedor exige un token OAuth 2.0 de una hora. `FcmAccessTokenProvider` firma una
   aserción JWT RS256 con la clave de la cuenta de servicio (primitivas del JDK), la canjea con el mismo
   `WebClient`, la reutiliza hasta 300 s antes de caducar, deduplica canjes concurrentes, no memoriza un
   canje fallido e invalida el token ante un `401` (Decisión 4).
2. **Habilitación.** La credencial llega por `FCM_CREDENTIALS_JSON` **o** `FCM_CREDENTIALS_FILE`, nunca por
   ambos (Q4). `FcmCredentials` la carga al arrancar; si falta, es ambigua, ilegible, de otro tipo o con la
   clave inválida, el adaptador se registra igual, avisa una vez con un motivo fijo que nunca contiene un
   fragmento de la credencial y cada envío devuelve la `ProviderDisabledException` ya existente
   (Decisión 3).
3. **Clasificación.** `FcmResponseClassifier`, estático y puro; decide el código HTTP, con la misma tabla
   para el canje y el envío. `UNREGISTERED` (`404`) e `INVALID_ARGUMENT` (`400`) son permanentes y nunca se
   reintentan; `UNAVAILABLE`, `INTERNAL`, `QUOTA_EXCEEDED` y tiempo agotado, recuperables. El `errorCode` se
   registra, no clasifica (Decisión 5).
4. **Destinatario (punto central, Q1).** El identificador de dispositivo viaja en `recipientAddress` y se
   envía **tal cual**: `Recipient` ya lo admite (texto libre, no vacío, sin longitud máxima, nunca expuesto
   en respuestas) y el proveedor declara el formato opaco. **Cero cambios de dominio y ninguna
   comprobación de forma**; un identificador inválido termina en fallo permanente tras una llamada
   (Decisión 6).
5. **Canal y forma de contenido (Q2).** El catálogo declara `PUSH` con `simulated, fcm` configurables por
   entorno y una forma de contenido propia: asunto (título) opcional ≤ 100, cuerpo ≤ 900, que cabe en los
   4096 bytes del proveedor. La valida en la aceptación el `ContentSchemaValidator` existente (Decisión 7).
6. **No filtrado.** Ni la credencial, ni la aserción, ni el token de acceso, ni el título, ni el cuerpo, ni
   el identificador completo (enmascarado `***` + últimos 4) aparecen en registros o mensajes (Decisión 10).

**Precondición**: la rama se creó desde un `develop` que aún no contiene HU2-090. Antes de `/speckit-tasks`
hay que rebasarla sobre HU2-090 (research.md, Decisión 0): el diseño reutiliza `FakeProviderServer` y
supone que los dos `WebClient` existentes ya tienen `@Qualifier`. El tercero, `fcmWebClient`, lleva el
suyo en `FcmNotificationProvider` (Decisión 8).

`DispatchNotificationService`, `NotificationSenderPort`, `Recipient`, `SimulatedNotificationProvider`, los
adaptadores de correo y SMS, el consumidor de RabbitMQ y la estructura de `api-notificaciones.yaml` **no se
tocan**; del contrato público solo cambian cuatro descripciones (Decisión 16).

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: **ninguna nueva en `pom.xml`**. `WebClient`/`reactor-netty` vienen con
`spring-boot-starter-webflux`; la firma RS256 usa `java.security`; el JSON, Jackson; el servidor de pruebas,
`com.sun.net.httpserver` del JDK a través de `FakeProviderServer`. Descartados: `firebase-admin` y
`google-auth-library-oauth2-http` (research.md, Decisión 1).

**Storage**: sin cambios de forma. Solo un documento sembrado nuevo (`channel_catalog`, `_id = PUSH`) con la
forma existente (data-model.md).

**Testing**: JUnit 5 + Mockito + `StepVerifier`. Unitario puro para el clasificador y la carga de
credenciales; obtenedor de autorización y adaptador contra **dos** `FakeProviderServer` reales
(autorización y envío); par RSA generado en la prueba, sin claves en el repositorio; **dos clases E2E
nuevas** con Testcontainers (Mongo + RabbitMQ) + `WebTestClient`, una con credencial y otra con la
configuración por defecto, porque necesitan contextos de Spring distintos.

**Target Platform**: mismo deployable. Una de dos variables de credencial para enviar de verdad y cinco
opcionales (`FCM_BASE_URL`, `FCM_TOKEN_URL`, `FCM_TIMEOUT_MS`, `FCM_CONNECT_TIMEOUT_MS`,
`NOTIFICATION_PUSH_PROVIDERS`).

**Project Type**: hexagonal — un adaptador de salida nuevo en `infrastructure`, configuración de catálogo.
Ninguna regla de negocio cambia de lugar.

**Performance Goals**: sin objetivo propio. Restricción de acotación: ninguna llamada excede su tiempo de
espera (10 s por defecto); con renovación de autorización, dos llamadas acotadas en serie, una vez por hora
(Decisión 9).

**Constraints**: `core` sin Spring y, en esta historia, sin cambios (Principio I); cero comentarios
explicativos (Principio III); cliente reactivo, sin `.block()` nuevo ni E/S bloqueante en hilos reactivos
(ADR-0003); ninguna credencial, token, contenido o identificador completo en código, configuración
versionada, registros o mensajes (RNF-09); ninguna prueba llama al proveedor real; ningún literal de prueba
con forma de credencial real (Decisión 11).

**Scale/Scope**: `core`: 0 cambios. `utils`: 0 cambios. `infrastructure` producción: 10 clases nuevas
(adaptador, obtenedor de autorización, clasificador, 3 records de petición/respuesta del proveedor,
propiedades, carga de credenciales, cuenta de servicio, configuración), `application.yml` y descripciones de
`api-notificaciones.yaml`. Pruebas: 1 ayuda (`FcmTestCredentials`) y 6 clases nuevas. Sin migración de
datos.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Re-evaluado después de Phase 1: sin cambios, sigue en PASS (con VI pendiente de aprobación).

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. `core` no cambia: se reutilizan
  `NotificationSenderPort`, `ProviderDisabledException`, `ContentSchemaValidator` y `Recipient` tal cual.
  Todo el conocimiento del proveedor (OAuth, JSON, clasificación) vive en `infrastructure`.
  `FcmProviderProperties`, `FcmCredentials` y `FcmServiceAccount` van a `config`; el adaptador y el
  obtenedor de autorización, a `adapter.out.provider`, que depende de `config` y nunca al revés.
  `HexagonalArchitectureTest` y `ModularityTests` deben seguir en verde.
- **II. Contract-first** — PASS. Ningún endpoint nuevo. Las descripciones del contrato público se editan
  **antes** del código (`contracts/api-notificaciones-cambios.md`). El contrato de salida del tercero queda
  en `contracts/fcm-http-v1-api.md` antes del adaptador.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). El porqué de la tabla del
  clasificador, del margen de renovación, de los límites 100/900 y de no validar el identificador vive en
  research.md. El literal JSON de `application.yml` no se comenta.
- **IV. Calidad verificada, no declarada** — PASS condicionado. Dos clases E2E explícitas en `tasks.md`
  (`FcmPushDeliveryE2ETest`, `FcmDisabledProviderE2ETest`). SC-006 con aserción de `Duration` en canje y
  envío; SC-004 inspeccionando registros y mensajes reales, con control positivo (el identificador
  enmascarado sí aparece); SC-008 contando peticiones de canje; SC-011 con `requests().isEmpty()` en los
  **dos** servidores simulados; SC-013 comprobando que no se persistió nada, no solo el `400`. Cobertura
  ≥80 % líneas / ≥70 % ramas con `./mvnw -B -ntp verify` completo.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-091-fcm-push`, commits de una línea en español sin
  tildes, sin trailers, artefactos de spec-kit en la misma rama. Los cambios ajenos (`Recipient.java`,
  `spring.application.name`) no entran en ningún commit (research.md, Decisión 15).
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PENDIENTE. Spec y plan en estado Pendiente;
  Q1–Q4 registradas con respuesta recomendada y pendientes de confirmación. No se genera `tasks.md` ni se
  implementa nada hasta que el usuario cambie `## Estado del plan` a Aceptado y la rama contenga HU2-090.
- **VII. Sin atajos** — PASS, con pendientes documentados con dueño y fecha, ninguno tapado: push duplicado
  sin idempotencia del proveedor (2026-10-31); canal PUSH ausente en entornos ya sembrados (2026-10-31);
  aviso al cliente de identificadores invalidados (2026-12-31); validación del destinatario por canal
  (2026-12-31); credenciales por tenant (2026-12-31); HU2-038, HU2-039, HU2-052 (research.md, Decisión 14).
  Si FindSecBugs señala la lectura del archivo montado, la exclusión se documenta aquí y se reporta al
  usuario antes de añadirla (Decisión 3); no se silencia.
- **VIII. Confiabilidad y durabilidad** — PASS. Credencial ausente, ambigua o ilegible → proveedor
  deshabilitado, notificación intacta y trazable en la DLQ, nunca quemada como fallida (por eso la clave se
  valida al arrancar). Fallo de transporte o de canje transitorio → recuperable y reintentos existentes.
  Fallo permanente (incluido `UNREGISTERED`) → terminal, trazable y reencolable a mano (`FAILED → PENDING`).
  Contenido demasiado largo → rechazado **antes** de aceptarse. Riesgo abierto: duplicado, documentado.
- **IX. Observabilidad y trazabilidad** — PASS. Cada despacho registra `notificationId`, `tenantId`,
  `providerId`, categoría, código HTTP y `providerMessageId` o `providerErrorCode`, con el identificador
  enmascarado; el fallo del canje se distingue con `stage=authorization`. El registro estructurado a nivel
  de aplicación (RNF-10) sigue siendo una brecha preexistente, fuera de alcance.
- **Restricciones técnicas** — PASS. `WebClient` sobre `ReactorClientHttpConnector` para canje y envío,
  sin `.block()` nuevo; la lectura del archivo de credencial ocurre en el arranque, no en un hilo reactivo;
  la firma es CPU, una vez por hora. Ninguna credencial versionada: las dos propiedades de credencial se
  declaran vacías. Extensibilidad por catálogo (ADR-0009): el canal y sus límites se declaran, no se
  programan. Sin cambios en bloqueo optimista ni en idempotencia de aceptación.
- **Ack manual y DLQ de RabbitMQ** — **sin excepción que justificar**. No se toca `RabbitConfig`,
  `RabbitRetryConfig` ni `NotificationDispatchListener`.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/010-fcm-proveedor-push/
├── plan.md              # This file (/speckit-plan command output)
├── spec.md              # /speckit-specify + /speckit-clarify (Q1–Q4 pendientes de confirmación)
├── research.md          # Phase 0 output — 18 decisiones (0–17)
├── data-model.md        # Phase 1 output — sin cambios de forma; documento PUSH sembrado
├── quickstart.md        # Phase 1 output — validación, alta manual del canal, prueba de humo
├── contracts/
│   ├── fcm-http-v1-api.md              # contrato de SALIDA hacia el proveedor (canje + envío)
│   └── api-notificaciones-cambios.md   # cambios (solo descripciones) del contrato público
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NO lo crea /speckit-plan)
```

### Source Code (repository root)

```text
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── FcmNotificationProvider.java     # NUEVO: @Component, NotificationSenderPort, providerId fcm;
│                                    # @Qualifier("fcmWebClient"); enmascarado privado del identificador
├── FcmAccessTokenProvider.java      # NUEVO: aserción JWT RS256, canje, caché con margen, invalidación
├── FcmResponseClassifier.java       # NUEVO: estatico y puro, status/error -> AttemptResult
├── FcmSendRequest.java              # NUEVO: record message{token, notification{title?, body}}, NON_NULL
├── FcmSendResponse.java             # NUEVO: record (name), ignoreUnknown
└── FcmErrorResponse.java            # NUEVO: record error{status, details[errorCode]}, sin message;
                                     # List.copyOf en constructor compacto y accesor

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/
├── FcmProviderProperties.java       # NUEVO: @ConfigurationProperties("notification.provider.fcm");
│                                    # defaults con Long; toString sin credenciales
├── FcmCredentials.java              # NUEVO: load(properties) -> cuenta de servicio o motivo
├── FcmServiceAccount.java           # NUEVO: projectId, clientEmail, sign(byte[]); la clave no sale
└── FcmProviderConfig.java           # NUEVO: @Bean fcmWebClient (timeouts, sin wiretap) y @Bean
                                     # fcmCredentials

infrastructure/src/main/resources/
├── application.yml                  # MODIFICADO: canal PUSH (proveedores + forma de contenido) y bloque
│                                    # notification.provider.fcm sin valores de credencial
└── static/openapi/api-notificaciones.yaml # MODIFICADO: solo descripciones (Decisión 16)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── FcmTestCredentials.java          # NUEVO: par RSA en tiempo de ejecucion + JSON de cuenta de servicio
│                                    # armado por concatenacion (Decisión 11)
├── FcmResponseClassifierTest.java   # NUEVO
├── FcmAccessTokenProviderTest.java  # NUEVO
├── FcmNotificationProviderTest.java # NUEVO
├── FcmPushDeliveryE2ETest.java      # NUEVO: E2E del Principio IV con credencial de prueba
└── FcmDisabledProviderE2ETest.java  # NUEVO: E2E con la configuracion por defecto

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/config/
└── FcmCredentialsTest.java          # NUEVO
```

**Structure Decision**: sin módulos ni paquetes nuevos. El adaptador vive junto a los otros tres en
`adapter/out/provider`; propiedades, carga de credenciales y `WebClient` en `infrastructure/config`, como
los de correo y SMS. `FakeProviderServer` se reutiliza sin cambios (dos instancias por prueba). Nada va a
`utils`: la única pieza compartida entre `adapter` y `config` es la cuenta de servicio, que vive en `config`.
`core` no gana ningún concepto de "push" ni de "dispositivo".

## Diseño del adaptador

Orden dentro de `send(notification)`:

1. **Deshabilitado** → `Mono.error(ProviderDisabledException)`. Sin canje, sin envío, sin intento, sin
   cambio de estado. Decidido una sola vez al construirse. Verificado con
   `authServer.requests().isEmpty()` **y** `fcmServer.requests().isEmpty()` además del resultado.
2. **Obtener autorización** del `FcmAccessTokenProvider` (en caché o canje nuevo). Si el canje falla →
   `FcmResponseClassifier` sobre su código HTTP o su excepción; registro con `stage=authorization`; no se
   envía nada (se afirma `fcmServer.requests().isEmpty()`).
3. **Construir el mensaje** `FcmSendRequest`: `token` = dirección del destinatario **tal cual** (Q1, sin
   comprobación de forma); `title` = asunto u omitido; `body` = cuerpo, ya validado contra la forma del
   canal (Q2).
4. **Llamar** `POST /v1/projects/{projectId}/messages:send` con `Authorization: Bearer`. Tiempos de espera
   fijados en el `HttpClient` del bean.
5. **Leer y clasificar**: `exchangeToMono` lee `FcmSendResponse` o `FcmErrorResponse` (tolerando cuerpo
   vacío o ilegible) y `FcmResponseClassifier.classifyStatus` decide con el código HTTP; `401` además
   invalida el token en caché; en el camino de error, `onErrorResume` con `classifyError` →
   `RECOVERABLE_FAILURE`.
6. **Registrar** según research.md, Decisión 10.

El adaptador **no** reintenta por su cuenta: los reintentos son del consumidor y de `RetryPolicy`.

## Orden de implementación y puntos de control

Paso 0, antes de `/speckit-tasks`: **rebasar la rama sobre HU2-090** (research.md, Decisión 0) y confirmar
que `FakeProviderServer` existe y que `BrevoNotificationProvider` y `TwilioNotificationProvider` tienen
`@Qualifier`.

Orden por tarea: escribir la prueba, verla fallar por la razón correcta, implementar, ejecutar la clase,
`spotless:apply`.

1. **Contrato público** — descripciones de `api-notificaciones.yaml` (Principio II, antes del código).
2. **`FcmResponseClassifier` + su prueba** — toda la tabla de la Decisión 5.
3. **`FcmTestCredentials`** (ayuda de pruebas, Decisión 11) — revisar el diff antes de commitear: ningún
   literal contiguo con forma de clave PEM, correo de cuenta de servicio, token de acceso o identificador
   real.
4. **`FcmProviderProperties`, `FcmServiceAccount`, `FcmCredentials` + `FcmCredentialsTest`** — cada fila de
   motivos de la Decisión 3; ningún motivo ni `toString()` contiene fragmentos de la credencial. Ejecutar
   SpotBugs en `infrastructure` aquí: si señala `PATH_TRAVERSAL_IN`, detenerse y reportar (Decisión 3).
5. **`FcmProviderConfig`** (`fcmWebClient` + `fcmCredentials`) — correr `BrevoEmailDeliveryE2ETest` y
   `TwilioSmsDeliveryE2ETest` inmediatamente: demuestran que el contexto arranca con tres `WebClient`.
6. **`FcmAccessTokenProvider` + `FcmAccessTokenProviderTest`** — firma verificada con la clave pública,
   *claims*, reutilización (una petición para diez obtenciones), renovación con `expires_in` dentro del
   margen, fallo no memorizado, `Duration` explícita en el tiempo de espera del canje.
7. **Records del proveedor, `FcmNotificationProviderTest`, `FcmNotificationProvider`** — el caso
   deshabilitado afirma `requests().isEmpty()` en los dos servidores; el caso de canje fallido afirma cero
   peticiones de envío; `401` invalida la caché.
8. **`application.yml`** siguiendo research.md, Decisión 15 (`git stash push` del cambio ajeno antes de
   editar, commit solo de lo propio, `git stash pop` al final de la historia; conflicto → se reporta).
9. **`FcmPushDeliveryE2ETest` y `FcmDisabledProviderE2ETest`** — tareas E2E explícitas del Principio IV.
10. **Regresión**: `ProviderRoutingE2ETest`, `ChannelCatalogE2ETest`, pruebas de correo y SMS,
    `HexagonalArchitectureTest`, `ModularityTests`.
11. **`./mvnw -B -ntp verify` completo y en verde.** Si las únicas que fallan son `DeadLetterQueueE2ETest`
    y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo explícitamente: sobre esas dos
    decide CI.

## Riesgos de esta implementación

| Riesgo | Cómo lo acota el plan |
|---|---|
| El flujo de autorización propio tiene un error que solo el proveedor real revela. | Prueba que verifica firma y *claims* con la clave pública; prueba de humo contra un proyecto real (quickstart § 3); plan de respaldo (c) que sustituye solo el obtenedor (Decisión 1). |
| El escaneo de secretos bloquea el push por literales sintéticos (ocurrió en HU2-090). | Par RSA generado en tiempo de ejecución; JSON y valores reconocibles armados por concatenación; revisión explícita del diff en el paso 3 (Decisión 11). |
| Un mensaje de excepción filtra la clave privada al registro (Jackson incluye fragmentos de la entrada). | Motivos de deshabilitación con texto fijo; nunca se registra `getMessage()` de parseo ni de transporte; `FcmCredentialsTest` y la E2E buscan fragmentos de la clave en los registros. |
| FindSecBugs señala `PATH_TRAVERSAL_IN` en la lectura del archivo montado. | Lectura con `FileSystemResource`; si igual lo señala, exclusión acotada documentada aquí y reportada antes de añadirla (paso 4). |
| El tercer `WebClient` rompe el arranque del contexto. | `@Qualifier("fcmWebClient")` y ejecución de las E2E de correo y SMS en el paso 5, antes de seguir. |
| Canjes concurrentes al caducar el token disparan ráfagas contra el servicio de autorización. | Un único `Mono` compartido en vuelo (Decisión 4); prueba de diez obtenciones → una petición. |
| Sembrar PUSH rompe pruebas que dan por hecho que no existe. | Búsqueda de `PUSH` en el código y las pruebas: sin resultados. Se confirma en el paso 10. |
| El literal JSON de la forma de contenido se rompe al editarse en YAML. | Comillas simples en YAML y E2E que envía 100/900 y 101/901 con la configuración por defecto. |
| La cobertura de ramas baja por la lectura tolerante de las respuestas. | `FcmNotificationProviderTest` cubre cuerpo válido, vacío e ilegible en aceptación y rechazo; no se escriben pruebas solo para el porcentaje. |

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
