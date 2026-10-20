package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class OpcodeTest {
    @Test
    public static void prefixFreePlacesAndOutOfRangeCodesAreUndefined() {
        int[] undefined = {Opcode.PREFIX, 0x0B, 0xF4, 0xFE10, 0xFEFF, -1, 0x100, 0xFF00, 0x1FE00};
        for (int code : undefined) {
            assertTrue(!Opcode.isDefined(code));
            assertThrows(IllegalArgumentException.class, () -> Opcode.mnemonic(code));
        }
        assertTrue(Opcode.isDefined(Opcode.BREAKPOINT) && Opcode.isDefined(Opcode.ADD_OVF_I32));
    }
}
