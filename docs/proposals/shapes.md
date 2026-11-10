# Proposal: Reified Structural Shapes

Introduce `shape`, a VM-known type kind for viewing a value through an expected set of members.
Like a TypeScript interface, a shape accepts a class or struct with matching public members
without requiring an `implements` declaration. Unlike a TypeScript interface, a Reflect shape is
reified, survives separate compilation and has VM-checked dispatch.

A shape aggregates **structural requirements**, not nominal interface membership. It guarantees
the operations available through the view, not that the view itself is a class, a struct or an
instance of one of its source interfaces. Conversion into a shape narrows the available surface;
it does not establish a conversion back to a concrete type.

This feature is not implemented or assigned a release. All rules here are **[Proposed]** unless
marked **[Open]**. They do not amend the baseline [grammar](../grammar.md),
[type system](../type-system.md), [module format](../bytecode.md) or
[proof layers](../formal-model.md). Conventions: [architecture.md](../architecture.md) section 0.
This proposal does not expand R0 or the self-hosting requirements.

## 1. The contract

- **SHP-01 [Proposed]** A shape is a named, reified structural view. Its contract is the
  aggregate of its declared requirements and the public instance requirements projected from
  its source interfaces or shapes. A matching value needs no nominal relationship to those
  sources. A shape has no constructors, instance storage, implementation bodies or independently
  constructible instances; it always views an existing reference or a boxed ordinary value.

| Type | What a value guarantees |
|---|---|
| Interface `I` | The ordinary nominal implementation relationship to `I`, including its dispatch rules |
| Intersection `I & J` | Both existing type relationships; it does not structurally invent either one |
| Shape aggregating `I` and `J` | Their projected member requirements; neither nominal relationship follows |

This is not dynamic name lookup, object construction, a property bag, a conversion operator or a
way to make a foreign value adopt an interface's default implementation. Signature conformance
alone does not prove a semantic protocol, purity, thread safety or an interface's marker meaning.

## 2. Declarations and examples

- **SHP-02 [Proposed]** Declare a shape wherever a type declaration is allowed. Requirements
  are implicitly public instance members. Ordinary visibility, namespaces and generic shape
  parameters follow the corresponding type-declaration rules.

```reflect
public shape FunctionA {
    void functionA(int value);
}

public class Worker {
    public void functionA(int value) { }
}

public struct WorkerValue {
    public void functionA(int value) { }
}

public class Example {
    public static void call(FunctionA value) {
        value.functionA(42);
    }

    public static void run() {
        call(new Worker());       // structural view of the same object
        call(new WorkerValue());  // structural view of a boxed value copy
    }
}
```

`Worker.functionA` is an ordinary non-virtual method. It neither needs nor permits `override`
merely because it matches a shape. Nominal interface implementations still follow MEM-02.

An aggregate can reuse interfaces and add requirements:

```reflect
public interface HasFunctionA {
    void functionA(int value);
}

public interface HasLabel {
    String label { get; }
}

public shape LabeledAction extends HasFunctionA, HasLabel {
    int priority { get; }
}

public shape Source<T> {
    T read();
}
```

`extends` here aggregates requirements; it does not add nominal supertypes. Classes and structs
do not write `implements LabeledAction`, and interfaces cannot extend a shape.

- **SHP-03 [Proposed]** Make `shape` a contextual keyword in shape declarations and kind
  bounds. Ordinary identifiers named `shape` keep their meaning outside those positions.
  The exact ambiguity policy needs the source-compatibility decision recorded in
  [the meta-analysis](meta-analysis.md) as MA-17 before reservation or parser changes land.
  Grammar sketch, not an amendment to the baseline grammar:

```text
TypeDecl      = ... | Annotation* Visibility? ShapeDecl ;
ShapeDecl     = "shape" Ident TypeParams? ( "extends" Types )? Where? "{" ShapeMember* "}" ;
ShapeMember   = Annotation* ( "mutating"? ShapeMethod | ShapeProperty | ShapeField ) ;
ShapeMethod   = Type Ident TypeParams? "(" Params? ")" Throws? Where? ";" ;
ShapeProperty = Type Ident "{" ShapeAccessor+ "}" ;
ShapeAccessor = "mutating"? ( "get" | "set" ) Throws? ";" ;
ShapeField    = "final"? Type Ident ";" ;
Bound         = "class" | "struct" | "shape" | Type ;
```

