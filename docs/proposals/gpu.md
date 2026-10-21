# Proposal: GPU Access, Reflect-G and a GPU-resident Interpreter

Two related but independently useful features: generic access to GPU capabilities and execution
of Reflect bytecode by an interpreter running on the GPU itself. **Reflect-G** names a checked
GPU subset of Reflect, including the code used to implement that interpreter.

This work is not implemented or assigned a release. All rules here are **[Proposed]** unless
marked **[Open]**; they do not amend the baseline [language](../type-system.md),
[module format](../bytecode.md), [backend](../jit-aot.md) or [roadmap](../roadmap.md). Conventions:
[architecture.md](../architecture.md) section 0.

This is a companion to [Threads, Atomics and Lock-free Semantics](threading.md), not an addition
to R0 or v1.0. CON-01's single-threaded host VM remains the baseline. Submitting work to a device
does not require an application-visible CPU thread pool or `async`/`await`; neither becomes
implicitly available through this proposal.

## 1. Scope: access is not interpretation

- **GPU-01 [Proposed]** Split the design into a GPU-access layer and a device-execution layer.
  A renderer should be able to use the first without running a bytecode interpreter. The
  interpreter should use the same device/resource/submission contracts as other compute work.

| Layer | Responsibility | Does not imply |
|---|---|---|
| GPU access | Discover adapters; request features/limits; manage buffers, images, queues, commands and completion | A language kernel, bytecode execution or a complete rendering engine |
| Reflect-G | Check ordinary Reflect code against a device profile and lower it for execution | The whole standard library or the host VM on the GPU |
| GPU-resident interpreter | Execute a verified Reflect bytecode subset on device, using device-resident VM state | Offloading each bytecode step to the CPU or compiling every guest program to a native shader |

Distinguish three representations:

1. A **GPU command list** describes transfers, dispatches, rendering and synchronization.
2. A **guest program image** contains verified Reflect bytecode and resolved metadata/data.
3. A **device executable** contains the interpreter, or a directly compiled Reflect-G kernel.
   A backend representation such as SPIR-V is not Reflect bytecode.

The first milestone can support transfers and compute only. The access API must nevertheless
leave a coherent path to graphics queues, textures, graphics pipelines and presentation.

## 2. GVM: precedent and its limits

