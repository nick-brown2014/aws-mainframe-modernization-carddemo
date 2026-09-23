package com.carddemo.posttran.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * The DISPLAYs and RETURN-CODE handling at the end of the PROCEDURE DIVISION. A completed job with
 * one or more rejects ends with exit status {@link #COMPLETED_WITH_REJECTS} and JVM exit code
 * {@link #RETURN_CODE_REJECTS} (COBOL {@code MOVE 4 TO RETURN-CODE}).
 */
@Component
public class PostTranJobListener implements JobExecutionListener, ExitCodeGenerator {

    public static final String COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";
    public static final int RETURN_CODE_REJECTS = 4;

    private static final Logger log = LoggerFactory.getLogger(PostTranJobListener.class);

    private final PostTranCounters counters;
    private volatile int exitCode = 0;

    public PostTranJobListener(PostTranCounters counters) {
        this.counters = counters;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        counters.reset();
        exitCode = 0;
        log.info("START OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        log.info("TRANSACTIONS PROCESSED :{}", String.format("%09d", counters.getTransactionCount()));
        log.info("TRANSACTIONS REJECTED  :{}", String.format("%09d", counters.getRejectCount()));
        if (jobExecution.getStatus().isUnsuccessful()) {
            log.error("ABENDING PROGRAM");
        } else if (counters.getRejectCount() > 0) {
            exitCode = RETURN_CODE_REJECTS;
            jobExecution.setExitStatus(new ExitStatus(COMPLETED_WITH_REJECTS,
                    counters.getRejectCount() + " transaction(s) rejected"));
        }
        log.info("END OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
