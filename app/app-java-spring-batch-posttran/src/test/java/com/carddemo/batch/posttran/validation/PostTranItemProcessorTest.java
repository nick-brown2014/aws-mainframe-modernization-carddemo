package com.carddemo.batch.posttran.validation;

import com.carddemo.batch.posttran.ProgramConsole;
import com.carddemo.batch.posttran.io.Db2TimestampFormatter;
import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.DailyTransaction;
import com.carddemo.batch.posttran.model.PostingResult;
import com.carddemo.batch.posttran.model.PostingResult.Posted;
import com.carddemo.batch.posttran.model.PostingResult.Rejected;
import com.carddemo.batch.posttran.posting.ChunkStateCache;
import com.carddemo.batch.posttran.posting.PostTranItemProcessor;
import com.carddemo.batch.posttran.posting.PostingService;
import com.carddemo.batch.posttran.repo.AccountRepository;
import com.carddemo.batch.posttran.repo.CardXrefRepository;
import com.carddemo.batch.posttran.repo.TranCategoryBalanceRepository;
import com.carddemo.batch.posttran.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.carddemo.batch.posttran.support.TestData.ACCT_ID;
import static com.carddemo.batch.posttran.support.TestData.CARD;
import static com.carddemo.batch.posttran.support.TestData.amt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AC-V100 .. AC-V-ORDER (spec 5.2 / 8.2): validation rules, precedence and short-circuit semantics. */
class PostTranItemProcessorTest {

    private CardXrefRepository xrefRepo;
    private AccountRepository acctRepo;
    private TranCategoryBalanceRepository balRepo;
    private PostTranItemProcessor processor;

    @BeforeEach
    void setUp() {
        xrefRepo = mock(CardXrefRepository.class);
        acctRepo = mock(AccountRepository.class);
        balRepo = mock(TranCategoryBalanceRepository.class);
        ChunkStateCache cache = new ChunkStateCache();
        Db2TimestampFormatter ts = new Db2TimestampFormatter(
                Clock.fixed(Instant.parse("2026-09-23T18:15:42.376Z"), ZoneOffset.UTC));
        PostingService posting = new PostingService(balRepo, cache, ts,
                new ProgramConsole(new PrintStream(new ByteArrayOutputStream())));
        processor = new PostTranItemProcessor(xrefRepo, acctRepo, cache, posting);
    }

    private void given(CardXref xref, Account acct) {
        when(xrefRepo.findByCardNum(CARD)).thenReturn(Optional.ofNullable(xref));
        when(acctRepo.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.ofNullable(acct));
        when(balRepo.findByKey(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
    }

    private static DailyTransaction tran(String amount, String origTs) {
        return TestData.daily(TestData.dailyLine("0000000000000001", "01", 1, amt(amount), CARD, origTs));
    }

    @Test
    @DisplayName("AC-V100: card absent from card_xref -> 100, account never read")
    void invalidCard() {
        given(null, null);
        PostingResult r = processor.process(tran("10.00", TestData.ORIG_TS));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected) r).failure()).isEqualTo(ValidationFailure.INVALID_CARD);
        assertThat(((Rejected) r).failure().description()).isEqualTo("INVALID CARD NUMBER FOUND");
        verifyNoInteractions(acctRepo, balRepo);
    }

    @Test
    @DisplayName("AC-V101: xref present, account missing -> 101, no writes")
    void accountNotFound() {
        given(TestData.xref(CARD, ACCT_ID), null);
        PostingResult r = processor.process(tran("10.00", TestData.ORIG_TS));
        assertThat(((Rejected) r).failure()).isEqualTo(ValidationFailure.ACCOUNT_NOT_FOUND);
        assertThat(((Rejected) r).failure().description()).isEqualTo("ACCOUNT RECORD NOT FOUND");
        verify(acctRepo).findByIdForUpdate(ACCT_ID);
        verifyNoInteractions(balRepo);
    }

    @Test
    @DisplayName("AC-V102a: creditLimit 1000, cycCredit 900, cycDebit 0, amt 100 -> posted (limit >= temp)")
    void atLimitPosts() {
        given(TestData.xref(CARD, ACCT_ID), TestData.account("1000.00", "900.00", "0.00"));
        assertThat(processor.process(tran("100.00", TestData.ORIG_TS))).isInstanceOf(Posted.class);
    }

    @Test
    @DisplayName("AC-V102b: one cent over the limit -> 102")
    void oneCentOverRejects() {
        given(TestData.xref(CARD, ACCT_ID), TestData.account("1000.00", "900.00", "0.00"));
        PostingResult r = processor.process(tran("100.01", TestData.ORIG_TS));
        assertThat(((Rejected) r).failure()).isEqualTo(ValidationFailure.OVERLIMIT);
        assertThat(((Rejected) r).failure().description()).isEqualTo("OVERLIMIT TRANSACTION");
    }

    @Test
    @DisplayName("AC-V102c: negative cycDebit is subtracted: 900 - (-200) + 250 = 1350 > 1000 -> 102")
    void debitSignHandling() {
        given(TestData.xref(CARD, ACCT_ID), TestData.account("1000.00", "900.00", "-200.00"));
        PostingResult r = processor.process(tran("250.00", TestData.ORIG_TS));
        assertThat(((Rejected) r).failure().code()).isEqualTo(102);
    }

    @Test
    @DisplayName("AC-V103a: origTs date equal to expiration date passes")
    void expirationSameDayPosts() {
        given(TestData.xref(CARD, ACCT_ID),
                TestData.account(ACCT_ID, "100.00", "1000.00", "2025-12-31", "0.00", "0.00"));
        assertThat(processor.process(tran("10.00", "2025-12-31 23:59:59.999999"))).isInstanceOf(Posted.class);
    }

    @Test
    @DisplayName("AC-V103b: origTs date after expiration -> 103")
    void expiredRejects() {
        given(TestData.xref(CARD, ACCT_ID),
                TestData.account(ACCT_ID, "100.00", "1000.00", "2025-12-31", "0.00", "0.00"));
        PostingResult r = processor.process(tran("10.00", "2026-01-01 00:00:00.000000"));
        assertThat(((Rejected) r).failure()).isEqualTo(ValidationFailure.EXPIRED);
        assertThat(((Rejected) r).failure().description()).isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    @DisplayName("AC-V-PREC: fails both 102 and 103 -> exactly one reject with 103 (last rule wins)")
    void lastRuleWins() {
        given(TestData.xref(CARD, ACCT_ID),
                TestData.account(ACCT_ID, "100.00", "1000.00", "2025-12-31", "900.00", "0.00"));
        PostingResult r = processor.process(tran("500.00", "2026-01-01 00:00:00.000000"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected) r).failure().code()).isEqualTo(103);
        verifyNoInteractions(balRepo);
    }

    @Test
    @DisplayName("AC-V-ORDER: rule 100 short-circuits; account repository never consulted")
    void rule100ShortCircuits() {
        given(null, TestData.account(ACCT_ID, "100.00", "1000.00", "2025-12-31", "900.00", "0.00"));
        PostingResult r = processor.process(tran("500.00", "2026-01-01 00:00:00.000000"));
        assertThat(((Rejected) r).failure().code()).isEqualTo(100);
        verifyNoInteractions(acctRepo);
    }
}
