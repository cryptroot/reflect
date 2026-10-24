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

## 4. Working rules

- **[Decided, RM-04]** v0.0.x is developed on its own branch, `v0.0.x`, while work on it
  continues.
- **[Decided, RM-04]** Commits are small and frequent, so that each one can be reviewed and
  checked alone.

1. **Branch.** `v0.0.x` starts from the integration branch (Q45). Every v0.0.x commit goes to
   it. When the exit test passes (§8), squash it into one commit on `main`, the integration
   branch, and stop committing to it. `main` then holds one commit per version (Q45, Q63); the
   branch and its stage tags keep the history of the stages.
2. **Stages.** A stage is a group of commits with one exit check. When the check passes, put an
   annotated tag `v0.0.N` on the last commit of the stage. A tag marks a stage, not a release
   (RM-01). A stage that merges with another or drops gets no tag, and its number is not used
   again (Q46).
3. **Commits.** One commit holds one change:
   1. From the commit that adds the job `all` (v0.0.0), `all` passes in Docker on every commit.
   2. Code, its test and the document section it implements change in the same commit.
   3. The subject is imperative, at most 72 characters, and starts with the stage, for example
      `v0.0.2: Write the section directory`. A change to this plan starts with `v0.0.x:`.
   4. The body names the rule IDs and the test that checks the change.

   ```text
   v0.0.2: Write the section directory

   BC-02. Checked by WriterTest.sectionDirectory.
   ```

4. **Documents.** This README states the current plan only. When the plan changes, the README
   changes with it. Dates, decision history, open questions and engineering issues go to
   [sketchpad.md](sketchpad.md).

## 5. Development environment: Docker

- **[Decided]** Every build, test and proof job runs in a Docker container, on Windows and Linux
  hosts alike. Lean runs in Docker too. A host needs only Docker and git.
- **[Decided]** The Docker environment comes first: stage v0.0.0 builds it, and every later
  commit is checked in it.

### 5.1 Hosts

| Host | Needs |
|---|---|
| Windows | Docker Desktop running Linux containers (WSL 2 backend); git; Windows PowerShell 5.1 or PowerShell 7 |
| Linux | Docker Engine; git; a POSIX shell |

A host needs no JDK and no Lean. An IDE can use a host JDK to edit stage0, but only results from
the container count.

### 5.2 The image

`reflect-build` (BLD-05), built from `docker/build/Dockerfile`:

1. The base is `debian:trixie-slim`.
2. OpenJDK 21 comes from the Debian packages (Q02). Stage0 compiles with `javac --release 21`.
3. The Lean toolchain is unpacked to `/opt/lean` (LEAN-04). The Dockerfile pins its version and
   the SHA-256 of its release archive (Q51).
4. The tag is `reflect-build:` followed by the first 12 hexadecimal digits of the SHA-256 of the
   Dockerfile. A change to the Dockerfile gives a new tag, and the wrappers build the new image
   on their next run.

Only the image build uses the network. Jobs run without network access, so no job can depend on
a download.

### 5.3 Wrappers and jobs

`docker/dev.ps1 <job>` on Windows and `docker/dev.sh <job>` on Linux do the same steps:

1. Stop with a message if the Docker engine does not answer.
2. Build the image if its tag does not exist.
3. Run `sh docker/ci.sh <job>` in a new container. The checkout is mounted at `/work`, and the
   container has no network (`--network none`).
4. Return the exit code of the job.

| Job | Runs | From |
|---|---|---|
| `stage0` | `stage0/build.sh`: `javac` and `jar` into `out/stage0/` (BOOT-10) | v0.0.0 |
| `unit` | the Java unit tests of stage0 | v0.0.0 |
| `bytecode` | the `.rfla` suite: rejected files (v0.0.3), round trips (v0.0.4), programs on VM0 (v0.0.5) | v0.0.3 |
| `lean` | `lake build` in `formal/`, after a check that `formal/lean-toolchain` names the version in the image | v0.0.0 |
| `all` | builds stage0, runs `unit`, then `lean`; `bytecode` joins before `lean` in v0.0.3; stops at the first failure | v0.0.0 |
| `shell` | an interactive shell in the container | v0.0.0 |

`docker/ci.sh` is the only CI entry point: a hosted CI service runs `docker/dev.sh all` (Q53).

### 5.4 Lean

1. Run `docker/dev.ps1 lean` on Windows, or `docker/dev.sh lean` on Linux.
2. The job passes when `lake build` succeeds. The open gate obligations of FR-core,
   `preservation` and `progress` ([formal-model.md](../formal-model.md) §3), show as `sorry`
   warnings.
3. The build cache `formal/.lake/` stays in the checkout; git ignores it.

A Lean language server, used to edit proofs, also needs the toolchain from the image (Q54).
VS Code's **Dev Containers: Reopen in Container** uses
[the Dev Container definition](../../.devcontainer/devcontainer.json), the same Dockerfile and
`leanprover.lean4`, with `/opt/lean/bin` on the extension's path. The editor container is also
offline; install the extension from the host if VS Code cannot download it locally.
On a proxy host, add `--secret` and `id=reflect_extra_ca,src=<local PEM path>` to the Dev
Container's `build.options` locally, without committing the path or certificate. The host
wrappers handle `REFLECT_EXTRA_CA` automatically.

### 5.5 Differences between hosts

