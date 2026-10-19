# References and prior art

Grouped by topic; each entry notes why it matters for Reflect.

## Generics, variance and type systems

- Andrew Kennedy, Don Syme. *Design and Implementation of Generics for the .NET Common Language
  Runtime.* PLDI 2001. The model for reified generics with shared and specialized code (G1).
- ECMA-335, *Common Language Infrastructure*, 6th edition, 2012. Metadata tables, signatures,
  exception clauses, constrained calls ([bytecode.md](bytecode.md)).
- Gilad Bracha, Martin Odersky, David Stoutamire, Philip Wadler. *Making the Future Safe for the
  Past: Adding Genericity to the Java Programming Language.* OOPSLA 1998. Erasure, the design
  Reflect avoids.
- Atsushi Igarashi, Benjamin C. Pierce, Philip Wadler. *Featherweight Java: A Minimal Core
  Calculus for Java and GJ.* TOPLAS 23(3), 2001. Basis of Featherweight Reflect.
- Atsushi Igarashi, Mirko Viroli. *On Variance-Based Subtyping for Parametric Types.* ECOOP 2002.
- Mads Torgersen, Christian Plesner Hansen, Erik Ernst, Peter von der Ahé, Gilad Bracha, Neal
  Gafter. *Adding Wildcards to the Java Programming Language.* SAC 2004.
- Mads Torgersen, Erik Ernst, Christian Plesner Hansen. *Wild FJ.* FOOL 2005.
- Andrew J. Kennedy, Benjamin C. Pierce. *On Decidability of Nominal Subtyping with Variance.*
  FOOL/WOOD 2007.
- Ross Tate, Alan Leung, Sorin Lerner. *Taming Wildcards in Java's Type System.* PLDI 2011. The
  four restrictions are adopted for Reflect's wildcards (F31).
- Radu Grigore. *Java Generics are Turing Complete.* POPL 2017. Why the restriction is needed.
- Jana Dunfield, Neelakantan R. Krishnaswami. *Bidirectional Typing.* ACM Computing Surveys
  54(5), 2021.
