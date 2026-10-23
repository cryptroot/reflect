# v0.0.x sketchpad

Working document for v0.0.x: progress, housekeeping for the main documents, questions and
answers, and the engineering log. The plan is [README.md](README.md); the project-wide decision
log is [../sketchpad.md](../sketchpad.md).

- Branch `v0.0.x` was created from `research` at `6408e3d` on 2026-10-19.
- Inputs: [roadmap.md](../roadmap.md) (row v0.0.x), [bootstrapping.md](../bootstrapping.md),
  [bytecode.md](../bytecode.md), [vm.md](../vm.md), [testing.md](../testing.md),
  [formal-model.md](../formal-model.md), [headroom.md](../headroom.md).

## Progress log

| Stage | Content | Status |
|---|---|---|
| plan | README and this sketchpad | written 2026-10-19; Q45 to Q56 answered 2026-10-19; Q57 to Q62 asked 2026-10-19 and answered 2026-10-20; Q63 to Q71 asked and answered 2026-10-20; Q72 to Q74 asked and answered 2026-10-21; Q75 to Q79 asked and answered 2026-10-22; Q80 to Q86 asked and answered 2026-10-23; Q87 to Q92 asked and answered 2026-10-23 |
| v0.0.0 | Docker environment, skeleton, Lean in Docker | done; Linux, PowerShell 7 and native Windows checks pass (D.3); tag `v0.0.0` on `7039022`, 2026-10-20 |
| v0.0.1 | encoding tables, constants, document test | done; `all` passes on Windows and Linux (D.4); tag `v0.0.1`, 2026-10-20 |
| v0.0.2 | model, writer, reader | done; `all` passes with 132 tests, 0 failures; tag `v0.0.2`, 2026-10-22 |
| v0.0.3 | assembler; suite runner for rejected files | done; `all` passes with 170 tests, 0 failures, and the `bytecode` job with 8 files, 0 failures; tag `v0.0.3`, 2026-10-22 |
| v0.0.4 | disassembler, round trip | done; `all` passes on Windows with 190 tests, 0 failures, and the `bytecode` job with 8 files, 0 failures, each file of `format/` round-tripped; the Linux run of Q53 is pending (D.5); tag `v0.0.4` on `0de78e4`, 2026-10-23 |
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
| [architecture.md](../architecture.md) §9 | a rule that every job runs in Docker on Windows and Linux hosts, Lean included, and that a host needs only Docker and git (README §5) | done in v0.0.0 |
| [architecture.md](../architecture.md) §3 | the layout gains `.gitattributes`, `docker/dev.ps1`, `docker/dev.sh`, `docker/ci.sh` and `tests/bytecode/` (README §6.1) | done in v0.0.0 |
| [bootstrapping.md](../bootstrapping.md) BOOT-10 | the package list gains `reflect.stage0.bytecode` and `reflect.stage0.tools` | done in v0.0.0 (Q47) |
| [bootstrapping.md](../bootstrapping.md) BOOT-11, BOOT-12 | one writer, written in stage0, instead of a Java port of a Reflect writer; the transliterator also prints `reflect.bytecode` | Q47 |
| [bytecode.md](../bytecode.md) | encoding tables, the R0 mark on opcodes, the canonical form, the `main` column and `.main`, the `.rfla` grammar, `RFA` codes, the Stack column of §5.1 | done in v0.0.1; the `RFA` codes and the Stack column in v0.0.3 (Q77, Q79) |
| [testing.md](../testing.md) §7 | the home of `.rfla` tests (`tests/bytecode/format`, `lib`, `run` and `invalid`) and the `RFA` form of `expect-error` | done in v0.0.3 (TST-13) |
| [bytecode.md](../bytecode.md) BC-03, BC-08, BC-11, §8.1 | names that are identifiers (Q83), `@` (Q82), brackets for the module itself and for a primitive's struct (Q81), `BYREF` only at the top of a signature (Q84), one member per name and signature (Q85), `.typeref` and `.memberref` (Q80), and the canonical text, BC-25 (Q86) | done in v0.0.4 |
| [type-system.md](../type-system.md) GEN-12 | brackets before a primitive's struct, and the module's own name before its own struct (Q81) | done in v0.0.4 |
| [testing.md](../testing.md) §7 | the round trip of each `.rfla` file in `test` (TST-09, TST-13) | done in v0.0.4 (TST-13) |
| [examples.md](../examples.md) §11.1, §11.2 | the hidden `$receiver` argument becomes the implicit `R` of BC-05 (A.2 item 3); the `.try` blocks become regions in label form, and calls into other modules state their signatures (A.2 item 9) | with [../sketchpad.md](../sketchpad.md) A.6 |
| [headroom.md](../headroom.md) and the rules its entries cite | an answered entry changes the owning rules and records where it went | done 2026-10-20 (Q57 to Q62) |
| [type-system.md](../type-system.md) MOD-03 | the module path supplies the module of `requires m` as the file `m.rflc` of exactly one directory (Q88) | v0.0.5 |
| [type-system.md](../type-system.md) NUM-05 | `String.valueOf` writes numbers in Java's text (Q87) | v0.0.5 |
| [bytecode.md](../bytecode.md) BC-04, BC-17 | the `i32` count of a 64-bit shift, and locals that start at zero (Q91) | v0.0.5 |
| [type-system.md](../type-system.md) NUM-04, DAT-09; [bytecode.md](../bytecode.md) BC-04; [proposals/meta-analysis.md](../proposals/meta-analysis.md) MA-01 | the canonical NaN, and *Went to* in MA-01 (Q92) | v0.0.5 |
| [testing.md](../testing.md) TST-01 | how `test` compares the output, the exit code and an uncaught exception (Q90) | v0.0.5 |

### A.2 Inconsistencies found while planning

1. GEN-12 spells primitives by struct name in `.rfla` (`reflect.core.Int32`), but ex §11.2 writes
   `int` (Q52).
2. BC-08 names type parameters by index (`!0`, `!!0`); ex §11.1 also uses names (`!!K`) (Q52).
3. ex §11.1 still passes the receiver class as a hidden `$receiver` argument after `ldtype`.
   BC-05 replaced this with the implicit `R` of `call`, and with `call.recv` and `ldrecv`.
4. BC-04 calls the operand of `ldstr` an index (`<n>`); BC-02 says heaps are referenced by byte
   offset. Fixed 2026-10-20 with Q67: `ldstr <str>` takes an offset into LITERALS.
5. The `Module` table of BC-11 has no column for the descriptor's `main` class, which VM-12
   reads. v0.0.1 adds the column and the `.main` directive. Fixed 2026-10-20 in v0.0.1.
6. BOOT-11 speaks of "a Java port of the writer". Under Q47 there is one writer.
7. TST-09 puts verifier rejections in hand-written `.rfla` files, but names no directory.
8. Two rule IDs have two definitions. LIB-03 is stated in architecture.md §6 and restated in
   stdlib.md §1. OPR-08 is split: type-system.md §10 gives its timing, and grammar.md §5 its
   token rule (Q56).
9. ex §11.1 and §11.2 call members of other modules in the short form, such as
   `call reflect.io.Console::println(reflect.core.Object?)` (Q69), and ex §11.2 writes
   exception regions as blocks (Q71). Found 2026-10-20 in v0.0.1.
10. README §7.7 makes VM0 check `permits` at load (v0.0.6), but BC-11 has no table that lists
    the permitted subtypes of a sealed type (DAT-06). Settle before v0.0.6: a `Permits` table, or
    a check that every subtype of a sealed type is in its module. Found 2026-10-20 in v0.0.1.
11. A `MethodRef` states no `GENERIC` count unless it instantiates the method, so a `MemberRef`
    row with `GENERIC` that no `MethodSpec` names has no text. Settle with `MethodSpec` in
    v0.0.9. Found 2026-10-22 in v0.0.3 (Q78). Q80 keeps it for v0.0.9; until then `rfld`
    reports such a row.
12. The reader accepts `TypeRef` and `MemberRef` rows that nothing in the module references, but
    `rfla` makes these rows only at their first use, so `rfld` cannot print such a module. Settle
    in v0.0.4: a rule of BC-24 that every such row is referenced, or a directive that declares
    one. Found 2026-10-22 in v0.0.3 (Q78). Settled 2026-10-23 with Q80: `.typeref` and
    `.memberref`.
