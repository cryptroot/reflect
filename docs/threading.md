# Proposal: Threads, Atomics and Lock-free Semantics

A path from Reflect's single-threaded VM to shared-memory threads, with explicit synchronization
and room for lock-free algorithms. This feature is not implemented or assigned a release.
All rules here are **[Proposed]** unless marked **[Open]**; they do not amend the baseline
[language rules](type-system.md), [grammar](grammar.md) or [module format](bytecode.md).
Conventions: [architecture.md](architecture.md) section 0.

CON-01 and Q07 still govern: one thread until after v1.0. CON-02 proposes Java's memory model
as the baseline, and CON-04 has already decided opt-in `Lockable` objects. This proposal expands
that direction and [headroom](headroom.md), not the self-hosting critical path. Async keeps its
existing deferral under CON-03; a state-machine lowering is not a thread scheduler.

## 1. Goals and boundaries

- **THR-01 [Proposed]** Start with managed, shared-memory OS threads, monitors on `Lockable`
  objects, volatile fields and a small atomic API. Specify their memory model before enabling
  concurrent execution. Preserve sequential program behavior and the existing GC/ref-safety
  rules; do not require an ownership-system rewrite to obtain useful threads.

Keep these separate:

| Property | Meaning | Not implied |
|---|---|---|
| Atomicity | An operation takes effect indivisibly on its location | Publication of unrelated data |
| Ordering | Which accesses become visible through synchronization | Mutual exclusion or fairness |
| Thread safety | A type preserves its documented invariants under concurrent use | Lock freedom |
| Progress | What completes when threads contend or are delayed | Bounded scheduling or GC pauses |
| Static race safety | A checker excludes conflicting unsynchronized accesses | Provided by tracing GC or `final` alone |

The first usable subset need not include lightweight threads, a work-stealing pool, concurrent
collections, arbitrary atomic structs, transactional memory or compile-time rejection of every
data race. It must not silently treat those as solved.

## 2. Headroom to preserve before threads

The following are design/implementation constraints, not instructions to implement threads now.
Existing headroom judgements remain unchanged.

| Existing hook | Preserve now | Required before concurrent execution |
|---|---|---|
| CON-02, VM-07, HR-08 | Per-instantiation initialization state, owner and recorded failure; exact first-active-use semantics | Waiting by other threads and safe publication of successful initialization and failures |
| CON-04, VM-23 | Lock word only on opt-in `Lockable` classes; no universal object monitor | Ownership, recursion, contention and exceptional release |
| GC-02, GC-10, GC-13 | Allocation contexts, handles, precise roots, reference-store barriers and safepoint polls | Context per mutator and a coordinated all-thread safepoint protocol |
| HR-18, HR-21 | VM-owned layouts; centralized managed-reference accesses | Aligned atomic cells and collector-aware atomic loads and updates |
| HR-19 | Stack maps identify interior references; blocking IO behind wrappers | Movable/suspendable stacks and parking without assuming an OS thread per logical thread |
| HR-22 | Few mutable statics; distinguish deep immutability from a read-only view | A separately specified transfer/shareability discipline if adopted |
| HR-07, HR-23 | Architecture-specific intrinsics and one current-context accessor | Explicit compiler/CPU ordering, not reliance on x86-64's stronger ordering |
| BC-20, BC-23 | Reject unknown critical semantics; finals stay final | Versioned synchronization semantics, including reflection and emitted code |

Keep process-wide services behind `Runtime`, but distinguish them from execution-local state:
stack/frames, allocation context, pending exception, handles and any dynamic transaction stack.
One current-context accessor can return the sole context today and a thread/fiber context later.
Do not bake TLS offsets, stack addresses, lock-word encodings or OS thread IDs into user bytecode.

Do not promise that a mutable library singleton, cache, listener list or collection is safe to
share just because it currently has only one caller. Keep confinement and ownership visible at
API boundaries. No new public keyword, opcode number or metadata bit needs reserving here.

## 3. Threads and execution contexts

- **THR-02 [Proposed]** A thread has an independently rooted managed stack and an execution
  context. Creation publishes a fully registered context before user code runs; termination
  publishes a completion record after the target and its `finally` blocks finish. A join keeps
  that record alive, not the terminated stack forever.