Only non-null interface or shape types may appear as aggregation sources, with explicit,
well-formed type arguments. Aggregation cycles are errors. No member bodies, field initializers,
statics, constructors, operators, events, indexers or nested declarations are included in this
first subset. A field-like requirement is access syntax, not a demand for storage: `int count;`
means `int count { get; set; }`, and `final int count;` means `int count { get; }`. The latter
does not promise that the underlying value never changes.

## 3. Aggregation is structural, not nominal

- **SHP-04 [Proposed]** Project the public instance methods and properties of each source,
  inherited ones included. A default method contributes its signature, not its body: a candidate
  must already provide a callable implementation, directly or through its existing inheritance.
  Private helpers, statics and constants contribute no instance requirements. An unsupported
  public instance member produces a declaration diagnostic rather than silently disappearing.

An empty or marker interface therefore contributes no operations and no marker guarantee.
Aggregating a sealed interface does not confer membership of its permitted hierarchy. A candidate
may happen to implement a source interface, but the shape does not record that as a promise to
callers. Nominal interface tests and casts retain their existing meaning.

The initial contract is about signatures and receiver mutability. If the
[Intent Contracts proposal](intent-contracts-and-transactions.md) is adopted, projection of a
contract-bearing interface must be rejected until its enforcement semantics are specified;
matching signatures must not silently discharge inherited preconditions or postconditions.
Contracts on an actual target method still run through that method's normal entry and exit paths.

- **SHP-05 [Proposed]** Normalize field-like requirements to property access requirements.
  Identical method requirements coalesce; distinct parameter lists remain overloads.
  Same-name properties of the same type combine their required accessors. Duplicate accessor
  signatures must agree. Conflicting member kinds, return types, generic bounds, receiver
  mutability or `throws` declarations are declaration errors, not an invitation to synthesize a
  common type or choose one source's contract. Parameter names are not part of a signature.

Two declarations with identical requirements remain distinct reified types. They can support
structural view conversions in both directions without becoming aliases or making their array
and generic instantiations identical.

## 4. Matching a candidate

- **SHP-06 [Proposed]** Match against the candidate's effective public instance surface, after
  ordinary inheritance and override resolution. Each requirement must have an unambiguous,
  accessible binding. Private, protected and package-private members, extension methods,
  user-defined conversions and similarly named statics cannot satisfy it. Normal module-access
  checks remain in force; `opens` is not a request to match private members.

| Requirement | Initial matching rule |
|---|---|
| Method | Same name, generic arity and alpha-equivalent generic constraints; exact parameter types, parameter modes and variadic flags |
| Result | Exact reified return type, including nullability and type arguments; no implicit result adapter |
| Checked exceptions | Each checked exception of the implementation must be permitted by the requirement's `throws` clause |
| Get-only property | A public getter or readable public field of exactly the required type |
| Settable property | Every requested public accessor, or an appropriately readable/writable public field, of exactly the required type |
| Read-only struct receiver | A non-`mutating` implementation; a requirement marked `mutating` may also use a read-only implementation |

Methods and accessors preserve the usual checked-exception analysis at calls. A field binding
throws no checked exceptions; a getter or setter must fit the corresponding accessor's `throws`
clause. A `final` field cannot satisfy a setter. Property access remains an accessor call, not a
variable usable with `ref` or `out`.

Matching never applies numeric widening, boxing of a result, parameter contravariance or return
covariance to repair a signature. This intentionally starts more conservatively than nominal
override bridges (MEM-02). Comparing member types uses their exact identities, including any
nested shapes, rather than recursively guessing new structural member-type relationships.
Extra candidate members are harmless. Binding examines metadata and runs no getters or methods.

Existing nominal dispatch slots remain part of that surface, including their already verified
override bridges. A matching original interface/base signature may bind through such a slot;
the matcher does not synthesize a new bridge. Thus conformance established from an interface
expression remains valid for its receiver even when an implementation has a covariant return.

