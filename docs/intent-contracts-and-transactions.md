# Proposal: Intent Contracts and Transactions

Two complementary language features: Intent Contracts describe what a method requires and
guarantees; Transactions restore managed state when a block exits by throwing. Neither feature
is implemented or assigned a release. All rules here are **[Proposed]** unless marked **[Open]**;
they do not yet amend the baseline [grammar](grammar.md) or [language rules](type-system.md).
Conventions: [architecture.md](architecture.md) §0.

## 1. Goals and limits

- State method intent in executable, type-checked expressions, useful to callers, diagnostics
  and compiler analyses.
- Prove contracts at compile time where feasible, with mandatory run-time checks wherever the
  proof is unavailable. An incomplete prover must not make contracts advisory.
- Provide compiler-generated transaction boundaries without requiring setters, wrapper objects
  or source instrumentation in every method that mutates state.
- Intercept managed field and array writes in the VM, including writes made by dynamically
  called code, with a cheap inactive path and an optional repeated-write optimization.

These are not a general theorem prover, a database, durable storage or a concurrency/isolation
mechanism. Reflect is initially single-threaded (CON-01); multi-threaded transactions require a
separate design before they can be enabled.

## 2. Intent Contracts (IC)

### 2.1 Surface syntax

```reflect
int divide(int a, int b)
    requires b != 0
{
    return a / b;
}

int increment(int value)
    requires value < int.MAX_VALUE
    ensures result == old(value) + 1
{
    return value + 1;
}

void deposit(Account account, int amount)
    requires amount >= 0
    requires account.balance <= int.MAX_VALUE - amount
    ensures account.balance == old(account.balance) + amount
{
    account.balance += amount;
}
```

- **IC-01 [Proposed]** A method may declare zero or more `requires` clauses followed by zero or
  more `ensures` clauses, after `throws` and `where`, before its body or terminating semicolon.
  Each clause is a `boolean` expression. Repeated clauses are conjunctive, evaluated in source
  order with short-circuit failure. An interface or abstract method may declare contracts too.
  The initial proposal covers methods, not constructors, accessors, operators or lambdas.
- **IC-02 [Proposed]** `requires` is a precondition: it must hold before the method body starts.
  `ensures` is a postcondition: it must hold when the method returns normally, including a
  fall-through return from a `void` method. Exceptional exits do not evaluate postconditions.
  A contract does not promise termination or that a method cannot throw.

Grammar sketch, not an opcode or grammar-version commitment:

```text
Method       = Type Ident TypeParams? "(" Params? ")" Throws? Where? Contracts?
               ( Block | ";" ) ;
Contracts    = Requires+ Ensures* | Ensures+ ;
Requires     = "requires" Expression ;
Ensures      = "ensures" Expression ;
EntryState   = "old" "(" Expression ")" ;
Transaction  = "transaction" Block ;
```

`requires`, `ensures`, `old`, `result` and `transaction` are proposed contextual keywords only
in these new positions. `requires` keeps its existing meaning in module descriptors; existing
identifiers and ordinary calls such as `old(x)` must keep parsing normally outside contracts.
The parser must distinguish a contract-only `old(...)` from a call when binding contract syntax.

### 2.2 Entry state and the return value

- **IC-03 [Proposed]** `old(e)` in a postcondition evaluates `e` once at method entry, after all
  preconditions pass and before any body statement executes. The compiler stores each required
  capture in a hidden typed local. Captures run in first-occurrence order across the clauses.
  `old` is illegal in preconditions, nested inside `old`, or in an ordinary method body.
  Captures are per invocation, including recursive calls.
- **IC-04 [Proposed]** A capture is a value copy, not a deep snapshot: primitives and structs
  copy by value; object and array references preserve identity. `old(account.balance)` saves the
  balance; `old(account).balance` reads the saved object's current balance at exit. An array
  snapshot needs explicit copied data in a future extension, not an implicit clone.
- **IC-05 [Proposed]** `result` denotes the final return value and is legal only in a
  non-`void` postcondition, never inside `old`. All clauses bind parameter names to their
  incoming values, even if the body reassigns a parameter; `this` is the incoming receiver.
  Fields and array elements outside `old` are read at the time the clause runs.
  Clauses cannot refer to body locals.

Return expressions retain the existing evaluation order. The compiler saves the selected return
value, completes the method body's `finally` blocks and resource cleanup, and only then checks
postconditions, immediately before returning to the caller. If cleanup replaces a return,
checks use the replacement value; if cleanup throws, no postcondition runs.

