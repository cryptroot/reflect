const assert = require("node:assert/strict");
const { readFileSync, readdirSync } = require("node:fs");
const path = require("node:path");
const { before, test } = require("node:test");
const { INITIAL, Registry, parseRawGrammar } = require("vscode-textmate");
const { loadWASM, OnigScanner, OnigString } = require("vscode-oniguruma");

const root = path.resolve(__dirname, "..");
const docs = path.resolve(root, "../../docs");
const manifest = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8"));
const markdownPath = process.env.VSCODE_MARKDOWN_GRAMMAR;
const markdownHost = markdownPath
  ? parseRawGrammar(readFileSync(markdownPath, "utf8"), markdownPath)
  : {
      scopeName: "text.html.markdown",
      patterns: [
        {
          name: "markup.fenced_code.block.markdown",
          begin: "^(`{3,}|~{3,}).*$",
          end: "^\\1[ \\t]*$"
        },
        {
          name: "markup.inline.raw.string.markdown",
          match: "`[^`]+`"
        }
      ]
    };
let registry;

before(async () => {
  await loadWASM(readFileSync(require.resolve("vscode-oniguruma/release/onig.wasm")));
  registry = new Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (patterns) => new OnigScanner(patterns),
      createOnigString: (text) => new OnigString(text)
    }),
    loadGrammar: async (scopeName) => {
      if (scopeName === markdownHost.scopeName) return markdownHost;
      const contribution = manifest.contributes.grammars.find(
        (grammar) => grammar.scopeName === scopeName
      );
      if (!contribution) return null;
      const filename = path.join(root, contribution.path);
      return parseRawGrammar(readFileSync(filename, "utf8"), filename);
    },
    getInjections: (scopeName) => manifest.contributes.grammars
      .filter((grammar) => grammar.injectTo?.includes(scopeName))
      .map((grammar) => grammar.scopeName)
  });
});

async function tokenize(source, scopeName = "source.reflect") {
  const grammar = await registry.loadGrammar(scopeName);
  assert.ok(grammar, `Missing grammar: ${scopeName}`);
  let state = INITIAL;
  return source.split("\n").map((line) => {
    const result = grammar.tokenizeLine(line, state);
    assert.equal(result.stoppedEarly, false);
    state = result.ruleStack;
    return result.tokens.map((token) => ({
      ...token,
      text: line.slice(token.startIndex, token.endIndex)
    }));
  });
}

function scopesAt(tokens, source, text, offset = 0) {
  const index = source.indexOf(text, offset);
  assert.notEqual(index, -1, `Missing text: ${text}`);
  const token = tokens.find((entry) => entry.startIndex <= index && entry.endIndex > index);
  assert.ok(token, `Missing token: ${text}`);
  return token.scopes;
}

function hasScope(scopes, prefix) {
  return scopes.some((scope) => scope.startsWith(prefix));
}

test("manifest registers Reflect, its file extension and embedded Markdown language", () => {
  assert.equal(manifest.contributes.languages[0].id, "reflect");
  assert.deepEqual(manifest.contributes.languages[0].extensions, [".rfl"]);
  assert.equal(manifest.contributes.grammars[0].scopeName, "source.reflect");
  assert.equal(
    manifest.contributes.grammars[1].embeddedLanguages["meta.embedded.block.reflect"],
    "reflect"
  );
  const configuration = JSON.parse(readFileSync(
    path.join(root, manifest.contributes.languages[0].configuration), "utf8"
  ));
  assert.deepEqual(configuration.comments, {
    lineComment: "//",
    blockComment: ["/*", "*/"]
  });
});

for (const label of ["Reserved", "Reserved for later", "Contextual"]) {
  test(`all ${label.toLowerCase()} keywords in LEX-03 are highlighted`, async () => {
    const specification = readFileSync(path.join(docs, "grammar.md"), "utf8");
    const match = specification.match(new RegExp(`- ${label}[^:\n]*: \`([^\`]+)\``));
    assert.ok(match, `Missing keyword list: ${label}`);
    for (const word of match[1].trim().split(/\s+/)) {
      const [[token]] = await tokenize(word);
      assert.ok(
        token.scopes.some((scope) => /^(keyword|storage|constant|variable\.language)\./.test(scope)),
        `Unhighlighted keyword: ${word}`
      );
    }
  });
}

