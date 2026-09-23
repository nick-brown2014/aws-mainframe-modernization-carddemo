package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostingProcessorTest {
    private static final Path DATA = Path.of("../../app/data/ASCII");
    private PostingRepository repository;
    private PostingProcessor processor;
    private DailyTransaction daily;
    private CardXref card;
    private Account account;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(PostingRepository.class);
        processor = new PostingProcessor(repository);
        daily = DailyTransaction.fromRecord(Files.readAllLines(DATA.resolve("dailytran.txt")).get(0));
        account = Account.fromRecord(Files.readAllLines(DATA.resolve("acctdata.txt")).get(0));
        card = Files.readAllLines(DATA.resolve("cardxref.txt")).stream()
                .map(raw -> CardXref.fromRecord(raw + " ".repeat(14)))
                .filter(xref -> xref.acctId().equals(account.id())).findFirst().orElseThrow();
        daily = DailyTransaction.fromRecord(replace(daily.originalRecord(), 262, card.cardNum()));
        when(repository.card(card.cardNum())).thenReturn(Optional.of(card));
        when(repository.account(account.id())).thenReturn(Optional.of(account));
    }

    @Test
    void rejectsUnknownCard() {
        when(repository.card(card.cardNum())).thenReturn(Optional.empty());
        assertReject(100, "INVALID CARD NUMBER FOUND");
    }

    @Test
    void rejectsUnknownAccount() {
        when(repository.account(account.id())).thenReturn(Optional.empty());
        assertReject(101, "ACCOUNT RECORD NOT FOUND");
    }

    @Test
    void rejectsOverlimit() {
        daily = DailyTransaction.fromRecord(replace(daily.originalRecord(), 132, "0000999999{"));
        assertReject(102, "OVERLIMIT TRANSACTION");
    }

    @Test
    void expirationOverridesOverlimitAsInCobol() {
        String raw = replace(daily.originalRecord(), 132, "0000999999{");
        daily = DailyTransaction.fromRecord(replace(raw, 278, "2029-01-01"));
        assertReject(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    void acceptsValidPosting() {
        daily = DailyTransaction.fromRecord(replace(daily.originalRecord(), 132, "0000000010{"));
        PostingItem result = processor.process(daily);
        assertFalse(result.isRejected());
        assertEquals(account.id(), result.accountId());
        assertEquals(new BigDecimal("1.00"), daily.amount());
    }

    @Test
    void signedDisplayAndSeedRecordWidths() throws IOException {
        assertEquals(350, daily.originalRecord().length());
        assertEquals(new BigDecimal("-0.50"), FixedWidth.signedDecimal("0000000005}"));
        assertEquals(300, Files.readAllLines(DATA.resolve("acctdata.txt")).get(0).length());
        assertEquals(50, Files.readAllLines(DATA.resolve("tcatbal.txt")).get(0).length());
        assertEquals(50, card.cardNum().length() + card.custId().length()
                + card.acctId().length() + card.filler().length());
    }

    private void assertReject(int reason, String description) {
        PostingItem result = processor.process(daily);
        assertTrue(result.isRejected());
        assertEquals(reason, result.failReason());
        assertEquals(description, result.failDescription());
        assertEquals(430, result.rejectRecord().length());
        assertEquals(daily.originalRecord(), result.rejectRecord().substring(0, 350));
    }

    private static String replace(String raw, int offset, String value) {
        return raw.substring(0, offset) + value + raw.substring(offset + value.length());
    }
}
