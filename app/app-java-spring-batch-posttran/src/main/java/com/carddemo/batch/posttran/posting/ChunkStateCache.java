package com.carddemo.batch.posttran.posting;

import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.TranCatKey;
import com.carddemo.batch.posttran.model.TranCategoryBalance;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-chunk write-behind cache (spec section 6.4 / R4). Items processed earlier in the same chunk have
 * not been written yet, so later items must see their computed state here rather than in the database.
 * Cleared after every chunk (commit or rollback).
 */
@Component
public class ChunkStateCache implements ChunkListener {

    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<TranCatKey, TranCategoryBalance> balances = new HashMap<>();

    public Optional<Account> account(long acctId) {
        return Optional.ofNullable(accounts.get(acctId));
    }

    public void put(Account account) {
        accounts.put(account.acctId(), account);
    }

    public Optional<TranCategoryBalance> balance(TranCatKey key) {
        return Optional.ofNullable(balances.get(key));
    }

    public void put(TranCategoryBalance balance) {
        balances.put(balance.key(), balance);
    }

    public void clear() {
        accounts.clear();
        balances.clear();
    }

    @Override
    public void beforeChunk(ChunkContext context) {
        clear();
    }

    @Override
    public void afterChunk(ChunkContext context) {
        clear();
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        clear();
    }
}
