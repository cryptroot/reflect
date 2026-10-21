package reflect.stage0.bytecode;

/**
 * Strict UTF-8 (RFC 3629) for IDENTS and DEBUG (BC-02), in R0-shaped Java (Q47): no overlong
 * form, no surrogate and nothing above U+10FFFF, so each string has exactly one encoding.
 */
public final class Utf8 {
    private Utf8() {
    }

    /** Encodes the text; an unpaired surrogate has no UTF-8 form, so it is an error. */
    public static byte[] encode(String text) throws ModuleFormatException {
        var sink = new ByteSink();
        int i = 0;
        while (i < text.length()) {
            int unit = text.charAt(i);
            int codePoint = unit;
            if (unit >= 0xD800 && unit <= 0xDFFF) {
                int next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                if (unit > 0xDBFF || next < 0xDC00 || next > 0xDFFF) {
                    throw new ModuleFormatException("unpaired surrogate " + hex(unit) + " at index " + i
                            + " has no UTF-8 form");
                }
                codePoint = 0x10000 + ((unit - 0xD800) << 10) + (next - 0xDC00);
                i++;
            }
            i++;
            if (codePoint < 0x80) {
                sink.u8(codePoint);
            } else if (codePoint < 0x800) {
                sink.u8(0xC0 | (codePoint >>> 6));
                sink.u8(0x80 | (codePoint & 0x3F));
            } else if (codePoint < 0x10000) {
                sink.u8(0xE0 | (codePoint >>> 12));
                sink.u8(0x80 | ((codePoint >>> 6) & 0x3F));
                sink.u8(0x80 | (codePoint & 0x3F));
            } else {
                sink.u8(0xF0 | (codePoint >>> 18));
                sink.u8(0x80 | ((codePoint >>> 12) & 0x3F));
                sink.u8(0x80 | ((codePoint >>> 6) & 0x3F));
                sink.u8(0x80 | (codePoint & 0x3F));
            }
        }
        return sink.toByteArray();
    }

    /** Decodes the next length bytes of the source; an error names the offset of its sequence. */
    public static String decode(ByteSource source, int length) throws ModuleFormatException {
        if (length < 0 || length > source.remaining()) {
            throw new ModuleFormatException(source.position(), "UTF-8 text of " + length
                    + " bytes runs past the end of its data");
        }
        int end = source.position() + length;
        var text = new StringBuilder();
        while (source.position() < end) {
            int start = source.position();
            int lead = source.u8();
            if (lead < 0x80) {
                text.append((char) lead);
                continue;
            }
            int count;
            int smallest;
            int codePoint;
            if (lead >= 0xC2 && lead <= 0xDF) {
                count = 1;
                smallest = 0x80;
                codePoint = lead & 0x1F;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                count = 2;
                smallest = 0x800;
                codePoint = lead & 0x0F;
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                count = 3;
                smallest = 0x10000;
                codePoint = lead & 0x07;
            } else {
                throw new ModuleFormatException(start, "invalid UTF-8 lead byte " + hex(lead));
            }
            for (int k = 0; k < count; k++) {
                if (source.position() == end) {
                    throw new ModuleFormatException(start, "truncated UTF-8 sequence");
                }
                int next = source.u8();
                if ((next & 0xC0) != 0x80) {
                    throw new ModuleFormatException(start, "invalid UTF-8 continuation byte " + hex(next));
                }
                codePoint = (codePoint << 6) | (next & 0x3F);
            }
            if (codePoint < smallest) {
                throw new ModuleFormatException(start, "overlong UTF-8 sequence");
            }
            if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
                throw new ModuleFormatException(start, "UTF-8 sequence encodes the surrogate " + hex(codePoint));
            }
            if (codePoint > 0x10FFFF) {
                throw new ModuleFormatException(start, "UTF-8 sequence encodes a value above U+10FFFF");
            }
            if (codePoint < 0x10000) {
                text.append((char) codePoint);
            } else {
                text.append((char) (0xD800 + ((codePoint - 0x10000) >>> 10)));
                text.append((char) (0xDC00 + ((codePoint - 0x10000) & 0x3FF)));
            }
        }
        return text.toString();
    }

    /**
     * Compares by code points, which is the order of the UTF-8 bytes (BC-11) and differs from
     * String.compareTo when a surrogate pair meets a unit from U+E000 to U+FFFF.
     */
    public static int compare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = codePoint(left, i);
            int b = codePoint(right, j);
            if (a != b) {
                return a < b ? -1 : 1;
            }
            i += a >= 0x10000 ? 2 : 1;
            j += b >= 0x10000 ? 2 : 1;
        }
        return i < left.length() ? 1 : j < right.length() ? -1 : 0;
    }

    private static int codePoint(String text, int index) {
        int unit = text.charAt(index);
        if (unit >= 0xD800 && unit <= 0xDBFF && index + 1 < text.length()) {
            int next = text.charAt(index + 1);
            if (next >= 0xDC00 && next <= 0xDFFF) {
                return 0x10000 + ((unit - 0xD800) << 10) + (next - 0xDC00);
            }
        }
        return unit;
    }

    private static String hex(int value) {
        return ModuleFormatException.hex(value);
    }
}
