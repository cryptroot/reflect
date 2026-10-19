# Reflect sketchpad

Working document for the Reflect language: housekeeping of the older documents (Part A), the log
of every design question and its answer (Part B), and how the proposal maps onto the design
documents (Part C). The design itself lives in the documents listed in C.2;
[architecture.md](architecture.md) is their entry point.

- Inputs: [design-proposal.md](design-proposal.md) (requirements), [examples.md](examples.md)
  (syntax baseline, cited as **ex §N**), [references.md](references.md) (prior art).
- Parts C.3, D, E and F were moved into the documents on 2026-10-19; commit `168e215` keeps the
  earlier text.

## Progress log

| Part | Content | Status |
|---|---|---|
| 0 | Conventions | moved to architecture.md §0 |
| A | Housekeeping: dead references, errors, deviations from the proposal | open for your fixes |
| B | Questions and answers | Q01 to Q44 answered; Q45 onward in [v0.0.x/sketchpad.md](v0.0.x/sketchpad.md) |
| C | Requirements traceability, document plan | done; documents built |
| D, E, F | Language design, implementation, glossary | moved into the documents |

## 0. Conventions

Status tags, rule IDs and prefixes are defined in [architecture.md](architecture.md) §0. Rule IDs
(`SVD-03`) and question IDs (`Q12`) keep their meaning forever; the IDs used in examples.md and
references.md (`F31`, `SV4`, `G1`, ...) have no register (A.2) and are not reused.

## A. Housekeeping

Nothing in examples.md or references.md was edited except the Q12 change (A.6) and the wording of
the Reflect-S entry in references.md (lines 62 and 63, same line count); these lists are for you
to fix or delete. Line numbers refer to commit `00d6207`. The Q12 change added one line after
line 611, so in the current examples.md add 1 to every line number above 611.

### A.3 Leftovers of removed references

- A space before punctuation where an ID was cut: lines 95, 344, 523, 811, 956, 1083, 1645, 1794,
  2008, 2353, 2894, 2912, 2982, 2983, 3106, 3171, 3208.
- An empty comment: line 2421 (`GC.collect(); //`).
- 159 lines end in spaces, mostly comments that lost an ID.
  `sed -i 's/[[:space:]]*$//' docs/examples.md` removes them; review with `git diff`.

### A.4 Errors and inconsistencies in examples.md

1. Line 2738: `println(Object.referenceEquals);` has no arguments. Intended:
   `println(Object.referenceEquals(s1, s2)); // prints: false`.
2. Lines 152 and 1956: namespace `demo.tools` exists in module `demo` (Blocks.rfl) and in module
   `demo.tools` (AutoToString.rfl), and `demo` requires `demo.tools` (line 46). Q13 forbids split
   namespaces, so the block needs another name (A.6).
3. Line 1035: the rule is undecided ("error, or `Shapes<?>` with a warning").
4. Lines 1805, 1847, 1853: `classWide` is declared and read, but `ObservableGenerator` (ex §6.3)
   ignores it and always raises `propertyChanged`.
5. Lines 2395, 2405: `reflect.core.Natives.alloc/free` are public, so any module can double-free.
   They belong behind `unsafe` (UNS-02, Q18: `@Unsafe`).
6. Line 3064: "9.6 Missing" is a placeholder title for destructible objects. The block has no
   `// file:` line and mixes declarations with statements, and `T extends class & Destructible`
   repeats the kind (`Destructible` is already a class).
7. Line 2310: "catching by type argument (not in R0)", yet `Throwing.retry` above it already
   catches `E`. Either `retry` is not R0 or the note is wrong.
8. Line 51: `unsafe; // Raw access to pointers (?)`. The flag also gates proxies and emit until the
   verifier (UNS-02); reading annotation instances no longer needs it (Q10).
9. ex §6.5, last sentence: "`const`-ness may also be applied to `enums`" has no example; DAT-05
   gives it a meaning.

### A.5 Deviations from the design proposal

