package reflect.stage0.bytecode;

import reflect.stage0.test.Test;

import static reflect.stage0.bytecode.TestModules.column;
import static reflect.stage0.bytecode.TestModules.columnOffset;
import static reflect.stage0.bytecode.TestModules.list;
import static reflect.stage0.bytecode.TestModules.patch;
import static reflect.stage0.bytecode.TestModules.rejected;
import static reflect.stage0.bytecode.TestModules.roundTrip;
import static reflect.stage0.bytecode.TestModules.unwritable;
import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class OverrideTableTest {
    private static final TypeSig VOID = new TypeSig.Primitive(SigCode.VOID);
    private static final FieldSig INT_FIELD = new FieldSig(new TypeSig.Primitive(SigCode.I32));
    private static final MethodSig INSTANCE_VOID = new MethodSig(0, 0, VOID, list());
    private static final MethodSig STATIC_VOID = new MethodSig(SigCode.METHOD_STATIC, 0, VOID, list());

    @Test
    public static void overridesSortByClassAndOverriddenMethod() throws Exception {
        byte[] file = roundTrip(animals());
        // a.Animal, a.Dog, a.Loud and a.Pet are TypeDef rows 1 to 4, and their methods Method rows 1 to 7:
        // Animal::toString and speak, Dog::play and speak, Loud::speak, Pet::speak and play.
        // reflect.core.Object::toString is MemberRef row 1.
        var rows = new StringBuilder();
        for (int row = 1; row <= 5; row++) {
            rows.append(column(file, TableId.OVERRIDE, row, 0)).append(' ').append(column(file, TableId.OVERRIDE, row, 1))
                    .append(' ').append(Integer.toHexString(column(file, TableId.OVERRIDE, row, 2))).append("; ");
        }
        assertEquals("1 1 f000001; 2 4 9000002; 2 4 9000006; 2 3 9000007; 4 6 9000005; ", rows.toString());
        ModuleDef read = ModuleReader.read(file);
        assertEquals("a.Animal::toString reflect.core.Object::toString; a.Dog::play a.Pet::play;"
                + " a.Dog::speak a.Animal::speak a.Pet::speak; a.Pet::speak a.Loud::speak; ", overrides(read));
        assertTrue(read.types().get(0).methods().get(0).overrides().get(0) == read.memberRefs().get(0));
    }

    @Test
    public static void theWriterRejectsInvalidOverrides() {
        String slot = "method a.Derived::m overrides a method, but is not a VIRTUAL or ABSTRACT instance method (BC-11)";
        unwritable(derived(0, INSTANCE_VOID), slot);
        unwritable(derived(Flags.METHOD_STATIC | Flags.METHOD_VIRTUAL, STATIC_VOID), slot);
        ModuleDef own = derived(Flags.METHOD_VIRTUAL, INSTANCE_VOID);
        TypeDef type = own.types().get(1);
        type.methods().get(0).overrides().add(method(type, Flags.METHOD_VIRTUAL, "n", INSTANCE_VOID));
        unwritable(own, "method a.Derived::m overrides method a.Derived::n of its own type (BC-11)");
        ModuleDef field = derived(Flags.METHOD_VIRTUAL, INSTANCE_VOID);
        field.types().get(1).methods().get(0).overrides().add(objectMember(field, "count", INT_FIELD));
        unwritable(field, "method a.Derived::m overrides MemberRef reflect.core.Object::count, which is not a method (BC-11)");
        ModuleDef lost = derived(Flags.METHOD_VIRTUAL, INSTANCE_VOID);
        lost.types().get(1).methods().get(0).overrides().add(new MethodDef(Flags.METHOD_VIRTUAL, "lost", INSTANCE_VOID));
        unwritable(lost, "method a.Derived::m overrides the method lost, which the module does not list");
        ModuleDef lostRef = derived(Flags.METHOD_VIRTUAL, INSTANCE_VOID);
        lostRef.types().get(1).methods().get(0).overrides().add(new MemberRef(new TypeRef(new ModuleRef("reflect.core", 0),
                "reflect.core", "Object"), "finalize", INSTANCE_VOID));
        unwritable(lostRef, "method a.Derived::m overrides the MemberRef reflect.core.Object::finalize, which the module"
                + " does not list");
    }

    @Test
    public static void aTypeOverridesAMethodOnce() {
        ModuleDef once = derived(Flags.METHOD_VIRTUAL, INSTANCE_VOID);
        MethodDef m = once.types().get(1).methods().get(0);
        m.overrides().add(m.overrides().get(0));
        unwritable(once, "type a.Derived overrides method a.Base::m twice (BC-24)");
        // Two methods of a type do not fill one slot either.
        ModuleDef two = derived(Flags.METHOD_VIRTUAL, INSTANCE_VOID);
        TypeDef type = two.types().get(1);
        method(type, Flags.METHOD_VIRTUAL, "n", INSTANCE_VOID, type.methods().get(0).overrides().get(0));
        unwritable(two, "type a.Derived overrides method a.Base::m twice (BC-24)");
        ModuleDef refs = derived(Flags.METHOD_VIRTUAL, INSTANCE_VOID);
        MemberRef finalize = objectMember(refs, "finalize", INSTANCE_VOID);
        refs.types().get(1).methods().get(0).overrides().add(finalize);
        refs.types().get(1).methods().get(0).overrides().add(finalize);
        unwritable(refs, "type a.Derived overrides MemberRef reflect.core.Object::finalize twice (BC-24)");
    }

    @Test
    public static void theReaderRejectsInvalidOverrideRows() throws Exception {
        byte[] file = ModuleWriter.write(animals());
        int override = TableId.OVERRIDE;
        // Row 3 is Dog::speak over Pet::speak, and row 4 Dog::play over Pet::play.
        rejected(patch(file, override, 4, 2, 0x09000002), columnOffset(file, override, 4, 0),
                "the Override rows are not sorted by class and overridden method (BC-24)");
        rejected(patch(file, override, 4, 2, 0x09000006), columnOffset(file, override, 4, 0),
                "two Override rows have equal keys (BC-24)");
        reject(file, override, 1, 0, 0, "row 0 of table 0x5 does not exist");
        reject(file, override, 1, 0, 5, "row 5 of table 0x5 does not exist");
        reject(file, override, 1, 1, 0, "row 0 of table 0x9 does not exist");
        reject(file, override, 1, 1, 8, "row 8 of table 0x9 does not exist");
        reject(file, override, 1, 2, 0, "token 0x0 does not name a Method or MemberRef row");
        reject(file, override, 1, 2, 0x08000001, "token 0x8000001 does not name a Method or MemberRef row");
        reject(file, override, 1, 2, 0x09000008, "token 0x9000008 names row 8 of table 0x9, which does not exist");
        reject(file, override, 1, 2, 0x0F000002, "token 0xF000002 names row 2 of table 0xF, which does not exist");
    }

    @Test
    public static void theReaderChecksTheMethodsOfOverrideRows() throws Exception {
        byte[] file = ModuleWriter.write(animals());
        int override = TableId.OVERRIDE;
        // The runs of Method rows: a.Animal 1 and 2, a.Dog 3 and 4, a.Loud 5, a.Pet 6 and 7.
        reject(file, override, 1, 1, 3, "the Override row names method a.Dog::play, which is not a method of a.Animal (BC-11)");
        reject(file, override, 2, 1, 2, "the Override row names method a.Animal::speak, which is not a method of a.Dog (BC-11)");
        // Without VIRTUAL, Dog::play has no slot, so it cannot fill that of Pet::play in row 4.
        rejected(patch(file, TableId.METHOD, 3, 0, Flags.PUBLIC), columnOffset(file, override, 4, 1),
                "the Override row names method a.Dog::play, which is not a VIRTUAL or ABSTRACT instance method (BC-11)");
        reject(file, override, 2, 2, 0x09000003, "the Override row overrides method a.Dog::play of its own type (BC-11)");
        // Loud::speak, the row after the run of a.Dog, is a method of another type.
        ModuleReader.read(patch(file, override, 2, 2, 0x09000005));
        ModuleDef withField = animals();
        withField.memberRefs().add(new MemberRef(withField.typeRefs().get(1), "length", INT_FIELD));
        // reflect.core.String::length is MemberRef row 2.
        reject(ModuleWriter.write(withField), override, 1, 2, 0x0F000002, "the Override row overrides MemberRef"
                + " reflect.core.String::length, which is not a method (BC-11)");
    }

    // a.Pet extends the interface a.Loud, and a.Dog extends the abstract class a.Animal and implements a.Pet;
    // all are added out of order. Each method names the method that source overrides (Q97):
    // Animal::toString makes Object::toString abstract again, and Dog::speak names Pet::speak first.
    private static ModuleDef animals() {
        var module = new ModuleDef("a", 0, 0, 0);
        var core = new ModuleRef("reflect.core", 0);
        module.requires().add(core);
        var object = new TypeRef(core, "reflect.core", "Object");
        var string = new TypeRef(core, "reflect.core", "String");
        module.typeRefs().add(object);
        module.typeRefs().add(string);
        var toString = new MethodSig(0, 0, new TypeSig.Named(false, string), list());
        var objectToString = new MemberRef(object, "toString", toString);
        module.memberRefs().add(objectToString);
        var animal = new TypeDef("a", "Animal", Flags.KIND_CLASS, Flags.PUBLIC | Flags.TYPE_ABSTRACT, 0, null);
        var dog = new TypeDef("a", "Dog", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        var loud = new TypeDef("a", "Loud", Flags.KIND_INTERFACE, Flags.PUBLIC, 0, null);
        var pet = new TypeDef("a", "Pet", Flags.KIND_INTERFACE, Flags.PUBLIC, 0, null);
        dog.setBase(animal);
        dog.interfaces().add(pet);
        pet.interfaces().add(loud);
        method(animal, Flags.METHOD_VIRTUAL | Flags.METHOD_ABSTRACT, "toString", toString, objectToString);
        MethodDef animalSpeak = method(animal, Flags.METHOD_VIRTUAL, "speak", INSTANCE_VOID);
        MethodDef loudSpeak = method(loud, Flags.METHOD_ABSTRACT, "speak", INSTANCE_VOID);
        MethodDef petSpeak = method(pet, Flags.METHOD_ABSTRACT, "speak", INSTANCE_VOID, loudSpeak);
        MethodDef petPlay = method(pet, Flags.METHOD_ABSTRACT, "play", INSTANCE_VOID);
        method(dog, Flags.METHOD_VIRTUAL, "play", INSTANCE_VOID, petPlay);
        method(dog, Flags.METHOD_VIRTUAL, "speak", INSTANCE_VOID, petSpeak, animalSpeak);
        for (TypeDef type : new TypeDef[] {pet, dog, loud, animal}) {
            module.types().add(type);
        }
        return module;
    }

    // a.Base with virtual void m(), and a.Derived, which extends it, with a method m of these flags and
    // this signature that overrides Base::m.
    private static ModuleDef derived(int flags, MethodSig signature) {
        var module = new ModuleDef("a", 0, 0, 0);
        var base = new TypeDef("a", "Base", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        var derived = new TypeDef("a", "Derived", Flags.KIND_CLASS, Flags.PUBLIC, 0, null);
        derived.setBase(base);
        method(derived, flags, "m", signature, method(base, Flags.METHOD_VIRTUAL, "m", INSTANCE_VOID));
        module.types().add(base);
        module.types().add(derived);
        return module;
    }

    // A public method of the type that overrides these methods, with a body of ret unless it is abstract.
    private static MethodDef method(TypeDef type, int flags, String name, MethodSig signature,
            MethodHandle... overridden) {
        var method = new MethodDef(Flags.PUBLIC | flags, name, signature);
        if (Code.hasBody(flags)) {
            var body = new MethodBody(0);
            body.code().add(new Instruction.Plain(Opcode.RET));
            method.setBody(body);
        }
        for (MethodHandle handle : overridden) {
            method.overrides().add(handle);
        }
        type.methods().add(method);
        return method;
    }

    // A MemberRef of reflect.core.Object, which the module then requires and lists.
    private static MemberRef objectMember(ModuleDef module, String name, MemberSig signature) {
        var core = new ModuleRef("reflect.core", 0);
        var object = new TypeRef(core, "reflect.core", "Object");
        var ref = new MemberRef(object, name, signature);
        module.requires().add(core);
        module.typeRefs().add(object);
        module.memberRefs().add(ref);
        return ref;
    }

    // Each method that overrides others, followed by the methods that it overrides, in order.
    private static String overrides(ModuleDef module) {
        var text = new StringBuilder();
        for (TypeDef type : module.types()) {
            for (MethodDef method : type.methods()) {
                if (!method.overrides().isEmpty()) {
                    text.append(type.qualifiedName()).append("::").append(method.name());
                    for (MethodHandle overridden : method.overrides()) {
                        text.append(' ').append(name(module, overridden));
                    }
                    text.append("; ");
                }
            }
        }
        return text.toString();
    }

    // A method of the module, or a MemberRef, by its type and name.
    private static String name(ModuleDef module, MethodHandle method) {
        if (method instanceof MemberRef ref) {
            return ref.parent().qualifiedName() + "::" + ref.name();
        }
        for (TypeDef type : module.types()) {
            for (MethodDef other : type.methods()) {
                if (other == method) {
                    return type.qualifiedName() + "::" + other.name();
                }
            }
        }
        throw new AssertionError("no type of the module has the method " + ((MethodDef) method).name());
    }

    private static void reject(byte[] file, int table, int row, int column, int value, String message) throws Exception {
        rejected(patch(file, table, row, column, value), columnOffset(file, table, row, column), message);
    }
}
