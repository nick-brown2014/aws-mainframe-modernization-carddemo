package com.carddemo.batch.posttran.io;

import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.TranCategoryBalance;
import com.carddemo.batch.posttran.model.Transaction;

import static com.carddemo.batch.posttran.io.FixedWidth.padRight;
import static com.carddemo.batch.posttran.io.FixedWidth.zeroPad;

/** Renders domain records back into their copybook fixed-width layouts (FILLER regenerated as spaces). */
public final class CopybookRecordFormatter {

    private CopybookRecordFormatter() {
    }

    /** CVTRA05Y / CVTRA06Y, 350 bytes. */
    public static String formatTransaction(Transaction t) {
        return padRight(t.tranId(), 16)
                + padRight(t.tranTypeCd(), 2)
                + zeroPad(t.tranCatCd(), 4)
                + padRight(t.tranSource(), 10)
                + padRight(t.tranDesc(), 100)
                + ZonedDecimal.format(t.tranAmt(), 11)
                + zeroPad(t.merchantId(), 9)
                + padRight(t.merchantName(), 50)
                + padRight(t.merchantCity(), 50)
                + padRight(t.merchantZip(), 10)
                + padRight(t.cardNum(), 16)
                + padRight(t.origTs(), 26)
                + padRight(t.procTs(), 26)
                + padRight("", 20);
    }

    /** CVACT01Y, 300 bytes. */
    public static String formatAccount(Account a) {
        return zeroPad(a.acctId(), 11)
                + padRight(a.activeStatus(), 1)
                + ZonedDecimal.format(a.currBal(), 12)
                + ZonedDecimal.format(a.creditLimit(), 12)
                + ZonedDecimal.format(a.cashCreditLimit(), 12)
                + padRight(a.openDate(), 10)
                + padRight(a.expirationDate(), 10)
                + padRight(a.reissueDate(), 10)
                + ZonedDecimal.format(a.currCycCredit(), 12)
                + ZonedDecimal.format(a.currCycDebit(), 12)
                + padRight(a.addrZip(), 10)
                + padRight(a.groupId(), 10)
                + padRight("", 178);
    }

    /** CVTRA01Y, 50 bytes. */
    public static String formatTranCategoryBalance(TranCategoryBalance b) {
        return b.key().asFixedWidth()
                + ZonedDecimal.format(b.tranCatBal(), 11)
                + padRight("", 22);
    }

    /** CVACT03Y, 50 bytes. */
    public static String formatCardXref(CardXref x) {
        return padRight(x.cardNum(), 16) + zeroPad(x.custId(), 9) + zeroPad(x.acctId(), 11) + padRight("", 14);
    }
}
