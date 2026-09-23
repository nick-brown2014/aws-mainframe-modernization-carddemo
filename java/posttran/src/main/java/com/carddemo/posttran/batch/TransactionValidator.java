package com.carddemo.posttran.batch;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.Account;
import com.carddemo.posttran.domain.CardXref;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.ValidationResult;
import com.carddemo.posttran.repository.AccountRepository;
import com.carddemo.posttran.repository.CardXrefRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code 1500-VALIDATE-TRAN}: card cross-reference lookup ({@code 1500-A-LOOKUP-XREF}) followed by
 * the account lookup and balance/expiration checks ({@code 1500-B-LOOKUP-ACCT}).
 */
@Component
public class TransactionValidator {

    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;

    public TransactionValidator(CardXrefRepository cardXrefRepository, AccountRepository accountRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
    }

    /** Result of a successful or failed validation, carrying the looked-up records when found. */
    public record Validation(ValidationResult result, CardXref xref, Account account) {
        public boolean isValid() {
            return result.isValid();
        }
    }

    public Validation validate(DailyTransaction tran) {
        Optional<CardXref> xref = cardXrefRepository.findById(tran.getCardNum());
        if (xref.isEmpty()) {
            return new Validation(ValidationResult.INVALID_CARD_NUMBER, null, null);
        }

        Optional<Account> account = accountRepository.findById(xref.get().getAcctId());
        if (account.isEmpty()) {
            return new Validation(ValidationResult.ACCOUNT_NOT_FOUND, xref.get(), null);
        }

        return new Validation(checkAccount(account.get(), tran), xref.get(), account.get());
    }

    /**
     * The two checks in {@code 1500-B-LOOKUP-ACCT} run back to back with no early exit, so when both
     * fail the expiration reason (103) overwrites the overlimit reason (102).
     */
    static ValidationResult checkAccount(Account account, DailyTransaction tran) {
        ValidationResult result = ValidationResult.VALID;

        BigDecimal tempBal = account.getCurrCycCredit()
                .subtract(account.getCurrCycDebit())
                .add(tran.getAmount());
        if (account.getCreditLimit().compareTo(tempBal) < 0) {
            result = ValidationResult.OVERLIMIT;
        }

        if (compareAlnum(account.getExpirationDate(), tran.getOrigDate()) < 0) {
            result = ValidationResult.AFTER_EXPIRATION;
        }
        return result;
    }

    /** COBOL alphanumeric comparison: the shorter operand is padded with spaces. */
    static int compareAlnum(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        int len = Math.max(left.length(), right.length());
        return CobolFields.alnum(left, len).compareTo(CobolFields.alnum(right, len));
    }
}