13. BC-03 keeps the structs of primitives out of signatures, but the reader accepts a `TypeRef`
    row for `reflect.core.Int32` that an instruction or a base type names. In `.rfla` that name is
    the primitive (Q52), so only the brackets of Q76 can print the row. Settle in v0.0.4. Found
    2026-10-22 in v0.0.3 (Q78). Settled 2026-10-23 with Q81: brackets name the struct's row.
14. A name that equals a word the grammar takes at its place cannot be written there: a type in
    the global namespace named `record` or `final` after `.class`, or a parameter type named
    `scoped`. Settle in v0.0.4, when `rfld` must print every name. Found 2026-10-22 in v0.0.3.
    Settled 2026-10-23 with Q82: `@`.
15. The writer and the reader accept any UTF-8 string as a name, such as an empty type name or
    a type name with a dot, but `.rfla` spells only identifiers and dotted names. Found
    2026-10-23 while v0.0.4 was planned. Settled 2026-10-23 with Q83.
16. The reader accepts `BYREF` inside another type, such as an array of `ref int`, which `.rfla`
    cannot spell, since `ref` binds before suffixes. Found 2026-10-23 while v0.0.4 was planned.
    Settled 2026-10-23 with Q84.
17. Two fields of a type can share a name and a type, and two methods a name and a signature,
    so a reference cannot tell them apart. Found 2026-10-23 while v0.0.4 was planned. Settled
    2026-10-23 with Q85.
18. README §7.6 checks v0.0.5 with programs that print factorials, Fibonacci numbers and Collatz
    sequences, but the natives that turn numbers into text arrive in v0.0.7 (README §7.8 commit
    5), as boxing does. Found 2026-10-23 while v0.0.5 was planned. Settled 2026-10-23 with Q87.
19. BC-04 does not give the type of the count of a 64-bit shift, and BC-17 does not give the value
    of a local before its first store. VM0 needs both. Found 2026-10-23 while v0.0.5 was planned.
    Settled 2026-10-23 with Q91.

## B. Questions and answers

Answer in chat, in the question prompts, or by editing the **Answer** lines.

### B.1 Batch 5: the v0.0.x plan (asked 2026-10-19)

**Q45. Integration branch.** Only `research` exists, and a version branch needs a branch to
start from and to merge into. *Recommendation:* create `main` from `research`; `v0.0.x` starts
at the same commit (`6408e3d`) and merges into `main` with a merge commit; `research` keeps the
design history. **Answer:** 2026-10-19: Recommendation rejected. `main` is still created from `research`, but version branches reach `main` by squashed merge, one commit per version (v0.1 is one commit, v0.2 is one commit), so `main` stays lean.

**Q46. Branches for stages.** *Recommendation:* one branch per version (`v0.0.x`) and an
annotated tag per finished stage (`v0.0.N`); a short-lived stage branch only when two stages
run in parallel. **Answer:** 2026-10-19: Accepted as recommended.

**Q47 (BOOT-10, BOOT-12). Is stage0's bytecode library R0-shaped Java?** `rflc` needs
`reflect.bytecode` in Reflect by v0.2 (ARC-11). If `reflect.stage0.bytecode` is R0-shaped Java
(BOOT-03), and uses only the facade and the `java.lang` types that the transliterator maps, the
transliterator prints `reflect.bytecode` from it. The format then has one implementation instead
of a Java one and a Reflect one. Costs: SHA-256 and the UTF-8 and UTF-16LE codecs are written by
hand, and the facade `reflect.stage0.lib` must exist from v0.0.2. *Recommendation:* yes.
**Answer:** 2026-10-19: Yes, as recommended.

**Q48 (BOOT-10). Java unit tests.** *Recommendation:* a small harness in `stage0/test` (an
annotation, a runner and assertions) instead of JUnit: there is nothing to download, and the
harness leaves with stage0 at v0.6. **Answer:** 2026-10-19: Accepted as recommended.

**Q49. `reflect.core` and `reflect.io` before the stdlib exists.** VM0 needs `Object`, `String`,
the exceptions and `Console` to run a program. *Recommendation:* hand-written `.rfla` stub
modules in `tests/bytecode/lib/`, with the native names of ex §2.11. v0.1 replaces them with
stdlib sources and keeps the native table. Types built into VM0 instead would bypass the loader,
which the stubs test. **Answer:** 2026-10-19: Accepted as recommended.

**Q50 (BC-19). Stack maps in `rfla`.** VM0 does not read them; the register form of `rfl` (VM-05)
and the verifier (BC-07) do. *Recommendation:* in v0.0.x, `rfla` stores `.stackmap` directives
as written and computes nothing; `rflc0` writes stack maps from v0.1; `rfla` learns to compute
them before `rfl` runs hand-written tests (v0.6). **Answer:** 2026-10-19: Accepted as recommended. In v0.0.x `rfla` stores `.stackmap` directives as written and computes nothing.

**Q51 (LEAN-04). The Lean toolchain in the image.** *Recommendation:* the Dockerfile pins the
Lean version and the SHA-256 of its release archive, downloads the archive with `curl`, checks
it, and unpacks it to `/opt/lean`, without elan. The image holds one version, so elan's
toolchain switching is not needed, and one download path is simpler to check behind a proxy.
The `lean` job fails if `formal/lean-toolchain` names another version. **Answer:** 2026-10-19: Accepted as recommended.

**Q52 (GEN-12, BC-08). Spelling in `.rfla`.** GEN-12 spells primitives by struct name
(`reflect.core.Int32`), and BC-08 names type parameters by index (`!0`, `!!0`); ex §11 writes
`int` and `!!K`. *Recommendation:* `rfld` prints the canonical spelling; `rfla` also accepts
primitive keywords and type-parameter names, so hand-written tests stay readable. Canonical text
is the fixpoint of the round trip. **Answer:** 2026-10-19: Accepted as recommended.

**Q53 (BLD-06, TST-05). Hosted CI.** The repository has no remote. *Recommendation:* once it has
one, a hosted Linux runner runs `docker/dev.sh all` on every push to a version branch. Until
then, the developer runs `all` on Windows and on Linux before each tag. **Answer:** 2026-10-19: Accepted as recommended.

**Q54 (LEAN-04). Editing Lean on Windows.** The Lean language server needs the toolchain, which
exists only in the image. *Recommendation:* a `.devcontainer/devcontainer.json` that opens the
repository in `reflect-build` with the VS Code Lean extension, added in v0.0.0. **Answer:** 2026-10-19: Accepted as recommended.

**Q55 (RM-03). Where VM0 ends in v0.0.x.** The roadmap's exit test passes at v0.0.5 (hello
world). *Recommendation:* continue to v0.0.9, so hand-written modules test the whole R0
instruction subset before `rflc0` can hide VM0 faults behind compiler faults. Alternative: end
v0.0.x at v0.0.5 and grow VM0 in v0.1 together with `rflc0`. **Answer:** 2026-10-19: Accepted as recommended; v0.0.x continues to v0.0.9.

**Q56. A job that checks the documents.** *Recommendation:* a `docs` job that checks relative
links, and that each cited rule ID has exactly one definition. Add it after the broken links of
[../sketchpad.md](../sketchpad.md) A.1 are fixed and LIB-03 and OPR-08 have one definition each
(each has two today, A.2 item 8). **Answer:** 2026-10-19: Accepted as recommended; the `docs` job is added after those fixes.

### B.2 Batch 6: headroom (asked 2026-10-19, answered 2026-10-20)

These come from [../headroom.md](../headroom.md) and bind in the stage named. An accepted answer
changes the owning rules, and the HR entry records where it went.

