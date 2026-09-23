package com.carddemo.posttran.batch;

import com.carddemo.posttran.cobol.Db2Timestamp;
import com.carddemo.posttran.domain.Account;
import com.carddemo.posttran.domain.CardXref;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.PostedTransaction;
import com.carddemo.posttran.domain.TranCatBalance;
import com.carddemo.posttran.domain.TranCatBalanceId;
import com.carddemo.posttran.domain.Transaction;
import com.carddemo.posttran.repository.TranCatBalanceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code 2000-POST-TRANSACTION}: builds the {@code TRAN-RECORD}, then applies
 * {@code 2700-UPDATE-TCATBAL} and {@code 2800-UPDATE-ACCOUNT-REC} to the in-memory records.
 *
 * <p>The account and category balance are mutated in place. Within a chunk they are JPA-managed
 * entities, so a later transaction in the same chunk that hits the same account or category sees
 * the updated balances - matching the record-at-a-time semantics of the COBOL program. A newly
 * created category balance is registered with the repository immediately for the same reason
 * (the COBOL {@code WRITE} in {@code 2700-A-CREATE-TCATBAL-REC} makes it visible to the next READ).
 */
@Component
public class TransactionPoster {

    private static final Logger log = LoggerFactory.getLogger(TransactionPoster.class);

    private final TranCatBalanceRepository tranCatBalanceRepository;
    private final Db2Timestamp db2Timestamp;

    public TransactionPoster(TranCatBalanceRepository tranCatBalanceRepository, Db2Timestamp db2Timestamp) {
        this.tranCatBalanceRepository = tranCatBalanceRepository;
        this.db2Timestamp = db2Timestamp;
    }

    public PostedTransaction post(DailyTransaction daily, CardXref xref, Account account) {
        Transaction tran = toTransaction(daily, db2Timestamp.now());

        TranCatBalanceId key = new TranCatBalanceId(xref.getAcctId(), daily.getTypeCd(), daily.getCatCd());
        Optional<TranCatBalance> existing = tranCatBalanceRepository.findById(key);
        boolean created = existing.isEmpty();
        TranCatBalance tcatbal;
        if (created) {
            log.info("TCATBAL record not found for key : {}.. Creating.", key);
            tcatbal = TranCatBalance.initialized(key);
            updateTranCatBalance(tcatbal, daily.getAmount());
            tcatbal = tranCatBalanceRepository.save(tcatbal);
        } else {
            tcatbal = existing.get();
            updateTranCatBalance(tcatbal, daily.getAmount());
        }

        updateAccount(account, daily.getAmount());

        return new PostedTransaction(daily, tran, account, tcatbal, created);
    }

    /** Field-by-field MOVEs at the top of {@code 2000-POST-TRANSACTION}, plus the DB2 processing timestamp. */
    static Transaction toTransaction(DailyTransaction d, String procTs) {
        Transaction t = new Transaction();
        t.setTranId(d.getTranId());
        t.setTypeCd(d.getTypeCd());
        t.setCatCd(d.getCatCd());
        t.setSource(d.getSource());
        t.setDescription(d.getDescription());
        t.setAmount(d.getAmount());
        t.setMerchantId(d.getMerchantId());
        t.setMerchantName(d.getMerchantName());
        t.setMerchantCity(d.getMerchantCity());
        t.setMerchantZip(d.getMerchantZip());
        t.setCardNum(d.getCardNum());
        t.setOrigTs(d.getOrigTs());
        t.setProcTs(procTs);
        return t;
    }

    /** {@code 2700-A-CREATE-TCATBAL-REC} / {@code 2700-B-UPDATE-TCATBAL-REC}: {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}. */
    static void updateTranCatBalance(TranCatBalance tcatbal, BigDecimal amount) {
        tcatbal.add(amount);
    }

    /** {@code 2800-UPDATE-ACCOUNT-REC}. */
    static void updateAccount(Account account, BigDecimal amount) {
        account.setCurrBal(account.getCurrBal().add(amount));
        if (amount.signum() >= 0) {
            account.setCurrCycCredit(account.getCurrCycCredit().add(amount));
        } else {
            account.setCurrCycDebit(account.getCurrCycDebit().add(amount));
        }
    }
}
