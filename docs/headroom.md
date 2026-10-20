# Headroom

Features and optimizations that early decisions could rule out by accident, each with a judgement
and the cheap step that keeps it possible. Java added generics after its class files and
libraries had shipped, so it erased them; Reflect has reified generics from the start. The same
gap gave Java covariant arrays, which Reflect no longer copies (HR-11). Conventions:
[architecture.md](architecture.md) §0.

The unscheduled [threads, atomics and lock-free semantics proposal](threading.md) expands the
concurrency hooks, particularly HR-19, HR-21, HR-22 and HR-23. It does not accept these entries
or change CON-01's single-threaded baseline.

The separate [GPU access and Reflect-G proposal](gpu.md) records seams for device resources,
transfer layouts and a GPU-resident interpreter. It adds no accepted headroom entries, required
backend or bootstrap dependency.

## 0. Reading an entry

- An entry names what it keeps possible, then gives *Today* (what the rules say now),
  *Precedent* (what other systems gained or lost) and *Step* (the decision that keeps it
  possible).
- Judgements: **want** (plan for it; the step is cheap), **keep open** (Reflect may never use it;
  the step only avoids ruling it out) and **avoid** (rule it out on purpose).
- *Decide by* is the first stage that builds on the choice. Formats change freely before v1.0;
  afterwards most changes break (BC-09, BC-10).
- An entry never redefines another area's rule. Once an entry is accepted, the owning rule
  changes and the entry records where it went (*Went to*); its *Today* then records the rules as
  they were before the decision.
- Q57 to Q62 are in Batch 6 of [v0.0.x/sketchpad.md](v0.0.x/sketchpad.md). The other entries
  wait for the stage that first needs them.

## 1. Summary

| ID | Keeps possible | Judgement | Decide by | Question |
|---|---|---|---|---|
| HR-01 | new sections and flags without breaking older readers | want | v0.0.1 | Q57 |
| HR-02 | stripped debug data, signatures, stable deltas and build caches | want | v0.0.2 | Q58 |
| HR-03 | random access to rows; columns added later | want | v0.0.1 | Q59 |
| HR-04 | large generated code without silent overflow | want | v0.0.1 | Q59 |
| HR-05 | version diagnostics and a later compatibility policy | keep open | v1.0 | - |
| HR-06 | experimental features | want | v1.0 | - |
| HR-07 | unsafe modules on other architectures | want | v0.6 | - |
| HR-08 | build-time initialization that preserves behavior | want; `beforefieldinit`: avoid | v0.6 | - |
| HR-09 | folding final fields | want | v0.0.6 | Q60 |
| HR-10 | caching and eliminating boxes | want | v0.0.6 | Q61 |
| HR-11 | no store checks, no null hole in arrays | avoid covariance | v0.0.7 | Q62 |
| HR-12 | seeded or faster hashing | want | v0.1 | - |
| HR-13 | better lowerings without recompiling | want | v0.1 | - |
| HR-14 | changing constants safely | want | v0.1 | - |
| HR-15 | adding enum constants and permitted subtypes | want | v0.1 | - |
| HR-16 | relaxing a parameter to nullable | keep open | v0.1 | - |
| HR-17 | closed-world AOT with reflection | keep open | v0.5 | - |
| HR-18 | layout private to the VM | want | v0.4 | - |
| HR-19 | lightweight threads | keep open | v0.6 | - |
| HR-20 | guaranteed tail calls | want | v0.6 | - |
| HR-21 | load barriers | keep open | v0.6 | - |
| HR-22 | data-race safety | keep open | v1.0 | - |
| HR-23 | AArch64 | keep open | v0.6 | - |

## 2. Module format