| Proposal | examples.md | Question |
|---|---|---|
| Item 8: `@Property`, `value.onValueChanged().addListener(x -> ...)` | `@Observable` generator; `valueChanged` and `propertyChanged` events; listeners get `PropertyChange<T>` | Q14: examples kept |
| Item 9: "overriding assignment operators" | compound assignment operators and property setters; plain `=` is not overloadable | Q15: examples kept |
| Item 5 snippet: `void <K extends Kind> Function(K kind)`, `Value`, `string` | `function<K extends Kind>(K kind)`, lowerCamelCase members, `String` | Q16: examples kept |
| Item 6: "prefer a Rust model and avoid a GC" | tracing GC plus C# 11 ref safety; `Borrow` types open | Q05: examples kept |
| Item 4: eager JIT "requested by annotation" | only `@Inline` and `@ForceInline` (ex §6.6) | Q17: `@Precompile` added |
| Item 1: a minimal standard library may use external tooling, "C++ for example" | Java stage0 and VM0 only; the VM is Reflect-S compiled by `rflc` | Q01, Q19: no C or C++ |
| Item 2: "templating and generics day one" | reified generics; templating is the generators' quasi-quotes (ex §6.3) | none |

### A.6 Example changes the answers require

| examples.md | Change | Answer |
|---|---|---|
| §2.9 rejected block | added: `yield` in a block arm is an error (done) | Q12 |
| §2.2 Blocks.rfl, lines 152 and 157 | rename the `demo.tools` block, for example to `demo.measure` | Q13 |
| §6.5 line 2068 | `public const ReadOnlyArray<int> SMALL_PRIMES = { 2, 3, 5, 7 };` | Q27 |
| §8.3 `Accumulator` | `public mutating void operator +=(long x)` | Q24 |
| §8.5 line 2738 | `Object.referenceEquals(s1, s2)` (A.4 item 1) | none |
| §9.6 | title "Destructible objects", a `// file:` header, declarations apart from statements | Q26 |
| §2.1 `module demo` | drop `requires reflect.meta;`, add `requires reflect.meta.dynamic;` (proxies, emit, hot reload) | Q30 |
| §2.1 `reflect.lint`, §6.2 `reflect.observable` | drop `requires transitive reflect.meta;` (the namespace is in `reflect.core`) | Q30 |
| §10.4 to §10.6 | `import reflect.meta.dynamic;` for `Proxy`, `ModuleBuilder`, `HotReload` | Q30 |
| §2.1 `module demo` | optional `version "0.1.0";` (ex §11.1 prints `.module demo 0.1.0`) | Q40 |
| §3.1 `parse` | `parse<T extends Number<T>>`, since `Number<T>` includes `Parsable<T>` | Q39 |
| §6.1 intro, §6.2 descriptors | annotation instances are generated classes: reading them needs no `unsafe;`, so `reflect.observable` and `demo.tools` drop it | Q10 |
| §7.7 | "only `Lockable` objects can be locked"; `Lock` implements `Lockable` | Q37 |
| §10.2 | `cubes.getMethod("add", Cube.class)` | Q36 |

## B. Questions and answers

Answer in chat, in the question prompts, or by editing the **Answer** lines. Answers become
[Decided] rules and are dated.

### B.1 Batch 1: foundations (answered 2026-10-19)

**Q01. What is the VM written in?** v1.0 must maintain itself (proposal item 1). ex §11.4 shows
VM code in Reflect (`vm/src/gc/Pages.rfl`), references.md then modeled "Reflect-S" on Squeak's
VM subset translated to C (reworded since), and the proposal allows C++ for the first runtime.
*Recommendation:* a C++ VM for v0.x, ported to Reflect-S and compiled ahead of time by `rflc`
before v1.0. **Answer:** "Whatever the most direct path is to not needing any external tooling
and will not require maintenance of another language toolchain, follow the Java model explicitly
- allowed to be very slow from the start if necessary to avoid tech debt." Reading, confirmed in
Q19: no C++ at any stage. Stage0 is a Java compiler plus a slow Java-hosted interpreter (VM0)
that runs the first `rflc`; the real VM is written in Reflect (Reflect-S, BOOT-08) and compiled
by `rflc`'s own x86-64 backend into a static ELF that calls Linux directly, so neither a C
toolchain nor libc is needed. See bootstrapping.md.

**Q02. Which language is stage0 written in?** ex §12.3 uses "R0-shaped Java" transliterated once
into the first `rflc`. *Recommendation:* Java 21+. **Answer:** Java 21+. [Decided]

**Q03. How does a fresh checkout bootstrap after self-hosting?** *Recommendation:* commit a
portable seed (the `rflc` bytecode plus a prebuilt VM), as OCaml (`boot/`) and Zig (`zig1.wasm`)
do. **Answer:** download the previous release binary (Rust model); the first release is built
through the stage0 chain. See bootstrapping.md.

