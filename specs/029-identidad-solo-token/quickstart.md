# Quickstart: llamada autenticada en desarrollo (HU2-113)

1. `docker compose up -d mongodb rabbitmq`
2. `pwsh scripts/dev-token.ps1 -Tenant tenant-1 -Subject dev -Role ADMINISTRADOR -ValidityMinutes 60` imprime
   `AUTH_PLATFORM_PUBLIC_KEY` y un token. La vigencia por defecto es de 60 minutos; con el token vencido hay que emitir otro.
3. Exporta la clave impresa y arranca.
   - PowerShell: `$env:AUTH_PLATFORM_PUBLIC_KEY='<clave>'; $env:SPRING_PROFILES_ACTIVE='local'; .\mvnw.cmd -pl infrastructure spring-boot:run`
   - Bash: `AUTH_PLATFORM_PUBLIC_KEY='<clave>' SPRING_PROFILES_ACTIVE=local ./mvnw -pl infrastructure spring-boot:run`
4. `curl -H "Authorization: Bearer <token>" http://localhost:8060/api/v1/notifications`.

Sin `AUTH_PLATFORM_PUBLIC_KEY` el servicio no arranca, tampoco si solo se usa cookie de sesión. Con una sesión real del PDP
basta la cookie `SECURITY_BASELINE_SESSION`.
