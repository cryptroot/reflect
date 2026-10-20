const assert = require("node:assert/strict");
const { readFileSync, readdirSync } = require("node:fs");
const path = require("node:path");
const { after, before, test } = require("node:test");
const MarkdownIt = require("markdown-it");
const { activate } = require("../extension.cjs");

const root = path.resolve(__dirname, "..");
const context = { subscriptions: [] };
let extension;

before(async () => {
  extension = await activate(context);
});

after(() => {
  for (const subscription of context.subscriptions) subscription.dispose();
});

function renderer(options) {
  return extension.extendMarkdownIt(new MarkdownIt(options));
}

function textContent(html) {
  const entities = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"' };
  return html.replace(/<[^>]*>/g, "").replace(/&(?:amp|lt|gt|quot);/g,
    (entity) => entities[entity]);
}

test("manifest activates the preview plugin and packages its runtime and stylesheet", () => {
  const manifest = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8"));
  assert.equal(manifest.main, "./extension.cjs");
  assert.ok(manifest.activationEvents.includes("onLanguage:markdown"));
  assert.equal(manifest.contributes["markdown.markdownItPlugins"], true);
  assert.deepEqual(manifest.contributes["markdown.previewStyles"], ["./preview.css"]);
  assert.ok(manifest.dependencies["vscode-textmate"]);
  assert.ok(manifest.dependencies["vscode-oniguruma"]);
  assert.doesNotMatch(manifest.scripts.package, /--no-dependencies/);
  const ignored = readFileSync(path.join(root, ".vscodeignore"), "utf8");
  assert.doesNotMatch(ignored, /^node_modules\/\*\*$/m);
});

