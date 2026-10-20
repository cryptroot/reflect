package reflect.stage0.bytecode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;

/** The document test of README §7.2: docs/bytecode.md states the numbers that the code declares. */
public final class DocumentTest {
    private static final Path BYTECODE = Path.of("docs", "bytecode.md");
    private static final Pattern OPCODE_RANGE = Pattern.compile(
            "`([0-9A-F]{2}(?: [0-9A-F]{2})?)`(?:-`([0-9A-F]{2}(?: [0-9A-F]{2})?)`)?");
    private static final Pattern TABLE_NAME = Pattern.compile("`([A-Za-z]+)`");

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
                throw new AssertionError(BYTECODE + " section 5.1 does not list " + Opcode.mnemonic(code));
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

    @Test
    public static void signatureCodes() throws IOException {
        var listed = new HashSet<String>();
        checkCodes("| Code | Name | Meaning | Operands |", SigCode.class, "", listed);
        checkCodes("| BYREF kind | Name | Meaning |", SigCode.class, "BYREF_", listed);
        checkCodes("| WILDCARD kind | Name | Meaning |", SigCode.class, "WILDCARD_", listed);
        checkCodes("| METHOD flag | Name | Meaning |", SigCode.class, "METHOD_", listed);
        checkAllListed(SigCode.class, listed);
    }

