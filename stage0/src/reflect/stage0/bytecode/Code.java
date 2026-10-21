package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.HashMap;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Map;
import reflect.stage0.lib.Nullable;

/**
 * Encodes and decodes method bodies (BC-17) and their instructions (BC-04), and states the rules
 * of a body once, for the writer and the reader: a stack depth and locals within the limits of
 * BC-21, a defined opcode other than breakpoint, the operands of its operand kind, tokens that
 * name what the instruction takes, and only the arguments and locals that the method has.
 */
final class Code {
    /** What the tok operand of an instruction names (§8.1). */
    static final int TYPE = 0;
    static final int FIELD = 1;
    static final int METHOD = 2;

    private static final String[] NOTATIONS = {"", "<i4>", "<i8>", "<r4>", "<r8>", "<str>", "<tok>", "<n>", "<off>",
        "<count> <off>...", "<tok> <tok>", "<n> <n>"};
    private static final String[] KINDS = {"a type", "a field", "a method"};
    private static final String[] TABLES = {"a TypeDef, TypeRef or TypeSpec row", "a Field or MemberRef row",
        "a Method, MemberRef or MethodSpec row"};

    /** The token of a row, for the writer. */
    @FunctionalInterface
    interface Tokens {
        int token(Handle handle) throws ModuleFormatException;
    }

    /** The row of a token, or null when it names no row that an instruction may name, for the reader. */
    @FunctionalInterface
    interface Handles {
        @Nullable Handle handle(int token);
    }

    private Code() {
    }

    /**
     * What the tok operand of the instruction names (§8.1): a field for ldfld, stfld, ldflda,
     * ldsfld, stsfld, ldsflda and ldconst; a method for newobj, newobj.svirt, call, call.recv,
     * callvirt and call.svirt; a type for the others.
     */
    static int tokenKind(int opcode) {
        if (opcode == Opcode.LDFLD || opcode == Opcode.STFLD || opcode == Opcode.LDFLDA || opcode == Opcode.LDSFLD
                || opcode == Opcode.STSFLD || opcode == Opcode.LDSFLDA || opcode == Opcode.LDCONST) {
            return FIELD;
        }
        if (opcode == Opcode.NEWOBJ || opcode == Opcode.NEWOBJ_SVIRT || opcode == Opcode.CALL || opcode == Opcode.CALL_RECV
                || opcode == Opcode.CALLVIRT || opcode == Opcode.CALL_SVIRT) {
            return METHOD;
        }
        return TYPE;
    }

    /** Whether a method with these flags has a body: it is not ABSTRACT, NATIVE or EXTERN (§3.1). */
    static boolean hasBody(int flags) {
        return (flags & (Flags.METHOD_ABSTRACT | Flags.METHOD_NATIVE | Flags.METHOD_EXTERN)) == 0;
    }

    /** The number of arguments: this first, unless the signature is STATIC, then the parameters. */
    static int arguments(MethodSig signature) {
        return signature.params().size() + ((signature.flags() & SigCode.METHOD_STATIC) != 0 ? 0 : 1);
    }

    /** The first rule that the body of a method with this many arguments breaks, or null. */
    static @Nullable String problem(MethodBody body, int arguments, String module) {
        String stack = stackProblem(body.maxStack());
        if (stack != null) {
            return stack;
        }
        if (!body.locals().isEmpty()) {
            String locals = Signatures.localsProblem(body.locals(), ModuleWriter.DEFINITION, module);
            if (locals != null) {
                return "locals: " + locals;
            }
        }
        List<Instruction> code = body.code();
        for (int i = 0; i < code.size(); i++) {
            String problem = problem(code.get(i), arguments, body.locals().size());
            if (problem == null) {
                problem = targetProblem(code.get(i), body);
            }
            if (problem != null) {
                return "instruction " + i + ": " + problem;
            }
        }
        List<Region> regions = body.regions();
        if (regions.size() > Limits.MAX_REGIONS) {
            return "the body has " + regions.size() + " exception regions, more than the limit of " + Limits.MAX_REGIONS
                    + " (BC-21)";
        }
        for (int i = 0; i < regions.size(); i++) {
            String problem = Regions.problem(regions.get(i), body);
            if (problem != null) {
                return "region " + i + ": " + problem;
            }
        }
        Regions.Crossing crossing = Regions.crossing(regions);
        return crossing == null ? null : "region " + crossing.region() + ": " + Regions.crossingProblem(crossing);
    }

