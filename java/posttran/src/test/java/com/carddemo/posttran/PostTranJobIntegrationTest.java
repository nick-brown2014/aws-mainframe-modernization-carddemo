package com.carddemo.posttran;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.posttran.batch.PostTranJobListener;
import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.config.PostTranJobConfig;
import com.carddemo.posttran.domain.Account;
import com.carddemo.posttran.domain.TranCatBalance;
import com.carddemo.posttran.domain.TranCatBalanceId;
import com.carddemo.posttran.domain.Transaction;
import com.carddemo.posttran.repository.AccountRepository;
import com.carddemo.posttran.repository.TranCatBalanceRepository;
import com.carddemo.posttran.repository.TransactionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the whole POSTTRAN job against a small DALYTRAN file exercising every branch: posting with
 * category-balance create and update, credit and debit amounts, and all four reject reasons.
 */
@SpringBatchTest
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:posttran-it;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "posttran.chunk-size=3",
    "posttran.load.truncate=true"
})
class PostTranJobIntegrationTest {

    static final Path DATA_DIR = Path.of("target", "it-data");
    static final Path DALYTRAN = DATA_DIR.resolve("dalytran.txt");
    static final Path DALYREJS = DATA_DIR.resolve("dalyrejs.txt");

    static final String CARD_OK = "4000000000000001";
    static final String CARD_NO_ACCT = "4000000000000002";
    static final String CARD_LOW_LIMIT = "4000000000000003";
    static final String CARD_EXPIRED = "4000000000000004";
    static final String CARD_UNKNOWN = "4999999999999999";
    static final String TS = "2022-06-10 19:27:53.000000";

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(DATA_DIR);
        Files.deleteIfExists(DALYREJS);

        write(DATA_DIR.resolve("acctfile.txt"), List.of(
                account(1, "1000.00", "2030-01-01"),
                account(2, "100.00", "2030-01-01"),
                account(3, "1000.00", "2022-06-09")));
        write(DATA_DIR.resolve("xreffile.txt"), List.of(
                xref(CARD_OK, 1),
                xref(CARD_NO_ACCT, 99),
                xref(CARD_LOW_LIMIT, 2),
                xref(CARD_EXPIRED, 3)));
        write(DATA_DIR.resolve("tcatbalf.txt"), List.of(tcatbal(1, "01", 1, "10.00")));
        write(DALYTRAN, List.of(
                TestRecords.dalytranLine("T0000000000001", "01", 1, bd("50.00"), CARD_OK, TS),       // update tcatbal
                TestRecords.dalytranLine("T0000000000002", "02", 2, bd("25.00"), CARD_OK, TS),       // create tcatbal
                TestRecords.dalytranLine("T0000000000003", "01", 1, bd("-10.00"), CARD_OK, TS),      // debit, same chunk
                TestRecords.dalytranLine("T0000000000004", "01", 1, bd("5.00"), CARD_UNKNOWN, TS),   // 100
                TestRecords.dalytranLine("T0000000000005", "01", 1, bd("5.00"), CARD_NO_ACCT, TS),   // 101
                TestRecords.dalytranLine("T0000000000006", "01", 1, bd("150.00"), CARD_LOW_LIMIT, TS), // 102
                TestRecords.dalytranLine("T0000000000007", "01", 1, bd("5.00"), CARD_EXPIRED, TS)));  // 103

