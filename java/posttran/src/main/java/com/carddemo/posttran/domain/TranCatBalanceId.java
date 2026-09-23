package com.carddemo.posttran.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** {@code TRAN-CAT-KEY} (copybook CVTRA01Y): account id + transaction type code + category code. */
@Embeddable
public class TranCatBalanceId implements Serializable {

    @Column(name = "TRANCAT_ACCT_ID", nullable = false)
    private long acctId;

    @Column(name = "TRANCAT_TYPE_CD", length = 2, nullable = false)
    private String typeCd;

    @Column(name = "TRANCAT_CD", nullable = false)
    private int catCd;

    protected TranCatBalanceId() {
    }

    public TranCatBalanceId(long acctId, String typeCd, int catCd) {
        this.acctId = acctId;
        this.typeCd = typeCd;
        this.catCd = catCd;
    }

    public long getAcctId() {
        return acctId;
    }

    public String getTypeCd() {
        return typeCd;
    }

    public int getCatCd() {
        return catCd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranCatBalanceId other)) {
            return false;
        }
        return acctId == other.acctId && catCd == other.catCd && Objects.equals(typeCd, other.typeCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(acctId, typeCd, catCd);
    }

    @Override
    public String toString() {
        return String.format("%011d%s%04d", acctId, typeCd, catCd);
    }
}
