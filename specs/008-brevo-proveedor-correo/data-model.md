# Phase 1 — Data model: Integrar Brevo como primer proveedor real de correo

**Feature**: 008-brevo-proveedor-correo | **Date**: 2026-09-21

## Resumen

**Cero cambios en el modelo persistido.** No hay colección, documento, campo ni índice nuevo. No hay
migración de datos. Las estructuras que esta historia introduce son de transporte (cuerpo de la petición
al proveedor) y de configuración, viven en `infrastructure` y no se guardan en ninguna parte.

---

## Entidades de dominio existentes que se leen (sin modificar)

### `Notification` (`core/domain/Notification.java`)

| Campo leído | Uso en esta historia |
|---|---|
| `notificationId()` | Clave de idempotencia de la petición y correlación en los registros. |
| `tenantId()` | Correlación en los registros. No viaja al proveedor. |
| `recipient().address()` | Destinatario del correo. |
| `content().subject()` | Asunto. **Puede ser `null` o vacío** → ver `NotificationContent`. |
| `content().body()` | Cuerpo del correo, como texto plano. |

No se leen `externalId`, `recipientId`, `priority`, `acceptedAt`, `status`, `deliveryAttempts` ni
`version`; y ninguno viaja al proveedor.

### `NotificationContent` (`core/domain/valueobject/NotificationContent.java`)

```java
public record NotificationContent(String subject, String body)
```

- `body`: obligatorio y no vacío, invariante del propio record.
- `subject`: **opcional**. `NotificationContent.of(body)` lo deja en `null`, y el contrato público
  (`api-notificaciones.yaml`) lo declara `nullable: true` y fuera de `required`.
- Longitud total (`subject + body`) acotada a 32 768 caracteres por el propio record.

Esa opcionalidad es la que obliga a definir FR-012 / Q1 (ver `research.md`, Decisión 6).

### `AttemptResult` (`core/domain/valueobject/AttemptResult.java`)

Enumeración existente con exactamente los tres valores que el adaptador debe producir: `ACCEPTED`,
`RECOVERABLE_FAILURE`, `PERMANENT_FAILURE`. **No se amplía.**

### `ProviderId` (`core/domain/valueobject/ProviderId.java`)

`record ProviderId(String value)`. El adaptador declara la constante `ProviderId.of("brevo")`, igual
que el simulado declara `simulated` (decisión ya tomada en HU2-088: el `providerId` es una constante del
adaptador, no configuración de entorno).

---

## Elementos nuevos

### `ProviderDisabledException` (`core`, paquete `core.exception`) — NUEVO

```text
ProviderDisabledException extends RuntimeException
  - providerId : ProviderId
  - reason     : String   (motivo fijo, sin valores de credencial)
```

Hermana de `ProviderNotAvailableException`. Java puro, sin Spring — cumple el Principio I. Se lanza como
señal de error del `Mono` que devuelve `send(...)` cuando el adaptador está deshabilitado. El mensaje
nombra el `providerId` y el motivo; ese mensaje es el que termina en el header de causa de la DLQ.

**Invariantes**: `providerId` no nulo; `reason` no vacío. El `reason` es uno de un conjunto cerrado de
constantes del adaptador (qué variable de entorno falta), nunca texto derivado de un valor secreto.

### Propiedades de configuración del proveedor (`infrastructure`) — NUEVO

`BrevoProviderProperties`, `@ConfigurationProperties(prefix = "notification.provider.brevo")`:

| Propiedad | Variable de entorno | Por defecto | Sensible |
|---|---|---|---|
| `api-key` | `BREVO_API_KEY` | *(vacío → deshabilitado)* | **Sí** |
| `sender-email` | `BREVO_SENDER_EMAIL` | *(vacío → deshabilitado)* | No, pero no se registra |
| `sender-name` | `BREVO_SENDER_NAME` | *(vacío → se omite del envío)* | No |
| `base-url` | `BREVO_BASE_URL` | `https://api.brevo.com` | No |
| `timeout-ms` | `BREVO_TIMEOUT_MS` | `10000` | No |
| `connect-timeout-ms` | `BREVO_CONNECT_TIMEOUT_MS` | `5000` | No |

Ninguna lleva valor real en la configuración versionada: las dos primeras quedan sin valor por defecto,
de modo que un despliegue sin variables de entorno arranca con el proveedor deshabilitado (FR-004).

**Regla derivada**: `enabled = api-key no vacía && sender-email no vacío`. Se evalúa una sola vez, al
construirse el adaptador.

### Estructuras de transporte hacia el proveedor (`infrastructure`) — NUEVO, no persistidas

```text
BrevoEmailRequest
  - sender      : BrevoContact          { email, name? }
  - to          : List<BrevoContact>    (exactamente un elemento)
  - subject     : String                (no vacío, garantizado antes de construirlo)
  - textContent : String
  - headers     : Map<String, String>   { "Idempotency-Key": <notificationId> }
```

Serialización JSON con el `ObjectMapper` ya disponible en el contexto. Los campos nulos se omiten
(`sender.name` cuando no está configurado).

No se define una estructura para la respuesta: la clasificación solo necesita el código de estado y el
tipo de excepción (ver `research.md`, Decisión 4), y **deliberadamente no se parsea el cuerpo de la
respuesta de error** para no arrastrar texto del proveedor hacia los registros.

---

## Configuración del catálogo (dato sembrado, no esquema)

El documento `channel_catalog` del canal `EMAIL` pasa de `providers: ["simulated"]` a
`providers: ["simulated", "brevo"]` por defecto, con la lista configurable por entorno. La **forma** del
documento (`ChannelCatalogDocument(channelType, providers, contentSchema)`) no cambia.

Limitación conocida: la siembra solo actúa sobre un catálogo vacío, así que en entornos ya sembrados ese
documento se actualiza a mano (ver `research.md`, Decisión 3, y `quickstart.md`).

---

## Transiciones de estado

Ninguna nueva. Las respuestas del proveedor entran por las transiciones existentes de
`StatusTransitionPolicy` a través de `DispatchNotificationService`:

| Categoría del adaptador | Efecto ya existente |
|---|---|
| `ACCEPTED` | `IN_PROCESS → DELIVERED`, intento `ACCEPTED` con `providerId = brevo`. |
| `RECOVERABLE_FAILURE` | `IN_PROCESS → RECOVERABLE` (o `FAILED` si la política de reintentos se agota), intento `RECOVERABLE_FAILURE`. |
| `PERMANENT_FAILURE` | `IN_PROCESS → FAILED`, intento `PERMANENT_FAILURE`. |
| Señal de error (`ProviderDisabledException`) | **Ninguna**: no se persiste nada, la notificación sigue `PENDING` y sin intentos; el mensaje va a la DLQ con la causa. |
