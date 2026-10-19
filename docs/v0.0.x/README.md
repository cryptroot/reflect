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
   it. When the exit test passes (§8), merge it into the integration branch with a merge commit,
   and stop committing to it.
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
| `all` | `stage0`, `unit`, `bytecode` and `lean`, in this order; it stops at the first failure | v0.0.0 |
| `shell` | an interactive shell in the container | v0.0.0 |

`docker/ci.sh` is the only CI entry point: a hosted CI service runs `docker/dev.sh all` (Q53).

### 5.4 Lean

1. Run `docker/dev.ps1 lean` on Windows, or `docker/dev.sh lean` on Linux.
2. The job passes when `lake build` succeeds. The open gate obligations of FR-core,
   `preservation` and `progress` ([formal-model.md](../formal-model.md) §3), show as `sorry`
   warnings.
3. The build cache `formal/.lake/` stays in the checkout; git ignores it.

A Lean language server, used to edit proofs, also needs the toolchain from the image (Q54).

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
docker/build/Dockerfile               the reflect-build image (§5.2)
docker/dev.ps1, docker/dev.sh         host wrappers (§5.3)
docker/ci.sh                          the jobs, run inside the container
stage0/build.sh                       javac and jar (BOOT-10)
stage0/src/reflect/stage0/lib/        the facade that mirrors reflect.collections (BOOT-03)
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

- `reflect.stage0.lib` and `reflect.stage0.bytecode` are R0-shaped Java (BOOT-03), so the
  transliterator can print them as Reflect (Q47). The bytecode package works on byte arrays;
  file access stays in `reflect.stage0.tools`.
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

`vm0 run` exits with:

- the result of `int main`, the argument of `Runtime.exit`, or 0;
- 1 after an uncaught exception;
- 2 when a module cannot be read.

### 6.3 Test files

`.rfla` tests state their expectations in leading `//` comments, with the directives of TST-01:

| Directive | Meaning |
|---|---|
| `// expect-output: text` | one line of standard output, in order |
| `// expect-exit: n` | the exit code |
| `// expect-throws: Type` | an uncaught exception of that type |
| `// args: a b c` | arguments for `main` |
| `// expect-error: RFAnnnn @ line:col` | `rfla` rejects the file with this diagnostic |

- Assembler diagnostics have codes `RFA0001` and up, in the style of CMP-02, listed in
  [bytecode.md](../bytecode.md) §8.
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
        call     reflect.io.Console::println(reflect.core.Object?)
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

- bytecode.md gets exact tables: opcode numbers for the whole instruction set, inside the ranges
  of BC-04; signature codes (BC-03); table numbers and column encodings (BC-11) for the tables
  that v0.0.x uses; section contents (BC-02); method bodies (BC-17); exception regions (BC-18);
  stack maps (BC-19); the DEBUG section; and the `.rfla` grammar in EBNF (BC-08).
- A new rule in bytecode.md defines the canonical form: LEB128 values are minimal; padding and
  reserved fields are zero; tables are sorted by their keys; heap entries are unique, referenced
  and in canonical order. A module that is not canonical is invalid. Each valid module then has
  exactly one encoding, so the round trip of BC-08 is exact by construction.
- The `Module` table gets a column for the descriptor's `main` class, which VM-12 reads, and
  `.rfla` gets the `.main` directive.
- `reflect.stage0.bytecode` declares the numbers as constants: `Opcode`, `SigCode`, `TableId` and
  `SectionId`.
- The opcode table marks the instructions of the R0 subset, which VM0 implements in v0.0.x.
- A unit test reads the tables from bytecode.md and compares them with the constants.

**Commits.**

1. The opcode table, `Opcode` and the document test.
2. Signature codes and `SigCode`.
3. Table numbers, column encodings and `TableId`.
4. The header, the sections and `SectionId`; the layouts of method bodies, exception regions,
   stack maps and the DEBUG section.
5. The canonical form; the `main` column and `.main`.
6. The `.rfla` grammar.

The check of each commit is the document test, run by `unit`.

**Exit check.** The tables cover every instruction, and every table that v0.0.x uses; `all`
passes. Tag `v0.0.1`.

### 7.3 v0.0.2: Model, writer and reader

**Goal.** Modules are built in memory, written deterministically, and read back with full
checks.

**Design.**

- First comes the facade `reflect.stage0.lib`: the list and map types that the package needs,
  with the member names of LIB-04 (Q47).
- The model holds the tables as lists of rows, the heaps, and method bodies as lists of
  instructions with symbolic operands (labels and references), not as bytes.
- The writer sorts the tables by their keys, assigns rows and tokens, deduplicates the heaps and
  lays out the code. LEB128 operands make the size of an instruction depend on offsets and
  tokens, so the layout repeats until no size changes; sizes only grow, so the loop ends. Then
  the writer aligns the sections and computes the content hash (BC-02, BOOT-05).
