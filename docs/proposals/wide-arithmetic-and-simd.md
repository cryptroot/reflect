# Proposal: 128-bit Arithmetic and SIMD

Extend Reflect with 128-bit scalar arithmetic and explicit, fixed-width SIMD operations.
SIMD means **single instruction, multiple data**: one operation applies to several lanes in a
vector. A 128-bit scalar is one number; a 128-bit vector holds several smaller numbers. Neither
implies the other.

This work is not implemented or assigned a release. All rules here are **[Proposed]** unless
marked **[Open]**. They do not amend the baseline [language](../type-system.md),
[module format](../bytecode.md), [backend](../jit-aot.md) or [roadmap](../roadmap.md). Conventions:
[architecture.md](../architecture.md) section 0. This is not an addition to R0 or a requirement for
the self-hosting fixpoint.

## 1. Scope and design constraints

- **WID-01 [Proposed]** Treat wide integers, wide floating point and SIMD as separate features.
  Each must have a complete interpreter implementation before compiled execution is accepted.
  Hardware acceleration changes performance, never the result or the availability of an API.

| Feature | Proposed types | Main uses | Not included |
|---|---|---|---|
| Wide integer arithmetic | `Int128`, `UInt128` | Exact counters, fixed-point intermediates, hashes and identifier processing | Arbitrary-precision integers; constant-time cryptography guarantees |
| Wide floating-point arithmetic | `Float128` | Higher-precision numerical work and reference calculations | Decimal arithmetic, symbolic calculus, automatic differentiation |
| Portable 128-bit SIMD | `Vector128<T>`, `Mask128<T>` | Explicit data-parallel arithmetic, byte processing and bulk numeric work | 128-bit lanes, scalable vectors, GPU execution, automatic vectorisation |

Here, "128-bit calculus" means numerical calculation with 128-bit representations. A symbolic
calculus library is a different proposal. `Float128` means IEEE 754 **binary128**, not two
`double` values and not a decimal format. Its cost and implementation schedule are independent
of the integer and SIMD work.

Follow ARC-02: maintained arithmetic helpers, instruction emission and software fallbacks are
Reflect code. No required C/C++ library, host `__int128`, external assembler or vendor SDK is
introduced. Development tests may use an independent oracle without adding a runtime dependency.

## 2. Scalar types and source compatibility

- **WID-02 [Proposed]** Add VM-known, reference-free primitive structs to `reflect.core`:

  | Type | Size | Representation | Range or precision |
  |---|---|---|---|
  | `Int128` | 16 bytes | Two's complement | $-2^{127}$ through $2^{127}-1$ |
  | `UInt128` | 16 bytes | Unsigned binary | $0$ through $2^{128}-1$ |
  | `Float128` | 16 bytes | IEEE 754 binary128 | 1 sign bit, 15 exponent bits, 112 stored fraction bits; 113 bits of precision for normal values |

  Normal binary128 values have unbiased exponents from -16382 through 16383. The smallest
  positive subnormal is $2^{-16494}$. Preserve subnormals; do not flush them to zero.

Use the existing type names rather than adding keywords such as `int128` or changing `long`.
Ordinary names such as `Int128` remain subject to normal name lookup. The types implement the
appropriate existing [generic-math interfaces](../stdlib.md); vectors do not become scalar numbers.
Boxing, nullable values, arrays, reflection, equality and hashing follow the corresponding
primitive rules. Hashing and formatting must include all 128 bits, not only the low word.

- **WID-03 [Proposed]** Leave existing literal tokens and suffixes unchanged. Provide invariant,
  correctly rounded `parse` and `tryParse` APIs, plus `fromBits(ulong high, ulong low)` for all
  three types. `fromBits` preserves bits; it is not a numeric conversion. Its constant use must
  follow the existing `const` rules. `toBits()` returns the same named high/low words.

  Do not make an oversized, unsuffixed integer literal silently acquire a new type. Expressions
  such as `UInt128.parse("340282366920938463463374607431768211455")` provide an initial spelling
  without a grammar change. New suffixes can be considered separately after real use.

