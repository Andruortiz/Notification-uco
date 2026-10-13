# Research: Corregir los hallazgos de la revisión de las specs 011 a 016

**Feature**: 017-Fix-specs-012-016 | **Fecha**: 2026-10-05 | **Base**: `origin/develop` en `9298d6b`

El informe es del 2026-10-02 y se hizo sobre `ea79f56`. Desde entonces entraron HU2-092 (adjuntos), el
modo de autenticación `platform` y varias correcciones. Antes de decidir nada se volvió a verificar cada
hallazgo contra el código actual con cuatro revisiones de solo lectura; el resultado es la sección 1. Las
decisiones de diseño están en la sección 2.

## 1. Estado verificado de los hallazgos

Leyenda: **A** abierto, **C** corregido desde el informe, **P** parcial, **D** documental (artefactos de spec).
Las revisiones las hicieron agentes; los puntos que sostienen el diseño (A-02, A-03, A-04, A-01) los releyó la
sesión principal en el código.

### Autenticación y seguridad

| ID | Estado | Evidencia actual |
|---|---|---|
| A-01 | A | `application.yml:50` conserva el secreto de desarrollo por defecto; no hay `@Validated` ni verificación de arranque |
| A-02 | A | `AuthenticationWebFilter.filter` (líneas 68-71): el `onErrorResume` va después del `flatMap` que ejecuta `chain.filter` |
| M-01 | A | `LocalJwtTokenValidationAdapter.parseClaims` no exige `exp`; en `platform` tampoco |
| M-02 | A | `AuthenticationWebFilter.isExempt` usa `startsWith` sin delimitar el segmento |
| M-03 | A | `?access_token=` con el JWT de 12 h en `/notifications:subscribe` |
| M-04 | A | `classify` por tipo de causa; `extractTenantId` solo lee tokens expirados |
| M-05 | A | `AuthJwtProperties` y `AuthPlatformProperties` sin validación de arranque (la de clave pública en `platform` sí falla con mensaje claro) |
| M-06 | A | No hay prueba de p95 de 200 ms (SC-004 de 015) |
| M-29 | P | `token.mjs` ya no está versionado en `develop`, pero sigue en disco sin seguimiento, no está en `.gitignore` y contiene el secreto |
| B-04, B-05, B-06, B-21 | A | Observaciones menores, sin cambio |

### Lote y adjuntos

| ID | Estado | Evidencia actual |
|---|---|---|
| A-07 | A | El contrato solo tiene `minItems: 1` en el lote (`api-notificaciones.yaml:1153`) |
| A-08 | A | `SendNotificationBatchService.java:55` traga el error de guardado sin registro; una prueba fija ese comportamiento |
| A-09 | A | Un reenvío con el mismo `batchId` reprocesa los ítems y el guardado falla por la clave única `tenant_batch_unique`, que A-08 traga |
| M-11 | A | `failed(...)` devuelve y persiste `ex.getMessage()` de cualquier `Throwable` |
| M-12 | C | `SendNotificationRequest` normaliza `attachments` nulo a `List.of()` |
| M-13 | P | Sin `@Valid`, pero hay pruebas de lista vacía, nula, elemento nulo y prioridad inválida; solo falta el tope |
| A-10 | P | Ya existe `ScanState.FAILED` con motivos `OBJECT_MISSING`, `SIZE_MISMATCH`, `SCAN_EXHAUSTED` y `EXPIRED`, y un sweeper. Quedan dos huecos: la pérdida del CAS y `discardOrphanCopy` terminan en `Mono.empty()` sin registro, y un `AttachmentObjectChangedException` agotado deja la carga en `PENDING_SCAN` hasta que actúa el sweeper (1 h) |
| A-11 | C | Se compara el tamaño real con el declarado y con el máximo (10 MiB) antes de leer, y la lectura se ata al ETag |
| M-19 | C | Se compara `expiresAt`, se usa el booleano de `transition` y hay sweeper de abandonados |
| M-20 | P | La subida es un POST firmado con `content-length-range` exacto y usa el `Clock` inyectado; la política sigue vigente tras `complete` (mitigada con una regla de ciclo de vida de 1 día) |
| M-21 | P | Se resolvieron la confirmación de publicación y el JSON venenoso; el `basicAck` tras republicar sigue sin ser atómico y el reintento no tiene retardo |
| M-22, B-11, B-12, B-13 | C | Corregidos |

### Despacho, reencolado y globales

