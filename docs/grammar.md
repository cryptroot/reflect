# Grammar

Lexical structure and syntax of Reflect source. Semantics live in
[type-system.md](type-system.md); conventions in [architecture.md](architecture.md) §0.

Proposed method contracts and `transaction` blocks are described separately in
[Intent Contracts and Transactions](proposals/intent-contracts-and-transactions.md); they are not yet part
of the grammar below.

Proposed `component` and `system` declarations are described separately in
[First-class Entity-Component Systems](proposals/entity-component-systems.md); those keywords are not yet
reserved by the baseline grammar below.

## 1. Source files

- **LEX-01 [Proposed]** Source files are UTF-8 (a BOM is ignored) with extension `.rfl`. Each
  module root holds `module-info.rfl`; generator output uses `.g.rfl`; `workspace.rfl` describes a
  build (architecture.md §7). Line ends are LF, CRLF or CR.

## 2. Lexical structure

### 2.1 Identifiers

- **LEX-02 [Proposed]** Identifiers follow Unicode UAX #31 (`ID_Start`, `ID_Continue`, plus `_`)
  and are compared after NFC normalization. A lone `_` is a discard, not an identifier. `$` never
  starts or continues an identifier: it appears only in `$"` (interpolated strings) and `${`
  (template splices), and compiler-generated names use it so they cannot clash with source names
  (ex §11.3).

### 2.2 Keywords

- **LEX-03 [Decided]** Three classes:
  - Reserved: `abstract boolean break byte case catch char checked class const continue default
    do double else enum event explicit extends extern false final finally float for if
    implements import in instanceof int interface long namespace native new null operator out
    override private protected public ref return short sizeof stackalloc static struct super
    switch this throw throws true try ubyte uint ulong unsafe ushort using virtual void while`.
  - Reserved for later: `async await fixed goto infix postfix precedence prefix synchronized
    volatile yield` (`fixed` pins objects once the collector moves them; `yield` belongs to
    iterators, Q12, Q20).
  - Contextual, keywords only in their position: `add expr field get member mutating nameof
    partial permits quote record remove scoped sealed set stmt unsealed value var when where`;
    in `module-info.rfl` also `exports main module opens provides requires to transitive unsafe
    uses version with`; in `workspace.rfl` the directives of architecture.md §7.

### 2.3 Comments

- **LEX-04 [Decided, Q41]** `//` line comments and `/* */` block comments, which do not nest. A
  documentation comment is a run of `///` lines directly before a declaration, written in
  Markdown with the block tags `@param name`, `@return`, `@throws Type` and `@see` (Java's JEP
  467). `/** */` is an ordinary comment. There is no Unicode-escape preprocessing: `\u` means
  something only inside char and string literals.

  ```reflect
  /// Returns the larger of `a` and `b`.
  /// @param a the first value
  /// @return `a` when the two compare equal
  public static T max<T extends Comparable<T>>(T a, T b) { ... }
  ```

### 2.4 Literals

- **LEX-05 [Proposed]**

  ```text
  IntegerLiteral = ( Decimal | "0x" Hex | "0b" Binary ) IntegerSuffix? ;
  Decimal        = Digit { "_"* Digit } ;          (likewise Hex and Binary)
  IntegerSuffix  = "u" | "U" | "L" | "UL" | "uL" ;  (lowercase "l" is rejected)
  FloatLiteral   = Decimal "." Decimal Exponent? FloatSuffix?
                 | Decimal Exponent FloatSuffix? | Decimal FloatSuffix ;
  Exponent       = ( "e" | "E" ) ( "+" | "-" )? Decimal ;
  FloatSuffix    = "f" | "F" | "d" | "D" ;
  CharLiteral    = "'" ( Char | Escape ) "'" ;
  StringLiteral  = '"' { Char | Escape | "\u{" Hex "}" } '"' ;
  Escape         = "\t" | "\n" | "\r" | "\0" | "\\" | "\'" | '\"' | "\u" Hex Hex Hex Hex ;
  Interpolated   = '$"' { Char | Escape | "{{" | "}}" | Hole } '"' ;
  Hole           = "{" Expression ( ":" FormatChars )? "}" ;
  ```

  - An unsuffixed integer literal is `int` and must fit it, hex and binary included
    (`0xFFFF_FFFF` needs `u`; ex §2.7 rejects `3_000_000_000`).
  - A constant converts implicitly to any integral type that holds its value (`byte b = -1`,
    `ubyte ub = 255`).
  - `1.5f` is `float`; `2.5e-3` and `2.5d` are `double`; there are no hex floats.
  - A char literal is one UTF-16 code unit; `\u{...}` (any code point) is allowed only in strings.
  - In a hole, a top-level `:` starts the format, so a conditional needs parentheses (ex §2.8).
    Holes may contain string literals.
  - No text blocks or raw strings in v1; `"""` is reserved.

