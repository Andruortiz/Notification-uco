# Cambios al contrato: `api-notificaciones.yaml` (HU2-073)

Se commitean en el YAML **antes** del controller (Principio II).

## Operación nueva

```yaml
  /configuration:
    parameters:
      - $ref: '#/components/parameters/CorrelationId'
      - $ref: '#/components/parameters/TraceParent'
    get:
      tags: [Configuración]
      summary: Consultar los parámetros gestionables y la configuración en uso
      description: >
        CU-11 (lectura). Requiere permiso de ADMINISTRADOR. Devuelve el registro de parámetros que el
        servicio permite gestionar y, para la réplica que responde, la versión de la configuración en uso,
        su origen y las claves aceptadas que esperan un reinicio. Ningún campo contiene credenciales,
        topología de colas ni direcciones base.
      operationId: getConfiguration
      responses:
        '200':
          headers:
            X-Correlation-Id:
              $ref: '#/components/headers/CorrelationId'
            traceparent:
              $ref: '#/components/headers/TraceParent'
          description: Configuración en uso de la réplica y registro de parámetros.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ConfigurationResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
```

## Esquemas nuevos

```yaml
    ConfigurationResponse:
      type: object
      required: [version, source, adoptedAt, pendingRestart, parameters]
      properties:
        version:
          type: integer
          format: int64
          description: Versión asignada por el Componente de Parámetros; 0 para los valores por defecto.
        source:
          type: string
          enum: [PARAMETERS, LAST_KNOWN, DEFAULTS]
        adoptedAt:
          type: string
          format: date-time
        pendingRestart:
          type: array
          items:
            type: string
        parameters:
          type: array
          items:
            $ref: '#/components/schemas/ParameterDescriptorResponse'
    ParameterDescriptorResponse:
      type: object
      required: [key, type, defaultValue, currentValue, min, max, scope, adoption]
      properties:
        key:
          type: string
          example: provider.brevo.timeout-ms
        type:
          type: string
          enum: [INTEGER]
        defaultValue:
          type: integer
          format: int64
        currentValue:
          type: integer
          format: int64
        min:
          type: integer
          format: int64
        max:
          type: integer
          format: int64
        scope:
          type: string
          enum: [GLOBAL, CHANNEL, PROVIDER]
        scopeIds:
          type: array
          items:
            type: string
        adoption:
          type: string
          enum: [HOT, RESTART]
```

Además: etiqueta `Configuración` en `tags`, y la descripción general del contrato enumera `ADMINISTRADOR`
como rol mínimo de esta operación. `RouteAuthorizationPolicy` gana la regla explícita
`GET /configuration` -> `ADMINISTRADOR`.

## Sin operación de escritura

La publicación de cambios no se expone por HTTP en esta historia: el puerto de entrada lo alimenta el
adaptador de sondeo. Si SUP-02 resuelve publicación por evento o por llamada entrante, se define entonces
(Principio II) y se decide su autenticación.