- **WID-04 [Proposed]** Extend numeric promotion only for expressions that already contain a new
  type. Existing expressions retain NUM-02's types, diagnostics and overload selection.

  | Operands involving a new type | Common type or rule |
  |---|---|
  | `Int128` and an existing integral type | `Int128`; all existing integral ranges fit |
  | `UInt128` and an existing unsigned integral type or `char` | `UInt128` |
  | `UInt128` and a signed integral type, including `Int128` | Error; explicitly convert the signed operand to `UInt128` if that is the intended domain |
  | Either wide integer and `float` or `double` | Error; explicitly choose floating-point conversion or integer arithmetic |
  | `Float128` and an existing scalar numeric type | `Float128` |
  | `Float128` and either wide integer | Error; explicitly convert the integer to `Float128` |

  Allow implicit widening from existing signed and unsigned integers to `Int128`, from existing
  unsigned integers and `char` to `UInt128`, and from existing numeric types to `Float128`.
  Conversion of a wide integer to `Float128` is explicit: not every 128-bit integer fits its
  113-bit precision. No implicit conversion exists between `Int128` and `UInt128`.
  Conversion to a narrower integer or floating-point type is explicit. The initial extension
  grants no signed-constant exception for `UInt128`: write an explicit conversion even for `1`.
  Existing `ulong` expressions retain NUM-02's existing constant rules.

## 3. Integer arithmetic

- **WID-05 [Proposed]** Extend NUM-03 to both wide integers. Unchecked `+`, `-`, `*`, unary `-`,
  `++` and `--` retain the low 128 bits. Interpret those bits with the destination signedness.
  `checked` uses the exact mathematical result and throws `ArithmeticException` if it is outside
  the destination range. Checked narrowing checks range; unchecked narrowing keeps low bits.

  Division truncates towards zero. For signed operands, the remainder has the dividend's sign
  unless it is zero. Division and remainder by zero always throw. `Int128.MIN_VALUE / -1`
  returns `MIN_VALUE` unchecked and throws checked; `MIN_VALUE % -1` is zero in both contexts.
  Do not let a native divide fault determine these language-level results.

- **WID-06 [Proposed]** Define `&`, `|`, `^` and `~` over all 128 bits. Shift counts have type
  `int` and use `count & 127`, including negative counts. `>>` sign-extends `Int128` and
  zero-extends `UInt128`; `>>>` always zero-extends. Left shift discards high bits, also in a
  checked context. Comparisons respect signedness and return `boolean`, not a vector mask.

  Provide leading/trailing-zero count, population count and rotate operations through the
  generic-math API. Counts cover all 128 bits; both zero-count operations return 128 for zero.
  Carry/borrow helpers can be internal initially; a public multi-precision API is separate work.

## 4. Binary128 arithmetic

- **WID-07 [Proposed]** Apply NUM-04 to `Float128`: round to nearest, ties to even after each
  operation; retain signed zero, infinities and subnormals. Basic operations are `+`, `-`, `*`,
  `/`, remainder, comparisons and square root. Remainder uses truncating-quotient semantics like
  the existing floating-point remainder, not IEEE remainder's nearest-integer quotient.

  `Math.fma` gets an explicit binary128 overload with one final rounding. Ordinary multiply
  followed by add must not contract. There is no implicit extended precision, reassociation,
  floating-point trap mode or application-visible floating-point status flags. Parsing and
  formatting must round-trip finite values; the general decimal format needs up to 36 significant
  digits. Transcendental functions require a separate accuracy contract before publication.

  `==` follows IEEE: NaNs compare unequal and the two zero signs compare equal. `equals` and
  hashing follow NUM-04's canonical-NaN bit comparison. `fromBits`/`toBits` preserve NaN payloads;
  arithmetic NaN results use one specified, positive quiet-NaN encoding across all execution tiers.
  Fix that encoding in the adopted format specification, not independently in each backend.

