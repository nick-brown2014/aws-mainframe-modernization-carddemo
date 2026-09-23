package com.carddemo.batch.posttran.model;

/** CVACT03Y - CARD-XREF-RECORD (50 bytes, key = card number). */
public record CardXref(String cardNum, long custId, long acctId) {

    public static final int RECORD_LENGTH = 50;
}
