package com.carddemo.batch.posttran.listener;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

/** Spec section 7.5: 0 = clean, 4 = completed with rejects, 8 = failure (Q5 recommendation). */
public final class PostTranExitCodeMapper {

    public static final int CLEAN = 0;
    public static final int REJECTS = 4;
    public static final int FAILURE = 8;

    private PostTranExitCodeMapper() {
    }

    public static int exitCode(JobExecution execution) {
        if (execution == null || execution.getStatus() != BatchStatus.COMPLETED) {
            return FAILURE;
        }
        return PostTranJobListener.hasRejects(execution) ? REJECTS : CLEAN;
    }
}
