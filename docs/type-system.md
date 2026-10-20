# Type system and language rules

The static and dynamic rules of Reflect, area by area. Syntax: [grammar.md](grammar.md);
reflection, annotations and generators: [reflection.md](reflection.md); conventions:
[architecture.md](architecture.md) §0. Examples are cited as **ex §N** ([examples.md](examples.md)).

Intent Contracts and managed-memory rollback scopes are proposed separately in
[Intent Contracts and Transactions](intent-contracts-and-transactions.md). Their rules are not
yet part of the baseline language below.

[First-class Entity-Component Systems](entity-component-systems.md) separately proposes components
as structs, systems as classes, checked batch access and an optimizable kernel subset. Those roles
are not yet part of the baseline type rules below.

## 1. Modules, namespaces, visibility (MOD)

- **MOD-01 [Decided]** A module is one `.rflc` file: the unit of compilation, versioning, access
  and the `unsafe` capability (ex §2.1).

  | Directive | Meaning |
  |---|---|
  | `version "1.2.0";` | module version (BLD-01, Q40) |
  | `requires M;` | M is readable at compile time and run time |
  | `requires transitive M;` | readers of this module also read M |
  | `requires static M;` | compile time only (annotation types, generators, analyzers); optional at run time |
  | `exports N;`, `exports N to M;` | public types of namespace N are accessible to readers (to M only) |
  | `opens N;`, `opens N to M;` | every member of N is reflectively accessible (to M only) |
  | `provides S with I, J;` | service providers: a `public static S provider()` or a public parameterless constructor |
  | `uses S;` | the module looks up providers of S |
  | `unsafe;` | the module may contain unsafe code (§14) |
  | `main C;` | default entry class for `rfl run` |

- **MOD-02 [Proposed]** Module `reflect.core` is required and namespace `reflect.core` imported
  implicitly (as `java.lang`).
- **MOD-03 [Decided, Q40]** `requires` names no version; the module path supplies exactly one
  module per name, and a `requires` cycle is an error.
- **MOD-04 [Decided, Q13]** A namespace belongs to exactly one module.
- **MOD-05 [Proposed]** Namespace segments start with a lowercase letter (an error otherwise) and
  type names with an uppercase one (a warning), so in `a.b.C.d` the parser knows where the
  namespace ends.
- **MOD-06 [Proposed]** Lookup of a simple type name, innermost first:
  1. type parameters of enclosing members and types;
  2. nested types of enclosing types, inherited ones included;
  3. types of the current namespace (all files of the module), then of each enclosing namespace
     by name prefix, in file-scoped and block form (C#; ex §2.2 finds `Units` from
     `demo.geometry`);
  4. aliases and imports of enclosing namespace blocks, then of the file;
  5. `reflect.core`; 6. the global namespace.

  Two imports providing the same name are an error only where the name is used; a name hiding one
  from an outer step warns (ex §2.2 `Scratch`). Lookup ignores accessibility, so a found but
  inaccessible type is an error, not a fallback (ex §2.2 `Draft`).
- **MOD-07 [Decided]** `import static T;` imports every static member of `T`, extension methods
  included (MEM-09). Aliases (`using A<X> = ...;`) are scoped to their file or namespace block,
  never exported and erased at compile time; an alias target uses no other alias, every alias
  parameter appears in the target, bounds are checked at each use, and `?` may stand only for a
  parameter used once (ex §2.2).
- **MOD-08 [Decided]** Visibility (ex §2.3): `public`; `protected` (subclasses plus the same
  namespace in the same module); package-private (same namespace, same module); `private` (the
  top-level type and everything nested in it). A public type in a namespace that is not exported is
  module-internal. Overrides never reduce visibility; accessors never exceed their member.
- **MOD-09 [Proposed]** Entry and exit (ex §2.4, §7.5): `main` comes from the descriptor or
  `--main` and returns `void` (exit code 0) or `int`. An uncaught exception prints its stack trace
  to stderr and exits with 1. Before exit the VM runs one full collection and drains finalizers
  (memory.md §6).
- **MOD-10 [Decided, Q35]** Modules created or loaded at run time (emit, proxies, hot-reload
  deltas, generators in `rflc`, plugins) live in child layers that can be unloaded once
  unreachable (ARC-12); no rule may assume a type lives forever.
- **MOD-11 [Proposed]** Services: `ServiceLoader.load<S>()` returns `List<Provider<S>>`; a
  provider exposes `type` (a `Class<? extends S>`, so statics dispatch through it before any
  instance exists) and `get()` (ex §2.1).

## 2. Types, subtyping, conversions, inference (TYP)

- **TYP-01 [Decided]** Kinds of types:

  | Kind | Examples | Stored | Nullable form |
  |---|---|---|---|
  | reference: class, interface, array, record, annotation | `String`, `List<T>`, `int[]`, `Point3` | heap | `T?`, same representation, distinct at run time |
  | value: primitive, struct, record struct, enum, tuple | `int`, `Vec2`, `Rgb`, `Color`, `(int, String)` | inline | `T?`, a wrapper with a has-value flag |
  | ref struct | `Span<T>` | stack only | none |
  | pointer (unsafe) | `int*` | inline | `null` is a valid pointer |
  | type parameter | `T` | by kind | `T?` needs a kind |
  | intersection | `Runnable & Retryable` | reference | none |
  | wildcard | `? extends Shape` | type arguments only | none |

- **TYP-02 [Proposed]** Subtyping, the relation the VM's casts and the Lean model implement:

  ```text
  T <: T                         S <: T and T <: U  =>  S <: U
  class C<X> extends D<U>        =>  C<T> <: D<U[X := T]>    (likewise implements)
  T <: T?                        S <: T  =>  S? <: T?          (reference types)
  null <: T?                     T <: Object <: Object?       (T a non-null reference type)
  T[] <: Array                   (arrays are otherwise invariant: S[] <: T[] only if S = T)
  A & B <: A,  A & B <: B        S <: A and S <: B  =>  S <: A & B
  T <: U  =>  C<T> <: C<? extends U>       L <: T  =>  C<T> <: C<? super L>       C<T> <: C<?>
  (int count, String label)  ≡  (int, String)
  ```

  Value types are not subtypes of `Object`; they box. `int` to `int?` wraps; `String` to
  `String?` is subtyping. Tuple types are invariant [Proposed].
- **TYP-03 [Decided, details Proposed]** Conversions:
  - Implicit: identity, subtyping, numeric widening (§4), constant narrowing that fits
    (LEX-05), boxing to `Object`, `Object?` or an implemented interface, wrapping `T` to `T?` for
    value types, `null` to any nullable type, element-wise tuple conversion, lambda or method
    reference to a functional interface (§13), interpolated string to `String`.
  - Explicit (casts): numeric narrowing, unboxing, reference narrowing (`ClassCastException`),
    user-defined `explicit operator` (ex §8.1), enum to and from its backing type (§11), pointer
    casts (§14). Casting `null` to a non-null type throws `NullPointerException` (NUL-05).
  - Never: user-defined implicit conversions, implicit narrowing, `long` with `ulong`.
  - A box's class is the value type's class: `((Object) 42).getClass() == int.class`.
