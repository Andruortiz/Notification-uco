---
name: "speckit-autor"
description: "Lanza el agente autor de spec-kit para avanzar una historia de Notification-uco: specify, clarify y plan (se detiene para tu aprobación) y, con el plan aprobado, tasks e implement."
argument-hint: "Historia y hasta dónde, ej. 'HU2-077' (hasta el plan) o 'HU2-077 implementar'"
compatibility: "Requiere .specify/, el agente speckit-autor en .claude/agents/ y acceso a Notion desde la sesión principal"
user-invocable: true
disable-model-invocation: false
---

## User Input

```text
$ARGUMENTS
```

## Qué hace este comando

No escribe la historia por sí mismo: prepara el contexto y delega en el agente `speckit-autor` (`.claude/agents/speckit-autor.md`), el único que escribe `spec.md`, `plan.md`, `tasks.md` y código. El agente no tiene acceso a Notion ni puede preguntarte a mitad de camino, por eso esta sesión le entrega todo lo necesario y gestiona las aprobaciones.

## Pasos

1. **Identificar historia y alcance.** Toma el ID (`HU2-XXX`) del texto. Por defecto el alcance es "hasta el plan": el agente se detiene ahí para que apruebes. Solo pasa a `tasks` e `implement` si el usuario dice que el plan ya está aprobado y existe `specs/<historia>/plan.md`. Si falta el ID, pregunta.
2. **Traer el texto de la historia desde Notion** (fuente del backlog; el agente no puede leerlo). Carga las herramientas de Notion con ToolSearch y consulta la base del backlog (`collection://a6e67ffc-1869-4d8d-86ed-2a72c83c8265`) por `userDefined:ID`: Descripción, Notas, Trazabilidad, Prioridad, Tamaño, Estado y Marcador. Si la historia está `Bloqueado`, o depende de otra que no está `Hecho`, dilo al usuario antes de seguir. Si no existe, pregunta.
3. **Preparar la rama.** Comprueba `git branch --show-current` y `git status`. El agente trabaja sobre `feature/HU2-XXX-descripcion` (o `fix/...` para correcciones), creada desde `develop` actualizado (`git fetch`, luego `git checkout -b <rama> origin/develop`). Los cambios sin commitear que no sean de la historia se dejan como están y no entran en sus commits. Si hay conflicto posible, pregunta antes.
4. **Lanzar el agente** con la herramienta Agent (`subagent_type: "speckit-autor"`), en segundo plano, con un prompt autocontenido que incluya:
   - el ID y el texto de la historia obtenidos de Notion;
   - la rama, el alcance (hasta dónde llegar y dónde detenerse) y las decisiones ya tomadas por el usuario;
   - el recordatorio de que **no cite Notion** en artefactos versionados ni en commits, que los commits son locales, y que las preguntas de `clarify` deben volver en su informe.
5. **Al terminar**, resume lo hecho y lo pendiente, y comprueba lo importante leyendo los artefactos (no des por buena la descripción del agente). Si se detuvo en la puerta de aprobación, muestra el resumen del spec y del plan y **pide la aprobación del usuario**: la aprobación es del usuario (Principio VI), no del agente.
6. **Continuar tras la aprobación** reanudando el mismo agente con SendMessage (conserva su contexto) en vez de lanzar uno nuevo.
7. **Sugerir la revisión.** Recomienda `/speckit-revisor` después del plan y después de implementar.

Nunca pushes ni abras un PR desde este comando; eso lo decide el usuario, y la base es siempre `develop`.
