package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * The assembler rfla (README §7.4): reads .rfla text (§8.1) and writes the module that the text
 * states, or reports the first defect with its code, line and column (Q79). The text gives the
 * signature that each row of another module holds, so rfla reads no other module (Q69). rfla checks
 * each rule of the format at the place in the text that breaks it, with the checks that the writer
 * uses; what the writer then rejects is a limit of the whole module, reported at .module.
 */
public final class Assembler {
    private final Parser parser;
    private final ModuleDef module;
    private final Token moduleName;
    private final Map<String, ModuleRef> requires = new HashMap<>();
    private final Map<Export, Boolean> exports = new HashMap<>();
    private final Map<String, TypeDef> types = new HashMap<>();
    private final List<Kept> sections = new ArrayList<>();
    private @Nullable TypeName main;

    private Assembler(Parser parser, ModuleDef module, Token moduleName) {
        this.parser = parser;
        this.module = module;
        this.moduleName = moduleName;
    }

    /** The module that UTF-8 .rfla text states, as the writer gives it. */
    public static byte[] assemble(byte[] text) throws AssemblyException {
        var parser = new Parser(Lexer.tokens(text));
        if (!parser.atDirective(".module")) {
            throw parser.unexpected(".module");
        }
        parser.next();
        Token name = parser.qualifiedName("the name of the module");
        int[] version = parser.version();
        var assembler = new Assembler(parser, new ModuleDef(name.text(), version[0], version[1], version[2]), name);
        assembler.identifier(name, name.text());
        assembler.directives();
        assembler.resolveMain();
        return assembler.write();
    }

    // The directives after .module, in any order (§8.1).
    private void directives() throws AssemblyException {
        while (!parser.atKind(Token.END)) {
            if (parser.atDirective(".main")) {
                main();
            } else if (parser.atDirective(".requires")) {
                requires();
            } else if (parser.atDirective(".exports")) {
                exports(Flags.EXPORTS);
            } else if (parser.atDirective(".opens")) {
                exports(Flags.OPENS);
            } else if (parser.atDirective(".unsafe")) {
                unsafe();
            } else if (parser.atDirective(".section")) {
                section();
            } else {
                throw parser.unexpected("a directive of the module");
            }
        }
    }

    // The main class is a type of the module, which may come later in the text.
    private void main() throws AssemblyException {
        Token directive = parser.next();
        if (main != null) {
            throw Parser.error(Diagnostic.REPEATED, directive, "the module names one main class");
        }
        main = parser.typeName("the name of the main class");
    }

    private void resolveMain() throws AssemblyException {
        TypeName name = main;
        if (name == null) {
            return;
        }
        TypeDef type = types.get(name.qualified());
        if (type == null) {
            throw Parser.error(Diagnostic.NO_TYPE, name.token(), "the module defines no type " + name.qualified());
        }
        module.setMain(type);
    }

    private void requires() throws AssemblyException {
        parser.next();
        int flags = 0;
        while (parser.at("transitive") || parser.at("static")) {
            Token word = parser.next();
            int flag = word.text().equals("transitive") ? Flags.REQUIRES_TRANSITIVE : Flags.REQUIRES_STATIC;
            if ((flags & flag) != 0) {
                throw Parser.error(Diagnostic.REPEATED, word, word.text() + " appears twice");
            }
            flags |= flag;
        }
        Token name = parser.qualifiedName("the name of a required module");
        identifier(name, name.text());
        if (requires.containsKey(name.text())) {
            throw Parser.error(Diagnostic.FORMAT, name, "two ModuleRef rows have the name " + name.text() + " (BC-24)");
        }
        var ref = new ModuleRef(name.text(), flags);
        requires.put(name.text(), ref);
        module.requires().add(ref);
    }

    // One Export row per target, or one for every module without targets (§3.1).
    private void exports(int kind) throws AssemblyException {
        parser.next();
        Token namespace = parser.qualifiedName("a namespace");
        identifier(namespace, namespace.text());
        if (!parser.accept("to")) {
            export(new Export(namespace.text(), kind, ""), namespace);
            return;
        }
        do {
            Token target = parser.qualifiedName("the name of a module");
            identifier(target, target.text());
            export(new Export(namespace.text(), kind, target.text()), target);
        } while (parser.accept(","));
    }