- **WID-08 [Proposed]** Define conversions by value, not host-language casts. Integer-to-binary128
  conversion rounds once. Binary128-to-integer conversion truncates towards zero, then saturates
  to the destination range unchecked, with NaN mapping to zero; checked conversion throws for
  NaN, infinity or an out-of-range truncated result. Narrowing to `float` or `double` rounds
  once to the destination format, with overflow to signed infinity and gradual underflow.
  `checked` does not turn floating-point overflow into an integer overflow exception.

  Use a Reflect software implementation for unsupported operations. Reusing `double` arithmetic
  as an approximation is not a valid fallback. Compiler constant evaluation calls the same
  semantic implementation as the interpreter, subject to the existing evaluation limits.

## 5. Explicit SIMD types

- **SIMD-01 [Proposed]** Put VM-known `Vector128<T>` and `Mask128<T>` in `reflect.core`, under a
  `reflect.core.simd` namespace. This preserves ARC-10 without making the VM depend on a new
  library module. Each closed vector instantiation is a 16-byte, reference-free value type.
  It is not interchangeable with `Int128`, `UInt128` or a byte array.

  | Lane types `T` | Lane count |
  |---|---|
  | `byte`, `ubyte` | 16 |
  | `short`, `ushort` | 8 |
  | `int`, `uint`, `float` | 4 |
  | `long`, `ulong`, `double` | 2 |

  Reject every other element type, including `boolean`, `char`, references, user structs and the
  new 128-bit scalar types. Size alone does not make a type a supported lane. A generic method
  using vector operations must prove that `T` belongs to this finite set through a built-in
  lane constraint checked by both compiler and verifier; `T extends struct` is insufficient.
  The constraint's source spelling remains an adoption decision in section 12.

- **SIMD-02 [Proposed]** Start with named operations, not overloaded scalar operators or a new
  vector literal syntax. Examples below illustrate proposed APIs, not existing library members:

  ```reflect
  static void addFour(ReadOnlySpan<float> left, ReadOnlySpan<float> right,
                      Span<float> destination, int index) {
      Vector128<float> first = Vector128<float>.load(left, index);
      Vector128<float> second = Vector128<float>.load(right, index);
      Vector128<float> sum = Vector128<float>.add(first, second);
      sum.store(destination, index);
  }
  ```

  This example processes exactly four elements; it is not an arbitrary-length loop. A bulk
  algorithm handles its remaining elements with scalar code and states its aliasing contract.
  No `isSupported` guard is needed for correctness. Hardware capability information, if exposed,
  describes acceleration only and cannot change lane count or operation semantics.

### 5.1 Operation set

- **SIMD-03 [Proposed]** Define a finite portable operation set before adding target intrinsics:

  | Family | Required semantics |
  |---|---|
  | Construction and access | Zero, broadcast, construction from exactly the lane count, extract and replace one lane; reject an out-of-range lane index |
  | Arithmetic | Add, subtract, multiply and negate for numeric lanes; divide and square root for floating-point lanes |
  | Integer bits | And, or, xor, not; per-vector `int` shift count masked by lane width; arithmetic and logical right shifts named separately |
  | Comparisons | Equal, not-equal, less, less-or-equal, greater, greater-or-equal; signed or unsigned according to `T`; IEEE comparisons for float lanes |
  | Masks | And, or, xor, not, `anyTrue`, `allTrue`, lane extraction and lane construction |
  | Selection | `select(mask, whenTrue, whenFalse)`, choosing a complete lane |
  | Permutation | Shuffle from two vectors of the same type with compile-time lane indices; an explicit zero entry is permitted; invalid indices are rejected |
  | Bit reinterpretation | Explicit `reinterpret<U>()` among the supported lane types; preserve all 128 bits, with no numeric conversion |
  | Numeric conversion | Explicit, same-lane-count conversions, initially signed/unsigned pairs of equal width and `int`/`uint` with `float`, `long`/`ulong` with `double` |

  Integer arithmetic wraps independently in each lane. Carry never crosses a lane boundary.
  A lexical `checked` block does not change these named methods; do not expose implicit checked
  vector operators. Named checked vector operations are deferred, as are saturating arithmetic,
  integer division, gathers, scatters, horizontal reductions and cross-width numeric conversion.
  This keeps exception ordering and partial-lane failures out of the initial arithmetic subset.

  Same-width integer conversions preserve bits unchecked and numeric float/integer conversions
  follow NUM-03 lane by lane. Initial conversion methods use these unchecked semantics explicitly;
  a surrounding checked block does not change a method's contract. No instruction may leave a
  partly constructed result visible to the program.

