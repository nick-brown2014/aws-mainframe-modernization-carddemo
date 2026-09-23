package com.carddemo.posttran.repository;

import com.carddemo.posttran.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/** ACCTFILE (VSAM KSDS keyed by {@code ACCT-ID}). */
public interface AccountRepository extends JpaRepository<Account, Long> {
}
