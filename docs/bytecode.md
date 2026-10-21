# Bytecode and module files

The `.rflc` module format, its metadata, the instruction set, verification, the `.rfla`
assembly syntax, binary compatibility and the format's limits. The reader, writer, assembler,
disassembler and verifier form one library, `reflect.bytecode`, shared by `rflc`, `rfla`, `rfld`,
emit and the VM (ARC-11). Conventions: [architecture.md](architecture.md) §0.

## 1. Overview

- **BC-01 [Decided, Q08]** Bytecode is stack-based like CIL: compact, easy to emit and to verify.
  The VM translates each method into a register form on first execution (vm.md §6); that form is
  also the baseline JIT's input (jit-aot.md §4).
- **BC-10 [Proposed]** Everything is little-endian. Table columns have fixed widths (BC-11);
  integers in blobs and code use LEB128 (unsigned) or SLEB128 (signed); other fixed-width fields
  are noted. Format versions change freely before v1.0; from v1.0 a minor version only adds.
  Which versions a reader accepts: BC-20.

## 2. File layout

- **BC-02 [Proposed]** A module file is a header, a section directory and the sections. This
  document describes format 0.1: formatMajor 0 and formatMinor 1.

  | Offset | Size | Header field | Content |
  |---|---|---|---|
  | `0` | `4` | `magic` | `RFLC` |
  | `4` | `2` | `formatMajor` | u16 |
  | `6` | `2` | `formatMinor` | u16 |
  | `8` | `4` | `flags` | header flags, u32; other bits zero (BC-20) |
  | `12` | `16` | `reserved` | zero |
  | `28` | `4` | `sectionCount` | u32, the number of directory entries |
  | `32` | `32` | `contentHash` | the module's identity (BC-22) |

  | Header flag | Name | Meaning |
  |---|---|---|
  | `01` | `UNSAFE` | the module declares `unsafe;` (UNS-02) |
  | `02` | `DELTA` | the module is a hot-reload delta (REFL-07); until v0.5 a reader rejects it, as it rejects the `DELTA` section |

  The section directory follows the header: sectionCount entries of 12 bytes, each an id, an
  offset from the start of the file and a length, all u32, sorted by id. Each section starts at
  an offset that is a multiple of 8 bytes: the first one where the directory ends, rounded up,
  and each later one where the section before it ends, rounded up. Zero bytes fill the gaps, and
  the file ends where its last section ends. Bit 31 of an ID marks an ancillary section:

  | ID | Section | Content |
  |---|---|---|
  | `00000001` | `IDENTS` | identifiers and namespace names, UTF-8 |
  | `00000002` | `LITERALS` | string literals, UTF-16LE code units (unpaired surrogates allowed) |
  | `00000003` | `BLOBS` | signatures (BC-03), constant values (§3.1) and annotation values |
  | `00000004` | `TABLES` | table directory, then the rows of each table (§3.1) |
  | `00000005` | `CODE` | method bodies (§6) |
  | `00000006` | `DELTA` | base module hash and change list, only in delta modules (reflection.md §8); its layout comes with v0.5, and until then a reader rejects it |
  | `80000001` | `DEBUG` | ancillary, optional: contentHash, the source files (relative path and SHA-256), then per method token its line table and local names (§6) |

  IDENTS, LITERALS and BLOBS are heaps. A heap is a run of entries, from its empty entry at offset
  0, one zero byte, to its end. An entry is a ULEB128 length, then that many units: UTF-8 bytes in
  IDENTS, UTF-16LE code units of two bytes in LITERALS, bytes in BLOBS. Heaps are referenced by the byte offset of an
  entry's length from the start of the heap (BC-22); table rows by a **token**
  `(table << 24) | row`, rows counted from 1, token 0 meaning none (CIL's scheme). The writer
  deduplicates heap entries in the order of a canonical traversal, so equal inputs give equal
  bytes (BOOT-05). Ancillary sections sort last, so stripping them removes their directory
  entries and the end of the file.

- **BC-20 [Decided, Q57]** Unknown content. An ancillary section is one a reader may skip: the
  module means the same without it. Every other section is critical. A reader:
  1. rejects a format version it does not accept: before v1.0 (formatMajor 0), every formatMinor
     other than its own; from v1.0, a newer formatMinor;
  2. rejects an unknown critical section, an unknown table, and a flag bit it does not know, in
     the header or in a flags column;
  3. skips an unknown ancillary section and keeps its bytes, so tools write it back unchanged and
     the round trip of BC-08 holds;
  4. treats a known ancillary section that describes critical content as absent when the
     contentHash at its start differs from the module's: it is a stale copy.

  From v1.0 the writer stamps the lowest formatMinor its content needs; an ancillary section needs
  no newer minor. Critical content holds no source positions: line tables, local names and source
  files live in DEBUG, keyed by method token. Stripping DEBUG or moving a line therefore changes no
  critical byte.

- **BC-22 [Decided, Q58]** Identity. contentHash is the SHA-256 of header bytes 4 to 27
  (formatMajor, formatMinor, flags and reserved), followed by each critical section as its id
  (u32), its length (u32) and its bytes, in directory order. Every offset inside a section is
  relative to the start of the section it points into, so stripping an ancillary section, which
  shrinks the directory, changes no hashed byte; nor does moving a line (BC-20). Deltas name their
  base by contentHash (REFL-07), incremental builds key on it (CMP-04), and a signature section
  can sign it. Two builds that differ only in lines share one contentHash; the SHA-256 of each
  source file in DEBUG tells a debugger which source a DEBUG matches. A signature over contentHash
  does not cover DEBUG.

- **BC-24 [Proposed]** Canonical form. Each valid module has exactly one encoding, so the round
  trip of BC-08 is exact by construction. A module that breaks one of these rules is invalid, and
  a reader rejects it:
  1. LEB128 values are minimal and fit their field: no value has a redundant last byte, `<i8>`
     fits 64 bits, and every other LEB128 value fits 32 bits.
  2. Padding, `reserved` and every flag that the format does not define are zero.
  3. A section is present only when it has content, and the directory lists the sections by ID
     with no ID twice (BC-02). IDENTS, LITERALS, BLOBS and TABLES are always present, and CODE
     when a method has a body.
  4. TABLES lists the present tables only, and each row has the size that its table and the
     formatMinor give. `Module` has exactly one row.
  5. A table with a key is sorted by it, and no two of its rows have equal keys; the other tables
     keep the runs of §3.1, and their first-row columns follow from those runs.
  6. Heap entries are unique, and every entry except the empty one at offset 0 is referenced from
     a critical section. The entries come in the order of their first reference, met by visiting
     the tables in ID order, their rows in order and their columns in order, a `Constant` value
     before the LITERALS entry it holds, then the bodies in CODE in order, each with its `locals`
     before the operands of its instructions.
  7. Bodies follow BC-17, `Method.code` is 0 for a method without a body, and regions and frames
     follow the orders of BC-18 and BC-19.
  8. Signatures follow BC-03: a primitive is its code, and `GENERIC` is set exactly when a method
     has type parameters.
  9. DEBUG lists methods by token, line entries by offset and locals by index, and each file once,
     in the order of first use.
  10. contentHash holds the value of BC-22, and no value exceeds a limit of BC-21.

## 3. Metadata tables

- **BC-11 [Proposed]** Tables, their IDs and their main columns, sorted by the key in
  parentheses; a table without a key keeps its rows in runs by owner (§3.1):

  | ID | Table | Columns |
  |---|---|---|
  | `01` | `Module` | name, version (BLD-01), flags, `main` class (VM-12) |
  | `02` | `ModuleRef` (name) | name, flags `transitive`, `static` |
  | `03` | `Export` (namespace, kind, target) | namespace, kind `exports`/`opens`, target module name; one row per target |
  | `04` | `Service` (service) | kind `provides`/`uses`, service type, provider types |
  | `05` | `TypeDef` (namespace, name, enclosing type) | name, namespace, kind (class, interface, struct, ref struct, record, record struct, enum, annotation), modifiers, base type, enum backing type, enclosing type, first field, first method, first generic parameter |
  | `06` | `TypeRef` (scope, namespace, name) | resolution scope (module reference or enclosing type reference), namespace, name |
  | `07` | `TypeSpec` (signature) | signature blob: instantiations, arrays, nullability, wildcards, intersections, pointers, tuples |
  | `08` | `Field` | flags (visibility, static, final, const, backing field), name, type signature |
  | `09` | `Method` | flags (visibility, static, virtual, abstract, final, constructor, native, extern, unsafe, mutating, const, precompile, synthesized), name, method signature, code offset, first parameter, first generic parameter |
  | `0A` | `Param` | flags (`scoped`, extension `this`), name; `ref`, `in` and `out` are in the parameter's type, and variadic in the method signature (BC-03) |
  | `0B` | `GenericParam` (owner, index) | owner, index, name, kind (`class`, `struct`, none) |
  | `0C` | `GenericConstraint` (param, bound) | parameter, bound type |
  | `0D` | `InterfaceImpl` (class, interface) | class, interface type |
  | `0E` | `Override` (class, overridden method) | class, implementing method, overridden method (instance, static or constructor slot) |
  | `0F` | `MemberRef` (parent, name, signature) | parent type, name, signature |
  | `10` | `MethodSpec` (method, instantiation) | method, instantiation blob |
  | `11` | `Property` (owner, name) | owner, name, type, flags |
  | `12` | `Event` (owner, name) | owner, name, type, flags |
  | `13` | `Accessor` (owner) | property, event or indexer; kind `get`/`set`/`add`/`remove`; method |
  | `14` | `Annotation` (target) | target token, annotation type, value blob, retention |
  | `15` | `Constant` (field) | field, value blob (primitives, strings, class references, read-only arrays) |
  | `16` | `Provenance` (member) | generated member, generator type (`generatedBy`, ex §6.7) |
  | `17` | `TupleNames` (owner) | signature owner, element names blob |

  Every column is a little-endian u32, so any row is read in place without decoding the rows
  before it, and a row has four bytes per column. The row size follows from the table and the
  formatMinor; TABLES states it, and the reader checks it. A later minor may append columns to a
  table. A reader treats the columns appended since a module's minor as zero, and the zero value
  of an appended column means what modules meant before it existed (a variance column's 0 is
  invariant).

- **BC-12 [Proposed]** Overrides are explicit rows: the compiler records which slot each
  `override` fills, so the VM builds vtables and static dispatch tables without matching names at
  link time. A row whose overridden method no longer exists is a link error (`TypeLoadError`).
  Return types are covariant (MEM-02). An override that returns a reference subtype of the base's
  return type fills the base slot itself, so explicit rows need no bridge for it. When the return
  type needs boxing or nullable wrapping, the row names the synthesized bridge that converts the
  result, and that bridge fills the base slot; the override keeps a slot of its own, which a
  subclass may override with the same type. Loading checks that an override's return type
  converts to the return type of the method it replaces in the slot, the nearest one in its base
  classes, so a module compiled against an older base cannot widen it; otherwise `TypeLoadError`.
- **BC-13 [Proposed]** Annotations with retention `SOURCE` are not written; `CLASS` ones are
  written but invisible to run-time reflection; `RUNTIME` ones are visible (META-01).

### 3.1 Table layouts

TABLES starts with its directory: the number of present tables, then for each present table,
in ID order, its ID, its row count and its row size, all u32. The rows of each table follow in
directory order without padding. A table is present when it has at least one row, and rows are
numbered from 1.

The layouts below cover the tables that v0.0.x uses. The other tables get theirs with the stage
that first needs them; until then a reader rejects them as unknown (BC-20). Each column is a
u32, in the order given:

| Table | Column | Content |
|---|---|---|
| `Module` | `name` | IDENTS offset of the module name |
| `Module` | `major` | version major (BLD-01) |
| `Module` | `minor` | version minor |
| `Module` | `patch` | version patch |
| `Module` | `flags` | no flag is defined yet |
| `Module` | `main` | `TypeDef` row of the descriptor's `main` class (VM-12), or 0 |
| `ModuleRef` | `name` | IDENTS offset of a required module |
| `ModuleRef` | `flags` | `ModuleRef` flags |
| `Export` | `namespace` | IDENTS offset |
| `Export` | `kind` | `Export` kind |
| `Export` | `target` | IDENTS offset of the module it is qualified to, or 0 for every module |
| `TypeDef` | `name` | IDENTS offset of the simple name |
| `TypeDef` | `namespace` | IDENTS offset, 0 for a nested type |
| `TypeDef` | `kind` | `TypeDef` kind |
| `TypeDef` | `flags` | visibility and `TypeDef` flags |
| `TypeDef` | `base` | token of a `TypeDef`, `TypeRef` or `TypeSpec` row, or 0 |
| `TypeDef` | `backing` | the signature code of an enum's integral backing type (DAT-05): `I8`, `U8`, `I16`, `U16`, `I32`, `U32`, `I64` or `U64`, not `CHAR`; otherwise 0 |
| `TypeDef` | `enclosing` | `TypeDef` row of the enclosing type, or 0 |
| `TypeDef` | `fields` | first `Field` row |
| `TypeDef` | `methods` | first `Method` row |
| `TypeDef` | `typeParams` | first `GenericParam` row |
| `TypeRef` | `scope` | token of a `ModuleRef` row, or of the `TypeRef` row of the enclosing type |
| `TypeRef` | `namespace` | IDENTS offset, 0 for a nested type |
| `TypeRef` | `name` | IDENTS offset |
| `TypeSpec` | `signature` | BLOBS offset of a type |
| `Field` | `flags` | visibility and `Field` flags |
| `Field` | `name` | IDENTS offset |
| `Field` | `signature` | BLOBS offset of a `FIELD` signature |
| `Method` | `flags` | visibility and `Method` flags |
| `Method` | `name` | IDENTS offset |
| `Method` | `signature` | BLOBS offset of a `METHOD` signature |
| `Method` | `code` | CODE offset of the body, or 0 for a method without one |
| `Method` | `params` | first `Param` row |
| `Method` | `typeParams` | first `GenericParam` row |
| `Param` | `flags` | `Param` flags |
| `Param` | `name` | IDENTS offset |
| `GenericParam` | `owner` | token of the `TypeDef` or `Method` row that declares it |
| `GenericParam` | `index` | position among the owner's type parameters, from 0 |
| `GenericParam` | `name` | IDENTS offset |
| `GenericParam` | `kind` | `GenericParam` kind |
| `GenericConstraint` | `param` | `GenericParam` row |
| `GenericConstraint` | `bound` | token of a `TypeDef`, `TypeRef` or `TypeSpec` row |
| `InterfaceImpl` | `class` | `TypeDef` row |
| `InterfaceImpl` | `interface` | token of a `TypeDef`, `TypeRef` or `TypeSpec` row |
| `Override` | `class` | `TypeDef` row |
| `Override` | `implementation` | `Method` row of the method, or of its bridge, that fills the slot |
| `Override` | `overridden` | token of the `Method` or `MemberRef` row whose slot it fills |
| `MemberRef` | `parent` | token of a `TypeRef` or `TypeSpec` row |
| `MemberRef` | `name` | IDENTS offset |
| `MemberRef` | `signature` | BLOBS offset of a `FIELD` or `METHOD` signature |
| `MethodSpec` | `method` | token of a `Method` or `MemberRef` row |
| `MethodSpec` | `instantiation` | BLOBS offset of an `INSTANTIATION` signature |
| `Constant` | `field` | `Field` row |
| `Constant` | `value` | BLOBS offset of the value |

- IDENTS offset 0 holds the empty identifier. A type in the global namespace and a nested type
  have namespace 0; a nested type takes its namespace from its outermost enclosing type.
- `fields`, `methods`, `params` and `typeParams` give the first row of the owner's run of rows.
  The run ends where the next owner's run starts, or at the end of the table. An empty run starts
  where the next one does, so a column whose table has no rows holds 1. `GenericParam` rows owned
  by types come before those owned by methods, so the run of the last type ends where the run of
  the first method starts. `Field`, `Method` and `Param` keep each run in declaration
  order, and the runs in the order of their owners.
- Keys compare column by column: identifiers by their UTF-8 bytes, signatures by their bytes, and
  the other columns as unsigned numbers.
- A constructor is named `new` and a static initializer `static`. Both have the `CONSTRUCTOR`
  flag, and a static initializer also has `STATIC`. Both words are reserved in source, so no
  method name can clash with them.
- A method has a body unless it is `ABSTRACT`, `NATIVE` or `EXTERN`.
- A `Constant` value blob holds the value in the encoding of the field's type: `BOOLEAN` as one
  byte, 0 or 1; signed integers as SLEB128; unsigned integers and `CHAR` as ULEB128; `F32` and
  `F64` as their IEEE 754 bit patterns; `reflect.core.String` as a ULEB128 offset into LITERALS;
  an enum as its backing type. Class references and read-only arrays come with the stage that
  first needs them.

Flags and kinds of the columns follow. A flag that the table does not list is zero (BC-20).
`TypeDef`, `Field` and `Method` flags hold at most one visibility flag; with none, the member is
package-private (MOD-08).

| Column | Value | Name | Meaning |
|---|---|---|---|
| visibility | `01` | `PUBLIC` | `public` |
| visibility | `02` | `PROTECTED` | `protected` |
| visibility | `04` | `PRIVATE` | `private` |
| `TypeDef` kind | `00` | `CLASS` | class |
| `TypeDef` kind | `01` | `INTERFACE` | interface |
| `TypeDef` kind | `02` | `STRUCT` | struct |
| `TypeDef` kind | `03` | `REF_STRUCT` | `ref struct` |
| `TypeDef` kind | `04` | `RECORD` | class record |
| `TypeDef` kind | `05` | `RECORD_STRUCT` | `record struct` |
| `TypeDef` kind | `06` | `ENUM` | enum |
| `TypeDef` kind | `07` | `ANNOTATION` | annotation type |
| `TypeDef` flags | `08` | `ABSTRACT` | `abstract` |
| `TypeDef` flags | `10` | `FINAL` | `final` |
| `TypeDef` flags | `20` | `SEALED` | `sealed` |
| `TypeDef` flags | `40` | `UNSEALED` | `unsealed` |
| `Field` flags | `08` | `STATIC` | `static` |
| `Field` flags | `10` | `FINAL` | `final` (BC-23) |
| `Field` flags | `20` | `CONST` | `const`, which is also `STATIC` |
| `Field` flags | `40` | `BACKING` | backing field of a property (MEM-13) |
| `Method` flags | `0008` | `STATIC` | `static` |
| `Method` flags | `0010` | `VIRTUAL` | `virtual` |
| `Method` flags | `0020` | `ABSTRACT` | `abstract` |
| `Method` flags | `0040` | `FINAL` | `final` |
| `Method` flags | `0080` | `CONSTRUCTOR` | constructor or static initializer |
| `Method` flags | `0100` | `NATIVE` | `native` (LIB-03) |
| `Method` flags | `0200` | `EXTERN` | `extern` |
| `Method` flags | `0400` | `UNSAFE` | `unsafe` |
| `Method` flags | `0800` | `MUTATING` | `mutating` (DAT-02) |
| `Method` flags | `1000` | `CONST` | `const` |
| `Method` flags | `2000` | `PRECOMPILE` | `@Precompile` (JIT-03) |
| `Method` flags | `4000` | `SYNTHESIZED` | made by the compiler |
| `Param` flags | `01` | `SCOPED` | `scoped` |
| `Param` flags | `02` | `THIS` | receiver of an extension method (MEM-09) |
| `ModuleRef` flags | `01` | `TRANSITIVE` | `requires transitive` |
| `ModuleRef` flags | `02` | `STATIC` | `requires static` |
| `Export` kind | `00` | `EXPORTS` | `exports` |
| `Export` kind | `01` | `OPENS` | `opens` |
| `GenericParam` kind | `00` | `NONE` | no kind bound |
| `GenericParam` kind | `01` | `CLASS` | `T extends class` |
| `GenericParam` kind | `02` | `STRUCT` | `T extends struct` |

## 4. Signatures

- **BC-03 [Proposed]** A type signature is a code byte followed by its operands. A signature in
  BLOBS starts with its kind, `FIELD`, `METHOD`, `LOCALS` or `INSTANTIATION`; a `TypeSpec` blob
  is a bare type. `<tok>` is a ULEB128 token of a `TypeDef` or `TypeRef` row; `<n>` and
  `<count>` are ULEB128.

  | Code | Name | Meaning | Operands |
  |---|---|---|---|
  | `01` | `VOID` | `void`, only as a return type | |
  | `02` | `BOOLEAN` | `boolean` | |
  | `03` | `I8` | `byte` | |
  | `04` | `U8` | `ubyte` | |
  | `05` | `I16` | `short` | |
  | `06` | `U16` | `ushort` | |
  | `07` | `CHAR` | `char` | |
  | `08` | `I32` | `int` | |
  | `09` | `U32` | `uint` | |
  | `0A` | `I64` | `long` | |
  | `0B` | `U64` | `ulong` | |
  | `0C` | `F32` | `float` | |
  | `0D` | `F64` | `double` | |
  | `10` | `CLASS` | reference type by name | `<tok>` |
  | `11` | `VALUETYPE` | value type by name | `<tok>` |
  | `12` | `GENERICINST` | instantiation | a `CLASS` or `VALUETYPE` type, `<count>`, the type arguments |
  | `13` | `VAR` | type parameter of the type (`!0`) | `<n>` |
  | `14` | `MVAR` | type parameter of the method (`!!0`) | `<n>` |
  | `15` | `NULLABLE` | nullable reference type `T?` | type |
  | `16` | `ARRAY` | one-dimensional array | element type |
  | `17` | `PTR` | pointer | element type |
  | `18` | `BYREF` | `ref`, `in` or `out` reference | BYREF kind, type |
  | `19` | `WILDCARD` | `?`, `? extends T`, `? super T` | WILDCARD kind, then the bound unless the kind is `ANY` |
  | `1A` | `INTERSECTION` | canonical intersection | `<count>`, the components: the class first, then the interfaces by qualified name (GEN-06) |
  | `20` | `FIELD` | field signature | type |
  | `21` | `METHOD` | method signature | METHOD flags, `<count>` type parameters if `GENERIC`, `<count>` parameters, return type, parameter types |
  | `22` | `LOCALS` | local variable signature | `<count>`, the types |
  | `23` | `INSTANTIATION` | type arguments of a `MethodSpec` | `<count>`, the types |

  | BYREF kind | Name | Meaning |
  |---|---|---|
  | `00` | `REF` | `ref` |
  | `01` | `IN` | `in`, a `ref readonly` |
  | `02` | `OUT` | `out` |

  | WILDCARD kind | Name | Meaning |
  |---|---|---|
  | `00` | `ANY` | `?` |
  | `01` | `EXTENDS` | `? extends T` |
  | `02` | `SUPER` | `? super T` |

  | METHOD flag | Name | Meaning |
  |---|---|---|
  | `01` | `STATIC` | no `this` argument |
  | `02` | `GENERIC` | the method has type parameters, and their count follows |
  | `04` | `VARIADIC` | the last parameter is variadic (MEM-10) |

  A kind byte and the flags byte are one byte each. A primitive is always its code, never
  `VALUETYPE` of its struct: `reflect.core.Int32` is `I32`. A nullable value type is an
  instantiation of the nullable wrapper; a tuple is an instantiation of `TupleN` with names in
  `TupleNames`. `scoped` is a parameter flag and `ref struct` a type flag. A signature also
  follows these rules:

  - `VOID` appears only as a return type, and `WILDCARD` only as a type argument.
  - `CLASS` names a reference type: a class, an interface, a record or an annotation type.
    `VALUETYPE` names a value type: a struct, a ref struct, a record struct or an enum (TYP-01).
  - `GENERICINST` has at least one type argument, and for a type that the module defines, one per
    type parameter.
  - `INTERSECTION` has at least two components, each `CLASS` or a `GENERICINST` of `CLASS`. The
    class comes first. The interfaces follow by qualified name, compared as UTF-8 bytes, and no
    name appears twice, since two instantiations of one interface have no common type (GEN-06,
    ex §3.7). A reader knows the kind only of a type that the module defines, so it checks the
    first component against the second only when the first is an interface of the module.
  - `NULLABLE` wraps `CLASS`, a `GENERICINST` of `CLASS`, `ARRAY`, `VAR` or `MVAR`. Value types,
    pointers, references, wildcards and intersections have no nullable form (TYP-01).
  - `VAR` names a type parameter of the type that declares the member, and `MVAR` one of the
    method. In a `MemberRef`, `MVAR` names one of its own `GENERIC` count, and `VAR` is not
    checked, since the parent's type parameters are declared in another module (Q74).
  - The last parameter of a `VARIADIC` signature is an `ARRAY` (MEM-10).
  - The signature of a `Method` row has `STATIC` exactly when the row has it, `GENERIC` exactly
    when the method has `GenericParam` rows (BC-24), and one parameter per `Param` row.
  - The types that a signature lists have depth 1, and a type inside another is one deeper: an
    element, a bound, a type argument, a component, or the type of `NULLABLE` or `BYREF`. The
    named type of a `GENERICINST` keeps the depth of the instantiation. `int[][]` has depth 3,
    and BC-21 limits the depth to 255.

## 5. Instruction set

- **BC-04 [Proposed; encoding Decided, Q67, Q68]** Opcodes are one byte; `0xFE` prefixes a
  second byte. Arithmetic is typed like the JVM's, so the interpreter never inspects stack types;
  `boolean`, `byte`, `short` and `char` values widen to `i32` on the stack and narrow with `conv`
  on store, and unsigned values share the slots of their signed widths. An instruction exists
  only where it changes the result: equality and stores ignore signedness (`ceq.i32` serves
  `u32`, `stelem.i8` serves `u8`), and conversions exist only where bits change. Other pairs
  compose: a `double` narrows to `byte` through `conv.f64.i32` then `conv.i32.i8`, which gives
  Java's result, 44 for `(byte) 300.0` (NUM-03). Operands: `<tok>` is a ULEB128 token, `<n>` a
  ULEB128 index, `<str>` a ULEB128 offset into LITERALS, `<i4>` and `<i8>` SLEB128 constants,
  `<r4>` and `<r8>` IEEE 754 bit patterns of 4 and 8 bytes, `<off>` an SLEB128 branch offset from
  the next instruction; `switch` takes a ULEB128 count, then that many `<off>`. `ldarg`, `starg`
  and `ldarga` name an argument of the method, `this` first unless it is static; `ldloc`, `stloc`
  and `ldloca` name a local of its `LOCALS` signature. A `<tok>` names a field, a method or a type,
  as §8.1 gives for each instruction: a field is a `Field` row or a `MemberRef` with a `FIELD`
  signature, a method a `Method` row, a `MemberRef` with a `METHOD` signature or a `MethodSpec`,
  and a type a `TypeDef`, `TypeRef` or `TypeSpec` row. Suffix sets: `t` is one of
  `i32 i64 f32 f64`, `u` adds `u32 u64`, `k` is a storage kind,
  `i8 u8 i16 u16 i32 i64 f32 f64 ref` for loads and `i8 i16 i32 i64 f32 f64 ref` for stores.
  The opcode table (§5.1) lists every instruction; an opcode it does not list is invalid, and the
  free places in each range let the family grow. Debuggers write `breakpoint` into loaded code; a
  module never contains it.

  | Family | Opcodes | Instructions and stack effect |
  |---|---|---|
  | constants, stack | `00`-`0F` | `nop`; `ldnull` → null; `ldc.i4 <i4>`, `ldc.i8 <i8>`, `ldc.r4 <r4>`, `ldc.r8 <r8>` → value; `ldstr <str>` → `String`; `ldtype <tok>` → `Class<T>`; `ldconst <tok>` → constant of a `const` field; `dup`; `pop` |
  | locals | `10`-`17` | `ldarg <n>`, `starg <n>`, `ldarga <n>`, `ldloc <n>`, `stloc <n>`, `ldloca <n>` |
  | arithmetic | `18`-`47` | `add.t sub.t mul.t` (integers wrap); `div` and `rem` over `t` and `u` (integer division by zero throws); `neg.t`; `and or xor not` over `i32 i64`; `shl`, `shr` (arithmetic), `shr.un` (logical) over `i32 i64`, count masked |
  | comparison | `48`-`67` | `ceq.t`; `clt cle cgt cge` over `t` and `u` → `i32` 0 or 1 (floats per IEEE); `ceq.ref` identity, or class and contents for two boxes without identity (DAT-09) |
  | conversion | `68`-`87` | `conv.<from>.<to>` from `i32 u32 i64 u64 f32 f64` where bits change (wrapping, float to integer saturating) |
  | control | `88`-`B7` | `br <off>`; `brtrue`, `brfalse`, `brnull`, `brnonnull` `<off>`; fused `beq.t bne.t`, and `blt ble bgt bge` over `t` and `u`, `<off>`; `switch <count> <off>...`; `leave <off>`; `endfinally`; `ret` |
  | objects | `B8`-`C7` | `newobj <tok>` (args → object); `newobj.svirt <tok>` (class, args → object, virtual constructor of the class); `ldfld <tok>`; `stfld <tok>`; `ldflda <tok>`; `ldsfld`, `stsfld`, `ldsflda <tok>`; `getclass` (object → `Class<?>`); `instanceof <tok>` (false for null); `castclass <tok>`; `checkalive` (object → object, DAT-08) |
  | value types | `C8`-`CF` | `box <tok>`; `unbox <tok>` (pushes a copy, never a reference into the box, DAT-09); `ldobj`, `stobj`, `cpobj`, `initobj <tok>`; `sizeof <tok>` → `i32` |
  | arrays | `D0`-`E7` | `newarr <tok>` (length → array); `ldlen`; `ldelem.k`, or `ldelem <tok>` for structs and type parameters; `stelem.k`, `stelem <tok>`; `ldelema <tok>` |
  | calls | `E8`-`EF` | `call <tok>`; `call.recv <tok>`; `callvirt <tok>`; `call.svirt <tok>`; `call.constrained <tok> <tok>`; `ldrecv` → `Class<?>` |
  | exceptions | `F0`-`F3` | `throw`; `rethrow` |
  | checked arithmetic | `FE 00`-`FE 1F` | `add.ovf sub.ovf mul.ovf` over `i32 i64 u32 u64`; `div.ovf` and `neg.ovf` over `i32 i64`; overflow throws `ArithmeticException` (NUM-03) |
  | checked conversion | `FE 20`-`FE 3F` | `conv.ovf.<from>.<to>` where a value can fail to fit; throws `ArithmeticException` instead of wrapping or saturating |
  | references | `FE 40`-`FE 5F` | `ldind.k`, `stind.k`, `ldind <tok>`, `stind <tok>` through `ref` values and pointers |
  | unsafe only | `FE 60`-`FE 6F` | `localloc` (size → pointer); `ptradd <tok>`, `ptrdiff <tok>` (scaled by the element size); `conv.ptr.i64`, `conv.i64.ptr`; `syscall <n> <n>`, the call number and the argument count (UNS-04) |
  | reserved | `FF` | `breakpoint` for debuggers |

- **BC-14 [Proposed]** Checks performed by instructions: every dereference checks null
  (`NullPointerException`); `stfld`, `stsfld`, `stelem`, `stobj` and `stind` of references run
  the write barrier (memory.md §5); a reference `stelem` checks no element type, since arrays are
  invariant (NUL-07); `castclass` throws `ClassCastException`, or `NullPointerException` for null
  to a non-null type (NUL-05); `checkalive` throws `ObjectDestroyedException`.
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
      call        static reflect.core.String reflect.core.String::concat(reflect.core.String, reflect.core.String)
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

### 5.1 Opcode table

Every instruction of BC-04 with its opcode and operands. A two-byte opcode shows both bytes. `R0`
marks the instructions that R0 code needs (BOOT-02), which VM0 implements.

| Opcode | Instruction | Operands | R0 |
|---|---|---|---|
| `00` | `nop` | | R0 |
| `01` | `ldnull` | | R0 |
| `02` | `ldc.i4` | `<i4>` | R0 |
| `03` | `ldc.i8` | `<i8>` | R0 |
| `04` | `ldc.r4` | `<r4>` | R0 |
| `05` | `ldc.r8` | `<r8>` | R0 |
| `06` | `ldstr` | `<str>` | R0 |
| `07` | `ldtype` | `<tok>` | R0 |
| `08` | `ldconst` | `<tok>` | R0 |
| `09` | `dup` | | R0 |
| `0A` | `pop` | | R0 |
| `10` | `ldarg` | `<n>` | R0 |
| `11` | `starg` | `<n>` | R0 |
| `12` | `ldarga` | `<n>` | R0 |
| `13` | `ldloc` | `<n>` | R0 |
| `14` | `stloc` | `<n>` | R0 |
| `15` | `ldloca` | `<n>` | R0 |
| `18` | `add.i32` | | R0 |
| `19` | `add.i64` | | R0 |
| `1A` | `add.f32` | | R0 |
| `1B` | `add.f64` | | R0 |
| `1C` | `sub.i32` | | R0 |
| `1D` | `sub.i64` | | R0 |
| `1E` | `sub.f32` | | R0 |
| `1F` | `sub.f64` | | R0 |
| `20` | `mul.i32` | | R0 |
| `21` | `mul.i64` | | R0 |
| `22` | `mul.f32` | | R0 |
| `23` | `mul.f64` | | R0 |
| `24` | `div.i32` | | R0 |
| `25` | `div.i64` | | R0 |
| `26` | `div.f32` | | R0 |
| `27` | `div.f64` | | R0 |
| `28` | `div.u32` | | |
| `29` | `div.u64` | | |
| `2A` | `rem.i32` | | R0 |
| `2B` | `rem.i64` | | R0 |
| `2C` | `rem.f32` | | R0 |
| `2D` | `rem.f64` | | R0 |
| `2E` | `rem.u32` | | |
| `2F` | `rem.u64` | | |
| `30` | `neg.i32` | | R0 |
| `31` | `neg.i64` | | R0 |
| `32` | `neg.f32` | | R0 |
| `33` | `neg.f64` | | R0 |
| `34` | `and.i32` | | R0 |
| `35` | `and.i64` | | R0 |
| `36` | `or.i32` | | R0 |
| `37` | `or.i64` | | R0 |
| `38` | `xor.i32` | | R0 |
| `39` | `xor.i64` | | R0 |
| `3A` | `not.i32` | | R0 |
| `3B` | `not.i64` | | R0 |
| `3C` | `shl.i32` | | R0 |
| `3D` | `shl.i64` | | R0 |
| `3E` | `shr.i32` | | R0 |
| `3F` | `shr.i64` | | R0 |
| `40` | `shr.un.i32` | | R0 |
| `41` | `shr.un.i64` | | R0 |
| `48` | `ceq.i32` | | R0 |
| `49` | `ceq.i64` | | R0 |
| `4A` | `ceq.f32` | | R0 |
| `4B` | `ceq.f64` | | R0 |
| `4C` | `clt.i32` | | R0 |
| `4D` | `clt.i64` | | R0 |
| `4E` | `clt.f32` | | R0 |
| `4F` | `clt.f64` | | R0 |
| `50` | `clt.u32` | | |
| `51` | `clt.u64` | | |
| `52` | `cle.i32` | | R0 |
| `53` | `cle.i64` | | R0 |
| `54` | `cle.f32` | | R0 |
| `55` | `cle.f64` | | R0 |
| `56` | `cle.u32` | | |
| `57` | `cle.u64` | | |
| `58` | `cgt.i32` | | R0 |
| `59` | `cgt.i64` | | R0 |
| `5A` | `cgt.f32` | | R0 |
| `5B` | `cgt.f64` | | R0 |
| `5C` | `cgt.u32` | | |
| `5D` | `cgt.u64` | | |
| `5E` | `cge.i32` | | R0 |
| `5F` | `cge.i64` | | R0 |
| `60` | `cge.f32` | | R0 |
| `61` | `cge.f64` | | R0 |
| `62` | `cge.u32` | | |
| `63` | `cge.u64` | | |
| `64` | `ceq.ref` | | R0 |
| `68` | `conv.i32.i8` | | R0 |
| `69` | `conv.i32.u8` | | |
| `6A` | `conv.i32.i16` | | R0 |
| `6B` | `conv.i32.u16` | | R0 |
| `6C` | `conv.i32.i64` | | R0 |
| `6D` | `conv.i32.f32` | | R0 |
| `6E` | `conv.i32.f64` | | R0 |
| `6F` | `conv.u32.i64` | | |
| `70` | `conv.u32.f32` | | |
| `71` | `conv.u32.f64` | | |
| `72` | `conv.i64.i32` | | R0 |
| `73` | `conv.i64.f32` | | R0 |
| `74` | `conv.i64.f64` | | R0 |
| `75` | `conv.u64.f32` | | |
| `76` | `conv.u64.f64` | | |
| `77` | `conv.f32.i32` | | R0 |
| `78` | `conv.f32.u32` | | |
| `79` | `conv.f32.i64` | | R0 |
| `7A` | `conv.f32.u64` | | |
| `7B` | `conv.f32.f64` | | R0 |
| `7C` | `conv.f64.i32` | | R0 |
| `7D` | `conv.f64.u32` | | |
| `7E` | `conv.f64.i64` | | R0 |
| `7F` | `conv.f64.u64` | | |
| `80` | `conv.f64.f32` | | R0 |
| `88` | `br` | `<off>` | R0 |
| `89` | `brtrue` | `<off>` | R0 |
| `8A` | `brfalse` | `<off>` | R0 |
| `8B` | `brnull` | `<off>` | R0 |
| `8C` | `brnonnull` | `<off>` | R0 |
| `8D` | `beq.i32` | `<off>` | R0 |
| `8E` | `beq.i64` | `<off>` | R0 |
| `8F` | `beq.f32` | `<off>` | R0 |
| `90` | `beq.f64` | `<off>` | R0 |
| `91` | `bne.i32` | `<off>` | R0 |
| `92` | `bne.i64` | `<off>` | R0 |
| `93` | `bne.f32` | `<off>` | R0 |
| `94` | `bne.f64` | `<off>` | R0 |
| `95` | `blt.i32` | `<off>` | R0 |
| `96` | `blt.i64` | `<off>` | R0 |
| `97` | `blt.f32` | `<off>` | R0 |
| `98` | `blt.f64` | `<off>` | R0 |
| `99` | `blt.u32` | `<off>` | |
| `9A` | `blt.u64` | `<off>` | |
| `9B` | `ble.i32` | `<off>` | R0 |
| `9C` | `ble.i64` | `<off>` | R0 |
| `9D` | `ble.f32` | `<off>` | R0 |
| `9E` | `ble.f64` | `<off>` | R0 |
| `9F` | `ble.u32` | `<off>` | |
| `A0` | `ble.u64` | `<off>` | |
| `A1` | `bgt.i32` | `<off>` | R0 |
| `A2` | `bgt.i64` | `<off>` | R0 |
| `A3` | `bgt.f32` | `<off>` | R0 |
| `A4` | `bgt.f64` | `<off>` | R0 |
| `A5` | `bgt.u32` | `<off>` | |
| `A6` | `bgt.u64` | `<off>` | |
| `A7` | `bge.i32` | `<off>` | R0 |
| `A8` | `bge.i64` | `<off>` | R0 |
| `A9` | `bge.f32` | `<off>` | R0 |
| `AA` | `bge.f64` | `<off>` | R0 |
| `AB` | `bge.u32` | `<off>` | |
| `AC` | `bge.u64` | `<off>` | |
| `AD` | `switch` | `<count> <off>...` | R0 |
| `AE` | `leave` | `<off>` | R0 |
| `AF` | `endfinally` | | R0 |
| `B0` | `ret` | | R0 |
| `B8` | `newobj` | `<tok>` | R0 |
| `B9` | `newobj.svirt` | `<tok>` | |
| `BA` | `ldfld` | `<tok>` | R0 |
| `BB` | `stfld` | `<tok>` | R0 |
| `BC` | `ldflda` | `<tok>` | |
| `BD` | `ldsfld` | `<tok>` | R0 |
| `BE` | `stsfld` | `<tok>` | R0 |
| `BF` | `ldsflda` | `<tok>` | |
| `C0` | `getclass` | | R0 |
| `C1` | `instanceof` | `<tok>` | R0 |
| `C2` | `castclass` | `<tok>` | R0 |
| `C3` | `checkalive` | | |
| `C8` | `box` | `<tok>` | R0 |
| `C9` | `unbox` | `<tok>` | R0 |
| `CA` | `ldobj` | `<tok>` | |
| `CB` | `stobj` | `<tok>` | |
| `CC` | `cpobj` | `<tok>` | |
| `CD` | `initobj` | `<tok>` | |
| `CE` | `sizeof` | `<tok>` | |
| `D0` | `newarr` | `<tok>` | R0 |
| `D1` | `ldlen` | | R0 |
| `D2` | `ldelem.i8` | | R0 |
| `D3` | `ldelem.u8` | | R0 |
| `D4` | `ldelem.i16` | | R0 |
| `D5` | `ldelem.u16` | | R0 |
| `D6` | `ldelem.i32` | | R0 |
| `D7` | `ldelem.i64` | | R0 |
| `D8` | `ldelem.f32` | | R0 |
| `D9` | `ldelem.f64` | | R0 |
| `DA` | `ldelem.ref` | | R0 |
| `DB` | `ldelem` | `<tok>` | R0 |
| `DC` | `stelem.i8` | | R0 |
| `DD` | `stelem.i16` | | R0 |
| `DE` | `stelem.i32` | | R0 |
| `DF` | `stelem.i64` | | R0 |
| `E0` | `stelem.f32` | | R0 |
| `E1` | `stelem.f64` | | R0 |
| `E2` | `stelem.ref` | | R0 |
| `E3` | `stelem` | `<tok>` | R0 |
| `E4` | `ldelema` | `<tok>` | |
| `E8` | `call` | `<tok>` | R0 |
| `E9` | `call.recv` | `<tok>` | |
| `EA` | `callvirt` | `<tok>` | R0 |
| `EB` | `call.svirt` | `<tok>` | |
| `EC` | `call.constrained` | `<tok> <tok>` | R0 |
| `ED` | `ldrecv` | | |
| `F0` | `throw` | | R0 |
| `F1` | `rethrow` | | R0 |
| `FF` | `breakpoint` | | |
| `FE 00` | `add.ovf.i32` | | |
| `FE 01` | `add.ovf.i64` | | |
| `FE 02` | `add.ovf.u32` | | |
| `FE 03` | `add.ovf.u64` | | |
| `FE 04` | `sub.ovf.i32` | | |
| `FE 05` | `sub.ovf.i64` | | |
| `FE 06` | `sub.ovf.u32` | | |
| `FE 07` | `sub.ovf.u64` | | |
| `FE 08` | `mul.ovf.i32` | | |
| `FE 09` | `mul.ovf.i64` | | |
| `FE 0A` | `mul.ovf.u32` | | |
| `FE 0B` | `mul.ovf.u64` | | |
| `FE 0C` | `div.ovf.i32` | | |
| `FE 0D` | `div.ovf.i64` | | |
| `FE 0E` | `neg.ovf.i32` | | |
| `FE 0F` | `neg.ovf.i64` | | |
| `FE 20` | `conv.ovf.i32.i8` | | |
| `FE 21` | `conv.ovf.i32.u8` | | |
| `FE 22` | `conv.ovf.i32.i16` | | |
| `FE 23` | `conv.ovf.i32.u16` | | |
| `FE 24` | `conv.ovf.i32.u32` | | |
| `FE 25` | `conv.ovf.u32.i32` | | |
| `FE 26` | `conv.ovf.i64.i32` | | |
| `FE 27` | `conv.ovf.i64.u32` | | |
| `FE 28` | `conv.ovf.i64.u64` | | |
| `FE 29` | `conv.ovf.u64.i32` | | |
| `FE 2A` | `conv.ovf.u64.u32` | | |
| `FE 2B` | `conv.ovf.u64.i64` | | |
| `FE 2C` | `conv.ovf.f32.i32` | | |
| `FE 2D` | `conv.ovf.f32.u32` | | |
| `FE 2E` | `conv.ovf.f32.i64` | | |
| `FE 2F` | `conv.ovf.f32.u64` | | |
| `FE 30` | `conv.ovf.f64.i32` | | |
| `FE 31` | `conv.ovf.f64.u32` | | |
| `FE 32` | `conv.ovf.f64.i64` | | |
| `FE 33` | `conv.ovf.f64.u64` | | |
| `FE 40` | `ldind.i8` | | |
| `FE 41` | `ldind.u8` | | |
| `FE 42` | `ldind.i16` | | |
| `FE 43` | `ldind.u16` | | |
| `FE 44` | `ldind.i32` | | |
| `FE 45` | `ldind.i64` | | |
| `FE 46` | `ldind.f32` | | |
| `FE 47` | `ldind.f64` | | |
| `FE 48` | `ldind.ref` | | |
| `FE 49` | `ldind` | `<tok>` | |
| `FE 4A` | `stind.i8` | | |
| `FE 4B` | `stind.i16` | | |
| `FE 4C` | `stind.i32` | | |
| `FE 4D` | `stind.i64` | | |
| `FE 4E` | `stind.f32` | | |
| `FE 4F` | `stind.f64` | | |
| `FE 50` | `stind.ref` | | |
| `FE 51` | `stind` | `<tok>` | |
| `FE 60` | `localloc` | | |
| `FE 61` | `ptradd` | `<tok>` | |
| `FE 62` | `ptrdiff` | `<tok>` | |
| `FE 63` | `conv.ptr.i64` | | |
| `FE 64` | `conv.i64.ptr` | | |
| `FE 65` | `syscall` | `<n> <n>` | |

## 6. Method bodies

- **BC-17 [Proposed]** A body in the CODE section holds: maximum stack depth, the local variable
  signature (a blob), the instruction bytes, exception regions and stack maps. Its line table and
  local names are in DEBUG, keyed by the method's token, so a body never refers to DEBUG (BC-20).
  The bodies follow one another in the order of their `Method` rows from CODE offset 0, without
  padding, and `Method.code` gives the offset of each. The flags tell which methods have a body
  (§3.1), so the first body has `code` 0, as a method without one has:

  | Body field | Encoding | Content |
  |---|---|---|
  | `maxStack` | ULEB128 | maximum stack depth |
  | `locals` | ULEB128 | BLOBS offset of a `LOCALS` signature, which lists at least one type, or 0 for a method without locals |
  | `codeSize` | ULEB128 | number of instruction bytes |
  | `code` | `codeSize` bytes | the instructions (§5.1); every branch offset lands on the first byte of an instruction |
  | `regionCount` | ULEB128 | number of exception regions |
  | `regions` | | the regions (BC-18) |
  | `frameCount` | ULEB128 | number of stack map frames |
  | `frames` | | the frames (BC-19) |

- **BC-18 [Proposed]** An exception region is `(kind, tryStart, tryEnd, handlerStart, handlerEnd,
  type)` with kind `catch` or `finally`; multi-catch writes one region per type; the type token
  may name a `TypeSpec` of `VAR` or `MVAR`, resolved through the frame's type context (ex §11.2).
  Regions nest properly and are ordered innermost first: by `tryEnd` ascending, then `tryStart`
  descending, then `handlerStart` ascending. An exception goes to the first region whose `try`
  holds the offset and whose type matches.

  | Region field | Encoding | Content |
  |---|---|---|
  | `kind` | u8 | region kind |
  | `tryStart` | ULEB128 | code offset of the first instruction of the `try` |
  | `tryEnd` | ULEB128 | code offset after its last instruction |
  | `handlerStart` | ULEB128 | code offset of the first instruction of the handler |
  | `handlerEnd` | ULEB128 | code offset after its last instruction |
  | `type` | ULEB128 | for `CATCH`, token of a `TypeDef`, `TypeRef` or `TypeSpec` row; for `FINALLY`, 0 |

  | Region kind | Name | Meaning |
  |---|---|---|
  | `00` | `CATCH` | `catch` of `type` and its subtypes |
  | `01` | `FINALLY` | `finally`, which runs on every exit (EXC-07) |

- **BC-19 [Proposed]** Stack maps record the verification type of every argument, local and
  stack slot at each branch target and handler start (the JVM's `StackMapTable`). Verification
  types: `i32`, `i64`, `f32`, `f64`, `null`, reference to `T`, uninitialized object of class `C`
  (before its constructor runs), `ref T` and `ref readonly T` (DAT-02), pointer, struct `S`,
  `Class<T>`. A body has at most one frame per offset, in offset order:

  | Frame field | Encoding | Content |
  |---|---|---|
  | `offset` | ULEB128 | code offset of a branch target or handler start |
  | `slotCount` | ULEB128 | number of argument and local slots |
  | `slots` | | verification types of the arguments, then of the locals |
  | `stackCount` | ULEB128 | stack depth |
  | `stack` | | verification types from the bottom of the stack |

  A verification type is a tag byte. A tag that names a type is followed by a ULEB128 token of a
  `TypeDef`, `TypeRef` or `TypeSpec` row:

  | Verification type | Name | Operand |
  |---|---|---|
  | `01` | `I32` | |
  | `02` | `I64` | |
  | `03` | `F32` | |
  | `04` | `F64` | |
  | `05` | `NULL` | |
  | `06` | `REF` | `<tok>`, the type |
  | `07` | `UNINIT` | `<tok>`, the class whose constructor has not run |
  | `08` | `BYREF` | `<tok>`, `T` of `ref T` |
  | `09` | `BYREF_READONLY` | `<tok>`, `T` of `ref readonly T` |
  | `0A` | `PTR` | |
  | `0B` | `STRUCT` | `<tok>`, the struct |
  | `0C` | `CLASS` | `<tok>`, `T` of `Class<T>` |

### 6.1 DEBUG layout

DEBUG (BC-02) holds no offset into another section, so a tool can strip it or keep it apart
(BC-22):

| DEBUG field | Encoding | Content |
|---|---|---|
| `contentHash` | 32 bytes | the module's contentHash; a DEBUG whose hash differs is stale (BC-20) |
| `fileCount` | ULEB128 | number of source files |
| `files` | | per file: its path relative to the module root, with `/` separators, as a ULEB128 length and UTF-8 bytes; then the SHA-256 of the file, 32 bytes |
| `methodCount` | ULEB128 | number of methods |
| `methods` | | per method that has a line entry or a named local, by increasing token: the token of its `Method` row (ULEB128), its lines and its locals |

- Lines: a ULEB128 count, then per entry a code offset, the index of a file in `files` and a
  line number counted from 1, each ULEB128, by increasing offset. An entry holds from its offset
  to the offset of the next entry.
- Locals: a ULEB128 count, then per local its index (ULEB128) and its name, as a ULEB128 length
  and UTF-8 bytes, by increasing index.

## 7. Verification

- **BC-07 [Proposed]** The verifier (v0.7; until then dynamic code needs `unsafe;`) type-checks
  each method in one linear pass against its stack maps (Leroy; Rose's lightweight
  verification). It checks:
  - stack and local types at every instruction, with exact generic and array types and type
    contexts;
  - that objects are initialized before use and constructors call a constructor of their class or
    its base;
  - stores to final fields and calls of constructors (BC-23);
  - references: no `ref` stored in the heap, captured or returned beyond its frame; no write
    through `ref readonly`; no `mutating` call on a read-only reference;
  - ref structs never boxed, stored in heap objects or used as type arguments;
  - access to private and package-private members, `final`, `sealed` and `permits` at link time;
  - unsafe-only instructions only in modules flagged `unsafe`;
  - properly nested exception regions and `leave`/`endfinally` use.

  It does not check nullability, which dereferences check anyway, or checked exceptions, a source
  rule as on the JVM. Code that `rflc` writes always verifies; the VM verifies every module that is
  not part of a trusted release (the stdlib and the toolchain).

- **BC-23 [Decided, Q60]** Final fields stay final:
  - `stfld` of a final instance field occurs only in a constructor of the field's declaring type,
    and its target is that constructor's `this`; `stsfld` of a static final occurs only in the
    static initializer of its declaring type; no instruction stores a `const` field;
  - a class constructor runs only on a new object: through `newobj`, `newobj.svirt`, or a call
    from a constructor of the class or of a subclass on its own `this`;
  - outside those initializers, `ldflda` and `ldsflda` of a final field give a `ref readonly`, so
    no store and no `mutating` call changes a final struct in place (DAT-02);
  - a violation is a link error, `TypeLoadError` at the use (VM-13): VM0 checks the method when
    the instruction links and the target when it runs; the verifier checks both before the method
    runs.

  Once set, a final never changes: the JIT and AOT may fold a static final after its class is
  initialized, and an instance final after its object's constructor returns (JIT-08). Reflection
  and hot reload keep the rule (REFL-03, REFL-07).

## 8. Assembly syntax

- **BC-08 [Proposed; references and regions Decided, Q69, Q71]** `.rfla` maps one-to-one to the
  binary, so `rfld` then `rfla` reproduces a module byte for byte (ex §12.2). Directives:
  `.module name version`, `.main`, `.requires`, `.exports`, `.opens`, `.source`, `.class`,
  `.field`, `.method`, `.property`, `.event`, `.override`, `.annotation`, `.locals`, `.maxstack`,
  `.try`, `.catch`, `.finally`, `.line`, `.stackmap`, `.section`; labels end in `:`; type names
  use the canonical spelling (GEN-12), with `!0` and `!!0` for type parameters. `.main` names the
  descriptor's `main` class (VM-12). A reference to another module states the signature that its
  row holds (Q69), so `rfla` reads no other module. `rfld` prints a section it cannot print as
  directives, such as an unknown ancillary section (BC-20), as `.section <id> hex "..."`, so the
  round trip covers it too. §8.1 gives the grammar; `.property`, `.event` and `.annotation` join
  it with their tables.

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

### 8.1 Grammar

The grammar of `.rfla`, in the notation of grammar.md §3.1, for the tables of §3.1. White space
separates tokens, and a `//` comment runs to the end of its line. Five character classes stay
outside the grammar: `IdStart` and `IdPart` are the characters of Unicode `ID_Start` and
`ID_Continue` (LEX-02), `Digit` is `0` to `9`, `HexDigit` is a digit or a letter from `a` to `f`
in either case, and `Char` is any character but `"`, `\` and a line end.

```text
File         = ModuleDecl TopLevel* ;
TopLevel     = Main | Requires | Exports | Opens | Source | Section | Class ;
ModuleDecl   = ".module" QName Version ;
Main         = ".main" TypeName ;
Requires     = ".requires" ( "transitive" | "static" )* QName ;
Exports      = ".exports" QName ToModules? ;
Opens        = ".opens" QName ToModules? ;
ToModules    = "to" QName ( "," QName )* ;
Source       = ".source" String Hex ;
Section      = ".section" Int Hex ;

Class        = ".class" TypeFlag* TypeKind? TypeName TypeParams? Backing? Extends? Implements?
               "{" Member* "}" ;
TypeFlag     = "public" | "protected" | "private" | "abstract" | "final" | "sealed" | "unsealed" ;
TypeKind     = "interface" | "struct" | "ref" "struct" | "record" "struct"? | "enum"
             | "annotation" ;
Backing      = ":" Type ;
Extends      = "extends" Type ;
Implements   = "implements" Types ;
Member       = Field | Method | Class ;
Field        = ".field" FieldFlag* Type Ident ( "=" Constant )? ;
FieldFlag    = "public" | "protected" | "private" | "static" | "final" | "const" | "backing" ;
Constant     = Int | Real | String | "true" | "false" ;
Method       = ".method" MethodFlag* ReturnType Ident TypeParams? "(" Params? ")"
               "{" BodyItem* "}" ;
MethodFlag   = "public" | "protected" | "private" | "static" | "virtual" | "abstract" | "final"
             | "native" | "extern" | "unsafe" | "mutating" | "const" | "precompile"
             | "synthesized" ;
Params       = Param ( "," Param )* ;
Param        = ( "scoped" | "this" )* Type "..."? Ident? ;
TypeParams   = "<" TypeParam ( "," TypeParam )* ">" ;
TypeParam    = Ident ( "extends" Bound ( "&" Bound )* )? ;
Bound        = "class" | "struct" | Type ;

BodyItem     = Label ":" | Instruction | Locals | MaxStack | Override | Region | Line | StackMap ;
Locals       = ".locals" "(" ( Local ( "," Local )* )? ")" ;
Local        = Type Ident? ;
MaxStack     = ".maxstack" Int ;
Override     = ".override" MethodRef ;
Region       = ".try" Label Label ( ".catch" Type | ".finally" ) Label Label ;
Line         = ".line" String? Int ;
StackMap     = ".stackmap" "(" VTypes? ")" "(" VTypes? ")" ;
VTypes       = VType ( "," VType )* ;
VType        = "i32" | "i64" | "f32" | "f64" | "null" | "ptr"
             | ( "ref" | "uninit" | "byref" | "byref.readonly" | "struct" | "class" ) Type ;
Instruction  = Mnemonic Operand* ;
Operand      = Int | Real | String | Index | Label | Targets | Type | FieldRef | MethodRef ;
Index        = Int | Ident ;
Targets      = "(" ( Label ( "," Label )* )? ")" ;
FieldRef     = Type? Type "::" Ident ;
MethodRef    = ( "static"? ReturnType )? Type "::" Ident TypeArgs? "(" Types? ")" ;

Type         = ( "ref" | "in" | "out" ) Type | BaseType Suffix* ;
BaseType     = Primitive | "valuetype"? TypeName TypeArgs? | TypeVar | Intersection ;
TypeVar      = "!" Digits | "!!" Digits ;
Intersection = "(" Type ( "&" Type )+ ")" ;
Suffix       = "?" | "[" "]" | "*" ;
TypeArgs     = "<" TypeArg ( "," TypeArg )* ">" ;
TypeArg      = Type | "?" ( ( "extends" | "super" ) Type )? ;
Types        = Type ( "," Type )* ;
ReturnType   = "void" | Type ;
Primitive    = "boolean" | "byte" | "ubyte" | "short" | "ushort" | "char" | "int" | "uint"
             | "long" | "ulong" | "float" | "double" ;
TypeName     = QName ( "/" Ident )* ;
QName        = Ident ( "." Ident )* ;
Mnemonic     = Ident ( "." Ident )* ;
Label        = Ident ;
Ident        = ( IdStart | "_" | "$" ) ( IdPart | "$" )* ;

Version      = Digits "." Digits "." Digits ;
Int          = "-"? ( Digits | "0x" HexDigits ) ;
Real         = "-"? Digits ( "." Digits )? Exponent? | "inf" | "-inf" | "bits" Int ;
Exponent     = ( "e" | "E" ) ( "+" | "-" )? Digits ;
String       = '"' ( Char | Escape )* '"' ;
Escape       = '\"' | "\\" | "\n" | "\t" | "\r" | "\0" | "\u" HexDigit HexDigit HexDigit HexDigit ;
Hex          = "hex" '"' ( HexDigit HexDigit )* '"' ;
Digits       = Digit+ ;
HexDigits    = HexDigit+ ;
```

- A `Class` inside another declares a nested type and names only itself. Elsewhere a nested type
  follows its enclosing type after `/` (GEN-12).
- A name resolves to a type parameter in scope before a type, and a primitive keyword means its
  struct (Q52). `valuetype` marks a value type (Q69).
- The short form of `FieldRef` and `MethodRef`, without the leading field type, `static` or
  return type, refers only to a member of the module being assembled, and `rfld` always prints
  the full form (Q69). `TypeArgs` after a method name instantiate it (a `MethodSpec`).
- `.override` in a method makes the method fill the slot of the method it names (BC-12).
- A `Label` names the offset of the instruction that follows it. `Region` lines may appear
  anywhere in a body (Q71).
- `.locals` gives the types of the locals and, in DEBUG, their names; an `Index` names an
  argument or a local by number or by name. `.maxstack` sets the maximum stack depth.
- `.source` declares a source file and its SHA-256. `.line` with a path starts lines in that
  file; without one, it continues the file of the method's previous `.line`.
- `.section` holds an unknown ancillary section, with its ID in hexadecimal (BC-20).
- The type of a field decides how its `Constant` is read; the operand kind of an instruction
  decides how an operand is read.
- `Real` gives a decimal value rounded to the nearest float, `inf`, `-inf`, or with `bits` the
  exact IEEE 754 bit pattern, which covers NaN payloads.
- `String` holds UTF-16 code units; `\u` gives one code unit, so an unpaired surrogate can be
  written.

The operands of an instruction follow its operand kind in §5.1:

| Operands | Syntax |
|---|---|
| | nothing |
| `<i4>` | `Int` |
| `<i8>` | `Int` |
| `<r4>` | `Real` |
| `<r8>` | `Real` |
| `<str>` | `String` |
| `<tok>` | `FieldRef` for `ldfld`, `stfld`, `ldflda`, `ldsfld`, `stsfld`, `ldsflda` and `ldconst`; `MethodRef` for `newobj`, `newobj.svirt`, `call`, `call.recv`, `callvirt` and `call.svirt`; `Type` for the others |
| `<n>` | `Index` |
| `<off>` | `Label` |
| `<count> <off>...` | `Targets` |
| `<tok> <tok>` | `Type`, then `MethodRef` |
| `<n> <n>` | `Int`, then `Int` |

## 9. Binary compatibility

- **BC-06 [Proposed]** Instance calls into another module always use `callvirt`, which the VM
  turns into a direct call when the target is not virtual; otherwise a library that later makes a
  method `virtual` would leave old callers bypassing overrides. Calls within a module use `call`
  for non-virtual targets.
- **BC-09 [Proposed]** Compatible changes: adding types, members, interfaces with default
  methods; making a class non-final (BC-05); making a method virtual (BC-06); adding an
  annotation. Breaking changes: removing or renaming members, changing signatures or nullability,
  adding a `static abstract` or an abstract member, sealing a class, removing an override that a
  subclass's `Override` row names, narrowing the return type of a member that a subclass in
  another module overrides with a wider one (BC-12), changing a struct's layout that unsafe code
  depends on.

## 10. Limits

- **BC-21 [Decided, Q59]** Limits of the format. The writer and `rfla` report a diagnostic
  instead of writing a module that exceeds one, and every reader rejects such a module, so all
  tools accept the same modules. A tier may still decline to compile a large method and leave it
  to the interpreter.

  | Item | Limit |
  |---|---|
  | rows per table | 16,777,215 (2^24 − 1, the row bits of a token) |
  | size of a section, a heap or the file | 4,294,967,295 bytes (2^32 − 1, u32 offsets and lengths) |
  | code of one method | 16,777,215 bytes (2^24 − 1) |
  | locals of one method | 65,535 |
  | stack depth of one method | 65,535 |
  | parameters of one method | 65,535 |
  | exception regions of one method | 65,535 |
  | nesting depth of a signature | 255 |
  | length of an identifier | 65,535 UTF-8 bytes |