## 5. Narrowing-only conversions

- **SHP-07 [Proposed]** Structural conformance is a separate relation from nominal subtyping.
  Write `C satisfies S` when the statically available members of `C` meet shape `S`. This permits
  creation of a shape view; it does not insert `C <: S <: Object` into TYP-02.

| Source and target | Implicit behavior |
|---|---|
| Matching class or interface expression to `S` | Form a view preserving the receiver |
| Matching ordinary struct to `S` | Box a copy under section 7, then form a view |
| Shape `S` to shape `T` | Project the view when every requirement of `T` is already guaranteed by `S` |
| Shape to a concrete class, struct or nominal interface | Rejected, even if a source interface was aggregated |
| Shape to `Object` or `Object?` | Rejected; ordinary erasure must not provide a back door around checked recovery |
| Nullable source to non-null shape | No implicit conversion; use ordinary null narrowing or an explicit checked shape cast |

The same rules apply to arguments, assignments, returns and member initialization. Overload
resolution uses the static source type, never speculative matching of its future run-time value.
For a reference source, ordinary identity and nominal conversions rank before shape formation;
shape formation ranks before boxing. Struct-to-shape formation is a boxing conversion and gains
no ranking advantage over other boxing targets. Between shape targets, a strict superset of
compatible requirements is more specific; incomparable targets are ambiguous. Existing calls
with no shape candidate keep TYP-04's behavior. Inference still never invents a shape (TYP-05).

- **SHP-08 [Proposed]** `(S) value` and `value instanceof S s` can request a checked shape view
  when the static source does not prove conformance, including from `Object` or another shape.
  The VM validates the actual receiver and constructs the required bindings. A nonconforming
  cast throws `ClassCastException`; a type test returns false. Neither operation constructs a
  nominal instance or runs a user conversion. A successful pattern binds `s` as the shape, not
  as the receiver's concrete type.

An implicit shape-to-shape projection can only remove exposed requirements. An explicit checked
cast may demand additional requirements, but it must validate them against the actual receiver.
It still grants no nominal type relationship.

## 6. Explicit checked recovery

- **SHP-09 [Proposed]** Reject direct shape-to-nominal assignments and casts. Provide an
  explicitly named helper that recovers the receiver and delegates to the existing nominal cast
  or unboxing operation. Suggested library surface in `reflect.meta`, shown as a signature:

```reflect
public final class Shapes {
    public static T classCast<T, S extends shape>(Class<T> target, S? value);
}
```

Both type arguments can be inferred from `Shapes.classCast(Worker.class, action)`. The target
`Class<T>` is an ordinary nominal/value-type mirror, not a shape descriptor.

```reflect
// Given: FunctionA action = new Worker();
Worker first = action;                         // error: no nominal conversion
Worker second = (Worker) action;                // error: use checked recovery
HasFunctionA nominal = action;                 // error: aggregation is not implements
Object erased = (Object) action;                // error: no ordinary erasure escape

Worker recovered = Shapes.classCast(Worker.class, action); // same Worker object
HasFunctionA checked = Shapes.classCast(HasFunctionA.class, action);
// The last call throws ClassCastException: Worker never implemented HasFunctionA.
```

Recovery checks the actual class, nominal interface relationship or boxed value type, not
whether the requested target happens to have the same members. A different class with
`functionA(int)` is not a valid recovery target. Struct recovery unboxes a copy of the boxed
payload; it neither borrows the original variable nor constructs a new struct from matching
fields. No user-defined conversion runs.

The helper may target `Object` or a nominal interface where the ordinary cast permits it:
the escape is intentional and explicit. Null follows the target's existing cast rules: null to
a non-null target throws `NullPointerException`; null to a nullable target succeeds. A null
target descriptor throws `NullPointerException`. There is no failure-shaped `null` fallback
for a bad cast.

## 7. Structs, boxing and mutation