| Difference | Rule |
|---|---|
| Git on Windows can check out text files with CRLF line ends (`core.autocrlf`). Shell scripts then fail in the container, and byte comparisons of text files fail. | `.gitattributes` sets LF for every text file and marks `.rflc` and `.jar` files as binary. |
| On Linux, a container writes files into the mounted checkout as root. | `dev.sh` runs the container as the host user (`--user uid:gid`). |
| Git on Windows does not set executable bits. | Jobs start scripts with `sh <script>`, never as `./<script>`. |
| Windows and Linux paths differ. | The wrappers mount the checkout with `--mount type=bind`; jobs use `/work` paths only. |
| A TLS-intercepting proxy breaks HTTPS downloads in the image build. | If `REFLECT_EXTRA_CA` names a PEM file, the wrappers pass it to the build as a secret. The build trusts it only while it downloads, and it never enters an image layer or the repository. |

## 6. Layout, commands and test files

### 6.1 Files added in v0.0.x

```text
.gitattributes                        line ends and binary types (§5.5)
.gitignore                            out/
.devcontainer/devcontainer.json      Lean editing with the build image (§5.4)
docker/build/Dockerfile               the reflect-build image (§5.2)
docker/dev.ps1, docker/dev.sh         host wrappers (§5.3)
docker/ci.sh                          the jobs, run inside the container
stage0/build.sh                       javac and jar (BOOT-10)
stage0/src/reflect/stage0/lib/        the facade for reflect.collections; Nullable (BOOT-03)
stage0/src/reflect/stage0/bytecode/   model, reader, writer, assembler, disassembler
stage0/src/reflect/stage0/vm/         VM0 (BOOT-04)
stage0/src/reflect/stage0/tools/      command lines: rfla, rfld, vm0, test
stage0/test/                          unit tests and their harness (Q48)
tests/bytecode/format/                modules that are assembled and round-tripped, not run
tests/bytecode/lib/                   stub modules reflect.core and reflect.io (Q49)
tests/bytecode/run/<area>/            programs with expectations, run on VM0
tests/bytecode/invalid/               files that rfla must reject
out/                                  build output
```

- `reflect.stage0.bytecode` is R0-shaped Java (BOOT-03), so the transliterator can print it as
  Reflect (Q47). It uses only `reflect.stage0.lib` and the `java.lang` types that the
  transliterator maps. It works on byte arrays; file access stays in `reflect.stage0.tools`.
- `reflect.stage0.lib` has R0-shaped members over `java.util` (Q72). The transliterator maps it to
  `reflect.collections` and does not print it. It has no iteration over maps, and every sort uses
  a total order, so no output depends on hash order or sort stability (BOOT-05). Its annotation
  `Nullable` marks a nullable type; an unmarked type is non-null (Q73).
- `reflect.stage0.vm` and `reflect.stage0.tools` are plain Java. They leave with stage0 at v0.6
  (BOOT-15).

### 6.2 Commands

Inside the container (job `shell`):

| Command | Purpose |
|---|---|
| `java -jar out/stage0/stage0.jar rfla in.rfla -o out.rflc` | assemble |
| `java -jar out/stage0/stage0.jar rfld in.rflc` | disassemble to standard output |
| `java -jar out/stage0/stage0.jar vm0 run [--module-path P] [--main C] app.rflc [args]` | run a module on VM0 (VM-12) |
| `java -jar out/stage0/stage0.jar test tests/bytecode` | run the `.rfla` suite |

`vm0 run` takes its options before the module file, and gives the words after it to `main`. The
module path lists directories, which the platform's path separator divides, `:` in the container
(Q88). VM0 writes the console of the program and its own messages as UTF-8 bytes. It exits with
(Q89):

- the result of `int main`, the argument of `Runtime.exit`, or 0;
- 1 after an uncaught exception, and until v0.0.8 after a fault; VM0 prints the type and the
  message, as `Throwable.toString` gives them, such as
  `reflect.core.TypeLoadError: VM0 has no native ...`;
- 2 when its arguments are wrong, or when VM0 cannot start or run the module: a module that cannot
  be read, a module graph that does not resolve, no `main` class or method, or code that VM0 cannot
  run, such as an instruction outside R0. VM0 prints the reason after `vm0: `.

`rfla` prints its diagnostic after the path of the file, as `in.rfla:3:5: RFA0101: ...`, and
writes no module then. It exits with:

- 0 when it writes the module;
- 1 after a diagnostic;
- 2 when its arguments are wrong, or a file cannot be read or written.

`rfld` prints the canonical text of BC-25 to standard output, as UTF-8 bytes. Two kinds of file
have no text: a file that the reader rejects, and before v0.0.9 a module with a `GENERIC`
`MemberRef` (§7.5). For such a file, `rfld` prints the reason after the path of the file, as
`in.rflc: offset 0x0: ...`, and prints no text. It exits with:

- 0 when it prints the text;
- 1 when the file has no text;
- 2 when its arguments are wrong, the file cannot be read, or the text cannot be written.

`test` checks each `.rfla` file under the directory, in path order: a file in `invalid/` must fail
with its `expect-error` (§6.3), and any other file must hold none, assemble into a module that the
reader accepts, and round-trip (§7.5). A file in `run/` then runs on a new VM0 in the JVM of
`test`, with the files directly in `lib/` as its module path, where `lib/m.rfla` holds the module of
`requires m` (Q88, Q90). The low 8 bits of its exit code, as Linux gives them, must equal its
`expect-exit`, or else 1 with `expect-throws` and 0 without; the qualified name of the type of its
uncaught exception must equal its `expect-throws`, or there must be none without one; and its
standard output must equal its `expect-output` lines, byte for byte (TST-01). It prints `PASS` or
`FAIL` with the path of each file, then the counts, and exits with:

