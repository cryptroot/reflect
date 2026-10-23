package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.test.Assertions.assertEquals;

public final class DisassemblerTest {
    @Test
    public static void moduleDirectivesFollowTheOrderOfTheBinary() throws Exception {
        String text = ".module demo 0.1.2\n"
                + ".requires transitive static reflect.io\n"
                + ".requires reflect.core\n"
                + ".requires @static\n"
                + ".exports demo.zoo to demo.tests, demo.app\n"
                + ".opens demo.zoo\n"
                + ".exports demo\n"
                + ".unsafe\n"
                + ".section 0x80000007 hex \"01\"\n"
                + ".section 0x80000002 hex \"00fF\"\n"
                + ".main demo.zoo.Zoo\n"
                + ".class public final demo.zoo.Zoo {\n"
                + "}\n";
        // The Module row, the header flag, the ModuleRef and Export rows in row order, then the sections by ID.
        assertEquals(".module demo 0.1.2\n"
                + ".unsafe\n"
                + ".main demo.zoo.Zoo\n"
                + ".requires reflect.core\n"
                + ".requires transitive static reflect.io\n"
                + ".requires @static\n"
                + ".exports demo\n"
                + ".exports demo.zoo to demo.app\n"
                + ".exports demo.zoo to demo.tests\n"
                + ".opens demo.zoo\n"
                + "\n"
                + ".class public final demo.zoo.Zoo {\n"
                + "}\n"
                + "\n"
                + ".section 0x80000002 hex \"00FF\"\n"
                + ".section 0x80000007 hex \"01\"\n", roundTrip(text));
        assertEquals(".module m 4294967295.0.1\n", roundTrip(".module m 4294967295.0.1"));
    }

    @Test
    public static void typeNamesTakeBracketsWhereTheNameAloneMeansAnotherType() throws Exception {
        String text = ".module demo 1.0.0\n"
                + ".requires reflect.core\n"
                + ".requires other\n"
                + ".class public abstract demo.zoo.Animal extends reflect.core.Object implements reflect.core.Comparable,"
                + " demo.zoo.Named {\n"
                + "    .class public enum Kind : ubyte {\n"
                + "    }\n"
                + "    .class private ref struct @record {\n"
                + "    }\n"
                + "}\n"
                + ".class public interface demo.zoo.Named {\n"
                + "}\n"
                + ".class protected sealed unsealed final record struct demo.Pair {\n"
                + "}\n"
                + ".class annotation demo.Tag {\n"
                + "}\n"
                + ".class @int extends [other]Global {\n"
                + "}\n"
                + ".class @final {\n"
                + "}\n"
                + ".class reflect.core.Thing extends [reflect.core]reflect.core.Thing {\n"
                + "}\n"
                + ".class public struct reflect.core.Int32 {\n"
                + "}\n"
                + ".class demo.Box extends [reflect.core]reflect.core.Int32 {\n"
                + "}\n"
                + ".class demo.User extends [demo]reflect.core.Int32 {\n"
                + "}\n"
                + ".class demo.Nested extends [other]x.Y/Z {\n"
                + "}\n"
                + ".typeref [other]a.B/C\n"
                + ".typeref reflect.core.String\n";
        // Global and nested types sort first, by name; a TypeDef token comes before a TypeRef token. Brackets
        // stand where the prefix rule gives another module or none, where the module defines a type of the name,
        // and before the struct of a primitive, with the module's own name for its own struct (Q76, Q81). Without
        // its @, the global type final would read as a flag (Q82).
        assertEquals(".module demo 1.0.0\n"
                + ".requires other\n"
                + ".requires reflect.core\n"
                + "\n"
                + ".class @final {\n"
                + "}\n"
                + "\n"
                + ".class @int extends [other]Global {\n"
                + "}\n"
                + "\n"
                + ".class demo.Box extends [reflect.core]reflect.core.Int32 {\n"
                + "}\n"
                + "\n"
                + ".class demo.Nested extends [other]x.Y/Z {\n"
                + "}\n"
                + "\n"
                + ".class protected final sealed unsealed record struct demo.Pair {\n"
                + "}\n"
                + "\n"
                + ".class annotation demo.Tag {\n"
                + "}\n"
                + "\n"
                + ".class demo.User extends [demo]reflect.core.Int32 {\n"
                + "}\n"
                + "\n"
                + ".class public abstract demo.zoo.Animal extends reflect.core.Object implements demo.zoo.Named,"
                + " reflect.core.Comparable {\n"
                + "    .class public enum Kind : reflect.core.UInt8 {\n"
                + "    }\n"
                + "    .class private ref struct @record {\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + ".class public interface demo.zoo.Named {\n"
                + "}\n"
                + "\n"
                + ".class public struct reflect.core.Int32 {\n"
                + "}\n"
                + "\n"
                + ".class reflect.core.Thing extends [reflect.core]reflect.core.Thing {\n"
                + "}\n"
                + "\n"
                + ".typeref reflect.core.String\n"
                + ".typeref [other]a.B/C\n", roundTrip(text));
        // Without a type of its name in the module, the struct of a primitive still takes its brackets.
        String struct = ".module m 0.0.0\n.requires reflect.core\n\n.class a.B extends [reflect.core]reflect.core.Int32 {\n}\n";
        assertEquals(struct, roundTrip(struct));
    }

    // The canonical text of the module that the text gives, which assembles to the same bytes (BC-08).
    static String roundTrip(String text) throws Exception {
        byte[] module = assemble(text);
        String canonical = Disassembler.text(ModuleReader.read(module));
        assertEquals(Leb128Test.hex(module), Leb128Test.hex(assemble(canonical)));
        return canonical;
    }
}