### 2.3 Purity, proof and run-time fallback

- **IC-06 [Proposed]** Contract expressions and entry captures must be effect-free. The initial
  subset permits literals, parameter/receiver references, field and array reads, built-in
  arithmetic, comparisons and boolean operations. Assignment, increment, allocation, arbitrary
  method calls, getters and overloaded operators are rejected. A property's getter is a call,
  not a field read. Any later expansion to calls needs compiler-verified purity, including
  termination and transitive effects; a user assertion of purity alone is insufficient.
  Nullability, bounds, numeric overflow and evaluation order follow the ordinary language rules.
- **IC-07 [Proposed]** The compiler first tries bounded analyses such as constant folding and
  flow/range reasoning. At a call, it may prove a precondition; in a body, it may use established
  preconditions to prove postconditions. A provably invalid call receives a compile-time
  diagnostic; an unknown result produces a run-time check, not a compilation failure. Proof is
  optional; enforcement is not. This proposal does not require an external solver.
- **IC-08 [Proposed]** Generated method entry checks protect every invocation path: direct,
  virtual, interface, reflection, proxies and emitted callers. A check may be removed only when
  its truth on every path reaching that entry is established. Proof at one caller is not enough
  to delete a shared callee guard. Inlining may specialize a proven call while retaining the
  original entry guard. Separate compilation, hot reload and changing dispatch targets must
  preserve or invalidate dependent proofs, never trust stale assumptions.
- **IC-09 [Proposed]** An unproven postcondition is checked on every normal exit. Only after
  proof or a successful check may a caller analysis rely on it. Release and debug builds have
  the same enforcement; there is no assertion-style switch to disable unresolved checks.
  A capture may be removed only when it is no longer needed and its evaluation is proven not
  to fail, so optimization preserves observable failure behavior.
- **IC-10 [Proposed]** A false clause throws an unchecked `ContractViolationError`, identifying
  the method, clause kind, source location and expression. A failing precondition prevents body
  execution; a failing postcondition prevents normal return. An exception while evaluating a
  clause or entry capture is reported as `ContractEvaluationError` with the original cause,
  not treated as `true` or silently swallowed. Fatal VM failures retain their ordinary behavior.
  Diagnostics must not automatically print captured object contents.

### 2.4 Overrides and metadata

- **IC-11 [Proposed]** Initially, overrides inherit the slot's contracts unchanged and cannot
  declare additional clauses. Every implementation checks inherited preconditions and captures
  at its own entry, and inherited postconditions at its normal exit. This applies to static
  virtual methods as well as instance dispatch. Incompatible contracts inherited from multiple
  declarations are a compile-time error in this subset. Independently weakening preconditions
  or strengthening postconditions is deferred until implication and inheritance rules exist.
- **IC-12 [Proposed]** Module metadata preserves contract identity, bound expressions, capture
  types and source locations, so separate compilation, reflection and diagnostics agree. Source
  locations go to the DEBUG section, since critical content holds none (BC-20).
  Access checks apply where the contract is declared; inherited checks do not require exposing
  private fields to callers. Proof consumers must understand the contract representation or
  retain checks, never interpret unknown metadata as a successful proof.

## 3. Transactions (TXN)

### 3.1 Surface semantics

```reflect
void transfer(Account from, Account to, int amount) {
    transaction {
        from.balance -= amount;
        to.balance += amount;
    }
}
```

- **TXN-01 [Proposed]** `transaction { ... }` establishes a managed-memory rollback scope.
  On normal completion it commits. If an exception escapes the block, the VM restores logged
  locations in reverse order before propagation continues. An exception caught inside the block
  does not itself trigger rollback. The example illustrates boundaries, not validation: amount
  checks, sufficient funds and checked arithmetic remain application responsibilities.
- **TXN-02 [Proposed]** `return`, `break` and `continue` that leave the block commit, after
  evaluating their expressions and running cleanup lexically inside the block. If that cleanup
  throws, the block rolls back instead. Cleanup outside the block runs after its boundary.
  There is initially no explicit `abort`, automatic retry or transaction expression.
- **TXN-03 [Proposed]** A transaction is dynamically scoped, not limited to its source block.
  Writes by callees, callbacks, property setters and event handlers on the same execution
  context join the active scope even if those methods contain no transaction syntax. Local
  variables and parameters are not restored; heap cells containing captured locals are fields
  and are covered. Rollback does not rewind control flow or undo values already observed by code.