- *The Java Language Specification, Java SE 21* (inference, definite assignment, exceptions) and
  the *C# Language Specification* with Microsoft's feature notes for static abstract interface
  members (C# 11), generic math (.NET 7), ref safety and `scoped` (C# 11), the `field` keyword and
  user-defined compound assignment (C# 14).

## Class-level dispatch

- Adele Goldberg, David Robson. *Smalltalk-80: The Language and its Implementation.*
  Addison-Wesley, 1983. Metaclasses: classes as objects with their own methods.
- Delphi (Object Pascal) class references and virtual constructors.
- Swift: class methods, `type(of:)`, `required` initializers, dynamic `Self`.
- PHP: late static binding (`static::`), the closest precedent for SV4.

## Reflection and metaprogramming

- Brian Cantwell Smith. *Procedural Reflection in Programming Languages.* PhD thesis, MIT, 1982.
- Gilad Bracha, David Ungar. *Mirrors: Design Principles for Meta-level Facilities of
  Object-Oriented Programming Languages.* OOPSLA 2004. Design principles behind one API with
  several implementations (R4).
- Tim Sheard, Simon Peyton Jones. *Template Meta-programming for Haskell.* Haskell Workshop 2002.
- Nicolas Stucki, Aggelos Biboudis, Martin Odersky. *A Practical Unification of Multi-stage
  Programming and Macros.* GPCE 2018. Scala 3 quotes and splices, the closest model for F10.
- WG21 P2996, *Reflection for C++26* (Childers, Dimov, Katz, Revzin, Sutton, Vali,
  Vandevoorde). Compile-time reflection in a systems language.
- JSR 269, Java's pluggable annotation processing; Roslyn source generators (C#); Rust procedural
  macros. Existing generator models: rounds, additive output, separate compilation.
- Project Lombok, JavaFX properties, CommunityToolkit.Mvvm `[ObservableProperty]`, Kotlin
  `Delegates.observable`: prior art for `@Observable`.

## Self-hosting and bootstrapping

- Ken Thompson. *Reflections on Trusting Trust.* CACM 27(8), 1984.
- David A. Wheeler. *Fully Countering Trusting Trust through Diverse Double-Compiling.* PhD
  dissertation, George Mason University, 2009.
- Dan Ingalls, Ted Kaehler, John Maloney, Scott Wallace, Alan Kay. *Back to the Future: The Story
  of Squeak, a Practical Smalltalk Written in Itself.* OOPSLA 1997. VM written in a subset
  translated to C, the model for Reflect-S.
- Bowen Alpern et al. *The Jalapeño Virtual Machine.* IBM Systems Journal 39(1), 2000.
- Carl Friedrich Bolz, Antonio Cuni, Maciej Fijałkowski, Armin Rigo. *Tracing the Meta-Level:
  PyPy's Tracing JIT Compiler.* ICOOOLPS 2009.
- Christian Wimmer et al. *Maxine: An Approachable Virtual Machine For, and In, Java.* ACM TACO
  9(4), 2013.
- OCaml's committed bytecode bootstrap compiler (`boot/`); Zig's "Goodbye to the C++
  Implementation of Zig" (2022); Rust's `cfg(bootstrap)` and staged builds; the Bootstrappable
  Builds project.

## Interpreters and virtual machines

- Robert Nystrom. *Crafting Interpreters.* Genever Benning, 2021.
- Yunhe Shi, Kevin Casey, M. Anton Ertl, David Gregg. *Virtual Machine Showdown: Stack Versus
  Registers.* ACM TACO 4(4), 2008. Motivates V2.
- Roberto Ierusalimschy, Luiz Henrique de Figueiredo, Waldemar Celes. *The Implementation of Lua
  5.0.* Journal of Universal Computer Science 11(7), 2005.
- M. Anton Ertl, David Gregg. *The Structure and Performance of Efficient Interpreters.* Journal
  of Instruction-Level Parallelism 5, 2003.
- Urs Hölzle, Craig Chambers, David Ungar. *Optimizing Dynamically-Typed Object-Oriented Languages
  with Polymorphic Inline Caches.* ECOOP 1991.
- Stefan Brunthaler. *Inline Caching Meets Quickening.* ECOOP 2010.
- Mark Shannon. *PEP 659: Specializing Adaptive Interpreter.* Python, 2021.
- Haoran Xu, Fredrik Kjolstad. *Copy-and-Patch Compilation.* OOPSLA 2021. The baseline JIT's
  technique (F17, F51).
- Thomas Würthinger et al. *One VM to Rule Them All.* Onward! 2013.
- Tim Lindholm, Frank Yellin, Gilad Bracha, Alex Buckley, Daniel Smith. *The Java Virtual Machine
  Specification, Java SE 21 Edition.* Class initialization, stack maps.

## Memory management and safety

- Richard Jones, Antony Hosking, Eliot Moss. *The Garbage Collection Handbook.* 2nd edition, CRC
  Press, 2023.
- Stephen M. Blackburn, Kathryn S. McKinley. *Immix: A Mark-Region Garbage Collector with Space
  Efficiency, Fast Collection, and Mutator Performance.* PLDI 2008.
- Stephen M. Blackburn, Perry Cheng, Kathryn S. McKinley. *Oil and Water? High Performance
  Garbage Collection in Java with MMTk.* ICSE 2004.
- Dimitri Racordon et al. *Implementation Strategies for Mutable Value Semantics.* Journal of
  Object Technology 21(2), 2022. An alternative to borrow checking considered for V7.
- Jeremy Manson, William Pugh, Sarita V. Adve. *The Java Memory Model.* POPL 2005. Basis of
  F39.

## Bytecode verification and mechanized semantics

- Raymie Stata, Martín Abadi. *A Type System for Java Bytecode Subroutines.* POPL 1998.
- Xavier Leroy. *Java Bytecode Verification: Algorithms and Formalizations.* Journal of Automated
  Reasoning 30, 2003.
- Eva Rose. *Lightweight Bytecode Verification.* Journal of Automated Reasoning 31, 2003. The
  stack-map approach adopted in [vm §9](vm.md#9-verifier).
- Gerwin Klein, Tobias Nipkow. *A Machine-Checked Model for a Java-Like Language, Virtual Machine,
  and Compiler.* TOPLAS 28(4), 2006. Jinja, the template for F-4.
- Leonardo de Moura, Sebastian Ullrich. *The Lean 4 Theorem Prover and Programming Language.*
  CADE-28, 2021.

## Parsing

- Vaughan R. Pratt. *Top Down Operator Precedence.* POPL 1973.
- *The Swift Programming Language*, "Advanced Operators": operator and precedence-group
  declarations, the model for custom operators after v1 (F49).
