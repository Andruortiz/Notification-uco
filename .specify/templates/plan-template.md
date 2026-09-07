# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]

**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

[Extract from feature spec: primary requirement + technical approach from research]

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: [e.g., Java 21]

**Primary Dependencies**: [e.g., Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB]

**Storage**: [if applicable, e.g., MongoDB or N/A]

**Testing**: [e.g., JUnit5, Mockito, StepVerifier, ArchUnit]

**Target Platform**: [e.g., Kubernetes]

**Project Type**: [e.g., hexagonal microservice / core module / infrastructure adapter]

**Performance Goals**: [domain-specific, e.g., 500 notifications/min per replica, or NEEDS CLARIFICATION]

**Constraints**: [domain-specific, e.g., ≤200ms p95 acceptance, no thread blocking]

**Scale/Scope**: [domain-specific, e.g., how many tenants, how many channels]

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

[Gates determined based on constitution file]

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., core/src/main/java/..., infrastructure/src/main/java/...).
  The delivered plan must not include Option labels.
-->

```text
# [REMOVE IF UNUSED] Option 1: core module (domain + use cases + ports)
core/src/main/java/co/edu/uco/notification/core/
├── domain/
├── port/in/
├── port/out/
├── repository/
└── usecase/

core/src/test/java/co/edu/uco/notification/core/
└── [same package structure, one test class per production class]

# [REMOVE IF UNUSED] Option 2: infrastructure adapter
infrastructure/src/main/java/co/edu/uco/notification/infrastructure/adapter/[in|out]/[technology]/
└── ...

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/[in|out]/[technology]/
└── ...
```

**Structure Decision**: [Document the selected structure and reference the real
directories captured above]

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., a new port] | [current need] | [why reusing an existing one isn't enough] |
