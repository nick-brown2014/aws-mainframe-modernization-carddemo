package com.carddemo.posttran.domain;

/**
 * Output of {@code 2000-POST-TRANSACTION}: the transaction master record to write plus the account
 * and category-balance records whose balances were updated.
 */
public record PostedTransaction(
        DailyTransaction source,
        Transaction transaction,
        Account account,
        TranCatBalance categoryBalance,
        boolean categoryBalanceCreated) implements PostingOutcome {
}