    private void export(Export export, Token at) throws AssemblyException {
        if (exports.containsKey(export)) {
            throw Parser.error(Diagnostic.FORMAT, at, "two Export rows have the key of " + ModuleWriter.describe(export)
                    + " (BC-24)");
        }
        exports.put(export, true);
        module.exports().add(export);
    }

    private void unsafe() throws AssemblyException {
        Token directive = parser.next();
        if (module.unsafe()) {
            throw Parser.error(Diagnostic.REPEATED, directive, "the module is unsafe once");
        }
        module.setUnsafe(true);
    }

    // An unknown ancillary section, or a stale DEBUG, kept as bytes (BC-20).
    private void section() throws AssemblyException {
        Token directive = parser.next();
        Token at = parser.peek();
        int id = parser.u32("a section ID");
        byte[] content = parser.hex();
        String name = "section " + ModuleFormatException.hex(id & 0xFFFFFFFFL);
        if (!SectionId.isAncillary(id)) {
            throw Parser.error(Diagnostic.FORMAT, at, name + " is not ancillary; .section keeps an ancillary section (BC-20)");
        }
        if (content.length == 0) {
            throw Parser.error(Diagnostic.FORMAT, at, name + " has no content (BC-24)");
        }
        for (Kept kept : sections) {
            if (kept.section().id() == id) {
                throw Parser.error(Diagnostic.FORMAT, at, "two sections have the ID " + ModuleFormatException.hex(id
                        & 0xFFFFFFFFL) + " (BC-02)");
            }
        }
        sections.add(new Kept(directive, new Section(id, content)));
    }

    // The ancillary sections leave the contentHash as it is (BC-22), so the module written without
    // them tells whether a kept DEBUG is stale (BC-20).
    private byte[] write() throws AssemblyException {
        byte[] file = written();
        if (sections.isEmpty()) {
            return file;
        }
        ModuleFile read;
        try {
            read = ModuleFile.read(file);
        } catch (ModuleFormatException e) {
            throw content(moduleName, e.getMessage());
        }
        for (Kept kept : sections) {
            if (kept.section().id() == SectionId.DEBUG) {
                if (read.find(SectionId.DEBUG) >= 0) {
                    throw Parser.error(Diagnostic.FORMAT, kept.token(), "the lines and local names of the module give"
                            + " its DEBUG, so it keeps no other (BC-02)");
                }
                if (!Debug.stale(kept.section().content(), read.contentHash())) {
                    throw Parser.error(Diagnostic.FORMAT, kept.token(), "the kept DEBUG starts with the contentHash of"
                            + " the module, so it is not stale (BC-20)");
                }
            }
            module.ancillary().add(kept.section());
        }
        return written();
    }

    private byte[] written() throws AssemblyException {
        try {
            return ModuleWriter.write(module);
        } catch (ModuleFormatException e) {
            throw content(moduleName, e.getMessage());
        }
    }

    // An identifier that IDENTS holds, which BC-21 limits.
    private void identifier(Token at, String text) throws AssemblyException {
        int length;
        try {
            length = Utf8.encode(text).length;
        } catch (ModuleFormatException e) {
            throw content(at, e.getMessage());
        }
        if (length > Limits.MAX_IDENTIFIER_BYTES) {
            throw Parser.error(Diagnostic.LIMIT, at, "an identifier of " + length + " UTF-8 bytes exceeds the limit of "
                    + Limits.MAX_IDENTIFIER_BYTES + " (BC-21)");
        }
    }

    // A rule of the format, or a limit of BC-21, which the message names.
    private static AssemblyException content(Token at, String problem) {
        return Parser.error(problem.endsWith("(BC-21)") ? Diagnostic.LIMIT : Diagnostic.FORMAT, at, problem);
    }

    // A section of a .section directive.
    private record Kept(Token token, Section section) {
    }
}