- 0 when every file passes;
- 1 when a file fails;
- 2 when its arguments are wrong, a file cannot be read, or the directory holds no `.rfla` file.

### 6.3 Test files

`.rfla` tests state their expectations in leading `//` comments, with the directives of TST-01:

| Directive | Meaning |
|---|---|
| `// expect-output: text` | one line of standard output, in order |
| `// expect-exit: n` | the exit code |
| `// expect-throws: Type` | an uncaught exception of that type |
| `// args: a b c` | arguments for `main` |
| `// expect-error: RFAnnnn @ line:col` | `rfla` rejects the file with this diagnostic |

- Assembler diagnostics have codes `RFA0001` and up, in the style of CMP-02, grouped by hundreds
  and listed in [bytecode.md](../bytecode.md) §8.2 (Q79). `rfla` stops at the first error, so a
  file in `invalid/` holds one `expect-error`.
- The value of a directive is the text after its colon and one space (TST-01). Only a file in
  `run/` holds `expect-output`, `expect-exit` or `expect-throws` (Q90). It holds at most one
  `expect-exit`, with a number from 0 to 255, and at most one `expect-throws`, with the qualified
  name of a type.
- A directory under `run/` is one multi-module test. Its expectations sit in its `test.rfla`, as
  for `.rfl` tests (TST-01).
- `rfla` ignores comments, and `rfld` does not print them.
- Every test module also goes through the round trip (§7, v0.0.4).

```text
// expect-output: Hello, world!
.module hello 0.0.0
.main hello.App
.requires reflect.core
.requires reflect.io

.class public final hello.App {
    .method public static void main(reflect.core.String[] args) {
        ldstr    "Hello, world!"
        call     static void reflect.io.Console::println(reflect.core.Object?)
        ret
    }
}
```

The `.main` directive records the descriptor's `main` class (VM-12); v0.0.1 adds it.

## 7. Stages

Each stage states its goal, its design, its commits and its exit check. A numbered commit is one
commit, or a few if the change is too large for one review. A commit's check is the test it adds,
run by `all`.

| Stage | Goal | Exit check |
|---|---|---|
| v0.0.0 | Docker environment and skeleton | `all` passes on both hosts |
| v0.0.1 | exact encoding | the document test passes |
| v0.0.2 | model, writer and reader | write, read and write give identical bytes; defects are rejected |
| v0.0.3 | assembler | files in `format/` assemble; files in `invalid/` are rejected |
| v0.0.4 | disassembler and round trip | the round trip holds for every test file |
| v0.0.5 | VM0 loader and core interpreter | `run/core/` passes; the roadmap's exit test passes |
| v0.0.6 | VM0 objects and dispatch | `run/objects/` passes |
| v0.0.7 | VM0 arrays, strings and boxing | `run/arrays/` and `run/strings/` pass |
| v0.0.8 | VM0 exceptions | `run/exceptions/` passes |
| v0.0.9 | VM0 reified types and generics | `run/generics/` and the integration test pass; §8 |

### 7.1 v0.0.0: Docker environment and skeleton

**Goal.** One command builds and checks the repository in Docker, on Windows and on Linux, Lean
included.

**Design.**

- The image, wrappers and jobs of §5.
- `stage0/build.sh` compiles `stage0/src` and `stage0/test` with `javac --release 21` and packs
  `out/stage0/stage0.jar` with `jar`. There is no build tool (BOOT-10).
- The test harness (Q48): a `@Test` annotation; a runner that finds test classes by name
  (`*Test`) and runs their annotated static methods; assertions that throw `AssertionError`; a
  summary line; a non-zero exit code when a test fails.
  Tests live in `stage0/test`, outside the production JAR. The `unit` job builds them first.
  The runner sorts classes and methods, requires public static parameterless `void` tests, and
  rejects an empty suite. Its self-tests invoke passing, failing and invalid fixtures in child
  JVMs to check actual summaries and exit codes.

**Commits.**

1. Add `.gitattributes` and renormalize the working tree. Check: on Windows, `git ls-files --eol`
   shows `w/lf` for every text file, and the index does not change.
2. Add the root `.gitignore` for `out/`.
3. Add the Dockerfile with OpenJDK 21. Check: `java -version` in the image prints version 21.
4. Add the `REFLECT_EXTRA_CA` build secret. Check: the image builds on a host behind a
   TLS-intercepting proxy, and its trust store does not contain the extra CA.
5. Add the Lean toolchain to the image. Check: `lean --version` in the image prints the pinned
   version.
6. Add `docker/ci.sh` with the job `lean`. Check: `lake build` passes with the two known `sorry`
   warnings.
7. Add `docker/dev.sh`. Check: `docker/dev.sh lean` passes on Linux.
8. Add `docker/dev.ps1`. Check: `docker/dev.ps1 lean` passes in Windows PowerShell 5.1.
9. Add `stage0/build.sh`, a `reflect.stage0.tools.Main` that prints the usage, and the job
   `stage0`. Check: `java -jar out/stage0/stage0.jar` prints the usage.
10. Add the harness, its self-tests and the job `unit`. Check: a passing fixture and a failing
    fixture give the expected summaries and exit codes.
