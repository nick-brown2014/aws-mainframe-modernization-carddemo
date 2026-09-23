package com.carddemo.posttran.batch;

import com.carddemo.posttran.domain.PostingOutcome;
import com.carddemo.posttran.domain.RejectedTransaction;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.item.Chunk;
import org.springframework.stereotype.Component;

/**
 * {@code WS-COUNTERS}: {@code WS-TRANSACTION-COUNT} (every record read, posted or not) and
 * {@code WS-REJECT-COUNT}. Counted after each chunk is written so rolled-back chunks are not counted.
 */
@Component
public class PostTranCounters implements ItemWriteListener<PostingOutcome> {

    private final AtomicLong transactionCount = new AtomicLong();
    private final AtomicLong rejectCount = new AtomicLong();

    @Override
    public void afterWrite(Chunk<? extends PostingOutcome> items) {
        for (PostingOutcome item : items) {
            transactionCount.incrementAndGet();
            if (item instanceof RejectedTransaction) {
                rejectCount.incrementAndGet();
            }
        }
    }

    public long getTransactionCount() {
        return transactionCount.get();
    }

    public long getRejectCount() {
        return rejectCount.get();
    }

    public void reset() {
        transactionCount.set(0);
        rejectCount.set(0);
    }
}
