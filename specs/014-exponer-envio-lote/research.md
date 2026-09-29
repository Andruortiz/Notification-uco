# Research: Enviar un lote de notificaciones por HTTP

## R1. ¿Controller nuevo o método en `NotificationController`?

- **Decision**: controller nuevo `NotificationBatchController` con la ruta completa
  `/notifications:sendBatch`.
- **Rationale**: es el precedente de `NotificationLiveUpdatesController` para una operación
  `recurso:accion`; evita agregar un cuarto caso de uso al constructor de `NotificationController` y un
  cuarto `@MockBean` a su prueba, y deja la ruta literal visible sin depender de cómo Spring combina
  `@RequestMapping("/notifications")` con un segmento que empieza por `:`.
- **Alternatives considered**: método en `NotificationController` con `@PostMapping(":sendBatch")`;
  descartado por lo anterior, sin beneficio adicional.

## R2. Errores estructurales de un elemento

- **Decision**: rechazan la solicitud completa con `400` antes de invocar el caso de uso.
- **Rationale**: ver `spec.md § Clarifications`. `NotificationExceptionHandler` ya traduce
  `IllegalArgumentException` a `400`, igual que en `POST /notifications`; los errores de negocio por
  elemento ya los convierte el caso de uso en `REJECTED`.
- **Alternatives considered**: rechazo individual en el adaptador y fusión con los resultados del caso
  de uso; descartado porque duplica en el adaptador la agregación, el orden y la generación del
  `batchId` que ya resuelve el núcleo, y no cubre elementos sin `externalId`.

## R3. Prioridad ausente

- **Decision**: `Preconditions.requireNonBlank(priority, ...)` antes de `Priority.valueOf`.
- **Rationale**: `Enum.valueOf(null)` lanza `NullPointerException`, que ningún handler traduce y
  terminaría en `500`. `requireNonBlank` lanza `IllegalArgumentException` -> `400`. Un valor fuera del
  enum ya lanza `IllegalArgumentException`.
- **Nota**: `POST /notifications` (`NotificationController.toCommand`) tiene el mismo patrón
  `Priority.valueOf(request.priority())` sin esa guarda; queda fuera de alcance y se reporta.

## R4. `batchId`

- **Decision**: `null` si el cliente no lo envía (el caso de uso genera uno con `BatchId.newId()`);
  `BatchId.of(valor)` si lo envía, que rechaza un valor en blanco con `IllegalArgumentException`.

## R5. Datos de prueba E2E

- **Decision**: usar el catálogo por defecto de `application.yml` (EMAIL con `simulated` como
  proveedor preferido, SMS con límite de 160 caracteres) y `SIMULATED_PROVIDER_RESULT` por defecto
  (`ACCEPTED`), de modo que los elementos aceptados lleguen a `DELIVERED`. Rechazo por negocio: canal
  `FAX` (inexistente) y un SMS de más de 160 caracteres. Cada prueba usa `externalId` y tenants
  propios para no depender del orden de ejecución ni vaciar la colección mientras el consumidor de
  despacho procesa mensajes de otra prueba.