An illustrative library facade is `reflect.concurrent.Thread`, accepting the existing
`reflect.functions.Runnable`. VM-known context and atomic primitives belong in `reflect.core`
under ARC-10; the facade may depend on `reflect.functions`, never the reverse dependency from
`reflect.core`. Names and exact signatures remain provisional:

| Operation | Proposed contract |
|---|---|
| `Thread.start(Runnable target)` | Start once and return a joinable handle; resource/start failures are reported to the caller, with partial registration cleaned up |
| `thread.join()` | Wait for completion; establish the visibility edge in section 4; rethrow a retained target failure through a thread-execution wrapper |
| `thread.isAlive` | Status observation only, not a substitute for joining or publishing application data |

Self-join throws `IllegalStateException`; multiple joiners are allowed. An uncaught target
exception must not disappear: retain its original cause/stack for joiners, and report an
unobserved failure at shutdown with a failing process status. The wrapper's exact exception
type and observation bookkeeping need specifying before this API ships.

- **THR-03 [Proposed]** The first subset has no detached/daemon threads or forced thread
  termination. Normal return from `main` waits for started threads before the final collection,
  finalizer drain and process exit; this extends VM-12/GC-04 only when threads are enabled.
  Stop requests are cooperative, initially expressible with an atomic flag. No operation injects
  an asynchronous exception that can bypass `finally` or abandon a monitor.

Captures retain FUN-03's variable semantics: lambdas that capture an assigned local share a heap
display; that display is shared mutable state, not an automatic atomic cell. Existing prohibitions
on capturing `ref` locals/parameters and ref structs remain. The initial unscoped thread API does
not accept borrowed stack storage. A future scoped-thread API needs a lifetime proof of its own.

```csharp
// future: illustrative API, after v1.0
int[] result = new int[] { 0 };
var worker = Thread.start(() -> { result[0] = 42; });
worker.join();
println(result[0]); // 42: completion/join publishes the ordinary array write
```

## 4. Memory model and publication

### 4.1 Baseline contract

- **THR-04 [Proposed]** Define program order, synchronization order, synchronizes-with,
  happens-before and allowed read observations, using the Java memory model as CON-02's
  baseline. A data race is a pair of conflicting ordinary accesses by different threads, at
  least one a write, not ordered by happens-before. Correctly synchronized programs have a
  sequentially consistent interpretation. This is not a promise that racy ordinary accesses
  observe the latest wall-clock write.

Happens-before includes program order, the following edges, and their transitive closure:

| Publisher | Observer | Edge |
|---|---|---|
| Successful thread start | First action of the started thread | Actions before start become visible to the target |
| Thread completion | Successful join of that thread | Target actions become visible after join |
| Final unlock of a monitor | Subsequent acquisition of the same monitor | Protected writes become visible to the next owner |
| Volatile write | Subsequent volatile read of that location in synchronization order | Prior writes become visible after the read |
| Atomic release update | Acquire operation observing that update | Prior writes become visible after the acquire |
| Successful class initialization | Active use observing initialized state | Initialized statics become visible before use |

Sequentially consistent atomics participate with volatile accesses in a common synchronization
order consistent with happens-before. For the initial subset, all atomic operations use that
ordering. Locks and thread lifecycle actions must fit the same model, not an unrelated
hardware-only ordering scheme.

- **THR-05 [Proposed]** Safe managed races are not C/C++-style undefined behavior. References
  must not tear, refer to reclaimed storage or fabricate a type; scalar accesses to supported
  primitive fields are non-tearing, including aligned 64-bit integers on the initial target.
  A non-atomic struct copy is not an atomic snapshot: different fields may come from different
  writes, while each contained reference remains GC-safe. Races still permit stale values,
  lost updates and broken application invariants. Unsafe raw-memory races require a separately
  stated contract; they do not weaken the safe-code floor.

The exact causality/read-validity rules are a delivery gate, not supplied by the phrase
"Java-like". They must exclude out-of-thin-air values without accidentally promising sequential
consistency for racy code. Extending the model with weaker atomics requires another proof.

### 4.2 Construction, finals and flow analysis

