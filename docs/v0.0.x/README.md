# v0.0.x: bytecode, assembler and VM0

The plan for the first version series of Reflect: what it delivers, what later versions use it
for, and how it is built, stage by stage. Roadmap: [roadmap.md](../roadmap.md), row v0.0.x.
Dated notes, questions and engineering issues: [sketchpad.md](sketchpad.md). Conventions:
[architecture.md](../architecture.md) §0.

## 1. Summary

v0.0.x turns the bytecode design into tested code in stage0, before any compiler exists. All
work runs in Docker, on Windows and Linux hosts alike (§5).

| Item | Content |
|---|---|
| Roadmap content | these documents; the `.rflc` format and `reflect.bytecode` design; assembler and VM0 in stage0 |
| Roadmap exit test | an `.rfla` program round-trips and runs on VM0 |
| Deliverables | the `reflect-build` Docker image and the jobs that run in it; exact encoding tables in [bytecode.md](../bytecode.md); a module reader, writer, assembler (`rfla`) and disassembler (`rfld`) in stage0; VM0 for the R0 instruction subset; the `.rfla` test suite and its runner |
| Branch | `v0.0.x` (§4) |
| Stages | v0.0.0 to v0.0.9 (§7). The numbers give the order. A stage can merge with another, split or drop. |