### 2.5 Operators and punctuation

- **LEX-06 [Decided]** Tokens:

  ```text
  ( ) [ ] { } ; , . :: ... @ -> ? ?. ?[ ?? ??= : ! ~ ++ -- + - * / % & | ^ << >>> ==
  != < <= > >= && || = += -= *= /= %= &= |= ^= <<= >>= >>>=
  ```

  The lexer never produces `>>`, `>>>`, `>>=` or `>>>=` as single tokens: it emits `>` tokens
  flagged "adjacent to the previous token", and the parser joins adjacent ones in expression
  context. `List<List<int>>` therefore closes two type-argument lists (ex §12.1).

## 3. Syntax

### 3.1 Notation

EBNF: `?` optional, `*` zero or more, `+` one or more, `|` alternatives, quoted text is a token,
`Ident` an identifier, `QName` a dotted name. Lists separated by commas are written `X ("," X)*`.

### 3.2 Compilation units and modules

```text
CompilationUnit = Import* ( "namespace" QName ";" TypeDecl* | ( NamespaceBlock | TypeDecl )* ) ;
NamespaceBlock  = "namespace" QName "{" Import* ( NamespaceBlock | TypeDecl )* "}" ;
Import          = "import" "static"? QName ";"
                | "using" Ident TypeParams? "=" Type ";" ;

ModuleDecl      = "module" QName "{" ModuleDirective* "}" ;
ModuleDirective = "version" String ";"
                | "requires" ( "transitive" | "static" )* QName ";"
                | "exports" QName ( "to" QName ( "," QName )* )? ";"
                | "opens" QName ( "to" QName ( "," QName )* )? ";"
                | "provides" QName "with" QName ( "," QName )* ";"
                | "uses" QName ";"
                | "unsafe" ";"
                | "main" QName ";" ;
```

A file has one file-scoped namespace or only blocks, and never top-level functions or fields
(ex §2.2). [Proposed] `exports ... to` mirrors `opens ... to`.

### 3.3 Type declarations

```text
TypeDecl      = Annotation* Modifier* ( ClassDecl | InterfaceDecl | StructDecl | RecordDecl
              | EnumDecl | AnnotationDecl ) ;
Modifier      = "public" | "protected" | "private" | "static" | "abstract" | "final"
              | "virtual" | "override" | "sealed" | "unsealed" | "partial" | "native"
              | "extern" | "unsafe" | "mutating" | "const" | "scoped" ;
ClassDecl     = "class" Ident TypeParams? ( "extends" Type )? ( "implements" Types )?
                ( "permits" Types )? Where? ClassBody ;
InterfaceDecl = "interface" Ident TypeParams? ( "extends" Types )? ( "permits" Types )? Where?
                ClassBody ;
StructDecl    = "ref"? "struct" Ident TypeParams? ( "implements" Types )? Where? ClassBody ;
RecordDecl    = "record" "struct"? Ident TypeParams? "(" Params? ")" ( "implements" Types )?
                Where? ClassBody ;
EnumDecl      = "enum" Ident ( ":" IntegralType )? "{" EnumConstant ( "," EnumConstant )* ","?
                ( ";" Member* )? "}" ;
EnumConstant  = Annotation* Ident ( "=" Expression )? Arguments? ClassBody? ;
AnnotationDecl = "@interface" Ident "{" AnnotationMember* "}" ;
AnnotationMember = Type Ident "(" ")" ( "default" ElementValue )? ";" | Member ;
ClassBody     = "{" Member* "}" ;
TypeParams    = "<" TypeParam ( "," TypeParam )* ">" ;
TypeParam     = Annotation* Ident ( "extends" Bound ( "&" Bound )* )? ;
Bound         = "class" | "struct" | Type ;
Where         = "where" Ident "extends" Bound ( "&" Bound )* ( "," Ident "extends" ... )* ;
Types         = Type ( "," Type )* ;
```

