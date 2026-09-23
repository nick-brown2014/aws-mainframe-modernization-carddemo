package com.carddemo.batch.posttran.listener;

import com.carddemo.batch.posttran.ProgramConsole;
import com.carddemo.batch.posttran.io.FixedWidth;
import com.carddemo.batch.posttran.posting.PostingResultCountingListener;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

/** Start/end-of-job DISPLAYs (spec section 7.4) and the RETURN-CODE 4 exit status (7.5). */
@Component
public class PostTranJobListener implements JobExecutionListener {

    public static final String COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    private final ProgramConsole console;

    public PostTranJobListener(ProgramConsole console) {
        this.console = console;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        console.display("START OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        long processed = 0;
        long rejected = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            processed += PostingResultCountingListener.processed(step);
            rejected += PostingResultCountingListener.rejected(step);
        }
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            for (Throwable t : jobExecution.getAllFailureExceptions()) {
                console.display("ERROR " + t.getMessage());
            }
            console.display("ABENDING PROGRAM");
            return;
        }
        console.display("TRANSACTIONS PROCESSED :" + FixedWidth.zeroPad(processed, 9));
        console.display("TRANSACTIONS REJECTED  :" + FixedWidth.zeroPad(rejected, 9));
        if (rejected > 0) {
            jobExecution.setExitStatus(new ExitStatus(COMPLETED_WITH_REJECTS));
        }
        console.display("END OF EXECUTION OF PROGRAM CBTRN02C");
    }

    public static boolean hasRejects(JobExecution execution) {
        return COMPLETED_WITH_REJECTS.equals(execution.getExitStatus().getExitCode());
    }
}
