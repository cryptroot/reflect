# Standard library

What each standard module contains. Layering and the dependency rules: [architecture.md](architecture.md)
§6; conventions: §0 there.

## 1. Principles

- **LIB-06 [Proposed]** Java's names and shapes, with C#'s strengths where the language has them:
  properties instead of getters (`size`, `length`, `isEmpty`), indexers (`list[0]`, `map[key]`),
  operators on value types, reified generics instead of primitive specializations.
- **LIB-07 [Proposed]** No host libraries: everything is Reflect over the VM's natives and Linux
  system calls (UNS-03, UNS-04). Formatting and parsing are culture-invariant (BOOT-05).
- **LIB-03 [Decided]** Only trusted stdlib modules declare natives (architecture.md §6).

## 2. `reflect.core`

Everything the language or the VM depends on (ARC-10), in namespace `reflect.core` unless noted:

| Area | Types |
|---|---|
| language roots | `Object`, `String`, `Array`, `Annotation`, `Enum` support, `Record` support, the nullable wrapper, `Tuple1` to `Tuple8`, `ListenerList<T>` (field-like events, MEM-15) |
| primitives | `Boolean`, `Char`, `Int8` to `UInt64`, `Float32`, `Float64` (NUM-01) and the generic-math interfaces (§3) |
| memory | `Span<T>`, `ReadOnlySpan<T>`, `ReadOnlyArray<T>` (META-07), `WeakReference<T>`, the weak-keyed table (GC-05), `GC`, `Natives` (`@Unsafe`, UNS-02) |
| iteration | `Iterable<T>`, `Iterator<T>`, `ReadOnlyCollection<T>`, `ReadOnlyList<T>` (§4) |
| text basics | `StringBuilder`, `Objects`, the interpolation builder |
| errors | `Throwable`, `Exception`, `RuntimeException`, `Error` and the exceptions of type-system.md §12 |
| runtime | `Class<T>`, `Runtime` (`idle()`, exit, arguments, environment), `Destructible` (DAT-08), `Lockable` and `Lock` (CON-04), `AutoCloseable`, `Math` (written in Reflect, NUM-04) |
| reflection | namespace `reflect.meta`: `Type`, `Member`, `Field`, `Property`, `Method`, `Constructor`, `Event`, `Parameter`, `AnnotatedElement`, `Phase`, `TypeKind` ([reflection.md](reflection.md) §1) |

## 3. Generic math

- **LIB-05 [Decided, Q39]** Per-operator interfaces plus aggregates, .NET's design trimmed. Each
  interface uses the self-type pattern `T extends X<T>` and static abstract members (SVD-08):

  | Interface | Members |
  |---|---|
  | `Equatable<T>` | `operator ==`, `operator !=` (ex §4.7) |
  | `Comparable<T>` | instance `compareTo(T)` (Java) |
  | `Ordered<T>` | `operator <`, `>`, `<=`, `>=` |
  | `Addable<T>` | `zero`, `operator +`, `operator checked +` (ex §4.6) |
  | `Subtractable<T>` | `operator -`, `operator checked -` |
  | `Multipliable<T>` | `one`, `operator *`, `operator checked *` |
  | `Divisible<T>` | `operator /` |
  | `Remainder<T>` | `operator %` |
  | `Negatable<T>` | unary `operator -`, `operator checked -` |
  | `Incrementable<T>` | `operator ++`, `operator --` |
  | `Bitwise<T>` | `operator &`, `\|`, `^`, `~` |
  | `Shiftable<T>` | `operator <<`, `>>`, `>>>` with an `int` count |
  | `Bounded<T>` | `MIN_VALUE`, `MAX_VALUE` |
  | `Parsable<T>` | `parse(String)`, `tryParse(String, out T)` (ex §3.1) |

  | Aggregate | Combines |
  |---|---|
  | `Number<T>` | `Equatable`, `Comparable`, `Ordered`, `Addable`, `Subtractable`, `Multipliable`, `Divisible`, `Negatable`, `Parsable`, and conversions `fromChecked<U extends Number<U>>(U)`, `fromWrapping`, `fromSaturating` |
  | `Integral<T>` | `Number`, `Remainder`, `Incrementable`, `Bitwise`, `Shiftable`, `Bounded` |
  | `FloatingPoint<T>` | `Number`, `Remainder`, `Bounded`, plus `NaN`, the infinities, `isNaN`, `isInfinite` |

  The integer primitives implement `Integral<T>`, `float` and `double` implement
  `FloatingPoint<T>`; `char` implements `Equatable`, `Comparable`, `Ordered`, `Incrementable` and
  `Bounded`; `boolean` implements `Equatable`. Signed types also implement `Negatable`. ex §3.1's
  `T extends Number & Parsable<T>` becomes `T extends Number<T>` (sketchpad A.6).

## 4. Collections

