package com.carddemo.posttran.layout;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.Account;

/** Copybook CVACT01Y ({@code ACCOUNT-RECORD}, RECLN 300). Offsets are 0-based. */
public final class AccountLayout {

    public static final int RECORD_LENGTH = 300;

    private AccountLayout() {
    }

    public static Account parse(String line) {
        String r = CobolFields.alnum(line, RECORD_LENGTH);
        Account a = new Account();
        a.setAcctId(CobolFields.parseUnsigned(r.substring(0, 11)));          // ACCT-ID                PIC 9(11)
        a.setActiveStatus(r.substring(11, 12));                              // ACCT-ACTIVE-STATUS     PIC X(01)
        a.setCurrBal(CobolFields.parseZoned(r.substring(12, 24), 2));        // ACCT-CURR-BAL          PIC S9(10)V99
        a.setCreditLimit(CobolFields.parseZoned(r.substring(24, 36), 2));    // ACCT-CREDIT-LIMIT      PIC S9(10)V99
        a.setCashCreditLimit(CobolFields.parseZoned(r.substring(36, 48), 2));// ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
        a.setOpenDate(r.substring(48, 58));                                  // ACCT-OPEN-DATE         PIC X(10)
        a.setExpirationDate(r.substring(58, 68));                            // ACCT-EXPIRAION-DATE    PIC X(10)
        a.setReissueDate(r.substring(68, 78));                               // ACCT-REISSUE-DATE      PIC X(10)
        a.setCurrCycCredit(CobolFields.parseZoned(r.substring(78, 90), 2));  // ACCT-CURR-CYC-CREDIT   PIC S9(10)V99
        a.setCurrCycDebit(CobolFields.parseZoned(r.substring(90, 102), 2));  // ACCT-CURR-CYC-DEBIT    PIC S9(10)V99
        a.setAddrZip(r.substring(102, 112));                                 // ACCT-ADDR-ZIP          PIC X(10)
        a.setGroupId(r.substring(112, 122));                                 // ACCT-GROUP-ID          PIC X(10)
        return a;                                                            // FILLER                 PIC X(178)
    }
}