- **SHP-10 [Proposed]** An ordinary shape value has reference-view semantics. Converting an
  unboxed struct boxes a value copy; copying the resulting view shares that box, not the
  original struct variable. Existing immutable boxes retain DAT-09's content equality and
  lack of identity. Shapes never silently borrow stack storage. `ref struct`, pointers and
  `ref`/`in`/`out` locations themselves cannot be captured as ordinary shape receivers.

- **SHP-11 [Proposed]** Preserve the existing box-mutable classification: only the nominal
  `mutating` interface implementation rule of DAT-09 and VM-13 marks a struct box-mutable.
  Merely declaring a shape or discovering structural conformance never changes that flag.
  A binding that mutates a struct receiver, writes one of its fields or exposes a setter through
  the view requires an already box-mutable type. Otherwise the struct does not satisfy that
  shape, even if the member names and types match.

Read-only calls can use immutable boxes. An already box-mutable receiver can satisfy a
`mutating` shape requirement and updates its box in place, never the source variable.
A class satisfies such a requirement with an ordinary instance member. `mutating` describes
permission to change a struct receiver, not purity or the absence of effects on referenced data.

```reflect
public interface Incrementable {
    mutating void increment();
}

public shape CounterView extends Incrementable {
    int count { get; }
}

public struct Counter implements Incrementable {
    public int count;
    public override mutating void increment() { count++; }
}

// Inside a method:
Counter original = new Counter();
CounterView view = original;
view.increment();
// original.count is 0; view.count is 1.
Counter snapshot = Shapes.classCast(Counter.class, view); // copies count == 1
```

Removing `implements Incrementable` and `override` leaves a structurally matching mutating
method, but makes this conversion invalid: an immutable box must not acquire mutation through a
shape. A scoped, non-boxing shape over a writable location would be a different feature.

## 8. Nullability, storage and generics

- **SHP-12 [Proposed]** A shape is its own type kind, neither `class` nor `struct`. Non-null
  `S` and nullable `S?` are distinct reified types. A nullable view carries either null or the
  same receiver and witness as `S`; it is not a nullable-struct wrapper. Normal null narrowing,
  `?.`, `??` and null-cast checks apply. `instanceof` is false for null, including against `S?`.
  `S` converts implicitly to `S?`, and null converts to `S?`. A permitted receiver-to-shape or
  shape-projection conversion lifts to a nullable target: a null input remains null; a non-null
  input forms the ordinary view. Lifting never permits null to become a non-null shape.

  Views can be locals, parameters, results, fields and array elements. There is no `new S()` or
  `default(S)`; `default(S?)` is null and `new S?[n]` is valid. `new S[n]` is rejected; use an
  array initializer or `Array.filled` for non-null elements. Array invariance is unchanged:
  `Worker[]` is not `FunctionA[]`, and two distinct
shape declarations do not produce interchangeable array types.

Shape `==` and `!=` compare the underlying receivers, ignoring the witness and declared shape
identity, with DAT-09's existing distinction between class identity, immutable boxes and
box-mutable identity. They accept shape operands and null, not an implicit `Object` recovery.
Object members such as `getClass`, `equals` and `toString` are not automatically in a shape's
callable surface; a method must be part of its declared requirements to be callable.

- **SHP-13 [Proposed]** Shapes can have reified type parameters. For storage in a generic type,
  add the non-null kind bound `T extends shape`; it admits shape types only, not a matching class
  or struct. `T extends shape & FunctionA` additionally requires a shape that guarantees
  `FunctionA`'s requirements. A caller must first form a view rather than infer a nominal
  receiver type into this kind bound. No mixed nominal/shape bound is included initially.

The existing implicit `Object?` bound and `class`/`struct` kind bounds are not widened. Thus an
ordinary `List<FunctionA>` with an `Object?`-bounded element parameter is not automatically
legal; an opt-in shape-kind container can store such views. This is a deliberate initial
restriction, not an implicit unwrap or a claim that existing generic libraries already support
shapes. A kind-polymorphic top bound is an open follow-up.

Shape-kind parameters permit `T?`, reified shape tests/casts and `T.class`, but not construction,
unboxing, class statics or concrete recovery without the helper. Type-argument equality and
ordinary generic invariance continue to use reified identity, not just requirement equivalence.

## 9. Relationship to intersections

