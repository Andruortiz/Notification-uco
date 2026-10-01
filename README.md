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

## Adjuntos por correo (Brevo)

El canal EMAIL entrega adjuntos por Brevo. Los envia como Base64 dentro de la solicitud al proveedor.

- **Activacion:** el proveedor por defecto del canal es el simulado, que no envia nada. Para entregar de
  verdad, definir `NOTIFICATION_EMAIL_PROVIDERS=brevo` y las variables `BREVO_API_KEY`,
  `BREVO_SENDER_EMAIL` y `BREVO_SENDER_NAME`.
- **Limite:** Brevo admite menos de 4 MB de adjuntos por correo transaccional. Se aplica ese limite como
  **suma de todos los adjuntos** (4 000 000 bytes), en dos puntos: al aceptar la notificacion, con el
  esquema del canal (`attachmentsTotalBytes` y `sizeBytes`, 400 con el motivo), y al despachar, donde un
  total mayor falla sin llamar a Brevo. Hasta 5 adjuntos por notificacion.
- **Archivos grandes:** los de mas de 1 MB se suben primero a MinIO (`POST /attachment-uploads`). Al
  despachar, el servicio los lee del almacen y comprueba su huella SHA-256 antes de enviarlos. Si el
  archivo ya no existe, cambio o no coincide con su huella, la notificacion falla sin reintentos; si el
  almacen no responde, se reintenta.
- **Subida desde el navegador:** la subida va directo a MinIO con una URL prefirmada. `MINIO_PUBLIC_ENDPOINT`
  debe ser una direccion alcanzable desde el navegador, y `MINIO_CORS_ALLOW_ORIGIN` debe incluir el origen
  del frontend.
- **Bases ya sembradas:** el sembrador solo agrega canales que faltan y nunca modifica uno guardado, asi que
  una base creada antes de este cambio sigue con el canal EMAIL sin adjuntos. Para actualizarlo, desde
  `mongosh` conectado a la base del servicio:

```js
db.channel_catalog.updateOne(
  { _id: "EMAIL" },
  { $set: { contentSchema: '{"type":"object","properties":{"attachments":{"type":"array","maxItems":5,"items":{"type":"object","properties":{"sizeBytes":{"type":"integer","maximum":4000000}}}},"attachmentsTotalBytes":{"type":"integer","maximum":4000000}}}' } }
)
```

  El catalogo se refresca solo cada 30 segundos.
- **Fuera de alcance:** SMS (Twilio) y push (FCM) no envian adjuntos; una notificacion con adjuntos
  despachada por ellos falla sin reintentos.

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
