# Roadmap

Milestones from v0.0.1 to the self-maintaining v1.0.0 (proposal item 1: every version before
v1.0.0 is a draft). Conventions: [architecture.md](architecture.md) §0.

- **RM-01 [Decided, Q32]** The first binary release is v0.6, as soon as `rfl` runs `rflc`; Java
  leaves the repository with it (BOOT-07, BOOT-15).
- **RM-02 [Decided, Q33, Q34]** v1.0 contains only rules whose Featherweight Reflect layer is
  proven; v0.9 shrinks or cuts what is not (LEAN-05).
- **RM-03 [Proposed]** The milestones below; a milestone is done when its exit test passes in CI.

| Version | Content | Exit test |
|---|---|---|
| v0.0.x | these documents; the `.rflc` format and `reflect.bytecode` design; assembler and VM0 in stage0 | an `.rfla` program round-trips and runs on VM0 |
| v0.1 | stage0 compiles R0 (BOOT-02); core stdlib with Java natives; conformance runner | the R0 conformance suite passes on VM0 |
| v0.2 | transliterator (BOOT-12); `compiler/` in Reflect; `rflc` build 1 made by stage0 | build 1 passes the R0 suite |
| v0.3 | self-hosting on VM0; transliteration stops; stage0 frozen | fixpoint (build 2 equals build 3) in CI |
| v0.4 | static virtual members, virtual constructors, operators, structs and ref safety, events, indexers, tuples, extension methods, varargs, intersections, record patterns | the full conformance suite on VM0 |
| v0.5 | annotations with generated classes, run-time reflection, generators, `const` evaluation, analyzers | ex §5, §6 and §10.1 to §10.3 run as tests (TST-12) |
| v0.6 | x86-64 backend, optimizing compiler and AOT (JIT-08); `rfl` in Reflect-S with the interpreter and the mark-sweep collector; proxies and emit behind `unsafe;`; first binary release and Docker images; stage0 deleted | `rflc` on `rfl` matches `rflc` on VM0 byte for byte; v0.7 builds from the v0.6 release alone |
| v0.7 | verifier (BC-07); proxies and emit no longer need `unsafe;`; hot reload | verifier suite; FR-bytecode proven |
| v0.8 | baseline JIT (copy and patch), `@Precompile` | differential tests across tiers (TST-08) |
| v0.9 | mixed-mode application AOT (Q43); performance; remaining proofs; unproven features shrunk or cut | every stable feature has its proof (TST-06) |
| v1.0 | the self-maintaining release of proven rules | built only from v0.9's binaries |

## Proof track

| By | Layers proven |
|---|---|
| v0.4 | FR-core, FR-null |
| v0.5 | FR-static, FR-generic |
| v0.7 | FR-exc, FR-bytecode |
| v0.9 | FR-wild, FR-ref, or the smaller subsets that ship instead (Q34) |

## After v1.0

Threads with `Lockable` and the memory model (CON-02, CON-04), the optimizing JIT tier (JIT-15),
Immix and generational collection (memory.md §9), closed-world AOT (JIT-14), C library calls
with Reflect's own dynamic loader (UNS-04), iterators if they did not make v1.0 (STM-06),
`async`/`await` (CON-03), custom operators (OPR-08), `Borrow` types (Q05), layer unloading if it
did not make v1.0 (VM-15).
