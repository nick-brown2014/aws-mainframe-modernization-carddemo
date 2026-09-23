package com.carddemo.posttran.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.carddemo.posttran.TestRecords;
import com.carddemo.posttran.domain.Account;
import com.carddemo.posttran.domain.CardXref;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.ValidationResult;
import com.carddemo.posttran.repository.AccountRepository;
import com.carddemo.posttran.repository.CardXrefRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** One test per WS-VALIDATION-FAIL-REASON in 1500-VALIDATE-TRAN. */
@ExtendWith(MockitoExtension.class)
class TransactionValidatorTest {

    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    @Mock
    private CardXrefRepository cardXrefRepository;
    @Mock
    private AccountRepository accountRepository;

    private TransactionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TransactionValidator(cardXrefRepository, accountRepository);
    }

    @Test
    void reason100WhenCardNotInXref() {
        when(cardXrefRepository.findById(TestRecords.CARD)).thenReturn(Optional.empty());

        TransactionValidator.Validation v = validator.validate(tran("50.00"));

        assertThat(v.result()).isEqualTo(ValidationResult.INVALID_CARD_NUMBER);
        assertThat(v.result().reasonCode()).isEqualTo(100);
        assertThat(v.result().description()).isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(v.xref()).isNull();
    }

    @Test
    void reason101WhenAccountMissing() {
        when(cardXrefRepository.findById(TestRecords.CARD)).thenReturn(Optional.of(xref()));
        when(accountRepository.findById(TestRecords.ACCT_ID)).thenReturn(Optional.empty());

        TransactionValidator.Validation v = validator.validate(tran("50.00"));

        assertThat(v.result().reasonCode()).isEqualTo(101);
        assertThat(v.result().description()).isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(v.account()).isNull();
    }

    @Test
    void reason102WhenCycleCreditMinusDebitPlusAmountExceedsLimit() {
        // 900 - 100 + 250 = 1050 > 1000
        stubAccount(TestRecords.account(TestRecords.ACCT_ID, "1000.00", "900.00", "100.00", "2030-01-01"));

        TransactionValidator.Validation v = validator.validate(tran("250.00"));

        assertThat(v.result().reasonCode()).isEqualTo(102);
        assertThat(v.result().description()).isEqualTo("OVERLIMIT TRANSACTION");
    }

    @Test
    void amountExactlyAtLimitIsAccepted() {
        // 900 - 100 + 200 = 1000 >= limit 1000 -> CONTINUE
        stubAccount(TestRecords.account(TestRecords.ACCT_ID, "1000.00", "900.00", "100.00", "2030-01-01"));

        assertThat(validator.validate(tran("200.00")).result()).isEqualTo(ValidationResult.VALID);
    }

    @Test
    void reason103WhenAccountExpiredBeforeOriginationDate() {
        stubAccount(TestRecords.account(TestRecords.ACCT_ID, "1000.00", "0.00", "0.00", "2022-06-09"));

        TransactionValidator.Validation v = validator.validate(tran("10.00"));

        assertThat(v.result().reasonCode()).isEqualTo(103);
        assertThat(v.result().description()).isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    void expirationOnOriginationDateIsAccepted() {
        stubAccount(TestRecords.account(TestRecords.ACCT_ID, "1000.00", "0.00", "0.00", "2022-06-10"));

        assertThat(validator.validate(tran("10.00")).result()).isEqualTo(ValidationResult.VALID);
    }

    @Test
    void expirationCheckOverridesOverlimitWhenBothFail() {
        stubAccount(TestRecords.account(TestRecords.ACCT_ID, "10.00", "0.00", "0.00", "2020-01-01"));

        assertThat(validator.validate(tran("500.00")).result().reasonCode()).isEqualTo(103);
    }

    @Test
    void validTransactionCarriesLookedUpRecords() {
        Account account = TestRecords.account(TestRecords.ACCT_ID, "1000.00", "0.00", "0.00", "2030-01-01");
        stubAccount(account);

        TransactionValidator.Validation v = validator.validate(tran("10.00"));

        assertThat(v.isValid()).isTrue();
        assertThat(v.xref().getAcctId()).isEqualTo(TestRecords.ACCT_ID);
        assertThat(v.account()).isSameAs(account);
    }

    private void stubAccount(Account account) {
        when(cardXrefRepository.findById(TestRecords.CARD)).thenReturn(Optional.of(xref()));
        when(accountRepository.findById(TestRecords.ACCT_ID)).thenReturn(Optional.of(account));
    }

    private static CardXref xref() {
        return new CardXref(TestRecords.CARD, 1L, TestRecords.ACCT_ID);
    }

    private static DailyTransaction tran(String amount) {
        return TestRecords.dalytran("T1", new BigDecimal(amount), ORIG_TS);
    }
}
