package com.carddemo.posttran.cobol;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Conversions between COBOL DISPLAY (zoned decimal / alphanumeric) fields and Java types.
 *
 * <p>Zoned numerics (PIC 9(n) / PIC S9(n)V99) are stored as ASCII digits. Signed fields carry the
 * sign as an overpunch on the last digit; the ASCII data shipped with CardDemo uses the EBCDIC
 * overpunch characters transliterated to ASCII: {@code '{'} = +0, {@code 'A'..'I'} = +1..+9,
 * {@code '}'} = -0, {@code 'J'..'R'} = -1..-9. A plain trailing digit is positive.
 */
public final class CobolFields {

    private CobolFields() {
    }

    /** Parses a zoned decimal field with {@code scale} implied decimal places (the V in PIC S9(n)V99). */
    public static BigDecimal parseZoned(String field, int scale) {
        String s = field == null ? "" : field.strip();
        if (s.isEmpty()) {
            return BigDecimal.ZERO.setScale(scale);
        }
        boolean negative = false;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            negative = s.charAt(0) == '-';
            s = s.substring(1);
        }
        char last = s.charAt(s.length() - 1);
        int lastDigit;
        if (last >= '0' && last <= '9') {
            lastDigit = last - '0';
        } else if (last == '{') {
            lastDigit = 0;
        } else if (last == '}') {
            lastDigit = 0;
            negative = true;
        } else if (last >= 'A' && last <= 'I') {
            lastDigit = last - 'A' + 1;
        } else if (last >= 'J' && last <= 'R') {
            lastDigit = last - 'J' + 1;
            negative = true;
        } else {
            throw new IllegalArgumentException("Invalid zoned decimal field: '" + field + "'");
        }
        String body = s.substring(0, s.length() - 1);
        if (!body.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid zoned decimal field: '" + field + "'");
        }
        BigInteger unscaled = new BigInteger(body + lastDigit);
        if (negative) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale);
    }

    /** Parses an unsigned PIC 9(n) field. */
    public static long parseUnsigned(String field) {
        return parseZoned(field, 0).longValueExact();
    }

    /**
     * Formats a value as a zoned decimal of {@code digits} total digits (excluding the sign) with
     * {@code scale} implied decimals. Negative values use the trailing overpunch convention.
     */
    public static String formatZoned(BigDecimal value, int digits, int scale, boolean signed) {
        BigDecimal scaled = value.setScale(scale, java.math.RoundingMode.UNNECESSARY);
        BigInteger unscaled = scaled.unscaledValue();
        boolean negative = unscaled.signum() < 0;
        if (negative && !signed) {
            throw new IllegalArgumentException("Negative value in unsigned field: " + value);
        }
        String body = unscaled.abs().toString();
        if (body.length() > digits) {
            body = body.substring(body.length() - digits);
        }
        body = "0".repeat(digits - body.length()) + body;
        if (!negative) {
            return body;
        }
        int lastDigit = body.charAt(body.length() - 1) - '0';
        char overpunch = lastDigit == 0 ? '}' : (char) ('J' + lastDigit - 1);
        return body.substring(0, body.length() - 1) + overpunch;
    }

    /** Formats an unsigned PIC 9(n) field. */
    public static String formatUnsigned(long value, int digits) {
        return formatZoned(BigDecimal.valueOf(value), digits, 0, false);
    }

    /** Pads/truncates to a PIC X(n) field (left-justified, space filled), as a COBOL MOVE would. */
    public static String alnum(String value, int length) {
        String s = value == null ? "" : value;
        if (s.length() >= length) {
            return s.substring(0, length);
        }
        return s + " ".repeat(length - s.length());
    }

    /** Right-trims trailing spaces of a PIC X field. */
    public static String rtrim(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }
}
