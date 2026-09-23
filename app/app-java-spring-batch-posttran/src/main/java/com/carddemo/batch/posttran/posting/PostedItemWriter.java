package com.carddemo.batch.posttran.posting;

import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.PostingResult;
import com.carddemo.batch.posttran.model.PostingResult.Posted;
import com.carddemo.batch.posttran.model.TranCategoryBalance;
import com.carddemo.batch.posttran.repo.AccountRepository;
import com.carddemo.batch.posttran.repo.TranCategoryBalanceRepository;
import com.carddemo.batch.posttran.repo.TransactionRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Persists a {@link Posted} result in COBOL order: tran_cat_bal, account, transaction. Also enforces
 * the copybook digit limits that COBOL would silently truncate (spec R1).
 */
@Component
public class PostedItemWriter implements ItemWriter<PostingResult> {

    private static final BigDecimal LIMIT_9 = new BigDecimal("1000000000.00");
    private static final BigDecimal LIMIT_10 = new BigDecimal("10000000000.00");

    private final TranCategoryBalanceRepository balanceRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public PostedItemWriter(TranCategoryBalanceRepository balanceRepository, AccountRepository accountRepository,
                            TransactionRepository transactionRepository) {
        this.balanceRepository = balanceRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public void write(Chunk<? extends PostingResult> chunk) {
        for (PostingResult result : chunk) {
            write((Posted) result);
        }
    }

    private void write(Posted p) {
        TranCategoryBalance b = p.balance();
        checkMagnitude(b.tranCatBal(), LIMIT_9, "TRAN-CAT-BAL");
        if (p.balanceIsNew()) {
            balanceRepository.insert(b);
        } else {
            expectOne(balanceRepository.updateBalance(b.acctId(), b.tranTypeCd(), b.tranCatCd(), b.tranCatBal()),
                    "REWRITING TRANSACTION CATEGORY BALANCE " + b.key().asFixedWidth());
        }

        Account a = p.account();
        checkMagnitude(a.currBal(), LIMIT_10, "ACCT-CURR-BAL");
        checkMagnitude(a.currCycCredit(), LIMIT_10, "ACCT-CURR-CYC-CREDIT");
        checkMagnitude(a.currCycDebit(), LIMIT_10, "ACCT-CURR-CYC-DEBIT");
        expectOne(accountRepository.updateBalances(a.acctId(), a.currBal(), a.currCycCredit(), a.currCycDebit()),
                "REWRITING ACCOUNT RECORD " + a.acctId());

        checkMagnitude(p.transaction().tranAmt(), LIMIT_9, "TRAN-AMT");
        transactionRepository.insert(p.transaction());
    }

    private static void expectOne(int rows, String what) {
        if (rows != 1) {
            throw new IncorrectResultSizeDataAccessException("ERROR " + what, 1, rows);
        }
    }

    private static void checkMagnitude(BigDecimal value, BigDecimal limit, String field) {
        if (value.abs().compareTo(limit) >= 0) {
            throw new ArithmeticException(field + " overflow: " + value);
        }
    }
}
