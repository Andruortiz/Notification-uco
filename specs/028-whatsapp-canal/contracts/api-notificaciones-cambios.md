# Cambios al contrato público: `api-notificaciones.yaml`

**Feature**: 028-whatsapp-canal | Principio II: estos cambios se editan en el YAML **antes** que el código.

Ningún endpoint nuevo ni cambia ninguna ruta. El envío en lote reutiliza `SendNotificationRequest`, así que
hereda los cambios. No hay cambios en las rutas públicas del PEP.

## `SendNotificationRequest` (componente)

1. **`required`**: se quita `body`. Ahora el cuerpo y la plantilla son excluyentes y exactamente uno es
   obligatorio; OpenAPI 3.0 no lo expresa de forma limpia sin `oneOf`, así que se documenta en la
   descripción de ambos campos y la regla la aplica el servicio con `400`.
2. **`body`**: la descripción añade "Obligatorio salvo que se envíe `template`; no puede enviarse junto con
   `template`" y que en WHATSAPP el límite por defecto es 1600 caracteres.
3. **Campo nuevo `template`** (opcional, `nullable: true`):

```yaml
template:
  type: object
  nullable: true
  required: [templateId]
  description: >
    Opcional. Envía una plantilla aprobada por el proveedor en lugar de un cuerpo libre; excluyente con
    body. El canal debe declararla en su forma de contenido (GET /channels, contentSchema, propiedad
    template); con la configuración por defecto solo WHATSAPP lo hace y cualquier otro canal rechaza la
    notificación con 400. Fuera de la ventana de 24 h de WhatsApp solo se admiten plantillas; un texto libre
    que el proveedor rechaza por estar fuera de la ventana termina en FAILED sin reintentos. El servicio
    valida la forma (referencia presente, a lo sumo 20 variables de hasta 1024 caracteres); que la plantilla
    exista y esté aprobada, y que no falte ninguna de sus variables, lo decide el proveedor al enviar y un
    rechazo termina en FAILED.
  properties:
    templateId:
      type: string
      maxLength: 64
      description: Identificador de la plantilla aprobada ante el proveedor.
      example: HXb5b62575e6e4ff6129ad7c8efe1f983e
    variables:
      type: object
      additionalProperties:
        type: string
        maxLength: 1024
      description: Valores de las variables de la plantilla, por nombre.
      example: { "1": "Alice", "2": "42" }
```

4. **`channelType`**: la descripción pasa a "La configuración por defecto declara EMAIL, SMS, PUSH y
   WHATSAPP".
5. **`recipientAddress`**: se añade "En WHATSAPP es un número en formato internacional (ej.
   +573001234567); un número sin ese formato, o sin cuenta de WhatsApp, termina en FAILED sin
   reintentos".
6. **`subject`**: se añade "En WHATSAPP se admite y se ignora".
7. **`attachments`**: se añade que WHATSAPP no los declara con la configuración por defecto.

## `contentSchema` en `GET /channels`

Sin cambio de estructura: el canal `WHATSAPP` aparece con el esquema de `data-model.md`.

## Respuestas

Sin cambios de estructura. `400` (`InvalidContent`) ya cubre: cuerpo mayor al límite, cuerpo y plantilla
juntos, ninguno de los dos, adjunto en canal que no los declara, plantilla en canal que no la declara y
variables fuera de la forma declarada.