### 3.4 Members

```text
Member      = Annotation* Modifier* MemberBody | ";" ;
MemberBody  = Field | Method | Constructor | StaticInit | Property | Indexer | Event
            | Operator | Conversion | Finalizer | TypeDecl ;
Field       = Type VarDeclarator ( "," VarDeclarator )* ";" ;
VarDeclarator = Ident ( "=" ( Expression | ArrayInit ) )? ;
Method      = Type Ident TypeParams? "(" Params? ")" Throws? Where? ( Block | ";" ) ;
Constructor = Ident "(" Params? ")" Throws? ( Block | ";" )      (compact record form: Ident Block)
StaticInit  = "static" Block ;
Property    = Type Ident "{" Accessor+ "}" ( "=" Expression ";" )? ;
Indexer     = Type "this" "[" Params "]" "{" Accessor+ "}" ;
Accessor    = Annotation* Modifier* ( "get" | "set" ) Throws? ( ";" | Block ) ;
Event       = "event" Type Ident ( ";" | "{" EventAccessor+ "}" ) ;
EventAccessor = ( "add" | "remove" ) Block ;
Operator    = Type "operator" "checked"? OperatorToken "(" Params ")" ( Block | ";" ) ;
Conversion  = "explicit" "operator" "checked"? Type "(" Param ")" Block ;
Finalizer   = "~" Ident "(" ")" Block ;
Params      = Param ( "," Param )* ;
Param       = Annotation* ( "final" | "scoped" | "this" )* ( "ref" | "in" | "out" )? Type "..."?
              Ident ;
Throws      = "throws" Types ;
```

- Constructors name their class and may be `virtual` or `override` (ex §4.1); a compact record
  constructor omits the parameter list (ex §9.2).
- `this` on the first parameter of a static method makes an extension method (MEM-09); `...`
  on the last parameter makes it variadic (MEM-10, Q22).
- A compound-assignment operator (`void operator +=(T x)`) is an instance member; on a struct it
  must be `mutating` (DAT-02).
- On an interface member, `mutating` lets a struct implement it with a `mutating` method (DAT-02,
  `Iterator<T>.next()`).
- `throws` precedes `where`: `void f<T>(T x) throws IOException where T extends Comparable<T>`.

### 3.5 Types

```text
Type        = BaseType TypeSuffix* ;
BaseType    = PrimitiveType | QName TypeArgs? ( "." Ident TypeArgs? )* | TupleType ;
TypeSuffix  = "[" "]" | "?" | "*" ;
TupleType   = "(" TupleElem "," TupleElem ( "," TupleElem )* ")" ;
TupleElem   = Type Ident? ;
TypeArgs    = "<" TypeArg ( "," TypeArg )* ">" ;
TypeArg     = Type | Wildcard | "(" Type ( "&" Type )+ ")" ;
Wildcard    = "?" ( ( "extends" | "super" ) Type )? ;
PrimitiveType = "boolean" | "byte" | "ubyte" | "short" | "ushort" | "char" | "int" | "uint"
              | "long" | "ulong" | "float" | "double" ;
```

