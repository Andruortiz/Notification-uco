# Implementation Plan: Corregir los hallazgos de la revisión de las specs 011 a 016

**Branch**: `fix/revision-specs-011-016` | **Date**: 2026-10-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/017-Fix-specs-012-016/spec.md`

## Estado del plan

**Estado**: Aceptado

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

El informe de revisión del 2026-10-02 encontró 13 hallazgos altos, 31 medios y 21 bajos. Antes de planificar
se verificó cada uno contra `develop` (`9298d6b`): desde el informe, HU2-092 corrigió buena parte de los
adjuntos, y el trabajo de esta spec se concentra en cuatro frentes que siguen abiertos:

1. **Autenticación y arranque seguros**: secreto sin valor público y validado al arrancar, `exp` obligatorio,
   filtro que no confunde errores posteriores con rechazos de credencial, exenciones por segmento y ticket de
   un solo uso para el panel en vivo.
2. **Lote y adjuntos**: tope de ítems, idempotencia y visibilidad del registro del lote, motivos genéricos y
   cierre de los dos huecos que quedan en el escaneo.
3. **Despacho**: reserva atómica antes de enviar, ack manual tras persistir, reentrega idempotente,
   reencolado que no deja huérfanos ni duplica entre réplicas, y la regla del contador del reintento manual.
4. **Higiene del proyecto**: respuestas 409 y 500 coherentes, cero comentarios en producción con una prueba
   que lo vigile, y artefactos de spec que describan el servicio real.

El detalle de la verificación y de cada decisión (con alternativas) está en [research.md](./research.md);
los cambios de datos y puertos, en [data-model.md](./data-model.md); los cambios del contrato HTTP, en
[contracts/api-notificaciones-cambios.md](./contracts/api-notificaciones-cambios.md); y cómo validar, en
[quickstart.md](./quickstart.md).

## Technical Context

**Language/Version**: Java 21 (ADR-0001)

**Primary Dependencies**: Spring Boot 3.3.4 sobre WebFlux y Reactor, Spring Data Reactive MongoDB, Spring AMQP,
jjwt, Spring Modulith, Testcontainers

**Storage**: MongoDB (colecciones `notifications`, `notification_batches` y la nueva `subscription_tickets`);
RabbitMQ (cola de despacho, DLQ y reintentos por republicación)

**Testing**: JUnit 5, Mockito, StepVerifier, ArchUnit (`HexagonalArchitectureTest`, `ModularityTests`),
Testcontainers para los E2E; JaCoCo con umbral 80 % líneas y 70 % ramas

**Target Platform**: contenedor Docker con varias réplicas detrás de un balanceador, sin afinidad de sesión

**Project Type**: microservicio hexagonal (módulos `core`, `infrastructure`, `utils`)

**Performance Goals**: rechazo de un lote por encima del tope en menos de 1 s (SC-003); 100 reentregas del
mismo mensaje producen exactamente 1 envío (SC-005); arranque en 30 s o menos (RNF-12)

**Constraints**: sin bloqueo de hilos en la ruta HTTP; actualizaciones atómicas con `findAndModify` para tomar
recursos compartidos; ack manual solo tras persistir; cero comentarios explicativos; secretos solo por entorno

**Scale/Scope**: 32 hallazgos comprometidos repartidos en 6 entregas; toca `core`, `infrastructure` y
documentación de 8 specs

## Constitution Check

*GATE: debe pasar antes de la investigación y se revisa tras el diseño.*

| Principio o restricción | Resultado | Notas |
|---|---|---|
| I. Hexagonal | Cumple | Los puertos nuevos (`reserveForDispatch`, `claimForRequeue`, `SubscriptionTicketPort`) viven en `core`; Mongo y RabbitMQ solo en `infrastructure`. `HexagonalArchitectureTest` sigue obligatorio |
| II. Contract-first | Cumple | La entrega de contrato (`maxItems`, `trackingSaved`, `subscribeTicket`, 409 y 500) precede a los controladores. Hay dos cambios incompatibles, señalados en el contrato |
| III. Cero comentarios | Cumple, y se refuerza | Se eliminan los restantes de `main` y se añade una prueba que los vigila |
| IV. Calidad verificada | Cumple | Cada corrección lleva una prueba que falla antes del cambio y un control positivo cuando verifica ausencia (FR-018). Cada entrega incluye un E2E contra el flujo que toca |
| V. Trazabilidad en git | Cumple | Una rama y un PR por entrega, commits de una línea, el razonamiento en el PR. El usuario mergea |
| VI. Spec-kit | Cumple | Este plan queda `Pendiente` hasta que el usuario lo apruebe |
| VII. Sin atajos | Cumple con excepciones registradas | Ver "Excepciones del Principio VII" |
| VIII. Confiabilidad | Mejora | La reserva atómica y el ack manual cierran la pérdida y la duplicación por reentrega; el riesgo residual del doble fallo está registrado |
| IX. Observabilidad | Mejora | Los errores hoy tragados pasan a registrarse con categoría y correlación, sin secretos ni mensajes internos |
| Ack manual y DLQ con causa | Cumple tras A-03 | El despacho pasa a ack manual tras persistir, con DLQ y causa |
| Bloqueo optimista y `findAndModify` | Cumple | La reserva, la liberación y el reclamo de reencolado son actualizaciones condicionadas atómicas |
| Estado consistente entre réplicas | Cumple | El ticket del panel vive en MongoDB, no en memoria por réplica |
| Configuración y secretos | Cumple tras A-01 | El secreto solo entra por entorno; sin valor por defecto público |
| Arranque en 30 s o menos | Cumple | La validación de propiedades es instantánea |

**Re-evaluación tras el diseño**: sin violaciones nuevas. La única complejidad añadida está justificada en
"Complexity Tracking".

## Entregas

Se recomienda una rama y un PR por entrega, en este orden. Cada una es verificable por separado y las dos
últimas dependen de las anteriores solo donde se indica.

| # | Entrega | Hallazgos | Depende de | E2E que la cierra |
|---|---|---|---|---|
| E1 | Arranque y autenticación seguros | A-01, A-02, M-01, M-02, M-04, M-05, M-29 | — | Arranque fallido sin secreto o con el de desarrollo; token sin `exp` -> 401; error del caso de uso -> 5xx y no 401; `/actuatorX` -> 401 |
| E2 | Ticket del panel en vivo | M-03 (FR-019) | E1 | Ticket válido abre el flujo; caducado, reutilizado o ausente -> 401; `access_token` rechazado |
| E3 | Lote y adjuntos | A-07, A-08, A-09, M-11, M-13, A-10 (restos) | — | Lote de 501 -> 400 sin efectos; reenvío del mismo `batchId`; guardado fallido -> `trackingSaved:false` con registro; `FAILED/SCAN_EXHAUSTED` inmediato |
| E4 | Despacho confiable | A-03, A-04, A-05, A-06, A-13, M-24, M-26 (y el modelo de M-25) | — | 100 reentregas = 1 envío; `save` fallido tras envío aceptado sin segundo envío; encolado fallido reintentado; proveedor deshabilitado conserva el comportamiento de 008/009/010 |
| E5 | Errores coherentes y cero comentarios | M-27, M-28 | — | 409, 409 y 500 genérico con correlación; prueba `NoExplanatoryCommentsTest` con control positivo |
| E6 | Reconciliación de artefactos | M-14, M-18, M-30, M-31, A-12, M-23, B-10, B-14 | E4 (para 018) | Búsqueda de `X-Tenant-Id` en las specs; quickstarts ejecutados con Bearer |

E4 es la más grande y la que desbloquea 013 y 018; conviene hacerla antes de implementar el reintento manual.
E6 puede hacerse en paralelo con las demás, salvo la parte de 018 que cita la regla del contador (D9) y el
ack manual.

## Decisiones que requieren confirmación del usuario

Confirmadas por el usuario el 2026-10-05 al aprobar el plan, sin cambios respecto de las propuestas, en
instrucción directa en el chat. Las cinco quedan vigentes, incluida la de la regla del contador del reintento
manual, que reemplaza la que dice el contrato de 018.

| # | Decisión | Propuesta | Alternativa principal |
|---|---|---|---|
| 1 | Ticket del panel en vivo (D4) | Ticket de 30 s, un solo uso, en MongoDB, y retirar `access_token` de `:subscribe` (cambio incompatible) | Mantener `access_token` con vida corta |
| 2 | Tope del lote (D5) | 500 ítems | 100 o 1000 |
| 3 | Regla del contador del reintento manual (D9) | Reinicia el ciclo y conserva el historial. **Contradice** lo que ya dice el contrato de 018 | Mantener que cuenta dentro del tope, lo que hace inútil el reintento manual tras agotar los automáticos |
| 4 | Fallo de guardado tras envío aceptado (D8) | Liberar a `RECOVERABLE` tras 10 minutos, aceptando un duplicado posible en el doble fallo | Esperar la intervención de un operador, lo que deja notificaciones sin camino |
| 5 | Perfil `local` para el secreto de desarrollo (D1) | El valor de desarrollo solo existe en `application-local.yml` | Requerir siempre el secreto, también en local, con un script que lo genere |

## Excepciones del Principio VII

Propuestas, con dueño y fecha. Se confirman al aprobar el plan.

| Excepción | Dueño | Fecha |
|---|---|---|
| Duplicado posible si el proveedor acepta, el guardado falla repetidamente y el barrido libera la notificación (D8) | Equipo de desarrollo del componente | 2026-11-30 |
| Hallazgos medios no comprometidos: M-06, M-08, M-09, M-10, M-15, M-16, M-17, M-20, M-21 | Equipo de desarrollo del componente | 2026-11-30 |
| Failover entre proveedores (M-25) | Equipo de desarrollo del componente | 2026-11-30 |

Los hallazgos bajos (B-01 a B-09 y B-15 a B-21) quedan como mejoras opcionales, sin fecha, como pide la spec.

## Riesgos

- **Pruebas existentes que fijan el comportamiento actual**: la que acepta el fallo silencioso del guardado del
  lote, la que usa `access_token` en el E2E de 015 y las que dependen del secreto por defecto. Hay que
  actualizarlas en la misma entrega que el cambio.
- **Migración de datos**: los campos nuevos de `Notification` son opcionales al leer y `pendingSince` usa
  `acceptedAt` como respaldo; no hace falta migración, pero sí un índice nuevo (`auto-index-creation` ya está
  activo).
- **Disabled providers**: la reserva debe liberarse cuando el proveedor lanza antes de producir un resultado,
  o se rompe el comportamiento descrito en 008, 009 y 010; el E2E de esas historias es la red de seguridad.
- **Cambio incompatible del panel en vivo**: afecta solo a clientes con `EventSource` nativo; el frontend
  actual no.
- **Tamaño de E4**: toca el servicio de despacho, el listener, el reencolador, el modelo y el adaptador Mongo.
  Se parte en tareas con pruebas primero y una verificación completa (`verify`) al final.

## Project Structure

### Documentation (this feature)

```text
specs/017-Fix-specs-012-016/
├── plan.md              # Este archivo
├── research.md          # Estado verificado de los hallazgos y decisiones
├── data-model.md        # Cambios de datos y puertos
├── quickstart.md        # Validación
├── contracts/
│   └── api-notificaciones-cambios.md
├── spec.md
├── informe.md
└── checklists/requirements.md
```

### Source Code (rutas que se tocan)

```text
core/src/main/java/co/edu/uco/notification/core/
├── domain/                 # Notification (campos nuevos), DeliveryAttempt (cycle), BatchLimits
├── port/in/                # BatchAcceptedResult.trackingSaved, IssueSubscriptionTicketUseCase
├── port/out/               # SubscriptionTicketPort
├── repository/             # NotificationRepository (reserve, release, claim), NotificationBatchRepository
└── usecase/                # DispatchNotificationService, RequeuePendingNotificationsService,
                            # SendNotificationBatchService, ScanAttachmentUploadService (restos)

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/in/web/         # AuthenticationWebFilter, RouteAuthorizationPolicy
├── adapter/in/rest/        # NotificationBatchController, NotificationExceptionHandler, nuevo endpoint de ticket
├── adapter/in/rabbit/      # NotificationDispatchListener (ack manual), AttachmentScanListener, componente común de ack
├── adapter/out/mongo/      # NotificationMongoAdapter, NotificationBatch*, SubscriptionTicket*
├── adapter/out/security/   # LocalJwtTokenValidationAdapter, PlatformJwtTokenValidationAdapter
├── config/                 # AuthJwtProperties, RabbitRetryConfig, SecurityConfig
└── resources/              # application.yml, application-local.yml, openapi/api-notificaciones.yaml

core/src/test/ e infrastructure/src/test/   # una clase de prueba por clase de producción, más los E2E de cada entrega
```

**Structure Decision**: se respeta la estructura hexagonal existente; no se crean módulos nuevos.

## Complexity Tracking

| Elemento | Por qué hace falta | Alternativa más simple descartada porque |
|---|---|---|
| Colección `subscription_tickets` | Un ticket de un solo uso debe ser consistente entre réplicas | Un almacén en memoria falla con más de una réplica; un JWT no puede ser de un solo uso |
| Componente común de ack manual con reintentos por republicación | El despacho adopta el patrón que ya usa el escaneo de adjuntos | Duplicar la lógica en los dos listeners dejaría dos copias a mantener |
| Campos `pendingSince`, `dispatchReservedAt` y `currentCycle` | Permiten reclamar huérfanos, detectar `IN_PROCESS` atascadas y reiniciar el conteo sin borrar el historial | Derivar todo de `deliveryAttempts` es la causa de A-06 y A-13 |
