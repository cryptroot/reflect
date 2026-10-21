package reflect.stage0.bytecode;

import reflect.stage0.lib.List;
import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.CodeTest.body;
import static reflect.stage0.bytecode.CodeTest.method;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;

public final class FrameTest {
    private static final TypeSig INT = new TypeSig.Primitive(SigCode.I32);
    private static final Frame.VType I32 = new Frame.VType(VerificationType.I32, null);
    // The reader tests: nop; br +0, which targets ret at code offset 3; ret; and no regions.
    private static final int[] CODE = {0x01, 0x00, 0x04, 0x00, 0x88, 0x00, 0xB0, 0x00};

    @Test
    public static void framesAreSortedByOffset() throws Exception {
        Fixture fixture = new Fixture();
        // At L4, the target of leave: i32, null, and an i32 on the stack. At L2, the handler start:
        // i32 and a reflect.core.String, the type of local 0.
        fixture.body.frames().add(new Frame(fixture.labels[4], list(I32, new Frame.VType(VerificationType.NULL, null)),
                list(I32)));
        fixture.body.frames().add(new Frame(fixture.labels[2], list(I32, new Frame.VType(VerificationType.REF,
                fixture.string)), list()));
        byte[] file = roundTrip(fixture.module);
        // The FINALLY region, then two frames: at 3, i32 and REF String, no stack; at 5, i32 and null, stack i32.
        assertEquals("01 01 00 03 03 05 00 02 03 02 01 06 81 80 80 30 00 05 02 01 05 01 01", RegionTest.regions(file));
        MethodBody read = ModuleReader.read(file).types().get(0).methods().get(0).body();
        assertEquals("reflect.core.String", read.frames().get(0).slots().get(1).type().qualifiedName());
        assertEquals(true, read.frames().get(1).at() == ((Instruction.Branch) read.code().get(1)).target());
    }

    @Test
    public static void theWriterChecksFrames() {
        frame(new Label(), list(I32, I32), list(), "frame 0: the stack map frame is at no instruction of the body (BC-19)");
        frame(0, list(I32, I32), list(), "frame 0: the stack map frame is at instruction 0, which is neither a branch target"
                + " nor a handler start (BC-19)");
        frame(4, list(I32), list(), "frame 0: the stack map frame has 1 slots, but the method has 1 arguments and 1 locals"
                + " (BC-19)");
        frame(4, list(I32, I32), list(I32, I32), "frame 0: the stack map frame has a stack of 2, deeper than the maximum"
                + " of 1 (BC-19)");
        frame(4, list(I32, new Frame.VType(0x0D, null)), list(), "frame 0: verification type 0xD is not defined (BC-20)");
        frame(4, list(I32, new Frame.VType(VerificationType.REF, null)), list(), "frame 0: verification type 0x6 has no"
                + " type, but its tag takes one (BC-19)");
        var fixture = new Fixture();
        frame(4, list(I32, new Frame.VType(VerificationType.I32, fixture.string)), list(),
                "frame 0: verification type 0x1 takes no type (BC-19)");
        fixture.body.frames().add(new Frame(fixture.labels[4], list(I32, I32), list()));
        fixture.body.frames().add(new Frame(fixture.labels[4], list(I32, I32), list(I32)));
        unwritable(fixture.module, "method a.A::m has two stack map frames at code offset 5 (BC-19)");
    }

    @Test
    public static void theReaderChecksFramesAtTheirOffsets() throws Exception {
        int[] none = {};
        ModuleReader.read(CodeTest.bodyFile(none, frames(0x01, 0x03, 0x00, 0x00)));
        body(9, "the stack map frame is at instruction 0, which is neither a branch target nor a handler start (BC-19)",
                none, frames(0x01, 0x00, 0x00, 0x00));
        body(9, "the stack map frame is at code offset 2, where no instruction starts (BC-19)", none,
                frames(0x01, 0x02, 0x00, 0x00));
        body(12, "two stack map frames have code offset 3 (BC-19)", none, frames(0x02, 0x03, 0x00, 0x00, 0x03, 0x00, 0x00));
        body(12, "the stack map frames are not sorted by code offset (BC-19)", none,
                frames(0x02, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00));
        body(9, "the stack map frame has 1 slots, but the method has 0 arguments and 0 locals (BC-19)", none,
                frames(0x01, 0x03, 0x01, 0x01, 0x00));
        body(9, "the stack map frame has a stack of 2, deeper than the maximum of 1 (BC-19)", none,
                frames(0x01, 0x03, 0x00, 0x02, 0x01, 0x01));
        body(12, "verification type 0xD is not defined (BC-20)", none, frames(0x01, 0x03, 0x00, 0x01, 0x0D));
        body(13, "token 0x9000001 does not name a TypeDef, TypeRef or TypeSpec row (BC-19)", none,
                frames(0x01, 0x03, 0x00, 0x01, 0x06, 0x81, 0x80, 0x80, 0x48));
        body(8, "a list of 5 stack map frames runs past the end of CODE", none, frames(0x05));
        body(10, "a list of 5 verification types runs past the end of CODE", none, frames(0x01, 0x03, 0x05, 0x00, 0x00));
    }

    // static void a.A::m(int), with the local reflect.core.String: L0: nop; leave L4; L2: nop;
    // endfinally; L4: ret, and a FINALLY region with try [L0, L2) and handler [L2, L4).
    private static final class Fixture {
        final MethodBody body = new MethodBody(1);
        final Label[] labels = new Label[5];
        final ModuleDef module;
        final TypeRef string;

        Fixture() {
            for (int i = 0; i < labels.length; i++) {
                labels[i] = new Label();
            }
            Instruction[] code = {new Instruction.Plain(Opcode.NOP), new Instruction.Branch(Opcode.LEAVE, labels[4]),
                new Instruction.Plain(Opcode.NOP), new Instruction.Plain(Opcode.ENDFINALLY), new Instruction.Plain(Opcode.RET)};
            for (int i = 0; i < code.length; i++) {
                body.mark(labels[i]);
                body.code().add(code[i]);
            }
            body.regions().add(new Region(Flags.REGION_FINALLY, labels[0], labels[2], labels[2], labels[4], null));
            module = method(Flags.METHOD_STATIC, new MethodSig(SigCode.METHOD_STATIC, 0, new TypeSig.Primitive(SigCode.VOID),
                    list(INT)), body);
            var core = new ModuleRef("reflect.core", 0);
            module.requires().add(core);
            string = new TypeRef(core, "reflect.core", "String");
            module.typeRefs().add(string);
            body.locals().add(new TypeSig.Named(false, string));
        }
    }

    // Expects the writer to reject the fixture with one frame at the label of this instruction.
    private static void frame(int instruction, List<Frame.VType> slots, List<Frame.VType> stack, String problem) {
        var fixture = new Fixture();
        fixture.body.frames().add(new Frame(fixture.labels[instruction], slots, stack));
        unwritable(fixture.module, "method a.A::m: " + problem);
    }

    private static void frame(Label at, List<Frame.VType> slots, List<Frame.VType> stack, String problem) {
        var fixture = new Fixture();
        fixture.body.frames().add(new Frame(at, slots, stack));
        unwritable(fixture.module, "method a.A::m: " + problem);
    }

    private static int[] frames(int... frames) {
        int[] body = new int[CODE.length + frames.length];
        System.arraycopy(CODE, 0, body, 0, CODE.length);
        System.arraycopy(frames, 0, body, CODE.length, frames.length);
        return body;
    }
}
