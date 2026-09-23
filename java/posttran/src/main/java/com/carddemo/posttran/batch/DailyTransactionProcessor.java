package com.carddemo.posttran.batch;

import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.PostingOutcome;
import com.carddemo.posttran.domain.RejectedTransaction;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Per-record body of the main {@code PERFORM UNTIL END-OF-FILE} loop: validate, then either post
 * ({@code 2000-POST-TRANSACTION}) or reject ({@code 2500-WRITE-REJECT-REC}).
 */
@Component
public class DailyTransactionProcessor implements ItemProcessor<DailyTransaction, PostingOutcome> {

    private final TransactionValidator validator;
    private final TransactionPoster poster;

    public DailyTransactionProcessor(TransactionValidator validator, TransactionPoster poster) {
        this.validator = validator;
        this.poster = poster;
    }

    @Override
    public PostingOutcome process(DailyTransaction item) {
        TransactionValidator.Validation validation = validator.validate(item);
        if (validation.isValid()) {
            return poster.post(item, validation.xref(), validation.account());
        }
        return new RejectedTransaction(item, validation.result());
    }
}