- **HR-01 [Decided, Q57]** Unknown content: a later minor version can add sections and flags
  without breaking older readers, and older readers can't misread them.
  - *Went to:* BC-20 (reader actions, version acceptance, no source positions in critical
    content), BC-02 (section numbers, DEBUG at `0x80000001`), BC-17 (no DEBUG index in a body),
    BC-08 (`.section`), VM-21, IC-12; [v0.0.x/README.md](v0.0.x/README.md) §7.2 to §7.5.
  - *Today:* BC-02 defines seven sections, two flag bits and 16 reserved zero bytes; from v1.0 a
    minor version only adds (BC-10). No rule says what a reader does with a section or flag it
    doesn't know.
  - *Precedent:* PNG marks each chunk critical or ancillary, and safe or unsafe to copy, by bits
    of its type; decoders skip unknown ancillary chunks. Wasm skips custom sections; the JVM
    ignores unknown attributes (JVMS §4.7.1).
  - *Step:* bit 31 of a section ID marks the section ancillary. Readers reject a newer
    formatMinor, an unknown critical section and an unknown flag bit, as the canonical form
    already rejects non-zero reserved bytes, and skip an unknown ancillary section. The model
    keeps skipped sections as opaque bytes, so the v0.0.2 rewrite check still holds and tools
    copy them unchanged. The writer stamps the lowest formatMinor its content needs. DEBUG
    becomes ancillary. An ancillary section that describes critical content starts with that
    content's hash (HR-02), so a reader detects a stale copy and a side file finds its module.
  - Readers must reject unknown flags: if they ignored them, no later flag could carry a meaning
    that older readers must not miss.
- **HR-02 [Decided, Q58]** Identity that survives stripping: debug data can be removed or kept
  apart, modules can be signed, and deltas and build caches stay valid.
  - *Went to:* BC-22 (hash input, section-relative offsets, source hashes in DEBUG), BC-02
    (`reserved` before `sectionCount`), CMP-04 (build code by whole-file hash), REFL-07;
    [v0.0.x/README.md](v0.0.x/README.md) §7.3.
  - *Today:* contentHash is the "SHA-256 of all sections, in directory order" (BC-02). A delta
    names its base by it (REFL-07) and incremental builds key on it (CMP-04), so stripping DEBUG
    or moving a line changes the module's identity and the keys of every module that reads it.
  - *Precedent:* Authenticode hashes a PE file without its certificate table; GNU build IDs and
    PDB GUIDs tie separated debug data to its binary.
  - *Step:* the hash covers formatMajor, formatMinor, flags, and each critical section as (ID,
    length, bytes) in directory order. A stripped module keeps its identity, so deltas still
    apply; an edit that only moves lines keeps its dependents' caches; a signature section can
    sign the hash.
- **HR-03 [Decided, Q59]** Fixed-width rows: any row can be read without decoding the rows
  before it, and a later minor version can append columns.
  - *Went to:* BC-10, BC-11 (widths, row sizes, appended columns), BC-02 (TABLES), VM-09,
    VM-13; [v0.0.x/README.md](v0.0.x/README.md) §7.2 and §7.3.
  - *Today:* TABLES holds a row count per table, then the rows (BC-02); integers in variable
    positions use LEB128 (BC-10); tables are decoded on demand (VM-13).
  - *Precedent:* ECMA-335 tables have fixed-width rows (§II.24.2.6); ELF states its entry sizes
    (`e_phentsize`, `e_shentsize`) so entries can grow.
  - *Step:* columns have fixed widths; heap offsets, tokens and row numbers are u32. TABLES gives
    the row count and row size of each table. The row size follows from the table and the
    formatMinor, so the canonical form stays unique. In a module of an earlier minor, a reader
    treats the columns appended since as zero, for example a later variance column in
    GenericParam. LEB128 stays in blobs and code.
