# Examples

Working document holding every code example of the design. Each example is written as Reflect
source that should compile once `rflc` exists, so it doubles as a review of the decisions.

## 1. How to read the examples

- `csharp` fences contain Reflect (the label only enables highlighting); `text` fences contain
  bytecode assembly or console output; `java` fence contains stage0 input.
- A block starting with `// file: <path>` is a complete source file of the example module `demo`
  . Blocks without that line are fragments of the nearest file above.
- Blocks starting with `// rejected` do not compile; each `// error:` line names the rule that
  rejects it. Blocks starting with `// future` show syntax planned after the preview; blocks
  starting with `// lowered` show compiler output in Reflect-like syntax. Every other block is
  meant to compile.
- `// prints: x` is console output, `// throws: X` a run-time exception.

## Contents

1. How to read the examples
2. Program structure
3. Generics and reification
4. Static virtual members
5. Properties, events, `@Observable`
6. Annotations, generators, compile-time evaluation
7. Lambdas, exceptions, cleanup, concurrency
8. Operators
9. Data modeling
10. Reflection API (run time)
11. Under the hood: lowering and bytecode
12. Tooling and tests

## 2. Program structure

### 2.1 Modules

One module is one `.rflc` file; the descriptor is Java's `module-info` plus `unsafe;` and `main`

```csharp
// file: demo/module-info.rfl
module demo {
    version "0.1.0";
    requires reflect.collections;        // reflect.core is implicit
    requires reflect.meta.dynamic;       // proxies, emit, hot reload
    requires reflect.observable;         // @Observable, its generator and PropertyChange
    requires static reflect.lint;        // compile time only: analyzers
    requires static demo.tools;          // compile time only: @AutoToString and its generator
    exports demo.shapes;                 // public types here are visible to other modules
    provides demo.shapes.Shape with demo.shapes.Square;   // a service provider
    opens demo.model;                    // private members here are reflectively accessible
    opens demo.bank to reflect.json;     // qualified opens (Java)
    unsafe;                              // gates pointer types, unsafe blocks, stackalloc, extern, @Unsafe members, proxies and emit (UNS-02)
    main demo.App;                       // default entry class for `rfl run`
}
```

```csharp
// file: reflect.lint/module-info.rfl
module reflect.lint {
    requires transitive reflect.meta.compile;   // the analyzers' public signatures use its types
    exports reflect.lint;
    provides reflect.meta.compile.Analyzer
        with reflect.lint.MutablePublicFieldAnalyzer, reflect.lint.IgnoredResultAnalyzer;
}
```

```csharp
// file: plugins/module-info.rfl
module plugins.host {
    requires demo;
    uses demo.shapes.Shape;              // service lookup through the module system
}
```

```csharp
// file: plugins/host/Gallery.rfl
namespace plugins.host;

import demo.shapes;
import static reflect.io.Console;

public final class Gallery {
    public static void show() {
        for (var provider : ServiceLoader.load<Shape>()) {   // List<Provider<Shape>>
            println(provider.type.kindName);                 // prints: SQUARE (a static, read before any instance exists)
            Shape shape = provider.get();                    // calls Square.provider()
            println(shape.describe());                       // prints: square: 1.00
        }
    }
}
```

### 2.2 Namespaces and aliases

Namespaces are lowercase, file-scoped or block form, and hold types only.

```csharp
// file: demo/shapes/Catalog.rfl
namespace demo.shapes;                   // file-scoped: applies to the whole file

import reflect.collections;               // import a namespace
import static reflect.io.Console;         // import static members: println(...)
using StringMap = HashMap<String, String>;              // alias; may use the imports above
using IntMap<V> = HashMap<int, V>;                      // generic alias
using Lookup<V extends class> = HashMap<String, V?>;    // bounds and kinds as on generic types

public final class Catalog {
    public static void print(StringMap entries) {
        for (String key : entries.keys) { println($"{key} = {entries[key]}"); }
    }

    // bySides is a HashMap<int, ? extends Shape>, byName a HashMap<String, Shape?>
    public static void count(IntMap<? extends Shape> bySides, Lookup<Shape> byName) {
        println(bySides.size + byName.size);
        println(StringMap.class == HashMap<String, String>.class);   // prints: true (an alias is no new type)
    }
}
```

```csharp
// rejected
namespace demo.bad;

import reflect.collections;
using StringMap = HashMap<String, String>;
using Names = StringMap;                 // error: an alias target cannot use another alias
using Cache<V> = HashMap<String, V?>;    // error: V? needs V extends class or V extends struct
using Unused<T> = String;                // error: T does not occur in the target
using Lookup<V extends class> = HashMap<String, V?>;
using Mirror<T> = HashMap<T, T>;

final class Lookups {
    void ids(Lookup<int> ids) { }        // error: int violates the alias's bound V extends class
    void pairs(Mirror<?> pairs) { }      // error: wildcard for T, which occurs twice in the target
}
```

```csharp
// file: demo/Blocks.rfl
namespace demo {                         // block form; several blocks per file
    import static reflect.io.Console;     // also applies inside nested blocks

    public final class Units { public const String SUFFIX = "cm"; }

    namespace geometry {                 // nested block: demo.geometry
        public final class Origin {
            public static void show() { println($"(0, 0) {Units.SUFFIX}"); }   // demo.Units without `import`
        }
    }
}

namespace demo.measure {
    public final class Ruler { }
    final class Scratch { }              // warning: hides the global Scratch inside demo.measure
}

final class Scratch { }                  // outside every block: global namespace, never exported
```

```csharp
// rejected
namespace demo {
    final class Draft { }                // package-private: namespace demo only

    namespace geometry {
        final class Sketch {
            Draft? draft;                // error: Draft is found  but not accessible from demo.geometry
        }
    }
}
```

```csharp
// rejected
namespace demo.bad;
namespace demo.other { }                 // error: a file has one file-scoped namespace or else blocks
public static void helper() { }          // error: no top-level functions
```

### 2.3 Visibility

`public`, `protected`, package-private (no modifier) and `private`

```csharp
// file: demo/bank/Vault.rfl
namespace demo.bank;                     // not exported: public types are module-internal

public class Vault {
    public String name { get; }          // all of module demo (other modules too, if exported)
    protected long reserve;              // subclasses, plus demo.bank inside module demo
    long audited;                        // package-private: demo.bank inside module demo
    private long secret;                 // Vault and its nested types

    public Vault(String name) { this.name = name; }

    class Ledger {                       // nested types see private members (Java)
        long peek(Vault v) { return v.secret + v.audited; }
    }
}
```

```csharp
// rejected
namespace demo.shop;

import demo.bank;

final class Teller {
    long look(Vault v) {
        return v.audited;                // error: package-private member, other namespace
    }
}
```

### 2.4 Entry point

```csharp
// file: demo/App.rfl
namespace demo;

import static reflect.io.Console;

public final class App {
    public static void main(String[] args) {      // or: public static int main(String[] args)
        String who = args.length > 0 ? args[0] : "world";
        println($"Hello, {who}!");                 // prints: Hello, world!
    }
}
```

`rfl run demo.rflc` starts the descriptor's `main` class; `--main demo.zoo.Zoo` overrides it.

### 2.5 Classes and members

Java declarations with C#'s dispatch defaults: non-virtual unless `virtual`/`abstract`, and
`override` everywhere.

```csharp
// file: demo/zoo/Animal.rfl
namespace demo.zoo;

public abstract class Animal {
    public String name { get; }                  // get-only auto-property, assignable in constructors
    protected int legs;                          // field
    private static int created;                  // static field: one storage, direct access
    public static int count { get { return created; } }

    static { created = 0; }                      // static initializer (Java)

    protected Animal(String name, int legs) {
        this.name = name;
        this.legs = legs;
        created++;
    }

    public abstract String sound();              // must be overridden
    public virtual String describe() {           // may be overridden
        return $"{name} says {sound()}";
    }
    public int legCount() { return legs; }       // non-virtual: compiled to a direct call
}
```

```csharp
// file: demo/zoo/Dog.rfl
namespace demo.zoo;

public final class Dog extends Animal {          // final: cannot be extended
    private final String breed;                  // final field: assigned once (C# `readonly`)

    public Dog(String name) { this(name, "mixed"); }
    public Dog(String name, String breed) {
        super(name, 4);                          // Java-style constructor chaining
        this.breed = breed;
    }

    public override String sound() { return "woof"; }

    public final override String describe() {    // final override: no further overriding
        return $"Dog ({breed}): " + super.describe();
    }
}
```

```csharp
// file: demo/zoo/Enclosure.rfl
namespace demo.zoo;

import reflect.collections;

public final class Enclosure {
    private final List<Animal> animals = new ArrayList<>();

    public class Tag {                           // nested: no `static`, never an outer instance
        public final String label;
        public Tag(String label) { this.label = label; }
        public int countIn(Enclosure e) { return e.animals.size; }   // private access (Java)
    }

    public void add(Animal a) { animals.add(a); }
}
```

```csharp
// file: demo/zoo/Zoo.rfl
namespace demo.zoo;

import static reflect.io.Console;

public final class Zoo {
    public static void main(String[] args) {
        Animal rex = new Dog("Rex");
        println(rex.describe());                 // prints: Dog (mixed): Rex says woof
        println(rex.legCount());                 // prints: 4
        println(Animal.count);                   // prints: 1

        var pen = new Enclosure();
        pen.add(rex);
        var tag = new Enclosure.Tag("north");    // created without an Enclosure
        println(tag.countIn(pen));               // prints: 1
    }
}
```

```csharp
// rejected
public class Cat extends Animal {
    public Cat() { super("Tom", 4); }
    public String sound() { return "meow"; }     // error: redeclares an inherited member without `override`
    public int describe;                         // error: one member namespace; `describe` is a method
}
public class Husky extends Dog { }               // error: Dog is final

public final class Enclosure2 {
    private int size;
    public class Counter {
        int read() { return size; }              // error: no outer instance
    }
}
```

### 2.6 Interfaces

Default methods, private helpers, properties, events and static abstract members.

```csharp
// file: demo/shapes/Shape.rfl
namespace demo.shapes;

import reflect.functions;
import reflect.text;

public interface Shape {
    double area();                               // implicitly public abstract
    String name { get; }                         // abstract property
    event Consumer<Shape> changed;               // abstract event

    default String describe() {                  // default method (Java keyword)
        return $"{name}: {format(area())}";
    }
    private static String format(double d) { return Doubles.toString(d, 2); }   // private helper

    static abstract String kindName { get; }     // static abstract member
}
```

```csharp
// file: demo/shapes/Square.rfl
namespace demo.shapes;

import reflect.functions;

public final class Square implements Shape {
    public Square(double side) { this.side = side; }

    public static Square provider() { return new Square(1); }   // service provider method

    public double side {
        get;
        set { field = value; changed(this); }    // raise the event inside the implementing class
    }

    public override double area() { return side * side; }        // `override` for interfaces too
    public override String name { get { return "square"; } }
    public override event Consumer<Shape> changed;
    public static override String kindName { get; } = "SQUARE";  // static abstract implemented
}
```

Default-method conflicts follow Java: the class resolves them with its own `override`.

```csharp
// file: demo/shapes/Labels.rfl
namespace demo.shapes;

public interface Named  { default String label() { return "named"; } }
public interface Tagged { default String label() { return "tagged"; } }

public final class Both implements Named, Tagged {
    public override String label() {             // required: two defaults conflict
        return Named.super.label() + "+" + Tagged.super.label();   // name the parent
    }
}

public final class OnlyNamed implements Named {
    public override String label() { return $"[{super.label()}]"; }   // Named.label(): the one parent with it
}

public class Plain { public virtual String label() { return "plain"; } }

public final class Fancy extends Plain implements Named {
    public override String label() { return super.label() + "!"; }   // Plain.label(): the class member wins
}
```

```csharp
// rejected
namespace demo.shapes;

public final class Either implements Named, Tagged {
    public override String label() { return super.label(); }   // error: Named and Tagged both provide label
}
```

### 2.7 Primitive types and literals

Java's primitives plus unsigned types; wrap-around by default, `checked` traps.

```csharp
// file: demo/basics/Literals.rfl
namespace demo.basics;

import static reflect.io.Console;

public final class Literals {
    public static void main(String[] args) {
        boolean ok = true;
        byte b = -1;                     // signed 8-bit (Java)
        ubyte ub = 255;                  // unsigned 8-bit
        short s = 1_000;
        ushort us = 0xFFFF;
        int i = 42;
        uint ui = 42u;                   // `u` suffix
        long l = 9_000_000_000L;
        ulong ul = 9_000_000_000UL;
        int mask = 0b1010_0101;
        float f = 1.5f;
        double d = 2.5e-3;
        char c = 'A';                    // one UTF-16 code unit
        char omega = '\u03A9';
        String str = "tab\there, \"quoted\"\n";   // UTF-16 string, a class like any other

        int max = int.MAX_VALUE;         // primitives are structs with static members
        Int32 same = Int32.MAX_VALUE;    // int is an alias of reflect.core.Int32
        int wrapped = max + 1;           // wraps to int.MIN_VALUE
        long wide = i;                   // implicit widening
        byte narrow = (byte) 300;        // explicit narrowing: 44
        long mixed = i + ui;             // int + uint is long (C# promotion)
        Object boxed = 42;               // implicit boxing
        int back = (int) boxed;          // checked unboxing cast
        println($"{wrapped} {narrow} {mixed} {back}");   // prints: -2147483648 44 84 42
    }

    static int total(int price, int quantity) {
        int sum = checked(price * quantity);           // throws: ArithmeticException on overflow
        checked {
            sum = sum * 2 + 1;           // every integral operation in the block traps
        }
        return sum;
    }

    static int compound(int x) {
        x += 1; x -= 2; x *= 3; x /= 2; x %= 7;
        x <<= 2; x >>= 1; x >>>= 1;      // >>> shifts in zeros (Java)
        x &= 0xFF; x |= 0x100; x ^= 0x0F;
        return x;
    }
}
```

