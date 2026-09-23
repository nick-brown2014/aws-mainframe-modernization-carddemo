package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.CardXref;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcCardXrefRepository implements CardXrefRepository {

    private static final RowMapper<CardXref> MAPPER = (rs, i) ->
            new CardXref(rs.getString("card_num"), rs.getLong("cust_id"), rs.getLong("acct_id"));

    private final JdbcTemplate jdbc;

    public JdbcCardXrefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CardXref> findByCardNum(String cardNum) {
        return jdbc.query("SELECT card_num, cust_id, acct_id FROM card_xref WHERE card_num = ?", MAPPER, cardNum)
                .stream().findFirst();
    }

    @Override
    public void insert(CardXref x) {
        jdbc.update("INSERT INTO card_xref (card_num, cust_id, acct_id) VALUES (?, ?, ?)",
                x.cardNum(), x.custId(), x.acctId());
    }
}
