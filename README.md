# Notification-uco

## Trazabilidad del desarrollo asistido por IA

Desde el 2026-09-07 este repositorio adopta [spec-kit](https://github.com/github/spec-kit): cada
historia nueva desarrollada con asistencia de IA queda documentada de punta a punta en
`specs/<historia>/` (`spec.md` → `plan.md` → `tasks.md`), gobernada por las reglas de
`.specify/memory/constitution.md`.

**Nota retrospectiva:** el trabajo anterior a esa fecha (PRs #1–22, dominio, casos de uso CU-01 a
CU-04, contrato OpenAPI inicial) se desarrolló con asistencia de IA pero sin este proceso
estructurado — la evidencia disponible para ese período es el historial real de commits/PRs en
GitHub y las pruebas automatizadas del repositorio (en particular `HexagonalArchitectureTest` como
verificación objetiva de la arquitectura). No se reconstruye retroactivamente para simular que el
proceso existía desde el inicio.

## Azure Key Vault

Las variables de entorno del servicio (`MONGO_PASSWORD`, `BREVO_API_KEY`, `AUTH_JWT_HS256_SECRET`, etc.)
pueden leerse tambien desde un Azure Key Vault. Los contenedores (MongoDB, RabbitMQ, MinIO, ClamAV) no
cambian.

- **Activacion:** definir `AZURE_KEYVAULT_ENDPOINT` (por ejemplo `https://mi-vault.vault.azure.net/`).
  Sin ella el servicio funciona solo con el `.env`, como siempre.
- **Nombres:** Key Vault no admite guiones bajos, asi que el secreto `MONGO-PASSWORD` se expone como
  `MONGO_PASSWORD`. Se crean con el mismo nombre de la variable cambiando `_` por `-`.
- **Prefijo (recomendado):** con `AZURE_KEYVAULT_SECRET_PREFIX` (por ejemplo `NOTIFICATION-`) solo se leen
  los secretos cuyo nombre empieza con ese prefijo, y este se descarta: `NOTIFICATION-MONGO-PASSWORD` se
  expone como `MONGO_PASSWORD`. Sin prefijo se leen todos los secretos del vault, tambien los de otros
  servicios, y cada uno es una llamada mas al arrancar.
- **Precedencia:** una variable de entorno real gana sobre el vault; el vault gana sobre los valores por
  defecto de `application.yml`.
- **Autenticacion:** `DefaultAzureCredential`. En Azure, identidad administrada del Container App o App
  Service con el rol *Key Vault Secrets User* sobre el vault. En local, `az login`.
- **Fallo:** si el vault esta configurado y no se puede leer, el arranque falla.
- **Alcance:** solo se leen los secretos habilitados; se cargan una vez al arrancar (rotar un secreto
  requiere reiniciar la aplicacion).
