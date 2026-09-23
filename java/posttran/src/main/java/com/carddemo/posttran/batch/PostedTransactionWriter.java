package com.carddemo.posttran.batch;

import com.carddemo.posttran.domain.PostedTransaction;
import com.carddemo.posttran.domain.PostingOutcome;
import com.carddemo.posttran.domain.Transaction;
import com.carddemo.posttran.repository.AccountRepository;
import com.carddemo.posttran.repository.TranCatBalanceRepository;
import com.carddemo.posttran.repository.TransactionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Persists a chunk of posted transactions: the updated accounts ({@code REWRITE FD-ACCTFILE-REC}),
 * the created/updated category balances ({@code WRITE}/{@code REWRITE FD-TRAN-CAT-BAL-RECORD}) and
 * the new transaction master records ({@code 2900-WRITE-TRANSACTION-FILE}).
 *
 * <p>A duplicate transaction id would make the VSAM WRITE fail with status 22 and abend the COBOL
 * job; here it raises {@link DuplicateTransactionException}, which fails the step.
 */
@Component
public class PostedTransactionWriter implements ItemWriter<PostingOutcome> {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TranCatBalanceRepository tranCatBalanceRepository;

    public PostedTransactionWriter(TransactionRepository transactionRepository,
                                   AccountRepository accountRepository,
                                   TranCatBalanceRepository tranCatBalanceRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.tranCatBalanceRepository = tranCatBalanceRepository;
    }

    @Override
    public void write(Chunk<? extends PostingOutcome> chunk) {
        List<Transaction> transactions = new ArrayList<>();
        Set<String> idsInChunk = new HashSet<>();
        for (PostingOutcome outcome : chunk) {
            if (!(outcome instanceof PostedTransaction posted)) {
                continue;
            }
            accountRepository.save(posted.account());
            tranCatBalanceRepository.save(posted.categoryBalance());
            Transaction tran = posted.transaction();
            if (!idsInChunk.add(tran.getTranId()) || transactionRepository.existsById(tran.getTranId())) {
                throw new DuplicateTransactionException(tran.getTranId());
            }
            transactions.add(tran);
        }
        transactionRepository.saveAll(transactions);
        transactionRepository.flush();
    }

    public static class DuplicateTransactionException extends RuntimeException {
        public DuplicateTransactionException(String tranId) {
            super("ERROR WRITING TO TRANSACTION FILE: duplicate TRAN-ID '" + tranId + "'");
        }
    }
}
