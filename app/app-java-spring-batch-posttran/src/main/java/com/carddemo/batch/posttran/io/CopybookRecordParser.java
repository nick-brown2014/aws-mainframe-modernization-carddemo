package com.carddemo.batch.posttran.io;

import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.TranCategoryBalance;

import static com.carddemo.batch.posttran.io.FixedWidth.parseUnsigned;
import static com.carddemo.batch.posttran.io.FixedWidth.slice;

/** Parses the three master-file copybook layouts (used by the fixture loaders, spec section 4.3-4.5). */
public final class CopybookRecordParser {

    private CopybookRecordParser() {
    }

    public static CardXref parseCardXref(String rec) {
        String r = FixedWidth.normalizeLine(rec, CardXref.RECORD_LENGTH);
        return new CardXref(slice(r, 1, 16),
                parseUnsigned(slice(r, 17, 9), "XREF-CUST-ID"),
                parseUnsigned(slice(r, 26, 11), "XREF-ACCT-ID"));
    }

    public static Account parseAccount(String rec) {
        String r = FixedWidth.normalizeLine(rec, Account.RECORD_LENGTH);
        return new Account(parseUnsigned(slice(r, 1, 11), "ACCT-ID"),
                slice(r, 12, 1),
                ZonedDecimal.parse(slice(r, 13, 12), 2),
                ZonedDecimal.parse(slice(r, 25, 12), 2),
                ZonedDecimal.parse(slice(r, 37, 12), 2),
                slice(r, 49, 10),
                slice(r, 59, 10),
                slice(r, 69, 10),
                ZonedDecimal.parse(slice(r, 79, 12), 2),
                ZonedDecimal.parse(slice(r, 91, 12), 2),
                slice(r, 103, 10),
                slice(r, 113, 10));
    }

    public static TranCategoryBalance parseTranCategoryBalance(String rec) {
        String r = FixedWidth.normalizeLine(rec, TranCategoryBalance.RECORD_LENGTH);
        return new TranCategoryBalance(parseUnsigned(slice(r, 1, 11), "TRANCAT-ACCT-ID"),
                slice(r, 12, 2),
                (int) parseUnsigned(slice(r, 14, 4), "TRANCAT-CD"),
                ZonedDecimal.parse(slice(r, 18, 11), 2));
    }
}
