# Quickstart: HU2-146

Prerrequisitos: Docker en marcha y `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`.

Validación automatizada:

```bash
./mvnw -B -ntp -pl infrastructure -am test -Dtest=ApplicationBindingE2ETest -Dsurefire.failIfNoSpecifiedTests=false
```

Resultado esperado: alta (201), repetición (200 sin segunda auditoría), listado aislado entre dos tenants, baja (204), dos entradas de auditoría, una sola fila con 20 altas concurrentes y respuesta 503 en ≤5 s con el PDP detenido.

Contrato: [application-bindings.yaml](contracts/application-bindings.yaml). Datos: [data-model.md](data-model.md).
