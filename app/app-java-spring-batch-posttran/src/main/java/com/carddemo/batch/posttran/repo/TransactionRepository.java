package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.Transaction;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository {

    /** WRITE; a duplicate key is fatal (COBOL file status '22'). */
    void insert(Transaction tran);

    Optional<Transaction> findById(String tranId);

    List<Transaction> findAllOrderedByKey();
}