11. Add the jobs `all` and `shell`. Check: `all` passes on Windows and on Linux.
12. If Q54 is accepted, add the Dev Container definition.

**Exit check.** In a fresh clone, `docker/dev.ps1 all` passes on Windows and `docker/dev.sh all`
passes on Linux; a second run reuses the image. Tag `v0.0.0`.

### 7.2 v0.0.1: Encoding

**Goal.** Every byte of a module is specified for the instructions and tables of v0.0.x. The
numbers exist once in code, and a test shows that [bytecode.md](../bytecode.md) states the same
numbers.

**Design.**

- bytecode.md gets exact tables: opcode numbers for the whole instruction set, inside the family
  ranges of BC-04 (Q67); signature codes (BC-03); table numbers and fixed column widths (BC-11) for the tables
  that v0.0.x uses; section contents, the ancillary bit and the content hash (BC-02, BC-20,
  BC-22); method bodies (BC-17); exception regions (BC-18); stack maps (BC-19); the DEBUG
  section, keyed by method token; and the `.rfla` grammar in EBNF (BC-08).
- A new rule in bytecode.md defines the canonical form: LEB128 values are minimal; padding,
  reserved fields and undefined flag bits are zero; sections are sorted by ID; each row has the
  size that its table and the formatMinor give; tables are sorted by their keys; heap entries are
  unique, referenced and in canonical order. A module that is not canonical is invalid. Each valid
  module then has exactly one encoding, so the round trip of BC-08 is exact by construction.
- The `Module` table gets a column for the descriptor's `main` class, which VM-12 reads, and
  `.rfla` gets the `.main` directive.
- `reflect.stage0.bytecode` declares the numbers as constants: `Opcode`, `SigCode`, `TableId`
  with the column counts, `Flags` for the flags and kinds of columns and exception regions,
  `Header`, `SectionId`, `VerificationType` and the limits of BC-21, `Limits`.
- The opcode table marks the instructions of the R0 subset, which VM0 implements in v0.0.x.
- A unit test reads the tables from bytecode.md and compares them with the constants.

**Commits.**

1. The opcode table, `Opcode` and the document test.
2. Signature codes and `SigCode`.
3. Table numbers, fixed column widths, the TABLES directory and `TableId`.
4. The header, the sections, the ancillary bit and `SectionId`, with the reader actions of BC-20;
   the layouts of method bodies, exception regions, stack maps and the DEBUG section.
5. The canonical form, and the limits of BC-21 with `Limits`; the `main` column and `.main`.
6. The `.rfla` grammar, `.section` included.

The check of each commit is the document test, run by `unit`.

**Exit check.** The tables cover every instruction, and every table that v0.0.x uses; `all`
passes. Tag `v0.0.1`.

### 7.3 v0.0.2: Model, writer and reader

**Goal.** Modules are built in memory, written deterministically, and read back with full
checks.

**Design.**

- First comes the facade `reflect.stage0.lib`: the list and map types that the package needs,
  with the member names of LIB-04 over `java.util` (Q47, Q72), and `Nullable` (Q73).
- The model holds the tables as lists of rows, the heaps, and method bodies as lists of
  instructions with symbolic operands (labels and references), not as bytes.
- The writer sorts the tables by their keys, assigns rows and tokens, deduplicates the heaps and
  lays out the code. LEB128 operands make the size of an instruction depend on offsets and
  tokens, so the layout repeats until no size changes; sizes only grow, so the loop ends. Then
  the writer aligns the sections and computes the content hash over the header's versions and
  flags and the critical sections (BC-22, BOOT-05). Every offset it writes is relative to the
  section it points into. Content over a limit of BC-21 gives a diagnostic, never a truncated
  module.
- The reader checks the magic, the format version and flags (BC-20), the directory, bounds,
  alignment, row sizes, the limits (BC-21) and token ranges, and that each branch target is the
  start of an instruction. Then it writes the model again, and it accepts the module only if the
  bytes are equal (the canonical form of §7.2). An unknown ancillary section and a stale DEBUG
  stay in the model as opaque bytes, which tools write back unchanged; the rewrite check leaves
  them out.
- SHA-256 and the UTF-8 and UTF-16LE codecs are written in R0-shaped Java. Unit tests compare
  them with `MessageDigest` and `String.getBytes`.
- A table arrives with the stage that first needs it. v0.0.2 covers `Module`, `ModuleRef`,
  `TypeDef`, `TypeRef`, `Field`, `Method`, `Param` and `MemberRef`.
- Signatures cover every type code of BC-03, with its structural rules (Q74). `INSTANTIATION`
  comes with `MethodSpec`.

**Commits.**

1. Facade lists and maps, and `Nullable`.
2. LEB128 and SLEB128, with tests at each byte-length boundary and for padded encodings.
3. SHA-256, tested against `MessageDigest` with the empty input, `abc`, and one MiB of seeded
   random bytes.
4. The UTF-8 and UTF-16LE codecs, tested with surrogate pairs and with unpaired surrogates, which
   BC-02 allows in LITERALS.
5. The header and the section directory, with tests for another format version, an unknown flag
   bit, an unknown critical section, and an unknown ancillary section that is written back byte
   for byte (BC-20).
6. The three heaps and their deduplication.
7. The tables listed above, in fixed-width rows.
8. Signature blobs, for every type code of BC-03 (Q74).
9. The CODE section: instruction encoding and the layout loop, tested with branches across
   LEB128 size boundaries.
