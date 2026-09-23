package com.carddemo.posttran.layout;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.TranCatBalance;
import com.carddemo.posttran.domain.TranCatBalanceId;

/** Copybook CVTRA01Y ({@code TRAN-CAT-BAL-RECORD}, RECLN 50). Offsets are 0-based. */
public final class TranCatBalanceLayout {

    public static final int RECORD_LENGTH = 50;

    private TranCatBalanceLayout() {
    }

    public static TranCatBalance parse(String line) {
        String r = CobolFields.alnum(line, RECORD_LENGTH);
        TranCatBalanceId id = new TranCatBalanceId(
                CobolFields.parseUnsigned(r.substring(0, 11)),          // TRANCAT-ACCT-ID PIC 9(11)
                r.substring(11, 13),                                    // TRANCAT-TYPE-CD PIC X(02)
                (int) CobolFields.parseUnsigned(r.substring(13, 17)));  // TRANCAT-CD      PIC 9(04)
        return new TranCatBalance(id, CobolFields.parseZoned(r.substring(17, 28), 2)); // TRAN-CAT-BAL PIC S9(09)V99
    }                                                                   // FILLER          PIC X(22)
}
