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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spec section 8.3 parity harness. Runs the job over {@code app/data/ASCII/dailytran.txt} and compares, byte for byte,
 * against COBOL-produced golden files under {@code src/test/resources/golden/posttran/}:
 * <ul>
 *   <li>{@code dalyrejs.txt} - 430-byte reject records in input order</li>
 *   <li>{@code transact.txt} - 350-byte TRANSACT records (CVTRA05Y) ordered by TRAN-ID, with the 26-byte
 *       TRAN-PROC-TS field masked</li>
 *   <li>{@code acctdata.txt} - 300-byte ACCTDATA records (CVACT01Y) ordered by ACCT-ID</li>
 *   <li>{@code tcatbal.txt} - 50-byte TCATBAL records (CVTRA01Y) ordered by key</li>
 * </ul>
 * Missing golden files are skipped individually; the class is disabled until at least one golden file exists.
 */
@Disabled("golden files pending")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:posttran-parity;DB_CLOSE_DELAY=-1")
class PostTranParityIT {

    static final Path GOLDEN = Paths.get("src", "test", "resources", "golden", "posttran").toAbsolutePath();

    @Autowired PostTranLauncher launcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionRepository transactions;
    @Autowired AccountRepository accounts;
    @Autowired TranCategoryBalanceRepository balances;

    @TempDir Path dir;

    @BeforeAll
    static void requireGoldenFiles() throws IOException {
        try (Stream<Path> files = Files.exists(GOLDEN) ? Files.list(GOLDEN) : Stream.empty()) {
            assumeTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".txt")),
                    "no golden files under " + GOLDEN);
        }
    }

    private Path runJob() {
        Path rejects = dir.resolve("dalyrejs.txt");
        launcher.run(new DefaultApplicationArguments(
                "--loadCardXrefFile=" + TestData.ASCII_DATA.resolve("cardxref.txt"),
                "--loadAcctFile=" + TestData.ASCII_DATA.resolve("acctdata.txt"),
                "--loadTcatbalFile=" + TestData.ASCII_DATA.resolve("tcatbal.txt"),
                "--dailyTranFile=" + TestData.ASCII_DATA.resolve("dailytran.txt"),
                "--rejectsFile=" + rejects,
                "--runDate=parity"));
        JobExecution e = launcher.lastExecution();
        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return rejects;
    }

    private static List<String> golden(String name) throws IOException {
        Path p = GOLDEN.resolve(name);
        assumeTrue(Files.exists(p), "golden file missing: " + p);
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    private static String maskProcTs(String transactRecord) {
        return transactRecord.substring(0, 304) + "*".repeat(26) + transactRecord.substring(330);
    }

    @Test
    @DisplayName("8.3: rejects file is byte-identical to the COBOL DALYREJS output")
    void rejectsParity() throws IOException {
        Path rejects = runJob();
        assertThat(Files.readAllLines(rejects, StandardCharsets.UTF_8)).isEqualTo(golden("dalyrejs.txt"));
    }

    @Test
    @DisplayName("8.3: TRANSACT, ACCTDATA and TCATBAL extracts match the COBOL outputs")
    void datasetParity() throws IOException {
        runJob();

        List<String> transact = new ArrayList<>();
        for (Transaction t : transactions.findAllOrderedByKey()) {
            transact.add(maskProcTs(CopybookRecordFormatter.formatTransaction(t)));
        }
        List<String> goldenTransact = new ArrayList<>();
        for (String line : golden("transact.txt")) {
            goldenTransact.add(maskProcTs(line));
        }
        assertThat(transact).isEqualTo(goldenTransact);

        List<String> acct = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT acct_id FROM account ORDER BY acct_id", Long.class)) {
            Account a = accounts.findById(id).orElseThrow();
            acct.add(CopybookRecordFormatter.formatAccount(a));
        }
        assertThat(acct).isEqualTo(golden("acctdata.txt"));

        List<String> tcat = new ArrayList<>();
        for (TranCategoryBalance b : balances.findAllOrderedByKey()) {
            tcat.add(CopybookRecordFormatter.formatTranCategoryBalance(b));
        }
        assertThat(tcat).isEqualTo(golden("tcatbal.txt"));
    }
}
