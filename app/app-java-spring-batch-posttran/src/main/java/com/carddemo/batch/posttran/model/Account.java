package com.carddemo.batch.posttran.model;

import java.math.BigDecimal;

/** CVACT01Y - ACCOUNT-RECORD (300 bytes, key = account id). */
public record Account(
        long acctId,
        String activeStatus,
        BigDecimal currBal,
        BigDecimal creditLimit,
        BigDecimal cashCreditLimit,
        String openDate,
        String expirationDate,
        String reissueDate,
        BigDecimal currCycCredit,
        BigDecimal currCycDebit,
        String addrZip,
        String groupId) {

    public static final int RECORD_LENGTH = 300;

    public Account withBalances(BigDecimal newCurrBal, BigDecimal newCurrCycCredit, BigDecimal newCurrCycDebit) {
        return new Account(acctId, activeStatus, newCurrBal, creditLimit, cashCreditLimit, openDate,
                expirationDate, reissueDate, newCurrCycCredit, newCurrCycDebit, addrZip, groupId);
    }
}