test("module, workspace and proposal words are highlighted", async () => {
  const words = "exports main module opens provides requires to transitive unsafe uses version with "
    + "workspace modulePath output member aot allowUnsafe option severity error warning none "
    + "component system ensures old result transaction";
  for (const word of words.split(" ")) {
    const [[token]] = await tokenize(word);
    assert.ok(hasScope(token.scopes, "keyword."), word);
  }
});

test("keywords do not match inside longer or Unicode identifiers", async () => {
  const source = "classification publicValue _return λpublic publicλ";
  const [tokens] = await tokenize(source);
  assert.ok(tokens.every((token) => !hasScope(token.scopes, "keyword.")));
  assert.ok(tokens.every((token) => !hasScope(token.scopes, "storage.")));
});

test("comments suppress keyword and numeric rules and block comments do not nest", async () => {
  const source = "/* public /* nested */ return 42; // class 23";
  const [tokens] = await tokenize(source);
  assert.ok(hasScope(scopesAt(tokens, source, "public"), "comment.block."));
  assert.ok(!hasScope(scopesAt(tokens, source, "public"), "storage."));
  assert.ok(hasScope(scopesAt(tokens, source, "return"), "keyword.control."));
  assert.ok(hasScope(scopesAt(tokens, source, "class"), "comment.line."));
  assert.ok(!hasScope(scopesAt(tokens, source, "23"), "constant.numeric."));
});

test("multiline block comments and documentation tags are highlighted", async () => {
  const lines = await tokenize("/* start\npublic 42\n*/ return;\n/// @param value description");
  assert.ok(lines[1].every((token) => hasScope(token.scopes, "comment.block.")));
  assert.ok(hasScope(lines[2].find((token) => token.text === "return").scopes, "keyword.control."));
  assert.ok(hasScope(lines[3].find((token) => token.text === "@param").scopes,
    "keyword.other.documentation."));
});

test("ordinary strings, characters and escapes are distinct from code", async () => {
  const source = String.raw`"public 42 \n \u{1F600}" '\u0041' '\u{41}' "bad\q"`;
  const [tokens] = await tokenize(source);
  assert.ok(hasScope(scopesAt(tokens, source, "public"), "string.quoted.double."));
  assert.ok(!hasScope(scopesAt(tokens, source, "public"), "storage."));
  assert.ok(hasScope(scopesAt(tokens, source, "\\n"), "constant.character.escape."));
  assert.ok(hasScope(scopesAt(tokens, source, "\\u{1F600}"), "constant.character.escape."));
  assert.ok(hasScope(scopesAt(tokens, source, "\\u0041"), "constant.character.escape."));
  assert.ok(hasScope(scopesAt(tokens, source, "\\u{41}"), "invalid.illegal.escape."));
  assert.ok(hasScope(scopesAt(tokens, source, "\\q"), "invalid.illegal.escape."));
});

test("unterminated single-line literals do not consume the following line", async () => {
  const lines = await tokenize("\"unterminated\nreturn 42;\n'x\npublic class App {}");
  assert.ok(hasScope(lines[1][0].scopes, "keyword.control."));
  assert.ok(hasScope(lines[3][0].scopes, "storage.modifier."));
});

test("interpolation handles escaped braces, nested strings, braces and format text", async () => {
  const source = '$"{{literal}} {new Thing { value = "text" }} {12.5:0.00}"';
  const [tokens] = await tokenize(source);
  assert.ok(hasScope(scopesAt(tokens, source, "{{"), "constant.character.escape."));
  assert.ok(hasScope(scopesAt(tokens, source, "new"), "keyword.other."));
  assert.ok(hasScope(scopesAt(tokens, source, "text"), "string.quoted.double.reflect"));
  assert.ok(hasScope(scopesAt(tokens, source, "12.5"), "constant.numeric.float."));
  assert.ok(hasScope(scopesAt(tokens, source, "0.00"), "string.unquoted.format."));
});

