package com.carddemo.posttran.batch;

import com.carddemo.posttran.domain.PostingOutcome;
import com.carddemo.posttran.domain.RejectedTransaction;
import com.carddemo.posttran.layout.RejectRecordLayout;
import org.springframework.batch.item.file.transform.LineAggregator;

/** Formats a {@link RejectedTransaction} as a 430-byte DALYREJS record ({@code 2500-WRITE-REJECT-REC}). */
public class RejectLineAggregator implements LineAggregator<PostingOutcome> {

    @Override
    public String aggregate(PostingOutcome item) {
        if (!(item instanceof RejectedTransaction rejected)) {
            throw new IllegalArgumentException("Only rejected transactions are written to DALYREJS: " + item);
        }
        return RejectRecordLayout.format(rejected);
    }
}