- **TYP-04 [Proposed]** Overload resolution is C#'s "better function member": per argument,
  identity beats subtyping, which beats numeric widening, which beats boxing. Remaining ties go to
  the non-generic candidate, then to more specific parameter types, then to the non-variadic form;
  anything still tied is an ambiguity error. Type arguments are inferred per candidate first.
- **TYP-05 [Decided]** Inference picks among candidates and never synthesizes a type (ex §3.6):
  1. Lower bounds come from argument types, exact bounds from `ref`/`out` arguments and invariant
     positions; lambdas wait until their parameter types are fixed (C#'s phases).
  2. A variable is fixed to the candidate (its lower bounds plus the target type) that every lower
     bound converts to (`pick(1, 2L)` is `long`). None or several: an error asking for explicit
     type arguments.
  3. Nullability is part of the bounds: `List.of("a", null)` is `List<String?>`.
  4. Exception: a `throws` type parameter joins its bounds into an intersection (ex §3.8), or is
     `RuntimeException` when nothing checked is thrown (ex §7.3).

  Diamonds take arguments from the target or constructor arguments; `var` takes the
  initializer's type, intersections included.
- **TYP-06 [Proposed]** Checking is bidirectional and local (Dunfield, Krishnaswami): every
  expression is checked against an expected type or synthesizes one.
- **TYP-07 [Decided]** No hiding anywhere: redeclaring an inherited member's signature without
  `override` is an error (ex §2.5), as is redeclaring a non-virtual member; a different parameter
  list adds an overload. A name is a field, property, event, nested type or method group, never two
  of these (ex §2.5 `describe`).

## 3. Null safety and arrays (NUL)

- **NUL-01 [Decided, Q09]** Nullability is reified: for reference types `T` and `T?` are distinct
  run-time types. `List<String>` and `List<String?>` are two classes sharing code, `String[]` and
  `String?[]` are two array types that never convert to each other (NUL-07), and reflection
  reports it (ex §2.10, §3.5).
- **NUL-02 [Decided]** Types are non-null unless written `T?`. Operators: `a?.b` (short-circuits
  the rest of the chain; `nick?.length` is `int?`), `a?[i]`, `a ?? b` (the right side may be
  `throw`), `a ??= b`, postfix `a!` (throws `NullPointerException` on null), and `x == null`,
  which every type allows (ex §4.7).
- **NUL-03 [Decided, details Proposed]** Flow analysis narrows `T?` to `T`:
  - subjects: locals, parameters, `final` fields of `this`, and other fields of `this` until the
    next call, assignment or event raise (ex §2.8); nothing longer than `this.f` is tracked;
  - conditions: `x != null`, `!(x == null)`, `x instanceof T t`, `x ??= e`, a completed `x!`,
    `if (x == null) { return; }`, short-circuit `&&` and `||`, patterns in `switch`;
  - a local assigned inside a lambda is never narrowed (Kotlin); loops solve to a fixpoint.
- **NUL-04 [Decided, Q11]** Construction follows Java: `super(...)` runs first, then field
  initializers in textual order, then the rest of the constructor. Every path definitely assigns
  each non-null field without an initializer, each `final` field once and each get-only
  auto-property (ex §2.8 `Person`). Warnings, not errors, flag the remaining holes: calling an
  overridable method on `this` in a constructor, and letting `this` escape before all non-null
  fields are assigned. A field read in such a case yields `null`; the next dereference throws,
  and memory safety holds because the VM checks every dereference. Hot reload adds a hole of the
  same kind in v0: a non-null field added by a delta is `null` in older objects (REFL-07, Q64).
- **NUL-05 [Proposed]** Run-time checks where static guarantees end:
  - casting `null` to a non-null type throws `NullPointerException`; to `T?` it succeeds;
  - `instanceof` is false for `null`, even against a nullable type, so pattern variables are
    never null;
  - generic code: `(T) o` checks non-null when `T` is instantiated with a non-null type;
  - reflection rejects `null` for non-null fields and parameters with
    `IllegalArgumentException`; reflective results are typed `Object?`;
  - array stores need no check: a `String[]` is never seen as a `String?[]` (NUL-07).
- **NUL-06 [Proposed]** Generics and null:
  - an unconstrained `T` has the implicit bound `Object?` and may be instantiated with nullable
    types (`Box<String?>`), so members of a `T` value need narrowing; a non-null bound such as
    `T extends Object` allows direct use;
  - `T extends class` admits only non-null reference types, `T extends struct` only non-null
    value types; `T?` is the nullable form of either (ex §3.1);
  - `default(T)` needs `T extends struct` (ex §3.3); `default(T?)` is `null`.
- **NUL-07 [Decided, Q62; details Proposed]** Arrays:
  - Type suffixes read left to right (grammar.md §3.5); `int[]?[]` is the jagged case of
    ex §2.10.
  - `new T[n]` needs a zero value: value types or nullable references. `new String[n]` and
    `new String[][n]` are errors; use an initializer, `Array.filled(n, v)` or a nullable element
    type.
  - Arrays are invariant, nullability included: `S[]` converts to `T[]` only when `S` and `T` are
    the same type, so `Square[]` is not a `Shape[]` and `String[]` is not a `String?[]`. A
    reference store therefore needs no check, and casts and type tests of arrays compare the exact
    array type. Covariant use goes through generic methods (`draw<T extends Shape>(T[] shapes)`),
    the `Array` base class and, later, a read-only covariant view. Such a view is a new type, so
    adding it changes no program; making arrays covariant again would change what existing array
    casts and type tests return.
  - `Span<T>` over a `T[]` needs no element type check, since the array's type is exact.
  - Lengths are `int`, indexes are checked (`IndexOutOfBoundsException`), there are no
    rectangular `int[,]` arrays in v1, and every array type extends `reflect.core.Array`.

```csharp
// rejected
String[][] grid = new String[][3];         // error: elements would start as null; use new String[]?[3]
static String show<T>(T x) { return x.toString(); }   // error: T's bound is Object?; narrow x or bound T
Shape[] shapes = new Square[] { new Square(1) };     // error: arrays are invariant; use a generic method
```

## 4. Primitives, numerics, strings (NUM)

- **NUM-01 [Decided]** Primitives are structs of `reflect.core` with keyword aliases (ex §2.7
  confirms `int` as `Int32`; the other names are [Proposed]):

  | Keyword | Struct | Bytes | Keyword | Struct | Bytes |
  |---|---|---|---|---|---|
  | `boolean` | `Boolean` | 1 | `uint` | `UInt32` | 4 |
  | `byte` (signed) | `Int8` | 1 | `long` | `Int64` | 8 |
  | `ubyte` | `UInt8` | 1 | `ulong` | `UInt64` | 8 |
  | `short` | `Int16` | 2 | `float` | `Float32` | 4 |
  | `ushort` | `UInt16` | 2 | `double` | `Float64` | 8 |
  | `char` (UTF-16 unit) | `Char` | 2 | `int` | `Int32` | 4 |

  No `decimal` and no pointer-sized integer: the only target is 64-bit (Q04). The numeric
  primitives implement the generic-math interfaces (stdlib.md §3).
- **NUM-02 [Decided]** Binary numeric promotion (C#):
  1. `double` if either operand is `double`, else `float` if either is `float`;
  2. `ulong` if either is `ulong` and the other unsigned; a signed operand is an error unless it
     is a non-negative constant (ex §2.7 rejects `1L + 1UL`);
  3. `long` if either is `long`, or if one is `uint` and the other signed (`i + ui`, ex §2.7);
  4. `uint` if either is `uint`; otherwise `int` (`byte + byte` is `int`).

  Implicit widening: every integral type to every wider type of the same signedness; unsigned to
  any wider signed type; `char` to `ushort` and wider; integral types to `float` and `double`;
  `float` to `double`.
- **NUM-03 [Decided, details Proposed]** Integer arithmetic wraps. `checked(e)` and `checked { }`
  trap on `+ - * ++ --`, unary `-` and narrowing casts with `ArithmeticException`; the context is
  lexical (ex §2.7). Also:
  - integer division by zero always throws; `int.MIN_VALUE / -1` wraps unchecked and throws
    checked; `%` truncates toward zero;
  - shift counts are masked to the operand width; `>>` is arithmetic for signed and logical for
    unsigned operands; `>>>` is always logical;
  - float-to-integer conversion saturates and maps NaN to 0 unchecked (Java) and throws checked;
  - inside `checked`, `operator checked +` wins over `operator +` (ex §4.6).
- **NUM-04 [Proposed]** Strict IEEE 754: round-to-nearest-even, no fused multiply-add except
  `Math.fma`, no extended precision. `==` follows IEEE (`NaN != NaN`, `0.0 == -0.0`); `equals`
  compares bits after canonicalizing NaN. `Math` is written in Reflect, never a host library, so
  `const` evaluation in `rflc` and run time agree bit for bit.
- **NUM-05 [Decided, details Proposed]** Strings:
  - `String` is a final, immutable sequence of UTF-16 code units (unpaired surrogates allowed);
    `length` is a property; `charAt(i)` and `s[i]` return `char`;
  - `==` compares values through `String`'s `operator ==` (ex §8.5); identity needs
    `Object.referenceEquals`;
  - `+` concatenates, converting the other operand with `String.valueOf(Object?)`;
  - literals are interned; strings are valid `switch` constants;
  - interpolation lowers to appends on a stack-allocated builder; format specifiers follow .NET
    custom numeric formats (`{pct:0.0}`) and are culture-invariant.

## 5. Statements and expressions (STM)

- **STM-01 [Decided]** Java's statements (grammar.md §3.6): classic `for` shares one variable,
  enhanced `for` makes a fresh variable per iteration; `switch` has only arrow arms; plus
  `checked { }` and `unsafe { }`. No `goto`.
- **STM-02 [Decided, details Proposed]** `switch` (ex §2.9, §9.4):
  - arms never fall through; `case A, B ->` lists constants; `default ->` and `throw` arms;
  - case constants: integral, `char`, `String`, enum constants, `const` fields;
  - patterns with `when` guards; an arm dominated by an earlier one is an error;
  - switch expressions are exhaustive (enums, sealed hierarchies, or `default`); switch
    statements only when they use patterns (Java 21);
  - `case null ->` is allowed for a nullable selector; without it a null selector throws
    `NullPointerException`; for a non-null selector it is an error.
- **STM-03 [Decided, Q12]** A switch-expression block arm returns its value with `return` and ends
  in `return e;` or `throw` on every path. That `return` never leaves the method, and `break` or
  `continue` may not jump out of a switch expression. The arm compiles inline: `return` jumps to
  the join point with the value.
- **STM-04 [Decided, details Proposed]** Enhanced `for`, in order of preference:
  1. arrays: an indexed loop reading `length` once;
  2. a type with an accessible `iterator()` (instance or extension method) whose result has
     `boolean hasNext()` and `T next()`, matched by name, so struct iterators never box;
  3. `Iterable<T>`.

  An iterator that implements `AutoCloseable` is closed when the loop exits. The collections'
  default iterators are structs (LIB-04, Q65): a loop over a class type such as `ArrayList<T>`
  keeps the struct in a local and never boxes it; a loop over an interface type such as `List<T>`
  gets the iterator boxed once, through the bridge of MEM-02.

  ```csharp
  // lowered: for (String s : list) { body }
  var $it = list.iterator();          // a local, so the struct's mutating next() may update it
  while ($it.hasNext()) {
      final String s = $it.next();    // fresh per iteration: what lambdas capture
      body
  }
  ```

- **STM-05 [Decided, details Proposed]** Expressions (grammar.md §3.7):
  - evaluation is strictly left to right: operands before operators, arguments in order; a
    compound assignment evaluates its target once (ex §8.3);
  - `nameof(x)` is a constant string; `sizeof(T)` is a constant for unmanaged types (UNS-05);
  - patterns: `T t`, record patterns with nested patterns, `var x` and `_` inside record patterns;
    pattern variables follow Java's flow scoping (`if (!(o instanceof T t)) { return; } use(t);`);
  - tuples: literals, deconstruction `var (lo, hi) = range(xs);`, assignment `(x, y) = (y, x);`;
  - not in v1: object initializers, collection literals, `with` expressions, local functions,
    catch filters.
- **STM-06 [Decided, Q20]** Iterators (`yield return e;`, `yield break;` in methods returning
  `Iterable<T>` or `Iterator<T>`, lowered to a state machine like `async`) are a late feature,
  close to v1.0 at best. They would let compiler passes hand out tree nodes lazily
  (`for (Node n : descendants(body))`) instead of building lists, visitors or hand-written
  iterators; nothing requires them.

## 6. Classes, interfaces, members (MEM)

- **MEM-01 [Decided]** Member kinds: fields, `const` fields, methods, constructors, static
  initializers, properties, indexers, events, operators, conversion operators, finalizers, nested
  types. [Proposed] No instance initializer blocks, local classes or anonymous classes.
- **MEM-02 [Decided]** Instance methods, properties, indexers and events are non-virtual unless
  `virtual` or `abstract`; non-virtual calls compile to direct calls (ex §2.5). Overriding needs
  `override`, also when implementing an interface member (ex §2.6); `final override` stops
  overriding; a `final` class has no subclasses. [Decided, Q65, Q66] Return types are
  covariant: an override keeps the parameter types of the member it overrides, but may return
  any type that converts to that member's return type by subtyping, boxing or nullable wrapping
  (TYP-03). So `virtual Object function()` can be overridden by `override MyClass function()`,
  and a caller through the subclass gets a `MyClass` without a cast (ex §2.5). An override never
  widens a return type. Get-only properties and indexers narrow the same way, instance and static
  (ex §4.2, SVD-05); settable ones keep the exact type, since their setter takes it. A reference
  subtype fills the base slot itself. A type that needs boxing or wrapping gets a synthesized
  bridge that fills the base slot, calls the override and converts the result (Java's bridge
  methods, BC-12), so `ArrayList<T>.iterator()` returns its struct iterator and still implements
  `Iterable<T>.iterator()` without explicit interface implementation (MEM-03, LIB-04).
- **MEM-03 [Decided]** Interfaces hold abstract methods, properties and events (implicitly
  `public abstract`), `default` methods, private methods, static methods, static abstract and
  static virtual members (§7) and [Proposed] `const` fields; never instance fields.
  Default-method conflicts (ex §2.6): a class member wins, then the unique most specific
  default; otherwise the class must override. `Named.super.label()` names a parent; `super.label()`
  works when exactly one parent provides the member. [Proposed] No explicit interface
  implementation in v1: one override serves every interface that declares the signature.
- **MEM-04 [Decided]** Nested types never capture an outer instance and see the private members
  of their enclosing types (ex §2.5).
- **MEM-05 [Decided]** `final` fields are assigned once; `const` fields are compile-time
  constants and implicitly static (reflection.md §5); a static field has one storage per class,
  or per instantiation for generic classes (§8). [Decided, Q60] In bytecode too, only the
  declaring type's constructors, on their own `this`, and its static initializer store to its
  finals, and reflection never does (BC-23).
- **MEM-06 [Decided, details Proposed]** Constructors use Java's syntax and chaining:
  `this(...)` or `super(...)` comes first, an omitted call means `super()`, and a class without
  constructors gets a public parameterless one (or forwarders for inherited virtual constructors,
  SVD-06). Static field initializers and `static { }` blocks run in textual order, once per class
  or instantiation, before first use (vm.md §7). If they throw, the first use gets
  `ExceptionInInitializerError` and later uses get a new one with the same cause. A static
  initialization cycle behaves as in Java (the nested request sees default values, possibly
  `null` in a non-null static); the compiler warns about cycles it can see.
- **MEM-07 [Decided, details Proposed]** `Object` declares virtual `equals(Object?)`,
  `hashCode()` and `toString()`, the final `getClass()` typed `Class<? extends T>` for a receiver
  of static type `T`, and `static boolean referenceEquals(Object?, Object?)` (identity; boxes have
  none, DAT-09). No `wait`, `notify`, `clone` or `finalize`; finalizers are `~C()` (memory.md §6).
- **MEM-08 [Decided, Q11]** Construction order and its warnings: NUL-04.
- **MEM-09 [Decided, Q21]** Extension methods are static methods whose first parameter says
  `this` (ex §2.10 `asSpan<T>(this T[] array, ...)`). They are in scope where their declaring
  class's namespace is imported or the class statically imported, and instance members always win.
- **MEM-10 [Decided, Q22]** Variadic parameters use Java's syntax, `Object?... args`: last
  parameter only, typed as an array; the expanded form loses ties (TYP-04). They serve
  `invoke(null, 2, 3)` (ex §10.5) and `List.of("a", 1, "b")`. [Proposed] Arrays are invariant
  (NUL-07), so a `String[]` passed to `Object?... args` becomes one element, not the argument
  array; the compiler warns.
- **MEM-11 [Proposed]** No default parameter values or named arguments in v1; annotations keep
  their named elements.

## 7. Static virtual dispatch (SVD)

Proposal item 5. Statics follow Smalltalk's metaclass idea without metaclass objects: every
static access has a **receiver class** `R`, and virtual statics dispatch on it.

- **SVD-01 [Decided]** Static methods, properties and operators (in interfaces, ex §4.6;
  [Proposed] also in classes) can be `virtual`, `abstract`, `override` or `final override`;
  constructors can be `virtual`. [Proposed] Static events cannot.
- **SVD-02 [Decided]** `R` comes from the syntax of the access:

  | Access | `R` | Example (ex §4.1, §4.2, §4.5) |
  |---|---|---|
  | `C.m`, a class name | `C`, fixed at compile time | `Kind.value` is `NO_KIND` |
  | `e.m`, an instance expression | run-time class of `e`; `null` throws | `kind.value` |
  | `T.m`, a type parameter | the reified type argument | `K.value` |
  | `k.m`, a `Class<? extends C>` value | the class `k` refers to, unless `Class<T>` has a member `m` | `k.value` |
  | unqualified `m` in an instance member | `this.getClass()` | `Probe.fromInstance()` |
  | unqualified `m` in a static member | the caller's `R`, forwarded | `Kind.describe()` |
  | `super.m` in a static override | the parent's implementation, same `R` | `SubProbe.value` |

  A virtual static resolves to the most-derived implementation visible from `R`.
- **SVD-03 [Decided]** Non-virtual static methods also receive `R`, so `Cube.describe()` runs
  `Kind.describe` with `R = Cube` and its unqualified `value` reads `CUBE_KIND` (late static
  binding, PHP's `static::`).
- **SVD-04 [Decided]** Storage (ex §4.3): a class declaring `static override` on an
  auto-property owns storage; other subclasses share the nearest overrider's. Each generic
  instantiation owns all of its statics.
- **SVD-05 [Decided]** Restrictions (ex §4.2, §4.4):
  1. no hiding: a static named like an inherited one is a `static override` of a virtual or
     abstract static;
  2. settable overrides keep the exact type; get-only ones may narrow it (MEM-02);
  3. `final static override` ends overriding;
  4. a concrete class implements every inherited `static abstract`;
  5. a `static abstract` read through a class name is a compile error; at run time an `R` lacking
     the implementation throws `AbstractMemberError`;
  6. a type with unimplemented static abstracts cannot be a type argument when the parameter's
     bound has static abstracts (C# 11);
  7. [Proposed] calling a static method through a class name that leaves static abstracts
     unimplemented warns (`Polygon.summary()` otherwise fails only at run time).
- **SVD-06 [Decided]** Virtual constructors (Delphi):
  - `public virtual Kind(int size)` declares a constructor slot; `override Cube(int size)`
    re-declares it;
  - a subclass without constructors inherits every virtual constructor through a synthesized
    `override Sphere(int size) { super(size); }`, so its field initializers run;
  - a subclass that declares any constructor overrides every inherited virtual one (ex §4.5);
  - `new K(x)` (the bound declares the constructor) and `k.create(x)` build an instance of `R`;
    an abstract `R` throws `InstantiationError`, a statically known abstract class is an error;
  - [Proposed] no C#-style `new()` constraint: a parameterless virtual constructor in the bound
    serves.
- **SVD-07 [Decided]** When a static and a member of `Class<T>` share a name, `k.x` means the
  `Class<T>` member and the static's declaration warns (ex §4.5).
- **SVD-08 [Proposed]** Interface statics dispatch through `R` like class statics: `T.zero`,
  `provider.type.kindName` and `shape.kindName` work, `Shape.kindName` is an error while abstract,
  and a default method reading an abstract static sees the implementing class.
- **SVD-09 [Proposed]** `R` travels in the calling convention, never as a declared parameter
  (bytecode.md §5), so making a class non-final changes no signature.
- **SVD-10 [Proposed]** Lambdas and method references capture what they need to find `R`:
  `() -> k.value` reads `k`'s class when it runs, `k::create` binds the class value, and an
  unqualified static inside a lambda in a static method captures that method's `R`.

## 8. Generics and reification (GEN)

- **GEN-01 [Decided]** Classes, interfaces, structs, records and methods can be generic; type
  parameters follow the method name and use Java's bounds: kinds `class`/`struct` first, then at
  most one class, then interfaces, joined with `&`; `where` adds bounds (ex §3.1).
- **GEN-02 [Decided]** Every instantiation is its own run-time class, with its own statics,
  static initialization, `Class<T>` object and nullability (ex §3.1, §3.5).
- **GEN-03 [Decided]** Operations legal on type parameters because of reification (ex §3.3,
  §7.3): `T.class`, `instanceof T`, `(T) o`, `catch (E e)`, `new T(...)` (SVD-06), `new T[n]` for
  element types with a zero value, `default(T)`, `T.m` (§7), and `getClass()` returning the exact
  instantiation.
- **GEN-04 [Decided]** Use-site variance only: `?`, `? extends T`, `? super T` in the types of
  fields, parameters, locals and results, in nested type arguments, casts and `instanceof`, where
  the VM checks each argument (ex §3.3). Wildcards are never instantiated nor direct supertype
  arguments. [Proposed] No declaration-site variance; wildcard-typed expressions go through Java's
  capture conversion.
- **GEN-05 [Decided]** The four restrictions of Tate, Leung and Lerner (inheritance, parameter,
  intersection, equivalence) plus Java's rule against wildcard supertype arguments keep subtyping
  decidable (ex §3.7). Their formal statements are transcribed from the paper into
  formal-model.md, whose FR-wild layer proves the algorithm terminates; if that proof lags, v1.0
  limits wildcards to a proven fragment (LEAN-05). [Proposed] `Shapes<? extends Object>` where the
  bound is `Shape` is an error with a fix-it to `Shapes<?>`.
- **GEN-06 [Decided]** Intersections (ex §3.8): at most one class component, first; interface
  components are sorted by qualified name at run time, so `Runnable & Retryable` and
  `Retryable & Runnable` are one type. They appear in type arguments, casts, parenthesized
  `instanceof`, and as inferred types of `var` and `throws` parameters.
- **GEN-07 [Proposed]** Generic virtual methods are allowed and dispatch through a per-class cache
  keyed by method and type arguments (vm.md §5).
- **GEN-08 [Proposed]** Instantiation is lazy, so polymorphic recursion works under the
  interpreter and JIT; the AOT compiler falls back to shared code with run-time dictionaries for
  instantiations it cannot enumerate (jit-aot.md §6). Expansion through inheritance
  (`class C<T> extends D<C<C<T>>>`) is rejected (ECMA-335 §II.9.2).
- **GEN-09 [Decided, details Proposed]** Implementation (Kennedy and Syme): instantiations over
  reference types share one body (nullability included); each combination of value-type arguments
  gets its own. Shared code finds exact type arguments through the type context: the run-time type
  of `this`, the receiver class of statics, or a hidden method-instantiation argument (vm.md §4).
- **GEN-10 [Decided]** Constraint violations are compile-time errors; reflective `instantiate`
  throws `TypeLoadError` (ex §7.8).
- **GEN-11 [Decided]** Exception classes may be generic and `catch` tests their type arguments
  (ex §7.3).
- **GEN-12 [Proposed; nested types Decided, Q70]** One canonical spelling of run-time type names
  serves reflection, errors and `.rfla`: qualified names, arguments in angle brackets, `?` for
  nullability, intersections in parentheses, primitives by struct name (ex §3.3 prints
  `reflect.collections.List<reflect.core.Int32>`). The last dotted segment names the top-level
  type, and `/` starts each nested type, as in CIL: `demo.zoo.Enclosure/Tag`.

## 9. Properties, indexers, events (MEM, continued)

- **MEM-12 [Decided, details Proposed]** Properties are C#'s (ex §5.1): accessors with `field`
  (C# 14) and `value`; auto, get-only auto (assignable in constructors), computed, initialized,
  static, abstract, virtual, override, interface and partial properties (reflection.md §3). An
  accessor may narrow its accessibility, never widen it, and may declare `throws`. Properties are
  not variables (no `ref`, no `out`); compound assignment and `++` call `get` then `set` and
  evaluate the receiver once. [Proposed] No `init` accessors in v1.
- **MEM-13 [Decided, details Proposed]** Lowering (ex §11.1): methods `get_X` and `set_X`, a
  property row linking them, and a backing field `X$field` for auto properties and properties that
  use `field`. A method named `get_X` next to a property `X` is an error.
- **MEM-14 [Decided, details Proposed]** Indexers (ex §8.4): `this[...]`, overloaded by parameter
  types, may throw, lowered to `get_Item` and `set_Item`; `grid[x, y] ??= v` calls `set_Item` only
  when `get_Item` returned null. No static indexers.
- **MEM-15 [Decided]** Events (ex §5.2, §11.3):
  - the event type is a functional interface; field-like events get a compiler-generated listener
    list; custom events write `add` and `remove`; events may be static, abstract, interface
    members or overrides;
  - outside the declaring class only `+=` and `-=` exist, and only the declaring class raises
    ([Proposed] subclasses raise through a protected method the class offers);
  - a raise snapshots the listeners and runs each in subscription order; the first exception is
    rethrown after all ran with later ones suppressed; an `Error` ends the raise at once, carrying
    earlier failures as suppressed; no listeners, nothing happens;
  - `-=` removes the last listener that `equals` the argument (FUN-05);
  - an event of a throwing functional interface raises under its `throws` clause (ex §5.2).

  ```csharp
  // lowered: Button.clicked (ex §5.2)
  private ListenerList<Consumer<ClickEvent>>? clicked$listeners;   // allocated on the first +=
  public void add_clicked(Consumer<ClickEvent> l) { (clicked$listeners ??= new ListenerList<>()).add(l); }
  public void remove_clicked(Consumer<ClickEvent> l) { clicked$listeners?.removeLast(l); }
  private void raise_clicked(ClickEvent e) { /* snapshot, run all, aggregate failures (ex §11.3) */ }
  ```

- **MEM-16 [Decided, Q23]** Listener lists hold strong references; `reflect.core` provides
  `WeakReference<T>` and a weak-keyed table, and the library a weak-listener wrapper
  (`ok.clicked += WeakListener.of(this, Window::onClick);`) that does nothing once its target is
  collected.
- **MEM-17 [Decided, Q14]** `@Observable` is a library generator over partial classes
  (reflection.md §3), not a language feature; it is how proposal item 8 is met.

## 10. Operators (OPR)

- **OPR-01 [Decided]** Overloadable (ex §8.1 to §8.4, §4.6): unary `+ - ! ~ ++ --`; binary
  `+ - * / % & | ^ << >> >>>`; comparisons in pairs (`==` with `!=`, `<` with `>`, `<=` with `>=`);
  compound assignments `+= -= *= /= %= &= |= ^= <<= >>= >>>=` as instance methods returning `void`
  (C# 14); `checked` variants of `+ - * / ++ --`, unary `-` and explicit conversions (C# 11);
  `explicit operator` conversions; indexers (§9). Never: `&& || ?: ?? ??= ?. = . :: instanceof
  new`, postfix `!`, implicit conversions (ex §8.1) and plain assignment (OPR-09).
- **OPR-02 [Decided, details Proposed]** Declarations are `public static` (compound assignments
  excepted), take at least one parameter of the containing type or its `T?`, and `++`/`--` take
  and return the containing type. `==`, `!=` and ordered comparisons return `boolean`; declaring
  `==` without overriding `equals` and `hashCode` warns.
- **OPR-03 [Proposed]** Resolution (C#): candidates are the user-defined operators of both operand
  types and their base classes, plus static abstract operators of type-parameter bounds
  (ex §4.6). If any is applicable, predefined operators are ignored. Value-type operators lift to
  `T?` (`Vec2? + Vec2?` is null when either side is).
- **OPR-04 [Decided]** What `==` means (ex §4.7, §8.5):

  | Operands | `==` compares |
  |---|---|
  | numeric primitives | values, floats per IEEE |
  | `boolean`, `char`, enums | values |
  | `String` | values (declared operator) |
  | records, record structs | components (generated operator) |
  | tuples | elements [Proposed] |
  | classes without `operator ==` | identity |
  | structs without `operator ==` | error |
  | type parameters | needs a bound declaring `==` (`Equatable<T>`) |
  | anything with `null` | null test |

  Operators are static, so static types choose: two strings typed `Object` compare by identity.
  On `Object` and interface operands `==` is `ceq.ref`, so two boxes compare by class and contents
  (DAT-09). [Proposed] A `reflect.lint` analyzer flags `==` on `Object` or interface operands.
- **OPR-05 [Decided]** Compound assignment (ex §8.3): an instance `operator op=` updates in place;
  without one, `x op= y` means `x = x op y` with `x` evaluated once. An in-place struct operator is
  `mutating` and needs a variable (local, field, array element, `ref`), never a property result.
- **OPR-06 [Decided]** `x++` yields the old value and stores `operator ++(x)`; `++x` yields the
  new one.
- **OPR-07 [Decided, Q39]** Generic math uses static abstract interface operators (ex §4.6); the
  interfaces are per operator plus aggregates (stdlib.md §3). `byte`'s `Addable` wraps at 8 bits
  although the predefined `+` promotes to `int`.
- **OPR-08 [Decided]** Custom operators come after v1.0 (ex §8.6); token rule: grammar.md §5.
- **OPR-09 [Decided, Q15]** Plain `=` is not overloadable: references copy identity, struct copies
  are bitwise, and property setters already intercept assignment to members.

## 11. Structs, ref safety, records, enums, sealed types, tuples, destructible objects (DAT)

- **DAT-01 [Decided, details Proposed]** Structs are values (ex §9.1): copied on assignment, zeroed
  by `default(S)` and in new arrays, boxed when converted to `Object` or an interface (a box has
  no identity, DAT-09), never extended (they may implement interfaces), never finalized.
  Reference-typed fields must be nullable because zeroing makes them `null`. A constructor leaves
  unassigned fields at zero (C# 11), and no parameterless constructor can be declared, so
  `new S()` equals `default(S)`.
- **DAT-02 [Decided, Q24]** Struct methods are read-only unless marked `mutating` (Swift). A
  read-only method may not assign fields of `this` or call `mutating` methods on it; calling a
  `mutating` method on a read-only location (a `final` field, an `in` parameter, a property or
  method result) is an error, so no hidden copies exist. Constructors assign `this`; in-place
  operators must say `mutating`. The flag is metadata, and the verifier tracks read-only
  references (bytecode.md §7). [Decided, Q65] An interface member may be declared `mutating`
  (Swift's rule for protocols): only such a member can be implemented by a `mutating` struct
  method, and a call to it on a type-parameter value needs a writable location, so generic code
  makes no hidden copy either. A class implements it as an ordinary method. `Iterator<T>.next()`
  is declared `mutating` (LIB-04); the boxes of a struct that implements such a member with a
  `mutating` method have identity (DAT-09).

  ```csharp
  public struct Tally {
      public int count;
      public mutating void increment() { count++; }
      public int peek() { return count; }        // assigning count here would be an error
  }
  final class Holder { public final Tally t = new Tally(); }
  // holder.t.peek() compiles; holder.t.increment() does not: t is final
  ```

- **DAT-03 [Decided, details Proposed]** Ref safety follows C# 11 (ex §9.1): `ref`, `in` and `out`
  parameters (call sites repeat `ref` and `out`), `ref` returns and locals, `scoped`, and ref
  structs that never reach the heap (no boxing, no fields outside ref structs, no captures, no
  type arguments, no array elements). Escape rules (safe-context and ref-safe-context) reject
  dangling references. [Proposed] User-declared `ref struct` types with `ref` fields, so `Span<T>`
  is plain Reflect, and `ReadOnlySpan<T>`. A `ref` may point inside an object (memory.md §5).
  Under the Lean gate, if FR-ref lags, v1.0 ships without `ref` returns, `ref` fields and
  `scoped`, and `Span<T>` stays VM-implemented (LEAN-05). `Borrow` types come after v1.0 (Q05).
- **DAT-04 [Decided, details Proposed]** Records (ex §9.2): `record` is a class, `record struct` a
  value. Components become get-only properties and a canonical constructor that a compact
  constructor can validate; `equals`, `hashCode`, `toString` (`Point3[x=1, y=2, z=3]`), `==` and
  `!=` are generated; record patterns deconstruct them. Class records are implicitly `final` and
  extend nothing, though they may implement interfaces.
- **DAT-05 [Decided, details Proposed]** Enums (ex §9.3):
  - value types over an integral backing type (`int` by default), counting from 0 unless given;
  - `ordinal` is the declaration index, a cast gives the backing value (`Level.HIGH.ordinal` is
    1, `(int) Level.HIGH` is 200);
  - `name`, `valueOf(String)`, `values()` (a fresh array per call), exhaustive `switch`,
    `Comparable` by ordinal; `==` and `!=` compare backing values; no ordered operators;
  - casting an undeclared value to a plain enum throws `ClassCastException`; a `@Flags` enum
    accepts any combination of declared bits, rejects other bits, supports bitwise operators,
    prints names joined by `" | "`, and has no per-constant fields or bodies;
  - per-constant fields and bodies lower to static tables and a switch on the value, since the
    value is only the backing integer:

    ```csharp
    // lowered: Planet.mass and Op.apply (ex §9.3)
    public struct Planet {
        private final int value$;
        private static final double[] mass$ = { 3.303e+23, 5.976e+24 };
        public double mass { get { return mass$[ordinal]; } }
    }
    public struct Op {
        private final int value$;
        public int apply(int a, int b) {
            switch (value$) {
                case 0 -> { return a + b; }
                default -> { return a * b; }
            }
        }
    }
    ```

  - enum constants are constant expressions, and a `const` method of an enum folds on a constant
    receiver (`Planet.EARTH.surfaceGravity()`, ex §9.3; reflection.md §5).
- **DAT-06 [Decided, details Proposed]** Sealed hierarchies (ex §9.4): `sealed` classes and
  interfaces list `permits`; each permitted subtype is `final`, `sealed` or `unsealed` (Java's
  `non-sealed`) and lives in the same module; switches and patterns check exhaustiveness. `permits`
  may be omitted when all subtypes share the file.
- **DAT-07 [Decided, details Proposed]** Tuples (ex §9.5) are value types
  (`reflect.core.Tuple2<T1, T2>` and so on) whose element names are metadata; they deconstruct,
  convert element-wise and compare element-wise with `==`; beyond 8 elements they nest.
- **DAT-08 [Decided, Q26]** Destructible objects (ex §9.6):
  - `Destructible` is an abstract class the VM knows; two header bits: *destroying*, set when
    `destroy()` starts so a nested call returns at once, and *destroyed*, set in the `finally`;
  - `destroy()` is protected and final, so each class decides who may destroy it; it runs
    `onDestroy()`, raises `destroying`, then sets *destroyed*;
  - guarded accesses throw `ObjectDestroyedException`: entry to any instance method or accessor
    declared in a `Destructible` subclass, and field accesses through a receiver other than
    `this`; exempt are `destroyed` and `Object`'s `equals`, `hashCode`, `toString`, `getClass`;
  - `Destructible.alive(x)` turns a destroyed object into `null` for `?.` and `??`.
- **DAT-09 [Decided, Q61, Q65]** Boxes have no identity, except those of box-mutable types (last
  item). A box is a value converted to `Object` or an interface (DAT-01):
  - `ceq.ref`, and so `==` on `Object` or interface operands and `Object.referenceEquals`, is
    true for two boxes of the same class with the same contents: primitive fields by bit pattern
    (JEP 401), so `(Object) 0.0 == (Object) -0.0` is false; struct fields recursively; reference
    fields by `ceq.ref`;
  - a box has no identity hash: `Object.hashCode()` of a box hashes its contents, consistently
    with `ceq.ref`;
  - `WeakReference` is declared `WeakReference<T extends class>`, and a box as its referent, or as
    the key of a weak-keyed table, throws `IllegalArgumentException` (GC-05);
  - boxes are immutable: `unbox` copies the value out, and reflection cannot set a box's fields
    (REFL-03);
  - a value type is box-mutable when one of its `mutating` methods implements a `mutating`
    interface member (DAT-02), as the collections' struct iterators do (LIB-04). Its boxes are
    ordinary objects: they have identity, a mutating call through the interface updates the box
    in place (C#'s boxed `List<T>.Enumerator`), and `ceq.ref`, hashing, weak references and
    reflection treat them as objects. The VM marks such a type when it loads it (VM-13).

  The VM may therefore cache boxes without identity, share equal ones and remove them after
  escape analysis with no visible change. The cost is a slow path in `ceq.ref` when two
  references differ and their static types admit boxes (`Object`, interfaces, type parameters).
  `==` can also tell whether a box's private fields equal those of a value the caller can build,
  such as `default(S)`.

## 12. Exceptions (EXC)

- **EXC-01 [Decided]** Java's hierarchy: `Throwable` splits into `Exception` (checked, except the
  `RuntimeException` subtree) and `Error` (unchecked). Checking follows JLS 11.2 for methods,
  constructors, property and indexer accessors (ex §5.1, §8.4), event raises (ex §5.2) and lambda
  bodies against their target's `throws` (ex §7.3). Static and field initializers cannot throw
  checked exceptions.
- **EXC-02 [Decided]** Exception transparency (ex §7.3): a type parameter `E extends Exception`
  in `throws`, inferred by TYP-05; `catch (E e)` matches the reified `E` and its subclasses;
  precise rethrow as in Java 7.
- **EXC-03 [Decided]** Exception classes may be generic; `catch` tests type arguments (ex §7.3).
- **EXC-04 [Decided]** try-with-resources uses Java's translation (ex §7.4): resources close in
  reverse order, a failing `close()` becomes a suppressed exception, existing variables may be
  resources.
- **EXC-05 [Decided, kinds Proposed]** Exceptions raised by the language and the VM (ex §7.8):

  | Exception | Kind | Raised by |
  |---|---|---|
  | `NullPointerException` | unchecked | dereferencing `null`, `x!`, casting `null` to a non-null type, `e.m` on a null `e` |
  | `ArithmeticException` | unchecked | integer division by zero, overflow under `checked` |
  | `IndexOutOfBoundsException` | unchecked | array and span indexes |
  | `ClassCastException` | unchecked | failed casts, undeclared enum values |
  | `ObjectDestroyedException` | unchecked | guarded access to a destroyed object (DAT-08) |
  | `UndeclaredThrowableException` | unchecked | a proxy handler throws an undeclared checked exception |
  | `PhaseException` | unchecked | run-time-only reflection inside the compiler |
  | `DeterminismViolation` | error | a nondeterministic call in compile-time code under `--deterministic` |
  | `InstantiationError` | error | a virtual constructor reached on an abstract class |
  | `AbstractMemberError` | error | an unimplemented static abstract reached through `R` |
  | `ExceptionInInitializerError` | error | a failing static initializer (MEM-06) |
  | `TypeLoadError` | error | missing types, constraint violations, invalid instantiations, link errors (BC-12, BC-23) |
  | `StackOverflowError`, `OutOfMemoryError` | error | exhausted resources |
  | `InvocationTargetException` | checked | a reflectively called member threw |
  | `IllegalAccessException` | checked | reflective access denied (ex §10.3) |

- **EXC-06 [Proposed]** Stack traces are recorded when an exception is constructed (Java) as raw
  method and offset pairs, decoded to source lines only when printed.
- **EXC-07 [Proposed]** No catch filters in v1; `finally` runs on every exit from its `try`,
  including `break`, `continue` and `return`.

## 13. Lambdas and functional interfaces (FUN)

- **FUN-01 [Decided]** Lambdas and method references convert only to functional interfaces (one
  abstract method; `@FunctionalInterface` is optional and checked) and always need a target type
  (ex §7.1). There are no delegate or function types.
- **FUN-02 [Decided]** `Function<String, int>` does not box: the interface instantiated over
  `int` has specialized code (GEN-09).
- **FUN-03 [Decided]** Captures take variables, not values (C#, ex §7.2): a local that is captured
  and assigned moves to a heap display shared by the method and its lambdas; enhanced-`for`
  variables are fresh per iteration, classic-`for` variables shared; `ref`, `in` and `out`
  parameters, `ref` locals and ref struct values cannot be captured.
- **FUN-04 [Decided]** Method references (ex §7.1): static (`Lambdas::twice`), unbound instance
  (`String::trim`), bound instance (`this::greet`; `expr::m` evaluates the receiver once and a
  null throws at creation), constructors (`Cube::new`), class-value virtual constructors
  (`k::create`), property getters (`Square::side`) and [Proposed] array constructors
  (`int[]::new`).
- **FUN-05 [Proposed]** Equality, which event `-=` relies on: method references are equal when
  they name the same method and bound receiver (C# delegates); a lambda equals only itself;
  non-capturing lambdas and static method references are cached per creation site.
- **FUN-06 [Decided, details Proposed]** Each lambda or method reference lowers to a synthesized
  final class implementing the interface, with fields for displays, `this` and the type context;
  inside generic code the class is generic over the enclosing type parameters. No
  `invokedynamic`-style machinery exists.
- **FUN-07 [Decided]** A lambda body throws only what the target method declares (ex §7.3).
- **FUN-08 [Decided]** The standard functional interfaces live in `reflect.functions` (ex §7.1);
  reified generics make primitive specializations unnecessary.

## 14. Unsafe code and natives (UNS)

- **UNS-01 [Decided, details Proposed]** A module that declares `unsafe;` may contain unsafe code
  (ex §11.4): pointer types `T*` over unmanaged types (primitives, pointers, structs without
  references), `unsafe { }` blocks and `unsafe` members, `*p`, `p[i]`, pointer arithmetic,
  `stackalloc`, casts between pointers and `long`/`ulong`, `&` of unmanaged locals, and `extern`
  functions. `->` is the lambda arrow, so members are reached with `(*p).next` or, [Proposed],
  `p.next`, since `.` on a pointer has no other meaning.
- **UNS-02 [Decided, Q18]** What `unsafe` gates:
  1. `unsafe;` is needed to declare or call members with pointer types and to use `unsafe`
     blocks, `stackalloc` and `extern`;
  2. library members that break memory safety without pointer types, such as `Natives.alloc` and
     `free`, are marked `@Unsafe` and need an unsafe caller;
  3. proxies and emit need it until the verifier ships (v0.7); reading annotations never does
     (Q10);
  4. the VM loads unsafe modules only when allowed (`--allow-unsafe`, BLD-04).
- **UNS-03 [Decided]** `native` methods (ex §2.11) are declared in Reflect and provided by the
  runtime, bound by qualified name and signature; only trusted stdlib modules declare them
  (LIB-03). They are Java methods in VM0 and Reflect code in `rfl`, never C.
- **UNS-04 [Decided, Q28]** `extern static` binds a Linux system call (ex §11.4: `mmap` becomes a
  wrapper over syscall 9, x86-64 syscall convention); there is no libc. Calling C libraries waits
  until after v1.0 and needs Reflect's own dynamic loader.
- **UNS-05 [Decided, Q04]** Pointers are 8 bytes, so `sizeof` is constant for every unmanaged
  type (`sizeof(PageHeader)` is 16). [Proposed] Struct layout is sequential with natural
  alignment, the size rounded up to the largest field alignment.
- **UNS-06 [Proposed]** Once the collector may move objects, a pointer into a managed object needs
  pinning: `fixed (int* p = &xs[0]) { }` (`fixed` is reserved). Under the non-moving collector a
  pin costs nothing, but code must already use `fixed`.

## 15. Concurrency and async (CON)

- **CON-01 [Decided, Q07]** One thread until after v1.0; `volatile`, `synchronized`, `async` and
  `await` are reserved (ex §7.6, §7.7).
- **CON-02 [Proposed]** Constraints that keep threads possible: Java's memory model (Manson,
  Pugh, Adve) as the baseline, written before threads ship; per-class initialization states with
  the running thread recorded (vm.md §7); safepoint polls at back-edges and calls (memory.md §4);
  run-time state in one `Runtime` object; collections and events not thread-safe by default; the
  lock model of CON-04.
- **CON-03 [Decided, details Proposed]** `async` and `await` come after the preview as C#-style
  state machines (ex §7.6); iterators (STM-06) share the lowering.
- **CON-04 [Decided, Q37]** Lockability is opt-in through `reflect.core.Lockable`, a marker
  interface the VM knows:
  - `synchronized (x) { }` (after v1.0) needs `x`'s static type to be a subtype of `Lockable`;
  - lockability is inherited by subclasses, interfaces may extend `Lockable`, and any class may
    implement it, `Destructible` subclasses included; structs cannot;
  - the VM appends a hidden lock word to the layout of the first class in a hierarchy that
    implements it (vm.md §12), so other objects carry no lock state and headers stay two words;
  - `Lock` is a ready-made final class implementing it, for private lock objects (ex §7.7);
  - an analyzer warns on `synchronized (this)` in public lockable types, because any holder of a
    reference can take that lock (Java's pitfall).
