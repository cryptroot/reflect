# Testing

How Reflect is tested, from source conformance tests to bootstrap checks, differential runs,
fuzzing against the Lean model, and examples.md itself. Conventions:
[architecture.md](architecture.md) §0.

## 1. Kinds of tests

| Kind | Checks | Runs on |
|---|---|---|
| conformance (§2) | language rules, diagnostics, output | VM0, then `rfl` in every tier and AOT |
| unit (§4) | compiler, VM and library internals | VM0 or `rfl` |
| bootstrap (§5) | fixpoint, identical output on VM0 and `rfl` | CI |
| differential (§6) | interpreter, JIT and AOT agree | `rfl` |
| bytecode (§7) | `rfla`/`rfld` round trips, verifier rejections | `rfl` |
| collector stress (§8) | precise maps, barriers, interior pointers | `rfl` |
| fuzzing (§9) | random well-typed programs against the Lean oracle | CI, nightly |
| examples (§10) | every example in examples.md | CI |

## 2. Conformance tests

- **TST-01 [Decided, details Proposed]** A test is a source file whose leading comments state the
  expectations (ex §12.1):

  | Directive | Meaning |
  |---|---|
  | `// expect-output: text` | one line of standard output, in order |
  | `// expect-error: CODE @ line:col` | a compile error at that position |
  | `// expect-warning: CODE @ line:col` | a warning at that position |
  | `// expect-exit: n` | the process exit code |
  | `// expect-throws: Type` | an uncaught exception of that type |
  | `// args: a b c` | arguments for `main` |
  | `// requires-feature: name` | skipped when the compiler lacks the feature (§3) |
  | `// tiers: interpret, jit, aot` | restricts the tiers (default: all) |

  Line and column count from the first line of the file, comments included. Tests live in
  `tests/lang/<feature>/`; a directory containing `module-info.rfl` files is one multi-module test
  whose expectations sit in a `test.rfl` at its root.

  ```csharp
  // file: tests/lang/nullability/cast-null.rfl
  // expect-throws: reflect.core.NullPointerException
  // requires-feature: nullability
  namespace tests.nullability;

  public final class Main {
      public static void main(String[] args) {
          Object? o = null;
          String s = (String) o;            // NUL-05: null cast to a non-null type
      }
  }
  ```

## 3. Features and proof status

- **TST-02 [Proposed]** `tests/features.rfl` lists every feature: its name (used by
  `requires-feature`), the rule IDs it covers, the first compiler that supports it, its
  Featherweight Reflect layer, and its status (`experimental` or `stable`). Each compiler declares
  the features it supports, so one suite follows the language from R0 onward.
- **TST-06 [Decided, Q31, Q33]** A feature becomes `stable` only when its layer's theorems are
  proven (formal-model.md); the v1.0 release check fails if a feature marked stable lacks a proof,
  and experimental features need `--experimental` from v1.0 on.

## 4. Unit tests

- **TST-03 [Decided]** Unit tests in Reflect use `@Test` from `reflect.testing` and are found by
  reflection (ex §12.1); `rfl test` runs them and the conformance suite. Assertions throw
  `AssertionError`; a test passes when it returns normally.

## 5. Bootstrap tests

- **TST-07 [Proposed]** CI builds the compiler with the seed, then with itself, and compares bytes
  (BOOT-05, BOOT-06). Before v0.6 it also compares the output of `rflc` running on VM0 and on
  `rfl` for the whole stdlib and compiler.

## 6. Differential tests

- **TST-08 [Proposed]** The conformance suite runs with `--interpret`, with the JIT, and AOT;
  output, exit codes and exceptions must match exactly (JIT-07). A failure in only one tier points
  at that tier.

## 7. Bytecode tests

- **TST-09 [Proposed]** Every module the suite produces goes through `rfld` then `rfla` and must
  come back byte-identical (BC-08). Hand-written invalid `.rfla` files, each tagged with the rule it
  breaks (`// expect-verify-error: BC-07 uninitialized`), must be rejected by the verifier.

## 8. Collector stress

- **TST-10 [Proposed]** Nightly runs of the suite in the collector's stress modes (GC-08): collect at
  every allocation, at every safepoint, verify the heap after each collection, poison freed memory.

## 9. Fuzzing and the Lean oracle

- **TST-11 [Proposed]** A generator produces random well-typed programs of the Featherweight
  Reflect fragments, prints them as Reflect source, and compares `rflc` plus `rfl` with the Lean
  evaluator compiled from the model (LEAN-03). A mismatch is either an implementation bug or a gap
  between the model and the language, and both are filed.

## 10. examples.md as a test

- **TST-12 [Proposed]** A script extracts the code blocks of examples.md:
  - blocks starting with `// file:` form modules by path and are compiled together;
  - `// prints:` comments become expected output for the enclosing `main`, in order;
  - in `// rejected` blocks, each line with `// error:` must produce an error on that line;
  - `// future`, `// lowered`, `text`, `java` blocks and fragments without `// file:` are skipped.

  examples.md then cannot drift from the compiler unnoticed; its known problems are listed in
  sketchpad.md A.4 and A.6.

## 11. Continuous integration

- **TST-05 [Decided, Q04]** CI runs in the `reflect-build` image (BLD-05): build with the seed,
  self-build and fixpoint, conformance in all tiers, differential and bytecode tests, examples,
  and a separate job for `lake build` in `formal/`. Nightly jobs add stress modes and fuzzing.
