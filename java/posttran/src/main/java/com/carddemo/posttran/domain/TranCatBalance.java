package com.carddemo.posttran.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** VSAM TCATBALF record (copybook CVTRA01Y, {@code TRAN-CAT-BAL-RECORD}, RECLN 50). */
@Entity
@Table(name = "TRAN_CAT_BAL")
public class TranCatBalance {

    @EmbeddedId
    private TranCatBalanceId id;

    @Column(name = "TRAN_CAT_BAL", precision = 11, scale = 2, nullable = false)
    private BigDecimal balance = BigDecimal.ZERO.setScale(2);

    protected TranCatBalance() {
    }

    public TranCatBalance(TranCatBalanceId id, BigDecimal balance) {
        this.id = id;
        this.balance = balance;
    }

    /** {@code INITIALIZE TRAN-CAT-BAL-RECORD} followed by moving in the key: a zero balance. */
    public static TranCatBalance initialized(TranCatBalanceId id) {
        return new TranCatBalance(id, BigDecimal.ZERO.setScale(2));
    }

    public TranCatBalanceId getId() {
        return id;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public void add(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }
}
