# Memory management

How `rfl` allocates and reclaims memory: the first collector, the hooks that let later collectors
move objects, finalization, weak references and destructible objects. Object layout:
[vm.md](vm.md) §3. Conventions: [architecture.md](architecture.md) §0.

- **GC-01 [Decided, Q05, Q06]** Memory safety comes from tracing collection plus C# 11 ref safety
  (DAT-03); `Borrow` types are evaluated after v1.0. The first collector is non-moving,
  stop-the-world mark-sweep, simple and proven; later collectors must fit without changing
  compiled code or the bytecode (§5, §9).

## 1. Heap organization and allocation

- **GC-09 [Proposed]** The heap is a set of 64 KiB pages aligned to their size, obtained with
  `mmap` (ex §11.4 `Pages.rfl`):
  - a small-object page holds slots of one size class; classes step by 16 bytes up to 256 bytes,
    then by about 25% up to 8 KiB;
  - an object larger than 8 KiB gets its own run of pages in the large-object space;
  - each page starts with a header (size class, free count, free-list head, page flags), and a
    page table maps any address to its page header.
- **GC-10 [Proposed]** Allocation goes through an allocation context (one while single-threaded):
  for a size class it pops the current page's free list, or bumps through a fresh page; when the
  page is exhausted it takes a page with free slots, then an empty page, then maps a new one. When
  usage passes a threshold proportional to the live size after the last collection, the next
  allocation collects first. Objects are zeroed before use.

## 2. What the collector reads

- Every object starts with its `Class<T>` pointer, which gives its size and how many references
  it starts with (references come first, vm.md §3); arrays add their length and element kind.
- Header bits used now: mark, finalizable, finalized; the destroyed bits are not the collector's.
  Bits reserved for later: age, forwarded, pinned, remembered (§5).

## 3. The first collector

- **GC-11 [Proposed]** Mark-sweep, stop-the-world:
  1. **Mark** from the roots (§4) with an explicit mark stack: set the header mark bit, push the
     references of each newly marked object; `Class<T>` objects mark their statics and metadata
     references.
  2. **Weak processing** (§7): clear weak references to unmarked objects; settle ephemeron tables.
  3. **Finalization** (§6): mark every unmarked finalizable object and what it reaches, and queue
     it.
  4. **Sweep** lazily: a page is swept when allocation next needs its size class, rebuilding its
     free list and clearing marks; empty pages return to the page pool; unmarked large objects are
     unmapped at once.
- **GC-12 [Proposed]** Interior pointers (`ref` into objects or arrays, DAT-03) are roots like any
  reference: the page table gives the page, and
  `objectStart = firstSlot + ((address - firstSlot) / slotSize) * slotSize`; a large object starts
  at its first page.

## 4. Roots, stack maps and safepoints

- **GC-03 [Proposed]** Roots: interpreted frames (registers typed as references, vm.md §6),
  compiled frames (maps recorded by the compiler at every safepoint), statics of initialized
  types, VM handles, interned strings, the finalization queue, `Class<T>` objects and constants of
  compiled code.
- **GC-13 [Proposed]** Safepoints are allocation sites, calls, loop back-edges and method entries.
  While single-threaded, a collection starts only at an allocation or `GC.collect()`, but the polls
  at back-edges and entries exist from day one (CON-02) so threads and incremental collection fit
  later. `@Uninterruptible` VM code has no safepoints and therefore needs no maps (BOOT-08).

## 5. Hooks for later collectors

- **GC-02 [Decided, Q06]** Present from day one, even where the first collector ignores them:
  1. **Precise roots only**; no conservative stack scanning.
  2. **A write barrier on every reference store** (`stfld`, `stsfld`, `stelem`, struct copies
     containing references, stores through `ref`; BC-14), called with the object and the address
     of the slot, so a later barrier can read the old value (snapshot marking) or mark a card
     (generations). The first collector's barrier does nothing; the JIT inlines it.
  3. **Identity hashes in the header**, never derived from addresses.
  4. **Handles**: VM code that keeps a managed reference across a possible safepoint stores it in
     a handle the collector can update; the `@Uninterruptible` analyzer enforces it.
  5. **Interior pointers** reported as such (GC-12), so a moving collector can adjust them.
  6. **Pinning** through `fixed` (UNS-06), free while nothing moves.
  7. **Safepoint polls** (GC-13).
  8. **Allocation contexts** (GC-10), so bump allocation in Immix blocks can replace free lists
     without touching callers.
  9. **Reserved header bits** for age, forwarding, pinning and remembered state.

## 6. Finalization

- **GC-04 [Decided, Q29]** Objects of classes with a finalizer `~C()` are flagged at allocation
  (ex §7.5). When marking finds a flagged object unreachable, it is kept alive with everything it
  reaches and queued in allocation order. Finalizers run only at defined points, never inside
  arbitrary code:
  - `GC.runPendingFinalizers()`;
  - right after every `GC.collect()`;
  - at idle points the program declares with `Runtime.idle()` (an event loop calls it between
    events);
  - at the end of `main`, after one full collection.

  `GC.suppressFinalize(obj)` clears the flag; a finalizer runs at most once; an object resurrected
  by its finalizer is collected normally later. Exceptions thrown by a finalizer are reported to
  stderr and do not stop the drain.

## 7. Weak references

- **GC-05 [Decided, Q23]** `reflect.core` provides `WeakReference<T>`, cleared when its referent is
  only weakly reachable, before finalization runs (Java's order), and a weak-keyed table with
  ephemeron semantics: a value stays alive only while its key is reachable, even when the value
  refers back to the key. Marking settles ephemerons by iterating to a fixpoint. The VM uses them
  for hot-reload field storage (vm.md §11), reflection caches and interned strings of unloadable
  layers; the library builds the weak-listener wrapper on them (MEM-16).

## 8. Destructible objects

- **GC-06 [Decided]** A destroyed object is an ordinary object to the collector; only its header
  bits differ (DAT-08). A `Destructible` class that owns native resources releases them in
  `onDestroy()` and keeps a finalizer as a safety net (ex §9.6).

## 9. Later collectors

| Step | Change | Unchanged |
|---|---|---|
| Immix (Blackburn and McKinley) | mark-region heap of blocks and lines, bump allocation into free lines, opportunistic evacuation of fragmented blocks, pinned objects stay put | bytecode, compiled code, barrier call sites, roots |
| Generational | a nursery (sticky mark bits or copying), card marking in the barrier body | the same |
| Concurrent or incremental marking | snapshot-at-the-beginning barrier body, mutator threads (after v1.0) | the same |

## 10. Configuration, diagnostics and testing

- **GC-07 [Proposed]** `--heap-max` and `--heap-initial`, `GC.collect()` for a full collection,
  `GC.stats` (collections, pause times, live and committed bytes), and `OutOfMemoryError` when a
  full collection cannot satisfy an allocation.
- **GC-08 [Proposed]** Test modes: collect at every allocation or every safepoint, verify the heap
  after each collection (every reference points at a live object start or a valid interior
  address), and poison freed slots.
