package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.assemble;
import static reflect.stage0.bytecode.TestModules.unassemblable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class AssembleStackTest {
    private static final String B = ".module m 0.0.0\n.class a.A {\n";
    // Three more values reveal the depth that an instruction leaves.
    private static final String PUSHES = "ldc.i4 0\nldc.i4 0\nldc.i4 0\n";

    @Test
    public static void theDepthFollowsEveryPathThroughTheCode() throws Exception {
        String text = ".module m 0.0.0\n.requires other\n.class a.A {\n"
                + method("void new(int x)", "ret")
                + method("int args(int x, int y)", "ldarg 0\nldarg x\nldarg y\ncall int a.A::args(int, int)\n" + PUSHES + "ret")
                + method("static void recv(int x)", "ldtype a.A\nldc.i4 1\ncall.recv a.A::recv(int)\n" + PUSHES + "ret")
                + method("static void svirt()", "ldtype a.A\nldc.i4 1\ncall.svirt a.A::recv(int)\n" + PUSHES + "ret")
                + method("static void params()", "ldc.i4 1\nnewobj a.A::new(int)\n" + PUSHES + "ret")
                + method("static void classParams()", "ldtype a.A\nldc.i4 1\nnewobj.svirt a.A::new(int)\n" + PUSHES + "ret")
                + method("static void result()", "call static void other.B::v()\n" + PUSHES + "ret")
                + method("static void count()", "ldc.i4 1\nldc.i4 2\nldc.i4 3\nsyscall 0 3\n" + PUSHES + "ret")
                + method("static void all()", "ldc.i4 1\nldc.i4 2\nleave L\nldc.i4 0\nL: " + PUSHES + "ret")
                + method("static int merge(int x)", "ldarg x\nbrtrue ONE\nldc.i4 0\nbr DONE\nONE: ldc.i4 1\nDONE: ret")
                + method("static void loop()", "TOP: ldc.i4 1\npop\nbr TOP")
                + method("static void choice(int x)", "ldarg x\nswitch (A)\nret\nA: ldc.i4 0\nldc.i4 0\npop\npop\nret")
                + method("static void afterBr()", "br L\n" + PUSHES + "L: ret")
                + method("static void afterRet()", "ret\n" + PUSHES)
                + method("static void afterThrow()", "ldnull\nthrow\n" + PUSHES)
                + method("static void afterRethrow()", "rethrow\n" + PUSHES)
                + method("static void afterEndfinally()", "endfinally\n" + PUSHES)
                + method("static void none()", "")
                + "}\n";
        var depths = new StringBuilder();
        for (MethodDef method : ModuleReader.read(assemble(text)).types().get(0).methods()) {
            MethodBody body = method.body();
            depths.append(method.name()).append(' ').append(body == null ? -1 : body.maxStack()).append("; ");
        }
        assertEquals("new 0; args 4; recv 3; svirt 3; params 4; classParams 4; result 3; count 4; all 3; merge 1; loop 1; "
                + "choice 2; afterBr 0; afterRet 0; afterThrow 1; afterRethrow 0; afterEndfinally 0; none 0; ", depths.toString());
    }

    @Test
    public static void aDepthThatCannotBeComputedHasItsDiagnostic() {
        unassemblable(B + ".method static void m() {\npop\n}\n}", "4:1: RFA0304: pop needs a stack depth of 1, but it is 0 (Q77)");
        unassemblable(B + ".method static int m() {\nret\n}\n}", "4:1: RFA0304: ret needs a stack depth of 1, but it is 0 (Q77)");
        unassemblable(B + ".method int f(int x) {\nldarg x\ncall int a.A::f(int)\nret\n}\n}",
                "5:1: RFA0304: call needs a stack depth of 2, but it is 1 (Q77)");
        unassemblable(B + ".method static void m() {\nsyscall 0 4294967295\n}\n}",
                "4:1: RFA0304: syscall needs a stack depth of 4294967295, but it is 0 (Q77)");
        unassemblable(B + ".method static void m(int x) {\nldarg x\nbrtrue L\nldc.i4 1\nL: ret\n}\n}",
                "7:4: RFA0304: two paths reach ret with stack depths 0 and 1 (Q77)");
    }

    @Test
    public static void theDepthHasTheLimitOfBc21() throws Exception {
        String most = "ldc.i4 0\n".repeat(Limits.MAX_STACK);
        ModuleReader.read(assemble(B + ".method static void m() {\n" + most + "ret\n}\n}"));
        unassemblable(B + ".method static void m() {\n" + most + "ldc.i4 0\nret\n}\n}",
                (4 + Limits.MAX_STACK) + ":1: RFA0302: the maximum stack depth of 65536 exceeds the limit of 65535 (BC-21)");
    }

    private static String method(String head, String code) {
        return ".method " + head + " {\n" + code + "\n}\n";
    }
}