- **HR-04 [Decided, Q59]** Stated limits: generated code that is too large fails with a
  diagnostic, never with a truncated module.
  - *Went to:* BC-21, which readers enforce too, with signature depth and identifier length
    added; [v0.0.x/README.md](v0.0.x/README.md) §7.2 to §7.4.
  - *Today:* tokens hold 24-bit row numbers (BC-02); no rule states the other limits.
  - *Precedent:* the JVM limits a method to 64 KiB of code, a class to 65,535 constant-pool
    entries and a method to 255 parameter slots; generated code meets "code too large".
  - *Step:* bytecode.md lists every limit (rows 2^24−1, heap and section sizes, code length,
    locals, stack depth, parameters, exception regions), chosen generously. The writer and
    `rfla` report a diagnostic instead of writing a truncated module.
- **HR-05 [Proposed]** Recorded versions: diagnostics can name the version a module was compiled
  against, and a compatibility policy can come later.
  - *Today:* `Module` has a version (BLD-01), but `ModuleRef` holds only a name and flags
    (BC-11); `requires` names no version, and the module path supplies one module per name
    (MOD-03).
  - *Precedent:* JPMS records `requires_version` in `module-info.class` for information only;
    .NET records a version in every AssemblyRef and binds by it.
  - *Step:* a `ModuleRef` column holds the version compiled against. Resolution ignores it
    (MOD-03 stands); diagnostics and tools read it. With HR-03 it can be appended at v1.0.
- **HR-06 [Proposed]** Experimental features: a module that uses one can't reach a release by
  accident.
  - *Today:* experimental features need `--experimental` from v1.0 (TST-06); the header defines
    flag bits 0 (unsafe) and 1 (delta) (BC-02).
  - *Precedent:* JEP 12 stamps preview class files with minor version 0xFFFF; the JVM loads them
    only with `--enable-preview` and only on the release that compiled them.
  - *Step:* flag bit 2 marks a module that uses an experimental feature. The VM loads it only
    with `--experimental` and only if the module's format version is the VM's own. Because
    readers reject unknown flags (HR-01, BC-20), the bit can come with v1.0.
- **HR-07 [Proposed]** System calls by name: unsafe modules can run on other architectures.
  - *Today:* ex §11.4 declares `extern static ubyte* mmap(...)` without a number, which "becomes
    a wrapper over syscall 9" under the x86-64 convention (UNS-04); VM-22 has one wrapper per
    call number. Unsafe instructions, `syscall` among them (BC-04), arrive with v0.6.
  - *Precedent:* Linux's generic table, used by AArch64 and RISC-V, numbers `mmap` 222 and has
    `openat` and `clone` but no `open` or `fork`. WASI imports system functions by name.
  - *Step:* an `extern static` method has no body in its module. The VM binds it by name and
    signature, as it binds natives (UNS-03), through a table per architecture keyed by the
    generic Linux names. Only the VM's own wrappers contain `syscall <n>`.

## 3. VM0 semantics

- **HR-08 [Proposed]** Build-time initialization that preserves behavior: AOT and the boot image
  can pre-run static initializers without changing what a program observes.
  - *Today:* a class initializes at its first active use, per instantiation (VM-07); compiled
    code computes exactly what the interpreter does, in the same order (JIT-07), and
    differential tests compare the tiers (TST-08). AOT and the boot image pre-run initializers
    that are deterministic (JIT-04, BOOT-13).
  - *Precedent:* .NET's `beforefieldinit` lets an initializer run at any time before the first
    access to a static field (ECMA-335 §II.10.1.6), and CA1810 recommends it for speed. GraalVM
    Native Image 19.0 made run-time initialization the default and build-time initialization
    opt-in per class.
  - *Step:* pre-run an initializer only if it completes in a sandbox like the one for
    compile-time code (REFL-11, META-09), reads only constants and the statics of classes
    pre-run the same way, and writes only its own statics. Otherwise the class initializes at
    run time. Pre-running is then unobservable, so VM-07's timing stays exact and every tier
    agrees. Compiled code patches a remaining check out once its class is initialized, as
    HotSpot's C1 patching stubs do.
  - `beforefieldinit` is judged avoid: the timing of an initializer's effects would depend on
    the tier, against JIT-07 and TST-08. Without the freedom to run early, relaxed timing only
    moves checks: an instance method could run before its class is initialized, so its static
    accesses would need checks.
