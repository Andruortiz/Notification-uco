# Implementation Plan: Integrar Brevo como primer proveedor real de correo

**Branch**: `feature/HU2-089-integrar-brevo-proveedor-correo` | **Date**: 2026-09-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-brevo-proveedor-correo/spec.md`

## Summary

Hoy el componente entrega de punta a punta pero **nadie recibe nada**: el único adaptador de envío es
`SimulatedNotificationProvider`, que devuelve el resultado que le dicte una variable de entorno. HU2-088
dejó listo el mecanismo (`NotificationSenderRegistry` resuelve `providerId → adaptador`, cada adaptador
declara su identidad); esta historia lo estrena con un proveedor real.

El plan: **un adaptador dedicado** (`BrevoNotificationProvider`, `providerId = brevo`) que llama al
endpoint de correo transaccional del proveedor con un `WebClient` reactivo y tiempo de espera explícito
(ADR-0003, sin envolver un cliente bloqueante). Tres piezas alrededor:

1. **Habilitación.** Las credenciales llegan solo por variables de entorno. Si falta la clave o el
   remitente verificado, el adaptador **se registra igual** pero queda deshabilitado, avisa una vez al
   arrancar con el motivo concreto, y cada `send(...)` devuelve `ProviderDisabledException` — una
   excepción nueva de `core`. El efecto observable es el mismo que ya tiene un proveedor sin adaptador:
   no se registra intento, la notificación conserva su estado y el mensaje va a la DLQ con la causa. Se
   cumple el criterio de HU2-046 **para un adaptador** sin inventar el modelo de catálogo que le
   corresponde a esa historia (research.md, Decisión 2).
2. **Clasificación.** `BrevoResponseClassifier`, una función pura y total, traduce cada resultado de la
   llamada — quince filas, incluidos tiempo agotado y error de conexión — a `ACCEPTED`,
   `RECOVERABLE_FAILURE` o `PERMANENT_FAILURE` (research.md, Decisión 4). Vive aparte del adaptador para
   poder probarse sin servidor ni contexto y para acotar la complejidad del adaptador (RNF-15).
3. **Catálogo.** El canal `EMAIL` pasa a listar `simulated, brevo`, con la lista configurable por
   entorno y un valor por defecto seguro para un despliegue sin credenciales. Así el criterio "el canal
   EMAIL lista a brevo" se cumple sin romper desarrollo local ni CI, y a la vez desaparece el riesgo que
   HU2-088 anotó (un `providerId` del catálogo sin adaptador que lo atienda).

`DispatchNotificationService`, `NotificationSenderPort`, `SimulatedNotificationProvider`, la
configuración de RabbitMQ y `api-notificaciones.yaml` **no se tocan**.

**Fuera de alcance explícito, con dueño y fecha** (research.md, Decisión 13): batería de contrato de
adaptadores (HU2-038), gestión de secretos por plataforma (HU2-052), límite de tasa por proveedor
(HU2-039), conmutación al siguiente proveedor (HU2-048), plantillas/adjuntos/webhooks del proveedor.

**Cuatro puntos siguen pendientes de confirmación del usuario** (spec.md § Clarifications, Q1–Q4). Están
resueltos de forma provisional en todo el plan; cada uno indica qué cambia si el usuario decide otra
cosa, y ninguno cambia la arquitectura del adaptador.

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: **ninguna nueva en `pom.xml`**. `WebClient` y `reactor-netty` llegan con
`spring-boot-starter-webflux`, que `infrastructure` ya declara; el `ObjectMapper` ya está en el
contexto; el servidor HTTP de pruebas usa `com.sun.net.httpserver` del propio JDK (research.md,
Decisión 10). `core` sigue sin una sola importación de Spring.

**Storage**: sin cambios. Cero colecciones, documentos, campos, índices o migraciones nuevos
(data-model.md). Lo único que cambia en Mongo es el **contenido sembrado** del documento
`channel_catalog` del canal `EMAIL`, no su forma.

**Testing**: JUnit 5 + Mockito + `StepVerifier`. Tres niveles: unitario puro para el clasificador
(quince filas), adaptador contra un servidor HTTP local real que simula al proveedor (petición
construida, cabeceras, deshabilitación, asunto faltante, tiempo de espera con aserción explícita de
`Duration`), y **una clase E2E nueva** con Testcontainers (Mongo + RabbitMQ) + `WebTestClient` que
ejercita el flujo completo, el camino deshabilitado y la ausencia de fugas en registros y mensajes.
`HexagonalArchitectureTest` y `ModularityTests` deben seguir en verde.

**Target Platform**: mismo deployable de Kubernetes. Dos variables de entorno nuevas obligatorias en el
entorno que quiera enviar de verdad (`BREVO_API_KEY`, `BREVO_SENDER_EMAIL`) y cuatro opcionales.

**Project Type**: hexagonal — un adaptador de salida nuevo en `infrastructure`, más una excepción en
`core`. Ninguna regla de negocio cambia de lugar.

**Performance Goals**: sin objetivo de rendimiento propio. La restricción operativa es de acotación, no
de velocidad: ninguna llamada al proveedor puede exceder su tiempo de espera (10 s por defecto), porque
el consumidor de RabbitMQ bloquea un hilo por despacho en un borde que es preexistente.

**Constraints**: `core` sin Spring (Principio I); cero comentarios explicativos (Principio III); cliente
HTTP reactivo, nunca bloqueante (ADR-0003); ninguna credencial en código, configuración versionada,
registros ni mensajes (RNF-09); ninguna prueba automatizada llama al proveedor real.

**Scale/Scope**: en `core`, 1 clase nueva (excepción) + su prueba. En `infrastructure`, 5 clases nuevas
de producción (adaptador, propiedades, clasificador, cuerpo de la petición, configuración del
`WebClient`), 1 archivo de configuración modificado, 1 ayuda de pruebas y 3 clases de prueba nuevas.
Sin migración de datos.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Re-evaluado después de Phase 1: sin cambios, sigue en PASS.

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. Lo único nuevo en `core` es
  `ProviderDisabledException`: Java puro, sin Spring, hermana de `ProviderNotAvailableException`. Todo el
  conocimiento del proveedor (HTTP, JSON, credenciales, clasificación) vive en
  `infrastructure/adapter/out/provider`. El adaptador implementa un puerto existente sin cambiarlo.
  `HexagonalArchitectureTest` lo verifica y debe seguir en verde.
- **II. Contract-first** — NO APLICA, y se deja constancia para que la revisión no lo lea como un
  contrato omitido. La historia no agrega ni cambia ningún endpoint;
  `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` no se toca. El único
  contrato involucrado es de **salida** y lo define el tercero: queda documentado en
  `contracts/brevo-transactional-email.md` **antes** de escribir el adaptador, que es la misma disciplina
  aplicada a la dirección que corresponde.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). Todo el razonamiento
  vive en `specs/008-brevo-proveedor-correo/`. Atención especial en el clasificador: la tentación de
  anotar cada código HTTP con un comentario es alta; el porqué de cada fila está en research.md,
  Decisión 4, y los nombres de las pruebas son la documentación.
- **IV. Calidad verificada, no declarada** — PASS condicionado. Exige la clase E2E nueva
  (`BrevoEmailDeliveryE2ETest`) además de las unitarias, y `tasks.md` debe llevarla como tarea explícita.
  Dos exigencias adicionales que esta historia se impone: la prueba del tiempo de espera afirma una
  `Duration` explícita (SC-006), no solo "falló"; y la prueba de no filtración inspecciona los registros
  y los mensajes reales, no se declara por inspección visual (SC-004). Cobertura ≥80 % líneas / ≥70 %
  ramas verificada con `./mvnw -B -ntp verify` completo y en verde antes de cerrar.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-089-integrar-brevo-proveedor-correo`, commits de
  una sola línea en español sin tildes; los artefactos de spec-kit se commitean en esta misma rama. Los
  cambios ajenos presentes en el árbol (`Recipient.java`, `spring.application.name`) no entran en ningún
  commit de esta historia — ver research.md, Decisión 12, que es una restricción **operativa** de la
  implementación, no una sugerencia.
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — **GATE ABIERTO**. El spec registra cuatro
  preguntas (Q1–Q4) que **no pudieron formularse al usuario** en esta sesión y quedan pendientes de
  confirmación. La aprobación del usuario a este plan es también la confirmación (o corrección) de esas
  cuatro respuestas provisionales. No se implementa una sola línea antes de esa aprobación.
