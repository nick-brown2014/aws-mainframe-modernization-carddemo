package com.carddemo.posttran;

import java.math.BigDecimal;

public record Account(
        String id, String activeStatus, BigDecimal currBal, BigDecimal creditLimit,
        BigDecimal cashCreditLimit, String openDate, String expirationDate, String reissueDate,
        BigDecimal currCycCredit, BigDecimal currCycDebit, String addrZip, String groupId,
        String filler) {

    public static Account fromRecord(String raw) {
        FixedWidth.Cursor fields = new FixedWidth.Cursor(raw, 300);
        return new Account(fields.numeric(11), fields.take(1), fields.decimal(10),
                fields.decimal(10), fields.decimal(10), fields.take(10), fields.take(10),
                fields.take(10), fields.decimal(10), fields.decimal(10),
                fields.take(10), fields.take(10), fields.take(178));
    }

    public Account post(BigDecimal amount) {
        return new Account(id, activeStatus, currBal.add(amount), creditLimit,
                cashCreditLimit, openDate, expirationDate, reissueDate,
                amount.signum() >= 0 ? currCycCredit.add(amount) : currCycCredit,
                amount.signum() < 0 ? currCycDebit.add(amount) : currCycDebit,
                addrZip, groupId, filler);
    }
}