**Q57 (BC-02, BC-10; HR-01). Unknown content.** No rule says what a reader does with a section
or flag it doesn't know, so a minor version after v1.0 can't add a section that older readers
skip safely, nor a flag they are sure to reject. *Recommendation:* bit 31 of a section ID marks
the section ancillary. Readers reject a newer formatMinor, an unknown critical section and an
unknown flag bit, and skip an unknown ancillary section; the model keeps it as opaque bytes, so
the v0.0.2 rewrite check still holds. The writer stamps the lowest formatMinor its content
needs. DEBUG becomes ancillary, and an ancillary section that describes critical content starts
with that content's hash. Binding in v0.0.1 (commits 4 and 5). **Answer:** 2026-10-20: Accepted,
with four additions found in review: (1) a body held an index into the DEBUG line table (BC-17),
so a longer line table in one method shifted the indexes of later ones and changed CODE; DEBUG is
now keyed by method token, and critical content holds no source positions; (2) before v1.0 a
reader accepts only its own format `0.N`, since BC-10 let formats change freely but no rule said
which versions the v0.0.2 reader checks; (3) `.rfla` gets `.section <id> hex "..."`, so the round
trip covers unknown ancillary sections; (4) a DEBUG whose contentHash differs is treated as absent,
not rejected. DELTA becomes section 6 and DEBUG `0x80000001`. Rules: BC-02, BC-08, BC-10, BC-17,
BC-20, VM-21, IC-12.

**Q58 (BC-02, REFL-07, CMP-04; HR-02). What the content hash covers.** BC-02 hashes all
sections, so stripping DEBUG or moving a line changes the module's identity: a delta no longer
finds its base (REFL-07), and the build key of every module that reads it changes (CMP-04).
*Recommendation:* with Q57, the hash covers formatMajor, formatMinor, flags, and each critical
section as (ID, length, bytes) in directory order. A stripped module keeps its identity, and a
signature section can sign the hash. Binding in v0.0.2 (the writer). **Answer:** 2026-10-20:
Accepted, with additions from review: (1) every offset inside a section is relative to the section
it points into, since stripping DEBUG removes a directory entry and would move file offsets in
TABLES (required); (2) `reserved` moves before `sectionCount`, so the hash covers header bytes 4 to
27 and any later header field; (3) DEBUG lists each source file with its SHA-256, since two builds
that differ only in lines now share one contentHash; (4) CMP-04 also keys on the whole-file hashes
of the compiler, generators and analyzers, whose DEBUG reaches stack traces; CMP-04 did not list
the compiler before. A signature over contentHash does not cover DEBUG. Rules: BC-02, BC-22,
CMP-04, REFL-07.

**Q59 (BC-02, BC-10, BC-11, VM-13; HR-03, HR-04). Fixed-width rows and stated limits.** BC-10
doesn't say whether table columns are fixed-width or LEB128. LEB128 rows lose random access
(VM-13 decodes on demand) and leave no way to append a column; and no rule states a limit beyond
the 24-bit rows of a token. *Recommendation:* columns have fixed widths, and heap offsets,
tokens and row numbers are u32. TABLES gives each table's row count and row size; the size
follows from the table and the formatMinor, so the canonical form stays unique. In a module of
an earlier minor, a reader treats columns appended since as zero. LEB128 stays in blobs and
code. bytecode.md lists every limit (rows, heap and section sizes, code length, locals, stack
depth, parameters, exception regions), and the writer and `rfla` report a diagnostic instead of
writing a truncated module. Binding in v0.0.1 (commits 3 and 4). **Answer:** 2026-10-20: Accepted,
with three notes from review: readers check the stated row size, which is redundant for them but
lets tools skip tables; the zero value of an appended column means what modules meant before the
column existed; the limits add signature nesting depth (255) and identifier length (65,535 bytes),
and readers reject a module over a limit, so the limits bind every tool. Rules: BC-02, BC-10,
BC-11, BC-21, VM-09, VM-13.

**Q60 (VM-13, BC-07, REFL-03, REFL-07; HR-09). Final fields stay final.** No rule says which
code may store to a final field, and reflection reaches the private members of an opened
namespace (REFL-03). If a final can change, the JIT and AOT can't fold it; HotSpot still doesn't
trust instance finals by default, because reflection and deserialization may change them.
*Recommendation:* `stfld` on a final field only in a constructor of the declaring type, `stsfld`
on a static final only in its static initializer. VM0 checks this at load (v0.0.6 commit 7), the
verifier later (BC-07). `Field.set` on a final throws `IllegalAccessException` even with
`opens`, and hot reload never changes a final (REFL-07). v1 has no `with` expressions and no
`init` accessors; either would need its own rule. Binding in v0.0.6. **Answer:** 2026-10-20:
Accepted, with four additions from review: the recommendation restricted where a store occurs but
not which object it targets, so the target must be the constructor's own `this`; a class
constructor runs only on a new object, so no bytecode re-runs one on a finished object;
`ldflda` and `ldsflda` of a final outside its initializer give `ref readonly`; no instruction
stores a `const` field. A violation is a link error (`TypeLoadError`). TXN-13 already keeps
allocation and class initialization out of transactions, so rollback never restores a final.
Rules: BC-07, BC-23, VM-16, MEM-05, REFL-03, REFL-07, EXC-05, JIT-08, TXN-13.

**Q61 (OPR-04, BC-04, VM-02, DAT-01, DAT-02; HR-10). Boxes without identity.** `ceq.ref` and
Object-typed `==` compare identity (OPR-04), so a program can tell two boxes of one value apart,
and the VM can't cache, share or remove a box that a comparison might see. *Recommendation:*
`ceq.ref` on two boxes compares class and contents (primitives as `equals` does, struct fields
recursively); a box has no identity hash; constructing a `WeakReference` to a box throws. Boxes
stay immutable: `unbox` copies, and reflection can't set a box's fields; how `mutating` methods
meet interfaces (DAT-02) is settled by v0.4. The cost is a slow path when two references differ
and their static types admit boxes; the comment "identity, always" in ex §8.5 changes. Binding
in v0.0.6 (commit 5, `ceq.ref`) and v0.0.7 (commit 4, `box`). **Answer:** 2026-10-20: Accepted,
with one change from review: primitive fields compare by bit pattern, as JEP 401 does, not as
`equals` does. `equals` canonicalizes NaN (NUM-04), so two NaNs with different bits would be equal,
and sharing such boxes would change the bits a program reads. `WeakReference` is bounded
`T extends class`, and a box as referent or weak-table key throws `IllegalArgumentException`, as
does `Field.set` on a box. Consequence: C#'s mutable struct enumerator behind an interface has no
equivalent; struct iterators work through STM-04. Rules: DAT-09, BC-04, OPR-04, MEM-07, DAT-01,
DAT-02, VM-02, GC-05, REFL-03; ex §8.5.

**Q62 (NUL-07, NUL-05, BC-14, EXC-05; HR-11). Invariant reference arrays.** This reopens a
decided rule. Java made arrays covariant because it had no generics; Reflect has generics but
copies the covariance and its cost, a check of element type and nullability on every reference
store. *Recommendation:* reference arrays are invariant; covariant use goes through generic
methods, the `Array` base class and, later, a read-only covariant view. ex §2.10 and §7.8,
NUL-05, NUL-07, BC-14 and EXC-05 change. Adding covariance later is compatible; removing it is
not. Alternative: keep NUL-07 as decided. Binding in v0.0.7 (commit 2). **Answer:** 2026-10-20:
Accepted; reference arrays are invariant, nullability included. The array-store part of Q09 becomes
a static guarantee: a `String[]` is never seen as a `String?[]`. Review added the rules the
recommendation did not list (TYP-02, NUL-01, MEM-10, BC-07) and two consequences: a `String[]`
passed to `Object?...` becomes one element, and the compiler warns; and "adding covariance later
is compatible" holds only for a read-only covariant view, a new type, since covariant arrays would
change what existing array casts and type tests return (`o instanceof Shape[]` on a `Square[]`).
Rules:
NUL-07, TYP-02, NUL-01, NUL-05, MEM-10, EXC-05, BC-04, BC-07, BC-14; ex §2.10, §7.8.

### B.3 Batch 7: follow-ups to batch 6 (asked and answered 2026-10-20)

Q63 to Q65 were found while batch 6 was applied; Q66 follows from the answer to Q65.