- **SHP-14 [Open]** Shapes may eventually compose with intersections, but this proposal does
  not extend GEN-06. An aggregate is not an alias for an existing nominal intersection:
  a class without `implements HasFunctionA` can fit a shape projecting that interface but is
  not a member of an intersection containing `HasFunctionA`.

A future `S1 & S2` could combine two structural views; `S & I` would need both structural
conformance to `S` and genuine nominal membership of `I`. It must not infer the latter from
matching signatures. Admissible kinds, normalization, member conflicts, nullability, boxing,
generic bounds and termination need explicit rules and proofs before either form is accepted.
Named shape aggregation works independently of this extension.

## 10. Reification, metadata and execution

- **SHP-15 [Proposed]** Add a `SHAPE` definition kind to reflection and a `ShapeType` mirror
  implementing `reflect.meta.Type`, shared by compiler and VM phases. `S.class` for a shape,
  and `T.class` for a shape-kind parameter, returns this mirror rather than `Class<S>`.
  A shape reports `isValueType == false` without acquiring the `class` kind or an `Object`
  conversion.
  Nullable mirrors follow the existing nullable-type convention and retain the underlying
  shape requirements. A descriptor contains the declaration identity, exact type arguments,
  aggregation sources and normalized requirements. `Type.isAssignableFrom` accounts for
  structural view formation/projection, not just nominal inheritance.

Requirements need shape-owned descriptors, not fake `Method` or `Property` instances whose
`declaringClass` invents a class. A shape mirror has no constructor or underlying receiver
class. Inspecting a view's shape type does not recover its concrete receiver. Reflection must
observe the same access, signature and mutation checks as source calls.

The existing `Object?`-based `Method.invoke` transport cannot implicitly unwrap a shape argument
or return a receiver in place of a shape result. A typed invocation API or opaque shape carrier
is an explicit integration gate; unsupported shape-valued invocation must be rejected before
executing the target, not return a success-shaped substitute. Metadata inspection and ordinary
typed calls do not depend on resolving this transport question.

- **SHP-16 [Proposed]** Introduce versioned critical metadata for the shape kind, its source
  types, normalized requirements and shape-valued signatures. Preserve parameter modes,
  generic constraints, `throws`, accessor availability and receiver-mutability requirements.
  Reified identity includes the defining module/layer and type arguments, not just a signature
  hash. The reader, writer, assembler, disassembler, emit and verifier must agree on canonical
  ordering and identities. Exact table/flag/opcode allocations remain open; no numbers are
  reserved here. Older readers reject the extension under BC-20.

- **SHP-17 [Proposed]** A view logically carries a receiver plus a VM-validated witness mapping
  each requirement to an existing method/accessor dispatch target or permitted field operation.
  This is VM-known state, not an ordinary object whose user code promises the right names.
  Binding a non-virtual member preserves direct-call behavior; binding a virtual member retains
  its ordinary overriding behavior. The witness cannot fabricate nominal interface membership
  or install a default body the receiver did not already have.

The verifier tracks shape-valued slots distinctly from ordinary object references and verifies
view formation, projection, checked casts, calls and helper recovery. Untrusted emitted bytecode
cannot forge a witness, erase a view to its receiver, bypass checked exceptions, write a final
field, mutate an immutable box or use a stack-only receiver. Loader/verification errors remain
explicit; a missing run-time binding is a failed cast/test, never a delayed missing-method call.

Field bindings use the ordinary field access path, including write barriers, final-field checks
and destruction guards. Method/accessor bindings retain initialization, destruction and any
contract guards; shape dispatch is not a way around normal method entry.

- **SHP-18 [Proposed]** The interpreter provides the complete semantics first. JIT/AOT may
  specialize witnesses, inline targets and remove redundant checks only while preserving the
  same results, exceptions, copy behavior and box identity. Shared generic code must retain
  exact shape/type contexts. Open-world AOT needs a matching/binding fallback for dynamically
  loaded receiver types, not only a list of statically discovered pairs.

Witnesses and positive or negative matching caches must respect module/layer lifetime and
access context. They cannot keep an otherwise unloadable child layer alive from a parent cache.
Hot reload invalidates affected bindings and dependent code before execution resumes; it must
not leave a view calling a stale target or ignoring a changed requirement.