- **THR-06 [Proposed]** Publish mutable objects through start/join, a monitor, a volatile field
  or an atomic release/acquire pair. Publishing a reference does not make subsequent mutations
  safe. `final` prevents reassignment, not mutation of the referenced graph; a read-only
  collection view is not deep immutability.

BC-23's final-field immutability remains essential. Specify constructor-completion/final-field
freeze semantics, including state reachable through finals at the freeze, before relying on
Java-style initialization safety. Constructor escape does not gain that guarantee. AOT boot-image
objects and pre-run initializers must obey the same publication contract (HR-08), without
restoring reflective writes to finals.

**[Open]** Racy construction and reified non-null fields need explicit treatment. NUL-04 already
warns about constructor escape, and REFL-07 permits default-null fields added by hot reload.
Threads must not introduce an unacknowledged third nullability hole through unsafe publication
of a completed object. Choose initialization visibility guarantees or explicit checks and
diagnostics, state the allowed exceptions to FR-null, and test them before enabling threads.
At minimum, every dereference must remain memory-safe even in those exceptional cases.

NUL-03's current narrowing of a mutable `this.f` until the next call is insufficient with
threads: another thread can write between the test and use, without a call by the reader.
Before threads ship, restrict such narrowing to proven confinement/protection or require a
local snapshot. Repeated volatile/atomic reads are separate observations; narrowing the first
does not narrow the next. Old source may need a diagnostic and a local-copy fix, not silent
retention of a single-threaded assumption.

## 5. Monitors and volatile fields

- **THR-07 [Proposed]** Preserve CON-04 exactly: `synchronized (x)` requires a static subtype
  of `Lockable`; interfaces may extend it, subclasses inherit it, structs cannot implement it,
  and `Lock` provides a private lock object. Do not add a monitor to `Object` or use the GC
  header as an implicit universal lock.

Evaluate the receiver once; a nullable receiver must first be made non-null. Monitor ownership
is reentrant for the same logical execution context. Every exit releases the acquired level,
including exceptions, `return`, `break` and `continue`; only the outermost release makes the
monitor available to another owner. Contention may park; neither fairness nor deadlock freedom
is promised. Prefer private `Lock` objects, preserving the existing analyzer warning for
`synchronized (this)` in public lockable classes.

- **THR-08 [Proposed]** `volatile` applies initially to scalar primitive and managed-reference
  fields, instance or static, not arbitrary structs, properties or an array's elements.
  A volatile array reference orders publication of the array, not subsequent element accesses.
  Reads/writes have Java-style volatile ordering and are non-tearing; `volatile final` is
  rejected. `volatileCount++` remains a read followed by a write, not an atomic increment.

Reflection, accessors and emitted code must preserve a volatile field's declared semantics.
Taking a writable ordinary `ref` to a volatile field cannot bypass them; reject that escape
unless a separately typed ordered-access mechanism is specified. Condition variables,
wait/notify, interruptible waits and timed locks are separate library designs; do not inherit
Java's entire `Object` monitor API accidentally.

## 6. Atomic cells

### 6.1 Representation and operations

- **THR-09 [Proposed]** Prefer a VM-known, invariant `Atomic<T>` reference type with private,
  naturally aligned storage and an explicit initial value. Reified instantiation checks admit
  integral primitives, `boolean` and managed-reference types, preserving exact nullability.
  Nullable value wrappers, ref structs, pointers, floating point and arbitrary user structs
  are outside the first subset. Invalid instantiations are compile errors when known and
  `TypeLoadError` at linking otherwise, including reflection and emit.

This is a checked intrinsic type restriction, not a new general-purpose generic constraint
syntax. Generic forwarding must preserve/check admissibility too. Reified generics avoid a
parallel public family of primitive boxes; they do not make every `T` atomically representable.
No ordinary load, store, field mirror or escaping `ref` may access the cell's backing slot.
Copying the cell reference shares the cell; it does not copy the value into a new atomic.

Minimal API sketch, with all operations sequentially consistent:

