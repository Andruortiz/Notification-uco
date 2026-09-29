# Implementation Plan: Adjuntar un archivo a una notificación

**Branch**: `feature/HU2-092-adjuntar-archivo-notificacion` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-adjuntar-archivo-notificacion/spec.md`

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

Hoy una notificación es solo texto (`subject` + `body`). Esta historia le agrega una lista opcional de
adjuntos, su validación en la aceptación y el contrato, sin enviar el archivo por ningún proveedor real.

El plan está construido sobre las **respuestas recomendadas** de `spec.md § Clarifications`, todas
pendientes de confirmación. La que más pesa es Q1, reservada por el usuario:

1. **Q1 — Referencia https (recomendada)**. Cada adjunto es `{ fileName, contentType, sizeBytes, url }`.
   El servicio no descarga ni guarda el archivo; guarda la referencia con la notificación. Si el usuario
   elige contenido embebido, la sección [Si Q1 cambia a contenido embebido](#si-q1-cambia-a-contenido-embebido)
   lista el rediseño.
2. **Q2 — Lista** de hasta 5 adjuntos, con máximo por canal.
3. **Q3 — Reglas por canal dentro de `contentSchema`** (el mismo JSON Schema que hoy fija `maxLength`),
   cerradas por defecto: un canal sin `properties.attachments` no acepta adjuntos. La configuración por
   defecto no habilita ningún canal.
4. **Q4 — Falla cerrada en el despacho**: `NotificationSenderPort.supportsAttachments()`; con adjuntos y un
   proveedor sin soporte, `PERMANENT_FAILURE` sin llamar al proveedor. Solo `simulated` los soporta hoy.
5. **Q5 — Lote por elemento**: un adjunto inválido rechaza solo su elemento.

Capas: `core` gana `Attachment`, `AttachmentPolicy` (topes globales: 5 adjuntos, 10 MiB, lista cerrada
de tipos, nombre seguro, `https` sin credenciales) e `InvalidAttachmentException`; extiende
`NotificationContent`, `ContentSchemaValidator`, `NotificationSenderPort` y los tres casos de uso de
aceptación, lote y despacho. `infrastructure` extiende el request REST, el documento de MongoDB, los
cuatro adaptadores de proveedor y registra aceptación y rechazo con nombre, tipo y tamaño únicamente.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB,
`com.networknt:json-schema-validator` 1.4.0 (ya en `core`), Jackson (ya en `core`). Sin dependencias
nuevas.

**Storage**: MongoDB, colección `notifications`: subdocumentos `attachments` embebidos. Sin colecciones,
índices ni migraciones nuevas; documentos anteriores se leen con lista vacía.

**Testing**: JUnit 5, Mockito, `StepVerifier`, `@WebFluxTest`, `@SpringBootTest(RANDOM_PORT)` +
Testcontainers (MongoDB, RabbitMQ) + `WebTestClient`, Logback `ListAppender`, ArchUnit, Spring Modulith.

**Target Platform**: contenedor en Kubernetes (varias réplicas).

**Project Type**: microservicio hexagonal — `core` + `infrastructure`.

**Performance Goals**: sin objetivo propio; la validación es en memoria sobre ≤ 5 elementos. El cuerpo de
la solicitud sigue muy por debajo del límite de 256 KB por defecto del servidor. SC-005 fija el único
umbral de tiempo (≤ 5 s con refresco de 1 s).

**Constraints**: `core` sin Spring y sin registros; ningún `.block()` nuevo; la `url` nunca en registros,
errores, eventos, mensajes de despacho ni respuestas de consulta; el servicio no descarga la `url`.

**Scale/Scope**: ≤ 5 adjuntos por notificación; pocos KB extra por documento.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Re-evaluado después de Phase 1: sin cambios, PASS (con VI pendiente de aprobación).

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS. Reglas globales, reglas por canal y la regla de
  despacho viven en `core` (`domain/policy`, `usecase`) sin Spring. `NotificationSenderPort` gana un
  método que los adaptadores implementan. El registro vive en el adaptador REST, no en `core`.
  `HexagonalArchitectureTest` y `ModularityTests` deben seguir en verde.
- **II. Contract-first** — PASS. `contracts/api-notificaciones-cambios.md` se aplica a
  `api-notificaciones.yaml` como **primera tarea**, antes de tocar el request o el controlador. No hay
  operaciones nuevas: se extiende `POST /notifications` (CRUD natural) y, por herencia del esquema, el
  lote.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). Los porqués (validar en
  la aceptación y no en el constructor, `toString` sin `url`, método abstracto) viven en research.md.
- **IV. Calidad verificada, no declarada** — PASS condicionado. E2E explícita
  `NotificationAttachmentE2ETest` en `tasks.md`, con SC-001 a SC-006 automatizados (SC-005 con `Duration`
  explícita; SC-003 y SC-006 con control positivo). SC-007 en prueba de caso de uso porque el lote no tiene
  operación HTTP. Cobertura ≥ 80 % líneas / ≥ 70 % ramas con `./mvnw -B -ntp verify` completo.
- **V. Trazabilidad en git** — PASS. Rama `feature/HU2-092-adjuntar-archivo-notificacion`, commits de una
  línea en español sin tildes, artefactos de spec-kit en la misma rama; los cambios ajenos (`.claude/`,
  `docs/`) no entran en ningún commit.
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PENDIENTE. Spec y plan en estado Pendiente;
  Q1–Q5 con respuesta recomendada. No se genera `tasks.md` ni se implementa nada hasta que el usuario
  confirme las respuestas (en especial Q1) y cambie `## Estado del plan` a Aceptado.