```csharp
// rejected
int big = 3_000_000_000;                 // error: does not fit int (write 3_000_000_000L)
byte small = 200;                        // error: byte is signed; use ubyte or (byte) 200
long both = 1L + 1UL;                    // error: long + ulong has no common type (C#)
int whole = 1.5;                         // error: no implicit narrowing
```

### 2.8 Null safety, `var`, interpolation, null operators

Non-null by default, `T?`, flow-sensitive narrowing.

```csharp
// file: demo/basics/Nulls.rfl
namespace demo.basics;

import reflect.collections;
import static reflect.io.Console;

public final class Nulls {
    private String? cached;

    public static void main(String[] args) {
        String name = "Ada";                       // non-null
        String? nick = args.length > 0 ? args[0] : null;

        if (nick != null) {
            println(nick.length);                  // narrowed to String by flow analysis
        }
        int len = nick?.length ?? 0;               // ?. yields int?, ?? supplies the fallback
        nick ??= "anonymous";                      // assigns only when null
        String sure = nick!;                       // asserts non-null at run time; a null throws NullPointerException

        var count = 10;                            // int
        var names = new ArrayList<String>();       // ArrayList<String>
        names.add(name);
        double pct = 12.345;
        println($"{name} knows {names.size} names; {count} max; {sure} ({len})");
        println($"share: {pct:0.0}%");             // format after `:`; prints: share: 12.3%
        println($"{{braces}} {(len > 3 ? "long" : "short")}");   // {{ }} escape; a conditional needs ( )
    }

    /** Fields other than `final` fields of `this` are not narrowed across calls. */
    int cachedLength() {
        String? c = cached;                        // copy into a local, which narrows
        return c != null ? c.length : -1;
    }
}
```

```csharp
// rejected
String? nick = null;
int a = nick.length;                       // error: nick may be null
String s = null;                           // error: null for a non-null type
String[] slots = new String[4];            // error: elements would start as null; use new String?[4]
                                           //        or Array.filled(4, "")
class Person {
    String name;
    Person() { }                           // error: non-null field `name` not definitely assigned
}
class Holder<T> {
    T? item;                               // error: T? needs `T extends class` or `T extends struct`
}
String page = """
    text block
    """;                                   // error: no text blocks or raw strings in v1
```

### 2.9 Statements

Java statements; `switch` has only the arrow form and never falls through.