| Operation | Result |
|---|---|
| `new Atomic<T>(initial)` | A cell initialized before publication |
| `cell.load()` | One observation of `T` |
| `cell.store(value)` | One indivisible replacement |
| `cell.exchange(value)` | Replace and return the previous `T` |
| `cell.compareAndSet(expected, desired)` | Strong CAS: replace and return `true` exactly when the comparison matches at its linearization point |

Each completed operation is linearizable on its cell, including a failed comparison as a read.
A strong CAS does not fail spuriously; failure has the ordering of a sequentially consistent
load, not a release write. It may fail if another thread changed the value after an earlier
load. Atomicity of the cell does not make the referenced object or multiple cells atomic together.

- **THR-10 [Proposed]** Primitive CAS compares stored representations without user callbacks.
  Managed-reference CAS compares object identity, never an overloaded `==` or `equals`.
  DAT-09 matters: `Object.referenceEquals`/`ceq.ref` can compare identityless boxes by contents.
  They therefore cannot be used blindly as the reference-CAS comparison.

For the initial reference-cell subset, reject identityless boxes with `IllegalArgumentException`
on construction and on store/exchange/CAS operands, as weak references already do (GC-05).
Identity-bearing box-mutable objects are ordinary references. A cell of `Object` or an interface
needs this dynamic check too; nullable reference cells admit `null`. This is an intentional
restriction, avoiding an accidental identity promise for value boxes. Float CAS, if added later,
must explicitly settle NaN payloads and signed zero rather than borrow numeric equality.

Integer `fetchAdd`, bitwise RMWs and update helpers can follow without changing CAS. Their API
must constrain the supported primitive types and specify fixed-width wrapping independently
of the caller's `checked` context. A callback-based update may invoke its callback repeatedly
under contention and is not implicitly side-effect-free or lock-free.

### 6.2 Optional weaker memory orders

- **THR-11 [Open]** Keep explicit ordering possible, but ship the sequentially consistent subset
  first. A later `MemoryOrder` parameter or named access modes would be a deliberate extension
  of CON-02's Java baseline, not a claim that Java volatile already means C++/Rust relaxed
  atomics. No `consume`, standalone fences or mixed plain/atomic access in the first subset.

If weaker modes are adopted, the intended operation matrix is:

| Order | Load | Store | Successful RMW | Failed CAS read |
|---|---|---|---|---|
| `relaxed` | Yes | Yes | Yes | Yes |
| `acquire` | Yes | No | Yes | Yes |
| `release` | No | Yes | Yes | No |
| `acqRel` | No | No | Yes | No |
| `seqCst` | Yes | Yes | Yes | Yes |

Relaxed operations remain atomic and coherent per location but do not publish unrelated data.
Acquire/release establishes an edge only through the specified reads-from relationship, not
because two operations happened near each other. Define RMW chains/release sequences explicitly;
do not assume another language's rules without adopting them.

CAS needs separate success/failure orders, with valid pairs enumerated:
`relaxed/relaxed`; `acquire/{relaxed, acquire}`; `release/relaxed`;
`acqRel/{relaxed, acquire}`; `seqCst/{relaxed, acquire, seqCst}`.
Failure never has release semantics. Statically known invalid combinations are diagnostics;
dynamic invalid combinations throw `IllegalArgumentException`, never silently strengthen,
weaken or reinterpret the request. Weak CAS, if exposed, needs a distinct name and documented
spurious-failure behavior.

## 7. What lock-free would mean

- **THR-12 [Proposed]** Do not equate an atomic API, a CAS loop or absence of a source-level
  `synchronized` block with a lock-free implementation.

| Progress class | Required guarantee for the documented operation |
|---|---|
| Blocking | Completion may depend on another thread releasing a resource |
| Obstruction-free | A thread running alone for long enough completes |
| Lock-free | System-wide progress despite contention or a delayed participating thread; individual starvation is possible |
| Wait-free | Each caller completes within a bounded number of its own steps |

These algorithmic guarantees assume execution steps are scheduled; they are not real-time
deadlines or fairness guarantees. An OS deschedule, stop-the-world collection or safepoint pause
can delay every implementation. Document the boundary between the atomic slot update and the
entire library operation, including barriers, allocation, lazy linking and reclamation.

