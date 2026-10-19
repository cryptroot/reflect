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

## 2. Engineering function

The module file is the contract between all later tools: `rflc0` writes it in v0.1, VM0 runs it
until v0.6, and `rfl` loads it after that. v0.0.x builds this contract first and tests it with
hand-written modules. As a result, the compiler of v0.1 is tested against a working VM, and a VM
fault cannot hide behind a compiler fault.

| Part | Later use |
|---|---|
| `reflect.stage0.bytecode`: model, reader, writer, assembler, disassembler | `rflc0` writes modules with it (v0.1); VM0 reads them; the transliterator prints `reflect.bytecode` from it (v0.2, Q47) |
| VM0 | runs the stdlib and the conformance suite (v0.1), and `rflc` until `rfl` replaces it (v0.6, [bootstrapping.md](../bootstrapping.md) §2) |
| the `.rfla` suite | stays as the instruction-level tests of VM0, and then of `rfl` in every tier (TST-08, TST-09) |
| the suite runner | becomes the conformance runner of v0.1 (TST-01) |
| the Docker image and its jobs | every later version; CI runs the same jobs (BLD-06) |

## 3. Scope

In scope:

- the Docker environment for Windows and Linux hosts, Lean included (§5);
- the exact encoding of modules: opcode numbers, signature codes, table layouts, sections,
  method bodies and the `.rfla` grammar, written into [bytecode.md](../bytecode.md);
- stage0's bytecode library, `rfla`, `rfld` and the round trip of BC-08;
- VM0 for the instructions that R0 needs (BOOT-02): primitives, static and instance calls,
  objects, interfaces, arrays, strings, boxing, exceptions and reified generics;
- hand-written stub modules for `reflect.core` and `reflect.io`, used until v0.1 compiles the
  stdlib (Q49).

Out of scope, with the version that brings it:

| Item | Version |
|---|---|
| `rflc0`; R0 and stdlib sources; `.rfl` conformance tests | v0.1 |
| finalization and weak references in VM0 | v0.1, with the stdlib that declares them |
| the transliterator | v0.2 |
| instructions for static virtual members, structs and `ref` | v0.4 |
| instructions for other features outside R0, such as unsigned types and checked arithmetic | with those features |
| reflection and generators on VM0 | v0.5 |
| `rfl`, its register form, unsafe instructions, the `reflect-runtime` image | v0.6 |
| the verifier | v0.7 |
| proofs | the proof track of [roadmap.md](../roadmap.md) |
