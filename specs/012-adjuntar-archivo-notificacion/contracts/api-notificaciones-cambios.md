# Cambios al contrato público: `api-notificaciones.yaml` (plan v3)

Se aplican a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` **antes** de tocar
cualquier controlador (Principio II). Parten del contrato tal como quedó tras la v1 (commit `8c45a4d`:
campo `attachments`, esquema `Attachment` con `url` obligatoria, `400` de `POST /notifications`, frase del
lote y descripciones de `contentSchema`). Los clientes actuales sin adjuntos no cambian: `attachments` sigue
siendo opcional.

Resumen:

| Elemento | Cambio |
|---|---|
| Etiqueta `Adjuntos` | nueva |
| `Attachment` | `content` XOR `url` (`oneOf`); lista blanca v3; descripción de `url` como subida emitida por el servicio |
| `SendNotificationRequest.attachments` | descripción reescrita |
| `POST /notifications` | `400` ampliado; `409`, `413` y `503` nuevos |
| `POST /notifications:sendBatch` | frase ampliada |
| `POST /attachment-uploads` | nueva (CRUD natural) |
| `POST /attachment-uploads/{uploadId}:complete` | nueva (acción de negocio) |
| `GET /attachment-uploads/{uploadId}` | nueva |
| `UploadId`, `IssueAttachmentUploadRequest`, `AttachmentUploadResponse`, `AttachmentUploadState` | nuevos |
| `ChannelItem.contentSchema`, `RegisterChannelRequest.contentSchema` | sin cambio desde la v1 |

## Etiqueta nueva

```yaml
  - name: Adjuntos
    description: >
      Subida de archivos de más de 1 MB al almacén del servicio para adjuntarlos a notificaciones.
      Los archivos de hasta 1 MB viajan embebidos en la propia notificación.
```

## `SendNotificationRequest.attachments` — descripción

```yaml
        attachments:
          type: array
          nullable: true
          maxItems: 5
          description: >
            Opcional. Archivos que acompañan la notificación, en el orden en que se entregarán. El canal
            debe declararlos en su forma de contenido (ver GET /channels, campo contentSchema, propiedad
            attachments); un canal que no los declara rechaza toda notificación con adjuntos. Con la
            configuración por defecto ningún canal los declara. Cada adjunto lleva exactamente uno de
            content (archivo de hasta 1 MB codificado en Base64) o url (archivo de más de 1 MB subido antes
            con POST /attachment-uploads y en estado CLEAN). A lo sumo 5 adjuntos y 25 MB (26214400 bytes)
            en total. El servicio verifica el tipo real de cada archivo y lo analiza con un antivirus. Una
            notificación con un adjunto inválido se rechaza completa; nunca se acepta sin el adjunto. Si el
            proveedor que la despacha no sabe enviar adjuntos, termina en FAILED sin enviarse.
          items:
            $ref: '#/components/schemas/Attachment'
```

## `Attachment` — esquema reescrito

```yaml
    Attachment:
      type: object
      required: [fileName, contentType, sizeBytes]
      oneOf:
        - required: [content]
          not:
            required: [url]
        - required: [url]
          not:
            required: [content]
      properties:
        fileName:
          type: string
          minLength: 1
          maxLength: 255
          description: >
            Nombre con el que lo verá el destinatario. Sin separadores de ruta ni caracteres de control;
            distinto de "." y "..". No puede terminar en una extensión prohibida (sin distinguir
            mayúsculas, ignorando puntos y espacios finales): .exe .msi .bat .cmd .com .scr .pif .vbs .vbe
            .js .jse .wsf .wsh .ps1 .hta .cpl .msc .reg .lnk .jar .dll .sh .apk .app .gadget .com.pif .msix
            .war.
          example: factura-0042.pdf
        contentType:
          type: string
          description: >
            Tipo de medio. Se compara en minúsculas y sin parámetros, y debe coincidir con el tipo real
            del contenido. Tipos admitidos por el servicio: application/pdf, image/png, image/jpeg,
            text/plain, text/csv,
            application/vnd.openxmlformats-officedocument.wordprocessingml.document,
            application/vnd.openxmlformats-officedocument.spreadsheetml.sheet. Cada canal puede admitir
            menos.
          example: application/pdf
        sizeBytes:
          type: integer
          format: int64
          minimum: 1
          maximum: 10485760
          description: >
            Tamaño real en bytes. Hasta 1048576 va en content; de 1048577 a 10485760, en url. Con content
            debe ser igual al tamaño decodificado; con url, igual al de la subida. Cada canal puede fijar
            menos. Límite inclusivo.
          example: 183422
        content:
          type: string
          format: byte
          description: >
            Archivo de hasta 1 MB codificado en Base64 estándar (RFC 4648, con relleno), sin prefijo
            "data:" ni saltos de línea. Dato sensible: nunca aparece en respuestas, registros ni eventos.
        url:
          type: string
          format: uri
          maxLength: 2048
          description: >
            attachmentUrl devuelta por POST /attachment-uploads para el mismo tenant, cuya subida está en
            estado CLEAN. Cualquier otra dirección se rechaza; el servicio no la descarga. La parte de
            consulta se ignora y la dirección no se guarda.