Recommend lock-free scalar integer/boolean atomics on Linux x86-64 as the first implementation
target. Reference atomics need an additional GC/barrier proof; wider values may never qualify.
Expose a stable capability such as `cell.isLockFree` for the actual storage/type/target/collector
combination. Its documented scope must include the mandatory barrier path, not just the CPU
instruction. A blocking implementation may preserve atomic semantics but must report that fact;
code requiring lock freedom needs an explicit capability check or a rejecting factory.

```csharp
// future: illustrative Atomic<int> API
public final class Counter {
    private final Atomic<int> count = new Atomic<int>(0);

    public int increment() {
        int observed = count.load();
        while (true) {
            int updated = observed + 1;
            if (count.compareAndSet(observed, updated)) { return updated; }
            observed = count.load();
        }
    }
}
```

This is a wrapping counter, not a multi-field transaction. Under contention a caller may retry
forever while others succeed; it is not wait-free. Its lock-free claim is conditional on the
underlying operations and run-time paths above. Retry loops keep GC safepoint polls; do not
hide an unbounded CAS loop inside `@Uninterruptible`.

- **THR-13 [Proposed]** Managed GC makes a removed node safe to read while a strong local/root
  still reaches it; it does not solve every ABA problem. Reinserting the same node can change
  a location from A to B to A and defeat an identity-only CAS. Version-tagged state, immutable
  replacement nodes or a proven no-reinsertion discipline are algorithm decisions, not
  properties of CAS. Do not assume an atomic reference-plus-version pair or spare pointer bits.

Unmanaged lock-free structures additionally need reclamation such as hazard pointers or epochs;
unsafe `free` after unlinking is not enough. Moving GC must compare logical object identity,
not an address cached across safepoints; a reference and version do not become atomically
comparable merely because they fit in a struct.

## 8. Runtime, collector and compiler integration

### 8.1 Roots, barriers and stopping threads

- **THR-14 [Proposed]** Every live context is registered for GC: interpreted and compiled
  frames, interior references, handles, allocation state and pending failures. Thread creation,
  termination and blocking transitions participate in the same handshake. A parked or
  syscall-blocked thread publishes stable roots; the collector cannot simply wait for an
  arbitrary blocking syscall to return before declaring the world stopped.

Stop-the-world collection remains a valid first threaded collector. Coordinate all mutators,
then use the existing precise maps; do not infer quiescence from lack of allocation. A thread
spinning on an atomic flag still polls. VM operations marked `@Uninterruptible` must be bounded
and cannot park, allocate or wait for a thread which needs a safepoint.

Atomic reference loads, stores, exchange and successful CAS go through collector-aware access
primitives (GC-02, HR-21). Preserve all required old-value and new-value barrier effects,
including SATB/card marking where applicable; a CPU CAS alone is not a managed-reference store.
Failed CAS is not an application write, though a collector may require conservative barrier
bookkeeping. Specify safe barrier placement around CAS retries, rooting of returned/expected
values, relocation and memory-order preservation for each supported collector.

### 8.2 Shared runtime state and execution tiers

- **THR-15 [Proposed]** Audit all lazily published runtime state, not only user fields:
  type interning and generic dictionaries, initialization records, token resolution, reflection
  caches, quickened interpreter operations, dispatch caches, compiled entry points, weak tables,
  identity hashes and GC/destruction header updates. Use internal synchronization with a stated
  lock order and safe publication; never expose partially constructed descriptors.

VM-07 recursion by the initializing context still sees defaults. Another context waits for
done/failed and observes the recorded result; do not hold a global loader lock while running
an initializer. Initialization cycles can deadlock across threads and need diagnostics, not
a silent switch to early initialization.

Interpreter quickening and JIT publication need indivisible state transitions. Code invalidation,
hot reload and layer unloading must retain old bodies/metadata until all executing frames and
readers release them. Reuse safepoint/epoch machinery where appropriate. Do not free code as
soon as a descriptor pointer changes.

Model ordered operations explicitly in register form/SSA and stencils. The interpreter,
baseline JIT, optimizing compiler and AOT must obey the same model; `const` evaluation and
generator sandboxes remain deterministic and cannot start threads (META-09). An optimizer
must not hoist volatile/atomic observations out of a polling loop, eliminate a required fence,
or fold mutable shared fields because no write appears in the current method.