Suffixes read left to right: `String?[]` is an array of nullable strings, `String[]?` a nullable
array, `int[]?[]` an array of nullable `int[]` (NUL-07). `*` is legal only in unsafe code. An
intersection appears only in type arguments, casts and parenthesized `instanceof` (GEN-06).

### 3.6 Statements

```text
Block          = "{" BlockStatement* "}" ;
BlockStatement = LocalDecl ";" | Statement ;
LocalDecl      = ( "final" | "scoped" )* ( Type | "var" ) VarDeclarator ( "," VarDeclarator )*
               | "var" "(" Ident ( "," Ident )* ")" "=" Expression ;
Statement      = Block | ";" | Expression ";" | If | While | Do | For | ForEach | Switch
               | Ident ":" Statement | "break" Ident? ";" | "continue" Ident? ";"
               | "return" Expression? ";" | "throw" Expression ";" | Try
               | "checked" Block | "unsafe" Block ;
If             = "if" "(" Expression ")" Statement ( "else" Statement )? ;
While          = "while" "(" Expression ")" Statement ;
Do             = "do" Statement "while" "(" Expression ")" ";" ;
For            = "for" "(" ( LocalDecl | ExprList )? ";" Expression? ";" ExprList? ")" Statement ;
ForEach        = "for" "(" "final"? ( Type | "var" ) Ident ":" Expression ")" Statement ;
Switch         = "switch" "(" Expression ")" "{" SwitchArm* "}" ;
SwitchArm      = ( "case" CaseLabel ( "," CaseLabel )* | "default" ) ( "when" Expression )?
                 "->" ( Expression ";" | Block | "throw" Expression ";" ) ;
CaseLabel      = Pattern | ConstantExpression | "null" ;
Try            = "try" ( "(" Resource ( ";" Resource )* ";"? ")" )? Block Catch* ( "finally" Block )? ;
Resource       = LocalDecl | QName ;
Catch          = "catch" "(" "final"? Type ( "|" Type )* Ident ")" Block ;
```

A `try` needs a resource list, a `catch` or a `finally`. In a switch expression, a block arm ends
in `return e;` or `throw` (STM-03).

### 3.7 Expressions

Precedence from highest to lowest:

| Level | Operators | Associativity |
|---|---|---|
| primary | literals, names, `this`, `super`, `(e)`, tuples, `new`, `T.class`, `nameof(x)`, `sizeof(T)`, `default(T)`, `checked(e)`, `switch` expressions, templates | |
| postfix | `.` `?.` `()` `<T>()` `[]` `?[]` `x++` `x--` `x!` `::` | left |
| unary | `+` `-` `!` `~` `++x` `--x` casts `(T) e`, and in unsafe code `*p` `&x` | right |
| multiplicative | `*` `/` `%` | left |
| additive | `+` `-` | left |
| shift | `<<` `>>` `>>>` | left |
| relational | `<` `>` `<=` `>=` `instanceof` | left |
| equality | `==` `!=` | left |
| bitwise and | `&` | left |
| bitwise xor | `^` | left |
| bitwise or | `\|` | left |
| conditional and | `&&` | left |
| conditional or | `\|\|` | left |
| null coalescing | `??` | right |
| conditional | `?:` | right |
| assignment, lambda | `=` and every compound assignment, `??=`, `->` | right |

```text
Lambda      = ( Ident | "(" ( Ident ( "," Ident )* | Params )? ")" ) "->" ( Expression | Block ) ;
MethodRef   = ( Expression | Type ) "::" ( Ident | "new" ) ;
New         = "new" Type ( Arguments ClassBody? | ArrayDims ArrayInit? )
            | "new" Type "[" "]" ArrayInit ;
ArrayInit   = "{" ( ElementInit ( "," ElementInit )* ","? )? "}" ;
Arguments   = "(" ( Argument ( "," Argument )* )? ")" ;
Argument    = ( "ref" | "out" | "in" )? Expression | "out" ( Type | "var" ) Ident ;
Throw       = "throw" Expression ;     (only as a switch arm or the right of ?? and the arms of ?:)
```

