package com.carddemo.batch.posttran.support;

import com.carddemo.batch.posttran.io.CopybookRecordFormatter;
import com.carddemo.batch.posttran.io.DailyTransactionLineMapper;
import com.carddemo.batch.posttran.io.ZonedDecimal;
import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.DailyTransaction;
import com.carddemo.batch.posttran.model.TranCategoryBalance;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.carddemo.batch.posttran.io.FixedWidth.padRight;
import static com.carddemo.batch.posttran.io.FixedWidth.zeroPad;

/** Builders for copybook-shaped fixtures used across the test suite. */
public final class TestData {

    public static final String CARD = "4000000000000001";
    public static final long ACCT_ID = 1L;
    public static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** {@code app/data/ASCII} resolved from the module directory (Surefire/Failsafe run with basedir = module). */
    public static final Path ASCII_DATA = Paths.get("..", "data", "ASCII").toAbsolutePath().normalize();

    private TestData() {
    }

    public static BigDecimal amt(String value) {
        return new BigDecimal(value).setScale(2);
    }

    /** Renders a 350-byte DALYTRAN record (CVTRA06Y) with the processing-timestamp and filler areas blank. */
    public static String dailyLine(String tranId, String typeCd, int catCd, BigDecimal amount, String cardNum,
                                   String origTs) {
        return padRight(tranId, 16)
                + padRight(typeCd, 2)
                + zeroPad(catCd, 4)
                + padRight("POS TERM", 10)
                + padRight("Purchase at Test Merchant", 100)
                + ZonedDecimal.format(amount, 11)
                + zeroPad(800000000L, 9)
                + padRight("Test Merchant", 50)
                + padRight("Test City", 50)
                + padRight("72112", 10)
                + padRight(cardNum, 16)
                + padRight(origTs, 26)
                + padRight("", 26)
                + padRight("", 20);
    }

    public static String dailyLine(String tranId, BigDecimal amount) {
        return dailyLine(tranId, "01", 1, amount, CARD, ORIG_TS);
    }

    public static DailyTransaction daily(String tranId, BigDecimal amount) {
        return daily(dailyLine(tranId, amount));
    }

    public static DailyTransaction daily(String line) {
        return new DailyTransactionLineMapper().mapLine(line, 1);
    }

    public static Account account(long acctId, String currBal, String creditLimit, String expirationDate,
                                  String cycCredit, String cycDebit) {
        return new Account(acctId, "Y", amt(currBal), amt(creditLimit), amt("500.00"), "2014-11-20", expirationDate,
                "2025-05-20", amt(cycCredit), amt(cycDebit), "", "A000000000");
    }

    public static Account account(String creditLimit, String cycCredit, String cycDebit) {
        return account(ACCT_ID, "100.00", creditLimit, "2030-12-31", cycCredit, cycDebit);
    }

    public static CardXref xref(String cardNum, long acctId) {
        return new CardXref(cardNum, 100L + acctId, acctId);
    }

    public static String xrefLine(CardXref x) {
        return CopybookRecordFormatter.formatCardXref(x);
    }

    public static String accountLine(Account a) {
        return CopybookRecordFormatter.formatAccount(a);
    }

    public static String balanceLine(TranCategoryBalance b) {
        return CopybookRecordFormatter.formatTranCategoryBalance(b);
    }
}
