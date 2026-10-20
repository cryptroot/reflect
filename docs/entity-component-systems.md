# Proposal: First-class Entity-Component Systems

An Entity-Component System (ECS) as a language feature, supporting data-oriented design alongside
Reflect's object-oriented design. Components are inferred as structs; systems are inferred as
classes. The compiler understands their roles, checks access to component batches and can
specialize update kernels rather than treating ECS as an opaque collection library.

This feature is not implemented or assigned a release. All rules here are **[Proposed]** unless
marked **[Open]**. They do not yet amend the baseline [grammar](grammar.md), [type
system](type-system.md), [module format](bytecode.md) or [roadmap](roadmap.md). Conventions:
[architecture.md](architecture.md) section 0.

## 1. Motivation and scope

Data-oriented and object-oriented designs solve different problems and often depend on each
other. Updating thousands of positions benefits from contiguous values and predictable loops.
A renderer, screen, user session or resource owner still benefits from object identity,
encapsulation, interfaces and a managed lifetime. An ECS should not force those objects into
component tables, nor force every simulated entity to allocate an object graph.

The proposal has three layers:

1. **Language:** `component` and `system` declarations, checked update signatures and metadata.
2. **Runtime/library:** worlds, entity handles, queries, component tables and batch dispatch.
3. **Compiler:** a checked kernel subset, sharing Reflect's optimizing JIT/AOT backend, with a
   role similar to Unity Burst or the GPU sychronization gates seen in `nvcc` (when multi-threading arrives).

The first useful implementation is sequential, safe and inspectable. Threads, GPU execution,
relaxed floating-point arithmetic and automatic field-level layout changes are not prerequisites.
This does not replace ordinary structs, arrays, collections, classes or tracing GC.

## 2. Declarations and type rules

### 2.1 Surface syntax

```reflect
public component Position {
    public float x;
    public float y;
    public float z;
}

public system Movement {
    public void update(scoped Span<Position> positions, Batch index) {
        for (int i = index.start; i < index.end; i++) {
            positions[i].x += 1.0f;
        }
    }
}
```

This preserves the intended shape of `update(Position[] positions)`, but uses a
bounded view of live table rows instead of exposing an entire backing array, including unused
capacity. `Batch` is a range, not an implicit integer or a new array-index conversion.

- **ECS-01 [Proposed]** Reserve `component` and `system` as declaration keywords upon adoption.
  `World`, `Entity`, `Batch`, `Span`, `ReadOnlySpan` and `Kernel` are type names, not additional
  reserved words. Queries, ordering and allocation initially use ordinary APIs. Further keywords
  need a separate justification. Reservation is a source-compatibility change for existing
  identifiers; a draft compiler must gate the syntax and diagnose collisions before adoption.
- **ECS-02 [Proposed]** `component C` declares a nominal struct type with an ECS component role.
  It follows DAT-01/02: inline storage, value copies, zero initialization, no class inheritance,
  no finalizer, ordinary visibility, and `mutating` methods where required. It may implement
  interfaces, with the ordinary boxing rules; ECS storage and dispatch must not box its rows.
  Managed-reference fields remain nullable as DAT-01 requires. A component is not automatically
  unmanaged: reference-free eligibility is checked recursively, not inferred from its keyword.
- **ECS-03 [Proposed]** `system S` declares a nominal class type with an ECS system role.
  Constructors, fields, interfaces, inheritance, identity and GC behave as for classes. No
  instance, world registration or scheduling is implicit. A system can hold a renderer or other
  object dependencies, and ordinary methods remain ordinary methods.

Declaration grammar sketch, reusing the existing type parameters, constraints and bodies:

```text
TypeDecl      = Annotation* Modifier* ( ... | ComponentDecl | SystemDecl ) ;
ComponentDecl = "component" Ident TypeParams? ( "implements" Types )? Where? ClassBody ;
SystemDecl    = "system" Ident TypeParams? ( "extends" Type )? ( "implements" Types )?
                ( "permits" Types )? Where? ClassBody ;
```

Struct-only and class-only modifiers retain their existing restrictions. The roles are not a
new subtyping relationship: an arbitrary struct cannot be queried as a component, and an
arbitrary class is not a schedulable system. Closed generic component/system instantiations keep
Reflect's reified type identity; a query for `C<A>` does not match `C<B>`.

