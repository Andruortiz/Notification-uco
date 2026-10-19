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

## Manual (cierra la suposición de la forma de la sesión)

1. Levantar dependencias y el servicio con `SECURITY_PEP_ENFORCEMENT_ENABLED=true` y `AUTH_SESSION_URL=http://localhost:18080`.
2. Con una cookie real: `curl -i -H "Cookie: SECURITY_BASELINE_SESSION=<valor>" http://localhost:8060/notifications/search`.
3. Comparar la respuesta de `GET http://localhost:18080/api/v1/session` con `contracts/session-response.md` y corregir el doble si difiere.
