package reflect.stage0.bytecode;

import java.util.Arrays;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.sectionOffset;
import static reflect.stage0.bytecode.TestModules.withSection;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class CanonicalTest {
    private static final String NOT_CANONICAL = "the canonical encoding of the module differs at this byte (BC-24)";

    // static void a.A::f() of CodeTest.bodyFile: L0: nop; br L2; 59 nops; br L0; L2: ret. Each branch
    // fits one byte of offset, 61 to ret and -64 to the nop. The second branch also fits two bytes,
    // with -65, and the first then still takes one, 62: every value is minimal in both layouts, so only
    // the rewrite finds that the second one is not the smallest (BC-24).
    @Test
    public static void aBranchLayoutThatIsNotTheSmallestIsRejected() throws Exception {
        byte[] smallest = CodeTest.bodyFile(new int[0], body(0x3D, 0x40));
        // The reader accepts the smallest layout, which is the one that the writer gives.
        assertTrue(Arrays.equals(smallest, ModuleWriter.write(ModuleReader.read(smallest))));
        byte[] larger = CodeTest.bodyFile(new int[0], body(0x3E, 0xBF, 0x7F));
        // The bodies differ first at codeSize, the third byte of CODE: 66 bytes instead of 65.
        rejected(larger, sectionOffset(larger, SectionId.CODE) + 2, NOT_CANONICAL);
    }

    @Test
    public static void theRewriteLeavesOutTheSectionsKeptAsBytes() throws Exception {
        // In the larger layout, a DEBUG that starts with the contentHash of the smallest one is stale, so
        // the reader keeps it as bytes; the writer would not keep it, since it is current in the
        // canonical encoding. The reader still names the first byte that differs.
        byte[] smallest = CodeTest.bodyFile(new int[0], body(0x3D, 0x40));
        var debug = new ByteSink();
        debug.bytes(ModuleFile.read(smallest).contentHash());
        debug.u8(0);
        byte[] larger = withSection(CodeTest.bodyFile(new int[0], body(0x3E, 0xBF, 0x7F)),
                new Section(SectionId.DEBUG, debug.toByteArray()));
        rejected(larger, sectionOffset(larger, SectionId.CODE) + 2, NOT_CANONICAL);
    }

    @Test
    public static void aSectionThatStartsItsCanonicalBytesDiffersAtItsEnd() {
        assertEquals(-1, Blob.firstDifference(new byte[] {1, 2}, new byte[] {1, 2}));
        assertEquals(1, Blob.firstDifference(new byte[] {1, 2}, new byte[] {1, 3}));
        assertEquals(1, Blob.firstDifference(new byte[] {1}, new byte[] {1, 0}));
        assertEquals(1, Blob.firstDifference(new byte[] {1, 0}, new byte[] {1}));
    }

    // The body of f, with these bytes as the offsets of the first and the second branch.
    private static int[] body(int first, int... second) {
        int size = 1 + 2 + 59 + 1 + second.length + 1;
        int[] body = new int[3 + size + 2];
        body[2] = size;
        int at = 3;
        body[at++] = Opcode.NOP;
        body[at++] = Opcode.BR;
        body[at++] = first;
        at += 59;
        body[at++] = Opcode.BR;
        for (int b : second) {
            body[at++] = b;
        }
        body[at] = Opcode.RET;
        return body;
    }
}
