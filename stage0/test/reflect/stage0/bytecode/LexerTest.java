package reflect.stage0.bytecode;

import reflect.stage0.lib.List;
import reflect.stage0.test.Test;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertThrows;
import static reflect.stage0.test.Assertions.assertTrue;

public final class LexerTest {
    @Test
    public static void tokensKeepTheirKindTextAndPosition() throws AssemblyException {
        String text = ".module demo 0.1.0\n"
                + ".class public demo.zoo.Enclosure/Tag {\n"
                + "    ldc.i4 -1 // a comment: \"not a string\n"
                + "    call static void [m]a.B::f(int[]...)\n"
                + "}\n";
        assertEquals("D.module@1:1 Wdemo@1:9 N0.1.0@1:14 "
                + "D.class@2:1 Wpublic@2:8 Wdemo.zoo.Enclosure@2:15 Y/@2:33 WTag@2:34 Y{@2:38 "
                + "Wldc.i4@3:5 N-1@3:12 "
                + "Wcall@4:5 Wstatic@4:10 Wvoid@4:17 Y[@4:22 Wm@4:23 Y]@4:24 Wa.B@4:25 Y::@4:28 Wf@4:30 Y(@4:31 "
                + "Wint@4:32 Y[@4:35 Y]@4:36 Y...@4:37 Y)@4:40 "
                + "Y}@5:1 E@6:1", describe(Lexer.tokens(text)));
        assertEquals("Y!!@1:1 N0@1:3 Y!@1:5 N1@1:6 Y:@1:8 Y<@1:10 Y?@1:11 Y>@1:12 Y&@1:14 Y*@1:15 Y=@1:16 Y,@1:17 E@1:18",
                describe(Lexer.tokens("!!0 !1 : <?> &*=,")));
    }

    @Test
    public static void linesEndAtLfCrlfAndCrAndColumnsCountCodePoints() throws AssemblyException {
        assertEquals("Wa@1:1 Wb@2:1 Wc@3:1 Wd@4:1 E@4:2", describe(Lexer.tokens("a\nb\r\nc\rd")));
        // U+10000 takes two UTF-16 code units and one column; a tab is one column.
        String wide = new String(Character.toChars(0x10000));
        assertEquals("S" + wide + "@1:1 Wx@1:5 Wy@2:2 E@2:3", describe(Lexer.tokens("\"" + wide + "\" x\n\ty")));
        assertEquals("Wx@1:1 E@1:2", describe(Lexer.tokens(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x'})));
        assertEquals("E@1:1", describe(Lexer.tokens("")));
        assertEquals("E@2:1", describe(Lexer.tokens("// only a comment\n")));
    }

    @Test
    public static void wordsTakeUnicodeIdentifiersUnderscoresAndDollars() throws AssemblyException {
        String e = String.valueOf((char) 0xE9);
        assertEquals("W_a$1@1:1 W$x.y$@1:6 W" + e + "t" + e + "@1:12 Wbyref.readonly@1:16 W-inf@1:31 Winf@1:36 E@1:39",
                describe(Lexer.tokens("_a$1 $x.y$ " + e + "t" + e + " byref.readonly -inf inf")));
    }

    @Test
    public static void numbersRunOverLettersDigitsDotsAndExponentSigns() throws AssemblyException {
        // A sign after the digit e of a hexadecimal number starts another number.
        assertEquals("N0x1F@1:1 N-0x80@1:6 N1.5e-3@1:12 N0.1.0@1:19 N1E+5@1:25 N0x1e@1:30 N-5@1:34 N1_000@1:37 "
                + "Y)@1:42 E@1:43", describe(Lexer.tokens("0x1F -0x80 1.5e-3 0.1.0 1E+5 0x1e-5 1_000)")));
    }

    @Test
    public static void stringsHoldTheirEscapesAsCodeUnits() throws AssemblyException {
        List<Token> tokens = Lexer.tokens("\"a\\\"b\\\\c\\n\\t\\r\\0\\u0041\\uD800\" \"plain\"");
        // The last escape gives an unpaired surrogate, which LITERALS allows.
        assertEquals("a\"b\\c\n\t\r" + (char) 0 + "A" + (char) 0xD800, tokens.get(0).text());
        assertTrue(tokens.get(0).escaped());
        assertEquals("plain", tokens.get(1).text());
        assertTrue(!tokens.get(1).escaped());
    }

    @Test
    public static void eachDefectHasItsCodeAndPosition() {
        defect("ret #", "1:5: RFA0001: the character # starts no token");
        defect("a" + (char) 0xA0 + "b", "1:2: RFA0001: the character U+00A0 starts no token");
        // U+200B is a format character, which ID_Continue leaves out.
        defect("a" + (char) 0x200B + "b", "1:2: RFA0001: the character U+200B starts no token");
        defect("a.1", "1:2: RFA0001: a dot that starts no directive");
        defect("x - 1", "1:3: RFA0001: a minus that starts no number");
        defect("ldstr \"abc\nret", "1:7: RFA0002: the string does not end on its line");
        defect("ldstr \"abc", "1:7: RFA0002: the string does not end on its line");
        defect("\"a\\x\"", "1:3: RFA0003: \\x is not an escape (BC-08)");
        defect("\"\\u12\"", "1:2: RFA0003: \\u without four hexadecimal digits is not an escape (BC-08)");
        defect("\"a\\", "1:3: RFA0003: a backslash at the end of the line is not an escape (BC-08)");
        var error = assertThrows(AssemblyException.class, () -> Lexer.tokens(new byte[] {'a', '\r', '\n', 'b',
            (byte) 0xC3, (byte) 0xA9, (byte) 0xFF}));
        assertEquals("2:3: RFA0004: the file is not UTF-8: invalid UTF-8 lead byte 0xFF", error.getMessage());
        assertEquals(Diagnostic.ENCODING + " " + error.line() + " " + error.column(), error.code() + " 2 3");
    }

    private static void defect(String text, String message) {
        assertEquals(message, assertThrows(AssemblyException.class, () -> Lexer.tokens(text)).getMessage());
    }

    // Each token as a letter for its kind, its text, and its line and column.
    static String describe(List<Token> tokens) {
        String kinds = "WDNSYE";
        var text = new StringBuilder();
        for (Token token : tokens) {
            text.append(text.length() == 0 ? "" : " ").append(kinds.charAt(token.kind())).append(token.text())
                    .append('@').append(token.line()).append(':').append(token.column());
        }
        return text.toString();
    }
}