- **SIMD-04 [Proposed]** Vector floating-point operations use the scalar numerical semantics for
  each lane, including subnormals, comparisons and signed zero. For these new operations, fix
  one canonical arithmetic quiet NaN per lane format; bitcasts, loads and stores preserve payloads.
  This does not change the NaN payload behaviour of existing scalar operations.
  `notEqual` is true for unordered comparisons. No fast-math mode, implicit FMA or unspecified
  reduction order is introduced. Add an explicitly named fused operation only with scalar-equivalent
  tests and a correct fallback. A native min/max instruction is not a specification for NaN or
  signed-zero handling; min/max are deferred until those rules are stated.

### 5.2 Masks and equality

- **SIMD-05 [Proposed]** `Mask128<T>` has the same lane count and lane width as `Vector128<T>`.
  Each lane is represented by all-zero bits for false or all-one bits for true. It occupies
  16 bytes, contains no references and has no implicit conversion to `boolean` or a numeric
  vector. Its default value has every lane false.

  Compare operations produce canonical masks. Lane construction accepts exactly one `boolean`
  per lane and encodes false as all zeros and true as all ones. `select` accepts only a mask of
  the matching lane type. No public SIMD operation exposes raw mask memory or allows a numeric
  vector-to-mask bitcast. Ordinary typed copies, fields and arrays remain available.

  Typed memory loads, unboxing, reflective invocation and native return adapters normalise
  incoming masks: each nonzero lane becomes all ones. This prevents unsafe memory aliases from
  supplying a noncanonical evaluation-stack mask. The verifier rejects forged noncanonical
  constants rather than normalising malformed modules. An `if` condition must explicitly use
  `anyTrue` or `allTrue`, or extract one lane.

  `Vector128<T>.equals` compares lanes using the scalar `equals` rule and returns one boolean;
  hashing uses the same rule. Floating-point lane comparison methods instead follow IEEE.
  Mask equality compares all logical lanes. There is no overloaded vector `==` in this proposal.

## 6. Memory, layout and safe access

- **WID-09 [Proposed]** Give wide scalars, vectors and masks 16-byte size and 8-byte natural
  alignment in managed storage. Preserve declared struct field order under UNS-05. Arrays have
  16-byte element stride, but neither their base nor a field is promised 16-byte alignment.
  A backend may align private spills more strongly. Ordinary access is not a 128-bit atomic
  operation; the [threading proposal](threading.md) must specify any future atomic support.

  Memory is little-endian: a wide integer's low word occupies the lower address. Vector lane 0
  occupies the lowest addressed lane-width bytes. Thus reinterpreting bytes `00` through `0f`
  as four `uint` lanes yields `0x03020100`, `0x07060504`, `0x0b0a0908`, `0x0f0e0d0c`.
  Typed loads do not perform byte-order conversion. File/network codecs remain explicit.

- **SIMD-06 [Proposed]** Safe vector loads accept `ReadOnlySpan<T>`; stores accept `Span<T>`.
  Indices count elements of `T`, not bytes. Validate the full lane range before touching memory,
  using `index >= 0`, `length >= laneCount` and `index <= length - laneCount`, without an
  overflowing `index + laneCount`. Use the existing bounds exception on failure.

  A failed store writes no lane. Successful unaligned access is valid and may use several
  machine instructions. It must not read or write beyond the requested lanes, including at a
  page boundary. Do not assume unused lanes can safely read past a span. Initial APIs exclude
  masked memory access; scalar tail handling requires no padded allocation.

  Managed references remain GC-tracked across a load/store; a temporary address is not a reason
  to pin or allocate. Keep the existing scoped/ref lifetime checks. An unsafe pointer API, if
  added later, must state its alignment and accessible-range preconditions separately.

