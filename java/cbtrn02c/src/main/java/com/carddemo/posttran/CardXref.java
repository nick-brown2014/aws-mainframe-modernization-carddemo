package com.carddemo.posttran;

public record CardXref(String cardNum, String custId, String acctId, String filler) {
    public static CardXref fromRecord(String raw) {
        FixedWidth.Cursor fields = new FixedWidth.Cursor(raw, 50);
        return new CardXref(fields.take(16), fields.numeric(9), fields.numeric(11), fields.take(14));
    }
}
