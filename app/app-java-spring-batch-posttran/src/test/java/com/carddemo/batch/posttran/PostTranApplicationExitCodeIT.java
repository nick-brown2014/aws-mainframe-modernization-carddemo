package com.carddemo.batch.posttran;

import com.carddemo.batch.posttran.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.carddemo.batch.posttran.support.TestData.amt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Process-level exit codes through the real Spring Boot entry point (spec 7.5): AC-O2, AC-O3, AC-X1.
 * Each run gets its own in-memory database so runs are independent.
 */
class PostTranApplicationExitCodeIT {

    @TempDir Path dir;

    private static String freshDb() {
        return "--spring.datasource.url=jdbc:h2:mem:exit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private Path masters(String name, List<String> lines) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, lines, StandardCharsets.UTF_8);
        return p;
    }

    private String[] argsFor(Path daily, Path rejects, String db) throws Exception {
        Path xref = masters("xref.txt", List.of(TestData.xrefLine(TestData.xref(TestData.CARD, TestData.ACCT_ID))));
        Path acct = masters("acct.txt", List.of(TestData.accountLine(TestData.account("1000.00", "0.00", "0.00"))));
        return new String[] {
                db,
                "--loadCardXrefFile=" + xref,
                "--loadAcctFile=" + acct,
                "--dailyTranFile=" + daily,
                "--rejectsFile=" + rejects,
                "--runDate=" + UUID.randomUUID()};
    }

    private static String capture(Runnable r) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-O2: zero rejects -> empty rejects file, exit code 0")
    void cleanRunExitsZero() throws Exception {
        Path daily = masters("daily.txt", List.of(TestData.dailyLine("0000000000000001", amt("10.00"))));
        Path rejects = dir.resolve("rejects.txt");
        int[] exit = new int[1];
        String out = capture(() -> {
            try {
                exit[0] = PostTranApplication.execute(argsFor(daily, rejects, freshDb()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(exit[0]).isZero();
        assertThat(rejects).exists().isEmptyFile();
        assertThat(out).contains("START OF EXECUTION OF PROGRAM CBTRN02C")
                .contains("TRANSACTIONS PROCESSED :000000001")
                .contains("TRANSACTIONS REJECTED  :000000000")
                .contains("END OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Test
    @DisplayName("AC-O3: at least one reject -> exit code 4 and matching REJECTED count")
    void rejectsExitFour() throws Exception {
        Path daily = masters("daily.txt", List.of(
                TestData.dailyLine("0000000000000001", amt("10.00")),
                TestData.dailyLine("0000000000000002", amt("2000.00"))));
        Path rejects = dir.resolve("rejects.txt");
        int[] exit = new int[1];
        String out = capture(() -> {
            try {
                exit[0] = PostTranApplication.execute(argsFor(daily, rejects, freshDb()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(exit[0]).isEqualTo(4);
        assertThat(Files.readAllLines(rejects)).hasSize(1);
        assertThat(out).contains("TRANSACTIONS REJECTED  :000000001");
    }

    @Test
    @DisplayName("AC-X1: database unavailable at start -> exit code 8, no output produced")
    void databaseUnavailableExitsEight() throws Exception {
        Path daily = masters("daily.txt", List.of(TestData.dailyLine("0000000000000001", amt("10.00"))));
        Path rejects = dir.resolve("rejects.txt");
        int[] exit = new int[1];
        String out = capture(() -> {
            try {
                exit[0] = PostTranApplication.execute(argsFor(daily, rejects,
                        "--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:1/unreachable"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(exit[0]).isEqualTo(8);
        assertThat(rejects).doesNotExist();
        assertThat(out).contains("ABENDING PROGRAM");
    }

    @Test
    @DisplayName("Missing required parameter -> exit code 8")
    void missingParameterExitsEight() {
        int exit = PostTranApplication.execute(freshDb(), "--rejectsFile=" + dir.resolve("r.txt"));
        assertThat(exit).isEqualTo(8);
    }
}
