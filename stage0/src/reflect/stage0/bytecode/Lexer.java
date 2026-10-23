package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;

/**
 * Splits .rfla text into tokens (§8.1): words, directives, numbers, strings and symbols, then one
 * token at the end. Spaces, tabs, line ends and // comments separate tokens; a line ends at LF,
 * CRLF or CR. Lines and columns count code points from 1, a tab as one (Q79). A word is an
 * identifier, or identifiers joined by dots, such as ldc.i4 and reflect.core.String; keywords are
 * words, which the parser tells apart by their place, and an @ before a word makes it a name that
 * matches no keyword (Q82). A number runs from a digit, or from a minus before one, over letters,
 * digits, underscores and dots, and a sign after an exponent; the parser reads it as an Int, a Real
 * or a Version.
 */
final class Lexer {
    private static final int BYTE_ORDER_MARK = 0xFEFF;

    private final String text;
    private final List<Token> tokens = new ArrayList<>();
    private int index;
    private int line = 1;
    private int column = 1;

    private Lexer(String text) {
        this.text = text;
    }

    /** The tokens of UTF-8 text; a byte order mark at its start is ignored (LEX-01). */
    static List<Token> tokens(byte[] utf8) throws AssemblyException {
        String text;
        try {
            text = Utf8.decode(new ByteSource(utf8), utf8.length);
        } catch (ModuleFormatException defect) {
            throw undecodable(utf8, defect);
        }
        return tokens(text);
    }