**Q63 (RM-04; README §4, §8). How a version branch reaches `main`.** README §4 and §8 still said
"merge commit", but the answer to Q45 chose a squashed merge. **Answer:** 2026-10-20: `main` keeps
one squashed commit per version, as Q45 says. README §4 and §8 and RM-04 now say so.

**Q64 (REFL-07, VM-10). Hot reload and non-null fields.** A delta may add fields to existing
objects, which start at zero, so an added non-null reference field reads `null` in objects made
before the delta. **Answer:** 2026-10-20: Hot reload cannot guarantee null safety in v0; this is
acceptable for now. REFL-07, VM-10, NUL-04 and ex §10.6 record the hole, and headroom.md §8 lists
it with a way out (initial values carried in the delta). `rflc delta` warns about each such field
[Proposed]. Memory safety holds, since every dereference checks null.

**Q65 (LIB-04, MEM-02, MEM-03, DAT-02, DAT-09, STM-04). Default iterators.** LIB-04 gave
`ArrayList` a struct iterator, but MEM-03 has no explicit interface implementation, so one
`iterator()` could not return both a struct and an `Iterator<T>`. **Answer:** 2026-10-20: the
default iterators are structs that implement `Iterator<T>`. To make that consistent: an override
may return a value type that implements the base's return type, and `rflc` emits a bridge that
boxes it (MEM-02, BC-12), so MEM-03 stands; an interface member may be declared `mutating`, and
`Iterator<T>.next()` is, so generic code never mutates a read-only location (DAT-02, Swift's
rule); the boxes of a struct that implements such a member with a `mutating` method are ordinary
objects with identity, which `next()` updates in place (DAT-09, C#'s boxed enumerators), while
every other box keeps the rules of Q61; struct iterators arrive with structs in v0.4 (BOOT-02).
This replaces the consequence noted under Q61. Rules: LIB-04, MEM-02, BC-12, DAT-02, DAT-09,
STM-04, BC-04, VM-02, VM-13, GC-05, REFL-03; grammar.md §3.4; ex §2.9.

**Q66 (MEM-02, BC-12, SVD-05; LEAN-01). Covariant returns.** MEM-02 only proposed that overrides
may narrow return types, Q65 added struct returns through a bridge, and FR-core's `OverrideOk`
required the exact return type. **Answer:** 2026-10-20: cemented. An override implements the
member it overrides, and its return type may be any type derived from the base's: a subtype, or a
type that boxes or wraps into it. `virtual Object function()` can be overridden by
`override MyClass function()`, so derived types specialize their returns instead of returning the
base class. Applied as: MEM-02 [Decided] (parameters stay exact; get-only properties and indexers
narrow too, settable ones keep the exact type); BC-12 (a reference subtype fills the base slot
itself, since `Override` rows are explicit; boxing and wrapping use a bridge; loading checks the
return type against the method replaced, the nearest base one); BC-09 (narrowing a return type
breaks a subclass in another module that overrides with a wider one); VM-13; ex §2.5
(`Dog.newborn` returns `Dog` where `Animal.newborn` returns `Animal?`, and a widening override is
rejected); README v0.0.6 commits 3 and 7. FR-core follows FGJ: `OverrideOk` asks for a subtype,
and `formal/Reflect/Core/Examples.lean` checks that a narrowing override is accepted and a
widening one rejected; with the exact-type rule put back, that file fails to build.

### B.4 Batch 8: the opcode space (asked and answered 2026-10-20)

Found while the exact opcode table of v0.0.1 was written.

**Q67 (BC-04; README §7.2). The family ranges are too small.** README §7.2 puts every opcode
inside the ranges of BC-04. Counted with one opcode per typed variant, and with the refinements
of Q68, the instructions need 58 arithmetic opcodes (range of 48), 29 comparison (16), 45
conversion (32), 41 control (16), 21 array (8) and 18 reference opcodes (8). R0 alone needs 21
comparison and 33 control opcodes. Ranges mirrored behind `0xFE` still leave control, arrays and
references short. *Recommendation:* re-size the one-byte ranges, so that every unchecked
instruction outside references and unsafe code has one byte (202 opcodes), and put checked
arithmetic, checked conversions, `ldind`, `stind` and the unsafe instructions behind `0xFE`.
Alternatives: keep the ranges and move the rest of each family behind `0xFE`, which makes R0
instructions such as float comparisons two bytes long; or drop the fused branches other than
`i32`, as the JVM does. **Answer:** 2026-10-20: Accepted as recommended. BC-04 gives the new
ranges, and `ldstr` takes a `<str>` offset into LITERALS (A.2 item 4).

**Q68 (BC-04, NUM-03, BC-16). Refinements of the instruction set.** *Recommendation:* (1) no
instruction where signedness does not change the result: `ceq`, `beq` and `bne` exist over `t`
only, and stores over `i8 i16 i32 i64 f32 f64 ref`; (2) conversions only where bits change, 25
unchecked and 20 checked, and other pairs compose, which gives Java's result for a float
narrowed to `byte` (NUM-03); (3) `div.ovf.i32` and `div.ovf.i64`, since NUM-03 makes
`MIN_VALUE / -1` throw in checked code and BC-04 had no checked division; (4) `ldarga` and
`ldloca` in R0, since `call.constrained` takes a reference to the value (BC-16). **Answer:**
2026-10-20: All four accepted. BC-04 and README §7.10 apply them.

### B.5 Batch 9: the `.rfla` syntax (asked and answered 2026-10-20)

Found while the `.rfla` grammar of v0.0.1 was written.

**Q69 (BC-08, BC-03; README §6.3). What `.rfla` states about another module.** A `MemberRef`
row holds the member's full signature, with the `STATIC` flag and the return type, and a
signature says whether a named type is a value type (`CLASS` or `VALUETYPE`). README §6.3 wrote
`call reflect.io.Console::println(reflect.core.Object?)`, which states neither, and `rfla` cannot
tell an enum of another module from a class. *Recommendation:* the text states them, as CIL and
the JVM assemblers do: `call static void reflect.io.Console::println(reflect.core.Object?)`, a
field reference with its type, and `valuetype` before a value type. `rfla` accepts the short form
for members and types of the module it assembles, `rfld` always prints the full form, and `rfla`
reads no other module. Alternative: the short form everywhere, with `rfla` reading the referenced
modules from a module path. **Answer:** 2026-10-20: Accepted as recommended. README §6.3 and the
example of BC-05 use the full form (A.2 item 9).

**Q70 (GEN-12, MOD-05). Nested type names.** GEN-12 spells run-time types by qualified name, but
in `demo.zoo.Enclosure.Tag` nothing marks where the namespace ends, since MOD-05 only warns about
a type name that starts with a lowercase letter. *Recommendation:* the last dotted segment names
the top-level type, and `/` starts each nested type, as in CIL: `demo.zoo.Enclosure/Tag`. GEN-12
adopts the spelling, so one spelling still serves reflection, errors and `.rfla`. Alternative:
`/` in `.rfla` only. **Answer:** 2026-10-20: Accepted as recommended.

**Q71 (BC-08, BC-18; ex §11.2). Exception regions in `.rfla`.** ex §11.2 writes
`.try { ... } .catch !!1 { ... }`. Blocks force each handler to follow its `try`, so a module
with its handlers elsewhere has no block form and could not round-trip. *Recommendation:* the
label form only, one line per region: `.try L0 L1 .catch reflect.core.Exception L2 L3` and
`.try L0 L1 .finally L2 L3`; ex §11.2 changes with A.6 of [../sketchpad.md](../sketchpad.md).
Alternative: the label form and the block form. **Answer:** 2026-10-20: Accepted as recommended
(A.2 item 9).

### B.6 Batch 10: the v0.0.2 library (asked and answered 2026-10-21)

Found while v0.0.2 was planned.

**Q72 (BOOT-03, Q47; README §6.1). How the facade is built.** README §6.1 called
`reflect.stage0.lib` R0-shaped Java "so the transliterator can print" it, but BOOT-03 maps the
facade to `reflect.collections`, which v0.1 writes in Reflect. *Recommendation:* the facade has
R0-shaped members with the names of LIB-04 and delegates to `java.util`; the transliterator maps
it and does not print it; it has no iteration over maps, and every sort uses a total order, so no
output depends on hash order or sort stability (BOOT-05). Alternative: an array list and a hash
map written by hand, which need an unchecked cast to make a `T[]`, and R0-shaped Java has no such
cast. **Answer:** 2026-10-21: Accepted as recommended. README §6.1 and §7.3 say so.

**Q73 (BOOT-03, BOOT-10). Nullable types in stage0 Java.** BOOT-03 maps JSpecify's `@Nullable T`
to `T?`, but the image has no JSpecify, and jobs have no network. *Recommendation:* a `TYPE_USE`
annotation `reflect.stage0.lib.Nullable` with JSpecify's meaning; an unmarked type is non-null, as
in Reflect. Alternatives: JSpecify's jar in the image, or no marks until v0.2. **Answer:**
2026-10-21: Accepted as recommended. BOOT-03 and BOOT-10 name the annotation.

**Q74 (BC-03; README §7.3, §7.10). Signature codes in v0.0.2.** README §7.10 added `GENERICINST`,
`VAR` and `MVAR` in v0.0.9. *Recommendation:* the v0.0.2 codec handles every type code of BC-03
and checks its structural rules: `VOID` only as a return type, `WILDCARD` only as a type argument,
a `GENERIC` count of at least 1, defined kinds and flags, no primitive by its struct name, and a
depth of at most 255. `VAR` and `MVAR` indexes are checked where the type parameters are known:
before v0.0.9 no definition has any, and a `MemberRef`'s `MVAR` is checked against its own
`GENERIC` count. `INSTANTIATION` comes with `MethodSpec`. Alternative: the codes in the order that
VM0 needs them, with the reader rejecting the others until then. **Answer:** 2026-10-21: Accepted
as recommended. README §7.3 and §7.10 follow.

### B.7 Batch 11: the v0.0.3 assembler (asked and answered 2026-10-22)

Found while v0.0.3 was planned.

**Q75 (BC-11; README §7.4, §7.7). Tables for the directives of v0.0.3.** README §7.4 commits 2
and 3 assemble `.exports`, `.opens` and `implements`, but the v0.0.2 model has no `Export` or
`InterfaceImpl` table; README §7.7 added `InterfaceImpl` in v0.0.6, and no stage added `Export`.
*Recommendation:* v0.0.3 adds the two tables that its commits name, with their writer and reader
checks. `Constant`, `Override`, `TypeSpec`, `GenericParam` and `MethodSpec` stay in v0.0.6 and
v0.0.9; `rfla` parses their syntax and rejects it with one code until then. Alternatives:
`Export` only, with `implements` rejected until v0.0.6; or no new table. **Answer:** 2026-10-22:
Accepted as recommended. README §7.4 and §7.7 follow.

**Q76 (BC-08, BC-11, MOD-04). The module of a `TypeRef`.** A `TypeRef` row names its module
through a `ModuleRef`, but the grammar could not say which required module holds
`reflect.io.Console`, and `rfla` reads no other module (Q69). *Recommendation:* the scope is the
required module whose name equals the namespace or is its longest prefix at a dot; when no
required module matches, or the type is in another one, the text names the module in brackets, as
CIL does: `[reflect.core]reflect.meta.Type`, since LIB-01 puts namespace `reflect.meta` in
`reflect.core`. `rfld` prints the brackets only when the prefix rule gives another module, so the
round trip stays exact. Alternatives: brackets on every reference; a directive that maps a
namespace to a module. **Answer:** 2026-10-22: Accepted as recommended. BC-08, its grammar
(`ModuleScope`) and GEN-12 follow.

**Q77 (BC-04, BC-17; README §7.4). Stack effects.** Without `.maxstack`, `rfla` computes the
stack depth from the stack effect of each instruction, but no rule stated the effects, and
`rflc0` (v0.1) and the verifier (v0.7) need the same ones. *Recommendation:* a Stack column in the
opcode table of §5.1, with the effects in `Opcode`, which the document test compares in both
directions. README §7.4 states the computation: depth 0 at entry, 1 at a catch handler and 0 at a
finally handler; `leave` empties the stack; two paths into one instruction agree; unreachable code
does not count. Alternatives: the effects in code only; or `.maxstack` in every body.
**Answer:** 2026-10-22: Accepted as recommended. README §7.4 follows.

**Q78 (BC-08, UNS-02, MEM-10). Two gaps in the grammar.** The grammar could not express the
header flag `UNSAFE`, nor a reference to a `VARIADIC` method of another module, so `rfld` could not
print such a module. *Recommendation:* `.unsafe` sets the header flag, and a `MethodRef` may end
its types with `...`, as a `Param` does. A.2 records three more gaps for later stages.
Alternative: leave both to v0.0.4. **Answer:** 2026-10-22: Accepted as recommended. BC-08 and its
grammar follow (A.2 items 11 to 13).

**Q79 (CMP-02, TST-01; README §6.3). Assembler diagnostics.** *Recommendation:* `rfla` stops at
the first error, so a file in `invalid/` holds one `expect-error`. Codes are grouped by hundreds:
`RFA00xx` lexical, `RFA01xx` syntax, `RFA02xx` names, `RFA03xx` content. Content has one code for a
rule of the format, whose message names the rule, one for a limit of BC-21, one for syntax whose
table comes in a later stage, and one for a stack depth that cannot be computed. Lines and columns
count code points from 1, and a tab is one column. bytecode.md §8.2 lists the codes, and the
document test compares them with the code. Alternatives: every error, with recovery; one code per
rule of the format. **Answer:** 2026-10-22: Accepted as recommended. README §6.3 and §7.4 follow.

### B.8 Batch 12: the v0.0.4 disassembler (asked and answered 2026-10-23)

Found while v0.0.4 was planned. Q80 to Q82 settle A.2 items 12 to 14, and Q83 to Q85 the gaps
found then, A.2 items 15 to 17.

**Q80 (BC-08, BC-24; A.2 items 11, 12). Rows that nothing references.** The reader accepts a
`TypeRef` or `MemberRef` row that nothing in the module names, but `rfla` makes these rows only at
their first use, so `rfld` cannot print such a module. *Recommendation:* BC-24 gains a rule that a
column, a signature or an instruction names every such row, as with heap entries, and an
instruction names a `GENERIC` method only through a `MethodSpec`. Alternative: directives
`.typeref` and `.memberref` that declare a row. **Answer:** 2026-10-23: Alternative chosen.
`.typeref` declares a `TypeRef` row by its name, with the module in brackets where Q76 needs it,
and `.memberref` a `MemberRef` row by the full form of a `FieldRef` or `MethodRef`. `rfld` writes
them only for rows that no column, signature, instruction or other row names. A `GENERIC`
`MemberRef` still has no text, so A.2 item 11 waits for v0.0.9, and `rfld` reports such a row
until then.

**Q81 (BC-03, BC-08, GEN-12, MOD-03; A.2 item 13). The struct of a primitive in a token.** `rfla`
rejects a token that names `reflect.core.Int32`: without brackets the name is the primitive, which
needs a `TypeSpec` row (v0.0.6), and with brackets the signature check rejects it. The reader
accepts the `TypeRef` row, and in module `reflect.core` a token of the `TypeDef` row.
*Recommendation:* a primitive is always its code, in tokens as in signatures, so the writer and the
reader reject such a row and such a token. Alternative: accept the `TypeRef` row, and `rfla` and
`rfld` write it with brackets. **Answer:** 2026-10-23: Alternative chosen. A token may name the
`TypeRef` row of a primitive's struct, `[reflect.core]reflect.core.Int32`; a signature still names
the primitive by its code (BC-03). A follow-up question covered the module's own `TypeDef` of the
struct: brackets that hold the module's own name name a type of the module, and `rfld` writes them
only before a primitive's struct. A `requires` cycle is an error (MOD-03), so `rfla`, the writer and
the reader reject a module that requires itself, and the brackets keep one meaning.

**Q82 (BC-08; A.2 item 14). Names that are words of the grammar.** `.class record {` reads
`record` as the kind. `record`, `scoped` and `sealed` are contextual keywords (LEX-03), and
`annotation` and `i32` are not keywords in source, so valid source can give such names.
*Recommendation:* `@` before a name makes it a name, never a word of the grammar, as in C#. `rfld`
writes `@` before a type name or a module name of one segment that is a quoted word of §8.1, and
the document test compares its list with the grammar. Member, parameter and local names follow a
type, so they never need it. Alternative: a format rule that no global type and no module has such
a name, which rejects modules compiled from valid source. **Answer:** 2026-10-23: Accepted as
recommended.

**Q83 (BC-11; A.2 item 15). Names that are not identifiers.** The writer and the reader accept any
UTF-8 string as a name: an empty type name, `a b`, or a type name with a dot, which reads as a
namespace. *Recommendation:* a type, field, method, parameter, `MemberRef` and local name is an
`Ident` of §8.1, and a module name, a namespace and an `Export` target a `QName`. The empty name
stands only for namespace 0, a parameter without a name and an export to every module. Source
identifiers (LEX-02) and the `$` names of the compiler fit this rule. Alternative: a quoted form
for any name, as ILASM's `'...'`, with quoted segments in dotted names. **Answer:** 2026-10-23:
Accepted as recommended.

**Q84 (BC-03; A.2 item 16). `ref` inside another type.** The reader accepts `ARRAY` or `PTR` of
`BYREF`, but `ref` binds before suffixes in `.rfla`, so such a type has no text. A type argument, a
wildcard bound or a `ref` of `ref` can hold `BYREF` too, and no language gives such types.
*Recommendation:* `BYREF` stands only as a type that a signature lists (depth 1), as in CIL.
Alternative: parentheses in `.rfla`, such as `(ref int)[]`. **Answer:** 2026-10-23: Accepted as
recommended.

**Q85 (BC-11; A.2 item 17). Two members with one name and signature.** `rfla`, the writer and the
reader accept two fields of a type with one name and type, or two methods with one name and
signature. A reference names a member by them (Q69), so `rfld` would print a reference to the
second as one to the first. *Recommendation:* no two `Field` rows of a type have the same name and
type, and no two `Method` rows the same name and signature; `rfla` reports `RFA0301` at the second.
Overloads, and fields of one name with other types, stay valid, as in CIL. Alternative: names
unique per type, as in source. **Answer:** 2026-10-23: Accepted as recommended.

**Q86 (BC-08; README §7.5). The canonical text.** README §7.5 fixed the order, the indentation and
the numbering of labels, but not the form of operands, types and directives. *Recommendation:*
1. `.maxstack` in every body, since the binary holds it, so a body whose depth `rfla` cannot
   compute still round-trips.
2. Operands as the binary holds them: argument and local numbers; integers in decimal; `.section`
   IDs, `bits` and hex strings in uppercase hexadecimal; floats as the shortest decimal that reads
   back to the same bits, `inf`, `-inf`, and `bits` for NaN; strings with the escapes of §8.1, and
   `\u` for each code unit outside printable ASCII.
3. Types in full: primitives by struct name (GEN-12, Q52); `valuetype` before each value type in a
   signature, the module's own types too, and never in a token; member references in full form
   (Q69).
4. The order: `.module`, `.unsafe`, `.main`, `.requires`, one `.exports` or `.opens` per row, the
   classes by `TypeDef` row with nested classes after the fields and methods of their enclosing
   class, then `.source` in DEBUG order and `.section` by ID. In a body: `.maxstack`, `.locals`,
   then before each instruction its label, `.line`, with the path when the file changes, and
   `.stackmap`; the `.try` lines after the code, in the order of BC-18.
5. Labels `L0`, `L1` and so on, only at branch targets and region bounds, numbered in code order;
   one blank line before each top-level class and before the directives after the classes.

Alternatives: no `.maxstack` where `rfla` computes the same depth; non-ASCII characters in strings
as they are. **Answer:** 2026-10-23: Accepted as recommended. With Q80, `.typeref` and `.memberref`
follow the classes, before `.source`. BC-25 states the canonical text.

### B.9 Batch 13: the v0.0.5 interpreter (asked and answered 2026-10-23)

Found while v0.0.5 was planned. Q87 settles A.2 item 18, and Q91 item 19. Q92 follows an
instruction in chat on 2026-10-23: apply MA-01 of
[the meta-analysis](../proposals/meta-analysis.md) in v0.0.5.

**Q87 (NUM-05; README §7.6, §7.8; A.2 item 18). Numbers in the output of v0.0.5.** The programs of
v0.0.5 print numbers, but the natives that turn numbers into text arrive in v0.0.7.
*Recommendation:* the stub `reflect.core` gets `String.valueOf(int)` and `String.valueOf(long)` in
v0.0.5, with Java's signed decimal text. Programs check float results by comparisons and
conversions to `int`, so the default text of floats waits. Alternatives: `valueOf(float)` and
`valueOf(double)` too, with Java's text; or no numbers in v0.0.5, with programs that compare each
result with a constant and print a fixed string. **Answer:** 2026-10-23: First alternative
chosen. v0.0.5 adds `String.valueOf` of `int`, `long`, `float` and `double`. A float or a double
gets the text of Java's `Float.toString` and `Double.toString` from JDK 19: the shortest decimal
that rounds to the value, such as `0.30000000000000004`, `100.0`, `1.0E10`, `NaN`, `Infinity` and
`-0.0`. This fixes the default text of floats in the stdlib, which NUM-05 states. `char` and
`boolean` stay in v0.0.7.

**Q88 (MOD-03, MOD-01, VM-12; README §7.6). How VM0 finds a module.** README §7.6 makes the module
path a list of directories of `.rflc` files, but no rule says which file of a directory holds the
module that `requires m` names, or what a duplicate is. *Recommendation:* the file `m.rflc`, as
.NET probes for `name.dll`. Exactly one directory of the path holds it (MOD-03): none is a missing
module, and two are a duplicate. The module in the file is named `m`. A `requires static` module
that no directory holds is skipped, since it is optional at run time (MOD-01). VM0 resolves the
graph from the main module at start-up (VM-12), rejects a cycle, and reads only the modules that
the graph requires. MOD-03 states the rule for every tool. Alternatives: read every `.rflc` file of
each directory and index the modules by the names in their `Module` tables, as Java's module path
does; or the file name, with the first directory that holds it in place of a duplicate, as Java
does across directories. **Answer:** 2026-10-23: Accepted as recommended.

**Q89 (VM-12, VM-08, BOOT-04; README §6.2). Exit codes of `vm0 run`.** README §6.2 gives the result
of `main`, 1 after an uncaught exception, and 2 when a module cannot be read. It gives no code for
a module graph that does not resolve, a module without a `main` class or method, or code that VM0
cannot run: an instruction outside R0, or one before the stage that brings it.
*Recommendation:* 2 for all three, since VM0 cannot start or cannot run the module; VM0 prints the
reason after `vm0: `. 1 after an uncaught exception, and until v0.0.8 after a fault, with the type
and a message in the form of `Throwable.toString`, such as
`reflect.core.ArithmeticException: division by zero`. A native outside the trusted modules stays a
`TypeLoadError` (VM-08). Alternatives: a new code 3 for code that VM0 cannot run; or a
`TypeLoadError` at the use, which Reflect code could catch from v0.0.8 although `rfl` runs that
code. **Answer:** 2026-10-23: Accepted as recommended.

**Q90 (TST-01; README §6.2, §6.3). How `test` runs a program.** *Recommendation:* `test`
assembles the files of `lib/` first. It runs each file of `run/` on a new VM0 in the JVM of
`test`, with the modules of `lib/` as the module path, so the opcode counts need no file. Standard
output equals the `expect-output` lines, each followed by LF, byte for byte, and the value of a
directive is the text after its colon and one space; without `expect-output`, a program prints
nothing. The exit code is its low 8 bits, as Linux gives it, and equals `expect-exit`, or else 0,
or 1 with `expect-throws`. Standard error is not compared. The directives of a program stand only
in `run/`, as `expect-error` stands only in `invalid/`. After the counts, `test` lists the R0
opcodes that no program runs; the list fails nothing until v0.0.9 (README §8). Alternatives: a
child JVM per program that runs `vm0 run`, as a user does; or `expect-output` lines in order with
other lines between them, as LLVM's FileCheck allows. **Answer:** 2026-10-23: Accepted as
recommended.

**Q91 (BC-04, BC-17, BC-19, LIB-05; A.2 item 19). The count of a 64-bit shift, and a local before
its first store.** *Recommendation:* the count of `shl.i64`, `shr.i64` and `shr.un.i64` is an
`i32`, masked to 6 bits, as `Shiftable<T>` takes an `int` count (LIB-05) and as the JVM and CIL
do. Each local starts at the zero value of its type, 0, 0.0 or null, as CIL's `localsinit` does,
since a frame of BC-19 gives each local a verification type and has none for an unset local.
BC-04 and BC-17 state both. Alternatives: a read before the first store is invalid code, as in
the JVM, with a new verification type `TOP`; or an `i64` count for a 64-bit shift. **Answer:**
2026-10-23: Accepted as recommended.

**Q92 (MA-01; NUM-04, DAT-09, BC-04, WID-07). The canonical NaN.** MA-01 makes an operation that
returns a NaN return the canonical quiet NaN of its format, but gives no bits for `float` and
`double`. Java's arithmetic does not do this: in the build image, `0.0 / 0.0` gives
`0xFFF8000000000000`, and a NaN input keeps its payload (D.6). *Recommendation:* the positive
quiet NaN with payload 0, `0x7FC00000` for `float` and `0x7FF8000000000000` for `double`: Java's
`Float.NaN` and `Double.NaN`, the default NaN of AArch64 and RISC-V, and the positive quiet NaN of
WID-07. `add`, `sub`, `mul`, `div` and `rem`, and the conversions between `f32` and `f64`, return
it for every NaN result; `neg` changes only the sign bit; loads, stores, calls and boxes keep
every bit. NUM-04 states the rule, BC-04 and DAT-09 cite it, and MA-01 records where it went.
Alternative: the default NaN of x86, with the sign bit set (`0xFFC00000` and
`0xFFF8000000000000`), which x86 gives for an invalid operation such as `0.0 / 0.0` but not for a
NaN input with a payload; AArch64 and RISC-V then need a fix-up after every such operation.
**Answer:** 2026-10-23: Accepted as recommended.

<!-- questions:end -->

## C. Traceability

| Roadmap content and instructions | Stages | Rules |
|---|---|---|
| these documents | every stage updates the sections it implements; A.1 | architecture.md §0 |
| the `.rflc` format | v0.0.1, v0.0.2 | BC-02, BC-03, BC-04, BC-10, BC-11, BC-12, BC-17, BC-18, BC-19, BC-20, BC-21, BC-22, BOOT-05 |
| the `reflect.bytecode` design | v0.0.2 to v0.0.4 | ARC-11, BC-08, Q47 |
| the assembler | v0.0.3, v0.0.4 | BC-04, BC-08, BC-11, TST-01, TST-09 |
| VM0 in stage0 | v0.0.5 to v0.0.9 | BOOT-04, VM-03, VM-06, VM-07, VM-12, VM-13, VM-17, VM-20, VM-21, BC-05, BC-06, BC-14, BC-15, BC-16, BC-23, NUL-01, NUL-05, NUL-07, DAT-09, GEN-02, GEN-04, GEN-09, EXC-05, EXC-06, EXC-07, UNS-03, LIB-03 |
| exit test | v0.0.4 (round trip), v0.0.5 (runs on VM0), v0.0.9 (the whole suite) | RM-03 |
| Docker on Windows and Linux, Lean in Docker (instruction, 2026-10-19) | v0.0.0 | BLD-05, BLD-06, TST-05, LEAN-04 |
| a branch per version and frequent small commits (instruction, 2026-10-19) | every stage | RM-04 |
| README for the plan, sketchpad for notes (instruction, 2026-10-19) | every stage | RM-05 |
| features and optimizations to keep possible (instruction, 2026-10-19) | v0.0.1, v0.0.2, v0.0.6, v0.0.7 | HR-01, HR-02, HR-03, HR-04, HR-09, HR-10, HR-11 |
| one NaN rule, MA-01 (instruction, 2026-10-23) | v0.0.5, v0.0.7 | NUM-04, DAT-09, BC-04, Q92 |

## D. Engineering log

### D.1 The Windows machine (2026-10-19)

The documents were drafted on a Linux machine; work continues on a Windows machine. Findings
before v0.0.0:

1. Docker: client and engine 29.6.1; the engine is `linux/amd64`, in the context
   `desktop-linux`.
2. Host tools: a JDK 21 is installed (Liberica 21.0.9); `lean`, `lake` and `elan` are not. Lean
   was installed with elan on the Linux machine (Q44). Here it runs only in Docker.
3. Line ends: the system git configuration sets `core.autocrlf=true`, and the repository has no
   `.gitattributes`. `git ls-files --eol` shows every tracked file as LF in the index and CRLF in
   the working tree, `formal/lean-toolchain` included. v0.0.0 starts with `.gitattributes`.
4. PowerShell: the execution policy is `Unrestricted` in the machine and local-machine scopes, so
   `docker/dev.ps1` runs here. A machine with the Windows default can run it with
   `powershell -ExecutionPolicy Bypass -File docker\dev.ps1 all`.
5. Windows PowerShell 5.1 breaks double quotes inside arguments to native programs. In a probe,
   `docker run ... sh -c` with a script that contained double quotes lost them, and `curl`
   reported "no URL specified". `docker/dev.ps1` must build its `docker` arguments without inner
   double quotes.
6. Network: a TLS-intercepting proxy sits between this machine and the internet. HEAD requests
   with `curl` from a `gcc:14` container (Debian) gave:

   | URL | System trust store | System store plus the proxy CA |
   |---|---|---|
   | `https://github.com/leanprover/lean4/releases` | 200 | not tested |
   | Lean 4.35.0 archive `lean-4.35.0-linux.tar.zst` (redirects to `release-assets.githubusercontent.com`) | TLS failure | 200 |
   | elan archive for x86-64 Linux (same redirect) | TLS failure | 200 |
   | `https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh` | TLS failure | 200 |
   | `https://releases.lean-lang.org/` | TLS failure | 200 |
   | `http://deb.debian.org/debian/dists/trixie/Release` | 200 | plain HTTP |

   Result: on this machine the image build needs the proxy CA while it downloads Lean
   (`REFLECT_EXTRA_CA`, README §5.5). The CA must not enter the repository or an image layer.
7. Base images: `eclipse-temurin:21-jdk` is Ubuntu 26.04 with `curl`, `tar` and `gzip`, but
   without `zstd`, `unzip` and `git`, and `apt-get update` in it gets HTTP 403 from
   `archive.ubuntu.com` through the proxy. `debian:trixie-slim` (Debian 13) reaches
   `deb.debian.org` and offers `openjdk-21-jdk-headless` 21.0.12.1+1-1~deb13u1 and `zstd` 1.5.7.
   Hence the Debian base of README §5.2.

### D.2 Issues to handle in v0.0.0

1. Record the SHA-256 of the Lean archive when the image first builds, and where the value came
   from (Q51).
2. Record the size of the image; the Lean toolchain makes it large. If size or build time becomes
   a problem, split Lean into its own image (BLD-05 keeps one image).
3. Bind mounts from the Windows file system into the WSL 2 VM can be slower than the VM's own
   disk, and Lake writes many small files. Measure the `lean` job; if it is slow, keep
   `formal/.lake` in a named volume.
4. With `--user uid:gid` on Linux, the container user has no home directory; set `HOME=/tmp` in
   the container.
5. After adding `.gitattributes`, renormalize the working tree and check that the index does not
   change (it holds LF already, D.1 item 3).

### D.3 v0.0.0 implementation (2026-10-19)

- LF normalization left every previously tracked file unchanged.
- Lean 4.35.0, `lean-4.35.0-linux.tar.zst`, is pinned to SHA-256
  `7289597a44543246c9227b8cae501767e095ce17f6d448b95fc4a8dc97268f6d`.
  Source: the `digest` field of the asset in the
  [official release API](https://api.github.com/repos/leanprover/lean4/releases/tags/v4.35.0),
  retrieved 2026-10-19. The Docker build independently checks the downloaded bytes.
- The optional proxy CA is a BuildKit secret. Only the download uses the combined CA bundle;
  neither the system trust store nor an image layer receives the extra certificate.
- Linux checks used Docker 29.8.2, OpenJDK 21.0.12.1 and Lean 4.35.0. In a fresh clone in a
  path with spaces, `all` passed in 3.51 s; a second run passed in 2.39 s and reused the same
  image without building. The cached `lean` job took 1.04 s. These are Linux measurements,
  not measurements of a Windows bind mount.
- The image `reflect-build:a5bb09736f0c` is 4,777,768,003 bytes (4.78 GB). Most of its size
  comes from the full Lean release. Keep the single image for now (BLD-05).
- The unit job passes nine tests, including subprocess fixtures for passing and failing tests,
  invalid test methods, an empty suite, missing classes and CLI usage/errors. An injected
  failing suite confirmed that `all` stops before Lean. Rebuilds remove stale classes, and the
  production JAR contains no test classes.
- Both host wrappers passed eight stubbed argument/failure scenarios: invalid and
  case-mismatched jobs, an unavailable engine, job and build failures, a missing CA file, CA
  secret arguments and interactive shell flags. The Linux wrapper also preserves file
  ownership; the Lean job rejects a mismatched toolchain.
- A no-cache image build with an ephemeral test CA verified the Lean checksum, left the system
  CA bundle byte-identical, and retained neither the secret nor temporary download files.
  This checks secret handling, not connectivity behind an actual intercepting proxy.
- PowerShell 7.4.7 on Linux ran `all` against Docker from a separate fresh clone with spaces in
  its path. The official Dev Container CLI 0.89.0 validated the configuration and built the
  editor image; `all` also passed as its non-root `reflect` user.
- Native Windows PowerShell 5.1 (5.1.26100.9444) ran `docker/dev.ps1 all` against Docker Desktop
  4.81.0 (engine 29.6.1) from a fresh clone in a path with spaces. The TLS-intercepting proxy on
  this host presents a self-issued chain (`Self-Managed Root Certificate Authority - G2`), not
  the root; `REFLECT_EXTRA_CA` needed that host-specific PEM, exported from
  `Cert:\LocalMachine\Root`, to let the Lean download verify. With that CA, the first `all` run
  built the image and passed in 181.0 s and produced the same tag, `reflect-build:a5bb09736f0c`,
  as the Linux build (same Dockerfile hash). A second `all` run reused the image and passed in
  8.4 s; the cached `lean` job alone took 5.8 s. `docker/dev.ps1` rejected an invalid job and a
  case-mismatched job (`Lean`) with exit code 2 in both cases, matching the Linux wrapper.
  `docker image inspect` on this host reports the image at 1,058,456,937 bytes, versus
  4,777,768,003 bytes recorded for the Linux host, for the same tag and Dockerfile hash. This
  size difference is unexplained; it has not been traced to a specific cause (for example, a
  reporting difference between image stores versus an actual content difference) and should not
  be treated as settled.

### D.4 v0.0.1 implementation (2026-10-20)

- bytecode.md states format 0.1: 263 opcodes with 159 marked R0, the signature codes, the IDs of
  23 tables with the layouts of the 16 that v0.0.x uses, the header, the sections and heaps, the
  layouts of bodies, regions, frames and DEBUG, the canonical form (BC-24), the limits and the
  `.rfla` grammar. `reflect.stage0.bytecode` declares the numbers in `Opcode`, `SigCode`,
  `TableId`, `Flags`, `Header`, `SectionId`, `VerificationType` and `Limits`.
- `DocumentTest` compares every table with the constants in both directions, checks the family
  ranges of BC-04, and checks that each grammar rule is defined and reachable. The `unit` job runs
  26 tests. For each commit, a changed number, row or constant made a document test fail with the
  file and line in its message, and the change was then reverted.
- The opcode rows and the `Opcode` entries were generated once from one temporary list, so that
  the first versions agree; from then on, only the document test keeps them in step.
- Details decided while the tables were written, all [Proposed]: every column is a u32; each heap
  starts with its empty entry; visibility takes three flags, and none means package-private; keys
  order every row, so `TypeDef` adds the enclosing type, a nested type has namespace 0, `Export`
  has one row per target, and four tables sort by all their columns; `Param` flags keep only
  `scoped` and `this`, since `ref`, `in`, `out` and variadic are in the signature; constructors
  are named `new` and static initializers `static`; a `Constant` blob uses the encoding of the
  field's type; a module never contains `breakpoint`; frames list the arguments before the
  locals; DEBUG lines hold no column.
- The container prints `§` as `?`, so the messages of `DocumentTest` use ASCII only.
- Twice, `docker image inspect` failed with "file has already been closed" on Docker Desktop's
  pipe. The wrapper then rebuilt `reflect-build:a5bb09736f0c` from its cache, with the same tag.
  Nothing needs to change unless it happens more often.
- Exit check: the tables cover every instruction and every table that v0.0.x uses. The Windows
  run, `docker/dev.ps1 all` (Docker engine 29.6.1), came first; the WSL distribution
  `Ubuntu-24.04` had no Docker Desktop integration, so no Linux run was possible then.
- Linux exit check, 2026-10-20: `sh docker/dev.sh all` passes on Docker 29.8.2 (exit 0). It
  reused image `reflect-build:a5bb09736f0c`, the tag the Windows host produced from the same
  Dockerfile hash. The `unit` job ran 26 tests with 0 failures. `lake build` succeeded with the
  two known `sorry` warnings in `Reflect/Core/Soundness.lean`. This meets Q53 for `v0.0.1`.

### D.5 v0.0.4 implementation (2026-10-23)

- Windows exit check: in a fresh clone of `0de78e4`, `docker/dev.ps1 all` passes in 53.4 s with
  Windows PowerShell 5.1.26100.9711 and Docker 29.6.1. The `unit` job runs 190 tests with 0
  failures. The `bytecode` job passes the 8 files of `format/` and `invalid/`, and each file of
  `format/` round-trips. `lake build` succeeds with the two known `sorry` warnings.
- Linux exit check: pending. No Linux host on this machine reaches Docker. In the WSL
  distributions `Ubuntu-24.04` and `archlinux`, Docker Desktop reports that the command `docker`
  could not be found, and recommends its WSL integration. By a decision in chat on 2026-10-23,
  the tag `v0.0.4` came after the Windows check alone, unlike Q53. The Linux result goes here.
- In the container, `LANG` and `LC_ALL` are unset, so the JVM's `stdout.encoding` and
  `stderr.encoding` are `ANSI_X3.4-1968` (ASCII), while `file.encoding` is `UTF-8`. Text printed
  through `System.out` loses every character outside ASCII: `a.Ä` prints as `a.?`, as D.4 found for
  `§`. `rfld` therefore writes the UTF-8 bytes of the disassembler unchanged, and `RfldTest` checks
  this with `a.Ä`. The console output of VM0 (v0.0.5) meets the same encoding.

### D.6 v0.0.5 implementation (2026-10-23)

- NaN bits in the build image (Q92), measured 2026-10-23 in `reflect-build:a5bb09736f0c` with
  OpenJDK 21, alike with the default compilers, `-Xint` and `-Xcomp`; not a repository test:

  | Java expression | Bits |
  |---|---|
  | `0.0 / 0.0` at run time | `FFF8000000000000` |
  | `0f / 0f` at run time | `FFC00000` |
  | a double NaN with payload 1, `+ 1.0` | `7FF8000000000001` |
  | the float `7F800001` converted to `double` | `7FF8000020000000` |
  | `Double.NaN`, `Float.NaN` | `7FF8000000000000`, `7FC00000` |
  | negation of the double `7FF0000000000001` | `FFF0000000000001` |

  A boxed `Double` or `Float` in an `Object[]` keeps every bit, the signalling NaNs
  `7FF0000000000001` and `7F800001` included. VM0 therefore holds floats as Java values, and
  replaces each NaN result of an arithmetic instruction or of a conversion between `f32` and `f64`
  with the canonical NaN.