| ID | Estado | Evidencia actual |
|---|---|---|
| A-03 | A | `RabbitRetryConfig` no fija `AcknowledgeMode`; solo el listener de adjuntos usa `MANUAL` |
| A-04 | A | `DispatchNotificationService.sendThrough`: `markQueued` en memoria, `send`, `save`; no hay reserva previa |
| A-05 | A | `markQueued()` sobre `IN_PROCESS`, `DELIVERED` o `FAILED` lanza `InvalidStatusTransitionException`; hoy solo se llega por reentrega tras un `save` fallido |
| A-06 | A | `recoverOrphanedPending` traga el error; un `PENDING` reencolado con intentos ya no cumple `deliveryAttempts().isEmpty()` |
| A-13 | A | `FAILED -> PENDING` conserva los intentos y `recoverableAttemptCount` cuenta todo el historial. El reintento manual aún no existe (spec 018) |
| M-24 | A | Solo se ignora `NotificationVersionConflictException`; otro error corta el `Flux.merge`; no hay candado entre réplicas |
| M-25 | A | Un error del `sender` se propaga sin guardar nada; no hay failover |
| M-26 | A | `Notification.version` es `final`; dos `save` sobre la misma instancia dan un conflicto falso |
| M-27 | A | El manejador no cubre `Exception`, conflicto de versión ni clave duplicada |
| M-08, M-09, M-10, B-01, B-03 | A | Catálogo: razones de configuración visibles para `CLIENTE`, snapshot vacío indistinguible de "sin cargar", parámetro sin usar |
| M-15, M-16, M-17 | A | Propagación del MDC; sin cambios |
| B-15..B-20 | A | `findByStatus` sin límite ni índice, eventos sin outbox, `.toList().size()` duplicado, prefijo vacío del vault, sembrado que traga el error, dependencias de `core` |
| M-28 | P | Quedan 1 comentario `//` y 12 bloques Javadoc de 56 líneas en miembros no públicos de 7 archivos de `main`; los 8 de la lista original ya no tienen `//` |

### Artefactos de spec

| ID | Estado | Evidencia actual |
|---|---|---|
| M-07 | D, cerrado en PR #57 | Las tareas T034 y T041–T045 de 015 quedan marcadas con evidencia al mergear #57 |
| M-14 | D | `014/plan.md` sigue `Estado: Pendiente` y `spec.md` en `Draft`; las tareas T007–T009 las cierra #57 |
| M-18 | D, parcial en #57 | T036 de 016 la cierra #57; T033 sigue marcada sin que la prueba mencione la correlación |
| M-30 | D | `X-Tenant-Id` sigue en 011 (10 menciones), 013 (4), 014 (8) y 018 (5); 012-adjuntar conserva residuos en `plan.md:236,740` y `tasks.md:251` |
| M-31 | D | 013: 19 tareas sin marcar, plan `Pendiente`, clarificaciones sin confirmar, FR-012 y FR-015 sin prueba planificada |
| A-12 | D | 018: la spec dice "no hay rol de operador" y usa `X-Tenant-Id`; ninguna tarea prueba 403 (CLIENTE) ni 202 (OPERADOR) |
| M-23 | D | 018: la excepción del ack `AUTO` sigue sin dueño ni fecha; el hueco de `PENDING` bloqueado no tiene tarea |
| B-10 | D | 016: FR desordenados; la excepción vence el 2026-10-15 |
| B-14 | D | Rutas `infra-main/` e `infra-test/` obsoletas: 15 en 011 y 13 en 018 |

**Resumen**: de los 13 hallazgos altos del informe, 11 siguen abiertos (A-01 a A-09, más A-12 y A-13, estos
dos en lo documental y de diseño), 1 está parcial (A-10) y 1 está corregido (A-11).

## 2. Decisiones de diseño

### D1. Secreto de firma obligatorio y sin valor público (A-01, M-01, M-05, M-29)

- **Decisión**: quitar el valor por defecto de `application.yml` (`${AUTH_JWT_HS256_SECRET:}`) y validar al
  arrancar: el secreto debe existir, tener al menos 32 bytes y no ser igual al de desarrollo conocido. El
  valor de desarrollo solo existe en `application-local.yml`, que se carga únicamente con el perfil Spring
  `local`. `ttl-minutes` se valida en un rango (1 a 1440). El parser exige la claim `exp` y rechaza con
  `InvalidTokenException` un token sin ella. `.env.example` deja de publicar un secreto utilizable.
  `token.mjs` se añade a `.gitignore` y se reescribe para exigir `AUTH_JWT_HS256_SECRET` por entorno, sin valor por
  defecto y sin comentarios.
