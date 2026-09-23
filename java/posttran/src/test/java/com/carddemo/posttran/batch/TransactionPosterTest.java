package com.carddemo.posttran.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.posttran.TestRecords;
import com.carddemo.posttran.cobol.Db2Timestamp;
import com.carddemo.posttran.domain.Account;
import com.carddemo.posttran.domain.CardXref;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.PostedTransaction;
import com.carddemo.posttran.domain.TranCatBalance;
import com.carddemo.posttran.domain.TranCatBalanceId;
import com.carddemo.posttran.repository.TranCatBalanceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Posting math of 2000-POST-TRANSACTION / 2700-UPDATE-TCATBAL / 2800-UPDATE-ACCOUNT-REC. */
@ExtendWith(MockitoExtension.class)
class TransactionPosterTest {

    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";
    private static final TranCatBalanceId KEY = new TranCatBalanceId(TestRecords.ACCT_ID, "01", 1);

    @Mock
    private TranCatBalanceRepository tranCatBalanceRepository;

    private TransactionPoster poster;
    private CardXref xref;
    private Account account;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2025-01-02T03:04:05.67Z"), ZoneOffset.UTC);
        poster = new TransactionPoster(tranCatBalanceRepository, new Db2Timestamp(clock));
        xref = new CardXref(TestRecords.CARD, 1L, TestRecords.ACCT_ID);
        account = TestRecords.account(TestRecords.ACCT_ID, "1000.00", "300.00", "-50.00", "2030-01-01");
    }

    @Test
    void positiveAmountAddsToCurrBalAndCycleCredit() {
        when(tranCatBalanceRepository.findById(KEY)).thenReturn(Optional.of(new TranCatBalance(KEY, bd("10.00"))));

        poster.post(tran("25.50"), xref, account);

        assertThat(account.getCurrBal()).isEqualByComparingTo("125.50");
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo("325.50");
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo("-50.00");
    }

    @Test
    void zeroAmountCountsAsCredit() {
        when(tranCatBalanceRepository.findById(KEY)).thenReturn(Optional.of(new TranCatBalance(KEY, bd("10.00"))));

        poster.post(tran("0.00"), xref, account);

        assertThat(account.getCurrBal()).isEqualByComparingTo("100.00");
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo("300.00");
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo("-50.00");
    }

    @Test
    void negativeAmountAddsToCurrBalAndCycleDebit() {
        when(tranCatBalanceRepository.findById(KEY)).thenReturn(Optional.of(new TranCatBalance(KEY, bd("10.00"))));

        poster.post(tran("-40.00"), xref, account);

        assertThat(account.getCurrBal()).isEqualByComparingTo("60.00");
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo("300.00");
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo("-90.00");
    }

    @Test
    void existingCategoryBalanceIsIncremented() {
        TranCatBalance existing = new TranCatBalance(KEY, bd("10.00"));
        when(tranCatBalanceRepository.findById(KEY)).thenReturn(Optional.of(existing));

        PostedTransaction posted = poster.post(tran("-40.00"), xref, account);

        assertThat(posted.categoryBalanceCreated()).isFalse();
        assertThat(posted.categoryBalance()).isSameAs(existing);
        assertThat(existing.getBalance()).isEqualByComparingTo("-30.00");
        verify(tranCatBalanceRepository, never()).save(any());
    }

    @Test
    void missingCategoryBalanceIsCreatedWithAmount() {
        when(tranCatBalanceRepository.findById(KEY)).thenReturn(Optional.empty());
        when(tranCatBalanceRepository.save(any(TranCatBalance.class))).thenAnswer(inv -> inv.getArgument(0));

        PostedTransaction posted = poster.post(tran("12.34"), xref, account);

        assertThat(posted.categoryBalanceCreated()).isTrue();
        assertThat(posted.categoryBalance().getId()).isEqualTo(KEY);
        assertThat(posted.categoryBalance().getBalance()).isEqualByComparingTo("12.34");
        verify(tranCatBalanceRepository).save(posted.categoryBalance());
    }

    @Test
    void transactionRecordMirrorsDailyRecordWithDb2ProcessingTimestamp() {
        when(tranCatBalanceRepository.findById(KEY)).thenReturn(Optional.of(new TranCatBalance(KEY, bd("0.00"))));
        DailyTransaction daily = tran("25.50");

        PostedTransaction posted = poster.post(daily, xref, account);

        assertThat(posted.transaction().getTranId()).isEqualTo(daily.getTranId());
        assertThat(posted.transaction().getTypeCd()).isEqualTo("01");
        assertThat(posted.transaction().getCatCd()).isEqualTo(1);
        assertThat(posted.transaction().getSource()).isEqualTo(daily.getSource());
        assertThat(posted.transaction().getDescription()).isEqualTo(daily.getDescription());
        assertThat(posted.transaction().getAmount()).isEqualByComparingTo("25.50");
        assertThat(posted.transaction().getMerchantId()).isEqualTo(800000000L);
        assertThat(posted.transaction().getMerchantName()).isEqualTo(daily.getMerchantName());
        assertThat(posted.transaction().getMerchantCity()).isEqualTo(daily.getMerchantCity());
        assertThat(posted.transaction().getMerchantZip()).isEqualTo(daily.getMerchantZip());
        assertThat(posted.transaction().getCardNum()).isEqualTo(TestRecords.CARD);
        assertThat(posted.transaction().getOrigTs()).isEqualTo(daily.getOrigTs());
        assertThat(posted.transaction().getProcTs()).isEqualTo("2025-01-02-03.04.05.670000");
    }

    private static DailyTransaction tran(String amount) {
        return TestRecords.dalytran("T1", new BigDecimal(amount), ORIG_TS);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