## 7. Bytecode and verification

- **WID-10 [Proposed]** Introduce the extension through a new, explicitly supported bytecode
  format version. Keep all existing signature codes, verification tags, opcodes and operand
  meanings unchanged. Old loaders must reject the new version before decoding its contents;
  new loaders must continue to accept baseline modules. Do not hide new opcodes behind optional
  metadata that an old loader can ignore.

  Add symbolic signature kinds `I128`, `U128`, `F128`, `V128 <laneKind>` and `M128 <laneKind>`.
  Resolve `Vector128<T>`/`Mask128<T>` to these canonical shapes when `T` is known; retain a
  verifiable symbolic lane parameter for permitted generic bodies. Reflection still reports
  the corresponding closed core type. Allocate numeric encodings only when updating BC-03,
  BC-04, their detailed tables and the [stage0 document tests](../../stage0/test/reflect/stage0/bytecode/DocumentTest.java)
  together. No byte values in this document are assigned opcodes.

- **WID-11 [Proposed]** Add typed wide scalar constants, arithmetic, comparison, conversion and
  memory operations. Reuse existing token-based struct load/store operations where their stack
  effect already suffices; add specialised operations only for a distinct result or measured
  need. Wide constant payloads are exactly 16 little-endian bytes, including in constant fields.
  Do not extend the existing `<i8>` operand to 128-bit LEB128 or change BC-10's operand limits.

  A wide scalar, vector or mask is one logical evaluation-stack value. Physical interpreter
  storage may use multiple words; it must never treat one half as an independently addressable
  local. Add exact verifier types, retaining signedness where it affects legal operations and
  retaining the lane kind for vectors and masks. Specify compatible joins, calls, returns,
  boxing and stack-map encodings before accepting a module.

- **SIMD-07 [Proposed]** Use a finite family of typed SIMD bytecode operations. Operation and
  lane kind must be known during verification; arithmetic may not inspect operand contents to
  decide their type. Publish a legal operation/lane matrix and reject missing combinations.
  Do not emit processor-specific instructions such as SSE opcodes into portable modules.

  Verification rejects mismatched lane kinds, incompatible masks, unsupported element types,
  malformed 16-byte constants, noncanonical mask constants, invalid shuffle immediates and
  incompatible control-flow joins. No vector can contain a managed reference. Stack maps mark
  wide values as nonreferences and preserve full values through exceptions and safepoints.
  Resource limits must bound expanded frame bytes, not only the number of logical values.

## 8. Interpreter, JIT and AOT

- **WID-12 [Proposed]** Implement integers as two 64-bit words with carry/borrow handling and
  explicit signedness. Multiplication, division and conversion must implement the full result,
  not approximate it through floating point. Handle shift counts 0 and 64 explicitly so that
  the host or backend's masked 64-bit shifts cannot change a 128-bit operation.

  The interpreter supplies the reference execution path for all new operations. Binary128
  helpers use integer significand arithmetic with guard, round and sticky bits, including exact
  handling of exceptional values. Their implementation and independent test oracle are separate
  acceptance requirements, not an assumption that binary128 is a small addition.

  Compiled binary128 operations call these software helpers until a lowering passes the same
  conformance tests. Helper calls are the required JIT/AOT path, not an unsupported-operation
  error or a conversion through SSE2 `double`. Adoption must extend JIT-09 explicitly for this
  helper-based execution as well as the packed SIMD instructions below.

- **SIMD-08 [Proposed]** Extend JIT-09's scalar-only SSE2 policy explicitly when this proposal
  is adopted. Use packed SSE2 instructions where they match the defined 128-bit operation;
  expand other operations into multiple instructions or scalar helpers. SSE4, AVX and later
  instructions are optional acceleration paths, not minimum CPU requirements.

  Select optional paths from detected CPU features and, where relevant, enabled OS register
  state. AOT output has an explicit target feature set; portable output includes a baseline
  path. Cross-compilation must not inherit the build host's CPU features silently. No promise
  is made that one vector operation becomes one native instruction or is faster than scalar code.

