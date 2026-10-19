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
| `bytecode` | the `.rfla` suite: round trips, rejected files, programs on VM0 | v0.0.4 |
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