- The reader checks the magic, versions, directory, bounds, alignment and token ranges, and that
  each branch target is the start of an instruction. Then it writes the model again, and it
  accepts the module only if the bytes are equal (the canonical form of §7.2).
- SHA-256 and the UTF-8 and UTF-16LE codecs are written in R0-shaped Java. Unit tests compare
  them with `MessageDigest` and `String.getBytes`.
- A table arrives with the stage that first needs it. v0.0.2 covers `Module`, `ModuleRef`,
  `TypeDef`, `TypeRef`, `Field`, `Method`, `Param` and `MemberRef`.

**Commits.**

1. Facade lists and maps.
2. LEB128 and SLEB128, with tests at each byte-length boundary and for padded encodings.
3. SHA-256, tested against `MessageDigest` with the empty input, `abc`, and one MiB of seeded
   random bytes.
4. The UTF-8 and UTF-16LE codecs, tested with surrogate pairs and with unpaired surrogates, which
   BC-02 allows in LITERALS.
5. The header and the section directory.
6. The three heaps and their deduplication.
7. The tables listed above.
8. Signature blobs.
9. The CODE section: instruction encoding and the layout loop, tested with branches across
   LEB128 size boundaries.
10. Exception regions, and stack maps stored as given (Q50).
11. The DEBUG section.
12. The canonical check in the reader, with one test per defect: a padded LEB128 value, unsorted
    rows, an unreferenced heap entry, a wrong content hash, a branch into the middle of an
    instruction.

**Exit check.** For every module that the unit tests build, write, read and write again gives
identical bytes, and every defect is rejected with a message that names its offset. Tag
`v0.0.2`.

### 7.4 v0.0.3: Assembler

**Goal.** `rfla` turns `.rfla` text into a module.

**Design.**

- A lexer and a recursive-descent parser for the grammar of §7.2. Diagnostics carry a code
  (`RFA0001` and up), a line and a column (§6.3).
- Names resolve to definitions in the module, or to `TypeRef` and `MemberRef` rows that are
  created at first use. Labels become offsets in the writer.
- `.maxstack` is used when present. Otherwise the assembler computes the depth from the stack
  effect of each instruction, call signatures included.
- `.stackmap` directives are stored as written (Q50).
- Names are spelled as GEN-12 states; primitive keywords and type-parameter names are accepted
  only if Q52 allows them.

**Commits.**

1. The lexer.
2. Module directives: `.module`, `.main`, `.requires`, `.exports`, `.opens`.
3. `.class` with its kind, modifiers, `extends` and `implements`; `.field`.
4. `.method` with its signature, and `.locals`.
5. Instructions: without operands, with immediates, with references, with labels, and `switch`.
6. `.try`, `.catch`, `.finally`, `.maxstack`, `.line` and `.stackmap`.
7. The command `rfla`.
8. The command `test`, first part: it finds the test files, and checks that each file in
   `invalid/` fails with its `expect-error`. The job `bytecode` runs it.

**Exit check.** Every file in `format/` assembles, and every file in `invalid/` fails with its
expected code and position. Tag `v0.0.3`.

### 7.5 v0.0.4: Disassembler and round trip

**Goal.** `rfld` prints canonical `.rfla`, and the round trip of BC-08 holds for every test
module.

**Design.**

- The output follows the order of the binary: one directive or instruction per line, four spaces
  of indentation, LF line ends, labels numbered in code order, and names spelled as GEN-12
  states.
- The round trip of one test file:
  1. Assemble the file to bytes A.
  2. Disassemble A to text T.
  3. Assemble T to bytes B.
  4. Disassemble B to text T2.
  5. The test passes when A equals B and T equals T2 (TST-09).
- The job `bytecode` runs the round trip on every file in `format/`, `lib/` and `run/`.

**Commits.**

1. Module directives and type names.
2. Members and signatures.
3. Code, labels, exception regions, stack maps and line numbers.
4. The command `rfld`.
5. The round trip in the command `test`.

**Exit check.** The round trip holds for every test file, on both hosts. Tag `v0.0.4`.

### 7.6 v0.0.5: VM0 loader and core interpreter

**Goal.** VM0 loads modules and runs static code over primitives and strings, with natives.

**Design.**

- Loader (VM-12, VM-13): the module path is a list of directories of `.rflc` files; each
  `requires` resolves to exactly one module (MOD-03); types and methods load at first use.
- Natives (UNS-03, LIB-03): Java methods in a fixed table, keyed by qualified signature. A native
  declared outside the trusted modules is a load error.
- Interpreter (BOOT-04): one Java call per Reflect call, and a `switch` over the opcodes. Locals
  and the operand stack hold Java values.