Ahmet Celik, Pengyu Nie, Christopher J. Rossbach and Milos Gligoric published
[*Design, Implementation, and Application of GPU-Based Java Bytecode Interpreters*](https://doi.org/10.1145/3360603)
at OOPSLA 2019, as article 177 of *Proceedings of the ACM on Programming Languages*, volume 3.
The [author-hosted paper](https://pengyunie.com/files/CelikETAL19GVM.pdf) describes GVM:

- The CPU packages class files, allocates device storage and transfers code/data; bytecode
  interpretation then runs on the GPU, without a host round trip for each instruction.
- Each tinyBee interpreter normally maps to a GPU thread, sharing code but owning its heap,
  statics, operand stack, frame stack and program counter. Handles replace host pointers.
  The paper also explores scheduling one tinyBee per warp rather than per hardware thread.
- Its target is many short-lived independent tasks sharing substantial code and having similar
  execution times. The evaluated applications are sequence-based test generation and execution
  of randomly generated tests, not general-purpose GPU rendering.
- The implementation supports substantial Java behavior, including allocation, dynamic dispatch
  and exception handling, but is not a complete JVM: it excludes `invokedynamic`, monitor
  instructions and multithreaded guests; native/library support is selective.
- There is no reclaiming guest GC: bounded heaps suit its short-lived jobs. The paper also
  reports floating-point differences and other compatibility gaps, and excludes guest access
  to external resources such as files and networks (sections 4 and 8).

The lesson is that GPU-resident interpretation is practical for a suitable task shape, not that
interpreting arbitrary programs always beats CPU JITs. Reported results distinguish sequential
CPU baselines from parallel JVM/JIT comparisons. Reflect must measure its own trade-offs and
preserve its own semantics rather than adopt GVM's compatibility gaps or CUDA implementation.

## 3. Platform and toolchain policy

- **GPU-02 [Open]** Select a backend and settle its dependency policy before promising hardware
  support. ARC-02, LIB-07 and UNS-04 currently exclude a maintained C/C++ layer, host libraries
  and pre-v1.0 C-library calls; JIT-01 currently has only an x86-64 backend. A Reflect API alone
  does not make a vendor driver, shader compiler or dynamic loader exist.

| Candidate | Opportunity | Unresolved cost |
|---|---|---|
| Vulkan-style backend | Compute and graphics under one capability/command model; SPIR-V device programs | User-space loader/driver ABI and dependency policy; synchronization and presentation integration |
| Compute-only vendor/API backend | A narrower interpreter experiment on selected hardware | Portability, vendor runtime dependencies and no implied graphics support |
| Direct kernel/driver interface in Reflect | Avoid a foreign user-space API where feasible | Driver-specific command/memory interfaces, substantial maintenance and no generic portable DRM compute ABI |
| CPU reference/simulated device | Validate profiles, commands and guest semantics without GPU hardware | No evidence of hardware execution, GPU performance or real driver behavior |

A Vulkan-shaped model is a useful design reference, not a selected backend. Emitting SPIR-V
directly from `rflc` avoids a required GLSL/CUDA/OpenCL source compiler, but the driver may still
compile that intermediate representation. Calling a user-space driver through a Reflect-owned
loader needs an explicit post-v1.0 exception to the current library policy; it is not silently
authorized by UNS-04's deferred FFI.

The maintained compiler, verifier and interpreter should remain Reflect code. A device-lowering
path in `rflc` is new work, not something the CPU backend already provides. CPU-only builds and
the bootstrap fixpoint must not require a GPU, vendor SDK or GPU driver.

## 4. GPU access: devices, resources and commands

### 4.1 Capability requests

- **GPU-03 [Proposed]** Put the public access facade in a separate proposed `reflect.gpu`
  module. Keep backend services narrowly scoped; `reflect.core` must not depend on rendering,
  a vendor SDK or the compiler. API names below are illustrative, not a new syntax commitment.

Discovery reports adapter identity, backend availability and immutable capability/limit data.
Opening a device checks a request containing required and preferred capabilities:

| Capability group | Examples |
|---|---|
| Execution | Transfer, compute, graphics and presentation; available queues and workgroup limits |
| Memory | Device-local/host-visible storage, maximum allocation/binding sizes, required alignment |
| Data and arithmetic | Buffer/image formats; integer widths; exact floating-point profile support |
| Synchronization | Completion primitives, memory scopes, supported atomic operations and widths |
| Interpreter | Accepted guest-image/profile versions, frame/state limits and dispatch budget limits |

Missing required capabilities produce a structured unsupported-capability error naming the
requirement and adapter. Preferred features can be omitted only with the selected configuration
reported. Distinguish no adapter, unavailable backend, permission/driver failure and unsupported
request. A CPU simulator is an explicitly selected backend, never a success-shaped GPU fallback.

### 4.2 Resources and memory ownership

- **GPU-04 [Proposed]** Devices own opaque resource handles with identity, generation, declared
  usage and explicit lifetime. Typed buffers, images, views, samplers, pipelines and command
  objects must reject cross-device use, stale handles and incompatible usages. Validate sizes,
  offsets, alignment and ranges with overflow-safe arithmetic before submission.

A typed `DeviceBuffer<T>` admits only a defined, reference-free transfer representation.
`T extends struct` alone is insufficient: structs may contain references, and CPU struct layout
is not automatically a portable GPU ABI. Define field layout, padding, strides, byte order,
boolean encoding and supported widths; reject recursive/reference-bearing layouts. No API
exposes the host object header or treats a managed address as a device resource.

- **GPU-05 [Proposed]** Use explicit upload/download and device-owned storage first. Upload
  snapshots the source into owned staging storage before returning, or exposes an explicit
  borrow lasting until completion; the initial safe facade should choose the snapshot form.
  Download publishes destination data only after completion and required visibility/cache work.
  A host `Span<T>` must never outlive its call merely because a DMA operation has been queued.

Command submissions retain all referenced resources and staging allocations until terminal
completion. Closing a wrapper rejects new uses and defers physical release while commands
still use the resource; finalizers are only leak safety nets, not synchronization. Do not reuse
an allocation or handle while an earlier submission can still address it.

Host-visible mappings, coherent/unified memory, zero-copy and external-memory import are later
capabilities with explicit leases and ownership transitions. Shared physical memory does not
remove visibility, aliasing, GC, pinning or lifetime obligations.

### 4.3 Command subset and completion

- **GPU-06 [Proposed]** Begin with a finite, validated command vocabulary:
  resource bindings, buffer copy/fill, compute-pipeline binding, bounded dispatch, dependency
  barriers and completion signaling. Track declared read/write ranges and queue ownership;
  reject conflicting use without an ordering dependency, including aliases of one allocation.

Host submission order is not a general memory barrier. Encode execution dependencies and
availability/visibility transitions explicitly; cross-queue work needs explicit dependencies.
A completion object provides polling and blocking wait without requiring `async`/`await`.
After successful completion, the download/map contract establishes visibility to host code;
later handoff to another CPU thread still needs the [threading publication rules](threading.md).

Errors during encoding are reported before submission when possible. Asynchronous execution
errors belong to the completion result, with operation/task context; submission success is not
execution success. Timeouts mean completion is unknown, not permission to release memory.
Device loss fails affected submissions and invalidates the context; resources remain quarantined
until backend teardown confirms they are no longer accessible. Never silently replay GPU writes.

### 4.4 Rendering is an access-layer client

- **GPU-07 [Proposed]** Extend the same access model with graphics capabilities, not a second
  ownership system: textures/image views, samplers, render targets, graphics pipelines,
  vertex/index bindings, bounded draw commands and image transitions. Request formats,
  sample counts and optional rendering features explicitly.

Presentation is optional and separate from off-screen rendering. A surface/swapchain extension
must define acquire, render, signal and present ordering, resize/out-of-date handling and image
ownership. Rendering can consume compute-produced buffers/images through declared dependencies.
It does not require a bytecode guest to call a graphics driver or a new scene-graph framework.

The companion [rendering proposal](rendering.md) builds on these contracts with an abstract RHI,
platform-owned presentation and separate GUI and terminal UI frontends. Its raster profile
reuses this device/resource/submission model; a separate cell profile supports real terminals
without requiring a GPU. It does not create a second graphics ownership or driver stack.

Graphics shader modules and Reflect-G compute/interpreter modules are distinct entry kinds with
backend validation. Arbitrary native shader binaries are not made safe by being called
"validated": a safe facade needs resource-robustness guarantees; an unchecked/native escape
must be explicitly gated under the unsafe policy.

## 5. Reflect-G: a checked device subset

- **GPU-08 [Proposed]** Reflect-G is ordinary Reflect with a transitive device-profile check,
  analogous in purpose to [Reflect-S](../bootstrapping.md#7-the-native-vm-and-reflect-s), but not
  the same restrictions. It is not a second source language or an automatic property of
  `unsafe;`, `@Uninterruptible` or an ECS `@Kernel`.

The first mandatory guest/device profile should be deliberately small:

| Area | Initial profile |
|---|---|
| Values | Fixed-width integral primitives, `boolean`, reference-free structs and explicitly typed device views |
| Control | Locals, conditionals, loops, switches and statically resolved calls |
| Generics | Closed admissible instantiations; reified identity/layout retained in the package where needed |
| Memory | Explicit arguments, read-only inputs and task-owned bounded output/scratch ranges |
| Calls | Direct helpers checked transitively; only allowlisted, specified device intrinsics |
| State | Explicit invocation/task ID and arguments; immutable constants, no mutable host statics |
| Failures | Required arithmetic/bounds checks with the task-fault protocol in section 6 |

Exclude managed allocation/boxing, host references and pointers, unrestricted virtual calls,
reflection, emit, dynamic loading, recursion, explicit exceptions/handlers, finalizers,
`Destructible`, monitors, CPU threads, `async` and syscalls. Reject them through indirect helpers
too. Later profiles may add features with their own runtime support; a subset declaration does
not authorize an implicit host callback to implement a missing operation.

Device views are scoped descriptors of resources/ranges, not ordinary `Span<T>` values pointing
into the CPU heap. Their reads/writes remain bounds checked. Restrict initial tasks to immutable
shared inputs and disjoint output/scratch ownership; reject overlapping bindings or require a
validated partition descriptor. A method annotation alone supplies no no-alias proof.

```reflect
// future: illustrative Reflect-G API, not enabled syntax
public final class Scale {
    @GpuEntry
    public static void twice(GpuInvocation invocation,
                             ReadOnlyDeviceSpan<int> input,
                             DeviceSpan<int> output) {
        int i = invocation.taskId;
        if (i < input.length && i < output.length) {
            output[i] = input[i] * 2;
        }
    }
}
```

Here each logical task writes at most its own element. `GpuInvocation`, the device views and
`@GpuEntry` are provisional API names. A host dispatch descriptor establishes the input/output
bindings and partition; the method is not silently invoked once for an entire array.

- **GPU-09 [Proposed]** Preserve NUM-03/NUM-04 and JIT-07, not a GPU's convenient default
  arithmetic. Integer overflow, masked shifts, signed division corner cases and conversions
  must match the CPU reference semantics. Floating point is an additional profile/capability,
  not mandatory for the initial integer interpreter.

A strict float profile needs the required rounding, subnormals, NaN/signed-zero behavior and
conversion results; disable implicit contraction/reassociation and do not silently flush values
to zero. Emulate operations where necessary or reject unsupported profiles. A relaxed-math
profile would be separately requested and specified, never selected as a performance fallback.
GVM's reported floating-point differences are a warning to test this boundary, not a precedent
for weakening Reflect's default arithmetic.

- **GPU-10 [Proposed]** Check profile closure in the compiler and package verifier, including
  generated code, generic instantiations, intrinsic signatures and all reachable helpers.
  Diagnostics identify the offending operation and call chain. Unsupported programs are
  rejected, not silently transferred to CPU execution midway through a dispatch.

Compile-time reflection may inspect ordinary symbols and build a package; device code does not
thereby acquire run-time reflection. Resolve allowable initialization/metadata before launch;
only permitted immutable constants are implicit inputs. Any snapshot of run-time state is an
explicit dispatch argument, not a hidden change to VM-07 initialization or per-instantiation
static semantics.

## 6. Running an interpreter on the GPU

### 6.1 Bootstrap versus interpreted execution

- **GPU-11 [Proposed]** Write the interpreter implementation in Reflect-G and compile it to a
  device executable using a new `rflc` device-lowering path. That executable implements the
  bytecode fetch/decode/execute loop on the GPU. A GPU cannot start interpreting Reflect
  bytecode merely by receiving `.rflc` bytes: the device interpreter must already exist.

```text
Reflect-G interpreter source -> rflc device lowering -> device executable
Reflect guest .rflc modules  -> verify/resolve/package -> guest image
host GPU-access API         -> upload image/state, launch interpreter executable
GPU                         -> execute guest instructions, store state/results/status
host                        -> wait, inspect status, download explicit outputs
```

No host service is required per guest instruction. Initial loading, validation, resource
allocation, submission and result processing remain host responsibilities, as in GVM.
Directly AOT-compiling a Reflect-G application to a native kernel is a useful separate mode;
it must not be presented as evidence that the bytecode interpreter is running.

- **GPU-12 [Proposed]** Share immutable program data across interpreter instances, while each
  logical task owns a PC, registers/operand slots, frames, bounded scratch and result/fault
  record. Use checked resource-relative handles/indices, not host object addresses or CPU
  method pointers. Every task has explicit state and instruction/frame/memory limits.

Keep canonical stack bytecode as the source of meaning (BC-01). A prototype can interpret a
verified subset directly or use a normalized, pre-resolved form, reusing VM-05's stack-to-register
idea where suitable. GPU images must not contain the CPU interpreter's mutable quickening
caches. Preserve source method tokens/bytecode offsets for faults and prove each normalization
preserves semantics; reject unrecognized instructions before upload.

The initial scalar/buffer guest profile has no object heap and no guest GC. This is narrower
than GVM on purpose. A later object-capable profile could add task-local bounded arenas,
device type tables, reference handles, dynamic dispatch, exception tables and per-instance
statics. Such guests are explicitly isolated VM instances, not transparent copies of the
host runtime's statics/heap. Allocation needs initialization/nullability and identity rules;
arena exhaustion must be a reported guest failure. Reclaiming GC, shared objects and host/device
object graphs each need a separate design.

### 6.2 Faults, budgets and output validity

- **GPU-13 [Proposed]** Preserve required checks without allocating host-style exceptions on
  device. On a bounds/arithmetic fault, stop that task before the invalid operation, recording
  a typed fault code, task ID, method/offset and bounded diagnostic operands. Completion exposes
  per-task success/fault status; the host may materialize a diagnostic exception at the boundary.
  Do not claim an arbitrary cross-device `Throwable` object or checked-exception handler ran.

Within a task, instructions before the fault keep their effects; subsequent instructions do
not execute. Other tasks can complete. A failed dispatch is not a transaction, and output is
not automatically rolled back: downloads must expose validity/status or refuse unqualified
success-only access to failed/partial results. Fault details must never be converted to zero
values or dropped because the GPU API itself reported successful completion.

Interpreter steps and intrinsic work must be bounded. An instruction budget causes a resumable
yield at an instruction boundary with the full logical state saved, or a distinct budget-limit
failure under a caller-selected policy. Keep resources/images alive across resumptions and do
not restart a partially executed task as if it were new. Cancellation has the same lifetime
and partial-output rules. Budgeting reduces watchdog risk; it is not a hard wall-clock deadline
or permission to free memory after a timed-out wait.

### 6.3 Scheduling and useful workloads

- **GPU-14 [Proposed]** Begin with one independent logical interpreter task per device
  invocation, grouping tasks by program/profile and similar expected work. Explore transposed
  state layouts, batching, active-task compaction and lane-versus-subgroup scheduling only
  after correctness. Logical task IDs stay stable when physical lanes or batches change.

SIMT divergence, large private stacks, spills and low occupancy can make interpretation slower
than the CPU. Do not bake a warp width of 32 into public semantics. A persistent interpreter
kernel or subgroup-wide cooperative interpreter is optional, not the only execution model.

Initial candidates are batched reference-free test cases, bytecode conformance fragments,
parameter sweeps and many short simulations sharing code. Full object-heavy sequence-based
test generation like GVM's is a later-profile candidate, not something the initial no-heap
subset already supports. Long serial jobs, blocking IO and heavily divergent tasks are poor
default targets.

## 7. Device memory ordering and atomics

- **GPU-15 [Proposed]** Specify device synchronization separately from CPU threads. Queue
  dependencies, memory scopes/address spaces, workgroup barriers, device atomics and host
  completion are not interchangeable forms of `synchronized` or `Atomic<T>`.

Start independent interpreter tasks without inter-task barriers or atomics. Read-only shared
code/inputs plus disjoint outputs avoid assuming shared-heap concurrency. Future cooperative
profiles must define uniform barrier participation, fault/yield behavior and visibility before
they become usable. A lane that faults before a barrier must not leave peers waiting forever.

Device atomics require explicit supported operations, width, address space, scope and ordering.
CPU atomic-cell representation and lock-free guarantees do not automatically extend to device
memory. Even unified memory needs an explicit system-scope/host-interoperability capability.
A CPU volatile flag is not a portable GPU completion signal.

Do not implement an all-workgroup barrier or lock by spinning on another workgroup: concurrent
residency and forward progress are not guaranteed. Use separate dispatches and API dependencies
first. Cross-queue ownership and host download/publication rules remain in the access layer.

## 8. Which command subset can device code use?

- **GPU-16 [Proposed]** Keep host driver commands out of the initial bytecode guest. Guest
  buffer reads/writes and arithmetic are not permission to create devices, allocate driver
  resources, submit arbitrary command buffers, present surfaces or call host syscalls.

A small command-list representation can be interpreted/validated by the host backend first.
Device execution of control logic may later consume a prevalidated list or produce bounded
indirect dispatch/draw parameters into designated buffers, provided the backend advertises
that capability. Validate resource allowlists, offsets, counts, nesting and total work limits;
declare the dependency between producing parameters and consuming them. Untrusted guest bytes
must never become an unrestricted native GPU command stream.

Dynamic device-side launches, persistent work queues and a general device command interpreter
remain separate extensions. A command request that needs host mediation must say so explicitly
and work at a batch boundary, not disguise CPU execution as GPU-resident interpretation.

## 9. Packaging, compatibility and integration

- **GPU-17 [Proposed]** A guest package identifies its source critical-content hashes, entry
  points, profile/version, closed type/layout information, resource bindings, limits and fault
  protocol. A device executable additionally identifies backend requirements and interpreter
  ABI. Guest code may be shared; mutable state must not be hidden inside a supposedly immutable
  package.

Validate metadata consistency and each entry's reachable code; a `@GpuEntry` annotation is not
proof. Package loading checks version/capability compatibility on the CPU before submission,
with device-side bounds/handle checks protecting dynamic accesses. The CPU simulator uses
the same checked image/profile and task-fault contract.

GPU-critical semantics must not be skippable ancillary metadata (BC-20). Either define a
versioned device package alongside `.rflc` or extend critical module content when accepted;
this proposal assigns no opcode, flag, file extension or format number. Cache identity includes
profile/layout versions, compiler/lowering version, interpreter ABI and relevant backend/device
requirements. Persistent driver artifacts may need driver-specific cache keys too.

Keep immutable program images and their layers/resources alive until all submissions and
resumable tasks retire. Hot reload creates a new image for new work; an in-flight task must not
execute mixed old/new bytecode or layouts. GPU records contain stable image IDs and offsets,
not pointers into a layer the host can unload.

Other proposals do not silently extend their guarantees:

- [ECS kernels](entity-component-systems.md) and Reflect-G have overlapping restrictions, but
  an ECS `@Kernel` is not a GPU entry. Offload needs explicit transfer layouts, partition proofs,
  component-table leases, completion and structural-change boundaries. Managed-reference
  components remain ordinary CPU data unless separately marshalled.
- [Transactions](intent-contracts-and-transactions.md) cannot undo already submitted device
  effects; reject submission in the initial transaction subset. Any later transactional GPU
  staging needs an explicit publication/rollback protocol.
- [Threading](threading.md) governs concurrent host access to queues/resources and result
  publication between CPU threads. Initially confine a command encoder/queue facade to one
  host context; later multi-producer recording needs explicit synchronization.
- Deterministic compile-time execution/generators cannot implicitly query or run a GPU.
  GPU availability must not affect the bootstrap fixpoint or silently alter `const` results.

## 10. Headroom to preserve now

- **GPU-18 [Proposed]** Preserve seams without adding a mandatory accelerator runtime:

| Existing direction | Cheap preservation step | Avoid promising |
|---|---|---|
| BC-01, VM-05 | Keep opcode semantics separate from CPU quickening and native pointer caches | A CPU register image can be copied straight to a GPU |
| BC-20, HR-01 | Keep required features/profile semantics versioned and reject unknown requirements | An old VM can ignore GPU annotations and execute equivalent code |
| HR-18, UNS-05 | Separate transfer layouts from host headers, structs and pointer values | GPU addresses have Reflect's host pointer ABI |
| GC-02, GC-03 | Use owned staging/resources and handles at asynchronous boundaries | Pinning alone makes a managed object DMA-safe or device-readable |
| JIT-01, JIT-08 | Keep target-specific lowering behind the backend boundary | x86-64 stencils or `Magic` stack intrinsics are GPU instructions |
| CON-02, HR-19 | Separate device completion and logical tasks from OS thread identity | GPU work requires a managed thread per submission |
| ECS-15 to ECS-18 | Retain checked effects, reference-free layouts and explicit leases | A CPU kernel annotation proves GPU safety or profitable offload |
| ARC-02, LIB-07 | Keep a reference backend and isolate optional platform integration | A vendor SDK is a new bootstrap prerequisite |

These are constraints for later decisions, not acceptance of new headroom entries or permission
to weaken existing rules. No keyword, numeric encoding or vendor identity needs reserving now.

## 11. Validation, proof and performance evidence

- **GPU-19 [Proposed]** Extend [testing](../testing.md) and the [formal model](../formal-model.md)
  before stabilization. Model typed device views, resource leases, image validation, task
  transitions/faults and the supported bytecode subset. Prove memory isolation and that device
  interpretation simulates the CPU reference task semantics, including required checks and
  prior effects on failure. Later shared-memory/barrier profiles need their own proofs.

Required cases include:

| Area | Evidence |
|---|---|
| Device requests | No hardware, backend/permission failure, unsupported required feature, reported preferences and explicit simulator selection |
| Resource lifetime | Cross-device/stale handles, overflow/alignment, aliasing, close during flight, DMA staging lifetime and no reuse after timeout/device loss |
| Commands/rendering | Invalid usages/transitions, transfer/compute ordering, cross-queue dependencies, bounded indirect parameters; render-to-texture and presentation lifecycle when supported |
| Profile verification | Unsupported opcode/helper/generic/layout, forged metadata, hidden allocation/host reference and backing-storage escapes |
| Interpreter semantics | Branches/calls, all admitted arithmetic/conversions, stack limits, bounds/fault positions, per-task isolation, budgets, resume and cancellation |
| Floating profiles | Required bit-level CPU/device comparisons for corner cases, subnormals, NaN, signed zero, checked operations and contraction |
| State and packaging | Batched tails, divergent lengths, immutable image sharing, rejected versions, cache invalidation and hot reload while tasks remain active |
| CPU integration | Moving-GC stress during transfers, retained layers, transaction rejection, explicit completion/error observation and host publication |

Use differential runs against a CPU implementation of the same profile, not unrestricted host
Reflect with different fault/ownership semantics. In the initial independent-task profile,
compare every task's output, status and fault location; task completion order need not match.
Mock/reference-backend tests are necessary but must not be reported as hardware-GPU passes.
GPU CI can be optional for CPU contributors; every claimed hardware/backend profile needs a
real-device conformance lane, with adapter and driver information recorded.

Benchmark three distinct modes where available: CPU reference/JIT, GPU bytecode interpreter,
and directly compiled GPU kernel. Include a parallel CPU baseline once host threads exist.
Separate cold packaging/compilation/upload time from warm resident execution, then also report
end-to-end time including transfers/readback. Measure throughput, latency, peak device bytes,
state bytes per task, instruction counts, divergence/occupancy and spill/transfer costs.

Vary batch size, code sharing, task duration/divergence and private-state size; include cases
where the GPU loses. For disjoint integer tasks, verify all `N` task results and statuses against
the reference, not a checksum or one sample. Preallocated repeated dispatch must not grow retained
host/device memory; measure transient allocations separately. Do not invent a speedup threshold
from GVM's results or describe profiling heuristics as guaranteed performance.

## 12. Delivery and open decisions

- **GPU-20 [Proposed]** Develop the two tracks independently, outside the self-hosting path:

1. **Specify/reference:** settle the capability/resource/command contracts, transfer ABI,
   integer guest subset and task-fault protocol. Build a CPU reference backend and image
   verifier with negative tests; preserve section 10's seams meanwhile.
2. **Platform gate:** choose the initial hardware/API backend and explicitly decide the
   driver/FFI dependency policy. Implement owned buffers, transfer, bounded compute dispatch
   and completion; demonstrate actual device execution, not just simulation.
3. **Access track:** add image/graphics/off-screen rendering and optional presentation as
   capabilities, with lifetime/dependency tests. This does not wait for a guest object heap
   or a full device interpreter.
4. **Interpreter track:** add the device-lowering path, compile the Reflect-G interpreter,
   and execute integer/buffer guest images entirely on device between launch and completion.
   Validate budgets/faults/isolation and benchmark suitable batched tasks.
5. **Optional extensions:** strict floating point, richer guest objects/arenas, cooperative
   profiles/device atomics, direct Reflect-G AOT kernels, indirect commands and ECS integration.
   Certify each supported profile rather than declare arbitrary Reflect GPU-compatible.

After the common platform gate, access/rendering and interpreter work can proceed separately.
Register experimental features under TST-02/TST-06; no stable GPU feature is implied until its
semantics, implementation, real-device validation and proof obligations are met. Do not move
CPU threads or async earlier simply to implement device submission.

**[Open]** Backend and dependency exception; exact module/API names; package placement/versioning;
device-lowering target and interpreter ABI; transfer layout rules; safe resource-robustness
requirements; supported numeric profiles; state/frame limits and budget policy; lane/task
scheduling; presentation integration; and later object/exception/GC profiles. These are explicit
delivery gates, not unspecified behavior allowed in a preview.

The central commitment is to keep access generic and execution honest: request only capabilities
the device provides, own data across asynchronous boundaries, and run a clearly specified
Reflect-G interpreter on the GPU rather than label CPU work or native kernel compilation as
GPU bytecode interpretation.

## 13. Prior art and specifications

- Celik, Nie, Rossbach and Gligoric. [GVM, OOPSLA 2019](https://doi.org/10.1145/3360603);
  [author-hosted full paper](https://pengyunie.com/files/CelikETAL19GVM.pdf).
  Sections 3 and 4 describe host packaging, device state and interpreter decisions; section 7
  evaluates the workloads; section 8 records limitations and future work.
- [Khronos SPIR-V registry](https://registry.khronos.org/SPIR-V/): device intermediate language
  for shader stages and compute kernels, not a GPU implementation of Reflect's VM.
- [Vulkan fundamentals](https://docs.vulkan.org/spec/latest/chapters/fundamentals.html):
  explicit host/device responsibilities and synchronization requirements. A design reference,
  not a selected backend or a promise that a wrapper removes driver dependencies.
