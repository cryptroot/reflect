package reflect.stage0.bytecode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads a Markdown table that is found by its exact header row. */
final class MarkdownTable {
    private MarkdownTable() {
    }

    static List<Row> read(Path file, String header) throws IOException {
        return read(file, Files.readAllLines(file, StandardCharsets.UTF_8), header);
    }

    static List<Row> read(Path file, List<String> lines, String header) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(header)) {
                if (start >= 0) {
                    throw new AssertionError(file + ": table appears twice: " + header);
                }
                start = i;
            }
        }
        if (start < 0) {
            throw new AssertionError(file + ": table not found: " + header);
        }
        if (start + 1 == lines.size() || !lines.get(start + 1).trim().matches("\\|(-+\\|)+")) {
            throw new AssertionError(file + ":" + (start + 2) + ": no separator row under " + header);
        }
        int width = cells(header).size();
        var rows = new ArrayList<Row>();
        for (int i = start + 2; i < lines.size() && lines.get(i).trim().startsWith("|"); i++) {
            var row = new Row(file, i + 1, cells(lines.get(i).trim()));
            row.check(row.cells().size() == width, "row has " + row.cells().size() + " cells, not " + width);
            rows.add(row);
        }
        return rows;
    }

    // Splits on the pipes that are not escaped as \|.
    private static List<String> cells(String line) {
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        for (int i = 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        return cells;
    }

    record Row(Path file, int line, List<String> cells) {
        String cell(int index) {
            return cells.get(index);
        }

        String code(int index) {
            String cell = cell(index);
            check(cell.length() > 2 && cell.startsWith("`") && cell.endsWith("`")
                    && cell.indexOf('`', 1) == cell.length() - 1, "not one code span: " + cell);
            return cell.substring(1, cell.length() - 1);
        }

        void check(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(file + ":" + line + ": " + message);
            }
        }

        void expect(String what, Object document, Object code) {
            check(Objects.equals(document, code), what + ": the document says " + document + ", the code says " + code);
        }
    }
}
