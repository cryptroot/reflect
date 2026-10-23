package reflect.stage0.vm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import reflect.stage0.bytecode.Assembler;
import reflect.stage0.bytecode.MethodDef;
import reflect.stage0.bytecode.TypeDef;
import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;

// The canonical NaN (NUM-04, MA-01, Q92). Reflect code cannot see the bits of a NaN before boxes arrive in
// v0.0.7, so these tests read the bits of the results that the interpreter returns.
public final class NanTest {
    @Test
    public static void arithmeticGivesTheCanonicalNaN() throws Exception {
        // Java gives 0xFFF8000000000000 for 0.0 / 0.0 at run time on x86-64, and keeps the payload of an input.
        assertEquals("7ff8000000000000", f64("ldc.r8 0\nldc.r8 0\ndiv.f64"));
        assertEquals("7ff8000000000000", f64("ldc.r8 bits 0x7FF8000000000001\nldc.r8 1\nadd.f64"));
        assertEquals("7ff8000000000000", f64("ldc.r8 inf\nldc.r8 inf\nsub.f64"));
        assertEquals("7ff8000000000000", f64("ldc.r8 0\nldc.r8 inf\nmul.f64"));
        assertEquals("7ff8000000000000", f64("ldc.r8 1\nldc.r8 0\nrem.f64"));
        assertEquals("7fc00000", f32("ldc.r4 0\nldc.r4 0\ndiv.f32"));
        assertEquals("7fc00000", f32("ldc.r4 bits 0x7FC00001\nldc.r4 1\nadd.f32"));
        assertEquals("7fc00000", f32("ldc.r4 inf\nldc.r4 inf\nsub.f32"));
        assertEquals("7fc00000", f32("ldc.r4 0\nldc.r4 inf\nmul.f32"));
        assertEquals("7fc00000", f32("ldc.r4 1\nldc.r4 0\nrem.f32"));
        // A result that is no NaN keeps its bits.
        assertEquals("4004000000000000", f64("ldc.r8 1.5\nldc.r8 1\nadd.f64"));
    }

    @Test
    public static void aConversionBetweenFloatFormatsGivesTheCanonicalNaN() throws Exception {
        // Java gives 0x7FF8000020000000 for the float 0x7F800001 as a double, and 0x7FC00001 for that double
        // as a float, since a conversion keeps the high bits of the payload.
        assertEquals("7ff8000000000000", f64("ldc.r4 bits 0x7F800001\nconv.f32.f64"));
        assertEquals("7fc00000", f32("ldc.r8 bits 0x7FF8000020000000\nconv.f64.f32"));
    }

    @Test
    public static void negationChangesOnlyTheSignBit() throws Exception {
        assertEquals("fff8000000000000", f64("ldc.r8 0\nldc.r8 0\ndiv.f64\nneg.f64"));
        assertEquals("fff0000000000001", f64("ldc.r8 bits 0x7FF0000000000001\nneg.f64"));
        assertEquals("ffc00000", f32("ldc.r4 0\nldc.r4 0\ndiv.f32\nneg.f32"));
        assertEquals("ff800001", f32("ldc.r4 bits 0x7F800001\nneg.f32"));
    }

    @Test
    public static void loadsStoresAndCallsKeepEveryBit() throws Exception {
        assertEquals("7ff0000000000001", f64(".locals (double kept)\nldc.r8 bits 0x7FF0000000000001\nstloc 0\nldloc 0\n"
                + "dup\npop"));
        assertEquals("7ff0000000000001", f64("ldc.r8 bits 0x7FF0000000000001\ncall static double m.N::pass(double)"));
        assertEquals("7f800001", f32("ldc.r4 bits 0x7F800001\nstarg 0\nldarg 0", "float spare"));
    }

    // The bits of the double that a static method with this body returns.
    private static String f64(String body) throws Exception {
        return Long.toHexString(Double.doubleToRawLongBits((Double) run("double", body, "")));
    }

    private static String f32(String body) throws Exception {
        return f32(body, "");
    }

    private static String f32(String body, String params) throws Exception {
        return Integer.toHexString(Float.floatToRawIntBits((Float) run("float", body, params)));
    }

    private static Object run(String type, String body, String params) throws Exception {
        String text = ".module m 0.0.0\n.class public m.N {\n    .method public static " + type + " f(" + params + ") {\n"
                + body + "\nret\n    }\n    .method public static double pass(double value) {\n        ldarg 0\n"
                + "        ret\n    }\n}\n";
        Modules modules = Modules.load(new ModuleData("m.rflc", Assembler.assemble(text.getBytes(StandardCharsets.UTF_8))),
                name -> null);
        var vm = new Vm(new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        RuntimeModule module = modules.main();
        TypeDef owner = module.type("m.N");
        for (MethodDef method : owner.methods()) {
            if (method.name().equals("f")) {
                Object[] arguments = params.isEmpty() ? new Object[0] : new Object[] {0.0f};
                return Interpreter.invoke(vm, vm.type(module, owner).method(method), arguments);
            }
        }
        throw new AssertionError("no method f");
    }
}
