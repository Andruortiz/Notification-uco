# Research: Registro de dispositivos (HU2-120)

## Hallazgos del código

- `AuthenticatedPrincipal(subject, tenantId, role)` ya expone tenant, sub y rol: sirve para origen del tenant, componente de origen y máscara.
- `FcmResponseClassifier.classifyStatus` marca todo 4xx (salvo 408/429) como `PERMANENT_FAILURE` sin distinguir 404/410; `AttemptResult` no lleva causa.
- `NotificationSenderPort.send` devuelve solo `AttemptResult`; la señal "token inválido" requiere un valor nuevo o un resultado más rico.

## Preguntas abiertas (recomendación entre paréntesis)

1. Base legal y plazo de retención de 270 días: ¿los confirma el responsable de datos? (Seguir con el supuesto; bloquear producción hasta confirmar.)
2. ¿404 de FCM borra siempre o solo con `UNREGISTERED`? (Solo `UNREGISTERED`: un 404 por configuración borraría todos los tokens.)
3. ¿Quién puede registrar para un `recipientId` arbitrario del tenant? (Cualquier CLIENTE del tenant; refinar por componente cuando exista autorización por recurso.)
4. ¿Valor nuevo `INVALID_RECIPIENT` en `AttemptResult` o resultado enriquecido? (Valor nuevo: menor cambio; revisar los `switch` en tasks.)
5. ¿Fan-out por persona en esta historia? (No; F6.)
6. ¿Plataformas válidas? (`ANDROID`, `IOS`, `WEB`.)