    /** The first rule that the instruction breaks in a body with these arguments and locals, or null. */
    static @Nullable String problem(Instruction instruction, int arguments, int locals) {
        int opcode = instruction.opcode();
        if (!Opcode.isDefined(opcode)) {
            return undefined(opcode);
        }
        if (opcode == Opcode.BREAKPOINT) {
            return "a module never contains breakpoint (BC-04)";
        }
        int kind = Opcode.operands(opcode);
        boolean fits = instruction instanceof Instruction.Plain ? kind == Opcode.OPERAND_NONE
                : instruction instanceof Instruction.Value32 ? kind == Opcode.OPERAND_I4 || kind == Opcode.OPERAND_R4
                : instruction instanceof Instruction.Value64 ? kind == Opcode.OPERAND_I8 || kind == Opcode.OPERAND_R8
                : instruction instanceof Instruction.Literal ? kind == Opcode.OPERAND_STR
                : instruction instanceof Instruction.Index ? kind == Opcode.OPERAND_N
                : instruction instanceof Instruction.Indexes ? kind == Opcode.OPERAND_N_N
                : instruction instanceof Instruction.Branch ? kind == Opcode.OPERAND_OFF
                : instruction instanceof Instruction.Switch ? kind == Opcode.OPERAND_SWITCH
                : instruction instanceof Instruction.Constrained ? kind == Opcode.OPERAND_TOK_TOK
                : kind == Opcode.OPERAND_TOK;
        if (!fits) {
            return Opcode.mnemonic(opcode) + (kind == Opcode.OPERAND_NONE ? " takes no operand"
                    : " takes the operands " + NOTATIONS[kind]) + " (BC-04)";
        }
        if (kind == Opcode.OPERAND_TOK) {
            int names = instruction instanceof Instruction.Type ? TYPE : instruction instanceof Instruction.Field ? FIELD
                    : METHOD;
            if (names != tokenKind(opcode)) {
                return Opcode.mnemonic(opcode) + " names " + KINDS[tokenKind(opcode)] + " (BC-04)";
            }
        }
        MemberRef ref = memberRef(instruction);
        boolean field = instruction instanceof Instruction.Field;
        if (ref != null && (ref.signature() instanceof FieldSig) != field) {
            return Opcode.mnemonic(opcode) + " names " + KINDS[field ? FIELD : METHOD] + ", but the MemberRef "
                    + ref.parent().qualifiedName() + "::" + ref.name() + " is " + KINDS[field ? METHOD : FIELD] + " (BC-04)";
        }
        if (instruction instanceof Instruction.Index index) {
            boolean argument = opcode == Opcode.LDARG || opcode == Opcode.STARG || opcode == Opcode.LDARGA;
            int count = argument ? arguments : locals;
            if (index.index() < 0 || index.index() >= count) {
                return (argument ? "argument " : "local ") + (index.index() & 0xFFFFFFFFL) + " does not exist; the "
                        + (argument ? "method has " + count + " arguments" : "body has " + count + " locals") + " (BC-04)";
            }
        }
        return null;
    }

    // A branch lands on an instruction of its own body (BC-17). The reader makes its labels itself, from
    // the offsets, and checks each offset instead.
    private static @Nullable String targetProblem(Instruction instruction, MethodBody body) {
        String problem = null;
        if (instruction instanceof Instruction.Branch branch) {
            problem = targetProblem(branch.target(), body, branch.opcode());
        } else if (instruction instanceof Instruction.Switch choice) {
            for (Label target : choice.targets()) {
                if (problem == null) {
                    problem = targetProblem(target, body, choice.opcode());
                }
            }
        }
        return problem;
    }

    private static @Nullable String targetProblem(Label target, MethodBody body, int opcode) {
        if (target.body() != body) {
            return Opcode.mnemonic(opcode) + " targets a label that this body does not mark (BC-17)";
        }
        if (target.index() >= body.code().size()) {
            return Opcode.mnemonic(opcode) + " targets the end of the code, after its last instruction (BC-17)";
        }
        return null;
    }

    // The MemberRef that a field or method operand names, or null.
    private static @Nullable MemberRef memberRef(Instruction instruction) {
        Handle handle = instruction instanceof Instruction.Field field ? field.field()
                : instruction instanceof Instruction.Method method ? method.method()
                : instruction instanceof Instruction.Constrained constrained ? constrained.method() : null;
        return handle instanceof MemberRef ref ? ref : null;
    }

    private static @Nullable String stackProblem(int maxStack) {
        return maxStack >= 0 && maxStack <= Limits.MAX_STACK ? null : "the maximum stack depth of "
                + (maxStack & 0xFFFFFFFFL) + " exceeds the limit of " + Limits.MAX_STACK + " (BC-21)";
    }

