
# New language: Reflect

## File extensions: `.rfl`

## Design

### Key:
- Invariant: not up for debate
- Desired: requested but can be discussed further, requires significant persuasion if changing
- Up for discussion: intended goal but fairly loose aim overally, depends on the design of the rest of the requests

### Proposals

Object-oriented programming language with inheritance, relatively standard model - up for discussion.

1. It must be able to compile itself soon. We do not want to maintain a separate language tooling for long. This is a priority - Invariant
2. Reflection is a first-class feature and priority (runtime and compile-time) - Invariant
3. It must use a Java/C#-like syntax - Invariant
4. It must run inside a Virtual Machine using custom bytecode/IL - similar to Java - for performance reasons, we may reconsider this - Desired but not an Invariant
5. It must implement some kind of static virtual dispatch - Desired
6. It must guarantee memory safety - Desired, method of memory safety is up for debate. Obviously, we'd prefer a Rust model and avoid a GC but this can be reconsidered

Other design requirements, less important in first draft but still required for release:
7. Annotations / Functional Interfaces / Lambdas / Callbacks - Invariant
8. Generated methods / Property dispatch/handling is a first-class feature - Invariant
9. Operator overriding / custom operators is a first-class feature - Invariant

Overview of Design:

1. Compile itself - this is a priority as we do not want to have to maintain outside tooling to keep it running.
A minimal standard library can be drafted and made using external tooling (using C++ for example).
However, every build from general release v1.0.0 should maintain itself (all versions drafted now will be v0.0.1-0.9.9 depending on complexity).

2. Reflection first-class.
The functionality of templating and generics should be supported day one. Java suffers from type erasure whilst C++ requires external build
tooling such as the UnrealHeaderTool to achieve reflection. We will aim for something similar to C#. Extensive runtime and compile-time reflection is mandatory.

3. Java/C# syntax.
This is a group/personal gripe and is not up for debate. Rust syntax reads horribly whilst advanced C++ templates become cumbersome to understand.
Java/C# hit a sweet spot for keyword usage (and admittedly often over-usage) and makes the language intent clear. Rust often mixes high-level and low-level.
C/C++/Python have useable components but structuring is nicer in Java/C#. This would also focus on prioritising interfaces and abstract classes.

4. Virtual Machine / Bytecode
Originally, there was an intention to use LLVM as a backend. A VM complicates things in my mind but the design fits better with what we imagine the language to look like.
C# handles CIL (Common Intermediate Language) to perform a lot of its tasks, same with Java bytecode, but I am aware it could have impacts on other areas of design.
A technical challenge we'd prefer to try out at least, depending on benefits and risks.
An AOT option and JIT compiler would also be preferred for performance.

5. Static Virtual Dispatch
The original concept was for accessing static members of inherited classes and taking the inherited static value.
For example:
```
abstract class Kind { public static string Value = "NO_KIND"; }

class Cube : Kind { public static string Value = "CUBE_KIND"; }

void <K extends Kind> Function(K kind) {
    print(kind.Value); // object accessor - prints "CUBE_KIND" if a Cube object is passed inside
}

OR

class OtherObject<K extends Kind> { String ReturnReflectedKind() { return K.Value; } } // type accessor - resolves to whatever class is passed in
```

6. Memory Safety
Arguments behind both major memory models, Borrow checking and Garbage collection, are very well established. We do not want to relitigate them here, only picking the one most appropriate.
We would lean towards garbage collection, as does Java and C#, however if more advanced methods could be implemented (such as borrow checking or something close to it), we would absolutely
want to pursue that. We'd be happy to see what the options / discussions are available.

7. Annotations / Functional Interfaces / Lambdas / Callbacks
This borrows heavily from Java and C# and they work exceedingly well. We'd want to keep them.

8. Generated methods / Property dispatch
This is a part of point 7 but an annotated member with @Property (for example, using the Java synax here) should support listeners and reporting on state changes.
The compiler would handle automatic method generation and make it clear what methods can be generated - this includes ToString-like methods (though they could be overriden).
For example:
```
@Property
public int value;

void Function() {
    value = 10; // triggers a custom onValueChanged generated method
    value.onValueChanged().addListener((x) -> { print(x) });
}
```

9. Operator overloading / custom operators
Custom operator overloading would make adding two types together as simple as `typeX + typeX = result` - this is an invariant
This also goes for overriding assignment operators
Custom operators is a discussion topic but something we'd like to consider
