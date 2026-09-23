package com.carddemo.posttran.layout;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.CardXref;

/** Copybook CVACT03Y ({@code CARD-XREF-RECORD}, RECLN 50). Offsets are 0-based. */
public final class CardXrefLayout {

    public static final int RECORD_LENGTH = 50;

    private CardXrefLayout() {
    }

    public static CardXref parse(String line) {
        String r = CobolFields.alnum(line, RECORD_LENGTH);
        return new CardXref(
                r.substring(0, 16),                                  // XREF-CARD-NUM PIC X(16)
                CobolFields.parseUnsigned(r.substring(16, 25)),      // XREF-CUST-ID  PIC 9(09)
                CobolFields.parseUnsigned(r.substring(25, 36)));     // XREF-ACCT-ID  PIC 9(11)
    }                                                                // FILLER        PIC X(14)
}
