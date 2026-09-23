package com.carddemo.batch.posttran.posting;

import com.carddemo.batch.posttran.ProgramConsole;
import com.carddemo.batch.posttran.io.Db2TimestampFormatter;
import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.DailyTransaction;
import com.carddemo.batch.posttran.model.PostingResult.Posted;
import com.carddemo.batch.posttran.model.TranCatKey;
import com.carddemo.batch.posttran.model.TranCategoryBalance;
import com.carddemo.batch.posttran.model.Transaction;
import com.carddemo.batch.posttran.repo.TranCategoryBalanceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 2000-POST-TRANSACTION posting math (spec section 5.3). Computes, but does not persist, the new
 * category balance, account and transaction master record; persisting is the writer's job so that
 * every write of a chunk shares one database transaction.
 */
@Service
public class PostingService {

    private final TranCategoryBalanceRepository balanceRepository;
    private final ChunkStateCache cache;
    private final Db2TimestampFormatter timestamps;
    private final ProgramConsole console;

    public PostingService(TranCategoryBalanceRepository balanceRepository, ChunkStateCache cache,
                          Db2TimestampFormatter timestamps, ProgramConsole console) {
        this.balanceRepository = balanceRepository;
        this.cache = cache;
        this.timestamps = timestamps;
        this.console = console;
    }

    public Posted post(DailyTransaction tran, CardXref xref, Account acct) {
        BigDecimal amt = tran.tranAmt();

        // 2700-UPDATE-TCATBAL: key uses XREF-ACCT-ID plus the daily record's type/category codes.
        TranCatKey key = new TranCatKey(xref.acctId(), tran.tranTypeCd(), tran.tranCatCd());
        Optional<TranCategoryBalance> existing = cache.balance(key)
                .or(() -> balanceRepository.findByKey(key.acctId(), key.tranTypeCd(), key.tranCatCd()));
        boolean isNew = existing.isEmpty();
        TranCategoryBalance balance;
        if (isNew) {
            console.display("TCATBAL record not found for key : " + key.asFixedWidth() + ".. Creating.");
            balance = new TranCategoryBalance(key.acctId(), key.tranTypeCd(), key.tranCatCd(), BigDecimal.ZERO.setScale(2).add(amt));
        } else {
            balance = existing.get().withBalance(existing.get().tranCatBal().add(amt));
        }
        cache.put(balance);

        // 2800-UPDATE-ACCOUNT-REC: amt >= 0 goes to the credit bucket, else the (negative running) debit bucket.
        BigDecimal currBal = acct.currBal().add(amt);
        BigDecimal cycCredit = acct.currCycCredit();
        BigDecimal cycDebit = acct.currCycDebit();
        if (amt.signum() >= 0) {
            cycCredit = cycCredit.add(amt);
        } else {
            cycDebit = cycDebit.add(amt);
        }
        Account updated = acct.withBalances(currBal, cycCredit, cycDebit);
        cache.put(updated);

        // 2900-WRITE-TRANSACTION-FILE
        Transaction transaction = Transaction.from(tran, timestamps.now());

        return new Posted(tran, transaction, updated, balance, isNew);
    }
}