10. Exception regions, and stack maps stored as given (Q50).
11. The DEBUG section, keyed by method token, starting with the content hash and listing each
    source file with its SHA-256. Tests: a stale DEBUG is treated as absent; removing DEBUG gives
    a module that the reader accepts with the same content hash; moving a line changes DEBUG
    only (BC-22).
12. The canonical check in the reader: it rejects a module that the writer does not give back,
    at the first byte that differs, and leaves the sections kept as bytes out of the rewrite.
    Tests: a branch layout that is not the smallest, which no other check finds, alone and with a
    stale DEBUG. The tests of the commits before reject each other defect at its offset: a padded
    LEB128 value, unsorted rows, a wrong row size, an unreferenced heap entry, a wrong content
    hash, a branch into the middle of an instruction, a value over a limit.

**Exit check.** For every module that the unit tests build, write, read and write again gives
identical bytes, and every defect is rejected with a message that names its offset. Tag
`v0.0.2`.

### 7.4 v0.0.3: Assembler

**Goal.** `rfla` turns `.rfla` text into a module.

**Design.**

- A lexer and a recursive-descent parser for the grammar of §7.2. Diagnostics carry a code
  (`RFA0001` and up), a line and a column (§6.3), which count code points from 1, a tab as one
  column. `rfla` stops at the first diagnostic (Q79). Content over a limit of BC-21 is a
  diagnostic.
- Tables added: `Export` and `InterfaceImpl`, for `.exports`, `.opens` and `implements` (Q75).
  The parser also reads the syntax of the tables that come later, `Constant`, `Override`,
  `TypeSpec`, `GenericParam` and `MethodSpec`, and `rfla` rejects it with one code until their
  stage.
- Names resolve to definitions in the module, or to `TypeRef` and `MemberRef` rows that are
  created at first use. A `TypeRef` belongs to the required module whose name is its namespace or
  the longest prefix of it at a dot, or else to the module in brackets before it (Q76). Labels
  become offsets in the writer.
- A reference to a member or a value type of another module states its signature: `static`, the
  return type or field type, `valuetype`, and `...` for a variadic method (Q69, Q78). `rfla` reads
  no other module; the short form serves only the members and types of the module it assembles.
- `.unsafe` sets the header flag `UNSAFE` (Q78).
- `.maxstack` is used when present. Otherwise `rfla` computes the depth from the Stack column of
  §5.1, call signatures included (Q77):
  - the first instruction has depth 0, a catch handler starts at 1 and a finally handler at 0;
  - `leave` empties the stack, and `br`, `leave`, `ret`, `throw`, `rethrow` and `endfinally` do not
    go on to the next instruction;
  - two paths into one instruction have the same depth, and no instruction pops more than the
    stack holds; otherwise `rfla` reports a diagnostic;
  - instructions that no path reaches do not count.
- `.stackmap` directives are stored as written (Q50).
- Names are spelled as GEN-12 states; primitive keywords and type-parameter names are accepted
  too (Q52).

**Commits.**

1. The `Export` table: model, writer and reader (Q75).
2. The `InterfaceImpl` table (Q75).
3. The Stack column of §5.1, and the stack effects in `Opcode` (Q77).
4. The lexer, and the codes of bytecode.md §8.2.
5. Module directives: `.module`, `.main`, `.requires`, `.exports`, `.opens`, `.unsafe`,
   `.section`.
6. Types, with the scope of a `TypeRef` (Q76); `.class` with its kind, modifiers, `extends` and
   `implements`; `.field`.
7. `.method` with its signature, and `.locals`.
8. Instructions: without operands, with immediates, with references, with labels, and `switch`;
   the stack depth.
9. `.try`, `.catch`, `.finally`, `.maxstack`, `.source`, `.line` and `.stackmap`.
10. The command `rfla`.
11. The command `test`, first part: it finds the test files, checks that each file in `invalid/`
    fails with its `expect-error`, and that each other file assembles into a module that the
    reader accepts. The job `bytecode` runs it, and `all` runs it before `lean`.

**Exit check.** Every file in `format/` assembles, and every file in `invalid/` fails with its
expected code and position. Tag `v0.0.3`.

### 7.5 v0.0.4: Disassembler and round trip

**Goal.** `rfld` prints canonical `.rfla`, and the round trip of BC-08 holds for every test
module.

**Design.**

- The output follows the order of the binary: one directive or instruction per line, four spaces
  of indentation, LF line ends, labels numbered in code order, and names spelled as GEN-12
  states. BC-25 states the canonical text in full (Q86).
- Every module that the reader accepts gets a text, so the format closes the gaps of
  [sketchpad.md](sketchpad.md) A.2 items 12 to 17:
  - a name is an `Ident` of [bytecode.md](../bytecode.md) §8.1, and a module name, a namespace
    and an `Export` target a `QName` (Q83);
  - `@` before a name makes it a name, never a word of the grammar, and `rfld` writes it before a
    type name or a module name of one segment that is such a word (Q82);
  - a primitive's struct in brackets names its `TypeDef` or `TypeRef` row, which a token may name;
    brackets that hold the module's own name name a type of the module, so a module does not
    require itself (Q81, MOD-03);
  - `BYREF` stands only at the top of a type that a signature lists (Q84);
  - no two fields of a type share a name and a type, and no two methods a name and a signature
    (Q85);
  - `.typeref` and `.memberref` declare the `TypeRef` and `MemberRef` rows that nothing else names
    (Q80).
