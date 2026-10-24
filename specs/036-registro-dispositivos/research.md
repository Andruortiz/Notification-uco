# Research: Registro de dispositivos (HU2-120)

## Hallazgos del código

- `AuthenticatedPrincipal(subject, tenantId, role)` expone tenant, sub y rol: sirve para origen del tenant, propiedad, componente de origen y máscara.
- `FcmResponseClassifier.classifyStatus(int)` solo mira el status HTTP; hace falta el `errorCode` del cuerpo (`FcmErrorResponse`).
- `NotificationDocument.recipientAddress` guarda hoy el token FCM en claro.
- `MicrometerNotificationMetrics.resultLabel` es un switch exhaustivo sobre `AttemptResult`; agregar un valor rompe la compilación hasta mapearlo.
- E3 (spec 031) rediseña los tipos compartidos antes que esta historia; se parte de sus tipos finales (`ProviderResult`, `AttemptRecord`, `Outcome`).

## Decisiones tomadas (aprobadas)

- Token de notificación PUSH cifrado en el mapeador, sin excepción; reintentos usan la dirección real descifrada.
- Propiedad por `subject` de la sesión; respuesta uniforme sin 409 ni diferencia entre ajeno e inexistente.
- Tres roles del código; dueños y fechas: rotación de claves, Seguridad; el resto, Equipo de backend, 2026-12-15.

## Preguntas abiertas (recomendación entre paréntesis)

1. ¿Responsable de datos confirma base legal y 270 días? (Seguir con el supuesto; bloquear producción hasta confirmar.)
2. ¿El barrido de cifrado del histórico corre en el despliegue de esta historia? (Sí, activado por propiedad una vez.)
