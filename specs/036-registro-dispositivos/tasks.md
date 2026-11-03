# Tasks: Registro de dispositivos por destinatario (HU2-120)

Plan: [plan.md](plan.md). Spec: [spec.md](spec.md). Orden por tarea: prueba, verla fallar, implementar, verde, `spotless:apply`.

## Fase 1: Core

- [x] T001 Dominio `RecipientIdentity`, `Device`, `DevicePlatform`, `TokenMask`, excepciones y puertos (`DeviceRegistryPort`, `DeviceLifecyclePort`, casos de uso de entrada). Desviacion: no existe `TokenProtectionPort` en `core`; el cifrado es de `infrastructure` y la mascara es `TokenMask` en `core`.
- [x] T002 `DeviceService` (registro, listado, baja, borrado) con prueba unitaria `DeviceServiceTest` (FR-001..FR-003, FR-013).
- [x] T003 `AttemptResult.INVALID_RECIPIENT` y `FailureCause.INVALID_RECIPIENT`: `Notification.markFailed` lo conserva, `DispatchNotificationService` lo trata como fallo permanente, llama a `DeviceLifecyclePort.invalidate` (410/UNREGISTERED) y `touch` (aceptado, solo PUSH) tolerando fallos; `DispatchDeviceMaintenanceTest` (FR-007, FR-008). Desviacion: segundo constructor de 7 argumentos; el bean primario esta en `DeviceConfig`, no en `UseCaseConfig`.

## Fase 2: Infraestructura

- [x] T004 `AesGcmTokenProtection` (AES-256-GCM con AAD, HMAC-SHA256, versiones de clave) y `AesGcmTokenProtectionTest` (FR-005).
- [x] T005 `DeviceMongoAdapter` (documentos, upsert atomico, indices unico y TTL con `collMod`, `DeviceLifecyclePort`) y `DeviceMongoAdapterTest` con Testcontainers: SC-005, mismo token en dos tenants, `collMod`, FR-011. Desviacion: contadores `devices.removed{reason}` sin el gauge `devices.active`.
- [x] T006 Cifrado de `recipientAddress` y `recipientId` PUSH en `NotificationDocumentMapper`/`NotificationMongoAdapter`, `recipientIdFingerprint`, filtro por huella y barrido en segundo plano (`PushRecipientEncryptionSweep`); `PushRecipientEncryptionTest` (FR-012). Desviacion: el AAD incluye el nombre del campo; `findByProviderMessageId` tambien descifra.
- [x] T007 `FcmResponseClassifier.classify(status, errorCode)` y `FcmNotificationProvider` (410/UNREGISTERED -> `INVALID_RECIPIENT`); `MicrometerNotificationMetrics` y `ProviderLogs`; pruebas del clasificador y del proveedor ajustadas (FR-007).
- [x] T008 `DeviceController`, DTOs, `NotificationExceptionHandler`, `ErrorCode.DEVICE_NOT_FOUND` (3041), `RouteAuthorizationPolicy` y mascara de `recipientId` PUSH en `GET /notifications` y en `GET /notifications/stream` (el plan decia que el stream no devolvia el destinatario; si lo devuelve).
- [x] T009 `DeviceConfig` (paquete `adapter.out.device`, para no crear un ciclo con `config`): claves `DEVICE_TOKEN_KEY` y `DEVICE_FINGERPRINT_KEY` leidas del entorno, retencion, barrido y `DispatchNotificationUseCase` primario con el puerto de ciclo de vida. Desviacion: sin claves, los perfiles `local` y `test` usan claves efimeras y el resto falla al arrancar.

## Fase 3: Verificacion

- [x] T010 E2E `DeviceRegistryE2ETest` (Testcontainers Mongo y RabbitMQ, `WebTestClient`, FCM simulado): registrar, listar, enviar PUSH con 404 `UNREGISTERED` y 410 y comprobar que el dispositivo desaparece en menos de 5 s, baja manual, borrado por titular, dos tenants y dos sujetos, mascara por rol, control positivo de logs, p95 de SC-001 (SC-001..SC-005).
- [x] T011 Regresion dirigida de envio PUSH (FCM, E2E de proveedor, reintento, reenvio, lote, linea de tiempo), `HexagonalArchitectureTest`, `ModularityTests`, `RouteAuthorizationPolicyTest`.
- [ ] T012 Cierre: `./mvnw -B -ntp verify` una vez en segundo plano (lo ejecuta quien integra; no se corrio en esta rama de trabajo).

## Pendientes de integracion (fuera de esta rama)

- Rutas a `available` en el contrato (`FailureCause` y el resultado `INVALID_RECIPIENT` ya estan declarados); aprovisionar el PDP y regenerar `protected-resources.json`.
- `DEVICE_TOKEN_KEY`, `DEVICE_FINGERPRINT_KEY` (y `*_VERSION`) en el gestor de secretos, `.env.example`, compose y manual; `notification.devices.retention-days`, `sweep-enabled` y `sweep-batch-size` en `application.yml`.
- Reemplazar el `DispatchNotificationUseCase` primario de `DeviceConfig` por el cableado en `UseCaseConfig` (pasar `DeviceLifecyclePort` al constructor de 7 argumentos).
