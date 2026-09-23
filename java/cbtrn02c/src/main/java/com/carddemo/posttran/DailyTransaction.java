package com.carddemo.posttran;

import java.math.BigDecimal;

public record DailyTransaction(
        String id, String typeCd, String catCd, String source, String description,
        BigDecimal amount, String merchantId, String merchantName, String merchantCity,
        String merchantZip, String cardNum, String origTs, String procTs, String filler,
        String originalRecord) {

    public static DailyTransaction fromRecord(String raw) {
        FixedWidth.Cursor fields = new FixedWidth.Cursor(raw, 350);
        return new DailyTransaction(fields.take(16), fields.take(2), fields.numeric(4),
                fields.take(10), fields.take(100), fields.decimal(9), fields.numeric(9),
                fields.take(50), fields.take(50), fields.take(10), fields.take(16),
                fields.take(26), fields.take(26), fields.take(20), raw);
    }
}
