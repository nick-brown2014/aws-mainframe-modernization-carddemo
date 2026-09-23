package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.Account;

import java.math.BigDecimal;
import java.util.Optional;

public interface AccountRepository {

    /** ACCTFILE random READ. */
    Optional<Account> findById(long acctId);

    /** SELECT ... FOR UPDATE (I-O semantics). */
    Optional<Account> findByIdForUpdate(long acctId);

    /** REWRITE of the three mutable columns only; returns rows affected. */
    int updateBalances(long acctId, BigDecimal currBal, BigDecimal currCycCredit, BigDecimal currCycDebit);

    void insert(Account account);
}
