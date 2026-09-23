package com.carddemo.posttran;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.Account;
import com.carddemo.posttran.domain.DailyTransaction;
import java.math.BigDecimal;

/** Builders for copybook-shaped test data. */
public final class TestRecords {

    public static final String CARD = "4859452612877065";
    public static final long ACCT_ID = 1L;

    private TestRecords() {
    }

    /** Builds a raw 350-byte CVTRA06Y record. */
    public static String dalytranLine(String tranId, String typeCd, int catCd, BigDecimal amount,
                                      String cardNum, String origTs) {
        return CobolFields.alnum(tranId, 16)
                + CobolFields.alnum(typeCd, 2)
                + CobolFields.formatUnsigned(catCd, 4)
                + CobolFields.alnum("POS TERM", 10)
                + CobolFields.alnum("Purchase at Test Merchant", 100)
                + CobolFields.formatZoned(amount, 11, 2, true)
                + CobolFields.formatUnsigned(800000000L, 9)
                + CobolFields.alnum("Test Merchant", 50)
                + CobolFields.alnum("Testville", 50)
                + CobolFields.alnum("72112", 10)
                + CobolFields.alnum(cardNum, 16)
                + CobolFields.alnum(origTs, 26)
                + CobolFields.alnum("", 26)
                + CobolFields.alnum("", 20);
    }

    public static DailyTransaction dalytran(String tranId, BigDecimal amount, String origTs) {
        DailyTransaction t = new DailyTransaction();
        t.setRawRecord(dalytranLine(tranId, "01", 1, amount, CARD, origTs));
        t.setTranId(CobolFields.alnum(tranId, 16));
        t.setTypeCd("01");
        t.setCatCd(1);
        t.setSource(CobolFields.alnum("POS TERM", 10));
        t.setDescription(CobolFields.alnum("Purchase at Test Merchant", 100));
        t.setAmount(amount.setScale(2));
        t.setMerchantId(800000000L);
        t.setMerchantName(CobolFields.alnum("Test Merchant", 50));
        t.setMerchantCity(CobolFields.alnum("Testville", 50));
        t.setMerchantZip(CobolFields.alnum("72112", 10));
        t.setCardNum(CARD);
        t.setOrigTs(CobolFields.alnum(origTs, 26));
        t.setProcTs(CobolFields.alnum("", 26));
        return t;
    }

    public static Account account(long acctId, String creditLimit, String cycCredit, String cycDebit,
                                  String expirationDate) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setActiveStatus("Y");
        a.setCurrBal(new BigDecimal("100.00"));
        a.setCreditLimit(new BigDecimal(creditLimit));
        a.setCashCreditLimit(new BigDecimal("500.00"));
        a.setOpenDate("2014-11-20");
        a.setExpirationDate(expirationDate);
        a.setReissueDate("2025-05-20");
        a.setCurrCycCredit(new BigDecimal(cycCredit));
        a.setCurrCycDebit(new BigDecimal(cycDebit));
        a.setAddrZip("72112     ");
        a.setGroupId("A000000000");
        return a;
    }
}
