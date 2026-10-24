# Quickstart: llamada autenticada en desarrollo (HU2-113)

1. `docker compose up -d mongodb rabbitmq`
2. `pwsh scripts/dev-token.ps1 -Tenant tenant-1 -Subject dev -Role ADMINISTRADOR` imprime `AUTH_PLATFORM_PUBLIC_KEY` y un token.
3. Exporta la clave impresa y arranca: `SPRING_PROFILES_ACTIVE=local ./mvnw -pl infrastructure spring-boot:run`.
4. `curl -H "Authorization: Bearer <token>" http://localhost:8060/api/v1/notifications`.

Sin `AUTH_PLATFORM_PUBLIC_KEY` el servicio no arranca. Con una sesión real del PDP basta la cookie `SECURITY_BASELINE_SESSION`.
