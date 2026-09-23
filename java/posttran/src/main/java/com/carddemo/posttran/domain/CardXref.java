package com.carddemo.posttran.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** VSAM XREFFILE record (copybook CVACT03Y, {@code CARD-XREF-RECORD}, RECLN 50) keyed by card number. */
@Entity
@Table(name = "CARD_XREF")
public class CardXref {

    @Id
    @Column(name = "XREF_CARD_NUM", length = 16, nullable = false)
    private String cardNum;

    @Column(name = "XREF_CUST_ID", nullable = false)
    private long custId;

    @Column(name = "XREF_ACCT_ID", nullable = false)
    private long acctId;

    protected CardXref() {
    }

    public CardXref(String cardNum, long custId, long acctId) {
        this.cardNum = cardNum;
        this.custId = custId;
        this.acctId = acctId;
    }

    public String getCardNum() {
        return cardNum;
    }

    public long getCustId() {
        return custId;
    }

    public long getAcctId() {
        return acctId;
    }
}
