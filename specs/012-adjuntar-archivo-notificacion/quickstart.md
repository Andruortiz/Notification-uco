# Quickstart: Adjuntar un archivo a una notificación

Guía de validación. La verificación que cuenta es la automatizada (`NotificationAttachmentE2ETest` y
`./mvnw -B -ntp verify`); los pasos manuales sirven para ver el comportamiento real, no sustituyen ninguna
prueba.

## Prerrequisitos

- Docker Desktop en marcha (`docker info` responde).
- En este equipo, `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` exportada antes de `./mvnw` (Testcontainers
  1.19.8 con Docker Engine 29).

## Pruebas automatizadas

```bash
export JAVA_TOOL_OPTIONS="-Dapi.version=1.44"

./mvnw -B -ntp -pl core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest=NotificationControllerTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest=NotificationMongoAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest=NotificationAttachmentE2ETest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

Resultado esperado: todo en verde; cobertura ≥ 80 % líneas y ≥ 70 % ramas (`<módulo>/target/site/jacoco/`).

| Criterio | Dónde se verifica |
|---|---|
| SC-001 | `NotificationAttachmentE2ETest`: aceptada → `DELIVERED`; el emisor de prueba recibe los mismos adjuntos en orden |
| SC-002 | `NotificationAttachmentE2ETest` (una solicitud por regla, `400`, nada guardado) + `AttachmentPolicyTest` y `ContentSchemaValidatorTest` |
| SC-003 | `NotificationAttachmentE2ETest`: dirección reconocible ausente de registros, errores, eventos y consultas; nombre, tipo y tamaño presentes en registros |
| SC-004 | Suite existente sin cambios de expectativas |
| SC-005 | `NotificationAttachmentE2ETest`: cambio de `maximum` aplicado en < `Duration.ofSeconds(5)` con refresco de 1 s |
| SC-006 | `NotificationAttachmentE2ETest` (emisor sin soporte → `FAILED`, no invocado; sin adjuntos → `DELIVERED`) + `DispatchNotificationServiceTest` |
| SC-007 | `SendNotificationBatchServiceTest` (el lote no tiene operación HTTP, research.md, Decisión 10) |

## Validación manual (opcional)

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run
```

1. Con la configuración por defecto, `POST /notifications` por `EMAIL` con un `attachments` válido → `400`
   "channel EMAIL does not accept attachments".
2. Habilitar adjuntos en EMAIL editando su documento en MongoDB (`db.channel_catalog.updateOne({_id:
   "EMAIL"}, {$set: {contentSchema: "<esquema de data-model.md>"}})`) y esperar el refresco (30 s por
   defecto).
3. Repetir 1 con un PDF de 1 MB declarado → `202`; `GET /notifications/{id}` → `DELIVERED` por
   `simulated` (posición 1 por defecto). La respuesta no incluye la dirección.
4. Repetir con `sizeBytes: 6000000` → `400` `attachments[0]: …`; con `contentType: "image/gif"` → `400`.
5. Revisar la salida del servicio: aparecen nombre, tipo y tamaño; la dirección no.
6. Restaurar el `contentSchema` de EMAIL a `null`.

La forma exacta del contrato está en [contracts/api-notificaciones-cambios.md](contracts/api-notificaciones-cambios.md)
y las reglas de cada campo en [data-model.md](data-model.md).
