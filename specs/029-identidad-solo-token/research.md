# Research: Identidad solo desde el token (HU2-113)

## Preguntas abiertas

- **Q1 (Seguridad, DEP-01)**: nombres definitivos de las claims de sujeto, tenant, rol y cliente. Evidencia: el laboratorio usa
  `tenant`; el servicio asume `tenantId`. Recomendación: mantener `tenantId`, `sub`, `role`, `azp` como valores por defecto y
  fijar los del laboratorio por variable de entorno hasta que Seguridad confirme. Excepción registrada: dueño Seguridad,
  fecha a fijar por el usuario.
- **Q2**: código nuevo `AUTHENTICATION_CLAIMS_MISSING` frente a conservar `AUTHENTICATION_REQUIRED` con otro mensaje.
  Recomendación: código nuevo; el criterio pide un "código claro" y el mensaje no es contrato.
- **Q3**: claim de cliente opcional con campo en el principal frente a solo propiedad. Recomendación: opcional con campo
  anulable (cambio mínimo, B3a ya lo encuentra).
- **Q4**: script de desarrollo en PowerShell 7 frente a Java. Recomendación: PowerShell 7 (ya se usa `pwsh` en CI para
  `provision-pdp.ps1`); sin dependencias nuevas.

## Alternativas descartadas

- Conservar el modo local solo en el perfil `local`: contradice la decisión de retirarlo por completo.
- Par RSA fijo versionado para pruebas: obliga a versionar una clave privada; se genera en tiempo de ejecución.
- Poner la clave pública de prueba en `application.properties` de test: no existe valor estático sin versionar la privada.