Keyword reservation may be avoidable if `Component` and `System` interfaces are used (same as `Destructible`).
This would also coincide with a `World` and `Entity` typing concept.
However, keyword use may allow better control over ECS use in general as it would avoid
accidental use of the interfaces in unrelated code.

### 2.2 Update signatures and queries

```reflect
public component Velocity {
    public float x;
    public float y;
    public float z;
}

public system Integrate {
    @Kernel
    public void update(scoped Span<Position> positions,
                       scoped ReadOnlySpan<Velocity> velocities, Batch batch) {
        for (int i = batch.start; i < batch.end; i++) {
            positions[i].x += velocities[i].x;
            positions[i].y += velocities[i].y;
            positions[i].z += velocities[i].z;
        }
    }
}
```

- **ECS-04 [Proposed]** The initial schedulable entry point is one directly declared,
  non-static, non-generic, non-virtual `void update` method: one or more scoped component spans,
  followed by one `Batch`. Ordinary overloads/helpers are allowed only if they do not introduce
  another candidate entry point. Missing or ambiguous entries are errors when registering a
  system, at compile time when its type is known, otherwise at run time. Inherited helpers do
  not become entries implicitly.
- **ECS-05 [Proposed]** The signature defines an intersection query: each matched entity has
  every listed component type. `Span<C>` declares read/write access; `ReadOnlySpan<C>` declares
  read-only access. An invocation's spans have equal live lengths, and element `i` of each span
  belongs to the same entity. A component type may appear only once in a signature; conflicting
  or duplicate access declarations are errors, not potentially aliasing parameters.
- **ECS-06 [Proposed]** `Batch` is a non-escaping ref struct describing the half-open range
  `[start, end)` within those spans, with `0 <= start <= end <= length`. The dispatcher may split
  a chunk into smaller ranges; every matched live row is covered exactly once per successful
  `World.run(system)` call. Empty matches perform no update calls. Batch boundaries are not a
  persistent entity ordering or a promise about vector width.

`World`, `Entity`, `Batch`, `Kernel` and the APIs below are proposed library names; their exact
module placement remains open. `Span<T>` and `ReadOnlySpan<T>` reuse DAT-03 rather than adding
another general borrowing model. An ordinary `Position[]` remains an array of inline structs;
it neither becomes world storage nor acquires entity-lifetime semantics because of its element
type. A future array adapter would need explicit lengths, alignment and access validation.

## 3. Worlds, entities and object-oriented integration

- **ECS-07 [Proposed]** A world owns entity identity, component storage and query caches.
  An entity is a small value handle containing a world identity token, a slot and a generation,
  not an object reference or table address. The zero handle is invalid. Every entity API checks
  world ownership and generation; stale, destroyed and cross-world handles fail explicitly.
  Reusing a slot increments its generation; a slot is retired rather than allowing generation
  wraparound to validate an old handle. World tokens must not be reused while old handles can
  still exist.
- **ECS-08 [Proposed]** Hosts explicitly create and run system objects. Initial scheduling is
  synchronous and single-threaded, as required by CON-01. Successive `run` calls establish order;
  there is no name-based discovery, hidden background update or automatic reordering. System
  state survives between calls because the host owns the same class instance.

Illustrative host-side API, to be specified with the library:

```reflect
World world = new World();
world.reserve<Position, Velocity>(4096);
Entity player = world.create(new Position(), new Velocity());
Integrate integrate = new Integrate();
world.run(integrate);
```

`reserve<A, B>(n)` reserves row capacity for the exact component set `{A, B}`, plus the
corresponding entity-directory capacity, or throws if that cannot be satisfied. `create` copies
the supplied values into one row and rejects duplicate component types. These are typed API
overloads, not a proposed heterogeneous-varargs conversion or a new generic-constraint syntax.
Reservation for one component set is not reservation for every possible combination.

An ordinary system can use objects without qualifying as an optimized kernel:

```reflect
public system RenderScene {
    private final Renderer renderer;

    public RenderScene(Renderer renderer) {
        this.renderer = renderer;
    }

    public void update(scoped ReadOnlySpan<Position> positions, Batch batch) {
        for (int i = batch.start; i < batch.end; i++) {
            renderer.draw(positions[i]);
        }
    }
}
```

