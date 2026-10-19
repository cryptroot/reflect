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

## A. Housekeeping

### A.1 Changes the main documents need

These follow from the plan. Each is made in the stage named, or when its question is answered.

| Document | Change | When |
|---|---|---|
| [architecture.md](../architecture.md) §9 | a rule that every job runs in Docker on Windows and Linux hosts, Lean included, and that a host needs only Docker and git (README §5) | when README §5 is accepted |
| [architecture.md](../architecture.md) §3 | the layout gains `.gitattributes`, `docker/dev.ps1`, `docker/dev.sh`, `docker/ci.sh` and `tests/bytecode/` (README §6.1) | v0.0.0 |
| [bootstrapping.md](../bootstrapping.md) BOOT-10 | the package list gains `reflect.stage0.bytecode` and `reflect.stage0.tools` | Q47 |
| [bootstrapping.md](../bootstrapping.md) BOOT-11, BOOT-12 | one writer, written in stage0, instead of a Java port of a Reflect writer; the transliterator also prints `reflect.bytecode` | Q47 |
| [bytecode.md](../bytecode.md) | encoding tables, the R0 mark on opcodes, the canonical form, the `main` column and `.main`, the `.rfla` grammar, `RFA` codes | v0.0.1, v0.0.3 |
| [testing.md](../testing.md) §7 | the home of `.rfla` tests (`tests/bytecode/format`, `lib`, `run` and `invalid`) and the `RFA` form of `expect-error` | v0.0.3 |
| [examples.md](../examples.md) §11.1 | the hidden `$receiver` argument becomes the implicit `R` of BC-05 (A.2 item 3) | with [../sketchpad.md](../sketchpad.md) A.6 |

### A.2 Inconsistencies found while planning

1. GEN-12 spells primitives by struct name in `.rfla` (`reflect.core.Int32`), but ex §11.2 writes
   `int` (Q52).
2. BC-08 names type parameters by index (`!0`, `!!0`); ex §11.1 also uses names (`!!K`) (Q52).
3. ex §11.1 still passes the receiver class as a hidden `$receiver` argument after `ldtype`.
   BC-05 replaced this with the implicit `R` of `call`, and with `call.recv` and `ldrecv`.
4. BC-04 calls the operand of `ldstr` an index (`<n>`); BC-02 says heaps are referenced by byte
   offset.
5. The `Module` table of BC-11 has no column for the descriptor's `main` class, which VM-12
   reads. v0.0.1 adds the column and the `.main` directive.
6. BOOT-11 speaks of "a Java port of the writer". Under Q47 there is one writer.
7. TST-09 puts verifier rejections in hand-written `.rfla` files, but names no directory.
8. Two rule IDs have two definitions: LIB-03 (architecture.md §6 and stdlib.md §1) and OPR-08
   (type-system.md §10 and grammar.md §5). In each pair the second restates the first (Q56).

## B. Questions and answers

Answer in chat, in the question prompts, or by editing the **Answer** lines.

### B.1 Batch 5: the v0.0.x plan (asked 2026-10-19)

**Q45. Integration branch.** Only `research` exists, and a version branch needs a branch to
start from and to merge into. *Recommendation:* create `main` from `research`; `v0.0.x` starts
at the same commit (`6408e3d`) and merges into `main` with a merge commit; `research` keeps the
design history. **Answer:**

**Q46. Branches for stages.** *Recommendation:* one branch per version (`v0.0.x`) and an
annotated tag per finished stage (`v0.0.N`); a short-lived stage branch only when two stages
run in parallel. **Answer:**

**Q47 (BOOT-10, BOOT-12). Is stage0's bytecode library R0-shaped Java?** `rflc` needs
`reflect.bytecode` in Reflect by v0.2 (ARC-11). If `reflect.stage0.bytecode` is R0-shaped Java
(BOOT-03), and uses only the facade and the `java.lang` types that the transliterator maps, the
transliterator prints `reflect.bytecode` from it. The format then has one implementation instead
of a Java one and a Reflect one. Costs: SHA-256 and the UTF-8 and UTF-16LE codecs are written by
hand, and the facade `reflect.stage0.lib` must exist from v0.0.2. *Recommendation:* yes.
**Answer:**

**Q48 (BOOT-10). Java unit tests.** *Recommendation:* a small harness in `stage0/test` (an
annotation, a runner and assertions) instead of JUnit: there is nothing to download, and the
harness leaves with stage0 at v0.6. **Answer:**

**Q49. `reflect.core` and `reflect.io` before the stdlib exists.** VM0 needs `Object`, `String`,
the exceptions and `Console` to run a program. *Recommendation:* hand-written `.rfla` stub
modules in `tests/bytecode/lib/`, with the native names of ex §2.11. v0.1 replaces them with
stdlib sources and keeps the native table. Types built into VM0 instead would bypass the loader,
which the stubs test. **Answer:**

**Q50 (BC-19). Stack maps in `rfla`.** VM0 does not read them; the register form of `rfl` (VM-05)
and the verifier (BC-07) do. *Recommendation:* in v0.0.x, `rfla` stores `.stackmap` directives
as written and computes nothing; `rflc0` writes stack maps from v0.1; `rfla` learns to compute
them before `rfl` runs hand-written tests (v0.6). **Answer:**

**Q51 (LEAN-04). The Lean toolchain in the image.** *Recommendation:* the Dockerfile pins the
Lean version and the SHA-256 of its release archive, downloads the archive with `curl`, checks
it, and unpacks it to `/opt/lean`, without elan. The image holds one version, so elan's
toolchain switching is not needed, and one download path is simpler to check behind a proxy.
The `lean` job fails if `formal/lean-toolchain` names another version. **Answer:**

**Q52 (GEN-12, BC-08). Spelling in `.rfla`.** GEN-12 spells primitives by struct name
(`reflect.core.Int32`), and BC-08 names type parameters by index (`!0`, `!!0`); ex §11 writes
`int` and `!!K`. *Recommendation:* `rfld` prints the canonical spelling; `rfla` also accepts
primitive keywords and type-parameter names, so hand-written tests stay readable. Canonical text
is the fixpoint of the round trip. **Answer:**

**Q53 (BLD-06, TST-05). Hosted CI.** The repository has no remote. *Recommendation:* once it has
one, a hosted Linux runner runs `docker/dev.sh all` on every push to a version branch. Until
then, the developer runs `all` on Windows and on Linux before each tag. **Answer:**

**Q54 (LEAN-04). Editing Lean on Windows.** The Lean language server needs the toolchain, which
exists only in the image. *Recommendation:* a `.devcontainer/devcontainer.json` that opens the
repository in `reflect-build` with the VS Code Lean extension, added in v0.0.0. **Answer:**

**Q55 (RM-03). Where VM0 ends in v0.0.x.** The roadmap's exit test passes at v0.0.5 (hello
world). *Recommendation:* continue to v0.0.9, so hand-written modules test the whole R0
instruction subset before `rflc0` can hide VM0 faults behind compiler faults. Alternative: end
v0.0.x at v0.0.5 and grow VM0 in v0.1 together with `rflc0`. **Answer:**

**Q56. A job that checks the documents.** *Recommendation:* a `docs` job that checks relative
links, and that each cited rule ID has exactly one definition. Add it after the broken links of
[../sketchpad.md](../sketchpad.md) A.1 are fixed and LIB-03 and OPR-08 have one definition each
(both are stated twice today). **Answer:**

<!-- questions:end -->
