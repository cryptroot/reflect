# Bytecode and module files

The `.rflc` module format, its metadata, the instruction set, verification, the `.rfla`
assembly syntax and binary compatibility. The reader, writer, assembler, disassembler and
verifier form one library, `reflect.bytecode`, shared by `rflc`, `rfla`, `rfld`, emit and the VM
(ARC-11). Conventions: [architecture.md](architecture.md) §0.

## 1. Overview

- **BC-01 [Decided, Q08]** Bytecode is stack-based like CIL: compact, easy to emit and to verify.
  The VM translates each method into a register form on first execution (vm.md §6); that form is
  also the baseline JIT's input (jit-aot.md §4).
- **BC-10 [Proposed]** Everything is little-endian. Integers in variable positions use LEB128
  (unsigned) or SLEB128 (signed); fixed-width fields are noted. Format versions change freely
  before v1.0; from v1.0 a minor version only adds.

## 2. File layout

- **BC-02 [Proposed]**

  ```text
  Header (64 bytes)
    magic         4   "RFLC"
    formatMajor   2
    formatMinor   2
    flags         4   bit 0 unsafe, bit 1 delta
    sectionCount  4
    contentHash  32   SHA-256 of all sections, in directory order
    reserved     16   zero
  Section directory: sectionCount entries of { id u32, offset u32, length u32 }
  Sections, each 8-byte aligned:
    1 IDENTS    identifiers and namespace names, UTF-8, length-prefixed
    2 LITERALS  string literals, UTF-16LE code units, length-prefixed (unpaired surrogates allowed)
    3 BLOBS     signatures, constants, annotation values, length-prefixed
    4 TABLES    table directory (row count per table), then the rows of each table
    5 CODE      method bodies (§6)
    6 DEBUG     source files, line tables, local names; optional
    7 DELTA     base module hash and change list; only in delta modules (reflection.md §8)
  ```

  Heaps are referenced by byte offset; table rows by a **token** `(table << 24) | row`, rows
  counted from 1, token 0 meaning none (CIL's scheme). The writer deduplicates heap entries in the
  order of a canonical traversal, so equal inputs give equal bytes (BOOT-05).

## 3. Metadata tables

- **BC-11 [Proposed]** Tables and their main columns, sorted by the key in parentheses:

  | Table | Columns |
  |---|---|
  | `Module` | name, version (BLD-01), flags |
  | `ModuleRef` (name) | name, flags `transitive`, `static` |
  | `Export` (namespace) | namespace, kind `exports`/`opens`, target module names |
  | `Service` (service) | kind `provides`/`uses`, service type, provider types |
  | `TypeDef` (namespace, name) | name, namespace, kind (class, interface, struct, ref struct, record, record struct, enum, annotation), modifiers, base type, enum backing type, enclosing type, first field, first method, first generic parameter |
  | `TypeRef` (scope, namespace, name) | resolution scope (module reference or enclosing type reference), namespace, name |
  | `TypeSpec` (signature) | signature blob: instantiations, arrays, nullability, wildcards, intersections, pointers, tuples |
  | `Field` | flags (static, final, const, visibility, backing field), name, type signature |
  | `Method` | flags (static, virtual, abstract, final, constructor, native, extern, unsafe, mutating, const, precompile, synthesized), name, method signature, code offset, first parameter, first generic parameter |
  | `Param` | flags (`ref`, `in`, `out`, `scoped`, extension `this`, variadic), name |
  | `GenericParam` (owner, index) | owner, index, name, kind (`class`, `struct`, none) |
  | `GenericConstraint` (param) | parameter, bound type |
  | `InterfaceImpl` (class) | class, interface type |
  | `Override` (class) | class, implementing method, overridden method (instance, static or constructor slot) |
  | `MemberRef` (parent, name) | parent type, name, signature |
  | `MethodSpec` (method) | method, instantiation blob |
  | `Property`, `Event` (owner, name) | owner, name, type, flags |
  | `Accessor` (owner) | property, event or indexer; kind `get`/`set`/`add`/`remove`; method |
  | `Annotation` (target) | target token, annotation type, value blob, retention |
  | `Constant` (field) | field, value blob (primitives, strings, class references, read-only arrays) |
  | `Provenance` (member) | generated member, generator type (`generatedBy`, ex §6.7) |
  | `TupleNames` (owner) | signature owner, element names blob |

- **BC-12 [Proposed]** Overrides are explicit rows: the compiler records which slot each
  `override` fills, so the VM builds vtables and static dispatch tables without matching names at
  link time. A row whose overridden method no longer exists is a link error (`TypeLoadError`).
- **BC-13 [Proposed]** Annotations with retention `SOURCE` are not written; `CLASS` ones are
  written but invisible to run-time reflection; `RUNTIME` ones are visible (META-01).

## 4. Signatures

- **BC-03 [Proposed]** Type signatures are byte codes followed by operands:

  | Code | Meaning | Operands |
  |---|---|---|
  | `VOID BOOLEAN I8 U8 I16 U16 CHAR I32 U32 I64 U64 F32 F64` | primitives | |
  | `CLASS`, `VALUETYPE` | named type | token |
  | `GENERICINST` | instantiation | base type, count, arguments |
  | `VAR`, `MVAR` | class or method type parameter (`!0`, `!!0`) | index |
  | `NULLABLE` | nullable reference type `T?` | element |
  | `ARRAY` | one-dimensional array | element |
  | `PTR` | pointer | element |
  | `BYREF` | `ref`, `in` or `out` reference | kind, element |
  | `WILDCARD` | `?`, `? extends T`, `? super T` | kind, bound |
  | `INTERSECTION` | canonical intersection | count, components (class first, interfaces by name) |

  A nullable value type is an instantiation of the nullable wrapper; a tuple is an instantiation
  of `TupleN` with names in `TupleNames`. Method signatures hold flags (static, generic,
  variadic), the generic parameter count, the parameter count, the return type and the parameter
  types. `scoped` is a parameter flag and `ref struct` a type flag.

## 5. Instruction set

- **BC-04 [Proposed]** One-byte opcodes, with `0xFE` prefixing a second byte. Arithmetic is typed
  like the JVM's, so the interpreter never inspects stack types; `byte`, `short` and `char` values
  widen to `i32` on the stack and narrow with `conv` on store. Operands: `<tok>` is a ULEB128
  token, `<n>` a ULEB128 index, `<imm>` an SLEB128 or fixed-width constant, `<off>` an SLEB128
  branch offset from the next instruction. Suffix sets: `t` is one of `i32 i64 f32 f64`, `u` adds
  `u32 u64`, `k` is a storage kind `i8 u8 i16 u16 i32 i64 f32 f64 ref`.

  | Family (opcode range) | Instructions and stack effect |
  |---|---|
  | constants, stack (`00`-`0F`) | `nop`; `ldnull` → null; `ldc.i4 <imm>`, `ldc.i8`, `ldc.r4`, `ldc.r8` → value; `ldstr <n>` → `String`; `ldtype <tok>` → `Class<T>`; `ldconst <tok>` → constant of a `const` field; `dup`; `pop` |
  | locals (`10`-`1F`) | `ldarg <n>`, `starg <n>`, `ldarga <n>`, `ldloc <n>`, `stloc <n>`, `ldloca <n>` |
  | arithmetic (`20`-`4F`) | `add.t sub.t mul.t` (integers wrap); `div` and `rem` over `t` and `u` (integer division by zero throws); `neg.t`; `and or xor not` over `i32 i64`; `shl`, `shr` (arithmetic), `shr.un` (logical) over `i32 i64`, count masked; checked `add.ovf sub.ovf mul.ovf` over `i32 u32 i64 u64` and `neg.ovf` over `i32 i64` |
  | comparison (`50`-`5F`) | `ceq clt cle cgt cge` over `t` and `u` → `i32` 0 or 1 (floats per IEEE); `ceq.ref` identity |
  | conversion (`60`-`7F`) | `conv.<from>.<to>` among `i8 u8 i16 u16 i32 u32 i64 u64 f32 f64` (wrapping, float to integer saturating); `conv.ovf.<from>.<to>` throws instead |
  | control (`80`-`8F`) | `br <off>`; `brtrue`, `brfalse`, `brnull`, `brnonnull` `<off>`; fused `beq bne blt ble bgt bge` over `t` and `u` `<off>`; `switch <count> <off>...`; `leave <off>`; `endfinally`; `ret` |
  | objects (`90`-`9F`) | `newobj <tok>` (args → object); `newobj.svirt <tok>` (class, args → object, virtual constructor of the class); `ldfld <tok>`; `stfld <tok>`; `ldflda <tok>`; `ldsfld`, `stsfld`, `ldsflda <tok>`; `getclass` (object → `Class<?>`); `instanceof <tok>` (false for null); `castclass <tok>`; `checkalive` (object → object, DAT-08) |
  | value types (`A0`-`A7`) | `box <tok>`; `unbox <tok>`; `ldobj`, `stobj`, `cpobj`, `initobj <tok>`; `sizeof <tok>` → `i32` |
  | arrays (`A8`-`AF`) | `newarr <tok>` (length → array); `ldlen`; `ldelem.k` or `ldelem <tok>` for structs; `stelem.k`, `stelem <tok>`; `ldelema <tok>` (exact element type check) |
  | calls (`B0`-`BF`) | `call <tok>`; `call.recv <tok>`; `callvirt <tok>`; `call.svirt <tok>`; `call.constrained <tok> <tok>`; `ldrecv` → `Class<?>` |
  | exceptions (`C0`-`C7`) | `throw`; `rethrow` |
  | references (`C8`-`CF`) | `ldind.k`, `stind.k`, `ldind <tok>`, `stind <tok>` through `ref` values and pointers |
  | unsafe only (`D0`-`DF`) | `localloc` (size → pointer); `ptradd <tok>`, `ptrdiff <tok>` (scaled by the element size); `conv.ptr.i64`, `conv.i64.ptr`; `syscall <n> <argc>` (UNS-04) |
  | reserved | `FF` `breakpoint` for debuggers |

- **BC-14 [Proposed]** Checks performed by instructions: every dereference checks null
  (`NullPointerException`); `stfld`, `stsfld`, `stelem`, `stobj` and `stind` of references run
  the write barrier (memory.md §5); reference `stelem` checks element type and nullability
  (`ArrayStoreException`); `castclass` throws `ClassCastException`, or `NullPointerException`
  for null to a non-null type (NUL-05); `checkalive` throws `ObjectDestroyedException`.
- **BC-15 [Proposed]** A class handle on the stack is a `Class<T>` reference. Every run-time type
  owns its `Class<T>` object from creation, so `ldtype`, `getclass` and `ldrecv` never allocate.
- **BC-05 [Proposed]** Receivers (SVD-09): `call C::m` on a static passes `C` as `R`;
  `call.recv C::m` pops a class handle (a subtype of `C`) and passes it; `call.svirt C::m` pops one
  and dispatches through it; `ldrecv` pushes the current frame's `R`. Calls to `reflect.vm.Magic`
  methods are intrinsics of the AOT compiler (BOOT-08), not instructions. ex §11.1, revised:

  ```text
  .method public static reflect.core.String describe()
  {
      ldstr       "Kind: "
      ldrecv
      call.svirt  demo.kinds.Kind::get_value()
      call        reflect.core.String::concat(reflect.core.String, reflect.core.String)
      ret
  }

      call        demo.kinds.Kind::describe()  // Kind.describe(): R = Kind, implicit
      ldloc       k
      getclass
      call.recv   demo.kinds.Kind::describe()  // k.describe(): R = run-time class of k
  ```

- **BC-16 [Proposed]** `call.constrained T m` calls a member of a type parameter's bound on a
  reference to a `T` value without boxing (the CLR's `constrained.` prefix as an instruction):
  value types call their implementation directly, reference types dereference and dispatch.

## 6. Method bodies

- **BC-17 [Proposed]** A body in the CODE section holds: maximum stack depth, the local variable
  signature (a blob), the instruction bytes, exception regions, stack maps, and an index into the
  DEBUG line table.
- **BC-18 [Proposed]** An exception region is `(tryStart, tryEnd, handlerStart, handlerEnd, kind,
  type)` with kind `catch` or `finally`; multi-catch writes one region per type; the type token
  may be `VAR` or `MVAR`, resolved through the frame's type context (ex §11.2). Regions nest
  properly and are ordered innermost first.
- **BC-19 [Proposed]** Stack maps record the verification type of every local and stack slot at
  each branch target and handler start (the JVM's `StackMapTable`). Verification types: `i32`,
  `i64`, `f32`, `f64`, `null`, reference to `T`, uninitialized object of class `C` (before its
  constructor runs), `ref T` and `ref readonly T` (DAT-02), pointer, struct `S`, `Class<T>`.

## 7. Verification

- **BC-07 [Proposed]** The verifier (v0.7; until then dynamic code needs `unsafe;`) type-checks
  each method in one linear pass against its stack maps (Leroy; Rose's lightweight
  verification). It checks:
  - stack and local types at every instruction, with exact generic types and type contexts;
  - that objects are initialized before use and constructors call a constructor of their class or
    its base;
  - references: no `ref` stored in the heap, captured or returned beyond its frame; no write
    through `ref readonly`; no `mutating` call on a read-only reference;
  - ref structs never boxed, stored in heap objects or used as type arguments;
  - access to private and package-private members, `final`, `sealed` and `permits` at link time;
  - unsafe-only instructions only in modules flagged `unsafe`;
  - properly nested exception regions and `leave`/`endfinally` use.

  It does not check nullability, which dereferences check anyway, or checked exceptions, a source
  rule as on the JVM. Code that `rflc` writes always verifies; the VM verifies every module that is
  not part of a trusted release (the stdlib and the toolchain).

## 8. Assembly syntax

- **BC-08 [Proposed]** `.rfla` maps one-to-one to the binary, so `rfld` then `rfla` reproduces a
  module byte for byte (ex §12.2). Directives: `.module name version`, `.requires`, `.exports`,
  `.opens`, `.class`, `.field`, `.method`, `.property`, `.event`, `.override`, `.annotation`,
  `.locals`, `.try`, `.catch`, `.finally`, `.stackmap`; labels end in `:`; type names use the
  canonical spelling (GEN-12), with `!0` and `!!0` for type parameters.

  ```text
  .module demo 0.1.0
  .requires reflect.core

  .class public abstract demo.kinds.Kind {
      .property static virtual reflect.core.String value { .get get_value }
      .field private static reflect.core.String value$field
      .method public static virtual reflect.core.String get_value() {
          ldsfld   demo.kinds.Kind::value$field
          ret
      }
  }
  ```

## 9. Binary compatibility

- **BC-06 [Proposed]** Instance calls into another module always use `callvirt`, which the VM
  turns into a direct call when the target is not virtual; otherwise a library that later makes a
  method `virtual` would leave old callers bypassing overrides. Calls within a module use `call`
  for non-virtual targets.
- **BC-09 [Proposed]** Compatible changes: adding types, members, interfaces with default
  methods; making a class non-final (BC-05); making a method virtual (BC-06); adding an
  annotation. Breaking changes: removing or renaming members, changing signatures or nullability,
  adding a `static abstract` or an abstract member, sealing a class, removing an override that a
  subclass's `Override` row names, changing a struct's layout that unsafe code depends on.

