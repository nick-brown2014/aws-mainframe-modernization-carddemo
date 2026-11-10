package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.TranCategoryBalance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TranCategoryBalanceRepository {

    Optional<TranCategoryBalance> findByKey(long acctId, String tranTypeCd, int tranCatCd);

    /** WRITE. */
    void insert(TranCategoryBalance bal);

    /** REWRITE; returns rows affected. */
    int updateBalance(long acctId, String tranTypeCd, int tranCatCd, BigDecimal newBal);

    /** All rows in key order (VSAM key sequence), for extracts / parity. */
    List<TranCategoryBalance> findAllOrderedByKey();
}
