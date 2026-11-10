package com.carddemo.batch.posttran;

import com.carddemo.batch.posttran.io.Db2TimestampFormatter;
import com.carddemo.batch.posttran.io.DailyTransactionLineMapper;
import com.carddemo.batch.posttran.io.RejectRecordLineAggregator;
import com.carddemo.batch.posttran.listener.PostTranJobListener;
import com.carddemo.batch.posttran.model.DailyTransaction;
import com.carddemo.batch.posttran.model.PostingResult;
import com.carddemo.batch.posttran.model.PostingResult.Posted;
import com.carddemo.batch.posttran.model.PostingResult.Rejected;
import com.carddemo.batch.posttran.model.RejectRecord;
import com.carddemo.batch.posttran.posting.ChunkStateCache;
import com.carddemo.batch.posttran.posting.PostTranItemProcessor;
import com.carddemo.batch.posttran.posting.PostedItemWriter;
import com.carddemo.batch.posttran.posting.PostingResultCountingListener;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.batch.repeat.CompletionPolicy;
import org.springframework.batch.repeat.policy.SimpleCompletionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** postTranJob: one chunk-oriented step mirroring the single COBOL read loop (spec section 6). */
@Configuration
@EnableConfigurationProperties(PostTranProperties.class)
public class PostTranJobConfig {

    public static final String JOB_NAME = "postTranJob";
    public static final String STEP_NAME = "postTransactionsStep";
    public static final String PARAM_DAILY_TRAN_FILE = "dailyTranFile";
    public static final String PARAM_REJECTS_FILE = "rejectsFile";
    public static final String PARAM_RUN_DATE = "runDate";
    public static final String PARAM_CHUNK_SIZE = "chunkSize";
    public static final int DEFAULT_CHUNK_SIZE = 100;

    @Bean
    public Clock postTranClock(PostTranProperties props) {
        return Clock.system(props.clock().zone());
    }

    @Bean
    public Db2TimestampFormatter db2TimestampFormatter(Clock postTranClock) {
        return new Db2TimestampFormatter(postTranClock);
    }

    @Bean
    public JobParametersValidator postTranJobParametersValidator() {
        return new DefaultJobParametersValidator(
                new String[] {PARAM_DAILY_TRAN_FILE, PARAM_REJECTS_FILE},
                new String[] {PARAM_RUN_DATE, PARAM_CHUNK_SIZE, "run.id"});
    }

    @Bean
    public Job postTranJob(JobRepository jobRepository, Step postTransactionsStep, PostTranJobListener jobListener,
                           JobParametersValidator postTranJobParametersValidator) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .validator(postTranJobParametersValidator)
                .listener(jobListener)
                .start(postTransactionsStep)
                .build();
    }

    @Bean
    public Step postTransactionsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                     FlatFileItemReader<DailyTransaction> dailyTransactionReader,
                                     PostTranItemProcessor processor,
                                     ItemWriter<PostingResult> postingResultWriter,
                                     FlatFileItemWriter<RejectRecord> rejectsWriter,
                                     CompletionPolicy chunkCompletionPolicy,
                                     ChunkStateCache chunkStateCache,
                                     PostingResultCountingListener countingListener) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostingResult>chunk(chunkCompletionPolicy, transactionManager)
                .reader(dailyTransactionReader)
                .processor(processor)
                .writer(postingResultWriter)
                .stream(rejectsWriter)
                .listener(chunkStateCache)
                .listener((StepExecutionListener) countingListener)
                .listener((ItemWriteListener<PostingResult>) countingListener)
                .build();
    }

    @Bean
    @StepScope
    public CompletionPolicy chunkCompletionPolicy(@Value("#{jobParameters['chunkSize']}") Long chunkSize) {
        return new SimpleCompletionPolicy(chunkSize == null ? DEFAULT_CHUNK_SIZE : chunkSize.intValue());
    }

    @Bean
    @StepScope
    public FlatFileItemReader<DailyTransaction> dailyTransactionReader(
            @Value("#{jobParameters['dailyTranFile']}") String dailyTranFile, PostTranProperties props) {
        return new FlatFileItemReaderBuilder<DailyTransaction>()
                .name("dailyTransactionReader")
                .resource(new FileSystemResource(dailyTranFile))
                .encoding(props.input().charset().name())
                .strict(true)
                .lineMapper(new DailyTransactionLineMapper())
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemWriter<RejectRecord> rejectsWriter(
            @Value("#{jobParameters['rejectsFile']}") String rejectsFile, PostTranProperties props) {
        return new FlatFileItemWriterBuilder<RejectRecord>()
                .name("rejectsWriter")
                .resource(new FileSystemResource(rejectsFile))
                .encoding(props.output().charset().name())
                .lineSeparator(props.output().lineSeparator())
                .lineAggregator(new RejectRecordLineAggregator())
                .shouldDeleteIfEmpty(false)
                .shouldDeleteIfExists(true)
                .build();
    }

    @Bean
    public ItemWriter<PostingResult> postingResultWriter(PostedItemWriter postedItemWriter,
                                                         FlatFileItemWriter<RejectRecord> rejectsWriter) {
        ItemStreamWriter<PostingResult> rejectedWriter = new RejectedItemWriter(rejectsWriter);
        ClassifierCompositeItemWriter<PostingResult> writer = new ClassifierCompositeItemWriter<>();
        writer.setClassifier(result -> result instanceof Posted ? postedItemWriter : rejectedWriter);
        return writer;
    }

    /** Adapts {@link Rejected} results to the 430-byte rejects {@link FlatFileItemWriter}. */
    static final class RejectedItemWriter implements ItemStreamWriter<PostingResult> {

        private final FlatFileItemWriter<RejectRecord> delegate;

        RejectedItemWriter(FlatFileItemWriter<RejectRecord> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(org.springframework.batch.item.Chunk<? extends PostingResult> chunk) throws Exception {
            org.springframework.batch.item.Chunk<RejectRecord> rejects = new org.springframework.batch.item.Chunk<>();
            for (PostingResult r : chunk) {
                rejects.add(((Rejected) r).toRejectRecord());
            }
            delegate.write(rejects);
        }
    }
}
