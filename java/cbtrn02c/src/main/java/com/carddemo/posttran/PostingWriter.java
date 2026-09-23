package com.carddemo.posttran;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemWriter;

public class PostingWriter implements ItemWriter<PostingItem>, StepExecutionListener {
    private static final DateTimeFormatter DB2_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'");

    private final PostingRepository repository;
    private final FlatFileItemWriter<String> rejects;
    private final Clock clock;
    private StepExecution step;

    public PostingWriter(PostingRepository repository, FlatFileItemWriter<String> rejects, Clock clock) {
        this.repository = repository;
        this.rejects = rejects;
        this.clock = clock;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        step = stepExecution;
    }

    @Override
    public void write(Chunk<? extends PostingItem> items) throws Exception {
        for (PostingItem item : items) {
            if (item.isRejected()) {
                rejects.write(new Chunk<>(List.of(item.rejectRecord())));
                step.getExecutionContext().putLong("rejectCount",
                        step.getExecutionContext().getLong("rejectCount", 0) + 1);
            } else {
                post(item);
            }
        }
    }

    private void post(PostingItem item) {
        DailyTransaction daily = item.daily();
        var existing = repository.balance(item.accountId(), daily.typeCd(), daily.catCd());
        TranCatBalance balance = existing.orElseGet(() -> new TranCatBalance(item.accountId(),
                daily.typeCd(), daily.catCd(), BigDecimal.ZERO.setScale(2), " ".repeat(22)));
        repository.saveBalance(balance.add(daily.amount()), existing.isPresent());

        Account account = repository.account(item.accountId())
                .orElseThrow(() -> new IllegalStateException("Account disappeared during posting"));
        repository.updateAccount(account.post(daily.amount()));
        repository.insertTransaction(Transaction.posted(daily,
                LocalDateTime.now(clock).format(DB2_TIMESTAMP)));
    }
}