    /**
     * Encodes a body that the writer has checked. Its LOCALS signature, then the strings of its
     * instructions, enter the heaps here, in the canonical order of BC-24.
     */
    static byte[] body(MethodBody body, HeapWriter blobs, HeapWriter literals, Tokens tokens, String owner)
            throws ModuleFormatException {
        var out = new ByteSink();
        out.uleb(body.maxStack());
        out.uleb(body.locals().isEmpty() ? 0 : blobs.blob(Signatures.locals(body.locals(), tokens::token)));
        var code = new ByteSink();
        int[] starts = layout(body.code(), code, literals, tokens);
        if (code.size() > Limits.MAX_CODE_SIZE) {
            throw new ModuleFormatException(owner + " has " + code.size() + " bytes of code, more than the limit of "
                    + Limits.MAX_CODE_SIZE + " (BC-21)");
        }
        out.uleb(code.size());
        out.bytes(code.toByteArray());
        Regions.write(body.regions(), starts, out, tokens, owner);
        // frameCount; stack maps come later in v0.0.2.
        out.uleb(0);
        return out.toByteArray();
    }

    /**
     * Lays out the code. A branch takes as many bytes as its offsets need, and they depend on the sizes
     * of the instructions between it and its targets, so the layout repeats until no size changes. It
     * starts from the smallest sizes, and an offset only grows when an instruction that it spans
     * grows, so the loop ends, and every offset is minimal (README §7.3, BC-24).
     */
    private static int[] layout(List<Instruction> code, ByteSink out, HeapWriter literals, Tokens tokens)
            throws ModuleFormatException {
        int count = code.size();
        boolean[] branches = new boolean[count];
        byte[][] fixed = new byte[count][];
        int[] sizes = new int[count];
        int[] starts = new int[count + 1];
        for (int i = 0; i < count; i++) {
            Instruction instruction = code.get(i);
            branches[i] = instruction instanceof Instruction.Branch || instruction instanceof Instruction.Switch;
            if (branches[i]) {
                fixed[i] = new byte[0];
                sizes[i] = branch(instruction, starts, i, null, true);
            } else {
                var bytes = new ByteSink();
                encode(instruction, bytes, literals, tokens);
                fixed[i] = bytes.toByteArray();
                sizes[i] = bytes.size();
            }
        }
        boolean changed = true;
        while (changed) {
            for (int i = 0; i < count; i++) {
                starts[i + 1] = starts[i] + sizes[i];
            }
            changed = false;
            for (int i = 0; i < count; i++) {
                if (branches[i]) {
                    int size = branch(code.get(i), starts, i, null, false);
                    changed |= size != sizes[i];
                    sizes[i] = size;
                }
            }
        }
        for (int i = 0; i < count; i++) {
            if (branches[i]) {
                branch(code.get(i), starts, i, out, false);
            } else {
                out.bytes(fixed[i]);
            }
        }
        return starts;
    }

    // The size of the branch or switch at instruction i, whose offsets count from the instruction after
    // it, with the instructions at these starts; it writes the branch when out is not null. The
    // smallest size takes one byte per offset.
    private static int branch(Instruction instruction, int[] starts, int i, @Nullable ByteSink out, boolean smallest) {
        int opcode = instruction.opcode();
        if (out != null) {
            opcode(opcode, out);
        }
        int next = starts[i + 1];
        if (instruction instanceof Instruction.Branch branch) {
            int offset = starts[branch.target().index()] - next;
            if (out != null) {
                out.sleb(offset);
            }
            return opcodeSize(opcode) + (smallest ? 1 : Leb128.sizeSigned(offset));
        }
        List<Label> targets = ((Instruction.Switch) instruction).targets();
        if (out != null) {
            out.uleb(targets.size());
        }
        int size = opcodeSize(opcode) + Leb128.sizeUnsigned(targets.size());
        for (Label target : targets) {
            int offset = starts[target.index()] - next;
            if (out != null) {
                out.sleb(offset);
            }
            size += smallest ? 1 : Leb128.sizeSigned(offset);
        }
        return size;
    }

