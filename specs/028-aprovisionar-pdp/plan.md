# Implementation Plan: Aprovisionar recursos, roles y perfiles en el PDP como código (HU2-099)

**Branch**: `feature/HU2-099-aprovisionar-pdp` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Aprobado

**Versión del plan**: 1

## Summary

Un script PowerShell (`scripts/provision-pdp.ps1`) lee `protected-resources.json` y deja en el PDP los recursos `available`,
los roles CLIENTE/OPERADOR/ADMINISTRADOR (cada uno vinculado a los recursos cuyo rol mínimo es él) y los perfiles acumulativos.
Consulta antes de escribir, no borra nada y nunca imprime la credencial. Se prueba con un doble HTTP del PDP.

## Technical Context

**Language/Version**: PowerShell 5.1 y 7 (HttpClient de .NET) | **Testing**: JUnit 5 que invoca el script, `HttpServer` del JDK

## Constitution Check (2.0.0)

- I Hexagonal: no se toca `core` ni `infrastructure/src/main`. Cumple.
- II Contrato primero: no hay endpoints nuevos en el servicio. Cumple.
- III Sin comentarios en código nuevo (script y Java). Cumple.
- IV Pruebas: la prueba que invoca el script es el E2E del flujo contra el doble; `verify` completo. Cumple.
- V Commits de una línea. VII Sin soluciones temporales: lo pendiente está en research.md (preguntas abiertas con alcance claro).
- Consumidores RabbitMQ: no aplica.

## Decisiones

1. PowerShell (el equipo trabaja en Windows); sin variante bash por costo (research.md, punto 8).
2. Credencial: cookie de sesión de un administrador en `PDP_ADMIN_SESSION`, porque el PDP con perfil `keycloak` no ofrece otra.
3. Modelo: cada rol concede solo los recursos de su rol mínimo; los perfiles acumulan roles. Equivale a los `profiles` de la lista.
4. El script valida la lista antes de llamar (perfiles coherentes, rutas registrables, sin duplicados) y falla si diverge.
5. Sin borrado: huérfanos y desvíos se informan.
6. `--dry-run` sin credencial no toca la red; con credencial hace solo lecturas.
7. Prueba: el JUnit genera la lista con `ProtectedResourcesCatalog` (extraído de `ProtectedResourcesCatalogTest`), ejecuta el
   script contra `FakePdpAdmin` y compara el estado con las operaciones `available` de `ApiContract`. Usa `pwsh` o
   `powershell`; sin shell se omite salvo que `CI` esté definida (ubuntu-latest trae `pwsh`).

## Project Structure

- `scripts/provision-pdp.ps1`
- `infrastructure/src/test/java/.../contract/ProtectedResourcesCatalog.java`
- `infrastructure/src/test/java/.../support/FakePdpAdmin.java`
- `infrastructure/src/test/java/.../provisioning/PdpProvisioningScriptTest.java`
- Documentación: README y `quickstart.md` de esta spec.
