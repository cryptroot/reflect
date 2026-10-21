package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * Encodes, decodes and checks the stack map frames of a body (BC-19), which stage0 stores as given
 * (Q50), and states their rules once, for the writer and the reader: a frame at a branch target or
 * a handler start, one verification type per argument and local, a stack no deeper than maxStack,
 * and defined tags with a type exactly where the tag takes one. The writer sorts the frames by
 * offset, and the reader checks their order.
 */
final class Frames {
    private Frames() {
    }

    /** Where a frame may stand: the instructions that a branch targets or a handler starts at. */
    static boolean[] places(MethodBody body) {
        boolean[] places = new boolean[body.code().size() + 1];
        for (Instruction instruction : body.code()) {
            if (instruction instanceof Instruction.Branch branch) {
                mark(places, branch.target());
            } else if (instruction instanceof Instruction.Switch choice) {
                for (Label target : choice.targets()) {
                    mark(places, target);
                }
            }
        }
        for (Region region : body.regions()) {
            mark(places, region.handlerStart());
        }
        return places;
    }

    private static void mark(boolean[] places, Label label) {
        if (label.index() >= 0 && label.index() < places.length) {
            places[label.index()] = true;
        }
    }

    /** The first rule that the frame breaks in a body with this many arguments and these places, or null. */
    static @Nullable String problem(Frame frame, MethodBody body, int arguments, boolean[] places) {
        Label at = frame.at();
        if (at.body() != body || at.index() >= body.code().size()) {
            return "the stack map frame is at no instruction of the body (BC-19)";
        }
        if (!places[at.index()]) {
            return "the stack map frame is at instruction " + at.index() + ", which is neither a branch target nor a"
                    + " handler start (BC-19)";
        }
        int locals = body.locals().size();
        if (frame.slots().size() != arguments + locals) {
            return "the stack map frame has " + frame.slots().size() + " slots, but the method has " + arguments
                    + " arguments and " + locals + " locals (BC-19)";
        }
        if (frame.stack().size() > body.maxStack()) {
            return "the stack map frame has a stack of " + frame.stack().size() + ", deeper than the maximum of "
                    + body.maxStack() + " (BC-19)";
        }
        String problem = typesProblem(frame.slots());
        return problem != null ? problem : typesProblem(frame.stack());
    }

    private static @Nullable String typesProblem(List<Frame.VType> types) {
        for (Frame.VType type : types) {
            int tag = type.tag();
            if (tag < VerificationType.I32 || tag > VerificationType.CLASS) {
                return undefined(tag);
            }
            if (VerificationType.hasToken(tag) != (type.type() != null)) {
                return "verification type " + hex(tag) + (VerificationType.hasToken(tag) ? " has no type, but its tag"
                        + " takes one (BC-19)" : " takes no type (BC-19)");
            }
        }
        return null;
    }

    /** Writes the frames, sorted by offset, with the code offsets of their labels. */
    static void write(List<Frame> frames, int[] starts, ByteSink out, Code.Tokens tokens, String owner)
            throws ModuleFormatException {
        var order = new ArrayList<Frame>();
        for (Frame frame : frames) {
            order.add(frame);
        }
        order.sort((left, right) -> ModuleWriter.compareRows(left.at().index(), right.at().index()));
        out.uleb(order.size());
        for (int i = 0; i < order.size(); i++) {
            Frame frame = order.get(i);
            int offset = starts[frame.at().index()];
            if (i > 0 && order.get(i - 1).at().index() == frame.at().index()) {
                throw new ModuleFormatException(owner + " has two stack map frames at code offset " + offset + " (BC-19)");
            }
            out.uleb(offset);
            write(frame.slots(), out, tokens);
            write(frame.stack(), out, tokens);
        }
    }

    private static void write(List<Frame.VType> types, ByteSink out, Code.Tokens tokens) throws ModuleFormatException {
        out.uleb(types.size());
        for (Frame.VType type : types) {
            out.u8(type.tag());
            TypeHandle handle = type.type();
            if (handle != null) {
                out.uleb(tokens.token(handle));
            }
        }
    }

    /**
     * Reads the frames of a body whose instructions start at these code offsets, the end of the code
     * included, and checks each one at its offset.
     */
    static void read(ByteSource in, MethodBody body, Map<Integer, Integer> starts, Code.Targets targets,
            Code.Handles handles, int arguments) throws ModuleFormatException {
        int countAt = in.position();
        int count = in.uleb();
        // A frame takes at least three bytes.
        if (count < 0 || count > in.remaining() / 3) {
            throw new ModuleFormatException(countAt, "a list of " + (count & 0xFFFFFFFFL) + " stack map frames runs past"
                    + " the end of CODE");
        }
        boolean[] places = places(body);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int at = in.position();
            int offset = in.uleb();
            int index = starts.getOrDefault(offset, -1);
            if (index < 0) {
                throw new ModuleFormatException(at, "the stack map frame is at code offset " + (offset & 0xFFFFFFFFL)
                        + ", where no instruction starts (BC-19)");
            }
            if (offset <= previous) {
                throw new ModuleFormatException(at, offset == previous ? "two stack map frames have code offset " + offset
                        + " (BC-19)" : "the stack map frames are not sorted by code offset (BC-19)");
            }
            Label label = targets.marked(offset, index, body);
            var frame = new Frame(label, read(in, handles), read(in, handles));
            String problem = problem(frame, body, arguments, places);
            if (problem != null) {
                throw new ModuleFormatException(at, problem);
            }
            body.frames().add(frame);
            previous = offset;
        }
    }

    // A count, then the verification types; a tag decides whether a token follows, so the reader
    // checks it where it reads it.
    private static List<Frame.VType> read(ByteSource in, Code.Handles handles) throws ModuleFormatException {
        int countAt = in.position();
        int count = in.uleb();
        if (count < 0 || count > in.remaining()) {
            throw new ModuleFormatException(countAt, "a list of " + (count & 0xFFFFFFFFL) + " verification types runs"
                    + " past the end of CODE");
        }
        var types = new ArrayList<Frame.VType>();
        for (int i = 0; i < count; i++) {
            int at = in.position();
            int tag = in.u8();
            if (tag < VerificationType.I32 || tag > VerificationType.CLASS) {
                throw new ModuleFormatException(at, undefined(tag));
            }
            TypeHandle type = null;
            if (VerificationType.hasToken(tag)) {
                int tokenAt = in.position();
                int token = in.uleb();
                if (!(handles.handle(token) instanceof TypeHandle handle)) {
                    throw new ModuleFormatException(tokenAt, "token " + ModuleFormatException.hex(token & 0xFFFFFFFFL)
                            + " does not name a TypeDef, TypeRef or TypeSpec row (BC-19)");
                }
                type = handle;
            }
            types.add(new Frame.VType(tag, type));
        }
        return types;
    }

    private static String undefined(int tag) {
        return "verification type " + hex(tag) + " is not defined (BC-20)";
    }

    private static String hex(int value) {
        return ModuleFormatException.hex(value & 0xFFFFFFFFL);
    }
}
