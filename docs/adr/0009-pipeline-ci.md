# ADR-0009 — Pipeline de CI de 7 etapas

**Estado**: Aceptado
**Referenciado en**: `.specify/memory/constitution.md` ("Flujo de desarrollo")

## Contexto

No se puede depender de pasos manuales propensos a error entre escribir código y desplegarlo.

## Decisión

Pipeline automatizado de 7 etapas: compilar → pruebas → puerta de cobertura → análisis estático →
imagen → publicación → despliegue.

## Consecuencias

- Cada PR contra `develop` corre el pipeline completo antes de poder mergear — verificar
  periódicamente que las 7 etapas siguen efectivamente implementadas en `.github/workflows/` y no
  solo declaradas aquí.

## Alternativas consideradas

- **Pasos manuales antes de desplegar** (ej. correr pruebas localmente y confiar en la disciplina
  del desarrollador): descartada — es exactamente lo que un pipeline automatizado existe para
  eliminar como fuente de error humano.
