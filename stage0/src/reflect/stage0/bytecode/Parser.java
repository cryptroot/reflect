package reflect.stage0.bytecode;

import reflect.stage0.lib.ArrayList;
import reflect.stage0.lib.List;
import reflect.stage0.lib.Nullable;

/**
 * A cursor over the tokens of .rfla text, with the rules of §8.1 that read one token or a few:
 * names, integers and versions, strings and hexadecimal bytes. A number token is read as the rule
 * at its place requires, and a value outside the range of its place is a diagnostic (Q79).
 */
final class Parser {
    // The largest magnitude, 2^64 - 1 as unsigned, divided by 10.
    private static final long DECIMAL_LIMIT = 1_844_674_407_370_955_161L;

    private final List<Token> tokens;
    private int position;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    Token peek() {
        return tokens.get(position);
    }

    /** The token after the next one, or the last token, which ends the file. */
    Token peekSecond() {
        return tokens.get(position + 1 < tokens.size() ? position + 1 : position);
    }

    Token next() {
        Token token = tokens.get(position);
        if (token.kind() != Token.END) {
            position++;
        }
        return token;
    }

    int position() {
        return position;
    }

    void seek(int position) {
        this.position = position;
    }

    /** Whether the next token is the word or the symbol. */
    boolean at(String word) {
        return peek().is(word);
    }

    boolean atDirective(String name) {
        return peek().kind() == Token.DIRECTIVE && peek().text().equals(name);
    }

    boolean atKind(int kind) {
        return peek().kind() == kind;
    }

    /** Takes the next token if it is the word or the symbol. */
    boolean accept(String word) {
        if (at(word)) {
            next();
            return true;
        }
        return false;
    }

    Token expect(String word) throws AssemblyException {
        if (!at(word)) {
            throw unexpected(word);
        }
        return next();
    }

    /** A QName: a word that is an identifier, or identifiers joined by dots. */
    Token qualifiedName(String what) throws AssemblyException {
        if (!atKind(Token.WORD) || peek().text().startsWith("-")) {
            throw unexpected(what);
        }
        return next();
    }

    /** An Ident: a word without a dot. */
    Token identifier(String what) throws AssemblyException {
        if (!atKind(Token.WORD) || peek().text().startsWith("-") || peek().text().indexOf('.') >= 0) {
            throw unexpected(what);
        }
        return next();
    }

    /** TypeName of §8.1: a QName, then an identifier after each "/". */
    TypeName typeName(String what) throws AssemblyException {
        Token token = qualifiedName(what);
        var nested = new ArrayList<String>();
        while (accept("/")) {
            nested.add(identifier("the name of a nested type").text());
        }
        return new TypeName(token, nested);
    }

    String string(String what) throws AssemblyException {
        if (!atKind(Token.STRING)) {
            throw unexpected(what);
        }
        return next().text();
    }