- The one exception is a `GENERIC` `MemberRef`, which has no text until `MethodSpec` arrives
  (A.2 item 11), so `rfld` reports it until v0.0.9.
- The round trip of one test file:
  1. Assemble the file to bytes A.
  2. Disassemble A to text T.
  3. Assemble T to bytes B.
  4. Disassemble B to text T2.
  5. The test passes when A equals B and T equals T2 (TST-09).
- The job `bytecode` runs the round trip on every file in `format/`, `lib/` and `run/`.

**Commits.**

1. Names that are identifiers (Q83).
2. `@` before a name (Q82).
3. Brackets for the module itself and for a primitive's struct, and a module that requires
   itself (Q81).
4. `BYREF` only at the top of a signature (Q84).
5. One member per name and signature (Q85).
6. `.typeref` and `.memberref` (Q80).
7. Module directives, `.section` for an unknown ancillary section included, and type names.
8. Members and signatures.
9. Code, labels, exception regions, stack maps and line numbers.
10. The command `rfld`.
11. The round trip in the command `test`.

**Exit check.** The round trip holds for every test file, on both hosts. Tag `v0.0.4`.

### 7.6 v0.0.5: VM0 loader and core interpreter

**Goal.** VM0 loads modules and runs static code over primitives and strings, with natives.

**Design.**

- Loader (VM-12, VM-13): the module path is a list of directories of `.rflc` files. The module of
  `requires m` is the file `m.rflc`, which exactly one directory holds, and the module in it is
  named `m` (MOD-03, Q88). A `requires static` module that no directory holds is skipped (MOD-01).
  VM0 resolves the graph from the main module at start-up and rejects a cycle; types and methods
  load at first use.
- Natives (UNS-03, LIB-03): Java methods in a fixed table, keyed by qualified signature. A native
  declared outside the trusted modules is a load error.
- Interpreter (BOOT-04): one Java call per Reflect call, and a `switch` over the opcodes. Locals
  and the operand stack hold Java values. Each local starts at the zero value of its type, and
  the count of a 64-bit shift is an `i32` (BC-17, BC-04, Q91).
- Floating point (NUM-04, MA-01, Q92): an arithmetic instruction or a conversion between `f32` and
  `f64` that gives a NaN gives the canonical NaN, `0x7FC00000` or `0x7FF8000000000000`; `neg`
  changes only the sign bit; loads, stores and calls keep every bit. Java's arithmetic gives other
  NaNs ([sketchpad.md](sketchpad.md) D.6), so VM0 replaces them.
- Entry: the `main` class comes from `.main` or `--main`. `main` is a `public static` method with
  one `reflect.core.String[]` parameter, and returns `void` or `int` (ex §2.4); the `main` class has
  one such method. The run exits with the result of `int main`; `Runtime.exit(int)` ends it at the
  call, with its argument as the exit code (§6.2). Until arrays arrive (v0.0.7), `main` receives its
  arguments as a Java `String[]`, which no instruction reads before then. `main` runs on a thread
  with a stack of 256 MiB, so deep recursion in Reflect does not overflow the JVM stack first: in
  the build image, the JVM's default stack of 1 MiB holds about 2,000 calls of VM0, and 256 MiB at
  least 860,000 (Q93, [sketchpad.md](sketchpad.md) D.6). Until v0.0.8, an overflow of that stack is
  the fault `reflect.core.StackOverflowError` (Q94).
- Stub modules (Q49): `reflect.core` with `Object`, `String` and `Runtime`, and `reflect.io` with
  `Console`, written in `.rfla` with the native names of ex §2.11 (`Console.write(int, String)`).
  `String.valueOf` turns `int`, `long`, `float` and `double` into Java's text (NUM-05, Q87).
- Instructions: `nop`, `ldc.*`, `ldstr`, `dup` and `pop`; arguments and locals; arithmetic,
  bitwise operations, shifts and comparisons over `i32`, `i64`, `f32` and `f64`; conversions
  among the primitive types of R0 (`byte`, `short`, `char`, `int`, `long`, `float`, `double`);
  branches; `switch`; static `call`; `ret`.
- VM0 exits with 2 when it cannot start or cannot run the module: a module graph that does not
  resolve, no `main` class or method, or an instruction outside R0 or before the stage that brings
  it. It prints the reason after `vm0: ` (Q89).
- Until v0.0.8, a fault that later throws an exception, such as an integer division by zero,
  stops VM0 with the exception's type name and exit code 1. VM0 prints the type and a message as
  `Throwable.toString` gives them (Q89). Tests written with `expect-throws` then stay valid when
  v0.0.8 makes the exceptions real.
- `test` assembles the files of `lib/`, and runs each file of `run/` on a new VM0 in the JVM of
  `test`, with those modules as the module path. The output, the exit code and the uncaught
  exception meet the directives of §6.3 (Q90).
- VM0 counts the opcodes it executes, and `test` reports the R0 opcodes that no program executed
  (§8). The report fails no test until v0.0.9.

**Commits.**

1. The stub modules: `reflect.core` with `Object` and `String`, and `reflect.io` with `Console`.
   Later commits add the natives that their tests need, and commit 12 adds `Runtime`.
2. The module path and `requires` resolution (Q88), with tests for a missing module, a duplicate
   module, a module of another name and a cycle.
3. Frames, the interpreter loop, `ldstr`, `ldc.i4`, `ldarg`, static calls of methods and natives,
   and `ret`; natives only in the trusted modules.