- **HR-09 [Decided, Q60]** Final fields stay final: once a final field is set, the JIT and AOT
  can treat its value as a constant.
  - *Went to:* BC-23, which adds that the target is the constructor's own `this`, that a class
    constructor runs only on a new object, that `ldflda` of a final gives `ref readonly`, and that
    nothing stores a `const`; BC-07, VM-16, MEM-05, REFL-03, REFL-07, EXC-05, JIT-08, TXN-13;
    [v0.0.x/README.md](v0.0.x/README.md) §7.7.
  - *Today:* loading checks `final` classes and methods (VM-13, v0.0.6 commit 7), but no rule
    says which code may store to a final field. Reflection reaches the private members of an
    opened namespace (REFL-03).
  - *Precedent:* in class files of version 53 (JDK 9) and later, only the class's own
    initializers may store to its final fields. HotSpot still can't trust instance finals by
    default (`TrustFinalNonStaticFields`), because reflection and deserialization may change
    them; JEP 500 (JDK 26) starts to close that. .NET Core 3.0 throws when
    `FieldInfo.SetValue` writes an initialized static readonly field.
  - *Step:* `stfld` on a final field only in a constructor of the declaring type, `stsfld` on a
    static final only in its static initializer. VM0 checks this at load from v0.0.6, the
    verifier later (BC-07). `Field.set` on a final throws `IllegalAccessException` even with
    `opens` (v0.5), and hot reload never changes a final (REFL-07). Java's exception exists for
    deserialization, which Reflect doesn't build in. v1 has no `with` expressions and no `init`
    accessors (STM-05, MEM-12); either would need its own rule for writing finals.
- **HR-10 [Decided, Q61]** Boxes without identity: the VM can cache boxes, share equal ones and
  remove them after escape analysis.
  - *Went to:* DAT-09, which compares primitive fields by bit pattern rather than as `equals`
    does, since `equals` treats NaNs with different bits as equal and sharing them would change
    the bits a program reads; BC-04, OPR-04, MEM-07, DAT-01, DAT-02, VM-02, GC-05, REFL-03,
    ex §8.5; [v0.0.x/README.md](v0.0.x/README.md) §7.7 and §7.8. Q65 then exempted box-mutable
    types, such as the struct iterators: their boxes are ordinary objects (DAT-09). Primitives,
    enums, tuples and every other value type keep boxes without identity.
  - *Today:* a value type converted to `Object` or an interface is boxed (DAT-01), and a box is
    a header and the value (VM-02). `ceq.ref` and Object-typed `==` compare identity (BC-04,
    OPR-04), and ex §8.5 calls `referenceEquals` "identity, always". So a program can tell two
    boxes of one value apart and hold one weakly (GC-05).
  - *Precedent:* Java caches `Integer` boxes from −128 to 127 (JLS §5.1.7), so `==` on boxes
    depends on the value; JEP 390 warns about synchronizing on value-based classes; JEP 401
    compares value objects by substitutability.
  - *Step:* `ceq.ref` on two boxes compares class and contents: primitives as `equals` does
    (NUM-04), struct fields recursively. A box has no identity hash; hashing one hashes its
    value. Constructing a `WeakReference` to a box throws; locking already needs `Lockable`
    (CON-04). Boxes stay immutable: `unbox` copies, reflection can't set a box's fields, and
    either a `mutating` method can't implement an interface member or calling one through a box
    is an error (decide by v0.4). The cost is a slow path when two references differ and their
    static types admit boxes. The comment "identity, always" in ex §8.5 changes.