test("parenthesized conditional expressions keep their colon inside interpolation code", async () => {
  const source = '$"{(true ? 1 : 2):0}"';
  const [tokens] = await tokenize(source);
  assert.ok(hasScope(scopesAt(tokens, source, "2"), "constant.numeric."));
  assert.ok(hasScope(scopesAt(tokens, source, "0}"), "string.unquoted.format."));
});

test("template splices contain Reflect expressions and balanced nested braces", async () => {
  const source = "quote expr { ${new Thing { value = true }} + 1 }";
  const [tokens] = await tokenize(source);
  assert.ok(hasScope(scopesAt(tokens, source, "new"), "meta.interpolation.template."));
  assert.ok(hasScope(scopesAt(tokens, source, "true"), "constant.language."));
  assert.ok(!hasScope(scopesAt(tokens, source, "1"), "meta.interpolation.template."));
});

test("numeric literals include bases, separators, suffixes and exponents", async () => {
  for (const literal of ["0xFF_FFu", "0b10_01UL", "42", "1__000uL", "1.5f", "2.5e-3", "2d", "3e+2F"]) {
    const [[token]] = await tokenize(literal);
    assert.equal(token.text, literal);
    assert.ok(hasScope(token.scopes, "constant.numeric."), literal);
  }
});

test("annotations and annotation declarations have distinct scopes", async () => {
  const source = "@reflect.meta.Observable @interface Example {}";
  const [tokens] = await tokenize(source);
  assert.ok(hasScope(scopesAt(tokens, source, "reflect.meta.Observable"),
    "entity.name.type.annotation."));
  assert.ok(hasScope(scopesAt(tokens, source, "interface"), "keyword.declaration."));
});

test("types, variables, dotted members, calls and constants have separate scopes", async () => {
  const source = "Account account; account.balance = account.total<int>(42); "
    + "println(account?.name); account?.reset(); Status.READY; MAX_COUNT; T item;";
  const [tokens] = await tokenize(source);
  for (const [text, prefix] of [
    ["Account", "entity.name.type."],
    ["account;", "variable.other.reflect"],
    ["balance", "variable.other.member."],
    ["total", "entity.name.function.member."],
    ["println", "entity.name.function.reflect"],
    ["name", "variable.other.member."],
    ["reset", "entity.name.function.member."],
    ["Status", "entity.name.type."],
    ["READY", "constant.other."],
    ["MAX_COUNT", "constant.other."],
    ["T item", "entity.name.type."],
    ["item", "variable.other.reflect"]
  ]) {
    assert.ok(hasScope(scopesAt(tokens, source, text), prefix), text);
  }
});

test("namespace paths, type declarations and accessor properties are recognized", async () => {
  const source = "namespace demo.bank; import static reflect.io.Console; module demo.app { "
    + "class account { String title { get; set; } void deposit(int amount) {} } }";
  const [tokens] = await tokenize(source);
  for (const [text, prefix] of [
    ["demo.bank", "entity.name.namespace."],
    ["reflect.io.Console", "entity.name.namespace."],
    ["demo.app", "entity.name.namespace."],
    ["account", "entity.name.type."],
    ["title", "variable.other.member."],
    ["deposit", "entity.name.function."],
    ["amount", "variable.other.reflect"]
  ]) {
    assert.ok(hasScope(scopesAt(tokens, source, text), prefix), text);
  }
});

test("identifier rules preserve strings, comments, decimals and Unicode identifiers", async () => {
  const source = '"account.balance()" /* account.total() */ 1.25; café.name; αβ.μέλος;';
  const [tokens] = await tokenize(source);
  assert.ok(hasScope(scopesAt(tokens, source, "account.balance"), "string."));
  assert.ok(hasScope(scopesAt(tokens, source, "account.total"), "comment."));
  assert.ok(hasScope(scopesAt(tokens, source, "1.25"), "constant.numeric.float."));
  assert.ok(hasScope(scopesAt(tokens, source, "café"), "variable.other.reflect"));
  assert.ok(hasScope(scopesAt(tokens, source, "μέλος"), "variable.other.member."));
});