    static List<Token> tokens(String text) throws AssemblyException {
        var lexer = new Lexer(text);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            lexer.index = 1;
        }
        lexer.run();
        return lexer.tokens;
    }

    private void run() throws AssemblyException {
        while (index < text.length()) {
            int c = text.codePointAt(index);
            if (c == ' ' || c == '\t') {
                advance();
            } else if (c == '\n' || c == '\r') {
                lineEnd();
            } else if (text.startsWith("//", index)) {
                while (index < text.length() && text.charAt(index) != '\n' && text.charAt(index) != '\r') {
                    advance();
                }
            } else if (identifierStart(c)) {
                word(false);
            } else if (c == '@') {
                escapedWord();
            } else if (c == '.') {
                dot();
            } else if (c >= '0' && c <= '9') {
                number();
            } else if (c == '-') {
                minus();
            } else if (c == '"') {
                string();
            } else {
                symbol(c);
            }
        }
        tokens.add(new Token(Token.END, "", false, line, column));
    }

    // An identifier, then each identifier after a dot that joins it; after @ the word is a name (Q82).
    private void word(boolean escaped) {
        int startColumn = escaped ? column - 1 : column;
        int start = index;
        identifier();
        while (index + 1 < text.length() && text.charAt(index) == '.' && identifierStart(text.codePointAt(index + 1))) {
            advance();
            identifier();
        }
        tokens.add(new Token(Token.WORD, text.substring(start, index), escaped, line, startColumn));
    }

    private void escapedWord() throws AssemblyException {
        if (index + 1 == text.length() || !identifierStart(text.codePointAt(index + 1))) {
            throw new AssemblyException(Diagnostic.CHARACTER, line, column, "an @ that starts no name");
        }
        advance();
        word(true);
    }

    private void identifier() {
        advance();
        while (index < text.length() && identifierPart(text.codePointAt(index))) {
            advance();
        }
    }

    // A directive, or the "..." of a variadic parameter.
    private void dot() throws AssemblyException {
        int start = index;
        int startColumn = column;
        if (text.startsWith("...", index)) {
            advance();
            advance();
            advance();
            tokens.add(new Token(Token.SYMBOL, "...", false, line, startColumn));
        } else if (index + 1 < text.length() && identifierStart(text.codePointAt(index + 1))) {
            advance();
            identifier();
            tokens.add(new Token(Token.DIRECTIVE, text.substring(start, index), false, line, startColumn));
        } else {
            throw new AssemblyException(Diagnostic.CHARACTER, line, column, "a dot that starts no directive");
        }
    }

    // A sign follows an exponent, so it never follows the digit e of a hexadecimal number.
    private void number() {
        int start = index;
        int startColumn = column;
        if (text.charAt(index) == '-') {
            advance();
        }
        boolean hexadecimal = text.startsWith("0x", index);
        advance();
        while (index < text.length()) {
            char c = text.charAt(index);
            char previous = text.charAt(index - 1);
            boolean sign = (c == '+' || c == '-') && (previous == 'e' || previous == 'E') && !hexadecimal;
            if (!(asciiLetterOrDigit(c) || c == '_' || c == '.' || sign)) {
                break;
            }
            advance();
        }
        tokens.add(new Token(Token.NUMBER, text.substring(start, index), false, line, startColumn));
    }

    // A minus starts a number, or the word -inf of a Real.
    private void minus() throws AssemblyException {
        if (index + 1 < text.length() && text.charAt(index + 1) >= '0' && text.charAt(index + 1) <= '9') {
            number();
        } else if (text.startsWith("-inf", index)
                && (index + 4 == text.length() || !identifierPart(text.codePointAt(index + 4)))) {
            int startColumn = column;
            for (int i = 0; i < 4; i++) {
                advance();
            }
            tokens.add(new Token(Token.WORD, "-inf", false, line, startColumn));
        } else {
            throw new AssemblyException(Diagnostic.CHARACTER, line, column, "a minus that starts no number");
        }
    }

    private void string() throws AssemblyException {
        int startColumn = column;
        advance();
        var value = new StringBuilder();
        boolean escaped = false;
        while (true) {
            if (index == text.length() || text.charAt(index) == '\n' || text.charAt(index) == '\r') {
                throw new AssemblyException(Diagnostic.UNCLOSED, line, startColumn, "the string does not end on its line");
            }
            char c = text.charAt(index);
            if (c == '"') {
                advance();
                tokens.add(new Token(Token.STRING, value.toString(), escaped, line, startColumn));
                return;
            }
            if (c == '\\') {
                escaped = true;
                escape(value);
            } else {
                value.appendCodePoint(text.codePointAt(index));
                advance();
            }
        }
    }

    // One escape of §8.1. The escape with u gives one UTF-16 code unit, so it can give an unpaired
    // surrogate, which LITERALS allows (BC-02).
    private void escape(StringBuilder value) throws AssemblyException {
        char next = index + 1 < text.length() ? text.charAt(index + 1) : 0;
        int unit = switch (next) {
            case '"' -> '"';
            case '\\' -> '\\';
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '0' -> 0;
            case 'u' -> hexUnit(index + 2);
            default -> -1;
        };
        if (unit < 0) {
            String found = next == 'u' ? "\\u without four hexadecimal digits"
                    : next == 0 || next == '\n' || next == '\r' ? "a backslash at the end of the line" : "\\" + next;
            throw new AssemblyException(Diagnostic.ESCAPE, line, column, found + " is not an escape (BC-08)");
        }
        value.append((char) unit);
        int length = next == 'u' ? 6 : 2;
        for (int i = 0; i < length; i++) {
            advance();
        }
    }

    // The code unit that four hexadecimal digits at the index give, or -1.
    private int hexUnit(int at) {
        if (at + 4 > text.length()) {
            return -1;
        }
        int unit = 0;
        for (int i = at; i < at + 4; i++) {
            int digit = hexDigit(text.charAt(i));
            if (digit < 0) {
                return -1;
            }
            unit = unit * 16 + digit;
        }
        return unit;
    }

    private void symbol(int c) throws AssemblyException {
        String symbol = switch (c) {
            case '{', '}', '(', ')', '[', ']', '<', '>', ',', '?', '*', '&', '=', '/' -> String.valueOf((char) c);
            case ':' -> text.startsWith("::", index) ? "::" : ":";
            case '!' -> text.startsWith("!!", index) ? "!!" : "!";
            default -> "";
        };
        if (symbol.isEmpty()) {
            throw new AssemblyException(Diagnostic.CHARACTER, line, column, "the character " + describe(c)
                    + " starts no token");
        }
        int startColumn = column;
        for (int i = 0; i < symbol.length(); i++) {
            advance();
        }
        tokens.add(new Token(Token.SYMBOL, symbol, false, line, startColumn));
    }

    private void advance() {
        index += Character.charCount(text.codePointAt(index));
        column++;
    }

    private void lineEnd() {
        if (text.startsWith("\r\n", index)) {
            index++;
        }
        index++;
        line++;
        column = 1;
    }

    // The bytes before the defect are UTF-8, so its column counts their lead bytes after the last
    // line end, and a byte order mark at the start counts for nothing.
    private static AssemblyException undecodable(byte[] utf8, ModuleFormatException defect) {
        int line = 1;
        int column = 1;
        boolean mark = utf8.length >= 3 && (utf8[0] & 0xFF) == 0xEF && (utf8[1] & 0xFF) == 0xBB
                && (utf8[2] & 0xFF) == 0xBF;
        for (int i = mark ? 3 : 0; i < defect.offset(); i++) {
            int b = utf8[i] & 0xFF;
            if (b == '\n' || b == '\r' && (i + 1 == utf8.length || utf8[i + 1] != '\n')) {
                line++;
                column = 1;
            } else if (b != '\r' && (b & 0xC0) != 0x80) {
                column++;
            }
        }
        String message = defect.getMessage();
        return new AssemblyException(Diagnostic.ENCODING, line, column, "the file is not UTF-8: "
                + message.substring(message.indexOf(": ") + 2));
    }

    /** IdStart of §8.1, Unicode ID_Start (LEX-02), with the _ and $ of .rfla names. */
    static boolean identifierStart(int c) {
        return c == '_' || c == '$' || Character.isUnicodeIdentifierStart(c);
    }

    /** IdPart of §8.1: Unicode ID_Continue, which Java gives without the characters it ignores, and $. */
    static boolean identifierPart(int c) {
        return c == '$' || Character.isUnicodeIdentifierPart(c) && !Character.isIdentifierIgnorable(c);
    }

    /** Whether the text is an Ident of §8.1, which the lexer reads as one word without a dot (Q83). */
    static boolean isIdentifier(String text) {
        if (text.isEmpty() || !identifierStart(text.codePointAt(0))) {
            return false;
        }
        for (int i = Character.charCount(text.codePointAt(0)); i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            if (!identifierPart(text.codePointAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the text is a QName of §8.1: identifiers joined by dots (Q83). */
    static boolean isQualifiedName(String text) {
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '.') {
                if (!isIdentifier(text.substring(start, i))) {
                    return false;
                }
                start = i + 1;
            }
        }
        return true;
    }

    static int hexDigit(char c) {
        return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    }

    private static boolean asciiLetterOrDigit(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    // A printable ASCII character as itself, any other as U+ and at least four hexadecimal digits.
    private static String describe(int c) {
        if (c > 0x20 && c < 0x7F) {
            return String.valueOf((char) c);
        }
        String digits = ModuleFormatException.hex(c).substring(2);
        while (digits.length() < 4) {
            digits = "0" + digits;
        }
        return "U+" + digits;
    }
}
