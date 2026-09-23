package com.carddemo.batch.posttran.io;

/** Helpers for COBOL PIC X(n) / PIC 9(n) fixed-width fields. */
public final class FixedWidth {

    private FixedWidth() {
    }

    /** PIC X(n): left-justified, space padded, truncated on the right. */
    public static String padRight(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() > width) {
            return v.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width).append(v);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** PIC 9(n) unsigned display: zero padded. */
    public static String zeroPad(long value, int width) {
        if (value < 0) {
            throw new IllegalArgumentException("Unsigned field cannot hold " + value);
        }
        String s = Long.toString(value);
        if (s.length() > width) {
            throw new ArithmeticException("Value " + value + " does not fit in 9(" + width + ")");
        }
        return "0".repeat(width - s.length()) + s;
    }

    /** 1-based COBOL offset slice: bytes {@code offset .. offset+len-1}. */
    public static String slice(String record, int offset, int len) {
        return record.substring(offset - 1, offset - 1 + len);
    }

    /** Parses a PIC 9(n) field; anything other than n digits is a malformed record (fail fast, spec Q6/R10). */
    public static long parseUnsigned(String field, String fieldName) {
        if (field.isEmpty() || !field.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Field " + fieldName + " is not numeric: '" + field + "'");
        }
        return Long.parseLong(field);
    }

    /** Right-pads a text line (with any trailing CR removed) to the copybook record length. */
    public static String normalizeLine(String line, int recordLength) {
        String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        if (l.length() > recordLength) {
            throw new IllegalArgumentException("Record longer than " + recordLength + ": " + l.length());
        }
        return padRight(l, recordLength);
    }
}
