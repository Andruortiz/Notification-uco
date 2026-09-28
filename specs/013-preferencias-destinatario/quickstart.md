# Quickstart: Gestionar preferencias del destinatario y excluir bajas al despachar

**Feature**: 013-preferencias-destinatario | **Date**: 2026-09-26

Las garantías medibles (SC-001 a SC-009) las verifican pruebas automatizadas; esta guía solo sirve para
verlo funcionar a mano. Contrato: [contracts/api-notificaciones-cambios.md](./contracts/api-notificaciones-cambios.md).

## Pruebas automatizadas

Docker debe estar corriendo (Testcontainers). En este equipo, con Docker Engine 29:
`JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`.

```bash
./mvnw -B -ntp -pl core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest='RecipientPreferenceMongoAdapterTest,RecipientPreferencesE2ETest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

| Garantía | Prueba |
|---|---|
| SC-001, SC-002, SC-005 (consulta) | `RecipientPreferencesE2ETest` |
| SC-003 (≤5 s, cero peticiones al proveedor), SC-004, SC-005 (despacho), SC-006 | `RecipientPreferencesE2ETest` |
| SC-007 (fallo al leer preferencias) | `DispatchNotificationServiceTest` |
| SC-009 (10 actualizaciones concurrentes) | `RecipientPreferenceMongoAdapterTest` |
| SC-008 (sin regresiones) | suite completa en `verify` |

## Verificación manual

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run
```

1. Valor por defecto:
   `curl -s -H "X-Tenant-Id: tenant-a" localhost:8060/recipients/r-1/preferences`
   → `{"recipientId":"r-1","optedOutAll":false,"acceptedChannels":[],"updatedAt":null}`
2. Baja total:
   `curl -s -X POST -H "X-Tenant-Id: tenant-a" -H "Content-Type: application/json" -d '{"optedOutAll":true}' "localhost:8060/recipients/r-1:updatePreferences"`
3. Enviar una notificación para `r-1` en `tenant-a` (`POST /notifications`) y consultar su estado con
   `GET /notifications/{id}` → `DISCARDED`, sin `providerId`.
4. Repetir el envío con `X-Tenant-Id: tenant-b` → `DELIVERED` (la baja de `tenant-a` no cruza).
5. Entrada inválida: `-d '{"acceptedChannels":["EMAIL"," "]}'` → `400` y la consulta sigue mostrando la
   baja del paso 2.
