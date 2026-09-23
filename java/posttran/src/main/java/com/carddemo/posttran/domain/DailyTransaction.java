package com.carddemo.posttran.domain;

import java.math.BigDecimal;

/**
 * One 350-byte DALYTRAN record (copybook CVTRA06Y, {@code DALYTRAN-RECORD}).
 *
 * <p>{@link #rawRecord} keeps the original 350 characters so a reject can echo the record
 * verbatim, exactly as {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} does.
 */
public class DailyTransaction {

    public static final int RECORD_LENGTH = 350;

    private String rawRecord;
    private String tranId;
    private String typeCd;
    private int catCd;
    private String source;
    private String description;
    private BigDecimal amount;
    private long merchantId;
    private String merchantName;
    private String merchantCity;
    private String merchantZip;
    private String cardNum;
    private String origTs;
    private String procTs;

    public String getRawRecord() {
        return rawRecord;
    }

    public void setRawRecord(String rawRecord) {
        this.rawRecord = rawRecord;
    }

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

    /** {@code DALYTRAN-ORIG-TS (1:10)} - the date portion of the origination timestamp. */
    public String getOrigDate() {
        String ts = origTs == null ? "" : origTs;
        return ts.length() >= 10 ? ts.substring(0, 10) : ts;
    }

    @Override
    public String toString() {
        return "DailyTransaction[" + tranId + " card=" + cardNum + " amt=" + amount + "]";
    }
}
