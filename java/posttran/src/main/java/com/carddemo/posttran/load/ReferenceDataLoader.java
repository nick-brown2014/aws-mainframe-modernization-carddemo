package com.carddemo.posttran.load;

import com.carddemo.posttran.config.PostTranProperties;
import com.carddemo.posttran.layout.AccountLayout;
import com.carddemo.posttran.layout.CardXrefLayout;
import com.carddemo.posttran.layout.TranCatBalanceLayout;
import com.carddemo.posttran.repository.AccountRepository;
import com.carddemo.posttran.repository.CardXrefRepository;
import com.carddemo.posttran.repository.TranCatBalanceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads fixed-width VSAM extracts (the {@code app/data/ASCII} files) into the ACCOUNT, CARD_XREF and
 * TRAN_CAT_BAL tables before the job runs. Runs ahead of Spring Boot's job launcher (order 0).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReferenceDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataLoader.class);

    private final PostTranProperties props;
    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TranCatBalanceRepository tranCatBalanceRepository;

    public ReferenceDataLoader(PostTranProperties props,
                               AccountRepository accountRepository,
                               CardXrefRepository cardXrefRepository,
                               TranCatBalanceRepository tranCatBalanceRepository) {
        this.props = props;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.tranCatBalanceRepository = tranCatBalanceRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        PostTranProperties.Load load = props.load();
        load("ACCTFILE", load.acctfile(), AccountLayout::parse, accountRepository, load.truncate());
        load("XREFFILE", load.xreffile(), CardXrefLayout::parse, cardXrefRepository, load.truncate());
        load("TCATBALF", load.tcatbalf(), TranCatBalanceLayout::parse, tranCatBalanceRepository, load.truncate());
    }

    private <T> void load(String dd, String file, Function<String, T> parser,
                          JpaRepository<T, ?> repository, boolean truncate) throws IOException {
        if (file == null || file.isBlank()) {
            return;
        }
        if (truncate) {
            repository.deleteAllInBatch();
        }
        try (Stream<String> lines = Files.lines(Path.of(file), StandardCharsets.ISO_8859_1)) {
            List<T> records = lines
                    .map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l)
                    .filter(l -> !l.isBlank())
                    .map(parser)
                    .toList();
            repository.saveAll(records);
            log.info("Loaded {} {} records from {}", records.size(), dd, file);
        }
    }
}
