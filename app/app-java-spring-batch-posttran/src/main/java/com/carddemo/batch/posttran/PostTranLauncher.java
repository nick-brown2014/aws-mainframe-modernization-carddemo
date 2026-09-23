package com.carddemo.batch.posttran;

import com.carddemo.batch.posttran.listener.PostTranExitCodeMapper;
import com.carddemo.batch.posttran.load.MasterFileLoader;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Command-line entry point: {@code --dailyTranFile=... --rejectsFile=... [--runDate=YYYY-MM-DD]
 * [--chunkSize=n] [--loadCardXrefFile=... --loadAcctFile=... --loadTcatbalFile=...]}.
 * <p>
 * Restart semantics (spec section 6.7): if the previous execution for the job failed or stopped it is
 * restarted (same identifying parameters); otherwise a new JobInstance is created via {@code run.id}.
 */
@Component
public class PostTranLauncher implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPT_LOAD_CARD_XREF = "loadCardXrefFile";
    public static final String OPT_LOAD_ACCT = "loadAcctFile";
    public static final String OPT_LOAD_TCATBAL = "loadTcatbalFile";

    private final Job postTranJob;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final MasterFileLoader loader;
    private final PostTranProperties props;
    private final Clock clock;
    private final ProgramConsole console;

    private volatile JobExecution execution;

    public PostTranLauncher(Job postTranJob, JobLauncher jobLauncher, JobExplorer jobExplorer, MasterFileLoader loader,
                            PostTranProperties props, Clock postTranClock, ProgramConsole console) {
        this.postTranJob = postTranJob;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.loader = loader;
        this.props = props;
        this.clock = postTranClock;
        this.console = console;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Charset charset = props.input().charset();
            option(args, OPT_LOAD_CARD_XREF).ifPresent(f -> loader.loadCardXref(Path.of(f), charset));
            option(args, OPT_LOAD_ACCT).ifPresent(f -> loader.loadAccounts(Path.of(f), charset));
            option(args, OPT_LOAD_TCATBAL).ifPresent(f -> loader.loadTranCategoryBalances(Path.of(f), charset));

            String dailyTranFile = option(args, PostTranJobConfig.PARAM_DAILY_TRAN_FILE)
                    .orElseThrow(() -> new IllegalArgumentException("Missing required --dailyTranFile=<path>"));
            String rejectsFile = option(args, PostTranJobConfig.PARAM_REJECTS_FILE)
                    .orElseThrow(() -> new IllegalArgumentException("Missing required --rejectsFile=<path>"));
            String runDate = option(args, PostTranJobConfig.PARAM_RUN_DATE).orElse(LocalDate.now(clock).toString());
            long chunkSize = option(args, PostTranJobConfig.PARAM_CHUNK_SIZE).map(Long::parseLong)
                    .orElse((long) PostTranJobConfig.DEFAULT_CHUNK_SIZE);

            JobParameters params = new JobParametersBuilder(identifyingParameters(runDate), jobExplorer)
                    .addString(PostTranJobConfig.PARAM_DAILY_TRAN_FILE, dailyTranFile, false)
                    .addString(PostTranJobConfig.PARAM_REJECTS_FILE, rejectsFile, false)
                    .addLong(PostTranJobConfig.PARAM_CHUNK_SIZE, chunkSize, false)
                    .toJobParameters();
            execution = jobLauncher.run(postTranJob, params);
        } catch (Exception e) {
            console.display("ERROR " + e.getMessage());
            console.display("ABENDING PROGRAM");
            execution = null;
        }
    }

    private JobParameters identifyingParameters(String runDate) {
        List<JobInstance> instances = jobExplorer.getJobInstances(PostTranJobConfig.JOB_NAME, 0, 1);
        if (!instances.isEmpty()) {
            JobExecution last = jobExplorer.getLastJobExecution(instances.get(0));
            if (last != null && last.getStatus().isUnsuccessful()
                    && runDate.equals(last.getJobParameters().getString(PostTranJobConfig.PARAM_RUN_DATE))) {
                return new JobParameters(last.getJobParameters().getIdentifyingParameters());
            }
        }
        JobParameters next = new JobParametersBuilder(jobExplorer).getNextJobParameters(postTranJob).toJobParameters();
        return new JobParametersBuilder(next).addString(PostTranJobConfig.PARAM_RUN_DATE, runDate, true).toJobParameters();
    }

    private static java.util.Optional<String> option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(values.get(values.size() - 1));
    }

    public JobExecution lastExecution() {
        return execution;
    }

    @Override
    public int getExitCode() {
        return PostTranExitCodeMapper.exitCode(execution);
    }
}
