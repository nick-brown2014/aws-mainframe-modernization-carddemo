package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.TranCategoryBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTranCategoryBalanceRepository implements TranCategoryBalanceRepository {

    private static final RowMapper<TranCategoryBalance> MAPPER = (rs, i) -> new TranCategoryBalance(
            rs.getLong("acct_id"), rs.getString("tran_type_cd"), rs.getInt("tran_cat_cd"),
            rs.getBigDecimal("tran_cat_bal").setScale(2));

    private final JdbcTemplate jdbc;

    public JdbcTranCategoryBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TranCategoryBalance> findByKey(long acctId, String tranTypeCd, int tranCatCd) {
        return jdbc.query("SELECT acct_id, tran_type_cd, tran_cat_cd, tran_cat_bal FROM tran_cat_bal "
                        + "WHERE acct_id = ? AND tran_type_cd = ? AND tran_cat_cd = ?", MAPPER, acctId, tranTypeCd, tranCatCd)
                .stream().findFirst();
    }

    @Override
    public void insert(TranCategoryBalance b) {
        jdbc.update("INSERT INTO tran_cat_bal (acct_id, tran_type_cd, tran_cat_cd, tran_cat_bal) VALUES (?, ?, ?, ?)",
                b.acctId(), b.tranTypeCd(), b.tranCatCd(), b.tranCatBal());
    }

    @Override
    public int updateBalance(long acctId, String tranTypeCd, int tranCatCd, BigDecimal newBal) {
        return jdbc.update("UPDATE tran_cat_bal SET tran_cat_bal = ? WHERE acct_id = ? AND tran_type_cd = ? AND tran_cat_cd = ?",
                newBal, acctId, tranTypeCd, tranCatCd);
    }

    @Override
    public List<TranCategoryBalance> findAllOrderedByKey() {
        return jdbc.query("SELECT acct_id, tran_type_cd, tran_cat_cd, tran_cat_bal FROM tran_cat_bal "
                + "ORDER BY acct_id, tran_type_cd, tran_cat_cd", MAPPER);
    }
}
