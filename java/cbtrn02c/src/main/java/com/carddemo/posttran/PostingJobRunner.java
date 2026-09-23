package com.carddemo.posttran;

import java.nio.file.Path;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@ConditionalOnProperty(name = "posttran.autorun", havingValue = "true", matchIfMissing = true)
public class PostingJobRunner implements ApplicationRunner, ExitCodeGenerator {
    private final JobLauncher launcher;
    private final Job job;
    private final String input;
    private final String rejects;
    private int exitCode;

    public PostingJobRunner(JobLauncher launcher, Job posttranJob,
                            @Value("${posttran.input}") String input,
                            @Value("${posttran.rejects}") String rejects) {
        this.launcher = launcher;
        this.job = posttranJob;
        this.input = input;
        this.rejects = rejects;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        var execution = launcher.run(job, new JobParametersBuilder()
                .addString("input", Path.of(input).toAbsolutePath().toString())
                .addString("rejects", Path.of(rejects).toAbsolutePath().toString())
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());
        exitCode = execution.getStatus() == BatchStatus.COMPLETED
                ? "4".equals(execution.getExitStatus().getExitCode()) ? 4 : 0 : 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