```

## `POST /notifications` — respuestas

```yaml
        '400':
          description: >
            El canal no existe/está deshabilitado, el contenido no cumple el esquema del canal, o un adjunto
            es inválido: el canal no acepta adjuntos; el adjunto incumple un tope del servicio (cantidad,
            tamaño, tamaño total, tipo, extensión) o una regla del canal; lleva content y url a la vez o
            ninguno; content no es Base64 válido o no coincide con sizeBytes; url no es una subida de este
            tenant o sus datos no coinciden; la subida está INFECTED; el tipo real no coincide con el
            declarado; o el archivo contiene software malicioso. El mensaje identifica el adjunto por su
            posición (attachments[i]) y la regla incumplida; nunca incluye el contenido ni la dirección.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '409':
          description: >
            Un adjunto referencia una subida todavía en PENDING_SCAN. Reintentar cuando
            GET /attachment-uploads/{uploadId} devuelva CLEAN. Una subida en FAILED se rechaza con 400.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '413':
          description: El cuerpo de la solicitud supera 8 MB.
        '503':
          description: >
            El antivirus o el almacén de archivos no están disponibles o no respondieron a tiempo. La
            notificación no se aceptó; puede reintentarse. Las notificaciones sin adjuntos no se ven
            afectadas.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
```

## `POST /notifications:sendBatch` — descripción

Reemplaza la frase añadida en la v1:

```yaml
        Un elemento con un adjunto inválido, o que referencia una subida todavía en PENDING_SCAN, se
        rechaza solo, con su motivo; los demás siguen.
