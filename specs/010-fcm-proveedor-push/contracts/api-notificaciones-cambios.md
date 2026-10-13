# Cambios en el contrato público — `api-notificaciones.yaml`

**Feature**: 010-fcm-proveedor-push | **Date**: 2026-09-24

Archivo: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`.

**Sin cambios estructurales**: ningún endpoint, esquema, campo, tipo, `required` ni código de respuesta se
agrega, quita o modifica. El `400` de `POST /notifications` ya documenta "el contenido no cumple el
esquema del canal", que cubre el rechazo de un push con asunto o cuerpo demasiado largo, y el lote ya
devuelve ese caso como ítem rechazado.

Se actualizan **solo descripciones** de `components.schemas.SendNotificationRequest`, antes de escribir
código (Principio II). El punto de partida es el texto que deja HU2-090 (que ya menciona EMAIL y SMS); si
la rama se rebasa sobre una versión distinta, se ajusta sobre el texto vigente con el mismo sentido.

| Propiedad | Descripción nueva (sentido) |
|---|---|
| `channelType` | Canal registrado en el catálogo; la configuración por defecto declara EMAIL, SMS y PUSH. |
| `recipientAddress` | Añade: en PUSH, el identificador de dispositivo que el proveedor de push emitió a la aplicación cliente; se trata como texto opaco y no se valida su forma. Un identificador inválido o caducado termina en `FAILED` sin reintentos. Ejemplo adicional de PUSH con un valor evidentemente inventado. |
| `subject` | Añade: en PUSH es el título visible del push, opcional, hasta 100 caracteres. |
| `body` | Añade: en PUSH, hasta 900 caracteres. Un contenido más largo se rechaza con `400`; nunca se trunca. |

La redacción exacta se decide al editar el archivo, respetando el estilo de las descripciones vecinas.
