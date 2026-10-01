# Research: Logs estructurados con identificador de correlación (HU2-056)

Las cuatro decisiones son **del usuario**. Se documentan opciones, tradeoffs y una recomendación; ninguna
está resuelta hasta que el usuario responda.

## Decisión 1 — Formato de log estructurado (resuelta: opción A, JSON con `logstash-logback-encoder`)

Contexto: Spring Boot 3.3.4 no incluye logging estructurado (llega en 3.4). La constitución exige
"JSON o equivalente parseable por máquina, nunca texto plano libre". El patrón de texto actual
(`[cid=...]`) no cumple.

| Opción | Pros | Contras |
|---|---|---|
| A. JSON de una línea con `logstash-logback-encoder` (recomendada) | Estándar con Kubernetes y agregadores; campos MDC y argumentos estructurados nativos; maneja stack traces. | Una dependencia nueva solo en `infrastructure`. |
| B. Layout JSON propio sobre Logback | Sin dependencia nueva. | Código y pruebas propias; escape y excepciones a mano; mayor riesgo. |
| C. logfmt / `clave=valor` | Legible a simple vista. | Menos soportado por agregadores; escape frágil de valores con espacios. |
| D. Esperar a Boot 3.4 | Sin dependencia. | Rompe el alcance de la historia; es una actualización de plataforma no decidida. |

Recomendación: A.

## Decisión 2 — Propagación del identificador (resuelta: conservar lo implementado y transportar `traceparent`)

Estado: ya implementada de forma preliminar. Cabecera HTTP `X-Correlation-Id`, generada si falta o es
inválida; contexto Reactor puenteado a MDC con `context-propagation`; cabecera AMQP
`x-correlation-id`; persistencia con la notificación.

| Opción | Pros | Contras |
|---|---|---|
| A. Conservar lo implementado (recomendada) | Ya existe y está probado sin Docker; sin dependencias de plataforma. | Debe verificarse contra el contexto completo, Mongo y Rabbit. |
| B. Adoptar un estándar de trazado distribuido (W3C `traceparent` / Micrometer Tracing) | Interoperable con APM. | Dependencia y alcance mayores; no hay un colector decidido. |

Riesgos señalados: `Hooks.enableAutomaticContextPropagation()` es global y aplica a todo el proceso;
`.block()` en los listeners exige restaurar el MDC manualmente (ya hecho); `flatMap` no conserva orden,
no relevante para el identificador pero sí para el orden de logs en lote.

Recomendación: A. Fuera de alcance: reenviar el id a proveedores.

## Decisión 3 — Alcance de `LogSanitizer` (resuelta: destinatario enmascarado; contenido y credenciales nunca)

Hoy hay tres sanitizaciones duplicadas (`AttachmentLogFormatter.safe`, `AuthenticationLogFormatter.safe`,
máscaras de Twilio y FCM) y los logs de proveedor ya imprimen destinatario enmascarado.

| Elemento | Opción recomendada | Alternativas |
|---|---|---|
| Dirección del destinatario | Enmascarada (forma consistente por canal) | Omitida; hash |
| Contenido del mensaje | Nunca se registra | Registrar solo tamaño |
| Credenciales, tokens, secretos | Nunca se registran; redacción también en excepciones | — |
| Valores externos (externalId, cabeceras) | Neutralización de caracteres de control | Longitud máxima |

Recomendación: la columna recomendada. Hay que decidir además si el hash del destinatario sirve para
correlacionar sin exponer (útil, pero un hash de un teléfono es reversible por fuerza bruta).

## Decisión 4 — Política de niveles (resuelta: INFO éxitos, WARN rechazos 401/403 y fallos recuperables, ERROR fallos de sistema y permanentes)

Provisional: INFO aceptación y cambios de estado; DEBUG detalle interno; WARN fallos recuperables;
ERROR fallos permanentes o de infraestructura no recuperada. Pregunta abierta: un rechazo
`401/403` hoy es WARN (autenticación interina); se mantiene salvo indicación.

## Decisiones técnicas internas (derivadas, sujetas a aprobación del plan)

- **Eventos con identificadores**: añadir `correlationId` y `tenantId` a `DomainEvent`. Alternativa:
  loguear en los casos de uso de `core` (descartada: Principio I). Alternativa: enriquecer en el
  publicador consultando la notificación (descartada: lectura extra por evento). Efecto: cambia 7
  records y sus pruebas; el consumidor de actualizaciones en vivo debe seguir leyendo mensajes
  (probar con el productor real, ver trampas del repositorio).
- **Categoría de fallo**: `FailureCategory` mínima en lugar de un catálogo `ErrorCode`; el catálogo
  numérico no tiene consumidor hoy.
- **Excepciones en logs**: converter de Logback que sanitiza el stack trace; los mensajes de
  excepción de proveedor se tratan como no confiables.
- **Rendimiento**: appender asíncrono para no bloquear hilos reactivos; la medición de SC no se fija
  como umbral numérico en la spec; se revisa en la prueba de carga de la fase de rendimiento.