    private static void encode(Instruction instruction, ByteSink out, HeapWriter literals, Tokens tokens)
            throws ModuleFormatException {
        int opcode = instruction.opcode();
        opcode(opcode, out);
        int kind = Opcode.operands(opcode);
        if (instruction instanceof Instruction.Value32 value) {
            if (kind == Opcode.OPERAND_I4) {
                out.sleb(value.value());
            } else {
                out.u32(value.value());
            }
        } else if (instruction instanceof Instruction.Value64 value) {
            if (kind == Opcode.OPERAND_I8) {
                out.sleb(value.value());
            } else {
                out.u64(value.value());
            }
        } else if (instruction instanceof Instruction.Literal literal) {
            out.uleb(literals.literal(literal.text()));
        } else if (instruction instanceof Instruction.Index index) {
            out.uleb(index.index());
        } else if (instruction instanceof Instruction.Indexes indexes) {
            out.uleb(indexes.first());
            out.uleb(indexes.second());
        } else if (instruction instanceof Instruction.Type type) {
            out.uleb(tokens.token(type.type()));
        } else if (instruction instanceof Instruction.Field field) {
            out.uleb(tokens.token(field.field()));
        } else if (instruction instanceof Instruction.Method method) {
            out.uleb(tokens.token(method.method()));
        } else if (instruction instanceof Instruction.Constrained constrained) {
            out.uleb(tokens.token(constrained.type()));
            out.uleb(tokens.token(constrained.method()));
        }
    }

    /**
     * Decodes the body at the position of the source, which ends where CODE does, for a method with
     * this many arguments. Each error names its offset: a malformed instruction its own.
     */
    static MethodBody body(ByteSource in, HeapReader blobs, HeapReader literals, Handles handles, int arguments,
            String module) throws ModuleFormatException {
        int at = in.position();
        int maxStack = in.uleb();
        check(at, stackProblem(maxStack));
        var body = new MethodBody(maxStack);
        int localsAt = in.position();
        int locals = in.uleb();
        if (locals != 0) {
            ByteSource signature = blobs.blob(locals, localsAt);
            int start = signature.position();
            List<TypeSig> types = Signatures.locals(signature, token -> handles.handle(token) instanceof TypeHandle type
                    ? type : null);
            check(start, Signatures.localsProblem(types, ModuleWriter.DEFINITION, module));
            for (TypeSig type : types) {
                body.locals().add(type);
            }
        }
        int sizeAt = in.position();
        int size = in.uleb();
        if (size < 0 || size > Limits.MAX_CODE_SIZE) {
            throw new ModuleFormatException(sizeAt, "the code of " + (size & 0xFFFFFFFFL) + " bytes exceeds the limit of "
                    + Limits.MAX_CODE_SIZE + " (BC-21)");
        }
        if (size > in.remaining()) {
            throw new ModuleFormatException(sizeAt, "the code of " + size + " bytes runs past the end of CODE");
        }
        ByteSource code = in.slice(in.position(), in.position() + size);
        var targets = new Targets(code.position(), size);
        Map<Integer, Integer> starts = new HashMap<>();
        while (!code.atEnd()) {
            int start = code.position();
            starts.put(start - targets.start, body.code().size());
            Instruction instruction = decode(code, literals, handles, targets);
            check(start, problem(instruction, arguments, body.locals().size()));
            body.code().add(instruction);
        }
        // Every branch lands on the first byte of an instruction (BC-17).
        for (int i = 0; i < targets.labels.size(); i++) {
            int index = starts.getOrDefault(targets.offsets.get(i), -1);
            if (index < 0) {
                throw new ModuleFormatException(targets.operands.get(i), "the branch lands at code offset "
                        + targets.offsets.get(i) + ", inside an instruction (BC-17)");
            }
            targets.labels.get(i).mark(body, index);
        }
        in.skip(size);
        starts.put(size, body.code().size());
        Regions.read(in, body, starts, targets, handles);
        int frames = in.position();
        if (in.uleb() != 0) {
            throw new ModuleFormatException(frames, "this reader does not support stack maps yet");
        }
        return body;
    }

