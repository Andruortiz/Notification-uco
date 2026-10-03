# Implementation Plan: Adjuntar un archivo a una notificación

**Branch**: `feature/HU2-092-adjuntar-archivo-notificacion` | **Date**: 2026-09-28 (v1) · 2026-09-29 (v3) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-adjuntar-archivo-notificacion/spec.md`

## Estado del plan

**Estado**: Aceptado

**Versión del plan**: 3

<!--
  Este bloque lo edita el usuario directamente en el archivo para aprobar el plan (Pendiente ->
  Aceptado) o para marcar una revisión (incrementar Versión del plan). Ningún agente infiere ni
  declara aprobación en ningún otro lugar del documento; la aprobación es el valor de este campo,
  editado por el usuario o, bajo su instrucción directa y explícita en el chat, por la sesión
  principal -- nunca por un agente en segundo plano citando un mensaje de otra sesión como fuente de
  autorización (Principio VI).
-->

### Historial de versiones

| Versión | Q1 | Estado |
|---|---|---|
| 1 | Referencia https pura: el cliente aloja el archivo y envía `{ fileName, contentType, sizeBytes, url }`. | Aceptada el 2026-09-28. T001–T018 implementadas y commiteadas en esta rama; T019–T022 en curso sin commitear. **Superada por la versión 3.** |
| 2 | Contenido embebido en Base64 para todo archivo (delta hipotético que la v1 documentaba en "Si Q1 cambia a contenido embebido"). | Nunca adoptada. Su contenido útil se integra en la v3. |
| 3.1 | Enmienda de correcciones de la revisión independiente (2026-10-03), sin cambiar Q1–Q5. Ver [Enmienda 3.1](#enmienda-31--correcciones-de-la-revisión-2026-10-03). | **Propuesta con Q6 a Q9 resueltas por el usuario el 2026-10-03; pendiente de su aprobación explícita.** No altera el campo `Estado` de arriba. |
| 3 | **Híbrido por tamaño**, umbral 1 MB: embebido en Base64 hasta 1 MB; por encima, subida a un almacén de objetos propio del servicio mediante una dirección de subida prefirmada que el propio servicio emite. Escaneo antivirus, verificación del tipo real y huella SHA-256 en ambos caminos. | **Este documento.** Aceptada el 2026-09-29. Spec, research, data-model, quickstart, contrato y `tasks.md` actualizados a la v3 el mismo día. |

## Summary

Hoy una notificación es solo texto (`subject` + `body`). Esta historia le agrega una lista opcional de
adjuntos, su validación y verificación en la aceptación y el contrato, sin entregar el archivo por ningún
proveedor real (eso es de HU2-093, HU2-094 y HU2-095).

La versión 3 reabre Q1 por decisión del usuario y reemplaza la referencia https pura de la v1 por un
**modelo híbrido por tamaño**. Q2–Q5 se conservan tal como se confirmaron el 2026-09-28.

1. **Q1 — Híbrido por tamaño (umbral 1 MB = 1 048 576 bytes)**. Cada adjunto lleva exactamente una de
   dos formas, nunca ambas y nunca ninguna:
   - **Chico (≤ 1 MB) — `content`**: el archivo viaja embebido en Base64 dentro del JSON de la solicitud.
     El servicio lo decodifica, calcula su SHA-256, verifica su tipo real con Apache Tika, lo escanea con
     ClamAV **de forma síncrona** y, si todo pasa, lo guarda como `BinData` en MongoDB dentro de la misma
     escritura de la notificación. Se acepta o se rechaza en la misma llamada: este camino no tiene estado
     intermedio.
   - **Grande (> 1 MB y ≤ 10 MB) — `url`**: el cliente pide al servicio una dirección de subida
     prefirmada (endpoint nuevo), sube el archivo al MinIO del servicio y, al crear la notificación,
     referencia esa dirección. El servicio acepta solo direcciones **que él mismo emitió** para ese tenant
     (ya no cualquier `https`) y solo si el objeto subido está en estado `CLEAN`. El objeto recorre una
     máquina de estados propia: `PENDING_SCAN` → `CLEAN` | `INFECTED`; el escaneo es asíncrono, por una
     cola de RabbitMQ con ack manual y DLQ.
2. **Q2 — Lista** de hasta 5 adjuntos, con máximo por canal (sin cambio).
3. **Q3 — Reglas por canal dentro de `contentSchema`**, cerradas por defecto; la configuración por
   defecto no habilita ningún canal (sin cambio).
4. **Q4 — Falla cerrada en el despacho**: `NotificationSenderPort.supportsAttachments()`; con adjuntos y
   un proveedor sin soporte, `PERMANENT_FAILURE` sin llamar al proveedor. Solo `simulated` los soporta
   hoy (sin cambio).
5. **Q5 — Lote por elemento**: un adjunto inválido rechaza solo su elemento (sin cambio).

**Seguridad común a ambos caminos**:

- **ClamAV autoalojado**: contenedor de infraestructura nuevo, de la misma categoría que MongoDB y
  RabbitMQ.
- **Apache Tika** (librería, no infraestructura): verifica el tipo real contra el contenido. Reemplaza
  cualquier comprobación separada de "firma de los primeros bytes" que la v1 o la v2 hubieran propuesto.
- **SHA-256** de cada archivo: integridad, auditoría y trazabilidad, y caché de veredictos de escaneo
  por huella para no reescanear un archivo ya conocido.
- **Multitenancy obligatoria**: todo adjunto y todo registro de subida lleva su propio `tenantId`, que se
  valida contra el `tenantId` de la notificación. Motivo explícito: el antecedente de fuga entre tenants
  en HU2-072, donde un filtro de tenant correcto no evitó emitir un `REMOVE` con los datos completos de
  otro tenant.

**Listas y límites**:

- **Lista blanca de tipos** (reemplaza la de la v1): PDF (`application/pdf`), PNG (`image/png`), JPG/JPEG
  (`image/jpeg`), DOCX, XLSX, CSV (`text/csv`) y TXT (`text/plain`). **Cambio respecto del plan
  anterior**: salen GIF (`image/gif`), WEBP (`image/webp`) y PPTX. PPTX no figuraba en la constante de
  `AttachmentPolicy` de la v1, pero cabía en la redacción "documentos de ofimática" del spec; en la v3
  queda excluido de forma explícita.
- **Lista negra de extensiones** (nueva; capa adicional sobre el tipo, no lo reemplaza): `.exe` `.msi`
  `.bat` `.cmd` `.com` `.scr` `.pif` `.vbs` `.vbe` `.js` `.jse` `.wsf` `.wsh` `.ps1` `.hta` `.cpl` `.msc`
  `.reg` `.lnk` `.jar` `.dll` `.sh` `.apk` `.app` `.gadget` `.com.pif` `.msix` `.war`.
- **Límites**: 10 MB por archivo (sin cambio), **25 MB agregados por notificación** (nuevo) y 5 adjuntos
  como máximo (sin cambio).

**Capas**: `core` conserva `InvalidAttachmentException`, la validación por `contentSchema` y
`supportsAttachments()`; rediseña `Attachment` y `AttachmentPolicy`; y gana la máquina de estados de la
subida (`AttachmentUpload`), la huella SHA-256 (JDK puro), un inspector de adjuntos y cuatro puertos de
salida nuevos (almacén de objetos, antivirus, detección de tipo y caché de veredictos) más el repositorio
de subidas. `infrastructure` gana los adaptadores de MinIO, ClamAV y Tika, el controlador de subidas, el
consumidor de escaneo y dos colecciones de MongoDB, y extiende el request REST, el documento de la
notificación y el manejador de errores.

**Fuera de alcance, explícitamente**: cómo se entrega el adjunto al destinatario final por cada proveedor
(correo, SMS, push), incluido cómo obtiene cada proveedor el archivo desde MongoDB o MinIO. Eso es de
HU2-093, HU2-094 y HU2-095.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB, Spring AMQP,
`com.networknt:json-schema-validator` 1.4.0 (ya en `core`), Jackson (ya en `core`). **Nuevas**:

- **Apache Tika** (solo en `infrastructure`): `tika-core` más el detector de contenedores ZIP/OOXML,
  necesario para distinguir DOCX y XLSX de un ZIP genérico. Versión fijada en el `pom.xml`; pasa por
  el escaneo de dependencias de CI.
- **Cliente de MinIO** (solo en `infrastructure`): `MinioAsyncClient` (respuestas `CompletableFuture`,
  envueltas en `Mono.fromFuture`); la firma de direcciones prefirmadas es local, sin llamada de red.
- **ClamAV**: sin librería cliente; el adaptador habla el protocolo `INSTREAM` de `clamd` por TCP con
  Reactor Netty (`TcpClient`), sin bloquear.
- **Pruebas**: módulo MinIO de Testcontainers (o `GenericContainer` si la versión 1.19.8 no lo trae) y un
  `GenericContainer` de ClamAV.

**Storage**:

- **MongoDB, colección `notifications`**: subdocumentos `attachments` embebidos, ahora con `tenantId`,
  `sha256`, `storage` (`EMBEDDED` | `OBJECT`) y, según la forma, `content` como `BinData` (camino chico)
  o `uploadId` + `objectKey` (camino grande). Tope embebido por notificación: 5 × 1 MB = 5 MB de
  `BinData`, lejos del límite de 16 MB por documento. La dirección prefirmada **no** se guarda.
- **MongoDB, colección nueva `attachment_uploads`**: un documento por subida emitida, con `tenantId`
  propio, estado, huella y claves de objeto. Índice único en `uploadId`; índice `(tenantId, uploadId)`.
- **MongoDB, colección nueva `attachment_scan_verdicts`**: caché de veredictos por huella. Índice único
  `(tenantId, sha256)`; índice TTL para los veredictos `CLEAN`.
- **MinIO**: un bucket (`notification-attachments`, configurable) con prefijo por tenant:
  `tenants/{tenantId}/uploads/{uploadId}` (destino de la subida prefirmada) y
  `tenants/{tenantId}/clean/{uploadId}` (copia inmutable tras el escaneo, nunca prefirmada para escritura).
- Documentos de notificación anteriores sin `attachments` se leen con lista vacía. No hay migración de
  adjuntos de la v1: la v1 nunca llegó a `develop`, así que no hay datos de adjuntos que conservar.

**Testing**: JUnit 5, Mockito, `StepVerifier`, `@WebFluxTest`, `@SpringBootTest(RANDOM_PORT)` +
Testcontainers (MongoDB, RabbitMQ, **MinIO**, **ClamAV**) + `WebTestClient`, Logback `ListAppender`,
ArchUnit, Spring Modulith. Archivo de prueba antivirus: la cadena estándar EICAR.

**Target Platform**: contenedor en Kubernetes (varias réplicas). MinIO y ClamAV son servicios aparte, igual
que MongoDB y RabbitMQ; en ambientes distintos a desarrollo, gestionados fuera del servicio.

**Project Type**: microservicio hexagonal — `core` + `infrastructure`.

**Performance Goals**: SC-005 (≤ 5 s con refresco de 1 s), SC-011 (`PENDING_SCAN` → `CLEAN` en ≤ 30 s
para 10 MB con ClamAV listo) y SC-012 (aceptación de 5 × 1 MB embebidos en ≤ 5 s con ClamAV listo). La v3
agrega trabajo síncrono a la aceptación con adjuntos chicos (decodificar, SHA-256, Tika y ClamAV sobre ≤ 5
MB), acotado por un tiempo máximo configurable del escaneo (`notification.attachments.scan.timeout`, 10 s
por defecto); al vencer, la aceptación falla con `503`, nunca acepta sin escanear. Las solicitudes sin
adjuntos no cambian.

**Límite del cuerpo HTTP**: `spring.codec.max-in-memory-size` sube de 256 KB (por defecto) a **8 MB**:
5 adjuntos × 1 MB × 4/3 de Base64 ≈ 6,7 MB, más el texto y el margen. Un cuerpo mayor responde `413`. El
límite es global del servidor, así que cada solicitud concurrente puede retener hasta 8 MB (Riesgos).

**Constraints**: `core` sin Spring ni librerías de terceros para Tika, MinIO o ClamAV (todas detrás de
puertos); SHA-256 con `java.security.MessageDigest` (JDK) en `core`; ningún `.block()` nuevo; Tika se
ejecuta en `Schedulers.boundedElastic()`. Datos sensibles que nunca aparecen en registros, errores,
eventos, mensajes de despacho, mensajes de escaneo ni respuestas de consulta: el `content` (bytes o
Base64) y la dirección prefirmada. La dirección prefirmada solo sale del servicio en la respuesta de su
emisión. El servicio nunca descarga una dirección que le envíe el cliente: lee únicamente su propio bucket
con el SDK, por clave (sin riesgo de SSRF).

**Scale/Scope**: ≤ 5 adjuntos y ≤ 25 MB agregados por notificación; ≤ 5 MB de `BinData` por documento de
notificación; ≤ 10 MB por objeto en MinIO.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Re-evaluado para la versión 3, aceptada el 2026-09-29, y de nuevo después de alinear spec, research,
data-model, quickstart, contrato y `tasks.md` con ella. Resultado: **PASS para pasar a implementación**,
sin ningún FAIL de diseño. Lo que sigue condicionado se verifica en la implementación (IV, reactivo sin
bloqueo), y queda un pendiente con dueño y fecha que no bloquea la implementación en desarrollo: la
decisión de arquitectura sobre MinIO y ClamAV fuera de desarrollo.

- **I. Arquitectura hexagonal (NON-NEGOTIABLE)** — PASS por diseño. MinIO, ClamAV y Tika entran solo en
  `infrastructure`, cada uno detrás de un puerto de `core` (`AttachmentStoragePort`,
  `MalwareScannerPort`, `ContentTypeDetectorPort`, `ScanVerdictCachePort`) o de un repositorio
  (`AttachmentUploadRepository`). La máquina de estados, el orden de verificación, las listas, los
  límites y la huella SHA-256 (JDK puro) viven en `core`. Tika no se admite en `core` aunque no sea
  Spring: `HexagonalArchitectureTest` solo prohíbe Spring, pero el principio prohíbe cualquier framework.
  `HexagonalArchitectureTest` y `ModularityTests` deben seguir en verde con los paquetes de adaptador
  nuevos.
- **II. Contract-first** — PASS condicionado. Hay **operaciones nuevas**: `POST /attachment-uploads` (CRUD
  natural: crea la subida), `POST /attachment-uploads/{uploadId}:complete` (acción de negocio, patrón
  `recurso:accion`) y `GET /attachment-uploads/{uploadId}`. Además `Attachment` pasa a `content` XOR `url`
  (`oneOf`) y `POST /notifications` gana `409`, `413` y `503`. Todo se define en `api-notificaciones.yaml`
  como **primera tarea**, antes de cualquier controlador. `contracts/api-notificaciones-cambios.md` ya
  está reescrito para la v3, partiendo del contrato que dejó la v1.
- **III. Cero comentarios explicativos** — PASS (a verificar en implementación). Los porqués (umbral,
  copia inmutable condicionada por ETag, caché por tenant, alcance de la lista negra) viven en este plan y
  en research.md.
- **IV. Calidad verificada, no declarada** — PASS condicionado (a verificar en implementación). La
  estrategia de pruebas está definida (ver [Pruebas](#pruebas)): dos E2E explícitas con MongoDB, RabbitMQ,
  MinIO y ClamAV reales (más una tercera con ClamAV inalcanzable para SC-014), pruebas de integración de cada adaptador nuevo contra su servicio real y
  aislamiento entre dos tenants con control positivo. El spec actualizado tiene FR-016 a FR-026 y SC-008 a
  SC-014 medibles, y cada SC tiene su prueba automatizada asignada en `tasks.md` y en `quickstart.md`
  (SC-011 y SC-012 con `Duration`). Costo nuevo: el contenedor de ClamAV tarda en arrancar y usa más de 1
  GB de memoria (Riesgos). Cobertura ≥ 80 % líneas / ≥ 70 % ramas con `./mvnw -B -ntp verify` completo.
- **V. Trazabilidad en git** — PASS. Misma rama; commits de una línea en español sin tildes; artefactos de
  spec-kit en la misma rama; los cambios ajenos (`.claude/`, `docs/`, `Dockerfile`) no entran en ningún
  commit de la historia.
- **VI. Desarrollo asistido por IA, gobernado por spec-kit** — PASS. El usuario aceptó el plan v3 el
  2026-09-29 editando `## Estado del plan`. Con esa aprobación se actualizaron el spec (Q1 reabierta con
  la respuesta híbrida) y los demás artefactos, y `tasks.md` se regeneró para la v3 (ver [Impacto en el
  spec](#impacto-en-el-spec-y-demás-artefactos)). El código de la v1 y el trabajo sin commitear no se han
  tocado todavía: su rediseño está en `tasks.md`.
- **VII. Sin atajos** — PASS con pendientes nuevos, cada uno con dueño y fecha (ver [Pendientes
  declarados](#pendientes-declarados-principio-vii)). Desaparecen dos pendientes de la v1: "tamaño y tipo
  declarados" (ahora se verifican) y "alojamiento a cargo del cliente" (ahora aloja el servicio). Aparecen
  otros: retención y borrado de archivos, subidas abandonadas y escaneos agotados en la DLQ, y la
  decisión de arquitectura sobre MinIO y ClamAV. `supportsAttachments()` sigue siendo abstracto.
- **VIII. Confiabilidad y durabilidad** — PASS condicionado.
  - **Camino chico**: el contenido se guarda con la notificación en la misma escritura antes de
    responder `202`; reintentos y reencolado lo leen del mismo documento. Sin cambio de garantía respecto
    de la v1.
  - **Camino grande**: la notificación solo se acepta si su objeto está `CLEAN` y copiado a una clave
    inmutable; la notificación guarda esa clave en la misma escritura. `PENDING_SCAN` nunca afecta a una
    notificación aceptada: una notificación con una subida pendiente se rechaza (`409`), no queda a medias.
    La solicitud de escaneo es un mensaje durable en RabbitMQ con ack manual y DLQ; su resultado se
    persiste con una actualización condicionada atómica antes del ack.
  - **Condición**: la durabilidad del archivo pasa a depender de MinIO. Ninguna política de retención
    puede borrar un objeto referenciado por una notificación que no esté en estado terminal. Hoy no hay
    borrado, así que la condición se cumple, pero queda escrita para cuando se defina la retención.
- **IX. Observabilidad y trazabilidad** — PASS con pendiente declarado. Aceptación, rechazo, emisión de
  subida, `:complete` y veredicto de escaneo quedan registrados con `tenantId`, identificadores
  (`externalId`, `notificationId`, `uploadId`), `sha256` y metadatos (nombre, tipo, tamaño); nunca el
  contenido ni la dirección prefirmada. La huella no es un dato sensible: identifica un archivo sin
  revelarlo. Los errores de infraestructura (ClamAV o MinIO caídos o lentos → `503`) se distinguen de los
  de negocio (adjunto inválido o infectado → `400`; escaneo pendiente → `409`). El formato sigue siendo
  `clave=valor`, no JSON (brecha RNF-10 preexistente).
- **Restricciones técnicas**:
  - **Reactivo sin bloqueo** — PASS condicionado: `MinioAsyncClient`, `clamd` por Reactor Netty y Tika en
    `boundedElastic`; se verifica en revisión que ninguna llamada bloqueante quede en un hilo de evento.
  - **Secretos** — PASS: credenciales de MinIO por variables de entorno, nunca versionadas; la dirección
    prefirmada se trata como credencial temporal.
  - **Multi-tenant** — PASS por diseño, con prueba obligatoria de dos tenants (ver
    [Aislamiento por tenant](#aislamiento-por-tenant)). El tenant sale del token JWT interino (HU2-096), no de
    `X-Tenant-Id`, mientras DEP-01 esté bloqueado.
  - **Bloqueo optimista y actualizaciones atómicas** — PASS: la notificación sigue en una sola escritura
    del agregado; las transiciones de `AttachmentUpload` son `findAndModify` condicionados al estado
    anterior, nunca leer-luego-escribir.
  - **Arranque ≤ 30 s (RNF-12)** — PASS: el servicio no espera a ClamAV ni a MinIO para arrancar.
  - **Sondas de salud (RNF-11)** — PASS. Decisión aceptada con el plan: indicadores de salud de MinIO y
    ClamAV visibles en `/actuator/health`, pero **fuera** del grupo de *readiness*, porque su caída solo
    afecta a notificaciones con adjuntos (que fallan con `503`) y no debe sacar de servicio las que no los
    llevan.
  - **Servicios gestionados fuera de local (análogo a ADR-0006 y ADR-0007)** — PENDIENTE: MinIO y ClamAV
    son dependencias de infraestructura nuevas sin una decisión de arquitectura registrada sobre dónde y
    cómo se operan fuera de desarrollo. El usuario decide dónde registrarla.
  - **Extensibilidad por catálogo (ADR-0009)** — PASS: habilitar adjuntos en un canal sigue siendo editar
    su `contentSchema`; un proveedor nuevo declara `supportsAttachments()` sin tocar el núcleo.
- **Ack manual y DLQ de RabbitMQ** — **sin excepción que justificar**. El consumidor de despacho no cambia
  (sigue en modo `AUTO`, brecha preexistente ajena a esta historia). El consumidor nuevo de escaneo
  (`AttachmentScanListener`) usa `AcknowledgeMode.MANUAL` explícito (`Channel`/`deliveryTag`,
  `channel.basicAck` en el propio `onMessage`) con su propio conteo de intentos por encabezado
  (`x-scan-attempt`, ya que el interceptor de reintento con estado no reinvoca el método cuando se agotan
  los intentos) y su propia DLQ vía `RepublishMessageRecoverer`; confirma solo después de persistir el
  veredicto o de reencolar/enviar a la DLQ; un reenvío de un mensaje ya resuelto se confirma sin efecto (la
  transición exige `PENDING_SCAN`).

No violations requiring justification — Complexity Tracking section left empty. El crecimiento del alcance
(de M a XL) no es una violación de la constitución, pero se registra como riesgo.

## Project Structure

### Documentation (this feature)

```text
specs/012-adjuntar-archivo-notificacion/
├── plan.md              # This file — versión 3, Aceptado
├── spec.md              # Q1 reabierta (híbrido); FR-001–FR-026; SC-001–SC-014; US1–US7
├── research.md          # Decisiones 1, 3, 5, 9 y 11 reescritas; 12–15 nuevas
├── data-model.md        # modelo v3
├── quickstart.md        # validación v3 (dos E2E)
├── contracts/
│   └── api-notificaciones-cambios.md   # content XOR url, subidas, 409/413/503
├── checklists/
│   └── requirements.md  # checklist de calidad del spec inicial (2026-09-28)
└── tasks.md             # regenerado para la v3; conserva lo hecho de la v1 y reabre lo que cambia
```

### Source Code (repository root)

Nivel de detalle: los nombres son los propuestos; los que dependan de decisiones del spec todavía abiertas
pueden cambiar al generar `tasks.md`, y ese cambio se anota como desviación.

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/
│   └── AttachmentUpload.java                  # NUEVO: agregado de la subida; PENDING_SCAN → CLEAN | INFECTED
├── domain/valueobject/
│   ├── AttachmentSubmission.java              # NUEVO: adjunto tal como llega (content Base64 XOR url); toString sin ambos
│   ├── Attachment.java                        # REDISEÑADO: adjunto verificado del agregado (tenantId, sha256, source)
│   ├── AttachmentSource.java                  # NUEVO: sellado — EmbeddedContent(bytes) | StoredObject(uploadId, objectKey)
│   ├── Sha256Digest.java                      # NUEVO: huella hexadecimal; cálculo con MessageDigest
│   ├── UploadId.java                          # NUEVO
│   ├── ScanState.java                         # NUEVO: PENDING_SCAN, CLEAN, INFECTED
│   ├── ScanVerdict.java                       # NUEVO: CLEAN | INFECTED + firma detectada + versión de firmas
│   ├── AttachmentRejectionReason.java         # NUEVO: MALWARE, CONTENT_TYPE_MISMATCH
│   └── NotificationContent.java               # CONSERVADO en forma: List<Attachment>, hasAttachments()
├── domain/policy/
│   ├── AttachmentPolicy.java                  # REESCRITO en parte: XOR, umbral, listas, límite agregado
│   └── ContentSchemaValidator.java            # CONSERVADO: nodo con fileName, contentType, sizeBytes
├── exception/
│   ├── InvalidAttachmentException.java        # CONSERVADO: posición + regla → 400
│   ├── AttachmentNotReadyException.java       # NUEVO: subida en PENDING_SCAN → 409
│   ├── AttachmentInspectionUnavailableException.java # NUEVO: ClamAV o MinIO no disponibles → 503
│   └── AttachmentUploadNotFoundException.java # NUEVO: subida inexistente o de otro tenant → 404
├── port/in/
│   ├── IssueAttachmentUploadUseCase.java      # NUEVO
│   ├── CompleteAttachmentUploadUseCase.java   # NUEVO
│   ├── GetAttachmentUploadUseCase.java        # NUEVO
│   ├── ScanAttachmentUploadUseCase.java       # NUEVO: lo invoca el consumidor de escaneo
│   ├── SendNotificationCommand.java           # MODIFICADO: + List<AttachmentSubmission> attachments
│   └── BatchNotificationItem.java             # MODIFICADO: ídem
├── port/out/
│   ├── AttachmentStoragePort.java             # NUEVO: presignUpload, stat, read, copyIfMatch, delete
│   ├── MalwareScannerPort.java                # NUEVO: scan(bytes) → ScanVerdict; signatureVersion()
│   ├── ContentTypeDetectorPort.java           # NUEVO: detect(bytes, fileName) → tipo real
│   ├── ScanVerdictCachePort.java              # NUEVO: find/save por (tenantId, sha256)
│   ├── AttachmentScanRequestPort.java         # NUEVO: requestScan(tenantId, uploadId) → RabbitMQ
│   └── NotificationSenderPort.java            # CONSERVADO: boolean supportsAttachments()
├── repository/
│   └── AttachmentUploadRepository.java        # NUEVO: save, findByTenantAndId, transición condicionada
└── usecase/
    ├── AttachmentInspector.java               # NUEVO: huella → caché → Tika → ClamAV (común a ambos caminos)
    ├── AttachmentResolver.java                # NUEVO: submission → Attachment verificado (chico o grande)
    ├── IssueAttachmentUploadService.java      # NUEVO
    ├── CompleteAttachmentUploadService.java   # NUEVO
    ├── GetAttachmentUploadService.java        # NUEVO
    ├── ScanAttachmentUploadService.java       # NUEVO
    ├── SendNotificationService.java           # MODIFICADO: política → esquema → resolución → duplicada
    ├── SendNotificationBatchService.java      # MODIFICADO: InvalidAttachment y NotReady → rejected
    └── DispatchNotificationService.java       # MODIFICADO: falla cerrada (Q4, sin cambio respecto de la v1)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/
│   ├── AttachmentRequest.java                 # MODIFICADO: + content; toString sin content ni url
│   ├── AttachmentLogFormatter.java            # NUEVO: [fileName|contentType|sizeBytes|sha256], control chars fuera
│   ├── AttachmentUploadController.java        # NUEVO: POST /attachment-uploads, :complete, GET
│   ├── IssueAttachmentUploadRequest.java      # NUEVO
│   ├── AttachmentUploadResponse.java          # NUEVO: uploadId, uploadUrl (solo al emitir), expiresAt, state
│   ├── SendNotificationRequest.java           # CONSERVADO (v1)
│   ├── NotificationController.java           # MODIFICADO: mapeo a AttachmentSubmission + registros
│   └── NotificationExceptionHandler.java      # MODIFICADO: 400, 404, 409, 503
├── adapter/in/rabbit/
│   └── AttachmentScanListener.java            # NUEVO: ack manual + DLQ
├── adapter/out/rabbit/
│   └── AttachmentScanRequestRabbitPublisher.java # NUEVO: publica {tenantId, uploadId}
├── adapter/out/storage/
│   └── MinioAttachmentStorageAdapter.java     # NUEVO: MinioAsyncClient; prefijo por tenant; copia if-match
├── adapter/out/antivirus/
│   └── ClamAvMalwareScannerAdapter.java       # NUEVO: INSTREAM y VERSION por Reactor Netty; concurrencia acotada
├── adapter/out/detection/
│   └── TikaContentTypeDetectorAdapter.java    # NUEVO: detección por contenido en boundedElastic
├── adapter/out/mongo/
│   ├── AttachmentDocument.java                # REDISEÑADO: tenantId, sha256, storage, content (BinData) | uploadId + objectKey; sin url
│   ├── NotificationDocument.java              # CONSERVADO (v1)
│   ├── NotificationDocumentMapper.java        # MODIFICADO
│   ├── NotificationMongoAdapter.java          # MODIFICADO: proyección sin attachments.content en búsqueda y reencolado
│   ├── AttachmentUploadDocument.java          # NUEVO
│   ├── AttachmentUploadMongoAdapter.java      # NUEVO: findAndModify condicionado al estado
│   ├── ScanVerdictDocument.java               # NUEVO
│   └── ScanVerdictMongoAdapter.java           # NUEVO: TTL para CLEAN
├── adapter/out/provider/                      # CONSERVADO (v1): supportsAttachments() en los cuatro
└── config/
    ├── AttachmentProperties.java              # NUEVO: umbral, timeout, expiración de subida, bucket, endpoints
    ├── MinioConfig.java                       # NUEVO
    ├── ClamAvConfig.java                      # NUEVO
    ├── RabbitConfig.java                      # MODIFICADO: cola de escaneo + DLQ
    ├── RabbitTopologyProperties.java          # MODIFICADO
    └── UseCaseConfig.java                     # MODIFICADO: cableado de los servicios nuevos

infrastructure/src/main/resources/
├── static/openapi/api-notificaciones.yaml     # MODIFICADO: primera tarea (Principio II)
└── application.yml                            # MODIFICADO: codec 8 MB, bloque notification.attachments; sin secretos

docker-compose.yml                             # MODIFICADO: servicios minio y clamav
pom.xml / infrastructure/pom.xml               # MODIFICADO: Tika, MinIO, Testcontainers MinIO

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/
├── adapter/in/rest/
│   ├── NotificationControllerTest.java        # MODIFICADO
│   ├── AttachmentUploadControllerTest.java    # NUEVO
│   ├── NotificationAttachmentE2ETest.java     # NUEVO: E2E camino chico
│   ├── NotificationAttachmentScannerDownE2ETest.java # NUEVO: E2E con ClamAV inalcanzable (SC-014)
│   └── AttachmentUploadE2ETest.java           # NUEVO: E2E camino grande + aislamiento por tenant
├── support/
│   └── AttachmentTestContainers.java          # NUEVO: contenedores compartidos de ClamAV y MinIO
├── config/
│   └── AttachmentHealthIndicatorsTest.java    # NUEVO: MinIO y ClamAV fuera de readiness
├── adapter/in/rabbit/
│   └── AttachmentScanListenerTest.java        # NUEVO: RabbitMQ real; mensaje producido con el publicador real
├── adapter/out/storage/
│   └── MinioAttachmentStorageAdapterTest.java # NUEVO: Testcontainers MinIO
├── adapter/out/antivirus/
│   └── ClamAvMalwareScannerAdapterTest.java   # NUEVO: Testcontainers ClamAV; EICAR y archivo limpio
├── adapter/out/detection/
│   └── TikaContentTypeDetectorAdapterTest.java # NUEVO: archivos reales de cada tipo de la lista blanca
└── adapter/out/mongo/
    ├── NotificationMongoAdapterTest.java      # MODIFICADO: BinData ida y vuelta; proyección sin content
    ├── AttachmentUploadMongoAdapterTest.java  # NUEVO: transición condicionada; dos tenants
    └── ScanVerdictMongoAdapterTest.java       # NUEVO
```

Pruebas de `core` nuevas o modificadas: `AttachmentSubmissionTest`, `AttachmentTest`,
`AttachmentPolicyTest`, `AttachmentUploadTest`, `Sha256DigestTest`, `AttachmentInspectorTest`,
`AttachmentResolverTest`, las cuatro de los servicios de subida, y las ya existentes de
`SendNotificationService`, `SendNotificationBatchService`, `DispatchNotificationService`,
`ContentSchemaValidator` y `NotificationContent`.

**Structure Decision**: sin módulos nuevos. Tres paquetes de adaptador nuevos bajo `adapter/out`
(`storage`, `antivirus`, `detection`), uno por dependencia externa, como ya ocurre con `mongo`, `rabbit` y
`provider`. El catálogo por defecto sigue sin declarar adjuntos en ningún canal (Q3); `application.yml`
sí cambia, por el límite del cuerpo y la configuración no sensible de adjuntos.

## Diseño

### Forma del adjunto en la solicitud

```text
AttachmentRequest = {
  fileName:    string,             requerido
  contentType: string,             requerido
  sizeBytes:   integer,            requerido
  content:     string (Base64),    exactamente uno de content | url
  url:         string (https)
}
```

- **Exactamente uno** de `content` o `url`. Ambos o ninguno → `400`.
- `content`: Base64 estándar (RFC 4648, con relleno), sin prefijo `data:` ni saltos de línea; mal
  formado → `400`. El tamaño decodificado debe ser ≤ 1 048 576 bytes **y** igual a `sizeBytes`.
- `url`: solo para `sizeBytes` > 1 048 576. Debe ser una dirección emitida por este servicio para el
  mismo tenant (ver [Camino grande](#camino-grande-url-a-minio-propio)). Se ignora la parte de consulta
  (la firma): el servicio extrae el `uploadId` de la ruta y no guarda la dirección.
- El umbral separa los caminos en ambos sentidos: un archivo ≤ 1 MB no se acepta por `url` y uno > 1 MB
  no se acepta por `content`.

### Reglas globales (`AttachmentPolicy`, en `core`, síncronas y sin E/S)

Se aplican en este orden y se reporta la primera regla incumplida del primer adjunto inválido, con el
mismo formato de la v1: `attachments[i]: <regla> (<fileName>)`, sin contenido ni dirección.

1. Cantidad ≤ 5 (conservado).
2. `fileName`: presente, 1–255 caracteres, sin `/`, `\`, caracteres de control, distinto de `.` y `..`
   (conservado).
3. **Lista negra de extensiones** (nuevo): `fileName` en minúsculas, sin puntos ni espacios finales, no
   puede terminar en ninguna extensión de la lista negra. Se compara por sufijo, por eso `.com.pif` figura
   aparte y `factura.pdf.exe` se rechaza. `factura.exe.pdf` pasa esta capa y la detiene, si su contenido
   no es PDF, la verificación del tipo real.
4. `contentType` normalizado (conservado) y dentro de la **lista blanca v3**.
5. `sizeBytes` presente, entre 1 y 10 485 760, inclusivo (conservado).
6. `content` XOR `url`, Base64 válido y umbral de 1 MB en ambos sentidos (nuevo).
7. **Suma de `sizeBytes` ≤ 26 214 400** (25 MB, nuevo); si se incumple, se informa sin posición, igual
   que hoy la cantidad.

### Verificación de contenido (`AttachmentInspector`, en `core`, detrás de puertos)

Común a ambos caminos; en el chico se ejecuta durante la aceptación, en el grande dentro del consumidor de
escaneo:

```text
sha256  = Sha256Digest.of(bytes)
tipo    = ContentTypeDetectorPort.detect(bytes, fileName)   -- Tika
          tipo ∉ lista blanca o incompatible con contentType declarado → rechazo (CONTENT_TYPE_MISMATCH)
cache   = ScanVerdictCachePort.find(tenantId, sha256)
          INFECTED en caché → rechazo sin reescanear
          CLEAN en caché con la misma versión de firmas y ≤ 24 h → se omite ClamAV
          si no → MalwareScannerPort.scan(bytes) y se guarda el veredicto
```

- Compatibilidad de tipo: igualdad exacta tras normalizar, con una excepción conocida de Tika: un CSV
  declarado como `text/csv` se detecta por contenido como `text/plain`, y se acepta. Un `text/plain` que
  Tika detecta como `text/html` o como un guion se rechaza.
- La caché es **por tenant**, `(tenantId, sha256)`: evita que un tenant averigüe, por la latencia o por
  la respuesta, si otro tenant subió un archivo concreto (antecedente de HU2-072).
- Los veredictos `INFECTED` no vencen; los `CLEAN` vencen por TTL y se invalidan cuando cambia la versión
  de firmas de ClamAV (`VERSION` de `clamd`, cacheada unos minutos en el adaptador).
- ClamAV no disponible o fuera de tiempo → `AttachmentInspectionUnavailableException` (`503` en la
  aceptación; reintento y DLQ en el consumidor). Nunca se acepta sin escanear.

### Camino chico (`content` embebido)

`SendNotificationService`:

```text
findActiveRoute
→ AttachmentPolicy.validate(submissions)                  -- barato, sin E/S
→ ContentSchemaValidator.validate                         -- reglas del canal (Q3, sin cambio)
→ AttachmentResolver.resolve(tenantId, submissions)       -- decodifica + inspecciona (chico) o resuelve (grande)
→ findByTenantAndExternalId (duplicada)
→ Notification.accept → save → publish → enqueueForDispatch
```

- Todo error ocurre antes de `save`: nada se guarda ni se encola (FR-006 y SC-002 siguen valiendo).
- El orden respecto de la idempotencia se conserva: primero se valida y luego se busca la duplicada. Un
  reintento de una duplicada vuelve a inspeccionar, pero la caché por huella evita volver a llamar a
  ClamAV.
- Los adjuntos chicos de una notificación se inspeccionan con `concatMap` (orden conservado y a lo sumo
  un escaneo por solicitud a la vez); la concurrencia total hacia `clamd` la acota el adaptador.
- `Attachment` resultante: `tenantId` de la notificación, `sha256`, `source = EmbeddedContent(bytes)`.
  Se guarda como `BinData` en el subdocumento, en la misma escritura de la notificación.

### Camino grande (`url` a MinIO propio)

**Emisión** — `POST /attachment-uploads` (tenant tomado del token) con `{ fileName, contentType, sizeBytes }`:

- Mismas reglas 2–5 de `AttachmentPolicy`, más `sizeBytes` > 1 048 576.
- Crea `AttachmentUpload { uploadId, tenantId, fileName, contentType, sizeBytes, uploadKey =
  tenants/{tenantId}/uploads/{uploadId}, state = PENDING_SCAN, issuedAt, expiresAt }`.
- Devuelve `201` con `{ uploadId, uploadUrl (PUT prefirmado), expiresAt }`. Vigencia configurable, 15
  minutos por defecto. La dirección se firma contra el endpoint **público** de MinIO configurado, que puede
  ser distinto del interno.
- Es la única respuesta que contiene la dirección prefirmada; no se registra ni se guarda.

**Cierre** — `POST /attachment-uploads/{uploadId}:complete`:

- Busca por `(tenantId, uploadId)`; inexistente o de otro tenant → `404` idéntico en ambos casos.
- `stat` del objeto: ausente → `409`; tamaño real distinto del declarado → `400`, se borra el objeto y la
  subida sigue en `PENDING_SCAN` para reintentar con la misma dirección mientras esté vigente.
- Publica `{ tenantId, uploadId }` en la cola de escaneo y responde `202`. Un segundo `:complete` sobre una
  subida ya resuelta devuelve su estado sin volver a encolar.

**Escaneo** — `AttachmentScanListener` → `ScanAttachmentUploadService`:

```text
upload = findByTenantAndId; si state ≠ PENDING_SCAN → ack sin efecto
etag   = stat(uploadKey)
bytes  = read(uploadKey, ifMatch = etag)                  -- ≤ 10 MB; concurrencia del consumidor acotada
veredicto = AttachmentInspector.inspect(...)
CLEAN    → copyIfMatch(uploadKey → tenants/{tenantId}/clean/{uploadId}, etag)
           → transición condicionada PENDING_SCAN → CLEAN (sha256, cleanKey) → delete(uploadKey)
INFECTED → delete(uploadKey) → transición condicionada PENDING_SCAN → INFECTED (sha256, motivo)
error de infraestructura → nack → reintentos → DLQ con causa
ack solo después de persistir la transición
```

- La copia condicionada por ETag a una clave nunca prefirmada cierra la ventana en la que alguien con la
  dirección de subida aún vigente sobrescribe un objeto ya escaneado. Si el objeto cambió entre la lectura
  y la copia, la precondición falla y el mensaje se reintenta sobre el contenido nuevo.
- `INFECTED` es el único estado terminal no apto que la v3 define. Lleva un motivo (`MALWARE`, con el
  nombre de la firma, o `CONTENT_TYPE_MISMATCH`) para que un tipo incorrecto no se lea como virus en la
  auditoría.

**Consulta** — `GET /attachment-uploads/{uploadId}` → `{ uploadId, state, fileName, contentType,
sizeBytes, sha256 (si ya existe) }`; nunca la dirección prefirmada. De otro tenant → `404`.

**Uso en la notificación** — `AttachmentResolver`, para cada adjunto con `url`:

1. La dirección debe tener el esquema, el servidor y el puerto del endpoint público configurado y la ruta
   `/{bucket}/tenants/{tenantId}/uploads/{uploadId}`; si no → `400` ("url is not an upload issued by this
   service").
2. El `tenantId` de la ruta debe ser el de la solicitud, y la subida se busca por `(tenantId, uploadId)`;
   inexistente o de otro tenant → el **mismo** `400` del punto 1, sin revelar si existe.
3. `fileName`, `contentType` y `sizeBytes` deben coincidir con los de la subida → si no, `400`.
4. Estado: `PENDING_SCAN` → `409` (`AttachmentNotReadyException`, reintentable); `INFECTED` → `400`;
   `CLEAN` → `Attachment { tenantId, sha256, source = StoredObject(uploadId, cleanKey) }`.
5. Una misma subida `CLEAN` puede referenciarse desde varias notificaciones **del mismo tenant** (por
   ejemplo, el mismo PDF en un lote).

### Aislamiento por tenant

Por el antecedente de HU2-072, el diseño no se conforma con un predicado correcto; cada punto dice qué se
hace con el resultado:

| Punto | Regla | Qué se hace con un resultado de otro tenant |
|---|---|---|
| `attachment_uploads` | `tenantId` propio; toda búsqueda es por `(tenantId, uploadId)`, nunca por `uploadId` solo. | No se carga: `404` en la API de subidas, `400` genérico en la notificación. Ningún campo de la subida ajena llega a la respuesta. |
| Claves de MinIO | Prefijo `tenants/{tenantId}/`; la clave se arma desde el registro del tenant, nunca desde la dirección del cliente. | No se lee, no se copia, no se borra. |
| Subdocumento del adjunto | `tenantId` propio, igual al de la notificación al construir el agregado. | Si al reconstituir desde MongoDB no coinciden, la notificación no se despacha con ese adjunto: error de integridad trazable. |
| Caché de veredictos | Clave `(tenantId, sha256)`. | Un veredicto de otro tenant no existe para el que consulta. |
| Registros y errores | Solo el tenant de la solicitud y sus propios identificadores. | Ningún mensaje de error cita un `uploadId`, clave o huella de otro tenant. |

### Validación por canal (`ContentSchemaValidator`)

Sin cambio respecto de la v1: si hay adjuntos y el esquema está vacío o no tiene `properties.attachments`
en la raíz → rechazo "channel does not accept attachments"; si no, el nodo validado incluye
`attachments[i].{fileName, contentType, sizeBytes}`. Ni `content` ni `url` entran en el nodo.

### Despacho (`DispatchNotificationService.sendThrough`)

Sin cambio respecto de la v1 (Q4):

```text
outcome = notification.content().hasAttachments() && !sender.supportsAttachments()
    ? Mono.just(PERMANENT_FAILURE)
    : sender.send(notification)
```

El proveedor simulado recibe el `Attachment` con su `source`; cómo obtiene un proveedor real los bytes
(desde `BinData` o desde la clave limpia de MinIO) es de HU2-093/094/095.

### Lote (Q5)

`SendNotificationBatchService` convierte en `rejected` con su motivo: `ChannelNotAvailableException`,
`InvalidContentException`, `InvalidAttachmentException` (ya en curso en el WIP) y
`AttachmentNotReadyException` (nuevo). `AttachmentInspectionUnavailableException` es de infraestructura:
no se disfraza de rechazo de negocio y se propaga como hoy cualquier otro error de infraestructura del
lote.

### REST y registros

- `toCommand` mapea `attachments` (nulo → vacío) a `AttachmentSubmission`; el Base64 se decodifica en
  `core` (`AttachmentPolicy` y `AttachmentResolver`), para que el lote aplique las mismas reglas con las
  mismas posiciones.
- `NotificationExceptionHandler`: `InvalidAttachmentException` → `400`, `AttachmentNotReadyException` →
  `409`, `AttachmentUploadNotFoundException` → `404`, `AttachmentInspectionUnavailableException` → `503`;
  el cuerpo que excede el límite del codec → `413`.
- Registros `clave=valor` (conservado de la v1, ampliado): aceptación y rechazo con adjuntos, emisión,
  `:complete` y veredicto de escaneo, con `tenantId`, identificadores, `sha256` y
  `[fileName|contentType|sizeBytes]` mediante `AttachmentLogFormatter` (caracteres de control fuera). Un
  veredicto `INFECTED` registra el nombre de la firma. Nunca el contenido ni la dirección prefirmada.

### Pruebas

Por nivel, siguiendo el patrón del repositorio:

- **Unitarias en `core`** (JUnit 5 + Mockito + `StepVerifier`): cada regla de `AttachmentPolicy` con sus
  límites inclusivos (1 048 576 embebido válido y 1 048 577 inválido; 26 214 400 agregado válido y
  26 214 401 inválido; cada extensión de la lista negra, `.com.pif` y variantes con mayúsculas y punto
  final); XOR; Base64 inválido; transiciones de `AttachmentUpload` (solo desde `PENDING_SCAN`); orden y
  cortocircuitos de `AttachmentInspector` (caché `INFECTED` sin escanear, caché `CLEAN` con otra versión
  de firmas sí escanea); `AttachmentResolver` con dos tenants.
- **Integración de adaptadores** (Testcontainers contra el servicio real, sin simular el broker, la base,
  MinIO ni ClamAV): MinIO (firma de la dirección, PUT real con `WebClient`, `stat`, copia con ETag
  obsoleto que falla); ClamAV (EICAR → `INFECTED`, archivo limpio → `CLEAN`, versión de firmas); Tika
  (un archivo real por tipo de la lista blanca, un ejecutable renombrado a `.pdf`, un HTML declarado como
  `text/plain`); MongoDB (`BinData` ida y vuelta, proyección sin `content`, transición condicionada
  concurrente: solo una gana).
- **Consumidor de escaneo**: el mensaje se produce con el publicador real
  (`AttachmentScanRequestPort.requestScan`), nunca con un JSON armado a mano; se comprueba el ack tras
  persistir, el reenvío de un mensaje ya resuelto sin efecto y la llegada a la DLQ con ClamAV detenido.
- **E2E `NotificationAttachmentE2ETest`** (camino chico): `@SpringBootTest(RANDOM_PORT)` +
  `@Testcontainers` con MongoDB, RabbitMQ y ClamAV + `WebTestClient`, estructura de
  `NotificationLiveUpdatesE2ETest`. Casos: PDF limpio → `202` → `DELIVERED` con el mismo contenido y
  huella en el emisor de prueba; EICAR → `400` y nada guardado; tipo real distinto del declarado → `400`;
  extensión de la lista negra → `400`; agregado > 25 MB → `400`; `content` y `url` a la vez → `400`;
  ausencia del contenido Base64 en registros, errores, eventos y consultas, con control positivo de
  nombre, tipo, tamaño y huella en los registros. Se conservan los casos de la v1 que no dependen de la
  forma del adjunto (canal sin adjuntos, SC-005 con `Duration`, SC-006, duplicada).
- **E2E `AttachmentUploadE2ETest`** (camino grande): lo mismo más MinIO. Emitir → PUT real a la dirección
  prefirmada → `:complete` → esperar `CLEAN` con un plazo afirmado con `Duration` → notificación con esa
  `url` → `DELIVERED`; notificación con una subida en `PENDING_SCAN` → `409`; archivo EICAR → `INFECTED`
  → `400`; dirección https ajena → `400`. **Dos tenants**: B no ve la subida de A (`GET` → `404`), no
  puede completarla ni referenciarla (`400` idéntico al de una subida inexistente), y ninguna respuesta,
  registro o documento de B contiene datos de A; con control positivo: A sí puede usarla.
- **E2E `NotificationAttachmentScannerDownE2ETest`** (SC-014): ClamAV inalcanzable; adjunto embebido →
  `503` y nada guardado; sin adjuntos → `202` (control positivo).
- **Arquitectura**: `HexagonalArchitectureTest` y `ModularityTests` en verde.

## Impacto en lo ya implementado

T001–T018 (commits `8c45a4d`, `0bd17b0` y `dfb462d`) implementan la v1, y hay trabajo sin commitear que
avanza T019–T022. **Este plan no toca ese código**: la tabla dice qué se conserva y qué se rediseña, y
`tasks.md` de la v3 la aplica (los números de tarea de esta tabla son los de la v1; `tasks.md` trae la
correspondencia con los nuevos).

| Elemento v1 | Tarea v1 | Destino en la v3 |
|---|---|---|
| Contrato `Attachment` `{fileName, contentType, sizeBytes, url}` en `api-notificaciones.yaml` | T001 | **Rediseñar**: `oneOf` `content`/`url`, descripción de `url` como "emitida por este servicio", operaciones de subida, `409`/`413`/`503`, lista blanca v3. Se conservan `attachments` en `SendNotificationRequest`, la frase del lote y las descripciones de `contentSchema`. |
| `NotificationSenderPort.supportsAttachments()` abstracto, 4 adaptadores y 3 dobles | T002–T003 | **Se conserva intacto** (Q4). |
| `Attachment` (record con `url`, normalización de tipo, `toString` sin `url`) | T004–T005 | **Rediseñar**: se divide en `AttachmentSubmission` (entrada con `content` XOR `url`) y `Attachment` verificado (`tenantId`, `sha256`, `source`). Se conservan la normalización del tipo y la técnica de `toString` sin datos sensibles; `AttachmentTest` se reescribe en parte. |
| `NotificationContent` con `List<Attachment>`, `hasAttachments()` y constructor de dos argumentos | T006–T007 | **Se conserva** en forma; cambia el tipo de elemento. Las pruebas que usan `url` como dato de ejemplo se ajustan. |
| `InvalidAttachmentException` (posición + regla + nombre) | T009 | **Se conserva** tal cual. |
| `AttachmentPolicy` (5 adjuntos, 10 MB, nombre seguro, lista de tipos, `https` sin credenciales) | T008–T009 | **Reescribir en parte**: se conservan cantidad, tamaño por archivo, reglas del nombre y formato del mensaje; cambia la lista blanca (salen GIF y WEBP); la regla de `url` arbitraria se reemplaza por "emitida por el servicio" (que se comprueba en `AttachmentResolver`, no en la política); entran la lista negra, el XOR, el umbral, el Base64 y el límite agregado. |
| `ContentSchemaValidator` cerrado por defecto, nodo sin `url` | T010–T011 | **Se conserva** (Q3); solo se comprueba que el nodo tampoco lleva `content`. |
| `SendNotificationService`: política antes que esquema; rechazo sin `save` | T012–T013, T018 | **Se conserva el orden y se amplía** con `AttachmentResolver` entre el esquema y la duplicada. Las pruebas con `url` https arbitraria dejan de ser un adjunto válido y se rehacen con `content` o con una subida `CLEAN` simulada por el puerto. |
| `AttachmentDocument` con `url`; mapeo nulo → vacío | T014–T015 | **Rediseñar**: sin `url`; `tenantId`, `sha256`, `storage`, `content` (`BinData`) o `uploadId` + `objectKey`. Se conserva la lectura de documentos sin `attachments` como lista vacía. |
| `AttachmentRequest`, `SendNotificationRequest.attachments`, mapeo en `toCommand` | T016–T017 | **Se conserva** la estructura; `AttachmentRequest` gana `content` y su `toString` omite `content` y `url`; el mapeo produce `AttachmentSubmission`. |
| WIP sin commitear: `SendNotificationBatchService` + prueba (`InvalidAttachmentException` → `rejected`) | T019–T020 | **Se conserva la regla** (Q5) y se amplía con `AttachmentNotReadyException`. La prueba usa una `url` https arbitraria como adjunto válido: en la v3 ese caso es inválido y hay que rehacerlo con `content`. |
| WIP sin commitear: `NotificationExceptionHandler` (`InvalidAttachmentException` → `400`) + prueba en `NotificationControllerTest` | T021–T022 | **Se conserva** y se amplía con `404`, `409` y `503`. La prueba sigue siendo válida en esencia; su dato de ejemplo con `url` se ajusta. |
| WIP sin commitear: `Dockerfile` | — | **No pertenece a esta historia**. Queda fuera de los commits de HU2-092. |
| Planeado y no hecho: `AttachmentLogFormatter` y registros | T023–T024 | **Se conserva** el diseño; se añade `sha256` y los registros de subida y escaneo. |
| Planeado y no hecho: comprobación del catálogo por defecto | T025 | **Se conserva** (Q3). |
| Planeado y no hecho: falla cerrada en el despacho | T026 | **Se conserva** tal cual (Q4). |
| Planeado y no hecho: `NotificationAttachmentE2ETest`, regresión y `verify` | T027–T029 | **Rediseñar**: pasa a dos E2E (camino chico y camino grande) con ClamAV y MinIO. |

## Impacto en el spec y demás artefactos

**Aplicado el 2026-09-29**, tras la aceptación del plan. Se conserva como registro de qué cambió en el spec
y por qué. Secciones afectadas:

- **Clarifications Q1**: reabierta; nueva respuesta: híbrido por tamaño.
- **FR-001**: `content` XOR `url`, umbral de 1 MB.
- **FR-005**: lista blanca v3, lista negra de extensiones, límite agregado de 25 MB.
- **FR-006**: reglas nuevas (XOR, Base64, umbral, extensión, tipo real, malware, subida no emitida, de
  otro tenant o no `CLEAN`).
- **FR-009**: el dato sensible pasa a ser el contenido y la dirección prefirmada.
- **FR-016**: se invierte: el servicio sí lee y escanea el archivo, pero solo desde su propio bucket o
  desde la solicitud, nunca desde una dirección del cliente.
- **Requisitos nuevos**: FR de emisión, cierre y consulta de subidas, máquina de estados y aislamiento por
  tenant.
- **Key Entities**: subida, veredicto de escaneo y huella.
- **Edge Cases**: umbral exacto, doble extensión, CSV detectado como texto, sobrescritura tras el escaneo,
  subida vencida.
- **Out of Scope**: salen "alojar archivos" y "analizar malware"; entra de forma explícita "entrega por
  proveedor (HU2-093/094/095)", y se conservan "retención" y "descarga del adjunto por API" como fuera de
  alcance.
- **Assumptions**: lista de tipos.
- **Risks**: salen "tamaño y tipo declarados" y "alojamiento a cargo del cliente"; entran los de la
  sección siguiente.
- **Success Criteria nuevos**, cada uno con prueba automatizada: SC-008 (100 % de EICAR rechazado en
  ambos caminos), SC-009 (cero aceptaciones con una subida en `PENDING_SCAN`, `INFECTED` o ajena), SC-010
  (dos tenants, con control positivo), SC-011 (`PENDING_SCAN` → `CLEAN` en ≤ 30 s para 10 MB), SC-012
  (aceptación de 5 × 1 MB en ≤ 5 s), SC-013 (archivo fijado = archivo analizado) y SC-014 (antivirus caído
  → `503`, nunca aceptar sin escanear).
- **User Stories nuevas**: US6 (archivo grande por subida) y US7 (aislamiento entre tenants), ambas P1.

`research.md` (Decisiones 1, 3, 5, 9 y 11 reescritas; 12–15 nuevas), `data-model.md`, `quickstart.md` y
`contracts/api-notificaciones-cambios.md` se reescribieron en la misma línea; `tasks.md` se regeneró.

## Enmienda 3.1 — correcciones de la revisión (2026-10-03)

**Estado**: propuesta con las clarificaciones Q6 a Q9 resueltas (2026-10-03); pendiente de aprobación explícita. Rama `fix/HU2-092-hallazgos-revision`. No es una historia
nueva: enmienda esta carpeta, no renumera nada (FR-027 a FR-040, SC-015 a SC-022, User Story 8, tareas
T076 en adelante) y no genera un `tasks.md` nuevo; las tareas propuestas están abajo y se pasan a
`tasks.md` solo tras la aprobación.

### Hallazgos de la revisión del 2026-10-03

Cada hallazgo se contrastó con el código de `develop` antes de aceptarlo.

| # | Hallazgo | Veredicto | Verificación y decisión |
|---|---|---|---|
| 1 | Subida `PENDING_SCAN` para siempre (stat vacío, tamaño distinto, transición perdida tras borrar) | **Aceptado** | `ScanAttachmentUploadService.scan` devuelve `Mono.empty()` en los tres casos y el listener confirma sin log. Estado `FAILED` + motivo (FR-027 a FR-029). |
| 2 | `read` sin comparar `stat` con lo declarado ni con el máximo | **Aceptado** | `scanStoredObject` lee antes de comparar. FR-028 compara antes de leer y acota la lectura. |
| 3a | `send`/`recover` lanzan excepción y se sale sin ack ni nack | **Aceptado** | `handleFailure` propaga la excepción antes de `basicAck`. Además la cola de escaneo no tiene DLX (`new Queue(properties.queue())`), así que `nack(requeue=false)` solo funciona si se añade (Q9). |
| 3b | JSON ilegible se reintenta `maxAttempts` veces | **Aceptado** | FR-030, error irrecuperable directo a la DLQ. |
| 3c | `AttachmentObjectChangedException` debería ir directo a la DLQ | **Descartado** | FR-025 y este plan exigen volver a analizar el archivo cambiado: el reintento sobre el contenido nuevo es el comportamiento diseñado. Se conserva el reintento. |
| 3d | Reenvío y ack no atómicos (duplicado si falla el ack tras publicar) | **Aceptado como diseño, no como defecto** | El consumo es "al menos una vez" y el consumidor es idempotente (la transición exige `PENDING_SCAN`). Se documenta en FR-031 y se prueba que un duplicado no tiene efecto; no se busca atomicidad imposible entre broker y base. |
| 4a | `complete` ignora el booleano de `transition` y devuelve la versión vieja | **Aceptado** | `requestScanIfComplete` hace `.then(...)` sobre el booleano y `.thenReturn(completed)` con `version` previa. FR-032. |
| 4b | `expiresAt` no se compara al completar; no hay limpieza | **Aceptado** | FR-033 y FR-034 (Q7, Q8). |
| 5a | PUT prefirmado sigue vigente tras `complete` y sin límite de tamaño ni tipo | **Aceptado, con matiz** | Lo escrito después de `complete` no llega a ninguna notificación, porque el escaneo copia por ETag a `clean/` y las notificaciones solo referencian `clean/`. El riesgo real es memoria (hallazgo 2) y almacenamiento sin vigilancia. Q6 elige el mecanismo. |
| 5b | `Instant.now()` en lugar del `Clock` | **Aceptado** | `MinioAttachmentStorageAdapter` línea 113. FR-036. |
| 5c | `copyIfMatch` antes de `transition`: copia huérfana en `clean/` | **Aceptado** | FR-036. El orden se conserva porque la copia debe existir antes de marcar `CLEAN`; se corrige limpiando la copia si se pierde la carrera. |
| 6a | SC-011 sin prueba | **Descartado** | `AttachmentUploadE2ETest.aCleanTenMegabyteFileIsScannedInTimeAndDeliveredByReference` afirma `scanTime <= 30 s` con 10 MB y `Duration`. |
| 6b | SC-012 sin prueba | **Descartado** | `NotificationAttachmentE2ETest.fiveEmbeddedFilesOfOneMegabyteAreAcceptedWithinFiveSeconds` afirma `<= 5 s`. |
| 6c | FR-026 (8 MB) sin prueba | **Descartado** | `NotificationAttachmentE2ETest` (caso `sc002-413`) espera `413` y comprueba que nada se guardó; `NotificationControllerTest` cubre `PAYLOAD_TOO_LARGE`. |
| 6d | Sin prueba del caso del punto 1, del mensaje venenoso, del fallo del `send`; `atLeast(2)` | **Aceptado** | Confirmado: `AttachmentScanListenerTest` usa `atLeast(2)` en la prueba de DLQ. Tareas T079 a T084. |
| 7a | `tasks.md` cita `infra-main/` e `infra-test/` que no existen | **Descartado como defecto, mejorado como legibilidad** | Son alias definidos en `## Path Conventions` (`infra-main/` = `infrastructure/src/main/java/...`, `core-main/` = `core/src/main/java/...`) y resuelven a rutas reales. No hay `utils` citado. Se propone T091 para sustituirlos por rutas reales, de baja prioridad. |
| 7b | Spec y plan deben reflejar la autenticación vigente | **Aceptado, parcial** | El spec no mencionaba `X-Tenant-Id` (se añadió el supuesto); el plan lo citaba en dos lugares (corregidos en esta enmienda) y `quickstart.md` línea 71 también (T091). |
| 8a | `UploadId.of` fuera de `Mono.defer` en `complete` y `get` | **Aceptado** | Confirmado en `AttachmentUploadController`; `issue` sí usa `Mono.defer`. FR-037. |
| 8b | Lista negra sin `.docm .xlsm .html .svg .iso` | **Aceptado** | Confirmado. Es defensa en profundidad: la lista blanca de tipos ya excluye esos tipos y Tika detectaría el contenido. FR-038. |
| 8c | `uploadIdFromKey` acepta `.` y `..` | **Aceptado** | `isUnreserved` incluye `.`. FR-039. |
| 9 | Javadoc en clase interna y record de `AttachmentContentLoader`; comentarios en código de adjuntos | **Aceptado** | Confirmado en `AttachmentContentLoader` (tres bloques `/** */`). Se revisan también `BrevoEmailRequest` y `BrevoNotificationProvider`, que aparecen con comentarios y tocan adjuntos (HU2-093): T090 los trata si son de código de adjuntos. |

Observación fuera de los hallazgos (no se corrige aquí): el consumidor de escaneo usa `.block()` en el hilo
del listener; está declarado en T073 y es el patrón del consumidor de despacho.

### Diseño de la enmienda

- **Estado `FAILED`** (FR-027): `ScanState.FAILED` y un enum de motivo (`OBJECT_MISSING`, `SIZE_MISMATCH`,
  `SCAN_EXHAUSTED`, `EXPIRED`) junto a `AttachmentRejectionReason`. `AttachmentUpload.markFailed` exige
  `PENDING_SCAN`, como `markClean` y `markInfected`. `AttachmentUploadDocument` guarda el motivo.
- **Escaneo** (FR-028, FR-029): `stat` vacío → `FAILED(OBJECT_MISSING)`; `stat.size != sizeBytes` o `> 10 MB`
  → sin leer, `FAILED(SIZE_MISMATCH)`; lectura acotada. Orden de salida: persistir la transición, y solo
  entonces borrar. Si `markClean` pierde la carrera: borrar la copia de `clean/` y registrar.
- **Cierre** (FR-032, FR-033): `transition` devuelve `Boolean`; si es falso, se relee la subida y se
  devuelve; si es verdadero, se publica el escaneo y se devuelve `completed` con la versión nueva. Vencida →
  `FAILED(EXPIRED)` y `409`; una notificación que referencia una subida `FAILED` recibe `400` (Q8 = A).
- **Consumidor** (FR-030, FR-031): se clasifica el fallo. Irrecuperable (JSON ilegible, identificadores
  inválidos) → DLQ en el primer intento. Recuperable → contador por encabezado `x-scan-attempt` y reenvío con
  confirmación del publicador. Si el reenvío o la DLQ fallan → `basicNack(requeue=false)` hacia el DLX de la
  cola de escaneo y confirmaciones del publicador antes del ack (Q9 = A + C). `AttachmentObjectChangedException` sigue siendo recuperable.
- **Dirección de subida** (FR-035, Q6 = A): política POST de MinIO (`PostPolicy`) con `content-length-range`
  exacto y `Content-Type` igual al declarado; la respuesta de emisión devuelve la URL del formulario y los
  campos firmados, y el cliente envía un multipart. Primer paso: actualizar el contrato OpenAPI (T076) y una
  prueba de integración con MinIO real que demuestre que un tamaño o tipo distintos son rechazados por el
  almacén. El borrado del huérfano de `uploads/` al resolverse la subida y la regla de ciclo de vida del
  bucket completan la defensa.
- **Abandonadas** (FR-034, Q7 = A más ciclo de vida): una regla de ciclo de vida de MinIO sobre `uploads/` elimina los temporales huérfanos (se aplica al crear el bucket); el estado inicial real es `PENDING_SCAN`. `ExpireAbandonedUploadsService` en `core` (caso de uso disparado desde
  `infrastructure` por un programador con intervalo configurable) busca por un índice `(state, expiresAt)`
  y aplica la transición condicionada a `FAILED`; es seguro con varias réplicas porque el que pierde la
  transición no hace nada.
- **Constitución**: Principio I (el caso de uso y el puerto de consulta de vencidas en `core`, el
  programador en `infrastructure`); II (contrato primero, T076); III (se retiran los comentarios, T090);
  IV (E2E explícita T089, pruebas con conteo exacto); VII (la excepción de subidas abandonadas se resuelve o,
  si se elige C, se declara con dueño y fecha); **ack manual y DLQ**: se mantiene `MANUAL`, se añade el DLX
  de la cola de escaneo y no hay excepciones nuevas.
- **Riesgos de la enmienda**: añadir `x-dead-letter-exchange` a una cola existente falla con
  `PRECONDITION_FAILED` en un broker que ya la declaró sin argumentos (en desarrollo hay que eliminar y recrear la cola; se
  anota en `quickstart.md`); cambiar el PUT por un POST multipart cambia el contrato del cliente (Q6 = A); sin despliegue fuera de desarrollo, no rompe clientes.

### Pruebas de la enmienda

Siguen el patrón del repositorio. Reglas de las trampas de HU2-072: mensajes producidos con el publicador
real; las pruebas de "no llega nada" con control positivo; umbrales de tiempo con `Duration`; conteos
exactos (`times(n)`) en lugar de `atLeast`.

### Tareas propuestas (T076 en adelante; no están en `tasks.md` hasta la aprobación)

Orden por tarea: prueba, verla fallar por la razón correcta, implementar, ejecutar la clase, `spotless:apply`.

- [ ] T076 Contrato primero, primer paso de la enmienda (Principio II): en `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`, añadir el estado `FAILED` y su motivo a la respuesta de subida, `409` de `:complete` por vencimiento y, la nueva forma de la subida (formulario multipart con política POST firmada, Q6 = A, en lugar de PUT); reflejarlo en `contracts/api-notificaciones-cambios.md`
- [ ] T077 [P] Pruebas en `core/src/test/.../domain/AttachmentUploadTest.java`: `markFailed` solo desde `PENDING_SCAN`, con cada motivo; `FAILED` es final (FR-027)
- [ ] T078 `ScanState.FAILED`, enum de motivo, `AttachmentUpload.markFailed`, `AttachmentUploadDocument` y su mapeo, y la regla del resolvedor (`FAILED` → `400`) con prueba en `AttachmentResolverTest` — depende de T077
- [ ] T079 [P] Pruebas en `core/src/test/.../usecase/ScanAttachmentUploadServiceTest.java` con `StepVerifier`: `stat` vacío → `FAILED(OBJECT_MISSING)`; tamaño distinto o mayor a 10 MB → `verify(storage, never()).read(...)` y `FAILED(SIZE_MISMATCH)`; la transición se persiste antes del `delete` (`InOrder`); transición perdida no borra el objeto de otra réplica y borra la copia de `clean/`; control positivo CLEAN (FR-028, FR-029, FR-036, SC-015, SC-019)
- [ ] T080 Implementar T079 en `ScanAttachmentUploadService` y registrar cada paso a `FAILED` (FR-010) — depende de T078, T079
- [ ] T081 [P] Pruebas en `core/src/test/.../usecase/CompleteAttachmentUploadServiceTest.java`: la transición perdida no publica y devuelve el estado vigente; la respuesta lleva la versión nueva; vencida → `FAILED(EXPIRED)` y error; 10 `complete` concurrentes con un repositorio atómico de prueba publican exactamente 1 escaneo (FR-032, FR-033, SC-018)
- [ ] T082 Implementar T081 en `CompleteAttachmentUploadService` — depende de T078, T081
- [ ] T083 [P] Reescribir las pruebas de `infrastructure/src/test/.../adapter/in/rabbit/AttachmentScanListenerTest.java` con RabbitMQ real y el publicador real: mensaje ilegible → DLQ con 1 intento; fallo del reenvío y fallo del `recover` → el mensaje termina en la DLQ, no reaparece y no se pierde; un mensaje que siempre falla se intenta `times(maxAttempts)` (reemplaza `atLeast(2)`); `AttachmentObjectChangedException` se reintenta; duplicado de un mensaje ya resuelto sin efecto; control positivo (FR-030, FR-031, SC-016, SC-017)
- [ ] T084 Implementar T083 en `AttachmentScanListener` y añadir DLX a la cola de escaneo en `AttachmentScanRabbitConfig` y `basicNack(requeue=false)` si falla el reenvío o la DLQ (Q9 = A + C), con confirmaciones del publicador antes del ack — depende de T083
- [ ] T085 [P] Pruebas de `ExpireAbandonedUploadsService` en `core` y de la consulta de vencidas en `AttachmentUploadMongoAdapterTest` (Testcontainers): vencida y pendiente → `FAILED`; no vencida, `CLEAN` e `INFECTED` no se tocan; dos ejecuciones simultáneas, una sola gana (FR-034, SC-021)
- [ ] T086 Implementar `ExpireAbandonedUploadsService`, el puerto de consulta de vencidas, su índice `(state, expiresAt)`, el programador en `infrastructure` con intervalo configurable en `AttachmentProperties`, y la regla de ciclo de vida de MinIO sobre `uploads/` con su prueba de integración — depende de T085 (Q7 = A más ciclo de vida)
- [ ] T087 [P] Pruebas en `MinioAttachmentStorageAdapterTest` con MinIO real: la subida de un objeto de tamaño o tipo distintos del declarado es rechazada (SC-020); el vencimiento usa el `Clock` inyectado (reloj fijo) (FR-035, FR-036)
- [ ] T088 Implementar T087 en `MinioAttachmentStorageAdapter` (inyectar `Clock`; política POST de Q6 = A) y el borrado del huérfano de `uploads/` al resolverse la subida — depende de T076, T087
- [ ] T089 **E2E explícita** en `AttachmentUploadE2ETest`: objeto re-subido con otro tamaño tras `:complete` → `FAILED(SIZE_MISMATCH)` sin leerse; subida abandonada → `FAILED(EXPIRED)` con el objeto borrado en un plazo afirmado con `Duration`; 10 `:complete` simultáneos → un solo escaneo; control positivo: una subida sana llega a `CLEAN` y se entrega; y los casos de dos tenants existentes siguen verdes (SC-015, SC-018, SC-019, SC-021)
- [ ] T090 [P] Pruebas y cambio de política/controlador: `AttachmentPolicyTest` y `AttachmentUploadTest` (`.docm .xlsm .html .htm .svg .iso`, `.` y `..` en `uploadIdFromKey`, control positivo `informe.pdf`), `AttachmentUploadControllerTest` (identificador inválido en `complete` y `get` responde por el flujo, no por excepción síncrona); implementar en `AttachmentPolicy`, `AttachmentUpload.uploadIdFromKey` y `AttachmentUploadController` (FR-037 a FR-039, SC-022)
- [ ] T091 Retirar los comentarios y el Javadoc del código de adjuntos (`AttachmentContentLoader` y lo que muestre `grep -rn "/\*\*\|^\s*//"` en los archivos de adjuntos); actualizar `tasks.md` (alias de ruta por rutas reales) y `quickstart.md` (autenticación con token en lugar de `X-Tenant-Id`, recrear la cola de escaneo por el DLX) (FR-040)
- [ ] T092 `./mvnw -B -ntp spotless:apply`, `HexagonalArchitectureTest` y `ModularityTests`, y `./mvnw -B -ntp verify` completo en verde (cobertura ≥ 80 % líneas / ≥ 70 % ramas); si solo fallan `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo

## Pendientes declarados (Principio VII)

| Pendiente | Dueño | Fecha de revisión |
|---|---|---|
| Retención y borrado de archivos: `BinData` en `notifications`, objetos limpios en MinIO, subidas y veredictos. El servicio pasa a custodiar documentos de destinatarios. Hasta resolverlo, nada se borra salvo los objetos `INFECTED` y los de un tamaño incorrecto. | andrualv | 2026-12-31 |
| ~~Subidas abandonadas y escaneos agotados sin limpieza~~: resuelto en la Enmienda 3.1 (FR-027, FR-034; Q7 = A más ciclo de vida). Ya no es una excepción. | — | — |
| Decisión de arquitectura sobre MinIO y ClamAV fuera de desarrollo (servicio gestionado, dimensionamiento, actualización de firmas). | andrualv | antes de desplegar fuera de desarrollo, a más tardar 2026-12-31 |
| Motivo textual del fallo por proveedor sin adjuntos (heredado de la v1). | andrualv | 2026-12-31 |
| Registros estructurados (JSON) incompletos (heredado de la v1; brecha RNF-10). | andrualv | 2026-12-31 |

## Orden de implementación y puntos de control

**Prerrequisito, cumplido el 2026-09-29**: el usuario aceptó la v3 (`## Estado del plan` → Aceptado), el
spec se actualizó con Q1 reabierta, los demás artefactos se reescribieron y `tasks.md` se regeneró. Los
pasos siguientes son los de `tasks.md`.

Orden por tarea: escribir la prueba, verla fallar por la razón correcta, implementar, ejecutar la clase y
`spotless:apply`.

1. **Contrato público**: aplicar el `contracts/api-notificaciones-cambios.md` reescrito a
   `api-notificaciones.yaml` (Principio II).
2. **Infraestructura local y dependencias**: `minio` y `clamav` en `docker-compose.yml`; Tika, MinIO y el
   módulo de Testcontainers en los `pom.xml`. Punto de control: `clean compile` en verde, contenedores de
   ClamAV y MinIO arrancando bajo Testcontainers en una prueba mínima, y medición del tiempo de arranque de
   ClamAV.
3. **Modelo en `core`**: `AttachmentSubmission`, `Attachment` rediseñado, `AttachmentSource`,
   `Sha256Digest`, `ScanState`, `ScanVerdict`, `AttachmentUpload` y `AttachmentPolicy` v3, con sus
   pruebas. Compilar `core` e `infrastructure`: el cambio de `Attachment` rompe el mapeo de la v1 y debe
   corregirse aquí.
4. **Puertos y servicios de `core`**: `AttachmentInspector`, `AttachmentResolver` y los cuatro servicios
   de subida, con puertos simulados. Punto de control: `./mvnw -B -ntp -pl core test` en verde.
5. **Adaptadores con integración real**: Tika, ClamAV, MinIO, `AttachmentUploadMongoAdapter`,
   `ScanVerdictMongoAdapter` y `AttachmentDocument`/mapper/proyección.
6. **Flujo de subida**: controlador de subidas, publicador y `AttachmentScanListener` (ack manual + DLQ)
   con sus pruebas.
7. **Aceptación**: `SendNotificationService` con el resolvedor, lote, `NotificationController`,
   manejador de errores, límite del codec y registros.
8. **Despacho**: falla cerrada (Q4), como la v1.
9. **E2E**: `NotificationAttachmentE2ETest` y `AttachmentUploadE2ETest` (tareas E2E explícitas del
   Principio IV).
10. **Regresión**: `ProviderRoutingE2ETest`, `ChannelCatalogQueryE2ETest`, `ChannelCatalogE2ETest`, E2E de
    proveedores, `NotificationControllerSearchE2ETest`, `NotificationLiveUpdates*E2ETest`,
    `HexagonalArchitectureTest` y `ModularityTests`.
11. **`./mvnw -B -ntp verify` completo y en verde.** Si las únicas que fallan son
    `DeadLetterQueueE2ETest` y `RabbitRetryConfigCustomAttemptsTest`, repetir excluyéndolas y decirlo
    explícitamente: sobre esas dos decide CI.

## Riesgos de esta implementación

| Riesgo | Cómo lo acota el plan |
|---|---|
| El alcance crece de M a XL: dos dependencias de infraestructura, tres endpoints, un consumidor nuevo y una máquina de estados. | Orden por capas con puntos de control; el camino chico puede cerrarse y probarse antes que el grande. Si el usuario lo prefiere, el camino grande puede separarse en una historia propia sin cambiar el contrato del camino chico. |
| Fuga entre tenants (antecedente de HU2-072). | `tenantId` propio en subida, adjunto y caché; búsquedas siempre por tenant; prefijo por tenant en MinIO; respuestas idénticas para "no existe" y "es de otro tenant"; E2E con dos tenants que revisa qué se hace con el resultado, no solo el predicado, y con control positivo. |
| Sobrescritura del objeto con la dirección de subida aún vigente después del escaneo. | Lectura y copia condicionadas por ETag a una clave inmutable nunca prefirmada; prueba de integración con ETag obsoleto. |
| ClamAV lento para arrancar (base de firmas), con más de 1 GB de memoria y dependiente de la red para actualizar firmas. Encarece CI y el entorno local. | Imagen oficial fijada por versión con base de firmas incluida; un solo contenedor compartido por clase de prueba; medición en el paso 2 antes de seguir; la E2E usa EICAR, que no depende de firmas recientes. |
| ClamAV caído bloquea la aceptación de adjuntos chicos. | Falla cerrada con `503` y tiempo máximo configurable; las notificaciones sin adjuntos no pasan por ClamAV; ClamAV queda fuera de *readiness*. |
| Presión de memoria: límite del cuerpo en 8 MB por solicitud, lote con 16 elementos concurrentes y hasta 10 MB por escaneo en el consumidor. | Concurrencia del consumidor de escaneo acotada (2 por defecto, configurable); concurrencia hacia `clamd` acotada en el adaptador; el lote no tiene operación HTTP hoy. Se revisa con la prueba de carga pendiente del proyecto. |
| `BinData` en `notifications` engorda cada lectura de búsqueda, reencolado y actualizaciones en vivo. | Proyección que excluye `attachments.content` en búsqueda y reencolado, con prueba de integración; el despacho sí lo lee completo, porque lo necesita. |
| Tika arrastra dependencias con CVE (el detector de OOXML) y el escaneo de dependencias de CI las marca. | Solo `tika-core` más el detector de contenedores necesario, con versión fijada; si Trivy marca algo, se actualiza o se documenta como pendiente con dueño y fecha, nunca con una exclusión silenciosa. |
| Detección de Tika imperfecta (CSV como `text/plain`, OOXML como ZIP si falta el detector). | Tabla de compatibilidad explícita y probada con archivos reales de cada tipo de la lista blanca. |
| Librerías bloqueantes en un servicio reactivo. | `MinioAsyncClient`, `clamd` por Reactor Netty y Tika en `boundedElastic`; ningún `.block()` nuevo. |
| El contenido o la dirección prefirmada se filtran por un `toString`, un mensaje de validación o un registro. | `toString` sin ellos en `AttachmentSubmission`, `Attachment`, `AttachmentRequest` y `AttachmentUploadResponse`; fuera del nodo validado; la dirección no se guarda; E2E con control positivo. |
| Un nombre de archivo malicioso parte una línea de registro (inyección CRLF, FindSecBugs). | `AttachmentLogFormatter` reemplaza caracteres de control; si SpotBugs lo marca igualmente, se corrige el código en lugar de añadir una exclusión. |
| `byte[]` expuesto en records (SpotBugs `EI_EXPOSE_REP`). | Copias defensivas en `EmbeddedContent` y en el documento. |
| Veredicto `CLEAN` cacheado que las firmas nuevas detectarían. | Caché `CLEAN` invalidada por cambio de versión de firmas y con TTL de 24 h; `INFECTED` no vence. |
| El método abstracto rompe implementaciones no previstas. | Ya resuelto en la v1 (4 adaptadores + 3 dobles) y se conserva. |
| Las E2E que editan `channel_catalog` interfieren con otras clases. | Canales de prueba con nombres propios, restaurados en `@BeforeEach`; espera activa a que `GET /channels` los refleje. |
| Código de la v1 ya commiteado y trabajo sin commitear que contradicen la v3 (`url` https arbitraria como adjunto válido). | `tasks.md` de la v3 reabre cada pieza afectada según la tabla de [Impacto en lo ya implementado](#impacto-en-lo-ya-implementado); la compilación tras el cambio de `Attachment` y de `SendNotificationCommand` obliga a corregir todo uso anterior. |

---

**Versión 3 aceptada por el usuario el 2026-09-29.** Spec, research, data-model, quickstart, contrato y
`tasks.md` están alineados con ella. El código de la v1 y el trabajo sin commitear de T019–T022 todavía no
se han tocado: su rediseño son tareas abiertas de `tasks.md`, que se ejecutan con `/speckit-implement`.