A `Screen` object can own the world, a persistent `Integrate` and `RenderScene`, and run simulation
before rendering each frame. A `User` object can own an entity handle and translate input into
component values between runs. `Renderer` remains an ordinary class/interface managing its
resources. The example passes a position value to `draw`; it does not let a renderer retain a
reference into movable component storage.

Outside a run, proposed `get<C>(entity)` returns a value copy and `set<C>(entity, value)` writes a
checked value back. No ordinary API returns a persistent writable reference into a table.
Adding/removing a component changes membership; setting an existing value does not.

## 4. Component tables and arena-like lifetime

### 4.1 Archetypes and chunks

- **ECS-09 [Proposed]** The initial storage is an archetype table: one table per exact set of
  component types, split into bounded-capacity chunks. Each chunk has an entity-handle column
  and one contiguous, unboxed column per component type. All columns share the same live row
  count. A query requiring `{Position, Velocity}` also visits tables with additional components.
  Matching is cached at table granularity; it is not a component lookup for every row.

```text
Table {Position, Velocity}, chunk capacity N, live rows n

row          0       1       2       ...     n - 1     | spare capacity
Entity       e0      e1      e2      ...     en-1      |
Position     p0      p1      p2      ...     pn-1      | zeroed / reusable slots
Velocity     v0      v1      v2      ...     vn-1      | zeroed / reusable slots
```

This is **structure-of-arrays at component granularity**: positions are separate from velocities,
but a `Position` column initially stores `x, y, z, x, y, z, ...` in normal struct layout. It is
not automatically three field arrays. Each managed column can initially be a capacity-sized
array; a logical chunk need not be one physical allocation. An unmanaged slab representation
is a later implementation option, not permission to hide managed references from the GC.

### 4.2 Creation, destruction and migration

- **ECS-10 [Proposed]** Creation takes the next unused row in a non-full chunk, copies initialized
  component values, updates the entity directory and then publishes the handle. Destruction
  swap-removes the last live row into the removed row, repairs the moved entity's directory entry,
  clears the old last row and recycles the destroyed handle slot. Dense iteration therefore needs
  neither tombstone checks nor per-component heap allocation.
- **ECS-11 [Proposed]** Adding or removing a component migrates an entity to another archetype.
  Reserve the destination and validate inputs before changing membership; copy shared components,
  initialize added values, update the directory, then swap-remove the source row. An allocation
  failure must leave the original entity intact, not half-migrated. Creation likewise must not
  publish an incomplete row. Unused reference slots are cleared so they cannot retain objects.

This gives the requested arena-like behavior: capacity already exists for many future rows,
creation fills it in place, and removal immediately makes capacity reusable. It is not a guarantee
that an entity stays at one address. Swap-removal and migration deliberately move values;
generation-checked entity handles are the stable indirection.

| Operation                               | Expected cost and limits                                                                         |
| --------------------------------------- | ------------------------------------------------------------------------------------------------ |
| Query iteration                         | Linear in matched live rows, plus matched chunks/tables; no per-row membership search            |
| Create with available reserved capacity | Constant row/handle bookkeeping plus initialization/copies proportional to the component payload |
| Destroy                                 | Constant row/handle bookkeeping plus moving and clearing one row's component payload             |
| Add/remove component                    | Destination lookup/capacity work plus copying/moving the affected rows; not a free flag change   |
| Grow a table or entity directory        | May allocate, initialize storage and trigger GC; not covered by the warmed-up fast path          |

Empty chunks may be retained for reuse or explicitly trimmed. Reservation covers storage, not
allocations performed by application constructors or helpers. Cached queries, dispatch state and
metadata must also be warmed up before claiming allocation-free updates. Chunk size and retention
policy should be measured rather than fixed by source-language semantics.

Entity destruction removes values and invalidates the handle; it is not `Destructible.destroy()`
(DAT-08), does not run a struct finalizer, and does not recursively destroy referenced objects.
Native resources need an explicit OO owner and cleanup protocol. Closing a world releases its
storage and invalidates its handles; automatic resource callbacks are outside the initial proposal.

## 5. Access safety and execution semantics

