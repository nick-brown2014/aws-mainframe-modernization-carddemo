package com.carddemo.posttran.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** VSAM ACCTFILE record (copybook CVACT01Y, {@code ACCOUNT-RECORD}, RECLN 300) keyed by account id. */
@Entity
@Table(name = "ACCOUNT")
public class Account {

    @Id
    @Column(name = "ACCT_ID", nullable = false)
    private long acctId;

    @Column(name = "ACCT_ACTIVE_STATUS", length = 1)
    private String activeStatus;

    @Column(name = "ACCT_CURR_BAL", precision = 12, scale = 2, nullable = false)
    private BigDecimal currBal = zero();

    @Column(name = "ACCT_CREDIT_LIMIT", precision = 12, scale = 2, nullable = false)
    private BigDecimal creditLimit = zero();

    @Column(name = "ACCT_CASH_CREDIT_LIMIT", precision = 12, scale = 2, nullable = false)
    private BigDecimal cashCreditLimit = zero();

    @Column(name = "ACCT_OPEN_DATE", length = 10)
    private String openDate;

    @Column(name = "ACCT_EXPIRAION_DATE", length = 10)
    private String expirationDate;

    @Column(name = "ACCT_REISSUE_DATE", length = 10)
    private String reissueDate;

    @Column(name = "ACCT_CURR_CYC_CREDIT", precision = 12, scale = 2, nullable = false)
    private BigDecimal currCycCredit = zero();

    @Column(name = "ACCT_CURR_CYC_DEBIT", precision = 12, scale = 2, nullable = false)
    private BigDecimal currCycDebit = zero();

    @Column(name = "ACCT_ADDR_ZIP", length = 10)
    private String addrZip;

    @Column(name = "ACCT_GROUP_ID", length = 10)
    private String groupId;

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    public long getAcctId() {
        return acctId;
    }

    public void setAcctId(long acctId) {
        this.acctId = acctId;
    }

    public String getActiveStatus() {
        return activeStatus;
    }

    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    public BigDecimal getCurrBal() {
        return currBal;
    }

    public void setCurrBal(BigDecimal currBal) {
        this.currBal = currBal;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    public String getOpenDate() {
        return openDate;
    }

    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    public String getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    public String getReissueDate() {
        return reissueDate;
    }

    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    public void setCurrCycCredit(BigDecimal currCycCredit) {
        this.currCycCredit = currCycCredit;
    }

    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    public void setCurrCycDebit(BigDecimal currCycDebit) {
        this.currCycDebit = currCycDebit;
    }

    public String getAddrZip() {
        return addrZip;
    }

    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
}
