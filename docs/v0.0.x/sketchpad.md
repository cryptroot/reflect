# v0.0.x sketchpad

Working document for v0.0.x: progress, housekeeping for the main documents, questions and
answers, and the engineering log. The plan is [README.md](README.md); the project-wide decision
log is [../sketchpad.md](../sketchpad.md).

- Branch `v0.0.x` was created from `research` at `6408e3d` on 2026-10-19.
- Inputs: [roadmap.md](../roadmap.md) (row v0.0.x), [bootstrapping.md](../bootstrapping.md),
  [bytecode.md](../bytecode.md), [vm.md](../vm.md), [testing.md](../testing.md),
  [formal-model.md](../formal-model.md).

## Progress log

| Stage | Content | Status |
|---|---|---|
| plan | README and this sketchpad | in progress 2026-10-19 |
| v0.0.0 | Docker environment, skeleton, Lean in Docker | not started |
| v0.0.1 | encoding tables, constants, document test | not started |
| v0.0.2 | model, writer, reader | not started |
| v0.0.3 | assembler | not started |
| v0.0.4 | disassembler, round trip, suite runner | not started |
| v0.0.5 | VM0 loader and core interpreter | not started |
| v0.0.6 | VM0 objects and dispatch | not started |
| v0.0.7 | VM0 arrays, strings and boxing | not started |
| v0.0.8 | VM0 exceptions | not started |
| v0.0.9 | VM0 reified types and generics; exit test | not started |

## 0. Conventions

- Question IDs continue the sequence of [../sketchpad.md](../sketchpad.md) Part B, so no ID
  means two things.
- Status tags and rule IDs: [architecture.md](../architecture.md) §0.
- Dates use ISO 8601. Dated notes, decision history and engineering issues belong here, not in
  the README.
- When a question is answered, its answer is dated here, and the README states only the result.