- **ECS-12 [Proposed]** `World.run` exclusively leases the world's component storage until all
  invocations return, including exceptional exit. Re-entering that world, running it recursively,
  or using its direct entity read/write/structural APIs during the lease throws a defined access
  error. This rejects mutation through an object dependency as well as through the update body.
  Updates use only the supplied views for that world's component data.
- **ECS-13 [Proposed]** Update spans, derived `ref` values and `Batch` cannot escape the invocation:
  no heap fields, boxing, captures, returns or delayed tasks. DAT-03 applies, and the verifier must
  additionally preserve the dispatch lease boundary. No compaction, migration, trimming or
  destruction occurs while a view is live. Static escapes are compile errors; dynamic lifecycle
  conflicts throw rather than returning an empty query or ignoring a write.
- **ECS-14 [Proposed]** An update exception propagates to the host after the lease is released in
  a `finally`-equivalent boundary. Earlier component writes remain visible; later batches are not
  run. ECS is not an implicit transaction. For a fixed world state and implementation, traversal
  is deterministic across interpreter, JIT and AOT; source programs must not use row order as
  persistent identity. A separate stable-order API would have an explicit cost.

Creation/destruction and component-set changes initially happen between runs. A deferred command
buffer could later allow systems to request changes for a synchronization boundary, but its
ordering, failure behavior and temporary-handle rules need a separate design. It must not be
silently inserted as an allocation-heavy substitute for in-place access.

Managed-reference components stay legal in ordinary systems. Their backing stores must be visible
to the collector; copies, swaps, clearing and `ref` writes obey GC-02 barriers. The collector must
understand all reference-containing struct array elements and active interior references, including
under a moving collector. Safepoints remain required; a kernel is not `@Uninterruptible`.

The proposed [Transactions](intent-contracts-and-transactions.md) feature is independent. Enabling
both needs an explicit policy for logging component writes and undoing membership/directory
changes, or a diagnostic rejecting the combination; ECS must not bypass transaction interception.

## 6. Checked kernels and AOT

- **ECS-15 [Proposed]** `@Kernel` opts an update entry into a compiler-verified, Burst-like subset.
  The initial subset permits primitive arithmetic, reference-free safe component values,
  scoped spans, bounded batch access and statically resolved helpers verified transitively.
  Raw pointers and unsafe escapes are excluded initially. It rejects managed allocation, boxing,
  managed-reference component payloads, reflection, I/O, unresolved/virtual calls, recursive call
  graphs, explicit throws and exception handlers. Receiver reads are limited to final,
  reference-free values copied into dispatch arguments; receiver mutation/escape is rejected.
  A system's other methods and unaccessed object dependencies are not thereby prohibited.
- **ECS-16 [Proposed]** Kernel restrictions are checked before any execution tier runs the method,
  not only when an AOT optimizer happens to inspect it. Invalid kernels are compile/load errors
  with the offending operation or call chain reported, not silently downgraded to ordinary
  systems. A valid kernel still has an interpreter implementation. If a particular optimization
  is unprovable or unavailable, execute the same valid kernel with scalar checked code and report
  the missed optimization in compiler diagnostics.

The distinction matters: `RenderScene` can make virtual renderer calls because it is an ordinary
system. Adding `@Kernel` to it would be an error, not a promise to somehow optimize rendering I/O.
Implicit bounds, arithmetic and other required checks still apply inside valid kernels; the
annotation is not a guarantee that execution cannot throw.

- **ECS-17 [Proposed]** AOT specializes update entry points and query dispatch for known closed
  component sets using the existing [optimizing backend](jit-aot.md), not an external toolchain.
  It can precompute component layouts, access descriptors and matching tables where the world
  shape is known. Dynamically introduced archetypes still update query caches. Generic sharing,
  reflection metadata, dynamic loading and mixed-mode fallback follow JIT-04/GEN-08; no closed-world
  assumption is inferred from `system` or `@Kernel`.
- **ECS-18 [Proposed]** Strong alias/access facts belong to validated ECS dispatch. An ordinary
  direct call to `update` with caller-created spans must not inherit a false no-alias assumption.
  Optimized entry stubs must establish the required facts or use an ordinary checked path.
  All tiers preserve NUM-04/JIT-07: floating-point results, overflow, evaluation order and precise
  observable exceptions. AOT changes when compilation happens, not the language's arithmetic.

