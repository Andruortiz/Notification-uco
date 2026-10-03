# Quickstart: Adjuntar un archivo a una notificación

Guía de validación de la versión 3 (Q1 híbrida por tamaño). La verificación que cuenta es la automatizada
(`NotificationAttachmentE2ETest`, `AttachmentUploadE2ETest` y `./mvnw -B -ntp verify`); los pasos manuales
sirven para ver el comportamiento real, no sustituyen ninguna prueba.

## Prerrequisitos

- Docker Desktop en marcha (`docker info` responde).
- En este equipo, `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` exportada antes de `./mvnw` (Testcontainers
  1.19.8 con Docker Engine 29).
- Memoria suficiente para el contenedor de ClamAV (más de 1 GB) y tiempo para su primer arranque.

## Pruebas automatizadas

```bash
export JAVA_TOOL_OPTIONS="-Dapi.version=1.44"

./mvnw -B -ntp -pl core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest='TikaContentTypeDetectorAdapterTest,ClamAvMalwareScannerAdapterTest,MinioAttachmentStorageAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='NotificationMongoAdapterTest,AttachmentUploadMongoAdapterTest,ScanVerdictMongoAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='NotificationControllerTest,AttachmentUploadControllerTest,AttachmentScanListenerTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='NotificationAttachmentE2ETest,NotificationAttachmentScannerDownE2ETest,AttachmentUploadE2ETest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

Resultado esperado: todo en verde; cobertura ≥ 80 % líneas y ≥ 70 % ramas (`<módulo>/target/site/jacoco/`).

| Criterio | Dónde se verifica |
|---|---|
| SC-001 | `NotificationAttachmentE2ETest` (embebido) y `AttachmentUploadE2ETest` (subida): `DELIVERED`; el emisor de prueba recibe los mismos adjuntos, en orden y con la misma huella |
| SC-002 | `NotificationAttachmentE2ETest` (una solicitud por regla, nada guardado) + `AttachmentPolicyTest`, `ContentSchemaValidatorTest`, `AttachmentResolverTest` |
| SC-003 | Ambas E2E: contenido reconocible y dirección de subida ausentes de registros, errores, eventos, mensajes y consultas; nombre, tipo, tamaño y huella presentes en registros |
| SC-004 | Suite existente sin cambios de expectativas |
| SC-005 | `NotificationAttachmentE2ETest`: cambio de `maximum` aplicado en < `Duration.ofSeconds(5)` con refresco de 1 s |
| SC-006 | `NotificationAttachmentE2ETest` (emisor sin soporte → `FAILED`, no invocado; sin adjuntos → `DELIVERED`) + `DispatchNotificationServiceTest` |
| SC-007 | `SendNotificationBatchServiceTest` (el lote no tiene operación HTTP) |
| SC-008 | `NotificationAttachmentE2ETest` (EICAR embebido → `400`) y `AttachmentUploadE2ETest` (EICAR subido → `INFECTED` → `400`) |
| SC-009 | `AttachmentUploadE2ETest` (`PENDING_SCAN` → `409`, `INFECTED` → `400`, https ajena → `400`) + `AttachmentResolverTest` |
| SC-010 | `AttachmentUploadE2ETest` con dos tenants y control positivo + `AttachmentUploadMongoAdapterTest` y `ScanVerdictMongoAdapterTest` |
| SC-011 | `AttachmentUploadE2ETest`: `PENDING_SCAN` → `CLEAN` en ≤ `Duration.ofSeconds(30)` para 10 MB |
| SC-012 | `NotificationAttachmentE2ETest`: 5 × 1 MB embebidos aceptados en ≤ `Duration.ofSeconds(5)`, con ClamAV ya usado una vez |
| SC-013 | `MinioAttachmentStorageAdapterTest` (copia con ETag obsoleto falla) + `ScanAttachmentUploadServiceTest` |
| SC-014 | `NotificationAttachmentScannerDownE2ETest` (ClamAV inalcanzable: embebido → `503` y nada guardado; sin adjuntos → `202`) + `ClamAvMalwareScannerAdapterTest` (puerto cerrado → no disponible) y `NotificationControllerTest` (`503`) |

## Validación manual (opcional)

```bash
docker compose up -d mongodb rabbitmq minio clamav
./mvnw -pl infrastructure spring-boot:run
```

Variables de entorno necesarias además de las actuales: `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` (y, si no
son los valores por defecto, `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT`, `CLAMAV_HOST`, `CLAMAV_PORT`).
Esperar a que el contenedor de ClamAV termine de cargar las firmas.

1. Con la configuración por defecto, `POST /notifications` por `EMAIL` con un adjunto embebido válido →
   `400` "channel EMAIL does not accept attachments".
2. Habilitar adjuntos en EMAIL editando su documento en MongoDB (`db.channel_catalog.updateOne({_id:
   "EMAIL"}, {$set: {contentSchema: "<esquema de data-model.md>"}})`) y esperar el refresco (30 s por
   defecto).
3. **Camino chico**: repetir 1 con un PDF de 200 KB en `content` (`base64 -w0 factura.pdf`) → `202`;
   `GET /notifications/{id}` → `DELIVERED` por `simulated`.
4. Repetir con la cadena EICAR en un archivo `.txt` embebido → `400` de software malicioso; con un PNG
   declarado como `application/pdf` → `400` de tipo que no coincide; con `factura.pdf.exe` → `400` de
   extensión.
5. **Camino grande**: `POST /attachment-uploads` con un PDF de 5 MB → `201` con `uploadUrl`, `uploadFields`
   y `attachmentUrl`; subir con un formulario multipart que envía todos los campos de `uploadFields` y el
   archivo al final (`curl -F key=... -F Content-Type=application/pdf -F policy=... -F file=@contrato.pdf
   "<uploadUrl>"` → `204`; otro tamaño o tipo → rechazo del almacén); `POST /attachment-uploads/{uploadId}:complete`
   → `202`; `GET /attachment-uploads/{uploadId}` hasta `CLEAN`.
6. `POST /notifications` con `attachmentUrl` en `url` → `202` → `DELIVERED`. Con el token de otro tenant → `400`,
   igual que con una dirección inventada; `GET /attachment-uploads/{uploadId}` con ese otro tenant → `404`.
7. Revisar la salida del servicio: aparecen nombre, tipo, tamaño y huella; ni el contenido ni `uploadFields`.
8. Restaurar el `contentSchema` de EMAIL a `null`.

La forma exacta del contrato está en [contracts/api-notificaciones-cambios.md](contracts/api-notificaciones-cambios.md)
y la de cada tipo y documento en [data-model.md](data-model.md).