For performance, enhanced `for` loops (`foreach` in Java) will support struct iterators and enumerators (C#).

```csharp
// file: demo/basics/Statements.rfl
namespace demo.basics;

import reflect.collections;
import static reflect.io.Console;

public final class Statements {
    enum Day { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

    static void run(int x, Day day, char grade, List<List<String>> rows) {
        if (x > 0) { println("positive"); } else if (x < 0) { println("negative"); } else { println("zero"); }

        int n = 3;
        while (n > 0) { n--; }
        do { n++; } while (n < 2);
        for (int i = 0; i < n; i++) { print(i); }          // classic for: one shared `i`
        for (final String s : rows[0]) { print(s); }       // enhanced for: fresh `s` per iteration
        for (var row : rows) { print(row.size); }

        switch (day) {
            case MONDAY, FRIDAY -> println("work");
            case SATURDAY -> { println("rest"); }
            default -> { }
        }
        int points = switch (grade) { case 'A' -> 4; case 'B' -> 3; default -> 0; };
        String kind = switch (day) {
            case SATURDAY, SUNDAY -> "weekend";
            default -> {
                String lower = day.name.toLowerCase();
                return "weekday " + lower;                  // `return` gives a block arm its value
            }
        };
        int code = switch (points) {
            case 4, 3 -> 1;
            case 0 -> throw new IllegalStateException("no grade");
            default -> 0;
        };

        outer:
        for (List<String> r : rows) {
            for (String cell : r) {
                if (cell.isEmpty) { continue outer; }
                if (cell == "stop") { break outer; }       // String == is value equality
            }
        }
    }
}
```

```csharp
// rejected
switch (day) {
    case MONDAY: println("work");          // error: only the arrow form exists
}
goto done;                                 // error: `goto` is reserved and unused
int v = switch (x) { default -> { yield 1; } };   // error: `yield` is reserved for iterators; a block arm uses `return`
```

### 2.10 Arrays

Arrays are invariant, nullability included, so a reference store needs no run-time check; generic
methods take any array of a bound (§3.2 does the same for lists).

```csharp
// file: demo/basics/ArrayDemo.rfl
namespace demo.basics;

import demo.shapes;
import static reflect.io.Console;

public final class ArrayDemo {
    static double totalArea<T extends Shape>(T[] shapes) {  // any array of shapes, exact at run time
        double sum = 0;
        for (T s : shapes) { sum += s.area(); }
        return sum;
    }

    public static void main(String[] args) {
        int[] zeros = new int[4];                         // value-type elements start at zero
        int[] primes = { 2, 3, 5, 7 };                    // initializer in a declaration
        String[] words = new String[] { "a", "b" };       // non-null elements need values
        String[] blanks = Array.filled(3, "");            // static helper of the Array base class
        Array any = blanks;                               // every array type extends Array
        String?[] slots = new String?[3];                 // nullable elements start as null
        String[]? maybe = null;                           // the array itself is nullable
        int[]?[] rows = new int[]?[2];                    // jagged: rows start as null, see type-system.md NUL-07

        Square[] squares = { new Square(1), new Square(2) };
        println(totalArea(squares) == 5);                 // prints: true (T = Square; 1 + 4)
        squares[0] = new Square(3);                       // no store check: the array is exactly Square[]

        println(primes.length);                           // prints: 4
        println(zeros[4]);                                // throws: IndexOutOfBoundsException
    }
}
```

```csharp
// rejected
Shape[] shapes = new Square[] { new Square(1) };   // error: arrays are invariant; use a generic method
String?[] loose = new String[] { "a" };    // error: String[] is not String?[]
Object[] boxes = new int[3];               // error: arrays are invariant
long[] longs = new int[3];                 // error: no array conversion between element types
```

```csharp
// file: stdlib/reflect.core/Array.rfl
namespace reflect.core;

public abstract class Array {                                 // every array type extends it
    public static native T[] filled<T>(int n, T value);      // allocated and filled in one VM step

    public static Span<T> asSpan<T>(this T[] array, int start, int length) {   // also numbers.asSpan(2, 5)
        return new Span<T>(array, start, length);            // checks bounds and the exact element type
    }
}
```

### 2.11 Native methods

Natives are declared in Reflect; only the stdlib has them in v0.
Natives will be supported to extend functionality to outside libraries, such as OS-specific operations.

```csharp
// file: stdlib/reflect.core/Natives.rfl
namespace reflect.core;

public final class Natives {
    @Unsafe public static native long alloc(int size);   // bound by name and signature to the VM
    @Unsafe public static native void free(long handle);
}
```

```csharp
// file: stdlib/reflect.io/Console.rfl
namespace reflect.io;

public final class Console {
    private static native void write(int fd, String text);

    public static void print(Object? value) { write(1, String.valueOf(value)); }
    public static void println(Object? value) { write(1, String.valueOf(value) + "\n"); }
    public static void println() { write(1, "\n"); }
}
```

```csharp
// rejected only for v0
namespace demo.basics;

final class Fast {
    static native int popcount(int x);     // error: native methods belong to the stdlib for v0
}
```

## 3. Generics and reification

### 3.1 Declarations

Generic classes and methods with bounds, `where` clauses and kind constraints; statics exist per
instantiation.

```csharp
// file: demo/generics/Box.rfl
namespace demo.generics;

public class Box<T> {
    public static int count;                      // one per instantiation
    private T item;

    static { BoxStats.instantiations++; }         // runs once per instantiation

    public Box(T item) { this.item = item; count++; }
    public T get() { return item; }
}

final class BoxStats { public static int instantiations; }
```

```csharp
// file: stdlib/reflect.core/Parsable.rfl
namespace reflect.core;

public interface Parsable<T extends Parsable<T>> {                // implemented by the numeric primitives
    static abstract T parse(String text);                         // bad input: unchecked NumberFormatException
    static abstract boolean tryParse(String text, out T result);  // never throws
}
```

```csharp
// file: demo/generics/GenericsDemo.rfl
namespace demo.generics;

import reflect.collections;
import static reflect.io.Console;

public final class GenericsDemo {
    public static T max<T extends Comparable<T>>(T a, T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static void copy<T, S>(List<? super T> dst, List<S> src) where S extends T {
        for (S s : src) { dst.add(s); }
    }

    // Number<T> aggregates Parsable<T>; Number is an interface the numeric primitives implement
    public static T parse<T extends Number<T>>(String text) {
        return T.parse(text);                     // static abstract member through reified T
    }

    public static void main(String[] args) {
        new Box<int>(1);
        new Box<int>(2);
        new Box<String>("x");
        println(Box<int>.count);                  // prints: 2
        println(Box<String>.count);               // prints: 1
        println(BoxStats.instantiations);         // prints: 2 (Box<int> and Box<String>)

        int bigger = max<int>(3, 4);              // explicit type arguments
        String later = max("pear", "apple");      // inferred: T = String
        var list = new ArrayList<String>();       // or: List<String> list = new ArrayList<>();
        List<Object> sink = new ArrayList<>();    // diamond
        copy<Object, String>(sink, List.of("a", "b"));
        int n = parse<int>("42");                 // int implements Parsable<int>
    }
}
```

```csharp
// file: demo/generics/Cache.rfl
namespace demo.generics;

import reflect.collections;

public class Cache<K extends class, V extends struct> {
    private final Map<K, V> entries = new HashMap<>();
    public K? lastKey;                            // legal: K is a reference type
    public V? find(K key) {                       // legal: V? is a nullable value type
        lastKey = key;
        return entries.containsKey(key) ? entries[key] : null;
    }
}

public class Sorted<T extends class & Comparable<T>> { }   // kind plus bound, kind first
```

```csharp
// rejected
var c = new Cache<int, String>();          // error: int is not a class, String is not a struct
Box<Span<int>> spans;                      // error: a ref struct is never a type argument
// T? on an unconstrained T: §2.8
```

### 3.2 Use-site variance

Wildcards `? extends` / `? super` in static types only.

```csharp
// file: demo/generics/Variance.rfl
namespace demo.generics;

import demo.shapes;
import reflect.collections;

public final class Variance {
    public static double totalArea(List<? extends Shape> shapes) {   // read as Shape
        double sum = 0;
        for (Shape s : shapes) { sum += s.area(); }
        return sum;
    }

    public static void addSquares(List<? super Square> out) {        // write Squares
        out.add(new Square(1));
    }

    public static void main(String[] args) {
        List<Square> squares = new ArrayList<>();
        addSquares(squares);
        List<Shape> mixed = new ArrayList<>();
        addSquares(mixed);                        // List<Shape> <: List<? super Square>
        double a = totalArea(squares);            // List<Square> <: List<? extends Shape>
        Class<? extends Shape> k = Square.class;
        List<?> anything = squares;               // List<?> means List<? extends Object?>
    }
}
```

```csharp
// rejected
List<? extends Shape> shapes = new ArrayList<Square>();
shapes.add(new Square(2));                 // error: no writes through `? extends`
var w = new ArrayList<? extends Shape>();  // error: wildcards are never instantiated
List<Shape> fixed = new ArrayList<Square>();   // error: invariant without a wildcard
```

### 3.3 Reified operations

Type arguments exist at run time, so every operation below is legal on a type parameter (all are
illegal in Java).

```csharp
// file: demo/generics/Reified.rfl
namespace demo.generics;

import demo.kinds;
import reflect.collections;
import static reflect.io.Console;

public final class Reified {
    static Class<T> classOf<T>() { return T.class; }

    static boolean isA<T>(Object? o) { return o instanceof T; }       // real run-time test

    static T cast<T>(Object? o) { return (T) o; }                      // checked against reified T

    static List<T> onlyOf<T>(List<?> xs) {
        var out = new ArrayList<T>();
        for (Object? o : xs) {
            if (o instanceof T t) { out.add(t); }                       // pattern binding
        }
        return out;
    }

    static T[] zeros<T extends struct>(int n) { return new T[n]; }     // zero-initialized elements
    static T?[] slots<T extends class>(int n) { return new T?[n]; }
    static T blank<T extends struct>() { return default(T); }          // the all-zero value

    static K make<K extends Kind>(int size) { return new K(size); }    // virtual constructor

    public static void main(String[] args) {
        Object o = new ArrayList<Cube>();
        println(o instanceof List<Cube>);                 // prints: true
        println(o instanceof List<? extends Kind>);       // prints: true (arguments checked against the bound)
        println(o instanceof List<Sphere>);               // prints: false
        println(classOf<List<int>>().name);               // prints: reflect.collections.List<reflect.core.Int32>
        println(isA<String>("x"));                        // prints: true
        String s = cast<String>(42);                      // throws: ClassCastException

        var mixed = List.of<Object>("a", 1, "b");         // explicit: String and int have no best common type
        List<String> strings = onlyOf<String>(mixed);     // [a, b]
        Cube cube = make<Cube>(3);
        int[] none = zeros<int>(4);                       // [0, 0, 0, 0]
    }
}
```

### 3.4 Generic definitions as values

```csharp
// file: demo/generics/Definitions.rfl
namespace demo.generics;

import reflect.collections;
import static reflect.io.Console;

public final class Definitions {
    public static void main(String[] args) throws Exception {   // reflective actions throw checked exceptions
        Class<?> def = ArrayList.class;                   // generic definition ArrayList<>
        Class<?> ints = def.instantiate(int.class);       // ArrayList<int>, built at run time
        Object list = ints.getConstructor()!.invoke();    // reflective construction; lookups may return null
        println(ints.name);                               // prints: reflect.collections.ArrayList<reflect.core.Int32>
        println(ints.genericDefinition == def);           // prints: true
    }
}
```

### 3.5 Run-time identity of instantiations

Each instantiation is its own run-time class; nullability of type arguments is part of it, tuple
element names are not. Code sharing is invisible to programs.

```csharp
// file: demo/generics/Identity.rfl
namespace demo.generics;

import reflect.collections;
import static reflect.io.Console;

public final class Identity {
    public static void main(String[] args) {
        Object strict = new ArrayList<String>();
        Object loose = new ArrayList<String?>();
        println(strict instanceof List<String>);          // prints: true
        println(loose instanceof List<String>);           // prints: false
        println(strict.getClass() == loose.getClass());   // prints: false: two classes, shared code

        Object ints = new ArrayList<int>();               // own specialized code
        println(ints instanceof List<long>);              // prints: false

        Object pairs = new ArrayList<(int count, String label)>();
        println(pairs instanceof List<(int, String)>);    // prints: true: tuple names are metadata

        var oops = (List<String?>) strict;                // throws: ClassCastException
    }
}
```

### 3.6 Inference

Inferred type arguments are reified, so inference picks a best common type, counting the target
type, and never synthesizes one. The only exception is a `throws` type parameter.

```csharp
// file: demo/generics/Inference.rfl
namespace demo.generics;

import demo.kinds;

public final class Inference {
    static T pick<T>(T a, T b) { return a; }

    static void run(Cube cube, Sphere sphere) {
        var n = pick(1, 2L);               // long: int converts to long
        Kind k = pick(cube, sphere);       // Kind: the target type is a candidate
        var y = pick<Kind>(cube, sphere);  // explicit type argument
    }
}
```

```csharp
// rejected
var x = pick(cube, sphere);                // error: neither Cube nor Sphere converts to the other
```

### 3.7 Wildcard restrictions

Tate, Leung and Lerner's four restrictions plus Java's base rule keep subtyping decidable, in the
compiler and in the VM.

```csharp
// file: demo/generics/Wildcards.rfl
namespace demo.generics;

import demo.shapes;
import reflect.collections;

public interface Plugin { }
public class Event { }

public abstract class Registry implements Map<String, List<? extends Plugin>> { }   // `? extends`: fine
public interface SortedBag<T> extends Collection<T> {
    Comparator<? super T> comparator { get; }       // members are unaffected
}
public abstract class TypedEventLog<L extends List<? super Event>> implements Map<String, L> { }
public class SortedList<T, C extends Comparator<? super T>> { }   // `? super` at a covariant spot
public class Ok<T extends Shape & Comparable<T>> { }
public class Tight<E, T extends List<E> & Set<E>> { }             // element type named once
public class Shapes<S extends Shape> { }

public final class Wildcards {
    public static T max<T extends Comparable<? super T>>(Collection<? extends T> xs) {
        var it = xs.iterator();
        T best = it.next();
        while (it.hasNext()) {
            T x = it.next();
            if (x.compareTo(best) > 0) { best = x; }
        }
        return best;
    }

    public static void copy<T>(List<? super T> dst, List<? extends T> src) {   // not a bound
        for (T x : src) { dst.add(x); }
    }

    static Shapes<?> any;                           // fine
    static Shapes<? extends Square> squares;        // fine
}
```

```csharp
// rejected
class X implements List<? extends Shape> { }                    // error: wildcard as a direct supertype argument (Java)
class C implements List<List<? super C>> { }                    // error: inheritance restriction (proof cycles)
class D<P> implements List<List<? super D<D<P>>>> { }           // error: inheritance restriction (proof grows)
class EventLog implements Map<String, List<? super Event>> { }  // error: inheritance restriction (it is syntactic)
class Bad1<P extends List<List<? super P>>> { }                 // error: parameter restriction (invariant position)
class Bad2<T extends Comparable<? super List<? super T>>> { }   // error: parameter restriction (inside `? super`)
class Never<T extends List<String> & Collection<int>> { }       // error: intersection restriction (no common type)
class Loose<T extends List<?> & Set<?>> { }                     // error: intersection restriction (unrelated `?`)
Shapes<? extends Object> wide;                                  // error: equivalence (bound Object is wider than Shape); fix-it: Shapes<?>
```

### 3.8 Intersection types

Intersections are run-time types: inferred only for `throws` type parameters, written only in
type arguments, casts and `instanceof`.

```csharp
// file: demo/generics/Intersections.rfl
namespace demo.generics;

import demo.errors;                                // Throwing.map
import reflect.collections;
import reflect.functions;
import reflect.io;
import static reflect.io.Console;

public interface Retryable { int attempts { get; } }

public class SQLException extends Exception {
    public SQLException(String message) { super(message); }
}
public class NetTimeout extends IOException implements Retryable {
    public NetTimeout() { super("timeout"); }
    public override int attempts { get { return 3; } }
}
public class DbBusy extends SQLException implements Retryable {
    public DbBusy() { super("busy"); }
    public override int attempts { get { return 5; } }
}

public record Query(String text) { }
public record Row(String data) { }

public final class Job implements Runnable, Comparable<Job> {
    public override void run() { }
    public override int compareTo(Job other) { return 0; }
}

public final class Intersections {
    static Row fetch(Query q) throws NetTimeout, DbBusy {
        if (q.text.isEmpty) { throw new DbBusy(); }
        return new Row(q.text);
    }

    static void run(List<Query> queries, Object item, List<Runnable> queue) {
        try {
            // E = Exception & Retryable, reified like any type argument;
            // warning: E is wider than NetTimeout and DbBusy
            List<Row> rows = Throwing.map(queries, q -> fetch(q));
        } catch (Exception e) {
            if (e instanceof Retryable r) { println(r.attempts); }
        }

        var job = (Runnable & Comparable<Job>) item;           // cast: checks both components
        if (item instanceof (Runnable & Comparable<Job>) j) {  // parenthesized after instanceof
            queue.add(j);
        }
        List<IOException & Retryable> failures = new ArrayList<>();

        Object tasks = new ArrayList<Runnable & Retryable>();
        println(tasks instanceof List<Retryable & Runnable>);  // prints: true: components are sorted
        println(Reified.classOf<Runnable & Retryable>().kind); // prints: INTERSECTION
    }
}
```

```csharp
// rejected
Runnable & Retryable r = task;             // error: not a declaration type; use var or a pattern
if (item instanceof Runnable & Retryable) { }   // error: parenthesize the intersection
var p = Inference.pick(new NetTimeout(), new DbBusy());   // error: no best common type
class Late<T extends Runnable & IOException> { }  // error: the class component comes first
List<IOException & Exception> causes;      // error: two class components, even related ones
```

## 4. Static virtual members

### 4.1 Virtual and override everywhere

```csharp
// file: demo/kinds/Kind.rfl
namespace demo.kinds;

public abstract class Kind {
    public static virtual String value { get; } = "NO_KIND";       // overridable static property

    public static String describe() { return "Kind: " + value; }   // unqualified `value`: late-bound

    protected final int size;
    public virtual Kind(int size) { this.size = size; }            // virtual constructor
}
```

```csharp
// file: demo/kinds/Cube.rfl
namespace demo.kinds;

public class Cube extends Kind {
    public static override String value { get; } = "CUBE_KIND";    // own storage
    public override Cube(int size) { super(size); }                // re-declared with `override`
}

public class Sphere extends Kind { }    // reads Kind's value; inherits Kind(int)

public class OtherObject<K extends Kind> {
    public String returnReflectedKind() { return K.value; }        // type accessor: reified argument
}
```

```csharp
// file: demo/kinds/Demo.rfl
namespace demo.kinds;

import static reflect.io.Console;

public final class Demo {
    public static void function<K extends Kind>(K kind) {
        println(kind.value);         // object accessor: run-time class of `kind`
        println(K.value);            // type accessor: the type argument chosen at the call site
    }

    public static void main(String[] args) {
        function(new Cube(1));       // prints: CUBE_KIND, CUBE_KIND   (K inferred as Cube)
        Kind k = new Cube(2);
        function(k);                 // prints: CUBE_KIND, NO_KIND     (K inferred as Kind)

        println(Kind.value);         // prints: NO_KIND                (class name: early-bound)
        println(k.describe());       // prints: Kind: CUBE_KIND        (receiver class = Cube)
        println(Kind.describe());    // prints: Kind: NO_KIND
        println(Cube.describe());    // prints: Kind: CUBE_KIND        (inherited static, receiver Cube)
        println(new OtherObject<Cube>().returnReflectedKind());   // prints: CUBE_KIND
    }
}
```

### 4.2 Resolution rules

`super` in a static override, covariant get-only overrides and `final static override`.

```csharp
// file: demo/kinds/Resolution.rfl
namespace demo.kinds;

import static reflect.io.Console;

public class Probe extends Kind {
    public String fromInstance() { return value; }       // receiver: this.getClass()
    public static String fromStatic() { return value; }  // receiver: forwarded from the call
}

public class SubProbe extends Probe {
    public static override String value { get { return "SUB/" + super.value; } }   // super forwards the receiver
}

public abstract class Token {
    public static virtual Object id { get; } = "token";
}
public class NamedToken extends Token {
    public final static override String id { get; } = "named";   // covariant (get-only), final
}

public final class Resolution {
    static String viaType<K extends Kind>() { return K.value; }

    public static void main(String[] args) {
        Kind kind = new SubProbe(1);
        Class<? extends Kind> k = SubProbe.class;
        println(Kind.value);                 // prints: NO_KIND          class name: early-bound
        println(kind.value);                 // prints: SUB/NO_KIND      instance: run-time class
        println(viaType<SubProbe>());        // prints: SUB/NO_KIND      type parameter: reified argument
        println(k.value);                    // prints: SUB/NO_KIND      class value: referenced class
        println(((Probe) kind).fromInstance());   // prints: SUB/NO_KIND  unqualified, instance member
        println(SubProbe.fromStatic());      // prints: SUB/NO_KIND      unqualified, static member
        println(Probe.fromStatic());         // prints: NO_KIND
        String id = NamedToken.id;           // typed String through NamedToken
    }
}
```

```csharp
// rejected
public class Hider extends Kind {
    public static String value { get; } = "X";               // error: no hiding; write `static override`
}
public class Counter0 { public static virtual int n { get; set; } }
public class Wrong extends Counter0 {
    public static override long n { get; set; }             // error: a settable override keeps the exact type
}
public class Plain { public static int limit { get; } = 1; }
public class Tries extends Plain {
    public static override int limit { get; } = 2;          // error: `limit` is not static virtual
}
public class Later extends NamedToken {
    public static override String id { get; } = "later";    // error: `id` is final
}
```

### 4.3 Storage

Each class declaring `static override` owns its storage; the others share the nearest
overrider's. Generic instantiations own separate storage for every static.

```csharp
// file: demo/kinds/Storage.rfl
namespace demo.kinds;

import static reflect.io.Console;

public class Counter {
    public static virtual int instances { get; set; }
}
public class A extends Counter { public static override int instances { get; set; } }
public class B extends Counter { }

public final class Storage {
    public static void main(String[] args) {
        A.instances = 5;               // A's own storage
        B.instances = 7;               // B has no override: writes Counter's storage
        println(Counter.instances);    // prints: 7 (shared by Counter and B)
        println(A.instances);          // prints: 5
    }
}
```

### 4.4 Late static binding

Unqualified static references resolve through the receiver class; there is no syntax naming it.

```csharp
// file: demo/polygons/Polygon.rfl
namespace demo.polygons;

import static reflect.io.Console;

public abstract class Polygon {
    public static abstract String label { get; }
    public static virtual int sides { get; } = 0;

    public static String summary() { return $"{label} with {sides} sides"; }   // receiver class
    public String tag() { return $"<{label}>"; }                               // this.getClass()
}

public class Triangle extends Polygon {
    public static override String label { get; } = "triangle";
    public static override int sides { get; } = 3;
}

public final class Polygons {
    public static void main(String[] args) {
        println(Triangle.summary());     // prints: triangle with 3 sides
        println(new Triangle().tag());   // prints: <triangle>
        println(Polygon.summary());      // throws: AbstractMemberError: receiver Polygon
    }
}
```

```csharp
// rejected
println(Polygon.label);                    // error: abstract static through the class name
public class Square4 extends Polygon { }   // error: must override the static abstract `label`
static String show<P extends Polygon>() { return P.label; }
show<Polygon>();                           // error: Polygon leaves static abstracts unimplemented
```

### 4.5 Class references and virtual constructors

`Class<? extends Kind>` values dispatch statics; `new K(x)` and `k.create(x)` call virtual
constructors. `Kind(int)` is declared in §4.1.

```csharp
// file: demo/kinds/Factory.rfl
namespace demo.kinds;

import static reflect.io.Console;

public final class Factory {
    static K make<K extends Kind>(int size) { return new K(size); }   // type-parameter form

    public static void main(String[] args) {
        Class<? extends Kind> k = args.length > 0 ? Cube.class : Sphere.class;
        println(k.value);                       // dispatches like `kind.value`
        Kind made = k.create(3);                // virtual constructor of the referenced class
        Cube c = Cube.class.create(4);          // result type follows the type argument
        Sphere s = make<Sphere>(5);             // Sphere inherited Kind(int)

        Class<? extends Kind> abstractOne = Kind.class;
        Kind none = abstractOne.create(1);      // throws: InstantiationError
    }
}
```

```csharp
// rejected
public class Pyramid extends Kind {
    public Pyramid() { super(1); }             // error: declares constructors but not the virtual Kind(int)
}
static T build<T>(int n) { return new T(n); } // error: T's bound declares no virtual constructor
```

Name clashes with `Class<T>` : `k.x` finds members of `Class<T>` first, and the static that
loses gets a warning at its declaration.

```csharp
// file: demo/plugins/Plugin.rfl
namespace demo.plugins;

import static reflect.io.Console;

public abstract class Plugin {
    public static virtual String kind { get; } = "none";    // warning: hidden by Class<T>.kind
    public static Plugin create(String spec) { return new Echo(spec.length); }   // warning: hidden by Class<T>.create
    public virtual Plugin(int id) { }
}

public class Echo extends Plugin {
    public static override String kind { get; } = "echo";
    public override Echo(int id) { super(id); }
}

public final class Clash {
    static String kindOf<P extends Plugin>() { return P.kind; }

    public static void main(String[] args) {
        Class<? extends Plugin> k = Echo.class;
        println(k.kind);              // prints: CLASS (Class<T>.kind, not the static)
        Plugin made = k.create(3);    // virtual constructor, not the static factory
        println(Plugin.kind);         // prints: none (the static, through the class name)
        println(kindOf<Echo>());      // prints: echo (the static, through a type parameter)
    }
}
```

### 4.6 Interfaces and generic math

Static abstract interface members, operators included, dispatch through the reified type
argument. `Addable` belongs to the stdlib, so the numeric primitives implement it.

```csharp
// file: stdlib/reflect.core/Addable.rfl
namespace reflect.core;

public interface Addable<T extends Addable<T>> {                  // implemented by the numeric primitives
    static abstract T zero { get; }
    static abstract T operator +(T a, T b);
    static virtual T operator checked +(T a, T b) { return a + b; }   // used inside `checked`; integers trap
}
```

```csharp
// file: demo/math/Money.rfl
namespace demo.math;

public struct Money implements Addable<Money> {
    public final long cents;
    public Money(long cents) { this.cents = cents; }

    public static override Money zero { get; } = new Money(0);       // `override`
    public static override Money operator +(Money a, Money b) { return new Money(a.cents + b.cents); }
    public override String toString() { return $"Money({cents})"; }
}
```

```csharp
// file: demo/math/Sums.rfl
namespace demo.math;

import reflect.collections;
import static reflect.io.Console;

public final class Sums {
    public static T sum<T extends Addable<T>>(List<T> items) {
        T total = T.zero;                  // static abstract property through reified T
        for (T item : items) {
            total = total + item;          // T's operator +, dispatched through reified T
        }
        return total;
    }

    public static T checkedSum<T extends Addable<T>>(List<T> items) {
        T total = T.zero;
        for (T item : items) {
            total = checked(total + item); // T's operator checked +
        }
        return total;
    }

    public static void main(String[] args) {
        Money m = sum(List.of(new Money(150), new Money(250)));
        println(m);                        // prints: Money(400)
        println(sum(List.of(1, 2, 3)));    // prints: 6 (int implements Addable<int>)

        byte hundred = 100;
        println(sum(List.of(hundred, hundred)));         // prints: -56 (a byte sum wraps at 8 bits)
        println(hundred + hundred);                      // prints: 200 (predefined `+` gives int)
        println(checkedSum(List.of(int.MAX_VALUE, 1)));  // throws: ArithmeticException
    }
}
```

### 4.7 `==` in generic code

`==` on type-parameter operands needs a bound that declares it; `x == null` always works;
identity is `Object.referenceEquals`.

```csharp
// file: stdlib/reflect.core/Equatable.rfl
namespace reflect.core;

public interface Equatable<T extends Equatable<T>> {
    static abstract boolean operator ==(T a, T b);
    static abstract boolean operator !=(T a, T b);
}
```

```csharp
// file: demo/math/Search.rfl
namespace demo.math;

import static reflect.io.Console;

public final class Search {
    public static int indexOf<T extends Equatable<T>>(T[] xs, T x) {
        for (int i = 0; i < xs.length; i++) {
            if (xs[i] == x) { return i; }      // T's operator ==, dispatched through reified T
        }
        return -1;
    }

    public static boolean sameObject<T extends class>(T a, T b) {
        return Object.referenceEquals(a, b);   // identity, asked for explicitly
    }

    public static boolean missing<T>(T x) { return x == null; }   // null tests: always allowed

    public static void main(String[] args) {
        println(indexOf(new String[] { "a", "b" }, "b"));   // prints: 1 (String implements Equatable<String>)
        println(indexOf(new int[] { 3, 4 }, 4));            // prints: 1 (primitives implement Equatable)
        println(missing<int>(0));                            // prints: false (constant for non-null value types)
        println(missing<String?>(null));                     // prints: true
    }
}
```

```csharp
// rejected
public static boolean same<T extends class>(T a, T b) {
    return a == b;                             // error: the bound declares no `==`
}
public static boolean eq<T>(T a, T b) { return a == b; }   // error: no bound declares `==`
boolean m = new Money(1) == new Money(1);      // error: struct without `operator ==`
```

## 5. Properties, events, `@Observable`

### 5.1 Properties

C#-style properties with `field`; the setter parameter is always `value`; accessors may throw.

```csharp
// file: demo/bank/Account.rfl
namespace demo.bank;

import reflect.io;

public class Account {
    private final Path path;
    private long cents;                                   // read reflectively in §10.3

    public String owner { get; }                          // get-only, assignable in constructors
    public long balance { get; private set; }             // public get, private set
    public String note { get; set { field = value.trim(); } } = "";   // `field` = backing storage
    public boolean overdrawn { get { return balance < 0; } }          // computed, no storage
    public String statement { get throws IOException { return Files.readString(path); } }
    public static int opened { get; private set; }

    public Account(String owner, Path path) {
        this.owner = owner;
        this.path = path;
        opened++;
    }

    public void deposit(long amount) { balance += amount; }   // get, add, set
}
```

```csharp
// file: demo/props/Figure.rfl
namespace demo.props;

import static reflect.io.Console;

public abstract class Figure {
    public abstract double area { get; }
    public virtual String name { get { return "figure"; } }
}

public final class Circle extends Figure {
    public double radius { get; set; } = 1;
    public override double area { get { return Math.PI * radius * radius; } }
    public override String name { get { return "circle"; } }
}

public final class Gauge {
    public int value {
        get;
        set {
            println($"{this.value} -> {value}");   // this.value: the property; value: the new value
            field = value;
        }
    }
}
```

```csharp
// rejected
static void bump(ref long x) { x++; }
bump(ref account.balance);                 // error: a property is not a variable (no `ref`)
account.owner = "Eve";                     // error: get-only outside constructors
class Loud { String name { get; public set; } }   // error: an accessor is never more accessible than its property
```

### 5.2 Events

C#-style events over functional interfaces.

```csharp
// file: demo/ui/Button.rfl
namespace demo.ui;

import reflect.collections;
import reflect.functions;

public record ClickEvent(Button source) { }

public final class Button {
    public event Consumer<ClickEvent> clicked;     // event type: a functional interface
    public static event Runnable anyPressed;       // static event

    public void press() {
        clicked(new ClickEvent(this));             // raise: only inside the declaring class
        anyPressed();                              // no listeners: no-op
    }
}

public final class Throttled {
    private final List<Runnable> listeners = new ArrayList<>();

    public event Runnable fired {                  // custom accessors (C#)
        add { if (listeners.size < 8) { listeners.add(value); } }
        remove { listeners.remove(value); }
    }

    public void fire() { for (Runnable r : listeners) { r.run(); } }
}
```

```csharp
// file: demo/ui/Saver.rfl
namespace demo.ui;

import reflect.functions;
import reflect.io;

public final class Saver {
    public event ThrowingConsumer<String, IOException> saved;   // the raise may throw IOException

    public void save(String name) throws IOException {
        saved(name);       // all listeners run; first failure rethrown, later ones suppressed
    }
}
```

```csharp
// file: demo/ui/Events.rfl
namespace demo.ui;

import reflect.functions;
import reflect.io;
import static reflect.io.Console;

public final class Events {
    public static void main(String[] args) {
        Button ok = new Button();
        Consumer<ClickEvent> log = e -> println("clicked " + e.source);
        ok.clicked += log;                         // subscribe
        ok.clicked += e -> println("beep");
        ok.clicked -= log;                         // removes the last listener equal to `log`
        Button.anyPressed += () -> println("any");
        ok.press();                                // prints: beep, any

        var s = new Saver();
        s.saved += n -> { throw new IOException("disk full"); };
        s.saved += n -> println("still runs");
        s.saved += n -> { throw new FileNotFoundException(n); };
        try {
            s.save("a.txt");                       // prints: still runs
        } catch (IOException e) {
            println(e.message);                    // prints: disk full
            println(e.suppressed[0].message);      // prints: a.txt
        }

        var t = new Saver();
        t.saved += n -> { throw new IOException("first"); };
        t.saved += n -> { throw new OutOfMemoryError(); };    // an Error ends the raise at once
        t.saved += n -> println("never runs");
        try {
            t.save("b");
        } catch (OutOfMemoryError e) {
            println(e.suppressed.length);          // prints: 1 (earlier failures attached)
        } catch (IOException e) { }
    }
}
```

```csharp
// rejected
ok.clicked(new ClickEvent(ok));            // error: only the declaring class raises an event
ok.clicked = null;                         // error: outside the declaring class only += and -=
public event Object changed;               // error: the event type must be a functional interface
```

### 5.3 The proposal's `@Property` example

`@Observable` on a field of a partial class. The proposal subscribed after assigning,
so its first change would go unseen; here the listener comes first.

```csharp
// file: demo/model/Model.rfl
namespace demo.model;

import reflect.observable;                // @Observable, PropertyChange<T>
import static reflect.io.Console;

public partial class Model {             // partial: generators may add members
    @Observable
    public int value;                    // becomes a partial property completed by the generator

    public void function() {
        valueChanged += change -> println(change.newValue);
        value = 10;                      // the setter raises: prints 10
        value = 10;                      // unchanged: no notification
    }
}
```

```csharp
// file: demo/model/ModelDemo.rfl
namespace demo.model;

import static reflect.io.Console;

public final class ModelDemo {
    public static void main(String[] args) {
        Model m = new Model();
        m.propertyChanged += c -> println($"{c.property.name}: {c.oldValue} -> {c.newValue}");
        m.function();                    // prints: 10, then value: 0 -> 10
    }
}
```

### 5.4 What the generator adds

`rflc --emit-generated` prints the members that `ObservableGenerator`  adds to `Model`. The `import` lines are an assumption, see [reflection §3, META-04](reflection.md#3-generators-and-templates).

```csharp
// file: demo/model/Model.g.rfl
// <generated by reflect.observable.ObservableGenerator for demo.model.Model>
namespace demo.model;

import reflect.functions;
import reflect.meta;
import reflect.observable;

public partial class Model implements PropertyChangeSource {
    public partial int value {
        get { return field; }
        set {
            int old = field;
            if (old == value) { return; }        // references use Objects.equals(old, value)
            field = value;
            var change = new PropertyChange<int>(this, VALUE_PROPERTY, old, value);
            try {
                valueChanged(change);
            } catch (Exception first) {          // one raise over both events
                try { propertyChanged(change); } catch (Exception next) { first.addSuppressed(next); }
                throw first;                     // precise rethrow: still unchecked here
            }
            propertyChanged(change);
        }
    }

    public event Consumer<PropertyChange<int>> valueChanged;
    public override event Consumer<PropertyChange<?>> propertyChanged;   // from PropertyChangeSource

    private static final Property VALUE_PROPERTY = Model.class.getProperty(nameof(value))!;
}
```

Run-time support types:

```csharp
// file: stdlib/reflect.observable/PropertyChange.rfl
namespace reflect.observable;

import reflect.functions;
import reflect.meta;

public record PropertyChange<T>(Object source, Property property, T oldValue, T newValue) { }

public interface PropertyChangeSource {
    event Consumer<PropertyChange<?>> propertyChanged;
}
```

### 5.5 Partial classes and members

Every part says `partial`; a partial member is completed by exactly one generator.

```csharp
// file: demo/model/Settings.rfl
namespace demo.model;

import reflect.observable;

public partial class Settings {
    @Observable
    public partial String theme { get; set; } = "light";   // explicit form of the field shorthand; see reflection.md META-04
}
```

```csharp
// file: demo/model/SettingsDefaults.rfl
namespace demo.model;

public partial class Settings {                 // second part, in another file
    public int version = 1;
}
```

```csharp
// rejected
public class Plain {
    @Observable public int x;                   // error: generator-bound annotation outside a partial class
}
public partial class Half {
    public partial int size { get; set; }       // error: no generator completes `size`
}
public class Settings { }                       // error: every part of Settings must say `partial`
```

## 6. Annotations, generators, compile-time evaluation

### 6.1 Annotation declarations

Java `@interface` syntax, retention `RUNTIME` by default, bound to a generator with
`@GeneratedBy`. Each annotation type has a generated implementation class, so reading
annotations needs no `unsafe;`.

```csharp
// file: stdlib/reflect.observable/Observable.rfl
namespace reflect.observable;

import reflect.meta;

@Target({ ElementType.FIELD, ElementType.PROPERTY })   // PROPERTY is a Reflect addition
@GeneratedBy(ObservableGenerator.class)                // annotation → generator binding
public @interface Observable {
    boolean classWide() default true;                  // Java element syntax
}
```

```csharp
// file: demo/annotations/Route.rfl
namespace demo.annotations;

import reflect.meta;

public enum Verb { GET, POST }

public @interface Since { int major(); int minor(); }
public @interface Tag { String value(); }              // `value` allows the single-element form

@Retention(RetentionPolicy.RUNTIME)                    // the default; SOURCE, CLASS also exist
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Route {
    String path();
    Verb[] verbs() default { Verb.GET };               // `{ }` arrays only in annotations
    Class<?> handler() default Object.class;
    int priority() default 0;
    Since since() default @Since(major = 0, minor = 1);   // nested annotation
}

@Route(path = "/items", verbs = { Verb.GET, Verb.POST }, priority = 2)
@Tag("catalog")
public final class Items {
    @Route(path = "/items/count")
    public int count(@Tag("query") String filter) { return 0; }
}
```

```csharp
// file: demo/model/Document.rfl
namespace demo.model;

import reflect.meta;
import reflect.observable;
import static reflect.io.Console;

public partial class Document {
    @Observable(classWide = false)
    public String title = "untitled";

    public static void main(String[] args) {
        Property title = Document.class.getProperty("title")!;
        Observable? o = title.getAnnotation<Observable>();   // reified type argument; a proxy
        println(o != null && o.classWide());                 // prints: false
        println(title.hasAnnotation<Observable>());          // prints: true (metadata only, no proxy)
    }
}
```

### 6.2 Where generators run

A generator lives in a module compiled earlier and runs on the VM inside `rflc`.

```csharp
// file: stdlib/reflect.observable/module-info.rfl
module reflect.observable {
    requires reflect.meta.compile;       // ClassGenerator, GeneratorContext, Template
    exports reflect.observable;
}
```

```csharp
// file: tools/module-info.rfl
module demo.tools {                      // a pure tooling module, compiled before demo
    requires reflect.meta.compile;
    requires reflect.observable;         // its generator is ordered after ObservableGenerator
    exports demo.tools;
}
```

```csharp
// rejected
// inside module reflect.observable itself:
public partial class Local {
    @Observable public int x;            // error: a generator never runs in the module that defines it
}
```

### 6.3 Writing a generator

Quasi-quotes with typed splices [Proposed shape].

```csharp
// file: stdlib/reflect.observable/ObservableGenerator.rfl
namespace reflect.observable;

import reflect.collections;
import reflect.meta;               // unified API: Class, Field, Property, Type, ...
import reflect.meta.compile;       // compile-time only: ClassGenerator, GeneratorContext, Template

public final class ObservableGenerator implements ClassGenerator<Observable> {

    // called once per partial class that has at least one @Observable member
    public override void generate(Class<?> target, List<Annotated<Observable>> members,
                                  GeneratorContext ctx) throws GeneratorException {
        for (var m : members) {
            Field f = (Field) m.member;
            Type t = f.type;
            Name name = Name.of(f.name);
            Name event = Name.of(f.name + "Changed");
            Name constant = Name.of(Names.upperSnake(f.name) + "_PROPERTY");
            Template<Stmt> raise = m.annotation.classWide()
                ? quote stmt {
                    try {
                        ${event}(change);
                    } catch (Exception first) {          // one raise over both events
                        try { propertyChanged(change); }
                        catch (Exception next) { first.addSuppressed(next); }
                        throw first;
                    }
                    propertyChanged(change);
                }
                : quote stmt { ${event}(change); };

            ctx.add(quote member {
                public partial ${t} ${name} {
                    get { return field; }
                    set {
                        ${t} old = field;
                        if (${sameValue(t, quote expr { old }, quote expr { value })}) { return; }
                        field = value;
                        var change = new PropertyChange<${t}>(this, ${constant}, old, value);
                        ${raise}
                    }
                }
            });
            ctx.add(quote member { public event Consumer<PropertyChange<${t}>> ${event}; });
            ctx.add(quote member {
                private static final Property ${constant} = ${target}.class.getProperty(${f.name})!;
            });
        }
        ctx.addInterface(PropertyChangeSource.class);
        ctx.add(quote member { public override event Consumer<PropertyChange<?>> propertyChanged; });
    }

    private static Template<Expr> sameValue(Type t, Template<Expr> a, Template<Expr> b) {
        return t.isValueType && t.hasOperator("==")
            ? quote expr { ${a} == ${b} }
            : quote expr { Objects.equals(${a}, ${b}) };
    }
}
```

Ordering : a generator that reads members produced by another names it in
`@Generator(after = ...)`.

```csharp
// file: tools/AutoToString.rfl
namespace demo.tools;

import reflect.collections;
import reflect.meta;
import reflect.meta.compile;
import reflect.observable;

@Target(ElementType.TYPE)
@GeneratedBy(ToStringGenerator.class)
public @interface AutoToString { }

@Generator(after = { ObservableGenerator.class })   // sees the properties ObservableGenerator added
public final class ToStringGenerator implements ClassGenerator<AutoToString> {
    public override void generate(Class<?> target, List<Annotated<AutoToString>> members,
                                  GeneratorContext ctx) throws GeneratorException {
        Name sb = ctx.freshName("sb");               // collision-free local
        var parts = new ArrayList<Template<Stmt>>();
        for (Property p : target.properties) {
            if (p.isStatic) { continue; }
            parts.add(quote stmt {
                ${sb}.append(${p.name}).append("=").append(this.${Name.of(p.name)}).append(" ");
            });
        }
        ctx.add(quote member {
            public override String toString() {
                var ${sb} = new StringBuilder(${target.name}).append("{ ");
                ${parts}
                return ${sb}.append("}").toString();
            }
        });
    }
}
```

```csharp
// file: demo/model/Point2.rfl
namespace demo.model;

import demo.tools;
import reflect.observable;

@AutoToString
public partial class Point2 {
    @Observable public int x;
    @Observable public int y;
}
// new Point2().toString() is "demo.model.Point2{ x=0 y=0 }"
```

### 6.4 Analyzers

Analyzers only report diagnostics; they may read a read-only view of bound bodies.
They are registered with `provides` and enabled with `requires static`.

```csharp
// file: reflect.lint/MutablePublicFieldAnalyzer.rfl
namespace reflect.lint;

import reflect.meta;
import reflect.meta.compile;

public final class MutablePublicFieldAnalyzer implements Analyzer<Field> {
    public override void analyze(Field f, DiagnosticSink sink) {
        if (f.isPublic && !f.isFinal) {
            sink.warning(f, "RFL1001", $"public mutable field '{f.name}'; prefer a property");
        }
    }
}
```

```csharp
// file: reflect.lint/IgnoredResultAnalyzer.rfl
namespace reflect.lint;

import reflect.meta;
import reflect.meta.compile;

public @interface CheckResult { }

public final class IgnoredResultAnalyzer implements Analyzer<Method> {
    public override void analyze(Method m, DiagnosticSink sink) {
        for (CallSite call : m.body.calls) {          // read-only bound body  [Proposed]
            if (call.resultIgnored && call.target.hasAnnotation<CheckResult>()) {
                sink.warning(call, "RFL1002", $"result of '{call.target.name}' is ignored");
            }
        }
    }
}
```

### 6.5 Compile-time evaluation

`const` methods and fields run inside the compiler; `static final` stays a run-time constant. A `const` enum's constants are constant expressions, and its `const` methods fold on a constant receiver, as `Planet.EARTH.surfaceGravity()` does below (DAT-05, META-07).

```csharp
// file: demo/consts/Tables.rfl
namespace demo.consts;

import demo.annotations;
import demo.kinds;
import demo.math;

public final class Tables {
    public static const int fib(int n) {            // const method: may run inside the compiler
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }
    public const int FIB_20 = fib(20);              // const field: implicitly static, evaluated by rflc
    public const String KIND_NAME = nameof(Kind);   // "Kind"
    public const String PATH = "/tables/" + KIND_NAME;
    public const int WORD = sizeof(long);           // 8
    public const int MONEY_SIZE = sizeof(Money);    // 8: a struct without references
    public const Class<?> DEFAULT_KIND = Cube.class;
    public const ReadOnlyArray<int> SMALL_PRIMES = { 2, 3, 5, 7 };   // read-only array; see reflection.md META-07
    public const double EARTH_GRAVITY = Planet.EARTH.surfaceGravity();   // const enum folded by rflc (Planet: §9.3)
    public static final long STARTED = Clock.nowMillis();   // run-time constant

    @Route(path = PATH, priority = FIB_20 % 3)      // const values in annotation arguments
    public static void serve() { }
}
```

```csharp
// rejected
public const long NOW = Clock.nowMillis();          // error: const initializers call only const methods
public static const int spin(int n) { return spin(n + 1); }
public const int SPUN = spin(0);                    // error: recursion limit reached during evaluation
public const int A = B + 1;
public const int B = A + 1;                         // error: initializer cycle
public const List<int> LIST = List.of(1, 2);        // error: not a constant-pool type
```

### 6.6 Inlining and eager compilation

`@Inline` and `@ForceInline` hint the optimizing JIT to inline functions. `@Precompile` requests eager compilation at module load under the JIT, and always under the AOT compiler (JIT-03).

### 6.7 One API for both phases

The same code runs over compiler symbols in a generator and over VM metadata in a program.

```csharp
// file: demo/meta/Introspect.rfl
namespace demo.meta;

import demo.model;
import reflect.collections;
import reflect.meta;
import reflect.observable;
import static reflect.io.Console;

public final class Introspect {
    // Runs unchanged inside a generator (compiler symbols) and in a program (VM metadata).
    public static List<String> observableNames(Class<?> c) {
        var out = new ArrayList<String>();
        for (Property p : c.properties) {
            if (p.hasAnnotation<Observable>()) { out.add(p.name); }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        println(observableNames(Model.class));     // prints: [value]
        println(Model.class.phase);                // prints: RUN_TIME
        Property value = Model.class.getProperty("value")!;
        println(value.generatedBy?.name);          // prints: reflect.observable.ObservableGenerator
        println(value.get(new Model()));           // prints: 0
        // inside a generator, `target.phase` is COMPILE_TIME and value.get(...) throws PhaseException
    }
}
```

## 7. Lambdas, exceptions, cleanup, concurrency

### 7.1 Functional interfaces and lambdas

Lambdas and method references convert only to functional interfaces. The stdlib set:

```csharp
// file: stdlib/reflect.functions/Functions.rfl
namespace reflect.functions;

@FunctionalInterface public interface Runnable { void run(); }
@FunctionalInterface public interface Supplier<T> { T get(); }
@FunctionalInterface public interface Consumer<T> { void accept(T t); }
@FunctionalInterface public interface BiConsumer<T, U> { void accept(T t, U u); }
@FunctionalInterface public interface Function<T, R> { R apply(T t); }
@FunctionalInterface public interface BiFunction<T, U, R> { R apply(T t, U u); }
@FunctionalInterface public interface Predicate<T> { boolean test(T t); }
@FunctionalInterface public interface UnaryOperator<T> extends Function<T, T> { }
@FunctionalInterface public interface BinaryOperator<T> extends BiFunction<T, T, T> { }
@FunctionalInterface public interface ThrowingSupplier<T, E extends Exception> { T get() throws E; }
@FunctionalInterface public interface ThrowingConsumer<T, E extends Exception> { void accept(T t) throws E; }
@FunctionalInterface public interface ThrowingFunction<T, R, E extends Exception> { R apply(T t) throws E; }
```

```csharp
// file: demo/fn/Lambdas.rfl
namespace demo.fn;

import demo.kinds;
import demo.shapes;
import reflect.functions;
import static reflect.io.Console;

public interface Transformer<T, R> { R apply(T t); }      // any single-abstract-method interface

public final class Lambdas {
    private final String user = "Ada";

    String greet() { return "hi " + user; }
    static int twice(int x) { return 2 * x; }

    public void run() {
        Function<String, int> length = s -> s.length;        // primitive type arguments
        Function<String, String> trim = String::trim;        // unbound receiver
        Supplier<String> greeting = this::greet;             // bound receiver
        Function<int, Cube> makeCube = Cube::new;            // constructor reference
        Function<int, int> doubler = Lambdas::twice;         // static method
        Function<Square, double> side = Square::side;        // property getter (Kotlin)
        BinaryOperator<int> add = (a, b) -> a + b;
        BiFunction<int, int, int> times = (int a, int b) -> a * b;   // explicit parameter types
        Transformer<String, int> custom = s -> s.length * 2;
        Runnable task = () -> { println("start"); println(greeting.get()); };

        task.run();                                          // prints: start, hi Ada
        println(add.apply(length.apply(" x "), doubler.apply(3)));   // prints: 9
    }
}
```

```csharp
// rejected
Object o = () -> 1;                        // error: Object is not a functional interface
var f = (int x) -> x + 1;                  // error: a lambda needs a target type
```

### 7.2 Captures

Lambdas capture variables, not values; enhanced-`for` variables are fresh per iteration, classic
`for` variables shared.

```csharp
// file: demo/fn/Captures.rfl
namespace demo.fn;

import reflect.collections;
import reflect.functions;
import static reflect.io.Console;

public final class Captures {
    public static void main(String[] args) {
        int counter = 0;
        Runnable inc = () -> counter++;      // captures the variable itself (C#)
        inc.run();
        inc.run();
        println(counter);                    // prints: 2

        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < 3; i++) { actions.add(() -> print(i)); }          // one shared i
        for (int x : new int[] { 0, 1, 2 }) { actions.add(() -> print(x)); }   // fresh x
        for (Runnable a : actions) { a.run(); }                               // prints: 333012
    }
}
```

```csharp
// rejected
static void bump(ref int total) {
    Runnable r = () -> total++;            // error: no capture of `ref`/`in`/`out` parameters
}
static void peek(Span<int> xs) {
    Supplier<int> first = () -> xs[0];     // error: a ref struct value cannot be captured
}
```

### 7.3 Checked exceptions

Java's hierarchy and checking, plus generic `throws E` for exception transparency.

```csharp
// file: demo/errors/Loader.rfl
namespace demo.errors;

import reflect.io;
import static reflect.io.Console;

public final class Loader {
    public String load(Path p) throws IOException {
        return Files.readString(p);
    }

    public void show(Path path) {
        try {
            println(load(path));
        } catch (FileNotFoundException | AccessDeniedException e) {   // multi-catch (Java)
            println("missing or denied: " + e.message);
        } catch (IOException e) {
            println("other IO failure");
        } finally {
            println("done");
        }
    }
}
```

Exception transparency: `E` is inferred from the lambda and reified.

```csharp
// file: demo/errors/Throwing.rfl
namespace demo.errors;

import reflect.collections;
import reflect.functions;

public final class Throwing {
    public static List<R> map<T, R, E extends Exception>(List<T> xs, ThrowingFunction<T, R, E> f)
            throws E {
        var out = new ArrayList<R>();
        for (T x : xs) { out.add(f.apply(x)); }
        return out;
    }

    public static T retry<T, E extends Exception>(int times, ThrowingSupplier<T, E> f) throws E {
        for (int i = 1; ; i++) {
            try { return f.get(); }
            catch (E e) { if (i == times) { throw e; } }    // catches exactly the reified E
        }
    }
}
```

```csharp
// file: demo/errors/Transparency.rfl
namespace demo.errors;

import reflect.collections;
import reflect.io;

public final class Transparency {
    static void run(List<Path> paths, List<String> words) throws IOException {
        List<String> texts = Throwing.map(paths, p -> Files.readString(p));   // E = IOException
        List<int> sizes = Throwing.map(words, w -> w.length);   // E = RuntimeException: nothing to handle
        String first = Throwing.retry(3, () -> Files.readString(paths[0]));
    }
}
```

```csharp
// rejected
static void quiet(List<Path> paths) {
    Throwing.map(paths, p -> Files.readString(p));   // error: unreported IOException
}
Function<Path, String> read = p -> Files.readString(p);   // error: Function.apply declares no IOException
```

Several checked exceptions infer an intersection for `E`: §3.8. Generic exception classes and catching by type argument:

```csharp
// file: demo/errors/ParseError.rfl
namespace demo.errors;

import static reflect.io.Console;

public record Json(String raw) { }

public class ParseError<T> extends Exception {
    public final T partial;
    public ParseError(String message, T partial) { super(message); this.partial = partial; }
}

public final class Parsing {
    static Json parse(String text) throws ParseError<Json> {
        if (!text.startsWith("{")) { throw new ParseError<Json>("expected {", new Json("")); }
        return new Json(text);
    }

    static Json readOrEmpty(String text) {
        try {
            return parse(text);
        } catch (ParseError<Json> e) {             // tests the type argument at run time
            return e.partial;
        }
    }

    static void mismatch() {
        try {
            throw new ParseError<String>("bad", "");
        } catch (ParseError<Json> e) {
            println("json");                       // skipped: the type argument differs
        } catch (ParseError<?> e) {
            println("other");                      // prints: other
        }
    }
}
```

### 7.4 try-with-resources

Java's statement and translation; `close()` failures become suppressed exceptions.

```csharp
// file: stdlib/reflect.core/AutoCloseable.rfl
namespace reflect.core;

public interface AutoCloseable { void close() throws Exception; }
```

```csharp
// file: demo/errors/Copy.rfl
namespace demo.errors;

import reflect.io;

public final class Copy {
    public static void copy(Path src, Path dst) throws IOException {
        try (var input = Files.openRead(src); var output = Files.openWrite(dst)) {
            input.copyTo(output);
        }   // closes output, then input, also on exceptions
    }

    public static void drain(InputStream source, Path dst) throws IOException {
        try (source; OutputStream out = Files.openWrite(dst)) {   // an existing variable as a resource
            source.copyTo(out);
        }
    }
}
```

### 7.5 Finalizers

C#-style destructors as a safety net next to try-with-resources; the queue drains only at
defined points.

```csharp
// file: demo/interop/NativeBuffer.rfl
namespace demo.interop;

public final class NativeBuffer implements AutoCloseable {
    private long handle;

    public NativeBuffer(int size) { handle = Natives.alloc(size); }

    public override void close() {
        release();
        GC.suppressFinalize(this);       // finalizer no longer needed (C#)
    }

    ~NativeBuffer() { release(); }      // safety net when close() was forgotten

    private void release() {
        if (handle != 0) { Natives.free(handle); handle = 0; }
    }
}
```

```csharp
// file: demo/interop/Buffers.rfl
namespace demo.interop;

public final class Buffers {
    static NativeBuffer? kept;

    public static void main(String[] args) {
        try (var scratch = new NativeBuffer(64)) { }   // closed, never finalized
        new NativeBuffer(32);                          // forgotten: finalizable once unreachable
        kept = new NativeBuffer(16);                   // reachable from a static: never finalized
        GC.collect();
        GC.runPendingFinalizers();                     // drains in allocation order
    }   // end of main: one full collection, then one drain
}
```

```csharp
// rejected
public struct Handle {
    ~Handle() { }                          // error: structs have no finalizers
}
```

### 7.6 async/await

```csharp
// future: after the preview; `async` and `await` are reserved now
public async Task<String> fetch(Uri uri) throws IOException {
    var response = await http.get(uri);
    return await response.readText();
}
```

Compiled to a state-machine class (C#).

### 7.7 Threads

Lock design pending review - Java model vs C# model - `synchronized` is reserved eithr way.
Below is the C# model.

```csharp
// future: after v1.0; `volatile` and `synchronized` are reserved now
public final class Counter {
    private final Lock lock = new Lock();
    private volatile boolean stopped;
    private int count;

    public void increment() {
        synchronized (lock) { count++; }       // only `Lockable` objects can be locked; `Lock` implements `Lockable`
    }
}
```

### 7.8 Exceptions raised by the language and the VM

Which construct throws what. The VM raises ordinary Reflect exceptions; reflection wraps callee failures.

```csharp
// file: demo/errors/Raised.rfl
namespace demo.errors;

import demo.generics;
import demo.kinds;
import demo.polygons;
import reflect.functions;
import static reflect.io.Console;

final class Broken {
    public static final int VALUE = compute();
    static int compute() { throw new IllegalStateException("boom"); }
}

public final class Raised {
    public static void fail() { throw new IllegalStateException("inner"); }

    static void expect(String label, ThrowingSupplier<Object?, Exception> body) {
        try {
            body.get();
            println(label + ": no exception");
        } catch (Throwable t) {
            println(label + ": " + t.getClass().name);
        }
    }

    public static void main(String[] args) {
        String? none = null;
        int[] one = new int[1];
        Object boxed = 42;

        expect("!", () -> none!.length);                     // NullPointerException
        expect("/", () -> 1 / one[0]);                       // ArithmeticException
        expect("checked", () -> checked(int.MAX_VALUE + one[0] + 1));   // ArithmeticException
        expect("index", () -> one[2]);                       // IndexOutOfBoundsException
        expect("cast", () -> (String) boxed);                // ClassCastException
        expect("create", () -> Kind.class.create(1));        // InstantiationError
        expect("abstract", () -> Polygon.summary());         // AbstractMemberError
        expect("init", () -> Broken.VALUE);                  // ExceptionInInitializerError
        expect("instantiate", () -> Cache.class.instantiate(int.class, String.class));   // TypeLoadError
        expect("invoke", () -> Raised.class.getMethod("fail")!.invoke(null));   // InvocationTargetException
    }
}
```

Compile-time code adds the unchecked `PhaseException` and, under `--deterministic`,
`DeterminismViolation`.

## 8. Operators

### 8.1 Declaring operators

Operators are `public static` methods; conversions are explicit only.

```csharp
// file: demo/math/Vec2.rfl
namespace demo.math;

import demo.data;

public struct Vec2 {
    public final double x;
    public final double y;

    public Vec2(double x, double y) { this.x = x; this.y = y; }

    public static Vec2 operator +(Vec2 a, Vec2 b) { return new Vec2(a.x + b.x, a.y + b.y); }
    public static Vec2 operator -(Vec2 v) { return new Vec2(-v.x, -v.y); }                  // unary
    public static Vec2 operator *(double k, Vec2 v) { return new Vec2(k * v.x, k * v.y); }  // 2 * v
    public static Vec2 operator *(Vec2 v, double k) { return k * v; }                       // v * 2
    public static Vec2 operator /(Vec2 v, double k) { return new Vec2(v.x / k, v.y / k); }
    public static Vec2 operator %(Vec2 v, double m) { return new Vec2(v.x % m, v.y % m); }  // wrap around
    public static boolean operator ==(Vec2 a, Vec2 b) { return a.x == b.x && a.y == b.y; }
    public static boolean operator !=(Vec2 a, Vec2 b) { return !(a == b); }
    public static explicit operator Vec2(Point p) { return new Vec2(p.x, p.y); }

    public override boolean equals(Object? o) { return o instanceof Vec2 v && this == v; }
    public override int hashCode() { return Double.hashCode(x) * 31 + Double.hashCode(y); }
}
```

```csharp
// file: demo/math/Vectors.rfl
namespace demo.math;

import demo.data;
import static reflect.io.Console;

public final class Vectors {
    public static void main(String[] args) {
        Vec2 a = new Vec2(1, 2);
        Vec2 b = 2 * a + new Vec2(0.5, 0);
        Vec2 c = (Vec2) new Point(3, 4);         // explicit conversions are used through casts only
        println(b == new Vec2(2.5, 4));          // prints: true
        println(-a != c);                        // prints: true
    }
}
```

```csharp
// rejected
Vec2 d = new Point(3, 4);                  // error: no implicit conversions
public struct Bad {
    public static Bad operator +(int a, int b) { return new Bad(); }   // error: a parameter must be Bad
    public static boolean operator <(Bad a, Bad b) { return false; }   // error: `<` needs `>` too
    public Bad operator -(Bad other) { return other; }                 // error: operators are static
    public static boolean operator &&(Bad a, Bad b) { return true; }   // error: never overloadable
}
```

### 8.2 The overloadable set

Bitwise and shift operators, `!`, `~`, increments and ordered comparisons.

```csharp
// file: demo/math/Bits.rfl
namespace demo.math;

public struct Bits {
    public final ulong mask;
    public Bits(ulong mask) { this.mask = mask; }

    public static Bits operator &(Bits a, Bits b) { return new Bits(a.mask & b.mask); }
    public static Bits operator |(Bits a, Bits b) { return new Bits(a.mask | b.mask); }
    public static Bits operator ^(Bits a, Bits b) { return new Bits(a.mask ^ b.mask); }
    public static Bits operator ~(Bits a) { return new Bits(~a.mask); }
    public static Bits operator <<(Bits a, int n) { return new Bits(a.mask << n); }
    public static Bits operator >>(Bits a, int n) { return new Bits(a.mask >> n); }
    public static Bits operator >>>(Bits a, int n) { return new Bits(a.mask >>> n); }
    public static boolean operator !(Bits a) { return a.mask == 0; }
}

public struct Version implements Comparable<Version> {
    public final int major;
    public final int minor;
    public Version(int major, int minor) { this.major = major; this.minor = minor; }

    public static Version operator +(Version v) { return v; }
    public static Version operator ++(Version v) { return new Version(v.major, v.minor + 1); }
    public static Version operator --(Version v) { return new Version(v.major, v.minor - 1); }
    public static boolean operator <(Version a, Version b) { return a.compareTo(b) < 0; }
    public static boolean operator >(Version a, Version b) { return a.compareTo(b) > 0; }
    public static boolean operator <=(Version a, Version b) { return a.compareTo(b) <= 0; }
    public static boolean operator >=(Version a, Version b) { return a.compareTo(b) >= 0; }

    public override int compareTo(Version o) { return major != o.major ? major - o.major : minor - o.minor; }
}

final class Releases {
    static boolean next(Version v) {
        Version old = v;
        v++;                                     // v = operator ++(v), as in C#
        return old < v && !(new Bits(0));        // true
    }
}
```

### 8.3 Compound assignment

An instance `operator +=` works in place; without one, `x += y` means `x = x + y` with `x`
evaluated once.

```csharp
// file: demo/math/Matrix.rfl
namespace demo.math;

public final class Matrix {
    private final double[] data;

    public Matrix(int n) { data = new double[n * n]; }
    private Matrix(double[] data) { this.data = data; }

    public static Matrix operator +(Matrix a, Matrix b) {   // returns a new Matrix
        var sum = new double[a.data.length];
        for (int i = 0; i < sum.length; i++) { sum[i] = a.data[i] + b.data[i]; }
        return new Matrix(sum);
    }

    public void operator +=(Matrix other) {                 // in place: instance method, returns void
        for (int i = 0; i < data.length; i++) { data[i] += other.data[i]; }
    }
}

public struct Accumulator {
    public long total;
    public mutating void operator +=(long x) { total += x; }
}

final class Compound {
    static void run(Matrix m, Matrix n, Vec2 v) {
        m += n;                                  // m.operator +=(n): no allocation
        Matrix s = m + n;                        // a new Matrix
        v += new Vec2(1, 1);                     // Vec2 has no `+=`: v = v + new Vec2(1, 1)
        var acc = new Accumulator();
        acc += 5;                                // in place on a local
    }
}
```

```csharp
// rejected
final class Holder { public Accumulator acc { get; set; } }
holder.acc += 5;                           // error: in-place struct operator on a property result
```

### 8.4 Indexers

`this[...]` with `get`/`set`, overloaded by parameter types, may throw.

```csharp
// file: demo/data/Grid.rfl
namespace demo.data;

import reflect.io;

public final class Grid<T extends class> {
    private final T?[] cells;
    private final int width;

    public Grid(int width, int height) { this.width = width; cells = new T?[width * height]; }

    public T? this[int x, int y] {
        get { return cells[y * width + x]; }
        set { cells[y * width + x] = value; }
    }

    public T? this[Point p] {                    // overload by parameter type
        get { return this[p.x, p.y]; }
        set { this[p.x, p.y] = value; }
    }
}

public final class FileLines {
    private final Path path;
    public FileLines(Path path) { this.path = path; }
    public String this[int line] { get throws IOException { return Files.readAllLines(path)[line]; } }
}

final class Board {
    static void place(Grid<String> grid, String player, String fallback) {
        grid[2, 3] = player;
        grid[2, 4] ??= fallback;                 // assigns only when the cell is null
        grid[new Point(0, 0)] = "origin";
    }
}
```

### 8.5 Equality

Generic `==`: §4.7.

```csharp
// file: demo/data/Equality.rfl
namespace demo.data;

import demo.kinds;
import demo.math;
import static reflect.io.Console;

public record Pair(int a, int b) { }

public final class Equality {
    public static void main(String[] args) {
        println(new Cube(1) == new Cube(1));                 // prints: false (identity: no operator ==)
        String s1 = "ab";
        String s2 = new StringBuilder("a").append("b").toString();
        println(s1 == s2);                                   // prints: true (String overloads ==)
        println(Object.referenceEquals(s1, s2));             // prints: false (identity: strings are objects)
        Object x = 42;
        Object y = 42;
        println(x == y);                                     // prints: true (boxes have no identity: class and value)
        println(new Pair(1, 2) == new Pair(1, 2));           // prints: true (records)
        println(new Vec2(1, 2) == new Vec2(1, 2));           // prints: true (declared on the struct)
        String? none = null;
        println(none == null);                               // prints: true (null test)
    }
}
```

```csharp
// rejected
boolean same = new Point(1, 2) == new Point(1, 2);   // error: struct without `operator ==`
```

### 8.6 Custom operators after v1

Swift-style operator and precedence-group declarations; syntax and placement are open for discussion.

```csharp
// future: after v1; `infix`, `prefix`, `postfix`, `precedence` are reserved now
precedence PipePrecedence { associativity left; lowerThan AdditivePrecedence; }
infix operator |> : PipePrecedence;

public final class Query {
    public static Query operator |>(Query q, Stage s) { return q.then(s); }
}

var active = source |> filter(isActive) |> sortBy(name);
```

## 9. Data modeling

### 9.1 Structs and ref safety

User value types plus C# 11 ref safety instead of a borrow checker.

An explicit set of `Borrow` types (treated differently by the compiler) might be added for extra safety in some circumstances, this is open to discussion.

```csharp
// file: demo/data/Point.rfl
namespace demo.data;

public struct Point {
    public int x;
    public int y;
    public Point(int x, int y) { this.x = x; this.y = y; }
}

public struct Pin {
    public Point at;
    public String? label;                 // reference fields of structs are nullable
}
```

```csharp
// file: demo/data/Structs.rfl
namespace demo.data;

import static reflect.io.Console;

public final class Structs {
    public static void main(String[] args) {
        Point p = new Point(1, 2);
        Point q = p;                      // copy
        q.x = 5;                          // p.x is still 1
        Object o = p;                     // boxing
        Point back = (Point) o;           // unboxing
        Pin blank = default(Pin);         // all zero: at = (0, 0), label = null
        Point[] pts = new Point[3];       // zero-initialized elements
        println(p.x);                     // prints: 1
    }
}
```

```csharp
// file: demo/data/Refs.rfl
namespace demo.data;

import demo.math;
import static reflect.io.Console;

public final class Refs {
    public static void scale(ref Vec2 v, double k) { v = k * v; }    // by-reference parameter
    public static double length(in Vec2 v) { return Math.sqrt(v.x * v.x + v.y * v.y); }   // read-only

    public static boolean tryParse(String s, out int result) {       // must assign result
        result = 0;
        for (int i = 0; i < s.length; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') { return false; }
            result = result * 10 + (c - '0');
        }
        return s.length > 0;
    }

    public static ref int slot(int[] xs, int i) { return ref xs[i]; }   // ref return

    public static int sum(scoped Span<int> xs) {                      // xs cannot escape this call
        int total = 0;
        for (int x : xs) { total += x; }
        return total;
    }

    public static void main(String[] args) {
        Vec2 a = new Vec2(1, 2);
        scale(ref a, 3);                                  // `ref`/`out` repeated at the call site (C#)
        println(length(a));
        if (tryParse("42", out int n)) { println(n); }   // prints: 42
        if (!tryParse("x", out var bad)) { println(bad); }   // prints: 0

        int[] numbers = { 1, 2, 3, 4, 5, 6, 7, 8 };
        ref int s = ref slot(numbers, 3);
        s = 0;                                            // writes numbers[3]
        Span<int> window = numbers.asSpan(2, 5);          // zero-copy slice of 3, 0, 5, 6, 7; Array.asSpan
        println(sum(window));                             // prints: 21
    }
}
```

`Span<T>` is a well-known `ref struct` of `reflect.core`
([architecture §6](architecture.md#6-dependency-rules)).

```csharp
// rejected
public struct Named { public String name; }        // error: reference-typed struct fields must be nullable
final class Keeper { Span<int> kept; }              // error: a ref struct never reaches the heap
Object boxedSpan = window;                          // error: no boxing of ref structs
static ref int dangling() { int local = 1; return ref local; }   // error: a reference to a local escapes
static Span<int> leak(scoped Span<int> xs) { return xs; }        // error: a scoped value escapes
```

### 9.2 Records

Java record syntax for classes, C# `record struct` for values; both generate value equality.

```csharp
// file: demo/data/Records.rfl
namespace demo.data;

import static reflect.io.Console;

public record Point3(int x, int y, int z) { }               // reference record (Java syntax)

public record Range(int start, int end) {
    public Range {                                           // compact constructor (Java)
        if (start > end) { throw new IllegalArgumentException("start > end"); }
    }
    public int length { get { return end - start; } }
}

public record struct Rgb(ubyte r, ubyte g, ubyte b) { }     // value record (C# `record struct`)

public final class Records {
    public static void main(String[] args) {
        var p = new Point3(1, 2, 3);
        println(p.x);                                        // prints: 1 (get-only property)
        println(p);                                          // prints: Point3[x=1, y=2, z=3]
        println(p == new Point3(1, 2, 3));                   // prints: true
        if (p instanceof Point3(int x, int y, int z) && z > 0) { println(x + y); }   // prints: 3
        println(new Rgb(255, 0, 0) == new Rgb(255, 0, 0));   // prints: true
        new Range(5, 1);                                     // throws: IllegalArgumentException
    }
}
```

```csharp
// rejected
p.x = 5;                                   // error: record components are get-only
```

### 9.3 Enums

One value-type enum kind with an integral backing type, Java-style per-constant members and
`@Flags`. Rules: [type-system §11, DAT-05](type-system.md#11-structs-ref-safety-records-enums-sealed-types-tuples-destructible-objects-dat).

```csharp
// file: demo/data/Enums.rfl
namespace demo.data;

import static reflect.io.Console;

public enum Color { RED, GREEN, BLUE }                     // value type backed by an int ordinal

public enum Level : ubyte { LOW = 1, HIGH = 200 }          // any integral backing type

@Flags
public enum Permission : int { NONE = 0, READ = 1, WRITE = 2, EXECUTE = 4 }

public const enum Planet {
    MERCURY(3.303e+23, 2.4397e6),
    EARTH(5.976e+24, 6.37814e6);

    private const double G = 6.67300E-11;
    public final double mass;                              // per-constant data
    public final double radius;

    Planet(double mass, double radius) { this.mass = mass; this.radius = radius; }

    public const double surfaceGravity() { return G * mass / (radius * radius); }   // folds on a constant receiver
}

public enum Op {
    ADD { public override int apply(int a, int b) { return a + b; } },
    MUL { public override int apply(int a, int b) { return a * b; } };

    public abstract int apply(int a, int b);
}

public final class Enums {
    static String temperature(Color c) {
        return switch (c) {                                // exhaustive without default
            case RED -> "warm";
            case GREEN, BLUE -> "cool";
        };
    }

    public static void main(String[] args) {
        Permission rw = Permission.READ | Permission.WRITE;   // bit operators only on @Flags enums
        println((int) rw);                                     // prints: 3
        println((rw & Permission.WRITE) != Permission.NONE);  // prints: true (enum `==`: see type-system.md DAT-05)
        println(Color.valueOf("GREEN").ordinal);              // prints: 1
        println(Color.BLUE.name);                             // prints: BLUE
        for (Color c : Color.values()) { print(temperature(c) + " "); }   // prints: warm cool cool
        println(Op.MUL.apply(6, 7));                          // prints: 42
        println((int) Level.HIGH);                            // prints: 200
        Permission all = (Permission) 7;                      // undeclared combination: fine for @Flags
        Color odd = (Color) 7;                                // throws: undeclared value (unlike C#), see type-system.md DAT-05
    }
}
```

```csharp
// rejected
Color mix = Color.RED | Color.BLUE;        // error: bit operators need @Flags
int i = Color.RED;                         // error: conversions are explicit
@Flags
enum Mode : int {
    A = 1 { public int x() { return 1; } } // error: @Flags enums have no per-constant bodies or fields
}
```

### 9.4 Sealed hierarchies and pattern matching

Java 17 `sealed`/`permits`/`unsealed`, Java 21 patterns and guards. Stage0 supports type
patterns only; deconstruction and guards come with `rflc`.

```csharp
// file: demo/data/Expr.rfl
namespace demo.data;

import demo.props;
import static reflect.io.Console;

public sealed interface Expr permits Num, Add, Mul, Neg { }
public record Num(int value) implements Expr { }
public record Add(Expr left, Expr right) implements Expr { }
public record Mul(Expr left, Expr right) implements Expr { }
public unsealed class Neg implements Expr {             // open again below this point
    public final Expr inner;
    public Neg(Expr inner) { this.inner = inner; }
}

public final class Evaluator {
    public static int eval(Expr e) {
        return switch (e) {
            case Num n -> n.value;
            case Add(Expr l, Expr r) -> eval(l) + eval(r);    // record deconstruction pattern
            case Mul m when isZero(m.left) -> 0;              // guard (Java 21 `when`)
            case Mul m -> eval(m.left) * eval(m.right);
            case Neg g -> -eval(g.inner);
        };                                                     // exhaustive: no default needed
    }

    static boolean isZero(Expr e) { return e instanceof Num n && n.value == 0; }

    static void inspect(Figure f) {
        if (f instanceof Circle c && c.radius > 10) { println("big circle"); }
    }

    public static void main(String[] args) {
        println(eval(new Add(new Num(2), new Mul(new Num(3), new Num(4)))));   // prints: 14
    }
}
```

```csharp
// rejected
public record Pow(Expr base, int exp) implements Expr { }   // error: Pow is not in the permits list
static int partial(Expr e) {
    return switch (e) { case Num n -> n.value; };            // error: not exhaustive (Add, Mul, Neg missing)
}
```

### 9.5 Tuples

Value tuples with optional element names that are not part of the type.

```csharp
// file: demo/data/Tuples.rfl
namespace demo.data;

import static reflect.io.Console;

public final class Tuples {
    public static (int min, int max) range(int[] xs) {
        int lo = int.MAX_VALUE;
        int hi = int.MIN_VALUE;
        for (int x : xs) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
        return (lo, hi);
    }

    public static void main(String[] args) {
        (int, String) pair = (1, "one");
        (int count, String label) named = (2, "two");
        println(named.count);                         // prints: 2
        pair = named;                                 // names are not part of the type
        var (lo, hi) = range(new int[] { 3, 9, 1 });  // deconstruction
        println($"{lo}..{hi}");                       // prints: 1..9
    }
}
```

`(...)` followed by `->` is a lambda; casts, parenthesized expressions and tuples use C#'s
disambiguation ([grammar §4](grammar.md#4-ambiguities-and-how-they-are-resolved)).

### 9.6 Destructible objects

Uses one flag bit in the object header to indicate existence.

`Destructible`s are still `Object`s.

Explicitly not the same as `null`.

Order: the onDestroy() hook runs, then a destroying event is raised, then the flag is set in a finally. Other objects can still query it during the hook, and calling destroy again from inside the hook does nothing.

Access to destruction may be permitted via a static method on `Object` such as Unity's `Object.Destroy()`. Open for discussion, a closed implementation is shown below.

```csharp
// file: stdlib/reflect.core/Destructible.rfl
namespace reflect.core;

public abstract class Destructible {                  // VM-known root class; no structs or records
    public boolean destroyed { get; }                 // header flag; never throws
    public event Runnable destroying;                 // raised in destroy(), before the flag is set
    protected final void destroy() { /* idempotent: onDestroy(), destroying, then flag in finally */ }
    protected virtual void onDestroy() { }
    public static T? alive<T extends Destructible>(T? x) { return x != null && !x.destroyed ? x : null; }
}
```

```csharp
// file: demo/actors/Actor.rfl
namespace demo.actors;

import reflect.core;

public class Actor extends Destructible {
    private final Texture texture;                    // `Texture` is illustrative
    public Vec2 position { get; set; }
    public Actor(Texture texture) { this.texture = texture; }
    public void despawn() { destroy(); }              // each class decides who may destroy it
    protected override void onDestroy() { texture.release(); GC.suppressFinalize(this); }
    ~Actor() { texture.release(); }                   // finalizer safety net; suppressed once destroyed
}
```

```csharp
// file: demo/actors/Main.rfl
namespace demo.actors;

import static reflect.io.Console;

public final class Main {
    public static void main(String[] args) {
        Actor actor = new Actor(new Texture("hero.png"));
        Actor alias = actor;
        actor.despawn();
        println(alias.destroyed);                         // true; alias is still not null
        alias.position = new Vec2(0, 0);                  // throws ObjectDestroyedException (unchecked)
        Actor? live = Destructible.alive(alias);          // null: an explicit conversion that works with ?. and ??
    }
}
```

## 10. Reflection API (run time)

`reflect.meta`; `Class<T>` lives in `reflect.core`. API surface:
[reflection §1](reflection.md#1-api-surface-reflectmeta). Reflective actions declare checked
exceptions, so these `main` methods say `throws Exception`; lookups return `null` for a
missing member, hence the `!`.

### 10.1 Inspection and invocation

Static virtual members are first-class: a member remembers the class it was looked up through
(`reflectedClass`), which becomes the receiver class of reflective static calls.

```csharp
// file: demo/meta/Inspect.rfl
namespace demo.meta;

import demo.kinds;
import reflect.meta;
import static reflect.io.Console;

public final class Inspect {
    public static void main(String[] args) throws Exception {
        Kind kind = new Cube(1);
        Class<? extends Kind> c = kind.getClass();
        println(c.name);                         // prints: demo.kinds.Cube
        println(c.superclass?.name);             // prints: demo.kinds.Kind
        for (Property p : c.properties) {
            println($"{p.name}: {p.type.name} static={p.isStatic} virtual={p.isVirtual}");
        }

        Property value = c.getProperty("value")!;
        println(value.get(null));                // prints: CUBE_KIND (static virtual resolved for Cube)

        Method describe = c.getMethod("describe")!;
        println(describe.declaringClass.name);   // prints: demo.kinds.Kind
        println(describe.reflectedClass.name);   // prints: demo.kinds.Cube
        println(describe.invoke(null));          // prints: Kind: CUBE_KIND (receiver class = c)

        Property base = Kind.class.getProperty("value")!;
        println(base.implementationFor(Sphere.class).declaringClass.name);   // prints: demo.kinds.Kind
    }
}
```

### 10.2 Generic types at run time

```csharp
// file: demo/meta/Generic.rfl
namespace demo.meta;

import demo.kinds;
import reflect.collections;
import static reflect.io.Console;

public final class Generic {
    public static void main(String[] args) throws Exception {
        Class<?> def = ArrayList.class;                    // generic definition
        Class<?> cubes = def.instantiate(Cube.class);      // ArrayList<Cube>, created at run time
        Object list = cubes.getConstructor()!.invoke();   // reflective construction
        cubes.getMethod("add", Cube.class)!.invoke(list, new Cube(1));
        println(cubes.typeArguments[0].name);              // prints: demo.kinds.Cube
        println(list.getClass() == cubes);                 // prints: true (one Class per instantiation)
        println(list instanceof List<Cube>);               // prints: true
    }
}
```

### 10.3 Access control

Private members are reachable reflectively only through `opens`. Module `demo` opens
`demo.bank` to `reflect.json` only; `Account.cents` is private.

```csharp
// file: reflect.json/JsonWriter.rfl
namespace reflect.json;

import reflect.meta;

public final class JsonWriter {
    public static String write(Object o) throws Exception {
        var sb = new StringBuilder("{ ");
        for (Field f : o.getClass().fields) {
            sb.append($"\"{f.name}\": {f.get(o)} ");   // private fields too: demo.bank is open to reflect.json
        }
        return sb.append("}").toString();
    }
}
```

```csharp
// file: plugins/Snoop.rfl
namespace plugins;

import reflect.meta;

public final class Snoop {
    public static long peek(Object account) throws Exception {
        Field cents = account.getClass().getField("cents")!;  // lookup works; access is checked at use
        return (long) cents.get(account);                      // throws: IllegalAccessException (checked)
    }
}
```

### 10.4 Proxies

Proxies arrive in v0.6, before the verifier, so the calling module declares `unsafe;`.
The handler may throw anything (`throws Throwable`); a checked exception that the proxied method
does not declare reaches the caller as `UndeclaredThrowableException`.

```csharp
// file: demo/meta/Proxies.rfl
namespace demo.meta;

import reflect.meta;
import reflect.meta.dynamic;
import static reflect.io.Console;

public interface Greeter { String greet(String name); }

public final class Proxies {
    public static Greeter logged(Greeter real) {
        return Proxy.create<Greeter>((proxy, method, args) -> {   // demo is `unsafe;`
            println("call " + method.name);
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.cause!;                    // rethrow what `real` threw, unwrapped
            }
        });
    }
}
```

```csharp
// rejected
// in module plugins.host, which does not declare `unsafe;`
Greeter g = Proxy.create<Greeter>((p, m, a) -> null);   // error: needs an unsafe module until the verifier
```

### 10.5 Emitting code

The emit API is the compiler's own bytecode writer [Proposed].

```csharp
// file: demo/meta/Emit.rfl
namespace demo.meta;

import reflect.collections;
import reflect.meta;
import reflect.meta.dynamic;
import static reflect.io.Console;

public final class Emit {
    public static void main(String[] args) throws Exception {   // demo is `unsafe;` until the verifier
        var mb = ModuleBuilder.create("dyn");
        var cb = mb.defineClass("dyn.Adder", Object.class, Modifier.PUBLIC | Modifier.FINAL);
        var add = cb.defineMethod("add", Modifier.PUBLIC | Modifier.STATIC,
                                  int.class, List.of(int.class, int.class));
        var code = add.body();                  // final class: no hidden receiver argument
        code.ldarg(0); code.ldarg(1); code.add(Type.INT); code.ret();
        Class<?> adder = mb.load().getClass("dyn.Adder")!;
        println(adder.getMethod("add", int.class, int.class)!.invoke(null, 2, 3));  // prints: 5
    }
}
```

### 10.6 Hot reload

Modeled on .NET Edit and Continue.

Add-only changes in v0.

```csharp
// file: demo/meta/Reload.rfl
namespace demo.meta;

import reflect.io;
import reflect.meta;
import reflect.meta.dynamic;

public final class Reload {
    public static void apply(Path deltaPath) throws IOException {
        HotReload.apply(ModuleDelta.read(deltaPath));   // delta produced by `rflc delta`
    }
}
```

```csharp
// file: demo/meta/Greeting.rfl (before)
namespace demo.meta;

public final class Greeting {
    public String text() { return "hello"; }
}
```

```csharp
// file: demo/meta/Greeting.rfl (after: a valid delta)
namespace demo.meta;

public final class Greeting {
    private int calls;                                   // added field: side storage for old objects
    public String text() { calls++; return "hello again"; }   // replaced body: new calls only
    public String shout() { return text().toUpperCase(); }    // added method
}
```

A delta that removes `text()`, changes its signature, or changes the base class is rejected in v0.

## 11. Under the hood: lowering and bytecode

### 11.1 Static virtual dispatch in bytecode

`.rfla` text for parts of §4.1 [Proposed].

```text
.module demo 0.1.0
.requires reflect.core

.class public abstract demo.kinds.Kind {
    .property static virtual reflect.core.String value { .get get_value }
    .method public static virtual reflect.core.String get_value() {
        ldsfld   demo.kinds.Kind::value$field      // each overrider's accessor reads its own storage
        ret
    }
}
```

`Demo.function<K>` : `Demo` is final, so no hidden receiver; `K` arrives in the type
context.

```text
.method public static void function<K extends demo.kinds.Kind>(!!K kind)
{
    ldarg       kind                       // object accessor
    getclass                               // run-time class of kind
    call.svirt  demo.kinds.Kind::get_value()   // static-virtual dispatch through a class handle
    call        reflect.io.Console::println(reflect.core.Object?)

    ldtype      !!K                        // type accessor: reified K from the type context
    call.svirt  demo.kinds.Kind::get_value()
    call        reflect.io.Console::println(reflect.core.Object?)
    ret
}
```

Static methods of non-final or generic classes take the receiver class as a hidden first argument
. `Kind.describe()` and two of its call sites:

```text
.method public static reflect.core.String describe()
{
    ldstr       "Kind: "
    ldarg       $receiver                  // hidden receiver class
    call.svirt  demo.kinds.Kind::get_value()   // unqualified `value`: late static binding
    call        reflect.core.String::concat(reflect.core.String, reflect.core.String)
    ret
}

    ldtype      demo.kinds.Kind            // Kind.describe(): receiver Kind
    call        demo.kinds.Kind::describe()
    ldloc       k                          // k.describe(): receiver = run-time class of k
    getclass
    call        demo.kinds.Kind::describe()
```

### 11.2 Reified type tests and catches

```text
.method static boolean isA<T>(reflect.core.Object? o)       // §3.3
{
    ldarg       o
    instanceof  !!0                        // T read from the type context
    ret
}

    ldloc       o                          // o instanceof List<? extends Kind>
    instanceof  reflect.collections.List<? extends demo.kinds.Kind>   // TypeSpec with a wildcard

.method public static !!0 retry<T, E extends reflect.core.Exception>(int times, ...)   // §7.3
{
    .try {
        ...
    } .catch !!1 {                         // catch type MVAR 1: matched against the reified E
        ...
    }
}
```

### 11.3 Lowered events and captures

Lowering recipes. `$` cannot appear in source identifiers, so compiler-made names use it.

```csharp
// lowered: Button.clicked from §5.2
private ListenerList<Consumer<ClickEvent>>? clicked$listeners;   // allocated on first +=

public void add_clicked(Consumer<ClickEvent> listener) {
    clicked$listeners ??= new ListenerList<>();
    clicked$listeners.add(listener);
}

public void remove_clicked(Consumer<ClickEvent> listener) { clicked$listeners?.removeLast(listener); }

private void raise_clicked(ClickEvent e) {
    var snapshot = clicked$listeners?.snapshot();
    if (snapshot == null) { return; }
    Throwable? first = null;
    for (Consumer<ClickEvent> listener : snapshot) {
        try {
            listener.accept(e);
        } catch (Error error) {               // an Error ends the raise at once
            if (first != null) { error.addSuppressed(first); }
            throw error;
        } catch (Throwable t) {               // run every listener, keep the first failure
            if (first == null) { first = t; } else { first.addSuppressed(t); }
        }
    }
    if (first != null) { throw first; }       // exactly what Consumer.accept declares
}
```

```csharp
// lowered: Captures.main from §7.2 [P shape]
final class main$Counter { int counter; }    // captured and mutated: moved to a heap display
final class main$Loop { int i; }             // classic for: one display outside the loop
final class main$Each { int x; }             // enhanced for: a fresh display per iteration
```

### 11.4 Unsafe code

Any module may declare `unsafe;` and use pointers.

```csharp
// file: demo/interop/Raw.rfl
namespace demo.interop;

public final class Raw {
    public static int sumTo(int count) {
        unsafe {                                   // module demo declares `unsafe;`
            int* buf = stackalloc int[count];      // [Proposed]
            for (int i = 0; i < count; i++) { *(buf + i) = i; }
            int total = 0;
            for (int i = 0; i < count; i++) { total += buf[i]; }
            return total;
        }
    }
}
```

```csharp
// file: vm/src/gc/Pages.rfl
// module reflect.vm declares `unsafe;`
namespace reflect.vm.gc;

public struct PageHeader {
    public PageHeader* next;
    public uint sizeClass;
    public uint freeCount;
}

public final class Pages {
    const int PAGE_SIZE = 65536;

    extern static ubyte* mmap(ubyte* addr, ulong length, int prot, int flags, int fd, long offset);   // [Proposed]

    private static PageHeader* freeList;

    public static PageHeader* take(uint sizeClass) {
        PageHeader* page = freeList;
        if (page == null) {
            page = (PageHeader*) mmap(null, PAGE_SIZE, 3, 0x22, -1, 0);
        } else {
            freeList = (*page).next;               // `->` is the lambda arrow, see type-system.md UNS-01
        }
        (*page).sizeClass = sizeClass;
        (*page).freeCount = (uint) (PAGE_SIZE - sizeof(PageHeader)) / sizeClass;
        return page;
    }

    public static ulong fnv1a(ubyte* data, int length) {
        ulong h = 14695981039346656037UL;
        for (int i = 0; i < length; i++) { h = (h ^ data[i]) * 1099511628211UL; }   // wraps
        return h;
    }
}
```

## 12. Tooling and tests

### 12.1 Test files

Source tests carry their expectations in leading comments.

```csharp
// file: tests/lang/static-virtual/type-accessor.rfl
// expect-output: CUBE_KIND
// expect-output: NO_KIND
// requires-feature: static-virtual
namespace tests.staticvirtual;

import static reflect.io.Console;

public abstract class Kind { public static virtual String value { get; } = "NO_KIND"; }
public class Cube extends Kind { public static override String value { get; } = "CUBE_KIND"; }

public final class Main {
    static String read<K extends Kind>() { return K.value; }

    public static void main(String[] args) {
        println(read<Cube>());
        println(read<Kind>());
    }
}
```

```csharp
// file: tests/lang/static-virtual/abstract-through-name.rfl
// expect-error: RFL0412 @ 11:54
// requires-feature: static-virtual
namespace tests.staticvirtual;

import static reflect.io.Console;

public abstract class Shape { public static abstract String label { get; } }

public final class Main {
    public static void main(String[] args) { println(Shape.label); }
}
```

Reflect unit tests are found through reflection (JUnit syntax):

```csharp
// file: compiler/tests/LexerTests.rfl
namespace reflect.compiler.tests;

import reflect.compiler.syntax;
import reflect.testing;

public final class LexerTests {
    @Test
    public static void splitsNestedGenericClosers() {
        var tokens = Lexer.tokenize("List<List<int>>");
        Assert.equal(7, tokens.size);              // the two `>` stay separate tokens
    }
}
```

### 12.2 Command lines

Paths are illustrative.

Command-line may change, depending on implementation review. This is open for discussion.

```text
$ rflc build --module-path stdlib/out --out out demo
$ rfl run out/demo.rflc                          # starts demo.App, the descriptor's `main`
Hello, world!
$ rfl run --main demo.zoo.Zoo out/demo.rflc
Dog (mixed): Rex says woof
4
1
1
$ rflc build --deterministic --emit-generated gen --depfile out/demo.d --out out demo
                                 # confined generator IO: every file they read
$ ls gen/demo/model
Model.g.rfl  Point2.g.rfl  Settings.g.rfl  Document.g.rfl
$ rflc build -G observable.trace=true --out out demo      # generator option (reflection.md §3)
$ rflc delta --base out/demo.rflc demo                    # hot-reload delta for §10.6
$ rfld out/demo.rflc > demo.rfla                          # disassemble
$ rfla demo.rfla -o check.rflc && cmp check.rflc out/demo.rflc   # round trip: identical bytes
```

### 12.3 Stage0 Java and its transliteration

Stage0 is written in "R0-shaped Java" and transliterated once into the first `rflc`.

```java
// stage0: src/main/java/reflect/compiler/syntax/TokenList.java
package reflect.compiler.syntax;

import java.util.ArrayList;
import java.util.List;
import static reflect.compiler.Diagnostics.error;

public class TokenList {
    private final List<Integer> kinds = new ArrayList<>();

    public int count() { return kinds.size(); }

    @Virtual
    public int kindAt(int i) {
        if (i >= kinds.size()) { error("token index " + i); }
        return kinds.get(i);
    }

    static final class Cursor {
        int index;
    }
}

final class CompactTokenList extends TokenList {
    @Override
    public int kindAt(int i) { return super.kindAt(i) & 0xFF; }
}
```

```csharp
// file: compiler/reflect/compiler/syntax/TokenList.rfl
namespace reflect.compiler.syntax; // package becomes namespace

import reflect.collections;
import static reflect.compiler.Diagnostics;

public class TokenList {
    private final List<int> kinds = new ArrayList<>();       // Integer becomes int

    public int count() { return kinds.size; }               // size() becomes a property

    public virtual int kindAt(int i) {                      // @Virtual becomes `virtual`
        if (i >= kinds.size) { error("token index " + i); }
        return kinds.get(i);
    }

    final class Cursor {                                    // no `static`
        int index;
    }
}

final class CompactTokenList extends TokenList {
    public override int kindAt(int i) { return super.kindAt(i) & 0xFF; }   // @Override becomes `override`
}
```