The initial supported state is instance fields, static fields of already initialized types, and
array elements in existing managed storage, for primitive, reference and struct values. Object
headers, class-initialization state, VM metadata and external resources are not ordinary fields.
Allocation, destruction and initialization need the restrictions or decisions in §5 before a
general-purpose implementation can ship.

### 3.2 Compiler-generated boundaries

- **TXN-04 [Proposed]** The compiler lowers a block to an enter operation, a protected body and
  distinct commit and rollback exits. The enter operation creates a frame/checkpoint on the
  current execution context's transaction stack. Every escaping branch passes through the
  appropriate exit; return values are saved before cleanup. Unwinding runs the block's inner
  cleanup while its transaction is active, then rolls back and rethrows the resulting exception.
  A failure to enter leaves the previous context unchanged.
- **TXN-05 [Proposed]** Boundary operations are VM-recognized and verifiable, not user-callable
  methods that can leave a log active accidentally. The bytecode verifier must reject branches
  into a transaction, unmatched exits and exception paths that bypass cleanup. Concrete
  instructions, metadata and format versions remain to be designed in
  [bytecode.md](bytecode.md); this proposal assigns no opcode numbers.

### 3.3 VM write interception and the inactive fast path

- **TXN-06 [Proposed]** All managed mutation paths consult the current transaction context:
  `stfld`, `stsfld`, array stores, stores through a managed `ref`, struct copy/initialization and
  bulk operations. Reflection and native helpers that mutate managed memory must use the same
  store path. A `ref` must retain enough location provenance to distinguish heap storage from
  an unlogged stack local. Interpreter, quickened code, JIT and AOT share these semantics.
- **TXN-07 [Proposed]** A store first performs its ordinary validity checks, then, if a
  transaction is active, reserves and records an undo entry containing the old typed value
  before publishing the write. If logging fails, that write must not occur. The log uses a
  stable owner handle plus a field/subfield path or array index, not an untracked raw address.
  Aliases to the same managed location must resolve consistently.
- **TXN-08 [Proposed]** With no active transaction, a store takes a fast path: a context test
  followed by the ordinary store and any required GC barrier. It allocates no log entry,
  reads no old value for transaction purposes and performs no deduplication lookup. This path
  applies to primitive writes too; the existing reference-only GC barrier (GC-02) is not enough.
  An optimizer may remove the context test only if inactivity is proven for that store.

The active-store sequence is conceptually:

```text
check target, bounds, type and ordinary store restrictions
if current transaction exists:
    reserve undo space
    save location and old typed value
perform store with normal GC barrier
```

Bulk writes must either record the entire affected range before mutation or preserve a correctly
logged prefix on failure. Optimizers cannot move a store across a boundary, merge it with an
unlogged store, or eliminate the only entry needed for rollback.

### 3.4 Undo log, nesting and optional deduplication

- **TXN-09 [Proposed]** The simplest implementation appends one entry per write to a shared,
  dynamically scoped log. Rollback replays a frame's suffix in reverse and truncates it.
  Nested transactions are savepoints: inner rollback restores inner-entry state; inner commit
  removes only its checkpoint, retaining the entries for a possible outer rollback. Only the
  outermost commit discards all remaining entries. An outer handler may catch an inner failure
  and continue with the restored inner state.
- **TXN-10 [Proposed]** Optional deduplication saves only the first old value per exact location
  per savepoint, never merely once per outer transaction. Inner commit merges bookkeeping
  without losing the parent's earliest value; inner rollback discards its bookkeeping.
  Keys use identity and location, never user `equals` or `hashCode`. Overlapping struct/subfield
  or bulk writes must retain ordered entries unless normalized to identical storage cells;
  deduplicating partially overlapping ranges is not required.
- **TXN-11 [Proposed]** Undo entries keep owners, old reference values and referenced metadata
  alive as GC roots. A moving collector updates their handles; replay performs the normal GC
  barriers but suppresses transaction logging. Replay must allocate nothing and invoke no
  setters, callbacks or user code. Thus it can complete after a log-growth failure and preserve
  the original escaping exception. Commit releases roots only when no enclosing rollback
  needs them. VM corruption or process termination cannot promise rollback.

### 3.5 External effects and unsupported operations

- **TXN-12 [Proposed]** Files, sockets, console output, system calls, unmanaged pointer writes
  and other irreversible effects are not made transactional. For the initial safe subset,
  reject statically known unsupported operations and guard dynamic/native boundaries, throwing
  an unchecked `TransactionEffectError` before an unsupported effect occurs. Trusted VM
  operations need an audited classification: read-only, managed-store-aware, or forbidden.
  `unsafe` must not silently bypass the guard. No claim of rollback is valid for an untracked
  write. Running finalizers or destructors with external effects inside a transaction is
  unsupported; GC still needs to remain usable without draining finalizers there.