- **WID-13 [Proposed]** Extend the Reflect calling convention, interpreter/compiled adapters,
  register allocation, stencil catalogue and frame maps together. Define argument/result
  placement for 128-bit integers, binary128, vectors and masks separately. Do not inherit a C
  ABI by accident. Preserve both words or all vector bits through calls, spills, exception
  unwinding, debugging, hot reload and future deoptimisation.

  Core reflection and dynamic invocation must preserve exact type identity and all bits. CPU
  SIMD values are not automatically a [GPU transfer ABI](gpu.md). GPU profiles must opt into
  supported types and semantics separately; binary128 software support on the CPU proves
  nothing about a device's capabilities.

## 9. Conformance and adversarial checks

- **WID-14 [Proposed]** Extend the [testing strategy](../testing.md) with type-checking, bytecode,
  runtime and differential cases for each feature. Run the same cases through `const`, the
  interpreter, baseline JIT and AOT; add the optimising JIT when it exists. Force fallback paths
  even on capable CPUs, and test each accelerated path against the fallback.

  Required integer seed vectors follow. Operands and results are hexadecimal bit patterns;
  shift counts are hexadecimal `int` values. `.ovf` means checked arithmetic. These are proposed
  test inputs, not results from a working Reflect implementation.

  | ID | Operation | Left (hex) | Right (hex) | Expected (hex or exception) |
  |---|---|---|---|---|
  | A01 | `add.u128` | `ffffffffffffffffffffffffffffffff` | `1` | `00000000000000000000000000000000` |
  | A02 | `add.ovf.u128` | `ffffffffffffffffffffffffffffffff` | `1` | `ArithmeticException` |
  | A03 | `add.i128` | `7fffffffffffffffffffffffffffffff` | `1` | `80000000000000000000000000000000` |
  | A04 | `div.i128` | `80000000000000000000000000000000` | `ffffffffffffffffffffffffffffffff` | `80000000000000000000000000000000` |
  | A05 | `div.ovf.i128` | `80000000000000000000000000000000` | `ffffffffffffffffffffffffffffffff` | `ArithmeticException` |
  | A06 | `rem.i128` | `80000000000000000000000000000000` | `ffffffffffffffffffffffffffffffff` | `00000000000000000000000000000000` |
  | A07 | `shr.un.i128` | `80000000000000000000000000000000` | `7f` | `00000000000000000000000000000001` |
  | A08 | `shl.u128` | `00000000000000000000000000000001` | `80` | `00000000000000000000000000000001` |
  | A09 | `shl.u128` | `00000000000000000000000000000001` | `40` | `00000000000000010000000000000000` |
  | A10 | `div.i128` | `fffffffffffffffffffffffffffffff9` | `3` | `fffffffffffffffffffffffffffffffe` |
  | A11 | `rem.i128` | `fffffffffffffffffffffffffffffff9` | `3` | `ffffffffffffffffffffffffffffffff` |

  Add boundary and random cases for carry, borrow, high-half products, division by zero,
  negative shift counts, casts and parsing failures. Use a test-only arbitrary-precision integer
  oracle to check exact results before reduction modulo $2^{128}$.

  Binary128 cases must include ties-to-even rounding, cancellation, subnormal boundaries,
  overflow, signed zero, infinities, NaN payload round-trips and arithmetic canonicalisation.
  `1 + 2^-112` must differ from `1`; `1 + 2^-113` must round to `1`. Include values that expose
  double rounding. Use published vectors and an independent correctly rounded oracle, not only
  comparisons between execution tiers sharing the same helper.