## 11. Compatibility and rollout

- **SHP-19 [Proposed]** Keep the baseline nominal cast, interface and boxing rules unchanged.
  Accept shape syntax/metadata only when this experimental feature is enabled. Editor keyword
  support, parser reservations, reflection additions and format changes belong to adoption,
  not to this documentation-only proposal.

Adding or changing a shape requirement is a contract change and can break existing providers.
Shape identity is not structural deduplication across modules. Adding a public member to a
candidate may change a checked shape test from false to true; negative caches must not hide that
observable consequence of structural matching. Introducing shape overloads may introduce
ambiguities, but cannot change calls in code that has no shape candidates.

Implement in independently testable stages:

1. Specify the kind, exact matcher, access rules, helper and source compatibility; extend a
   Featherweight Reflect model with view formation and projection.
2. Add versioned metadata, reader/writer round trips, verifier states and interpreter witnesses
   for methods and read-only properties/fields.
3. Add existing box-mutable receivers, writes, generic shape kinds, reflection integration and
   reload/unloading tests. Full reflective invocation waits for its explicit transport design.
4. Validate JIT/AOT equivalence. General-purpose generic containers, member variance, borrowed
   shapes, events/indexers and intersections remain separate extensions.

No stage is a new bootstrap dependency or an assigned release. Resolve the generic/transport
boundary and any combined Intent Contract semantics before claiming full integration.

## 12. Conformance and proof obligations

- **SHP-20 [Proposed]** Add positive and negative source tests, bytecode/verifier tests and
  cross-tier tests before stabilization. These are acceptance criteria, not tests implemented
  by this draft:

| Case | Required result |
|---|---|
| Public `functionA(int)` with no `implements` | Fits `FunctionA`; ordinary non-virtual method remains non-virtual |
| Matching class and read-only struct passed to the same call | Both accepted; class receiver preserved, struct copied into a box |
| Aggregated interfaces, extra members and inherited implementations | Match the projected requirements; no nominal membership is invented |
| Default body available only on an unimplemented source interface | Does not supply a missing implementation |
| Private/static/extension-only member; wrong type, nullability or parameter mode | Rejected statically, or failed checked cast/test when only dynamically known |
| Conflicting aggregate requirements or cyclic aggregation | Declaration error with the conflicting origins |
| Read-only field/property, private setter and wider checked exceptions | Only the permitted accessors and exception contracts match |
| Shape projection to fewer requirements; cast demanding more | Projection succeeds; stronger cast validates the actual receiver |
| Direct shape-to-class/interface/struct/`Object` conversion | Rejected, including explicit casts and generic-erasure attempts |
| Helper recovery to the actual class or exact boxed struct | Original class reference or unboxed copy, respectively |
| Helper recovery to a merely similar class or unimplemented interface | `ClassCastException`, without running a user conversion |
| Immutable versus already box-mutable struct receiver | Writes/mutating bindings rejected for the former; latter updates only its box |
| Null, nullable views and nullable arrays | Existing null checks and invariant array rules; no hidden non-null default |
| Distinct equal-signature shapes and generic instantiations | Distinct descriptors; only specified view conversions, no array covariance |
| Generic shape-kind storage and legacy `Object?`-bounded APIs | Opt-in storage works; no implicit receiver erasure into legacy APIs |
| Forged witness, unsupported metadata or stack-only receiver | Normal loader/verifier rejection |
| Reload, child-layer unload and interpreter/JIT/AOT execution | No stale bindings or cache roots; identical observable behavior across tiers |

The proof extension must separate structural conformance from nominal subtyping; establish
progress and preservation for view operations; show that bound calls satisfy parameter,
result, exception and receiver-mutability rules; and connect run-time tests to the reified
matcher. Its reference/boxing model must preserve DAT-03 and DAT-09, including the absence of
borrowed stack references. A verifier/compiler correspondence argument must show that neither
ordinary conversion nor forged bytecode can recover a nominal receiver without the checked
helper. Intersections and variance need their own termination and soundness arguments if added.
