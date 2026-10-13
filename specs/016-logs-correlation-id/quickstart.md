# Quickstart: validar HU2-056

Prerrequisitos: Docker corriendo; `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`.

1. Dependencias: `docker compose up -d mongodb rabbitmq`.
2. Arranque: `./mvnw -pl infrastructure spring-boot:run` (puerto 8060).
3. Enviar una notificación con id conocido:
   `POST /notifications` con cabecera `X-Correlation-Id: demo-001` y token válido.
   Esperado: la respuesta devuelve `X-Correlation-Id: demo-001`.
4. Buscar `demo-001` en la salida del servicio. Esperado: cada línea es JSON parseable y todas las
   entradas del recorrido (aceptación, encolado, consumo, envío, estado terminal) contienen
   `correlationId`, `tenantId` y `notificationId` como campos.
5. Consultar el estado de la notificación. Esperado: expone `correlationId: demo-001`.
6. Repetir sin cabecera: el servicio genera un id y lo devuelve. Repetir con un valor inválido
   (espacios o más de 64 caracteres): se descarta y no aparece en los logs.
7. Revisar que ni dirección completa, contenido, credenciales ni token aparecen en la salida.
8. Automatizado: `./mvnw -B -ntp -pl infrastructure -am test -Dtest=LogCorrelationE2ETest
   -Dsurefire.failIfNoSpecifiedTests=false` y luego `./mvnw -B -ntp verify` completo.
