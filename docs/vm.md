# Virtual machine

`rfl`, the Reflect VM: one static x86-64 ELF written in Reflect-S (BOOT-08) that loads `.rflc`
modules, verifies and links them, runs them in an interpreter and a JIT, and provides the run-time
services. Memory management: [memory.md](memory.md); code generation: [jit-aot.md](jit-aot.md);
instruction semantics: [bytecode.md](bytecode.md). Conventions: [architecture.md](architecture.md)
§0.

Proposed dynamic undo logs and transaction-aware managed stores are described in
[Intent Contracts and Transactions](intent-contracts-and-transactions.md); they are not current
VM guarantees.

## 1. Process

- **VM-01 [Decided, Q04]** Linux x86-64 only: one static ELF that calls the kernel directly, run
  natively or through Docker (including on Windows).
- **VM-12 [Proposed]** `rfl run app.rflc` proceeds as follows:
  1. map the boot image (BOOT-13) and initialize the collector;
  2. map the module files given and those found on the module path;
  3. resolve the module graph from `requires` (MOD-03) and refuse unsafe modules not allowed by
     `--allow-unsafe` (UNS-02);
  4. create the boot layer, find the `main` class (descriptor or `--main`) and call `main`;
  5. at exit, run one full collection, drain finalizers (memory.md §6) and exit with the code.

## 2. Loading, linking and layers

- **VM-13 [Proposed]** Loading is lazy: a module's tables are mapped read-only and read in place,
  since rows have fixed widths (BC-11); a type is loaded when first named by executing code,
  reflection or another type's layout. Loading a type checks its constraints, `final`, `sealed`
  and `permits`, computes its layout (§3) and builds its dispatch tables (§5). A value type whose
  `mutating` method fills the slot of a `mutating` interface member is marked box-mutable
  (DAT-09). A failure throws `TypeLoadError` at the use.
- **VM-14 [Proposed]** Verification (bytecode.md §7) runs per method before its first execution,
  for modules outside the trusted release.
- **VM-15 [Decided, Q35]** Layers (ARC-12): every run-time type, method body, compiled code
  block, static storage and interned string belongs to a layer. A child layer (generators in
  `rflc`, emitted modules, proxies, hot-reload deltas, plugins) may be unloaded once nothing
  reachable refers to it; parents never refer to children. Unloading itself may ship after v1.0,
  but no structure may assume types live forever.
- **VM-16 [Proposed]** Tokens resolve on first execution of an instruction; the register form
  (§6) caches the result, so later executions never consult the tables. Resolution also applies
  the checks that depend on the target, such as BC-23's for stores to final fields.

## 3. Object layout

- **VM-02 [Proposed]**
  - Header, two words: the `Class<T>` pointer of the object's run-time type, then a word with GC
    bits (mark, plus bits reserved for age, forwarding and pinning), a lazily assigned 31-bit
    identity hash (never derived from the address, so objects can move), finalizable and
    finalized bits, and the destroying and destroyed bits (DAT-08). No lock bits (CON-04).
  - Fields: references first, then the rest by decreasing size, so the collector scans a
    reference count instead of a bitmap. A subclass appends its fields after its base class's.
  - A class that implements `Lockable` gets a hidden lock word appended to its fields; its
    subclasses inherit it (§12).
  - Structs keep declared order with natural alignment (UNS-05).
  - Arrays: header, `int` length (padded to 8 bytes), elements; the array's run-time type carries
    the element type and its nullability.
  - Strings: header, length, a Latin-1 or UTF-16 flag, inline data (NUM-05).
  - Boxes: header and value. A nullable wrapper boxes to `null` when it has no value. A box
    without identity never gets an identity hash, and `ceq.ref` compares its contents (DAT-09),
    so the VM may cache, share and remove such boxes. A box of a box-mutable type is an ordinary
    object.

## 4. Run-time types and dictionaries

- **VM-03 [Proposed]** A run-time type exists per class, per instantiation (nullability included)
  and per array type, and is reached through its `Class<T>` object (BC-15):

  | Part | Contents |
  |---|---|
  | identity | generic definition, type arguments, kind, module, layer, flags |
  | layout | instance size, reference count, field offsets, lock word offset |
  | vtable | instance virtual methods by slot |
  | interface table | pairs of interface and method-slot block |
  | static dispatch table | static virtual members and virtual constructors by slot (§5) |
  | statics | storage of the type's static fields, allocated at initialization |
  | initialization | state and failure cause (§7) |
  | dictionary | lazily filled slots for derived types, method entry points, field offsets |
  | metadata | links to the module tables for reflection (§10) |

  Consequence of GEN-02: `Box<String>.count` and `Box<String?>.count` are two fields.
- **VM-17 [Proposed]** Code sharing (GEN-09): every instantiation whose type arguments are all
  reference types runs the canonical body of the definition; instantiations with value-type
  arguments get their own body in the JIT and the AOT compiler, while the interpreter runs the
  shared bytecode with sizes and offsets read from the dictionary. Exact type arguments come from:
  - instance members: the `Class<T>` in the receiver's header;
  - static members: the receiver class `R` (SVD-09), which is an instantiation;
  - generic methods: a hidden method-instantiation argument, a small dictionary of its own.

  A dictionary slot is filled on first use, so `List<T>` inside `ArrayList<String>` becomes
  `List<String>` once and is read from the slot afterwards.

## 5. Dispatch