- **Justificación**: es el riesgo más grave del informe y la única forma de que "fuera del perfil local" sea
  verificable es un perfil explícito. `exp` obligatorio cierra tokens eternos.
- **Alternativas**: solo advertir en el log (rechazada: un secreto público sigue permitiendo forjar tokens);
  mantener el default y exigir un perfil `prod` (rechazada: falla abierto si se olvida el perfil).
- **Impacto**: las pruebas que dependen del secreto por defecto deben declararlo explícitamente; el modo
  `platform` no cambia, salvo la exigencia de `exp`.

### D2. El filtro de autenticación solo captura errores de la validación (A-02, M-04)

- **Decisión**: separar la validación del token (que produce el principal o un motivo de rechazo) de la
  continuación de la cadena, de modo que el manejo de errores envuelva solo la validación. Un error posterior
  llega al manejador global y responde 5xx. `classify` pasa a depender de un motivo explícito que lanzan los
  adaptadores, no del tipo de la causa, y el log del rechazo lleva el `tenantId` cuando el token lo permite.
- **Alternativas**: mantener la estructura y comprobar si la respuesta ya está comprometida (rechazada: tapa
  el síntoma y sigue enmascarando 5xx).

### D3. Exenciones por segmento completo (M-02)

- **Decisión**: una ruta está exenta si es igual a la exención o empieza por `exención + "/"`. Se mantiene la
  lista actual (`/actuator`, `/v3/api-docs`, `/swagger-ui`, `/openapi`) y se añaden pruebas con `/actuatorX`,
  `/openapiX` y la barra final.

### D4. Ticket de un solo uso para el panel en vivo (FR-019, M-03)

- **Decisión**: nueva operación `POST /notifications:subscribeTicket` (rol mínimo `CLIENTE`, igual que
  `:subscribe`) que devuelve un ticket opaco de 32 bytes aleatorios en base64url con vigencia de **30 s**.
  El ticket se guarda como huella SHA-256 en la colección `subscription_tickets` de MongoDB, con el tenant, el
  rol y el sujeto del principal y un índice TTL. `GET /notifications:subscribe?ticket=...` lo consume con una
  operación atómica de borrado condicionado (`findAndRemove` por huella y `expiresAt > ahora`): el segundo uso
  o uno vencido da 401. `access_token` deja de aceptarse en esa ruta. La cabecera `Authorization` sigue
  siendo válida y tiene prioridad.
- **Justificación**: la constitución exige almacén compartido para el estado estrictamente consistente entre
  réplicas, y un ticket de un solo uso lo es (un ticket emitido por una réplica puede consumirse en otra).
  MongoDB ya es dependencia obligatoria. Guardar solo la huella evita que una lectura de la base entregue
  tickets utilizables.
- **Alternativas**: almacén en memoria por réplica (rechazada: falla con más de una réplica sin afinidad de
  sesión); Redis (rechazada: no está en el stack); ticket JWT sin estado (rechazada: no puede ser de un solo
  uso); mantener `access_token` con vida corta (rechazada: sigue viajando en la URL).
- **Impacto**: cambio incompatible para clientes que usen `EventSource` nativo con `access_token`. El
  frontend actual usa `fetch-event-source` con cabecera y no se ve afectado. Hay que actualizar el contrato, el
  quickstart de 015 y el E2E de 015 que prueba `access_token`.
- **Confirmación pendiente**: la spec marca este punto como recomendación pendiente de confirmar por el
  usuario. Se confirma al aprobar el plan.

### D5. Tope de ítems por lote (A-07)

- **Decisión**: máximo **500** ítems por lote, definido como `maxItems: 500` en el contrato y como constante
  única en el núcleo (`BatchLimits.MAX_ITEMS`). El controlador rechaza con 400 antes de procesar nada.
- **Justificación**: el límite de cuerpo de 8 MiB permitiría miles de ítems; con un procesamiento de 16 en
  paralelo, 500 acotan una petición a un trabajo del orden de segundos. SC-003 exige rechazo en menos de 1 s.
- **Alternativas**: 100 (muy restrictivo para envíos masivos legítimos); 1000 (duplica el tiempo máximo de una
  petición sin un caso de uso que lo pida); configurable por entorno (rechazada: el contrato debe declarar un
  valor único y verificable).

### D6. Idempotencia y visibilidad del registro del lote (A-08, A-09, M-11)