Implement Linux thread/parking services through Reflect-S and existing syscall wrappers,
without introducing libc, C/C++ or a host runtime. Keep architecture-specific atomic lowering
behind intrinsics; validate compiler ordering as well as instructions. x86-64 success is not
evidence of AArch64 correctness (HR-23).

### 8.3 Verifier, compatibility and other proposals

- **THR-16 [Proposed]** Source, assembler, verifier, reflection and emit need one synchronization
  contract. Validate admissible atomic instantiations, exact value types/nullability, monitor
  receiver types, ordered field accesses, non-escaping atomic/volatile storage and exceptional
  monitor cleanup. Intrinsics or future opcodes need verifier semantics; a trusted-looking
  annotation is not authorization to bypass them.

Thread/ordering requirements must be critical, versioned semantics under BC-20, not skippable
debug metadata or ignored field flags. An older VM must reject unsupported requirements.
Define how legacy bytecode is reverified/lowered by a threaded runtime; lack of new metadata
does not certify shareability. Recompile or reject old AOT code whose single-thread assumptions
are invalidated. This proposal assigns no encodings and does not change today's format.

Cross-feature constraints:

- **Collections/events:** remain non-thread-safe by default (CON-02). Listener snapshots,
  immutable values and purpose-built concurrent collections need explicit contracts. Making
  an event's listener pointer atomic does not serialize callbacks or their effects.
- **Destruction:** DAT-08's check at method entry is not a lifetime lease. Racing destruction
  can invalidate native resources after the check. Specify at-most-once header transitions,
  same-context recursive destruction and coordination with in-flight resource users;
  concurrency behavior for another `destroy()` caller is a delivery gate.
- **Finalization/weak references:** retain explicit GC-04 drain points, not an implicit finalizer
  thread. Serialize concurrent drains and define allocation-order registration of finalizable
  objects across mutators. Weak reads must acquire a strong root before reclamation can win.
- **Transactions:** [TXN-15](intent-contracts-and-transactions.md) initially excludes multiple
  mutators and suspension. Keep that restriction enforced; undoing an atomic write cannot undo
  another thread's observation. Neither monitors nor atomics make undo logs isolated.
- **ECS:** [parallel system scheduling](entity-component-systems.md) needs verified effects
  beyond component parameters and join/publication boundaries. Component disjointness alone
  does not make callbacks, captured objects or structural changes safe.
- **Async/lightweight threads:** future suspension must preserve logical monitor ownership,
  roots and dynamic context without binding them permanently to a carrier OS thread (HR-19).
  Prefer scoped context over making mutable thread-local state the only extensibility point.

## 9. Validation and proof obligations

- **THR-17 [Proposed]** Extend [testing](testing.md) and the [formal model](formal-model.md)
  before marking threads stable. A concurrent layer must cover thread states, publication,
  monitor ownership/exceptional release and atomic read/RMW observations, with preservation
  of managed-reference safety and a stated race-free sequential-consistency theorem. Static
  transfer checking and relaxed orders, if adopted, need their own obligations.

Required evidence:

| Area | Required cases |
|---|---|
| Publication | Start/join, monitor handoff, volatile message passing, class initialization success/failure, constructor escape and final fields |
| Memory-order litmus tests | Sequentially consistent store buffering forbids both reads seeing zero; volatile/SC publication of a ready flag forbids observing ready with stale payload; relaxed variants may allow outcomes forbidden by SC |
| CAS | Strong success/failure, contention, ABA reinsertion, nullability, identityless-box rejection, comparison with equal-but-distinct objects, invalid order pairs |
| Monitors/lifecycle | Recursion, all exceptional exits, non-`Lockable` rejection, start failure cleanup, self/multiple join, retained worker exceptions and shutdown |
| Type/flow safety | Mutable-field narrowing diagnostics/local snapshots, concurrent captured displays, borrowed-stack escape rejection, torn aggregate versus intact reference fields |
| Collector stress | Creation/termination/parking during collection, tight retry loops, interior roots, relocation around reference CAS, weak promotion, concurrent explicit finalizer drains |
| Runtime publication | Competing initialization, dictionary/cache fill, quickening, code compilation/replacement and delayed layer reclamation |
| Bytecode/reflection | Forged types/flags, backing-slot escape, volatile bypass, missing monitor cleanup, unsupported requirements and legacy AOT rejection |