- **VII. Sin atajos** — PASS. Pendientes con dueño y fecha en spec.md § Risks: tamaño y tipo declarados
  (2026-12-31), alojamiento a cargo del cliente (2026-12-31), motivo no textual del fallo por proveedor
  sin adjuntos (2026-12-31), registros estructurados incompletos (2026-12-31). Método abstracto en lugar
  de un `default` que ocultaría a un adaptador que no pensó en adjuntos.
- **VIII. Confiabilidad y durabilidad** — PASS. Los adjuntos se guardan con la notificación en la misma
  escritura antes de responder `202`; reintentos y reencolado los leen del mismo documento. Una
  notificación con adjuntos nunca queda entregada sin ellos: falla de forma trazable (historial con
  proveedor y fallo definitivo, evento `NotificationFailed`).
- **IX. Observabilidad y trazabilidad** — PASS con pendiente declarado. Aceptación y rechazo con adjuntos
  quedan registrados con tenant, identificador externo, `notificationId` (si existe) y metadatos; el
  formato sigue el `clave=valor` actual, no JSON (brecha RNF-10 preexistente, en Risks).
- **Restricciones técnicas** — PASS. Reactivo sin bloqueo. Ningún secreto ni la `url` (tratada como
  sensible) en registros, respuestas HTTP o mensajes de RabbitMQ. Extensibilidad por catálogo
  (ADR-0009): habilitar adjuntos en un canal es editar su `contentSchema`; un proveedor nuevo declara
  `supportsAttachments()` sin tocar el núcleo. Bloqueo optimista intacto (misma escritura del agregado).
- **Ack manual y DLQ de RabbitMQ** — **sin excepción que justificar**. El consumidor de despacho no cambia;
  el nuevo desenlace (`PERMANENT_FAILURE` sin llamar al proveedor) se persiste por el mismo camino que
  cualquier otro resultado antes de terminar el procesamiento del mensaje.

No violations requiring justification — Complexity Tracking section left empty.

## Project Structure

### Documentation (this feature)

