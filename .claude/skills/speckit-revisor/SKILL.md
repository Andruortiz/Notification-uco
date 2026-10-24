---
name: "speckit-revisor"
description: "Lanza el agente revisor de solo lectura sobre una historia de Notification-uco: analiza spec, plan, tareas y código contra la constitución y devuelve hallazgos con severidad y qué se verificó."
argument-hint: "Qué revisar, ej. 'spec 007', 'rama actual', 'PR 34' o un commit"
compatibility: "Requiere .specify/ y el agente speckit-revisor en .claude/agents/"
user-invocable: true
disable-model-invocation: false
---

## User Input

```text
$ARGUMENTS
```

## Qué hace este comando

No revisa por sí mismo: delega en el agente `speckit-revisor` (`.claude/agents/speckit-revisor.md`), que trabaja en solo lectura y con contexto limpio. Ese aislamiento es lo que lo hace útil: no hereda los supuestos de quien escribió el código.

## Pasos

1. **Resolver el objetivo.**
   - Si el texto indica un número (`spec 007`), usa la carpeta `specs/007-*`.
   - Si indica un PR o un commit, úsalo como alcance del código (`gh pr view <n>` o `git show <sha>`).
   - Si está vacío, infiérelo de la rama actual (`git branch --show-current`, por ejemplo `feature/HU2-077-...`) y de la carpeta `specs/` que le corresponda. Si no es inequívoco, pregunta al usuario qué revisar.
2. **Comprobar que el agente existe** (`.claude/agents/speckit-revisor.md`). Si no está, dilo y detente.
3. **Lanzar el agente** con la herramienta Agent (`subagent_type: "speckit-revisor"`), en segundo plano, con un prompt autocontenido que incluya:
   - la carpeta de la historia, la rama o el commit y el estado (mergeada o no);
   - el alcance: artefactos de `specs/<historia>/` contra `.specify/memory/constitution.md` y el código relacionado con sus pruebas;
   - cualquier pregunta concreta que haya dado el usuario;
   - el formato pedido (tabla de hallazgos, máximo unas 500 palabras).
   No incluyas tus propias conclusiones sobre la historia: se pierde la independencia.
4. **Mientras corre**, no edites los archivos que revisa ni repitas su trabajo.
5. **Al terminar**, verifica los hallazgos más importantes leyendo el código antes de reportarlos (un agente puede equivocarse). Presenta el resultado separando lo confirmado, lo no verificado y lo que descartaste, y por qué.
6. **No apliques correcciones** salvo que el usuario lo pida. Para corregir, el autor (`/speckit-autor`) o esta sesión trabajan en una rama nueva.