For `N` workers performing `M` increments without overflow, joining all workers must yield
exactly `N * M`; check return-value uniqueness too. Force interleavings around CAS, barrier and
publication boundaries. Run in interpreter, JIT and AOT, with GC stress and adversarial scheduling.
Concurrency differential tests compare allowed outcomes/invariants, not identical scheduling,
print order or one observed trace (an extension of TST-08).

Stress runs cannot prove a forbidden outcome impossible or establish lock freedom. Use a
bounded interleaving/weak-memory model for small litmus programs, reviewed lowering mappings,
and algorithmic progress arguments with explicit assumptions. Keep weak-order tests even while
only x86-64 is supported.

Measure uncontended/contended latency distributions, throughput, CAS retries, allocations and
GC pauses with thread counts, hardware and build flags stated. Warmed-up scalar atomic operations
and counter retries should allocate **zero managed bytes**; verify that directly. Report barrier,
parking and reclamation costs rather than advertising the CPU instruction as the entire cost.
Wait-free claims require a step bound, not a benchmark.

## 10. Delivery and open decisions

Suggested stages, unscheduled and not additions to R0 or the v1.0 proof track:

1. **Now:** retain section 2's hooks and record thread-confinement assumptions. Do not enable
   reserved syntax or weaken the single-threaded milestone.
2. **Before a threading preview:** settle the baseline memory model, construction/nullability,
   flow analysis, lifetime/shutdown and destruction/finalizer rules; audit runtime publication
   and specify verifier/format requirements. Update the owning rules only when decisions land.
3. **First post-v1.0 experiment:** OS threads, join, opt-in reentrant monitors, volatile fields
   and sequentially consistent atomic cells; coordinated stop-the-world GC; feature-gated under
   TST-02/TST-06, with parity across execution tiers.
4. **After correctness evidence:** certify lock-free target/type/collector combinations and
   add purpose-built concurrent libraries. Enable weaker orders only after their formal and
   lowering contracts are independently validated.
5. **Optional later work:** static race checking, lightweight threads/scoped values, structured
   task APIs, scoped borrowing, richer RMWs and atomically representable aggregates.

**[Open]** Static data-race prevention (HR-22) remains a separate design choice. Preserve a path
to `Send`/`Sync`-like transfer/shareability or isolated domains, but do not add unchecked marker
interfaces that claim a proof. Deep immutability must be transitive and account for mutable
statics, captured displays, native resources and reflection; `final`, records and read-only
interfaces are not sufficient. Decide how legacy modules and explicit unchecked/unsafe
boundaries participate before making checked sharing a stable default.

Other open decisions: exact API/module names and intrinsic admissibility checking; complete
causality/final-field rules; racy initialization and flow-analysis compatibility; worker-failure
observation and process-exit policy; concurrent destruction/finalizer ordering; collector-specific
lock-free guarantees; optional memory-order syntax and proof layers. These are explicit gates,
not grounds to ship unspecified behavior.

The commitment now is narrower: preserve the seams, avoid false thread-safety promises, and make
the later move from one mutator to many a specified extension rather than a runtime rewrite.

## 11. Prior art

- [Java Language Specification, chapter 17](https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html):
  monitors, volatile, happens-before, final-field semantics and the baseline memory model.
  Reflect retains its decided opt-in lockability instead of Java's universal object monitors.
- [Java `VarHandle`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/invoke/VarHandle.html):
  differentiated plain, opaque, acquire/release and volatile accesses. Useful precedent for
  future ordered access, not a proposal to copy its signature-polymorphic API or mixed access.
- [References: memory management and safety](references.md#memory-management-and-safety):
  Manson, Pugh and Adve's Java memory model, collector foundations and related safety work.
- [Headroom HR-19, HR-21, HR-22 and HR-23](headroom.md): lightweight threads, load barriers,
  static race safety and weakly ordered targets. Their opportunities remain open.
