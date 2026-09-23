package com.carddemo.posttran;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

public final class FixedWidth {
    private FixedWidth() {
    }

    public static void requireLength(String record, int length) {
        if (record.length() != length || record.getBytes(StandardCharsets.US_ASCII).length != length
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(record)) {
            throw new IllegalArgumentException("Expected " + length + " ASCII bytes, got " + record.length());
        }
    }

    public static BigDecimal signedDecimal(String value) {
        char last = value.charAt(value.length() - 1);
        boolean negative = last == '}' || last >= 'J' && last <= 'R';
        char digit = last == '{' || last == '}' ? '0'
                : last >= 'A' && last <= 'I' ? (char) ('1' + last - 'A')
                : last >= 'J' && last <= 'R' ? (char) ('1' + last - 'J') : last;
        String digits = value.substring(0, value.length() - 1) + digit;
        if (!digits.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid signed display decimal");
        }
        return new BigDecimal((negative ? "-" : "") + digits).movePointLeft(2);
    }

    public static final class Cursor {
        private final String record;
        private int offset;

        public Cursor(String record, int length) {
            requireLength(record, length);
            this.record = record;
        }

        public String take(int length) {
            String value = record.substring(offset, offset + length);
            offset += length;
            return value;
        }

        public String numeric(int length) {
            String value = take(length);
            if (!value.matches("[0-9]+")) {
                throw new IllegalArgumentException("Invalid PIC 9(" + length + ") at offset " + (offset - length));
            }
            return value;
        }

        public BigDecimal decimal(int integerDigits) {
            return signedDecimal(take(integerDigits + 2));
        }
    }
}