- **VII. Sin atajos** — PASS, con cinco pendientes documentados con dueño y fecha, ninguno tapado con un
  parche: riesgo de correo duplicado por idempotencia no garantizada (Decisión 8, revisión 2026-10-31);
  siembra del catálogo que no actualiza entornos ya sembrados (Decisión 3, revisión 2026-10-31, con el
  paso manual en `quickstart.md`); HU2-052, HU2-039 y HU2-038 (Decisión 13). Ninguno se resuelve a medias
  dentro de esta historia ni se presenta como resuelto.
- **VIII. Confiabilidad y durabilidad** — PASS y reforzado. Un proveedor deshabilitado o un fallo de
  transporte **nunca** dan una notificación por entregada ni la descartan: o queda intacta y trazable en
  la DLQ (configuración), o registra un intento y entra en el camino de reintentos existente
  (recuperable). El caso `PERMANENT_FAILURE` es terminal pero trazable, y `StatusTransitionPolicy` admite
  `FAILED → PENDING`, así que es recuperable a mano. El riesgo abierto es el **inverso** — duplicado, no
  pérdida — y está documentado, no oculto.
- **IX. Observabilidad y trazabilidad** — PASS. Cada despacho deja `notificationId`, `tenantId`,
  `providerId`, categoría del resultado y código de estado; nunca credencial, asunto, cuerpo ni
  dirección. Las tres formas de fallar son distinguibles por tipo y por efecto: proveedor deshabilitado
  (sin intento, notificación intacta, DLQ con motivo), fallo de entrega recuperable (intento registrado,
  reintento) y fallo permanente (intento registrado, estado terminal). Esta historia **no** introduce
  registro estructurado a nivel de aplicación — eso sigue siendo una brecha abierta del proyecto (RNF-10),
  anterior a esta historia y fuera de su alcance.
