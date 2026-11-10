package com.carddemo.batch.posttran.model;

import com.carddemo.batch.posttran.io.FixedWidth;

/** 17-byte composite KSDS key of TCATBALF: 9(11) acct id + X(2) type + 9(4) category. */
public record TranCatKey(long acctId, String tranTypeCd, int tranCatCd) {

    /** Renders the key exactly as the COBOL FD-TRAN-CAT-KEY (17 bytes). */
    public String asFixedWidth() {
        return FixedWidth.zeroPad(acctId, 11) + FixedWidth.padRight(tranTypeCd, 2) + FixedWidth.zeroPad(tranCatCd, 4);
    }
}