- **Decisión**: el puerto `NotificationBatchRepository` gana `findByTenantAndBatchId`. Si el lote ya existe,
  se devuelve el resultado guardado sin reprocesar. Si no existe, se procesa y se guarda; una
  `DuplicateKeyException` por carrera se resuelve devolviendo el registro ganador. Si el guardado falla por
  otra causa, se registra con categoría `BATCH_RECORD_NOT_PERSISTED` y `BatchAcceptedResponse` incluye
  `trackingSaved: false` (campo aditivo; `true` en el caso normal). Los ítems fallidos devuelven el motivo
  fijo `Internal error` con la causa registrada en el log, nunca el mensaje de la excepción. El adaptador usa
  el `Clock` inyectado en lugar de `Instant.now()`.
- **Alternativas**: devolver 5xx si falla el guardado (rechazada: las notificaciones ya se aceptaron y no deben
  perderse); cabecera en vez de campo (rechazada: el campo es explícito y está en el contrato).

### D7. Cierre de los huecos de adjuntos que quedan (A-10)

- **Decisión**: registrar a nivel INFO la pérdida del CAS y el descarte de la copia huérfana. Cuando el
  listener agota los intentos con `AttachmentObjectChangedException`, invoca el mismo caso de uso de fallo que
  usa el sweeper para llevar la carga a `FAILED` con `SCAN_EXHAUSTED` sin esperar 1 hora.
- **Alternativas**: dejar solo el sweeper (rechazada: la carga queda colgada una hora sin motivo visible).

### D8. Despacho: reserva atómica, ack manual y reentrega idempotente (A-03 a A-06, M-24 a M-26)

- **Decisión**:
  1. `NotificationRepository` gana `reserveForDispatch(id)`: `findAndModify` con filtro `{_id, status: PENDING}`
     que pasa el estado a `IN_PROCESS`, fija `dispatchReservedAt` y devuelve el documento reservado. Si no hay
     coincidencia, el servicio lee el estado: `IN_PROCESS`, `RECOVERABLE`, `DELIVERED`, `FAILED` o `DISCARDED`
     significan reentrega y se ignoran con un registro INFO; ausente se trata como mensaje venenoso
     (`NotificationNotFoundException`, con reintentos acotados y luego DLQ).
  2. El envío al proveedor solo ocurre con la reserva ganada. El resultado se guarda con la instancia devuelta
     por la reserva (corrige M-26) y luego se publican los eventos.
  3. Si `sender.send` falla con una excepción antes de producir un resultado (proveedor deshabilitado o no
     disponible), se hace `releaseReservation(id)` (vuelve a `PENDING`) y se propaga el error. Así se conserva
     el comportamiento de 008, 009 y 010 (la notificación queda `PENDING` sin intentos y el mensaje va a la
     DLQ con la causa).
  4. El listener pasa a `MANUAL`. Confirma solo después de persistir el resultado. Ante un fallo reintenta
     republicando con la cabecera `x-dispatch-attempt` hasta `max-attempts` y luego deriva a la DLQ con
     `x-exception-message`, con el mismo patrón que `AttachmentScanListener`; la lógica común se extrae a un
     componente compartido para no duplicarla.
  5. **Fallo de guardado tras un envío aceptado**: se reintenta el guardado de forma acotada dentro del mismo
     procesamiento. Si sigue fallando, la notificación queda `IN_PROCESS` en Mongo, se hace nack a la DLQ y la
     reentrega posterior se ignora (no hay segundo envío, SC-005). Un barrido libera las `IN_PROCESS` con
     `dispatchReservedAt` anterior a 10 minutos pasándolas a `RECOVERABLE`.
  6. El reencolado deja de depender de que `deliveryAttempts` esté vacío: un `PENDING` es huérfano si su
     `pendingSince` (nueva marca, fijada en cada transición a `PENDING`) es anterior al umbral. Cada
     notificación se reclama con `claimForRequeue(id)` (`findAndModify` que adelanta `pendingSince`), lo que
     impide que dos réplicas la tomen a la vez y reintenta de forma natural un encolado fallido en la pasada
     siguiente. Los errores se aíslan por notificación y se registran con categoría, sin cortar la pasada.
     Las consultas llevan límite de lote (100 por defecto) y un índice compuesto `{status, pendingSince}`.
- **Riesgo residual aceptado y registrado**: si el proveedor acepta, el guardado falla repetidamente y el
  barrido libera la notificación tras 10 minutos, el reenvío puede duplicar la entrega. Es el caso de doble
  fallo; la clave de idempotencia de Brevo lo cubre solo para ese proveedor. Se registra como excepción del
  Principio VII con dueño y fecha (ver `plan.md`).