```text
specs/012-adjuntar-archivo-notificacion/
├── plan.md              # This file (/speckit-plan command output)
├── spec.md              # /speckit-specify + /speckit-clarify (Q1–Q5 pendientes de confirmación)
├── research.md          # Phase 0 output — 11 decisiones
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output — validación automatizada y manual
├── contracts/
│   └── api-notificaciones-cambios.md   # attachments, Attachment, descripciones
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NO lo crea /speckit-plan)
```

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/valueobject/
│   ├── Attachment.java                   # NUEVO: record; normaliza contentType; toString sin url
│   └── NotificationContent.java          # MODIFICADO: + List<Attachment> attachments, hasAttachments()
├── domain/policy/
│   ├── AttachmentPolicy.java             # NUEVO: topes globales, validate(List<Attachment>)
│   └── ContentSchemaValidator.java       # MODIFICADO: cerrado por defecto; nodo con metadatos sin url
├── exception/
│   └── InvalidAttachmentException.java   # NUEVO: posición + regla
├── port/out/
│   └── NotificationSenderPort.java       # MODIFICADO: + boolean supportsAttachments() (abstracto)
└── usecase/
    ├── SendNotificationService.java      # MODIFICADO: AttachmentPolicy antes de ContentSchemaValidator
    ├── SendNotificationBatchService.java # MODIFICADO: InvalidAttachmentException → rejected
    └── DispatchNotificationService.java  # MODIFICADO: falla cerrada sin llamar al proveedor

core/src/test/java/co/edu/uco/notification/core/
├── domain/AttachmentTest.java                    # NUEVO
├── domain/AttachmentPolicyTest.java              # NUEVO
├── domain/ContentSchemaValidatorTest.java        # MODIFICADO
├── domain/NotificationContentTest.java           # MODIFICADO
├── port/out/NotificationSenderRegistryTest.java  # MODIFICADO: FakeSender implementa supportsAttachments
└── usecase/
    ├── SendNotificationServiceTest.java          # MODIFICADO
    ├── SendNotificationBatchServiceTest.java     # MODIFICADO: SC-007
    ├── DispatchNotificationServiceTest.java      # MODIFICADO: SC-006 unitario
    └── QueryChannelCatalogServiceTest.java       # MODIFICADO: doble implementa supportsAttachments

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/
│   ├── AttachmentRequest.java            # NUEVO: record
│   ├── AttachmentLogFormatter.java       # NUEVO: [fileName|contentType|sizeBytes], control chars fuera
│   ├── SendNotificationRequest.java      # MODIFICADO: + List<AttachmentRequest> attachments
│   ├── NotificationController.java       # MODIFICADO: mapeo + registro de aceptación/rechazo
│   └── NotificationExceptionHandler.java # MODIFICADO: InvalidAttachmentException → 400
├── adapter/out/mongo/
│   ├── AttachmentDocument.java           # NUEVO: record
│   ├── NotificationDocument.java         # MODIFICADO: + List<AttachmentDocument> attachments
│   └── NotificationDocumentMapper.java   # MODIFICADO: ida y vuelta; null → vacío
└── adapter/out/provider/
    ├── SimulatedNotificationProvider.java # MODIFICADO: supportsAttachments() = true
    ├── BrevoNotificationProvider.java     # MODIFICADO: false
    ├── TwilioNotificationProvider.java    # MODIFICADO: false
    └── FcmNotificationProvider.java       # MODIFICADO: false

infrastructure/src/main/resources/static/openapi/
└── api-notificaciones.yaml               # MODIFICADO: primera tarea (Principio II)

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/
│   ├── NotificationControllerTest.java       # MODIFICADO
│   └── NotificationAttachmentE2ETest.java    # NUEVO: E2E del Principio IV
├── adapter/out/mongo/
│   └── NotificationMongoAdapterTest.java     # MODIFICADO: ida y vuelta con adjuntos; documento antiguo
└── adapter/out/provider/
    ├── SimulatedNotificationProviderTest.java  # MODIFICADO: true
    ├── BrevoNotificationProviderTest.java      # MODIFICADO: false
    ├── TwilioNotificationProviderTest.java     # MODIFICADO: false
    ├── FcmNotificationProviderTest.java        # MODIFICADO: false
    └── ProviderRoutingE2ETest.java             # MODIFICADO: RecordingNotificationSender implementa supportsAttachments
