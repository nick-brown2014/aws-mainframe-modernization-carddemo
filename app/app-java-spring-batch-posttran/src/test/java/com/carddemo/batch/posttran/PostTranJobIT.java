package com.carddemo.batch.posttran;

import com.carddemo.batch.posttran.listener.PostTranExitCodeMapper;
import com.carddemo.batch.posttran.listener.PostTranJobListener;
import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.TranCategoryBalance;
import com.carddemo.batch.posttran.model.Transaction;
import com.carddemo.batch.posttran.repo.AccountRepository;
import com.carddemo.batch.posttran.repo.CardXrefRepository;
import com.carddemo.batch.posttran.repo.JdbcTransactionRepository;
import com.carddemo.batch.posttran.repo.TranCategoryBalanceRepository;
import com.carddemo.batch.posttran.repo.TransactionRepository;
import com.carddemo.batch.posttran.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.carddemo.batch.posttran.support.TestData.ACCT_ID;
import static com.carddemo.batch.posttran.support.TestData.CARD;
import static com.carddemo.batch.posttran.support.TestData.amt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job-level acceptance tests on H2: AC-R5, AC-P4..P9, AC-O1..O4, AC-X2 and the 300-record end-to-end run.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:posttran-it;DB_CLOSE_DELAY=-1")
@Import(PostTranJobIT.TestConfig.class)
class PostTranJobIT {