- **LIB-04 [Decided, Q42]** Java's model plus read-only supertypes (C#'s `IReadOnlyList`
  pattern), so parameters can accept read-only views while the examples stay unchanged:

  ```mermaid
  flowchart BT
      ROC["ReadOnlyCollection (core)"] --> IT["Iterable (core)"]
      ROL["ReadOnlyList (core)"] --> ROC
      ROS["ReadOnlySet"] --> ROC
      C["Collection"] --> ROC
      L["List"] --> C
      L --> ROL
      S["Set"] --> C
      S --> ROS
      M["Map"] --> ROM["ReadOnlyMap"]
  ```

  - Read-only interfaces have no mutators; mutable ones add them. `ReadOnlyArray<T>` implements
    `ReadOnlyList<T>`, and the reflection mirrors return read-only lists.
  - Implementations: `ArrayList`, `ArrayDeque`, `HashSet`, `HashMap`, `TreeMap`, `TreeSet`,
    `PriorityQueue`. `List.of`, `Set.of` and `Map.of` return immutable instances of the mutable
    interfaces that throw `UnsupportedOperationException` on mutation (Java), as ex §3.1 passes
    `List.of(...)` where a `List<S>` is expected.
  - `map[key]` throws `NoSuchElementException` when the key is absent, since `V` may be non-null;
    `map.get(key)` returns `V?`.
  - Iterators are fail-fast; `Iterator` adds `remove()` only on mutable collections.
  - [Decided, Q65] The default iterators of the collections and of arrays are structs that
    implement `Iterator<T>`, like C#'s enumerators. They arrive with structs in v0.4 (BOOT-02);
    until then they are classes. `ArrayList<T>.iterator()` returns `ArrayListIterator<T>`, so an
    enhanced `for` over an `ArrayList<T>` does not box (STM-04). Through `Iterable<T>` or
    `List<T>`, a synthesized bridge boxes the struct once, into a box with identity that `next()`
    updates in place (MEM-02, DAT-09). `next()` is `mutating`, so on an `ArrayList<T>`,
    `list.iterator().next()` is an error, since a method result is read-only (DAT-02); store the
    iterator in a local first.

  ```reflect
  // file: stdlib/reflect.core/Iterator.rfl (abridged)
  public interface Iterator<T> {
      boolean hasNext();
      mutating T next();                   // a struct may implement it with a mutating method
  }

  // file: stdlib/reflect.collections/ArrayListIterator.rfl (abridged)
  public struct ArrayListIterator<T> implements Iterator<T> {
      private final ArrayList<T>? list;    // struct reference fields are nullable (DAT-01)
      private int index;
      ArrayListIterator(ArrayList<T> list) { this.list = list; }
      public override boolean hasNext() { return list != null && index < list.size; }
      public mutating override T next() { return list![index++]; }
  }

  // file: stdlib/reflect.collections/ArrayList.rfl (abridged)
  public class ArrayList<T> implements List<T> {
      public override ArrayListIterator<T> iterator() { return new ArrayListIterator<T>(this); }
  }
  ```
  - `ServiceLoader` (MOD-11) and `Comparator<T>` live here.

## 5. Text and input/output

- **LIB-08 [Proposed]** `reflect.text`: number formatting and parsing (`Doubles.toString(d, 2)`,
  ex §2.6), the .NET-style custom numeric formats used by interpolation (NUM-05), UTF-8 encoding
  and decoding, string utilities. All culture-invariant.
- **LIB-09 [Proposed]** `reflect.io`: `Console` (ex §2.11), `Path`, `Files` (`readString`,
  `readAllLines`, `openRead`, `openWrite`), `InputStream`, `OutputStream`, `Reader`, `Writer`, and
  `IOException` with `FileNotFoundException` and `AccessDeniedException` (ex §7.3), all over system
  calls; error numbers map to exceptions in one place (VM-22).

## 6. Other modules

| Module | Contents |
|---|---|
| `reflect.functions` | the functional interfaces of ex §7.1 and `WeakListener` (MEM-16) |
| `reflect.bytecode` | module reader and writer, assembler, disassembler, verifier ([bytecode.md](bytecode.md)) |
| `reflect.meta.dynamic` | `Proxy`, `ModuleBuilder`, `HotReload`, `ModuleDelta`, layers ([reflection.md](reflection.md) §7, §8) |
| `reflect.meta.compile` | `ClassGenerator`, `GeneratorContext`, `Template`, `Name`, `Expr`, `Stmt`, `Member`, `Annotated`, `Analyzer`, `DiagnosticSink` |
| `reflect.observable` | `@Observable`, `ObservableGenerator`, `PropertyChange<T>`, `PropertyChangeSource` (ex §5.4) |
| `reflect.lint` | the standard analyzers and `@CheckResult` (reflection.md §6) |
| `reflect.json` | JSON reading and writing through reflection (ex §10.3) |
| `reflect.testing` | `@Test`, `Assert`, the unit-test runner ([testing.md](testing.md)) |

## 7. Unscheduled rendering modules

The [rendering proposal](proposals/rendering.md) describes optional `reflect.platform`,
`reflect.render.rhi`, `reflect.render.2d`, `reflect.ui.gui` and `reflect.ui.terminal` modules.
They separate native platform integration, shared profile-aware rendering and frontend UI
semantics. These names and their packaging are proposed, not additions to the baseline module
graph. Terminal-only builds do not require the GUI, a GPU or window-system libraries; none of
these modules becomes a dependency of `reflect.core`, the compiler or the baseline VM.
Native backends remain subject to LIB-07 and the proposal's explicit platform/dependency gate.