An AOT executable can therefore enter a precompiled update without JIT warm-up or repeated query
construction, while keeping the interpreter/JIT fallback required by Reflect's mixed-mode design.
No performance multiplier follows merely from marking a class as a system.

## 7. Optimization opportunities and their proof conditions

| Opportunity                        | Why ECS helps                                                                         | Required guard or limit                                                                   |
| ---------------------------------- | ------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| Cache locality and prefetching     | Dense component columns avoid per-entity object chasing and skip unrelated components | Chunk occupancy and workload still determine cache behavior                               |
| Fewer allocations and less GC work | Inline values share column storage; reserved slots are reused                         | Managed-reference fields still require scanning/barriers; capacity growth still allocates |
| Query specialization               | Component/access sets are explicit in the update signature                            | Invalidate cached table matches when archetypes change; respect reified types             |
| Inlining and scalar replacement    | Statically resolved kernel helpers and known value layouts expose the arithmetic      | Preserve effects, exceptions and module/hot-reload dependencies                           |
| Bounds-check hoisting/elimination  | Dispatcher proves matching span lengths and batch ranges                              | Prove every accessed index, including offsets; retain checks otherwise                    |
| Alias analysis                     | Distinct component columns and checked access modes expose disjoint storage           | No unchecked casts, duplicate access paths or unvalidated direct-call assumptions         |
| Loop unrolling and register reuse  | Dense, simple kernels expose induction variables and repeated field access            | Preserve row effects and exception points; measure code size                              |
| AOT dispatch and specialization    | Known entry points/layouts can be compiled and bound before startup                   | Retain a valid path for dynamic types, modules and shared generics                        |
| SIMD, later                        | Independent rows may permit packed arithmetic                                         | Requires backend work, dependency/exception proofs and strict per-lane arithmetic         |
| Parallel scheduling, later         | Read/write component sets can reveal non-conflicting jobs                             | Requires threads, a memory model and verified effects beyond component parameters         |

**SIMD is not already available.** JIT-09 currently specifies scalar SSE2 floating-point
instructions. Packed floating-point updates would require an explicit extension to that rule and
the shared backend, plus scalar tails and capability checks for instructions beyond the baseline.
Even then, no implicit FMA contraction, reassociation or reduction-order change is allowed.
Vectorization must prove that inter-row dependencies and possible exceptions do not change which
writes occur before a failure; otherwise the loop remains scalar.

**Field-level SoA is a separate question.** Splitting `Position` into `x[]`, `y[]`, `z[]` could
improve SIMD utilization, but a normal `Span<Position>` promises addressable struct elements.
Silently splitting those fields would break `ref` behavior and layout-sensitive code. A future
field-view API or explicitly restricted layout mode needs its own semantics. The initial proposal
keeps normal struct layout and can still benefit from component-level columns.

**Parameter access modes alone do not prove parallel safety.** An ordinary system may mutate its
receiver, call a renderer, touch statics or access another world. A future scheduler must analyze
those effects or require a verified restriction before reordering or overlapping calls. It must
also define reductions and cross-row dependencies. CON-01 remains unchanged in the initial feature.

## 8. Compiler, bytecode and tooling integration

- **ECS-19 [Proposed]** Lower components and systems through the existing struct/class machinery,
  retaining validated role metadata plus query/access and kernel descriptors. Reflection reports
  the underlying `STRUCT`/`CLASS` kind with discoverable ECS roles, not a second incompatible type
  system. Generators and analyzers see the same roles at compile time and run time.
- **ECS-20 [Proposed]** The verifier checks role consistency, legal entry signatures, scoped-view
  lifetime and kernel restrictions for loaded/produced bytecode, not just source emitted by
  `rflc`. A metadata flag cannot certify unsafe code by itself. The initial design should lower to
  existing field, array, `ref` and call operations where possible; intrinsic dispatch/storage hooks
  need a documented verifier contract. No opcode numbers or file-version changes are reserved here.

Diagnostics should identify the component query, read/write sets, closed instantiation, selected
kernel tier and reasons an optimization was not applied. World statistics should expose live rows,
capacity, chunks, archetype count, migrations and bytes reserved so arena-like reuse is measurable.
Debuggers should map optimized columns back to entity handles and component values.