- **Restricciones técnicas** — reactivo/no bloqueante por `WebClient` sobre `ReactorClientHttpConnector`,
  nunca `Mono.fromCallable` sobre un cliente bloqueante (ADR-0003, Decisión 1). Ninguna credencial
  versionada; la configuración versionada declara las propiedades **sin valor**, de modo que un
  despliegue sin variables arranca deshabilitado en lugar de con un valor de ejemplo. Sin cambios en
  bloqueo optimista ni en la idempotencia de aceptación. Extensibilidad por catálogo (ADR-0009): sumar
  este proveedor no obliga a tocar el núcleo ni `UseCaseConfig`, porque el registro se arma con la lista
  de beans.
- **Ack manual y DLQ de RabbitMQ** — **sin excepción que justificar**. Esta historia no toca
  `RabbitConfig`, `RabbitRetryConfig` ni `NotificationDispatchListener`; el consumidor conserva su
  confirmación y su cola de mensajes muertos exactamente como están. Se deja dicho explícitamente porque
  la constitución exige justificar por escrito cualquier desviación, y aquí no la hay.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/008-brevo-proveedor-correo/
├── plan.md              # This file (/speckit-plan command output)
├── spec.md              # /speckit-specify + /speckit-clarify
├── research.md          # Phase 0 output — 14 decisiones
├── data-model.md        # Phase 1 output — sin cambios persistidos
├── quickstart.md        # Phase 1 output — validación + prueba manual de humo
├── contracts/
│   └── brevo-transactional-email.md   # contrato de SALIDA hacia el proveedor
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NO lo crea /speckit-plan)
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/exception/
└── ProviderDisabledException.java        # NUEVO: RuntimeException con providerId + motivo; Java puro

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── BrevoNotificationProvider.java        # NUEVO: @Component, implements NotificationSenderPort;
│                                         # providerId() = brevo; decide habilitacion al construirse y
│                                         # avisa una vez; construye la peticion y clasifica la respuesta
├── BrevoProviderProperties.java          # NUEVO: @ConfigurationProperties("notification.provider.brevo")
├── BrevoResponseClassifier.java          # NUEVO: funcion pura y total respuesta/error -> AttemptResult
├── BrevoEmailRequest.java                # NUEVO: record del cuerpo JSON (+ contacto anidado)
└── SimulatedNotificationProvider.java    # SIN CAMBIOS

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/config/
└── BrevoWebClientConfig.java             # NUEVO: @Bean WebClient del proveedor, con responseTimeout y
                                          # connect timeout explicitos; sin wiretap

infrastructure/src/main/resources/
└── application.yml                       # MODIFICADO: canal EMAIL lista simulated,brevo (configurable
                                          # por entorno) + bloque notification.provider.brevo sin
                                          # valores de credencial. Ver research.md, Decision 12