**Q04. Which platforms must v1.0 support?** This drives the JIT/AOT backends, the natives and
whether pointer size can vary. *Recommendation:* Linux x86-64 first, 64-bit only.
**Answer:** Linux x86-64 only; Docker images for tooling and builds; Windows users run Reflect
through Docker. Pointers are 8 bytes and little-endian everywhere, so `sizeof` of a
pointer-holding struct is a constant (UNS-05, JIT-09).

**Q05. Memory model.** Proposal item 6 prefers a Rust model if feasible; the examples use a
tracing GC with C# 11 ref safety (ex §9.1) and leave `Borrow` types open. *Recommendation:* GC
plus ref safety; evaluate `Borrow` types after v1.0. **Answer:** as recommended. [Decided]

**Q06. Collector path.** *Recommendation:* non-moving mark-sweep, then Immix (mostly
non-moving, so pinning stays cheap), generational later. **Answer:** "Simple first GC for proven
implementation. Extensive changes will come later to improve performance / generational GC -
ensure we do not implement ourselves into a corner." So: a non-moving mark-sweep collector first,
with every hook a moving, generational collector needs present from day one (GC-02).

**Q07. Threads.** ex §7.7 defers threads past v1.0. *Recommendation:* a single-threaded VM until
then, with class initialization, GC safepoints and the JIT designed for threads.
**Answer:** as recommended. [Decided] (CON-01)

**Q08. Bytecode shape.** *Recommendation:* stack-based on disk like ex §11 (compact, easy to emit
and verify); the VM converts it to a register or quickened form for speed. **Answer:** as
recommended. [Decided] (BC-01)

**Q09. Reified nullability.** ex §2.10 and §3.5 make `List<String>` and `List<String?>` distinct
run-time classes and check nullability on array stores. *Recommendation:* keep.
**Answer:** keep. [Decided] (NUL-01)

**Q10. Annotation instances.** Reading them needs `unsafe;` because they are proxies (ex §6.1).
*Recommendation:* the compiler generates one implementation class per annotation type; no proxy,
no `unsafe;`. **Answer:** deferred; proxies are not a commitment; both options were compared.
Revisited in B.4: generated classes (META-03).

