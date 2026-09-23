package com.carddemo.posttran;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostingRepository {
    private final JdbcTemplate jdbc;

    public PostingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CardXref> card(String cardNum) {
        return jdbc.query("SELECT * FROM card_xref WHERE card_num = ?",
                (rs, row) -> new CardXref(rs.getString("card_num"), rs.getString("cust_id"),
                        rs.getString("acct_id"), rs.getString("filler")), cardNum).stream().findFirst();
    }

    public Optional<Account> account(String acctId) {
        return jdbc.query("SELECT * FROM account WHERE acct_id = ?",
                (rs, row) -> new Account(rs.getString("acct_id"), rs.getString("active_status"),
                        rs.getBigDecimal("curr_bal"), rs.getBigDecimal("credit_limit"),
                        rs.getBigDecimal("cash_credit_limit"), rs.getString("open_date"),
                        rs.getString("expiration_date"), rs.getString("reissue_date"),
                        rs.getBigDecimal("curr_cyc_credit"), rs.getBigDecimal("curr_cyc_debit"),
                        rs.getString("addr_zip"), rs.getString("group_id"), rs.getString("filler")),
                acctId).stream().findFirst();
    }

    public Optional<TranCatBalance> balance(String acctId, String typeCd, String catCd) {
        return jdbc.query("SELECT * FROM tran_cat_balance WHERE acct_id = ? AND type_cd = ? AND cat_cd = ?",
                (rs, row) -> new TranCatBalance(rs.getString("acct_id"), rs.getString("type_cd"),
                        rs.getString("cat_cd"), rs.getBigDecimal("balance"), rs.getString("filler")),
                acctId, typeCd, catCd).stream().findFirst();
    }

    public void saveBalance(TranCatBalance balance, boolean exists) {
        int rows = exists
                ? jdbc.update("UPDATE tran_cat_balance SET balance = ? WHERE acct_id = ? AND type_cd = ? AND cat_cd = ?",
                        balance.balance(), balance.acctId(), balance.typeCd(), balance.catCd())
                : jdbc.update("INSERT INTO tran_cat_balance (acct_id, type_cd, cat_cd, balance, filler) VALUES (?, ?, ?, ?, ?)",
                        balance.acctId(), balance.typeCd(), balance.catCd(), balance.balance(), balance.filler());
        requireOneRow(rows);
    }

    public void updateAccount(Account account) {
        requireOneRow(jdbc.update("UPDATE account SET curr_bal = ?, curr_cyc_credit = ?, curr_cyc_debit = ? WHERE acct_id = ?",
                account.currBal(), account.currCycCredit(), account.currCycDebit(), account.id()));
    }

    public void insertTransaction(Transaction transaction) {
        requireOneRow(jdbc.update("""
                INSERT INTO transaction_master (tran_id, type_cd, cat_cd, source, description, amount,
                    merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, filler)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transaction.id(), transaction.typeCd(), transaction.catCd(), transaction.source(),
                transaction.description(), transaction.amount(), transaction.merchantId(),
                transaction.merchantName(), transaction.merchantCity(), transaction.merchantZip(),
                transaction.cardNum(), transaction.origTs(), transaction.procTs(), transaction.filler()));
    }

    public void insertCard(CardXref card) {
        requireOneRow(jdbc.update("INSERT INTO card_xref (card_num, cust_id, acct_id, filler) VALUES (?, ?, ?, ?)",
                card.cardNum(), card.custId(), card.acctId(), card.filler()));
    }

    public void insertAccount(Account account) {
        requireOneRow(jdbc.update("""
                INSERT INTO account (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit,
                    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit,
                    addr_zip, group_id, filler)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, account.id(), account.activeStatus(), account.currBal(), account.creditLimit(),
                account.cashCreditLimit(), account.openDate(), account.expirationDate(),
                account.reissueDate(), account.currCycCredit(), account.currCycDebit(),
                account.addrZip(), account.groupId(), account.filler()));
    }

    private static void requireOneRow(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Expected to write one record, wrote " + rows);
        }
    }
}
