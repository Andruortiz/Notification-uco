# Cambios en el contrato público — `api-notificaciones.yaml`

**Feature**: 009-twilio-proveedor-sms | **Date**: 2026-09-24

Archivo: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`.

**Sin cambios estructurales**: ningún endpoint, esquema, campo, tipo, `required` ni código de respuesta se
agrega, quita o modifica. El `400` de `POST /notifications` ya documenta "el contenido no cumple el
esquema del canal", que es exactamente el rechazo de un SMS demasiado largo, y el lote ya devuelve ese
caso como ítem rechazado.

Se actualizan **solo descripciones** de `components.schemas.SendNotificationRequest` (y, por coherencia,
las mismas propiedades del ítem de lote si repiten el texto), antes de escribir código (Principio II),
porque con esta historia el texto actual queda falso o incompleto:

| Propiedad | Descripción actual | Descripción nueva (sentido) |
|---|---|---|
| `channelType` | "Canal registrado en el catálogo (hoy solo EMAIL está configurado)." | Canal registrado en el catálogo; la configuración por defecto declara EMAIL y SMS. |
| `recipientAddress` | "Dirección concreta del canal usado en este envío (email, teléfono, etc.)." | Añade: en SMS, número en formato internacional (`+`, código de país y número); un número sin ese formato termina en `FAILED` sin enviarse. Ejemplo adicional de SMS. |
| `subject` | "Opcional -- algunos canales (ej. SMS) no lo usan." | Añade: en SMS se admite y se ignora; no se envía ni se antepone al cuerpo. |
| `body` | (sin descripción) | La longitud máxima la fija la forma de contenido del canal; en SMS, 160 caracteres por defecto. Un cuerpo más largo se rechaza con `400`; nunca se trunca. |

La redacción exacta se decide al editar el archivo, respetando el estilo de las descripciones vecinas.