    /** Hex of §8.1: the word hex, then a string of pairs of hexadecimal digits without escapes. */
    byte[] hex() throws AssemblyException {
        expect("hex");
        Token token = peek();
        String digits = string("a string of hexadecimal digits");
        boolean pairs = !token.escaped() && digits.length() % 2 == 0;
        for (int i = 0; i < digits.length() && pairs; i++) {
            pairs = Lexer.hexDigit(digits.charAt(i)) >= 0;
        }
        if (!pairs) {
            throw error(Diagnostic.SYNTAX, token, "a hex string holds pairs of hexadecimal digits");
        }
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (Lexer.hexDigit(digits.charAt(2 * i)) * 16 + Lexer.hexDigit(digits.charAt(2 * i + 1)));
        }
        return bytes;
    }

    /** An Int from 0 to 2^32 - 1, the range of a u32 field, as its bits. */
    int u32(String what) throws AssemblyException {
        Token token = number(what);
        Magnitude value = integer(token, what);
        if (!value.fits(false, 0xFFFF_FFFFL)) {
            throw outside(token, what, "0", "4294967295");
        }
        return (int) value.bits();
    }

    /** An Int from -2^31 to 2^31 - 1. */
    int i32(String what) throws AssemblyException {
        Token token = number(what);
        Magnitude value = integer(token, what);
        if (!value.fits(true, 0x7FFF_FFFFL)) {
            throw outside(token, what, "-2147483648", "2147483647");
        }
        return (int) value.bits();
    }

    /** An Int from -2^63 to 2^63 - 1. */
    long i64(String what) throws AssemblyException {
        Token token = number(what);
        Magnitude value = integer(token, what);
        if (!value.fits(true, Long.MAX_VALUE)) {
            throw outside(token, what, "-9223372036854775808", "9223372036854775807");
        }
        return value.bits();
    }

    /** Version of §8.1: three decimal u32 values joined by dots. */
    int[] version() throws AssemblyException {
        Token token = number("a version");
        var parts = new ArrayList<String>();
        String text = token.text();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '.') {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        boolean digits = parts.size() == 3;
        for (int i = 0; i < parts.size() && digits; i++) {
            digits = !parts.get(i).isEmpty() && decimal(parts.get(i));
        }
        if (!digits) {
            throw error(Diagnostic.SYNTAX, token, "expected a version of three numbers, such as 1.2.0, found " + text);
        }
        int[] version = new int[3];
        for (int i = 0; i < 3; i++) {
            var magnitude = new Magnitude(false, 0);
            for (int k = 0; k < parts.get(i).length() && magnitude != null; k++) {
                magnitude = magnitude.append(10, parts.get(i).charAt(k) - '0');
            }
            if (magnitude == null || !magnitude.fits(false, 0xFFFF_FFFFL)) {
                throw outside(token, "a version number", "0", "4294967295");
            }
            version[i] = (int) magnitude.bits();
        }
        return version;
    }

    AssemblyException unexpected(String expected) {
        return error(Diagnostic.SYNTAX, peek(), "expected " + expected + ", found " + peek().describe());
    }

    static AssemblyException error(int code, Token at, String message) {
        return new AssemblyException(code, at.line(), at.column(), message);
    }

    private Token number(String what) throws AssemblyException {
        if (!atKind(Token.NUMBER)) {
            throw unexpected(what);
        }
        return next();
    }

    // Int of §8.1: a minus, then decimal digits, or 0x and hexadecimal digits.
    private static Magnitude integer(Token token, String what) throws AssemblyException {
        String text = token.text();
        boolean negative = text.startsWith("-");
        String digits = negative ? text.substring(1) : text;
        int base = 10;
        if (digits.startsWith("0x")) {
            base = 16;
            digits = digits.substring(2);
        }
        boolean valid = !digits.isEmpty();
        for (int i = 0; i < digits.length() && valid; i++) {
            valid = base == 16 ? Lexer.hexDigit(digits.charAt(i)) >= 0 : decimal(digits.substring(i, i + 1));
        }
        if (!valid) {
            throw error(Diagnostic.SYNTAX, token, "expected " + what + ", found " + text);
        }
        var magnitude = new Magnitude(negative, 0);
        for (int i = 0; i < digits.length(); i++) {
            magnitude = magnitude.append(base, Lexer.hexDigit(digits.charAt(i)));
            if (magnitude == null) {
                throw error(Diagnostic.RANGE, token, text + " does not fit 64 bits");
            }
        }
        return magnitude;
    }

    private static boolean decimal(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static AssemblyException outside(Token token, String what, String lowest, String highest) {
        return error(Diagnostic.RANGE, token, token.text() + " is outside the range of " + what + ", from " + lowest
                + " to " + highest);
    }

    /** A sign and the bits of a magnitude from 0 to 2^64 - 1, read as unsigned. */
    private record Magnitude(boolean negative, long magnitude) {
        // The magnitude with one more digit, or null when it passes 2^64 - 1.
        @Nullable Magnitude append(int base, int digit) {
            boolean over = base == 16 ? magnitude >>> 60 != 0
                    : magnitude < 0 || magnitude > DECIMAL_LIMIT || magnitude == DECIMAL_LIMIT && digit > 5;
            return over ? null : new Magnitude(negative, magnitude * base + digit);
        }

        // Whether the value lies from -largest - 1, when negative values are allowed, or from 0, to largest.
        boolean fits(boolean signed, long largest) {
            if (!negative || magnitude == 0) {
                return magnitude >= 0 && magnitude <= largest;
            }
            return signed && (magnitude >= 0 && magnitude <= largest || magnitude == largest + 1);
        }

        long bits() {
            return negative ? -magnitude : magnitude;
        }
    }
}
