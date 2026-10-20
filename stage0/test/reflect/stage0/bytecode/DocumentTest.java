package reflect.stage0.bytecode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;

/** The document test of README §7.2: docs/bytecode.md states the numbers that the code declares. */
public final class DocumentTest {
    private static final Path BYTECODE = Path.of("docs", "bytecode.md");
    private static final Pattern OPCODE_RANGE = Pattern.compile(
            "`([0-9A-F]{2}(?: [0-9A-F]{2})?)`(?:-`([0-9A-F]{2}(?: [0-9A-F]{2})?)`)?");

    @Test
    public static void opcodeTable() throws IOException {
        boolean[] listed = new boolean[0x10000];
        int previous = -1;
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE, "| Opcode | Instruction | Operands | R0 |")) {
            int code = opcode(row, row.code(0));
            row.check(code > previous, "opcodes are not in increasing order");
            row.check(Opcode.isDefined(code), "Opcode does not define " + row.cell(0));
            row.expect("instruction", row.code(1), Opcode.mnemonic(code));
            row.expect("operands", operandKind(row, row.cell(2)), Opcode.operands(code));
            row.check(row.cell(3).isEmpty() || row.cell(3).equals("R0"), "the R0 column holds R0 or nothing");
            row.expect("R0 mark", row.cell(3).equals("R0"), Opcode.isR0(code));
            listed[code] = true;
            previous = code;
        }
        for (int code : possibleOpcodes()) {
            if (Opcode.isDefined(code) && !listed[code]) {
                throw new AssertionError(BYTECODE + " §5.1 does not list " + Opcode.mnemonic(code));
            }
        }
    }

    @Test
    public static void opcodesLieInTheirFamilyRanges() throws IOException {
        var ranges = new ArrayList<int[]>();
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE,
                "| Family | Opcodes | Instructions and stack effect |")) {
            Matcher range = OPCODE_RANGE.matcher(row.cell(1));
            row.check(range.matches(), "not an opcode range: " + row.cell(1));
            int first = opcode(row, range.group(1));
            int last = range.group(2) == null ? first : opcode(row, range.group(2));
            row.check(first <= last, "the range ends before it starts");
            for (int[] other : ranges) {
                row.check(last < other[0] || first > other[1], "the range overlaps another family");
            }
            ranges.add(new int[] {first, last});
        }
        for (int code : possibleOpcodes()) {
            if (Opcode.isDefined(code) && !inside(ranges, code)) {
                throw new AssertionError(Opcode.mnemonic(code) + " lies outside every family range of BC-04");
            }
        }
    }

    @Test
    public static void opcodeConstantsAreNamedAfterTheirInstructions() throws IllegalAccessException {
        int constants = 0;
        for (Field field : Opcode.class.getDeclaredFields()) {
            String name = field.getName();
            if (!isIntConstant(field) || name.startsWith("OPERAND_") || name.equals("PREFIX")) {
                continue;
            }
            int code = field.getInt(null);
            if (!Opcode.isDefined(code)) {
                throw new AssertionError("Opcode." + name + " is not a defined opcode");
            }
            assertEquals(Opcode.mnemonic(code).toUpperCase(Locale.ROOT).replace('.', '_'), name);
            constants++;
        }
        int defined = 0;
        for (int code : possibleOpcodes()) {
            if (Opcode.isDefined(code)) {
                defined++;
            }
        }
        assertEquals(defined, constants);
    }

    private static int opcode(MarkdownTable.Row row, String text) {
        row.check(text.matches("[0-9A-F]{2}|FE [0-9A-F]{2}"), "not an opcode: " + text);
        return Integer.parseInt(text.replace(" ", ""), 16);
    }

    private static int operandKind(MarkdownTable.Row row, String cell) {
        return switch (cell) {
            case "" -> Opcode.OPERAND_NONE;
            case "`<i4>`" -> Opcode.OPERAND_I4;
            case "`<i8>`" -> Opcode.OPERAND_I8;
            case "`<r4>`" -> Opcode.OPERAND_R4;
            case "`<r8>`" -> Opcode.OPERAND_R8;
            case "`<str>`" -> Opcode.OPERAND_STR;
            case "`<tok>`" -> Opcode.OPERAND_TOK;
            case "`<n>`" -> Opcode.OPERAND_N;
            case "`<off>`" -> Opcode.OPERAND_OFF;
            case "`<count> <off>...`" -> Opcode.OPERAND_SWITCH;
            case "`<tok> <tok>`" -> Opcode.OPERAND_TOK_TOK;
            case "`<n> <n>`" -> Opcode.OPERAND_N_N;
            default -> {
                row.check(false, "unknown operands: " + cell);
                yield -1;
            }
        };
    }

    private static int[] possibleOpcodes() {
        int[] codes = new int[0x200];
        for (int i = 0; i < 0x100; i++) {
            codes[i] = i;
            codes[0x100 + i] = (Opcode.PREFIX << 8) | i;
        }
        return codes;
    }

    private static boolean inside(List<int[]> ranges, int code) {
        for (int[] range : ranges) {
            if (code >= range[0] && code <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIntConstant(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                && field.getType() == int.class;
    }
}
