# Quickstart: validar la corrección de los hallazgos de la revisión

**Feature**: 017-Fix-specs-012-016 | **Fecha**: 2026-10-05

Guía de validación manual y automatizada. No sustituye las pruebas de `tasks.md`. Cada escenario remite a la
historia de usuario de `spec.md` y a las decisiones de `research.md`.

## Prerrequisitos

- Docker en marcha (Mongo, RabbitMQ, MinIO y ClamAV con `docker compose up -d`).
- Servicio arrancado desde el jar: `./mvnw -B -ntp -DskipTests -pl infrastructure -am package` y
  `java -jar infrastructure/target/infrastructure-0.1.0-SNAPSHOT.jar`. (`spring-boot:run` no funciona desde la
  raíz porque `infrastructure/pom.xml` no fija la versión del plugin).
- Para no tocar los datos de desarrollo, usar una base y un vhost propios:
  `MONGO_DATABASE=qs_017` y `SPRING_RABBITMQ_VIRTUAL_HOST=qs017` (crear el vhost por la API de gestión).
- Proveedores simulados: `NOTIFICATION_EMAIL_PROVIDERS=simulated` y equivalentes. **No exportar** credenciales
  de proveedores reales.
- Un token de desarrollo: `AUTH_JWT_HS256_SECRET=<secreto de 32+ bytes> TENANT=t1 ROLE=OPERADOR node token.mjs`.

## Historia 1 — arranque y autenticación seguros

1. **Sin secreto** (`unset AUTH_JWT_HS256_SECRET`), sin perfil `local`: el servicio no arranca y el mensaje
   nombra la variable. Esperado: salida con error antes de abrir el puerto 8060.
2. **Con el secreto de desarrollo** fuera del perfil `local`: no arranca con un mensaje claro.
3. **Con perfil `local`** (`SPRING_PROFILES_ACTIVE=local`): arranca con el secreto de desarrollo.
4. **Token sin `exp`**: `curl -i http://localhost:8060/notifications -H "Authorization: Bearer <token sin exp>"`
   devuelve 401.
5. **Ruta que solo comparte prefijo**: `curl -i http://localhost:8060/actuatorX` devuelve 401, mientras que
   `curl -i http://localhost:8060/actuator/health` devuelve 200.
6. **Error posterior a la autenticación**: forzar una excepción en un caso de uso (la prueba automatizada lo
   hace con un stub) y comprobar que la respuesta es 500 y que el log no la marca como rechazo de credencial.
7. **Ticket del panel**:
   ```bash
   TICKET=$(curl -s -X POST http://localhost:8060/notifications:subscribeTicket -H "Authorization: Bearer $TOKEN" | jq -r .ticket)
   curl -N -m 5 "http://localhost:8060/notifications:subscribe?ticket=$TICKET"
   curl -i -m 5 "http://localhost:8060/notifications:subscribe?ticket=$TICKET"
   ```
   El primero abre el flujo; el segundo (mismo ticket) da 401. Un ticket pedido hace más de 30 s da 401, y
   `?access_token=<jwt>` ya no se acepta.

## Historia 2 — nada se pierde sin rastro

1. **Tope del lote**: enviar un lote de 501 elementos a `POST /notifications:sendBatch` y comprobar 400 en menos
   de 1 s sin que se cree ninguna notificación; con 500 elementos, 202.
2. **Reenvío del mismo `batchId`**: enviar dos veces el mismo lote; la segunda respuesta es idéntica a la
   primera y no crea notificaciones nuevas.
3. **Registro del lote no guardado**: la prueba automatizada fuerza el fallo del guardado; esperado: 202 con
   `trackingSaved: false` y un registro ERROR con la categoría `BATCH_RECORD_NOT_PERSISTED`.
4. **Motivo genérico**: un ítem que falla por una causa interna devuelve `Internal error` y no contiene hosts
   ni nombres de colección.
5. **Adjunto con tamaño inconsistente**: subir un objeto de tamaño distinto al declarado y completarlo; la
   carga pasa a `FAILED` con `SIZE_MISMATCH`. Un cambio del objeto tras la finalización agota los intentos y
   la carga pasa a `FAILED` con `SCAN_EXHAUSTED` sin esperar al sweeper.

## Historia 3 — el despacho no duplica ni pierde envíos

1. **Reentrega idempotente**: publicar 100 veces el mismo `notificationId` (con `message_id` distinto cada vez)
   en `notification.dispatch.exchange`; el proveedor simulado registra exactamente 1 envío y la DLQ no recibe
   ningún mensaje.
2. **Fallo de guardado tras envío aceptado**: la prueba automatizada falla el `save` tras un envío aceptado; la
   reentrega no produce un segundo envío y el mensaje queda en la DLQ con la causa.
3. **Encolado que falla en el reencolado**: forzar el fallo de `enqueueForDispatch`; la notificación sigue
   `PENDING`, el fallo queda registrado y la pasada siguiente la vuelve a intentar.
4. **Proveedor deshabilitado** (008, 009, 010): con `brevo` preferente y sin credenciales, la notificación queda
   `PENDING` sin intentos y el mensaje termina en la DLQ con la causa; la reserva se libera.
5. **Mensaje sin `message_id`**: ya no detiene el consumidor; llega a la DLQ con la causa tras los intentos
   acotados.

## Historia 4 — artefactos coherentes

1. Buscar `X-Tenant-Id` en `specs/011-*`, `013-*`, `014-*`, `018-*` y `012-adjuntar-*`: 0 coincidencias salvo
   notas históricas marcadas como tales.
2. Ejecutar el quickstart actualizado de cada historia mergeada con credencial Bearer: sin pasos fallidos.
3. En 018, comprobar que el rol `OPERADOR` figura y que existen las pruebas 403 y 202.

## Historia 5 — reglas del proyecto

1. `./mvnw -B -ntp -pl core,infrastructure,utils test -Dtest=NoExplanatoryCommentsTest` pasa; el control
   positivo detecta un fixture con comentario.
2. Un conflicto de versión y una clave duplicada responden 409; un error inesperado responde 500 con mensaje
   genérico y `correlationId`, sin el mensaje de la excepción.

## Puerta completa

```bash
./mvnw -B -ntp spotless:check
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp clean verify
```

El `verify` completo tarda unos 19 minutos en local; CI lo ejecuta en cada PR.
