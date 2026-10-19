# Quickstart: validar HU2-101

## Prerrequisitos

- Docker en marcha; `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` definido.
- Para la verificación manual: PDP en `http://localhost:18080` y una sesión iniciada (cookie `SECURITY_BASELINE_SESSION`).

## Automatizado

1. Contrato y filtro: `./mvnw -B -ntp -pl infrastructure -am test -Dtest='SessionCookieContractTest,AuthenticationWebFilterSessionTest,PdpSessionIdentityAdapterTest,CorsCredentialsTest' -Dsurefire.failIfNoSpecifiedTests=false`
2. E2E: `./mvnw -B -ntp -pl infrastructure -am test -Dtest=SessionCookieE2ETest -Dsurefire.failIfNoSpecifiedTests=false`
3. Arquitectura: `./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false`
4. Puerta completa: `./mvnw -B -ntp verify`

Resultado esperado: todo en verde; 401 sin credencial, 200 con cookie válida solo con datos del tenant de la sesión, 503 con el
PDP simulado caído, preflight con credenciales solo para el origen configurado.

## Manual con una cookie real (tarea T023, a cargo del usuario)

Las pruebas automáticas usan un doble del PDP cuya respuesta reproduce la forma verificada por el usuario
(`contracts/session-response.md`). Este paso confirma el flujo contra el PDP real.

Prerrequisitos: PDP en `http://localhost:18080`, PEP en `http://localhost:18081` con la aplicación `notification-service`
registrada y su credencial en `PEP_REGISTRATION_TOKEN`, y una sesión iniciada en el navegador.

1. Obtener el valor de la cookie: iniciar sesión en la plataforma, abrir las herramientas del navegador, pestaña Aplicación o
   Almacenamiento, Cookies de `localhost`, y copiar el valor de `SECURITY_BASELINE_SESSION`.
2. Levantar dependencias y el servicio:
   `docker compose up -d mongodb rabbitmq` y luego
   `SECURITY_PEP_ENFORCEMENT_ENABLED=true AUTH_SESSION_URL=http://localhost:18080 PEP_REGISTRATION_TOKEN=<credencial> ./mvnw -pl infrastructure spring-boot:run`
   (en PowerShell, definir cada variable con `$env:NOMBRE = 'valor'` antes del comando).
3. Comprobar la sesión directa: `curl -i -H "Cookie: SECURITY_BASELINE_SESSION=<valor>" http://localhost:18080/api/v1/session`;
   debe dar 200 con `data.subject` y `data.tenantId`.
4. Operar con solo la cookie: `curl -i -H "Cookie: SECURITY_BASELINE_SESSION=<valor>" http://localhost:8060/notifications`;
   esperado: 200 con datos del `tenantId` de la sesión. Repetir sin cookie: esperado 401 con `NTF-2001`.
5. Cerrar la sesión en la plataforma (`GET http://localhost:18080/api/v1/session/logout`) y repetir el paso 4 con la misma
   cookie: esperado 401 en la petición siguiente.
6. Detener el PDP y repetir el paso 4 con una cookie vigente: esperado 503 con `NTF-2003` en menos de 5 s.
7. Desde el panel (`credentials: 'include'`, por el proxy de Vite) comprobar que listar y enviar notificaciones funcionan sin
   `VITE_AUTH_TOKEN`.
8. Registrar el resultado aquí y, si la respuesta de sesión difiere de `contracts/session-response.md`, avisar para corregir
   el adaptador y el doble de prueba.

Resultado: pendiente de ejecución por el usuario.
