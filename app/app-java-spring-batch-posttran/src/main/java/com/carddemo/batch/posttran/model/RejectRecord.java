package com.carddemo.batch.posttran.model;

import com.carddemo.batch.posttran.validation.ValidationFailure;

/** 430-byte reject record: original 350-byte DALYTRAN record + 80-byte validation trailer. */
public record RejectRecord(String rawRecord, ValidationFailure failure) {

    public static final int RECORD_LENGTH = 430;
}