**Q11. Non-null fields during construction.** A base constructor that calls an overridden method,
or an early `this` escape, can observe an unassigned non-null field. *Recommendation:* field
initializers run before `super(...)` (C# order), statements may precede `super(...)` (Java 25),
and early escapes warn. The VM null-checks every dereference, so memory safety does not depend
on this. **Answer:** Java/Kotlin order, warnings only. [Decided] (NUL-04)

**Q12. Value of a switch-expression block arm.** ex §2.9 uses `return`. *Recommendation:* Java's
`yield`; `return` inside a switch expression stays an error. **Answer:** "Use `return` and update
example, yield should be reserved for enumerator/iterator use later (C# model)." ex §2.9 now also
rejects `yield` in a block arm. [Decided] (LEX-08, STM-03)

### B.2 Batch 2: language details (answered 2026-10-19)

Each question names the rule its answer settles.

**Q13 (MOD-04).** May two modules contain the same namespace? *Recommendation:* no, as Java.
**Answer:** no. [Decided]

**Q14 (MEM-17, META-04).** Proposal item 8: keep `@Observable` with generated `xChanged` and
`propertyChanged` events, or the proposal's `value.onValueChanged().addListener(...)`?
*Recommendation:* keep the events. **Answer:** keep the events. [Decided]

**Q15 (OPR-09).** Is overloading plain `=` required? *Recommendation:* no; setters and compound
assignment operators cover the uses. **Answer:** no. [Decided]

**Q16 (A.5).** Java naming (lowerCamelCase members, `String`) and type parameters after the
method name, as in the examples? *Recommendation:* yes. **Answer:** as in the examples. [Decided]

**Q20 (STM-06).** Iterators with `yield return`: before or after v1.0? *Recommendation:* after
v1.0, keeping v1 small. **Answer:** "Define what 'tree walks' mean in this context and how they
could be useful here? They would be a late stage feature at best (close to v1 release if
before)." Late feature, close to v1.0 at best [Decided]; STM-06 explains tree walks.

**Q21 (MEM-09).** Extension methods as a general feature with C#'s scoping? *Recommendation:*
yes. **Answer:** yes, with C#'s scoping. [Decided]

**Q22 (MEM-10).** Varargs syntax? *Recommendation:* Java's `Object?... args`. **Answer:** Java's syntax. [Decided]

**Q24 (DAT-02).** Methods called on read-only struct locations? *Recommendation:* Swift's
`mutating`, methods read-only by default. **Answer:** `mutating`. [Decided]

**Q25 (META-06).** Template hygiene? *Recommendation:* library names bind where the template is
written. **Answer:** as recommended. [Decided]

**Q26 (DAT-08).** Destructible objects: which accesses throw, and `destroy()` or
`Object.destroy(x)`? *Recommendation:* the guarded members of DAT-08 and a protected
`destroy()`. **Answer:** as recommended. [Decided]

**Q27 (META-07).** `const` arrays? *Recommendation:* a read-only type. **Answer:** a read-only type (META-07). [Decided]

### B.3 Batch 3: implementation (answered 2026-10-19)

**Q17 (JIT-03).** Annotation for eager compilation? *Recommendation:* `@Precompile`; otherwise
tier 1 compiles at a method's first call. **Answer:** as recommended. [Decided]

**Q18 (UNS-02).** What `unsafe` gates, and may the VM refuse unsafe modules?
*Recommendation:* the four rules of UNS-02. **Answer:** all four. [Decided]

**Q19 (BOOT-01).** Is the reading of Q01 in B.1 right? **Answer:** correct. [Decided]

**Q23 (MEM-16, GC-05).** Weak references in `reflect.core`? *Recommendation:* yes, plus a
weak-listener wrapper. **Answer:** as recommended. [Decided]

**Q28 (UNS-04).** Calling C libraries? *Recommendation:* after v1.0; it needs our own dynamic
loader because there is no libc. **Answer:** after v1.0. [Decided]

**Q29 (GC-04).** When do finalizers run? *Recommendation:* also after every `GC.collect()` and
at idle points the program declares. **Answer:** as recommended. [Decided]

**Q30 (LIB-02).** Where do `Class<T>`'s reflective members live? *Recommendation:* namespace
`reflect.meta` inside module `reflect.core` (Java's `java.base` model). **Answer:** as
recommended. [Decided] A.6 lists the example changes.

**Q31 (LEAN-05).** Is the Lean model a parallel track or a gate? *Recommendation:* parallel,
FR-core, FR-null and FR-static first. **Answer:** a gate: a rule ships only after its layer is
proven. [Decided] Q33 and Q34 set its scope.

**Q32 (RM-01).** Roadmap; and should the first binary release come as soon as `rfl` runs `rflc`
(v0.6) so Java leaves earlier? *Recommendation:* yes. **Answer:** yes, v0.6. [Decided]

**Q33 (LEAN-05).** What does the gate apply to? *Recommendation:* v1.0 only: drafts may
implement and use rules before their proofs. **Answer:** as recommended. [Decided]

**Q34 (LEAN-05).** If a hard layer (FR-wild, FR-ref) lags, what goes into v1.0?
*Recommendation:* a smaller provable subset of each feature. **Answer:** as recommended.
[Decided]

### B.4 Batch 4: before the documents (answered 2026-10-19)

These answers are applied in the documents of C.2.

**Q10 (META-03), revisited.** **Answer:** B, a generated implementation class per annotation
type; reading annotations needs no `unsafe;`. [Decided]

**Q35 (MOD-10).** Should run-time module layers be unloadable? *Recommendation:* yes, by design.
**Answer:** as recommended. [Decided]

**Q36 (REFL-02).** A name-only lookup of an overloaded method? *Recommendation:*
`getMethod(name, parameterTypes...)`, and the name-only form throws. **Answer:** as recommended.
[Decided]

**Q37 (CON-02).** Lock model? **Answer:** "Lock objects with inheritable lockability (special
class like Destructible) - if this is not possible or ill-advised, say so immediately and we will
reconsider." Possible, but a base class takes the single superclass slot (no lockable
`Destructible`); follow-up answer: a VM-known marker interface `Lockable`. [Decided]

**Q38 (VM-05).** Interpreter dispatch? **Answer:** a plain switch loop. [Decided]

**Q39 (LIB-05).** Generic-math interfaces? **Answer:** per-operator interfaces plus aggregates
(.NET's design, trimmed). [Decided]

**Q40 (MOD-03).** Build description? **Answer:** `version` in `module-info.rfl` plus a declarative
`workspace.rfl`. [Decided]

**Q41 (LEX-04).** Documentation comments? **Answer:** `///` lines with Markdown and
`@param`/`@return`/`@throws` tags. [Decided]

**Q42 (LIB-04).** Read-only collections? **Answer:** Java's model plus read-only supertypes
(`ReadOnlyList<T>`, which `List<T>` extends). [Decided]

**Q43 (JIT-04).** Application AOT? **Answer:** mixed-mode application AOT in v1.0. [Decided]

**Q44 (LEAN-04).** Lean now? **Answer:** "I've installed elan-lean (you may need a terminal
refresh), scaffold checked code." [Decided]

<!-- questions:end -->

## C. Requirements and document plan

### C.1 Traceability

| # | Proposal requirement | Status | Designed in |
|---|---|---|---|
| 1 | Compiles itself soon; no outside tooling to maintain | Invariant | [bootstrapping.md](bootstrapping.md), [architecture.md](architecture.md), [roadmap.md](roadmap.md) |
| 2 | Reflection at run time and compile time; generics from day one | Invariant | [reflection.md](reflection.md), [type-system.md](type-system.md) §8 |
| 3 | Java/C#-like syntax | Invariant | [grammar.md](grammar.md), [type-system.md](type-system.md) |
| 4 | VM with custom bytecode; lazy JIT, AOT, inlining | Desired | [bytecode.md](bytecode.md), [vm.md](vm.md), [jit-aot.md](jit-aot.md) |
| 5 | Static virtual dispatch | Desired | [type-system.md](type-system.md) §7, [vm.md](vm.md) §5 |
| 6 | Memory safety | Desired | [memory.md](memory.md), [type-system.md](type-system.md) §3, §11, §14 |
| 7 | Annotations, functional interfaces, lambdas, callbacks | Invariant | [reflection.md](reflection.md) §4, [type-system.md](type-system.md) §9, §13 |
| 8 | Generated methods, property dispatch | Invariant | [reflection.md](reflection.md) §3, [type-system.md](type-system.md) §9 |
| 9 | Operator overloading (custom operators under discussion) | Invariant | [type-system.md](type-system.md) §10, [grammar.md](grammar.md) §5 |
| - | v0.0.1 to v0.9.9 drafts, then a self-maintaining v1.0.0 | Invariant | [roadmap.md](roadmap.md) |
| - | soundness of the design | Q31 | [formal-model.md](formal-model.md), `formal/` |

### C.2 Document plan (built 2026-10-19)

| Document | Scope |
|---|---|
| [architecture.md](architecture.md) | conventions, components, principles, repository layout, compiler pipeline, dependency rules, builds and `workspace.rfl`, tool commands, Docker images, glossary |
| [bootstrapping.md](bootstrapping.md) | stage0 and VM0 in Java, R0, transliteration, fixpoint, Reflect-S and the boot image, releases, retiring Java |
| [grammar.md](grammar.md) | lexical structure, EBNF, precedence, ambiguities, syntax reserved for later |
| [type-system.md](type-system.md) | modules, types, null safety, numerics, statements, members, static virtual dispatch, generics, properties and events, operators, data types, exceptions, lambdas, unsafe code, concurrency |
| [bytecode.md](bytecode.md) | module file, metadata tables, signatures, instruction set, verification, `.rfla`, binary compatibility |
| [vm.md](vm.md) | process, loading and layers, object layout, run-time types, dispatch, interpreter, class initialization, exceptions, natives, reflection support, hot reload, locks |
| [memory.md](memory.md) | allocation, mark-sweep, roots and safepoints, hooks for later collectors, finalization, weak references, destructible objects |
| [jit-aot.md](jit-aot.md) | the x86-64 backend, ELF writer, tiers, copy-and-patch JIT, calling convention, AOT, code memory |
| [reflection.md](reflection.md) | `reflect.meta`, phases and access, generators and templates, annotations, `const` evaluation, analyzers, proxies, emit, hot reload, sandboxing |
| [stdlib.md](stdlib.md) | `reflect.core`, generic math, collections, text and IO, other modules |
| [testing.md](testing.md) | test kinds, conformance format, feature and proof status, differential and bootstrap tests, fuzzing, examples as tests |
| [formal-model.md](formal-model.md) and `formal/` | Featherweight Reflect layers, the v1.0 gate, the checked FR-core scaffold |
| [roadmap.md](roadmap.md) | milestones v0.0.1 to v1.0.0 and the proof track |

examples.md stays the home of examples; the sketchpad keeps the housekeeping lists and the
question log.

