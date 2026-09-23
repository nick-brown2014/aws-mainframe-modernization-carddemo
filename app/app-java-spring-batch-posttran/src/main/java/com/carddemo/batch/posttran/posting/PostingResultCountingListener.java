package com.carddemo.batch.posttran.posting;

import com.carddemo.batch.posttran.model.PostingResult;
import com.carddemo.batch.posttran.model.PostingResult.Rejected;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * Maintains WS-TRANSACTION-COUNT / WS-REJECT-COUNT equivalents in the step {@link ExecutionContext}
 * so they are committed with each chunk and survive a restart.
 */
@Component
public class PostingResultCountingListener implements ItemWriteListener<PostingResult>, StepExecutionListener {

    public static final String PROCESSED_COUNT = "processed.count";
    public static final String POSTED_COUNT = "posted.count";
    public static final String REJECTED_COUNT = "rejected.count";

    private StepExecution stepExecution;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
    }

    @Override
    public void afterWrite(Chunk<? extends PostingResult> items) {
        long rejected = items.getItems().stream().filter(Rejected.class::isInstance).count();
        long posted = items.size() - rejected;
        ExecutionContext ctx = stepExecution.getExecutionContext();
        ctx.putLong(PROCESSED_COUNT, ctx.getLong(PROCESSED_COUNT, 0L) + items.size());
        ctx.putLong(POSTED_COUNT, ctx.getLong(POSTED_COUNT, 0L) + posted);
        ctx.putLong(REJECTED_COUNT, ctx.getLong(REJECTED_COUNT, 0L) + rejected);
    }

    public static long processed(StepExecution step) {
        return step.getExecutionContext().getLong(PROCESSED_COUNT, 0L);
    }

    public static long rejected(StepExecution step) {
        return step.getExecutionContext().getLong(REJECTED_COUNT, 0L);
    }
}
