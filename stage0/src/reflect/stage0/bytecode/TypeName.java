package reflect.stage0.bytecode;

import reflect.stage0.lib.List;

/**
 * A TypeName of §8.1: a top-level type by its qualified name, whose last dotted segment is the
 * name, then each nested type after "/" (GEN-12, Q70).
 */
record TypeName(Token token, List<String> nested) {
    String namespace() {
        int dot = token.text().lastIndexOf('.');
        return dot < 0 ? "" : token.text().substring(0, dot);
    }

    /** The simple name of the top-level type. */
    String name() {
        return token.text().substring(token.text().lastIndexOf('.') + 1);
    }

    /** The spelling of GEN-12, which qualifiedName of a TypeDef or a TypeRef gives too. */
    String qualified() {
        var text = new StringBuilder(token.text());
        for (String name : nested) {
            text.append('/').append(name);
        }
        return text.toString();
    }
}