- **SIMD-09 [Proposed]** Include lane-isolation, mask, shuffle, conversion and memory tests:

  | Case | Required result |
  |---|---|
  | `Vector128<ubyte>`: lane 0 is 255, all other lanes zero; add a vector with only lane 0 set to 1 | Every lane is zero; no carry enters lane 1 |
  | Compare float lanes `[NaN, +0, -0, 1]` with `[NaN, -0, +0, 2]` | Equality mask `[false, true, true, false]`; not-equal is its logical inverse |
  | `select([true, false, true, false], [1, 2, 3, 4], [5, 6, 7, 8])` | `[1, 6, 3, 8]` |
  | Load/store a four-lane vector at index `length - 4` | Success, including unaligned storage |
  | Store a four-lane vector at index `length - 3` | Bounds exception before any write; destination unchanged |
  | Reinterpret bytes `00` through `0f` as `uint` lanes | The four values in WID-09, independent of execution tier |

  Test lengths from zero through two vectors plus one, negative and extreme indices, aliases,
  spills, generic calls, boxing, debugger inspection and safepoints with live values. Protected
  page-boundary tests must detect native over-reads even when the managed bounds check passed.
  Invalid-bytecode tests cover each SIMD-07 rejection, including generic instantiations.

  Perform small mutation checks: remove carry propagation, omit count masking, replace a full
  range check with a first-lane check, or choose a native comparison with wrong NaN behaviour.
  Each corresponding focused test must fail before the implementation is accepted.

## 10. Delivery and acceptance

- **WID-15 [Proposed]** Deliver in independent steps after the required self-hosting work:

  1. Settle source/API and format decisions. Extend the formal numeric and vector model and
     identify the proof obligations required by ARC-09. Publish legal instruction/type matrices.
  2. Add wide integer metadata, compiler checks, software arithmetic and interpreter execution.
     Add format, verifier and negative tests before enabling compiled emission.
  3. Add wide integer JIT/AOT lowering, calls, reflection and all-tier differential tests.
  4. Add SIMD types, masks, safe memory access and scalar fallback execution. Then add SSE2
     lowering and validate accelerated/fallback equivalence. This step does not depend on binary128.
  5. Add binary128 only after the software implementation, conversion/formatting APIs and
     independent oracle meet WID-07, WID-08 and WID-14. Then enable compiled helper calls.
  6. Add optional CPU acceleration and benchmark representative workloads separately from
     correctness. Update baseline documents only for the feature subset that is adopted.

  Every shipped subset must be complete across accepted module versions, verification,
  interpretation, JIT, AOT, `const`, reflection and GC maps. A verifier must reject unimplemented
  feature combinations; successful parsing is not a claim of executable support.

  Benchmarks should include integer parse/format and division, binary128 numeric kernels, byte
  scanning and bulk float arithmetic, with scalar baselines, fallback timings and small inputs.
  Record CPU features and code size. There is no speedup target until measurements exist.

## 11. Compatibility and costs

Existing source meanings and old module encodings remain stable. The main required changes are
larger frame values, new canonical types, a versioned verifier/format extension, wide arithmetic
helpers and new backend operations. Binary128 parsing, correct rounding and formatting are a
substantial independent task. SIMD adds register pressure and can increase code size when an
operation needs a scalar expansion. Alignment and bounds rules must not be weakened to hide
these costs.

This proposal does not change the unsafe policy, promise atomic 16-byte access, allow SIMD over
managed references, relax strict floating point or introduce implicit parallel execution.

## 12. Open decisions

- **WID-16 [Open]** Confirm whether to adopt wide integers and SIMD first, with binary128 as
  a later independent extension. Assign release and bytecode versions only after acceptance.
- **WID-17 [Open]** Fix binary128's and SIMD lane formats' canonical arithmetic NaN bits,
  binary128 decimal format rules and independent oracle, plus the public treatment of signalling
  NaN inputs. No hardware-dependent answer is permitted under the proposed strict profile.
- **SIMD-10 [Open]** Choose the built-in lane constraint's source spelling, intrinsic API names,
  exact calling convention and signature/verification encodings. These decisions must preserve
  generic verification and cannot be left as backend-only conventions.
- **SIMD-11 [Open]** Decide which later operations justify separate proposals: saturating or
  widening arithmetic, deterministic reductions, explicit FMA, wider/scalable vectors, masked
  memory access and target-specific intrinsics. None is required by the initial portable subset.