4. The command `vm0 run`, with its exit codes (Q89).
5. `expect-output` in `test`, which runs the files of `run/` (Q90). Check: `run/core/hello.rfla`.
6. Locals at zero (Q91), `starg`, `ldloc`, `stloc`, `nop`, `dup`, `pop` and `i32` arithmetic, with
   `String.valueOf(int)` (Q87).
7. `i64`, `f32` and `f64` arithmetic, with the `i32` count of a 64-bit shift (Q91), and
   `String.valueOf` of `long`, `float` and `double` (Q87).
8. Comparisons and conversions, with the IEEE and saturation cases of BC-04.
9. The canonical NaN (MA-01, Q92).
10. Branches and `switch`. Check: programs for factorials, Fibonacci numbers and Collatz
    sequences.
11. Static calls with arguments and results; recursion, on a thread with a stack of 256 MiB
    (Q93), whose overflow is the fault `reflect.core.StackOverflowError` (Q94).
12. Exit codes: `int main`, `Runtime.exit` and `expect-exit`.
13. Faults reported by exception name, and `expect-throws`.
14. The opcode count and its report.

**Exit check.** The programs in `run/core/` pass. From here on, the roadmap's exit test passes
with `run/core/hello.rfla`. Tag `v0.0.5`.

### 7.7 v0.0.6: VM0 objects and dispatch

**Goal.** VM0 runs classes, interfaces, fields, constructors, virtual and interface calls, type
tests and class initialization.

**Design.**

- A run-time type per class (VM-03): field slots, a vtable built from the `Override` rows
  (BC-12), an interface table, static storage and the initialization state.
- An object is a Java object that holds its run-time type and an array of slots (BOOT-04).
- `callvirt` becomes a direct call when the target is not virtual (BC-06). Default methods of
  interfaces are found through the interface table.
- Class initialization follows VM-07: at first active use, superclasses first, with the states
  not started, running, done and failed. A nested request from the running initializer sees
  default values.
- Frames record the receiver class `R` of static calls (BC-05, VM-20). No instruction reads it
  before v0.4.
- `castclass` to a non-null type throws `NullPointerException` for `null`, and `instanceof` is
  false for `null` (NUL-05).
- Loading checks `final`, `sealed` and `permits`, that every `Override` row names an existing
  method, and that the override's return type converts to that method's (BC-12, VM-13). An
  override that returns a subtype fills the base slot itself (MEM-02).
- Final fields follow BC-23. When a `stfld` or `stsfld` links, VM0 checks that its method may
  store to the field; when a `stfld` of a final runs, it checks that the target is the
  constructor's `this`. A class constructor runs only on a new object. A violation is a
  `TypeLoadError`.
- `ceq.ref` compares identity, and two boxes by class and contents (DAT-09). Boxes arrive in
  v0.0.7, which tests that path.
- Tables added: `Override`, `Constant`, and `TypeSpec` for nullable types (`NULLABLE`, BC-03).
  `InterfaceImpl` comes from v0.0.3 (Q75).

**Commits.**

1. Type loading and layout; `newobj`, constructors, `ldfld` and `stfld`.
2. Static fields and class initialization, with tests of order, re-entry and failure.
3. Non-virtual instance calls; `callvirt` and vtables, with an override that returns a subtype
   of its base's return type (MEM-02).
4. Interfaces, default methods and interface tables.
5. `ldnull`, `brnull`, `brnonnull`, `ceq.ref`, `instanceof` and `castclass`, with the null
   rules.
6. `ldconst` and the `Constant` table.
7. The load-time checks, and the final-field and constructor rules of BC-23, with one test per
   violation: a store from another method, a store to another object, a store to a `const`, and
   a constructor called on a finished object; and an override that widens its return type
   (BC-12).

**Exit check.** The programs in `run/objects/` pass. Tag `v0.0.6`.

### 7.8 v0.0.7: VM0 arrays, strings and boxing

**Goal.** VM0 runs arrays with reified element types, the string operations that R0 needs, and
boxing.

**Design.**

- An array is a Java array together with its run-time array type, which holds the element type
  and its nullability (VM-02, NUL-01). Array types are `TypeSpec` rows with `ARRAY` signatures
  (BC-03).
- Arrays are invariant (NUL-07): a reference `stelem` checks only the index
  (`IndexOutOfBoundsException`), and `castclass` and `instanceof` of an array type compare the
  exact array type, nullability included.
- `box` and `unbox` work for primitives and enums; an enum value is its backing integer on the
  stack (DAT-05). Boxes have no identity (DAT-09): `ceq.ref` on two boxes compares their classes
  and bit patterns, and `unbox` copies the value out.
- String operations are natives of the stub `reflect.core`. The set grows with the tests that
  need it; v0.1 fixes it with the stdlib sources.
- `main` receives its arguments as a `String[]`.

**Commits.**

1. `newarr`, `ldlen`, and `ldelem` and `stelem` for primitive kinds.
2. Reference arrays, and exact casts and type tests of array types (NUL-07).
3. Index checks.
4. `box` and `unbox`, and `ceq.ref` on boxes, with tests for two boxes of one value, an enum
   and an `int` with the same backing value, `0.0` and `-0.0`, and NaN: two NaN results of
   arithmetic give equal boxes, and a NaN with a payload gives another (DAT-09, MA-01).
5. String natives: length, characters, comparison, and conversion of `char` and `boolean` to
   strings. `concat` and the conversion of numbers arrive in v0.0.5 (Q87).