        registry.add("posttran.dalytran", DALYTRAN::toString);
        registry.add("posttran.dalyrejs", DALYREJS::toString);
        registry.add("posttran.load.acctfile", () -> DATA_DIR.resolve("acctfile.txt").toString());
        registry.add("posttran.load.xreffile", () -> DATA_DIR.resolve("xreffile.txt").toString());
        registry.add("posttran.load.tcatbalf", () -> DATA_DIR.resolve("tcatbalf.txt").toString());
    }

    @Autowired
    JobLauncherTestUtils jobLauncherTestUtils;
    @Autowired
    PostTranJobListener jobListener;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    TranCatBalanceRepository tranCatBalanceRepository;
    @Autowired
    TransactionRepository transactionRepository;

    @Test
    void postsValidTransactionsAndRejectsTheRest() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(PostTranJobListener.COMPLETED_WITH_REJECTS);
        assertThat(jobListener.getExitCode()).isEqualTo(4);

        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName()).isEqualTo(PostTranJobConfig.STEP_NAME);
        assertThat(step.getReadCount()).isEqualTo(7);
        assertThat(step.getWriteCount()).isEqualTo(7);

        // TRANFILE: only the three valid transactions
        List<Transaction> posted = transactionRepository.findAll();
        assertThat(posted).extracting(Transaction::getTranId)
                .containsExactlyInAnyOrder(
                        CobolFields.alnum("T0000000000001", 16),
                        CobolFields.alnum("T0000000000002", 16),
                        CobolFields.alnum("T0000000000003", 16));
        assertThat(posted).allSatisfy(t -> assertThat(t.getProcTs())
                .hasSize(26)
                .matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000"));

        // ACCTFILE: 50 + 25 - 10 applied to account 1; others untouched
        Account acct1 = accountRepository.findById(1L).orElseThrow();
        assertThat(acct1.getCurrBal()).isEqualByComparingTo("165.00");     // 100 + 65
        assertThat(acct1.getCurrCycCredit()).isEqualByComparingTo("75.00");
        assertThat(acct1.getCurrCycDebit()).isEqualByComparingTo("-10.00");
        assertThat(accountRepository.findById(2L).orElseThrow().getCurrBal()).isEqualByComparingTo("100.00");
        assertThat(accountRepository.findById(3L).orElseThrow().getCurrBal()).isEqualByComparingTo("100.00");

        // TCATBALF: existing (1,01,0001) 10 + 50 - 10 = 50; new (1,02,0002) = 25
        assertThat(balance(1, "01", 1)).isEqualByComparingTo("50.00");
        assertThat(balance(1, "02", 2)).isEqualByComparingTo("25.00");
        assertThat(tranCatBalanceRepository.count()).isEqualTo(2);

        // DALYREJS: four 430-byte records with the right trailers, in input order
        List<String> rejects = Files.readAllLines(DALYREJS, StandardCharsets.ISO_8859_1);
        assertThat(rejects).hasSize(4);
        assertThat(rejects).allSatisfy(r -> assertThat(r).hasSize(430));
        assertThat(rejects).extracting(r -> r.substring(0, 16).strip())
                .containsExactly("T0000000000004", "T0000000000005", "T0000000000006", "T0000000000007");
        assertThat(rejects).extracting(r -> r.substring(350, 354))
                .containsExactly("0100", "0101", "0102", "0103");
        assertThat(rejects).extracting(r -> r.substring(354).strip())
                .containsExactly(
                        "INVALID CARD NUMBER FOUND",
                        "ACCOUNT RECORD NOT FOUND",
                        "OVERLIMIT TRANSACTION",
                        "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThat(rejects.get(0).substring(0, 350))
                .isEqualTo(TestRecords.dalytranLine("T0000000000004", "01", 1, bd("5.00"), CARD_UNKNOWN, TS));
    }

    private BigDecimal balance(long acct, String type, int cat) {
        return tranCatBalanceRepository.findById(new TranCatBalanceId(acct, type, cat))
                .map(TranCatBalance::getBalance)
                .orElseThrow();
    }

    private static String account(long id, String creditLimit, String expiration) {
        return CobolFields.formatUnsigned(id, 11)
                + "Y"
                + CobolFields.formatZoned(bd("100.00"), 12, 2, true)
                + CobolFields.formatZoned(bd(creditLimit), 12, 2, true)
                + CobolFields.formatZoned(bd("500.00"), 12, 2, true)
                + "2014-11-20" + expiration + "2025-05-20"
                + CobolFields.formatZoned(bd("0.00"), 12, 2, true)
                + CobolFields.formatZoned(bd("0.00"), 12, 2, true)
                + CobolFields.alnum("72112", 10)
                + CobolFields.alnum("A000000000", 10)
                + " ".repeat(178);
    }

    private static String xref(String card, long acctId) {
        return card + CobolFields.formatUnsigned(acctId, 9) + CobolFields.formatUnsigned(acctId, 11) + " ".repeat(14);
    }

    private static String tcatbal(long acctId, String type, int cat, String bal) {
        return CobolFields.formatUnsigned(acctId, 11) + type + CobolFields.formatUnsigned(cat, 4)
                + CobolFields.formatZoned(bd(bal), 11, 2, true) + " ".repeat(22);
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.write(file, lines, StandardCharsets.ISO_8859_1);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