- `new T(...)` with a type parameter calls a virtual constructor (SVD-06); `ClassBody` after `new`
  is rejected (no anonymous classes, MEM-01) but parsed for a clear error.
- Array initializers stand alone only in declarations: `int[] primes = { 2, 3, 5, 7 };`.

### 3.8 Patterns

```text
Pattern       = TypePattern | RecordPattern | "_" ;
TypePattern   = Type Ident | "var" Ident ;          ("var" only inside a record pattern)
RecordPattern = Type "(" ( Pattern ( "," Pattern )* )? ")" ;
InstanceofExpr = Expression "instanceof" ( Type | Pattern | "(" Type ( "&" Type )+ ")" Ident? ) ;
```

### 3.9 Annotations

```text
Annotation    = "@" QName ( "(" ( ElementValue | ElementPair ( "," ElementPair )* )? ")" )? ;
ElementPair   = Ident "=" ElementValue ;
ElementValue  = ConstantExpression | Annotation | "{" ( ElementValue ( "," ElementValue )* )? "}" ;
```

### 3.10 Templates

```text
Template = "quote" ( "member" | "expr" | "stmt" ) "{" TemplateBody "}" ;
Splice   = "${" Expression "}" ;
```

The body is parsed as members, an expression or statements, with splices allowed wherever an
identifier, type, expression, statement or member may stand; what a splice inserts depends on
its static type (META-05). Templates are valid only in modules that require
`reflect.meta.compile`.

## 4. Ambiguities and how they are resolved

- **LEX-07 [Proposed]** Rules, taken from C# where the syntax matches:
  1. After a well-formed type-argument list, the next token decides: one of
     `( ) ] } : ; , . ? == != | ^ && || & [ ::` means type arguments (`max<int>(3, 4)`,
     `Box<int>.count`); anything else means relational operators.
  2. `(X) e` is a cast when `X` is a primitive type, or when `X` parses as a type and the next
     token is an identifier, literal, `(`, `!`, `~` or a keyword that starts an expression.
     `(a) - b` is a subtraction; `(int) -b` is a cast.
  3. A parenthesized list followed by `->` is a lambda parameter list.
  4. `(a, b)` is a tuple, `(a)` a parenthesized expression, `(int, String) x` a tuple-typed
     declaration.
  5. An intersection after `instanceof` must be parenthesized (ex §3.8).
  6. At statement start, a declaration is tried first: `A<B> c;` and `int* p;` declare.
  7. `T?` in a type position is nullable; after `instanceof T`, a `?` followed by an expression
     and `:` is the conditional operator.
  8. `~Name(` starts a finalizer only in member position.
  9. `default` is a switch arm at arm position, `default(T)` in an expression, a method modifier
     in an interface, and an element default in an annotation type.
  10. `->` is a lambda arrow except directly after a switch label.
  11. `quote`, `version` and the other contextual keywords are keywords only where their
      production can start; elsewhere they are identifiers.
- **LEX-08 [Decided, Q12]** A switch-expression block arm returns its value with `return`; a
  `yield` statement is an error until iterators exist (STM-06).

## 5. Syntax reserved for later

| Syntax | Feature | When |
|---|---|---|
| `yield return e;`, `yield break;` | iterators (STM-06) | late, close to v1.0 at best (Q20) |
| `async`, `await` | asynchronous methods (CON-03) | after the preview |
| `synchronized (x) { }`, `volatile` | threads (CON-02) | after v1.0 |
| `fixed (T* p = &x) { }` | pinning (UNS-06) | with a moving collector |
| `infix`, `prefix`, `postfix`, `precedence`, new operator tokens | custom operators (OPR-08) | after v1.0 |
| `"""` | text blocks, raw strings | after v1 |
| `goto` | never | |

- **OPR-08 [Decided]** A new operator token must be a character sequence that is a syntax error
  today: `|>` qualifies, `<-` does not (`x<-1` is valid now).
