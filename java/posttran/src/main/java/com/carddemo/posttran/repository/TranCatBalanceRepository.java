package com.carddemo.posttran.repository;

import com.carddemo.posttran.domain.TranCatBalance;
import com.carddemo.posttran.domain.TranCatBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;

/** TCATBALF (VSAM KSDS keyed by {@code TRAN-CAT-KEY}). */
public interface TranCatBalanceRepository extends JpaRepository<TranCatBalance, TranCatBalanceId> {
}
