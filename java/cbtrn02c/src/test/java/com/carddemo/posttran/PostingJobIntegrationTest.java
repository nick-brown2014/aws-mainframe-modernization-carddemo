package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "posttran.autorun=false",
        "spring.datasource.url=jdbc:h2:mem:posttran-test;DB_CLOSE_DELAY=-1"
})
class PostingJobIntegrationTest {
    private static final Path DATA = Path.of("../../app/data/ASCII");

    @Autowired
    private JobLauncher launcher;
    @Autowired
    private Job posttranJob;
    @Autowired
    private PostingRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @TempDir
    Path temp;

    @Test
    void postsExistingAndNewCategoriesAndProducesExactRejectRecords() throws Exception {
        Account original = Account.fromRecord(Files.readAllLines(DATA.resolve("acctdata.txt")).get(0));
        CardXref card = seedCardFor(original);
        TranCatBalance seed = TranCatBalance.fromRecord(Files.readAllLines(DATA.resolve("tcatbal.txt")).get(0));
        repository.saveBalance(seed, false);

        List<String> source = Files.readAllLines(DATA.resolve("dailytran.txt")).subList(0, 6);
        String first = makeDaily(source.get(0), card.cardNum(), "0001", "0000000010{", "2022-06-10");
        String second = makeDaily(source.get(1), card.cardNum(), "0001", "0000000005}", "2022-06-10");
        String third = makeDaily(source.get(2), card.cardNum(), "9999", "0000000020{", "2022-06-10");
        String unknown = makeDaily(source.get(3), "9999999999999999", "0001", "0000000010{", "2022-06-10");
        String overlimit = makeDaily(source.get(4), card.cardNum(), "0001", "0000999999{", "2022-06-10");
        String expired = makeDaily(source.get(5), card.cardNum(), "0001", "0000000010{", "2029-01-01");
        var execution = launch(List.of(first, second, third, unknown, overlimit, expired), "mixed");

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals("4", execution.getExitStatus().getExitCode());
        assertEquals(6, execution.getStepExecutions().iterator().next().getReadCount());
        assertEquals(3L, execution.getStepExecutions().iterator().next()
                .getExecutionContext().getLong("rejectCount"));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM transaction_master", Long.class));
        assertEquals(new BigDecimal("0.50"),
                repository.balance(original.id(), "01", "0001").orElseThrow().balance());
        assertEquals(new BigDecimal("2.00"),
                repository.balance(original.id(), "01", "9999").orElseThrow().balance());
        Account updated = repository.account(original.id()).orElseThrow();
        assertEquals(original.currBal().add(new BigDecimal("2.50")), updated.currBal());
        assertEquals(original.currCycCredit().add(new BigDecimal("3.00")), updated.currCycCredit());
        assertEquals(original.currCycDebit().subtract(new BigDecimal("0.50")), updated.currCycDebit());
        String procTs = jdbc.queryForObject("SELECT proc_ts FROM transaction_master WHERE tran_id = ?",
                String.class, first.substring(0, 16));
        assertTrue(procTs.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{7}"));
        assertEquals(27, procTs.length());
        List<String> rejects = Files.readAllLines(temp.resolve("mixed.rejects"), StandardCharsets.US_ASCII);
        assertEquals(3, rejects.size());
        assertEquals(430, rejects.get(0).length());
        assertEquals(unknown + "0100" + String.format("%-76s", "INVALID CARD NUMBER FOUND"), rejects.get(0));
        assertEquals(overlimit + "0102" + String.format("%-76s", "OVERLIMIT TRANSACTION"), rejects.get(1));
        assertEquals(expired + "0103" + String.format("%-76s", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),
                rejects.get(2));
    }

    @Test
    void failureRollsBackEntireChunk() throws Exception {
        Account account = Account.fromRecord(Files.readAllLines(DATA.resolve("acctdata.txt")).get(1));
        CardXref card = seedCardFor(account);
        String source = Files.readAllLines(DATA.resolve("dailytran.txt")).get(0);
        String record = makeDaily(source, card.cardNum(), "8888", "0000000010{", "2022-06-10");
        var execution = launch(List.of(record, record), "duplicate");
        assertEquals(BatchStatus.FAILED, execution.getStatus());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transaction_master WHERE tran_id = ?", Long.class, record.substring(0, 16)));
        assertEquals(new BigDecimal("1.00"),
                repository.balance(account.id(), "01", "8888").orElseThrow().balance());
        assertEquals(account.currBal().add(new BigDecimal("1.00")),
                repository.account(account.id()).orElseThrow().currBal());
    }

    private CardXref seedCardFor(Account account) throws Exception {
        repository.insertAccount(account);
        CardXref card = Files.readAllLines(DATA.resolve("cardxref.txt")).stream()
                .map(raw -> CardXref.fromRecord(raw + " ".repeat(14)))
                .filter(xref -> xref.acctId().equals(account.id())).findFirst().orElseThrow();
        repository.insertCard(card);
        return card;
    }

    private org.springframework.batch.core.JobExecution launch(List<String> records, String name) throws Exception {
        Path input = temp.resolve(name + ".input");
        Files.write(input, records, StandardCharsets.US_ASCII);
        return launcher.run(posttranJob, new JobParametersBuilder()
                .addString("input", input.toString())
                .addString("rejects", temp.resolve(name + ".rejects").toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters());
    }

    private static String makeDaily(String raw, String card, String category, String amount, String date) {
        raw = replace(raw, 18, category);
        raw = replace(raw, 132, amount);
        raw = replace(raw, 262, card);
        return replace(raw, 278, date);
    }

    private static String replace(String raw, int offset, String value) {
        return raw.substring(0, offset) + value + raw.substring(offset + value.length());
    }
}
