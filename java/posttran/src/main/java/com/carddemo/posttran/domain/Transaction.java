package com.carddemo.posttran.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** VSAM TRANFILE record (copybook CVTRA05Y, {@code TRAN-RECORD}, RECLN 350) keyed by transaction id. */
@Entity
@Table(name = "TRANSACT")
public class Transaction {

    @Id
    @Column(name = "TRAN_ID", length = 16, nullable = false)
    private String tranId;

    @Column(name = "TRAN_TYPE_CD", length = 2)
    private String typeCd;

    @Column(name = "TRAN_CAT_CD")
    private int catCd;

    @Column(name = "TRAN_SOURCE", length = 10)
    private String source;

    @Column(name = "TRAN_DESC", length = 100)
    private String description;

    @Column(name = "TRAN_AMT", precision = 11, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "TRAN_MERCHANT_ID")
    private long merchantId;

    @Column(name = "TRAN_MERCHANT_NAME", length = 50)
    private String merchantName;

    @Column(name = "TRAN_MERCHANT_CITY", length = 50)
    private String merchantCity;

    @Column(name = "TRAN_MERCHANT_ZIP", length = 10)
    private String merchantZip;

    @Column(name = "TRAN_CARD_NUM", length = 16)
    private String cardNum;

    @Column(name = "TRAN_ORIG_TS", length = 26)
    private String origTs;

    @Column(name = "TRAN_PROC_TS", length = 26)
    private String procTs;

    public String getTranId() {
        return tranId;
    }

    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    public String getTypeCd() {
        return typeCd;
    }

    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    public int getCatCd() {
        return catCd;
    }

    public void setCatCd(int catCd) {
        this.catCd = catCd;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(long merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantCity() {
        return merchantCity;
    }

    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    public String getMerchantZip() {
        return merchantZip;
    }

    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    public String getCardNum() {
        return cardNum;
    }

    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    public String getOrigTs() {
        return origTs;
    }

    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    public String getProcTs() {
        return procTs;
    }

    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }
}