```

**Structure Decision**: sin módulos ni paquetes nuevos. `Attachment` junto a los demás value objects,
`AttachmentPolicy` junto a `ContentSchemaValidator`. `application.yml` y el catálogo por defecto no se
tocan (Q3).

## Diseño

### Aceptación (`SendNotificationService`)

`findActiveRoute` → `AttachmentPolicy.validate(content.attachments())` → `ContentSchemaValidator.validate`
→ duplicada → `Notification.accept` → `save` → `publish` → `enqueueForDispatch`. Cualquier error de
validación ocurre antes de `save`: nada se guarda ni se encola (FR-006, SC-002). El orden respecto de la
idempotencia no cambia (research.md, Decisión 7).

### Validación por canal (`ContentSchemaValidator`)

Si hay adjuntos y el esquema está vacío o no tiene `properties.attachments` en la raíz → rechazo "channel
does not accept attachments". Si no, el nodo validado incluye `attachments[i].{fileName, contentType,
sizeBytes}`; los mensajes del validador ya traen la ruta `$.attachments[i]…`, que da la posición. La `url`
no está en el nodo.

### Despacho (`DispatchNotificationService.sendThrough`)

```text
outcome = notification.content().hasAttachments() && !sender.supportsAttachments()
    ? Mono.just(PERMANENT_FAILURE)
    : sender.send(notification)