- Entry: the `main` class comes from `.main` or `--main`, and `main` returns `void` or `int`
  (ex §2.4). `main` runs on a thread with a large stack, so deep recursion in Reflect does not
  overflow the JVM stack first.
- Stub modules (Q49): `reflect.core` with `Object`, `String` and `Runtime`, and `reflect.io` with
  `Console`, written in `.rfla` with the native names of ex §2.11 (`Console.write(int, String)`).
- Instructions: `nop`, `ldc.*`, `ldstr`, `dup` and `pop`; arguments and locals; arithmetic,
  bitwise operations, shifts and comparisons over `i32`, `i64`, `f32` and `f64`; conversions
  among the primitive types of R0 (`byte`, `short`, `char`, `int`, `long`, `float`, `double`);
  branches; `switch`; static `call`; `ret`.
- Until v0.0.8, a fault that later throws an exception, such as an integer division by zero,
  stops VM0 with the exception's type name and exit code 1. Tests written with `expect-throws`
  then stay valid when v0.0.8 makes the exceptions real.
- VM0 counts the opcodes it executes, and `test` reports the opcodes that no test executed (§8).

**Commits.**

1. The stub modules.
2. The module path and `requires` resolution, with tests for a missing module and a duplicate
   module.
3. Frames, the interpreter loop, `ldstr`, static calls of natives, `ret`, the command `vm0 run`,
   and `expect-output` in `test`. Check: `run/core/hello.rfla`.
4. Arguments, locals and `i32` arithmetic.
5. `i64`, `f32` and `f64`; comparisons; conversions, with the IEEE and saturation cases of BC-04.
6. Branches and `switch`. Check: programs for factorials, Fibonacci numbers and Collatz
   sequences.
7. Static calls with arguments and results; recursion.
8. Exit codes: `int main`, `Runtime.exit` and `expect-exit`.
9. Faults reported by exception name, and `expect-throws`.
10. The opcode count and its report.

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
- Loading checks `final`, `sealed` and `permits`, and that every `Override` row names an
  existing method (BC-12, VM-13).
- Tables added: `InterfaceImpl`, `Override`, `Constant`, and `TypeSpec` for nullable types
  (`NULLABLE`, BC-03).

**Commits.**

1. Type loading and layout; `newobj`, constructors, `ldfld` and `stfld`.
2. Static fields and class initialization, with tests of order, re-entry and failure.
3. Non-virtual instance calls; `callvirt` and vtables.
4. Interfaces, default methods and interface tables.
5. `ldnull`, `brnull`, `brnonnull`, `ceq.ref`, `instanceof` and `castclass`, with the null
   rules.
6. `ldconst` and the `Constant` table.
7. The load-time checks.

**Exit check.** The programs in `run/objects/` pass. Tag `v0.0.6`.

### 7.8 v0.0.7: VM0 arrays, strings and boxing

**Goal.** VM0 runs arrays with reified element types, the string operations that R0 needs, and
boxing.

**Design.**

- An array is a Java array together with its run-time array type, which holds the element type
  and its nullability (VM-02, NUL-01). Array types are `TypeSpec` rows with `ARRAY` signatures
  (BC-03).
- `stelem` of a reference checks the element type and nullability (`ArrayStoreException`,
  BC-14, NUL-05), and every index is checked (`IndexOutOfBoundsException`).
- `box` and `unbox` work for primitives and enums; an enum value is its backing integer on the
  stack (DAT-05).
- String operations are natives of the stub `reflect.core`. The set grows with the tests that
  need it; v0.1 fixes it with the stdlib sources.
- `main` receives its arguments as a `String[]`.

**Commits.**

1. `newarr`, `ldlen`, and `ldelem` and `stelem` for primitive kinds.
2. Reference arrays and their store checks.
3. Index checks.
4. `box` and `unbox`.
5. String natives: length, characters, concatenation, comparison, and conversion of primitives
   to strings.
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
  `ArrayStoreException`, `ExceptionInInitializerError` and `TypeLoadError`. The JVM's
  `StackOverflowError` and `OutOfMemoryError` become the Reflect ones (BOOT-04). These
  exceptions replace the stops of v0.0.5.
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
8. Stack traces with line numbers.

**Exit check.** The programs in `run/exceptions/` pass, and the `expect-throws` tests of earlier
stages still pass. Tag `v0.0.8`.

### 7.10 v0.0.9: VM0 reified types and generics

**Goal.** Every instantiation is a run-time type of its own with its own `Class<T>` object, and
generic code reads its exact type arguments.

**Design.**

- Tables added: `MethodSpec`, `GenericParam` and `GenericConstraint`. Signatures add
  `GENERICINST`, `VAR` and `MVAR` (BC-03).
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
  example `compareTo` when `T` is `int`.
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
8. `call.constrained`.
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
2. `v0.0.x` merges into the integration branch with a merge commit (§4).
3. `v0.1.x` starts from that merge commit.
