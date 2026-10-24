# Notification-uco

Servicio de notificaciones (Java 25, Spring Boot 4.1, WebFlux, Mongo reactivo, RabbitMQ). Módulos Maven: `core` (dominio sin frameworks), `infrastructure` (adaptadores, API), `utils`.

## Comandos (desde la raíz, `./mvnw` en Bash o `.\mvnw.cmd` en PowerShell)

| Necesidad | Comando |
|---|---|
| Compilar | `./mvnw -B -ntp compile` (sin `clean`, salvo clases obsoletas) |
| Puerta rápida | `./mvnw -B -ntp spotless:check compile` |
| Pruebas de `core` | `./mvnw -B -ntp -pl core test` |
| Una clase de `infrastructure` | `./mvnw -B -ntp -pl infrastructure -am test -Dtest=Clase -Dsurefire.failIfNoSpecifiedTests=false` |
| Formato tras crear o editar `.java` | `./mvnw -B -ntp spotless:apply` |
| Puerta completa (CI) | `./mvnw -B -ntp verify`, solo una vez al cerrar la historia, en segundo plano (~10 min) |

## Reglas

- Las pruebas de integración y E2E usan Testcontainers: Docker debe estar corriendo y con Docker Engine 29 hay que definir `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`. No uses `-DargLine`.
- Cero comentarios explicativos en código nuevo.
- Commits de una sola línea `tipo(ámbito): descripción`, sin `Co-Authored-By`. No hacer push ni abrir PR sin pedirlo; los PR van contra `main`.
- Salida de Maven: usa `-q` o redirige a un archivo y filtra (`Select-String 'ERROR|FAIL|Tests run:'`); no vuelques el log completo al chat.
- Contexto: de `specs/` lee solo la carpeta de la historia activa (`specs/<historia>/`); las otras 28 no se tocan salvo que la historia las cite. `Grep`/`Glob` siempre acotados a un módulo o carpeta.
- Archivos de más de 150 líneas: lee por rangos (`offset`/`limit`), no completos.
- Constitución: lee `.specify/memory/constitution-resumen.md` (1,5 KB); el `constitution.md` completo (16 KB) solo si una decisión depende del texto exacto.
- Flujo de historias: agentes `speckit-autor` y `speckit-revisor`; la constitución está en `.specify/memory/constitution.md`.
