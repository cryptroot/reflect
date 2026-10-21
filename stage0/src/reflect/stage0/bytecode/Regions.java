package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * Encodes, decodes and checks the exception regions of a body (BC-18), and states their rules once,
 * for the writer and the reader: a defined kind, a type exactly for CATCH, a try and a handler that
 * each start at an instruction and end at one or at the end of the code, a handler outside its own
 * try, and tries and handlers that nest. The writer sorts the regions by their key, and the reader
 * checks their order.
 */
final class Regions {
    private static final String[] FIELDS = {"tryStart", "tryEnd", "handlerStart", "handlerEnd"};

    private Regions() {
    }

    /** Two regions, by index, with a try or handler that overlaps one of the other without nesting. */
    record Crossing(int region, int other) {
    }

    /** The first rule that the region breaks in the body, or null. */
    static @Nullable String problem(Region region, MethodBody body) {
        int kind = region.kind();
        if (kind != Flags.REGION_CATCH && kind != Flags.REGION_FINALLY) {
            return "region kind " + ModuleFormatException.hex(kind & 0xFFFFFFFFL) + " is not defined (BC-20)";
        }
        if ((kind == Flags.REGION_CATCH) != (region.type() != null)) {
            return "a CATCH region has a type, and a FINALLY region none (BC-18)";
        }
        Label[] labels = {region.tryStart(), region.tryEnd(), region.handlerStart(), region.handlerEnd()};
        for (int i = 0; i < labels.length; i++) {
            Label label = labels[i];
            int last = body.code().size() - (i % 2 == 0 ? 1 : 0);
            if (label.body() != body || label.index() > last) {
                return "the " + FIELDS[i] + " of the region names no instruction of the body" + (i % 2 == 0 ? ""
                        : ", nor its end") + " (BC-18)";
            }
        }
        int tryStart = region.tryStart().index();
        int tryEnd = region.tryEnd().index();
        int handlerStart = region.handlerStart().index();
        int handlerEnd = region.handlerEnd().index();
        if (tryStart >= tryEnd) {
            return "the try of the region holds no instruction (BC-18)";
        }
        if (handlerStart >= handlerEnd) {
            return "the handler of the region holds no instruction (BC-18)";
        }
        if (handlerStart < tryEnd && tryStart < handlerEnd) {
            return "the handler of the region overlaps its try (BC-18)";
        }
        return null;
    }

