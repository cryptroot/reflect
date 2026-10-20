# Reflect syntax highlighting for VS Code

No Java, C#, language server or Reflect compiler is needed. The extension registers the
language ID `reflect`, the `.rfl` extension and the TextMate scope `source.reflect`, and adds
highlighting to VS Code's built-in Markdown preview using the same grammar.

## Install locally

From this directory, with Node.js 20 or newer and npm:

```sh
npm ci
npm test
npm run package
code --install-extension reflect-language-0.0.3.vsix
```

Alternatively, use **Extensions: Install from VSIX...** in VS Code and select the generated
package. Run **Developer: Reload Window** after upgrading to refresh existing previews.

Copy the same VSIX to other machines or VS Code instances/profiles and install it there too.
The package includes the tokenizer, its WebAssembly engine and the grammar: target instances
do not need Node.js, npm or any build tools installed separately. For Remote SSH or Dev
Containers, install in the extension host selected by VS Code for that window.

To try the extension without installing it, run this from the repository root:

```sh
code --new-window --extensionDevelopmentPath="$PWD/editors/vscode" "$PWD"
```

The extension is local; it is not published on the Marketplace. Packaging skips the license
check because this repository does not currently declare a license; it does not grant a new one.

## Markdown examples

Use the language name `reflect`, not `java` or `csharp`:

````markdown
```reflect
public final class App {
    public static void main(String[] args) {
        println($"Hello, {args[0]}!");
    }
}
```
````

The extension highlights these fences in both the **Markdown source editor** and the
**built-in Markdown preview**. Open a Markdown file and run **Markdown: Open Preview to the
Side** (`Ctrl+K V` on Linux/Windows) to view it. The `rfl` alias and tilde fences also work.

The preview plugin tokenizes Reflect blocks with the existing TextMate grammar and escapes
the source before emitting styled HTML. Other fenced languages and Markdown rendering are
left to the existing renderer. No scripts or network access are needed in the preview.
The preview has its own distinct Reflect palette for dark and light backgrounds, including
high-contrast variants. It does not rely on UI icon colours, which can all resolve to the
same colour in a theme, and does not reproduce custom editor syntax-theme rules.

### Preview colours

Each category has a separate colour. These dark/light defaults are defined in
[preview.css](./preview.css):

| Category | Dark | Light | Recognition |
|---|---|---|---|
| Keyword | `#c792ea` | `#7b21b0` | Keywords and modifiers |
| Type | `#56d4dd` | `#00636b` | Primitive types, declared types and PascalCase identifiers |
| Variable | `#82b1ff` | `#174fc4` | Other identifiers, including parameters |
| Member | `#ffad66` | `#9b4300` | Dotted field/property access and accessor-style property declarations |
| Function/method | `#ffe082` | `#665500` | Names followed by a call/signature, including generic calls |
| Constant | `#b4e88d` | `#286600` | `true`, `false`, `null` and multi-character ALL_CAPS names |
| Namespace | `#b8b2ff` | `#4d48a8` | Namespace, import and module declaration paths |
| Annotation | `#ff8fcf` | `#a41472` | `@Annotation` |
| String/character | `#f29b8f` | `#a52a2a` | String and character contents |
| Number | `#d4c88a` | `#847038` | Numeric literals |
| Comment | `#9aa5b1` | `#59636e` | Comments, also italicized |
| Escape | `#83e7cf` | `#287d52` | Escape sequences |
| Operator | `#c9d1d9` | `#39424e` | Operators |
| Invalid escape | `#ff657a` | `#b80050` | Invalid escapes, also underlined |

For example, in `Account account; account.balance = account.total(42);`, `Account` is a type,
`account` a variable, `balance` a member, `total` a method and `42` a number.

To change the shared defaults, edit the `--reflect-*` variables in
[preview.css](./preview.css), rebuild the VSIX and reinstall it in each instance. Alternatively,
load a personal stylesheet through VS Code's `markdown.styles` setting; user styles are loaded
after the extension's styles. For example:

```css
.reflect-code,
.vscode-light .reflect-code,
.vscode-high-contrast-light .reflect-code {
  --reflect-keyword: #c792ea;
  --reflect-member: #ffad66;
}
```

Choose colours with sufficient contrast against your preview background. These preview-only
settings leave `.rfl` files and Markdown source highlighting under the active editor theme.

This does not change rendering on GitHub or in third-party preview extensions with their own
renderers. GitHub support would require upstream language registration.

## Keywords and coverage

The canonical lists are in [Grammar, LEX-03](../../docs/grammar.md#22-keywords).
The grammar groups these words into theme-compatible scopes:

- Reserved: `abstract boolean break byte case catch char checked class const continue default
  do double else enum event explicit extends extern false final finally float for if implements
  import in instanceof int interface long namespace native new null operator out override
  private protected public ref return short sizeof stackalloc static struct super switch this
  throw throws true try ubyte uint ulong unsafe ushort using virtual void while`.
- Reserved for later: `async await fixed goto infix postfix precedence prefix synchronized
  volatile yield`.
- Contextual: `add expr field get member mutating nameof partial permits quote record remove
  scoped sealed set stmt unsealed value var when where`.
- Module descriptors: `exports main module opens provides requires to transitive unsafe uses
  version with`.
- Workspace descriptors: `workspace modulePath output member aot allowUnsafe option severity
  error warning none`.
- Proposal examples: `component system ensures old result transaction`; `requires` is already
  included for module descriptors. These are highlighted without making them baseline keywords.

The grammar also handles comments, documentation tags, annotations, numeric and character
literals, escapes, interpolated strings, template splices, operators, brackets and identifier
categories.

This is lexical highlighting, not parsing or validation. Contextual, descriptor and proposed
words are coloured wherever they appear as whole words, even when used as ordinary identifiers.
Identifier categories are also lexical heuristics, not symbol resolution: lowercase type uses
can look like variables, capitalized calls can look like types, and undotted field names cannot
reliably be distinguished from locals. Semantic highlighting can refine this once a language
server exists.

## Tests

`npm test` tokenizes with `vscode-textmate` and `vscode-oniguruma`, the TextMate engine and
regular-expression implementation used by VS Code. Tests check the keyword lists against the
specification, lexical constructs, Markdown injection and every Reflect example in the docs.
Preview tests exercise the extension activation and Markdown-it hook, HTML escaping, theme
styles, distinct identifier categories, colour uniqueness and contrast, delegation to other
highlighters and rendering all documented Reflect blocks.
The default Markdown source-editor tests use a small host grammar to stay self-contained.

To additionally exercise the installed VS Code Markdown grammar, set its path explicitly:

```sh
VSCODE_MARKDOWN_GRAMMAR=/path/to/code/resources/app/extensions/markdown-basics/syntaxes/markdown.tmLanguage.json npm test
```
