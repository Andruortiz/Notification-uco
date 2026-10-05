# Contrato provisional de la fuente de Parámetros (Supuesto S-1)

**Estado**: PROVISIONAL. No es el contrato de SUP-02; el equipo del Componente de Parámetros no lo ha
publicado. Lo propone este servicio para poder construir y probar el adaptador de sondeo. Se descarta o se
reemplaza cuando SUP-02 se cierre; el puerto `ParametersSourcePort` aísla el cambio. El adaptador HTTP queda
inactivo mientras `notification.parameters.base-url` esté vacía.

## Consulta del estado completo

`GET {base-url}/notification-service/configuration`

Respuesta `200`:

```json
{
  "version": 12,
  "values": {
    "dispatch.max-attempts": 5,
    "provider.brevo.timeout-ms": 8000,
    "requeue.interval-ms": 20000
  }
}
```

- `version`: entero creciente asignado por Parámetros (FR-013).
- `values`: parcial o completo; las claves ausentes conservan el valor vigente.
- Cualquier otro estado, tiempo de espera o cuerpo ilegible cuenta como indisponibilidad.

## Preguntas que dependen del otro equipo

1. Ruta, autenticación entre componentes y forma de la versión.
2. Si habrá publicación por evento además de la consulta.
3. Si `values` podrá traer claves de otros servicios (el servicio las ignoraría o las rechazaría; la spec
   exige rechazar claves desconocidas, FR-016).
