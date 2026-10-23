package reflect.stage0.bytecode;

/**
 * A token of .rfla text (§8.1): its kind, its text, whether a string held an escape or a word followed
 * an @, and the line and column where it starts. A word is an identifier or a dotted name, the keywords
 * among them unless it follows an @ (Q82); a directive keeps its dot; a number keeps its text for the
 * parser; a string holds its value.
 */
record Token(int kind, String text, boolean escaped, int line, int column) {
    static final int WORD = 0;
    static final int DIRECTIVE = 1;
    static final int NUMBER = 2;
    static final int STRING = 3;
    static final int SYMBOL = 4;
    static final int END = 5;

    /** Whether the token is the word or the symbol; a word after @ is a name, never a keyword. */
    boolean is(String word) {
        return (kind == WORD && !escaped || kind == SYMBOL) && text.equals(word);
    }

    /** The token as a message names it. */
    String describe() {
        return kind == STRING ? "a string" : kind == END ? "the end of the file" : kind == WORD && escaped ? "@" + text
                : text;
    }
}
