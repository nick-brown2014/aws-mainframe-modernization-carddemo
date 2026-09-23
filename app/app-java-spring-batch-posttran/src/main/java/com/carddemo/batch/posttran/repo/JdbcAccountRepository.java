package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.Account;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final String COLUMNS = "acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, "
            + "expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id";

    static final RowMapper<Account> MAPPER = (rs, i) -> new Account(
            rs.getLong("acct_id"),
            rs.getString("active_status"),
            rs.getBigDecimal("curr_bal").setScale(2),
            rs.getBigDecimal("credit_limit").setScale(2),
            rs.getBigDecimal("cash_credit_limit").setScale(2),
            rs.getString("open_date"),
            rs.getString("expiration_date"),
            rs.getString("reissue_date"),
            rs.getBigDecimal("curr_cyc_credit").setScale(2),
            rs.getBigDecimal("curr_cyc_debit").setScale(2),
            rs.getString("addr_zip"),
            rs.getString("group_id"));

    private final JdbcTemplate jdbc;

    public JdbcAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> findById(long acctId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM account WHERE acct_id = ?", MAPPER, acctId).stream().findFirst();
    }

    @Override
    public Optional<Account> findByIdForUpdate(long acctId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM account WHERE acct_id = ? FOR UPDATE", MAPPER, acctId)
                .stream().findFirst();
    }

    @Override
    public int updateBalances(long acctId, BigDecimal currBal, BigDecimal currCycCredit, BigDecimal currCycDebit) {
        return jdbc.update("UPDATE account SET curr_bal = ?, curr_cyc_credit = ?, curr_cyc_debit = ? WHERE acct_id = ?",
                currBal, currCycCredit, currCycDebit, acctId);
    }

    @Override
    public void insert(Account a) {
        jdbc.update("INSERT INTO account (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.acctId(), a.activeStatus(), a.currBal(), a.creditLimit(), a.cashCreditLimit(), a.openDate(),
                a.expirationDate(), a.reissueDate(), a.currCycCredit(), a.currCycDebit(), a.addrZip(), a.groupId());
    }
}
