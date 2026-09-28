# Cambios al contrato público: `api-notificaciones.yaml`

Se aplican a `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml` **antes** de
escribir el controlador (Principio II). Nada existente cambia de forma; se añaden dos operaciones, seis
esquemas y se actualiza la descripción del tag `Catálogo`.

## Tag `Catálogo`

```yaml
  - name: Catálogo
    description: >
      CU-07, CU-08. La consulta (GET /channels, GET /providers) está disponible; el registro
      (/channels:register, /providers:register) sigue bloqueado.
```

## `GET /channels`

Se añade a un path nuevo `/channels` (el path `/channels:register` existente no cambia).

```yaml
  /channels:
    get:
      tags: [Catálogo]
      summary: Consultar los canales del catálogo y cómo se enrutan
      description: >
        CU-07 (lectura). Devuelve los canales que el enrutamiento está usando en este momento, cada uno
        con sus proveedores en orden de preferencia y el estado de cada proveedor. Hoy el despacho usa
        solo el proveedor en la posición 1; los demás se listan en su orden pero no se usan como
        respaldo automático. El catálogo es del despliegue, no del tenant: la respuesta es la misma para
        cualquier X-Tenant-Id. Un cambio guardado en el catálogo aparece aquí en el mismo refresco en que
        el enrutamiento empieza a usarlo. Ningún campo contiene el valor de una credencial.
      operationId: listChannels
      parameters:
        - $ref: '#/components/parameters/TenantId'
      responses:
        '200':
          description: Canales ordenados por channelType (items vacío si el catálogo está vacío).
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ChannelCatalogResponse'
        '400':
          description: Falta el X-Tenant-Id o está vacío.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
```

## `GET /providers`

```yaml
  /providers:
    get:
      tags: [Catálogo]
      summary: Consultar los proveedores, su estado y dónde se usan
      description: >
        CU-08 (lectura). Devuelve la unión de los proveedores con adaptador en este despliegue y los que
        nombra el catálogo, cada uno con su estado y los canales en que aparece. MISSING_ADAPTER indica
        que el catálogo nombra un proveedor para el que el despliegue no tiene adaptador. El estado de
        habilitación es el de configuración de la réplica que responde, no la salud actual del
        proveedor. Misma respuesta para cualquier X-Tenant-Id; ningún campo contiene el valor de una
        credencial.
      operationId: listProviders
      parameters:
        - $ref: '#/components/parameters/TenantId'
      responses:
        '200':
          description: Proveedores ordenados por providerId.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ProviderCatalogResponse'
        '400':
          description: Falta el X-Tenant-Id o está vacío.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
```

## Esquemas nuevos (`components.schemas`)

```yaml
    ProviderStatus:
      type: string
      enum: [ENABLED, DISABLED, MISSING_ADAPTER]
      description: >
        ENABLED: tiene adaptador y su configuración está completa. DISABLED: tiene adaptador pero le falta
        o tiene mal formada una credencial (ver statusReason). MISSING_ADAPTER: el catálogo lo nombra pero
        el despliegue no tiene adaptador para él.

    ChannelCatalogResponse:
      type: object
      required: [items]
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/ChannelItem'

    ChannelItem:
      type: object
      required: [channelType, contentSchema, providers]
      properties:
        channelType:
          type: string
          example: EMAIL
        contentSchema:
          type: string
          nullable: true
          description: Forma de contenido (JSON Schema como texto) tal como está guardada. Nulo si el canal no declara ninguna.
        providers:
          type: array
          minItems: 1
          description: En orden de preferencia.
          items:
            $ref: '#/components/schemas/ChannelProviderItem'

    ChannelProviderItem:
      type: object
      required: [providerId, preferenceOrder, status, statusReason]
      properties:
        providerId:
          type: string
          example: simulated
        preferenceOrder:
          type: integer
          minimum: 1
          description: 1 = preferente, el único que usa hoy el despacho.
        status:
          $ref: '#/components/schemas/ProviderStatus'
        statusReason:
          type: string
          nullable: true
          description: Nulo cuando status es ENABLED. Nombra la configuración ausente o mal formada, nunca su valor.
          example: missing notification.provider.twilio.account-sid (TWILIO_ACCOUNT_SID)

    ProviderCatalogResponse:
      type: object
      required: [items]
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/ProviderItem'

    ProviderItem:
      type: object
      required: [providerId, status, statusReason, channels]
      properties:
        providerId:
          type: string
          example: twilio
        status:
          $ref: '#/components/schemas/ProviderStatus'
        statusReason:
          type: string
          nullable: true
          description: Nulo cuando status es ENABLED. Nombra la configuración ausente o mal formada, nunca su valor.
        channels:
          type: array
          description: Canales que nombran a este proveedor, con su posición en cada uno. Vacío si ninguno.
          items:
            type: object
            required: [channelType, preferenceOrder]
            properties:
              channelType:
                type: string
              preferenceOrder:
                type: integer
                minimum: 1
```

## Ejemplo de respuesta con la configuración por defecto y sin credenciales reales

`GET /channels` (fragmento):

```json
{
  "items": [
    {
      "channelType": "EMAIL",
      "contentSchema": null,
      "providers": [
        { "providerId": "simulated", "preferenceOrder": 1, "status": "ENABLED", "statusReason": null },
        { "providerId": "brevo", "preferenceOrder": 2, "status": "DISABLED",
          "statusReason": "missing notification.provider.brevo.api-key (BREVO_API_KEY)" }
      ]
    }
  ]
}
```

`GET /providers` (fragmento):

```json
{
  "items": [
    { "providerId": "brevo", "status": "DISABLED",
      "statusReason": "missing notification.provider.brevo.api-key (BREVO_API_KEY)",
      "channels": [ { "channelType": "EMAIL", "preferenceOrder": 2 } ] },
    { "providerId": "simulated", "status": "ENABLED", "statusReason": null,
      "channels": [
        { "channelType": "EMAIL", "preferenceOrder": 1 },
        { "channelType": "PUSH", "preferenceOrder": 1 },
        { "channelType": "SMS", "preferenceOrder": 1 }
      ] }
  ]
}
```

## Respuestas de error

- Cabecera `X-Tenant-Id` ausente o vacía: `400` con `ErrorResponse` en ambos casos (el
  `IllegalArgumentException` de `TenantId` lo traduce el `NotificationExceptionHandler` existente; ver
  research.md, Decisión 1, sobre por qué la cabecera ausente no usa el `400` genérico de WebFlux).
- No hay `404` ni `5xx` propios: un catálogo vacío es `200` con `items: []`.