Hot reload may replace an update body only after active invocations finish, invalidating inlined
code and descriptors as necessary (JIT-06). Changing component layout requires an explicit
migration/rebuild or rejection while worlds contain that type; existing chunks must never be
reinterpreted with new offsets. Worlds and caches hold their types/layers alive until released;
they must not create permanent global roots that defeat MOD-10.

## 9. Validation and performance evidence

- **ECS-21 [Proposed]** Before stabilizing, add conformance, verifier, collector-stress and
  differential tests following [testing.md](testing.md), plus a formal extension covering entity
  validity, aligned queries, lease lifetime and preservation under row moves. DAT-03/FR-ref is a
  prerequisite, not a substitute for proving the ECS-specific storage rules.

Required correctness cases include:

- Struct copying, zero/default values, visibility, managed-reference nullability and generic
  type identity; normal class construction and dependency injection for systems.
- Aligned multi-component queries across several archetypes/chunks; empty worlds, partial batches,
  read-only violations, duplicate component parameters and missing/ambiguous update entries.
- Destruction of first/middle/last rows, repeated reuse, stale/cross-world handles, generation
  exhaustion, world closure and directory repair after swap-removal and migration.
- View escape rejection, recursive runs, structural/direct access during a run, and an update
  that throws midway: prior writes remain, later batches do not run, and the lease is released.
- Allocation failure before create/migration publication; managed-reference swaps/clears under
  stress GC; kernel rejection via indirect helper calls and forged metadata.
- Matching results and exceptions in interpreter, baseline JIT and AOT, including checked overflow,
  NaN, infinities, signed zero, scalar tails and any later SIMD path.
- Hot-reload invalidation and eventual layer collection after worlds/caches release their types.

Performance comparisons must use identical calculations and entity populations:

| Baseline or experiment                                                | What it isolates                                                       |
| --------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| OO entities with equivalent fields                                    | Object allocation, pointer chasing and dispatch overhead               |
| Hand-written `Position[]`/`Velocity[]` loop                           | Whether first-class ECS approaches the existing data-oriented baseline |
| Ordinary system versus verified kernel                                | Benefits of restrictions, specialization and inlining                  |
| Interpreter/JIT/AOT, cold and warmed separately                       | Compilation/startup costs versus steady-state update throughput        |
| Reserved versus growing tables, with create/destroy/migrate workloads | Slot reuse, memory overhead and structural-change costs                |

Report rows/second, time per update and structural operation, allocated bytes, GC time, live versus
reserved bytes and generated code size, with hardware, build flags and repeated-run variability.
Include small and large populations, low occupancy, many archetypes and queries that use only one
field. After reservation and warm-up, a reference-free kernel run and create/destroy cycles within
reserved row/directory capacity, with preconstructed values, should allocate **zero managed
bytes**; test that claim directly. Do not hide allocations in per-frame descriptors or boxed rows.
Any throughput acceptance threshold should be agreed from these measurements, not invented as a
Burst-like speedup claim.

## 10. Delivery and open decisions

Suggested stages, unscheduled and not additions to R0 or the self-hosting critical path:

1. Specify role metadata, entry signatures, leases and entity semantics; implement ordinary
   sequential systems over managed column arrays, with correctness tests.
2. Add chunk reservation, swap-removal, migration, query caching, statistics and GC stress coverage.
3. Check the kernel subset in compiler and verifier; add AOT specialization and benchmark against
   hand-written struct-array loops using the existing scalar backend.
4. Consider SIMD only after backend/numeric conformance work; consider parallel execution only
   after threads and their memory model. Neither blocks a useful sequential ECS.

**[Open]** Exact module/API names; chunk sizing and trimming policy; filters, tags, optional
components and variable-length component buffers; stable iteration and serialization order;
deferred structural commands and lifecycle events; migration tooling for layout changes; and the
exact proof layer and milestone. A field-split representation, relaxed-math mode, GPU target or
parallel scheduler each requires a separate explicit proposal.

[GPU Access, Reflect-G and a GPU-resident Interpreter](gpu.md) supplies that separate GPU
direction. It does not make an ECS `@Kernel` a GPU entry: offload still needs checked transfer
layouts, explicit leases/dependencies and device-profile validation.

The central commitment is smaller: keep OO objects where identity and ownership are useful,
store component values densely where bulk processing is useful, and give Reflect enough checked
information to optimize that processing safely, including ahead of time.
