package com.carddemo.posttran.config;

import com.carddemo.posttran.batch.DailyTransactionLineMapper;
import com.carddemo.posttran.batch.DailyTransactionProcessor;
import com.carddemo.posttran.batch.PostTranCounters;
import com.carddemo.posttran.batch.PostTranJobListener;
import com.carddemo.posttran.batch.PostedTransactionWriter;
import com.carddemo.posttran.batch.RejectLineAggregator;
import com.carddemo.posttran.cobol.Db2Timestamp;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.PostingOutcome;
import com.carddemo.posttran.domain.RejectedTransaction;
import java.time.Clock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the POSTTRAN job: one chunk-oriented step reading DALYTRAN, validating/posting each record,
 * and routing outcomes to the relational stores (posted) or the DALYREJS flat file (rejected).
 */
@Configuration
@EnableConfigurationProperties(PostTranProperties.class)
public class PostTranJobConfig {

    public static final String JOB_NAME = "POSTTRAN";
    public static final String STEP_NAME = "CBTRN02C";

    @Bean
    public Db2Timestamp db2Timestamp() {
        return new Db2Timestamp(Clock.systemDefaultZone());
    }

    @Bean
    public FlatFileItemReader<DailyTransaction> dalytranReader(PostTranProperties props) {
        return new FlatFileItemReaderBuilder<DailyTransaction>()
                .name("dalytranReader")
                .resource(new FileSystemResource(props.dalytran()))
                .lineMapper(new DailyTransactionLineMapper())
                .strict(true)
                .build();
    }

    @Bean
    public FlatFileItemWriter<PostingOutcome> dalyrejsWriter(PostTranProperties props) {
        return new FlatFileItemWriterBuilder<PostingOutcome>()
                .name("dalyrejsWriter")
                .resource(new FileSystemResource(props.dalyrejs()))
                .lineAggregator(new RejectLineAggregator())
                .shouldDeleteIfEmpty(false)
                .build();
    }

    @Bean
    public ClassifierCompositeItemWriter<PostingOutcome> outcomeWriter(
            PostedTransactionWriter postedTransactionWriter,
            FlatFileItemWriter<PostingOutcome> dalyrejsWriter) {
        ClassifierCompositeItemWriter<PostingOutcome> writer = new ClassifierCompositeItemWriter<>();
        writer.setClassifier(outcome -> outcome instanceof RejectedTransaction
                ? dalyrejsWriter
                : (ItemWriter<? super PostingOutcome>) postedTransactionWriter);
        return writer;
    }

    @Bean
    public Step cbtrn02cStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             PostTranProperties props,
                             FlatFileItemReader<DailyTransaction> dalytranReader,
                             DailyTransactionProcessor processor,
                             ClassifierCompositeItemWriter<PostingOutcome> outcomeWriter,
                             FlatFileItemWriter<PostingOutcome> dalyrejsWriter,
                             PostTranCounters counters) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostingOutcome>chunk(props.chunkSize(), transactionManager)
                .reader(dalytranReader)
                .processor(processor)
                .writer(outcomeWriter)
                .stream(dalyrejsWriter)
                .listener(counters)
                .build();
    }

    @Bean
    public Job posttranJob(JobRepository jobRepository, Step cbtrn02cStep, PostTranJobListener listener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(listener)
                .start(cbtrn02cStep)
                .build();
    }
}