    /**
     * Two regions whose ranges cross, or null when every two tries and handlers are disjoint or one
     * holds the other. The ranges are sorted by start, the longer first, and a stack holds the ranges
     * that hold the next one, so the check takes n log n steps for n regions.
     */
    static @Nullable Crossing crossing(List<Region> regions) {
        var ranges = new ArrayList<int[]>();
        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            ranges.add(new int[] {region.tryStart().index(), region.tryEnd().index(), i, 0});
            ranges.add(new int[] {region.handlerStart().index(), region.handlerEnd().index(), i, 1});
        }
        // Start ascending and end descending, so a range comes before the ranges that it holds.
        ranges.sort((left, right) -> {
            int order = ModuleWriter.compareRows(left[0], right[0]);
            if (order == 0) {
                order = ModuleWriter.compareRows(right[1], left[1]);
            }
            if (order == 0) {
                order = ModuleWriter.compareRows(left[2], right[2]);
            }
            return order != 0 ? order : ModuleWriter.compareRows(left[3], right[3]);
        });
        int[] stack = new int[ranges.size()];
        int top = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            while (top > 0 && ranges.get(stack[top - 1])[1] <= range[0]) {
                top--;
            }
            if (top > 0 && range[1] > ranges.get(stack[top - 1])[1]) {
                return new Crossing(range[2], ranges.get(stack[top - 1])[2]);
            }
            stack[top] = i;
            top++;
        }
        return null;
    }

    static String crossingProblem(Crossing crossing) {
        return "a try or handler of the region overlaps one of region " + crossing.other() + " without nesting (BC-18)";
    }

    /** Writes the regions, sorted by their key, with the code offsets of their labels. */
    static void write(List<Region> regions, int[] starts, ByteSink out, Code.Tokens tokens, String owner)
            throws ModuleFormatException {
        var keys = new ArrayList<int[]>();
        for (Region region : regions) {
            TypeHandle type = region.type();
            keys.add(new int[] {starts[region.tryStart().index()], starts[region.tryEnd().index()],
                starts[region.handlerStart().index()], starts[region.handlerEnd().index()],
                type == null ? 0 : tokens.token(type), region.kind()});
        }
        keys.sort(Regions::compare);
        out.uleb(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            int[] key = keys.get(i);
            if (i > 0 && compare(keys.get(i - 1), key) == 0) {
                throw new ModuleFormatException(owner + " has two equal exception regions (BC-18)");
            }
            out.u8(key[5]);
            for (int column = 0; column < 5; column++) {
                out.uleb(key[column]);
            }
        }
    }

    /**
     * Reads the regions of a body whose instructions start at these code offsets, the end of the code
     * included, and checks each one at its offset.
     */
    static void read(ByteSource in, MethodBody body, Map<Integer, Integer> starts, Code.Targets targets,
            Code.Handles handles) throws ModuleFormatException {
        int countAt = in.position();
        int count = in.uleb();
        if (count < 0 || count > Limits.MAX_REGIONS) {
            throw new ModuleFormatException(countAt, "the body has " + (count & 0xFFFFFFFFL) + " exception regions,"
                    + " more than the limit of " + Limits.MAX_REGIONS + " (BC-21)");
        }
        // A region takes at least six bytes.
        if (count > in.remaining() / 6) {
            throw new ModuleFormatException(countAt, "a list of " + count + " exception regions runs past the end of CODE");
        }
        int[] offsets = new int[count];
        int[] previous = new int[6];
        for (int i = 0; i < count; i++) {
            offsets[i] = in.position();
            int kind = in.u8();
            int[] key = new int[6];
            Label[] labels = new Label[4];
            for (int field = 0; field < 4; field++) {
                int at = in.position();
                key[field] = in.uleb();
                int index = starts.getOrDefault(key[field], -1);
                if (index < 0) {
                    throw new ModuleFormatException(at, "the " + FIELDS[field] + " of the region is code offset "
                            + (key[field] & 0xFFFFFFFFL) + ", where no instruction starts (BC-18)");
                }
                labels[field] = targets.marked(key[field], index, body);
            }
            int typeAt = in.position();
            key[4] = in.uleb();
            key[5] = kind;
            TypeHandle type = null;
            if (key[4] != 0) {
                if (!(handles.handle(key[4]) instanceof TypeHandle handle)) {
                    throw new ModuleFormatException(typeAt, "token " + ModuleFormatException.hex(key[4] & 0xFFFFFFFFL)
                            + " does not name a TypeDef, TypeRef or TypeSpec row (BC-18)");
                }
                type = handle;
            }
            var region = new Region(kind, labels[0], labels[1], labels[2], labels[3], type);
            String problem = problem(region, body);
            if (problem == null && i > 0 && compare(previous, key) >= 0) {
                problem = compare(previous, key) == 0 ? "two exception regions are equal (BC-18)"
                        : "the exception regions are not sorted by tryEnd, tryStart, handlerStart, handlerEnd and type (BC-18)";
            }
            if (problem != null) {
                throw new ModuleFormatException(offsets[i], problem);
            }
            body.regions().add(region);
            previous = key;
        }
        Crossing crossing = crossing(body.regions());
        if (crossing != null) {
            throw new ModuleFormatException(offsets[crossing.region()], crossingProblem(crossing));
        }
    }

    // The key of BC-18 over tryStart, tryEnd, handlerStart, handlerEnd and the type token: tryEnd
    // ascending, tryStart descending, then the others ascending, so the regions of a multi-catch,
    // which share their try and handler, have one order.
    private static int compare(int[] left, int[] right) {
        int order = ModuleWriter.compareRows(left[1], right[1]);
        if (order == 0) {
            order = ModuleWriter.compareRows(right[0], left[0]);
        }
        for (int column = 2; column < 5 && order == 0; column++) {
            order = ModuleWriter.compareRows(left[column], right[column]);
        }
        return order;
    }
}
