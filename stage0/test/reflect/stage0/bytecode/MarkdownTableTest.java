package reflect.stage0.bytecode;

import java.nio.file.Path;
import java.util.List;

import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class MarkdownTableTest {
    private static final Path FILE = Path.of("fixture.md");

    @Test
    public static void readsAnIndentedTableUnderItsHeader() {
        List<MarkdownTable.Row> rows = MarkdownTable.read(FILE, List.of(
                "Text before.",
                "  | Name | Value |",
                "  |---|---|",
                "  | `a` | x \\| y |",
                "  | | 2 |",
                "",
                "| Name | Other |"), "| Name | Value |");
        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).code(0));
        assertEquals("x | y", rows.get(0).cell(1));
        assertEquals("", rows.get(1).cell(0));
        assertEquals(5, rows.get(1).line());
    }

    @Test
    public static void rejectsMissingRepeatedAndMalformedTables() {
        assertThrows(AssertionError.class, () -> MarkdownTable.read(FILE, List.of("| A |", "|---|"), "| B |"));
        assertThrows(AssertionError.class, () -> MarkdownTable.read(FILE,
                List.of("| A |", "|---|", "| A |", "|---|"), "| A |"));
        assertThrows(AssertionError.class, () -> MarkdownTable.read(FILE, List.of("| A |", "| 1 |"), "| A |"));
        AssertionError ragged = assertThrows(AssertionError.class,
                () -> MarkdownTable.read(FILE, List.of("| A | B |", "|---|---|", "| 1 |"), "| A | B |"));
        assertTrue(ragged.getMessage().startsWith("fixture.md:3: "));
    }

    @Test
    public static void rowsReportMismatchesWithTheirLine() {
        MarkdownTable.Row row = MarkdownTable.read(FILE, List.of("| A |", "|---|", "| `x` `y` |"), "| A |").get(0);
        AssertionError span = assertThrows(AssertionError.class, () -> row.code(0));
        assertTrue(span.getMessage().startsWith("fixture.md:3: not one code span"));
        AssertionError mismatch = assertThrows(AssertionError.class, () -> row.expect("value", 1, 2));
        assertEquals("fixture.md:3: value: the document says 1, the code says 2", mismatch.getMessage());
    }
}