- **HR-11 [Decided, Q62]** Invariant reference arrays: a reference store needs no type or
  nullability check.
  - *Went to:* NUL-07, TYP-02, NUL-01, NUL-05, MEM-10 (an array passed to `Object?...` becomes
    one element, with a warning), EXC-05, BC-04, BC-07, BC-14, ex §2.10 and §7.8;
    [v0.0.x/README.md](v0.0.x/README.md) §7.8 and §7.9. Correction to the *Step*: a read-only
    covariant view is a new type and changes no program, but making arrays covariant later would
    change what existing array casts and type tests return (`o instanceof Shape[]` on a
    `Square[]`), in compiled modules too.
  - *Today:* reference arrays are covariant, nullability included (NUL-07, ex §2.10), so a
    reference `stelem` checks the element type and nullability and throws
    `ArrayStoreException` (BC-14, NUL-05, EXC-05, ex §7.8). A span needs the exact element type
    (NUL-07), which `ldelema` checks (BC-04).
  - *Precedent:* Java 1.0 made arrays covariant because, without generics, a method that sorts
    or copies any array had to take `Object[]`; C# copied it. Both check every reference store.
    Kotlin and Scala arrays are invariant.
  - *Step:* reference arrays are invariant. Covariant use goes through generic methods
    (`<T extends Shape> void draw(T[] shapes)`), the `Array` base class (ex §2.10) and, later, a
    read-only covariant view such as Kotlin's `Array<out T>`. Adding covariance later is
    compatible and removing it breaks programs, so decide by v0.0.7 commit 2. This reopens a
    decided rule: ex §2.10 and §7.8, NUL-05, NUL-07, BC-14 and EXC-05 change.

## 4. Compiler and library

- **HR-12 [Proposed]** Hashing free to change: string hashes can be seeded against hash flooding,
  and faster hash functions can come later.
  - *Today:* no document defines `String.hashCode` or the iteration order of `HashMap`; BOOT-05
    already keeps hash order out of build output.
  - *Precedent:* javac bakes `String.hashCode` values into string switches, so Java can't change
    that function, and hash flooding (28C3, 2011) needed another fix (JEP 180). Python
    (PEP 456), .NET Core and Rust's `HashMap` seed their hashes. Python 3.7 made dict order
    insertion order; Go randomizes map iteration so that no program depends on it.
  - *Step:* `String.hashCode` stays unspecified and may be seeded. Lowered code never contains a
    hash value: a string switch compares lengths and characters, or calls a helper (HR-13).
    `HashMap` and `HashSet` iterate in insertion order, so a seed never changes output. The boot
    image fixes the seed per image or rehashes its tables at start-up.
- **HR-13 [Proposed]** Lowerings that improve without recompiling: interpolation, record members
  and switches get faster when the library does.
  - *Today:* interpolation lowers to appends on a stack-allocated builder (NUM-05); records have
    synthesized members (DAT-04); there is no `invokedynamic` (FUN-06); `rflc` never inlines
    across methods (META-10).
  - *Precedent:* Java moved string concatenation (JEP 280) and record members (`ObjectMethods`)
    onto `invokedynamic`, and Kotlin 2.0 its lambdas, so old class files get new strategies.
    C# 10 lowers interpolation onto `DefaultInterpolatedStringHandler`, a library type.
  - *Step:* lowerings call narrow stdlib members whose contract is stated and whose
    implementation is free: an interpolation handler, `HashCode.combine`, switch helpers.
    Synthesized names are not API (REFL-13).
- **HR-14 [Proposed]** Constants that can change: a library can change a constant or an enum
  value without leaving stale copies in the modules that use it.
  - *Today:* `ldconst <tok>` reads a constant at run time (BC-04), but switch labels (STM-02),
    folded expressions, annotation values and the results of `const` methods (META-07) are
    copies in the using module. BC-09 doesn't say whether changing a constant breaks.
  - *Precedent:* C# copies `const` values into callers, so changing one needs every user
    recompiled. Java does the same with constant variables (JLS §13.4.9) and guards a switch
    over another class's enum with a synthesized `$SwitchMap$` array.
  - *Step:* `ldconst` stays for plain reads. Every copy of another module's constant or enum
    value (switch labels, folded expressions, annotation values) is recorded with its token and
    value; the loader checks the record, and a mismatch is a link error. The results of another
    module's `const` methods can't be rechecked cheaply, so BC-09 lists changing them as
    breaking, along with changing a constant or an enum value. Default parameters, if they come
    (MEM-11), are copies too.