6. `main` arguments and the `args` directive.

**Exit check.** The programs in `run/arrays/` and `run/strings/` pass. Tag `v0.0.7`.

### 7.9 v0.0.8: VM0 exceptions

**Goal.** Exceptions are Reflect objects that programs throw and catch, and that VM0 raises.

**Design.**

- `throw`, `rethrow`, `leave` and `endfinally`. Exception regions are searched innermost first in
  each frame (BC-18, VM-06), and `finally` runs on every exit from its `try` (EXC-07).
- A Java exception carries the Reflect `Throwable` through the Java frames of the interpreter
  (BOOT-04).
- VM0 raises the exceptions of EXC-05 that the R0 subset can produce: `NullPointerException`,
  `ArithmeticException`, `IndexOutOfBoundsException`, `ClassCastException`,
  `ExceptionInInitializerError` and `TypeLoadError`. The JVM's `StackOverflowError` and
  `OutOfMemoryError` become the Reflect ones (BOOT-04). These exceptions replace the stops of
  v0.0.5.
- The first use of a class whose static initializer fails throws `ExceptionInInitializerError`;
  every later use throws a new one with the same cause (VM-07, MEM-06).
- A stack trace is recorded at construction as pairs of method and offset, and decoded through
  the DEBUG line table only when printed (EXC-06, VM-21). An uncaught exception prints its trace
  to standard error, and VM0 exits with code 1.

**Commits.**

1. The exception classes of the stub `reflect.core`: `Throwable`, `Exception`,
   `RuntimeException`, `Error`, and the subset above.
2. `throw`, the uncaught path, and `expect-throws` with real exceptions.
3. `catch` regions and `leave`; nested regions and `rethrow`.
4. `finally` regions and `endfinally`: exits by `leave` and by an exception, and an exception
   thrown inside `finally`.
5. The exceptions that VM0 raises, one test each.
6. Stack overflow, tested with unbounded recursion.
7. Initializer failure and `ExceptionInInitializerError`.
8. Stack traces with line numbers, and with offsets only when DEBUG is absent (VM-21).

**Exit check.** The programs in `run/exceptions/` pass, and the `expect-throws` tests of earlier
stages still pass. Tag `v0.0.8`.

### 7.10 v0.0.9: VM0 reified types and generics

**Goal.** Every instantiation is a run-time type of its own with its own `Class<T>` object, and
generic code reads its exact type arguments.

**Design.**

- Tables added: `MethodSpec`, with the `INSTANTIATION` signature, `GenericParam` and
  `GenericConstraint`. VM0 runs code whose signatures use `GENERICINST`, `VAR` and `MVAR`
  (BC-03), which the codec reads from v0.0.2 (Q74).
- One run-time type per instantiation, nullability included, created at first use (BOOT-04,
  GEN-02, GEN-08), with its own statics and initialization. `Box<String>.count` and
  `Box<String?>.count` are two fields (VM-03).
- Every run-time type owns its `Class<T>` object from creation (BC-15); `ldtype` and `getclass`
  push it.
- The type context (GEN-09, VM-17): instance code reads the run-time type of `this`, static code
  reads `R`, and a generic method receives a hidden method-instantiation argument. VM0 runs the
  shared bytecode for every instantiation.
- `instanceof`, `castclass`, `newarr`, `ldelem`, `stelem` and field access accept type
  parameters; `(T) o` checks for null when `T` is instantiated with a non-null type (NUL-05).
- A type test with wildcards checks each type argument (GEN-04).
- `call.constrained` calls a member of a type parameter's bound without boxing (BC-16), for
  example `compareTo` when `T` is `int`. It takes a reference to the value, made by `ldarga` or
  `ldloca` (Q68).
- An integration test closes the stage. It has two modules: a library with a generic list and
  its iterator, and an application with a sealed hierarchy, virtual dispatch, string building
  and exceptions.

**Commits.**

1. Instantiated run-time types and their statics. Check: `Box<String>.count` and
   `Box<String?>.count` are two fields.
2. `Class<T>` objects, `ldtype` and `getclass`.
3. `VAR` in instance code.
4. `MVAR`, `MethodSpec` and the method-instantiation argument.
5. `R` as the type context of static code.
6. Type tests, casts and arrays over type parameters, with the null rules.
7. Type tests with wildcards.
8. `ldarga`, `ldloca` and `call.constrained`.
9. The integration test.

**Exit check.** The programs in `run/generics/` and the integration test pass, and the exit test
of v0.0.x (§8) passes. Tag `v0.0.9`.

## 8. Exit test

v0.0.x is done when one commit on its branch meets all of these conditions:

1. `docker/dev.ps1 all` passes on a Windows host, and `docker/dev.sh all` passes on a Linux
   host.
2. `all` runs:
   1. the stage0 build;
   2. the unit tests, with the document test of §7.2;
   3. the `.rfla` suite: every file in `format/`, `lib/` and `run/` round-trips; every program in
      `run/` meets its expectations on VM0; every file in `invalid/` fails with its expected
      diagnostic;
   4. the Lean job.
3. The opcode report of `test` shows that the suite executes every instruction that the opcode
   table marks as R0 (§7.2).

The roadmap's exit test is the case `run/core/hello.rfla` of item 2.3.

When the exit test passes:

1. The tag of the last remaining stage marks the commit (`v0.0.9` if that stage remains).
2. `v0.0.x` is squashed into one commit on `main` (§4).
3. `v0.1.x` starts from that commit.