    static final String PROC_TS = "2026-09-23-13.15.42.370000";
    static final ByteArrayOutputStream CONSOLE = new ByteArrayOutputStream();
    static final AtomicReference<String> FAIL_ON_TRAN_ID = new AtomicReference<>();

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-23T18:15:42.376Z"), ZoneId.of("America/Chicago"));
        }

        @Bean
        @Primary
        ProgramConsole capturingConsole() {
            return new ProgramConsole(new PrintStream(CONSOLE, true, StandardCharsets.UTF_8));
        }

        /** Failure injection for AC-X2: throws once when the configured tran_id is inserted. */
        @Bean
        @Primary
        TransactionRepository failingTransactionRepository(JdbcTransactionRepository delegate) {
            return new TransactionRepository() {
                @Override
                public void insert(Transaction tran) {
                    if (tran.tranId().equals(FAIL_ON_TRAN_ID.get())) {
                        FAIL_ON_TRAN_ID.set(null);
                        throw new IllegalStateException("injected failure at " + tran.tranId());
                    }
                    delegate.insert(tran);
                }

                @Override
                public Optional<Transaction> findById(String tranId) {
                    return delegate.findById(tranId);
                }

                @Override
                public List<Transaction> findAllOrderedByKey() {
                    return delegate.findAllOrderedByKey();
                }
            };
        }
    }

    @Autowired JobLauncher jobLauncher;
    @Autowired Job postTranJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired CardXrefRepository xrefRepo;
    @Autowired AccountRepository acctRepo;
    @Autowired TranCategoryBalanceRepository balRepo;
    @Autowired TransactionRepository tranRepo;
    @Autowired PostTranLauncher launcher;

    @TempDir Path dir;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM transaction");
        jdbc.update("DELETE FROM tran_cat_bal");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM card_xref");
        CONSOLE.reset();
        FAIL_ON_TRAN_ID.set(null);
    }

    private String console() {
        return CONSOLE.toString(StandardCharsets.UTF_8);
    }

    private Path write(String name, List<String> lines) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, lines, StandardCharsets.UTF_8);
        return p;
    }

    private JobParameters params(Path input, Path rejects, String runDate, int chunkSize) {
        return new JobParametersBuilder()
                .addString(PostTranJobConfig.PARAM_RUN_DATE, runDate, true)
                .addString(PostTranJobConfig.PARAM_DAILY_TRAN_FILE, input.toString(), false)
                .addString(PostTranJobConfig.PARAM_REJECTS_FILE, rejects.toString(), false)
                .addLong(PostTranJobConfig.PARAM_CHUNK_SIZE, (long) chunkSize, false)
                .toJobParameters();
    }

    private JobExecution run(Path input, Path rejects, int chunkSize) throws Exception {
        return jobLauncher.run(postTranJob, params(input, rejects, UUID.randomUUID().toString(), chunkSize));
    }

    private void seedAccount(long acctId, String card, String currBal, String creditLimit, String cycCredit,
                             String cycDebit) {
        xrefRepo.insert(TestData.xref(card, acctId));
        acctRepo.insert(TestData.account(acctId, currBal, creditLimit, "2030-12-31", cycCredit, cycDebit));
    }

    private static StepExecution step(JobExecution e) {
        return e.getStepExecutions().iterator().next();
    }

    private List<String> rejectLines(Path rejects) throws IOException {
        byte[] bytes = Files.readAllBytes(rejects);
        List<String> lines = new ArrayList<>();
        for (String s : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        return lines;
    }

    @Test
    @DisplayName("AC-R5: 349-byte record fails the step with FlatFileParseException")
    void shortRecordFailsStep() throws Exception {
        seedAccount(ACCT_ID, CARD, "0.00", "1000.00", "0.00", "0.00");
        String line = TestData.dailyLine("0000000000000001", amt("1.00"));
        Path in = write("short.txt", List.of(line.substring(0, 349)));
        JobExecution e = run(in, dir.resolve("rej.txt"), 100);
        assertThat(e.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(e).getFailureExceptions()).anySatisfy(t -> assertThat(t).isInstanceOf(FlatFileParseException.class));
        assertThat(PostTranExitCodeMapper.exitCode(e)).isEqualTo(8);
        assertThat(console()).contains("ABENDING PROGRAM");
    }

    @Test
    @DisplayName("AC-P4/P5/P7/O2: posting persists tran_cat_bal (insert then update), account and transaction rows")
    void postsToDatabase() throws Exception {
        seedAccount(ACCT_ID, CARD, "100.00", "5000.00", "10.00", "-5.00");
        Path in = write("in.txt", List.of(
                TestData.dailyLine("0000000000000001", amt("100.00")),
                TestData.dailyLine("0000000000000002", amt("-40.00"))));
        Path rejects = dir.resolve("rej.txt");
        JobExecution e = run(in, rejects, 1);

        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(PostTranExitCodeMapper.exitCode(e)).isEqualTo(0);
        assertThat(rejects).exists().isEmptyFile();

        TranCategoryBalance bal = balRepo.findByKey(ACCT_ID, "01", 1).orElseThrow();
        assertThat(bal.tranCatBal()).isEqualTo(amt("60.00"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tran_cat_bal", Integer.class)).isEqualTo(1);

        Account a = acctRepo.findById(ACCT_ID).orElseThrow();
        assertThat(a.currBal()).isEqualTo(amt("160.00"));
        assertThat(a.currCycCredit()).isEqualTo(amt("110.00"));
        assertThat(a.currCycDebit()).isEqualTo(amt("-45.00"));

        Transaction t = tranRepo.findById("0000000000000001").orElseThrow();
        assertThat(t.tranAmt()).isEqualTo(amt("100.00"));
        assertThat(t.cardNum()).isEqualTo(CARD);
        assertThat(t.origTs()).isEqualTo(TestData.ORIG_TS);
        assertThat(t.procTs()).isEqualTo(PROC_TS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction", Integer.class)).isEqualTo(2);

        assertThat(console()).isEqualTo(String.join("\n",
                "START OF EXECUTION OF PROGRAM CBTRN02C",
                "TCATBAL record not found for key : 00000000001010001.. Creating.",
                "TRANSACTIONS PROCESSED :000000002",
                "TRANSACTIONS REJECTED  :000000000",
                "END OF EXECUTION OF PROGRAM CBTRN02C") + "\n");
    }

    @Test
    @DisplayName("AC-P6: tran_cat_bal key comes from xref.acctId")
    void categoryKeyFromXref() throws Exception {
        xrefRepo.insert(new CardXref(CARD, 5L, 42L));
        acctRepo.insert(TestData.account(42L, "0.00", "1000.00", "2030-12-31", "0.00", "0.00"));
        Path in = write("in.txt", List.of(TestData.dailyLine("0000000000000001", amt("7.00"))));
        JobExecution e = run(in, dir.resolve("rej.txt"), 100);
        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(balRepo.findByKey(42L, "01", 1)).isPresent();
        assertThat(console()).contains("key : 00000000042010001..");
    }

    @Test
    @DisplayName("AC-P8: two transactions for one account in a single chunk apply sequentially")
    void intraChunkConsistency() throws Exception {
        seedAccount(ACCT_ID, CARD, "0.00", "1000.00", "0.00", "0.00");
        Path in = write("in.txt", List.of(
                TestData.dailyLine("0000000000000001", amt("600.00")),
                TestData.dailyLine("0000000000000002", amt("300.00")),
                TestData.dailyLine("0000000000000003", amt("200.00"))));
        Path rejects = dir.resolve("rej.txt");
        JobExecution e = run(in, rejects, 100);
        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Account a = acctRepo.findById(ACCT_ID).orElseThrow();
        assertThat(a.currBal()).isEqualTo(amt("900.00"));
        assertThat(a.currCycCredit()).isEqualTo(amt("900.00"));
        assertThat(balRepo.findByKey(ACCT_ID, "01", 1).orElseThrow().tranCatBal()).isEqualTo(amt("900.00"));
        List<String> rej = rejectLines(rejects);
        assertThat(rej).hasSize(1);
        assertThat(rej.get(0)).startsWith("0000000000000003").contains("0102OVERLIMIT TRANSACTION");
        assertThat(PostTranExitCodeMapper.exitCode(e)).isEqualTo(4);
    }

    @Test
    @DisplayName("AC-P9: duplicate tran_id fails the step, rolls back the chunk, exit code 8")
    void duplicateTranIdFails() throws Exception {
        seedAccount(ACCT_ID, CARD, "0.00", "1000.00", "0.00", "0.00");
        Path in = write("in.txt", List.of(
                TestData.dailyLine("0000000000000001", amt("10.00")),
                TestData.dailyLine("0000000000000001", amt("20.00"))));
        JobExecution e = run(in, dir.resolve("rej.txt"), 100);
        assertThat(e.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(e).getFailureExceptions()).anySatisfy(t -> assertThat(t).isInstanceOf(DuplicateKeyException.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction", Integer.class)).isZero();
        assertThat(acctRepo.findById(ACCT_ID).orElseThrow().currBal()).isEqualTo(amt("0.00"));
        int exit = PostTranExitCodeMapper.exitCode(e);
        assertThat(exit).isNotIn(0, 4).isEqualTo(8);
    }

    @Test
    @DisplayName("AC-O1/O3/O4: 430-byte reject layout, counts and exit code 4")
    void rejectFileLayoutAndCounts() throws Exception {
        seedAccount(ACCT_ID, CARD, "0.00", "1000.00", "0.00", "0.00");
        String badCard = TestData.dailyLine("0000000000000001", "01", 1, amt("1.00"), "9999999999999999", TestData.ORIG_TS);
        Path in = write("in.txt", List.of(badCard, TestData.dailyLine("0000000000000002", amt("1.00"))));
        Path rejects = dir.resolve("rej.txt");
        JobExecution e = run(in, rejects, 100);

        List<String> rej = rejectLines(rejects);
        assertThat(rej).hasSize(1);
        String r = rej.get(0);
        assertThat(r.getBytes(StandardCharsets.UTF_8)).hasSize(430);
        assertThat(r.substring(0, 350)).isEqualTo(badCard);
        assertThat(r.substring(350, 354)).isEqualTo("0100");
        assertThat(r.substring(354)).isEqualTo(String.format("%-76s", "INVALID CARD NUMBER FOUND")).hasSize(76);

        assertThat(PostTranExitCodeMapper.exitCode(e)).isEqualTo(4);
        assertThat(e.getExitStatus().getExitCode()).isEqualTo(PostTranJobListener.COMPLETED_WITH_REJECTS);
        assertThat(console()).contains("TRANSACTIONS PROCESSED :000000002")
                .contains("TRANSACTIONS REJECTED  :000000001")
                .endsWith("END OF EXECUTION OF PROGRAM CBTRN02C\n");
    }

    @Test
    @DisplayName("AC-X2: failure in chunk N rolls back chunk N (DB and rejects file); restart resumes and matches a clean run")
    void chunkFailureAndRestart() throws Exception {
        seedAccount(1L, "4000000000000001", "0.00", "1000.00", "0.00", "0.00");
        seedAccount(2L, "4000000000000002", "0.00", "50.00", "0.00", "0.00");
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            // odd records post to account 1; even records overlimit on account 2 -> reject (one per chunk of 2)
            boolean odd = i % 2 == 1;
            lines.add(TestData.dailyLine(String.format("%016d", i), "01", 1, amt(odd ? "10.00" : "60.00"),
                    odd ? "4000000000000001" : "4000000000000002", TestData.ORIG_TS));
        }
        Path in = write("in.txt", lines);
        Path rejects = dir.resolve("rej.txt");
        String runDate = "2026-09-23-" + UUID.randomUUID();

        FAIL_ON_TRAN_ID.set(String.format("%016d", 5)); // chunk 3 (records 5,6) with chunkSize=2
        JobExecution first = jobLauncher.run(postTranJob, params(in, rejects, runDate, 2));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(PostTranExitCodeMapper.exitCode(first)).isEqualTo(8);
        assertThat(step(first).getCommitCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction", Integer.class)).isEqualTo(2);
        assertThat(acctRepo.findById(1L).orElseThrow().currBal()).isEqualTo(amt("20.00"));
        assertThat(rejectLines(rejects)).hasSize(2);
        assertThat(console()).contains("ERROR injected failure at 0000000000000005").contains("ABENDING PROGRAM");

        CONSOLE.reset();
        JobExecution second = jobLauncher.run(postTranJob, params(in, rejects, runDate, 2));
        assertThat(second.getJobId()).isEqualTo(first.getJobId());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(PostTranExitCodeMapper.exitCode(second)).isEqualTo(4);
        assertThat(step(second).getReadCount()).isEqualTo(5);
        assertThat(console()).contains("TRANSACTIONS PROCESSED :000000009").contains("TRANSACTIONS REJECTED  :000000004");

        List<String> restarted = rejectLines(rejects);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction", Integer.class)).isEqualTo(5);
        Account restartedAcct = acctRepo.findById(1L).orElseThrow();

        // uninterrupted reference run
        reset();
        seedAccount(1L, "4000000000000001", "0.00", "1000.00", "0.00", "0.00");
        seedAccount(2L, "4000000000000002", "0.00", "50.00", "0.00", "0.00");
        Path cleanRejects = dir.resolve("clean-rej.txt");
        JobExecution clean = run(in, cleanRejects, 2);
        assertThat(clean.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted).isEqualTo(rejectLines(cleanRejects));
        assertThat(restartedAcct).isEqualTo(acctRepo.findById(1L).orElseThrow());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction", Integer.class)).isEqualTo(5);
    }

    @Test
    @DisplayName("E2E: 300-record dailytran.txt against the ASCII master files")
    void endToEndSampleData() throws Exception {
        Path rejects = dir.resolve("dalyrejs.txt");
        String runDate = "e2e-" + UUID.randomUUID();
        var loaderArgs = new org.springframework.boot.DefaultApplicationArguments(
                "--loadCardXrefFile=" + TestData.ASCII_DATA.resolve("cardxref.txt"),
                "--loadAcctFile=" + TestData.ASCII_DATA.resolve("acctdata.txt"),
                "--loadTcatbalFile=" + TestData.ASCII_DATA.resolve("tcatbal.txt"),
                "--dailyTranFile=" + TestData.ASCII_DATA.resolve("dailytran.txt"),
                "--rejectsFile=" + rejects,
                "--runDate=" + runDate);
        launcher.run(loaderArgs);
        JobExecution e = launcher.lastExecution();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_xref", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class)).isEqualTo(50);
        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution s = step(e);
        assertThat(s.getReadCount()).isEqualTo(300);
        List<String> rej = rejectLines(rejects);
        long posted = jdbc.queryForObject("SELECT COUNT(*) FROM transaction", Long.class);
        assertThat(posted + rej.size()).isEqualTo(300);
        assertThat(rej).allSatisfy(r -> assertThat(r.getBytes(StandardCharsets.UTF_8)).hasSize(430));
        assertThat(console()).contains("TRANSACTIONS PROCESSED :000000300")
                .contains(String.format("TRANSACTIONS REJECTED  :%09d", rej.size()));
        assertThat(launcher.getExitCode()).isEqualTo(rej.isEmpty() ? 0 : 4);

        // AC-P7 on real data: proc_ts is the injected clock rendered in America/Chicago
        assertThat(jdbc.queryForList("SELECT DISTINCT proc_ts FROM transaction", String.class)).containsExactly(PROC_TS);

        // Ledger identity: sum of posted amounts == sum of account balance deltas == sum of category balance deltas
        BigDecimal sumPosted = jdbc.queryForObject("SELECT COALESCE(SUM(tran_amt),0) FROM transaction", BigDecimal.class);
        BigDecimal sumCat = jdbc.queryForObject("SELECT COALESCE(SUM(tran_cat_bal),0) FROM tran_cat_bal", BigDecimal.class);
        assertThat(sumCat).isEqualByComparingTo(sumPosted);
    }
}