## 4. How the features compose

Contracts do not implicitly open a transaction. A contract failure rolls back only if it escapes
an active transaction. A caller can keep a callee's postcondition check inside its boundary:

```reflect
void transfer(Account from, Account to, int amount) {
    transaction {
        applyTransfer(from, to, amount);
    }
}
```

If `applyTransfer` has a failing postcondition, its normal return becomes an exception and the
transaction rolls back, unless caught inside the block. In contrast, a postcondition on
`transfer` itself runs after its body, so its transaction has already committed. Automatic
rollback of a method's failed postcondition would require a separate whole-method transaction
form; it is not implied here. Entry captures describe method-entry values, not transaction-entry
values, and never change during rollback.

## 5. Open decisions and delivery gates

- **IC-13 [Open]** Exact purity expansion, proof budgets and diagnostics, contract metadata
  encoding and compatibility, and richer override implication rules. Core contracts must remain
  enforceable without these extensions.
- **TXN-13 [Open]** Allocation and initialization: define whether new objects' contents are
  logged, how failed construction and local references surviving rollback behave, and how
  nested savepoints distinguish older allocations. Restoring default values into an initialized
  non-null field must not create a new type-safety hole. Until settled, disallow application
  allocation and first-time class initialization while a transaction is active, including
  dynamic calls; VM allocations needed for logs and exception reporting remain internal.
  Class-initialization flags must never be rolled back as ordinary static fields. Whatever is
  decided, rollback never restores a final field: finals are stored only by constructors and
  static initializers (BC-23), and the JIT may already have folded them.
- **TXN-14 [Open]** Destructible-object header transitions, weak-reference processing, finalizer
  scheduling, hot reload/layer unloading during active scopes, and resource limits for retained
  undo data. The first subset must guard unsupported transitions, not claim they are reversible.
- **TXN-15 [Open]** Thread/fiber context propagation, suspension and isolation. The initial
  feature permits neither multiple mutator threads nor suspension inside an active transaction.
  An undo log alone supplies no isolation or durability.

No milestone is added to the current self-hosting path. Before implementation, settle the
supported subset, update the owning grammar, type-system, bytecode, VM, memory, reflection and
JIT/AOT rules, and register experimental features under TST-02/TST-06. Older VMs must reject
unsupported transaction or contract semantics, not ignore them as optional metadata.

### 5.1 Required validation

| Area | Required cases |
|---|---|
| Contract syntax and typing | clause order, non-boolean clauses, contextual keyword compatibility, illegal `old`/`result`, rejected effects and inaccessible names |
| Contract execution | false precondition never enters body; all normal exits check postconditions; exceptional exits skip them; capture order, recursion, shallow references, reassigned parameters, cleanup-replaced returns |
| Proof fallback | proven, disproven and unknown conditions; unresolved checks in release builds; separately compiled and reflective callers; stale proofs invalidated on reload |
| Transaction control flow | fall-through, return, break, continue, caught and escaping exceptions, cleanup that writes or throws, failure to enter |
| Store coverage | instance/static fields, primitive/reference arrays, aliases, managed `ref`, overlapping structs, bulk writes, setters, callbacks, reflection and audited VM helpers |
| Nesting | inner rollback with outer continuation, inner commit then outer rollback, repeated writes at both depths; identical results with deduplication on and off |
| Failure and GC | log-growth failure before mutation, allocation-free replay, old references retained through collection, moving-GC handles, rollback barriers, unsupported-effect guards |
| Inactive path | no undo allocation, transaction old-value capture or deduplication access outside transactions; explicit overhead measurements, not a claim of zero cost |
| Execution tiers | the same observable outcomes in interpreter, quickened code, JIT and AOT, including optimized boundary crossings |

Proposed formal extensions, not existing Lean results: an FR-contract layer should establish
that normal returns satisfy checked or soundly proven postconditions; an FR-transaction layer
should establish restoration of the supported entry heap on an escaping exception, nested
savepoint correctness, and equivalence of logged/deduplicated and inactive execution paths.
Both must preserve typing and agree with exception lowering. Stability remains subject to the
existing [formal-model.md](formal-model.md) gate; unresolved allocation and external-effect cases
cannot be hidden by a proof of a smaller core.