- **HR-15 [Proposed]** Enums and sealed types that can grow: a library can add an enum constant
  or a permitted subtype without leaving its users' switches undefined.
  - *Today:* a switch expression is exhaustive over an enum or a sealed type without `default`
    (STM-02); permitted subtypes live in the sealed type's module (DAT-06); casting an
    undeclared value to an enum throws `ClassCastException` (DAT-05). BC-09 says nothing about
    adding either.
  - *Precedent:* an exhaustive switch in Java 21 throws `MatchException` on an unknown case, and
    C# throws `SwitchExpressionException`; Swift has `@unknown default` and Rust
    `#[non_exhaustive]`.
  - *Step:* an exhaustive switch over a type from another module gets a synthesized default that
    throws a new unchecked `MatchException` (a new row in EXC-05). BC-09 then lists adding an
    enum constant or a permitted subtype as binary-compatible but source-breaking.
- **HR-16 [Proposed]** Relaxing a parameter: a library can widen a parameter from `String` to
  `String?` without breaking its callers.
  - *Today:* nullability is reified (NUL-01), and BC-09 lists changing it as breaking. TYP-07
    says "a different parameter list adds an overload" and is silent on lists that differ only
    in nullability.
  - *Precedent:* Kotlin keeps nullability out of JVM descriptors and C# keeps it in attributes,
    so both can relax a parameter without breaking callers.
  - *Step:* overloads that differ only in nullability are an error. Linking may then match
    signatures up to nullability, but only for members that can't be overridden: an overrider
    may rely on a non-null parameter, so relaxing it there is unsound.
- **HR-17 [Proposed]** Closed-world AOT with reflection: an AOT build can drop unreached members
  even in programs that reflect.
  - *Today:* AOT keeps metadata (JIT-04); a closed-world option comes after v1.0 (JIT-14);
    reflection looks members up by name (REFL-13).
  - *Precedent:* GraalVM Native Image needs reachability metadata for reflected members; .NET
    trimming relies on `[DynamicallyAccessedMembers]` and `[RequiresUnreferencedCode]`.
  - *Step:* other modules can reflect only what the declaring module exports or opens
    (REFL-03), which bounds the members an AOT build keeps. An annotation marks `Class<?>`
    parameters whose members are reflected, and an analyzer warns about member names computed
    at run time.

## 5. The VM in Reflect-S

- **HR-18 [Proposed]** Layout private to the VM: compressed references, smaller headers and
  niches in nullable values stay possible.
  - *Today:* VM-02 fixes a two-word header, references first in classes, declared order in
    structs and an `int` array length padded to 8 bytes; UNS-05 proposes sequential struct
    layout; BC-09 lists changing a layout that unsafe code depends on as breaking.
  - *Precedent:* HotSpot compressed its references and later its headers (JEP 450, JEP 519); V8
    compressed its pointers in 2020. Rust keeps layout free unless `repr(C)` and stores
    `Option<&T>` in a niche; .NET chooses the layout itself under `LayoutKind.Auto`.
  - *Step:* no API or constant exposes the header size, field offsets or the offset of array
    elements; they stay in the run-time type (VM-03) and in VM constants that Reflect-S reads
    through `Magic` (BOOT-08). Declared order is guaranteed only for unmanaged structs. The
    nullable wrapper is not unmanaged, so `sizeof(int?)` doesn't fold and the wrapper may use a
    niche: an enum's undeclared values or a `boolean`'s 254 unused byte values. The 8-byte
    length slot leaves room for `long` lengths.
