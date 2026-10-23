package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * Encodes, decodes and checks DEBUG (§6.1): the module's contentHash, the source files, and per
 * method token the line entries and the local names of its body. It states their rules once, for
 * the writer and the reader: a line entry at an instruction, with a line from 1 in a source file
 * that has a path and a SHA-256, and a name that is an identifier for a local of the body. The writer
 * sorts the entries, lists each file at its first use and writes no DEBUG without content; the
 * reader checks these orders (BC-24), and reads a DEBUG only when it is not stale (BC-20).
 */
final class Debug {
    private Debug() {
    }

    /** Whether a DEBUG describes other content: it does not start with the module's contentHash (BC-20). */
    static boolean stale(byte[] content, byte[] contentHash) {
        if (content.length < contentHash.length) {
            return true;
        }
        for (int i = 0; i < contentHash.length; i++) {
            if (content[i] != contentHash[i]) {
                return true;
            }
        }
        return false;
    }

    /** The first rule that the line entry breaks in the body, or null. */
    static @Nullable String problem(Line line, MethodBody body) {
        Label at = line.at();
        if (at.body() != body || at.index() >= body.code().size()) {
            return "the line entry is at no instruction of the body (BC-17)";
        }
        if (line.number() == 0) {
            return "the line entry is at line 0; lines count from 1 (BC-17)";
        }
        return problem(line.file());
    }

    /** The first rule that the source file breaks, or null. */
    static @Nullable String problem(SourceFile file) {
        if (file.path().isEmpty()) {
            return "a source file has an empty path (BC-17)";
        }
        if (file.hash().length != Sha256.SIZE) {
            return "the SHA-256 of the source file " + file.path() + " has " + file.hash().length + " bytes, not "
                    + Sha256.SIZE + " (BC-17)";
        }
        return null;
    }

    /** The first rule that the name breaks in the body, or null. */
    static @Nullable String problem(LocalName name, MethodBody body) {
        int locals = body.locals().size();
        if (name.index() < 0 || name.index() >= locals) {
            return "local " + (name.index() & 0xFFFFFFFFL) + " does not exist; the body has " + locals + " locals (BC-17)";
        }
        if (name.name().isEmpty()) {
            return "the name of local " + name.index() + " is empty; DEBUG lists only the locals that have a name (BC-17)";
        }
        return ModuleWriter.nameProblem("name of local " + name.index(), name.name(), false, false);
    }

    /**
     * Encodes DEBUG for the methods, in row order, whose instructions start at these code offsets. It
     * gives no bytes when no body has a line entry or a local name, since DEBUG then has no content
     * (BC-24). The bodies are checked already.
     */
    static byte[] write(List<MethodDef> methods, int[][] starts, List<String> owners, byte[] contentHash)
            throws ModuleFormatException {
        var files = new ArrayList<SourceFile>();
        Map<String, Integer> indexes = new HashMap<>();
        var entries = new ByteSink();
        int count = 0;
        for (int i = 0; i < methods.size(); i++) {
            MethodBody body = methods.get(i).body();
            if (body == null || (body.lines().isEmpty() && body.localNames().isEmpty())) {
                continue;
            }
            String owner = owners.get(i);
            entries.uleb((TableId.METHOD << 24) | (i + 1));
            var lines = new ArrayList<Line>();
            for (Line line : body.lines()) {
                lines.add(line);
            }
            lines.sort((left, right) -> ModuleWriter.compareRows(left.at().index(), right.at().index()));
            entries.uleb(lines.size());
            for (int k = 0; k < lines.size(); k++) {
                Line line = lines.get(k);
                int offset = starts[i][line.at().index()];
                if (k > 0 && lines.get(k - 1).at().index() == line.at().index()) {
                    throw new ModuleFormatException(owner + " has two line entries at code offset " + offset + " (BC-24)");
                }
                entries.uleb(offset);
                entries.uleb(fileIndex(line.file(), files, indexes));
                entries.uleb(line.number());
            }
            var names = new ArrayList<LocalName>();
            for (LocalName name : body.localNames()) {
                names.add(name);
            }
            names.sort((left, right) -> ModuleWriter.compareRows(left.index(), right.index()));
            entries.uleb(names.size());
            for (int k = 0; k < names.size(); k++) {
                LocalName name = names.get(k);
                if (k > 0 && names.get(k - 1).index() == name.index()) {
                    throw new ModuleFormatException(owner + " gives local " + name.index() + " two names (BC-24)");
                }
                byte[] utf8 = Utf8.encode(name.name());
                if (utf8.length > Limits.MAX_IDENTIFIER_BYTES) {
                    throw new ModuleFormatException(owner + ": a local name of " + utf8.length
                            + " UTF-8 bytes exceeds the limit of " + Limits.MAX_IDENTIFIER_BYTES + " (BC-21)");
                }
                entries.uleb(name.index());
                entries.uleb(utf8.length);
                entries.bytes(utf8);
            }
            count++;
        }
        if (count == 0) {
            return new byte[0];
        }
        var out = new ByteSink();
        out.bytes(contentHash);
        out.uleb(files.size());
        for (SourceFile file : files) {
            byte[] path = Utf8.encode(file.path());
            out.uleb(path.length);
            out.bytes(path);
            out.bytes(file.hash());
        }
        out.uleb(count);
        out.bytes(entries.toByteArray());
        return out.toByteArray();
    }

