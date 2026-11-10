package com.carddemo.batch.posttran.model;

import java.math.BigDecimal;

/** CVTRA05Y - TRAN-RECORD (350 bytes, key = transaction id). Layout identical to CVTRA06Y. */
public record Transaction(
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
        String procTs) {

    public static final int RECORD_LENGTH = 350;

    /** 2000-POST-TRANSACTION: fields 1-12 copied verbatim, TRAN-PROC-TS generated. */
    public static Transaction from(DailyTransaction d, String procTs) {
        return new Transaction(d.tranId(), d.tranTypeCd(), d.tranCatCd(), d.tranSource(), d.tranDesc(), d.tranAmt(),
                d.merchantId(), d.merchantName(), d.merchantCity(), d.merchantZip(), d.cardNum(), d.origTs(), procTs);
    }
}
