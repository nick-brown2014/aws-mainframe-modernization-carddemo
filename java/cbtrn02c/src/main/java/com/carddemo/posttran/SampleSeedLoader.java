package com.carddemo.posttran;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
@ConditionalOnProperty(name = "posttran.seed-directory")
public class SampleSeedLoader implements ApplicationRunner {
    private final PostingRepository repository;
    private final Path directory;

    public SampleSeedLoader(PostingRepository repository, @Value("${posttran.seed-directory}") String directory) {
        this.repository = repository;
        this.directory = Path.of(directory);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (String raw : Files.readAllLines(directory.resolve("acctdata.txt"), StandardCharsets.US_ASCII)) {
            Account account = Account.fromRecord(raw);
            if (repository.account(account.id()).isEmpty()) {
                repository.insertAccount(account);
            }
        }
        for (String raw : Files.readAllLines(directory.resolve("cardxref.txt"), StandardCharsets.US_ASCII)) {
            CardXref card = CardXref.fromRecord(raw.length() == 36 ? raw + " ".repeat(14) : raw);
            if (repository.card(card.cardNum()).isEmpty()) {
                repository.insertCard(card);
            }
        }
        for (String raw : Files.readAllLines(directory.resolve("tcatbal.txt"), StandardCharsets.US_ASCII)) {
            TranCatBalance balance = TranCatBalance.fromRecord(raw);
            if (repository.balance(balance.acctId(), balance.typeCd(), balance.catCd()).isEmpty()) {
                repository.saveBalance(balance, false);
            }
        }
    }
}