    // The index of the file in the order of first use; a path names one file, with one SHA-256.
    private static int fileIndex(SourceFile file, List<SourceFile> files, Map<String, Integer> indexes)
            throws ModuleFormatException {
        int index = indexes.getOrDefault(file.path(), -1);
        if (index < 0) {
            index = files.size();
            indexes.put(file.path(), index);
            files.add(file);
        } else if (Blob.compare(files.get(index).hash(), file.hash()) != 0) {
            throw new ModuleFormatException("two source files have the path " + file.path() + " (BC-24)");
        }
        return index;
    }

    /**
     * Reads a DEBUG that is not stale into the bodies, by method row, that the reader decoded, for a
     * module with this many Method rows, and checks each entry at its offset.
     */
    static void read(ByteSource in, int methods, Map<Integer, Code.Decoded> bodies) throws ModuleFormatException {
        in.skip(Sha256.SIZE);
        int countAt = in.position();
        int fileCount = in.uleb();
        // A file takes at least 33 bytes: the length of its path, and its SHA-256.
        if (fileCount < 0 || fileCount > in.remaining() / 33) {
            throw new ModuleFormatException(countAt, "a list of " + (fileCount & 0xFFFFFFFFL) + " source files runs past"
                    + " the end of DEBUG");
        }
        var files = new ArrayList<SourceFile>();
        var fileOffsets = new ArrayList<Integer>();
        Map<String, Integer> paths = new HashMap<>();
        for (int i = 0; i < fileCount; i++) {
            int at = in.position();
            String path = Utf8.decode(in, in.uleb());
            var file = new SourceFile(path, in.bytes(Sha256.SIZE));
            check(at, problem(file));
            if (paths.containsKey(path)) {
                throw new ModuleFormatException(at, "two source files have the path " + path + " (BC-24)");
            }
            paths.put(path, i);
            files.add(file);
            fileOffsets.add(at);
        }
        int methodsAt = in.position();
        int count = in.uleb();
        if (count == 0) {
            throw new ModuleFormatException(methodsAt, "DEBUG lists no method; a section is present only when it has"
                    + " content (BC-24)");
        }
        // A method takes at least three bytes: its token and two counts.
        if (count < 0 || count > in.remaining() / 3) {
            throw new ModuleFormatException(methodsAt, "a list of " + (count & 0xFFFFFFFFL) + " methods runs past the end"
                    + " of DEBUG");
        }
        int used = 0;
        int previous = 0;
        for (int i = 0; i < count; i++) {
            int at = in.position();
            int token = in.uleb();
            int row = token & 0xFFFFFF;
            if (token >>> 24 != TableId.METHOD || row == 0 || row > methods) {
                throw new ModuleFormatException(at, "token " + hex(token) + " does not name a Method row (BC-17)");
            }
            if (token <= previous) {
                throw new ModuleFormatException(at, token == previous ? "DEBUG lists the method " + hex(token)
                        + " twice (BC-24)" : "the methods of DEBUG are not sorted by token (BC-24)");
            }
            Code.Decoded decoded = bodies.get(row);
            if (decoded == null) {
                throw new ModuleFormatException(at, "the method " + hex(token) + " has no body, so it has no line entry"
                        + " and no local name (BC-17)");
            }
            used = readLines(in, decoded, files, used);
            MethodBody body = decoded.body();
            readNames(in, body);
            if (body.lines().isEmpty() && body.localNames().isEmpty()) {
                throw new ModuleFormatException(at, "DEBUG lists the method " + hex(token) + ", which has no line entry"
                        + " and no local name (BC-17)");
            }
            previous = token;
        }
        if (used < files.size()) {
            throw new ModuleFormatException(fileOffsets.get(used), "no line entry names the source file "
                    + files.get(used).path() + "; DEBUG lists only the files that line entries name (BC-24)");
        }
        if (!in.atEnd()) {
            throw new ModuleFormatException(in.position(), "DEBUG continues after its last method (BC-17)");
        }
    }

