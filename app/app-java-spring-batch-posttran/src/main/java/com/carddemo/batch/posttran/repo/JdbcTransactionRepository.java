package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTransactionRepository implements TransactionRepository {

    private static final String COLUMNS = "tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt, merchant_id, "
            + "merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts";

    private static final RowMapper<Transaction> MAPPER = (rs, i) -> new Transaction(
            rs.getString("tran_id"), rs.getString("tran_type_cd"), rs.getInt("tran_cat_cd"), rs.getString("tran_source"),
            rs.getString("tran_desc"), rs.getBigDecimal("tran_amt").setScale(2), rs.getLong("merchant_id"),
            rs.getString("merchant_name"), rs.getString("merchant_city"), rs.getString("merchant_zip"),
            rs.getString("card_num"), rs.getString("orig_ts"), rs.getString("proc_ts"));

    private final JdbcTemplate jdbc;

    public JdbcTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Transaction t) {
        jdbc.update("INSERT INTO transaction (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                t.tranId(), t.tranTypeCd(), t.tranCatCd(), t.tranSource(), t.tranDesc(), t.tranAmt(), t.merchantId(),
                t.merchantName(), t.merchantCity(), t.merchantZip(), t.cardNum(), t.origTs(), t.procTs());
    }

    @Override
    public Optional<Transaction> findById(String tranId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transaction WHERE tran_id = ?", MAPPER, tranId).stream().findFirst();
    }

    @Override
    public List<Transaction> findAllOrderedByKey() {
        return jdbc.query("SELECT " + COLUMNS + " FROM transaction ORDER BY tran_id", MAPPER);
    }
}
