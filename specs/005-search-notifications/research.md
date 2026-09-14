# Phase 0 Research: Buscar notificaciones por filtros

## Decisión 1 — Mecanismo de paginación: offset/limit, no cursor

**Decision**: Parámetros de consulta `limit` (tamaño de página) y `offset` (posición inicial), sobre
una consulta MongoDB ordenada por `acceptedAt` descendente. La respuesta incluye `items`, `limit`,
`offset` y `hasNext` (booleano) — este último calculado pidiendo `limit + 1` documentos y recortando
a `limit` en el adaptador, sin necesidad de un `count()` adicional.

**Rationale**:
- La clarificación de la especificación pidió "paginación obligatoria (`limit`/`offset` o
  equivalente)" sin exigir un cursor — offset/limit es el mecanismo más simple que cumple el
  requisito y es el que cualquier herramienta de búsqueda de operador espera (saltar a la página N).
- `hasNext` vía "pedir uno de más" evita una consulta `count()` separada (que en Mongo, sobre una
  colección filtrada dinámicamente, es tan cara como la búsqueda misma) — es el patrón estándar para
  paginación "siguiente/anterior" sin necesitar el total exacto de resultados.
- Un cursor opaco (basado en el último `acceptedAt`/`_id` visto) sería más robusto ante inserciones
  concurrentes entre páginas, pero es complejidad que esta historia no necesita: es una herramienta de
  auditoría para un operador humano, no un feed en tiempo real ni una API pública de alto volumen.

**Alternatives considered**:
- **Cursor opaco (`acceptedAt`+`_id` codificado)**: descartado por complejidad innecesaria para el
  caso de uso — se puede migrar a esto después sin romper el contrato si offset/limit resulta
  insuficiente (se agregaría un parámetro `cursor` opcional que compita con `offset`).
- **Sin paginación, con un tope duro fijo (ej. 1000)**: era la Opción B de la clarificación —
  descartada explícitamente por el usuario a favor de paginación real (Opción A).

## Decisión 2 — Valores por defecto y límites de `limit`

**Decision**: `limit` por defecto = 50, máximo permitido = 200. Un `limit` fuera de `[1, 200]` o un
`offset` negativo se rechaza con `400 Bad Request` (FR-010), igual que un rango de fechas inválido
(FR-008).

**Rationale**: 50 es un tamaño de página razonable para una tabla de operador (visible sin scroll
excesivo); 200 como tope evita que un cliente mal configurado fuerce una consulta desproporcionada
incluso con el índice nuevo. Ambos son valores de arranque documentados aquí, no un RNF con un número
ya fijado — se ajustan si el uso real lo pide, sin necesitar otra historia (son constantes de
aplicación, no un contrato nuevo).

**Alternatives considered**: Sin máximo — descartado, contradice directamente el objetivo de FR-007
(nunca una respuesta sin límite), que es precisamente lo que la paginación obligatoria vino a evitar.

## Decisión 3 — Índice compuesto nuevo: `(tenantId, acceptedAt)`

**Decision**: Agregar un `@CompoundIndex` nuevo en `NotificationDocument` sobre
`{tenantId: 1, acceptedAt: -1}`, construido como parte de esta historia (no diferido).

**Rationale**:
- El índice único existente (`tenantId, externalId`) no sirve para esta consulta — MongoDB solo
  puede usar el prefijo de un índice compuesto para filtrar (`tenantId` sí, `externalId` no, porque
  esta consulta no lo usa), y no puede usarlo en absoluto para ordenar por `acceptedAt` descendente.
- Sin un índice que cubra `tenantId` + `acceptedAt`, cualquier búsqueda (con o sin filtros
  adicionales) degrada a un escaneo completo de la colección del tenant, ordenado en memoria —
  inaceptable para SC-003 (2 s con hasta 10 000 notificaciones) y exactamente el tipo de promesa sin
  respaldo que el Principio VII prohíbe dejar pasar.
- Los filtros adicionales (`recipientId`, `channelType`, `status`) no tienen su propio índice
  dedicado en esta historia — se aplican como filtros adicionales sobre el resultado ya acotado por
  `tenantId` + rango de `acceptedAt` del índice compuesto. Es una decisión de alcance: son filtros de
  igualdad sobre un conjunto ya reducido por tenant, no el cuello de botella principal a este volumen
  (10 000 documentos por tenant). Si el uso real muestra lo contrario, se agregan índices adicionales
  en una historia de rendimiento separada, no aquí.

**Alternatives considered**:
- **Índice compuesto de 5 campos** (`tenantId, recipientId, channelType, status, acceptedAt`):
  descartado — MongoDB no puede usar eficientemente un índice así cuando la mayoría de las búsquedas
  reales solo van a usar 1-2 filtros además de `tenantId`; un índice tan ancho también penaliza cada
  escritura (`save`) sin necesidad.
- **Sin índice nuevo, aceptar el escaneo por ahora**: descartado explícitamente — ver Principio VII
  en Constitution Check.

## Decisión 4 — Forma del resultado: reutilizar `Notification`/`DeliveryAttempt`, sin entidad nueva

**Decision**: El caso de uso mapea directamente desde el agregado `Notification` ya existente (via
`NotificationRepository`) a una vista `NotificationSearchResult` (un registro por notificación con su
historial completo de `DeliveryAttempt`) — no se crea ninguna entidad de dominio nueva ni un modelo de
lectura materializado aparte.

**Rationale**: `Notification.deliveryAttempts()` ya expone la lista completa de intentos — es
exactamente el dato que FR-004 pide mostrar. Crear una proyección/modelo de lectura separado
(ej. un "read model" desnormalizado) sería una optimización prematura sin evidencia de que el mapeo
directo no cumple SC-003 una vez que existe el índice de la Decisión 3.

**Alternatives considered**: Modelo de lectura desnormalizado, actualizado por evento — descartado por
prematuro; esta historia es la primera vez que se necesita leer varias notificaciones a la vez, no hay
todavía evidencia de que la lectura directa del agregado sea insuficiente.

## Notas de implementación (post-`/speckit-implement`)

1. **Los DTOs de respuesta con `List` necesitaron copia defensiva.** SpotBugs marcó
   `NotificationHistoryItemResponse.deliveryAttempts` y `NotificationSearchResponse.items` como
   `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` — exponían la lista mutable recibida sin copiarla. Se corrigió con
   un constructor compacto (`List.copyOf(...)`) en ambos records, el mismo patrón que
   `NotificationSearchResult`/`NotificationSearchPage` ya usaban en `core`. No cambia el diseño
   descrito arriba, solo cierra un hallazgo real de la puerta de análisis estático.
2. **El E2E test persiste datos directamente por el repositorio, no vía `POST /notifications`.** El
   flujo observable de esta historia es "buscar", no "aceptar" — pasar por el endpoint de aceptación
   arrastraría RabbitMQ (publicación del mensaje de despacho) sin aportar nada a lo que esta historia
   prueba. `NotificationControllerSearchE2ETest` persiste notificaciones con
   `NotificationRepository.save(...)` directamente y ejerce solo el camino HTTP → controller → caso de
   uso → Mongo real de la búsqueda, incluyendo un caso con historial de intentos real (no vacío) para
   que `DeliveryAttemptResponse` quede genuinamente cubierto, no solo de forma cosmética.