    // Reads the line entries of one body, after the files that earlier bodies use; returns how many
    // files this body and earlier ones use, since each file comes at its first use.
    private static int readLines(ByteSource in, Code.Decoded decoded, List<SourceFile> files, int used)
            throws ModuleFormatException {
        int countAt = in.position();
        int count = in.uleb();
        // A line entry takes at least three bytes.
        if (count < 0 || count > in.remaining() / 3) {
            throw new ModuleFormatException(countAt, "a list of " + (count & 0xFFFFFFFFL) + " line entries runs past"
                    + " the end of DEBUG");
        }
        MethodBody body = decoded.body();
        int named = used;
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int at = in.position();
            int offset = in.uleb();
            int fileAt = in.position();
            int file = in.uleb();
            int number = in.uleb();
            int index = decoded.starts().getOrDefault(offset, -1);
            if (index < 0) {
                throw new ModuleFormatException(at, "the line entry is at code offset " + (offset & 0xFFFFFFFFL)
                        + ", where no instruction starts (BC-17)");
            }
            if (offset <= previous) {
                throw new ModuleFormatException(at, offset == previous ? "two line entries have code offset " + offset
                        + " (BC-24)" : "the line entries are not sorted by code offset (BC-24)");
            }
            if (file < 0 || file >= files.size()) {
                throw new ModuleFormatException(fileAt, "the line entry names source file " + (file & 0xFFFFFFFFL)
                        + ", but DEBUG lists " + files.size() + " source files (BC-17)");
            }
            if (file > named) {
                throw new ModuleFormatException(fileAt, "the line entry names source file " + file + " before source file "
                        + named + "; DEBUG lists the files in the order of their first use (BC-24)");
            }
            if (file == named) {
                named++;
            }
            var line = new Line(decoded.targets().marked(offset, index, body), files.get(file), number);
            check(at, problem(line, body));
            body.lines().add(line);
            previous = offset;
        }
        return named;
    }

    private static void readNames(ByteSource in, MethodBody body) throws ModuleFormatException {
        int countAt = in.position();
        int count = in.uleb();
        // A name takes at least two bytes: an index and a length.
        if (count < 0 || count > in.remaining() / 2) {
            throw new ModuleFormatException(countAt, "a list of " + (count & 0xFFFFFFFFL) + " local names runs past the"
                    + " end of DEBUG");
        }
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int at = in.position();
            int index = in.uleb();
            int lengthAt = in.position();
            int length = in.uleb();
            if (length < 0 || length > Limits.MAX_IDENTIFIER_BYTES) {
                throw new ModuleFormatException(lengthAt, "a local name of " + (length & 0xFFFFFFFFL)
                        + " UTF-8 bytes exceeds the limit of " + Limits.MAX_IDENTIFIER_BYTES + " (BC-21)");
            }
            var name = new LocalName(index, Utf8.decode(in, length));
            check(at, problem(name, body));
            if (index <= previous) {
                throw new ModuleFormatException(at, index == previous ? "local " + index + " has two names (BC-24)"
                        : "the local names are not sorted by index (BC-24)");
            }
            body.localNames().add(name);
            previous = index;
        }
    }

    private static void check(int at, @Nullable String problem) throws ModuleFormatException {
        if (problem != null) {
            throw new ModuleFormatException(at, problem);
        }
    }

    private static String hex(int value) {
        return ModuleFormatException.hex(value & 0xFFFFFFFFL);
    }
}
