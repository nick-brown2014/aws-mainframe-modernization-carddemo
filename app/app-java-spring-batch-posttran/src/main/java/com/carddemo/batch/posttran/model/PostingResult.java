package com.carddemo.batch.posttran.model;

import com.carddemo.batch.posttran.validation.ValidationFailure;

/** Outcome of processing one daily transaction. */
public sealed interface PostingResult {

    DailyTransaction source();

    /**
     * Computed (not yet persisted) state after posting: the mutated account, the upserted
     * category balance ({@code balanceIsNew} = WRITE rather than REWRITE) and the new master record.
     */
    record Posted(DailyTransaction source, Transaction transaction, Account account,
                  TranCategoryBalance balance, boolean balanceIsNew) implements PostingResult {
    }

    record Rejected(DailyTransaction source, ValidationFailure failure) implements PostingResult {

        public static Rejected of(DailyTransaction source, int code, String description) {
            return new Rejected(source, new ValidationFailure(code, description));
        }

        public RejectRecord toRejectRecord() {
            return new RejectRecord(source.rawRecord(), failure);
        }
    }
}
