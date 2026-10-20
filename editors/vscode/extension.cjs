const { readFile } = require("node:fs/promises");
const path = require("node:path");
const { INITIAL, Registry, parseRawGrammar } = require("vscode-textmate");
const { loadWASM, OnigScanner, OnigString } = require("vscode-oniguruma");

function tokenClass(scopes) {
  for (let index = scopes.length - 1; index >= 0; index--) {
    const scope = scopes[index];
    if (scope.startsWith("meta.embedded.") || scope.startsWith("punctuation.")) return "";
    if (scope.startsWith("invalid.")) return "invalid";
    if (scope.startsWith("comment.")) return "comment";
    if (scope.startsWith("constant.character.escape.")) return "escape";
    if (scope.startsWith("constant.numeric.")) return "number";
    if (scope.startsWith("constant.language.") || scope.startsWith("constant.other.")) return "constant";
    if (scope.startsWith("string.")) return "string";
    if (scope.startsWith("entity.name.type.annotation.")) return "annotation";
    if (scope.startsWith("entity.name.namespace.")) return "namespace";
    if (scope.startsWith("entity.name.function.")) return "function";
    if (scope.startsWith("variable.other.member.")) return "member";
    if (scope.startsWith("variable.other.")) return "variable";
    if (scope.startsWith("storage.type.") || scope.startsWith("entity.name.type.")) return "type";
    if (scope.startsWith("keyword.operator.")) return "operator";
    if (scope.startsWith("keyword.") || scope.startsWith("storage.modifier.")
      || scope.startsWith("variable.language.")) return "keyword";
  }
  return "";
}

function highlight(code, grammar, escapeHtml) {
  let state = INITIAL;
  const lines = code.split("\n").map((line) => {
    const result = grammar.tokenizeLine(line, state);
    if (result.stoppedEarly) {
      throw new Error("Reflect preview tokenization stopped before completing a line.");
    }
    state = result.ruleStack;
    return result.tokens.map((token) => {
      const text = escapeHtml(line.slice(token.startIndex, token.endIndex));
      if (text === "") return "";
      const name = tokenClass(token.scopes);
      return name ? `<span class="reflect-token-${name}">${text}</span>` : text;
    }).join("");
  });
  return `<span class="reflect-code">${lines.join("\n")}</span>`;
}

async function activate(context) {
  await loadWASM(await readFile(require.resolve("vscode-oniguruma/release/onig.wasm")));
  const registry = new Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (patterns) => new OnigScanner(patterns),
      createOnigString: (text) => new OnigString(text)
    }),
    loadGrammar: async (scopeName) => {
      if (scopeName !== "source.reflect") return null;
      const filename = path.join(__dirname, "syntaxes/reflect.tmLanguage.json");
      return parseRawGrammar(await readFile(filename, "utf8"), filename);
    }
  });
  context.subscriptions.push(registry);
  const grammar = await registry.loadGrammar("source.reflect");
  if (!grammar) throw new Error("Could not load the Reflect grammar for Markdown previews.");

  return {
    extendMarkdownIt(md) {
      const originalHighlight = md.options.highlight;
      md.options.highlight = function (code, language, ...args) {
        const name = language?.toLowerCase();
        if (name === "reflect" || name === "rfl") {
          return highlight(code, grammar, md.utils.escapeHtml);
        }
        return originalHighlight
          ? originalHighlight.call(this, code, language, ...args)
          : md.utils.escapeHtml(code);
      };
      return md;
    }
  };
}

module.exports = { activate };
