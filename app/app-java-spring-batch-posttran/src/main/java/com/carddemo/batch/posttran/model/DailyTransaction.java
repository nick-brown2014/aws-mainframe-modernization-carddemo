package com.carddemo.batch.posttran.model;

import java.math.BigDecimal;

/** CVTRA06Y - DALYTRAN-RECORD (350 bytes). {@code rawRecord} keeps the input line verbatim for reject echoing. */
public record DailyTransaction(
        String tranId,
        String tranTypeCd,
        int tranCatCd,
        String tranSource,
        String tranDesc,
        BigDecimal tranAmt,
        long merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String cardNum,
        String origTs,
        String procTs,
        String filler,
        String rawRecord) {

    public static final int RECORD_LENGTH = 350;
}
