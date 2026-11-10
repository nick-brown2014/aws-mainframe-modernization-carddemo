package com.carddemo.batch.posttran.validation;

import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.DailyTransaction;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Rules V3 (102) and V4 (103) from 1500-B-LOOKUP-ACCT. Both are always evaluated and each overwrites
 * the reason, so a record failing both carries 103 (spec section 5.2). V1/V2 are lookups done by the
 * processor.
 */
public class TransactionValidator {

    public Optional<ValidationFailure> validateAgainstAccount(DailyTransaction tran, Account acct) {
        ValidationFailure failure = null;
        BigDecimal tempBal = acct.currCycCredit().subtract(acct.currCycDebit()).add(tran.tranAmt());
        if (acct.creditLimit().compareTo(tempBal) < 0) {
            failure = ValidationFailure.OVERLIMIT;
        }
        if (acct.expirationDate().compareTo(tran.origTs().substring(0, 10)) < 0) {
            failure = ValidationFailure.EXPIRED;
        }
        return Optional.ofNullable(failure);
    }
}
