package com.carddemo.batch.posttran.io;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Codec for signed zoned-decimal display fields ({@code PIC S9(n)V99}) whose sign is overpunched on
 * the last digit (spec section 4.7). Positive: {@code { A-I}, negative: {@code } J-R}; a plain digit
 * is treated as positive.
 */
public final class ZonedDecimal {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private ZonedDecimal() {
    }

    public static BigDecimal parse(String field) {
        return parse(field, 2);
    }

    public static BigDecimal parse(String field, int scale) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("Empty zoned decimal field");
        }
        char last = field.charAt(field.length() - 1);
        boolean negative;
        int lastDigit;
        int p;
        if ((p = POSITIVE.indexOf(last)) >= 0) {
            negative = false;
            lastDigit = p;
        } else if ((p = NEGATIVE.indexOf(last)) >= 0) {
            negative = true;
            lastDigit = p;
        } else if (last >= '0' && last <= '9') {
            negative = false;
            lastDigit = last - '0';
        } else {
            throw new IllegalArgumentException("Invalid zoned decimal sign byte '" + last + "' in '" + field + "'");
        }
        String head = field.substring(0, field.length() - 1);
        if (!head.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid zoned decimal digits in '" + field + "'");
        }
        BigInteger unscaled = new BigInteger(head + lastDigit);
        if (negative) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale);
    }

    /**
     * Formats {@code value} into {@code width} bytes using the value's own scale (2 for every field
     * in this program). Throws if the digits do not fit (COBOL would silently truncate; spec R1).
     */
    public static String format(BigDecimal value, int width) {
        BigInteger unscaled = value.unscaledValue();
        String digits = unscaled.abs().toString();
        if (digits.length() > width) {
            throw new ArithmeticException("Value " + value + " overflows S9(" + (width - value.scale()) + ")V" + "9".repeat(value.scale()));
        }
        digits = "0".repeat(width - digits.length()) + digits;
        int lastDigit = digits.charAt(width - 1) - '0';
        char sign = (unscaled.signum() < 0 ? NEGATIVE : POSITIVE).charAt(lastDigit);
        return digits.substring(0, width - 1) + sign;
    }
}
