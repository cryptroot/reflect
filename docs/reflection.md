# Reflection and metaprogramming

Reflection at run time and at compile time through one API, plus annotations, generators,
compile-time evaluation, analyzers, proxies, emit and hot reload (proposal items 2, 7 and 8).
Conventions: [architecture.md](architecture.md) §0.

## 1. API surface (`reflect.meta`)

- **REFL-02 [Decided, Q30, Q36; details Proposed]** The mirror types live in namespace
  `reflect.meta` inside module `reflect.core` (LIB-02), next to `Class<T>`. Collections of mirrors
  are `ReadOnlyList<T>` (stdlib.md §4). Abridged:

  ```csharp
  // file: stdlib/reflect.core/meta/Mirrors.rfl (abridged)
  namespace reflect.meta;

  public enum Phase { COMPILE_TIME, RUN_TIME }
  public enum TypeKind { CLASS, INTERFACE, STRUCT, ENUM, RECORD, ANNOTATION, ARRAY, TUPLE,
                         INTERSECTION, TYPE_PARAMETER, WILDCARD, NULLABLE, POINTER }

  public interface AnnotatedElement {
      A? getAnnotation<A extends Annotation>();
      boolean hasAnnotation<A extends Annotation>();       // metadata only
      ReadOnlyList<Annotation> annotations { get; }
  }

  public interface Type extends AnnotatedElement {        // also type parameters, wildcards, T?
      String name { get; }                                 // canonical spelling (GEN-12)
      TypeKind kind { get; }
      Phase phase { get; }
      boolean isValueType { get; }
      boolean isNullable { get; }
      ReadOnlyList<Type> typeArguments { get; }
      boolean isAssignableFrom(Type other);
      boolean hasOperator(String symbol);
  }

  public abstract class Member implements AnnotatedElement {
      public abstract String name { get; }
      public abstract Class<?> declaringClass { get; }
      public abstract Class<?> reflectedClass { get; }    // R for static members (REFL-04)
      public abstract boolean isStatic { get; }
      public abstract boolean isVirtual { get; }
      public abstract boolean isPublic { get; }
      public abstract boolean isFinal { get; }
      public abstract Class<?>? generatedBy { get; }       // ex §6.7
  }

  public abstract class Field extends Member {
      public abstract Type type { get; }
      public abstract Object? get(Object? target) throws IllegalAccessException;
      public abstract void set(Object? target, Object? value) throws IllegalAccessException;
  }

  public abstract class Property extends Member {
      public abstract Type type { get; }
      public abstract Object? get(Object? target) throws IllegalAccessException, InvocationTargetException;
      public abstract void set(Object? target, Object? value) throws IllegalAccessException, InvocationTargetException;
      public abstract Property implementationFor(Class<?> receiver);
  }

  public abstract class Method extends Member {
      public abstract Type returnType { get; }
      public abstract ReadOnlyList<Parameter> parameters { get; }
      public abstract Object? invoke(Object? target, Object?... args)
              throws IllegalAccessException, InvocationTargetException;
      public abstract Method implementationFor(Class<?> receiver);
  }

  public abstract class Constructor extends Member {
      public abstract ReadOnlyList<Parameter> parameters { get; }
      public abstract Object invoke(Object?... args) throws IllegalAccessException, InvocationTargetException;
  }

  public abstract class Event extends Member {
      public abstract Type type { get; }
      public abstract void add(Object? target, Object listener) throws IllegalAccessException;
      public abstract void remove(Object? target, Object listener) throws IllegalAccessException;
  }

  public interface Parameter extends AnnotatedElement {
      String name { get; }
      Type type { get; }
      RefKind refKind { get; }                             // NONE, REF, IN, OUT
      boolean isVariadic { get; }
  }
  ```