```

`markQueued` y `applyOutcome` como hoy → `FAILED` con intento `PERMANENT_FAILURE` a nombre del proveedor.

### REST

`toCommand` mapea `attachments` (nulo → vacío) a `Attachment.of(...)`. Si la solicitud trae adjuntos, el
`Mono` de envío registra con `doOnSuccess` (aceptada) y `doOnError` (rechazada) mediante
`AttachmentLogFormatter`. El manejador global convierte `InvalidAttachmentException` en `400`.

## Si Q1 cambia a contenido embebido

Si el usuario elige la opción A (o C) de Q1, este plan pasa a **Versión 2** con estos cambios; el resto
(Q2–Q5, reglas por canal en `contentSchema`, falla cerrada en el despacho, registros) se conserva.

| Área | Cambio |
|---|---|
| Contrato | `Attachment.url` → `content` (base64); límite total de la solicitud documentado; 413 o 400 al excederlo. |
| Tamaño y tipo | `sizeBytes` deja de ser declarado: se calcula del contenido decodificado (o se exige y se compara). El tipo se verifica contra la firma de los primeros bytes (PDF, PNG, JPEG, GIF, WEBP; para texto y ofimática, regla aparte). Desaparece el riesgo "tamaño y tipo declarados". |
| Servidor | `spring.codec.max-in-memory-size` sube de 256 KB a ~70 MB (5 × 10 MiB × 4/3) o se bajan los topes (p. ej. 10 MiB en total por notificación). Presión de memoria: el lote procesa 16 elementos a la vez. |
| Almacenamiento | Puerto nuevo en `core`, `AttachmentStoragePort` (`store`, `load`, `delete`), y adaptador GridFS (`ReactiveGridFsTemplate`, sin infraestructura nueva) u objeto externo. La notificación guarda `storageId` por adjunto, no los bytes. |
| Durabilidad (Principio VIII) | Dos escrituras sin atomicidad: archivos y luego notificación. Hace falta limpieza de huérfanos si falla la segunda, y definir qué pasa con los archivos de una duplicada. |
| Retención | Política de borrado de archivos tras estado terminal; hoy el proyecto no tiene política de retención. Pendiente con dueño y fecha si no se resuelve en la historia. |
| Despacho | El proveedor recibe los bytes cargados desde el almacén. SMS y push necesitarán, en sus historias, publicar el archivo en una dirección firmada accesible por el proveedor. |
| Registros | Igual, pero el riesgo pasa del `url` al contenido: desactivar cualquier registro del cuerpo HTTP. |
| Pruebas | + integración del adaptador de almacenamiento con Testcontainers; E2E con archivos reales y verificación de tipo real. |
| Tamaño de la historia | De M a L (estimado ~2×). |

## Orden de implementación y puntos de control

Orden por tarea: escribir la prueba, verla fallar por la razón correcta, implementar, ejecutar la clase,
`spotless:apply`.

1. **Contrato público** — aplicar `contracts/api-notificaciones-cambios.md` a `api-notificaciones.yaml`.
2. **`NotificationSenderPort.supportsAttachments()`** + cuatro adaptadores + tres dobles de prueba + sus
   pruebas. Compilar `core` e `infrastructure` antes de seguir: el método abstracto rompe cualquier
   implementación olvidada.
3. **`Attachment`** + `AttachmentTest` (normalización, `toString` sin `url`).
4. **`NotificationContent`** + `NotificationContentTest` (lista por defecto vacía, copia, constructor de
   dos argumentos intacto).
5. **`InvalidAttachmentException` y `AttachmentPolicy`** + `AttachmentPolicyTest` (cada regla, límites
   inclusivos, mensaje sin `url`).
6. **`ContentSchemaValidator`** + `ContentSchemaValidatorTest` (cerrado por defecto, `maxItems`, `enum`,
   `maximum`, posición en el mensaje, sin `url`).
7. **`SendNotificationService`** + prueba (rechazo sin `save`/`publish`/`enqueue`; duplicada inválida
   rechazada; duplicada válida devuelta).
8. **`SendNotificationBatchService`** + prueba (SC-007).
9. **`DispatchNotificationService`** + prueba (sin soporte → `FAILED`, emisor no invocado; con soporte o
   sin adjuntos → flujo normal).
10. **MongoDB**: `AttachmentDocument`, `NotificationDocument`, mapper + `NotificationMongoAdapterTest`.
11. **REST**: `AttachmentRequest`, `SendNotificationRequest`, `AttachmentLogFormatter`, controlador,
    manejador + `NotificationControllerTest`.
12. **`NotificationAttachmentE2ETest`** — tarea E2E explícita del Principio IV (research.md,
    Decisión 11).
13. **Regresión**: `ProviderRoutingE2ETest`, `ChannelCatalogQueryE2ETest`, E2E de proveedores,
    `NotificationControllerSearchE2ETest`, `NotificationLiveUpdates*E2ETest`, `HexagonalArchitectureTest`,
    `ModularityTests`.
14. **`./mvnw -B -ntp verify` completo y en verde.** Si las únicas que fallan son `DeadLetterQueueE2ETest`
    y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo explícitamente: sobre esas dos
    decide CI.

## Riesgos de esta implementación

| Riesgo | Cómo lo acota el plan |
|---|---|
| El método abstracto rompe implementaciones no previstas. | Búsqueda hecha: 4 adaptadores + 3 dobles de prueba; los mocks de Mockito devuelven `false`. Compilación completa en el paso 2. |
| La `url` se filtra por un `toString` o un mensaje de validación. | `url` fuera del nodo validado; `Attachment.toString()` sin `url`; SC-003 lo comprueba con una dirección reconocible en registros, errores, eventos y consultas, con control positivo. |
| Un nombre de archivo malicioso parte una línea de registro (inyección CRLF, FindSecBugs). | `AttachmentLogFormatter` reemplaza caracteres de control; si SpotBugs lo marca igualmente, se corrige el código en lugar de añadir una exclusión sin reportarla. |
| Un esquema de canal que declara `attachments` sin `enum` acepta cualquier tipo. | La lista global cerrada de `AttachmentPolicy` aplica siempre antes del esquema (Edge Cases). |
| Cambiar el constructor canónico de `NotificationContent` rompe llamadas existentes. | Se conserva un constructor de dos argumentos; las únicas 2 llamadas directas a `new NotificationContent(...)` están en las fábricas `of` del propio record. |
| Las E2E que editan `channel_catalog` interfieren con otras clases. | Canales de prueba con nombres propios, restaurados en `@BeforeEach`; espera activa a que `GET /channels` los refleje; contexto con propiedades propias. |
| Q1 cambia tras aprobar este plan. | La sección anterior acota el rediseño; ninguna tarea se genera antes de confirmar Q1. |
