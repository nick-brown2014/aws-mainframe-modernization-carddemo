package com.carddemo.batch.posttran.model;

import java.math.BigDecimal;

/** CVTRA01Y - TRAN-CAT-BAL-RECORD (50 bytes, key = 17-byte composite). */
public record TranCategoryBalance(long acctId, String tranTypeCd, int tranCatCd, BigDecimal tranCatBal) {

    public static final int RECORD_LENGTH = 50;

    public TranCatKey key() {
        return new TranCatKey(acctId, tranTypeCd, tranCatCd);
    }

    public TranCategoryBalance withBalance(BigDecimal newBal) {
        return new TranCategoryBalance(acctId, tranTypeCd, tranCatCd, newBal);
    }
}