```

## Operaciones nuevas

```yaml
  /attachment-uploads:
    post:
      tags: [Adjuntos]
      summary: Pedir una dirección de subida para un archivo grande
      description: >
        Emite una política de subida firmada (formulario multipart por POST) para subir al almacén del
        servicio un archivo de más de 1 MB y hasta 10 MB. La subida nace en PENDING_SCAN. El cliente envía un
        POST multipart a uploadUrl con todos los campos de uploadFields y el campo "file" al final, antes de
        expiresAt; el almacén rechaza un archivo de tamaño distinto de sizeBytes o de un tipo distinto de
        contentType. Después avisa con POST /attachment-uploads/{uploadId}:complete y referencia el archivo
        en la notificación con attachmentUrl. uploadFields es una credencial temporal: solo aparece en esta
        respuesta.
      operationId: issueAttachmentUpload
      parameters:
        - $ref: '#/components/parameters/TenantId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/IssueAttachmentUploadRequest'
      responses:
        '201':
          description: Subida emitida.
          headers:
            Location:
              schema:
                type: string
              description: /attachment-uploads/{uploadId}
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AttachmentUploadResponse'
        '400':
          description: >
            Nombre, tipo, extensión o tamaño inválidos, o tamaño de hasta 1 MB (ese archivo va embebido en
            la notificación).
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '503':
          description: El almacén de archivos no está disponible.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
  /attachment-uploads/{uploadId}:complete:
    post:
      tags: [Adjuntos]
      summary: Avisar que la subida terminó
      description: >
        Comprueba que el archivo está en el almacén y que su tamaño coincide con el declarado, y encola
        su análisis. La subida sigue en PENDING_SCAN hasta que el análisis termina en CLEAN, INFECTED o
        FAILED. Si la subida ya está resuelta, o si otra llamada simultánea ganó la transición, devuelve su
        estado vigente sin volver a analizar. Si la subida ya venció, responde 409 y pasa a FAILED.
      operationId: completeAttachmentUpload
      parameters:
        - $ref: '#/components/parameters/TenantId'
        - $ref: '#/components/parameters/UploadId'
      responses:
        '202':
          description: Análisis encolado, o subida ya resuelta (ver state).
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AttachmentUploadResponse'
        '400':
          description: >
            El tamaño del archivo subido no coincide con el declarado. El archivo se descarta y la subida
            sigue en PENDING_SCAN: puede volver a subirse mientras la política de subida esté vigente.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '404':
          description: La subida no existe para este tenant.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '409':
          description: >
            El archivo todavía no está en el almacén, o la subida venció (pasa a FAILED con motivo
            EXPIRED y debe pedirse una nueva).
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '503':
          description: El almacén de archivos o el broker no están disponibles.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
  /attachment-uploads/{uploadId}:
    get:
      tags: [Adjuntos]
      summary: Consultar el estado de una subida
      operationId: getAttachmentUpload
      parameters:
        - $ref: '#/components/parameters/TenantId'
        - $ref: '#/components/parameters/UploadId'
      responses:
        '200':
          description: Estado actual. Nunca incluye uploadUrl.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AttachmentUploadResponse'
        '404':
          description: La subida no existe para este tenant.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
```

## Parámetro y esquemas nuevos

```yaml
  parameters:
    UploadId:
      name: uploadId
      in: path
      required: true
      schema:
        type: string

  schemas:
    IssueAttachmentUploadRequest:
      type: object
      required: [fileName, contentType, sizeBytes]
      properties:
        fileName:
          type: string
          minLength: 1
          maxLength: 255
          description: Mismas reglas que Attachment.fileName.
        contentType:
          type: string
          description: Mismos tipos que Attachment.contentType.
        sizeBytes:
          type: integer
          format: int64
          minimum: 1048577
          maximum: 10485760
          description: Tamaño exacto del archivo que se subirá.
    AttachmentUploadState:
      type: string
      enum: [PENDING_SCAN, CLEAN, INFECTED, FAILED]
    AttachmentUploadResponse:
      type: object
      required: [uploadId, state, fileName, contentType, sizeBytes]
      properties:
        uploadId:
          type: string
        state:
          $ref: '#/components/schemas/AttachmentUploadState'
        fileName:
          type: string
        contentType:
          type: string
        sizeBytes:
          type: integer
          format: int64
        sha256:
          type: string
          nullable: true
          description: Huella SHA-256 en hexadecimal, desde que termina el análisis.
        rejectionReason:
          type: string
          nullable: true
          enum: [MALWARE, CONTENT_TYPE_MISMATCH, null]
          description: Solo en INFECTED.
        uploadUrl:
          type: string
          format: uri
          nullable: true
          description: Solo en la respuesta de POST /attachment-uploads. Credencial temporal.
        expiresAt:
          type: string
          format: date-time
          nullable: true
          description: Vencimiento de la política de subida. Solo en la respuesta de POST /attachment-uploads.
```

## Enmienda 3.1 (2026-10-03)

- La respuesta de POST /attachment-uploads devuelve uploadUrl (destino del formulario), uploadFields (campos firmados, credencial temporal) y attachmentUrl (referencia para Attachment.url, sin firma).
- AttachmentUploadState gana FAILED; rejectionReason gana OBJECT_MISSING, SIZE_MISMATCH, SCAN_EXHAUSTED y EXPIRED.
- :complete responde 409 si la subida venció (pasa a FAILED).
