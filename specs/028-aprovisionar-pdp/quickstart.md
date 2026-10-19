# Quickstart: aprovisionar el PDP (HU2-099)

1. Generar la lista canónica: `.\mvnw.cmd -B -ntp -pl infrastructure -am test -Dtest=ProtectedResourcesCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
   (queda en `infrastructure/target/contract/protected-resources.json`).
2. Registrar la aplicación `notification-service` en la consola (una sola vez).
3. Iniciar sesión como administrador de la aplicación en la consola y copiar el valor de la cookie `SECURITY_BASELINE_SESSION`
   (herramientas del navegador). No pegarlo en archivos ni en la línea de comandos:
   `$env:PDP_ADMIN_SESSION = Read-Host -MaskInput` (PowerShell 7) o asignarlo en la sesión actual.
4. Ver el plan sin cambios: `.\scripts\provision-pdp.ps1 --dry-run` (sin credencial no contacta al PDP; con ella solo lee).
5. Aplicar: `.\scripts\provision-pdp.ps1`. Se puede repetir sin duplicar.

Parámetros: `-PdpUrl` (por defecto `PDP_BASE_URL` o `http://localhost:18080`), `-ApplicationName` (por defecto
`notification-service`), `-ApplicationId`, `-CatalogPath`, `-DryRun` o `--dry-run`.

Orden: recursos, roles, vínculos rol-recurso, perfiles, vínculos perfil-rol. La asignación de perfiles a usuarios sigue
siendo manual en la consola. Los recursos del PDP que no están en la lista se informan como `[HUERFANO]` y no se borran.