    @Test
    public static void tableIds() throws IOException {
        var listed = new HashSet<String>();
        var values = new HashSet<Integer>();
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE, "| ID | Table | Columns |")) {
            Matcher table = TABLE_NAME.matcher(row.cell(1));
            row.check(table.lookingAt(), "no table name: " + row.cell(1));
            checkCode(row, row.code(0), TableId.class, constantName(table.group(1)), listed, values);
        }
        checkAllListed(TableId.class, listed);
    }

    @Test
    public static void tableLayouts() throws IOException {
        var firstRows = new LinkedHashMap<String, MarkdownTable.Row>();
        var counts = new HashMap<String, Integer>();
        String previous = "";
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE, "| Table | Column | Content |")) {
            String table = row.code(0);
            row.check(table.equals(previous) || !counts.containsKey(table), "the rows of " + table + " are apart");
            firstRows.putIfAbsent(table, row);
            counts.merge(table, 1, Integer::sum);
            previous = table;
        }
        var documented = new HashSet<Integer>();
        int previousId = 0;
        for (MarkdownTable.Row row : firstRows.values()) {
            String table = row.code(0);
            int id = constant(row, TableId.class, constantName(table));
            row.check(id > previousId, "the layouts are not in ID order");
            row.expect("columns of " + table, counts.get(table), TableId.columns(id));
            row.expect("row size of " + table, 4 * counts.get(table), TableId.rowSize(id));
            documented.add(id);
            previousId = id;
        }
        for (Field field : TableId.class.getDeclaredFields()) {
            if (isIntConstant(field) && TableId.columns(getInt(field)) != 0 && !documented.contains(getInt(field))) {
                throw new AssertionError(BYTECODE + " section 3.1 has no layout for " + field.getName());
            }
        }
    }

    @Test
    public static void columnFlagsAndKinds() throws IOException {
        Map<String, String> prefixes = Map.of("visibility", "", "`TypeDef` kind", "KIND_",
                "`TypeDef` flags", "TYPE_", "`Field` flags", "FIELD_", "`Method` flags", "METHOD_",
                "`Param` flags", "PARAM_", "`ModuleRef` flags", "REQUIRES_", "`Export` kind", "",
                "`GenericParam` kind", "GENERIC_");
        Set<String> withVisibility = Set.of("`TypeDef` flags", "`Field` flags", "`Method` flags");
        int visibility = Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE;
        var listed = new HashSet<String>();
        var values = new HashMap<String, Set<Integer>>();
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE, "| Column | Value | Name | Meaning |")) {
            String column = row.cell(0);
            String prefix = prefixes.get(column);
            row.check(prefix != null, "unknown column: " + column);
            int value = checkCode(row, row.code(1), Flags.class, prefix + row.code(2), listed,
                    values.computeIfAbsent(column, key -> new HashSet<>()));
            if (column.endsWith("flags") || column.equals("visibility")) {
                row.check(Integer.bitCount(value) == 1, "a flag is one bit");
            }
            if (withVisibility.contains(column)) {
                row.check((value & visibility) == 0, "the flag overlaps a visibility flag");
            }
        }
        checkAllListed(Flags.class, listed);
    }

    @Test
    public static void headerLayout() throws IOException {
        var listed = new HashSet<String>();
        int end = 0;
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE, "| Offset | Size | Header field | Content |")) {
            String field = row.code(2);
            String name = constantName(field) + "_OFFSET";
            int offset = decimal(row, row.code(0));
            row.check(offset == end, field + " does not start where the field before it ends");
            row.expect("Header." + name, offset, constant(row, Header.class, name));
            listed.add(name);
            end = offset + decimal(row, row.code(1));
            if (field.equals("magic")) {
                row.expect("Header.MAGIC", littleEndian(row.code(3)), Header.MAGIC);
                listed.add("MAGIC");
            }
        }
        expect("Header.SIZE", end, Header.SIZE);
        MatchResult version = prose("This document describes format (\\d+)\\.(\\d+)");
        expect("Header.FORMAT_MAJOR", Integer.parseInt(version.group(1)), Header.FORMAT_MAJOR);
        expect("Header.FORMAT_MINOR", Integer.parseInt(version.group(2)), Header.FORMAT_MINOR);
        MatchResult entry = prose("sectionCount entries of (\\d+) bytes");
        expect("Header.DIRECTORY_ENTRY_SIZE", Integer.parseInt(entry.group(1)), Header.DIRECTORY_ENTRY_SIZE);
        MatchResult alignment = prose("next multiple of (\\d+) bytes after the directory");
        expect("Header.ALIGNMENT", Integer.parseInt(alignment.group(1)), Header.ALIGNMENT);
        listed.addAll(List.of("SIZE", "FORMAT_MAJOR", "FORMAT_MINOR", "DIRECTORY_ENTRY_SIZE", "ALIGNMENT"));
        checkCodes("| Header flag | Name | Meaning |", Header.class, "FLAG_", listed);
        checkAllListed(Header.class, listed);
    }

    @Test
    public static void sectionIds() throws IOException {
        var listed = new HashSet<String>();
        var values = new HashSet<Integer>();
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE, "| ID | Section | Content |")) {
            int id = checkCode(row, row.code(0), SectionId.class, row.code(1), listed, values);
            row.expect("ancillary mark of " + row.code(1), row.cell(2).startsWith("ancillary"),
                    SectionId.isAncillary(id));
        }
        MatchResult bit = prose("Bit (\\d+) of an ID marks an ancillary section");
        expect("SectionId.ANCILLARY", 1 << Integer.parseInt(bit.group(1)), SectionId.ANCILLARY);
        listed.add("ANCILLARY");
        checkAllListed(SectionId.class, listed);
    }

    // Finds the only match of a pattern in the text of the document, with line breaks as spaces.
    private static MatchResult prose(String regex) throws IOException {
        String text = String.join(" ", Files.readAllLines(BYTECODE, StandardCharsets.UTF_8)).replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            throw new AssertionError(BYTECODE + ": no text matches " + regex);
        }
        MatchResult result = matcher.toMatchResult();
        if (matcher.find()) {
            throw new AssertionError(BYTECODE + ": two places match " + regex);
        }
        return result;
    }

    private static void expect(String what, Object document, Object code) {
        if (!document.equals(code)) {
            throw new AssertionError(BYTECODE + ": " + what + ": the document says " + document + ", the code says " + code);
        }
    }

    private static int decimal(MarkdownTable.Row row, String text) {
        row.check(text.matches("[0-9]+"), "not a decimal number: " + text);
        return Integer.parseInt(text);
    }

    private static int littleEndian(String ascii) {
        int value = 0;
        for (int i = ascii.length() - 1; i >= 0; i--) {
            value = (value << 8) | ascii.charAt(i);
        }
        return value;
    }

    // The first two cells of each row are a hexadecimal value and the name of a constant after prefix.
    private static void checkCodes(String header, Class<?> type, String prefix, Set<String> listed)
            throws IOException {
        var values = new HashSet<Integer>();
        for (MarkdownTable.Row row : MarkdownTable.read(BYTECODE, header)) {
            checkCode(row, row.code(0), type, prefix + row.code(1), listed, values);
        }
    }

    private static int checkCode(MarkdownTable.Row row, String hex, Class<?> type, String name, Set<String> listed,
            Set<Integer> values) {
        row.check(hex.matches("[0-9A-F]+"), "not a hexadecimal value: " + hex);
        int value = Integer.parseUnsignedInt(hex, 16);
        row.check(listed.add(name), "lists " + name + " twice");
        row.check(values.add(value), "repeats the value " + hex);
        row.expect(type.getSimpleName() + "." + name, value, constant(row, type, name));
        return value;
    }

    // ModuleRef becomes MODULE_REF.
    private static String constantName(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static int getInt(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Integer constant(MarkdownTable.Row row, Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            row.check(isIntConstant(field), type.getSimpleName() + "." + name + " is not an int constant");
            return field.getInt(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            row.check(false, type.getSimpleName() + " has no constant " + name);
            return null;
        }
    }

    private static void checkAllListed(Class<?> type, Set<String> listed) {
        for (Field field : type.getDeclaredFields()) {
            if (isIntConstant(field) && !listed.contains(field.getName())) {
                throw new AssertionError(type.getSimpleName() + "." + field.getName() + " is missing from " + BYTECODE);
            }
        }
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