- **HR-19 [Open]** Lightweight threads: many cheap threads that block without blocking an OS
  thread.
  - *Today:* one thread (CON-01); no native frames, since natives are Reflect code (UNS-03);
    locks only on `Lockable` (CON-04); the collector already finds interior pointers (GC-12).
  - *Precedent:* Java's virtual threads (JEP 444) were pinned by `synchronized` until JEP 491,
    and scoped values (JEP 506) suit them better than thread locals. .NET ended its
    green-threads experiment in 2023 over native frames, byrefs and spans, and coexistence with
    `async`. Go grows stacks by copying them.
  - *Step:* stack maps record `ref` slots so a stack can move; a frame that holds `stackalloc`
    pointers pins its stack; IO keeps blocking signatures behind the system call wrappers
    (VM-22); scoped values rather than thread locals.
- **HR-20 [Proposed]** Guaranteed tail calls: interpreter loops and stencils written as tail
  calls, and later a source-level `become`.
  - *Today:* compiled code passes arguments in registers, then on the stack, in Reflect's own
    convention (JIT-05); stencils are Reflect-S compiled by the optimizing compiler (JIT-12).
  - *Precedent:* CPython 3.14's tail-calling interpreter and its JIT's templates rely on clang's
    `musttail`; .NET 5 added helpers for tail calls whose stack arguments don't fit; Wasm added
    `return_call`; Rust reserved `become`, which RFC 3407 uses for explicit tail calls.
  - *Step:* in JIT-05's convention the callee pops its stack arguments, so a tail call works
    between any two signatures. The optimizing compiler guarantees the tail calls that Reflect-S
    marks (through `Magic`). User code gets no implicit tail-call elimination, which would drop
    frames from stack traces (EXC-06) and from caller-sensitive lookups (REFL-09).
- **HR-21 [Proposed]** Load barriers: a concurrent compacting collector stays possible.
  - *Today:* GC-02 has a write barrier and no load barrier. Pointers reach only unmanaged data
    (UNS-01), so every load of a reference is one that a compiler emits.
  - *Precedent:* HotSpot routes heap accesses through its Access API, next to the collector
    interface of JEP 304, which let ZGC and Shenandoah add load barriers; LXR (PLDI 2022) gets
    low pauses with write barriers only.
  - *Step:* VM code and JIT stencils load managed references through one accessor.

## 6. Before v1.0

- **HR-22 [Open]** Data-race safety: if Reflect gains threads, its types can rule out data
  races.
  - *Today:* one thread (CON-01); statics belong to layers (VM-15); ref structs never reach the
    heap, so no race can tear a span.
  - *Precedent:* Rust checks `Send` and `Sync`; Swift 6's `Sendable` checking became a long
    migration; Kotlin/Native dropped object freezing in 2022; Dart isolates share no mutable
    objects.
  - *Step:* mark deeply immutable types in metadata, and keep the stdlib's mutable statics few.
- **HR-23 [Proposed]** AArch64: a second backend for the VM and the JIT.
  - *Today:* the backend is x86-64 (v0.6); GC pages are 64 KiB (GC-09), which also suits
    AArch64 kernels with 16 KiB or 64 KiB pages.
  - *Precedent:* x86-64's total store order hides missing barriers that AArch64 exposes. Thread
    pointers differ too: x86-64 keeps thread-local storage below `%fs` (TLS variant II),
    AArch64 above `TPIDR_EL0` (variant I).
  - *Step:* HR-07; explicit acquire and release orderings in VM code; thread-local state through
    one accessor rather than `%fs` offsets.

## 7. Already open

Decisions already taken that keep these possible; they need only to be preserved.

