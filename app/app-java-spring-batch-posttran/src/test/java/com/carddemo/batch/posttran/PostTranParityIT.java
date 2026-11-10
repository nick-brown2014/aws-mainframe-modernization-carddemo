package com.carddemo.batch.posttran;

import com.carddemo.batch.posttran.io.CopybookRecordFormatter;
import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.TranCategoryBalance;
import com.carddemo.batch.posttran.model.Transaction;
import com.carddemo.batch.posttran.repo.AccountRepository;
import com.carddemo.batch.posttran.repo.TranCategoryBalanceRepository;
import com.carddemo.batch.posttran.repo.TransactionRepository;
import com.carddemo.batch.posttran.support.TestData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spec section 8.3 parity harness. Runs the job over {@code app/data/ASCII/dailytran.txt} and compares, byte for byte,
 * against the COBOL outputs under {@code src/test/resources/golden/posttran/} (produced by
 * {@code golden-harness/run-golden.sh}: CBTRN02C compiled with GnuCOBOL, EBCDIC sign overpunch):
 * <ul>
 *   <li>{@code dalyrejs.txt} - 430-byte reject records in input order</li>
 *   <li>{@code transact.txt} - 350-byte TRANSACT records (CVTRA05Y) ordered by TRAN-ID; TRAN-PROC-TS (305-330)
 *       is checked by pattern and against job start time instead of by value</li>
 *   <li>{@code acctdata.txt} - 300-byte ACCTDATA records (CVACT01Y) ordered by ACCT-ID</li>
 *   <li>{@code tcatbal.txt} - 50-byte TCATBAL records (CVTRA01Y) ordered by key</li>
 *   <li>{@code sysout.txt} / {@code returncode.txt} - DISPLAY output and RETURN-CODE</li>
 * </ul>
 * Reviewed exclusion: TCATBAL FILLER (bytes 29-50). The COBOL leaves whatever the last READ INTO put in
 * working storage (zeros in the sample data); spec section 4.9 does not persist FILLER and regenerates it as spaces.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:posttran-parity;DB_CLOSE_DELAY=-1")
@Import(PostTranParityIT.ConsoleConfig.class)
class PostTranParityIT {

    static final Path GOLDEN = Paths.get("src", "test", "resources", "golden", "posttran").toAbsolutePath();
    static final Pattern PROC_TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000");
    static final DateTimeFormatter PROC_TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");
    static final ByteArrayOutputStream CONSOLE = new ByteArrayOutputStream();

    @TestConfiguration
    static class ConsoleConfig {
        @Bean
        @Primary
        ProgramConsole capturingConsole() {
            return new ProgramConsole(new PrintStream(CONSOLE, true, StandardCharsets.UTF_8));
        }
    }

    @Autowired PostTranLauncher launcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionRepository transactions;
    @Autowired AccountRepository accounts;
    @Autowired TranCategoryBalanceRepository balances;
    @Autowired java.time.Clock clock;

    @TempDir Path dir;

    @BeforeAll
    static void requireGoldenFiles() {
        assumeTrue(Files.exists(GOLDEN.resolve("dalyrejs.txt")), "no golden files under " + GOLDEN);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM transaction");
        jdbc.update("DELETE FROM tran_cat_bal");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM card_xref");
        CONSOLE.reset();
    }

    private Path runJob(String runDate) {
        Path rejects = dir.resolve("dalyrejs.txt");
        launcher.run(new DefaultApplicationArguments(
                "--loadCardXrefFile=" + TestData.ASCII_DATA.resolve("cardxref.txt"),
                "--loadAcctFile=" + TestData.ASCII_DATA.resolve("acctdata.txt"),
                "--loadTcatbalFile=" + TestData.ASCII_DATA.resolve("tcatbal.txt"),
                "--dailyTranFile=" + TestData.ASCII_DATA.resolve("dailytran.txt"),
                "--rejectsFile=" + rejects,
                "--runDate=" + runDate));
        JobExecution e = launcher.lastExecution();
        assertThat(e).as("job execution (see console: %s)", CONSOLE.toString(StandardCharsets.UTF_8)).isNotNull();
        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return rejects;
    }

    private static List<String> golden(String name) throws IOException {
        return Files.readAllLines(GOLDEN.resolve(name), StandardCharsets.UTF_8);
    }

    private static String maskProcTs(String transactRecord) {
        return transactRecord.substring(0, 304) + "*".repeat(26) + transactRecord.substring(330);
    }

    private static String withoutTcatbalFiller(String tcatbalRecord) {
        assertThat(tcatbalRecord).hasSize(50);
        return tcatbalRecord.substring(0, 28);
    }

    @Test
    @DisplayName("8.3(4): rejects file is byte-identical to the COBOL DALYREJS output")
    void rejectsParity() throws IOException {
        Path rejects = runJob("parity-rejects");
        assertThat(Files.readAllBytes(rejects)).isEqualTo(Files.readAllBytes(GOLDEN.resolve("dalyrejs.txt")));
    }

    @Test
    @DisplayName("8.3(1-3): TRANSACT, ACCTDATA and TCATBAL extracts match the COBOL outputs")
    void datasetParity() throws IOException {
        LocalDateTime jobStart = LocalDateTime.now(clock).withNano(0);
        runJob("parity-datasets");

        List<String> transact = new ArrayList<>();
        for (Transaction t : transactions.findAllOrderedByKey()) {
            String record = CopybookRecordFormatter.formatTransaction(t);
            String procTs = record.substring(304, 330);
            assertThat(procTs).matches(PROC_TS);
            assertThat(LocalDateTime.parse(procTs, PROC_TS_FORMAT)).isAfterOrEqualTo(jobStart);
            transact.add(maskProcTs(record));
        }
        List<String> goldenTransact = golden("transact.txt").stream().map(PostTranParityIT::maskProcTs).toList();
        assertThat(transact).hasSameSizeAs(goldenTransact).isEqualTo(goldenTransact);

        List<String> acct = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT acct_id FROM account ORDER BY acct_id", Long.class)) {
            Account a = accounts.findById(id).orElseThrow();
            acct.add(CopybookRecordFormatter.formatAccount(a));
        }
        assertThat(acct).isEqualTo(golden("acctdata.txt"));

        List<String> tcat = new ArrayList<>();
        for (TranCategoryBalance b : balances.findAllOrderedByKey()) {
            tcat.add(withoutTcatbalFiller(CopybookRecordFormatter.formatTranCategoryBalance(b)));
        }
        List<String> goldenTcat = golden("tcatbal.txt").stream().map(PostTranParityIT::withoutTcatbalFiller).toList();
        assertThat(tcat).isEqualTo(goldenTcat);
    }

    @Test
    @DisplayName("8.3(5): console output and exit code match the COBOL SYSOUT and RETURN-CODE")
    void consoleAndExitCodeParity() throws IOException {
        runJob("parity-console");
        List<String> console = CONSOLE.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(console).isEqualTo(golden("sysout.txt"));
        assertThat(launcher.getExitCode()).isEqualTo(Integer.parseInt(golden("returncode.txt").get(0).trim()));
    }
}