    private static Instruction decode(ByteSource in, HeapReader literals, Handles handles, Targets targets)
            throws ModuleFormatException {
        int at = in.position();
        int opcode = in.u8();
        if (opcode == Opcode.PREFIX) {
            opcode = Opcode.PREFIX << 8 | in.u8();
        }
        if (!Opcode.isDefined(opcode)) {
            throw new ModuleFormatException(at, undefined(opcode));
        }
        int kind = Opcode.operands(opcode);
        if (kind == Opcode.OPERAND_NONE) {
            return new Instruction.Plain(opcode);
        }
        if (kind == Opcode.OPERAND_I4 || kind == Opcode.OPERAND_R4) {
            return new Instruction.Value32(opcode, kind == Opcode.OPERAND_I4 ? in.sleb32() : in.u32());
        }
        if (kind == Opcode.OPERAND_I8 || kind == Opcode.OPERAND_R8) {
            return new Instruction.Value64(opcode, kind == Opcode.OPERAND_I8 ? in.sleb64() : in.u64());
        }
        if (kind == Opcode.OPERAND_STR) {
            int operand = in.position();
            return new Instruction.Literal(opcode, literals.literal(in.uleb(), operand));
        }
        if (kind == Opcode.OPERAND_N) {
            return new Instruction.Index(opcode, in.uleb());
        }
        if (kind == Opcode.OPERAND_N_N) {
            int first = in.uleb();
            return new Instruction.Indexes(opcode, first, in.uleb());
        }
        if (kind == Opcode.OPERAND_TOK) {
            int names = tokenKind(opcode);
            Handle handle = handle(in, handles, names);
            return names == TYPE ? new Instruction.Type(opcode, (TypeHandle) handle)
                    : names == FIELD ? new Instruction.Field(opcode, (FieldHandle) handle)
                    : new Instruction.Method(opcode, (MethodHandle) handle);
        }
        if (kind == Opcode.OPERAND_TOK_TOK) {
            var type = (TypeHandle) handle(in, handles, TYPE);
            return new Instruction.Constrained(opcode, type, (MethodHandle) handle(in, handles, METHOD));
        }
        if (kind == Opcode.OPERAND_OFF) {
            int operand = in.position();
            int offset = in.sleb32();
            return new Instruction.Branch(opcode, targets.at((long) in.position() - targets.start + offset, operand));
        }
        // The last operand kind, OPERAND_SWITCH: a count, then one offset per target.
        int countAt = in.position();
        int count = in.uleb();
        if (count < 0 || count > in.remaining()) {
            throw new ModuleFormatException(countAt, "a switch of " + (count & 0xFFFFFFFFL) + " targets runs past the end"
                    + " of the code");
        }
        int[] offsets = new int[count];
        int[] operands = new int[count];
        for (int i = 0; i < count; i++) {
            operands[i] = in.position();
            offsets[i] = in.sleb32();
        }
        long next = in.position() - targets.start;
        var labels = new ArrayList<Label>();
        for (int i = 0; i < count; i++) {
            labels.add(targets.at(next + offsets[i], operands[i]));
        }
        return new Instruction.Switch(opcode, labels);
    }

    // A token, and the row it names, of the kind that the instruction takes.
    private static Handle handle(ByteSource in, Handles handles, int kind) throws ModuleFormatException {
        int at = in.position();
        int token = in.uleb();
        Handle handle = handles.handle(token);
        boolean fits = kind == TYPE ? handle instanceof TypeHandle : kind == FIELD ? handle instanceof FieldHandle
                : handle instanceof MethodHandle;
        if (!fits) {
            throw new ModuleFormatException(at, "token " + ModuleFormatException.hex(token & 0xFFFFFFFFL) + " does not name "
                    + TABLES[kind] + " (BC-04)");
        }
        return handle;
    }

    // The targets of the branches of one body, which become labels once every instruction is read: one
    // label per target, in the order of first use, with the file offset of the operand that first names it.
    static final class Targets {
        final int start;
        final int size;
        final List<Label> labels = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final List<Integer> operands = new ArrayList<>();
        private final Map<Integer, Label> byOffset = new HashMap<>();

        Targets(int start, int size) {
            this.start = start;
            this.size = size;
        }

        // The label at a code offset where an instruction starts, or at the end, for a region.
        Label marked(int offset, int index, MethodBody body) {
            Label label = byOffset.get(offset);
            if (label == null) {
                label = new Label();
                label.mark(body, index);
                byOffset.put(offset, label);
            }
            return label;
        }

        Label at(long target, int operand) throws ModuleFormatException {
            if (target < 0 || target >= size) {
                throw new ModuleFormatException(operand, "the branch lands at code offset " + target
                        + ", outside the code (BC-17)");
            }
            Label label = byOffset.get((int) target);
            if (label == null) {
                label = new Label();
                byOffset.put((int) target, label);
                labels.add(label);
                offsets.add((int) target);
                operands.add(operand);
            }
            return label;
        }
    }

    private static void opcode(int opcode, ByteSink out) {
        if (opcode > 0xFF) {
            out.u8(Opcode.PREFIX);
        }
        out.u8(opcode & 0xFF);
    }

    private static int opcodeSize(int opcode) {
        return opcode > 0xFF ? 2 : 1;
    }

    private static String undefined(int opcode) {
        return "opcode " + ModuleFormatException.hex(opcode & 0xFFFFFFFFL) + " is not defined (BC-04)";
    }

    private static void check(int at, @Nullable String problem) throws ModuleFormatException {
        if (problem != null) {
            throw new ModuleFormatException(at, problem);
        }
    }
}