core/src/test/java/co/edu/uco/notification/core/exception/
└── ProviderDisabledExceptionTest.java    # NUEVO: mensaje y accesores

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/provider/
├── FakeBrevoServer.java                  # NUEVO (ayuda de pruebas): servidor HTTP del JDK que registra
│                                         # peticiones y permite programar estado, cuerpo y retardo
├── BrevoResponseClassifierTest.java      # NUEVO: las quince filas de la tabla de clasificacion
├── BrevoNotificationProviderTest.java    # NUEVO: peticion construida, cabeceras, clave de idempotencia,
│                                         # deshabilitado, sin asunto, tiempo de espera con Duration
└── BrevoEmailDeliveryE2ETest.java        # NUEVO: E2E del Principio IV (Mongo + RabbitMQ + WebTestClient
                                          # + FakeBrevoServer); flujo completo, camino deshabilitado y
                                          # ausencia de fugas en registros y mensajes

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/
└── ChannelCatalogSeederTest.java         # MODIFICADO: el canal EMAIL sembrado lista brevo
```

**Structure Decision**: no se crea módulo ni paquete nuevo. El adaptador vive junto a
`SimulatedNotificationProvider` en `adapter/out/provider`, que es donde HU2-088 dejó el punto de
extensión; la configuración del `WebClient` va a `infrastructure/config` con el resto del cableado; y la
excepción acompaña a `ProviderNotAvailableException` en `core/exception`, porque describe un estado del
dominio del despacho (un proveedor que existe pero no puede operar) y no un detalle de HTTP. `core` no
gana ningún concepto de "proveedor de correo".

## Diseño del adaptador

Orden dentro de `send(notification)`, con el porqué de cada paso:

1. **Si el adaptador está deshabilitado** → `Mono.error(ProviderDisabledException)`. Primero, antes de
   tocar nada: no hay llamada, no hay intento, la notificación no cambia. La decisión de habilitación se
   tomó una sola vez al construirse, así que esto es una lectura de un `boolean` por despacho.
2. **Si el asunto está vacío** → `Mono.just(PERMANENT_FAILURE)`. Sin llamar al proveedor: sabemos que lo
   rechazaría. A diferencia del paso 1, **sí** produce un intento y un estado terminal, porque es un dato
   inválido de esa notificación y no un problema del despliegue (research.md, Decisión 6; pendiente Q1).
3. **Construir el cuerpo** a partir de `recipient`, `content` y el remitente configurado, con
   `headers["Idempotency-Key"] = notificationId` (research.md, Decisiones 5 y 8).
4. **Llamar** con el `WebClient` del proveedor: `POST {base-url}/v3/smtp/email`, cabecera `api-key`. El
   tiempo de espera y el de conexión ya están fijados en el `HttpClient` del bean, no por llamada.
5. **Clasificar** con `BrevoResponseClassifier`: el código de estado en el camino normal, el tipo de
   excepción en el camino de error (`onErrorResume`), y el caso por defecto es `RECOVERABLE_FAILURE`
   porque reintentar es menos dañino que descartar.
6. **Registrar** el resultado con `notificationId`, `tenantId`, `providerId`, categoría y código de
   estado. Nunca credencial, asunto, cuerpo ni dirección.

El adaptador **no** reintenta por su cuenta: los reintentos son del consumidor de RabbitMQ y de la
política existente. Introducir un reintento propio aquí multiplicaría los intentos reales y falsearía el
historial.

## Orden de implementación y puntos de control

El orden por tarea es el del repositorio: escribir la prueba, verla fallar por la razón correcta,
implementar, ejecutar la clase de prueba, `spotless:apply`.

1. `ProviderDisabledException` en `core` + su prueba. Es la pieza de la que depende el resto y la única
   que toca `core`; hacerla primero mantiene verde `HexagonalArchitectureTest` desde el inicio.
2. `BrevoResponseClassifier` + `BrevoResponseClassifierTest`. Quince filas, sin red ni contexto: es
   donde vive casi toda la lógica condicional de la historia y donde más barata sale la cobertura de
   ramas.
3. `BrevoProviderProperties`, `BrevoEmailRequest`, `BrevoWebClientConfig`.
4. `FakeBrevoServer` + `BrevoNotificationProviderTest` + `BrevoNotificationProvider`.
5. `BrevoEmailDeliveryE2ETest` — tarea E2E explícita del Principio IV.
6. `application.yml` y `ChannelCatalogSeederTest`. **Último**, y sujeto a research.md, Decisión 12: si
   el cambio ajeno de `spring.application.name` sigue sin commitear, el archivo no entra en ningún commit
   y queda reportado como paso manual.
7. `./mvnw -B -ntp verify` completo y en verde. Si las dos únicas pruebas que fallan son
   `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo
   explícitamente en el informe: sobre esas dos decide CI.

## Riesgos de esta implementación

| Riesgo | Cómo lo acota el plan |
|---|---|
| El E2E filtra credenciales en su propia salida al afirmar que no se filtran. | El `FakeBrevoServer` usa una clave de prueba inventada y las aserciones comprueban **ausencia**; ningún mensaje de fallo de aserción imprime el valor buscado. |
| `ProviderRoutingE2ETest` u otras pruebas existentes dependen de que `EMAIL` liste un solo proveedor. | Esa clase fija sus propios proveedores por `properties` y no depende del valor sembrado; aun así, el paso 6 exige correr la suite completa tras tocar `application.yml`. |
| El adaptador deshabilitado hace ruidosa la DLQ si alguien pone `brevo` como preferente sin credenciales. | Es el comportamiento deseado y el mismo que HU2-088 ya aceptó para un proveedor sin adaptador; el valor por defecto de la configuración evita que ocurra por accidente. |
| La cobertura baja por clases de transporte sin lógica. | `BrevoEmailRequest` y `BrevoProviderProperties` son records que el E2E recorre; no se les escriben pruebas propias solo para subir el porcentaje. |

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Ninguna violación — sección vacía.
