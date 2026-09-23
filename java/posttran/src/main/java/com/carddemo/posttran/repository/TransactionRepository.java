package com.carddemo.posttran.repository;

import com.carddemo.posttran.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

/** TRANFILE (VSAM KSDS keyed by {@code TRAN-ID}). */
public interface TransactionRepository extends JpaRepository<Transaction, String> {
}