- **Alternativas**: outbox transaccional con estado de envío (rechazada: obliga a un rediseño mayor y a
  transacciones multidocumento); dejar `IN_PROCESS` atascado a la espera de un operador (rechazada: viola el
  Principio VIII, ningún error de infraestructura puede dejar una notificación sin camino); marcar `FAILED`
  tras el timeout (rechazada: convierte un fallo de infraestructura en un fallo terminal de negocio).

### D9. Regla del contador del reintento manual (A-13)

- **Decisión**: cada `DeliveryAttempt` guarda el `cycle` en que ocurrió y `Notification` guarda `currentCycle`
  (1 al aceptar). Un reintento manual de una notificación `FAILED` incrementa `currentCycle`; el conteo de
  intentos recuperables, el límite y el backoff se calculan solo sobre el ciclo actual, y el historial
  completo se conserva. Los documentos existentes sin `cycle` se leen como ciclo 1.
- **Alternativas**: borrar los intentos (rechazada: pierde el historial exigido por IX); sumar sin reiniciar
  (rechazada: el primer fallo recuperable tras un reintento manual vuelve a `FAILED` sin backoff, que es el
  defecto A-13).
- **Alcance**: esta spec define el modelo y la regla; el caso de uso `:retry` lo construye la 018.
- **Contradice una decisión previa**: la descripción de `retryNotification` en
  `specs/018-reintentar-envio-manual/contracts/api-notificaciones-cambios.md` dice que "un reintento manual
  cuenta dentro del tope de reintentos automáticos y no lo reinicia". El informe (A-13) la señala como el
  origen del defecto. Cambiarla es decisión del usuario y se pide confirmación al aprobar el plan; si se
  mantiene la regla actual, el reintento manual deja de ser útil tras agotar los automáticos.

### D10. Respuestas de error coherentes (M-27)

- **Decisión**: el manejador global añade `NotificationVersionConflictException`,
  `NotificationAlreadyAcceptedException` e `InvalidStatusTransitionException` como 409 con mensaje fijo, y una
  rama final para `Exception` que responde 500 con mensaje genérico e identificador de correlación y registra
  la causa en ERROR con categoría, sin devolver el mensaje interno. Los 409 y el 500 se documentan en el
  contrato.

### D11. Cero comentarios en producción (M-28)

- **Decisión**: eliminar el comentario `//` y los 12 bloques Javadoc de miembros no públicos de `main`, y
  añadir una prueba que escanee `src/main` de los tres módulos y falle si encuentra `//` o `/* */`
  explicativos. La prueba lleva un control positivo (un fixture con comentario que debe detectar).
  `./mvnw spotless:apply` tras los cambios.
- **Alternativas**: Checkstyle (rechazada: añade una herramienta nueva para una sola regla); confiar en la
  revisión (rechazada: el hallazgo ya se repitió).

### D12. Reconciliación de artefactos (M-14, M-18, M-30, M-31, A-12, M-23, B-10, B-14)

- **Decisión**: es trabajo de documentación y se hace en su propio PR sin tocar código. Reemplaza `X-Tenant-Id`
  y los 400 por credencial Bearer y 401 en 011, 013, 014 y 018, y limpia los residuos de 012-adjuntar;
  sustituye `infra-main/` e `infra-test/` por las rutas actuales; en 018 declara el rol `OPERADOR` para
  `:retry`, añade las pruebas de 403 (`CLIENTE`) y 202 (`OPERADOR`), cubre el `PENDING` bloqueado y sustituye
  la excepción del ack `AUTO` por la dependencia de esta spec (el despacho con ack manual y reserva atómica);
  en 013 confirma las clarificaciones, cubre FR-012 y FR-015 y reconcilia el plan; marca el estado real de
  planes y tareas de 014 y 016 y reordena los FR de 016.

## 3. Hallazgos que no se comprometen en esta spec

La spec limita el alcance a los requisitos FR-001 a FR-019. Los siguientes no figuran en ellos. Los de
severidad media llevan dueño y fecha según el Principio VII; los de severidad baja quedan como mejoras
opcionales sin fecha.

| Hallazgo | Tratamiento |
|---|---|
| M-06 (prueba de p95 de 015), M-08 (razones de configuración visibles a `CLIENTE`), M-09 (snapshot vacío indistinguible de "sin cargar"), M-10, M-15, M-16, M-17 (propagación del MDC), M-20, M-21 (atomicidad del ack del escaneo) | Excepción del Principio VII: dueño equipo de desarrollo del componente, fecha 2026-11-30 |
| B-01..B-09, B-15..B-21 | Mejoras opcionales, sin compromiso de fecha |
| M-25 (failover de proveedor) | Fuera de alcance: requiere definir la política de failover; ver D8 para el comportamiento mínimo que sí se corrige |
