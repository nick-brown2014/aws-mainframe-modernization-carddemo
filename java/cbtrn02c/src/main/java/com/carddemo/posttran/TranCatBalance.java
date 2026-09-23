package com.carddemo.posttran;

import java.math.BigDecimal;

public record TranCatBalance(String acctId, String typeCd, String catCd,
                             BigDecimal balance, String filler) {
    public static TranCatBalance fromRecord(String raw) {
        FixedWidth.Cursor fields = new FixedWidth.Cursor(raw, 50);
        return new TranCatBalance(fields.numeric(11), fields.take(2), fields.numeric(4),
                fields.decimal(9), fields.take(22));
    }

    public TranCatBalance add(BigDecimal amount) {
        return new TranCatBalance(acctId, typeCd, catCd, balance.add(amount), filler);
    }
}
