package com.carddemo.posttran;

import java.math.BigDecimal;

public record Transaction(
        String id, String typeCd, String catCd, String source, String description,
        BigDecimal amount, String merchantId, String merchantName, String merchantCity,
        String merchantZip, String cardNum, String origTs, String procTs, String filler) {

    public static Transaction posted(DailyTransaction daily, String timestamp) {
        return new Transaction(daily.id(), daily.typeCd(), daily.catCd(), daily.source(),
                daily.description(), daily.amount(), daily.merchantId(), daily.merchantName(),
                daily.merchantCity(), daily.merchantZip(), daily.cardNum(), daily.origTs(),
                timestamp, daily.filler());
    }
}
