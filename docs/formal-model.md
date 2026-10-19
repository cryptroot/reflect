# Formal model

Featherweight Reflect (FR), a family of core calculi in Lean 4 that states and proves the
soundness of Reflect's rules, plus a model of the bytecode verifier. Lean is a verification tool
only; building Reflect never needs it (ARC-02). Conventions: [architecture.md](architecture.md) §0.

## 1. Role: the v1.0 gate

- **LEAN-05 [Decided, Q31, Q33, Q34]** The model gates v1.0: drafts (v0.x) may implement and use
  rules before their proofs, but v1.0 contains only rules whose layer is proven, and anything
  unproven stays experimental or is cut. If a hard layer lags, v1.0 ships a smaller provable
  subset: for FR-ref, `ref`/`in`/`out` parameters and ref structs without `ref` returns, `ref`
  fields or `scoped`; for FR-wild, wildcards limited to a proven fragment. Each feature records its
  layer and proof status (TST-02, TST-06).
- **LEAN-06 [Proposed]** "Proven" means: the layer's theorems compile in `formal/` without `sorry`
  or new axioms, and the layer's definitions have been reviewed against the rule IDs they cite.

## 2. Layers

- **LEAN-01 [Proposed]** Each layer is a set of Lean modules with its own soundness theorems, in
  the style of Featherweight Java and GJ (Igarashi, Pierce, Wadler):

  | Layer | Adds | Main theorems | Rules covered |
  |---|---|---|---|
  | FR-core | classes, fields, methods, non-virtual default, `virtual`/`override`, no hiding, casts | progress, preservation | MEM-02, TYP-02, TYP-07 |
  | FR-null | `T` and `T?`, `null`, flow narrowing, run-time null checks | soundness; a non-null-typed value is never null outside the warned construction holes | NUL-01 to NUL-06 |
  | FR-static | receiver classes, static virtual members, per-class storage, virtual constructors | soundness; `AbstractMemberError` only from forms the checker cannot see | SVD-01 to SVD-10 |
  | FR-generic | reified type arguments, type contexts, `instanceof T`, `new T(...)`, per-instantiation statics | soundness; run-time tests agree with static types | GEN-01 to GEN-11 |
  | FR-wild | use-site wildcards under the four restrictions | termination and soundness of the subtyping algorithm | GEN-04, GEN-05 |
  | FR-exc | exceptions, checked-exception analysis | no undeclared checked exception escapes | EXC-01 to EXC-04 |
  | FR-ref | structs, `ref`, `scoped`, ref structs, `mutating` | no dangling references; as far as I know C#'s rules have no published mechanization, so this layer is research | DAT-01 to DAT-03 |
  | FR-bytecode | a core of the instruction set and the stack-map verifier (Jinja, Klein and Nipkow) | verified code never gets stuck | BC-07 |
  | FR-compile | compiling FR-core to FR-bytecode | semantics preservation | CMP pipeline |

- **LEAN-07 [Proposed]** Order: FR-core, FR-null and FR-static first (the novel parts), then
  FR-generic and FR-exc, FR-bytecode before the v0.7 verifier ships, FR-wild and FR-ref last.

## 3. FR-core as scaffolded

`formal/Reflect/Core/` compiles with Lean 4.35.0 (`lake build`); the two main theorems are
stated with `sorry`, as gate obligations.

| Module | Contents |
|---|---|
| `Syntax.lean` | `Ty` (class types), `Expr` (`var`, `field`, `callVirt`, `callDirect`, `new`, `cast`), `MethodDecl` (with `isVirtual`, `isOverride`), `ClassDecl`, `ClassTable`; the relations `Subclass`, `Fields` (inherited first) and `MethodIn` (own declaration, else inherited) |
| `Typing.lean` | `IsSubtype`; mutual `HasType` and `ArgsTyped` with subsumption; `OverrideOk` (an inherited name must be overridden with `override`, the inherited method virtual, the signature identical; `override` needs an inherited method); `MethodOk`; `WellFormed` (built-in `Object`, distinct names, acyclic inheritance, defined superclasses, well-typed methods, distinct field and method names) |
| `Semantics.lean` | `IsValue`, `AllValues`; substitution; small-step `Step` and `StepAll` (call by value, left to right; virtual calls use the run-time class, direct calls the named class); `FailedCast` for casts that fail |
| `Soundness.lean` | proven: `Subclass.trans`, `IsSubtype.refl`, `IsSubtype.trans`; obligations: `preservation`, `progress` |

Simplifications in FR-core: one method per name (no overloading), calls already elaborated into
virtual and direct forms, `Object` built in and empty, casts stuck instead of throwing.

```lean
theorem progress {ct : ClassTable} (_wf : WellFormed ct) {e : Expr} {T : Ty}
    (_ht : HasType ct [] e T) : IsValue e ∨ (∃ e', Step ct e e') ∨ FailedCast ct e
```

## 4. The next layers

- **FR-null.** Types gain `nullable`; `null` is a value; field access and calls on `null` step to
  an NPE state. Narrowing is modeled as a typing rule on `if (x != null)` that types `x` as
  non-null in the branch; the construction holes of NUL-04 appear as an explicit "uninitialized
  field" state whose reads yield `null`. Theorem: in a well-typed program, a non-null-typed
  expression never evaluates to `null` except by reading such a field (the warning cases).
- **FR-static.** Class declarations gain static fields and methods with `virtual`/`override`;
  expressions gain `C.m`, `e.m`, `k.m` and the receiver `R` of every static frame (SVD-02).
  Dispatch looks up from `R`; storage follows SVD-04. Theorem: soundness, with
  `AbstractMemberError` reachable only through class values or forwarded receivers whose class
  leaves an abstract unimplemented (SVD-05).
- **FR-generic.** FGJ plus reified arguments: run-time values carry exact instantiations, and
  `instanceof T` and `new T(...)` read the type context. Theorem: run-time type tests agree with
  static types after substitution of the type context.
- **FR-wild.** The restrictions of Tate, Leung and Lerner, transcribed from the paper with their
  numbering, and the algorithmic subtyping relation of the compiler and VM; theorem: the algorithm
  terminates on restricted programs and agrees with declarative subtyping.
- **FR-exc, FR-ref, FR-bytecode, FR-compile** follow as listed in §2; FR-bytecode follows Jinja's
  structure (semantics, type-checking verifier with stack maps, soundness).

## 5. Executable oracle

- **LEAN-03 [Proposed]** Each layer's checker and evaluator get executable counterparts (Lean
  functions proven equivalent to the relations) compiled into a native test oracle. Random
  well-typed programs are printed as Reflect source; the oracle's results must match `rflc` plus
  `rfl` (TST-11).

## 6. Project layout

- **LEAN-04 [Decided, Q44]** `formal/` is a Lake package built with core Lean only, no Batteries
  or Mathlib until a proof needs them:

  ```text
  formal/lean-toolchain        leanprover/lean4:v4.35.0
  formal/lakefile.toml         package reflect_formal, library Reflect
  formal/Reflect.lean          imports every layer
  formal/Reflect/Core/         FR-core (§3)
  formal/Reflect/Null/ ...     one directory per later layer
  ```

  `lake build` runs in its own CI job (TST-05); a `sorry` is a warning, and the release check
  counts the obligations that remain (TST-06).
  Run `sh docker/dev.sh lean` on Linux or `docker/dev.ps1 lean` on Windows. The build image
  pins Lean and the release archive's SHA-256, without elan, and checks `lean-toolchain`
  before building. The Dev Container uses that same toolchain for editing; no host Lean
  installation is needed. See [v0.0.x/README.md](v0.0.x/README.md) §5.