test(`Markdown fences inject Reflect (${markdownPath ? "VS Code" : "minimal host"} grammar)`,
  async () => {
    for (const marker of ["```", "~~~", "````"]) {
      for (const language of ["reflect", "rfl", "Reflect"]) {
        const source = `${marker}${language}\npublic class App {}\n${marker}\nplain public`;
        const lines = await tokenize(source, "text.html.markdown");
        assert.ok(hasScope(lines[1][0].scopes, "meta.embedded.block.reflect"));
        assert.ok(hasScope(lines[1][0].scopes, "storage.modifier."));
        assert.ok(!hasScope(lines[3][0].scopes, "meta.embedded.block.reflect"));
      }
    }
  });

test("Markdown accepts longer closing fences but not shorter or mismatched ones", async () => {
  const source = "````reflect\npublic\n```\n~~~\nreturn\n`````\nplain public";
  const lines = await tokenize(source, "text.html.markdown");
  assert.ok(hasScope(lines[4][0].scopes, "keyword.control."));
  assert.ok(!hasScope(lines[6][0].scopes, "meta.embedded.block.reflect"));
});

test("Markdown closing fences recover from unfinished Reflect comments", async () => {
  const source = "```reflect\n/* unfinished\n```\nplain public";
  const lines = await tokenize(source, "text.html.markdown");
  assert.ok(hasScope(lines[1][0].scopes, "comment.block."));
  assert.ok(!hasScope(lines[3][0].scopes, "comment."));
  assert.ok(!hasScope(lines[3][0].scopes, "meta.embedded.block.reflect"));
});

test("indented Markdown fences embed Reflect without consuming following prose", async () => {
  const source = "- Example:\n\n  ```reflect\n  public class App {}\n  ```\n\nplain public";
  const lines = await tokenize(source, "text.html.markdown");
  assert.ok(lines[3].some((token) => hasScope(token.scopes, "storage.modifier.")));
  assert.ok(!hasScope(lines[6][0].scopes, "meta.embedded.block.reflect"));
});

test("Markdown injection leaves inline code and other fenced languages alone", async () => {
  const source = "`reflect public`\n```text\n```reflect\npublic\n```\nplain";
  const lines = await tokenize(source, "text.html.markdown");
  for (const tokens of lines) {
    assert.ok(tokens.every((token) => !hasScope(token.scopes, "meta.embedded.block.reflect")));
  }
});

test("all documented Reflect fences tokenize and actual stage0 Java remains Java", async (t) => {
  const files = readdirSync(docs, { recursive: true })
    .filter((filename) => filename.endsWith(".md"));
  let blocks = 0;
  let javaBlocks = 0;
  for (const filename of files) {
    const content = readFileSync(path.join(docs, filename), "utf8");
    assert.doesNotMatch(content, /^[ \t]*```csharp\b/m, filename);
    const markdownLines = await tokenize(content, "text.html.markdown");
    for (const match of content.matchAll(/^[ \t]*```reflect[ \t]*\n([\s\S]*?)^[ \t]*```[ \t]*$/gm)) {
      await tokenize(match[1]);
      const firstLine = content.slice(0, match.index).split("\n").length;
      for (const [index, line] of match[1].split("\n").entries()) {
        if (line.trim() === "") continue;
        assert.ok(
          markdownLines[firstLine + index].some(
            (token) => hasScope(token.scopes, "meta.embedded.block.reflect")
          ),
          `${filename}:${firstLine + index + 1}: Reflect fence was not embedded`
        );
      }
      blocks++;
    }
    for (const match of content.matchAll(/^[ \t]*```java\b/gm)) {
      assert.equal(filename, "examples.md");
      javaBlocks++;
    }
  }
  assert.ok(blocks > 0);
  assert.equal(javaBlocks, 1);
  t.diagnostic(`Checked ${blocks} Reflect documentation fences.`);
});