- **VM-04 [Proposed]**
  - `callvirt` loads the vtable slot of the receiver's run-time type. For an interface method it
    searches the receiver's interface table, and the call site caches the result.
  - `call.svirt` loads the static dispatch table slot of the class handle; interface statics are
    found through the class's interface table like instance interface methods.
  - Abstract slots, instance or static, hold stubs that throw `AbstractMemberError`; an abstract
    class's constructor slots hold stubs that throw `InstantiationError` (SVD-05, SVD-06).
  - Call sites cache one target, then up to four (polymorphic), then fall back to the table
    lookup (Hölzle, Chambers, Ungar).
  - Generic virtual methods (GEN-07) dispatch through a per-type cache keyed by method and type
    arguments, filled by resolving the override and instantiating it.
- **VM-18 [Proposed]** Static dispatch tables are built like vtables: a subclass copies its base's
  table, then each `Override` row (BC-12) replaces a slot, and new `static virtual` members append
  slots. A virtual constructor's slot holds an entry that allocates an instance of the class whose
  table holds it and runs that class's constructor, so `new K(x)` and `k.create(x)` are one
  indirect call.

## 6. Interpreter

- **VM-05 [Decided, Q38]** On first execution, a method's stack bytecode is translated into a
  register form: every argument, local and stack slot becomes a typed virtual register (types come
  from the stack maps, Shi et al.), and instructions become three-address operations. The
  interpreter is a plain `switch` loop over that form, which the AOT backend compiles to a jump
  table. Since methods are JIT-compiled at their first call (JIT-03), the interpreter mostly runs
  cold code; between v0.6 and the v0.8 JIT it runs everything, `rflc` included.
- **VM-19 [Proposed]** Quickening (Brunthaler): an operation rewrites itself once its operand is
  resolved (`ldfld <token>` becomes a load at an offset, `call <token>` a call to a method
  descriptor) and call sites hold their caches inline (PEP 659's specialization). The quickened
  register form is also the baseline JIT's input.
- **VM-20 [Proposed]** Frames: interpreted frames live on a VM stack of typed slots, each holding
  the method, the current position, the receiver class `R`, the method-instantiation dictionary if
  any, and the registers. The collector scans them with the register types (memory.md §4).
  Adapters connect interpreted and compiled frames in both directions. A guard page on the machine
  stack and a limit on the VM stack raise `StackOverflowError`.

## 7. Class initialization

- **VM-07 [Proposed]** Lazy, at first active use (JVMS §5.5): access to a static field through any
  receiver, a static call, instance creation, or reflective use. Each instantiation initializes
  separately; superclasses first; an interface only when its own statics are touched. States:
  not started, running (with the running thread, CON-02), done, failed. A failure is recorded and
  rethrown as a new `ExceptionInInitializerError` with the same cause at every later use (MEM-06).
  A nested request from the running initializer proceeds and sees default values (Java).

## 8. Exceptions

- **VM-06 [Proposed]** Unwinding is table-driven: the interpreter searches each frame's exception
  regions innermost first, compiled code has unwind tables, and catch types relative to the type
  context (`VAR`, `MVAR`) are resolved through the frame's dictionary (BC-18). `finally` regions
  run on every exit.
- **VM-21 [Proposed]** A stack trace is recorded at construction as pairs of method and code
  offset (EXC-06) and decoded through the DEBUG line tables only when printed; without DEBUG, or
  with a stale one (BC-20), it shows methods and offsets only. The VM raises the exceptions of
  type-system.md §12 as ordinary Reflect objects allocated on the heap; `OutOfMemoryError` and
  `StackOverflowError` are preallocated.

## 9. Natives and system calls

- **VM-08 [Proposed]** Natives are VM functions registered by qualified signature when the VM is
  built (UNS-03): console and file IO, memory mapping, time, exit, arguments, environment. They are
  Reflect-S code over Linux system calls (UNS-04); a native declared by a module outside the
  trusted stdlib list is a `TypeLoadError`.
- **VM-22 [Proposed]** System calls go through one Reflect-S wrapper per call number, which
  converts `-errno` results into `IOException` subclasses at the library boundary.

## 10. Reflection support

- **VM-09 [Proposed]** Reflection reads the mapped metadata tables in place (VM-13); member
  mirrors are created on first request and cached per run-time type. Access checks use the caller
  frame's module (REFL-09). Reflective calls use per-signature stubs (REFL-08).

## 11. Dynamic code and hot reload

- **VM-10 [Proposed]** Emit and proxies load in-memory modules into new child layers (§2). Hot
  reload applies a delta (reflection.md §8): it swaps the code pointer in each replaced method's
  descriptor (every call goes through the descriptor or a patchable stub), invalidates call-site
  caches and dependent compiled code, appends new methods to their types, and keeps added fields
  of existing objects in a weak side table (memory.md §7), where they start at zero, so an added
  non-null reference field is `null` in those objects (REFL-07, Q64). Frames already running the
  old body finish it.

## 12. Locks

- **VM-23 [Decided, Q37]** Lock state lives only in objects whose class implements `Lockable`
  (CON-04): the first such class in a hierarchy gets a hidden lock word after its own fields, and
  the run-time type records its offset, so `synchronized` on a value typed as an interface finds
  the word through one load. Until threads exist (after v1.0) the word is reserved and
  `synchronized` is rejected by the compiler.

## 13. Start-up and diagnostics

- **VM-11 [Proposed]** Start-up maps the boot image instead of loading the VM's own classes
  (BOOT-13). Later additions: a pre-linked snapshot of the stdlib for faster start-up (as Java's
  class-data sharing), and a debugger speaking the Debug Adapter Protocol on top of interpreter
  single-stepping and the `breakpoint` instruction.
- **VM-24 [Proposed]** Diagnostic options: `--interpret` (no JIT), `--trace-load`, `--trace-gc`,
  `--verify-all` (also verify trusted modules), and the collector's stress modes (memory.md §10).
