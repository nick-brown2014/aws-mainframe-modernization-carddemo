package com.carddemo.posttran;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PostingJobConfig {
    private static final Logger LOG = LoggerFactory.getLogger(PostingJobConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<DailyTransaction> dailyReader(@Value("#{jobParameters['input']}") String input) {
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setColumns(new Range(1, 16), new Range(17, 18), new Range(19, 22),
                new Range(23, 32), new Range(33, 132), new Range(133, 143),
                new Range(144, 152), new Range(153, 202), new Range(203, 252),
                new Range(253, 262), new Range(263, 278), new Range(279, 304),
                new Range(305, 330), new Range(331, 350));
        tokenizer.setStrict(true);
        FieldSetMapper<DailyTransaction> mapper = fields -> {
            StringBuilder raw = new StringBuilder(350);
            for (int field = 0; field < fields.getFieldCount(); field++) {
                raw.append(fields.readRawString(field));
            }
            return DailyTransaction.fromRecord(raw.toString());
        };
        return new FlatFileItemReaderBuilder<DailyTransaction>()
                .name("dailyTransactionReader")
                .resource(new FileSystemResource(input))
                .encoding("UTF-8")
                .lineTokenizer(tokenizer)
                .fieldSetMapper(mapper)
                .strict(true)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemWriter<String> rejectFileWriter(@Value("#{jobParameters['rejects']}") String output) {
        return new FlatFileItemWriterBuilder<String>()
                .name("dailyRejectWriter")
                .resource(new FileSystemResource(output))
                .lineAggregator(record -> record)
                .encoding("US-ASCII")
                .transactional(true)
                .shouldDeleteIfExists(true)
                .build();
    }

    @Bean
    @StepScope
    public PostingWriter postingWriter(PostingRepository repository, FlatFileItemWriter<String> rejectFileWriter,
                                       Clock clock) {
        return new PostingWriter(repository, rejectFileWriter, clock);
    }

    @Bean
    public Step postStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                         FlatFileItemReader<DailyTransaction> dailyReader, PostingProcessor processor,
                         PostingWriter postingWriter, FlatFileItemWriter<String> rejectFileWriter) {
        return new StepBuilder("postTransactions", jobRepository)
                .<DailyTransaction, PostingItem>chunk(1, transactionManager)
                .reader(dailyReader)
                .processor(processor)
                .writer(postingWriter)
                .stream(rejectFileWriter)
                .listener(postingWriter)
                .build();
    }

    @Bean
    public Job posttranJob(JobRepository jobRepository, Step postStep) {
        return new JobBuilder("POSTTRAN", jobRepository)
                .start(postStep)
                .listener(new JobExecutionListener() {
                    @Override
                    public void afterJob(JobExecution execution) {
                        long processed = execution.getStepExecutions().stream()
                                .mapToLong(step -> step.getReadCount()).sum();
                        long rejected = execution.getStepExecutions().stream()
                                .mapToLong(step -> step.getExecutionContext().getLong("rejectCount", 0)).sum();
                        LOG.info("TRANSACTIONS PROCESSED : {}", processed);
                        LOG.info("TRANSACTIONS REJECTED  : {}", rejected);
                        if (execution.getStatus().isUnsuccessful()) {
                            LOG.error("POSTTRAN failed: {}", execution.getAllFailureExceptions());
                        } else if (rejected > 0) {
                            execution.setExitStatus(new ExitStatus("4"));
                        }
                    }
                })
                .build();
    }
}
