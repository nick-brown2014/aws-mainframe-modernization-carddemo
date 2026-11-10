package com.carddemo.batch.posttran.posting;

import com.carddemo.batch.posttran.ProgramConsole;
import com.carddemo.batch.posttran.io.Db2TimestampFormatter;
import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.DailyTransaction;
import com.carddemo.batch.posttran.model.PostingResult.Posted;
import com.carddemo.batch.posttran.model.TranCategoryBalance;
import com.carddemo.batch.posttran.model.Transaction;
import com.carddemo.batch.posttran.repo.TranCategoryBalanceRepository;
import com.carddemo.batch.posttran.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.carddemo.batch.posttran.support.TestData.amt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AC-P1 .. AC-P7 posting arithmetic (spec 5.3); DB-level effects are covered in PostTranJobIT. */
class PostingServiceTest {

    private static final String PROC_TS = "2026-09-23-18.15.42.370000";

    private TranCategoryBalanceRepository balRepo;
    private ChunkStateCache cache;
    private ByteArrayOutputStream console;
    private PostingService service;
    private final CardXref xref = TestData.xref(TestData.CARD, TestData.ACCT_ID);
    private final Account account = TestData.account(TestData.ACCT_ID, "100.00", "5000.00", "2030-12-31", "10.00", "-5.00");

    @BeforeEach
    void setUp() {
        balRepo = mock(TranCategoryBalanceRepository.class);
        when(balRepo.findByKey(anyLong(), anyString(), anyInt())).thenReturn(Optional.empty());
        cache = new ChunkStateCache();
        console = new ByteArrayOutputStream();
        service = new PostingService(balRepo, cache,
                new Db2TimestampFormatter(Clock.fixed(Instant.parse("2026-09-23T18:15:42.376Z"), ZoneOffset.UTC)),
                new ProgramConsole(new PrintStream(console, true, StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("AC-P1: +50.00 -> currBal 150.00, cycCredit 60.00, cycDebit unchanged")
    void creditPosting() {
        Posted p = service.post(TestData.daily("0000000000000001", amt("50.00")), xref, account);
        assertThat(p.account().currBal()).isEqualTo(amt("150.00"));
        assertThat(p.account().currCycCredit()).isEqualTo(amt("60.00"));
        assertThat(p.account().currCycDebit()).isEqualTo(amt("-5.00"));
    }

    @Test
    @DisplayName("AC-P2: -30.00 -> currBal 70.00, cycCredit unchanged, cycDebit -35.00")
    void debitPosting() {
        Posted p = service.post(TestData.daily("0000000000000001", amt("-30.00")), xref, account);
        assertThat(p.account().currBal()).isEqualTo(amt("70.00"));
        assertThat(p.account().currCycCredit()).isEqualTo(amt("10.00"));
        assertThat(p.account().currCycDebit()).isEqualTo(amt("-35.00"));
    }

    @Test
    @DisplayName("AC-P3: 0.00 goes to the credit bucket with no change; row still rewritten")
    void zeroAmount() {
        Posted p = service.post(TestData.daily("0000000000000001", amt("0.00")), xref, account);
        assertThat(p.account().currBal()).isEqualTo(amt("100.00"));
        assertThat(p.account().currCycCredit()).isEqualTo(amt("10.00"));
        assertThat(p.account().currCycDebit()).isEqualTo(amt("-5.00"));
        assertThat(p.account()).isEqualTo(account);
        assertThat(p.balance().tranCatBal()).isEqualTo(amt("0.00"));
    }

    @Test
    @DisplayName("AC-P4: missing tran_cat_bal -> new row = amt and COBOL-style console line")
    void createsCategoryBalance() {
        Posted p = service.post(TestData.daily("0000000000000001", amt("50.00")), xref, account);
        assertThat(p.balanceIsNew()).isTrue();
        assertThat(p.balance()).isEqualTo(new TranCategoryBalance(1L, "01", 1, amt("50.00")));
        assertThat(console.toString(StandardCharsets.UTF_8))
                .isEqualTo("TCATBAL record not found for key : 00000000001010001.. Creating.\n");
    }

    @Test
    @DisplayName("AC-P5: existing tran_cat_bal 100.00 + (-40.00) -> 60.00 update, not insert")
    void updatesCategoryBalance() {
        when(balRepo.findByKey(1L, "01", 1)).thenReturn(Optional.of(new TranCategoryBalance(1L, "01", 1, amt("100.00"))));
        Posted p = service.post(TestData.daily("0000000000000001", amt("-40.00")), xref, account);
        assertThat(p.balanceIsNew()).isFalse();
        assertThat(p.balance().tranCatBal()).isEqualTo(amt("60.00"));
        assertThat(console.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("AC-P6: category key uses xref.acctId, not the account record or daily record")
    void categoryKeyUsesXrefAccountId() {
        CardXref other = new CardXref(TestData.CARD, 900L, 77L);
        Posted p = service.post(TestData.daily("0000000000000001", amt("5.00")), other, account);
        assertThat(p.balance().acctId()).isEqualTo(77L);
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("key : 00000000077010001..");
    }

    @Test
    @DisplayName("AC-P7: transaction row copies the 12 business fields and stamps proc_ts from the injected clock")
    void transactionRow() {
        DailyTransaction d = TestData.daily("0000000000000001", amt("50.00"));
        Transaction t = service.post(d, xref, account).transaction();
        assertThat(t).isEqualTo(new Transaction(d.tranId(), d.tranTypeCd(), d.tranCatCd(), d.tranSource(), d.tranDesc(),
                d.tranAmt(), d.merchantId(), d.merchantName(), d.merchantCity(), d.merchantZip(), d.cardNum(),
                d.origTs(), PROC_TS));
        assertThat(t.procTs()).hasSize(26);
    }

    @Test
    @DisplayName("AC-P8 (unit): second transaction in the same chunk sees the first one's balances")
    void chunkCacheCarriesState() {
        service.post(TestData.daily("0000000000000001", amt("50.00")), xref, account);
        Account cached = cache.account(TestData.ACCT_ID).orElseThrow();
        Posted second = service.post(TestData.daily("0000000000000002", amt("25.00")), xref, cached);
        assertThat(second.account().currBal()).isEqualTo(amt("175.00"));
        assertThat(second.account().currCycCredit()).isEqualTo(amt("85.00"));
        assertThat(second.balanceIsNew()).isFalse();
        assertThat(second.balance().tranCatBal()).isEqualTo(amt("75.00"));
    }
}