- **REFL-13 [Proposed]** `Class<T>` (namespace `reflect.core`) implements `Type` and adds:
  `module`, `namespace`, `superclass`, `interfaces`, `genericDefinition`,
  `instantiate(Class<?>... arguments)`, the lists `fields`, `properties`, `methods`,
  `constructors`, `events`, lookups `getField(name)`, `getProperty(name)`, `getEvent(name)`,
  `getMethod(name, Class<?>... parameterTypes)`, `getConstructor(Class<?>... parameterTypes)`,
  `isInstance`, `cast`, and `create(...)` for virtual constructors (SVD-06). Lookups return `null`
  when nothing matches (hence the `!` in ex §10). A name-only `getMethod(name)` throws
  `AmbiguousMatchException` (unchecked) when the name is overloaded (Q36), so ex §10.2 needs
  `getMethod("add", Cube.class)`. Synthesized members (backing fields, lambda classes, `A$Impl`,
  bridges of MEM-02) are listed only on request.

## 2. Phases, implementations and access

- **REFL-01 [Decided]** The API has two implementations (Bracha and Ungar's mirrors): compiler
  symbols inside `rflc` (CMP-01) and VM metadata in a running program (ex §6.7). `phase` tells them
  apart. Operations that need a running program (`get`, `set`, `invoke`, `create`, `add`,
  `remove`) throw `PhaseException` at compile time.
- **REFL-03 [Decided, details Proposed]** Access (ex §10.3): lookups find every member, private
  ones included; access is checked at use against the caller's module. Public members of exported
  namespaces are always accessible, everything in a namespace opened to the caller too; the rest
  throws `IllegalAccessException`. There is no `setAccessible`-style override. `Field.set` on a
  final field throws `IllegalAccessException` even where the namespace is open (BC-23), and on a
  box without identity it throws `IllegalArgumentException`, since such boxes are immutable
  (DAT-09).
- **REFL-09 [Proposed]** Caller-sensitive operations find the caller's module from its frame
  (Java's `@CallerSensitive`); the JIT records the caller when it inlines them.
- **REFL-04 [Decided]** Static virtual members (ex §10.1): `Property.get(null)` and
  `Method.invoke(null, ...)` on statics use `reflectedClass` as `R`; `implementationFor(C)` returns
  the override used when `R` is `C`.

## 3. Generators and templates

- **META-04 [Decided, details Proposed]** Generators (ex §6.2, §6.3):
  - a generator implements `ClassGenerator<A>`, lives in a module compiled earlier and is bound to
    `A` by `@GeneratedBy`; `rflc` loads it into a child layer of the VM it runs on and never runs
    it in its own module;
  - `generate(target, members, ctx)` runs once per partial class that has `A` on itself or on a
    member, and sees declarations plus members added by generators ordered before it;
  - it only adds members, interfaces and diagnostics, never edits or removes code (Roslyn's model);
  - every part of a class says `partial`, and each partial member is completed by exactly one
    generator (ex §5.5);
  - field shorthand: in a partial class, `@Observable public int value = 1;` becomes
    `public partial int value { get; set; } = 1;` and the annotations move to the property; a
    `final` field cannot be used this way;
  - `@Generator(after = ...)` edges form a DAG (a cycle is an error); unordered generators run in
    a fixed order (module, then qualified name) and must not read each other's output;
  - options come from `-G name=value` or `option` in `workspace.rfl` (BLD-02) and reach the
    generator as `ctx.option("name")`; `--emit-generated DIR` writes one `X.g.rfl` per class.

  ```csharp
  // file: stdlib/reflect.meta.compile/Generators.rfl (abridged)
  namespace reflect.meta.compile;

  public record Annotated<A extends Annotation>(Member member, A annotation) { }

  public interface ClassGenerator<A extends Annotation> {
      void generate(Class<?> target, List<Annotated<A>> members, GeneratorContext ctx)
              throws GeneratorException;
  }

  public interface GeneratorContext {
      void add(Template<Member> member);
      void addInterface(Class<?> type);
      Name freshName(String hint);
      String? option(String name);
      String readFile(String path) throws IOException;     // declared inputs only (§9)
      void report(Diagnostic diagnostic);
  }
  ```

- **META-05 [Decided]** Templates: `quote member { }`, `quote expr { }` and `quote stmt { }` build
  `Template<Member>`, `Template<Expr>` and `Template<Stmt>`. A splice `${e}` inserts according to
  the static type of `e`:

  | Type of `e` | Inserted as |
  |---|---|
  | `Template<Expr>`, `Template<Stmt>`, `Template<Member>` | that syntax |
  | an `Iterable` of templates | the sequence |
  | `Name` | an identifier |
  | `Type`, `Class<?>` | a type reference |
  | `String`, primitives, enum constants | a literal |

  Any other type is an error in the generator's source.
- **META-06 [Decided, Q25]** Hygiene: a name that resolves at the quote's site to a type,
  namespace or static member binds there and is emitted fully qualified; every other name
  resolves in the target; `ctx.freshName` makes collision-free locals. Generated code never
  depends on the target's imports.
- **REFL-12 [Proposed]** When `rflc` compiles a generator, each `quote` becomes code that builds a
  syntax tree with holes, its definition-site names already qualified. At generation time
  `ctx.add` hands the tree to `rflc`, which fills the holes, then declares and binds the result
  like source.

## 4. Annotations

- **META-01 [Decided, details Proposed]** Annotation types use Java's `@interface` (ex §6.1).
  Elements are methods with optional `default`s; element types are primitives, `String`,
  `Class<?>`, enums, annotations and arrays of those. Retention is `SOURCE`, `CLASS` or `RUNTIME`
  (the default; BC-13). Targets are Java's plus `PROPERTY` and `EVENT`; no type-use annotations in
  v1. Arguments are constant expressions, `const` members included (§5). `@Repeatable` and
  `@Inherited` behave as in Java.
- **META-02 [Decided]** `hasAnnotation<A>()` reads metadata only; `getAnnotation<A>()` returns an
  instance or `null`.
- **META-03 [Decided, Q10]** Instances come from a generated class, not a proxy. For each
  annotation type `A`, `rflc` emits a hidden final class `A$Impl` in `A`'s module: one final field
  per element, a constructor taking every element, `annotationType()`, and `equals`, `hashCode` and
  `toString` following Java's `Annotation` contract. `getAnnotation<A>()` decodes the value blob,
  fills defaults from `A`'s metadata, builds an `A$Impl` and caches it per element; an array
  element returns a copy on every read. It is ordinary verified code, so readers need no `unsafe;`
  and element reads are field reads. Inside `rflc` the same class is built from symbol values,
  because `A`'s module is always compiled before its users.
- **META-11 [Proposed]** `@Observable(classWide = false)` means the property raises only its own
  `xChanged` event, not `propertyChanged`; `ObservableGenerator` reads it.
- **META-12 [Proposed]** Annotations the toolchain knows:

  | Annotation | Module | Effect |
  |---|---|---|
  | `@Target`, `@Retention`, `@Repeatable`, `@Inherited` | core | annotation declarations |
  | `@GeneratedBy`, `@Generator` | core, meta.compile | generator binding and order (§3) |
  | `@FunctionalInterface` | functions | checked marker (FUN-01) |
  | `@Flags` | core | flag enums (DAT-05) |
  | `@Inline`, `@ForceInline` | core | JIT and AOT hints (META-10) |
  | `@Precompile` | core | eager compilation (JIT-03) |
  | `@Unsafe` | core | callers need an unsafe context (UNS-02) |
  | `@Uninterruptible` | VM only | Reflect-S regions (BOOT-08) |
  | `@Observable` | observable | property-change generator (MEM-17) |
  | `@Test` | testing | unit test (testing.md) |
- **META-10 [Proposed]** `@Inline` and `@ForceInline` (ex §6.6) are hints to the optimizing
  compiler and the JIT. `rflc` never inlines across methods when emitting bytecode, which would
  break binary compatibility, hot reload and reflection's view of methods.

## 5. Compile-time evaluation

- **META-07 [Decided, Q27]** `const` (ex §6.5):
  - `const` methods call only `const` methods; `const` fields are static and evaluated by `rflc`;
    `nameof` and `sizeof` are constants; recursion limits, cycles and non-constant types are
    errors; `static final` stays a run-time constant;
  - constant-pool types: primitives, `String`, `Class<?>`, enums, and read-only arrays of these;
  - a `const` array is declared `ReadOnlyArray<T>`, because a plain array would let
    `Tables.SMALL_PRIMES[0] = 1` change a constant:
    `public const ReadOnlyArray<int> SMALL_PRIMES = { 2, 3, 5, 7 };`;
  - enum constants are constant expressions, and a `const` method of an enum folds on a constant
    receiver (DAT-05).
- **REFL-11 [Proposed]** `rflc` compiles the `const` methods a module needs (their dependency
  graph is known), loads them into a temporary child layer, runs them with an instruction budget
  and a recursion limit, and serializes the results into `Constant` rows. Strict IEEE and a
  Reflect-written `Math` (NUM-04) make the results equal to run time. Stage0 has no `const`
  methods (BOOT-02).

## 6. Analyzers

- **META-08 [Decided, details Proposed]** Analyzers (ex §6.4) implement `Analyzer<T>` for `Field`,
  `Method` and other members, report through `DiagnosticSink`, and read a read-only view of bound
  bodies (`m.body.calls`). They are registered with `provides reflect.meta.compile.Analyzer with
  ...` and enabled by `requires static` on their module. Codes from `RFL1000` up (CMP-02);
  `-W RFL1001=error` or `severity` in `workspace.rfl` changes a severity. The standard set lives in
  `reflect.lint`: mutable public fields (`RFL1001`), ignored `@CheckResult` results (`RFL1002`),
  `==` on `Object` or interface operands (OPR-04), `synchronized (this)` in public lockable types
  (CON-04).

## 7. Proxies, emit and reflective calls

- **REFL-05 [Decided]** Proxies (ex §10.4), module `reflect.meta.dynamic`: `Proxy.create<I>(handler)`
  builds a class implementing `I` in a child layer; the handler is declared `throws Throwable`, and
  an undeclared checked exception reaches the caller as `UndeclaredThrowableException`. A module
  using proxies needs `unsafe;` until the verifier ships in v0.7 (UNS-02).
- **REFL-06 [Decided]** Emit (ex §10.5), module `reflect.meta.dynamic`: `ModuleBuilder` drives the
  `reflect.bytecode` writer, and `load()` puts the module in a new child layer (MOD-10). Before
  v0.7 it needs `unsafe;`; afterwards emitted code is verified like any other.
- **REFL-08 [Proposed]** Reflective calls: the interpreter copies the argument array into typed
  frame slots; compiled code uses one stub per signature, generated lazily or by the AOT compiler.
  Stubs check access, unbox arguments, reject `null` for non-null parameters
  (`IllegalArgumentException`) and wrap exceptions from the callee in
  `InvocationTargetException`.

## 8. Hot reload

- **REFL-07 [Decided, details Proposed]** `rflc delta --base old.rflc module` writes a delta, a
  module file flagged `delta` that names its base by content hash, which leaves DEBUG out, so a
  delta also applies to a stripped base (BC-22). `HotReload.apply` (ex §10.6) accepts additions
  only in v0: new types, methods and fields (side storage for existing objects), and replaced
  method bodies; running frames finish the old body. Removing members or changing a signature, a
  base class or a layout is rejected. A delta never re-runs an initializer and never writes a
  final field of an existing object or class (BC-23). [Decided, Q64] Hot reload does not
  guarantee null safety in v0: an added field starts at zero in objects created before the delta,
  so an added non-null reference field reads `null` there. Dereferences still check null, so
  memory safety holds. [Proposed] `rflc delta` warns about each such field. The VM's side of it:
  vm.md §11.

## 9. Determinism and sandboxing

- **META-09 [Decided, details Proposed]** Under `--deterministic`, generator file access is
  confined to declared inputs, which the depfile records (ex §12.2); clocks, randomness, the
  environment and the network throw `DeterminismViolation`; run-time-only reflection throws
  `PhaseException`. All compile-time code has instruction and memory budgets.
- **REFL-10 [Proposed]** The sandbox is the layer: each generator and `const` evaluation runs in a
  child layer whose policy the stdlib's natives consult before touching files, clocks or the
  network. Determinism therefore needs no special VM mode, only the policy object.

