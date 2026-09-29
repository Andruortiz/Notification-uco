# Cambios al contrato público: `api-notificaciones.yaml`

Se aplican a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` **antes** de tocar
el controlador (Principio II). Con la respuesta recomendada de Q1 (referencia https). Ninguna operación
nueva; se añade un esquema, un campo opcional y se amplían descripciones. Los clientes actuales no
cambian: `attachments` es opcional.

## `SendNotificationRequest` — campo nuevo `attachments`

Se añade a `properties` (no a `required`). Como `SendNotificationBatchRequest.items` referencia este
esquema, el lote lo hereda sin cambios propios.

```yaml
        attachments:
          type: array
          nullable: true
          maxItems: 5
          description: >
            Opcional. Archivos que acompañan la notificación, en el orden en que se entregarán. El canal
            debe declararlos en su forma de contenido (ver GET /channels, campo contentSchema, propiedad
            attachments); un canal que no los declara rechaza toda notificación con adjuntos. Con la
            configuración por defecto ningún canal los declara. El servicio no descarga ni guarda el
            archivo: guarda la dirección y la entrega al proveedor en el despacho. El tamaño y el tipo son
            los declarados; un archivo que no coincida lo rechazará el proveedor. Una notificación con un
            adjunto inválido se rechaza completa con 400; nunca se acepta sin el adjunto. Si el proveedor
            que la despacha no sabe enviar adjuntos, termina en FAILED sin enviarse.
          items:
            $ref: '#/components/schemas/Attachment'
```

## `Attachment` — esquema nuevo

```yaml
    Attachment:
      type: object
      required: [fileName, contentType, sizeBytes, url]
      properties:
        fileName:
          type: string
          minLength: 1
          maxLength: 255
          description: Nombre con el que lo verá el destinatario. Sin separadores de ruta ni caracteres de control; distinto de "." y "..".
          example: factura-0042.pdf
        contentType:
          type: string
          description: >
            Tipo de medio. Se compara en minúsculas y sin parámetros. Tipos admitidos por el servicio:
            application/pdf, image/png, image/jpeg, image/gif, image/webp, text/plain, text/csv,
            application/vnd.openxmlformats-officedocument.wordprocessingml.document,
            application/vnd.openxmlformats-officedocument.spreadsheetml.sheet. Cada canal puede admitir
            menos.
          example: application/pdf
        sizeBytes:
          type: integer
          format: int64
          minimum: 1
          maximum: 10485760
          description: Tamaño declarado en bytes. Tope del servicio 10 MiB; cada canal puede fijar menos. Límite inclusivo.
          example: 183422
        url:
          type: string
          format: uri
          maxLength: 2048
          pattern: '^[Hh][Tt][Tt][Pp][Ss]://'
          description: >
            Dirección https donde el cliente aloja el archivo, sin usuario ni contraseña. Debe seguir
            accesible durante los reintentos. Se trata como dato sensible: nunca aparece en respuestas,
            registros ni eventos del servicio.
          example: https://files.example.com/invoices/0042.pdf?token=abc
```

## `POST /notifications` — respuesta `400`

```yaml
        '400':
          description: >
            El canal no existe/está deshabilitado, el contenido no cumple el esquema del canal, o un adjunto
            es inválido: el canal no acepta adjuntos, o el adjunto incumple un tope del servicio o una regla
            del canal. El mensaje identifica el adjunto por su posición (attachments[i]) y la regla
            incumplida; nunca incluye la dirección del archivo.
```

## `POST /notifications:sendBatch` — descripción

Se añade al final de la descripción existente:

```yaml
        Un elemento con un adjunto inválido se rechaza solo, con su motivo; los demás siguen.
```

## `ChannelItem.contentSchema` y `RegisterChannelRequest.contentSchema` — descripción

```yaml
          description: >
            Forma de contenido (JSON Schema como texto) tal como está guardada. Nulo si el canal no declara
            ninguna. Además del largo de subject y body, puede declarar adjuntos con una propiedad
            "attachments" de tipo arreglo: maxItems (cantidad), items.properties.contentType.enum (tipos,
            en minúsculas) e items.properties.sizeBytes.maximum (bytes). Un canal sin esa propiedad no
            acepta adjuntos.
```

(`RegisterChannelRequest` conserva su primera frase propia y añade la misma explicación de adjuntos.)