test("preview renders Reflect and rfl fences with the existing TextMate scopes", () => {
  const md = renderer();
  for (const language of ["reflect", "rfl", "Reflect", "RFL"]) {
    const html = md.render(`\`\`\`${language}\npublic class App { int value = 42; }\n\`\`\``);
    assert.match(html, /<pre><code class="language-/);
    assert.match(html, /<span class="reflect-code">/);
    assert.match(html, /<span class="reflect-token-keyword">public<\/span>/);
    assert.match(html, /<span class="reflect-token-type">int<\/span>/);
    assert.match(html, /<span class="reflect-token-number">42<\/span>/);
  }
});

test("preview preserves tilde fences, fence attributes and source-mapping renderers", () => {
  const md = new MarkdownIt();
  const originalFence = md.renderer.rules.fence;
  extension.extendMarkdownIt(md);
  assert.equal(md.renderer.rules.fence, originalFence);
  const tokens = md.parse("~~~reflect example\nreturn true;\n~~~", {});
  tokens[0].attrSet("data-line", "7");
  const html = md.renderer.render(tokens, md.options, {});
  assert.match(html, /data-line="7"/);
  assert.match(html, /class="language-reflect"/);
  assert.match(html, /reflect-token-keyword">return/);
  assert.match(html, /reflect-token-constant">true/);
});

test("other languages delegate to the original highlighter with its arguments and receiver", () => {
  const calls = [];
  const original = function (...args) {
    calls.push({ args, receiver: this });
    return "<span>original highlighting</span>";
  };
  const md = renderer({ highlight: original });
  const receiver = {};
  assert.equal(
    md.options.highlight.call(receiver, "const x = 1;", "javascript", "extra"),
    "<span>original highlighting</span>"
  );
  assert.deepEqual(calls, [{
    args: ["const x = 1;", "javascript", "extra"],
    receiver
  }]);
  md.render("```reflect\nreturn;\n```");
  assert.equal(calls.length, 1);
});

test("unrecognized languages, unlabelled fences and ordinary Markdown are unchanged", () => {
  const original = new MarkdownIt();
  const md = renderer();
  for (const source of [
    "```javascript\nconst x = '<value>';\n```",
    "```unknown\n<tag>& text\n```",
    "```\npublic class App {}\n```",
    "# Title\n\nText with `public class` and **emphasis**."
  ]) {
    assert.equal(md.render(source), original.render(source));
  }
});

test("preview escapes markup and preserves code text, whitespace, entities and Unicode", () => {
  const md = renderer({ html: true });
  const source = 'String s = "</code><script>alert(1)</script>&lt;&\\"";\n'
    + "// <img src=x onerror=alert(1)> 😀 café\n\treturn;\n\n";
  const html = md.render(`\`\`\`reflect\n${source}\`\`\``);
  assert.doesNotMatch(html, /<script|<img|onerror="/i);
  assert.match(html, /&lt;script&gt;/);
  assert.match(html, /&amp;lt;/);
  assert.equal(textContent(html), `${source}\n`);
});

test("multiline comment state stays within a block and never leaks to another fence", () => {
  const md = renderer();
  const html = md.render("```reflect\n/* start\npublic 42\n```\n\n```reflect\npublic\n```");
  assert.match(html, /reflect-token-comment">public 42<\/span>/);
  assert.match(html, /reflect-token-keyword">public<\/span>/);
});

test("interpolation expressions do not inherit the surrounding string colour", () => {
  const md = renderer();
  const source = '$"Hello {name}, {new Thing()}, {(true ? 1 : 2):0.00}!"';
  const html = md.options.highlight(source, "reflect");
  assert.match(html, /reflect-token-string">Hello /);
  assert.doesNotMatch(html, /reflect-token-string">name/);
  assert.match(html, /reflect-token-variable">name/);
  assert.match(html, /reflect-token-keyword">new/);
  assert.match(html, /reflect-token-constant">true/);
  assert.match(html, /reflect-token-number">2/);
  assert.match(html, /reflect-token-string">0.00/);
  assert.equal(textContent(html), source);
});

test("invalid escapes are visibly classified rather than silently hidden", () => {
  const md = renderer();
  const html = md.options.highlight(String.raw`"bad\q"`, "reflect");
  assert.match(html, /reflect-token-invalid">\\q<\/span>/);
});

test("direct highlighting preserves empty input, final newlines and CRLF", () => {
  const md = renderer();
  for (const source of ["", "\n", "return;", "return;\n", "/* x\r\n y */\r\n", "😀\n\n"]) {
    assert.equal(textContent(md.options.highlight(source, "reflect")), source);
  }
});

test("extending a Markdown renderer twice does not duplicate Reflect markup", () => {
  const md = renderer();
  assert.equal(extension.extendMarkdownIt(md), md);
  const html = md.render("```reflect\nreturn;\n```");
  assert.equal(html.match(/class="reflect-code"/g).length, 1);
});

test("preview styles cover every category without relying on UI icon colours", () => {
  const css = readFileSync(path.join(root, "preview.css"), "utf8");
  const html = renderer().options.highlight(
    "namespace demo; @Example public class Account { int count = 42; // comment\n"
      + String.raw`$"text \n {account.balance} {account.total()} {true} {1 + 2}" "bad\q" }`,
    "reflect"
  );
  const categories = new Set();
  for (const match of html.matchAll(/class="(reflect-token-[a-z]+)"/g)) {
    assert.ok(css.includes(`.reflect-code .${match[1]} {`), match[1]);
    categories.add(match[1].replace("reflect-token-", ""));
  }
  assert.deepEqual([...categories].sort(), [
    "annotation", "comment", "constant", "escape", "function", "invalid", "keyword",
    "member", "namespace", "number", "operator", "string", "type", "variable"
  ]);
  for (const rule of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    assert.ok(rule[1].trim().split(",").every((selector) => selector.includes(".reflect-code")));
  }
  assert.doesNotMatch(css, /var\(--vscode-(symbolIcon|debugTokenExpression)/);
  assert.match(css, /\.vscode-light .reflect-code/);
  assert.match(css, /\.vscode-high-contrast-light .reflect-code/);
});

function luminance(hex) {
  const channels = hex.match(/[a-f\d]{2}/gi).map((value) => {
    const channel = parseInt(value, 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

test("both palettes give every category a distinct colour with readable contrast", () => {
  const css = readFileSync(path.join(root, "preview.css"), "utf8");
  const paletteRules = [...css.matchAll(/([^{}]+)\{([^{}]*--reflect-keyword:[^{}]*)\}/g)];
  assert.equal(paletteRules.length, 2);
  for (const [index, rule] of paletteRules.entries()) {
    const colours = [...rule[2].matchAll(/--reflect-([a-z]+):\s*(#[a-f\d]{6})/gi)];
    assert.equal(colours.length, 14);
    assert.equal(new Set(colours.map((match) => match[2].toLowerCase())).size, 14);
    for (const [first, [, firstCategory, firstColour]] of colours.entries()) {
      const firstRgb = firstColour.slice(1).match(/../g).map((value) => parseInt(value, 16));
      for (const [, secondCategory, secondColour] of colours.slice(first + 1)) {
        const secondRgb = secondColour.slice(1).match(/../g).map((value) => parseInt(value, 16));
        const distance = Math.hypot(...firstRgb.map((value, channel) => value - secondRgb[channel]));
        assert.ok(distance >= 40, `${firstCategory} and ${secondCategory} are too similar`);
      }
    }
    const backgrounds = index === 0 ? ["#1e1e1e", "#000000"] : ["#ffffff"];
    for (const [, category, colour] of colours) {
      assert.ok(css.includes(`color: var(--reflect-${category});`), category);
      for (const background of backgrounds) {
        const values = [luminance(colour), luminance(background)].sort((a, b) => a - b);
        const contrast = (values[1] + 0.05) / (values[0] + 0.05);
        assert.ok(contrast >= 4.5, `${category}: ${colour} on ${background} is ${contrast}:1`);
      }
    }
  }
});

test("preview gives identifiers separate categories, including inside interpolation", () => {
  const md = renderer();
  const source = 'namespace demo.bank; @Example class Account { String title { get; set; } '
    + 'void deposit(int amount) { account.balance += account.total(amount); '
    + 'println($"{account.balance} {amount}"); Status.READY; } }';
  const html = md.options.highlight(source, "reflect");
  for (const [text, category] of [
    ["demo.bank", "namespace"], ["Example", "annotation"], ["class", "keyword"],
    ["Account", "type"], ["String", "type"], ["title", "member"], ["deposit", "function"],
    ["amount", "variable"], ["account", "variable"], ["balance", "member"],
    ["total", "function"], ["println", "function"], ["READY", "constant"]
  ]) {
    assert.ok(html.includes(`<span class="reflect-token-${category}">${text}</span>`),
      `${text} should be a ${category}`);
  }
  assert.equal(textContent(html), source);
});

test("every documented Reflect fence renders with escaped, unchanged source text", (t) => {
  const md = renderer({ html: true });
  const docs = path.resolve(root, "../../docs");
  let blocks = 0;
  for (const filename of readdirSync(docs, { recursive: true })) {
    if (!filename.endsWith(".md")) continue;
    const source = readFileSync(path.join(docs, filename), "utf8");
    const tokens = md.parse(source, {});
    const html = md.renderer.render(tokens, md.options, {});
    const reflectBlocks = tokens.filter((token) => token.type === "fence"
      && /^(reflect|rfl)$/i.test(token.info.trim().split(/\s+/)[0]));
    assert.equal((html.match(/class="reflect-code"/g) || []).length, reflectBlocks.length, filename);
    for (const block of reflectBlocks) {
      assert.equal(textContent(md.options.highlight(block.content, "reflect")), block.content,
        filename);
      blocks++;
    }
  }
  assert.ok(blocks > 0);
  t.diagnostic(`Rendered ${blocks} Reflect documentation fences.`);
});