| Stays possible | Through | Compare |
|---|---|---|
| specialization, reflection over type arguments | reified generics (VM-03) | Java's erasure |
| value types | structs from v0.4 (DAT-01) | Java's Project Valhalla, begun in 2014 |
| null checks at run time and in reflection | nullability in metadata (NUL-01) | Kotlin's erased nullability |
| parameter names in reflection | `Param` rows (BC-11) | Java's opt-in `-parameters` (JDK 8) |
| strong encapsulation | access per caller module, no `setAccessible` (REFL-03) | JEP 403 (JDK 17) |
| objects without monitors | opt-in `Lockable` (CON-04) | a monitor on every Java object |
| moving objects with stable hashes | identity hash in the header (VM-02) | - |
| other collectors | GC-02's barrier and hooks, polls from day one (GC-13) | JEP 304 |
| a moving collector with unsafe code | pointers to unmanaged data only (UNS-01), `fixed` now (UNS-06) | - |
| making a method virtual later | `callvirt` across modules (BC-06) | - |
| replacing method bodies | no inlining across methods in `rflc` (META-10) | - |
| changing constants | `ldconst` (BC-04), HR-14 | C#'s copied `const` |
| new instructions, debugger breakpoints | the `0xFE` prefix and `FF` (BC-04) | the JVM's reserved `breakpoint` |
| compact strings | Latin-1 flag (VM-02) | JEP 254 (JDK 9) |
| reproducible builds | BOOT-05, META-09 | - |
| unloading, plugins, hot reload | layers (VM-15) | - |
| new keywords and syntax | reserved words and syntax (grammar.md §2.2, §5) | Java's contextual `var`, `yield`, `record` |
| exception transparency | `throws` type parameters (EXC-02) | Java's lambdas and checked exceptions |
| evolving interfaces, generic math | default and static abstract members (MEM-03, OPR-07) | Java 8, C# 11 |
| deoptimization | frame metadata from the first JIT (JIT-12) | - |
| reflection in AOT builds | metadata kept (JIT-04) | GraalVM's reflection configuration |
| declaration-site variance | use-site variance now (ex §3.2), a column later (HR-03) | - |

## 8. Closed or costly on purpose

Decisions that close a door or carry a cost, kept on purpose.

| Choice | Rule | Cost | Way out |
|---|---|---|---|
| strict IEEE | NUM-04, JIT-07 | no FMA contraction or reassociation; Java chose the same (JEP 306) | `Math.fma`, explicit vectors |
| little-endian, 8-byte pointers | BC-10, UNS-05 | no big-endian or 32-bit targets | 64-bit Wasm (memory64) |
| no libc | UNS-04 (Q28) | no C libraries before v1.0 | Reflect's own dynamic loader after v1.0 |
| no `setAccessible` | REFL-03 | frameworks need `opens` | `opens` |
| finalizers | GC-04 (Q29) | collector work and ordering rules; Java deprecated them (JEP 421) | - |
| exact lazy initialization | VM-07 | checks in compiled code | HR-08 |
| one thread | CON-01 | no parallelism | HR-19, HR-22 |
| `int` array lengths | VM-02 | at most 2^31−1 elements | HR-18's 8-byte slot |
| no default parameters | MEM-11 | more overloads | HR-14, if they come |
| no `invokedynamic` | FUN-06 | lowerings fixed at compile time | HR-13 |
| stack traces at construction | EXC-06 | every exception walks the stack | - |
| final means final | BC-23 (Q60) | no reflective writes to finals; frameworks build objects through constructors | - |
| hot reload without null safety | REFL-07 (Q64) | a non-null field added by a delta is `null` in older objects | initial values carried in the delta |
| boxes without identity | DAT-09 (Q61) | a slow path in `ceq.ref`; no weak references to boxes; box-mutable types excepted (Q65) | - |
| invariant arrays | NUL-07 (Q62) | a `Square[]` is no `Shape[]`; an array passed to `Object?...` becomes one element | generic methods; a read-only covariant view (covariant arrays would change existing type tests) |
